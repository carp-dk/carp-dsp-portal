# UbiLife 2025 replication (Zenodo)

Reproduces the analysis of the UbiLife 2025 study
([dtu-digital-health/UbiLife](https://github.com/dtu-digital-health/UbiLife))
from its published export on Zenodo
([19220274](https://doi.org/10.5281/zenodo.19220274), CC BY 4.0).

The same analysis as `../ubilife/`, from the public export instead of the
database. Drop this folder, or a zip of it, on the Upload page. A run takes about 90
seconds once its environments are built.

## Steps

| Step | Does | Notebook |
|---|---|---|
| fetch | Downloads the export (library step `core.io.fetch-zenodo`) | - |
| read | `data-streams.json` to the measurement and provenance tables, the same two the SQL decoder writes | - |
| split | One table per stream, with participant and timestamp | - |
| prepare-steps | Conference week, cumulative counter to per-interval steps | 02, sections 1-2 |
| prepare-surveys | Submitted surveys to six tables | 02, section 3 |
| figures-overview, -sensors, -surveys | The 14 figures | 01 |
| findings | Every number the analysis reports, against the published value | 01 and 02 |

`findings.csv` should show 54 of 54 matching.

## Where the code comes from

`surveys.py` and `plots.py` are the UbiLife repo's own (MIT), with the changes
listed at the top of each file. The rest is new: reading the export, splitting
streams, and the step entry points.

Changes that alter output:

- The BFI-10 boxplot passed `labels=`, which matplotlib 3.11 removed. Last
  year's code no longer runs on a fresh environment.
- Survey answers are labelled by value, since their text is in the
  participant's language.
- The session-ratings figure ordered by labels no answer carries, so "Very
  Boring" and "Amazing" fell out. It now shows all five; "I did not attend" is
  left out, as it is not a rating.

None of the 54 checked numbers changes.

## Notes

- Times are UTC, as in the notebooks.
- `findings` reads `data/expected-2025.csv`; each expected value names its source.
