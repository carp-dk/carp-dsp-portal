"""The numbers the UbiLife analysis reports, as one table (notebooks 01 and 02).

Each row is a finding: a metric, its value, and - when an expected table is
given - the value expected and whether they match. Run on the 2025 export with
data/expected-2025.csv, it shows whether the workflow reproduces the published
analysis.
"""

import argparse

import pandas as pd

import explore
from common import in_window, load_stream, window

SENSORS = ["activity", "airquality", "ambientlight", "batterystate", "heartbeat",
           "location", "screenevent", "stepcount", "weather"]
SURVEY_TABLES = ["onboarding", "stress", "sleep", "who5", "bfi10", "events"]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--provenance", required=True)
    ap.add_argument("--measurements", required=True)
    ap.add_argument("--steps", required=True, help="step stream table")
    ap.add_argument("--intervals", required=True, help="per-interval steps from prepare_steps.py")
    ap.add_argument("--survey", required=True, help="survey stream table")
    ap.add_argument("--heartbeat", required=True)
    ap.add_argument("--deviceinformation", required=True)
    for name in SURVEY_TABLES:
        ap.add_argument(f"--{name}", required=True, help=f"{name} table from prepare_surveys.py")
    ap.add_argument("--from", dest="start", required=True)
    ap.add_argument("--to", dest="end", required=True)
    ap.add_argument("--coverage-from", required=True)
    ap.add_argument("--coverage-to", required=True)
    ap.add_argument("--expected", help="metric,expected table to check against")
    ap.add_argument("--output", required=True)
    args = ap.parse_args()

    found = {}

    # Everything read (notebook 01, section 1).
    provenance = pd.read_csv(args.provenance, dtype=str, keep_default_na=False,
                             usecols=["row_id", "study_deployment_id", "data_type"])
    found["records_total"] = len(provenance)
    found["participants_total"] = provenance["study_deployment_id"].nunique()

    # The conference window (notebook 02, section 1).
    start, end = window(args.start, args.end)
    times = pd.read_csv(args.measurements, dtype=str, keep_default_na=False,
                        usecols=["row_id", "sensor_start_time"])
    stamped = provenance.merge(times, on="row_id")
    stamped["timestamp"] = pd.to_datetime(pd.to_numeric(stamped["sensor_start_time"]), unit="us")
    week = in_window(stamped, start, end)
    week["stream"] = week["data_type"].str.removeprefix("dk.cachet.carp.")
    for stream in SENSORS:
        rows = week[week["stream"] == stream]
        found[f"{stream}_rows"] = len(rows)
        found[f"{stream}_participants"] = rows["study_deployment_id"].nunique()

    # Steps (notebook 02, section 2).
    intervals = pd.read_csv(args.intervals)
    found["steps_delta_total"] = int(intervals["steps_delta"].sum())

    # Survey tables (notebook 02, section 3).
    for name in SURVEY_TABLES:
        table = pd.read_csv(getattr(args, name))
        found[f"{name}_rows"] = len(table)
        found[f"{name}_participants"] = table["participant_id"].nunique() if len(table) else 0

    # Notebook 01's printed findings.
    spans = explore.participant_spans(load_stream(args.steps, ["participant_id", "timestamp"]))
    found["active_days_median"] = spans["days_active"].median()
    found["active_days_mean"] = round(spans["days_active"].mean(), 1)
    found["active_days_max"] = spans["days_active"].max()

    matrix, total = explore.heartbeat_coverage(
        load_stream(args.heartbeat, ["participant_id", "timestamp"]),
        pd.Timestamp(args.coverage_from), pd.Timestamp(args.coverage_to))
    found["coverage_participants_shown"] = len(matrix)
    found["coverage_participants_total"] = total
    found["coverage_mean_pct"] = round(matrix.values.mean() * 100, 1)

    for platform, count in explore.platforms(load_stream(args.deviceinformation)).items():
        found[f"platform_{platform}"] = count

    surveys = explore.parsed_surveys(load_stream(args.survey))
    submitted = surveys[surveys["status"] == "submitted"]
    found["surveys_total"] = len(surveys)
    found["surveys_submitted"] = len(submitted)
    found["surveys_canceled"] = int((surveys["status"] == "canceled").sum())
    found["surveys_participants"] = submitted["participant_id"].nunique()

    who5 = pd.DataFrame(explore.who5_scores(submitted))
    found["who5_n"] = len(who5)
    if len(who5):
        found["who5_median_pct"] = who5["pct_score"].median()
        found["who5_below_50"] = int((who5["pct_score"] < 50).sum())
    bfi = pd.DataFrame(explore.bfi_dimensions(submitted))
    found["bfi10_n"] = len(bfi)
    for dim in ["Extraversion", "Agreeableness", "Conscientiousness", "Neuroticism", "Openness"]:
        if len(bfi):
            found[f"bfi10_{dim.lower()}_mean"] = round(bfi[dim].mean(), 2)

    result = pd.DataFrame({"metric": list(found), "value": [_text(v) for v in found.values()]})
    if args.expected:
        expected = pd.read_csv(args.expected, dtype={"expected": str})
        result = result.merge(expected, on="metric", how="outer")
        result["match"] = [
            "" if pd.isna(e) else str(_same(v, e)).lower()
            for v, e in zip(result["value"], result["expected"])
        ]
        misses = result[result["match"] == "false"]
        print(f"{(result['match'] == 'true').sum()} of {result['expected'].notna().sum()} findings match")
        for _, row in misses.iterrows():
            print(f"  {row['metric']}: {row['value']} (expected {row['expected']})")
    result.to_csv(args.output, index=False)


def _text(value):
    """A whole number without its `.0`, so the table reads as the notebook printed it."""
    number = float(value)
    return str(int(number)) if number.is_integer() else str(number)


def _same(value, expected):
    try:
        return abs(float(value) - float(expected)) < 1e-9
    except (TypeError, ValueError):
        return str(value) == str(expected)


if __name__ == "__main__":
    main()
