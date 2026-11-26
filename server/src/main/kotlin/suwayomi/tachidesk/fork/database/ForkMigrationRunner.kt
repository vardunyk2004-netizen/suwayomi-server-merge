package suwayomi.tachidesk.fork.database

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.fork.ForkFeatureAvailability
import suwayomi.tachidesk.fork.database.migration.F0001_CoreSchema
import suwayomi.tachidesk.fork.database.migration.F0002_MergeLinkDetails
import suwayomi.tachidesk.fork.database.migration.F0003_MaintenanceTables
import suwayomi.tachidesk.fork.merge.ForkMergedSource

object ForkMigrationRunner {
    private val logger = KotlinLogging.logger {}
    internal val migrations: List<ForkMigration> =
        listOf(
            F0001_CoreSchema(),
            F0002_MergeLinkDetails(),
            F0003_MaintenanceTables(),
        )

    fun run() {
        try {
            check(migrations.map { it.version } == (1..migrations.size).toList()) {
                "Fork migrations must be numbered consecutively from 1"
            }
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
            // Registering the reserved virtual source is in-memory only (no SQL).
            ForkMergedSource.ensureRegistered()
        } catch (error: Exception) {
            ForkFeatureAvailability.markUnavailable()
            logger.error(error) { "Fork migrations unavailable; fork features disabled" }
        }
    }

    private fun JdbcTransaction.isApplied(version: Int): Boolean =
        exec("SELECT 1 FROM fork_migration WHERE version = $version") { resultSet -> resultSet.next() } ?: false
}
