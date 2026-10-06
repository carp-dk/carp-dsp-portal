package dk.cachet.carp.dsp.portal

import dk.cachet.carp.dsp.portal.api.PortSource
import dk.cachet.carp.dsp.portal.api.PortSpec
import dk.cachet.carp.dsp.portal.api.StepSpec
import dk.cachet.carp.dsp.portal.run.BundleChecks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A `file` input the bundle itself carries. */
class BundleChecksTest {

    private val step = StepSpec(
        id = "findings",
        inputs = listOf(PortSpec(id = "expected", source = PortSource(type = "file", path = "data/expected.csv"))),
    )

    @Test
    fun a_file_the_bundle_carries_is_available() {
        val findings = BundleChecks.boundaryFindings(listOf(step), bundled = setOf("data/expected.csv"))

        assertTrue(findings.none { it.code == "DATA_NOT_AVAILABLE" }, findings.toString())
    }

    @Test
    fun a_file_neither_the_bundle_nor_the_study_has_is_flagged() {
        val findings = BundleChecks.boundaryFindings(listOf(step))

        assertEquals(listOf("DATA_NOT_AVAILABLE"), findings.map { it.code })
    }

    @Test
    fun a_file_input_counts_as_a_reference() {
        assertEquals(listOf("data/expected.csv"), BundleChecks.fileInputsOf(step))
    }
}
