# Performance

Measured with the Gatling simulations in [`loadtest/`](../loadtest). Re-run them yourself; numbers from
your hardware are more meaningful than these.

## Results

**Setup:** one API instance (Java 21, `-Xmx1g`, default G1 GC) on PostgreSQL 16, demo data (7 flags incl.
rules, segments, a prerequisite and percentage rollouts). The load generator runs **on the same 4-vCPU cloud
container** as the server, so it competes for CPU (see [caveats](#caveats)). JVM warmed up with a 30 s run first.

**Traffic:** open workload model (requests arrive at a fixed rate whether or not earlier ones have finished),
80% *evaluate all flags* / 20% *evaluate one flag*, contexts drawn from 100,000 distinct users with random
attributes, so bucketing and rule matching do real work. 60 s at each rate.

### Steady state

| Arrival rate | Requests | Errors | p50 | p95 | p99 | max |
|---:|---:|---:|---:|---:|---:|---:|
| 500 req/s | 32,510 | 0 | 1 ms | 2 ms | 4 ms | 282 ms |
| 1,000 req/s | 65,010 | 0 | 1 ms | 2 ms | 7 ms | 114 ms |
| 2,000 req/s | 130,010 | 0 | 1 ms | 3 ms | 19 ms | 274 ms |
| 4,000 req/s | 260,010 | 0 | 1 ms | 25 ms | 71 ms | 1,103 ms |

At 4,000 req/s the container was fully saturated (0% idle): the server used about **2.3 cores** and Gatling
about **1.5 cores**. Latency starts bending between 2k and 4k req/s, but that knee reflects this shared box
rather than a limit of the server alone.

### Where the time goes

The server times every evaluation batch (`ffp_evaluation_batch_seconds`, exported to Prometheus):

| Evaluate all 7 flags for one user, in-process | |
|---|---|
| p50 | **25 µs** |
| p95 | 122 µs |
| p99 | 344 µs |
| mean | 56 µs (561,090 batches) |

So evaluation is a few percent of a ~1 ms request; the rest is HTTP, JSON serialisation and the security
filter chain. That is the case for SDK-side evaluation, now implemented: `LocalFlagClient` evaluates in-process
at **~2.4 µs per flag** in Node.js (`sdk/js/examples/differential-check.mjs`), roughly 400× faster than a
round trip to even a local server, with no dependency on the platform being up.

### Changes under load

`ChangesUnderLoadSimulation`: 1,000 req/s of SDK traffic while an admin toggles a flag every 2 s. Every toggle
commits, invalidates the environment snapshot and forces a rebuild from PostgreSQL while reads continue.

| Request | Count | Errors | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|---:|---:|
| SDK: evaluate all flags | 52,005 | 0 | 1 ms | 2 ms | 9 ms | 128 ms |
| SDK: evaluate one flag | 13,005 | 0 | 1 ms | 2 ms | 10 ms | 127 ms |
| Admin: toggle flag | 30 | 0 | 30 ms | 54 ms | 224 ms | 224 ms |

Compared with steady state at the same rate, SDK p99 moves from 7 ms to 9–10 ms: rebuilding a snapshot every
2 s is cheap, and the generation-checked cache never serves an error or a half-built snapshot. (The run's
overall max of 719 ms was the single admin login: BCrypt hashing is deliberately slow.)

## Caveats

* **Shared CPU.** Generator and server shared 4 vCPUs; at the top rate Gatling took ~40% of the machine.
  Run the generator on a separate host to find the server's real ceiling.
* **Single instance, local database.** No network hop to PostgreSQL. Evaluation never touches the database
  except to rebuild a snapshot after a change, so this mainly affects the toggle numbers.
* **Tail outliers (100–280 ms max at moderate rates) were not investigated.** GC pauses or JIT
  deoptimisation are the usual suspects; GC logs (`-Xlog:gc*`) are the next step.
* **Cloud container.** Some steal time (`st`) was visible; expect run-to-run variance of a few ms at p99.

## Reproduce

```bash
# 1. Start the API (PostgreSQL recommended for the changes-under-load test)
cd backend && mvn package -DskipTests
FFP_DB_URL=jdbc:postgresql://localhost:5432/ffp FFP_DB_USER=ffp FFP_DB_PASSWORD=ffp java -Xmx1g -jar target/*.jar

# 2. Warm up, then measure (from another terminal / machine)
cd loadtest
mvn gatling:test -Dsimulation=ffp.loadtest.SdkEvaluationSimulation -Drps=1000 -Dduration=30   # warm-up
mvn gatling:test -Dsimulation=ffp.loadtest.SdkEvaluationSimulation -Drps=2000 -Dduration=60
mvn gatling:test -Dsimulation=ffp.loadtest.ChangesUnderLoadSimulation -Drps=1000 -Dduration=60
# HTML report: loadtest/target/gatling/<simulation>-<timestamp>/index.html
```

Knobs (all `-D`): `baseUrl`, `sdkKey`, `rps`, `duration`, `rampSeconds`, `p95Ms`, `p99Ms` (the pass/fail
thresholds; the build fails if they're exceeded, so the simulations double as a performance regression check),
`adminEmail`, `adminPassword`, `project`, `environment`.

There is also a manually triggered **Load test** workflow in GitHub Actions that boots the stack and runs a
simulation, uploading the HTML report as an artifact.
