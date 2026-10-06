package dk.cachet.carp.dsp.portal

import dk.cachet.carp.dsp.portal.store.ProtocolStore
import dk.cachet.carp.dsp.portal.store.WorkflowParseException
import dk.cachet.carp.dsp.portal.store.toDto
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Protocols read as core's snapshot, from carp-dsp's own fixtures. */
class ProtocolStoreTest {

    private val id = "aabbccdd-0000-4000-8000-000000000001"

    @Test
    fun `a protocol collects the data types its measures name`() {
        val hr = ProtocolStore.add(fixture("hr-study-protocol.json"))

        assertEquals(listOf("dk.cachet.carp.heartrate", "dk.cachet.carp.stepcount"), hr.toDto(active = true).collectedDataTypes)
        assertEquals(listOf("Participant phone"), hr.toDto(active = true).deviceRoles)
    }

    @Test
    fun `a version selects that version, and an unknown one is not held`() {
        ProtocolStore.add(fixture("hr-study-protocol.json"))
        ProtocolStore.add(fixture("steps-only-protocol.json"))

        assertEquals(listOf("dk.cachet.carp.stepcount"), ProtocolStore.collectedDataTypes(id, 2))
        assertEquals(listOf("dk.cachet.carp.stepcount"), ProtocolStore.collectedDataTypes(id))
        assertNull(ProtocolStore.collectedDataTypes(id, 9))
    }

    @Test
    fun `something that is not a snapshot is refused`() {
        assertFailsWith<WorkflowParseException> { ProtocolStore.add("""{ "name": "half a protocol" }""") }
    }

    private fun fixture(name: String): String {
        val repo = listOfNotNull(System.getenv("DSP_REPO"), "../carp-dsp", "../../carp-dsp")
            .map { File(it) }
            .firstOrNull { File(it, PROTOCOLS).isDirectory }
            ?: error("No carp-dsp checkout beside this repo. Set DSP_REPO to one.")

        return File(File(repo, PROTOCOLS), name).readText()
    }

    private companion object {
        const val PROTOCOLS = "carp.dsp.demo/src/jvmMain/resources/protocols"
    }
}
