package suwayomi.tachidesk.fork.lifecycle

/**
 * Produces the conservative, side-effect-free lifecycle plan shared by preview and execution.
 *
 * Database and filesystem changes belong to the later lifecycle executor. Keeping this planner
 * pure makes it impossible for a UI/API preview to acquire a destructive side effect.
 */
object ForkLifecyclePlanner {
    fun plan(request: LifecycleRequest): LifecyclePlan {
        require(request.mergedMangaId > 0) { "A lifecycle target must be a positive manga ID" }
        require(request.sourceMangaIds.all { it > 0 }) { "Source manga IDs must be positive" }
        require(request.sourceMangaIds.distinct().size == request.sourceMangaIds.size) {
            "Source manga IDs must be unique"
        }

        val sourceToRestore =
            when (request.action) {
                LifecycleAction.REMOVE_SOURCE_FROM_MERGE -> {
                    listOf(requireSourceToRemove(request))
                }

                LifecycleAction.SOFT_REMOVE -> {
                    if (request.restoreSources) request.sourceMangaIdsEligibleForRestore.distinct() else emptyList()
                }

                LifecycleAction.REMOVE_MERGED,
                LifecycleAction.UNMERGE,
                -> {
                    emptyList()
                }
            }

        val virtualChaptersToRemove =
            when (request.action) {
                LifecycleAction.UNMERGE -> request.virtualChapterIds.distinct()

                LifecycleAction.REMOVE_SOURCE_FROM_MERGE,
                LifecycleAction.REMOVE_MERGED,
                LifecycleAction.SOFT_REMOVE,
                -> emptyList()
            }

        return LifecyclePlan(
            targetLifecycleState =
                when (request.action) {
                    LifecycleAction.REMOVE_SOURCE_FROM_MERGE,
                    LifecycleAction.UNMERGE,
                    -> LifecycleState.ACTIVE

                    LifecycleAction.REMOVE_MERGED,
                    LifecycleAction.SOFT_REMOVE,
                    -> LifecycleState.SOFT_REMOVED
                },
            virtualChapterIdsToRemove = virtualChaptersToRemove,
            sourceMangaIdsToRestore = sourceToRestore,
            sourceMangaIdsToRemove = emptyList(),
            sourceDownloadIdsToDelete = emptyList(),
            materializedDownloadIdsToDelete =
                if (request.action == LifecycleAction.REMOVE_MERGED && request.deleteMaterializedDownloads) {
                    request.materializedDownloadIds.distinct()
                } else {
                    emptyList()
                },
            requiresProgressWriteBack = request.action == LifecycleAction.UNMERGE,
            journal = LifecycleJournal(request.action),
        )
    }

    private fun requireSourceToRemove(request: LifecycleRequest): Int {
        val sourceMangaId = requireNotNull(request.sourceMangaIdToRemove) { "Removing a source requires its manga ID" }
        require(sourceMangaId in request.sourceMangaIds) { "The source must belong to the merge" }
        require(request.sourceMangaIds.size > 2) { "A merge must retain at least two sources" }
        return sourceMangaId
    }
}

enum class LifecycleAction {
    REMOVE_SOURCE_FROM_MERGE,
    REMOVE_MERGED,
    SOFT_REMOVE,
    UNMERGE,
}

enum class LifecycleState {
    ACTIVE,
    HIDDEN_BY_MERGE,
    SOFT_REMOVED,
}

enum class LifecycleJournalState {
    PLANNED,
}

data class LifecycleRequest(
    val action: LifecycleAction,
    val mergedMangaId: Int,
    val sourceMangaIds: List<Int> = emptyList(),
    val sourceMangaIdToRemove: Int? = null,
    val sourceMangaIdsEligibleForRestore: List<Int> = emptyList(),
    val virtualChapterIds: List<Int> = emptyList(),
    val sourceDownloadIds: List<Int> = emptyList(),
    val materializedDownloadIds: List<Int> = emptyList(),
    val restoreSources: Boolean = false,
    val deleteMaterializedDownloads: Boolean = false,
)

data class LifecyclePlan(
    val targetLifecycleState: LifecycleState,
    val virtualChapterIdsToRemove: List<Int>,
    val sourceMangaIdsToRestore: List<Int>,
    val sourceMangaIdsToRemove: List<Int>,
    val sourceDownloadIdsToDelete: List<Int>,
    val materializedDownloadIdsToDelete: List<Int>,
    val requiresProgressWriteBack: Boolean,
    val journal: LifecycleJournal,
)

data class LifecycleJournal(
    val action: LifecycleAction,
    val state: LifecycleJournalState = LifecycleJournalState.PLANNED,
    val reversible: Boolean = true,
)
