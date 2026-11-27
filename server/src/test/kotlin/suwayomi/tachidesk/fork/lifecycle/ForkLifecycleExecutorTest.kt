package suwayomi.tachidesk.fork.lifecycle

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.fork.ForkClock
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.ForkLifecyclePolicy
import suwayomi.tachidesk.fork.ForkRequestException
import suwayomi.tachidesk.fork.ForkTestFixtures
import suwayomi.tachidesk.fork.merge.CreateMergeRequest
import suwayomi.tachidesk.fork.merge.ForkChapterPartService
import suwayomi.tachidesk.fork.merge.ForkMergeService
import suwayomi.tachidesk.fork.merge.MergeMode
import suwayomi.tachidesk.fork.progress.ForkProgressService
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.captureStateSnapshot
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Snapshot tests per deletion-matrix row (F5). Written for CI; not run in the authoring sandbox. */
class ForkLifecycleExecutorTest : ApplicationTest() {
    private var policy = ForkLifecyclePolicy()
    private val merge = ForkMergeService(gate = ForkGate.allOn, policy = { ForkTestFixtures.allOnMergePolicy })
    private val parts =
        ForkChapterPartService(
            gate = ForkGate.allOn,
            policy = { ForkTestFixtures.allOnMergePolicy.copy(chapterLevelEnabled = true) },
            merge = merge,
            pageCountOf = { 10 },
        )
    private val progress = ForkProgressService(gate = ForkGate.allOn, policy = { ForkTestFixtures.allOnProgressPolicy })
    private val executor =
        ForkLifecycleExecutor(
            gate = ForkGate.allOn,
            policy = { policy },
            mergePolicy = { ForkTestFixtures.allOnMergePolicy },
            merge = merge,
            progress = progress,
        )

    @BeforeEach
    fun setUp() {
        policy = ForkLifecyclePolicy()
        ForkTestFixtures.reset()
    }

    @AfterEach
    fun tearDown() {
        ForkClock.nowSeconds = { System.currentTimeMillis() / 1000 }
        ForkTestFixtures.reset()
    }

    private data class Fixture(
        val a: Int,
        val b: Int,
        val c: Int,
        val merged: Int,
        val groupId: Int,
        val aChapter: Int,
        val cChapter: Int,
    )

    private fun fixture(): Fixture {
        val a = ForkTestFixtures.manga("Src A")
        val b = ForkTestFixtures.manga("Src B")
        val c = ForkTestFixtures.manga("Src C")
        val aChapter = ForkTestFixtures.chapter(a, 1f, 1, pageCount = 10)
        ForkTestFixtures.chapter(b, 1f, 1, pageCount = 10)
        val cChapter = ForkTestFixtures.chapter(c, 2f, 1, pageCount = 10)
        val result = merge.create(CreateMergeRequest(listOf(a, b, c), MergeMode.MIRROR))
        return Fixture(a, b, c, result.mergedMangaId, result.groupId, aChapter, cChapter)
    }

    private fun assertStateUnchangedExcept(
        before: suwayomi.tachidesk.test.StateSnapshot,
        after: suwayomi.tachidesk.test.StateSnapshot,
        vararg changedTables: String,
    ) {
        val changed = changedTables.toSet()
        val beforeDatabase = before.database.filterKeys { !it.startsWith("fork_") && it !in changed }
        val afterDatabase = after.database.filterKeys { !it.startsWith("fork_") && it !in changed }
        assertEquals(beforeDatabase, afterDatabase, "unrelated upstream database state changed")
        assertEquals(before.downloadFiles, after.downloadFiles, "download files changed")
    }

    private fun sourceChapterIds(f: Fixture): Set<Int> =
        listOf(f.a, f.b, f.c)
            .flatMap(ForkTestFixtures::chaptersOf)
            .map { it[ChapterTable.id].value }
            .toSet()

    @Test
    fun `D3 unmerge removes virtual rows and keeps source progress`() {
        val f = fixture()
        val virtual = ForkTestFixtures.chaptersOf(f.merged).first { it[ChapterTable.chapter_number] == 1f }[ChapterTable.id].value
        transaction {
            ChapterTable.update({ ChapterTable.id eq virtual }) {
                it[ChapterTable.isRead] = true
                it[ChapterTable.lastPageRead] = 9
                it[ChapterTable.lastReadAt] = 5_000
            }
        }
        val downloads = File(Injekt.get<ApplicationDirs>().downloadsRoot)
        val sourceIdsBefore = sourceChapterIds(f)
        val before = captureStateSnapshot(downloads)
        val outcome = executor.apply(LifecycleActionRequest(LifecycleAction.UNMERGE, f.merged))
        val after = captureStateSnapshot(downloads)

        assertTrue(ForkTestFixtures.chaptersOf(f.merged).isEmpty(), "no ghost virtual rows (D3)")
        assertStateUnchangedExcept(before, after, "chapter")
        assertEquals(sourceIdsBefore, sourceChapterIds(f), "unmerge must keep every source chapter row")
        assertTrue(ForkTestFixtures.chapterRow(f.aChapter)[ChapterTable.isRead], "progress written back")
        assertEquals(LifecycleState.ACTIVE, ForkLifecycleStore.stateOf(f.a))
        assertEquals(LifecycleState.SOFT_REMOVED, ForkLifecycleStore.stateOf(f.merged))
        assertTrue(outcome.progressWrittenBack > 0)

        executor.undo(outcome.journalId)
        assertEquals(LifecycleState.HIDDEN_BY_MERGE, ForkLifecycleStore.stateOf(f.a))
        assertTrue(ForkTestFixtures.chaptersOf(f.merged).isNotEmpty(), "undo rebuilds the merge")
    }

    @Test
    fun `D4 removing a source makes it visible and keeps the minimum`() {
        val f = fixture()
        val cVirtual = ForkTestFixtures.chaptersOf(f.merged).single { it[ChapterTable.chapter_number] == 2f }[ChapterTable.id].value
        transaction {
            ChapterTable.update({ ChapterTable.id eq cVirtual }) {
                it[ChapterTable.isRead] = true
                it[ChapterTable.lastPageRead] = 7
                it[ChapterTable.lastReadAt] = 6_000
            }
        }
        val downloads = File(Injekt.get<ApplicationDirs>().downloadsRoot)
        val sourceIdsBefore = sourceChapterIds(f)
        val before = captureStateSnapshot(downloads)
        executor.apply(LifecycleActionRequest(LifecycleAction.REMOVE_SOURCE_FROM_MERGE, f.merged, sourceMangaId = f.c))
        val after = captureStateSnapshot(downloads)

        assertEquals(LifecycleState.ACTIVE, ForkLifecycleStore.stateOf(f.c))
        assertStateUnchangedExcept(before, after, "chapter")
        assertEquals(sourceIdsBefore, sourceChapterIds(f), "removing a source must keep every source chapter row")
        assertTrue(ForkTestFixtures.chapterRow(f.cChapter)[ChapterTable.isRead], "source progress is written back")
        // Two sources left: the planner refuses before anything is written.
        assertFailsWith<IllegalArgumentException> {
            executor.apply(LifecycleActionRequest(LifecycleAction.REMOVE_SOURCE_FROM_MERGE, f.merged, sourceMangaId = f.b))
        }
        assertEquals(LifecycleState.HIDDEN_BY_MERGE, ForkLifecycleStore.stateOf(f.b))
        assertTrue(ForkTestFixtures.chaptersOf(f.merged).none { it[ChapterTable.chapter_number] == 2f }, "c's chapter left the merge")
    }

    @Test
    fun `D5 removing the merged manga leaves a removed source removed`() {
        val f = fixture()
        transaction {
            MangaTable.update({ MangaTable.id eq f.a }) { it[MangaTable.inLibrary] = false }
        }
        val downloads = File(Injekt.get<ApplicationDirs>().downloadsRoot)
        val before = captureStateSnapshot(downloads)
        executor.apply(LifecycleActionRequest(LifecycleAction.REMOVE_MERGED, f.merged))
        val after = captureStateSnapshot(downloads)

        assertStateUnchangedExcept(before, after)
        assertEquals(false, ForkTestFixtures.mangaRow(f.a)!![MangaTable.inLibrary])
        assertEquals(LifecycleState.HIDDEN_BY_MERGE, ForkLifecycleStore.stateOf(f.b), "restoreSources defaults to false (setting 1032)")
    }

    @Test
    fun `D16 removing merged with delete downloads keeps source files`() {
        val f = fixture()
        val downloads = File(Injekt.get<ApplicationDirs>().downloadsRoot)
        val sourceFile =
            File(downloads, "fork-test/Src A/Chapter 1/001.png").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
        val before = captureStateSnapshot(downloads)
        executor.apply(LifecycleActionRequest(LifecycleAction.REMOVE_MERGED, f.merged, deleteMaterializedDownloads = true))
        val after = captureStateSnapshot(downloads)

        assertEquals(before.downloadFiles, after.downloadFiles, "no file changed")
        assertTrue(sourceFile.exists())
        // Soft removal only writes the fork lifecycle row: every upstream table is byte-identical.
        listOf("manga", "chapter", "categorymanga", "trackrecord").forEach { table ->
            assertEquals(before.database[table], after.database[table], "$table changed")
        }
        sourceFile.parentFile
            .parentFile
            .parentFile
            .deleteRecursively()
    }

    @Test
    fun `D22 trash lists restores and retention purges`() {
        val f = fixture()
        val downloads = File(Injekt.get<ApplicationDirs>().downloadsRoot)
        val sourceIdsBefore = sourceChapterIds(f)
        val beforeRemove = captureStateSnapshot(downloads)
        ForkClock.nowSeconds = { 1_000_000 }
        executor.apply(LifecycleActionRequest(LifecycleAction.SOFT_REMOVE, f.merged, reason = "test"))
        val afterRemove = captureStateSnapshot(downloads)
        assertStateUnchangedExcept(beforeRemove, afterRemove)
        val item = executor.trash().single()
        assertEquals(f.merged, item.mangaId)
        assertEquals(1_000_000 + 30 * 86_400L, item.purgeAfter)

        executor.restoreFromTrash(f.merged)
        val afterRestore = captureStateSnapshot(downloads)
        assertStateUnchangedExcept(afterRemove, afterRestore)
        assertTrue(executor.trash().isEmpty())

        executor.apply(LifecycleActionRequest(LifecycleAction.SOFT_REMOVE, f.merged))
        ForkClock.nowSeconds = { 1_000_000 + 31 * 86_400L }
        val beforePurge = captureStateSnapshot(downloads)
        assertEquals(1, executor.purgeExpired())
        val afterPurge = captureStateSnapshot(downloads)
        assertStateUnchangedExcept(beforePurge, afterPurge, "manga", "chapter")
        assertEquals(sourceIdsBefore, sourceChapterIds(f), "purge must preserve source chapter rows")
        assertNull(ForkTestFixtures.mangaRow(f.merged), "merged row purged")
        assertEquals(LifecycleState.ACTIVE, ForkLifecycleStore.stateOf(f.a), "sources are released, never stuck hidden")
        assertTrue(ForkTestFixtures.mangaRow(f.a)!![MangaTable.inLibrary], "source rows untouched")
    }

    @Test
    fun `purge needs the nonce and the typed confirmation`() {
        val f = fixture()
        val downloads = File(Injekt.get<ApplicationDirs>().downloadsRoot)
        val sourceIdsBefore = sourceChapterIds(f)
        executor.apply(LifecycleActionRequest(LifecycleAction.SOFT_REMOVE, f.merged))
        val beforePreview = captureStateSnapshot(downloads)
        val preview = executor.previewPurge(listOf(f.merged))
        val afterPreview = captureStateSnapshot(downloads)
        assertStateUnchangedExcept(beforePreview, afterPreview)
        assertEquals(0, preview.sourceRowsDeleted)
        assertEquals(0, preview.sourceFilesDeleted)
        assertEquals("CONFIRMATION_REQUIRED", assertFailsWith<ForkRequestException> { executor.confirmPurge(preview.nonce, "purge") }.code)
        val afterRejectedConfirmation = captureStateSnapshot(downloads)
        assertStateUnchangedExcept(afterPreview, afterRejectedConfirmation)
        val again = executor.previewPurge(listOf(f.merged))
        val beforePurge = captureStateSnapshot(downloads)
        assertEquals(1, executor.confirmPurge(again.nonce, "PURGE"))
        val afterPurge = captureStateSnapshot(downloads)
        assertStateUnchangedExcept(beforePurge, afterPurge, "manga", "chapter")
        assertEquals(sourceIdsBefore, sourceChapterIds(f), "confirmed purge must retain every source chapter row")
        assertTrue(ForkTestFixtures.mangaRow(f.a) != null, "confirmed purge must retain source manga rows")
        assertEquals("NONCE_INVALID", assertFailsWith<ForkRequestException> { executor.confirmPurge(again.nonce, "PURGE") }.code)
    }

    @Test
    fun `merge split undo trash and purge preserve source download hashes`() {
        val downloads = File(Injekt.get<ApplicationDirs>().downloadsRoot)
        val matrixRoot = File(downloads, "fork-test/matrix-${System.nanoTime()}")
        val sourceFile =
            File(matrixRoot, "source/chapter/page.bin").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(3, 1, 4, 1, 5))
            }
        try {
            val a = ForkTestFixtures.manga("Matrix A")
            val b = ForkTestFixtures.manga("Matrix B")
            ForkTestFixtures.chapter(a, 1f, 1, pageCount = 10)
            ForkTestFixtures.chapter(b, 1f, 1, pageCount = 10)
            val before = captureStateSnapshot(downloads).downloadFiles

            val result = merge.create(CreateMergeRequest(listOf(a, b), MergeMode.MIRROR))
            val afterMerge = captureStateSnapshot(downloads).downloadFiles
            assertEquals(before, afterMerge, "merge changed download files")

            val virtual = ForkTestFixtures.chaptersOf(result.mergedMangaId).single()[ChapterTable.id].value
            runBlocking { parts.splitChapter(virtual, listOf(5)) }
            assertEquals(before, captureStateSnapshot(downloads).downloadFiles, "split changed download files")

            val unmerge = executor.apply(LifecycleActionRequest(LifecycleAction.UNMERGE, result.mergedMangaId))
            assertEquals(before, captureStateSnapshot(downloads).downloadFiles, "unmerge changed download files")
            executor.undo(unmerge.journalId)
            assertEquals(before, captureStateSnapshot(downloads).downloadFiles, "undo changed download files")

            executor.apply(LifecycleActionRequest(LifecycleAction.SOFT_REMOVE, result.mergedMangaId))
            assertEquals(before, captureStateSnapshot(downloads).downloadFiles, "trash changed download files")
            executor.restoreFromTrash(result.mergedMangaId)
            executor.apply(LifecycleActionRequest(LifecycleAction.SOFT_REMOVE, result.mergedMangaId))
            val purge = executor.previewPurge(listOf(result.mergedMangaId))
            executor.confirmPurge(purge.nonce, "PURGE")
            assertEquals(before, captureStateSnapshot(downloads).downloadFiles, "purge changed download files")
            assertTrue(sourceFile.exists())
        } finally {
            matrixRoot.deleteRecursively()
        }
    }
}
