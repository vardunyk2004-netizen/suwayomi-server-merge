package suwayomi.tachidesk.graphql.mutations

import suwayomi.tachidesk.fork.duplicates.DuplicateScore
import suwayomi.tachidesk.fork.duplicates.ForkDuplicateService
import suwayomi.tachidesk.fork.maintenance.DoctorIssueCode
import suwayomi.tachidesk.fork.maintenance.DoctorReport
import suwayomi.tachidesk.fork.maintenance.ForkDoctorService
import suwayomi.tachidesk.fork.maintenance.ForkReindexService
import suwayomi.tachidesk.fork.maintenance.ReindexAdoption
import suwayomi.tachidesk.fork.maintenance.ReindexReport
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.ReindexAdoptMode
import suwayomi.tachidesk.server.JavalinSetup.future
import java.util.concurrent.CompletableFuture

// GraphQL types for F10-F12 (kept next to their resolvers; all names are fork-prefixed).

data class ForkDuplicateSignalType(
    val signal: String,
    val weight: Double,
    val similarity: Double,
)

data class ForkDuplicateCandidateType(
    val mangaIdA: Int,
    val mangaIdB: Int,
    val score: Int,
    val strong: Boolean,
    val sequelBlocked: Boolean,
    val breakdown: List<ForkDuplicateSignalType>,
)

data class ForkReindexOrphanType(
    val mangaId: Int,
    val path: String,
    val match: String,
    val chapterIds: List<Int>,
)

data class ForkReindexReportType(
    val flaggedButMissing: List<Int>,
    val presentButUnflagged: List<Int>,
    val orphans: List<ForkReindexOrphanType>,
    val skippedMergedManga: Int,
    val createdAt: Long,
)

data class ForkDoctorIssueType(
    val code: String,
    val repairable: Boolean,
    val mangaId: Int?,
    val chapterId: Int?,
    val groupId: Int?,
    val detail: String,
)

data class ForkDoctorCountType(
    val code: String,
    val count: Int,
)

data class ForkDoctorReportType(
    val dryRun: Boolean,
    val issues: List<ForkDoctorIssueType>,
    val counts: List<ForkDoctorCountType>,
    val repaired: Int,
    val createdAt: Long,
)

internal fun DuplicateScore.toType() =
    ForkDuplicateCandidateType(
        a,
        b,
        score,
        strong,
        sequelBlocked,
        breakdown.map { ForkDuplicateSignalType(it.signal, it.weight, it.similarity) },
    )

internal fun ReindexReport.toType() =
    ForkReindexReportType(
        flaggedButMissing,
        presentButUnflagged,
        orphans.map { ForkReindexOrphanType(it.mangaId, it.path, it.match.name, it.chapterIds) },
        skippedMergedManga,
        createdAt,
    )

internal fun DoctorReport.toType() =
    ForkDoctorReportType(
        dryRun,
        issues.map { ForkDoctorIssueType(it.code.name, it.code.repairable, it.mangaId, it.chapterId, it.groupId, it.detail) },
        counts.map { (code, count) -> ForkDoctorCountType(code.name, count) }.sortedBy { it.code },
        repaired,
        createdAt,
    )

/** Read-side of F10-F12 lives here too: graphql-kotlin exposes it through [ForkMaintenanceQuery]. */
class ForkMaintenanceQuery {
    @RequireAuth
    fun duplicateCandidates(
        mangaId: Int? = null,
        limit: Int? = null,
    ): List<ForkDuplicateCandidateType> = ForkDuplicateService.default.candidates(mangaId, limit ?: 100).map { it.toType() }

    @RequireAuth
    fun mergeSuggestions(limit: Int? = null): List<ForkDuplicateCandidateType> =
        ForkDuplicateService.default.suggestions(limit ?: 50).map { it.toType() }

    @RequireAuth
    fun reindexLastReport(): ForkReindexReportType? = ForkReindexService.default.lastReport()?.toType()

    @RequireAuth
    fun doctorLastReport(): ForkDoctorReportType? = ForkDoctorService.default.lastReport()?.toType()
}

class ForkMaintenanceMutation {
    data class IgnoreDuplicatePairInput(
        val clientMutationId: String? = null,
        val mangaIdA: Int,
        val mangaIdB: Int,
        /** False removes the pair from the ignore list. Default true. */
        val ignore: Boolean? = null,
    )

    data class ForkCountPayload(
        val clientMutationId: String?,
        val count: Int,
    )

    @RequireAuth
    fun ignoreDuplicatePair(input: IgnoreDuplicatePairInput): ForkCountPayload {
        ForkDuplicateService.default.ignorePair(input.mangaIdA, input.mangaIdB, input.ignore ?: true)
        return ForkCountPayload(input.clientMutationId, 1)
    }

    data class ForkClientMutationInput(
        val clientMutationId: String? = null,
    )

    @RequireAuth
    fun refreshDuplicateSignatures(input: ForkClientMutationInput): ForkCountPayload =
        ForkCountPayload(input.clientMutationId, ForkDuplicateService.default.refreshSignatures())

    data class ReindexScanInput(
        val clientMutationId: String? = null,
        val mangaIds: List<Int>? = null,
    )

    data class ReindexScanPayload(
        val clientMutationId: String?,
        val report: ForkReindexReportType,
    )

    @RequireAuth
    fun reindexScan(input: ReindexScanInput): CompletableFuture<ReindexScanPayload> =
        future { ReindexScanPayload(input.clientMutationId, ForkReindexService.default.scan(input.mangaIds).toType()) }

    data class ReindexFixFlagsInput(
        val clientMutationId: String? = null,
        val clearMissingChapterIds: List<Int>? = null,
        val markPresentChapterIds: List<Int>? = null,
        /** Null -> setting 1071 (default true). */
        val dryRun: Boolean? = null,
    )

    @RequireAuth
    fun reindexFixFlags(input: ReindexFixFlagsInput): ForkCountPayload =
        ForkCountPayload(
            input.clientMutationId,
            ForkReindexService.default.fixFlags(
                input.clearMissingChapterIds.orEmpty(),
                input.markPresentChapterIds.orEmpty(),
                input.dryRun,
            ),
        )

    data class ReindexAdoptionInput(
        val chapterId: Int,
        val path: String,
        val confirmFuzzy: Boolean? = null,
    )

    data class ReindexAdoptInput(
        val clientMutationId: String? = null,
        val adoptions: List<ReindexAdoptionInput>,
        /** Null -> setting 1073 (default COPY). */
        val mode: ReindexAdoptMode? = null,
        /** Null -> setting 1071 (default true). */
        val dryRun: Boolean? = null,
    )

    data class ReindexAdoptPayload(
        val clientMutationId: String?,
        val journalIds: List<Int>,
    )

    @RequireAuth
    fun reindexAdopt(input: ReindexAdoptInput): CompletableFuture<ReindexAdoptPayload> =
        future {
            ReindexAdoptPayload(
                input.clientMutationId,
                ForkReindexService.default.adopt(
                    input.adoptions.map { ReindexAdoption(it.chapterId, it.path, it.confirmFuzzy ?: false) },
                    input.mode,
                    input.dryRun,
                ),
            )
        }

    data class UndoReindexAdoptInput(
        val clientMutationId: String? = null,
        val journalId: Int,
    )

    @RequireAuth
    fun undoReindexAdopt(input: UndoReindexAdoptInput): ForkCountPayload {
        ForkReindexService.default.undoAdopt(input.journalId)
        return ForkCountPayload(input.clientMutationId, 1)
    }

    data class DoctorRunInput(
        val clientMutationId: String? = null,
        /** Null -> true (report only). Repairs need an explicit false. */
        val dryRun: Boolean? = null,
        val codes: List<String>? = null,
    )

    data class DoctorRunPayload(
        val clientMutationId: String?,
        val report: ForkDoctorReportType,
    )

    @RequireAuth
    fun doctorRun(input: DoctorRunInput): DoctorRunPayload =
        DoctorRunPayload(
            input.clientMutationId,
            ForkDoctorService.default
                .run(
                    input.dryRun,
                    input.codes?.mapNotNull { code -> DoctorIssueCode.entries.firstOrNull { it.name == code } }?.toSet(),
                ).toType(),
        )
}
