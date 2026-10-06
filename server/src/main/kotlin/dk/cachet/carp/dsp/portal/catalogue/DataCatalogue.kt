package dk.cachet.carp.dsp.portal.catalogue

import dk.cachet.carp.dsp.portal.api.DataSource
import dk.cachet.carp.dsp.portal.store.ProtocolStore
import dk.cachet.carp.dsp.portal.store.StateStore
import java.util.concurrent.ConcurrentHashMap

/**
 * What data the study has available for a workflow to read.
 *
 * Three kinds, matching the three non-step-output source types a workflow input
 * can declare:
 *
 * - **protocol** - a DataType the active study protocol collects
 * - **file** - a data file supplied with the study
 * - **external** - open data, referenced by URI, typically a Zenodo record
 *
 * This is what an import is validated against: a workflow is compatible with
 * the study when every boundary input it declares can be satisfied here.
 */
object DataCatalogue {

    /** Uploaded or repo-supplied files, keyed by their path. */
    private val files = ConcurrentHashMap<String, DataSource>()

    /** External datasets, keyed by URI. */
    private val external = ConcurrentHashMap<String, DataSource>()

    init {
        RepoSource.dataFiles().forEach { path ->
            files[path] = DataSource(
                kind = "file",
                id = path,
                name = path.substringAfterLast('/'),
                description = "Sample data shipped with the demo workflows",
                sizeBytes = RepoSource.dataFileText(path)?.length?.toLong(),
            )
        }
    }

    /**
     * Everything available, protocol data types included.
     *
     * Protocol entries are derived rather than stored: the active protocol is
     * the authority on what the study collects, so caching it here would just
     * be a second copy to keep in step.
     */
    fun list(): List<DataSource> {
        val protocol = ProtocolStore.active()

        val fromProtocol = protocol?.let { ProtocolStore.collectedDataTypes(it) }.orEmpty().map { dataType ->
            DataSource(
                kind = "protocol",
                id = dataType,
                name = dataType.substringAfterLast('.'),
                description = "Collected by ${protocol?.name} v${protocol?.version}",
                dataType = dataType,
                protocolId = protocol?.id?.toString(),
            )
        }

        return fromProtocol +
            files.values.sortedBy { it.id } +
            external.values.sortedBy { it.id }
    }

    fun addFile(path: String, sizeBytes: Long?, description: String?): DataSource {
        val entry = DataSource(
            kind = "file",
            id = path,
            name = path.substringAfterLast('/'),
            description = description,
            sizeBytes = sizeBytes,
        )
        files[path] = entry
        StateStore.save()
        return entry
    }

    /**
     * Registers an external dataset. A Zenodo record is the common case, so the
     * record number is pulled out of the URI for a readable name.
     */
    fun addExternal(uri: String, citation: String?, description: String?): DataSource {
        val zenodo = Regex("""zenodo\.org/(?:record|records)/(\d+)""")
            .find(uri)
            ?.groupValues
            ?.get(1)

        val entry = DataSource(
            kind = "external",
            id = uri,
            name = zenodo?.let { "Zenodo $it" } ?: uri.substringAfterLast('/'),
            description = description,
            uri = uri,
            citation = citation,
        )
        external[uri] = entry
        StateStore.save()
        return entry
    }

    fun hasFile(path: String): Boolean = path.trim('/') in files.keys

    fun hasExternal(uri: String): Boolean = uri in external.keys

    // ---- persistence --------------------------------------------------------
    // Only entries added through the portal are saved. Files that came from the
    // repo are re-read at startup, so persisting them would pin a stale list.

    private val repoFiles: Set<String> = files.keys.toSet()

    fun fileSnapshot(): List<DataSource> =
        files.filterKeys { it !in repoFiles }.values.toList()

    fun externalSnapshot(): List<DataSource> = external.values.toList()

    fun restoreFile(entry: DataSource) {
        files[entry.id] = entry
    }

    fun restoreExternal(entry: DataSource) {
        external[entry.id] = entry
    }
}
