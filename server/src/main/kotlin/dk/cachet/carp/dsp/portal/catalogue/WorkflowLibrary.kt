package dk.cachet.carp.dsp.portal.catalogue

import dk.cachet.carp.dsp.portal.api.DemoWorkflow
import dk.cachet.carp.dsp.portal.api.WorkflowSummary
import dk.cachet.carp.dsp.portal.run.EngineValidator
import dk.cachet.carp.dsp.portal.store.WorkflowParseException
import dk.cachet.carp.dsp.portal.store.WorkflowStore

/**
 * The demo workflows from carp-dsp, as a catalogue of examples.
 *
 * The `injections/` fixtures are deliberately broken and are kept rather than
 * filtered out - each one is a real example of a check firing.
 */
object WorkflowLibrary {

    private val entries: List<DemoWorkflow> by lazy { load() }

    private fun load(): List<DemoWorkflow> =
        RepoSource.workflows()
            .sortedBy { it.path }
            .map { source ->
                // Filename stem stands in for a missing metadata.id.
                val fallbackId = source.path.substringAfterLast('/').substringBeforeLast('.')
                val parsed = runCatching {
                    WorkflowStore.parse(source.yaml, fallbackId = fallbackId)
                }

                val report = parsed.getOrNull()
                    ?.let { runCatching { EngineValidator.validateDefinition(source.yaml) }.getOrNull() }

                val problems = report?.findings
                    ?.filter { it.severity == dk.cachet.carp.dsp.portal.api.Severity.ERROR }
                    .orEmpty()

                DemoWorkflow(
                    path = source.path,
                    // A broken fixture may not parse far enough to have an id.
                    workflowId = parsed.getOrNull()?.summary?.workflowId
                        ?: source.path.substringAfterLast('/').substringBeforeLast('.'),
                    summary = parsed.getOrNull()?.summary,
                    valid = parsed.isSuccess && problems.isEmpty(),
                    problem = parsed.exceptionOrNull()?.message
                        ?: problems.firstOrNull()?.message,
                    rawYaml = source.yaml,
                )
            }

    fun list(): List<DemoWorkflow> = entries

    fun get(path: String): DemoWorkflow? = entries.firstOrNull { it.path == path }

    /** Copies a demo into the study's repository. */
    fun addToStudy(path: String): WorkflowSummary {
        val demo = get(path)
            ?: throw WorkflowParseException("No demo workflow at '$path'.")

        if (!demo.valid) {
            throw WorkflowParseException(
                demo.problem ?: "This example does not validate, so it cannot be added.",
            )
        }

        return WorkflowStore.put(WorkflowStore.parse(demo.rawYaml, fallbackId = demo.workflowId)).summary
    }
}
