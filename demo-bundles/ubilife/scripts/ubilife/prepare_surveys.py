"""Parse submitted surveys into one table per instrument (notebook 02, section 3).

Writes onboarding, daily stress, daily sleep, WHO-5, BFI-10 and event ratings,
using the UbiLife parsers unchanged. Surveys are not filtered to the conference
window, as in the notebook.
"""

import argparse

import surveys

TABLES = {
    "onboarding": surveys.parse_onboarding,
    "stress": surveys.parse_daily_stress,
    "sleep": surveys.parse_daily_sleep,
    "who5": surveys.parse_who5,
    "bfi10": surveys.parse_bfi10,
    "events": surveys.parse_event_ratings,
}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--surveys", required=True, help="survey stream table")
    for name in TABLES:
        ap.add_argument(f"--{name}", required=True, help=f"{name} table (csv)")
    args = ap.parse_args()

    submitted = surveys.load_submitted(args.surveys)
    for name, parse in TABLES.items():
        table = parse(submitted)
        table.to_csv(getattr(args, name), index=False)
        participants = table["participant_id"].nunique() if len(table) else 0
        print(f"{name}: {len(table)} rows, {participants} participants")


if __name__ == "__main__":
    main()
