import CloseIcon from '@mui/icons-material/Close';
import InsertChartIcon from '@mui/icons-material/InsertChart';
import RefreshIcon from '@mui/icons-material/Refresh';
import TuneIcon from '@mui/icons-material/Tune';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  FormGroup,
  IconButton,
  Link,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { listResultSets, listWorkflows } from '../api/client';
import type { ArtifactEntry, ExecutorState, ResultSet } from '../api/types';

/**
 * One figure on the dashboard: an output of the latest successful run of any of
 * `workflows`. Listing a replay copy beside the real workflow lets the page be
 * tried before the conference; the newer run wins.
 */
interface Panel {
  title: string;
  workflows: string[];
  outputId: string;
  /** Spans the whole row, for wide figures. */
  wide?: boolean;
  /** Spans two rows, for tall figures. */
  tall?: boolean;
}

const LIVE = ['ubilife-compare-live', 'ubilife-live-replay'];
const ACCUMULATION = ['ubilife-accumulation', 'ubilife-accumulation-replay'];
const ANALYSIS = ['ubilife'];

const PANELS: Panel[] = [
  { title: 'Data so far', workflows: ACCUMULATION, outputId: 'accumulation-figure', wide: true },
  { title: '2026 against 2025', workflows: LIVE, outputId: 'comparison-figure', tall: true },
  { title: 'Records per day', workflows: ANALYSIS, outputId: 'daily-volume' },
  { title: 'Activity', workflows: ANALYSIS, outputId: 'activity-types' },
  { title: 'Who is taking part', workflows: ANALYSIS, outputId: 'demographics' },
  { title: 'Stress and sleep', workflows: ANALYSIS, outputId: 'daily-surveys' },
  { title: 'Session ratings', workflows: ANALYSIS, outputId: 'session-ratings' },
  { title: 'Weather', workflows: ANALYSIS, outputId: 'weather' },
];

/** Figure heights at medium size: small enough that a screen holds several. */
const WIDE_HEIGHT = 340;
const HEIGHT = 260;
const MIN_WIDTH = 420;

type Size = 'small' | 'medium' | 'large';
const SCALE: Record<Size, number> = { small: 0.75, medium: 1, large: 1.4 };

/** What the settings button changes. Kept in this browser only. */
interface Settings {
  /** 0 is off. */
  refreshSeconds: number;
  size: Size;
  /** Output ids of the panels not shown. */
  hidden: string[];
}

const DEFAULTS: Settings = { refreshSeconds: 60, size: 'medium', hidden: [] };
const REFRESH_CHOICES = [
  { seconds: 0, label: 'Off' },
  { seconds: 30, label: 'Every 30 seconds' },
  { seconds: 60, label: 'Every minute' },
  { seconds: 300, label: 'Every 5 minutes' },
];
const STORAGE_KEY = 'ubilife-dashboard';

const loadSettings = (): Settings => {
  try {
    return { ...DEFAULTS, ...JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}') };
  } catch {
    return DEFAULTS;
  }
};

const saveSettings = (settings: Settings) => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
  } catch {
    // Storage blocked: the settings last until the page is closed.
  }
};

const finishedAt = (run: ExecutorState) => new Date(run.completedAt ?? run.startedAt).getTime();

/**
 * A real run keys outputs by the plan's UUID and gives the YAML id as their
 * name; a simulated run keys them by the YAML id.
 */
const isOutput = (entry: ArtifactEntry, id: string) => entry.outputName === id || entry.outputId === id;

/** The newest successful run of any of `workflows` that has the output, with that output. */
const latest = (sets: ResultSet[], panel: Panel) =>
  sets
    .filter((s) => s.run.status === 'SUCCEEDED' && panel.workflows.includes(s.run.workflowId))
    .map((s) => ({ run: s.run, entry: s.finals.find((f) => isOutput(f, panel.outputId)) }))
    .filter((found): found is { run: ExecutorState; entry: ArtifactEntry } => Boolean(found.entry?.downloadUrl))
    .sort((a, b) => finishedAt(b.run) - finishedAt(a.run))[0];

const time = (iso: string) =>
  new Date(iso).toLocaleString(undefined, { weekday: 'short', hour: '2-digit', minute: '2-digit' });

/**
 * The UbiLife 2026 dashboard for the booth: a few figures from the latest runs
 * of the UbiLife workflows, sized to fit a screen. A figure opens full size.
 */
const UbiLifeDashboard = () => {
  const [open, setOpen] = useState<{ title: string; src: string } | null>(null);
  const [settings, setSettings] = useState<Settings>(loadSettings);
  const [editing, setEditing] = useState(false);

  const change = (next: Settings) => {
    setSettings(next);
    saveSettings(next);
  };

  const sets = useQuery({
    queryKey: ['result-sets'],
    queryFn: () => listResultSets(),
    refetchInterval: settings.refreshSeconds > 0 ? settings.refreshSeconds * 1000 : false,
  });
  const workflows = useQuery({ queryKey: ['workflows'], queryFn: () => listWorkflows() });
  const names = Object.fromEntries((workflows.data ?? []).map((w) => [w.workflowId, w.name]));

  if (sets.isPending) return <CircularProgress />;
  if (sets.error) return <Alert severity="error">{(sets.error as Error).message}</Alert>;

  const scale = SCALE[settings.size];
  const shown = PANELS.filter((panel) => !settings.hidden.includes(panel.outputId));

  return (
    <Stack spacing={3}>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography variant="h1_web" component="h1">
          UbiLife 2026
        </Typography>
        <Stack direction="row" spacing={1}>
          <Tooltip title="Refresh now">
            <IconButton aria-label="Refresh now" onClick={() => sets.refetch()} disabled={sets.isFetching}>
              <RefreshIcon />
            </IconButton>
          </Tooltip>
          <Tooltip title="Settings">
            <IconButton aria-label="Settings" onClick={() => setEditing(true)}>
              <TuneIcon />
            </IconButton>
          </Tooltip>
        </Stack>
      </Stack>

      <Box
        sx={{
          display: 'grid',
          gap: 2,
          gridTemplateColumns: `repeat(auto-fill, minmax(${MIN_WIDTH * scale}px, 1fr))`,
          gridAutoFlow: 'dense',
        }}
      >
        {shown.map((panel) => {
          const found = latest(sets.data ?? [], panel);
          // A tall figure fills the two rows it spans; the others have a fixed height.
          const size = panel.tall
            ? { flex: 1, height: 0, minHeight: 2 * HEIGHT * scale }
            : { height: (panel.wide ? WIDE_HEIGHT : HEIGHT) * scale };
          return (
            <Paper
              key={panel.outputId}
              variant="outlined"
              sx={{
                p: 1.5,
                display: 'flex',
                flexDirection: 'column',
                gridColumn: panel.wide ? '1 / -1' : undefined,
                gridRow: panel.tall ? 'span 2' : undefined,
              }}
            >
              <Stack
                direction="row"
                sx={{ alignItems: 'baseline', justifyContent: 'space-between', mb: 1, gap: 1 }}
              >
                <Typography variant="h4_web">{panel.title}</Typography>
                {found && (
                  <Typography variant="h6" color="text.secondary" noWrap>
                    {names[found.run.workflowId] ?? found.run.workflowId} ·{' '}
                    <Link component={RouterLink} to={`/runs/${found.run.executionId}`} underline="hover">
                      {time(found.run.completedAt ?? found.run.startedAt)}
                    </Link>
                  </Typography>
                )}
              </Stack>

              {found ? (
                <Box
                  component="img"
                  src={found.entry.downloadUrl ?? undefined}
                  alt={panel.title}
                  onClick={() => setOpen({ title: panel.title, src: found.entry.downloadUrl ?? '' })}
                  sx={{
                    display: 'block',
                    width: '100%',
                    ...size,
                    objectFit: 'contain',
                    cursor: 'zoom-in',
                    backgroundColor: '#fff',
                    borderRadius: 1,
                  }}
                />
              ) : (
                <Box
                  sx={{
                    ...size,
                    display: 'grid',
                    placeItems: 'center',
                    borderRadius: 1,
                    color: 'text.disabled',
                    backgroundColor: (t) => t.palette.grey[100],
                  }}
                >
                  <Stack sx={{ alignItems: 'center' }}>
                    <InsertChartIcon sx={{ fontSize: 40 }} />
                    <Typography variant="h6">
                      No successful run of {panel.workflows.join(' or ')} yet
                    </Typography>
                  </Stack>
                </Box>
              )}
            </Paper>
          );
        })}
      </Box>

      <SettingsDialog
        open={editing}
        settings={settings}
        onChange={change}
        onClose={() => setEditing(false)}
      />

      <Dialog open={open !== null} onClose={() => setOpen(null)} maxWidth={false}>
        {open && (
          <Box sx={{ position: 'relative', p: 1 }}>
            <IconButton
              aria-label="Close"
              onClick={() => setOpen(null)}
              sx={{ position: 'absolute', top: 8, right: 8, backgroundColor: 'background.paper' }}
            >
              <CloseIcon />
            </IconButton>
            <Box
              component="img"
              src={open.src}
              alt={open.title}
              sx={{ display: 'block', maxWidth: '92vw', maxHeight: '88vh' }}
            />
          </Box>
        )}
      </Dialog>
    </Stack>
  );
};

/** Refresh, figure size and which figures to show. Changes apply at once. */
const SettingsDialog = ({
  open,
  settings,
  onChange,
  onClose,
}: {
  open: boolean;
  settings: Settings;
  onChange: (next: Settings) => void;
  onClose: () => void;
}) => {
  const toggle = (outputId: string) =>
    onChange({
      ...settings,
      hidden: settings.hidden.includes(outputId)
        ? settings.hidden.filter((id) => id !== outputId)
        : [...settings.hidden, outputId],
    });

  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Dashboard settings</DialogTitle>
      <DialogContent>
        <Stack spacing={2.5} useFlexGap sx={{ pt: 1 }}>
          <TextField
            select
            label="Refresh"
            size="small"
            value={settings.refreshSeconds}
            onChange={(e) => onChange({ ...settings, refreshSeconds: Number(e.target.value) })}
          >
            {REFRESH_CHOICES.map((choice) => (
              <MenuItem key={choice.seconds} value={choice.seconds}>
                {choice.label}
              </MenuItem>
            ))}
          </TextField>

          <TextField
            select
            label="Figure size"
            size="small"
            value={settings.size}
            onChange={(e) => onChange({ ...settings, size: e.target.value as Size })}
          >
            <MenuItem value="small">Small</MenuItem>
            <MenuItem value="medium">Medium</MenuItem>
            <MenuItem value="large">Large</MenuItem>
          </TextField>

          <Box>
            <Typography variant="h6" color="text.secondary">
              Figures
            </Typography>
            <FormGroup>
              {PANELS.map((panel) => (
                <FormControlLabel
                  key={panel.outputId}
                  label={panel.title}
                  control={
                    <Checkbox
                      size="small"
                      checked={!settings.hidden.includes(panel.outputId)}
                      onChange={() => toggle(panel.outputId)}
                    />
                  }
                />
              ))}
            </FormGroup>
          </Box>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={() => onChange(DEFAULTS)}>Reset</Button>
        <Button variant="contained" onClick={onClose}>
          Done
        </Button>
      </DialogActions>
    </Dialog>
  );
};

export default UbiLifeDashboard;
