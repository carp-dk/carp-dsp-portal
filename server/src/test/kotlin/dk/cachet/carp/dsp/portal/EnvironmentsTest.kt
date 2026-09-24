package dk.cachet.carp.dsp.portal

import carp.dsp.core.infrastructure.execution.handlers.EnvironmentStore
import dk.cachet.carp.analytics.application.plan.PixiEnvironmentRef
import dk.cachet.carp.dsp.portal.api.EnvironmentDto
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Environments page's endpoint, over a store the test controls.
 *
 * [EnvironmentStore.root] is process-wide, so each test gets its own directory
 * and puts the original back.
 */
class EnvironmentsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val initialRoot = EnvironmentStore.root

    @BeforeTest
    fun useTemporaryRoot() {
        EnvironmentStore.root = Files.createTempDirectory("envs")
    }

    @AfterTest
    fun restore() {
        EnvironmentStore.root.toFile().deleteRecursively()
        EnvironmentStore.root = initialRoot
    }

    private val ref = PixiEnvironmentRef(
        id = "env-1",
        name = "carp-task-runtime",
        dependencies = listOf("openjdk=17.*"),
        channels = listOf("conda-forge"),
        pythonVersion = "3.11",
    )

    @Test
    fun `a solved environment is listed with its definition and size`() = testApplication {
        application { module() }

        val dir = EnvironmentStore.resolve(ref)!!.directory
        EnvironmentStore.writeManifest(dir, ref)
        dir.resolve("payload.bin").writeBytes(ByteArray(1024))

        val response = client.get("/api/environments")
        assertEquals(HttpStatusCode.OK, response.status)

        val row = json.decodeFromString<List<EnvironmentDto>>(response.bodyAsText()).single()
        assertEquals("carp-task-runtime", row.name)
        assertEquals("pixi", row.kind)
        assertEquals("solved", row.status)
        assertEquals("Python 3.11", row.runtime)
        assertEquals(listOf("openjdk=17.*"), row.dependencies)
        assertTrue((row.sizeBytes ?: 0) >= 1024, "${row.sizeBytes}")
    }

    @Test
    fun `a build that never finished is listed as incomplete, named from its directory`() = testApplication {
        application { module() }

        EnvironmentStore.root.resolve("pixi").resolve("python-data-0123456789abcdef").createDirectories()

        val row = json.decodeFromString<List<EnvironmentDto>>(client.get("/api/environments").bodyAsText()).single()
        assertEquals("python-data", row.name)
        assertEquals("incomplete", row.status)
        assertNull(row.builtAt)
    }

    @Test
    fun `an empty store lists nothing`() = testApplication {
        application { module() }

        assertEquals("[]", client.get("/api/environments").bodyAsText().trim())
    }
}
