package dk.cachet.carp.dsp.portal.mock

import dk.cachet.carp.dsp.portal.api.DataSource
import dk.cachet.carp.dsp.portal.api.Schedule
import dk.cachet.carp.dsp.portal.api.WorkflowBindings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persists what the user made, so a restart does not wipe the session.
 *
 * Location: `DSP_STATE`, or `./data/portal-state.json`. Missing or unreadable
 * is not an error; the portal starts empty and says so.
 */
object StateStore {

    private val log = LoggerFactory.getLogger(StateStore::class.java)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val file: File = File(
        System.getenv("DSP_STATE") ?: "data/portal-state.json",
    )

    /** Suppresses saves while restoring, or every restored item rewrites the file. */
    @Volatile
    private var restoring = false

    private val lock = Any()

    @Serializable
    data class StoredWorkflow(val yaml: String, val draft: Boolean = false)

    @Serializable
    data class PortalState(
        val workflows: List<StoredWorkflow> = emptyList(),
        val runs: List<RunSimulator.RunRecord> = emptyList(),
        val schedules: List<Schedule> = emptyList(),
        /** Raw StudyProtocolSnapshot JSON, re-parsed on load. */
        val protocols: List<String> = emptyList(),
        val activeProtocol: String? = null,
        val dataFiles: List<DataSource> = emptyList(),
        val externalDatasets: List<DataSource> = emptyList(),
        /** Raw step.yaml text, re-parsed on load. */
        val librarySteps: List<String> = emptyList(),
        /** How each workflow's time parameters are set. */
        val timeBindings: List<WorkflowBindings> = emptyList(),
    )

    /** Reads state and hands each slice back to its store. */
    fun restore() {
        if (!file.isFile) {
            log.info("No saved state at ${file.absolutePath} - starting empty.")
            return
        }

        val state = try {
            json.decodeFromString(PortalState.serializer(), file.readText())
        } catch (e: Exception) {
            // A state file from an older shape should not stop the portal.
            log.warn("Could not read ${file.absolutePath}, starting empty: ${e.message}")
            return
        }

        restoring = true
        try {
            state.librarySteps.forEach { runCatching { StepLibrary.add(it) } }
            state.protocols.forEach { runCatching { ProtocolStore.add(it) } }
            state.activeProtocol?.let { key ->
                val id = key.substringBeforeLast("@v")
                val version = key.substringAfterLast("@v").toIntOrNull()
                if (version != null) ProtocolStore.activate(id, version)
            }
            state.workflows.forEach { stored ->
                runCatching {
                    MockStore.put(
                        MockStore.parse(
                            stored.yaml,
                            validate = !stored.draft,
                            draft = stored.draft,
                        ),
                    )
                }
            }
            state.dataFiles.forEach { DataCatalogue.restoreFile(it) }
            state.externalDatasets.forEach { DataCatalogue.restoreExternal(it) }
            RunSimulator.restore(state.runs)
        } finally {
            restoring = false
        }

        log.info(
            "Restored from ${file.absolutePath}: " +
                "${state.workflows.size} workflows, ${state.runs.size} runs, " +
                "${state.schedules.size} schedules, ${state.librarySteps.size} steps",
        )

        state.schedules.forEach { ScheduleStore.restore(it) }
        state.timeBindings.forEach { BindingStore.restore(it) }
    }

    /**
     * Writes the current state.
     *
     * Via a temp file and an atomic move, so a crash mid-write cannot leave a
     * truncated file that fails to load next boot.
     */
    fun save() {
        if (restoring) return

        synchronized(lock) {
            val state = PortalState(
                workflows = MockStore.snapshot(),
                runs = RunSimulator.snapshot(),
                schedules = ScheduleStore.snapshot(),
                protocols = ProtocolStore.uploadedSnapshot(),
                activeProtocol = ProtocolStore.activeProtocolKey(),
                dataFiles = DataCatalogue.fileSnapshot(),
                externalDatasets = DataCatalogue.externalSnapshot(),
                librarySteps = StepLibrary.uploadedSnapshot(),
                timeBindings = BindingStore.snapshot(),
            )

            try {
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile ?: File("."), "${file.name}.tmp")
                // Serializer passed explicitly: the reified overload loses out
                // to encodeToString(strategy, value) here and fails to infer.
                temp.writeText(json.encodeToString(PortalState.serializer(), state))
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: Exception) {
                // Losing a save is bad; taking the request down with it is worse.
                log.warn("Could not save state to ${file.absolutePath}: ${e.message}")
            }
        }
    }

    /** Where state is being written, for /health. */
    fun location(): String = file.absolutePath
}
