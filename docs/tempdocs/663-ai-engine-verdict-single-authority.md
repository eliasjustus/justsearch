---
title: "AI-engine verdict — single-authority state for the brain surface (re-architect one seam, don't rewrite)"
type: tempdoc
status: open
created: 2026-06-30
related:
  - 649-connection-truthfulness-under-load   # parent: shared root (FE state-truthfulness) + shared poller
  - 595                                        # the single-verdict kernel this extends to the brain
---

# 663. AI-engine verdict — single-authority state for the brain surface

> **Spun out of 649 ("Related finding").** 649 found the AI-engine (brain) state surface is the FE's most
> fragile, sharing 649's root theme (FE state-truthfulness) and the *same* timeout-less poller. 649 recorded
> the diagnosis + recommended direction but explicitly left the full fix to its own tempdoc. This is it.
> 649's connection fix and this are **separable** (neither depends on the other). Diagnosis carried from 649
> §"Related finding" — verify against `main` before trusting (649 is the more-current source).

## The problem

The brain is the **one major subsystem deliberately left outside the FE's anti-drift kernel.** Search/retrieval
health was collapsed onto a single `SystemHealthVerdict` every surface merely consumes (595, `explanation/27`),
gated against re-derivation. The AI/inference component was **explicitly excluded** (`state/verdict.ts:142-143`:
"the AI (inference) component is intentionally EXCLUDED … surfaced by its own AI-Engine card").

That exclusion left the brain doing by hand what every other surface gets for free → five compounding
fragilities (evidence from 649):

1. **`BrainSurface.deriveAiState()` reconciles ~5 overlapping state representations** by a hand-ordered
   precedence ladder (`views/BrainSurface.ts:1014-1047`): `installStatus`, local `busy[...]` flags,
   `runtimeStatus.onnxFeatures[].modelActive`, `_unifiedAiState.runtime`, and a separate raw `inference`
   snapshot. A comment admits the drift it fights — the representation-drift class the kernel makes unwritable
   elsewhere.
2. **Fed by four self-owned poll timers** (`pollInstall`/`pollPack`/`pollRuntime`/`pollDiagnostics`,
   `:270-273`) **plus** shared `subscribeAiState` (`:520`) **plus** on-mount `fetch`es — each independently
   stale-able. `installStatus` alone has 6 write-sites — every combination a potential wrong-state render.
3. **The most states / most complex lifecycle of any subsystem** (`not_installed → installing → offline →
   starting → connecting → online`, + `indexing`/`transitioning`, × per-variant activate/deactivate, GPU/VRAM,
   restart-ETA) — because it fronts an **external native process** (`llama-server.exe`) with far more failure
   modes than the in-process Lucene worker.
4. **Weakly-typed wire boundary:** the raw inference `mode` is an untyped `string` (`utils/inferencePoll.ts:13`)
   and `BrainSurface` branches on `mode === 'transitioning'` (`:1040`), a value absent from the typed runtime
   union `'offline'|'online'|'indexing'|'starting'|'unknown'` (`state/aiStateStore.ts:72`) — vocabulary drift
   escapes the compiler.
5. **Inherits 649's connection bug** — `inferencePoll` is one of the two timeout-less pollers that starve under
   the connection-pool exhaustion (`utils/inferencePoll.ts:48`), so a starved poll can make the brain read "AI
   Offline / Connecting…" wrongly for the same reason 649 made the header read "Reconnecting…".

Corroboration: `UnifiedChatView.ts` (~4302 lines) and `BrainSurface.ts` (~2051) are the two largest FE view
files — both AI-facing. (Honesty caveat from 649: this public mirror is squashed, so "breaks most frequently"
is argued structurally, not from git churn — but the design history keeps revisiting this surface:
518/586/601/604/627/630.)

## Judgment — re-architect one seam, do NOT rewrite

A "rewrite the brain" is the wrong, riskier tool:
- The fragility is **concentrated** in the state-reconciliation seam, not the render/install/variant code
  (downstream-but-correct).
- A rewrite **discards embedded edge-case knowledge** the precedence ladder encodes
  (runtime-active-while-install-stale, no-data≠not-installed, restart-ETA window, variant handling) — the
  classic second-system regression.
- The fix **already exists as a pattern** (the single-verdict kernel) — invent nothing.
- "Rewrite" slides into rewriting the two largest FE files on shared `main` — large blast radius for a bounded
  defect.

## Recommended direction (state/health layer only)

1. **Add an `aiEngineVerdict` authority** parallel to the search verdict — one typed place that folds install
   state + runtime mode + inference snapshot into a single AI-engine state. Type the boundary (kill the
   `mode: string` / `'transitioning'` drift; extend the runtime union or map at the edge).
2. **Make `BrainSurface` a pure consumer** — delete `deriveAiState()`'s 5-source ladder; collapse the four
   self-owned poll timers into the store.
3. **Keep the render markup** mostly as-is — once state is single-source it barely changes.
4. **Gate it** — extend the `verdict-derivation` gate so the AI verdict can't be re-forked.

**The key design insight (makes this a clean refactor, not a philosophical reopening):** the original 595
exclusion conflated *"don't raise a health alarm when AI is off"* (correct — AI is offline-by-design) with
*"don't give AI a single state authority"* (the actual mistake). A **calm-by-default, single-source AI
verdict** — one that never alarms but is single-authority — satisfies both. This is the lens that keeps the
work bounded.

## Scope
- **IN:** the `aiEngineVerdict` authority + typed boundary; `BrainSurface` (and other AI-mode consumers like
  the status pill) become pure consumers; the `verdict-derivation` gate extension.
- **OUT:** the render/markup, install/download/variant/GPU UI (correct as-is); the connection-pool resource
  fix (→ tempdoc 662); 649's reachability layer (shipped).
- **Interaction to preserve:** the `inferencePoll` timeout-less-poller starvation is shared with 649 and
  addressed structurally by 662 (consolidation) — this doc should make the AI verdict *resilient to* a starved
  poll (calm "checking", not false "offline"), mirroring 649's positive-contact treatment.

## Verification (when built)
- A regression test: a stale/failed `inferencePoll` does NOT flip the AI verdict to a confident "offline"
  (mirrors 649's anti-false-alarm test).
- `BrainSurface` renders solely from `aiEngineVerdict` (no `deriveAiState` ladder; the `verdict-derivation`
  gate fails on a re-fork).
- The typed boundary: no `mode: string` / unlisted `'transitioning'` literal escapes the compiler.
