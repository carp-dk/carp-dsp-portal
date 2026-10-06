@file:OptIn(kotlin.time.ExperimentalTime::class)

package dk.cachet.carp.dsp.portal.run

import carp.dsp.core.application.authoring.parameters.ArgumentRef
import carp.dsp.core.application.authoring.parameters.TimeParameter
import carp.dsp.core.application.authoring.parameters.bindArguments
import carp.dsp.core.application.authoring.parameters.detectTimeParameters
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import dk.cachet.carp.dsp.portal.api.BindingMode
import dk.cachet.carp.dsp.portal.api.BoundValue
import dk.cachet.carp.dsp.portal.api.ParameterBinding
import dk.cachet.carp.dsp.portal.api.TimeParameterDto
import dk.cachet.carp.dsp.portal.api.TimeParametersView
import dk.cachet.carp.dsp.portal.api.WorkflowBindings
import dk.cachet.carp.dsp.portal.store.BindingStore
import java.time.Instant
import kotlin.time.Instant as KInstant

/** A half-open time range, `[from, to)`. */
data class TimeWindow(val from: Instant, val to: Instant)

/** A workflow with its time parameters set, and the values it was given. */
data class BoundWorkflow(val yaml: String, val values: List<BoundValue>)

/**
 * Checks a workflow's time-parameter bindings, and sets them for a run.
 *
 * The bindings sit beside the workflow rather than in it: the stored YAML is
 * never rewritten, so a shared workflow stays exactly as it was imported. A run
 * gets a copy with concrete values, which is what the engine plans.
 */
object TimeBindings {

    private val codec = WorkflowYamlCodec()

    /** Returns the time parameters found in [yaml]; none for a workflow that cannot be read. */
    fun detect(yaml: String): List<TimeParameter> =
        runCatching { detectTimeParameters(codec.decodeOrThrow(yaml)) }.getOrDefault(emptyList())

    /** Returns what [yaml]'s time parameters are and how [workflowId] binds them. */
    fun view(workflowId: String, yaml: String): TimeParametersView {
        val detected = detect(yaml)
        val saved = BindingStore.get(workflowId)
        val refs = detected.map { it.ref }.toSet()

        return TimeParametersView(
            detected = detected.map { it.toDto() },
            bindings = saved?.let { it.copy(parameters = it.parameters.filter { p -> p.ref() in refs }) },
            stale = saved?.parameters.orEmpty().filterNot { it.ref() in refs },
        )
    }

    fun get(workflowId: String): WorkflowBindings? = BindingStore.get(workflowId)

    /**
     * Saves how [yaml]'s time parameters are set.
     *
     * @throws IllegalArgumentException when a binding names a parameter [yaml]
     *   does not have, a fixed value is missing or not an instant, a parameter
     *   follows the window but the window has no start, or the window ends
     *   before it starts.
     */
    fun save(value: WorkflowBindings, yaml: String): WorkflowBindings {
        val refs = detect(yaml).map { it.ref }.toSet()
        val unknown = value.parameters.filterNot { it.ref() in refs }
        require(unknown.isEmpty()) { "The workflow has no time parameter ${unknown.joinToString { it.label() }}." }

        value.parameters.filter { it.mode == BindingMode.FIXED }.forEach {
            requireNotNull(it.value?.let(::instantOrNull)) { "${it.label()} is fixed but has no valid date-time." }
        }

        val start = value.windowStart?.let { requireNotNull(instantOrNull(it)) { "The window start is not a date-time." } }
        val end = value.windowEnd?.let { requireNotNull(instantOrNull(it)) { "The window end is not a date-time." } }
        require(start != null || !value.followsWindow()) { "A parameter follows the window, so the window needs a start." }
        require(start == null || end == null || start < end) { "The window must end after it starts." }

        BindingStore.put(value)
        return value
    }

    /** Whether any of [workflowId]'s parameters follow the window. */
    fun followsWindow(workflowId: String): Boolean = BindingStore.get(workflowId)?.followsWindow() == true

    /**
     * The window a run started by hand reads: from the window start to its end,
     * or to [now] when the end is open or later. Null when nothing follows the
     * window.
     */
    fun manualWindow(workflowId: String, now: Instant = Instant.now()): TimeWindow? {
        val saved = BindingStore.get(workflowId)?.takeIf { it.followsWindow() } ?: return null
        val start = saved.windowStart?.let(::instantOrNull) ?: return null
        val end = saved.windowEnd?.let(::instantOrNull)?.takeIf { it < now } ?: now

        return if (start < end) TimeWindow(start, end) else null
    }

    /**
     * Returns [yaml] with [workflowId]'s time parameters set for one run.
     *
     * Kept parameters are left alone; fixed ones get their value; window ones
     * get [window]'s start or end, each written in the format it was found in.
     *
     * @throws IllegalStateException when a parameter follows the window and
     *   [window] is null.
     */
    fun bind(workflowId: String, yaml: String, window: TimeWindow?): BoundWorkflow {
        val saved = BindingStore.get(workflowId) ?: return BoundWorkflow(yaml, emptyList())
        val detected = detect(yaml).associateBy { it.ref }

        val values = saved.parameters.mapNotNull { binding ->
            val parameter = detected[binding.ref()] ?: return@mapNotNull null
            val instant = when (binding.mode) {
                BindingMode.KEEP -> return@mapNotNull null
                BindingMode.FIXED -> binding.value?.let(::instantOrNull) ?: return@mapNotNull null
                BindingMode.WINDOW_START -> checkNotNull(window) { "No window for ${binding.label()}." }.from
                BindingMode.WINDOW_END -> checkNotNull(window) { "No window for ${binding.label()}." }.to
            }
            parameter.ref to parameter.format.format(KInstant.fromEpochMilliseconds(instant.toEpochMilli()))
        }.toMap()

        if (values.isEmpty()) return BoundWorkflow(yaml, emptyList())

        val bound = codec.encode(bindArguments(codec.decodeOrThrow(yaml), values))
        return BoundWorkflow(bound, values.map { (ref, value) -> BoundValue(ref.stepKey, ref.label, value) })
    }

    private fun WorkflowBindings.followsWindow(): Boolean =
        parameters.any { it.mode == BindingMode.WINDOW_START || it.mode == BindingMode.WINDOW_END }
}

internal fun instantOrNull(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

private fun ParameterBinding.ref() = ArgumentRef(stepKey, flag, name)

private fun ParameterBinding.label() = "'${ref().label}' on step '$stepKey'"

private fun TimeParameter.toDto() = TimeParameterDto(
    stepKey = ref.stepKey,
    flag = ref.flag,
    name = ref.name,
    label = ref.label,
    value = value,
    format = format.name,
)
