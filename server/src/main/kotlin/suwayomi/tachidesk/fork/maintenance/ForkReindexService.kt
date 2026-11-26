package suwayomi.tachidesk.fork.maintenance

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.fork.ForkClock
import suwayomi.tachidesk.fork.ForkFeatureAvailability.Feature
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.ForkRequestException
import suwayomi.tachidesk.fork.database.ForkMaintenanceReportTable
import suwayomi.tachidesk.fork.duplicates.ForkDuplicateScorer
import suwayomi.tachidesk.fork.forkRequire
import suwayomi.tachidesk.fork.journal.ForkJournal
import suwayomi.tachidesk.fork.journal.ForkJournalState
import suwayomi.tachidesk.fork.journal.ForkOperation
import suwayomi.tachidesk.fork.merge.ForkMergePlanner
import suwayomi.tachidesk.fork.merge.ForkMergedSource
import suwayomi.tachidesk.fork.require
import suwayomi.tachidesk.graphql.types.ReindexAdoptMode
import suwayomi.tachidesk.manga.impl.ChapterDownloadHelper
import suwayomi.tachidesk.manga.impl.download.DownloadManager
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.impl.util.getChapterDownloadPath
import suwayomi.tachidesk.manga.impl.util.getMangaDownloadDir
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

@Serializable
enum class ReindexMatch {
    EXACT,
    FUZZY,
    AMBIGUOUS,
    NONE,
}

@Serializable
data class ReindexOrphan(
    val mangaId: Int,
    val path: String,
    val match: ReindexMatch,
    val chapterIds: List<Int>,
)

@Serializable
data class ReindexReport(
    val dryRun: Boolean,
    val flaggedButMissing: List<Int>,
    val presentButUnflagged: List<Int>,
    val orphans: List<ReindexOrphan>,
    val skippedMergedManga: Int,
    val createdAt: Long,
)

@Serializable
data class ReindexAdoptJournalPayload(
    val chapterId: Int,
    val from: String,
    val to: String,
    val mode: String,
)

/** One adoption the user confirmed from a report. */
data class ReindexAdoption(
    val chapterId: Int,
    val path: String,
    /** Required for FUZZY matches (setting 1072 must also be on). */
    val confirmFuzzy: Boolean = false,
)

/**
 * F11 download reindex. It never deletes user files: `clearMissing` only flips DB flags; adoption copies by
 * default (setting 1073) and is journaled and undoable (D21). It refuses to run while downloads are queued.
 * Virtual chapters of merged manga never own downloads and are skipped.
 */
class ForkReindexService(
    private val gate: ForkGate = ForkGate.production,
    private val queueBusy: () -> Boolean = { DownloadManager.getStatus().queue.isNotEmpty() },
) {
    private fun requireIdle() {
        forkRequire(!queueBusy(), "DOWNLOADS_ACTIVE") { "Reindex is unavailable while the download queue is not empty" }
    }

    suspend fun scan(mangaIds: List<Int>? = null): ReindexReport {
        gate.require(Feature.REINDEX)
        requireIdle()
        val mangas =
            dbTransaction {
                val query = MangaTable.selectAll().where { MangaTable.inLibrary eq true }
                if (mangaIds != null) query.andWhere { MangaTable.id inList mangaIds }
                query.toList()
            }
        val merged = mangas.count { it[MangaTable.sourceReference] == ForkMergedSource.ID }
        val missing = mutableListOf<Int>()
        val unflagged = mutableListOf<Int>()
        val orphans = mutableListOf<ReindexOrphan>()
        mangas.filter { it[MangaTable.sourceReference] != ForkMergedSource.ID }.forEach { manga ->
            val mangaId = manga[MangaTable.id].value
            val chapters =
                dbTransaction {
                    ChapterTable
                        .selectAll()
                        .where { ChapterTable.manga eq mangaId }
                        .orderBy(ChapterTable.sourceOrder to SortOrder.ASC)
                        .toList()
                }
            val expected = mutableSetOf<String>()
            chapters.forEach { chapter ->
                val chapterId = chapter[ChapterTable.id].value
                if (ForkMergePlanner.VIRTUAL_URL_PREFIX.let { chapter[ChapterTable.url].startsWith(it) }) return@forEach
                expected += File(getChapterDownloadPath(mangaId, chapterId)).name
                expected += File(getChapterCbzPath(mangaId, chapterId)).name
                val count = runCatching { ChapterDownloadHelper.getImageCount(mangaId, chapterId) }.getOrDefault(0)
                if (chapter[ChapterTable.isDownloaded] && count == 0) missing += chapterId
                if (!chapter[ChapterTable.isDownloaded] && count > 0) unflagged += chapterId
            }
            val dir = File(getMangaDownloadDir(mangaId))
            dir.listFiles()?.filter { it.name !in expected }?.forEach { file ->
                val base = ForkMergePlanner.normalize(file.nameWithoutExtension)
                val exact =
                    chapters.filter {
                        val name = ForkMergePlanner.normalize(it[ChapterTable.name])
                        name == base || base.endsWith(name)
                    }
                val (match, ids) =
                    when {
                        exact.size == 1 -> {
                            ReindexMatch.EXACT to exact.map { it[ChapterTable.id].value }
                        }

                        exact.size > 1 -> {
                            ReindexMatch.AMBIGUOUS to exact.map { it[ChapterTable.id].value }
                        }

                        else -> {
                            val fuzzy =
                                chapters.filter {
                                    ForkDuplicateScorer.similarity(base, ForkMergePlanner.normalize(it[ChapterTable.name])) >= 0.85
                                }
                            when (fuzzy.size) {
                                0 -> ReindexMatch.NONE to emptyList()
                                1 -> ReindexMatch.FUZZY to fuzzy.map { it[ChapterTable.id].value }
                                else -> ReindexMatch.AMBIGUOUS to fuzzy.map { it[ChapterTable.id].value }
                            }
                        }
                    }
                orphans += ReindexOrphan(mangaId, file.absolutePath, match, ids)
            }
        }
        val report = ReindexReport(true, missing, unflagged, orphans, merged, ForkClock.now())
        saveReport("REINDEX", report)
        return report
    }

    /** Only flips flags (missing -> not downloaded, present -> downloaded). Never deletes files. */
    fun fixFlags(
        clearMissingIds: List<Int>,
        markPresentIds: List<Int>,
        dryRun: Boolean?,
    ): Int {
        gate.require(Feature.REINDEX)
        requireIdle()
        if (dryRun ?: serverConfig.reindexDefaultDryRun.value) return clearMissingIds.size + markPresentIds.size
        return dbTransaction {
            if (clearMissingIds.isNotEmpty()) {
                ChapterTable.update({ ChapterTable.id inList clearMissingIds }) { it[ChapterTable.isDownloaded] = false }
            }
            if (markPresentIds.isNotEmpty()) {
                ChapterTable.update({ ChapterTable.id inList markPresentIds }) { it[ChapterTable.isDownloaded] = true }
            }
            clearMissingIds.size + markPresentIds.size
        }
    }

    /** Adopts orphans into the expected chapter path. COPY by default; each adoption is a journal row. */
    suspend fun adopt(
        adoptions: List<ReindexAdoption>,
        mode: ReindexAdoptMode?,
        dryRun: Boolean?,
    ): List<Int> {
        gate.require(Feature.REINDEX)
        requireIdle()
        val adoptMode = mode ?: serverConfig.reindexAdoptMode.value
        val report = lastReport() ?: throw ForkRequestException("NO_REPORT", "Scan first")
        val journalIds = mutableListOf<Int>()
        for (adoption in adoptions) {
            val orphan =
                report.orphans.firstOrNull { it.path == adoption.path && adoption.chapterId in it.chapterIds }
                    ?: throw ForkRequestException(
                        "NOT_IN_REPORT",
                        "${adoption.path} is not a reported candidate for chapter ${adoption.chapterId}",
                    )
            forkRequire(orphan.match != ReindexMatch.AMBIGUOUS && orphan.match != ReindexMatch.NONE, "AMBIGUOUS") {
                "Choose a different file"
            }
            if (orphan.match == ReindexMatch.FUZZY) {
                forkRequire(serverConfig.reindexAllowFuzzy.value && adoption.confirmFuzzy, "FUZZY_NEEDS_CONFIRMATION") {
                    "Fuzzy matches need setting 1072 and a per-item confirmation"
                }
            }
            val from = File(orphan.path)
            val to =
                if (from.isDirectory) {
                    File(getChapterDownloadPath(orphan.mangaId, adoption.chapterId))
                } else {
                    File(getChapterCbzPath(orphan.mangaId, adoption.chapterId))
                }
            forkRequire(!to.exists(), "TARGET_EXISTS") { "${to.name} already exists; nothing was changed" }
            if (dryRun ?: serverConfig.reindexDefaultDryRun.value) continue
            to.parentFile?.mkdirs()
            when (adoptMode) {
                ReindexAdoptMode.COPY -> from.copyRecursively(to, overwrite = false)
                ReindexAdoptMode.MOVE -> check(from.renameTo(to)) { "Could not move ${from.name}" }
            }
            dbTransaction {
                ChapterTable.update({ ChapterTable.id eq adoption.chapterId }) { it[ChapterTable.isDownloaded] = true }
                journalIds +=
                    ForkJournal.record(
                        ForkOperation.REINDEX_ADOPT,
                        ForkJournal.json.encodeToString(
                            ReindexAdoptJournalPayload.serializer(),
                            ReindexAdoptJournalPayload(adoption.chapterId, from.absolutePath, to.absolutePath, adoptMode.name),
                        ),
                        mangaId = orphan.mangaId,
                    )
            }
        }
        return journalIds
    }

    /** Undo: COPY deletes only the copy we made; MOVE moves the files back. */
    fun undoAdopt(journalId: Int) {
        gate.require(Feature.REINDEX)
        requireIdle()
        val entry = ForkJournal.get(journalId)
        val undoable = entry != null && entry.operation == ForkOperation.REINDEX_ADOPT && entry.state == ForkJournalState.APPLIED
        forkRequire(undoable, "NOT_UNDOABLE") {
            "Journal entry $journalId is not an applied adoption"
        }
        val payload = ForkJournal.json.decodeFromString(ReindexAdoptJournalPayload.serializer(), entry!!.payload!!)
        val from = File(payload.from)
        val to = File(payload.to)
        when (ReindexAdoptMode.valueOf(payload.mode)) {
            ReindexAdoptMode.COPY -> {
                forkRequire(from.exists(), "STALE") { "The original is gone; the copy is kept" }
                // Defence in depth for the only file delete in fork code: the copy must sit under the downloads
                // root and must not be (or contain) the original.
                val root = File(Injekt.get<ApplicationDirs>().downloadsRoot).canonicalFile.toPath()
                val copy = to.canonicalFile.toPath()
                forkRequire(copy.startsWith(root) && copy != root && !from.canonicalFile.toPath().startsWith(copy), "UNSAFE_PATH") {
                    "Refusing to delete ${to.path}: it is not a copy inside the downloads folder"
                }
                to.deleteRecursively()
            }

            ReindexAdoptMode.MOVE -> {
                forkRequire(!from.exists(), "STALE") { "Something now occupies the original location" }
                check(to.renameTo(from)) { "Could not move the files back" }
            }
        }
        dbTransaction {
            ChapterTable.update({ ChapterTable.id eq payload.chapterId }) { it[ChapterTable.isDownloaded] = false }
            ForkJournal.markState(journalId, ForkJournalState.UNDONE)
        }
    }

    fun lastReport(): ReindexReport? {
        gate.require(Feature.REINDEX)
        return dbTransaction {
            ForkMaintenanceReportTable
                .selectAll()
                .where { ForkMaintenanceReportTable.kind eq "REINDEX" }
                .orderBy(ForkMaintenanceReportTable.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.let { ForkJournal.json.decodeFromString(ReindexReport.serializer(), it[ForkMaintenanceReportTable.payload]) }
        }
    }

    private fun saveReport(
        kind: String,
        report: ReindexReport,
    ) {
        dbTransaction {
            ForkMaintenanceReportTable.insert {
                it[ForkMaintenanceReportTable.kind] = kind
                it[ForkMaintenanceReportTable.dryRun] = report.dryRun
                it[ForkMaintenanceReportTable.payload] = ForkJournal.json.encodeToString(ReindexReport.serializer(), report)
                it[ForkMaintenanceReportTable.createdAt] = report.createdAt
            }
        }
    }

    companion object {
        val default by lazy { ForkReindexService() }
    }
}
