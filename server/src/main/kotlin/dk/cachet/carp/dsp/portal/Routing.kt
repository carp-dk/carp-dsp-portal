package dk.cachet.carp.dsp.portal

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import dk.cachet.carp.dsp.portal.api.analyticsRoutes
import dk.cachet.carp.dsp.portal.mock.MockStore
import dk.cachet.carp.dsp.portal.mock.RepoSource
import dk.cachet.carp.dsp.portal.mock.StateStore
import dk.cachet.carp.dsp.portal.mock.StepLibrary
import dk.cachet.carp.dsp.portal.mock.WorkflowLibrary
import dk.cachet.carp.dsp.portal.run.EnvironmentReuseSetting
import dk.cachet.carp.dsp.portal.run.Validation
import io.ktor.http.defaultForFilePath
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

private const val STATIC_ROOT = "static"
private const val SPA_INDEX = "$STATIC_ROOT/index.html"

/** Anchor for resolving resources against this module's own classloader. */
private object SpaProbe

private val loader = SpaProbe::class.java.classLoader

fun Application.configureRouting() {
    // Read once at startup. Null means the Vite build was not bundled.
    val indexHtml = loader.getResource(SPA_INDEX)?.readText()

    routing {
        get("/health") {
            call.respond(
                HealthResponse(
                    status = "UP",
                    service = "carp-dsp-portal",
                    version = System.getenv("DSP_VERSION") ?: "dev",
                    source = RepoSource.mode,
                    librarySteps = StepLibrary.list().size,
                    demoWorkflows = WorkflowLibrary.list().size,
                    studyWorkflows = MockStore.list().size,
                    stepLoadFailures = StepLibrary.loadFailures,
                    stateFile = StateStore.location(),
                    validator = Validation.mode,
                    environmentReuse = EnvironmentReuseSetting.mode,
                ),
            )
        }

        // One route per service, each taking a serialised *ServiceRequest.
        // See docs/api-contract.md.
        //
        // This block must stay ahead of the catch-all below, and the tail card
        // must stay last inside it, so a missing endpoint answers with JSON
        // rather than with a page of HTML.
        route("/api") {
            analyticsRoutes()

            get("{...}") {
                call.respond(
                    HttpStatusCode.NotFound,
                    ApiError(
                        kind = "NotFound",
                        message = "No such endpoint: ${call.request.local.uri}",
                    ),
                )
            }
        }

        // Static assets and the SPA fallback, handled together and explicitly.
        //
        // Neither staticResources nor singlePageApplication is used here. Both
        // register a catch-all that answers 404 for a path they cannot resolve.
        get("{...}") {
            val path = call.request.local.uri.substringBefore('?').trimStart('/')

            // A last segment with a dot is an asset request; anything else is a
            // client-side route. That is the whole rule, and it is why
            // /runs/123 gets the app while /assets/index-abc.js gets the file.
            val lastSegment = path.substringAfterLast('/')
            val isAssetRequest = lastSegment.contains('.') && !path.contains("..")

            val asset = if (isAssetRequest) loader.getResource("$STATIC_ROOT/$path") else null

            when {
                asset != null -> call.respondBytes(
                    bytes = asset.readBytes(),
                    contentType = ContentType.defaultForFilePath(path),
                )

                indexHtml != null -> call.respondText(
                    text = indexHtml,
                    contentType = ContentType.Text.Html,
                    status = HttpStatusCode.OK,
                )

                // Missing asset, and no bundle to fall back to.
                isAssetRequest -> call.respond(HttpStatusCode.NotFound)

                // No bundle at all. Say so rather than 404, which otherwise
                // reads as a routing bug.
                else -> call.respondText(
                    text = NO_SPA_PAGE,
                    contentType = ContentType.Text.Html,
                    status = HttpStatusCode.ServiceUnavailable,
                )
            }
        }
    }
}

private val NO_SPA_PAGE = """
    <!doctype html>
    <html lang="en">
      <head><meta charset="utf-8"><title>CARP DSP Portal</title></head>
      <body style="font-family: system-ui, sans-serif; margin: 4rem auto; max-width: 40rem;">
        <h1>Server is up, the web app is not bundled</h1>
        <p>No <code>static/index.html</code> on the classpath.</p>
        <p>Build it with <code>./gradlew :server:run</code>, or run the SPA
           separately with <code>pnpm dev</code> in <code>web/</code>.</p>
        <p><a href="/health">/health</a></p>
      </body>
    </html>
""".trimIndent()
