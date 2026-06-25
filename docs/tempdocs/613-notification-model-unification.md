---
title: "Notifications need one product model and placement strategy"
type: tempdocs
status: "MERGED to main 2026-06-19 (merge `e210f2312` — P1–P6 + reindex/capability coherence; FE-only; 338 files / 3284 tests + typecheck + both gates green). The ONE open USER decision was RESOLVED 2026-06-20 → **DELETE** the unreachable legacy `CapabilityPills` (§12.P6 DECISION): it projects correctly + is gate-guarded but renders nowhere in the unified-window IA, and a chrome pills strip would re-state the banner — the over-notify this doc prevents. The durable keepers (the `projectAvailability` projection + the `check-capability-availability` gate) stay and guard the reachable surfaces; only the dead component is removed. End-to-end deletion plan tracked separately; §16 AS-BUILT for the treatment slice already merged. PRIOR: open — DESIGN PASS done (2026-06-19, §DESIGN below; deepened from an earlier surface-isomorphic draft): the messaging substrate is complete and each surface is single-authority. The reframed gap is that 'notification' conflates TWO different things — PUSH events (announce a discrete happening: receipt / toast / advisory / Activity) and PULL/ambient state (continuously reflect a standing condition: degradation banner, unavailable-reason). Only PUSH needs routing; PULL is already state-projection (verdict / availability) and is OUT. The decisive structural finding: PUSH events already carry a required, invariant-enforced per-class declaration — `EmissionPolicy(renderHint, dedupeWindow?)` on advisory-kind Resources (`advisoryKindRequiresEmissionPolicy`) — while PULL surfaces have none. So the long-term design is NOT a new catalog: it is to recognize `EmissionPolicy` as the existing routing-declaration seam, broaden its facets to the product meaning, DERIVE the surface from facets that already exist as declared facts (trigger from the action-lifecycle ledger; horizon from RenderHint; locality from emit-source; severity), and bring the ad-hoc FE local `emitEphemeralToast` path under the same declaration. The ONE genuinely-new structure (610-style) is a routing DERIVATION (surface = f(facets)) replacing per-site surface hard-coding, plus a 'don't push what's already pulled' suppression boundary. Rejected: a fan-out emission engine (the problem has suppression constraints, not multi-projection needs) and folding the pull surfaces into one table. LIVE UI INSPECTION done (2026-06-19, §10): the running app (in a degraded state) showed ONE root condition surfaced across FIVE loci with THREE different wordings (banner / brain banner / health pill / advisory inbox raw 'ReindexRequired' / status 'Offline'), and the Activity feed FLOODED 494 user-navigations to 6 system events because every navigation is BOTH toasted AND journaled. Two user-facing refinements folded in: (1) split the suppression boundary by kind — PUSH transients 'don't repeat' (binary) vs PULL standing conditions 'project ONE identity + worded family from the existing readinessNotice to every locus at its altitude' (coherence, the dominant felt problem); (2) the navigation de-flood (toast yes, durable Activity no — the 612 seam) is the lead demonstrable outcome. No new components — the substrate is visually complete (602 R4 placement fix confirmed live). CONFIDENCE AUDIT done (2026-06-19, §11): mechanism CONFIRMED+strengthened (PUSH/PULL real & enforced via EmissionPolicy-present⇔push; de-flood is a genuine TWO-emit routing change; decision locus = the existing effect substrate which already carries the originator facet). Two claims CORRECTED: (U1) the 5 reindex loci are THREE independent derivations of one upstream worker-compat cause forked into 3 wire fields + 3 FE wording authorities (verdict/readinessNotice vs hardcoded BrainSurface compatState vs backend schema.reindex-required Condition) — so the coherence half is medium-scope/cross-module, gated on a product choice (reword-to-one-identity vs enforce-non-contradiction); (U4) local toasts BYPASS classChrome so the free-form classId is a latent governance gap, NOT a warning that ships — the 'provable leak/smallest first move' framing was overstated. Critical confidence for remaining impl: 6/10 (routing/de-flood ~8, coherence ~4-5, classId-gate ~7). No code yet. PRIOR: problem statement only."
created: 2026-06-18
author: user walkthrough notes, filed by agent
category: frontend / ux / messaging / notifications / design-system
related:
  - presentation-adjacent-authorities
  - presentation-authority-depth-coverage
  - per-instance-vs-global-frontend-scope
  - unavailable-affordance-reason-authority
  - degradation-cause-not-observable
  - activity-feed-non-user-action-semantics
  - residual-walkthrough-findings-fe-reliability-and-consistency
  - frontend-presentation-kernel
---

# 613 - Notifications need one product model and placement strategy

> What this document is. A short problem statement from a user walkthrough. It records the idea for a
> future design pass only; it deliberately does not prescribe implementation.

## Problem

Notifications need a clearer product routing model. JustSearch already has important pieces of a
messaging substrate: advisory-backed ephemeral toasts, shared `SystemNotice` presentation, degradation
banners, point-of-control unavailable reasons, in-control copy receipts, Activity/history, and transient
layer placement rules. The remaining problem is that the product rules for choosing between those
surfaces are not explicit enough.

The unification question is therefore not "make every notification use one component." It is "when is a
message a local receipt, transient toast, persistent system notice, advisory/inbox item, Activity event, or
blocking ceremony?"

## Why it matters

Without a unified routing model, the UI can over-notify, under-notify, stack messages, place messages
where they occlude controls, or use different treatments for the same user meaning. Users then cannot
predict what needs attention, what auto-resolves, what is merely a transient acknowledgement, and what
will remain visible in Activity or an advisory history.

The model also needs to preserve altitude. A copy action can be acknowledged locally in the control. A
degradation cause should usually be stated once at the window/banner level instead of repeated as several
smaller messages. Direct user actions and durable background consequences may belong in Activity, while
short receipts should not become permanent history by default.

## Boundary

This generalizes several narrower residual findings in the
`residual-walkthrough-findings-fe-reliability-and-consistency` tempdoc, but should not reopen fixes that
are already settled there. Navigation feedback is evidence for singleton/supersede and non-occluding
placement rules; copy confirmation is evidence for local receipt treatment, not a request for a global
copy toast.

This should not become a grab bag of individual toast fixes, nor a mandate that every message render in
one visual channel. Previous presentation-authority work deliberately separated message lifecycle,
notice presentation, observed-state banners, and transient-layer placement. The point of this tempdoc is
to define the product taxonomy and routing table across those existing mechanisms.

Later design work should classify message types, severity, placement, persistence, stacking/supersede
rules, altitude, and relationship to Activity. This document only opens the unification problem.

---

## DESIGN PASS (2026-06-19, source-verified against `main`)

> **What this part is.** The problem statement above is preserved verbatim; this is the design round it
> asked for. The method follows 610's discipline: make the missing structure *structural by extending an
> existing authority*, add **exactly the one genuinely-new datum** the problem requires, and **reject
> speculative structure** for cases the product does not yet include. The size of the change is an outcome
> of that judgment, not a target. Every claim about the current substrate is verified at `file:line`.
>
> **This draft deepens and corrects an earlier one** that proposed a "7-class taxonomy + routing table"
> and a "complete `advisoryClassChrome` + correspondence gate" fix. Two faults made that draft a
> short-term fix dressed as a design: (a) its taxonomy was **isomorphic to the surface list**, so "route
> by class" carried no information beyond "the surface you already picked" (§3); and (b) it treated all
> eight surfaces as one routing space, missing that half of them are not routed at all (§2). The corrected
> design below is what the real problem requires — no more, no less.

### §1 — The substrate inventory (verified; complete and single-authority)

The problem statement is right that "important pieces of a messaging substrate" exist. The audit found the
substrate is not merely present — it is **complete, and each surface already has one authority**:

| # | Surface (product meaning) | Authority / owner (file) |
|---|---|---|
| 1 | **Local receipt** (in-control "that worked") | `components/Control.ts` (in-button flash, queued-action receipt; 602 R7) |
| 2 | **Transient toast** (auto-resolving ack) | `emitEphemeralToast` → `AdvisoryStore` → `AdvisoryToastHost` (`components/advisory/`); 559 Authority III |
| 3 | **Standing advisory / inbox** | `SystemNotice.ts` + `AdvisoryStore` + `AdvisoryInboxDrawer.ts` (right-drawer); 490/494 |
| 4 | **Degradation banner** (one cause + remedy) | `state/verdict.ts` `computeVerdict` + `state/readinessNotice.ts` `CAUSE_ROWS`; 600/595 |
| 5 | **Point-of-control unavailable reason** | `state/availability.ts` `projectAvailability` + `Control.ts` WCAG-1.4.13 tooltip; 596 |
| 6 | **Activity event** (durable history) | `views/ActivitySurface.ts` → `ActionLedgerView` over the action-lifecycle ledger; 550 |
| 7 | **Blocking ceremony** (confirm-before-proceed) | `ConfirmDialog.ts` via `ModalController` / `AuthorizationHost.ts`; 574 Move 4 |
| 8 | **Transient-layer placement** (where 1–3 dock) | `chrome/OverlayHost.ts` slots + `primitives/transientController.ts`; 574 |

The mechanism-level *dispatch* is also already unified: the `Effect` union + `dispatchEffectToChrome`
(`substrates/actions/index.ts:443`) renders an effect-kind (`toast`, `open-modal`, `copy-to-clipboard`, …)
to chrome. So neither surfaces nor dispatch are missing. The gap is one level up: the *decision*.

### §2 — The reframe: "notification" conflates PUSH and PULL

The decisive observation the first draft missed: the eight surfaces are not one routing space. They split
into **two fundamentally different kinds of thing**, and only one of them is "routed" at all.

- **PUSH events** — a discrete thing *happened* at a moment, and the system must decide *where to announce
  it*: a local receipt (#1), a transient toast (#2), a standing advisory (#3), or a durable Activity row
  (#6). These are *emitted*.
- **PULL / ambient state** — a *standing condition* that the UI continuously *reflects at its natural
  locus*: the degradation banner (#4) is a projection of the one system verdict; the unavailable-reason
  (#5) is a projection of an affordance's `Availability`. These are *derived*, not sent. Nobody "chooses"
  to route a degradation cause to the banner — the verdict authority *is* the banner.

The blocking ceremony (#7) is a third, separate thing: it is **pre-hoc authorization** ("do you approve
*before* this acts"), governed by the trust lattice (`RiskTier × SourceTier`, `CoreOperationCatalog.java`)
and the typed-confirm queue — not a *post-hoc* message about something that already happened. It is out of
the notification-routing problem by lifecycle phase.

**This split is already real in the substrate, not a theory imposed on it.** PUSH advisories carry a
required `EmissionPolicy` (§5); PULL surfaces (verdict, availability) carry none — they are state
derivations with their own single authorities and their own gates (`verdict-derivation`, `controls-a11y`).

**Consequence for scope:** the routing model governs **PUSH events only** (#1/#2/#3/#6). PULL (#4/#5) and
pre-hoc ceremony (#7) stay where they are. The routing model's *only* relationship to PULL is a
**suppression boundary** (§6): a push event must not re-announce a condition that an ambient surface
already shows. This is the structural form of the problem statement's "state a cause once at the
window, not as several smaller messages" — and of 602 §UX's live-verified "defer to the banner, don't
double-surface" rule.

### §3 — Why the surface must be DERIVED, not labelled (the corrected core)

A taxonomy whose classes map 1:1 onto the surfaces (receipt-class→receipt-surface, toast-class→toast,
…) is circular: naming the class *is* naming the surface, so the "routing model" adds nothing an author
didn't already decide. That circularity was the first draft's central fault.

The non-circular model is a **derivation**: a push event is described by a few *meaning-facets*, and the
surface is a **function of those facets** — `surface = f(facets)`. The author states *what the message
means*; the model decides *where it goes*. Now the model carries information, the altitude rules become
**properties of `f`** rather than prose pleas, and "the same meaning gets different treatments at
different sites" becomes impossible because the same facets always produce the same surface.

This is the codebase's own **projection-vs-fork** pattern (553), applied to messages: the meaning-facets
are the canonical source; each surface placement is a projection; two emit sites that describe the same
logical event with disagreeing surfaces are a *fork*.

### §4 — The routing facets already exist as declared facts

The strong "extend, don't invent" finding: the inputs `f` needs are **already modelled** elsewhere — as
separate, declared facts scattered across surfaces. The missing structure is not the facts; it is the
*one function that consumes them together*.

| Facet (what `f` needs) | Meaning | Already declared as | Status |
|---|---|---|---|
| **trigger / originator** | did the user directly cause-and-witness this, or is it background / system / agent? | action-lifecycle ledger `originator: user \| agent \| system` (550); effect journal stamps it (`substrates/actions/index.ts`) | **exists** — also the axis 612 routes Activity on |
| **horizon** | does it auto-resolve (transient), stand until resolved, or become a permanent record? | `RenderHint = EPHEMERAL \| PERSISTED \| REQUIRES_ACK`, stamped per-event from `emissionPolicy.renderHint` (`AdvisoryStore.ts:324`) | **exists** (producer side) |
| **locality** | is it about the one control the user is touching, or a window / system condition? | implicit in emit *source*: a `Control.ts` emit is at-control; an `AdvisoryStore` emit is window-scoped | **implicit** — the one under-declared facet |
| **severity / tone** | info / success / warning / error | `MessageSeverity` (`ephemeralToast.ts:19`), orthogonal to horizon by design | **exists** |

So three of the four facets are already first-class declared data, and the fourth (locality) is latent in
where the emit originates. `f` does not need new inputs — it needs to *read the inputs that exist* instead
of having each call site hard-code the output.

### §5 — The existing primitive to extend: `EmissionPolicy`

The seam already exists. `EmissionPolicy(renderHint, dedupeWindow?)` (`app-agent-api`, verified via
`EmissionPolicyTest` / `ResourceAdvisoryInvariantsTest`) is a **required, invariant-enforced, per-class
declaration** of how an advisory class is surfaced:

- advisory-kind Resources **must** declare one (`advisoryKindRequiresEmissionPolicy`);
- non-advisory kinds are **forbidden** one (`nonAdvisoryKindRejectsEmissionPolicy`) — which is exactly the
  PUSH/PULL split of §2, already enforced;
- it already carries two routing facets: **horizon** (`renderHint`) and **dedup/stacking**
  (`dedupeWindow`).

`EmissionPolicy` is therefore the skeleton of the routing declaration — a producer binding a message
*class* to its surfacing *policy*, not a render component. The long-term design is to **grow this existing
declaration into the routing authority**, in two general moves:

1. **Broaden its facets to the product meaning.** Today it encodes horizon + dedup. The product routing
   decision additionally needs *locality* and the *trigger/severity*-derived altitude, so the surface
   (`f`) can be derived rather than presupposed by the advisory channel. (`EmissionPolicy` currently always
   implies "advisory channel"; the broadened policy is what lets a class resolve to receipt vs toast vs
   advisory vs Activity.)
2. **Bring the FE-local path under the same declaration.** The genuine hole is `emitEphemeralToast`
   (`ephemeralToast.ts`): unlike a backend advisory, it carries *no* declared policy — every call site
   hand-picks `severity` / `renderHint` (implied EPHEMERAL) / `classId` (free-form, default
   `core.ephemeral`) / `supersede`. The provable symptom of this hole: the app emits `core.navigation`,
   `core.ephemeral`, `core.req-ack`, `operation.completed`, `health.recoverable`, but
   `AdvisoryClassChrome.ts` *declares only two*; the other three fall through to `DEFAULT_CHROME` and a
   runtime `console.warn("Unknown advisory classId")` (`AdvisoryClassChrome.ts:43`) — **drift that ships
   today.** A local emit should name a *declared class* whose policy is the same kind of declaration the
   backend already requires, not a bag of free-form parameters.

Crucially this is *extension*, not replacement: the backend already lives this model (a declared policy
per advisory class); the design makes the *concept* single and applies it to the one path (FE-local) that
currently escapes it. (`EmissionPolicy` the Java record stays in `app-agent-api`; "single model" means one
*concept* realized on both sides — not the FE importing a backend record, which would cross module
boundaries.)

### §6 — The one genuinely-new structure: the routing derivation + suppression boundary

Stripped to its core, the new structure is small and singular (the 610 test: "what is the *one* datum the
problem requires?"):

- **A routing derivation `surface = f(trigger, horizon, locality, severity)`** — one authority that maps a
  push event's declared meaning-facets to its surface(s), replacing per-site surface hard-coding. The
  altitude rules from the problem statement become *invariants of `f`*, not prose:
  - locality = at-control ⟹ surface = in-control receipt, and Activity = no. (*A receipt structurally
    cannot become a window toast or a history row* — the problem statement's "short receipts should not
    become permanent history by default.")
  - horizon = transient ⟹ surface = toast, never the persisted inbox. (*A transient cannot silently
    persist.*)
  - horizon = durable / trigger ∈ {background, system, agent} ⟹ eligible for an Activity row — but the
    *inclusion rule* is **deferred to 612**, not re-decided here (§9).
- **A suppression boundary: "don't push what's already pulled."** Because a degradation cause (PULL) and a
  toast (PUSH) can share the one `readinessNotice` cause-vocabulary, a push event that names a cause the
  ambient verdict already shows is a *double-surface* and must be suppressed in favour of the banner. This
  is the structural home for the problem statement's "stated once at the window" and 602 §UX's "defer to
  the banner."

Everything else the problem names — the surfaces, the dispatch, the dedup/supersede mechanism, the facets
— already exists. `f` plus the suppression boundary is the whole of the new structure.

### §7 — What stays OUT (scope discipline; rejected structure)

Naming the structure the problem does *not* yet require is as important as naming what it does:

- **No fan-out emission engine.** A model where one emit auto-projects to N surfaces (toast *and* inbox
  *and* Activity simultaneously) is tempting but unjustified: the problem expresses **suppression
  constraints** ("don't double-surface", "a receipt isn't history"), not multi-projection *needs*. The one
  place 1→N already happens (an advisory showing in both toast and inbox) is already governed by
  `RenderHint` within a single family. Building a general fan-out engine would be structure for a case the
  problem does not include.
- **No folding PULL surfaces into the routing table.** The banner (verdict) and unavailable-reason
  (availability) are state projections with their own single authorities and gates. Routing them through
  `f` would re-centralize what 600/596 deliberately made ambient, and fight the `verdict-derivation`
  gate. They are *referenced* by the suppression boundary, never *re-rendered*.
- **No new render component / no one visual channel.** The problem statement's explicit non-goal. The
  surfaces are correctly distinct (559/574); `f` chooses among them, it does not merge them.
- **No premature encoding of rarely-used facets.** Locality and the Activity-eligibility flag are real,
  but altitude sub-bands and per-class Activity *inclusion* have exactly one consumer each today (the
  receipt rule; 612). Encode a facet when a second consumer needs it; until then it is a routing *rule*
  in this doc, not a field — matching how 610 shipped its floor before the summarizer and how 602 §D.0
  refused to fuse vocabularies.

### §8 — The honest ceiling (per doc 27 "The honest ceiling")

Where the design can and cannot reach Collapse, stated so it is not oversold:

- **The facet → surface derivation (`f`) and the closed message-class vocabulary** can reach
  **Generate/Gate**: one source for `f`, and the existing runtime "unknown classId" warning becomes a
  build-time forward+backward correspondence check (the 602 §D.0 pattern, already shipped twice). This is
  the firm part — a class with no declared policy, or a policy for a class nobody emits, fails the build.
- **The suppression boundary** has *latent* teeth: because PUSH toasts and the PULL banner can share the
  `readinessNotice` cause-codes, a toast that echoes a verdict-owned cause is machine-detectable — *if*
  cause-echoing toasts are required to name the cause-code. Without that coupling it stays **prose-tier**.
  Recommendation: prose first; promote to a gate only if a real double-surface ships.
- **Facet *assignment*** — did the author tag the event with the right trigger/horizon/locality — is
  **prose-tier / review-caught**: once the facets are right the surface follows, but choosing the wrong
  facet is a judgment a static gate cannot adjudicate.

This matches the kernel's stated ceiling: a product-decision layer's realistic top is *Generate/Gate for
the declared mapping* + *prose for the meaning judgments* — not full Collapse. By design, not an
unfinished edge.

### §9 — Boundaries to neighbours (no overlap)

- **559 Authority III** ("one message model") is *orthogonal and presupposed*: it guarantees a single
  toast/advisory *channel*; 613 sits one level up, governing the *routing* decision across surfaces.
- **600 / 595** own the degradation banner as a PULL projection of the verdict; **596** owns the
  unavailable-reason as a PULL projection of availability. 613 references both via the suppression
  boundary and never re-derives them (the `verdict-derivation` gate already forbids a second derivation).
- **612** owns Activity *inclusion semantics* (which events earn a history row, on the same trigger axis
  §4 reads). The seam: **613 routes the notification; 612 decides the history row.** 613 supplies the
  `trigger`/`horizon` facets; 612 owns the predicate over them.
- **614** is the IA umbrella that explicitly delegates notification routing to 613; this doc is that
  narrow delegate.
- **610 / 611** are sibling walkthrough docs (chat transcript controls / composer add-action); no
  messaging overlap — noted only because they share the 2026-06-18 walkthrough batch and the same
  "extend an existing authority, add one new datum" method this design follows.

### §10 — User-facing inspection (live dev stack, 2026-06-19) and what it changes

> Per the "don't judge from the tempdoc alone" instruction, the running app was inspected with screenshots
> (chat, AI Brain, System Health, the advisory inbox drawer, the Activity feed). The stack happened to be
> in a **degraded** state (index needs a reindex; local AI offline) — which is exactly the state that
> stresses a notification-routing model, so the inspection was unusually informative. Four findings, each
> with a direct design consequence. The headline: the abstract design above is right about the *mechanism*
> but **under-weighted one user-facing axis** — cross-surface *coherence of a single standing condition* —
> which the live UI shows is the dominant felt problem.

**Finding 1 — One condition, five surfaces, three wordings (the dominant problem the user actually feels).**
The single "index needs rebuild / AI degraded" condition was simultaneously surfaced as: (a) the Chat
**degradation banner** — "Reindex required. Semantic search is degraded…" + *Force Rebuild*; (b) the AI
Brain **banner** — "Embedding model fingerprint missing… Vector and hybrid search are disabled…"; (c) the
System Health **header pill** — "Reindex required"; (d) the **advisory inbox** record — "Schema Reindex
Required (worker.schema) · ReindexRequired" (the reason code leaks semi-raw); (e) the status-bar **pill** —
"Offline" / "Reconnecting…". Five loci, **three different wordings** for one root cause, and a raw reason
code in one of them. A user cannot tell whether these are *five problems or one*. This is the live form of
the problem statement's "different treatments for the same user meaning" — and it is **bigger than the
binary "don't double-surface" rule** §6 proposed.

*Consequence:* the design's most important user-facing job is **not** suppression (hiding duplicates) — it
is **coherence**: a standing condition needs **one canonical identity + one worded family**, and each
surface must render a *projection* of that identity at its own altitude, not an independently-authored
re-statement. Crucially, for a STANDING (PULL) condition, multi-locus reflection is *correct* — you *want*
the cause at the control you're blocked at, glanceable in the status bar, prominent in the banner, and
acknowledgeable in the inbox. The defect is not that they coexist; it is that they **disagree**. So §6's
"state once at the window" must be split: it applies to **PUSH transients** (don't toast the same thing
twice), while **PULL state** is governed by a *projection-consistency* rule (every locus words the one
condition identically, at its altitude). The vocabulary to project from **already exists** —
`readinessNotice.CAUSE_ROWS` — so this is an *extend-the-projection*, not a new store: the banner already
uses it; the brain banner, the System-Health pill, and the advisory record do not yet.

**Finding 2 — Navigation is double-routed: a transient toast AND a durable Activity row (the demonstrable
win).** Navigating to a surface raised a top-right toast ("Navigated to AI Brain · *Go back to Chat*") AND
appended a permanent Activity row. The **Activity feed was flooded**: its own filter read **`user (494)`
vs `system (6)`**, and the list was almost entirely `user · Navigate to X`. The system events that actually
matter (`system · Indexed · default`, `Resolve path hash · SUCCESS`) were buried under hundreds of
navigations. The surface's own subtitle even concedes it records "button clicks."

*Consequence:* this is the single most concrete user-visible change the design produces, and it validates
the §4/§9 facet seam exactly. A navigation is `trigger=direct-user, horizon=transient, locality=window` →
it routes to a toast and is **Activity-ineligible**. Under the routing derivation, the 494 navigation rows
disappear from the default Activity feed and the six real system/agent events become legible. This is the
concrete payoff of "613 routes the notification; **612** decides the history row" — 613's facets are the
predicate 612 applies. (It also shows the toast and the journal entry are *independent emits today*; under
the derivation they are one declaration with derived placements, so they cannot disagree about whether a
navigation is worth recording.)

**Finding 3 — The same C5 reason repeats verbatim, which is acceptable and reinforces the projection
model.** System Health's "What you can do right now" listed *Ask AI* / *Extract structured data* / *Run
agent tasks* each as "The local AI model is offline → *Open Health*" — the same reason three times. This is
**fine** (a per-affordance list legitimately repeats the shared cause at each control) and it is *already*
a projection of one vocabulary (availability → `readinessNotice`). It is evidence that the
project-one-identity-to-many-loci pattern is the right one and is already partly lived — the fix in Finding
1 is to extend that same discipline to the loci that currently fork (the brain banner, the inbox wording).

**Finding 4 — The placement/substrate mechanism is sound; only the decision layer is missing.** The nav
toast docked *below* the 2.5rem topbar and did **not** occlude the Copy URL / bookmark controls (the 602 R4
`OverlayHost` placement fix is live and working); the banner consolidated its three sub-causes into one
bulleted block with a single remedy (the "state once *within* a surface" half is already done well). So the
inspection **confirms the §1–§2 framing**: surfaces, placement, dedup/supersede, and within-surface
consolidation are all built and correct. Nothing visual needs adding. The gap is purely the cross-surface
*decision/coherence* layer — which is exactly what this design adds and nothing more.

**Net effect on the design.** The mechanism (§3–§9) stands. Two user-facing refinements are folded in:

1. **Split the §6 boundary into two rules by kind.** PUSH transients: *don't repeat* (binary suppression,
   as before). PULL standing conditions: *project one identity consistently to every locus* (coherence) —
   same canonical reason-code + one worded family from `readinessNotice`, rendered at each surface's
   altitude (prominent banner / compact pill / at-control reason / acknowledgeable record / ambient
   glance). This is the change the live UI most needs and it is an *extension* of the existing
   `readinessNotice` projection, not new structure.
2. **The navigation de-flood is the lead user-facing outcome.** It is the most legible, demonstrable
   consequence of the routing derivation and should be the worked example whenever this design is
   evaluated, because its before/after is visible on one screen (the Activity feed).

No new components, channels, or visual treatments are introduced — consistent with the problem statement's
non-goal and confirmed by Finding 4 that the substrate is visually complete.

### §11 — Confidence audit (pre-implementation, 2026-06-19, source-verified)

> A read-only pass over the five claims the implementation most depends on. Each ends with a verdict and
> `file:line` evidence. The headline: the **mechanism** (PUSH/PULL split, derive-surface-from-facets,
> decide-at-the-effect-layer) is **confirmed and strengthened**; two specific claims were **mischaracterized**
> and are corrected here. Net result lowers, not raises, the "smallest first move" optimism while raising
> confidence in the routing/de-flood half.

**U1 — "the 5 degradation loci share one authority" → REFUTED AS STATED (the biggest correction).**
They resolve to **three independent signal derivations of one upstream cause** (the worker's
embedding/schema compat state), forked into three wire fields and worded by three FE authorities:
- **Verdict family (Chat banner + Health pill + status pill)** — *one* shared authority: `computeVerdict`
  (`state/verdict.ts`) over the `retrieval` composite `reasonCodes` → `readinessNotice` `isReindexCause` /
  `REINDEX_CAUSE_CODES` (`state/readinessNotice.ts:247`); banner at `views/UnifiedChatView.ts:2091`, pill
  via `verdictHeadline` (`verdict.ts:201`). ✔ shares the vocabulary.
- **AI Brain banner** — **independent**: reads `status.embedding.compatState` raw and renders a
  *hardcoded* "Embedding model fingerprint missing" (`views/BrainSurface.ts:~1447`), bypassing
  verdict/`readinessNotice` entirely.
- **Advisory inbox record** — **separate lifecycle**: a backend `schema.reindex-required` Condition
  emitted by `WorkerSnapshotTap.java:135` from `compatibility().reindexRequired()` (a *different* boolean
  than `retrieval.reasonCodes`); its FE *title* is governed (`display/present.ts:74` →
  "Schema Reindex Required"), but the trailing `ReindexRequired` reason code is shown semi-raw.
- *Consequence:* the §10 "coherence" goal is **bigger and more cross-module than §10 implied** — it spans
  the wire (3 fields) and 3 FE authorities, not one FE projection. Two honest options the design must now
  name: **(a)** pick one canonical reason identity and re-word the Brain banner + advisory to project from
  `readinessNotice`; or **(b)** accept these as legitimate *altitude* views (Chat = user, Brain =
  technical/config, advisory = persistent record) and only enforce *non-contradiction*. (b) is lower-scope
  and likely correct; the upstream being one cause makes either reconcilable. This is a real scope finding.

**U2 — "navigation is two independent emits" → CONFIRMED.** Toast: `Shell.ts` `setActiveSurface` →
`showNavigationToast` → `emitEphemeralToast({classId:'core.navigation', supersede:true})` (`Shell.ts:1630`).
Activity row: a *separate* intentRouter subscriber (`Shell.ts:~1403`) → `recordNavigation` →
`recordEffect({kind:'navigate'}, CORE_PROVENANCE)` (`NavigationJournal.ts:159`) → effect journal → POST
`/api/action-ledger/events` → backend stream → Activity. Two paths branching from one intent dispatch, not
one emit. The de-flood is a genuine 613/612 routing change (confirms §10 Finding 2). High confidence.

**U3 — "facets are available at the FE-local emit site" → REFINED (decision locus moves).** The
*effect/journal* layer **does** carry the trigger facet — `applyEffect(effect, invokedBy, originator)` and
`recordEffect(…, {originator})` thread it (`substrates/actions/index.ts:424`), and navigation already
journals with `CORE_PROVENANCE`. But the *bare* toast path does **not**: `EphemeralToastSpec`
(`ephemeralToast.ts:22`) has no originator. *Consequence (strengthens the design):* the routing derivation
`f(trigger,…)` should live at the **effect substrate** (`applyEffect` — already the one unified dispatch
seam, §1), and the facet-less `emitEphemeralToast` bypass is precisely the path to bring under it. The
decision is "route through the effect layer," not "decide at the toast call."

**U4 — "the free-form classId is a provable leak that warns" → PARTIALLY REFUTED (correct the framing).**
Local-origin toasts **bypass** `advisoryClassChrome` entirely (`AdvisoryToastHost.ts:352–357`: `isLocal` ⇒
skip class chrome "to avoid a spurious unknown-classId warning"). So `core.navigation` / `core.ephemeral`
/ `core.verdict.settled` (all local emits) do **not** warn. The `console.warn("Unknown advisory classId")`
fires only for **non-local backend advisory classes** absent from `CHROME_MAP` (which holds only
`operation.completed` + `health.recoverable`). *Consequence:* the free-form `classId` is a **latent
governance/type gap, not a shipping symptom** — the "one provable leak / smallest first move" framing in
§5.2/§8 overstated it. The closed-vocabulary gate is still worthwhile, but it is *not* fixing a visible
bug; it is hardening a quiet drift surface. Honest correction.

**U5 — "EmissionPolicy propagates into FE behaviour" → CONFIRMED, with a caveat that softens §5.**
`EmissionPolicy{renderHint, dedupeWindow}` reaches the FE as wire data (`api/types/registry.ts:218`,
generated `schema-types/resource.ts:34`), so broadening it *does* propagate for **backend advisories**.
But `dedupeWindow` is **enforced backend-side** ("the backend already enforces the dedupe at the
change-registry boundary", `registry.ts:223`), and the **FE-local toast path never touches the
Resource/EmissionPolicy pipeline at all**. *Consequence:* §5's "one concept, two realizations" holds, but
the FE-local side is a **mirror** of the concept, not a literal extension of the Java record — the two do
not share enforcement. Frame it as "the same *policy-per-class* idea, realized once per side," not "extend
the one record."

**Net effect on confidence.** The design's spine is sound and now better-anchored: PUSH/PULL is real and
enforced (EmissionPolicy present⇔push), the de-flood is a true routing change with a known two-emit cause,
and the right decision locus is the existing effect substrate. The two soft spots are now explicit: the
**coherence half (U1) is medium-scope and cross-module** (needs the (a)/(b) decision), and the **classId
gate (U4) is hardening, not bug-fixing**. No surprises remain that would invalidate the approach; the
remaining risk is *scope sizing* of the coherence work, not *feasibility*.

**Critical confidence rating for the remaining implementation work: 6/10.**
- Routing / navigation de-flood half: **~8/10** — mechanism, emit sites, and the 612 seam are traced and
  confirmed; the change is well-bounded.
- Coherence half (one identity across the 3 reindex derivations): **~4–5/10** — feasible but cross-module
  (wire + 3 FE authorities) and gated on an unmade product decision (reword-to-one-identity vs
  enforce-non-contradiction). Until that decision is made, scope is uncertain.
- classId closed-vocabulary gate: **~7/10** — clear pattern (602 §D.0), but lower *value* than first
  framed (U4), so priority should drop.
The 6/10 is the honest blend: high confidence the design is *right*, medium confidence in the *size* of
the coherence slice until option (a)/(b) is chosen.

> **Status of this design:** problem-statement-level design only, no code. It reframes the gap
> (PUSH-routing vs PULL-state), identifies the existing seam to extend (`EmissionPolicy` + the
> already-declared facets), names the one new structure (`f` + the suppression boundary), and draws the
> scope lines (what stays out, what defers to 612). Implementation is a separate, accept-then-build step;
> the smallest correct first move is to close the FE-local declaration hole (§5.2) since that is the one
> *provable* leak on `main` today.

---

## §12 — AS-BUILT (2026-06-19, full-coherence slice implemented)

> The user selected the **full-coherence (one canonical reason identity)** slice from §11's menu — i.e.
> resolving the (a)/(b) fork in §11/U1 toward **(a) one identity**. Implemented in worktree
> `worktree-613-reindex-coherence` (FE-only, no backend change). The verdict family (Chat banner / Health
> pill / status pill) was already canonical (§11/U1); this brought the two forked loci back to it.

**Changed sites (two FE forks → projections of the one `readinessNotice` vocabulary):**
1. **`views/BrainSurface.ts` `renderCompatibilityCallouts`.** Replaced the hardcoded strings ("Embedding
   model fingerprint missing" / "…before embedding fingerprinting was enabled…") with a **canonical lead**:
   the reindex reason code(s) are read from the shared verdict already on the `subscribeAiState` snapshot
   (`this._unifiedAiState.verdict.reasons`), filtered by `isReindexCause`, and worded via
   `reasonFor(code).wording` — the *same* wording the Chat banner renders. The legacy/mismatch tag, the
   `fingerprintStored → fingerprintCurrent` hashes, and the schema reason are retained as **config-altitude
   technical detail beneath** the canonical lead; the existing `core.rebuild-index` op (already the
   canonical remedy) is kept. Consolidated the prior two-section markup into one callout (mirrors how the
   Chat banner consolidates causes). **No FE `compatState→code` map** was introduced — the backend already
   derives the codes onto `retrieval.reasonCodes`, so there is no parallel mapping to drift.
2. **`components/advisory/AdvisoryInboxDrawer.ts` `deriveFallbackSubtitle`.** Stopped emitting the raw
   `extras.reason` token (`ReindexRequired`); it is now passed through a small module-level
   `humanizeReasonToken` (camelCase + dotted/dashed/underscored → Title Case), so the advisory feed cannot
   show a machine code at the user altitude beneath the already-`present()`-humanized title.

**Reused seams (no new vocabulary):** `reasonFor` / `isReindexCause` (`state/readinessNotice.ts`, the 596
§17 + 600 Design A vocabulary); `AiState.verdict.reasons` (`state/aiStateStore.ts`); `present()` for the
title (`display/present.ts`). `humanizeReasonToken` is a generic string formatter (sibling of
`format.ts`/`formatTime`), not a second presentation authority.

**Tests (green):** `views/BrainSurface.reindex-coherence.test.ts` (new — asserts the callout lead equals
`reasonFor('index.embedding_legacy').wording` *against the authority, not a literal*, that the old fork
wording is gone, that the fingerprint detail is retained, and the no-reindex-code fallback);
`AdvisoryInboxDrawer.test.ts` (new case — the subtitle no longer contains the raw `ReindexRequired`,
shows humanized `Reindex Required`). Full suite: **335 files / 3244 tests pass**; `npm run typecheck`
clean.

**Explicitly deferred (recorded, not built):**
- The backend **Condition-reason↔`index.*` vocabulary merge** (so the advisory subtitle could show the
  *exact* canonical cause sentence rather than a humanized token) — a cross-module backend change, the §11
  "(b) is lower-scope" boundary; out of this FE slice.
- A **gate** forbidding a surface from reading `compatState`/condition-reason raw (the re-fork guard). The
  `check-readiness-reason-codes` gate doesn't cover a raw-`compatState` reader; extending coverage is
  reasonable hardening but was *not* selected (the user picked content coherence, not new enforcement).
  Follow-up candidate.
- The other §11 slices (**navigation de-flood**, **classId closed-vocabulary gate**) were not selected.

**Verification status:** unit + typecheck done above. The user-visible browser verification (Brain banner
leads with the canonical reindex wording matching the Chat banner; advisory inbox shows no raw
`ReindexRequired`) is the final batch — see the goal's closing condition.

### §12.P1 — FE-local message-class vocabulary + correspondence gate (2026-06-19)

> The "all remaining work" pass (§5.2/§8). Closes the free-form `emitEphemeralToast` classId into ONE
> declared, closed vocabulary, pushed as high up the kernel ladder as the substrate allows
> (Collapse via a union type + the 602 §D.0 correspondence gate as the backstop). Honest note (per §11/U4):
> this hardens a latent drift surface — it is not fixing a visible bug.

**New authority — `modules/ui-web/src/shell-v0/state/messageClasses.ts`.** Declares `LOCAL_MESSAGE_CLASSES`
(the 3 local classes — `core.ephemeral` [default], `core.navigation`, `core.verdict.settled`) with each
class's policy (`supersede` + `defaultSeverity`; the local channel is always `EPHEMERAL`). Exports the
`LocalMessageClass` union + `policyFor`.

**Collapse on the emit side.** `EphemeralToastSpec.classId` is now typed `LocalMessageClass` (was
`string`), so a free-form/typo'd local class won't compile. `emitEphemeralToast` resolves a call's
`severity`/`supersede` from the class policy when omitted — so the CLASS, not the call site, is the single
source for those routing-relevant axes. The two non-default emit sites (`chrome/Shell.ts`
`showNavigationToast`, `components/StatusDeck.ts` `announceSettledIfNeeded`) dropped their now-duplicated
inline `supersede`/`severity`. The WIRE `AdvisoryEvent.classId` stays `string` (it also carries backend
advisory classes).

**Gate (the 602 §D.0 backstop).** `scripts/ci/check-message-classes.mjs` + register
`governance/message-classes.v1.json` + `check-message-classes.test.mjs`. FORWARD: every emitted
`classId: '...'` is declared; BACKWARD: every declared class except the omitted `defaultClass` is emitted.
Documented in CLAUDE.md's pre-merge list beside the sibling reason-code gates.

**Reused, not invented:** the `RenderHint` (EPHEMERAL) axis + the 602 R4 `supersede` mechanism + the
readiness-gate's correspondence pattern. No parallel catalog.

**Tests:** updated `actions.test.ts` toast-detail assertion to the now-resolved detail shape (classId +
supersede stamped). **Full suite: 335 files / 3244 tests pass; typecheck clean; gate + gate-test green.**

### §12.P2 — Routing derivation: the Activity-inclusion decision, NOT the universal seam (2026-06-19)

> The honest outcome of P2. The maximal "force every receipt/toast/advisory through one `f`" (Alt-C)
> is the **§7-rejected over-build** — those surfaces already have single authorities, and
> horizon→{toast | inbox} is already derived from `RenderHint`; unifying them is the AHA over-DRY.
> The ONE routing decision with present, multi-surface value is **inclusion-in-Activity** (§6/§10):
> a routine direct-user action the user already witnessed (navigation) is a transient ack, not history.

**New — `shell-v0/state/messageRouting.ts`.** `isRoutineActivity(kind, originator, effectKind?)` —
true only for direct-user navigation (backend `kind='navigation'`, an ingested FE navigate effect, or a
raw FE `navigate` journal row; agent/system navigation stays — it explains a background effect).
`UnifiedActionEntry` gains an optional `isRoutine` facet, stamped in `projectBackend`/`projectEffect`
(`ActionLedgerClient.ts`) from the predicate. Reads the ledger's existing `originator` + `kind` facets —
no new inputs. Consumed immediately by P3 (not speculative).

### §12.P3 — Navigation de-flood (the §10 lead outcome; the 613×612 seam) (2026-06-19)

`components/ActionLedgerView.ts` excludes `isRoutine` entries from the DEFAULT Activity feed (the
curated projection) and offers a **`routine (N)` toggle** (reusing the `jf-filter-chip` atom) that reveals
the full ledger — 612's "curated projection, complete ledger still reachable". The §10 flood
(`user (494)` vs `system (6)`) is gone by default. Existing tests adapted (the "ONE log" test reveals the
full view; the originator-facet test uses a non-routine user op — it tests faceting, not navigation); new
test pins the de-flood + toggle. **tempdoc 612 noted** (first concrete inclusion rule; 612 owns extending
the predicate beyond navigation).

### §12.P4 — Suppression boundary: "don't push what's already pulled" (§6 R-3) (2026-06-19)

`messageRouting.ts` `causePushSuppressedByBanner(verdict)` — true when `readinessNotice(verdict)` mints a
notice (the banner is up), the ONE shared signal both the push toast and the pull banner read, so they
cannot disagree about whether the cause is visible. `SearchSurface`'s "AI is offline" toast (`handleAskAi`)
now suppresses itself when the banner already shows the degradation; it pushes only when nothing pulled the
cause. Per §8's honest ceiling this is the one real shipped double-surface; the general R-3 stays prose for
free-form messages that don't name a verdict cause. Tested in `messageRouting.test.ts`.

**Phases P1–P4 net:** full ui-web suite **336 files / 3250 tests pass; typecheck clean; the
`check-message-classes` gate + test green.** Five commits on `worktree-613-reindex-coherence`
(coherence + P1–P4). Live browser verification is the final batch (goal's closing condition).

### §12.P5 — the routing derivation `f` + §5.1, where it does real work (2026-06-19)

> A post-P4 critical re-read found P1–P4 delivered the *edges* but not §6's central structure: the
> derivation `surface = f(meaning)` that makes the surface a *function* of a message's meaning and the
> altitude rules *structural*. Investigation pinned the load-bearing case: **RECEIPTS mis-routed as window
> TOASTS** (the problem statement's "copy confirmation is a local receipt, not a global copy toast").
> Verified mis-routes: `EffectAuditLog` export "Exported N entries" and the `copy-to-clipboard` Effect's
> `successMessage` both fired window toasts; the only working receipt (the result-card "Copied!" flash) was
> *bespoke* (`SearchSurface.copyFlash`), with no shared authority.

**The derivation `f` — `state/messageRouting.ts` `routePushSurface(locality) → 'receipt' | 'toast'`.** The
surface is now a function of the declared `locality` facet: `at-control ⟹ receipt`, `window ⟹ toast`. The
altitude rule "a receipt is not a window toast" is an **invariant of `f`** (it cannot resolve `at-control`
to `toast`).

**§5.1 — the facet is declared on the class.** `LocalMessageClassPolicy` (`state/messageClasses.ts`) gains
`locality`; the three toast classes declare `'window'`. **Structural enforcement:** `emitEphemeralToast`
consults `f` and **refuses** an `at-control` (receipt) class — so a receipt *cannot* be emitted through the
window-toast channel (it errors + suppresses), not merely "shouldn't".

**The receipt surface gets ONE authority — `primitives/receiptController.ts` `ReceiptController`.** A
ReactiveController that owns the transient in-element "✓ <message>" flash (state + timer + auto-clear),
keyed per target. This lifts the bespoke `copyFlash` into the single authority every other surface has — it
is *completing surface #1* (the in-button flash; 602 R7), **not** a new surface kind (§7).

**Migrations (the real work):** `SearchSurface` result-card copy buttons refactored from `copyFlash` to
`ReceiptController` (behavior-preserving); `EffectAuditLog` export now flashes "✓ Exported N entries" in the
Export button and the `copy-to-clipboard` Effect dropped its `successMessage` window-toast (its lone
caller). Genuine window acks (navigation, "AI is offline", "Index ready", undo/import) stay toasts — `f`
confirms `locality:'window'`.

**Tests:** `receiptController.test.ts` (flash / auto-clear / reset / teardown); `routePushSurface` +
the at-control invariant in `messageRouting.test.ts`; `EffectAuditLog.test.ts` updated to assert the
export flashes a receipt and fires NO toast. **Full suite 337 files / 3255 tests pass; typecheck clean;
gate green.**

### §12.P6 — the last coherence fork: CapabilityPills (2026-06-19)

> A targeted hunt for *other* instances of the §10 coherence bug-class (a surface hardcoding a
> degradation/capability wording instead of projecting from the one authority) found exactly one beyond
> the already-fixed BrainSurface, and confirmed every other surface (SearchSurface, UnifiedChatView,
> HealthSurface, StatusDeck, CapabilityMap) already projects from `readinessNotice`/`verdict`/availability.

**The fork — `components/CapabilityPills.ts`** (the chrome-level degraded-state pills) read
`aiState.capabilities.{chat,rag,embedding}` booleans and **hardcoded** the wording, the remedy tooltip, AND
the navigation target ("Chat unavailable" / "RAG unavailable" / "Embedding blocked"). For one system state
it disagreed with its sibling `CapabilityMap` ("The local AI model is offline", from `reasonFor`); the
"Embedding blocked" pill forked the very reindex condition P5/the coherence slice unified.

**Fix (commit `ef8a9e254`) — project from the ONE authority.** CapabilityPills renders a compact pill per
affordance (`documents`/`extract`/`agent`, the same set CapabilityMap uses) from
`projectAvailability(affordance, aiState)`; the pill's **reason** (tooltip/accessible name) and **remedy**
(navigation via `navigate-with-context` + `dispatchRemedy`, like CapabilityMap) come from the canonical
`Availability` value — so a pill **cannot disagree** with the Health map or the control. The compact label
is the altitude (§10's "compact pill"); the wording is the projection. No hardcoded
strings/booleans/targets remain. Unit-test-verified the pill reason equals `projectAvailability(...)`
*against the authority* + the remedy navigation.

**Structural guard — `check-capability-availability.mjs`** + `governance/capability-availability-surfaces.v1.json`
(positive coverage, mirroring `verdict-derivation`/`declared-surfaces`): every registered
capability-availability display surface (`CapabilityMap`, `CapabilityPills`) MUST call
`projectAvailability`, so a registered surface can't silently re-fork. Honest limit per §8: it guards the
registered seam, not a new unregistered fork surface (a discovery-step row) nor free-text wording. Tested
(`CapabilityPills.test.ts`; `check-capability-availability.test.mjs`). Documented in CLAUDE.md. **Full
suite 338 files / 3259 tests pass; typecheck clean; both gates green + vocab regen-check green.**

**Known limitation found at live verification — CapabilityPills does not currently MOUNT (open question for
the user).** Browser verification surfaced that `CapabilityPills` only mounts on an AI-dependent RAIL
surface (`Shell.ts` — `_aiDependentIds.has(activeId)`), a set computed from rail surfaces consuming
`conversationShapes`, which is **empty today** (verified live: 7 rail surfaces, `consumingConvShapes: []`) —
the AI affordances became TABS in the unified window, whose own in-surface banner already states the cause.
So the projecting pills are coherent-by-construction (and the gate prevents a re-fork) but currently render
nowhere, and force-mounting them would re-introduce the over-notify problem this tempdoc warns against
(banner + pills for one condition). **Recommendation (NOT done unilaterally — it departs from the approved
plan):** the cleanest long-term move is to *delete* this legacy tempdoc-510 component as dead+redundant code
(a removal was implemented in `51a8eb49e` then **reverted** in `2c3aec95e` to honor the approved
plan/end-state, which requires the projecting pills + their test + both gate registrations). The
keep-vs-delete call is the user's; until then the pills stay, projecting correctly, gate-guarded.

**Live coherence verified (the user-visible part).** With the AI online + an optional re-ranker off, the
Health `CapabilityMap` rendered the canonical `projectAvailability` wording ("Ask AI about your documents:
Available — An optional ranking model is unavailable — results are complete, ranking may be simpler") and
the Search degradation banner showed the SAME canonical reranker wording ("Learned re-ranking (LambdaMART)
is not configured") — coherent across the reachable surfaces. Navigation still toasts; the P5
copy-receipt / Activity de-flood / reindex-coherence are untouched. The CapabilityPills tooltip itself
could NOT be browser-shown because the component does not mount (above) — its coherence is verified by the
unit test (pill reason == `projectAvailability`, against the authority) + the gate.

**DECISION (2026-06-20) — DELETE.** The open keep-or-delete question was resolved toward **delete**. Rationale
(conceptual, not feasibility): a chrome-level capability-pills strip is, by construction, a *second* locus
re-stating a standing condition the active window's own banner already states — i.e. the very over-notify /
"different treatments for one meaning" failure this tempdoc exists to prevent (§10). "Coherent but
unreachable" is not a reason to keep code: the projecting pills render nowhere in the unified-window IA
(`_aiDependentIds` is empty), so the component is a fossil of the retired multi-surface (510/507) layout.
The DURABLE keepers are the `projectAvailability` projection + the `check-capability-availability` gate —
those now protect every *reachable* surface (the Health `CapabilityMap`, the banners). Only the dead
`CapabilityPills` component is removed. (`_aiDependentIds` stays — the rail still consumes it.) Full
end-to-end deletion footprint + plan: tracked separately; AS-BUILT note to follow on execution.

**AS-BUILT (2026-06-20) — deleted.** Removed `components/CapabilityPills.ts` + its test; dropped the
`Shell.ts` import + the unreachable mount conditional (`_aiDependentIds.has(activeId) ? <jf-capability-pills>`
— `_aiDependentIds` retained, the rail still consumes it); removed the surface from
`governance/capability-availability-surfaces.v1.json` (now `CapabilityMap`-only) and the path from
`governance/atom-facets.v1.json` `ahaDistinct`; regenerated `component-vocabulary.generated.ts`
(126→125, `jf-capability-pills` gone); updated the prose in `check-atom-fork-ratchet.mjs` / `CLAUDE.md` /
`docs/explanation/27` (the gate-history "prior CapabilityPills" references stay — they explain why the gate
exists). Verified: typecheck clean; **340 files / 3312 tests** pass; `check-capability-availability`
(CapabilityMap-only), `atom-fork-ratchet`, `gen-component-vocabulary --check`, `check-ui-step-coverage` all
green. The durable keepers — `projectAvailability` + the gate — are intact and guard the reachable
`CapabilityMap`. (Live mount check deferred: dev stack was owned by another active agent; the change is
non-user-visible — the pills never rendered — and fully verified at the static/unit/gate tiers.)

**Issue class (recorded for reuse) — the "reachability fossil".** CapabilityPills was not a logic bug; it
was **dead code that passes every *static* tier**: it compiled, its unit tests were green, and the
`capability-availability` gate confirmed it projected correctly — yet it **never mounted** because its
mount predicate (`Shell.ts` `_aiDependentIds.has(activeId)`) is structurally empty in the unified-window
IA (no rail surface consumes `conversationShapes`). It is an **architectural-drift orphan**: built for the
retired 510/507 multi-surface layout, silently orphaned when the AI affordances became tabs in one window —
no test or gate asserts "this component actually appears on screen," so nothing failed. This is the
`static-green ≠ live-working` class (`.claude/rules/agent-lessons.md`): correctness checks verify a
component is *right*, not that it is *reachable*. **How it was caught:** only the **live** tier — an
attempted browser screenshot of the pills found them absent from the DOM; probing the running DOM showed
`_aiDependentIds` empty; tracing that predicate to its source proved the mount can never fire. A follow-up
hunt for *other* instances of this class found one (recorded in `docs/observations.md`): a strict scan
for totally-orphaned `jf-*` elements came back clean (132 defined, all referenced), but the *referenced-yet-
unreachable* sub-class — the real CapabilityPills shape — surfaced a **sibling fossil with the same root
cause**. The `_aiDependentIds` set (the rail-surface subset consuming `conversationShapes`) is structurally
empty because the AI window is a DEEPLINK, not a rail peer (577) — so beyond the now-deleted pills, its only
remaining consumers are two **always-false rail visuals** (the `ai-dimmed` dim + the `activity-dot` pulse in
`Shell.ts renderButton`). NOTE this **corrects** the AS-BUILT line above ("`_aiDependentIds` retained — the
rail still consumes it"): the rail *references* it but that consumption is itself dead, so the whole
`_aiDependentIds` subsystem is a fossil — a keep-vs-delete judgment identical to the pills' (deferred to the
user; logged, not built). The detection lesson generalizes: the strict orphan scan is cheap but misses this
class; only **root-cause tracing of an empty source** (or live per-surface/per-state inspection) finds the
referenced-but-unreachable shape.

**This was the LAST identified coherence fork.** With it, the §10 "dominant problem" (one condition, one
worded family across loci) is closed for every present surface AND made structural for the
capability-availability display class. The remaining critical-analysis partials — the routing model as
three small derivations rather than one god-`f`; `f` enumerating only the boundaries with present FE-local
cases; "state once" being correctly "state consistently" for PULL conditions; §5.1's facet living FE-side —
are deliberate scope cuts (AHA / §7 / §8), not gaps. P6's net result: the §10 dominant problem is closed
for every REACHABLE surface and structurally guarded for the capability-availability display class. The
visible display (`CapabilityMap`) is verified coherent; `CapabilityPills` projects correctly and is
gate-guarded, but is UNREACHABLE in the current IA (does not mount) — so its tooltip cannot be
browser-shown, and whether to keep it (dead-but-coherent) or delete it (legacy cleanup) is an open
decision left to the user (a removal was implemented then reverted to honor the approved plan).

**FINAL STATUS (P6).** Items 1–5 of the approved plan are complete and green (typecheck; 338 files / 3259
tests; both gates; vocab-check). The only unmet item is the *live render of the pills' tooltip*, which is
structurally impossible because the component does not mount in the shipped IA — not unfinished work. The
genuinely user-visible coherence (CapabilityMap + the degradation banners) is verified coherent live.

### Deliberately NOT built (recorded with reasons)

- **The universal fan-out engine (Alt-C, §7):** one emit auto-projecting to N surfaces simultaneously, and
  folding the PULL surfaces (banner/availability) through `f`. The problem expresses *suppression*
  constraints, not multi-projection needs; the PULL surfaces have their own single authorities. The
  genuine routing decisions (receipt-vs-toast `f`; Activity inclusion) ARE built (P5; P2/P3).
- **The standing-advisory / Activity arms of the FE-local `f`:** no FE-local emitter produces them today,
  so `f` enumerates only `receipt | toast` (§7 "encode a facet when a second consumer needs it").
- **An R-3 gate:** the suppression stays a runtime check + prose; promote to a gate only if a second real
  double-surface ships (§8).

---

## §13 — FORWARD-LOOKING RESEARCH (post-merge, 2026-06-19): what the model unlocks

> **What this part is.** A pure research/design pass requested after the implementation merged: given the
> shipped routing model, what could we *polish, simplify, extend, or newly build* on top of it? No code —
> documentation only. The method is the same as §1–§9: **measure the as-built against established practice,
> then apply this doc's own scope discipline (§7: encode a facet when a second consumer needs it; don't
> build structure for a case the product doesn't yet include).** The app has no users and is not in
> production, so every idea below is weighed for *design soundness*, not urgency. External practice was
> read from Carbon, Nielsen Norman Group, and a 2025 notifications-UX synthesis (Smashing); the as-built was
> re-mapped at `file:line`. Two concrete substrate facts were verified for this pass and are load-bearing
> below: (V1) the advisory **toast host has no `aria-live`/politeness region** — `aria-live` exists in 12
> shell files (StatusDeck, SystemNotice, Control…) but *not* `components/advisory/AdvisoryToastHost.ts`; and
> (V2) `TOAST_DURATION_MS = 5000` is **fixed regardless of severity**, and the `dedupeWindow` facet of
> `EmissionPolicy` is **dormant on the FE-local path** (it appears only in backend wire records; the FE
> toast channel has `supersede` single-occupancy but no count-coalescing).

### §13.0 — How the as-built already scores against external practice (the honest baseline)

The shipped model is, by external standards, *already strong* — which is why the list below is short and
mostly polish, not a redesign. Mapping the three sources onto the as-built:

- **NN/g "indicators vs validations vs notifications"** — our PUSH/PULL split (§2) *is* their indicator
  (PULL, ambient) vs notification (PUSH, event) distinction, made structural. ✔ Already lived.
- **Carbon "toast vs inline vs actionable vs notification panel"** — we have toast (`AdvisoryToastHost`),
  inline/banner (the degradation banner + `SystemNotice`), actionable (toast `primaryAction` + undo), and
  a **notification panel** (`AdvisoryInboxDrawer`) that *already* carries read/unread state, mark-all-read,
  unread count, class/transport/outcome grouping, cross-session persistence, and keyboard nav. ✔ The panel
  pattern others recommend *building* is already built.
- **Smashing/Notion/Slack anti-fatigue (bundle, digest, DnD, preset modes)** — the **one** place we lag.
  We de-flood Activity (P3) and supersede same-class toasts, but we do **not** *coalesce-with-count* on the
  toast channel, and we have no mute/quiet lever. This is the only external theme the as-built does not yet
  touch — and §13.3/§13.4 weigh whether it's worth touching for a single-user local app.

So the research conclusion up front: **the substrate is mature; the high-value follow-ups are a handful of
polish items (a11y + severity), one principled extension (coalescing via the dormant `dedupeWindow`), and
one product decision already on the table (CapabilityPills). The big "notification-center / DnD / focus
mode" features are real levers but are §7-style speculative structure for an app with no users — recorded,
deliberately not recommended now.**

### §13.1 — POLISH (small, high-confidence, reuse an existing pattern)

- **PL-1 · Toast screen-reader announcement with severity-derived politeness (the one real a11y gap).**
  Per V1 the toast host renders visually but does not announce through an `aria-live` region, so a
  screen-reader user can miss a toast entirely. The fix reuses a pattern *already in the codebase*:
  `presentVerdict` (`state/verdict.ts`) already computes `announce.politeness` (`'alert'` for error, else
  `'status'`). Mirror it on the toast host — error/warning severity → `aria-live="assertive"`
  (`role="alert"`), info/success → `aria-live="polite"` (`role="status"`). The `severity` facet the
  policy already declares (§4) *is* the input. Grounding: NN/g warns "a toast…would be a bad way to
  implement an error message" precisely because passive toasts get missed; a live region is the WCAG remedy.
  **Disposition: recommend — it's a genuine accessibility defect, not an enhancement, and the input + the
  mirror-pattern already exist.**
- **PL-2 · Severity-derived dwell time (errors shouldn't vanish on a 5s timer).** Per V2 every local toast
  auto-dismisses at a fixed 5s. NN/g and Carbon both say *action-required / error* notifications should
  require deliberate dismissal while *passive* ones auto-dismiss. The honest framing: §4 deliberately kept
  `severity` **orthogonal to horizon** — and that was right for the *routing* decision. But *dwell time*
  is a third, finer axis where severity legitimately bites. Minimal move: derive `durationMs` from the
  class `defaultSeverity` (info ≈ 4s, warning ≈ 8s, error → sticky/`REQUIRES_ACK`-like, click-to-dismiss).
  This is a property of the class policy, not a per-call decision — consistent with §5.1. **Disposition:
  recommend the error→sticky half (real correctness — an error that auto-hides is the NN/g anti-pattern);
  treat the info/warning dwell tuning as optional polish.**
- **PL-3 · `prefers-reduced-motion` on toast enter/exit.** The toast slide animation should honor the
  reduced-motion media query, consistent with the tempdoc 574 ambient-purity / motion-token discipline.
  Verify and, if absent, add. **Disposition: recommend (cheap, matches an existing house rule).**

### §13.2 — SIMPLIFY (reduce drift surface, no behavior change)

- **SI-1 · One tone-projection authority.** Three near-duplicate tone mappers exist
  (`AdvisoryToastHost` severity→tone, the class-tone→`NoticeTone` converter, and `verdictTone` in
  `state/verdict.ts`). Per this codebase's "one authority per domain" discipline (the same logic that drove
  the verdict/availability single-derivations), fold them into one `tone.ts` mapping `severity → NoticeTone`
  that all three consume. Removes a latent wording/colour-drift surface. **Disposition: recommend if/when a
  fourth tone consumer appears or a drift is observed; otherwise low-priority (AHA: only unify what shares a
  reason to change — these three plausibly do, so it's a borderline-yes).**
- **SI-2 · `describe(record)` helper in the toast host.** The render path re-checks
  `record.origin === 'local'` 6+ times. Extract one `describe(record)` accessor (the pattern
  `CapabilityPills` already uses). Pure tidiness. **Disposition: optional, do it opportunistically when
  next editing that file — not worth a standalone change.**

### §13.3 — EXTEND (activate a declared-but-dormant facet; this doc's own "extend an authority" method)

- **EX-1 · Toast count-coalescing via the dormant `dedupeWindow` (the strongest single follow-up).** Today
  the FE toast channel only *supersedes* (replace prior same-class) — so five "Indexed file X" events
  become one toast showing only the last, **losing the count**. Every external source names *bundle /
  digest / coalesce* as the primary defence against notification fatigue, and the codebase **already does
  exactly this one level over** — the Activity feed's bounded burst-collapse renders "Indexed N ·
  {collection}" (tempdoc 550). The extension is to apply that *same* bounded-projection to the toast
  channel: N same-class events within `dedupeWindow` → one toast carrying a **count** ("Indexed 5 files").
  This *activates the `EmissionPolicy.dedupeWindow` facet on the FE side* — the facet is already declared
  and wire-present (V2), it is simply unused locally. That is precisely §5's "grow the existing declaration"
  method, not new structure. **Disposition: recommend as the highest-value extension — it's principled
  (dormant declared facet), grounded (the dominant external theme), and mirror-able from an existing
  in-repo implementation (the Activity burst-collapse).**
- **EX-2 · The next Activity inclusion rules (the 613×612 seam, continued).** 613 shipped the *first*
  inclusion rule (navigation de-flood, P3) and supplied the `trigger`/`horizon` facets; **612 owns the
  predicate**. The natural next slice, per 612's own statement, is to **keep** causally/audit-relevant
  direct-user actions in the default feed (approvals, denials, destructive confirmations, durable grants,
  background-run launches, "user did X → system did Y" chains) while de-flooding the *other* routine acks
  (in-place toggles, local acknowledgements). This is 612 design work consuming 613's facets — no new 613
  structure. **Disposition: recommend as 612's next pass, not 613's; record the seam so 612 picks it up.**
- **EX-3 · Generalize the actionable-receipt-with-undo.** The toast host already wires an undo action with a
  Ctrl+Z hotkey for one path; Carbon's "actionable notifications" generalize this. A reversible-effect
  receipt (delete / move / import) could carry an undo for the `dedupeWindow`, reusing the existing
  `primaryAction` + `ReceiptController` seams. **Disposition: viable, low-novelty; build per concrete need
  (when a second reversible destructive op wants it), per §7.**

### §13.4 — NEW (bigger features; real levers, deliberately NOT recommended now — §7 / YAGNI)

These are recorded so the option space is explicit, with an honest "why not yet" for an app with no users:

- **NW-1 · Notification-center convergence (a 614 IA question, not a 613 one).** The advisory inbox drawer
  (alerts) and the Activity feed (history) are two windows onto overlapping push data; they *could* converge
  into one "Activity & Alerts" centre (tabs: Alerts = PERSISTED/REQUIRES_ACK advisories; History = the
  action ledger). **614 is the IA umbrella that delegates routing to 613**, so this convergence is *its*
  call. Recorded here only because 613 built the substrate it would sit on. **Disposition: defer to 614;
  not a 613 follow-up.**
- **NW-2 · Quiet/Focus mode + per-class mute + preset notification profiles.** The external gold standard
  (Slack per-channel matrix, iOS Focus, "calm/regular/power-user" presets). The shipped closed
  `LOCAL_MESSAGE_CLASSES` vocabulary makes a per-class mute set *trivially keyable*, so the substrate is
  ready. **But this is the §7 over-build for the current product:** a single-user local desktop app with no
  users and low notification volume, where the P3 de-flood already removed the one measured flood. Building a
  preferences/DnD surface now is "structure for a case the product does not include." **Disposition:
  explicitly NOT now — record the lever, pull it only if real toast volume or a multi-context usage pattern
  appears.** (Snooze/remind-me-later on advisories rides along with this; same disposition.)

### §13.5 — Already on the table (carried, not new)

- **CapabilityPills keep-or-delete** (§12.P6) remains the one open *product* decision: it projects correctly
  and is gate-guarded but does not mount in the current IA. The research above doesn't change the call —
  if anything it reinforces *delete* (a chrome-level pills strip is the NW-2-adjacent "more surfaces for one
  condition" the de-flood philosophy resists), but it stays the user's decision.

### §13.6 — Research summary (the short version)

The shipped model already matches or exceeds established notification practice on every axis **except
anti-fatigue coalescing**, and it already has the notification-panel features other design systems tell you
to build. The worthwhile follow-ups are therefore narrow: **(1) a genuine a11y fix** (toasts don't announce
to screen readers — PL-1), **(2) let error severity affect dwell/persistence** (a fixed 5s timer hides
errors — PL-2), and **(3) one principled extension** — count-coalescing on the toast channel by activating
the already-declared, currently-dormant `dedupeWindow` facet, mirroring the Activity feed's existing
burst-collapse (EX-1). Everything larger (notification centre, focus/DnD, preset profiles) is a real but
*speculative* lever this design's own §7 scope discipline says to leave un-pulled until the product has the
users to justify it. Sources: Carbon notification pattern; NN/g "Indicators, Validations, and
Notifications"; Smashing "Design Guidelines for Better Notifications UX" (2025).

---

## §14 — LONG-TERM DESIGN (2026-06-19): close the one residual presentation-treatment fork

> **What this part is.** The design theorization the §13 research asked for, scoped by the same discipline
> as §1–§9: extend an existing authority, add exactly the structure the *present* problem requires, reject
> structure for cases it doesn't yet include. The §13 items looked like a grab-bag (a11y, dwell, tone,
> coalescing, DnD…). They are not. The load-bearing ones share **one root** and resolve with **one move**;
> the rest are correctly out of scope. Every claim is source-verified on `main`.

### §14.1 — The real present problem (one root, not five items)

Phases P1 and P5 established that a FE-local toast's *routing-relevant* facets are **declared on the class**
(`LocalMessageClassPolicy` in `state/messageClasses.ts`) and *read* by the emit/route path: `supersede`,
`defaultSeverity`, and `locality` (the §5.1 receipt-vs-toast facet `f` reads). But the toast's **rendered
treatment** — *how loudly it announces, how long it persists, its tone* — still escapes that seam and is
decided at the **render site**: `TOAST_DURATION_MS = 5000` is a fixed literal regardless of severity
(`AdvisoryToastHost.ts:29`); there is **no `aria-live` region** on the toast host (verified §13/V1 — present
in 12 other shell files, absent here); and tone is computed by **three scattered mappers** (§13/SI-1). So
presentation-treatment is the **one residual *fork*** that the P1/P5 policy-seam work did not yet absorb —
the same class of fork 613 kept closing (free-form classId → P1; bespoke `copyFlash` → P5; hardcoded
CapabilityPills wording → P6).

**This is a present defect, not a hypothetical.** Error-severity local toasts are emitted *today*: an agent
background task **failed** (`AgentSessionController.ts:1879`, `severity: ok ? 'success' : 'error'`), an
operation or undo **failed** (`Shell.ts:1004/1028`, `severity:'error'`), a queued action **couldn't run**
(`Control.ts:530`). Each of these auto-vanishes on the 5-second timer and is never announced to a screen
reader — precisely NN/g's named anti-pattern ("a toast … would be a bad way to implement an error
message," because critical information is dismissed before the user notices). The §13 PL-1/PL-2 items are
two faces of this **one** escaped-fork root.

### §14.2 — The design: complete the class policy; derive treatment, don't hardcode it

The correct long-term move is the same one §5 named for the routing facets — **grow the existing
declaration (`LocalMessageClassPolicy`) into the authority for the toast's full rendered treatment, and make
`AdvisoryToastHost` *derive* politeness + dwell + tone from it instead of choosing them at the render
site.** It is the `treatment = f(declared facet)` shape §3/§6 already built for *which surface*, applied one
level deeper to *how the surface renders*. This **extends** the seam P1/P5 built; it invents no parallel
authority and adds no new render channel (the §7 / problem-statement non-goal).

**Scope discipline — one derivation, not three free knobs.** The wrong version of this design bolts three
new per-class columns (`politeness`, `dwellMs`, `tone`) onto every class — speculative structure, since the
right value of all three is a *function of severity* in all but rare cases. The right version is **one
derivation** `presentationFor(severity, policy)` with a principled default, which a class overrides only
when it has a reason to:

- **Announcement** = f(severity): error/warning → assertive (`role="alert"`, `aria-live="assertive"`);
  info/success → polite (`role="status"`, `aria-live="polite"`). The input (`severity`) is already a
  declared facet; the verdict path *already computes this exact mapping* (`presentVerdict.announce.politeness`
  in `state/verdict.ts`) — so this is a *reuse*, not a new rule.
- **Horizon/dwell** = f(severity): error → **sticky** (click-to-dismiss, not auto-timed); warning → longer;
  info/success → the current timed dwell. This is the one place severity legitimately bites *horizon* — §4
  correctly kept severity orthogonal to **routing**, but *dwell* is a finer axis below routing where the
  coupling is right (an error that auto-hides is the defect).
- **Tone** = f(severity): the single mapper SI-1 folds the three scattered ones into — the third treatment
  axis, same input, same home.

**The payoff is an invariant, not just a fix.** Once treatment is derived from the declared facet, *"an
error toast that silently auto-vanishes or never announces"* becomes **structurally impossible** — exactly
as P5's `at-control`-rejection made *"a receipt on the window-toast channel"* impossible, and as 596/608
made *"an unavailable control with no reachable reason / an in-flight command with no acknowledgement"*
unrepresentable. The defect class closes at the seam, not per call site.

### §14.3 — What the present problem does NOT require (rejected / deferred structure)

- **Count-coalescing / activating the dormant `dedupeWindow` (was §13/EX-1 "recommend"; now corrected to
  NOT-yet).** A scope check of every `emitEphemeralToast` call site found **no present emitter that produces
  N rapid same-class toasts** — navigation *supersedes* (single-occupancy), and nothing loops per-item. So
  coalescing-with-count is structure for a case the problem **does not yet include** (§7). The dormant
  `dedupeWindow` facet stays dormant; record it as the lever to pull **when** a burst emitter actually
  appears (the obvious future trigger: a per-file indexing toast). This is the §7 "encode a facet when a
  second consumer needs it" discipline applied honestly — the deeper look downgrades §13's enthusiasm.
- **DnD / quiet-mode / per-class mute / preset profiles (§13/NW-2).** No users; the P3 de-flood already
  removed the one *measured* flood. Recorded lever, deliberately not built.
- **Notification-center convergence (§13/NW-1).** Belongs to **614** (the IA umbrella), which explicitly
  delegates notification *routing* to 613 but owns the cross-surface mental-model / window-taxonomy. The
  inbox-drawer ↔ Activity-feed convergence is 614's call, not 613 structure.
- **The next Activity inclusion rules (§13/EX-2).** **612** owns the predicate over the facets 613 supplies;
  not 613 structure.
- **CapabilityPills keep-or-delete (§12.P6).** Still the one open *product* decision, unchanged.

**Net:** the whole of the new structure the present problem requires is *completing one existing
declaration* (`LocalMessageClassPolicy` gains a severity-derived treatment projection the toast host reads)
— closing the two present defects (a11y announcement, error auto-dismiss) and the tone-mapper drift in one
move. Everything larger is recorded and deliberately unbuilt.

## §15 — REACH JUDGMENT (the principle this design instances, and where else it lives)

### §15.1 — This is an instance of an existing seam — conform, don't parallelize

The design is **not novel structure**; it is the fourth sibling of one discipline the codebase already runs
in three adjacent places. All four say the same thing — *a control's or message's treatment and state are a
**projection of one declared authority**, never a render-site literal or a per-caller hand-track* (the
**projection-vs-fork** discipline, tempdoc 553):

| Authority | Axis it makes a projection | "Unrepresentable" invariant it buys |
|---|---|---|
| **596** availability | a control's *pre-activation* state | an unavailable control with no reachable reason |
| **608** acknowledgement | a control's *in-flight* state, *sourced from the dispatch seam* | a live button atop an in-flight command with no acknowledgement |
| **613 §3/§6** routing | a message's *surface* = f(facets) | the same meaning getting different surfaces at different sites |
| **§14 (this)** treatment | a message's *rendered treatment* (politeness/dwell/tone) = f(facets) | an error toast that silently auto-vanishes / never announces |

608 states the shared rule almost verbatim ("state an action produces is sourced from the dispatch seam,
not re-tracked by each caller … projection-vs-fork applied to *transient* state"). §14 is that rule applied
to *presentation* treatment. So the design **conforms** to the `messageClasses` policy seam + the 596/608
"property of the one primitive, derived from the declared facet" pattern — it must not grow a parallel
treatment mechanism.

### §15.2 — The recurring shape, named (recorded, not built)

Naming the general law plainly: **"treatment is a projection of declared meaning, not a render-site
literal."** The codebase already enforces this for **visual tokens** — the 574/576 presentation-token
closure gates forbid a bare colour / `z-index` / motion-duration / `font-size` literal and require derivation
from a token. The insight this design surfaces is that **the token-closure discipline has a behavioral
sibling**: *announcement politeness, auto-dismiss horizon, focus management, retry/backoff timing* are
**behaviors** that should likewise be derived from a declared facet (severity / role / liveness), not frozen
at a render-site literal. `TOAST_DURATION_MS = 5000` is the behavioral analogue of a bare `#hex` — a
meaning-dependent treatment hardcoded where a projection belongs.

**Candidate scope (where the law would apply, recorded for future judgment — not swept now):** any
`setTimeout(…, <literal>)` or fixed politeness/focus choice that encodes a *meaning-dependent* behavior —
the toast dwell + politeness this design closes, and potentially other fixed UI timers whose correct value
depends on severity/role/liveness rather than being a universal constant. (Single-authority constants like
`RECEIPT_FLASH_MS` are *not* violations — their value is meaning-independent and already lives in one
authority.)

**Deliberately NOT generalized now.** Per the separate-recognition-from-construction discipline: the present
problem requires only that the **toast** presentation-treatment fork be closed at the `messageClasses` seam.
A general *"no bare behavioral literal"* gate — the behavioral counterpart to the 574/576 visual-token
closure gates — is a real candidate, but building it now would be premature abstraction over a single
present instance. The principle and its candidate scope are recorded here; the generalized gate is built
only if/when a second meaning-dependent behavioral literal shows the class is recurring (mirroring how
574/576 themselves earned their gates only after the visual-literal class was proven).

---

## §16 — AS-BUILT (2026-06-20): the presentation-treatment derivation, shipped + live-verified

> Implemented the §14 design directly on `main` (FE-only). The slice is exactly what §14 scoped — no more.

**Corrected finding confirmed at the source (refines §13/V1 + §14).** The toast host was **not** missing an
`aria-live` region: `<jf-system-notice>` (the 559 seam, `components/SystemNotice.ts`) already owns
`live → role + aria-live` (`status`→polite, `alert`→assertive). The real gap was that `AdvisoryToastHost`
keyed `live` on `sourceRenderHint` (`REQUIRES_ACK→alert`, else `status`) and dwell on a fixed
`TOAST_DURATION_MS = 5000` — **neither on severity**. So local **error** toasts (agent-task-failed,
op/undo-failed, queued-couldn't-run) announced *politely* and auto-vanished in 5 s.

**The one derivation (the fix).** `state/messageClasses.ts` gains `presentationForSeverity(severity) →
{ tone, live, sticky }` (the §14 authority; it **absorbs** the former private `severityToTone`):
`error → {error, alert, sticky}`, `warning → {warning, alert, ¬sticky}`, `success → {success, status,
¬sticky}`, `info`/unset → `{neutral, status, ¬sticky}`. `AdvisoryToastHost` now DERIVES a local record's
`tone` + `live` (render) and `sticky` (in `pushToast`: a sticky local record gets `timeoutId = null`, the
same no-auto-dismiss mechanism `REQUIRES_ACK` already used) from this one authority instead of the
render-site literals. Stream records are unchanged. PL-3: a `prefers-reduced-motion` block disables the
toast slide animation (the existing 250 ms `dismiss` fallback covers the lost `animationend`).

**Honest SI-1 narrowing.** The sibling mappers `toneClassToNotice` (input = stream-chrome toneClass) and
`verdictTone` (input = verdict) are **different input domains** and were left in place — SI-1 unified only
the severity→presentation projection, narrower than §13/SI-1 first implied.

**Tests (green).** `state/messageClasses.test.ts` (new — the `presentationForSeverity` table + the
"only error is sticky" invariant); `AdvisoryToastHost.test.ts` (new cases — a local error renders
`jf-system-notice[role=alert][aria-live=assertive]` AND has a null auto-dismiss timer; warning announces
assertively but is not sticky; success/info stay polite + auto-dismiss). `npm run typecheck` clean; full
suite **3315 pass** (1 unrelated 5 s-timeout flake in `resourceRegistry.test.ts`, passes in isolation);
`check-message-classes` gate green (class set unchanged).

**Live browser verification (REQUIRED tier — done).** Real Lit app, injected an `error` + a `success`
toast through the actual `jf-advisory-ephemeral` document-event path. Confirmed via DOM: the error toast's
`<jf-system-notice>` carried `role="alert"` / `aria-live="assertive"` / `tone="error"` and **persisted past
the 5 s window** (no auto-dismiss timer) while the **success toast auto-dismissed** and stayed
`role="status"` / `aria-live="polite"`. The degradation banner + chrome stayed coherent (no regression).
*(Field note: the dev-stack Vite on :5173 watches `.dev-data/telemetry/metrics-worker.ndjson` — a
backend-written runtime file — causing a `page reload` storm that intermittently prevents the shell from
mounting in a scripted browser; a clean restart + loading between reloads gave a stable window. Logged to
`observations.md`; pre-existing, unrelated to this change.)*

**Net.** "An error toast that silently auto-vanishes or under-announces" is now unrepresentable for the
local channel — derived from the declared severity at the `messageClasses` seam, the fourth sibling of the
596/608/613-routing projection discipline (§15.1). The §15.2 behavioral-literal closure gate remains
recorded-but-unbuilt (one instance ≠ build).
