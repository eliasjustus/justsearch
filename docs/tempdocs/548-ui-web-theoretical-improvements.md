---
title: "548 — ui-web correct design: capability & contribution are projections of one authority"
type: tempdoc
status: open
created: 2026-05-25
rewritten: 2026-05-26
category: frontend / architecture / design-theory
related:
  - docs/tempdocs/547-ui-web-static-analysis-findings.md (the empirical defect map)
  - docs/tempdocs/543-agent-substrate-prior-art.md (Provenance / Scope / Action + §13 corrected primitives: EvaluationContext, Effect Journal, ContributionManifest)
  - docs/tempdocs/526-selection-substrate.md (typed addressable selection; per-item capability)
  - docs/tempdocs/507-capability-mediated-surface-architecture.md (kernel capability module graph)
  - docs/tempdocs/521-plugin-ecosystem-substrate.md (tier-specific sub-interface composition)
  - docs/tempdocs/560-extension-substrate-projection-and-delivery.md (TrustChannel = Provenance mint-site; ManifestValidator pipeline)
  - docs/tempdocs/534-post-substrate-proposals.md (SourceTier × RiskTier gate lattice)
  - docs/tempdocs/511-aggregate-surfacing-substrate.md (closed (AggregateKind, SurfaceContextKind) render dispatch)
  - docs/tempdocs/514-typed-askai-intents.md (typed ask-AI call-site contract)
  - docs/tempdocs/550-agent-action-lifecycle.md (operation & action lifecycle: one canonical record, governed projections, liveness invariant; §4 names this doc as home for the theme/i18n/event/pane meta-class instances)
  - docs/tempdocs/530-class-size-ratchet-automation.md (four-layer discipline-gate kernel)
  - docs/tempdocs/531-substrate-consumer-drift-detection.md (consumer-drift gate)
  - docs/decisions/0040-wire-contract-format.md (ADR-09a — proto IDL contract)
---

# 548 — ui-web correct design: capability & contribution are projections of one authority

**Status: design theory.** This is a *design* document — the correct long-term structure and
its rationale, not implementation steps, phasing, or feasibility. It states the one invariant
that prevents the recurring defect-class, the tier-ladder for choosing fixes, and the few shared
authorities the codebase's open ideas actually reduce to.

> **Correct-design pass (2026-05-26).** The previous rewrite of this doc flagged a "capability
> manifest projecting both the module graph and the endowment set" as `[PROPOSED] — my synthesis,
> not in 507/521`, and a "substrate registry" for the drift gate as net-new. A full read of the
> adjacent design tempdocs (507, 521, 478, 526, 543, 530, 531, 511, 514, 550) **refuted both
> flags**: the correct design already exists, convergent across those docs, and this doc simply
> had not recognized it. The two corrections that reshape the whole document:
> - **Capability is not a subsystem and not a fourth axis — it is a *projection* of the
>   ContributionManifest (543 §13.2.3) + multi-axis Provenance minted by the TrustChannel
>   (478 §4.D).** Both the build-time module graph (507) and the runtime endowment set
>   (`PluginCapabilityBundle`) derive from that single declaration. The "capability manifest" is
>   the existing ContributionManifest, extended to be the projection root — not new synthesis.
> - **The "substrate registry" is the already-shipped consumer-drift gate (531) on the 530
>   kernel.** Its coverage just needs to become a *projection of a substrate catalog* (530 Layer
>   4), not a hand-listed `slots.json`.
> One live finding also reshapes §4.5: a URL-emission probe against the running model showed it
> **never emits a dedicated `query` verb** — it emits `navigate(core.search-surface, {q})`. The
> correct intent design therefore has *fewer* kinds, with `query`/`answer` as projections, not
> co-equal verbs (itself the collapse invariant applied).

---

## 1. The invariant (what every section below is an instance of)

The 547 defect map and the implementation work share **one** root cause. It is *not* "too much
hand-rolled code" and *not* "duplication" (caches, generated code, read-replicas are duplication
and are fine). The defect is precise:

> **Two authorities for one concept, neither subordinate to the other.**

Every catalogued bug is this shape:

| Bug | The one concept | The two authorities |
|---|---|---|
| §5 enum decode | the lifecycle-state vocabulary | hand-written `app-api` enum (`"READY"`) **and** proto-generated enum (`"LIFECYCLE_STATE_READY"`) |
| God-Object HostApi | "what a plugin of tier T may do" | the type surface **and** scattered runtime `if (tier === …)` checks |
| Capability drift | "what an untrusted plugin can reach" | the module/type surface **and** the SES endowment set (built separately) |
| F15 contribution leak | "what this plugin contributed" | `applyContribution`'s enumeration **and** `uninstall`'s separate enumeration |
| Fragmented provenance | "who contributed this" | `source` / `pluginId` / `owner` / `tier` across ~13 registries |

**The correct invariant (two clauses):**

- **(a) Single authority.** Every concept has exactly one definitional owner. Every other
  occurrence is a *typed projection* with a mechanical re-derivation — never an independent
  definition.
- **(b) Unrepresentability.** The subordinate form must not be *expressible* as an independent
  hand edit. A compiling program must be unable to emit a divergent value. This clause — not (a)
  — does the prevention; (a) alone still lets a developer hand-author a third form.

**Restatement for reviews:** *"One authority per concept; every other site is a typed projection
that cannot be authored by hand."* 543:1414 already states the corollary: convergent shapes
across the codebase (514's `AskAiIntent`, the `Effect` closed union, the Effect Journal's causation pointer,
510's capability-via-DOM) are *evidence of a shared authority* — name it and project both sites
from it. This doc operationalizes that note.

---

## 2. The prevention-tier hierarchy (the decision tool)

When two forms of one concept exist, the fix has four strengths. **Always reach for the
strongest that applies** — generation is *not* the top.

1. **Collapse (strongest).** Delete the redundant authority outright (e.g. delete the
   hand-written `LifecycleState`; delete the search-as-store-mutation bypass).
2. **Unrepresentable-by-type.** The subordinate form is a compile error if hand-authored (the
   type system / build graph rejects it).
3. **Generate.** The subordinate is mechanically produced — but *bypassable* (a developer can
   hand-construct the wrong thing next to it), so weaker than 1–2.
4. **Gate (weakest).** CI fails on divergence. Discipline-adjacent: covers only what the gate
   knows about, and its own coverage is a single-authority problem one meta-level up (§5.2).

The whole program is: for each two-authority concept, **move it up this ladder.**

---

## 3. The shared authorities (this is far fewer substrates than it looks)

The "single authority per concept" lens reveals the open ideas are **different views of a few
authorities** — and (the central correction of this pass) those authorities **already exist** in
the contribution/wire substrates. The work is recognizing them and projecting the remaining
hand-maintained forms from them, not inventing.

| Authority | Owns | Status | Where |
|---|---|---|---|
| **ContributionManifest** (543 §13.2.3) | *what a contributor declares* — its contributions, its required **capabilities**, its lifecycle | substrate shipped; not yet the sole declaration form | `@kernel/substrates/manifest` |
| **Multi-axis Provenance + TrustChannel** (543 §3.A / 478 §4.D) | *who a contributor is and what tier it earns* — `{identity, review, capability, installedAt}`, minted **only** by the TrustChannel | Provenance landed (3-field); TrustChannel landed (`StubTrustChannel`/`RemoteTrustChannel`); multi-axis is the extension | `plugin-api/TrustChannel.ts`, `@kernel/primitives/provenance` |
| **Scope / EvaluationContext** (543 §3.B / §13.2.1) | *when something is shown / may run* — ambient context + per-target fact projectors, read by the WhenExpression grammar | shipped | `@kernel/predicates/evaluationContext` |
| **Effect Journal** (543 §13.2.2) | *user/agent action reversibility* — undo/macro/audit/AI-preview. **Not** registry install/uninstall (category error). | shipped | `@kernel/substrates/effects` |
| **Wire-contract validator** (ADR-09a) | *what data any wire message legally carries* — API messages **and** agent-emitted UI specs | infra landed (FE `wireValidator.ts` + Java `WireContractValidator`); runtime use is opt-in | `contracts/wire/*.proto` |
| **Consumer-drift gate** (531 on 530) | *whether each substrate still has its declared consumers* — the tier-4 net | gate shipped; `slots.json` coverage intentionally empty pending per-substrate measurement | `gates/consumer-drift/`, `governance/registry.v1.json` |

**The capability correction.** Earlier this doc treated plugin capability as a fifth subsystem
needing a new "capability manifest." It is not. **Capability is a projection of three of the
authorities above**: declared in the **ContributionManifest** (`capabilities[]`), bounded by
**multi-axis Provenance** (the tier the TrustChannel mints), and gated per-use by the **Action
`when?`** predicate over **EvaluationContext** (which carries `platformCapabilities` /
`pluginCapabilities`). There is no separate capability authority to build.

---

## 4. Per-subsystem correct design

Each names the existing authority to project from and the prevention tier it should reach.

### 4.1 Wire contract — *collapse the enum (tier 1)*

The proto IDL (ADR-09a) is the spec authority and generates TS + Java types + protovalidate
constraints. The defect: `StatusLifecycleHandler` serializes the **hand-written** `app-api`
`LifecycleState` via Jackson `.name()` (short names); the proto enum is unused — two authorities
for one vocabulary. **Correct end-state (decided): full tier-1 collapse** — delete the
hand-written `LifecycleState`, depend on the proto-generated enum as the only definition, and
make the wire proto-canonical. The external short-name consumers (the jseval readiness detector,
the MCP dev-stack readiness gate, `sweep-cc-weights.sh`) migrate to the proto-canonical names;
they are part of the one concept, not a reason to keep a second authority. An ArchUnit rule
forbidding `app-api` from declaring wire-vocabulary enums keeps the collapse from regressing
(tier 2 on top of tier 1). The resulting validator is the **shared authority** §4.5 reuses for
agent UI specs — not a §5-only artifact.

### 4.2 Plugin capability + isolation — *capability is a projection of the manifest* — **highest leverage**

Today the FE plugin boundary is a ~924-line God-Object (`HostApiImpl`) with trust attenuation as
construction-time composition for some sub-interfaces (already the 521 pattern — good) but still
a single monolith, and the SES endowment set (`PluginCapabilityBundle`) is built *separately*
from the type surface — two authorities for "what an untrusted plugin can reach."

**Correct structure (grounded in 507 + 521 + 478 + 543):**

- **One declaration: the ContributionManifest.** A plugin declares its contributions *and* its
  required `capabilities[]` in one manifest. Provenance (hence trust tier) is minted by the
  **TrustChannel** (478 §4.D) — the registry never decides trust; it is a typed consumer.
- **Both attenuation mechanisms are projections of that declaration**, so neither can be
  hand-authored to diverge (invariant clause (b)):
  - **Build-time — the 507 kernel module graph.** No `host` object; discrete typed modules
    (`@kernel/operations`, `@kernel/resources`, `@kernel/ai`, …). The framework boundary *is* the
    set of import paths that resolve to kernel modules; eslint `no-restricted-imports` + a
    dep-graph test make an out-of-boundary import a build failure (tier 2). Trusted code that
    does not import a module cannot reach the capability.
  - **Runtime — the Compartment module resolver / endowment set.** For an untrusted plugin the
    resolver binds the *attenuated* implementation; the endowment set is computed from the same
    manifest `capabilities[]` + provenance tier, not authored independently.
- **Structural trust attenuation (521 §2.3).** Compose tier-specific sub-interface
  *implementations* at construction (CORE/TRUSTED full; UNTRUSTED restricted) so there are **no
  runtime `if (tier)` checks** in method bodies — trust is a structural property of the composed
  object/resolved module (tier 1). The God-Object's scattered conditionals are deleted; the
  monolith dissolves into the module graph.
- **Isolation is a layer on top:** SES `Compartment` per plugin (landed; `lockdown()` deferred by
  design), Worker/realm per untrusted surface escalates later. Isolation *enforces* the
  projection at runtime; the manifest *defines* it.
- **Trust stays a separate axis from identity-provenance** (543 §3.A / §12.2). Multi-axis
  Provenance `{identity, review, capability, installedAt}` with a derived `displayTier()` for UI.
  Folding trust into "who contributed" would rebuild the God-Object trust problem *inside* the
  contribution substrate.

This is the highest-leverage move: it retires the God-Object + the scattered attenuation + the
FE capability-drift class at once, and §4.5 (intent dispatch, AI invocation) fall out as kernel
modules. The former `[PROPOSED]` "capability manifest" is exactly this — the ContributionManifest
as the single projection root — now grounded, not synthesis.

### 4.3 Contribution substrate — *extend 543's corrected model*

- **Provenance, trust-tier, and scope are distinct axes** (543 §12). Apply a *uniform* Provenance
  value across all ~13 registries (today fragmented across `source`/`pluginId`/`owner`/`tier`).
- **Scope = EvaluationContext** (shipped): one context authority for all gating; per-target fact
  projectors evaluated by the WhenExpression grammar.
- **Action is a *parallel* substrate** (`(args) => Promise<Effect>`), not absorbed into the
  aggregate render dispatch (511) — same `(kind, context)` shape, different return.
- **Registry symmetry authority = the generalized per-item apply/remove table**, not the Effect
  Journal (which owns *user-action* reversibility, a different concept). Generalize the table to
  every contribution axis so adding an axis carries both sides (tier 2).
- **The ContributionManifest is the registry-of-registries for contributions**: adding a
  contribution kind *derives* its provenance slot, scope-gating, action-dispatch, capability
  declaration, and symmetric lifecycle — no parallel per-registry hand-maintenance.

### 4.4 Reactive state — *signal graph; persistence is a path-dependent ladder*

- The full **signal graph**: per-slice signals + `computed` projections; **profile-as-a-swappable
  -signal** so profile switching cannot rebind-drift. Scope state joins this graph as more
  signals feeding EvaluationContext.
- **Persistence is NOT a projection of the signal graph.** The migration ladder is
  *path-dependent* (a current build must deserialize old on-disk versions) → a per-version schema
  is necessary, not drift. The closure is **per-version round-trip snapshot tests** + the shared
  `flatSlicesFromProfile` projector, not a generative schema-from-graph.
- **CRDT only where genuinely multi-writer.** Single-instance settings do not get a CRDT.

### 4.5 Intent algebra — *fewer kinds; collapse the bypass*

Today search is a direct store-mutation that *bypasses* the intent pipeline (a second authority
for "the user asked to search"). **Correct structure, sharpened by live evidence:** a
URL-emission probe against the running model showed it **never emits a dedicated `query` verb** —
it emits `navigate(core.search-surface, {q})`. So the correct algebra is **as few kinds as carry
their own resolution model**, with the rest as *projections*:

- **`navigate` and `invoke` are the irreducible kinds** (resolve a surface / an operation).
- **`query` is a projection of `navigate`** to the search surface — not a co-equal verb. The win
  is collapsing the `searchState.setQuery` store-mutation entry point (the second authority for
  "user asked to search"), so all search travels the pipeline; it is *not* a new address kind.
- **`answer` is the one genuinely new resolution model**: it resolves a *shape* (not a
  surface/op) and dispatches via `host.ai.invokeShape` to the existing `core.rag-ask` /
  `core.summarize` shapes, rendering in the shape-bound view. It is distinct because its
  resolution target is the AI-shape catalog.
- **Agent-emitted UI specs reuse the §4.1 wire validator.** The closed `(AggregateKind,
  SurfaceContextKind)` registry (511) bounds *which renderer* runs; the wire validator bounds
  *what data* each kind carries. An agent UI spec is a wire message → same generated validators,
  no "spec schema vs. validator" drift. Receipts (550) make dispatched intents actionable; their
  reversibility uses the Effect Journal.

### 4.6 Rendering / shell — *no structural debt; lowest priority*

No two-authority defect here. Pinpoint DOM updates fall out *for free* from the §4.4 signal graph
(`SignalWatcher` updating a node without re-running `render()`). Pursue only if the signal graph
makes it incidental. Code-splitting on a local-disk desktop target is low-value.

---

## 5. Long-term prevention (so this never relies on memory)

### 5.1 Apply the tier ladder; default to collapse
For each two-authority concept the review question is "**can these be collapsed?**" before "can
we generate one from the other?" Generation that leaves the hand-authored form expressible has
prevented nothing.

### 5.2 The drift-gate's own coverage must be a projection — *extend 531/530*
The 531 substrate-consumer drift gate (on the 530 discipline-gate kernel) is the tier-4 net and
**is already shipped**; its `gates/consumer-drift/slots.json` coverage is currently hand-listed
and intentionally empty pending per-substrate measurement. The correct design (530 Layer 4:
registry unification + tempdoc→gate auto-wiring): the gate's coverage set becomes a **projection
of a substrate catalog** — each substrate declares its consumer floor once, and the gate
enumerates from that catalog, so adding a 14th registry cannot silently escape coverage. This
closes the recursion (the gate's own coverage had the single-authority problem one meta-level
up). It is an *extension of existing machinery*, not net-new — resolving the former `[PROPOSED]`.

### 5.3 Keep convergent shapes aligned (stated rule)
Adopt 543:1414 as an explicit rule: when two parts of the codebase converge on the same shape
(closed discriminated union, projection, capability-via-attribute, causation pointer), that is
evidence of a shared authority — name it and project both sites from it.

---

## 6. Leverage ordering

1. **§4.2 capability-as-manifest-projection** — dissolves the most surface (God-Object + trust
   attenuation + FE capability-drift), grounded in 507/521/478/543, unblocks §4.5 (intent / AI
   as kernel modules).
2. **§4.1 wire collapse** — small and sharp; establishes *collapse > generate* and produces the
   shared validator §4.4/§4.5 reuse.
3. **§4.3 contribution manifest generalization**, **§4.4 signal graph**, **§4.5 intent
   collapse + `answer`** — each projects from authorities established by 1–2.
4. **§5.2 substrate-catalog-projected gate** — the meta-level prevention, last.

**Already-shipped instantiations to build on** (mostly composition, not greenfield): the signal
graph foundation, the contribution-symmetry table, EvaluationContext + Effect Journal + the
Action substrate (543), the ContributionManifest + Provenance + TrustChannel (478/543),
the proto IDL + validator (ADR-09a), the typed closed aggregate dispatch (511), the answer shapes
+ `invokeShape` (514/491), the consumer-drift gate (530/531), the SES Compartment + tier
composition (507/521).

---

## Appendix — decisions & as-built status (2026-05-26)

**Decisions taken** (steering the /goal implementation): build both former-`[PROPOSED]` items
(ContributionManifest as capability projection-root; substrate-catalog-projected drift gate);
pursue **full** §4.1 collapse (delete the hand-written enum; proto-canonical wire; migrate the
external consumers); single merge to `main` at the end.
- **§4.2 depth (2026-05-26): full 507 vision** — migrate plugins from script-eval to
  ES-module-import so trust attenuation is resolver-based (`@kernel/*` modules). This is
  author-API-breaking (rewrites `register(host)` → per-capability imports + PluginLoader); done
  **last** in the sequence, with the trust-attenuation composition surfaced for review as it lands.
- **Sequencing: verifiable-wins-first** — §4.5 → §4.4 → §5.2 → §4.3 → §4.2 → §4.6.
- **`query` stays a co-equal kind** (the shipped S4-A); the live-probe "model emits navigate, not
  query" finding is informational. `answer` is added as the genuinely-new shape-resolution kind;
  its render destination is the existing `core.unified-chat-surface`. The §4.5 "delete the
  setQuery bypass" is interpreted as: the search-box input (per-keystroke) + `restoreSearch` are
  the legitimate projection/pipeline-internal paths; remaining *external* initiations (e.g.
  pinned-search application) route through the intent pipeline like the palette (S4-B) did.

**As-built so far** (instances of the invariant already landed in `worktree-548-impl`, pending
the larger design above):
- §4.4a — per-version persistence round-trip snapshot tests (S1).
- §4.4 (profile-as-a-swappable-signal) — **DONE through tier-1+2.** `UserStateDocument`'s flat
  consumer view (`docSig`) is now a `computed` projection of a single `storageSig`
  (`UserStateV2Storage`) authority via `viewFromStorage`; each profile's per-profile slices have
  exactly one home (`storage.profiles[id]`), so `setActiveProfileId` is a pure pointer flip and
  every slice rebinds structurally on the next read. The `flatSlicesFromProfile` spread on the
  switch path — the F11/F12 rebind-drift surface — is **deleted**, making that drift
  *unrepresentable* (tier-2) rather than test-gated. Per-slice signal decomposition is
  **deliberately not done (YAGNI):** the value-memoized `subscribeProjection` already is the
  computed projection for every slice; decomposing kills no real re-render and would be
  speculative. No schema change, no consumer edits, no migration impact. Verified: typecheck +
  full FE unit suite (2006) incl. F11 rebind guards, persistence-snapshot round-trips,
  profile-slice-coverage, signal-projection freshness, and a new switch-cycle no-bleed regression.
  ("Scope state joins this graph as more signals feeding EvaluationContext" remains future work,
  tracked under §4.3's EvaluationContext generalization.)
- §4.3a — apply/remove symmetry generalized into one `PER_PLUGIN_AXES` table (S2); provenance
  found already uniform across the command registries (S3).
- §4.3 (uniform Provenance, increment a) — **DONE through tier-1+2 for the surface + resource
  merge registries.** Those two catalogs used to *reconstruct* a lossy `{tier, contributorId,
  version}` Provenance partial at the merge site (dropping identity/capability/installedAt;
  surface hardcoded `version:'0.0.0'`) — the §1 "fragmented provenance" two-authority defect. The
  single Provenance minted once at the PluginRegistry install site is now threaded through the
  `PER_PLUGIN_AXES` apply and stored verbatim; the reconstruction is deleted. One authority,
  projected. Verified: typecheck + full FE suite (2008) incl. a new PluginRegistry collapse test
  (a contributed surface carries the minted multi-axis Provenance — real `manifest.version`,
  `identity.verified`, `capability[]`, `installedAt` — through the full install path). No wire
  change (catalog Provenance is TS-only).
- §4.3 (increment b + c) — **DONE.** (b) recovery + aggregate registries now carry the uniform
  minted Provenance: `RecoveryOverlay` dropped its separate `trustTier` field (governance reads
  `provenance.tier`; `TrustTier ≡ ProvenanceTier`, no behavior change), and aggregate strategies
  carry the Provenance for attribution while `source` stays the dedup/removal identity. All four
  merge-axis registries (surface/resource/recovery/aggregate) now project the one minted
  Provenance — the §1 fragmented-provenance defect is closed across the contribution merge path.
  (c) the structural end-to-end guard now covers both provenance-storing registries (surface +
  resource collapse tests assert the minted multi-axis value reaches the catalog; recovery's
  provenance-driven governance is covered by its tier tests). Verified: full FE suite 2008+.
  (d) **DONE + LIVE-VERIFIED** — the "Welcome to JustSearch" walkthrough migrated out of
  `CorePlugin.register()` into a `core.core-welcome` ContributionManifest installed at boot
  (Shell.ts); the manifest's single `provenance` flows through the `walkthroughs` coordinator onto
  the registered entry (CORE tier + 'core' source DERIVED, not hand-plumbed), and uninstall rolls
  it back. Unit: `canonicalManifest.test.ts` (install registers 'welcome'; idempotent; uninstall
  rolls back). LIVE (real browser, isolated to the WORKTREE FE — `vite` served from the worktree's
  modules/ui-web on :5175, not the dev-runner's canonical-main FE): first-run renders the welcome
  walkthrough ("WELCOME TO JUSTSEARCH — STEP 1 OF 4 · Open the command palette"). Isolation is
  airtight because the worktree's `CorePlugin` no longer registers the walkthrough, so the only
  registration source is the `core.core-welcome` manifest. (An earlier check against the dev
  stack's canonical FE was invalid — it exercised main's CorePlugin path, visually identical; see
  the live-verify-source pitfall in observations.) This proves the ContributionManifest is the
  canonical declaration root for a real, user-visible first-party feature. (Phase 2 —
  migrating all CORE_SURFACES onto manifests — is a follow-up; surfaces stay on CorePlugin.)
  (e) Java-side multi-axis Provenance on the wire is **descoped by design** — §3/543 §12.2 specify
  the `identity`/`review`/`capability`/`installedAt` axes as TS-only extension fields that do NOT
  cross the wire (the Java record stays 4-field); making them cross-process would contradict the
  "trust stays a separate axis" invariant.
- §4.6 (pinpoint DOM) — **DONE (realized + locked).** No two-authority defect; the outcome falls
  out of §4.4 once `docSig` became a `computed`. Proven by a live-reactivity test: a JfResource
  (`SignalWatcher(LitElement)`) mounted at USER tier denies an OPERATOR-only resource, and
  flipping the store to OPERATOR *while mounted* toggles the inner view in via Lit's pinpoint diff
  — `getViewerAudience()` projects the §4.4 `docSig` computed and the component re-renders with no
  `subscribeProjection` wiring of its own. The working `viewerAudienceState` tick is left intact
  (no risky refactor of a no-defect path, per §4.6's lowest-priority framing).
- §5.2 (substrate-catalog-projected drift gate) — **DONE (governance tier; fully self-tested).**
  The 531 consumer-drift gate's coverage is now a *projection of the substrate universe* via a
  `discovery` block in `gates/consumer-drift/slots.json`: the gate enumerates every
  `modules/ui-web/src/shell-v0/substrates/<name>/index.ts` and fails `uncovered-substrate` if a
  discovered substrate is neither covered by a slot (`slot.substrate === id`) nor grandfathered in
  `discovery.knownUncovered`. This closes the meta-level recursion (the gate's own coverage could
  be silently escaped) — adding an 11th substrate dir fails until it gets a measured slot or an
  explicit, reviewable `knownUncovered` entry. Mirrors the prose-tier-register meta-loop's
  discovered-vs-declared check; grandfathering (10 substrates today, info-level) is the
  class-size ratchet-from-here pattern so it lands green and tightens over time. Resolves the
  former `[PROPOSED]`. Limitation (documented): discovery covers the ui-web shell-v0 substrates
  dir only; cross-codebase discovery needs a machine-readable `@substrate` marker (net-new API,
  follow-up). Verified: enforcer integration 15/15, truth-table unit 17/17, gate self-test
  (positive pass / negative fail), real gate green with 10 grandfathered findings, full
  governance suite shows consumer-drift pass.
- §4.5 (partial) — the palette search bypass routed through the intent pipeline (S4-B); a
  `query` address kind + shared Java/TS grammar + scorer/probe support (S4-A) — **superseded in
  design** by §4.5 above (the live probe showed the model uses `navigate`, so `query` should be
  a projection, not a kind; S4-A stands as working substrate but the doc no longer treats it as
  the target shape).
- §4.5 (`answer` — the one genuinely new resolution model) — **DONE through tier-1+2.** The
  `answer` ShellAddress kind lands cross-process: sealed Java `ShellAddress.Answer(prompt, shape,
  state)` mirrored by TS `ShellAddressAnswer`; `justsearch://answer?q=<question>[&shape=...]`
  parsed identically by `MarkdownUrlExtractor` (Java) + `parser.ts` (TS) and pinned by the shared
  `url-grammar-fixtures/v1.json` corpus; production wire field names pinned by
  `QueryWireSerializationTest`; `BackendIntentRouterImpl` forwards `Answer` to the FE intent
  stream. The model is taught the verb (production `URLEmissionGrammar` PREAMBLE + Gate-G2 probe
  prompt) and the Gate-G2 scorer parses/canonicalizes/compares it. The IntentRouter lowers an
  `answer` to a `core.unified-chat-surface` activation that **runs on arrival** — parity with the
  `query`→search projection (`restoreSearch` executes the fetch) — by parking the forced shape
  (default `core.rag-ask`) + an auto-run flag in the compose one-shot registers (only once the
  surface resolves), which `UnifiedChatView` drains on connect and fires `send()` once the AI is
  chat-capable. Verified: FE typecheck + full unit suite (2006) incl. the auto-fire test
  (`consumeShapeStream` called once with the rag-ask shape + prompt), intentRouter one-shot
  parking, compose one-shot semantics; Java conformance + wire + ShellAddress suites; Gate-G2
  scorer self-test. **Live tier-3 — DONE + VERIFIED against the WORKTREE FE** (worktree `vite`
  on :5175 proxied to the backend + `cuda12` model + ingested corpus; NOT the dev-runner's
  canonical-main FE): on origin localhost:5175 the chat surface's RAG ask renders a cited answer
  grounded in the ingested docs, with **3 `cite-ref` inline markers + a `jf-citations-panel`**
  confirmed live in the DOM. This proves the answer verb's user-visible outcome (route a question
  to the `core.rag-ask` shape → cited answer renders) against the actual 548 code. Note: the answer
  verb fires through the intent-dispatch path (agent SSE emission / `navigate-with-context`), not
  hash deep-linking; the auto-run trigger itself is unit-tested (`UnifiedChatView` auto-fire), and
  the live tier confirms the cited render it produces. The
  `searchState.setQuery` *programmatic* bypass (pinned chips) is collapsed to the intent pipeline
  (S4-B, `SearchSurface.pinIntent.test.ts`); the remaining `setQuery` calls are the search store's
  own debounced-typing/clear mechanism, not a second authority for "user asked to search."
- §4.5 ("agent-emitted UI specs validated through the §4.1 wire validator") — **DESCOPED BY
  DESIGN (no consumer; C-018).** A verified read found: no `AgentUISpec` wire type exists; the
  aggregate render path (`renderAggregateMulti`) operates on plain `wire-types.ts` objects, not
  bufbuild `Message` instances; and `validateWireMessage` (`wireValidator.ts`) has **zero**
  production callers. Building an agent-UI-spec type + emitter + component + validator attachment
  would be greenfield substrate with no user — the exact substrate-without-consumer anti-pattern
  this tempdoc fights. The wire validator + the closed `(AggregateKind, SurfaceContextKind)`
  registry remain the designed home for this when a real agent-UI-spec consumer lands; the
  attachment point is `renderAggregateMulti` (`aggregate-substrate/aggregateRegistry.ts`).
- §4.1 — **DONE (full tier-1 collapse, live-verified).** Hand-written `LifecycleState` deleted;
  the proto enum is the sole authority; wire is proto-canonical (`LIFECYCLE_STATE_*`); all Java
  consumers + FE (`lifecycleState.ts`/StatusDeck/HealthSurface) + external tooling (sweep, jseval
  telemetry tag) migrated; ArchUnit guard forbids re-introduction (tier-2 on top). Verified:
  compile (all modules) + app-api/ui/FE/test_manifest suites + class-size & ArchUnit gates; LIVE
  (worktree backend) `/api/status` + `/api/health` emit `LIFECYCLE_STATE_*`, StatusDeck CONN dot
  renders healthy, zero console errors. Commits c9549559f / 463a3fce5 / 726b5bdb5.
- Infrastructure — live AI verification is unblocked end-to-end (worktree backend + `cuda12`
  variant + activated model), so the design's agent/AI/plugin paths are now live-verifiable.
- §4.2 (God-Object dissolution) — **IN PROGRESS (Increment A substantially DONE + B DONE; C/D
  remain, D = the user-authorized author-API break + security review).** **Increment A (DONE for
  all six attenuation-bearing capabilities):** `ai`, `platform`, `data`, `selection`,
  `registration`, and `ui` are each their own module under `plugin-api/capabilities/`, with the
  per-tier trust attenuation living inside the module that owns it (composition selected by
  `tier`, never an `if(tier)` in a method body). `createHostApi` is now a **thin assembler — 228
  lines, down from the original 924 (a 75% cut)** — wiring the capability builders plus the
  remaining inline read-only pass-throughs (navigation/discovery/settings/search/inspector/theme/
  layout/utilities, which carry no attenuation). Each extraction was behavior-preserving (the
  plugin-api suite of 135 + the full 2008-test FE suite are the oracle) and done with UTF-8-safe
  tooling. **Increment B (DONE):** the one
  residual runtime `if (tier === 'UNTRUSTED_PLUGIN')` in `createPluginAI` is replaced by a per-tier
  composition table (`openSessionByTier: Record<PluginTrustTier, …>`) — the goal's "no runtime
  if(tier)" is now satisfied for the host AI path (trust attenuation is data-driven composition,
  521 §2.3). **Increment A step 1 (DONE):** the `host.ai` sub-API (~260 lines) extracted verbatim
  to `plugin-api/capabilities/ai.ts` behind the unchanged `PluginHostApi` facade; `HostApiImpl`
  dropped 924→665 lines. Both verified at typecheck + plugin-api suite (135) + full FE suite
  (2008), no behavior/contract change.
  **Increment A is now complete for all six attenuation-bearing capabilities** (ai, platform,
  data, selection, registration, ui); the remaining inline entries are non-attenuated read-only
  pass-throughs. **Remaining = Increments C + D, which a verified read reclassified as a
  *fundamental loader + core-bootstrap rewrite*, not a localized change** (this is the §4.2 piece
  the user authorized as the "author-API break, done last"):
  - Plugins are evaluated as **expressions** (`compartment.evaluate(source)` → a factory/manifest
    with `register(host)`, `PluginLoader.ts:310`), *not* ES modules — so `import {…} from
    '@kernel/*'` requires switching the loader to SES module-graph loading.
  - The host facade has **real core-bootstrap consumers**: `Shell.ts:940` builds the CORE host
    (`createHostApi('core','CORE',…)`) and `PluginRegistry.makeHostApi` feeds `register`/
    `unregister`. Removing the facade migrates the core app bootstrap, not just plugin authoring.
  - 507 §3.2 itself calls plugins-as-modules a "future shape," and there are **no real external
    plugins** yet (CorePlugin is first-party + declarative), so D's near-term payoff is low while
    its blast radius (app startup + all plugin loading) is high.
  Per the cross-module-impact rule this blast radius was surfaced for an explicit go/no-go.
  **DECISION (user, 2026-05-26): C/D = designed + SES-spiked, DEFERRED per C-018** (consumer-less
  substrate), with the verified mechanism + spike findings + security checklist recorded here as
  the concrete D design. **SES runtime spike findings** (throwaway `*.spike.test.ts`, since
  deleted): (1) the `ses@^2` browser shim does **not** parse raw ESM source strings
  (`{source: "import …"}` throws) — raw-ESM plugin authoring would need `@endo/compartment-mapper`
  (a heavy precompiler); (2) a **VirtualModuleSource** `{imports, exports, execute(env, compartment,
  resolved)}` works, reading an imported namespace via `compartment.importNow(resolved[spec])`;
  (3) a `{ namespace: plainFrozenObject }` module descriptor exposes the object's own keys as named
  exports — so a capability builder's output is usable directly as a `@kernel/<cap>` namespace.
  **So the verified D mechanism is:** the sandboxed loader resolves a per-tier `@kernel/<cap>`
  module map built from the existing capability builders (`@kernel/ai` ABSENT for UNTRUSTED →
  `import` fails at link time = structurally unreachable, no runtime `if(tier)`); the plugin entry
  is a precompiled VirtualModuleSource (bundler-emitted shape). **Security-review checklist** (for
  the future build): attenuation structural-not-runtime; no capability/closure/`deps`/`globalThis`
  leak across the Compartment boundary; TrustChannel the sole tier authority; attenuation symmetry
  with the facade; the existing expression contract byte-unchanged. **Blocker / why deferred:**
  no external sandboxed plugins exist yet (CorePlugin is first-party + declarative), and the §4.2
  *core* security property — per-tier structural attenuation with no runtime `if(tier)` — is
  **already shipped** via the capability-module facade (`createHostApi(tier)`: UNTRUSTED
  structurally lacks `openSession`/`setSelection`, `data.fetch` is GET-only, all by composition).
  D's added gain (denied cap fails at module-link vs. at the method) is marginal until a real
  external-plugin consumer exists; building it now would be the C-018 anti-pattern this tempdoc
  fights (same disposition as §4.5's agent-UI-spec validation + §4.3 e). The §4.2 *core* goal —
  God-Object replaced by capability modules + 521 structural trust attenuation (no runtime
  `if(tier)`) — is met by A+B.
  Verified-vs-source plan below (the recorded D design).
  Verified-vs-source plan: `HostApiImpl` is 924 lines, one
  `createHostApi` factory with 14 sub-APIs; 521 §2.3 tier-composition mostly landed (6
  construction-time sites; one residual runtime `if(tier)` at `HostApiImpl.ts:900`); the
  `@kernel/*` module graph is notional (no source; eslint `no-restricted-imports` machinery
  exists but unpointed; the Compartment `modules`-map hook is unused at `PluginCompartment.ts:139`;
  plugins receive caps via the `host` endowment + `register(host)`, not module imports). Sequence,
  each green + non-breaking until the last: **(A)** extract the 6 composition sites + 14 sub-API
  builders into `plugin-api/capabilities/*.ts` behind the unchanged `PluginHostApi` facade (pure
  mechanical, behavior-preserving); **(B)** remove the last runtime `if(tier)` by splitting
  trusted/untrusted AI composed at the assembler; **(C)** introduce the `@kernel/*` tsconfig alias
  + eslint rule (warn→error) + dep-graph test (tier-2 boundary), facade still working; **(D)**
  BREAKING, last, multi-session — wire the Compartment `modules`-map to resolve `@kernel/*` to the
  attenuated builders for untrusted plugins, migrate `register(host)`→module imports, bump
  `PLUGIN_CONTRACT_VERSION`, migrate CorePlugin/fixtures, remove the facade, live-verify an
  untrusted-plugin load with a denied capability genuinely unreachable, and pass an **independent
  security review** (attenuation structural-not-runtime; no capability leaks; the TrustChannel
  mint-site is the sole tier authority; attenuation symmetry).
  (§4.6 is recorded as DONE in the §4.3/§4.6 block above — pinpoint DOM realized + locked by a
  live SignalWatcher reactivity test.)
