import {
  Alert,
  Box,
  Breadcrumbs,
  Chip,
  CircularProgress,
  Divider,
  Link,
  Paper,
  Stack,
  Tab,
  Tabs,
  Typography,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { getLibraryStep } from '../api/client';
import CertificationChip from '../components/CertificationChip';
import FileViewer from '../components/FileViewer';
import type { PortSpec } from '../api/types';

/** One library entry: what it does, what it takes, and how it was verified. */
const LibraryStep = () => {
  const { stepId = '' } = useParams();
  const [tab, setTab] = useState(0);

  const { data, isPending, error } = useQuery({
    queryKey: ['library', stepId],
    queryFn: () => getLibraryStep(stepId),
    enabled: Boolean(stepId),
  });

  if (isPending) return <CircularProgress />;
  if (error) return <Alert severity="error">{(error as Error).message}</Alert>;
  if (!data) return null;

  const meta = data.definition.metadata;
  const step = data.definition.steps[0];
  const lib = data.definition.library;

  return (
    <Stack spacing={3} sx={{ maxWidth: 900 }}>
      <Box>
        <Breadcrumbs sx={{ mb: 0.5 }}>
          <Link component={RouterLink} to="/library" underline="hover">
            Step library
          </Link>
          <Typography variant="h6">{lib?.tier ?? '—'}</Typography>
        </Breadcrumbs>

        <Stack
          direction="row"
          spacing={2}
          sx={{ alignItems: 'flex-start', justifyContent: 'space-between' }}
        >
          <Box>
            <Typography variant="h1_web" component="h1">
              {meta.name}
            </Typography>
            <Typography
              variant="h6"
              color="text.secondary"
              sx={{ fontFamily: 'monospace' }}
            >
              {data.stepId}
            </Typography>
          </Box>
          <CertificationChip certification={data.certification} />
        </Stack>

        <Typography variant="h3_web" color="text.secondary" sx={{ mt: 1 }}>
          {meta.description}
        </Typography>

        <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap', mt: 1.5 }}>
          <Chip label={`v${meta.version}`} size="small" color="primary" />
          {meta.tags.map((tag) => (
            <Chip key={tag} label={tag} size="small" variant="outlined" />
          ))}
        </Stack>
      </Box>

      <Tabs value={tab} onChange={(_, v) => setTab(v)}>
        <Tab label="Interface" />
        <Tab label="Method & verification" />
        <Tab label={`Files (${data.files.length})`} />
        {data.readme && <Tab label="README" />}
        <Tab label="YAML" />
      </Tabs>

      {tab === 0 && (
        <Stack spacing={2}>
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Stack
              direction={{ xs: 'column', md: 'row' }}
              spacing={2}
              divider={<Divider orientation="vertical" flexItem />}
            >
              <Ports title="Inputs" ports={step?.inputs ?? []} />
              <Ports title="Outputs" ports={step?.outputs ?? []} />
            </Stack>
          </Paper>

          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="h4_web" gutterBottom>
              Environment
            </Typography>
            <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap' }}>
              {lib?.environment?.requires?.interpreter && (
                <Chip
                  size="small"
                  color="primary"
                  label={`${lib.environment.requires.interpreter.name} ${lib.environment.requires.interpreter.version}`}
                />
              )}
              {lib?.environment?.requires?.kind.map((k) => (
                <Chip key={k} label={k} size="small" variant="outlined" />
              ))}
              {lib?.environment?.requires?.packages.length === 0 && (
                <Chip label="no packages" size="small" variant="outlined" />
              )}
              {lib?.environment?.requires?.packages.map((p) => (
                <Chip
                  key={p.name}
                  label={p.version ? `${p.name} ${p.version}` : p.name}
                  size="small"
                  variant="outlined"
                />
              ))}
            </Stack>
          </Paper>
        </Stack>
      )}

      {tab === 1 && (
        <Stack spacing={2}>
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="h4_web" gutterBottom>
              Method
            </Typography>
            <Typography variant="h5_web">
              {lib?.method?.name ?? 'Not stated'}
            </Typography>
            <Typography variant="h6" color="text.secondary" sx={{ mt: 0.5 }}>
              {lib?.method?.citation ?? 'No citation recorded'}
            </Typography>

            {(lib?.implementations ?? []).length > 0 && (
              <Stack spacing={0.5} sx={{ mt: 1.5 }}>
                {lib!.implementations.map((impl) => (
                  <Field
                    key={impl.language}
                    label={impl.language}
                    value={impl.path}
                    href={fileHref(data.stepId, impl.path)}
                  />
                ))}
              </Stack>
            )}
          </Paper>

          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="h4_web" gutterBottom>
              Reference fixture
            </Typography>
            {lib?.reference ? (
              <Stack spacing={0.5}>
                <Field
                  label="Input"
                  value={lib.reference.input}
                  href={fileHref(data.stepId, lib.reference.input)}
                />
                <Field
                  label="Expected"
                  value={lib.reference.expected}
                  href={fileHref(data.stepId, lib.reference.expected)}
                />
                <Field
                  label="Float tolerance"
                  value={lib.reference.tolerance?.float?.toString()}
                />
              </Stack>
            ) : (
              <Typography variant="h5_web" color="text.disabled">
                None recorded.
              </Typography>
            )}
          </Paper>

          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="h4_web" gutterBottom>
              Conformance
            </Typography>
            <Stack spacing={0.5}>
              <Field label="Level" value={data.certification?.level} />
              <Field label="Content hash" value={data.certification?.contentHash} />
              <Field label="Reviewed by" value={data.certification?.reviewer} />
              <Field label="Reviewed on" value={data.certification?.reviewedOn} />
              <Field label="Reviewed PR" value={data.certification?.reviewedPr} />
            </Stack>
          </Paper>
        </Stack>
      )}

      {tab === 2 && (
        <FileViewer
          files={data.files}
          urlFor={(path) =>
            `/api/repo/steps/${encodeURIComponent(data.stepId)}/${path}`
          }
          emptyLabel="This step has no files beyond its definition."
        />
      )}

      {tab === 3 && data.readme && (
        <Paper variant="outlined" sx={{ p: 2, overflowX: 'auto' }}>
          <Box component="pre" sx={{ m: 0, fontSize: '0.8125rem', lineHeight: 1.6 }}>
            {data.readme}
          </Box>
        </Paper>
      )}

      {tab === (data.readme ? 4 : 3) && (
        <Paper variant="outlined" sx={{ p: 2, overflowX: 'auto' }}>
          <Box component="pre" sx={{ m: 0, fontSize: '0.8125rem', lineHeight: 1.5 }}>
            {data.rawYaml}
          </Box>
        </Paper>
      )}
    </Stack>
  );
};

const Ports = ({ title, ports }: { title: string; ports: PortSpec[] }) => (
  <Box sx={{ flex: 1, minWidth: 0 }}>
    <Typography variant="h6" color="text.secondary" gutterBottom>
      {title.toUpperCase()}
    </Typography>
    {ports.length === 0 ? (
      <Typography variant="h5_web" color="text.disabled">
        None
      </Typography>
    ) : (
      <Stack spacing={1.5}>
        {ports.map((port) => (
          <Box key={port.id}>
            <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
              <Typography variant="h5">{port.id}</Typography>
              {port.descriptor?.fileFormat && (
                <Chip
                  label={port.descriptor.fileFormat}
                  size="small"
                  variant="outlined"
                />
              )}
            </Stack>
            {port.descriptor?.notes && (
              <Typography variant="h6" color="text.secondary">
                {port.descriptor.notes}
              </Typography>
            )}
          </Box>
        ))}
      </Stack>
    )}
  </Box>
);

/** Declared paths are links, so a stated file can be read rather than trusted. */
const fileHref = (stepId: string, path?: string | null): string | undefined =>
  path ? `/api/repo/steps/${encodeURIComponent(stepId)}/${path}` : undefined;

const Field = ({
  label,
  value,
  href,
}: {
  label: string;
  value?: string | null;
  href?: string;
}) => (
  <Stack direction="row" spacing={1}>
    <Typography variant="h6" color="text.secondary" sx={{ minWidth: 120 }}>
      {label}
    </Typography>
    {value && href ? (
      <Link
        href={href}
        target="_blank"
        rel="noreferrer"
        variant="h6"
        sx={{ fontFamily: 'monospace', wordBreak: 'break-all' }}
      >
        {value}
      </Link>
    ) : (
      <Typography
        variant="h6"
        sx={{ fontFamily: 'monospace', wordBreak: 'break-all' }}
        color={value ? 'text.primary' : 'text.disabled'}
      >
        {value ?? 'not set'}
      </Typography>
    )}
  </Stack>
);

export default LibraryStep;
