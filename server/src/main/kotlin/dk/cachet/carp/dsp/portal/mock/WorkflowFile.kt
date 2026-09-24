package dk.cachet.carp.dsp.portal.mock

import kotlinx.serialization.Serializable

/**
 * The workflow YAML schema, as written in carp-dsp's demo workflows.
 */
@Serializable
data class WorkflowFile(
    val schemaVersion: String = "1.0",
    val metadata: WorkflowMetadata,
    val environments: Map<String, EnvironmentSpec> = emptyMap(),
    val steps: List<StepSpec> = emptyList(),
)

@Serializable
data class WorkflowMetadata(
    /**
     * Optional, because not every demo declares one - `hr-clean-library.yaml`
     * has name, description, version and tags but no id. Callers supply a
     * fallback derived from the filename, which is what a file-backed catalogue
     * would key on anyway. Worth fixing at source in carp-dsp.
     */
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
)

@Serializable
data class ProtocolRef(
    val id: String,
    val name: String? = null,
    val version: Int? = null,
)
