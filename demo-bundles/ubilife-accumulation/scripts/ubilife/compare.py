"""Compare two UbiLife cohorts, measure by measure: same, or shifted?

Takes the prepared tables of two runs of the UbiLife workflow - by default 2025
and 2026 - and writes one row per measure: each year's n and summary, the test,
its p-value, an effect size, and a verdict.

The cohorts are different people, and small, so the verdict rests on the effect
size, not on the p-value alone:

- numbers and ordinal answers: Mann-Whitney U, and Cliff's delta. |d| < 0.147 is
  negligible ("same"); 0.33 and 0.474 mark small, medium and large shifts
  (Romano et al., 2006).
- categories: chi-square, and Cramér's V. V < 0.1 is negligible; 0.3 and 0.5
  mark small, medium and large.

Daily measures - steps, stress, sleep, ratings, activity - are compared within
each year's conference window; answers given once (onboarding, WHO-5, BFI-10)
are compared whole. With --b-until, the second year is compared so far: both
windows are cut to the time it has run, so day 1-3 of one year meets day 1-3 of
the other. Steps given per day count whole days only; steps given per interval
(a timestamp and steps_delta) count whole hours, so today's steps show too.

Every answer is compared by its value, never its text, which is in the
participant's language. "I did not attend" (5) is not a rating, and is left out.
Activity is compared record by record; records from one person are not
independent, so read its p-value loosely.
"""

import argparse

import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from scipy import stats

TABLES = ["daily_steps", "stress", "sleep", "who5", "bfi10", "onboarding", "events", "activity"]
BFI = ["extraversion", "agreeableness", "conscientiousness", "neuroticism", "openness"]
# Answers given once, cut only at the end.
ONCE = ["onboarding", "who5", "bfi10"]
# Tables cut to the window, and the column that dates each row.
DAILY = {"daily_steps": "date", "stress": "timestamp", "sleep": "timestamp",
         "events": "timestamp", "activity": "timestamp"}
DID_NOT_ATTEND = 5
MIN_N = 5

# (measure, table, column, kind, how to read it)
MEASURES = [
    ("Steps per participant-day", "daily_steps", "steps", "numeric", None),
    ("Stress (0-10)", "stress", "stress_level", "numeric", None),
    ("Sleep quality (0-4)", "sleep", "sleep_quality_value", "ordinal", None),
    ("Sleep duration (1 >8 h, 2 6-8 h, 3 <6 h)", "sleep", "sleep_hours_value", "ordinal", None),
    ("WHO-5 (%)", "who5", "pct_score", "numeric", None),
    *[(f"BFI-10 {d}", "bfi10", d, "numeric", None) for d in BFI],
    ("Age", "onboarding", "age", "numeric", None),
    ("Gender", "onboarding", "gender_value", "category", {0: "Female", 1: "Male", 2: "Other"}),
    ("Academic role", "onboarding", "academic_value", "category",
     {1: "Student", 2: "Researcher", 3: "Postdoc", 4: "Professor", 5: "Other"}),
    ("Prior UbiComps (0 first, 3 7+)", "onboarding", "prior_value", "ordinal", None),
    ("Session and keynote ratings (0-4)", "events", "rating_value", "ordinal", None),
    ("Activity type", "activity", "type", "category", None),
]


def cliffs_delta(a, b):
    """P(a > b) - P(a < b), from the Mann-Whitney U statistic."""
    u = stats.mannwhitneyu(b, a, alternative="two-sided").statistic
    return 2 * u / (len(a) * len(b)) - 1


def size_of(effect, cuts):
    small, medium, large = cuts
    magnitude = abs(effect)
    if magnitude < small:
        return "negligible"
    if magnitude < medium:
        return "small"
    if magnitude < large:
        return "medium"
    return "large"


def compare(a, b, kind):
    """Test, p, effect name, effect, size."""
    if kind in ("numeric", "ordinal"):
        p = stats.mannwhitneyu(a, b, alternative="two-sided").pvalue
        d = cliffs_delta(a, b)
        return "Mann-Whitney U", p, "Cliff's delta", d, size_of(d, (0.147, 0.33, 0.474))
    table = pd.crosstab(
        pd.Series(["a"] * len(a) + ["b"] * len(b)),
        pd.concat([pd.Series(a), pd.Series(b)], ignore_index=True),
    )
    chi2, p, _, _ = stats.chi2_contingency(table)
    v = np.sqrt(chi2 / (table.values.sum() * (min(table.shape) - 1))) if min(table.shape) > 1 else 0.0
    return "chi-square", p, "Cramér's V", v, size_of(v, (0.1, 0.3, 0.5))


def summary(values, kind, labels):
    if kind == "category":
        shares = pd.Series(values).map(lambda v: (labels or {}).get(v, v)).value_counts(normalize=True)
        return ", ".join(f"{k} {v:.0%}" for k, v in shares.head(3).items())
    q1, median, q3 = np.percentile(values, [25, 50, 75])
    return f"median {median:g} (IQR {q1:g}-{q3:g})"


def values_of(tables, table, column, kind):
    df = tables[table]
    if column not in df.columns:
        return []
    series = df[column]
    if table == "events":
        series = series[pd.to_numeric(series, errors="coerce") != DID_NOT_ATTEND]
    if kind == "category" and table == "activity":
        return series.dropna().astype(str).tolist()
    return pd.to_numeric(series, errors="coerce").dropna().tolist()


def instant(text):
    """A naive UTC timestamp, from an ISO date or date-time with or without an offset."""
    stamp = pd.Timestamp(text)
    return stamp.tz_convert(None) if stamp.tzinfo else stamp


def windows(args):
    """Each year's daily window [start, end), and the end for answers given once.

    Without --b-until: the whole window, and every answer given before it ends.
    With it: the time the second year has run, at the same point in both years.
    Before day 1 that point is days before the window, so the once-only answers
    still meet the cohort as it stood on the same day last year.
    """
    a_from, a_to = instant(args.a_from), instant(args.a_to)
    b_from, b_to = instant(args.b_from), instant(args.b_to)
    offset = b_to - b_from
    if args.b_until:
        offset = min(offset, instant(args.b_until) - b_from)
    span = max(offset, pd.Timedelta(0))
    a = (a_from, min(a_to, a_from + span), min(a_to, a_from + offset))
    b = (b_from, b_from + span, b_from + offset)
    return a, b


def stamps(series):
    numbers = pd.to_numeric(series, errors="coerce")
    if numbers.notna().any():
        return pd.to_datetime(numbers, unit="us")
    return pd.to_datetime(series, errors="coerce")


def cut(df, column, start, end):
    when = stamps(df[column])
    if column == "date":
        # A day counts only once it is over.
        return df[(when >= start.normalize()) & (when + pd.Timedelta(days=1) <= end)]
    return df[(when >= start) & (when < end)]


def read(path, **options):
    """A table, or an empty one when the file has no rows or lacks the columns asked for."""
    try:
        return pd.read_csv(path, **options)
    except (pd.errors.EmptyDataError, ValueError):
        return pd.DataFrame()


def steps_so_far(df, start, end):
    """Steps per participant-day from per-interval steps, counting only hours that are over."""
    hour = stamps(df["timestamp"]).dt.floor("h")
    keep = (hour >= start) & (hour + pd.Timedelta(hours=1) <= end)
    days = df[keep].assign(date=hour[keep].dt.date)
    return (days.groupby(["participant_id", "date"], as_index=False)["steps_delta"].sum()
            .rename(columns={"steps_delta": "steps"}))


def load(prefix, args, window):
    start, end, once_end = window
    tables = {}
    for name in TABLES:
        path = getattr(args, f"{prefix}_{name}")
        if name == "activity":
            df = read(path, usecols=["participant_id", "timestamp", "type"], dtype={"type": str})
        else:
            df = read(path)
        column = DAILY.get(name)
        if name == "daily_steps" and {"timestamp", "steps_delta"} <= set(df.columns):
            df = steps_so_far(df, start, end)
        elif column and column in df.columns:
            df = cut(df, column, start, end)
        elif name in ONCE and "timestamp" in df.columns:
            df = df[stamps(df["timestamp"]) < once_end]
        tables[name] = df
    return tables


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for prefix in ("a", "b"):
        for name in TABLES:
            ap.add_argument(f"--{prefix}-{name.replace('_', '-')}", dest=f"{prefix}_{name}", required=True)
        ap.add_argument(f"--{prefix}-from", dest=f"{prefix}_from", required=True, help="window start (ISO)")
        ap.add_argument(f"--{prefix}-to", dest=f"{prefix}_to", required=True, help="window end, exclusive (ISO)")
    ap.add_argument("--b-until", help="compare the second year up to here (ISO date-time); whole window when absent")
    ap.add_argument("--a-label", default="2025")
    ap.add_argument("--b-label", default="2026")
    ap.add_argument("--output", required=True, help="comparison table (csv)")
    ap.add_argument("--figure", required=True, help="side-by-side figure (png)")
    args = ap.parse_args()

    window_a, window_b = windows(args)
    a, b = load("a", args, window_a), load("b", args, window_b)
    spans = {args.a_label: span_text(window_a), args.b_label: span_text(window_b)}
    befores = {args.a_label: f"answered before {window_a[2]:%Y-%m-%d %H:%M}",
               args.b_label: f"answered before {window_b[2]:%Y-%m-%d %H:%M}"}
    print("Windows: " + ", ".join(f"{k} {v}" for k, v in spans.items()))
    rows, plotted = [], []
    for measure, table, column, kind, labels in MEASURES:
        va, vb = values_of(a, table, column, kind), values_of(b, table, column, kind)
        row = {"measure": measure, "kind": kind,
               f"window_{args.a_label}": spans[args.a_label] if table in DAILY else befores[args.a_label],
               f"window_{args.b_label}": spans[args.b_label] if table in DAILY else befores[args.b_label],
               f"n_{args.a_label}": len(va), f"n_{args.b_label}": len(vb)}
        if min(len(va), len(vb)) < MIN_N:
            rows.append(row | {"verdict": f"too few to compare (< {MIN_N} each)"})
            continue
        test, p, effect_name, effect, size = compare(va, vb, kind)
        rows.append(row | {
            args.a_label: summary(va, kind, labels),
            args.b_label: summary(vb, kind, labels),
            "test": test, "p": round(p, 4),
            "effect": effect_name, "effect_size": round(effect, 3), "magnitude": size,
            "verdict": "same" if size == "negligible" else f"shifted ({size})",
        })
        plotted.append((f"{measure}: {rows[-1]['verdict']}", kind, labels, va, vb))

    result = pd.DataFrame(rows)
    result.to_csv(args.output, index=False)
    for _, r in result.iterrows():
        print(f"{r['measure']}: {r['verdict']}")

    legend = [f"{label} (daily measures: {spans[label]})" for label in (args.a_label, args.b_label)]
    draw(plotted, args.a_label, args.b_label, args.figure, legend)


def span_text(window):
    start, end, _ = window
    if end <= start:
        return "not started"
    return f"{start:%Y-%m-%d %H:%M} to {end:%Y-%m-%d %H:%M}"


COLOURS = ["#4C72B0", "#DD8452"]


def draw(plotted, label_a, label_b, path, legend):
    columns = 4
    rows = int(np.ceil(len(plotted) / columns)) or 1
    fig, axes = plt.subplots(rows, columns, figsize=(4.2 * columns, 3.2 * rows))
    for ax in np.ravel(axes)[len(plotted):]:
        ax.axis("off")
    for ax, (measure, kind, labels, va, vb) in zip(np.ravel(axes), plotted):
        if kind == "category":
            shares = pd.DataFrame({
                label_a: pd.Series(va).value_counts(normalize=True),
                label_b: pd.Series(vb).value_counts(normalize=True),
            }).fillna(0)
            if labels:
                shares.index = [labels.get(int(float(i)), i) if str(i).replace(".", "").isdigit() else i
                                for i in shares.index]
            shares.plot.barh(ax=ax, legend=False, color=COLOURS)
            ax.set_xlabel("share")
        else:
            box = ax.boxplot([va, vb], tick_labels=[label_a, label_b], patch_artist=True)
            for patch, colour in zip(box["boxes"], COLOURS):
                patch.set_facecolor(colour)
                patch.set_alpha(0.6)
        ax.set_title(measure, fontsize=9)
    handles = [plt.Rectangle((0, 0), 1, 1, color=c) for c in COLOURS]
    fig.legend(handles, legend, loc="upper center", ncol=2, frameon=False)
    fig.tight_layout(rect=(0, 0, 1, 0.97))
    fig.savefig(path, dpi=130, bbox_inches="tight")
    plt.close(fig)


if __name__ == "__main__":
    main()
