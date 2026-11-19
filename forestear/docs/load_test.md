# Backend load test

`scripts/load_test.py` simulates N edge nodes posting heartbeats and events to
a running backend and records measured latency and throughput. It is a
measurement tool, not a capacity promise.

## How to run

Start the backend against a throwaway database so demo data is untouched
(`FORESTEAR_DB` is read by `backend/models.py`):

```bash
cd forestear
rm -f /tmp/forestear_load.db
FORESTEAR_DB=/tmp/forestear_load.db ../.venv/bin/python -m backend.main --port 8765
```

In a second terminal:

```bash
cd forestear
../.venv/bin/python scripts/load_test.py \
  --url http://127.0.0.1:8765 \
  --nodes 20 --duration 20 --rate 0.5 --event-ratio 0.2 \
  --out reports/load_test.json
```

`--rate` is requests per second **per node**. The backend rate limit is 120
requests/minute per node (2/s), so `--rate 2` sits exactly at the cap.

Each simulated node is identified as `<prefix>-NNN` (default `load-000` …), so
its rows are easy to recognise or delete from a scratch DB.

## What is measured

- request count, ok/failed count, status-code histogram
- wall-clock throughput (requests/second actually completed)
- client-observed latency: mean, p50, p95, p99, max
- up to five error strings

The script exits non-zero if every request failed, and writes the same JSON it
prints. It does not assert thresholds and does not print PASS.

## Measured on this machine (2026-11-16)

Host: Apple M1, 8 GB RAM, macOS. Backend: single Uvicorn process, SQLite at
`/tmp/forestear_load.db`, default config, dry-run Telegram. Client and server
on the same laptop (loopback), so these numbers exclude Wi-Fi and WAN.

| Run | Nodes | Per-node rate | Requests | Failed | Throughput | p50 | p95 | p99 | Max |
|---|---|---|---|---|---|---|---|---|---|
| `reports/load_test.json` | 20 | 0.5/s | 200 | 0 | 9.99 req/s | 42.7 ms | 110.5 ms | 141.1 ms | 163.3 ms |
| `reports/load_test_high.json` | 20 | 2.0/s | 760 | 0 | 37.98 req/s | 45.8 ms | 119.4 ms | 151.8 ms | 296.5 ms |

Both runs: zero HTTP errors, all responses 200. At the higher rate the mean
latency barely moved while p99 and max grew, which is the expected queueing
behaviour of a single process.

## Honest limits

- This is one laptop, one backend process, loopback networking. It says nothing
  about a cloud VM, TLS termination, or many days of operation.
- SQLite serialises writes. A production deployment with many nodes needs
  Postgres and a migration; that is not implemented here.
- The load test sends synthetic payloads, not real phone telemetry. It does not
  measure the phone side at all.
- Rate limiting is in-process memory; a multi-worker Uvicorn deployment would
  enforce it per worker, not globally.
- No soak test (hours/days) has been run.
