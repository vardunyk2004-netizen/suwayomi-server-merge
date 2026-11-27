package suwayomi.tachidesk.fork.progress

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import io.javalin.http.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.ForkTestFixtures
import suwayomi.tachidesk.fork.merge.CreateMergeRequest
import suwayomi.tachidesk.fork.merge.ForkMergeService
import suwayomi.tachidesk.fork.merge.MergeMode
import suwayomi.tachidesk.graphql.mutations.KoreaderSyncMutation
import suwayomi.tachidesk.manga.controller.MangaController
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.manga.impl.backup.proto.SyncRestoreMode
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupMangaHandler
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupChapter
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupManga
import suwayomi.tachidesk.manga.impl.sync.KoreaderSyncService
import suwayomi.tachidesk.manga.impl.util.source.GetSource.registerSource
import suwayomi.tachidesk.manga.impl.util.source.GetSource.unregisterSource
import suwayomi.tachidesk.manga.impl.util.source.StubSource
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForkProgressWritePathTest : ApplicationTest() {
    private val sourceId = 919192L
    private val fakeSource = FakeProgressSource(sourceId)
    private val merge = ForkMergeService(gate = ForkGate.allOn, policy = { ForkTestFixtures.allOnMergePolicy })
    private lateinit var originalProgressService: ForkProgressService
    private lateinit var fixture: ProgressFixture

    @BeforeEach
    fun setUp() {
        ForkTestFixtures.reset()
        originalProgressService = ForkProgressService.default
        registerSource(sourceId to fakeSource)
        fixture = mergedFixture()
        mockkObject(KoreaderSyncService)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(KoreaderSyncService)
        ForkProgressService.default = originalProgressService
        unregisterSource(sourceId)
        ForkTestFixtures.reset()
    }

    private fun installProgressService(enabled: Boolean) {
        ForkProgressService.default =
            ForkProgressService(
                gate = if (enabled) ForkGate.allOn else ForkGate.allOff,
                policy = { ForkTestFixtures.allOnProgressPolicy },
            )
    }

    private fun mergedFixture(): ProgressFixture {
        val sourceManga = ForkTestFixtures.manga("D17 source fixture", source = sourceId)
        val otherManga = ForkTestFixtures.manga("D17 mirror fixture", source = sourceId + 1)
        val sourceChapter = ForkTestFixtures.chapter(sourceManga, 7f, 0, name = "Fixture chapter", pageCount = 4)
        ForkTestFixtures.chapter(otherManga, 7f, 0, name = "Fixture chapter", pageCount = 4)
        val result = merge.create(CreateMergeRequest(listOf(sourceManga, otherManga), MergeMode.MIRROR))
        val virtualChapter = ForkTestFixtures.chaptersOf(result.mergedMangaId).single()[ChapterTable.id].value
        return ProgressFixture(sourceManga, sourceChapter, virtualChapter)
    }

    private fun assertRoutePropagation(enabled: Boolean) {
        installProgressService(enabled)
        val result = KoreaderSyncService.SyncResult(pageRead = 2, timestamp = 2_000, device = "local-test", shouldUpdate = true)
        coEvery { KoreaderSyncService.checkAndPullProgress(fixture.sourceChapterId) } returns result

        val context = mockk<Context>(relaxed = true)
        every { context.pathParam("mangaId") } returns fixture.sourceMangaId.toString()
        every { context.pathParam("chapterIndex") } returns "0"
        every { context.attribute<UserType>(Attribute.TachideskUser.name) } returns UserType.Admin(1)

        MangaController.chapterRetrieve.handle(context)

        coVerify(timeout = 5_000) { KoreaderSyncService.checkAndPullProgress(fixture.sourceChapterId) }
        verify(timeout = 5_000) { context.json(any<ChapterDataClass>()) }
        assertEquals(2, ForkTestFixtures.chapterRow(fixture.sourceChapterId)[ChapterTable.lastPageRead])
        assertEquals(
            if (enabled) 2 else 0,
            ForkTestFixtures.chapterRow(fixture.virtualChapterId)[ChapterTable.lastPageRead],
        )
    }

    @Test
    fun `D17 REST chapter retrieval propagates a pulled progress update when enabled`() {
        assertRoutePropagation(enabled = true)
    }

    @Test
    fun `D17 REST chapter retrieval keeps the stock write when propagation is disabled`() {
        assertRoutePropagation(enabled = false)
    }

    private fun assertKoReaderPropagation(enabled: Boolean) {
        installProgressService(enabled)
        coEvery {
            KoreaderSyncService.checkAndPullProgress(fixture.sourceChapterId)
        } returns KoreaderSyncService.SyncResult(pageRead = 3, timestamp = 3_000, device = "local-test", shouldUpdate = true)

        val result =
            runBlocking {
                KoreaderSyncMutation()
                    .pullKoSyncProgress(KoreaderSyncMutation.PullKoSyncProgressInput(chapterId = fixture.sourceChapterId))
                    .get()
            }

        assertEquals(3, result?.chapter?.lastPageRead)
        assertEquals(3, ForkTestFixtures.chapterRow(fixture.sourceChapterId)[ChapterTable.lastPageRead])
        assertEquals(
            if (enabled) 3 else 0,
            ForkTestFixtures.chapterRow(fixture.virtualChapterId)[ChapterTable.lastPageRead],
        )
    }

    @Test
    fun `D17 KOReader pull propagates progress when enabled`() {
        assertKoReaderPropagation(enabled = true)
    }

    @Test
    fun `D17 KOReader pull keeps the stock write when propagation is disabled`() {
        assertKoReaderPropagation(enabled = false)
    }

    private fun assertBackupRestorePropagation(enabled: Boolean) {
        installProgressService(enabled)
        val mangaRow = ForkTestFixtures.mangaRow(fixture.sourceMangaId)!!
        val chapterRow = ForkTestFixtures.chapterRow(fixture.sourceChapterId)
        val backup =
            BackupManga(
                source = mangaRow[MangaTable.sourceReference],
                url = mangaRow[MangaTable.url],
                title = "D17 source fixture",
                chapters =
                    listOf(
                        BackupChapter(
                            url = chapterRow[ChapterTable.url],
                            name = chapterRow[ChapterTable.name],
                            chapterNumber = chapterRow[ChapterTable.chapter_number],
                            sourceOrder = chapterRow[ChapterTable.sourceOrder],
                            read = true,
                            lastPageRead = 1,
                        ),
                    ),
            )
        val flags =
            BackupFlags(
                includeManga = true,
                includeCategories = false,
                includeChapters = true,
                includeTracking = false,
                includeHistory = false,
                includeClientData = false,
                includeServerSettings = false,
            )
        val errors = mutableListOf<Pair<java.util.Date, String>>()

        BackupMangaHandler.restore(backup, emptyMap(), emptyMap(), errors, flags, SyncRestoreMode.NONE)

        assertTrue(errors.isEmpty())
        assertTrue(ForkTestFixtures.chapterRow(fixture.sourceChapterId)[ChapterTable.isRead])
        assertEquals(
            enabled,
            ForkTestFixtures.chapterRow(fixture.virtualChapterId)[ChapterTable.isRead],
        )
    }

    @Test
    fun `D17 backup restore propagates seeded progress when enabled`() {
        assertBackupRestorePropagation(enabled = true)
    }

    @Test
    fun `D17 backup restore keeps the stock write when propagation is disabled`() {
        assertBackupRestorePropagation(enabled = false)
    }

    private data class ProgressFixture(
        val sourceMangaId: Int,
        val sourceChapterId: Int,
        val virtualChapterId: Int,
    )

    private class FakeProgressSource(
        id: Long,
    ) : StubSource(id) {
        override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(
            Page(0, "fixture-page-0"),
            Page(1, "fixture-page-1"),
        )
    }
}
