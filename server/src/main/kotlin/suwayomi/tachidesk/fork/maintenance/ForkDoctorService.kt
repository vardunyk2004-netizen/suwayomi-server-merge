package suwayomi.tachidesk.fork.maintenance

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.fork.ForkClock
import suwayomi.tachidesk.fork.ForkFeatureAvailability.Feature
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.database.ForkChapterLinkTable
import suwayomi.tachidesk.fork.database.ForkMaintenanceReportTable
import suwayomi.tachidesk.fork.database.ForkMangaLifecycleTable
import suwayomi.tachidesk.fork.database.ForkMergeGroupTable
import suwayomi.tachidesk.fork.journal.ForkJournal
import suwayomi.tachidesk.fork.journal.ForkOperation
import suwayomi.tachidesk.fork.lifecycle.ForkLifecycleStore
import suwayomi.tachidesk.fork.lifecycle.LifecycleState
import suwayomi.tachidesk.fork.merge.ForkMergeRepository
import suwayomi.tachidesk.fork.merge.ForkMergeService
import suwayomi.tachidesk.fork.merge.ForkMergedSource
import suwayomi.tachidesk.fork.merge.LinkStatus
import suwayomi.tachidesk.fork.merge.MergeMode
import suwayomi.tachidesk.fork.require
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.database.dbTransaction

/**
 * Stable issue codes (F12). Many old-fork checks are impossible now because of real foreign keys (F0001), so
 * the doctor verifies only what the schema cannot enforce. The old "stale category joins from manual
 * tombstones" repair is NOT carried forward: it deleted every category join of such manga (answers.md, Q2).
 */
@Serializable
enum class DoctorIssueCode(
    val repairable: Boolean,
) {
    FD001_LINK_SOURCE_MISSING(false),
    FD002_PART_NEEDS_REVIEW(false),
    FD003_HIDDEN_WITHOUT_OWNER(true),
    FD004_MERGED_WITHOUT_GROUP(true),
    FD005_GROUP_BELOW_MINIMUM(false),
    FD006_VIRTUAL_DOWNLOAD_FLAG(true),
    FD007_VIRTUAL_WITHOUT_LINK(true),
    FD008_SOURCE_NOT_HIDDEN(true),
}

@Serializable
data class DoctorIssue(
    val code: DoctorIssueCode,
    val mangaId: Int?,
    val chapterId: Int?,
    val groupId: Int?,
    val detail: String,
)

@Serializable
data class DoctorReport(
    val dryRun: Boolean,
    val issues: List<DoctorIssue>,
    val repaired: Int,
    val createdAt: Long,
) {
    val counts: Map<DoctorIssueCode, Int> get() = issues.groupingBy { it.code }.eachCount()
}

/** F12 consistency verifier: dry run by default; repairs are idempotent and journaled one row per issue. */
class ForkDoctorService(
    private val gate: ForkGate = ForkGate.production,
    private val merge: ForkMergeService = ForkMergeService.default,
) {
    fun run(
        dryRun: Boolean?,
        codes: Set<DoctorIssueCode>? = null,
    ): DoctorReport {
        gate.require(Feature.DOCTOR)
        val isDryRun = dryRun ?: true
        return dbTransaction {
            val issues = verify().filter { codes == null || it.code in codes }
            var repaired = 0
            if (!isDryRun) {
                issues.filter { it.code.repairable }.forEach { issue ->
                    if (repair(issue)) {
                        repaired++
                        ForkJournal.record(
                            ForkOperation.DOCTOR_REPAIR,
                            ForkJournal.json.encodeToString(DoctorIssue.serializer(), issue),
                            groupId = issue.groupId,
                            mangaId = issue.mangaId,
                            reversible = false,
                        )
                    }
                }
            }
            val report = DoctorReport(isDryRun, issues, repaired, ForkClock.now())
            ForkMaintenanceReportTable.insert {
                it[ForkMaintenanceReportTable.kind] = "DOCTOR"
                it[ForkMaintenanceReportTable.dryRun] = isDryRun
                it[ForkMaintenanceReportTable.payload] = ForkJournal.json.encodeToString(DoctorReport.serializer(), report)
                it[ForkMaintenanceReportTable.createdAt] = report.createdAt
            }
            report
        }
    }

    fun lastReport(): DoctorReport? {
        gate.require(Feature.DOCTOR)
        return dbTransaction {
            ForkMaintenanceReportTable
                .selectAll()
                .where { ForkMaintenanceReportTable.kind eq "DOCTOR" }
                .orderBy(ForkMaintenanceReportTable.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.let { ForkJournal.json.decodeFromString(DoctorReport.serializer(), it[ForkMaintenanceReportTable.payload]) }
        }
    }

    internal fun verify(): List<DoctorIssue> {
        val issues = mutableListOf<DoctorIssue>()

        ForkChapterLinkTable
            .selectAll()
            .where { ForkChapterLinkTable.sourceChapterId.isNull() or (ForkChapterLinkTable.status eq LinkStatus.SOURCE_MISSING.name) }
            .forEach {
                issues +=
                    DoctorIssue(
                        DoctorIssueCode.FD001_LINK_SOURCE_MISSING,
                        null,
                        it[ForkChapterLinkTable.mergedChapterId],
                        null,
                        it[ForkChapterLinkTable.originalName],
                    )
            }
        ForkChapterLinkTable
            .selectAll()
            .where { ForkChapterLinkTable.status eq LinkStatus.NEEDS_REVIEW.name }
            .forEach {
                issues +=
                    DoctorIssue(
                        DoctorIssueCode.FD002_PART_NEEDS_REVIEW,
                        null,
                        it[ForkChapterLinkTable.mergedChapterId],
                        null,
                        it[ForkChapterLinkTable.originalName],
                    )
            }

        val groupIds = ForkMergeGroupTable.selectAll().map { it[ForkMergeGroupTable.id].value }.toSet()
        ForkMangaLifecycleTable
            .selectAll()
            .where { ForkMangaLifecycleTable.state eq LifecycleState.HIDDEN_BY_MERGE.name }
            .forEach {
                val owner = it[ForkMangaLifecycleTable.ownerGroupId]
                if (owner == null || owner !in groupIds) {
                    issues +=
                        DoctorIssue(
                            DoctorIssueCode.FD003_HIDDEN_WITHOUT_OWNER,
                            it[ForkMangaLifecycleTable.mangaId],
                            null,
                            owner,
                            "hidden by a merge that no longer exists",
                        )
                }
            }

        val mergedRows = MangaTable.selectAll().where { MangaTable.sourceReference eq ForkMergedSource.ID }.toList()
        val groupsByManga = ForkMergeRepository.allGroups().associateBy { it.mangaId }
        mergedRows.forEach { row ->
            val mangaId = row[MangaTable.id].value
            val group = groupsByManga[mangaId]
            if (group == null && ForkLifecycleStore.stateOf(mangaId) != LifecycleState.SOFT_REMOVED) {
                issues += DoctorIssue(DoctorIssueCode.FD004_MERGED_WITHOUT_GROUP, mangaId, null, null, row[MangaTable.title])
            }
        }

        groupsByManga.values.forEach { group ->
            val sources = ForkMergeRepository.sources(group)
            if (group.mode != MergeMode.SPLIT && sources.size < 2) {
                issues += DoctorIssue(DoctorIssueCode.FD005_GROUP_BELOW_MINIMUM, group.mangaId, null, group.id, "${sources.size} source(s)")
            }
            if (group.mode != MergeMode.SPLIT) {
                sources.forEach { source ->
                    if (ForkLifecycleStore.stateOf(source.sourceMangaId) == LifecycleState.ACTIVE) {
                        issues +=
                            DoctorIssue(
                                DoctorIssueCode.FD008_SOURCE_NOT_HIDDEN,
                                source.sourceMangaId,
                                null,
                                group.id,
                                "source visible next to its merge",
                            )
                    }
                }
            }
            val virtualIds = ForkMergeRepository.virtualChapterIds(group.mangaId)
            val linked = ForkMergeRepository.linkSourceChapterIds(virtualIds).keys
            (virtualIds - linked).forEach { chapterId ->
                issues +=
                    DoctorIssue(
                        DoctorIssueCode.FD007_VIRTUAL_WITHOUT_LINK,
                        group.mangaId,
                        chapterId,
                        group.id,
                        "virtual chapter without link",
                    )
            }
            if (virtualIds.isNotEmpty()) {
                ChapterTable
                    .selectAll()
                    .where { (ChapterTable.id inList virtualIds) and (ChapterTable.isDownloaded eq true) }
                    .forEach {
                        issues +=
                            DoctorIssue(
                                DoctorIssueCode.FD006_VIRTUAL_DOWNLOAD_FLAG,
                                group.mangaId,
                                it[ChapterTable.id].value,
                                group.id,
                                "download flag on a virtual chapter",
                            )
                    }
            }
        }
        return issues
    }

    private fun repair(issue: DoctorIssue): Boolean =
        when (issue.code) {
            DoctorIssueCode.FD003_HIDDEN_WITHOUT_OWNER -> {
                ForkLifecycleStore.set(issue.mangaId!!, LifecycleState.ACTIVE, null, null)
                true
            }

            DoctorIssueCode.FD004_MERGED_WITHOUT_GROUP -> {
                // Reversible: goes to the trash, never deleted here.
                ForkLifecycleStore.set(issue.mangaId!!, LifecycleState.SOFT_REMOVED, "doctor: merge definition missing", null)
                true
            }

            DoctorIssueCode.FD006_VIRTUAL_DOWNLOAD_FLAG -> {
                ChapterTable.update({ ChapterTable.id eq issue.chapterId!! }) { it[ChapterTable.isDownloaded] = false }
                true
            }

            DoctorIssueCode.FD007_VIRTUAL_WITHOUT_LINK, DoctorIssueCode.FD008_SOURCE_NOT_HIDDEN -> {
                merge.reconcile(issue.groupId!!)
                true
            }

            else -> {
                false
            }
        }

    companion object {
        val default by lazy { ForkDoctorService() }
    }
}
