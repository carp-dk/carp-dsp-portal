package dk.cachet.carp.dsp.portal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The edition this portal was built as, from `editions.yaml` and `-Pedition`.
 *
 * [content] is false when the build carries no carp-dsp content; a `DSP_REPO`
 * mount is then ignored too. [seedRuns] is true when the portal starts with a
 * finished run for each demo workflow that has recorded output. [tabs] are the
 * nav tabs the web app shows, by route name.
 */
@Serializable
data class Edition(
    val name: String,
    val content: Boolean,
    val seedRuns: Boolean = false,
    val tabs: List<String>,
) {
    /** Returns the default path for state named [name], under `data/<edition>/`. */
    fun dataPath(name: String): String = "data/${this.name}/$name"

    companion object {
        // Written by the build's writeEdition task. Missing only when the server
        // runs without processResources (an IDE run), so fall back to standard.
        private const val RESOURCE = "edition.json"

        private val json = Json { ignoreUnknownKeys = true }

        private val FALLBACK = Edition(
            name = "standard",
            content = true,
            tabs = listOf(
                "workflows", "runs", "results", "data", "compose",
                "library", "environments", "upload", "settings",
            ),
        )

        /** Returns the edition baked into this build. */
        val current: Edition by lazy {
            Edition::class.java.classLoader.getResource(RESOURCE)
                ?.readText()
                ?.let { json.decodeFromString<Edition>(it) }
                ?: FALLBACK
        }
    }
}
