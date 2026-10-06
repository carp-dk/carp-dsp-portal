package dk.cachet.carp.dsp.portal.store

import carp.dsp.core.infrastructure.serialization.DecodeResult
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import dk.cachet.carp.dsp.portal.api.WorkflowDetail
import dk.cachet.carp.dsp.portal.api.WorkflowSummary
import dk.cachet.carp.dsp.portal.api.toView
import dk.cachet.carp.dsp.portal.catalogue.WorkflowLibrary
import dk.cachet.carp.dsp.portal.mock.RunSimulator
import java.util.concurrent.ConcurrentHashMap

/** Thrown when an uploaded file cannot be read as a workflow. */
class WorkflowParseException(message: String) : Exception(message)

/**
 * In-memory store of workflows and runs.
 *
 * Bundled workflows are the real demo files from carp-dsp, parsed at startup.
 * Uploads are parsed the same way, so the upload page reports genuine failures
 * rather than scripted ones.
 */
object WorkflowStore {

    private val codec = WorkflowYamlCodec()

    /**
     * Workflows added to the study, by upload, composition, or copying a demo.
     *
     * Deliberately not pre-filled with every demo: the study's repository is
     * what a researcher has actually put there. Browsing examples is
     * [WorkflowLibrary]'s job.
     */
    private val workflows = ConcurrentHashMap<String, WorkflowDetail>()

    /** The one study the portal holds, until it is connected to CARP. */
    const val DEMO_STUDY_ID = "11111111-1111-1111-1111-111111111111"

    /**
     * The demos seeded on startup.
     *
     * `wf-activity-summary-offline` is the workflow with a recorded run behind
     * it, so the Runs page and real results are there immediately - see
     * RunSimulator.seedRecordedRuns. `mobgap-gait-analysis` is the reference
     * case the live demo runs, so it has to be on the Workflows page without
     * anyone copying it in first.
     *
     * Seeded per workflow rather than only into an empty store, so a saved
     * session from before this list grew still picks up what it is missing. A
     * seeded demo that was deleted therefore comes back on the next start;
     * removing it from this list is what makes that stick.
     */
    private val SEEDED = listOf(
        "wf-activity-summary-offline.yaml",
        "mobgap-gait-analysis.yaml",
    )

    fun seedFromLibrary() {
        SEEDED.forEach { path ->
            val id = WorkflowLibrary.get(path)?.workflowId
            if (id == null || !has(id)) {
                runCatching { WorkflowLibrary.addToStudy(path) }
            }
        }
    }

    /**
     * Parses YAML into a [WorkflowDetail], with core's codec.
     *
     * Only reads the file: whether the workflow is valid is the engine's call,
     * made by `Validation` where a verdict is needed. The codec's message carries
     * kaml's line and column, which is what the upload page shows inline.
     *
     * @param fallbackId used when the file declares no `metadata.id`, normally
     *   its filename.
     * @throws WorkflowParseException when the file is not a readable workflow or
     *   has no id.
     */
    fun parse(text: String, draft: Boolean = false, fallbackId: String? = null): WorkflowDetail {
        val descriptor = when (val result = codec.decode(text)) {
            is DecodeResult.Success -> result.descriptor
            is DecodeResult.MalformedYaml -> throw WorkflowParseException(result.message)
            is DecodeResult.SchemaError -> throw WorkflowParseException(result.message)
            is DecodeResult.PolicyViolation -> throw WorkflowParseException(result.message)
        }

        val id = descriptor.metadata.id?.takeIf { it.isNotBlank() } ?: fallbackId.orEmpty()
        if (id.isBlank()) {
            throw WorkflowParseException("The workflow needs a metadata.id.")
        }

        val view = descriptor.toView(id)

        return WorkflowDetail(
            summary = WorkflowSummary(
                workflowId = id,
                name = view.metadata.name,
                description = view.metadata.description,
                version = view.metadata.version,
                tags = view.metadata.tags,
                stepCount = view.steps.size,
                draft = draft,
            ),
            definition = view,
            rawYaml = text,
        )
    }

    fun list(): List<WorkflowSummary> =
        workflows.values
            .map { detail ->
                val last = RunSimulator.lastRunFor(detail.summary.workflowId)
                detail.summary.copy(
                    lastRunAt = last?.startedAt,
                    lastRunStatus = last?.status,
                )
            }
            .sortedBy { it.name }

    fun get(workflowId: String): WorkflowDetail? = workflows[workflowId]

    fun has(workflowId: String): Boolean = workflows.containsKey(workflowId)

    /**
     * Stores a workflow.
     *
     * Overwriting is deliberate rather than accidental: the id is the key, so
     * saving under an id already in the study replaces what is there. Callers
     * check [has] first and make the user confirm.
     */
    fun put(detail: WorkflowDetail): WorkflowDetail {
        workflows[detail.summary.workflowId] = detail
        StateStore.save()
        return detail
    }

    /** True when a workflow existed and was removed. */
    fun remove(workflowId: String): Boolean =
        (workflows.remove(workflowId) != null).also { if (it) StateStore.save() }

    /**
     * Stored as text plus the draft flag, not as parsed objects, so a change to
     * the workflow DTOs cannot make an existing state file unreadable.
     */
    fun snapshot(): List<StateStore.StoredWorkflow> =
        workflows.values.map { StateStore.StoredWorkflow(it.rawYaml, it.summary.draft) }
}
