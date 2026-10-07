# UbiLife 2026 so far against 2025

The UbiLife comparison for the booth: 2026 up to now against the same point in
2025, refreshed on a schedule. Monday 13:00 this year meets Monday 13:00 last year.

```
query → decode → split → prepare-steps, prepare-surveys → compare → comparison.csv, comparison.png
(2026 only)                                                  ↑
                                              data/2025/ (last year, in the bundle)
```

Same measures and verdicts as `ubilife-compare`; see its README.

## Why it is quick

- It reads only 2026, and only steps, surveys and activity (`data/live-data.sql`).
- Last year is a set of small tables in `data/2025/`, made once.
- A run takes about 20 seconds and 100-200 MB, growing over the week.

## Set it up in the portal

1. Upload the bundle. When asked about time parameters, set two to
   **window end** and keep the rest:
   - `query-2026` `--to`: read what has arrived by now
   - `compare` `--b-until`: compare up to now
2. Window start `2026-10-08T00:00:00Z`, window end `2026-10-16T00:00:00Z`.
3. Add a schedule, every 15 minutes or hourly, starting now. It stops itself
   at the window end.

## What a run shows

- **Day 1 is Monday**: 13 Oct 2025 and 12 Oct 2026. Sunday is workshops.
- Steps, stress, sleep, ratings and activity: from Monday up to now, in both
  years. Steps count whole hours, so today shows.
- Onboarding, WHO-5 and BFI-10: answers given by now, and by the same point
  last year. Before Monday this shows who has joined so far.
- Fewer than 5 answers in either year: "too few".
- The spans compared are in `comparison.csv` and the figure's legend.

## Last year's tables

`data/2025/` is not in git. Make it from any finished `ubilife-compare` run:

```sh
cd demo-bundles/tools/ubilife
python make_baseline.py --run <run folder> --out ../../ubilife-compare-live/data/2025
```

## Notes

- Phones upload in batches, so the last hour or so of 2026 can be incomplete;
  last year's tables are complete.
- The scripts are copies of the UbiLife bundle's, and `compare.py` is the same
  as `ubilife-compare`'s. Keep them in step.
