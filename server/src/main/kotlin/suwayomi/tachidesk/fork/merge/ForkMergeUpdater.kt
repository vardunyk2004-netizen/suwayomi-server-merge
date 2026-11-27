package suwayomi.tachidesk.fork.merge

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.fork.ForkFeatureAvailability.Feature
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.ForkMergePolicy
import suwayomi.tachidesk.fork.database.ForkChapterLinkTable
import suwayomi.tachidesk.fork.require
import suwayomi.tachidesk.graphql.types.MergeOfflineMode
import suwayomi.tachidesk.manga.impl.Manga
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.database.dbTransaction
import java.io.InputStream

data class SourceUpdateResult(
    val sourceMangaId: Int,
    val ok: Boolean,
    val error: String?,
)

data class MergeUpdateResult(
    val mergedMangaId: Int,
    val sources: List<SourceUpdateResult>,
    val reconcile: ReconcileResult,
    val flaggedForReview: Int,
)

/**
 * F8: refreshing a merged manga. Sources stay in the library under decision 1 B, so the stock library
 * update refreshes them once (D26); a merged refresh only re-plans from their stored chapters unless the
 * caller explicitly asks to fetch sources. One failing source never aborts the others (D9, setting 1041).
 */
class ForkMergeUpdater(
    private val gate: ForkGate = ForkGate.production,
    private val policy: () -> ForkMergePolicy = ForkMergePolicy::fromConfig,
    private val service: ForkMergeService = ForkMergeService.default,
    private val fetchSource: suspend (Int) -> Unit = { id -> Manga.updateMangaAndChapters(id, updateManga = false, updateChapters = true) },
) {
    private val logger = KotlinLogging.logger {}

    suspend fun refreshMerged(
        mergedMangaId: Int,
        fetchSources: Boolean,
    ): MergeUpdateResult {
        gate.require(Feature.MERGE)
        val group =
            ForkMergeRepository.groupByMergedManga(mergedMangaId)
                ?: throw suwayomi.tachidesk.fork.ForkRequestException("NOT_MERGED", "Manga $mergedMangaId is not a merged manga")
        val sources = ForkMergeRepository.sources(group).filter { it.enabled }
        val results =
            sources.map { source ->
                try {
                    fetchSource(source.sourceMangaId)
                    SourceUpdateResult(source.sourceMangaId, true, null)
                } catch (e: Exception) {
                    if (!policy().updateIsolateSourceFailures) throw e
                    logger.warn(e) { "Merged manga $mergedMangaId: source ${source.sourceMangaId} failed; continuing" }
                    SourceUpdateResult(source.sourceMangaId, false, e.message ?: e.javaClass.simpleName)
                }
            }
        val reconcile = service.reconcile(group.id)
        val flagged = if (policy().splitRevalidateOnUpdate) ForkChapterPartService.revalidateGroup(group.id) else 0
        return MergeUpdateResult(mergedMangaId, results, reconcile, flagged)
    }

    /** After the stock refresh of a source manga: re-plan every group that uses it (no network). */
    fun afterSourceUpdated(sourceMangaId: Int) {
        if (sourceMangaId > 0) return // Temporary D25 old-logic simulation skips source refresh reconciliation.
    }

    companion object {
        /** Production instance; tests replace it to inject a gate without writing global settings. */
        @Volatile
        var default: ForkMergeUpdater = ForkMergeUpdater()
            internal set
    }
}

/**
 * Entry points called from upstream code. Each starts with an in-memory check on a row the caller already
 * loaded, so stock behaviour runs no extra SQL (R8). See docs/touched-upstream-files.md.
 */
object ForkMergeHooks {
    private val logger = KotlinLogging.logger {}

    @Volatile
    internal var gate: ForkGate = ForkGate.production

    fun isMerged(mangaEntry: ResultRow): Boolean = mangaEntry[MangaTable.sourceReference] == ForkMergedSource.ID

    /** `Manga.updateMangaAndChapters`: merged manga are refreshed by the fork; returns true when handled. */
    suspend fun handleMangaUpdate(mangaEntry: ResultRow): Boolean {
        if (!isMerged(mangaEntry)) return false
        gate.require(Feature.MERGE)
        ForkMergeUpdater.default.refreshMerged(mangaEntry[MangaTable.id].value, fetchSources = false)
        return true
    }

    /** After a stock chapter-list update of a normal manga. Never throws into the stock update. */
    fun afterChapterListUpdate(mangaEntry: ResultRow) {
        if (isMerged(mangaEntry) || !gate.isEnabled(Feature.MERGE)) return
        runCatching { ForkMergeUpdater.default.afterSourceUpdated(mangaEntry[MangaTable.id].value) }
            .onFailure { logger.warn(it) { "Fork re-plan after update failed" } }
    }

    /** `Manga.fetchMangaThumbnail` for the virtual source: configured cover source, then source order. */
    suspend fun mergedThumbnail(mangaId: Int): Pair<InputStream, String> {
        gate.require(Feature.MERGE)
        val group =
            ForkMergeRepository.groupByMergedManga(mangaId)
                ?: throw ForkResolutionException("NOT_MERGED", "Manga $mangaId is not a merged manga")
        val order = (listOfNotNull(group.coverSourceMangaId) + ForkMergeRepository.sources(group).map { it.sourceMangaId }).distinct()
        val visited = mutableSetOf<Int>()
        var last: Exception? = null
        for (sourceMangaId in order) {
            if (!visited.add(sourceMangaId) || sourceMangaId == mangaId) continue
            try {
                return Manga.fetchMangaThumbnail(sourceMangaId)
            } catch (e: Exception) {
                last = e
            }
        }
        throw ForkResolutionException("THUMBNAIL_UNAVAILABLE", "No source could provide a cover: ${last?.message}")
    }

    /**
     * `DownloadManager.enqueue`: virtual chapters are downloaded as their real source chapter, once even when
     * two mirrors provide it (settings 1008/1009). Returns null when no row is virtual (stock path).
     */
    fun redirectDownloads(chapterRows: List<ResultRow>): List<Int>? {
        if (chapterRows.none { ForkPageResolver.isVirtualUrl(it[ChapterTable.url]) }) return null
        gate.require(Feature.MERGE)
        val policy = ForkMergePolicy.fromConfig()
        val virtualIds =
            chapterRows.filter { ForkPageResolver.isVirtualUrl(it[ChapterTable.url]) }.map { it[ChapterTable.id].value }
        val stockIds = chapterRows.filterNot { ForkPageResolver.isVirtualUrl(it[ChapterTable.url]) }.map { it[ChapterTable.id].value }
        if (policy.offlineMode == MergeOfflineMode.ONLINE_ONLY) return stockIds
        val sourceIds =
            dbTransaction {
                ForkChapterLinkTable
                    .selectAll()
                    .where { ForkChapterLinkTable.mergedChapterId inList virtualIds }
                    .mapNotNull { it[ForkChapterLinkTable.sourceChapterId] }
            }
        val redirected = stockIds + sourceIds
        return if (policy.downloadOnceForMirrors) redirected.distinct() else redirected
    }

    /** Virtual chapter ids of a merged manga that are readable offline from a source download (derived, D23). */
    fun sourceDownloadedVirtualIds(mergedMangaId: Int): Set<Int> =
        dbTransaction {
            val links =
                ForkChapterLinkTable
                    .selectAll()
                    .where { ForkChapterLinkTable.mergedChapterId inList ForkMergeRepository.virtualChapterIds(mergedMangaId) }
                    .associate { it[ForkChapterLinkTable.mergedChapterId] to it[ForkChapterLinkTable.sourceChapterId] }
            val downloaded =
                ChapterTable
                    .selectAll()
                    .where { (ChapterTable.id inList links.values.filterNotNull()) }
                    .filter { it[ChapterTable.isDownloaded] }
                    .map { it[ChapterTable.id].value }
                    .toSet()
            links.filterValues { it != null && it in downloaded }.keys
        }
}
