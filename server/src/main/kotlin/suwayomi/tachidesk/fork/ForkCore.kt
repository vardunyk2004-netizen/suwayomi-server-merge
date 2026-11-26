package suwayomi.tachidesk.fork

import suwayomi.tachidesk.graphql.types.MergeChapterDisplay
import suwayomi.tachidesk.graphql.types.MergeDedupeStrategy
import suwayomi.tachidesk.graphql.types.MergeOfflineMode
import suwayomi.tachidesk.graphql.types.MergeTrackerPolicy
import suwayomi.tachidesk.graphql.types.MergeUnnumberedChapterMatching
import suwayomi.tachidesk.graphql.types.ProgressBookmarkPolicy
import suwayomi.tachidesk.graphql.types.ProgressConflictPolicy
import suwayomi.tachidesk.graphql.types.ProgressDirection
import suwayomi.tachidesk.server.serverConfig

/**
 * Injectable feature gate. Production code uses [ForkGate.production]; tests pass an explicit gate so
 * they never have to write global settings (an asynchronous config write broke unrelated tests in CI
 * run 42447431583, see docs/STATUS.md).
 *
 * Checking a gate never runs SQL: [ForkFeatureAvailability.isEnabled] reads in-memory config only.
 */
fun interface ForkGate {
    fun isEnabled(feature: ForkFeatureAvailability.Feature): Boolean

    companion object {
        val production = ForkGate { feature -> ForkFeatureAvailability.isEnabled(feature) }
        val allOn = ForkGate { true }
        val allOff = ForkGate { false }
    }
}

/** Thrown at every fork entry point when its feature is off. The GraphQL layer reports `FEATURE_DISABLED`. */
class ForkFeatureDisabledException(
    val feature: ForkFeatureAvailability.Feature,
) : IllegalStateException("FEATURE_DISABLED: ${feature.name.lowercase()} is disabled or unavailable")

/** A rejected fork request (validation). Never partially applied. */
class ForkRequestException(
    val code: String,
    message: String,
) : IllegalArgumentException("$code: $message")

fun ForkGate.require(feature: ForkFeatureAvailability.Feature) {
    if (!isEnabled(feature)) throw ForkFeatureDisabledException(feature)
}

fun forkRequire(
    condition: Boolean,
    code: String,
    message: () -> String,
) {
    if (!condition) throw ForkRequestException(code, message())
}

/** Merge settings snapshot (global defaults; per-merge overrides live in the group's options). */
data class ForkMergePolicy(
    val minimumSources: Int = 2,
    val dedupeStrategy: MergeDedupeStrategy = MergeDedupeStrategy.PREFERRED_SOURCE,
    val unnumberedMatching: MergeUnnumberedChapterMatching = MergeUnnumberedChapterMatching.TITLE_SCANLATOR,
    val chapterDisplay: MergeChapterDisplay = MergeChapterDisplay.ORIGINAL,
    val fallbackSourcesEnabled: Boolean = true,
    val offlineMode: MergeOfflineMode = MergeOfflineMode.USE_EXISTING,
    val downloadOnceForMirrors: Boolean = true,
    val chapterLevelEnabled: Boolean = false,
    val splitRevalidateOnUpdate: Boolean = true,
    val trackerPolicy: MergeTrackerPolicy = MergeTrackerPolicy.COPY,
    val updateReapplyPlan: Boolean = true,
    val updateIsolateSourceFailures: Boolean = true,
    val detectRemovedChapters: Boolean = true,
) {
    companion object {
        fun fromConfig() =
            ForkMergePolicy(
                minimumSources = serverConfig.mergeMinimumSources.value,
                dedupeStrategy = serverConfig.mergeDedupeStrategy.value,
                unnumberedMatching = serverConfig.mergeUnnumberedChapterMatching.value,
                chapterDisplay = serverConfig.mergeChapterDisplay.value,
                fallbackSourcesEnabled = serverConfig.mergeFallbackSourcesEnabled.value,
                offlineMode = serverConfig.mergeOfflineMode.value,
                downloadOnceForMirrors = serverConfig.mergeDownloadOnceForMirrors.value,
                chapterLevelEnabled = serverConfig.mergeChapterLevelEnabled.value,
                splitRevalidateOnUpdate = serverConfig.mergeSplitRevalidateOnUpdate.value,
                trackerPolicy = serverConfig.mergeTrackerPolicy.value,
                updateReapplyPlan = serverConfig.mergeUpdateReapplyPlan.value,
                updateIsolateSourceFailures = serverConfig.mergeUpdateIsolateSourceFailures.value,
                detectRemovedChapters = serverConfig.mergeDetectRemovedChapters.value,
            )
    }
}

data class ForkProgressPolicy(
    val enabled: Boolean = true,
    val conflict: ProgressConflictPolicy = ProgressConflictPolicy.NEWEST_WINS,
    val readIsMonotonic: Boolean = true,
    val bookmark: ProgressBookmarkPolicy = ProgressBookmarkPolicy.UNION,
    val direction: ProgressDirection = ProgressDirection.BOTH,
) {
    companion object {
        fun fromConfig() =
            ForkProgressPolicy(
                enabled = serverConfig.progressSyncEnabled.value,
                conflict = serverConfig.progressConflictPolicy.value,
                readIsMonotonic = serverConfig.progressReadIsMonotonic.value,
                bookmark = serverConfig.progressBookmarkPolicy.value,
                direction = serverConfig.progressDirection.value,
            )
    }
}

data class ForkLifecyclePolicy(
    val trashEnabled: Boolean = true,
    val retentionDays: Int = 30,
    val restoreSourcesOnRemoveMerged: Boolean = false,
    val confirmPurgeText: Boolean = true,
) {
    companion object {
        fun fromConfig() =
            ForkLifecyclePolicy(
                trashEnabled = serverConfig.lifecycleTrashEnabled.value,
                retentionDays = serverConfig.lifecycleTrashRetentionDays.value,
                restoreSourcesOnRemoveMerged = serverConfig.lifecycleRestoreSourcesOnRemoveMerged.value,
                confirmPurgeText = serverConfig.lifecycleConfirmPurgeText.value,
            )
    }
}

/** Seconds since epoch; replaceable in tests. */
object ForkClock {
    @Volatile
    var nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }

    fun now(): Long = nowSeconds()
}
