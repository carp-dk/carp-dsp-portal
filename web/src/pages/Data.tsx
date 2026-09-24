import AddIcon from '@mui/icons-material/Add';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import LinkIcon from '@mui/icons-material/Link';
import SensorsIcon from '@mui/icons-material/Sensors';
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
  Divider,
  Paper,
  Stack,
  Tab,
  Tabs,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import {
  activateProtocol,
  addDataFile,
  addExternalDataset,
  listDataSources,
  listProtocols,
  uploadProtocol,
} from '../api/client';
import type { DataSource, ProtocolSummary } from '../api/types';

/**
 * What data the study has, and where it comes from.
 *
 * Three kinds, matching the three non-step-output source types a workflow input
 * can declare: `protocol`, `file` and `external`. A workflow import is
 * compatible with the study when every boundary input it declares is satisfied
 * by something on this page.
 *
 * The protocol is the interesting one - it is the authority on what the study
 * collects, and switching version is what makes the coupling check visibly
 * pass or fail.
 */
const Data = () => {
  const queryClient = useQueryClient();
  const [addOpen, setAddOpen] = useState(false);

  const sources = useQuery({
    queryKey: ['dataSources'],
    queryFn: () => listDataSources(),
  });

  const protocols = useQuery({
    queryKey: ['protocols'],
    queryFn: () => listProtocols(),
  });

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['dataSources'] });
    queryClient.invalidateQueries({ queryKey: ['protocols'] });
  };

  const byKind = (kind: string) =>
    (sources.data ?? []).filter((s) => s.kind === kind);

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
            Data
          </Typography>
          <Typography variant="h3_web" color="text.secondary">
            What this study collects, and what a workflow can read
          </Typography>
        </Box>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => setAddOpen(true)}
        >
          Add data
        </Button>
      </Box>

      <AddDataDialog
        open={addOpen}
        onClose={() => setAddOpen(false)}
        onAdded={refresh}
      />

      <ProtocolSection
        protocols={protocols.data ?? []}
        loading={protocols.isPending}
        onChanged={refresh}
      />

      {sources.isPending && <CircularProgress />}
      {sources.error && (
        <Alert severity="error">{(sources.error as Error).message}</Alert>
      )}

      <Section
        title="Collected by the protocol"
        caption="Available to any input declaring source type protocol"
        icon={<SensorsIcon fontSize="small" />}
        items={byKind('protocol')}
        empty="No protocol selected, so no collected data types."
      />

      <Section
        title="Data files"
        caption="Available to any input declaring source type file"
        icon={<UploadFileIcon fontSize="small" />}
        items={byKind('file')}
        empty="No data files yet."
      />

      <Section
        title="External datasets"
        caption="Open data - attributed, never protocol-checked"
        icon={<LinkIcon fontSize="small" />}
        items={byKind('external')}
        empty="No external datasets linked yet."
      />
    </Stack>
  );
};

const ProtocolSection = ({
  protocols,
  loading,
  onChanged,
}: {
  protocols: ProtocolSummary[];
  loading: boolean;
  onChanged: () => void;
}) => {
  const fileRef = useRef<HTMLInputElement>(null);

  const upload = useMutation({
    mutationFn: (json: string) => uploadProtocol(json),
    onSuccess: onChanged,
  });

  const activate = useMutation({
    mutationFn: ({ id, version }: { id: string; version: number }) =>
      activateProtocol(id, version),
    onSuccess: onChanged,
  });

  const active = protocols.find((p) => p.active);

  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Stack
        direction="row"
        spacing={2}
        sx={{ alignItems: 'flex-start', justifyContent: 'space-between' }}
      >
        <Box>
          <Typography variant="h4_web">Study protocol</Typography>
          <Typography variant="h6" color="text.secondary">
            Not connected to a live study - the protocol is an authored
            snapshot, which is what the coupling check validates against.
          </Typography>
        </Box>
        <Button
          size="small"
          startIcon={<UploadFileIcon />}
          onClick={() => fileRef.current?.click()}
        >
          Upload protocol
        </Button>
        <input
          ref={fileRef}
          type="file"
          accept=".json"
          hidden
          onChange={async (e) => {
            const file = e.target.files?.[0];
            if (file) upload.mutate(await file.text());
          }}
        />
      </Stack>

      {upload.error && (
        <Alert severity="error" sx={{ mt: 1 }}>
          {(upload.error as Error).message}
        </Alert>
      )}

      <Divider sx={{ my: 1.5 }} />

      {loading && <CircularProgress size={18} />}

      {protocols.length === 0 && !loading && (
        <Alert severity="info">
          No protocol loaded. Upload a StudyProtocolSnapshot to check workflow
          compatibility.
        </Alert>
      )}

      <Stack spacing={1}>
        {protocols.map((p) => (
          <Paper
            key={`${p.id}@${p.version}`}
            variant="outlined"
            sx={{
              p: 1.5,
              borderColor: (t) => (p.active ? t.palette.primary.main : undefined),
              backgroundColor: (t) =>
                p.active ? t.palette.drawer.active : 'transparent',
            }}
          >
            <Stack
              direction="row"
              spacing={2}
              sx={{ alignItems: 'flex-start', justifyContent: 'space-between' }}
            >
              <Box sx={{ minWidth: 0 }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                  <Typography variant="h5">{p.name}</Typography>
                  <Chip label={`v${p.version}`} size="small" />
                  {p.active && (
                    <Chip
                      icon={<CheckCircleIcon />}
                      label="active"
                      size="small"
                      color="primary"
                    />
                  )}
                </Stack>
                <Typography variant="h6" color="text.secondary">
                  {p.description}
                </Typography>
                <Stack
                  direction="row"
                  spacing={0.5}
                  useFlexGap
                  sx={{ flexWrap: 'wrap', mt: 1 }}
                >
                  {p.collectedDataTypes.map((t) => (
                    <Chip
                      key={t}
                      label={t}
                      size="small"
                      variant="outlined"
                      sx={{ fontFamily: 'monospace' }}
                    />
                  ))}
                </Stack>
              </Box>

              {!p.active && (
                <Button
                  size="small"
                  onClick={() => activate.mutate({ id: p.id, version: p.version })}
                  sx={{ flexShrink: 0 }}
                >
                  Use this
                </Button>
              )}
            </Stack>
          </Paper>
        ))}
      </Stack>

      {active && protocols.length > 1 && (
        <Alert severity="info" sx={{ mt: 1.5 }}>
          Switching version changes what workflows validate against. The
          steps-only snapshot drops heart rate, so a workflow needing it fails
          with PROTOCOL_DATA_NOT_COLLECTED.
        </Alert>
      )}
    </Paper>
  );
};

const Section = ({
  title,
  caption,
  icon,
  items,
  empty,
}: {
  title: string;
  caption: string;
  icon: React.ReactNode;
  items: DataSource[];
  empty: string;
}) => (
  <Box>
    <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
      {icon}
      <Typography variant="h2_web" component="h2">
        {title}
      </Typography>
      <Chip label={items.length} size="small" />
    </Stack>
    <Typography variant="h6" color="text.secondary" gutterBottom>
      {caption}
    </Typography>

    {items.length === 0 ? (
      <Typography variant="h5_web" color="text.disabled">
        {empty}
      </Typography>
    ) : (
      <Stack spacing={1}>
        {items.map((item) => (
          <Paper key={item.id} variant="outlined" sx={{ p: 1.5 }}>
            <Typography variant="h5">{item.name}</Typography>
            <Typography
              variant="h6"
              color="text.secondary"
              sx={{ fontFamily: 'monospace', wordBreak: 'break-all' }}
            >
              {item.id}
            </Typography>
            {item.description && (
              <Typography variant="h6" color="text.secondary">
                {item.description}
              </Typography>
            )}
            {item.citation && (
              <Typography variant="h6" sx={{ mt: 0.5, fontStyle: 'italic' }}>
                {item.citation}
              </Typography>
            )}
          </Paper>
        ))}
      </Stack>
    )}
  </Box>
);

const AddDataDialog = ({
  open,
  onClose,
  onAdded,
}: {
  open: boolean;
  onClose: () => void;
  onAdded: () => void;
}) => {
  const [tab, setTab] = useState(0);
  const fileRef = useRef<HTMLInputElement>(null);
  const [uri, setUri] = useState('https://zenodo.org/record/53894');
  const [citation, setCitation] = useState('');

  const done = () => {
    onAdded();
    onClose();
  };

  const addFile = useMutation({
    mutationFn: (file: File) =>
      // Only the path and size are registered. Storing the bytes is a W4
      // concern; what validation needs is whether the path is available.
      addDataFile(`${file.name}`, file.size, 'Uploaded to the study'),
    onSuccess: done,
  });

  const addExternal = useMutation({
    mutationFn: () => addExternalDataset(uri, citation || undefined),
    onSuccess: done,
  });

  const error = (addFile.error ?? addExternal.error) as Error | null;

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Add data</DialogTitle>
      <DialogContent>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
          <Tab label="Upload a file" />
          <Tab label="Link a Zenodo record" />
        </Tabs>

        {error && <Alert severity="error">{error.message}</Alert>}

        {tab === 0 && (
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Button
              variant="outlined"
              startIcon={<UploadFileIcon />}
              onClick={() => fileRef.current?.click()}
            >
              Choose a data file
            </Button>
            <input
              ref={fileRef}
              type="file"
              hidden
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) addFile.mutate(file);
              }}
            />
            <Typography variant="h6" color="text.secondary">
              A workflow reads this through an input with source type
              <code> file</code>, matched on the path.
            </Typography>
          </Stack>
        )}

        {tab === 1 && (
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Record URI"
              value={uri}
              onChange={(e) => setUri(e.target.value)}
              fullWidth
              size="small"
            />
            <TextField
              label="Citation"
              value={citation}
              onChange={(e) => setCitation(e.target.value)}
              fullWidth
              size="small"
              multiline
              minRows={2}
              helperText="External data with no citation validates with a warning, not an error."
            />
          </Stack>
        )}
      </DialogContent>

      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        {tab === 1 && (
          <Button
            variant="contained"
            onClick={() => addExternal.mutate()}
            disabled={!uri || addExternal.isPending}
          >
            Link dataset
          </Button>
        )}
      </DialogActions>
    </Dialog>
  );
};

export default Data;
