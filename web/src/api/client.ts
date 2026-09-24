import type {
  ArtifactEntry,
  DataSource,
  DemoWorkflow,
  EnvironmentEntry,
  ExecutionReport,
  ProtocolSummary,
  ExecutorState,
  LibraryEntry,
  Schedule,
  TimeParametersView,
  ValidationReport,
  WorkflowBindings,
  WorkflowDetail,
  WorkflowSummary,
} from './types';

/**
 * RPC client.
 *
 * One endpoint per service; the body is a request object tagged with `__type`.
 * This mirrors how CARP WS exposes application services, so swapping the mock
 * for the real backend at W4-1 is a change of base URL, not of call sites.
 */

const BASE = '/api/analytics';

/** The one study the mock knows about. */
export const DEMO_STUDY_ID = '11111111-1111-1111-1111-111111111111';

/** Thrown with the server's ApiError so pages can show `kind` and `message`. */
export class RpcError extends Error {
  constructor(
    readonly kind: string,
    message: string,
  ) {
    super(message);
    this.name = 'RpcError';
  }
}

const call = async <T>(service: string, request: object): Promise<T> => {
  const response = await fetch(`${BASE}/${service}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    let kind = `HTTP_${response.status}`;
    let message = response.statusText;
    try {
      const body = await response.json();
      if (body?.kind) kind = body.kind;
      if (body?.message) message = body.message;
    } catch {
      // Not a JSON error body; keep the status text.
    }
    throw new RpcError(kind, message);
  }

  return (await response.json()) as T;
};

// ---- WorkflowService --------------------------------------------------------

export const listWorkflows = (studyId = DEMO_STUDY_ID) =>
  call<WorkflowSummary[]>('WorkflowService', {
    __type: 'ListWorkflows',
    studyId,
  });

export const getWorkflow = (workflowId: string, studyId = DEMO_STUDY_ID) =>
  call<WorkflowDetail>('WorkflowService', {
    __type: 'GetWorkflow',
    studyId,
    workflowId,
  });

/**
 * Dry run over a bundle. Only paths and the workflow text go up - validation
 * needs nothing else, and posting every script's bytes to be told a path is
 * missing would be wasteful.
 */
export const validateBundle = (
  paths: string[],
  yaml: string | null,
  studyId = DEMO_STUDY_ID,
) =>
  call<ValidationReport>('WorkflowService', {
    __type: 'ValidateBundle',
    studyId,
    paths,
    yaml,
  });

/** Same validation, with the server unpacking the archive. */
export const validateArchive = async (
  file: File,
): Promise<ValidationReport> => {
  const response = await fetch('/api/bundles/validate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/zip' },
    body: file,
  });

  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new RpcError(
      body?.kind ?? `HTTP_${response.status}`,
      body?.message ?? response.statusText,
    );
  }

  return (await response.json()) as ValidationReport;
};

/**
 * Runs an uploaded bundle: the workflow plus the files its steps reference.
 *
 * A zip goes up as the raw body; a dropped folder goes up as multipart with each
 * part named by its path within the bundle. The server reads both into the same
 * set of files - see docs/bundle-layout.md.
 *
 * Portal-only, like validation: neither shape is something the analytics RPC
 * surface carries.
 */
export const executeBundle = async (
  files: File[],
  relativePathOf: (file: File) => string,
  studyId = DEMO_STUDY_ID,
): Promise<ExecutorState> => {
  const archive = files.find((f) => f.name.toLowerCase().endsWith('.zip'));

  let init: RequestInit;
  if (archive) {
    init = { method: 'POST', headers: { 'Content-Type': 'application/zip' }, body: archive };
  } else {
    const form = new FormData();
    // The part name is ignored; the filename carries the bundle-relative path.
    files.forEach((file, i) => form.append(`f${i}`, file, relativePathOf(file)));
    init = { method: 'POST', body: form };
  }

  const response = await fetch(
    `/api/bundles/execute?studyId=${encodeURIComponent(studyId)}`,
    init,
  );

  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new RpcError(
      body?.kind ?? `HTTP_${response.status}`,
      body?.message ?? response.statusText,
    );
  }

  return (await response.json()) as ExecutorState;
};

/**
 * Saves an uploaded bundle's workflow to the study.
 *
 * Same two shapes as [executeBundle], for the same reason: a zip never gives the
 * browser the workflow text, so `createWorkflow` cannot be used for one.
 *
 * `draft` skips the validation gate - a draft is work in progress, and something
 * that fails validation is exactly what you want to keep and fix.
 */
export const saveBundle = async (
  files: File[],
  relativePathOf: (file: File) => string,
  options: { draft?: boolean; overwrite?: boolean } = {},
  studyId = DEMO_STUDY_ID,
): Promise<WorkflowDetail> => {
  const archive = files.find((f) => f.name.toLowerCase().endsWith('.zip'));

  let init: RequestInit;
  if (archive) {
    init = { method: 'POST', headers: { 'Content-Type': 'application/zip' }, body: archive };
  } else {
    const form = new FormData();
    files.forEach((file, i) => form.append(`f${i}`, file, relativePathOf(file)));
    init = { method: 'POST', body: form };
  }

  const query = new URLSearchParams({ studyId });
  if (options.draft) query.set('draft', 'true');
  if (options.overwrite) query.set('overwrite', 'true');

  const response = await fetch(`/api/bundles/save?${query}`, init);

  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new RpcError(
      body?.kind ?? `HTTP_${response.status}`,
      body?.message ?? response.statusText,
    );
  }

  return (await response.json()) as WorkflowDetail;
};

/**
 * How far environment reuse may stretch: 'exact' or 'superset'.
 *
 * Portal-only, like the run mode. `exact` builds unless a digest-identical
 * environment exists; `superset` lets a larger one serve, which is faster and
 * strictly less safe - see the endpoint's KDoc.
 */
export type EnvironmentReuse = 'exact' | 'superset';

const reuseCall = async (init?: RequestInit): Promise<EnvironmentReuse> => {
  const response = await fetch('/api/environments/reuse', init);

  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new RpcError(
      body?.kind ?? `HTTP_${response.status}`,
      body?.message ?? response.statusText,
    );
  }

  return ((await response.json()) as { mode: EnvironmentReuse }).mode;
};

/** The environments on the state volume, solved or not. */
export const listEnvironments = async (): Promise<EnvironmentEntry[]> => {
  const response = await fetch('/api/environments');

  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new RpcError(
      body?.kind ?? `HTTP_${response.status}`,
      body?.message ?? response.statusText,
    );
  }

  return (await response.json()) as EnvironmentEntry[];
};

export const getEnvironmentReuse = () => reuseCall();

export const setEnvironmentReuse = (mode: EnvironmentReuse) =>
  reuseCall({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode }),
  });

// ---- StudyDataService -------------------------------------------------------

export const listDataSources = () =>
  call<DataSource[]>('StudyDataService', { __type: 'ListDataSources' });

export const listProtocols = () =>
  call<ProtocolSummary[]>('StudyDataService', { __type: 'ListProtocols' });

export const uploadProtocol = (json: string) =>
  call<ProtocolSummary>('StudyDataService', { __type: 'UploadProtocol', json });

export const activateProtocol = (id: string, version: number) =>
  call<ProtocolSummary>('StudyDataService', {
    __type: 'ActivateProtocol',
    id,
    version,
  });

export const addDataFile = (
  path: string,
  sizeBytes?: number,
  description?: string,
) =>
  call<DataSource>('StudyDataService', {
    __type: 'AddDataFile',
    path,
    sizeBytes: sizeBytes ?? null,
    description: description ?? null,
  });

export const addExternalDataset = (
  uri: string,
  citation?: string,
  description?: string,
) =>
  call<DataSource>('StudyDataService', {
    __type: 'AddExternalDataset',
    uri,
    citation: citation ?? null,
    description: description ?? null,
  });

export const listDemoWorkflows = () =>
  call<DemoWorkflow[]>('WorkflowService', { __type: 'ListDemoWorkflows' });

export const addDemoWorkflow = (path: string, studyId = DEMO_STUDY_ID) =>
  call<WorkflowSummary>('WorkflowService', {
    __type: 'AddDemoWorkflow',
    studyId,
    path,
  });

/** Parse without storing - what makes the composer's YAML pane two-way. */
export const parseWorkflow = (yaml: string) =>
  call<WorkflowDetail>('WorkflowService', { __type: 'ParseWorkflow', yaml });

export const createLibraryStep = (yaml: string) =>
  call<LibraryEntry>('StepLibraryService', {
    __type: 'CreateLibraryStep',
    yaml,
  });

/**
 * Saves a workflow.
 *
 * `draft` skips the resolution checks so work in progress can be kept.
 * `overwrite` is required to replace one already in the study - without it the
 * server answers 409 `WorkflowIdInUse` rather than silently replacing it.
 */
export const createWorkflow = (
  yaml: string,
  options: { draft?: boolean; overwrite?: boolean } = {},
  studyId = DEMO_STUDY_ID,
) =>
  call<WorkflowDetail>('WorkflowService', {
    __type: 'CreateWorkflow',
    studyId,
    yaml,
    draft: options.draft ?? false,
    overwrite: options.overwrite ?? false,
  });

export const deleteWorkflow = async (
  workflowId: string,
  studyId = DEMO_STUDY_ID,
): Promise<void> => {
  const response = await fetch(`${BASE}/WorkflowService`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ __type: 'DeleteWorkflow', studyId, workflowId }),
  });

  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new RpcError(
      body?.kind ?? `HTTP_${response.status}`,
      body?.message ?? response.statusText,
    );
  }
};

// ---- ExecutionService -------------------------------------------------------

export const executeWorkflow = (workflowId: string, studyId = DEMO_STUDY_ID) =>
  call<ExecutorState>('ExecutionService', {
    __type: 'ExecuteWorkflow',
    studyId,
    workflowId,
  });

export const executeWorkflowFromDefinition = (
  yaml: string,
  studyId = DEMO_STUDY_ID,
) =>
  call<ExecutorState>('ExecutionService', {
    __type: 'ExecuteWorkflowFromDefinition',
    studyId,
    yaml,
  });

export const getExecutionState = (executionId: string) =>
  call<ExecutorState>('ExecutionService', {
    __type: 'GetExecutionState',
    executionId,
  });

export const getExecutionResult = (executionId: string) =>
  call<ExecutionReport>('ExecutionService', {
    __type: 'GetExecutionResult',
    executionId,
  });

export const findExecutions = (
  studyId = DEMO_STUDY_ID,
  workflowId?: string,
) =>
  call<ExecutorState[]>('ExecutionService', {
    __type: 'FindExecutions',
    studyId,
    workflowId: workflowId ?? null,
  });

/**
 * Stops a run.
 *
 * DEPARTURE: no counterpart in core, which has no notion of stopping a run. It
 * exists because a demo needs a way out when a step hangs.
 */
export const cancelExecution = (executionId: string) =>
  call<ExecutorState>('ExecutionService', {
    __type: 'CancelExecution',
    executionId,
  });

/** A step's captured stdout, as text. */
export const getStepOutput = async (url: string): Promise<string> => {
  const response = await fetch(url);
  if (!response.ok) throw new RpcError(`HTTP_${response.status}`, response.statusText);
  return response.text();
};

// ---- ArtefactRegistryService ------------------------------------------------

export const getRunArtefacts = (executionId: string) =>
  call<ArtifactEntry[]>('ArtefactRegistryService', {
    __type: 'GetRunArtefacts',
    executionId,
  });

// ---- StepLibraryService -----------------------------------------------------
// Service is planned but not in core yet, so the request shape is a guess. The
// payload is real: entries are the step.yaml files from carp.dsp.steps.

export const listLibrarySteps = () =>
  call<LibraryEntry[]>('StepLibraryService', { __type: 'ListLibrarySteps' });

export const getLibraryStep = (stepId: string) =>
  call<LibraryEntry>('StepLibraryService', {
    __type: 'GetLibraryStep',
    stepId,
  });

// ---- Time parameters (portal-only REST) -------------------------------------

const restCall = async <T>(url: string, init?: RequestInit): Promise<T> => {
  const response = await fetch(url, init);

  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new RpcError(
      body?.kind ?? `HTTP_${response.status}`,
      body?.message ?? response.statusText,
    );
  }

  return (await response.json()) as T;
};

export const getTimeParameters = (workflowId: string) =>
  restCall<TimeParametersView>(
    `/api/workflows/${encodeURIComponent(workflowId)}/time-parameters`,
  );

export const saveTimeParameters = (bindings: WorkflowBindings) =>
  restCall<TimeParametersView>(
    `/api/workflows/${encodeURIComponent(bindings.workflowId)}/time-parameters`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(bindings),
    },
  );

// ---- ScheduleService --------------------------------------------------------
// INVENTED. No service in core to mirror; scheduling moves onto protocol
// triggers with the CARP integration, and this shape will change with it.

export const createSchedule = (
  workflowId: string,
  cadence: string,
  startAt?: string,
  studyId = DEMO_STUDY_ID,
) =>
  call<Schedule>('ScheduleService', {
    __type: 'CreateSchedule',
    studyId,
    workflowId,
    cadence,
    startAt: startAt ?? null,
  });

export const listSchedules = (workflowId?: string, studyId = DEMO_STUDY_ID) =>
  call<Schedule[]>('ScheduleService', {
    __type: 'ListSchedules',
    studyId,
    workflowId: workflowId ?? null,
  });

export const setScheduleEnabled = (scheduleId: string, enabled: boolean) =>
  call<Schedule>('ScheduleService', {
    __type: 'SetScheduleEnabled',
    scheduleId,
    enabled,
  });

/** Returns 204 with no body, so this resolves to void rather than JSON. */
export const deleteSchedule = async (scheduleId: string): Promise<void> => {
  const response = await fetch(`${BASE}/ScheduleService`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ __type: 'DeleteSchedule', scheduleId }),
  });

  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new RpcError(
      body?.kind ?? `HTTP_${response.status}`,
      body?.message ?? response.statusText,
    );
  }
};

// ---- Run mode ---------------------------------------------------------------
// Portal-only, and not on a service: CARP WS runs workflows one way. This is
// here so a live demo can fall back to the simulation without a restart, and it
// goes when the mock shell does.

export type RunMode = 'real' | 'simulated';

const modeCall = async (init?: RequestInit): Promise<RunMode> => {
  const response = await fetch('/api/mode', init);

  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new RpcError(
      body?.kind ?? `HTTP_${response.status}`,
      body?.message ?? response.statusText,
    );
  }

  return ((await response.json()) as { mode: RunMode }).mode;
};

export const getRunMode = () => modeCall();

export const setRunMode = (mode: RunMode) =>
  modeCall({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode }),
  });
