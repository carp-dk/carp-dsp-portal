import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/Delete';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  Tab,
  Tabs,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { useMemo, useRef, useState } from 'react';
import { RpcError, createLibraryStep } from '../api/client';
import type { LibraryEntry } from '../api/types';

/**
 * Author a library step without leaving the composer.
 *
 * This lives here rather than on its own page because you need a step that
 * does not exist yet precisely while composing - sending someone to a separate
 * screen loses the draft they were building.
 *
 * The vendored library is gated: a real step arrives by pull request and
 * conformance review. Anything made here is unreviewed and lives only until
 * the server restarts, and the dialog says so.
 */
const FORMATS = ['csv', 'json', 'png', 'parquet', 'txt'];
const TIERS = ['core', 'sensing', 'analysis'];

interface Port {
  id: string;
  fileFormat: string;
  notes: string;
}

const NewStepDialog = ({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (entry: LibraryEntry) => void;
}) => {
  const fileRef = useRef<HTMLInputElement>(null);
  const [tab, setTab] = useState(0);

  const [tier, setTier] = useState('core');
  const [subject, setSubject] = useState('reshape');
  const [slug, setSlug] = useState('my-step');
  const [name, setName] = useState('My step');
  const [description, setDescription] = useState('');
  const [scriptPath, setScriptPath] = useState('impl/python/my_step.py');
  const [inputs, setInputs] = useState<Port[]>([
    { id: 'table', fileFormat: 'csv', notes: '' },
  ]);
  const [outputs, setOutputs] = useState<Port[]>([
    { id: 'result', fileFormat: 'csv', notes: '' },
  ]);
  const [uploaded, setUploaded] = useState<string | null>(null);

  const stepId = `${tier}.${subject}.${slug}`;
  const generated = useMemo(
    () =>
      buildStepYaml({
        stepId,
        slug,
        name,
        description,
        tier,
        subject,
        scriptPath,
        inputs,
        outputs,
      }),
    [stepId, slug, name, description, tier, subject, scriptPath, inputs, outputs],
  );

  const yaml = tab === 0 ? generated : uploaded;

  const save = useMutation({
    mutationFn: () => createLibraryStep(yaml!),
    onSuccess: onCreated,
  });

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>New library step</DialogTitle>

      <DialogContent dividers>
        <Alert severity="info" sx={{ mb: 2 }}>
          The vendored library is gated - a real step arrives by pull request
          and conformance review. This one is unreviewed and is lost when the
          server restarts.
        </Alert>

        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
          <Tab label="Fill in a form" />
          <Tab label="Upload step.yaml" />
        </Tabs>

        {tab === 0 && (
          <Stack spacing={2}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                select
                label="Tier"
                value={tier}
                onChange={(e) => setTier(e.target.value)}
                size="small"
                sx={{ width: 140 }}
              >
                {TIERS.map((t) => (
                  <MenuItem key={t} value={t}>
                    {t}
                  </MenuItem>
                ))}
              </TextField>
              <TextField
                label="Subject"
                value={subject}
                onChange={(e) => setSubject(e.target.value)}
                size="small"
                helperText="e.g. reshape, heartrate"
              />
              <TextField
                label="Slug"
                value={slug}
                onChange={(e) => setSlug(e.target.value)}
                size="small"
                sx={{ flex: 1 }}
              />
            </Stack>

            <Typography variant="h6" sx={{ fontFamily: 'monospace' }}>
              {stepId}
            </Typography>

            <TextField
              label="Name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              size="small"
              fullWidth
            />
            <TextField
              label="Description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              size="small"
              multiline
              minRows={2}
              fullWidth
            />
            <TextField
              label="Script path"
              value={scriptPath}
              onChange={(e) => setScriptPath(e.target.value)}
              size="small"
              fullWidth
            />

            <PortEditor title="Inputs" ports={inputs} onChange={setInputs} />
            <PortEditor title="Outputs" ports={outputs} onChange={setOutputs} />

            <Paper variant="outlined" sx={{ p: 2, overflowX: 'auto', maxHeight: 260 }}>
              <Box component="pre" sx={{ m: 0, fontSize: '0.78125rem', lineHeight: 1.5 }}>
                {generated}
              </Box>
            </Paper>
          </Stack>
        )}

        {tab === 1 && (
          <Stack spacing={2} sx={{ alignItems: 'flex-start' }}>
            <Button
              variant="outlined"
              startIcon={<UploadFileIcon />}
              onClick={() => fileRef.current?.click()}
            >
              {uploaded ? 'Choose a different file' : 'Choose a step.yaml'}
            </Button>
            <input
              ref={fileRef}
              type="file"
              accept=".yml,.yaml"
              hidden
              onChange={async (e) => {
                const file = e.target.files?.[0];
                if (file) {
                  setUploaded(await file.text());
                  save.reset();
                }
              }}
            />
            {uploaded && (
              <Paper variant="outlined" sx={{ p: 2, width: '100%', maxHeight: 320, overflow: 'auto' }}>
                <Box component="pre" sx={{ m: 0, fontSize: '0.78125rem' }}>
                  {uploaded}
                </Box>
              </Paper>
            )}
          </Stack>
        )}

        {save.error && (
          <Alert severity="error" sx={{ mt: 2 }}>
            <AlertTitle>
              {save.error instanceof RpcError &&
              save.error.kind === 'StepValidationError'
                ? 'This step file could not be read'
                : 'Could not add the step'}
            </AlertTitle>
            {(save.error as Error).message}
          </Alert>
        )}
      </DialogContent>

      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={!yaml || save.isPending}
          onClick={() => save.mutate()}
        >
          Add and use it
        </Button>
      </DialogActions>
    </Dialog>
  );
};

const PortEditor = ({
  title,
  ports,
  onChange,
}: {
  title: string;
  ports: Port[];
  onChange: (ports: Port[]) => void;
}) => (
  <Paper variant="outlined" sx={{ p: 2 }}>
    <Stack
      direction="row"
      spacing={1}
      sx={{ alignItems: 'center', justifyContent: 'space-between' }}
    >
      <Typography variant="h4_web">{title}</Typography>
      <Button
        size="small"
        startIcon={<AddIcon />}
        onClick={() => onChange([...ports, { id: '', fileFormat: 'csv', notes: '' }])}
      >
        Add
      </Button>
    </Stack>

    <Divider sx={{ my: 1.5 }} />

    <Stack spacing={1.5}>
      {ports.length === 0 && (
        <Typography variant="h5_web" color="text.disabled">
          None
        </Typography>
      )}
      {ports.map((port, i) => (
        <Stack key={i} direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          <TextField
            label="Port id"
            value={port.id}
            onChange={(e) =>
              onChange(ports.map((p, j) => (i === j ? { ...p, id: e.target.value } : p)))
            }
            size="small"
            sx={{ width: 170 }}
          />
          <TextField
            select
            label="Format"
            value={port.fileFormat}
            onChange={(e) =>
              onChange(
                ports.map((p, j) => (i === j ? { ...p, fileFormat: e.target.value } : p)),
              )
            }
            size="small"
            sx={{ width: 120 }}
          >
            {FORMATS.map((f) => (
              <MenuItem key={f} value={f}>
                {f}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label="Notes"
            value={port.notes}
            onChange={(e) =>
              onChange(ports.map((p, j) => (i === j ? { ...p, notes: e.target.value } : p)))
            }
            size="small"
            sx={{ flex: 1 }}
          />
          <IconButton
            size="small"
            onClick={() => onChange(ports.filter((_, j) => j !== i))}
            aria-label={`Remove ${title.toLowerCase()} port`}
          >
            <DeleteIcon fontSize="small" />
          </IconButton>
        </Stack>
      ))}
    </Stack>
  </Paper>
);

const q = (s: string) => `"${s.replace(/"/g, '\\"')}"`;

/** Writes the same shape as the vendored step.yaml files. */
const buildStepYaml = (o: {
  stepId: string;
  slug: string;
  name: string;
  description: string;
  tier: string;
  subject: string;
  scriptPath: string;
  inputs: Port[];
  outputs: Port[];
}): string => {
  const ports = (list: Port[], key: string) => {
    if (list.length === 0) return [];
    const lines = [`    ${key}:`];
    list.forEach((p) => {
      lines.push(`      - id: ${q(p.id)}`);
      lines.push('        descriptor:');
      lines.push(`          fileFormat: ${q(p.fileFormat)}`);
      lines.push('          encoding: "utf-8"');
      if (p.notes) lines.push(`          notes: ${q(p.notes)}`);
    });
    return lines;
  };

  return [
    'schemaVersion: "1.0"',
    '',
    'metadata:',
    `  id: ${q(o.stepId)}`,
    `  name: ${q(o.name)}`,
    ...(o.description ? [`  description: ${q(o.description)}`] : []),
    '  version: "1.0"',
    '  tags:',
    `    - ${q(o.tier)}`,
    `    - ${q(o.subject)}`,
    '',
    'environments:',
    '  env-python-base:',
    '    name: "Python (standard library only)"',
    '    kind: "pixi"',
    '    spec:',
    '      pythonVersion:',
    '        - "3.11"',
    '      dependencies: []',
    '',
    'steps:',
    `  - id: ${q(o.slug)}`,
    '    metadata:',
    `      name: ${q(o.name)}`,
    ...(o.description ? [`      description: ${q(o.description)}`] : []),
    '      version: "1.0"',
    '    environmentId: "env-python-base"',
    '    dependsOn: []',
    '    task:',
    '      type: "python"',
    `      id: ${q(`task-${o.stepId.replace(/\./g, '-')}`)}`,
    `      name: ${q(o.slug)}`,
    '      entryPoint:',
    '        type: "script"',
    `        scriptPath: ${q(o.scriptPath)}`,
    '      args:',
    '        - "--input"',
    '        - "input.0"',
    '        - "--output"',
    '        - "output.0"',
    ...ports(o.inputs, 'inputs'),
    ...ports(o.outputs, 'outputs'),
    '',
    'library:',
    `  tier: ${q(o.tier)}`,
    `  subject: ${q(o.subject)}`,
    '  environment:',
    '    default: "env-python-base"',
    '    requires:',
    '      kind: ["pixi", "conda", "system"]',
    '      interpreter:',
    '        name: "python"',
    '        version: ">=3.11,<4"',
    '      packages: []',
    '  implementations:',
    '    - language: "python"',
    `      path: ${q(o.scriptPath)}`,
    '  method:',
    `    name: ${q(o.name)}`,
    '    citation: null',
    '',
  ].join('\n');
};

export default NewStepDialog;
