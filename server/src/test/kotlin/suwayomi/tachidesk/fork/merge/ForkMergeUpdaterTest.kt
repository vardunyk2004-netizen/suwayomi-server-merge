package suwayomi.tachidesk.fork.merge

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.ForkTestFixtures
import suwayomi.tachidesk.fork.database.ForkChapterLinkTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** F8 update tests (D9, D25, D26). Written for CI; not run in the authoring sandbox. */
class ForkMergeUpdaterTest : ApplicationTest() {
    private val service = ForkMergeService(gate = ForkGate.allOn, policy = { ForkTestFixtures.allOnMergePolicy })
    private val fetched = mutableListOf<Int>()
    private var failing: Int? = null
    private val updater =
        ForkMergeUpdater(
            gate = ForkGate.allOn,
            policy = { ForkTestFixtures.allOnMergePolicy },
            service = service,
            fetchSource = { id ->
                fetched += id
                if (id == failing) error("source down")
            },
        )

    @BeforeEach
    fun setUp() {
        ForkTestFixtures.reset()
        fetched.clear()
        failing = null
    }

    @AfterEach
    fun tearDown() = ForkTestFixtures.reset()

    @Test
    fun `D9 exclusion survives update and a failing source is isolated`() {
        val a = ForkTestFixtures.manga("U A")
        val b = ForkTestFixtures.manga("U B")
        ForkTestFixtures.chapter(a, 1f, 1)
        ForkTestFixtures.chapter(b, 2f, 1)
        val result = service.create(CreateMergeRequest(listOf(a, b), MergeMode.MIRROR))
        service.updateOptions(result.groupId) { it.copy(excludedEntryKeys = setOf("n:2")) }
        failing = a

        val update = runBlocking { updater.refreshMerged(result.mergedMangaId, fetchSources = true) }

        assertEquals(listOf(false, true), update.sources.sortedBy { it.sourceMangaId != a }.map { it.ok })
        assertEquals(listOf(1f), ForkTestFixtures.chaptersOf(result.mergedMangaId).map { it[ChapterTable.chapter_number] })
    }

    @Test
    fun `D25 re-uploaded chapter re-links and keeps progress`() {
        val a = ForkTestFixtures.manga("R A")
        val b = ForkTestFixtures.manga("R B")
        val old = ForkTestFixtures.chapter(a, 3f, 1)
        ForkTestFixtures.chapter(b, 4f, 1)
        val result = service.create(CreateMergeRequest(listOf(a, b), MergeMode.MIRROR))
        val virtual =
            ForkTestFixtures.chaptersOf(result.mergedMangaId).first { it[ChapterTable.chapter_number] == 3f }[ChapterTable.id].value
        transaction {
            ChapterTable.update({ ChapterTable.id eq virtual }) { it[ChapterTable.lastPageRead] = 5 }
        }

        // the source re-uploads chapter 3: stock deletes the row (link FK -> SET NULL) and inserts a new one
        transaction { ChapterTable.deleteWhere { ChapterTable.id eq old } }
        val reuploaded = ForkTestFixtures.chapter(a, 3f, 1)
        updater.afterSourceUpdated(a)

        val link = transaction { ForkChapterLinkTable.selectAll().where { ForkChapterLinkTable.mergedChapterId eq virtual }.single() }
        assertEquals(reuploaded, link[ForkChapterLinkTable.sourceChapterId])
        assertEquals(LinkStatus.OK.name, link[ForkChapterLinkTable.status])
        assertEquals(5, ForkTestFixtures.chapterRow(virtual)[ChapterTable.lastPageRead], "same virtual row, progress kept")
    }

    @Test
    fun `removed source chapter is flagged SOURCE_MISSING not deleted`() {
        val a = ForkTestFixtures.manga("M A")
        val b = ForkTestFixtures.manga("M B")
        val gone = ForkTestFixtures.chapter(a, 7f, 1)
        ForkTestFixtures.chapter(b, 8f, 1)
        val result = service.create(CreateMergeRequest(listOf(a, b), MergeMode.MIRROR))
        transaction { ChapterTable.deleteWhere { ChapterTable.id eq gone } }
        updater.afterSourceUpdated(a)

        val rows = ForkTestFixtures.chaptersOf(result.mergedMangaId)
        assertEquals(2, rows.size)
        val virtual = rows.first { it[ChapterTable.chapter_number] == 7f }[ChapterTable.id].value
        val link = transaction { ForkChapterLinkTable.selectAll().where { ForkChapterLinkTable.mergedChapterId eq virtual }.single() }
        assertEquals(LinkStatus.SOURCE_MISSING.name, link[ForkChapterLinkTable.status])
    }

    @Test
    fun `D26 merged refresh does not fetch sources a second time`() {
        val a = ForkTestFixtures.manga("N A")
        val b = ForkTestFixtures.manga("N B")
        val result = service.create(CreateMergeRequest(listOf(a, b), MergeMode.MIRROR))
        runBlocking { updater.refreshMerged(result.mergedMangaId, fetchSources = false) }
        assertTrue(fetched.isEmpty())
    }
}
