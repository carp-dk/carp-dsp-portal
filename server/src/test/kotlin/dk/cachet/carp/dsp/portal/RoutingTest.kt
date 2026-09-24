package dk.cachet.carp.dsp.portal

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `health reports UP`() = testApplication {
        application { module() }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<HealthResponse>(response.bodyAsText())
        assertEquals("UP", body.status)
        assertEquals("carp-dsp-portal", body.service)
    }

    @Test
    fun `unknown api route returns json not html`() = testApplication {
        application { module() }

        val response = client.get("/api/v0/does-not-exist")

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("NotFound", body.kind)
    }

    @Test
    fun `deep link falls back to the spa rather than 404`() = testApplication {
        application { module() }

        val response = client.get("/runs/123")

        // 200 once the SPA is bundled, 503 with an explanatory page when it is
        // not. Either way a client-side route must never come back as a 404.
        assertTrue(
            response.status == HttpStatusCode.OK ||
                response.status == HttpStatusCode.ServiceUnavailable,
            "expected SPA fallback, got ${response.status}",
        )
    }
}
