import {
  Alert,
  Button,
  Chip,
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
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { saveTimeParameters } from '../api/client';
import type {
  BindingMode,
  ParameterBinding,
  TimeParameter,
  TimeParametersView,
} from '../api/types';

/**
 * How runs set a workflow's time parameters.
 *
 * Each date or time found in a step's arguments can be kept as written, fixed
 * to a date, or follow the window: a run started by hand reads from the window
 * start up to now, and a schedule moves the window forward run by run. The
 * workflow file itself is never changed.
 */
const MODES: { value: BindingMode; label: string }[] = [
  { value: 'KEEP', label: 'Keep as written' },
  { value: 'FIXED', label: 'Fixed date' },
  { value: 'WINDOW_START', label: 'Window start' },
  { value: 'WINDOW_END', label: 'Window end' },
];

const keyOf = (p: { stepKey: string; flag: string; name?: string | null }) =>
  `${p.stepKey} ${p.flag} ${p.name ?? ''}`;

/** ISO instant to the value a datetime-local input shows, in local time. */
export const toLocalInput = (iso?: string | null): string => {
  if (!iso) return '';
  const date = new Date(iso);
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
};

/** A datetime-local value, read as local time, to an ISO instant. */
export const fromLocalInput = (value: string): string | null =>
  value ? new Date(value).toISOString() : null;

const TimeParametersPanel = ({
  workflowId,
  view,
}: {
  workflowId: string;
  view: TimeParametersView;
}) => {
  const queryClient = useQueryClient();

  const initial = (): Record<string, ParameterBinding> =>
    Object.fromEntries(
      view.detected.map((p) => {
        const saved = view.bindings?.parameters.find(
          (b) => keyOf(b) === keyOf(p),
        );
        return [
          keyOf(p),
          saved ?? {
            stepKey: p.stepKey,
            flag: p.flag,
            name: p.name ?? null,
            mode: 'KEEP' as BindingMode,
          },
        ];
      }),
    );

  const [parameters, setParameters] = useState(initial);
  const [windowStart, setWindowStart] = useState(
    toLocalInput(view.bindings?.windowStart),
  );
  const [windowEnd, setWindowEnd] = useState(
    toLocalInput(view.bindings?.windowEnd),
  );

  // A save or a refetch brings new saved values; show those.
  useEffect(() => {
    setParameters(initial());
    setWindowStart(toLocalInput(view.bindings?.windowStart));
    setWindowEnd(toLocalInput(view.bindings?.windowEnd));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view]);

  const save = useMutation({
    mutationFn: () =>
      saveTimeParameters({
        workflowId,
        windowStart: fromLocalInput(windowStart),
        windowEnd: fromLocalInput(windowEnd),
        parameters: Object.values(parameters),
      }),
    onSuccess: (next) => {
      queryClient.setQueryData(['time-parameters', workflowId], next);
      queryClient.invalidateQueries({ queryKey: ['schedules'] });
    },
  });

  const update = (p: TimeParameter, change: Partial<ParameterBinding>) =>
    setParameters((current) => ({
      ...current,
      [keyOf(p)]: { ...current[keyOf(p)], ...change },
    }));

  const followsWindow = Object.values(parameters).some(
    (b) => b.mode === 'WINDOW_START' || b.mode === 'WINDOW_END',
  );

  if (view.detected.length === 0) {
    return (
      <Alert severity="info">
        No dates or times were found in this workflow&apos;s step arguments.
        Scheduled runs will run it as written.
      </Alert>
    );
  }

  return (
    <Stack spacing={2}>
      <Typography variant="h5_web" color="text.secondary">
        Choose how each run sets the dates and times found in this
        workflow&apos;s steps. The workflow file is not changed.
      </Typography>

      {view.stale.length > 0 && (
        <Alert severity="warning">
          {view.stale.length} saved setting(s) no longer match an argument in
          the workflow and are ignored:{' '}
          {view.stale.map((b) => `${b.flag} ${b.name ?? ''}`.trim()).join(', ')}
        </Alert>
      )}

      <Paper variant="outlined">
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Step</TableCell>
              <TableCell>Argument</TableCell>
              <TableCell>In the file</TableCell>
              <TableCell>Runs use</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {view.detected.map((p) => {
              const binding = parameters[keyOf(p)];
              return (
                <TableRow key={keyOf(p)}>
                  <TableCell>
                    <Typography variant="h5">{p.stepKey}</Typography>
                  </TableCell>
                  <TableCell>
                    <Typography variant="h5_web" sx={{ fontFamily: 'monospace' }}>
                      {p.label}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Typography variant="h5_web" sx={{ fontFamily: 'monospace' }}>
                      {p.value}
                    </Typography>
                    <Chip
                      label={p.format.toLowerCase().replace(/_/g, ' ')}
                      size="small"
                      sx={{ mt: 0.5 }}
                    />
                  </TableCell>
                  <TableCell sx={{ minWidth: 220 }}>
                    <Stack spacing={1}>
                      <TextField
                        select
                        size="small"
                        value={binding.mode}
                        onChange={(e) =>
                          update(p, { mode: e.target.value as BindingMode })
                        }
                      >
                        {MODES.map((m) => (
                          <MenuItem key={m.value} value={m.value}>
                            {m.label}
                          </MenuItem>
                        ))}
                      </TextField>
                      {binding.mode === 'FIXED' && (
                        <TextField
                          type="datetime-local"
                          size="small"
                          value={toLocalInput(binding.value)}
                          onChange={(e) =>
                            update(p, { value: fromLocalInput(e.target.value) })
                          }
                        />
                      )}
                    </Stack>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </Paper>

      {followsWindow && (
        <Stack direction="row" spacing={2}>
          <TextField
            label="Window start"
            type="datetime-local"
            required
            value={windowStart}
            onChange={(e) => setWindowStart(e.target.value)}
            slotProps={{ inputLabel: { shrink: true } }}
            helperText="The first run reads from here"
          />
          <TextField
            label="Window end"
            type="datetime-local"
            value={windowEnd}
            onChange={(e) => setWindowEnd(e.target.value)}
            slotProps={{ inputLabel: { shrink: true } }}
            helperText="Optional. A schedule stops here"
          />
        </Stack>
      )}

      {save.error && (
        <Alert severity="error">{(save.error as Error).message}</Alert>
      )}
      {save.isSuccess && !save.isPending && (
        <Alert severity="success">Saved. The next run uses these settings.</Alert>
      )}

      <Stack direction="row">
        <Button
          variant="contained"
          disabled={save.isPending || (followsWindow && !windowStart)}
          onClick={() => save.mutate()}
        >
          Save
        </Button>
      </Stack>
    </Stack>
  );
};

export default TimeParametersPanel;
