# UbiLife 2025 against 2026

Reads both years from the CARP database with the UbiLife workflow's own steps,
then asks of each measure: has it stayed the same, or shifted?

```
query → decode → split → prepare-steps, prepare-surveys    (once per year)
                                         ↓
                                      compare → comparison.csv, comparison.png
```

## What it compares

Steps per participant-day, stress, sleep quality and duration, WHO-5, the five
BFI-10 traits, age, gender, academic role, prior UbiComps, session and keynote
ratings, and activity type.

## How it decides

The cohorts are different people, and small, so the verdict rests on effect
size, not on the p-value alone.

| Kind | Test | Effect size | "same" when |
|---|---|---|---|
| Numbers, ordinal answers | Mann-Whitney U | Cliff's delta | \|d\| < 0.147 |
| Categories | chi-square | Cramér's V | V < 0.1 |

Anything larger is "shifted (small, medium or large)". Fewer than 5 answers in
either year: "too few". Answers are compared by value, never by their text,
which is in the participant's language. "I did not attend" is not a rating.

Daily measures (steps, stress, sleep, ratings, activity) are compared within
each year's conference window; answers given once (onboarding, WHO-5, BFI-10)
are those given before it ends.

## Arguments

| | 2025 | 2026 |
|---|---|---|
| Study id | `b3607581-…` | `ba1834de-…` |
| Arrival window | 2025-10-01 → 2026-01-16 | 2026-10-01 → 2026-11-01 |
| Conference days, Monday to Thursday (`--a-from/--a-to`, `--b-from/--b-to`) | 2025-10-13 → 2025-10-17 | 2026-10-12 → 2026-10-16 |

## Notes

- Needs `CARP_DSP_SQL_*` and `JAVA_TOOL_OPTIONS=-Xmx4g`, as the UbiLife workflow does.
- The scripts other than `compare.py` are copies of the UbiLife bundle's. Keep them in step.
- To check it locally, see `../tools/ubilife/README.md`.
- `ubilife-compare-live` is the same comparison on 2026 so far, for the booth.
- Sunday is the workshop day, so both years start on Monday.
