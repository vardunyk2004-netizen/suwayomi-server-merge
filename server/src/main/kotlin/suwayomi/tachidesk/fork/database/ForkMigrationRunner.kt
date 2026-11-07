package suwayomi.tachidesk.fork.database

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.fork.ForkFeatureAvailability
import suwayomi.tachidesk.fork.database.migration.F0001_CoreSchema

object ForkMigrationRunner {
    private val logger = KotlinLogging.logger {}
    private val migrations: List<ForkMigration> = listOf(F0001_CoreSchema())

    fun run() {
        try {
            transaction {
                exec(
                    """
                    CREATE TABLE IF NOT EXISTS fork_migration (
                        version INT PRIMARY KEY,
                        name VARCHAR(200) NOT NULL,
                        applied_at BIGINT NOT NULL
                    )
                    """.trimIndent(),
                )
            }

            migrations.forEach { migration ->
                transaction {
                    if (!isApplied(migration.version)) {
                        migration.apply(this)
                        exec(
                            "INSERT INTO fork_migration (version, name, applied_at) VALUES (${migration.version}, '${migration.name}', ${System.currentTimeMillis()})",
                        )
                    }
                }
            }

            ForkFeatureAvailability.markAvailable()
        } catch (error: Exception) {
            ForkFeatureAvailability.markUnavailable()
            logger.error(error) { "Fork migrations unavailable; fork features disabled" }
        }
    }

    private fun JdbcTransaction.isApplied(version: Int): Boolean =
        exec("SELECT 1 FROM fork_migration WHERE version = $version") { resultSet -> resultSet.next() } ?: false
}
