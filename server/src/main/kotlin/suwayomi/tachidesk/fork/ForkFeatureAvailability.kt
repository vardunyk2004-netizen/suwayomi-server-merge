package suwayomi.tachidesk.fork

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

    @Volatile
    private var currentStatus = Status.AVAILABLE

    fun status(): Status = currentStatus

    fun isEnabled(feature: Feature): Boolean {
        if (currentStatus != Status.AVAILABLE || serverConfig.forkStockMode.value) {
            return false
        }

        return when (feature) {
            Feature.MERGE -> serverConfig.mergeEnabled.value
            Feature.DUPLICATES -> serverConfig.duplicatesEnabled.value
            Feature.REINDEX -> serverConfig.reindexEnabled.value
            Feature.DOCTOR -> serverConfig.doctorEnabled.value
        }
    }

    fun markUnavailable() {
        currentStatus = Status.UNAVAILABLE
    }

    fun markAvailable() {
        currentStatus = Status.AVAILABLE
    }
}
