import BoltIcon from '@mui/icons-material/Bolt';
import DeleteIcon from '@mui/icons-material/Delete';
import KeyboardArrowDownIcon from '@mui/icons-material/KeyboardArrowDown';
import KeyboardArrowUpIcon from '@mui/icons-material/KeyboardArrowUp';
import {
  Alert,
  Box,
  Chip,
  Collapse,
  IconButton,
  Tooltip,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Fragment, useState } from 'react';
import { deleteSchedule, setScheduleEnabled } from '../api/client';
import type { Schedule } from '../api/types';
import { CADENCES } from './ScheduleDialog';
import RunsTable from './RunsTable';
import StatusPill from './StatusPill';

/**
 * Saved schedules, for a study or for one workflow, with where each stands.
 *
 * Next run and window come from the server, worked out from the schedule's
 * runs. A row opens onto those runs.
 */
const CADENCE_LABELS: Record<string, string> = Object.fromEntries(
  CADENCES.map((c) => [c.value, c.label]),
);

const when = (iso?: string | null) => (iso ? new Date(iso).toLocaleString() : '-');

const SchedulesTable = ({
  schedules,
  workflowNames,
  showWorkflow = true,
  empty = 'No schedules yet.',
}: {
  schedules: Schedule[];
  workflowNames?: Record<string, string>;
  showWorkflow?: boolean;
  empty?: string;
}) => {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState<string | null>(null);

  const remove = useMutation({
    mutationFn: (scheduleId: string) => deleteSchedule(scheduleId),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ['schedules'] }),
  });

  const toggle = useMutation({
    mutationFn: (schedule: Schedule) =>
      setScheduleEnabled(schedule.scheduleId, !schedule.enabled),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ['schedules'] }),
  });

  if (schedules.length === 0) return <Alert severity="info">{empty}</Alert>;

  const columns = showWorkflow ? 8 : 7;

  return (
    <Paper variant="outlined">
      <Table>
        <TableHead>
          <TableRow>
            <TableCell />
            {showWorkflow && <TableCell>Workflow</TableCell>}
            <TableCell>Cadence</TableCell>
            <TableCell>Next run</TableCell>
            <TableCell>Next window from</TableCell>
            <TableCell>Runs</TableCell>
            <TableCell>State</TableCell>
            <TableCell align="right" />
          </TableRow>
        </TableHead>
        <TableBody>
          {schedules.map((schedule) => {
            const expanded = open === schedule.scheduleId;
            const last = schedule.runs[0];
            return (
              <Fragment key={schedule.scheduleId}>
                <TableRow hover>
                  <TableCell padding="checkbox">
                    <IconButton
                      size="small"
                      aria-label="Show runs"
                      onClick={() => setOpen(expanded ? null : schedule.scheduleId)}
                    >
                      {expanded ? <KeyboardArrowUpIcon /> : <KeyboardArrowDownIcon />}
                    </IconButton>
                  </TableCell>

                  {showWorkflow && (
                    <TableCell>
                      <Typography variant="h5">
                        {workflowNames?.[schedule.workflowId] ?? schedule.workflowId}
                      </Typography>
                    </TableCell>
                  )}

                  <TableCell>
                    <Chip
                      label={CADENCE_LABELS[schedule.cadence] ?? schedule.cadence}
                      size="small"
                      color="primary"
                    />
                  </TableCell>

                  <TableCell>
                    <Typography variant="h5_web">{when(schedule.nextRunAt)}</Typography>
                  </TableCell>

                  <TableCell>
                    <Typography variant="h5_web">
                      {schedule.windowFrom ? when(schedule.windowFrom) : 'No window'}
                    </Typography>
                    {schedule.windowEnd && (
                      <Typography variant="h6" color="text.secondary">
                        until {when(schedule.windowEnd)}
                      </Typography>
                    )}
                  </TableCell>

                  <TableCell>
                    <Box sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
                      <Typography variant="h5_web">{schedule.runs.length}</Typography>
                      {last && <StatusPill status={last.status} />}
                    </Box>
                  </TableCell>

                  <TableCell>
                    {CADENCE_LABELS[schedule.cadence] === undefined ? (
                      <Chip label="not supported" size="small" />
                    ) : schedule.completed ? (
                      <Chip label="completed" size="small" />
                    ) : !schedule.enabled ? (
                      <Chip label="paused" size="small" />
                    ) : (
                      <Chip label="active" size="small" color="success" variant="outlined" />
                    )}
                  </TableCell>

                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                    <Tooltip title={schedule.enabled ? 'Pause' : 'Resume'}>
                      <IconButton
                        size="small"
                        aria-label={schedule.enabled ? 'Pause schedule' : 'Resume schedule'}
                        color={schedule.enabled ? 'warning' : 'default'}
                        disabled={toggle.isPending || schedule.completed}
                        onClick={() => toggle.mutate(schedule)}
                      >
                        <BoltIcon fontSize="small" />
                      </IconButton>
                    </Tooltip>
                    <IconButton
                      size="small"
                      aria-label="Delete schedule"
                      disabled={remove.isPending}
                      onClick={() => remove.mutate(schedule.scheduleId)}
                    >
                      <DeleteIcon fontSize="small" />
                    </IconButton>
                  </TableCell>
                </TableRow>

                <TableRow>
                  <TableCell colSpan={columns} sx={{ py: 0, borderBottom: expanded ? undefined : 'none' }}>
                    <Collapse in={expanded} unmountOnExit>
                      <Box sx={{ py: 2 }}>
                        <RunsTable
                          runs={schedule.runs}
                          showWorkflow={false}
                          empty="This schedule has not fired yet."
                        />
                      </Box>
                    </Collapse>
                  </TableCell>
                </TableRow>
              </Fragment>
            );
          })}
        </TableBody>
      </Table>
    </Paper>
  );
};

export default SchedulesTable;
