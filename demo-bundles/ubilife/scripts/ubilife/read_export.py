"""Read a CARP study export into the measurement and provenance tables.

An export's data-streams.json holds one JSON object per measurement. This writes
the same two tables core.io.decode-carp-data-streams writes from the database,
so everything downstream reads one shape whichever way the data came in:

- measurements: row_id, data_type, sensor_start_time, sensor_end_time,
  duration_ms, then a `<type>.<field>` column per top-level data field. A nested
  field is one cell of JSON.
- provenance: row_id, study_deployment_id, device_role_name, data_type.

The input may be the JSON itself or the export's zip.
"""

import argparse
import csv
import io
import json
import zipfile
from contextlib import contextmanager

import ijson

FIXED = ["row_id", "data_type", "sensor_start_time", "sensor_end_time", "duration_ms"]
PROVENANCE = ["row_id", "study_deployment_id", "device_role_name", "data_type"]
MEMBER = "data-streams.json"
CARP_PREFIX = "dk.cachet.carp."


@contextmanager
def open_export(path):
    """Yields the data-streams.json stream, from the file or from inside a zip."""
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            names = [n for n in archive.namelist()
                     if n.endswith(MEMBER) and not n.startswith("__MACOSX")]
            if len(names) != 1:
                raise SystemExit(f"Expected one {MEMBER} in {path}, found {names}")
            with archive.open(names[0]) as stream:
                yield stream
    else:
        with open(path, "rb") as stream:
            yield stream


def records(path):
    with open_export(path) as stream:
        yield from ijson.items(stream, "item", use_float=True)


def type_of(record):
    data_type = record["dataStream"]["dataType"]
    return f'{data_type["namespace"]}.{data_type["name"]}'


def cell(value):
    """A scalar as written in JSON, a nested value as compact JSON, null as empty."""
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (dict, list)):
        return json.dumps(value, separators=(",", ":"), ensure_ascii=False)
    return str(value)


def prefix_of(data_type):
    """The data type without CARP's own namespace, as the decoder names columns."""
    return data_type.removeprefix(CARP_PREFIX)


def fields(record):
    """The measurement's top-level fields, keyed `<type>.<field>`."""
    prefix = prefix_of(type_of(record))
    data = record["measurement"].get("data") or {}
    return {f"{prefix}.{k}": cell(v) for k, v in data.items() if k != "__type"}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--input", required=True, help="data-streams.json, or the export zip")
    ap.add_argument("--measurements", required=True, help="measurement table (csv)")
    ap.add_argument("--provenance", required=True, help="provenance table (csv)")
    ap.add_argument("--types", default="",
                    help="data types to keep, without dk.cachet.carp., comma-separated (default: all)")
    args = ap.parse_args()
    keep = {t.strip() for t in args.types.split(",") if t.strip()}

    def wanted(record):
        return not keep or prefix_of(type_of(record)) in keep

    # Two passes over the stream: the columns are the union of the types present,
    # and that is only known at the end. Streaming twice is cheaper than holding a
    # million measurements in memory.
    columns = set()
    for record in records(args.input):
        if wanted(record):
            columns.update(fields(record))
    header = FIXED + sorted(columns)

    rows = 0
    with open(args.measurements, "w", newline="", encoding="utf-8") as m_out, \
            open(args.provenance, "w", newline="", encoding="utf-8") as p_out:
        m = csv.DictWriter(m_out, fieldnames=header, restval="")
        p = csv.writer(p_out)
        m.writeheader()
        p.writerow(PROVENANCE)
        for index, record in enumerate(records(args.input)):
            if not wanted(record):
                continue
            measurement = record["measurement"]
            data_type = type_of(record)
            row_id = str(index)
            m.writerow({
                "row_id": row_id,
                "data_type": data_type,
                "sensor_start_time": cell(measurement.get("sensorStartTime")),
                "sensor_end_time": cell(measurement.get("sensorEndTime")),
                "duration_ms": "",
                **fields(record),
            })
            p.writerow([row_id, record["studyDeploymentId"], record["deviceRoleName"], data_type])
            rows += 1

    if rows == 0:
        raise SystemExit("No measurements read. An empty table downstream is much harder to notice.")
    print(f"Read {rows:,} measurements, {len(columns)} value columns")


if __name__ == "__main__":
    main()
