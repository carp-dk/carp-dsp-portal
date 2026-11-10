# UbiLife test tools

The UbiLife workflow reads the CARP database, which is only reachable from
production. These make a local stand-in from study exports.

| Tool | Does |
|---|---|
| `make_2026.py` | A 2025 export as the 2026 study would have sent it: dates +364 days, new deployment ids, `Smartphone`, `stepevent`, no keynote 2. `--steps-factor` and `--stress-offset` put in a known shift |
| `make_test_db.py` | A seed for a local Postgres with the four tables the workflow's query reads, from one or more exports. Takes the same two shift options, for the `--study-2026` exports |

## A local test database

```sh
cd demo-bundles/tools/ubilife
pip install ijson
python make_test_db.py --out test-db/seed.sql.gz \
    --study b3607581-56c6-4652-a4f6-8e86e5a4e7e5 <2025-export.zip> \
    --study-2026 ba1834de-8caf-4e0e-9ec3-8db05285777f <2025-export.zip>
cd ../../..
docker compose --profile test-db up -d
```

Then, on the portal's Settings page:

- `CARP_DSP_SQL_URL` = `jdbc:postgresql://test-db:5432/carp`
- `CARP_DSP_SQL_USER` = `carp`, `CARP_DSP_SQL_PASSWORD` = `carp`
- `JAVA_TOOL_OPTIONS` = `-Xmx4g` - the decoder holds a study in memory; about
  3 GB for a million measurements.

`Test SQL connection` should answer. The seed is gitignored; the 2025 export is
on Zenodo (record 19220274).

## Checking the comparison

`ubilife-compare` should find what was put in, and nothing else.

1. Seed as above (no shift) and run it: all 16 measures "same".
2. Reseed with a shift, and start the database afresh (`rm -v` drops only its data; the seed loads on an empty database):

   ```sh
   python make_test_db.py --out test-db/seed.sql.gz \
       --study b3607581-56c6-4652-a4f6-8e86e5a4e7e5 <2025-export.zip> \
       --study-2026 ba1834de-8caf-4e0e-9ec3-8db05285777f <2025-export.zip> \
       --steps-factor 1.5 --stress-offset 2
   docker compose --profile test-db rm -sfv test-db
   docker compose --profile test-db up -d test-db
   ```

   Steps "shifted (small)" (d ≈ 0.24), stress "shifted (medium)" (d ≈ 0.46),
   the other 14 "same".

## Replaying the conference now

To watch the live comparison take in data as it arrives, seed the 2026 study with
`--day-one` set to yesterday. Its data then arrives in real time, from its second
day on, as if the conference were running. Use a copy of `ubilife-compare-live`
whose 2026 dates match (`replay/ubilife-live-replay.zip` moves them to 6-10 Oct;
`replay/ubilife-accumulation-replay.zip` does the same for `ubilife-accumulation`).

After any reseed, clear the query store, or `--incremental` keeps rows the old
database returned: `docker compose exec dsp-portal rm -rf /state/query-store`
(`/root/.carp-dsp/query-store` on images before 7 Oct).

## Notes

- An export has no arrival time, so `created_at` is the last measurement's time
  in each sequence.
- An export can hold one sequence id twice, from a re-upload. The seed keeps the
  first. The published 2025 counts include those duplicates (6,474 of 1,033,274
  measurements), so a database run of 2025 counts slightly fewer rows.
