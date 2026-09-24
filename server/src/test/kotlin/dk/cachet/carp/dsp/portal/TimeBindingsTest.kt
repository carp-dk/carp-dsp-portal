package dk.cachet.carp.dsp.portal

import dk.cachet.carp.dsp.portal.api.BindingMode
import dk.cachet.carp.dsp.portal.api.ParameterBinding
import dk.cachet.carp.dsp.portal.api.WorkflowBindings
import dk.cachet.carp.dsp.portal.mock.BindingStore
import dk.cachet.carp.dsp.portal.run.TimeBindings
import dk.cachet.carp.dsp.portal.run.TimeWindow
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Setting a workflow's time parameters for a run, without touching the workflow. */
class TimeBindingsTest {

    private val workflowId = "wf-time-bindings-test"

    private val yaml = """
        schemaVersion: "1.0"
        metadata:
          id: "$workflowId"
          name: "Windowed"
        steps:
          - id: "query"
            uses: "core.io.query-sql"
            args:
              - "--param"
              - "studyId=abc"
              - "--from"
              - "2026-10-01T00:00:00Z"
              - "--to"
              - "2026-10-02"
    """.trimIndent()

    private fun binding(flag: String, mode: BindingMode, value: String? = null) =
        ParameterBinding(stepKey = "query", flag = flag, mode = mode, value = value)

    private fun windowed(start: String? = "2026-10-12T00:00:00Z", end: String? = null) = WorkflowBindings(
        workflowId = workflowId,
        windowStart = start,
        windowEnd = end,
        parameters = listOf(binding("--from", BindingMode.WINDOW_START), binding("--to", BindingMode.WINDOW_END)),
    )

    private val window = TimeWindow(Instant.parse("2026-10-12T10:00:00Z"), Instant.parse("2026-10-12T10:15:00Z"))

    @AfterTest
    fun forget() = BindingStore.remove(workflowId)

    @Test
    fun `both window flags are found, and nothing is bound until chosen`() {
        val view = TimeBindings.view(workflowId, yaml)

        assertEquals(listOf("--from", "--to"), view.detected.map { it.label })
        assertNull(view.bindings)
    }

    @Test
    fun `a window run gets each end in the format the file used`() {
        TimeBindings.save(windowed(), yaml)

        val bound = TimeBindings.bind(workflowId, yaml, window)

        assertEquals(
            listOf("--from" to "2026-10-12T10:00:00Z", "--to" to "2026-10-12"),
            bound.values.map { it.key to it.value },
        )
        assertTrue(bound.yaml.contains("2026-10-12T10:00:00Z"))
        assertTrue(bound.yaml.contains("studyId=abc"))
    }

    @Test
    fun `kept parameters are left as written`() {
        TimeBindings.save(
            WorkflowBindings(workflowId, parameters = listOf(binding("--from", BindingMode.KEEP))),
            yaml,
        )

        val bound = TimeBindings.bind(workflowId, yaml, null)

        assertEquals(yaml, bound.yaml)
        assertTrue(bound.values.isEmpty())
    }

    @Test
    fun `a fixed parameter gets its value`() {
        TimeBindings.save(
            WorkflowBindings(
                workflowId,
                parameters = listOf(binding("--from", BindingMode.FIXED, "2026-09-01T00:00:00Z")),
            ),
            yaml,
        )

        assertEquals("2026-09-01T00:00:00Z", TimeBindings.bind(workflowId, yaml, null).values.single().value)
    }

    @Test
    fun `following the window needs a window start`() {
        assertFailsWith<IllegalArgumentException> { TimeBindings.save(windowed(start = null), yaml) }
    }

    @Test
    fun `a binding for an argument the workflow lacks is refused`() {
        val stray = WorkflowBindings(workflowId, parameters = listOf(binding("--since", BindingMode.KEEP)))

        assertFailsWith<IllegalArgumentException> { TimeBindings.save(stray, yaml) }
    }

    @Test
    fun `a run by hand reads from the window start up to now, or to the end if it has passed`() {
        TimeBindings.save(windowed(end = "2026-10-13T00:00:00Z"), yaml)

        val before = TimeBindings.manualWindow(workflowId, now = Instant.parse("2026-10-12T12:00:00Z"))
        val after = TimeBindings.manualWindow(workflowId, now = Instant.parse("2026-10-20T00:00:00Z"))

        assertEquals(Instant.parse("2026-10-12T12:00:00Z"), before?.to)
        assertEquals(Instant.parse("2026-10-13T00:00:00Z"), after?.to)
        assertNull(TimeBindings.manualWindow(workflowId, now = Instant.parse("2026-10-11T00:00:00Z")))
    }
}
