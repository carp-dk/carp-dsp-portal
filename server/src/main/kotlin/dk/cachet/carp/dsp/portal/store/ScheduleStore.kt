package dk.cachet.carp.dsp.portal.store

import dk.cachet.carp.dsp.portal.api.Schedule
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Saved schedules.
 *
 * Only what the user chose is kept here. Firing, and where each schedule
 * stands, is run/Scheduler.kt, which reads it off the schedule's runs.
 */
object ScheduleStore {

    private val schedules = ConcurrentHashMap<String, Schedule>()

    fun create(
        studyId: String,
        workflowId: String,
        cadence: String,
        startAt: String?,
    ): Schedule {
        val schedule = Schedule(
            scheduleId = UUID.randomUUID().toString(),
            studyId = studyId,
            workflowId = workflowId,
            cadence = cadence,
            startAt = startAt,
            createdAt = Instant.now().toString(),
        )
        schedules[schedule.scheduleId] = schedule
        StateStore.save()
        return schedule
    }

    /** Returns the schedule with [enabled] set, or null when there is no such schedule. */
    fun setEnabled(scheduleId: String, enabled: Boolean): Schedule? =
        schedules.computeIfPresent(scheduleId) { _, schedule -> schedule.copy(enabled = enabled) }
            ?.also { StateStore.save() }

    /** True when a schedule existed and was removed. */
    fun delete(scheduleId: String): Boolean =
        (schedules.remove(scheduleId) != null).also { if (it) StateStore.save() }

    fun snapshot(): List<Schedule> = schedules.values.toList()

    /** Puts a saved schedule back without rewriting the state file. */
    fun restore(schedule: Schedule) {
        schedules[schedule.scheduleId] = schedule
    }

    fun list(studyId: String, workflowId: String?): List<Schedule> =
        schedules.values
            .filter { it.studyId == studyId }
            .filter { workflowId == null || it.workflowId == workflowId }
            .sortedByDescending { it.createdAt }
}
