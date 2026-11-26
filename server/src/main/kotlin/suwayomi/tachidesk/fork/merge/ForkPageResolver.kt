package suwayomi.tachidesk.fork.merge

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.fork.ForkFeatureAvailability.Feature
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.ForkMergePolicy
import suwayomi.tachidesk.fork.database.ForkChapterFallbackTable
import suwayomi.tachidesk.fork.database.ForkChapterLinkTable
import suwayomi.tachidesk.fork.database.ForkChapterPartTable
import suwayomi.tachidesk.fork.require
import suwayomi.tachidesk.manga.impl.chapter.getChapterDownloadReady
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.database.dbTransaction
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import suwayomi.tachidesk.manga.impl.Page as PageImpl

/** D8: a virtual chapter that cannot be resolved is an explicit, typed error, never an empty page list. */
class ForkResolutionException(
    val code: String,
    message: String,
) : IllegalStateException("$code: $message")

/** One way to read a virtual chapter: an ordered list of page ranges of real source chapters. */
data class ResolutionCandidate(
    val sourceMangaId: Int,
    val ranges: List<PartRange>,
    /** True when the candidate is a whole chapter (range = all its pages). */
    val wholeChapter: Boolean,
)

/**
 * F7: the single resolution function used by reader, downloader and page lists. Upstream code calls
 * [isVirtualUrl] on rows it already loaded (string check, no SQL), so stock reading is unchanged.
 */
object ForkPageResolver {
    private val logger = KotlinLogging.logger {}

    @Volatile
    internal var gate: ForkGate = ForkGate.production

    @Volatile
    internal var policy: () -> ForkMergePolicy = ForkMergePolicy::fromConfig

    /** virtual chapter id -> candidate that last resolved successfully (fallback memory). */
    private val chosen = ConcurrentHashMap<Int, ResolutionCandidate>()

    fun isVirtualUrl(url: String): Boolean = url.startsWith(ForkMergePlanner.VIRTUAL_URL_PREFIX)

    /** Ordered candidates (primary, then fallbacks) for a virtual chapter. */
    fun candidates(mergedChapterId: Int): List<ResolutionCandidate> =
        dbTransaction {
            val link =
                ForkChapterLinkTable
                    .selectAll()
                    .where { ForkChapterLinkTable.mergedChapterId eq mergedChapterId }
                    .firstOrNull()
                    ?: throw ForkResolutionException("LINK_MISSING", "Virtual chapter $mergedChapterId has no link")
            val kind = runCatching { LinkKind.valueOf(link[ForkChapterLinkTable.linkKind]) }.getOrDefault(LinkKind.CHAPTER)
            val status = runCatching { LinkStatus.valueOf(link[ForkChapterLinkTable.status]) }.getOrDefault(LinkStatus.OK)
            if (status == LinkStatus.NEEDS_REVIEW) {
                throw ForkResolutionException("NEEDS_REVIEW", "The source page count changed; this chapter needs review")
            }
            val sourceMangaId = link[ForkChapterLinkTable.sourceMangaId]
            when (kind) {
                LinkKind.PARTS -> {
                    val parts =
                        ForkChapterPartTable
                            .selectAll()
                            .where { ForkChapterPartTable.mergedChapterId eq mergedChapterId }
                            .orderBy(ForkChapterPartTable.partIndex to SortOrder.ASC)
                            .toList()
                    if (parts.any { it[ForkChapterPartTable.sourceChapterId] == null }) {
                        throw ForkResolutionException("SOURCE_MISSING", "A part's source chapter no longer exists")
                    }
                    listOf(
                        ResolutionCandidate(
                            sourceMangaId,
                            parts.map {
                                PartRange(
                                    it[ForkChapterPartTable.sourceChapterId]!!,
                                    it[ForkChapterPartTable.pageStart],
                                    it[ForkChapterPartTable.pageEnd],
                                    it[ForkChapterPartTable.sourcePageCountSnapshot],
                                )
                            },
                            wholeChapter = false,
                        ),
                    )
                }

                LinkKind.PART -> {
                    val sourceChapterId =
                        link[ForkChapterLinkTable.sourceChapterId]
                            ?: throw ForkResolutionException("SOURCE_MISSING", "The part's source chapter no longer exists")
                    listOf(
                        ResolutionCandidate(
                            sourceMangaId,
                            listOf(
                                PartRange(
                                    sourceChapterId,
                                    link[ForkChapterLinkTable.pageStart] ?: 0,
                                    link[ForkChapterLinkTable.pageEnd] ?: 0,
                                    link[ForkChapterLinkTable.sourcePageCountSnapshot],
                                ),
                            ),
                            wholeChapter = false,
                        ),
                    )
                }

                LinkKind.CHAPTER -> {
                    val primary =
                        link[ForkChapterLinkTable.sourceChapterId]?.let {
                            ResolutionCandidate(sourceMangaId, listOf(PartRange(it, 0, -1, -1)), wholeChapter = true)
                        }
                    val fallbacks =
                        if (policy().fallbackSourcesEnabled) {
                            ForkChapterFallbackTable
                                .selectAll()
                                .where { ForkChapterFallbackTable.linkId eq link[ForkChapterLinkTable.id].value }
                                .orderBy(ForkChapterFallbackTable.rank to SortOrder.ASC)
                                .mapNotNull { row ->
                                    row[ForkChapterFallbackTable.sourceChapterId]?.let {
                                        ResolutionCandidate(
                                            row[ForkChapterFallbackTable.sourceMangaId],
                                            listOf(PartRange(it, 0, -1, -1)),
                                            wholeChapter = true,
                                        )
                                    }
                                }
                        } else {
                            emptyList()
                        }
                    listOfNotNull(primary) + fallbacks
                }
            }.ifEmpty { throw ForkResolutionException("SOURCE_MISSING", "No source chapter is available for this virtual chapter") }
        }

    /**
     * Hook for `getChapterDownloadReady`: returns null for non-virtual rows (no SQL); otherwise prepares the
     * source chapter(s), sets the virtual page count and returns the virtual chapter. Progress of the virtual
     * row is never clamped here (D29).
     */
    suspend fun virtualDownloadReady(chapterEntry: ResultRow): ChapterDataClass? {
        if (!isVirtualUrl(chapterEntry[ChapterTable.url])) return null
        gate.require(Feature.MERGE)
        ForkMergedSource.ensureRegistered()
        val virtualId = chapterEntry[ChapterTable.id].value
        val errors = mutableListOf<String>()
        for (candidate in candidates(virtualId)) {
            try {
                val pageCount = prepare(candidate)
                chosen[virtualId] = candidate
                dbTransaction {
                    ChapterTable.update({ ChapterTable.id eq virtualId }) { it[ChapterTable.pageCount] = pageCount }
                }
                return dbTransaction {
                    ChapterTable.toDataClass(ChapterTable.selectAll().where { ChapterTable.id eq virtualId }.first())
                }
            } catch (e: ForkResolutionException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Virtual chapter $virtualId: source ${candidate.sourceMangaId} failed, trying next fallback" }
                errors += "${candidate.sourceMangaId}: ${e.message}"
            }
        }
        throw ForkResolutionException("SOURCE_UNAVAILABLE", "Every source failed: ${errors.joinToString("; ")}")
    }

    /** Readies every range's source chapter and returns the virtual page count; validates part snapshots (D7). */
    private suspend fun prepare(candidate: ResolutionCandidate): Int {
        if (candidate.wholeChapter) {
            val source = getChapterDownloadReady(chapterId = candidate.ranges.single().sourceChapterId)
            if (source.pageCount <= 0) throw IllegalStateException("source chapter has no pages")
            return source.pageCount
        }
        var total = 0
        candidate.ranges.forEach { range ->
            val source = getChapterDownloadReady(chapterId = range.sourceChapterId)
            if (range.sourcePageCount >= 0 && source.pageCount != range.sourcePageCount) {
                ForkChapterPartService.markNeedsReview(range.sourceChapterId)
                throw ForkResolutionException("NEEDS_REVIEW", "Source chapter ${range.sourceChapterId} now has ${source.pageCount} pages")
            }
            if (range.pageEnd >= source.pageCount || range.pageStart < 0 || range.pageStart > range.pageEnd) {
                throw ForkResolutionException(
                    "RANGE_INVALID",
                    "Page range ${range.pageStart}-${range.pageEnd} is outside the source chapter",
                )
            }
            total += range.pageEnd - range.pageStart + 1
        }
        return total
    }

    /** Virtual page index -> (source manga id, source chapter id, source page index). */
    fun mapPage(
        virtualChapterId: Int,
        index: Int,
    ): Triple<Int, Int, Int> {
        val candidate = chosen[virtualChapterId] ?: candidates(virtualChapterId).first()
        if (candidate.wholeChapter) {
            return Triple(candidate.sourceMangaId, candidate.ranges.single().sourceChapterId, index)
        }
        val (chapterId, page) =
            suwayomi.tachidesk.fork.progress.PartMapping(candidate.ranges).toSource(index)
                ?: throw ForkResolutionException("RANGE_INVALID", "Page $index is outside this chapter")
        val sourceMangaId = ForkMergeRepository.chaptersOfIds(listOf(chapterId))[chapterId] ?: candidate.sourceMangaId
        return Triple(sourceMangaId, chapterId, page)
    }

    /**
     * Hook for `Page.getPageImage`: called only when the already-loaded chapter row is virtual. Delegates to
     * the stock path of the real source chapter, so source downloads and caches are reused (offline read).
     */
    suspend fun getPageImage(
        virtualChapterId: Int,
        index: Int,
        progressFlow: ((StateFlow<Int>) -> Unit)?,
    ): Pair<InputStream, String> {
        gate.require(Feature.MERGE)
        if (chosen[virtualChapterId] == null) {
            val entry = dbTransaction { ChapterTable.selectAll().where { ChapterTable.id eq virtualChapterId }.first() }
            virtualDownloadReady(entry)
        }
        val (sourceMangaId, sourceChapterId, sourceIndex) = mapPage(virtualChapterId, index)
        return PageImpl.getPageImage(
            mangaId = sourceMangaId,
            chapterId = sourceChapterId,
            index = sourceIndex,
            progressFlow = progressFlow,
        )
    }

    /** Used by [ForkMergedSource.getPageList]. */
    suspend fun pageListForVirtualUrl(url: String): List<Page> {
        gate.require(Feature.MERGE)
        val entry =
            dbTransaction { ChapterTable.selectAll().where { ChapterTable.url eq url }.firstOrNull() }
                ?: throw ForkResolutionException("LINK_MISSING", "Unknown virtual chapter")
        val virtualId = entry[ChapterTable.id].value
        val candidate = candidates(virtualId).first()
        val pages = mutableListOf<Page>()
        candidate.ranges.forEach { range ->
            val chapterRow = dbTransaction { ChapterTable.selectAll().where { ChapterTable.id eq range.sourceChapterId }.first() }
            val mangaRow = dbTransaction { MangaTable.selectAll().where { MangaTable.id eq chapterRow[ChapterTable.manga].value }.first() }
            val source = GetSource.getSourceOrStub(mangaRow[MangaTable.sourceReference])
            val sourcePages =
                source.getPageList(
                    SChapter.create().apply {
                        this.url = chapterRow[ChapterTable.url]
                        name = chapterRow[ChapterTable.name]
                        scanlator = chapterRow[ChapterTable.scanlator]
                        chapter_number = chapterRow[ChapterTable.chapter_number]
                        date_upload = chapterRow[ChapterTable.date_upload]
                        memo = chapterRow[ChapterTable.memo]
                    },
                )
            val slice = if (range.pageEnd < 0) sourcePages else sourcePages.subList(range.pageStart, range.pageEnd + 1)
            pages += slice
        }
        return pages.mapIndexed { index, page -> Page(index, page.url, page.imageUrl, page.uri) }
    }

    fun forget(virtualChapterId: Int) {
        chosen.remove(virtualChapterId)
    }
}
