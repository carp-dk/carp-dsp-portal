# UbiLife data so far

How the data piles up over the conference week, for the booth: 2026 up to now
against 2025 over the same week, refreshed on a schedule.

```
2025: query → decode → split ─┐
2026: query → decode → split ─┴→ accumulate → totals.csv, accumulation-figure.png
```

## What it shows

One small chart per measure, with a running total hour by hour:

- **Participants**: who has sent anything yet.
- **Records**: step and activity records.
- **Survey answers**: submitted surveys in all, and by kind (onboarding, stress,
  sleep, WHO-5, BFI-10, session and keynote ratings).

2025 is dashed over the whole week; 2026 is solid up to now, marked by a grey
line. Each chart's title gives this year's total and last year's at the same point.

The week runs Sunday (workshops) to Thursday: 12-16 Oct 2025, 11-15 Oct 2026.
Monday meets Monday. Data from before Sunday counts from the start.

## Set it up in the portal

1. Upload the bundle. When asked about time parameters, set two to
   **window end** and keep the rest:
   - `query-2026` `--to`: read what has arrived by now
   - `accumulate` `--b-until`: draw up to now
2. Window start `2026-10-08T00:00:00Z`, window end `2026-10-16T00:00:00Z`.
3. Add a schedule, every 15 minutes. Start it a few minutes after the live
   comparison's, so the two do not read at the same moment.

## Shares its reads with the live comparison

- **2025** is read once (`cache: true`), and its decode and split are the same
  steps as `ubilife-compare-live`'s, so whichever runs first fills the cache for both.
- **2026** uses the same query, so both workflows keep one store of what has
  been read: each run fetches only what arrived since either last read.

The scripts in `scripts/ubilife/` are copies of `ubilife-compare-live`'s. Keep
them identical, or the cache runs the 2025 steps again.
