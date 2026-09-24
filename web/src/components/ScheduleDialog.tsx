import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
} from '@mui/material';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { createSchedule, getTimeParameters } from '../api/client';
import { fromLocalInput } from './TimeParametersPanel';

/**
 * Schedule a workflow to run on a cadence.
 *
 * The request shape is invented - core has no schedule service to mirror - and
 * kept thin for that reason. The window each run reads comes from the
 * workflow's time parameters, not from here.
 */
export const CADENCES = [
  { value: 'EVERY_15_MINUTES', label: 'Every 15 minutes' },
  { value: 'HOURLY', label: 'Every hour' },
  { value: 'DAILY', label: 'Every day' },
  { value: 'WEEKLY', label: 'Every week' },
];

const ScheduleDialog = ({
  workflowId,
  workflowName,
  open,
  onClose,
  onSaved,
}: {
  workflowId: string;
  workflowName: string;
  open: boolean;
  onClose: () => void;
  onSaved?: () => void;
}) => {
  const [cadence, setCadence] = useState('DAILY');
  const [startAt, setStartAt] = useState('');

  const timeParameters = useQuery({
    queryKey: ['time-parameters', workflowId],
    queryFn: () => getTimeParameters(workflowId),
    enabled: open,
  });

  const followsWindow = timeParameters.data?.bindings?.parameters.some(
    (b) => b.mode === 'WINDOW_START' || b.mode === 'WINDOW_END',
  );

  const save = useMutation({
    mutationFn: () =>
      createSchedule(workflowId, cadence, fromLocalInput(startAt) ?? undefined),
    onSuccess: () => {
      onClose();
      onSaved?.();
    },
  });

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Schedule {workflowName}</DialogTitle>

      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Alert severity={followsWindow ? 'info' : 'warning'}>
            {followsWindow
              ? 'Each run reads the data since the last successful run, following the window set under Time.'
              : 'No time window is set under Time, so each run reads what the workflow says as written.'}
          </Alert>

          <TextField
            select
            label="Run"
            value={cadence}
            onChange={(e) => setCadence(e.target.value)}
            fullWidth
          >
            {CADENCES.map((c) => (
              <MenuItem key={c.value} value={c.value}>
                {c.label}
              </MenuItem>
            ))}
          </TextField>

          <TextField
            label="First run"
            helperText="Later runs follow at the chosen interval. Leave empty to start now."
            type="datetime-local"
            value={startAt}
            onChange={(e) => setStartAt(e.target.value)}
            slotProps={{ inputLabel: { shrink: true } }}
            fullWidth
          />

          {save.error && (
            <Alert severity="error">{(save.error as Error).message}</Alert>
          )}
        </Stack>
      </DialogContent>

      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          onClick={() => save.mutate()}
          disabled={save.isPending}
        >
          Save schedule
        </Button>
      </DialogActions>
    </Dialog>
  );
};

export default ScheduleDialog;
