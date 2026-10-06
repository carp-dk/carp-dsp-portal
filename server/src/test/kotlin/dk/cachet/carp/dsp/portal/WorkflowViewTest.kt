package dk.cachet.carp.dsp.portal

import dk.cachet.carp.dsp.portal.api.PortSource
import dk.cachet.carp.dsp.portal.mock.MockStore
import dk.cachet.carp.dsp.portal.mock.WorkflowParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** What the UI is shown of a workflow, read through core's codec. */
class WorkflowViewTest {

    private val yaml = """
        schemaVersion: "1.0"
        metadata:
          name: "Mixed"
        environments:
          env-py:
            name: "Python"
            kind: "pixi"
            spec:
              pythonVersion: ["3.11"]
              dependencies: ["pandas"]
              channels: ["conda-forge"]
        steps:
          - id: "query"
            uses: "core.io.query-sql"
            args: ["--from", "2026-10-11T00:00:00Z"]
          - id: "clean"
            dependsOn: ["query"]
            environmentId: "env-py"
            task:
              type: "python"
              name: "clean"
              entryPoint:
                type: "module"
                moduleName: "carp.clean"
              args: ["--limit", "5"]
            inputs:
              - id: "rows"
                source:
                  type: "step-output"
                  stepId: "query"
                  outputId: "rows"
              - id: "token"
                source:
                  type: "env-var"
                  variableName: "API_TOKEN"
          - uses: "core.viz.visualise"
    """.trimIndent()

    private val view = MockStore.parse(yaml, fallbackId = "from-filename").definition

    @Test
    fun `a workflow without an id takes the fallback`() {
        assertEquals("from-filename", view.metadata.id)
    }

    @Test
    fun `a referenced step keeps its library id and args`() {
        val query = view.steps[0]

        assertEquals("core.io.query-sql", query.uses)
        assertEquals(listOf("--from", "2026-10-11T00:00:00Z"), query.args)
        assertNull(query.task)
    }

    @Test
    fun `a defined step's task and sources are flattened for the UI`() {
        val clean = view.steps[1]

        assertEquals("python", clean.task?.type)
        assertEquals("carp.clean", clean.task?.entryPoint?.module)
        assertEquals(listOf("--limit", "5"), clean.task?.args)
        assertEquals(PortSource(type = "step-output", stepId = "query", outputId = "rows"), clean.inputs[0].source)
        assertEquals("API_TOKEN", clean.inputs[1].source?.variableName)
    }

    @Test
    fun `environment channels are kept`() {
        assertEquals(listOf("conda-forge"), view.environments.getValue("env-py").spec.channels)
    }

    @Test
    fun `a step without an id is shown by its position`() {
        assertEquals("#2", view.steps[2].id)
    }

    @Test
    fun `a file that is not a workflow is refused with the codec's message`() {
        assertFailsWith<WorkflowParseException> { MockStore.parse("steps: [", fallbackId = "x") }
    }
}
