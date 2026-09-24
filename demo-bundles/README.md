# Demo bundles

Three copies of the mobgap pipeline, and one SQL read, for the Upload page. Drop one on
`/upload` — as a zip, or unzipped as a folder.

| Bundle | What it shows |
|---|---|
| `mobgap-bundle.zip` | The good one. Validates clean, runs all 8 steps, produces both plots |
| `mobgap-bundle-broken.zip` | Two scripts deleted and one left behind renamed. Two `MISSING_SCRIPT` errors and an `UNREFERENCED_FILE` warning — the checks only the bundle path can make |
| `mobgap-bundle-typemismatch.zip` | Every script present, but a step declares an input as JSON that its producer writes as CSV. `DEPENDENCY_TYPE_MISMATCH`, straight from the planner |
| `sql-study-data/` | One study read straight from the CARP database by `core.io.query-sql` and decoded by `core.io.decode-carp-data-streams`. Replace `STUDY_ID` in `workflow.yaml`, and put `CARP_DSP_SQL_URL`, `CARP_DSP_SQL_USER` and `CARP_DSP_SQL_PASSWORD` in `.env` beside `docker-compose.yml` |

The two failures are worth showing together: the first is the portal's, about
what the archive carries, and the second is the engine's, about whether the
workflow makes sense. Same page, same report, different source.

Rebuild them from a carp-dsp checkout — `workflow.yaml` at the root plus
`scripts/mobgap/*.py`. See `docs/bundle-layout.md`.
