package dk.cachet.carp.dsp.portal.mock

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable

/**
 * The step library, read from the real `step.yaml` files in
 * carp.dsp.steps.
 *
 * Jars cannot list directories, so `index.txt` records the paths. Regenerate it
 * with `find . -name step.yaml` from the resource root after adding a step.
 */
object StepLibrary {

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    @Serializable
    data class StepLibraryFile(
        val schemaVersion: String = "1.0",
        val metadata: WorkflowMetadata,
        val environments: Map<String, EnvironmentSpec> = emptyMap(),
        val steps: List<StepSpec> = emptyList(),
        val library: LibraryBlock? = null,
    )

    @Serializable
    data class LibraryBlock(
        val tier: String? = null,
        val subject: String? = null,
        val environment: LibraryEnvironment? = null,
        val implementations: List<Implementation> = emptyList(),
        val method: Method? = null,
        val reference: Reference? = null,
    )

    @Serializable
    data class LibraryEnvironment(
        val default: String? = null,
        val requires: Requires? = null,
    )

    @Serializable
    data class Requires(
        val kind: List<String> = emptyList(),
        val interpreter: Interpreter? = null,
        /**
         * Objects, not strings: `- name: pandas` / `version: ">=2.0"`.
         *
         * Only `select-columns` and `generate-hr-steps` declare `packages: []`,
         * which is why those two were the only steps loading while this was
         * typed as `List<String>`.
         */
        val packages: List<Package> = emptyList(),
    )

    @Serializable
    data class Package(val name: String, val version: String? = null)

    @Serializable
    data class Interpreter(val name: String? = null, val version: String? = null)

    @Serializable
    data class Implementation(val language: String, val path: String? = null)

    @Serializable
    data class Method(val name: String? = null, val citation: String? = null)

    @Serializable
    data class Reference(
        val input: String? = null,
        val expected: String? = null,
        val tolerance: Tolerance? = null,
    )

    @Serializable
    data class Tolerance(val float: Double? = null)

    /**
     * The conformance record. `level` is the gate: a step is `gated` until it
     * has been reviewed, and `reviewedHash` is what ties the review to the
     * content it covered.
     */
    @Serializable
    data class Certification(
        val id: String? = null,
        val version: String? = null,
        val level: String? = null,
        val contentHash: String? = null,
        val reviewedOn: String? = null,
        val reviewer: String? = null,
        val reviewedPr: String? = null,
        val reviewedHash: String? = null,
    )

    /** One library entry: the parsed file, its certification, and its README. */
    @Serializable
    data class Entry(
        val stepId: String,
        val definition: StepLibraryFile,
        val certification: Certification? = null,
        val readme: String? = null,
        val rawYaml: String,
        /** Directory relative to the steps root - the key for fetching files. */
        val dir: String = "",
        /** Everything in that directory: implementations, tests, fixtures. */
        val files: List<String> = emptyList(),
    )

    private val bundled: Map<String, Entry> by lazy { load() }

    /**
     * Steps added through the UI. Kept apart from the vendored set so an upload
     * can never quietly shadow a gated step, and so a restart returns the
     * library to exactly what is in the repo.
     */
    private val uploaded = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    private val entries: Map<String, Entry> get() = bundled + uploaded

    /**
     * Parses a `step.yaml` and adds it. Returns the entry, or throws
     * [WorkflowParseException] with something a person can act on.
     *
     * Anything added here is unreviewed by definition: the real library gates
     * on a PR and a conformance review, so the certification is written as
     * `gated` with no reviewer rather than left absent.
     */
    fun add(yamlText: String): Entry {
        val definition = try {
            yaml.decodeFromString(StepLibraryFile.serializer(), yamlText)
        } catch (e: Exception) {
            throw WorkflowParseException(e.message ?: "The step file could not be read.")
        }

        val id = definition.metadata.id
        if (id.isBlank()) {
            throw WorkflowParseException("The step needs a metadata.id, e.g. 'core.reshape.my-step'.")
        }
        if (id in bundled) {
            throw WorkflowParseException("'$id' is already in the vendored library and cannot be replaced here.")
        }
        if (definition.steps.isEmpty()) {
            throw WorkflowParseException("A step file needs exactly one entry under 'steps:'.")
        }

        val entry = Entry(
            stepId = id,
            definition = definition,
            certification = Certification(
                id = id,
                version = definition.metadata.version,
                level = "gated",
            ),
            readme = null,
            rawYaml = yamlText,
        )
        uploaded[id] = entry
        StateStore.save()
        return entry
    }

    /** Raw step.yaml of portal-added steps, reparsed on load. */
    fun uploadedSnapshot(): List<String> = uploaded.values.map { it.rawYaml }

    /**
     * Why a step file was skipped, keyed by its directory.
     *
     * very useful for debugging when steps don't load.
     */
    val loadFailures: MutableMap<String, String> = linkedMapOf()

    private fun load(): Map<String, Entry> =
        RepoSource.steps()
            .mapNotNull { source ->
                val definition = try {
                    yaml.decodeFromString(StepLibraryFile.serializer(), source.stepYaml)
                } catch (e: Exception) {
                    loadFailures[source.dir] = e.message ?: e::class.simpleName.orEmpty()
                    return@mapNotNull null
                }

                Entry(
                    stepId = definition.metadata.id,
                    definition = definition,
                    certification = source.certificationYaml?.let { cert ->
                        runCatching {
                            yaml.decodeFromString(Certification.serializer(), cert)
                        }.getOrNull()
                    },
                    readme = source.readme,
                    rawYaml = source.stepYaml,
                    dir = source.dir,
                    files = source.files,
                )
            }
            .associateBy { it.stepId }

    fun list(): List<Entry> = entries.values.sortedBy { it.stepId }

    fun get(stepId: String): Entry? = entries[stepId]
}
