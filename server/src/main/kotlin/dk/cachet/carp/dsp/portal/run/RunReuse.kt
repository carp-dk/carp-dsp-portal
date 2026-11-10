package dk.cachet.carp.dsp.portal.run

import carp.dsp.core.infrastructure.execution.StepCache
import dk.cachet.carp.dsp.portal.api.ExecutionStatus
import dk.cachet.carp.dsp.portal.api.ExecutorState
import dk.cachet.carp.dsp.portal.store.StateStore
import java.io.File
import java.security.MessageDigest
import org.slf4j.LoggerFactory

/**
 * What lets a frequent schedule run without filling the disk: the step cache every
 * run shares, and a limit on how many runs a schedule keeps.
 *
 * - `DSP_CACHE`: where cached step outputs live; beside the state file by default.
 *   On the same volume as the runs, so a reused output is a hard link, not a copy.
 * - `DSP_CACHE_MAX_GB`: the size the cache is trimmed to (10).
 * - `DSP_KEEP_SCHEDULED_RUNS`: finished runs a schedule keeps (24); older ones
 *   are deleted. Runs started by hand are never deleted.
 * - `CARP_DSP_QUERY_STORE`: what `core.io.query-sql --incremental` keeps between
 *   runs; beside the cache by default, so it survives a new container.
 */
object RunReuse {

    private val log = LoggerFactory.getLogger(RunReuse::class.java)

    private const val DEFAULT_KEEP = 24
    private const val BYTES_PER_GIGABYTE = 1L shl 30
    private const val HEX_RADIX = 16
    private const val BYTE_MASK = 0xff

    // Paths the runs need, not inputs to them: a different path gives the same data.
    private const val QUERY_STORE = "CARP_DSP_QUERY_STORE"
    private val NOT_INPUTS = setOf("CARP_DSP_TASK_RUNTIME", QUERY_STORE)

    val cacheDirectory: File =
        System.getenv("DSP_CACHE")?.let(::File)
            ?: File(File(StateStore.location()).absoluteFile.parentFile, "cache")

    private val maxBytes: Long =
        (System.getenv("DSP_CACHE_MAX_GB")?.toLongOrNull() ?: StepCache.DEFAULT_MAX_GIGABYTES) * BYTES_PER_GIGABYTE

    private val queryStore: File = File(cacheDirectory.absoluteFile.parentFile, "query-store")

    /** [environment] for a run's steps, with the query store set unless it already is. */
    fun withStores(environment: Map<String, String>): Map<String, String> =
        if (environment.containsKey(QUERY_STORE) || System.getenv(QUERY_STORE) != null) environment
        else environment + (QUERY_STORE to queryStore.path)

    /** How many finished runs one schedule keeps. */
    val keep: Int = System.getenv("DSP_KEEP_SCHEDULED_RUNS")?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_KEEP

    /**
     * A cache for one run. The database and credentials the run reads with are part
     * of every fingerprint, so pointing the Settings page at another database never
     * reuses what the first one returned.
     */
    fun cacheFor(environment: Map<String, String>): StepCache =
        StepCache(cacheDirectory.toPath(), saltOf(System.getenv() + environment), maxBytes)

    /**
     * Returns the runs of [scheduleId] to delete: finished ones beyond the newest
     * [keep]. A run still going is never among them.
     */
    fun surplus(scheduleId: String, runs: List<ExecutorState>, keep: Int = this.keep): List<ExecutorState> =
        runs.filter { it.context?.scheduleId == scheduleId }
            .filter { it.status != ExecutionStatus.RUNNING && it.status != ExecutionStatus.PENDING }
            .sortedByDescending { it.startedAt }
            .drop(keep)

    /** Deletes [runs]' directories, logging rather than failing a run over it. */
    fun delete(runs: List<ExecutorState>, dirOf: (String) -> File?) {
        runs.forEach { run ->
            val dir = dirOf(run.executionId) ?: return@forEach
            if (dir.deleteRecursively()) {
                log.info("Deleted run ${run.executionId} of schedule ${run.context?.scheduleId}: past the last $keep")
            } else {
                log.warn("Could not delete run ${run.executionId} at $dir")
            }
        }
    }

    /** What in [environment] decides a step's result: the `CARP_DSP_` variables, paths aside. */
    internal fun saltOf(environment: Map<String, String>): String {
        val inputs = environment
            .filterKeys { it.startsWith("CARP_DSP_") && it !in NOT_INPUTS }
            .toSortedMap()
            .entries.joinToString("\n") { (name, value) -> "$name=$value" }
        return MessageDigest.getInstance("SHA-256").digest(inputs.toByteArray())
            .joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0') }
    }
}
