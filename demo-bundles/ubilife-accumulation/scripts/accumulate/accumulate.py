"""How the data piled up over the conference week, this year against last.

Reads the steps, survey and activity tables of two years (from split_streams.py)
and writes the running totals hour by hour, and a figure with one small chart
per measure: 2025 dashed over the whole week, 2026 solid up to now.

    --a-steps ... --a-from 2025-10-12 --a-to 2025-10-17 --a-label 2025
    --b-steps ... --b-from 2026-10-11 --b-to 2026-10-16 --b-label 2026
    --b-until 2026-10-13T14:30:00Z

The two weeks are lined up by their start: hour 0 is `--a-from` in one year and
`--b-from` in the other. Records from before the start count from the start.

Measures: participants who have sent anything, step and activity records, and
submitted surveys, in all and by kind.
"""

import argparse
import json

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import pandas as pd  # noqa: E402

COLOURS = {"a": "#4C72B0", "b": "#DD8452"}
GRID = "#E5E5E5"
NOW = "#888888"

SURVEY_KINDS = [
    ("Onboarding", lambda t: t == "onboarding.task"),
    ("Stress", lambda t: t == "daily.stress.task"),
    ("Sleep", lambda t: t == "daily.sleep.task"),
    ("WHO-5", lambda t: t == "who5.task"),
    ("BFI-10", lambda t: t == "bfi10.task"),
    ("Session and keynote ratings",
     lambda t: t.startswith("survey.session.") or t.startswith("survey.keynote.")),
]
PANELS = ["Participants", "Step records", "Activity records", "Survey answers",
          "Session and keynote ratings", "Onboarding", "Stress", "Sleep", "WHO-5", "BFI-10"]


def utc(value):
    """A timestamp as naive UTC."""
    stamp = pd.Timestamp(value)
    return stamp.tz_convert("UTC").tz_localize(None) if stamp.tzinfo else stamp


def times(path, columns=("participant_id", "timestamp")):
    """A stream table's rows, with `timestamp` as naive UTC. No rows yet: an empty table."""
    head = pd.read_csv(path, nrows=0).columns
    df = pd.read_csv(path, dtype=str, keep_default_na=False,
                     usecols=[c for c in columns if c in head])
    for column in columns:
        if column not in df.columns:
            df[column] = pd.Series(dtype=str)
    df["timestamp"] = pd.to_datetime(pd.to_numeric(df["timestamp"], errors="coerce"), unit="us")
    return df.dropna(subset=["timestamp"])


def survey_kind(result):
    try:
        identifier = (json.loads(result) or {}).get("identifier") or ""
    except ValueError:
        return None
    return next((name for name, matches in SURVEY_KINDS if matches(identifier)), None)


def events(steps, survey, activity):
    """Each measure's event times: one per record, or per participant's first record."""
    steps, activity = times(steps), times(activity)
    survey = times(survey, ("participant_id", "timestamp", "result", "status"))
    everything = pd.concat([steps, activity, survey[["participant_id", "timestamp"]]])
    out = {
        "Participants": everything.groupby("participant_id")["timestamp"].min(),
        "Step records": steps["timestamp"],
        "Activity records": activity["timestamp"],
    }
    submitted = survey[survey["status"] == "submitted"].copy()
    submitted["kind"] = submitted["result"].map(survey_kind)
    out["Survey answers"] = submitted["timestamp"]
    for name, _ in SURVEY_KINDS:
        out[name] = submitted.loc[submitted["kind"] == name, "timestamp"]
    return out


def running_totals(series, start, end):
    """Records up to each hour from `start` to `end`, both ends included."""
    hours = pd.date_range(start, end.ceil("h"), freq="h")
    hours = hours[hours <= end].append(pd.DatetimeIndex([end])).unique()
    ordered = series.sort_values().to_numpy()
    counts = ordered.searchsorted(hours.to_numpy(), side="right")
    return pd.DataFrame({"time": hours, "hour": (hours - start) / pd.Timedelta(hours=1), "count": counts})


def table(year_events, label, start, end):
    parts = []
    for measure in PANELS:
        totals = running_totals(year_events[measure], start, end)
        parts.append(totals.assign(measure=measure, year=label))
    return pd.concat(parts, ignore_index=True)[["measure", "year", "time", "hour", "count"]]


def figure(totals, args, a_end, b_end, path):
    columns = 5
    rows = -(-len(PANELS) // columns)
    fig, axes = plt.subplots(rows, columns, figsize=(3.6 * columns, 2.9 * rows), sharex=True)
    span = (a_end - utc(args.a_from)) / pd.Timedelta(hours=1)
    now = (b_end - utc(args.b_from)) / pd.Timedelta(hours=1)
    days = list(range(0, int(span) + 1, 24))
    # Day names from the first year: a replay moves the second year's dates, not the week.
    names = [(utc(args.a_from) + pd.Timedelta(hours=h)).strftime("%a") for h in days[:-1]]

    for ax, measure in zip(axes.flat, PANELS):
        rows_a = totals[(totals["measure"] == measure) & (totals["year"] == args.a_label)]
        rows_b = totals[(totals["measure"] == measure) & (totals["year"] == args.b_label)]
        ax.plot(rows_a["hour"], rows_a["count"], color=COLOURS["a"], linewidth=1.6,
                linestyle=(0, (4, 2)), label=args.a_label)
        ax.plot(rows_b["hour"], rows_b["count"], color=COLOURS["b"], linewidth=2, label=args.b_label)
        ax.axvline(now, color=NOW, linewidth=0.8)
        then = rows_a.loc[rows_a["hour"] <= now, "count"]
        b_now = int(rows_b["count"].iloc[-1]) if len(rows_b) else 0
        a_then = int(then.iloc[-1]) if len(then) else 0
        ax.set_title(f"{measure}\n{args.b_label}: {b_now:,}   {args.a_label} by then: {a_then:,}",
                     fontsize=9, loc="left")
        ax.set_xlim(0, span)
        ax.set_ylim(bottom=0)
        ax.set_xticks(days, [""] * len(days))
        ax.set_xticks([d + 12 for d in days[:-1]], names, minor=True, fontsize=8)
        ax.tick_params(axis="x", which="minor", length=0)
        ax.tick_params(axis="y", labelsize=8)
        ax.yaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda v, _: f"{v:,.0f}"))
        ax.grid(color=GRID, linewidth=0.6)
        ax.set_axisbelow(True)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
    for ax in list(axes.flat)[len(PANELS):]:
        ax.set_visible(False)

    handles, labels = axes.flat[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper right", ncol=2, frameon=False, fontsize=9)
    fig.suptitle(f"Data so far: {args.b_label} up to {b_end:%a %d %b %H:%M} UTC "
                 f"against {args.a_label} over the same week", x=0.01, ha="left", fontsize=11)
    fig.tight_layout(rect=(0, 0, 1, 0.95))
    fig.savefig(path, dpi=130)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for side in ("a", "b"):
        for stream in ("steps", "survey", "activity"):
            ap.add_argument(f"--{side}-{stream}", required=True, help=f"{stream} table")
        ap.add_argument(f"--{side}-from", required=True, help="start of the week (hour 0)")
        ap.add_argument(f"--{side}-to", required=True, help="end of the week")
        ap.add_argument(f"--{side}-label", default=side.upper())
    ap.add_argument("--b-until", help="draw the second year up to here (default: its end)")
    ap.add_argument("--output", required=True, help="running totals, CSV")
    ap.add_argument("--figure", required=True, help="PNG")
    args = ap.parse_args()

    a_start, a_end = utc(args.a_from), utc(args.a_to)
    b_start, b_end = utc(args.b_from), utc(args.b_to)
    if args.b_until:
        b_end = max(b_start, min(b_end, utc(args.b_until)))

    totals = pd.concat([
        table(events(args.a_steps, args.a_survey, args.a_activity), args.a_label, a_start, a_end),
        table(events(args.b_steps, args.b_survey, args.b_activity), args.b_label, b_start, b_end),
    ], ignore_index=True)
    totals.to_csv(args.output, index=False)
    figure(totals, args, a_end, b_end, args.figure)

    now = totals[totals["year"] == args.b_label].groupby("measure")["count"].last()
    print(f"{args.b_label} up to {b_end:%Y-%m-%d %H:%M}: "
          + ", ".join(f"{m} {int(now[m]):,}" for m in PANELS))


if __name__ == "__main__":
    main()
