import DescriptionIcon from '@mui/icons-material/Description';
import {
  Alert,
  Box,
  Chip,
  CircularProgress,
  Paper,
  Stack,
  Typography,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

/**
 * File list plus a plain text viewer.
 *
 * Everything is fetched from the repo through the server, so a file shown here
 * is the file in carp-dsp - not a copy that can drift.
 */
const FileViewer = ({
  files,
  urlFor,
  emptyLabel = 'No files.',
}: {
  files: string[];
  urlFor: (path: string) => string;
  emptyLabel?: string;
}) => {
  const [selected, setSelected] = useState<string | null>(files[0] ?? null);

  const { data, isFetching, error } = useQuery({
    queryKey: ['repoFile', selected],
    queryFn: async () => {
      const response = await fetch(urlFor(selected!));
      if (!response.ok) throw new Error(`Could not read ${selected}`);
      return response.text();
    },
    enabled: Boolean(selected),
  });

  if (files.length === 0) {
    return <Typography variant="h5_web" color="text.disabled">{emptyLabel}</Typography>;
  }

  return (
    <Stack direction={{ xs: 'column', md: 'row' }} spacing={2}>
      <Stack spacing={0.5} sx={{ width: { md: 280 }, flexShrink: 0 }}>
        {group(files).map(([folder, items]) => (
          <Box key={folder}>
            <Typography variant="h6" color="text.secondary" sx={{ mt: 1 }}>
              {folder.toUpperCase()}
            </Typography>
            {items.map((file) => (
              <Box
                key={file}
                onClick={() => setSelected(file)}
                sx={{
                  px: 1,
                  py: 0.5,
                  borderRadius: 1,
                  cursor: 'pointer',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 0.5,
                  backgroundColor: (t) =>
                    file === selected ? t.palette.drawer.active : 'transparent',
                }}
              >
                <DescriptionIcon fontSize="inherit" color="disabled" />
                <Typography
                  variant="h6"
                  sx={{ fontFamily: 'monospace', wordBreak: 'break-all' }}
                >
                  {file.split('/').pop()}
                </Typography>
              </Box>
            ))}
          </Box>
        ))}
      </Stack>

      <Paper variant="outlined" sx={{ flex: 1, minWidth: 0, p: 2 }}>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1 }}>
          <Chip
            label={selected}
            size="small"
            variant="outlined"
            sx={{ fontFamily: 'monospace' }}
          />
          {isFetching && <CircularProgress size={14} />}
        </Stack>

        {error && <Alert severity="error">{(error as Error).message}</Alert>}

        {data !== undefined && (
          <Box
            component="pre"
            sx={{
              m: 0,
              maxHeight: 520,
              overflow: 'auto',
              fontSize: '0.78125rem',
              lineHeight: 1.6,
            }}
          >
            {data}
          </Box>
        )}
      </Paper>
    </Stack>
  );
};

/** Groups by top folder - impl, reference, or the step root. */
const group = (files: string[]): [string, string[]][] => {
  const map = new Map<string, string[]>();
  files.forEach((f) => {
    const folder = f.includes('/') ? f.split('/')[0] : 'step';
    map.set(folder, [...(map.get(folder) ?? []), f]);
  });
  return [...map.entries()].sort(([a], [b]) => a.localeCompare(b));
};

export default FileViewer;
