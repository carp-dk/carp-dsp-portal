import DeleteIcon from '@mui/icons-material/Delete';
import HistoryIcon from '@mui/icons-material/History';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { deleteWorkflow, listWorkflows } from '../api/client';
import StatusPill from '../components/StatusPill';
import type { WorkflowSummary } from '../api/types';

/** The study's workflow repository (W5-v1). */
const Workflows = () => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [pendingDelete, setPendingDelete] = useState<WorkflowSummary | null>(null);

  const { data, isPending, error } = useQuery({
    queryKey: ['workflows'],
    queryFn: () => listWorkflows(),
  });

  const remove = useMutation({
    mutationFn: (workflowId: string) => deleteWorkflow(workflowId),
    onSuccess: () => {
      setPendingDelete(null);
      queryClient.invalidateQueries({ queryKey: ['workflows'] });
    },
  });

  return (
    <Stack spacing={3}>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'flex-start',
          justifyContent: 'space-between',
          gap: 2,
        }}
      >
        <Box>
          <Typography variant="h1_web" component="h1">
            Workflows
          </Typography>
          <Typography variant="h3_web" color="text.secondary">
            Analysis pipelines stored for this study
          </Typography>
        </Box>
        <Button
          variant="contained"
          startIcon={<UploadFileIcon />}
          onClick={() => navigate('/upload')}
        >
          Upload workflow
        </Button>
      </Box>

      {isPending && <CircularProgress />}
      {error && <Alert severity="error">{(error as Error).message}</Alert>}
      {remove.error && (
        <Alert severity="error">{(remove.error as Error).message}</Alert>
      )}

      {/* Confirmed rather than immediate: a run history points at the workflow,
          and there is no undo. */}
      <Dialog open={Boolean(pendingDelete)} onClose={() => setPendingDelete(null)}>
        <DialogTitle>Remove “{pendingDelete?.name}”?</DialogTitle>
        <DialogContent>
          <Typography variant="h5_web">
            It leaves this study's repository. Runs already recorded against it
            stay, and it can be added again from the library if it came from
            there.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPendingDelete(null)}>Cancel</Button>
          <Button
            color="error"
            variant="contained"
            disabled={remove.isPending}
            onClick={() => pendingDelete && remove.mutate(pendingDelete.workflowId)}
          >
            Remove
          </Button>
        </DialogActions>
      </Dialog>

      {data && (
        <Paper variant="outlined">
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Name</TableCell>
                <TableCell>Version</TableCell>
                <TableCell align="right">Steps</TableCell>
                <TableCell>Tags</TableCell>
                <TableCell>Last run</TableCell>
                <TableCell align="right" />
              </TableRow>
            </TableHead>
            <TableBody>
              {data.map((wf) => (
                <TableRow
                  key={wf.workflowId}
                  hover
                  sx={{ cursor: 'pointer' }}
                  onClick={() => navigate(`/workflows/${wf.workflowId}`)}
                >
                  <TableCell>
                    <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                      <Typography variant="h5">{wf.name}</Typography>
                      {wf.draft && (
                        <Chip label="draft" size="small" color="warning" />
                      )}
                    </Stack>
                    <Typography variant="h5_web" color="text.secondary">
                      {wf.description}
                    </Typography>
                  </TableCell>
                  <TableCell>{wf.version}</TableCell>
                  <TableCell align="right">{wf.stepCount}</TableCell>
                  <TableCell>
                    <Stack
                      direction="row"
                      spacing={0.5}
                      useFlexGap
                      sx={{ flexWrap: 'wrap', maxWidth: 220 }}
                    >
                      {wf.tags.slice(0, 4).map((tag) => (
                        <Chip key={tag} label={tag} size="small" variant="outlined" />
                      ))}
                    </Stack>
                  </TableCell>
                  <TableCell>
                    {wf.lastRunStatus ? (
                      <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>
                        <StatusPill status={wf.lastRunStatus} />
                        <Typography variant="h6" color="text.secondary">
                          {wf.lastRunAt && new Date(wf.lastRunAt).toLocaleString()}
                        </Typography>
                      </Stack>
                    ) : (
                      <Typography variant="h5_web" color="text.disabled">
                        Never run
                      </Typography>
                    )}
                  </TableCell>

                  <TableCell align="right">
                    <Stack direction="row" spacing={0.5} sx={{ justifyContent: 'flex-end' }}>
                      <Button
                        size="small"
                        startIcon={<HistoryIcon />}
                        onClick={(e) => {
                          e.stopPropagation();
                          navigate(`/workflows/${wf.workflowId}?tab=runs`);
                        }}
                      >
                        Runs
                      </Button>
                      <IconButton
                        size="small"
                        aria-label={`Delete ${wf.name}`}
                        onClick={(e) => {
                          e.stopPropagation();
                          setPendingDelete(wf);
                        }}
                      >
                        <DeleteIcon fontSize="small" />
                      </IconButton>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
      )}
    </Stack>
  );
};

export default Workflows;
