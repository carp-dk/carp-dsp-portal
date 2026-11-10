"""Step counts for the conference window (notebook 02, sections 1-2).

The phone reports a cumulative pedometer counter. Each participant's series is
sorted and differenced; a negative difference (a counter reset) becomes zero. A
reading sent twice differences to zero, so it changes no total. Writes the per-interval table and one row per participant
per day.
"""

import argparse

import pandas as pd

from common import in_window, load_stream, numeric, window


def deltas(steps):
    steps = steps.sort_values(["participant_id", "timestamp"], kind="stable")
    steps["steps_delta"] = (
        steps.groupby("participant_id")["steps_cumulative"].diff().clip(lower=0).fillna(0).astype(int)
    )
    return steps


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--steps", required=True, help="step stream table (stepcount or stepevent)")
    ap.add_argument("--from", dest="start", required=True, help="window start, inclusive (ISO)")
    ap.add_argument("--to", dest="end", required=True, help="window end, exclusive (ISO)")
    ap.add_argument("--intervals", required=True, help="per-interval steps (csv)")
    ap.add_argument("--daily", required=True, help="steps per participant per day (csv)")
    args = ap.parse_args()

    start, end = window(args.start, args.end)
    steps = in_window(load_stream(args.steps, ["participant_id", "timestamp", "steps"]), start, end)
    steps["steps_cumulative"] = numeric(steps.pop("steps"))
    steps = deltas(steps)
    steps.to_csv(args.intervals, index=False)

    steps["date"] = steps["timestamp"].dt.date
    daily = steps.groupby(["participant_id", "date"], as_index=False).agg(
        steps=("steps_delta", "sum"), intervals=("steps_delta", "size"))
    daily.to_csv(args.daily, index=False)
    print(f"{len(steps):,} intervals, {steps['participant_id'].nunique()} participants, "
          f"{len(daily)} participant-days")


if __name__ == "__main__":
    main()
