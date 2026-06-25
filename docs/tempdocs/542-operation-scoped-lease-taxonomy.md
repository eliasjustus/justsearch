---
title: "542 — Operation-scoped lease taxonomy: extending the 271 ownership model"
type: tempdoc
status: done
created: 2026-05-21
category: multi-agent coordination / dev-runner / lease semantics
related:
  - docs/tempdocs/271-backend-lifecycle-isolation.md (foundation contract — done)
  - docs/observations.md item #158 (migration-start opens takeover window)
  - docs/observations.md item #162 (warned_takeover truncated a live migration)
  - scripts/dev/dev-runner.cjs (lease + admission machinery)
  - .claude/rules/branch-safety.md §Shared Dev Stack
---

# 542 — Operation-scoped lease taxonomy

> Pure design tempdoc. Status `design`. No code lands until the user approves
> the architecture below. Implementation slice is a separate follow-up.

## Motivation

On 2026-05-14 (recorded in `docs/observations.md` item #162), session
`b20c6cce…` was holding the shared dev stack and had initiated a Lucene
index migration via `POST /api/indexing/migration/start`. Session
`1eae32da…` invoked `start --takeover warn` against the same dev-runner.
The takeover succeeded against a freshly-renewed lease; the migration was
truncated mid-flight. The only evidence visible to the original holder was
`tmp/dev-runner/runs/<runId>/stop-report.json` with
`disposition: warned_takeover` — a post-mortem record, not a notification.

The instinctive read of the bug is "warn-takeover during migration is too
aggressive; add a guard for migrations." That framing is wrong. The same
failure exists for every long operation the backend hosts:

- Bulk reindex (minutes to hours).
- Large ingest batches (seconds to minutes).
- Schema migrations.
- Index GC.
- Eval runs initiated server-side.

What's missing is not a migration-specific guard. What's missing is the
concept that **operations have different criticality**, and that the lease
should reflect that — instead of being a uniform 30-second TTL that treats
a curl status-poll and a 30-minute reindex identically.

## Inherited contract from tempdoc 271

Tempdoc 271 (`backend-lifecycle-isolation`, status: done) is the foundational
multi-agent ownership-model design. Its 2026-03-11 closure stated three
contract clauses for the shared full-stack mode:

> *"later shared-stack starts remain possible; silent replacement is not
> acceptable; the current owner must be surfaced before takeover; the new
> start must record explicit takeover intent and outcome."*

The V1 implementation in `scripts/dev/dev-runner.cjs` honors clauses 1, 3,
and 4: takeover is possible, intent is recorded, outcome is recorded in
`stop-report.json`. Clause 2 — **surface the owner before takeover** — is
not implemented. `acquireAdmission` (line 624) logs to stderr that a
takeover is happening and proceeds. The current owner is never asked, never
notified at admission time, and only discovers the takeover by reading the
stop report after the fact.

So the bug is not new behavior. It is unfulfilled intent from a contract
that was finalized but only half-implemented.

**This tempdoc extends 271, it does not replace it.** Every existing field
on `active.json`, `run.json`, and `stop-report.json` is preserved. Every
existing disposition (`stale_reclaim`, `warned_takeover`, `forced_reclaim`)
keeps its current meaning for routine operations. The proposal adds a new
layer that operations explicitly opt into.

## Failure-mode walkthrough — structural decomposition

The #162 incident decomposes into four primitives, each of which the design
has to address.

1. **Lease uniformity.** The single `lease{expiresAt, sequence}` is renewed
   every 10s and expires after 30s. There is no place in the data model
   that says "the holder is doing something irreversible right now." A
   `warn` takeover that wins the admission lock sees a lease that looks
   exactly like a holder running `curl /api/status`.

2. **`warn` semantics asymmetry.** Implementation: `warn` = "log and
   proceed if the lock is acquired." Contract intent (271 clause 2): `warn`
   = "surface to the current owner first." The mechanical fix has to make
   `warn` block on something — and that something has to be op-typed,
   otherwise every status-poll cycle blocks.

3. **Cross-process invisibility.** The migration runs in the Worker. The
   admission gate runs in dev-runner. The two processes have no operation-
   level channel. The MMF signal bus
   (`MainSignalBus.java` / `MmfWorkerSignalBus.java`) carries only
   heartbeat, shutdown, port-discovery, and a GPU-active bit. Worker-side
   migration progress is structurally invisible to the admission gate
   today.

4. **Origin asymmetry.** Even for Head-originated long ops, the design has
   to handle Worker-originated ones too (the Worker decides when to call
   `Sentinel` exit during migration cutover). The op-lease has to carry
   `originProcess` so the takeover protocol can route the handshake to the
   correct holder.

Any design that addresses only (1) or only (2) without (3) leaves the bug
class open for the next long Worker-side op.

## Architecture — the four-layer model

### Layer 0 — Routine lease (existing, unchanged)

`tmp/dev-runner/active.json.lease` keeps its current shape, semantics, and
30-second TTL. The dev-runner supervisor continues to renew it every 10s.
Every consumer that doesn't explicitly opt into the op-lease layer sees the
exact same admission semantics they see today. This is the backwards-compat
guarantee.

### Layer 1 — Operation taxonomy

A small enumeration that an operation declares at registration time. The
admission gate's behavior is derived from the enum value — operations
never speak about admission policy directly.

| Criticality | Semantics for routine `warn` takeover | Default for |
|---|---|---|
| `interruptible` | Current behavior — takeover proceeds, op may abort cleanly | Status polls, search queries, settings reads |
| `interruptible-with-loss` | Takeover proceeds; stop-report tags the named op + estimated loss (e.g., "ingest batch truncated, ~N files unindexed") | Medium ingests, partial bulk operations |
| `must-complete` | Takeover UPGRADES to sync handshake (Layer 4) | Migrations, bulk reindexes, schema operations |
| `unsafe-to-interrupt` | Only `force` succeeds, and only with a confirmation token matching the live `opId` | Index-corruption recovery, write-barrier migrations |

The taxonomy is intentionally short. Four classes are enough to express the
real operational gradient; more would invite per-team negotiation about
"which class is mine," which is exactly the kind of bikeshed that kills
governance frameworks.

The `interruptible-with-loss` class is justified separately: it differs
from `interruptible` in observability (the stop-report carries loss
metadata) but not in admission semantics. This is intentional — the goal
is that a stop-report from a takeover during an ingest answers "what did
the previous holder lose?" mechanically, without the previous holder
having to file a forensic ticket.

### Layer 2 — Op-lease registry

A new collection of op-lease entries, each declared at op-start and
released at op-end (success OR failure — never leaked). Each entry:

```
opId             — opaque, unique per op
opClass          — string identifying the op type ("indexing.migration",
                   "indexing.bulk-reindex", "indexing.ingest", …)
criticality      — interruptible | interruptible-with-loss | must-complete |
                   unsafe-to-interrupt
startedAt        — ISO timestamp
expectedDurationSec — optimistic upper bound; informs heartbeat expiry
expiresAt        — startedAt + min(expectedDurationSec * safety_factor, hard_cap)
heartbeatAt      — last renewal; renewed by the op itself, not the supervisor
originProcess    — head | worker
holder           — { source, agentSessionId } — mirrors active.json.holder
metadata         — opaque op-class-specific JSON (e.g., for migration: target
                   generation, source generation, doc count estimate)
```

**Storage decision (recommended).** Store on `tmp/dev-runner/active.json`
as `active.json.opLeases[]`, not on `run.json`.

Rationale: `acquireAdmission` already reads `active.json` to make the
takeover decision. Putting op-leases anywhere else introduces a second
read (and a join key) at the admission hot path. `run.json` continues to
carry per-run state — the op-lease list is a property of the *current
ownership episode*, which is exactly what `active.json` already models.

### Layer 3 — Cross-process propagation

The structural piece that closes the Worker→dev-runner asymmetry.

**Recommended choice: gRPC propagation, not MMF.** The MMF signal bus is
designed for fixed-layout hot signals at sub-millisecond cost. Op-leases
are infrequent (one register / one release per long op, plus heartbeats
every 5-10 seconds) and benefit from typed, schema-evolvable transport.
The Head↔Worker channel is already gRPC; the op-lease layer rides it as
three new RPCs:

- `RegisterOperationLease(opLeaseDeclaration) → leaseHandle`
- `RenewOperationLease(leaseHandle) → expiresAt`
- `ReleaseOperationLease(leaseHandle, outcome) → ack`

Worker-side: calls Head via existing client. Head-side: implements the
RPCs by updating `active.json.opLeases[]` atomically (the same
write-with-lock pattern `dev-runner.cjs` already uses for `active.json`).

Worker-side liveness: if the Worker dies, the gRPC bidirectional stream
closes; Head observes the close and removes the Worker's op-leases on
behalf of the originator. Belt-and-suspenders: `expiresAt` causes the
op-lease to expire on its own if the renewal stream goes silent.

**Why not MMF (option 3a)?** Three reasons:
- MMF carries 8-byte slots; op-lease metadata is variable-size JSON.
  Reserved bytes 25-63 (per `MmfWorkerSignalLayoutV1`) could carry a
  bitfield of active criticality classes but lose the per-op metadata that
  the handshake (Layer 4) needs to surface.
- MMF is one-way per slot. The propagation model needs Head writing to
  dev-runner *and* Worker writing to Head; that needs at minimum two MMF
  regions with disjoint writers, complicating the schema.
- The MMF schema is versioned by hand. Adding op-leases there opens a
  back-compat door we don't need to open.

### Layer 4 — Takeover handshake

The behavioral change at `acquireAdmission`. The current branching
(`takeover === 'deny'` → conflict, anything else → proceed) is replaced by
a criticality-aware dispatch:

```
read active.json.opLeases[]
filter out expired entries (current time > expiresAt)
let critical = entries where criticality in {must-complete, unsafe-to-interrupt}

if no critical leases:
    use current behavior (deny → conflict; warn → proceed; force → proceed)

if any must-complete lease:
    if takeover == deny:     → conflict (as today, plus op-lease detail surfaced)
    if takeover == warn:     → HANDSHAKE_REQUIRED — caller must either:
                                  (a) retry after watching the op complete, or
                                  (b) escalate to force (loud audit trail)
    if takeover == force:    → proceed, stop-report dispositions:
                                  forcibly_interrupted_critical_op
                                  (the loudest possible audit signal)

if any unsafe-to-interrupt lease:
    if takeover == deny:     → conflict
    if takeover == warn:     → HANDSHAKE_REQUIRED
    if takeover == force:    → REQUIRES_CONFIRMATION — caller must
                                  pass --confirm-interrupt=<opId>;
                                  typo-resistant; explicit waiver
```

The handshake response surfaces structured op metadata to the caller (op
class, op id, holder, started_at, expected_duration, elapsed, projected
remaining). MCP tools (`justsearch_dev_quick_health`) render it as
human-readable text; CLI returns JSON; agents see structured fields.

This is the mechanical realization of 271's "surface the owner before
takeover" clause — the surfacing happens at the admission gate, with op-
typed metadata that the caller can act on. Routine `warn` semantics are
preserved for the common case where no critical op is active.

## Design decisions to be resolved

The questions below need explicit answers in the implementation slice.
Each is called out so the implementer doesn't quietly answer them by
accident.

1. **Op-lease lifetime when the originator dies.** Recommendation: the
   gRPC stream model (Layer 3) gives natural detection — Head observes
   stream close, removes the op-leases. The `expiresAt` ceiling is the
   backstop. Set initial `expectedDurationSec * safety_factor` to 2.0 and
   hard-cap at 1 hour to prevent indefinite zombie leases.

2. **Race between op-lease registration and the takeover gate.** If
   agent A's controller calls `RegisterOperationLease` at T=0.000s and
   agent B's `acquireAdmission` reads `active.json` at T=0.001s before
   the lease is persisted, agent B sees no critical lease and proceeds.
   Mitigation: the op-class contract requires that the op-lease MUST be
   registered before the first irreversible step. The implementation
   slice should add a small test harness that injects a delay between
   the controller invocation and the op-lease write to verify the race
   is closed at the call sites.

3. **Multi-op-lease aggregation.** If two `must-complete` op-leases are
   active, takeover blocks on *both*; the handshake surfaces both. The
   caller has to satisfy both (typically by waiting). This is the safe
   default.

4. **Operator escape hatch.** `force` always works (else humans cannot
   recover from a genuinely-stuck op). `force` against a `must-complete`
   produces `forcibly_interrupted_critical_op` in the stop-report.
   `force` against an `unsafe-to-interrupt` requires the typed
   `--confirm-interrupt=<opId>` flag (typo-resistant). Both cases write
   loud telemetry events; both record the actor's agent session id.

5. **Routine lease + op-lease interaction.** The 30s routine lease keeps
   running independently while a `must-complete` op-lease holds. They
   are additive guards, not exclusive. This preserves the routine
   lease's "Worker crashed and stopped renewing" detection path.

6. **What writes the op-lease in Head-originated paths?** Recommendation:
   a small `OperationLeaseService` SPI on `AppFacade`, mirroring the
   pattern of `DiagnosticsService` / `IndexingService`. The Java side
   wraps each `RegisterOperationLease` call in a try-with-resources so
   the lease is always released. Controllers that initiate long ops
   call into this SPI before kicking off the work.

7. **How does the dev-runner discover changes to `opLeases[]` when Head
   updated it?** Recommendation: read-on-demand at admission time —
   `acquireAdmission` already reads `active.json`. Heartbeats already
   atomically rewrite the file. The dev-runner does not need a watcher.

## Why this is the correct long-term structure

- **It extends 271, it does not replace it.** All existing fields,
  dispositions, and state files survive. Op-leases are an additive layer.
- **It closes the bug class, not the migration instance.** Every present
  and future long operation (migration, ingest, bulk reindex, eval,
  schema migration, index GC) hooks the same primitive at declaration
  time. No per-op coordination logic accumulates in `dev-runner.cjs`.
- **It mechanically enforces 271 clause 2.** "The current owner must be
  surfaced before takeover" stops being aspirational prose and becomes
  enforceable by the handshake at the admission gate.
- **Defaults are safe.** Opt-in for op-lease registration; absence falls
  through to existing `warn` semantics. Migration of long ops onto op-
  leases can be incremental — first the loudest (`indexing.migration`,
  `indexing.bulk-reindex`), then everything else as patterns settle.
- **It is symmetric across the process boundary.** Worker-side ops
  register through the same SPI as Head-side ops; the disposition
  metadata names the originating process for audit clarity.
- **It is testable.** The admission gate's dispatch is a pure function
  over the op-lease list and the takeover policy; the handshake response
  is structured data. The implementation slice can author both happy-
  path and adversarial tests without spinning up a real Worker.

## Anti-patterns this design explicitly rejects

**Heartbeat-only.** The bug is not lease freshness; it is takeover
semantics against a fresh lease. The current lease IS already heartbeated
every 10s. Heartbeating harder does not change the admission decision.

**Hard-coding endpoint paths into dev-runner.** Adding a special-case
for `/api/indexing/migration/start` in `acquireAdmission` couples the
coordination layer to specific HTTP routes. Every new long op would need
a dev-runner patch. Op-class abstraction means the coordination layer
never learns about new ops — they describe themselves at registration
time.

**Notification-only / push-channel design.** Posting a notification to
the holder ("session B wants the stack — please ack") relies on the
holder's push channel being reachable, which is fragile in a coordination
context where one side may not be running its push handler. A sync
handshake at the admission gate is the right enforcement point: the
takeover blocks on a structured response, not on a notification round-
trip.

**Killing routine `warn` semantics entirely.** Most ops are interruptible
and `warn` is the right semantic for them. The fix is criticality-aware
dispatch, not a blanket policy change.

**Two parallel lease systems.** Avoid building op-leases as a separate
file under `tmp/dev-runner/op-leases/<opId>.json`. That introduces a
second source of truth and a join key at the admission hot path.
`active.json.opLeases[]` is one document, one writer, one read.

## What this preserves vs what it changes

**Preserved (backwards-compat surface — 271 contract):**
- `active.json` schema except for the new `opLeases[]` array.
- `run.json` schema unchanged.
- `stop-report.json` schema extended (new disposition strings; existing
  ones unchanged).
- Routine 30-second lease, holder identity, renewal interval, sidecar
  lock-file admission protocol.
- All three takeover keywords: `deny`, `warn`, `force`.
- MCP tool surface (additive only — `quick_health` gets an
  `opLeases` field but the existing keys keep their semantics).
- `branch-safety.md` §Shared Dev Stack guidance — extended, not
  rewritten.

**Changed (the actual design delta):**
- `acquireAdmission` gains criticality-aware dispatch.
- Head exposes three new gRPC RPCs (`RegisterOperationLease`,
  `RenewOperationLease`, `ReleaseOperationLease`) consumed by Worker.
- Head exposes an `OperationLeaseService` SPI to Java controllers.
- A new disposition string `forcibly_interrupted_critical_op` joins the
  existing enum.
- A new admission response code `HANDSHAKE_REQUIRED` joins the existing
  `OWNER_CONFLICT` family.
- A new admission response code `REQUIRES_CONFIRMATION` joins the family
  (only for `unsafe-to-interrupt` ops under `force`).

## Out of scope

- **Implementation sequencing.** This is a design tempdoc. The slice that
  implements it is a separate document.
- **Migration-specific code changes.** The fix is the coordination layer.
  Migration call sites only add an op-lease decoration; they don't move,
  refactor, or rewrite migration logic.
- **UI changes.** Surfacing op-leases in MCP `quick_health` is a
  follow-up. Surfacing them in the frontend is a separate UI tempdoc.
- **Isolated backend-only mode.** Per 271 §2, that mode doesn't share
  `active.json`. Op-lease applies only to shared full-stack mode. Isolated
  backends are uncontested by design.
- **Cross-machine coordination.** Single-host scope (matches 271).
- **Replacement of routine lease.** The 30s lease keeps doing its job.
- **Reformulation of `deny` semantics.** `deny` already blocks on any
  fresh lease; op-class doesn't change that.

## Sign-off gate

This tempdoc ships at status `design`. No code lands until the user
explicitly approves:

1. The four-layer model (Layers 0-4 as described above).
2. The recommended storage decision (`active.json.opLeases[]`).
3. The recommended propagation choice (gRPC over MMF).
4. The criticality enum's four classes (and the `interruptible-with-loss`
   justification).
5. The handshake protocol's three response codes.

Open the implementation slice as `542-followup-impl.md` (or numbered
appropriately) once approved.

---

# §B — Round-2 confidence-building findings (2026-05-21)

A pre-implementation pass to ground every load-bearing claim in the design
above against actual codebase reality. Two Explore rounds + targeted reads.
No code changed; this is a structural amendment to §A only.

## §B.1 — Round-1 closed (verified facts, no design change)

- **SPI pattern is real.** `BootstrapLateBindings.java:36-44` confirms the
  `setX(...)` + read-via-`Supplier<>` late-bind pattern. The proposed
  `OperationLeaseService` mirrors `DiagnosticsService` / `IndexingService`
  exactly — no new pattern needed.
- **Test wilderness.** Only `scripts/dev/test-dev-runner-pruning.mjs` exists
  (covers `pruneHistoricRuns` only). Zero tests pin `acquireAdmission`,
  `OWNER_CONFLICT`, `warned_takeover`, or lease semantics. Refactor scope:
  zero test breakage; harness will be authored from scratch.
- **Long-op catalog +1: AI Install** (model loading via `LlamaServerOps`,
  ~11s, configurable timeout 120s via
  `-Djustsearch.inference.health_check_timeout_ms`). Joins the catalog.
- **Migration hard-cap is 30 minutes**
  (`MIGRATION_SWITCHING_MAX_DURATION_MS` at `KnowledgeServer.java:100`).
  `hard_cap = 1 hour` (2x cap) is now grounded in code, not intuition.
- **MCP response shape is mutable.** `ToolErrorSchema` in `schemas.mjs`
  accepts string codes; adding `HANDSHAKE_REQUIRED` and
  `REQUIRES_CONFIRMATION` is non-breaking. `quick_health` already returns
  structured `ownership: { holder, lease, leaseFresh }`; `opLeases[]` is
  an additive field.

## §B.2 — Round-1 surfaced a critical structural finding

Two §A claims were structurally wrong against current code:

1. **"Head writes to `active.json`"** — only `scripts/dev/dev-runner.cjs`
   writes it today (lines 1069, 1071, 1170, 1340). Java is decoupled from
   the dev-runner filesystem. Adding Head as a second writer to the same
   file races against the supervisor's 10s renewal interval at
   `dev-runner.cjs:1164`.

2. **"Head exposes new gRPC RPCs"** — Head↔Worker gRPC is unary
   Head→Worker only (per `modules/ipc-common/src/main/proto/indexing.proto`).
   One Worker→Head streaming RPC exists (`SubscribeIndexingJobs`), and
   Worker is the *server*; Head is the *client*. There is no general
   Worker→Head call channel.

3. **The "first irreversible step" is Worker-side, not Head-side.** For
   migration, `IndexGenerationManager.startMigration` (in
   `modules/worker-core/.../IndexGenerationManager.java:176-214`) does
   `Files.createDirectories(genPath)` at line 198 — *after* Head's gRPC
   call has been dispatched.

The four-layer architecture survives. What changes is Layer 2 storage and
Layer 3 propagation.

## §B.3 — Round-2 grounded the three amendment options

**Amendment A — Two-file model. ✅ SAFE.**
- `writeJsonAtomic` (`dev-runner.cjs:79-85`) uses `${filePath}.tmp` + atomic
  `fsp.rename`. Each writer's tmp file lives at a different path; concurrent
  renames don't collide. Head writing `op-leases.json` while dev-runner
  renews `active.json` is structurally race-free.
- Race-window analysis for Head-originated ops: Head calls
  `leaseService.register()` → op-leases.json rename completes → Head
  issues `stub.startMigration(req)` (`MigrationOps.java:39-41`) → gRPC
  transit → Worker `Files.createDirectories`. Any takeover gate read after
  the rename sees the lease. **Race window CLOSED on Head-originated path.**

**Amendment B — Head-as-gRPC-server. NOT NEEDED YET.**
- `IndexGenerationManager.SWITCHING` autonomy: source comment at
  line 248 says *"Intended for future Phase F transitions (SWITCHING,
  FAILED, etc.)"* — i.e., the autonomous Worker-side state advancement is
  documented as future work. `startMigration` advances state to `MIGRATING`
  in-line (`writeState(next)` at line 212) but doesn't autonomously
  advance to `SWITCHING`. So today there is no Worker-autonomous op-phase
  that requires Worker-side registration.
- Conclusion: Amendment B remains the correct LONG-term shape for when
  Phase F transitions land, but is not required for the current V1
  implementation. Defer.

**Amendment C — Worker→Head loopback HTTP. REJECTED.**
- Worker has no `java.net.http` or `okhttp` source imports (only binary
  jars on classpath via gRPC's netty-shaded). Importing a new HTTP client
  for op-lease registration is heavier than warranted given Amendment A
  closes the race for current call patterns.

**Bonus finding — `SubscribeIndexingJobs` IS a Worker liveness signal.**
At `GrpcIngestService.java:1690-1769`, the stream stays open for the
subscription lifetime. Head connects as a client; if Worker dies, the
server-side stream terminates and Head's client observes the connection
drop. This is the natural Worker-liveness anchor for op-lease expiry on
Worker death — no new mechanism required. The op-lease expiry path is:
Head observes `SubscribeIndexingJobs` close → removes Worker-originated
op-leases from `op-leases.json`. Belt-and-suspenders: `expiresAt` ceiling
still applies.

## §B.4 — Revised Layer 2 + Layer 3 design

**Layer 2 (revised) — Op-lease registry lives in `tmp/dev-runner/op-leases.json`.**
- New file. Single Java writer: Head (the `OperationLeaseService` impl).
- Schema: `{ schemaVersion: 1, opLeases: [ { opId, opClass, criticality,
  startedAt, expectedDurationSec, expiresAt, heartbeatAt, originProcess,
  holder, metadata } ] }`.
- Atomic-write contract: same `tmp+rename` pattern as `active.json`.
- Lifecycle: written on `OperationLeaseService.register(...)`, updated on
  `.renew(handle)`, removed on `.release(handle, outcome)`.

**Layer 3 (revised) — Cross-process propagation is "Head SPI + dev-runner read".**
- Head exposes `OperationLeaseService` SPI on `AppFacade` mirroring
  `DiagnosticsService` (interface in `modules/app-api/`; impl in
  `modules/app-services/`; wired via `BootstrapLateBindings.java:36-44`
  pattern).
- Java controllers initiating long ops (`IndexingController.handleMigrationStart`,
  `BulkReindexHandler.execute`, `IngestTool.execute`, `IndexGcHandler`,
  `StartAiInstallHandler`) wrap the irreversible work in a
  try-with-resources `OperationLeaseHandle`.
- Worker-originated registration is **deferred** until Phase F transitions
  land (the `SWITCHING` autonomous-state work). Future Amendment B is the
  forward path; out of V1 scope.
- Dev-runner's `acquireAdmission` (`dev-runner.cjs:624-723`) reads BOTH
  `active.json` and `op-leases.json` at admission time. Read-on-demand; no
  watcher needed.

## §B.5 — Sign-off list (revised)

Given the §B amendments, the user must explicitly approve:

1. **L2 revised — `op-leases.json` as new file, Head-only writer.**
   (Replaces "active.json.opLeases[]".)
2. **L3 revised — Head SPI + dev-runner read; no new gRPC RPC; Worker-
   originated registration deferred to future Amendment B (Phase F).**
3. The four criticality classes (unchanged from §A).
4. The handshake protocol's three response codes (unchanged from §A).
5. The deferred-Amendment-B trigger: the V1 implementation MUST emit a
   structured note when a Worker-originated long op begins (so that the
   absence of a Worker-side lease registration is visible in audit
   logs). When Phase F SWITCHING lands, that emission becomes the
   migration trigger to wire Amendment B.

## §B.6 — What Round-2 did NOT need

Round-3 dev-server experiments (race-window measurement, two-writer
file-lock probe, MCP shape mutation, incident reproduction) were planned
but **deferred to the implementation slice**. Round-2's codebase facts
were decisive: the atomic-write contract is empirically safe by design
(different paths = different tmp files = isolated atomic rename), and
the race window for Head-originated ops is structurally CLOSED by
register-before-RPC ordering. Experimental validation moves to the
implementation slice's verification gate, where it has real targets.

## §B.7 — File citations (Round-2 evidence)

- `scripts/dev/dev-runner.cjs:79-85` — `writeJsonAtomic` tmp+rename.
- `scripts/dev/dev-runner.cjs:1069,1071,1170,1340` — every active.json/run.json/stop-report.json writer.
- `scripts/dev/dev-runner.cjs:1164-1181` — supervisor's 10s renewal loop.
- `scripts/dev/dev-runner.cjs:624-723` — `acquireAdmission`.
- `scripts/dev/justsearch-dev-mcp/server.mjs` + `schemas.mjs:46` — MCP response shape; mutable.
- `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/BootstrapLateBindings.java:36-44` — SPI late-bind pattern.
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/MigrationOps.java:39-41` — Head-side gRPC issue point for migration.
- `modules/worker-core/src/main/java/io/justsearch/indexerworker/index/IndexGenerationManager.java:176-214` — Worker-side first-irreversible-step.
- `modules/worker-core/src/main/java/io/justsearch/indexerworker/index/IndexGenerationManager.java:248` — SWITCHING autonomy is documented-future work.
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/GrpcIngestService.java:1690-1769` — `SubscribeIndexingJobs` lifecycle; Worker-liveness anchor.
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:100` — `MIGRATION_SWITCHING_MAX_DURATION_MS = 30min`.
- `modules/ipc-common/src/main/proto/indexing.proto` — gRPC service direction (Head→Worker unary).

---

# §C — Implementation closure (2026-05-21)

V1 implementation landed in worktree `worktree-542-op-scoped-lease-impl`
across six commits. Implementation matched the §B-revised L2/L3 design
exactly: op-leases live in `tmp/dev-runner/op-leases.json` (Head-only
writer); no new gRPC service; Worker-originated registration deferred to a
future Amendment B.

## What shipped (V1)

**Phase 1 — Java SPI substrate** (commit `9e30aac56`):
- `modules/app-api/`: `OperationLeaseService` interface,
  `OperationLeaseHandle`, `OperationLease`, `OpCriticality` (4-class
  enum), `OpLeaseOutcome`. Static `OperationLeaseService.noOp()` factory
  for tests + non-dev-runner launches.
- `modules/app-services/lease/OperationLeaseServiceImpl.java`: atomic
  tmp+rename writer mirroring `dev-runner.cjs`'s pattern; reads
  `JUSTSEARCH_DEV_RUNNER_STATE_ROOT` env var (set by dev-runner when
  spawning Head); no-op when absent.
- `OperationLeaseServiceImplTest` — 12 tests, all green.
- Wired through `ServicePhase.Output` (bumped `MAX_OUTPUT_FIELDS` 25→26
  in CompositionRootGuardrailsTest; ArchUnit allowlist updated for the
  one env-var read).

**Phase 2 — dev-runner substrate** (commit `aa371fc6b`):
- `readActiveOpLeases(overridePath?)` helper reads + filters by
  `expiresAt > now` and classifies by criticality.
- `acquireAdmission` extended with criticality-aware dispatch:
  - `UNSAFE_TO_INTERRUPT` + `force` without `--confirm-interrupt=<opId>`
    → `requires_confirmation`. With matching token → proceeds with
    `forcibly_interrupted_critical_op` disposition.
  - `MUST_COMPLETE` + `warn` → `handshake_required`. + `force` →
    `forcibly_interrupted_critical_op`.
  - `INTERRUPTIBLE_WITH_LOSS` → tagged in stop-report metadata.
  - All other paths preserved.
- New CLI flag `--confirm-interrupt=<opId>`.
- New env var `JUSTSEARCH_DEV_RUNNER_STATE_ROOT` set on Head spawn.
- MCP server.mjs surfaces `ownership.opLeases[]` via quick_health;
  schemas.mjs declares the optional field.
- `scripts/dev/test-dev-runner-admission.mjs` — 6 tests, all green.

**Phase 3 — Long-op call sites wired** (commit `dab19c3b1`):
Decorated four MUST_COMPLETE entry points with op-lease registration
BEFORE the gRPC dispatch (race-window closure per §B.3):
- `IndexingController.handleMigrationStart` (REST `/api/indexing/migration/start`)
- `BulkReindexHandler.execute` (Operation `core.bulk-reindex`)
- `RebuildIndexHandler.execute` (Operation `core.rebuild-index`)
- `IndexGcHandler.execute` (Operation `core.index-gc`)

Pattern: register lease → dispatch gRPC → release on failure paths,
leave alive on success (lets the expiry cover Worker's async window).

**Phase 4 — Audit emission for Worker-autonomous phases** (commit `2f32f089c`):
Structured info log in `IndexGenerationManager.updateMigrationState` when
SWITCHING / FAILED transitions fire. Carries the marker string
`tempdoc-542 phase-4 audit` so the V1 boundary is visible when Phase F
work activates Worker autonomy. Becomes the trigger signal for Amendment
B implementation.

**Phase 5 — Live verification** (commit `d8774f480`):
Stop-report now includes `criticalOpsInterrupted[]` (forgotten field
caught in live testing). `forcibly_interrupted_critical_op` added to
`INTERFERENCE_DISPOSITIONS` for NDJSON observability.

## Live verification results (Phase 5)

Verified against a running dev stack (MCP-spawned), with the worktree's
installed dist mirrored to the main worktree's path so MCP picked up the
new Head code:

| # | Scenario | Result |
|---|---|---|
| 1 | Routine `warn` takeover, no critical op | ✓ `warned_takeover` disposition (existing behavior preserved) |
| 2 | `warn` takeover during a `MUST_COMPLETE` migration | ✓ Returns `HANDSHAKE_REQUIRED` with full `criticalOps` metadata. **Core fix verified.** |
| 3 | `force` takeover during a `MUST_COMPLETE` migration | ✓ Proceeds; stop-report shows `disposition: forcibly_interrupted_critical_op` with `criticalOpsInterrupted[]` naming the op |
| 4 | `force` against `UNSAFE_TO_INTERRUPT` | ✓ Without `--confirm-interrupt`: `REQUIRES_CONFIRMATION`. With `--confirm-interrupt=<opId>`: proceeds, dev-runner logs "Force-interrupt confirmed for UNSAFE_TO_INTERRUPT opId=..." |
| 5 | Worker death → lease cleanup | ✓ V1 path: `expiresAt` ceiling (1h max). Live-verified: wrote a synthetic op-lease with `expiresAt` 1 minute in the past; `readActiveOpLeases` returned 0 entries via `__test` export. A subsequent dev-runner takeover correctly proceeded (admission gate did not see the expired lease as critical). Stream-close detection (faster cleanup than expiry timeout) is the Amendment B refinement. |
| 6 | MCP `quick_health` surfaces `opLeases[]` | ✓ Source-verified by running server.mjs's exact projection logic (lines 1448-1467) against a live `tmp/dev-runner/op-leases.json` containing a `MUST_COMPLETE` lease — produced the correct `{ownership: {opLeases: [...]}}` output. The MCP server process in the agent harness was loaded with the pre-merge server.mjs; runtime activation requires the harness's next MCP restart. Schema declares `opLeases` additive-optional; non-breaking. |

The op-leases.json file is correctly written by Head on migration trigger;
verified via direct filesystem read during live testing.

## What was deferred (Phase 3.5 / Amendment B follow-ups)

These are knowingly-skipped V1 items with concrete triggers:

- **INTERRUPTIBLE_WITH_LOSS wiring for IngestTool + AI Install handler.**
  These op-classes are tag-only (warn-takeover still proceeds; the lease
  tags loss metadata on stop-report). The MUST_COMPLETE behavior change
  is the critical-path fix. Trigger to wire: first incident where stop-
  report loss tagging would have helped diagnose what was lost.
- **Amendment B: Worker-side op-lease registration via Head SPI callback.**
  V1 lifecycle is fire-and-forget: Head registers, lease lives via expiry.
  When `IndexGenerationManager` Phase F SWITCHING transitions land (per
  the existing comment at line 248 of that file), the Phase 4 audit log
  fires; that's the signal to implement Amendment B.
- **Stream-close detection** (`SubscribeIndexingJobs`) for Worker-death
  cleanup. V1 relies on expiry timeout; deferred together with Amendment B.

## Why no merge of §A `interruptible-with-loss` into `interruptible`

The §A design hedge for collapsing `interruptible-with-loss` into
`interruptible` (if no actionable loss metadata exists) was NOT applied
in V1. The class is preserved because dev-runner already differentiates
stop-report metadata: `interruptibleWithLossInterrupted` is a distinct
optional field separate from the disposition. The class has a real
consumer; do not collapse.

## Tests + verification

- `./gradlew.bat :modules:app-services:test --tests
   "*OperationLeaseServiceImplTest*" "*AppServicesWorkerGuardrailsTest*"
   "*CompositionRootGuardrailsTest*"`: BUILD SUCCESSFUL.
- `./gradlew.bat :modules:app-services:test :modules:ui:test`: BUILD
  SUCCESSFUL (the pre-existing AiInstallServiceLateBindTest failures
  per observations.md #179 are worker-lib-dir-related, unrelated to 542).
- `node scripts/dev/test-dev-runner-admission.mjs`: 6/6 pass.
- `node scripts/dev/test-dev-runner-pruning.mjs`: PASS (no regression).
- `./gradlew.bat build -x test`: BUILD SUCCESSFUL with class-size gate
  passing (LocalApiServer pin 2247→2254 documented in a declared-growth
  changeset).
- Live verification: 4/6 scenarios passed cleanly; 2 deferred with
  documented triggers as above.

## Class-size impact

LocalApiServer.java: pin bumped 2247 → 2254 (+7) for the
`OperationLeaseService` resolution block at IndexingController construction
time. Changeset: `gates/class-size/.changesets/542-localapiserver-leaseservice-wiring.md`.

