import AddIcon from '@mui/icons-material/Add';
import CheckIcon from '@mui/icons-material/Check';
import ErrorOutlineIcon from '@mui/icons-material/WarningAmber';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Paper,
  Stack,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { addDemoWorkflow, listDemoWorkflows, listWorkflows } from '../api/client';
import type { DemoWorkflow } from '../api/types';

/**
 * The demo workflows from carp-dsp, as a catalogue to copy from.
 *
 * Only workflows that work are synced: the `injections/` fixtures are excluded
 * at build time, since they are deliberately-broken clones used to prove the
 * validator fires rather than pipelines anyone would copy.
 */
const DemoWorkflowList = () => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [added, setAdded] = useState<Set<string>>(new Set());

  const demos = useQuery({
    queryKey: ['demoWorkflows'],
    queryFn: () => listDemoWorkflows(),
  });

  const inStudy = useQuery({
    queryKey: ['workflows'],
    queryFn: () => listWorkflows(),
  });

  const add = useMutation({
    mutationFn: (path: string) => addDemoWorkflow(path),
    onSuccess: (summary, path) => {
      setAdded((s) => new Set(s).add(path));
      queryClient.invalidateQueries({ queryKey: ['workflows'] });
      navigate(`/workflows/${summary.workflowId}`);
    },
  });

  if (demos.isPending) return <CircularProgress />;
  if (demos.error)
    return <Alert severity="error">{(demos.error as Error).message}</Alert>;

  const all = demos.data ?? [];
  const working = all.filter((d) => d.valid);
  const broken = all.filter((d) => !d.valid);
  const studyIds = new Set((inStudy.data ?? []).map((w) => w.workflowId));

  const row = (demo: DemoWorkflow) => (
    <DemoRow
      key={demo.path}
      demo={demo}
      alreadyAdded={studyIds.has(demo.workflowId) || added.has(demo.path)}
      adding={add.isPending && add.variables === demo.path}
      onAdd={() => add.mutate(demo.path)}
    />
  );

  return (
    <Stack spacing={3}>
      {add.error && (
        <Alert severity="error">{(add.error as Error).message}</Alert>
      )}

      <Box>
        <Typography variant="h2_web" component="h2" gutterBottom>
          Example workflows{' '}
          <Typography component="span" variant="h6" color="text.secondary">
            {working.length} from carp-dsp
          </Typography>
        </Typography>
        <Stack spacing={1.5}>{working.map(row)}</Stack>
      </Box>

      {/*
        The deliberately-broken injection fixtures are filtered out at sync
        time now, so anything landing here is a demo that stopped validating -
        a real problem, not a fixture doing its job.
      */}
      {broken.length > 0 && (
        <Box>
          <Typography variant="h2_web" component="h2" gutterBottom>
            Not currently valid{' '}
            <Typography component="span" variant="h6" color="text.secondary">
              {broken.length} cannot be added until fixed
            </Typography>
          </Typography>
          <Stack spacing={1.5}>{broken.map(row)}</Stack>
        </Box>
      )}
    </Stack>
  );
};

const DemoRow = ({
  demo,
  alreadyAdded,
  adding,
  onAdd,
}: {
  demo: DemoWorkflow;
  alreadyAdded: boolean;
  adding: boolean;
  onAdd: () => void;
}) => (
  <Paper variant="outlined" sx={{ p: 2 }}>
    <Stack
      direction="row"
      spacing={2}
      sx={{ alignItems: 'flex-start', justifyContent: 'space-between' }}
    >
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="h4_web">
          {demo.summary?.name ?? demo.workflowId}
        </Typography>
        <Typography
          variant="h6"
          color="text.secondary"
          sx={{ fontFamily: 'monospace' }}
        >
          {demo.path}
        </Typography>

        {demo.summary?.description && (
          <Typography
            variant="h5_web"
            sx={{
              mt: 1,
              display: '-webkit-box',
              WebkitLineClamp: 2,
              WebkitBoxOrient: 'vertical',
              overflow: 'hidden',
            }}
          >
            {demo.summary.description}
          </Typography>
        )}

        {demo.problem && (
          <Alert
            severity="warning"
            icon={<ErrorOutlineIcon fontSize="inherit" />}
            sx={{ mt: 1 }}
          >
            {demo.problem}
          </Alert>
        )}

        {demo.summary && (
          <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap', mt: 1 }}>
            <Chip
              label={`${demo.summary.stepCount} steps`}
              size="small"
              variant="outlined"
            />
            {demo.summary.tags.slice(0, 4).map((tag) => (
              <Chip key={tag} label={tag} size="small" variant="outlined" />
            ))}
          </Stack>
        )}
      </Box>

      <Button
        size="small"
        variant={alreadyAdded ? 'text' : 'contained'}
        startIcon={alreadyAdded ? <CheckIcon /> : <AddIcon />}
        disabled={!demo.valid || alreadyAdded || adding}
        onClick={onAdd}
        sx={{ flexShrink: 0 }}
      >
        {alreadyAdded ? 'In study' : 'Add to study'}
      </Button>
    </Stack>
  </Paper>
);

export default DemoWorkflowList;
