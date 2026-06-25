---
title: "544 — §5.2 LAZY candidate migrations: LambdaMart / GPL / VDU"
type: tempdoc
status: open
created: 2026-05-21
category: composition substrate / lazy phases / cold-start
related:
  - docs/tempdocs/541-composition-substrate-completion.md §5.2 + §12.4
  - modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/Memoized.java (primitive)
  - modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/Eagerness.java
  - modules/app-services/src/main/java/io/justsearch/app/services/HeadAssembly.java (current eager call sites)
---

# 544 — §5.2 LAZY candidate migrations: LambdaMart / GPL / VDU

**Status**: open

## Why

Tempdoc 541 §5.2 documented `Eagerness.LAZY` candidates: LambdaMartTraining.loadOrTrain, GplJobCoordinator startup, VduOfflineCoordinator startup. The fix-pass shipped the primitive (`Memoized<T>`) and a real consumer (agent-tool registration). The other three candidates remain EAGER today; the substrate accepts them lazy. This tempdoc plans the migrations.

Each candidate is its own slice — the substrate's contract is the same; the cost is in identifying the right resolution trigger + verifying the cold-start savings.

## Slices

### S1 — `LambdaMartTraining` → `Memoized<Path>` resolved at first reranking request

- Today: `LambdaMartTraining.loadOrTrain` runs in `OrchestrationPhase`. Returns the model file path; if missing, fires async retraining. Config-gated: `lambdamart.enabled = false` is a fast-path no-op.
- Trigger: first reranker invocation needs the model. `LambdaMartReranker` already gates on `loadedAt != null`.
- Migration: `OrchestrationPhase.Output.lambdaMartModelFile` becomes `Memoized<Optional<Path>>`. Reranker calls `.get()` lazily.
- BootTrace entry: `"lambdamart-load" LAZY/PENDING` until first reranker call.
- Acceptance: cold-boot trace shows the LAZY entry; first search-with-reranker triggers resolution + transitions trace to READY/resolved (mirrors agent-tools-registration pattern).

### S2 — `GplJobCoordinator` → `Memoized<GplJobCoordinator>` resolved at first GPL snapshot trigger

- Today: `GplJobCoordinator` constructed in `OrchestrationPhase` regardless of whether GPL is configured (it has its own enabled-flag internal check). The auto-trigger thread starts even if no work is queued.
- Trigger: first GPL snapshot file landing OR first manual trigger.
- Migration: `OrchestrationPhase.Output.gplJobCoordinator` becomes `Memoized<GplJobCoordinator>`. The auto-trigger thread is only spawned at resolution time.
- Acceptance: with `gpl.enabled = false`, the coordinator never resolves; trace shows persistent LAZY/PENDING. With `gpl.enabled = true`, first trigger event flips the entry to READY.

### S3 — `VduOfflineCoordinator` → `Memoized<VduOfflineCoordinator>` resolved at first VDU sync event

- Today: VduOfflineCoordinator wires into the signal-bus at SubstratePhase. Listener is registered eagerly.
- Trigger: first VDU snapshot event lands on the bus.
- Migration: similar shape to S2. The listener registration is the eager hook; the coordinator body is the lazy work.
- Acceptance: zero VDU activity = coordinator stays LAZY/PENDING; first sync flips to READY.

## Order of execution

Suggested: S1 (smallest, cleanest trigger) → S2 (medium, needs auto-trigger thread refactor) → S3 (largest scope; signal-bus interaction). Each ships independently.

## Boundaries (not in scope)

- Tempdoc 539 cold-start profile measurement — that's the verification layer; this tempdoc is the migration. Numbers go in 539's tempdoc once the migrations ship and we can measure before/after.
- Worker-side lazy phases (separate concern under 546 WorkerAssembly's v2).
- Brain-side lazy phases (none today; ILM is wrapped, not phase-decomposed).
- AgentToolHandlers.registerEager — already shipped (541 fix-pass Tier 3 A.1).

## Verification per slice

- Unit: extend `MemoizedTest` to cover the specific resolution body.
- BootTrace assertion: `/api/boot/phases` shows the LAZY entry; after triggering the resolution path, re-fetched envelope shows READY/resolved with non-null timing (depends on §12.G Memoized resolution timing capture from 541 §12.3).
- Live-stack: trigger the resolution path through the real UI / agent invocation; verify the substrate reflects the transition.
