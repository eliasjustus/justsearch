---
title: "529 — Observability surface built on the tempdoc 518 substrate"
---

# 529 — Observability surface built on the tempdoc 518 substrate

**Date**: 2026-05-19
**Status**: done
**Parent**: [tempdoc 518](518-inference-lifecycle-design.md) — supplies the
structural primitives this tempdoc builds consumers on.
**Related**:
- `docs/explanation/08-observability.md` — the doc this tempdoc updated with
  the local-trace-viewer section + cross-process tracing notes.
- ADR-0017 / ADR-0014 — same constraints that bounded 518's body.

---

## Why this tempdoc exists

Tempdoc 518 shipped four structural primitives:

1. The `TransitionRunner` envelope with typed lifecycle events.
2. The immutable `InferenceRuntimeView` atom + three-tier update model.
3. The typed `InferenceFailure` taxonomy + per-category metric routing.
4. The role-typed `OnlineAi*` interfaces + ArchUnit-enforced module
   boundary.

518's body was declared **implemented** in Appendix C and confirmed in
the post-merge defect-cleanup slice. Everything below grew **on top** of
those primitives — the work isn't 518's contract, but it would be
illegible attributed to 518 because (a) the body is closed and (b) the
volume is large enough that the tempdoc-as-contract framing dilutes
518's own message ("how to dissolve the half-decomposed plateau") with
"how to build a trace viewer."

This tempdoc takes that follow-on work as its own contract. The work
itself is fully shipped; 529 is primarily an archival reframing so a
future reader who sees a `feat(518): Wave …` commit can find the
intent under the right header.

---

## Scope

The forward observability surface that 518's substrate enables. Four
tracks:

1. **Naming + polish** on the substrate (renames, idempotency contract
   docstring, shared listener notifier, consumer migration).
2. **Persistent state extensions** built on the runner (failure-history
   ring buffer, generation-tagged tracing, wireCode → UX hint i18n
   catalog).
3. **UX consumers** for the typed surfaces (restart-ETA badge,
   mode-transition timeline panel, gen-aware chat banner, trace
   explorer, generation sparkline).
4. **Activation work** that makes the substrate functional end-to-end
   (head-side OTel tracing init, HTTP span wiring, per-request trace ID).

Items intentionally **out of scope** (each documented inline with a
specific blocker):
- `awaitOnline()` Promise — no current waiter.
- Diagnostic capture-on-failure bundle — no consumer destination.
- Route-template span names — high-cardinality is theoretical until a
  consumer aggregates by name.
- `<module>.internal.*` package convention — massive import churn for
  marginal gain over the explicit ArchUnit FQN rule.

---

## Implementation log

The work landed across 17 commits in 4 phases, each with its own
critical-analysis pass.

### Phase 1 — Forward catalog implementation (2026-05-19)

| Slice | Item | Commit | Notes |
|---|---|---|---|
| W1.2 | Idempotency contract test + docstring | `e8f9870ed` | Pinned the IllegalStateException re-entry contract on `TransitionRunner.run`. W1.1 renames skipped on critical-analysis (cosmetic; no OTel impact). |
| W2.1 | Failure-history ring buffer + GET /api/inference/failures | `c5bf06e0d` | 20-entry `ArrayDeque` parallel to view atom. Three recording sites. New endpoint. |
| W2.2 | Generation-tagged OTel span attribute | `07ccfae29` | Static-slot `InferenceGenerationContext` + `InferenceGenerationSpanProcessor`. **Dormant until Phase 2's head-tracing init.** |
| W2.3 | wireCode → UX hint i18n catalog | `0f778a15c` | 18 entries in new `inference-failures.en.properties` namespace + contract test. Skipped the ApiErrorCode-bloat path. |
| W3.1 | Restart-ETA badge | `92e299452` | `lastStartupDurationMs` → "AI is initializing. Usually takes ~Ns." in the Brain panel. |
| W3.2 | Mode-transition timeline | `ab0afa6f7` | `TransitionRecord` ring + `GET /api/inference/transitions` + `<details>` panel. |
| W3.3 | Gen-aware chat restart banner | `70228fe66` | `currentGeneration()` exposed on `/api/inference/status`; FreeChatView latches a banner on mid-session bump. |
| W4.1 | ObservableNotifier substrate | `2113eaf50` | Generic listener-list in `core-contracts`; ConfigStore + TransitionRunner migrated. EngineCircuitBreaker dropped (no registry). |
| W4.2 | Consumer migration (2 of 3 mechanical) | `4b17eebb7` | OfflineCoordinator + BootstrapInferenceFactory → role interfaces. VduProcessor deferred to Phase 2 — three-interface span. |
| W4.3 | IndexingLoop salvage | `8bc6d0fa1` | Folded additional-listeners branch onto ObservableNotifier; envelope adoption refuted as literally written. |

**Items skipped or deferred with specific blockers** (audit verdicts in
Appendix A):
- D.1 renames (`TransitionRunner→TransitionOrchestrator`,
  `wireCode()→errorCode()`) — cosmetic; OTel `error.type` attribute key
  is consumer-side, not method-name.
- D.4 #5 / D.4 #6 / D.3c — each blocker named inline.

### Phase 2 — Structural-gap closure (2026-05-19)

After Phase 1, the catalog was "implemented or specifically blocked."
Critical analysis surfaced two remaining gaps:

- **D.3b dormant** (head process never initialized `TracingBootstrap` →
  zero spans → generation attribute attached to nothing).
- **P4 partial** — VduProcessor still held the concrete
  `InferenceLifecycleManager`.

Note: the VduProcessor closure is properly **518 body work** (closes
the P4 follow-up Appendix C named as deferred). It's tracked in 518's
Appendix D, not here. The remaining gap closures landed as:

| Slice | Item | Commit | Notes |
|---|---|---|---|
| S2 | Head-side OTel tracing init | `58c60e607` | New `HEAD_TRACING_LEVEL` env key + `TracingBootstrap.forHead`. Activates the existing head spans (`AgentLoopService`, `KnowledgeHttpApiAdapter`); W2.2's generation attribute now lights up on actual exported spans. |
| S3 | `inference.transition` span | `c5db8507d` | Wraps `TransitionRunner.run` body. Carries `inference.from_phase` / `inference.to_phase` / `inference.reason` / `inference.success` / `inference.wire_code` / `justsearch.inference.generation`. |

Cross-process tracing was found **already wired** (`TraceClientInterceptor`
in ipc-common + `TracingServerInterceptor` in worker-core, W3C
TraceContext propagator). With S2 active, head ↔ worker traces stitch
automatically.

### Phase 3 — Forward build (Wave A-E, 2026-05-19)

A new round of research-driven additions, grouped into five waves of
varying maturity:

| Wave | Item | Commits | Notes |
|---|---|---|---|
| A.1 | otel-desktop-viewer setup docs | `ec6b94167` (bundled) | Section added to `docs/explanation/08-observability.md` covering the OTLP fan-out → local viewer flow. |
| A.2 | HTTP spans per request | `ec6b94167` | Composed into LocalApiServer's existing `before`/`after` hooks. **Initial implementation was incomplete; see Phase 4 Slice 1.** |
| A.3 | `X-Trace-Id` response header | `ec6b94167` | Stamped from active span context. **Initial implementation didn't guard for invalid context; see Phase 4 Slice 1 Fix-5.** |
| B.1 | inference-transitions NDJSON sidecar | `17545ad2a` | Persistent log via `InferenceTransitionLog` interface + `NdjsonInferenceTransitionLog`. **Initial implementation ran I/O under the runner's lock; see Phase 4 Slice 2.** |
| B.2 | Replay harness | `17545ad2a` | 5 vitest cases including a fixture-driven `ModeStateMachine` replay test. |
| C.1 | `ModeChangeListener` contract Javadoc | `1701271f0` | Reversed the original "deprecate" plan — documented the synchronous-side-effect role distinct from span (S3) + sidecar (B.1). |
| D.1 | Trace explorer Brain-panel sub-component | `ee48c89d2` | `GET /api/diagnostics/traces` + clickable row list. **Initial endpoint read full file; see Phase 4 Slice 3 Fix-7.** |
| D.2 | Generation sparkline | `ee48c89d2` | **Initial implementation rendered only dots, not the actual y-line; see Phase 4 Slice 3 Fix-4.** |
| E.1 | `/module-arch` skill template | `f95ce4ff7` | Boundary-test pattern from 518 S1 captured as a copy-paste template. |

### Phase 4 — Post-ship defect fixes (2026-05-19)

Critical-analysis pass on Phase 3 identified correctness defects that
live verification missed because it only checked "did a thing emit at
all," not "is the thing's shape useful."

| Slice | Defect | Commit | Resolution |
|---|---|---|---|
| 1 | HTTP spans not parented (flat trace tree); X-Trace-Id all-zeros; slow-request dump didn't include trace ID | `39bc24474` | `span.makeCurrent()` in `before` + close in `after` (Fix-2). Guard `isValid()` on header (Fix-5). `SlowRequestDumper.captureDump` takes a traceId parameter (Fix-9). Live-verified: search request now produces a 3-level `http.post./api/knowledge/search` → `search` → `search/cross_encoder` tree. |
| 2 | Sidecar I/O under the runner's transition lock; hand-rolled JSON only escapes `\` and `"` | `3a5355216` | New `AsyncInferenceTransitionLog` decorator wraps the NDJSON impl + drains writes on a single-threaded daemon executor. Jackson replaces hand-rolled JSON. Shutdown drains pending writes. |
| 3 | Sparkline lacked the y-line; trace endpoint read full file every poll; no Wave D tests | `38da77b53` | `<polyline>` step-function 0 → currentGen with per-row dots at gen-at-that-row. `RandomAccessFile` tail-by-bytes (256 KB window) for trace endpoint. 4 new vitest cases on the sparkline contract. |

**Deferred from Phase 4 with documented blockers**:
- Fix-1 (route-template span names) — no aggregating consumer.
- Fix-8 (sidecar dataDir vs home divergence) — production-equivalent;
  dev-only edge case.
- B7 / D6 / D7 / E2 — nits.

---

## Appendix A — Forward catalog confidence audit (2026-05-19)

Three read-only investigations were dispatched to grade each Phase 1
catalog item against the live code. Verdict vocabulary: `confirmed` /
`confirmed-with-caveats` / `modified` / `refuted as literally written,
partially viable` / `deferred — no consumer demand`.

The full audit was captured as the original tempdoc 518 Appendix E.
Headline verdicts:

- **D.1 renames** — `confirmed`. Mechanical.
- **D.2a IndexingLoop envelope reuse** — `refuted as literally written`.
  Loop is unbounded polling, not a discrete FSM. Salvage: D.2b
  substrate.
- **D.2b shared listener substrate** — `confirmed-with-caveats`. 2.5 of
  3 actual participants (EngineCircuitBreaker has no registry).
- **D.2c idempotency contract** — `confirmed-with-caveats`. Pin with
  test + docstring; latent multi-thread risk.
- **D.2d consumer migration** — `confirmed-with-caveats`. VduProcessor
  spans 3 interfaces.
- **D.3a failure-history ring** — `confirmed-with-caveats`. Parallel
  atomic, not on view atom.
- **D.3b gen-tagged tracing** — `confirmed-with-caveats`. Caveat
  resolved in Phase 2 (head-tracing init).
- **D.3c `awaitOnline()`** — `deferred — no consumer demand`.
- **D.3d wireCode → UX hint** — `modified`. Catalog has no inference
  slot; 18 new entries to add.
- **D.4 UX features (6 items)** — mixed: item 2 `confirmed`, item 5
  refuted, item 6 deferred, others `confirmed-with-caveats`.

---

## Appendix B — Phase 4 critical-analysis writeup (2026-05-19)

The Phase 4 defect-fix slices were prompted by a critical re-read of
Phase 3's Wave A-E commits. The key findings:

- **HTTP span correctness depended on context propagation, not span
  emission.** Wave A.2's live verification showed spans appearing in
  `traces.ndjson` — concluded "shipped." But because `makeCurrent()`
  was never called, every child span authored by `AgentLoopService`,
  `KnowledgeHttpApiAdapter`, etc. produced disconnected roots. The
  trace tree was flat; the largest fraction of HTTP-instrumentation
  value (causal chains) was missing.
- **Sidecar latency wasn't fictional.** The transition lock holds
  during every `TransitionRunner.run()` invocation. Sidecar I/O inside
  that block was a latency regression on the same axis 518 worked to
  optimize.
- **Sparkline rendered only dots, not the line.** The plan said
  "sparkline of `generation` over time." Live verification confirmed
  dots emit, but the y-axis encoding (the actual value the user wants
  to see) was unused. Plan-promised feature partially missing.

Specifically NOT a defect: the Wave-A `matchedPath()` returning `*`
behavior. Empirical research confirmed Javalin's wildcard hook
registration makes its own pattern the matched path. The route-template
attribution gap remains; the fix is deferred (Fix-1) as documented in
Phase 4's "out of scope" section.

---

## Status

Tempdoc 529 is **implemented**. The four phases above closed every
in-scope item. Items intentionally deferred carry named blockers and
unblock conditions in their per-slice notes.

The parent tempdoc 518 remains **implemented** independently — 529's
work neither extends nor regresses 518's body.

---

## What this tempdoc is *not*

- Not a re-litigation of 518's body. The substrate work (P1–P5) is
  closed in 518.
- Not a forward roadmap. Everything here is shipped; the speculative
  follow-ons are in each slice's "deferred" notes.
- Not a commitment to maintain attribution by commit-message rewriting.
  Existing commits keep their original `feat(518): …` / `fix(518): …`
  prefixes; future commits in this area should use `feat(529):` /
  `fix(529):`.
