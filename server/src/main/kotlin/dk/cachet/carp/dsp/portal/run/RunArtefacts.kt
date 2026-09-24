package dk.cachet.carp.dsp.portal.run

import dk.cachet.carp.dsp.portal.api.ArtifactEntry
import dk.cachet.carp.dsp.portal.api.ArtifactReference
import dk.cachet.carp.dsp.portal.api.GenericArtifact
import dk.cachet.carp.dsp.portal.api.ImageArtifact
import dk.cachet.carp.dsp.portal.api.TablePreview
import dk.cachet.carp.dsp.portal.api.WorkflowArtifact
import java.io.File

/**
 * Turns what a real run wrote into the rows the results view renders.
 *
 * The engine records a produced output as a path, a size, a digest and a content
 * type. That is enough to type an artefact for display: an image gets an
 * [ImageArtifact] so the page renders it inline, anything else falls back to
 * [GenericArtifact], which the page shows as a row with a download link.
 *
 * Richer typing - a plot carrying its spec, a feature table naming its columns -
 * comes from the step's own manifest, which is W3-2's job. Until then this reads
 * only what the executor already knows.
 */
object RunArtefacts {

    /** Rows shown, and the most of a file worth reading to find them. */
    private const val PREVIEW_ROWS = 10
    private const val PREVIEW_BYTES = 64 * 1024
    private const val COUNTABLE_BYTES = 16L * 1024 * 1024

    /** How CommandStepRunner inlines a short output. */
    private const val DATA_URI_PREFIX = "data:text/plain,"

    fun entries(executionId: String): List<ArtifactEntry> {
        val report = DspRunner.report(executionId) ?: return emptyList()

        return report.stepResults.flatMap { step ->
            step.outputs.map { output ->
                val path = output.location.value
                ArtifactEntry(
                    stepId = step.stepMetadata.id,
                    outputId = output.outputId,
                    artifact = artifactFor(output.contentType, path, output.sizeBytes, output.sha256),
                    downloadUrl = "/api/artefacts/$executionId/${step.stepMetadata.id}/${output.outputId}",
                    stepName = step.stepMetadata.name,
                    outputName = output.name,
                    preview = previewOf(executionId, step.stepMetadata.id, output.outputId, output.contentType),
                )
            }
        }
    }

    /**
     * The first rows of a delimited output, for the results view.
     *
     * The whole point of a pipeline is its final table, and making someone
     * download a file to see it is a poor way to end a demo. Only the head of the
     * file is read - the IMU recording mobgap starts from is three megabytes, and
     * ten rows is what the page shows.
     *
     * [TablePreview.totalRows] is counted only for files small enough that
     * counting is free. Null means "not counted", not "empty".
     */
    private fun previewOf(
        executionId: String,
        stepId: String,
        outputId: String,
        contentType: String?,
    ): TablePreview? {
        val delimiter = when {
            contentType == null -> return null
            contentType.startsWith("text/csv") -> ','
            contentType.startsWith("text/tab-separated-values") -> '\t'
            else -> return null
        }

        val file = file(executionId, stepId, outputId)?.first?.takeIf { it.isFile } ?: return null

        val head = file.inputStream().use { stream ->
            String(stream.readNBytes(PREVIEW_BYTES), Charsets.UTF_8)
        }

        // A trailing fragment is dropped: the last line of a truncated read is
        // usually half a row, and half a row is worse than one row fewer.
        val lines = head.lineSequence()
            .let { if (head.length == PREVIEW_BYTES) it.take(PREVIEW_ROWS + 1) else it }
            .filter { it.isNotBlank() }
            .take(PREVIEW_ROWS + 1)
            .toList()

        if (lines.isEmpty()) return null

        return TablePreview(
            columns = splitRow(lines.first(), delimiter),
            rows = lines.drop(1).map { splitRow(it, delimiter) },
            totalRows = countDataRows(file),
        )
    }

    /** Data rows, excluding the header. Null when the file is too big to be worth counting. */
    private fun countDataRows(file: File): Long? {
        if (file.length() > COUNTABLE_BYTES) return null
        val lines = file.bufferedReader().use { reader -> reader.lineSequence().count { it.isNotBlank() } }
        return (lines - 1).coerceAtLeast(0).toLong()
    }

    /**
     * Splits one row, respecting double-quoted fields.
     *
     * Not a full CSV parser - it does not handle a newline inside a quoted field,
     * which would need the reader to know about quoting too. It handles the case
     * that actually corrupts a preview: a delimiter inside a quoted value.
     */
    private fun splitRow(line: String, delimiter: Char): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false

        line.forEach { char ->
            when {
                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> {
                    fields += field.toString()
                    field.clear()
                }
                else -> field.append(char)
            }
        }

        fields += field.toString()
        return fields
    }

    /**
     * What a step printed, as text.
     *
     * The engine records it two ways depending on length: a `data:` URI inline for
     * a short run, or a path under the execution root for anything the recorder
     * wrote to disk. Both are resolved here so the caller does not have to know
     * which it got.
     */
    fun stepOutput(executionId: String, stepId: String): String? {
        val report = DspRunner.report(executionId) ?: return null
        val step = report.stepResults.firstOrNull { it.stepMetadata.id == stepId } ?: return null
        val ref = step.detail?.output?.value ?: return null

        if (ref.startsWith(DATA_URI_PREFIX)) {
            return ref.removePrefix(DATA_URI_PREFIX)
        }

        val runDir = DspRunner.runDir(executionId) ?: return null
        val root = executionRoot(runDir, executionId) ?: return null
        val file = File(root, ref)

        // Same containment check the artefact route makes: the ref comes from a
        // report, and a report is data.
        if (!file.isFile || !file.canonicalPath.startsWith(runDir.canonicalPath)) return null

        return file.readText()
    }

    /** The file a run produced, or null if the run or the output is unknown. */
    fun file(executionId: String, stepId: String, outputId: String): Pair<File, String>? {
        val report = DspRunner.report(executionId) ?: return null
        val runDir = DspRunner.runDir(executionId) ?: return null

        val step = report.stepResults.firstOrNull { it.stepMetadata.id == stepId } ?: return null
        val output = step.outputs.firstOrNull { it.outputId == outputId } ?: return null

        val root = executionRoot(runDir, executionId) ?: return null
        val file = File(root, output.location.value)
        if (!file.isFile || !file.canonicalPath.startsWith(runDir.canonicalPath)) return null

        return file to (output.contentType ?: "application/octet-stream")
    }

    /**
     * The directory a produced output's path is relative to.
     *
     * The workspace manager lays a run out as `<workspace>/<workflow-slug>/run_<id>/`,
     * and records output locations relative to that inner directory rather than to
     * the workspace root. The slug is derived from the workflow name, so it is
     * found rather than reconstructed - and the executor does not hand the
     * workspace back, so there is nothing to ask.
     */
    private fun executionRoot(runDir: File, executionId: String): File? =
        runDir.listFiles { f: File -> f.isDirectory }
            ?.map { File(it, "run_$executionId") }
            ?.firstOrNull { it.isDirectory }

    private fun artifactFor(
        contentType: String?,
        path: String,
        sizeBytes: Long?,
        sha256: String?,
    ): WorkflowArtifact {
        val reference = ArtifactReference(
            uri = path,
            mimeType = contentType ?: "application/octet-stream",
            sizeBytes = sizeBytes ?: 0,
            sha256 = sha256.orEmpty(),
        )
        return if (contentType?.startsWith("image/") == true) {
            ImageArtifact(reference = reference)
        } else {
            GenericArtifact(reference = reference)
        }
    }
}
