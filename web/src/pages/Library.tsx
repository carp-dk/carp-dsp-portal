import AddIcon from '@mui/icons-material/Add';
import SearchIcon from '@mui/icons-material/Search';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  InputAdornment,
  Paper,
  Stack,
  Tab,
  Tabs,
  TextField,
  Typography,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { listLibrarySteps } from '../api/client';
import CertificationChip from '../components/CertificationChip';
import DemoWorkflowList from '../components/DemoWorkflowList';
import type { LibraryEntry } from '../api/types';

/**
 * The step library.
 *
 * Entries are the real step.yaml files from carp.dsp.steps, grouped by tier -
 * the tier is what the id's first segment encodes, so `core.reshape.*` and
 * `sensing.heartrate.*` sort into different sections on their own.
 */
const Library = () => {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [tab, setTab] = useState(0);

  const { data, isPending, error } = useQuery({
    queryKey: ['library'],
    queryFn: () => listLibrarySteps(),
  });

  const grouped = useMemo(() => {
    const needle = query.trim().toLowerCase();
    const matches = (entry: LibraryEntry) => {
      if (!needle) return true;
      const m = entry.definition.metadata;
      return (
        entry.stepId.toLowerCase().includes(needle) ||
        m.name.toLowerCase().includes(needle) ||
        (m.description ?? '').toLowerCase().includes(needle) ||
        m.tags.some((t) => t.toLowerCase().includes(needle))
      );
    };

    return (data ?? []).filter(matches).reduce<Record<string, LibraryEntry[]>>(
      (acc, entry) => {
        const tier =
          entry.definition.library?.tier ?? entry.stepId.split('.')[0] ?? 'other';
        (acc[tier] ??= []).push(entry);
        return acc;
      },
      {},
    );
  }, [data, query]);

  const tiers = Object.keys(grouped).sort();
  const total = Object.values(grouped).reduce((n, g) => n + g.length, 0);

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1_web" component="h1">
          Library
        </Typography>
        <Typography variant="h3_web" color="text.secondary">
          Reusable steps, and example workflows to start from
        </Typography>
      </Box>

      <Tabs value={tab} onChange={(_, v) => setTab(v)}>
        <Tab label={`Steps (${data?.length ?? 0})`} />
        <Tab label="Demo workflows" />
      </Tabs>

      {tab === 1 && <DemoWorkflowList />}

      {tab === 0 && (
      <>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
      <TextField
        placeholder="Search by id, name, description or tag"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        sx={{ flex: 1, maxWidth: 480 }}
        slotProps={{
          input: {
            startAdornment: (
              <InputAdornment position="start">
                <SearchIcon />
              </InputAdornment>
            ),
          },
        }}
      />
        {/* Authoring moved into the composer - you want a new step while
            building a workflow, not from a browsing page. */}
        <Button
          variant="outlined"
          startIcon={<AddIcon />}
          onClick={() => navigate('/compose')}
        >
          New step
        </Button>
      </Stack>

      {isPending && <CircularProgress />}
      {error && <Alert severity="error">{(error as Error).message}</Alert>}

      {data && total === 0 && (
        <Alert severity="info">Nothing matches “{query}”.</Alert>
      )}

      {tiers.map((tier) => (
        <Box key={tier}>
          <Typography variant="h2_web" component="h2" gutterBottom>
            {tier}{' '}
            <Typography component="span" variant="h6" color="text.secondary">
              {grouped[tier].length} steps
            </Typography>
          </Typography>
          <Stack spacing={1.5}>
            {grouped[tier].map((entry) => (
              <StepRow key={entry.stepId} entry={entry} />
            ))}
          </Stack>
        </Box>
      ))}
      </>
      )}
    </Stack>
  );
};

// The source mode is still on /health for debugging; it is just not shown.

const StepRow = ({ entry }: { entry: LibraryEntry }) => {
  const navigate = useNavigate();
  const meta = entry.definition.metadata;
  const step = entry.definition.steps[0];

  return (
    <Paper
      variant="outlined"
      sx={{ p: 2, cursor: 'pointer' }}
      onClick={() => navigate(`/library/${entry.stepId}`)}
    >
      <Stack
        direction="row"
        spacing={2}
        sx={{ alignItems: 'flex-start', justifyContent: 'space-between' }}
      >
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="h4_web">{meta.name}</Typography>
          <Typography
            variant="h6"
            color="text.secondary"
            sx={{ fontFamily: 'monospace' }}
          >
            {entry.stepId}
          </Typography>
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
            {meta.description}
          </Typography>

          <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap', mt: 1 }}>
            {step && (
              <Chip
                label={`${step.inputs.length} in / ${step.outputs.length} out`}
                size="small"
                variant="outlined"
              />
            )}
            {entry.definition.library?.implementations.map((impl) => (
              <Chip
                key={impl.language}
                label={impl.language}
                size="small"
                color="primary"
              />
            ))}
            {meta.tags.slice(0, 4).map((tag) => (
              <Chip key={tag} label={tag} size="small" variant="outlined" />
            ))}
          </Stack>
        </Box>

        <CertificationChip certification={entry.certification} />
      </Stack>
    </Paper>
  );
};

export default Library;
