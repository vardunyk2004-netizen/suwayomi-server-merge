package suwayomi.tachidesk.fork.maintenance

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.ForkRequestException
import suwayomi.tachidesk.fork.ForkTestFixtures
import suwayomi.tachidesk.fork.lifecycle.ForkLifecycleStore
import suwayomi.tachidesk.fork.lifecycle.LifecycleState
import suwayomi.tachidesk.fork.merge.CreateMergeRequest
import suwayomi.tachidesk.fork.merge.ForkMergeService
import suwayomi.tachidesk.fork.merge.MergeMode
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** F12 doctor and F11 reindex guards. Written for CI; not run in the authoring sandbox. */
class ForkDoctorServiceTest : ApplicationTest() {
    private val merge = ForkMergeService(gate = ForkGate.allOn, policy = { ForkTestFixtures.allOnMergePolicy })
    private val doctor = ForkDoctorService(gate = ForkGate.allOn, merge = merge)

    @BeforeEach
    fun setUp() = ForkTestFixtures.reset()

    @AfterEach
    fun tearDown() = ForkTestFixtures.reset()

    @Test
    fun `seeded defects are detected, dry run changes nothing, repair is idempotent`() {
        val a = ForkTestFixtures.manga("D A")
        val b = ForkTestFixtures.manga("D B")
        val orphanHidden = ForkTestFixtures.manga("D hidden")
        ForkTestFixtures.chapter(a, 1f, 1)
        ForkTestFixtures.chapter(b, 1f, 1)
        val result = merge.create(CreateMergeRequest(listOf(a, b), MergeMode.MIRROR))
        val virtual = ForkTestFixtures.chaptersOf(result.mergedMangaId).single()[ChapterTable.id].value
        transaction { ChapterTable.update({ ChapterTable.id eq virtual }) { it[ChapterTable.isDownloaded] = true } }
        ForkLifecycleStore.set(orphanHidden, LifecycleState.HIDDEN_BY_MERGE, "stale", null)

        val dry = doctor.run(dryRun = null)
        assertTrue(dry.dryRun)
        assertEquals(
            setOf(DoctorIssueCode.FD003_HIDDEN_WITHOUT_OWNER, DoctorIssueCode.FD006_VIRTUAL_DOWNLOAD_FLAG),
            dry.issues.map { it.code }.toSet(),
        )
        assertTrue(ForkTestFixtures.chapterRow(virtual)[ChapterTable.isDownloaded], "dry run changed nothing")

        val repaired = doctor.run(dryRun = false)
        assertEquals(2, repaired.repaired)
        assertEquals(LifecycleState.ACTIVE, ForkLifecycleStore.stateOf(orphanHidden))
        assertEquals(0, doctor.run(dryRun = false).issues.size, "second repair finds nothing")
    }

    @Test
    fun `D21 reindex refuses while the download queue is busy and defaults to dry run`() {
        val busy = ForkReindexService(gate = ForkGate.allOn, queueBusy = { true })
        assertEquals(
            "DOWNLOADS_ACTIVE",
            assertFailsWith<ForkRequestException> { busy.fixFlags(listOf(1), emptyList(), dryRun = false) }.code,
        )

        val idle = ForkReindexService(gate = ForkGate.allOn, queueBusy = { false })
        val a = ForkTestFixtures.manga("R A")
        val chapter = ForkTestFixtures.chapter(a, 1f, 1, downloaded = true)
        assertEquals(1, idle.fixFlags(listOf(chapter), emptyList(), dryRun = true))
        assertTrue(ForkTestFixtures.chapterRow(chapter)[ChapterTable.isDownloaded], "dry run flips nothing")
        idle.fixFlags(listOf(chapter), emptyList(), dryRun = false)
        assertEquals(false, ForkTestFixtures.chapterRow(chapter)[ChapterTable.isDownloaded], "only the flag changes; no file is deleted")
    }
}
