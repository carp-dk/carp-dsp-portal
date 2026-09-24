package dk.cachet.carp.dsp.portal.mock

import dk.cachet.carp.dsp.portal.api.ProtocolSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Top level, not a member of [ProtocolStore]: an extension declared inside an
 * object is only in scope within it, so the routes could not import it.
 */
fun ProtocolStore.Snapshot.toDto(active: Boolean) = ProtocolSummary(
    id = id,
    name = name,
    version = version,
    description = description,
    deviceRoles = primaryDevices.mapNotNull { it.roleName },
    collectedDataTypes = collectedDataTypes,
    active = active,
)

/**
 * Study protocols, as the source of what data a study collects.
 *
 * The portal is not connected to a live study, so a protocol is an authored
 * artifact here: the fixtures from carp-dsp are loaded by default, and one can
 * be uploaded. That matches the coupling design, which validates against a
 * protocol *definition* rather than a deployment - no live study needed.
 *
 * Only the fields the coupling check needs are modelled. A real
 * StudyProtocolSnapshot carries far more; unknown keys are ignored.
 */
object ProtocolStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val loader = ProtocolStore::class.java.classLoader

    @Serializable
    data class Snapshot(
        val id: String,
        val name: String,
        val version: Int = 1,
        val description: String? = null,
        val ownerId: String? = null,
        val createdOn: String? = null,
        val primaryDevices: List<Device> = emptyList(),
        val tasks: List<Task> = emptyList(),
    ) {
        /**
         * Every CARP DataType this protocol collects.
         *
         * This is the set a `protocol`-sourced workflow input is checked
         * against. Matching is on the domain DataType, never on file format.
         */
        val collectedDataTypes: List<String>
            get() = tasks.flatMap { task -> task.measures.mapNotNull { it.type } }.distinct()
    }

    @Serializable
    data class Device(
        val roleName: String? = null,
        val isPrimaryDevice: Boolean = false,
    )

    @Serializable
    data class Task(
        val name: String? = null,
        val description: String? = null,
        val measures: List<Measure> = emptyList(),
    )

    @Serializable
    data class Measure(val type: String? = null)

    /** Keyed by id and version - the fixtures share an id and differ by version. */
    private val protocols = ConcurrentHashMap<String, Snapshot>()

    private fun key(id: String, version: Int) = "$id@v$version"

    init {
        RepoSource.protocols().forEach { text ->
            runCatching { json.decodeFromString<Snapshot>(text) }
                .onSuccess { protocols[key(it.id, it.version)] = it }
        }
    }

    /**
     * Which protocol version the study is currently working against.
     *
     * Switching it is how the demo shows the coupling check both ways: v1
     * collects heart rate, v2 does not.
     */
    @Volatile
    private var activeKey: String? = protocols.values
        .minByOrNull { it.version }
        ?.let { key(it.id, it.version) }

    fun list(): List<Snapshot> = protocols.values.sortedWith(
        compareBy({ it.name }, { it.version }),
    )

    fun active(): Snapshot? = activeKey?.let { protocols[it] }

    fun activate(id: String, version: Int): Snapshot? {
        val found = protocols[key(id, version)] ?: return null
        activeKey = key(id, version)
        StateStore.save()
        return found
    }

    /** Snapshots loaded from the repo fixtures - never persisted. */
    private val fixtureKeys: Set<String> = protocols.keys.toSet()

    /** Raw JSON of uploaded protocols, re-parsed on load. */
    private val uploadedJson = ConcurrentHashMap<String, String>()

    fun uploadedSnapshot(): List<String> = uploadedJson.values.toList()

    /** Named apart from the `activeKey` property to avoid shadowing it. */
    fun activeProtocolKey(): String? = activeKey

    fun add(text: String): Snapshot {
        val snapshot = try {
            json.decodeFromString<Snapshot>(text)
        } catch (e: Exception) {
            throw WorkflowParseException(
                e.message ?: "That does not look like a study protocol snapshot.",
            )
        }
        val k = key(snapshot.id, snapshot.version)
        protocols[k] = snapshot
        activeKey = k
        if (k !in fixtureKeys) uploadedJson[k] = text
        StateStore.save()
        return snapshot
    }

    /**
     * Data types collected by a referenced protocol.
     *
     * Selects by id, then by version when given, latest otherwise - the rule
     * the coupling design states. Null means the protocol is unknown here, which
     * is a different answer from "collects nothing".
     */
    fun collectedDataTypes(id: String, version: Int? = null): List<String>? {
        val candidates = protocols.values.filter { it.id == id }
        if (candidates.isEmpty()) return null

        val chosen = version?.let { v -> candidates.firstOrNull { it.version == v } }
            ?: candidates.maxByOrNull { it.version }

        return chosen?.collectedDataTypes
    }
}
