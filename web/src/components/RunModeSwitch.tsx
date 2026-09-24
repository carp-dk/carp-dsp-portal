import { useEffect, useState } from 'react';
import { Box, Switch, Tooltip, Typography } from '@mui/material';
import { getRunMode, setRunMode, type RunMode } from '../api/client';

/**
 * Real or simulated, switched live.
 *
 * A demo machine can lose its network, its pixi cache, or its patience mid-talk,
 * and the scripted path has to be one click away. The server holds the mode, so
 * a reload does not undo the choice and every page agrees on which one is in
 * force.
 *
 * Portal-only. It goes when the mock shell does.
 */
const RunModeSwitch = () => {
  const [mode, setMode] = useState<RunMode | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    getRunMode().then(setMode).catch(() => setMode(null));
  }, []);

  const toggle = async (real: boolean) => {
    setBusy(true);
    try {
      setMode(await setRunMode(real ? 'real' : 'simulated'));
    } catch {
      // Leave the switch where it was; the server is the source of truth.
    } finally {
      setBusy(false);
    }
  };

  // Unknown mode means the server did not answer, so offering a switch would
  // be a lie about what the next run will do.
  if (mode === null) return null;

  const real = mode === 'real';

  return (
    <Tooltip
      title={
        real
          ? 'Workflows run on this machine, through the DSP engine.'
          : 'Runs are replayed from recorded results. Nothing executes.'
      }
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, mr: 2 }}>
        <Switch
          size="small"
          checked={real}
          disabled={busy}
          onChange={(e) => toggle(e.target.checked)}
          slotProps={{ input: { 'aria-label': 'Run workflows for real' } }}
        />
        <Typography
          variant="body2"
          sx={{ color: real ? 'success.main' : 'text.secondary', minWidth: 72 }}
        >
          {real ? 'Real' : 'Simulated'}
        </Typography>
      </Box>
    </Tooltip>
  );
};

export default RunModeSwitch;
