package suwayomi.tachidesk.fork

object ForkFeatureAvailability {
    enum class Status {
        AVAILABLE,
        UNAVAILABLE,
    }

    @Volatile
    private var currentStatus = Status.AVAILABLE

    fun status(): Status = currentStatus

    fun markUnavailable() {
        currentStatus = Status.UNAVAILABLE
    }

    fun markAvailable() {
        currentStatus = Status.AVAILABLE
    }
}
