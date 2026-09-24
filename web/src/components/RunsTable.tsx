import ScienceIcon from '@mui/icons-material/Science';
import {
  Alert,
  Button,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { useNavigate } from 'react-router-dom';
import type { ExecutorState } from '../api/types';
import StatusPill from './StatusPill';

/**
 * Shared run list, used both for a whole study and for one workflow.
 *
 * Workflow names are joined in by the caller rather than returned by the
 * service: ExecutorState carries only workflowId, and it mirrors core, so
 * adding a name to it here would put the mock out of step with the real type.
 */
const RunsTable = ({
  runs,
  workflowNames,
  showWorkflow = true,
  empty = 'No runs yet.',
}: {
  runs: ExecutorState[];
  workflowNames?: Record<string, string>;
  showWorkflow?: boolean;
  empty?: string;
}) => {
  const navigate = useNavigate();

  if (runs.length === 0) return <Alert severity="info">{empty}</Alert>;

  return (
    <Paper variant="outlined">
      <Table>
        <TableHead>
          <TableRow>
            {showWorkflow && <TableCell>Workflow</TableCell>}
            <TableCell>Started</TableCell>
            <TableCell>Duration</TableCell>
            <TableCell>Status</TableCell>
            <TableCell align="right">Results</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {runs.map((run) => (
            <TableRow
              key={run.executionId}
              hover
              sx={{ cursor: 'pointer' }}
              onClick={() => navigate(`/runs/${run.executionId}`)}
            >
              {showWorkflow && (
                <TableCell>
                  <Typography variant="h5">
                    {workflowNames?.[run.workflowId] ?? run.workflowId}
                  </Typography>
                </TableCell>
              )}

              <TableCell>
                <Typography variant="h5_web">
                  {new Date(run.startedAt).toLocaleString()}
                </Typography>
                <Typography
                  variant="h6"
                  color="text.secondary"
                  sx={{ fontFamily: 'monospace' }}
                >
                  {run.executionId.slice(0, 8)}
                  {run.context?.trigger === 'SCHEDULE' && ' · scheduled'}
                </Typography>
                {run.context?.windowFrom && (
                  <Typography variant="h6" color="text.secondary">
                    {windowOf(run)}
                  </Typography>
                )}
              </TableCell>

              <TableCell>{durationOf(run)}</TableCell>

              <TableCell>
                <StatusPill status={run.status} />
              </TableCell>

              <TableCell align="right">
                <Button
                  size="small"
                  startIcon={<ScienceIcon />}
                  disabled={run.status === 'RUNNING'}
                  onClick={(e) => {
                    e.stopPropagation();
                    navigate(`/runs/${run.executionId}/results`);
                  }}
                >
                  View
                </Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Paper>
  );
};

/** The run's time window, `from - to`, in local time. */
export const windowOf = (run: ExecutorState): string => {
  const from = run.context?.windowFrom;
  const to = run.context?.windowTo;
  const at = (iso?: string | null) => (iso ? new Date(iso).toLocaleString() : 'open');
  return `window ${at(from)} - ${at(to)}`;
};

const durationOf = (run: ExecutorState): string => {
  if (!run.completedAt) return 'running';
  const ms =
    new Date(run.completedAt).getTime() - new Date(run.startedAt).getTime();
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
};

export default RunsTable;
