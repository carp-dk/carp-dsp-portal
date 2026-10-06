package dk.cachet.carp.dsp.portal.run

import dk.cachet.carp.dsp.portal.api.ExecutorState
import dk.cachet.carp.dsp.portal.api.RunContext
import dk.cachet.carp.dsp.portal.api.RunTrigger
import dk.cachet.carp.dsp.portal.catalogue.RepoSource
import dk.cachet.carp.dsp.portal.mock.RunSimulator

/** Thrown when a run cannot be started; the message is shown to the user. */
class RunNotStarted(message: String, val notFound: Boolean = false) : Exception(message)

/**
 * Starts runs, by hand or on a schedule, by whichever path [RunMode] selects.
 *
 * The workflow's time parameters are set here, before planning, and what the
 * run was given is recorded on it. The stored workflow is never changed.
 */
object RunLauncher {

    /**
     * Starts [yaml] as a run of [workflowId].
     *
     * A run started by hand reads the workflow's window up to now. A scheduled
     * run reads [window].
     *
     * @throws RunNotStarted when the workflow is unknown, its window has not
     *   started, or the engine cannot plan it.
     */
    fun launch(
        yaml: String,
        workflowId: String,
        studyId: String,
        packageFiles: Map<String, ByteArray> = emptyMap(),
        trigger: RunTrigger = RunTrigger.MANUAL,
        window: TimeWindow? = TimeBindings.manualWindow(workflowId),
        scheduleId: String? = null,
        fireTime: String? = null,
    ): ExecutorState {
        if (window == null && TimeBindings.followsWindow(workflowId)) {
            throw RunNotStarted("This workflow reads a time window that has not started yet.")
        }

        val bound = try {
            TimeBindings.bind(workflowId, yaml, window)
        } catch (e: Exception) {
            throw RunNotStarted(e.message ?: "The workflow's time parameters could not be set.")
        }

        val context = RunContext(
            trigger = trigger,
            scheduleId = scheduleId,
            fireTime = fireTime,
            windowFrom = window?.from?.toString(),
            windowTo = window?.to?.toString(),
            values = bound.values,
        )

        if (!RunMode.isReal) {
            return RunSimulator.start(workflowId, studyId, context)
                ?: throw RunNotStarted("No workflow '$workflowId'.", notFound = true)
        }

        return try {
            DspRunner.submit(
                yaml = bound.yaml,
                workflowId = workflowId,
                studyId = studyId,
                packageFiles = RepoSource.packageFiles() + packageFiles,
                context = context,
            )
        } catch (e: Exception) {
            throw RunNotStarted(e.message ?: "The workflow could not be prepared for execution.")
        }
    }

    /** Every run, real and simulated. */
    fun allRuns(): List<ExecutorState> = DspRunner.allRuns() + RunSimulator.allRuns()
}
