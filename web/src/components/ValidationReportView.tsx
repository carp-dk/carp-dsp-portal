import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import ErrorIcon from '@mui/icons-material/Error';
import WarningIcon from '@mui/icons-material/Warning';
import {
  Alert,
  Box,
  Chip,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import type { Finding, ValidationReport } from '../api/types';

/**
 * Validation output.
 *
 * Two halves, because they answer different questions: the findings say what is
 * wrong, and the resolution table says how each step expects to be satisfied -
 * from the library, or from a file in the bundle.
 */
const ValidationReportView = ({ report }: { report: ValidationReport }) => {
  const errors = report.findings.filter((f) => f.severity === 'ERROR');
  const warnings = report.findings.filter((f) => f.severity === 'WARNING');

  return (
    <Stack spacing={2}>
      <Alert
        severity={report.valid ? 'success' : 'error'}
        icon={report.valid ? <CheckCircleIcon /> : <ErrorIcon />}
      >
        {report.valid
          ? `${report.workflowName ?? 'Workflow'} is complete - ${report.stepCount} steps, ${report.fileCount} files.`
          : `${errors.length} problem${errors.length === 1 ? '' : 's'} to fix before this can run.`}
        {warnings.length > 0 && ` ${warnings.length} warning${warnings.length === 1 ? '' : 's'}.`}
      </Alert>

      {errors.length > 0 && (
        <FindingList title="Problems" findings={errors} />
      )}
      {warnings.length > 0 && (
        <FindingList title="Warnings" findings={warnings} />
      )}

      {report.resolutions.length > 0 && (
        <Paper variant="outlined">
          <Box sx={{ p: 2, pb: 0 }}>
            <Typography variant="h4_web">How each step resolves</Typography>
          </Box>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Step</TableCell>
                <TableCell>Resolves via</TableCell>
                <TableCell>Needs</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {report.resolutions.map((r) => (
                <TableRow key={r.stepId}>
                  <TableCell>
                    <Typography variant="h5">{r.name}</Typography>
                    <Typography
                      variant="h6"
                      color="text.secondary"
                      sx={{ fontFamily: 'monospace' }}
                    >
                      {r.stepId}
                    </Typography>
                  </TableCell>

                  <TableCell>
                    {r.resolvedVia === 'library' && r.libraryStepId ? (
                      <Chip
                        size="small"
                        color="primary"
                        label={r.libraryStepId}
                        clickable
                        component={RouterLink}
                        to={`/library/${r.libraryStepId}`}
                        sx={{ fontFamily: 'monospace' }}
                      />
                    ) : (
                      <Chip
                        size="small"
                        label={r.resolvedVia}
                        color={r.resolvedVia === 'unresolved' ? 'error' : 'default'}
                        variant={r.resolvedVia === 'bundle' ? 'outlined' : 'filled'}
                      />
                    )}
                  </TableCell>

                  <TableCell>
                    {r.scripts.length === 0 ? (
                      <Typography variant="h6" color="text.disabled">
                        nothing from the bundle
                      </Typography>
                    ) : (
                      <Stack spacing={0.5}>
                        {r.scripts.map((s) => (
                          <Stack
                            key={s}
                            direction="row"
                            spacing={0.5}
                            sx={{ alignItems: 'center' }}
                          >
                            {r.missingScripts.includes(s) ? (
                              <ErrorIcon color="error" fontSize="inherit" />
                            ) : (
                              <CheckCircleIcon color="success" fontSize="inherit" />
                            )}
                            <Typography
                              variant="h6"
                              sx={{ fontFamily: 'monospace' }}
                              color={
                                r.missingScripts.includes(s)
                                  ? 'error.main'
                                  : 'text.secondary'
                              }
                            >
                              {s}
                            </Typography>
                          </Stack>
                        ))}
                      </Stack>
                    )}
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

const FindingList = ({
  title,
  findings,
}: {
  title: string;
  findings: Finding[];
}) => (
  <Paper variant="outlined" sx={{ p: 2 }}>
    <Typography variant="h4_web" gutterBottom>
      {title}
    </Typography>
    <Stack spacing={1}>
      {findings.map((f, i) => (
        <Stack key={i} direction="row" spacing={1} sx={{ alignItems: 'flex-start' }}>
          {f.severity === 'ERROR' ? (
            <ErrorIcon color="error" fontSize="small" />
          ) : (
            <WarningIcon color="warning" fontSize="small" />
          )}
          <Box>
            <Typography variant="h5_web">{f.message}</Typography>
            <Typography
              variant="h6"
              color="text.secondary"
              sx={{ fontFamily: 'monospace' }}
            >
              {f.code}
              {f.stepId ? ` · ${f.stepId}` : ''}
            </Typography>
          </Box>
        </Stack>
      ))}
    </Stack>
  </Paper>
);

export default ValidationReportView;
