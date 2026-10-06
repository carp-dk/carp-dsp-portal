import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { listRunVariables, setRunVariable, testSqlConnection } from '../api/client';
import type { RunVariable } from '../api/types';

const QUERY_KEY = ['run-environment'];

const SOURCE_LABEL: Record<RunVariable['source'], string> = {
  SAVED: 'Saved here',
  CONTAINER: 'Container',
  UNSET: 'Not set',
};

/**
 * The variables every step receives, editable while the portal runs.
 *
 * Saved values sit over the container's environment and reach the next run, so
 * connection details can be fixed in a deployed portal without a rebuild.
 */
const Settings = () => {
  const { data, isPending, error } = useQuery({ queryKey: QUERY_KEY, queryFn: listRunVariables });

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1_web" component="h1">
          Settings
        </Typography>
        <Typography variant="h3_web" color="text.secondary">
          Variables every step receives
        </Typography>
      </Box>

      <Alert severity="info">
        Saved values apply from the next run and replace the container&apos;s. Clear one to fall
        back to the container. Passwords are never shown.
      </Alert>

      {isPending && <CircularProgress />}
      {error && <Alert severity="error">Could not load settings: {error.message}</Alert>}
      {data && <VariablesTable variables={data} />}

      <SqlCheck />
    </Stack>
  );
};

const VariablesTable = ({ variables }: { variables: RunVariable[] }) => (
  <Paper variant="outlined">
    <Table>
      <TableHead>
        <TableRow>
          <TableCell>Name</TableCell>
          <TableCell>Value</TableCell>
          <TableCell>Source</TableCell>
          <TableCell />
        </TableRow>
      </TableHead>
      <TableBody>
        {variables.map((variable) => (
          // Keyed on what it shows, so a save or clear resets the row's input.
          <VariableRow
            key={`${variable.name}:${variable.source}:${variable.value ?? ''}`}
            variable={variable}
          />
        ))}
        <NewVariableRow />
      </TableBody>
    </Table>
  </Paper>
);

const useSave = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ name, value }: { name: string; value: string | null }) =>
      setRunVariable(name, value),
    onSuccess: (next) => queryClient.setQueryData(QUERY_KEY, next),
  });
};

const VariableRow = ({ variable }: { variable: RunVariable }) => {
  const [value, setValue] = useState(variable.value ?? '');
  const save = useSave();
  const changed = value !== (variable.value ?? '');

  return (
    <TableRow>
      <TableCell>
        <Typography variant="h5" sx={{ fontFamily: 'monospace' }}>
          {variable.name}
        </Typography>
      </TableCell>
      <TableCell sx={{ minWidth: 320 }}>
        <TextField
          size="small"
          fullWidth
          type={variable.secret ? 'password' : 'text'}
          value={value}
          placeholder={variable.secret && variable.set ? '•••••• (set)' : ''}
          onChange={(e) => setValue(e.target.value)}
        />
      </TableCell>
      <TableCell>
        <Chip
          size="small"
          variant={variable.source === 'SAVED' ? 'filled' : 'outlined'}
          color={variable.source === 'UNSET' ? 'warning' : 'default'}
          label={SOURCE_LABEL[variable.source]}
        />
      </TableCell>
      <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
        <Button
          size="small"
          disabled={!changed || value === '' || save.isPending}
          onClick={() =>
            save.mutate(
              { name: variable.name, value },
              { onSuccess: () => variable.secret && setValue('') },
            )
          }
        >
          Save
        </Button>
        <Button
          size="small"
          color="inherit"
          disabled={variable.source !== 'SAVED' || save.isPending}
          onClick={() => save.mutate({ name: variable.name, value: null })}
        >
          Clear
        </Button>
        {save.error && (
          <Typography variant="h6" color="error">
            {save.error.message}
          </Typography>
        )}
      </TableCell>
    </TableRow>
  );
};

const NewVariableRow = () => {
  const [name, setName] = useState('');
  const [value, setValue] = useState('');
  const save = useSave();

  const add = () =>
    save.mutate(
      { name: name.trim(), value },
      {
        onSuccess: () => {
          setName('');
          setValue('');
        },
      },
    );

  return (
    <TableRow>
      <TableCell>
        <TextField
          size="small"
          placeholder="NEW_VARIABLE"
          value={name}
          onChange={(e) => setName(e.target.value.toUpperCase())}
        />
      </TableCell>
      <TableCell>
        <TextField size="small" fullWidth value={value} onChange={(e) => setValue(e.target.value)} />
      </TableCell>
      <TableCell />
      <TableCell align="right">
        <Button size="small" disabled={!name.trim() || !value || save.isPending} onClick={add}>
          Add
        </Button>
        {save.error && (
          <Typography variant="h6" color="error">
            {save.error.message}
          </Typography>
        )}
      </TableCell>
    </TableRow>
  );
};

/** Opens a connection with the values a run would get. */
const SqlCheck = () => {
  const check = useMutation({ mutationFn: testSqlConnection });

  return (
    <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
      <Button variant="outlined" disabled={check.isPending} onClick={() => check.mutate()}>
        Test SQL connection
      </Button>
      {check.isPending && <CircularProgress size={20} />}
      {check.data && (
        <Alert severity={check.data.ok ? 'success' : 'error'} sx={{ flex: 1 }}>
          {check.data.message}
        </Alert>
      )}
      {check.error && (
        <Alert severity="error" sx={{ flex: 1 }}>
          {check.error.message}
        </Alert>
      )}
    </Stack>
  );
};

export default Settings;
