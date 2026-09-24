package dk.cachet.carp.dsp.portal.mock

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import dk.cachet.carp.dsp.portal.api.WorkflowDetail
import dk.cachet.carp.dsp.portal.api.WorkflowSummary
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
object MockStore {

    /** Non-strict: fields this DTO set does not model are ignored, not fatal. */
    private val yaml = Yaml(
        configuration = YamlConfiguration(strictMode = false),
    )

    /**
     * Workflows added to the study, by upload, composition, or copying a demo.
     *
     * Deliberately not pre-filled with every demo: the study's repository is
     * what a researcher has actually put there. Browsing examples is
     * [WorkflowLibrary]'s job.
     */
    private val workflows = ConcurrentHashMap<String, WorkflowDetail>()

    /** One study is enough for a mock. */
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
     * Parses YAML into a [WorkflowDetail]. Kaml's failures carry the line and
     * column, which is what makes the upload page's inline errors useful, so
     * the message is passed through rather than replaced.
     */
    fun parse(text: String): WorkflowDetail = parse(text, validate = true)

    /**
     * Parses YAML into a [WorkflowDetail].
     *
     * [validate] off is what "save as draft" needs: the file still has to be
     * readable, because an unparseable workflow has no id to store it under,
     * but the resolution checks are skipped so work in progress can be kept.
     */
    fun parse(
        text: String,
        validate: Boolean,
        draft: Boolean = false,
        /** Used when the file declares no `metadata.id`. Normally the filename. */
        fallbackId: String? = null,
    ): WorkflowDetail {
        val parsed = try {
            yaml.decodeFromString(WorkflowFile.serializer(), text)
        } catch (e: Exception) {
            throw WorkflowParseException(e.message ?: "Could not read the workflow file.")
        }

        val id = parsed.metadata.id.ifBlank { fallbackId.orEmpty() }
        if (id.isBlank()) {
            throw WorkflowParseException("The workflow needs a metadata.id.")
        }

        val file = if (id == parsed.metadata.id) {
            parsed
        } else {
            parsed.copy(metadata = parsed.metadata.copy(id = id))
        }

        if (validate) validate(file)

        return WorkflowDetail(
            summary = WorkflowSummary(
                workflowId = file.metadata.id,
                name = file.metadata.name,
                description = file.metadata.description,
                version = file.metadata.version,
                tags = file.metadata.tags,
                stepCount = file.steps.size,
                draft = draft,
            ),
            definition = file,
            rawYaml = text,
        )
    }

    /**
     * The checks the real WorkflowService would make during resolution.
     * Enough to make the upload page's error states real - not a full
     * implementation of resolution.
     */
    private fun validate(file: WorkflowFile) {
        if (file.steps.isEmpty()) {
            throw WorkflowParseException("The workflow has no steps.")
        }

        val stepIds = file.steps.map { it.id }
        stepIds.groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .keys
            .firstOrNull()
            ?.let { throw WorkflowParseException("Duplicate step id: $it") }

        file.steps.forEach { step ->
            step.dependsOn.firstOrNull { it !in stepIds }?.let {
                throw WorkflowParseException("Step '${step.id}' depends on '$it', which does not exist.")
            }
            step.inputs.mapNotNull { it.source?.stepId }
                .firstOrNull { it !in stepIds }
                ?.let {
                    throw WorkflowParseException("Step '${step.id}' reads from '$it', which does not exist.")
                }
            step.environmentId?.let { envId ->
                if (envId !in file.environments.keys) {
                    throw WorkflowParseException(
                        "Step '${step.id}' uses environment '$envId', which is not declared.",
                    )
                }
            }
        }

        detectCycle(file)?.let {
            throw WorkflowParseException("The workflow has a dependency cycle: $it")
        }
    }

    /**
     * Returns a readable cycle path, or null when the graph is acyclic.
     *
     * Walks control *and* data edges together. Checking only `dependsOn` misses
     * a step that declares no dependency but reads an output from further down
     * the pipeline - which is exactly the fault the `inj-cycle` fixture
     * injects, and it is unrunnable either way.
     */
    private fun detectCycle(file: WorkflowFile): String? {
        val known = file.steps.map { it.id }.toSet()
        val edges = file.steps.associate { step ->
            step.id to (
                step.dependsOn +
                    step.inputs.mapNotNull { it.source?.stepId }
                ).filter { it in known }.distinct()
        }
        val visiting = mutableSetOf<String>()
        val done = mutableSetOf<String>()
        var cycle: String? = null

        fun walk(id: String, path: List<String>) {
            if (cycle != null || id in done) return
            if (id in visiting) {
                cycle = (path + id).dropWhile { it != id }.joinToString(" -> ")
                return
            }
            visiting += id
            edges[id].orEmpty().forEach { walk(it, path + id) }
            visiting -= id
            done += id
        }

        edges.keys.forEach { walk(it, emptyList()) }
        return cycle
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
