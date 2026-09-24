package dk.cachet.carp.dsp.portal.mock

import dk.cachet.carp.dsp.portal.api.TablePreview
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Real output from a real DSP run, bundled as a fixture.
 *
 * The portal does not execute anything - running a pipeline needs pixi
 * environments inside the container, which is still an open decision (epic
 * question 4). So instead of faking bytes, a recorded run is served verbatim:
 * real CSVs, a real plot, real sizes and digests.
 *
 * To add another pipeline, drop its output under
 * `resources/runs/<name>/` with a `manifest.json` alongside, and add the
 * directory to [FIXTURES]. Nothing else changes.
 */
object RecordedRun {

    private val FIXTURES = listOf("activity-summary-offline")

    @Serializable
    data class Entry(
        val stepId: String,
        val outputId: String,
        /** Relative to the fixture directory. */
        val path: String,
        val sizeBytes: Long,
        val sha256: String,
        val contentType: String,
    )

    @Serializable
    data class Manifest(
        val workflowId: String,
        val outputs: List<Entry> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val loader = RecordedRun::class.java.classLoader

    /** workflowId -> fixture directory plus its manifest. */
    private val byWorkflow: Map<String, Pair<String, Manifest>> = FIXTURES.mapNotNull { dir ->
        val text = loader.getResource("runs/$dir/manifest.json")?.readText() ?: return@mapNotNull null
        val manifest = runCatching { json.decodeFromString<Manifest>(text) }.getOrNull()
            ?: return@mapNotNull null
        manifest.workflowId to (dir to manifest)
    }.toMap()

    fun manifestFor(workflowId: String): Manifest? = byWorkflow[workflowId]?.second

    fun hasFixture(workflowId: String): Boolean = workflowId in byWorkflow

    /** Workflows that have recorded output, so they can be seeded at startup. */
    fun workflowIds(): Set<String> = byWorkflow.keys

    fun entry(workflowId: String, stepId: String, outputId: String): Entry? =
        manifestFor(workflowId)?.outputs?.firstOrNull {
            it.stepId == stepId && it.outputId == outputId
        }

    /** Raw bytes for one output, or null when the fixture has no such file. */
    fun bytes(workflowId: String, stepId: String, outputId: String): ByteArray? {
        val (dir, _) = byWorkflow[workflowId] ?: return null
        val entry = entry(workflowId, stepId, outputId) ?: return null
        return loader.getResourceAsStream("runs/$dir/${entry.path}")?.use { it.readBytes() }
    }

    /**
     * First few rows of a CSV output, for the results table.
     *
     * Deliberately naive splitting on commas: these are the pipeline's own
     * outputs, which have no quoted fields. A general CSV parser is not worth
     * the dependency here, but this will need replacing if a step ever emits
     * quoted values.
     */
    fun preview(workflowId: String, stepId: String, outputId: String, limit: Int = 8): TablePreview? {
        val entry = entry(workflowId, stepId, outputId) ?: return null
        if (entry.contentType != "text/csv") return null

        val text = bytes(workflowId, stepId, outputId)?.decodeToString() ?: return null
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return null

        return TablePreview(
            columns = lines.first().split(','),
            rows = lines.drop(1).take(limit).map { it.split(',') },
            totalRows = (lines.size - 1).toLong(),
        )
    }
}
