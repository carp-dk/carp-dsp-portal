# carp-dsp-portal

The CARP-DSP demo platform: a React SPA and a Ktor server that runs workflows for
real, through `carp-dsp`.

Standalone on purpose. Components move into CARP Web Services later — see
`CARP-DSP — UbiComp Demo & Infrastructure` in the vault.

## Before anything

Three sibling checkouts, because the build is a composite:

```
Code/
├── carp-dsp-portal      ← you are here
├── carp-dsp
└── carp.core-kotlin     ← branch feature/core-analytics
```

Override with `-PdspRepo=<path>` / `-PcarpCoreRepo=<path>`. The build stops with a
message naming both if either is missing.

Also needed: **pnpm** (the SPA), **pixi** on `PATH` (workflow environments — every
step fails at setup without it), and a JDK 21.

## Run it

```bash
./gradlew :server:run                      # builds the SPA, serves on :8080
./gradlew :server:run -PskipWebBuild=true  # reuse the built SPA — faster loop
```

Open http://localhost:8080. Upload `wf-activity-summary-offline.yaml` from
`carp-dsp/carp.dsp.demo/src/jvmMain/resources/workflows/` and execute — that path
runs for real and takes about 40 seconds.

Working on the front end only? `cd web && pnpm dev` gives Vite's hot reload, with
the Kotlin side running separately.

## Build and check

```bash
./gradlew :server:compileKotlin -PskipWebBuild=true   # Kotlin only, quickest
./gradlew :server:test -PskipWebBuild=true            # routing tests
./gradlew :server:installDist -PskipWebBuild=true     # runnable dist
./gradlew :server:build                               # everything, SPA included
```

**Compiling proves less than you would like here.** Three separate breaks so far
were green at compile time and fatal at request time, all from the composite
raising a transitive version — kaml, and kotlinx-datetime via Ktor. Start the
server and run something before believing a dependency change.

## Environment

| Variable | Default | What it does |
|---|---|---|
| `DSP_PORT` | `8080` | Listen port |
| `DSP_HOST` | `0.0.0.0` | Bind address |
| `DSP_REPO` | *(build-time sync)* | A carp-dsp checkout read live, instead of the copy baked into the jar |
| `DSP_MODE` | `real` | `real` runs workflows through the engine, `simulated` replays recorded results. Switchable live from the top bar |
| `DSP_RUNS` | `data/runs` | Where real runs write their workspace and artefacts |
| `DSP_RUN_CONCURRENCY` | `1` | Workflows at once. One workflow can occupy the machine |
| `DSP_STATE` | `data/portal-state.json` | Session state: uploads, composed workflows, run history |

## Docker

```bash
docker compose build
docker compose up
```

Run it from this repo. The build context is the **parent** directory — the one
holding `carp-dsp-portal`, `carp-dsp`, `carp.core-kotlin` and
`health-workflow-interfaces` — because the server is built against all four
through the composite build. `Dockerfile.dockerignore` excludes everything else
in that directory, so a neighbouring repo never enters the context.
A hand-written `docker build .` from here will not work; use compose.

The image is self-contained: the step library, demo workflows, scripts and
sample data are baked in, and it carries pixi, so it runs workflows for real
with nothing mounted. Uncomment the `../carp-dsp:/repo:ro` volume and set
`DSP_REPO=/repo` to read them live from a checkout instead — edit a step,
restart, see it.

`HOME` points into the `/state` volume, so solved environments
(`$HOME/.carp-dsp/envs`) and pixi's package cache survive a restart. The first
mobgap run pays about a minute and 850 MB for its environment; later ones start
straight away. Keep the volume between demo rehearsals.

## What is real and what is not

Real: uploading a workflow, validating it, running it — stored or uploaded, on
its own or as a zipped package — and its results: per-step progress, artefacts,
downloads. Runs are written to `DSP_RUNS` as they go, so they survive a restart.

Still mocked, in `server/.../mock/`: the step library and demo catalogue, the
data page, schedules.

## Real and simulated

The switch in the top bar decides how the next run starts. **Real** hands the
workflow to the engine. **Simulated** replays `RunSimulator`'s recorded results
and executes nothing — the way back when a machine, a network, or a pixi solve
lets a live demo down.

The server holds the mode, so a reload does not undo the choice:

```bash
curl -s localhost:8080/api/mode
curl -s -X POST localhost:8080/api/mode -H 'Content-Type: application/json' -d '{"mode":"simulated"}'
```

Portal-only, like `/bundles/*`: CARP WS runs workflows one way, so this goes
when the mock shell does.

A real run is handed the repo's `scripts/` and `data/` trees along with the
workflow. A command step names its script as a plain argument, so nothing in the
workflow declares it and only the caller can stage it.

`docs/api-contract.md` is the reference for every request and response shape.
Read it before changing anything under `api/`.

## Health

```bash
curl -s localhost:8080/health
```

Reports where content came from, how many library steps and demo workflows
loaded, and any that failed to parse.
