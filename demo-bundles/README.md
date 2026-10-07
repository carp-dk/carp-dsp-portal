# Demo bundles

Three copies of the mobgap pipeline, one SQL read, the UbiLife analysis and its year-on-year comparison, for the Upload page. Test tools
for the UbiLife workflow are in `tools/ubilife/`. Drop one on
`/upload` — as a zip, or unzipped as a folder.

| Bundle | What it shows |
|---|---|
| `mobgap-bundle.zip` | The good one. Validates clean, runs all 8 steps, produces both plots |
| `mobgap-bundle-broken.zip` | Two scripts deleted and one left behind renamed. Two `MISSING_SCRIPT` errors and an `UNREFERENCED_FILE` warning — the checks only the bundle path can make |
| `mobgap-bundle-typemismatch.zip` | Every script present, but a step declares an input as JSON that its producer writes as CSV. `DEPENDENCY_TYPE_MISMATCH`, straight from the planner |
| `ubilife/` | The UbiLife analysis, read from the CARP database: tables, the 14 figures and its findings. One workflow for every year; as written it reads 2026. See its README |
| `ubilife-compare/` | Both years through the UbiLife steps, then each measure marked the same or shifted, by effect size. `comparison.csv` and one figure. See its README |
| `ubilife-compare-live/` | The comparison for the booth: 2026 so far against the same point in 2025, every 15 minutes or hourly. Reads only 2026; last year's tables are made once (not in git). See its README |
| `ubilife-zenodo/` | The same analysis from the published 2025 export, with `findings.csv` checking 54 of last year's numbers |
| `sql-study-data/` | One study read straight from the CARP database by `core.io.query-sql` and decoded by `core.io.decode-carp-data-streams`. Replace `STUDY_ID` in `workflow.yaml`, and put `CARP_DSP_SQL_URL`, `CARP_DSP_SQL_USER` and `CARP_DSP_SQL_PASSWORD` in `.env` beside `docker-compose.yml` |

The two failures are worth showing together: the first is the portal's, about
what the archive carries, and the second is the engine's, about whether the
workflow makes sense. Same page, same report, different source.

Rebuild them from a carp-dsp checkout — `workflow.yaml` at the root plus
`scripts/mobgap/*.py`. See `docs/bundle-layout.md`.
