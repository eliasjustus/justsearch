---
title: "Tempdoc 501 — Runtime manifest as substrate for non-JVM service discovery"
type: tempdocs
status: done
created: 2026-05-16
revised: 2026-05-21
category: architecture / runtime substrate
authority: theorization of the correct long-term primitive for runtime-state publication; supersedes the original "port discovery" framing
related:
  - modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java (current port-file producer, future manifest producer)
  - modules/ipc-common/src/main/java/io/justsearch/ipc/mmf/MmfWorkerSignalLayoutV1.java (existing versioned-publication-slot pattern)
  - modules/app-services/src/main/java/io/justsearch/app/services/worker/MainSignalBus.java
  - modules/indexer-worker/src/main/java/io/justsearch/indexerworker/coordination/MmfWorkerSignalBus.java
  - modules/configuration/src/main/java/io/justsearch/configuration/PlatformPaths.java (single resolver to be contractified)
  - modules/ui/src/main/java/io/justsearch/ui/api/routes/InfraRoutes.java (capabilities + 503-while-bootstrapping pattern)
  - modules/ui-web/vite.config.js (current Vite-side consumer; Stage 1 of the design)
  - modules/ui-web/src/api/http.ts (browser-side endpoint resolution, future SSE consumer)
  - scripts/prod/justsearch-mcp/discovery.mjs (production consumer; reference shape for liveness validation)
  - scripts/dev/dev-runner.cjs (orchestrator-side observation — `run.json`, `active.json`)
  - scripts/dev/justsearch-dev-mcp/server.mjs
  - modules/shell/src-tauri/src/lib.rs (Tauri shell — future manifest consumer)
  - docs/tempdocs/500-mcp-protocol-surface.md (cited by §12.4 / §12.5 for the curated-tool + Resource/surface distinction)
  - docs/tempdocs/502-boot-composition-architecture.md (cited by §12.1 — LifecycleProjection.derive is the projection source)
  - docs/tempdocs/518-inference-lifecycle-design.md (cited by §12.1 — InferenceRuntimeView is the AI projection atom)
  - docs/tempdocs/519-head-composition-graph.md (cited by §12.2 — typed phase-output records the publisher will plug into)
  - docs/tempdocs/521-plugin-ecosystem-substrate.md (cited by §12.6 — module-resolver trust attenuation is orthogonal to manifest)
gates:
  - One producer-published document is the canonical source of all non-JVM runtime facts. New runtime facts grow that document's schema; they do not get new sibling files or new env-var channels.
  - Data-dir resolution lives in one contract consumed by every language. Java, Node, and Rust read identically; drift is mechanically detectable, not socially policed.
---

# Tempdoc 501 — Runtime manifest as substrate for non-JVM service discovery

This tempdoc replaces the earlier "Runtime port discovery architecture" doc.
The earlier doc correctly diagnosed a single failure (bare `npm run dev`
couldn't find the backend) and shipped a correct local fix (file-based Vite
proxy plugin, on `main` as commits `65f217626` + `4f2698bf7`). That fix is
preserved in §8 as Stage 1 of the larger design described here. It is not
re-litigated.

The reframing: the *failure class* the earlier doc named — "nine partly-redundant
mechanisms for telling consumers where the backend bound" — is not a list to
prune. It is a symptom of a missing publisher-side primitive. This tempdoc
theorizes that primitive and constrains the design space of every future
runtime-state question by answering "where does X belong?" with a single
answer.

## 1. Why "nine mechanisms" is a symptom, not a backlog

Each of the nine current mechanisms (MMF, stdout parse, port file, env var,
Vite `define`, URL query, Tauri invoke, JavaFX bridge, dev-runner `run.json`)
encodes a *fragment* of a single fact: *"There is a JustSearch backend with
identity X, running with PID Y, bound at address Z, since time T, exposing
contract versions V."* No mechanism today carries that full fact. Each one
encodes a slice — usually just `Z`, occasionally `Z + token`, rarely
identity or freshness.

Because no mechanism carries the full fact, every consumer reconstructs it
ad-hoc:
- The Vite plugin checks the file, hopes it's current, has a 2-second TTL,
  and falls back to a 502 when proxying fails.
- The prod MCP reads the file, then issues `GET /api/status` to confirm a
  backend is actually responding.
- The dev-runner parses stdout, falls back to the port file, and writes its
  own `run.json` snapshot to record what it observed.
- The Tauri shell parses stdout from a subprocess it owns.

These are five different liveness/identity protocols invented privately by
five consumers. None of them detect *"the backend died and a different one
bound the same port"* — only the explicit-token paths (prod MCP fetching
`/api/mcp/token`) come close, and they pay an HTTP round-trip per check.

Adding a tenth mechanism (a new env var, a new sibling file, a new query
param) does not reduce this surface area. It enlarges it. The correct
response is one primitive that subsumes all nine.

## 2. What already exists — extend, don't reinvent

The substrate to build on is partly there. The rewrite must integrate with
these existing layers, not parallel them:

- **MMF protocol with versioned header** (`MmfWorkerSignalLayoutV1`). A
  64-byte slot, schema `v1`, 30 bytes reserved, MAGIC + `FORMAT_VERSION` +
  `COMPAT_FLAGS` for forward compatibility. This is the codebase's reference
  pattern for *"versioned, extensible publication slot."* It stays scoped to
  intra-JVM Head ↔ Worker IPC — that scope is correct and the manifest design
  must not widen it. The pattern itself, however, is the right one to mirror
  for the cross-process, cross-language manifest.

- **`tmp/dev-runner/run.json`.** Already records `apiPortActual`, `dataDir`,
  `apiBaseUrl`, `pids`, `resourceClaims`, `confirmedIndexBasePath`. The
  *shape* the manifest should converge with is essentially what `run.json`
  already documents — but `run.json` is the *orchestrator's observation*,
  not the producer's self-declaration. Same fields, different authority.
  These views should remain distinct (producer cannot know
  `owner.agentSessionId`), but they should agree on shared fields by reading
  the manifest into `run.json` rather than re-deriving them.

- **`/infra/capabilities` + 503-while-bootstrapping.** The HTTP layer already
  has the right "ready vs not-ready" signal. The manifest is the filesystem-
  level peer of that signal — it must land precisely when capabilities flip
  503 → 200, never earlier. Today, `api-port.txt` is written before the
  API can serve traffic, which is why the prod MCP wraps every candidate in
  `validatePort()`. With manifest-as-boot-affidavit, that wrap becomes
  redundant.

- **`PlatformPaths.java` and its replicas.** Three drifting JS/Node
  implementations exist (`prod/justsearch-mcp/discovery.mjs`,
  `dev-runner.cjs`, `vite.config.js`), plus a Rust path-resolution layer
  in the Tauri shell. This is the same shape as the `app-api` records
  problem — a fact that lives in one source and is consumed in many — and
  the codebase already has the right idiom for it (`updateSchemas` task,
  generated bindings, contract test). Reusing that idiom is preferred over
  inventing a new sync mechanism.

- **`/infra/capabilities/stream` (SSE).** The codebase already has a change-
  notification channel for one specific runtime fact (contract versions).
  The manifest should multiplex onto the same stream rather than introducing
  a parallel `/api/runtime/manifest/stream`. One stream, multiple event
  kinds.

## 3. The runtime manifest — design principles

These principles define the primitive. Concrete schema, transport details,
and migration sequencing are deliberately deferred to follow-up tempdocs.

### 3.1 One document, not N files

Every runtime fact a non-JVM consumer needs is a field on `manifest.json`,
not a new sibling file. The convention says: *"adding a fact that crosses
the JVM boundary means growing the manifest schema, never adding a
sibling."* Consumers detect availability via `schemaVersion`.

This is the same closure the `app-api` records discipline gives the
contract layer: one place to add a field, one place to regenerate, one
place that downstream consumers watch. Reviewers can mechanically reject
any PR that tries to introduce `X.txt` alongside `api-port.txt`, because
the convention already specifies where that fact lives.

### 3.2 Identity over liveness

The manifest carries a per-process `instanceId` (UUID, generated at boot,
constant for the life of the process). Consumers cache discovery results
keyed by `instanceId`, not by TTL. When `instanceId` changes, every
consumer's cache invalidates by definition — there is no "wait for the 2
second TTL to expire and hope" path.

This is what the current `api-port.txt`-with-2s-cache cannot do: it
cannot distinguish *"same backend, same port"* (cache hit, correct) from
*"different backend bound the same port after a crash"* (cache hit,
wrong). With `instanceId`, the second case is impossible to miss.

### 3.3 Freshness is explicit and cheap

Identity tells a consumer *who* the manifest describes. A separate,
cheap liveness signal tells them *whether that who is still alive*.
The canonical mechanism is **PID-alive check against the manifest's
own `pid` field**:

- Consumer reads `manifest.pid` (one filesystem read or one HTTP GET).
- Consumer calls `ProcessHandle.of(pid)` (Java) / `OpenProcess`
  (Windows) / `kill -0` (POSIX). One syscall, sub-millisecond, no
  HTTP round-trip.
- If the PID is dead the consumer refuses to use any field on that
  manifest and waits for the next write.

No separate lock file in the manifest's path — the producer-side
mutex is owned by the existing `AppInstanceLock`
(`<dataDir>/app.lock`, OS-level `FileChannel.tryLock()` with PID +
start-timestamp metadata and stale-recovery via `ProcessHandle.of()`,
in `modules/app-util/src/main/java/io/justsearch/app/util/AppInstanceLock.java`).
Today `AppInstanceLock` is acquired by the Worker; Phase 3 lifts it
into the Head so it covers the whole producer. Adding a second
`manifest.lock` would violate this tempdoc's own closure rule
(one mechanism per concern): the existing OS file lock is correct.

Two alternatives exist but are not committed:

- *MMF heartbeat reuse* — the existing `heartbeat_epoch_ms` slot in
  the MMF carries the same signal for intra-JVM consumers, and the
  binary layout admits cross-language reads in principle. No non-JVM
  reader exists today (verified by repo-wide grep), so this is
  feasible-but-untried.
- *Companion `heartbeat.txt`* on a periodic tick. Adds a writer loop
  the PID-check approach doesn't need.

The PID-check pattern is what dev-runner's `active.json` already proves
out, just lifted from orchestrator scope into the producer-published
document. Liveness is *decoupled* from the manifest contents
themselves: the manifest is rewritten only when an identity-relevant
fact changes; liveness is read from `manifest.pid` on every consumer
check.

This is what makes the 502-on-stale-port-file problem dissolve: the
consumer doesn't proxy to a port until it has verified the
`instanceId` it cached is still live via the manifest's PID.

### 3.4 Manifest readiness is phased, not single-moment

The codebase has no single "stack ready" moment to ride. `/infra/capabilities`
flips `200` inside `AppFacadeBootstrap`'s constructor (before Worker
connects); `/api/status` flips `200` immediately after HTTP bind; Worker
readiness lands in a separate later phase. A single boot affidavit
would lie about at least one of these. The manifest reflects the
truth: readiness is *phased*.

- **First write** — at HTTP bind. Carries `instanceId`, `pid`,
  `dataDir`, `head.apiPort`, `head.apiBaseUrl`, `headReadyAt`.
  `workerReadyAt` is null. Consumers needing only the API can proceed;
  consumers needing Worker wait on the next write.
- **Rewrite** — when the Worker connects (after the head completes
  Phase 3 `connectWorker`). Adds `worker.grpcPort`, `worker.indexBasePath`,
  `workerReadyAt`. SSE UPDATE frame fires.
- **Further rewrites** — additional readiness fields (e.g.,
  `aiReadyAt`) added by subsequent transitions as they become
  identity-relevant.

A consumer chooses its bar by which field it requires non-null.
Manifest *present* ⇒ producer at least passed HTTP bind. Manifest
*absent* ⇒ either not started or shutting down.

On clean shutdown the producer removes the manifest before tearing
down the HTTP server, so a consumer that reads-and-finds-no-file is
correctly informed. Crashed producers leave a stale manifest behind —
verified empirically by force-killing the JVM (see Appendix A) — so
the lock-file PID check in §3.3 is load-bearing, not a luxury.

### 3.5 Two transports, one document

The same JSON shape is served at both:
- `<dataDir>/runtime/manifest.json` — filesystem transport. For local
  tools, the Vite proxy, the dev-runner, the prod MCP. Required when the
  consumer doesn't yet know the port.
- `GET /api/runtime/manifest` — HTTP transport. For browsers, remote
  tools, sandboxed processes. Bootstraps from a known base URL (Tauri
  invoke, URL param, or the proxy itself).
- Change notification rides a dedicated SSE stream
  `/api/runtime/manifest/stream` with `streamId
  registry:runtime-manifest`. The existing capabilities stream
  envelope (`streamId` + `frameKind` + `payload` + `resumeToken`) is
  shared via `SseEnvelopeWriter`, but the streams are kept separate:
  capabilities is contract-version metadata; the manifest is runtime
  identity. Different lifecycles, different consumers.

Filesystem and HTTP are *transports* for one document, not separate
APIs. Whatever fields exist in one exist in the other.

### 3.6 One canonical data-dir resolver

`PlatformPaths.java` is the sole Java authority today (verified by
repo-wide grep — only `LOCALAPPDATA` read site is in PlatformPaths
itself). Drift lives outside Java, in three JS/Rust replicas.

The contract pattern: a neutral spec at
`contracts/platform-paths.v1.json` describes precedence chain,
env-var/sysprop names, and platform-default templates. Java retains
its source as the reference; Node and Rust implementations follow the
spec. A cross-language contract test feeds fixed env+platform fixtures
into each implementation and asserts identical paths. Drift becomes a
contract-test failure rather than a runtime silent-wrong-path failure.

What this collapses is the *algorithm* (one shared resolver across
languages), not the *candidate count*. When a consumer runs without
`JUSTSEARCH_DATA_DIR` set (bare `npm run dev`, etc.), every
implementation still has to try the same platform-default-plus-
worktree-conventions ladder — the ladder is correct logic, just
de-replicated. Reducing the candidate count further is an
orthogonal problem: an orchestrator-injected env var on every dev
launch path. The manifest design does not require it.

### 3.7 Multi-instance is enforced at the producer

Today, "one backend per dataDir" lives in two places, neither of
which covers all launch paths:

- The dev-runner's `active.json` lease (`OWNER_CONFLICT` in
  `dev-runner.cjs`) — fires only on dev-runner-mediated launches.
- `AppInstanceLock` (`<dataDir>/app.lock`, OS-level `FileChannel.tryLock()`
  with PID metadata + stale recovery, in
  `modules/app-util/src/main/java/io/justsearch/app/util/AppInstanceLock.java`) —
  acquired by the *Worker* (`KnowledgeServerBootstrap.java:108`). The
  Head currently has no lock; two Heads can race to bind ephemeral
  HTTP ports against the same dataDir before either Worker fails.

The design lifts enforcement entirely into the producer by having the
Head acquire `AppInstanceLock` early in boot, before HTTP bind. The
existing OS-level lock and PID-metadata format are correct; the only
move is shifting the acquisition site. If the lock is held by a live
holder the Head exits with a structured diagnostic instead of
silently double-binding. If the holder is dead `AppInstanceLock`
already handles stale recovery via `ProcessHandle.of()`.

- `<dataDir>/app.lock` — OS-level exclusive lock, held by the Head
  for the life of the process. Worker no longer needs to acquire it
  (Head's lock implies single-stack-on-dataDir); keep the call as
  defense-in-depth or remove it as Phase 3 decides.
- `<dataDir>/runtime/manifest.json` — the *current* instance's
  manifest. Last-writer-wins under the Head's lock.
- `<dataDir>/runtime/instances/<instanceId>/` — optional per-instance
  history (manifest snapshot, start log) mirroring the
  `tmp/dev-runner/runs/<runId>/` pattern.

This consolidates three would-be lock files (dev-runner lease, Worker
lock, hypothetical `manifest.lock`) into one OS-enforced mutex held
by the producer.

## 4. What stays unchanged

- **MMF (Head ↔ Worker).** Different domain. Intra-JVM IPC, binary,
  sub-millisecond. Manifest does not replace it.
- **`/api/status` as readiness probe.** Cheap and well-understood. Stays
  as the liveness check for paths where PID inspection is awkward
  (sandboxes, remote callers).
- **`JUSTSEARCH_API_PORT` env var as configuration.** "Try to bind to
  port X" — an *input* to the backend. The manifest reverses the
  direction (output). The two never collide because they're different
  flows.
- **`?api_port=N` URL escape hatch.** Manual override for debugging.
- **Tauri `invoke('api_port')`.** Stays as the production WebView
  bootstrap path; what changes is that the Rust side reads the manifest
  rather than parsing stdout.
- **stdout `JUSTSEARCH_API_PORT=...` line.** Stays as human-readable log
  output. Stops being a *discovery path* — no tool parses it.

## 5. What the manifest subsumes

| Existing artifact | Disposition under the manifest design |
|---|---|
| `<dataDir>/runtime/api-port.txt` | Superseded by `manifest.head.apiPort`. Thin mirror during deprecation, then removed. |
| Stdout `JUSTSEARCH_API_PORT=` parsing as a discovery path | Removed as a discovery path; surface stays for human log readability. |
| 501-introduced Vite-side six-candidate path ladder | Algorithm deduplicates under the shared PlatformPaths contract (one resolver, many languages). Candidate count is bounded by env-var presence, not by the contract. |
| `VITE_JUSTSEARCH_API_PORT` env var | Survives only as a test override. |
| Dev-runner's stdout-parse-or-fall-back-to-port-file logic | Becomes "read manifest, observe `instanceId`, attach `agentSessionId` in `run.json`." |
| Tauri shell stdout parsing | Replaced by Tauri reading the manifest directly via the Phase 4 Rust binding. |

**Out of scope under this design:** `worker-config-snapshot.json` is
Head → Worker *configuration passing* (`-Djustsearch.worker.config_snapshot`),
not external discovery. It does not fold into the manifest.

## 6. The closure rule

The point of this primitive is not just to fix the 501 symptom. It is to
constrain the answer to every future question of the form *"how does
non-JVM consumer C find runtime fact F?"* to exactly one answer:

> *"F is a field on the manifest, served at the filesystem path and the
> HTTP endpoint, watchable via SSE, scoped to the current `instanceId`."*

When a future tempdoc proposes a tenth mechanism (a new env var, a new
sibling file, a new query param) for some new fact — port for a new
sidecar, address of a debug socket, model-server endpoint, whatever — the
review answer is *"extend the manifest."* The convention closes the door
that today is open to nine variant answers.

This is the same closure pattern the `app-api` records / `@RecordBuilder` /
`updateSchemas` discipline gives the contract layer.

## 7. Open questions for design review

These are design questions, not implementation questions. Each has a
preferred answer; none is committed.

- **History or last-writer-wins?** Canonical path is last-writer-wins;
  `runtime/instances/<instanceId>/` retains history for postmortem.
- **Same SSE stream as capabilities, or separate?** Same stream, multiple
  event kinds. One stream simpler than two for both producers and
  consumers.
- **Merge with `tmp/dev-runner/run.json`?** No. The producer cannot know
  orchestrator-only facts (`owner.agentSessionId`, `confidence`). The
  `run.json` shrinks to "producer fields by reference + orchestrator
  fields by value."
- **Where does the session token live?** On the manifest, gated by
  filesystem permissions. The current `GET /api/mcp/token` endpoint
  becomes a convenience for HTTP consumers; both return the same value.
- **How does shutdown signal liveness to a consumer mid-read?** Manifest
  removal before HTTP teardown; consumer with stale handle falls through
  to PID-alive check, which fails. No new protocol needed.

## 8. The shipped 501 fix as Stage 1

Commits `65f217626` (file-based Vite proxy plugin) and `4f2698bf7` (hop-
by-hop header strip + `'proxy'` source fallback) — merged via
`3ce57c489` — are a correct local move *inside* this design:

- They shift Vite from env-var dependency to file-based discovery, which
  is the right transport (§3.5).
- They establish the precedent that consumers read `<dataDir>/runtime/...`
  rather than rely on orchestrator-injected env vars.
- They explicitly fail (502) rather than silently wrong-port when the
  source is missing, which is the right semantics for §3.4.

What they do not yet provide, and what the design above requires:

- The file is opaque text (one integer), not a structured manifest with
  identity, freshness, or schema version.
- Vite's six-candidate path ladder is one of three drifting copies of
  the data-dir resolution (§3.6 contractification not yet done).
- No `instanceId` cache invalidation; the 2-second TTL is a stopgap for
  the identity-over-liveness gap (§3.2).
- No HTTP transport for the same document; the browser still does its
  own thing via `resolveApiEndpoint()`.

These are framed as follow-up tempdocs, not as a backlog inside 501. The
shipped behavior is correct under the design described here; later
stages strengthen it without contradicting it.

## 9. Appendix A — verification of design-bearing claims (2026-05-20)

Static + dev-stack pass. Each numbered claim in this appendix mirrors a
claim made in §3 / §5. Verdicts:

**§3.4 / Claim 1 — `/infra/capabilities` as end-of-boot affidavit. REVISED.**
The capabilities handler is set inside `AppFacadeBootstrap`'s constructor
(`AppFacadeBootstrap.java:708`, immediately after `infraHealthBootstrap` /
`infraHealthGrpcServer` wiring). HeadlessApp's Phase 2 (`buildApi`) calls
that constructor *before* Phase 3 (`connectWorker`). Consequence: a live
dev stack returns `200` on both `/infra/capabilities` and `/api/status`
within the same HTTP server-bind moment — there is currently no
"end-of-boot" signal at all in the HTTP layer, and capabilities is not
the affidavit the rewrite assumed.

Design impact: §3.4's "manifest lands when capabilities flips 503 → 200"
is wrong as written. The manifest cannot piggy-back on capabilities for
its readiness contract. Either (a) the manifest defines its *own*
end-of-boot moment (written last, after `connectWorker` + worker
capability `available()`); or (b) the manifest carries a phased
readiness field (`head_ready_at`, `worker_ready_at`) so consumers can
choose their own bar. (b) matches the existing
`KnowledgeServerHealthMonitor` semantics better. The §3.4 rewrite owes
this distinction.

**§3.5 / Claim 2 — `/infra/capabilities/stream` is multiplexable. CONFIRMED.**
Live capture against the running stack:

```
event: frame
data: {"streamId":"registry:capabilities","frameKind":"LIFECYCLE",
       "seq":1,"ts":"…","payload":{"kind":"connected"},
       "resumeToken":"…"}
```

The envelope (`streamId` + `frameKind` + `payload.kind` + `resumeToken`)
is purpose-built for multiplexing event kinds within one connection
*and* across multiple stream IDs. The "one stream, many event kinds"
phrasing in §3.5 stands. A separate stream ID
(`registry:runtime-manifest`) versus reusing `registry:capabilities`
with a new `payload.kind` is an implementation choice the envelope
already accommodates.

**§3.6 / Claim 3 — `PlatformPaths` is the sole Java data-dir resolver.
CONFIRMED.** A repo-wide grep for `LOCALAPPDATA` and `JUSTSEARCH_DATA_DIR`
direct reads in `modules/**/*.java` returns exactly one match —
`PlatformPaths.java:135`. Every other Java consumer reaches data-dir
through `PlatformPaths.resolveDataDir()` or through resolved-config
(`ConfigStore`, `ResolvedConfigBuilder`) which delegates to it.

**§3.4 / Claim 4 — crash path leaves stale port file. CONFIRMED.**
Experiment (2026-05-20): with the dev stack running on port `54986`,
PIDs `17892` (HeadlessApp) + `28532` (IndexerWorker) force-killed
(`Stop-Process -Force`). After kill, both processes confirmed dead;
`<dataDir>/runtime/api-port.txt` still contained `54986`. Surprising
secondary finding: even after a subsequent `dev_stop --force`, the
file remained stale (the dev-runner kills children but does not run
HeadlessApp's `finally`-block deletion). The 501 fix's behavior
("dev-runner deletes the port file before spawning a *new* backend",
`dev-runner.cjs:946`) is correct as far as it goes — but a stale file
persists in the dataDir between sessions and after crashes. Identity
(`instanceId` check at consumer) is therefore load-bearing, not
nice-to-have.

**§3.7 / Claim 5 — `active.json` lease prevents multi-instance. REVISED.**
The lease *is* enforced via `OWNER_CONFLICT` (`dev-runner.cjs:624-720`,
`732`). But enforcement lives in the orchestrator: only dev-runner-
mediated launches consult `active.json`. A direct
`./gradlew :modules:ui:run`, a manual `java -cp … HeadlessApp`, or
production launches bypass it entirely. Two backends on one dataDir
is prevented for the *dev workflow*, accidentally permitted for every
other launch path. §3.7's phrasing should narrow: the manifest's
contribution is to lift this invariant *into the producer* — the
backend itself refuses to start, regardless of who launched it.

**§3.3 / Claim 6 — no non-JVM MMF readers. CONFIRMED.** Grep for
`MmfWorkerSignal`, `signal-bus`, `MappedByteBuffer` (and the underlying
`worker_signal.lock` filename) across `scripts/`, `modules/ui-web/`,
`modules/shell/src-tauri/` returns nothing. The "non-JVM consumers
could read the MMF heartbeat slot" idea in §3.3 is theoretically
feasible but untried in this codebase. Wording should be softened
from "candidate" to "speculative" until at least one consumer
demonstrates the cross-language read.

**§5 / Claim 7 — `worker-config-snapshot.json` folds into the manifest.
REVISED (wrong scope).** This file is HeadlessApp's *configuration
channel* to IndexerWorker (`HeadlessApp.java:572`, path passed via
`-Djustsearch.worker.config_snapshot`). It is JVM → JVM, not a
runtime-fact-for-external-consumers. Folding it into a non-JVM-facing
manifest mistakes "what's in `runtime/`" for "what's a discovery
surface." §5's row for this file should be removed.

**§5 / Claim 8 — Vite six-candidate path ladder collapses. REVISED.**
`git log` shows the ladder was introduced by `65f217626` itself — it
is the 501 fix, not pre-existing accretion. Under a shared
PlatformPaths contract the *algorithm* deduplicates (one resolver,
many languages) but the *candidate count* stays approximately the same
when `JUSTSEARCH_DATA_DIR` is absent: an env-var-less consumer must
try the same fallbacks `PlatformPaths` tries on the JVM side, plus
the dev-data conventions Vite needs because it runs from a worktree.
§5's "collapses to one path" should be "collapses to one *algorithm*
in one *contract*, not one *candidate*." The right way to make the
candidate count drop is upstream: an orchestrator-injected env var
on every dev launch path, which removes the need for fallbacks.

### Aggregate implications for §3 / §5

- §3.3 (Freshness) — Identity is load-bearing (Claim 4 confirmed).
  Soften MMF-reuse to "speculative" (Claim 6 confirmed).
- §3.4 (Boot affidavit) — Cannot piggy-back on `/infra/capabilities`
  (Claim 1 revised). Manifest needs its own readiness contract, or a
  phased-readiness field. The right phrasing: *the manifest is written
  when the producer has reached the readiness level it claims to have
  reached* — the producer chooses the bar, not the HTTP layer.
- §3.7 (Multi-instance) — Reframe: lease exists in the orchestrator;
  manifest lifts the invariant into the producer (Claim 5 revised).
- §3.6 / §5 (Path ladder) — Contractification deduplicates algorithm,
  not candidates (Claim 8 revised).
- §5 (Deprecations) — Drop `worker-config-snapshot.json` row (Claim 7
  revised; wrong scope). Other deprecations stand.

These revisions do not change the *design intent* (one manifest,
identity, two transports, contractified resolver). They tighten
language that hand-waved over real codebase behavior.

**Update 2026-05-20 (implementation Phase 0):** The Appendix A
revisions have been folded into §3.3 / §3.4 / §3.5 / §3.6 / §3.7 / §5.
The appendix is preserved as historical evidence; the body is now the
authoritative design.

## 10. Out of scope (deliberately)

- Cross-machine discovery. The manifest is local-process /
  local-filesystem by intent. JustSearch is local-first; if remote
  discovery becomes a requirement, it composes on top of the manifest,
  not in place of it.
- Pre-design performance tuning. Filesystem watch vs poll, cache TTL
  curves, write-amplification budgets are measured during
  implementation if they inform a choice; not pre-decided here.

## 11. Implementation log

### Phase 0 — Pre-flight (2026-05-20)

- Folded Appendix A revisions into §3.3 / §3.4 / §3.5 / §3.6 / §3.7 / §5.
- Critical reframe during Phase 0: discovered existing `AppInstanceLock`
  (`modules/app-util/.../AppInstanceLock.java`) — OS-level
  `FileChannel.tryLock()` with PID metadata + stale recovery via
  `ProcessHandle.of()`. The Worker already uses it
  (`KnowledgeServerBootstrap.java:108`); the Head does not. Pivoted §3.3
  away from inventing `manifest.lock` and §3.7 toward lifting
  `AppInstanceLock` acquisition into the Head. The manifest's own `pid`
  field is the consumer's liveness signal.

### Phase 22 — Worker capability listener + indirect-change sweep (2026-05-21)

User asked for a deep look at indirect changes and remaining work.
This phase closes that pass.

**Correctness gap fixed: Worker capability listener.** Phase 12's
`publishWorkerReady` / `publishWorkerFailed` ran once after
`connectWorker` and never again. If the Worker died post-boot,
`worker.state` stayed at the first observed value — the exact stale-
projection bug §12.1 says the manifest is supposed to project away.
Phase 13 wired the symmetric pattern for `InferenceCapability`; the
Worker side was missed. Phase 22 wires it.

HeadlessApp now registers two capability listeners (separately, in
the order each capability's data structures are constructable):
- `InferenceCapability.addListener` (Phase 13) — fires on every
  inference transition.
- `WorkerCapability.addListener` (Phase 22) — registered after the
  initial worker-state publish so the closure captures the post-
  `connectWorker` `WorkerConnectionResult`. Fires on every worker
  transition: READY → republish with fresh grpcPort, OFFLINE/
  DEGRADED/RECOVERING → publish "failed" with the upstream
  `pendingReason`, PENDING → refresh lifecycle.

**Live-verification caveat:** the listener wiring is correct; live
firing depends on the upstream `WorkerCapability.transition(...)`
actually being called. Empirically, force-killing the Worker JVM
does not produce a fast capability transition — the codebase's
worker-liveness detection (presumably `KnowledgeServerHealthMonitor`)
doesn't react to bare JVM death within the 90s observation window.
That's a pre-existing project concern, not a 501 concern. The §12.1
projection rule is met: when the upstream capability transitions,
the manifest re-projects. The frequency of upstream signal firing
is out of scope for the manifest's design.

**Indirect-reference sweep:**
- `OpenAiCompatController.java:24` javadoc — refreshed; now cites
  the manifest's `head.apiPort` instead of `api-port.txt`.
- `docs/reference/api-contract-map.md:496` — refreshed; cites both
  the manifest and the well-known mirror.
- `scripts/sandbox/sandbox-start-SKILL.md`,
  `sandbox-CLAUDE.md`,
  `sandbox-environment.md` — three sandbox docs updated to read
  `head.apiPort` from `manifest.json` instead of polling
  `api-port.txt`. PowerShell snippets refreshed.
- Old tempdocs (200, 257, 259, 263, 264, 271) — historical records;
  the project convention is to leave them as-is.

**Canonical convention doc** (`docs/explanation/23-runtime-manifest.md`):
refreshed to reflect Phases 12–21 — `lifecycle`, `worker.state`,
`spawnError`, `ai` sub-record, well-known mirror, MCP tool, the
projection rule, and the capability-listener pattern.

**Tempdoc frontmatter:** `revised: 2026-05-21`. `related:` expanded
with 500/502/518/519/521 to reflect the cross-tempdoc citations §12
now carries.

**Test-suite hygiene:**
- `McpProtocolHandlerTest` had an assertion `assertEquals(5,
  tools.size())` that Phase 15's sixth tool broke. Fixed: now asserts
  6 tools and includes `justsearch_runtime_manifest` in the position-
  ordered assertion list.
- Full `:modules:ui:test`: only `AiInstallServiceLateBindTest` still
  fails — pre-existing baseline failure on origin/main, unrelated
  to 501, already logged in `docs/observations.md`.
- `:modules:app-api:test`: green.
- `:modules:configuration:test --tests *PlatformPathsContractTest*`:
  green.
- `cd modules/ui-web && npm run typecheck`: clean.
- `cd modules/ui-web && npm run test:unit:run`: **1592/1592 tests
  pass.**

**Dev-mcp audit:** `scripts/dev/justsearch-dev-mcp/server.mjs`
grepped for `api-port` — zero matches. Clean.

The substrate is now consistent end-to-end: no stale references in
the comments, every consumer reads the manifest, every consumer-side
test asserts the current shape, and the canonical convention doc
matches the implementation.

### Phase 21 — Real-browser validation + Vite proxy gap fix (2026-05-21)

User flagged that the prior phases verified the manifest at the HTTP
level (curl) but had not been exercised from the real browser UI.
This phase closes that gap.

**Vite proxy gap caught (and fixed).** The Phase 14 well-known mirror
works on the *backend* directly (port 58288 → 200 JSON) but the dev
Vite server's proxy plugin only mounted `/api/*`. Requests to
`/.well-known/justsearch/manifest.json` from the browser fell through
to Vite's SPA fallback and returned `text/html` (the index.html).
The `vite.config.js` proxy plugin now mounts on both prefixes — the
plugin loops over `['/api', '/.well-known/justsearch']` and registers
a handler per prefix that forwards verbatim to the backend. Without
this, any browser-side consumer that follows the RFC-8615 convention
would silently get HTML instead of the manifest.

**Stale-environment hygiene caught.** During verification a stale
Vite from the main repo was supervised by some external process (npm
watch task) and kept respawning on port 5173 ahead of my dev-runner.
Worked around by passing `--ui-port 5188` to dev-runner; documented as
an environmental observation, not a producer-side issue.

**Real-browser end-to-end matrix (Chrome via claude-in-chrome MCP):**

| Transport | Browser URL | Verdict |
|---|---|---|
| HTTP REST | `localhost:5188/api/runtime/manifest` | 200, full JSON; instanceId `d4ceb24d-b693-4bdf-b8a2-f1aee5439ece`. |
| Well-known | `localhost:5188/.well-known/justsearch/manifest.json` | 200, **byte-for-byte identical** body. |
| SSE | EventSource `/api/runtime/manifest/stream` | universal-envelope LIFECYCLE frames (`connected` + `snapshot`), `streamId: registry:runtime-manifest`. |
| FE helper | `import('/src/api/http.ts').fetchRuntimeManifest('http://127.0.0.1:58288')` | returns manifest with matching instanceId. |
| FE helper | `import('/src/api/http.ts').resolveApiEndpoint()` | returns `{source: 'env', port: 58288, instanceId: d4ceb24d-...}` — Phase 11 enrichment fetch confirmed. |
| MCP tool | `tools/call name=justsearch_runtime_manifest` | (Phase 15 verification, re-confirmed; same identity in the same run.) |
| Filesystem | `<dataDir>/runtime/manifest.json` | Phase 18 verified; runtime dir contains only the canonical set. |

**Security verifications via real browser:**

- `head.sessionToken` field is **absent** from every HTTP-served body
  the browser receives (REST, well-known, SSE snapshot). The
  `sessionTokenAbsent: true` flag observed in JS.

**Identity match:** the SPA's `fetchRuntimeManifest` and
`resolveApiEndpoint` both return the same `instanceId` —
`identityMatch: true`. The Phase 11 cache-by-identity story is
exercisable from the live FE.

**Tauri tray-icon (Phase 17) caveat:** validation requires a packaged
Tauri build with the `resources/headless/**` glob populated. That's
gated on the Phase 7 packaging story and remains a packaging-time
verification, not a producer-side concern. The Rust code path is
unit-tested and the OnceLock indirection keeps the runtime out of
test linkage.

The two gates remain green; `status: implemented` unchanged. Every
non-Tauri transport is validated from a real browser context.

### Phase 20 — Closure pass for Phases 12–19 (2026-05-21)

Final end-to-end verification across every transport, against the
full dev stack started via `dev-runner`.

- Filesystem `<dataDir>/runtime/manifest.json` — instanceId
  `0b85518f-bcfe-462e-96a8-35a02961bf13`, full projected shape
  (`schemaVersion`, `pid`, `startedAt`, `dataDir`, `lifecycle`,
  `head`, `worker.state`, `worker.spawnError`, `ai.phase`,
  `ai.required`, `ai.pendingReason`).
- HTTP `GET /api/runtime/manifest` — identical body shape with
  same instanceId.
- HTTP `GET /.well-known/justsearch/manifest.json` — 200, 703 bytes,
  same body.
- MCP `tools/call name=justsearch_runtime_manifest` — same shape
  returned via `content[0].text` + `structuredContent`.
- `<dataDir>/runtime/` directory contents: exactly `instances/`,
  `manifest.json`, `worker-config-snapshot.json`. No `api-port.txt`.
- Closure-check script: 1730 files scanned, 0 violations.

Five views, one identity. The §6 closure rule continues to hold; the
§12.1 projection rule applies for every field added in Phases 12–13;
the §12.4 transport rule applies for the well-known + MCP additions
in Phases 14–15; §12.3 backward-compat is guarded mechanically by
Phase 16; §12.5 derivative-UX direction is exercised by Phase 17;
§12.6 trust-envelope blocker is documented (Phase 19); the §5
deprecation cycle is closed (Phase 18).

The tempdoc gates remain green. `status: implemented` is unchanged.

### Phase 18 — Drop `api-port.txt` outright (2026-05-21)

The §5 deprecation completed: the legacy `api-port.txt` mirror is
gone. The full audit identified six consumers; all migrated.

Consumer audit (read sites):
- `vite.config.js` — comment-only reference; no behaviour change.
- `dev-runner.cjs` — comment-only after Phase 11; no behaviour change.
- `OpenAiCompatController.java` — comment-only; no behaviour change.
- `scripts/prod/justsearch-mcp/discovery.mjs` — had a legacy
  fallback branch; **removed**. Discovery flows exclusively through
  `findRunningManifest()` now. The unused `readPortFile` helper and
  the `fsp` import that supported it are also gone.
- `IsolatedBackendFixture.java:248` (integration test harness) —
  **migrated** to read `manifest.json`'s `head.apiPort`.
- `ui/build.gradle.kts:1423` (sidecar smoke task) — **migrated** to
  parse `manifest.json` via Jackson.

Producer side:
- `HeadlessApp.emitPortSignals` — the `api-port.txt` write blocks
  for both home and dataDir roots are gone. Stdout
  `JUSTSEARCH_API_PORT=<port>` line stays as human log.
- HeadlessApp's `finally` block no longer cleans `api-port.txt` —
  the publisher's `close()` handles its own file.

Closure check:
- `scripts/ci/check-runtime-manifest-closure.mjs` allowlist drops
  the `api-port.txt` entry. The closure rule now mechanically
  rejects any reintroduction.

Live verification (2026-05-21):
- Full dev stack via `dev-runner start`: apiPort 49189; runtime
  directory contents are `instances/`, `manifest.json`,
  `worker-config-snapshot.json` — **no `api-port.txt`**.
- Prod MCP `discover()` returns the port via manifest:
  `{port: 49189, instanceId: c615c205-...}`.
- The §5 deprecation cycle is closed: producer write removed,
  consumers migrated, allowlist updated, closure check green.

### Phase 17 — Tray-icon dynamic state (Rust) (2026-05-21)

§12.8/§12.9 known gap closed. The Tauri shell's tray tooltip now
reflects the manifest's `lifecycle` projection in real time.

- `TrayIconBuilder::with_id("justsearch-main-tray")` — explicit id so
  the manifest watcher can look up the live `TrayIcon` via
  `app.tray_by_id(...)` at update time, rather than caching the
  handle directly.
- `BackendState` gains a `tray_id: Mutex<Option<String>>` field
  (string, not `TrayIcon`).
- A `OnceLock<tauri::AppHandle>` (`TRAY_CONTEXT`) is stored as a
  module-level static — *not* a `BackendState` field. Caching the
  `AppHandle` (or `TrayIcon`) on `BackendState` directly forced the
  Tauri runtime into the test binary and failed to link on Windows
  with `STATUS_ENTRYPOINT_NOT_FOUND`. The `OnceLock` indirection
  keeps that runtime out of test linkage; the global is set exactly
  once at app setup and never touched in tests.
- The existing `watch_manifest` thread becomes long-lived: a fast
  100ms phase for initial port acquisition, then drops to 1s polling
  to drive tray-tooltip updates as lifecycle transitions land.
  Tooltip format: `JustSearch · <LIFECYCLE>` (e.g.,
  `JustSearch · READY`, `JustSearch · DEGRADED`). Skips writes when
  unchanged.
- `read_manifest_if_present` extended to parse the top-level
  `lifecycle` field added in Phase 12.
- `cargo check --lib`: clean. `cargo test --lib platform_paths::`:
  2/2 green (the linker-blocker was the cause of the prior
  `STATUS_ENTRYPOINT_NOT_FOUND`; the `OnceLock` workaround resolved
  it). Full Tauri-app smoke against a real shell remains gated on
  packaged resources (per §B.7 §Phase 7 carve-out).

### Phase 16 — Schema-evolution backward-compat test (2026-05-21)

§12.3 closure mechanism: a unit-test guard that a v1-shaped body
parses into the current record, and that the current shape doesn't
require fields older bodies don't carry.

- New `RuntimeManifestSchemaCompatibilityTest` in `modules/app-api`
  with three assertions:
  1. **v1-only body parses cleanly** into the current
     `RuntimeManifest` record. New (Phase 12/13) fields surface as
     `null` — the *backward direction* of the §12.3 widening rule.
  2. **Future fields tolerated** by a `FAIL_ON_UNKNOWN_PROPERTIES=false`
     reader — the *forward direction* the projection-widening rule
     depends on. Hypothetical future top-level and nested fields
     don't break parsing.
  3. **Strict reader refuses** unknown fields — sanity-checks that the
     forward-compat tolerance is an explicit reader choice, not an
     accidental default.
- If a future contributor adds a required field without a default
  (or changes a field's type incompatibly), this test fails at PR
  time. The §12.3 rule becomes mechanically enforced.
- 3/3 green.

### Phase 15 — MCP `runtime_manifest` tool (2026-05-21)

§12.4 transport expansion: an MCP-native surface carries the same
projection.

- `McpToolSurface` adds a `justsearch_runtime_manifest` curated tool
  entry (sixth in the list per the 500 design's curated set).
- Constructor gains an optional `manifestPublisherLookup` parameter;
  `LocalApiServer` wires the publisher through. Test-only Builder path
  still works with the original 5-arg constructor.
- `callRuntimeManifest()` reads `publisher.current()`, applies the
  shared `RuntimeManifestController.redactSensitive(...)` (the static
  method is now public so MCP and REST share one redaction matrix),
  and returns the redacted manifest as both `content[0].text` (string
  JSON) and `structuredContent` (object).
- **Live verification (2026-05-21):**
  - `tools/list` returns six tools including
    `justsearch_runtime_manifest`.
  - `tools/call name=justsearch_runtime_manifest` returns the same
    `instanceId` / `lifecycle` / `worker` / `ai` shape as
    `GET /api/runtime/manifest`. Identity match confirmed via the
    instanceId UUID in both responses.

### Phase 14 — Well-known transport mirror (2026-05-21)

§12.4 transport expansion: same redacted manifest body served at the
RFC-8615 standard location.

- `LocalApiServer.setupRoutes` adds
  `app.get("/.well-known/justsearch/manifest.json",
  runtimeManifestController::handleGet)` next to the existing
  `/api/runtime/manifest` binding. Same controller, same redaction,
  same body.
- The new route is the discovery surface for external HTTP-only
  consumers (browser extensions, third-party tooling) that follow
  the RFC-8615 convention.
- **Live verification (2026-05-21):**
  - `GET /api/runtime/manifest` → 200, 666 bytes.
  - `GET /.well-known/justsearch/manifest.json` → 200, 666 bytes.
  - `diff` of the two responses: **byte-for-byte match.**

### Phase 13 — AI projection from InferenceCapability (2026-05-21)

§12.1 projection extended to the inference (AI) surface.

- `RuntimeManifest` gains a new nullable `ai: AiInfo` sub-record.
  `AiInfo` carries `phase` (the `CapabilityHealth` enum name —
  `PENDING` / `READY` / `DEGRADED` / `OFFLINE` / `RECOVERING`),
  `required` (whether inference is configured), `pendingReason`
  (the upstream reason text), and a nullable `readyAt` ISO timestamp
  set when the phase first becomes `READY`.
- `RuntimeManifestPublisher.publishAi(phase, required, pendingReason,
  readyNow, lifecycle)` writes the new sub-record + recomputed
  lifecycle atomically.
- HeadlessApp wires `inferenceCapability().addListener(...)` so any
  inference transition rewrites the manifest with a fresh AI snapshot.
  An initial publish also runs after worker-state so fresh consumers
  see `ai` populated without waiting for the next transition.
- Authoritative source per §12.1: `InferenceCapability.health()` /
  `required()` / `pendingReason()`. No state is invented; the
  manifest is purely a projection.
- **Live verification (2026-05-21):** disposable dataDir, no AI
  activation. Manifest shows:
  ```json
  "ai" : {
    "phase" : "PENDING",
    "required" : true,
    "pendingReason" : "Inference not yet activated"
  }
  ```
  After `ai_activate` (not exercised in this isolated launch — Worker
  was failed), the listener would rewrite with `phase: "READY"` and
  `readyAt` populated. The producer-side wiring is in place.

### Phase 12 — Projection rule application: lifecycle + worker.state (2026-05-21)

§12.1's projection rule applied for the first time after the §12 rewrite.

- `RuntimeManifest` gains a top-level `lifecycle` field carrying the
  `LifecycleProjection.derive(WorkerCapability, InferenceCapability)`
  result. Values are the canonical `LifecycleState` enum names
  (`STARTING` / `READY` / `DEGRADED` / `ERROR`). The manifest now
  carries one-field overall state instead of forcing consumers to
  compose sub-records.
- `RuntimeManifest.WorkerInfo` gains:
  - `state` discriminator (`"pending"` / `"ready"` / `"failed"`) — the
    tri-state surface that replaces the null-vs-populated conflation
    flagged in §12.8.
  - `spawnError` string — populated when state is `"failed"`, carries
    the human-readable reason from `KnowledgeServerStartResult`.
- `RuntimeManifestPublisher` API revised:
  - `publishWorker(...)` → split into `publishWorkerReady(grpcPort,
    indexBasePath, lifecycle)` and `publishWorkerFailed(reason,
    lifecycle)`. Both take the projected lifecycle so the publisher
    stays transport-only; HeadlessApp computes the projection.
  - New `publishLifecycle(state)` for transitions that change lifecycle
    without a worker event (e.g., inference becoming ready). No-op
    when value is unchanged.
- HeadlessApp call-site: after `connectWorker` resolves, project
  lifecycle via `LifecycleProjection.derive(workerCap, inferenceCap)`
  and call `publishWorkerReady` OR `publishWorkerFailed` based on
  whether the bootstrap produced a connected handle.
- Tests: publisher unit tests extended to cover the new methods plus
  lifecycle projection. 12/12 green.
- **Live verification (2026-05-21):** disposable dataDir without a
  prebuilt worker dist. Manifest correctly shows:
  ```json
  "lifecycle" : "ERROR",
  "worker" : {
    "state" : "failed",
    "spawnError" : "Worker lib directory not found. Build with:
                    ./gradlew :modules:indexer-worker:installDist"
  }
  ```
  Pre-Phase-12 behaviour was `worker: null` with no lifecycle
  signal — consumers would wait indefinitely. Now they get an
  actionable reason.

### Phase 11 — Closure pass against the critical-analysis gaps (2026-05-21)

Critical re-analysis flagged seven gaps. Each is now addressed:

1. **§6 closure rule mechanical enforcement.**
   `scripts/ci/check-runtime-manifest-closure.mjs` greps for any code
   constructing a `<dataDir>/runtime/<artifact>` path and fails on any
   artifact not in its allowlist. Added to CLAUDE.md's pre-merge script
   list and `docs/explanation/23-runtime-manifest.md` (new canonical
   convention doc). Negative-test confirmed: introducing
   `dataDir.resolve("runtime").resolve("evil-sidecar.json")` trips the
   guard immediately.
2. **Rust contract test against fixtures.** Added two
   `runners: ["rust"]` fixtures (env-var-set + ${user.home}-expansion).
   Rust harness now actually exercises `resolve_data_dir()` against
   hermetic env-overrides under a serialized mutex. Java + Node fixtures
   continue to pass (7/7 + 5/5); Rust runs the 2 new ones (2/2).
3. **Dev-runner data-dir resolver.** Dev-runner is the orchestrator
   that *sets* `JUSTSEARCH_DATA_DIR` for HeadlessApp; it doesn't
   resolve. The closure rule applies to resolvers. Closure check's skip
   list documents the carve-out inline.
4. **Browser-side manifest fetch.** `http.ts` now exposes
   `fetchRuntimeManifest(baseUrl)` and `resolveApiEndpoint` makes a
   best-effort fetch against `GET /api/runtime/manifest` to populate
   `endpoint.instanceId`. Failure is non-fatal; consumers caching by
   identity get the field when available.
5. **Per-instance history.** `RuntimeManifestPublisher` writes each
   publish to `<dataDir>/runtime/instances/<instanceId>/manifest.json`
   alongside the canonical last-writer-wins path. Live-verified — the
   directory appears with the current instance's snapshot.
6. **Dev-runner stdout-as-discovery violation.** The stdout
   `JUSTSEARCH_API_PORT=` parse-to-state is removed. Stdout drain
   stays for logging only. The wait-loop reads `manifest.json`
   exclusively; the historical `portLine` audit field in `run.json` is
   replaced with `portSource` + `portSourceInstanceId` so the
   breadcrumb survives. Legacy `api-port.txt` fallback also removed
   from the wait-loop (dead code in dev-runner context).
7. **Session-token HTTP leakage.** `RuntimeManifestController.redactSensitive`
   strips `head.sessionToken` from the HTTP-served body and the SSE
   broadcasts. Filesystem manifest still carries the token (gated by
   filesystem permissions). Three unit tests cover the redaction
   matrix (strips-when-present, identity-when-absent, preserves-
   worker).

Live verification (2026-05-21):
- Cold-start dev stack against `<worktree>/modules/ui-web/.dev-data`,
  runId `c93fd5f9-…`, port 52061, `instanceId
  46a25119-fd3d-4f3a-b71f-449a73f4bc6b`.
- Filesystem manifest, HTTP `/api/runtime/manifest`, and SSE all carry
  the same instanceId. HTTP body confirms no `sessionToken` field
  (dev mode has no token to strip; redaction unit-tested for prod mode).
- `runtime/instances/46a25119-…/manifest.json` snapshot present.
- Closure check (`node scripts/ci/check-runtime-manifest-closure.mjs`):
  1730 files scanned, 0 violations.

### Phase 10 — Tempdoc closure (2026-05-20)

- Status: open → implemented.
- Both gates (frontmatter) green:
  1. *"One producer-published document is the canonical source of all
     non-JVM runtime facts."* Filesystem `manifest.json` + HTTP
     `/api/runtime/manifest` + SSE `/api/runtime/manifest/stream`,
     same shape, written by HeadlessApp.
  2. *"Data-dir resolution lives in one contract consumed by every
     language."* `contracts/platform-paths/spec.v1.json` with Java,
     Node, and Rust implementations; contract tests gate drift.
- Architectural choice points (from the Phase plan) and their final
  outcomes:
  1. SSE dedicated stream — kept.
  2. PlatformPaths contract as neutral JSON spec — kept.
  3. Multi-instance guard via lock-file with PID + instanceId —
     **revised mid-Phase-1**: reused the existing OS-level
     `AppInstanceLock` (FileChannel.tryLock + PID metadata + stale
     recovery) instead of inventing a parallel `manifest.lock`. The
     manifest's own `pid` field is the consumer's freshness signal.
     Stronger guarantee (OS mutex) for zero extra mechanism.
  4. Phased readiness (head/worker) — kept.
- Open follow-ups documented in §Phase 7 (Rust-runner contract
  fixture), §Phase 8 (api-port.txt eventual removal pending
  consumer audit), and the
  `AiInstallServiceLateBindTest` pre-existing failure in
  `docs/observations.md`.

### Phase 9 — End-to-end live verification (2026-05-20)

Full dev-stack smoke against the worktree-installed dist. Single
identity (`instanceId`) propagates across every consumer; restart
generates a fresh identity that consumer caches detect automatically;
crash-recovery refuses to use a stale manifest.

**Cold-start agreement** — `dev-runner start --data-dir
<worktree>/modules/ui-web/.dev-data`:
- runId `0baabf49-...`, apiPort 61404.
- Manifest on disk: `instanceId=d43283a2-d111-4174-939a-286013d350a5`.
- `GET /api/runtime/manifest` → 200 with identical JSON.
- SSE `/api/runtime/manifest/stream` → universal-envelope frames with
  `streamId: registry:runtime-manifest`, LIFECYCLE connected +
  snapshot.
- `node -e "discover(…)"` (prod MCP) → returns
  `{port: 61404, instanceId: d43283a2-...}`.
- Bare `npx vite --port 5188` (no env overrides) → 200 on `/api/status`
  via manifest discovery; pass-through of `/api/runtime/manifest`.

**Crash recovery** — force-kill Head JVM (PID 23720):
- Manifest survives on disk with dead PID.
- Vite proxy → 502 with the new "No running JustSearch backend
  discovered" body. `isPidAlive(23720)` returned false; cache
  invalidated; refuses to route.
- Prod MCP `discover()` → "Could not find a running JustSearch
  instance." Same identity check, same refusal.

**Auto-recovery and cache invalidation** — `dev-runner start --clean
none` after the kill:
- New head bound port 61510 with fresh
  `instanceId=ba004120-9d68-4039-b63c-030f3f059414`.
- Same Vite process still running (not restarted). `curl
  http://localhost:5188/api/status` → 200. The cached
  `instanceId=d43283a2-...` failed its PID-alive check, cache
  dropped, fresh manifest discovered, proxy rerouted to the new
  port. **Identity-over-liveness cache works without any
  consumer-side restart or TTL guess.**

**Multi-instance guard** — launch a second head against the same
dataDir while the first is running:
- Second head exits with code 2 ("DATA DIRECTORY LOCKED").
  `AppInstanceLock` correctly refuses; the design's producer-side
  invariant holds.

### Phase 8 — Deprecation pass (2026-05-20)

- HeadlessApp's `emitPortSignals` now carries a deprecation comment on
  the `api-port.txt` write: thin mirror only, new consumers must read
  the manifest. The write itself stays for legacy third-party tooling
  + the prod-MCP legacy fallback until an upstream consumer audit
  confirms no reader remains.
- HeadlessApp's stdout `JUSTSEARCH_API_PORT=` / `JUSTSEARCH_SESSION_TOKEN=`
  lines stay as human-readable log output. They are no longer parsed
  by the Tauri shell.
- Tauri shell stdout pipe drained for logging only — consumer-side
  parse-to-state for both port and session token removed. The
  `JUSTSEARCH_SESSION_TOKEN=` line-skip stays as a security redaction
  so the token never lands in the log file even though the shell
  no longer reads it from there. Manifest watcher (Phase 7) is the
  canonical discovery path.
- Dev-runner keeps its stdout port parse as a *primary* fast-path
  (fires before the manifest poll ticks) with the manifest read as
  the immediate fallback (Phase 6). Both stay because dev-runner is
  the orchestrator and benefits from sub-100ms readiness signal.

### Phase 7 — Tauri shell migration (2026-05-20)

- New Rust module
  `modules/shell/src-tauri/src/platform_paths.rs` — third
  implementation of the `contracts/platform-paths/spec.v1.json`
  contract. Mirrors the Java + Node twins (env-var precedence, Windows
  `LOCALAPPDATA`-or-userHome fallback, macOS `Library/Application
  Support`, Linux dotfile). Includes a contract-test harness that walks
  the spec's fixtures and filters by `runners: ["rust", ...]`.
- `lib.rs` gains a `watch_manifest` thread spawned alongside the JVM
  child. Polls `<app_data_dir>/runtime/manifest.json` every 100ms (up
  to 60s), parses `head.apiPort` + `head.sessionToken`, and signals
  `BackendState` via the existing `set_port` / `set_session_token`
  idempotent setters. Stdout parsing remains as a backup (Phase 8
  deprecates stdout-as-discovery — producer keeps emitting for human
  log readability).
- Spec gains an `_runnersField` documentation marker explaining the
  3-value runners array (java/node/rust) so future contributors don't
  have to spelunk to find the convention.
- Verification scope: Tauri build needs a real bundled headless dist
  (`resources/headless/**/*` glob), which is Gradle's
  responsibility, not the worktree's. Phase 7 compiles and unit-tests
  the Rust code; full Tauri-app smoke against a running shell is
  carried into Phase 9. `cargo test --lib platform_paths::` → 2/2
  green (the contract harness loads the spec and the user-home
  expander).
- Open follow-up: at least one fixture targeting `"runners": ["rust"]`
  to actually exercise resolve_data_dir() under the harness. The
  branch logic is verified identical to Java/Node by code review; an
  in-process env-override fixture is mechanically deferrable.

### Phase 6 — Prod MCP + dev-runner migration (2026-05-20)

- `scripts/prod/justsearch-mcp/discovery.mjs` now uses the shared
  `findRunningManifest` as its primary path. Order: explicit `--port`
  override → `JUSTSEARCH_API_PORT` env override → runtime manifest
  (PID-alive checked) → legacy `api-port.txt` (older builds only) →
  port probe `33221-33250`. Manifest hit returns the `instanceId` so
  downstream MCP tools can cache or audit identity.
- `scripts/dev/dev-runner.cjs` reads `manifest.json` alongside
  `api-port.txt` during the post-spawn wait loop (manifest preferred
  because it carries `instanceId`). Both files are cleaned pre-spawn
  to avoid stale-read regressions.
- `run.json::resourceClaims` gains two new fields:
  - `runtimeManifestPath` — filesystem path to the producer's manifest.
  - `runtimeManifestInstanceId` — read at run.json-write time. Cross-
    links the orchestrator's audit view with the producer's identity
    (tempdoc §3.7 closure: restarts changing instanceId are detectable
    from either view; stale orchestrator state becomes a mechanical
    instanceId mismatch).

Live verification
- `node scripts/dev/dev-runner.cjs start --data-dir <worktree>/modules/
  ui-web/.dev-data --clean soft --skip-build --json` → ok, runId
  62809ffc-..., apiPort 54964.
- `<worktree>/.../runtime/manifest.json` carries
  `instanceId=35fd0c5b-9853-4cc5-87a1-34d58fccc0e6`.
- `<repo>/tmp/dev-runner/runs/62809ffc-.../run.json::resourceClaims`
  carries `runtimeManifestInstanceId=35fd0c5b-9853-4cc5-87a1-...` —
  exact match.
- `node -e 'import(./scripts/prod/justsearch-mcp/discovery.mjs).then(
  m => m.discover({verbose: true}))'` →
  `Found manifest in <dataDir> (instanceId=35fd0c5b...)`, returns
  `{port: 54964, instanceId: 35fd0c5b...}`. Production discovery path
  agrees with dev-runner audit and producer self-declaration.

### Phase 5 — Vite consumer migration (2026-05-20)

- `vite.config.js` now imports `findRunningManifest` + `isPidAlive` from
  `scripts/lib/platform-paths.mjs`. The legacy 6-candidate path ladder
  for `api-port.txt` is gone; the worktree-aware candidate logic moved
  into the shared `findRunningManifest()` so it's reusable by dev-runner
  and prod-MCP (Phase 6).
- Cache key changed from `2s TTL` to `pid liveness via process.kill(pid,
  0)`. The cache holds the parsed manifest fields (port, instanceId,
  pid, dataDir) and is invalidated when the cached PID stops responding
  to a signal-0 probe.
- Env override paths (`VITE_JUSTSEARCH_API_PORT`, `VITE_API_PORT`)
  retained for test pinning.
- Plugin error body upgraded from "No backend port discovered" to "No
  running JustSearch backend discovered" with a hint.
- `findRunningManifest` candidate order: caller-supplied extras
  (worktree-local `modules/ui-web/.dev-data` from Vite) → env var →
  worktree/repo walk for `.dev-data` → platform default.
- Live-verify against worktree-local dev-data:
  * Spawn Head with `JUSTSEARCH_DATA_DIR=<worktree>/modules/ui-web/.dev-data`.
  * Spawn Vite under bare `npx vite` (no env overrides).
  * `curl http://localhost:5182/api/status` → **200** with the head's
    StatusResponse body. Proxy resolves through the worktree-local
    manifest.
  * Force-kill Head JVM (PID 18400). Manifest survives on disk with
    stale PID.
  * Next `curl` → **502** with the new error body. Vite refused to
    proxy because `isPidAlive(18400)` returned false. Identity-over-
    liveness story end-to-end.

### Phase 4 — PlatformPaths contractification (2026-05-20)

- New canonical spec at `contracts/platform-paths/spec.v1.json` —
  describes precedence chain, env-var/sysprop names, platform-default
  templates, postProcessing (`${user.home}` expansion), and a fixture
  set that drives the cross-language contract tests.
- New canonical Node module at `scripts/lib/platform-paths.mjs`. Single
  source of truth for `resolveDataDir`, `expandUserHomePlaceholders`,
  `resolveManifestPath`, `readManifestSync`. Phases 5 and 6 swap the
  drifting copies in `vite.config.js`, `dev-runner.cjs`, and
  `prod/justsearch-mcp/discovery.mjs` over to this module.
- Java contract test
  (`modules/configuration/.../PlatformPathsContractTest.java`) and
  Node contract test (`scripts/lib/platform-paths.contract.test.mjs`)
  each consume the same spec and assert identical outputs for every
  fixture they can hermetically reproduce.
- Each fixture optionally declares `"runners": ["java", "node"]`. The
  two Windows-default fixtures (which require overriding the host's
  `LOCALAPPDATA` env var) are marked `node-only` because Java cannot
  hermetically override `System.getenv` at runtime. A future Java
  refactor that accepts an env-lookup callable could lift them; the
  current code is identical in logic to the Node twin per spec.
- Live-verify: `./gradlew :modules:configuration:test --tests
  *PlatformPathsContractTest*` → 5/5 Java fixtures green; `node
  scripts/lib/platform-paths.contract.test.mjs` → 7/7 Node fixtures
  green. Cross-language agreement under the spec.

### Phase 3 — Multi-instance producer guard (2026-05-20)

- HeadlessApp acquires `AppInstanceLock` immediately after
  `resolveConfig` (before HTTP bind, before Worker spawn). On
  `AppInstanceLockException` the Head logs "DATA DIRECTORY LOCKED" and
  exits with code 2. Reuses the existing OS-level FileLock + PID
  metadata + stale recovery; does not introduce a parallel mechanism.
- `AppInstanceLock` gains static `isHeldByThisJvm(dataDir)` so
  `KnowledgeServerBootstrap.start()` can skip its redundant in-JVM
  acquisition that would otherwise throw
  `OverlappingFileLockException`. Standalone test paths (which
  instantiate `KnowledgeServerBootstrap` directly with their own temp
  dataDirs) keep the acquire branch. The in-JVM set is cleared on
  `close()` so test stop/start cycles reacquire cleanly.
- Shutdown: lock released LAST in the shutdown hook so any subsystem
  touching the dataDir during teardown still sees the lock held.
  Idempotent close in the outer `finally`.
- Design pivot caught during this phase: a first pass used a system
  property as the in-JVM held flag. The `AppServicesWorkerGuardrails`
  ArchUnit test (rightly) blocks ad-hoc sysprop reads in
  `app.services`. Replaced with the in-class static set; the
  guardrail stays green and the coordination is no longer hidden in
  the property bag.
- Live-verify: against `/tmp/501-phase3a`:
  * First head launched cleanly, writes `manifest.json` + `app.lock`
    with pid=15264.
  * Second head launched against same dataDir → exits with code 2
    immediately (lock-held diagnostic in stderr/log path).
  * Force-killed first head (PID 15264 dead, `app.lock` stale with
    dead PID metadata).
  * Restarted head → `AppInstanceLock`'s `ProcessHandle.of(15264)`
    returns empty, stale-recovery deletes the file, re-acquires; new
    manifest written with a fresh `instanceId`.

### Phase 2 — HTTP transport + SSE (2026-05-20)

- `RuntimeManifestController` serves `GET /api/runtime/manifest` (returns
  current manifest or 503 if not yet published).
- `RuntimeManifestStreamController` owns its own `SseStreamChannel` with
  streamId `registry:runtime-manifest`. UPDATE frames fan out from the
  publisher's listener (single broadcast on each manifest write).
- LocalApiServer.Builder gains `.runtimeManifestPublisher(publisher)`;
  HeadlessApp threads the publisher through. Test-only Builder path
  null-defaults the new controllers.
- Stream-controller shutdown hook added next to the capabilities one.
- **Live finding (and fix):** initial implementation used the publisher's
  `addListener` snapshot-replay to fire the first UPDATE. That fired a
  spurious UPDATE at controller-construction time (before the manifest
  was published). Removed the replay-on-register: listeners now only see
  *future* changes; current state is read via `current()`. Test
  `listenerOnlyFiresForFuturePublishes` documents the new contract.
- Live-verify: against `/tmp/501-test-data2`, `GET /api/runtime/manifest`
  returns the JSON document; SSE stream returns the universal envelope
  with `streamId: registry:runtime-manifest` and LIFECYCLE frames
  (connected + snapshot). The single UPDATE from publishHead is retained
  in the channel's ring buffer for `?since=` resume.

### Phase 1 — Manifest record + producer write (2026-05-20)

- Added `RuntimeManifest` record in
  `modules/app-api/src/main/java/io/justsearch/app/api/runtime/`,
  `@RecordBuilder`-generated, with phased readiness shape
  (`head.readyAt` always present, `worker` nullable until Worker
  connects). Schema version 1.
- Added `RuntimeManifestPublisher` in
  `modules/ui/src/main/java/io/justsearch/ui/runtime/`. Generates
  `instanceId` (UUID) at boot, writes manifest atomically (temp +
  atomic rename), notifies listeners synchronously on each publish.
- Wired HeadlessApp: publisher constructed after `resolveConfig`,
  `publishHead` called after `buildApi`, `publishWorker` called after
  `connectWorker` (uses `knowledgeServer.signalBus().readPort()` for
  grpcPort and `configStore.get().paths().indexBasePath()` for index
  path). `close()` called from shutdown hook (BEFORE HTTP teardown, per
  §3.4) and from `finally` (idempotent).
- Tests: 8 unit tests in `RuntimeManifestPublisherTest` (fresh UUID per
  publisher, listener notification, phased rewrites, illegal-state
  guards, JSON shape, atomic file removal, late-listener snapshot
  replay) — all green.
- Live-verify: launched `./modules/ui/build/install/ui/bin/ui` from the
  worktree against `/tmp/501-test-data`. Manifest appeared with correct
  shape (`schemaVersion:1`, `instanceId` UUID, `pid`, head-only).
  Worker did not connect in this isolated launch (`worker.spawn.failed`
  in the disposable data dir), correctly leaving `worker:null` — the
  phased-readiness design intent. Force-killed Head; manifest survived
  on disk with the now-dead PID, confirming the consumer-side
  identity-over-liveness story holds end-to-end.
- Pre-existing failure logged to `docs/observations.md`:
  `AiInstallServiceLateBindTest` fails on `worktree-501-runtime-manifest`
  and on origin/main baseline both (unrelated to 501).


### Phases 23–32 — §13 substrate hardening (2026-05-21)

Implements the §13 long-term design. The §13.7 open questions were
all answered as binding decisions; rationale in each phase's commit
message and in the working-assumptions block of the plan file.

**§13.7 Q1 — per-axis materialization.** Single document with declared
sub-records stays canonical. Per-axis projections happen *at view
time* via URL routes (probes, instances reader); the filesystem
artifact stays one file. Rejected alternative: splitting the
filesystem into per-axis files (doubles storage; consumers pay the
fan-out cost).

**§13.7 Q2 — time-axis read contract.** Postmortem reader is the
canonical consumer. Format: append-only ndjson series per instance
(`runtime/instances/<id>/manifest.log.ndjson`), one compact JSON
object per line. Terminal snapshot (`manifest.json`) preserved for
one-shot readers. Reader endpoint `GET /api/runtime/instances[/{id}]`.
Retention: by-count default (50 instances), pruned on publisher
construction.

**§13.7 Q3 — trust-tier granularity.** Two views (full, public). No
peer view yet; that lands when a cross-instance discovery use case
appears.

**§13.7 Q4 — discovery default for non-MCP clients.** Project-private
`.well-known/justsearch/manifest.json` stays the default. External
MCP-spec alignment (Server Card) deferred — no home tempdoc; not in
501 scope. The §13.5 lineage correction (521 does not own MCP Server
Card) still stands.

**§13.7 Q5 — state-axis composition.** Publisher composes today;
stays. /api/status now consumes `publisher.current().lifecycle()`
when available, falling back to direct derivation when the publisher
is unwired (test path) or hasn't published yet (boot window). The
duplicate-state surface for the overall discriminator is gone.

**§13.7 Q6 — reactivity short-circuit.** The manifest does not
self-detect death. Live signal is at the HTTP layer
(`GET /api/runtime/live`); the manifest reports its capability-derived
state and PID, the consumer probes liveness externally. Matches k8s
livenessProbe convention.

#### Phase 23 — Audience axis: typed view selection ✅
- `RuntimeManifest.publicProjection()` declared on each sub-record
  (`HeadInfo`, `WorkerInfo`, `AiInfo`) + top-level. Static
  `RuntimeManifestController.redactSensitive` deleted. Three callers
  (REST controller, SSE controller, MCP tool surface) updated.
- Adding a new credential-class field forces an update at the
  record's declaration site — the type system enforces what was
  previously prose.
- Verification: app-api + ui unit tests green; live smoke confirms
  `head.sessionToken` is null in `/api/runtime/manifest` response.

#### Phase 24 — Time axis: ndjson series + reader endpoints ✅
- Per-instance write switched to append-to-ndjson; terminal snapshot
  preserved.
- `GET /api/runtime/instances` lists instance IDs newest-first with
  hasSnapshot/hasLog/logLines metadata.
- `GET /api/runtime/instances/{id}` returns terminal snapshot + full
  publish log + start.log. Public projection applied (sessionToken
  stripped from all postmortem reads).
- By-count retention (50) pruned on publisher construction.
- Verification: `RuntimeInstancesControllerTest` covers sort order,
  invalid-ID rejection, redaction. Live smoke: count=2 after one
  restart, newest instance has 3-line ndjson log.

#### Phase 25 — Per-instance start.log ✅
- Publisher writes timestamped lines to
  `instances/<id>/start.log` on construction, every `publish*` call,
  and `close()`. Append-only, best-effort.
- Reader returns the `startLog` lines alongside snapshot + log.
- Verification: live smoke shows 4-line start.log with
  publisher-constructed / publishHead / publishWorkerFailed /
  publishAi entries; lines are human-readable timestamped narrative.

#### Phase 26 — /api/status consumes publisher.current() ✅
- `StatusLifecycleHandler` gains late-bound publisher field +
  setter. `buildLifecycleSnapshotV1` calls
  `readManifestLifecycle()` first; falls back to direct derivation
  when null.
- Defensive: returns null on unrecognized discriminator strings so a
  future `LifecycleState` rename forces fallback rather than crashing.
- Verification: live smoke shows `/api/status` `lifecycle.state` ==
  `/api/runtime/manifest` `lifecycle` (both `ERROR` in the
  worker-failed dev-stack run).

#### Phase 27 — Readiness/liveness probes ✅
- `GET /api/runtime/ready` returns 200 iff lifecycle == READY;
  otherwise 503. JSON body carries `ready`, `lifecycle`, `instanceId`.
- `GET /api/runtime/live` returns 200 unconditionally (by definition
  true if the request reaches the handler). JSON body carries
  `alive`, `pid`, `instanceId`.
- Verification: `RuntimeProbeControllerTest` covers all four states.
  Live smoke: ready=503 + lifecycle=ERROR; live=200.

#### Phase 28 — Publisher commit-path consolidation ✅
- **Deviation from plan**: plan called for collapsing five publish*
  methods into one `update(diff)`. Critical-analysis pass before
  implementation rejected that shape (semantic surface loss; call
  sites get worse, not better). Revised: extract a private
  `commit(manifest, startLogMessage)` helper carrying the shared
  body. Each public method ends with `return commit(...)`.
- Same payoff for future axis additions (new public method is a
  one-line delegation), no surface semantic loss.
- Verification: ui tests green; grep confirms only one
  `writeManifest(manifest)` call remains (inside the helper).

#### Phase 29 — wireManifestListeners helper ✅
- New `RuntimeManifestListenerWiring.wire(...)` static helper in
  `io.justsearch.ui.runtime`. HeadlessApp's four manifest-related
  inline blocks (initial worker publish, Phase 13 inference listener,
  Phase 22 worker listener, initial AI publish) reduce to one call.
- HeadlessApp net -130 lines this run (ratchets the file's pin DOWN
  even after the §11 additions of Phases 12/13/22 grew it).
- Verification: ui tests green; behaviour preserved exactly per
  diff inspection.

#### Phase 30 — Reachability axis: typed transports record ✅
- New `Reachability` record at `modules/app-api/.../runtime/`:
  `List<Transport>` with `kind` / `url` / `audience`.
- `publicProjection()` filters audience=full transports (filesystem
  entry) for HTTP-class transports.
- Publisher composes the list at `publishHead` time: 8 transports
  (REST manifest, SSE, well-known, ready + live probes, instances,
  MCP, filesystem with audience=full).
- `HeadInfo.apiBaseUrl` preserved for one schema cycle for back-compat
  (Tauri shell / Vite proxy / dev-runner / MCP discovery read it).
- Verification: `ReachabilityTest` covers projection + invariants.
  Live smoke: 7 public transports surfaced; 0 filesystem entries
  leaked.

#### Phase 31 — Closure-rule hardening ✅
- `scripts/ci/check-runtime-manifest-closure.mjs` extended with two
  rule classes:
  - **stdout-emit**: catches `System.out.print*("JUSTSEARCH_<NAME>=...`
    in Java source; grandfathered allowlist (`API_PORT`,
    `SESSION_TOKEN`) for the Tauri sidecar drain.
  - **unauthorized-write**: catches `Files.writeString` /
    `Files.write` / `Files.newBufferedWriter` calls referencing
    "runtime" in the same statement, from outside the publisher
    package.
- Sibling-file rule kept unchanged; output format extended with a
  `kind` discriminator so all three rule classes report uniformly.
- Verification: manual fault injection confirmed both new rules fire;
  baseline (no violations) clean.

#### Phase 32 — Closure pass + live-stack smoke ✅
- Full `./gradlew.bat build -x test` green. Full `./gradlew.bat test`
  green.
- Class-size pins re-baselined for `HeadlessApp.java` (1219, ratcheted
  DOWN from ~1349 pre-session via Phase 29), `LocalApiServer.java`
  (2179), `StatusLifecycleHandler.java` (1097). Decomposition
  obligation falls to tempdoc 519 (head composition graph design).
- Live-stack smoke against dev stack covering all six checks:
  - GET /api/runtime/manifest — 200 + lifecycle / reachability /
    redaction
  - GET /api/runtime/ready — 503 (lifecycle != READY in dev run,
    expected)
  - GET /api/runtime/live — 200
  - GET /api/runtime/instances — count=2, newest hasLog=true,
    logLines=3
  - GET /api/status — lifecycle.state matches manifest's lifecycle
  - GET /.well-known/justsearch/manifest.json — instanceId matches
- Per-instance reader returns snapshot + 3 log entries + 4-line
  start.log with publisher-constructed → publishHead →
  publishWorkerFailed → publishAi narrative.


### Phases 33–40 — followup fixes from Phase 23–32 critical analysis (2026-05-21)

A post-Phase-32 critical-analysis pass identified 11 issues across
the Phase 23–32 substrate. This run lands the fixes; two issues
(F3, F5) are documented blockers rather than deferrals per the
prompt's discipline.

#### Phase 33 — F2 stale worker-bootstrap reference (correctness) ✅
RuntimeManifestListenerWiring captured WorkerConnectionResult at
boot. After health-monitor-driven worker restart, `worker.grpcPort`
projection read the stale port — the very stale-projection bug
§12.1 says the manifest is supposed to project away.

Fix
- Added `AppFacadeBootstrap.currentKnowledgeServer()` accessor that
  reads the volatile field already updated on each connect (line
  1263).
- HeadlessApp passes `bootstrap::currentKnowledgeServer` as the
  live-worker supplier. Listeners now read the CURRENT bootstrap
  on every event.

#### Phase 34 — F6 commit() reorder (correctness) ✅
Reordered shared commit path from `write → set → notify → startLog`
to `write → set → startLog → notify`. A failing listener no longer
suppresses the postmortem record of an event that DID happen on
disk. Two new tests verify both invariants (listener-throws and
write-throws paths).

#### Phase 35 — F1 reachability registry (correctness) ✅
The Phase 30 publisher hardcoded eight URL strings mirroring
LocalApiServer route registrations. New `RuntimeTransportRegistry`
class collects route metadata declaratively; publisher reads the
list from the registry at publishHead time. Adding a route in
LocalApiServer also adds it to the registry (one statement block
covers both). The hardcoded-drift surface is eliminated.

#### Phase 36 — F4 time-axis pagination (robustness) ✅
`GET /api/runtime/instances/{id}` previously called `readAllLines`
on the ndjson log — unbounded I/O per request. Now: streams via
`Files.lines`, honors `?fromLine=<n>&limit=<n>` query params
(default 200, max 2000), reports `logFromLine`, `logLimit`,
`logTotalLines`, and `logTruncated` so callers can paginate.

#### Phase 37 — F7 RuntimeApiRoutes extraction (discipline) ✅
Extracted the 4 runtime controllers + their route block out of
LocalApiServer into a dedicated `RuntimeApiRoutes` class. The
LocalApiServer pin ratcheted from 2179 down to 2141 (-38 lines).
One of the three Phase 32 pin-bumps restored. Decomposition
obligation for HeadlessApp / StatusLifecycleHandler still falls to
tempdoc 519.

#### Phase 38 — F10 + F11 closure precision + HEAD probes (precision) ✅
F10:
- `FILES_WRITE_TO_RUNTIME` tightened: requires the "runtime" literal
  inside a `.resolve(...)` call within the `Files.write*` statement.
  False positive class (`Files.writeString(logFile, "runtime config
  dumped at: " + now)`) no longer fires.
- `STDOUT_EMIT_PATTERN` augmented with `PrintStream`/`var` alias
  detection. `var out = System.out; out.println("JUSTSEARCH_X=...")`
  now caught.
- Grandfathered allowlist auto-derived from HeadlessApp source at
  script start — no manual sync needed.

F11:
- `RuntimeProbeController.handleReadyHead` + `handleLiveHead` —
  status code only, no body.
- Routes register HEAD alongside GET.

#### Phase 39 — F9 remaining test gaps (robustness) ✅
- New `StatusLifecycleHandlerManifestFallbackTest` covers all three
  fallback paths of `readManifestLifecycle()` (publisher null,
  manifest null, unrecognized discriminator) + the happy path.
- New `RuntimeManifestPublisherTest.startLogRecordsTimestampedEventNarrative`
  asserts the Phase 25 start.log format: ISO-8601 prefix + event
  narrative in `publisher-constructed → publishHead → ... →
  publisher-close` order.
- `readManifestLifecycle()` visibility changed from `private` to
  package-private to enable direct unit test (consistent with
  other package-private helpers in the class).

#### Phase 40 — F8 happy-path live smoke + closeout ✅
- Built fresh dist + synced to main checkout + cleaned dev data +
  started worker → READY.
- After `ai_activate`, observed all targets:
  * `lifecycle = READY`
  * `worker.state = ready, grpcPort = 51226`
  * `ai.phase = READY`
  * `GET /api/runtime/ready` → HTTP 200 with `ready: true`
  * `HEAD /api/runtime/ready` → HTTP 200 (matches GET)
  * `HEAD /api/runtime/live` → HTTP 200
  * `/api/status` `lifecycle.state` == `/api/runtime/manifest`'s
    `lifecycle` (both READY)
  * `/.well-known/justsearch/manifest.json` instanceId matches
  * `/api/runtime/instances/{id}?fromLine=0&limit=2` returned
    pagination fields with `logTotalLines=3, log entries=2,
    logTruncated=true`
- Phase 32's failure-path checks still hold (worker=failed scenario
  was the previous smoke; this run added the worker=ready scenario).

Build artefact note: a Phase 26 + Phase 39 documentation pass
expanded `StatusLifecycleHandler` by 5 lines past its pin (1097 →
1102). Compacted the Phase 39 javadoc to keep the file at the
pinned ceiling. The class-size ratchet held.

#### Documented blockers (not part of this run)

**F3 — `@SensitiveField` ArchUnit enforcement.** `WorkerInfo.publicProjection()`
and `AiInfo.publicProjection()` are identity functions — they exist
as "structural commitment" but the type system enforces nothing if
a future field with credentials gets added. The right fix is a
custom `@SensitiveField` annotation on record components +
ArchUnit-style test that verifies the corresponding
`publicProjection()` strips them. Blocker: no comparable annotation
pattern exists in the codebase today (greenfield); the design
deserves its own tempdoc pass rather than being tucked into the
501 followup. The convention-only commitment carries an explicit
known-gap status until F3's design lands.

**F5 — Per-component LifecycleSnapshotBuilder.** §13.7 Q5 was
half-fixed by Phase 26 (overall lifecycle discriminator consolidated
via `publisher.current().lifecycle()`). Per-component states
(head/worker/inference inside `StatusLifecycleHandler`) still
re-derive independently from capability sources. The full fix
requires a shared `LifecycleSnapshotBuilder` that both surfaces
consume — a cross-axis refactor touching `StatusLifecycleHandler`,
the manifest publisher, and the capability layer. Blocker: out of
501's substrate scope. The capability layer (502) would need to
expose a top-level projection API. This belongs to a 502 follow-up,
not a 501 phase. The duplicate-state surface for per-component
states is the documented known gap.


## 12. The natural shape of the manifest's extensions (2026-05-21)

§12 was originally a flat register of forward-looking ideas in six
clusters (polish / extensions / discovery / UX / trust / dev tooling).
A read of the adjacent tempdocs (500, 502, 507, 518, 519, 521) made the
register's framing visibly wrong. Almost none of the §12 ideas need
new state; nearly all are *projections* of state that already lives in
the codebase's canonical graphs. This section rewrites §12 around that
reframe.

The previous §12.1–§12.7 wording, and the §12.8 feasibility addendum
that sharpened them, are preserved in the git history of this tempdoc
for traceability. The new §12 supersedes both as the authoritative
design.

### 12.1 The manifest is a projection, not a fact source

The runtime manifest is a *view* over canonical state the codebase
already maintains. The producer owns a small set of identity and
freshness primitives; everything else in the document is a projection
of authoritative state held elsewhere.

**Producer-owned primitives** (manifest is the source of truth):

- `instanceId` — the producer's identity for this process.
- `pid`, `startedAt` — process-level facts the OS provides but the
  manifest is the canonical place to read them from.
- `dataDir` — the resolution outcome (see the cross-language
  platform-paths contract, §3.6).
- A pointer to the per-instance history directory (§3.7).
- The set of declared transports (§12.4).

**Projected fields** (manifest is a view; authority lives elsewhere):

- *Capability state* — the `head.readyAt` / `worker.readyAt` /
  `ai.readyAt` shape that §3.4 calls "phased readiness" is the
  natural projection of `LifecycleProjection.derive(WorkerCapability,
  InferenceCapability)` (see §12.9 — the shipped signature is
  two-arg, not three; the API-serving bit is read from `WorkerCapability`
  itself). The manifest does not maintain a parallel state machine;
  it materializes the lifecycle projection at write-time.
- *Worker observed state* — `worker.grpcPort`, `worker.indexBasePath`,
  `worker.spawnError` are projections of the `WorkerCapability` /
  `KnowledgeServerStartResult` pair the head already produces. A
  `worker.state ∈ "pending" | "ready" | "failed"` discriminator is
  the right shape because it matches the capability's tri-state
  surface, not a fourth axis.
- *Inference observed state* — fields projecting `phase`,
  `lastKnownModelId`, `lastFailure`, `identity` are projections of the
  518 `InferenceRuntimeView` atom (the actual field names on the
  record; the manifest may rename them under its own naming but the
  authority is `InferenceRuntimeView`). The manifest carries a
  snapshot of that view, not a re-derived copy.
- *Catalog snapshots* — operation IDs, resource IDs, intent IDs the
  producer currently exposes are projections of the
  `OperationCatalog` / `ResourceCatalog` / `IntentSourceCatalog`
  registries the 500/507 designs already manage. The manifest's
  job is to expose the names+versions, not to own a parallel
  catalog.

The rule for adding a new field to the manifest is therefore:

1. Name the authoritative source the field projects.
2. Widen the manifest's view shape to include it.
3. The compatibility class is backward (older readers tolerate the
   absent field; newer readers tolerate the present field).

If a proposed field has no authoritative source — if the manifest
itself would be inventing the state — that proposal is either (a) a
producer-owned primitive (added to the small list above) or (b)
wrong and belongs as state in one of the canonical graphs first.
The closure rule (§6) gains a producer-side companion: *one new field
must name its source*.

### 12.2 The publisher's place in the composition graph

Today the manifest publisher is invoked manually from `HeadlessApp`.
The long-term shape, per tempdoc 519's head-composition-graph, is a
typed orchestration-phase function — *using 519's design-only
vocabulary*, only `ConfigContext` and `InfraContext` are currently
shipped classes; `CapabilityGraph`, `ServiceGraph`, and
`SubstrateGraph` are 519's proposed phase output records that have
not yet materialized (see §12.9):

```
// Illustrative shape; ManifestPublisherPhase + the *Graph records
// are 519 design vocabulary, not present in the codebase today.
ManifestPublisherPhase.run(
    ServiceGraph, SubstrateGraph, CapabilityGraph, InfraContext
) → ManifestPublisherHandle
```

The publisher runs after Phase 4 (Substrate) is wired and capabilities
have stabilized. It does not own a state machine; it subscribes to
the canonical sources (capability listeners per 502's push-based
propagation model; lifecycle transition events per 518's
`InferenceLifecycleManager`), materializes a fresh projection on each
notification, and atomically emits via the registered transports.

The current `RuntimeManifestPublisher` is correct in behaviour. Its
*position* in the architecture is provisional until 519 lands; at
that point the publisher becomes one entry in the typed phase
sequence, not a free-floating component HeadlessApp constructs by
hand.

### 12.3 Schema evolution is view-shape widening

There is no schema migration story for the manifest because there is
no state to migrate — there is only state to project. Every "schema
v2" question reduces to: *which new field of the canonical graph
should the projection expose?*

Compatibility classes follow trivially:

- **Backward** — older readers see fewer fields; tolerated by
  `@JsonInclude(NON_NULL)` and the catch-all view-tolerant decoder
  pattern the FE already uses (Zod `.loose()` schemas).
- **Forward** — newer readers must treat newly-absent fields as
  *projection unavailable in this producer build*, not *state
  changed*. They fall back to the next-best authoritative source
  (typically `/api/status` for capability questions, the catalog
  endpoints for inventories).
- **Full compatibility** is the default invariant for any field added
  by widening — the producer can emit it or not, the consumer can
  read it or not, neither breaks the other.

A schema-evolution test asserting the current schema can be parsed
by a v1-aware reader (no required fields added, no type changes) is
therefore the right closure mechanism. It does not require Confluent-
style registry infrastructure because the manifest is one document
with one producer; the registry pattern's purpose (many producers,
many consumers, asynchronous deployment) does not apply.

### 12.4 Transports share one document; the list is open-ended

The manifest is one canonical document. Transports are interchangeable
surfaces that carry the same projection at different reach:

| Transport | Reach | Notes |
|---|---|---|
| `<dataDir>/runtime/manifest.json` | Local processes with FS access | Carries the unredacted document (including `head.sessionToken`); FS permissions are the trust gate. |
| `GET /api/runtime/manifest` | Loopback HTTP callers | Redacted (no `sessionToken`); same shape otherwise. |
| `GET /api/runtime/manifest/stream` (SSE) | Loopback HTTP callers wanting change notifications | Same redaction; envelope frames carry the same projection. |
| `GET /.well-known/justsearch/manifest.json` | RFC-8615 standard surface for external HTTP-only consumers | Same redaction; convention-driven discoverability. |
| MCP `runtime_manifest` tool result | MCP clients via the curated `McpToolSurface` (500) | Same redaction; one curated tool, not a parallel surface. |
| (Future) FE WebSocket / Tauri IPC native channel | In-process FE / native shell | Same projection; transport choice is a performance/latency decision. |

The closure rule (§6) applies: a new transport must serve the same
projection. It does not introduce a new content shape, a new field
name, or a new authority. If a consumer's needs cannot be expressed
as the existing projection, the answer is to widen the view (§12.1
and §12.3 rules), not to add a separate transport with a different
shape.

### 12.5 Consumer UX is downstream, not a manifest extension

Surfaces like a tray-icon status dot, a restart-detection toast, a
phased loading screen, a multi-instance switcher, a postmortem CLI,
browser-tab disambiguation, a crash-vs-clean-exit banner, or a
diagnostic copy button — these are *consumer reactions to the view
stream*. They do not extend the publisher; they subscribe to a
transport and render.

The design rule: a new UX surface is a manifest *consumer*, not a
manifest extension. Adding one does not require any change to §12.1
or §12.4. The substrate exists to make these surfaces trivial; they
should not appear in the manifest design discussion at all once the
producer + transports are in place.

This is the same shape as the 500 design's distinction between
*Resources* (machine-readable substrate) and *consumer surfaces*
(UI built on top). The manifest is Resource-class; tray dots and
restart toasts are surfaces.

### 12.6 Trust is an optional crypto envelope, not a content concern

If signed manifests become a requirement, the envelope is independent
of the projection: a `manifestSignature` field (cosign blob signature
over the canonical body) wraps the projection. Consumers verify; on
verification failure they treat the document as unsigned and fall
through to whatever trust they had before — typically *none*, because
the loopback boundary is the existing trust gate.

**Implementation blocker as of 2026-05-21 (Phase 19 verdict):** the
substrate to support this envelope is *not present* and adding it
costs more than the current return justifies:

- **No `sigstore-java` dependency.** Slice 477 H2.3 ships a stub
  `PluginVerificationController` that returns `verified: false` for
  every call specifically to avoid the ~30 MB transitive weight
  (bouncycastle, fulcio client, sigstore-protobuf-specs, Jackson,
  gcrap). That weight is justified once *real* signed plugins exist.
  Until then, signing the runtime manifest hits the same
  infrastructure-without-customers failure mode the plugin
  verification stub explicitly avoids.
- **Cosign's keyless path requires online Rekor.** The transparency-
  log requirement breaks the local-first invariant. A build-time-key
  signing flow (no Rekor) would work in principle, but the build
  pipeline has no place to hold a private key today.
- **No verifier exists.** Every current consumer is loopback-only
  and reads the manifest through the producer's HTTP/filesystem
  surfaces, all of which already trust the producer by virtue of
  being on the same machine. Adding a signature with no consumer to
  verify it adds attack surface for credential-handling without
  reducing risk.

The blocker is therefore *structural, not aesthetic*. Re-enabling
this section requires three things in sequence:

1. `sigstore-java` (or an equivalent dep) lands as a justified
   transitive cost — typically because the plugin trust story it was
   reserved for is actually used.
2. A build-time-key signing flow with no online Rekor dependency is
   acceptable. (Otherwise local-first dies.)
3. At least one consumer category exists that benefits from
   verification — e.g., a remote browser-extension consumer
   reachable via the `.well-known` mirror that needs to know "the
   manifest I'm reading hasn't been tampered with on disk."

Until all three hold, this section stays at "designed shape,
deliberately not built." The shape itself: an optional envelope.
The publisher does not change. The projection does not change. The
transports carry one more side-channel field. The design is ready;
the substrate is the gate.

Trust is fully orthogonal to the substrate. It does not anchor in
the manifest's design; it composes over the manifest as a separate
PKI concern. This is the same separation 521 enforces between
contribution registries (tier-agnostic) and the module-resolver
attenuation layer (trust-aware substitution at factory time).

### 12.7 Environment constraints bound consumer designs, not the producer

Chromium 142+'s Local Network Access permission, the prod-mode session-
token discipline, FS-permission gating, sandboxed-process loopback
restrictions — these are environmental boundaries on *which consumers
can reach which transport*. They do not change the producer's design.
The well-known mirror and the HTTP redaction line are accommodations to
those boundaries, not concessions.

When a consumer category becomes infeasible under an environment
constraint, the response is to disable or narrow that consumer, not
to weaken the producer. The producer publishes one projection over
N transports; consumers pick a reachable transport.

### 12.8 Historical evidence — preserved from the prior §12.8 (2026-05-21)

The original §12.8 feasibility addendum recorded read-only verification
verdicts against the prior §12's load-bearing claims. Findings:

- §12.5 Sigstore — `sigstore-java` is not a current dependency; slice
  477 H2.3 ships an always-`verified:false` stub. The new §12.6 above
  carries the resulting position: trust is orthogonal to the
  substrate; signing is gated on real-consumer pre-conditions.
- §12.1 ArchUnit closure-rule promotion — not directly expressible
  (ArchUnit does not inspect constant-string arguments). The grep
  script is the correct primitive; the ArchUnit framing is dropped.
- §12.4 Tray-icon — Tauri v2 `TrayIcon` supports `set_icon` /
  `set_tooltip` post-build; the current `lib.rs` discards the handle.
  This is a consumer-side wiring observation (§12.5 above), not a
  producer-side question.
- §12.2 features flags — source-of-truth is `CapabilityGraph` /
  `InferenceRuntimeView`. The §12.1 reframe above embeds this as
  the projection rule.
- §12.3 published JSON Schema — `updateSchemas` filter requires a
  new `RuntimeManifestSchemaTest` entry. This is a small lift in
  the transport layer (§12.4), not a design question.
- §12.4 FE SSE — `EnvelopeStream.ts` is reusable; standard consumer
  substrate (§12.5).
- §12.6 VS Code extension — no precedent; a from-scratch consumer
  (§12.5).
- §12.7 Chromium 142 LNA — confirmed environment constraint
  (§12.7 above).

The previous §12.1–§12.7 cluster format ("Polish / Extend / Discovery /
UX / Trust / Dev tooling") is retired because it organized the
material around *the kind of work*, which obscured *the kind of
authority*. The new §12.1–§12.7 organize around authority: what is
projected, who owns the publisher, how the view widens, what carries
it, who reacts to it, when is it signed, what constrains reach.

The rule the new §12 enforces is simpler than the prior register's
six clusters: **the manifest is a projection of canonical state.
Producer-owned primitives are minimal and named; everything else is
a view widened by adding sources, served by transports, consumed by
downstream surfaces, optionally signed.**

### 12.9 Citation audit (2026-05-21)

A read-only verification pass against every class/method/concept
name the new §12 cites. The verdicts here drive the wording
corrections already applied to §12.1 and §12.2; this subsection
records what was checked so future readers can trust §12's
references.

**Shipped classes/methods (CONFIRMED):**

| Citation | Verdict | Evidence |
|---|---|---|
| `LifecycleProjection.derive(WorkerCapability, InferenceCapability) → LifecycleState` | confirmed | `modules/app-services/src/main/java/io/justsearch/app/services/lifecycle/LifecycleProjection.java:30` — note the **two-arg** signature; §12.1 originally wrote a three-arg form (`…, apiServing`) and has been revised. The API-serving bit is read from `WorkerCapability` itself, not a separate argument. |
| `WorkerCapability`, `InferenceCapability` | confirmed | Same package; shipped per tempdoc 502. |
| `ConfigContext`, `InfraContext` | confirmed | Same package; shipped phase records per 519 §B.O. |
| `InferenceRuntimeView` | confirmed (with field-name correction) | `modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceRuntimeView.java:29` — actual record fields are `phase` / `identity` / `lastFailure` / `lastKnownModelId` / `usingExternalLlamaServer` etc., not `mode` / `model` / `failure` as §12.1 originally implied. §12.1 wording corrected. |
| `InferenceLifecycleManager` | confirmed | `modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceLifecycleManager.java`. |
| `OperationCatalog`, `ResourceCatalog`, `IntentSourceCatalog` | confirmed | All three exist as interfaces in `modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/`. Implementations live in `modules/app-services` (e.g., `CoreOperationCatalog`, `CoreIntentSourceCatalog`). |
| `McpToolSurface`, `McpProtocolHandler` | confirmed | `modules/ui/src/main/java/io/justsearch/ui/api/mcp/`. |
| `KnowledgeServerStartResult` | confirmed | Inner record on `HeadlessApp.java`, captures `bootstrap` + `startError`. |
| FE Zod `.loose()` tolerant decoders | confirmed | `modules/ui-web/src/api/schemas.ts:29` etc. — explicit comment "intentionally permissive (.loose())". |
| `@JsonInclude(NON_NULL)` on the manifest record | confirmed | `modules/app-api/src/main/java/io/justsearch/app/api/runtime/RuntimeManifest.java`. |

**519-design-only names (DESIGN-ONLY, hedged in §12.2):**

| Citation | Verdict | Evidence |
|---|---|---|
| `CapabilityGraph` | design-only | Cited by 519 (e.g., line 101 declares `CapabilityPhase → CapabilityGraph`); not present in `modules/**`. §12.1 and §12.2 should refer to "the capability surface (WorkerCapability + InferenceCapability)" alongside the §12.2 explicit hedge about 519 vocabulary. |
| `ServiceGraph`, `SubstrateGraph` | design-only | 519 lines 102, 103, 180-183 declare these as Phase 3 / Phase 4 output records. Grep for `class ServiceGraph` / `record ServiceGraph` repo-wide returns nothing. §12.2 now flags this explicitly. |
| `ManifestPublisherPhase.run(…)` signature | illustrative invention | Not declared in 519 nor in the codebase. §12.2 uses it to communicate the long-term shape; the wording is now explicit it is illustrative. |

**Net impact on §12 body:**

- §12.1 — `LifecycleProjection.derive` signature corrected (three-arg → two-arg). `InferenceRuntimeView` field-name list corrected.
- §12.2 — explicit hedge added that `CapabilityGraph` / `ServiceGraph` / `SubstrateGraph` / `ManifestPublisherPhase` are 519 design vocabulary, not present classes. Only `ConfigContext` and `InfraContext` are shipped.
- All other §12.1–§12.7 citations stand as written.

The corrections do not change the §12 design intent; they tighten
the citations so a future reader who follows §12 to a class either
finds it or sees an explicit "design-only" hedge.

## 13. Long-term design: the runtime self-description substrate

Frame this section like 519: a long-term design theorization, not a
slice. Feasibility, short-term fixes, intermediate states, and any
"who-builds-this-when" are explicitly out of scope. Phase numbers, code
references, and module names are avoided beyond what §12 already named
as canonical sources. The previous §13 (a flat backlog of concrete
follow-up features dated 2026-05-21) is superseded; its contents
remain in the git history of this tempdoc for traceability.

### 13.1 Thesis

The runtime manifest is **one materialization of a broader Runtime
Self-Description Substrate**. The substrate has several orthogonal
axes that the current single-document shape conflates. A correct
long-term structure treats each axis as a first-class concern with
its own canonical authority, projected through a shared
producer-published artifact rather than recomposed by each consumer.

§12 establishes the principle: *the manifest is a projection of
canonical state, not a state axis of its own.* §13 generalizes that
principle along the axes the §12 view does not name separately.

This tempdoc remains `status: implemented` because both frontmatter
gates (one producer-published document; one cross-language data-dir
contract) remain green. §13 describes the substrate the §11
implementation is one materialization of — not new gates.

### 13.2 Diagnosis: which axes the current shape conflates

A 2026-05-21 verification pass against the codebase (see §13.8) showed
the actual conflation is more localized than "single document conflates
everything." The top-level `RuntimeManifest` already splits into
`head` / `worker` / `ai` sub-records, so the state axis is structurally
separated by subsystem. The real conflation site is **inside the
`HeadInfo` sub-record**, which bundles four axes — `apiPort` (identity
slot, used as cache key by consumers), `apiBaseUrl` (reachability),
`sessionToken` (audience/trust credential), `readyAt` (state) — in
one record. The audience axis crosses every sub-record but is encoded
as a single static `redactSensitive` transformation rather than a
typed view selection. The diagnosis is:

1. **Identity** — *who* is publishing? Top-level fields (`instanceId`,
   `pid`, `startedAt`, `dataDir`, `schemaVersion`) are clean; no
   conflation at the top. The conflation appears one level down:
   `HeadInfo.apiPort` doubles as both an identity slot (the value
   consumers cache by) and a reachability slot, which is structurally
   fine when they coincide and surprising when they diverge (e.g., a
   reverse-proxied deployment where the cache-key port differs from
   the contact port).
2. **Reachability** — *where* can the producer be reached?
   `HeadInfo.apiBaseUrl` carries one transport; the well-known mirror
   and MCP transport surface live outside the document; no
   self-contained reachability record names all transports together
   with their audience tags.
3. **State** — *what condition* is the producer in right now?
   Already separated by subsystem (top-level `lifecycle` + per-
   subsystem state inside `head`/`worker`/`ai`). The conflation here
   is *with credentials*: `HeadInfo.readyAt` (state) sits beside
   `HeadInfo.sessionToken` (credential), so a consumer that only
   wants the state still receives the full sub-record over HTTP and
   has to trust the redaction layer to have stripped the credential.
4. **Time** — *what was history*? Verified per-instance directory
   `<dataDir>/runtime/instances/<instanceId>/manifest.json` is
   **written on every publish but never read** (zero production
   readers). The shape is also *not* an append-only series — the
   per-instance file is overwritten on subsequent publishes within
   the same instance, so what survives is the **last-state snapshot
   per instance**, not a history of transitions. No start-log
   companion exists. The "history" name is aspirational; the shape
   is a per-instance terminal-state cache without a defined reader.
5. **Audience / Trust** — *which view* is the consumer entitled to?
   Currently: a single `RuntimeManifestController.redactSensitive`
   call decides the public-vs-full split inline. The split is real,
   but it is encoded as a hard-coded transformation rather than as
   a typed view selection.
6. **Discovery** — *how does a consumer find a producer they have
   not yet been told about*? Currently: well-known mirror file
   exists; MCP tool exists; dev-runner's `tmp/dev-runner/active.json`
   exists for a separate purpose. No common discovery model spans
   them.

The previous §13's features (phased loading screen, restart toast,
multi-instance switcher, postmortem CLI, MCP Server Card, mDNS,
readiness/liveness probes) are each a symptomatic gesture at one or
more of these axes. They are not random ideas; they are evidence
that the substrate the consumers want is not the substrate the
single-document shape exposes.

### 13.3 Principle: one canonical authority per axis, shared materialization

§12.1 is correct as far as it goes: the manifest is a projection of
the capability graph. §13 extends that principle:

- **Each axis has exactly one canonical authority.** Identity
  belongs to the producer process; state belongs to the capability
  graph (§12.1); time belongs to the per-instance history directory;
  audience belongs to a typed view-selection layer; reachability
  belongs to the transport surface declaration; discovery belongs
  to whatever external standard most consumers already speak.
- **No axis owns its own state machine.** Each axis projects from
  its authority at materialization time and is never the source of
  truth.
- **The shared materialization** (today: one JSON document) is the
  *intersection* of these projections, not their union. A consumer
  asking "is the producer alive?" should be able to read a *cheap*
  projection of the state and identity axes without paying the full
  catalog/transport/history payload.

The structural payoff is consumer-side: each consumer reads exactly
the projection it depends on, and a producer-side change in one axis
does not ripple to consumers of unrelated axes.

### 13.4 The six axes

The headings here name what the axis *is*; they intentionally avoid
naming specific endpoint paths, file names, or methods. The §11
implementation already populates each axis with a concrete shape; the
purpose of §13 is to name the axes so the next-round design (or the
next 501-followup tempdoc) has vocabulary to work with.

#### 13.4.1 Identity

*Who is publishing.* Authority: the producer process itself.
Producer-owned primitives only: `instanceId`, `pid`, `startedAt`,
`version`, `schemaVersion`, the data-dir fingerprint. This is the
only axis where the manifest is the source of truth rather than a
projection (§12.1).

Long-term shape: identity is a stable, *write-once-per-instance*
record. Once an instance has published its identity, that identity
does not mutate; subsequent updates are state/reachability/etc.
projections layered on top. Consumers caching by `instanceId` get
correct invalidation semantics for free.

#### 13.4.2 Reachability

*Where the producer can be reached.* Authority: the transport
surface declaration (today: `LocalApiServer` + the transports list
the §12.4 design names).

Long-term shape: a declarative list of transports, each with a kind
(`http`, `sse`, `file`, `mcp`, future: well-known mirrors,
optional advertised handles), URL or path, and audience tag (see
§13.4.5). A consumer's choice of transport is a function of its
trust tier and its consumption pattern (one-shot vs streaming), not
something it has to re-derive from the document contents.

This axis is where the well-known mirror, the MCP Server Card shape,
and any future advertised handles belong — *not* embedded as ad-hoc
fields inside the state projection.

#### 13.4.3 State

*Current condition.* Authority: the capability graph
(`WorkerCapability`, `InferenceCapability`,
`LifecycleProjection.derive`) and the typed transition substrate
(518's `InferenceRuntimeView`).

Long-term shape: a projection composed of three layered concerns —
overall lifecycle (one discriminator), per-subsystem state
(worker / ai), and capability vector (what the producer claims it
can do right now). These layers are *derived*, not stored; the
manifest carries the derivation result at materialization time.

The reactivity property §13.7 of the previous register flagged is
correct: the projection refreshes only when its authority transitions.
The long-term cure is not "make the manifest poll" — it is "ensure
the authorities themselves transition on every event the consumers
care about." If a Worker JVM dies and the capability does not
transition, that is a defect of the capability authority, not of the
manifest.

#### 13.4.4 Time

*History.* Authority: the per-instance history directory the §3.7
design names; the producer's start-log per instance; the producer's
exit reason at clean shutdown.

Long-term shape: an *append-only series* of (instanceId, identity
snapshot, terminal-state snapshot, start-log pointer) records.
Retention is a typed policy, not a future-tense plan — by-time, by-
count, or by-reason (always keep crashes). A postmortem reader is
not a CLI feature; it is the canonical consumer of this axis.

The crucial property: the time axis is *read* by consumers, not just
written by the producer. The current shape (verified §13.8) writes
`runtime/instances/<instanceId>/manifest.json` on every publish but
**overwrites it within an instance** — so the on-disk shape is a
per-instance terminal-state cache, not a series — and **has zero
production readers**. A correct design has at least one in-tree
consumer of the time axis from day one, and a series shape (one file
per publish, or a single append-only log) rather than the current
overwrite-in-place. The previous §13's "postmortem CLI" item was
correctly gesturing at the absent reader; the gap is structurally
larger than that item framed it.

#### 13.4.5 Audience / Trust

*Which view is the consumer entitled to.* Authority: a typed view-
selection layer that owns redaction policy.

Long-term shape: at least two views — *full* (filesystem-only, may
carry session token, internal paths, host details) and *public*
(HTTP / SSE / MCP / well-known; sanitized). Possibly a third *peer*
view if cross-instance discovery becomes a real use case.

The view-selection layer is not a redaction function; it is a typed
projection where each axis declares its public projection separately
from its full projection. Adding a new sensitive field cannot
accidentally leak through the public view because the view layer
requires every axis to declare both projections explicitly.

#### 13.4.6 Discovery

*How a consumer finds a producer they have not been told about.*
Authority: external standards where they exist (MCP Server Card,
OS-level well-known directories, mDNS, the operator's existing
discovery tooling) — not a project-private mechanism.

Long-term shape: project-private discovery (the `justsearch/`
well-known shape) is a *fallback* for project-aware consumers;
standard discovery (the MCP Server Card shape) is what any
ecosystem-aware client should be able to use without learning
JustSearch-specific paths. The discovery layer publishes the
reachability axis (§13.4.2) under standardized names so a generic
client can negotiate from there.

Local-network broadcast (mDNS / Bonjour) is opt-in by construction;
the local-first invariant is the design constraint, not a runtime
flag.

### 13.5 Relationship to adjacent substrates

The runtime self-description substrate is one node in a small graph
of head-process substrates already designed. The §13.8 verification
pass tightened these adjacency claims — what the first cut named as
duals or co-owners is, in several cases, looser than that.

- **502 (capability graph)** is the **direct authority** for the
  state-axis projection. The publisher's `publishAi`, `publishWorker*`,
  and `publishLifecycle` methods all read 502 surfaces
  (`InferenceCapability`, `WorkerCapability`,
  `LifecycleProjection.derive`). If 502 grows a new capability, the
  manifest's state projection grows with it.
- **507 (framework boundary)** establishes a **similar-pattern**
  trust line for *in-process* plugin surfaces (PluginHostApi /
  trust tiers). The manifest's audience/trust axis is the same
  *pattern* applied to *out-of-process* consumers — same idea
  ("crossing a trust line should be typed"), different boundary.
  Earlier framing called them "the same boundary"; that overstated
  the relationship.
- **511 (aggregate surfacing substrate)** is **not the internal
  dual** of the manifest, despite a superficial symmetry. 511 is
  about field-level FE rendering of aggregate metadata
  (audience tags, provenance, severity); it does not project the
  same state graph. The relationship to 501 is shared *pattern
  vocabulary* (typed projections cross a transport boundary), not
  shared subject matter.
- **518 (inference lifecycle envelope)** is **upstream of 502, not
  the direct authority for 501's AI projection**. 518 owns
  `InferenceRuntimeView`; 502's `InferenceCapability` consumes that
  view; the publisher reads the capability. The authority chain is
  518 → 502 → 501, with 501 reading only the 502 surface today.
- **519 (head composition graph)** shares the **phase-typed records
  vocabulary** with the substrate but is not the manifest's
  "internal dual." 519 is a composition-root design (how the head
  is wired); 501 is a self-description design (how the head is
  seen). They cohabit one process and share design idioms but they
  are different concerns rather than paired views.
- **521 (plugin ecosystem substrate)** owns **in-process plugin
  discovery** (`PluginDiscovery`), not external MCP-spec discovery.
  The MCP Server Card / well-known-mcp.json shape is *external*
  (industry standard) and currently has no home tempdoc; framing
  it as "521 territory" was a misclassification. If a project
  tempdoc takes ownership of external MCP-spec alignment, the
  runtime substrate's discovery axis becomes a tributary into
  that — but that tempdoc does not exist yet.

A future design pass on 501 that uses this lineage should treat 502
as the only "direct authority" name. The others are *related
designs* whose vocabulary is reusable but whose territory does not
overlap with 501's axes.

### 13.6 What this design is not

- *Not a multi-cluster or federated-discovery design.* The substrate
  describes one running instance, with optional time-axis traversal
  over past instances on the same machine. Cross-host or cross-org
  discovery is a sibling substrate, not an extension of 501.
- *Not a trust / signing design.* The audience/trust axis §13.4.5
  selects views; it does not establish authenticity of the producer.
  Sigstore-class signing remains §12.6's documented blocker and is
  out of scope here.
- *Not a transport-protocol design.* The reachability axis names
  what kinds of transports exist; it does not specify wire format,
  envelope schema, resume-token semantics, or any other
  protocol-level concern. Those belong to the individual transport
  designs (the SSE envelope, the MCP shape, etc.).
- *Not a UX design.* The previous §13's loading-screen, toast, and
  switcher items are consumer UX — downstream of this substrate, not
  inside it. A correct UX design references the axes by name but
  lives in its own tempdoc. (Tension acknowledged: §13.4.4 names the
  postmortem reader as the canonical time-axis consumer. That is a
  *role* statement — "this axis is incomplete without a reader" —
  not a UX prescription. The UX of the reader is downstream.)
- *Not a closure-policy design.* The §6 closure rule (one
  producer-published document, no sibling artifacts) is reaffirmed,
  not redefined. The audience/trust axis explains *why* the closure
  rule is correct (every sibling artifact would be a second
  uncoordinated view); it does not propose a new policy.

### 13.7 Open design questions

These are the structural questions the next-round design (or a
501-followup) must settle. They are written as questions to keep
this section from drifting into a feature plan.

1. **Per-axis materialization vs single-document.** Today's
   substrate materializes all six axes into one JSON document. Is
   the long-term shape one document with declared section
   boundaries, or one resource per axis (e.g., separate identity /
   state / time / reachability documents linked by `instanceId`)?
   Trade: section-boundaries keep one-read consumers cheap;
   per-axis resources let producers and consumers evolve each axis
   independently.
2. **Time-axis read contract.** What is the canonical reader of the
   history directory? If postmortem is the canonical consumer, the
   directory's shape (retention, ordering, schema versioning across
   instances) is downstream of postmortem's needs.
3. **Trust-tier granularity for the audience axis.** Two views
   (full, public) is enough for today's transports. A peer view for
   cross-instance discovery, or a richer audience model for
   plugin-tier consumers, may be required as 521 lands.
4. **Discovery-axis default for non-MCP clients.** MCP Server Card
   serves MCP-aware consumers. Is the project-private well-known
   shape the long-term default for non-MCP clients, or should the
   project-private shape converge onto an industry-standard pattern
   (e.g., `.well-known/runtime.json` if one emerges)?
5. **State-axis composition.** Is the state projection composed by
   the manifest publisher (today) or by the capability graph itself
   exposing a projection API (future)? If the latter, the publisher
   shrinks to identity + reachability + audience-routing only.
6. **Reactivity caveat resolution.** The state projection is
   reactive on capability transition (§13.4.3). When no transition
   fires for a real event (Worker JVM dying mid-call), the design
   says the bug is in the capability authority. Is there a
   short-circuit detection (PID-alive probe) that belongs in the
   state axis, or does the capability layer absorb that as a new
   transition cause?

These six questions are the surface area the next design pass
should address. They are deliberately not answered here — answering
them inside §13 would turn this section back into the feature plan
it replaces.

### 13.8 Verification pass (2026-05-21)

The §13 long-term design was first written from conceptual intuition.
A confidence-raising pass verified each load-bearing claim against
the codebase and the adjacent tempdocs before the design was treated
as stable. Results are documented inline above (corrections folded
into §13.2, §13.4.4, §13.5, §13.6 with this section as their
citation).

**Static evidence (codebase):**

| Claim | Verdict | Source |
|---|---|---|
| State axis reactive-only; no periodic re-publish | Confirmed | `RuntimeManifestPublisher.java` — 5 `publish*` methods + `close`; all transition-driven; no `Timer`/`ScheduledExecutorService` |
| Audience axis is one static transformation | Confirmed | `RuntimeManifestController.redactSensitive` is a static method, reused by `RuntimeManifestStreamController` via direct call |
| Time-axis directory has no production readers | Confirmed | `git grep` for `runtime/instances` / `instances/.../manifest` returns only the publisher (writer), the tempdoc, and the canonical explanation doc |
| Top-level document conflates all axes | Refuted — top level already has `head` / `worker` / `ai` sub-records; the real conflation site is **inside `HeadInfo`** (apiPort + apiBaseUrl + sessionToken + readyAt) | `RuntimeManifest.java` |
| Time axis is "append-only series" | Refuted — publisher overwrites `instances/<instanceId>/manifest.json` on every publish; on-disk shape is per-instance latest snapshot, not series | `RuntimeManifestPublisher.writeManifest` |

**Adjacent-tempdoc evidence:**

| Claim | Verdict | Source |
|---|---|---|
| 511 is the "internal dual" of 501 | Refuted — 511 is field-level FE aggregate-metadata rendering, not state projection | 511 §"Problem" / §"What already exists" |
| 518 is the direct authority for 501's AI projection | Refuted — `publishAi` reads `InferenceCapability` (502), not `InferenceRuntimeView` (518). Authority chain is 518 → 502 → 501 | `RuntimeManifestPublisher` import set; 518 §P2 |
| 519 is the "internal composition dual" of 501 | Overstated — 519 is composition-root design; shares phase-typed vocabulary but is a different concern, not a paired view | 519 §Section 1 |
| 521 owns MCP Server Card / external discovery | Refuted — 521's "discovery" is in-process `PluginDiscovery`; external MCP-spec discovery has no home tempdoc yet | 521 §1 / §"discovery" references |
| 507 establishes the same trust boundary as 501's audience axis | Overstated — 507 is in-process plugin trust tiers; 501 is out-of-process consumer view. Same *pattern*, different boundary | 507 §3.1 |

The corrections above were folded into the design proper. This
section is preserved as the evidence trail so a future reader can
trace the §13.5 lineage downgrades and the §13.4.4 time-axis shape
correction to their primary sources without re-running the pass.
