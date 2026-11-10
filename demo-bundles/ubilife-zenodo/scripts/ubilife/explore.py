"""The calculations behind the exploration figures and findings (notebook 01).

Each function takes stream tables from split_streams.py and returns what the
notebook computed.
"""

import json

import numpy as np
import pandas as pd

from common import numeric

CARP_PREFIX = "dk.cachet.carp."


# ── Streams ──────────────────────────────────────────────────────────────────


def stream_overview(provenance):
    """Records and participants per stream, over everything read."""
    df = provenance.assign(stream=provenance["data_type"].str.removeprefix(CARP_PREFIX))
    return (df.groupby("stream")
              .agg(records=("row_id", "size"), participants=("study_deployment_id", "nunique"))
              .reset_index()
              .sort_values("records"))


def participant_spans(steps):
    spans = steps.groupby("participant_id")["timestamp"].agg(["min", "max", "count"]).sort_values("min")
    spans["days_active"] = (spans["max"] - spans["min"]).dt.days + 1
    return spans


def heartbeat_coverage(heartbeat, start, end, min_coverage=0.10, period_minutes=5):
    """Participants x 4-hour bins, the share of `period_minutes` slots with a heartbeat.

    The 2025 heartbeat carried its period (5 minutes); 2026's does not, so it is
    an argument.
    """
    df = heartbeat[(heartbeat["timestamp"] >= start) & (heartbeat["timestamp"] <= end)].copy()
    df["slot"] = df["timestamp"].dt.floor(f"{period_minutes}min")
    df = df.drop_duplicates(subset=["participant_id", "slot"])
    df["bin4h"] = df["slot"].dt.floor("4h")
    slots_per_bin = 4 * 60 // period_minutes

    counts = df.groupby(["participant_id", "bin4h"])["slot"].nunique().reset_index(name="n")
    all_bins = pd.date_range(start, end.ceil("4h"), freq="4h")
    all_pids = sorted(df["participant_id"].unique())
    matrix = counts.pivot_table(index="participant_id", columns="bin4h", values="n", fill_value=0)
    matrix = matrix.reindex(columns=all_bins, fill_value=0).reindex(all_pids, fill_value=0)
    matrix = (matrix / slots_per_bin).clip(upper=1.0)
    keep = matrix.mean(axis=1) >= min_coverage
    matrix = matrix.loc[keep]
    return matrix.loc[matrix.mean(axis=1).sort_values(ascending=False).index], len(all_pids)


def devices(deviceinformation):
    df = deviceinformation.rename(columns={"deviceManufacturer": "manufacturer"})
    df["hardware"] = df["hardware"].fillna("")
    return df


def platforms(deviceinformation):
    """Participants per platform, by each participant's first report."""
    return deviceinformation.groupby("participant_id")["platform"].first().value_counts()


def weather(df):
    return df.assign(
        temperature=numeric(df["temperature"]),
        humidity=numeric(df["humidity"]),
        wind_speed=numeric(df["windSpeed"]),
        weather_main=df["weatherMain"],
    )


# ── Surveys ──────────────────────────────────────────────────────────────────

# Labels by answer value, from the protocol's choices and the study's English
# language file. An answer's text is in the participant's language ("Godt",
# "Good"), so grouping by text splits one answer in two; its value does not.
RATING = {0: "Very Boring", 1: "Boring", 2: "Neutral", 3: "Interesting", 4: "Amazing",
          5: "I did not attend"}
LABELS = {
    "onboarding.gender.step": {0: "Female", 1: "Male", 2: "Other"},
    "onboarding.academic.step": {1: "Student", 2: "Researcher", 3: "Postdoc", 4: "Professor", 5: "Other"},
    "onboarding.prior.step": {0: "First", 1: "1-3", 2: "4-6", 3: "7+"},
    "daily.sleep.step": {0: "Very Bad", 1: "Bad", 2: "Neutral", 3: "Good", 4: "Amazing"},
    "daily.sleep.hours.step": {3: "<6 hours", 2: "6-8 hours", 1: ">8 hours"},
}
RATING_ORDER = [RATING[v] for v in range(5)]
DID_NOT_ATTEND = 5


def label(step_id, answer):
    """The English label for an answer, by its value; its own text if the value is unknown."""
    labels = RATING if ".rating." in step_id or step_id.startswith("survey.keynote.") else LABELS.get(step_id, {})
    try:
        return labels[int(answer["value"])]
    except (KeyError, TypeError, ValueError):
        return str(answer["text"])


def extract_answer(answer):
    """(value, text) of an answer, whatever its shape (notebook 01)."""
    if answer is None or (isinstance(answer, float) and np.isnan(answer)):
        return None, None
    if isinstance(answer, list) and answer and isinstance(answer[0], dict):
        if len(answer) == 1:
            return answer[0].get("value"), answer[0].get("text")
        return [c.get("value") for c in answer], [c.get("text") for c in answer]
    return answer, str(answer)


def parsed_surveys(survey):
    """Every survey with its type, status and {step: {value, text}} answers."""
    def parse(result):
        if not result:
            return None, {}
        task = json.loads(result) or {}
        answers = {}
        for step_id, step in (task.get("results") or {}).items():
            raw = (step.get("results") or {}).get("answer") if isinstance(step, dict) else None
            value, text = extract_answer(raw)
            if value is not None:
                answers[step_id] = {"value": value, "text": text}
        return task.get("identifier"), answers

    parsed = survey["result"].map(parse)
    return survey.assign(
        survey_type=[p[0] if p[0] is not None else "unknown" for p in parsed],
        answers=[p[1] for p in parsed],
    ).drop(columns=["result"])


def demographics(submitted):
    ages, genders, roles, prior = [], [], [], []
    for a in submitted.loc[submitted["survey_type"] == "onboarding.task", "answers"]:
        if "onboarding.age.step" in a:
            try:
                ages.append(float(a["onboarding.age.step"]["value"]))
            except (ValueError, TypeError):
                pass
        if "onboarding.gender.step" in a:
            genders.append(label("onboarding.gender.step", a["onboarding.gender.step"]))
        if "onboarding.academic.step" in a:
            roles.append(label("onboarding.academic.step", a["onboarding.academic.step"]))
        if "onboarding.prior.step" in a:
            prior.append(label("onboarding.prior.step", a["onboarding.prior.step"]))
    return ages, genders, roles, prior


def daily_surveys(submitted):
    stress, quality, hours = [], [], []
    for survey_type, a in zip(submitted["survey_type"], submitted["answers"]):
        if survey_type == "daily.stress.task" and "daily.stress.step" in a:
            try:
                stress.append(float(a["daily.stress.step"]["value"]))
            except (ValueError, TypeError):
                pass
        if survey_type == "daily.sleep.task":
            if "daily.sleep.step" in a:
                quality.append(label("daily.sleep.step", a["daily.sleep.step"]))
            if "daily.sleep.hours.step" in a:
                hours.append(label("daily.sleep.hours.step", a["daily.sleep.hours.step"]))
    return stress, quality, hours


def who5_scores(submitted):
    scores = []
    for pid, a in submitted.loc[submitted["survey_type"] == "who5.task", ["participant_id", "answers"]].values:
        vals = []
        for q in range(1, 6):
            key = f"survey.who5.question.{q}"
            if key in a:
                try:
                    vals.append(int(a[key]["value"]))
                except (ValueError, TypeError):
                    pass
        if len(vals) == 5:
            scores.append({"participant_id": pid, "raw_sum": sum(vals), "pct_score": sum(vals) * 4})
    return scores


def bfi_dimensions(submitted):
    dims = []
    for pid, a in submitted.loc[submitted["survey_type"] == "bfi10.task", ["participant_id", "answers"]].values:
        items = {}
        for q in range(1, 11):
            key = f"bfi10.question{q}"
            if key in a:
                try:
                    items[q] = int(a[key]["value"])
                except (ValueError, TypeError):
                    pass
        if len(items) == 10:
            dims.append({
                "participant_id": pid,
                "Extraversion": (4 - items[1] + items[6]) / 2,
                "Agreeableness": (items[2] + 4 - items[7]) / 2,
                "Conscientiousness": (4 - items[3] + items[8]) / 2,
                "Neuroticism": (4 - items[4] + items[9]) / 2,
                "Openness": (4 - items[5] + items[10]) / 2,
            })
    return dims


def session_ratings(submitted):
    """Session and keynote ratings, labelled by value. "I did not attend" is not a rating, and is left out."""
    ratings = []
    for st, a in zip(submitted["survey_type"], submitted["answers"]):
        if st.startswith("survey.session.") and st.endswith(".task"):
            letter = st.replace("survey.session.", "").replace(".task", "")
            key = f"survey.session.rating.question.{letter}.step"
            if key in a and _value(a[key]) != DID_NOT_ATTEND:
                ratings.append({"session": f"Session {letter}", "rating": label(key, a[key])})
        elif st.startswith("survey.keynote.") and st.endswith(".task"):
            num = st.replace("survey.keynote.", "").replace(".task", "")
            key = f"survey.keynote.{num}.step"
            if key in a and _value(a[key]) != DID_NOT_ATTEND:
                ratings.append({"session": f"Keynote {num}", "rating": label(key, a[key])})
    return ratings


def _value(answer):
    try:
        return int(answer["value"])
    except (KeyError, TypeError, ValueError):
        return None
