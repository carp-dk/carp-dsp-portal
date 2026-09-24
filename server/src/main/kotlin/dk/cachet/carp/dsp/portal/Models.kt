package dk.cachet.carp.dsp.portal

import kotlinx.serialization.Serializable

/**
 * Error envelope returned by every `/api` route.
 *
 * [kind] mirrors the `ExecutionIssue` / `WorkflowException` subtype names so the
 * front end can switch on it. Once the real services are wired in (W4-1) the
 * values come from the domain rather than from string literals here.
 */
@Serializable
data class ApiError(
    val kind: String,
    val message: String,
)

/**
 * Health, plus enough counts to diagnose a loading problem without guessing.
 *
 * [stepLoadFailures] is the one that matters: a step file the parser rejects is
 * skipped, and without this it disappears silently.
 */
@Serializable
data class HealthResponse(
    val status: String,
    val service: String,
    val version: String,
    /** "repo:<path>" when reading a live checkout, "bundled" otherwise. */
    val source: String = "bundled",
    val librarySteps: Int = 0,
    val demoWorkflows: Int = 0,
    val studyWorkflows: Int = 0,
    val stepLoadFailures: Map<String, String> = emptyMap(),
    /** Where the session is saved, so a lost session can be diagnosed. */
    val stateFile: String = "",
    /** "engine" or "mock" - which validator answers. See run/Validation.kt. */
    val validator: String = "",
    /** "exact" or "superset" - how far environment reuse may stretch. */
    val environmentReuse: String = "",
)
