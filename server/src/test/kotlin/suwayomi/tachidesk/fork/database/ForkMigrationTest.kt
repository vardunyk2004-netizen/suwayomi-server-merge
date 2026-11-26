package suwayomi.tachidesk.fork.database

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import suwayomi.tachidesk.fork.ForkFeatureAvailability
import suwayomi.tachidesk.fork.ForkTestFixtures
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** F0001-F0003 runner tests (H2 locally, PostgreSQL in CI). Written for CI; not run in the authoring sandbox. */
class ForkMigrationTest : ApplicationTest() {
    @AfterEach
    fun tearDown() = ForkTestFixtures.reset()

    private fun ledger(): List<Int> =
        dbTransaction {
            exec("SELECT version FROM fork_migration ORDER BY version") { rs -> buildList { while (rs.next()) add(rs.getInt(1)) } }!!
        }

    @Test
    fun `all fork migrations are applied once and a second run adds nothing`() {
        assertEquals(listOf(1, 2, 3), ledger())
        ForkMigrationRunner.run()
        assertEquals(listOf(1, 2, 3), ledger())
        assertEquals(ForkFeatureAvailability.Status.AVAILABLE, ForkFeatureAvailability.status())
    }

    @Test
    fun `fork migrations are numbered consecutively`() {
        assertEquals((1..ForkMigrationRunner.migrations.size).toList(), ForkMigrationRunner.migrations.map { it.version })
    }

    @Test
    fun `D10 D25 deleting a source chapter sets the link null instead of cascading`() {
        val a = ForkTestFixtures.manga("FK A")
        val merged = ForkTestFixtures.manga("FK merged")
        val source = ForkTestFixtures.chapter(a, 1f, 1)
        val virtual = ForkTestFixtures.chapter(merged, 1f, 1)
        val link =
            transaction {
                ForkChapterLinkTable
                    .insertAndGetId {
                        it[mergedChapterId] = virtual
                        it[sourceChapterId] = source
                        it[sourceMangaId] = a
                        it[rank] = 0
                        it[originalName] = "1"
                        it[originalUrl] = "/1"
                        it[status] = "OK"
                        it[originalNumber] = 1f
                        it[originalUploadDate] = 0
                        it[entryKey] = "n:1"
                        it[linkKind] = "CHAPTER"
                        it[sourcePageCountSnapshot] = -1
                    }.value
            }
        transaction { ChapterTable.deleteWhere { ChapterTable.id eq source } }
        val row = transaction { ForkChapterLinkTable.selectAll().where { ForkChapterLinkTable.id eq link }.single() }
        assertNull(row[ForkChapterLinkTable.sourceChapterId])
        assertEquals(virtual, row[ForkChapterLinkTable.mergedChapterId], "the virtual chapter and its link survive")
    }
}
