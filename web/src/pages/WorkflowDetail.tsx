import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import EditIcon from '@mui/icons-material/Edit';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import ScheduleIcon from '@mui/icons-material/Schedule';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  Paper,
  Stack,
  Tab,
  Tabs,
  Typography,
} from '@mui/material';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import {
  Link as RouterLink,
  useNavigate,
  useParams,
  useSearchParams,
} from 'react-router-dom';
import {
  executeWorkflow,
  findExecutions,
  getTimeParameters,
  getWorkflow,
  listSchedules,
} from '../api/client';
import RunsTable from '../components/RunsTable';
import SchedulesTable from '../components/SchedulesTable';
import ScheduleDialog from '../components/ScheduleDialog';
import TimeParametersPanel from '../components/TimeParametersPanel';
import WorkflowDag from '../components/WorkflowDag';
import type { StepSpec, WorkflowDetail as Detail } from '../api/types';

/**
 * Graphical view of the workflow YAML.
 *
 * Step cards in dependency order, with each input rendered as a chip naming the
 * step that produced it. That lineage is the data-flow graph, which is not the
 * same as `dependsOn` - a step can read from an ancestor it does not directly
 * depend on. A rendered node graph is deliberately out of scope; that is W5-v3,
 * parked until after the demo.
 */
const TABS: string[] = [
  'pipeline',
  'dag',
  'runs',
  'schedules',
  'time',
  'environments',
  'yaml',
];

const WorkflowDetailPage = () => {
  const { workflowId = '' } = useParams();
  const navigate = useNavigate();

  // Tab lives in the URL so links can point straight at it and a refresh
  // keeps you where you were.
  const [scheduleOpen, setScheduleOpen] = useState(false);

  const [searchParams, setSearchParams] = useSearchParams();
  const tab = Math.max(0, TABS.indexOf(searchParams.get('tab') ?? ''));
  const setTab = (next: number) =>
    setSearchParams(next === 0 ? {} : { tab: TABS[next] }, { replace: true });

  const { data, isPending, error } = useQuery({
    queryKey: ['workflow', workflowId],
    queryFn: () => getWorkflow(workflowId),
    enabled: Boolean(workflowId),
  });

  const runs = useQuery({
    queryKey: ['runs', workflowId],
    queryFn: () => findExecutions(undefined, workflowId),
    enabled: Boolean(workflowId),
    refetchInterval: (query) =>
      query.state.data?.some((r) => r.status === 'RUNNING') ? 2000 : false,
  });

  const schedules = useQuery({
    queryKey: ['schedules', workflowId],
    queryFn: () => listSchedules(workflowId),
    enabled: Boolean(workflowId),
  });

  const timeParameters = useQuery({
    queryKey: ['time-parameters', workflowId],
    queryFn: () => getTimeParameters(workflowId),
    enabled: Boolean(workflowId),
  });

  // Found but not yet chosen: ask, rather than let runs silently keep what is written.
  const unbound =
    (timeParameters.data?.detected.length ?? 0) > 0 && !timeParameters.data?.bindings;

  const run = useMutation({
    mutationFn: () => executeWorkflow(workflowId),
    onSuccess: (state) => navigate(`/runs/${state.executionId}`),
  });

  if (isPending) return <CircularProgress />;
  if (error) return <Alert severity="error">{(error as Error).message}</Alert>;
  if (!data) return null;

  return (
    <Stack spacing={3}>
      <Header
        detail={data}
        onRun={() => run.mutate()}
        onSchedule={() => setScheduleOpen(true)}
        running={run.isPending}
      />

      <ScheduleDialog
        workflowId={workflowId}
        workflowName={data.summary.name}
        open={scheduleOpen}
        onClose={() => setScheduleOpen(false)}
        onSaved={() => {
          // Show what was just created rather than leaving it invisible.
          schedules.refetch();
          setTab(TABS.indexOf('schedules'));
        }}
      />

      {run.error && (
        <Alert severity="error">{(run.error as Error).message}</Alert>
      )}

      {unbound && tab !== TABS.indexOf('time') && (
        <Alert
          severity="info"
          action={
            <Button color="inherit" size="small" onClick={() => setTab(TABS.indexOf('time'))}>
              Choose
            </Button>
          }
        >
          This workflow has {timeParameters.data?.detected.length} date or time
          argument(s). Choose whether runs keep them, fix them, or follow a
          time window.
        </Alert>
      )}

      <Tabs value={tab} onChange={(_, v) => setTab(v)}>
        <Tab label={`Pipeline (${data.definition.steps.length} steps)`} />
        <Tab label="DAG" />
        <Tab label={`Runs (${runs.data?.length ?? 0})`} />
        <Tab label={`Schedules (${schedules.data?.length ?? 0})`} />
        <Tab label={`Time (${timeParameters.data?.detected.length ?? 0})`} />
        <Tab label="Environments" />
        <Tab label="YAML" />
      </Tabs>

      {tab === 0 && (
        <Stack spacing={2}>
          {data.definition.steps.map((step, index) => (
            <StepCard key={step.id} step={step} index={index} />
          ))}
        </Stack>
      )}

      {tab === 1 && <WorkflowDag steps={data.definition.steps} />}

      {tab === 2 && (
        <RunsTable
          runs={runs.data ?? []}
          showWorkflow={false}
          empty="This workflow has not been run yet."
        />
      )}

      {tab === 3 && (
        <SchedulesTable
          schedules={schedules.data ?? []}
          showWorkflow={false}
          empty="No schedules for this workflow yet."
        />
      )}

      {tab === 4 &&
        (timeParameters.data ? (
          <TimeParametersPanel workflowId={workflowId} view={timeParameters.data} />
        ) : timeParameters.error ? (
          <Alert severity="error">{(timeParameters.error as Error).message}</Alert>
        ) : (
          <CircularProgress />
        ))}

      {tab === 5 && (
        <Stack spacing={2}>
          {Object.entries(data.definition.environments).map(([id, env]) => (
            <Paper key={id} variant="outlined" sx={{ p: 2 }}>
              <Typography variant="h4_web">
                {env.name}{' '}
                <Chip label={env.kind} size="small" sx={{ ml: 1 }} />
              </Typography>
              <Typography variant="h6" color="text.secondary" gutterBottom>
                {id}
              </Typography>
              <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap' }}>
                {env.spec.pythonVersion.map((v) => (
                  <Chip key={v} label={`python ${v}`} size="small" color="primary" />
                ))}
                {env.spec.dependencies.map((d) => (
                  <Chip key={d} label={d} size="small" variant="outlined" />
                ))}
              </Stack>
            </Paper>
          ))}
        </Stack>
      )}

      {tab === 6 && (
        <Paper variant="outlined" sx={{ p: 2, overflowX: 'auto' }}>
          <Box
            component="pre"
            sx={{ m: 0, fontSize: '0.8125rem', lineHeight: 1.5 }}
          >
            {data.rawYaml}
          </Box>
        </Paper>
      )}
    </Stack>
  );
};

const Header = ({
  detail,
  onRun,
  onSchedule,
  running,
}: {
  detail: Detail;
  onRun: () => void;
  onSchedule: () => void;
  running: boolean;
}) => (
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
        {detail.summary.name}
      </Typography>
      <Typography variant="h3_web" color="text.secondary" sx={{ maxWidth: 760 }}>
        {detail.summary.description}
      </Typography>
      <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap', mt: 1 }}>
        <Chip label={`v${detail.summary.version}`} size="small" color="primary" />
        {detail.summary.draft && (
          <Chip label="draft - not runnable" size="small" color="warning" />
        )}
        {detail.summary.tags.map((tag) => (
          <Chip key={tag} label={tag} size="small" variant="outlined" />
        ))}
      </Stack>
    </Box>
    <Stack spacing={1} sx={{ flexShrink: 0 }}>
      <Button
        variant="contained"
        startIcon={<PlayArrowIcon />}
        onClick={onRun}
        // A draft skipped the checks that decide whether it can run at all.
        disabled={running || detail.summary.draft}
      >
        Execute now
      </Button>
      <Button
        variant="outlined"
        startIcon={<EditIcon />}
        component={RouterLink}
        to={`/compose?workflow=${encodeURIComponent(detail.summary.workflowId)}`}
      >
        Edit
      </Button>
      <Button
        variant="outlined"
        startIcon={<ScheduleIcon />}
        onClick={onSchedule}
      >
        Schedule
      </Button>
    </Stack>
  </Box>
);

/** Path-shaped and script-suffixed, so it can be fetched from the repo. */
const isScript = (value: string) =>
  value.includes('/') && /\.(py|R|r|sh|jl|kt)$/.test(value);

const StepCard = ({ step, index }: { step: StepSpec; index: number }) => {
  // A library step (`uses`) carries no task of its own - the library supplies
  // it. Prefer a script path over a bare executable, since that is the thing
  // worth linking to.
  const scriptArg = step.task?.args.find(isScript);
  const command =
    step.task?.entryPoint?.scriptPath ??
    scriptArg ??
    step.task?.executable ??
    step.task?.entryPoint?.module ??
    step.uses ??
    step.task?.type;

  return (
    <Paper variant="outlined" sx={{ p: 2 }} id={`step-${step.id}`}>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'flex-start' }}>
        <Box
          sx={{
            width: 32,
            height: 32,
            flexShrink: 0,
            borderRadius: '50%',
            display: 'grid',
            placeItems: 'center',
            fontWeight: 700,
            color: '#fff',
            backgroundColor: (t) => t.palette.primary.main,
          }}
        >
          {index + 1}
        </Box>

        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography variant="h4_web">{step.metadata?.name ?? step.id}</Typography>
          <Typography variant="h6" color="text.secondary">
            {step.id}
          </Typography>
          {step.metadata?.description && (
            <Typography variant="h5_web" sx={{ mt: 1 }}>
              {step.metadata.description}
            </Typography>
          )}

          <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap', mt: 1.5 }}>
            <Chip
              label={step.uses ? 'library' : (step.task?.type ?? 'step')}
              size="small"
              color="secondary"
            />
            {step.uses ? (
              // A library step: link straight to its entry.
              <Chip
                label={step.uses}
                size="small"
                variant="outlined"
                clickable
                component={RouterLink}
                to={`/library/${step.uses}`}
                sx={{ fontFamily: 'monospace' }}
              />
            ) : (
              command &&
              // A script path resolves in the repo, so link it; anything else
              // (a bare executable like "python") is just a label.
              (isScript(command) ? (
                <Chip
                  label={command}
                  size="small"
                  variant="outlined"
                  clickable
                  component="a"
                  href={`/api/repo/scripts/${command.replace(/^scripts\//, '')}`}
                  target="_blank"
                  rel="noreferrer"
                  sx={{ fontFamily: 'monospace' }}
                />
              ) : (
                <Chip label={command} size="small" variant="outlined" />
              ))
            )}
            {step.environmentId && (
              <Chip label={step.environmentId} size="small" variant="outlined" />
            )}
          </Stack>

          <Divider sx={{ my: 1.5 }} />

          <Stack
            direction={{ xs: 'column', md: 'row' }}
            spacing={2}
            divider={<Divider orientation="vertical" flexItem />}
          >
            <PortColumn title="Inputs" empty="No inputs - this step starts the pipeline">
              {step.inputs.map((input) => (
                <Stack
                  key={input.id}
                  direction="row"
                  spacing={0.5}
                 
                  sx={{ alignItems: 'center', flexWrap: 'wrap' }}
                >
                  {input.source?.stepId && (
                    <>
                      <Chip
                        label={input.source.stepId}
                        size="small"
                        component="a"
                        href={`#step-${input.source.stepId}`}
                        clickable
                        sx={{ fontFamily: 'monospace' }}
                      />
                      <ArrowForwardIcon fontSize="inherit" color="disabled" />
                    </>
                  )}
                  <Typography variant="h5_web">{input.id}</Typography>
                  {input.descriptor?.fileFormat && (
                    <Chip label={input.descriptor.fileFormat} size="small" variant="outlined" />
                  )}
                </Stack>
              ))}
            </PortColumn>

            <PortColumn title="Outputs" empty="No declared outputs">
              {step.outputs.map((output) => (
                <Box key={output.id}>
                  <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                    <Typography variant="h5_web">{output.id}</Typography>
                    {output.descriptor?.fileFormat && (
                      <Chip
                        label={output.descriptor.fileFormat}
                        size="small"
                        variant="outlined"
                      />
                    )}
                  </Stack>
                  {output.descriptor?.notes && (
                    <Typography variant="h6" color="text.secondary">
                      {output.descriptor.notes}
                    </Typography>
                  )}
                </Box>
              ))}
            </PortColumn>
          </Stack>
        </Box>
      </Stack>
    </Paper>
  );
};

const PortColumn = ({
  title,
  empty,
  children,
}: {
  title: string;
  empty: string;
  children: React.ReactNode[];
}) => (
  <Box sx={{ flex: 1, minWidth: 0 }}>
    <Typography variant="h6" color="text.secondary" gutterBottom>
      {title.toUpperCase()}
    </Typography>
    {children.length === 0 ? (
      <Typography variant="h5_web" color="text.disabled">
        {empty}
      </Typography>
    ) : (
      <Stack spacing={1}>{children}</Stack>
    )}
  </Box>
);

export default WorkflowDetailPage;
