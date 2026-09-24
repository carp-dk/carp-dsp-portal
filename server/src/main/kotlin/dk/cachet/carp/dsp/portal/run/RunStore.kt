package dk.cachet.carp.dsp.portal.run

import dk.cachet.carp.dsp.portal.api.ExecutionReport
import dk.cachet.carp.dsp.portal.api.ExecutorState
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A run's state and report, as written beside its workspace. */
@Serializable
data class RunRecord(val state: ExecutorState, val report: ExecutionReport)

/**
 * Runs on disk.
 *
 * A run directory already holds the workspace and the files the steps produced.
 * This puts the state and the report beside them, written on every step event,
 * so the Runs page survives a restart and a finished run can be reopened
 * without the engine.
 *
 * Reads are cached: a record only changes while its run is live, and a live run
 * is answered from memory.
 */
object RunStore {

    private const val FILE_NAME = "run.json"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val cache = ConcurrentHashMap<String, RunRecord>()

    fun save(runDir: File, record: RunRecord) {
        cache[runDir.name] = record
        runCatching { File(runDir, FILE_NAME).writeText(json.encodeToString(record)) }
    }

    fun load(runDir: File): RunRecord? =
        cache[runDir.name] ?: runCatching {
            File(runDir, FILE_NAME).takeIf { it.isFile }?.readText()
                ?.let { json.decodeFromString<RunRecord>(it) }
        }.getOrNull()?.also { cache[runDir.name] = it }

    fun all(runsRoot: File): List<RunRecord> =
        runsRoot.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { load(it) }
}
