import {
  Alert,
  Box,
  Chip,
  CircularProgress,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  ToggleButton,
  ToggleButtonGroup,
  Tooltip,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getEnvironmentReuse,
  listEnvironments,
  setEnvironmentReuse,
  type EnvironmentReuse,
} from '../api/client';
import type { EnvironmentEntry } from '../api/types';

/** Dependencies shown before the rest collapse into "+n". */
const SHOWN_DEPENDENCIES = 4;

/**
 * The environments steps run in, as they sit on the state volume.
 *
 * Refreshes on its own, so a run started elsewhere shows up here as it builds
 * or reuses an environment.
 */
const Environments = () => {
  const { data, isPending, error } = useQuery({
    queryKey: ['environments'],
    queryFn: listEnvironments,
    refetchInterval: 15_000,
  });

  return (
    <Stack spacing={3}>
      <Stack direction="row" sx={{ alignItems: 'flex-end', justifyContent: 'space-between' }}>
        <Box>
          <Typography variant="h1_web" component="h1">
            Environments
          </Typography>
          <Typography variant="h3_web" color="text.secondary">
            Solved once, reused by every run that asks for the same thing
          </Typography>
        </Box>
        <ReuseToggle />
      </Stack>

      {isPending && <CircularProgress />}
      {error && <Alert severity="error">Could not load environments: {error.message}</Alert>}
      {data && data.length === 0 && (
        <Alert severity="info">
          No environments yet. The first run of a workflow builds the ones it needs.
        </Alert>
      )}
      {data && data.length > 0 && <EnvironmentsTable environments={data} />}
    </Stack>
  );
};

const EnvironmentsTable = ({ environments }: { environments: EnvironmentEntry[] }) => (
  <Paper variant="outlined">
    <Table>
      <TableHead>
        <TableRow>
          <TableCell>Environment</TableCell>
          <TableCell>Status</TableCell>
          <TableCell>Dependencies</TableCell>
          <TableCell align="right">Size</TableCell>
          <TableCell>Built</TableCell>
          <TableCell>Last used</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {environments.map((env) => (
          <TableRow key={`${env.kind}/${env.id}`}>
            <TableCell>
              <Typography variant="h5">{env.name}</Typography>
              <Typography variant="h6" color="text.secondary">
                {[env.kind, env.runtime].filter(Boolean).join(' · ')}
              </Typography>
            </TableCell>

            <TableCell>
              {env.status === 'solved' ? (
                <Chip size="small" color="success" label="Solved" />
              ) : (
                <Tooltip title="The build failed or was interrupted. The next run that needs it builds it again.">
                  <Chip size="small" color="warning" label="Incomplete" />
                </Tooltip>
              )}
            </TableCell>

            <TableCell>
              <Dependencies names={env.dependencies} />
            </TableCell>

            <TableCell align="right">
              {env.sizeBytes === null ? '—' : formatBytes(env.sizeBytes)}
            </TableCell>

            <TableCell>
              <When iso={env.builtAt} />
            </TableCell>

            <TableCell>
              <When iso={env.lastUsedAt} />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  </Paper>
);

const Dependencies = ({ names }: { names: string[] }) => {
  if (names.length === 0) return <>—</>;

  const shown = names.slice(0, SHOWN_DEPENDENCIES);
  const hidden = names.slice(SHOWN_DEPENDENCIES);

  return (
    <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5 }}>
      {shown.map((name) => (
        <Chip key={name} size="small" variant="outlined" label={name} />
      ))}
      {hidden.length > 0 && (
        <Tooltip title={hidden.join(', ')}>
          <Chip size="small" label={`+${hidden.length}`} />
        </Tooltip>
      )}
    </Box>
  );
};

/** A time as "3 min ago", with the exact time on hover. */
const When = ({ iso }: { iso: string | null }) => {
  if (!iso) return <>—</>;

  const date = new Date(iso);
  return (
    <Tooltip title={date.toLocaleString()}>
      <span>{ago(date)}</span>
    </Tooltip>
  );
};

/**
 * Exact or superset reuse, switched live. The server holds the setting, so
 * every run agrees on it.
 */
const ReuseToggle = () => {
  const queryClient = useQueryClient();
  const { data: mode } = useQuery({ queryKey: ['environment-reuse'], queryFn: getEnvironmentReuse });

  const change = useMutation({
    mutationFn: setEnvironmentReuse,
    onSuccess: (next) => queryClient.setQueryData(['environment-reuse'], next),
  });

  // Unknown means the server did not answer; a toggle would claim a setting it
  // cannot show.
  if (!mode) return null;

  return (
    <Tooltip
      title={
        mode === 'exact'
          ? 'Reuse only an environment built from exactly the same definition.'
          : 'An environment holding everything a workflow asks for may serve it, extra packages and all.'
      }
    >
      <ToggleButtonGroup
        size="small"
        exclusive
        value={mode}
        disabled={change.isPending}
        onChange={(_, next: EnvironmentReuse | null) => next && change.mutate(next)}
        aria-label="Environment reuse"
      >
        <ToggleButton value="exact">Exact</ToggleButton>
        <ToggleButton value="superset">Superset</ToggleButton>
      </ToggleButtonGroup>
    </Tooltip>
  );
};

const formatBytes = (bytes: number): string => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} kB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
};

const ago = (date: Date): string => {
  const seconds = Math.max(0, Math.round((Date.now() - date.getTime()) / 1000));
  if (seconds < 60) return 'just now';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 48) return `${hours} h ago`;
  return `${Math.round(hours / 24)} d ago`;
};

export default Environments;
