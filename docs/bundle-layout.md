# Workflow bundles

A bundle is a workflow plus the files its steps reach by relative path. It is
what the Upload page takes, as a folder drop or a zip.

## Layout

```
my-analysis/
  workflow.yaml          the workflow - one per bundle
  scripts/               anything a step names as an argument
    clean.py
  data/                  files a `file` input points at
    readings.csv
  steps.lock             written by resolution; do not hand-edit
```

Only `workflow.yaml` is required. A workflow built entirely from `uses:`
references needs nothing else — the step library supplies the implementations.

**One workflow per bundle.** Resolution writes `steps.lock` beside the workflow,
so two workflows in one directory would write over each other's lock. The
shallowest `.yaml` or `.yml` is taken as the workflow; a second one deeper in the
tree is left alone.

A single wrapping directory is stripped. Zipping a folder normally nests
everything under it, and the paths inside the workflow do not account for that,
so `my-analysis/scripts/clean.py` and `scripts/clean.py` both arrive as
`scripts/clean.py`.

## How paths resolve

Every step in a run shares one execution root, and commands run with that root as
their working directory. A step naming `scripts/clean.py` gets the bundle's
`scripts/clean.py`, whichever step it belongs to.

A run is also handed the repo's `scripts/` and `data/` trees, so a workflow that
reaches for a demo script still works without carrying a copy. The bundle wins
where both have the same path.

## Saving a bundle to the study

Saving keeps the files beside the workflow, not only `workflow.yaml`, so running
it later from Workflows stages the same files an upload would. They live in a
`bundles/<workflow id>/` directory beside the state file (`DSP_BUNDLES`
overrides it). Saving again replaces them; saving a plain definition under the
same id, or deleting the workflow, drops them.

## What is checked before it runs

`POST /api/bundles/validate` resolves and plans the workflow without executing
it. Uploading answers three questions the planner cannot:

- a script a step names that the bundle does not carry — an error
- a file in the bundle no step references — a warning, because READMEs and
  helper modules imported at runtime are legitimate
- a `file` input naming data this study does not hold — a warning, fixed by
  adding it under Data

Everything else — the graph, resolution, environments, types, protocol coupling —
comes from the engine that will run it. See `server/.../run/EngineValidator.kt`.

`POST /api/bundles/execute` does the same and then runs it. It takes either
shape: a zip as the raw body, or the files as multipart with each part named by
its path within the bundle, which is how a dropped folder goes up. Both are
portal-only — neither is something the analytics RPC surface carries. A lone
workflow file with nothing to stage still goes through
`ExecuteWorkflowFromDefinition`.

## Ready-made bundles

`demo-bundles/` holds three: one that works and two built to fail, one on a
bundle check and one on a planner check. See the README there.
