import { yaml as yamlLang } from '@codemirror/lang-yaml';
import { Alert, Box, Button, Paper, Stack, Typography } from '@mui/material';
import CodeMirror from '@uiw/react-codemirror';
import { useEffect, useState } from 'react';
import { parseWorkflow } from '../api/client';
import { draftFromWorkflow, type Draft } from '../compose/draft';
import type { LibraryEntry } from '../api/types';

/**
 * Editable YAML pane for the composer.
 *
 * The text is generated from the draft. Editing it detaches the pane, and
 * "Apply to composer" sends the text to the server, which parses it with the
 * same code that reads an uploaded workflow. Whatever comes back rebuilds the
 * draft.
 *
 * Parsing server-side keeps one schema implementation rather than two, and
 * means the editor reports exactly the errors an upload would.
 */
const YamlEditor = ({
  value,
  library,
  onParsed,
}: {
  value: string;
  library: LibraryEntry[];
  onParsed: (draft: Draft) => void;
}) => {
  const [text, setText] = useState(value);
  const [error, setError] = useState<string | null>(null);
  const [applying, setApplying] = useState(false);

  const dirty = text !== value;

  // Follow the draft while the pane is untouched; stop once it is edited, or
  // typing would be overwritten on every keystroke.
  useEffect(() => {
    if (!dirty) setText(value);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  const apply = async () => {
    setApplying(true);
    setError(null);
    try {
      const detail = await parseWorkflow(text);
      onParsed(draftFromWorkflow(detail.definition, library));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setApplying(false);
    }
  };

  return (
    <Stack spacing={1.5}>
      {dirty && (
        <Alert severity="warning">
          Edited by hand. The composer still shows the previous version until
          you apply these changes.
        </Alert>
      )}
      {error && <Alert severity="error">{error}</Alert>}

      <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
        <Box
          sx={{
            '& .cm-editor': { fontSize: '0.8125rem' },
            '& .cm-editor.cm-focused': { outline: 'none' },
          }}
        >
          <CodeMirror
            value={text}
            height="460px"
            extensions={[yamlLang()]}
            onChange={setText}
          />
        </Box>
      </Paper>

      <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
        <Button
          variant="contained"
          size="small"
          disabled={!dirty || applying}
          onClick={apply}
        >
          Apply to composer
        </Button>
        <Button
          size="small"
          disabled={!dirty}
          onClick={() => {
            setText(value);
            setError(null);
          }}
        >
          Discard edits
        </Button>
        <Typography variant="h6" color="text.secondary">
          Parsed by the server, so errors here match what an upload would report
        </Typography>
      </Stack>
    </Stack>
  );
};

export default YamlEditor;
