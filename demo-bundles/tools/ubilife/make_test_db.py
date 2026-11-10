"""Write a seed for a local CARP test database from study exports.

The UbiLife workflow reads the CARP database, which is only reachable from
production. This builds the four tables the workflow's query reads - the same
cut-down schema as carp-dsp's PostgresQueryIntegrationTest - from export files,
so the same workflow runs locally against a Postgres container.

    python make_test_db.py --out seed.sql.gz \\
        --study b3607581-56c6-4652-a4f6-8e86e5a4e7e5 2025-export.zip \\
        --study-2026 ba1834de-8caf-4e0e-9ec3-8db05285777f 2025-export.zip

`--study-2026` reshapes a 2025 export as 2026 would have sent it (make_2026.py)
before loading it; `--steps-factor` and `--stress-offset` shift it, so the
comparison workflow has something known to find. An export has no arrival time, so `created_at` is the last
measurement's time in each sequence.
"""

import argparse
import csv
import gzip
import io
import json
import sys
from collections import defaultdict
from datetime import date, datetime, timezone
from pathlib import Path

import ijson

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "ubilife-zenodo" / "scripts" / "ubilife"))
from read_export import open_export  # noqa: E402

import make_2026  # noqa: E402

SEQUENCE_LIMIT = 500
# The first conference day of each year (the Sunday before is workshops).
DAY_ONE_2025 = date(2025, 10, 13)
DAY_ONE_2026 = date(2026, 10, 12)

SCHEMA = """
CREATE TABLE recruitment_participant_groups (
    study_id    VARCHAR(255) NOT NULL,
    group_id    VARCHAR(255) NOT NULL,
    is_deployed BOOLEAN      NOT NULL
);
CREATE TABLE data_stream_ids (
    id                  INTEGER PRIMARY KEY,
    study_deployment_id VARCHAR(255) NOT NULL,
    device_role_name    VARCHAR(255),
    name                VARCHAR(255),
    name_space          VARCHAR(255)
);
CREATE TABLE data_stream_sequence (
    id                INTEGER PRIMARY KEY,
    data_stream_id    INTEGER,
    first_sequence_id INTEGER,
    last_sequence_id  INTEGER,
    snapshot          JSONB,
    created_at        TIMESTAMP WITHOUT TIME ZONE
);
"""
INDEXES = """
CREATE INDEX ON data_stream_sequence (data_stream_id, first_sequence_id);
CREATE INDEX ON data_stream_sequence (created_at);
CREATE INDEX ON recruitment_participant_groups (study_id);
"""


def records(path, reshape, steps_factor=1.0, stress_offset=0.0, offset_us=make_2026.OFFSET_US):
    with open_export(path) as stream:
        for record in ijson.items(stream, "item", use_float=True):
            if reshape:
                record = make_2026.convert(record, steps_factor, stress_offset, offset_us)
                if record is None:
                    continue
            yield record


def sync_point(record):
    point = dict(record.get("syncPoint") or {})
    # Exports write the clock speed as a string; the snapshot holds a number.
    point["relativeClockSpeed"] = float(point.get("relativeClockSpeed", 1.0))
    return point


def sequences(items):
    """Splits one stream's measurements, sorted by sequence id, into stored sequences."""
    current = []
    for item in items:
        seq, _, triggers, point = item
        if current and (seq != current[-1][0] + 1 or triggers != current[-1][2]
                        or point != current[-1][3] or len(current) >= SEQUENCE_LIMIT):
            yield current
            current = []
        current.append(item)
    if current:
        yield current


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, help="seed file (.sql or .sql.gz)")
    ap.add_argument("--study", nargs=2, action="append", default=[], metavar=("STUDY_ID", "EXPORT"))
    ap.add_argument("--study-2026", nargs=2, action="append", default=[], metavar=("STUDY_ID", "EXPORT"))
    ap.add_argument("--steps-factor", type=float, default=1.0, help="scales the reshaped studies' steps")
    ap.add_argument("--stress-offset", type=float, default=0.0, help="moves the reshaped studies' stress answers")
    ap.add_argument("--day-one", type=date.fromisoformat, default=DAY_ONE_2026,
                    help="the date 13 Oct 2025 moves to in the reshaped studies (default 2026-10-12, the real Monday). "
                         "Yesterday replays the conference from its second day, arriving in real time")
    args = ap.parse_args()
    offset_us = (args.day_one - DAY_ONE_2025).days * 24 * 3600 * 1_000_000

    streams = defaultdict(list)       # (deployment, role, namespace, name) -> items
    groups = set()                    # (study, deployment)
    for study_id, path, reshape in ([(s, p, False) for s, p in args.study]
                                    + [(s, p, True) for s, p in args.study_2026]):
        for record in records(path, reshape, args.steps_factor, args.stress_offset, offset_us):
            data_type = record["dataStream"]["dataType"]
            key = (record["studyDeploymentId"], record["deviceRoleName"],
                   data_type["namespace"], data_type["name"])
            measurement = {k: v for k, v in record["measurement"].items() if v is not None}
            streams[key].append((
                int(record["sequenceId"]),
                json.dumps(measurement, separators=(",", ":"), ensure_ascii=False),
                tuple(record.get("triggerIds") or []),
                json.dumps(sync_point(record), separators=(",", ":")),
            ))
            groups.add((study_id, record["studyDeploymentId"]))
        print(f"{study_id}: read {path}")

    opener = gzip.open if args.out.endswith(".gz") else open
    with opener(args.out, "wt", encoding="utf-8", newline="") as out:
        out.write(SCHEMA)
        out.write("COPY recruitment_participant_groups (study_id, group_id, is_deployed) FROM stdin WITH (FORMAT csv);\n")
        writer = csv.writer(out, lineterminator="\n")
        for study_id, deployment in sorted(groups):
            writer.writerow([study_id, deployment, "true"])
        out.write("\\.\n")

        out.write("COPY data_stream_ids (id, study_deployment_id, device_role_name, name_space, name) FROM stdin WITH (FORMAT csv);\n")
        ids = {key: index for index, key in enumerate(sorted(streams), start=1)}
        for key, index in ids.items():
            writer.writerow([index, *key])
        out.write("\\.\n")

        out.write("COPY data_stream_sequence (id, data_stream_id, first_sequence_id, last_sequence_id, snapshot, created_at) FROM stdin WITH (FORMAT csv);\n")
        row = 0
        for key, index in ids.items():
            # An export can hold one sequence id twice, from a re-upload. The
            # database keeps one; keep the first, or sequences would overlap.
            items = list({item[0]: item for item in reversed(streams[key])}.values())
            items.sort(key=lambda item: item[0])
            for sequence in sequences(items):
                row += 1
                times = [json.loads(m)["sensorStartTime"] for _, m, _, _ in sequence]
                snapshot = ('{"measurements":[' + ",".join(m for _, m, _, _ in sequence) + '],'
                            f'"triggerIds":{json.dumps(list(sequence[0][2]))},'
                            f'"syncPoint":{sequence[0][3]}}}')
                arrived = datetime.fromtimestamp(max(times) / 1e6, tz=timezone.utc).replace(tzinfo=None)
                writer.writerow([row, index, sequence[0][0], sequence[-1][0], snapshot, arrived.isoformat(sep=" ")])
        out.write("\\.\n")
        out.write(INDEXES)

    print(f"{len(groups)} deployments, {len(ids)} streams, {row} sequences -> {args.out}")


if __name__ == "__main__":
    main()
