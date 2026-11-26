package suwayomi.tachidesk.fork.merge

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.ForkRequestException
import suwayomi.tachidesk.fork.ForkTestFixtures
import suwayomi.tachidesk.fork.database.ForkChapterLinkTable
import suwayomi.tachidesk.fork.lifecycle.ForkLifecycleStore
import suwayomi.tachidesk.fork.lifecycle.LifecycleState
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Database tests for linked merge (F2/F7). Written for CI; not run in the authoring sandbox. */
class ForkMergeServiceTest : ApplicationTest() {
    private val service = ForkMergeService(gate = ForkGate.allOn, policy = { ForkTestFixtures.allOnMergePolicy })

    @BeforeEach
    fun setUp() = ForkTestFixtures.reset()

    @AfterEach
    fun tearDown() = ForkTestFixtures.reset()

    private fun twoSources(): Pair<Int, Int> {
        val a = ForkTestFixtures.manga("Hero A")
        val b = ForkTestFixtures.manga("Hero B")
        ForkTestFixtures.chapter(a, 1f, 1, downloaded = true)
        ForkTestFixtures.chapter(a, 2f, 2)
        ForkTestFixtures.chapter(b, 2f, 1)
        ForkTestFixtures.chapter(b, 3f, 2)
        return a to b
    }

    @Test
    fun `D28 created links equal the preview plan`() {
        val (a, b) = twoSources()
        val request = CreateMergeRequest(listOf(a, b), MergeMode.MIRROR)
        val preview = service.preview(request)
        val result = service.create(request)

        val links =
            transaction {
                (ForkChapterLinkTable innerJoin ChapterTable)
                    .selectAll()
                    .where { ChapterTable.manga eq result.mergedMangaId }
                    .orderBy(ChapterTable.sourceOrder)
                    .map { it[ForkChapterLinkTable.entryKey] to it[ForkChapterLinkTable.sourceChapterId] }
            }
        assertEquals(preview.plan.entries.map { it.entryKey to it.primary.id }, links)
    }

    @Test
    fun `D23 virtual chapters never copy the download flag`() {
        val (a, b) = twoSources()
        val result = service.create(CreateMergeRequest(listOf(a, b), MergeMode.MIRROR))
        assertTrue(ForkTestFixtures.chaptersOf(result.mergedMangaId).none { it[ChapterTable.isDownloaded] })
    }

    @Test
    fun `D10 nested merge and double membership are rejected`() {
        val (a, b) = twoSources()
        val c = ForkTestFixtures.manga("Hero C")
        val merged = service.create(CreateMergeRequest(listOf(a, b), MergeMode.MIRROR)).mergedMangaId

        assertEquals(
            "NESTED_MERGE",
            assertFailsWith<ForkRequestException> { service.create(CreateMergeRequest(listOf(merged, c), MergeMode.MIRROR)) }.code,
        )
        assertEquals(
            "ALREADY_MERGED",
            assertFailsWith<ForkRequestException> { service.create(CreateMergeRequest(listOf(a, c), MergeMode.MIRROR)) }.code,
        )
        assertEquals(
            "MIN_SOURCES",
            assertFailsWith<ForkRequestException> { service.create(CreateMergeRequest(listOf(c), MergeMode.MIRROR)) }.code,
        )
    }

    @Test
    fun `decision 1 B sources keep library state and are hidden by lifecycle only`() {
        val (a, b) = twoSources()
        val result = service.create(CreateMergeRequest(listOf(a, b), MergeMode.MIRROR))
        transaction {
            assertTrue(MangaTable.selectAll().where { MangaTable.id inList listOf(a, b) }.all { it[MangaTable.inLibrary] })
        }
        assertEquals(LifecycleState.HIDDEN_BY_MERGE, ForkLifecycleStore.stateOf(a))
        assertEquals(result.groupId, ForkLifecycleStore.get(b).ownerGroupId)
        assertEquals(ForkMergedSource.ID, ForkTestFixtures.mangaRow(result.mergedMangaId)!![MangaTable.sourceReference])
    }

    @Test
    fun `D20 batch reports per group and keeps others`() {
        val (a, b) = twoSources()
        val c = ForkTestFixtures.manga("Other C")
        val d = ForkTestFixtures.manga("Other D")
        val outcomes =
            service.createBatch(
                listOf(
                    CreateMergeRequest(listOf(a, b), MergeMode.MIRROR),
                    CreateMergeRequest(listOf(a, c), MergeMode.MIRROR),
                    CreateMergeRequest(listOf(c, d), MergeMode.MIRROR),
                ),
            )
        assertEquals(listOf(true, false, true), outcomes.map { it.result != null })
        assertEquals("ALREADY_MERGED", outcomes[1].errorCode)
    }

    @Test
    fun `exclusion is journaled and undo restores the chapter`() {
        val (a, b) = twoSources()
        val result = service.create(CreateMergeRequest(listOf(a, b), MergeMode.MIRROR))
        val before = ForkTestFixtures.chaptersOf(result.mergedMangaId).size
        service.updateOptions(result.groupId) { it.copy(excludedEntryKeys = it.excludedEntryKeys + "n:3") }
        assertEquals(before - 1, ForkTestFixtures.chaptersOf(result.mergedMangaId).size)

        val journalId =
            suwayomi.tachidesk.fork.journal.ForkJournal
                .recent(1)
                .single()
                .id
        service.undoOptions(journalId)
        assertEquals(before, ForkTestFixtures.chaptersOf(result.mergedMangaId).size)
    }
}
