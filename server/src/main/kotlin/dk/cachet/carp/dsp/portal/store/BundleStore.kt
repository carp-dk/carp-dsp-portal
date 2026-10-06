package dk.cachet.carp.dsp.portal.store

import java.io.File

/**
 * The files a saved bundle carried beside its workflow, kept so that running it
 * later from the study stages what running it from the upload would have.
 *
 * Location: `DSP_BUNDLES`, or a `bundles` directory beside the state file.
 */
object BundleStore {

    // On disk rather than in the state file: a bundle can carry data files of any
    // size, and the state file is rewritten whole on every change.
    var root: File = File(
        System.getenv("DSP_BUNDLES")
            ?: File(System.getenv("DSP_STATE") ?: "data/portal-state.json")
                .absoluteFile.resolveSibling("bundles").path,
    )

    /**
     * Replaces the files kept for [workflowId] with [files], keyed by their path
     * in the bundle. An empty map keeps none.
     *
     * @throws IllegalArgumentException when a path would land outside the bundle.
     */
    fun save(workflowId: String, files: Map<String, ByteArray>) {
        val dir = directoryOf(workflowId)
        dir.deleteRecursively()

        files.forEach { (relative, bytes) ->
            val target = File(dir, relative)
            require(target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                "Bundle entry '$relative' escapes the bundle."
            }
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
        }
    }

    /** Returns the files kept for [workflowId], keyed by their path in the bundle. */
    fun load(workflowId: String): Map<String, ByteArray> {
        val dir = directoryOf(workflowId)
        if (!dir.isDirectory) return emptyMap()

        return dir.walkTopDown()
            .filter { it.isFile }
            .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes() }
    }

    /** Forgets the files kept for [workflowId], if any. */
    fun remove(workflowId: String) {
        directoryOf(workflowId).deleteRecursively()
    }

    /**
     * A workflow id is text the user wrote, and here it names a directory, so it
     * must not be able to reach outside [root].
     */
    private fun directoryOf(workflowId: String): File {
        val dir = File(root, workflowId)
        require(dir.canonicalFile.parentFile == root.canonicalFile) {
            "Workflow id '$workflowId' cannot name a bundle directory."
        }
        return dir
    }
}
