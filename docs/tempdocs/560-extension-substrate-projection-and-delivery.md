---
title: "560. The Extension Substrate, Completed — Single-Authority Projection + Delivery"
status: done
created: 2026-05-30
updated: 2026-06-11
landed: "§29 delivery-face + 4c landed on main (merge c67e73217, 2026-06-11); the §5 run-tier witness's
  LIVE-REGISTRY tier landed after (§30, ADR-0042, merge cd70b39f2, 2026-06-11) — resolving the four
  §E.2.1 decisions as defensible defaults, so the witness is no longer 'blocked' (only the broader
  §4b uniform-all-kinds witness surface remains). DONE = the buildable verb/delivery face (§28–§29):
  operator-approval trust path + RemoteTrustChannel fix, the ConversationShape runner, Resources/Prompts
  serving bridges, run-tier witness observability, SES lockdown, 4d CapabilityFamily/durable grants, the
  4c Operation projection, AND the §5 live-registry consumer-presence witness (LiveWitness — covers the
  runtime-composed contributions the static tiers miss; complements the static runtime-witness gate).
  Residual is deferred-by-design (NOT incomplete): 4a backend capability-attenuation (do-not-build, §9.2
  security), the §4b *uniform-all-kinds* witness surface (model as a 575 projection when scoped), real
  Sigstore verification, DeliveryChannel reification, and a single global trust verdict — see
  §28.D-deferred / §30."
takeover-review: "§9 (2026-06-03) — fresh-eyes takeover: 5 parallel source-verification passes (file:line ledger §9.1), external grounding (MCP / VS Code extension host / object-capability), one live gate experiment. Verdict: the meta-substrate spine (4e) is sound and the trust seam (4d/issue 1) is verified-good, but §4's 'extend/reify' verbs misread the current state — 4d is ~80% built (Grant exists; 'new SourceTiers' is a category error), 4b conflates a build-time gate with a run-time witness, 4c's 'Brain self-declares as CORE' collides with the host-owns-truth gate, and 4a's issue-3 resolution omits the backend capability-attenuation substrate it presupposes ('MCP-style isolation' carries ambient authority — isolation ≠ attenuation). Concrete §4 amendments in §9.8; open maintainer decisions in §9.9. Theory §1–§8 stands with those folded in."
related: [564, 561, 553, 550, 543, 542, 534, 530, 521, 507, 487, 481]
scope-decision: "§12 (2026-06-03) — near-term implementation scoped to 4c ONLY (registry shape → generated single-authority projection, retiring hand-mirrored types/registry.ts). All other facets deferred/skipped: 4a (backend attenuation) skipped as greenfield-XL + security-dubious; 4b/§5 (witness+gate) deferred (YAGNI until a real plugin ecosystem; blocked on 4 §E.2.1 decisions); 4d (CapabilityFamily) skipped YAGNI; 4e is a lens. 4c kept because it closes a known shipped defect class (AuditPolicy drift). 4c readiness in §13: mechanism HIGH (DR-B demonstrated), clean-landing MED-LOW (5 untested unknowns)."
audience: maintainer
---

# 560. The Extension Substrate, Completed — Single-Authority Projection + Delivery

The first build of this substrate unified the **noun**: one `Declaration`, one
`ContributionRegistry` composer, one trust verdict, and the `NonEmpty<ConsumerHook>`
keystone (status in Appendix A). What it never unified is the **verb** — how a
declaration actually *reaches* its consumer across a boundary. Every remaining gap is a
boundary-crossing problem, and the correct design is one structural element with two
faces.

> **A contribution has a declared *shape* and a delivered *behavior*. The correct
> substrate makes both single-authority seams on the one discipline-gate kernel (530):
> the shape is declared once and *projected* to every process; the behavior is
> *delivered* to the altitude its trust permits through one gated, attested seam. The
> alternative — a second shape authority, an undelivered declaration, an ungated
> delivery, an unwitnessed contribution — is made unrepresentable, not merely
> discouraged.**

---

## 1. The problem, restated structurally

The four open items are not four problems. They are four faces of one: **the registry
composes declarations, but nothing unifies their delivery.** There are exactly two
boundaries a contribution must cross, and a gap on each:

- **The shape, across a *process* boundary.** A `Declaration` authored in the Head must
  be understood by the FE (TypeScript), the Worker (Java), and Brain (inference). Today
  it is *hand-mirrored* — the FE reads the registry through a hand-written
  `types/registry.ts`; `HealthEvent` is defined four times; the `AuditPolicy 'FULL'`
  enum bug shipped from exactly this drift. This is **WS7** ("one declaration across
  processes") and it is *precisely* the defect class sibling tempdoc **564** names for
  the FE↔backend contract boundary.

- **The behavior, across an *altitude* boundary.** A contribution must *execute* at the
  right altitude — browser, Head, Worker, or an external process. Today the altitude is
  *implicit*: it is hidden in which `OperationHandler` is bound to an operation, and a
  user plugin has no handler binding at all — so it is **browser-only by construction**
  (**issue 3**). And because there is no first-class delivery *event*, the proof that a
  delivered contribution was actually consumed is a *static snapshot* blind to anything
  added at runtime (**issue 4**), and the four delivery paths are four bespoke code
  routes with no shared seam (**WS8**).

One face is already correct, and it is the template for the rest. **Issue 1 (agent
safety) is solved**: the trust verdict is computed once, at the delivery point, in
`IntentGateEvaluator` — the same instance shared by the executor and the preview
controller (so prediction cannot disagree with enforcement), and the FE obeys the
backend's `gateBehavior` verdict rather than re-deriving it (the second FE authority was
deleted in 561). The live re-validation (Appendix A) demonstrated it: an LLM-emitted
MEDIUM write was held behind a typed-confirmation gate, not auto-fired. **The trust seam
is the exemplar; the correct design is to make delivery, projection, and witness follow
its shape — verdict-at-the-seam, single-authority, attested.**

---

## 2. What already exists — the foundation to extend (not invent)

The correct design builds almost nothing from scratch. The seams exist; they are
unjoined.

- **Registration is already unified.** `ContributionRegistry` /
  `ContributionComposer<K,V>` (`modules/app-agent-api/.../registry/`) is one
  transactional, tier-agnostic composer over all four axes, reused by Head and Worker.
  `install(Installation)` validates-before-commits and is exactly how the MCP host adds
  an external server's tools at runtime. *Registration* is single-authority; *delivery*
  is not.
- **The cross-boundary execution pattern already exists, unnamed.** `McpToolHandler`
  implements `OperationHandler` — it *projects an external-process tool onto an
  `Operation` + a handler whose body crosses the boundary* (stdio to the subprocess).
  `IngestOperationHandler` / `SearchOperationHandler` do the identical thing for the
  Worker (a thin handler whose body is a gRPC call). This "handler-body-crosses-the-
  boundary" pattern is the latent **DeliveryChannel** — it just isn't reified or declared.
- **Trust-at-the-seam is built and single-authority.** `IntentGateEvaluator` +
  `TrustLattice` (N-dimensional, `DENY` default) + `ConsentCapsuleAuthority`
  (cryptographic, args-bound, single-use) compute one verdict at
  `OperationExecutorImpl.enforceTrustLattice`. `RiskTier` is declared once on
  `OperationPolicy.risk`; provenance enters via `InvocationProvenance.transport`
  (`AGENT_LOOP`→`UNTRUSTED` vs `BUTTON`→`TRUSTED`); the Watch/Assist/Auto dial
  (`AutonomyLevel`) is a first-class issuance input.
- **The register-and-gate kernel exists and has precedents.** The 530 discipline-gate
  kernel already hosts the `execution-surface` register (one canonical `SearchTrace`,
  governed projections) and `operation-surface` (the indexing lifecycle). A new
  *contribution-surface* register is one more instance, not a new mechanism.
- **The projection substrate exists and 564 has already designed its completion.** The
  one working single-authority projection today is `registry-enums.generated.ts` (a
  Java→TS emitter, byte-identical drift-checked) and the `lifecycleState.ts` pattern
  (proto enum → derived FE constants). 564 §4 lays out the correct end-state for the
  *shape*: one contract authority, every representation a generated projection, mandate
  over capability.
- **The lineage names the rest.** 507 (KCS / registries-as-kernel-primitives, resolver-
  time trust attenuation), 543 (the `Action` declaration unifying command + virtual-
  operation), 550 (the lifecycle record + the attenuable **Grant** primitive), 521 (the
  FE `PluginContribution` atomic install), 481 (the recognized-but-unbuilt "RegistryEntry
  single-authority" frame this doc completes).

---

## 3. The unifying principle

One principle subsumes both faces and all four substrates (Boundary / Dispatch / Trust /
Lifecycle):

> Every contribution is **declared once**. Its *shape* is **projected** to every process
> that must understand it; its *behavior* is **delivered** to the altitude its trust
> permits; its delivery is **attested** to whatever consumes it; and its delivery is
> **gated** by one verdict at the seam. Every projection, delivery, attestation, and gate
> is mechanically derived from the one declaration — never a hand-authored second copy,
> never a bespoke per-kind path.

Two sub-principles make it precise:

- **The projection-tier ladder** (strongest first): **Collapse** (there is one thing; no
  second copy exists) > **Unrepresentable** (a second copy cannot compile) > **Generate**
  (a human never authors the copy) > **Gate** (drift is caught at build). Every concern is
  pushed as high as it can go. The shape boundary today sits at the *bottom* (hand-written
  TS types and `.loose()` Zod where *Generate* should reign); the behavior boundary has no
  rung at all (altitude is implicit, delivery unattested).

- **Mandate over capability** (564 §3). "Drift is structurally impossible" is true only
  under mandate. The codebase's own `structural-defects-no-repeat` rule already met the
  evidence threshold (the `AuditPolicy 'FULL'` drift, `HealthEvent` 4×, the agent-safety
  seam). Continuing to "tolerate hand copies and *also* generate" is the half-applied
  state that produces the tax.

**The AHA guardrail, stated up front.** Unify only what shares a reason to change. The
four delivery *transports* — SES compartment-eval, in-process call, gRPC, stdio — do
**not** share a reason to change; they stay plural. What unifies is the *seam around*
them: route + gate + attest. The Delivery substrate is therefore **transport-polymorphic**
(one seam, an adapter per altitude), **not** a collapsed `deliver()` that erases the
transports. This is the honest half of the WS8a "the axes legitimately differ" finding,
kept.

---

## 4. The correct structure

Five facets, each a completion or extension of a §2 seam.

### 4a. Altitude is a declared property; reify the Delivery substrate

A contribution's execution altitude (browser / Head / Worker / external) becomes a
**declared field on the declaration**, not an accident of which handler happens to be
bound. The "Dispatch" substrate — today merely the composed read-view — is reified into a
real **DeliveryChannel**: a transport-polymorphic seam that, given a declaration and its
consumer, routes the invocation to the declared altitude through the right transport
adapter. The `McpToolHandler` / `IngestOperationHandler` pattern *is* the adapter shape,
generalized and named.

This dissolves **issue 3**. "Browser-only" was never a law — it was altitude-as-implicit
plus the absence of a non-browser delivery channel for user contributions. Once altitude
is declared and delivery is a substrate, a plugin that declares backend behavior is
delivered to the Head the *same way MCP already is*. And the trust-correct form of
*untrusted* backend behavior **is** the out-of-process sandbox MCP already uses — so the
answer to "let plugins do more than the browser" is "deliver to the altitude your trust
permits," with isolation proportional to trust (507's resolver-time attenuation at the FE
altitude; an MCP-style sandboxed subprocess at the Head/Worker altitude). User plugins
beyond the browser stop being a missing feature and become a *trust tier of the one
delivery seam*.

### 4b. Delivery is an attested event; the witness is live

Delivery becomes a **first-class, attested runtime event**: when a contribution is
composed into the live registry and handed to its consumer, the substrate emits an
attestation ("declaration *D* delivered to consumer *C*"). The witness is then a
**subscriber to that live signal** — kind-agnostic, covering core, agent-tool, MCP,
workflow, and plugin uniformly — served over a runtime channel
(`/api/registry/witness`, or a `witnessed` block on the live `/api/registry/operations`),
and the gate checks the *live attestation*, not a frozen file.

This closes **issue 4** at the root. Today `RegistrySnapshotExporter` builds the witness
from `new CoreOperationCatalog()` — static catalogs that never see the live
`ContributionRegistry` or the MCP host, which is *why* the gate is blind to runtime-only
tools. Attestation makes witness a property of delivery itself: **a contribution is
witnessed because it was delivered**, so a runtime MCP tool is witnessed for free, and a
declared-but-undelivered capability is a live, observable absence rather than a silent
one. (The `consumer-presence` keystone stays as the *compile-time* half — a declaration
must *name* a consumer; attestation adds the *runtime* half — the named consumer must
*receive* it.)

### 4c. The declaration shape is a projection — adopt 564

The `Declaration` shape is the canonical source; `types/registry.ts`, the Worker's view,
and Brain's view are **projections of it**, generated — not the hand-mirrors that drift
today. This is **not a new design**: it is 564's contract-projection substrate with the
`Declaration`/registry shape registered as one more instance (a `registry.proto`, or the
`registry-enums.generated.ts` emitter extended from the six enums to the full nested
shape). This closes **WS7**.

Brain joins by the *same* path: it stops being a process the Head declares *about*, and
becomes a `ContributionRegistry` participant that *self-declares* its OBSERVABLE and
EXECUTABLE capabilities — over the same `install(Installation)` channel the MCP host
already uses (a gRPC Brain→Head install, Brain treated as a `CORE`/trusted contributor).
"One declaration generated to FE+Head+Worker+Brain" is then projection (the shape) plus
delivery (Brain's install crossing the process boundary) — the two faces, applied to the
inference process.

### 4d. Trust is computed once, at the delivery seam — extend the exemplar

Issue 1's topology is already correct and becomes the **rule for every delivery**: one
verdict, computed once, at the seam, obeyed everywhere. The extensions are additive, not a
rewrite: new `SourceTier`s (`PLUGIN`, `MCP`, `WORKFLOW_NODE`, `BRAIN`) flow through the
*same* `IntentGateEvaluator` chokepoint (the `TrustLattice` already accepts new
coordinates without forking); and the single-use consent capsule generalizes to 550's
**Grant** — attenuable, revocable, caveat-bearing — so "may this plugin / MCP call /
workflow node / Brain op proceed" is one model, with the autonomy dial as the issuance
policy *over* grants, not a parallel authority. Every DeliveryChannel (4a) passes through
this one gate; an ungated delivery is structurally impossible because delivery *is*
gate-then-transport.

### 4e. One meta-substrate

Wire-contract, registry, `SearchTrace`, presentation, the FE-contract boundary (564),
**and** this extension substrate are instances of **one** meta-substrate: *canonical
source → governed projections → coverage gate*, hosted on the 530 kernel. 564 completes it
for **data** (the contract *shape*); this doc completes it for **behavior** (the
contribution's *delivery*). The result is not two bespoke solutions; it is one principle —
481's recognized-but-deferred "RegistryEntry single-authority" frame — with N instances,
two of which (564 and 560) are siblings differing only in whether they project a shape or
deliver a behavior.

---

## 5. Why this prevents the issue long-term

The issues recur because the structure *permits* a bypass: a second hand-authored shape, a
contribution with an implicit altitude, a delivery that skips the gate, a capability that
is declared but never delivered. The correct design adds one **contribution-surface
register + gate** on the 530 kernel that makes each bypass **unrepresentable at build
time**:

- a contribution declaring an altitude with **no DeliveryChannel** fails the build;
- a delivery path that **bypasses the trust seam** fails the build;
- a declaration **kind with no live-attestation consumer** fails the build (the witness,
  promoted from snapshot to live);
- a **hand-authored wire-shaped declaration** (a second shape authority) fails the build
  (564's mandate).

These four invariants stop being honor-system prose (~70% adherence) and become build
properties (~100% within scope) — the same move that made `SearchTrace`'s four-
representations problem (549/553) unrepeatable for search execution. A new contribution
kind, a new altitude, a new process inherit the guarantees for free, because the substrate
only admits *declarations-and-their-derived-projections-and-deliveries*.

---

## 6. Extend-vs-build map

| Facet | Action | Existing foundation |
|---|---|---|
| 4a — altitude declared + Delivery substrate | **reify** `Dispatch` into a transport-polymorphic `DeliveryChannel`; **generalize** the handler-body-crosses-boundary pattern | `McpToolHandler` / `IngestOperationHandler`; `ContributionSubstrates`; 507 resolver attenuation |
| 4b — live attestation witness | **promote** witness from static snapshot to a runtime attestation on `install` / `emit`; serve over a registry endpoint | `RegistrySnapshotExporter`, `AgentOperationEmitter`, `ContributionRegistry`; `runtime-witness` gate |
| 4c — shape as projection (+ Brain self-declares) | **adopt** 564; register `registry.proto`; Brain installs over gRPC | 564 substrate; `registry-enums.generated.ts`; `ContributionRegistry.install` (MCP path) |
| 4d — one verdict at the seam | **extend** `IntentGateEvaluator` with new `SourceTier`s; capsule → Grant | `IntentGateEvaluator`, `TrustLattice` (built); 550 Grant |
| 4e — one meta-substrate | **complete** 481's projection unification; register on the kernel | 530 kernel; 564; 553 projection-vs-fork |
| prevention | **add** one `contribution-surface` register + gate | 530 kernel (`execution-surface` / `operation-surface` precedents) |

Explicitly **not** built: a new IDL (`buf`/proto is validated), a new gate kernel (530
exists), a new declaration type (543's `Action` / the existing `Declaration` suffices), or
a `deliver()` that collapses the four transports (the AHA guardrail forbids it).

---

## 7. Honest tensions and open decisions

- **WS8a reconciled, not overturned.** "The four delivery shapes legitimately differ" is
  kept as the reason the transports stay plural; "therefore there is no unifying seam" is
  the half corrected — the seam is route+gate+attest, which *is* uniform. The risk is
  re-collapsing under pressure into a leaky `deliver()`; the design's defense is that the
  DeliveryChannel is an *adapter interface*, not a universal method.
- **Issue 1 is already correct — resist re-opening it.** The temptation is to "redesign
  trust" alongside the rest. Don't. The work is the additive extension in 4d; treating it
  as a rewrite would risk the one face that is verified-good.
- **Issue 3's literal reading is *not* the goal.** "Let a user's `plugin.js` run arbitrary
  backend code" is the wrong target — it is a security regression. The correct target is
  *trust-proportional delivery*: the same out-of-process sandbox MCP already is, reached
  through the one delivery seam. Naming this prevents a future agent from "fixing issue 3"
  by punching a hole in the Head.
- **The one genuinely new structural element.** Everything above extends something — except
  the **cross-process/cross-altitude registry protocol**: the contract by which a
  declaration authored at one altitude propagates its *dispatch intent* (its DeliveryChannel
  binding + its gate verdict requirement) to the altitude that will execute it. No existing
  tempdoc names this; it is 560's actual contribution, and it is what `install(Installation)`
  must grow to carry beyond the MCP special-case.
- **Attestation cardinality.** A live witness must decide its granularity — attest per
  *install* (cheap, coarse) or per *delivery to a specific consumer per session* (precise,
  higher volume). The 550 lifecycle record's correlation-id federation is the likely host;
  the open decision is how much of the agent-window-per-session delivery to attest vs.
  sample.

---

## 8. Relationship to the lineage

This doc is one node in a single meta-design, not a standalone plan:

- **564** — the sibling: contract-projection (the *shape* face). 560 and 564 share §3's
  principle and §5's prevention; they differ only in shape-vs-behavior.
- **561** — the one-window product surface this substrate delivers tools into; the source
  of the now-correct single-authority trust seam (the exemplar of 4d).
- **553 / 550** — projection-vs-fork discipline and the lifecycle + Grant primitive; the
  attestation host (4b) and the Grant model (4d).
- **507 / 543 / 521** — KCS + the `Action` declaration + the FE `PluginContribution`; the
  FE-altitude delivery + isolation foundation (4a).
- **530 / 481** — the gate kernel that hosts the prevention (4e/§5) and the
  RegistryEntry single-authority frame this completes.

---

## 9. Takeover review (2026-06-03): what the code says, and where the theory needs correction

> **What this section is.** A fresh-eyes takeover of this tempdoc. The §1–§8 theory was
> read in full, then every load-bearing *current-state* claim it rests on was verified
> against `main`-as-of-this-worktree by five parallel source-reading passes (each cited
> `file:line`), grounded against external precedent (MCP / VS Code extension host /
> object-capability security), and one live experiment (running the three 560-relevant
> discipline gates). The verdict: **the theory's spine is sound and substantially more
> built than §4's "extend/reify" verbs imply — but four of its five facets misframe the
> *current state* or the *hard part*, and one (4a/issue-3) omits a structural element it
> presupposes.** This section is corrective, not a rewrite; §1–§8 stand, with the
> amendments below folded in. Noncanonical; re-verify before relying.

### 9.1 Verification ledger — the §1–§8 factual substrate

| # | Claim (where) | Verdict | Evidence / nuance |
|---|---|---|---|
| 1 | One `ContributionComposer<K,V>` over four axes (Lifecycle/Boundary/Trust/Dispatch), reused Head+Worker (§2) | **CONFIRMED** | `ContributionComposer.java:23-34` names the four axes verbatim; `install()` validate-before-commit at `:81-114`. Head = `ContributionRegistry.java:71`; Worker = `ExtractorContributionRegistry.java:76`. **Drift:** it lives in module `extension-substrate`, not `app-agent-api/registry` as §2 says. |
| 2 | `McpToolHandler implements OperationHandler`, body crosses the boundary (§2) | **CONFIRMED** | `McpToolHandler.java:23`; `client.callTool(...)` at `:50`; `StdioMcpTransport.java:27` (newline-delimited JSON-RPC over child stdio). |
| 3 | `IngestOperationHandler`/`SearchOperationHandler` are thin gRPC handlers (§2) | **PARTIAL** | They delegate to `IngestTool`/`SearchTool`, which own the `…Callback` that crosses to the Worker. The boundary is **not in the handler body** — it is one indirection deeper. The "handler-body-crosses-the-boundary" pattern §4a wants to generalize is cleanest in `McpToolHandler`; the Worker handlers are a *2-hop* variant. |
| 4 | No `DeliveryChannel`; altitude is implicit (§1, §4a) | **CONFIRMED** | No `DeliveryChannel`/`Altitude` type exists. `Operation.java:37-48` has no altitude field; altitude is 100% encoded in whether `HandlerRegistry.resolve(binding.handlerId())` finds a handler — and `HandlerRegistry` is a **boot-time** registry, so a runtime user plugin has no path to add one. "Browser-only by construction" is exact. |
| 5 | Issue 1 trust seam: one verdict, one instance, executor+preview share it (§1, §4d) | **CONFIRMED** | `IntentGateEvaluator.java:36` `evaluate()` `:67-79`; enforced at `OperationExecutorImpl.enforceTrustLattice` `:576`; same instance read by `OperationPreviewController:109`. `TrustLattice` N-dim, `DENY` default (`:31-41`, `:88`). `ConsentCapsuleAuthority` cryptographic/args-bound/single-use (`:20-44`). FE obeys `gateBehavior` (`AgentSessionController.ts:51`); the second FE authority `agentToolAutoApprove` is **removed** (`:261`). This face is verified-good, as §1 claims. |
| 6 | 4d "add new `SourceTier`s PLUGIN/MCP/WORKFLOW_NODE/BRAIN" | **REFUTED as written** | `SourceTier.java:24-48` has exactly `TRUSTED/MEDIUM/UNTRUSTED`. MCP and workflow sources **already flow through the gate today** as `IntentSource` catalog rows mapping to `UNTRUSTED` (`CoreIntentSourceCatalog.java:63 MCP_EXTERNAL`, `:57 WORKFLOW_TOOL_CALL`). See §9.4 — the literal reading would *fork* the abstraction the lattice exists to prevent. |
| 7 | 4d "the capsule generalizes to 550's Grant" (future) | **ALREADY BUILT** | `Grant.java:33` is a sealed record with `BoundAction` + `CapabilityFamily` scopes and `attenuate()` (`:28-31`). `durableGrantStore` is wired into `enforceTrustLattice` (`OperationExecutorImpl.java:110`, consulted `:598-602`). What is missing is the **issuance ceremony + the `CapabilityFamily` consumer** (`authorizes()` is fail-closed at `Grant.java:115`), *not* the primitive. 4d is a finishing job, not a generalization. |
| 8 | 4c shape-as-projection / WS7 (`HealthEvent` 4×, `AuditPolicy 'FULL'` bug) | **MIXED** | `AuditPolicy 'FULL'` drift was a **real** shipped bug (commit `d05a77d11`; Java `FULL_PAYLOAD` vs FE `'FULL'`). But `HealthEvent`'s "four definitions" are a **source + three generated projections** (`HealthEvent.java` → `health.proto` → `health_pb.d.ts` → `schema-types/health-event.ts`), i.e. *already the model outcome*, not a fork. The genuine un-projected fork is narrower: `Operation`/`Resource`/`Presentation` are still **hand-mirrored** in `types/registry.ts` (header admits it). See §9.6. |
| 9 | 4b witness: built from static catalogs, blind to runtime (§4b, issue 4) | **CONFIRMED + sharper** | `RegistrySnapshotExporter.java:61` literally `new CoreOperationCatalog()`. The `runtime-witness` **gate already exists** (`registry.v1.json:285`) but reads a **unit-test-generated static snapshot** (`enforcer.mjs:29` → `tmp/consumer-presence/registry-snapshot.json`, made by `RegistrySnapshotExporterTest`). Live experiment: in this fresh worktree the snapshot is **absent**, so the gate **passes with a warning**. See §9.3 — this is a tier confusion, not just a static/live gap. |
| 10 | Issue 3: no backend user-plugin loader; FE loader is real (§4a, App. A) | **CONFIRMED** | Backend `Plugin.java:23-55` is only CORE (`OperationCatalogComposition.ownerPlugin:122`) + MCP (`McpToolProjection.toPlugin:109`). FE loader is genuinely live: `PluginLoader.ts` + SES `PluginCompartment.ts` + resolver-time `@kernel` attenuation (`KernelResolver.ts:48-95`); `@endo/module-source ^1.4.1` is a real dep. **Caveat the doc omits:** the FE compartment does **not** `lockdown()` (`PluginCompartment.ts:19-27`) — partial realm isolation. |
| 11 | 4c: Brain "self-declares over a gRPC install, treated as CORE" | **REFUTED (status quo + invariant clash)** | Today the **Head** declares Brain: `CoreInferenceResourceCatalog` lives in `app-observability` (a Head module) and emits `core.inference-runtime` as an OBSERVABLE Resource. There is **no** Brain→Head gRPC install. And a `host-owns-truth` gate already exists (`registry.v1.json:276`: core namespace ⟺ CORE provenance) — letting Brain *mint* `core.*` over the wire is exactly what it forbids. See §9.5. |
| 12 | §5: a `contribution-surface` register + gate (the prevention keystone) | **DOES NOT EXIST** | No `governance/contribution-surfaces.v1.json`, no `gates/contribution-surface/`. The *kernel* and three sibling registers (`execution-surface`, `operation-surface`) plus `consumer-presence`/`runtime-witness`/`host-owns-truth` exist — the §5 keystone itself is purely proposed. This is the one place §5's "add one register+gate" is honest about being net-new. |

**Net:** of §4's five facets, **4d is ~80% built and partly misframed**, **4b is half-built and tier-confused**, **4c is split** (shape projection genuinely pending; Brain "self-declare" both unbuilt *and* in tension with an existing invariant), **4a is the real frontier** (and underspecified — §9.2), **4e/§5 is the honest net-new** (one register + gate). The doc's own Appendix A ledger is closer to reality than §4's prose: §4 reads as greenfield where the substrate is mostly standing.

### 9.2 Critique 1 — "deliver to the altitude your trust permits" presupposes a backend attenuation substrate the doc never names (4a, issue 3)

This is the most important correction. §4a resolves issue 3 by analogy: a user plugin that declares backend behavior is "delivered to the Head the *same way MCP already is*," and "the trust-correct form of *untrusted* backend behavior **is** the out-of-process sandbox MCP already uses … isolation proportional to trust." **The analogy is structurally wrong, and feasibility-disregard does not save it — it is a category error, not a cost.**

MCP-style out-of-process isolation is *isolation for stability and protocol cleanliness*, **not capability attenuation**. The external precedent is unambiguous: VS Code's extension host is a separate process, yet "the extension host has the same permissions as VS Code itself … an extension can read and write files, make network requests, run external processes" ([VS Code extension runtime security](https://code.visualstudio.com/docs/configure/extensions/extension-runtime-security)). A subprocess inherits **ambient authority** — the OS credentials of the account that spawned it ([capability-based security overview](https://css.csail.mit.edu/6.858/2017/lec/l06-capsicum.txt)). True attenuation requires *eliminating* ambient authority — Capsicum's `cap_enter()`, or, on JustSearch's FE, the SES Compartment whose endowment set is the *only* authority the plugin gets (`KernelResolver.ts`). The backend has **no analogue**. So "deliver untrusted backend behavior into an MCP-style subprocess" delivers it with **full host authority** — the opposite of "isolation proportional to trust."

The doc half-sees this (§7: "punching a hole in the Head" is named as the wrong target) but then mislabels the right target. The corrected structure:

- **The genuinely-new structural element §7 names is actually *two* elements, not one.** §7 says the one new thing is "the cross-process/cross-altitude registry protocol … how a declaration propagates its dispatch intent + gate-verdict requirement to the executing altitude." That is the *propagation* half, and it is the easy half. The hard, also-new half is a **backend capability-attenuation substrate** — the thing that lets the *executing* altitude run delivered code under authority bounded by the declaration's trust tier. Propagation without an enforcing remote endpoint is a request the remote can ignore. The FE already has this (SES + `@kernel`); the backend does not, and §4a presumes it exists ("the sandbox MCP already is") when it does not.
- **Therefore the correct end-state for issue 3 is most likely *not* "a backend loader for user plugins" at all.** The trust-correct options are: (a) user backend behavior stays *out-of-process with no ambient authority* — a real ocap subprocess (Capsicum/seccomp/WASI-component model, not "MCP-style"); or (b) backend behavior is admissible **only** through a *trust ceremony* — either CORE/reviewed contributions, or the existing MCP path where **the user has explicitly installed and thereby trusted** that server. Reframed: MCP is not the *isolation* exemplar, it is the *trust-delegation* exemplar — its safety comes from the user's install decision, not from the subprocess boundary. Under that reading, "issue 3" partly dissolves the way the doc wants, but for a different reason than it states, and the residual ("untrusted user code at backend altitude") is a **large unbuilt sandbox substrate**, which the theory should name as a first-class facet rather than fold into "reify Delivery."

WASM component-model sandboxing for exactly this "componentized, capability-scoped extension" problem is now an active pattern ([Cosmonic on capability sandboxing for agentic systems](https://blog.cosmonic.com/engineering/aiops-and-agentic-ai-security-in-a-componentized-world/)) and is a more honest reference point for backend-altitude untrusted delivery than MCP.

### 9.3 Critique 2 — 4b conflates a *build-time* gate with a *run-time* witness; "witnessed for free" is unreachable by the gate (4b, issue 4)

§4b's promise: make delivery an attested runtime event so "a contribution is witnessed because it was delivered … a runtime MCP tool is witnessed for free." The live experiment shows why the *gate* can never deliver this promise, and that the framing hides a tier error:

- The `runtime-witness` gate runs at **build/CI time**, over a snapshot produced by a **unit test** from **static catalogs** (`RegistrySnapshotExporterTest` → `RegistrySnapshotExporter.java:61 new CoreOperationCatalog()`). At that moment **no MCP host is connected and no running instance exists** — so a runtime-only MCP tool is invisible *by nature*, not by a fixable blindness. Promoting the snapshot to a "live attestation" does not help the build gate, because the build gate has no live instance to read.
- What §4b actually describes — a `/api/registry/witness` endpoint + a live subscriber — is a **runtime observability assertion** on a *running* instance. That is a legitimate and good thing, but it is a **different authority at a different tier** than the build gate. The doc collapses them under one word ("witness") and one prevention bullet (§5: "a declaration kind with no live-attestation consumer fails the build"), which is not satisfiable: the build cannot observe live delivery.

**Correction — split the witness into two named tiers, and right-size each:**

1. **Build-tier witness (declared coverage).** Keep the existing gate, but fix the *real* defect it has: `RegistrySnapshotExporter` reconstructs static catalogs instead of reading the **live composed `ContributionRegistry`**. The minimal correct change is to export the snapshot from the *actual composer state* (still at build/test time, but from the real registry rather than `new CoreOperationCatalog()`), so the build-tier coverage is faithful to what the registry composes. This is far smaller than §4b's "first-class attested event + endpoint + subscriber," and it is the part the gate can actually enforce.
2. **Run-tier witness (live delivery).** The `/api/registry/witness` endpoint + subscriber is the *runtime* half — surfaced as a **health/observability** assertion (it belongs with the `/api/health` + observability lineage, and the 550 lifecycle record's correlation-id federation §7 already flags as the host). Its consumer is a *running-instance* check (a smoke / e2e / live probe), **not** a CI gate. Runtime-only MCP tools are witnessed *here*, never in the build gate.

Naming the two tiers separately also resolves §7's open "attestation cardinality" question cleanly: the build tier is per-*declaration-kind* (coarse, cheap, what a gate needs); the run tier is per-*delivery-to-consumer-per-session* (precise, sampled, what observability wants). They were only in tension because they were one word.

### 9.4 Critique 3 — 4d's "new `SourceTier`s" inverts the lattice's central virtue; the work is issuance, not enumeration

§4d says new `SourceTier`s (`PLUGIN`, `MCP`, `WORKFLOW_NODE`, `BRAIN`) "flow through the *same* `IntentGateEvaluator`." Read literally this is a **category error that the codebase already avoids**. `TrustLattice`'s entire stated virtue is that "adding a dimension cannot silently weaken a gate — combinations you don't enumerate deny by default" (`TrustLattice.java:23`). `SourceTier` is the *abstract trust altitude* (`TRUSTED/MEDIUM/UNTRUSTED`); the *concrete* sources (MCP, workflow) are already modelled as `IntentSource` **catalog rows** carrying a `transport` + a tier mapping (`CoreIntentSourceCatalog.java:63,57`, both → `UNTRUSTED`). That is exactly right: a new contribution source is **a new catalog row** (new provenance/transport, mapped to an existing tier), **not a new enum constant**. Minting `PLUGIN`/`MCP`/`BRAIN` as `SourceTier` values would fork the very axis the lattice keeps closed.

**Correction:** restate 4d as "new contribution sources enter as `IntentSource` rows (transport + provenance → existing tier); the lattice gains coordinates only when a genuinely new *trust dimension* appears (which `TrustLattice` already supports without forking)." And reclassify the Grant half (per ledger #7) from "generalize the capsule" to "**finish** the already-built Grant: build the `CapabilityFamily` issuance ceremony + its gate consumer (today `Grant.java:115 authorizes()` is fail-closed)." 4d is the *most finished* facet, not an extension to design.

### 9.5 Critique 4 — 4c's "Brain self-declares as CORE over gRPC" collides with the `host-owns-truth` invariant

§4c wants Brain to "stop being a process the Head declares *about*, and become a `ContributionRegistry` participant that self-declares … treated as a `CORE`/trusted contributor … over the same `install(Installation)` channel." But the `host-owns-truth` gate already encodes the opposite invariant (`registry.v1.json:276`; kernel decision 05): **`core.*` provenance is the Head's to assert, and a non-core owner may not mint a `core.*` key** (`ContributionComposer` Trust axis, `:23-34`). A Brain→Head gRPC install asserting `core.inference-runtime` would be a *remote process minting a core key* — precisely the bypass that gate exists to make unrepresentable. This is why the status quo (`CoreInferenceResourceCatalog` in the *Head's* `app-observability`, Head-declares-about-Brain) is not an accident to "fix"; it is the invariant-correct shape.

**Correction:** 4c has two trust-correct end-states, and the doc should pick one explicitly: (a) **Head continues to declare Brain** (status quo), and "Brain joins the registry" means the Head projects Brain's self-reported capabilities under its *own* CORE authority after verifying them — "self-report, host-attests"; or (b) Brain self-declares under a **`brain.*` namespace with a distinct `BRAIN` provenance/tier**, never `core.*`, so the install carries no core-minting. Option (a) is the smaller, invariant-preserving move and matches what's built; option (b) is the larger structural claim and should be stated as such, not slipped in as "treated as CORE." Either way, "self-declares as CORE over gRPC" as written is structurally inadmissible under the project's own gate.

### 9.6 Critique 5 — the §1 drift evidence overstates the fork; the real WS7 target is narrower (and that *strengthens* the case)

§1 lists "`HealthEvent` is defined four times" beside the `AuditPolicy 'FULL'` bug as twin evidence of shape drift. The verification shows these are not the same thing: `HealthEvent` is a **single Java authority with three generated projections** — i.e. the *desired* outcome of 4c, used as if it were the disease. Only `AuditPolicy` was a true hand-mirror fork that shipped a bug. The genuinely un-projected registry shapes are `Operation`/`Resource`/`Presentation`, still hand-authored in `types/registry.ts` (its own header admits the generator "doesn't currently emit these").

This is a *friendly* correction: it makes 4c's case **tighter**, not weaker. The honest framing is "the enum vocabulary is already projected (`registry-enums.generated.ts`, 6 enums, byte-checked) and `HealthEvent` proves the pattern works end-to-end; the residual fork is exactly the **nested `Declaration`/`Operation`/`Resource` record shapes**, which is what 4c should register as the next `contract-surfaces.v1.json` instance." Citing the model outcome as drift muddies the one place the substrate is already doing the right thing.

### 9.7 What the theory gets right (affirmed under scrutiny)

- **The AHA guardrail (§3) is correct and well-defended.** Transport-polymorphism (one route+gate+attest seam, an adapter per transport) over a collapsed `deliver()` matches how MCP itself layers one JSON-RPC protocol over swappable stdio/HTTP transports ([MCP transports](https://modelcontextprotocol.io/docs/learn/architecture)) and how VS Code keeps one extension API over multiple host processes. Keeping the four transports plural while unifying the seam is the right cut.
- **The trust-at-the-seam exemplar (§1, 4d) is real and verified-good.** "One verdict, one instance, computed at the chokepoint, obeyed by FE and preview alike" is fully built and is the correct template. §7's "resist re-opening issue 1" is good advice.
- **The meta-substrate framing (4e) is the doc's most durable contribution** and survives all five critiques: *canonical source → governed projections → coverage gate*, hosted on the 530 kernel, with 560 (behavior) and 564 (shape) as siblings. The corrections above all *operate within* this frame rather than against it.
- **§7's "identify the one new structural element" instinct is right** — it just under-counts (the backend attenuation substrate is a second new element, §9.2) and the witness needs splitting (§9.3).

### 9.8 Recommended amendments to §4 (concrete, no new tempdoc)

1. **4a** — add a named facet: *backend capability-attenuation substrate* as the precondition for any non-browser untrusted delivery; replace "the sandbox MCP already is" with "an ambient-authority-free ocap subprocess (WASI-component / seccomp), or — for *trusted* sources — the user-install ceremony MCP actually relies on." Mark trust-proportional *backend* delivery as net-new, peer to the §5 gate, not a reuse.
2. **4b** — split into build-tier witness (fix `RegistrySnapshotExporter` to export from the live composer, not `new CoreOperationCatalog()`) and run-tier witness (`/api/registry/witness` as observability, not a CI gate). Drop "witnessed for free" for runtime-only tools at the *gate* tier.
3. **4d** — reclassify from "extend the lattice with new `SourceTier`s" to "new sources are `IntentSource` rows; **finish** the already-built `Grant` (`CapabilityFamily` issuance + consumer)."
4. **4c** — pick (a) host-attests or (b) `brain.*`/`BRAIN`-tier; delete "treated as CORE over gRPC." Re-aim the shape-projection target at `Operation`/`Resource`/`Presentation`, and stop citing `HealthEvent` as drift.
5. **§5** — the `contribution-surface` register+gate is the honest net-new keystone; its four invariants are sound, but invariant #3 ("no live-attestation consumer fails the build") must be reworded to "no *declared* consumer fails the build" (the build cannot see live attestation, per §9.3).

### 9.9 Open decisions for the maintainer

- **Is issue 3 a feature to build or a frame to retire?** §9.2 argues the trust-correct answer may be "backend behavior requires a trust ceremony; untrusted user code does not reach backend altitude," which would make 4a's frontier *smaller* (browser SES + MCP-trust-delegation cover most of it) and reclassify "true backend ocap sandbox" as a separate, possibly-YAGNI substrate. Decision needed before 4a is scoped.
- **Does 4c want host-attests (small) or `brain.*` self-declare (large)?** These are different-sized commitments hiding under one sentence.
- **Should the `contribution-surface` gate ship before any of 4a–4d?** It is the only net-new prevention keystone and could lock in the *current* good shape (enum projection, trust seam, host-owns-truth) against regression while the larger facets are still theory — i.e. the highest-leverage, lowest-risk next step, consistent with the doc's own "prevent the bypass" thesis.

---

## 10. Cross-doc dependency assessment (2026-06-03): do the later tempdocs (561–564) move 560's baseline?

> **Why this section.** Before any further 560 theorization or build, the tempdocs opened
> *after* 560 (561 LLM-interaction surface, 562 installer, 563 FE-rewrite-impact, 564
> contract-projection) were read against current `main` to decide which **change the
> current-state facts 560's §4 rests on** and therefore warrant codebase investigation
> first. **Method note:** the working base was refreshed from a 69-commit-stale point
> (branched at the 559 merge `98e280cb2`, *before* the entire 564 wave + the 561 rewrite)
> to current `main`. §9's verification survives the refresh — its Explore agents read the
> *main checkout* (`F:\JustSearch`, then already current) via absolute paths, so §9's
> 564-state claims are valid against current `main`. (A worktree-population quirk left
> `modules/**` un-materialized on disk in this worktree; code reads here use the main
> checkout's absolute paths. Logged for whoever builds next.)

### 10.1 Per-doc verdict

| Doc | Relevance to 560 | Warrants investigation first? |
|---|---|---|
| **564** contract-projection | **Direct** — 560 §4c "adopts 564"; §4b/§5's witness is 564's deferred 481 Pass-3 | **YES** — and largely done in §10.2–10.3 below |
| **561** LLM-interaction surface | **Direct** — 560 §4a delivers tools *into* 561's one surface; 4d's trust-seam exemplar shipped *in* 561 | **YES (light)** — 561's own §8 de-risk already verified the surface state; 560 needs to *align*, done in §10.4 |
| **563** FE-rewrite backend impact | **Contextual** — its §9.2 names the FE↔backend contract boundary the single biggest friction (HIGH), corroborating *why* 4c matters | **No** — historical analysis, no new infrastructure 560 depends on |
| **562** installer build pipeline | **None** — zero plugin/contribution/registry/trust content (grep: 0 hits) | **No** |

### 10.2 564 finding A — the mechanism shipped; the registry-declaration shape is a *clean, available* 4c win (better than §9.6 implied)

564 shipped the `record → JSON-Schema → {TS, Zod}` substrate (`scripts/codegen/gen-wire-schema-types.mjs`, the `contract-surfaces.v1.json` register, the `contract-projection` gate) and **retired `wire-types.ts`** (confirmed absent in current `main`; the "a5 restored wire-types.ts" in `564-impl-progress.md` is *itself stale* — an intermediate decision the 2026-06-03 final superseded). The 16 generated surfaces are search/status/inference/packs/browse/folders/policy/indexed-roots/search-trace/health/agent-sessions/history/timeseries/failed-jobs — **the registry `Declaration` shape is NOT among them.** So 4c's target is genuinely un-done and the registry is still hand-mirrored in `types/registry.ts` (confirming §9.6).

**New, decisive fact:** the registry declaration records are **clean for projection**. `Operation`/`Resource`/`Prompt`/`Presentation` are records over a **sealed** `RegistryEntry` hierarchy explicitly engineered for schema emission — `RegistryEntry.java:18` produces "discriminated `anyOf` schemas with `const`", and `ConsumerHook`/`ConfirmStrategy`/`RequiredCapability`/`AvailabilityExpression`/`NamespacedId` are all sealed/typed. **None carry the `Map<String,Object>` / `google.protobuf.Value` faithfulness blocker** that gated the health body and `KnowledgeSearchResponse` (564's "4a-blocked" set). → **4c is a clean, available win** — register `Operation`/`Resource`/`Declaration` as `contract-surfaces.v1.json` instances and generate; no architecture decision blocks it. This *upgrades* §9.6: the residual fork is not just narrow, it is **unblocked**.

### 10.3 564 finding B — 560 §4b/§5 (witness) IS 564's deferred 481 Pass-3; they cannot be scoped independently

The `Map<String,Object>` blocker *does* bite the **agent tool/result wire** — `OperationResult.structuredData`/`errorDetails` and `AgentToolEmitter.emit` return untyped `List<Map<String,Object>>`. This is exactly the surface 564 "logged as record-less (untyped `Map<String,Object>` backend)" and **deferred by design**. Consequence for 560: any facet that projects *the agent tool list itself* (a "one declaration incl. agent tools to FE+Head+Worker+Brain" reading of §4c, or §4b's witness-over-delivered-agent-tools) inherits 564's open faithfulness question — it is **not** a clean win like the registry declaration shape.

More importantly: **564 explicitly carves out a "481 Pass-3" slice — `NonEmpty<ConsumerHook>` enforcement + runtime-witness — and parks it as blocked on four §E.2.1 design decisions** (agent-consumer witness shape; per-actor deadline policy; SliceCatalog referential-integrity; runtime-traffic-counter). **That slice is the same work as 560 §4b (live witness) and §5 (the runtime-witness half of the contribution-surface gate).** The `runtime-witness` gate that already exists (§9.1 #9) is the *build-tier* half; the *run-tier* witness 560 §4b proposes is precisely 481 Pass-3's parked half. → **560 §4b/§5 cannot be theorized or scoped in isolation** — they are entangled with 564/481 Pass-3 and must either consume or co-decide those four §E.2.1 questions. This is the strongest "investigate before proceeding" signal in the batch.

### 10.4 561 findings — the plugin-declaration ceiling is shared, and the gate template already exists

561's §8 de-risk pass (2026-06-03 — methodologically a twin of §9) surfaced two facts that bind 560:

- **The plugin-declaration gap is the *same* ceiling on both docs.** 561 found `PluginSurfaceContribution` cannot declare `conversationShapes`, so a *plugin* interaction surface is **gate-invisible** — "the same undeclared-fork limit the data tier has." 560 §5 hits the identical wall: a plugin contribution that can't declare its altitude / consumers / shapes in a *gate-visible* way escapes the contribution-surface gate. → **One declaration-completeness fix (give `PluginContribution`/`PluginSurfaceContribution` the gate-visible declaration fields — altitude, consumers, shapes) serves BOTH the 561 interaction-surface gate and the 560 contribution-surface gate.** They should be designed together, not twice. (This also re-grounds §9.5: 561 already adopts the `vendor.x.*` namespace for plugin-owned shapes — the `brain.*`/non-`core.*` namespacing §9.5 recommends is the established pattern.)
- **The gate template is built.** 561 reports its interaction-surface gate is "a near-clone of the `operation-surface` enforcer, keyed on placement×audience×consumes," writable today against the served `/api/registry/surfaces` (static Java `CoreSurfaceCatalog`). 560 §5's `contribution-surface` gate is the *same* near-clone shape over the contribution registry — so §5's "one register + gate" is lower-risk than §9.1 #12's "purely proposed" implies: the **enforcer pattern, the register format, and a live sibling all exist**; only the contribution-specific rules are net-new.

One correction this forces on 560: §4e lists **presentation (559)** as a built "instance" of the meta-substrate. 561 §8 verified **559's composition engine is unbuilt design theory** (`SurfaceFactory` mints top-level surfaces only; interiors are hand-authored Lit). 4e's claim is fine *as a meta-pattern* but should not be read as "presentation is a shipped instance" — it is the *least*-built sibling.

### 10.5 Net recommendation

- **4c (shape projection) is the cleanest, most independent next step** — the registry declaration records are projectable today via 564's shipped mechanism with no blocker (§10.2). It can proceed without waiting on anyone.
- **4b/§5 (witness + contribution-surface gate) must NOT proceed before reconciling with 564's 481 Pass-3** — its four §E.2.1 decisions are the same decisions, and the build-tier/run-tier split (§9.3) must be agreed jointly. Read §E.2.1 first.
- **The plugin-declaration completeness fix is a shared 560×561 precondition** — scope it once, for both gates (§10.4).
- **562/563 need no further investigation** for 560.
- **4a (backend attenuation) remains the largest open frontier** (§9.2) and is untouched by 561–564 — none of them build a backend capability-attenuation substrate, so §9.2's gap stands unmitigated.

---

## 11. Confidence / de-risk findings (2026-06-03): hypotheses converted to evidence

> **What this section is.** §9/§10 reached several conclusions by static reasoning —
> `audit-without-test` hypotheses. This section executes a de-risk plan that turns each
> into a runnable signal: one **throwaway codegen spike** (run, inspected, reverted —
> tree clean), targeted **source reads**, and verbatim **design-decision extraction**. Two
> live **dev-stack experiments** (DR-C trust-seam reproduction, DR-D witness-blindness)
> remain **pending stack access** (the stack is held by another session). The findings
> **correct three earlier claims** (§9.3, §10.2, §9.1 #7) and sharpen two more.

### 11.1 Updated per-facet confidence (evidence-backed)

| Facet | Was | Now | What moved it |
|---|---|---|---|
| 4c shape projection | Med-High ("clean win") | **Med — feasible, demonstrated, but NOT a drop-in** | Spike produced a real faithful TS+Zod, *and* exposed a real migration cost (§11.3). Feasibility ↑, "cheap/clean" ↓. |
| 4d trust extension | Med-High | **High** | Durable-grant issuance/consumption/revocation already exist (§11.5); the gate hold was reproduced firsthand (§11.6 DR-C); remaining work is narrow + named. |
| 4b / §5 witness + gate | Low | **Low, now precisely bounded** | The build-tier witness is *structurally* static (§11.2); a wrong "easy fix" was removed; four named decisions block it. Understanding ↑, difficulty confirmed. |
| 4a DeliveryChannel + attenuation | Low | **Low, sharper scope** | One adapter does NOT cleanly fit the Worker streaming/proto path (§11.4); backend attenuation confirmed greenfield. |

### 11.2 DR-A — §9.3 CORRECTED: the build-tier witness is structurally static (not a "small fix")

§9.3 proposed a "minimal correct change: make `RegistrySnapshotExporter` export from the live composed registry instead of `new CoreOperationCatalog()`." **That fix is not available.** `RegistrySnapshotExporter.writeDefault()` is invoked by `RegistrySnapshotExporterTest` during `:modules:app-services:test` — i.e. at **build time, before the Head process or the live `ContributionRegistry` exist**. There is no live composer to read at that scope; the exporter *reconstructs* static catalogs because that is the only thing available pre-boot. So the build-tier/run-tier split (§9.3) is even more fundamental than stated: **the build gate can only ever witness *declared* contributions; runtime-only delivery (MCP tools, late installs) is invisible to it by construction, not by a fixable blindness.** §5's prevention invariant #3 must read "no *declared* consumer fails the build" (already noted in §9.8 item 5); the runtime half is a separate observability tier (DR-D will demonstrate it).

**The four §E.2.1 decisions** that 564 parked for "481 Pass-3" — and that block 560 §4b/§5 — verbatim from `docs/decisions/0039-contract-substrate.md (historical: the unified-substrate-theory slice; retired to git)`:

1. **Agent-consumer witness shape** — agents build prompts at agent-loop time, not over the SSE wire, so *which* prompt-build phase is the witness, *how* the `ConsumerHook` id resolves against the tool-binding registry, and *what* honest failure mode applies if the tool is never invoked in the test session are all unspecified. **Blocks §4b and §5.**
2. **Per-actor deadline policy** — RFC-9745/k8s grace windows were calibrated for *uncoordinated* consumers; JustSearch's compiled-in FE/agent ships in lockstep (deadline = overkill) while plugins are independent (deadline = appropriate). The per-actor model is unspecified. **Blocks §5.**
3. **SliceCatalog referential-integrity** — validating `Promised.sliceId` against `slices/*.md` frontmatter is itself a new substrate primitive (what counts as "slice exists"; how status transitions graduate Promised→Realized). **Blocks §5.**
4. **Runtime traffic counter** — "a Realized hook received ≥1 delivery in a representative session" could be a recursive `RegistryEntry`-shaped `TrafficCounter` primitive or a procedural CI check; the agent-consumer case breaks the "no new primitive needed" assumption. **Blocks §4b and §5.**

→ **560 §4b/§5 cannot be scoped before these four are decided.** Decisions 1 & 4 are architectural (witness shape, traffic mechanism); 2 & 3 are policy/process. This is the batch's strongest "do not proceed yet" signal.

### 11.3 DR-B — §10.2 CORRECTED: 4c is feasible-by-the-proven-mechanism, but NOT a drop-in (real, bounded migration cost)

A throwaway spike added `Operation` (schema `SSOT/schemas/operation.v1.json`, already emitted by `app-api`'s `SubstrateSchemaGenTest`) as a `gen-wire-schema-types.mjs` TARGET, ran the generator, inspected the output, and reverted (tree clean). **The mechanism works** — a faithful 213-line `operation.ts` with TS types + runtime Zod was produced:

- Sealed `ConfirmStrategy` → faithful inline discriminated union (`{kind:"NONE"} | {kind:"INLINE"} | {confirmTextKey?;kind:"TYPED"}` + matching `z.union([z.literal…])`); `AuditPolicy` enum correct (`"NONE"|"METADATA_ONLY"|"FULL_PAYLOAD"`); `ConsumerHook.Realized → kind:"realized"`.

**But two real costs §10.2's "clean win" glossed:**

1. **Named nested types are inlined, not emitted.** `app-api`'s victools config emits only `$ref`s + enums as named `$defs`; nested *records/sealed-types* (`OperationPolicy`, `ConfirmStrategy`, `Presentation`, `RequiredCapability`, `Interface`, …) are **inlined anonymously** under `Operation`. The hand-authored `types/registry.ts` exposes ~25 *named* types that **29 FE files import by name**. So the generated projection is **not a drop-in replacement** — it requires either (a) **configuring victools to promote nested record/sealed types to named `$defs`** (so the generator yields the named types), or (b) refactoring the ~29 importers off the named types. Bounded, but real — not free.
2. **`AvailabilityExpression` is lossy — confirmed concretely.** The spike emitted `availability.expression` as `Record<string, unknown>` / `z.record(z.string(), z.unknown())` — the sealed AST is gone, because `AvailabilityExpression.java` carries **no `@JsonTypeInfo`** (unlike `ConfirmStrategy`/`RequiredCapability`). Fix: add `@JsonTypeInfo` so victools emits the discriminated `anyOf`, **or** keep that one field hand-authored.

→ **4c revised verdict:** the *path* is proven and low-risk per surface, but the "clean, available win" framing is downgraded — landing it as a true single-authority replacement of `types/registry.ts` carries a **named prerequisite** (emitter-config promotion of nested types **or** a ~29-file FE refactor) plus a **one-field `@JsonTypeInfo` fix**. It remains the most independent next step (no cross-doc blocker), just not a freebie.

### 11.4 DR-E — §4a's DeliveryChannel adapter must be richer than "deliver→result"; backend attenuation confirmed greenfield

Tracing all four transports: MCP (`McpToolHandler.execute → client.callTool`), in-process (`PingBackendHandler.execute`), and FE (`OperationsController` HTTP → `dispatcher.execute`) all return **`OperationResult` at their boundary** — a clean uniform seam. But the **Worker/gRPC** path frays it: the real boundary is two hops deep (`IngestOperationHandler → IngestTool → …Callback → RemoteKnowledgeClient`), returns **proto** (`BatchResponse`/`ScanRootProgress`, *not* `OperationResult`), and **`scanRoot` is server-streaming** while the others are unary. A naive `deliver(declaration, invocation) → OperationResult` cannot capture streaming + proto-conversion + the callback indirection. → **§4a's AHA-guardrail "one seam, an adapter per altitude" survives, but the adapter contract is richer than the doc implies** — it must admit streaming results and per-transport result conversion, not a single `→ result`. This upgrades §9.1 #3's "2-hop variant" flag to a structural design constraint on the DeliveryChannel interface.

Backend attenuation (§9.2): **confirmed greenfield.** `StdioMcpTransport.java:74-82` is a bare `new ProcessBuilder(command); pb.environment().putAll(extraEnv); pb.start()` — **no** `environment().clear()`, **no** `directory()` jail, **no** privilege drop, **no** seccomp/pledge/chroot. The MCP subprocess inherits the Head's full ambient authority. §9.2's "isolation ≠ attenuation; there is no backend capability-attenuation substrate" stands with quoted code.

### 11.5 DR-F — §9.1 #7 REFINED: the durable-grant *issuance ceremony already exists*; only the `CapabilityFamily` variant + persistence remain

§9.1 #7 said 4d's missing piece was "the issuance ceremony + the `CapabilityFamily` consumer." Half of that is already built:

- **`DurableGrantStore`** exists (in-memory, keyed by `(operationId, sourceTier)`) with `grantAllowAlways` / `isAllowed` / `revoke` / `revokeNonUser` (the Hard Stop), all audited via `ActionEvent`.
- **Issuance is wired:** `AuthorizationController` `/api/authorizations/approve` with `{allowAlways:true}` calls `durableGrantStore.grantAllowAlways(...)`. It is consumed at `OperationExecutorImpl.enforceTrustLattice` (~:598-602) *before* the capsule check.

So the durable "allow-always" grant (the `BoundAction`-class, per-operation×tier) is **end-to-end live** (modulo disk persistence). The genuinely-remaining 4d work is **specifically the `CapabilityFamily` (wider-than-one-operation) branch**, whose `authorizes()` is fail-closed pending: (i) a `capabilityFamily` field on `OperationPolicy` + an `op→family` resolver [small]; (ii) disk persistence mirroring `FileConversationStore` [large]; (iii) a grant-management surface (list/revoke) [medium]. → 4d confidence ↑ to High: the trust seam *and its durable-grant ceremony* are built; what's left is a scoped, well-understood extension, not a new mechanism.

### 11.6 DR-C + DR-D — both experiments RUN (live, fresh stack, 2026-06-03)

Stack taken over (`warn`) and model activated (`cuda12`, 8.7s); both experiments executed firsthand.

- **DR-C (trust-seam reproduction) — CONFIRMED firsthand.** `justsearch_dev_agent_chat {autoApprove:false}` with a "ingest the folder F:\JustSearch\docs" prompt: the agent emitted `core_ingest_files {paths:["F:\\JustSearch\\docs"]}` with `risk:"medium"`, and the call came back **`approved:false`, `success:null`, `output:null`** — held behind the confirmation gate, not executed. The run then *timed out* precisely because no approval was given, proving execution blocks on the gate (Assist mode). This reproduces Appendix A's headline result with my own run (the prior one was a subagent's) — **the trust seam 4d extends is verified-good, firsthand. 4d → High.**

- **DR-D (witness-blindness) — DEMONSTRATED empirically, and without even needing MCP.** The MCP-injection route was blocked (the dev `start` tool exposes no env/sysprop; `EnvRegistry.MCP_HOST_CONFIG` is env/sysprop-only with no default file; there is no runtime MCP-connect endpoint). But a *better* instance was already live: `GET /api/chat/agent/tools` returns 7 tools including **`core_workflow_demo_compose`**, which is composed at runtime from `CoreWorkflowCatalog.java` — **not** in the static `CoreOperationCatalog`/`AgentToolsOperationCatalog` the witness is built from. Generating the actual snapshot (`:modules:app-services:test --tests *RegistrySnapshotExporterTest` → `tmp/consumer-presence/registry-snapshot.json`, 14 entries) and diffing against the 7 live tools:

  | live agent tool | in build-time witness snapshot? |
  |---|---|
  | core_search_index, core_browse_folders, core_ingest_files, core_file_operations, core_navigate_to_surface, core_remember | **WITNESSED** (6/7) |
  | **core_workflow_demo_compose** | **BLIND** — absent; the snapshot never even mentions it |

  → A runtime-composed, agent-invocable **core** contribution is **live but invisible to the build-time witness** — a concrete, in-tree instance of "Issue 4," demonstrated on the default stack (no external MCP required). It is currently a *demo* op (so the gap is benign today), but the mechanism is exactly the one §4b/§5 must close, and it would apply identically to any MCP or plugin contribution. This empirically confirms §11.2 / the corrected §9.3: **the build-time witness cannot see runtime-composed delivery — by construction, not by a fixable blindness.**

### 11.7 Net confidence delta

The de-risk pass **raised** confidence on 4d (the durable-grant ceremony is built) and on 4c's *mechanism* (demonstrated), **lowered** confidence on 4c's *cost* (a named migration, not a freebie), and **confirmed-as-hard** 4b/§5 (structurally build-static; four named blockers) and 4a (richer adapter + greenfield backend attenuation). No facet got *easier than believed*; two got *better understood*, two got *correctly harder*. The §10.5 implementation order is unchanged, but **4c now carries a named prerequisite** and **4b/§5 are explicitly gated on the four §E.2.1 decisions** — both now de-risked rather than assumed.

---

## 12. Scoping decision (2026-06-03): implement 4c only; defer/skip the rest

A calibration pass (git history of comparable implemented tempdocs — 543/549/530/516, plus
560's own already-built "noun" at ~+10k code LOC) established that the **full** 560 thesis is
**XL — three-to-four tempdocs' worth of work**, exceeding the largest recent substrate (543,
~15k code LOC), driven mostly by 4a's greenfield backend-attenuation substrate. But the
substrate's **load-bearing parts are already built** (registration, trust gating, FE plugin
loading — Appendix A), and the remaining "verb" facets are prevention / cleanliness /
infrastructure **for a third-party plugin ecosystem that does not yet exist** (only CORE + MCP
flow through today). **Decision: implement 4c only; defer or skip the rest.**

| Facet | Disposition | Reason |
|---|---|---|
| **4a** backend attenuation / issue 3 | **Skip (strongly)** | Greenfield XL *and* dubious value — §9.2: running untrusted user code at backend altitude is a security regression. Backend behavior should require a trust ceremony (MCP/CORE); untrusted user code stays browser-altitude (SES, already live). |
| **4e** one meta-substrate | **Skip (free)** | A lens, not code; already true as a description. |
| **4b / §5** runtime witness + contribution-surface gate | **Defer** | Build-tier witness already exists; the runtime half governs contributions that aren't there yet (only a benign demo op + user-trusted MCP are blind — §11.6). Most expensive to start (blocked on 4 §E.2.1 decisions). No shipped bug → YAGNI, not a deferred *known* defect. |
| **4d** CapabilityFamily grant finish | **Skip (YAGNI)** | Trust seam + per-op "allow-always" durable grants are built/live; CapabilityFamily is a wider convenience, fail-closed, breaks nothing if absent. |
| **4a** DeliveryChannel reification | **Skip** | Implicit altitude works today across all 4 transports; reify only if adding altitudes often. |
| **plugin-declaration completeness** | **Skip with §5** | Only needed to make plugins gate-visible; folds into §5/561 if/when those land. |
| **4c** registry shape projection | **KEEP — sole target** | Closes a **known, already-shipped defect class** (the `AuditPolicy 'FULL'` drift) — the one facet `structural-defects-no-repeat` says *not* to defer; cheapest; no cross-doc blocker. |

**Net:** the near-term implementation of 560 is **4c alone** — making the registry `Declaration`
shape a generated single-authority projection (record→JSON-Schema→{TS,Zod}) that retires the
hand-mirrored `types/registry.ts`. Everything else is correct-design end-state to revisit when a
real plugin ecosystem (or a forcing need for backend-altitude delivery) actually exists.

## 13. 4c implementation-readiness (critical confidence, 2026-06-03)

**Mechanism feasibility: HIGH (demonstrated).** DR-B (§11.3) ran the real generator on the
already-emitted `operation.v1.json` and produced faithful TS+Zod (sealed `ConfirmStrategy`→inline
discriminated union, enums correct incl. `FULL_PAYLOAD`).

**"Clean single-authority replacement of `types/registry.ts`": MED-LOW — five untested unknowns,
any of which can turn an ~M (≈1 week) into an L (≈2–2.5 weeks):**

1. **Emitter-config `$defs` promotion (the crux).** The generated `Operation` **inlines** its
   nested record/sealed types anonymously (only refs+enums become named `$defs`). To match the
   ~25 *named* types `types/registry.ts` exposes, victools must be configured to emit nested
   records/sealed-types as named `$defs`. **Untested**, and the registry schemas are emitted by
   `app-api`'s `SubstrateSchemaGenTest` whose config is **shared with the 16 existing surfaces** —
   a config change could perturb them. *Highest risk.*
2. **FE blast-radius compatibility.** 29 importers of `api/types/registry` — *what* they import
   (named types? the `Operation` interface?) and whether the generated shapes are **structurally
   identical** (field names, optionality, nullability, no lost convenience unions) is **unaudited**.
3. **Gate / parse-boundary fit.** `contract-surfaces.v1.json` expects FE **parse-boundary**
   consumers (`parseWireContract`). The 16 surfaces are fetch-parsed wire *responses*; the registry
   types are consumed differently (the FE builds typed registries from `/api/registry/*`). Whether
   they have a parse boundary the gate can validate is **unverified** — a possible integration
   mismatch needing gate or wiring changes.
4. **`AvailabilityExpression` is a fork, not a free fix.** It emits opaque (`Record<string,unknown>`)
   because it lacks `@JsonTypeInfo`. Adding the annotation **changes the wire shape** (adds a
   discriminator field) — a wire-contract change with its own blast radius — *or* it stays a
   hand-authored carve-out. **Unresolved.**
5. **Enum-path reconciliation.** The 6 registry enums are already generated by a *different* path
   (`registry-enums.generated.ts` via `RegistryEnumsTsGenerationTest`); `gen-wire-schema-types`
   *inlines* enums as string unions. Two generation paths for one shape → drift risk; needs a
   reconcile (generated records reference the generated enum types, not inline copies).

**Honest verdict (pre-probe):** the *mechanism* is proven; the *landing* is not. Four cheap probes
were run to resolve #1–#4 (results in §13.1).

### 13.1 Probe results (2026-06-03) — landing confidence MED-LOW → MED-HIGH

- **#4 `AvailabilityExpression` — ELIMINATED.** The hand-authored FE type is *already*
  `expression?: unknown` (`registry.ts:356`) — it never modeled the AST. The generated
  `Record<string,unknown>` loses nothing; and since the type is **recursive** (`AllOf`/`AnyOf`/`Not`
  self-reference) and the generator throws on cycles, keeping it opaque is *required*, not a
  compromise. No `@JsonTypeInfo`, no wire change, no carve-out. Non-issue.
- **#2 blast radius — MODERATE, not hard.** The nested types (`ConfirmStrategy`/`OperationPolicy`/…)
  are **never imported by name** — inlining them under `Operation` breaks no imports. Only
  `Operation` (17), `Audience` (11), `Resource`/`Provenance` (8 each), `Presentation` (3),
  `OperationCatalog`/`ResourceCatalog` (6 each) are imported standalone. ~34 files total, mostly
  mechanical; the real touches are **11 test fixtures** that hand-construct `Operation`/`Resource`
  literals and **2 catalog clients**.
- **#1 crux — CHARACTERIZED + a surgical path found.** `WireSchemaConfig` (shared by all 16
  surfaces) uses `OptionPreset.PLAIN_JSON` → nested objects **inline**. The only nested types that
  *must* survive as named are `Presentation` + `Provenance` (the two imported standalone). Two
  paths: **(a)** a global victools `DEFINITIONS_FOR_ALL_OBJECTS…` option — promotes them but
  **ripples to all 16 surfaces** (untested, risky); **(b) surgical** — emit `Presentation` +
  `Provenance` as their **own** TARGETs and rely on TS **structural typing** for the inlined fields
  in `Operation`/`Resource`, **avoiding any shared-config change**. Path (b) removes the crux's main
  risk. Residual: structural-typing assignability across the ~34 consumers — only an implementation
  typecheck fully closes it.
- **#3 gate-fit — "needs a parse boundary added" (bounded, net-positive).** The catalog clients use
  `await res.json() as OperationCatalog` with **no runtime validation**; the `contract-projection`
  gate requires declared consumers to import the generated module. Registering cleanly means
  replacing the `as`-casts in `OperationCatalogClient`/`ResourceCatalogClient` with
  `parseWireContract(<generated Zod>, …)` and registering them — which *adds* the runtime validation
  the registry lacks today. Bounded, and an improvement.
- **#5 enum reconciliation — minor.** The 6 enums are already generated (`registry-enums.generated.ts`,
  separate path); standalone enum importers are unaffected; the generated records inline enum unions
  that are structurally compatible. Cosmetic reconcile, not a blocker.

**Refined verdict:** mechanism **HIGH (~90%)**; clean landing **MED-HIGH (~65%)**, up from MED-LOW.
The surgical path (#1b) + the eliminated #4 + the moderate-not-hard #2 lower the risk; the one
residual only an implementation typecheck closes is structural-typing assignability across the ~34
consumers + 11 fixtures. Realistic effort: **M→L, leaning M (~1–1.5 weeks, ~2–4k code LOC)** — the
work is ~5–6 new generated TARGETs (operation/resource/prompt/presentation/provenance + the catalog
wrappers), 2 catalog-client parse boundaries, ~11 fixture touch-ups, and the register/gate wiring.

### 13.2 Planning correction (2026-06-03): schema ≠ wire for `Operation` — DR-B validated the wrong thing

Implementation planning surfaced an error in §11.3/§13.1: **DR-B generated `operation.ts` from the
record schema `operation.v1.json` and called it "faithful" — but never checked the schema against the
LIVE wire.** The victools schema describes the Java *record*; `/api/registry/operations` is served by
`UIOperationEmitter` — a **divergent second projection**. Verified live (`GET /api/registry/operations`
vs `operation.v1.json`):

- wire `policy.inverseOperationId` vs schema `policy.inverseOperationRef` (renamed);
- wire `intf = {errors, inputs:{object}, result, uiHints}` vs schema `intf = {inputs, outputs}` strings;
- schema has `binding`/`rateLimit`/`requiredCapabilities`/`retry`; the wire omits them.

→ **`Operation` is NOT faithfully generatable from today's schema** — a generated Zod would
fail-open-log `[WireContract]` against the live wire, and the type would misname fields. By contrast
**`Resource` schema == wire EXACTLY** (18 keys identical), and `Presentation`/`Provenance` serialize
directly. This is itself a 560-relevant finding: `UIOperationEmitter` is exactly the *second
representation that drifts from the source* that the projection kernel exists to eliminate — the
correct fix (Phase B) is to make the emitter produce a typed `UIOperationView` record (one wire
authority), not to bless a hand schema.

**Corrected 4c confidence:** the clean, faithfully-generatable, browser-validatable cluster is
**`Resource` + `Presentation` + `Provenance` (Phase A — Med-High, ~M)**; **`Operation` (Phase B)** needs
the emitter→record reconciliation first (**Med, ~M on its own**). §13.1's single "~65% landing" is
split accordingly. Implementation plan: land+browser-validate Phase A, then Phase B.

---

## Appendix A — implementation history (condensed; dated)

The first two build waves + a confidence pass + a live re-validation produced the
following verified state. Full narrative (Waves 1–3, de-risk passes #1/#2, and the
empirical field-study / popularity / dogfood appendices) is in this file's git history
before the 2026-06-01 rewrite. The §-references below point at the *prior* revision's
section numbers, preserved here only as a status ledger.

**Status ledger (2026-05-31, badge row updated 2026-06-01):**

| Element | Status |
|---|---|
| Shared declaration base (`Declaration`/`Provenanced`/`ConsumerDeclaring`) | **BUILT** |
| One declaration model FE↔backend (the WS7 / 4c shape projection) | **APPROX** — generated enum mirrors + a projection, not one shared shape; WS7a sized as the "multi-year" build; Brain OBSERVABLE-participating (WS7b: `core.inference-runtime`) |
| One mechanism (catalogs → one registry) | **APPROX** — one composer for runtime contributions; static-catalog collapse SIZED (~15–20 sites + 2-phase boot) |
| `@kernel/*` capability boundary | **PARTIAL** — resolver-time substitution LIVE; ES-import form SIZED (~64 KB `@endo/module-source`) |
| Four substrates, cross-process (`ContributionComposer<K,V>`) | **BUILT** |
| Trust seam → one lattice (the 4d exemplar) | **BUILT** — FE consumes backend `gateBehavior`; second FE authority removed (561) |
| Lattice dimension-extensible (`TrustLattice`, WS6) | **BUILT** — N-dim coordinate map + `DENY` default; 3rd dim proven without forking |
| Typed axis projections / delivery | **PARTIAL → the WS8 / 4a gap** — storage unified; runtime delivery per-axis; WS8a: transports legitimately differ (kept in §3 AHA) |
| `NonEmpty<ConsumerHook>` keystone | **BUILT** (split: unrepresentable for inline kinds, gate for the rest) |
| Runtime-witness (the issue 4 / 4b gap) | **PARTIAL** — static-snapshot GHOST/PHANTOM; verification gap (live `@Tag("ai")` witness unbuilt), **not** a correctness hole (consumer guaranteed by the keystone) |
| One window for the agent (WS5) | **BUILT + LIVE** — core+agent-tools+MCP+workflow merged; `/api/chat/agent/tools` attributed by tier/kind |
| Real first consumer through the real loader | **LIVE** — scaffold loads via the real loader; `@kernel` attenuation fires live |
| Settings trust badge (issue 2) | **FIXED (WS1) — LIVE 2026-06-01** — reads the registration tier; CORE shows `trusted` |

**Live re-validation (2026-06-01) — fresh-built 560 stack + model.** (The first dev-stack
start served a *stale pre-560 jar*, caught by probing a 560-only endpoint before trusting
it — a reusable freshness check; forced `:modules:ui:installDist` + relaunch.) Of the four
user-visible issues that opened this doc:

- **Issue 2 (trust badge) — FIXED, browser-confirmed.** Settings → Plugins shows `core
  v1.0.0` / `trusted`.
- **Issue 1 (agent safety) — fixed behavior DEMONSTRATED** (correcting the de-risk pass's
  "neither confirmed nor refuted"): an LLM `core_ingest_files` (MEDIUM) write was held
  behind a typed-confirmation *Authorize action* gate in Assist mode, not auto-fired
  (Auto-mode auto-fire of MEDIUM is by-design policy; HIGH/destructive never).
- **One window (WS5), undo (WS3), Brain (WS7b)** — all live-confirmed (the merged window
  carries `core_workflow_demo_compose`; the Effect-Journal "Undo all AI actions" banner;
  `core.inference-runtime` + the AI Brain surface).
- **Issue 3 (browser-only) — confirmed NOT fixed** (no backend plugin loader exists;
  backend `Plugin` is only CORE + MCP projections) — **§4a is its correct resolution.**
- **Issue 4 (declared-but-dead) — compile-time closed, live-witness gap** — **§4b is its
  correct resolution.**

Net 2026-06-01: 2 issues demonstrably fixed (badge, agent-safety gate), 1 structurally
fixed with a verification gap (declared-but-dead → 4b), 1 not fixed (browser-only → 4a);
the improvements (one-window, undo, Brain) all live. This theorization (§1–§8) is the
correct end-state those four faces converge on.

## 14. 4c hardening (2026-06-03): the shipped projection's shortcuts, fixed

A critical pass over the *shipped* 4c (§13.1/§13.2 — Resource Phase A + Operation Phase B)
found four code-verified shortcuts, all now resolved. The wire-type authority + Zod stay the
runtime contract; the FE-facing `Resource`/`Operation` remain ergonomic re-views.

**The four issues.**
1. **Weak validation (root cause).** The generated wire schemas were all-optional/nullable —
   `WireSchemaConfig.isNullableOnWire` returned `true` for every reference field, no record used
   `@JsonProperty(required=true)`, and victools never populated `required`. So `parseWireContract`
   validated almost nothing (an entry missing `id`/`presentation` PASSED). This is the known
   564 §7.2 precision gap.
2. **The `consumers.kind` strip was a symptom-patch in the wrong layer** — a brittle recursive
   heuristic in `SubstrateSchemaGenTest` deleting the `@JsonTypeInfo` discriminator the wire
   never carried (the controller's `Map` round-trip erased it).
3. **7 nullability type-lies** — hand-authored `HistoryPolicy`/`Privacy`/`EmissionPolicy`/
   `ConsumerHook` declared fields non-null the wire marked `?|null`, and were still hand-mirrored.
4. **Unsafe casts** — `parseWireContract(...) as unknown as XCatalog` asserted more than the
   all-optional Zod proved.

**The fix (three coordinated changes).**
- **Precision via an opt-in `PreciseWire` marker** + a second victools hook
  (`forFields().withRequiredCheck`). For a `PreciseWire` type, a component is `required` unless
  `@JsonInclude(NON_NULL)` omits it, and non-null unless `Optional<>` (detected from the raw field
  generic type, since victools unwraps it) or a new `@Nullable` marker (`Provenance.identity`,
  `PluginIdentity.signature`, `PolicyView.inverseOperationId` — the present-as-null fields). Opt-in
  so the other ~12 wire surfaces stay **byte-identical** (proven: their content diff is empty).
- **`UIResourceView` + `ConsumerView`.** The Resource wire now has ONE typed authority
  (`UIResourceEmitter` builds `UIResourceView`, mirroring `Resource`'s field types component-for-
  component so serialization is faithful **by construction**, changing only `consumers` → the
  discriminator-free `ConsumerView`). Both wire views use `ConsumerView`, so the consumer schema is
  flat by construction and the brittle `stripRealizedConsumerKind` heuristic is **deleted**.
- **FE derivation.** `HistoryPolicy`/`Privacy`/`EmissionPolicy`/`ConsumerHook` now DERIVE from the
  precise generated wire (no hand-mirror, no type-lies); `Tighten<>` deleted. Fields the Operation
  wire genuinely omits or sends present-as-null (`identity`/`iconHint`/`category`/`emissionPolicy`/
  `inverseOperationId`) are optional in the *shared ergonomic view* (a safe underclaim — the
  per-surface Zod still validates each precisely); the resource cast collapses to a single `as`
  (the operation cast keeps `as unknown as` because `z.unknown()` infers the opaque `intf` fields
  optional, diverging from `OperationInterface`).

**Verification (all tiers green).** `build -x test` ✅; app-agent-api / app-api / ui
`RegistryControllerTest` ✅; app-services ✅ (sole failure is the pre-existing `ValidatorRunnerTest`
`core.remember` handler-binding gap, observations.md #323); new `UIResourceViewConformanceTest`
proves the view reproduces the raw-record wire across every field shape; FE typecheck 0 errors +
2385 unit tests ✅; gates `contract-projection` / `wire-schema-types-regen` /
`wire-type-single-authority` / `presentation-purity` / `class-size` ✅. **Live parse-boundary proof:**
the live `/api/registry/{resources,operations}` wire (15 resources + 29 operations) validated
against the new generated Zod with **0 `[WireContract]` failures** — the precision is faithful, not
over-tight, against real production data; the worktree FE renders the shell cleanly.

## 15. The §5 prevention keystone shipped (2026-06-04): the `contribution-surface` gate locks in §4c

§4c made the registry shape a generated projection, but nothing *stopped the hand-mirror coming
back: the existing guards (`contract-projection`, `check-wire-type-single-authority`) enforce
single-authority only for shapes already in `TARGETS`/`contract-surfaces.v1.json` — a NEW
hand-authored `interface X {}` / `type X = {}` in `types/registry.ts` for an unregistered shape
passed every gate green. That is the open hole §5 names. This ships the **unblocked SHAPE-face half**
of §5's "contribution-surface register + gate" keystone — turning 4c's guarantee from honor-system
(~70%) into a build property (~100%).

**What it is.** A new discipline-gate (`governance/registry.v1.json` + `governance/contribution-surfaces.v1.json`
+ `scripts/governance/gates/contribution-surface/`), cloned from `operation-surface`. It is
**barrel-centric**: it scans the one registry FE type barrel for **shape-purity** (a bare
`interface`/object-literal `type` not on a 3-entry allowlist — `OperationInvocation`/`ResourceCatalog`/
`OperationCatalog` — is a second shape authority → FAIL) and **import-purity** (the barrel may import
only from `../generated/*` or `zod`; a laundered import from a sibling FE module → FAIL). Detection
is a regex/line-scan over the block-comment-stripped barrel (no TypeScript-compiler dependency — the
first-token-of-RHS rule distinguishes `= Omit<Wire>&{…}` (allowed) from `= {…}` (forbidden), and the
import-purity check closes the cross-file laundering hole a naive regex would miss). It **grandfathers**
the registry surfaces that aren't projected yet (Prompt/DiagnosticChannel/Surface/ConversationShape —
served raw; known-pending, NOT demanded-projected) and FAILS if one silently gains a generated module
(promote-when-projectable). The register reserves `altitude`/`consumers`/`delivery` keys (ignored by
the v1 enforcer) so the deferred 4a/4b/4d facets extend it additively, without a rewrite.

**Verification (build-time gate — browser N/A).** Real-repo green baseline (the current barrel → 0
violations, the false-positive acceptance test); kernel self-test (positive→pass, negative→fail); 9
table-driven unit tests (every derivation form passes; bare interface / object-literal / laundered
import fail; doc-comment robustness; grandfather drift+coverage); a negative smoke on the **real**
barrel (an injected `SneakyHandMirror` interface → `registry-barrel-purity` fires, revert clean); the
full gate suite green (no sibling regression). The deferred §5 invariants (DeliveryChannel /
trust-bypass / live-witness, facets 4a/4b/4d) stay deferred — they need the delivery substrate +
plugin ecosystem that don't exist; this is the shape-face half, and the register is the scaffold they
will extend.

## 16. DiagnosticChannel projected (2026-06-04): the last hand-mirror retired, the register's promotion teeth exercised

§4c projected `Operation`/`Resource` (the two primitives that shipped the `AuditPolicy` drift bug),
but the registry kept **one live remaining instance of that same drift class**: `DiagnosticChannel`
(the **Logs surface**, an active operator feature) was fully hand-mirrored in
`modules/ui-web/src/api/types/diagnostic.ts`, served **raw** by
`RegistryController.handleDiagnosticChannels`, with **no parse boundary** (`as DiagnosticChannelCatalog`).
A concrete drift was live: the wire **added a `consumers` field** post-hoc as raw `ConsumerHook` **with
its `kind` discriminator** (the last un-flattened consumer serialization), while the FE `DiagnosticChannel`
interface **omitted `consumers` entirely** — a type-lie. This slice retires that mirror, applying the
§4c recipe and exercising the §5 gate's **promote-when-projectable** teeth.

**What shipped.**
- **Backend projection (mirror view).** A new `UIDiagnosticChannelView` (`app-agent-api/.../registry/`,
  `implements PreciseWire`) mirrors the domain record's nine fields **plus the discriminator `type`** (the
  raw wire carries it, same as `UIResourceView`) **plus a first-class `List<ConsumerView> consumers`**.
  `LoggerNamespaceSelector` was marked `implements PreciseWire` so its `Map<String,SubCategory>` selector
  projects precisely (object with enum `additionalProperties` → `Record<string,SubCategory>`), not a bare
  `{type:object}`. A new `UIDiagnosticChannelEmitter` (clone of `UIResourceEmitter`) builds the view;
  `RegistryController.handleDiagnosticChannels` now emits via the emitter and **flattens consumers to
  `ConsumerView`** (`SurfaceConsumerIndex.merge` + `ConsumerView::from`) — **retiring the last `kind`-ful
  consumers wire**, now consistent with Resource/Operation. `UIDiagnosticChannelViewConformanceTest` pins
  the emitter byte-for-byte against the raw-record wire + a synthesized `consumers:[]`.
- **Codegen + FE.** `SubstrateSchemaGenTest` captures `diagnostic-channel.v1.json` (11 required top-level
  fields; precise selector; flat `ConsumerView[]`); `gen-wire-schema-types.mjs` gains the TARGET →
  generated `diagnostic-channel.ts` (`DiagnosticChannelWire` + `diagnosticChannelWireSchema`). `diagnostic.ts`
  now **derives** every type from the generated wire (the hand-mirror gone — only the deferred SSE
  `DiagnosticEvent`/`DiagnosticEventEnvelope` payload stays hand-authored, a distinct streamed surface, a
  564-style follow-up); `DiagnosticChannelCatalogClient` gained the `parseWireContract` boundary (was an
  unchecked `as`).
- **§5 register promotion (the teeth).** The instant `diagnostic-channel.ts` existed, the gate's
  `grandfather-drift` check would FAIL unless `diagnostic-channel` moved `grandfatheredPending → surfaces[]` —
  so it was promoted, and the §5 enforcer's `scan.barrel` was **generalized from a single barrel to a list**
  (`registry.ts` + `diagnostic.ts`), bringing `diagnostic.ts` under §5 shape/import-purity governance in the
  same stroke (a sibling-barrel `./registry` import is allowed for the reused `Presentation`/`Provenance`).
  The 564 register (`contract-surfaces.v1.json`) gained the `DiagnosticChannelWire` row.

**Verification (this IS user-visible → browser required).** Deterministic: conformance + schema-gen tests;
`gen-wire-schema-types --check` clean; FE typecheck + 2368 unit tests; `build -x test`; the §5
`contribution-surface` gate (incl. the generalized barrel-list + the promoted register) + the 564
`contract-projection` gate green; the §5 enforcer's own 11 unit tests (incl. 2 new multi-barrel cases) +
kernel self-test. **Live + browser:** the worktree backend (the MCP runs *main*, which serves the old
`kind`-ful wire the new precise Zod rejects — so a standalone worktree Head on a loopback port) served the
real `/api/registry/diagnostic-channels`, validated against **both** the envelope Zod (the exact FE parse
boundary) and the per-entry generated Zod → **0 drift**; the wire showed `type:"diagnostic-channel"`,
flattened `consumers` (the real `core.logs-surface`/`OPERATOR` hook derived from the Surface index),
present-as-null `provenance.identity`, `Record` selector. The **Logs surface rendered in a real browser**
(worktree FE Vite → worktree backend): badge **"connected"**, **live log lines streaming** via the parsed
`channel.endpoint`, **zero `[WireContract]` console errors**.

**Net.** The registry's last kind-ful consumers wire and last hand-mirror are both gone; all four served
registry primitives that have a real catalog (Operation/Resource/DiagnosticChannel projected;
Prompt/Surface/ConversationShape remain grandfathered as raw/known-pending). The §5 gate's promotion path is
no longer theoretical — it forced exactly the right migration the moment the module landed.

## 17. Plugin-declaration completeness shipped (2026-06-04): the contribution model spans all six kinds

§12 deferred plugin-declaration completeness "Skip with §5/561" — *only needed to make plugins
gate-visible, folds in if/when those land*. **Both have now landed** (§5 contribution-surface gate + 561's
interaction-surface gate are on `main`), so the precondition the disposition named is met. Investigation
pinned the concrete defect: `PluginContributions` + `ContributionRegistry.Installation` carried only **four**
of the six declarable kinds (operations/resources/prompts/surfaces) — a plugin could *consume* a
ConversationShape/DiagnosticChannel (`SurfaceConsumes` carries both) but **could not contribute** one. CORE
declares all six.

**Part A — model completeness (the keystone).** Both `DiagnosticChannel` (RegistryEntry) and
`ConversationShape` (Provenanced) already implement `Declaration` with `RegistryRef` ids, so the substrate's
own promise held — *"a new `List<X>` on `Installation` routed through the same generic loop, not a new
mechanism."* Added `diagnosticChannels` + `conversationShapes` to `Installation` (+ `axes()`),
`PluginContributions` (the ref manifest), and the composed `ContributionRegistry.{diagnosticChannels,
conversationShapes}()` accessors. The generic validate-before-commit + trust-boundary loop covers the two
new axes for free — tested: a vendor channel/shape composes; a **non-core plugin minting a `core.*`
DiagnosticChannel is rejected** ("Host owns truth"), proving the boundary bites on the new axis with zero
per-axis code.

**Part B — the DiagnosticChannel composition bridge.** The registry endpoint read a *static*
`List.of(headLogCatalog())`; `ContributionRegistry` never fed it (the `RegistryController` "plugin-supplied
catalogs land later" comment). Mirrored the Operation precedent: snapshot `contributions.diagnosticChannels()`
after installs → a composed `DiagnosticChannelCatalog` threaded `SubstratePhase.Output → SubstrateGraphAssembler
→ ChannelSubstrate.pluginChannelCatalog → LocalApiServer`, so `/api/registry/diagnostic-channels` now serves
core head-log **plus** the composed plugin channels.

**Part C — the demo + browser proof.** A dev-gated example plugin (`ExampleChannelPlugin`,
`-Djustsearch.demo.pluginChannel=true`, off in prod) contributes a `vendor.example.demo-log` DiagnosticChannel
through the new axis. **Verified end-to-end in the real UI:** the worktree backend served the live
`/api/registry/diagnostic-channels` with **two** entries (`core.head-log` + `vendor.example.demo-log`),
validated against the generated `diagnosticChannelWireSchema` with **0 drift**; the Logs surface loaded the
two-channel catalog through the FE parse boundary and rendered with **zero `[WireContract]` errors** — a
plugin-contributed primitive consumed cleanly by the FE, the first proof the declaration-completeness path
works plugin→endpoint→UI.

**Honest scope limit.** This delivers the declaration-completeness *model* + a working plugin→endpoint→UI
path. The build-time *gate enforcement* over plugin surfaces (the interaction-surface "one visible USER
surface" invariant counting vendor surfaces) stays out of scope — it hits the build/run wall (§11.2) and needs
a static declared-plugin source or the deferred runtime-witness. The model is the precondition that future
gate work needs. `conversationShapes` is added to the model for symmetry (the defect was "4 of 6") with
unit-test coverage; only DiagnosticChannel has a shipped FE consumer to demo.

## 18. Plugin Surface + ConversationShape served end-to-end (2026-06-04): a plugin rail surface, in the real rail

§17 made all six kinds *declarable* but only Operations + DiagnosticChannels were *served*. This bridges the
two **ConversationSubstrate** kinds — Surface + ConversationShape — so a plugin-contributed instance actually
reaches `/api/registry/{surfaces,shapes}`. The **Surface** is the showcase: the FE was already plugin-aware
(`SurfaceCatalogClient.bootSurfaceRegistry()` → `listSurfaces()` → `Shell.refreshSurfaces()` renders every
`placement:RAIL` surface), so once the backend serves a plugin surface it **visibly appears in the rail** — a
genuinely user-visible plugin contribution, not just a registry entry.

**The bridge.** Exactly the §17 DiagnosticChannel pattern, mirrored: `ContributionRegistry.{surfaces,
conversationShapes}()` are snapshotted in `SubstratePhase`, composed into `SurfaceCatalog.of("composed", …)` /
`ConversationShapeCatalog.of("composed", …)`, threaded `SubstratePhase.Output → SubstrateGraphAssembler →
SubstrateGraph.ConversationSubstrate.{pluginSurfaceCatalog,pluginShapeCatalog} → LocalApiServer`, and served
alongside the core catalogs. **No FE changes, no §4c projection** (both stay grandfathered/raw — the
contribution-surface gate's `grandfather-coverage` holds and `grandfather-drift` doesn't fire because no
generated module is added). `RegistryControllerTest` pins that a composed plugin surface/shape appears in the
`/surfaces` and `/shapes` envelopes.

**The demo.** `ExampleChannelPlugin` was renamed **`ExamplePlugin`** and now installs **one multi-axis
`Installation`** (`vendor.example.demo`) contributing a DiagnosticChannel + a `vendor.example.demo-surface`
(`Placement.RAIL`, `Audience.USER`, mountTag `jf-log-surface` reused so click renders the Logs view,
`SurfaceConsumes.empty()`) + a `vendor.example.demo-shape`. Dev-gated by `-Djustsearch.demo.plugin=true` **or**
the env var `JUSTSEARCH_DEMO_PLUGIN=true` (the env var propagates reliably to the forked head; the sysprop via
JAVA_TOOL_OPTIONS proved flaky across dev restarts). Off in production.

**Verified end-to-end in the real UI** (worktree backend + FE Vite): the live `/api/registry/{surfaces,shapes,
diagnostic-channels}` each include their `vendor.example.*` entry; the shell **rail renders a "Demo" item** (the
plugin surface, label derived from the id) alongside the core surfaces, and **navigating to it renders the Logs
view with zero `[WireContract]`/console errors** — a plugin-contributed Surface, navigable in the real rail.

**Scope limit.** ConversationShape is served + FE-catalog-consumed (validated at the endpoint + the boot fetch),
but a *visibly running* plugin conversation shape needs a conversation-substrate shape runner — out of scope.
Resources/Prompts plugin-serving bridges remain unbuilt (the same one-pattern extension, lower demo value).

## 19. The pivot to the first plugin (2026-06-04): the delivery path is mostly built — ship a plugin, let it drive the rest

This section **refines §12's scoping decision** and **updates Appendix A's delivery-state view** with what a focused
delivery-path investigation surfaced: the plugin *delivery* half is far more built than the doc reflected (Appendix
A only recorded "scaffold loads via the real loader"). It reframes "what's left" from a list of theorized substrate
facets into a single concrete decision — **build the first plugin next, against the seams that already exist.**
(Confidence note: the state map below is from one 2026-06-04 investigation; load-bearing files are cited, and the
genuinely-unverified items are flagged. Earlier theory under-counted this; verify against `main` before trusting a
specific BUILT claim.)

### 19.1 Corrected delivery-state — BUILT vs STUBBED/MISSING

**BUILT (production-ready or live):**
- FE plugin loader — `PluginLoader.ts` (fetch → evaluate in an **SES compartment** → install; module-mode `@kernel/*`
  imports + script-mode factory; SES lazy-loaded/code-split).
- Plugin manifest/artifact format — `plugin-types.ts` `PluginManifest` (id, version, `contractVersion`, per-Category
  `contractVersions`, capabilities, register/activate hooks, settingsSchema, optional signature), validated at
  `PluginRegistry.install()`.
- `@kernel` capability boundary — `KernelResolver.ts` (tier-aware module map; resolver-time attenuation, not runtime
  if-checks).
- Install/uninstall lifecycle + trust-tier enforcement — `PluginRegistry.ts` (register/activate/deactivate/unregister;
  per-plugin namespace teardown; audience-floor demotion UNTRUSTED→`OPERATOR`; tier-differentiated endowment bundle).
- A live **scaffold plugin** — `dev-examples/plugin-scaffold/plugin.js` loads through the real loader and contributes
  a RAIL surface + custom element, exercising endowments + attenuation.
- The **backend** 6-axis contribution model + serving bridges for Operations / DiagnosticChannels / Surfaces /
  ConversationShapes (§4c + §16 + §17 + §18) and the build-time `contribution-surface` shape gate (§5).

**STUBBED / MISSING:**
- **Signature verification** — `TrustChannel` is a stub → every URL-loaded plugin defaults to **UNTRUSTED** (no way to
  grant TRUSTED yet). *The single most load-bearing item for trusted plugins.*
- **`lockdown()` not called** — the compartment gives realm isolation only (mutable intrinsics not frozen).
- **FE manifest declaration gaps** — `PluginCapabilities` can't declare `conversationShapes` / `diagnosticChannels` /
  altitude / consumers (the FE half of the §10.4 model fix done on the backend).
- **No plugin-supplied operation handlers** — FE plugins can only *invoke* core/MCP operations, not *contribute* a new
  one (no handler-registration path, FE or backend).
- **No backend plugin loader** — only CORE (static) + MCP (external process) reach the backend registry (this is §4a).
- **No write capability**; Resources/Prompts plugin-serving bridges unbuilt; no plugin-*install-time* declaration
  validator (the `contribution-surface` gate is build-time / FE-barrel-shape, not a runtime check); Settings
  load-from-URL UI hook **unverified**.

### 19.2 Sequencing decision — first plugin BEFORE further substrate

The hard architectural cuts are **already seams in code** (`TrustChannel` verdict seam, `KernelResolver` attenuation,
the `contractVersion`'d manifest, the 6-axis model, `ContributionComposer` host-owns-truth). So the missing
implementations are **backward-compatible additions, not rewrites** — a first plugin coded against these seams does
not break when verification / handlers / attenuation land later. Therefore: **ship the first plugin against the built
seams; defer 4a / 4b / 4d / signature verification / handlers until a real plugin demands them.** The first plugin is
a **probe**, not just a deliverable — §11/§13 are the precedent (theorized "clean win" hid costs only the
implementation exposed); a real plugin yields a *shorter and more correct* next-step list than abstract theorizing.
This is the "prevent the substrate-without-consumer anti-pattern" application of the doc's own YAGNI thesis.

### 19.3 The first plugin — criteria + the recommended candidate

**Criteria:** FE-altitude, untrusted-safe (runs sandboxed, needs no `verify()`/backend loader), **read-mostly** (no
write path / no plugin handlers), **peripheral** (low blast radius — *not* Search/Health/Logs), real + useful, a
multi-interface probe, ideally a **dogfood-by-extraction** of an existing CORE surface (the VS-Code-built-ins
strategy — proves a plugin can express what CORE does, with a known-good reference to diff).

**Recommended: the Token Editor** (the design-token tool), scoped as **read + live-preview + export** — *not*
core-token write. Its peripherality is the killer feature (theming failure breaks nothing critical); its
operator-tool shape maps exactly to the untrusted→`OPERATOR` audience floor; it exercises surface + presentation +
settings + a theme read. **The decisive caveat:** an UNTRUSTED plugin is sandbox-demoted and **cannot mutate `core.*`
state**, so *persisting* core theme tokens would need TRUSTED tier + a write path + would fight the theme
single-authority gates / host-owns-truth — the wrong shape for a *first* plugin. Reframe it as read current tokens →
tweak + live-preview (plugin-scoped CSS custom properties) → export a token file the operator applies out-of-band.
**Open question to settle first:** is token read/preview reachable through the plugin capability API, or core-internal
(which would force capability-API work)? If core-internal, ship a trivial read-only peripheral surface (e.g. Help) as
the literal *first* to prove the pipe, and make the Token Editor the richer *second*.

### 19.4 The post-first-plugin sequence (unlocked by demand, in order)

- **Bar 1 — a safe FE plugin (small last mile):** a Settings **load-from-URL** entry point; a **`lockdown()` /
  scope-as-trusted** sandbox-safety decision; **FE manifest completeness** (altitude/consumers/shapes/channels).
- **Bar 2 — a trusted / backend-capable plugin (the real lift, in order):** **signature verification** (`TrustChannel`
  → real) → **plugin-supplied operation handlers** (FE + backend registry path) → **§4a backend attenuation
  substrate** (the greenfield-XL frontier, for Head/Worker-altitude plugin code) → **Resources/Prompts serving
  bridges** + a plugin-install-time declaration validator.

**Net.** 560's registration + projection + prevention spine is shipped/in-worktree; the delivery seams are built and a
scaffold runs live. The remaining work is no longer "theorize the substrate" — it is "ship the first (untrusted, FE,
read-mostly) plugin, then let what it cannot do cleanly order Bar 1 → Bar 2." Everything heavier stays deferred until
a real consumer demands it.

## 20. De-risk pass (2026-06-04): §19 verified + corrected — read §19 *with* this section

§19 was written from a single-agent delivery map (one known error) + dated claims, and **never reconciled tempdoc
533** (the canonical first-plugin doc) or **527 §6.2** (the PluginHostApi consumer audit). This pass converted §19's
assumptions to evidence (source reads + the 160-test plugin-api suite + a **live** scaffold-load experiment). Net:
§19 was *directionally right* (the delivery seams are built; first-plugin-as-forcing-function is correct) but **wrong
on its headline recommendation** and imprecise on "hollow." Corrections below supersede §19 where they conflict.

### 20.1 Corrections to §19

- **"Hollow" was the wrong word.** Per 527 §6.2 the 14 `PluginHostApi` sub-interfaces are **implemented but
  *unconsumed*** — not missing. `registration` is live; `navigation` partial (`navigateForward` no-op); `data` is
  bridge-wired (no plugin yet); and **8 are fully implemented with zero plugin consumers** (`discovery, settings,
  search, inspector, selection, theme, platform, utilities`). The first plugin is their *first consumer / validator*.
  Three are genuinely broken: **`VirtualOperationCatalog` is never booted** (`bootVirtualOperationCatalog()` has no
  production callsite → plugin agent-verbs silently no-op — 527 §6.3 Hollow 1), `host.ai` streaming is unconsumed, and
  Profiles V2 has no UI.
- **The §19 BUILT/STUBBED map had errors; the source-verified map is:** loader = **real** (fetch text → evaluate in
  SES Compartment → validate manifest → install; `PluginLoader.ts`); sandbox = built, **`lockdown()` deliberately
  off in V1.5 alpha** (staged to V1.5.1/.2) **but test-proven viable** (`substrate-lockdown.test.ts`); trust = **seam
  built** (`StubTrustChannel`→UNTRUSTED default + `RemoteTrustChannel`→`/api/plugins/verify`; real Sigstore is
  V1.5.2-pending); manifest = **richer than §19 claimed** — `consumers`, `diagnosticChannels`, `inspectorTabs` already
  declarable; the *real* FE-manifest gaps are **`conversationShapes` + `altitude`** only.
- **The Settings entry point EXISTS** (§19 flagged it unverified): `SettingsSurface.ts` ships a live **"Plugin URL"
  load-from-URL** input (confirmed in the browser). Bar-1 item #1 is *done*, not pending.

### 20.2 The live experiment — the substrate is validated, not just dated

Loaded the scaffold (`dev-examples/plugin-scaffold/plugin.js`) through the real loader in a running app. Console
proved the security substrate works **live**: the SES namespace proxy **blocked** a non-namespaced
`customElements.define`; `@kernel/data` **rejected** a non-allowlisted path and **allowed** `/api/health`. So
load → evaluate → run-as-UNTRUSTED → attenuate → working `@kernel/data` is **live-verified in the current build**.
Two onboarding bugs found: the scaffold's `dev-server.js` is **broken under ESM** (`require` in a `type:module`
package), and loading the Vite-transformed module mangled the manifest id to `'unknown'` (so the surface didn't mount
— use the raw-source path). The substrate is proven; the *authoring/onboarding* path has small fixable bugs.

### 20.3 First-plugin recommendation — §19's Token Editor is OUT; align with 533

§19 recommended the **Token Editor** (read+preview+export). The existing `TokenEditorSurface.ts` *is* already exactly
that shape (preview via a `@layer user-theme` `<style>`; export to JSON; **never writes core tokens** — the §19 caveat
is satisfied). **But it is not extractable as a plugin on the current capability:** the plugin `theme` sub-interface
(`PluginThemeState`) is **selection-only** (`subscribeActiveTheme` / `getActiveThemeId` / `selectTheme`) — it exposes
*no* design-token tree and *no* preview-apply; the surface relies on a core-internal `KNOWN_TOKEN_NAMES` import +
direct `<style>` injection. Shipping it as a plugin would **force `theme`-capability expansion** — a substrate task,
not a clean dogfood. So §19's pick does not survive contact with the capability.

**Reconciled recommendation: adopt 533's framing.** 533 picks a **Markdown-checklist tracker** that exercises ≥6
*built-but-unconsumed* sub-interfaces (`data` read/write, `settings`, `inspector`, `selection`, `navigation`,
`registration`) — i.e. it validates the capabilities that actually exist, and its read-vs-write split is the trust
calibration 533 wants. If a *smaller* first step is preferred, pick a plugin built on the live/implemented surfaces
(`data` read of allowlisted endpoints + `settings` + `inspector`) — **not** the Token Editor and **not** anything that
needs `VirtualOperationCatalog` (broken) or `host.ai` (unconsumed). A "Plain theme" plugin (533's smallest candidate)
*is* doable on `selectTheme` alone but validates only one thin slot. **This is a genuine pick the user should make**
(checklist = best calibration, bigger; data/settings/inspector reader = smaller; plain theme = trivial) — surface it
before implementing.

### 20.4 Net confidence change

Up on "the pipe works" (live-proven + 160 green tests) and on "the seams are built." **Corrected** on the
first-plugin pick (Token Editor → not viable; 533's checklist or a data/settings/inspector reader). **Reframed**
"hollow" (implemented-but-unconsumed). **Resolved** the Settings entry point (exists). **Found** two onboarding bugs.
The §19 → §20 delta is exactly the `audit-without-test` / `static-green ≠ live-working` correction the project's own
rules warn about — caught *before* implementation, which was the point.

## 21. First-plugin decision (2026-06-04): the Markdown-checklist tracker

§20.3 left the first-plugin pick open. **Decision: the Markdown-checklist tracker** (533's recommendation), chosen as
the better *long-term* option. This resolves §20.3 and re-converges 560 with the canonical first-plugin doc (533);
the §19 Token-Editor pick is retired (§20.3: the plugin `theme` capability is selection-only — not extractable).

**Why it's the better long-term pick (substrate-validation leverage, not "smallest safe step"):**

1. **Highest de-risking leverage.** It exercises **six implemented-but-unconsumed `PluginHostApi` sub-interfaces** in
   one slice — `data` (read **+ write**), `settings`, `inspector`, `selection`, `navigation`, `registration` — turning
   the largest block of "built-but-never-validated-against-a-plugin" surface (527 §6.2) into *validated*.
2. **It uniquely exercises the read/WRITE trust calibration** — the highest-risk-if-left-unvalidated property. The
   substrate's *distinct* treatment of read vs write (UNTRUSTED attenuation) has never met a real writing consumer.
   Every serious future plugin will write, so validating write-attenuation early — against a real consumer — prevents
   a cold future surprise. The read-only / trivial alternatives leave this entirely untested, i.e. 533's "false
   confidence."
3. **The "ship something tiny first to prove the pipe" rationale is already moot.** §20's live experiment *already*
   proved load → SES sandbox → attenuation → working `@kernel/data`. So the first *shipped* plugin should be the
   high-value calibration one, not another smoke test.
4. **Genuinely useful + mid-effort.** Notes-corpus users benefit immediately (533's "a real user would install it"
   bar); days, not weeks.

**Why not the alternatives:** a *Plain-theme* plugin validates only the thin `theme` (selection) slot — near-zero
leverage + false confidence; a *read-only `data`/`settings`/`inspector` reader* is smaller but leaves the write
calibration (the load-bearing risk) unvalidated.

**Forcing-function consequences to honour when it's implemented (not now):**
- The checklist's **write** forces the `host.data` write path + its UNTRUSTED attenuation to be built/validated — per
  533 "write is the calibration," that is the *intended* cost, not a detour.
- **Keep V1 FE-only and agent-verb-free.** Per §20, `VirtualOperationCatalog` is **not booted in production**
  (`bootVirtualOperationCatalog()` has no callsite) — so a plugin agent verb would silently no-op. If the checklist
  later wants `vop_extract_checklists`, that boot-wiring gap (532) must be fixed first; the V1 checklist should *not*
  expose an agent verb, keeping 532 deferred until genuinely demanded. Likewise no custom ConversationShape (491
  Phase D stays closed unless the plugin needs one).
- **Precondition (cheap):** fix the broken scaffold onboarding before authoring — `dev-examples/plugin-scaffold/
  dev-server.js` won't run under `type:module` (observations.md 2026-06-04); rename to `.cjs` (or rewrite ESM) so the
  documented "Browser dev mode → Load from URL" path works with raw source (Vite-served loads mangle the manifest id).

**Status:** the first plugin is now *decided*. The next step, when the user chooses to start, is to plan + implement
the Markdown-checklist tracker as the PluginHostApi forcing function (the Bar-1 work — load entry point exists,
manifest gaps are `conversationShapes`/`altitude` only, lockdown is a staged flip), with the onboarding fix as the
precondition. This is not yet started.

## 22. First-plugin decision REVISED (2026-06-04): the Token Editor — correcting §20.3/§21

**This supersedes §21's pick and corrects §20.3.** §20.3 "ruled out" the Token Editor on an *unfair* basis — that
extracting it "would force capability-API work." That was a double standard: §21 *praised* the checklist **for**
forcing capability work (the `data` write path — "write is the calibration"). Forcing substrate work is the whole
point of a forcing-function plugin, so "it forces work" was never a real disqualifier; it was retro-justification for
converging with 533. Retracted.

**Decision: the Token Editor (design-token editor) is the first plugin**, as a dogfood-by-extraction.

**Why it's a sound pick:**
- **It already exists and is already the right shape.** `TokenEditorSurface.ts` is read + live-preview + export and
  **never writes core tokens** (preview via a `@layer user-theme` `<style>`; export to JSON) — so it's UNTRUSTED-safe
  and host-owns-truth-safe out of the box. Extraction is *lower greenfield* than the checklist (a new surface).
- **It's a clean dogfood-by-extraction** — re-ship an existing core surface *as* a plugin, the gold-standard "a plugin
  can do what core does" validation, with a known-good reference to diff against.
- **It's a legitimate forcing function** — it becomes the *first consumer* of the `theme` sub-interface (one of the 8
  implemented-but-unconsumed slots, 527 §6.2), forcing that slot to be completed and validated.

**Honest tradeoff vs the checklist (not hidden):** the checklist would validate *more* — six sub-interfaces +, uniquely,
the read/**WRITE** trust calibration; the Token Editor is narrower (it mainly validates/expands `theme`) and read-only,
so it leaves write-attenuation untested. So **the checklist remains the higher-leverage *pure-validation* probe and is
the recommended *second* plugin** (it closes the write-calibration gap soon after). The Token Editor is chosen *first*
because it is the lower-risk extraction and the maintainer's pick; the breadth gap is paid down by sequencing the
checklist next.

**The one real design wrinkle to handle (not a blocker):** a plugin token-editor needs the `theme` capability to expose
(a) the token tree (read) and (b) a preview-apply. Apply must be **value-based, not raw-CSS** — i.e.
`theme.previewTokens(Map<knownTokenName, value>)` where the *host* generates the scoped `<style>` from known token
names — so an UNTRUSTED plugin can only set values for a fixed token set, never inject arbitrary CSS (which would be a
clickjacking / UI-spoof vector). Read is low-risk (`theme.getTokens()`).

**V1 scope (when implemented — not now):** UNTRUSTED-safe, FE-only, no agent verb, no core-token write — read the token
tree → value-preview (host-injected `@layer`) → export JSON. Capability work = extend `PluginThemeState`
(`getTokens()` + `previewTokens(Map)`); the export download path is the small open bit (host download capability vs
clipboard, given UNTRUSTED has no file picker). **Preconditions:** fix the broken scaffold `dev-server.js`
(observations.md 2026-06-04) and keep V1 agent-verb-free (`VirtualOperationCatalog` is not booted — §20).

**Status:** decided (Token Editor, first; checklist, recommended second). Not started.

## 23. Token Editor shipped + the §4.4 discovery that corrected §22's "UNTRUSTED V1" framing (2026-06-04)

**The Token Editor plugin is implemented and live-verified in the real browser.** Along the way the
spec-tightening pass (reading the admission code before writing the plugin) falsified a load-bearing
assumption in §22, which is recorded here because it is the substantive lesson.

**The discovery — §22's "UNTRUSTED-safe V1" was wrong.** §22 scoped V1 as "UNTRUSTED-safe … no
signing." But the §4.4 PRESENTATION constraint (`isPresentationAdmissible`,
`modules/ui-web/src/shell-v0/plugin-api/PresentationVocabulary.ts`, tested in
`PresentationVocabulary.test.ts`) **drops an UNTRUSTED plugin's own-element surface** — an untrusted
plugin may only mount the constrained `jf-*` host vocabulary; its own `<token-editor-panel>` is a
forbidden "second presentation authority." A Token Editor renders a bespoke editor UI, so **as
UNTRUSTED its surface is dropped — it never renders.** The Token Editor (or any custom-UI plugin)
therefore **requires TRUSTED tier.** This is exactly what 533 said all along ("real signature
verification … shipping a plugin that bypasses lockdown defeats the calibration") and what §19–§22
kept deferring. There is no clean "force TRUSTED" dev escape: `explicitTier:'TRUSTED_PLUGIN'` is
overridden by the channel verdict (stays UNTRUSTED), and `explicitTier:'CORE'` removes the sandbox
entirely (abandoning the calibration).

**The decision (user-chosen): a minimal, dev-gated, sandboxed first-party TrustChannel.** Rather than
implement full Sigstore now (533's "real signing", still deferred) or drop the sandbox (CORE escape),
V1 grants TRUSTED to a first-party-*marked* source **in dev only**, keeping the SES compartment +
lockdown (only the endowment bundle + presentation admission widen). This is trust-by-assertion behind
a dev gate — the minimal sandboxed form of 533's "first-party manifest field" — **not** a substitute
for signature verification, which supersedes it when it lands.

**What shipped:**
- **Part 0 — `FirstPartyTrustChannel`** (`plugin-api/TrustChannel.ts`): in dev, a source carrying the
  `@justsearch-first-party` marker → `TRUSTED_PLUGIN`; otherwise delegates unchanged to the fallback
  (RemoteTrustChannel). In a production build (`devMode === false`) it is a pure pass-through — it can
  never grant trust. Wired into the Settings "Load from URL" path (it wraps RemoteTrustChannel, so the
  third-party path is unchanged). Tested incl. the prod-never-grants case.
- **Part A — the `theme` capability** (the §22 forcing function, value-only design realized): a shared
  `themes/tokenIntrospection.ts` (`listTokens()` + `applyTokenPreview(Map, styleId)` validating every
  key ∈ `KNOWN_TOKEN_NAMES`); `PluginThemeState.getTokens()` + `previewTokens(Map)` in `HostApiImpl`
  (the **host** generates the scoped `@layer user-theme` `<style>` from `{name→value}` pairs — the
  plugin never supplies raw CSS). The core `TokenEditorSurface` was refactored onto the same util (one
  source of truth). `HostApiTheme.test.ts` proves read + injection + the **unknown-token rejection**.
- **Part B — the plugin** (`dev-examples/token-editor/`): a TRUSTED, sandboxed plugin contributing a
  RAIL surface mounting its own `<token-editor-panel>`; reads `host.theme.getTokens()`, live-previews
  via `host.theme.previewTokens`, exports via `host.ui.copyToClipboard`. **The core surface stays** —
  the plugin runs alongside it as the dogfood.
- **Part C — scaffold onboarding fix**: `dev-server.js` → `.cjs` (it was CommonJS under a
  `type:module` package — `require is not defined`); both dev servers serve raw source.

**A routability finding (fixed in-plugin; logged for the scaffold).** A surface id must be
`core.*`/`vendor.<x>.*` to be a valid `SurfaceRef` (router regex, `router/parser.ts:53`) — a
`<pluginId>.<suffix>` id like the scaffold's `scaffold.panel-surface` is *admitted but not navigable*.
The plugin uses `vendor.token-editor.editor-surface` (mirroring the Java ExamplePlugin convention) so
it is fully navigable; the scaffold's unnavigable convention is logged in observations.md.

**Live-verified (real browser, MSW-mock FE so no shared-stack dependency).** Through the **real**
Settings "Load from URL": the row reads `token-editor v0.1.0 **trusted** … **1 surface(s)**` (an
UNTRUSTED load would show 0 — dropped by §4.4); navigating to
`justsearch://surface/vendor.token-editor.editor-surface` **mounts the panel via the router**, which
renders **70/70 tokens** (`getTokens`); editing `--p-glass` **live-restyled the running app**
(`87,60,40` → a vivid value) via a host-generated `@layer user-theme { :root { --p-glass: … } }`
`<style>` (value-only, no raw plugin CSS — the §4.4/§22 security design); Export copied the token-tree
JSON; Reset cleared the preview and reverted; **zero console errors.** All host-side deterministic
gates green (typecheck; the full 2379-test FE suite; the new theme + trust tests).

**What this does NOT close (unchanged from §22):** real signature verification (533's signing path —
the `FirstPartyTrustChannel` is the dev stand-in, prod-inert); the read/**WRITE** trust calibration
(the Token Editor is read+preview only) — both belong to the recommended **second** plugin (the
checklist tracker, 533). `VirtualOperationCatalog` stays unbooted, so V1 remains agent-verb-free.

## 24. Critical-analysis hardening of the §23 slice — 6 fixes, two security-grade (2026-06-04)

A critical-analysis pass of the §23 changes found real defects in the new `theme` capability and its
dev trust path — two security-grade — which are now fixed (all six, source-verified + browser-proven):

1. **CSS value injection (HIGH).** `applyTokenPreview` validated the token *name* but interpolated the
   *value* raw into `:root { --name: value; }`, so a value containing `}` broke out and injected an
   arbitrary CSS rule (`red} html{display:none}` → app hidden) — falsifying §23's "the plugin never
   supplies raw CSS" claim, and present in the core `TokenEditorSurface` too (shared util). **Fix:**
   reuse the existing `isSafeTokenValue` rule (`/[}{<>]/`, designTokenTree.ts — the same check the
   theme-tree loader uses) in `applyTokenPreview`; throw + write-nothing on an unsafe value. One fix
   covers the capability and the core surface.
2. **Theme writes un-gated for UNTRUSTED (HIGH).** `theme` was the only capability built inline in
   `createHostApi` (not a `create*Api(tier)` factory), so an UNTRUSTED plugin got `previewTokens` (new)
   AND `selectTheme` (pre-existing) — it could silently repaint the whole app (hide a warning, `url()`
   beacon, spoof), a presentation-authority escalation contradicting §4.4. **Fix:** extracted
   `capabilities/theme.ts` `createThemeApi(tier, pluginId)` mirroring `createSelectionApi` — reads
   universal, the writes structurally omitted for UNTRUSTED (`selectTheme?`/`previewTokens?` now
   optional on `PluginThemeState`). The one CORE consumer (`SettingsSurface` theme picker) guards with
   `?.`.
3. **Preview lifecycle (MEDIUM).** The injected `<style>` was a global constant id (multi-plugin
   clobber) and was never cleared on uninstall (Revoke left the app restyled). **Fix:** per-plugin id
   `jf-plugin-token-preview--<pluginId>`; the Token Editor manifest implements `unregister(host)` →
   `previewTokens?.(new Map())`, which the registry calls on uninstall — Revoke now fully reverts.
4. **First-party marker hardening (MEDIUM).** The marker was honored anywhere in the source. **Fix:**
   only honor it within the leading ~512 chars (a header-comment anchor), and `console.warn` on every
   dev-trust grant (no silent elevation).
5. **Value-aware widget (MEDIUM; pre-existing).** `categorizeToken` typed channel-triplet tokens
   (`--p-glass` = `87, 60, 40`, used as `rgb(var(--p-glass))`) as full colors, so the color picker
   wrote a hex that corrupts the downstream composition. **Fix:** `listTokens` keeps the color picker
   only for standalone color literals (hex / self-contained color function); triplets / `var()` /
   unset get a text input.
6. **Scaffold surface-id convention (LOW).** The scaffold's `scaffold.panel-surface` was unnavigable;
   updated to `vendor.scaffold.panel-surface` + documented the `vendor.*` requirement in the scaffold
   README authoring guide (the copy-me template now models the right convention).

**Verified.** Typecheck clean; the full 2391-test FE suite green (+12: tier-attenuation, sanitizer-
rejection, value-aware-widget, marker-anchor); lint clean. Live browser (MSW-mock FE): the Token
Editor (TRUSTED) still renders 70 tokens and live-restyles; a `}` value is **rejected** (no injected
rule, app stays visible); **Revoke fully reverts** (per-plugin `<style>` removed, `--accent-tint` back
to `oklch(75% 0.15 180)`); the dev-trust `console.warn` is visible; the color picker now lands on a
real color literal (`accent-tint`), not the channel-triplet `p-glass`. The UNTRUSTED-omits-the-writes
property is proven by unit test (createThemeApi(UNTRUSTED) lacks `previewTokens`/`selectTheme`) rather
than a separate live load.

## 25. Decision: the plugin is the ONE token editor — retire the core surface, graduate the plugin to first-party (2026-06-05)

**This supersedes the "keep the core surface, retire later" framing carried through §22/§23/§24.** That
was a half-measure. The end state is **one** token editor — the plugin — and the core
`TokenEditorSurface` (508 §5) **deleted**. Two editors is pure redundancy (it even surfaced as two
"Token Editor" entries during the §23/§24 live verification). The §22 "dogfood-by-extraction, the
plugin runs *alongside* core" was always meant to converge here; this records that convergence as the
goal, not an optional follow-up.

**The blocker this exposes — the plugin is currently dev-only.** As shipped in §23/§24 it lives in
`dev-examples/token-editor/`, is served by a local `dev-server.cjs`, and loads as TRUSTED only via the
*dev-gated* `FirstPartyTrustChannel` (prod-inert by construction). In a production build there is no
dev server and that shim never grants trust — so the plugin would not load at all. **Deleting the core
surface today would leave production with zero token editors.** So "make the plugin the only one"
first requires graduating it from a dev example to a shipped first-party plugin.

**Graduation path (compiled-in / bundled-trusted — recommended):**
1. Move the source out of `dev-examples/` into a bundled first-party location (imported with the app;
   the panel extends the real `HTMLElement`, not a SES-compartment endowment).
2. Install it at boot via the direct `registry.install(manifest)` path — compiled-in plugins default
   to `TRUSTED_PLUGIN` (PluginRegistry), so it is trusted-by-construction in every build with **no**
   dev server, **no** dev-trust shim, and **no** signing. (Open item: find/add the prod-safe boot
   site — the existing dev-fixtures auto-install is `import.meta.env.DEV`-gated and must not be the
   only path.)
3. Retire the core `TokenEditorSurface`: delete the file + its `core.token-editor-surface`
   registration. The plugin already covers read + value-preview + export, so nothing is lost.

**The honest tradeoff this records — the token editor's substrate-validation role narrows.** §22
justified the token editor as "the forcing function for the *whole* plugin substrate." A *compiled-in*
plugin bypasses the loader, so it exercises the **contribution + host-API + lifecycle** layers
(manifest, `register`/`unregister`, `surfaceContributions`, `customElements`, `host.theme.*`,
`host.ui.*`) — but NOT the **loader / SES sandbox / trust-verification** layers in production
(compiled-in is trusted, unsandboxed). That coverage is now carried by: (a) the §20 de-risk, which
already live-proved the loader→sandbox→attenuation pipe once (with the scaffold); and (b) the
still-deferred **signing path** (533) plus a genuinely third-party plugin (533's checklist tracker),
which remain the way to validate untrusted/sandboxed/**signed** loading in production. So shipping the
token editor compiled-in does **not** discharge 533's signing item — it just stops blocking the "one
editor" product goal on it. Framed positively: a core-quality feature delivered through the plugin
contribution pipe is itself a real proof that the pipe is expressive enough to replace bespoke core
surfaces — the original dogfood thesis, intact at the contribution layer.

**Alternative (not chosen now): signed-load.** Keep the token editor going through the real loader +
sandbox in production, trusted via a real signature. This preserves full substrate coverage in prod
but is the deferred Sigstore work — a much larger lift than compiled-in. Recommended as a later
*upgrade* of the shipped plugin, not the path to "one editor."

**The dev `FirstPartyTrustChannel` stays** — it remains the dev-time trust path for *other*
URL-loaded first-party dev plugins (e.g. iterating on the scaffold / the 533 checklist); the token
editor simply stops depending on it once compiled-in.

**Status:** decided (one token editor = the plugin; core `TokenEditorSurface` retired; graduate via
compiled-in first-party). Not yet implemented. Open item: the prod-safe boot-registration site for a
new first-party plugin.

## 26. Implemented: the plugin is the ONE token editor (graduated to bundled first-party; core retired) (2026-06-05)

The §25 decision is implemented and browser-verified. There is now exactly one token editor — the
bundled first-party `token-editor` plugin — and the core surface is deleted.

**Open item from §25 resolved.** The prod-safe boot-registration site is `main.jsx`, exactly where the
compiled-in `CorePlugin` installs (`registry.install(createCorePluginManifest(), 'CORE')` after
`setHostApiDeps`). The token editor installs the same way — unconditional (NOT `import.meta.env.DEV`-
gated), in its own try/catch so a failure logs but never blocks CorePlugin/discovery.

**What shipped:**
- **New `src/shell-v0/plugins/token-editor/TokenEditorPlugin.ts`** — the verified dev plugin ported to a
  bundled TS module: a module-level `TokenEditorPanel extends HTMLElement` (plain DOM, the §24-hardened
  panel) + `createTokenEditorPluginManifest()` (id `token-editor`, surface
  `vendor.token-editor.editor-surface` USER/RAIL mounting `token-editor-panel`, `register` declaring the
  custom element + translations + surface contribution, `unregister` clearing the preview). No SES, no
  compartment, no `@justsearch-first-party` marker — trusted by being compiled-in.
- **`main.jsx`** — installs it after CorePlugin as `registry.install(createTokenEditorPluginManifest(),
  'TRUSTED_PLUGIN')` (a real plugin, trusted, first-party — not `CORE`, so the "a plugin can do what
  core does" dogfood stays a distinct plugin).
- **Core surface retired** — deleted the `core.token-editor-surface` block in `CorePlugin.ts`, the
  `import './views/TokenEditorSurface.js'` in `index.ts`, the `TokenEditorSurface.ts` file
  (`jf-token-editor-surface`), and the whole `dev-examples/token-editor/` (superseded; keeping a
  URL-loadable `token-editor` would collide on the id). The scaffold + `FirstPartyTrustChannel` stay
  (the dev-load trust path for other URL-loaded dev plugins). `tokenIntrospection.ts` stays — its
  consumer is now the `theme` capability (`createThemeApi`), which the plugin reaches via `host.theme.*`.

**The dogfood, now sharper.** A core-quality feature (the design-token editor) is delivered *entirely*
through the plugin contribution model + host API, with zero bespoke core surface — proof the plugin pipe
is expressive enough to replace core surfaces. The tradeoff recorded in §25 holds: compiled-in bypasses
the loader/SES/signed-trust layers in prod (that coverage stays with the §20 de-risk + the deferred
533 signing path); this slice does not discharge signing.

**Verified.** Typecheck clean; full **2395-test FE suite** green (+4 manifest-shape test); the
**production `vite build`** emits `dist/assets/TokenEditorPlugin-*.js` (it ships, not just dev). Live
browser (MSW-mock, no manual load): at boot Settings lists `token-editor … trusted … 1 surface(s)`;
navigating to `vendor.token-editor.editor-surface` renders 70/70 tokens and editing `--accent-tint`
live-restyles; `customElements.get('token-editor-panel')` is defined while
`customElements.get('jf-token-editor-surface')` is **undefined** (exactly one editor, core gone);
**zero console errors** and no `FirstPartyTrustChannel` warning (compiled-in, not URL-loaded).

## 27. Current-state de-risk re-verification (2026-06-11): the deferred verb/delivery facets

A confidence-building pass re-verified §9–§20's dated (2026-06-03/04) current-state claims against
`main` after it advanced ~81 commits (565/571/570/421/558-color-pair landed). The claims mostly hold,
with corrections worth recording (some surfaced only during the §28 implementation):

- **`altitude` now EXISTS** (tempdoc 571: `Altitude.java` enum + FE `PluginSurfaceContribution.altitude`).
  §1/§4a/§19.1's "altitude is implicit / the FE-manifest gap" is **stale** — altitude is a declared
  property at both tiers (surface-scoped; not yet an operation/delivery axis, so 4a is unaffected).
- **`VirtualOperationCatalog` is ALREADY booted** (`main.jsx:330`, unconditional) — the §19.1/§20 / 527
  "Hollow 1 / unbooted" claim is **stale**; plugin agent-verbs are live. (Correcting an earlier draft of
  this section that repeated the stale claim.)
- **The real FE write path is `host.settings`**, not `host.data`: `setSetting/getSetting` is
  plugin-namespaced + localStorage-backed (`HostApiImpl.ts`), ADR-0035-safe — a writing plugin needs no
  new persistence. (`host.data` stays GET-only for UNTRUSTED; TRUSTED `fetch` POST is the backend-write
  path.) This sharpened §19.1's "no write method".
- Confirmed-unchanged: presentation-admissibility still forces a custom-UI plugin to TRUSTED; signing is
  an allowlist stub (`PluginVerificationController`); `lockdown()` was off but viability proven
  (`test:unit:lockdown` green); 4a greenfield; 4b build-static + blocked on the four §E.2.1 decisions.

## 28. As-built: the buildable verb/delivery slice (2026-06-11)

The buildable half of the verb/delivery face — *trust-proportional delivery of a real third-party
plugin* — is implemented on `worktree-560-delivery` (4a/4b stay deferred-by-design; see §28.D-deferred).
At every fork the long-term/structural option was taken. Build + unit/frontend suites are green; the
live real-browser batch is the closing gate.

### 28.A — Operator-approval trust path (the production-real trust ceremony, short of Sigstore)

`PluginAllowlistStore` (mirrors `UiSettingsStore`; persists `$JUSTSEARCH_HOME/ui/plugin-allowlist.json`,
a sibling of `settings.json`; mode-aware) gives the operator allowlist **durability** — an approval now
survives a restart instead of silently dropping the plugin to UNTRUSTED. `PluginVerificationController`
is persistence-backed (loads at construct, writes through on approve/revoke) and gains
`POST /api/plugins/allowlist` (approve), `GET` (list), `DELETE /…/{sha}` (revoke), wired in
`LocalApiServer`. FE: one sha256 authority (`artifactSha256OfSource`, reused by verify + approve) + one
source-fetch authority (`fetchPluginSource`); `SettingsSurface` load flow detects an UNTRUSTED URL-load
and offers **Approve & trust** → allowlists the source hash → reloads through the same channel → the
now-allowlisted verdict returns TRUSTED (its own-element surface becomes admissible).

### 28.B — The first real third-party plugin (the live consumer)

`dev-examples/checklist-tracker/` (533's pick): a third-party, URL-loaded, *writing* plugin with its
own `<checklist-panel>` element, persisting items via `host.settings` (ADR-0035-safe). Its custom UI
forces TRUSTED, so it is the forcing function for 28.A. Served by `dev-server.cjs`. This exercises the
delivery path the compiled-in Token Editor bypassed.

### 28.C — SES `lockdown()` flipped on

`lockdown()` now fires once from the single SES chokepoint (`ensureSesLoaded` → `hardenRealmOnce`),
before the first Compartment, with the taming proven by `substrate-lockdown.test.ts`. Lazy (first
plugin load) so the SES bundle stays code-split and the intrinsic-freeze lands after app chrome is up
(dodging the boot-time Lit/Vite risk that deferred it); a no-op under vitest (so the main suite, which
reaches this path via PluginLoader tests, stays valid).

### 28.D — 4d: `CapabilityFamily` realized + durable grants persisted

`OperationPolicy.capabilityFamily` (back-compat constructors; off the registry wire). `DurableGrantStore`
gains **family grants** + **disk persistence** (`$JUSTSEARCH_HOME/ui/durable-grants.json`, mode-aware;
`persistent()` at the bootstrap construction site) — the per-op AND the wider `Grant.CapabilityFamily`
position, both surviving restarts. The fail-closed `CapabilityFamily.authorizes()` stays correct; the
**consumer** resolves op→family — the executor passes `op.policy().capabilityFamily()` to
`isAllowed(...)`. Issuance/management surface: `AuthorizationController` gains
`GET/POST/DELETE /api/authorizations/grants` (list / grant / revoke, operation or family); the
`ingest`/`file-operations` agent tools declare a `file-operations` family so the axis is real; a
**Durable grants** panel in `SettingsSurface` lists + grants + revokes them.

### 28.E — `conversationShapes` FE-manifest symmetry (declaration-only)

`PluginContribution.conversationShapes` added so the FE manifest spans all six contribution kinds (the
FE half of §17). Declaration-only: there is no plugin-facing `registerViewFactory` path and no shape
emitter (560 §18 names the runner out of scope), so a declared shape does not yet render live — logged
in `observations.md` as the pending runner.

### 28.D-deferred — what stays deferred-by-design

- **4a backend attenuation** — NOT built. Untrusted backend code is a security regression (ADR-0035 /
  §9.2); backend behavior requires a CORE/MCP trust ceremony, untrusted user code stays browser-altitude.
- **4b runtime witness** — ~~NOT built. Build-static by construction; blocked on tempdoc 575's register
  (theory-only) + the four §E.2.1 decisions.~~ **SUPERSEDED IN PART by §30 (2026-06-11, ADR-0042):** the
  LIVE-REGISTRY tier IS now built (`LiveWitness` — consumer-presence over the running registry, covering
  the runtime-composed contributions the static tiers miss), and the four §E.2.1 decisions are resolved
  as defensible defaults. What stays deferred is only the broader **§4b *uniform-all-kinds* witness
  surface** (one attested-event signal unioning the core substrate catalogs) — model it as a 575
  projection when scoped, not a fork.
- **Real Sigstore verification** — deferred (dep-weight gated). The 28.A operator-allowlist is the
  interim production-real path; Sigstore later falls through to the allowlist as an override.

### 28.F — Verification

`./gradlew.bat build -x test` green; FE `typecheck` + `test:unit:run` (2696) + `test:unit:lockdown` green;
backend module tests green (the lone failures were the env-dependent `AiInstallServiceLateBindTest`,
which needs `:modules:indexer-worker:installDist` — a worktree-setup artifact, unrelated). The closing
gate is one end-of-work live real-browser batch (untrusted→approve→trusted→persist→revoke; lockdown
smoke; durable family-grant persist+revoke) against the running dev stack.

### 28.G — Live-validation: two defects found and fixed (the point of the real-browser gate)

The closing real-browser batch found two defects unit tests + curl could not — both now fixed and
re-validated end-to-end:

1. **`tagNamespace` ≠ `id`** (`dev-examples/checklist-tracker/plugin.js`). The plugin declared
   `tagNamespace: 'checklist'` while its `id` is `checklist-tracker`; the renderer contract
   (slice 3a.1.5 §2) requires them equal, so the install was rejected. Fixed to `'checklist-tracker'`.

2. **`RemoteTrustChannel` "Illegal invocation" — every URL-loaded plugin forced UNTRUSTED**
   (`TrustChannel.ts`). The channel stored the native `fetch` as an instance field
   (`private readonly fetchImpl: typeof fetch = fetch`) and called it as `this.fetchImpl(...)`. A
   method call sets the receiver to the RemoteTrustChannel instance, and WHATWG `fetch` rejects a
   non-`Window`/`Worker` receiver with `TypeError: Illegal invocation`. `verify()`'s catch swallowed
   the throw → `untrusted('backend unreachable')`, and — because the throw is synchronous — **the
   verify POST never left the page**. So the operator allowlist could never take effect: approving a
   plugin wrote the hash to the backend, but the reload's verify never reached it. The pre-existing
   tests all injected `vi.fn()` doubles (which tolerate any receiver) so none exercised the native
   path; happy-dom's `fetch` likewise does not enforce the receiver rule. **Fix:** bind once in the
   constructor — `this.fetchImpl = fetchImpl.bind(globalThis)` — matching the established idiom in
   `CapabilitiesHandshake` / `ActionLedgerClient` / `OperationClient` (the only field-style fetch
   holder that had drifted from it). Regression test asserts the structural property (the receiver
   handed to the underlying fetch is the global realm, never the channel instance), so it pins the
   bug environment-independently.

**Live result (worktree bundle, backend allowlist persisted across a real restart):**
URL-load → UNTRUSTED with a real `verified:false` verify POST → **Approve & trust** → allowlist
persisted + reload `verified:true` → **TRUSTED** (badge flips, custom element defined) → surface
routes and `<checklist-panel>` mounts ("Checklist") → add/check items → state persists under
`pluginSettings['checklist-tracker']` → **survive reload**: fresh load returns `verified:true`
immediately (no re-approval) and the checked item is restored. The whole delivery path is proven.

## 29. As-built: the remaining delivery-face + projection work (2026-06-11)

After §28, a scoped pass ("Full delivery-face + 4c", with 4a backend-attenuation and the §5 runtime-witness
**gate** kept OUT — the former a §9.2 security regression, the latter blocked on the four §11.2 maintainer
decisions) implemented the genuinely-remaining actionable work on `worktree-560-delivery`. Build + unit/FE
suites green (FE 2702; backend app-services + ui green); the discipline-gate kernel passes (one declared
`class-size` growth for the `LocalApiServer` registration site). The live real-browser batch is the closing gate.

### 29.1 — Phase 1: the ConversationShape runner (the last undelivered kind)

A plugin's declared ConversationShape now RENDERS. `PluginRegistry.applyContribution` gained a
`conversationShapes` step that, for each declared shape with a `viewTag`, registers its `(shapeRef →
element)` view factory (`registerViewFactory`) so `<jf-chat-shape-mount>` resolves it. Trust-gated three
ways: the shape id MUST be `vendor.*` (registerViewFactory REPLACES on collision — without this a plugin
could hijack a `core.*` view); an UNTRUSTED plugin's own-element shape is DROPPED (mirrors how
`surfaceContributions()` drops inadmissible surfaces, §4.4); declaration-only shapes (no viewTag) register
nothing. The real trust tier is now threaded into `applyContribution` (it previously hardcoded
`TRUSTED_PLUGIN`; the legacy surface/resource axes' hardcode — inert because admissibility is re-checked at
enumeration — is logged in `observations.md`). The `check-shape-view-coverage` gate exempts `vendor.*`
(plugin shapes register at runtime, install-time-trust-validated, not as a source callsite). The
checklist-tracker plugin gained a second KIND — a `vendor.checklist.demo-shape` + its `<checklist-tracker-shapeview>`
element + a consuming `jf-chat-shape-mount` surface — as the live vehicle.

### 29.2 — Phase 2: Resources/Prompts plugin-serving bridges

The two remaining contribution KINDS now reach `/api/registry/{resources,prompts}`, completing the 6-kind
serving symmetry (the backend `Installation` already carried the axes; only the serving thread was missing).
Mirroring the §17/§18 Surfaces/DiagnosticChannels thread: `SubstratePhase` extracts
`contributions.{resources,prompts}()` → `SubstratePhase.Output` → `SubstrateGraphAssembler` composes
`ResourceCatalog.of("composed", …)` into `ResourceSubstrate.pluginResources` and merges core+plugin prompts
into `OperationSubstrate.prompts` (read only by the registry endpoint) → `LocalApiServer` adds the plugin
resource catalog to the served list. `ExamplePlugin` now contributes a demo Resource + Prompt (the Prompt's
`NonEmpty<ConsumerHook>` keystone caught a latent zero-consumer throw, fixed). The FE `promptContributions`
field was deliberately **NOT** added — there is no FE prompt-delivery path (unlike `resourceContributions`'
`mergePluginResourceContributions`), so it would be a consumer-less declaration, the very anti-pattern §5
forbids.

### 29.3 — Phase 3: run-tier witness observability (NOT a gate)

`GET /api/registry/witness` (new `WitnessController`) serves the LIVE composed `ContributionRegistry`, each
row flagged `buildWitnessed` (diffed against `RegistrySnapshotExporter`'s static snapshot). **Scope (not the
full catalog union):** the registry holds every *operation* from all sources (core/agent/workflow/MCP/plugin)
but only *plugin-contributed* surfaces/resources/prompts/channels/shapes — core ones live in separate
substrate catalogs (served at `/api/registry/*`), not mirrored here. A full uniform-all-kinds witness (§4b)
would additionally union those core catalogs (deferred; `/api/registry/*` already exposes them). The live
registry is reached via a new `HeadAssembly.liveRegistry()` (the `McpHostService`'s registry, already
threaded through `SubstratePhase.Output`). A runtime-only contribution (the demo
`core_workflow_demo_compose`, MCP tools, runtime plugins) appears here with `buildWitnessed=false` — the
concrete DR-D blind-spot, now observable. A read-only "Delivered contributions" panel in `SettingsSurface`
surfaces it. This is the **observability half** of 4b only; the §5 runtime-witness **enforcement** stayed deferred at
the time of writing (the four §11.2 decisions) — **subsequently built in §30** (ADR-0042): the
live-registry consumer-presence tier (`LiveWitness`) + the four decisions resolved. A pre-existing §28
ghost token (`--text-warning`) surfaced by the theme-token gate was fixed to `--accent-warning`.

### 29.4 — Phase 4: 4c Operation projection — already landed; stale comment corrected

`verify-don't-guess` over the dated §13.2 claim: the FE-facing `Operation` + every nested type
(`OperationPolicy`, `ConfirmStrategy`, `OperationInterface`, `OperationAvailability`, `OperationLineage`,
`ConsumerHook`) are ALREADY a generated `Tighten<OperationWire>` projection with `operationWireSchema` as the
parse-boundary authority (`AvailabilityExpression` opaque by design). The `contract-projection` +
`wire-type-single-authority` gates confirm `OperationWire` is single-authority with no hand-copy. Phase B had
landed; only the `registry.ts` header comments still said "hand-authored until Phase B" — corrected. 4c is
complete.

### 29.5 — Stays OUT, by sound design

4a backend plugin-loader / capability-attenuation (greenfield + §9.2 security regression — untrusted code
stays browser-altitude); the §5 runtime-witness GATE (four §11.2 maintainer decisions; only the Phase-3
observability half is unblocked); DeliveryChannel reification (YAGNI); a single global trust verdict (the FE
plugin-trust seam and backend operation-gate stay separate — 569-frozen).

### 29.6 — Live real-browser batch (2026-06-11): all user-visible phases proven

Ran the closing batch against a standalone worktree backend (the MCP dev-runner is rooted in `main`, so
a worktree-dist head + Vite was launched directly via `scripts/dev/dev-runner.cjs` with
`JUSTSEARCH_DEMO_PLUGIN=true`; apiPort 60386, uiPort 5174).

- **Phase 3 (witness) — PASS.** `GET /api/registry/witness` returns 40 entries, **6 runtime-only**
  (`buildWitnessed=false`): `core.workflow-demo-compose` (owner `core.workflows`) — the exact DR-D
  blind-spot — plus all five demo-plugin kinds (resource / prompt / surface / diagnostic-channel /
  conversation-shape, owner `vendor.example.demo`). The Settings "Delivered contributions" panel renders
  them with `runtime-only` badges.
- **Phase 2 (resource/prompt serving) — PASS.** `vendor.example.demo-resource` reaches
  `/api/registry/resources` and `vendor.example.demo-prompt` reaches `/api/registry/prompts`; both are
  also visible in the witness panel.
- **Phase 1 (ConversationShape runner) — PASS, after a live-caught fix.** The runner registered the view
  factory, but the plugin `jf-chat-shape-mount` surface rendered the "no shape-id" placeholder: two merge
  sites (`PluginRegistry.applyContribution` surface axis + `SurfaceCatalogClient.mergePluginSurfaceContributions`)
  rebuilt `consumes` WITHOUT `conversationShapes`, so `mintFactory` never derived `shape-id`. Both fixed
  (the FE `SurfaceConsumes` type already supported the field); regression test added. After the fix, the
  checklist plugin's `vendor.checklist.demo-shape` renders live — `<checklist-tracker-shapeview>` mounts
  ("Checklist (shape view) — mounted by the ConversationShape runner"), no placeholder. This is precisely
  the class of end-to-end gap unit tests + curl miss and only a live browser catches.

All four phases are implemented, committed on `worktree-560-delivery`, gate-green, and (for the three
user-visible phases) proven in the real browser. Merging remains the maintainer's call.

## 30. As-built: the §5 LIVE-REGISTRY witness (2026-06-11) — ADR-0042

Consumer-presence already had **two static tiers** (§9.1 #9, §9.3): the `consumer-presence` gate (a
declaration NAMES a consumer, over the static `RegistrySnapshotExporter` snapshot) and the
`runtime-witness` gate (the §5 "second half" for the AGENT channel — declared agent consumers match what
`AgentOperationEmitter` *would* deliver, computed from the **static** catalogs). Both read static
catalogs, so **neither examines the contributions composed into the live registry at runtime** —
projected `core.workflow-*` ops, MCP tools, plugin contributions (the DR-D blind spot §11.6, structural
per DR-A). This adds the **third, live-registry tier** (`live-witness`): a check that every contribution
DELIVERED into the live `ContributionRegistry` carries a consumer, applied over the LIVE registry so the
runtime-composed contributions are covered. It **complements** (does not replace or fork) the static
`runtime-witness` gate — same §5 keystone, the tier that sees runtime composition. Implemented on
`worktree-560-runtime-witness`; recorded in **ADR-0042**.

**The four §E.2.1 decisions — resolved as defensible defaults (the maintainer chose "pick defaults").**
1. **Agent-consumer witness shape →** attest at the **delivery seam**, not consumption (an agent tool's
   consumer = bound into the live toolset; delivery ≠ invocation). Deterministic + testable.
2. **Per-actor deadline →** none; **attested-at-compose, tier-keyed** (lockstep core/FE/agent at boot,
   independent plugin/MCP on `install`). "Undelivered" = declared-but-never-composed (a live absence).
3. **SliceCatalog referential-integrity →** out of scope; **Realized hooks only** (Promised is a separate
   future primitive — it was reverted, so only Realized exists today).
4. **Runtime traffic counter →** **delivery-PRESENCE, not a count**; reuse the live registry/witness, no
   new `TrafficCounter` primitive.

**As-built (three layers; no fork — reuses the build-tier merge):**
- **Teeth:** `LiveWitness.orphanedDeliveries(ContributionRegistry)` (app-services, co-located with
  `RegistrySnapshotExporter`) flags every delivered operation/resource/prompt with zero merged
  consumers. It **reuses** `RegistrySnapshotExporter.operationConsumerIds` (inline ∪ executor-derived)
  for ops and `SurfaceConsumerIndex` (inline ∪ surface-derived) for resources — the SAME
  consumer-presence merge, applied over the live set, not a second authority. Because an op with any
  executor derives a consumer, the witness cannot over-report; only a genuine zero-executor /
  no-surface / zero-consumer delivery is an orphan.
- **Enforcing tier:** `LiveWitnessTest` composes the live registry exactly as `SubstratePhase` does
  (`OperationCatalogComposition.installBaseCatalogs` + `installWorkflowOps`) and asserts: (a) clean
  composition has **no** orphans (GREEN); (b) the runtime-composed `core.workflow-*` ops are present in
  the live registry yet **absent** from the static snapshot (the DR-A/DR-D contrast made concrete); (c) a
  planted runtime-composed zero-consumer op is flagged — and ONLY it (RED detection, no over-report). The
  live-registry test is the only tier that can see runtime composition (DR-A), so this is where the
  invariant lives — not an offline kernel gate.
- **Anti-drift register (575-style):** `governance/live-witness.v1.json` names the authority + the
  enforcing test + the reused build-tier merge + the four decisions + a `complements: runtime-witness`
  cross-reference; `scripts/ci/check-live-witness.mjs` is a register-integrity early-warning
  (authority/test/reuse present + wired) so the witness can't be silently deleted or forked.
  Early-warning ceiling per §12.10 — the invariant itself is the green `LiveWitnessTest` run.

This closes the §4b/§5 witness against the DR-D blind spot at the live-registry tier. What stays deferred-by-design is
unchanged (§28.D-deferred): 4a backend attenuation (security do-not-build), the §4b *uniform-all-kinds*
witness (unioning the core substrate catalogs into one live surface — model as a 575 projection when
scoped), Sigstore, and DeliveryChannel reification.
