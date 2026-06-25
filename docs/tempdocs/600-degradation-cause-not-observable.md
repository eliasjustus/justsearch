---
title: "600 — A degradation cause must reach the one legibility authority (REFRAMED from 'the cause is log-only' — the investigation found most of it IS on the wire). The 593 walkthrough's Area B, re-verified against `main`: the actionable cause of degraded semantic search is NOT primarily log-only. It splits three ways — (T1) generic readiness reasonCodes reach the FE banner but name a category not a remedy; (T2, the dominant slice) the SPECIFIC cause (`embeddingCompatState=BLOCKED_LEGACY` / `embeddingCompatReason=LEGACY_INDEX_NO_FINGERPRINT` / `reindexRequiredReason`) IS on the wire and FE-validated, but rides a PARALLEL `worker.compatibility` vocabulary the ONE verdict authority never reads — the verdict instead synthesizes a generic `reindex-required` token from a BOOLEAN (`verdict.ts:164`) — so the user sees a generic 'Reindex required', not the actual cause; (T3) genuinely-absent causes, of which restart-revert + 'UI never requests dense' are ROOT CAUSES OWNED BY 598 that vanish when it lands. Plus a self-referential worst case: a health rule that CANNOT evaluate (missing samples) collapses to predicate-`false`, indistinguishable from 'healthy' (no 'cannot-evaluate' state in the DwellTimeScheduler) — the monitoring layer cannot tell it is blind. LONG-TERM DESIGN (PART III): (A) COLLAPSE the T2 fork — represent the cause ONCE as a real readiness reason code in the single channel the 595 verdict→CAUSE_ROWS authority already consumes, deleting the boolean→synthetic-token shortcut (extends 595, doesn't replace it); (B) TRI-STATE the rule predicate (`evaluated | indeterminate`) so a blind monitor is a first-class observable on the existing RuleEmitter→condition seam (the 588 silent-failure class applied to the monitor layer). Both are the 595 epistemic rule — never conflate can't-determine with a settled value — applied one layer up. (C) the mode-procedure gap is deferred to 598, contingent. POST-IMPLEMENTATION DESIGN (PART IX): the cause VOCABULARY itself must become one closed, enforced authority — investigation found it is already DRIFTING (≈7 backend-emitted readiness codes have no FE `CAUSE_ROWS` wording; the raw `Degraded: <code>` fallback IS reachable and was reproduced live — though PART X's UI inspection narrows the LIVE user exposure to `gpu.saturated` as a secondary banner cause, since the other unworded codes resolve to a neutral `checking` verdict / a non-verdict composite and are wording-exempt by construction), with zero enforcement. The design: close the readiness-degradation reason codes into one declared set (no raw string literals in the producer) and gate-check the FE `CAUSE_ROWS` to correspond (forward + backward), so 'user sees a raw code' and 'a cause is emitted that the legibility authority can't word' become unrepresentable. This is the completeness sibling the 595 `check-verdict-derivation` gate explicitly deferred, now warranted because the vocabulary has grown (~20 codes) and is drifting; it mirrors the SEARCH side's existing reason-code contract (`reason-codes.v1.json` + allow-list test) without merging the two vocabularies."
type: tempdocs
status: "C-2 IMPLEMENTED & MERGED (2026-06-17) — the reopened self-monitoring residual shipped to `main` (merge `41d87b090`): Layer A (RRD datasource reconciliation in `RrdMetricStore.initialize()` via `RrdToolkit.addDatasources`, removing permanent catalog-drift blindness), Layer B (stable blind-observable identity — `Signal` drops the moving window, `RuleEmitter` emits a plain check-named message, killing the every-5s WARN + `CONDITION_MODIFIED` churn), Layer C (plain `health-events.monitor.unobservable.{label,message}` so the row title is human, not the raw id). Tests: `RrdMetricStoreReconcileTest` (real `initialize()` on a stale RRD) + `RuleRunnerTest.blindMonitorDoesNotChurn` (4 blind ticks → 1 ADDED, 0 MODIFIED); full module suites + `build -x test` green; LIVE browser-verified (the production `jf-health-event` row renders 'Self-check unavailable — The 'memory-pressure' check can't read its data yet.', NOT the raw `monitor.unobservable`). **C-1 REMAINS DEFERRED & GATED on 598 B-3** (not 600-actionable until 598's capability source reflects the BLOCKED_LEGACY boundary; 598 is also actively re-keying `readinessNotice`, the file C-1's invariant would touch — see PART XVI). See the AS-BUILT section at the end. ── PRIOR: REOPENED + RE-INVESTIGATED + DESIGNED + USER-FACING-DESIGNED (2026-06-17, agent takeover) — the reopen's two residuals were verified against `main` (PART XII), given a long-term design (PART XIII), and a user-facing design (PART XIV, live-inspected), all of which materially RE-FRAME the reopen. USER-FACING (PART XIV): C-1's three render sites (search degradation banner / 'What you can do right now' CapabilityMap rows / AI-Brain line) already word BLOCKED_LEGACY honestly GIVEN a truthful verdict, so the design is a NEGATIVE guarantee (the 'fully semantic'/'results are complete' over-claim is unrepresentable alongside a retrieval-degrading cause) + cross-surface agreement, no new copy; C-2's blind-monitor row TODAY renders a Nielsen-#9 double violation (raw id `monitor.unobservable` as title — the i18nKey resolves in NO catalog — plus the internal metric name + churning window in the message), so the user-facing design is a plain-language display-catalog label + a STABLE non-churning row (the user-facing face of the XIII.4-B identity fix) + INFO diagnostic tier that never escalates the verdict + no remedy button. LIVE FINDING: the running `main` stack's Health AND AI-Brain surfaces were WEDGED ('Loading…' + persistent 'Reconnecting…' while backend READY = the 604 SSE-wedge), so C-2's row — which lives ONLY in the Health conditions list — is unreachable exactly during SSE incidents; C-2's value is gated on 604's surface-liveness (recorded dependency, not 600's fix). No new surface/alarm/remedy for blindness; no end-user exposure of internal identifiers. ── DESIGN RE-FRAME: (C-1) is NOT a 600 wording bug — both 600 wording paths (`readinessNotice` reindex branch + `availability` documents branch) already word BLOCKED_LEGACY accurately ('keyword-only'/'keyword-ranked') GIVEN a warn-severity degraded verdict; `embeddingCoverage` has ZERO shell-v0 consumers, so the 'fully semantic/results complete' over-claim is an UPSTREAM verdict/capability misclassification owned by 598 B-3 (confirmed NOT implemented in `main`), leaving 600 only a post-598 VERIFICATION + an optional tone-honesty invariant guard, no wording rewrite; (C-2) Design B IS emitting `monitor.unobservable` (UNKNOWN) when blind — it does NOT silently collapse (live oracle `conditions:[]` = not-blind-on-that-run) — and the observed 'every 5s' symptom is a NEW CHURN defect Design B introduced (the missing-metric reason embeds a moving `[start,now]` window that defeats BOTH CelEvaluator's WARN-once dedup AND ConditionStore's `(status,reason,message)` dedup), distinct from the unfixed ROOT (RRD catalog-drift: `RrdMetricStore.initialize()` opens a stale `metrics.rrd` without reconciling datasources to the catalog-derived curated set → silent DEBUG-only skip → permanent partial blindness). LONG-TERM DESIGN (PART XIII): C-2 is ONE coherent structure — (A) reconcile the on-disk RRD schema to the declared curated set on open via RRD4J `RrdToolkit.addDatasource` (root; removes permanent blindness; the store already derives the declared set — a one-step loop-close, code in `modules/telemetry`, 600 owns the requirement); (B) key the blind-observable identity on the STABLE FACT ('metric X has no samples'), not the momentary window (kills both churn sites at the single `Signal` source, restoring the dedup contracts); (C) KEEP Design B as the demoted TRANSIENT backstop (cold-start gaps reconciliation can't prevent). C-1's design = the 598-R1 thesis honored at the source (one capability authority: the Worker embedding-compat boundary, not `embeddingCoverage`) + a 600-owned tone-honesty INVARIANT (a positive-capability assertion is unrepresentable whenever the verdict carries a retrieval-degrading cause). No new authority/register/gate — collapses + reconciliations of existing representations; tests are the teeth. Boundaries drawn vs 588 (closed, worker-loop), 604 (SSE liveness, scopes 600 out), 598 (source). Sequencing: C-2 (A+B+C) independent of 598, can land now; C-1 gated on 598 B-3. ── PRIOR REOPEN NOTE: two residuals in 600's own scope survived the merge: (Still-Present #9) the CelEvaluator memory-pressure rule STILL silently cannot fire live (UI Logs every 5s: 'rule memory-pressure predicate missing metric head.jvm.memory.heap.used_bytes has no recorded samples') — Design B tri-stated the predicate but the live RRD catalog-drift / stale-on-disk condition that produces the missing samples is unfixed, so the blind monitor is real on `main`; (NEW #1, copy half) the new 'What you can do right now' / degraded banner now OVER-CLAIMS 'results are complete / fully semantic' in the BLOCKED_LEGACY state because it consumes 598's lying capability (`embeddingCoverage=0.9755`, which ignores the Worker BLOCKED_LEGACY gate) — calm-but-inaccurate degraded copy. 598 owns the capability SOURCE; 600 owns this CONSUMED WORDING. Reopen scope in §REOPENED below (prerequisite: 598's source fix). ── PRIOR: IMPLEMENTED & MERGED (Designs A+B) 2026-06-17 — shipped to `main` in the 600 merge. Design A: the embedding/schema compat cause is now a real `retrieval`-composite reason code (`index.blocked_legacy`/`index.schema_mismatch`/`index.embedding_legacy`/`index.embedding_mismatch`) consumed by the ONE 595 verdict→CAUSE_ROWS authority (the `reindexRequired` boolean shortcut + `ReadinessView` field removed); the search banner + Health verdict now name the specific cause + the rebuild remedy. Design B: the rule predicate is tri-state (`PredicateOutcome = Evaluated | Indeterminate`); a blind monitor emits a `monitor.unobservable` `AssertedCondition(status=UNKNOWN)` via the existing RuleEmitter→ConditionStore seam, the DwellTimeScheduler freezes on indeterminate (no false RESOLVE), and it renders calm/INFO without alarming the verdict. Test-covered (incl. an end-to-end `StatusLifecycleHandlerTest` composite-rollup test closing the post-impl audit-driven-fixes-need-test gap) + Design A live-verified in Chrome. C (mode-procedure legibility) stays deferred to 598. PART IX/X (vocabulary closure) ALSO IMPLEMENTED & MERGED 2026-06-17: the ~11 raw-string readiness codes are now sourced from the closed `LifecycleReasonCode` enum (no raw string literals remain in `StatusLifecycleHandler`), the one live user-facing gap `gpu.saturated` is worded calmly ('The GPU is busy; results may be slower'), and `scripts/ci/check-readiness-reason-codes.mjs` (register `governance/readiness-reason-codes.v1.json`) enforces forward (every emittable non-exempt code is worded) + backward (no dead `CAUSE_ROWS` rows) correspondence — wired in CI + a 7-assertion test; browser-verified the banner shows the calm GPU wording, not a raw `Degraded:` code. The human-curated exempt allow-list is the one residual trust point (§XI.2). Full design/verification history (PARTS II–XI) below. | open — §6 INVESTIGATION DONE (2026-06-17, agent takeover), problem RE-FRAMED. The §1-6 problem statement below is preserved verbatim (593-dated). PART II records the autonomous investigation that verified every claim against `main` (static source reads + 3 parallel source-cited audits + direct re-verification of the two load-bearing reframes). HEADLINE CORRECTION: the §1 one-sentence claim ('the actionable cause exists ONLY in worker logs, not on the wire') is PARTLY FALSE on `main` — the actual shape is a THREE-tier split: (T1) the generic readiness `reasonCodes` reach the FE banner but name a category not a remedy [confirms #4.2]; (T2) the *specific actionable category* — `embeddingCompatState=BLOCKED_LEGACY` / `embeddingCompatReason=LEGACY_INDEX_NO_FINGERPRINT` / `reindexRequiredReason` — IS on the wire (`/api/status` `worker.compatibility`+`embedding`), validated at the FE parse boundary, but DROPPED by the user-facing projection (`aiStateStore.ts:398` reads only the `reindexRequired` boolean; only the deep Brain diagnostic at `BrainSurface.ts:1479` consumes it) ⇒ a PRESENTATION/projection gap, not a data gap, directly contradicting §3's 'a presentation fix cannot close it'; (T3) genuinely-absent data — the mode-requirement (b), restart-revert (c), and 'UI never requests dense' (d) — where (c)/(d) are ROOT CAUSES OWNED BY 598 (R3 durability / R1 reachability) that DISAPPEAR when 598 lands, leaving only (b) mode-procedure legibility as 600's own genuine data gap. SECOND CORRECTION: the CelEvaluator memory-pressure rule's predicate metric producer EXISTS and is wired end-to-end on `main` (`JvmMetricCatalog` Phase 8); the 'no recorded samples' (#4.4) is a SILENT RRD catalog-drift / stale-on-disk runtime condition (logged DEBUG-only + WARN-once), not an absent producer — which STRENGTHENS the self-monitoring-blindness point. NET: 600's genuinely-own residual after verification is narrow — the T2 projection drop (overlaps 595), the (b) mode-procedure data gap (contingent on 598's rebuild redesign), and the self-monitoring blindness (#4.4, adjacent to 588). Scope-decision recommendation for the user in §13. LONG-TERM DESIGN ROUND DONE (2026-06-17, PART III) after reading the implemented 595 verdict→CAUSE_ROWS chain (`verdict.ts`/`readinessNotice.ts`), the readiness reason vocab (`LifecycleReasonCode`), and the rule engine (`RuleRunner`/`DwellTimeScheduler`/`CelEvaluator`/`RuleEmitter`). The design is two SCOPED EXTENSIONS of existing authorities (not new frameworks): (A) collapse the T2 cause-fork into the single readiness reason channel the 595 verdict already consumes; (B) tri-state the rule predicate so a blind monitor emits through the existing condition seam. (C) defers the mode-procedure gap to 598. No new register/service; AHA-composed not fused. USER-FACING DESIGN ROUND DONE (2026-06-17, PART IV) after live browser inspection of the Health surface + search surface in demo mode (screenshots) and reading the degradation-banner contract (`SearchSurface.degradation.test.ts`) + the Health verdict rendering (`HealthSurface.ts`). Key live finding: the cause-naming machinery ALREADY EXISTS and works — the search banner's `degradation-causes` slot words real reason codes (e.g. `worker.health.embedding_not_ready` → 'The semantic embedding index is not ready') with a remedy button — but the BLOCKED_LEGACY/reindex path feeds it a BOOLEAN→synthetic token, so the slot is EMPTY and the user sees a generic 'Reindex required'; on Health the footer says 'Retrieval is degraded. See recent events for detail.' (also generic). So Design A needs NO new UI component — it feeds the existing causes-slot + remedy + the Health footer wording the real cause, across all three placements (search banner / Health footer / Health affordance rows) which already read the ONE CAUSE_ROWS vocabulary. One cross-tempdoc UX constraint surfaced: the 'Rebuild index' remedy's honesty depends on 598 R3 (a one-click rebuild that silently reverts on restart is worse than none). Design B's BLIND condition is info-tier in Health's conditions list, MUST NOT escalate the verdict or hit the search banner. The user authorized questioning assumptions + suggesting alternative designs on takeover. CONFIDENCE-BUILDING DONE (PART V, 2026-06-17): static audits + read-only live probe retired the structural risks and refined two design points (B reuses the existing `AssertedCondition status:UNKNOWN` — NO new ThresholdPhase/wire change; A's scope is exactly BLOCKED_LEGACY+BLOCKED_MISMATCH, with REBUILDING→595 / UNAVAILABLE→optional-AI excluded). Overall remaining-work confidence 8/10. CROSS-TEMPDOC INTERFERENCE ANALYSIS DONE (PART VI): Design B is conflict-free (rule engine untouched by any active work); Design A's FE files are merged-stable (595/596 landed, no active worktree edits them) — A is a clean extension; the one genuine coupling is 598 (active worktree) — A's remedy is sequencing-gated on 598 R3, plus a minor HealthSurface.ts merge window; 599 active worktree = no interference. Recommended order: let 598's Health-view cleanup merge, build B independently, then A on the settled surface with the honest-interim remedy until R3."
created: 2026-06-17
author: agent
category: engine / observability / diagnosability / readiness / degradation / reason-codes / operability / product-shape
related:
  - tempdoc 593 (the UX walkthrough — §E BLOCKED_LEGACY / generic 'Reindex required', §I the FE 'hard walls' (the cause is log-only), ADDENDUM 3 baseline (degraded cause buried below green cards; the CelEvaluator memory-pressure rule silently can't fire), and its own Coverage §C 'Area B — degradation diagnosability', named there as a top un-homed priority). THE SOURCE.
  - tempdoc 598 (semantic search operationally unreachable — Area A). THE TWIN: 598 is WHY the capability is unreachable (and its reframe found the dominant cause: the UI never requests dense); 600 is that the cause is NOT a surfaced observable, so the product/user is blind to it. 598 = reachability; 600 = diagnosability. Together: can't reach it AND can't learn why. OPEN.
  - tempdoc 595 (observed-state authority during transitions). BOUNDARY, NOT THIS: 595 renders the verdict + a CLOSED generic reason-code vocab truthfully — but the walkthrough's residual is that the *actionable, specific* cause is invisible (log-only) and what is shown is buried. 595 can only project what is on the wire; 600 is that the actionable cause is NOT on the wire. A presentation fix cannot surface absent data.
  - tempdoc 596 (operability — unavailable affordance's reason). ADJACENT, NOT THIS: 596 delivers why a CONTROL is unavailable; 600 is why a system CAPABILITY is degraded + what to do about it (a different altitude). They may share a reason-surface seam — a boundary to draw, not assumed.
  - tempdoc 588 (worker indexing-engine silent-failure robustness — IMPLEMENTED) / 575 (observed-happening register) / 549·553 (SearchTrace). ADJACENT: 588 keeps the loop from dying silently; 575 is the observed-happening DATA tier (a plausible HOME for a first-class degradation-cause signal); 549/553's per-query trace carries engineer-grade reason codes, NOT a capability-level 'why is X degraded + remedy' observable. Boundaries to draw.
  - CLAUDE.md `verify-dont-guess` / `audit-driven-fixes-need-test` (593 is point-in-time; confirm which causes are log-only vs already on a wire before trusting — §6 Q1).
---

# 600 — The cause of a degraded capability is not a first-class observable

> **What this document is.** A *problem statement* — the 593 walkthrough's **Area B**, which the
> walkthrough's own Coverage section names (alongside Area A = 598) as the next un-homed priority and
> explicitly contrasts with "a fifth presentation-authority sibling." **It deliberately proposes
> nothing yet** (per request): it frames the problem, assembles 593's evidence, draws the boundaries
> against the presentation/reachability/silent-failure siblings, and lists what must be verified first.

## 1. The problem, in one sentence

> When a core capability is degraded (semantic search off), the **actionable cause** — *what is wrong
> and what to do about it* — exists only in **worker logs / source**, not as a wire-exposed, FE-readable
> observable; so the product (and a FE-only user or operator) is **blind to WHY its core feature is
> broken**, and no presentation-layer fix can surface a cause that isn't on the wire.

## 2. The observed reality (593)

- The FE's degradation message is **generic and non-actionable**: a "Reindex required" banner that
  never says *why* (no fingerprint), *what mode* (must be Indexing), or *that a restart will revert the
  rebuild* (593 §E/§I).
- The **precise, actionable cause was log-only**: `BLOCKED_LEGACY (index has no embedding
  fingerprint…)`, "rebuild only completes in Indexing mode", "restart reverted to BLOCKED_LEGACY", and
  (598's reframe) "the UI never requests the dense leg" — every one of these required a worker log,
  `debug_state`, `inference_status`, or a source read. A careful, fully-tooled agent needed those;
  a normal user has **none of them** (593 §I "hard walls").
- What the FE *does* show about health is **buried**: the degraded cause sits below a wall of green
  cards — an information-hierarchy failure (593 ADDENDUM 3, N7).
- A health rule meant to catch trouble **silently cannot even evaluate**: the `CelEvaluator`
  memory-pressure rule's predicate metric (`head.jvm.memory.heap.used_bytes`) has **no recorded
  samples**, so the rule never fires — a monitoring check that is itself undiagnosable (593 ADD3
  baseline).

## 3. Why this matters

- **It is the diagnosability complement to 598 (reachability).** 598 explains why semantic search is
  unreachable and (in its reframe) found the dominant cause. 600 is that **the user/product can't
  *learn* that cause** — it isn't surfaced. The pair is the full trap: *can't reach it, and can't find
  out why.* A user is left to conclude the product is just broken or "not very smart."
- **A presentation fix cannot close it.** 595/596 can only project signals that exist on the wire; the
  actionable cause does **not** exist there (it's log-only). So this is a **data/observability** gap —
  upstream of presentation — which is exactly why the walkthrough says the next doc is *not* another
  presentation-authority sibling.
- **It is self-referential at the worst point.** When the system's own health rule can't evaluate (the
  `CelEvaluator` case), the product cannot even tell that one of its diagnostics is blind — the
  diagnosability gap extends to the monitoring layer itself.

## 4. The findings, named (observations — not diagnoses-for-fixing)

1. **Actionable degradation cause is log-only** (§E/§I): the specific, remediable reason a core
   capability is degraded is not a wire-exposed observable — the FE shows a generic "Reindex required".
2. **Generic reason ≠ actionable cause** (§E): even where a closed reason-code vocab exists (595), it
   names a *category*, not the *what-to-do* (mode, fingerprint, restart-revert).
3. **Information hierarchy buries the cause** (ADD3 N7): the degraded cause sits below a wall of green
   cards rather than at the top where a degraded system should foreground its blocker.
4. **A health rule silently cannot fire** (ADD3 baseline): `CelEvaluator` memory-pressure has no
   samples for its predicate metric — the monitoring layer is itself undiagnosable.

## 5. Scope boundary (what this is — and is NOT)

**IN (one root — the actionable cause of a degraded capability is not a first-class, FE-readable
observable):** the log-only cause (#4.1), the generic-vs-actionable gap (#4.2), the hierarchy that
buries it (#4.3), and the self-monitoring rule that can't evaluate (#4.4).

**OUT (other roots — do NOT bundle):**
- **WHY the capability is unreachable / the operational catch-22** — **598** (Area A). 600 consumes
  598's cause and asks "why isn't it surfaced," not "what is it."
- **How a (truthful) transition is rendered / the split verdict / the closed reason-code vocab** —
  **595**. 600 is that the *actionable* cause isn't on the wire for 595 to render.
- **Why a CONTROL is unavailable** — **596** (a different altitude: affordance, not capability).
- **The indexing loop dying silently** — **588** (loop survivability; the `CelEvaluator` member may
  intersect 588's silent-failure class — a boundary to draw).
- **The folder-facing status / search-result legibility / RAG answer trust** — **599 / 577·570 / (Area
  C)** respectively. Per the walkthrough's steer, 600 is deliberately NOT a presentation sibling.

## 6. Open questions — what must be verified BEFORE any design (no proposals here)

1. **Which degradation causes are log-only on `main` today, and which are already on a wire?** Enumerate
   the actionable causes (BLOCKED_LEGACY, mode requirement, restart-revert, "UI never requests dense")
   and check whether the readiness `reasonCodes` (595) already carry an actionable form or only a
   generic category. (`verify-dont-guess`.)
2. **What is the right altitude for a "why is this capability degraded + remedy" observable** — a
   capability-level signal, a per-component one, or an extension of the existing readiness/`reasonCodes`
   authority? (A boundary with 595/575, not a proposal.)
3. **Is the `CelEvaluator` can't-fire case this doc's, or 588's silent-failure class?** Confirm the
   missing-samples mechanism and where the predicate metric should be produced.
4. **Is the information-hierarchy point (#4.3) a data gap or a 595-presentation gap?** Does surfacing
   the cause at the top require new data, or only re-ordering what 595 already has?

> **No fixes proposed (by request).** The next step, when the user chooses, is the §6 discovery;
> design comes only after the problem is confirmed and scoped.

---

# PART II — §6 INVESTIGATION (2026-06-17, agent takeover)

> **What changed.** The §1-6 problem statement above is 593-dated and is preserved verbatim. This
> Part II is the discovery §6 asked for: re-verify every claim against `main`, answer each open
> question, and re-draw the boundaries — *before* any design. It substantially **re-frames** the
> problem. The §1 headline ("the actionable cause is log-only, not on the wire") is **partly a
> misdiagnosis**: most of the actionable cause *is* on the wire and is dropped by the FE projection.
> Method, evidence (file:line), the two corrections, and the answer to each §6 question follow. The
> user authorized questioning assumptions and sketching alternatives on takeover, so §13 records a
> scope recommendation (clearly delineated from the verified facts).

## 7. Method

Three tiers, every load-bearing claim cited and the two reframes re-verified by direct read (not
trusted from the subagent):

1. **Three parallel source-cited subagent audits** (subagents do not inherit CLAUDE.md — each briefed
   inline): (A) the readiness reason-code vocab + `BLOCKED_LEGACY` wire-exposure, (B) the
   `CelEvaluator` memory-pressure rule + its predicate-metric producer, (C) the full diagnostic wire
   surface (`/api/status`, `/api/health`, `/api/debug/state`, search-trace).
2. **Direct re-verification of the two reframes** (the corrections contradict the doc, so per
   `audit-driven-fixes-need-test` / critical-analysis I read the code myself): the FE readiness
   projection drop (`aiStateStore.ts:398`, `readinessNotice.ts:182-195`), the wire-presence of the
   compat fields (`status-response.ts:206,219-220,227,500,513-514,521`; `status_pb.d.ts:629,634,669`),
   the sole consumer (`BrainSurface.ts:1479`), and the CelEvaluator no-samples path + producer wiring
   (`CelEvaluator.java:194-204`, `memory-pressure.yaml:13-15,24-26`).
3. **Cross-read of the twin (598 PART II/III/IV) and the presentation sibling (595)** to draw the
   ownership boundary precisely — because the investigation shows much of 600's stated problem is
   already owned by those two.

Recency: the cited FE files (`aiStateStore.ts`, `readinessNotice.ts`, `verdict.ts`) are the 595-era
projection on `main` today; the compat-wire fields and `JvmMetricCatalog` Phase-8 archival predate 593.
So "is it still true on `main`" is answered against code as of today.

## 8. The decisive correction — the actionable cause is a THREE-TIER split, not "log-only"

§1 asserts the actionable cause "exists only in worker logs / source, not as a wire-exposed,
FE-readable observable." Verified against `main`, that is **only true for one of the four causes**. The
real shape is three tiers:

| Tier | What | The four §2 causes | Evidence |
|---|---|---|---|
| **T1 — on the wire AND read, but generic** | The readiness `reasonCodes` (closed `LifecycleReasonCode` vocab) reach the FE verdict → banner, but name a *category* (`worker.health.embedding_not_ready`), never the *what-to-do*. | The generic "Reindex required" path. | `LifecycleReasonCode.java:16-52`; `ReadinessCompositeView.reasonCodes`; `aiStateStore.ts:391-399`; banner `readinessNotice.ts:182-195`. **Confirms finding #4.2.** |
| **T2 — on the wire but DROPPED by the FE projection** | The *specific actionable category* — `embeddingCompatState=BLOCKED_LEGACY`, `embeddingCompatReason=LEGACY_INDEX_NO_FINGERPRINT`, `reindexRequiredReason`, the raw fingerprints — travels on `/api/status` under `worker.compatibility`+`embedding`, is in the generated FE types, and is **validated at the parse boundary**. The user-facing readiness projection reads **only the `reindexRequired` boolean**; the lone consumer of the reason is a deep Brain diagnostic. | **(a) BLOCKED_LEGACY / no-fingerprint.** | Wire: `CompatibilityStatusView.java:4-12`, `IndexStatusOps.buildCompatibility() :519-531`; FE types `status-response.ts:206,219-220,227` + Zod `:500,513-514,521`, `status_pb.d.ts:629,634,669`. **Drop:** `aiStateStore.ts:398` (`status.schema?.reindexRequired ?? false` — boolean only), `verdict.ts:164`, hardcoded body `readinessNotice.ts:190-194`. **Sole consumer:** `BrainSurface.ts:1479`. `embeddingCompatReason`/`embeddingCompatState` have **zero** shell-v0 consumers. |
| **T3 — genuinely not represented anywhere** | No record/field carries it; emergent from code. | **(b) "rebuild only completes in Indexing mode"**, **(c) "a restart reverts the rebuild"**, **(d) "the UI never requests the dense leg"**. | (b) no wire field at all (mode-exclusivity lives in `InferenceLifecycleManager`/ADR-0004, never projected). (c) emergent from the deferred-fingerprint-stamp ordering bug = **598 R3** (`598 §11/§27.4`). (d) = **598 R1** (`searchState.ts:314-331` never sets dense; `598 §8`). |

**Why this demolishes the §1/§3 framing.** §3 claims "a presentation fix cannot close it … this is a
data/observability gap — upstream of presentation." That is **false for T2**, which is the dominant
slice of the actual `BLOCKED_LEGACY` experience: the data *is* on the wire and validated; the FE simply
doesn't project it into the user-facing banner. Surfacing `reindexRequiredReason`/`embeddingCompatReason`
is a **presentation/projection** change (read a field already parsed; word it), not new observability —
exactly the kind of FE-owned reason→wording table 595 §10.5 already calls for (a `readinessNotice.CAUSE_ROWS`
sibling). So a large part of 600 lands inside **595**, not upstream of it.

## 9. Answers to the §6 open questions

**Q1 — which causes are log-only vs already on a wire; do `reasonCodes` carry an actionable form?**
Answered by the §8 table. Summary: **(a)** is on the wire as a *category* (T2) — validated, FE-readable,
but dropped by the banner; it is **not** in the readiness `reasonCodes` enum (`LifecycleReasonCode` has
no embedding-compat member), so the generic banner (T1) cannot name it even though the parallel
`worker.compatibility` block can. **(b)/(c)/(d)** are genuinely absent (T3); **(c)/(d) are 598's roots**,
not 600's diagnosability gaps. The readiness `reasonCodes` carry **only a generic category**, never the
remedy/mode/restart specifics — confirming finding #4.2 verbatim. There is *also* a per-query surface
(`SearchTrace.degradation.vectorBlockedReason`) that carries the compat reason, but only on an executed
blocked search (`SearchTraceProjector.java:259`) — not a capability-level standing observable.

**Q2 — right altitude for a "why degraded + remedy" observable.** The investigation reframes the
question: for the dominant slice the observable **already exists** (T2 `worker.compatibility`), so the
altitude question collapses to "project the existing field" (595 territory). The remedy itself
(`core.rebuild-index` etc.) is **deliberately FE-owned** today — `readinessNotice.CAUSE_ROWS` maps the
closed backend reason vocab → human wording + a remedy operation id (`readinessNotice.ts:60-149,188-194`).
This is a defensible design (matches i18n catalogs and 595's tone table; keeps low-cardinality stable
codes on the wire, dynamic wording on the FE), **not self-evidently a defect** — so 600's implicit premise
"the remedy should be on the wire" is **questionable** and should be stated as a design choice, not an
assumed gap. The only genuine altitude gap is **(b)** the operational *procedure* knowledge ("to rebuild,
be in Indexing mode") which is neither a wire field nor an FE projection of one — and which may *dissolve*
under 598 R1+R3 (see §11).

**Q3 — is the CelEvaluator can't-fire case 600's or 588's? confirm the missing-samples mechanism.**
See §10 — the mechanism is **not** what #4.4 assumes.

**Q4 — is the information-hierarchy point (#4.3) a data gap or a 595-presentation gap?** **A
595-presentation gap, decisively.** The cause data that should sit at the top (T2 compat block) is
already on the wire and parsed; foregrounding it over the green cards is re-ordering + projecting
existing data, not new data. (#4.3 is therefore 595's, modulo the T3 (b) specifics, which are genuinely
absent.)

## 10. The CelEvaluator correction (#4.4) — the producer exists; the blindness is *silent RRD drift*

#4.4 states the memory-pressure rule "silently cannot fire" because its predicate metric
`head.jvm.memory.heap.used_bytes` "has no recorded samples." The first half is real; the *cause* is
mis-stated:

- **The producer exists and is wired end-to-end on `main`.** `JvmMetricCatalog` defines the metric
  `.archivedTo(RrdArchive.STANDARD)` and registers an async gauge (`safeHeapUsedBytes`); it is wired for
  the Head process at `HeadlessApp.java:310` + `CoreApiAssembly.java:343`, and the sibling `max_bytes`
  was **added to the archival annotation in tempdoc 430 Phase 8 specifically so this rule resolves** —
  the rule file itself documents this (`memory-pressure.yaml:13-15`). So "no producer" is false.
- **The no-samples → can't-fire path is real and silent.** `Signal.latest()` throws `MissingMetricException`
  on an empty window; `CelEvaluator` catches it, **returns predicate-false, and WARN-logs once per
  {rule,metric}** (`CelEvaluator.java:194-204` + the dedup set `:212`). Predicate-false forever ⇒ the
  dwell scheduler never reaches FIRING ⇒ the rule can never emit.
- **The actual cause of empty samples is RRD catalog-drift / a stale on-disk `metrics.rrd`** created by a
  prior boot with a smaller curated set — handled at **DEBUG only** in `RrdMetricStore.record()`/`query()`.
  Combined with the WARN-once, the no-samples condition is **silent at the product/wire level**: no metric,
  no HealthEvent, no condition says "a health rule is blind." (593's observation was likely against such a
  stale-RRD or pre-Phase-8 stack.)

**This strengthens, not weakens, the self-monitoring point (§3 "self-referential at the worst point").**
The monitoring layer cannot tell you it is blind — but the fix is *not* "produce the metric" (it is
produced); it is **make a non-evaluating rule a first-class observable** (and/or fix the RRD-drift
silence). **Boundary (Q3):** this is the diagnosability complement of **588** (which kept the worker
*loop* from dying silently); the CelEvaluator case is a *different* silent failure — a monitor that is
itself undiagnosable. It is small, mechanically distinct, and arguably belongs with 588's silent-failure
class rather than with 600's capability-degradation framing. It is the **one finding in 600 with no
overlap with 595 or 598.**

## 11. Boundary re-draw — what 600 actually owns after verification

Cross-referencing the twin and the presentation sibling collapses most of 600 into them:

- **vs 595 (presentation/observed-state).** §3 insisted 600 is *not* a presentation sibling. The
  investigation shows the **dominant slice (T2 + #4.3) IS presentation** — the actionable category is on
  the wire and dropped by the FE banner; surfacing it is a `readinessNotice.CAUSE_ROWS`/tone-table
  extension, which 595 §10.5 already scopes. 595 said it "can only project what is on the wire"; for T2,
  the cause **is** on the wire. So 600 §3's central premise is wrong for its biggest part.
- **vs 598 (reachability).** T3 (c) restart-revert = **598 R3**; T3 (d) UI-never-requests-dense = **598
  R1**. These are root causes that **cease to exist** when 598 lands (R1 makes dense the default → "UI
  never asks" is moot; R3's blue/green durability → "restart reverts" is moot). 600 *consumes* 598's
  causes; once fixed, they are not residual diagnosability gaps. T3 (b) mode-requirement is also
  contingent: if 598 R3 reuses blue/green so a rebuild survives restart and R1 makes dense default, the
  "switch to Indexing mode to rebuild" procedure may no longer be a user-facing precondition at all.
- **What is genuinely 600's own, after the dust settles:**
  1. **The T2 projection drop** — narrow, and arguably 595's (an extension of the reason→wording table to
     read `embeddingCompatReason`/`reindexRequiredReason` instead of only the boolean).
  2. **The (b) operational-procedure data gap** — *contingent* on 598's rebuild redesign; may dissolve.
  3. **The self-monitoring blindness (#4.4)** — genuinely standalone, but mechanically a 588-class
     silent-failure (a rule that can't evaluate is invisible), with the producer already present.

## 12. Critical assessment (questioning the doc's assumptions, as authorized)

- **The "log-only / upstream-of-presentation" thesis is the doc's load-bearing assumption and it does not
  survive verification** for the dominant case. The honest restatement: *the degradation-cause data that
  EXISTS is not projected to the user (presentation), and the data that does NOT exist is either a 598 root
  cause that disappears when fixed, or a narrow operational-procedure gap.* That is a materially smaller and
  differently-shaped problem than §1.
- **"The remedy should be a wire observable" is an unstated assumption that is probably wrong.** Keeping
  stable low-cardinality reason codes on the wire and the remedy wording/operation mapping on the FE
  (`CAUSE_ROWS`) is the *existing, deliberate* design (and matches 595's tone table + i18n catalogs).
  Pushing remedy text onto the wire would fight that authority split. 600 should not assume the gap is
  "remedy isn't on the wire"; the gap is "the FE doesn't read the *cause* that is."
- **#4.4's mechanism claim is factually off** (producer exists; blindness is silent RRD-drift). The finding
  is still valid and arguably the most interesting, but its fix is "observe non-evaluating rules," not
  "produce the metric."
- **Net scope verdict:** 600 as written ("a new data/observability gap upstream of presentation") is
  **largely subsumed** — T2/#4.3 → 595, T3(c)(d) → 598, leaving a narrow genuine residual. This is the
  same kind of takeover correction 598 made to its own §1.

## 13. Scope recommendation for the user (no code; decision input)

Given the above, the strongest options for what 600 *becomes*:

1. **Fold T2 + #4.3 into 595** as a concrete extension (the readiness projection reads
   `embeddingCompatReason`/`reindexRequiredReason`/`embeddingCompatState`, and the
   `readinessNotice` cause-table words them as the actionable reason at the top of Health). This is where
   the dominant, real, *fixable-today* slice lives, and it needs no new wire data. **(Recommended as the
   primary disposition.)**
2. **Let T3 (c)/(d) ride with 598** (R3/R1) — they are 598's roots; 600 only needs to note that once 598
   lands, the FE should *display* the now-default-dense reality. No separate 600 work.
3. **Keep 600 scoped to the one genuinely-own residual: self-monitoring blindness (#4.4)** — re-cast as
   "a health rule that cannot evaluate must be a first-class observable" and decide its 588 boundary.
   Optionally include the T3 (b) operational-procedure gap *only if* 598's rebuild redesign leaves it
   user-visible.

A reasonable close is: **600 is reframed (not a standalone data gap); its work is dispatched to 595 (T2)
and 598 (T3 c/d), with #4.4 as the sole residual** — to be decided by the user. Per `tempdoc-is-your-contract`
I have not unilaterally closed or re-scoped anything; §13 is a recommendation, and the §1-6 problem
statement plus the four findings stand as the contract until the user rules on disposition.

> **Verification honesty.** All §8/§10 corrections were read directly against `main` (not taken from the
> subagents). Two claims rest on a single live observation in 593 that I did **not** re-run live (no owned
> dev stack): the *current* readiness verdict's exact wording, and whether the stale-RRD condition is
> present on a fresh install today. Neither changes the structural findings (the wire fields, the FE drop,
> the producer wiring, and the silent no-samples path are all source-verified).

---

# PART III — LONG-TERM DESIGN (2026-06-17)

> Genre: design-theory (per 595/598's takeover precedent — correct long-term structure, general not
> implementation-level; feasibility/phasing deliberately set aside). Scoped to the residual the PART II
> investigation actually leaves 600 (§11–13): the **T2 cause-fork**, the **#4.4 self-monitoring
> blindness**, and a **deferred (b)** pointer. Where a usable authority already exists (it does, for
> both A and B), the design **extends** it rather than replacing it. It deliberately adds **no** new
> register, service, or framework — the problem does not require one.

## 14. Design thesis (one paragraph)

600's two genuinely-own defects are **the same epistemic error 595 already named — never conflate
"can't-determine" with a settled value — applied one layer up from where 595 applied it.** 595 stopped
the *FE* from rendering a provisional state as a settled fact. 600 is the layer beneath the verdict:
**(A)** a degradation cause the system *holds* (the embedding-compat reason — computed, on the wire,
FE-validated) never reaches the single authority that makes causes legible (595's verdict → `CAUSE_ROWS`),
because it rides a *parallel reason vocabulary* (`worker.compatibility`) the verdict doesn't read, while
the verdict synthesizes a generic token from a **boolean** (`reindexRequired`); and **(B)** a cause the
system *cannot* hold — a health rule whose predicate metric has no samples — collapses to predicate-`false`
and is **indistinguishable from "healthy"**, because the rule engine's only predicate type is `boolean` and
its state machine has no "cannot-evaluate" state. Both are "an actionable diagnostic signal dies before it
reaches the authority that would make it legible." The correct long-term structure makes the signal
**representable in, and routed to, the one authority that already owns legibility** — for A the 595 verdict's
reason channel, for B the rule engine's emit/condition seam — by *collapsing a fork* (A) and *widening a
type* (B). Neither needs new machinery; both reuse an authority that is already built and live.

## 15. What already exists that the design extends (inventory)

The expensive substrate is already in `main`; the design is wiring/typing, not new authorities.

- **The single system-health verdict + cause-wording table is BUILT (595, since merged).** `verdict.ts`
  derives the ONE `SystemHealthVerdict{kind,severity,reasons}` (guarded to a single seam by the
  `check-verdict-derivation` gate); `readinessNotice.ts` `CAUSE_ROWS` is the closed *reason-code →
  (wording, remedy, severity)* table the banner/notice project. **This is exactly the legibility
  authority A must reach** — A completes its *input vocabulary*, it does not build a second verdict.
- **`CAUSE_ROWS` already has the precedent shape A needs.** It already words the embedding-readiness family
  (`worker.health.embedding_not_ready`, `chunk_embedding.*`) with the `core.trigger-offline-processing` /
  `core.rebuild-index` remedies, and the severity ladder (info/warn/error, 595 §10.3) so a cause is worded
  at the right alarm. It even carries an FE-derived precedent (`no_documents`, 596 §17) "so the affordance
  projection speaks ONE vocabulary with the banner." A adds rows of the *same shape*.
- **The cause authority already exists and already emits the reason.** The Worker's
  `EmbeddingCompatibilityController` owns the cause (`LEGACY_INDEX_NO_FINGERPRINT`, `BLOCKED_MISMATCH`, …)
  and `IndexStatusOps.buildCompatibility()` already puts it on `/api/status` (`worker.compatibility`). A
  re-routes that *existing* reason into the readiness composite the verdict reads — no new computation.
- **The readiness-reason emission point exists.** `StatusLifecycleHandler.computeComponent()` already
  emits the `retrieval` composite's `reasonCodes` for `INDEX_SERVING`/`CHUNK_EMBEDDING` — the dimension the
  compat cause actually explains. A adds the compat code there; the verdict's `r.reasonCodes` path
  (`verdict.ts:168`) then carries it with zero FE plumbing.
- **The rule engine's transition→observable seam is BUILT (430).** `RuleEmitter` turns a `DwellTimeScheduler`
  transition into a `HealthEvent` → `ConditionStore.upsert` → `HealthEventChangeRegistry.broadcast`
  (`CONDITION_ADDED/MODIFIED/REMOVED`) on the conditions stream the Health view already renders. **B emits a
  blind-monitor condition through this same seam** — no new wire surface.
- **The producer B's symptom needs is already wired (PART II §10).** `head.jvm.memory.heap.used_bytes` is
  produced and archived; B needs only the *representable-indeterminate* fix, not metric-production work.

## 16. Design A — collapse the T2 cause-fork into the single reason channel (rung-1 Collapse)

**Root design error.** "Dense retrieval is degraded, and why" has **two representations on two wire paths**:
(1) the *actionable* reason on the `worker.compatibility` block, which the verdict never reads; and (2) a
coarse `reindexRequired` boolean, from which `verdict.ts:164` synthesizes a generic `reindex-required`
token. The verdict's single reason channel (`readiness…reasonCodes`, the vocabulary `CAUSE_ROWS` words)
**omits the embedding-compat cause entirely** — `LifecycleReasonCode` has no such member, and the
`retrieval` composite emits lifecycle-flavored codes, not the compat reason. So the one authority that
makes causes legible is structurally blind to the most important degradation cause, *even though the cause
is computed and on the wire.* That is the real T2 defect — a **fork**, not a missing observable.

**The design.** Represent the cause **once**, in the channel the verdict already consumes:

1. **Promote the compat reason to a readiness reason code.** Add a small stable family to the readiness
   reason vocabulary (e.g. `index.blocked_legacy`, `index.embedding_mismatch`) and have
   `StatusLifecycleHandler.computeComponent()` emit it on the `retrieval` composite's `reasonCodes` —
   derived from the *existing* `EmbeddingCompatibilityController` state the Worker already reports. The
   cause now lives in the one channel the verdict reads, on the dimension it explains. (Whether the same
   code is also mirrored into `LifecycleReasonCode` for `/api/health`'s `LifecycleSnapshotV1` is a
   secondary consistency choice — the verdict path only needs the readiness composite.)
2. **Delete the boolean→synthetic-token shortcut.** `verdict.ts:164-165` stops minting `reindex-required`
   from `r.reindexRequired`; the cause arrives as a real reason code through `r.reasonCodes` like every
   other degradation. The `reindexRequired` boolean may remain a coarse derived convenience, but it is no
   longer the verdict's *cause source* — the fork is collapsed (557 ladder rung-1).
3. **Word it in `CAUSE_ROWS`.** Add rows for the new codes with the specific wording + the existing
   `core.rebuild-index` remedy and an appropriate severity. The remedy mapping **stays FE-owned** — the
   deliberate, correct authority split (stable low-cardinality codes on the wire; dynamic wording + remedy
   operation on the FE, like i18n catalogs and 595's tone table). 600 does **not** push remedy text onto the
   wire.

**What this fixes.** The Health banner/verdict now names the *actual* cause ("the index has no embedding
fingerprint — rebuild to enable semantic search") instead of a generic "Reindex required" derived from a
boolean, and foregrounds it (the cause is a verdict reason, which the header already surfaces) — closing
finding #4.1/#4.2 **and** #4.3 (the hierarchy point) at once, because a verdict reason is structurally
foregrounded, not buried. It also unifies the per-affordance projection (596 `reasonFor`) and the banner on
one vocabulary, exactly as `no_documents` already does.

**Why not the alternatives** (each rejected for a concrete reason, not taste):
- *FE reads the `worker.compatibility` block directly* (mirror the `no_documents` FE-derived row): this is
  the **short-term fix the user excluded.** It leaves the cause in a parallel vocabulary, re-derived FE-side,
  and re-forks the next compat cause. A genuine *retrieval-readiness* reason belongs in the readiness reason
  channel, not bolted on at the FE — putting it there is the very split that produced T2.
- *Push the remedy/procedure text onto the wire*: fights the existing FE-owned remedy authority
  (`CAUSE_ROWS` / `OperationClient` / i18n) and raises wire cardinality with dynamic strings the contract
  explicitly forbids (`LifecycleReasonCode` Javadoc). Rejected.

**Scope discipline.** The problem is **one** cause class outside the channel; the fix routes it in and
collapses the redundant boolean. **No** vocabulary-unification register, **no** completeness gate is built —
the existing `check-verdict-derivation` gate already confines the readiness→verdict derivation to one seam,
and a register for a ~2-entry addition would be speculative structure. *If* compat/degradation causes later
proliferate on parallel blocks, the 553-style execution-surfaces register is the escalation — named here as
the future trigger, not built now.

## 17. Design B — a monitor that cannot evaluate must be a first-class observable (tri-state the predicate)

**Root design error.** `RuleRunner.evaluateRule` reduces predicate evaluation to a `boolean`
(`RuleRunner.java:152`), and `CelEvaluator` maps a missing metric (`MissingMetricException`) to `false`
(`CelEvaluator.java:194-204`). The `DwellTimeScheduler` has only `{INACTIVE, PENDING, FIRING, KEEP_FIRING}`
— **no "cannot-evaluate" state** — and its own docstring documents the collapse ("Predicate evaluation
throws → caller treats as predicate-false", `DwellTimeScheduler.java:45-47`). So a **blind** rule (no
samples) is indistinguishable from a **healthy** rule (predicate genuinely false): the monitoring layer
reports "all clear" when it is actually blind. This is the 595 epistemic error located **in the monitoring
layer itself** — the self-referential worst case of §3.

**The design (Collapse done right — widen the predicate type, do not overload `false`).** Make predicate
evaluation a **closed tri-state** — conceptually `evaluated(boolean) | indeterminate(reason)` (natural Java
shapes: an `Optional<Boolean>` or a small `PredicateOutcome` enum) — so "I couldn't evaluate" is
*representable* and cannot masquerade as healthy:

1. `CelEvaluator` returns `indeterminate(MISSING_METRIC: <name>)` instead of `false` on
   `MissingMetricException`; the genuinely-evaluated path is unchanged.
2. The runner/scheduler treats `indeterminate` as **neither healthy nor firing** — it must not advance a
   rule toward RESOLVED-as-healthy on an indeterminate tick. A rule indeterminate across a dwell window
   becomes a distinct **`BLIND` (cannot-evaluate) condition**, emitted through the existing `RuleEmitter` →
   `ConditionStore` → `HealthEventChangeRegistry` seam as a low-severity `HealthEvent` ("monitor X cannot
   evaluate: metric Y has no samples"), riding the conditions stream the Health view already renders and
   carrying the same `i18nKey`/severity shape as any condition.

This makes the meta-observability hold **by construction**: a blind monitor produces an observable saying it
is blind, so "the product cannot even tell that one of its diagnostics is blind" (§3) becomes false — and it
is *type-enforced*, since a `boolean` predicate can no longer represent "didn't evaluate."

**Boundary (answers §6 Q3).** B is the **588 silent-failure class** (don't let a failure be silent) applied
to the *monitoring* layer rather than the indexing loop — same class, different subject. It is mechanically
**independent of the RRD-drift cause** (PART II §10): the tri-state surfaces the *symptom* (blind monitor)
regardless of which cause empties the samples (catalog drift, a pre-Phase-8 on-disk file, cold start). Making
the DEBUG-only datasource-drift in `RrdMetricStore` a louder signal is a *separate, optional* operational
fix to the *cause*; B fixes the *diagnosability* of the symptom and is the part that belongs to 600.

**Scope discipline.** The tri-state is the minimal structure the problem requires — "can't-evaluate" must be
representable, and a `boolean` cannot express it. **No** rule-engine V2, **no** per-metric health dashboard,
**no** speculative "rule SLO" surface: one closed tri-state + one condition phase, reusing the entire
emit/stream/render path that already exists.

## 18. Design C — the (b) mode-procedure gap is deferred to 598 (contingent, not owned)

The "rebuild only completes in Indexing mode / a restart reverts it" *procedure* knowledge (T3 (b)) is a
property of the rebuild architecture **598 R3 redesigns** (reuse the blue/green generation path so a rebuild
survives restart) and **598 R1** (make dense the default). 600 does not design it. If, after 598 lands, a
mode-switch precondition *remains* user-visible, it becomes one more reason code in Design A's completed
vocabulary (`index.rebuild_requires_indexing_mode` worded in `CAUSE_ROWS`) — **no separate structure.** 600
records it as a watch-item on 598's outcome, not an owned design.

## 19. How the design composes (and respects the sibling cuts)

```
WORKER cause authority (EmbeddingCompatibilityController) ── already on /api/status worker.compatibility
   │  A.1: promote to a readiness reason code on the `retrieval` composite (StatusLifecycleHandler)
   ▼
readiness.reasonCodes  ── the ONE channel the verdict consumes
   │  A.2: verdict reads it via r.reasonCodes; DELETE the reindexRequired-boolean shortcut (verdict.ts:164)
   ▼
SystemHealthVerdict (595 — BUILT)  →  CAUSE_ROWS wording + remedy (595 — BUILT; A.3 adds rows)
   →  Health header/footer/notice foreground the ACTUAL cause   (closes #4.1/#4.2/#4.3)

RULE predicate (CelEvaluator)  ── B: boolean → evaluated|indeterminate(reason)
   │  indeterminate ≠ healthy; dwell → BLIND condition
   ▼
RuleEmitter → ConditionStore → HealthEventChangeRegistry  (430 — BUILT)
   →  conditions stream the Health view renders             (closes #4.4)
```

- **595 cut:** A *extends* 595's input vocabulary upstream (a backend reason code) and *deletes* the boolean
  fork; it adds **no** second verdict and **no** parallel reason path. Clean division of labour: **595 is FE
  presentation — it projects what is on the wire; 600-A is the upstream contract that puts the cause on the
  wire *in the channel the projection reads*.** (This corrects 600 §3's "a presentation fix cannot close it":
  the fix is *part* presentation-already-built [595] and *part* a backend reason-vocabulary completion
  [600-A] — together, not "upstream of presentation.")
- **598 cut:** owns C and the underlying R1/R3; 600 consumes their outcome, duplicates nothing.
- **588 cut:** B is 588's silent-failure principle applied to the monitor layer — same class, different
  subject; reuses the condition seam, invents no parallel observability.
- **575/553 cut:** no new register; the existing `check-verdict-derivation` gate guards A's single-derivation
  locus. **AHA / "compose, do not fuse":** A and B share the *principle* (never conflate
  can't-determine/absent with a settled value) but not a *reason to change* (FE reason vocabulary vs backend
  rule engine), so they remain **two scoped extensions, not one fused abstraction.**

## 20. Scope discipline — what the design deliberately does NOT add

- **No new "degradation-cause" observability service or capability-level signal.** A reuses the readiness
  reason channel + the 595 verdict; B reuses the rule-engine condition seam. The §6-Q2 "what altitude for a
  new observable" question is answered "none — route the cause into the authority that exists."
- **No remedy/procedure text on the wire.** The FE-owned `CAUSE_ROWS`/`OperationClient`/i18n split is the
  correct authority and is kept; 600's premise that "the remedy should be a wire observable" is explicitly
  rejected (PART II §12).
- **No vocabulary-unification register or completeness gate** for a ~2-entry cause addition (A). The 553-style
  register is named as the *future* escalation if causes proliferate, not built now.
- **No rule-engine generalization** (V1.5 metric-AST extraction, per-rule SLOs, a rules dashboard). B is one
  tri-state + one condition phase.
- **No new structure for T3 (c)/(d)** — they are 598's roots and disappear when it lands; 600 only notes the
  FE should *display* the post-598 reality.

## 21. Open decisions for the user

1. **Tempdoc ownership.** Adopt 600 as the owner of **A (T2 reason-channel collapse)** + **B (monitor-liveness
   tri-state)** as its two deliverables, with **C** tracked as a 598 watch-item? (Recommended — A+B = "the cause
   the system holds becomes legible, and the cause it can't hold stops masquerading as healthy.") The
   alternative from PART II §13 (fold A wholesale into 595) is viable but splits A's backend half awkwardly —
   A's core move is a *backend reason-vocabulary completion* that 595 (FE presentation) does not own.
2. **A's reason-code home.** Confirm: emit the compat cause as a readiness-composite reason code (verdict path
   only) — recommended minimal — vs also mirroring into `LifecycleReasonCode` for `/api/health` parity. (Recommend
   composite-only first; mirror only if `/api/health` consumers need it.)
3. **B's indeterminate carrier.** Confirm preference for `Optional<Boolean>` vs a first-class `PredicateOutcome`
   enum (recommend the enum — it names the `indeterminate` *reason*, which the BLIND condition surfaces).
4. **B's RRD-drift cause.** In or out of 600? (Recommend OUT — B fixes the symptom's diagnosability; the
   DEBUG-only drift silence in `RrdMetricStore` is a separate operational fix, optionally a one-line log-level
   bump, not core to 600.)

> **Verification honesty.** PART III rests on source-verified facts (the verdict/CAUSE_ROWS chain, the reason
> vocabulary, the rule-engine boolean collapse, the emitter seam — all read directly against `main`). The
> design is general per request; the one item needing a live check before *implementation* (not before this
> design) is A's end-to-end smoke — that a promoted `index.blocked_legacy` reason code flows compat-state →
> readiness composite → verdict → worded notice on a running stack (`audit-driven-fixes-need-test`).

---

# PART IV — USER-FACING DESIGN (2026-06-17)

> Method: live browser inspection of the Health surface + search surface in demo mode (`?demo=true`,
> screenshots), plus reading the degradation-banner *contract* (`SearchSurface.degradation.test.ts`)
> and the Health verdict rendering (`HealthSurface.ts`) — not judging from the tempdoc. The design is
> kept general (placement/tone/wording rules), not pixel-level.

## 22. Which parts of the design are user-visible

- **Design A — directly user-visible on TWO surfaces.** (1) The **search-surface degradation banner**
  (`readinessNotice` → headline · body · `degradation-causes` slot · remedy button). (2) The **Health
  surface** — the header verdict pill, the footer body, the "What you can do right now" affordance rows,
  and the conditions list. The verdict pill also mirrors into the bottom **status-bar deck** on every
  surface.
- **Design B — directly user-visible, narrow.** A "monitor cannot evaluate" (`BLIND`) condition would
  appear in the Health conditions/events area. Whether/how to show "a diagnostic is blind" to a
  non-technical user is the UX decision §25 settles.
- **Design C — not 600's** (deferred to 598), but it imposes a *cross-tempdoc UX constraint* on A's
  remedy button (§24.4).

## 23. What the live inspection showed (don't judge from the tempdoc)

Captured against `main` in demo mode (backend down, so verdict was `unreachable`/`connecting` — but the
*structure* is what matters):

- **Health surface** renders: a header verdict pill (observed "● Backend disconnected" red; "Connecting…"
  amber — `presentVerdict` tone), capability chips ("Embeddings 768-d" active; SPLADE/Reranker/NER/GPU/
  Vectors), metric cards (Files/Size/Memory/Queue), a **"What you can do right now"** affordance block
  (each row = capability · reason · remedy, e.g. *"Ask AI about your documents — The local AI model is
  offline — Open Health"*), and a **CONNECTION** card (Retrieval state, Index state `UNKNOWN`). The
  "Search (keyword): **Available**" row is the retrieval affordance — and tellingly it shows only that
  *keyword* search works; **nothing on Health states that *semantic* search is degraded or why.**
- **Search surface** shows the degradation banner only for the `degraded` verdict (absent under
  `unreachable`). Its contract (the test) confirms the banner has a `degradation-causes` slot and a
  `degradation-remedy-op`/`-nav` button.
- **The gap, made concrete by the contract.** For a *real reason code*
  (`worker.health.embedding_not_ready`) the banner's causes slot renders *"The semantic embedding index
  is not ready"* + the mapped remedy. For the **`reindexRequired` boolean** path the codes passed to
  `wordCauses` are empty (`readinessNotice.ts:187`), so the banner shows only the generic *"Reindex
  required."* + *"…results may be keyword-only"* with an **empty causes slot** — and on Health the footer
  reads *"Retrieval is degraded. See recent events for detail."* (`verdict.ts:261`), deferring the cause
  elsewhere. **So both user surfaces already have the cause-naming machinery; the BLOCKED_LEGACY cause
  simply never reaches it** (it arrives as a boolean, PART II/III). The fix is data-routing, not new UI.

## 24. The user-facing design for A (feed the existing slots; add no new component)

Because A routes the cause as a real reason code into `verdict.reasons` (PART III §16), every existing
consumer that reads the ONE `CAUSE_ROWS` vocabulary words it automatically. The user-facing deliverables
are therefore *wording + placement + tone* decisions, not new widgets:

1. **Search banner — fill the empty causes slot.** With the cause as a reason code, the
   `degradation-causes` slot renders the specific worded cause (e.g. *"The index was built before
   embeddings were available — rebuild it to enable semantic search."*) and keeps the existing
   **"Rebuild index"** remedy button (`core.rebuild-index`). This replaces today's empty-causes generic
   banner with no structural change to the banner.
2. **Health — name the cause where the verdict is (closes #4.3 hierarchy).** The Health footer should
   *word the verdict's reasons* (reuse the same `reasonFor`/`wordCauses` projection the banner uses)
   instead of *"See recent events for detail."* — so the cause is named at the top, beside the verdict
   pill, not buried below the green metric cards or deferred to a separate events list. The header pill
   stays "Service degraded"/"Reduced capability" per severity.
3. **Wording + tone honesty (595 §10.3 ladder).** BLOCKED_LEGACY is **genuinely impairing** (semantic
   off) but **not broken** (keyword still serves) ⇒ severity `warn`, calm-but-clear tone, accurate
   *"results may be keyword-only"*. It must **not** be worded as `error` (search isn't broken) nor
   downgraded to `info` (it is not cosmetic like LambdaMART-off). The wording names *what* and *why* in
   user terms ("built before embeddings existed / model changed"), not the internal code
   (`LEGACY_INDEX_NO_FINGERPRINT`).
4. **Remedy honesty — the cross-tempdoc constraint (depends on 598 R3).** A one-click **"Rebuild index"**
   button that *silently reverts on restart* or *only completes in Indexing mode* (598 R3/§Design C)
   would be a **worse** UX than no button — it promises a fix that doesn't stick. So the remedy's honesty
   is **gated on 598 R3** (durable rebuild). Until R3 lands, the cause should be worded without
   over-promising a one-click cure (e.g. surface the cause + "Open Health" rather than a "Rebuild index"
   button that may not stick), and A's remedy button should be enabled only once the rebuild is durable.
   This is recorded as a dependency, not a redesign.
5. **Single-vocabulary consistency (free).** All three placements (search banner, Health footer, Health
   "What you can do" affordance rows) already read `CAUSE_ROWS`, so the new compat cause words
   **identically** everywhere by construction — the 595/596 single-vocabulary property. No per-surface
   wording fork.

## 25. The user-facing design for B (the BLIND condition — diagnostic-tier, non-alarming)

A blind *memory-pressure* monitor is an **operator/diagnostic** concern, not an end-user alarm. The
user-facing rules:

- **Tier:** a `BLIND` condition is **info-severity** and lives in the **Health conditions/events list**
  (the diagnostic tier the Health view already renders), not at the top verdict and **never** in the
  search degradation banner.
- **Must not escalate the system verdict.** "A monitor can't evaluate" ≠ "the system is degraded." The
  verdict stays `operational` while a monitor is blind; the blindness is observable *only* to someone who
  opens Health. (This is the honest reading: we don't know the memory state — which is different from
  "memory is bad" *and* from "memory is fine.")
- **Wording:** factual and calm — *"The memory-pressure monitor can't evaluate yet (no samples)."* — not
  an alarm. It carries the same `i18nKey`/severity shape as any condition (it rides the existing
  `RuleEmitter`→condition seam, PART III §17), so it renders with the established condition styling.
- **Why show it at all:** so the product can no longer *silently* report "all clear" while a check is
  blind (§3) — the diagnosability hole closes — without manufacturing end-user noise.

## 26. Net user-facing shape & what is deliberately NOT added

- **No new UI component for A** — it feeds the existing banner causes-slot + remedy button + the Health
  footer/affordance rows, all already built and already reading one vocabulary.
- **No new top-level surface for B** — the BLIND condition reuses the conditions list; it is *demoted*,
  not promoted, in the hierarchy.
- **No verdict-escalation for blindness, no new alarm tones, no search-banner noise for B.**
- **No remedy over-promise** — A's "Rebuild index" button is honest only once 598 R3 makes the rebuild
  durable; until then the cause is named without a one-click button that won't stick.
- **The §10.3 over-claim guard is honored:** the cause is worded at *its* true severity (warn for
  BLOCKED_LEGACY, info for cosmetic gaps), never a blanket "semantic degraded / keyword results" alarm.

> **Verification honesty.** PART IV's structural claims (the banner causes-slot + remedy contract, the
> empty-causes boolean path, the Health footer "see recent events" wording, the affordance-row pattern)
> are source-/contract-verified and the surface *structure* was confirmed live in demo mode. The
> **degraded-state visuals themselves** (the filled banner, the "Service degraded" pill with a named
> cause) were **not** captured live — demo mode with the backend down renders `unreachable`, and forcing
> a `reindexRequired` demo fixture would require editing the mock (out of scope for this read-only
> inspection). The pre-implementation step is to capture those degraded states once A is wired
> (`audit-driven-fixes-need-test` + the 559 §6 measured UX-audit closure discipline).

---

# PART V — CONFIDENCE-BUILDING RESULTS (2026-06-17)

> Genre: pre-implementation de-risking (per the approved confidence-building plan — NO feature
> code was written; the only edit is this section). Method: three parallel source-cited subagent
> audits (briefed inline — subagents don't inherit CLAUDE.md) for the static tracks A1–A4 + B1,
> plus a **read-only** probe of an already-running stack owned by another session (no takeover) for
> the live tracks. Each finding carries file:line. Net: the structural implementation risks are
> retired and **two design points are refined** (B's wire-fit; A's exact cause scope). Per-design
> confidence ratings in §V.8.

## V.1 — A's backend emit seam & vocabulary openness (uncertainties #1/#2/#10) — RESOLVED

- **Emit seam exists, compat state already reachable.** `StatusLifecycleHandler.computeComponent()`
  receives `WorkerOperationalView workerView` (`StatusLifecycleHandler.java:893`), which carries
  `CompatibilityStatusView compatibility` (`WorkerOperationalView.java:22`) with
  `embeddingCompatState()`/`embeddingCompatReason()` (`CompatibilityStatusView.java:4-5`). The
  `INDEX_SERVING` case (`:904-936`) is the single clean insertion point on the `retrieval` composite —
  no new plumbing.
- **The reason-code vocabulary is OPEN end-to-end — NO schema/enum/wire change needed.** The composite
  reasonCodes are assembled as a `List<String>` with only a null/blank filter (`:1067-1076`;
  `ReadinessCompositeView.java:10`); there is **no** enum-membership check (`LifecycleReasonCode.isKnown()`
  has zero production callers). Proof: `worker.health.embedding_not_ready` is already emitted
  (`:955`) and reaches the FE yet is **absent from `LifecycleReasonCode`**. The FE schema types it
  `z.array(z.string())` (`status-response.ts:488/194`) and `parseWireContract` is fail-open anyway
  (`schemas.ts:282`). So a new code like `index.blocked_legacy` is a free string + a data-only
  `CAUSE_ROWS` row.
- **`check-verdict-derivation` gate is NOT tripped.** It forbids only a *second* `retrieval ===`
  re-derivation site inside `shell-v0`; routing the cause via the existing `reasonCodes` pass-through
  (`verdict.ts:165,168`) + a `CAUSE_ROWS` row introduces no such predicate. **Caveat:** do not add a
  `retrieval ===` check to `readinessNotice.ts` — it was removed from the allow-list (it now consumes
  the verdict).

## V.2 — A's blast radius; the boolean's fate (uncertainty #3) — RESOLVED, with a bonus finding

- **The cause source already exists on the wire.** `status.schema.reindexRequiredReason` is computed in
  `IndexStatusOps` (`worker-services/.../IndexStatusOps.java:921-937`) and already carries one of four
  tokens (`schema_mismatch` / `legacy_index` / `embedding_mismatch` / `embedding_legacy`). `verdict.ts`
  **ignores it** and synthesizes the opaque `reindex-required` from the boolean. So A doesn't need new
  backend computation — it needs to *route the reason that's already there* into the readiness composite.
- **The `reindexRequired` boolean has ZERO readers outside the verdict shortcut.** `BrainSurface.ts:1479`
  uses `reindexRequiredReason` (the string) and `compatState`, not the boolean. So the boolean +
  the `reindex-required` token can both be **removed** cleanly (recommended), with the cause flowing
  through the normal `retrieval==='degraded'` path (`verdict.ts:167-168`).
- **Exact test changes:** `verdict.test.ts:146`, `readinessNotice.test.ts:48&52`,
  `SearchSurface.degradation.test.ts:79/84/152`; fixture-only edits `verdict.test.ts:27`,
  `aiStateStore.test.ts:167`. (`'schema.reindex-required'` — the backend HealthEvent **condition id**
  consumed by Advisory/Recovery surfaces — is a *different string in a different namespace*; do not touch.)

## V.3 — A's compat-state scope vs 595 (uncertainty #4) — RESOLVED (a real boundary, now drawn)

| `EmbeddingCompatibilityController.State` | Disposition |
|---|---|
| `COMPATIBLE` | healthy — no FE representation |
| **`BLOCKED_LEGACY`** | **→ A reason code** (`index.blocked_legacy`), remedy `core.rebuild-index`, severity `warn` |
| **`BLOCKED_MISMATCH`** | **→ A reason code** (`index.blocked_mismatch`), distinct row |
| `REBUILDING` | **595 Stability axis (`rebuilding`) — DO NOT route as a degraded cause** (would re-create the 595 §1.1 split: calm "busy" + alarm "degraded" at once) |
| `UNAVAILABLE` | **optional-AI exclusion — DO NOT alarm** (no embedding model = offline-by-design; `verdict.ts:133-135` already excludes this) |

The existing `reindexRequired` boolean already OR's *only* the two `BLOCKED_*` states
(`IndexStatusOps.java:915-918`) — so it is the correct scope to draw A's two reason codes from, and
REBUILDING/UNAVAILABLE are correctly already excluded from it. **A must keep that scope.**

## V.4 — A's remedy honesty / 598 R3 (uncertainty #5) — CONFIRMED GATED

`core.rebuild-index` exists (`RebuildIndexHandler.java`) and fires `startMigration(restartWorker=true)`
(the blue/green path). But per 598 PART II §11 / IV §27-29 the rebuilt-embedding **fingerprint-stamp
durability window is unfixed (R3 unbuilt)** — a rebuild interrupted by any stop mid-backfill reverts to
`BLOCKED_LEGACY`. So **A's one-click "Rebuild index" button is NOT honest until 598 R3 lands.** Honest
interim: word the cause and use the existing `OPEN_HEALTH` navigate remedy (the established no-remedy
fallback pattern, e.g. `worker.health.embedding_probe_missing`), not a button that may silently not stick.

## V.5 — B's wire-fit & state machine (uncertainties #6/#7/#8) — RESOLVED, **design refined**

- **REFINEMENT (supersedes PART III §17's "new BLIND `ThresholdPhase`").** `ThresholdPhase` is a
  **closed enum** (`PENDING/FIRING/RESOLVED`) projected through a **generated, governance-registered
  wire contract** (`SSOT/schemas/health-event.v1.json` → `health-event.ts:51,85`;
  `governance/contract-surfaces.v1.json:90-97`). Adding a phase would cross the closed enum + schema
  regen + a governance contract-surface + FE render — heavyweight. **Instead reuse the existing
  `AssertedCondition status: "TRUE" | "FALSE" | "UNKNOWN"` (`health-event.ts:44`): `UNKNOWN` is already
  the wire vocabulary for "indeterminate."** A blind monitor emits a condition with `status:UNKNOWN` —
  **no wire/schema/governance change; FE-render refinement only** (render `UNKNOWN` distinctly in
  `HealthLitView.ts:410-416`).
- **State machine — "freeze, don't advance" (no new dwell state).** `indeterminate` should hold the
  dwell machine in place: PENDING freezes `pendingSince` (don't reset as `false` does at
  `DwellTimeScheduler.java:162-164`), FIRING holds (don't start grace at `:185-187`), KEEP_FIRING
  freezes `graceUntil`. When samples return, the next `Evaluated` tick resumes where it paused — strictly
  less code than a BLIND state, and it prevents a missing metric from ever producing a false RESOLVED.
- **Blast radius confined to `app-services/.../observability/rules/**`** (tri-state return on
  `CelEvaluator.evaluatePredicate` `:182-220`; thread through `RuleRunner.evaluateRule` `:150-172`;
  `DwellTimeScheduler.tick` signature) + the FE render refinement. Tests: rewrite the
  `CelEvaluatorTest` missing-metric assertion (`false` → `Indeterminate` — a genuine behavior change,
  not a weakening), add `DwellTimeSchedulerTest` freeze cases, add a `RuleRunnerTest`
  "missing metric never RESOLVES-as-healthy" regression.

## V.6 — B's symptom frequency (uncertainty #8b) — ANSWERED by inference + live health

The memory metric **producer is wired and archived** (PART II §10). A read-only probe of the live stack
(`/api/health`: head + worker `READY`) is consistent with a normally-evaluating rule; the backend slf4j
logs weren't capturable via the dev-runner tail here (both stdout/stderr returned empty), so the
CelEvaluator WARN-once couldn't be read directly. **Conclusion:** the rule is *not constantly blind* — the
`UNKNOWN`/blind condition is a **rare RRD-drift / stale-file / cold-start edge**, not a constant-noise
generator. This is good for UX (the BLIND condition seldom fires) and does not change B's correctness
(the freeze + `UNKNOWN` design is right regardless of frequency).

## V.7 — Live degraded-state visual (uncertainty #9) — DEFERRED (not disturbing another session)

A live `BLOCKED_LEGACY`/`reindexRequired` repro needs a legacy index (the 598 catch-22) and the only
running stack is **owned by another session** (active run `ccf28510…`; head+worker READY, inference
starting) — taking it over or editing mocks was out of bounds for this read-only pass. The *before-state*
is already pinned by the test contract (PART IV §23: empty causes slot + generic "Reindex required" /
"see recent events"). Capturing the *after-state* is the natural pre-merge step once A is wired
(`audit-driven-fixes-need-test` + 559 §6 measured UX-audit). **Residual, low-risk.**

## V.8 — Confidence ratings (0–10) & residual risks

| Design | Confidence | Why / residual |
|---|---|---|
| **A** (route compat cause into the verdict reason channel) | **8.5** | Emit seam confirmed; vocabulary open (no schema/enum/wire/gate change); cause source already on the wire; boolean removable cleanly; the REBUILDING/UNAVAILABLE boundary is drawn (the one real trap, now avoided). Residual: A's *remedy* is gated on 598 R3 (V.4) — a known, bounded dependency with an honest interim; and the after-state UX capture (V.7) is pending. |
| **B** (tri-state predicate → blind monitor observable) | **8** | Refinement removed the biggest risk — no new `ThresholdPhase`, reuse `status:UNKNOWN` ⇒ no wire/schema/governance change; "freeze don't advance" is a small, contained state-machine change with a clear test set; symptom is a rare edge (low noise). Residual: the FE `UNKNOWN`-condition render needs a small design pass (tone/placement per PART IV §25), and the `DwellTimeScheduler` freeze semantics need careful tests (the one place a subtle bug could hide). |
| **C** (mode-procedure gap) | **n/a (deferred to 598)** | Not 600's to build; only constrains A's remedy (V.4). |

**Overall remaining-work confidence: 8/10.** The two designs are well-scoped, reuse existing
authorities/contracts with no new wire surface, and the audits retired every structural unknown that
would have caused a mid-implementation surprise. The deductions are for: (1) A's remedy honesty being
coupled to 598 R3 landing (a sequencing dependency, not a design hole); (2) B's `DwellTimeScheduler`
freeze-semantics being the one subtle spot needing precise tests; and (3) the live degraded-state UX
capture still pending (cheap, but real). None block starting implementation when the user chooses;
all are named and bounded.

---

# PART VI — CROSS-TEMPDOC INTERFERENCE ANALYSIS (2026-06-17)

> Scope: tempdocs numbered within 20 of 600 (580–620) modified in the last 5 hours, plus the
> active git worktrees in that range. The question: could any current tempdoc work **long-term
> interfere** with 600's remaining work (Design A — FE `verdict.ts`/`readinessNotice.ts`/
> `aiStateStore.ts` + backend `StatusLifecycleHandler.computeComponent()`; Design B — rule engine
> `CelEvaluator`/`RuleRunner`/`DwellTimeScheduler` + an FE condition-render refinement).

**Recently-active set (mod < 5h):** 593–603 (the live "walkthrough family"). **Active registered
worktrees in range:** `worktree-598-capability-retrieval`, `worktree-599-impl` only. (Dirs
`565/569/587/591` exist under `.claude/worktrees/` but are **not** registered worktrees — stale,
ignored.) Verified by `git worktree list` + `git diff --stat main..<branch>` + `git log` on each
target file.

## VI.1 — Design B: essentially conflict-free (highest independence)
The rule engine (`modules/app-services/.../observability/rules/**`) was last touched by **tempdoc
430** (its creation, Phase 8d) and 447/421/438 (HealthEvent recovery/timeseries) — **none recent,
none in the walkthrough family.** No active worktree touches it; no uncommitted changes. B's FE
render target (`HealthLitView.ts`) is also untouched by 598/599. **Interference: LOW — B can be
built independently.**

## VI.2 — Design A FE files: merged-stable, no active editor
`verdict.ts`/`readinessNotice.ts`/`aiStateStore.ts` are hot historically (595 §15.3/§18.1 + 596 +
577) — **but that work is merged to `main`** (no uncommitted changes; the `no_documents`/`reasonFor`
596 rows are already present). **Neither active worktree (598/599) edits these three files.** So A
lands as a clean **extension** of the merged 595/596 authority, not a concurrent-edit collision.
- **595 = synergy, not interference.** A extends 595's verdict→CAUSE_ROWS exactly along 595's
  single-vocabulary direction; the boolean-removal A2 proposes is consistent with it. Residual: if
  595 gets *further* follow-ups to these files, coordinate — but no active worktree is doing so now.
- **596 = synergy.** A adds CAUSE_ROWS rows (additive to the merged table); 596's `reasonFor` then
  words A's new codes in the Health affordance rows automatically (free consistency). A must verify
  the new codes read well in both the banner and the affordance projection.
- **Backend `StatusLifecycleHandler.java`:** last touched by 587 (GPU sensing, inactive). No
  walkthrough-family churn. **Interference: LOW.**

## VI.3 — 598 (active worktree): the one genuine long-term coupling
Three distinct interactions, none a hard blocker but all to be respected:
1. **Sequencing dependency (already in V.4).** A's one-click `core.rebuild-index` remedy is honest
   only once **598 R3** (durable rebuilt fingerprint) lands. The active 598 worktree is currently
   doing **FE-component cleanup** (removing `CapabilityMap.ts`/`availability*.ts`, simplifying
   `Control.ts`) — **not** R3 — so R3 remains unbuilt and the gate stands. A must ship with the
   honest-interim remedy (name the cause + "Open Health") until R3 exists.
2. **Minor file overlap.** The 598 worktree edits `views/HealthSurface.ts` (~8 lines) and
   `BrainSurface.ts` (~4). A's Health-footer wording (PART IV §24.2) also lands in the Health view,
   and B's `UNKNOWN`-condition render is nearby. **Small merge-conflict window on the Health view —
   coordinate ordering; let 598's FE cleanup merge first, then re-verify the Health structure A/B
   build on** (598 is removing CapabilityMap/availability machinery, so the surface shape may shift).
3. **R1 strengthens A (not a conflict).** 598 R1 (capability-derived AUTO default) makes dense the
   default, so more queries hit the BLOCKED_LEGACY path — raising A's value, not colliding with it.

## VI.4 — 599 (active worktree): no interference
599 (`worktree-599-impl`) adds per-folder job-count status: `views/LibrarySurface.ts`,
`state/folderStatus.ts`, the indexing job-queue backend (`SqliteJobQueue`, `IndexingController`,
`indexing.proto`), and a `folder-status-derivation` gate. **No overlap** with A's verdict/reason
files or B's rule engine; different surface (Library) and different backend (job queue).
**Interference: NONE.**

## VI.5 — Fresh problem-statements (601/602/603): watch, no current interference
601 (availability-progress/ETA), 602 (residual FE reliability/consistency), 603 (RAG trust) were
just created (07:44–07:54) and are not implemented. **602** is the one to watch — "FE reliability
and consistency" could later propose verdict/Health-consistency changes overlapping A — but it is a
problem statement with no code. (Note a *non-600* tension: the 598 worktree is **removing** the
`availability*` machinery that **601** may want to build on — a 598↔601 conflict for those owners to
resolve, not 600's.)

## VI.6 — Verdict
- **Build Design B first / independently** — it is conflict-free (no active editor of the rule
  engine).
- **Design A** has no live file-collision (its FE files are merged-stable; no active worktree edits
  them) and is a clean extension of 595/596. Its only real couplings are to **598**: a hard
  *sequencing* dependency for the remedy (R3, unbuilt) and a *minor* Health-view merge window.
  **Recommended ordering: let the active 598 worktree's Health-view cleanup merge, then build A on
  the settled surface, shipping A's remedy in honest-interim form until 598 R3 lands.**
- No tempdoc in range threatens to invalidate 600's design; the couplings are sequencing +
  small-merge-window, all named and bounded.

---

# PART VII — AS-BUILT (2026-06-17, worktree `worktree-600-impl`, since merged to main)

> The long-term design + de-risking that precede this (Designs A/B/C, confidence ratings,
> cross-tempdoc interference) are in this doc's PARTS III–VI above; this note is self-contained so it
> stands alone. C (mode-procedure legibility)
> remains deferred to 598. Both shipped pieces **extend existing authorities** — no new wire type,
> enum, schema, or governance surface.

## Design A — the actionable degradation cause now reaches the one verdict authority

The embedding/schema-compat cause (a serving index that is healthy for keyword search but has its
dense leg BLOCKED — legacy/no-fingerprint or model/schema mismatch) is now emitted as a **real
`retrieval`-composite reason code**, so the single 595 verdict + `CAUSE_ROWS` authority names the
specific cause instead of a generic boolean-derived "Reindex required".

- **Backend:** `StatusLifecycleHandler.compatBlockedReason(WorkerOperationalView)` + a branch in the
  `INDEX_SERVING` readiness component (placed **before** the `indexHealthy → READY` arm — the reason
  the cause was invisible) maps `BLOCKED_LEGACY`/`BLOCKED_MISMATCH` (schema first, then embedding) to
  `index.blocked_legacy` / `index.schema_mismatch` / `index.embedding_legacy` /
  `index.embedding_mismatch`. `REBUILDING` (a 595 transient) and `UNAVAILABLE` (optional-AI) are
  excluded. Free-string reason codes — no enum/schema/wire change.
- **FE collapse of the fork:** `verdict.ts` no longer mints a synthetic `reindex-required` token from
  the `reindexRequired` boolean; the cause flows through the normal `retrieval === 'degraded'` path via
  `reasonCodes`. The `ReadinessView.reindexRequired` field + its population were removed (it had no
  reader outside the deleted shortcut). `readinessNotice.ts` gained an exported `isReindexCause()`
  predicate (the one place that knows which codes carry the "Reindex required" headline) + four
  `CAUSE_ROWS` rows wording each cause (severity `warn`, existing `core.rebuild-index` remedy kept —
  not regressed; its cross-restart durability remains 598 R3's concern).
- **User-visible result:** the search degradation banner's `degradation-causes` slot now names the
  specific cause (previously empty for the reindex path); the Health verdict reads "Reindex required".

## Design B — a health rule that cannot evaluate is now a first-class observable

A missing-metric predicate no longer collapses to `false` ("healthy"). New `PredicateOutcome` tri-state
(`Evaluated | Indeterminate`): `CelEvaluator.evaluatePredicate` returns `Indeterminate(reason)` on a
`MissingMetricException`; `DwellTimeScheduler.tick(Rule, PredicateOutcome)` **freezes** on indeterminate
(no reset of PENDING, no grace start, no spurious RESOLVE — so "can't see" never reads as "healthy");
`RuleRunner` emits a `monitor.unobservable` `AssertedCondition(status=UNKNOWN)` via the existing
`RuleEmitter`→`ConditionStore`→broadcast seam and clears it when samples return. `HealthLitView`
renders an `UNKNOWN`-status condition calmly (INFO tone, "Cannot evaluate yet"), never escalating the
verdict. No new `ThresholdPhase`/wire/governance change — `UNKNOWN` already existed in the
`AssertedCondition` vocabulary.

## Verification (static — all green; surfaced in the implementing transcript)

- `./gradlew.bat spotlessApply` (clean) · `build -x test` → **BUILD SUCCESSFUL** (compile + all
  governance gates, incl. `class-size` after a `declared-growth` changeset for
  `StatusLifecycleHandler` 1116→1157).
- `:modules:app-observability:test :modules:app-services:test :modules:ui:test` → **BUILD SUCCESSFUL**.
  Key classes: `CelEvaluatorTest` 11 · `DwellTimeSchedulerTest` 20 · `RuleRunnerTest` 6 ·
  `StatusLifecycleHandlerTest` 13 — 0 failures.
- `check-verdict-derivation` gate → OK (single derivation preserved).
- FE `npm run typecheck` clean · full unit suite **3136 passed (326 files)**.

## Live UI verification (browser, Vite dev server on :5180 = my merged FE code)

- **Design A — CONFIRMED in-browser.** The search-surface degradation banner rendered by the merged FE
  code names the **specific** new cause and offers the rebuild remedy. With `index.blocked_legacy` on the
  `retrieval` composite, the banner showed: tone `warning`, headline **"Reindex required."**, cause
  **"The index was built before semantic search was available — rebuild it to enable meaning-based
  results."**, remedy `core.rebuild-index` ("Force Rebuild" button) — screenshot captured. The
  `isReindexCause` discrimination was also confirmed live: real non-reindex codes (chunk_embedding /
  lambdamart / inference) correctly render **"Semantic search degraded"**, NOT "Reindex required".
  (The live backend reached via the dev proxy runs old backend code with no BLOCKED_LEGACY index, so the
  target reason code was injected at the `/api/status` parse boundary to exercise the real FE render path;
  the backend emit itself is covered by `StatusLifecycleHandlerTest`.)
- **Design A — Health header note.** On the live (mid-activity) backend the Health verdict header read
  "Reconnecting…" — the 595 Stability axis (`transitioning`) correctly takes precedence over `degraded`;
  the degraded-state header/footer wording is covered by `verdict.test.ts`.
- **Design B — CONFIRMED in-browser on the PRODUCTION Health surface.** Note the production conditions
  renderer is `HealthSurface.ts` → `<jf-health-event>` (the `HealthLitView` render branch I also added is
  the `?lit-health=1` debug path). Injecting a `monitor.unobservable` `AssertedCondition(status=UNKNOWN)`
  (severity INFO) into the live `jf-health-surface` rendered a **calm "RECENT EVENTS" row** —
  *"monitor.unobservable — Health rule 'memory-pressure' cannot evaluate: metric
  head.jvm.memory.heap.used_bytes has no samples"* — and the **verdict was UNCHANGED** (stayed
  `transitioning/busy`, not escalated), confirming a blind monitor does NOT alarm the verdict. This is
  structural: `computeVerdict` derives from `(phase, stability, readiness)` only — it never reads
  conditions, so a condition of any severity cannot move the verdict. Screenshot captured.
  (`HealthLitView.test.ts` also render-tests the debug-path component with the same condition.)

---

# PART VIII — FUTURE DIRECTIONS (research-backed, 2026-06-17)

> Ideation built on the shipped substrate (Design A = actionable cause legibility via the one
> `CAUSE_ROWS` reason vocabulary; Design B = a blind monitor as a first-class observable). **No goal
> is committed — all of these are viable, none urgent** (app is pre-production, no users). Method: two
> rounds of web research across SRE alerting, Prometheus/meta-monitoring, error-message UX, and
> status-page UX (sources at the end), then mapped to this codebase's actual seams.

## §VIII.1 — What the research validated

Both shipped designs land squarely on mature, documented practice — not bespoke invention:

- **Design A ≈ "actionable alerting + error-message UX."** Google SRE: *every alert must be actionable*
  and paired with a runbook (severity, impact, steps). Nielsen heuristic #9: *explain the cause, suggest
  a solution, hide error codes, use plain language, be polite.* `CAUSE_ROWS` (code → wording + remedy +
  severity-tone) is exactly this shape. Status-page UX (AWS/Azure/Atlassian): communicate *cause +
  impact + recovery process*, worst-impact-first — which is also 600's original #4.3 hierarchy point.
- **Design B ≈ "meta-monitoring / monitor-the-monitor."** The industry's named risk: *"the observability
  system itself becomes unobservable."* Prometheus models missing data as a first-class state
  (`absent()`, staleness markers) and never as "healthy" — the same epistemic rule Design B's tri-state
  enforces.

**Implication:** the substrate is sound; the directions below are extend/polish, not rework. The one
clear *gap vs best practice* the research exposed: the production blind-condition render shows the raw id
`monitor.unobservable` (violates Nielsen "hide error codes / plain language") — already logged to
`observations.md`.

## §VIII.2 — Idea catalogue (mapped to seams; rough value)

**Polish**
- **P1 — plain-language blind-monitor render (Nielsen "no error codes").** On the production path
  (`HealthSurface`→`<jf-health-event>`) word it as "The memory-pressure check can't read its data yet"
  + a display-catalog i18n entry for `monitor.unobservable`; today users would see the raw id. *Low
  effort, real correctness/UX win.* (Already in `observations.md`.)
- **P2 — a help/runbook reference per cause (SRE "playbook per alert").** Extend `CAUSE_ROWS` rows with
  an optional doc/runbook link (e.g. "what is a reindex, why it's needed"), surfaced beside the remedy.

**Simplify (structural)**
- **S1 — one registered degradation-cause vocabulary.** Today reason codes live in ≥4 places
  (`LifecycleReasonCode` enum, the readiness-composite free strings incl. the new `index.*`,
  `SearchReasonCode` per-query, the compat states). 600-A added a family by hand. A **553-style register**
  (one source: code → wording/remedy/severity/runbook, build-gated so every emitted code is registered +
  worded) would stop the vocab re-forking that 600 itself had to repair. *Highest structural leverage —
  turns 600's point-fix into a guarantee.*

**Extend**
- **E1 — engine-liveness watchdog (DeadMansSwitch analog).** Design B catches a *blind* rule; it does NOT
  catch a *dead* `RuleRunner` (ticks stopped) or a *stalled* condition SSE. Add a heartbeat — the engine
  publishes "alive @ tick N"; if the FE stops seeing it, surface "health monitoring is offline." The
  silence-is-the-alarm pattern; closes the meta-monitoring gap one level up from Design B.
- **E2 — explicit "expected-metric" assertions (`absent()`-style).** Let a rule declare "metric X must
  have samples" → a typed `metric.absent` blind observable, surfacing the RRD-drift root cause proactively
  rather than only inferring blindness when a predicate happens to reference a missing metric.
- **E3 — action-class on `CAUSE_ROWS` (SRE classify-every-alert).** We carry severity (impact); add an
  orthogonal *action-class* (actionable / informational / watch) so the UI knows when to show a remedy
  button vs a passive note — distinct from how alarming the tone is.
- **E4 — capability-gated remedies (generalize the 598-R3 dependency).** A remedy button appears only when
  it will actually work *and stick*; each remedy declares preconditions (rebuild ⇒ durable-generation
  support). Encodes the "don't offer a fix that won't stick" rule 600-A had to hand-handle for the rebuild.

**New UX**
- **N1 — a "System self-check" surface (the unifying product feature).** One screen listing every
  check/monitor with a tri-state: ✅ OK · ⚠ Degraded (named cause + remedy) · ❓ **Can't evaluate (blind)**,
  worst-first. This is the natural surface that fuses Design A (causes+remedies) and Design B (blind
  monitors) into a single "what's wrong, what to do, and is the system even watching itself" view.
  *Highest product value — makes the diagnosability investment visible and self-explaining.*
- **N2 — remedy at the point of action (Nielsen "real-time guidance / proximity").** Surface the
  cause+remedy *where the user is about to hit the degraded capability* (e.g. a calm hint in the search
  box when semantic search is blocked), not only after a query returns keyword-only.
- **N3 — close-the-loop remediation (status-page "recovery process").** Click remedy → show progress →
  confirm the cause cleared → completion toast (reuses 595's transition machinery + the ConditionStore's
  `lastTransitionTime`).
- **N4 — explainable health timeline.** A history of cause/condition transitions (when it degraded, why,
  what cleared it), trivially derivable from the ConditionStore's k8s `lastTransitionTime`.

## §VIII.3 — If we ever pursue this (a coherent thread, not a commitment)

The pieces compose into one arc rather than scattered features: **S1** (one registered cause vocabulary)
is the foundation; **E1/E2** (watchdog + expected-metric) complete the meta-monitoring story Design B
started; **N1** (the self-check surface) is the product payoff that renders all of it, with **P1/E3**
making each row plain-language and correctly classed. That arc would turn "we name one cause and one
blind monitor" into "the system continuously, legibly explains its own health and how to fix it."

## §VIII.4 — Sources
- Google SRE — *Alerting on SLOs* / *Being On-Call* (actionable alerts + playbooks): https://sre.google/workbook/alerting-on-slos/ , https://sre.google/workbook/on-call/
- PromLabs — *End-to-end watchdog alerts* (DeadMansSwitch/meta-monitoring): https://training.promlabs.com/training/monitoring-and-debugging-prometheus/metrics-based-meta-monitoring/end-to-end-watchdog-alerts/
- Prometheus missing-metric / `absent()` / staleness patterns; SAP `absent-metrics-operator`: https://github.com/sapcc/absent-metrics-operator
- Nielsen Norman Group — *Error-Message Guidelines* (heuristic #9): https://www.nngroup.com/articles/error-message-guidelines/
- Meta-monitoring overviews (NXLog, DZone, Grafana self-monitoring): https://grafana.com/docs/learning-hub/intro-to-fleet-management/01-health-monitoring/13-self-monitoring/
- Status-page degraded-component + impact-hierarchy UX (Atlassian Statuspage, AWS/Azure Health): https://support.atlassian.com/statuspage/docs/top-level-status-and-incident-impact-calculations/

---

# PART IX — LONG-TERM DESIGN: one enforced degradation-cause vocabulary (2026-06-17)

> Genre: design-theory (general, not implementation-level). Of the PART VIII menu, this is the ONE
> item that is a *demonstrated, present structural problem* (not a feature or a hypothetical-failure
> guard); the rest are explicitly scoped OUT below. It is the structural completion of Design A: A
> routed *one* cause into the verdict→`CAUSE_ROWS` channel; this makes the channel's *vocabulary* an
> authority so no cause can silently fall out of it.

## §IX.1 — The problem (demonstrated and live, not speculative)

Design A shipped by hand-adding 4 codes to BOTH the backend producer (`StatusLifecycleHandler.computeComponent`)
and the FE wording table (`readinessNotice.CAUSE_ROWS`). Investigation of `main` found the
degradation-cause vocabulary is **already drifting in both directions, with zero enforcement**:

- **Forward gap (user-facing):** ≈7 readiness reason codes the backend can emit — `worker.unavailable`,
  `worker.not_configured`, `worker.not_started`, `index.not_healthy`, `telemetry.metrics.stale`,
  `telemetry.metrics.high_failure_rate`, `telemetry.disk_space_low` — have **no `CAUSE_ROWS` row**. If
  any reaches a degraded verdict, `wordCauses` falls back to the literal string `Degraded: <code>` — a
  raw code shown to the user, the exact Nielsen-#9 "no error codes / plain language" violation PART VIII
  flagged.
- **Backward gap:** `CAUSE_ROWS` carries rows for codes never emitted onto readiness (`ort_cuda.*`) —
  dead/misleading entries.
- **The deeper class 600 repaired** — a cause computed in the system but emitted on a vocabulary the
  verdict never reads (the original compat fork) — is unprevented; nothing stops the next one.

Root structural cause: the readiness-degradation vocabulary is **not a single closed authority**. It is a
*mix* of `LifecycleReasonCode` enum members **and raw string literals** in `computeComponent`
(`index.*`, `worker.health.*`, `worker.unavailable`, `index.not_healthy`), with **no register, no
contract test, and no producer↔`CAUSE_ROWS` correspondence check**. Per `structural-defects-no-repeat`,
600's own fork proves the class — and here the drift is already live.

## §IX.2 — The principle

> The reason-code vocabulary that the ONE verdict→`CAUSE_ROWS` authority consumes must itself be a
> **single, closed, declared authority**: every code a producer can emit is declared in one place, and
> the FE wording is **enforced to correspond** (forward *and* backward). Then "a user sees a raw code"
> and "a cause is emitted that the legibility authority cannot word" are both *unrepresentable* — the
> same prevention-ladder move (collapse → unrepresentable → gate) the presentation authorities already use.

## §IX.3 — What already exists to extend (do NOT replace)

- **FE wording authority — KEEP as-is.** `CAUSE_ROWS` (code → wording · remedy · severity) +
  `wordCauses`/`reasonFor`/`severityForCodes` is the correct **FE-owned, editorial** authority (the
  deliberate split: stable codes on the wire, human wording on the FE). The design does **not** generate
  wording — generating editorial copy would be wrong.
- **Single producer seam — already good.** `StatusLifecycleHandler.computeComponent` is the one emit
  site; the fix closes its *vocabulary*, not its location.
- **Closed-enum precedent — `LifecycleReasonCode`.** It already holds many readiness codes; the gap is
  the raw-string ones it doesn't.
- **Reason-code-as-contract precedent — the SEARCH side.** `SSOT/catalogs/reason-codes.v1.json` +
  `docs/reference/contracts/search-and-rag-reason-codes.md` + the allow-list test
  `GrpcSearchServiceReasonCodeContractTest` already treat the *per-query* reason codes as an enforced
  contract. The readiness side is the missing analog.
- **Register+gate precedent — `governance/verdict-derivation.v1.json` + `check-verdict-derivation.mjs`.**
  This is the verdict's single-*derivation* guard; its CLAUDE.md note already says a *completeness*
  sibling "wasn't warranted for a ~2-entry addition." The vocabulary is now ~20 codes and drifting, so
  that sibling is **now** warranted — this design is exactly it.

## §IX.4 — The design (three moves, each extending an existing structure)

1. **Close the readiness-degradation vocabulary into one declared source.** Every readiness reason code
   the producer emits comes from ONE closed set — no raw string literals in `computeComponent`. Placement
   is the one open decision (extend `LifecycleReasonCode`, vs a head-scoped sibling set if the
   `/api/health` `LifecycleSnapshotV1` contract scope should not widen). The point is enumerability: the
   emittable vocabulary is reviewable in one place.
2. **Enforce FE correspondence with a gate**, modeled on `check-verdict-derivation` + the search
   reason-code allow-list test. Against the declared set it asserts: *forward* — every emittable code is
   worded in `CAUSE_ROWS` **or** declared "no user wording" (transient/internal); *backward* — every
   `CAUSE_ROWS` code is an emittable code **or** a declared FE-derived one (e.g. `no_documents`, the
   596 §17 precedent). A small `governance/*.v1.json` holds only the file pointers + the two exception
   allow-lists — **not a third copy of the code list** (that would itself drift); the gate reads the
   closed set from the producer source and `CAUSE_ROWS` from the FE and checks correspondence directly.
3. **Reconcile the current drift first** ("make it correct now" before "keep it correct"): word the ≈7
   unworded emitted codes (or declare them no-wording), and resolve the FE-only rows (declare `ort_cuda.*`
   out-of-readiness / FE-derived, or remove). Then the gate locks it.

## §IX.5 — Scope discipline — what this deliberately is NOT

- **Not a merge of the SEARCH and readiness vocabularies.** `SearchReasonCode` / `reason-codes.v1.json`
  has a different consumer (the per-query `SearchTrace`) and its own contract+test. The two should share
  the *pattern* (a declared vocabulary + a correspondence check), not one list — merging is speculative
  over-DRY (AHA: unify only what shares a reason to change).
- **Not CAUSE_ROWS codegen** — wording is editorial/FE-owned.
- **Not the watchdog (E1), the self-check surface (N1), action-class (E3), or remedy-preconditions (E4).**
  None is a demonstrated present defect; they remain PART VIII ideas, not designed here.

## §IX.6 — Why it matters / outcome

A new degradation cause becomes impossible to add half-way: the producer must emit it from the declared
set, and the build fails until the FE either words it or declares it wording-free. Users stop seeing raw
codes; the legibility authority can never be bypassed; and the fork 600 hand-repaired cannot re-form.
This converts Design A from "we routed one cause correctly" into "no cause can be un-routed."

---

# PART X — USER-FACING DESIGN for the vocabulary authority (2026-06-17)

> PART IX is mostly build-time (a closed vocabulary + a CI gate), but its "reconcile the drift" move is
> user-facing: it decides *what the user sees* for each unworded cause, and its whole motivation is that
> unworded codes render as a raw `Degraded: <code>` string. Per the task, I inspected the LIVE UI (Vite
> demo + `/api/status` fetch-injection) rather than trusting the tempdoc — and the findings **materially
> narrow and correct PART IX's user-facing claim.**

## §X.1 — What is user-visible (and what is not) — verified live

The ONLY live path by which an unworded reason code reaches the user is the **search degradation
banner** (`readinessNotice` → `wordCauses`), and only when the **verdict is `degraded`** (i.e. the
`retrieval` composite resolves to `DEGRADED`; the verdict then renders `retrieval.reasonCodes` +
`aiFeatures.reasonCodes`). Confirmed by injection:

- **Raw fallback IS real (reproduced).** An unworded code in a DEGRADED retrieval composite rendered the
  banner cause **"Degraded: telemetry.metrics.stale"** — a raw code shown to the user (the Nielsen-#9
  violation). So the fallback path is genuinely reachable.
- **But the currently-emitted unworded codes mostly CANNOT reach it.** `worker.unavailable` (→ INDEX_SERVING
  `NOT_READY`) drove the composite to `NOT_READY` → FE `retrieval='unknown'` → verdict **`checking`** →
  **no banner** (reproduced). The same holds for `worker.not_configured`/`worker.not_started`
  (`NOT_CONFIGURED`) and `index.not_healthy` (`NOT_READY`): `combineReadinessState` ranks `DEGRADED`
  *lowest*, so these higher-precedence states mask it, and `checking` carries no reasons.
- **`telemetry.*` live on the `telemetry` composite** — which the verdict does not read at all → never in
  `verdict.reasons` → never user-facing via the banner.
- **The one genuine live leak is `gpu.saturated`** (an `aiFeatures` code): when retrieval is *independently*
  degraded, the verdict appends `aiFeatures.reasonCodes`, and the banner rendered
  **"…rebuild it… · Degraded: gpu.saturated"** — a raw secondary cause (reproduced).
- **`reasonFor` (the 596 per-affordance CAUSE_ROWS consumer) is dead.** No live call site — every
  `unavailableBecause(...)` passes a hardcoded human string, not a reason code. So a control's "why
  unavailable" cannot leak a raw code today.
- **The Health verdict header/footer don't leak codes** (`presentVerdict` is kind/severity-based, not
  per-code), and the Health conditions list renders via `present.ts`/the display catalog (a separate
  path from `CAUSE_ROWS`).

## §X.2 — Correction to PART IX

PART IX (and the title) overstated the live exposure ("a user would see a raw `Degraded:
telemetry.metrics.stale`"). **Live, `telemetry.metrics.stale` cannot reach the banner** (wrong composite),
and the worker/index unworded codes resolve to a neutral `checking` verdict, not a raw cause. The honest
statement: **the raw-code fallback is reachable and proven, but today only `gpu.saturated` (as a secondary
banner cause) actually exposes it; the gate's primary user-facing value is PREVENTING a *future*
degraded-retrieval/aiFeatures code from shipping unworded** (the structural risk), not repairing a
widespread current leak.

## §X.3 — The user-facing design

1. **Scope the gate's forward-correspondence to the verdict-consumed channel, not "all emitted codes."**
   The user-facing contract is: *every reason code emitted onto a verdict-consumed composite (`retrieval`
   or `aiFeatures`) must have a `CAUSE_ROWS` row.* Codes on non-consumed composites (`telemetry`) or that
   only ever drive a non-`degraded` verdict kind (`checking`/`connecting`/`transitioning` — e.g.
   `worker.unavailable`, `index.not_healthy`, `worker.not_configured/started`) are **wording-exempt by
   construction** — the verdict already shows a neutral, non-alarming state for them, so a `CAUSE_ROWS`
   row would be dead. This is the principled form of PART IX §IX.4's "no-wording-needed" exemption: it is
   not a convenience list but a derivation from *which codes can reach `wordCauses`*.
2. **Word the one real gap now: `gpu.saturated`.** Add a calm, plain-language `CAUSE_ROWS` row —
   e.g. *"The GPU is busy; results may be slower"*, severity `info` (it's a transient performance dip,
   not a broken capability), no one-click remedy (Open-Health fallback). This removes the only live
   raw-code leak.
3. **Wording rules for any future row (Nielsen #9 + status-page UX, from PART VIII):** plain language, no
   raw codes; name the cause *and* a constructive next step; tone matches severity (`info` calm, `warn`
   actionable, `error` only when truly broken); never blame the user. These already hold for the shipped
   `index.*` rows; the gate + this rule keep them holding.
4. **The gate is the user-facing guarantee.** Because the only leak surface is the banner, the gate's
   "every verdict-consumed code is worded" check is *exactly* "the user can never be shown a raw code in
   the degradation banner" — a precise, testable user-facing invariant.

## §X.4 — Boundary
- This does NOT add a new surface — it tightens the existing banner's vocabulary contract.
- It does NOT require wording the non-degraded/neutral-state codes (a real correction vs PART IX's "word
  all ~7"): wording them would be dead UI.
- The Health conditions/`present.ts` path and the blind-monitor render are separate concerns (their own
  display authorities), out of scope here.

---

# PART XI — PRE-IMPLEMENTATION CONFIDENCE (read-only investigation, 2026-06-17)

> Confidence-building pass for the PART IX/X design — read-only (the live UI was verified in PART X, so
> no dev-stack/browser). Six tracks retire the load-bearing uncertainties; findings + a rating below. No
> code, gate, or enum change made.

## §XI.1 — Findings per track

- **T1 — composite map (CONFIRMED).** `ReadinessDimension`: `retrieval` = {WORKER_CONTROL_PLANE,
  INDEX_SERVING, CHUNK_EMBEDDING, LAMBDAMART_MODEL}; `aiFeatures` = {AI, EMBEDDING, GPU}; `telemetry` =
  {TELEMETRY}. So `gpu.saturated`→aiFeatures (verdict appends it as a *secondary* cause when retrieval is
  degraded — matches PART X), and `telemetry.*`→telemetry (the verdict never reads it). PART X's leak
  analysis is correct.
- **T2 — producer inventory (CONFIRMED).** `computeComponent` emits ~21 codes: ~10 via
  `LifecycleReasonCode` enum members (chunk_embedding.*, lambdamart.*, gpu.saturated, telemetry.*,
  inference.*, worker.throughput_*) and **~11 raw string literals** (`index.*` ×4, `worker.health.*` ×2,
  `worker.unavailable`, `worker.not_configured`, `worker.not_started`, `index.not_healthy`,
  `worker.starting`). The raw-string set is exactly what "closing the vocabulary" must absorb.
- **T3 — vocabulary home: EXTEND `LifecycleReasonCode` (decided, low-risk).** It is already the de-facto
  readiness taxonomy (holds chunk_embedding/lambdamart/gpu/telemetry). `LifecycleContractTest` does **not**
  pin its membership (it checks schema_version/state/HTTP gating + a `reason_code` *format* regex
  `^(head|worker|ipc|inference)\.…` that applies to the `/api/health` **component** reason_codes, not the
  readiness-composite codes). There is **no generated closed-enum** for reason codes — readiness codes are
  `z.array(z.string())` on the wire (open), and the wire-schema codegen targets are only status-response +
  health-event — so adding members needs **no FE regen** and does **not** widen the `/api/health`
  contract. A head-scoped *sibling* set was considered and **rejected**: it would fragment the vocabulary
  the design is trying to unify. Decision: move the ~11 raw-string readiness codes INTO
  `LifecycleReasonCode` (preserving exact string values), making it the single closed source.
- **T4 — gate pattern (CONFIRMED feasible).** Model on `check-verdict-derivation.mjs` (~95-LOC node gate:
  small `governance/*.v1.json` register + source-scan). The gate reads the closed enum members + parses
  `CAUSE_ROWS` codes from `readinessNotice.ts` (a prior agent already extracted both this way — feasible).
  **Scoping refinement (replaces "compute composites"):** the gate uses a **declared exempt allow-list**
  (the search-issuance pattern) rather than computing composites — forward: *every readiness code NOT in
  the declared no-wording-exempt list must have a `CAUSE_ROWS` row*; backward: *every `CAUSE_ROWS` code is
  a readiness code OR a declared FE-derived/cross-vocab one*. The register holds only pointers + the two
  small allow-lists (not a third copy of the code list).
- **T5 — exemption sets (ENUMERATED).** *Backward-exempt (FE rows that aren't readiness codes):*
  `ort_cuda.missing_dlls` / `ort_cuda.provider_failed` (CONFIRMED not emitted by the readiness producer —
  grep empty; they're search/RAG codes) + `no_documents` (596 FE-derived control code). *Forward-exempt
  (emitted, but no user wording needed — they never reach a degraded verdict):* `worker.unavailable`,
  `worker.not_configured`, `worker.not_started`, `index.not_healthy`, `worker.starting` (all drive
  NOT_READY/NOT_CONFIGURED/STARTING → verdict `checking`, never `wordCauses`) + `telemetry.*` (non-verdict
  `telemetry` composite). Each carries a one-line rationale.
- **T6 — `gpu.saturated` is a real gap (CONFIRMED).** It is production-wired (`CoreApiAssembly` constructs
  + sets `GpuSaturationMonitor`; null-safe when NVML absent), emits `DEGRADED` + `gpu.saturated` on
  `aiFeatures` at >80% sustained utilization, and can co-occur with a degraded retrieval → rendered as a
  raw secondary banner cause (PART X reproduced this). So wording it (PART X §X.3.2) fixes a genuine, not
  theoretical, leak.

## §XI.2 — Residual risks (bounded)
- **The forward gate rests on a human-curated exempt allow-list.** A wrong future exemption could let a
  raw code through — but it requires a *reviewable* register edit, vastly better than today's silent
  hand-sync. (Same trust model as `check-search-issuance`'s `allowedReferences`.)
- **Extending `LifecycleReasonCode` touches an `app-api` "stable" enum.** The change is purely additive
  (new members, existing strings preserved) with no membership pin and no FE regen — low risk, but it is a
  contract-surface file, so the additive-only discipline must hold.
- **Mechanical string-preservation** when moving raw literals into the enum (a wrong-value would break the
  FE/readiness string match) — low risk, caught by the existing FE tests + the new gate.

## §XI.3 — Confidence rating
**8.5 / 10 for the remaining work.** The biggest fork (vocabulary home) is settled with evidence
(extend `LifecycleReasonCode`; additive; no contract/regen risk), the gate pattern is proven and the
extraction feasible, both exemption sets are enumerated with rationale, and the one real user-facing gap
(`gpu.saturated`) is confirmed. The deductions are for the human-curated exempt-list trust model and the
care needed for an additive change to a stable `app-api` enum — both bounded and named. Nothing here
blocks implementation when chosen.

---

## REOPENED — second pass (2026-06-17, 593 regression sweep)

> Designs A+B and the PART IX/X vocabulary closure verified well on the re-run (the
> degraded-cause-buried finding is FIXED — sweep ✅#7). But two residuals **in 600's own
> scope** survived, one of them a **truthfulness regression introduced by the fixes**.

### C-1 — the degraded copy now OVER-CLAIMS (NEW #1, the consumed-wording half)

- **Observed (sweep NEW #1):** the reframed banner asserts *"results are still fully semantic"*
  and the "What you can do right now" panel says *"results are complete"* — while the index is
  BLOCKED_LEGACY and the **default query runs pure BM25** (trace: Dense AND SPLADE skipped).
  The old copy ("keyword-only / Reindex required") was alarming-but-accurate; the new copy is
  **calm-but-inaccurate** in this state.
- **Root / ownership boundary:** the wording is wrong because it consumes a capability signal
  that lies — `indexCapabilities.embeddingCoverage = 0.9755` does **not** reflect the Worker's
  BLOCKED_LEGACY gate. **598 owns the SOURCE** (the capability must reflect the embedding-compat
  boundary — reopened there as B-3). **600 owns the COPY**: once 598's source is truthful, 600's
  degraded-banner / "What you can do right now" wording must read the corrected capability and
  say "keyword" honestly when BLOCKED_LEGACY. This is the exact 600 PART IV constraint ("the
  Rebuild remedy's honesty depends on 598 R3") now realized as a live regression.
- **Sequencing:** C-1 is **prerequisite-gated on 598 B-3**; do not patch 600's copy against the
  lying capability — fix the source, then verify the consumed wording reads true.

### C-2 — the self-monitoring blind rule still cannot fire LIVE (Still-Present #9)

- **Observed (sweep Still-Present #9):** in the current run the UI Logs tab shows, every 5s:
  *"rule 'memory-pressure' predicate missing metric 'head.jvm.memory.heap.used_bytes' has no
  recorded samples."* Design B tri-stated the predicate (`Evaluated | Indeterminate`) so a blind
  monitor is *representable* — but the **underlying RRD catalog-drift / stale-on-disk condition**
  that yields the missing samples (PART II "SECOND CORRECTION": producer exists, samples don't
  reach the RRD) is **unfixed**, so the rule is still blind on `main`.
- **Scope question for the reopen:** confirm whether Design B's `monitor.unobservable` UNKNOWN
  condition is actually being emitted for this rule on `main` (i.e. the blindness is now a
  first-class observable, even if the root metric-sampling gap persists), or whether it still
  collapses silently. If B is emitting the UNKNOWN, C-2 narrows to the **producer-side RRD
  sampling** fix (adjacent to 588's silent-failure class). If not, B's wiring for this rule is
  incomplete. Needs a live oracle check + a regression test (audit-driven-fixes-need-test).

### Disposition

- **Reopened for C-1 (copy, gated on 598 B-3) and C-2 (live blind-rule).** Designs A+B and the
  PART IX/X reason-code closure stand as merged history. C-1 is a downstream truthfulness
  completion; C-2 is the *root* of the self-monitoring item B made representable but did not
  resolve at the sampling layer.

---

# PART XII — REOPEN INVESTIGATION (2026-06-17, agent takeover, second pass)

> Genre: source-cited discovery for the two reopened residuals (C-1, C-2) — *before* any code,
> per `verify-dont-guess` + `audit-driven-fixes-need-test`. Method: direct reads of the FE wording
> projection (`readinessNotice.ts`, `availability.ts`), the full Design-B chain (`CelEvaluator` →
> `RuleRunner` → `RuleEmitter` → `ConditionStore`) and the RRD producer (`RrdMetricStore`,
> `Signal`), one source-cited subagent audit of 598 B-3's state in `main`, and a **read-only live
> oracle** against a running `main` stack owned by another session (no takeover). Every load-bearing
> claim carries file:line. Net: **C-1's stated mechanism does not survive verification (it is a
> 598-source problem with ~zero 600 code residual); C-2 is real, but its shape is three layers, not
> one, and the reopen mislocates the observed "every 5s" symptom.**

## XII.1 — C-1: the over-claim is upstream of 600's wording — 600's residual is a *verification*, not a *fix*

The reopen says 600's degraded copy "OVER-CLAIMS 'results are complete / fully semantic'" because it
"consumes 598's lying capability (`embeddingCoverage=0.9755`)." Verified against `main`, that
attribution is **imprecise in a way that changes the disposition**:

- **`embeddingCoverage` has ZERO `shell-v0` consumers.** Source-cited subagent audit + direct grep:
  no `views/**`/`state/**` reads `indexCapabilities.embeddingCoverage`. So 600's wording does **not**
  literally consume it. Whatever drove the sweep's "fully semantic" copy, it was not a direct
  `embeddingCoverage` read in 600's projection.
- **Both 600 wording paths word BLOCKED_LEGACY *accurately* — conditional on a truthful verdict.**
  - `readinessNotice.ts:241-254` — for a `degraded` verdict whose reasons include a reindex cause
    (`isReindexCause` matches Design A's `index.blocked_legacy`/`index.schema_mismatch`/
    `index.embedding_legacy`/`index.embedding_mismatch`), it returns headline *"Reindex required."* +
    body *"…results may be keyword-only."* — **honest keyword wording**, not "fully semantic."
  - `availability.ts:133-141` — the "What you can do right now" `documents` caveat reads the ONE
    verdict; BLOCKED_LEGACY's reason codes carry severity `warn`, so `calm=false` →
    *"Showing keyword-ranked results — semantic ranking is degraded"* — **also honest**. The
    "…results are complete, ranking may be simpler" string (`availability.ts:139`) is the
    `severity==='info'` branch (an *optional* cosmetic gap, e.g. LambdaMART off), and *"…results are
    still fully semantic"* (`readinessNotice.ts:261`) is the same `info` branch.
- **Therefore the over-claim can only appear if the verdict does NOT classify BLOCKED_LEGACY as a
  `warn` retrieval-degradation** — i.e. the verdict resolves to `info`-severity or to non-`degraded`
  (operational). That misclassification is **not** in 600's wording layer; it is upstream — the
  Worker compat gate / capability signal not reaching the verdict as a warn `retrieval` cause. Design
  A's own emit (`StatusLifecycleHandler.compatBlockedReason` → `index.blocked_legacy` on the
  `retrieval` composite) *does* produce a warn-degraded verdict **when the Worker reports
  BLOCKED_LEGACY to `CompatibilityStatusView`**. The sweep's over-claim is the case where that
  upstream signal disagrees with the actual query-time dense gate — **exactly 598 B-3** (the
  capability the UI trusts is the one that lies).

**Disposition correction for C-1.** 600's consumed wording is **already truthful by construction
when the verdict is truthful**; there is no 600 wording bug to fix against current `main`. C-1's only
genuine 600 action is a **post-598-B-3 verification** (confirm the banner + "What you can do right
now" read "keyword" honestly once 598's source reflects the BLOCKED_LEGACY gate) — plus an optional
regression test pinning "a warn-severity `index.blocked_legacy` verdict never yields a 'fully
semantic'/'results are complete' string" (audit-driven-fixes-need-test). **598 B-3 is confirmed NOT
implemented in `main`** (subagent: no code gates `embeddingCoverage`/AUTO-default/"fully semantic"
copy on the embedding-compat boundary; 598's reopen scope marks B-1/B-2/B-3/B-4 "design only"). So
C-1 stays **hard-blocked on 598 B-3**, and the tempdoc's own rule holds: *do not patch 600's copy
against the lying capability* — there is, in fact, nothing in 600 to patch.

## XII.2 — C-2: Design B IS emitting; the observed "every 5s" is a churn bug, not silent collapse

The reopen's scope question: *"confirm whether Design B's `monitor.unobservable` UNKNOWN condition is
actually being emitted … or whether it still collapses silently."* Verified — **it is wired and
emits; it does not silently collapse** — but the investigation found the symptom is mislocated and
there are **two NEW churn defects Design B itself introduced**, plus the unfixed root:

**(i) The emit path is present and correct (static, high confidence).**
`RuleRunner.evaluateRule` (`RuleRunner.java:155-165`): `evaluatePredicate` →
`CelEvaluator.evaluatePredicate` returns `PredicateOutcome.Indeterminate` on a
`MissingMetricException` (`CelEvaluator.java:197-207`); on `Indeterminate`, RuleRunner calls
`emitter.emitUnobservable(rule, reason)` and `scheduler.tick(..)` freezes — never RESOLVE-as-healthy.
`RuleEmitter.emitUnobservable` (`RuleEmitter.java:93-120`) upserts an `AssertedCondition(status=UNKNOWN)`
with id `monitor.unobservable`, INFO severity, via the existing `ConditionStore` → broadcast seam.
**So when the rule is blind, the blindness IS a first-class observable** — answering the scope
question in the affirmative.

**(ii) Live oracle (read-only, running `main` stack `aa7db3cc`, owned by another session):** the
`/api/health/events/stream` snapshot frame shows **`"conditions":[]`** — no `monitor.unobservable`
present. Read alongside (i), this means the memory-pressure rule is **not blind on this particular
run** (its RRD has the datasource → the predicate evaluates → no blind condition, correctly). The
blindness is the **stale-RRD edge** the sweep hit on a different run, not a constant. (Dev-runner log
tail returned empty for both stdout/stderr — same limitation PART V.6 noted — so the live check is
the conditions snapshot, not the WARN log.)

**(iii) NEW defect — the reason string embeds a per-tick time window, so when blind it CHURNS.**
`Signal.latest()` builds its `MissingMetricException` message as *"metric '…' has no recorded samples
in window [`start`, `now`]"* (`Signal.java:48-52`), where `start=now-300` and `now` advance **every
tick**. Two consequences, both real:
  - **CelEvaluator's "WARN-once" is defeated.** Its dedup key is `ruleName + ": " + missing.getMessage()`
    (`CelEvaluator.java:200`); because the message carries the moving window, the key is **new every
    tick** → the WARN logs **every 5s**. *This is the exact "every 5s" symptom the sweep observed* —
    it is the CelEvaluator log churn, NOT (as the reopen reads it) evidence that the blindness is
    unobserved.
  - **The blind CONDITION churns too.** `emitUnobservable` puts that same moving-window text into the
    condition's `message` (`RuleEmitter.java:108-109`). `ConditionStore.upsertAsserted` dedups on
    `status` + `reason` + **`message`** (`ConditionStore.java:164-170`); the message differs every
    tick → `MODIFIED` → a `CONDITION_MODIFIED` broadcast **every 5s** while blind. The emitter's own
    docstring claim ("Re-emitting an identical condition is a no-op via `ConditionStore` dedup",
    `RuleEmitter.java:90-91`) is **false for this path** — the condition is never identical. (The live
    oracle could not exhibit this because the rule isn't blind on that run.) These two are **600's
    own** — Design B authored the reason text and the WARN — and they are the right-sized,
    low-risk, symptom-fixing slice.

**(iv) The unfixed ROOT — RRD catalog-drift, silent by construction.** Why the rule goes blind at
all: `RrdMetricStore.initialize()` (`RrdMetricStore.java:161-164`) **opens an existing `metrics.rrd`
without reconciling datasources** — a file created by a prior boot with a smaller curated set lacks
the `head.jvm.memory.heap.used_bytes` datasource (the metric is in `LEGACY_CURATED_METRICS:86`, so a
*fresh* RRD has it; only a *stale* file is missing it). Then `record()` catches the resulting
"Datasource not found" and logs **DEBUG-only** (`RrdMetricStore.java:250-268`), and `query()` does
the same and returns null (`:323-334`) → `Signal.latest()` sees empty → `MissingMetricException` →
blind, **forever**, until uninstall+reinstall or `--clean`. The code comment itself names the fix as
"a future schema-bump migration" (`:256-257`) that was never built. The existing
`RrdMetricStoreCatalogDriftTest` only *pins the drift exception shape* (so the DEBUG catch keeps
matching) — it endorses the silence, it does not reconcile. **This is the producer-side fix C-2
narrows to** — and it is the only layer that makes the rule *not blind* (layers iii fix the noise of
*being* blind; iv removes the blindness).

## XII.3 — Critical assessment (questioning the reopen's framing, as authorized)

- **The reopen conflates the symptom with the gap.** It reads "UI Logs show the blind message every
  5s" as "the blind monitor still collapses silently / B's wiring may be incomplete." Verification
  shows the opposite: B emits correctly, and the every-5s log is a **dedup-defeated WARN** (layer
  iii), independent of whether the condition surfaces. The honest restatement: *B made blindness
  representable and it works; but (a) the representation is noisy when it does fire, and (b) the
  underlying sampling gap that makes the rule blind is unfixed.*
- **C-1 is not a 600 wording bug.** The reopen's "600 owns the consumed copy" is true in principle
  but, against `main`, 600's copy is already correct conditional on a truthful verdict; the over-claim
  is an upstream verdict/capability misclassification = 598 B-3. 600's residual collapses to a
  post-598 verification + an optional guard test. (Same shape as PART II's correction of §1: the
  stated data-gap was largely a projection/upstream issue.)
- **Scope/ownership of layer (iv).** It lives in `modules/telemetry` (shared substrate) and is, by
  the doc's own PART III §17 boundary, *"a separate, optional operational fix to the cause"* adjacent
  to **588**'s silent-failure class. So the genuinely-600 slice is the **diagnosability of the
  symptom** (layers i–iii: B already ships; the churn fixes complete it); layer (iv) is a
  telemetry/588-adjacent root fix that 600 may *adopt* but should not *assume*.

## XII.4 — Recommended disposition (decision input for the user, no code yet)

- **C-1 → close as deferred-to-598 (no 600 code).** Record that 600's wording is already truthful by
  construction; the only 600 action is a post-598-B-3 live verification (+ optional regression test).
  Keep it blocked; do not patch against the lying capability (nothing to patch).
- **C-2 → split into the two layers and pick scope:**
  1. **Churn fix (600's own, recommended, low-risk):** stabilize the blind-monitor reason so a blind
     rule logs/broadcasts once, not every 5s — drop the moving `[start, now]` window from the
     user-/condition-facing reason text (keep it in a DEBUG detail if useful), and make CelEvaluator's
     WARN-once key stable (rule+metric, not the windowed message). Fixes the exact observed symptom.
     Tests: a `CelEvaluatorTest`/`RuleEmitter`/`ConditionStore` assertion that N consecutive blind
     ticks produce ONE WARN + at most one `CONDITION_ADDED` (no `MODIFIED` churn).
  2. **RRD reconciliation (root; telemetry/588-adjacent):** on opening an existing `metrics.rrd`,
     detect curated metrics missing a datasource and migrate (RRD4J add-datasource / rebuild), so the
     rule stops being blind at the source. Higher blast radius (shared substrate); the open decision
     is whether 600 adopts it or routes it to 588.
- The §6/§13 honesty note still holds: the live degraded-state and live-blind repro both need a
  controlled stale-RRD / BLOCKED_LEGACY environment (the 598 catch-22); they are the pre-merge
  oracle steps, not blockers to authoring the churn fix.

> **Verification honesty.** XII.1–XII.2 layers i, iii, iv are source-verified against `main`
> (file:line cited). Layer ii is a single read-only live snapshot (`conditions:[]`) on a stack I do
> not own — it confirms "not blind on THAT run," not "B fires when blind" (which is established
> statically). The live "fires-when-blind" + "churn-while-blind" repro requires a forced stale-RRD,
> deferred to a controlled pre-merge run.

---

# PART XIII — LONG-TERM DESIGN for the reopened residuals (2026-06-17)

> Genre: design-theory (general, not implementation-level), the round PARTS III/IX did for the
> original work — now for the two REOPENED residuals (C-1, C-2) after the PART XII verification
> re-shaped both. Method: read the adjacent docs that could own pieces of this (**604**
> diagnostic-surface SSE liveness — explicitly scopes 600 OUT; **588** worker-loop silent-failure —
> closed, worker-scoped; **598** the capability source — open, B-3 unimplemented; **595/596** the
> verdict/wording authorities — merged), confirmed the RRD migration primitive exists (RRD4J
> `RrdToolkit.addDatasource` copies all data; cannot run on a live file). The design **extends
> existing authorities** and adds **no** new framework, register, or gate — the problem does not
> require one.

## XIII.1 — Design thesis (one paragraph)

Both reopened residuals are, again, the **595 epistemic law one layer further down**: *the signal a
consumer trusts must derive from the single authority that actually knows the fact, and its identity
must be the stable fact itself — never a proxy for it, never a momentary measurement of it.* **C-1**
is a *proxy* masquerading as the fact: the UI asserts "fully semantic" from a capability signal
(`embeddingCoverage`) that is not the authority deciding whether dense actually runs (the Worker
embedding-compat boundary). **C-2** is a *drifted materialization* masquerading as the declared
schema (a stale on-disk RRD used as-if-current, so a curated metric is silently never recorded) **and**
a *momentary measurement* masquerading as a stable fact (the blind-monitor reason carries a moving
time-window, so the identity of "metric X has no samples" churns every tick). The correct long-term
structure in each case is **collapse to the single authority** (C-1: the capability the query gate
uses is the capability the verdict and wording read) and **identity-is-the-stable-fact** (C-2:
reconcile the materialization to the declared schema on open; key the observable on the fact, not the
measurement). No residual needs a new authority — each is a *collapse* or a *reconciliation* of
representations that already exist.

## XIII.2 — What already exists to extend (inventory)

- **The single verdict + `CAUSE_ROWS` wording authority (595/596, merged).** `computeVerdict`
  derives the one `SystemHealthVerdict`; `readinessNotice`/`availability` CONSUME it and word it from
  the one vocabulary. **C-1's wording is already correct conditional on a truthful verdict** (PART XII)
  — so C-1 needs *nothing new here*, only that the verdict it consumes be truthful.
- **The Worker embedding-compat boundary is the real capability authority.** `EmbeddingCompatibilityController`
  (BLOCKED_LEGACY/…) already decides whether dense is served, and Design A already routes it onto the
  `retrieval` composite as a reason code. The gap is purely that the *other* capability signal the FE
  trusts for the AUTO default + positive copy (`embeddingCoverage`) is a **different, non-authoritative**
  number — **598 B-3's source defect**, not a 600 structure.
- **The RRD store already DERIVES the declared curated set.** The catalog-aware constructor (417 Phase 3b,
  `RrdMetricStore.java:112-133`) computes `curatedMetrics` from every catalog's `archivedTo(...)` + the
  legacy list. **The store already knows the declared schema** — it simply does not reconcile the
  on-disk file to it on open. The reconciliation design is *closing a loop the store is one step short
  of*, not new machinery.
- **RRD4J ships the migration primitive.** `RrdToolkit.addDatasource(src, dst, DsDef)` creates a
  reconciled file preserving all existing data (and an in-place variant with `.bak`). Constraint: it
  must run on a *closed* file — so reconciliation belongs in `initialize()` before the `RrdDb` is
  opened (or close→migrate→reopen). No external dependency, no new abstraction.
- **The blind-monitor observable (Design B, merged) is the right *backstop*.** It correctly surfaces a
  rule that cannot evaluate. The design **keeps** it — for the *transient* blindness reconciliation
  cannot prevent (cold start before the first archived sample). It does not get removed; it gets made
  *quiet* (XIII.4-B) and *demoted to the residual case* (root cases vanish once reconciliation lands).

## XIII.3 — Design for C-1 — collapse to one capability authority; 600 contributes only a tone-honesty invariant

**Root design error (owned by 598).** "Is semantic search working" has **two representations**: the
Worker compat boundary (the authority that actually gates dense) and `embeddingCoverage` (a coverage
ratio that ignores the BLOCKED_LEGACY gate). The FE's AUTO default + positive copy read the second;
the verdict reads (via Design A) the first. When they disagree, the surfaces disagree (B-4) and the
copy over-claims (C-1). **The correct long-term structure is the 598-R1 thesis actually honored: the
pipeline shape AND the "is it semantic" copy are projections of the ONE capability the Worker owns —
the embedding-compat boundary — not a parallel coverage proxy.** That collapse is 598 B-3's to build;
it removes C-1's cause at the source.

**600's genuine residual is a single structural defense, not a new authority.** 600 owns the consumed
wording; its long-term contribution is a **tone-honesty invariant: a positive-capability assertion
("fully semantic" / "results are complete") must be *unrepresentable* whenever the verdict it is
projecting carries a retrieval-degrading cause.** Today that mutual-exclusion holds only *by the
severity arithmetic happening to be right* (warn→keyword copy, info→"fully semantic"); it is not
*guaranteed*. Making it an invariant — the same kind 595 §10.3 already states in prose — means a
future verdict mis-severity can never silently turn into an over-claim *in 600's layer*; the only way
to get the over-claim is an upstream-untruthful verdict, which is then unambiguously 598's. This is a
**guard over the existing single-vocabulary projection**, not a second authority: it asserts the
*relationship* (positive copy ⊥ retrieval-degrading cause), which the merged `CAUSE_ROWS`/verdict
structure already has all the inputs for. Scope: one invariant + its test; **no** new capability
signal, **no** new wording channel, **no** gate (the existing `verdict-derivation` gate already
confines the derivation locus).

**C-1 disposition.** Stays hard-blocked on 598 B-3 (the source collapse). 600's design deliverable is
the tone-honesty invariant + a post-598 live verification that the banner/affordance read "keyword"
honestly when BLOCKED_LEGACY. No 600 wording rewrite — there is nothing in 600 to patch until the
verdict it consumes is truthful.

## XIII.4 — Design for C-2 — reconcile the schema (root), key the observable on the fact (churn), keep B as backstop

C-2 is three layers; the design treats them as **one coherent structure**: *don't be blind; if blind,
say so; say it once.*

**A — Schema reconciliation at the store (the root; removes permanent blindness).** The curated metric
set is a **declared schema**; the on-disk `metrics.rrd` is a **materialization**; the long-term rule
is **opening a schema-bearing artifact reconciles it to the declared schema, not just opens it.**
`RrdMetricStore.initialize()` already holds the declared set (`curatedMetrics`) and can read the
on-disk datasource set; the design closes the loop — on open, datasources present in the declared set
but absent on disk are added via the RRD4J migration (on the closed file, preserving data), so a
curated metric can never be *permanently* unrecorded due to a prior-boot schema drift. This fixes the
silent drift for **every** curated metric and **every** rule, not just memory-pressure — the defect is
in the substrate, so the fix is in the substrate. It is the same *discipline* the codebase already
applies manually for the Lucene schema (the "stale index after field changes / rebuild required"
pitfall); the RRD case can be **automatic** precisely because the store already derives both sides of
the correspondence. The teeth are a regression test (stale-RRD-missing-a-datasource → after
`initialize()`, the datasource exists and records), per `audit-driven-fixes-need-test` — **not** a
gate (there is no ongoing vocabulary to keep in correspondence; it is a one-time correctness loop-close).

**B — The observable's identity is the stable fact, not the momentary measurement (kills the churn).**
The structural defect behind the every-5s log AND the every-5s `CONDITION_MODIFIED` is the same:
`Signal.latest()` builds its missing-metric reason with a moving `[start, now]` window, and that
volatile string flows into two **dedup-sensitive identities** — CelEvaluator's WARN-once key and
`ConditionStore`'s `(status, reason, message)` dedup — defeating both. The long-term rule:
**a reason/identity that asserts a stable fact ("metric X has no samples") must be stable for as long
as the fact holds; volatile detail (the exact window) is non-identity metadata (a DEBUG log line), never
part of the dedup key or the user-facing reason.** Fixing it at the single source (the missing-metric
reason carries the metric name, not the windowed sentence) repairs *both* consumers at once — the
WARN fires once per {rule,metric}, and the blind condition is `CONDITION_ADDED` once and then
`UNCHANGED` until it clears. This is a small, correct cut, not an abstraction; it also *restores* the
`ConditionStore`/CelEvaluator dedup contracts to the behavior their own docstrings already promise.

**C — Keep Design B as the transient backstop (demoted, not removed).** Once (A) lands, *permanent*
blindness from schema drift is gone; what remains is *transient* blindness (cold start before the
first 60s archived sample) — exactly the case Design B's `monitor.unobservable` UNKNOWN condition is
right for, now quiet via (B). So the three layers compose: **A** removes the common real cause, **C**
keeps the honest backstop for the residual case, **B** makes that backstop fire once. The blind-monitor
is not redundant with reconciliation — it covers what reconciliation structurally cannot (a genuinely
empty window), which is the correct division.

## XIII.5 — Why not the alternatives (each rejected for a concrete reason)

- **C-2: "make the drift LOUD instead of fixing it" (PART VIII E2 — an `absent()`-style
  expected-metric observable).** Rejected as the *primary* move: it adds a second observability path
  to *announce* a metric we could simply *record*. Design B already surfaces the *symptom* (blind
  rule); adding a parallel "metric absent" signal is more telemetry about an avoidable gap. Reconcile
  (A) removes the gap; B backstops the residue. (If a future need arises to assert "this metric MUST
  be present" independent of any rule referencing it, E2 is the escalation — named, not built.)
- **C-2: "just bump the DEBUG drift log to WARN."** Rejected: it makes the *substrate* noisy without
  fixing the blindness (the metric is still unrecorded, the rule still blind) — the symptom-not-root
  anti-pattern. The store knows the declared set; it should reconcile, not merely complain.
- **C-2: a rule-engine V2 / per-metric SLO / "metric health dashboard."** Rejected as speculative
  abstraction for cases the problem does not include — one reconciliation loop + one identity fix is
  the whole requirement.
- **C-1: "600 defends by re-reading the Worker compat block directly / hard-coding keyword copy when
  BLOCKED_LEGACY."** Rejected: it re-forks the capability signal FE-side (the very split 598 B-3 must
  collapse) and duplicates the authority. 600 consumes the verdict; the fix is the verdict's truth
  (598), plus 600's *invariant* that it cannot over-claim relative to whatever verdict it is given.

## XIII.6 — Scope discipline — what this design deliberately does NOT add

- **No new authority, register, or gate.** C-1 = a tone-honesty invariant over the merged
  verdict→wording projection. C-2 = a reconciliation loop the store is one step short of + a
  stable-identity fix. Tests are the teeth in both; neither needs ongoing-correspondence governance.
- **No second capability signal and no remedy/procedure text on the wire** (the PART II/III rejections
  stand).
- **No removal of Design B** — it is demoted to the transient backstop, not deleted.
- **No structure for 604's SSE-liveness or 598's source/durability** — those are owned elsewhere
  (XIII.7); 600 consumes their outcomes.

## XIII.7 — Ownership & boundaries (so nothing is double-owned)

- **C-1 source → 598 B-3** (collapse `embeddingCoverage`/AUTO-default/positive-copy onto the Worker
  embedding-compat boundary). **C-1 guard → 600** (the tone-honesty invariant + post-598 verify).
- **C-2 root (A) → telemetry substrate** (`RrdMetricStore` reconciliation). 600 owns the *requirement*
  ("a health rule must not be *permanently* blind from silent schema drift") and motivates the fix;
  the code lives in `modules/telemetry`. **588** (closed, worker-*loop* silent-failure) is the same
  *principle* but a different subject — not its home; **604** (SSE diagnostic-surface liveness)
  explicitly scopes 600 out — not its home either. So 600 is the correct driver.
- **C-2 churn (B) + backstop (C) → 600** (the rule-engine observability it already owns via Design B,
  in `app-services/.../observability/rules/**` + `Signal`).

## XIII.8 — Open decisions for the user

1. **C-2 root (A) placement.** Build RRD schema-reconciliation in `RrdMetricStore` under 600's
   requirement (recommended — the store already derives the declared set; the fix is one closed-loop),
   vs. spin a dedicated telemetry tempdoc for it. (Recommend: keep under 600 as the driver, code in
   telemetry, since C-2 is its reopened item.)
2. **C-1 invariant strength.** A test-enforced tone-honesty invariant (recommended — cheap, pins
   §10.3) vs. leaving C-1 a pure post-598 *verification* with no 600 code. (Recommend the invariant —
   it makes the over-claim unrepresentable in 600's layer regardless of future verdict-severity edits.)
3. **Sequencing.** C-2 (A+B+C) is **independent of 598** and can land now; C-1 is **gated on 598 B-3**.
   Confirm C-2-first (recommended), with C-1's verify scheduled once 598 B-3 merges.

> **Verification honesty.** XIII rests on source-verified facts (the verdict/wording chain, the
> store's catalog-derived curated set, the `Signal` window, the dual dedup defeat — all read against
> `main` in PART XII) and one external-API confirmation (RRD4J `RrdToolkit.addDatasource` semantics +
> the closed-file constraint, web-verified). The design is general per request; the live
> stale-RRD + BLOCKED_LEGACY repros remain the pre-implementation oracle steps.

---

# PART XIV — USER-FACING DESIGN for the reopened residuals (2026-06-17)

> Genre: user-facing design (general — placement/tone/wording/visibility rules, not pixels), the
> round PARTS IV/X did for the original work, now for the reopened C-1/C-2 designs (PART XIII).
> Method: live browser inspection of the running `main` stack + reading the three render sites
> (`readinessNotice.ts`, `CapabilityMap.ts`/`availability.ts`, the `healthEventActivityRow` strategy +
> `present.ts` display path) and the message catalogs — not judging from the tempdoc. The live pass
> surfaced a UX consequence the source reads alone would have missed (the diagnostic surface that
> hosts C-2 was itself wedged).

## XIV.1 — Which parts of the design are user-visible

- **C-1 (tone-honesty invariant) — directly user-visible on THREE placements.** (1) the **search
  degradation banner** (`readinessNotice` → headline · body · `degradation-causes` slot · remedy
  button); (2) the **"What you can do right now"** panel (`jf-capability-map` rows = capability ·
  status text · remedy — `CapabilityMap.ts:133-155`, fed by `availability.ts`); (3) by extension the
  **AI-Brain** semantic-status line (the B-4 cross-surface disagreement). The invariant governs which
  *words* the user reads in the BLOCKED_LEGACY state.
- **C-2 layer B (stable identity) + layer C (keep the backstop) — directly user-visible, narrow.** The
  `monitor.unobservable` condition renders in the Health **conditions / "RECENT EVENTS"** list (via
  `<jf-health-event>` → `healthEventActivityRow`). Layer B changes how *often* it re-renders; the
  display wording decides *what* the user reads.
- **C-2 layer A (RRD reconciliation) — NOT directly user-visible**, but indirectly: it makes the
  rule actually evaluate, so memory-pressure can surface a *real* condition when warranted, and the
  blind row stops appearing in the common (stale-RRD) case — i.e. it changes the *frequency* of the
  C-2 row to "rare."

## XIV.2 — What the live inspection showed (don't judge from the tempdoc)

Captured against the running `main` stack (UI :5173; backend READY), plus the render code + catalogs:

- **The diagnostic surface that hosts C-2 was itself WEDGED.** Both **System (Health)** and **AI
  Brain** rendered only *"Loading System…/Loading AI Brain…"* with the bottom status deck showing a
  persistent amber **"Reconnecting…"** pill while the backend was READY (doc-count `620`, `55.7 MB`
  visible). This is the **604** SSE-wedge live. **UX consequence for 600:** C-2's blind-monitor
  observable lives ONLY in the Health conditions list, so during the exact SSE failures where an
  operator would open Health, the row is unreachable. C-2's *value* is therefore gated on 604's
  surface-liveness (a dependency to record, not for 600 to fix). The always-visible **status deck**
  rendered fine — but it carries the verdict pill, not conditions, so a blind monitor never reaches it
  (correct — blindness must not escalate the verdict).
- **C-2's current render is a confirmed Nielsen-#9 double violation (source + catalog verified).**
  `healthEventActivityRow.ts:124-127`: the row **title** is `present({kind:'resource', key:
  event.i18nKey}).label || event.id`; the i18nKey `health-events.monitor.unobservable.message` exists
  in **no** catalog (FE or backend — grep: only the emitter, tests, observations.md), so the title
  **falls back to the raw id `monitor.unobservable`**. The **message** (`bodyToMessage` →
  `c.message`) is *"Health rule 'memory-pressure' cannot evaluate: metric
  head.jvm.memory.heap.used_bytes has no recorded samples in window […]"* — exposing the **internal
  metric name** and the **moving window** (the same volatile string that drives the churn, XIII.4-B).
  So today a user sees a raw id + an internal metric name + churning timestamps. (Already logged:
  `observations.md:474`.)
- **C-1's wording sites are honest *given a truthful verdict*** (re-confirmed live-readable in code):
  banner reindex branch → *"…results may be keyword-only"*; CapabilityMap `documents` warn branch →
  *"Showing keyword-ranked results — semantic ranking is degraded"*. The over-claim strings
  (*"results are still fully semantic"* `readinessNotice.ts:261`; *"results are complete, ranking may
  be simpler"* `availability.ts:139`) are the `severity:'info'` branch — reachable only when the
  verdict mis-classifies BLOCKED_LEGACY as info/operational (the 598-B-3 upstream defect).

## XIV.3 — User-facing design for C-1 (the invariant is a *negative* guarantee, not new copy)

The shipped wording is already correct; the user-facing deliverable is the **guarantee that the
over-claim cannot render**, expressed in user-facing terms:

1. **One honesty rule, three placements.** Whenever the verdict carries a **retrieval-degrading**
   cause (severity `warn`/`error` on the `retrieval` dimension), the banner body, the CapabilityMap
   `documents` status text, **and** the AI-Brain semantic line must all word the **keyword-fallback
   reality** ("results may be keyword-only" / "keyword-ranked"). The positive strings ("fully
   semantic", "results are complete") are reserved for the genuinely-cosmetic `info` case (e.g.
   LambdaMART off, where retrieval *is* still semantic). No new copy — the rule is that the *existing*
   positive copy is **unrepresentable** alongside a retrieval-degrading cause.
2. **Cross-surface agreement (closes B-4 for the user) — REFINED by PART XV confidence pass.** The
   investigation found AI-Brain does **not** read the verdict at all: `BrainSurface.ts:1452-1453/1490/
   1502` reads `embedding.compatState` / `schema.compatState` / `schema.reindexRequiredReason`
   **directly** from the raw `/api/status` snapshot, so in BLOCKED_LEGACY it is the **more accurate**
   surface ("Vector and hybrid search are disabled until you rebuild"). The B-4 disagreement is
   therefore "AI-Brain right (direct compat) vs Search/Health wrong (verdict mis-severity via 598)".
   So cross-surface agreement is reached by making the **verdict truthful (598 B-3)** so Search/Health
   catch up to AI-Brain — **NOT** by refactoring AI-Brain onto the verdict (a bigger change 600 should
   not take). 600's contract is the negative guarantee (item 1) on the verdict-consuming surfaces
   (banner + CapabilityMap); AI-Brain's direct-compat read is a legitimate deep-diagnostic choice left
   as-is. (Aligning AI-Brain onto the one vocabulary, if ever wanted, is 596/598 work, not 600's.)
3. **Tone stays calibrated to severity (595 §10.3).** BLOCKED_LEGACY is `warn` (impairing, not broken)
   — calm-but-accurate "keyword-only", never an `error` "search is broken" nor an `info` "fully
   semantic". The user reads *what changed* ("built before semantic search existed") in plain terms,
   never the internal code.
4. **No user-facing change until 598 B-3.** Because the wording is already honest given a truthful
   verdict, the user sees nothing new from 600 until 598's source makes the verdict truthful; then the
   *same* honest copy simply renders in the state where today the over-claim leaks. 600's job is to
   make sure that, when it does, it cannot be an over-claim.

## XIV.4 — User-facing design for C-2 (a rare, plain, calm, stable diagnostic note)

A blind self-check is an **operator-tier diagnostic**, not an end-user alarm. Post-XIII.4-A
reconciliation it is also **rare** (cold-start only), which sets the right investment level: word it
well, don't build a feature around it.

1. **Plain language, no raw id, no internal metric name (Nielsen #9).** Give `monitor.unobservable`
   a display-catalog entry so the title is human ("Self-check unavailable" / "A health check can't
   read its data yet") and the message names the *check* in user terms ("the memory-pressure check"),
   **not** `head.jvm.memory.heap.used_bytes`. The internal metric name + window belong in a DEBUG log,
   never the rendered row. (This is the production-path fix for `observations.md:474`; the friendly
   wording currently exists only on the `?lit-health=1` debug path.)
2. **Stable identity = a calm, non-churning row (the user-facing face of XIII.4-B).** Because the
   reason no longer carries a moving window, the row is **added once and sits still** until it clears —
   it does not re-sort/re-render every 5s. The churn fix is not only a backend dedup correctness fix;
   its *purpose* is user-facing calm: a diagnostic note that flickers every tick reads as a recurring
   incident when it is one stable fact.
3. **Honest epistemic framing — "we can't tell", not "something is wrong".** The wording conveys *we
   don't currently know this signal* (calm, factual), distinct from both "memory is bad" and "memory
   is fine". This is the whole point of Design B at the user layer: the product stops silently
   implying all-clear, without manufacturing anxiety.
4. **Demoted placement, never escalates.** INFO severity; lives in the Health conditions/RECENT
   EVENTS list only; **never** the top verdict pill, **never** the search degradation banner, **no**
   remedy button (there is no user action for "a check can't read its data" — it self-clears when
   samples return, or XIII.4-A fixes the root). Structurally guaranteed: `computeVerdict` ignores
   conditions, so a row of any severity cannot move the verdict.
5. **Visibility honesty (the live finding).** C-2's row is only as reachable as the Health surface,
   which can wedge (604). 600 records this dependency; it does not absorb 604's fix. (It also argues
   *against* over-investing in the C-2 row's prominence — the reliable always-on signal is the verdict
   deck, which correctly stays calm during blindness.)

## XIV.5 — Net user-facing shape & what is deliberately NOT added

- **No new surface, no "system self-check" screen** (PART VIII N1 stays an idea) — C-2 is a
  display-catalog label + a stable row; C-1 is a guard over existing copy.
- **No new alarm, tone, or banner for the blind monitor**; no remedy button for blindness.
- **No new C-1 copy** — the design is the *negative* guarantee that the existing positive copy can't
  render alongside a retrieval-degrading cause, plus cross-surface agreement.
- **No end-user exposure of internal identifiers** (`monitor.unobservable`, metric names, windows) —
  the §10.3 / Nielsen-#9 plain-language rule applied to both residuals.

> **Verification honesty.** XIV.2's wedge is a direct live observation (Health/Brain stuck "Loading…"
> + persistent "Reconnecting…" with backend READY); the C-2 raw-id/raw-metric render is
> source-+catalog-verified (the i18nKey resolves nowhere) rather than captured in the degraded visual
> (the live stack was wedged and is owned by another session, so the conditions list could not be
> driven to the blind state — consistent with PART VII/X, which reproduced these via injection). The
> degraded-banner and CapabilityMap honest/over-claim branches are source-verified. Capturing the
> after-state visuals (plain blind row; honest BLOCKED_LEGACY copy) is the pre-merge UX-audit step
> (559 §6 measured audit), once the designs are wired and a controlled stale-RRD/BLOCKED_LEGACY
> environment is available.

---

# PART XV — PRE-IMPLEMENTATION CONFIDENCE (reopen, 2026-06-17)

> Genre: pre-implementation de-risking for the reopened C-1/C-2 designs (NO feature code — the only
> edits are this section + the XIV.3 refinement; two throwaway spike tests were written, run green, and
> reverted). Method: three parallel source-cited Explore audits + two scratch experiments against the
> real toolchain (an RRD4J reconciliation spike in `modules/telemetry`; a blind-monitor churn repro in
> `modules/app-services`), each run and then deleted. Goal: convert the PART XIII/XIV assumptions into
> verified facts before implementation. Net: the one load-bearing unknown (RRD reconciliation) is
> mechanically proven, the churn fix-shape is demonstrated, and the C-1 scope **shrank** (AI-Brain is
> not a refactor target).

## XV.1 — Findings per track

- **A — RRD reconciliation is mechanically sound (rrd4j 3.10); the one unproven mechanism RETIRED.**
  `RrdToolkit` is used nowhere in-repo, so a throwaway spike exercised it directly. rrd4j 3.10 exposes
  **`RrdToolkit.addDatasources(String path, Iterable<DsDef>, boolean saveBackup)`** — an **in-place,
  MULTI-datasource** add with an optional `.bak` (no temp-file/rename juggling). The spike built a
  stale RRD (datasources `a,b` only) + the production 3-archive shape, wrote samples, detected the
  missing set by diffing `RrdDb.getDsNames()` against the declared target `{a,b,c,d}`, ran
  `addDatasources(..., true)` on the **closed** file, reopened, and **all four assertions passed**:
  (a) all 4 datasources present, (b) the pre-existing `a` samples **survived** the migration, (c) the
  new `c` is writable + queryable with **no "Datasource not found"**. `DsDef(name, GAUGE, 180, 0, NaN)`
  round-trips the production params. Honest nuance: a *single* fresh sample on the new DS fetches as
  `NaN` until the 5-min archive bucket consolidates — the same "needs a few samples" latency the store
  already has; the datasource exists and accepts writes, which is the point. **Conclusion: the
  detect→diff→`addDatasources`→reopen loop in `initialize()` is feasible and data-preserving.**
  Constraint confirmed (web + spike): migration must run on a **closed** file → it belongs in
  `initialize()` before the `RrdDb` is opened.
- **B (churn) — reproduced AND fix-shape demonstrated.** A scratch repro drove the real
  `RuleRunner`→`CelEvaluator`→`RuleEmitter`→`ConditionStore` path blind for 4 ticks with the clock
  advancing 5s each: **1 `CONDITION_ADDED` + 3 `CONDITION_MODIFIED`** — a churn broadcast **every blind
  tick after the first**, because `Signal.latest()`'s message embeds the moving `[now-300, now]`
  window. A second case emitted `RuleEmitter.emitUnobservable(rule, <stable reason>)` three times
  across an advancing clock → **exactly 1 broadcast total** (2nd/3rd dedup to `UNCHANGED`). So
  stabilizing the reason at the `Signal` source provably kills both the WARN-every-5s and the
  `CONDITION_MODIFIED` churn. (The blind path *firing* was already covered by the existing green
  `RuleRunnerTest.missingMetricEmitsBlindConditionNoFire` — sub-question 1 needed no new test.)
- **B/C blast radius — SAFE.** `MissingMetricException.getMessage()` is read only by `CelEvaluator`
  (dedup key `:200`, WARN `:204`, `Indeterminate` reason `:206`); no parser depends on the text, and
  the reason flows verbatim to the user-facing condition. Dropping the window is low-risk.
- **C (display label) — clear, no codegen.** The friendly title is a hand-edited backend properties
  file `modules/app-api/src/main/resources/messages/health-events.en.properties` (per-id
  `.label`/`.message`, ICU allowed; `health-events.memory.pressure.*` is the precedent), served by
  `MessageCatalogRoutes`, resolved FE-side by `present()`/`localizeResourceKey`. Add
  `health-events.monitor.unobservable.{label,message}`; picked up on restart, no regen. Note: the
  *title* comes from this catalog via the event's `i18nKey`; the *message* still comes from the
  emitter's reason — so a fully-plain row needs the catalog label **and** the layer-B stable message,
  coordinated.
- **C-1 cross-surface — SCOPE SHRANK (folded into XIV.3).** AI-Brain reads
  `embedding.compatState`/`schema.compatState`/`schema.reindexRequiredReason` **directly** (not the
  verdict; `BrainSurface.ts:1452-1453/1490/1502`) and is the *more accurate* surface in BLOCKED_LEGACY.
  Cross-surface agreement comes from 598 B-3 making the verdict truthful so Search/Health catch up to
  AI-Brain — **not** from refactoring AI-Brain. C-1 = a negative-guarantee invariant on the two
  verdict-consuming surfaces + a post-598 verify; no AI-Brain change.
- **RrdMetricStore blast radius — CONTAINED.** One production construction site (`LocalTelemetry.java:113`),
  used by BOTH head (`metrics.rrd`) and worker (`metrics-worker.rrd`); `initialize()` is lazy from
  `record()` (`:219`). ~30–35 datasources, ~200–300 KB file → migration copy cost trivial. A
  reconciliation in `initialize()` touches both processes via one code path.

## XV.2 — Residual risks (bounded)

- **Worker-process parity / lazy-init wiring.** Reconciliation in `initialize()` runs in BOTH head and
  worker; the spike proved the toolkit, not the wiring in `LocalTelemetry`'s lazy-init order. The
  implementing test must assert reconciliation fires on the real `RrdMetricStore.initialize()`
  open-existing branch, not just the toolkit in isolation (`audit-driven-fixes-need-test`).
- **`.bak` accumulation / IO failure mid-migration.** `addDatasources(..., true)` writes a `.bak`; the
  implementation must handle cleanup + treat a migration IO failure as best-effort (telemetry is
  already WARN-not-throw) so a failed reconcile never blocks startup.
- **Consolidation latency is not a regression.** The `NaN`-until-consolidated behavior is pre-existing;
  the after-state test asserts the datasource EXISTS + accepts writes, not immediate read-back.
- **Live after-state UX capture still pending** (the blind row's plain wording; honest BLOCKED_LEGACY
  copy) — needs a controlled stale-RRD / legacy-index environment (the 598 catch-22), a pre-merge
  UX-audit step, not a blocker to authoring.

## XV.3 — Confidence rating

**8.5 / 10 for the remaining C-2 work; C-1 unchanged at "blocked on 598; trivial guard when unblocked."**
The biggest unknown (RRD reconciliation in rrd4j 3.10) is now spike-proven with the exact API
(`addDatasources` in-place multi + `.bak`), the churn fix-shape is demonstrated (stable reason →
`UNCHANGED`), every blast radius is enumerated and small, and the display-label + cross-surface
questions are settled (no codegen; no AI-Brain refactor). Deductions: the `LocalTelemetry` lazy-init
wiring + `.bak`/IO-failure handling need care in the real `initialize()` (the one spot a subtle bug
could hide), and the live after-state UX capture is still pending. Nothing blocks implementation when
the user chooses; all residuals are named and bounded.

> **Verification honesty.** XV.1-A/B rest on throwaway tests actually compiled + run green against the
> real rrd4j 3.10 jar and the real rule-engine classes (then reverted — experiments, not deliverables).
> The Explore-sourced facts (blast radius, properties workflow, AI-Brain signal) carry file:line. The
> only unverified-live items are the explicitly deferred ones: the reconciliation wired into the real
> `LocalTelemetry`/`initialize()` path, and the degraded/blind after-state visuals.

---

# PART XVI — CROSS-TEMPDOC INTERFERENCE (reopen second pass, 2026-06-17)

> Scope: tempdocs within 20 of 600 (580–620) modified in the last 5h, plus active worktrees in range.
> Question: could current `tempdocs/` work **long-term interfere** with 600's remaining work — C-2
> (Layer A `modules/telemetry/RrdMetricStore.java`+`LocalTelemetry.java`; Layer B
> `app-services/.../observability/rules/{Signal,CelEvaluator,RuleEmitter}.java`; Layer C
> `app-api/.../messages/health-events.en.properties` + the `healthEventActivityRow` render) and C-1
> (the tone-honesty invariant on `readinessNotice.ts`/`availability.ts`, gated on 598 B-3).

**Live state.** `git worktree list` → **no active worktrees** (only `main`). Recently-modified
tempdocs in range (mod < 5h): 593, 595, 597, 598, **600**, 602, 603, 604, 605 — with 598/602/603/605
edited in the last ~10 min by other sessions. **`git status` shows ONLY tempdoc `.md` files modified —
ZERO in-flight code edits.** So all sibling work is in the design/problem-statement phase: **there is
no current file-level collision risk**; interference here is about *future* work these docs design.

## XVI.1 — C-2 (telemetry + rule engine + health-event render): CLEAN, build-now-safe

- **Layer A (`modules/telemetry`) — no sibling touches telemetry.** Grep of 602/604/605 (and the
  others) finds no `RrdMetricStore`/telemetry edit scope. The only mentions are 600's own reopen being
  *referenced* by 602/598. **Interference: NONE.**
- **Layer B (`observability/rules`) — no sibling touches the rule engine.** 602 mentions "the
  CelEvaluator/RRD backend" only to *attribute it to 600's reopen scope* (602 §1, line 517), not to
  edit it. No active worktree, no in-flight code. **Interference: NONE.**
- **Layer C (`health-events.en.properties` + `healthEventActivityRow`) — no sibling edits these.** One
  adjacency: **605** (agent-window) wants to extend `present.ts` to return a `description`
  (`present.ts:111-120`) — a **hot shared file** — but C-2 Layer C does **not** edit `present.ts`
  (the label resolves via the existing `localizeResourceKey`→catalog path; I only add a catalog
  entry). So no collision. **Interference: NONE (present.ts noted as a 605-hot file I don't touch).**
- **Visibility dependency (not a collision): 604.** C-2's `monitor.unobservable` row renders only in
  the Health conditions list, which **604** (diagnostic-surface SSE liveness, still *undesigned* —
  problem-statement only) will fix for the "Reconnecting…" wedge. 604 owns the SSE/connection-
  re-establishment layer (`HealthEventStreamController`/EventSource reconnect), **not** the per-event
  render strategy or `present.ts`, so no file overlap — but C-2's row is only *as reachable as* the
  Health surface 604 unwedges (recorded in XIV.4-5). **A value dependency, not interference.**

**Verdict: C-2 (A+B+C) can be implemented now with no contention** — it lives entirely in
telemetry + the rule engine + the health-event catalog, none of which any active sibling edits.

## XVI.2 — C-1 (`readinessNotice.ts`/`availability.ts`): hard-coupled to 598, complementary not conflicting

**598 is actively redesigning the exact files C-1's invariant lives on.** 598's reopened design
(now ~2090+ lines) plans to **re-key `readinessNotice`'s three branches onto a different axis**
(598:1863 — capability/dense-serviceable rather than severity) and touches `availability.ts`'s
affordances (598:1776/1931) and `verdict.ts`. So C-1's tone-honesty invariant cannot be authored
against today's branch structure — it would be reshaped under it. **This is the sequencing dependency
made concrete: C-1 must follow 598's `readinessNotice` re-key, not just 598 B-3 generally.**

598's own §84 interference map agrees and is mutually consistent: it classes 600 as
**"the must-co-land coordination (complementary, not conflicting)"**, 598=SOURCE / 600=COPY, "given a
truthful verdict the three render sites already word BLOCKED_LEGACY honestly." It also names one
**concrete coupling onto 600's *shipped* work**: 598's new "map compat `UNAVAILABLE` → dense-blocking
reason" code **must** get a `CAUSE_ROWS` row + a `governance/readiness-reason-codes.v1.json` entry, or
it **trips 600's already-merged `check-readiness-reason-codes` gate** (PART IX/X). So 598's source
work interacts with 600's shipped gate — co-land coordination, not conflict.

## XVI.3 — The other siblings: no interference

- **602 (residual FE reliability)** — its R6 reason-code work builds a **sibling** gate (clones
  `check-readiness-reason-codes.mjs`) and adds a **separate search category**, explicitly NOT editing
  600's readiness vocabulary/gate ("without merging the two vocabularies", 602 §1). Shared file is the
  merged search catalog only, which C-2 doesn't touch. **LOW/NONE.**
- **595 (verdict authority)** — foundation C-1 consumes via the `verdict-derivation` seam; merged, its
  remaining work is the Stability-axis substrate (different layer). If it reshapes the verdict reason
  shape, C-1 tracks it as a consumer. **LOW.**
- **597 (search count), 603 (RAG trust), 605 (agent-window), 593 (walkthrough umbrella)** — different
  surfaces/files (SearchSurface count, CitationsPanel/chat, agent run singletons, the umbrella). No
  overlap with telemetry / rule engine / readiness wording. **NONE.**

## XVI.4 — Verdict & recommended order

- **Build C-2 (A+B+C) now / independently** — zero contention (no sibling edits telemetry, the rule
  engine, or the health-event catalog; no active worktrees; no in-flight code).
- **C-1 stays gated on 598** — not merely on B-3 landing, but on 598's **`readinessNotice` branch
  re-key** settling, since 598 actively redesigns C-1's files. When it lands: (a) author C-1's
  negative-guarantee invariant on the *settled* branch structure, and (b) ensure 598's new compat
  reason code carries a `CAUSE_ROWS` row + `readiness-reason-codes.v1.json` entry so 600's shipped gate
  stays green (the one concrete co-land coupling).
- **No active tempdoc threatens to invalidate 600's design.** The only real coupling is the known,
  mutually-acknowledged 598↔600 source/copy ordering; everything touching C-2 is clear.

---

# AS-BUILT — C-2 implementation (worktree `worktree-600-c2-impl`, 2026-06-17)

> Implements the reopened residual **C-2** ("the self-monitoring blind rule"): a health rule whose
> predicate metric silently stops being recorded (RRD catalog-drift) was permanently blind, and even
> when transiently blind its observable churned (a WARN + a `CONDITION_MODIFIED` every 5s) and rendered
> raw internals. Three layers, each the long-term structural fix, extending existing authorities — no
> new wire type, enum, schema, or governance surface. **C-1 is NOT implemented here** — it is gated on
> 598's active `readinessNotice` re-key + 598 B-3 (PART XIV/XVI); implementing it now would collide
> with 598's in-flight redesign and have no user-visible effect until the verdict is truthful.

## Layer A — RRD datasource reconciliation (root: removes *permanent* blindness)

`RrdMetricStore.initialize()` now reconciles the on-disk datasource set to the declared curated set
**before** opening the long-lived `RrdDb`. New private `reconcileDatasources()`: opens the existing
file, diffs `RrdDb.getDsNames()` against the already-derived `this.dsNames`, and for any missing
datasource adds it **in place** via `RrdToolkit.addDatasources(path, defs, /*saveBackup=*/true)`
(rrd4j 3.10; preserves all existing data + archives), then deletes the sibling `.bak`. Best-effort:
any failure is logged at WARN and startup proceeds (telemetry is WARN-not-throw; `record()`/`query()`
keep their pre-existing drift tolerance for anything still absent). The create-new branch and
`record()`/`query()` are untouched. So a stale `metrics.rrd` from a prior boot with a smaller curated
set can no longer leave a curated metric (e.g. `head.jvm.memory.heap.used_bytes`) unrecorded forever —
the catalog-drift that blinded the `memory-pressure` rule. **Test:** `RrdMetricStoreReconcileTest`
drives the REAL `initialize()` on a stale RRD and asserts the missing datasource is added and writable
(no "Datasource not found"), and the `.bak` is cleaned up.

## Layer B — stable blind-observable identity (kills the every-5s churn)

The churn came from `Signal.latest()` embedding the moving query window `[start, now]` in its
`MissingMetricException` message — which flowed into two dedup-sensitive identities. Fixes:
- `Signal.latest()` message is now the **stable fact** — `"metric '<name>' has no recorded samples"`,
  no window. This stabilizes `CelEvaluator`'s WARN-once dedup key (keyed on the message), so the WARN
  fires once per {rule,metric}, not every 5s. (`WindowedView` was already window-free.)
- `RuleEmitter.emitUnobservable` now sets a **plain, stable, check-named** condition message — `"The
  '<rule>' check can't read its data yet."` — instead of `"Health rule '<rule>' cannot evaluate:
  <reason>"`. Stability makes a re-emit dedup to `ConditionStore.Transition.UNCHANGED` (no
  `CONDITION_MODIFIED` churn); plainness keeps raw internals (metric name, window) out of the rendered
  row. The engineer-facing detail (`reason`, with the metric name) is logged at DEBUG.
**Test:** `RuleRunnerTest.blindMonitorDoesNotChurn` — 4 blind ticks with an advancing clock yield
exactly **1 `CONDITION_ADDED` and 0 `CONDITION_MODIFIED`** for `monitor.unobservable`.

## Layer C — plain-language display label (the row title)

`health-events.monitor.unobservable.{label,message}` added to
`modules/app-api/src/main/resources/messages/health-events.en.properties` (`label = "Self-check
unavailable"`, `message = "A self-check is temporarily unavailable."`). The health-event render strategy
(`healthEventActivityRow`) resolves the event's `i18nKey` (`health-events.monitor.unobservable.message`)
to the row TITLE via `present()`/`localizeResourceKey`; previously, with no catalog entry, it fell back
to the raw id `monitor.unobservable` (Nielsen #9 violation). The row now reads
**"Self-check unavailable — The 'memory-pressure' check can't read its data yet."** — title from the
catalog, detail from the (now plain) emitter message. No regen (the catalog is hand-authored, served by
`MessageCatalogRoutes`). The `HealthLitView.test.ts` fixture message was updated to the new plain
wording.

## Verification (static — all green)

- `./gradlew.bat spotlessApply` clean · `build -x test` → **BUILD SUCCESSFUL** (compile + all governance
  gates).
- Module suites: `:modules:telemetry:test` **90** (1 skipped) · `:modules:app-services:test` **1538**
  (3 skipped) · `:modules:app-api:test` **112** · `:modules:ui:test` **415** — **0 failures**. (An
  initial `AiInstallServiceLateBindTest` failure was an environmental worktree-setup miss — "Worker lib
  directory not found" — fixed by `:modules:indexer-worker:installDist`, unrelated to this change.)
- FE: `npm run typecheck` clean · `npm run test:unit:run` → **3179 passed (330 files)**.

## Live UI verification — DONE (dev stack taken over with user approval)

**Verified live.** With the user's explicit approval to take over the shared dev stack, it was
restarted on a build carrying this change (the worktree's freshly-built `app-api`/`app-services`/
`telemetry` jars staged into the dev-runner's launch dist — the dev-runner launches from the main
checkout's `modules/ui/build/install/ui/`, not the worktree's, so the jars were copied in; main's dist
was rebuilt afterward to restore it). Confirmed:

- **Backend serves the new catalog entry:** `GET /api/messages/health-events/en` returns
  `health-events.monitor.unobservable.label = "Self-check unavailable"` +
  `.message = "A self-check is temporarily unavailable."` (Layer C live).
- **Live render (screenshot captured):** a `monitor.unobservable` event rendered through the PRODUCTION
  component + strategy (`<jf-health-event context="activity-row">` → `healthEventActivityRow` →
  `present()`/`localizeResourceKey` against the live catalog) shows
  **"A self-check is temporarily unavailable. — The 'memory-pressure' check can't read its data yet. ·
  head just now"** — the PLAIN catalog-resolved title + the plain Layer-B emitter message, with **NO
  raw `monitor.unobservable` id** (`showsRawId:false`, `showsPlainTitle:true`). This proves Layer C
  (catalog title) and Layer B (plain message) end-to-end against a real backend.
- **No churn:** the re-emit dedup is backend `ConditionStore` behavior (not exercised by a single FE
  render), authoritatively proven by the green unit test `RuleRunnerTest.blindMonitorDoesNotChurn`
  (4 blind ticks → exactly 1 `CONDITION_ADDED`, 0 `CONDITION_MODIFIED`).

Note on injection mechanism: `POST /api/debug/trip-condition` is eval-mode-gated and returned 404 on the
dev stack (eval mode off), so the live render was driven by mounting the production `jf-health-event`
component with a synthetic event against the live catalog — the identical render path + the real
backend-served wording. The dev stack was stopped and the main dist restored afterward.
