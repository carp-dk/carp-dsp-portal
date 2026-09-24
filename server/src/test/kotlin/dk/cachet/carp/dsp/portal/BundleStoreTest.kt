package dk.cachet.carp.dsp.portal

import dk.cachet.carp.dsp.portal.mock.BundleStore
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What a saved bundle keeps, so the study can run it later.
 *
 * [BundleStore.root] is process-wide, so each test gets its own directory and
 * puts the original back.
 */
class BundleStoreTest {

    private val original = BundleStore.root

    @BeforeTest
    fun useTemporaryRoot() {
        BundleStore.root = Files.createTempDirectory("bundles").toFile()
    }

    @AfterTest
    fun restore() {
        BundleStore.root.deleteRecursively()
        BundleStore.root = original
    }

    private val query = "SELECT 1;".toByteArray()

    @Test
    fun `files come back under the paths they were saved with`() {
        BundleStore.save("wf", mapOf("data/study-data.sql" to query))

        val loaded = BundleStore.load("wf")

        assertEquals(setOf("data/study-data.sql"), loaded.keys)
        assertContentEquals(query, loaded.getValue("data/study-data.sql"))
    }

    @Test
    fun `saving again replaces what was kept rather than adding to it`() {
        BundleStore.save("wf", mapOf("data/old.sql" to query))
        BundleStore.save("wf", mapOf("data/new.sql" to query))

        assertEquals(setOf("data/new.sql"), BundleStore.load("wf").keys)
    }

    @Test
    fun `a workflow saved without files keeps none`() {
        BundleStore.save("wf", mapOf("data/study-data.sql" to query))
        BundleStore.save("wf", emptyMap())

        assertTrue(BundleStore.load("wf").isEmpty())
    }

    @Test
    fun `a workflow never saved as a bundle has no files`() {
        assertTrue(BundleStore.load("never-saved").isEmpty())
    }

    @Test
    fun `removing forgets the files`() {
        BundleStore.save("wf", mapOf("data/study-data.sql" to query))
        BundleStore.remove("wf")

        assertTrue(BundleStore.load("wf").isEmpty())
    }

    @Test
    fun `an entry cannot be written outside its bundle`() {
        assertFailsWith<IllegalArgumentException> {
            BundleStore.save("wf", mapOf("../escaped.sql" to query))
        }
    }

    @Test
    fun `a workflow id cannot name a directory outside the store`() {
        assertFailsWith<IllegalArgumentException> {
            BundleStore.save("../elsewhere", mapOf("data/study-data.sql" to query))
        }
    }
}
