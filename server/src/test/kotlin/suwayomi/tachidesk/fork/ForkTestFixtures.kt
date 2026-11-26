package suwayomi.tachidesk.fork

import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.fork.database.forkTablesChildFirst
import suwayomi.tachidesk.fork.merge.ForkMergedSource
import suwayomi.tachidesk.manga.impl.util.lang.EMPTY
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable

/** Test-only fixtures for fork tests. Written for CI; not run in the authoring sandbox. */
object ForkTestFixtures {
    fun manga(
        title: String,
        source: Long = 1L,
        inLibrary: Boolean = true,
    ): Int =
        transaction {
            MangaTable
                .insertAndGetId {
                    it[MangaTable.title] = title
                    it[MangaTable.url] = "/$title-${System.nanoTime()}"
                    it[MangaTable.sourceReference] = source
                    it[MangaTable.inLibrary] = inLibrary
                    it[MangaTable.memo] = JsonObject.EMPTY
                }.value
        }

    fun chapter(
        mangaId: Int,
        number: Float,
        order: Int,
        name: String = "Chapter $number",
        scanlator: String? = null,
        read: Boolean = false,
        lastPageRead: Int = 0,
        lastReadAt: Long = 0,
        pageCount: Int = -1,
        downloaded: Boolean = false,
        url: String = "/c/$mangaId/$order/${System.nanoTime()}",
    ): Int =
        transaction {
            ChapterTable
                .insertAndGetId {
                    it[ChapterTable.url] = url
                    it[ChapterTable.name] = name
                    it[ChapterTable.chapter_number] = number
                    it[ChapterTable.scanlator] = scanlator
                    it[ChapterTable.sourceOrder] = order
                    it[ChapterTable.manga] = mangaId
                    it[ChapterTable.isRead] = read
                    it[ChapterTable.lastPageRead] = lastPageRead
                    it[ChapterTable.lastReadAt] = lastReadAt
                    it[ChapterTable.pageCount] = pageCount
                    it[ChapterTable.isDownloaded] = downloaded
                    it[ChapterTable.memo] = JsonObject.EMPTY
                }.value
        }

    fun chapterRow(id: Int) = transaction { ChapterTable.selectAll().where { ChapterTable.id eq id }.first() }

    fun mangaRow(id: Int) = transaction { MangaTable.selectAll().where { MangaTable.id eq id }.firstOrNull() }

    fun chaptersOf(mangaId: Int) = transaction { ChapterTable.selectAll().where { ChapterTable.manga eq mangaId }.toList() }

    /** Removes fork rows first, then merged manga rows and all test manga. */
    fun reset() {
        transaction {
            forkTablesChildFirst.forEach { it.deleteAll() }
            ChapterTable.deleteAll()
            MangaTable.deleteWhere { MangaTable.sourceReference eq ForkMergedSource.ID }
            MangaTable.deleteAll()
        }
    }

    val allOnMergePolicy = ForkMergePolicy()
    val allOnProgressPolicy = ForkProgressPolicy()
}
