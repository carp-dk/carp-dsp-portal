package dk.cachet.carp.dsp.portal.run

import dk.cachet.carp.dsp.portal.api.Finding
import dk.cachet.carp.dsp.portal.api.Severity
import dk.cachet.carp.dsp.portal.api.StepResolution
import dk.cachet.carp.dsp.portal.api.StepSpec
import dk.cachet.carp.dsp.portal.catalogue.DataCatalogue
import dk.cachet.carp.dsp.portal.catalogue.StepLibrary

/**
 * The checks the planner is not in a position to make: whether a bundle carries
 * the scripts its steps name, whether a `file` input is something this study
 * holds, whether `external` data is attributed, and which step a failed `uses:`
 * lookup belongs to.
 *
 * Run by [EngineValidator] alongside the plan.
 */
internal object BundleChecks {

    /** Args the runtime substitutes; never file references. */
    private val PORT_PLACEHOLDER = Regex("""^(input|output)\.\d+$""")

    private val SCRIPT_EXTENSIONS = setOf("py", "R", "r", "sh", "jl", "kt")

    /**
     * A step must resolve to something: a library entry, or its own task.
     *
     * carp-dsp throws rather than reporting when a
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
     * Protocol inputs are not checked here: the planner's
     * ProtocolCouplingValidator does that. What is left:
     *
     * - `external` with no uri or citation -> `EXTERNAL_DATA_UNATTRIBUTED`, a
     *   warning. Missing provenance is a documentation gap, not a fault.
     * - a boundary input with no source at all is treated as empty `external`,
     *   which is what keeps older workflows planning.
     * - `file` input whose path the study does not have -> `DATA_NOT_AVAILABLE`.
     */
    internal fun boundaryFindings(steps: List<StepSpec>): List<Finding> {
        val stepIds = steps.map { it.id }.toSet()

        return steps.flatMap { step ->
            step.inputs.mapNotNull { input ->
                val source = input.source

                // Wired to an upstream step: not a boundary input.
                if (source?.type == "step-output" && source.stepId in stepIds) {
                    return@mapNotNull null
                }

                when (source?.type) {
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
}
