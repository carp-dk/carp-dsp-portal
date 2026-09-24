import { Box, Paper, Stack, Typography } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import type { StepSpec } from '../api/types';

/**
 * Dependency graph, laid out by hand in SVG.
 *
 * No graph library on purpose - we should look into using one.
 */
const NODE_W = 190;
const NODE_H = 58;
const COL_GAP = 90;
const ROW_GAP = 26;
const PAD = 16;

type Placed = {
  step: StepSpec;
  level: number;
  row: number;
  x: number;
  y: number;
};

const WorkflowDag = ({ steps }: { steps: StepSpec[] }) => {
  const theme = useTheme();

  if (steps.length === 0) {
    return <Typography variant="h5_web">No steps to draw.</Typography>;
  }

  const placed = layout(steps);
  const byId = new Map(placed.map((p) => [p.step.id, p]));

  const maxLevel = Math.max(...placed.map((p) => p.level));
  const maxRow = Math.max(...placed.map((p) => p.row));
  const width = PAD * 2 + (maxLevel + 1) * NODE_W + maxLevel * COL_GAP;
  const height = PAD * 2 + (maxRow + 1) * NODE_H + maxRow * ROW_GAP;

  // What actually runs: an edge for every input read from another step.
  const dataPairs = new Set<string>();
  steps.forEach((step) =>
    step.inputs.forEach((input) => {
      const from = input.source?.stepId;
      if (!from || from === step.id) return;
      dataPairs.add(`${from}->${step.id}`);
    }),
  );

  // Declared but unenforced: a dependsOn the data does not already imply.
  const declaredOnlyPairs = new Set<string>();
  steps.forEach((step) =>
    step.dependsOn.forEach((dep) => {
      const key = `${dep}->${step.id}`;
      if (!dataPairs.has(key)) declaredOnlyPairs.add(key);
    }),
  );

  const edge = (key: string, dashed: boolean) => {
    const [fromId, toId] = key.split('->');
    const from = byId.get(fromId);
    const to = byId.get(toId);
    if (!from || !to) return null;

    const x1 = from.x + NODE_W;
    const y1 = from.y + NODE_H / 2;
    const x2 = to.x;
    const y2 = to.y + NODE_H / 2;
    const mid = (x1 + x2) / 2;

    return (
      <path
        key={`${key}-${dashed ? 'declared' : 'data'}`}
        d={`M ${x1} ${y1} C ${mid} ${y1}, ${mid} ${y2}, ${x2} ${y2}`}
        fill="none"
        stroke={dashed ? theme.palette.warning.main : theme.palette.grey[300]}
        strokeWidth={dashed ? 1.5 : 2}
        strokeDasharray={dashed ? '5 4' : undefined}
        markerEnd={`url(#${dashed ? 'arrow-declared' : 'arrow-data'})`}
      />
    );
  };

  return (
    <Stack spacing={2}>
      <Legend hasDeclaredOnly={declaredOnlyPairs.size > 0} />

      <Paper variant="outlined" sx={{ p: 1, overflowX: 'auto' }}>
        <Box
          component="svg"
          viewBox={`0 0 ${width} ${height}`}
          sx={{ width, maxWidth: 'none', height, display: 'block' }}
        >
          <defs>
            <Arrow id="arrow-data" colour={theme.palette.grey[300]} />
            <Arrow id="arrow-declared" colour={theme.palette.warning.main} />
          </defs>

          {/* Edges first, so nodes sit on top of them. */}
          {[...dataPairs].map((key) => edge(key, false))}
          {[...declaredOnlyPairs].map((key) => edge(key, true))}

          {placed.map(({ step, x, y }, index) => (
            <g
              key={step.id}
              transform={`translate(${x} ${y})`}
              style={{ cursor: 'pointer' }}
              onClick={() => {
                document
                  .getElementById(`step-${step.id}`)
                  ?.scrollIntoView({ behavior: 'smooth', block: 'center' });
              }}
            >
              <rect
                width={NODE_W}
                height={NODE_H}
                rx={8}
                fill={theme.palette.grey[100]}
                stroke={theme.palette.grey[300]}
              />
              <circle
                cx={20}
                cy={NODE_H / 2}
                r={12}
                fill={theme.palette.primary.main}
              />
              <text
                x={20}
                y={NODE_H / 2 + 4}
                textAnchor="middle"
                fontSize={11}
                fontWeight={700}
                fill="#fff"
              >
                {index + 1}
              </text>
              <text
                x={40}
                y={NODE_H / 2 - 2}
                fontSize={12}
                fontWeight={700}
                fill={theme.palette.text.heading}
              >
                {truncate(step.metadata?.name ?? step.id, 20)}
              </text>
              <text
                x={40}
                y={NODE_H / 2 + 14}
                fontSize={10}
                fill={theme.palette.text.secondary}
              >
                {truncate(step.id, 24)}
              </text>
            </g>
          ))}
        </Box>
      </Paper>
    </Stack>
  );
};

const Arrow = ({ id, colour }: { id: string; colour: string }) => (
  <marker
    id={id}
    viewBox="0 0 10 10"
    refX={9}
    refY={5}
    markerWidth={6}
    markerHeight={6}
    orient="auto-start-reverse"
  >
    <path d="M 0 0 L 10 5 L 0 10 z" fill={colour} />
  </marker>
);

/**
 * The dashed entry is shown only when there is one to explain. In a workflow
 * where every `dependsOn` is backed by data - which is every workflow written so
 * far - the legend is a single line.
 */
const Legend = ({ hasDeclaredOnly }: { hasDeclaredOnly: boolean }) => (
  <Stack direction="row" spacing={3} sx={{ alignItems: 'center' }}>
    <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
      <Box
        sx={{ width: 28, height: 2, backgroundColor: (t) => t.palette.grey[300] }}
      />
      <Typography variant="h6" color="text.secondary">
        passes data to
      </Typography>
    </Stack>
    {hasDeclaredOnly && (
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
        <Box
          sx={{
            width: 28,
            height: 0,
            borderTop: (t) => `2px dashed ${t.palette.warning.main}`,
          }}
        />
        <Typography variant="h6" color="text.secondary">
          declared with dependsOn, but no data flows - the engine will not order these
        </Typography>
      </Stack>
    )}
  </Stack>
);

/**
 * Level is the longest path from a root, so a step always sits to the right of
 * everything it reads from. Rows pack within a level in declaration order.
 *
 * Levelled on the same graph the edges are drawn from - the data one, which is
 * the graph the engine runs. `dependsOn` is added to it rather than replacing
 * it, so a declared-only edge still pushes its consumer rightwards and the
 * arrow does not point backwards.
 */
const layout = (steps: StepSpec[]): Placed[] => {
  const byId = new Map(steps.map((s) => [s.id, s]));
  const levels = new Map<string, number>();

  const upstreamOf = (step: StepSpec | undefined): string[] => {
    if (!step) return [];
    const fromData = step.inputs
      .map((input) => input.source?.stepId)
      .filter((id): id is string => Boolean(id) && id !== step.id);
    return [...new Set([...fromData, ...step.dependsOn])].filter((d) => byId.has(d));
  };

  const levelOf = (id: string, seen: Set<string>): number => {
    const cached = levels.get(id);
    if (cached !== undefined) return cached;
    // Cycles are rejected at upload, but never recurse forever.
    if (seen.has(id)) return 0;

    const deps = upstreamOf(byId.get(id));
    const level = deps.length
      ? 1 + Math.max(...deps.map((d) => levelOf(d, new Set(seen).add(id))))
      : 0;

    levels.set(id, level);
    return level;
  };

  const rowsPerLevel = new Map<number, number>();

  return steps.map((step) => {
    const level = levelOf(step.id, new Set());
    const row = rowsPerLevel.get(level) ?? 0;
    rowsPerLevel.set(level, row + 1);

    return {
      step,
      level,
      row,
      x: PAD + level * (NODE_W + COL_GAP),
      y: PAD + row * (NODE_H + ROW_GAP),
    };
  });
};

const truncate = (text: string, max: number) =>
  text.length <= max ? text : `${text.slice(0, max - 1)}…`;

export default WorkflowDag;
