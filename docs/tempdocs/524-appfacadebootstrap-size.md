---
title: 524 — AppFacadeBootstrap decomposition (2,837 LOC → ≤ 1,000)
type: tempdoc
status: open
---

> NOTE: Noncanonical doc. May drift; verify against code.

# 524 — `AppFacadeBootstrap`: decompose the 2,837-LOC head composition root

**Date**: 2026-05-18 (created), 2026-05-19 (refreshed — sibling status updated)
**Source path**: `modules/app-services/src/main/java/io/justsearch/app/services/AppFacadeBootstrap.java`
**Pinned LOC**: 2,837 (per `gradle/class-size-exceptions.txt`)
**Related**:
- `docs/reference/contributing/class-size-standard.md` — 1,000 LOC ceiling + the 13-row precedent matrix
- tempdoc 516 (indexingloop-size) — sibling decomposition; the P1-P5 playbook applied to the worker-side central coordinator. **Closed.**
- tempdoc 517 (search-execution) — sibling decomposition for SearchOrchestrator. **Already closed** (the file is now a 154-LOC facade over capture/plan/execute/respond collaborators; tempdoc 516 W7.2 completed the Phase-2 EncoderBindings cut during the merge).
- tempdoc 516 Appendix D §D.2 — F1/F2 fixes are concrete evidence that the `standalone-capability-stays-stuck` bridge pattern is a real and recurring failure mode in this codebase. Any 524 decomposition that moves capability wiring across new seams must preserve the lazy-resolution discipline that F1/F2 had to retrofit.
- `.claude/rules/agent-lessons.md` §`standalone-capability-stays-stuck` — the bridge's named handle. `AppFacadeBootstrap.connectKnowledgeServer` is its exemplar.

---

## The issue

`AppFacadeBootstrap.java` is **2,837 LOC** — 2.8× the 1,000 LOC ceiling and now the **last remaining** central-coordinator mega-class in the worker-services / app-services seam. Its two siblings closed during the 516 session:

- `AppFacadeBootstrap` — **2,837 LOC** (this tempdoc; last one standing)
- `SearchOrchestrator` — closed: 1,919 → 154 LOC via 517's input/plan/execute/respond decomposition + 516 W7.2's EncoderBindings Phase-2 cut. No grandfather row left.
- `IndexingLoop` — closed: 1,955 → 931 LOC via 516's six collaborators. No grandfather row left.

This is the Head-process composition root: the place where every front-end service, the worker-side gRPC clients, the inference surface, the agent loop, the UI HTTP server, and the lifecycle/capability bridges all get wired. The class is large because everything has to attach somewhere — exactly the central-coordinator anti-pattern tempdoc 516's body framing diagnoses.

---

## Apply the tempdoc 516 playbook (with one important caveat)

The 516 P1–P5 design is symmetric and applies here, but two structural facts make this the **highest-risk** decomposition remaining:

1. The class hosts the `connectKnowledgeServer` late-bind capability bridge. Per `agent-lessons.md` §`standalone-capability-stays-stuck`, this bridge is the exemplar for a subtle bug class (a class lazily creating a Capability when a dependency isn't ready yet, with the dependency later holding its own Capability instance, requires `addListener` mirroring or `Supplier<X>`-style lazy resolution). 516 Appendix D's F1/F2 fixes are direct evidence: when 502's "eliminate late-binding" refactor pushed `IndexingService` and `DocumentService` values into 4 downstream controllers, it accidentally re-shipped this exact bug pattern. The fix was to convert those captures from `Service` to `Supplier<Service>`. Any 524 decomposition that moves capability wiring across new seams must preserve the lazy-resolution discipline.
2. Bootstrap fires before health-check signal; failures show as Head-side startup deadlocks, not graceful errors. The Tier-3 verification surface for AppFacadeBootstrap changes is necessarily a cold-start of the Head process against a fresh worker, which is more expensive than the IndexingLoop iterations tempdoc 516 used.

### The P1–P5 cut, adapted

- **P1 — Collaborator extraction.** Cluster the existing methods by what they wire:
  - `*ServiceFactory` cluster (the gRPC client construction) — extract `GrpcClientBindings` or similar.
  - `*LifecycleAdapter` cluster (Head-side adapters around worker capabilities) — extract `LifecycleAdapterBindings`.
  - Agent-loop wiring — extract `AgentSurfaceBindings`.
  - Inference surface wiring — extract `InferenceSurfaceBindings`.
  - UI HTTP route wiring — already mostly in `LocalApiServer`; verify no leakage.
  - The `connectKnowledgeServer` late-bind bridge — extract as a **named, dedicated** collaborator (`KnowledgeServerConnector`?) so the bridge pattern is recognizable at the call site and the postmortem citation has a stable target.

- **P2 — Named transitions / phases.** The bootstrap proceeds through implicit phases (read config → resolve env → instantiate sub-services → bind capabilities → start HTTP). Make each phase a named private method (`phaseResolveConfig()`, `phaseInstantiateServices()`, etc.) so the startup graph is `grep`-able.

- **P3 — No setters.** AppFacadeBootstrap currently uses a lot of post-ctor wiring. The 516 final cut (KS `newAppServices()` helper + DWAS 2-arg ctor) is the playbook for late-bound values that turn out to be construction-orderable.

- **P4 — Ratchet trip-wire.** Already live. Pin tightens with each decomposition wave.

- **P5 — Concrete classes, not strategy interfaces.**

---

## Why this is bounded but expensive

- **Bounded**: the 516 playbook is proven and the substrate exists.
- **Expensive**:
  - Largest file of the three (2,823 LOC) → most cuts to plan.
  - Highest cross-module impact (touches Head + worker-services seam + agent-loop seam).
  - The `standalone-capability-stays-stuck` bridge requires careful preservation.
  - Tier-3 verification needs cold-start of full Head process; harder to iterate than IndexingLoop's batch-level verification.

Estimate: 2–4 sessions equivalent to tempdoc 516's full arc (which took 1 long session for ~1000 LOC reduction).

---

## Done criteria

1. `AppFacadeBootstrap.java` ≤ 1,000 LOC.
2. The grandfather row for it removed from `gradle/class-size-exceptions.txt`.
3. The `connectKnowledgeServer` late-bind bridge preserved (verified by the existing `AppFacadeBootstrapTest` and any new bridge-specific tests).
4. Head cold-start succeeds against a fresh worker + verifies via `/api/health` lifecycle = READY.
5. Tier-3 verification: full agent loop + inference surface + RAG retrieval against a live stack.

---

## Workflow notes for future agent

- Re-read `agent-lessons.md` §`standalone-capability-stays-stuck` BEFORE starting the bridge extraction. The postmortem is more important here than for the other two siblings.
- The 516 plan template structure (waves + sub-slices, pre/post pass discipline) is reusable. Plan 4–6 waves to cover the size.
- Don't try to land this in one commit. Sub-slice aggressively; tempdoc 516 shipped 9 commits.
- Do not relax verification tiers. Tier-3 cold-start is required for any commit that touches the bridge or the bind ordering.
