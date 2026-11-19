# Final powered soak — measured summary

This run supersedes the invalid `20261118-210215` run (1 h 29 m, power unplugged, no report).

**Status: PARTIAL — not a completed 24 h soak.** The device-reported
`soak_report.json` records `completed_24h: false`. The run was stopped at the
user's instruction about 7 minutes before the 24 h target, so no full-day PASS
is claimed.

| Field | Measured value |
| --- | --- |
| Started (first sample, UTC) | 2026-11-18T15:57:08.969Z |
| Ended (last sample, UTC) | 2026-11-19T15:50:24.749Z |
| Report `started_utc` / `ended_utc` | 2026-11-18T15:57:08.094Z / 2026-11-19T15:50:25.300Z |
| Report `duration_s` | 85997 (23 h 53 m 17 s) |
| Sample count | 17,072 |
| Maximum inter-sample gap | 6.76 s (0 gaps > 90 s) |
| `charging` in all samples | `true` |
| `thermal` in all samples | `NONE` |
| `governor` in all samples | `NORMAL` |
| Battery level range | 80–100 % |
| Battery temperature range | 21.6–31.1 °C |
| Service restarts | 1 → 1 (no restart during run) |
| Dropped frames | 3 |
| Alerts sent | 93 |
| Queue size at stop | 0 |
| `completed_24h` | false |

Source files: `soak_report.json` (device-generated), `soak_samples.csv`
(17,072 data rows), `battery_log.csv`. Pulled with `../scripts/soak.sh --stop`
at 2026-11-19T15:50Z.
