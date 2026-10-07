"""Last year's tables for ubilife-compare-live, made once.

The live comparison reads only 2026; 2025 comes from these files in the bundle.
They are the 2025 outputs of a ubilife-compare run, made smaller:

- steps: per participant per hour, from the per-interval table
- activity: only the conference window
- surveys: as they are

    python make_baseline.py --run <run folder> --out ../../ubilife-compare-live/data/2025
"""

import argparse
import shutil
from pathlib import Path

import pandas as pd

SURVEYS = ["onboarding", "stress", "sleep", "who5", "bfi10", "events"]


def find(run: Path, step: str, name: str) -> Path:
    matches = sorted(run.glob(f"steps/*_{step}/outputs/{name}"))
    if not matches:
        raise SystemExit(f"No {name} from a step '*_{step}' in {run}")
    return matches[0]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--run", required=True, type=Path, help="a ubilife-compare run folder (the one holding steps/)")
    ap.add_argument("--out", required=True, type=Path, help="where the tables go")
    ap.add_argument("--from", dest="start", default="2025-10-13", help="conference window start (ISO date)")
    ap.add_argument("--to", dest="end", default="2025-10-17", help="conference window end, exclusive")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    steps = pd.read_csv(find(args.run, "prepare_step_counts_2025", "intervals.csv"))
    steps["timestamp"] = pd.to_datetime(steps["timestamp"]).dt.floor("h")
    hourly = steps.groupby(["participant_id", "timestamp"], as_index=False)["steps_delta"].sum()
    hourly.to_csv(args.out / "steps.csv", index=False)

    activity = pd.read_csv(find(args.run, "split_2025", "activity.csv"),
                           usecols=["participant_id", "timestamp", "type"], dtype={"type": str})
    stamp = pd.to_datetime(pd.to_numeric(activity["timestamp"], errors="coerce"), unit="us")
    inside = (stamp >= pd.Timestamp(args.start)) & (stamp < pd.Timestamp(args.end))
    activity[inside].to_csv(args.out / "activity.csv", index=False)

    for name in SURVEYS:
        shutil.copy(find(args.run, "parse_surveys_2025", f"{name}.csv"), args.out / f"{name}.csv")

    print(f"{len(hourly):,} participant-hours of steps, {inside.sum():,} activity records, "
          f"{len(SURVEYS)} survey tables in {args.out}")


if __name__ == "__main__":
    main()
