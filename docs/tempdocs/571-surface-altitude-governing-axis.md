---
title: "571 — Surface Composition: a host surface presents its sibling views as DECLARED members (the one-interaction-window pattern generalized; composition is declared, not derived). [Supersedes the original framing — 'Surface Altitude as a Governing Axis' — whose derived-homing half was built, reverted, and replaced; see §10 retrospective + §11 corrected theory. Driving case: System Health hosts Logs + Boot-phases.]"
type: tempdocs
status: SUPERSEDED-AND-REFRAMED (2026-06-10). **The current go-forward theory is §11 (Surface Composition — declared, not derived); read it first.** §1–§9 (the altitude axis) shipped on `gate-fixes-main-green`; a follow-on consolidation slice was built then reverted (§10 retrospective); §11 re-theorizes the design around the *actual* root gap — surface composition (host/member) is not a first-class declarable relationship — and replaces the axis's derived-homing half. Where §11 disagrees with §1–§9, **§11 wins** (dated-history rule). The remainder of this status line is the ORIGINAL 2026-06-09 framing, preserved as history. — CORRECT-DESIGN THEORY (2026-06-09). The correct long-term structure for *where a surface lives, what it groups with, and whether it may be plugin-authored* — not an implementation slice, not a homing patch. The motivating question (where do the **Activity** and **Logs** surfaces go; may either be a plugin) turned out to be a *proof-by-example* of a missing axis: the surface substrate already derives a surface's **audience** from the authority it projects, but derives nothing else — so **homing** and **core-vs-plugin eligibility** are under-determined and decided ad-hoc. The single claim: the correct structure is **one generalization of a derivation the substrate already performs** — lift the audience-floor into a full **altitude** axis, and make homing, projector-sharing, and plugin-eligibility *projections* of altitude, so mis-homing, render-genre mis-unification, and a trust surface in the sandbox become **unrepresentable**. This **completes 569's reserved-channel principle from the component tier up to the surface tier**, and **extends** 449's Surface manifest + `SurfaceAreaValidator`'s existing derivation + 561's register/gate pattern — it is NOT a new substrate. Genre: design-theory per 557/559/567 — feasibility, phasing, and sequencing deliberately disregarded; major rewrites/refactors in scope; end-states stated at the bar the category sets. Current-behavior claims verified against `main` on 2026-06-09 (citations inline); re-verify before relying. **De-risked 2026-06-09 (§8 confidence ledger):** the central move (altitude as a governing axis; homing/eligibility as projections; the reserved-channel completion) is CONFIRMED, but the §4a *mechanism* is CORRECTED — `consumes` is NOT a complete manifest of what a surface projects (Activity's trust ledger is fetched out-of-band, invisible to `consumes`), so altitude derives from the surface's **primary** projected authority under a **consumes-completeness precondition**, and the trust foreclosure reduces to the **reserved-component tier** (forbid mounting `jf-action-ledger`), not an altitude-from-consumes derivation. This implies a **consumes-completeness migration** (Activity must declare its trust authority; cf. Health/slice 481), not a pure forward-gate. Where §8 corrects §1–§7, **§8 wins** (dated-history rule).
created: 2026-06-09
category: frontend / ux / surfaces / information-architecture / plugins / trust / diagnostics / single-authority / projection / design-theory
related:
  - tempdoc 449 (slice — the Surface Manifest substrate: the typed `Surface` declaration + `SurfaceAreaValidator` audience-floor. THE extension point: the audience-floor IS the seed of this whole design)
  - tempdoc 561 (the one-interaction-window unification + the `interaction-surface` register/gate — one-authority-per-*concept*. **§11's *cautionary* precedent (corrected §11.7 D1):** 561 *declares* chat modes in `conversationShapes` but `UnifiedChatView` hard-codes their render and never iterates the declaration — declaration-without-iteration, which drifts. §11's declared-member *iteration* is therefore NEW work, not a 561 extension; 561 is the evidence FOR the primitive, not a reusable copy of it)
  - tempdoc 569 (the user-authored frontend — the merged projection spine + the reserved channel / `RESERVED_COMPONENTS`. This doc is its surface-tier completion: altitude is the host-owned floor 569's presentation composes over)
  - tempdoc 560 (the extension substrate — what a plugin IS: untrusted, value-only, SES-sandboxed, capability-attenuated; §4a backend attenuation deferred. Supplies the trust boundary this doc projects to the surface tier)
  - tempdoc 550 (the canonical action-lifecycle record + the Outcome read-view `jf-action-ledger` Activity projects — operations + navigations + trust-gate firings + FE effects)
  - tempdoc 472 (slice — Layout Manifest `zones`: hand-assigned `SurfaceRef[]` per zone — a per-zone *visibility allowlist*, NOT a host/member composition authority; §11 names what zones cannot express)
  - tempdoc 565 (the composition tier — `composeGridStyles` + `composition-surfaces.v1.json` register/gate: the one-authority-per-composition template §11's one-home gate mirrors)
  - tempdoc 575 (the SAME projection spine one tier DOWN — the observability-authority tier: the observed-happening register that single-sources + classifies the streams (logs, conditions, action-lifecycle, jobs, metrics, …) this doc's surfaces project. 571 governs *where a stream is shown*; 575 governs *what it is as data*)
  - tempdoc 471 (slice — surface override channel: substitutive `userConfig.surfaceOverride`) ; tempdoc 470 (the truth/presentation boundary for user UI authorship)
  - tempdoc 559 (the projection kernel / SurfaceLayout / projector set — where the shared filtered-event-stream projector lives) ; tempdoc 553 / 550 (the execution-surface / operation-surface register+gate pattern this doc's gate mirrors)
  - slice 448 (DiagnosticChannel substrate — what Logs projects) ; slice 444b (operation-history) ; slices 456 / 497 (the Health surface — the Diagnostics-cluster host)
verified-against-main:
  - modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/Surface.java (the typed manifest: SurfaceRef × Presentation × Audience × Placement × SurfaceConsumes × Provenance × Optional<SurfaceStateSchema> × RiskTier; AGENT⟺HEADLESS_AGENT_TOOL structural invariant in the compact constructor)
  - .../registry/Placement.java (COMMAND, RAIL, STAGE, HUD, STATUS, DRAWER, MODAL, DEEPLINK, HEADLESS_AGENT_TOOL) ; .../registry/Audience.java (USER < OPERATOR < DEVELOPER; AGENT orthogonal) ; .../registry/ConsumerPermission.java (CORE / TRUSTED_PLUGIN / OPERATOR_OVERRIDE) ; .../registry/Provenance.java + TrustTier.java (CORE / TRUSTED_PLUGIN / UNTRUSTED_PLUGIN)
  - modules/app-services/src/main/java/io/justsearch/app/services/registry/validator/SurfaceAreaValidator.java (effectiveAudience = max(declared, floorFromProvenance, floorFromConsumedChannels) — the existing derive-from-projected-authority move this doc generalizes)
  - modules/app-observability/src/main/java/io/justsearch/app/observability/surface/CoreSurfaceCatalog.java (the 14 core surfaces; Activity consumes operation-history, Logs consumes core.head-log @ OPERATOR_OVERRIDE)
  - modules/ui-web/src/shell-v0/chrome/Shell.ts (rail = listSurfaces().filter(placement===RAIL) then layout-zone + visibility + order — a FLAT list; no altitude/cluster axis)
  - modules/ui-web/src/shell-v0/themes/authorableComponents.ts (RESERVED_COMPONENTS: jf-authorization-host, jf-provenance-badge, jf-overlay-host, jf-shell — the component-tier foreclosure this doc lifts to the surface tier)
  - modules/ui-web/src/shell-v0/views/{ActivitySurface,LogSurface,HealthSurface}.ts
  - scripts/governance/gates/interaction-surface/ + governance/interaction-surfaces.v1.json (the register/gate template)
---

# 571 — Surface Composition (declared, not derived)

> **Reading order (2026-06-10):** the **current near-term decision is §11.8 — do the consolidation
> *manually*** (System Health embeds Logs as a tab; Logs set off-rail by hand; no new substrate). §11.1–
> §11.7 document the *composition system* (a declared host/member relationship) as the **correct
> long-term structure, deferred** to a trigger-on-second-host — read them for the theory, but §11.8 is
> what's actually planned. §1–§10 are dated history: §1–§9 are the original *altitude-axis* design
> (shipped on `gfmg`); §10 is the retrospective on the consolidation slice that was built then reverted.
> The original H1 was "Surface Altitude as a Governing Axis."

## §1 Thesis — the missing derived axis

The codebase runs one projection spine at every altitude:

> **one authority → a typed declaration → a governed projection → the breaking/subordinate form made
> *unrepresentable* → a register/gate whose coverage itself projects from the authority's catalog.**

(This doc applies the spine at the **surface tier**. The same spine one tier *down* — at the
**observability-authority tier**, governing the *streams* these surfaces project (logs, conditions,
the action lifecycle, jobs, metrics, …) so each is single-sourced and correctly typed — is **tempdoc
575**, the data-tier sibling of this doc.)

A **surface** (a RAIL window — Activity, Logs, Health, Search, …) is already a *typed declaration* on
this spine: the `Surface` manifest (slice 449) is `SurfaceRef × Placement × Audience ×
Provenance(TrustTier) × SurfaceConsumes × RiskTier`, and `SurfaceAreaValidator` already runs a
*projection* over it — it **derives** a surface's effective audience from what the surface consumes:

```
effectiveAudience = max( declared, floorFromProvenance(tier), floorFromConsumedChannels(consumes) )
```

So the substrate **already computes a surface property from the authority the surface projects**: a
surface that consumes the `core.head-log` DiagnosticChannel (declared `OPERATOR_OVERRIDE`) is *forced*
to OPERATOR audience. **That single existing derivation is the seed of this entire design.**

But it is the *only* derived property. Everything else about *where a surface belongs* is **asserted,
not derived**:

- **Placement** is a hand-declared enum value per surface; the rail is a **flat** list
  (`listSurfaces().filter(placement === 'RAIL')`, `Shell.ts`).
- **Grouping** does not exist as an authority — there is no "Diagnostics cluster," only a flat catalog
  ordered by user config (472).
- **Core-vs-plugin eligibility** is governed only by the audience-floor (an untrusted plugin lifts to
  OPERATOR) — there is **no rule tying a surface's *kind* to whether it may be plugin-authored at all.**

The correct structure adds the missing axis — **altitude** — and, crucially, derives it the same way
audience is already derived: **from the authority the surface projects.** Then **homing,
projector-sharing, and plugin-eligibility are projections of altitude**, not independent decisions.
This is not a new substrate; it is **one generalization of `floorFromConsumedChannels`** — and it
**completes 569's reserved channel from the component tier up to the surface tier** (§5).

The keystone, in one line:

> **A surface's altitude is a function of the authority it carries; its home and its
> trust-eligibility are functions of its altitude. Decide none of the three by hand — and
> mis-homing, render-genre mis-unification, and a trust surface in the plugin sandbox all become
> unrepresentable.**

## §2 The defect class — three faces of one under-determination

Because altitude is unmodelled, three defects are *latent* (none has shipped — Activity/Logs are the
live proof-by-example that would trigger them):

**(a) Render-genre mis-unification.** Two surfaces that share a *render genre* (a virtualized,
filtered, pause/resume, newest-first chronological feed — exactly Activity and Logs) invite a merge,
because nothing makes "one surface carries exactly one authority" structural. The `interaction-surface`
gate (561) forecloses the *inverse* defect — two surfaces for one concept — but there is **no invariant
forbidding two authorities behind one surface.** Merging Activity (the 550 lifecycle record, incl.
trust-gate firings) with Logs (the 448 diagnostic channel) would re-create the 548/565 two-authorities
defect under the banner of tidiness.

**(b) Mis-homing.** A user-facing **trust read-view** (Activity) and an operator-facing **diagnostic
tail** (Logs) get identical flat-RAIL treatment, because there is no altitude taxonomy that says
"diagnostics group here, product surfaces there, trust surfaces stay first-class." Homing is hand-placed,
so the *wrong* home (Logs as a top-level peer of Search; Activity buried inside an operator Health panel)
is just as representable as the right one.

**(c) Trust-surface-in-sandbox.** Nothing structurally bars a **trust-role** surface from plugin
provenance. `RESERVED_COMPONENTS` (569) protects trust **components** (the live `jf-authorization-host`
dialog) from being mounted by a skin — but a trust **surface** like the Activity audit ledger has *no
equivalent protection*. It is safe today only incidentally, because no one has tried to plugin-ify it.
A plugin-authored Activity surface is a **forged-trust** primitive: untrusted code rendering the *audit
of what was authorized* (it could hide that a destructive op ran, recolor a denied outcome as approved).

**Why post-hoc gates are insufficient.** Per 557 §2 / 569, a defect class that the *author can express*
will eventually be expressed; the durable fix is **unrepresentability**, not a linter that flags it after
the fact. All three faces above are "expressible because altitude isn't modelled." Modelling altitude as
a *derived* property — and homing/eligibility as projections of it — removes the expressibility, not just
the symptom.

## §3 What already exists — the substrate is one generalization away

The investigation (verified against `main`, 2026-06-09) found the surface layer is **already a
fully-typed, build-time-enforced authority** — which is *why* this is an extension, not a rebuild:

| Existing mechanism | What it governs | Relationship to this design |
|---|---|---|
| `Surface` manifest (449) | the typed declaration: placement, audience, provenance, consumes, risk | the declaration this design adds a *derived* read over |
| `SurfaceAreaValidator` audience-floor | derives `effectiveAudience` from provenance + consumed channels | **the seed** — altitude is this same derivation, generalized |
| `Placement` / `Audience` enums | *where* (zone) / *who* (trust-ordered) | retained unchanged; altitude is a new axis *above* them |
| `Provenance(TrustTier)` | CORE / TRUSTED_PLUGIN / UNTRUSTED_PLUGIN | the field trust-eligibility (§4d) constrains |
| Layout Manifest `zones` (472) | hand-assigned `SurfaceRef[]` per kernel-fixed zone | generalized to altitude-*derived* homing (§4b) |
| `surfaceOverride` (471) / plugin merge (560) | user substitution / plugin contribution by id | bounded by altitude (a plugin can't contribute a trust surface, §4d) |
| `interaction-surface` gate (561) | one visible interaction surface (one-authority-per-*concept*) | the register/gate **pattern** this design's gate mirrors (§4e) |
| `RESERVED_COMPONENTS` (569 Move 4) | trust **components** unmountable by a skin | the *component-tier* instance of the *surface-tier* foreclosure (§5) |

**The precise extension point:** 449's `Surface` + `SurfaceAreaValidator`'s derivation, governed by a
561-style register/gate. Nothing here is discarded. The gap is singular and named: **no axis derives a
surface's *kind* (altitude), and therefore homing and plugin-eligibility are ungoverned.**

## §4 The correct structure

> **Read §8 first.** The 2026-06-09 de-risk pass CORRECTED the mechanism below: §4a's "derive from
> the `consumes` graph" is incomplete (`consumes` under-declares projected authorities), §4c's "one
> authority per surface" is sharpened to a **primary-authority** rule (affordance Operations don't
> count), and §4d's trust foreclosure reduces to the **reserved-component tier**. The end-states in
> §4 stand; the *derivation mechanism* is as refined in §8 (R1–R3, X4).

### §4a — Altitude is *derived* from the projected authority (not a hand-set field)

A surface's **altitude** is a function of the authority named in its `consumes` graph — exactly as
`effectiveAudience` already is. Illustratively (the *taxonomy is not a frozen enum* — the **derivation
rule** is the invariant):

- consumes a **DiagnosticChannel** → **diagnostic** altitude (Logs, head-log, boot phases).
- projects the **authorization / trust read-view** (the 550 lifecycle record's trust-gate firings —
  what `jf-action-ledger` carries) → **trust** altitude (Activity).
- projects **product** authorities (Resources / Operations / ConversationShapes) → **product** altitude
  (Search, Browse, Workflow, Library, Brain, Settings, the one chat window).
- `HEADLESS_AGENT_TOOL` placement → **tool** altitude (already structurally distinct via the
  AGENT⟺HEADLESS_AGENT_TOOL invariant in `Surface.java`).

This is the literal generalization of `floorFromConsumedChannels`: that function already says "consuming
an operator channel makes you operator-audience"; altitude says "consuming a diagnostic channel makes you
a *diagnostic surface*; projecting the trust record makes you a *trust surface*." Resisting the weaker
"add a hand-set `cluster` field" alternative is deliberate: a hand-set field only relocates the ad-hoc
decision up one level. **Derived-from-what-you-project is what makes mis-homing unrepresentable rather
than merely catalogued.**

### §4b — Homing is a *projection* of altitude (not hand-placed)

The cluster/zone a surface lands in is **computed from its altitude**, not declared per surface:
diagnostic → the **Diagnostics cluster** (with Health + boot phases); trust → first-class, adjacent to
the interaction surface; product → the product rail; tool → headless (no chrome zone). This generalizes
472's Layout Manifest from *hand-assigned* `zones: Record<ZoneId, SurfaceRef[]>` to *altitude-derived*
groups: the layout composes **altitude bands**, and surfaces fall into bands by what they project.

569's user-authored presentation still applies **within** an altitude — a user may reorder, hide, skin,
and re-compose surfaces inside a band — but **may not move a surface across altitudes** (presentation is
authored over the altitude floor, never under it). Crossing altitudes is not a skin choice; it would
change what the surface *is*.

### §4c — One authority per surface (the merge foreclosure)

**A surface projects exactly one altitude-authority.** This is the dual of 561's one-surface-per-concept:
561 forbade two surfaces for one authority; this forbids two authorities behind one surface. It makes
"merge Activity + Logs into one window" **unrepresentable** — they are two authorities at two altitudes;
a single surface cannot carry both.

The shared **render genre** that *tempts* the merge is real, and is handled **orthogonally**: extract one
**filtered-event-stream projector** (virtualization + severity/category filter model + pause/resume +
connection-state + newest-first) into the 559 projector set, and have both surfaces *declare through it*.
This is the structural encoding of **"unify the engine, not the surface"** — the duplicated render
machinery collapses to one projector while the two data authorities stay distinct.

> Consistency check the invariant must pass: Activity consumes *both* the action-ledger and
> `operation-history`. That is **one** authority — the 550 lifecycle record — rendered as **two
> read-views**, which is admissible (a projection may have multiple views of *one* authority).
> Activity + Logs is **two** authorities — forbidden. The invariant is "one authority," not "one
> consumed ref," and the audience-floor's existing multi-ref handling is the precedent for how a
> surface relates to several refs of a single authority.

### §4d — Core-vs-plugin eligibility is a *projection* of trust-role

**Trust altitude ⟹ CORE provenance**, by construction — a trust surface is *unrepresentable* as a
plugin (any `TRUSTED_PLUGIN` / `UNTRUSTED_PLUGIN` provenance on a trust-altitude surface is a declaration
error, not a runtime check). This is the **surface-tier completion of `RESERVED_COMPONENTS`**: 569
forecloses *mounting* a trust component; this forecloses *authoring* a trust surface. The forged-trust
defect (§2c) ceases to be expressible.

The positive complement — the principled answer to *which surfaces CAN be plugins* — falls out for free:
**plugin surfaces are admissible at product and tool altitude** (a community saved-searches dashboard, a
disk-usage visualizer — these project product authorities and carry no trust role). **Diagnostic-altitude
plugins remain gated on 560 §4a** (backend capability-attenuation, deliberately deferred): streaming the
raw head-log to attenuated plugin code needs the attenuation substrate that does not yet exist, so
diagnostic altitude is plugin-ineligible *until* §4a ships — a derived, not ad-hoc, "no for now."

This reframes the maintainer's original question. "Should Activity/Logs be plugins?" is not a per-surface
judgment; it is **read off their altitude**: trust → never; diagnostic → not until 560 §4a. And the
*customizability* motive that often hides inside "make it a plugin" is satisfied by **569** (user-authored
presentation over the altitude floor), with no trust-inverting move.

### §4e — The register/gate (coverage projects from the catalog)

A `surface-altitude.v1.json` register + a discipline gate, mirroring `execution-surface` (553),
`interaction-surface` (561), and `operation-surface` (550), enforces:

1. **Altitude-derivation closure** — every surface's altitude is consistent with what it projects
   (no surface declares "product" while consuming a DiagnosticChannel).
2. **Homing closure** — every surface's cluster/zone is the projection of its altitude (no diagnostic
   surface homed outside the Diagnostics band; no trust surface buried in it).
3. **Trust-eligibility** — trust altitude ⟹ CORE provenance (the §4d foreclosure, gate-checked at the
   declaration, including plugin-merge and override paths).
4. **One-authority-per-surface** — the §4c merge foreclosure.

Per the kernel discipline, the gate's **coverage projects from the surface catalog** — it is not a
hand-maintained allowlist; a new surface is in scope the moment it is declared, so the gate cannot rot
behind the catalog (the 530/553 "coverage projects from the authority" property).

## §5 How this composes with 569 — the same foreclosure at two granularities

569 made *appearance, layout, composition, and behavior* user-authored over closed host vocabularies,
with a **reserved channel** keeping trust UI out of the authorable surface. This design is its
**surface-tier completion**:

- **Altitude is the host-owned structural floor.** 569's user-authored presentation composes *over* it:
  users skin/arrange/compose **within** an altitude; the altitude itself (what a surface *is*, where it
  homes, whether it may be plugin-authored) is functional-core, team-owned, unauthorable.
- **The reserved channel and the altitude authority are the same rule at two granularities.**
  `RESERVED_COMPONENTS` forecloses authoring a trust **component**; trust-altitude-⟹-CORE forecloses
  authoring a trust **surface**. Both are instances of one principle:

  > **trust-role ⟹ host-owned, unauthorable** — at whatever granularity (component or surface) the
  > trust role attaches.

  569 solved it for components; this solves it for surfaces. The defect class — *a trust-bearing
  artifact authored or homed by an untrusted/wrong-altitude party* — is closed at both tiers, by the
  same move.

## §6 The Activity & Logs resolution, now *derived* (not asserted)

The original homing decisions become **consequences of the structure**, not judgments:

- **Activity** projects the 550 lifecycle record incl. trust-gate firings → **trust altitude** →
  first-class, homed adjacent to the interaction surface, **CORE-only by construction** (plugin-
  ineligible, not by policy but by §4d). It is the retrospective companion to the live approval moment;
  the approval *dialog* is reserved at the component tier (569), the approval *audit* is reserved at the
  surface tier (here).
- **Logs** consumes the `core.head-log` DiagnosticChannel → **diagnostic altitude** → homed in the
  **Diagnostics cluster** with Health + boot phases (it already imports `BootPhasesPanel`); plugin-
  ineligible until 560 §4a.
- **Both** share the §4c **filtered-event-stream projector** — the render-genre commonality is absorbed
  by one engine while the two authorities stay distinct. The merge that the genre tempted is
  unrepresentable.

No decision here is a maintainer call; each is the projection of an altitude that is itself derived from
what the surface carries. That is the point — the *next* Activity-vs-Logs question answers itself.

## §7 Out of scope + open verification items

**Out of scope (design-theory genre):** feasibility, phasing, sequencing, the exact mount shape of the
Diagnostics cluster (Health sub-tab vs co-located surface group vs nested band), Activity's optional
filter/search/export widening (slice 486 §22.1, a Tier-B `AgentRunStore` concern), and the precise
schema of `surface-altitude.v1.json`. These are slice concerns; this doc states the *structure*.

**Open verification items (before an implementing slice trusts this):**

1. **Audience-floor → altitude generalization is clean.** Confirm the existing
   `floorFromConsumedChannels` / `floorFromProvenance` derivation generalizes to a full altitude axis
   without re-classifying any of the 14 current surfaces incorrectly (run the derivation over
   `CoreSurfaceCatalog` and check every surface lands at its intended altitude).
2. **Trust altitude is distinguishable from OPERATOR audience in every case.** The two axes are
   orthogonal (Activity is **USER**-audience **trust**-altitude; Logs is **OPERATOR**-audience
   **diagnostic**-altitude). Confirm no surface collapses the distinction — i.e. "trust" is genuinely a
   new axis, not a rename of an audience tier. (If any trust surface is also operator-only, the
   orthogonality claim needs a stated exception.)
3. **Activity actually renders trust content.** Confirm `jf-action-ledger` surfaces trust-gate
   *outcomes* (allow / deny / attenuated) to the user, not merely operation rows — that is what makes
   Activity trust-altitude rather than product-altitude (`ActivitySurface.ts:87-89` asserts gate firings
   are in the stream; verify the renderer projects them).
4. **Reserved-channel scope, precisely.** §4d/§5 rest on `RESERVED_COMPONENTS` covering the live
   `jf-authorization-host` while the Activity *audit* is unprotected at the surface tier. Confirm the
   denylist contents and whether any audit/provenance read-view is (or should be) added — if the audit
   is meant to be reserved at the component tier too, §4d becomes belt-and-suspenders rather than the
   sole foreclosure.
5. **Log sensitivity.** Confirm whether `core.head-log` is producer-redacted or carries raw sensitive
   content — this sets how hard the "diagnostic altitude is plugin-ineligible until 560 §4a" claim bites.
6. **Full RAIL inventory.** The 14 declared surfaces should each be classified by the derived altitude
   and the Diagnostics-cluster consolidation sized against the whole rail, not just Logs — there may be
   a broader altitude-banding of the rail this doc only opens.

## §8 Confidence ledger — de-risk pass (2026-06-09)

A measured de-risk pass (the 559 §12 / 565 §10 discipline) ran the §7 probes against `main` source
before any implementation slice trusts the design. **Where this section corrects §1–§7, this section
wins** (dated-history rule). The §1–§6 framing was built largely on second-hand subagent investigation;
this pass read the cited code directly. Net: **the central move is CONFIRMED; the §4a derivation
*mechanism* is CORRECTED** (see R1–R3, X4). The live tier (P6) was **not run** — the static tier
settled every load-bearing claim at higher fidelity than a runtime probe (Activity's trust content was
confirmed backend→wire→render directly), so a live `/api/registry/surfaces` call would only re-confirm
the declared catalog; available on request for runtime/visual confirmation.

### Per-claim verdicts

| # | Claim (§) | Verdict | Evidence (`main`, 2026-06-09) |
|---|---|---|---|
| C1 | The substrate already derives a surface property from what it projects — the "one generalization away" keystone (§1, §3) | **CONFIRMED** | `SurfaceAreaValidator.java:33-53,128-185` — `effectiveAudience = max(declared, floorFromProvenance, floorFromConsumedChannels)`, verbatim; the channel-floor derives audience from `consumes.diagnosticChannels` |
| C2 | Activity is genuinely **trust-altitude** — it surfaces authorization verdicts, not just operation rows (§2c, §4d, §6) | **CONFIRMED (all 3 tiers)** | backend emits gate rows: `ActionLedgerController.java:59-61,125-126` (`projectGate`, "GATED/DENIED/APPROVED"); wire: `ActionLedgerClient.ts:33,41-43` (`kind:'gate'`, `disposition`); render: `ActionLedgerClient.ts:133-135` → `ActionLedgerView.ts` |
| C3 | `RESERVED_COMPONENTS` is a **validation error (rung-2 unrepresentable)**, not a soft gate — the §5 two-granularity symmetry | **CONFIRMED** | `authorableComponents.ts:8-10,19-31` — denylist {`jf-authorization-host`, `jf-provenance-badge`, `jf-overlay-host`, `jf-shell`}; "mounting a reserved component is a validation error (rung-2 unrepresentable), not merely a gate catch." `jf-action-ledger` is **not** in it (so the trust *audit* is unprotected at the component tier — the §4d gap is real) |
| C4 | The register/gate **pattern** §4e mirrors exists and already classifies surfaces by a derived predicate (§4e) | **CONFIRMED + feasibility de-risked** | `interaction-surface/enforcer.mjs:81-128` — authority (`CORE_USER_INTERACTION_SHAPES`) declared once, projected over `CoreSurfaceCatalog`, "No second copy of the shape list lives in the register"; it already computes "visible interaction surfaces" from (audience × placement × consumes) and enforces cardinality+coverage — structurally identical to the proposed `surface-altitude` gate |
| C5 | The gap is real — no altitude/cluster axis; the rail is a flat placement list; `Surface` has no group field (§1, §2b, §3) | **CONFIRMED** | the `Surface` shape (`SurfaceCatalogClient.ts:448-465`) is `{id, presentation, audience, placement, consumes, mountTag, provenance, splitPairing?}` — no altitude/cluster field; `splitPairing` is a 2-surface split-view, not a band. Rail = `placement===RAIL` filter |
| C6 | A single choke point exists where a plugin-surface foreclosure could attach (§4d) | **CONFIRMED (with X4)** | `mergePluginSurfaceContributions` (`SurfaceCatalogClient.ts:414-468`) is the sole plugin-surface entry; `provenance`+`effectiveAudience` pre-computed. But see X4 — the trust foreclosure can't be a `consumes` derivation |
| X1 | §4a: "altitude derives from the `consumes` graph" | **CORRECTED → R1** | Activity's declared `consumes` is **only** `core.operation-history` (`CoreSurfaceCatalog.java:228-230`); the trust ledger is fetched out-of-band by `jf-action-ledger` via `GET /api/action-ledger` — invisible to `consumes`. Naive altitude-from-consumes misclassifies Activity as *product* |
| X2 | §4c: "one authority per surface" (literal) | **CORRECTED → R2** | Health consumes 3 diagnostic Resources + 6 product Operations + an OPERATOR-audience Resource inside a USER surface (`CoreSurfaceCatalog.java:483-499`, the slice-481/482 tension documented at `:245-264`). It does not mono-partition by raw consumed ref |
| X3 | Altitude is derivable for *every* surface (§4a) | **CORRECTED → R3** | `core.search-surface` declares **empty** `consumes` (slice 463) — no projected authority to derive from |
| X4 | §4d: trust-altitude ⟹ CORE as a *consumes* derivation | **CORRECTED** | the trust authority (action-ledger) is **not a registry primitive** (not Operation/Resource/Prompt/DiagnosticChannel/ConversationShape) — a plugin cannot declare it consumes it. So the foreclosure is properly a **component-tier** rule (forbid plugins mounting `jf-action-ledger`, i.e. add it to `RESERVED_COMPONENTS`), which **strengthens** §5's "same mechanism at both tiers" while correcting §4d's framing |

### Forced refinements (these supersede the §4 mechanism)

- **R1 — Consumes-completeness precondition.** The derivation presupposes every authority a surface
  *projects* is a *declared consumable*. Today it is not: Activity projects the trust ledger but
  declares only `operation-history`; Health historically under-declared its Resources (slice 481
  §E.5.1, closed by adding the refs). The structurally-correct fix is to **promote the trust
  read-view (the action-ledger) to a declarable authority** so altitude-from-projection can see it —
  or, failing that, delegate its foreclosure to the component tier (R/X4). Either way the design
  **implies a consumes-completeness migration**, not a pure forward-gate.
- **R2 — Primary-authority rule (not per-ref mono-altitude).** A surface's altitude is its **primary**
  projected authority — its reason to exist — and consumed Operations are **affordances**
  (remediation/actions) that do **not** constitute a second altitude. Health = a diagnostic surface
  that *offers* product Operations as remediation; Activity = a trust read-view that may *offer*
  operations. The §4c merge-foreclosure **survives** under primary-authority: Activity's primary =
  trust ledger, Logs's primary = diagnostic channel → different primaries → unmergeable.
- **R3 — Empty-consumes default.** A surface declaring no projected authority (Search) defaults to
  **product** altitude (the user-interaction default), rather than being underivable.

### Go / no-go on the central move

**GO — with the derivation reframed.** The governing insight (altitude as the missing axis; homing,
projector-sharing, and plugin-eligibility as projections of it; the surface-tier completion of the
reserved channel) is well-supported: the keystone derivation is real (C1), the gate pattern is proven
and already does this kind of classification (C4), the reserved-channel mechanism is unrepresentability
not a soft gate (C3), and the flagship trust surface genuinely carries trust content (C2). But the
naive "derive altitude purely from the `consumes` set" is **not viable as written** — `consumes` is
incomplete and the trust authority is not yet a declarable primitive. The corrected mechanism is
**primary-authority (R2) + a consumes-completeness precondition (R1) + an empty-consumes default (R3)**,
with the **trust foreclosure living at the component tier (X4)** until/unless the action-ledger becomes
a first-class declarable authority. Discovering this now — rather than mid-implementation — is the
return on the de-risk pass.

### Residuals (genuinely open, demoted from "untested assumption")

- §7.2 (trust ⊥ audience): **holds as observed** (Activity = USER-audience + trust; Logs =
  OPERATOR-audience + diagnostic) — the two axes are orthogonal in the catalog; no surface collapses
  them. Re-confirm if a future trust surface is operator-gated.
- §7.5 (log redaction) and §7.6 (full rail altitude-banding): **unchanged** — still slice concerns.
- The **live tier** (`/api/registry/surfaces` runtime catalog; a `ui-shot` of Activity): not run;
  confirmatory only; available on request.

## §9 As-built (implemented 2026-06-09, branch `worktree-571-surface-altitude`)

The corrected design (§8) was implemented in four phases. Three implementation findings refined the
plan (dated-history rule: §9 is the as-built truth where it differs):

- **CI-1 — Surface is not a generated wire contract.** `Surface` is hand-serialized
  (`RegistryController.handleSurfaces` passes the record through Jackson) and its FE type is
  hand-written; it is NOT in `contract-surfaces.v1.json`. So adding `altitude` was lightweight (the
  `RiskTier` precedent): a new `Altitude` enum + a record field (default `null → PRODUCT`) +
  back-compat constructors + the generated FE enum union (`RegistryEnumsTsGenerationTest`). No
  schema-codegen migration, no conformance test.
- **CI-2 — two surface-declaration sites drift.** The Java `CoreSurfaceCatalog` (the
  `/api/registry/surfaces` authority) and the FE `CorePlugin.ts` disagree on audience for
  Health/Activity (Java USER vs FE OPERATOR) and the FE has surfaces absent from the Java catalog
  (memory, command-palette). The Java catalog is the altitude authority; §7.2's "Activity = USER +
  TRUST" holds against it. The drift + FE-only surfaces are pre-existing and out of altitude scope —
  logged to `observations.md`, not fixed here.
- **CI-3 — the shared engine was already shared.** 571 §4c over-stated the duplication: both surfaces
  already subscribe through the one `EnvelopeStream` SSE primitive, and `collapseBursts` is an
  already-shared pure fn. The ONLY genuine duplication was the newest-first ordering (`newestFirst`).
  Per AHA + this doc's own thesis, Phase 4 extracted exactly that and deliberately did NOT force a
  heavier shared substrate coupling the two distinct authorities (448 channel vs 550 ledger).

**Mechanism, as-built (refines §4 per §8 R1–R3, X4):** altitude is a **declared, gate-witnessed**
property. PRODUCT is the benign default; DIAGNOSTIC/TRUST are declared explicitly on the 3 non-default
core surfaces (Logs/Health = DIAGNOSTIC, Activity = TRUST). The `surface-altitude` gate enforces the two
mechanizable foreclosures against the catalog authority — `TRUST ⟹ CORE provenance` and
`consumes-a-channel ⟹ DIAGNOSTIC` (coverage projects from the catalog). The trust foreclosure is
completed at the component tier (X4): `jf-action-ledger` is reserved in `RESERVED_COMPONENTS`, and the FE
plugin-merge clamps a non-CORE `TRUST` claim to `PRODUCT`. Homing is a projection of declared altitude
(the rail bands DIAGNOSTIC surfaces together; the TRUST Activity surface stays first-class in the product
band). Verified: full Java build + FE typecheck/unit + the `surface-altitude` gate (pass-on-branch,
negative-fixture-fail) + a live whole-app browser pass.

### §9.A Implementation corrections 2 (critical self-review, 2026-06-09)

A critical pass found the first cut **partially violated 571's own principles**; corrected:

- **Single authority (was two).** Altitude had been declared in BOTH the Java catalog AND `CorePlugin.ts`,
  and `mergePluginSurfaceContributions` clobbered the wire's altitude (it never read the existing entry) —
  so the FE's effective altitude came from CorePlugin while the gate checked Java (silent-drift risk).
  Fix: the merge now **preserves the existing wire entry's altitude** when a contribution omits it
  (`c.altitude ?? existing?.altitude ?? 'PRODUCT'`; the wire populates before the merge — `main.jsx`), and
  the CorePlugin altitude declarations were **removed**. The Java catalog (gate-checked, wire-served) is now
  the single altitude authority. The `TRUST ⟹ first-party` clamp moved to a typed
  `isFirstPartyProvenance` predicate (no magic string).
- **Derived homing (was hand-placed).** Activity-near-chat had been a hand reorder of the Java
  `DEFINITIONS` — hand-placement, contradicting the "homing is a projection" thesis. Fix: the Java reorder
  was **reverted** (Activity back to natural order); the **Rail render derives** TRUST adjacency — within
  the product band, order = `[interaction surface] → [TRUST] → [rest]`, so Activity homes next to chat by
  construction for any TRUST surface.
- **Completed gate witnessing.** Added three rules: `TRUST ⟹ mounts a registered trust read-view`
  (`trustedMountTags` — TRUST is declared+witnessed, not a bare claim); `consumes a registered diagnostic
  Resource ⟹ DIAGNOSTIC` (`diagnosticResources` — closes the Health-class gap); `HEADLESS_AGENT_TOOL ⟹
  TOOL` (forward-compat). Allowlists live in `governance/surface-altitude.v1.json`.
- Added `surface-altitude` to the CLAUDE.md pre-merge checklist.

### §9.B The DERIVED implementation (2026-06-09, supersedes §9/§9.A)

§9/§9.A shipped altitude as a **declared, gate-witnessed** field — the design's intent (§4a: altitude is
*derived* from the projected authority) was approximated, not reached. A follow-up pass closed the gap so
altitude is a genuine **projection** of consumed authority; wrong altitudes become *underivable*, not
merely gate-flagged. This is the as-built truth where it differs from §9/§9.A (dated-history rule). Six
phases, each its own commit on `worktree-571-surface-altitude`:

- **Phase A — Resource altitude-`role`.** New `Role {PRODUCT, DIAGNOSTIC, TRUST}` on the `Resource` record
  (default PRODUCT via a `withRole` wither — no ctor combinatorial explosion). The diagnostic Resources
  (`health-events`, `failed-indexing-jobs`, `condition-recovery-index`) declare `DIAGNOSTIC`. `role` flows
  to the registry wire via `UIResourceView` (regenerated `resource.v1.json` + FE `resource.ts`). This is
  the **seed** §4a needed: an authority self-classifies, and altitude derives from it.
- **Phase B — the `core.action-ledger` Resource (`Role.TRUST`).** The unified 550 Outcome-face ledger,
  promoted from an out-of-band read-view (§8 R1's gap) to a declarable Resource mirroring
  `core.operation-history`, backed by the existing `/api/action-ledger/stream`. Activity now **consumes**
  it (its real authority — the `jf-action-ledger` view) instead of the pre-unified `operation-history`
  stand-in, so its TRUST altitude can derive from consumption. This closes the §8 R1 "trust authority
  projected out-of-band, so altitude can't be derived" exception that forced the §9 declared compromise.
- **Phase C — derive altitude on the wire.** Shared `SurfaceAltitude.derive(surface, resourceRoles)`
  collects the non-PRODUCT signal set (channel ⟹ DIAGNOSTIC; DIAGNOSTIC/TRUST-role Resource ⟹ that
  altitude; HEADLESS_AGENT_TOOL ⟹ TOOL) and resolves 0 ⟹ PRODUCT, 1 ⟹ that, ≥2 distinct ⟹ a **conflict**
  (the §4c merge-foreclosure, now a *derivation* outcome, not a gate allowlist). `RegistryController`
  stamps the derived altitude onto the surfaces wire (`withAltitude`); the explicit
  `Altitude.DIAGNOSTIC/TRUST` declarations were **removed** from Logs/Health/Activity. `SurfaceAreaValidator`
  shares the same `derive` (resource cross-ref + conflict finding) so the wire and the validated value
  cannot drift. `CoreSurfaceAltitudeDerivationTest` proves `derive` reproduces all 14 core surfaces'
  intended altitudes with zero conflicts (the `audit-without-test` guard).
- **Phase D — the gate, rewritten to derive.** The gate no longer reads a declared `Altitude.X` field —
  it globs the `*ResourceCatalog.java` `.withRole(Role.X)` declarations for the role index and DERIVES each
  surface's altitude (mirroring the Java `derive`). The hand-maintained `trustedMountTags` +
  `diagnosticResources` allowlists are **gone** (coverage projects from the catalogs). The
  channel/diagnostic-resource/invalid/tool rules are now tautological under derivation and were dropped;
  the invariants derivation *can* still violate are the only rules: `altitude-conflict` (the
  merge-foreclosure) and `trust-requires-core` (a surface that DERIVES TRUST must be CORE — the
  surface-tier completion of 569's reserved channel; the component-tier foreclosure on the trust read-view
  stays in the FE `RESERVED_COMPONENTS`). [A third, `diagnostic-requires-core`, was added in the §9.C
  de-risk pass.] Fixtures rewritten for the derived model (a `FixtureResourceCatalog` declares the roles;
  the negative fails for every rule). Self-test green; real-repo pass.
- **Phase E — the cross-altitude move-ban (§4b).** `Shell.refreshSurfaces` clamps a user `surfaceOrder` to
  within-altitude bands (`clampReorderToAltitudeBands`, a stable sort by band), so a reorder can only
  permute within an altitude — never move a surface across one. Also closed the FE fallout of exposing
  `role` on the wire (the generated `ResourceWire` now requires `role`): production `FieldRoles` + eight
  test fixtures gained `role`.
- **Phase F — verification.** Full Java `build` (compile + PMD + Spotless + all unit tests);
  `npm run typecheck` + 2623 FE unit tests; the `surface-altitude` gate (self-test positive→pass /
  negative→fail-for-both-rules + real-repo pass). **Live whole-app browser pass** against this worktree's
  Head (`runHeadlessEval` on :9876 + Vite proxying to it), all confirmed against the running stack:
  - the **derived** `altitude` on `/api/registry/surfaces` — Logs/Health = `DIAGNOSTIC`, Activity =
    `TRUST`, all else `PRODUCT` — computed from consumed authority with **no** altitude declared in the
    catalog;
  - `core.action-ledger` `role:TRUST` (+ the three diagnostic Resources `role:DIAGNOSTIC`) on
    `/api/registry/resources`, endpoint `/api/action-ledger/stream`;
  - the rendered rail: a Diagnostics band grouping `[health, logs]` and Activity (`data-altitude="TRUST"`)
    homed immediately after the interaction surface — both DERIVED from the wire altitude;
  - the Activity surface mounts the reserved `jf-action-ledger` trust read-view.
  The full hard-stop → agent-`DENIED` disposition-row REPLAY was deferred at Phase F (the eval Head's
  inference would not start) but **completed in the §9.C de-risk pass** via a model-free path — see §9.C.

**Net:** the spine is complete — one authority (the Resource's `role` + the channel/placement axes) → a
typed derivation (`SurfaceAltitude.derive`, shared by wire + validator + gate) → the breaking forms
(two-authority merge, plugin trust surface) are *underivable* / gate-failed, with coverage projecting from
the catalogs. §9/§9.A's declared+allowlisted mechanism is fully superseded.

### §9.C De-risk pass (2026-06-09) — closes the §4d diagnostic foreclosure

A confidence pass over the §9.B work (3 read-only investigations) confirmed three risks safe (the Activity
view's data source is hardcoded and consumes-decoupled, so the operation-history→action-ledger declaration
change is pure metadata; the plugin-altitude merge preserves the wire value and only clamps TRUST; the
audience-floor and altitude derivations are orthogonal-by-design, no drift) and surfaced **one real gap**:
§4d's "diagnostic surfaces are plugin-ineligible until 560 §4a" was stated but **unenforced** — a plugin
could declare `altitude: 'DIAGNOSTIC'` and home in the Diagnostics band. Closed symmetrically with the
TRUST foreclosure:

- **FE clamp** (`SurfaceCatalogClient.ts`): a non-first-party `DIAGNOSTIC` claim now clamps to `PRODUCT`,
  exactly as TRUST does — the runtime foreclosure.
- **Gate rule** `surface-altitude/diagnostic-requires-core` (mirrors `trust-requires-core`): a surface that
  DERIVES DIAGNOSTIC with non-CORE provenance fails the build. The negative fixture now fails for all three
  rules; the positive + real repo pass (Logs/Health derive DIAGNOSTIC and ARE core).

This forecloses a plugin claiming diagnostic **altitude** (homing/status); the deeper channel-**data**
attenuation (a plugin consuming the head-log bytes) remains the 560 §4a concern, as the tempdoc intends.

**Live trust-disposition replay (the one Phase-F deferral, now closed).** The §4d trust story's runtime
proof — a real gate firing rendered as a disposition row in the Activity ledger — was confirmed live
against this worktree's Head. The eval Head's inference would not autostart even with `-Pllm=true`
(an environment issue, not a regression — models + `llama-server.exe` are present), so the replay used a
**model-free** path: the hard-stop DENY fires in `enforceTrustLattice` *before* inference, so engaging the
global hard-stop (`POST /api/agent/hard-stop {engaged:true}`) and dispatching an UNTRUSTED MCP mutation
(`tools/call justsearch_ingest` → `core.ingest-files`) returned *"Trust gate denied operation
core.ingest-files from sourceTier=UNTRUSTED"* and recorded a `{kind:'gate', originator:'agent',
disposition:'DENIED'}` row on `/api/action-ledger`. In the browser the Activity surface's
`jf-action-ledger` **rendered** it verbatim: **"agent DENIED Ingest Files (DENY)"**. The full
gate-firing → ledger → render path is verified end-to-end; only the agent-LLM emission of the tool call was
substituted by a direct MCP dispatch (the gate fires identically for any UNTRUSTED transport).

### §9.D Design-resolved items — 571 is complete

Two items flagged in the post-implementation critical analysis as "partial/deferred" were investigated
(two independent read-only passes) to decide whether they were genuine remaining work. Both are **resolved
by design decision, not deferred debt** — implementing either *literally* would degrade the design:

- **§4b homing — the rail-render derivation IS the correct single home; do NOT lift bands into the
  LayoutManifest.** `LayoutManifest.ts` is by contract a **JSON-only, no-code declaration safe for
  untrusted plugins**; its `zones.rail.surfaces` is a *visibility allowlist*, not a band-composition
  authority. The sole band authority is `Rail.render()` (`Shell.ts`), which derives bands from each
  surface's wire `altitude`; the within-altitude move-ban is `clampReorderToAltitudeBands`. Lifting band
  composition into the layout data would (a) **invert §4a's own "altitude is a derived projection"
  invariant** — surfaces homed by a layout declaration rather than by their altitude, a second source of
  truth that can diverge from the catalog — and (b) break the no-code-layout contract (a plugin layout
  could then try to override altitude grouping). Non-rail zones (stage / statusBar) are single-surface /
  flag zones where bands carry no meaning. §4b's prose "the layout composes altitude bands" is aspirational;
  the faithful realization, shipped here, is **"the render derives bands from altitude; the layout stays
  data-only."**
- **§4c projector — `newestFirst` + `EnvelopeStream` are the right extractions; a full shared
  filtered-event-stream projector is over-DRY (AHA).** The one genuinely shared concern (newest-first
  ordering, `eventStreamProjection.ts`) is extracted, and `EnvelopeStream` is the shared SSE substrate both
  surfaces compose. Beyond that, Activity and Logs are **distinct authorities with distinct reasons to
  change**: Logs has severity + sub-category filters, pause/resume, a connection-state UI, and
  virtualization; Activity has burst-collapse, a transparent lifecycle, and no filter model. A shared
  projector would couple two authorities and force each into the other's model for ~30 lines of saved
  lifecycle code — the over-unification AHA warns against (and this doc's own one-authority-per-surface
  thesis endorses). The §4c **merge-foreclosure** (the structural half — the gate `altitude-conflict` rule)
  IS implemented; only the projector-unification half is declined, by reasoned AHA, not omission.

**Net — 571 is complete.** §4a–§4e, §5, and §6 are implemented and verified: altitude is DERIVED on the
wire (validator + gate share the one `SurfaceAltitude.derive`); both the trust and diagnostic foreclosures
hold at the surface tier (gate: `trust-requires-core`, `diagnostic-requires-core`) and the component tier
(FE `RESERVED_COMPONENTS` + the merge clamp); homing is a derived projection with a within-altitude
move-ban; the one-authority-per-surface merge-foreclosure is a derivation conflict; and Activity / Logs
derive their altitudes live (incl. the rendered trust-`DENIED` row). §7 stays out of scope (slice
concerns). The two items above are closed by decision. **(But see §10 — a follow-on consolidation slice
was built, then reverted; the retrospective there is the most current truth on this doc.)**

## §10 Retrospective — the diagnostics consolidation slice, built then reverted (2026-06-10)

**Current state of this branch.** `worktree-571-surface-altitude` is reset to its base (`4f221028c`); the
altitude axis (§1–§9) remains landed on `gate-fixes-main-green` (merge `dfcb4286b`) and is untouched. A
follow-on **diagnostics-consolidation slice** was implemented and live-verified on 2026-06-10, then
**reverted in full** by decision. Nothing from 571 is on `main`. This section is the learning; where it
disagrees with §1–§9, **§10 wins** (dated-history rule).

**What the slice did (and why it was built).** §9's as-built homed the two DIAGNOSTIC surfaces by *grouping
their rail icons into a band* (line 393: "the rail bands DIAGNOSTIC surfaces together"). The user flagged
that this was **not the intended design** — the motivating idea (§6) was to **consolidate** Logs (and
Health) into **one** Diagnostics home, not to group separate rail peers. The slice acted on that: a new
`core.diagnostics` rail surface (`jf-diagnostics`) that embeds Health + Logs + Boot-phases as tabs;
Health/Logs moved off-rail (`Placement.DEEPLINK`); the rail band removed; and a new gate rule
(`surface-altitude/diagnostic-not-consolidated`) to make the band-vs-cluster mistake unbuildable. It passed
all static tiers and a real-browser pass (single Diagnostics rail entry; live tabs; deep-link still mounts
Logs).

**Why it was reverted — the proportionality learning.** Re-examined end-to-end, the *visible* deliverable
equalled a **small placement task** ("put Logs/Health in one Diagnostics view"), while the machinery
beneath it — a four-value governing axis, its derivation, and a governance gate — was the much larger
structure the small task had been used as the *proof-by-example* for (the frontmatter says as much: the
audience-floor "is the seed of this entire design"). Two concrete redundancy seams confirmed the mismatch,
both found by re-reading source after an audit (the `audit-without-test` / `critical-analysis` discipline):

1. **The plugin foreclosure rhymes with an existing one.** `TRUST/DIAGNOSTIC ⟹ CORE` (the gate) and the
   pre-existing `audienceFloorFromProvenance` (`UNTRUSTED_PLUGIN → OPERATOR`,
   `SurfaceAreaValidator.java`) read the *same* trust-tier hierarchy — different teeth (hard build-fail vs
   soft floor), same underlying signal.
2. **`Placement` and `altitude` are partly redundant and must be gate-synced.** The visible consolidation
   (off-rail) is actually driven by hand-set `Placement.DEEPLINK`; altitude drives only the divider/banding.
   Two signals encode one intent, kept consistent by the gate — a sync burden, not a single source.

The axis is **not** a pure duplicate of audience — it genuinely subdivides each audience tier by
surface-kind (Health USER+DIAGNOSTIC vs Activity USER+TRUST is a distinction audience cannot make), and
its homing reads `altitude` at runtime (`Rail.render`, `clampReorderToAltitudeBands`). But "not a duplicate"
is a low bar; the operative judgment was **proportionality** — the same user-visible outcome was reachable
with a far smaller change (set `Placement.DEEPLINK` on Logs/Health + one `jf-diagnostics` container, no new
axis-tier scaffolding). Given that, the slice was reverted rather than merged.

**The transferable lesson.** When a concrete, small placement/UX request arrives, resist promoting it into a
new governing axis on the strength of a single proof-by-example. Ship the small change against the existing
substrate (here: `Placement` + a container), and only generalize to a new derived axis once *multiple*
independent surfaces demand the distinction — not because one surface's placement was momentarily
under-determined. The §3 framing ("the substrate is one generalization away") is seductive precisely
because the generalization is always *one* step away; that it is cheap to state is not evidence it is owed.

## §11 The correct structure — Surface Composition (declared, not derived) — 2026-06-10

This is the current theory; where it disagrees with §1–§9, §11 wins. It keeps the *goal* the original
doc started from — **"put Logs inside System Health"** — and discards the axis that goal was inflated
into. The genre is unchanged (design-theory: feasibility/phasing/mount-shape disregarded; the end-state
is what's stated).

### §11.1 Re-diagnosis — the missing primitive is *composition*, not *altitude*

The axis answered the wrong question. It asked *"how do we **derive** where a surface lives from what
it consumes?"* The honest, smaller gap is structural:

> **"Surface B lives inside surface A" is not a first-class, declarable relationship.** The `Surface`
> manifest has `id × presentation × audience × placement × consumes × mountTag × provenance ×
> stateSchema × riskTier × altitude` — and **no host / member / parent field**. `SurfaceConsumes`
> carries `conversationShapes` (561's chat-specific mechanism) but no member-*surface* concept. So a
> surface can declare *what data it reads* and *which chrome zone it sits in*, but it **cannot declare
> that it presents another surface inside itself.**

Because that relationship is unrepresentable, every attempt to express "these views belong together"
has been an improvisation against a substitute mechanism:

1. a **rail band** (group the icons) — grouping faked in the chrome renderer;
2. **ad-hoc embedding** (one surface's Lit template hard-codes another's tag) — grouping faked in code,
   invisible to the registry/gate;
3. the **altitude axis** (derive grouping from consumed authority) — grouping faked as a *derived
   property*, with the redundancy seams §10 found.

Three different fakes for one missing primitive. The fix is to **add the primitive**, not a fourth fake.

### §11.2 What already exists — and what does NOT (the 561 correction)

> **De-risk correction (2026-06-10, §11.7 D1):** an earlier draft of this section claimed §11 merely
> *extends 561's host-with-panels*. First-hand source reading **refuted** that — 561 shipped no reusable
> declared-member host. The host/member iteration §11 needs is **new work**. The bullets below are the
> corrected, source-true reuse map; where the de-risk corrects this section, the de-risk wins.

- **561 is a *cautionary precedent*, not a reusable mechanism.** "One interaction window" *declares* its
  modes in `SurfaceConsumes.conversationShapes`, but `UnifiedChatView` **hard-codes** their rendering — an
  affordance bar of explicit buttons (`UnifiedChatView.ts:1358-1412`), with no iteration over the
  declaration, and it never reads `conversationShapes` at runtime; the generic `jf-chat-shape-mount`
  consumes only the *first* shape (`Shell.ts:2454-2463`). So 561 is precisely *declaration without an
  iteration mechanism* — the two drift, which is the failure mode §11's primitive exists to prevent. It is
  evidence **for** the primitive, not an existing copy of it. A general "a host iterates its declared
  member surfaces" relationship **does not exist today and is genuinely new** (§11.3 part 1).
- **The embed pattern is the rendering mechanism, already live-verified.** `ActivitySurface` embeds
  `<jf-action-ledger>`; `HealthSurface` / `LogSurface` are self-contained custom elements that render
  fully when embedded in another surface's template (live-confirmed in the reverted slice).
- **The manifest field-flow is a proven, lightweight reuse (§11.7 D4).** Adding a `members:
  List<SurfaceRef>` field mirrors the `altitude` precedent exactly: a Java record field that Jackson
  auto-serializes (`RegistryController.handleSurfaces`), a hand-written FE `surface.ts` type, and
  `CorePlugin.ts` omitting it so the wire stays the single authority (merged by
  `mergePluginSurfaceContributions`) — no dual-declaration drift. This part *is* genuine reuse.
- **There is no reusable tab/pane primitive — but one is cleanly extractable (§11.7 D5).**
  `RetrospectivePanel` hand-rolls a `role="tablist"` (`RetrospectivePanel.ts:252-271`) whose render is
  generic (list of `[id,label]` + active + onSelect); only its data-loading is coupled. Extracting a
  `<jf-tab-group>` is minor decoupling (add arrow-key a11y the current one lacks). This is the second
  honestly-new piece.
- **565's composition register/gate is the governance template** (`composeGridStyles` +
  `composition-surfaces.v1.json`: one-authority-per-composition), and **559's `SurfaceLayout`** is the
  header/body region contract a composite host composes over. **472's `zones`** is a per-zone *visibility
  allowlist* (`exclusive` / `splitAxis` included) — explicitly *not* a host/member authority; it names
  what a layout *shows*, never what a surface *contains*.

**Honest cost summary:** §11 reuses the field-flow (D4), the embed pattern, `SurfaceLayout`, and the
565 gate template — but the two load-bearing pieces, **the declared-member iteration mechanism** and
**the extracted tab component**, are NEW. "Extend 561" was an overclaim (the same one the reverted axis
made); the accurate statement is *"a small new primitive, built against proven field-flow and gate
templates."* The user accepted this new-substrate cost (§11.7) because it is the minimum that delivers
the prevent-recurrence property.

### §11.3 The structure — three parts, one of them genuinely new

The driving case is the user's literal goal: **System Health hosts Logs** (one member). (Boot-phases is
a *panel*, not a registered surface — §11.7 D2 — so it is a later promotion, not part of the core case;
keeping the core case at one member also keeps the design honest about what one host actually needs.)

1. **Structural (the missing primitive — NEW, §11.2/§11.7 D1).** A **declared host/member relationship**
   on the surface manifest: a host surface names the member surfaces it presents (System Health declares
   it hosts Logs). The field flows like `altitude` (D4) — single Java authority, no drift. **Membership
   is stated once and read as-is — never projected from authority.** A member's *home is its host*, and
   the mechanics are now concrete (§11.7 D3): "off the rail" is a **one-line member-exclusion** in the
   rail build (`Shell.ts:1717-1718`, beside the existing visibility filter), reading the membership
   relationship — *not* a separately hand-set `Placement`. Because routing is placement-agnostic
   (the dispatcher has no placement check), the member **stays deep-link-navigable** even though it is
   off the rail — so the membership declaration is the single home-authority, and "off-rail vs routable"
   stop being two signals to sync.
2. **Presentational (extract one component — NEW-but-cheap, §11.7 D5).** One reusable **tabbed-composite**
   primitive (the `RetrospectivePanel` tablist, extracted with arrow-key a11y added) that a host renders
   its *declared* members into — per 559's "presentation projects from a declaration." The host does not
   hard-code member tags (the 561 anti-pattern); it **iterates its declared members** and mounts each via
   the shared stage-mount path (`mountSurface`, D3). (Tabs vs panes vs accordion is mount-shape, out of scope.)
3. **Governance (light, integrity-only).** A small register/gate on the 565 template enforcing **one home
   per surface**: a surface declared as a member of a host may **not** also be a standalone rail surface,
   and may not be a member of two hosts. This is the teeth that makes the *original* bug — Logs as a stray
   rail icon beside its own home — **unrepresentable**. It is a **referential-integrity** check over a
   declaration, NOT a derivation: it reads the declared relationship and forbids contradictions; it
   computes nothing from consumed authority.

### §11.4 Why this is correct where the axis was wrong

| | Reverted axis (§1–§9) | Surface Composition (§11) |
|---|---|---|
| Membership is… | **derived** from consumed authority (altitude) | **declared** directly (host names its members) |
| Sources of truth for "home" | two — `altitude` *and* `Placement`, gate-synced | one — the membership declaration |
| Redundancy with existing substrate | foreclosure rhymes with `audienceFloorFromProvenance` | none — composition is orthogonal to audience |
| What it diagnoses | "homing isn't derived" (a symptom) | "composition isn't representable" (the cause) |
| New conceptual surface area | a 4-value axis + derivation + projection | one manifest relationship + one extracted component |

The contrast is the whole point: the axis tried to be *clever* (infer where things go from what they
read); composition is *honest* (say where things go, once). It directly **dissolves the §10
`Placement`/altitude seam** — there is no second signal to keep in sync, because the relationship *is*
the home. And it embodies §10's transferable lesson rather than re-breaking it: the generalization it
adds is the one the recurring bug actually demanded (representable composition), not a speculative axis.

### §11.5 Scope — and what this does NOT re-open

- **It supersedes the axis's homing/grouping half only.** Declared composition replaces derived homing.
  §1–§9's altitude enum/derivation as it concerns *where surfaces group* is the part §11 overrides.
- **The plugin-eligibility foreclosures are a separate reckoning, scoped out here.** Whether a diagnostic
  or trust surface may be plugin-authored is an authority/trust question, not a composition question — and
  §10 showed that part partly restates the existing `audienceFloorFromProvenance`. §11 deliberately does
  not entangle it; it should be settled on its own merits (likely: reduce to the audience-floor, not a new
  gate), in its own slice.
- **Out of scope (design-theory genre):** the mount shape (tabs vs panes vs nested stage), migration
  mechanics for the existing surfaces, the exact manifest field name/shape, and feasibility/phasing.

### §11.6 Why it prevents the issue long-term

The defect class behind this whole arc was: *"related surfaces need to live together, and there is no
declared way to say so, so it gets improvised."* Once composition is a first-class declared relationship
with an integrity gate, **"group these surfaces" has exactly one answer** — declare the host/member
relationship — and the three fakes (rail band, ad-hoc embed, derived axis) are no longer reachable:
the band has nothing to group (members aren't rail surfaces), the ad-hoc embed is visible to the gate
(an undeclared member is an integrity violation), and the axis is unnecessary (home is declared, not
derived). That is the long-term structure the small "Logs into System Health" task actually needed.

### §11.7 Confidence ledger — de-risk pass (2026-06-10)

A measured de-risk pass (the §8 discipline, applied to §11) read the load-bearing claims against shipped
source *before* trusting the design — specifically to avoid repeating the axis's "reuse 561 machinery"
overclaim. Where this ledger corrects §11.1–§11.6, **the ledger wins** (dated-history rule). Net: **the
design is mechanically CONFIRMED feasible; one framing claim ("extend 561") was REFUTED and corrected;
the proportionality call was put to the user and resolved as *structural*.**

- **D1 — "extend 561's host-with-panels" → REFUTED; corrected to "new primitive."** `UnifiedChatView`
  hard-codes its affordance bar (`UnifiedChatView.ts:1358-1412`), never iterates its declared
  `conversationShapes`, and `jf-chat-shape-mount` takes only the first shape (`Shell.ts:2454-2463`). 561
  shipped *declaration without iteration* — the drift the §11 primitive prevents, not a reusable copy of
  it. The host/member iteration is **new work**. §11.2 corrected; this is the single most important
  finding (it is the same overclaim class as the reverted axis, caught this time before building).
- **D2 — boot-phases is a panel, not a surface.** `jf-boot-phases-panel` is unregistered. The core
  driving case is narrowed to the user's literal goal — **System Health hosts Logs** (one member);
  boot-phases is a later promotion.
- **D3 — off-rail mechanics RESOLVED (the §11.3 hand-wave made concrete).** The rail filters
  `placement === 'RAIL'` with existing per-surface exclusions (`Shell.ts:1697`, visibility at
  `1714-1717`); a member-exclusion is one line at `Shell.ts:1717-1718`. DEEPLINK-navigability is
  **orthogonal** to rail-presence (the router has no placement check), so a member is off-rail yet stays
  URL-routable. The §10 `Placement`/altitude seam is genuinely **dissolved** — membership is the single
  home-authority, *read* not *derived*.
- **D4 — manifest field-flow CONFIRMED lightweight + single-authority.** `members: List<SurfaceRef>`
  mirrors the `altitude` precedent: Java record field → Jackson auto-serialize (`RegistryController`) →
  hand-written FE `surface.ts` → `CorePlugin.ts` omits, wire wins (`mergePluginSurfaceContributions`).
  No dual-declaration drift. This part is genuine reuse, not new substrate.
- **D5 — tab primitive extractable-with-minor-decoupling.** `RetrospectivePanel.ts:252-271` render is
  generic; only data-loading is coupled; a11y has roles + `aria-selected` but **lacks arrow-key**
  navigation — the extracted `<jf-tab-group>` should add it.
- **Live tier.** The embed-renders-live concern was already settled by the reverted slice's live-browser
  pass (Health/Logs rendered fully when embedded); no new dev-server experiment was required.

**Confidence after the pass:** design correctness/feasibility **HIGH** (every mechanism cites shipped
source); the "extend 561" claim **corrected to NEW**; **proportionality** — the one judgment the de-risk
could not settle internally — was surfaced to the user with the D1 cost made explicit. (The initial
answer was "structural"; it was **revised to manual** the same day — see §11.8, which supersedes this.)
No surprise remains at the implementation-mechanics level; the honest residual is that §11-the-system is
a *small new substrate*, and per §11.8 we are choosing **not to build it yet**.

### §11.8 Scope decision — REVISED to manual (2026-06-10, supersedes §11.7's "structural")

After the de-risk made the cost explicit (D1: §11-the-system is *new* substrate — a `members` field, a
rail-exclusion, a one-home gate, an extracted tab component — for a *single* host today), the user
revised the call: **do not build the composition system now; do the consolidation manually.** This is
the §10 proportionality lesson applied one notch further — a single window into a single host does not
earn a general "move a surface inside another" mechanism (YAGNI).

**What "manual" means concretely (the actual near-term plan):**
- **System Health embeds Logs directly.** The Health surface's own template gains a small hand-rolled
  tab strip (the `RetrospectivePanel` pattern, copied inline — *not* extracted into a shared
  `<jf-tab-group>`) and embeds `<jf-log-surface>` via the already-verified embed pattern.
- **Logs leaves the rail by hand.** Its catalog entry's `Placement` is set to `DEEPLINK` directly in
  `CoreSurfaceCatalog.java` (it stays reachable by URL). No `members` field, no rail-exclusion read.
- **No new substrate.** No manifest `members` field, no one-home gate, no shared tab primitive. The
  grouping lives in the Health view's template — accepted as a *deliberate, contained* instance of
  "fake #2" (§11.1) for one screen, not a pattern to spread.

**Status of §11.1–§11.7 (the composition system):** retained as the **documented correct long-term
structure**, *deferred not discarded*. It is the **trigger-on-second-host** plan: if a second surface
ever needs to host members, build the declared `members` primitive then (the de-risk already proved it
cheap and drift-free), rather than hand-rolling a second template embed. Until then, the manual embed is
the proportionate answer to the literal goal — *Logs inside System Health*.

### §11.9 IMPLEMENTED (2026-06-13, via tempdoc 578)

The §11 composition system — deferred in §11.8 as "trigger-on-second-host" — is **now built**, because
tempdoc 578 (window taxonomy) met that trigger four times over (Library⊇Browse, System⊇{Health,Logs,
Activity}, Settings⊇Appearance, AI Brain⊇Memory). As-built per §11.3:
- **Structural:** `members: List<SurfaceRef>` on the `Surface` manifest (raw-Jackson wire; FE `surface.ts`
  mirror), declared via `.withMembers(...)` in `CoreSurfaceCatalog.java`. Excluded from
  `SurfaceAltitude.derive` (a host does not inherit member altitude — 578 §10-U1).
- **Presentational:** one reusable `<jf-surface-tabs>` composite (lazy `ensureSurfaceLoaded`+`mountSurface`,
  roving-tabindex a11y, per-tab altitude framing for cross-altitude members; host-own body via `<slot>`).
- **Governance:** `check-surface-composition.mjs` + `governance/surface-composition.v1.json` — one-home
  integrity (a member is not also RAIL, not double-hosted, not dangling, not self-hosting), plus an
  `check-a11y-closure` extension (members emit no own `<h1>`/`main`).
- **Beyond §11's single-member scope (578):** N members per host; **cross-altitude** composition (a host
  presents DIAGNOSTIC + TRUST members, each keeping its own altitude framing in the tab strip);
  **cross-source** composition (a Java host presents an FE-CorePlugin member, e.g. AI Brain ⊇ Memory).
See tempdoc 578 §11–§13 for the full as-built record (incl. the post-review correction that the System
hub is PRODUCT, not DIAGNOSTIC — it composes members, it does not itself project a diagnostic channel).
