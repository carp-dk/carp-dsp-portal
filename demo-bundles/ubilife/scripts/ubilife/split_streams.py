"""Split the measurement table into one table per data stream.

Reads the measurement and provenance tables (from core.io.decode-carp-data-streams
or read_export.py) and writes, for each requested stream, a table with
participant_id, device_role_name, timestamp (microseconds) and that stream's
fields, without their prefix.

    --stream stepcount output.0 --stream survey output.1

A requested stream with no rows is written with its header only, and named on
stdout, so a missing stream is visible without failing the run.
"""

import argparse

import pandas as pd

CARP_PREFIX = "dk.cachet.carp."
CHUNK_ROWS = 200_000


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--measurements", required=True)
    ap.add_argument("--provenance", required=True)
    ap.add_argument("--stream", nargs=2, action="append", required=True,
                    metavar=("TYPE", "OUTPUT"),
                    help="data type without dk.cachet.carp., and where to write it")
    args = ap.parse_args()
    streams = dict(args.stream)

    provenance = pd.read_csv(
        args.provenance, dtype=str, keep_default_na=False,
        usecols=["row_id", "study_deployment_id", "device_role_name"],
    ).rename(columns={"study_deployment_id": "participant_id"}).set_index("row_id")

    parts = {name: [] for name in streams}
    for chunk in pd.read_csv(args.measurements, dtype=str, keep_default_na=False,
                             chunksize=CHUNK_ROWS):
        short = chunk["data_type"].str.removeprefix(CARP_PREFIX)
        for name in streams:
            rows = chunk[short == name]
            if rows.empty:
                continue
            prefix = f"{name}."
            columns = [c for c in rows.columns if c.startswith(prefix)]
            part = rows[["row_id", "sensor_start_time"] + columns].rename(
                columns={c: c[len(prefix):] for c in columns} | {"sensor_start_time": "timestamp"})
            # A field this stream never fills is a column of another type's
            # making; the prefix is unique, so an all-empty one is just absent.
            part = part.loc[:, (part != "").any(axis=0) | part.columns.isin(["row_id", "timestamp"])]
            parts[name].append(part)

    for name, path in streams.items():
        if not parts[name]:
            print(f"{name}: no rows")
            pd.DataFrame(columns=["participant_id", "device_role_name", "timestamp"]).to_csv(path, index=False)
            continue
        table = pd.concat(parts[name], ignore_index=True).fillna("")
        table = table.join(provenance, on="row_id")
        front = ["participant_id", "device_role_name", "timestamp"]
        table = table[front + [c for c in table.columns if c not in front + ["row_id"]]]
        table.to_csv(path, index=False)
        print(f"{name}: {len(table):,} rows, {table['participant_id'].nunique()} participants")


if __name__ == "__main__":
    main()
