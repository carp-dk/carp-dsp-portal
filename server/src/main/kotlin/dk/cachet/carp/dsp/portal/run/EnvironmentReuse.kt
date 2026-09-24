package dk.cachet.carp.dsp.portal.run

import carp.dsp.core.infrastructure.execution.handlers.ReusePolicy as EngineReuse
import carp.dsp.core.infrastructure.execution.handlers.EnvironmentStore

/**
 * How far the engine may stretch to avoid building an environment.
 *
 * Two settings, and the default is the strict one:
 *
 * - `exact` - reuse an environment whose spec is digest-identical, otherwise
 *   build. This is what Snakemake does, and it is the setting a result should be
 *   produced under.
 * - `superset` - an existing environment that contains everything the workflow
 *   asked for may serve it.
 *
 * Superset reuse is opt-in because it is not free. A superset of *declared
 * names* is not a superset of the *installed closure*: asking for one more
 * package can move the resolved versions of packages that were already there,
 * and a version difference is not cosmetic. It also hides a missing declaration
 * - a step importing a package it never declared succeeds, and the workflow
 * quietly stops being portable.
 *
 * When it is on, every run records what it actually got: the provisioning row
 * carries the match and the extra packages.
 *
 * The engine holds the setting, so this is a view onto it rather than a second
 * copy. `DSP_ENV_REUSE=superset` sets the starting value.
 */
object EnvironmentReuseSetting {

    val mode: String get() = if (EnvironmentStore.reuse == EngineReuse.ALLOW_SUPERSET) "superset" else "exact"

    /** Returns null for a name that is not a setting, so a bad request stays a 400. */
    fun parse(name: String): EngineReuse? = when (name.trim().lowercase()) {
        "exact" -> EngineReuse.EXACT
        "superset" -> EngineReuse.ALLOW_SUPERSET
        else -> null
    }

    fun set(value: EngineReuse) {
        EnvironmentStore.reuse = value
    }
}
