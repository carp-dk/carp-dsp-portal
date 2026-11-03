"""Replace one study's data in the running test database with a real export.

Writes SQL that deletes the study's rows and loads the export in their place,
leaving every other study as it is. Load it with psql:

    python replace_study.py --study ba1834de-8caf-4e0e-9ec3-8db05285777f \\
        --export <export folder or zip> --out replace.sql
    docker cp replace.sql carp-dsp-portal-test-db-1:/tmp/
    docker exec carp-dsp-portal-test-db-1 psql -U carp -d carp -v ON_ERROR_STOP=1 -f /tmp/replace.sql

Rows are numbered from fixed offsets, above the seed's, so no id clashes. As in
make_test_db.py, `created_at` is the last measurement's time in each sequence.
"""

import argparse
import csv
import json
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

import make_test_db as seed

STREAM_ID_OFFSET = 1_000_000
SEQUENCE_ID_OFFSET = 1_000_000_000


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--study", required=True)
    ap.add_argument("--export", required=True, help="export zip, folder, or its data-streams.json")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    export = Path(args.export)
    if export.is_dir():
        export = export / "data-streams.json"

    streams = defaultdict(list)
    deployments = set()
    for record in seed.records(export, reshape=False):
        data_type = record["dataStream"]["dataType"]
        key = (record["studyDeploymentId"], record["deviceRoleName"], data_type["namespace"], data_type["name"])
        measurement = {k: v for k, v in record["measurement"].items() if v is not None}
        streams[key].append((
            int(record["sequenceId"]),
            json.dumps(measurement, separators=(",", ":"), ensure_ascii=False),
            tuple(record.get("triggerIds") or []),
            json.dumps(seed.sync_point(record), separators=(",", ":")),
        ))
        deployments.add(record["studyDeploymentId"])

    study = args.study
    with open(args.out, "w", encoding="utf-8", newline="") as out:
        out.write("BEGIN;\n")
        out.write(f"""
DELETE FROM data_stream_sequence WHERE data_stream_id IN (
    SELECT i.id FROM data_stream_ids i
    JOIN recruitment_participant_groups g ON g.group_id = i.study_deployment_id
    WHERE g.study_id = '{study}');
DELETE FROM data_stream_ids WHERE study_deployment_id IN (
    SELECT group_id FROM recruitment_participant_groups WHERE study_id = '{study}');
DELETE FROM recruitment_participant_groups WHERE study_id = '{study}';
""")
        writer = csv.writer(out, lineterminator="\n")
        out.write("COPY recruitment_participant_groups (study_id, group_id, is_deployed) FROM stdin WITH (FORMAT csv);\n")
        for deployment in sorted(deployments):
            writer.writerow([study, deployment, "true"])
        out.write("\\.\n")

        out.write("COPY data_stream_ids (id, study_deployment_id, device_role_name, name_space, name) FROM stdin WITH (FORMAT csv);\n")
        ids = {key: STREAM_ID_OFFSET + n for n, key in enumerate(sorted(streams), start=1)}
        for key, index in ids.items():
            writer.writerow([index, *key])
        out.write("\\.\n")

        out.write("COPY data_stream_sequence (id, data_stream_id, first_sequence_id, last_sequence_id, snapshot, created_at) FROM stdin WITH (FORMAT csv);\n")
        row = SEQUENCE_ID_OFFSET
        for key, index in ids.items():
            # One sequence id twice, from a re-upload: keep the first.
            items = list({item[0]: item for item in reversed(streams[key])}.values())
            items.sort(key=lambda item: item[0])
            for sequence in seed.sequences(items):
                row += 1
                times = [json.loads(m)["sensorStartTime"] for _, m, _, _ in sequence]
                snapshot = ('{"measurements":[' + ",".join(m for _, m, _, _ in sequence) + '],'
                            f'"triggerIds":{json.dumps(list(sequence[0][2]))},'
                            f'"syncPoint":{sequence[0][3]}}}')
                arrived = datetime.fromtimestamp(max(times) / 1e6, tz=timezone.utc).replace(tzinfo=None)
                writer.writerow([row, index, sequence[0][0], sequence[-1][0], snapshot, arrived.isoformat(sep=" ")])
        out.write("\\.\n")
        out.write("COMMIT;\nANALYZE;\n")

    by_type = defaultdict(int)
    for (_, _, _, name), items in streams.items():
        by_type[name] += len(items)
    print(f"{len(deployments)} deployments, {len(ids)} streams, {row - SEQUENCE_ID_OFFSET} sequences -> {args.out}")
    print("measurements by type: " + ", ".join(f"{k} {v:,}" for k, v in sorted(by_type.items(), key=lambda kv: -kv[1])))


if __name__ == "__main__":
    main()
