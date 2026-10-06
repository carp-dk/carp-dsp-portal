package dk.cachet.carp.dsp.portal

import dk.cachet.carp.dsp.portal.api.ArtifactEntry
import dk.cachet.carp.dsp.portal.api.ExecutionReport
import dk.cachet.carp.dsp.portal.api.ExecutionStatus
import dk.cachet.carp.dsp.portal.api.GenericArtifact
import dk.cachet.carp.dsp.portal.api.PortSource
import dk.cachet.carp.dsp.portal.api.PortSpec
import dk.cachet.carp.dsp.portal.api.StepRunMetadata
import dk.cachet.carp.dsp.portal.api.StepRunResult
import dk.cachet.carp.dsp.portal.api.StepSpec
import dk.cachet.carp.dsp.portal.api.WorkflowMetadata
import dk.cachet.carp.dsp.portal.api.WorkflowView
import dk.cachet.carp.dsp.portal.run.ResultSets
import kotlin.test.Test
import kotlin.test.assertEquals

/** Picking a run's final outputs out of everything it produced. */
class ResultSetsTest {

    // a -> b by data, a -> c by dependsOn: b and c are the leaves.
    private val workflow = WorkflowView(
        metadata = WorkflowMetadata(name = "Leaves"),
        steps = listOf(
            StepSpec(id = "a"),
            StepSpec(
                id = "b",
                inputs = listOf(PortSpec(id = "in", source = PortSource(type = "step-output", stepId = "a"))),
            ),
            StepSpec(id = "c", dependsOn = listOf("a")),
        ),
    )

    private fun entry(stepId: String) = ArtifactEntry(stepId = stepId, outputId = "out-$stepId", artifact = GenericArtifact())

    @Test
    fun leaves_are_steps_nothing_reads_from_or_depends_on() {
        assertEquals(setOf("b", "c"), ResultSets.leafSteps(workflow))
    }

    @Test
    fun a_simulated_run_keyed_by_step_id_keeps_the_leaf_outputs() {
        val finals = ResultSets.finals(listOf(entry("a"), entry("b"), entry("c")), report = null, workflow = workflow)

        assertEquals(listOf("b", "c"), finals.map { it.stepId })
    }

    @Test
    fun a_real_run_keyed_by_uuid_is_mapped_through_the_report() {
        val report = ExecutionReport(
            runId = "run",
            planId = "plan",
            status = ExecutionStatus.SUCCEEDED,
            stepResults = listOf("a", "b", "c").map {
                StepRunResult(
                    stepMetadata = StepRunMetadata(id = "uuid-$it", name = it, descriptorId = it),
                    status = ExecutionStatus.SUCCEEDED,
                )
            },
        )

        val finals = ResultSets.finals(listOf(entry("uuid-a"), entry("uuid-b")), report, workflow)

        assertEquals(listOf("uuid-b"), finals.map { it.stepId })
    }

    @Test
    fun without_the_workflow_the_last_step_stands_in() {
        val finals = ResultSets.finals(listOf(entry("a"), entry("b")), report = null, workflow = null)

        assertEquals(listOf("b"), finals.map { it.stepId })
    }
}
