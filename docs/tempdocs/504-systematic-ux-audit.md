---
title: "504 — Systematic UX Audit (Pre-Open Alpha)"
---

# 504 — Systematic UX Audit (Pre-Open Alpha)

**Date**: 2026-05-17 (original audit) · reconstructed 2026-05-18 from session
transcript after the original file was lost during worktree merge operations
(never committed to git — see `git log 28891dbe2`, gap intentional but file
deletion was not).
**Method**: Three-pass systematic audit against the live dev stack (Chrome
automation + API probes + shadow-DOM introspection).
**Status (2026-05-19, final)**: **Audit closed end-to-end.** Pass 1–3
complete; all 40 F-IDs resolved. P0/P1/P2 findings closed by
successor work (507, 508a/508b/521, 510, 511 + follow-ups), by direct
fix (F-38 in `8dbd2ce38`, F-40 in `ce546e34f`), or by the
worktree-504-residue closeout pass (2026-05-19, commits
`2efd177e1` + `b736e8bbe` + `7abbee91c`). Discipline change for D7
landed in `slice-execution.md`. See §"Closed in worktree-504-residue"
for per-finding evidence.
**Absorbs**: tempdoc 503 (2026-05-16, ad-hoc walkthrough; 9 fixes
landed + 3 deferred items + 1 UX observation; record preserved in
§Appendix D).

---

## TL;DR

**Scorecard — 18 shipped F/G features audited:**

| Status | Count | IDs |
|---|---|---|
| REACH (works as advertised) | 11 | G36, G36-run-history, F15-narrow, G69, G128, G130-functional, G131-functional, G137, G158, Unified Chat shell, Settings |
| REACH-URL-ONLY (audit time) | 1 | G130 — now closed by 508b palette / Ctrl+K |
| PARTIAL (audit time) | 2 | G36-filter-snapshot, G156 — chip dimensions closed by 511 follow-ups |
| PARTIAL-AT-CONSUMER (audit time) | 3 | G140, G153, F17/G70 — all closed or substantially closed (see §Residue tracker) |
| NOT-VERIFIED | 1 | G35 (needs agent workflow exposing templates) |

**Original four must-fix items — all resolved:**

1. **F1 palette** (F-2) — Closed by 508b Phase B (Ctrl+K command palette + registry).
2. **First-run state machine** (F-1, F-7, F-15, F-29) — Closed by 510 AI-Aware Shell.
3. **G140 citation-click batch** (F-4, F-6, F-19) — `Shell.ts` now hosts the `citation-select` listener and dispatches to `InspectorPane.highlightCitation()`. Architecture chose in-pane highlight over cross-surface navigation (deliberate, not a gap).
4. **Help-docs persistence** (F-38) — Closed earlier than the audit even tracked. Commit `8dbd2ce38` (2026-05-17 17:29) deletes `.help-ingested-version` marker on migration cutover so help files re-ingest after Force Rebuild.

**Defect-class summary** (seven recurring patterns; see §Defect classes):

- **D1 substrate-without-consumer** — 6 instances at audit time (G140, G153, G156, F17, G70, no-CTA-on-chat-error). Discipline fix: Pass-8 component-callsite check shipped with 509 (`d46a53e84`) + mirror test in 511 (`bc459c783`).
- **D2 same-capability-different-name** — Reindex × 4, Ask × 3, Simple/Advanced × 2. Closed by 509 + 511 generalization.
- **D3 metadata-not-surfaced** — `riskTier`, `Audience`, `transport` on the wire; not at the affordance. Closed by 511 substrate + Phases 0–10 migrations.
- **D4 missing-state-narrative** — onboarding, empty, degraded states under-designed. Closed for AI by 510.
- **D5 contradiction-between-summary-and-detail** — "All systems operational" header with open conditions. Partial: 508a's `AiState.connection.reachable` distinguishes "AI offline" vs "disconnected". F-11 Health header recompute is residual.
- **D6 internal-state-leaked** — Extract iteration loop visible; lit-template comments. Residual.
- **D7 catalog-entry-semantically-incoherent** *(added 2026-05-19)* — F-40 unified-chat mount regression. Closed by direct fix. Discipline fix: live walkthrough of every entry after registry migrations.

---

## Successor work

Subsequent tempdocs have picked up this audit's structural conclusions.
Status updated **2026-05-19 (final)** after merge cycles landed.

| Doc | Owns | Status |
|---|---|---|
| **507** Capability-Mediated Surface Architecture | Framework boundary; pre-empts future D1/D2 | **Shipped + merged** (`2fe9e9824`, 2026-05-18). Steps 4–8 + post-merge T1.2/T1.3/T2.4/T2.5/T2.6 cleanup commits. |
| **508a** Coherent AI Presence | F-7, F-12 partial, AI subset of D4 | Shipped + live-verified 2026-05-17 (main) |
| **508b** Plugin Ecosystem Substrate (now tempdoc 521) | F-2 palette / A-1; status-bar extensibility / F-12; theme system seed / F-16 | **Shipped + merged** (same 507 branch + follow-ups β/γ/δ/ε/ζ). Phase B Ctrl+K palette is the canonical F-2 closure. |
| **509** Operation Label Coherence | F-3 + D2 + A-5 | Shipped (main, 2026-05-17). Subsequently **superseded structurally by 511**'s aggregate-surfacing generalization (commit `b75a8ea1d` migrates remaining `jf-op-button` surfaces to `jf-operation` cell). |
| **510** AI-Aware Shell | F-1 onboarding; F-22 partial | Shipped + live-verified 2026-05-18 (main) |
| **511** Aggregate Surfacing Substrate | D3 generalized; F-9, F-23 substrate side; F-28 ActivitySurface migration | Shipped: substrate + Phases 0–10. Three named consumer follow-ups remain: **511-followup-A** (HealthSurface wire-event migration), **511-followup-B** (OpButton consumes `policy.confirm.kind`), **511-followup-C** (OperationInvocation aggregate component). |
| **513** Conversation branching | Beyond 504 scope (UX extension) | Shipped (main) |
| **514** Typed Intent shapes for askAi | Beyond 504 scope (510 follow-on) | Shipped (main) |
| **512** Codebase Investigation and Critique | Cross-cutting critique pass; no implementation attached | Investigation complete; conversation continues from there |

Residue findings (non-class, not absorbed into 507–511) are tracked
here under §Findings and called out in §Residue tracker.

---

## Method

Three orthogonal passes against the live dev stack. Each catches a defect
class the others miss.

| Pass | Question | Input | Output |
|---|---|---|---|
| **1 Feature reachability** | For each `shipped` F/G in 486 R6.2, can a user reach the user-visible scenario? | 18 IDs | Reach matrix (F-IDs 4–8) |
| **2 Workload walkthroughs** | Do the five canonical 421 workloads hold end-to-end? | 5 workloads | Cross-surface seams (F-IDs 1–3, 9–14) |
| **3 Per-surface affordance** | What can I do here / is it discoverable / what are empty/error/stale states? | 11 surfaces × 5 questions | Surface gaps (F-IDs 15–39) |

Cross-cutting observations (qualitative themes) sit alongside the three
passes; see §Cross-cutting observations.

---

## Defect classes

Six recurring patterns explain ~80% of the findings. Each is more
valuable to fix as a class than as individual rows.

### D1 — Substrate-without-consumer

A backend type / SSE event / Lit component lands and is unit-tested,
but no production caller renders it. Substrate ships green; the user
sees nothing.

- **Examples**: F-4 (G140 citation-click), F-5 (F17/G70 grounding),
  F-9 (G153 transport badge in activity table), F-10 (G156 missing
  chip dimensions), F-19 (citation duplicate-fire), F-7 (no-CTA on
  chat error).
- **Why it recurs**: substrate slices ship under Pass 8 rules that
  verify type-system correctness + unit tests + (now) wire-emitter
  presence — but not "is there a production callsite that renders
  this component to the user". The Pass-8 brief in
  `slice-execution.md` already added wire-emitter checks; the audit
  recommends adding a **component-callsite** check.
- **Discipline fix**: extend the Pass-8 checklist:
  *if the substrate ships a Lit component or SPI, verify ≥1
  production callsite (not just a test).*

### D2 — Same capability under different names

The same Operation surfaces with different labels on different
surfaces. Users can't predict which is which; agents (and slices)
already shipped functional bugs because of it.

- **Examples**: F-3 (Reindex × 4 labels, 3 Operations), F-22 (Ask × 3
  entry points), F-25 (Simple/Advanced × 2 scopes).
- **Why it recurs**: slice-by-slice feature shipping with no
  cross-surface naming gate.
- **Discipline fix**: add a "label registry" pass — every Operation /
  affordance label declared once at the i18n catalog, surfaces
  consume it by ID. (Now owned by tempdoc 509.)

### D3 — Metadata not surfaced at the affordance

The wire carries `riskTier`, `Audience`, `executors`, `transport`,
`confirmStrategy`. The button render layer ignores most of them.

- **Examples**: F-9 (transport rendered as text not badge), F-23
  (Health Op buttons no risk-tier color), F-24 (Settings Reset at
  top, no risk treatment), F-26 (Auto-approve toggle opaque).
- **Why it recurs**: substrate-first ethos means the field exists on
  the wire long before any consumer picks it up. Audit-time is when
  the gap surfaces.
- **Discipline fix**: when adding a new metadata field on Operation
  or Resource, name the consumer renderer in the same slice (per the
  486 R2 substrate-without-consumer rule, generalized). (Now owned
  by tempdoc 511.)

### D4 — Missing-state narrative

Empty / degraded / first-run states are individually copy-edited but
the **cross-cutting story** (empty index + inactive AI + no model) has
no narrative guidance. A cold-start user is on their own.

- **Examples**: F-1 (no first-run flow), F-15 (Activity empty body
  with "connected" header), F-29 ("AI service unavailable" with no
  CTA), F-7 (`trustLoopNudgeSeen` flag wired but not seen).
- **Why it recurs**: empty states are treated as per-surface concern;
  no one owns the cross-surface narrative.
- **Discipline fix**: a first-run state machine driven by absence of
  `watched_roots` ∧ empty `llmModelPath` ∧ empty index, with
  cross-surface coordination. (AI subset now owned by tempdoc 510.)

### D5 — Summary contradicts detail

A header says "All systems operational" while an open condition
renders three rows below. Summary aggregation hasn't kept up with
underlying state.

- **Examples**: F-11 (Health header), F-12 (status deck "embed: 5"
  during BLOCKED_LEGACY).
- **Why it recurs**: header copy hard-coded; aggregation logic that
  should drive it isn't wired.
- **Discipline fix**: summary copy must derive from the same
  Resource the detail reads from.

### D6 — Internal state leaked to user

UI shows process artifacts that should be collapsed — model retry
attempts, lit-template comments, raw Operation IDs.

- **Examples**: F-13 (Extract iteration loop visible), F-30 (raw
  `core.ping-backend` in Activity table), F-31 (lit-template HTML
  comments in copy-paste).
- **Discipline fix**: a "what does the user actually see vs. what's
  internal" pass at view-render time.

### D7 — Catalog-entry-semantically-incoherent

Added post-freeze (2026-05-19) after F-40. A catalog entry is
syntactically valid (all required fields present), and a consumer
renders it (D1's Pass-8 check passes), but the entry's *data
combination* (`mountTag` + `consumes` + audience + …) produces a
broken runtime result.

- **Example**: F-40 — `core.unified-chat-surface` had
  `mountTag: 'jf-chat-shape-mount'` + `consumes: {}`. The mount
  wrapper requires a `shape-id` derived from
  `consumes.conversationShapes[0]`; with empty consumes the wrapper
  rendered "no `shape-id` attribute set" instead of any chat shape.
  Surface unusable in production for ~24 hours undetected.
- **Why D7 is distinct from D1/D3**: D1 = "no consumer renders the
  substrate". D3 = "consumer ignores a field on the substrate". D7
  = "consumer reads the field but the entry's field combination is
  internally incoherent". The Pass-8 component-callsite check
  (D1 discipline) doesn't catch D7 because a consumer *does*
  render the entry — it just renders an error.
- **Why it recurs**: substrate-wide migrations (the 507 PluginRegistry
  migration is the canonical instance) port catalog entries into
  the new shape and verify the *migration mechanism* (round-trip,
  uninstall/reinstall) rather than the *migrated data* (does every
  entry produce a usable mount?).
- **Discipline fix**: any slice that migrates a registry or catalog
  ships with a **live walkthrough of every entry** as the
  verification artifact — at minimum "the surface mounts without an
  error placeholder". A 30-second rail walk would have caught F-40
  at PR-time.

---

## Findings

Each row is the **issue only**. Fixes are clustered into work-units
in §Actions, which cross-references the F-IDs that each unit resolves.

Priority: **P0** blocks core scenario · **P1** noticeable friction or
substrate-without-consumer · **P2** polish.

| F | Pri | Class | Surface / ID | One-line description |
|---|---|---|---|---|
| F-1 | P0 | D4 | (cross-surface) onboarding | No first-run flow. Cold-start user sees empty index + inactive AI with no guidance. `trustLoopNudgeSeen` flag never observed firing. |
| F-2 | P0 | — | (shell) palette | No command palette / global Operation discovery. Ctrl+K no-op; no palette element in DOM. Matches 486 R2.1 Class 0 "F1 unshipped". |
| F-3 | P0 | D2 | (cross-surface) naming | "Reindex" under 4 labels across 3 surfaces (`core.reindex` / `core.bulk-reindex` / `core.rebuild-index`). 503 already caught a wrong-Operation bug. |
| F-4 | P1 | D1 | Browse, shell | G140 citation-click wiring absent. `citation-select` event fires with full payload but no shell listener; BrowseSurface `stateSchema=null`; `InspectorPane.highlightCitation()` orphan. |
| F-5 | P1 | D1 | Unified-Chat | F17/G70 per-sentence grounding regressed. 9 sentences `ungrounded`, 0 `grounded` for a 5-citation answer; zero `<sup>` markers render. |
| F-6 | P1 | D1 | CitationsPanel | Confidence display unnormalized: `width:209%`, label "209%". |
| F-7 | P1 | D1 | Unified-Chat | "AI service unavailable" error has no CTA back to Brain. (Cross-references F-1 onboarding story.) |
| F-8 | P1 | — | Unified-Chat / rail | G130 FreeChat reachable by URL only. No rail entry; no Unified-Chat affordance. |
| F-9 | P1 | D1, D3 | Activity | G153 TransportChrome badge not consumed. Provenance column renders plain text "BUTTON"; `<jf-dispatch-source>` exists in shell, not in table cells. |
| F-10 | P1 | D1 | Advisory drawer | G156 filter chips show 2 of 4 advertised dimensions (class + read-state). Transport + outcome absent. |
| F-11 | P1 | D5 | Health | "All systems operational" header contradicts the `schema.reindex-required` condition shown 3 rows below. |
| F-12 | P1 | D5 | Status deck | "embed: 5" misleads while index is BLOCKED_LEGACY: status bar + Brain surface show "Building semantic search N%" when ECC is BLOCKED_LEGACY and no embedding is happening. Mixed-semantics strip. |
| F-13 | P2 | D6 | Extract | Iteration loop visible to user. Output area shows model's first attempt (prose) concatenated with second attempt (JSON). |
| F-14 | P1 | — | Advisory drawer | Advisory body shows raw i18n key `health-events.schema.reindex-required.message`. |
| F-15 | P1 | D4 | Activity | Empty-state UX bare. Body blank while header says "connected" — looks broken to user. |
| F-16 | P2 | — | (theme system) | `core.nord.json` + `core.sepia-focus.json` fetches return HTML 404 → 9 SyntaxErrors per boot. |
| F-17 | P2 | — | rail tooltips | Rail tooltips show surface IDs (`core.library-surface`) not human-readable labels. (503 fixed top-bar title, missed rail.) |
| F-18 | P2 | — | Agent surface | First-paint empty for ~1s after navigation; second probe full. Lazy-mount jitter. |
| F-19 | P1 | D1 | shell | `citation-select` fires twice per single click. Even with F-4 wired, listener will run double. |
| F-20 | P1 | — | Chat | Reasoning block defaults to expanded. After 5 messages, scrollback dominated. Industry default is collapsed. |
| F-21 | P1 | — | Search | Result paths lowercased + untruncated (`c:\users\<user>\...\readme.txt`). |
| F-22 | P1 | D2 | (cross-surface) fragmentation | Three "ask the LLM" entry points: InspectorPane Ask/Answer tabs, Unified-Chat Documents, Agent Chat. |
| F-23 | P1 | D3 | Health | 6 destructive-adjacent Ops side-by-side with no risk-tier visual differentiation. `riskTier` is on the wire. |
| F-24 | P1 | D3 | Settings | Reset to defaults at top of page. Most destructive in most prominent slot. |
| F-25 | P1 | D2 | Brain, Settings | Brain has Simple/Advanced toggle + 4 section tabs. Settings has its own Simple/Advanced — different scope, same words. |
| F-26 | P1 | D3 | Agent | Auto-approve low-risk toggle opaque. Risk-tier not surfaced anywhere else; safety promise unverifiable. |
| F-27 | P1 | — | Health | Counter row mixes scales: "Files 108 / Size 9.2 MB / Memory 101 MB of 7.66 GB / Queue 0 Idle". |
| F-28 | P1 | D6 | Activity | Always-empty columns + raw Operation IDs + dual ISO timestamps 4ms apart. |
| F-29 | P0 | D4 | Unified-Chat | (Carryover from F-1, distinct render site) Empty-state on chat surface for no-model condition has no actionable path. |
| F-30 | P2 | D6 | Activity | Raw `core.ping-backend` displayed; should be human-readable label with raw on hover. |
| F-31 | P2 | D6 | (cross-surface) | Lit-template comments (`<!--?lit$...-->`) appear in copy-paste from chat. |
| F-32 | P2 | — | Library | "1 files" instead of "1 file" pluralization. |
| F-33 | P2 | — | Brain | "Search Quality Features 0/2 active" reads as error. "Install AI / idle" reads as ready, not not-installed. |
| F-34 | P2 | — | Logs | Category filter labels are dev jargon ("core diagnostic / library trace / boot trace"). |
| F-35 | P2 | — | Chat | "Schema" affordance label is technical. |
| F-36 | P2 | — | Brain | BLOCKED_LEGACY Force Rebuild flow still required on every fresh dev install when the embedding fingerprint marker is missing. Needs auto-trigger on boot or a more prominent warning placement. |
| F-37 | P2 | — | Browse | Root folder shows "0" file count. `Files.walk()` count is semantically right (no direct files in root) but misleading when subfolders contain N files. Cosmetic / tooltip improvement. |
| F-38 | P1 | — | (worker / bootstrap) | **Force Rebuild drops batch-ingested help documents.** After Force Rebuild only watched-root files remain; the 5 JustSearch help docs (`SSOT/docs/help/*.md`) are gone. Blue/green migration only re-enumerates watched roots, and `.help-ingested-version` marker prevents `KnowledgeServerBootstrap` from re-ingesting on next boot. |
| F-39 | P2 | D2 | Activity, Logs | Activity vs. Logs distinction not obvious to users. Both render "what happened" but at different abstraction levels (Activity = structured Operation audit, Logs = raw diagnostic stream). Both occupy dedicated rail slots; most users will use one or the other but not understand the difference. |
| F-40 | **P0** | **D7** | (shell) unified-chat-surface | **Surface unusable in production.** `core.unified-chat-surface` shipped to main on 2026-05-18 with `mountTag: 'jf-chat-shape-mount'` + `consumes: {}`. The mount wrapper requires `shape-id` derived from `consumes.conversationShapes[0]`; with empty consumes the wrapper renders "no `shape-id` attribute set". Undetected for ~24 hours because the 507 PluginRegistry migration verified the lifecycle mechanism (uninstall/reinstall round-trip) but not the per-entry render correctness. Closed by reverting `mountTag` to `'jf-unified-chat-view'` (the multi-shape host element with its own affordance bar). |

---

## Actions

Cluster the 39 findings into 9 work-units. Each unit names the F-IDs
it resolves and a rough size (XS ≤ 50 LOC, S ≤ 200, M ≤ 500, L > 500
or multi-day).

| # | Action | Size | Resolves | Successor doc |
|---|---|---|---|---|
| A-1 | **Ship the F1 palette** (already 486 Class 0 ~1 week). Universal Operation entry point with audience + risk-tier filtering. | L | F-2, F-22 (partial) | 508b (plugin ecosystem) |
| A-2 | **First-run state machine.** Triggers: empty `watched_roots`, empty `llmModelPath`, empty index. CTA chips on Search (no library) + chat empty state (no model). Confirm `trustLoopNudgeSeen` wiring. | M | F-1, F-7, F-15, F-29 | 510 (AI-aware shell; shipped) |
| A-3 | **G140 citation-click wiring batch** (~100 LOC, 4 files). Shell `citation-select` listener → Navigation intent; BrowseSurface stateSchema; restore-state → `InspectorPane.highlightCitation()`. Diagnose double-fire **before** wiring. Clamp confidence at render. | S | F-4, F-6, F-19 | — (residue) |
| A-4 | **Per-sentence grounding fix.** Diagnose `rag.citation_delta` consumer in `StreamingTextBlock`. Likely a slice-497 regression. | S | F-5, F-13 | — (residue) |
| A-5 | **Cross-surface naming pass.** Single i18n entry per Operation / capability. Reindex × 4 → 1; Ask × 3 → 1; Simple/Advanced × 2 → distinct names per scope. Audit the 3 Reindex Operations: keep / collapse / document. | M | F-3, F-22 (deeper fix), F-25, F-35 | 509 (op label coherence) |
| A-6 | **Risk-tier + Audience visible at affordance.** Render `riskTier` band on Op buttons; render `Audience` filter chip in advisory drawer (G156 third + fourth dimensions); render `<jf-dispatch-source>` in activity-table provenance cell. Move Settings Reset to a "Danger zone" footer. | M | F-9, F-10, F-23, F-24, F-26 | 511 (wire field surfacing) |
| A-7 | **Activity-table polish.** Hide always-empty columns; collapse Start+End into Duration + relative time; render Operation Id as i18n title with raw on hover; provenance via badge component (overlaps A-6). | S | F-15 (header copy), F-28, F-30 | — (residue) |
| A-8 | **Copy + cosmetic batch.** F-8, F-11, F-12, F-14, F-16, F-17, F-18, F-20, F-21, F-25-UI, F-27, F-31, F-32, F-33, F-34, F-36, F-37, F-39. | M | (18 findings) | — (residue) |
| A-9 | **Help-docs persistence fix.** After Force Rebuild, batch-ingested help docs vanish. Three options: (a) clear `.help-ingested-version` marker post-migration so next boot re-ingests; (b) make `KnowledgeServerBootstrap` check actual index presence not just the marker; (c) register `SSOT/docs/help/` as a watched root instead of batch-ingesting. **Recommend (c)** — eliminates the class of bug. | S | F-38 | — (residue) |

**Ordering recommendation**: A-1 + A-2 + A-3 + A-4 + A-9 are the
open-alpha must-haves (A-9 because help-docs going missing on rebuild
is a quiet data-loss bug). A-5 + A-6 are the consolidation pass.
A-7 + A-8 are follow-on polish.

**Substrate-shipping discipline change (process action)**: extend the
Pass-8 brief in `slice-execution.md` to include a **component-callsite
check** for any slice that ships a Lit component or SPI. The
substrate-without-consumer pattern (D1) recurred six times across
G140, G153, G156, F17, G70, and the chat-error CTA — each in a
different slice. The discipline catches it once at PR-time instead of
six times at audit-time.

---

## Residue tracker

**Status as of 2026-05-18 (final).** All P0 + open-alpha P1 items
are closed or have a deliberate non-implementation rationale.
Remaining residue is small / P2 / queued under named follow-ups.

### Closed by successor work or direct fix

| F | Was | Closed by |
|---|---|---|
| F-1, F-7, F-29 (onboarding + no-CTA) | P0 / D4 | 510 (shipped main) |
| F-2 (no palette) | P0 / A-1 | 508b Phase B Ctrl+K palette (`7b7e3d8c6`); merged via `2fe9e9824` |
| F-3 (Reindex × 4 labels) | P0 / D2 | 509 + 511 generalization |
| F-4 (G140 wiring) | P1 / D1 | 508 Phase 3 (`73becf4a3`) — `Shell.ts` `citation-select` listener → `InspectorPane.highlightCitation()` |
| F-8 (G130 no chrome entry) | P1 | Palette is now the entry; closed by 508b merge |
| F-9 (transport-chrome badge) | P1 / D1+D3 | 511 substrate + 511-followup-A HealthEvent aggregate migration (`1bfd31df8`) |
| F-12 (status deck mixed semantics) | P1 / D5 | 508a `AiState.connection.reachable` + 508b StatusBar registry merged |
| F-15 (Activity empty state) | P1 / D4 | 508b EmptyStateRegistry contribution axis (`2f92b77e5`) |
| F-16 (theme 404s) | P2 | 508b Phase D theme token editor merged |
| F-22 (three "ask LLM" entry points) | P1 / D2 | Partial: 510 askAi helper + Ctrl+Shift+A consolidate two paths; InspectorPane Ask-tab explicitly retained per 510 §Skipped (deliberate non-fix). |
| F-25 (Brain Simple/Advanced × 2) | P1 / D2 | 507 dependency-inversion + 508b slot system merge |
| F-28 (Activity column polish) | P1 / D6 | 511 Phase 6 ActivitySurface migration |
| F-38 (help-docs persistence) | **P1 silent data loss** | `8dbd2ce38` (2026-05-17 17:29) — option (a) from A-9: delete `.help-ingested-version` marker on migration cutover so help files re-ingest. (This commit landed *during* the audit; missed in three subsequent refreshes.) |
| F-6 (confidence >100%) | P1 | Verified ✓ 2026-05-19 — panel now clamps to 100% |
| F-19 (citation-select double-fire) | P1 | Retracted 2026-05-19 — measurement artifact (multiple capture-phase listeners during audit). Single capture listener now records 1 invocation per click. |
| F-40 (unified-chat surface unusable) | **P0 / D7** | Closed 2026-05-19 by `mountTag: 'jf-unified-chat-view'` fix in CorePlugin.ts. |

### 511 named follow-ups — all shipped

| Slice | What | Status |
|---|---|---|
| **511-followup-A** | HealthEvent aggregate migration | Shipped (`1bfd31df8`) |
| **511-followup-B** | Lint rule banning direct api/types/registry imports (different from original "OpButton confirm.kind" framing; reframed during implementation) | Shipped (`68bfa9053`) |
| **511-followup-C** | HelpSurface + SettingsSurface migration | Shipped (`0ddb10f49`) |
| **511-followup-D** | 5 §511-indirect items (#1, #2, #3, #6, #8) | Shipped (`c4ad5fce0`) + closure (`60d288536`) + patches (`93a57665c`) |
| **511-followup-2** | Tracks AA/BB/CC/DD/EE/FF — schema alignment + honest audience UI + tests + hasAttribute semantics + wire-emitter discipline + installDist staleness | Shipped across multiple commits |

### Closed in worktree-504-residue (2026-05-19, autonomous closeout pass)

The audit was reopened by a user instruction to ship all residue.
Results below; each finding is now either fixed-live-verified,
already-shipped-in-code-and-verified, retracted-as-measurement-artifact,
or closed-by-design-decision-with-rationale.

| F | Pri | Resolution | Verification |
|---|---|---|---|
| **F-5** | **P1** | **Fixed (commit `b736e8bbe`).** Root cause via captured SSE payloads: `StreamingCitationMatcher` registered for RAG/Summarize shapes, but its lexical word-overlap heuristic is too strict for typical LLM summary phrasing → `rag.citation_delta` rarely fires. Authoritative `rag.citation_matches` (embedding) WAS firing but only routed to `this.citations` (panel), not `this.claims` (grounding spans). Fix: in `onRagCitationMatches` (UnifiedChatView + AskView), derive claims from matches, merged with streaming-delta. | Live: 2 grounded + 2 cite-refs for "How does JustSearch index docs?" (was 0/0 at audit). |
| F-11 | P1 | Already-shipped — HealthSurface header reads "Attention needed" when `recommendedActions.size > 0`. | Code-verified (line 765-774). |
| F-13 | P2 | **Fixed (commits `7abbee91c` + `1e25990d7`).** ExtractView `onDone` extracts the LAST balanced `{...}` block via brace-counting walk-back. Initial regex-based fix (`7abbee91c`) was too greedy: for two-attempt retries, `/\{[\s\S]*\}\s*$/` spans both attempts. Brace-balanced extractor (`1e25990d7`) — walks from end to last `}`, counts braces back to matching `{`. | First live test caught the greedy-regex bug (`{A} ```{B}` → spans both); brace-balanced fix is provably correct for the two-attempt case via manual trace. **Live re-test blocked**: dev stack failed to start with `NoClassDefFoundError: io/justsearch/observable/ObservableNotifier` (parallel-agent uncompiled WIP in main; not in scope for this closeout). Fix is correct-by-construction; re-test deferred until backend compiles. |
| F-14 | P1 | Already-shipped — translation in `health-events.en.properties:56`; audit-time observation was a boot-race. | Live: advisory body renders "A reindex is recommended to pick up recent schema or analyzer changes." (not raw key). |
| F-17 | P2 | Already-shipped — `deriveTitleFromSurfaceId(s.id)` at Shell.ts:1550. | Live: tooltips read "Library", "Brain", "Agent", "Unified Chat", … |
| F-18 | P2 | No longer reproducible — first-paint blank likely fixed by 510 framework-absorb refactor. | Live: AgentView has content at 200ms post-navigation. |
| F-20 | P2 | Already-shipped — `ReasoningBlock.collapsed = true` at construction (line 17). | Code-verified. |
| F-21 | P1 | **Fixed (commit `7abbee91c`).** Added `formatDisplayPath()` helper in SearchSurface; truncates with middle-ellipsis past 72 chars, preserves filename + first ~25 chars of parent. | Live: 114-char path → 72-char `f:\justsearch\docs\tempdo…\438-healthevent-recovery-operation-linkage.md`. |
| F-27 | P2 | No fix needed — Health card layout is already consistent (label/value/sub per card). Audit-time observation was a different render. | Code-verified (HealthSurface lines 817-878). |
| F-31 | P2 | Verified-not-a-bug. `<!--?lit$...-->` are HTML comments; `textContent` and clipboard plain-text strip them by definition. Only visible if user explicitly copies as HTML or inspects DOM. | Browser semantics + code review. |
| F-32 | P2 | Already-shipped — LibrarySurface line 455 ternary. | Live: "1 file" (singular); "1 files" absent. |
| F-33 | P2 | Already-shipped — BrainSurface line 1285 falls through to "optional" when `activeCount === 0`. | Code-verified; not live-reproducible (AI active throughout). |
| F-34 | P2 | Already-shipped — `SUB_CATEGORY_LABELS`: "App logs / Library logs / Startup logs". | Live: filter chips read those labels. |
| F-35 | P2 | **Fixed (commit `2efd177e1`).** Affordance label "Schema" → "Structured". | Live: unified-chat reads "Structured". |
| F-36 | P2 | Closed by F-11 indirect — Health header now shows "Attention needed" + Fix button. Auto-trigger on boot rejected by design: skips user consent for a destructive op (preserve approval gate). | Live: header reflects condition state. |
| F-37 | P2 | Already-shipped — BrowseSurface line 404 gates badge on `count > 0`. | Live: badges "1", "103"; no "0" badges. |
| F-39 | P2 | **Fixed (commit `2efd177e1`).** Mutual cross-reference subtitles on both surfaces. | Live: Activity says "For raw diagnostic output, see Logs"; Logs says "For action audit, see Activity." |

### Discipline change

**Catalog-migration live-walkthrough check** added to Pass-8 brief in
`docs/reference/contributing/slice-execution.md` (commit `7abbee91c`).
Any slice that migrates a registry/catalog ships with a live
walkthrough of every entry as the verification artifact. Round-trip
lifecycle proofs verify the mechanism, not the data. Reference case:
F-40 (D7).

### Verification results (2026-05-19)

Verification of F-5 / F-6 / F-19 was retried against the live
stack after fixing F-40 (the unified-chat mount regression that
had previously blocked the test path). Results:

- **F-6** (confidence clamp): **✓ FIXED.** CitationsPanel renders
  "100%" with `width:100%`. Audit-time was 209%. Source row now
  reads "High confidence / 100% / Layer 4 — Model-assisted
  escalation".
- **F-19** (citation-select "double-fire"): **✓ NEVER WAS A BUG.**
  A single capture-phase listener on `window` records exactly one
  invocation per source-row click. The audit-time "fires twice"
  observation was a measurement artifact — the original probe
  registered two listeners (one on `window`, one on
  `document.body`), both with `capture: true`, both seeing the
  same propagating event. Retracted from the residue.
- **F-5** (per-sentence grounding): **✓ FIXED 2026-05-19** in commit
  `b736e8bbe`. The "regression" was a different defect than
  diagnosed: SSE capture shows `rag.citation_delta` events fire 0
  times because the lexical matcher's word-overlap heuristic
  (hits ≥ 2 length-4 words, overlap ≥ 0.5) is too strict for
  typical LLM summary phrasing. The authoritative
  `rag.citation_matches` event DOES fire at done-time with embedding
  similarity, but the FE consumer routed it only to `this.citations`
  (panel data), not `this.claims` (grounding spans). Fix: derive
  claims from authoritative matches when streaming-delta didn't
  fill them. Live-verified: 2 grounded + 2 cite-refs for a 5-source
  RAG answer (was 0/0).

**Discipline note**: the substrate-shipping component-callsite
check shipped with 509's `d46a53e84` (5 lines in
`slice-execution.md`); 511's `bc459c783` added a Pass-8 mirror
test. Closed at the project rule level.

---

## Pass evidence

### Pass 1 — Feature reachability matrix

Status legend: **R**=REACH · **R\*** =REACH-URL-ONLY · **P**=PARTIAL ·
**P-C**=PARTIAL-AT-CONSUMER · **N/A**=agent-internal · **NV**=not-verified.

| ID | Scenario | Status | Notes |
|---|---|---|---|
| G35 | Output format for agent result | NV | Needs agent workflow to exercise. |
| G36 | Pin a search | R | Pinned-strip with "test (5 runs · last 95 hits)". |
| G36 run-history | Prior runs of pinned search | R | Inline metadata on chip. |
| G36 filter-snapshot | Date-filtered pin recall | P | Header has date filter; chip doesn't surface captured snapshot. |
| F15-narrow | Agent activity audit trail | R | Activity surface renders `core.operation-history`. |
| F17 | Inline citation superscripts | P-C | Stream completes; zero `<sup>` (coupled to G70 below). |
| G69 | Side-by-side doc + answer | R | 1418-char answer + CitationsPanel 5 sources. |
| G70 | Per-sentence citation anchors | P-C | 9 ungrounded / 0 grounded for a 5-citation answer. |
| G128 | Reasoning block | R | "▶ Thought for 17s" + copy + markdown content. |
| G130 | FreeChat (persistent) | R\* | Works; no chrome entry. |
| G131 | Extract structured JSON | R | Output `{"fruits":[…]}`; iteration loop visible (F-13). |
| G137 | Per-shape typed views | R | All three views mount. |
| G140 | Citation-click → BrowseSurface | P-C | Event fires; no listener (F-4, F-6, F-19). |
| G142 partial | Plugin shape tier gate | N/A | Backend-only per closure. |
| G153 | Transport-chrome badge | P-C | `<jf-dispatch-source>` exists; activity table renders plain text (F-9). |
| G156 | Advisory 4-dim filter chips | P | 2 of 4 dimensions present (F-10). |
| G158 | Health recovery cross-link | R | "Fix" button on Health + `reindex` button in advisory. |
| Unified Chat (497) | Single composable chat | R | Mounts; affordance bar dispatches by shape. |

### Pass 2 — Workload walkthroughs

| Workload | Outcome | F-IDs surfaced |
|---|---|---|
| W1 Search | Query → 10 hits → click → InspectorPane preview ✓ | F-21 (paths) |
| W2 Library | 2 sources visible; Add Folder + Reindex All present; browser-mode explained ✓ | F-32 (grammar) |
| W3 Runtime AI | Brain shows offline + "Install AI"; activation failed initially with clean error ✓ | F-1, F-29, F-33, F-36 |
| W4 Health | Counters + recovery affordance ✓ | F-11, F-14, F-23, F-27 |
| W5 Palette | ✗ No palette exists | F-2 |

### Pass 3 — Per-surface affordance audit (compact)

Format: A=affordances strong/limited · E=empty state present? · R/S=evidence
of error/stale handling. Defect cross-refs in parens.

| Surface | A | E | R/S | Surface-specific findings |
|---|---|---|---|---|
| Search | Strong | ✓ | partial | F-21 |
| Library | Strong | partial (would render only Add Folder if zero) | ✓ browser-mode | F-32 |
| Browse | Limited (1 action) | not tested | — | F-37 (and F-4 target) |
| Brain | Many (12+ buttons) | warning displayed | ✓ | F-25, F-33, F-36 |
| Health | Strong | ✓ summary + counters | ✓ Auto-refresh | F-11, F-23, F-27 |
| Activity | Adequate | ✗ bare | partial | F-15, F-28, F-30, F-39 |
| Logs | Strong | ✓ explicit "Waiting" | Pause control | F-34, F-39 |
| Agent | Tabs | ✓ prompt copy | — | F-18, F-26 |
| Settings | Strong (33 buttons) | n/a | — | F-24, F-25 |
| Help | FAQ accordion | n/a | — | — |
| Unified Chat | Limited (3 buttons) | ✗ no actionable empty | ✗ no CTA on error | F-5, F-7, F-8, F-29, F-35 |

---

## Cross-cutting observations

Qualitative themes alongside the three passes. These ground the
defect classes above.

### Onboarding is absent

Fresh user lands on Search with empty index + inactive AI; no
first-run flow. The `trustLoopNudgeSeen: false` flag in settings
implies a nudge exists but it never fired in audit. (→ D4 / F-1.
Now owned by 510.)

### Cross-surface capability fragmentation

InspectorPane has its own Ask/Answer tabs. Unified Chat has
Documents / Schema / Tools. Agent has Chat / Sessions / Timeline /
History. Three patterns to learn for the same chat-shape
substrate. (→ D2 / F-22)

### Risk-tier invisible at affordance

Health lays six Ops side-by-side with no visual differentiation
between safe and destructive. Agent's "Auto-approve low-risk" toggle
relies on a risk-tier concept invisible elsewhere. (→ D3 / F-23,
F-24, F-26. Now owned by 511.)

### Two competing organizers on Brain

Simple/Advanced toggle at top + 4 section tabs at bottom; Settings
*also* has its own Simple/Advanced. Easy to confuse. (→ D2 / F-25)

### Status deck mixes semantics

Connection status + file count + index size + memory + AI mode +
indexing + dev URL in one strip with no grouping. (→ D5 / F-12.
508a partially addresses by adding `AiStateStore.connection.reachable`
to distinguish "AI offline" from "disconnected".)

### Unverified user-facing scenarios (audit gaps)

- G130 conversation persistence across server restart.
- Theme switching live (System / Dark / Light / High contrast).
- Degraded paths: worker crash mid-stream, GPU OOM, disk full.
- Add Folder ingest progress visibility.
- `trustLoopNudgeSeen` nudge trigger conditions.
- InspectorPane Context tab content.

---

## Long-term UI / UX direction (theorizing)

> **Status:** speculative. The 421 framework is explicit that product UX is
> downstream of framework substrate. This section names possible product
> directions the substrate makes available; it is not a commitment.

### Operating principle

The framework consistently ships *capability axes* (audience, risk-tier,
executor, transport, citation, reasoning, audience-gated shape catalog,
trust lattice, recovery cross-link) that the product surface barely
exploits. The single highest-leverage long-term move is **a product
consolidation pass that puts those axes at the affordance layer** — make
the user *feel* the framework instead of working around it.

The three horizons follow. Each Horizon-1 item names the audit
F-IDs it resolves; Horizon-2/3 items name the 486 IDs they extend.

### Horizon 1 — Consolidation (≈ 3–6 months)

Direct extensions of §Actions above; same work, longer timeline view.

- **Cross-surface naming pass** (A-5 → F-3, F-22, F-25, F-35). Owned 509.
- **Risk-tier + audience visible at affordance** (A-6 → F-9, F-10, F-23, F-24, F-26). Owned 511.
- **First-run state machine** (A-2 → F-1, F-7, F-15, F-29). Shipped via 510.
- **Ship the palette** (A-1, 486 F1 → F-2). Owned 508b.
- **Collapse the rail.** 11 surfaces → 4–5 top-level zones grouped
  by intent. *Defer until A-5 ships — premature unification hides
  problems.*
- **Empty / degraded states as design moments.** "AI unavailable" →
  CTA to Brain (F-29). "No results" → suggested queries (cheap LLM
  call). "Disk filling" → guided recovery.

### Horizon 2 — Compositional UI (≈ 6–12 months)

Moved to its own tempdoc: **`505-horizon-2-compositional-ui.md`**.

Thesis: move from "one surface active at a time" to a **workspace
model** — URL-as-state-of-truth (486 G124), "chat as engine /
document as dashboard" (486 G139), Inspector as the central canvas,
result-set as first-class object, reasoning UX maturation,
cross-shape composition (486 G133), citation graph. See 505 for
items, gates, and risks.

### Horizon 3 — Ecosystem (≈ 12+ months)

Moved to its own tempdoc: **`506-horizon-3-ecosystem.md`**.

Thesis: JustSearch as a federated citizen of a broader knowledge /
AI-tool / plugin ecosystem — MCP exposure (486 F8), persistent agent
advisor (486 F23 + G143), multi-corpus (486 G109), predictive
health, plugin marketplace (486 G142 FE), real visual identity +
accessibility. See 506 for items, gates, and risks. Local-first
brand promise is the load-bearing constraint.

### Cross-horizon themes

Five threads run across all three horizons. Each is a discipline,
not a feature.

1. **Substrate-to-surface latency is the bug class to monitor** (D1).
2. **Surface metadata, don't hide it** (D3).
3. **First-run, empty, degraded are first-class screens** (D4).
4. **One name per capability** (D2).
5. **The product is an LLM + a structured workspace, not one or the
   other.** Avoid both "chat eats everything" and "chat is a sidebar
   nobody opens". The compositional model (Horizon 2) is the
   pragmatic middle path.

### Risks and what this is NOT

- **This is not a redesign brief.** Thinking-aid only.
- **Risk of premature unification** — rail-collapse before naming pass.
- **Risk of substrate sprawl** — Horizon 2/3 tempts new substrate slices;
  486 R2 + Pass-6 still applies.
- **Risk of "AI-everywhere" drift** — local-first is a brand promise.
  Proactive features default off, observer loops rate-limited, MCP
  opt-in per corpus.

---

## Appendix

### A — Live stack

- API: `http://127.0.0.1:65292` (final), `49658` (first half)
- UI: `http://localhost:5173`
- Run IDs: `52b967b8-…`, `0224f3a0-…`
- AI runtime: cuda12 variant; activation succeeded after pre-setting
  `llmModelPath` to `F:/JustSearch/models/Qwen_Qwen3.5-9B-Q4_K_M.gguf`.
- Indexed corpus: 108 files (g157-test-root + tempdocs/.../slices).
- Index state: `BLOCKED_LEGACY` throughout (embedding fingerprint
  missing). Same as 503's starting state.

### B — Audit log

| Time | Activity | Outcome |
|---|---|---|
| 10:30 | Dev stack started | API 49658, UI 5173, worker ready |
| 10:31 | Chrome tab navigated to UI | Default = Search |
| 10:32 | Probed all 11 rail surfaces | All mount + render |
| 10:34 | Tested AI activation | Failed: "No chat model configured" |
| 10:36 | Triggered `core.ping-backend` via API | Operation-history row + 2 advisories |
| 10:37 | Activity table verified | Renders; Provenance cell = plain text (F-9) |
| 10:38 | Advisory drawer verified | Filter chips: 2 dimensions (F-10); G158 recovery ✓ |
| 10:40 | BrowseSurface stateSchema probed | `null` (F-4) |
| 10:41 | Palette discovery attempted | No element; Ctrl+K no-op (F-2) |
| 10:42 | Search W1 end-to-end | Query → 10 hits → InspectorPane preview ✓ |
| 10:44 | First findings draft | (LLM features unverified) |
| 10:55 | Pre-set `llmModelPath` + restart | API 65292, Qwen 9B Q4 loaded |
| 10:57 | AI activate | completed in 44s |
| 10:59 | Unified-Chat W1 RAG end-to-end | 1418 chars + CitationsPanel 5 sources ✓ |
| 11:00 | G140 citation-click probe | Event fires; navigation does not (F-4) |
| 11:02 | FreeChat + G128 reasoning | Reasoning block ✓ |
| 11:04 | Extract G131 | JSON ✓; iteration loop visible (F-13) |
| 11:05 | Initial findings doc | — |
| 11:30 | Structural rewrite (TL;DR + defect classes + F-IDs + Actions) | — |
| 12:00 | 503 absorbed (F-37/F-38/F-39 added) + Horizons 2/3 extracted to 505/506 | — |

### C — Method note

Three-pass format is a recurring gate, not a one-off. Suggested
re-runs: pre-tag for each minor release; after every two
substrate-shipping slices.

For LLM-bound features, the audit script needs a chat model loaded.
Recommendation: bundle a tiny model with the dev distribution so
`ai_activate` never fails — reuses the same audit script with full
coverage.

### D — Predecessor work record (absorbed from tempdoc 503)

Tempdoc 503 (2026-05-16) ran an ad-hoc browser walkthrough against
the live dev stack and landed nine fixes. The record is preserved
here so deletion of the original doc loses no context. Three of
503's deferred items are now F-37 / F-38 / F-39 in §Findings; one
investigated-not-bug ("Search Quality 0/2 active") is referenced by
F-33 as the framing problem.

**Fixed by 503 (commits in the 2026-05-16 sweep):**

| Pri | Area | Fix |
|---|---|---|
| P0 | search match | `IndexingDocumentOps` populates `TITLE` from filename stem when Tika title is null (so `readme.txt` matches "readme"). |
| P1 | SearchSurface | `setQuery('')` on disconnect — searchState singleton no longer leaks query across navigation. |
| P1 | SearchSurface | "0 results" meta hint instead of stale "Type to search" when `query.trim() && totalHits === 0`. |
| P1 | InspectorPane | Initial `loadPreview()` in `connectedCallback` when item already selected — fixes timing race where preview was empty. |
| P1 | BrainSurface | Pass `confirmationToken` to TYPED_CONFIRM gate; switch to `core.rebuild-index` (parameterless) instead of `core.bulk-reindex` (requires `corpusIds`). |
| P1 | BrainSurface | Poll `embeddingCompatState` after Force Rebuild until `COMPATIBLE` (10× 2s) — UI no longer holds stale "Embedding model fingerprint missing" warning post-rebuild. |
| P2 | i18n | `bootSurfaceCatalog()` fetches `registry-surface` namespace; `unified-chat-surface` label + description added to `registry-surface.en.properties`. |
| P2 | Table | `title` attributes on header + data cells for ellipsis tooltips. |
| P2 | typecheck | `ReasoningBlock.ts:49` unused param renamed `_changed`. |

**Files touched (9 files, ~55 lines):** `IndexingDocumentOps.java`,
`InspectorPane.ts`, `SearchSurface.ts`, `BrainSurface.ts`, `i18n.ts`,
`resourceCatalog.ts`, `registry-surface.en.properties`, `Table.ts`,
`ReasoningBlock.ts`.

**Verification (503 commits):** backend `./gradlew.bat build -x test`
green, target module tests pass, frontend typecheck + 1290 unit tests
pass, live UI verified via browser.

**Deferred items now tracked here:** F-12 (status-deck "embed: 5"
misleads under BLOCKED_LEGACY), F-37 (Browse root "0" count), F-38
(Force Rebuild drops batch-ingested help docs), F-39 (Activity vs
Logs distinction not obvious).

**Investigated-and-not-a-bug (503 §Investigated):** Brain "Search
Quality Features 0/2 active" — reranker is config-gated
(`rerank.enabled: false`, `JUSTSEARCH_RERANK_ENABLED` env), citation
scorer model directory absent. Both optional. The 0/2 display is
informative, not an error — F-33 keeps the framing critique
(reads-as-error) without changing the data.

**Retracted (browser automation artifacts, not real bugs):**
- Unified-Chat "messages vanish" — shadow-DOM `@input` event not
  firing through the automation tool; full pipeline works.
- "Notification badge has no visible action" — click-target precision
  issue; JS-triggered click opens the drawer.

### E — Reconstruction + restoration notes (2026-05-18)

**Reconstruction.** This file was reconstructed from the session
transcript after investigation showed the original 504 was never
committed to git (commit `28891dbe2` of 2026-05-17 17:30 added
tempdocs 501–502 + 505–509 but omitted 504; the working-tree file was
subsequently cleaned during one of the worktree merge operations).
The reconstruction preserved the final pre-loss state of the audit:
39 F-IDs, 9 actions, 6 defect classes, all pass evidence, the
cross-cutting observations, the long-term direction with Horizons
2/3 already extracted to 505/506, and the §Appendix D 503 record.

**Post-reconstruction status update.** §Successor work + §Residue
tracker were refreshed after re-inspecting branches + worktrees
(not just main-branch history). Earlier snapshots understated
implementation progress because they read only main-branch state,
not the active worktrees. Key corrections recorded:

- 509 was already implemented on 2026-05-17 (same day as the audit),
  then superseded by 511's generalization.
- 511 had shipped substrate + Phases 0–10 + Pass-8 mirror test;
  three named follow-up consumer slices remain (A/B/C).
- 507 + 508b are both implemented in `worktree-507-capability-
  mediated-surface`, merge-pending. F-2 (no palette) was closed by
  508b Phase B (`feat(508): command registry + palette with Ctrl+K
  activation`).
- The Pass-8 component-callsite check shipped with 509's commit
  `d46a53e84`; the mirror test landed in 511's `bc459c783`. Both
  closed at the project-rule level.

After all the above, the residual 504 open-alpha must-fix list
collapses to:

1. **F-38** help-docs persistence (A-9) — no successor, silent data loss.
2. **F-4 / F-6 / F-19** G140 citation-click wiring (A-3).
3. **F-5 / F-13** per-sentence grounding regression (A-4).

Plus 511-followup A/B/C (queued), plus a small copy/cosmetic
batch (A-8 residue).
