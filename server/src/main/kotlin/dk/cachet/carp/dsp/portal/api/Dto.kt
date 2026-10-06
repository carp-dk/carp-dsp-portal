package dk.cachet.carp.dsp.portal.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The portal's wire contract: what the web client reads.
 *
 * Shaped after carp.analytics.core and filled from core's real types at the
 * boundary (see run/DspRunner.kt). They are kept as the portal's own because the
 * web client is built on them, and because they carry what core does not yet
 * have: cancelled runs, live provisioning and run context. docs/api-contract.md
 * records where each departs from core.
 *
 * UUIDs and Instants travel as strings, matching how core serialises them.
 */

// ---- ExecutionService -------------------------------------------------------

/**
 * DEPARTURE: `CANCELLED` has no counterpart in core's ExecutionStatus, which has
 * no notion of stopping a run. Cancelling is this layer's, and core will need the
 * value when W7 inherits it.
 */
@Serializable
enum class ExecutionStatus { PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED, CANCELLED }

@Serializable
data class ExecutorState(
    val executionId: String,
    val status: ExecutionStatus,
    val startedAt: String,
    val completedAt: String? = null,
    val workflowId: String,
    val studyId: String,
    /** What started the run and the values it was given. Portal-only. */
    val context: RunContext? = null,
)

/** Whether a person or a schedule started a run. */
enum class RunTrigger { MANUAL, SCHEDULE }

/**
 * What a run was given, recorded when it starts and never changed.
 *
 * DEPARTURE: no counterpart in core. When scheduling moves onto protocol
 * triggers, [scheduleId] and [fireTime] become core's TriggerActivation.
 *
 * @property windowFrom Start of the time window the run read, inclusive.
 * @property windowTo End of the time window, exclusive.
 * @property values Each time parameter the run was given, as written.
 */
@Serializable
data class RunContext(
    val trigger: RunTrigger = RunTrigger.MANUAL,
    val scheduleId: String? = null,
    val fireTime: String? = null,
    val windowFrom: String? = null,
    val windowTo: String? = null,
    val values: List<BoundValue> = emptyList(),
)

/** One argument value a run was given. */
@Serializable
data class BoundValue(val stepKey: String, val key: String, val value: String)

@Serializable
data class ExecutionReport(
    val runId: String,
    val planId: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val status: ExecutionStatus,
    val stepResults: List<StepRunResult> = emptyList(),
    val issues: List<ExecutionIssue> = emptyList(),
    /** Environment provisioning, which happens before the first step. */
    val provisioning: List<ProvisioningEvent> = emptyList(),
)

@Serializable
data class StepRunResult(
    val stepMetadata: StepRunMetadata,
    val status: ExecutionStatus,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val outputs: List<ProducedOutputRef> = emptyList(),
    val failure: StepFailure? = null,
    /** Tail of the step's log, for the run view. */
    val logTail: List<String> = emptyList(),
    val detail: StepRunDetail? = null,
)

/**
 * Provisioning an environment, as it happens.
 *
 * Solving one for the first time takes minutes and reusing one takes a moment,
 * and both used to be silent - the run view showed eight pending steps and no
 * sign of why nothing was happening.
 *
 * INVENTED: core's ExecutionReport carries `EnvironmentExecutionLogs`, but only
 * once the run has finished. This is the live view.
 */
@Serializable
data class ProvisioningEvent(
    val environmentId: String,
    val name: String,
    val status: ExecutionStatus,
    val startedAt: String,
    val finishedAt: String? = null,
    val durationMs: Long? = null,
    /** `BUILT`, `EXACT`, or `SUPERSET`. See run/EnvironmentReuse.kt. */
    val match: String = "BUILT",
    /**
     * What a `SUPERSET` environment carries that the workflow never asked for.
     *
     * Shown rather than hidden: a run that got packages it did not declare is a
     * run whose result a reader should be able to question.
     */
    val extras: List<String> = emptyList(),
    val message: String? = null,
)

/**
 * What a step actually ran, and what it printed.
 *
 * The engine records this on every command step; the run view shows it behind a
 * toggle, because most of the time the step name and its duration are all anyone
 * wants. [outputUrl] serves the captured stdout - it is a URL rather than the text
 * so a chatty step does not weigh down every poll of the report.
 */
@Serializable
data class StepRunDetail(
    val command: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val exitCode: Int? = null,
    /** Where the engine put the output, as it recorded it. Resolved by [outputUrl]. */
    val output: ResourceRef? = null,
    /** What the client fetches. Null when the step printed nothing. */
    val outputUrl: String? = null,
)

/**
 * Core's `StepMetadata` carries a UUID `id` plus a `descriptorId` holding the
 * author-written step id from the YAML. The UI keys off [descriptorId], so both
 * are here.
 */
@Serializable
data class StepRunMetadata(
    val id: String,
    val name: String,
    val description: String? = null,
    val descriptorId: String? = null,
)

/**
 * Where a produced output lives. Matches the `location` object written into a
 * real run's artifact `metadata.json` - it is a ResourceRef, not a bare string.
 */
@Serializable
data class ResourceRef(
    val kind: String = "RELATIVE_PATH",
    val value: String,
)

@Serializable
data class ProducedOutputRef(
    val outputId: String,
    val location: ResourceRef,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val contentType: String? = null,
    /**
     * The output's declared name - `imu-data-csv`, not its UUID.
     *
     * The id is what the engine keys on and the only thing it is guaranteed to
     * have; the name is what the workflow author wrote and the only thing worth
     * showing anyone. Null for a run recorded before this field existed.
     */
    val name: String? = null,
)

@Serializable
data class StepFailure(val kind: FailureKind, val message: String)

@Serializable
enum class FailureKind {
    COMMAND_FAILED, TIMEOUT, CANCELLED, INFRASTRUCTURE, OUTPUT_MISSING, UNKNOWN
}

@Serializable
data class ExecutionIssue(
    val stepMetadata: StepRunMetadata? = null,
    val kind: ExecutionIssueKind,
    val message: String,
)

@Serializable
enum class ExecutionIssueKind {
    WORKSPACE_ERROR,
    POLICY_VIOLATION,
    ORCHESTRATOR_ERROR,
    OUTPUT_MISSING,
    UNEXPECTED_OUTPUT,
    ARTIFACT_COLLECTION_FAILED,
    PROCESS_FAILED,
    UNKNOWN,
}

// ---- WorkflowService --------------------------------------------------------

/** Row in the study's workflow list. */
@Serializable
data class WorkflowSummary(
    val workflowId: String,
    val name: String,
    val description: String? = null,
    val version: String,
    val tags: List<String> = emptyList(),
    val stepCount: Int,
    val lastRunAt: String? = null,
    val lastRunStatus: ExecutionStatus? = null,
    /**
     * Saved without passing validation. A draft can be edited and re-saved but
     * should not be run - the checks it skipped are the ones that decide
     * whether it can run at all.
     */
    val draft: Boolean = false,
)

/** The workflow detail view: summary, the parsed pipeline, and the raw file. */
@Serializable
data class WorkflowDetail(
    val summary: WorkflowSummary,
    val definition: WorkflowView,
    val rawYaml: String,
)

// ---- Artefacts --------------------------------------------------------------

/**
 * Artefacts use core's `__type` discriminator, not kotlinx's default `type`.
 * Getting this wrong is invisible until the real client is plugged in.
 */
@Serializable
data class ArtifactReference(
    val uri: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
sealed interface WorkflowArtifact

/**
 * A workflow output there is no specific type for: an inline payload, a stored
 * reference, or both. Mirrors core's `GenericArtifact`.
 *
 * This is what a run produces before a step declares richer typing through its
 * manifest, so it is the common case today rather than an edge case.
 */
@Serializable
@SerialName("dk.cachet.carp.dsp.generic")
data class GenericArtifact(
    val payload: JsonElement? = null,
    val reference: ArtifactReference? = null,
    val schemaRef: String? = null,
) : WorkflowArtifact

@Serializable
@SerialName("dk.cachet.carp.dsp.analytic.feature")
data class ComputedFeature(
    val name: String,
    val value: Double,
    val unit: String? = null,
) : WorkflowArtifact

@Serializable
@SerialName("dk.cachet.carp.dsp.analytic.summary")
data class SummaryStatistic(
    val name: String,
    val value: Double,
    val sampleCount: Int,
    val unit: String? = null,
) : WorkflowArtifact

@Serializable
@SerialName("dk.cachet.carp.dsp.analytic.table")
data class FeatureTable(
    val reference: ArtifactReference,
    val columns: List<String>,
    val rowCount: Long? = null,
) : WorkflowArtifact

@Serializable
@SerialName("dk.cachet.carp.dsp.media.image")
data class ImageArtifact(
    val reference: ArtifactReference,
    val widthPixels: Int? = null,
    val heightPixels: Int? = null,
) : WorkflowArtifact

@Serializable
@SerialName("dk.cachet.carp.dsp.media.plot")
data class PlotArtifact(
    val reference: ArtifactReference,
    val specFormat: String? = null,
    val title: String? = null,
) : WorkflowArtifact

/**
 * A demo workflow from carp-dsp, offered as an example to copy into a study.
 *
 * [valid] is false for the `injections/` fixtures, which are deliberately
 * broken. [problem] carries the reason, which is the validator's own message.
 */
@Serializable
data class DemoWorkflow(
    val path: String,
    val workflowId: String,
    val summary: WorkflowSummary? = null,
    val valid: Boolean,
    val problem: String? = null,
    val rawYaml: String,
)

/** A study protocol, reduced to what the coupling check and the UI need. */
@Serializable
data class ProtocolSummary(
    val id: String,
    val name: String,
    val version: Int,
    val description: String? = null,
    val deviceRoles: List<String> = emptyList(),
    /** Every CARP DataType this protocol collects. */
    val collectedDataTypes: List<String> = emptyList(),
    val active: Boolean = false,
)

/**
 * One thing the study has data for.
 *
 * [kind] is `protocol`, `file` or `external`, matching the three non-step-output
 * source types a workflow input can declare. A workflow is compatible with the
 * study when every boundary input it declares is satisfied by one of these.
 */
@Serializable
data class DataSource(
    val kind: String,
    val id: String,
    val name: String,
    val description: String? = null,
    /** For protocol data: the CARP DataType, e.g. dk.cachet.carp.heartrate. */
    val dataType: String? = null,
    val protocolId: String? = null,
    val uri: String? = null,
    val citation: String? = null,
    val sizeBytes: Long? = null,
)

// ---- Bundle validation ------------------------------------------------------

@Serializable
enum class Severity { ERROR, WARNING, INFO }

/**
 * One validation result. [code] is stable and switchable; [message] is for
 * people. [stepId] and [path] point at what to fix, where that is known.
 */
@Serializable
data class Finding(
    val severity: Severity,
    val code: String,
    val message: String,
    val stepId: String? = null,
    val path: String? = null,
)

/** How one-step resolves, and what it still needs. */
@Serializable
data class StepResolution(
    val stepId: String,
    val name: String,
    /** "library", "bundle", or "unresolved". */
    val resolvedVia: String,
    val libraryStepId: String? = null,
    val scripts: List<String> = emptyList(),
    val missingScripts: List<String> = emptyList(),
)

@Serializable
data class ValidationReport(
    val valid: Boolean,
    val workflowId: String? = null,
    val workflowName: String? = null,
    val stepCount: Int = 0,
    val fileCount: Int = 0,
    val findings: List<Finding> = emptyList(),
    val resolutions: List<StepResolution> = emptyList(),
)

/**
 * A saved schedule. INVENTED alongside ScheduleService - see Requests.kt.
 *
 * @property startAt When the first run fires; later runs follow at [cadence]
 *   from it. Defaults to [createdAt].
 */
@Serializable
data class Schedule(
    val scheduleId: String,
    val studyId: String,
    val workflowId: String,
    val cadence: String,
    val startAt: String? = null,
    val createdAt: String,
    /** A paused schedule does not fire; resuming catches up with one run. */
    val enabled: Boolean = true,
)

/**
 * A schedule and where it stands, worked out from its runs.
 *
 * @property nextRunAt When the next run fires; null once [completed] or paused.
 * @property windowFrom Where the next run's window starts: the end of the last
 *   successful run's window, or the workflow's window start.
 * @property windowEnd The workflow's window end, where the schedule stops.
 * @property completed Whether the window has reached [windowEnd].
 * @property runs This schedule's runs, newest first.
 */
@Serializable
data class ScheduleView(
    val scheduleId: String,
    val studyId: String,
    val workflowId: String,
    val cadence: String,
    val startAt: String? = null,
    val createdAt: String,
    val enabled: Boolean = true,
    val nextRunAt: String? = null,
    val windowFrom: String? = null,
    val windowEnd: String? = null,
    val completed: Boolean = false,
    val runs: List<ExecutorState> = emptyList(),
)

// ---- Time parameters ------------------------------------------------------------
//
// Portal-only. Detection and binding are core's; which value a run gets is the
// portal's, so the workflow file never carries a schedule.

/** A time value found in a workflow's step arguments. */
@Serializable
data class TimeParameterDto(
    val stepKey: String,
    val flag: String,
    val name: String? = null,
    val label: String,
    val value: String,
    val format: String,
)

/** How runs set one time parameter. */
enum class BindingMode { KEEP, FIXED, WINDOW_START, WINDOW_END }

/**
 * @property value For [BindingMode.FIXED], the instant to use (ISO-8601, UTC).
 */
@Serializable
data class ParameterBinding(
    val stepKey: String,
    val flag: String,
    val name: String? = null,
    val mode: BindingMode = BindingMode.KEEP,
    val value: String? = null,
)

/**
 * How runs of one workflow set its time parameters.
 *
 * @property windowStart Where the window starts; required when a parameter
 *   follows the window.
 * @property windowEnd Where it ends; open when null.
 */
@Serializable
data class WorkflowBindings(
    val workflowId: String,
    val windowStart: String? = null,
    val windowEnd: String? = null,
    val parameters: List<ParameterBinding> = emptyList(),
)

/**
 * What a workflow's time parameters are and how they are bound.
 *
 * @property bindings Null until the user has chosen.
 * @property stale Saved bindings whose argument the workflow no longer has.
 */
@Serializable
data class TimeParametersView(
    val detected: List<TimeParameterDto>,
    val bindings: WorkflowBindings? = null,
    val stale: List<ParameterBinding> = emptyList(),
)

/** First rows of a tabular output, so the results view can show real data. */
@Serializable
data class TablePreview(
    val columns: List<String>,
    val rows: List<List<String>>,
    val totalRows: Long? = null,
)

/**
 * Artefacts produced by a run, keyed by the step that produced them.
 *
 * Mock-only wrapper. [downloadUrl] and [preview] deliberately live here rather
 * than on the artefact, so the artefact types stay a faithful mirror of
 * carp.analytics.core. In the real system the download URL comes from
 * resolving the artefact's `ArtifactReference.uri` against the artefact store.
 */
@Serializable
data class ArtifactEntry(
    val stepId: String,
    val outputId: String,
    val artifact: WorkflowArtifact,
    val downloadUrl: String? = null,
    val preview: TablePreview? = null,
    /** Display names for [stepId] and [outputId]. Null when the run predates them. */
    val stepName: String? = null,
    val outputName: String? = null,
)

/**
 * The portal's run mode: `real` or `simulated`.
 *
 * Not part of the analytics surface - see the `/mode` endpoints in
 * AnalyticsRoutes.
 */
@Serializable
data class RunModeDto(val mode: String)

/**
 * How far environment reuse may stretch: `exact` or `superset`.
 *
 * Not part of the analytics surface - see the `/environments/reuse` endpoints in
 * AnalyticsRoutes and run/EnvironmentReuse.kt.
 */
@Serializable
data class EnvironmentReuseDto(val mode: String)

/**
 * One environment directory on the state volume.
 *
 * `status` is `solved`, or `incomplete` for a build that failed or was
 * interrupted. Times are ISO-8601; `lastUsedAt` is null until a run has built or
 * reused the environment since usage was recorded.
 *
 * Not part of the analytics surface - see the `/environments` endpoint in
 * AnalyticsRoutes.
 */
@Serializable
data class EnvironmentDto(
    val id: String,
    val kind: String,
    val name: String,
    val status: String,
    val runtime: String?,
    val dependencies: List<String>,
    val channels: List<String>,
    val sizeBytes: Long?,
    val builtAt: String?,
    val lastUsedAt: String?,
)
