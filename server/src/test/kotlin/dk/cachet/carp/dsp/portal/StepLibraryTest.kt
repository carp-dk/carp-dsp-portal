package dk.cachet.carp.dsp.portal

import dk.cachet.carp.dsp.portal.catalogue.StepLibrary
import dk.cachet.carp.dsp.portal.store.WorkflowParseException
import dk.cachet.carp.dsp.portal.run.PortalStepLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Steps added through the portal: listed, gated, and resolvable by the engine. */
class StepLibraryTest {

    private fun stepYaml(id: String, version: String = "1.0") = """
        schemaVersion: "1.0"
        metadata:
          id: "$id"
          name: "Added"
          version: "$version"
        environments:
          env-system:
            name: "System"
            kind: "system"
        steps:
          - id: "added"
            environmentId: "env-system"
            task:
              type: "command"
              name: "added"
              executable: "echo"
              args: ["hello"]
    """.trimIndent()

    @Test
    fun `an added step is listed as gated`() {
        StepLibrary.add(stepYaml("test.added.listed"))

        val entry = assertNotNull(StepLibrary.get("test.added.listed"))
        assertEquals("gated", entry.certification?.level)
        assertEquals("echo", entry.definition.steps.single().task?.executable)
    }

    @Test
    fun `the engine's library resolves an added step, at its version only`() {
        StepLibrary.add(stepYaml("test.added.resolved", version = "2.0"))

        assertEquals("2.0", PortalStepLibrary.lookup("test.added.resolved", null)?.version)
        assertNotNull(PortalStepLibrary.lookup("test.added.resolved", "2.0"))
        assertNull(PortalStepLibrary.lookup("test.added.resolved", "1.0"))
    }

    @Test
    fun `a step file without its own task is refused`() {
        val reference = """
            metadata:
              id: "test.added.reference"
              name: "Reference"
            steps:
              - id: "ref"
                uses: "core.stats.summarise"
        """.trimIndent()

        assertFailsWith<WorkflowParseException> { StepLibrary.add(reference) }
    }
}
