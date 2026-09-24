package dk.cachet.carp.dsp.portal.run

import carp.dsp.core.application.execution.EnvironmentOutcome
import carp.dsp.core.application.execution.ExecutionLogger
import carp.dsp.core.application.run.WorkflowExecutor
import carp.dsp.core.application.run.WorkflowPackage
import carp.dsp.steps.ClasspathStepLibrary
import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.dsp.portal.api.ExecutionIssue
import dk.cachet.carp.dsp.portal.api.ExecutionIssueKind
import dk.cachet.carp.dsp.portal.api.ExecutionReport
import dk.cachet.carp.dsp.portal.api.ExecutionStatus
import dk.cachet.carp.dsp.portal.api.ExecutorState
import dk.cachet.carp.dsp.portal.api.ProducedOutputRef
import dk.cachet.carp.dsp.portal.api.ResourceRef
import dk.cachet.carp.dsp.portal.api.RunContext
import dk.cachet.carp.dsp.portal.api.StepRunDetail
import dk.cachet.carp.dsp.portal.api.StepRunMetadata
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.slf4j.LoggerFactory
import dk.cachet.carp.analytics.application.execution.ExecutionStatus as EngineStatus

/**
 * Runs uploaded workflows for real, on this machine.
 *
 * The engine is synchronous and single-run, by design - queueing and live state
 * are this layer's job. A submitted run gets a directory of its own, a thread of
 * its own, and a [LiveRun] the API reads while it is still going.
 *
 * Concurrency is deliberately small. One workflow can occupy a machine's CPU and
 * its pixi cache, and the demo runs one at a time.
 */
object DspRunner {
    private val log = LoggerFactory.getLogger(DspRunner::class.java)

    /** Where run workspaces live. A volume in Docker, so runs survive a restart. */
    private val runsRoot: File =
        File(System.getenv("DSP_RUNS") ?: "data/runs").absoluteFile.apply { mkdirs() }

    private val pool = Executors.newFixedThreadPool(
        System.getenv("DSP_RUN_CONCURRENCY")?.toIntOrNull() ?: 1,
    ) { r -> Thread(r, "dsp-run").apply { isDaemon = true } }

    private val runs = ConcurrentHashMap<String, LiveRun>()

    /** Kept so a run can be cancelled. Dropped as soon as the run settles. */
    private val workers = ConcurrentHashMap<String, Future<*>>()

    init {
        reconcileInterruptedRuns()
    }

    /**
     * Settles runs the last process left mid-flight.
     *
     * A run is written to disk as it goes, so one that was still going when the
     * server stopped is on disk as RUNNING with nothing behind it. Nothing would
     * ever settle it, and the run view would spin forever. Nothing is in memory at
     * startup, so a persisted RUNNING is orphaned by definition.
     */
    private fun reconcileInterruptedRuns() {
        RunStore.all(runsRoot)
            .filter { it.state.status == ExecutionStatus.RUNNING }
            .forEach { record ->
                val settled = record.copy(
                    state = record.state.copy(status = ExecutionStatus.FAILED),
                    report = record.report.copy(
                        status = ExecutionStatus.FAILED,
                        issues = record.report.issues + ExecutionIssue(
                            stepMetadata = null,
                            kind = ExecutionIssueKind.ORCHESTRATOR_ERROR,
                            message = "The server stopped while this run was in progress.",
                        ),
                    ),
                )
                RunStore.save(File(runsRoot, record.state.executionId), settled)
                log.warn("Run ${record.state.executionId} was interrupted by a restart; marked failed")
            }
    }

    /**
     * Prepares [yaml] and starts running it, returning as soon as the plan is
     * known. A workflow that cannot be parsed or planned throws, so the caller
     * can answer with the same inline errors the upload page already renders.
     */
    fun submit(
        yaml: String,
        workflowId: String,
        studyId: String,
        packageFiles: Map<String, ByteArray> = emptyMap(),
        context: RunContext? = null,
    ): ExecutorState {
        val executionId = UUID.randomUUID().toString()
        val runDir = File(runsRoot, executionId).apply { mkdirs() }

        // A package is unpacked beside the workflow, so the run directory holds
        // exactly what was uploaded. The engine reads the workflow from disk:
        // resolution writes a steps.lock beside it, and a declared relative input
        // resolves against its directory.
        val bundleDir = File(runDir, "package").apply { mkdirs() }
        val workflowFile = File(bundleDir, "workflow.yaml").apply { writeText(yaml) }
        packageFiles.forEach { (relative, bytes) ->
            val target = File(bundleDir, relative)
            require(target.canonicalPath.startsWith(bundleDir.canonicalPath)) {
                "package entry '$relative' escapes the bundle"
            }
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
        }

        val executor = WorkflowExecutor.filesystem(
            stepLibrary = ClasspathStepLibrary(),
            workspaceRoot = runDir.toPath(),
        )
        val pkg = WorkflowPackage.of(bundleDir.toPath(), "workflow.yaml")
        val prepared = executor.prepare(pkg)

        // The plan is the only place that knows an output's declared name; the
        // report that comes back carries ids alone.
        val outputNames = prepared.plan.steps
            .flatMap { it.bindings.outputs.values }
            .associate { it.spec.id.toString() to it.spec.name }

        val stepOrder = prepared.plan.steps.map {
            StepRunMetadata(
                id = it.metadata.id.toString(),
                name = it.metadata.name,
                description = it.metadata.description,
                descriptorId = it.metadata.descriptorId,
            )
        }
        val live = LiveRun(
            executionId = executionId,
            workflowId = workflowId,
            studyId = studyId,
            planId = prepared.plan.planId,
            stepOrder = stepOrder,
            context = context,
            onChange = { RunStore.save(runDir, RunRecord(it.state(), it.report())) },
        )
        runs[executionId] = live
        RunStore.save(runDir, RunRecord(live.state(), live.report()))

        if (!prepared.plan.isRunnable()) {
            live.abort(
                "Plan has errors: " +
                    prepared.plan.issues.joinToString("; ") { it.message },
            )
            return live.state()
        }

        // Re-built with a logger bound to this run, so progress reaches LiveRun.
        val running = WorkflowExecutor.filesystem(
            stepLibrary = ClasspathStepLibrary(),
            workspaceRoot = runDir.toPath(),
            options = WorkflowExecutor.Options(executionLogger = LiveRunLogger(live)),
        )

        val worker = pool.submit {
            try {
                val report = running.run(prepared, UUID.parse(executionId))
                live.finish(
                    status = if (report.status == EngineStatus.SUCCEEDED) {
                        ExecutionStatus.SUCCEEDED
                    } else {
                        ExecutionStatus.FAILED
                    },
                    outputsByStep = report.stepResults.associate { step ->
                        step.stepMetadata.id.toString() to step.outputs.orEmpty().map {
                            ProducedOutputRef(
                                outputId = it.outputId.toString(),
                                location = ResourceRef(value = it.location.value),
                                sizeBytes = it.sizeBytes,
                                sha256 = it.sha256,
                                contentType = it.contentType,
                                name = outputNames[it.outputId.toString()],
                            )
                        }
                    },
                    runIssues = report.issues.map {
                        ExecutionIssue(
                            stepMetadata = null,
                            kind = runCatching { ExecutionIssueKind.valueOf(it.kind.name) }
                                .getOrDefault(ExecutionIssueKind.UNKNOWN),
                            message = it.message,
                        )
                    },
                    detailsByStep = report.stepResults.mapNotNull { step ->
                        step.detail?.let { detail ->
                            step.stepMetadata.id.toString() to StepRunDetail(
                                command = detail.command.orEmpty(),
                                workingDirectory = detail.workingDirectory,
                                exitCode = detail.exitCode,
                                output = detail.stdout?.let { ResourceRef(value = it.value) },
                                outputUrl = detail.stdout?.let {
                                    "/api/runs/$executionId/steps/${step.stepMetadata.id}/output"
                                },
                            )
                        }
                    }.toMap(),
                )
            } catch (e: InterruptedException) {
                // Cancellation: the interrupt reached the child process, which
                // JvmCommandRunner destroys. The state is already CANCELLED.
                log.info("Run $executionId cancelled")
                if (live.isRunning) live.cancel()
            } catch (e: Exception) {
                log.error("Run $executionId failed", e)
                live.abort(e.message ?: e::class.simpleName ?: "Unknown failure")
            } finally {
                workers.remove(executionId)
            }
        }
        workers[executionId] = worker

        return live.state()
    }

    /**
     * Stops a run.
     *
     * Interrupting the worker is enough to reach the child process:
     * `JvmCommandRunner` catches `InterruptedException` while waiting and calls
     * `destroyForcibly`. A queued run that has not started is simply dropped.
     *
     * Returns null when there is no such run, or it has already settled.
     */
    fun cancel(executionId: String): ExecutorState? {
        val live = runs[executionId] ?: return null
        if (!live.isRunning) return null

        workers.remove(executionId)?.cancel(true)
        live.cancel()
        return live.state()
    }

    // A live run is answered from memory; anything else is read back from its
    // directory, so a restart does not lose the runs already on disk.

    fun state(executionId: String): ExecutorState? =
        runs[executionId]?.state() ?: stored(executionId)?.state

    fun report(executionId: String): ExecutionReport? =
        runs[executionId]?.report() ?: stored(executionId)?.report

    fun knows(executionId: String): Boolean =
        runs.containsKey(executionId) || stored(executionId) != null

    fun runsFor(studyId: String): List<ExecutorState> {
        val live = runs.values.map { it.state() }
        val onDisk = RunStore.all(runsRoot).map { it.state }
        return (live + onDisk.filterNot { record -> live.any { it.executionId == record.executionId } })
            .filter { it.studyId == studyId }
    }

    /** Every real run, live or on disk. */
    fun allRuns(): List<ExecutorState> {
        val live = runs.values.map { it.state() }
        val onDisk = RunStore.all(runsRoot).map { it.state }
        return live + onDisk.filterNot { record -> live.any { it.executionId == record.executionId } }
    }

    /** The most recent real run of a workflow, live or on disk. */
    fun lastRunFor(workflowId: String): ExecutorState? =
        (runs.values.map { it.state() } + RunStore.all(runsRoot).map { it.state })
            .filter { it.workflowId == workflowId }
            .maxByOrNull { it.startedAt }

    /** The directory a run wrote into, for serving its artefacts. */
    fun runDir(executionId: String): File? =
        dirOf(executionId)?.takeIf { it.isDirectory }

    private fun stored(executionId: String): RunRecord? =
        dirOf(executionId)?.let { RunStore.load(it) }

    /** An execution id is a path segment here, so anything shaped like a path is refused. */
    private fun dirOf(executionId: String): File? =
        if (executionId.isBlank() || executionId.contains('/') || executionId.contains('\\') ||
            executionId.contains("..")
        ) {
            null
        } else {
            File(runsRoot, executionId)
        }
}

/** Feeds engine events into a [LiveRun]. */
private class LiveRunLogger(private val live: LiveRun) : ExecutionLogger {
    override fun onStepStarted(runId: UUID, stepId: UUID, stepName: String) =
        live.stepStarted(stepId.toString())

    override fun onStepCompleted(runId: UUID, stepId: UUID, stepName: String, durationMs: Long) =
        live.stepCompleted(stepId.toString(), durationMs)

    override fun onStepFailed(runId: UUID, stepId: UUID, stepName: String, reason: String) =
        live.stepFailed(stepId.toString(), reason)

    override fun onEnvironmentSetupStarted(runId: UUID, environmentId: String, name: String) =
        live.environmentSetupStarted(environmentId, name)

    override fun onEnvironmentReady(runId: UUID, outcome: EnvironmentOutcome) =
        live.environmentReady(outcome.environmentId, outcome.durationMs, outcome.match, outcome.extras)

    override fun onEnvironmentFailed(runId: UUID, environmentId: String, name: String, reason: String) =
        live.environmentFailed(environmentId, reason)
}
