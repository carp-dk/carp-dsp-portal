import InsertChartIcon from '@mui/icons-material/InsertChart';
import {
  Box,
  Chip,
  Divider,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { isInlineRenderable } from '../api/artifacts';
import type { ArtifactEntry } from '../api/types';

/**
 * What an artefact holds, shown under its name: an image inline, the first rows
 * of a table, or a value. Renders nothing for anything else.
 *
 * `maxRows` caps the table rows shown, for lists.
 */
const ArtifactPreview = ({ entry, maxRows }: { entry: ArtifactEntry; maxRows?: number }) => {
  const { artifact, preview, downloadUrl } = entry;

  if (isInlineRenderable(artifact)) {
    return (
      <>
        <Divider sx={{ my: 1.5 }} />
        {downloadUrl ? (
          // Real bytes, served by the artefact endpoint.
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
              <Typography variant="h5_web">No recorded output for this step yet</Typography>
            </Stack>
          </Box>
        )}
      </>
    );
  }

  // Any output with a preview, typed as a table or not: a real run's CSV comes
  // back generic, with its head read.
  if (preview) {
    const rows = maxRows === undefined ? preview.rows : preview.rows.slice(0, maxRows);
    return (
      <>
        <Divider sx={{ my: 1.5 }} />
        <Typography variant="h6" color="text.secondary" gutterBottom>
          first {rows.length}
          {preview.totalRows != null ? ` of ${preview.totalRows.toLocaleString()}` : ''} rows
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
              {rows.map((row, i) => (
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
    );
  }

  if (artifact.__type === 'dk.cachet.carp.dsp.analytic.table') {
    return (
      <>
        <Divider sx={{ my: 1.5 }} />
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

export const formatBytes = (bytes: number): string => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} kB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
};

export default ArtifactPreview;
