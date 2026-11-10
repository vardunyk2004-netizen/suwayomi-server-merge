package suwayomi.tachidesk.test

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.database.dbTransaction
import java.nio.file.Files
import java.nio.file.Path

class StateSnapshotTest : ApplicationTest() {
    @Test
    fun `snapshot ignores operation journal rows`(
        @TempDir downloadRoot: Path,
    ) {
        val before = captureStateSnapshot(downloadRoot.toFile())

        try {
            dbTransaction {
                exec(
                    """
                    INSERT INTO fork_merge_journal (operation, state, created_at, updated_at)
                    VALUES ('snapshot-test', 'APPLIED', 1, 1)
                    """.trimIndent(),
                )
            }

            assertEquals(before, captureStateSnapshot(downloadRoot.toFile()))
        } finally {
            dbTransaction {
                exec("DELETE FROM fork_merge_journal WHERE operation = 'snapshot-test'")
            }
        }
    }

    @Test
    fun `snapshot detects database rows and download file content`(
        @TempDir downloadRoot: Path,
    ) {
        val before = captureStateSnapshot(downloadRoot.toFile())
        val mangaId = createLibraryManga("Snapshot fixture")

        try {
            val withManga = captureStateSnapshot(downloadRoot.toFile())
            assertNotEquals(before.database, withManga.database, "database snapshot must include inserted manga rows")
            assertEquals(before.downloadFiles, withManga.downloadFiles)

            val chapterFile = downloadRoot.resolve("snapshot-fixture/chapter.cbz")
            Files.createDirectories(chapterFile.parent)
            Files.writeString(chapterFile, "first contents")

            val withDownload = captureStateSnapshot(downloadRoot.toFile())
            assertEquals(withManga.database, withDownload.database)
            assertNotEquals(
                withManga.downloadFiles,
                withDownload.downloadFiles,
                "download snapshot must hash file contents",
            )

            Files.writeString(chapterFile, "different contents")
            val withChangedContent = captureStateSnapshot(downloadRoot.toFile())
            assertNotEquals(withDownload.downloadFiles, withChangedContent.downloadFiles)
        } finally {
            transaction {
                MangaTable.deleteWhere { MangaTable.id eq mangaId }
            }
        }
    }
}
