# UbiLife 2026 so far against 2025

The UbiLife comparison for the booth: 2026 up to now against the same point in
2025, refreshed on a schedule. Monday 13:00 this year meets Monday 13:00 last year.

```
2025: query → decode → split → prepare-steps, prepare-surveys ─┐
2026: query → decode → split → prepare-steps, prepare-surveys ─┴→ compare → comparison.csv, comparison.png
```

Same measures and verdicts as `ubilife-compare`; see its README.

## Why it can run every 15 minutes

- **2025 is read once.** Its read says `cache: true`: the window is in the past,
  so the engine reuses it, and every 2025 step after it, from then on. The steps
  are the same as `ubilife-compare`'s, so either workflow fills the cache for the other.
- **2026 reads only what is new.** Its read has `--incremental`: each run fetches
  the rows that arrived since the last one and adds them to what it kept. Only
  steps, surveys and activity are read (`data/live-data.sql`).
- **Nothing new, nothing run.** The same rows give the same result, so everything
  after the read is reused.

On the test database: the first run takes about a minute; after that about 10
seconds, or 3 with nothing new, and under 1 MB of disk when nothing changed.

## Set it up in the portal

1. Upload the bundle. When asked about time parameters, set two to
   **window end** and keep the rest:
   - `query-2026` `--to`: read what has arrived by now
   - `compare` `--b-until`: compare up to now
2. Window start `2026-10-08T00:00:00Z`, window end `2026-10-16T00:00:00Z`.
3. Add a schedule, every 15 minutes, starting now. It stops itself at the window end.

A schedule keeps its last 24 runs (`DSP_KEEP_SCHEDULED_RUNS`).

## What a run shows

- **Day 1 is Monday**: 13 Oct 2025 and 12 Oct 2026. Sunday is workshops.
- Steps, stress, sleep, ratings and activity: from Monday up to now, in both
  years. Steps count whole hours, so today shows.
- Onboarding, WHO-5 and BFI-10: answers given by now, and by the same point
  last year. Before Monday this shows who has joined so far.
- Fewer than 5 answers in either year: "too few".
- The spans compared are in `comparison.csv` and the figure's legend.

## Notes

- Phones upload in batches, so the last hour or so of 2026 can be incomplete.
- The scripts are copies of `ubilife-compare`'s. Keep them identical: the cache
  sees any difference and runs the 2025 steps again.
