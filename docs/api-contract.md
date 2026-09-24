# Stub API contract

What the mock's endpoints return, and why these shapes.

Everything here is copied from `carp.core-kotlin @ feature/core-analytics`.
Nothing is invented. When W4-1 lands, the stub bodies get replaced and the
React code stays as it is.

## Transport

W4-1's DoD: services are exposed "via the existing request/invoker RPC pattern
- no hand-rolled routes". So the mock mirrors that instead of REST.

One endpoint per service. The body is a serialised request object; the response
is the service result.

```
POST /api/analytics/WorkflowService
POST /api/analytics/ExecutionService
POST /api/analytics/ArtefactRegistryService
POST /api/analytics/ScheduleService        <- invented, see below
```

**`ScheduleService` mirrors nothing.** The epic removed
`ScheduleManagementService` - it "exists only to drive triggers" - so there is
no type in core to copy. Its shapes are the portal's own and will change when
scheduling moves onto core's protocol triggers. Every other request on this page
is a mirror; this one is not.

Schedules fire from `run/Scheduler.kt`. A schedule stores only what the user
chose; when it last fired and where its next window starts are read off its
runs, each of which records a `context` (trigger, schedule, fire time, window
and the values it was given) on its `ExecutorState`.

**Time parameters are portal-only REST, beside the workflow:**

```
GET /api/workflows/{workflowId}/time-parameters   -> what was found, how it is bound
PUT /api/workflows/{workflowId}/time-parameters   <- WorkflowBindings
```

Detection and binding are carp-dsp core's (`authoring/parameters`). The stored
workflow is never rewritten: a run gets a copy with the values set.

Request bodies are the sealed `*ServiceRequest` subclasses, discriminated by
`__type`.

Artefacts get their own service on purpose: they belong to the registry (W2-3),
not to `ExecutionService`. Keeping them apart here means the results view
already calls the right thing.

**Two departures from core, both marked in `api/Requests.kt`.** `CreateWorkflow`
and `ExecuteWorkflowFromDefinition` take raw YAML rather than a resolved
`Workflow`, because parsing server-side is what makes the upload page's
validation errors real. W4-1 moves parsing into resolution and these return to
core's shape.

## ExecutionService requests

From `analytics/infrastructure/ExecutionServiceRequest.kt`:

| Request | Fields | Returns |
|---|---|---|
| `ExecuteWorkflow` | studyId, workflowId | `ExecutorState` |
| `ExecuteWorkflowFromDefinition` | studyId, workflow | `ExecutorState` |
| `GetExecutionState` | executionId | `ExecutorState?` |
| `GetExecutionResult` | executionId | `ExecutionReport?` |
| `FindExecutions` | studyId, workflowId?, from?, to? | `List<ExecutorState>` |

`ExecuteWorkflowFromDefinition` is the one W5-v0's "Execute now" button calls.

## Result shapes

```
ExecutorState    executionId, status, startedAt, completedAt?, workflowId, studyId
ExecutionReport  runId, planId, startedAt?, finishedAt?, status,
                 stepResults[], issues[], environmentLogs
ExecutionIssue   stepMetadata?, kind, message
```

`ExecutionIssueKind` is a closed enum, so the error view can map every value to
a readable message with no fallback branch:

`WORKSPACE_ERROR`, `POLICY_VIOLATION`, `ORCHESTRATOR_ERROR`, `OUTPUT_MISSING`,
`UNEXPECTED_OUTPUT`, `ARTIFACT_COLLECTION_FAILED`, `PROCESS_FAILED`, `UNKNOWN`.

## Artefacts

Nine leaves across three branches, already implemented upstream. TypeScript
mirror in `web/src/api/artifacts.ts`.

The branch is encoded in the flat type string, so the results view groups by
prefix - `dk.cachet.carp.dsp.analytic.`, `.media.`, `.control.`.

Only `ImageArtifact` and `PlotArtifact` render inline. Everything else is a
row with type, size and a download link. `PlotArtifact.spec` travels inline, so
a plot can be re-rendered interactively with no second fetch.

## What persists

Session state is written to `DSP_STATE`, defaulting to
`data/portal-state.json`. In Docker it lives on the `portal-state` named
volume, so a rebuild does not wipe a demo in progress.

**Only user-created state is saved:** workflows added to the study, runs,
schedules, time-parameter bindings, uploaded protocols and which one is active, added data files and
external datasets, and library steps added through the portal.

**Nothing derived from carp-dsp is saved.** Steps, demo workflows, fixture
protocols and sample data are reloaded from the checkout every boot -
persisting them would pin a stale copy and quietly undo the build-time sync.

Two decisions worth knowing:

- Uploaded protocols, library steps and workflows are stored as their original
  **text**, not as parsed objects, and re-parsed on load. Those DTOs are still
  moving, and a change to one must not make an existing state file unreadable.
- A run stores **step ids**, not whole step definitions. The workflow is the
  authority on its own steps; copying them would let a saved run drift from the
  workflow it ran. A run whose workflow is gone is dropped on load rather than
  restored as an id with nothing behind it.

Writes go through a temp file and an atomic move, so a crash mid-write cannot
leave a truncated file that fails to load next boot. A state file that cannot
be read is logged and ignored, not fatal.

## Where content comes from

The step library is owned by carp-dsp and is **not copied into this repo**. It is
pulled from a checkout at build time by `:server:syncStepLibrary`, into
`build/generated-resources/steplib` - generated output, never committed, so
there is nothing here that can drift from the source.

Resolution order, mirroring how carp-dsp finds a sibling carp.core-kotlin:

1. `-PdspRepo=<path>`
2. `DSP_REPO` environment variable
3. `../carp-dsp`

If none resolves, the build fails with a message naming all three rather than
quietly producing a portal with no steps. `-PskipStepSync=true` opts out.

At runtime `DSP_REPO` still takes precedence and is read live, so a change in
the checkout shows up on restart with no rebuild.

**Docker is the exception.** The image is built with `-PskipStepSync=true`,
because a carp-dsp checkout is not in its build context and making the parent
directory the context would drag in build outputs and a vendored interpreter.
The container reads the library from the read-only mount in
`docker-compose.yml` instead. That mount is required, not optional: without it
the portal starts with an empty step library.

The same applies to demo workflows, protocols, sample data and scripts. Nothing
from carp-dsp is committed to this repo; `server/src/main/resources` now holds
only `logback.xml` and the recorded run fixture.

**Only workflows that work are synced.** `injections/` is excluded: those are
deliberately-broken clones of the mobgap pipeline, one per validation fault,
and exist to prove the validator fires rather than as pipelines to copy. That
leaves 14 demos.

Nothing else is excluded, and two near-misses are worth recording:

- The `-v2` files are not test variants. They are the same pipelines recomposed
  from library steps - 14 `uses:` against 5 inline tasks - which is the reuse
  story worth showing.
- `protocol-coupling-mixed` is tagged `eval` and calls itself an acceptance
  case, but it is the only workflow exercising `protocol` and `external`
  sources. Dropping it would leave the Data page with nothing to demonstrate.

Two shapes in that directory caught me out and are handled explicitly:

- It holds a `steps.lock` and a `raw_heart_rate.csv` alongside the YAML. Every
  entry in the workflow index is read back as a workflow, so the sync keeps
  YAML only.
- `raw_heart_rate.csv` is the file `hr-clean-library` reads. It is copied into
  `data/`, which is what lets that demo resolve its input rather than warn about
  data the repo actually ships.

The fixture exclusion is duplicated in `RepoSource.FIXTURE_DIRS`, because repo
mode walks the checkout directly. If the two lists drift, the two modes show
different catalogues.

### Still committed here

`server/src/main/resources/runs/` - the recorded activity-summary run. It is run
*output* rather than a source definition, and it came from `demo_results/`,
which is not a packaged path. Worth revisiting if that changes.

## Repo files

Declared paths are links, not text. A step's implementation, tests and
reference fixtures, and the scripts demo workflows reference, are all served
from the repo:

```
GET /api/repo/steps/{stepId}/{path}     e.g. impl/python/summarise.py
GET /api/repo/scripts/{path}            e.g. mobgap/import_data.py
```

Read-only, resolved through `RepoSource`, which rejects traversal and skips
`__pycache__`, `.pytest_cache`, `.git` and `.pixi`. That last one matters:
`scripts/eval/.pixi` holds a whole interpreter with DLLs, and walking it would
put megabytes of binaries behind a file browser.

In repo mode the directory is walked, so a file added to carp-dsp is served
without any change here. In bundled mode `steplib/files.txt` and
`scripts/index.txt` stand in for the directory listing a jar cannot do.

## Bundle validation

A bundle is a workflow `.yml` plus whatever files its steps reference. A step
resolves one of two ways, and the check is really "does every step resolve, and
is every referenced file present":

- `uses:` a step library id - the library supplies the script, nothing needed
  in the bundle
- an inline `task` naming a script - that file must be there

Three ways in, one validator. A single `.yml` and a dropped folder send paths
plus the workflow text over RPC. A `.zip` is posted whole and unpacked
server-side with `java.util.zip`, then reaches the same code.

```
POST /api/analytics/WorkflowService   { "__type": "ValidateBundle", ... }
POST /api/bundles/validate            (zip as the raw request body)
```

Only paths and the workflow text travel for the unzipped routes. Posting every
script's bytes to be told a path is missing would be wasteful, and storing them
is a W4 concern.

### Checks

| Code | Severity |
|---|---|
| `NO_WORKFLOW` | error |
| `SCHEMA` - parse, duplicate ids, unknown `dependsOn`, undeclared environment, cycles | error |
| `UNRESOLVABLE_STEP` - neither `task` nor `uses` | error |
| `UNKNOWN_LIBRARY_STEP` - `uses:` id not in the library | error |
| `MISSING_SCRIPT` - referenced file absent | error |
| `UNKNOWN_OUTPUT` - input reads an outputId its producer does not declare | error |
| `FORMAT_MISMATCH` - input's declared fileFormat differs from the output it reads | error |
| `UNREFERENCED_FILE` | warning |

### Boundary inputs and protocol coupling

A boundary input is one a step reads from outside the pipeline. It declares
where the data comes from, and only `protocol` sources are checked against a
protocol - binding is per input, so a workflow can mix protocol and open data
freely and reference several protocols at once. Rules and codes follow
carp-dsp's `docs/PROTOCOL_COUPLING.md`:

| Code | Severity | When |
|---|---|---|
| `PROTOCOL_DATA_NOT_COLLECTED` | error | the named protocol does not collect that DataType |
| `PROTOCOL_NOT_VALIDATED` | warning | the protocol is not held by this study, so nothing can be checked |
| `EXTERNAL_DATA_UNATTRIBUTED` | warning | external source with no uri or citation, or a boundary input with no source at all |
| `DATA_NOT_AVAILABLE` | warning | a `file` source the study does not hold |

Matching for `protocol` sources is on the CARP **DataType**
(`dk.cachet.carp.heartrate`), never on the input's file format - the same
domain-vs-structural distinction the framework rests on.

`DATA_NOT_AVAILABLE` is a warning on purpose. A workflow needing a file the
study does not have yet is not malformed; adding the file fixes it. Making it an
error branded `hr-clean-library` - a perfectly good example - as broken.

### Acceptance case

`protocol-coupling-mixed` against the two fixture snapshots, which share an id
and differ by version:

| Protocol | Result |
|---|---|
| v1, collects heart rate + step count | clean |
| v2, collects step count only | `PROTOCOL_DATA_NOT_COLLECTED` for `dk.cachet.carp.heartrate` |

Switching the active protocol on the Data page is what shows this both ways.

**Cycles are checked over control and data edges together.** Checking only
`dependsOn` misses a step that declares no dependency but reads an output from
further down the pipeline. That is precisely what the `inj-cycle` fixture
injects, and it is unrunnable either way.

`validateDefinition` runs everything except the file-presence checks, for cases
like the demo catalogue where the scripts live in carp-dsp rather than in an
upload.

### Checked against the injection fixtures

carp-dsp ships five deliberately-broken workflows under `workflows/injections/`.
Four are caught:

| Fixture | Caught by |
|---|---|
| `inj-cycle` | cycle over control + data edges |
| `inj-missing-env` | undeclared environment |
| `inj-missing-producer` | input reads from a step that does not exist |
| `inj-type-mismatch` | `FORMAT_MISMATCH` |

`inj-missing-output` is **not** caught, and deliberately so. It renames a
consumer's *input port id*, leaving `source.outputId` correct. For an inline
task the workflow declares its own port names, so nothing is inconsistent from
the file's point of view - the fault is only visible against a step definition
that declares expected input ports, which inline tasks do not have. Catching it
would mean inventing a rule the schema does not currently support.

Unreferenced files warn rather than block: READMEs, sample data and helper
modules imported at runtime are all legitimate, and failing on them would
reject working bundles.

**One heuristic worth knowing.** `entryPoint.scriptPath` is explicit, but a
`command` task carries its script as a plain argument, so anything that looks
like a path to a script file is treated as one. It extracts correctly for every
workflow in the demo set, but the real resolver should not have to guess -
worth an explicit field when `WorkflowService` does this properly.

## Composition

The composer is the list-based half of W5-v5. The drag-and-drop canvas is the
other half and is deliberately not built.

Composition is over library steps: pick from the palette, wire each input to an
upstream output. Two things worth knowing about the model:

**`dependsOn` is derived, not authored.** A composed step depends on whatever it
reads from, so the control and data graphs cannot drift apart. A hand-written
workflow can legitimately have them differ - the DAG view draws that difference
- but there is no way to express it here, and no reason to.

**Auto-wiring matches on file format**, which is a weak signal: two `csv`
outputs are indistinguishable. It gets a linear pipeline right and is only a
starting guess. Real port type checking is W5-v5's job.

The YAML pane is generated from the draft and editable. Applying an edit posts
it to `ParseWorkflow`, which runs the same parser an upload does, and the result
rebuilds the draft. That keeps one schema implementation instead of growing a
second parser in the browser.

```
POST /api/analytics/WorkflowService     { "__type": "ParseWorkflow", "yaml": ... }
POST /api/analytics/StepLibraryService  { "__type": "CreateLibraryStep", "yaml": ... }
```

`CreateLibraryStep` adds to an overlay kept separate from the vendored set, so
an upload can never shadow a gated step and a restart returns the library to
what is in the repo. Anything added lands `gated` with no reviewer, because the
real library gates on a PR and a conformance review rather than an upload.

## Real artefacts

The portal executes nothing. Running a pipeline needs pixi environments inside
the container, which is epic open question 4 and still undecided.

Instead, a recorded run is bundled and served verbatim:
`server/src/main/resources/runs/activity-summary-offline/`. Real CSVs, a real
81 kB plot, real sizes and SHA-256 digests - the digests were cross-checked
against the `metadata.json` the executor itself wrote.

Bytes come from a plain GET, not an RPC call, because the browser has to follow
it from `<a download>` and `<img src>`:

```
GET /api/artefacts/{executionId}/{stepId}/{outputId}
```

In CARP WS this becomes a signed URL into object storage. The front end only
ever follows `ArtifactEntry.downloadUrl`, so nothing on that side changes.

`downloadUrl` and `preview` live on `ArtifactEntry`, not on the artefact, so the
artefact types stay a faithful mirror of core.

### Adding the mobgap run

1. Run `mobgap-gait-analysis` once with the demo harness.
2. Copy its `steps/` tree to `resources/runs/mobgap-gait-analysis/`.
3. Generate `manifest.json` beside it - `stepId`, `outputId`, `path`,
   `sizeBytes`, `sha256`, `contentType` per output. Output ids are the
   filename stems.
4. Add the directory name to `FIXTURES` in `RecordedRun.kt`.

No other change. Rows, plots and downloads light up on their own.

Per-step timings for mobgap are already real, taken from
`carp.dsp.demo/eval_results/mobgap-timing.txt`, scaled to a 24-second total so a
64-second pipeline does not stall a live demo. Relative weights are kept, so
gait sequence detection still visibly dominates.

## Two gotchas

**Discriminator.** Artefacts travel on core's convention, `__type`, not `type`.
`CoreAnalyticsSerializer` uses `type` internally; W2-1 step 10 settled that
artefacts use core's. Mock JSON must emit `__type` or the real client breaks the
day it is swapped in.

**Instants.** `startedAt`, `completedAt`, `activatedAt` are `kotlin.time.Instant`,
serialised as ISO-8601 strings. Not epoch millis.

## Open, and blocking

- **Execution host** - in-process, sidecar, or container-per-run. The epic dated
  this 25 Aug and W4-1 is blocked on it. Default is in-process with a
  concurrency limit.
- Progress streaming is SSE or WebSocket, undecided. The run view polls
  `GetExecutionState` in the mock either way.
