import type {
  EnvironmentSpec,
  LibraryEntry,
  PortSource,
  StepSpec,
  TaskSpec,
  WorkflowView,
} from '../api/types';

/**
 * The composer's working model.
 *
 * Composition is over library steps: a draft step names a library entry and
 * wires each of its declared inputs to an upstream output. The library owns the
 * task, the environment and the port declarations, so a draft carries only what
 * the author actually chooses - which step, what to call it, what it reads, and
 * its arguments.
 */
export interface DraftInput {
  /** Input port id, from the library step's declaration. */
  id: string;
  sourceStepId?: string;
  sourceOutputId?: string;
  fileFormat?: string | null;
  notes?: string | null;
  /**
   * A boundary source - `protocol`, `external` or `file` - carried verbatim.
   *
   * The composer only edits `step-output` wiring, but a workflow may declare
   * data from outside the pipeline, and those inputs must survive an edit
   * untouched. Dropping them is silent data loss: it would strip
   * protocol-coupling-mixed of exactly the inputs it exists to demonstrate.
   */
  boundarySource?: PortSource | null;
}

export interface DraftStep {
  /** Stable across renames, so React keys and wiring survive an id edit. */
  key: string;
  id: string;
  /** Library step id, empty for an inline step. */
  uses: string;
  name: string;
  description?: string;
  inputs: DraftInput[];
  outputs: { id: string; fileFormat?: string | null }[];
  args: string[];
  /**
   * Carried through untouched for a step that runs its own script rather than
   * a library step. Dropping it was what made workflows with inline scripts
   * un-editable - the scripts exist, so there was never a reason to refuse.
   */
  task?: TaskSpec | null;
  environmentId?: string | null;
}

export interface Draft {
  id: string;
  name: string;
  description: string;
  version: string;
  tags: string[];
  steps: DraftStep[];
  /**
   * Environments declared by the workflow, carried through so an inline step
   * keeps the environment it was authored against.
   */
  environments: Record<string, EnvironmentSpec>;
}

export const emptyDraft = (): Draft => ({
  id: 'my-workflow',
  name: 'My workflow',
  description: '',
  version: '1.0',
  tags: [],
  steps: [],
  environments: {},
});

let counter = 0;
const nextKey = () => `k${++counter}`;

/** Unique step id derived from the library id's last segment. */
const uniqueStepId = (base: string, taken: Set<string>): string => {
  if (!taken.has(base)) return base;
  let n = 2;
  while (taken.has(`${base}-${n}`)) n += 1;
  return `${base}-${n}`;
};

/** Adds a library step to the draft, pre-wiring what it can. */
export const addLibraryStep = (draft: Draft, entry: LibraryEntry): Draft => {
  const spec = entry.definition.steps[0];
  const base = entry.stepId.split('.').pop() ?? entry.stepId;
  const id = uniqueStepId(base, new Set(draft.steps.map((s) => s.id)));

  const step: DraftStep = {
    key: nextKey(),
    id,
    uses: entry.stepId,
    name: entry.definition.metadata.name,
    inputs: (spec?.inputs ?? []).map((p) => ({
      id: p.id,
      fileFormat: p.descriptor?.fileFormat,
    })),
    outputs: (spec?.outputs ?? []).map((p) => ({
      id: p.id,
      fileFormat: p.descriptor?.fileFormat,
    })),
    args: [],
  };

  return { ...draft, steps: [...draft.steps, autoWire(step, draft.steps)] };
};

/**
 * Wires each input to the nearest upstream output of a matching format.
 *
 * A guess, not a decision - the author can rewire anything. Matching on format
 * is weak (two csv outputs are indistinguishable here) but it gets the common
 * linear pipeline right, and W5-v5's type checking is where this gets done
 * properly.
 */
const autoWire = (step: DraftStep, upstream: DraftStep[]): DraftStep => ({
  ...step,
  inputs: step.inputs.map((input) => {
    for (let i = upstream.length - 1; i >= 0; i -= 1) {
      const candidate = upstream[i].outputs.find(
        (o) => !input.fileFormat || o.fileFormat === input.fileFormat,
      );
      if (candidate) {
        return {
          ...input,
          sourceStepId: upstream[i].id,
          sourceOutputId: candidate.id,
        };
      }
    }
    return input;
  }),
});

/** Every output visible to a step, i.e. everything declared before it. */
export const upstreamOutputs = (
  draft: Draft,
  beforeKey: string,
): { stepId: string; outputId: string; fileFormat?: string | null }[] => {
  const cut = draft.steps.findIndex((s) => s.key === beforeKey);
  return draft.steps
    .slice(0, cut === -1 ? draft.steps.length : cut)
    .flatMap((s) =>
      s.outputs.map((o) => ({
        stepId: s.id,
        outputId: o.id,
        fileFormat: o.fileFormat,
      })),
    );
};

/**
 * Control edges are derived, not authored: a step depends on whatever it reads
 * from. Keeping `dependsOn` a function of the wiring means the two graphs
 * cannot drift apart in a composed workflow - unlike a hand-written one, where
 * they legitimately differ.
 */
const dependsOnOf = (step: DraftStep): string[] =>
  [...new Set(step.inputs.map((i) => i.sourceStepId).filter(Boolean))] as string[];

const quote = (s: string) => `"${s.replace(/"/g, '\\"')}"`;

/** Renders the draft as workflow YAML. */
export const draftToYaml = (draft: Draft): string => {
  const lines: string[] = [];

  lines.push('schemaVersion: "1.0"');
  lines.push('metadata:');
  lines.push(`  id: ${quote(draft.id)}`);
  lines.push(`  name: ${quote(draft.name)}`);
  if (draft.description) {
    lines.push(`  description: ${quote(draft.description)}`);
  }
  lines.push(`  version: ${quote(draft.version)}`);
  if (draft.tags.length) {
    lines.push('  tags:');
    draft.tags.forEach((t) => lines.push(`    - ${quote(t)}`));
  }

  // Only emitted when a step actually uses one, so a purely library-based
  // workflow stays as short as it was.
  const usedEnvironments = Object.entries(draft.environments).filter(([id]) =>
    draft.steps.some((s) => s.environmentId === id),
  );

  if (usedEnvironments.length) {
    lines.push('');
    lines.push('environments:');
    usedEnvironments.forEach(([id, env]) => {
      lines.push(`  ${id}:`);
      lines.push(`    name: ${quote(env.name)}`);
      lines.push(`    kind: ${quote(env.kind)}`);
      lines.push('    spec:');
      if (env.spec?.pythonVersion?.length) {
        lines.push('      pythonVersion:');
        env.spec.pythonVersion.forEach((v) => lines.push(`        - ${quote(v)}`));
      }
      lines.push('      dependencies:');
      (env.spec?.dependencies ?? []).forEach((d) =>
        lines.push(`        - ${quote(d)}`),
      );
    });
  }

  lines.push('');
  lines.push('steps:');

  draft.steps.forEach((step) => {
    lines.push(`  - id: ${quote(step.id)}`);

    if (step.uses) {
      lines.push(`    uses: ${quote(step.uses)}`);
    } else {
      lines.push('    metadata:');
      lines.push(`      name: ${quote(step.name)}`);
      if (step.description) {
        lines.push(`      description: ${quote(step.description)}`);
      }
      lines.push('      version: "1.0"');
    }

    if (step.environmentId) {
      lines.push(`    environmentId: ${quote(step.environmentId)}`);
    }

    const deps = dependsOnOf(step);
    if (deps.length) {
      lines.push('    dependsOn:');
      deps.forEach((d) => lines.push(`      - ${quote(d)}`));
    } else {
      lines.push('    dependsOn: []');
    }

    // An inline step brings its own task. Emitted verbatim rather than
    // reconstructed, so nothing the composer does not model gets lost.
    const task = step.task;
    if (!step.uses && task) {
      lines.push('    task:');
      lines.push(`      type: ${quote(task.type)}`);
      if (task.id) lines.push(`      id: ${quote(task.id)}`);
      if (task.name) lines.push(`      name: ${quote(task.name)}`);
      if (task.executable) lines.push(`      executable: ${quote(task.executable)}`);
      if (task.entryPoint) {
        lines.push('      entryPoint:');
        if (task.entryPoint.type) {
          lines.push(`        type: ${quote(task.entryPoint.type)}`);
        }
        if (task.entryPoint.scriptPath) {
          lines.push(`        scriptPath: ${quote(task.entryPoint.scriptPath)}`);
        }
        if (task.entryPoint.module) {
          // Core's field is moduleName; the view calls it module.
          lines.push(`        moduleName: ${quote(task.entryPoint.module)}`);
        }
      }
      if (task.args.length) {
        lines.push('      args:');
        task.args.forEach((a) => lines.push(`        - ${quote(a)}`));
      }
    }

    // Wired inputs, plus any boundary input carried through from the original.
    const emitted = step.inputs.filter(
      (i) => (i.sourceStepId && i.sourceOutputId) || i.boundarySource,
    );

    if (emitted.length) {
      lines.push('    inputs:');
      emitted.forEach((input) => {
        lines.push(`      - id: ${quote(input.id)}`);

        if (input.fileFormat) {
          lines.push('        descriptor:');
          lines.push(`          fileFormat: ${quote(input.fileFormat)}`);
          lines.push('          encoding: "utf-8"');
          if (input.notes) lines.push(`          notes: ${quote(input.notes)}`);
        }

        const boundary = input.boundarySource;
        if (boundary) {
          lines.push('        source:');
          lines.push(`          type: ${quote(boundary.type)}`);
          if (boundary.protocol) {
            lines.push('          protocol:');
            lines.push(`            id: ${quote(boundary.protocol.id)}`);
            if (boundary.protocol.name) {
              lines.push(`            name: ${quote(boundary.protocol.name)}`);
            }
            if (boundary.protocol.version != null) {
              lines.push(`            version: ${boundary.protocol.version}`);
            }
          }
          if (boundary.dataType) {
            lines.push(`          dataType: ${quote(boundary.dataType)}`);
          }
          if (boundary.uri) lines.push(`          uri: ${quote(boundary.uri)}`);
          if (boundary.citation) {
            lines.push(`          citation: ${quote(boundary.citation)}`);
          }
          if (boundary.path) lines.push(`          path: ${quote(boundary.path)}`);
        } else {
          lines.push('        source:');
          lines.push('          type: "step-output"');
          lines.push(`          stepId: ${quote(input.sourceStepId!)}`);
          lines.push(`          outputId: ${quote(input.sourceOutputId!)}`);
        }
      });
    }

    if (step.args.length) {
      lines.push('    args:');
      step.args.forEach((a) => lines.push(`      - ${quote(a)}`));
    }

    // A library step's outputs are declared by the library. An inline step
    // declares its own, and omitting them breaks every downstream input that
    // reads from it.
    if (!step.uses && step.outputs.length) {
      lines.push('    outputs:');
      step.outputs.forEach((output) => {
        lines.push(`      - id: ${quote(output.id)}`);
        if (output.fileFormat) {
          lines.push('        descriptor:');
          lines.push(`          fileFormat: ${quote(output.fileFormat)}`);
          lines.push('          encoding: "utf-8"');
        }
      });
    }

    lines.push('');
  });

  return lines.join('\n').trimEnd() + '\n';
};

/**
 * Rebuilds a draft from a parsed workflow, so a hand edit in the YAML pane
 * flows back into the composer.
 *
 * Output declarations come from the library rather than the file, because a
 * `uses:` step does not restate them.
 */
export const draftFromWorkflow = (
  file: WorkflowView,
  library: LibraryEntry[],
): Draft => {
  const byId = new Map(library.map((e) => [e.stepId, e]));

  return {
    id: file.metadata.id,
    name: file.metadata.name,
    description: file.metadata.description ?? '',
    version: file.metadata.version,
    tags: file.metadata.tags ?? [],
    environments: file.environments ?? {},
    steps: file.steps.map((step: StepSpec) => {
      const entry = step.uses ? byId.get(step.uses) : undefined;
      const spec = entry?.definition.steps[0];

      return {
        key: nextKey(),
        id: step.id,
        uses: step.uses ?? '',
        name: entry?.definition.metadata.name ?? step.metadata?.name ?? step.id,
        description: step.metadata?.description ?? undefined,
        inputs: (spec?.inputs ?? step.inputs).map((p) => {
          const declared = step.inputs.find((i) => i.id === p.id);
          const source = declared?.source;
          const isStepOutput = source?.type === 'step-output';

          return {
            id: p.id,
            sourceStepId: isStepOutput ? (source?.stepId ?? undefined) : undefined,
            sourceOutputId: isStepOutput ? (source?.outputId ?? undefined) : undefined,
            fileFormat: p.descriptor?.fileFormat,
            notes: p.descriptor?.notes,
            // Anything that is not upstream wiring is kept as-is.
            boundarySource: source && !isStepOutput ? source : null,
          };
        }),
        outputs: (spec?.outputs ?? step.outputs).map((p) => ({
          id: p.id,
          fileFormat: p.descriptor?.fileFormat,
        })),
        // A library step takes its args from the step body; an inline one from
        // its task. Reading only one of the two silently dropped half of them.
        args: (step.args?.length ? step.args : step.task?.args) ?? [],
        task: step.uses ? null : step.task,
        environmentId: step.environmentId,
      };
    }),
  };
};

/** Draft steps as StepSpecs, so the DAG view can render a draft unchanged. */
export const draftToStepSpecs = (draft: Draft): StepSpec[] =>
  draft.steps.map((step) => ({
    id: step.id,
    metadata: { name: step.name, description: step.description },
    uses: step.uses || undefined,
    task: step.task,
    environmentId: step.environmentId,
    dependsOn: dependsOnOf(step),
    args: step.args,
    inputs: step.inputs
      .filter((i) => i.sourceStepId || i.boundarySource)
      .map((i) => ({
        id: i.id,
        descriptor: { fileFormat: i.fileFormat, notes: i.notes },
        source: i.boundarySource ?? {
          type: 'step-output',
          stepId: i.sourceStepId,
          outputId: i.sourceOutputId,
        },
      })),
    outputs: step.outputs.map((o) => ({
      id: o.id,
      descriptor: { fileFormat: o.fileFormat },
    })),
  }));
