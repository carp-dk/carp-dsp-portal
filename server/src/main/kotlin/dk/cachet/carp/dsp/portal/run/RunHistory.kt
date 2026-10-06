package dk.cachet.carp.dsp.portal.run

import dk.cachet.carp.dsp.portal.api.WorkflowDetail
import dk.cachet.carp.dsp.portal.api.WorkflowSummary
import dk.cachet.carp.dsp.portal.mock.RunSimulator

/**
 * The last run of each workflow, from whichever side actually ran it.
 *
 * [dk.cachet.carp.dsp.portal.store.WorkflowStore] asks [RunSimulator], which knows
 * only about simulated runs - so a workflow that had really run showed as
 * "Never run" on the Workflows page while its runs sat in the Runs list. The two
 * are merged here, newest wins.
 *
 * Lives on this side of the boundary so the dependency runs one way: `run` knows
 * about `mock`, never the reverse.
 */
object RunHistory {

    fun withLastRun(summaries: List<WorkflowSummary>): List<WorkflowSummary> =
        summaries.map { withLastRun(it) }

    fun withLastRun(detail: WorkflowDetail): WorkflowDetail =
        detail.copy(summary = withLastRun(detail.summary))

    private fun withLastRun(summary: WorkflowSummary): WorkflowSummary {
        val real = DspRunner.lastRunFor(summary.workflowId) ?: return summary

        // WorkflowStore has already filled in the simulated run, if there was one.
        val simulatedAt = summary.lastRunAt
        if (simulatedAt != null && simulatedAt >= real.startedAt) return summary

        return summary.copy(lastRunAt = real.startedAt, lastRunStatus = real.status)
    }
}
