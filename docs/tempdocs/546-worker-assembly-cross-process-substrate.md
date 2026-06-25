---
title: "546 — WorkerAssembly: cross-process composition substrate for Worker"
type: tempdoc
status: open
created: 2026-05-21
category: composition substrate / cross-process / Worker
related:
  - docs/tempdocs/541-composition-substrate-completion.md §5.1 + §12.4
  - docs/reference/contracts/composition-substrate-slots.v1.md (slot enumeration)
  - modules/app-services/src/main/java/io/justsearch/app/services/BrainAssembly.java (precedent)
  - modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java (~1989 LOC; not phase-decomposable today)
---

# 546 — WorkerAssembly: cross-process composition substrate for Worker

**Status**: open

## Why

Tempdoc 541 closed the composition substrate for Head + Brain. Brain shipped via `BrainAssembly` as the wrap-not-decompose variant (per §9.1 C2: ILM is not phase-decomposable; the composition root holds it as a single Phase Output). Worker is the third process; today it has no composition-substrate surface at all — `/api/boot/phases?process=worker` returns `501 NOT_SUPPORTED` with a comment pointing to "a future WorkerAssembly tempdoc."

This is that tempdoc.

## Scope

The lighter target is the wrap-not-decompose variant (same shape as BrainAssembly). The heavier target is full phase-decomposition of Worker's startup sequence. The substrate accepts both; this tempdoc proposes the lighter target as the v1.

### v1 — `WorkerAssembly` wraps `KnowledgeServer`

- `WorkerAssembly` is a record at `modules/indexer-worker/src/main/java/.../WorkerAssembly.java` (or `modules/worker-services/`, decided in implementation) carrying `(KnowledgeServer knowledgeServer, BootTrace bootTrace)`.
- `WorkerAssembly.project(knowledgeServer, t0, t1)` constructs a single-phase BootTrace with `process="worker"` and one phase named `"knowledge-server-construction"` recording the wall-clock timing of `KnowledgeServer.start()` to `KnowledgeServer.ready()`.
- Outcome: Ready when KnowledgeServer transitioned to READY; Degraded with a reason code when it's stuck in DEGRADED/RECOVERING; Failed when construction threw.
- The trace is exposed via a Worker-side gRPC RPC (Worker doesn't bind its own HTTP). Head's `BootRoutes` proxies the gRPC response when `?process=worker` is requested, replacing the current 501.

### v2 — phase-decomposition (deferred to a future tempdoc)

If Worker ever benefits from phase-level introspection (e.g., separate phases for index-open / Lucene-recovery / signal-bus-init / gRPC-bind), the wrap-not-decompose variant becomes the parent and individual phases get extracted. KnowledgeServer is ~1989 LOC and decomposition is a multi-session refactor. Out of scope for v1.

## Acceptance criteria

1. `GET /api/boot/phases?process=worker` returns a 200 envelope mirroring the head/brain shape (single phase entry today; expandable later).
2. The envelope passes through Head's `BootRoutes` via a new gRPC call to Worker; falls back to a 503 with `worker.not_connected` reason code if Worker is unavailable.
3. `WorkerAssembly` ships unit tests mirroring `BrainAssemblyTest` shape.
4. `composition-substrate-slots.v1.md` gains a Worker section (or v2 of the doc).

## Boundaries (not in scope)

- Full phase-decomposition of KnowledgeServer.
- Worker-side analogue of `Memoized<T>` / lazy phase migrations — Worker's lazy candidates are a separate exercise.
- Worker-side `RebuildHistory` — Worker doesn't have a `connectKnowledgeServer`-style rebuild path; the v1 trace is once-per-Worker-boot.

## Investigation needed before implementation

- Worker's gRPC service surface — which existing RPC does the BootTrace ride on? Add a new RPC method or piggy-back on an existing health endpoint?
- Worker has its own bootstrap flow at `IndexerWorker.java` (per CLAUDE.md "Body" process). What's the current composition shape there?
- Cross-process timestamp coherence — Head's BootTrace uses Head's clock; Worker's uses Worker's. Should the envelope mark whose clock the timestamps belong to?

## Prior art from tempdoc 542 (operation-scoped lease taxonomy)

Tempdoc 542 §B Round-2 established the current Head↔Worker gRPC topology and is directly load-bearing for this design:

- **gRPC direction**: today's Head↔Worker gRPC is **unary Head→Worker only** (`modules/ipc-common/src/main/proto/indexing.proto`). Worker is a gRPC server; Head is the gRPC client. There is no general Worker→Head call channel.
- **The only Worker→Head stream**: `SubscribeIndexingJobs` (`GrpcIngestService.java:1690-1769`). Worker is the *server*, Head opens a long-lived subscription as *client*. When Worker dies, the server-side stream terminates and Head observes the client-side disconnect — making `SubscribeIndexingJobs` the natural **Worker-liveness anchor** without any additional mechanism.
- **For BootTrace propagation specifically**: ride a new unary RPC `GetBootTrace` on Worker's existing gRPC service. Head calls it; Worker returns its in-memory trace. The 542-discovered `SubscribeIndexingJobs` liveness signal can be used to detect Worker death between calls (returns `worker.not_connected` per acceptance criterion #2 without needing a heartbeat-poll).
- **What 542 deliberately did NOT do**: invert the gRPC direction. Adding a Head-as-gRPC-server would be a larger architectural change than warranted for op-leases; 542's solution was a Head-side file write (`tmp/dev-runner/op-leases.json`). For 546, the question is different: BootTrace is fundamentally Worker-side state; it must be queryable from Head via Worker→Head transport. Adding a unary `GetBootTrace` RPC mirrors the existing Head-as-client pattern.
