package suwayomi.tachidesk.fork.merge

import suwayomi.tachidesk.graphql.types.MergeDedupeStrategy
import suwayomi.tachidesk.graphql.types.MergeUnnumberedChapterMatching
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure planner tests. Written for CI; not run in the authoring sandbox. */
class ForkMergePlannerTest {
    private var nextId = 1

    private fun ch(
        source: Int,
        number: Float,
        order: Int,
        name: String = "Chapter $number",
        scanlator: String? = null,
        pages: Int = 10,
        upload: Long = 0,
    ) = PlanChapter(nextId++, source, "/$source/$order", name, number, scanlator, upload, pages, order)

    private fun plan(
        mode: MergeMode,
        sources: List<PlanSource>,
        options: MergeOptions = MergeOptions(),
        strategy: MergeDedupeStrategy = MergeDedupeStrategy.PREFERRED_SOURCE,
        unnumbered: MergeUnnumberedChapterMatching = MergeUnnumberedChapterMatching.TITLE_SCANLATOR,
    ) = ForkMergePlanner.plan(PlanInput(mode, sources, options), strategy, unnumbered, fallbacksEnabled = true)

    @Test
    fun `D2 real chapter numbers and names are preserved`() {
        val a = PlanSource(1, 0, 0f, true, listOf(ch(1, 1f, 1, "Start", "GroupA"), ch(1, 1.5f, 2, "Extra"), ch(1, 2f, 3, "Next")))
        val b = PlanSource(2, 1, 0f, true, listOf(ch(2, 1f, 1, "Begin"), ch(2, 2f, 2, "Continue")))
        val result = plan(MergeMode.MIRROR, listOf(a, b))

        assertEquals(listOf(1f, 1.5f, 2f), result.entries.map { it.number })
        assertEquals(listOf("Start", "Extra", "Next"), result.entries.map { it.primary.name })
        assertEquals(
            "GroupA",
            result.entries
                .first()
                .primary
                .scanlator,
        )
        assertEquals(
            listOf(2),
            result.entries
                .first()
                .fallbacks
                .map { it.sourceMangaId },
        )
    }

    @Test
    fun `D11 unnumbered chapters do not collapse`() {
        val a = PlanSource(1, 0, 0f, true, (1..5).map { ch(1, -1f, it, "Special $it") })
        val b = PlanSource(2, 1, 0f, true, (1..5).map { ch(2, -1f, it, "Omake $it") })

        val byTitle = plan(MergeMode.MIRROR, listOf(a, b))
        assertEquals(10, byTitle.entries.size)

        val keepAll = plan(MergeMode.MIRROR, listOf(a, b), unnumbered = MergeUnnumberedChapterMatching.KEEP_ALL)
        assertEquals(10, keepAll.entries.size)

        val sameTitles = PlanSource(2, 1, 0f, true, (1..5).map { ch(2, -1f, it, "Special $it") })
        assertEquals(5, plan(MergeMode.MIRROR, listOf(a, sameTitles)).entries.size, "same title+scanlator dedupes")
    }

    @Test
    fun `D12 mirror ignores offsets and sequence applies them`() {
        val a = PlanSource(1, 0, 0f, true, listOf(ch(1, 1f, 1), ch(1, 2f, 2)))
        val b = PlanSource(2, 1, 10f, true, listOf(ch(2, 1f, 1), ch(2, 2f, 2)))

        assertEquals(listOf(1f, 2f), plan(MergeMode.MIRROR, listOf(a, b)).entries.map { it.number })
        assertEquals(listOf(1f, 2f, 11f, 12f), plan(MergeMode.SEQUENCE, listOf(a, b)).entries.map { it.number })
    }

    @Test
    fun `dedupe strategies choose deterministically`() {
        val a = PlanSource(1, 0, 0f, true, listOf(ch(1, 1f, 1, pages = 5, upload = 100)))
        val b = PlanSource(2, 1, 0f, true, listOf(ch(2, 1f, 1, pages = 9, upload = 50)))

        assertEquals(
            1,
            plan(MergeMode.MIRROR, listOf(a, b)).entries
                .single()
                .primary
                .sourceMangaId,
        )
        assertEquals(
            2,
            plan(MergeMode.MIRROR, listOf(a, b), strategy = MergeDedupeStrategy.MOST_PAGES).entries
                .single()
                .primary
                .sourceMangaId,
        )
        assertEquals(
            1,
            plan(MergeMode.MIRROR, listOf(a, b), strategy = MergeDedupeStrategy.NEWEST_UPLOAD).entries
                .single()
                .primary
                .sourceMangaId,
        )
        val manual = plan(MergeMode.MIRROR, listOf(a, b), strategy = MergeDedupeStrategy.MANUAL_PER_CHAPTER)
        assertTrue(manual.entries.single().needsManualChoice)
        val chosen =
            plan(
                MergeMode.MIRROR,
                listOf(a, b),
                MergeOptions(preferredSourceByEntry = mapOf("n:1" to 2)),
                MergeDedupeStrategy.MANUAL_PER_CHAPTER,
            )
        assertEquals(
            2,
            chosen.entries
                .single()
                .primary
                .sourceMangaId,
        )
    }

    @Test
    fun `exclusions and part definitions hide entries without deleting sources`() {
        val c1 = ch(1, 1f, 1)
        val c2 = ch(1, 2f, 2)
        val source = PlanSource(1, 0, 0f, true, listOf(c1, c2))
        val other = PlanSource(2, 1, 0f, true, emptyList())
        val options =
            MergeOptions(
                excludedEntryKeys = setOf("n:2"),
                partDefinitions =
                    listOf(
                        PartDefinition("split:${c1.id}:000", "Part A", 1f, listOf(PartRange(c1.id, 0, 4, 10)), listOf("n:1")),
                    ),
            )
        val result = plan(MergeMode.MIRROR, listOf(source, other), options)

        assertEquals(listOf("p:split:${c1.id}:000"), result.entries.map { it.entryKey })
        assertEquals(listOf("n:2"), result.excludedEntryKeys)
        assertEquals(LinkKind.PART, result.entries.single().kind)
        assertEquals(
            c1.id,
            result.entries
                .single()
                .parts
                .single()
                .sourceChapterId,
            "parts point at the real chapter (D1)",
        )
    }

    @Test
    fun `D28 the same input always produces the same plan`() {
        val sources =
            listOf(
                PlanSource(1, 0, 0f, true, (1..30).map { ch(1, (it % 7).toFloat() - 1f, it, "T$it") }),
                PlanSource(2, 1, 0f, true, (1..30).map { ch(2, (it % 5).toFloat(), it, "T$it") }),
            )
        val first = plan(MergeMode.MIRROR, sources)
        val second = plan(MergeMode.MIRROR, sources.reversed())
        assertEquals(first.entries.map { it.entryKey to it.primary.id }, second.entries.map { it.entryKey to it.primary.id })
    }

    @Test
    fun `split view keeps only included chapters in source order`() {
        val chapters = (1..6).map { ch(1, it.toFloat(), it) }
        val result =
            plan(
                MergeMode.SPLIT,
                listOf(PlanSource(1, 0, 0f, true, chapters)),
                MergeOptions(includedSourceChapterIds = listOf(chapters[4].id, chapters[1].id)),
            )
        assertEquals(listOf(2f, 5f), result.entries.map { it.number })
    }

    /** The WebUI hard-codes this decimal string (src/features/fork/Fork.constants.ts); both must change together. */
    @Test
    fun `reserved merged source id matches the WebUI constant`() {
        assertEquals("6005353588250330436", ForkMergedSource.ID.toString())
        assertEquals(
            "SWMERGED",
            String(
                java.nio.ByteBuffer.allocate(8)
                    .putLong(ForkMergedSource.ID)
                    .array(),
                Charsets.US_ASCII,
            ),
        )
    }
}
