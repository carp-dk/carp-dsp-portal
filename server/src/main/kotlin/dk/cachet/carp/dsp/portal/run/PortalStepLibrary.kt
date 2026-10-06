package dk.cachet.carp.dsp.portal.run

import carp.dsp.core.application.authoring.resolve.LibraryStep
import carp.dsp.core.application.authoring.resolve.StepLibrary
import carp.dsp.steps.ClasspathStepLibrary
import dk.cachet.carp.dsp.portal.catalogue.StepLibrary as Catalogue

/**
 * The step library runs and validation resolve against: the vendored library,
 * then steps added through the portal.
 *
 * Vendored steps are looked up first, so a portal-added step can never stand
 * in for one; adding one under a vendored id is refused anyway.
 */
object PortalStepLibrary : StepLibrary {

    private val classpath = ClasspathStepLibrary()

    override fun lookup(id: String, version: String?): LibraryStep? =
        classpath.lookup(id, version) ?: Catalogue.uploadedStep(id, version)
}
