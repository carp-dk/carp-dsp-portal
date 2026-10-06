package dk.cachet.carp.dsp.portal.run

import dk.cachet.carp.dsp.portal.api.ArtifactEntry
import dk.cachet.carp.dsp.portal.api.ExecutionReport
import dk.cachet.carp.dsp.portal.api.ExecutionStatus
import dk.cachet.carp.dsp.portal.api.ExecutorState
import dk.cachet.carp.dsp.portal.api.ResultSet
import dk.cachet.carp.dsp.portal.api.WorkflowView
import dk.cachet.carp.dsp.portal.mock.RunSimulator
import dk.cachet.carp.dsp.portal.store.WorkflowStore

/**
 * The results of every run in a study, one row per run, for the results list.
 */
object ResultSets {

    private val SETTLED = setOf(ExecutionStatus.SUCCEEDED, ExecutionStatus.FAILED, ExecutionStatus.CANCELLED)

    /** Returns the settled runs in [studyId] that produced at least one output, newest first. */
    fun list(studyId: String): List<ResultSet> =
        (DspRunner.runsFor(studyId) + RunSimulator.runsFor(studyId))
            .filter { it.status in SETTLED }
            .mapNotNull { resultSetOf(it) }
            .sortedByDescending { it.run.startedAt }

    private fun resultSetOf(run: ExecutorState): ResultSet? {
        val id = run.executionId
        val real = DspRunner.knows(id)

        val entries = if (real) RunArtefacts.entries(id, countRows = false) else RunSimulator.artifacts(id)
        if (entries.isEmpty()) return null

        val report = if (real) DspRunner.report(id) else RunSimulator.report(id)
        val workflow = WorkflowStore.get(run.workflowId)?.definition

        return ResultSet(run = run, outputCount = entries.size, finals = finals(entries, report, workflow))
    }

    /**
     * Returns the outputs of [workflow]'s leaf steps.
     *
     * Falls back to the last step that produced anything when the workflow is
     * gone, since there is then no graph to read.
     */
    internal fun finals(
        entries: List<ArtifactEntry>,
        report: ExecutionReport?,
        workflow: WorkflowView?,
    ): List<ArtifactEntry> {
        if (workflow == null) return entries.filter { it.stepId == entries.lastOrNull()?.stepId }

        // A real run keys entries by the plan's step UUID, a simulated one by the
        // YAML step id; the report maps one to the other.
        val leaves = leafSteps(workflow)
        val ids = leaves + report?.stepResults.orEmpty()
            .filter { it.stepMetadata.descriptorId in leaves }
            .map { it.stepMetadata.id }

        return entries.filter { it.stepId in ids }
    }

    /** Returns the ids of steps no other step reads from or depends on. */
    internal fun leafSteps(workflow: WorkflowView): Set<String> {
        val upstream = workflow.steps
            .flatMap { step -> step.dependsOn + step.inputs.mapNotNull { it.source?.stepId } }
            .toSet()
        return workflow.steps.map { it.id }.filterNot { it in upstream }.toSet()
    }
}
