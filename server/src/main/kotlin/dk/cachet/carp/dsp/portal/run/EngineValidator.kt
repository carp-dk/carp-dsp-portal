package dk.cachet.carp.dsp.portal.run

import carp.dsp.core.application.plan.ProtocolDataTypeProvider
import carp.dsp.core.application.run.WorkflowExecutor
import carp.dsp.core.application.run.WorkflowSource
import dk.cachet.carp.dsp.portal.api.Finding
import dk.cachet.carp.dsp.portal.api.Severity
import dk.cachet.carp.dsp.portal.api.ValidationReport
import dk.cachet.carp.dsp.portal.mock.BundleValidator
import dk.cachet.carp.dsp.portal.mock.MockStore
import dk.cachet.carp.dsp.portal.mock.ProtocolStore
import dk.cachet.carp.dsp.portal.mock.WorkflowParseException
import java.nio.file.Files
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity as EngineSeverity

/**
 * Validation by the engine that will run the workflow.
 *
 * [BundleValidator] answers the same questions with its own reimplementation of
 * resolution, which is the two-parser problem: a workflow can validate here and
 * fail there, and the demo finds out on stage. This hands the file to carp-dsp's
 * real resolve-and-plan path and reports what the planner says.
 *
 * Three checks stay on this side, because the planner is not in a position to
 * make them:
 *
 * - **the bundle** - whether an uploaded archive carries the scripts its steps
 *   name. Validation sees paths; only execution sees bytes.
 * - **study data** - whether a `file` input names something this study holds.
 *   That is [dk.cachet.carp.dsp.portal.mock.DataCatalogue]'s question.
 * - **external attribution** - a missing uri or citation is a documentation gap,
 *   not something a planner has an opinion about.
 *
 * Protocol coupling moved the other way: carp-dsp's `ProtocolCouplingValidator`
 * does it properly, so [ProtocolStore] is handed to it as a provider. Its verdict
 * is stricter than the mock's - a protocol this study has not uploaded is an
 * ERROR rather than a warning - and that is the point. Validation should say what
 * execution will do.
 */
object EngineValidator {

    /** [ProtocolStore] as the planner wants it. */
    private val protocols = ProtocolDataTypeProvider { id, version ->
        ProtocolStore.collectedDataTypes(id, version)?.toSet()
    }

    fun validateDefinition(yamlText: String): ValidationReport =
        validate(paths = emptySet(), yamlText = yamlText, checkFiles = false)

    fun validate(
        paths: Set<String>,
        yamlText: String?,
        checkFiles: Boolean = true,
    ): ValidationReport {
        if (yamlText == null) {
            return ValidationReport(
                valid = false,
                findings = listOf(
                    Finding(Severity.ERROR, "NO_WORKFLOW", "The bundle has no workflow .yml at its root."),
                ),
            )
        }

        // Parsed for the report's own fields and for the checks below - never for
        // a verdict, which is the planner's.
        val detail = try {
            MockStore.parse(yamlText)
        } catch (e: WorkflowParseException) {
            return ValidationReport(
                valid = false,
                findings = listOf(
                    Finding(Severity.ERROR, "SCHEMA", e.message ?: "The workflow could not be read."),
                ),
            )
        }

        val steps = detail.definition.steps
        val normalised = paths.map { it.trim('/') }.toSet()
        val referenced = steps.flatMap { BundleValidator.scriptRefsOf(it) }.toSet()

        val findings = plan(yamlText) +
            BundleValidator.resolutionFindings(steps) +
            BundleValidator.missingScriptFindings(steps, normalised, checkFiles) +
            BundleValidator.boundaryFindings(steps, includeProtocol = false) +
            if (checkFiles) BundleValidator.unreferencedFileFindings(normalised, referenced) else emptyList()

        return ValidationReport(
            valid = findings.none { it.severity == Severity.ERROR },
            workflowId = detail.summary.workflowId,
            workflowName = detail.summary.name,
            stepCount = steps.size,
            fileCount = if (checkFiles) normalised.size else 0,
            findings = findings,
            resolutions = BundleValidator.resolutionsOf(steps, normalised, checkFiles),
        )
    }

    /**
     * Resolves and plans the workflow, and reports what the planner found.
     *
     * The file goes to a directory of its own because resolution writes a
     * `steps.lock` beside it. Nothing is executed and no workspace is used; the
     * directory is thrown away either way.
     */
    private fun plan(yamlText: String): List<Finding> {
        val dir = Files.createTempDirectory("dsp-validate")

        return try {
            val file = dir.resolve("workflow.yaml").toFile().apply { writeText(yamlText) }

            val executor = WorkflowExecutor.filesystem(
                stepLibrary = PortalStepLibrary,
                workspaceRoot = dir.resolve("workspace"),
                options = WorkflowExecutor.Options(protocolDataTypeProvider = protocols),
            )

            val plan = executor.prepare(WorkflowSource.of(file.toPath())).plan

            // The planner works in UUIDs; the report points at the id the author
            // wrote, which is what the editor can highlight.
            val authoredId = plan.steps.associate { it.metadata.id to it.metadata.descriptorId }

            plan.issues.map { issue ->
                Finding(
                    severity = when (issue.severity) {
                        EngineSeverity.ERROR -> Severity.ERROR
                        EngineSeverity.WARNING -> Severity.WARNING
                        EngineSeverity.INFO -> Severity.INFO
                    },
                    code = issue.code,
                    message = issue.message,
                    stepId = issue.stepId?.let { authoredId[it] ?: it.toString() },
                )
            }
        } catch (e: Exception) {
            // Resolution throws rather than reporting - an unreadable file, or a
            // `uses:` reference it cannot follow. The message carries the detail,
            // including kaml's line and column.
            listOf(
                Finding(
                    Severity.ERROR,
                    "SCHEMA",
                    e.message ?: e::class.simpleName ?: "The workflow could not be resolved.",
                ),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
