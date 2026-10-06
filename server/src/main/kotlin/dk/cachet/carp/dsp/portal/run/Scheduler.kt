package dk.cachet.carp.dsp.portal.run

import dk.cachet.carp.dsp.portal.api.ExecutionStatus
import dk.cachet.carp.dsp.portal.api.ExecutorState
import dk.cachet.carp.dsp.portal.api.RunTrigger
import dk.cachet.carp.dsp.portal.api.Schedule
import dk.cachet.carp.dsp.portal.api.ScheduleView
import dk.cachet.carp.dsp.portal.store.BundleStore
import dk.cachet.carp.dsp.portal.store.WorkflowStore
import dk.cachet.carp.dsp.portal.store.ScheduleStore
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** How often a schedule fires. */
enum class Cadence(val interval: Duration) {
    EVERY_15_MINUTES(Duration.ofMinutes(15)),
    HOURLY(Duration.ofHours(1)),
    DAILY(Duration.ofDays(1)),
    WEEKLY(Duration.ofDays(7)),
    ;

    companion object {
        fun of(name: String): Cadence? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Fires saved schedules.
 *
 * Fire times are the schedule's start plus whole intervals. Fires missed while
 * the server was down, or while the schedule was paused, collapse into one run
 * whose window covers them all.
 */
object Scheduler {

    private val log = LoggerFactory.getLogger(Scheduler::class.java)

    private val started = AtomicBoolean(false)

    /** Fires that could not start a run, so a broken schedule is logged once per fire, not every tick. */
    private val refused = ConcurrentHashMap<String, Instant>()

    /** Starts checking schedules every `DSP_SCHEDULER_TICK_SECONDS` (30). Safe to call more than once. */
    fun start() {
        if (!started.compareAndSet(false, true)) return

        val seconds = System.getenv("DSP_SCHEDULER_TICK_SECONDS")?.toLongOrNull() ?: 30L
        val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "dsp-scheduler").apply { isDaemon = true }
        }
        val check = Runnable { runCatching { tick() }.onFailure { log.error("Scheduler tick failed", it) } }

        executor.scheduleWithFixedDelay(check, seconds, seconds, TimeUnit.SECONDS)
    }

    /** Starts a run for every schedule that is due at [now] and not already running. */
    fun tick(now: Instant = Instant.now()) {
        val runs = RunLauncher.allRuns()
        ScheduleStore.snapshot().forEach { schedule -> fireIfDue(schedule, runsOf(schedule, runs), now) }
    }

    /** Returns [schedule] with where it stands at [now]. */
    fun view(schedule: Schedule, now: Instant = Instant.now()): ScheduleView {
        val runs = runsOf(schedule, RunLauncher.allRuns())
        val state = stateOf(schedule, runs, now)

        return ScheduleView(
            scheduleId = schedule.scheduleId,
            studyId = schedule.studyId,
            workflowId = schedule.workflowId,
            cadence = schedule.cadence,
            startAt = schedule.startAt,
            createdAt = schedule.createdAt,
            enabled = schedule.enabled,
            nextRunAt = state?.nextRunAt?.takeIf { schedule.enabled }?.toString(),
            windowFrom = state?.windowFrom?.toString(),
            windowEnd = state?.windowEnd?.toString(),
            completed = state?.completed == true,
            runs = runs.sortedByDescending { it.startedAt },
        )
    }

    private data class State(
        /** The latest fire time at or before now, or null before the first. */
        val dueFire: Instant?,
        val due: Boolean,
        val nextRunAt: Instant?,
        val windowFrom: Instant?,
        val windowEnd: Instant?,
        val completed: Boolean,
    )

    private fun runsOf(schedule: Schedule, runs: List<ExecutorState>) =
        runs.filter { it.context?.scheduleId == schedule.scheduleId }

    private fun stateOf(schedule: Schedule, runs: List<ExecutorState>, now: Instant): State? {
        val interval = Cadence.of(schedule.cadence)?.interval ?: return null
        val anchor = schedule.startAt?.let(::instantOrNull) ?: instantOrNull(schedule.createdAt) ?: return null

        val bindings = TimeBindings.get(schedule.workflowId)
        val windowEnd = bindings?.windowEnd?.let(::instantOrNull)
        val windowFrom = runs
            .filter { it.status == ExecutionStatus.SUCCEEDED }
            .mapNotNull { it.context?.windowTo?.let(::instantOrNull) }
            .maxOrNull()
            ?: bindings?.windowStart?.let(::instantOrNull)
        val completed = TimeBindings.followsWindow(schedule.workflowId) &&
            windowEnd != null && windowFrom != null && windowFrom >= windowEnd

        val dueFire = if (now < anchor) null else {
            val elapsed = Duration.between(anchor, now).toMillis() / interval.toMillis()
            anchor.plus(interval.multipliedBy(elapsed))
        }
        val lastFire = runs.mapNotNull { it.context?.fireTime?.let(::instantOrNull) }.maxOrNull()
        val due = !completed && dueFire != null && (lastFire == null || dueFire > lastFire)

        val nextRunAt = when {
            completed -> null
            dueFire == null -> anchor
            due -> now
            else -> dueFire.plus(interval)
        }

        return State(dueFire, due, nextRunAt, windowFrom, windowEnd, completed)
    }

    private fun fireIfDue(schedule: Schedule, runs: List<ExecutorState>, now: Instant) {
        if (!schedule.enabled) return
        val state = stateOf(schedule, runs, now) ?: return
        val fire = state.dueFire ?: return
        if (!state.due) return
        if (runs.any { it.status == ExecutionStatus.RUNNING || it.status == ExecutionStatus.PENDING }) return
        if (refused[schedule.scheduleId] == fire) return

        val window = if (!TimeBindings.followsWindow(schedule.workflowId)) null else {
            val from = state.windowFrom ?: return
            val to = listOfNotNull(fire, state.windowEnd).min()
            // The window has not opened by this fire; there is nothing to read yet.
            if (from >= to) return
            TimeWindow(from, to)
        }

        val detail = WorkflowStore.get(schedule.workflowId)
        if (detail == null) {
            refuse(schedule, fire, "its workflow '${schedule.workflowId}' is no longer in the study")
            return
        }

        try {
            RunLauncher.launch(
                yaml = detail.rawYaml,
                workflowId = schedule.workflowId,
                studyId = schedule.studyId,
                packageFiles = BundleStore.load(schedule.workflowId),
                trigger = RunTrigger.SCHEDULE,
                window = window,
                scheduleId = schedule.scheduleId,
                fireTime = fire.toString(),
            )
            log.info("Schedule ${schedule.scheduleId} fired for $fire, window $window")
        } catch (e: RunNotStarted) {
            refuse(schedule, fire, e.message.orEmpty())
        }
    }

    private fun refuse(schedule: Schedule, fire: Instant, reason: String) {
        refused[schedule.scheduleId] = fire
        log.warn("Schedule ${schedule.scheduleId} could not fire for $fire: $reason")
    }
}
