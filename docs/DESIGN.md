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
* Invalidation happens in a `@TransactionalEventListener(AFTER_COMMIT)`: invalidating before commit would let
  a concurrent reader rebuild from the *old* data; invalidating on rollback would be wasteful. The loader uses
  `REQUIRES_NEW` because it may run inside that after-commit callback.

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

* `ffp.evaluations{environment,flag,reason}` counter, `ffp.evaluation.batch` timer with p50/p95/p99,
  `ffp.stream.connections` gauge — exposed at `/actuator/prometheus`.
* Per-flag, per-variation evaluation counts and "last evaluated" are kept in-process with `LongAdder`
  (contention-free on the hot path) and shown in the dashboard. Combined with the `temporary` flag and last
  change time, they drive the **"Stale?"** badge — flags are technical debt with an expiry date.

## 8. Scaling out — what would change

The current build is a single deployable that scales vertically comfortably (evaluation is an in-memory map
lookup plus a few comparisons). For horizontal scale:

| Concern | Today | At scale |
|---|---|---|
| Cache invalidation across instances | In-process event after commit | Publish the same `EnvironmentsChangedEvent` over PostgreSQL `LISTEN/NOTIFY` or Redis pub/sub; every instance runs the existing `ChangePropagator`. Nothing else changes. |
| SSE connections | Held by API instances | Separate stateless "relay" tier or a managed pub/sub edge; streams already recycle every 30 min to rebalance. |
| Evaluation latency | One HTTP call per context | Ship rulesets to server-side SDKs and evaluate locally (the engine is pure and portable; MurmurHash3 is a cross-language contract) — zero network on the hot path. |
| Evaluation stats | In-memory per instance | Batch to a time-series store / Kafka for experiment analysis. |
| Audit log | Same database | Partition by month; ship to the data warehouse. |
| SDK abuse | — | Rate-limit per SDK key at the gateway. |

## 9. Things deliberately left out

Scheduled flag changes, approval workflows for production, multi-tenant organisations, SSO, and
client-side (browser) SDK keys that expose only non-sensitive flags. The architecture accommodates each
(e.g. approvals = a pending-change table that `applyTargeting` consumes), but they weren't needed to show
the core.
