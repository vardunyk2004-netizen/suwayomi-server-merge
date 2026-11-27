package suwayomi.tachidesk.fork.progress

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.ForkTestFixtures
import suwayomi.tachidesk.fork.merge.CreateMergeRequest
import suwayomi.tachidesk.fork.merge.ForkMergeService
import suwayomi.tachidesk.fork.merge.MergeMode
import suwayomi.tachidesk.graphql.mutations.ChapterMutation
import suwayomi.tachidesk.graphql.types.ProgressDirection
import suwayomi.tachidesk.manga.impl.Chapter
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** F6 chokepoint tests (D2, D13, D17). Written for CI; not run in the authoring sandbox. */
class ForkProgressServiceTest : ApplicationTest() {
    private var policy = ForkTestFixtures.allOnProgressPolicy
    private val service = ForkProgressService(gate = ForkGate.allOn, policy = { policy })
    private val merge = ForkMergeService(gate = ForkGate.allOn, policy = { ForkTestFixtures.allOnMergePolicy })
    private lateinit var original: ForkProgressService

    @BeforeEach
    fun setUp() {
        ForkTestFixtures.reset()
        policy = ForkTestFixtures.allOnProgressPolicy
        original = ForkProgressService.default
        ForkProgressService.default = service
    }

    @AfterEach
    fun tearDown() {
        ForkProgressService.default = original
        ForkTestFixtures.reset()
    }

    private fun merged(): Triple<Int, Int, Int> {
        val a = ForkTestFixtures.manga("P A")
        val b = ForkTestFixtures.manga("P B")
        val sourceChapter = ForkTestFixtures.chapter(a, 12.5f, 1, name = "Real twelve and a half", pageCount = 20)
        ForkTestFixtures.chapter(b, 12.5f, 1, pageCount = 20)
        val result = merge.create(CreateMergeRequest(listOf(a, b), MergeMode.MIRROR))
        val virtual = ForkTestFixtures.chaptersOf(result.mergedMangaId).single()[ChapterTable.id].value
        return Triple(result.mergedMangaId, virtual, sourceChapter)
    }

    private fun write(
        chapterId: Int,
        page: Int,
        at: Long,
        read: Boolean = false,
    ) = transaction {
        ChapterTable.update({ ChapterTable.id eq chapterId }) {
            it[ChapterTable.lastPageRead] = page
            it[ChapterTable.lastReadAt] = at
            it[ChapterTable.isRead] = read
        }
    }

    @Test
    fun `D17 virtual write propagates to every mirror source chapter`() {
        val (_, virtual, source) = merged()
        write(virtual, 7, 1_000)
        service.afterWrite(listOf(virtual))
        assertEquals(7, ForkTestFixtures.chapterRow(source)[ChapterTable.lastPageRead])
    }

    @Test
    fun `D17 source write propagates to the virtual chapter unless direction is to-source only`() {
        val (_, virtual, source) = merged()
        write(source, 4, 2_000, read = true)
        service.afterWrite(listOf(source))
        assertTrue(ForkTestFixtures.chapterRow(virtual)[ChapterTable.isRead])

        policy = policy.copy(direction = ProgressDirection.TO_SOURCE_ONLY)
        write(source, 9, 3_000, read = true)
        service.afterWrite(listOf(source))
        assertEquals(4, ForkTestFixtures.chapterRow(virtual)[ChapterTable.lastPageRead])
    }

    @Test
    fun `D17 the stock modifyChapter site reaches the chokepoint`() {
        val (merged, virtual, source) = merged()
        val index = ForkTestFixtures.chapterRow(virtual)[ChapterTable.sourceOrder]
        Chapter.modifyChapter(merged, index, isRead = true, isBookmarked = null, markPrevRead = null, lastPageRead = null)
        assertTrue(ForkTestFixtures.chapterRow(source)[ChapterTable.isRead])
    }

    @Test
    fun `D17 single and batch GraphQL chapter mutations propagate bookmarks`() {
        val (_, virtual, source) = merged()
        val mutation = ChapterMutation()

        mutation.updateChapter(
            ChapterMutation.UpdateChapterInput(
                id = virtual,
                patch = ChapterMutation.UpdateChapterPatch(isBookmarked = true),
            ),
        )
        assertTrue(ForkTestFixtures.chapterRow(source)[ChapterTable.isBookmarked])

        transaction {
            ChapterTable.update({ ChapterTable.id eq virtual }) {
                it[ChapterTable.isBookmarked] = false
            }
            ChapterTable.update({ ChapterTable.id eq source }) {
                it[ChapterTable.isBookmarked] = false
            }
        }
        mutation.updateChapters(
            ChapterMutation.UpdateChaptersInput(
                ids = listOf(virtual),
                patch = ChapterMutation.UpdateChapterPatch(isBookmarked = true),
            ),
        )
        assertTrue(ForkTestFixtures.chapterRow(source)[ChapterTable.isBookmarked])
    }

    @Test
    fun `read never reverts through propagation`() {
        val (_, virtual, source) = merged()
        write(source, 19, 1_000, read = true)
        service.afterWrite(listOf(source))
        write(virtual, 0, 5_000, read = false)
        service.afterWrite(listOf(virtual))
        assertTrue(ForkTestFixtures.chapterRow(source)[ChapterTable.isRead])
    }

    @Test
    fun `D2 tracker number comes from the link`() {
        val (_, virtual, _) = merged()
        assertEquals(12.5f, service.trackerNumber(ForkTestFixtures.chapterRow(virtual)))
    }

    @Test
    fun `D13 koreader uses the source chapter`() {
        val (_, virtual, source) = merged()
        assertEquals(source, service.koreaderSourceChapterId(virtual, ForkTestFixtures.chapterRow(virtual)[ChapterTable.url]))
        assertEquals(null, service.koreaderSourceChapterId(source, ForkTestFixtures.chapterRow(source)[ChapterTable.url]))
    }
}
