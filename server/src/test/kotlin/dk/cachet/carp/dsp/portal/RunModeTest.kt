package dk.cachet.carp.dsp.portal

import dk.cachet.carp.dsp.portal.api.RunModeDto
import dk.cachet.carp.dsp.portal.run.RunMode
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The demo's fallback switch.
 *
 * [RunMode] is process-wide state, so each test puts it back - a leaked
 * `simulated` would quietly turn off every other test's real path.
 */
class RunModeTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val initial = RunMode.current

    @AfterTest
    fun restore() {
        RunMode.current = initial
    }

    @Test
    fun `mode is readable and settable`() = testApplication {
        application { module() }

        val set = client.post("/api/mode") {
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"simulated"}""")
        }
        assertEquals(HttpStatusCode.OK, set.status)
        assertEquals("simulated", json.decodeFromString<RunModeDto>(set.bodyAsText()).mode)

        val read = client.get("/api/mode")
        assertEquals("simulated", json.decodeFromString<RunModeDto>(read.bodyAsText()).mode)
        assertEquals(RunMode.Mode.SIMULATED, RunMode.current)
    }

    @Test
    fun `an unknown mode is rejected and changes nothing`() = testApplication {
        application { module() }

        val response = client.post("/api/mode") {
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"pretend"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("UnknownMode", json.decodeFromString<ApiError>(response.bodyAsText()).kind)
        assertEquals(initial, RunMode.current)
    }
}
