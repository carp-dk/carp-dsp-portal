package dk.cachet.carp.dsp.portal.mock

import dk.cachet.carp.dsp.portal.api.WorkflowBindings
import java.util.concurrent.ConcurrentHashMap

/**
 * How each workflow's time parameters are set, as the user chose.
 *
 * Only the choices are kept here. Checking them against a workflow and
 * applying them to a run is run/TimeBindings.kt.
 */
object BindingStore {

    private val bindings = ConcurrentHashMap<String, WorkflowBindings>()

    fun get(workflowId: String): WorkflowBindings? = bindings[workflowId]

    fun put(value: WorkflowBindings) {
        bindings[value.workflowId] = value
        StateStore.save()
    }

    fun remove(workflowId: String) {
        if (bindings.remove(workflowId) != null) StateStore.save()
    }

    fun snapshot(): List<WorkflowBindings> = bindings.values.toList()

    /** Puts saved bindings back without rewriting the state file. */
    fun restore(value: WorkflowBindings) {
        bindings[value.workflowId] = value
    }
}
