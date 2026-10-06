"""Draw the UbiLife exploration figures (notebook 01).

Each figure is asked for by name, with where to write it:

    --figure stream_overview output.0 --figure daily_volume output.1

and reads only the stream tables it needs. Names: stream_overview, daily_volume,
participant_spans, records_per_participant, heartbeat_coverage,
step_cumulative_vs_delta, activity_types, devices, weather, survey_overview,
demographics, daily_surveys, who5_bfi10, session_ratings.
"""

import argparse
from functools import cache

import pandas as pd

import explore
import plots
from common import load_stream

NEEDS = {
    "stream_overview": ["provenance"],
    "daily_volume": ["steps", "location", "activity", "survey"],
    "participant_spans": ["steps"],
    "records_per_participant": ["steps", "location", "activity", "survey"],
    "heartbeat_coverage": ["heartbeat"],
    "step_cumulative_vs_delta": ["steps"],
    "activity_types": ["activity"],
    "devices": ["deviceinformation"],
    "weather": ["weather"],
    "survey_overview": ["survey"],
    "demographics": ["survey"],
    "daily_surveys": ["survey"],
    "who5_bfi10": ["survey"],
    "session_ratings": ["survey"],
}
TABLES = sorted({t for needs in NEEDS.values() for t in needs})


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    for table in TABLES:
        ap.add_argument(f"--{table}", help=f"{table} table")
    ap.add_argument("--figure", nargs=2, action="append", required=True, metavar=("NAME", "OUTPUT"))
    ap.add_argument("--window-from", default="2025-10-13", help="conference band start")
    ap.add_argument("--window-to", default="2025-10-17", help="conference band end")
    ap.add_argument("--coverage-from", default="2025-10-13 08:00:00")
    ap.add_argument("--coverage-to", default="2025-10-17 23:59:59")
    args = ap.parse_args()

    figures = dict(args.figure)
    unknown = set(figures) - set(NEEDS)
    if unknown:
        ap.error(f"unknown figures: {sorted(unknown)}")
    for name in figures:
        missing = [t for t in NEEDS[name] if not getattr(args, t)]
        if missing:
            ap.error(f"{name} needs --{' --'.join(missing)}")

    @cache
    def table(name):
        path = getattr(args, name)
        if name == "provenance":
            return pd.read_csv(path, dtype=str, keep_default_na=False,
                               usecols=["row_id", "study_deployment_id", "data_type"])
        return load_stream(path)

    @cache
    def submitted():
        surveys = explore.parsed_surveys(table("survey"))
        return surveys[surveys["status"] == "submitted"]

    def key_streams():
        return {"stepcount": table("steps"), "location": table("location"),
                "activity": table("activity"), "survey": table("survey")}

    draw = {
        "stream_overview": lambda p: plots.plot_stream_overview(explore.stream_overview(table("provenance")), p),
        "daily_volume": lambda p: plots.plot_daily_volume(key_streams(), p),
        "participant_spans": lambda p: plots.plot_participant_spans(
            explore.participant_spans(table("steps")), p, window=(args.window_from, args.window_to)),
        "records_per_participant": lambda p: plots.plot_records_per_participant(key_streams(), p),
        "heartbeat_coverage": lambda p: draw_coverage(p),
        "step_cumulative_vs_delta": lambda p: plots.plot_step_cumulative_vs_delta(one_participant(), p),
        "activity_types": lambda p: plots.plot_activity_types(
            table("activity").assign(activity_type=table("activity")["type"]), p),
        "devices": lambda p: plots.plot_devices(explore.devices(table("deviceinformation")), p),
        "weather": lambda p: plots.plot_weather(explore.weather(table("weather")), p),
        "survey_overview": lambda p: plots.plot_survey_overview(
            explore.parsed_surveys(table("survey")), submitted(), p),
        "demographics": lambda p: plots.plot_demographics(*explore.demographics(submitted()), p),
        "daily_surveys": lambda p: plots.plot_daily_surveys(*explore.daily_surveys(submitted()), p),
        "who5_bfi10": lambda p: plots.plot_who5_bfi10(
            explore.who5_scores(submitted()), explore.bfi_dimensions(submitted()), p),
        "session_ratings": lambda p: plots.plot_session_ratings(explore.session_ratings(submitted()), p),
    }

    def one_participant():
        steps = table("steps")
        top = steps.groupby("participant_id").size().idxmax()
        one = steps[steps["participant_id"] == top].sort_values("timestamp").copy()
        one["steps"] = pd.to_numeric(one["steps"], errors="coerce")
        one["step_delta"] = one["steps"].diff().clip(lower=0)
        return one

    def draw_coverage(path):
        plots.plot_heartbeat_coverage(table("heartbeat"), path,
                                      window=(args.coverage_from, args.coverage_to))

    for name, path in figures.items():
        draw[name](path)
        print(f"{name} -> {path}")


if __name__ == "__main__":
    main()
