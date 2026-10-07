"""Make a 2026-shaped study export from the 2025 one, for testing.

Takes the 2025 export and writes what the 2026 study would have sent, with last
year's participants:

- dates moved forward 364 days, so weekdays line up (13 Oct 2025, a Monday,
  becomes 12 Oct 2026, a Monday)
- new deployment ids, derived from the old ones
- the phone's role `Smartphone`, not `Primary Phone`
- `stepcount` as `stepevent`; `heartbeat` without its period, CAMS device types
- app-task completions as `completedapptask.<task type>`, as 2026 stores them
- keynote 2 dropped: the 2026 protocol has no keynote 2

Optional shifts give the comparison something known to find: `--steps-factor`
scales every step count, `--stress-offset` moves every stress answer.
"""

import argparse
import json
import sys
import uuid
from pathlib import Path

import ijson

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "ubilife-zenodo" / "scripts" / "ubilife"))
from read_export import open_export  # noqa: E402

OFFSET_US = 364 * 24 * 3600 * 1_000_000
NAMESPACE = uuid.UUID("2b8e4c1e-3f6a-4d6b-9a57-0f3c2d1e2026")
CORE_DEVICES = "dk.cachet.carp.common.application.devices."
CAMS_DEVICES = "dk.carp.cams.devices."


def new_id(old):
    return str(uuid.uuid5(NAMESPACE, old))


def role(name):
    return "Smartphone" if name == "Primary Phone" else name


def shift_stress(result, offset):
    step = (result.get("results") or {}).get("daily.stress.step")
    answers = (step or {}).get("results") or {}
    if "answer" in answers and isinstance(answers["answer"], (int, float)):
        answers["answer"] = min(10, max(0, answers["answer"] + offset))


def convert(record, steps_factor, stress_offset):
    """The record as 2026 would have sent it, or None when 2026 would not have."""
    stream = record["dataStream"]
    data_type = stream["dataType"]
    data = record["measurement"]["data"]
    name = data_type["name"]

    if name == "survey":
        identifier = ((data.get("result") or {}).get("identifier"))
        if identifier == "survey.keynote.2.task":
            return None
        if identifier == "daily.stress.task" and stress_offset:
            shift_stress(data["result"], stress_offset)

    if name == "stepcount":
        data_type["name"] = "stepevent"
        data["__type"] = "dk.cachet.carp.stepevent"
        if steps_factor != 1:
            data["steps"] = round(data["steps"] * steps_factor)
    elif name == "heartbeat":
        data.pop("period", None)
        data["deviceType"] = data.get("deviceType", "").replace(CORE_DEVICES, CAMS_DEVICES)
        data["deviceRoleName"] = role(data.get("deviceRoleName"))
    elif name == "completedapptask":
        task_type = data.get("taskType") or "task"
        data_type["namespace"] = "dk.cachet.carp.completedapptask"
        data_type["name"] = task_type
        data["__type"] = f"dk.cachet.carp.completedapptask.{task_type}"

    deployment = new_id(record["studyDeploymentId"])
    record["studyDeploymentId"] = stream["studyDeploymentId"] = deployment
    record["deviceRoleName"] = stream["deviceRoleName"] = role(record["deviceRoleName"])

    measurement = record["measurement"]
    for key in ("sensorStartTime", "sensorEndTime"):
        if measurement.get(key) is not None:
            measurement[key] = int(measurement[key]) + OFFSET_US
    return record


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--input", required=True, help="the 2025 data-streams.json, or its export zip")
    ap.add_argument("--output", required=True, help="2026-shaped data-streams.json")
    ap.add_argument("--steps-factor", type=float, default=1.0)
    ap.add_argument("--stress-offset", type=float, default=0.0)
    args = ap.parse_args()

    written = 0
    with open_export(args.input) as stream, open(args.output, "w", encoding="utf-8") as out:
        out.write("[")
        for record in ijson.items(stream, "item", use_float=True):
            converted = convert(record, args.steps_factor, args.stress_offset)
            if converted is None:
                continue
            out.write("," if written else "")
            out.write(json.dumps(converted, separators=(",", ":"), ensure_ascii=False))
            written += 1
        out.write("]")
    print(f"Wrote {written:,} measurements")


if __name__ == "__main__":
    main()
