package suwayomi.tachidesk.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.server.database.dbTransaction
import java.nio.file.Files
import java.nio.file.Path

class StateSnapshotTest : ApplicationTest() {
    @Test
    fun `snapshot ignores operation journal rows`(
        @TempDir downloadRoot: Path,
    ) {
        assertTrue(isIgnoredSnapshotTable("fork_merge_journal"))
        assertTrue(isIgnoredSnapshotTable("FORK_MERGE_JOURNAL"))
    }

    @Test
    fun `snapshot detects database rows and download file content`(
        @TempDir downloadRoot: Path,
    ) {
        dbTransaction {
            exec("CREATE TABLE snapshot_test_fixture (id BIGINT PRIMARY KEY, value VARCHAR(255))")
        }

        try {
            val before = captureStateSnapshot(downloadRoot.toFile())
            dbTransaction {
                exec("INSERT INTO snapshot_test_fixture (id, value) VALUES (1, 'first')")
            }

            val withRow = captureStateSnapshot(downloadRoot.toFile())
            assertNotEquals(before.database, withRow.database, "database snapshot must include inserted rows")
            assertEquals(before.downloadFiles, withRow.downloadFiles)

            val chapterFile = downloadRoot.resolve("snapshot-fixture/chapter.cbz")
            Files.createDirectories(chapterFile.parent)
            Files.writeString(chapterFile, "first contents")

            val withDownload = captureStateSnapshot(downloadRoot.toFile())
            assertEquals(withRow.database, withDownload.database)
            assertNotEquals(
                withRow.downloadFiles,
                withDownload.downloadFiles,
                "download snapshot must hash file contents",
            )

            Files.writeString(chapterFile, "different contents")
            val withChangedContent = captureStateSnapshot(downloadRoot.toFile())
            assertNotEquals(withDownload.downloadFiles, withChangedContent.downloadFiles)
        } finally {
            dbTransaction {
                exec("DROP TABLE snapshot_test_fixture")
            }
        }
    }
}
