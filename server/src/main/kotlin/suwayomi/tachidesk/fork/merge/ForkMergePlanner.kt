package suwayomi.tachidesk.fork.merge

import suwayomi.tachidesk.graphql.types.MergeDedupeStrategy
import suwayomi.tachidesk.graphql.types.MergeUnnumberedChapterMatching
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * Pure merge planner (no database, no clock). Preview and execution both call [plan] with the same
 * input, so a created merge always equals its preview (D28).
 *
 * Rules:
 * - MIRROR: chapters with a known number (>= 0) are grouped by that number. Unnumbered chapters are
 *   grouped by normalized title + scanlator (TITLE_SCANLATOR) or each kept separately (KEEP_ALL); they
 *   never collapse into a single "-1" group (D11). Offsets are ignored in mirror mode (D12).
 * - SEQUENCE: every chapter is its own entry; its number is the real number plus the source offset.
 * - SPLIT: entries of the single source restricted to `includedSourceChapterIds` (F4).
 * - Real numbers, names and scanlators are never rewritten (D2); they travel in the link.
 */
object ForkMergePlanner {
    fun plan(
        input: PlanInput,
        defaultStrategy: MergeDedupeStrategy,
        defaultUnnumbered: MergeUnnumberedChapterMatching,
        fallbacksEnabled: Boolean,
    ): MergePlan {
        val enabled = input.sources.filter { it.enabled }.sortedBy { it.rank }
        val rankOf = enabled.associate { it.sourceMangaId to it.rank }
        val strategy =
            input.options.dedupeStrategy
                ?.let { runCatching { MergeDedupeStrategy.valueOf(it) }.getOrNull() } ?: defaultStrategy
        val unnumbered =
            input.options.unnumberedMatching
                ?.let { runCatching { MergeUnnumberedChapterMatching.valueOf(it) }.getOrNull() } ?: defaultUnnumbered
        val warnings = mutableListOf<String>()

        val rawEntries: List<PlanEntry> =
            when (input.mode) {
                MergeMode.MIRROR -> {
                    mirrorEntries(enabled, rankOf, strategy, unnumbered, input.options, fallbacksEnabled)
                }

                MergeMode.SEQUENCE -> {
                    enabled.flatMap { source ->
                        source.chapters.sortedBy { it.sourceOrder }.map { chapter ->
                            PlanEntry(
                                entryKey = uniqueKey(chapter),
                                number = if (chapter.number >= 0f) chapter.number + source.offset else -1f,
                                primary = chapter,
                                fallbacks = emptyList(),
                                kind = LinkKind.CHAPTER,
                            )
                        }
                    }
                }

                MergeMode.SPLIT -> {
                    val included = input.options.includedSourceChapterIds.toSet()
                    if (enabled.size != 1) warnings += "A split view must have exactly one source"
                    enabled.flatMap { source ->
                        source.chapters
                            .filter { it.id in included }
                            .sortedBy { it.sourceOrder }
                            .map { chapter ->
                                PlanEntry(uniqueKey(chapter), chapter.number, chapter, emptyList(), LinkKind.CHAPTER)
                            }
                    }
                }
            }

        val hiddenByParts =
            input.options.partDefinitions
                .flatMap { it.hidesEntryKeys }
                .toSet()
        val excluded = input.options.excludedEntryKeys
        val chaptersById = input.sources.flatMap { it.chapters }.associateBy { it.id }

        val partEntries =
            input.options.partDefinitions.mapNotNull { definition ->
                val first = definition.parts.firstOrNull() ?: return@mapNotNull null
                val primary = chaptersById[first.sourceChapterId]
                if (primary == null) {
                    warnings += "Part definition '${definition.key}' points at a chapter that no longer exists"
                    return@mapNotNull null
                }
                PlanEntry(
                    entryKey = "p:${definition.key}",
                    number = definition.number,
                    primary = primary,
                    fallbacks = emptyList(),
                    kind = if (definition.parts.size == 1) LinkKind.PART else LinkKind.PARTS,
                    parts = definition.parts,
                    nameOverride = definition.name,
                )
            }

        val kept =
            (rawEntries.filterNot { it.entryKey in excluded || it.entryKey in hiddenByParts } + partEntries)
                .filterNot { it.entryKey in excluded }

        val ordered =
            when (input.mode) {
                MergeMode.SEQUENCE, MergeMode.SPLIT -> {
                    // Keep source order; part entries are placed by number among them.
                    val (parts, chapters) = kept.partition { it.kind != LinkKind.CHAPTER }
                    insertByNumber(chapters, parts)
                }

                MergeMode.MIRROR -> {
                    kept.sortedWith(mirrorOrder(rankOf))
                }
            }

        return MergePlan(
            entries = ordered,
            excludedEntryKeys = rawEntries.map { it.entryKey }.filter { it in excluded }.sorted(),
            warnings = warnings,
        )
    }

    private fun mirrorEntries(
        sources: List<PlanSource>,
        rankOf: Map<Int, Int>,
        strategy: MergeDedupeStrategy,
        unnumbered: MergeUnnumberedChapterMatching,
        options: MergeOptions,
        fallbacksEnabled: Boolean,
    ): List<PlanEntry> {
        val all = sources.flatMap { it.chapters }
        val groups = LinkedHashMap<String, MutableList<PlanChapter>>()
        all.forEach { chapter ->
            val key =
                when {
                    chapter.number >= 0f -> numberKey(chapter.number)
                    unnumbered == MergeUnnumberedChapterMatching.TITLE_SCANLATOR -> titleKey(chapter)
                    else -> uniqueKey(chapter)
                }
            groups.getOrPut(key) { mutableListOf() }.add(chapter)
        }

        return groups.map { (key, candidates) ->
            val preferred = options.preferredSourceByEntry[key]
            val ordered = sortCandidates(candidates, strategy, rankOf, preferred)
            PlanEntry(
                entryKey = key,
                number = ordered.first().number,
                primary = ordered.first().copy(name = "Chapter ${ordered.first().number}", scanlator = null),
                fallbacks = if (fallbacksEnabled) ordered.drop(1) else emptyList(),
                kind = LinkKind.CHAPTER,
                needsManualChoice =
                    strategy == MergeDedupeStrategy.MANUAL_PER_CHAPTER && preferred == null && candidates.size > 1,
            )
        }
    }

    /** Deterministic candidate order; every strategy ends with source rank, then chapter id. */
    internal fun sortCandidates(
        candidates: List<PlanChapter>,
        strategy: MergeDedupeStrategy,
        rankOf: Map<Int, Int>,
        preferredSourceMangaId: Int?,
    ): List<PlanChapter> {
        val byRank = compareBy<PlanChapter>({ rankOf[it.sourceMangaId] ?: Int.MAX_VALUE }, { it.id })
        val strategyOrder: Comparator<PlanChapter> =
            when (strategy) {
                MergeDedupeStrategy.PREFERRED_SOURCE, MergeDedupeStrategy.MANUAL_PER_CHAPTER -> byRank
                MergeDedupeStrategy.MOST_PAGES -> compareByDescending<PlanChapter> { it.pageCount }.then(byRank)
                MergeDedupeStrategy.NEWEST_UPLOAD -> compareByDescending<PlanChapter> { it.uploadDate }.then(byRank)
            }
        val preferredFirst =
            compareBy<PlanChapter> { if (preferredSourceMangaId != null && it.sourceMangaId == preferredSourceMangaId) 0 else 1 }
        return candidates.sortedWith(preferredFirst.then(strategyOrder))
    }

    private fun mirrorOrder(rankOf: Map<Int, Int>): Comparator<PlanEntry> =
        compareBy<PlanEntry>(
            { if (it.number >= 0f) 0 else 1 },
            { if (it.number >= 0f) it.number else 0f },
            { it.primary.uploadDate },
            { rankOf[it.primary.sourceMangaId] ?: Int.MAX_VALUE },
            { it.primary.sourceOrder },
            { it.entryKey },
        )

    private fun insertByNumber(
        chapters: List<PlanEntry>,
        parts: List<PlanEntry>,
    ): List<PlanEntry> {
        if (parts.isEmpty()) return chapters
        val result = chapters.toMutableList()
        parts.sortedBy { it.number }.forEach { part ->
            val index = result.indexOfFirst { it.number >= 0f && it.number > part.number }
            if (index < 0) result.add(part) else result.add(index, part)
        }
        return result
    }

    fun numberKey(number: Float): String = "n:" + number.toBigDecimal().stripTrailingZeros().toPlainString()

    fun titleKey(chapter: PlanChapter): String = "t:" + normalize(chapter.name) + "|" + normalize(chapter.scanlator ?: "")

    fun uniqueKey(chapter: PlanChapter): String = "c:${chapter.sourceMangaId}:${shortHash(chapter.url)}"

    fun normalize(text: String): String =
        Normalizer
            .normalize(text, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private fun shortHash(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it) }

    /** Stable URL of a virtual chapter row; also how stock code recognizes a virtual chapter without SQL. */
    fun virtualChapterUrl(
        groupId: Int,
        entryKey: String,
    ): String {
        val key = if (entryKey.length > 300) entryKey.take(200) + "~" + shortHash(entryKey) else entryKey
        return "$VIRTUAL_URL_PREFIX$groupId/$key"
    }

    const val VIRTUAL_URL_PREFIX = "fork-merge://"
}
