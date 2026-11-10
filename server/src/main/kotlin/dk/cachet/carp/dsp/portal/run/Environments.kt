package dk.cachet.carp.dsp.portal.run

import carp.dsp.core.infrastructure.execution.handlers.EnvironmentEntry
import carp.dsp.core.infrastructure.execution.handlers.EnvironmentStore
import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.application.plan.PixiEnvironmentRef
import dk.cachet.carp.analytics.application.plan.REnvironmentRef
import dk.cachet.carp.dsp.portal.api.EnvironmentDto
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.io.path.name

/** How long a measured size is trusted before the directory is walked again. */
private const val SIZE_TTL_MS = 5 * 60 * 1000L

/** The store names a directory `<name>-<16 hex digits>`. */
private val DIGEST_SUFFIX = Regex("-[0-9a-f]{16}$")

/**
 * The environments on the state volume, as the Environments page lists them.
 */
object Environments {

    // A pixi environment is tens of thousands of files, so its size is measured
    // once and kept for a few minutes rather than walked on every page load.
    private val sizes = ConcurrentHashMap<Path, Pair<Long, Long>>()

    // Measured off the request thread: with hundreds of environments a walk takes
    // minutes, and the page must not wait for it. One thread, so walks never
    // compete with each other for the disk.
    private val measuring = ConcurrentHashMap.newKeySet<Path>()
    private val measurer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "environment-size").apply { isDaemon = true }
    }

    /** Returns every environment directory the engine keeps, solved or not. */
    fun list(): List<EnvironmentDto> = EnvironmentStore.entries().map(::toDto)

    private fun toDto(entry: EnvironmentEntry): EnvironmentDto {
        val ref = entry.ref
        val (runtime, dependencies, channels) = when (ref) {
            is PixiEnvironmentRef -> Triple("Python ${ref.pythonVersion}", ref.dependencies, ref.channels)
            is CondaEnvironmentRef -> Triple("Python ${ref.pythonVersion}", ref.dependencies, ref.channels)
            is REnvironmentRef -> Triple("R ${ref.rVersion}", ref.rPackages + ref.dependencies, emptyList())
            else -> Triple(null, emptyList(), emptyList())
        }

        return EnvironmentDto(
            id = entry.directory.name,
            kind = entry.kind,
            name = nameOf(entry),
            status = if (ref != null) "solved" else "incomplete",
            runtime = runtime,
            dependencies = dependencies,
            channels = channels,
            sizeBytes = sizeOf(entry.directory),
            builtAt = entry.builtAt?.toString(),
            lastUsedAt = entry.lastUsedAt?.toString(),
        )
    }

    /** The environment's own name, or the directory's without its digest when it never finished. */
    private fun nameOf(entry: EnvironmentEntry): String =
        when (val ref = entry.ref) {
            is PixiEnvironmentRef -> ref.name
            is CondaEnvironmentRef -> ref.name
            is REnvironmentRef -> ref.name
            else -> entry.directory.name.replace(DIGEST_SUFFIX, "")
        }

    /** The last measured size, or null until the first measurement finishes. Never waits for a walk. */
    private fun sizeOf(directory: Path): Long? {
        val cached = sizes[directory]
        if (cached == null || System.currentTimeMillis() - cached.second >= SIZE_TTL_MS) measureLater(directory)
        return cached?.first
    }

    private fun measureLater(directory: Path) {
        if (!measuring.add(directory)) return

        measurer.execute {
            try {
                // A directory can vanish or be half-written while it is walked; an
                // unknown size is better than a failed page.
                runCatching {
                    Files.walk(directory).use { paths ->
                        paths.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
                    }
                }.getOrNull()?.let { sizes[directory] = it to System.currentTimeMillis() }
            } finally {
                measuring.remove(directory)
            }
        }
    }
}
