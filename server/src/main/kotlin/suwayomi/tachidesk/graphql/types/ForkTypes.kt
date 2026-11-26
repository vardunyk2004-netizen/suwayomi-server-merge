package suwayomi.tachidesk.graphql.types

/*
 * GraphQL types of the fork (Suwayomi Next). They live in `suwayomi.tachidesk.graphql` because graphql-kotlin
 * only generates types from `supportedPackages` (TachideskGraphQLSchema.kt). Inputs are nullable with
 * conservative server-side defaults; destructive choices are enums or explicit flags that default to off (R11).
 */

enum class ForkMergeMode {
    MIRROR,
    SEQUENCE,
    SPLIT,
}

enum class ForkLifecycleAction {
    SOFT_REMOVE,
    REMOVE_MERGED,
    UNMERGE,
    REMOVE_SOURCE_FROM_MERGE,
}

enum class ForkLinkStatus {
    OK,
    SOURCE_MISSING,
    NEEDS_REVIEW,
}

enum class ForkLinkKind {
    CHAPTER,
    PART,
    PARTS,
}

enum class ForkMangaState {
    ACTIVE,
    HIDDEN_BY_MERGE,
    SOFT_REMOVED,
}

/** "Progress synced with source" / "Progress needs review" (design F6). */
enum class ForkProgressSyncStatus {
    SYNCED,
    NEEDS_REVIEW,
    OFF,
}

data class ForkMergeSourceType(
    val sourceMangaId: Int,
    val rank: Int,
    val offset: Double,
    val enabled: Boolean,
    val title: String,
    val sourceId: String,
    val state: ForkMangaState,
)

data class ForkChapterLinkType(
    val mergedChapterId: Int,
    val sourceChapterId: Int?,
    val sourceMangaId: Int,
    val originalNumber: Double,
    val originalName: String,
    val originalScanlator: String?,
    val status: ForkLinkStatus,
    val kind: ForkLinkKind,
    val entryKey: String,
    val pageStart: Int?,
    val pageEnd: Int?,
    val fallbackSourceMangaIds: List<Int>,
    /** Derived from the source chapter's download state, never copied (D23). */
    val sourceDownloaded: Boolean,
)

data class ForkPartDefinitionType(
    val key: String,
    val name: String,
    val number: Double,
    val pageCount: Int,
    val sourceChapterIds: List<Int>,
)

data class ForkMergeInfoType(
    val groupId: Int,
    val mergedMangaId: Int,
    val mode: ForkMergeMode,
    val coverSourceMangaId: Int?,
    val sources: List<ForkMergeSourceType>,
    val excludedEntryKeys: List<String>,
    val partDefinitions: List<ForkPartDefinitionType>,
    val chapterLinks: List<ForkChapterLinkType>,
    val progressSyncStatus: ForkProgressSyncStatus,
)

data class ForkMergePlanEntryType(
    val entryKey: String,
    val number: Double,
    val name: String,
    val scanlator: String?,
    val sourceMangaId: Int,
    val sourceChapterId: Int,
    val fallbackSourceMangaIds: List<Int>,
    val kind: ForkLinkKind,
    val needsManualChoice: Boolean,
)

data class ForkPreviewSourceType(
    val sourceMangaId: Int,
    val title: String,
    val chapterCount: Int,
)

data class ForkMergePreviewType(
    val sources: List<ForkPreviewSourceType>,
    val entries: List<ForkMergePlanEntryType>,
    val excludedEntryKeys: List<String>,
    val warnings: List<String>,
    val chapterCount: Int,
)

data class ForkMergeResultType(
    val groupId: Int,
    val mergedMangaId: Int,
    val journalId: Int,
    val chaptersAdded: Int,
    val chaptersRemoved: Int,
    val chaptersUpdated: Int,
    val chapterCount: Int,
    val warnings: List<String>,
)

data class ForkReconcileResultType(
    val chaptersAdded: Int,
    val chaptersRemoved: Int,
    val chaptersUpdated: Int,
    val chapterCount: Int,
)

data class ForkBatchMergeResultType(
    val index: Int,
    val ok: Boolean,
    val mergedMangaId: Int?,
    val errorCode: String?,
    val errorMessage: String?,
)

data class ForkSourceUpdateResultType(
    val sourceMangaId: Int,
    val ok: Boolean,
    val error: String?,
)

data class ForkMergeUpdateResultType(
    val mergedMangaId: Int,
    val sources: List<ForkSourceUpdateResultType>,
    val chaptersAdded: Int,
    val chaptersRemoved: Int,
    val chapterCount: Int,
    val flaggedForReview: Int,
)

data class ForkLifecyclePreviewType(
    val action: ForkLifecycleAction,
    val targetState: ForkMangaState,
    val virtualChaptersRemoved: Int,
    val sourcesRestored: List<Int>,
    val sourceRowsDeleted: Int,
    val sourceFilesDeleted: Int,
    val materializedFilesDeleted: Int,
    val writesProgressBack: Boolean,
    val sourceImpact: String,
)

data class ForkLifecycleResultType(
    val journalId: Int,
    val progressWrittenBack: Int,
)

data class ForkTrashItemType(
    val mangaId: Int,
    val title: String,
    val reason: String?,
    val removedAt: Long,
    val ownerGroupId: Int?,
    val isMerged: Boolean,
    /** Epoch seconds; 0 = kept forever. */
    val purgeAfter: Long,
)

data class ForkPurgePreviewType(
    val nonce: String,
    val mangaIds: List<Int>,
    val mergedMangaRows: Int,
    val virtualChapterRows: Int,
    val libraryRemovals: Int,
    val sourceRowsDeleted: Int,
    val sourceFilesDeleted: Int,
    val expiresAt: Long,
)

data class ForkJournalEntryType(
    val id: Int,
    val operation: String,
    val state: String,
    val groupId: Int?,
    val mangaId: Int?,
    val reversible: Boolean,
    val createdAt: Long,
    val undoneAt: Long?,
    val errorMessage: String?,
)

data class ForkMangaStateType(
    val mangaId: Int,
    val state: ForkMangaState,
    val isMerged: Boolean,
    val mergedIntoMangaId: Int?,
)

data class ForkMigrationCheckType(
    val mangaId: Int,
    val allowed: Boolean,
    val reason: String?,
)
