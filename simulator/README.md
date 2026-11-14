# Meter simulator

Spring Boot app that simulates a fleet of smart meters. Each meter POSTs batches of
15-minute readings to FileGrid, with a configurable rate and anomalies you can
switch on or off.

```sh
make seed                                   # generate the fleet and register it in filegrid.meter
make sim                                    # normal day, sends to http://localhost:8080
make sim SIM_ARGS=--spring.profiles.active=peak    # 10x volume
make sim SIM_ARGS="--spring.profiles.active=clean --simulator.meters.count=50000"
```

`make seed` and `make sim` must get the same `SIM_ARGS` for `seed` and `meters.*`. Both
log the fleet fingerprint, so you can check they match. Without Docker:
`cd simulator && mvn spring-boot:run -Dspring-boot.run.arguments=--simulator.sink.type=stdout`.

## Message

`POST /api/v1/meter-readings`. Headers: `X-API-Key` (shared key, `FILEGRID_METER_API_KEY`),
`Idempotency-Key` (= `readingId`) and `traceparent` (W3C).

```json
{
  "readingId": "5b1f6c1e-8a50-4f0e-9d0a-2f6f3f6f1a11",
  "meterId": "MTR-B-0004211",
  "sequence": 18342,
  "region": "BANAT",
  "measuredAt": "2026-11-14T10:00:00Z",
  "intervalMinutes": 15,
  "firmware": "2.4.1",
  "values": [
    { "at": "2026-11-14T09:15:00Z", "activeEnergyKwh": 0.412, "registerKwh": 12000.412, "voltageV": 229.8 },
    { "at": "2026-11-14T09:30:00Z", "activeEnergyKwh": 0.398, "registerKwh": 12000.810, "voltageV": 230.4 }
  ]
}
```

- `at` is the start of the interval. `measuredAt` is the end of the window the message covers.
- `registerKwh` is the cumulative meter index. If an interval is missing, its energy can still be estimated from the jump in the register.
- `sequence` increases by 1 per message, per meter.
- Load follows daily and seasonal curves for household, commercial and industrial customers.

**Retries.** Responses `2xx` and `409` count as delivered, other `4xx` as rejected (no retry).
Network errors, `408`, `429` and `5xx` are retried with exponential backoff, using the same
body and the same `readingId`, honouring `Retry-After`. By default it retries until the
message is delivered. A meter that falls behind (FileGrid down, paused) sends its backlog
in larger messages, up to 480 intervals (≈45 KB) each.

## Rate

Messages/s ≈ `meters.count × clock.speed / (avg batch × 900 s)`. The startup log prints the estimate.

| Setting | Default | |
|---|---|---|
| `simulator.meters.count` | 10000 | Fleet size (re-run `make seed`) |
| `simulator.reporting.batch-min` / `batch-max` | 1 / 8 | Intervals per message, random per message |
| `simulator.clock.speed` | 1 | Simulated seconds per real second |
| `simulator.clock.start` | now | `now`, ISO instant, or offset such as `-P1D` |
| `simulator.sink.max-in-flight` | 64 | Concurrent requests |

The simulated clock never runs ahead of real time (`clock.allow-future=false`): after
catching up, it continues at 1x. The `peak` profile replays the last 24 h at 10x, which
gives about 2 h 40 min of peak load.

Profiles: `peak`, `clean` (no anomalies), `seed`.

## Anomalies

Each one has a per-message probability under `simulator.anomalies.rates.<name>`. Delays for
`late` / `duplicate` / `resent-intervals` go up to `anomalies.max-delay` (2 h, simulated time).

| Name | Default | Effect |
|---|---|---|
| `duplicate` | 0.02 | Same message (same id, same bytes) sent again later |
| `race-duplicate` | 0.005 | Same message sent twice concurrently |
| `resent-intervals` | 0.005 | Same intervals, new `readingId` and `sequence` |
| `late` | 0.02 | Held back, arrives out of order with past timestamps |
| `future-timestamp` | 0.002 | All timestamps +1–48 h (clock drift) |
| `misaligned-timestamp` | 0.005 | Timestamps off the 15-min grid by ≤ 2 min |
| `gap` | 0.02 | Some intervals missing from the batch |
| `sequence-reset` | 0.001 | Meter reboot: sequence restarts at 1 |
| `malformed` | 0.005 | Truncated JSON, missing field, wrong type, or empty `values` |
| `invalid-value` | 0.003 | Negative energy or register rollback |
| `unknown-meter` | 0.002 | Meter id not in the seed (`MTR-x-9xxxxxx`) |
| `wrong-region` | 0.002 | `region` does not match the meter |
| `voltage-out-of-range` | 0.01 | Sag (180–206 V) or swell (254–270 V), valid data |
| `consumption-spike` | 0.003 | One interval 20–50× normal, valid data |

## Runtime control and observability (port 8090)

```sh
curl localhost:8090/api/control                                   # state + rates
curl -X PUT localhost:8090/api/control -H 'Content-Type: application/json' -d '{"speed": 10}'
curl -X PUT localhost:8090/api/control -H 'Content-Type: application/json' -d '{"paused": true}'
curl -X PUT localhost:8090/api/control/anomalies -H 'Content-Type: application/json' -d '{"DUPLICATE": 0.3}'
curl localhost:8090/actuator/prometheus | grep ^simulator_
```

Pausing is like taking the meters offline: they keep measuring and send the backlog when resumed.

**Ledger.** Set `--simulator.ledger.path=ledger.jsonl` to write one JSON line per message:
`readingId`, `kind` (ORIGINAL / DUPLICATE / RACE_DUPLICATE / RESENT), `copyOf`, intervals,
anomalies, outcome, HTTP status, attempts and trace id. Compare it with the database to prove
that no reading was lost or counted twice.
