import KeyboardArrowDownIcon from '@mui/icons-material/KeyboardArrowDown';
import KeyboardArrowUpIcon from '@mui/icons-material/KeyboardArrowUp';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Collapse,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { listResultSets, listWorkflows } from '../api/client';
import type { ResultSet } from '../api/types';
import ArtifactPreview from '../components/ArtifactPreview';
import StatusPill from '../components/StatusPill';

/** Table rows shown per final output when a row is opened. */
const PREVIEW_ROWS = 3;

/** Columns in the results table, for the preview row to span. */
const COLUMNS = 5;

/**
 * Every run's results, one row per run, filtered by workflow and date.
 *
 * A row opens that run's results page. The arrow shows a preview of the run's
 * final outputs without leaving the list.
 */
const ResultSets = () => {
  const [workflowId, setWorkflowId] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  const sets = useQuery({ queryKey: ['result-sets'], queryFn: () => listResultSets() });
  const workflows = useQuery({ queryKey: ['workflows'], queryFn: () => listWorkflows() });

  const names = Object.fromEntries((workflows.data ?? []).map((w) => [w.workflowId, w.name]));
  const all = sets.data ?? [];
  const workflowIds = [...new Set(all.map((s) => s.run.workflowId))];

  // Dates are local days, inclusive at both ends.
  const fromTime = from ? new Date(`${from}T00:00:00`).getTime() : -Infinity;
  const toTime = to ? new Date(`${to}T23:59:59.999`).getTime() : Infinity;

  const shown = all.filter((s) => {
    const started = new Date(s.run.startedAt).getTime();
    return (!workflowId || s.run.workflowId === workflowId) && started >= fromTime && started <= toTime;
  });

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1_web" component="h1">
          Results
        </Typography>
        <Typography variant="h3_web" color="text.secondary">
          What each run produced
        </Typography>
      </Box>

      <Stack direction="row" spacing={2} sx={{ alignItems: 'center', flexWrap: 'wrap' }} useFlexGap>
        <TextField
          select
          size="small"
          label="Workflow"
          value={workflowId}
          onChange={(e) => setWorkflowId(e.target.value)}
          sx={{ minWidth: 240 }}
        >
          <MenuItem value="">All workflows</MenuItem>
          {workflowIds.map((id) => (
            <MenuItem key={id} value={id}>
              {names[id] ?? id}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          type="date"
          size="small"
          label="From"
          value={from}
          onChange={(e) => setFrom(e.target.value)}
          slotProps={{ inputLabel: { shrink: true } }}
        />
        <TextField
          type="date"
          size="small"
          label="To"
          value={to}
          onChange={(e) => setTo(e.target.value)}
          slotProps={{ inputLabel: { shrink: true } }}
        />
        <Button
          size="small"
          disabled={!workflowId && !from && !to}
          onClick={() => {
            setWorkflowId('');
            setFrom('');
            setTo('');
          }}
        >
          Clear
        </Button>
        {sets.data && (
          <Typography variant="h6" color="text.secondary">
            {shown.length} of {all.length}
          </Typography>
        )}
      </Stack>

      {sets.isPending && <CircularProgress />}
      {sets.error && <Alert severity="error">{(sets.error as Error).message}</Alert>}
      {sets.data && all.length === 0 && (
        <Alert severity="info">No results yet. They appear here once a run has produced something.</Alert>
      )}
      {sets.data && all.length > 0 && shown.length === 0 && (
        <Alert severity="info">No results match these filters.</Alert>
      )}

      {shown.length > 0 && (
        <Paper variant="outlined">
          <Table>
            <TableHead>
              <TableRow>
                <TableCell padding="checkbox" />
                <TableCell>Workflow</TableCell>
                <TableCell>Started</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Final outputs</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {shown.map((set) => (
                <ResultRow
                  key={set.run.executionId}
                  set={set}
                  workflowName={names[set.run.workflowId] ?? set.run.workflowId}
                />
              ))}
            </TableBody>
          </Table>
        </Paper>
      )}
    </Stack>
  );
};

const ResultRow = ({ set, workflowName }: { set: ResultSet; workflowName: string }) => {
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();
  const { run, finals, outputCount } = set;

  return (
    <>
      <TableRow
        hover
        sx={{ cursor: 'pointer', '& > td': { borderBottom: open ? 'none' : undefined } }}
        onClick={() => navigate(`/runs/${run.executionId}/results`)}
      >
        <TableCell padding="checkbox">
          <IconButton
            size="small"
            aria-label={open ? 'Hide preview' : 'Show preview'}
            disabled={finals.length === 0}
            onClick={(e) => {
              e.stopPropagation();
              setOpen(!open);
            }}
          >
            {open ? <KeyboardArrowUpIcon /> : <KeyboardArrowDownIcon />}
          </IconButton>
        </TableCell>
        <TableCell>
          <Typography variant="h5">{workflowName}</Typography>
        </TableCell>
        <TableCell>
          <Typography variant="h5_web">{new Date(run.startedAt).toLocaleString()}</Typography>
          <Typography variant="h6" color="text.secondary" sx={{ fontFamily: 'monospace' }}>
            {run.executionId.slice(0, 8)}
            {run.context?.trigger === 'SCHEDULE' && ' · scheduled'}
          </Typography>
        </TableCell>
        <TableCell>
          <StatusPill status={run.status} />
        </TableCell>
        <TableCell>
          <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center' }}>
            {finals.map((entry) => (
              <Chip
                key={`${entry.stepId}/${entry.outputId}`}
                size="small"
                variant="outlined"
                label={entry.outputName ?? entry.outputId}
              />
            ))}
            {finals.length === 0 && (
              <Typography variant="h6" color="text.secondary">
                Final steps did not run
              </Typography>
            )}
            <Typography variant="h6" color="text.secondary">
              {outputCount} output{outputCount === 1 ? '' : 's'} in all
            </Typography>
          </Stack>
        </TableCell>
      </TableRow>

      <TableRow>
        <TableCell colSpan={COLUMNS} sx={{ py: 0 }}>
          <Collapse in={open} unmountOnExit>
            <Stack spacing={2} sx={{ py: 2 }}>
              {finals.map((entry) => (
                <Box key={`${entry.stepId}/${entry.outputId}`}>
                  <Typography variant="h5">{entry.outputName ?? entry.outputId}</Typography>
                  <Typography variant="h6" color="text.secondary">
                    produced by {entry.stepName ?? entry.stepId}
                  </Typography>
                  <ArtifactPreview entry={entry} maxRows={PREVIEW_ROWS} />
                </Box>
              ))}
            </Stack>
          </Collapse>
        </TableCell>
      </TableRow>
    </>
  );
};

export default ResultSets;
