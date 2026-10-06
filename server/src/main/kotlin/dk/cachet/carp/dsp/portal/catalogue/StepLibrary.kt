package dk.cachet.carp.dsp.portal.catalogue

import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.resolve.CertificationLevel
import carp.dsp.core.application.authoring.resolve.CertificationRecord
import carp.dsp.core.application.authoring.resolve.LibraryStep
import carp.dsp.core.application.authoring.resolve.StepCertificationFile
import carp.dsp.core.infrastructure.serialization.DecodeResult
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import dk.cachet.carp.dsp.portal.api.WorkflowView
import dk.cachet.carp.dsp.portal.api.toView
import dk.cachet.carp.dsp.portal.store.StateStore
import dk.cachet.carp.dsp.portal.store.WorkflowParseException
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The step library as the Library pages show it: every step, its README, its
 * certification and its files.
 *
 * `step.yaml` and `certification.yaml` are read with core's codec and records,
 * the same the engine resolves with, so a step reads the same here as when it
 * runs. The listing itself comes from [RepoSource], since core's classpath
 * library looks steps up by id but cannot list them.
 */
object StepLibrary {

    private val codec = WorkflowYamlCodec()

    /**
     * One library entry.
     *
     * @property dir Directory relative to the steps root; the key for fetching files.
     * @property files Everything in that directory: implementations, tests, fixtures.
     */
    @Serializable
    data class Entry(
        val stepId: String,
        val definition: WorkflowView,
        val certification: CertificationRecord? = null,
        val readme: String? = null,
        val rawYaml: String,
        val dir: String = "",
        val files: List<String> = emptyList(),
    )

    private val bundled: Map<String, Entry> by lazy { load() }

    /**
     * Steps added through the UI. Kept apart from the vendored set so an upload
     * can never quietly shadow a gated step, and so a restart returns the
     * library to exactly what is in the repo.
     */
    private val uploaded = ConcurrentHashMap<String, Entry>()

    private val entries: Map<String, Entry> get() = bundled + uploaded

    /**
     * Parses a `step.yaml` and adds it, as a gated step with no reviewer.
     *
     * @throws WorkflowParseException when the file does not parse, has no id,
     *   names a vendored step, or does not hold exactly one step with its own task.
     */
    fun add(yamlText: String): Entry {
        val descriptor = decode(yamlText)
        val id = descriptor.metadata.id.orEmpty()

        if (id.isBlank()) {
            throw WorkflowParseException("The step needs a metadata.id, e.g. 'core.reshape.my-step'.")
        }
        if (id in bundled) {
            throw WorkflowParseException("'$id' is already in the vendored library and cannot be replaced here.")
        }
        if (descriptor.steps.singleOrNull() !is DefinedStepDescriptor) {
            throw WorkflowParseException("A step file needs exactly one entry under 'steps:', with its own task.")
        }

        // Unreviewed by definition: the real library gates on a PR and a
        // conformance review, so this says gated rather than saying nothing.
        val entry = Entry(
            stepId = id,
            definition = descriptor.toView(id),
            certification = CertificationRecord(
                id = id,
                version = descriptor.metadata.version,
                level = CertificationLevel.GATED,
            ),
            rawYaml = yamlText,
        )
        uploaded[id] = entry
        StateStore.save()
        return entry
    }

    /** Raw step.yaml of portal-added steps, reparsed on load. */
    fun uploadedSnapshot(): List<String> = uploaded.values.map { it.rawYaml }

    /**
     * A portal-added step as the engine resolves it, or null when there is none
     * under [id] at [version]. A null [version] matches any.
     */
    fun uploadedStep(id: String, version: String?): LibraryStep? {
        val entry = uploaded[id] ?: return null
        val descriptor = decode(entry.rawYaml)
        val step = descriptor.steps.singleOrNull() as? DefinedStepDescriptor ?: return null
        if (version != null && version != descriptor.metadata.version) return null

        return LibraryStep(
            version = descriptor.metadata.version,
            step = step,
            environments = descriptor.environments,
            contentHash = sha256(entry.rawYaml),
        )
    }

    /** Why a step file was skipped, keyed by its directory. */
    val loadFailures: MutableMap<String, String> = linkedMapOf()

    private fun load(): Map<String, Entry> =
        RepoSource.steps()
            .mapNotNull { source ->
                val descriptor = try {
                    decode(source.stepYaml)
                } catch (e: WorkflowParseException) {
                    loadFailures[source.dir] = e.message.orEmpty()
                    return@mapNotNull null
                }
                val id = descriptor.metadata.id ?: source.dir.replace('/', '.')

                Entry(
                    stepId = id,
                    definition = descriptor.toView(id),
                    certification = source.certificationYaml?.let(StepCertificationFile::parse),
                    readme = source.readme,
                    rawYaml = source.stepYaml,
                    dir = source.dir,
                    files = source.files,
                )
            }
            .associateBy { it.stepId }

    fun list(): List<Entry> = entries.values.sortedBy { it.stepId }

    fun get(stepId: String): Entry? = entries[stepId]

    private fun decode(yamlText: String): WorkflowDescriptor = when (val result = codec.decode(yamlText)) {
        is DecodeResult.Success -> result.descriptor
        is DecodeResult.MalformedYaml -> throw WorkflowParseException(result.message)
        is DecodeResult.SchemaError -> throw WorkflowParseException(result.message)
        is DecodeResult.PolicyViolation -> throw WorkflowParseException(result.message)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
