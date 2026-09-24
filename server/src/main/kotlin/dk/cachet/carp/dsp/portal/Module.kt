package dk.cachet.carp.dsp.portal

import dk.cachet.carp.dsp.portal.mock.MockStore
import dk.cachet.carp.dsp.portal.mock.RepoSource
import dk.cachet.carp.dsp.portal.mock.RunSimulator
import dk.cachet.carp.dsp.portal.mock.StateStore
import dk.cachet.carp.dsp.portal.mock.StepLibrary
import dk.cachet.carp.dsp.portal.mock.WorkflowLibrary
import dk.cachet.carp.dsp.portal.run.Scheduler
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

/**
 * Wires the server. Kept separate from [main] so tests can start the same
 * configuration with `testApplication`.
 */
fun Application.module() {
    install(DefaultHeaders)

    install(CallLogging) {
        level = Level.INFO
        // Static asset requests drown out anything useful.
        filter { call -> call.request.local.uri.startsWith("/api") }
    }

    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                encodeDefaults = true
                // Core's convention.
                classDiscriminator = "__type"
            },
        )
    }

    // Dev only: lets `pnpm dev` on :3000 call this server on :8080.
    // Not needed once the SPA is served from this same origin.
    install(CORS) {
        allowHost("localhost:3000")
        allowHost("127.0.0.1:3000")
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError(kind = "InternalError", message = cause.message ?: "Unknown error"),
            )
        }
    }

    // Saved session first, so seeding does not overwrite restored work.
    StateStore.restore()

    // The study starts with the one demo that has a recorded run behind it, so
    // the Runs page is never empty and real results are viewable without
    // waiting. Both seeds are no-ops when state was restored.
    MockStore.seedFromLibrary()
    RunSimulator.seedRecordedRuns()

    logLoadedContent()

    // After restore, so the first tick sees the saved schedules and bindings.
    Scheduler.start()

    configureRouting()
}

/**
 * Says what actually loaded, at startup, in the container log.
 *
 * Without this, "the library is missing things" can only be investigated by
 * guessing at it from the outside - which is slower than just printing the
 * counts and the reason anything was skipped.
 */
private fun Application.logLoadedContent() {
    val log = environment.log
    val steps = StepLibrary.list()
    val demos = WorkflowLibrary.list()

    log.info("Content source: ${RepoSource.mode}")
    log.info("Library steps: ${steps.size} - ${steps.joinToString { it.stepId }}")
    log.info("Demo workflows: ${demos.size} (${demos.count { it.valid }} valid)")
    log.info("Study workflows: ${MockStore.list().size}")
    log.info("State file: ${StateStore.location()}")

    if (StepLibrary.loadFailures.isNotEmpty()) {
        log.warn("Step files skipped: ${StepLibrary.loadFailures.size}")
        StepLibrary.loadFailures.forEach { (dir, why) -> log.warn("  $dir: $why") }
    }

    demos.filterNot { it.valid }.forEach { log.info("Demo invalid: ${it.path} - ${it.problem}") }
}
