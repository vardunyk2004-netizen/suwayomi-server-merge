package suwayomi.tachidesk.fork.merge

import kotlinx.serialization.Serializable

/** Merge modes stored in `fork_merge_group.mode`. SPLIT is a series-split view over one manga (F4). */
enum class MergeMode {
    MIRROR,
    SEQUENCE,
    SPLIT,
}

/** `fork_chapter_link.status` / `fork_chapter_part.status`. */
enum class LinkStatus {
    OK,
    SOURCE_MISSING,
    NEEDS_REVIEW,
}

/** `fork_chapter_link.link_kind`. */
enum class LinkKind {
    CHAPTER,
    PART,
    PARTS,
}

/** A page range of one real source chapter (0-based, inclusive). Never points at a virtual chapter (D1). */
@Serializable
data class PartRange(
    val sourceChapterId: Int,
    val pageStart: Int,
    val pageEnd: Int,
    val sourcePageCount: Int,
)

/** User-defined chapter-level edit (F3): one virtual chapter built from page ranges. */
@Serializable
data class PartDefinition(
    val key: String,
    val name: String,
    val number: Float,
    val parts: List<PartRange>,
    /** Entry keys of plan entries hidden because this definition replaces them (D7: originals hidden, never deleted). */
    val hidesEntryKeys: List<String> = emptyList(),
)

/**
 * Per-merge options (`fork_merge_group.options`). Null fields fall back to the global settings.
 * This is the single owner of user edits to a merge plan; the planner derives everything else.
 */
@Serializable
data class MergeOptions(
    val dedupeStrategy: String? = null,
    val unnumberedMatching: String? = null,
    val chapterDisplay: String? = null,
    val offlineMode: String? = null,
    val excludedEntryKeys: Set<String> = emptySet(),
    /** entry key -> preferred source manga id (manual per-chapter choice). */
    val preferredSourceByEntry: Map<String, Int> = emptyMap(),
    /** SPLIT mode: the real chapter ids of the single source that belong to this view. */
    val includedSourceChapterIds: List<Int> = emptyList(),
    val partDefinitions: List<PartDefinition> = emptyList(),
)

data class MergeGroupRow(
    val id: Int,
    val mangaId: Int,
    val mode: MergeMode,
    val options: MergeOptions,
    val coverSourceMangaId: Int?,
)

data class MergeSourceRow(
    val sourceMangaId: Int,
    val rank: Int,
    val offset: Float,
    val enabled: Boolean,
)

/** A real chapter as seen by the planner (read from `chapter`). */
data class PlanChapter(
    val id: Int,
    val sourceMangaId: Int,
    val url: String,
    val name: String,
    val number: Float,
    val scanlator: String?,
    val uploadDate: Long,
    val pageCount: Int,
    val sourceOrder: Int,
)

data class PlanSource(
    val sourceMangaId: Int,
    val rank: Int,
    val offset: Float,
    val enabled: Boolean,
    val chapters: List<PlanChapter>,
)

data class PlanInput(
    val mode: MergeMode,
    val sources: List<PlanSource>,
    val options: MergeOptions,
)

data class PlanEntry(
    val entryKey: String,
    /** Display/sort number of the virtual chapter: the real number in MIRROR, real + offset in SEQUENCE. */
    val number: Float,
    val primary: PlanChapter,
    val fallbacks: List<PlanChapter>,
    val kind: LinkKind,
    val parts: List<PartRange> = emptyList(),
    val nameOverride: String? = null,
    val needsManualChoice: Boolean = false,
)

data class MergePlan(
    val entries: List<PlanEntry>,
    val excludedEntryKeys: List<String>,
    val warnings: List<String>,
)
