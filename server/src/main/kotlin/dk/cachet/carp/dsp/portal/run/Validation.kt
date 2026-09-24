package dk.cachet.carp.dsp.portal.run

import dk.cachet.carp.dsp.portal.api.ValidationReport
import dk.cachet.carp.dsp.portal.mock.BundleValidator

/**
 * Which validator answers.
 *
 * The engine path is the real one and the default. The mock stays reachable with
 * `DSP_VALIDATOR=mock` until it has been retired for a while without anyone
 * needing it - at which point [BundleValidator] and this switch both go.
 */
object Validation {

    private val useEngine: Boolean =
        System.getenv("DSP_VALIDATOR")?.equals("mock", ignoreCase = true) != true

    /** Named in /health, so which one is answering is never a guess. */
    val mode: String = if (useEngine) "engine" else "mock"

    fun validate(paths: Set<String>, yamlText: String?): ValidationReport =
        if (useEngine) EngineValidator.validate(paths, yamlText) else BundleValidator.validate(paths, yamlText)

    /**
     * A workflow on its own, with no bundle around it.
     *
     * The demo catalogue reads scripts from carp-dsp rather than from an upload,
     * so reporting every one of them as missing would be noise, not a finding.
     */
    fun validateDefinition(yamlText: String): ValidationReport =
        if (useEngine) EngineValidator.validateDefinition(yamlText) else BundleValidator.validateDefinition(yamlText)
}
