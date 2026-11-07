package suwayomi.tachidesk.fork

import suwayomi.tachidesk.fork.database.ForkMigrationRunner
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ForkSettingsTest : ApplicationTest() {
    @Test
    fun `fork controls default to stock-safe disabled state`() {
        assertFalse(serverConfig.forkStockMode.value)
        assertFalse(serverConfig.mergeEnabled.value)
        assertFalse(serverConfig.duplicatesEnabled.value)
        assertFalse(serverConfig.reindexEnabled.value)
        assertFalse(serverConfig.doctorEnabled.value)
    }

    @Test
    fun `fork features are available until a fork migration fails`() {
        assertEquals(ForkFeatureAvailability.Status.AVAILABLE, ForkFeatureAvailability.status())
    }

    @Test
    fun `fork migration runner is idempotent`() {
        val before = appliedMigrationCount()

        ForkMigrationRunner.run()

        assertEquals(before, appliedMigrationCount())
    }

    private fun appliedMigrationCount(): Int =
        dbTransaction {
            exec("SELECT COUNT(*) FROM fork_migration") { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            } ?: error("fork migration ledger query returned no result")
        }
}
