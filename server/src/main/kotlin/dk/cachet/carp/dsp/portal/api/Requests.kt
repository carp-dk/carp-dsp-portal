package dk.cachet.carp.dsp.portal.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request objects, mirroring the sealed `*ServiceRequest` hierarchies in
 * carp.analytics.core. One endpoint per service; the body says which call it is
 * via `__type`.
 *
 * Names match core's subclasses so the swap at W4-1 is a change of
 * implementation, not of contract. Two deliberate departures are marked below.
 */

// ---- WorkflowService --------------------------------------------------------

@Serializable
sealed interface WorkflowServiceRequest

@Serializable
@SerialName("ListWorkflows")
data class ListWorkflows(val studyId: String) : WorkflowServiceRequest

@Serializable
@SerialName("GetWorkflow")
data class GetWorkflow(val studyId: String, val workflowId: String) : WorkflowServiceRequest

/**
 * DEPARTURE: core's `CreateWorkflow` takes a resolved `Workflow`. The mock
 * takes the raw YAML instead, because parsing it here is what makes the upload
 * page's validation errors real. W4-1 moves parsing to the client or to
 * resolution and this becomes core's shape.
 */
@Serializable
@SerialName("CreateWorkflow")
data class CreateWorkflow(
    val studyId: String,
    val yaml: String,
    /** Skip the resolution checks and mark it a draft, so work in progress can be kept. */
    val draft: Boolean = false,
    /** Required to replace a workflow already in the study - the id is the key. */
    val overwrite: Boolean = false,
) : WorkflowServiceRequest

@Serializable
@SerialName("DeleteWorkflow")
data class DeleteWorkflow(val studyId: String, val workflowId: String) : WorkflowServiceRequest

// ---- ExecutionService -------------------------------------------------------

@Serializable
sealed interface ExecutionServiceRequest

@Serializable
@SerialName("ExecuteWorkflow")
data class ExecuteWorkflow(val studyId: String, val workflowId: String) : ExecutionServiceRequest

/** DEPARTURE: raw YAML rather than a resolved `Workflow`, as above. */
@Serializable
@SerialName("ExecuteWorkflowFromDefinition")
data class ExecuteWorkflowFromDefinition(val studyId: String, val yaml: String) : ExecutionServiceRequest

/**
 * DEPARTURE: no counterpart in core, which has no notion of stopping a run.
 * A demo needs one - see run/DspRunner.kt.
 */
@Serializable
@SerialName("CancelExecution")
data class CancelExecution(val executionId: String) : ExecutionServiceRequest

@Serializable
@SerialName("GetExecutionState")
data class GetExecutionState(val executionId: String) : ExecutionServiceRequest

@Serializable
@SerialName("GetExecutionResult")
data class GetExecutionResult(val executionId: String) : ExecutionServiceRequest

@Serializable
@SerialName("FindExecutions")
data class FindExecutions(val studyId: String, val workflowId: String? = null) : ExecutionServiceRequest

// ---- ArtefactRegistryService ------------------------------------------------
// Separate service on purpose: artefacts belong to the registry (W2-3), not to
// ExecutionService. Keeping them apart here means the results view already
// calls the right thing.

@Serializable
sealed interface ArtefactRegistryRequest

@Serializable
@SerialName("GetRunArtefacts")
data class GetRunArtefacts(val executionId: String) : ArtefactRegistryRequest

/**
 * Parse without storing.
 *
 * This is what makes the composer's YAML editor two-way: an edit goes to the
 * server, comes back parsed, and the composer rebuilds from it. Keeps the
 * schema in one place instead of growing a second parser in the browser.
 */
@Serializable
@SerialName("ParseWorkflow")
data class ParseWorkflow(val yaml: String) : WorkflowServiceRequest

/**
 * Dry run: check a bundle without storing anything.
 *
 * Only paths and the workflow text are sent - validation needs nothing else,
 * and shipping every script's bytes just to be told a path is missing would be
 * wasteful. Storing the scripts is a W4 concern.
 */
@Serializable
@SerialName("ValidateBundle")
data class ValidateBundle(
    val studyId: String,
    val paths: List<String>,
    val yaml: String? = null,
) : WorkflowServiceRequest

// ---- StudyDataService -------------------------------------------------------
// The portal is not connected to a live study, so a protocol is uploaded or
// taken from the demo fixtures. That matches the coupling design, which
// validates against a protocol definition rather than a deployment.

@Serializable
sealed interface StudyDataServiceRequest

@Serializable
@SerialName("ListDataSources")
data object ListDataSources : StudyDataServiceRequest

@Serializable
@SerialName("ListProtocols")
data object ListProtocols : StudyDataServiceRequest

/** A StudyProtocolSnapshot as JSON. */
@Serializable
@SerialName("UploadProtocol")
data class UploadProtocol(val json: String) : StudyDataServiceRequest

/** Switches which protocol version the study works against. */
@Serializable
@SerialName("ActivateProtocol")
data class ActivateProtocol(val id: String, val version: Int) : StudyDataServiceRequest

@Serializable
@SerialName("AddDataFile")
data class AddDataFile(
    val path: String,
    val sizeBytes: Long? = null,
    val description: String? = null,
) : StudyDataServiceRequest

@Serializable
@SerialName("AddExternalDataset")
data class AddExternalDataset(
    val uri: String,
    val citation: String? = null,
    val description: String? = null,
) : StudyDataServiceRequest

// ---- StepLibraryService -----------------------------------------------------
// The service is planned (the epic lists it under New) but does not exist in
// core yet, so these request shapes are a guess. The payload is not: entries
// are the real `step.yaml` files from carp.dsp.steps, parsed as written.

@Serializable
sealed interface StepLibraryServiceRequest

@Serializable
@SerialName("ListLibrarySteps")
data object ListLibrarySteps : StepLibraryServiceRequest

@Serializable
@SerialName("GetLibraryStep")
data class GetLibraryStep(val stepId: String) : StepLibraryServiceRequest

/**
 * Add a step to the library from a `step.yaml`.
 *
 * The real library is vendored and gated - a step arrives through a PR and a
 * conformance review, not through an upload. This exists so the authoring flow
 * can be shown; anything added lands unreviewed and is marked as such.
 */
@Serializable
@SerialName("CreateLibraryStep")
data class CreateLibraryStep(val yaml: String) : StepLibraryServiceRequest

/** The demo workflows from carp-dsp, as examples to copy into a study. */
@Serializable
@SerialName("ListDemoWorkflows")
data object ListDemoWorkflows : WorkflowServiceRequest

/** Copies a demo into the study's repository. */
@Serializable
@SerialName("AddDemoWorkflow")
data class AddDemoWorkflow(val studyId: String, val path: String) : WorkflowServiceRequest

// ---- ScheduleService --------------------------------------------------------
// INVENTED - no counterpart in core. The epic removed
// ScheduleManagementService ("exists only to drive triggers; returns at GUI
// v1.1"), so unlike everything above, this shape is not mirroring anything and
// should be expected to change wholesale when the real service arrives.
//
// Activation is a protocol-trigger call in the real system; DSP only records
// that it happened, as a TriggerActivation artefact.

@Serializable
sealed interface ScheduleServiceRequest

@Serializable
@SerialName("CreateSchedule")
data class CreateSchedule(
    val studyId: String,
    val workflowId: String,
    val cadence: String,
    val startAt: String? = null,
) : ScheduleServiceRequest

@Serializable
@SerialName("ListSchedules")
data class ListSchedules(
    val studyId: String,
    val workflowId: String? = null,
) : ScheduleServiceRequest

@Serializable
@SerialName("DeleteSchedule")
data class DeleteSchedule(val scheduleId: String) : ScheduleServiceRequest

@Serializable
@SerialName("SetScheduleEnabled")
data class SetScheduleEnabled(val scheduleId: String, val enabled: Boolean) : ScheduleServiceRequest
