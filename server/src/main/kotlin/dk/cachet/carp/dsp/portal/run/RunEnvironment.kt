package dk.cachet.carp.dsp.portal.run

import dk.cachet.carp.dsp.portal.api.ConnectionCheck
import dk.cachet.carp.dsp.portal.api.RunVariable
import dk.cachet.carp.dsp.portal.api.VariableSource
import dk.cachet.carp.dsp.portal.store.StateStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Environment variables handed to every step a real run starts.
 *
 * A step inherits the server's environment, which is fixed when the container
 * starts. Values saved here are laid over it, so connection details can be
 * fixed in a deployed portal without a rebuild or a restart.
 *
 * They live in `run-environment.properties` beside the state file, not in it,
 * so secrets stay out of a file that gets copied around. A secret is never sent
 * back to the browser.
 *
 * Only steps see these. Server settings (`DSP_*`) are still read once, at
 * startup, from the container's environment.
 */
object RunEnvironment {

    const val SQL_URL = "CARP_DSP_SQL_URL"
    const val SQL_USER = "CARP_DSP_SQL_USER"
    const val SQL_PASSWORD = "CARP_DSP_SQL_PASSWORD"

    /** Always listed, set or not: what core.io.query-sql reads. */
    private val KNOWN = listOf(SQL_URL, SQL_USER, SQL_PASSWORD)

    private val NAME = Regex("^[A-Z_][A-Z0-9_]*$")
    private val SECRET_HINTS = listOf("PASSWORD", "SECRET", "TOKEN", "KEY")

    private const val CHECK_TIMEOUT_SECONDS = 5

    private val log = LoggerFactory.getLogger(RunEnvironment::class.java)

    private val file: File = File(File(StateStore.location()).parentFile, "run-environment.properties")

    private val saved = ConcurrentHashMap(load())

    /** The saved values, for a run to lay over the inherited environment. */
    fun overrides(): Map<String, String> = saved.toMap()

    fun isSecret(name: String): Boolean = SECRET_HINTS.any { it in name }

    fun view(): List<RunVariable> = (KNOWN + saved.keys.sorted()).distinct().map { name ->
        val value = effective(name)
        val secret = isSecret(name)
        RunVariable(
            name = name,
            value = if (secret) null else value,
            set = value != null,
            source = when {
                saved.containsKey(name) -> VariableSource.SAVED
                System.getenv(name) != null -> VariableSource.CONTAINER
                else -> VariableSource.UNSET
            },
            secret = secret,
        )
    }

    /** Saves [value] for [name]. Null or empty drops the saved value, so the container's shows again. */
    @Synchronized
    fun set(name: String, value: String?) {
        require(NAME.matches(name)) { "'$name' is not a variable name. Use A-Z, 0-9 and _." }

        if (value.isNullOrEmpty()) saved.remove(name) else saved[name] = value
        write()
    }

    /** Opens a connection with the values a run would get, and runs `SELECT 1`. */
    fun checkSql(): ConnectionCheck {
        val url = effective(SQL_URL) ?: return ConnectionCheck(false, "$SQL_URL is not set.")

        return try {
            DriverManager.setLoginTimeout(CHECK_TIMEOUT_SECONDS)
            DriverManager.getConnection(url, effective(SQL_USER), effective(SQL_PASSWORD)).use { connection ->
                connection.createStatement().use { statement ->
                    statement.queryTimeout = CHECK_TIMEOUT_SECONDS
                    statement.execute("SELECT 1")
                }
                val meta = connection.metaData
                ConnectionCheck(true, "Connected to ${meta.databaseProductName} ${meta.databaseProductVersion}.")
            }
        } catch (e: Exception) {
            ConnectionCheck(false, e.message ?: e::class.simpleName ?: "Could not connect.")
        }
    }

    private fun effective(name: String): String? = saved[name] ?: System.getenv(name)

    private fun load(): Map<String, String> {
        if (!file.isFile) return emptyMap()

        return try {
            val properties = Properties()
            file.inputStream().use { properties.load(it) }
            properties.stringPropertyNames()
                .filter { NAME.matches(it) }
                .associateWith { properties.getProperty(it) }
        } catch (e: Exception) {
            log.warn("Could not read ${file.absolutePath}: ${e.message}")
            emptyMap()
        }
    }

    /** Via a temp file and an atomic move, readable by the owner only where the file system allows it. */
    private fun write() {
        val properties = Properties().apply { putAll(saved) }

        file.parentFile?.mkdirs()
        val temp = File(file.parentFile ?: File("."), "${file.name}.tmp")
        temp.outputStream().use { properties.store(it, "Set from the portal's Settings page") }
        runCatching { Files.setPosixFilePermissions(temp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
