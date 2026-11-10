# UbiLife

The UbiLife analysis
([dtu-digital-health/UbiLife](https://github.com/dtu-digital-health/UbiLife)),
read from the CARP database. One workflow for every year: the study id and the
dates are its arguments. As written it reads UbiLife 2026.

The same analysis from the published 2025 export, checked against last year's
numbers, is in `../ubilife-zenodo/`.

## Arguments

| | 2026 (as written) | 2025 |
|---|---|---|
| `query`: `studyId` | `ba1834de-8caf-4e0e-9ec3-8db05285777f` | `b3607581-56c6-4652-a4f6-8e86e5a4e7e5` |
| `query`: `--from` / `--to` (arrival) | 1 Oct - 1 Nov 2026 | 1 Oct 2025 - 16 Jan 2026 |
| analysis window (`--from` / `--to`, `--window-*`) | 11 - 16 Oct 2026 | 13 - 17 Oct 2025 |
| heartbeat coverage (`--coverage-*`) | 11 Oct 00:00 - 15 Oct 23:59 | 13 Oct 08:00 - 17 Oct 23:59 |

Dates show up on the workflow's Time tab, so a schedule can move them.

## Before it runs

- The connection on the Settings page: `CARP_DSP_SQL_URL`, `_USER`, `_PASSWORD`.
- `JAVA_TOOL_OPTIONS` = `-Xmx4g` on the Settings page. The decoder holds the
  study in memory, about 3 GB for a million measurements.

Without production access, test against a local database: `../tools/ubilife/`.

## Steps

query → decode → split → prepare steps and surveys → three figure steps →
findings. From split onwards it is the same code as the Zenodo bundle.

- `split` reads 2025's `stepcount` and 2026's `stepevent` as the same steps.
- Survey answers are grouped by value and labelled in English. Their text is in
  the participant's language.
- Session ratings leave out "I did not attend", and show all five ratings. Last
  year's figure dropped "Very Boring" and "Amazing".
- Health data is not read: 2026 collects 30 days back, and the analysis does not
  use it.
