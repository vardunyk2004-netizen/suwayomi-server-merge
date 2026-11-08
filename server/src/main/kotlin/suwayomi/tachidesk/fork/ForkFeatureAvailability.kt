package suwayomi.tachidesk.fork

import suwayomi.tachidesk.graphql.types.ForkFeaturesAvailableStatus
import suwayomi.tachidesk.server.serverConfig

object ForkFeatureAvailability {
    enum class Feature {
        MERGE,
        DUPLICATES,
        REINDEX,
        DOCTOR,
    }

    enum class Status {
        AVAILABLE,
        UNAVAILABLE,
    }

    internal data class Switches(
        val stockMode: Boolean,
        val mergeEnabled: Boolean,
        val duplicatesEnabled: Boolean,
        val reindexEnabled: Boolean,
        val doctorEnabled: Boolean,
    )

    @Volatile
    private var currentStatus = Status.AVAILABLE

    fun status(): Status = currentStatus

    fun graphqlStatus(): ForkFeaturesAvailableStatus {
        val switches =
            Switches(
                stockMode = serverConfig.forkStockMode.value,
                mergeEnabled = serverConfig.mergeEnabled.value,
                duplicatesEnabled = serverConfig.duplicatesEnabled.value,
                reindexEnabled = serverConfig.reindexEnabled.value,
                doctorEnabled = serverConfig.doctorEnabled.value,
            )
        return graphqlStatus(currentStatus, switches)
    }

    internal fun graphqlStatus(
        migrationStatus: Status,
        switches: Switches,
    ): ForkFeaturesAvailableStatus {
        if (migrationStatus == Status.UNAVAILABLE) return ForkFeaturesAvailableStatus.UNAVAILABLE
        if (switches.stockMode) return ForkFeaturesAvailableStatus.DISABLED_BY_STOCK_MODE

        val anyFeatureEnabled =
            switches.mergeEnabled || switches.duplicatesEnabled || switches.reindexEnabled || switches.doctorEnabled

        return if (anyFeatureEnabled) ForkFeaturesAvailableStatus.AVAILABLE else ForkFeaturesAvailableStatus.DISABLED_BY_SETTING
    }

    fun isEnabled(feature: Feature): Boolean {
        val switches =
            Switches(
                stockMode = serverConfig.forkStockMode.value,
                mergeEnabled = serverConfig.mergeEnabled.value,
                duplicatesEnabled = serverConfig.duplicatesEnabled.value,
                reindexEnabled = serverConfig.reindexEnabled.value,
                doctorEnabled = serverConfig.doctorEnabled.value,
            )
        return isEnabled(feature, currentStatus, switches)
    }

    internal fun isEnabled(
        feature: Feature,
        status: Status,
        switches: Switches,
    ): Boolean {
        if (status != Status.AVAILABLE || switches.stockMode) {
            return false
        }

        return when (feature) {
            Feature.MERGE -> switches.mergeEnabled
            Feature.DUPLICATES -> switches.duplicatesEnabled
            Feature.REINDEX -> switches.reindexEnabled
            Feature.DOCTOR -> switches.doctorEnabled
        }
    }

    fun markUnavailable() {
        currentStatus = Status.UNAVAILABLE
    }

    fun markAvailable() {
        currentStatus = Status.AVAILABLE
    }
}
