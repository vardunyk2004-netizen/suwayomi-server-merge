package suwayomi.tachidesk.fork.merge

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.ForkRequestException
import suwayomi.tachidesk.fork.ForkTestFixtures
import suwayomi.tachidesk.fork.database.ForkChapterLinkTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** F7 resolution, F3 parts and F4 split views (D1, D6, D7, D8). Written for CI; not run in the authoring sandbox. */
class ForkResolutionAndPartsTest : ApplicationTest() {
    private val policy = ForkTestFixtures.allOnMergePolicy.copy(chapterLevelEnabled = true)
    private val service = ForkMergeService(gate = ForkGate.allOn, policy = { policy })
    private val pageCounts = mutableMapOf<Int, Int>()
    private val parts =
        ForkChapterPartService(
            gate = ForkGate.allOn,
            policy = { policy },
            merge = service,
            pageCountOf = { pageCounts.getValue(it) },
        )

    @BeforeEach
    fun setUp() {
        ForkTestFixtures.reset()
        ForkPageResolver.gate = ForkGate.allOn
        ForkPageResolver.policy = { policy }
    }

    @AfterEach
    fun tearDown() {
        ForkPageResolver.gate = ForkGate.production
        ForkTestFixtures.reset()
    }

    private fun mergedWith(vararg numbers: Float): Pair<MergeResult, List<Int>> {
        val a = ForkTestFixtures.manga("X A")
        val b = ForkTestFixtures.manga("X B")
        val ids = numbers.mapIndexed { i, n -> ForkTestFixtures.chapter(a, n, i + 1, pageCount = 10).also { pageCounts[it] = 10 } }
        ForkTestFixtures.chapter(b, 99f, 1)
        return service.create(CreateMergeRequest(listOf(a, b), MergeMode.MIRROR)) to ids
    }

    private fun virtualFor(
        mergedMangaId: Int,
        number: Float,
    ) = ForkTestFixtures.chaptersOf(mergedMangaId).first { it[ChapterTable.chapter_number] == number }[ChapterTable.id].value

    @Test
    fun `D8 missing source chapter is an explicit error`() {
        val (result, ids) = mergedWith(1f)
        val virtual = virtualFor(result.mergedMangaId, 1f)
        transaction { ChapterTable.deleteWhere { ChapterTable.id eq ids.single() } }
        val error = assertFailsWith<ForkResolutionException> { ForkPageResolver.candidates(virtual) }
        assertEquals("SOURCE_MISSING", error.code)
    }

    @Test
    fun `D1 merging chapters keeps the source rows and points parts at them`() {
        val (result, ids) = mergedWith(1f, 2f)
        val v1 = virtualFor(result.mergedMangaId, 1f)
        val v2 = virtualFor(result.mergedMangaId, 2f)
        runBlocking { parts.mergeChapters(listOf(v1, v2), "Both") }

        ids.forEach { assertTrue(ForkTestFixtures.chapterRow(it)[ChapterTable.id].value == it, "source chapter $it still exists") }
        val merged = ForkTestFixtures.chaptersOf(result.mergedMangaId).single { it[ChapterTable.name] == "Both" }
        val candidate = ForkPageResolver.candidates(merged[ChapterTable.id].value).single()
        assertEquals(ids, candidate.ranges.map { it.sourceChapterId })
        assertEquals(20, candidate.ranges.sumOf { it.pageEnd - it.pageStart + 1 })
    }

    @Test
    fun `D7 split hides the original and a page count change flags needs review`() {
        val (result, ids) = mergedWith(5f)
        val v = virtualFor(result.mergedMangaId, 5f)
        runBlocking { parts.splitChapter(v, listOf(4)) }

        val names = ForkTestFixtures.chaptersOf(result.mergedMangaId).map { it[ChapterTable.name] }
        assertTrue(names.none { it == "Chapter 5.0" }, "original hidden, not shown next to its parts")
        assertEquals(2, names.count { it.contains("/2)") })
        assertTrue(ForkTestFixtures.chapterRow(ids.single())[ChapterTable.id].value == ids.single(), "original source row kept")

        ForkChapterPartService.markNeedsReview(ids.single())
        val statuses =
            transaction {
                ForkChapterLinkTable
                    .selectAll()
                    .where { ForkChapterLinkTable.sourceChapterId eq ids.single() }
                    .map { it[ForkChapterLinkTable.status] }
            }
        assertTrue(statuses.isNotEmpty() && statuses.all { it == LinkStatus.NEEDS_REVIEW.name })
    }

    @Test
    fun `D6 foreign and repeated chapters are rejected`() {
        val a = ForkTestFixtures.manga("S A")
        val other = ForkTestFixtures.manga("S other")
        val own = ForkTestFixtures.chapter(a, 1f, 1)
        val foreign = ForkTestFixtures.chapter(other, 1f, 1)
        assertEquals(
            "FOREIGN_CHAPTER",
            assertFailsWith<ForkRequestException> { ForkSeriesSplitPlanner.validate(a, listOf(own, foreign), emptySet()) }.code,
        )
        assertEquals(
            "DUPLICATE_CHAPTER",
            assertFailsWith<ForkRequestException> { ForkSeriesSplitPlanner.validate(a, listOf(own, own), emptySet()) }.code,
        )
        assertEquals(
            "CHAPTER_IN_OTHER_VIEW",
            assertFailsWith<ForkRequestException> { ForkSeriesSplitPlanner.validate(a, listOf(own), setOf(own)) }.code,
        )
    }
}
