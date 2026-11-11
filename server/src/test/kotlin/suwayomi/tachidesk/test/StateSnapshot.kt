package suwayomi.tachidesk.test

import suwayomi.tachidesk.server.database.dbTransaction
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal data class StateSnapshot(
    val database: Map<String, List<Map<String, String?>>>,
    val downloadFiles: Map<String, String>,
)

/** Captures durable state for non-destructive operation tests. Journal rows and bookkeeping timestamps are ignored. */
internal fun captureStateSnapshot(downloadRoot: File): StateSnapshot {
    val database =
        dbTransaction {
            val tables =
                exec(
                    """
                    SELECT TABLE_NAME
                    FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_SCHEMA = CURRENT_SCHEMA
                      AND TABLE_TYPE = 'BASE TABLE'
                    ORDER BY TABLE_NAME
                    """.trimIndent(),
                ) { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.getString(1))
                    }
                } ?: emptyList()

            tables
                .filterNot(::isIgnoredSnapshotTable)
                .associate { table ->
                    val quotedTable = table.replace("\"", "\"\"")
                    val rows =
                        exec("SELECT * FROM \"$quotedTable\"") { resultSet ->
                            val metadata = resultSet.metaData
                            val columns =
                                (1..metadata.columnCount)
                                    .map { index -> metadata.getColumnLabel(index) }
                                    .filterNot { it.lowercase(Locale.ROOT) in ignoredTimestampColumns }
                                    .sorted()

                            buildList {
                                while (resultSet.next()) {
                                    add(
                                        columns
                                            .associateWith { column -> resultSet.getObject(column).snapshotString() }
                                            .toSortedMap(),
                                    )
                                }
                            }.sortedBy { row -> row.entries.joinToString("|") { (key, value) -> "$key=$value" } }
                        } ?: emptyList()

                    table.lowercase(Locale.ROOT) to rows
                }.toSortedMap()
        }

    val downloadFiles =
        if (!downloadRoot.isDirectory) {
            emptyMap()
        } else {
            downloadRoot
                .walkTopDown()
                .filter(File::isFile)
                .associate { file ->
                    file.relativeTo(downloadRoot).invariantSeparatorsPath to file.sha256()
                }.toSortedMap()
        }

    return StateSnapshot(database, downloadFiles)
}

private val ignoredTimestampColumns = setOf("applied_at", "created_at", "updated_at", "changed_at")

internal fun isIgnoredSnapshotTable(table: String): Boolean = table.equals("fork_merge_journal", ignoreCase = true)

private fun Any?.snapshotString(): String? =
    when (this) {
        null -> null
        is ByteArray -> joinToString("") { byte -> "%02x".format(byte) }
        else -> toString()
    }

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
