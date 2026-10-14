# Design notes

This document explains the main design decisions, the alternatives considered, and what would change at
larger scale. It's written to be read alongside the code.

## 1. Domain model

```
Project ──< Environment            (development / staging / production …, each with its own SDK key)
   │
   ├──< FeatureFlag                 key, type, variations[], tags, temporary?, salt   (environment-independent)
   │        └──< FlagEnvironmentConfig   enabled + Targeting (JSON) + @Version       (one per flag × environment)
   │
   └──< Segment                     included / excluded keys + rules                (reusable audience)

AuditEntry                          append-only; no FKs so history survives deletions
```

* **Variations are referenced by index.** Rules say "serve variation 1", not "serve `false`", so changing a
  JSON payload doesn't require touching any targeting, and the evaluation result can report a stable
  variation index for experiment analysis.
* **Targeting is stored as one JSON document per flag × environment.** It is always read and written as a
  unit (the editor saves the whole thing; evaluation loads the whole thing), so normalising rules/clauses into
  five tables would add joins and write amplification for no query benefit. Queries that *do* need to look
  inside targeting ("which flags use segment X?") are rare admin operations done in memory per project.
  Tags, which *are* filtered on, live in a proper table with an index.
* **Flag definition vs. per-environment state** are separate aggregates with separate optimistic-lock
  versions, so renaming a flag never conflicts with someone editing production targeting.

## 2. Evaluation

The engine (`evaluation/engine`) is plain Java with no Spring, no I/O and immutable inputs, which makes it
trivially unit-testable and safe to share across threads.

Order: **off → prerequisites → individual targets → rules (first match) → default rule.**
Every result carries a `reason` (`RULE_MATCH` + rule id/index, `FALLTHROUGH` + `inRollout`, …) because
"why am I seeing this?" is the most common support question for any flag system, and experiment analysis
needs to know whether a user was *bucketed* or *targeted*.

Robustness rules:
* Bad context data never throws: `GREATER_THAN` on a non-number simply doesn't match.
* A missing attribute doesn't match — **even for negated clauses** — so `country NOT IN [US]` doesn't
  silently include users whose country is unknown.
* Prerequisite cycles are rejected on write (DFS over the environment's prerequisite graph) *and* guarded
  on read with a visiting set, so a bug can't cause a stack overflow.
* Size limits (rules, clauses, values, targets) bound worst-case evaluation cost; large lists belong in segments.

### Percentage rollouts

`bucket = murmur3_32(flagKey + "." + salt + "." + bucketValue) mod 100000`

* **Sticky**: same user → same bucket on every server and every SDK, forever.
* **Monotonic ramp-ups**: variations take cumulative bucket ranges, so going from 10% → 20% only *adds* users.
  Users who already had the feature never flip-flop.
* **Independent across flags**: the per-flag random salt means the same 10% of users aren't in every experiment.
* `String.hashCode()` was rejected: it's poorly distributed for sequential ids (`user-1`, `user-2`…) and
  isn't a cross-language contract. MurmurHash3 is fast, well-distributed and has reference vectors (tested).
* Weights are in thousandths of a percent (100 000 = 100%) to support very small canaries.
* `bucketBy` lets a rollout be per organisation (`orgId`) so all users of a company see the same thing.

## 3. Read path: snapshots and caching

Evaluation volume is orders of magnitude higher than write volume, so reads must not touch the database.

* `SnapshotLoader` builds an immutable `EnvironmentSnapshot` (all active flags + segments of one environment)
  with two queries.
* `SnapshotCache` holds one snapshot per environment and is **explicitly invalidated**, rather than TTL-based,
  so changes are visible immediately while steady-state reads are a hash-map lookup.
* **The race that matters**: a rebuild reads the DB, then a change commits and invalidates, then the rebuild
  finishes and would install a stale snapshot indefinitely. Each environment has a generation counter; a
  rebuild only installs its result if the generation is unchanged, checked atomically with the write inside
  `ConcurrentHashMap.compute`. `SnapshotCacheTest` reproduces the interleaving deterministically with latches.
* **Single flight.** Concurrent misses for one environment share one load: the first request loads, the rest
  wait on its `CompletableFuture`. Without it, every request that arrives during a rebuild starts its own:
  a freshly started instance hit by 400 concurrent requests rebuilt the same snapshot 110–127 times and
  queued on the connection pool (server-side max 1.7–1.8 s); with it, one rebuild (max ~0.8 s, the rest is
  JIT warm-up). The catch is freshness: a request that arrives *after* an invalidation must not join a load
  that started before it. Each in-flight load records the generation it started at, and a request only joins
  a load of the current generation; otherwise it starts its own. Tested: 200 concurrent misses → 1 load; a
  post-invalidation reader never receives the pre-invalidation read; a failed load fails its joiners and is
  not cached.
* Invalidation happens in a `@TransactionalEventListener(AFTER_COMMIT)`: invalidating before commit would let
  a concurrent reader rebuild from the *old* data; invalidating on rollback would be wasteful. The loader uses
  `REQUIRES_NEW` because it may run inside that after-commit callback.

### Keeping several instances in sync (`ffp.cluster.mode=redis`)

Each instance has its own snapshot cache, so a change made through instance A must also invalidate B's.

* **Apply locally, then broadcast.** After commit, `ChangePropagator` applies the change to its own instance
  (invalidate, notify its SSE clients, revoke SDK keys) and then publishes a `ClusterMessage` on a Redis
  channel. Other instances run the *same* `LocalChangeApplier`; each ignores messages carrying its own
  instance id. Applying locally first gives the person who made the change read-your-writes on that instance
  even if Redis is slow or down.
* **The message is a hint, not data.** It carries only environment ids ("this changed, reload it"); the
  database stays the single source of truth. That makes messages idempotent and order-insensitive, which is
  why plain pub/sub is enough — no Kafka or Redis Streams with offsets and consumer groups.
* **Pub/sub is at-most-once**, so losses are bounded, not prevented: snapshots also have a maximum age
  (`ffp.cluster.max-snapshot-age`, default 60 s). Normal propagation takes milliseconds; the worst case for
  a lost message is one max-age. Rebuilding each environment once a minute costs two queries.
* **SDK key revocation rides the same channel**, so a rotated key stops working on every instance
  immediately instead of after the per-instance resolver cache TTL.
* **Failure modes, all exercised by hand and in tests:**
  * *Redis down at startup* — the listener container would subscribe eagerly and fail the boot, so it is
    started by `ClusterSubscription` on a background thread with exponential backoff (1 s → 30 s). Instances
    boot and serve; evaluations never need Redis. (A failed `start()` leaves the container believing it is
    running, so every attempt resets it and checks `isListening()` before declaring success.)
  * *Redis restarts later* — the container resubscribes by itself (≈5 s).
  * *Publish fails* — logged and counted (`ffp.cluster.messages{outcome="publish_failed"}`), never thrown:
    the change is committed, so failing the request would be a lie. Command timeouts are 2 s because
    publishing runs on the request thread.
* **Alternatives considered.** PostgreSQL `LISTEN/NOTIFY` would avoid a second piece of infrastructure, but it
  needs a dedicated non-pooled connection and doesn't exist on H2; Redis is ubiquitous, and the broadcaster is
  an interface, so swapping transports is one class. Short TTLs alone (no messaging) were rejected because a
  kill switch that takes a minute to apply is not a kill switch.

## 4. Real-time updates (SSE)

SDKs keep one `GET /api/v1/sdk/stream` connection. On change, the server sends
`event: flags-changed` with the new snapshot version, and SDKs re-fetch their evaluations.

* **SSE over WebSockets**: one-directional server→client is all that's needed; SSE is plain HTTP (works
  through proxies and load balancers, auto-reconnect semantics), and Spring MVC supports it with `SseEmitter`.
* **Notify, don't push data**: evaluations are per user, so the server can't broadcast one payload anyway;
  a tiny "something changed" event keeps fan-out O(connections) and payload-free.
* Heartbeat comments every 25 s keep idle connections alive through proxies; streams are recycled every
  30 min, which naturally rebalances connections across instances.
* On (re)connect the server sends `ready` with the current version, so a client that missed events while
  disconnected catches up immediately.
* The SDK coalesces bursts (a busy editor saving repeatedly) into at most one follow-up request, and
  reconnects with exponential backoff and *full jitter* so a server restart doesn't cause a reconnect stampede.
* Virtual threads are enabled; `SseEmitter` is async, so idle streams don't pin request threads.

## 4a. Local evaluation and cross-language conformance

Evaluation takes ~25 µs on the server but a request takes ~1 ms, so for backend services the network *is* the
cost. `LocalFlagClient` downloads the environment's ruleset (`GET /api/v1/sdk/rules`) and evaluates in-process
(~2 µs per flag in Node), with the SSE stream triggering refreshes.

* **Two implementations of the same semantics are a correctness risk**, and the hard parts are exactly where
  languages differ: unsigned 32-bit hashing (`Math.imul`, `>>> 0`), UTF-8 encoding of non-ASCII keys, what
  counts as a number (`BigDecimal` rejects `0x10` and `Infinity`, JavaScript's `Number` accepts both), ISO
  timestamps without an offset, SemVer pre-release ordering, `null` attributes, numeric `bucketBy` values.
* **The fix is a shared, data-driven spec**: `conformance/evaluation-vectors.json`. Hand-written cases encode
  intended behaviour; hashes, buckets and rollout assignments are generated by the Java reference engine
  (`-Dconformance.regenerate=true` rewrites only cases marked `generated`). Both builds run all 176 cases with
  strict equality, reasons included. Any future SDK (Python, Go) gets a test suite for free.
* **Then verify against reality**: `examples/differential-check.mjs` evaluates every flag for 10,000 random
  users locally and via the server and diffs them (70,000 evaluations, 0 mismatches).
* **Conditional requests**: the ruleset's ETag is the snapshot version, so a reconnect or an event for an
  unchanged environment costs a `304` with no body. ETags are compared for equality only, never ordered,
  so they stay valid across instances with independent version counters.
* **Trade-offs**: the ruleset includes segment membership lists and flag salts, so it is only served to
  server-side SDK keys (browser SDKs keep using remote evaluation). Locally evaluated flags don't reach the
  server's evaluation counters; SDK-reported exposure events (see experimentation) close that gap.

## 4b. Experimentation

A flag with a percentage rollout is already a randomized controlled trial; the platform adds the measurement.

* **Data model.** SDKs send *exposures* (user saw variation v of flag f, and whether a rollout assigned it) and
  *custom events* (`track('purchase', user, 49.0)`). Exposures are stored once per user and flag, **first exposure
  wins** (intention-to-treat): if a rollout changes mid-test, users stay in their original arm. A conversion counts
  only if it happened **after** the user's exposure.
* **Only randomized users are analysed.** Users served by a targeting rule ("staff get the new checkout") were not
  randomly assigned; including them biases the comparison. They're excluded and their count is reported.
* **Statistics** (`experiment/stats`, no dependencies, every number tested against SciPy/statsmodels):
  Wilson score intervals per arm (well-behaved near 0% and for small n, unlike Wald); pooled two-proportion
  z-test and unpooled CI for conversion lift; Welch's t-test with Welch–Satterthwaite df for means (revenue per
  user doesn't have equal variances across arms); Bonferroni correction for A/B/n; and for inconclusive results
  the users per arm needed for 80% power, so "keep running" comes with a number.
* **Sample ratio mismatch** is the check that separates a toy from a real platform. If a 50/50 split delivers
  5,000 vs 5,500 users (χ² p = 1e-6), something drops or duplicates exposures for one arm — typically the new
  code path crashing before it logs — and *any* measured lift is suspect. The analysis runs a χ² goodness-of-fit
  test against the configured split (p < 0.001 → mismatch), and the UI replaces every verdict with
  "Untrusted (SRM)". The seeded `checkout-revenue` experiment plants exactly this bug (15% of treatment exposures
  lost) to show a highly "significant" lift being correctly distrusted.
* **Guardrails in the SDK.** Exposures are deduplicated in memory (a flag read on every request costs one
  event, not thousands), events are batched (≤1,000 per request), failed batches are re-queued with a bounded
  queue (oldest dropped and counted), invalid batches are not retried, and the flush timer is `unref`'d so
  analytics never keeps a Node process alive. Server-side, future timestamps are clamped and events older than
  7 days rejected so late data can't silently rewrite results people already acted on.
* **Trade-offs.** Results are computed on read with one aggregate query (sub-second at ~50k exposures). At
  scale this becomes an incremental pre-aggregation per (experiment, variation, day) or a columnar store fed by
  a stream; the analyzer only needs the per-arm sufficient statistics (n, conversions, Σx, Σx²), which is exactly
  what makes that migration easy. Peeking (re-checking a fixed-horizon test daily) inflates false positives;
  sequential testing (e.g. mSPRT) is the principled fix and the natural next step.

## 4c. Guarded progressive rollouts

A rollout ramps one variation through percentage steps under a guardrail metric (e.g. `error`) and decides on
its own: advance, hold, or roll back. It is the meeting point of everything above: bucketing, events,
statistics, cache invalidation, audit.

* **Monotonic ramp.** The rollout rewrites only the flag's default rule, with the treatment listed *first*, so
  it owns the lowest buckets: going from 10% to 25% only adds users. Nobody flips between versions mid-session.
* **Analysis per step.** The split changes at every step, so a cumulative comparison mixes populations
  assigned under different splits (and the SRM check would fire spuriously). Each step analyses only users
  *first exposed during that step*; within it, assignment follows one fixed split, so both the guardrail test
  and the sample-ratio check are valid. The guardrail is checked several times per step (interval/5, 2–60 s),
  so a regression is caught as soon as the evidence is significant, not at the end of the step.
* **Decision rules.** SRM → roll back (the assignment can't be trusted, so neither can the guardrail).
  Treatment significantly worse by the sequential test below → roll back. Otherwise advance once the step's
  time has elapsed *and* it has seen `minUsersPerStep` treatment users. Low traffic means waiting, not
  advancing blind. "Not significantly worse" is not proof of safety; the minimum-users gate and the
  multi-step ramp are what bound the risk.
* **Peeking, and why the guardrail uses a sequential test.** The first version ran a two-proportion z-test at
  α = 0.05 on every check. The live demo then rolled back a *healthy* release (p = 0.049). That was no fluke:
  a z-test is valid when run once, and checking it every few seconds gives it many chances to cross 0.05 by
  chance. Simulated A/A rollouts (no real difference) were rolled back 22% of the time with 15 checks, and
  50% with 240. The guardrail now uses a **mixture sequential probability ratio test** (Johari et al.,
  *Always Valid Inference*): at each check it computes the likelihood ratio Λ of "the rates differ" (effect
  drawn from N(0, τ²), τ = 0.1 σ) against "they're equal". Under the null Λ is a martingale, so by Ville's
  inequality it reaches 1/α with probability ≤ α *however often it is checked*. Each step is a fresh test on
  fresh users, so α is split across the guarded steps (Bonferroni); over a whole rollout, a healthy release
  is rolled back with probability ≤ α. Measured: 0.6–1.9% false rollbacks, with every simulated 2% → 8%
  regression still caught, and in the live demo within ~10 s. The cost is power for *small* effects (a z-test
  run once at p = 0.035 has sequential p = 0.39), which is the right trade for a guardrail: it exists to stop
  large regressions fast, not to measure small ones. `SequentialTestTest` reproduces the peeking problem and
  the fix; the dashboard shows the always-valid p-value next to the threshold it is compared against.
* **Many instances, one decision.** Every instance runs the scheduler. Each due rollout is claimed with
  `SELECT … FOR UPDATE SKIP LOCKED` inside the processing transaction, so exactly one instance processes it
  and the others move on instead of queueing. `@Version` on the rollout and optimistic locking on the flag
  config make a double-advance impossible even without the lock; a test fires four concurrent processors at
  one due rollout and asserts exactly one advance.
* **Humans win.** The rollout records the flag-config version it wrote. If someone edits the flag (or turns it
  off) mid-rollout, the next check sees a different version and *pauses* rather than overwriting their change;
  resuming re-applies the current step on top of their edit. Changes are made as `rollout-bot` through the
  normal flag service, so they're validated, audited and pushed to SDKs like any human change.
* **Time is a parameter.** `RolloutEngine.process(id, now)` takes the clock explicitly, so tests walk a
  rollout through an hour-long step in milliseconds, and the scheduler is just `process(id, clock.instant())`.

## 5. Write path: correctness and safety

* **Optimistic locking**: targeting writes must include the version the editor loaded. A mismatch returns
  409 naming who changed it; the UI offers "load latest". `@Version` additionally catches races between the
  check and the flush. Pessimistic locks were rejected: edits are long-lived UI sessions, not transactions.
* **Validation on write** (`TargetingValidator`) returns *all* problems with JSON-path-like locations
  (`rules[1].serve.rollout: weights must sum to 100000 (100%), got 120000`), so the UI can show them together.
  The frontend mirrors the common checks for instant feedback, but the server is the authority.
* **Referential integrity across JSON**: segments can't be deleted while referenced; flags can't be archived
  while they're another flag's prerequisite; variations can't be removed while targeting points at them.
* **Two-step delete**: archive (reversible; SDKs get their fallback) then delete (admin only).
* **Audit in the same transaction**: `AuditService.record` is `Propagation.MANDATORY`, so it is impossible to
  call it outside the transaction making the change — no change without its audit record and vice versa.
  Before/after state is stored as JSON, rendered as a line diff in the UI.

## 6. Security

* Two independent stateless filter chains: `/api/v1/sdk/**` (SDK key → environment principal) and `/api/**`
  (JWT bearer). An admin JWT is *not* accepted on SDK endpoints and vice versa.
* **SDK keys are stored hashed** (SHA-256). They carry 160 bits of entropy, so a fast unsalted hash is
  appropriate (no KDF needed) and lookup stays an indexed equality query. The raw key is shown once on
  creation/rotation. Resolved keys are cached for 60 s, which bounds how long a rotated key keeps working on
  *other* instances; unknown keys are never cached so random keys can't grow memory.
* Roles form a hierarchy (`ADMIN ⊃ EDITOR ⊃ VIEWER`) via `RoleHierarchy`, enforced with `@PreAuthorize`
  at controller level and mirrored in the UI to hide unavailable actions.
* JWTs are HS256 with issuer validation; the secret is mandatory under the `prod` profile.
  With an external IdP, swap the decoder for `issuer-uri` and keep the role mapping.

## 7. Observability

`docker compose up` starts Prometheus (scraping both API instances) and Grafana with the platform dashboard
provisioned from `observability/`.

* **Metrics.** `ffp.evaluations{environment,flag,reason}`, `ffp.stream.connections`, `ffp.cluster.messages
  {outcome}`, `ffp.snapshot.load` (one per cache miss) and `ffp.snapshot.invalidations`,
  `ffp.events.ingested{kind}`, `ffp.rollouts.decisions{outcome}`, plus HTTP, JVM and connection-pool metrics.
  Flag keys are bounded per project, so tagging by flag keeps cardinality under control; user keys never
  become labels.
* **Histograms, not client-side percentiles.** Latency is published as histogram buckets: percentiles
  computed inside each instance can't be averaged across instances, buckets can (`histogram_quantile` over
  the summed rates). The SLO boundaries (5–250 ms) are added as explicit buckets, so "share of evaluations
  under 50 ms" is an exact counter ratio, not an interpolation.
* **SLOs.** For the SDK API, the part applications depend on at runtime: 99.9% of requests succeed (5xx
  only; a 401 is the caller's problem) and 99% of evaluations complete within 50 ms. The SSE stream is
  long-lived and excluded from request SLOs; its health shows as connected streams per instance.
* **Burn-rate alerts** (Google SRE Workbook): page when the error budget burns 14.4× too fast over both 1 h
  and 5 min (2% of a 30-day budget in an hour), open a ticket at 6× over 6 h and 30 min. The long window
  avoids paging on a blip, and the short one makes the alert stop as soon as the problem does. Ratios are
  recording rules; `rules.test.yml` feeds synthetic series to `promtool` and checks that a 5% outage pages,
  0.5% does not, a spike that has already stopped does not, 4xx and SSE errors do not count, slow evaluations
  page, and an instance must be down for a full minute.
* **Rollouts on every graph.** Rollbacks and completions are Grafana annotations, so a latency or error
  change can be lined up against the release that caused it.
* Per-flag, per-variation evaluation counts and "last evaluated" are kept in-process with `LongAdder`
  (contention-free on the hot path) and shown in the dashboard. Combined with the `temporary` flag and last
  change time, they drive the **"Stale?"** badge — flags are technical debt with an expiry date.

## 8. Scaling out — what would change

The API is stateless apart from its caches, which stay coherent over Redis, so it scales horizontally behind
any load balancer without sticky sessions (`docker compose` runs two replicas behind nginx). Beyond that:

| Concern | Today | At scale |
|---|---|---|
| Cache invalidation across instances | **Done:** Redis pub/sub + max snapshot age (see §3) | At very large instance counts, put a relay tier in front so only a few processes subscribe. |
| SSE connections | Held by API instances | Separate stateless "relay" tier or a managed pub/sub edge; streams already recycle every 30 min to rebalance. |
| Evaluation latency | **Done:** `LocalFlagClient` evaluates in-process (§4a) | Edge/CDN-cached rulesets for browser SDKs with a client-safe subset. |
| Evaluation stats | In-memory per instance | Batch to a time-series store / Kafka for experiment analysis. |
| Audit log | Same database | Partition by month; ship to the data warehouse. |
| SDK abuse | — | Rate-limit per SDK key at the gateway. |

## 9. Things deliberately left out

Scheduled flag changes, approval workflows for production, multi-tenant organisations, SSO, and
client-side (browser) SDK keys that expose only non-sensitive flags. The architecture accommodates each
(e.g. approvals = a pending-change table that `applyTargeting` consumes), but they weren't needed to show
the core.
