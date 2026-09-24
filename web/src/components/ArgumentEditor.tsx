import AddIcon from '@mui/icons-material/Add';
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward';
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward';
import DeleteIcon from '@mui/icons-material/Delete';
import {
  Box,
  Button,
  Chip,
  IconButton,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';

/**
 * Editor for a step's command-line arguments.
 *
 * A textarea was wrong for this: arguments are an ordered list where position
 * carries meaning - `--columns` and its value are two entries, and
 * `input.0` refers to the first wired input by index. Newline-splitting hid
 * both facts and made a trailing space a silent argument.
 *
 * Each argument is its own field, order is explicit, and the placeholder
 * tokens are labelled rather than left looking like typos.
 */
const PORT_TOKEN = /^(input|output)\.(\d+)$/;

const ArgumentEditor = ({
  args,
  onChange,
  inputCount,
  outputCount,
}: {
  args: string[];
  onChange: (args: string[]) => void;
  inputCount: number;
  outputCount: number;
}) => {
  const patch = (index: number, value: string) =>
    onChange(args.map((a, i) => (i === index ? value : a)));

  const move = (index: number, by: number) => {
    const to = index + by;
    if (to < 0 || to >= args.length) return;
    const next = [...args];
    [next[index], next[to]] = [next[to], next[index]];
    onChange(next);
  };

  /** Flags a token that points at a port which does not exist. */
  const tokenProblem = (value: string): string | null => {
    const match = PORT_TOKEN.exec(value.trim());
    if (!match) return null;
    const [, kind, indexText] = match;
    const index = Number(indexText);
    const available = kind === 'input' ? inputCount : outputCount;
    return index < available
      ? null
      : `No ${kind}.${index} - this step has ${available} ${kind}${available === 1 ? '' : 's'}`;
  };

  return (
    <Box>
      <Stack
        direction="row"
        spacing={1}
        sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 1 }}
      >
        <Typography variant="h6" color="text.secondary">
          ARGUMENTS
        </Typography>
        <Button
          size="small"
          startIcon={<AddIcon />}
          onClick={() => onChange([...args, ''])}
        >
          Add
        </Button>
      </Stack>

      {args.length === 0 ? (
        <Typography variant="h6" color="text.disabled">
          None. Add one per token - a flag and its value are separate entries.
        </Typography>
      ) : (
        <Stack spacing={0.5}>
          {args.map((arg, index) => {
            const problem = tokenProblem(arg);
            const isToken = PORT_TOKEN.test(arg.trim());

            return (
              <Stack
                key={index}
                direction="row"
                spacing={0.5}
                sx={{ alignItems: 'flex-start' }}
              >
                <Typography
                  variant="h6"
                  color="text.disabled"
                  sx={{ width: 20, pt: 1.2, textAlign: 'right' }}
                >
                  {index}
                </Typography>

                <TextField
                  value={arg}
                  onChange={(e) => patch(index, e.target.value)}
                  size="small"
                  fullWidth
                  error={Boolean(problem)}
                  helperText={problem ?? undefined}
                  slotProps={{
                    input: {
                      endAdornment: isToken && !problem && (
                        <Tooltip title="Replaced at run time with the wired port">
                          <Chip label="port" size="small" color="primary" />
                        </Tooltip>
                      ),
                      sx: { fontFamily: 'monospace' },
                    },
                  }}
                />

                <IconButton
                  size="small"
                  aria-label="Move argument up"
                  disabled={index === 0}
                  onClick={() => move(index, -1)}
                >
                  <ArrowUpwardIcon fontSize="inherit" />
                </IconButton>
                <IconButton
                  size="small"
                  aria-label="Move argument down"
                  disabled={index === args.length - 1}
                  onClick={() => move(index, 1)}
                >
                  <ArrowDownwardIcon fontSize="inherit" />
                </IconButton>
                <IconButton
                  size="small"
                  aria-label="Remove argument"
                  onClick={() => onChange(args.filter((_, i) => i !== index))}
                >
                  <DeleteIcon fontSize="inherit" />
                </IconButton>
              </Stack>
            );
          })}
        </Stack>
      )}

      {(inputCount > 0 || outputCount > 0) && (
        <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap', mt: 1 }}>
          <Typography variant="h6" color="text.secondary" sx={{ mr: 0.5 }}>
            Insert:
          </Typography>
          {Array.from({ length: inputCount }, (_, i) => `input.${i}`)
            .concat(Array.from({ length: outputCount }, (_, i) => `output.${i}`))
            .map((token) => (
              <Chip
                key={token}
                label={token}
                size="small"
                variant="outlined"
                clickable
                onClick={() => onChange([...args, token])}
                sx={{ fontFamily: 'monospace' }}
              />
            ))}
        </Stack>
      )}
    </Box>
  );
};

export default ArgumentEditor;
