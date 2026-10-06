package dk.cachet.carp.dsp.portal.api

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.DataPortDescriptor
import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentVariableInputSource
import carp.dsp.core.application.authoring.descriptor.ExternalInputSource
import carp.dsp.core.application.authoring.descriptor.FileInputSource
import carp.dsp.core.application.authoring.descriptor.InProcessTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.InputSource
import carp.dsp.core.application.authoring.descriptor.LibraryDescriptor
import carp.dsp.core.application.authoring.descriptor.ModuleEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.ProtocolInputSource
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.RTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ReferencedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.ScriptEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.StepDescriptor
import carp.dsp.core.application.authoring.descriptor.StepOutputInputSource
import carp.dsp.core.application.authoring.descriptor.TaskDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import kotlinx.serialization.Serializable

/**
 * A workflow as the UI draws it: steps, their wiring, and environments.
 *
 * Built from core's [WorkflowDescriptor] by [toView], never parsed from YAML
 * itself, so it cannot disagree with what the engine reads. The shape is the web
 * client's, flattened where core uses sealed types.
 */
@Serializable
data class WorkflowView(
    val schemaVersion: String = "1.0",
    val metadata: WorkflowMetadata,
    val environments: Map<String, EnvironmentSpec> = emptyMap(),
    val steps: List<StepSpec> = emptyList(),
    /** Publication metadata; set only on a library step. */
    val library: LibraryDescriptor? = null,
)

@Serializable
data class WorkflowMetadata(
    /** A workflow without its own id is given one by the caller, normally from its filename. */
    val id: String = "",
    val name: String,
    val description: String? = null,
    val version: String = "1.0",
    val tags: List<String> = emptyList(),
)

@Serializable
data class EnvironmentSpec(
    val name: String,
    val kind: String,
    val spec: EnvironmentBody = EnvironmentBody(),
)

@Serializable
data class EnvironmentBody(
    val dependencies: List<String> = emptyList(),
    val pythonVersion: List<String> = emptyList(),
    val channels: List<String> = emptyList(),
)

/**
 * A step is written one of two ways, and both are in the demo set:
 *
 * - inline, carrying its own [metadata] and [task]
 * - by reference, carrying [uses] - a step library id such as
 *   `core.reshape.select-columns`. The library supplies the task, the metadata
 *   and the output declarations, so all three are absent from the file.
 *
 * Everything but [id] is therefore optional. The UI falls back to the step id
 * when there is no metadata.
 */
@Serializable
data class StepSpec(
    val id: String,
    val metadata: StepMetadata? = null,
    val uses: String? = null,
    val environmentId: String? = null,
    val dependsOn: List<String> = emptyList(),
    val task: TaskSpec? = null,
    val args: List<String> = emptyList(),
    val inputs: List<PortSpec> = emptyList(),
    val outputs: List<PortSpec> = emptyList(),
) {
    /** Display name: the step's own, or its id when it comes from the library. */
    val displayName: String get() = metadata?.name ?: id
}

@Serializable
data class StepMetadata(
    val name: String,
    val description: String? = null,
    val version: String? = null,
)

/**
 * Covers both task shapes in the demo set: `command` carries [executable],
 * `python` carries [entryPoint]. Both are nullable so neither kind fails.
 */
@Serializable
data class TaskSpec(
    val type: String,
    val id: String? = null,
    val name: String? = null,
    val executable: String? = null,
    val entryPoint: EntryPoint? = null,
    val args: List<String> = emptyList(),
)

@Serializable
data class EntryPoint(
    val type: String? = null,
    val scriptPath: String? = null,
    val module: String? = null,
    val function: String? = null,
)

@Serializable
data class PortSpec(
    val id: String,
    val descriptor: PortDescriptor? = null,
    val source: PortSource? = null,
)

@Serializable
data class PortDescriptor(
    val fileFormat: String? = null,
    val encoding: String? = null,
    val notes: String? = null,
)

/**
 * Where an input comes from - a union flattened into one type, since the
 * variants are distinguished by `type` rather than by a discriminated schema.
 *
 * - `step-output` - produced upstream. `stepId` plus `outputId` is the data-flow
 *   edge, distinct from [StepSpec.dependsOn], which is the control edge. They
 *   are not the same graph: a step can read from an ancestor it does not
 *   directly depend on.
 * - `protocol` - collected by a study protocol. Checked at plan time against
 *   that protocol's collected DataTypes.
 * - `external` - open data or a prior export. Never protocol-checked, only
 *   attributed.
 * - `file` - a data file supplied with the study.
 * - `env-var` - read from an environment variable.
 *
 * A boundary input with no `source` is treated as empty `external`, which keeps
 * older workflows planning.
 */
@Serializable
data class PortSource(
    val type: String,
    val stepId: String? = null,
    val outputId: String? = null,
    val protocol: ProtocolRef? = null,
    /** A CARP DataType, e.g. `dk.cachet.carp.heartrate` - not a file format. */
    val dataType: String? = null,
    val uri: String? = null,
    val citation: String? = null,
    val path: String? = null,
    val variableName: String? = null,
)

@Serializable
data class ProtocolRef(
    val id: String,
    val name: String? = null,
    val version: Int? = null,
)

/**
 * Returns this workflow as the UI draws it, identified by [id].
 *
 * A step without an `id` is shown as `#<index>`, the key its time parameters use.
 */
fun WorkflowDescriptor.toView(id: String): WorkflowView = WorkflowView(
    schemaVersion = schemaVersion,
    metadata = WorkflowMetadata(
        id = id,
        name = metadata.name,
        description = metadata.description,
        version = metadata.version,
        tags = metadata.tags,
    ),
    environments = environments.mapValues { (_, env) -> env.toView() },
    steps = steps.mapIndexed { index, step -> step.toView(step.id ?: "#$index") },
    library = library,
)

private fun EnvironmentDescriptor.toView() = EnvironmentSpec(
    name = name,
    kind = kind,
    spec = EnvironmentBody(
        dependencies = spec["dependencies"].orEmpty(),
        pythonVersion = spec["pythonVersion"].orEmpty(),
        channels = spec["channels"].orEmpty(),
    ),
)

private fun StepDescriptor.toView(id: String): StepSpec = when (this) {
    is ReferencedStepDescriptor -> StepSpec(
        id = id,
        metadata = metadata?.let { StepMetadata(it.name ?: id, it.description, it.version) },
        uses = uses,
        dependsOn = dependsOn,
        args = args.orEmpty(),
        inputs = inputs.map { it.toView() },
    )

    is DefinedStepDescriptor -> StepSpec(
        id = id,
        metadata = metadata?.let { StepMetadata(it.name ?: id, it.description, it.version) },
        environmentId = environmentId,
        dependsOn = dependsOn,
        task = task.toView(),
        inputs = inputs.map { it.toView() },
        outputs = outputs.map { it.toView() },
    )
}

private fun TaskDescriptor.toView(): TaskSpec = when (this) {
    is CommandTaskDescriptor -> TaskSpec("command", id, name, executable = executable, args = args)
    is PythonTaskDescriptor -> TaskSpec(
        type = "python",
        id = id,
        name = name,
        entryPoint = when (val entry = entryPoint) {
            is ScriptEntryPointDescriptor -> EntryPoint(type = "script", scriptPath = entry.scriptPath)
            is ModuleEntryPointDescriptor -> EntryPoint(type = "module", module = entry.moduleName)
        },
        args = args,
    )
    is RTaskDescriptor -> TaskSpec(
        type = "r",
        id = id,
        name = name,
        entryPoint = EntryPoint(type = "r-script", scriptPath = entryPoint.scriptPath),
        args = args,
    )
    is InProcessTaskDescriptor -> TaskSpec("in-process", id, name)
}

private fun DataPortDescriptor.toView() = PortSpec(
    id = id.orEmpty(),
    descriptor = descriptor?.let { PortDescriptor(it.fileFormat, it.encoding, it.notes) },
    source = source?.toView(),
)

private fun InputSource.toView(): PortSource = when (this) {
    is FileInputSource -> PortSource(type = "file", path = path)
    is StepOutputInputSource -> PortSource(type = "step-output", stepId = stepId, outputId = outputId)
    is EnvironmentVariableInputSource -> PortSource(type = "env-var", variableName = variableName)
    is ProtocolInputSource -> PortSource(
        type = "protocol",
        protocol = ProtocolRef(protocol.id, protocol.name, protocol.version),
        dataType = dataType,
    )
    is ExternalInputSource -> PortSource(type = "external", uri = uri, citation = citation)
}
