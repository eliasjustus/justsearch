---
title: "Activity feed should separate user actions from system and background influences"
type: tempdocs
created: 2026-06-18
updated: 2026-06-20
status: "AXIS 1 MERGED to main (2026-06-20, commit `ed686532f`; FF merge `9fd778a07` — §L.5). LIVE-VALIDATED. FE-only de-flood of the Activity AUDIT feed: widened the routine predicate (closed Effect-union Record) + operation-significance grading via the OperationCatalog + relabelled toggle (navigation→routine) + 'Only routine activity' empty state. typecheck clean; 45 targeted + full 3325-test suite green; LIVE browser proof against a real 493-user-effect/5-system-row ledger → default view shows ONLY the system index burst, toggle reads 'routine (493)', click reveals all 493 (reversible). Axis 2 stays OUT (events already on System Health). PRIOR: open — DESIGN + LIVE UI PASS done (2026-06-20; no code yet). See §L for the AUTHORITATIVE user-facing design (live inspection superseded the earlier Axis-2 framing). NET: 612's near-term work is ONE thing — AXIS 1, a witnessed-ness CURATION of the Activity 'AUDIT' feed — and it is FE-only, on the existing surface. Grounded in 550 (Activity = the action-ledger Outcome face) + 613 (`surface = f(declared facets)`; the routine filter is another `f` over facets that ALL already exist: ledger originator/outcome/kind + OperationCatalog risk/confirm/audit/affects joined at read time). Live-confirmed urgent: with 613's navigation rule already hiding navigation(413), the default feed STILL read user(501) vs system(4) — a wall of set-appearance/save-settings/set-ui-mode/toast/No-op/Resolve-path-hash chatter burying the audit-relevant rows. Axis-1 user-facing changes: generalize the reveal toggle label navigation(N)→routine(N); widen the routine set to preference/UI local-acks (incl. save-settings autosave — LIVE CORRECTION to the earlier 'weight-bearing' call) + repeated low-value ops; honest 'only routine activity' empty state. AXIS 2 (bridge lifecycle/recovery into Activity) is REMOVED from scope: live inspection found those events ALREADY render on the sibling System Health → 'Recent events' tab (healthEventActivityRow strategy) with good wording — bridging would DUPLICATE that surface and BLUR Activity's audit identity. The 'separation' 612 asks for is already achieved at the IA level (de-flood Activity beside the existing health tab). The real higher-order question (should the 3 System tabs Health·Logs·Activity unify into one 'what happened' timeline?) is handed to window-taxonomy-convergence, NOT 612 — if it ever unifies, the §4 federated-contributor/550-§I mechanism applies THEN. REACH: WITNESSED-REDUNDANCY PRINCIPLE reinforced; live pass adds a second named shape — SURFACE-IDENTITY INTEGRITY (content belongs to the one surface whose framing it matches, not duplicated for completeness) — which is precisely why Axis 2 was wrong. Both principles recorded, candidate scope = window-taxonomy-convergence cluster; NOT built now. No open design fork remains; Axis 1 is the committed slice. ── FORWARD-LOOKING RESEARCH (§R) + LONG-TERM DESIGN (§LT) added 2026-06-20: §LT theorizes the correct structure for the §R directions (unread / digest / importance / causality). Key reframe: there is NO 'lens framework' to build — 571 §8 already declined the shared event-stream projector as over-DRY ('unify the engine, not the surface'). The correct structure is the FACET-ALTITUDE RULE: a new way-to-see is added at the altitude of its reason-to-change — a declared FACET on the record when ≥2 readers derive it (importance → the latent EmissionPolicy.importanceFloor slot; causality → a new causal-origin id stamped at the 550-§I emit boundary, then grouped via the existing collapseBursts), a shared cross-surface AUTHORITY when one fact many surfaces read (unread → the existing seen-cursor recallCursor.ts), or surface-local logic when one surface cares (571 §8) — never a parallel view/query engine. Composes 550-§I + 558-§S1/548-§1 + 571-§8 + 613-§7; conform, don't parallel. Names the Facet-Altitude Rule ('project at the record, filter at the surface'); candidate existing violation = SearchResultsRenderer re-deriving the row projection (obs 602 R3). Nothing built — all §R/§LT is forward-looking; recording the rule, not the structure. ── USER-FACING DESIGN (§UX) added 2026-06-20 (live UI inspection of the merged feed): rows are visually UNIFORM, the feed already COMPRESSES (burst-collapse), and a 'while you were away' digest ALREADY exists as a global agent-autonomy overlay (jf-ai-activity-digest, 'undo all AI actions'). So the forward-looking work shrinks to: BUILD the unread/'new since you looked' marker (feed-local, reuse seen-cursor — the one non-redundant catch-up); DROP a feed-header digest (it would restate rows the user sees = 548-§1 second authority; and don't broaden the agent overlay = surface-identity integrity §L.4); importance = visual emphasis reusing statusTone (no new colour authority); causality = reuse the EXISTING burst-summary row (group by causal-origin, not collection — UX already prototyped). Net: no new surface/component; every piece extends something the user already sees. ── TWO FE FOLLOW-UPS NOW SHIPPED (§IMPL, 2026-06-20, `feat(612)` `97968af94`, FF merge `20b34e44d`): (1) the unread 'new since you looked' marker (reuses the one seen-cursor; additive opt-in on the shared row; explicit 'mark all read' — supersedes §UX's 'advance on view' per §CI R1's shared-cursor finding); (2) trust-disposition tone (DENIED→error, GATED/REVOKED→warning, via the one statusTone authority). typecheck + full 3351-test suite green; live browser-validated. Digest stays DROPPED; causal flagship stays DEFERRED (§CI R4, cross-process backend). Importance shipped as its minimal tone slice (ISSUED grants left neutral; no row-level/sort — a recorded un-built §LT extension, not a defect). §UX item 1 fully delivered, §UX item 4 minimally delivered."
author: user walkthrough notes, filed by agent
category: frontend / ux / activity / observability / semantics
related:
  - agent-action-lifecycle
  - observed-happening
  - surface-altitude-governing-axis
  - search-and-agent-window-convergence
  - agent-window-presentation-and-evidence
  - window-taxonomy-convergence
  - residual-walkthrough-findings-fe-reliability-and-consistency
---

# 612 - Activity feed should separate user actions from system and background influences

> What this document is. A short problem statement from a user walkthrough. It records the idea for a
> future design pass only; it deliberately does not prescribe implementation.

## Problem

Activity currently needs a clearer semantic boundary. The default user-facing Activity feed should
de-emphasize ordinary direct user actions and instead foreground other influences: background indexing,
system lifecycle changes, agent effects, external file changes, failures, recoveries, and other events the
user did not already cause and witness directly.

This is a presentation and inclusion-rule problem, not a request to delete user actions from the canonical
record. The action-lifecycle ledger already carries `user | agent | system` attribution and must remain
complete for audit, undo, trust review, causality, and history. The product gap is that the default
Activity projection reads too much like "everything I just clicked" instead of "what happened that I need
to know about."

## Why it matters

If Activity records ordinary user clicks and direct actions, it becomes a noisy history log. The user
opens Activity to answer "what happened that I should know about?", not "what did I just click?" Mixing
both weakens the surface exactly when it should explain hidden or background behavior.

Direct user actions still matter when they explain later effects or have trust/audit weight: approvals,
denials, durable grants, destructive confirmations, background-run launches, and "user did X, system then
did Y" chains. Those should stay visible when they are causal or accountability-relevant. Routine
navigation, direct button clicks, and local acknowledgements should not dominate the default feed.

## Boundary

This is adjacent to the observability and observed-happening lineage, but the immediate question is
product semantics: what belongs in the default user-facing Activity projection? It is also adjacent to
`window-taxonomy-convergence`, because Activity sits inside the broader diagnostics/system grouping while
retaining its trust/audit framing.

The complete ledger can remain reachable through an audit/history filter or mode. The default Activity
feed should be a curated projection over the same records, not a second store and not a lossy replacement
for the audit trail.

Later design work should define event classes, inclusion rules, and where direct user actions belong if
they need an audit trail. This document only opens the semantic separation problem.

---

## Note — first inclusion rule shipped via tempdoc 613 (2026-06-19)

The 613 "all remaining work" pass implemented the **first concrete inclusion rule** on this surface, as
the realization of the 613×612 seam (613 routes the notification; 612 owns the history-row predicate):

- **Routine direct-user navigation is excluded from the default Activity feed.** `isRoutineActivity`
  (`shell-v0/state/messageRouting.ts`) classifies a unified-activity row as routine when it is a
  direct-user navigation (backend `kind='navigation'`, an ingested FE navigate effect, or a raw FE
  `navigate` journal row); `UnifiedActionEntry.isRoutine` carries it; `ActionLedgerView` filters it out by
  default and offers a **routine (N) toggle** to reveal the full view — exactly the "curated projection,
  complete ledger still reachable" model above. (Live `593`-walkthrough symptom: the feed read
  `user (494)` vs `system (6)`.)

This is intentionally the *narrow, highest-value* rule (navigation), not the full event-class taxonomy.
The broader inclusion semantics this doc opens — which user actions are causal/audit-relevant
(approvals, denials, destructive confirmations, "user did X → system did Y" chains) and therefore stay in
the default feed, vs which routine actions (other clicks, local acks) also de-flood — remain **612's**
design to define. 613 supplied the `originator`/`kind` facets + the de-flood mechanism; 612 owns extending
the predicate.

---

## Design pass (2026-06-20, agent investigation + long-term design)

> Status of this pass: **design, no code yet.** Took over 612 from `open`. Source-verified the ledger
> event space, the existing declared facets, and the live effect composition (§1), then theorized the
> long-term design against the substrate it sits on (550 + 613). The load-bearing finding (§2): the
> Activity feed is *already* 550-Thesis-I's projection over the one federated action record, so 612 is its
> completion along two axes — a curation filter and one missing federated contributor — each conforming to
> an existing seam, scoped to exactly what the present problem requires (§5).

### §1 — What I verified (source-grounded event-space map)

The Activity feed is `<jf-action-ledger>` (`shell-v0/components/ActionLedgerView.ts`), a curated read-view
over the **one** action-ledger: `GET /api/action-ledger[/stream]` projected by
`ActionLedgerProjection.toWireRow()` (`modules/app-observability/.../ledger/`). Every row carries
`{id, kind, occurredAt, originator, ...}`. Verified enumeration:

| `kind` | originator(s) | what it is | in the flood? |
|---|---|---|---|
| `operation` | user / agent / system | a dispatched operation's terminal outcome (`SUCCESS`/`FAILURE`/`UNDONE`); agent tool completions project here too | no (ops are sparse) |
| `navigation` | user / agent / system | a forwarded navigation (no outcome axis) | **yes — user nav was 494 rows** |
| `gate` | user / agent / system | a trust-gate firing (`GATED`/`DENIED`/`APPROVED`) — only non-AUTO outcomes | no |
| `grant` | user / agent / system | consent-capsule / durable-grant lifecycle (`ISSUED`/`CONSUMED`/`REVOKED`) | no |
| `effect` | user / agent / system | a FE-local effect ingested via `POST /api/action-ledger/events` | **yes — UI-chrome toggles** |
| `index` | **system only** | a worker indexing job *terminal* outcome (`DONE`/`FAILED`); in-flight states deliberately excluded | no (and burst-collapsed) |

Two facts that change the design:

1. **The facets needed to grade a USER operation's significance already exist client-side.** The FE
   already receives, via `/api/registry/operations` (→ `shell-v0/api/types/registry.ts`, joined at render
   by `present()`/`getOperation()`): `policy.risk` (LOW/MEDIUM/HIGH = READ_ONLY/WRITE/DESTRUCTIVE),
   `policy.confirm` (NONE/INLINE/TYPED), `policy.audit` (NONE/METADATA_ONLY/FULL_PAYLOAD),
   `policy.undoSupported`, `lineage.affects` (Resources mutated), `presentation.category` (e.g.
   `"destructive"`). So "is this user action destructive / write-causing / confirmation-gated /
   audit-weighty / state-mutating" is **already declared** — no new catalog field is required to grade
   operations. (The single gap: no declared *"launches background work"* facet; but those operations are
   HIGH-risk and/or have non-empty `affects`, so they are caught indirectly — see §6.)

2. **The classes 612 most wants foregrounded are NOT all in the ledger.** 612's prose asks the default
   feed to foreground "background indexing, system lifecycle changes, agent effects, external file
   changes, failures, recoveries." Against the verified ledger:

   | 612 wants foregrounded | in the action-ledger today? |
   |---|---|
   | background indexing | ✅ `index` rows (system) |
   | agent effects | ✅ agent-originator `operation`/`effect` rows |
   | failures | ✅ `FAILURE` outcomes + `index` `FAILED` |
   | **system lifecycle changes** (worker/brain up/down) | ❌ `HealthEvent` `LifecycleEvent` — a **separate** `/api/health-events` stream |
   | **external file changes** | ❌ no event; absorbed into reindex operations |
   | **recoveries** | ❌ `HealthEvent` — separate stream |

   `HealthEventBody` is a sealed interface `permits LifecycleEvent, AssertedCondition, ThresholdState`
   (`modules/app-observability/.../health/HealthEventBody.java`). `LifecycleEvent` = discrete transitions
   ("worker restarted"); `AssertedCondition` = standing conditions (degradation). None of it reaches the
   action-ledger.

### §2 — The recognition: Activity *is* 550-Thesis-I's projection; 612 completes it

The deeper investigation (reading 550 + 613, the substrate this feed sits on) shows 612 is **not** a new
concept and **not** the "two competing forks" my first pass framed. The Activity feed already has a
designed identity, and 612 is the completion of it:

- **550 Thesis I** designed the action-ledger as *one append-only lifecycle record* that explicitly
  **"subsumes user actions, non-user-actor actions, AND system/background operations,"** written by
  **federated contributors** (Head action-ledger; Worker job-queue; FE Effect Journal) under one schema,
  **"never re-joined per read."** Its kind taxonomy is stated as `invoke|gate|navigate|effect|grant|undo|
  index|enrich|migrate…` — the `…` is deliberate headroom. And 550 names the **"Activity timeline"** as
  one of the *projections/filters* over this one record (alongside the receipt, advisory inbox, undo).
- **613** built the routing seam `surface = f(declared facets)` and, in §4, names the very axis 612 needs
  — **`trigger/originator`: "did the user directly cause-and-witness this?"** — calling it *"also the axis
  612 routes Activity on."* 613 §9 then **explicitly defers the Activity inclusion-rule to 612**, with a
  standing instruction: keep it a *derivation rule over existing facets*, not a new declared field, until
  a second consumer needs to override the derived default ("encode a facet when a second consumer needs
  it").

So 612 is the projection 550 promised but never fully realized, routed on the facet 613 named but deferred.
It completes that projection along **two axes the record was already designed to carry** — a *curation
filter* (Axis 1) and one missing *federated contributor class* (Axis 2). Neither is new architecture;
each conforms to a seam that already exists. The first-pass "Fork A vs Fork B, pick one" framing was
wrong: these are not alternatives, they are the two halves of one already-designed surface, and they are
gated by **scope** (what the present problem requires), not by preference.

### §3 — Axis 1 (curation): the witnessed-ness inclusion filter

The default Activity feed is the *governed, bounded projection* 550 §III requires — and the bound it is
missing is a **meaning-derived inclusion filter**. This is 613's `surface = f(facets)` applied to the
default-vs-full-ledger axis: a row's eligibility for the curated feed is a **function of its declared
meaning-facets**, computed in one place, never decided per emit-site.

The rule (general form): **a row is excluded from the curated feed iff it is a direct-user action the user
already caused *and* witnessed *and* it carries no causal or audit weight** — otherwise it is foreground
by construction. The facets that decide this **already exist**, on two surfaces the FE already holds:

- on the ledger row: `originator` (only `user` can be routine; all agent/system rows stay), `outcome`
  (a `FAILURE` is never routine), and `kind` (navigation + the *local-ack* class of FE effects — pane/
  modal/selection/scroll/focus/copy/form chrome — are witnessed acknowledgements; `gate`/`grant` never
  are, being the trust-weighty approvals/denials/grants 612 says must stay);
- on the **OperationCatalog**, joined at read time exactly as `present()` already joins `operationId →
  label`: a user `operation` is routine only if its *declared* facets say it is insignificant — low risk,
  no confirmation, not fully-audited, mutating no Resource (`lineage.affects` empty). Anything destructive,
  confirmed, audited, or state-mutating is the literal "user did X → system did Y" chain and stays.

Because every input is an existing declared fact, Axis 1 needs **no new store, no new wire field, and no
per-`operationId` list** (a hardcoded list would be a forbidden second significance-authority shadowing
the catalog). Per 613's deferral instruction, the eligibility stays a **derivation rule**, *not* a new
`activityEligible`/`EmissionPolicy` facet — that promotion is warranted only if and when a producer needs
to override the derived default (the named promotion-trigger, not built now).

### §4 — Axis 2 (coverage): the missing federated contributor

> **⚠ Revised by the live UI inspection — read §L before acting on this section.** The premise below
> ("the lifecycle/recovery classes are not surfaced anywhere, so Activity must absorb them") is **false in
> the running product**: those events already render on the **System Health → "Recent events"** list, a
> sibling tab. §L revises Axis 2 from "bridge them into Activity" to "leave them where they correctly are;
> defer any unification to `window-taxonomy-convergence`." The federated-contributor *mechanism* described
> here stays valid **only** as the implementation 612 would use *if* a future convergence decision moves
> them — it is not 612's near-term work. The text is kept for that contingency.

A filter cannot foreground a happening that is not in the record. Of the classes 612 names, the
system-side ones — **system lifecycle changes, recoveries** — are not in the ledger today; they live in
the separate `HealthEvent` stream. But 550 Thesis I already says the record subsumes system/background
happenings *via federated contributors*, and the **`IndexingJobsBridge` is the realized precedent**: a
worker job's terminal outcome is bridged into the one ledger as a `system` row (`index` kind). The
system-lifecycle/recovery class is **the same shape** — a not-yet-wired federated contributor, not a new
mechanism.

The general design for Axis 2, therefore, is: *a contributor that normalizes the discrete system-lifecycle
happening into the one ActionEvent schema at the contributor boundary* — **not** an FE read-time merge of
`/api/health-events` with the ledger (550 §I forbids re-join-per-read; 577 already eliminated the last
such join). Two constraints bound exactly which happenings qualify, both inherited from existing seams:

- **613's PUSH/PULL boundary.** `HealthEventBody` already separates `LifecycleEvent`/`ThresholdState`
  (discrete *transitions* — "worker restarted", "brain recovered") from `AssertedCondition` (a *standing
  condition* — "currently degraded"). Only the **transitions** are happenings that belong in a history;
  the standing condition is PULL state and stays the banner. So the contributor projects `LifecycleEvent`
  (and terminal `ThresholdState` crossings), never `AssertedCondition`.
- **The witnessed-ness rule (Axis 1) applies uniformly.** A system transition is `originator: system`, so
  it is foreground by the same rule that keeps `index` rows — no special-casing.

### §5 — Scope judgment (what this problem requires, and what it does not)

The size of the design is the *outcome* of matching structure to the present problem, not a target:

- **Axis 1 is required now.** It is the witnessed defect (the live 494-user-row flood). The structure it
  needs already exists (the projection-filter seam + every facet). Build it.
- **Axis 2 — SUPERSEDED by §L (the live pass).** This bullet argued the lifecycle/recovery bridge was
  "required to make the separation real" on the belief those events were surfaced nowhere. The live
  inspection found them **already rendered on System Health → Recent events**, so the bridge is *not*
  required and would duplicate that surface. The separation is achieved by Axis 1 alone (de-flood Activity
  beside the existing health tab). Read §L; treat the bridge as deferred-to-`window-taxonomy-convergence`,
  not 612 work.
- **External-file-change is *out*.** 612 names it, but there is **no event source** for it today (file
  changes are absorbed into reindex operations; no filesystem-watch happening is emitted). Building a
  detection source is a separate, larger problem the present tempdoc does not contain — adding that
  structure now would be inventing a case the problem does not yet include. Log it as an explicit
  non-goal, not a silent omission.
- **Also out (named, not built):** a new declared activity-eligibility facet (no second consumer yet —
  §3); a unified 612+613 routing *kernel* (each instance is small and the seam is shared without merging —
  §7); a backend-stamped `isRoutine` (denormalizes the catalog authority — would create a second
  significance-authority that drifts).

This yields a bounded shape — **after the §L live revision: one inclusion-filter rule** (Axis 1, FE-only,
existing facets). The federated lifecycle-contributor bridge (Axis 2) is *out* of 612 (the events already
have a home — §L), leaving an even tighter scope than this section originally concluded.

### §6 — Critical analysis (questioned assumptions / rejected alternatives)

- **Rejected: "curated feed = `originator !== 'user'`" (drop all user rows).** Violates 612's explicit
  keep-list (approvals, denials, destructive confirmations are user-originated and must stay). The
  significance clause is what preserves them — keep the derivation, not the blunt cut.
- **Rejected (now): a `launchesBackgroundWork` operation facet.** Background-launching ops are already
  HIGH-risk / non-empty `affects`, so Axis 1 keeps them without a new field. A facet no second consumer
  needs is the `substrate-without-consumer-flavors` anti-pattern. Defer until a concrete miss is measured.
- **Burst-collapse ≠ inclusion (orthogonal, both legitimate).** `collapseBursts` already summarizes index
  bursts; if a live histogram shows repeated identical *significant* ops flooding, that is a burst-collapse
  extension (group by `operationId`), not an inclusion change. Name the mechanism per symptom; don't
  overload the filter.
- **`undo-operation`, `save-settings`, `apply-presentation` are weight-bearing**, not local acks — they
  reverse an op or change durable state, so they stay even when user-originated. `error`/`warning` toasts
  carry retainable content and are foreground; only `info`/`success` toasts are routine acks.
  *(Superseded by the live pass — §L.2 reclassified `save-settings` as **routine** preference-autosave, and
  §L.5/R4 made **all `toast` effects routine** because toast `severity` is not on the ledger wire. The
  still-current calls from this bullet: `undo-operation` and `apply-presentation` stay foreground.)*

### §7 — Reach: principle, seam-conformance, and candidate scope

Stepping back from the immediate fix to judge what this design *is an instance of* and what it *reveals*:

**This design is an instance of two seams that already exist — conform, do not parallel.**
1. **`surface = f(declared facets)` (613).** Axis 1's inclusion filter is another `f` over the same facet
   substrate 613 routes on; it correctly lives beside 613's `routePushSurface` in `messageRouting.ts`. It
   must not become a second, divergent routing mechanism.
2. **One federated record, many contributors, normalized at the contributor boundary (550 §I).** Axis 2 is
   another contributor of the `IndexingJobsBridge` shape; its non-negotiable conformance is *normalize at
   the bridge, never re-join per read*.

**This design reveals a principle worth naming — the Witnessed-Redundancy Principle.**
> *A surface must not re-present, at a standing or durable altitude, a happening the recipient already
> perceived at the moment it occurred.*

It is the unifying invariant behind several already-shipped or designed rules, which are its faces:
- 613's **"don't push what's already pulled"** (a toast must not re-state a cause the banner already
  shows) — `causePushSuppressedByBanner`;
- 613's **receipt rule** (an at-control ack the user is watching is an in-element flash, not a window
  toast);
- 612's **routine-exclusion** (a navigation/chrome action the user just performed is not durable history).

All three are the same shape: surfaces are non-redundant with respect to what the user has already
perceived, keyed on a **witnessed-ness facet**.

**Candidate scope beyond 612 (named, not built):** the principle predicts a violation wherever one
happening is presented at multiple altitudes without a witnessed-ness/already-shown check. 613 §10's live
finding — **one degradation cause surfaced across five loci with three wordings** (banner / brain banner /
health pill / advisory inbox / status) — is exactly such a violation on the PULL side, and 613 already
logged it as its open "coherence" half. That is independent corroboration that the principle generalizes;
it is **not** a license to build a generalized witnessed-ness kernel now. 612 requires only its own
instance; 613 owns its instances. Recording the principle and its candidate scope is the deliverable here
— building the cross-surface abstraction would be premature until a third concrete consumer forces it.

### §8 — Open question (SUPERSEDED by §L)

> This section's "Axis 1 alone vs Axis 1 + Axis 2" question is **resolved by the live pass (§L)**: Axis 2
> is out of 612 (the lifecycle/recovery events already render on System Health), so there is no Axis-1-vs-
> Axis-2 scope fork. Axis 1 is the committed slice. The original text is retained below only as the record
> of the pre-inspection framing.

The design is settled; one product judgment remains (a *scope* choice, not a design fork):

- ~~**Ship Axis 1 alone, or Axis 1 + Axis 2 together?**~~ Axis 1 (the curation filter) is independently
  shippable and cures the witnessed flood. Axis 2 (the lifecycle-contributor bridge) is what makes the
  "separation" 612 names *real* by giving Activity the "worker restarted / brain recovered" history rows —
  low-novelty but backend-touching. Recommendation: **both**, since the separation is hollow without Axis
  2; but Axis 1 may land first as a self-contained slice. (External-file-change stays out either way —
  §5.) *[§L: the events already have a home on System Health — bridge deferred to
  `window-taxonomy-convergence`, not 612.]*

A lighter secondary question that **does** survive: whether repeated identical *significant* operations
(live: `Resolve path hash ×N`) warrant `operationId` burst-collapse in this slice or a follow-on (§6 /
§L.2 #3).

---

## §L — Frontend / user-facing design pass (live UI inspection, 2026-06-20)

> Ran the dev stack and inspected the real surfaces (screenshots, not the tempdoc). The inspection
> **confirmed Axis 1 and overturned Axis 2's premise.** The §2–§8 design above is sound on the curation
> half; this section is the authoritative user-facing design and supersedes the §4/§5/§8 treatment of
> coverage.

### §L.1 — What the running product actually shows

**The IA: Activity is the "AUDIT" tab of a three-tab "System" page.** The tabs are **System Health · Logs ·
Activity (AUDIT)**. Activity mounts `<jf-action-ledger>` (the 550 Outcome face) under the header *"What the
system did — a structured audit of operations … For raw diagnostic output, see Logs."* So Activity already
has a declared identity — a **trust/audit ledger of actor actions** — distinct from Health (status) and
Logs (raw). 612's own Boundary section anticipated this ("Activity … retains its trust/audit framing").

**Axis 1 is confirmed, and worse than the tempdoc assumed.** With 613's navigation rule already hiding
`navigation (413)`, the *default* feed still read `who: user (501)` vs `system (4)` and was a wall of
**preference/UI chatter** — dozens of identical `set-appearance` / `save-settings` / `set-ui-mode` rows,
plus `toast`, `No-op`, and repeated low-value `Resolve path hash` operations. The audit-relevant rows
(`Indexed · default` system outcomes, the few real ops) were buried. The feed reads as *"everything I
toggled,"* exactly 612's complaint — and navigation was never the whole flood.

**Axis 2's premise is false in the product.** The system-lifecycle / recovery classes 612 names are **not
missing** — they already render on **System Health → "Recent events"**, the sibling tab, with good
human wording:
> *"A reindex is recommended to pick up recent schema or analyzer changes."* · *"An embedding model
> mismatch was detected; reindex recommended…"* · *"The AI subsystem is not ready. Online generation
> requests will be unavailable until it recovers."*

These are rendered by the existing `(HealthEvent, activity-row)` aggregate strategy
(`aggregate-substrate/strategies/healthEventActivityRow.ts`) — a realized FE seam that already handles the
`lifecycle` / `condition` / `threshold` body variants. So the "coverage" 612 imagines is **already built,
on the surface whose identity fits it** (health happenings on the Health tab), not absent.

### §L.2 — The corrected user-facing design

**Axis 1 (curation) is the whole of 612's near-term, user-visible work.** The default Activity feed should
be the *curated audit*: agent operations, system index outcomes, failures, trust-gate firings, grants, and
significant (destructive / confirmed / audited / Resource-mutating) user operations. Everything the user
caused-and-witnessed with no audit weight collapses behind the existing reveal toggle. Concrete
user-facing changes, all on the surface that already exists (no new component):

1. **Generalize the reveal toggle's label from `navigation (N)` to `routine (N)`.** It already governs the
   curated/full split and shows a count; it now hides more than navigation, so the chip must say so. Keep
   the one-click reveal — the "curated default, complete ledger one toggle away" model is correct and
   already shipped; 612 only widens what it governs.
2. **Widen the routine set to the witnessed local-ack chatter the live feed exposed** — preference/UI
   effects (`set-appearance`, `set-ui-mode`, and the `save-settings` that merely persists them), pane/modal
   open-close, selection/scroll/focus, copy, `No-op`, and `toast`. *Live correction to §3:*
   the tempdoc had classed `save-settings` as weight-bearing, but in the running feed it is autosave noise
   alternating with `set-appearance`; preference-class settings are routine self-config the user witnessed.
   (A genuine trust/security setting change still surfaces as its own gate/grant row — the audit weight
   lives there, not in the `save-settings` effect.) *Toast reconciliation (was "`info`/`success` toasts
   only"; corrected at implementation — §L.5/R4):* **all `toast` effects are routine**, not just the calm
   severities. The earlier carve-out assumed the row could be graded by toast severity, but `severity` is
   **not carried on the ledger wire** (`startEffectIngest` posts only `{id, effectKind, originator, subject,
   occurredAt}`), so the projection cannot distinguish `info`/`success` from `error`/`warning`. This loses
   no audit signal: a toast is a transient the user already witnessed, and a durable failure it announces
   surfaces independently as a `FAILURE` operation/index row (which stays foreground). Restoring the
   severity split would require a wire change for negligible value — explicitly declined.
3. **Treat repeated low-value operations as routine too.** `Resolve path hash` flooded as a `user`
   operation; the §3 operation-significance rule (LOW risk · no confirm · not fully-audited · empty
   `affects`) classes it routine, which is correct. Where identical *significant* ops repeat, that is the
   burst-collapse lever (§6 / `collapseBursts`), not the inclusion rule.
4. **Give the curated feed an honest empty/near-empty state.** When curation leaves little (a session of
   only routine actions), the surface should say *"Only routine activity this session"* with the reveal
   affordance, not the bare *"No activity yet"* — otherwise de-flooding reads as breakage.

**Axis 2 (coverage) is removed from 612's scope.** The lifecycle/recovery events already render on the
correct sibling surface (System Health), and Activity's identity is *audit of actor actions* — a system
transition is not an actor action, and folding it into the AUDIT ledger would (a) duplicate the System
Health Recent-events list and (b) blur the one framing that makes Activity legible. So the "separation"
612 asks for is **already achieved at the IA level**: de-flood Activity (Axis 1) and the user has a clean
audit tab beside a clean health-happenings tab. No bridge, no new `lifecycle` ledger kind, nothing
backend-touching. This is the scope-match the problem actually has.

### §L.3 — The genuine open question this surfaces (placement, not mechanism)

The live IA exposes the real higher-order question — not "is coverage missing?" (it isn't) but **"should
the three System tabs (Health · Logs · Activity) present one unified 'what happened' timeline, or stay
three framings?"** Today a user asking *"what happened that I should know about?"* must know that *audit*
events are on Activity and *health* events are on System Health. That boundary clarity is exactly
`window-taxonomy-convergence`'s charter, **not 612's**. 612 should: ship Axis 1, leave health happenings on
System Health, and hand the unify-or-keep-separate decision to that convergence work with this note —
*if* it ever unifies, the right mechanism is still 550 §I (one record; System Health Recent-events becomes
a filtered projection of it), i.e. the §4 federated-contributor design, applied **then**, by **that**
tempdoc. Recording the seam without building it is the discipline; the present problem does not require it.

### §L.4 — Reach, revised by the live finding

The **Witnessed-Redundancy Principle** (§7) is reinforced, not changed: Activity re-listing `set-appearance`
the user just toggled is the same violation as a toast re-stating the banner. But the live pass adds a
second, distinct shape worth naming separately:

> **Surface-identity integrity:** a curated surface has *one* declared framing (Activity = audit of actor
> actions; System Health = system status & health happenings; Logs = raw), and content belongs to the
> surface whose framing it matches — *not* duplicated across surfaces because each "could" show it.

This is why Axis 2 was wrong: it would have placed health happenings on the audit surface for completeness'
sake, violating identity integrity and duplicating System Health. The principle's candidate scope is the
**window-taxonomy-convergence** cluster (611/612/614 and the System tab group); its existing tension is the
very Health/Activity boundary this pass examined. Named here, owned there — **not** built now.

### §L.5 — Implementation & live validation (2026-06-20) — Axis 1 SHIPPED (in worktree)

Implemented on branch `worktree-612-activity-curation` (FE-only; no backend / wire / schema / catalog
change), exactly the §L.2 design. Five files:

- **`shell-v0/state/messageRouting.ts`** — widened `isRoutineActivity` from navigation-only to a closed
  `EFFECT_ACTIVITY_CLASS` Record over the `Effect` union (a new effect kind fails to compile until
  classified — the §L.5-R2 lesson made structural); added the pure `isRoutineOperation(significance)`
  predicate (LOW · no-confirm · not-FULL_PAYLOAD · no-affects).
- **`shell-v0/operations/ActionLedgerClient.ts`** — `projectBackend` now grades operation rows via
  `getOperation` (the same singleton `present()` already joins), with the `FAILURE`-never-routine and
  fail-toward-foreground guards; effects/navigation still route through `isRoutineActivity`.
- **`shell-v0/components/ActionLedgerView.ts`** — relabelled the reveal chip `navigation`→`routine` (group
  label `hidden`); added the "Only routine activity this session" curated-empty state with an inline reveal.
- **Tests** (`messageRouting.test.ts`, `ActionLedgerClient.test.ts`, `ActionLedgerView.test.ts`) — extended
  with the widened routine set, weight-bearing exclusions, the operation-significance grade (seeded via the
  existing `__seedForTest` registry seam), fail-toward-foreground, the relabelled toggle, and the
  curated-empty state.

**Static + unit:** `npm run typecheck` clean; the 3 suites = 45 tests green; **full suite 340 files / 3325
tests green** (no regression in the History-tab `projectBackend` co-consumer).

**Live browser validation (required gate — passed).** Served the worktree build via its own Vite (the
shared dev stack was owned by another session — used it read-mostly via the FE proxy, never took the lease)
and mounted the real `<jf-action-ledger>` against the live `/api/action-ledger` stream. The backend ledger
was a true flood: **493 user routine effects** (`navigate 411 · save-settings 39 · set-appearance 26 ·
set-ui-mode 13 · toast 3 · noop 1`) + **5 system `index` rows**. Result:

- **Default curated view rendered ONLY the system indexing burst** (`Indexed 5 · justsearch-help`); all
  493 user routine effects hidden — `visibleRows: 0` individual rows, `showRoutine: false`.
- The reveal toggle read **`routine (493)`** (relabelled from `navigation`).
- Clicking it brought **all 493 rows back** (`visibleRows: 493`) — the de-flood is a reversible curation,
  not deletion; the full ledger stays reachable.

This is the §10/593 symptom (`user (494)` vs `system (6)`) cured live: the feed now answers *"what happened
that I should know about"* (the indexing) instead of *"everything I clicked."* (The curated-empty state is
unit-covered; it could not be triggered live because the ledger held system rows.)

**State:** MERGED to `main` (2026-06-20) — `feat(612)` commit `ed686532f`, fast-forward merge `9fd778a07`
(re-verified clean against main's then-HEAD `cff7da2ee`: typecheck + 45 targeted tests green after merging
main into the branch). 612 Axis 1 is **complete, live-validated, and shipped**; Axis 2 remains out of
scope (§L.2).

---

## §R — Forward-looking research: what the curation lens unlocks (2026-06-20)

> Post-implementation research pass (codebase recon + external UX/HCI practice). **No code; ideas only.**
> Nothing here is a commitment — the app has no users and no deadline, so all of this is optional. The
> goal is to record what Axis 1 *makes cheap or possible*, grounded in primitives that already exist, so a
> future pass isn't starting cold.

**Framing — Activity is one ledger seen through composable lenses.** Axis 1 didn't add a feature so much
as a *lens*: a witnessed-ness filter over the one action-ledger (550 §I). The natural shape of everything
below is **"add another lens,"** reusing the projection/filter/seam machinery already shipped
(`collapseBursts`, `FilterChip`/facets, the seen-cursor, the digest) rather than new surfaces. Each lens
conforms to the 550/613 "projections over one record" principle — this is the recurring shape to exploit.

### §R.1 — Polish (the shipped lens)
- **Make the curation legible in the hierarchy.** External practice puts the trust/count signal *in the
  main hierarchy*, not tiny secondary text. The current chip reads `hidden · routine (N)`; a clearer
  header-level signal ("showing 6 of 499 — 493 routine hidden") would make the curation self-explaining.
- **Emphasize failures over successes.** Both render as the same row with a tone glyph; "highlight the
  anomaly" (signal-detection) argues a `FAILURE`/`DENIED` row should be visually heavier than a routine
  success. Pure presentation; reuses `statusTone`.

### §R.2 — Simplify (honest findings — mostly "leave it")
- **The exhaustive `EFFECT_ACTIVITY_CLASS` Record is a feature, not over-spec.** It classifies all ~23
  Effect kinds though only ~6 appear in the live journal — but the closed Record is exactly what forces a
  new effect kind to be *consciously classified* (the §L.5-R2 lesson). Keep it.
- **A latent unification, recognized but not worth building.** The two routine paths (`isRoutineActivity`
  over the Effect union; `isRoutineOperation` over OperationCatalog facets) share one verdict shape
  ("witnessed & weightless?") but read different facet sources. They *could* collapse into one
  `rowSignificance(row) → verdict`. With only two cases this is premature abstraction; record the seam,
  don't build it.

### §R.3 — Extend (high-value, cheap — reuse existing primitives)
- **"New since you last looked" marker — the standout cheap win.** A seen-cursor authority **already
  exists** (`recallCursor.ts`, `justsearch.recall.seen-cursor.v1`: `getSeenCursor`/`markSeen`/
  `isNewSinceSeen`/`subscribeSeenCursor`) and the AI digest already consumes it. Wiring it into
  `<jf-action-ledger>` (mark foreground rows newer than the cursor as "new"; advance on view) is mostly
  *plumbing an existing primitive* and directly serves the unread/"catch me up" pattern that the
  notification-center literature treats as core.
- **A unified "while you were away" digest atop Activity.** `AiActivityDigest` + `summarizeAgentRecall`
  already do a since-cursor rollup — but scoped to *agent* actions. Generalize that projection to all
  *foreground* rows ("3 reindexes · 1 failure · 2 agent runs") and trigger it event-based on opening
  Activity. External evidence: digest/batching is the single most effective anti-fatigue move (and lifts
  engagement markedly). Reuses the digest, the seen-cursor, and `collapseBursts`.
- **A small importance ladder beyond the binary.** Axis 1 made one cut (routine vs foreground); the
  literature strongly favors *group/sort by importance* (errors, security, system). The slot is already
  designed-not-shipped — `EmissionPolicy.importanceFloor` (550 G160/G161). A **2–3 level** ordering
  (routine ‹ normal ‹ notable[failures/denials/grants]) could drive sort + emphasis. Keep it shallow —
  the same literature warns over-tiering recreates the noise.
- **Operation burst-collapse (the §8 lever, now grounded).** `collapseBursts` is generic; grouping
  repeated identical *foreground* operations by `operationId` is the "error-clustering / don't-flood-with-N-
  identical" pattern, for ~free. Add only if a live histogram shows such a run.

### §R.4 — New UX (higher effort — genuinely new capability)
- **Causal chains: "user did X → system did Y" (the flagship).** 612's own motivation names causal chains
  as the thing that must stay visible; the observability literature is built on *correlate events into a
  causal group and show cause→impact*. Today the ledger has only *session* correlation (`correlationId` =
  agent sessionId) and `executionId` (undo-only) — **no per-action effect linkage** exists. Realizing this
  needs a backend correlation seam (stamp the operation that triggered an indexing job / advisory with an
  origin id), then an FE grouping that renders "Reindex → indexed 1,240 · 1 failed" as one expandable
  chain. Highest value *and* highest cost; record as the headline direction, note the missing dependency,
  do not build speculatively.
- **A "needs attention" lane.** A derived view filtered to `outcome=FAILURE` / `disposition=DENIED` /
  recovery transitions — the signal-detection "spotlight the anomaly" pattern, as a filter over the one
  ledger (no new store).
- **LLM "catch me up" line (aspirational).** The app has a local Brain; a one-sentence plain-language
  summary of foreground activity since last visit ("indexing finished; one file failed to extract") could
  ride the digest data through the existing summarization path. Speculative — gated on LLM availability;
  flag, don't plan.

### §R.5 — What NOT to build (premature, per the research)
- **Multi-channel delivery** (email/push/SMS digests) — the notification literature's biggest theme, but
  irrelevant to a local-first single-surface app. Out of scope by construction.
- **Saved-filter management UI** — no users, no demonstrated need; the per-session facets suffice.
- **Deep importance taxonomies / per-category preference panels** — recreate the noise they remove; keep
  any importance signal to 2–3 levels.
- **Causal correlation without its backend seam** — the FE chain view is worthless until the origin-id
  linkage exists; don't half-build it.

**Sources (external practice consulted):** activity-feed / audit-log signal-vs-noise + group-by-importance
([uxpatterns.dev](https://uxpatterns.dev/patterns/social/activity-feed),
[ux-bulletin signal-detection](https://www.ux-bulletin.com/signal-detection-theory-in-ux/),
[AppMaster audit-log patterns](https://appmaster.io/blog/audit-logging-internal-tools-activity-feed));
notification batching/digest + unread + anti-fatigue
([Courier](https://www.courier.com/blog/how-to-reduce-notification-fatigue-7-proven-product-strategies-for-saas),
[MagicBell](https://www.magicbell.com/blog/notification-system-design)); causal/correlated event timelines
([StackState causal observability](https://www.stackstate.com/blog/causal-observability-level-3/),
[OpenObserve incident correlation](https://medium.com/@openobserve/incident-correlation-the-complete-guide-to-faster-root-cause-analysis-50397e589c10)).

> **§R's "add a lens" framing is sharpened by §LT below.** Investigation showed there is no "lens layer"
> to build — and shouldn't be. Read §LT for the corrected structure.

---

## §LT — Long-term design: where a new way-to-see belongs (the facet-altitude rule)

> Design theory, not an implementation slice. Theorizes the *correct structure* for the §R directions
> (and any future Activity lens), grounded in what already exists. No code; nothing here is committed.

### §LT.1 — What already exists (and the deliberate decision §R missed)

The action-ledger is ONE record (550 §I) with many read-surfaces (Activity feed, History/Timeline tabs, AI
digest, advisory inbox). A projection audit shows exactly three things are SHARED, and they are shared
*because they share a reason to change*:

- the **row rendering** — `eventRow.ts`/`renderEventRow` (558 §S1: "one shared event-row across
  Activity/History/Timeline");
- the **live-stream engine** — `openActionLedgerStream` (571 §8: "unify the engine, not the surface");
- the **"what's new" authority** — the seen-cursor `recallCursor.ts` (one definition of new, read by the AI
  digest and any future unread marker); plus the generic `collapseBursts` grouping primitive.

Everything else — *which* rows, *what order*, *what grouping* — is **deliberately inline per surface**.
571 §8/CI-3 considered extracting a shared "filtered-event-stream projector" and **declined it as over-DRY
(AHA), "by reasoned AHA, not omission"**: Activity's facet chips, Timeline's de-dup, History's
session-filter do not share a reason to change, so unifying them would manufacture 548 §1's "two
authorities for one concept" rather than remove it. **So the §R reflex — "add another lens to a lens
layer" — is wrong: there is no lens layer, and 571 already settled that there shouldn't be one.**

### §LT.2 — The rule: add a way-to-see at the altitude of its reason-to-change

The correct question for any new Activity capability is not "where does the lens plug in?" but **"at what
altitude does this fact's reason-to-change live?"** There are exactly three answers, each already
instantiated in the codebase:

1. **A declared FACET on the record** — when **≥2 surfaces would derive the same fact** (613 §7: "encode a
   facet when a second consumer needs it; until then it is a routing *rule*"). The fact is computed once at
   projection/emit time and rides the row; surfaces *filter/sort/group* on it. `isRoutine` and `outcome`
   are the shipped instances.
2. **A shared cross-surface AUTHORITY** — when it is **one fact many surfaces read** but is not a per-row
   property. The seen-cursor is the realized instance ("new since you looked", one authority, many
   readers).
3. **Surface-local logic** — when **only one surface cares**. Activity's facet chips, Timeline's dedup,
   History's session filter. 571 §8 keeps these local on purpose; hoisting them is the over-DRY to avoid.

The placement, not a framework, sets the size of each thing.

### §LT.3 — The §R directions, placed by the rule

- **Unread / "new since you looked"** → **authority reuse (altitude 2)**. The seen-cursor already exists;
  Activity consumes it and renders a per-surface marker (altitude 3). No new structure.
- **Generalized "while you were away" digest** → **projection reuse (2 + 3)**. `summarizeAgentRecall` is an
  agent-scoped projection over the ledger + the seen-cursor; generalizing its predicate to all *foreground*
  rows is a projection edit, not a framework. It stays a (mostly surface-local) projection — there is no
  call to share it until a second surface wants the same rollup.
- **Importance ladder** → **a record FACET (altitude 1)**. Beyond the binary `isRoutine`, a shallow ordered
  `importance` is the second facet on the row; the slot is already *designed-not-shipped*
  (`EmissionPolicy.importanceFloor`, 550 G160/G161). Declare it at emit, sort/emphasize at the surface.
  Keep it 2–3 levels (the over-tiering the external research warns against is just altitude-1 noise).
- **Causal chains ("user did X → system did Y")** → **the one genuinely-new record FACET (altitude 1) +
  the existing grouping shape**. Today the ledger carries only *session* correlation (`correlationId`) and
  undo-only `executionId` — no per-action *cause→effect* link. The correct structure is a **causal-origin
  id stamped on an emitted effect at the federated-contributor boundary** (550 §I — the worker indexing
  job / advisory records the operation that triggered it), then the FE **groups by it via `collapseBursts`**
  exactly as index rows group by collection. This is one declared field + reuse of an existing primitive —
  **not** a causal-graph engine. It is the only §R direction that touches the record shape, and it is the
  flagship because it realizes 612's own "causal/accountability-relevant chains stay visible" intent at a
  truer altitude.

**Scope verdict.** None of these is required by the present problem (Axis 1 shipped; §R is forward-looking),
so **nothing is built here**. But when a direction *is* pursued, this rule fixes where its structure goes —
and crucially says what NOT to add: **no shared lens/projection framework** (571 §8 declined it), **no
causal engine** (one field + group-by suffices), **no deep importance taxonomy** (one shallow facet). The
size of each change is the outcome of its altitude, not a target.

### §LT.4 — Reach: an instance of an existing seam; one rule worth naming

This design invents nothing — it is the **composition of four existing decisions** into a single placement
rule: 550 §I (one record, many projections) · 558 §S1 / 548 §1 (share the derivation that shares a reason
to change; avoid two-authorities) · 571 §8 ("unify the engine, not the surface") · 613 §7 ("encode a facet
when a second consumer needs it"). **Conform to these; do not build a parallel view/query engine — that is
the exact thing 571 §8 already declined.**

The recurring shape they compose is worth naming plainly:

> **The Facet-Altitude Rule.** A new way to see a shared record is added at the altitude of its
> reason-to-change — a **declared facet on the record** when ≥2 readers derive it, a **shared cross-surface
> authority** when it is one fact many surfaces read, or **surface-local logic** when only one surface
> cares — **never as a parallel view/query engine**. ("Project at the record; filter at the surface.")

**Candidate scope beyond Activity (named, not built):**
- **Search-result rendering — an existing violation.** `SearchResultsRenderer` is a *third* search-result
  renderer reading raw `hit` fields instead of the shared `projectResultView`/`resultRowPresentation`
  (observations.md, 602 R3) — a surface re-deriving a row projection that shares a reason to change. The
  Facet-Altitude Rule names exactly why that is a defect (altitude-1 fact derived per-surface), and 570's
  "result-as-projection" is its altitude-correct fix.
- **Timeline ordering drift (minor).** RetrospectivePanel re-derives newest-first inline (`.reverse()`)
  instead of the shared `newestFirst` helper — a small "re-derived the shared concern" slip.
- **Legitimately NOT a violation:** the advisory inbox projects a *different* record (`AdvisoryStore`), so
  its own faceting is correct (different reason to change); the rule only adjudicates if a future
  advisory↔Activity merge is proposed (613's PUSH/PULL boundary).

Do **not** build a placement-enforcing gate or a generalized projection layer now: 571 §8 already declined
the framework, and the rule is investigator-judgment (like AHA itself). Recording the rule + its one named
existing violation (602 R3) is the deliverable — separating "recognizing the shape" from "building the
structure," so the insight is captured without becoming the premature abstraction it warns against.

---

## §UX — User-facing design for the forward-looking lenses (live-grounded, 2026-06-20)

> §R/§LT settled the *structure* (where each facet lives). This section asks the orthogonal question —
> **what would the user actually see and do** — by inspecting the running product, not the tempdoc. As with
> the Axis-2 finding, the live UI changes the design. No code; design only.

### §UX.1 — What the running feed and its neighbours actually look like

On the live (post-merge) Activity tab:
- **The curation is working** (`who: user (492) / system (8)`, `hidden · routine (492)`; the default view
  is one row — "Indexed 8 · default"). Revealing routine shows the wall of "Navigate to …" rows.
- **Rows are visually UNIFORM** — `[who-badge] [label] [source] [relative-time]`. The only differentiation
  is the who-badge shade and, for operation rows, the success/failure outcome glyph (statusTone). There is
  **no importance hierarchy** and **no "new since you looked" marker** — recency is conveyed only by the
  relative-time column ("this minute" vs "12 minutes ago").
- **The feed already COMPRESSES**: the index run renders as one collapsed burst row ("Indexed 8 · default",
  a count), not 8 rows (`collapseBursts`).
- **A "while you were away" digest already exists — but it is a separate, global, agent-scoped overlay.**
  `<jf-ai-activity-digest>` mounts in the shell's top-center overlay slot (Shell.ts), an accent-info card
  that summarizes *agent* actions since the seen-cursor and offers **"undo all AI actions"** — i.e. its job
  is **autonomy review** (catch the user's attention anywhere; review+undo what the AI did), not general
  situational awareness. It reads the same seen-cursor a feed unread-marker would.

### §UX.2 — The user-facing design, refined by the live UI

Three of the four §R directions get *smaller and more grounded* once you see the product; one is partly
redundant.

- **Unread marker — the high-value, non-redundant catch-up (do this one).** For a user *who is already in
  Activity*, "what's new since I last looked" is best as a lightweight in-feed affordance — a **"new"
  divider / per-row dot** on foreground rows newer than the seen-cursor, advancing on view. It reuses the
  existing seen-cursor authority and sits inside the existing row. It must read as **new-since-visit**,
  *distinct* from the relative-time column (which is new-in-time) — the two answer different questions.
- **A separate "while you were away" *feed-header* digest is largely REDUNDANT — drop it.** The feed
  already does what a header digest would: it compresses runs (burst-collapse) and an unread marker frames
  "what's new". A summarizing band atop the same rows would be **a second authority over one concept**
  (548 §1) — restating rows the user is looking at. So the §R "generalized digest" should NOT become an
  Activity-feed header.
- **…and do NOT broaden the global agent overlay into a general digest.** By surface-identity integrity
  (§L.4), the agent-autonomy overlay (review-and-undo *AI* actions, global) and general situational
  awareness (what happened, no undo, local to Activity) are **different jobs**; merging them dilutes the
  autonomy-review surface. The overlay stays agent-scoped; Activity's own unread marker is its catch-up.
- **Importance — visual emphasis, reusing the existing tone vocabulary (no new authority).** Rows are
  uniform today, so a notable row (a `FAILURE`, a `DENIED` gate, a durable grant) should read *heavier* —
  but via the **existing `statusTone`/outcome-glyph vocabulary** (e.g. a tone-tinted left-accent on the
  row), never a parallel importance-colour system (548 §1). Sort/emphasis derive from the altitude-1
  `importance` facet (§LT), so the *look* is "the existing failure red, extended to the other notable
  classes," not a new palette. Keep it to 2–3 visible weights.
- **Causality — the UX is already prototyped by the burst row.** The collapsed burst ("Indexed 8 ·
  default", count + implied expand) is exactly the visual a causal chain wants: "Reindex → indexed 8 · 1
  failed" as **one summary row that expands to its effects**. So causality reuses the *existing
  burst-summary rendering*, grouped by the new causal-origin id (§LT) instead of by collection — no new
  row genre, no expandable-tree component to invent. The only genuinely-new user-visible element is the
  causal *label* ("X → Y"); the container already exists.

### §UX.3 — Net user-facing verdict

The live pass shrinks the forward-looking work to its honest core: **one new affordance worth building (the
unread marker)**, **two that are presentation-only re-uses of existing vocabulary** (importance emphasis
via statusTone; causal chains via the burst-summary row), and **one to drop** (a feed-header digest — the
feed already compresses + an unread marker suffices; the global agent overlay keeps its distinct
autonomy-review job). Nothing here is a new surface or component — every piece extends something the user
already sees, which is the same outcome the witnessed-redundancy + surface-identity-integrity principles
(§7/§L.4) predict. Still forward-looking; nothing built.

---

## §AF — Frontier check: the agentic-observability dimension (internet research, 2026)

> Most of 612 (activity-feed de-noising, unread, digest) is on *mature* UX ground already researched in §R.
> But one slice — the `user | agent | system` attribution, the "undo all AI actions" autonomy overlay, and
> the §LT **causal-origin** facet — sits on a *fast-moving 2026 frontier* (AI-agent action observability,
> provenance, human oversight). This pass checked that frontier specifically. Design-only; nothing built.

### §AF.1 — What the frontier validates in 612 (the design is on the right side)
The 2026 agentic-audit literature names, as core requirements, almost exactly the facets 612's record
already carries — so this is *confirmation*, not a redesign signal:
- **Attribution** (who acted) — completeness + point-in-time accuracy + attribution + integrity are the
  named demands of an agent audit trail; 612's `originator` (user/agent/system) + `correlationId` is that.
- **Guardrail / approval decisions logged + human-in-the-loop** — pausing for approval on high-stakes
  actions and recording overrides/approvals is the headline HITL pattern; 612 already keeps **gate**
  (`GATED`/`DENIED`/`APPROVED`) and **grant** rows foreground. The importance ladder (§LT/§UX) — *notable =
  guardrail/failure/high-risk* — is the same "signal vs noise" cut the agent-observability tools make.
- **Outcome tagging + undo/time-travel** — success/failure labels and "rewind what the agent did" map to
  612's `outcome` facet and the existing `executionId` undo + the "undo all AI actions" digest.

### §AF.2 — The one design refinement: the causal-origin facet should conform, not parallel
The frontier's flagship construct is the **"Action Provenance Graph"** — link prompt → plan → tool
invocation → outcome and reconstruct the *causal pathway* — and the emerging **OpenTelemetry GenAI semantic
conventions** model it concretely as a **parent→child span tree** (`invoke_agent` → child `execute_tool`
spans, correlated by trace/span ids). That is the same shape §LT's "user/operation did X → system did Y"
chain wants. Conclusions, held to the doc's "conform, don't parallel" discipline:
- **Model the §LT causal-origin id as a parent→child correlation link** (the span-tree / provenance-graph
  shape the field has converged on), **extending JustSearch's own existing trace seam** (`correlationId` /
  `executionId` / 549's `SearchTrace`) rather than inventing a novel scheme. The causal grouping the FE
  renders (via the existing burst-summary row, §UX) is then a *projection of that parent-child link* —
  conforming to both the app's trace seam and the industry shape at once.
- **Do NOT adopt OpenTelemetry GenAI wholesale.** It is an *ops/telemetry* standard (tokens, model,
  messages, tool params — for engineers debugging the agent), a different audience and altitude from 612's
  *end-user* audit feed. Borrow its *shape* (parent-child causal link), not its attribute vocabulary.
- **Do not hard-bind to the standard yet:** OTel GenAI is **experimental / "Development" status in 2026**
  (vendors support it, but the API isn't stable) — another reason to conform to the *shape*, not the spec.

### §AF.3 — Net
The frontier check produced **no change to the shipped Axis 1 and no new scope** — it *validates* that
612's attribution/guardrail/outcome/undo model is aligned with where agentic auditing is heading, and it
*sharpens one forward-looking item*: when the causal-chain view is built, its origin-id should be a
parent→child link extending the app's trace seam (matching the industry provenance-graph/span-tree shape),
not a bespoke field. Recorded as conformance guidance for the §LT flagship; still nothing built.

**Sources:** agentic AI observability + audit-trail provenance + HITL
([Arthur agentic-observability playbook 2026](https://www.arthur.ai/column/agentic-ai-observability-playbook-2026),
[Arize 2026 agent observability](https://arize.com/blog/best-ai-observability-tools-for-autonomous-agents-in-2026/),
[agent audit-trail / Action Provenance Graph](https://truescreen.io/articles/agent-to-agent-audit-trail/));
OpenTelemetry GenAI semantic conventions (agent + tool spans)
([OTel GenAI agent spans](https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-agent-spans/),
[OTel GenAI observability blog 2026](https://opentelemetry.io/blog/2026/genai-observability/)).

---

## §CI — Pre-implementation confidence pass (2026-06-20, source-verified; no code)

> De-risked the four assumptions the forward-looking items rest on, before any is built. Verdicts below;
> two corrected the optimistic framing in §UX/§LT/§AF. Design-only; nothing built.

- **R1 — the unread marker's seen-cursor reuse: CONFIRMED (and my "contention" worry was backwards).**
  `recallCursor.ts` (`justsearch.recall.seen-cursor.v1`) was created by **577 §2.14 specifically to be the
  ONE "seen" authority** — its docstring names "any 'new since you looked' marker" as an intended reader,
  and a per-surface cursor is exactly the "two authorities for recall" it *removed*. So the unread marker
  **must reuse this one cursor**; a second cursor would re-introduce the bug 577 fixed. The single open item
  is *interaction*, not structure: the cursor is monotonic-to-newest and the agent digest advances it to
  the newest *agent*-row time (`markSeen(digest.latestIso)`); whether Activity should also advance it on
  view is a product decision (one shared "caught up" boundary is the documented model). **Confidence: high.**
- **R2 — marker placement vs the shared row: CONFIRMED clean.** `renderEventRow(entry)` takes one entry; a
  "new" affordance is an **additive opt-in** — an optional param Activity sets (History/Timeline pass
  nothing → no marker) or the marker in `ActionLedgerView`'s own row wrapper. No fork of the shared row,
  no leak (558 §S1 intact). **Confidence: high.**
- **R3 — importance is FE-derivable, with a tiny correction to "reuse statusTone unchanged".** A 2–3 level
  importance needs NO new facet / no `importanceFloor`: `UnifiedActionEntry.outcome` already mirrors
  disposition/state/action across kinds (gate→`DENIED`/`GATED`/`APPROVED`, index→`FAILED`, grant→
  `ISSUED`/`REVOKED`), so notability derives from `{kind, outcome}` exactly as `isRoutine` is FE-derived.
  *Correction to §UX:* the single `statusTone` map currently tones only `FAILURE`/`FAILED`/`APPROVED` — it
  returns `neutral` for `DENIED`/`GATED`/`REVOKED`/`ISSUED`, so expressing trust-notable emphasis needs ~4
  labels added to that one map (a tiny extension of the single authority, **not** a new colour authority).
  **Confidence: high (FE-only, small).**
- **R4 — causal-origin plumbability: CORRECTED — the flagship is bigger than §LT/§AF implied (verdict (b)).**
  A `correlationId` exists at Head dispatch (`InvocationProvenance`), but it is **dropped crossing to the
  Worker**: the gRPC `BatchRequest`, the `IndexingJobView`, and `ActionEvent.Index` carry no operation id,
  and `ReindexHandler` doesn't even take the provenance-aware form. 550 §B's "Worker→Head carries only
  `pathHash`, no correlation id" is **still true on main**. So full causal linkage for the canonical (index)
  case is **net-new cross-process work**: +`operation_id` on `BatchRequest`, +`triggering_operation_id` on
  `IndexingJobView`, +a field on `ActionEvent.Index`, plus bridge/handler plumbing — and **each additional
  effect class (advisory emissions, etc.) needs its own equivalent plumbing**. The FE grouping (via
  `collapseBursts`) stays cheap; the cost is the backend correlation seam. §LT/§AF's "extend the existing
  trace seam" stands directionally (the seam *is* `correlationId`), but it is **not mostly-FE** — the seam
  must be *carried across two process hops it currently isn't*. **Confidence: the path is fully known; the
  cost is moderate-large, not cheap.**

### §CI.1 — Critical confidence rating for the remaining work (per item — they differ sharply)
- **Unread marker (the one §UX "do-this"): 9/10.** Purpose-built primitive, clean additive placement,
  FE-only; the only residual is an interaction choice (cursor-advance-on-view), not a technical unknown.
- **Importance emphasis: 8/10.** FE-derivable from existing fields; one small single-authority tone-map
  extension; FE-only.
- **Causal-chain flagship: 6/10.** No unknowns left (the wire path is fully enumerated), so risk is low —
  but cost is moderate-large (multi-wire cross-process additions, repeated per effect class), so confidence
  in "this is cheap" is low. Build only with that cost accepted; conform the new id to the trace-seam /
  provenance-graph shape (§AF).
- **Overall remaining work: ~7/10** — high for the committed cheap item, lower for the (well-understood but
  expensive) flagship; the digest is correctly dropped (§UX).

---

## §IMPL — Implementation record: the two FE follow-ups SHIPPED (2026-06-20)

> The two FE items the passes endorsed are now **implemented, browser-validated, and merged to `main`**
> (`feat(612)` commit `97968af94`, FF merge `20b34e44d`). The digest stays dropped; the causal flagship
> stays deferred (§CI R4). This section records what shipped and reconciles two places where the build
> *deliberately* differs from the earlier §UX text (so the doc and the code agree).

**Unread "new since you looked" marker** (`ActionLedgerView.ts` + the shared `eventRow.ts`). Foreground
rows newer than the ONE shared seen-cursor (`recallCursor.ts`, the 577 authority — §CI R1) get a per-row
mark + a "N since you looked" header; the mark is an additive opt-in on the shared row, so History/Timeline
never show it. Live-validated: marks appear, "mark all read" advances the one shared cursor and clears them.

**Trust-disposition tone** (`statusTone.ts` — the one tone authority). `DENIED→error`, `GATED`/`REVOKED→
warning`, so a denial/revocation reads as notable, not neutral. Live-validated (a DENIED gate renders the
error tone). No new colour authority.

**Reconciliations (build vs the earlier §UX text — the build is the authority where they differ):**
- **§UX.2 #1 said the marker "advances on view"; it ships as an explicit "mark all read" instead (no
  auto-advance).** This is the §CI R1 correction made concrete: the seen-cursor is a *shared* boundary the
  AI digest also advances, so silently advancing it because the user glanced at Activity would clear the
  digest's catch-up. Explicit "mark all read" (the notification-literature norm) is the safer choice. The
  "advancing on view" phrasing in §UX is superseded by this.
- **§UX.2 #4 listed "a durable grant" as a notable row to emphasize, and suggested a row-level accent;**
  the shipped slice tones only `DENIED`/`GATED`/`REVOKED` (outcome-cell tone), leaving an `ISSUED`/
  `CONSUMED` grant **neutral** and adding **no row-level emphasis or sort**. This is the deliberately-minimal
  importance scope (§CI/plan): grant rows are already *foreground* (never routine), so an issued grant is
  *visible*; only its extra tone-emphasis is omitted, because issuance is less alarming than a denial/
  revocation. A fuller importance ladder (issued-grant emphasis, row-level weight, sort) remains a
  recorded, un-built §LT extension — not a defect, a narrower scope.

**Net:** §UX item 1 is fully delivered (with the advance-on-view → mark-all-read correction); §UX item 4 is
delivered as its minimal tone slice; items "drop the digest" and "defer causality" are honored. The
forward-looking work that the design endorsed building is **done and shipped**; what remains (causal chains,
a fuller importance ladder) is recorded as scoped-but-unbuilt §LT/§AF directions.

---

## §F2 — Second forward-looking pass: what the SHIPPED marker + tone unlock (2026-06-20)

> Unlike §R (which speculated *before* building), this pass is grounded in the **shipped** code (the unread
> marker + trust-disposition tone, now on `main`) + a codebase recon + one targeted badge-UX check. It asks
> two questions the earlier passes couldn't: *is the shipped code clean?* and *what does HAVING the
> marker/tone make cheap next?* Design/ideas only; nothing built. Deliberately does NOT re-tread §R/§LT/§UX.

### §F2.1 — Polish (real, on the merged code)
- **The `seenCursor` reactive field is a re-render tickle, not a render dependency.** `ActionLedgerView`
  declares `seenCursor` as reactive state and assigns it on cursor change, but render never reads it —
  `isNewRow`/the burst call `isNewSinceSeen()` (the live module read) directly. Cleaner: have `isNewRow`
  compare `e.occurredAt > this.seenCursor` so render is a pure function of the reactive state (field
  genuinely read), or drop the field and `requestUpdate()` in the subscription. Small, real.
- **Two visual treatments for one fact.** A new row gets BOTH a left box-shadow accent (`[data-new]`) AND a
  `.new-dot`. The badge-UX literature says a single calm "something new" signal (the dot) is enough;
  collapsing to one treatment is a tidy simplification (and reduces visual weight per the badge-fatigue
  guidance below).
- *Otherwise the slice is tight* — it reuses the one seen-cursor, the one tone authority, and the shared
  row via an additive opt-in; there is no over-structure to remove (an honest "leave it").

### §F2.2 — Extend (grounded in the recon — existing primitives)
- **Cross-surface unread on the rail (the headline).** The seen-cursor is a *shared authority*, so its reach
  isn't limited to the feed: the left rail could signal "new in Activity" on the `core.activity-surface`
  icon, reusing the existing `AdvisoryRailBadge` count-pill (the advisory bell already badges) or the
  (currently dead, AI-scoped) `.activity-dot` precedent in `Shell.ts`. **Calibrated by the badge research:**
  badge only the *foreground* new (which the curation already makes infrequent — the precondition for a
  non-fatiguing badge), use a **dot** for "something new" and reserve a **count** for the higher-priority
  *needs-attention* subset (failures/denials), and ensure it **clears on mark-read** (we already have
  "mark all read"). This is the Slack two-signal pattern, and it conforms to the facet-altitude rule
  (shared authority, a new reader — no new cursor).
- **A "notable-only" facet + a "needs attention" lane.** Now that tone/disposition distinguishes notable
  rows, a `notable` FilterChip (reusing the existing who/outcome facet machinery) and a derived
  failures/denials/revocations lane are cheap — the lane is the `gateFirings(entries)` pure-projection
  pattern (`entries.filter(e => e.outcome==='FAILURE' || e.outcome==='DENIED' || …)`), reusing the one
  stream. A **standing "N unresolved failures" signal** is a pure projection over the ledger + seen-cursor
  (the `summarizeAgentRecall` shape).
- **Notable-first sort** — the one genuinely net-new bit (a priority sort over `newestFirst`); small.

### §F2.3 — New UX
- **A two-tier ambient signal** (a calm dot for "new foreground activity" + a count for "N needs
  attention") is the genuinely-new cross-surface feature — it turns the curation's *foreground/notable*
  distinctions into an at-a-glance signal the user sees without opening Activity, while staying
  badge-fatigue-safe because it rides the curation (routine is never signalled).

### §F2.4 — What NOT to build (per the badge research + the doc's own principles)
- **No count badge for *general* activity, and never badge *routine*.** "If everything has a badge, none
  feel important"; the feed includes lots of routine, so only the curated foreground/notable subset is
  ever signalled — this is the **witnessed-redundancy principle applied to badges** (don't badge what the
  user has already seen or what is always-present). A general count would manufacture the fatigue the
  curation removed.
- **No second cursor for the rail** — it reads the one shared seen-cursor (§CI R1).
- **No new badge atom** — `AdvisoryRailBadge` / `StatusBadge` already exist.

### §F2.5 — Reach
The cross-surface rail unread is the **facet-altitude rule** (§LT) made concrete: a *shared authority*
(the seen-cursor) gaining a new reader (the rail), exactly as predicted — conform, don't add a parallel
mechanism. And the badge-fatigue discipline is the **witnessed-redundancy principle** (§7) extended to a
new altitude (ambient signals): a signal must be non-redundant with what the user already perceived. Both
are existing principles applied, not new ones — recorded as candidate scope for any future ambient-signal
work, not built now.

**Sources:** badge UX (dot-vs-count, badge fatigue, mark-read-to-clear)
([Setproduct badge patterns](https://www.setproduct.com/blog/badge-ui-design),
[PatternFly notification-badge guidelines](https://www.patternfly.org/components/notification-badge/design-guidelines/),
[Povio — when to use badges](https://povio.com/blog/product-design-badges)).
