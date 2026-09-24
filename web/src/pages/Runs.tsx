import {
  Alert,
  Box,
  CircularProgress,
  Stack,
  Tab,
  Tabs,
  Typography,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { findExecutions, listSchedules, listWorkflows } from '../api/client';
import RunsTable from '../components/RunsTable';
import SchedulesTable from '../components/SchedulesTable';

/** Every run and schedule in the study. */
const Runs = () => {
  const [tab, setTab] = useState(0);
  const runs = useQuery({
    queryKey: ['runs'],
    queryFn: () => findExecutions(),
    // Keep the list live while anything is still going.
    refetchInterval: (query) =>
      query.state.data?.some((r) => r.status === 'RUNNING') ? 2000 : false,
  });

  const workflows = useQuery({
    queryKey: ['workflows'],
    queryFn: () => listWorkflows(),
  });

  const schedules = useQuery({
    queryKey: ['schedules'],
    queryFn: () => listSchedules(),
  });

  const names = Object.fromEntries(
    (workflows.data ?? []).map((w) => [w.workflowId, w.name]),
  );

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1_web" component="h1">
          Runs
        </Typography>
        <Typography variant="h3_web" color="text.secondary">
          Every execution in this study
        </Typography>
      </Box>

      <Tabs value={tab} onChange={(_, v) => setTab(v)}>
        <Tab label={`Runs (${runs.data?.length ?? 0})`} />
        <Tab label={`Schedules (${schedules.data?.length ?? 0})`} />
      </Tabs>

      {tab === 0 && (
        <>
          {runs.isPending && <CircularProgress />}
          {runs.error && (
            <Alert severity="error">{(runs.error as Error).message}</Alert>
          )}
          {runs.data && (
            <RunsTable
              runs={runs.data}
              workflowNames={names}
              empty="Nothing has been run yet. Open a workflow and press Execute now."
            />
          )}
        </>
      )}

      {tab === 1 && (
        <SchedulesTable
          schedules={schedules.data ?? []}
          workflowNames={names}
          empty="No schedules yet. Open a workflow and press Schedule."
        />
      )}
    </Stack>
  );
};

export default Runs;
