package dk.cachet.carp.dsp.portal.api

import dk.cachet.carp.dsp.portal.ApiError
import dk.cachet.carp.dsp.portal.run.RunEnvironment
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The variables steps receive, editable in a running portal.
 *
 * Open to anyone who can reach the portal, by choice. Anyone who can save a url
 * can also point the saved password at a host of their own.
 */
fun Route.settingsRoutes() {
    get("/run-environment") {
        call.respond(RunEnvironment.view())
    }

    put("/run-environment") {
        val request = call.receive<SetRunVariable>()
        try {
            RunEnvironment.set(request.name, request.value)
            call.respond(RunEnvironment.view())
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiError("InvalidVariable", e.message.orEmpty()))
        }
    }

    post("/run-environment/test-sql") {
        call.respond(withContext(Dispatchers.IO) { RunEnvironment.checkSql() })
    }
}
