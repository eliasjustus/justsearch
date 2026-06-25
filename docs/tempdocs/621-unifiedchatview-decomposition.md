---
title: "UnifiedChatView.ts — standing analysis & improvement charter for the chat convergence host (Cycle 1: decomposition + §F.3 fork-collapse, merged)"
type: tempdocs
status: "CHARTER OPEN · Cycle 1 MERGED to main 2026-06-20. Reframed from a one-shot 'decompose' into the standing health charter for the chat convergence host (see §0). Cycle 1 (decomposition + the 610 §F.3 live/record fork-collapse): UnifiedChatView.ts 5,436 → 4,030 (−26%), one reconciliation authority + one chat/RAG render body, FE suite 3363 green, reload==live live-validated. Phase 3 RETIRED (irreducible host orchestration). The findings register (§10) carries the open audit items; two are backend follow-ups (per-message shapeId + standaloneQuestion persistence)."
created: 2026-06-20
updated: 2026-06-20
author: agent (seed split out from tempdoc 618 §5)
category: dx / frontend / refactor / shell-v0 / maintainability
related:
  - docs/tempdocs/618-agent-developer-velocity-friction.md (§5 — where this was deferred from)
  - docs/tempdocs/610-chat-conversation-control-primitives.md (§F.3 — the dual-render-path bug-class)
  - docs/tempdocs/614-ui-information-architecture-separation.md (UI IA separation)
---

# 621 — `UnifiedChatView.ts`: the chat convergence-host health charter

# §0 — Purpose (reframed 2026-06-20): a standing analysis-and-improvement charter, not a one-shot decompose

> **What this document is.** The **standing health charter for `UnifiedChatView.ts`** — the chat
> *convergence host* (the one window for every interaction mode: free-chat / RAG / extract / agent /
> workflow). Its purpose is the **recurring, multi-axis analysis and improvement** of this one file, NOT a
> single refactor. It began (and was titled) as a one-shot "decompose," but the investigation itself
> (§1, §6.1, §7) found that this file is a *convergence host* whose defining property is **continuous
> accretion** — of responsibilities, latent bug-classes (the 610 §F.3 fork was discovered *reactively*,
> after shipping two live bugs), perf cruft, and UX-honesty gaps. A surface like that warrants an *ongoing*
> relationship, not a one-time fix. Hence the reframe.

**The model (the discipline that keeps "improve the file" from rotting into an unbounded grab-bag):**
1. **Umbrella, not a change.** The charter only *coordinates and records*. Every concrete improvement is a
   **scoped slice with its own single thesis** (like Cycle 1's P1–P4) — preserving one-reason-to-change.
2. **Bounded per cycle.** An audit **cycle** sweeps the file across the axes below and produces a **triaged
   findings list** (fix-now-as-a-slice / defer / backend-follow-up / wontfix-with-reason). A cycle is *done
   when triaged*, not when everything is fixed. Each cycle is **dated** — so the charter stays compatible
   with the "tempdocs are dated history" rule (this doc is append-only cycle records, not living truth).
3. **Rubric = the existing seams (objective, not taste).** The audit axes are conformance to what already
   governs this file: **correctness/behaviour** (live==record, no drift), **design-conformance** (the
   presentation kernel doc 27; the projection/merge-authority discipline §6.2; the convergence-host
   discipline §7B — orchestration on the host, presentation in projections, behaviour in controllers),
   **dead code / perf**, **UX-honesty** (no overstated reload parity), and **size/readability** (capped by
   the §3.5 irreducible-orchestration floor — *not* a target to drive to zero).

**Durable home (the one open structural question).** The host-specific findings + dated cycles live here in
621. The reusable audit *method* is a candidate to graduate into a **skill** (`/convergence-host-audit`, a
sibling of `/doc-audit`) so it can run against the sibling hosts too — see §7 reach (`Shell.ts`,
`SearchSurface.ts`). Recognised here; not built now (the recognise-vs-build separation).

**How to read the rest of this doc:** §1–§9 are the full record of **Cycle 1** (structure + the §F.3
bug-class — *merged*). §10 is the **standing findings register** (the open audit items, seeded from what
Cycle 1 surfaced-but-parked). A future cycle appends a dated `§11 — Cycle 2 …` and updates §10.

---

# Cycle 1 (2026-06-20, MERGED) — decomposition + the §F.3 live/record fork-collapse

> **Original seed (preserved).** A problem-statement SEED, split out of tempdoc 618 §5 (which deferred it as
> out-of-core for that effort). It named the problem so a future agent could scope and execute the work.
> Everything below was investigated, designed, implemented, and merged as Cycle 1.

## Problem (shallow)

`modules/ui-web/src/shell-v0/views/UnifiedChatView.ts` is the largest frontend file (~5400+ lines and
growing). Its size is friction in two distinct ways:

1. **Working friction.** The file is too big to read whole — every edit forces offset/limit/grep navigation
   (the `Read` auto-limit trips on it), which slows iteration and raises the chance of missing context.
2. **Defect friction.** Its size is *implicated in a bug-class*, not just inconvenient: tempdoc 610 §F.3
   describes a dual-render-path bug that the file's monolithic structure makes easy to introduce and hard to
   see. A large file with multiple overlapping responsibilities invites exactly this kind of drift.

The goal is to **decompose it into smaller, single-responsibility units** so both frictions drop — without
changing user-visible behaviour (this is a refactor, not a feature).

## Why this is its own tempdoc (not folded into 618)

618 is about agent developer-velocity *infrastructure* (worktrees, dev-stack, npm). This is a contained FE
refactor with its own risk profile (behaviour-neutrality, test coverage, the 610/614 coupling) and is large
enough to deserve its own scoping. 618 §5 marked it deferred and pointed here.

## Pointers for whoever picks this up (not commitments)

- **Coupling:** check tempdoc 610 §F.3 (the dual-render-path bug-class this decomposition should help close)
  and tempdoc 614 (UI information-architecture separation) — the right decomposition seams may already be
  implied there. Decompose along responsibility boundaries those docs name rather than inventing new ones.
- **Constraint:** `shell-v0` is a governed region (Lit, not React — ADR-0032; the presentation kernel in
  `docs/explanation/27`). Consult the governing decision docs before reshaping structure, and keep the change
  behaviour-neutral (the existing FE unit tests + `jseval ui-shot`/`/ui-check` visual verification are the
  guardrails).
- **Success criterion (to refine):** the file (and its split pieces) read whole within normal limits, the
  610 §F.3 bug-class is structurally harder to reintroduce, and `npm run typecheck && npm run test:unit:run`
  plus a live UI check stay green.
- This document records *observations*, not a design. No investigation beyond the 618 split has been done.

---

# §1 — Investigation (2026-06-20, agent; verified against `main` @ `52b2c2d48`)

> The sections below supersede the "shallow / not-investigated" framing above. Everything here is
> cited to `file:line` on current `main`. Numbers re-measured, not inherited from 618/610.

## §1.1 — File anatomy (where the 5,436 lines actually are)

`modules/ui-web/src/shell-v0/views/UnifiedChatView.ts` is **5,436 lines** (re-measured; the seed's
"~5,400+" was right). It is one `class UnifiedChatView extends JfElement` plus a module-level helper
prologue and a load-time registration epilogue. The lines cluster into seven responsibilities:

| # | Cluster | Lines (approx) | LOC | Nature |
|---|---------|----------------|-----|--------|
| A | Module prologue — `ShapeId`, constants, `ThreadMessage`, `SHAPE_LABELS`, `buildRequestBody`, `CONVERSATION_ZONES` | 226–331 | ~105 | **pure**, already module-level |
| B | Class state + lifecycle spine — `static properties`, ~25 fields, ~12 store subscriptions in `connectedCallback`/`disconnectedCallback`, `settleTransients`, bound handlers | 333–835 | ~500 | orchestration core (stays) |
| C | Transcript-control — turn menu, edit/retry/branch, context-floor (rewind), compaction, version pager, action bar, context meter | 850–1638 | ~790 | cohesive, stateful (tempdoc 610) |
| D | **CSS** — one `static styles = [...]` array (a `css\`\`` template flanked by 5 imported style modules + a `composeGridStyles(...)` call) | 1639–2944 | **~1,305** | **pure, mechanical** |
| E | Top-level render + chrome — `render`, degradation banner, affordance bar, answer plane, evidence rail | 2946–3251 | ~305 | host render |
| F | Agent-run presentation — run spine, activity rail, spine pointer/keyboard nav, budget/context decisions, citation resolution | 3252–3787 | ~535 | agent mode |
| G | Record + live render — timeline, unified-item, run trace/segment, grounding badge, source chips, retrieve tier, **`renderMessage` (live)** vs **`renderUnifiedItem` (record)**, send + SSE handlers | 3788–5402 | ~1,615 | **the §F.3 fork lives here** |

**Takeaway:** the single largest extractable block is **D (CSS, ~24% of the file)**, and it is the
*least* risky to move. The most *valuable* structural target is **G** (the dual-render fork), which is
small LOC of *new* code but high in defect-leverage.

## §1.2 — The two frictions are different work (the seed conflates them)

The seed names two frictions. Investigation shows they want **different, separable** fixes:

- **Working friction (Read-limit / navigation).** Dominated by sheer length. The fix is *bulk*
  extraction — and ~1,305 of the 5,436 lines (the CSS) carry **zero logic**. Moving the CSS out drops
  the file to **~4,130 lines** in one behavior-neutral move. This is the cheapest, highest-LOC win and
  it has near-zero blast radius.
- **Defect friction (610 §F.3 dual-render-path).** *Not* solved by gross file-splitting. It is solved by
  **finishing the `renderMessage`↔`renderUnifiedItem` unification**. Per 610 Probe B
  (`610-…md:1183`), that unification is already **~60% done** — the floor divider, frame line, and action
  bar route through shared adapters (`recordFloorParts` / `recordActionBar` / `frameFor` /
  `renderAnswerFrameLine`, confirmed at `UnifiedChatView.ts:1374,1385,4524,4543`) — and the remaining
  ~40% **cannot** unify cleanly because `UnifiedTurnItem` lacks `shapeId` / `inheritedFromParent` (the
  record path falls back to the global `currentShapeId()`). 610 estimated the finish at **~150–200 LOC**.

A good plan treats these as **two phases with two different success criteria**, not one "make it smaller."

## §1.3 — 610 §F.3 status: shipped *partial*, never completed

610 is `status: merged`. §F.3 declared the render-frame projection "warranted now," but the shipped state
is the ~60% adapters above — there is **no single `renderTurnFrame`**; `renderMessage` and
`renderUnifiedItem` remain two methods (`:4788`, `:4552`). So 621 is the natural home to *finish* what
610 §F.3 started. (This is consistent with `structural-defects-no-repeat`: the bug-class is documented;
the fix is endorsed; we do not need a second incident.)

## §1.4 — Tempdoc 614 is a *weak* pointer — de-scope it

The seed lists 614 as a coupling. Read in full (`614-…md`), 614 is a **user-facing information-architecture**
concern ("does the app teach a consistent mental model of where each task lives") — it explicitly "does
not prescribe implementation" and routes concrete sub-problems to *other* docs. It names **no code seam**
for decomposing this file. **Recommendation: drop 614 from 621's design inputs.** The real coupling is
610 (§F.3 fork) + the shell-v0 governance kernel. Keep 614 in `related:` only as lineage.

## §1.5 — The established decomposition vocabulary (don't invent a new one)

This file already sheds pieces along three sanctioned seams that exist *today* as siblings:

1. **Pure projection / view-model modules** in `views/` — `unifiedThreadProjection.ts`,
   `budgetProjection.ts`, `runStepPresentation.ts`, `searchResultViewModel.ts`, `unifiedThreadClient.ts`.
   Each is a pure function + a co-located `*.test.ts`. UnifiedChatView already *imports* all five.
2. **Reactive controllers** (Lit `ReactiveController`) in `controllers/` — `AgentSessionController.ts`,
   `ReasoningController.ts`, `DraftPersistence.ts`, `runControlIntent.ts`. The view already hosts
   `ReasoningController` (`this.reasoning`, `:5176,5188`) and `AgentSessionController` (`ensureAgentCtrl`,
   `:3519`) and `NavigationController` (`:536`).
3. **Sub-components** (separate `jf-*` custom elements) in `components/chat/` — `ToolCallCard`,
   `CitationsPanel`, `RunNode`, `MarkdownBlock`, `SourcesPane`, … (the view composes ~15 of them).

**Design consequence:** the decomposition must continue these three seams, not introduce a fourth. The
question per cluster is only *which* of the three a cluster belongs to.

---

# §2 — The binding constraint: governance-register coupling (the reason this is not a free refactor)

`UnifiedChatView.ts` is named **by path** as an adopter in **seven** governance registers. Each register
either (a) hard-fails the build if the named file stops composing/containing a seam, or (b) is an
early-warning grep. **Any line that participates in a registered seam cannot simply move to a helper file
without either staying behind or updating the register.** This is the single most important feasibility
finding — it bounds *what* can move and *how*.

| Register | Seam it pins in this file | `file:line` of the seam | Gate severity | Moving it requires |
|----------|---------------------------|-------------------------|---------------|--------------------|
| `composition-surfaces.v1.json` | `composeGridStyles(CONVERSATION_ZONES)` — **inside `static styles`** | `:2939` (inside the styles array) | **hard** (positive coverage) | keep the `composeGridStyles(...)` call in-host **or** repoint adopter |
| `intent-tier-coverage.v1.json` | `presetByShape` literal **+** `submitSearch` reference | `:628`, `:3212` | **hard** | keep both in `reg.window` **or** repoint `window` |
| `steering-surfaces.v1.json` | `dispatchRunControl(` / `.steer(` / `.cancelSession(` | `:5092` (initiate), agent ctrl | **hard** | keep dispatch sites in-host **or** add file to register |
| `run-renderers.v1.json` | mounts `jf-tool-call-card`; imports `composeToolLabel`, `projectUnifiedThread`, `projectLiveAgentActivity` | `:38`, `:169` | early-warning | keep imports/mounts in-host **or** repoint |
| `execution-surfaces.v1.json` | consumer of canonical `SearchTrace` (passes citations to panel) | entry `evidence-fe-unified-chat-view` | gate (consumer-exempt) | update `path` if the consumer line moves |
| `ui-step-coverage.v1.json` | screenshot steps `chat-mode`, `qa-response` map to this source path | register `source` | **hard** (path must resolve) | path stays (file is not deleted) — safe |
| `surface-task-state-retention.v1.json` (via `check-surface-task-state-retention.mjs`) | transient `@state` fields must reset in `settleTransients()`; no `resetUnifiedChatState(`/`setQuery('')` in connect/disconnect | `static properties` + `settleTransients` `:763` | **hard** | transient state stays `@state` on host **or** controller self-resets *and* host `settleTransients` still covers it |

**The CSS-extraction trap (verified).** The "obviously safe" Phase-1 CSS move is *not* unconditionally
safe: `check-composition-surfaces.mjs` reads `UnifiedChatView.ts` and asserts it contains
`composeGridStyles`. That call sits at the **tail of the `static styles` array** (`:2939`). Naively
moving the whole array out **breaks the composition gate.** The fix is to extract only the large
`css\`\`` *template body* into `views/unifiedChatStyles.ts` and keep the array *assembly* — including the
`composeGridStyles(CONVERSATION_ZONES)` call — in the host:

```ts
// host keeps the seam:
static styles = [composerStyles, unsafeCSS(SEARCH_EVIDENCE_CSS), whyThisResultStyles,
  facetChipStyles, highlightStyles, unifiedChatBodyStyles, composeGridStyles(CONVERSATION_ZONES, {...})];
```

This is the template for the whole effort: **move bulk, leave seams.**

---

# §3 — Critical analysis (questioning the seed's implicit assumptions)

**3.1 — "Decompose into smaller single-responsibility units" should NOT mean new custom elements (for most clusters).** A new `jf-*` element introduces a shadow-DOM boundary. But the view's spatial frame
(`run-spine | conversation | evidence-rail`) is generated by **one** `composeGridStyles` call into **one**
shadow root (`:325`, `:2939`); the ~1,305-line CSS styles those zones *together*. Splitting clusters E/F/G
into sub-elements would fragment that grid + duplicate/share CSS across roots + re-plumb events — a large
behavior-change surface in a region whose hard constraint is **behavior-neutrality**. **Prefer
pure-function render helpers (rendered into the host's existing shadow root) and reactive controllers;
reserve new elements for genuinely self-contained leaves.** This matches §1.5's established vocabulary.

**3.2 — Behaviour-neutrality hazard in controller extraction.** A Lit `ReactiveController` does *not*
auto-trigger re-render: a plain controller field change won't `requestUpdate` unless the controller calls
it. Several clusters mutate reactive `@state` (`isStreaming`, `streamingText`, `errorMessage`, `citations`,
`claims`, …). A faithful extraction must **keep the reactive `@state` declarations on the host** and have
the controller mutate `host.x` (or call `host.requestUpdate()`), *not* relocate the `@state` into the
controller. Relocating it silently changes the re-render trigger → stale renders. This argues for a
**conservative** controller boundary: extract *logic and event handlers*, not the reactive-state surface.
(It also keeps the `surface-task-state-retention` gate satisfied — see §2.)

**3.3 — Smaller file ≠ closed bug-class.** The two goals are orthogonal (§1.2). Bulk extraction (CSS,
transcript-control logic) shrinks the file but does *not* touch the §F.3 fork; finishing the fork closes
the bug-class but barely shrinks the file. The plan must do **both, separately**, and not let the cheap
LOC win masquerade as having addressed the defect friction.

**3.4 — Merge-conflict cadence.** shell-v0 + this file change fast and parallel agents share `main`
(CLAUDE.md "keep diffs scoped"). A single big-bang refactor maximizes conflict probability against
in-flight work. **Phase into small, independently-mergeable, independently-green slices** (CSS first,
then one cluster per slice), each landing on its own. Order phases so the *highest-churn* cluster (G, the
render fork) is touched in a dedicated slice when no neighbor holds it.

**3.5 — Is decomposition even the right intervention, vs. "leave it"?** Yes, but bounded. The file is a
genuine `Read`-auto-limit tripwire (CLAUDE.md `context-efficiency.md` lists it as a known large file),
and 610 proved the size *hosts* a real bug-class. But the governance coupling (§2) means there is a
**floor** on how small it can get without register churn: the orchestration spine (B, ~500 LOC) plus all
the seam-bearing lines are load-bearing in-place. A realistic floor is **~2,000–2,500 lines** (host =
spine + render entry + seam sites + the live render path), with CSS, transcript-control, agent-run
presentation projection, and record-item presentation extracted. Anything promising "~800 lines" would
be over-claiming and would fight the gates.

---

# §4 — Execution order (implementation-level; subordinate to the §6 design)

> §4 is the *execution sketch* that realizes the long-term design in **§6**. It is deliberately
> implementation-level (LOC, file names, phase order) — read §6 first for the structural intent; §4 is
> only "in what order, behaviour-neutrally, do we get there." If §4 and §6 ever disagree, §6 governs.

**Principle:** *move bulk, leave seams* (§2). Each phase is behavior-neutral, lands independently, and
keeps `npm run typecheck && npm run test:unit:run` green plus a `jseval ui-shot chat-mode qa-response`
visual check (the two registered steps). Every phase that moves a seam updates the owning register in the
**same** commit.

### Phase 1 — Extract CSS → `views/unifiedChatStyles.ts` (~1,290 LOC moved; ~0 risk)
- Export the big `css\`\`` body as `unifiedChatBodyStyles`. Host keeps the `static styles` array assembly
  **including** `composeGridStyles(CONVERSATION_ZONES)` (the composition-surfaces seam, §2).
- No register edits needed (the seam stays in-host). File: 5,436 → ~4,146.
- Verify: composition-surfaces gate + visual diff identical.

### Phase 2 — Extract the module prologue → `views/unifiedChatRequest.ts` (~105 LOC; ~0 risk)
- `ThreadMessage`, `SHAPE_LABELS`, `buildRequestBody`, `RAISE_BUDGET_STEP_TOKENS`,
  `EMPTY_PREFIX_SENTINEL`, `CONVERSATION_ZONES`. All already module-level + pure. `buildRequestBody` is
  trivially unit-testable in isolation (a co-located `*.test.ts` is a net coverage gain).
- Keep `CONVERSATION_ZONES` imported by the host so the `composeGridStyles` call still resolves in-host.

### Phase 3 — ~~Extract transcript-control → a `TranscriptControlController`~~ **RETIRED (2026-06-20)**
> **Decision: retired** — do not extract this cluster. Implementation-time source audit (§9.4) found the
> transcript-control methods coupled to **~22 host members** (state + the orchestration methods
> `loadConversation`/`send`/`branchHere`/`retryFrom`). A controller would have to expose all of them,
> *widening* the coupling surface and breaking encapsulation for **zero functional gain**. The
> `ReasoningController` precedent does not apply — that controller owns its *own* state, whereas
> transcript-control mutates host `@state` and drives host methods. It is **irreducible host
> orchestration** (§6.1), so it stays on the host. This empirically confirms the §3.5 floor: "readable
> whole" is unreachable for this convergence host without degrading it.
>
> *(Original plan, for the record: a Lit controller hosting turn-menu / edit-retry-branch / context-floor
> / compaction / version-pager logic, with `@state` kept on the host. Superseded by the above.)*

### Phase 4 — Collapse the turn-render fork at the MODEL layer (the §6.2 load-bearing move)
- This is the **defect-friction** phase (§1.2) and the realization of **§6.2** — *not* a frame-only
  helper. The order, per 610 Probe B's "pure helpers first, no big-bang":
  1. Add `shapeId?` / `inheritedFromParent?` to `UnifiedTurnItem` (close the type gap).
  2. Add a **live-overlay projector** (sibling to `projectLiveAgentActivity`) that projects the optimistic
     `this.thread` (`ThreadMessage[]`) into `UnifiedTurnItem[]`.
  3. Move the scattered "prefer fresher evidence, else record" reconciliation (`:4563/4584/4587`) into
     **one declared merge** inside `mergedTimeline` / the projection module.
  4. `renderMessage` collapses into `renderUnifiedItem`; the host renders one list through one path.
  5. Declare the single turn renderer as an **authority in `run-renderers.v1.json`** (the missing sibling
     of the registered run-projection / answer-renderer / tool-card), so a *third* turn-render site is
     caught by the same early-warning that already guards the run tier.
- Success criterion is **structural**: one model, one render path, registered — a future divergence is
  *gated*, not merely discouraged. Pin with a test rendering the same turn through the live and record
  entry points and asserting identical output (the §F.3 regression oracle).
- **Seam care (§2):** keep `projectUnifiedThread` / `projectLiveAgentActivity` imports + the
  `jf-tool-call-card` mount in-host (or repoint the register in the same commit).

### Phase 5 (optional, evaluate after 1–4) — Extract agent-run presentation (Cluster F, ~535 LOC)
- Candidate: a pure `views/agentRunPresentation.ts` (spine positions, node labels, activity-rail
  projection) + keep the pointer/keyboard handlers and the `dispatchRunControl`/`.steer(`/`.cancelSession(`
  sites in-host (steering-surfaces register, §2). Re-measure the file after Phases 1–4 before committing
  to this — it may already be under the working-friction threshold, in which case **stop** (YAGNI on
  further splitting; §3.5's floor).

**Projected end state:** ~4,146 (after P1) → ~4,040 (P2) → ~3,250 (P3) → ~3,100 (P4) → ~2,560 (P5).
Stop at the first phase where the file reads whole within normal limits *and* the §F.3 fork is closed.
P1+P4 are the load-bearing pair; P2/P3/P5 are LOC hygiene.

---

# §5 — Risks, verification, and open questions

**Verification per phase (the guardrails the seed named, made concrete):**
- `cd modules/ui-web && npm run typecheck && npm run test:unit:run` (the 2,147-line
  `UnifiedChatView.test.ts` pins ~40 behaviors — the behavior-neutrality oracle).
- The specific gates touched: `node scripts/ci/check-composition-surfaces.mjs` (P1),
  `node scripts/ci/check-intent-tier-coverage.mjs` + `check-steering-arbitration.mjs` +
  `check-run-renderers.mjs` + `check-surface-task-state-retention.mjs` (P3–P5 as relevant).
- Live: `jseval ui-shot chat-mode qa-response` then Read the PNGs (the two registered steps);
  load `/ui-check`. A live agent-run round-trip (chat↔agent lossless, pinned by `:923`) for P4/P5.

**Risks:**
- *Gate breakage from a moved seam* — mitigated by "move bulk, leave seams" + same-commit register edits.
- *Stale-render from relocated `@state`* (§3.2) — mitigated by keeping `@state` on host.
- *Merge conflict on shared `main`* (§3.4) — mitigated by small independent slices + doing the work in a
  worktree (this file is high-churn; branch per phase).
- *Over-splitting* (§3.5) — mitigated by re-measuring after each phase and stopping at the floor.

**Open questions for the owner (do before P3+):**
1. **Scope ambition:** is the goal *just* the working-friction floor (P1+P2, mechanical, ~1 session) or
   the full structural pass including the §6.2 fork-collapse (P1–P4)? P4 is the higher-value, higher-care
   work — and the only phase that closes the bug-class (the others are LOC hygiene).
2. **Controller vs pure-module for Cluster C:** a `ReactiveController` (stateful, idiomatic) vs a pure
   projection module + host-resident handlers. §3.1/§3.2 lean pure-module-where-possible; confirm.
3. **`UnifiedTurnItem` field additions (P4 step 1):** adding `shapeId`/`inheritedFromParent` touches a
   model that two projectors and the run-segmentation pass share — confirm no record-side consumer
   regresses (the projection's own `*.test.ts` is the oracle).

---

# §6 — The long-term design: the convergence host conforms to the seams it absorbed

## §6.1 — Reframing: size and the bug-class are two symptoms of ONE cause

The file is large **and** hosts the §F.3 bug-class for the same reason: it is a **convergence host** — the
one window that is the home for *every* interaction shape (561 surface tier; `:5418` registers all five
shapes to `ONE_WINDOW_MOUNT_TAG`). A convergence host predictably accretes responsibility, because every
shape's presentation and behaviour has *somewhere* to land and the path of least resistance is "another
method on the host." Over time it absorbs work that belongs to seams the codebase **already has**. The
correct design is therefore not "cut the file into N pieces by line count" — it is to **evict each absorbed
responsibility back to the seam that already owns its kind**, leaving the host a thin orchestrator. The
file's resulting size is an *outcome* of that eviction, not a target.

Three kinds of absorbed responsibility, three **existing** seams (extend, don't invent):

| Absorbed responsibility (today, in-host) | The seam that already owns its kind | Precedent in-tree |
|---|---|---|
| Turn / run / hit **presentation** | a **projection authority** (pure model→view, single source) | `unifiedThreadProjection`, `runStepPresentation`, `budgetProjection`; the run-renderers register |
| **Behavioural domains** (dispatch+streaming; transcript-control) | a **ReactiveController** | `AgentSessionController`, `ReasoningController`, `DraftPersistence` |
| **Styling** | a styles module | every sibling view |

The host keeps only **orchestration**: store wiring, lifecycle, mode routing, delegation. That residual is
irreducible for a convergence point — cf. `Shell.ts` (2,728 lines, the peer convergence host, decomposed
into `chrome/` yet still substantial, *and that is correct*).

## §6.2 — The load-bearing move: collapse the turn-render fork at the MODEL layer

The §F.3 bug-class is a **render-tier fork**, and the codebase has **already collapsed exactly this class
once**: 565 §12 collapsed the "two tool-card / two run-assembly authorities" fork into ONE registered
projection + ONE primitive (the run-renderers register, whose description names *"the 548 two-authorities
class at the render tier — the very fork §12 collapsed"*). The turn render is the **same class, not yet
collapsed**.

Read at source (`renderUnifiedItem`, `:4552`), the reason 610's partial frame-sharing (~60%) did not close
it is visible: the record path **conditionally delegates to the live path** — "prefer the live message when
it carries fresher evidence, else render from the record" (`:4563`, `:4584`, `:4587`). The divergence is not
in the *frame*; it is that **two turn MODELS feed the body** — `ThreadMessage` (live, optimistic,
FE-maintained in `this.thread`) and `UnifiedTurnItem` (record + agent, from the projection) — and the
scattered "prefer fresher / else record" branches are a **render-time reconciliation** of those two models.

> **The principle to conform to:** where two data sources feed one display — a **live/optimistic** stream
> and a **settled/record** projection — they converge at the **model layer** (one declared, pure merge), so
> the renderer sees **one model and one path**. Reconciliation lives in the projection, never in a render
> branch.

The usable existing design to **extend, not replace:** `unifiedThreadProjection.ts` *is* this projection,
and `mergedTimeline()` (`:3788`) already merges record + live-*agent* activity into one `UnifiedTurnItem[]`.
The single gap is that the **live chat/RAG optimistic thread** is *not* projected into that model — it stays
a parallel `ThreadMessage[]` and is reconciled at render time. Closing the gap (the §6.2 design, executed in
§4 Phase 4): project the optimistic thread into `UnifiedTurnItem` (adding the two missing fields), fold the
"prefer fresher" rule into the merge, collapse `renderMessage` into `renderUnifiedItem`, and **register the
single turn renderer in `run-renderers.v1.json`** as the missing sibling of the run-projection /
answer-renderer / tool-card — so a *third* turn-render site is caught by the same teeth that already guard
the run tier.

## §6.3 — Why this scope, and not more or less

- **Not less.** A frame-only helper with optional params (610 Probe B's pragmatic ~150–200 LOC) removes
  duplication but leaves the two models and the render-time reconciliation — the fork's *root* — in place,
  so the bug-class survives. A documented, twice-shipped bug-class requires closing the root: the model
  convergence.
- **Not more.** The host's orchestration spine, per-shape dispatch, and store wiring are intrinsic to a
  convergence point and stay. No "turn-lifecycle engine" and no "convergence-host framework" is built
  (§7) — there is no shared *mechanism* to extract, only a discipline plus the seams that already exist.
  `ThreadMessage` is **not** unified across other surfaces; the change is scoped to this one window's
  live↔record convergence.

---

# §7 — Reach: principle, instances, and candidate scope

**Is this an instance of an existing principle/seam?** Yes — three-fold; the design **conforms** rather
than parallels:

1. **Doc 27 presentation kernel** — single authority + the Collapse > Generate > Gate ladder. The turn frame
   is a presentation concept not yet pulled up the ladder; the design pulls it to the rung the run
   projection already occupies (a registered authority in `run-renderers.v1.json`).
2. **610 §F.3 display-axis projection principle** — "what the user sees is a single projection of the one
   canonical turn record." §6.2 is the faithful completion of that, not a new idea.
3. **565 §12 render-tier fork-collapse** — the exact move (two render authorities → one registered
   projection+primitive) applied to a new instance (the turn render). Same class, same register family,
   same mechanism.

**Does the design reveal a recurring shape worth naming?** Yes — two, at different altitudes (recorded, not
built):

- **(A) The live/settled model-convergence invariant** *(load-bearing).* **Where a surface renders a stream
  that exists in both a live/optimistic form and a settled/record form, the two must converge at the model
  layer via one declared merge; the renderer must see one model.** A render-time `prefer-live-else-record`
  branch is the smell that the merge is missing.
  - *Where else it would apply / audit candidates:* `SearchSurface.ts` (live search snapshot vs settled
    results, 1,643 lines); `BrainSurface` (live reindex progress vs settled job record); the
    **Activity / notification feeds (612 / 613)**. Notably, **613's own design pass independently reached
    the same shape** — "converge at the existing declaration seam (`EmissionPolicy`); project one identity
    to every locus; reject a fan-out engine" — which is corroborating evidence the invariant is real and
    already steering design elsewhere.
  - *Known violator:* `UnifiedChatView` (the §F.3 render-time reconciliation) — this tempdoc.

- **(B) The convergence-host discipline** *(organisational; weaker).* **A single element that is the one
  home for a family of modes delegates presentation to projection authorities and behaviour to controllers,
  retaining only orchestration; its size is an outcome, not a defect.** Applies to `Shell.ts` (mostly
  conforms via `chrome/`), this window, and any future "one X for all Y" surface.

**Do we build the generalised structure now? No — deliberately.** (A) is realised *per surface* by
conforming to the existing projection seam; beyond what `unifiedThreadProjection` / `mergedTimeline` already
are, there is no shared mechanism to extract. (B) is a discipline, not a framework — there is no
`ConvergenceHost` base class to write, and writing one would be the premature abstraction the kernel warns
against. The reach is **recorded here as audit candidates** (SearchSurface, BrainSurface, the feeds); each
conforms individually when its own problem requires it. Recognising the invariant is the deliverable;
building general structure is not.

> Status note (2026-06-20): this document is now **investigated and designed** (was a seed); §6 records the
> long-term structural design and §7 its reach; §8 records a pre-implementation confidence pass. No
> production code changed — the §4 phases realise §6 and are proposed, not executed. Awaiting the §5/§7
> scope decision before implementation.

---

# §8 — Confidence-building findings (2026-06-20, agent; pre-implementation, no feature code)

> A read-only + test-only pass to retire surprises before any §4 work. Seven probes (P0–P6), each
> `file:line`-cited. **One assumption was understated and is corrected (P1/P5); the rest confirmed.** The
> headline: the load-bearing Phase-4 move is **proven by a sibling already in-tree**, which moves it from
> "novel design" to "extend a proven pattern."

**P0 — Baseline is GREEN (the reference everything hangs on).** `npm run typecheck` exit 0; targeted
`vitest run UnifiedChatView.test.ts unifiedThreadProjection.test.ts` exit 0 (the trailing happy-dom
`AbortError` teardown traces are a known non-fatal artifact — vitest returns non-zero on real failures);
and the five file-pinned gates (`composition-surfaces`, `intent-tier-coverage`, `steering-arbitration`,
`run-renderers`, `surface-task-state-retention`) all **PASS** on the untouched file. So every "stays green"
claim now has a reference point.

**P1 — The Phase-4 model convergence is FEASIBLE and PROVEN-BY-SIBLING (the crux, de-risked).** The
decisive evidence is the `projectLiveAgentActivity` docstring (`unifiedThreadProjection.ts:322-325`): 565 §12
**already executed exactly the §6.2 move — for the agent path** — "project the LIVE agent run into the SAME
`UnifiedTurnItem` contract … killing the live-vs-record render fork." So Phase 4 is **extending a proven
in-tree projector to one more input (the chat/RAG optimistic thread)**, not inventing a mechanism. The
field-mapping audit (`renderMessage:4788-4884` vs `UnifiedTurnItem:116`) confirms the gaps are bounded:
`shapeId` + `inheritedFromParent` need adding (the known 610 gap); `standaloneQuestion` → `attributes`;
`isExtract` is **redundant** with `shapeId==='core.extract'` (no gap). **Correction to §6.2/§4-P4:** the move
is *not* "just add 2 fields" — the live evidence (`claims`/`sources`/`citations`/`ragMeta`) must be
**normalized into `attributes`** the same way the agent path already rides `LiveAnswerGrounding` on the last
assistant item (`:339-366`). That is *medium* work, not trivial — but it is the very "reconciliation moves
into the projection" the design calls for, and the agent path is the worked example to copy.

**P2 — The reconciliation is ~70% test-pinned; one branch needs a test FIRST.** Pinned: the core Phase-4
invariant ("single read-model: reconciled live turns dedupe, in-flight overlay", test `:888`, which seeds
*both* record events and a live thread), record-path evidence (`:195`), and the run/turn seams (`:324`,
`:406`). **Gap:** the specific *"prefer fresher live evidence in-session, else record"* branch
(`renderUnifiedItem:4584-4587`) has **no dedicated characterization test**. Per `audit-without-test`, the
implementer must **write that test first** (live-richer vs reload-record-only render identically post-merge)
before folding the rule into the projection. This converts Phase 4 from audit-driven to test-first.

**P3 — Phase 1 (CSS extraction) is gate-neutral (confirmed).** All four CSS-reactive gates
(`color-tokens`, `theme-token-closure`, `layout-purity`, `presentation-purity`) select files by walking the
whole tree (`**`/`readdir`/`walk`), so a new `views/unifiedChatStyles.ts` is scanned identically. Combined
with the §2 `composeGridStyles`-stays-in-host rule, Phase 1 trips nothing.

**P4 — The controller reactive-state pattern is confirmed (validates §3.2).** `ReasoningController` is a
plain class (not even `implements ReactiveController`); `AgentSessionController` is store-backed with a
`setHost`. Neither auto-triggers render — the host re-renders via its render-tick / store subscription. So
§3.2's mitigation ("keep the reactive `@state` on the host; the controller mutates host refs / the host
drives `requestUpdate`") is exactly the established pattern, and Phase 3 must follow it.

**P5 — Correction: Phase-4 step 5 needs NO new gate schema.** The `run-renderers` gate has **no
turn-frame authority slot** — but it already enforces a `runProjection` authority (only registered sites may
import `projectUnifiedThread`/`projectLiveAgentActivity`, `check-run-renderers.mjs:14-15`). Collapsing
`renderMessage` into the single `renderUnifiedItem` path **rides that existing authority** — the timeline is
assembled in one registered place by construction. So "register the turn renderer" (§4-P4 step 5) overstated
the work: **conform to the existing `runProjection` authority**; a dedicated turn-frame row would be optional
hardening (a schema extension), not a requirement.

**P6 — Visual oracle wired; live run deferred.** The `ui-shot` steps `chat-mode` / `qa-response` resolve to
`UnifiedChatView.ts` in `ui_step_index.json`; the harness is runnable via `/ui-check`. A full live
`jseval ui-shot` run needs the shared dev stack and is deferred to implementation time (coordinate
ownership) — not a design risk, a verification step.

## §8.1 — Confidence rating for the remaining work

| Phase | Confidence (0–10) | Why |
|---|---|---|
| P1 — CSS extract | **9** | Mechanical; gate-neutrality + baseline-green both verified. |
| P2 — prologue extract | **9** | Pure module-level move; nets test coverage. |
| P3 — transcript-control controller | **7** | Pattern confirmed (P4), but the largest behavioural surface and *no* gate pins it — discipline-dependent. |
| P4 — model convergence (the fork-close) | **7** | Architecture **proven by the agent sibling** (P1, up from ~5 pre-probe); residual is execution — evidence normalization (medium) + one test-first gap (P2). |
| P5 — agent-run presentation (optional) | **6** | Re-measure before committing; may be unnecessary (§3.5 floor). |
| **Overall (weighted, P1+P4 load-bearing)** | **7 / 10** | The *architectural* risk is largely retired (proven-by-sibling, registers understood, baseline green). What remains is *execution* risk: the evidence-normalization mechanics, the test-first characterization of one branch, and the behavioural breadth of the transcript controller. Nothing surfaced that invalidates the design; two claims were sharpened (P1 scope up, P5 work down). |

---

# §9 — Implementation status (2026-06-20, agent; worktree `worktree-uc-decompose`)

Implementation began on branch `worktree-uc-decompose` (off local HEAD). **Three phases landed,
verified, and live-validated; the load-bearing Phase-4 was re-scoped by a source-level discovery.**

## §9.1 — Shipped (committed, green)
- **Phase 1 — CSS extracted** to `views/unifiedChatStyles.ts` (`unifiedChatBodyStyles`). The host keeps
  the `static styles` array assembly + the in-host `composeGridStyles(CONVERSATION_ZONES)` call.
  File 5,436 → 4,140. Composition gate PASS; 71/71 view tests; typecheck clean. (commit `98cf6d493`)
- **Phase 2 — prologue extracted** to `views/unifiedChatRequest.ts` (`ThreadMessage`, `SHAPE_LABELS`,
  `buildRequestBody`, constants, `CONVERSATION_ZONES`) + a new `unifiedChatRequest.test.ts`. File 4,140 →
  4,044. Intent-tier gate PASS; 76/76 tests. (commit `995c3248e`)
- **Phase 4 (core) — the §F.3 reconciliation moved into the merge authority.** `renderUnifiedItem` no
  longer reaches into `this.thread` at render time to "prefer the fresher live message"; a new
  `attachLiveMatch` (applied in `mergedTimeline`) attaches the matched live `ThreadMessage` to the record
  item as `attributes.live`, computed ONCE. Match rules are byte-identical → **behaviour-neutral**; all
  live==record / dedup / prefer-fresher / reload tests stay green, plus a new §F.3 regression oracle.
  run-renderers / steering / intent-tier / composition / task-state gates PASS; 72 view + 30 projection
  tests. (commit `d785ca30c`)
- **Live UI validation (read-only):** served this worktree's FE against the running backend
  (`serve-worktree-fe`, port 5176) and drove the chat surface in the browser. The render path the change
  touches renders cleanly in **both** states — a populated conversation (user turns + assistant answer
  with its frame note + the `2 turns hidden · Include all` context-floor control) and the reload/resume
  state — with **no console errors**. (Full LLM round-trip blocked — see §9.3.)

## §9.2 — The Phase-4 re-scope (source-level discovery)
Reading the fork at source corrected the §6.2 framing: `renderMessage` (live) and `renderUnifiedItem`'s
record branch **render genuinely differently today** — the live path shows a **shape tag** + an
**"Interpreted as…"** note + the **full** turn action bar and computes the epistemic frame from the
**per-turn** `m.shapeId`; the record branch omits those and uses the **window-global** `currentShapeId()`
(a latent per-turn-shape bug for mixed-shape threads). So **fully** merging the two render bodies is **not
behaviour-neutral** — it *regularizes* the reload render (reload would gain the shape tag/frame). That is
the intended 610 §F.3 "live==record" outcome, but it is a **behaviour change on the core surface**, so it
was split from the neutral core:
- **Done (neutral):** centralize the reconciliation in the merge authority — closes the §F.3 *render-time
  cross-source* mechanism (the proven root) without changing any pixel. ✅
- **Deferred (behaviour-changing):** unify the two render *bodies* (eliminate `renderMessage`), which
  requires the evidence-normalization (§8 P1) **and** product sign-off for the reload-render
  regularization **and** a live LLM round-trip to validate. Recommended as its own slice/feature.

## §9.3 — Phase 5 (partial) shipped; full live LLM validation done (after stack takeover)
- **Phase 5 (partial) — pure run-spine helpers extracted** to `views/runSpinePresentation.ts`
  (`computeSpinePositions(items, fractions)` + `spineNodeLabel`) + co-located tests. Behaviour-neutral;
  matches the `runStepPresentation` pure-module pattern. controls-a11y / run-renderers / composition gates
  PASS; 72 view + 4 spine tests. (commit `a2f970395`) File → **4,020**.
- **Full live LLM validation (after the user approved dev-stack takeover):** activated the GPU runtime
  (Qwen3.5-9B, 11.3s), served this worktree's FE against it, and ran a **real RAG round-trip** in the
  browser ("What are the three main processes…?"). The answer rendered through the changed path with its
  frame note ("Partly grounded…"), grounded content, and **inline citation markers** — then **reloaded**
  and **auto-restored from the record**, rendering the same answer + citations (the `attributes.live ===
  undefined` reload path). **live == record confirmed end-to-end, no console errors.** So Phase-4-core is
  validated against a real model in both the streaming and reload states.

## §9.4 — Two findings that re-shape the remaining phases (do NOT just "do them")
- **Phase 3 is the WRONG decomposition (don't do it).** Source audit: the transcript-control methods are
  coupled to **~22 host members** (`thread`, `editingMessageId`, `contextFloorId`, `excludedMessageIds`,
  `sessionId`, `inputDraft`, … + orchestration methods `loadConversation`/`send`/`branchHere`/`retryFrom`).
  Extracting them to a controller would force exposing all of those — *widening* the coupling surface and
  breaking encapsulation for zero functional gain. The `ReasoningController` precedent does **not** apply:
  that controller owns its *own* state; transcript-control mutates host `@state` and calls host methods. It
  is **irreducible host orchestration** (§6.1 confirmed empirically). **Decision: Phase 3 RETIRED (user,
  2026-06-20)** — the transcript-control logic stays on the host (see §4 Phase 3).
- **Phase-4 full body-unification — the FE-only version is now DONE (§9.6); only EXACT per-turn shape on
  mixed-shape reloads remains backend-blocked.** Correction to an earlier over-claim: the convergence does
  NOT need the backend. The reloaded turn's shape comes from the window's **current shape**
  (`currentShapeId()`), the same source the former record branch already used — so a single-shape
  conversation reloads correctly FE-only. Only a *mixed-shape* conversation (shapes toggled mid-thread)
  needs a **per-message `shapeId`** (persisted only at the conversation level today) for exact per-turn
  labels — a dedicated backend slice, not a blocker for the convergence.

## §9.6 — Phase-4-full SHIPPED (FE-only) + live-validated (commits `0c0b4204e`, `7b9e97248`)
The last duplicate render body is gone. The inline RAG branch in `renderUnifiedItem` is replaced by
**enrich-then-`renderMessage`**: the reloaded turn (the live thread entry, or a minimal reconstruction when
thread-less) is enriched with the record's persisted evidence (reusing `claimsFromRecord` /
`matchesFromRecord`) and rendered through the ONE chat/RAG body. Its shape comes from `currentShapeId()`
(not the placeholder the auto-restore seeds); a reloaded extract keeps its verbatim `transform` frame
(`isExtract` derived from the mode). `renderMessage` now also emits `data-item-id` (so the run-spine scroll
resolves the turn) and omits the thread-coupled action bar when `idx<0`. So a reloaded RAG/chat turn renders
the **same answer, shape tag, frame, and citations as live** — full 610 §F.3 "live==record" closure for the
rendered body; the agent branch (`attributes.sources`) stays.
- **Live validation caught a real bug:** the first cut inherited the reloaded thread's `shapeId` (the
  auto-restore's placeholder `core.free-chat`), so a reloaded Document-Q&A turn mislabelled as
  "Chat · doesn't search your documents". Fixed to `currentShapeId()` (`7b9e97248`); re-validated in the
  browser against a live GPU model — **reload now == live** (Document Q&A + Partly grounded + inline
  citations), no console errors. This is exactly the tier the DOM-unit-test alone missed (it had seeded a
  realistic-looking but unreachable `rag-ask` thread shape).
- **Two known FE-unachievable deltas (both backend follow-ups), confirmed by the post-impl review:**
  (1) exact per-turn shape on a *mixed-shape* conversation needs a per-message `shapeId`; (2) the live
  "Interpreted as…" rewrite note needs `standaloneQuestion` persisted on the assistant record —
  `ConversationEngine.persistedAssistant` stores only `citations`/`calibration`/`claimMatches`, so the note
  is **live-only** (SSE `rag.rewrite`) and absent on reload. The enrich wires the `rag.standaloneQuestion`
  read as **forward-compat** (lights up when the record carries it). Single-shape conversations (the norm)
  reload correctly; neither delta is a regression vs the prior `currentShapeId()` record branch.

## §9.7 — Net (as shipped)
File **5,436 → 4,021 (−26%)** across **6 commits** (P1 CSS · P2 prologue · P4-core reconciliation ·
P5-partial spine · P4-full one-render-body · P4-full shape-fix). The 610 §F.3 fork is **fully closed** — the
render-time cross-source reconciliation (P4-core) AND the duplicate chat/RAG render body (P4-full), both
behaviour-validated and the latter **live-validated end-to-end** (ask → reload == live). Open items: a
**retired phase** (Phase 3 — irreducible orchestration) and one **backend follow-up** (per-message `shapeId`
for mixed-shape reloads). The "readable whole" goal is confirmed unreachable for this convergence host
without degrading it (§3.5 / §6.1, empirical). Merged to `main` 2026-06-20 (8 commits incl. the
post-review fixes + the `execution-surface` register for the extracted `unifiedChatRequest.ts`).

---

# §10 — Standing findings register (the charter's open items)

> The triaged audit findings for `UnifiedChatView.ts`. Each row: axis · severity · triage. *Fix-now* items
> become their own scoped slice (a future cycle). This register is the charter's living surface — seeded
> from what Cycle 1 surfaced-but-parked; a future audit cycle adds rows and re-triages.

| # | Axis | Finding | Sev | Triage |
|---|------|---------|-----|--------|
| F1 | correctness / backend | A reloaded *mixed-shape* conversation labels every turn with the window's current shape (`currentShapeId()`), because **per-message `shapeId` is not persisted** on the assistant record (`ConversationEngine.persistedAssistant`). Single-shape (the norm) is correct. | low | **backend follow-up** (persist per-message shape) — separate cross-stack slice. |
| F2 | correctness / backend | The live "Interpreted as…" rewrite note (SSE `rag.rewrite`) is **absent on reload** — `standaloneQuestion` is not persisted on the assistant record. The FE read is wired forward-compat. | low | **backend follow-up** (persist `standaloneQuestion`) — separate slice. |
| F3 | correctness / honesty (FE) | `renderMessage` computes the grounding `sourceCount = (sources?.length) + (claims?.length)` — for RAG that sums *retrieved docs* + *grounded sentences*, a possible **double-count** in the epistemic-frame signal. | ~~med?~~ | **RESOLVED 2026-06-21 — NOT a bug.** Source-verified: both consumers use `sourceCount` strictly as `=== 0` (`answerFrame` `evidenceProjection.ts:115`; `groundingDegraded` `:139`) — a has-any-evidence boolean; the grounded/partial refinement uses `coverage.cited/total`, and the displayed "N sources" comes from the grounding badge, not this. The magnitude never reaches an outcome, so the double-count is inert. Also behaviour-equivalent to the old record path's `citations.length` (for RAG `claims>0 ⇒ sources>0`, so both are `===0` together). Cosmetic-only; no fix warranted. |
| F4 | design-conformance (FE) | `renderLiveOverlay` still renders the *pending* (not-yet-recorded) chat thread as a **third place** chat items mount (alongside `mergedTimeline`→`renderUnifiedItem`). Folding it into the timeline was deferred in Cycle 1 (seam/order/`ts` work). | low | **defer** — fold into the merge in a later cycle; not a drift risk today (it's a filter, not a reconciliation). |
| F5 | design (FE) | The deeper §6.2 convergence — unify the **two turn models** (`ThreadMessage` live vs `UnifiedTurnItem` record) so the renderer has one input type — was done *pragmatically* (enrich-at-render) not *structurally*. The reconciliation is in the merge (good); the model duality remains. | low | **defer** — a future cycle could unify the models; current state is correct + tested. |
| F6 | perf (FE) | `attachLiveMatch` + the record-path enrich do `this.thread.find/findIndex` per record item per render → **O(record × thread)** on long threads. Same as the pre-Cycle-1 code, but now the standing baseline. | low | **defer** — measure on a long thread before optimising (build an index map only if it shows). |
| F7 | maintainability | File is **4,030 lines** — above the "readable-whole" comfort, but the further-extractable surface is capped by the §6.1 **irreducible-orchestration floor (~2,500)** (Phase 3 retired for this reason). | low | **wontfix-by-design** — re-measure each cycle; only act if a *new* genuinely-separable cluster appears (a projection or a controller, not orchestration). |

**Cycle 1 status of the register:** F1/F2 dispatched as backend follow-ups; **F3 RESOLVED (not a bug —
verified 2026-06-21)**; F4–F7 are deferred/by-design. **No open FE work item remains** — the register's only
actionable was F3, now closed; the file is in a known-good, merged, validated state. (The two backend
follow-ups F1/F2 are a separate cross-stack slice; the only structural recognition outstanding is the
`/convergence-host-audit` skill graduation, deliberately not built — §0.)

**Register upkeep — 2026-06-21 (tempdoc 610 landed on top of the decomposition).** 610
(chat conversation-control) merged at `main@ff3511033`, rebased onto Cycle 1; the only conflict was the
styles extraction, resolved in favour of this decomposition (610's four CSS blocks — source-chip
hide/restore, the context-meter breakdown trigger, the excluded-summary row — were ported *into*
`unifiedChatStyles.ts`, and `UnifiedChatView.ts` kept the `unifiedChatBodyStyles` reference). **Integration
verified clean by this charter:** typecheck green, 108 view+projection tests pass, the decomposition seams
(styles module, `unifiedChatRequest`, the merge authority `attachLiveMatch`, the run-spine helpers) all
hold. Host effects to fold into the *next* audit cycle: **F7 size refreshed 4,030 → 4,235** (610 added
`renderContextMeter`'s attribution breakdown, `renderSourceChips`'s per-source exclude, the context-inspector
trigger); **F4** (the third render path) now also has the new source-chip/inspector branches; the new shared
state stores (`state/excludedSources.ts`, `state/contextInspectorDrawer.ts`, `components/ContextInspectorPane.ts`)
are 610's, not host-orchestration, and are correctly *outside* the host. No new *fix-now* item — the host
remains known-good; this is the charter doing its job (tracking the host's evolution per landing).
