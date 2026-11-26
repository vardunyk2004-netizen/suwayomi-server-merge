package suwayomi.tachidesk.fork.maintenance

import io.github.oshai.kotlinlogging.KotlinLogging
import suwayomi.tachidesk.fork.ForkFeatureAvailability
import suwayomi.tachidesk.fork.duplicates.ForkDuplicateService
import suwayomi.tachidesk.fork.lifecycle.ForkLifecycleExecutor
import suwayomi.tachidesk.graphql.types.DoctorSchedule
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.util.HAScheduler
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * One daily maintenance task (04:23 server time). Each job checks its own gate first, so with every fork
 * feature off the task performs no SQL (R8):
 * - trash retention purge (setting 1031; merge enabled),
 * - doctor verification report, never repairs (setting 1081 DAILY or WEEKLY on Mondays; doctor enabled),
 * - duplicate signature refresh (setting 1066; duplicates enabled).
 */
object ForkScheduler {
    private val logger = KotlinLogging.logger {}
    private var taskId: String? = null

    fun start() {
        if (taskId != null) return
        taskId = HAScheduler.scheduleCron(::runDaily, "23 4 * * *", "fork-maintenance")
    }

    internal fun runDaily() {
        runCatching { ForkLifecycleExecutor.default.purgeExpired() }
            .onFailure { logger.warn(it) { "Fork trash retention purge failed" } }

        if (ForkFeatureAvailability.isEnabled(ForkFeatureAvailability.Feature.DOCTOR)) {
            val schedule = serverConfig.doctorSchedule.value
            val weeklyDue = schedule == DoctorSchedule.WEEKLY && LocalDate.now().dayOfWeek == DayOfWeek.MONDAY
            val due = schedule == DoctorSchedule.DAILY || weeklyDue
            if (due) {
                runCatching { ForkDoctorService.default.run(dryRun = true) }
                    .onFailure { logger.warn(it) { "Scheduled doctor run failed" } }
            }
        }

        val duplicatesOn = ForkFeatureAvailability.isEnabled(ForkFeatureAvailability.Feature.DUPLICATES)
        if (duplicatesOn && serverConfig.duplicatesBackgroundRefresh.value) {
            runCatching { ForkDuplicateService.default.refreshSignatures() }
                .onFailure { logger.warn(it) { "Duplicate signature refresh failed" } }
        }
    }
}
