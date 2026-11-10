package suwayomi.tachidesk.fork

import suwayomi.tachidesk.fork.database.ForkMigrationRunner
import suwayomi.tachidesk.graphql.types.DoctorSchedule
import suwayomi.tachidesk.graphql.types.ForkFeaturesAvailableStatus
import suwayomi.tachidesk.graphql.types.MergeChapterDisplay
import suwayomi.tachidesk.graphql.types.MergeDedupeStrategy
import suwayomi.tachidesk.graphql.types.MergeDefaultMode
import suwayomi.tachidesk.graphql.types.MergeOfflineMode
import suwayomi.tachidesk.graphql.types.MergeOpdsVisibility
import suwayomi.tachidesk.graphql.types.MergeTrackerPolicy
import suwayomi.tachidesk.graphql.types.MergeUnnumberedChapterMatching
import suwayomi.tachidesk.graphql.types.ProgressBookmarkPolicy
import suwayomi.tachidesk.graphql.types.ProgressConflictPolicy
import suwayomi.tachidesk.graphql.types.ProgressDirection
import suwayomi.tachidesk.graphql.types.ReindexAdoptMode
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.settings.SettingsRegistry
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
    fun `remaining fork settings use documented stock-safe defaults`() {
        assertEquals(true, serverConfig.progressSyncEnabled.value)
        assertEquals(ProgressConflictPolicy.NEWEST_WINS, serverConfig.progressConflictPolicy.value)
        assertEquals(true, serverConfig.progressReadIsMonotonic.value)
        assertEquals(ProgressBookmarkPolicy.UNION, serverConfig.progressBookmarkPolicy.value)
        assertEquals(ProgressDirection.BOTH, serverConfig.progressDirection.value)
        assertEquals(MergeTrackerPolicy.COPY, serverConfig.mergeTrackerPolicy.value)

        assertEquals(true, serverConfig.lifecycleTrashEnabled.value)
        assertEquals(30, serverConfig.lifecycleTrashRetentionDays.value)
        assertEquals(false, serverConfig.lifecycleRestoreSourcesOnRemoveMerged.value)
        assertEquals(true, serverConfig.lifecycleConfirmPurgeText.value)

        assertEquals(true, serverConfig.mergeUpdateReapplyPlan.value)
        assertEquals(true, serverConfig.mergeUpdateIsolateSourceFailures.value)
        assertEquals(true, serverConfig.mergeDetectRemovedChapters.value)
        assertEquals(true, serverConfig.mergeExcludeFromSync.value)
        assertEquals(true, serverConfig.mergeBackupEnabled.value)
        assertEquals(false, serverConfig.mergeBackupFlattenExport.value)
        assertEquals(MergeOpdsVisibility.MERGED_ONLY, serverConfig.mergeOpdsVisibility.value)

        assertEquals(88, serverConfig.duplicatesStrongThreshold.value)
        assertEquals(74, serverConfig.duplicatesMinThreshold.value)
        assertEquals(false, serverConfig.duplicatesUseThumbnailHash.value)
        assertEquals(true, serverConfig.duplicatesUseTrackerMatching.value)
        assertEquals(false, serverConfig.duplicatesBackgroundRefresh.value)
        assertEquals(true, serverConfig.duplicatesBlockSequels.value)
        assertEquals(false, serverConfig.duplicatesAutoSuggestMerges.value)
        val weightSum =
            serverConfig.duplicatesWeightTitle.value +
                serverConfig.duplicatesWeightCoreTitle.value +
                serverConfig.duplicatesWeightToken.value +
                serverConfig.duplicatesWeightAlias.value +
                serverConfig.duplicatesWeightUrl.value +
                serverConfig.duplicatesWeightChapter.value +
                serverConfig.duplicatesWeightThumbnail.value +
                serverConfig.duplicatesWeightTracker.value
        assertEquals(1.0, weightSum, 0.000000001)

        assertEquals(true, serverConfig.reindexDefaultDryRun.value)
        assertEquals(false, serverConfig.reindexAllowFuzzy.value)
        assertEquals(ReindexAdoptMode.COPY, serverConfig.reindexAdoptMode.value)
        assertEquals(DoctorSchedule.OFF, serverConfig.doctorSchedule.value)
        assertEquals(true, serverConfig.migrationBlockMergedSources.value)
        assertEquals(false, serverConfig.sourcePinEnabled.value)
    }

    @Test
    fun `fork numeric setting bounds reject out of range values`() {
        assertRangeValid("mergeMinimumSources", 2, 5)
        assertRangeInvalid("mergeMinimumSources", 1, 6)
        assertRangeValid("lifecycleTrashRetentionDays", 0, 3650)
        assertRangeInvalid("lifecycleTrashRetentionDays", -1, 3651)
        assertRangeValid("duplicatesStrongThreshold", 50, 100)
        assertRangeInvalid("duplicatesStrongThreshold", 49, 101)
        assertRangeValid("duplicatesMinThreshold", 30, 95)
        assertRangeInvalid("duplicatesMinThreshold", 29, 96)

        listOf(
            "duplicatesWeightTitle",
            "duplicatesWeightCoreTitle",
            "duplicatesWeightToken",
            "duplicatesWeightAlias",
            "duplicatesWeightUrl",
            "duplicatesWeightChapter",
            "duplicatesWeightThumbnail",
            "duplicatesWeightTracker",
        ).forEach { setting ->
            assertRangeValid(setting, 0.0, 1.0)
            assertRangeInvalid(setting, -0.001, 1.001)
        }
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
    fun `availability status explains why fork features are disabled`() {
        val switches =
            ForkFeatureAvailability.Switches(
                stockMode = false,
                mergeEnabled = false,
                duplicatesEnabled = false,
                reindexEnabled = false,
                doctorEnabled = false,
            )

        assertEquals(
            ForkFeaturesAvailableStatus.DISABLED_BY_SETTING,
            ForkFeatureAvailability.graphqlStatus(ForkFeatureAvailability.Status.AVAILABLE, switches),
        )
        assertEquals(
            ForkFeaturesAvailableStatus.DISABLED_BY_STOCK_MODE,
            ForkFeatureAvailability.graphqlStatus(
                ForkFeatureAvailability.Status.AVAILABLE,
                switches.copy(stockMode = true),
            ),
        )
        assertEquals(
            ForkFeaturesAvailableStatus.AVAILABLE,
            ForkFeatureAvailability.graphqlStatus(
                ForkFeatureAvailability.Status.AVAILABLE,
                switches.copy(mergeEnabled = true),
            ),
        )
        assertEquals(
            ForkFeaturesAvailableStatus.UNAVAILABLE,
            ForkFeatureAvailability.graphqlStatus(ForkFeatureAvailability.Status.UNAVAILABLE, switches),
        )
    }

    @Test
    fun `disabled feature gates preserve the fork database snapshot`() {
        val before = forkTableSnapshot()
        val stockMode =
            ForkFeatureAvailability.Switches(
                stockMode = true,
                mergeEnabled = true,
                duplicatesEnabled = true,
                reindexEnabled = true,
                doctorEnabled = true,
            )
        val disabledByMaster =
            mapOf(
                ForkFeatureAvailability.Feature.MERGE to
                    stockMode.copy(stockMode = false, mergeEnabled = false),
                ForkFeatureAvailability.Feature.DUPLICATES to
                    stockMode.copy(stockMode = false, duplicatesEnabled = false),
                ForkFeatureAvailability.Feature.REINDEX to
                    stockMode.copy(stockMode = false, reindexEnabled = false),
                ForkFeatureAvailability.Feature.DOCTOR to
                    stockMode.copy(stockMode = false, doctorEnabled = false),
            )

        ForkFeatureAvailability.Feature.entries.forEach { feature ->
            assertFalse(
                ForkFeatureAvailability.isEnabled(
                    feature,
                    ForkFeatureAvailability.Status.AVAILABLE,
                    stockMode,
                ),
            )
            assertFalse(
                ForkFeatureAvailability.isEnabled(
                    feature,
                    ForkFeatureAvailability.Status.AVAILABLE,
                    disabledByMaster.getValue(feature),
                ),
            )
        }

        assertEquals(before, forkTableSnapshot())
    }

    @Test
    fun `production feature gate keeps default-disabled features off without changing tables`() {
        val before = forkTableSnapshot()

        ForkFeatureAvailability.Feature.entries.forEach { feature ->
            assertFalse(ForkFeatureAvailability.isEnabled(feature), "$feature must be off with default master settings")
        }

        assertEquals(before, forkTableSnapshot(), "checking the production gate must not mutate fork tables")
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

    private fun forkTableSnapshot(): Map<String, Int> =
        dbTransaction {
            listOf(
                "fork_migration",
                "fork_merge_group",
                "fork_merge_source",
                "fork_chapter_link",
                "fork_manga_lifecycle",
                "fork_merge_journal",
            ).associateWith { table ->
                exec("SELECT COUNT(*) FROM $table") { resultSet ->
                    resultSet.next()
                    resultSet.getInt(1)
                } ?: error("fork table snapshot returned no rows for $table")
            }
        }

    private fun assertRangeValid(
        setting: String,
        vararg values: Any,
    ) {
        val validator = SettingsRegistry.get(setting)?.validator ?: error("setting $setting has no validator")
        values.forEach { value -> assertEquals(null, validator(value), "$setting should accept $value") }
    }

    private fun assertRangeInvalid(
        setting: String,
        vararg values: Any,
    ) {
        val validator = SettingsRegistry.get(setting)?.validator ?: error("setting $setting has no validator")
        values.forEach { value -> assertTrue(validator(value) != null, "$setting should reject $value") }
    }
}
