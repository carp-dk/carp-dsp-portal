package dk.cachet.carp.dsp.portal.api

import dk.cachet.carp.dsp.portal.ApiError
import dk.cachet.carp.dsp.portal.catalogue.DataCatalogue
import dk.cachet.carp.dsp.portal.store.BindingStore
import dk.cachet.carp.dsp.portal.store.BundleStore
import dk.cachet.carp.dsp.portal.store.WorkflowStore
import dk.cachet.carp.dsp.portal.store.ProtocolStore
import dk.cachet.carp.dsp.portal.store.toDto
import dk.cachet.carp.dsp.portal.catalogue.RepoSource
import dk.cachet.carp.dsp.portal.mock.RecordedRun
import dk.cachet.carp.dsp.portal.mock.RunSimulator
import dk.cachet.carp.dsp.portal.run.DspRunner
import dk.cachet.carp.dsp.portal.run.EnvironmentReuseSetting
import dk.cachet.carp.dsp.portal.run.Environments
import dk.cachet.carp.dsp.portal.run.RunHistory
import dk.cachet.carp.dsp.portal.run.RunMode
import dk.cachet.carp.dsp.portal.run.EngineValidator
import dk.cachet.carp.dsp.portal.run.RunArtefacts
import dk.cachet.carp.dsp.portal.run.Cadence
import dk.cachet.carp.dsp.portal.run.RunLauncher
import dk.cachet.carp.dsp.portal.run.Scheduler
import dk.cachet.carp.dsp.portal.run.TimeBindings
import dk.cachet.carp.dsp.portal.run.instantOrNull
import dk.cachet.carp.dsp.portal.run.RunNotStarted
import dk.cachet.carp.dsp.portal.store.ScheduleStore
import dk.cachet.carp.dsp.portal.catalogue.StepLibrary
import dk.cachet.carp.dsp.portal.catalogue.WorkflowLibrary
import dk.cachet.carp.dsp.portal.store.WorkflowParseException
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.io.readByteArray

/**
 * The mock's RPC surface. One endpoint per service, mirroring how CARP WS
 * exposes application services (W4-1: "no hand-rolled routes").
 *
 * Every handler here returns a shape from Dto.kt. Wiring the real services
 * means replacing the bodies; the front end does not change.
 */
fun Route.analyticsRoutes() {
    route("/analytics") {

        post("/WorkflowService") {
            when (val request = call.receive<WorkflowServiceRequest>()) {
                is ListWorkflows -> call.respond(RunHistory.withLastRun(WorkflowStore.list()))

                is GetWorkflow -> {
                    val detail = WorkflowStore.get(request.workflowId)
                    if (detail == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiError("WorkflowNotFound", "No workflow '${request.workflowId}'."),
                        )
                    } else {
                        call.respond(RunHistory.withLastRun(detail))
                    }
                }

                is CreateWorkflow -> {
                    val detail = try {
                        WorkflowStore.parse(request.yaml, draft = request.draft)
                            .also { if (!request.draft) requireValid(request.yaml) }
                    } catch (e: WorkflowParseException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError(
                                "WorkflowValidationError",
                                e.message ?: "The workflow could not be read.",
                            ),
                        )
                        null
                    }

                    if (detail != null) {
                        val id = detail.summary.workflowId
                        // The id is the key, so saving over one already in the
                        // study would silently replace it. Make that a choice.
                        if (WorkflowStore.has(id) && !request.overwrite) {
                            call.respond(
                                HttpStatusCode.Conflict,
                                ApiError(
                                    "WorkflowIdInUse",
                                    "'$id' is already in this study. Save again to replace it, " +
                                        "or change the workflow id.",
                                ),
                            )
                        } else {
                            // A definition alone carries no files, so any a
                            // bundle saved under this id no longer belong to it.
                            BundleStore.remove(id)
                            call.respond(WorkflowStore.put(detail))
                        }
                    }
                }

                is DeleteWorkflow ->
                    if (WorkflowStore.remove(request.workflowId)) {
                        BundleStore.remove(request.workflowId)
                        BindingStore.remove(request.workflowId)
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiError("WorkflowNotFound", "No workflow '${request.workflowId}'."),
                        )
                    }

                is ValidateBundle -> call.respond(
                    EngineValidator.validate(request.paths.toSet(), request.yaml),
                )

                is ParseWorkflow -> {
                    val detail = parseOrRespond(call, request.yaml)
                    if (detail != null) call.respond(detail)
                }

                is ListDemoWorkflows -> call.respond(WorkflowLibrary.list())

                is AddDemoWorkflow -> {
                    try {
                        call.respond(WorkflowLibrary.addToStudy(request.path))
                    } catch (e: WorkflowParseException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError(
                                "WorkflowValidationError",
                                e.message ?: "This example could not be added.",
                            ),
                        )
                    }
                }
            }
        }

        post("/ExecutionService") {
            when (val request = call.receive<ExecutionServiceRequest>()) {
                // A workflow already in the study. In real mode this is the
                // same engine path as an upload, reading the stored YAML.
                is ExecuteWorkflow -> {
                    val detail = WorkflowStore.get(request.workflowId)
                    if (detail == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiError("WorkflowNotFound", "No workflow '${request.workflowId}'."),
                        )
                    } else {
                        startRun(
                            call,
                            detail.rawYaml,
                            request.workflowId,
                            request.studyId,
                            packageFiles = BundleStore.load(request.workflowId),
                        )
                    }
                }

                is ExecuteWorkflowFromDefinition -> {
                    val detail = parseOrRespond(call, request.yaml)
                    if (detail != null) {
                        remember(detail)
                        startRun(call, request.yaml, detail.summary.workflowId, request.studyId)
                    }
                }

                is CancelExecution -> {
                    val state = DspRunner.cancel(request.executionId)
                    if (state == null) {
                        call.respond(
                            HttpStatusCode.Conflict,
                            ApiError(
                                "NotCancellable",
                                "Run '${request.executionId}' is not running, so there is nothing to stop.",
                            ),
                        )
                    } else {
                        call.respond(state)
                    }
                }

                is GetExecutionState -> {
                    val state = DspRunner.state(request.executionId)
                        ?: RunSimulator.state(request.executionId)
                    if (state == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiError("ExecutionNotFound", "No run '${request.executionId}'."),
                        )
                    } else {
                        call.respond(state)
                    }
                }

                is GetExecutionResult -> {
                    val report = DspRunner.report(request.executionId)
                        ?: RunSimulator.report(request.executionId)
                    if (report == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiError("ExecutionNotFound", "No run '${request.executionId}'."),
                        )
                    } else {
                        call.respond(report)
                    }
                }

                is FindExecutions -> call.respond(
                    (DspRunner.runsFor(request.studyId) + RunSimulator.runsFor(request.studyId))
                        .filter { request.workflowId == null || it.workflowId == request.workflowId },
                )
            }
        }

        post("/ArtefactRegistryService") {
            when (val request = call.receive<ArtefactRegistryRequest>()) {
                is GetRunArtefacts -> call.respond(
                    if (DspRunner.knows(request.executionId)) {
                        RunArtefacts.entries(request.executionId)
                    } else {
                        RunSimulator.artifacts(request.executionId)
                    },
                )
            }
        }

        post("/StudyDataService") {
            when (val request = call.receive<StudyDataServiceRequest>()) {
                is ListDataSources -> call.respond(DataCatalogue.list())

                is ListProtocols -> call.respond(
                    ProtocolStore.list().map { it.toDto(active = it == ProtocolStore.active()) },
                )

                is UploadProtocol -> {
                    try {
                        val snapshot = ProtocolStore.add(request.json)
                        call.respond(snapshot.toDto(active = true))
                    } catch (e: WorkflowParseException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError(
                                "ProtocolValidationError",
                                e.message ?: "The protocol could not be read.",
                            ),
                        )
                    }
                }

                is ActivateProtocol -> {
                    val snapshot = ProtocolStore.activate(request.id, request.version)
                    if (snapshot == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiError(
                                "ProtocolNotFound",
                                "No protocol '${request.id}' at version ${request.version}.",
                            ),
                        )
                    } else {
                        call.respond(snapshot.toDto(active = true))
                    }
                }

                is AddDataFile -> call.respond(
                    DataCatalogue.addFile(request.path, request.sizeBytes, request.description),
                )

                is AddExternalDataset -> call.respond(
                    DataCatalogue.addExternal(request.uri, request.citation, request.description),
                )
            }
        }

        post("/StepLibraryService") {
            when (val request = call.receive<StepLibraryServiceRequest>()) {
                is ListLibrarySteps -> call.respond(StepLibrary.list())

                is GetLibraryStep -> {
                    val entry = StepLibrary.get(request.stepId)
                    if (entry == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiError("StepNotFound", "No library step '${request.stepId}'."),
                        )
                    } else {
                        call.respond(entry)
                    }
                }

                is CreateLibraryStep -> {
                    try {
                        call.respond(StepLibrary.add(request.yaml))
                    } catch (e: WorkflowParseException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError(
                                "StepValidationError",
                                e.message ?: "The step file could not be read.",
                            ),
                        )
                    }
                }
            }
        }

        // INVENTED service - see Requests.kt. Fired by run/Scheduler.kt.
        post("/ScheduleService") {
            when (val request = call.receive<ScheduleServiceRequest>()) {
                is CreateSchedule -> {
                    val startAt = request.startAt?.takeIf { it.isNotBlank() }
                    when {
                        Cadence.of(request.cadence) == null -> call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError("UnknownCadence", "'${request.cadence}' is not a cadence."),
                        )

                        startAt != null && instantOrNull(startAt) == null -> call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError("BadStart", "'$startAt' is not a date-time with an offset."),
                        )

                        else -> call.respond(
                            Scheduler.view(
                                ScheduleStore.create(
                                    studyId = request.studyId,
                                    workflowId = request.workflowId,
                                    cadence = request.cadence,
                                    startAt = startAt,
                                ),
                            ),
                        )
                    }
                }

                is ListSchedules -> call.respond(
                    ScheduleStore.list(request.studyId, request.workflowId).map { Scheduler.view(it) },
                )

                is SetScheduleEnabled -> {
                    val schedule = ScheduleStore.setEnabled(request.scheduleId, request.enabled)
                    if (schedule == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiError("ScheduleNotFound", "No schedule '${request.scheduleId}'."),
                        )
                    } else {
                        call.respond(Scheduler.view(schedule))
                    }
                }

                is DeleteSchedule ->
                    if (ScheduleStore.delete(request.scheduleId)) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiError("ScheduleNotFound", "No schedule '${request.scheduleId}'."),
                        )
                    }
            }
        }
    }

    /**
     * Source files from the repo, as text.
     *
     * A step's implementation, tests and reference fixtures, and the scripts
     * demo workflows reference. Plain GETs so the UI can link to them and the
     * browser can open one directly.
     *
     * Read-only, and the paths are resolved through RepoSource, which rejects
     * traversal and skips caches and pixi environments.
     */
    get("/repo/steps/{stepId}/{path...}") {
        val stepId = call.parameters["stepId"].orEmpty()
        val path = call.parameters.getAll("path").orEmpty().joinToString("/")
        val entry = StepLibrary.get(stepId)
        val text = entry?.let { RepoSource.stepFileText(it.dir, path) }

        if (text == null) {
            call.respond(
                HttpStatusCode.NotFound,
                ApiError("FileNotFound", "No '$path' in step '$stepId'."),
            )
        } else {
            call.respondText(text, ContentType.Text.Plain)
        }
    }

    /**
     * The run mode, read and set.
     *
     * Portal-only, and deliberately not on a service: CARP WS runs workflows one
     * way. This exists so a live demo can fall back to the simulation without a
     * restart, and it goes when the mock shell does.
     */
    get("/mode") {
        call.respond(RunModeDto(RunMode.current.name.lowercase()))
    }

    post("/mode") {
        val requested = call.receive<RunModeDto>().mode
        val mode = RunMode.parse(requested)

        if (mode == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError("UnknownMode", "'$requested' is not a run mode. Use 'real' or 'simulated'."),
            )
        } else {
            RunMode.current = mode
            call.respond(RunModeDto(mode.name.lowercase()))
        }
    }

    /** The environments on the state volume, solved or not. Portal-only. */
    get("/environments") {
        call.respond(Environments.list())
    }

    /**
     * A workflow's time parameters and how runs set them. Portal-only.
     *
     * The workflow file is never rewritten; the bindings are kept beside it and
     * applied to a copy when a run starts.
     */
    get("/workflows/{workflowId}/time-parameters") {
        val workflowId = call.parameters["workflowId"].orEmpty()
        val detail = WorkflowStore.get(workflowId)

        if (detail == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("WorkflowNotFound", "No workflow '$workflowId'."))
        } else {
            call.respond(TimeBindings.view(workflowId, detail.rawYaml))
        }
    }

    put("/workflows/{workflowId}/time-parameters") {
        val workflowId = call.parameters["workflowId"].orEmpty()
        val detail = WorkflowStore.get(workflowId)
        val requested = call.receive<WorkflowBindings>().copy(workflowId = workflowId)

        if (detail == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("WorkflowNotFound", "No workflow '$workflowId'."))
            return@put
        }

        try {
            TimeBindings.save(requested, detail.rawYaml)
            call.respond(TimeBindings.view(workflowId, detail.rawYaml))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiError("InvalidBindings", e.message.orEmpty()))
        }
    }

    /**
     * How far environment reuse may stretch. Portal-only, like the run mode.
     *
     * `exact` builds unless a digest-identical environment exists; `superset`
     * lets a larger one serve. See run/EnvironmentReuse.kt for why the default
     * is the strict one.
     */
    get("/environments/reuse") {
        call.respond(EnvironmentReuseDto(EnvironmentReuseSetting.mode))
    }

    post("/environments/reuse") {
        val requested = call.receive<EnvironmentReuseDto>().mode
        val value = EnvironmentReuseSetting.parse(requested)

        if (value == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError("UnknownReuseMode", "'$requested' is not a reuse mode. Use 'exact' or 'superset'."),
            )
        } else {
            EnvironmentReuseSetting.set(value)
            call.respond(EnvironmentReuseDto(EnvironmentReuseSetting.mode))
        }
    }

    get("/repo/scripts/{path...}") {
        val path = call.parameters.getAll("path").orEmpty().joinToString("/")
        val text = RepoSource.scriptText(path)

        if (text == null) {
            call.respond(
                HttpStatusCode.NotFound,
                ApiError("FileNotFound", "No script at '$path'."),
            )
        } else {
            call.respondText(text, ContentType.Text.Plain)
        }
    }

    /**
     * Validate a zipped bundle.
     *
     * The archive is the raw request body rather than a multipart part or a
     * base64 field: the browser can post a File directly, and reading the
     * stream sidesteps content negotiation entirely.
     *
     * Unpacking happens here with java.util.zip, so a folder drop and a zip
     * reach the same validator with the same payload - a set of paths plus the
     * workflow text.
     */
    post("/bundles/validate") {
        val bytes = call.receiveStream().readBytes()

        if (bytes.isEmpty()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError("NoFile", "Post the archive as the request body."),
            )
            return@post
        }

        val (paths, yaml) = try {
            unzip(bytes)
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError("BadArchive", e.message ?: "The archive could not be read."),
            )
            return@post
        }

        call.respond(EngineValidator.validate(paths, yaml))
    }

    /**
     * Runs an uploaded bundle: the workflow plus the files its steps reference.
     *
     * Two shapes, because the Upload page has two: a zip goes up as the raw body,
     * and a dropped folder goes up as multipart with each part named by its path
     * within the bundle. Both land as the same map of files.
     *
     * Portal-only, like `/bundles/validate`: neither shape is something the
     * analytics RPC surface carries. `ExecuteWorkflowFromDefinition` remains the
     * path for a workflow that needs nothing but the step library.
     */
    post("/bundles/execute") {
        val studyId = call.request.queryParameters["studyId"] ?: "study-1"

        val (entries, workflowKey) = try {
            readBundle(call)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ApiError("BadArchive", e.message ?: "The upload could not be read."))
            return@post
        }

        if (workflowKey == null) {
            call.respond(HttpStatusCode.BadRequest, ApiError("NoWorkflow", "The upload holds no workflow YAML."))
            return@post
        }

        val yaml = entries.getValue(workflowKey).decodeToString()
        val detail = parseOrRespond(call, yaml) ?: return@post
        remember(detail, entries - workflowKey)

        startRun(
            call = call,
            yaml = yaml,
            workflowId = detail.summary.workflowId,
            studyId = studyId,
            packageFiles = entries - workflowKey,
        )
    }

    /**
     * What a step printed.
     *
     * The engine records stdout on the step's `StepRunDetail`, inline as a `data:`
     * URI for a short run or as a file for a long one. Served as text rather than
     * carried in the report, so a chatty step does not weigh down every poll.
     */
    get("/runs/{executionId}/steps/{stepId}/output") {
        val executionId = call.parameters["executionId"].orEmpty()
        val stepId = call.parameters["stepId"].orEmpty()

        val text = RunArtefacts.stepOutput(executionId, stepId)
        if (text == null) {
            call.respond(
                HttpStatusCode.NotFound,
                ApiError("OutputNotFound", "No recorded output for step '$stepId' in run '$executionId'."),
            )
        } else {
            call.respondText(text, ContentType.Text.Plain)
        }
    }

    /**
     * Artefact bytes.
     *
     * A plain GET rather than an RPC call, because the browser has to be able
     * to follow it from an <a download> and an <img src>. In CARP WS this
     * becomes a signed URL into object storage; the front end still just
     * follows `ArtifactEntry.downloadUrl`.
     */
    /**
     * Saves an uploaded bundle's workflow to the study.
     *
     * The same two shapes `/bundles/execute` takes, for the same reason: a zip
     * never gives the browser the workflow text, so it cannot use
     * `CreateWorkflow`. Without this, a bundle that fails validation could not be
     * kept at all - which is exactly when you most want to keep it.
     *
     * `draft=true` skips the validation gate. A draft is work in progress; the
     * file only has to be readable enough to have an id to store it under.
     */
    post("/bundles/save") {
        val draft = call.request.queryParameters["draft"]?.toBoolean() ?: false
        val overwrite = call.request.queryParameters["overwrite"]?.toBoolean() ?: false

        val (entries, workflowKey) = try {
            readBundle(call)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ApiError("BadArchive", e.message ?: "The upload could not be read."))
            return@post
        }

        if (workflowKey == null) {
            call.respond(HttpStatusCode.BadRequest, ApiError("NoWorkflow", "The upload holds no workflow YAML."))
            return@post
        }

        val yaml = entries.getValue(workflowKey).decodeToString()
        val detail = try {
            WorkflowStore.parse(yaml, draft = draft)
        } catch (e: WorkflowParseException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError("WorkflowValidationError", e.message ?: "The workflow could not be read."),
            )
            return@post
        }

        val report = if (draft) null else EngineValidator.validate(entries.keys, yaml)
        if (report != null && !report.valid) {
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ApiError(
                    "WorkflowValidationError",
                    "This bundle does not validate. Save it as a draft to keep working on it.",
                ),
            )
            return@post
        }

        val id = detail.summary.workflowId
        if (WorkflowStore.has(id) && !overwrite) {
            call.respond(
                HttpStatusCode.Conflict,
                ApiError(
                    "WorkflowIdInUse",
                    "'$id' is already in this study. Save again to replace it, or change the workflow id.",
                ),
            )
            return@post
        }

        BundleStore.save(id, entries - workflowKey)
        call.respond(WorkflowStore.put(detail))
    }

    get("/artefacts/{executionId}/{stepId}/{outputId}") {
        val executionId = call.parameters["executionId"].orEmpty()
        val stepId = call.parameters["stepId"].orEmpty()
        val outputId = call.parameters["outputId"].orEmpty()

        // A real run serves from its own workspace; anything else falls back to
        // the recorded fixture the mock pages still use.
        val produced = RunArtefacts.file(executionId, stepId, outputId)
        if (produced != null) {
            val (file, contentType) = produced
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileName, file.name)
                    .toString(),
            )
            call.respondBytes(file.readBytes(), ContentType.parse(contentType))
            return@get
        }

        val workflowId = RunSimulator.workflowOf(executionId)
        val entry = workflowId?.let { RecordedRun.entry(it, stepId, outputId) }
        val bytes = workflowId?.let { RecordedRun.bytes(it, stepId, outputId) }

        if (entry == null || bytes == null) {
            call.respond(
                HttpStatusCode.NotFound,
                ApiError(
                    "ArtefactNotFound",
                    "No recorded output '$outputId' for step '$stepId' in run '$executionId'.",
                ),
            )
            return@get
        }

        val fileName = entry.path.substringAfterLast('/')
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment
                .withParameter(ContentDisposition.Parameters.FileName, fileName)
                .toString(),
        )
        call.respondBytes(bytes, ContentType.parse(entry.contentType))
    }
}

/**
 * Reads a zip into its entry paths plus the workflow YAML text.
 *
 * A single top-level directory is stripped, since archiving a folder normally
 * nests everything under it and the workflow's own relative paths do not
 * account for that.
 */
private fun unzip(bytes: ByteArray): Pair<Set<String>, String?> =
    unzipEntries(bytes).let { (entries, workflowKey) ->
        entries.keys to workflowKey?.let { entries[it]?.decodeToString() }
    }

/**
 * Unpacks an archive, keeping the bytes.
 *
 * Returns every entry by its path relative to the bundle root, and the key of the
 * workflow within it. Validation only needs the paths; running the bundle needs
 * the files themselves.
 */
private fun unzipEntries(bytes: ByteArray): Pair<Map<String, ByteArray>, String?> {
    val entries = mutableMapOf<String, ByteArray>()

    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) {
                // Reject traversal before anything touches a path.
                val name = entry.name.replace('\\', '/').trim('/')
                require(!name.split('/').contains("..")) { "Unsafe path in archive: $name" }
                entries[name] = zip.readBytes()
            }
            zip.closeEntry()
        }
    }

    val prefix = commonRoot(entries.keys)
    val stripped = entries.mapKeys { it.key.removePrefix(prefix) }

    return stripped to workflowKeyOf(stripped.keys)
}

/**
 * The shallowest YAML. A step library folder inside the bundle would otherwise
 * be mistaken for the workflow.
 */
private fun workflowKeyOf(names: Set<String>): String? =
    names.filter { it.substringAfterLast('.', "") in setOf("yml", "yaml") }
        .minByOrNull { it.count { c -> c == '/' } }

/**
 * Reads a multipart upload into the same shape [unzipEntries] produces.
 *
 * A browser sends a dropped folder with each file's path in the part's filename,
 * so the wrapping directory is stripped here exactly as it is for a zip.
 */
private suspend fun collectParts(multipart: MultiPartData): Pair<Map<String, ByteArray>, String?> {
    val entries = mutableMapOf<String, ByteArray>()

    multipart.forEachPart { part ->
        if (part is PartData.FileItem) {
            val name = (part.originalFileName ?: "").replace('\\', '/').trim('/')
            require(name.isNotEmpty() && !name.split('/').contains("..")) { "Unsafe path in upload: $name" }
            entries[name] = part.provider().readRemaining().readByteArray()
        }
        part.dispose()
    }

    val prefix = commonRoot(entries.keys)
    val stripped = entries.mapKeys { it.key.removePrefix(prefix) }
    return stripped to workflowKeyOf(stripped.keys)
}

/** Single wrapping directory shared by every entry, or "". */
private fun commonRoot(names: Set<String>): String {
    if (names.isEmpty()) return ""
    val first = names.first().substringBefore('/')
    val allNested = names.all { it.startsWith("$first/") }
    return if (allNested && first.isNotEmpty()) "$first/" else ""
}

/**
 * Reads a bundle upload in either shape: a zip as the raw body, or the files as
 * multipart with each part named by its path within the bundle.
 */
private suspend fun readBundle(call: ApplicationCall): Pair<Map<String, ByteArray>, String?> =
    if (call.request.contentType().match(ContentType.MultiPart.FormData)) {
        collectParts(call.receiveMultipart())
    } else {
        val bytes = call.receiveStream().readBytes()
        require(bytes.isNotEmpty()) { "Post the archive as the request body, or the files as multipart." }
        unzipEntries(bytes)
    }

/**
 * Keeps an uploaded workflow only when the study does not already hold that id.
 *
 * Running a workflow is not saving it. Storing unconditionally meant an upload
 * quietly replaced whatever was already under that id - a real way to lose work,
 * and it happened during testing. Saving is `/bundles/save` and `CreateWorkflow`,
 * both of which ask before replacing.
 */
private fun remember(detail: WorkflowDetail, files: Map<String, ByteArray> = emptyMap()) {
    val id = detail.summary.workflowId
    if (WorkflowStore.has(id)) return

    // Kept with the workflow, or running it later from the study would stage
    // nothing and fail on the first file the bundle carried.
    BundleStore.save(id, files)
    WorkflowStore.put(detail)
}

/**
 * Starts a run, by whichever path [RunMode] currently selects.
 *
 * Real mode hands the YAML to the engine, together with the repo's scripts and
 * data files: a command step names its script as a plain argument, so nothing
 * declares it and only the caller can stage it. A bundle's own files are laid
 * over that, so an upload wins over the copy in the repo.
 *
 * A workflow that cannot be planned answers 422 with the engine's own message,
 * which is what the run view shows inline.
 */
private suspend fun startRun(
    call: ApplicationCall,
    yaml: String,
    workflowId: String,
    studyId: String,
    packageFiles: Map<String, ByteArray> = emptyMap(),
) {
    try {
        call.respond(RunLauncher.launch(yaml, workflowId, studyId, packageFiles))
    } catch (e: RunNotStarted) {
        if (e.notFound) {
            call.respond(HttpStatusCode.NotFound, ApiError("WorkflowNotFound", e.message.orEmpty()))
        } else {
            call.respond(HttpStatusCode.UnprocessableEntity, ApiError("ExecutionFailed", e.message.orEmpty()))
        }
    }
}

/**
 * Parses an uploaded workflow. Returns null after responding with the inline
 * error the upload page renders.
 *
 * Takes the call as a parameter rather than as a receiver: inside a route
 * handler `this` is the RoutingContext, so an ApplicationCall extension is not
 * in scope without spelling out `call.`.
 *
 * Kaml's message carries line and column, so it is passed through rather than
 * replaced with something vaguer.
 */
private suspend fun parseOrRespond(call: ApplicationCall, yaml: String): WorkflowDetail? =
    try {
        WorkflowStore.parse(yaml).also { requireValid(yaml) }
    } catch (e: WorkflowParseException) {
        call.respond(
            HttpStatusCode.BadRequest,
            ApiError("WorkflowValidationError", e.message ?: "The workflow could not be read."),
        )
        null
    }

/**
 * Throws with the engine's first error when [yaml] does not validate.
 *
 * @throws WorkflowParseException naming the error, and how many more there are.
 */
private fun requireValid(yaml: String) {
    val errors = EngineValidator.validateDefinition(yaml).findings.filter { it.severity == Severity.ERROR }
    val first = errors.firstOrNull() ?: return
    val more = if (errors.size > 1) " (and ${errors.size - 1} more)" else ""

    throw WorkflowParseException(first.message + more)
}
