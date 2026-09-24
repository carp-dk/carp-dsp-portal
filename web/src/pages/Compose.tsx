import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/Delete';
import DraftsIcon from '@mui/icons-material/Drafts';
import SaveIcon from '@mui/icons-material/Save';
import SearchIcon from '@mui/icons-material/Search';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Chip,
  Divider,
  IconButton,
  InputAdornment,
  MenuItem,
  Paper,
  Stack,
  Tab,
  Tabs,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  RpcError,
  createWorkflow,
  getWorkflow,
  listLibrarySteps,
  listWorkflows,
  validateBundle,
} from '../api/client';
import ArgumentEditor from '../components/ArgumentEditor';
import NewStepDialog from '../components/NewStepDialog';
import ValidationReportView from '../components/ValidationReportView';
import WorkflowDag from '../components/WorkflowDag';
import YamlEditor from '../components/YamlEditor';
import {
  addLibraryStep,
  draftFromWorkflow,
  draftToStepSpecs,
  draftToYaml,
  emptyDraft,
  upstreamOutputs,
  type Draft,
  type DraftStep,
} from '../compose/draft';
import type { ValidationReport } from '../api/types';

/**
 * Workflow composer.
 *
 * Composition is over library steps: pick from the palette, wire each input to
 * an upstream output. `dependsOn` is derived from the wiring rather than
 * authored, so a composed workflow's control and data graphs cannot drift.
 *
 * The YAML pane is generated from the draft and editable. An edit is parsed
 * server-side and folded back into the draft, so the two stay in step without a
 * second parser living in the browser.
 *
 * This is the list-based half of W5-v5. The drag-and-drop canvas is the other
 * half and is deliberately not here yet.
 */
const Compose = () => {
  const navigate = useNavigate();
  const [draft, setDraft] = useState<Draft>(emptyDraft);
  const [tab, setTab] = useState(0);
  const [query, setQuery] = useState('');
  const [report, setReport] = useState<ValidationReport | null>(null);
  const [newStepOpen, setNewStepOpen] = useState(false);
  const [conflict, setConflict] = useState<{
    draft?: boolean;
    overwrite?: boolean;
  } | null>(null);

  const library = useQuery({
    queryKey: ['library'],
    queryFn: () => listLibrarySteps(),
  });

  const workflows = useQuery({
    queryKey: ['workflows'],
    queryFn: () => listWorkflows(),
  });

  const yaml = useMemo(() => draftToYaml(draft), [draft]);

  // ?workflow=<id> opens an existing one for editing, so Edit on a workflow
  // page lands here with it already loaded. Runs once - re-running would
  // discard whatever the user has changed since.
  const [searchParams] = useSearchParams();
  const requested = searchParams.get('workflow');
  const loadedRef = useRef<string | null>(null);

  useEffect(() => {
    if (!requested || loadedRef.current === requested || !library.data) return;
    loadedRef.current = requested;
    openWorkflow.mutate(requested);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [requested, library.data]);

  // Validate on a pause rather than a keystroke.
  useEffect(() => {
    if (draft.steps.length === 0) {
      setReport(null);
      return;
    }
    const timer = setTimeout(() => {
      validateBundle([], yaml).then(setReport).catch(() => setReport(null));
    }, 400);
    return () => clearTimeout(timer);
  }, [yaml, draft.steps.length]);

  /**
   * Saves the draft.
   *
   * `draft` keeps work that does not validate. `overwrite` is only sent after
   * the user confirms - the server answers 409 the first time, so replacing a
   * workflow already in the study is always a decision.
   */
  const save = useMutation({
    mutationFn: (options: { draft?: boolean; overwrite?: boolean }) =>
      createWorkflow(yaml, options),
    onSuccess: (detail) => navigate(`/workflows/${detail.summary.workflowId}`),
    onError: (error, options) => {
      if (error instanceof RpcError && error.kind === 'WorkflowIdInUse') {
        setConflict(options);
      }
    },
  });

  /**
   * Loads a stored workflow into the draft.
   *
   * Inline-script steps are carried through untouched rather than refused. The
   * scripts are synced from carp-dsp, so there was never a reason the composer
   * could not open a workflow that uses them.
   */
  const openWorkflow = useMutation({
    mutationFn: (workflowId: string) => getWorkflow(workflowId),
    onSuccess: (detail) =>
      setDraft(draftFromWorkflow(detail.definition, library.data ?? [])),
  });

  const filtered = (library.data ?? []).filter((e) => {
    const needle = query.trim().toLowerCase();
    if (!needle) return true;
    return (
      e.stepId.toLowerCase().includes(needle) ||
      e.definition.metadata.name.toLowerCase().includes(needle)
    );
  });

  const patchStep = (key: string, patch: Partial<DraftStep>) =>
    setDraft((d) => ({
      ...d,
      steps: d.steps.map((s) => (s.key === key ? { ...s, ...patch } : s)),
    }));

  return (
    <Stack spacing={3}>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'flex-start',
          justifyContent: 'space-between',
          gap: 2,
        }}
      >
        <Box>
          <Typography variant="h1_web" component="h1">
            Compose a workflow
          </Typography>
          <Typography variant="h3_web" color="text.secondary">
            Assemble library steps and wire them together
          </Typography>
        </Box>
        <Stack direction="row" spacing={1} sx={{ flexShrink: 0 }}>
          <Button
            startIcon={<DraftsIcon />}
            disabled={draft.steps.length === 0 || save.isPending}
            onClick={() => save.mutate({ draft: true })}
          >
            Save as draft
          </Button>
          <Button
            variant="contained"
            startIcon={<SaveIcon />}
            disabled={!report?.valid || save.isPending}
            onClick={() => save.mutate({})}
          >
            Save to study
          </Button>
        </Stack>
      </Box>

      {/* 409 from the server: the id is the key, so this would replace. */}
      {conflict && (
        <Alert
          severity="warning"
          action={
            <Stack direction="row" spacing={1}>
              <Button size="small" onClick={() => setConflict(null)}>
                Cancel
              </Button>
              <Button
                size="small"
                color="warning"
                variant="contained"
                onClick={() => {
                  save.mutate({ ...conflict, overwrite: true });
                  setConflict(null);
                }}
              >
                Replace it
              </Button>
            </Stack>
          }
        >
          <AlertTitle>“{draft.id}” already exists in this study</AlertTitle>
          Saving replaces it. Change the workflow id above to keep both.
        </Alert>
      )}

      {save.error && !conflict && (
        <Alert severity="error">{(save.error as Error).message}</Alert>
      )}

      <Paper variant="outlined" sx={{ p: 2 }}>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={2}>
          <TextField
            label="Workflow id"
            value={draft.id}
            onChange={(e) => setDraft({ ...draft, id: e.target.value })}
            size="small"
          />
          <TextField
            label="Name"
            value={draft.name}
            onChange={(e) => setDraft({ ...draft, name: e.target.value })}
            size="small"
            sx={{ flex: 1 }}
          />
          <TextField
            label="Version"
            value={draft.version}
            onChange={(e) => setDraft({ ...draft, version: e.target.value })}
            size="small"
            sx={{ width: 120 }}
          />
        </Stack>
      </Paper>

      <Stack direction={{ xs: 'column', lg: 'row' }} spacing={3}>
        {/* Palette */}
        <Box sx={{ width: { lg: 320 }, flexShrink: 0 }}>
          <Stack
            direction="row"
            spacing={1}
            sx={{ alignItems: 'center', justifyContent: 'space-between' }}
          >
            <Typography variant="h4_web">Step library</Typography>
            {/* Authoring a step belongs here: you need one that does not exist
                yet precisely while composing. */}
            <Button
              size="small"
              startIcon={<AddIcon />}
              onClick={() => setNewStepOpen(true)}
            >
              New step
            </Button>
          </Stack>

          <NewStepDialog
            open={newStepOpen}
            onClose={() => setNewStepOpen(false)}
            onCreated={(entry) => {
              library.refetch();
              setDraft((d) => addLibraryStep(d, entry));
              setNewStepOpen(false);
            }}
          />
          <TextField
            placeholder="Search steps"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            size="small"
            fullWidth
            sx={{ mb: 1 }}
            slotProps={{
              input: {
                startAdornment: (
                  <InputAdornment position="start">
                    <SearchIcon fontSize="small" />
                  </InputAdornment>
                ),
              },
            }}
          />
          <Stack spacing={1} sx={{ maxHeight: 420, overflowY: 'auto' }}>
            {filtered.map((entry) => (
              <Paper
                key={entry.stepId}
                variant="outlined"
                sx={{ p: 1.5, cursor: 'pointer' }}
                onClick={() => setDraft((d) => addLibraryStep(d, entry))}
              >
                <Stack
                  direction="row"
                  spacing={1}
                  sx={{ alignItems: 'center', justifyContent: 'space-between' }}
                >
                  <Box sx={{ minWidth: 0 }}>
                    <Typography variant="h5">
                      {entry.definition.metadata.name}
                    </Typography>
                    <Typography
                      variant="h6"
                      color="text.secondary"
                      sx={{ fontFamily: 'monospace' }}
                    >
                      {entry.stepId}
                    </Typography>
                  </Box>
                  <AddIcon fontSize="small" color="primary" />
                </Stack>
              </Paper>
            ))}
          </Stack>

          {(workflows.data ?? []).length > 0 && (
            <>
              <Typography variant="h4_web" sx={{ mt: 3 }} gutterBottom>
                Start from a workflow
              </Typography>
              <Typography variant="h6" color="text.secondary" gutterBottom>
                Loads its steps into the draft. A workflow with inline scripts
                opens read-only here - the composer works over library steps.
              </Typography>
              <Stack spacing={1}>
                {(workflows.data ?? []).map((wf) => (
                  <Button
                    key={wf.workflowId}
                    size="small"
                    variant="outlined"
                    disabled={openWorkflow.isPending}
                    onClick={() => openWorkflow.mutate(wf.workflowId)}
                    sx={{ justifyContent: 'flex-start' }}
                  >
                    {wf.name}
                  </Button>
                ))}
              </Stack>
              {openWorkflow.error && (
                <Alert severity="warning" sx={{ mt: 1 }}>
                  {(openWorkflow.error as Error).message}
                </Alert>
              )}
            </>
          )}
        </Box>

        {/* Draft */}
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
            <Tab label={`Steps (${draft.steps.length})`} />
            <Tab label="DAG" />
            <Tab label="YAML" />
          </Tabs>

          {tab === 0 && (
            <Stack spacing={2}>
              {draft.steps.length === 0 && (
                <Alert severity="info">
                  Pick a step from the library to start.
                </Alert>
              )}
              {draft.steps.map((step, index) => (
                <StepEditor
                  key={step.key}
                  step={step}
                  index={index}
                  draft={draft}
                  onChange={(patch) => patchStep(step.key, patch)}
                  onRemove={() =>
                    setDraft((d) => ({
                      ...d,
                      steps: d.steps.filter((s) => s.key !== step.key),
                    }))
                  }
                />
              ))}
            </Stack>
          )}

          {tab === 1 && <WorkflowDag steps={draftToStepSpecs(draft)} />}

          {tab === 2 && (
            <YamlEditor
              value={yaml}
              library={library.data ?? []}
              onParsed={(parsed) => setDraft(parsed)}
            />
          )}

          {report && (
            <Box sx={{ mt: 3 }}>
              <ValidationReportView report={report} />
            </Box>
          )}
        </Box>
      </Stack>
    </Stack>
  );
};

const StepEditor = ({
  step,
  index,
  draft,
  onChange,
  onRemove,
}: {
  step: DraftStep;
  index: number;
  draft: Draft;
  onChange: (patch: Partial<DraftStep>) => void;
  onRemove: () => void;
}) => {
  const available = upstreamOutputs(draft, step.key);

  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Stack
        direction="row"
        spacing={2}
        sx={{ alignItems: 'flex-start', justifyContent: 'space-between' }}
      >
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography variant="h4_web">
            {index + 1}. {step.name}
          </Typography>
          {step.uses ? (
            <Chip
              label={step.uses}
              size="small"
              color="primary"
              sx={{ fontFamily: 'monospace', mt: 0.5 }}
            />
          ) : (
            // An inline step runs its own script. Shown as such rather than
            // hidden, since it is the reason the step has no library entry.
            <Chip
              label={
                step.task?.entryPoint?.scriptPath ??
                step.task?.args.find((a) => a.includes('/')) ??
                step.task?.executable ??
                'inline script'
              }
              size="small"
              variant="outlined"
              sx={{ fontFamily: 'monospace', mt: 0.5 }}
            />
          )}
        </Box>
        <IconButton size="small" onClick={onRemove} aria-label="Remove step">
          <DeleteIcon fontSize="small" />
        </IconButton>
      </Stack>

      <Divider sx={{ my: 1.5 }} />

      <TextField
        label="Step id"
        value={step.id}
        onChange={(e) => onChange({ id: e.target.value })}
        size="small"
        sx={{ mb: 2 }}
      />

      {step.inputs.length > 0 && (
        <Stack spacing={1.5}>
          <Typography variant="h6" color="text.secondary">
            INPUTS
          </Typography>
          {step.inputs.map((input) => {
            // Satisfied from outside the pipeline. Shown as such rather than
            // offered a wiring dropdown it must not be pointed at.
            if (input.boundarySource) {
              const source = input.boundarySource;
              return (
                <Stack
                  key={input.id}
                  direction="row"
                  spacing={1}
                  sx={{ alignItems: 'center' }}
                >
                  <Typography variant="h5" sx={{ minWidth: 140 }}>
                    {input.id}
                  </Typography>
                  <Chip
                    label={source.type}
                    size="small"
                    color="secondary"
                    sx={{ fontFamily: 'monospace' }}
                  />
                  <Typography variant="h6" color="text.secondary">
                    {source.dataType ??
                      source.uri ??
                      source.path ??
                      'from outside the pipeline'}
                  </Typography>
                </Stack>
              );
            }

            const value =
              input.sourceStepId && input.sourceOutputId
                ? `${input.sourceStepId}::${input.sourceOutputId}`
                : '';

            // Same-format outputs first, but everything stays selectable -
            // format is a hint here, not the type system W5-v5 will need.
            const options = [...available].sort((a, b) => {
              const am = a.fileFormat === input.fileFormat ? 0 : 1;
              const bm = b.fileFormat === input.fileFormat ? 0 : 1;
              return am - bm;
            });

            return (
              <Stack
                key={input.id}
                direction={{ xs: 'column', sm: 'row' }}
                spacing={1}
                sx={{ alignItems: { sm: 'center' } }}
              >
                <Typography variant="h5" sx={{ minWidth: 140 }}>
                  {input.id}
                  {input.fileFormat && (
                    <Chip
                      label={input.fileFormat}
                      size="small"
                      variant="outlined"
                      sx={{ ml: 0.5 }}
                    />
                  )}
                </Typography>
                <TextField
                  select
                  size="small"
                  fullWidth
                  value={value}
                  error={!value}
                  helperText={!value ? 'Not wired' : undefined}
                  onChange={(e) => {
                    const [stepId, outputId] = e.target.value.split('::');
                    onChange({
                      inputs: step.inputs.map((i) =>
                        i.id === input.id
                          ? { ...i, sourceStepId: stepId, sourceOutputId: outputId }
                          : i,
                      ),
                    });
                  }}
                >
                  {options.length === 0 && (
                    <MenuItem value="" disabled>
                      No upstream outputs - add a step before this one
                    </MenuItem>
                  )}
                  {options.map((o) => (
                    <MenuItem
                      key={`${o.stepId}::${o.outputId}`}
                      value={`${o.stepId}::${o.outputId}`}
                    >
                      {o.stepId} → {o.outputId}
                      {o.fileFormat ? ` (${o.fileFormat})` : ''}
                    </MenuItem>
                  ))}
                </TextField>
              </Stack>
            );
          })}
        </Stack>
      )}

      {step.outputs.length > 0 && (
        <>
          <Typography variant="h6" color="text.secondary" sx={{ mt: 2 }}>
            OUTPUTS
          </Typography>
          <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap' }}>
            {step.outputs.map((o) => (
              <Chip
                key={o.id}
                label={`${o.id}${o.fileFormat ? ` · ${o.fileFormat}` : ''}`}
                size="small"
                variant="outlined"
              />
            ))}
          </Stack>
        </>
      )}

      <Box sx={{ mt: 2 }}>
        <ArgumentEditor
          args={step.args}
          onChange={(args) => onChange({ args })}
          inputCount={step.inputs.filter((i) => i.sourceStepId).length}
          outputCount={step.outputs.length}
        />
      </Box>
    </Paper>
  );
};

export default Compose;
