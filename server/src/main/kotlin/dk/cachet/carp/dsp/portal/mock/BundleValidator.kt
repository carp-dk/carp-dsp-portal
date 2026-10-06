package dk.cachet.carp.dsp.portal.mock

import dk.cachet.carp.dsp.portal.api.Finding
import dk.cachet.carp.dsp.portal.api.Severity
import dk.cachet.carp.dsp.portal.api.StepResolution
import dk.cachet.carp.dsp.portal.api.StepSpec
import dk.cachet.carp.dsp.portal.api.ValidationReport
import dk.cachet.carp.dsp.portal.api.WorkflowView

/**
 * Checks that an uploaded workflow bundle carries everything it needs.
 *
 * A bundle is a workflow YAML plus whatever files its steps reference. Two ways
 * a step can resolve:
 *
 * - `uses:` a step library id - the library supplies the script, so nothing
 *   needs to be in the bundle
 * - an inline `task` naming a script - that file must be present
 *
 * Everything here is a resolution check the real WorkflowService will do
 * properly. This is a stand-in that is honest about the same failures, not an
 * implementation of resolution.
 */
object BundleValidator {

    /** Args the runtime substitutes; never file references. */
    private val PORT_PLACEHOLDER = Regex("""^(input|output)\.\d+$""")

    private val SCRIPT_EXTENSIONS = setOf("py", "R", "r", "sh", "jl", "kt")

    /**
     * Checks a workflow definition on its own, with no bundle around it.
     *
     * Everything except the file-presence checks, which need a bundle to look
     * in. Used for the demo catalogue, where the scripts live in carp-dsp
     * rather than in an upload - reporting every one of those as a missing file
     * would be noise, not a finding.
     */
    fun validateDefinition(yamlText: String): ValidationReport =
        validate(paths = emptySet(), yamlText = yamlText, checkFiles = false)

    fun validate(
        paths: Set<String>,
        yamlText: String?,
        checkFiles: Boolean = true,
    ): ValidationReport {
        val findings = mutableListOf<Finding>()

        if (yamlText == null) {
            return ValidationReport(
                valid = false,
                findings = listOf(
                    Finding(
                        Severity.ERROR,
                        "NO_WORKFLOW",
                        "The bundle has no workflow .yml at its root.",
                    ),
                ),
            )
        }

        // Schema and graph checks first - nothing below is meaningful if the
        // file does not parse.
        val detail = try {
            MockStore.parse(yamlText).also { checkGraph(it.definition) }
        } catch (e: WorkflowParseException) {
            return ValidationReport(
                valid = false,
                findings = listOf(
                    Finding(
                        Severity.ERROR,
                        "SCHEMA",
                        e.message ?: "The workflow could not be read.",
                    ),
                ),
            )
        }

        val steps = detail.definition.steps
        val normalised = paths.map { it.trim('/') }.toSet()
        val referenced = mutableSetOf<String>()
        val resolutions = mutableListOf<StepResolution>()

        steps.forEach { step -> referenced += scriptRefsOf(step) }
        findings += resolutionFindings(steps)
        findings += missingScriptFindings(steps, normalised, checkFiles)
        resolutions += resolutionsOf(steps, normalised, checkFiles)

        findings += outputWiringFindings(steps)
        findings += formatFindings(steps)
        findings += boundaryFindings(steps)

        if (!checkFiles) {
            return ValidationReport(
                valid = findings.none { it.severity == Severity.ERROR },
                workflowId = detail.summary.workflowId,
                workflowName = detail.summary.name,
                stepCount = steps.size,
                findings = findings,
                resolutions = resolutions,
            )
        }

        // Not an error: READMEs, sample data and helper modules imported at
        // runtime are all legitimate. But a renamed script left behind is a
        // common mistake and worth saying out loud.
        findings += unreferencedFileFindings(normalised, referenced)

        return ValidationReport(
            valid = findings.none { it.severity == Severity.ERROR },
            workflowId = detail.summary.workflowId,
            workflowName = detail.summary.name,
            stepCount = steps.size,
            fileCount = normalised.size,
            findings = findings,
            resolutions = resolutions,
        )
    }

    /**
     * A step must resolve to something: a library entry, or its own task.
     *
     * Shared with the engine path. carp-dsp throws rather than reporting when a
     * `uses:` reference cannot be found, so this stays the portal's check - a
     * thrown exception cannot say which step is at fault.
     */
    internal fun resolutionFindings(steps: List<StepSpec>): List<Finding> =
        steps.mapNotNull { step ->
            when {
                step.uses != null && StepLibrary.get(step.uses) == null -> Finding(
                    Severity.ERROR,
                    "UNKNOWN_LIBRARY_STEP",
                    "Step '${step.id}' uses '${step.uses}', which is not in the step library.",
                    stepId = step.id,
                )

                step.uses == null && step.task == null -> Finding(
                    Severity.ERROR,
                    "UNRESOLVABLE_STEP",
                    "Step '${step.id}' has neither a task nor a uses reference, so it cannot resolve.",
                    stepId = step.id,
                )

                else -> null
            }
        }

    /** Scripts a step names that the bundle does not carry. */
    internal fun missingScriptFindings(
        steps: List<StepSpec>,
        normalised: Set<String>,
        checkFiles: Boolean,
    ): List<Finding> {
        if (!checkFiles) return emptyList()

        return steps.flatMap { step ->
            scriptRefsOf(step)
                .filterNot { it in normalised }
                .map { path ->
                    Finding(
                        Severity.ERROR,
                        "MISSING_SCRIPT",
                        "Step '${step.id}' references '$path', which is not in the bundle.",
                        stepId = step.id,
                        path = path,
                    )
                }
        }
    }

    /**
     * Not an error: READMEs, sample data and helper modules imported at runtime
     * are all legitimate. But a renamed script left behind is a common mistake
     * and worth saying out loud.
     */
    internal fun unreferencedFileFindings(
        normalised: Set<String>,
        referenced: Set<String>,
    ): List<Finding> =
        (normalised - referenced)
            .filterNot { it.substringAfterLast('.', "") in setOf("yml", "yaml") }
            .sorted()
            .map { path ->
                Finding(
                    Severity.WARNING,
                    "UNREFERENCED_FILE",
                    "'$path' is in the bundle but no step references it.",
                    path = path,
                )
            }

    /** How each step resolves, for the report's per-step table. */
    internal fun resolutionsOf(
        steps: List<StepSpec>,
        normalised: Set<String>,
        checkFiles: Boolean,
    ): List<StepResolution> =
        steps.map { step ->
            val scripts = scriptRefsOf(step)
            val missing = scripts.filterNot { it in normalised }

            StepResolution(
                stepId = step.id,
                name = step.displayName,
                resolvedVia = when {
                    step.uses?.let { StepLibrary.get(it) } != null -> "library"
                    step.task != null -> "bundle"
                    else -> "unresolved"
                },
                libraryStepId = step.uses,
                scripts = scripts,
                missingScripts = if (checkFiles) missing else emptyList(),
            )
        }

    /**
     * Files a step points at.
     *
     * `entryPoint.scriptPath` is explicit. A `command` task is not: the script
     * is just an argument, so anything that looks like a path to a script file
     * is treated as one. That heuristic can miss a script passed in an unusual
     * way, and the real resolver should not need to guess.
     */
    internal fun scriptRefsOf(step: StepSpec): List<String> {
        val explicit = listOfNotNull(step.task?.entryPoint?.scriptPath)

        val fromArgs = (step.task?.args.orEmpty() + step.args)
            .filterNot { PORT_PLACEHOLDER.matches(it) }
            .filterNot { it.startsWith("-") }
            .filter { it.contains('/') }
            .filter { it.substringAfterLast('.', "") in SCRIPT_EXTENSIONS }

        return (explicit + fromArgs).distinct().map { it.trim('/') }
    }

    /**
     * Boundary inputs - the data a workflow expects from outside the pipeline.
     *
     * Implements the protocol coupling rules from carp-dsp's PROTOCOL_COUPLING
     * design, including its codes:
     *
     * - `protocol` input whose DataType the named protocol does not collect ->
     *   `PROTOCOL_DATA_NOT_COLLECTED`, an error. Matching is on the CARP
     *   DataType, never on file format.
     * - `protocol` input naming a protocol the portal does not hold ->
     *   `PROTOCOL_NOT_VALIDATED`, a warning. Not knowing a protocol is not the
     *   same as knowing it lacks the data.
     * - `external` with no uri or citation -> `EXTERNAL_DATA_UNATTRIBUTED`, a
     *   warning. Missing provenance is a documentation gap, not a fault.
     * - a boundary input with no source at all is treated as empty `external`,
     *   which is what keeps older workflows planning.
     * - `file` input whose path the study does not have -> `DATA_NOT_AVAILABLE`.
     *
     * Binding is per input, so a workflow may mix protocol and open data freely
     * and may reference several protocols at once.
     */
    internal fun boundaryFindings(
        steps: List<StepSpec>,
        /** Off for the engine path, which runs carp-dsp's own ProtocolCouplingValidator. */
        includeProtocol: Boolean = true,
    ): List<Finding> {
        val stepIds = steps.map { it.id }.toSet()

        return steps.flatMap { step ->
            step.inputs.mapNotNull { input ->
                val source = input.source

                // Wired to an upstream step: not a boundary input.
                if (source?.type == "step-output" && source.stepId in stepIds) {
                    return@mapNotNull null
                }

                when (source?.type) {
                    "protocol" -> {
                        if (!includeProtocol) return@mapNotNull null

                        val ref = source.protocol
                        val dataType = source.dataType

                        if (ref == null || dataType == null) {
                            return@mapNotNull Finding(
                                Severity.ERROR,
                                "PROTOCOL_DATA_NOT_COLLECTED",
                                "Step '${step.id}' declares '${input.id}' as protocol data " +
                                    "without naming both a protocol and a dataType.",
                                stepId = step.id,
                            )
                        }

                        val collected = ProtocolStore.collectedDataTypes(ref.id, ref.version)
                            ?: return@mapNotNull Finding(
                                Severity.WARNING,
                                "PROTOCOL_NOT_VALIDATED",
                                "Step '${step.id}' reads '$dataType' from protocol " +
                                    "'${ref.name ?: ref.id}', which this study does not hold. " +
                                    "Upload it to check compatibility.",
                                stepId = step.id,
                            )

                        if (dataType in collected) {
                            null
                        } else {
                            Finding(
                                Severity.ERROR,
                                "PROTOCOL_DATA_NOT_COLLECTED",
                                "Step '${step.id}' needs '$dataType', which " +
                                    "'${ref.name ?: ref.id}' does not collect. " +
                                    "It collects: ${collected.joinToString(", ").ifEmpty { "nothing" }}.",
                                stepId = step.id,
                            )
                        }
                    }

                    "external" ->
                        if (source.uri.isNullOrBlank() && source.citation.isNullOrBlank()) {
                            Finding(
                                Severity.WARNING,
                                "EXTERNAL_DATA_UNATTRIBUTED",
                                "Step '${step.id}' reads '${input.id}' from external data " +
                                    "with no uri or citation.",
                                stepId = step.id,
                            )
                        } else {
                            null
                        }

                    "file" -> {
                        val path = source.path
                            ?.removePrefix("./")
                            ?.removePrefix("data/")
                            ?.trim('/')

                        if (path != null && DataCatalogue.hasFile(path)) {
                            null
                        } else {
                            // A warning, not an error: the workflow is not
                            // malformed, the study simply does not hold this
                            // file yet, and adding it under Data fixes it.
                            // Treating it as an error would brand a perfectly
                            // good example as broken - which is exactly what it
                            // did to hr-clean-library.
                            Finding(
                                Severity.WARNING,
                                "DATA_NOT_AVAILABLE",
                                "Step '${step.id}' reads '${source.path}', which this study " +
                                    "does not have. Add it under Data before running.",
                                stepId = step.id,
                                path = source.path,
                            )
                        }
                    }

                    // No provenance at all - same treatment as empty external.
                    null -> Finding(
                        Severity.WARNING,
                        "EXTERNAL_DATA_UNATTRIBUTED",
                        "Step '${step.id}' takes '${input.id}' from outside the pipeline " +
                            "with no declared source.",
                        stepId = step.id,
                    )

                    else -> null
                }
            }
        }
    }

    /**
     * An input's declared format must match the output it reads.
     *
     * Both sides declare a `fileFormat`, so this is a stated contract rather
     * than an inference - a csv input wired to a png output is wrong however
     * the step is implemented. Only checked where both sides declare one.
     */
    private fun formatFindings(steps: List<StepSpec>): List<Finding> {
        val byId = steps.associateBy { it.id }

        return steps.flatMap { step ->
            step.inputs.mapNotNull { input ->
                val wanted = input.descriptor?.fileFormat ?: return@mapNotNull null
                val source = input.source ?: return@mapNotNull null
                val producer = byId[source.stepId] ?: return@mapNotNull null

                val outputs = if (producer.uses != null) {
                    StepLibrary.get(producer.uses)?.definition?.steps?.firstOrNull()?.outputs
                } else {
                    producer.outputs
                } ?: return@mapNotNull null

                val produced = outputs
                    .firstOrNull { it.id == source.outputId }
                    ?.descriptor
                    ?.fileFormat
                    ?: return@mapNotNull null

                if (produced.equals(wanted, ignoreCase = true)) {
                    null
                } else {
                    Finding(
                        Severity.ERROR,
                        "FORMAT_MISMATCH",
                        "Step '${step.id}' expects '${input.id}' as $wanted, but " +
                            "'${producer.id}.${source.outputId}' produces $produced.",
                        stepId = step.id,
                    )
                }
            }
        }
    }

    /**
     * An input naming a producing step must name an output that step actually
     * declares. Skipped where the producer is a library step, since the library
     * declares its outputs rather than the workflow file.
     */
    private fun outputWiringFindings(steps: List<StepSpec>): List<Finding> {
        val byId = steps.associateBy { it.id }

        return steps.flatMap { step ->
            step.inputs.mapNotNull { input ->
                val source = input.source ?: return@mapNotNull null
                val fromId = source.stepId ?: return@mapNotNull null
                val outputId = source.outputId ?: return@mapNotNull null
                val producer = byId[fromId] ?: return@mapNotNull null

                val declared = if (producer.uses != null) {
                    StepLibrary.get(producer.uses)
                        ?.definition
                        ?.steps
                        ?.firstOrNull()
                        ?.outputs
                        ?.map { it.id }
                        ?: return@mapNotNull null
                } else {
                    producer.outputs.map { it.id }
                }

                if (outputId in declared) {
                    null
                } else {
                    Finding(
                        Severity.ERROR,
                        "UNKNOWN_OUTPUT",
                        "Step '${step.id}' reads '$outputId' from '$fromId', which declares " +
                            (if (declared.isEmpty()) "no outputs." else declared.joinToString(", ") + "."),
                        stepId = step.id,
                    )
                }
            }
        }
    }

    /**
     * The graph checks this validator makes before the others: steps present,
     * ids unique, every reference pointing somewhere, and no cycle.
     *
     * Kept here rather than in parsing, which only reads the file now. Goes with
     * this class when the engine is the only validator.
     */
    private fun checkGraph(file: WorkflowView) {
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
    private fun detectCycle(file: WorkflowView): String? {
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
}
