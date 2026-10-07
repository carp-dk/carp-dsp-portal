"""Plot functions for UbiLife dataset exploration.

From the UbiLife analysis (github.com/dtu-digital-health/UbiLife,
src/visualisation/plots.py, MIT licence). Changes: figures are saved to a given
path and closed rather than shown, the conference window is a parameter rather
than 13-17 Oct 2025, the BFI-10 boxplot uses matplotlib 3.9's `tick_labels`, and the session
ratings are ordered by the labels the answers carry.
"""

import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import seaborn as sns

# The notebook's plot style.
sns.set_theme(style="whitegrid", palette="husl")
plt.rcParams["figure.figsize"] = (12, 6)
plt.rcParams["font.size"] = 10


def _save(fig, path):
    fig.tight_layout()
    fig.savefig(path, dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_stream_overview(overview_df, path):
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 5))
    sns.barplot(data=overview_df, y="stream", x="records", ax=ax1)
    ax1.set_xscale("log")
    ax1.set(xlabel="Records (log)", title="Records per Stream")
    sns.barplot(data=overview_df, y="stream", x="participants", ax=ax2)
    ax2.set(xlabel="Participants", title="Participants per Stream")
    _save(fig, path)


def plot_daily_volume(stream_dfs, path):
    fig, axes = plt.subplots(2, 2, figsize=(14, 8))
    for ax, (name, df) in zip(axes.flat, stream_dfs.items()):
        daily = df.set_index("timestamp").resample("D").size()
        ax.bar(daily.index, daily.values)
        ax.set(title=name, ylabel="Records")    
        ax.tick_params(axis="x", rotation=30)
    _save(fig, path)


def plot_participant_spans(participant_spans, path, window=("2025-10-13", "2025-10-17")):
    fig, ax = plt.subplots(figsize=(14, 8))
    norm = plt.Normalize(participant_spans["count"].min(), participant_spans["count"].max())
    for i, (_, row) in enumerate(participant_spans.iterrows()):
        ax.barh(i, row["days_active"], left=row["min"], height=0.7,
                color=plt.cm.RdYlGn(norm(row["count"])))
    ax.set(yticks=[], xlabel="Date", title="Per-Participant Active Period (step data)")
    ax.axvspan(pd.Timestamp(window[0]), pd.Timestamp(window[1]), alpha=0.1, color="blue", label="Conference week")
    ax.legend(loc="lower right")
    plt.colorbar(plt.cm.ScalarMappable(norm=norm, cmap="RdYlGn"), ax=ax, label="Step records")
    _save(fig, path)


def plot_records_per_participant(stream_dfs, path):
    fig, axes = plt.subplots(2, 2, figsize=(14, 8))
    for ax, (name, df) in zip(axes.flat, stream_dfs.items()):
        counts = df.groupby("participant_id").size().sort_values(ascending=False)
        ax.bar(range(len(counts)), counts.values)
        ax.axhline(counts.median(), color="red", ls="--", label=f"median={counts.median():,.0f}")
        ax.set(title=name, xlabel="Participant (ranked)", ylabel="Records")
        ax.legend(fontsize=8)
    _save(fig, path)


def plot_step_cumulative_vs_delta(one_p, path):
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 4))
    ax1.plot(one_p["timestamp"], one_p["steps"], lw=0.5)
    ax1.set(title="Raw Step Count (cumulative)", ylabel="Steps")
    ax2.plot(one_p["timestamp"], one_p["step_delta"], lw=0.5)
    ax2.set(title="Step Deltas (diff, clipped >=0)", ylabel="Steps/interval")
    _save(fig, path)


def plot_activity_types(df_activity, path):
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 5))
    df_activity["activity_type"].value_counts().plot.barh(ax=ax1)
    ax1.set(xlabel="Records", title="Activity Type Distribution")
    df = df_activity.copy()
    df["hour"] = df["timestamp"].dt.hour
    df.groupby(["hour", "activity_type"]).size().unstack(fill_value=0).plot.area(ax=ax2, alpha=0.7)
    ax2.set(xlabel="Hour", ylabel="Records", title="Activity by Hour")
    ax2.legend(fontsize=7)
    _save(fig, path)


def plot_devices(df_device, path):
    fig, (ax1, ax2, ax3) = plt.subplots(1, 3, figsize=(16, 5))
    df_device["platform"].value_counts().plot.pie(ax=ax1, autopct="%1.0f%%")
    ax1.set(ylabel="", title="Platform")
    df_device["manufacturer"].value_counts().plot.barh(ax=ax2)
    ax2.set(xlabel="Reports", title="Manufacturer")
    df_device["hardware"].value_counts().head(10).plot.barh(ax=ax3)
    ax3.set(xlabel="Reports", title="Top 10 Hardware")
    _save(fig, path)


def plot_survey_overview(df_survey, submitted, path):
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 5))
    ct = df_survey.groupby(["survey_type", "status"]).size().unstack(fill_value=0)
    ct.plot.barh(stacked=True, ax=ax1, color={"submitted": "#4CAF50", "canceled": "#F44336"})
    ax1.set(xlabel="Responses", title="Submissions vs Cancellations")
    submitted.set_index("timestamp").resample("D")["status"].count().plot.bar(ax=ax2)
    ax2.set(ylabel="Surveys", title="Submissions Over Time")
    _save(fig, path)


def plot_demographics(ages, genders, roles, prior, path):
    fig, axes = plt.subplots(2, 2, figsize=(13, 9))
    axes[0, 0].hist(ages, bins=range(20, 65, 3))
    axes[0, 0].axvline(np.median(ages), color="red", ls="--", label=f"median={np.median(ages):.0f}")
    axes[0, 0].set(xlabel="Age", ylabel="Count", title=f"Age (n={len(ages)})")
    axes[0, 0].legend()
    pd.Series(genders).value_counts().plot.pie(ax=axes[0, 1], autopct="%1.0f%%")
    axes[0, 1].set(ylabel="", title=f"Gender (n={len(genders)})")
    pd.Series(roles).value_counts().plot.barh(ax=axes[1, 0])
    axes[1, 0].set(xlabel="Count", title=f"Role (n={len(roles)})")
    pd.Series(prior).value_counts().plot.barh(ax=axes[1, 1])
    axes[1, 1].set(xlabel="Count", title=f"Prior UbiComp (n={len(prior)})")
    _save(fig, path)


def plot_daily_surveys(stress_vals, sleep_quality_texts, sleep_hour_texts, path):
    fig, (ax1, ax2, ax3) = plt.subplots(1, 3, figsize=(16, 5))
    ax1.hist(stress_vals, bins=np.arange(-0.5, 11.5, 1))
    ax1.axvline(np.median(stress_vals), color="red", ls="--", label=f"median={np.median(stress_vals):.1f}")
    ax1.set(xlabel="Stress (0-10)", ylabel="Responses", title=f"Stress (n={len(stress_vals)})")
    ax1.legend()
    quality_order = ["Very Bad", "Bad", "Neutral", "Good", "Amazing"]
    sq = pd.Series(sleep_quality_texts).value_counts()
    sq = sq.reindex([q for q in quality_order if q in sq.index])
    sq.plot.barh(ax=ax2)
    ax2.set(xlabel="Responses", title=f"Sleep Quality (n={len(sleep_quality_texts)})")
    hours_order = ["<6 hours", "6-8 hours", ">8 hours"]
    sh = pd.Series(sleep_hour_texts).value_counts()
    sh = sh.reindex([h for h in hours_order if h in sh.index])
    sh.plot.barh(ax=ax3)
    ax3.set(xlabel="Responses", title=f"Sleep Duration (n={len(sleep_hour_texts)})")
    _save(fig, path)


def plot_who5_bfi10(who5_scores, bfi_dimensions, path):
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 5))
    if who5_scores:
        who5_df = pd.DataFrame(who5_scores)
        ax1.hist(who5_df["pct_score"], bins=12)
        ax1.axvline(who5_df["pct_score"].median(), color="red", ls="--", label=f'median={who5_df["pct_score"].median():.0f}%')
        ax1.axvline(50, color="orange", ls=":", label="Threshold (50%)")
        ax1.set(xlabel="WHO-5 Score (%)", ylabel="Participants", title=f"WHO-5 (n={len(who5_df)})")
        ax1.legend(fontsize=8)
    if bfi_dimensions:
        bfi_df = pd.DataFrame(bfi_dimensions)
        dims = ["Extraversion", "Agreeableness", "Conscientiousness", "Neuroticism", "Openness"]
        bp = ax2.boxplot(
            [bfi_df[d] for d in dims],
            # `labels` in the original; matplotlib 3.9 renamed it, and 3.11 removed it.
            tick_labels=dims,
            vert=False,
            patch_artist=True,
            medianprops=dict(color="black", linewidth=2),
            whiskerprops=dict(color="#555555"),
            capprops=dict(color="#555555"),
            flierprops=dict(marker="o", markerfacecolor="#555555", markersize=5),
        )
        colors = ["#4C72B0", "#55A868", "#C44E52", "#8172B2", "#CCB974"]
        for patch, color in zip(bp["boxes"], colors):
            patch.set_facecolor(color)
            patch.set_alpha(0.7)
        ax2.set(xlabel="Score (0-4)", title=f"BFI-10 (n={len(bfi_df)})")
    _save(fig, path)


def plot_session_ratings(session_ratings, path):
    if not session_ratings:
        print("No session rating data found")
        return
    sr_df = pd.DataFrame(session_ratings)
    # The original ordered by "Slightly Boring" and "Very Interesting", which no
    # answer carries, so "Very Boring" and "Amazing" fell out of the figure.
    rating_order = ["Very Boring", "Boring", "Neutral", "Interesting", "Amazing"]
    ct = pd.crosstab(sr_df["session"], sr_df["rating"]).reindex(columns=rating_order, fill_value=0)
    fig, ax = plt.subplots(figsize=(12, 5))
    ct.plot.barh(stacked=True, ax=ax)
    ax.set(xlabel="Responses", title="Session Ratings")
    ax.legend(fontsize=8, bbox_to_anchor=(1.02, 1))
    _save(fig, path)


def plot_heartbeat_coverage(df_hb, path, min_coverage=0.10,
                            window=("2025-10-13 08:00:00", "2025-10-17 23:59:59")):
    """Heatmap: participants (y) × 4-hour bins (x), colour = coverage 0→1.

    Each cell = 4 hours.  Coverage = unique 5-min slots / 48 (max per 4h).
    Filtered to conference period (Oct 13-17), participants with ≥min_coverage.
    Colour scale: red (0) → blue (1).
    """
    conf_start = pd.Timestamp(window[0])
    conf_end = pd.Timestamp(window[1])

    # Parse timestamps, filter to conference
    df = df_hb.copy()
    df["ts"] = df["timestamp"]
    df = df[(df["ts"] >= conf_start) & (df["ts"] <= conf_end)]
    df["pid"] = df["participant_id"]

    # Floor to 5-min slots, deduplicate (multiple services per cycle)
    df["slot"] = df["ts"].dt.floor("5min")
    df = df.drop_duplicates(subset=["pid", "slot"])

    # 4-hour bins: 48 possible 5-min slots per bin
    df["bin4h"] = df["slot"].dt.floor("4h")
    slots_per_bin = 48  # 12 slots/hr × 4 hrs

    counts = df.groupby(["pid", "bin4h"])["slot"].nunique().reset_index(name="n")
    all_bins = pd.date_range(conf_start, conf_end.ceil("4h"), freq="4h")
    all_pids = sorted(df["pid"].unique())

    matrix = counts.pivot_table(index="pid", columns="bin4h", values="n", fill_value=0)
    matrix = matrix.reindex(columns=all_bins, fill_value=0).reindex(all_pids, fill_value=0)
    matrix = (matrix / slots_per_bin).clip(upper=1.0)

    # Filter ≥ min_coverage, sort best on top
    keep = matrix.mean(axis=1) >= min_coverage
    matrix = matrix.loc[keep].pipe(lambda d: d.loc[d.mean(axis=1).sort_values(ascending=False).index])
    n_shown, n_total = len(matrix), len(all_pids)

    # X-axis labels: show every bin
    x_labels = [b.strftime("%b %d %H:00") for b in all_bins]
    y_labels = [pid[:8] for pid in matrix.index]

    # Plot — 16:9
    fig, ax = plt.subplots(figsize=(16, 9))

    from matplotlib.colors import LinearSegmentedColormap
    cmap = LinearSegmentedColormap.from_list(
        "coverage", ["#c73a3a", "#ffffff", "#2177cf"], N=256
    )
    im = ax.imshow(matrix.values, aspect="auto", cmap=cmap, vmin=0, vmax=1)

    ax.set_xticks(range(len(all_bins)))
    ax.set_xticklabels(x_labels, fontsize=6, rotation=90, ha="center")
    ax.set_yticks(range(n_shown))
    ax.set_yticklabels(y_labels, fontsize=7)

    # Grid lines between days
    for i, b in enumerate(all_bins):
        if b.hour == 0 and i > 0:
            ax.axvline(i - 0.5, color="black", lw=1, alpha=0.6)

    ax.set_xlabel("Day / Time (4-hour bins)")
    ax.set_ylabel("Participant")
    ax.set_title(f"Heartbeat Coverage — Conference Period ({conf_start:%b %d}-{conf_end:%d})")

    cbar = fig.colorbar(im, ax=ax, shrink=0.6, pad=0.02)
    cbar.set_label("Coverage (0 = no signal, 1 = 48/48 slots)")

    mean_cov = matrix.values.mean() * 100
    fig.text(
        0.5, -0.01,
        f"{n_shown}/{n_total} participants (≥{min_coverage*100:.0f}% coverage) | "
        f"Mean coverage: {mean_cov:.1f}%",
        ha="center", fontsize=9, style="italic",
    )
    _save(fig, path)

    return matrix


def plot_weather(df_weather, path):
    fig, axes = plt.subplots(2, 2, figsize=(14, 8))
    daily = df_weather.set_index("timestamp").resample("D")["temperature"].agg(["mean", "min", "max"])
    axes[0, 0].fill_between(daily.index, daily["min"], daily["max"], alpha=0.3)
    axes[0, 0].plot(daily.index, daily["mean"])
    axes[0, 0].set(ylabel="Temp (C)", title="Daily Temperature")
    df_weather["weather_main"].value_counts().plot.barh(ax=axes[0, 1])
    axes[0, 1].set(xlabel="Records", title="Weather Conditions")
    axes[1, 0].hist(df_weather["humidity"].dropna(), bins=30)
    axes[1, 0].set(xlabel="Humidity (%)", title="Humidity")
    axes[1, 1].hist(df_weather["wind_speed"].dropna(), bins=30)
    axes[1, 1].set(xlabel="Wind Speed (m/s)", title="Wind Speed")
    _save(fig, path)
