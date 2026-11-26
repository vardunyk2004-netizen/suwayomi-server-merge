package suwayomi.tachidesk.fork

import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import suwayomi.tachidesk.fork.lifecycle.ForkLifecycleExecutor
import suwayomi.tachidesk.fork.lifecycle.ForkVisibility
import suwayomi.tachidesk.fork.merge.ForkMergeHooks
import suwayomi.tachidesk.fork.progress.ForkProgressService
import suwayomi.tachidesk.graphql.queries.ForkQuery
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * R8 / F1 open item: with default settings (every master off) the real resolvers and upstream hooks run no
 * SQL. Statements are counted on the enclosing transaction (`statementCount`); fork code joins it through
 * `dbTransaction`. Written for CI; not run in the authoring sandbox.
 */
class ForkOffEqualsStockTest : ApplicationTest() {
    @AfterEach
    fun tearDown() = ForkTestFixtures.reset()

    private fun statementsDuring(block: () -> Unit): Int =
        transaction {
            val before = statementCount
            block()
            statementCount - before
        }

    @Test
    fun `real resolvers refuse with FEATURE_DISABLED before any query`() {
        assertEquals(
            0,
            statementsDuring {
                assertFailsWith<ForkFeatureDisabledException> { ForkQuery().trashMangas() }
                assertFailsWith<ForkFeatureDisabledException> { ForkQuery().mergeInfo(1) }
                assertFailsWith<ForkFeatureDisabledException> { ForkQuery().forkJournal(10) }
                assertFailsWith<ForkFeatureDisabledException> { ForkLifecycleExecutor.default.trash() }
            },
        )
    }

    @Test
    fun `upstream hooks are no-ops without SQL`() {
        val manga = ForkTestFixtures.manga("Stock")
        val chapter = ForkTestFixtures.chapter(manga, 1f, 1)
        val rows = transaction { (ChapterTable innerJoin MangaTable).selectAll().toList() }
        assertEquals(
            0,
            statementsDuring {
                ForkProgressService.default.afterWrite(listOf(chapter))
                ForkProgressService.default.afterMarkPrevious(manga, 5)
                assertNull(ForkMergeHooks.redirectDownloads(rows))
                assertNull(ForkVisibility.visibleMangaOp())
                assertNull(ForkProgressService.default.trackerNumber(rows.first()))
                ForkMergeHooks.afterChapterListUpdate(rows.first())
                assertEquals(0, ForkLifecycleExecutor.default.purgeExpired())
            },
        )
    }

    @Test
    fun `migration check allows everything when merge is off`() {
        assertEquals(true, ForkQuery().forkMigrationCheck(1).allowed)
    }
}
