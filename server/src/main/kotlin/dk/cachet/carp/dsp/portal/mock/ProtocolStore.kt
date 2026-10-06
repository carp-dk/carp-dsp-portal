package dk.cachet.carp.dsp.portal.mock

import carp.dsp.core.application.plan.StudyProtocolSnapshotDataTypeProvider
import dk.cachet.carp.common.infrastructure.serialization.createDefaultJSON
import dk.cachet.carp.dsp.portal.api.ProtocolSummary
import dk.cachet.carp.protocols.application.StudyProtocolSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * Top level, not a member of [ProtocolStore]: an extension declared inside an
 * object is only in scope within it, so the routes could not import it.
 */
fun StudyProtocolSnapshot.toDto(active: Boolean) = ProtocolSummary(
    id = id.toString(),
    name = name,
    version = version,
    description = description,
    deviceRoles = primaryDevices.map { it.roleName },
    collectedDataTypes = ProtocolStore.collectedDataTypes(this),
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
 * Protocols are carp core's [StudyProtocolSnapshot], read with carp's own JSON,
 * and what they collect is worked out by the provider the engine plans with.
 */
object ProtocolStore {

    private val json = createDefaultJSON()

    /** Keyed by id and version - the fixtures share an id and differ by version. */
    private val protocols = ConcurrentHashMap<String, StudyProtocolSnapshot>()

    private fun key(id: String, version: Int) = "$id@v$version"

    private fun key(snapshot: StudyProtocolSnapshot) = key(snapshot.id.toString(), snapshot.version)

    init {
        RepoSource.protocols().forEach { text ->
            runCatching { json.decodeFromString(StudyProtocolSnapshot.serializer(), text) }
                .onSuccess { protocols[key(it)] = it }
        }
    }

    /**
     * Which protocol version the study is currently working against.
     *
     * Switching it is how the demo shows the coupling check both ways: v1
     * collects heart rate, v2 does not.
     */
    @Volatile
    private var activeKey: String? = protocols.values.minByOrNull { it.version }?.let { key(it) }

    fun list(): List<StudyProtocolSnapshot> = protocols.values.sortedWith(compareBy({ it.name }, { it.version }))

    fun active(): StudyProtocolSnapshot? = activeKey?.let { protocols[it] }

    fun activate(id: String, version: Int): StudyProtocolSnapshot? {
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

    /**
     * Adds a protocol and makes it the active one.
     *
     * @throws WorkflowParseException when [text] is not a study protocol snapshot.
     */
    fun add(text: String): StudyProtocolSnapshot {
        val snapshot = try {
            json.decodeFromString(StudyProtocolSnapshot.serializer(), text)
        } catch (e: Exception) {
            throw WorkflowParseException(e.message ?: "That does not look like a study protocol snapshot.")
        }
        val k = key(snapshot)
        protocols[k] = snapshot
        activeKey = k
        if (k !in fixtureKeys) uploadedJson[k] = text
        StateStore.save()
        return snapshot
    }

    /**
     * Data types collected by a referenced protocol: at [version] when given,
     * the latest otherwise. Null means the protocol, or that version of it, is
     * not held here - a different answer from "collects nothing".
     */
    fun collectedDataTypes(id: String, version: Int? = null): List<String>? =
        StudyProtocolSnapshotDataTypeProvider(protocols.values).collectedDataTypes(id, version)?.sorted()

    /** Every CARP DataType [snapshot] collects. */
    fun collectedDataTypes(snapshot: StudyProtocolSnapshot): List<String> =
        collectedDataTypes(snapshot.id.toString(), snapshot.version).orEmpty()
}
