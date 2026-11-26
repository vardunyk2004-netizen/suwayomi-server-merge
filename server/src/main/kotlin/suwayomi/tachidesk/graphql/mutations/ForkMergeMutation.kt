package suwayomi.tachidesk.graphql.mutations

import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.lifecycle.ForkLifecycleExecutor
import suwayomi.tachidesk.fork.lifecycle.LifecycleAction
import suwayomi.tachidesk.fork.lifecycle.LifecycleActionRequest
import suwayomi.tachidesk.fork.merge.ForkChapterPartService
import suwayomi.tachidesk.fork.merge.ForkMergeService
import suwayomi.tachidesk.fork.merge.ForkMergeUpdater
import suwayomi.tachidesk.fork.merge.ForkSeriesSplitPlanner
import suwayomi.tachidesk.fork.merge.ReconcileResult
import suwayomi.tachidesk.fork.merge.SplitViewRequest
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.queries.ForkGraphQLMapper
import suwayomi.tachidesk.graphql.queries.ForkQuery
import suwayomi.tachidesk.graphql.types.ForkBatchMergeResultType
import suwayomi.tachidesk.graphql.types.ForkLifecycleAction
import suwayomi.tachidesk.graphql.types.ForkLifecyclePreviewType
import suwayomi.tachidesk.graphql.types.ForkLifecycleResultType
import suwayomi.tachidesk.graphql.types.ForkMangaState
import suwayomi.tachidesk.graphql.types.ForkMergeMode
import suwayomi.tachidesk.graphql.types.ForkMergeResultType
import suwayomi.tachidesk.graphql.types.ForkMergeUpdateResultType
import suwayomi.tachidesk.graphql.types.ForkPurgePreviewType
import suwayomi.tachidesk.graphql.types.ForkReconcileResultType
import suwayomi.tachidesk.graphql.types.ForkSourceUpdateResultType
import suwayomi.tachidesk.server.JavalinSetup.future
import java.util.concurrent.CompletableFuture

/**
 * Merge, chapter-level and series-split mutations (F2/F3/F4). Every service call checks the merge gate before
 * touching a table. Payload fields come only from server results, so the UI never shows success before the
 * server confirms (R12).
 */
class ForkMergeMutation {
    data class CreateMergeInput(
        val clientMutationId: String? = null,
        val sourceMangaIds: List<Int>,
        val mode: ForkMergeMode? = null,
        val title: String? = null,
        val coverSourceMangaId: Int? = null,
        val offsets: List<ForkQuery.ForkSourceOffsetInput>? = null,
    )

    data class CreateMergePayload(
        val clientMutationId: String?,
        val result: ForkMergeResultType,
    )

    @RequireAuth
    fun createMerge(input: CreateMergeInput): CreateMergePayload {
        val result =
            ForkMergeService.default.create(
                ForkGraphQLMapper.createRequest(input.sourceMangaIds, input.mode, input.title, input.coverSourceMangaId, input.offsets),
            )
        return CreateMergePayload(
            input.clientMutationId,
            ForkMergeResultType(
                result.groupId,
                result.mergedMangaId,
                result.journalId,
                result.reconcile.added,
                result.reconcile.removed,
                result.reconcile.updated,
                result.reconcile.total,
                result.warnings,
            ),
        )
    }

    data class CreateMergesInput(
        val clientMutationId: String? = null,
        val merges: List<CreateMergeInput>,
    )

    data class CreateMergesPayload(
        val clientMutationId: String?,
        val results: List<ForkBatchMergeResultType>,
    )

    /** D20: each merge commits on its own; the payload reports every group. */
    @RequireAuth
    fun createMerges(input: CreateMergesInput): CreateMergesPayload {
        val outcomes =
            ForkMergeService.default.createBatch(
                input.merges.map {
                    ForkGraphQLMapper.createRequest(it.sourceMangaIds, it.mode, it.title, it.coverSourceMangaId, it.offsets)
                },
            )
        return CreateMergesPayload(
            input.clientMutationId,
            outcomes.map { ForkBatchMergeResultType(it.index, it.result != null, it.result?.mergedMangaId, it.errorCode, it.errorMessage) },
        )
    }

    data class MergeSourceInput(
        val clientMutationId: String? = null,
        val groupId: Int,
        val sourceMangaId: Int,
    )

    data class ForkReconcilePayload(
        val clientMutationId: String?,
        val result: ForkReconcileResultType,
    )

    @RequireAuth
    fun addMergeSource(input: MergeSourceInput): ForkReconcilePayload =
        ForkReconcilePayload(input.clientMutationId, ForkMergeService.default.addSource(input.groupId, input.sourceMangaId).toType())

    data class ReorderMergeSourcesInput(
        val clientMutationId: String? = null,
        val groupId: Int,
        val sourceMangaIds: List<Int>,
    )

    @RequireAuth
    fun reorderMergeSources(input: ReorderMergeSourcesInput): ForkReconcilePayload =
        ForkReconcilePayload(input.clientMutationId, ForkMergeService.default.reorder(input.groupId, input.sourceMangaIds).toType())

    data class SetMergeOffsetsInput(
        val clientMutationId: String? = null,
        val groupId: Int,
        val offsets: List<ForkQuery.ForkSourceOffsetInput>,
    )

    @RequireAuth
    fun setMergeOffsets(input: SetMergeOffsetsInput): ForkReconcilePayload =
        ForkReconcilePayload(
            input.clientMutationId,
            ForkMergeService.default
                .setOffsets(input.groupId, input.offsets.associate { it.sourceMangaId to it.offset.toFloat() })
                .toType(),
        )

    data class SetMergeCoverInput(
        val clientMutationId: String? = null,
        val groupId: Int,
        /** Null resets to the default cover order. */
        val coverSourceMangaId: Int? = null,
    )

    data class SetMergeCoverPayload(
        val clientMutationId: String?,
        val groupId: Int,
    )

    @RequireAuth
    fun setMergeCover(input: SetMergeCoverInput): SetMergeCoverPayload {
        ForkMergeService.default.setCover(input.groupId, input.coverSourceMangaId)
        return SetMergeCoverPayload(input.clientMutationId, input.groupId)
    }

    data class SetMergeEntryInput(
        val clientMutationId: String? = null,
        val groupId: Int,
        val entryKey: String,
        /** True hides the chapter from the merge (journaled, undoable); false shows it again. */
        val excluded: Boolean? = null,
        /** Manual per-chapter source choice; null leaves the current choice. */
        val preferredSourceMangaId: Int? = null,
        /** True removes the manual choice. */
        val clearPreferredSource: Boolean? = null,
    )

    @RequireAuth
    fun setMergeEntry(input: SetMergeEntryInput): ForkReconcilePayload =
        ForkReconcilePayload(
            input.clientMutationId,
            ForkMergeService.default
                .updateOptions(input.groupId) { options ->
                    var next = options
                    when (input.excluded) {
                        true -> next = next.copy(excludedEntryKeys = next.excludedEntryKeys + input.entryKey)
                        false -> next = next.copy(excludedEntryKeys = next.excludedEntryKeys - input.entryKey)
                        null -> Unit
                    }
                    if (input.clearPreferredSource == true) {
                        next = next.copy(preferredSourceByEntry = next.preferredSourceByEntry - input.entryKey)
                    } else if (input.preferredSourceMangaId != null) {
                        val preferred = next.preferredSourceByEntry + (input.entryKey to input.preferredSourceMangaId)
                        next = next.copy(preferredSourceByEntry = preferred)
                    }
                    next
                }.toType(),
        )

    data class UndoMergeChangeInput(
        val clientMutationId: String? = null,
        val journalId: Int,
    )

    @RequireAuth
    fun undoMergeChange(input: UndoMergeChangeInput): ForkReconcilePayload =
        ForkReconcilePayload(input.clientMutationId, ForkMergeService.default.undoOptions(input.journalId).toType())

    data class RefreshMergedMangaInput(
        val clientMutationId: String? = null,
        val mangaId: Int,
        /** True also fetches every source (isolated per source, D9). Default false: re-plan only (D26). */
        val fetchSources: Boolean? = null,
    )

    data class RefreshMergedMangaPayload(
        val clientMutationId: String?,
        val result: ForkMergeUpdateResultType,
    )

    @RequireAuth
    fun refreshMergedManga(input: RefreshMergedMangaInput): CompletableFuture<RefreshMergedMangaPayload> =
        future {
            val result = ForkMergeUpdater.default.refreshMerged(input.mangaId, input.fetchSources ?: false)
            RefreshMergedMangaPayload(
                input.clientMutationId,
                ForkMergeUpdateResultType(
                    result.mergedMangaId,
                    result.sources.map { ForkSourceUpdateResultType(it.sourceMangaId, it.ok, it.error) },
                    result.reconcile.added,
                    result.reconcile.removed,
                    result.reconcile.total,
                    result.flaggedForReview,
                ),
            )
        }

    data class SplitMergedChapterInput(
        val clientMutationId: String? = null,
        val chapterId: Int,
        /** 0-based page indexes where a new part starts. */
        val splitAtPages: List<Int>,
    )

    @RequireAuth
    fun splitMergedChapter(input: SplitMergedChapterInput): CompletableFuture<ForkReconcilePayload> =
        future {
            val result = ForkChapterPartService.default.splitChapter(input.chapterId, input.splitAtPages)
            ForkReconcilePayload(input.clientMutationId, result.toType())
        }

    data class MergeChaptersInput(
        val clientMutationId: String? = null,
        val chapterIds: List<Int>,
        val name: String? = null,
    )

    @RequireAuth
    fun mergeMergedChapters(input: MergeChaptersInput): CompletableFuture<ForkReconcilePayload> =
        future {
            val result = ForkChapterPartService.default.mergeChapters(input.chapterIds, input.name)
            ForkReconcilePayload(input.clientMutationId, result.toType())
        }

    data class RemovePartDefinitionInput(
        val clientMutationId: String? = null,
        val groupId: Int,
        val key: String,
    )

    @RequireAuth
    fun removeChapterPartDefinition(input: RemovePartDefinitionInput): ForkReconcilePayload =
        ForkReconcilePayload(input.clientMutationId, ForkChapterPartService.default.removePartDefinition(input.groupId, input.key).toType())

    data class SplitViewInput(
        val title: String,
        val chapterIds: List<Int>,
    )

    data class SplitSeriesInput(
        val clientMutationId: String? = null,
        val mangaId: Int,
        val views: List<SplitViewInput>,
        /** True only validates and returns counts (preview == execute validation, D28). */
        val dryRun: Boolean? = null,
    )

    data class SplitSeriesPayload(
        val clientMutationId: String?,
        val chapterCounts: List<Int>,
        val createdMangaIds: List<Int>,
    )

    @RequireAuth
    fun splitSeries(input: SplitSeriesInput): SplitSeriesPayload {
        val views = input.views.map { SplitViewRequest(it.title, it.chapterIds) }
        val preview = ForkSeriesSplitPlanner.preview(ForkGate.production, input.mangaId, views)
        if (input.dryRun != false) {
            return SplitSeriesPayload(input.clientMutationId, preview.map { it.chapterCount }, emptyList())
        }
        val created = ForkSeriesSplitPlanner.create(ForkGate.production, ForkMergeService.default, input.mangaId, views)
        return SplitSeriesPayload(input.clientMutationId, preview.map { it.chapterCount }, created.map { it.mergedMangaId })
    }

    private fun ReconcileResult.toType() = ForkReconcileResultType(added, removed, updated, total)
}

/** F5 lifecycle, trash and purge. */
class ForkLifecycleMutation {
    data class LifecycleActionInput(
        val clientMutationId: String? = null,
        val mangaId: Int,
        val action: ForkLifecycleAction,
        val sourceMangaId: Int? = null,
        val reason: String? = null,
        /** Null -> setting 1032 (default false, D5). */
        val restoreSources: Boolean? = null,
        /** Null -> false. Only materialized copies owned by the merged manga, never source files (D16). */
        val deleteMaterializedDownloads: Boolean? = null,
        /** Null -> true: return the preview without changing anything. Execute requires an explicit false. */
        val dryRun: Boolean? = null,
    )

    data class LifecycleActionPayload(
        val clientMutationId: String?,
        val preview: ForkLifecyclePreviewType,
        /** Null for a dry run. */
        val result: ForkLifecycleResultType?,
    )

    @RequireAuth
    fun applyLifecycleAction(input: LifecycleActionInput): LifecycleActionPayload {
        val request =
            LifecycleActionRequest(
                action = LifecycleAction.valueOf(input.action.name),
                mangaId = input.mangaId,
                sourceMangaId = input.sourceMangaId,
                reason = input.reason,
                restoreSources = input.restoreSources,
                deleteMaterializedDownloads = input.deleteMaterializedDownloads,
            )
        val preview = ForkLifecycleExecutor.default.preview(request)
        val previewType =
            ForkLifecyclePreviewType(
                action = input.action,
                targetState = ForkMangaState.valueOf(preview.plan.targetLifecycleState.name),
                virtualChaptersRemoved = preview.plan.virtualChapterIdsToRemove.size,
                sourcesRestored = preview.plan.sourceMangaIdsToRestore,
                sourceRowsDeleted = preview.plan.sourceMangaIdsToRemove.size,
                sourceFilesDeleted = preview.plan.sourceDownloadIdsToDelete.size,
                materializedFilesDeleted = preview.plan.materializedDownloadIdsToDelete.size,
                writesProgressBack = preview.plan.requiresProgressWriteBack,
                sourceImpact = preview.sourceImpact,
            )
        if (input.dryRun != false) return LifecycleActionPayload(input.clientMutationId, previewType, null)
        val outcome = ForkLifecycleExecutor.default.apply(request)
        val result = ForkLifecycleResultType(outcome.journalId, outcome.progressWrittenBack)
        return LifecycleActionPayload(input.clientMutationId, previewType, result)
    }

    data class RestoreFromTrashInput(
        val clientMutationId: String? = null,
        val mangaId: Int,
    )

    data class ForkJournalPayload(
        val clientMutationId: String?,
        val journalId: Int,
    )

    @RequireAuth
    fun restoreFromTrash(input: RestoreFromTrashInput): ForkJournalPayload =
        ForkJournalPayload(input.clientMutationId, ForkLifecycleExecutor.default.restoreFromTrash(input.mangaId))

    data class PreviewPurgeInput(
        val clientMutationId: String? = null,
        val mangaIds: List<Int>,
    )

    data class PreviewPurgePayload(
        val clientMutationId: String?,
        val preview: ForkPurgePreviewType,
    )

    @RequireAuth
    fun previewPurge(input: PreviewPurgeInput): PreviewPurgePayload {
        val p = ForkLifecycleExecutor.default.previewPurge(input.mangaIds)
        return PreviewPurgePayload(
            input.clientMutationId,
            ForkPurgePreviewType(
                p.nonce,
                p.mangaIds,
                p.mergedMangaRows,
                p.virtualChapterRows,
                p.libraryRemovals,
                p.sourceRowsDeleted,
                p.sourceFilesDeleted,
                p.expiresAt,
            ),
        )
    }

    data class ConfirmPurgeInput(
        val clientMutationId: String? = null,
        val nonce: String,
        /** Must be exactly "PURGE" while setting 1033 is on. */
        val confirmation: String? = null,
    )

    data class ConfirmPurgePayload(
        val clientMutationId: String?,
        val purged: Int,
    )

    @RequireAuth
    fun confirmPurge(input: ConfirmPurgeInput): ConfirmPurgePayload =
        ConfirmPurgePayload(input.clientMutationId, ForkLifecycleExecutor.default.confirmPurge(input.nonce, input.confirmation))

    data class UndoForkOperationInput(
        val clientMutationId: String? = null,
        val journalId: Int,
    )

    @RequireAuth
    fun undoForkOperation(input: UndoForkOperationInput): ForkJournalPayload {
        ForkLifecycleExecutor.default.undo(input.journalId)
        return ForkJournalPayload(input.clientMutationId, input.journalId)
    }
}
