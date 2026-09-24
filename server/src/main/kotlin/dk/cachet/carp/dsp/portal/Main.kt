package dk.cachet.carp.dsp.portal

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

/** Default port. Override with the DSP_PORT environment variable. */
private const val DEFAULT_PORT = 8080

fun main() {
    val port = System.getenv("DSP_PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val host = System.getenv("DSP_HOST") ?: "0.0.0.0"

    embeddedServer(CIO, port = port, host = host) { module() }
        .start(wait = true)
}
