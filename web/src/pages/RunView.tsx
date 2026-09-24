import LayersIcon from '@mui/icons-material/Layers';
import ScienceIcon from '@mui/icons-material/Science';
import StopIcon from '@mui/icons-material/Stop';
import {
  Alert,
  Chip,
  Box,
  Breadcrumbs,
  Button,
  CircularProgress,
  Divider,
  LinearProgress,
  Link,
  Paper,
  Stack,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import {
  cancelExecution,
  getExecutionResult,
  getExecutionState,
  getStepOutput,
  getWorkflow,
} from '../api/client';
import StatusPill from '../components/StatusPill';
import { windowOf } from '../components/RunsTable';
import type { ProvisioningEvent, StepRunResult } from '../api/types';

/**
 * Live run status.
 *
 * Polls GetExecutionState, which is the path the real API will use. W4-1 adds
 * SSE or WebSocket streaming; until that is decided, polling is what both the
 * mock and the first real integration can rely on.
 */
const POLL_MS = 1000;

const RunView = () => {
  const { runId = '' } = useParams();
  const navigate = useNavigate();

  const queryClient = useQueryClient();

  const cancel = useMutation({
    mutationFn: () => cancelExecution(runId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['run', runId] });
      queryClient.invalidateQueries({ queryKey: ['report', runId] });
    },
  });

  const state = useQuery({
    queryKey: ['run', runId],
    queryFn: () => getExecutionState(runId),
    enabled: Boolean(runId),
    refetchInterval: (query) =>
      query.state.data?.status === 'RUNNING' ? POLL_MS : false,
  });

  const report = useQuery({
    queryKey: ['report', runId],
    queryFn: () => getExecutionResult(runId),
    enabled: Boolean(runId),
    refetchInterval: state.data?.status === 'RUNNING' ? POLL_MS : false,
  });

  // Only for the breadcrumb label - a run knows its workflow id, not its name.
  const workflow = useQuery({
    queryKey: ['workflow', state.data?.workflowId],
    queryFn: () => getWorkflow(state.data!.workflowId),
    enabled: Boolean(state.data?.workflowId),
  });

  if (state.isPending) return <CircularProgress />;
  if (state.error) return <Alert severity="error">{(state.error as Error).message}</Alert>;
  if (!state.data) return null;

  const provisioning = report.data?.provisioning ?? [];
  const steps = report.data?.stepResults ?? [];
  const done = steps.filter((s) => s.status === 'SUCCEEDED').length;
  const percent = steps.length ? (done / steps.length) * 100 : 0;
  const finished = state.data.status !== 'RUNNING';

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
          <Breadcrumbs sx={{ mb: 0.5 }}>
            <Link component={RouterLink} to="/runs" underline="hover">
              Runs
            </Link>
            <Link
              component={RouterLink}
              to={`/workflows/${state.data.workflowId}`}
              underline="hover"
            >
              {workflow.data?.summary.name ?? state.data.workflowId}
            </Link>
          </Breadcrumbs>
          <Typography variant="h1_web" component="h1">
            Run
          </Typography>
          <Typography variant="h6" color="text.secondary" sx={{ fontFamily: 'monospace' }}>
            {state.data.executionId}
          </Typography>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 1 }}>
            <StatusPill status={state.data.status} size="medium" />
            <Typography variant="h5_web" color="text.secondary">
              started {new Date(state.data.startedAt).toLocaleTimeString()}
            </Typography>
            {state.data.context?.trigger === 'SCHEDULE' && (
              <Chip label="scheduled" size="small" variant="outlined" />
            )}
          </Stack>
          {state.data.context?.windowFrom && (
            <Typography variant="h6" color="text.secondary" sx={{ mt: 0.5 }}>
              {windowOf(state.data)}
            </Typography>
          )}
          {(state.data.context?.values.length ?? 0) > 0 && (
            <Typography variant="h6" color="text.secondary" sx={{ fontFamily: 'monospace' }}>
              {state.data.context?.values
                .map((v) => `${v.stepKey}: ${v.key} ${v.value}`)
                .join('  ·  ')}
            </Typography>
          )}
        </Box>

        <Stack direction="row" spacing={1}>
          {!finished && (
            <Button
              variant="outlined"
              color="error"
              startIcon={<StopIcon />}
              disabled={cancel.isPending}
              onClick={() => cancel.mutate()}
            >
              Stop
            </Button>
          )}
          <Button
            variant="contained"
            startIcon={<ScienceIcon />}
            disabled={!finished}
            onClick={() => navigate(`/runs/${runId}/results`)}
          >
            View results
          </Button>
        </Stack>
      </Box>

      <Box>
        <LinearProgress
          variant="determinate"
          value={percent}
          sx={{ height: 8, borderRadius: 4 }}
        />
        <Typography variant="h6" color="text.secondary" sx={{ mt: 0.5 }}>
          {done} of {steps.length} steps complete
        </Typography>
      </Box>

      <Stack spacing={1.5}>
        {provisioning.map((event) => (
          <ProvisioningRow key={event.environmentId} event={event} />
        ))}
        {steps.map((step, index) => (
          <StepRow key={step.stepMetadata.id} step={step} index={index} />
        ))}
      </Stack>
    </Stack>
  );
};

/**
 * Environment provisioning, above the steps because it happens before them.
 *
 * Building one takes minutes and reusing one takes a moment, and the difference
 * is worth showing: a run that starts instantly is doing so for a reason.
 */
const ProvisioningRow = ({ event }: { event: ProvisioningEvent }) => {
  const active = event.status === 'RUNNING';

  return (
    <Paper
      variant="outlined"
      sx={{
        p: 2,
        borderColor: active
          ? 'primary.main'
          : event.match === 'SUPERSET'
            ? 'warning.main'
            : undefined,
        opacity: event.status === 'FAILED' ? 1 : 0.9,
      }}
    >
      <Stack
        direction="row"
        spacing={1}
        sx={{ alignItems: 'center', justifyContent: 'space-between' }}
      >
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
          <LayersIcon fontSize="small" color={active ? 'primary' : 'disabled'} />
          <Box>
            <Typography variant="h5_web">Environment: {event.name}</Typography>
            <Typography variant="h6" color="text.secondary">
              {active
                ? 'Preparing. A first build downloads and solves its packages, which can take minutes.'
                : event.match === 'EXACT'
                  ? 'Reused - already built on this machine'
                  : event.match === 'SUPERSET'
                    ? `Satisfied by a larger environment, which also has ${event.extras.join(', ')}`
                    : (event.message ?? 'Built')}
            </Typography>
          </Box>
        </Stack>

        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          {event.durationMs != null && (
            <Typography variant="h6" color="text.secondary">
              {event.durationMs < 1000
                ? `${event.durationMs}ms`
                : `${Math.round(event.durationMs / 1000)}s`}
            </Typography>
          )}
          <StatusPill status={event.status} />
        </Stack>
      </Stack>
    </Paper>
  );
};

const StepRow = ({ step, index }: { step: StepRunResult; index: number }) => {
  const active = step.status === 'RUNNING';

  return (
    <Paper
      variant="outlined"
      sx={{
        p: 2,
        borderColor: (t) => (active ? t.palette.primary.main : undefined),
        opacity: step.status === 'PENDING' ? 0.55 : 1,
      }}
    >
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
        <Box
          sx={{
            width: 28,
            height: 28,
            flexShrink: 0,
            borderRadius: '50%',
            display: 'grid',
            placeItems: 'center',
            fontWeight: 700,
            fontSize: '0.8125rem',
            color: '#fff',
            // primary.main rather than status.blue: carp-portal's palette
            // defines `blue` at runtime but leaves it out of the Palette type
            // augmentation, so status.blue does not typecheck. Same value.
            backgroundColor: (t) =>
              step.status === 'SUCCEEDED'
                ? t.palette.status.green
                : active
                  ? t.palette.primary.main
                  : t.palette.status.grey,
          }}
        >
          {index + 1}
        </Box>

        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography variant="h4_web">{step.stepMetadata.name}</Typography>
          <Typography variant="h6" color="text.secondary">
            {step.stepMetadata.descriptorId}
          </Typography>
        </Box>

        <StatusPill status={step.status} />
      </Stack>

      {step.failure && (
        <Alert severity="error" sx={{ mt: 1.5 }}>
          <strong>{step.failure.kind}</strong> - {step.failure.message}
        </Alert>
      )}

      {step.logTail.length > 0 && (
        <>
          <Divider sx={{ my: 1.5 }} />
          <Box
            component="pre"
            sx={{
              m: 0,
              p: 1.5,
              borderRadius: 1,
              fontSize: '0.75rem',
              lineHeight: 1.6,
              overflowX: 'auto',
              color: (t) => t.palette.grey[100],
              backgroundColor: (t) => t.palette.grey[900],
            }}
          >
            {step.logTail.join('\n')}
          </Box>
        </>
      )}

      {step.outputs.length > 0 && (
        <Typography variant="h6" color="text.secondary" sx={{ mt: 1 }}>
          {step.outputs.length} output(s):{' '}
          {step.outputs.map((o) => o.name ?? o.outputId).join(', ')}
        </Typography>
      )}

      <StepOutput step={step} />
    </Paper>
  );
};

/**
 * What the step printed, on request.
 *
 * Behind a toggle and fetched only when opened: most of the time the step's name
 * and how long it took are all anyone wants, and a chatty step would otherwise
 * weigh down a view that polls every second.
 */
const StepOutput = ({ step }: { step: StepRunResult }) => {
  const [open, setOpen] = useState(false);
  const url = step.detail?.outputUrl;

  const output = useQuery({
    queryKey: ['step-output', url],
    queryFn: () => getStepOutput(url!),
    enabled: open && Boolean(url),
  });

  if (!step.detail) return null;

  const { command, exitCode } = step.detail;

  return (
    <Box sx={{ mt: 1 }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
        <Button
          size="small"
          onClick={() => setOpen((wasOpen) => !wasOpen)}
          disabled={!url}
        >
          {url ? (open ? 'Hide output' : 'Show output') : 'No output'}
        </Button>
        {exitCode !== null && exitCode !== undefined && (
          <Typography variant="h6" color="text.secondary">
            exit {exitCode}
          </Typography>
        )}
      </Stack>

      {open && (
        <>
          {command.length > 0 && (
            <Box
              component="pre"
              sx={{
                m: 0,
                mt: 1,
                p: 1,
                borderRadius: 1,
                fontSize: '0.7rem',
                overflowX: 'auto',
                color: 'text.secondary',
                backgroundColor: (t) => t.palette.action.hover,
              }}
            >
              {command.join(' ')}
            </Box>
          )}
          <Box
            component="pre"
            sx={{
              m: 0,
              mt: 1,
              p: 1.5,
              borderRadius: 1,
              fontSize: '0.75rem',
              lineHeight: 1.6,
              maxHeight: 320,
              overflow: 'auto',
              color: (t) => t.palette.grey[100],
              backgroundColor: (t) => t.palette.grey[900],
            }}
          >
            {output.isPending
              ? 'Loading...'
              : output.error
                ? (output.error as Error).message
                : output.data}
          </Box>
        </>
      )}
    </Box>
  );
};

export default RunView;
