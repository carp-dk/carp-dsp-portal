import type { WorkflowArtifact } from './artifacts';

/** Mirrors server/.../api/Dto.kt, which mirrors carp.analytics.core. */

export type ExecutionStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'SKIPPED'
  /** DEPARTURE: no counterpart in core, which cannot stop a run. */
  | 'CANCELLED';

export interface ExecutorState {
  executionId: string;
  status: ExecutionStatus;
  startedAt: string;
  completedAt?: string | null;
  workflowId: string;
  studyId: string;
  /** What started the run and the values it was given. Portal-only. */
  context?: RunContext | null;
}

export type RunTrigger = 'MANUAL' | 'SCHEDULE';

/** One argument value a run was given. */
export interface BoundValue {
  stepKey: string;
  key: string;
  value: string;
}

/** What a run was given, recorded when it starts. */
export interface RunContext {
  trigger: RunTrigger;
  scheduleId?: string | null;
  fireTime?: string | null;
  windowFrom?: string | null;
  windowTo?: string | null;
  values: BoundValue[];
}

export interface StepRunMetadata {
  id: string;
  name: string;
  description?: string | null;
  /** The step id as written in the YAML. This is what the UI keys off. */
  descriptorId?: string | null;
}

/** Matches the `location` object in a real run's artifact metadata.json. */
export interface ResourceRef {
  kind: string;
  value: string;
}

/**
 * Provisioning an environment, as it happens.
 *
 * INVENTED: core reports environment logs only once a run has finished. This is
 * the live view, and it is what fills the silence before step 1.
 */
export interface ProvisioningEvent {
  environmentId: string;
  name: string;
  status: ExecutionStatus;
  startedAt: string;
  finishedAt?: string | null;
  durationMs?: number | null;
  /** 'BUILT' | 'EXACT' | 'SUPERSET'. */
  match: string;
  /** What a SUPERSET environment carries that the workflow never asked for. */
  extras: string[];
  message?: string | null;
}

/** What a step actually ran, and where its output went. */
export interface StepRunDetail {
  command: string[];
  workingDirectory?: string | null;
  exitCode?: number | null;
  /** Fetch as text. Null when the step printed nothing. */
  outputUrl?: string | null;
}

export interface ProducedOutputRef {
  outputId: string;
  location: ResourceRef;
  sizeBytes?: number | null;
  sha256?: string | null;
  contentType?: string | null;
  /** Declared name - `imu-data-csv`, not the UUID. Null on runs recorded before this existed. */
  name?: string | null;
}

export type FailureKind =
  | 'COMMAND_FAILED'
  | 'TIMEOUT'
  | 'CANCELLED'
  | 'INFRASTRUCTURE'
  | 'OUTPUT_MISSING'
  | 'UNKNOWN';

export interface StepFailure {
  kind: FailureKind;
  message: string;
}

export interface StepRunResult {
  stepMetadata: StepRunMetadata;
  status: ExecutionStatus;
  startedAt?: string | null;
  finishedAt?: string | null;
  outputs: ProducedOutputRef[];
  failure?: StepFailure | null;
  logTail: string[];
  detail?: StepRunDetail | null;
}

export type ExecutionIssueKind =
  | 'WORKSPACE_ERROR'
  | 'POLICY_VIOLATION'
  | 'ORCHESTRATOR_ERROR'
  | 'OUTPUT_MISSING'
  | 'UNEXPECTED_OUTPUT'
  | 'ARTIFACT_COLLECTION_FAILED'
  | 'PROCESS_FAILED'
  | 'UNKNOWN';

export interface ExecutionIssue {
  stepMetadata?: StepRunMetadata | null;
  kind: ExecutionIssueKind;
  message: string;
}

export interface ExecutionReport {
  runId: string;
  planId: string;
  startedAt?: string | null;
  finishedAt?: string | null;
  status: ExecutionStatus;
  stepResults: StepRunResult[];
  issues: ExecutionIssue[];
  /** Environment provisioning, which happens before the first step. */
  provisioning?: ProvisioningEvent[];
}

// ---- Workflow ---------------------------------------------------------------

export interface WorkflowSummary {
  workflowId: string;
  name: string;
  description?: string | null;
  version: string;
  tags: string[];
  stepCount: number;
  lastRunAt?: string | null;
  lastRunStatus?: ExecutionStatus | null;
  /**
   * Saved without passing validation. Editable and re-savable, but not
   * runnable - the checks it skipped decide whether it can run at all.
   */
  draft?: boolean;
}

export interface PortDescriptor {
  fileFormat?: string | null;
  encoding?: string | null;
  notes?: string | null;
}

/**
 * Where an input comes from. This is the data-flow edge, and it is a different
 * graph from `dependsOn`: a step can read from an ancestor it does not directly
 * depend on. The detail view shows both.
 */
/**
 * A union flattened into one type, distinguished by `type`:
 *
 * - `step-output` - produced upstream (`stepId`, `outputId`)
 * - `protocol` - collected by a study protocol (`protocol`, `dataType`)
 * - `external` - open data (`uri`, `citation`)
 * - `file` - a data file supplied with the study (`path`)
 */
export interface PortSource {
  type: string;
  stepId?: string | null;
  outputId?: string | null;
  protocol?: { id: string; name?: string | null; version?: number | null } | null;
  /** A CARP DataType, e.g. dk.cachet.carp.heartrate - not a file format. */
  dataType?: string | null;
  uri?: string | null;
  citation?: string | null;
  path?: string | null;
}

export interface PortSpec {
  id: string;
  descriptor?: PortDescriptor | null;
  source?: PortSource | null;
}

export interface EntryPoint {
  type?: string | null;
  scriptPath?: string | null;
  module?: string | null;
  function?: string | null;
}

export interface TaskSpec {
  type: string;
  id?: string | null;
  name?: string | null;
  executable?: string | null;
  entryPoint?: EntryPoint | null;
  args: string[];
}

/**
 * A step is written inline (its own `metadata` and `task`) or by reference
 * (`uses`, a step library id - the library supplies task, metadata and output
 * declarations). Both forms are in the demo set, so everything but `id` is
 * optional.
 */
export interface StepSpec {
  id: string;
  metadata?: {
    name: string;
    description?: string | null;
    version?: string | null;
  } | null;
  uses?: string | null;
  environmentId?: string | null;
  dependsOn: string[];
  task?: TaskSpec | null;
  args: string[];
  inputs: PortSpec[];
  outputs: PortSpec[];
}

export interface EnvironmentSpec {
  name: string;
  kind: string;
  spec: { dependencies: string[]; pythonVersion: string[] };
}

export interface WorkflowFile {
  schemaVersion: string;
  metadata: {
    id: string;
    name: string;
    description?: string | null;
    version: string;
    tags: string[];
  };
  environments: Record<string, EnvironmentSpec>;
  steps: StepSpec[];
}

export interface WorkflowDetail {
  summary: WorkflowSummary;
  definition: WorkflowFile;
  rawYaml: string;
}

/**
 * A saved schedule and where it stands. INVENTED - no counterpart in core.
 * Everything after `createdAt` is worked out from the schedule's runs.
 */
export interface Schedule {
  scheduleId: string;
  studyId: string;
  workflowId: string;
  cadence: string;
  startAt?: string | null;
  createdAt: string;
  /** A paused schedule does not fire; resuming catches up with one run. */
  enabled: boolean;
  nextRunAt?: string | null;
  windowFrom?: string | null;
  windowEnd?: string | null;
  completed: boolean;
  runs: ExecutorState[];
}

// ---- Time parameters (portal-only) -------------------------------------------

/** A time value found in a workflow's step arguments. */
export interface TimeParameter {
  stepKey: string;
  flag: string;
  name?: string | null;
  label: string;
  value: string;
  format: 'ISO_DATE_TIME' | 'ISO_DATE' | 'EPOCH_MILLIS';
}

export type BindingMode = 'KEEP' | 'FIXED' | 'WINDOW_START' | 'WINDOW_END';

export interface ParameterBinding {
  stepKey: string;
  flag: string;
  name?: string | null;
  mode: BindingMode;
  /** For FIXED: the instant to use, ISO-8601. */
  value?: string | null;
}

export interface WorkflowBindings {
  workflowId: string;
  windowStart?: string | null;
  windowEnd?: string | null;
  parameters: ParameterBinding[];
}

export interface TimeParametersView {
  detected: TimeParameter[];
  /** Null until the user has chosen. */
  bindings?: WorkflowBindings | null;
  /** Saved bindings whose argument the workflow no longer has. */
  stale: ParameterBinding[];
}

/** First rows of a tabular output, so the results view shows real data. */
export interface TablePreview {
  columns: string[];
  rows: string[][];
  totalRows?: number | null;
}

/**
 * A demo workflow from carp-dsp, offered as an example to copy into a study.
 * `valid` is false for the injections/ fixtures, which are deliberately broken.
 */
export interface DemoWorkflow {
  path: string;
  workflowId: string;
  summary?: WorkflowSummary | null;
  valid: boolean;
  problem?: string | null;
  rawYaml: string;
}

// ---- Study data -------------------------------------------------------------

/** A study protocol, reduced to what the coupling check and the UI need. */
export interface ProtocolSummary {
  id: string;
  name: string;
  version: number;
  description?: string | null;
  deviceRoles: string[];
  /** Every CARP DataType this protocol collects. */
  collectedDataTypes: string[];
  active: boolean;
}

/**
 * One thing the study has data for. `kind` is `protocol`, `file` or `external`,
 * matching the three non-step-output source types a workflow input can declare.
 */
export interface DataSource {
  kind: string;
  id: string;
  name: string;
  description?: string | null;
  dataType?: string | null;
  protocolId?: string | null;
  uri?: string | null;
  citation?: string | null;
  sizeBytes?: number | null;
}

// ---- Bundle validation ------------------------------------------------------

export type Severity = 'ERROR' | 'WARNING' | 'INFO';

/** `code` is stable and switchable; `message` is for people. */
export interface Finding {
  severity: Severity;
  code: string;
  message: string;
  stepId?: string | null;
  path?: string | null;
}

export interface StepResolution {
  stepId: string;
  name: string;
  /** "library", "bundle", or "unresolved". */
  resolvedVia: string;
  libraryStepId?: string | null;
  scripts: string[];
  missingScripts: string[];
}

export interface ValidationReport {
  valid: boolean;
  workflowId?: string | null;
  workflowName?: string | null;
  stepCount: number;
  fileCount: number;
  findings: Finding[];
  resolutions: StepResolution[];
}

// ---- Step library -----------------------------------------------------------
// Entries are the real step.yaml files from carp.dsp.steps. A step file is a
// workflow file plus a `library:` block, so the step and environment shapes
// above are reused.

export interface LibraryBlock {
  tier?: string | null;
  subject?: string | null;
  environment?: {
    default?: string | null;
    requires?: {
      kind: string[];
      interpreter?: { name?: string | null; version?: string | null } | null;
      /** Objects, not strings: `{ name: "pandas", version: ">=2.0" }`. */
      packages: { name: string; version?: string | null }[];
    } | null;
  } | null;
  implementations: { language: string; path?: string | null }[];
  method?: { name?: string | null; citation?: string | null } | null;
  reference?: {
    input?: string | null;
    expected?: string | null;
    tolerance?: { float?: number | null } | null;
  } | null;
}

/**
 * Conformance record. `level` is the gate - a step stays `gated` until it has
 * been reviewed, and `reviewedHash` ties the review to the content it covered.
 */
export interface Certification {
  id?: string | null;
  version?: string | null;
  level?: string | null;
  contentHash?: string | null;
  reviewedOn?: string | null;
  reviewer?: string | null;
  reviewedPr?: string | null;
  reviewedHash?: string | null;
}

export interface StepLibraryFile {
  schemaVersion: string;
  metadata: {
    id: string;
    name: string;
    description?: string | null;
    version: string;
    tags: string[];
  };
  environments: Record<string, EnvironmentSpec>;
  steps: StepSpec[];
  library?: LibraryBlock | null;
}

export interface LibraryEntry {
  stepId: string;
  definition: StepLibraryFile;
  certification?: Certification | null;
  readme?: string | null;
  rawYaml: string;
  /** Directory relative to the steps root - the key for fetching files. */
  dir: string;
  /** Everything in that directory: implementations, tests, fixtures. */
  files: string[];
}

export interface ArtifactEntry {
  stepId: string;
  outputId: string;
  artifact: WorkflowArtifact;
  /** Present only where a recorded run backs this output with real bytes. */
  downloadUrl?: string | null;
  preview?: TablePreview | null;
  /** Display names for `stepId` and `outputId`. Null when the run predates them. */
  stepName?: string | null;
  outputName?: string | null;
}

export interface ApiError {
  kind: string;
  message: string;
}

/**
 * One environment directory on the state volume. Portal-only, like the run mode.
 *
 * `status` is `incomplete` for a build that failed or was interrupted. Times are
 * ISO-8601; `lastUsedAt` is null until a run has built or reused it.
 */
export interface EnvironmentEntry {
  id: string;
  kind: string;
  name: string;
  status: 'solved' | 'incomplete';
  runtime: string | null;
  dependencies: string[];
  channels: string[];
  sizeBytes: number | null;
  builtAt: string | null;
  lastUsedAt: string | null;
}
