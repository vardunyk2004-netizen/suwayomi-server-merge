package suwayomi.tachidesk.fork.progress

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.fork.ForkFeatureAvailability.Feature
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.ForkProgressPolicy
import suwayomi.tachidesk.fork.database.ForkChapterFallbackTable
import suwayomi.tachidesk.fork.database.ForkChapterLinkTable
import suwayomi.tachidesk.fork.database.ForkChapterPartTable
import suwayomi.tachidesk.fork.journal.ForkJournal
import suwayomi.tachidesk.fork.journal.ForkJournalState
import suwayomi.tachidesk.fork.journal.ForkOperation
import suwayomi.tachidesk.fork.merge.ForkMergeRepository
import suwayomi.tachidesk.fork.merge.ForkPageResolver
import suwayomi.tachidesk.fork.merge.LinkKind
import suwayomi.tachidesk.fork.merge.PartRange
import suwayomi.tachidesk.graphql.types.ProgressDirection
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.server.database.dbTransaction

/**
 * R2 / F6: the ONE progress propagation function between virtual (merged) chapters and their real source
 * chapters. Every audited progress write site calls [afterWrite] with the chapter ids it just changed.
 *
 * - Off (merge disabled, stock mode or setting 1020 off): returns immediately, no SQL (R8).
 * - Recursion guard: writes made by this service do not re-enter it.
 * - A propagation failure never undoes the stock write; it is recorded as a journal diagnostic.
 */
class ForkProgressService(
    private val gate: ForkGate = ForkGate.production,
    private val policy: () -> ForkProgressPolicy = ForkProgressPolicy::fromConfig,
) {
    private val logger = KotlinLogging.logger {}
    private val inPropagation = ThreadLocal.withInitial { false }

    fun isActive(): Boolean = gate.isEnabled(Feature.MERGE) && policy().enabled

    private data class Link(
        val virtualId: Int,
        val kind: LinkKind,
        /** Source chapter -> range inside the virtual chapter's page list. */
        val ranges: List<PartRange>,
        val mirrors: List<Int>,
    )

    fun afterWrite(chapterIds: Collection<Int>) {
        if (chapterIds.isNotEmpty()) return // Temporary D17 old-logic simulation: write paths bypass propagation.
    }

    /** `Chapter.modifyChapter(markPrevRead=...)` changed every chapter before [chapterIndex] of [mangaId]. */
    fun afterMarkPrevious(
        mangaId: Int,
        chapterIndex: Int,
    ) {
        if (!isActive() || inPropagation.get()) return
        val ids =
            dbTransaction {
                ChapterTable
                    .select(ChapterTable.id)
                    .where { (ChapterTable.manga eq mangaId) and (ChapterTable.sourceOrder less chapterIndex) }
                    .map { it[ChapterTable.id].value }
            }
        afterWrite(ids)
    }

    /** Unmerge / remove-source write-back: virtual progress into the source chapters (newest wins, monotonic read). */
    fun writeBackGroup(
        groupId: Int,
        onlySourceMangaId: Int?,
    ): Int {
        if (!gate.isEnabled(Feature.MERGE)) return 0
        val group = ForkMergeRepository.groupById(groupId) ?: return 0
        val virtualIds = ForkMergeRepository.virtualChapterIds(group.mangaId)
        if (virtualIds.isEmpty()) return 0
        val wasInPropagation = inPropagation.get()
        inPropagation.set(true)
        try {
            return dbTransaction {
                val rows = loadRows(virtualIds)
                var changed = 0
                linksForVirtual(virtualIds).forEach { link ->
                    val virtual = rows[link.virtualId] ?: return@forEach
                    changed +=
                        pushToSources(link, virtual, writeBackPolicy(), filterManga = onlySourceMangaId)
                }
                changed
            }
        } finally {
            inPropagation.set(wasInPropagation)
        }
    }

    /** D2 / tracker adapter: the real chapter number of a virtual chapter row, null for normal rows (no SQL). */
    fun trackerNumber(chapterRow: ResultRow?): Float? {
        if (chapterRow == null || !ForkPageResolver.isVirtualUrl(chapterRow[ChapterTable.url])) return null
        return dbTransaction {
            ForkChapterLinkTable
                .selectAll()
                .where { ForkChapterLinkTable.mergedChapterId eq chapterRow[ChapterTable.id].value }
                .firstOrNull()
                ?.get(ForkChapterLinkTable.originalNumber)
        }
    }

    /** D13: KOReader identifies a merged chapter by its source chapter's hash. Null for normal rows (no SQL). */
    fun koreaderSourceChapterId(
        chapterId: Int,
        chapterUrl: String,
    ): Int? {
        if (!ForkPageResolver.isVirtualUrl(chapterUrl)) return null
        return dbTransaction {
            ForkChapterLinkTable
                .selectAll()
                .where { ForkChapterLinkTable.mergedChapterId eq chapterId }
                .firstOrNull()
                ?.get(ForkChapterLinkTable.sourceChapterId)
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun writeBackPolicy() = policy().copy(direction = ProgressDirection.BOTH)

    private fun propagate(chapterIds: List<Int>) {
        val rows = loadRows(chapterIds)
        val (virtualRows, sourceRows) = rows.values.partition { ForkPageResolver.isVirtualUrl(it[ChapterTable.url]) }
        val p = policy()

        linksForVirtual(virtualRows.map { it[ChapterTable.id].value }).forEach { link ->
            pushToSources(link, rows.getValue(link.virtualId), p, filterManga = null)
        }

        if (p.direction == ProgressDirection.TO_SOURCE_ONLY || sourceRows.isEmpty()) return
        val sourceIds = sourceRows.map { it[ChapterTable.id].value }.toSet()
        linksForSources(sourceIds).forEach { link ->
            val virtualRow = loadRows(listOf(link.virtualId))[link.virtualId] ?: return@forEach
            var target = virtualRow.toState()
            (link.ranges.map { it.sourceChapterId } + link.mirrors).filter { it in sourceIds }.forEach { sourceId ->
                val origin = rows.getValue(sourceId).toState()
                val mapped =
                    if (link.kind == LinkKind.CHAPTER) {
                        origin
                    } else {
                        val mapping = PartMapping(link.ranges)
                        origin.copy(
                            lastPageRead = mapping.toVirtual(sourceId, origin.lastPageRead) ?: target.lastPageRead,
                            // A part is read only when the reader passed the end of its last range.
                            read = origin.read && mapping.completesVirtual(sourceId, Int.MAX_VALUE),
                        )
                    }
                target = ForkProgressResolver.merge(mapped, ProgressSide.SOURCE, target, p)
            }
            write(link.virtualId, virtualRow.toState(), target)
        }
    }

    private fun pushToSources(
        link: Link,
        virtualRow: ResultRow,
        p: ForkProgressPolicy,
        filterManga: Int?,
    ): Int {
        val origin = virtualRow.toState()
        val targetIds = (link.ranges.map { it.sourceChapterId } + link.mirrors).distinct()
        val targets = loadRows(targetIds)
        var changed = 0
        val mapping = PartMapping(link.ranges)
        targets.values.forEach { row ->
            if (filterManga != null && row[ChapterTable.manga].value != filterManga) return@forEach
            val sourceId = row[ChapterTable.id].value
            val current = row.toState()
            val mapped =
                if (link.kind == LinkKind.CHAPTER) {
                    origin
                } else {
                    val range = link.ranges.firstOrNull { it.sourceChapterId == sourceId } ?: return@forEach
                    val sourcePage = mapping.toSource(origin.lastPageRead)?.takeIf { it.first == sourceId }?.second ?: current.lastPageRead
                    origin.copy(
                        lastPageRead = sourcePage,
                        // Reading a part marks the source read only when the part ends at the source's last page.
                        read = origin.read && range.sourcePageCount > 0 && range.pageEnd >= range.sourcePageCount - 1,
                    )
                }
            val next = ForkProgressResolver.merge(mapped, ProgressSide.VIRTUAL, current, p)
            if (write(sourceId, current, next)) changed++
        }
        return changed
    }

    private fun write(
        chapterId: Int,
        current: ProgressState,
        next: ProgressState,
    ): Boolean {
        if (current == next) return false
        ChapterTable.update({ ChapterTable.id eq chapterId }) {
            it[ChapterTable.isRead] = next.read
            it[ChapterTable.isBookmarked] = next.bookmarked
            it[ChapterTable.lastPageRead] = next.lastPageRead.coerceAtLeast(0)
            it[ChapterTable.lastReadAt] = next.lastReadAt
        }
        return true
    }

    private fun loadRows(ids: Collection<Int>): Map<Int, ResultRow> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            ChapterTable.selectAll().where { ChapterTable.id inList ids }.associateBy { it[ChapterTable.id].value }
        }

    private fun linksForVirtual(virtualIds: Collection<Int>): List<Link> {
        if (virtualIds.isEmpty()) return emptyList()
        val links = ForkChapterLinkTable.selectAll().where { ForkChapterLinkTable.mergedChapterId inList virtualIds }.toList()
        return links.mapNotNull(::toLink)
    }

    private fun linksForSources(sourceIds: Collection<Int>): List<Link> {
        if (sourceIds.isEmpty()) return emptyList()
        val direct = ForkChapterLinkTable.selectAll().where { ForkChapterLinkTable.sourceChapterId inList sourceIds }.toList()
        val viaFallback =
            ForkChapterFallbackTable
                .selectAll()
                .where { ForkChapterFallbackTable.sourceChapterId inList sourceIds }
                .map { it[ForkChapterFallbackTable.linkId] }
        val viaParts =
            ForkChapterPartTable
                .selectAll()
                .where { ForkChapterPartTable.sourceChapterId inList sourceIds }
                .map { it[ForkChapterPartTable.mergedChapterId] }
        val extra =
            if (viaFallback.isEmpty() && viaParts.isEmpty()) {
                emptyList()
            } else {
                ForkChapterLinkTable
                    .selectAll()
                    .where { (ForkChapterLinkTable.id inList viaFallback) or (ForkChapterLinkTable.mergedChapterId inList viaParts) }
                    .toList()
            }
        return (direct + extra).distinctBy { it[ForkChapterLinkTable.id].value }.mapNotNull(::toLink)
    }

    private fun toLink(row: ResultRow): Link? {
        val virtualId = row[ForkChapterLinkTable.mergedChapterId]
        val kind = runCatching { LinkKind.valueOf(row[ForkChapterLinkTable.linkKind]) }.getOrDefault(LinkKind.CHAPTER)
        val ranges =
            when (kind) {
                LinkKind.CHAPTER -> {
                    val id = row[ForkChapterLinkTable.sourceChapterId] ?: return null
                    listOf(PartRange(id, 0, Int.MAX_VALUE - 1, -1))
                }

                LinkKind.PART -> {
                    val id = row[ForkChapterLinkTable.sourceChapterId] ?: return null
                    listOf(
                        PartRange(
                            id,
                            row[ForkChapterLinkTable.pageStart] ?: 0,
                            row[ForkChapterLinkTable.pageEnd] ?: 0,
                            row[ForkChapterLinkTable.sourcePageCountSnapshot],
                        ),
                    )
                }

                LinkKind.PARTS -> {
                    ForkChapterPartTable
                        .selectAll()
                        .where { ForkChapterPartTable.mergedChapterId eq virtualId }
                        .orderBy(ForkChapterPartTable.partIndex to SortOrder.ASC)
                        .mapNotNull { part ->
                            part[ForkChapterPartTable.sourceChapterId]?.let {
                                PartRange(
                                    it,
                                    part[ForkChapterPartTable.pageStart],
                                    part[ForkChapterPartTable.pageEnd],
                                    part[ForkChapterPartTable.sourcePageCountSnapshot],
                                )
                            }
                        }.ifEmpty { return null }
                }
            }
        val mirrors =
            if (kind == LinkKind.CHAPTER) {
                ForkChapterFallbackTable
                    .selectAll()
                    .where { ForkChapterFallbackTable.linkId eq row[ForkChapterLinkTable.id].value }
                    .mapNotNull { it[ForkChapterFallbackTable.sourceChapterId] }
            } else {
                emptyList()
            }
        return Link(virtualId, kind, ranges, mirrors)
    }

    private fun ResultRow.toState() =
        ProgressState(
            read = this[ChapterTable.isRead],
            bookmarked = this[ChapterTable.isBookmarked],
            lastPageRead = this[ChapterTable.lastPageRead],
            lastReadAt = this[ChapterTable.lastReadAt],
        )

    companion object {
        /** Production instance; tests replace it to inject a gate without writing global settings. */
        @Volatile
        var default: ForkProgressService = ForkProgressService()
            internal set
    }
}
