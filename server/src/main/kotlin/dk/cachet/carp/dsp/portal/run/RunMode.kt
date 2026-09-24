package dk.cachet.carp.dsp.portal.run

/**
 * Whether the portal runs a workflow for real or replays the simulation.
 *
 * A live demo needs a way back to the scripted path when a machine, a network,
 * or a pixi solve lets it down, so this is a switch rather than a build flag.
 *
 * Portal-only. The analytics RPC surface has no notion of a mode, so this goes
 * when the mock shell does.
 */
object RunMode {

    enum class Mode { REAL, SIMULATED }

    /** `DSP_MODE=simulated` starts a demo machine on the safe path. */
    @Volatile
    var current: Mode = System.getenv("DSP_MODE")
        ?.let { name -> Mode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        ?: Mode.REAL

    val isReal: Boolean get() = current == Mode.REAL

    /** Returns null for a name that is not a mode, so a bad request stays a 400. */
    fun parse(name: String): Mode? =
        Mode.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
}
