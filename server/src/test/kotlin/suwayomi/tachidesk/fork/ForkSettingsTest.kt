package suwayomi.tachidesk.fork

import suwayomi.tachidesk.fork.database.ForkMigrationRunner
import suwayomi.tachidesk.graphql.types.MergeChapterDisplay
import suwayomi.tachidesk.graphql.types.MergeDedupeStrategy
import suwayomi.tachidesk.graphql.types.MergeDefaultMode
import suwayomi.tachidesk.graphql.types.MergeOfflineMode
import suwayomi.tachidesk.graphql.types.MergeUnnumberedChapterMatching
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForkSettingsTest : ApplicationTest() {
    @Test
    fun `fork controls default to stock-safe disabled state`() {
        assertFalse(serverConfig.forkStockMode.value)
        assertFalse(serverConfig.mergeEnabled.value)
        assertFalse(serverConfig.duplicatesEnabled.value)
        assertFalse(serverConfig.reindexEnabled.value)
        assertFalse(serverConfig.doctorEnabled.value)
        ForkFeatureAvailability.Feature.entries.forEach { feature ->
            assertFalse(ForkFeatureAvailability.isEnabled(feature))
        }
    }

    @Test
    fun `merge configuration uses the documented safe defaults`() {
        assertEquals(MergeDefaultMode.MIRROR, serverConfig.mergeDefaultMode.value)
        assertEquals(MergeDedupeStrategy.PREFERRED_SOURCE, serverConfig.mergeDedupeStrategy.value)
        assertEquals(
            MergeUnnumberedChapterMatching.TITLE_SCANLATOR,
            serverConfig.mergeUnnumberedChapterMatching.value,
        )
        assertEquals(2, serverConfig.mergeMinimumSources.value)
        assertEquals(MergeChapterDisplay.ORIGINAL, serverConfig.mergeChapterDisplay.value)
        assertEquals(true, serverConfig.mergeFallbackSourcesEnabled.value)
        assertEquals(MergeOfflineMode.USE_EXISTING, serverConfig.mergeOfflineMode.value)
        assertEquals(true, serverConfig.mergeDownloadOnceForMirrors.value)
        assertEquals(false, serverConfig.mergeChapterLevelEnabled.value)
        assertEquals(true, serverConfig.mergeSplitRevalidateOnUpdate.value)
    }

    @Test
    fun `fork features are available until a fork migration fails`() {
        assertEquals(ForkFeatureAvailability.Status.AVAILABLE, ForkFeatureAvailability.status())
    }

    @Test
    fun `feature gate honors master switches stock mode and migration availability`() {
        val allEnabled =
            ForkFeatureAvailability.Switches(
                stockMode = false,
                mergeEnabled = true,
                duplicatesEnabled = true,
                reindexEnabled = true,
                doctorEnabled = true,
            )

        ForkFeatureAvailability.Feature.entries.forEach { feature ->
            assertTrue(ForkFeatureAvailability.isEnabled(feature, ForkFeatureAvailability.Status.AVAILABLE, allEnabled))
            assertFalse(
                ForkFeatureAvailability.isEnabled(
                    feature,
                    ForkFeatureAvailability.Status.AVAILABLE,
                    allEnabled.copy(stockMode = true),
                ),
            )
            assertFalse(
                ForkFeatureAvailability.isEnabled(
                    feature,
                    ForkFeatureAvailability.Status.UNAVAILABLE,
                    allEnabled,
                ),
            )
        }
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
