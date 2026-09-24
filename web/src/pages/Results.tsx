import DownloadIcon from '@mui/icons-material/Download';
import InsertChartIcon from '@mui/icons-material/InsertChart';
import {
  Alert,
  Box,
  Breadcrumbs,
  Button,
  Chip,
  CircularProgress,
  Divider,
  Link,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { getExecutionResult, getRunArtefacts } from '../api/client';
import {
  branchOf,
  isInlineRenderable,
  type ArtifactBranch,
} from '../api/artifacts';
import type { ArtifactEntry } from '../api/types';

/**
 * Results view.
 *
 * Artefacts are grouped by branch, which is read off the flat type-string
 * prefix - that prefix filtering is the documented contract, not a convenience.
 * Image and plot artefacts render inline; everything else is a row with type,
 * size and a download link.
 */
const BRANCH_LABELS: Record<ArtifactBranch, string> = {
  analytic: 'Analytic results',
  media: 'Plots and images',
  control: 'Control signals',
  generic: 'Other artefacts',
};

const Results = () => {
  const { runId = '' } = useParams();

  const artefacts = useQuery({
    queryKey: ['artefacts', runId],
    queryFn: () => getRunArtefacts(runId),
    enabled: Boolean(runId),
  });

  const report = useQuery({
    queryKey: ['report', runId],
    queryFn: () => getExecutionResult(runId),
    enabled: Boolean(runId),
  });

  if (artefacts.isPending) return <CircularProgress />;
  if (artefacts.error)
    return <Alert severity="error">{(artefacts.error as Error).message}</Alert>;

  const entries = artefacts.data ?? [];
  const issues = report.data?.issues ?? [];

  const grouped = entries.reduce<Record<string, ArtifactEntry[]>>((acc, entry) => {
    const branch = branchOf(entry.artifact);
    (acc[branch] ??= []).push(entry);
    return acc;
  }, {});

  return (
    <Stack spacing={3}>
      <Box>
        <Breadcrumbs sx={{ mb: 0.5 }}>
          <Link component={RouterLink} to="/runs" underline="hover">
            Runs
          </Link>
          <Link component={RouterLink} to={`/runs/${runId}`} underline="hover">
            This run
          </Link>
        </Breadcrumbs>
        <Typography variant="h1_web" component="h1">
          Results
        </Typography>
        <Typography variant="h6" color="text.secondary" sx={{ fontFamily: 'monospace' }}>
          {runId}
        </Typography>
      </Box>

      {issues.length > 0 && (
        <Stack spacing={1}>
          {issues.map((issue, i) => (
            <Alert key={i} severity="warning">
              <strong>{issue.kind}</strong>
              {issue.stepMetadata?.descriptorId
                ? ` in ${issue.stepMetadata.descriptorId}`
                : ''}{' '}
              - {issue.message}
            </Alert>
          ))}
        </Stack>
      )}

      {entries.length === 0 && (
        <Alert severity="info">
          No artefacts yet. They appear as steps complete.
        </Alert>
      )}

      {(Object.keys(BRANCH_LABELS) as ArtifactBranch[])
        .filter((branch) => grouped[branch]?.length)
        .map((branch) => (
          <Box key={branch}>
            <Typography variant="h2_web" component="h2" gutterBottom>
              {BRANCH_LABELS[branch]}
            </Typography>
            <Stack spacing={2}>
              {grouped[branch].map((entry) => (
                <ArtifactCard
                  key={`${entry.stepId}/${entry.outputId}`}
                  entry={entry}
                />
              ))}
            </Stack>
          </Box>
        ))}
    </Stack>
  );
};

const ArtifactCard = ({ entry }: { entry: ArtifactEntry }) => {
  const { artifact } = entry;

  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Stack
        direction="row"
        spacing={2}
       
       
       sx={{ alignItems: 'flex-start', justifyContent: 'space-between' }}>
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="h4_web">{entry.outputName ?? entry.outputId}</Typography>
          <Typography variant="h6" color="text.secondary">
            produced by {entry.stepName ?? entry.stepId}
          </Typography>
          <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap', mt: 1 }}>
            <Chip
              label={artifact.__type.split('.').slice(-2).join('.')}
              size="small"
              color="primary"
              sx={{ fontFamily: 'monospace' }}
            />
            {'reference' in artifact && artifact.reference && (
              <>
                <Chip label={artifact.reference.mimeType} size="small" variant="outlined" />
                <Chip
                  label={formatBytes(artifact.reference.sizeBytes)}
                  size="small"
                  variant="outlined"
                />
              </>
            )}
          </Stack>
        </Box>

        <Button
          size="small"
          startIcon={<DownloadIcon />}
          component="a"
          href={entry.downloadUrl ?? undefined}
          download
          disabled={!entry.downloadUrl}
        >
          Download
        </Button>
      </Stack>

      <ArtifactBody entry={entry} />
    </Paper>
  );
};

const ArtifactBody = ({ entry }: { entry: ArtifactEntry }) => {
  const { artifact, preview, downloadUrl } = entry;

  if (isInlineRenderable(artifact)) {
    return (
      <>
        <Divider sx={{ my: 1.5 }} />
        {downloadUrl ? (
          // Real bytes from the recorded run, served by the artefact endpoint.
          <Box
            component="img"
            src={downloadUrl}
            alt={entry.outputName ?? entry.outputId}
            sx={{
              display: 'block',
              maxWidth: '100%',
              borderRadius: 1,
              border: (t) => `1px solid ${t.palette.grey[200]}`,
            }}
          />
        ) : (
          <Box
            sx={{
              height: 200,
              display: 'grid',
              placeItems: 'center',
              borderRadius: 1,
              color: 'text.disabled',
              backgroundColor: (t) => t.palette.grey[200],
            }}
          >
            <Stack sx={{ alignItems: 'center' }}>
              <InsertChartIcon sx={{ fontSize: 40 }} />
              <Typography variant="h5_web">
                No recorded output for this step yet
              </Typography>
            </Stack>
          </Box>
        )}
      </>
    );
  }

  if (artifact.__type === 'dk.cachet.carp.dsp.analytic.table') {
    return (
      <>
        <Divider sx={{ my: 1.5 }} />
        {preview ? (
          <>
            <Typography variant="h6" color="text.secondary" gutterBottom>
              first {preview.rows.length} of{' '}
              {preview.totalRows?.toLocaleString() ?? '?'} rows
            </Typography>
            <Box sx={{ overflowX: 'auto' }}>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    {preview.columns.map((column) => (
                      <TableCell key={column} sx={{ fontFamily: 'monospace' }}>
                        {column}
                      </TableCell>
                    ))}
                  </TableRow>
                </TableHead>
                <TableBody>
                  {preview.rows.map((row, i) => (
                    <TableRow key={i}>
                      {row.map((cell, j) => (
                        <TableCell key={j} sx={{ whiteSpace: 'nowrap' }}>
                          {cell}
                        </TableCell>
                      ))}
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Box>
          </>
        ) : (
          <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap' }}>
            {artifact.columns.map((column) => (
              <Chip
                key={column}
                label={column}
                size="small"
                variant="outlined"
                sx={{ fontFamily: 'monospace' }}
              />
            ))}
          </Stack>
        )}
      </>
    );
  }

  if (
    artifact.__type === 'dk.cachet.carp.dsp.analytic.summary' ||
    artifact.__type === 'dk.cachet.carp.dsp.analytic.feature'
  ) {
    return (
      <>
        <Divider sx={{ my: 1.5 }} />
        <Typography variant="h2_web">
          {artifact.value}
          {artifact.unit ? ` ${artifact.unit}` : ''}
        </Typography>
        {'sampleCount' in artifact && (
          <Typography variant="h6" color="text.secondary">
            over {artifact.sampleCount} observations
          </Typography>
        )}
      </>
    );
  }

  return null;
};

const formatBytes = (bytes: number): string => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} kB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
};

export default Results;
