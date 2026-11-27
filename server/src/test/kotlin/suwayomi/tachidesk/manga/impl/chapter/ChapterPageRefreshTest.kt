package suwayomi.tachidesk.manga.impl.chapter

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.fork.ForkTestFixtures
import suwayomi.tachidesk.manga.impl.util.source.GetSource.registerSource
import suwayomi.tachidesk.manga.impl.util.source.GetSource.unregisterSource
import suwayomi.tachidesk.manga.impl.util.source.StubSource
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.PageTable
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterPageRefreshTest : ApplicationTest() {
    private val sourceId = 919191L
    private val source = PageSource(sourceId)

    @BeforeEach
    fun setUp() {
        ForkTestFixtures.reset()
        registerSource(sourceId to source)
    }

    @AfterEach
    fun tearDown() {
        unregisterSource(sourceId)
        ForkTestFixtures.reset()
    }

    @Test
    fun `D29 page-list refresh persists page rows and chapter page count`() = runTest {
        source.pageUrls = listOf("synthetic-page-0", "synthetic-page-1", "synthetic-page-2")
        val manga = ForkTestFixtures.manga("Synthetic", source = sourceId)
        val chapter = ForkTestFixtures.chapter(manga, 1f, 1, pageCount = 1, lastPageRead = 2)

        val refreshedCount = refreshChapterPageList(manga, chapter)
        val pageRows =
            transaction {
                PageTable.selectAll().where { PageTable.chapter eq chapter }.orderBy(PageTable.index to SortOrder.ASC).toList()
            }
        val chapterRow = ForkTestFixtures.chapterRow(chapter)

        assertEquals(3, refreshedCount)
        assertEquals(3, chapterRow[ChapterTable.pageCount])
        assertEquals(2, chapterRow[ChapterTable.lastPageRead])
        assertEquals(listOf(0, 1, 2), pageRows.map { it[PageTable.index] })
        assertEquals(source.pageUrls, pageRows.map { it[PageTable.url] })
    }

    private class PageSource(id: Long) : StubSource(id) {
        var pageUrls: List<String> = emptyList()

        override suspend fun getPageList(chapter: SChapter): List<Page> =
            pageUrls.mapIndexed { index, url -> Page(index, url) }
    }
}
