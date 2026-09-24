import FolderZipIcon from '@mui/icons-material/FolderZip';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import SaveIcon from '@mui/icons-material/Save';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Paper,
  Stack,
  Typography,
} from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { useRef, useState, type DragEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  RpcError,
  executeBundle,
  executeWorkflowFromDefinition,
  saveBundle,
  validateArchive,
  validateBundle,
} from '../api/client';
import ValidationReportView from '../components/ValidationReportView';
import type { ValidationReport } from '../api/types';

/**
 * Upload a workflow bundle: a workflow .yml plus whatever scripts its steps
 * reference.
 *
 * Three ways in, all reaching the same validator - a single .yml, a folder, or
 * a .zip. Only paths and the workflow text are sent for the first two; a zip
 * goes up whole and is unpacked server-side.
 *
 * Validation is the server's, so the errors here are the real resolution
 * checks rather than scripted ones.
 */
const Upload = () => {
  const navigate = useNavigate();
  const fileRef = useRef<HTMLInputElement>(null);
  const folderRef = useRef<HTMLInputElement>(null);

  // Kept so a validated bundle can be run: the files are what the run needs,
  // and for a zip the YAML never reaches the browser at all.
  const [files, setFiles] = useState<File[]>([]);
  const [label, setLabel] = useState<string | null>(null);
  const [yaml, setYaml] = useState<string | null>(null);
  const [report, setReport] = useState<ValidationReport | null>(null);
  const [dragging, setDragging] = useState(false);

  const reset = () => {
    setReport(null);
    save.reset();
    runNow.reset();
  };

  const validateFiles = useMutation({
    mutationFn: async (files: File[]) => {
      const archive = files.find((f) => f.name.toLowerCase().endsWith('.zip'));
      if (archive) {
        setYaml(null);
        return validateArchive(archive);
      }

      const paths = files.map(relativePathOf);
      const workflow = pickWorkflow(files);
      const text = workflow ? await workflow.text() : null;
      setYaml(text);
      return validateBundle(paths, text);
    },
    onSuccess: setReport,
  });

  const save = useMutation({
    // Always through the bundle endpoint: a zip never gives the browser the
    // workflow text, and a draft has to be savable whether or not it validated.
    mutationFn: (options: { draft?: boolean; overwrite?: boolean }) =>
      saveBundle(files, relativePathOf, options),
    onSuccess: (detail) => navigate(`/workflows/${detail.summary.workflowId}`),
  });

  const runNow = useMutation({
    // A lone workflow file needs nothing staged, so it goes through the analytics
    // service like any other definition. Anything carrying files goes through the
    // bundle endpoint, which stages them into the run.
    mutationFn: () =>
      files.length === 1 && yaml
        ? executeWorkflowFromDefinition(yaml)
        : executeBundle(files, relativePathOf),
    onSuccess: (state) => navigate(`/runs/${state.executionId}`),
  });

  const accept = (files: File[]) => {
    if (files.length === 0) return;
    reset();
    setFiles(files);
    setLabel(
      files.length === 1
        ? files[0].name
        : `${files.length} files · ${topFolderOf(files) ?? 'bundle'}`,
    );
    validateFiles.mutate(files);
  };

  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setDragging(false);
    accept(Array.from(e.dataTransfer.files ?? []));
  };

  // A clashing id is a choice to offer, not a failure to report.
  const saveConflict =
    save.error instanceof RpcError && save.error.kind === 'WorkflowIdInUse'
      ? save.error.message
      : null;

  const error = (validateFiles.error ?? (saveConflict ? null : save.error) ?? runNow.error) as
    | RpcError
    | Error
    | null;
  const busy = validateFiles.isPending || save.isPending || runNow.isPending;

  // A draft can always be kept - something that failed validation is exactly what
  // you want to keep and fix. Running and saving to the study need it to be valid.
  const uploaded = files.length > 0 && report !== null;
  const canRun = uploaded && report?.valid === true;

  return (
    <Stack spacing={3} sx={{ maxWidth: 900 }}>
      <Box>
        <Typography variant="h1_web" component="h1">
          Upload a workflow
        </Typography>
        <Typography variant="h3_web" color="text.secondary">
          A workflow .yml on its own, or a bundle with the scripts it needs
        </Typography>
      </Box>

      <Paper
        variant="outlined"
        onDragOver={(e) => {
          e.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        sx={{
          p: 5,
          textAlign: 'center',
          borderStyle: 'dashed',
          borderWidth: 2,
          borderColor: (t) =>
            dragging ? t.palette.primary.main : t.palette.grey[300],
          backgroundColor: (t) =>
            dragging ? t.palette.primary.light : 'transparent',
          transition: 'background-color 120ms, border-color 120ms',
        }}
      >
        <UploadFileIcon sx={{ fontSize: 44, color: 'primary.main' }} />
        <Typography variant="h4_web" sx={{ mt: 1 }}>
          {label ?? 'Drop a .yml, a folder, or a .zip here'}
        </Typography>
        <Typography variant="h5_web" color="text.secondary" gutterBottom>
          Scripts are checked against what each step references
        </Typography>

        <Stack
          direction="row"
          spacing={1}
          sx={{ justifyContent: 'center', mt: 2 }}
        >
          <Button
            variant="outlined"
            startIcon={<UploadFileIcon />}
            onClick={() => fileRef.current?.click()}
            disabled={busy}
          >
            Choose files
          </Button>
          <Button
            variant="outlined"
            startIcon={<FolderZipIcon />}
            onClick={() => folderRef.current?.click()}
            disabled={busy}
          >
            Choose folder
          </Button>
        </Stack>

        <input
          ref={fileRef}
          type="file"
          multiple
          accept=".yml,.yaml,.zip,.py,.R,.r,.sh"
          hidden
          onChange={(e) => accept(Array.from(e.target.files ?? []))}
        />
        <input
          ref={folderRef}
          type="file"
          hidden
          // Non-standard, but it is how a directory picker works in every
          // browser that has one. React passes unknown attributes through.
          {...{ webkitdirectory: '', directory: '' }}
          onChange={(e) => accept(Array.from(e.target.files ?? []))}
        />
      </Paper>

      {error && (
        <Alert severity="error">
          <AlertTitle>
            {error instanceof RpcError ? readable(error.kind) : 'Upload failed'}
          </AlertTitle>
          {error.message}
        </Alert>
      )}

      {report && <ValidationReportView report={report} />}

      {uploaded && (
        <Stack direction="row" spacing={2}>
          {canRun && (
            <Button
              variant="contained"
              startIcon={<PlayArrowIcon />}
              disabled={busy}
              onClick={() => runNow.mutate()}
            >
              Execute now
            </Button>
          )}
          {canRun && (
            <Button
              variant="outlined"
              startIcon={<SaveIcon />}
              disabled={busy}
              onClick={() => save.mutate({})}
            >
              Save to study
            </Button>
          )}
          <Button
            variant="outlined"
            startIcon={<SaveIcon />}
            disabled={busy}
            onClick={() => save.mutate({ draft: true })}
          >
            Save as draft
          </Button>
        </Stack>
      )}

      {saveConflict && (
        <Alert
          severity="warning"
          action={
            <Button
              color="inherit"
              size="small"
              disabled={busy}
              onClick={() => save.mutate({ draft: !canRun, overwrite: true })}
            >
              Replace
            </Button>
          }
        >
          <AlertTitle>Already in this study</AlertTitle>
          {saveConflict}
        </Alert>
      )}
    </Stack>
  );
};

/** Folder picks carry a relative path; a plain file pick does not. */
const relativePathOf = (file: File): string =>
  (file as File & { webkitRelativePath?: string }).webkitRelativePath ||
  file.name;

/** Strip the wrapping folder a directory pick adds. */
const topFolderOf = (files: File[]): string | null => {
  const first = relativePathOf(files[0]);
  return first.includes('/') ? first.split('/')[0] : null;
};

/** The shallowest YAML - a nested step library must not be mistaken for it. */
const pickWorkflow = (files: File[]): File | undefined =>
  files
    .filter((f) => /\.ya?ml$/i.test(f.name))
    .sort(
      (a, b) =>
        relativePathOf(a).split('/').length - relativePathOf(b).split('/').length,
    )[0];

const readable = (kind: string): string => {
  switch (kind) {
    case 'WorkflowValidationError':
      return 'This workflow could not be read';
    case 'BadArchive':
      return 'The archive could not be read';
    case 'NoFile':
      return 'Nothing was uploaded';
    default:
      return kind;
  }
};

export default Upload;
