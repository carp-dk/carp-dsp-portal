package dk.cachet.carp.dsp.portal.run

import dk.cachet.carp.dsp.portal.api.ExecutionIssue
import dk.cachet.carp.dsp.portal.api.ExecutionReport
import dk.cachet.carp.dsp.portal.api.ExecutionStatus
import dk.cachet.carp.dsp.portal.api.ProvisioningEvent
import dk.cachet.carp.dsp.portal.api.ExecutorState
import dk.cachet.carp.dsp.portal.api.RunContext
import dk.cachet.carp.dsp.portal.api.StepRunMetadata
import dk.cachet.carp.dsp.portal.api.StepRunDetail
import dk.cachet.carp.dsp.portal.api.StepRunResult
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** How many log lines a step keeps for the run view. */
private const val LOG_TAIL_LINES = 40

/**
 * One real run, readable while it is still going.
 *
 * The run view polls for a report before the run finishes, so this is assembled
 * as the executor reports progress rather than built from the final
 * `ExecutionReport`. Step order is the plan's; a step not yet started is PENDING.
 *
 * Every field is written from the run's own thread and read from request
 * threads, so the mutable parts are concurrent collections and [status] and the
 * timestamps are `@Volatile`.
 *
 * [onChange] fires after every transition, which is what lets the run be
 * written to disk as it goes rather than only at the end.
 */
class LiveRun(
    val executionId: String,
    val workflowId: String,
    val studyId: String,
    val planId: String,
    private val stepOrder: List<StepRunMetadata>,
    val context: RunContext? = null,
    private val onChange: (LiveRun) -> Unit = {},
) {
    val startedAt: String = Instant.now().toString()

    @Volatile
    var status: ExecutionStatus = ExecutionStatus.RUNNING
        private set

    @Volatile
    var completedAt: String? = null
        private set

    private val stepStatus = ConcurrentHashMap<String, ExecutionStatus>()
    private val stepStarted = ConcurrentHashMap<String, String>()
    private val stepFinished = ConcurrentHashMap<String, String>()
    private val stepLog = ConcurrentHashMap<String, MutableList<String>>()
    private val issues = mutableListOf<ExecutionIssue>()

    /** Outputs recorded once the run finishes, keyed by step id. */
    private val outputs = ConcurrentHashMap<String, List<dk.cachet.carp.dsp.portal.api.ProducedOutputRef>>()

    /** What each step ran, recorded with the outputs. */
    private val details = ConcurrentHashMap<String, StepRunDetail>()

    /** Environment provisioning, in the order it happened. */
    private val provisioning = ConcurrentHashMap<String, ProvisioningEvent>()
    private val provisioningOrder = mutableListOf<String>()

    fun stepStarted(stepId: String) {
        stepStatus[stepId] = ExecutionStatus.RUNNING
        stepStarted[stepId] = Instant.now().toString()
        log(stepId, "started")
        changed()
    }

    fun stepCompleted(stepId: String, durationMs: Long) {
        stepStatus[stepId] = ExecutionStatus.SUCCEEDED
        stepFinished[stepId] = Instant.now().toString()
        log(stepId, "succeeded in ${durationMs}ms")
        changed()
    }

    fun stepFailed(stepId: String, reason: String) {
        stepStatus[stepId] = ExecutionStatus.FAILED
        stepFinished[stepId] = Instant.now().toString()
        log(stepId, "failed: $reason")
        changed()
    }

    fun environmentSetupStarted(environmentId: String, name: String) {
        synchronized(provisioningOrder) {
            if (environmentId !in provisioningOrder) provisioningOrder += environmentId
        }
        provisioning[environmentId] = ProvisioningEvent(
            environmentId = environmentId,
            name = name,
            status = ExecutionStatus.RUNNING,
            startedAt = Instant.now().toString(),
        )
        changed()
    }

    fun environmentReady(environmentId: String, durationMs: Long, match: String, extras: List<String>) {
        provisioning.computeIfPresent(environmentId) { _, event ->
            event.copy(
                status = ExecutionStatus.SUCCEEDED,
                finishedAt = Instant.now().toString(),
                durationMs = durationMs,
                match = match,
                extras = extras,
                message = when (match) {
                    "EXACT" -> "Reused"
                    "SUPERSET" -> "Satisfied by a larger environment"
                    else -> "Built"
                },
            )
        }
        changed()
    }

    fun environmentFailed(environmentId: String, reason: String) {
        provisioning.computeIfPresent(environmentId) { _, event ->
            event.copy(
                status = ExecutionStatus.FAILED,
                finishedAt = Instant.now().toString(),
                message = reason,
            )
        }
        changed()
    }

    fun log(stepId: String, line: String) {
        val lines = stepLog.getOrPut(stepId) { mutableListOf() }
        synchronized(lines) {
            lines += line
            while (lines.size > LOG_TAIL_LINES) lines.removeAt(0)
        }
    }

    /** Records the outcome once the executor returns. */
    fun finish(
        status: ExecutionStatus,
        outputsByStep: Map<String, List<dk.cachet.carp.dsp.portal.api.ProducedOutputRef>>,
        runIssues: List<ExecutionIssue>,
        detailsByStep: Map<String, StepRunDetail> = emptyMap(),
    ) {
        outputs.putAll(outputsByStep)
        details.putAll(detailsByStep)
        synchronized(issues) { issues += runIssues }
        // Anything never reported is skipped, which is what the executor means
        // when it stops early.
        stepOrder.forEach { stepStatus.putIfAbsent(it.id, ExecutionStatus.SKIPPED) }
        completedAt = Instant.now().toString()
        this.status = status
        changed()
    }

    /**
     * Marks the run cancelled.
     *
     * The worker thread is interrupted separately; this records the outcome. A
     * step already running is left as it was rather than rewritten to CANCELLED -
     * it did run, and how far it got is worth keeping.
     */
    fun cancel() {
        synchronized(issues) {
            issues += ExecutionIssue(
                null,
                dk.cachet.carp.dsp.portal.api.ExecutionIssueKind.ORCHESTRATOR_ERROR,
                "Cancelled.",
            )
        }
        stepOrder.forEach { stepStatus.putIfAbsent(it.id, ExecutionStatus.SKIPPED) }
        completedAt = Instant.now().toString()
        status = ExecutionStatus.CANCELLED
        changed()
    }

    /** True while the run can still be cancelled. */
    val isRunning: Boolean get() = status == ExecutionStatus.RUNNING

    /** Marks the run failed before any step ran - a bad plan, or a crash in setup. */
    fun abort(message: String) {
        synchronized(issues) {
            issues += ExecutionIssue(null, dk.cachet.carp.dsp.portal.api.ExecutionIssueKind.ORCHESTRATOR_ERROR, message)
        }
        stepOrder.forEach { stepStatus.putIfAbsent(it.id, ExecutionStatus.SKIPPED) }
        completedAt = Instant.now().toString()
        status = ExecutionStatus.FAILED
        changed()
    }

    /** Never lets a persistence failure take the run down with it. */
    private fun changed() {
        runCatching { onChange(this) }
    }

    fun state(): ExecutorState = ExecutorState(
        executionId = executionId,
        status = status,
        startedAt = startedAt,
        completedAt = completedAt,
        workflowId = workflowId,
        studyId = studyId,
        context = context,
    )

    /** The report as it stands, complete or not. */
    fun report(): ExecutionReport = ExecutionReport(
        runId = executionId,
        planId = planId,
        startedAt = startedAt,
        finishedAt = completedAt,
        status = status,
        stepResults = stepOrder.map { step ->
            StepRunResult(
                stepMetadata = step,
                status = stepStatus[step.id] ?: ExecutionStatus.PENDING,
                startedAt = stepStarted[step.id],
                finishedAt = stepFinished[step.id],
                outputs = outputs[step.id].orEmpty(),
                logTail = stepLog[step.id]?.let { synchronized(it) { it.toList() } }.orEmpty(),
                detail = details[step.id],
            )
        },
        issues = synchronized(issues) { issues.toList() },
        provisioning = synchronized(provisioningOrder) { provisioningOrder.toList() }
            .mapNotNull { provisioning[it] },
    )
}
