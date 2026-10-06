package dk.cachet.carp.dsp.portal.mock

import dk.cachet.carp.dsp.portal.api.ArtifactEntry
import dk.cachet.carp.dsp.portal.api.ArtifactReference
import dk.cachet.carp.dsp.portal.api.ComputedFeature
import dk.cachet.carp.dsp.portal.api.ExecutionReport
import dk.cachet.carp.dsp.portal.api.ExecutionStatus
import dk.cachet.carp.dsp.portal.api.ExecutorState
import dk.cachet.carp.dsp.portal.api.FeatureTable
import dk.cachet.carp.dsp.portal.api.ImageArtifact
import dk.cachet.carp.dsp.portal.api.ProducedOutputRef
import dk.cachet.carp.dsp.portal.api.ResourceRef
import dk.cachet.carp.dsp.portal.api.RunContext
import dk.cachet.carp.dsp.portal.api.StepRunMetadata
import dk.cachet.carp.dsp.portal.api.StepRunResult
import dk.cachet.carp.dsp.portal.api.StepSpec
import dk.cachet.carp.dsp.portal.api.SummaryStatistic
import dk.cachet.carp.dsp.portal.api.WorkflowArtifact
import dk.cachet.carp.dsp.portal.store.StateStore
import dk.cachet.carp.dsp.portal.store.WorkflowStore
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * Simulated execution.
 *
 * State is derived from elapsed wall time rather than driven by a background
 * job. Nothing is mutated after a run starts, so polling is naturally
 * consistent and there is no scheduler to shut down.
 *
 * Where a recorded run exists for the workflow, artefacts are the real files
 * from that run. See [RecordedRun].
 */
object RunSimulator {

    /** Wall time a step appears to take when no measured figure is known. */
    private const val STEP_MILLIS = 3_500L

    /**
     * Measured per-step wall times, from carp.dsp.demo/eval_results.
     *
     * Using the real figures rather than a flat interval means the demo paces
     * like the pipeline actually does - the two plotting steps are visibly
     * quicker than gait sequence detection. Keyed by workflow, then step id.
     */
    private val MEASURED_MILLIS: Map<String, Map<String, Long>> = mapOf(
        "mobgap-gait-analysis" to mapOf(
            "import-data" to 13_717L,
            "gsd" to 13_790L,
            "icd" to 6_328L,
            "per-sec-params" to 4_779L,
            "wba" to 2_316L,
            "aggregate" to 1_825L,
            "plot-wb-params" to 6_302L,
            "plot-aggregated-dmos" to 2_318L,
        ),
    )

    /**
     * Runs are scaled to this total, so a 64-second pipeline does not stall a
     * live demo. Relative step weights are preserved.
     */
    private const val TARGET_TOTAL_MILLIS = 24_000L

    private data class Run(
        val executionId: String,
        val workflowId: String,
        val studyId: String,
        val startedAtMillis: Long,
        val order: List<StepSpec>,
        /** Scaled duration per step, index-aligned with [order]. */
        val durations: List<Long>,
        val context: RunContext? = null,
    ) {
        /** Start offset of each step, plus the total as a final element. */
        val offsets: List<Long> = durations.runningFold(0L) { acc, d -> acc + d }
        val totalMillis: Long get() = offsets.last()
    }

    private val runs = ConcurrentHashMap<String, Run>()

    /**
     * A run as saved.
     *
     * Steps are stored as ids rather than as whole [StepSpec]s: the workflow is
     * the authority on its own steps, and copying them here would let a saved
     * run drift from the workflow it ran. Durations are kept because they are
     * the run's own timing, not the workflow's.
     */
    @kotlinx.serialization.Serializable
    data class RunRecord(
        val executionId: String,
        val workflowId: String,
        val studyId: String,
        val startedAtMillis: Long,
        val stepIds: List<String>,
        val durations: List<Long>,
        val context: RunContext? = null,
    )

    fun snapshot(): List<RunRecord> = runs.values.map { run ->
        RunRecord(
            executionId = run.executionId,
            workflowId = run.workflowId,
            studyId = run.studyId,
            startedAtMillis = run.startedAtMillis,
            stepIds = run.order.map { it.id },
            durations = run.durations,
            context = run.context,
        )
    }

    /**
     * Rebuilds runs from saved records.
     *
     * A run whose workflow is no longer in the study is dropped: without the
     * step definitions there is nothing to show but an id, and keeping a husk
     * would put rows in the Runs table that open onto nothing.
     */
    fun restore(records: List<RunRecord>) {
        records.forEach { record ->
            val steps = WorkflowStore.get(record.workflowId)
                ?.definition
                ?.steps
                ?.associateBy { it.id }
                ?: return@forEach

            val order = record.stepIds.mapNotNull { steps[it] }
            if (order.size != record.stepIds.size) return@forEach

            runs[record.executionId] = Run(
                executionId = record.executionId,
                workflowId = record.workflowId,
                studyId = record.studyId,
                startedAtMillis = record.startedAtMillis,
                order = order,
                durations = record.durations,
                context = record.context,
            )
        }
    }

    /** How far in the past a seeded run appears to have started. */
    private const val SEEDED_AGE_MILLIS = 2 * 60 * 60 * 1000L

    /**
     * Adds one already-finished run for every workflow that has recorded
     * output.
     *
     * Runs are held in memory, so without this the Runs page is empty after
     * every restart - constant during development, and one restart away from
     * an empty demo. Seeding also means the real rows, plot and downloads are
     * there immediately, with no wait for a fresh run to finish.
     *
     * The execution id is derived from the workflow id, so a seeded run keeps
     * the same URL across restarts and links to it stay good. Idempotent.
     */
    fun seedRecordedRuns() {
        RecordedRun.workflowIds().forEach { workflowId ->
            val detail = WorkflowStore.get(workflowId) ?: return@forEach
            val order = topologicalOrder(detail.definition.steps)
            if (order.isEmpty()) return@forEach

            val durations = scaledDurations(workflowId, order)
            val executionId = stableUuid("seed:$workflowId")

            runs.putIfAbsent(
                executionId,
                Run(
                    executionId = executionId,
                    workflowId = workflowId,
                    studyId = WorkflowStore.DEMO_STUDY_ID,
                    // Far enough back that every step window has passed.
                    startedAtMillis = System.currentTimeMillis() - SEEDED_AGE_MILLIS,
                    order = order,
                    durations = durations,
                ),
            )
        }
    }

    fun start(workflowId: String, studyId: String, context: RunContext? = null): ExecutorState? {
        val detail = WorkflowStore.get(workflowId) ?: return null
        val order = topologicalOrder(detail.definition.steps)

        val run = Run(
            executionId = UUID.randomUUID().toString(),
            workflowId = workflowId,
            studyId = studyId,
            startedAtMillis = System.currentTimeMillis(),
            order = order,
            durations = scaledDurations(workflowId, order),
            context = context,
        )
        runs[run.executionId] = run
        StateStore.save()
        return stateOf(run)
    }

    fun state(executionId: String): ExecutorState? = runs[executionId]?.let { stateOf(it) }

    fun report(executionId: String): ExecutionReport? {
        val run = runs[executionId] ?: return null
        val completed = completedStepCount(run)

        return ExecutionReport(
            runId = run.executionId,
            planId = run.workflowId,
            startedAt = iso(run.startedAtMillis),
            finishedAt = if (isFinished(run)) iso(finishMillis(run)) else null,
            status = statusOf(run),
            stepResults = run.order.mapIndexed { index, step ->
                stepResult(run, step, index, completed)
            },
        )
    }

    fun artifacts(executionId: String): List<ArtifactEntry> {
        val run = runs[executionId] ?: return emptyList()
        val completed = completedStepCount(run)

        return run.order.take(completed).flatMap { step ->
            outputIdsOf(run.workflowId, step).map { outputId ->
                entryFor(run, step, outputId)
            }
        }
    }

    fun runsFor(studyId: String): List<ExecutorState> =
        runs.values.filter { it.studyId == studyId }
            .sortedByDescending { it.startedAtMillis }
            .map { stateOf(it) }

    fun allRuns(): List<ExecutorState> = runs.values.map { stateOf(it) }

    fun lastRunFor(workflowId: String): ExecutorState? =
        runs.values.filter { it.workflowId == workflowId }
            .maxByOrNull { it.startedAtMillis }
            ?.let { stateOf(it) }

    /** The workflow a run belongs to, for serving its recorded outputs. */
    fun workflowOf(executionId: String): String? = runs[executionId]?.workflowId

    // ---- timing -------------------------------------------------------------

    /**
     * Measured durations where known, a flat fallback otherwise, then scaled so
     * every run finishes in about [TARGET_TOTAL_MILLIS].
     */
    private fun scaledDurations(workflowId: String, order: List<StepSpec>): List<Long> {
        val measured = MEASURED_MILLIS[workflowId]
        val raw = order.map { measured?.get(it.id) ?: STEP_MILLIS }
        val total = raw.sum().coerceAtLeast(1L)
        val factor = TARGET_TOTAL_MILLIS.toDouble() / total
        // Never below 400ms, or fast steps flash past unreadably.
        return raw.map { max(400L, (it * factor).toLong()) }
    }

    private fun elapsed(run: Run) = System.currentTimeMillis() - run.startedAtMillis

    /** Number of steps whose window has fully passed. */
    private fun completedStepCount(run: Run): Int {
        val e = elapsed(run)
        return run.offsets.drop(1).count { it <= e }
    }

    private fun isFinished(run: Run) = completedStepCount(run) >= run.order.size

    private fun finishMillis(run: Run) = run.startedAtMillis + run.totalMillis

    private fun statusOf(run: Run) =
        if (isFinished(run)) ExecutionStatus.SUCCEEDED else ExecutionStatus.RUNNING

    private fun stateOf(run: Run) = ExecutorState(
        executionId = run.executionId,
        status = statusOf(run),
        startedAt = iso(run.startedAtMillis),
        completedAt = if (isFinished(run)) iso(finishMillis(run)) else null,
        workflowId = run.workflowId,
        studyId = run.studyId,
        context = run.context,
    )

    // ---- report -------------------------------------------------------------

    private fun stepResult(
        run: Run,
        step: StepSpec,
        index: Int,
        completed: Int,
    ): StepRunResult {
        val status = when {
            index < completed -> ExecutionStatus.SUCCEEDED
            index == completed -> ExecutionStatus.RUNNING
            else -> ExecutionStatus.PENDING
        }
        val startMillis = run.startedAtMillis + run.offsets[index]

        return StepRunResult(
            stepMetadata = StepRunMetadata(
                id = stableUuid(step.id),
                name = step.displayName,
                description = step.metadata?.description ?: step.uses,
                descriptorId = step.id,
            ),
            status = status,
            startedAt = if (status != ExecutionStatus.PENDING) iso(startMillis) else null,
            finishedAt = if (status == ExecutionStatus.SUCCEEDED) {
                iso(startMillis + run.durations[index])
            } else {
                null
            },
            outputs = if (status == ExecutionStatus.SUCCEEDED) {
                outputIdsOf(run.workflowId, step).map { outputId ->
                    producedOutput(run.workflowId, step, outputId)
                }
            } else {
                emptyList()
            },
            logTail = logFor(step, status),
        )
    }

    /**
     * Output ids for a step. Taken from the recorded run where one exists,
     * since library steps declare no outputs in the workflow file itself.
     */
    private fun outputIdsOf(workflowId: String, step: StepSpec): List<String> {
        val recorded = RecordedRun.manifestFor(workflowId)
            ?.outputs
            ?.filter { it.stepId == step.id }
            ?.map { it.outputId }
        return if (!recorded.isNullOrEmpty()) recorded else step.outputs.map { it.id }
    }

    private fun producedOutput(
        workflowId: String,
        step: StepSpec,
        outputId: String,
    ): ProducedOutputRef {
        val recorded = RecordedRun.entry(workflowId, step.id, outputId)
        return if (recorded != null) {
            ProducedOutputRef(
                outputId = outputId,
                location = ResourceRef(value = recorded.path),
                sizeBytes = recorded.sizeBytes,
                sha256 = recorded.sha256,
                contentType = recorded.contentType,
            )
        } else {
            ProducedOutputRef(
                outputId = outputId,
                location = ResourceRef(value = "steps/${step.id}/outputs/$outputId"),
                sizeBytes = syntheticSize(outputId),
                sha256 = stableSha(outputId),
                contentType = mimeFor(formatOf(step, outputId)),
            )
        }
    }

    private fun logFor(step: StepSpec, status: ExecutionStatus): List<String> {
        val what = step.task?.executable
            ?: step.task?.entryPoint?.scriptPath
            ?: step.uses
            ?: step.task?.type
            ?: "step"

        return when (status) {
            ExecutionStatus.PENDING -> emptyList()
            ExecutionStatus.RUNNING -> listOf(
                "[${step.id}] resolving environment '${step.environmentId ?: "default"}'",
                "[${step.id}] running $what",
            )

            else -> listOf(
                "[${step.id}] resolving environment '${step.environmentId ?: "default"}'",
                "[${step.id}] running $what",
                "[${step.id}] done",
            )
        }
    }

    // ---- artefacts ----------------------------------------------------------

    private fun entryFor(run: Run, step: StepSpec, outputId: String): ArtifactEntry {
        val recorded = RecordedRun.entry(run.workflowId, step.id, outputId)

        // Real file from a recorded run: real size, digest and bytes.
        if (recorded != null) {
            val reference = ArtifactReference(
                uri = recorded.path,
                mimeType = recorded.contentType,
                sizeBytes = recorded.sizeBytes,
                sha256 = recorded.sha256,
            )
            val preview = RecordedRun.preview(run.workflowId, step.id, outputId)

            return ArtifactEntry(
                stepId = step.id,
                outputId = outputId,
                artifact = artifactFromMime(recorded.contentType, outputId, reference, preview?.columns),
                downloadUrl = "/api/artefacts/${run.executionId}/${step.id}/$outputId",
                preview = preview,
            )
        }

        // No recorded run for this workflow yet - shape only.
        val reference = ArtifactReference(
            uri = "steps/${step.id}/outputs/$outputId",
            mimeType = mimeFor(formatOf(step, outputId)),
            sizeBytes = syntheticSize(outputId),
            sha256 = stableSha(outputId),
        )
        return ArtifactEntry(
            stepId = step.id,
            outputId = outputId,
            artifact = artifactFromMime(reference.mimeType, outputId, reference, columnsFor(outputId)),
        )
    }

    /** Maps a content type onto the artefact type the UI will receive. */
    private fun artifactFromMime(
        mime: String,
        outputId: String,
        reference: ArtifactReference,
        columns: List<String>?,
    ): WorkflowArtifact = when {
        mime.startsWith("image/") -> ImageArtifact(reference = reference)

        mime == "text/csv" || mime.contains("parquet") -> FeatureTable(
            reference = reference,
            columns = columns?.takeIf { it.isNotEmpty() } ?: columnsFor(outputId),
            rowCount = null,
        )

        mime == "application/json" -> SummaryStatistic(
            name = outputId,
            value = 0.0,
            sampleCount = 1,
        )

        else -> ComputedFeature(name = outputId, value = 0.0)
    }

    private fun formatOf(step: StepSpec, outputId: String): String? =
        step.outputs.firstOrNull { it.id == outputId }?.descriptor?.fileFormat

    /** Column names for outputs with no recorded file, so tables look real. */
    private fun columnsFor(outputId: String): List<String> = when {
        outputId.contains("gs-list") -> listOf("gs_id", "start", "end")
        outputId.contains("ic-list") -> listOf("gs_id", "ic_id", "ic", "lr_label")
        outputId.contains("turn-list") -> listOf("gs_id", "turn_id", "start", "end", "duration_s")
        outputId.contains("per-sec") -> listOf("gs_id", "sec_center", "cadence_spm", "stride_length_m", "walking_speed_mps")
        outputId.contains("stride-list") -> listOf("s_id", "start", "end", "lr_label", "walking_speed_mps", "wb_id")
        outputId.contains("wb-params") -> listOf("wb_id", "start", "end", "n_strides", "cadence_spm", "walking_speed_mps")
        outputId.contains("dmos") -> listOf("walking_speed_mps", "cadence_spm", "stride_length_m", "n_wb")
        outputId.contains("imu") -> listOf("acc_x", "acc_y", "acc_z", "gyr_x", "gyr_y", "gyr_z")
        else -> listOf("id", "value")
    }

    private fun mimeFor(format: String?): String = when (format?.lowercase()) {
        "csv" -> "text/csv"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "svg" -> "image/svg+xml"
        "parquet" -> "application/vnd.apache.parquet"
        else -> "application/octet-stream"
    }

    // ---- deterministic filler ----------------------------------------------
    // Same id always yields the same size and digest, so the UI does not
    // shuffle between polls.

    private fun syntheticSize(seed: String): Long = 4_096L + abs(seed.hashCode()) % 2_000_000L

    private fun stableSha(seed: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun stableUuid(seed: String): String =
        UUID.nameUUIDFromBytes(seed.toByteArray()).toString()

    private fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    /** Kahn's algorithm. Steps with no remaining dependency go first. */
    private fun topologicalOrder(steps: List<StepSpec>): List<StepSpec> {
        val byId = steps.associateBy { it.id }
        val remaining = steps.associate { step ->
            step.id to step.dependsOn.filter { it in byId }.toMutableSet()
        }.toMutableMap()
        val ordered = mutableListOf<StepSpec>()

        while (remaining.isNotEmpty()) {
            val ready = remaining.filterValues { it.isEmpty() }.keys.sorted()
            if (ready.isEmpty()) {
                // Cycle. Validation rejects these, but never loop forever.
                ordered += remaining.keys.sorted().mapNotNull { byId[it] }
                break
            }
            ready.forEach { id ->
                byId[id]?.let { ordered += it }
                remaining.remove(id)
            }
            remaining.values.forEach { it.removeAll(ready.toSet()) }
        }

        return ordered
    }
}
