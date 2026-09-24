import { Alert, Box, Chip, Paper, Stack, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';

type Health = { status: string; service: string; version: string };

/**
 * Pass-1 placeholder. Proves three things at once: the CARP theme is applied,
 * client-side routing works, and the Ktor server is reachable.
 * Replaced by the real W5-v0 pages in pass 2.
 */
const Placeholder = () => {
  const { runId } = useParams();
  const [health, setHealth] = useState<Health | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch('/health')
      .then((r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return r.json();
      })
      .then(setHealth)
      .catch((e: Error) => setError(e.message));
  }, []);

  return (
    <Stack spacing={3} sx={{ maxWidth: 720 }}>
      <Box>
        <Typography variant="h1_web" component="h1">
          DSP Portal
        </Typography>
        <Typography variant="h3_web" color="text.secondary">
          Mock UI for the analytics system
        </Typography>
      </Box>

      {error && <Alert severity="error">Server unreachable: {error}</Alert>}

      {health && (
        <Alert severity="success">
          Server {health.status} - {health.service} @ {health.version}
        </Alert>
      )}

      {runId && (
        <Paper sx={{ p: 2 }} variant="outlined">
          <Typography variant="h4_web">
            Deep link resolved, run id: <Chip label={runId} size="small" />
          </Typography>
          <Typography variant="h5_web" color="text.secondary">
            Reaching this after a hard refresh means the SPA fallback works.
          </Typography>
        </Paper>
      )}

      <Paper sx={{ p: 2 }} variant="outlined">
        <Typography variant="h4_web" gutterBottom>
          Next up - W5-v0 pages
        </Typography>
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
          {['Upload', 'Validation errors', 'Run view', 'Results', 'Errors'].map(
            (p) => (
              <Chip key={p} label={p} variant="outlined" size="small" />
            ),
          )}
        </Stack>
      </Paper>
    </Stack>
  );
};

export default Placeholder;
