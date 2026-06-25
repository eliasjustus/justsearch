---
title: "557 — The presentation projection: display, observed-state, and theme as single-authority projections (the ui-web layer 548 §4.6 under-weighted)"
type: tempdocs
status: open
created: 2026-05-28
amends: tempdoc 548 §4.6 (rendering/shell) — reclassifies it from "no structural debt" to a first-class single-authority subsystem with three sub-authorities (display / observed-state / theme)
category: frontend / ux / design-theory / single-authority / presentation
related:
  - tempdoc 548 (ui-web correct design — "one authority per concept; every other site a typed projection that cannot be authored by hand"; the prevention-tier ladder Collapse>Unrepresentable>Generate>Gate). THIS DOC EXTENDS 548 to the presentation layer.
  - tempdoc 547 (ui-web static-analysis defect map) — was 548's empirical grounding; the Appendix here (Q1–Q22) is the analogous grounding for 557.
  - tempdoc 504 (systematic UX audit — functional reachability + defect class D6 "internal-state-leaked"); 557's display-authority collapses D6 structurally.
  - tempdoc 508 (coherent AI presence — AiStateStore) + 550 (agent action lifecycle — one-log / liveness invariant): the observed-state authority projects from these.
  - tempdoc 511 (aggregate surfacing substrate — (AggregateKind, SurfaceContextKind) render dispatch) + 509 (operation label coherence — the partial display authority `<jf-op-button>`/`deriveLabel`).
  - tempdoc 521 / 507 (ContributionManifest + capability projection) — the single declaration the display authority projects from.
  - tempdoc 530 / 531 / 553 (discipline-gate kernel / consumer-drift / execution-surface register) + ui-web `eslint.config.js` no-restricted-imports — the existing enforcement the prevention tier reuses.
  - tempdoc 545 (vite-proxy/SSE/CDP hang) — the dev-env caveat under which the grounding audit ran; its uncommitted one-line fix is noted in the Appendix.
---

# 557 — The presentation projection

> **What this document is.** A *design theory* for the JustSearch frontend's presentation layer:
> what the correct long-term structure should be so a recurring defect-class cannot return. It is
> the presentation-layer counterpart to tempdoc 548 (which theorized the wire / capability /
> contribution / reactive-state / intent layers). A live browser audit (2026-05-28) produced 22
> symptom findings; rather than 22 fixes, this doc shows they are **one structural defect in three
> places**, and states the correct structure for each. **No implementation here** — the empirical
> findings are condensed in the Appendix as proof-by-example.

> **Amendment to tempdoc 548 §4.6.** 548 concluded "Rendering / shell — *no two-authority defect
> here … lowest priority*." That judgment looked at the *mechanics* (signal graph → pinpoint DOM
> updates) and was correct about them. It missed that the **presentation** sub-layer — how an
> entity is *labelled*, what *state* a surface claims, which *theme tokens* it reads — carries the
> exact two-authority defect 548 names everywhere else. This doc reclassifies §4.6: presentation is
> a first-class authority subsystem with three sub-authorities, each owed a single-authority
> projection.

---

## 1. The one invariant (presentation is not exempt)

548 §1 states the defect precisely: **two authorities for one concept, neither subordinate to the
other.** The correct invariant is two clauses: **(a) single authority** — every concept has one
definitional owner; every other occurrence is a *typed projection* with mechanical re-derivation —
and **(b) unrepresentability** — the subordinate form must not be *expressible* as an independent
hand edit; a compiling program must be unable to emit a divergent value. Clause (b) does the
prevention. The prevention-tier ladder (548 §2), strongest first:

1. **Collapse** — delete the redundant authority outright.
2. **Unrepresentable-by-type** — the subordinate form is a compile error if hand-authored.
3. **Generate** — mechanically produced, but *bypassable*.
4. **Gate** — CI fails on divergence (covers only what it knows about).

The presentation layer exhibits this defect in three concepts, each currently at "scattered
authorities + a representability gap," each of which the audit caught as user-visible breakage:

| Sub-authority | The one concept | The two (or more) authorities today |
|---|---|---|
| **A. Display** | how an entity is presented (label/icon/description) | backend catalogs (`labelKey`) **vs** scattered resolvers (`deriveLabel`, `deriveTitleFromSurfaceId`, `describeEffect`, inline plugin strings) **vs** render sites that bypass all of them |
| **B. Observed-state** | the system's observed runtime state | the backend's authoritative tri-state readiness/liveness **vs** a lossy FE re-derivation from leaf fields that defaults "no data" to a concrete value |
| **C. Theme/token** | the design-token vocabulary + current theme | `tokens.css` **vs** each component's CSS referencing arbitrary (incl. ghost) token names with hardcoded fallbacks |

These are the same shape as 548's table (enum decode, God-Object, capability drift, contribution
leak, fragmented provenance). Presentation simply hadn't been looked at through the lens.

---

## 2. The three sub-authorities — correct structure

For each: the *concept*, the *current two-authority defect* (with where it lives), the *correct
end-state*, and the *tier it should reach*.

### 2.A Display authority — *every label is a projection of one declaration* (collapses QB + 504 D6)

**Defect today.** There is no single authority for "how an entity is presented." The backend mints
the canonical declaration — `Operation.presentation.labelKey` (`OperationCatalogClient`),
`Surface.labelKey` (`SurfaceCatalogClient`), i18n strings (`i18n/resourceCatalog.ts`) — but the FE
resolves it through **independent, non-aligned resolvers**: `deriveLabel`
(`shell-v0/components/OpButton.ts:64`, *does* read the catalog), `deriveTitleFromSurfaceId`
(`shell-v0/utils/deriveRichLabel.ts:24`, *ignores* `Surface.labelKey` and pure-humanizes the id),
`describeEffect` (`shell-v0/substrates/effects/describe.ts:22`, a closed switch), and plugin
context-action labels (raw inline strings). Worse, several render sites bypass *every* resolver and
print the raw identifier: the command palette's Operations section renders `ops.*.label` keys
verbatim, Settings rail-customization renders `core.*-surface` ids, the Activity surface renders
raw routes (`navigate justsearch://surface/…`) and `noop`. tempdoc 509 closed this *for the
`<jf-op-button>` consumer only*; the other consumers were never routed through it.

**Correct end-state.** Every renderable entity's presentation is a **typed projection of its one
declaration** — the ContributionManifest / catalog entry that 548 §4.2/§4.3 already establishes as
the single declaration root. The render boundary's *typed seam* — the projector's
return type, a component prop, the `present()` mint — is a **branded `Presented<T>` / `DisplayLabel`
that rejects a raw `string` id/key at compile time** (tier-2; spike-confirmed: a raw string passed
where `DisplayLabel` is required errors). **Caveat (spike-measured, §5):** a Lit template *slot*
(`` html`${…}` ``) is *not* type-checked — interpolating a raw id there compiles — so the template
itself is held by a **tier-4 lint** banning raw-identifier interpolation, with the projector as the
sole sanctioned source. Net: tier-2 at the seam **+** tier-4 lint at the template, not pure tier-2. `deriveTitleFromSurfaceId` and
`describeEffect` survive only as the *declared fallback inside the one projector* (used when the
declaration has no label), not as independent parallel authorities. Plugin labels are declared as
i18n keys in the manifest, not inline strings, so they flow through the same projector. This is the
generalization 509 began: one display projection over the ContributionManifest, consumed by *all*
render sites (palette, rail, rail-customization, activity, op-buttons, context actions).

### 2.B Observed-state authority — *the FE projects the backend's tri-state, it does not re-derive it* (collapses QA + Q5)

**Defect today.** The backend already owns an **authoritative tri-state** model of system state:
`/api/status` returns `readiness` (per-component `state` + `stale` + `source`) and `composites`
(retrieval / aiFeatures) — and tempdoc 550 establishes the one-log / liveness invariant (one
canonical lifecycle record, federated contributors). But the FE **throws this away and rebuilds a
lossy second model**: the `StatusSnapshot` TypeScript type (`shell-v0/utils/statusPoll.ts`) *omits*
`readiness`/`composites`/`stale` entirely (stripped at the type boundary), and each surface
re-derives state from leaf fields with its own null-handling — `BrainSurface.deriveAiState()` maps
absent install-status to "Not Installed", `HealthSurface` renders `?? 0` for missing counts and an
"All systems operational" card from an un-cleared side channel, `AiStateStore`
(`shell-v0/state/aiStateStore.ts`) seeds `installed:false` so **"no data" is representationally
identical to "false."** Result (audit): with the status stream briefly down, Brain claims "Not
Installed" (model loaded), Health claims "All Operational" (retrieval DEGRADED), cards claim "0
files" — three confident-wrong states, each a different lossy authority.

**Correct end-state.** The FE is a **projection/consumer of the backend's readiness/liveness
authority, never a re-deriver.** The complete generated `StatusResponse` — already carrying
`readiness`/`composites`/`stale` — **already exists** in `api/generated` (the wire-types
generation); the lossy *hand-written* `StatusSnapshot` in `statusPoll.ts` is a deletable second
authority. So this is a **tier-1 collapse** (delete `StatusSnapshot`; project the generated type),
not new generation (§5). Every state
value the UI renders is **`Known<T> | Unknown` by construction**, and surface rendering is a
**total function over the tri-state**, so conflating "unknown" with a concrete value ("0 files" /
"Not Installed" / "All Operational") is **unrepresentable** (tier-2) — a surface physically cannot
render a concrete state without a branch for unknown. This observed-state signal joins 548 §4.4's
signal graph as a single `computed` projection that every surface (Brain, Health, StatusDeck,
Search) reads — collapsing the per-surface re-derivation (tier-1). Degradation (reindex-required /
`BLOCKED_LEGACY`, the silent keyword-only fallback of Q5) is then surfaced **by construction**,
because it is part of the projected `composites`, not something each surface must remember to check.
(`AiStateStore`'s existing `computeConnection` staleness logic is the seed of the tri-state; the
work is making *every* field tri-state and sourcing them from the projected backend authority rather
than ad-hoc pollers.)

### 2.C Theme/token authority — *one closed token vocabulary; every variant total* (collapses QC, Q1, Q19, Q21)

**Defect today.** `tokens.css` is the real token authority (with a complete `[data-theme="light"]`
block), but **each component's CSS is a second authority** that may reference *arbitrary* token
names — including **ghost tokens that do not exist** (`--surface-secondary`, `--border-subtle`, …)
written as `var(--ghost, #hardcoded-dark-fallback)`. Because CSS makes `var(--missing, fallback)`
always valid, the hardcoded fallback **silently wins regardless of the active theme** — which is
exactly why selecting Light made the Settings cards' content invisible (dark fallbacks on a light
page), while Dark looked fine. Two further symptoms of the same scattering: theme variants are not
*complete-by-construction* (a variant that fails to define a token a component uses → invisible
content), and theme *application* is scattered (the `data-theme` attribute is set in one code path
while the `.high-contrast` class is never applied anywhere → the High-contrast toggle is **dead**,
and the persisted theme isn't replayed on load).

**Correct end-state.** **One closed token vocabulary** — a `TokenName` authority. Component styles
reference tokens only through a **typed accessor**, so a non-existent token is a **compile error**
(tier-2; spike-confirmed: `token('surface-secondary')` is rejected). **Caveat (§5):** a literal
`var(--ghost)` inside a `css` template is *not* type-checked (it compiles), so banning the raw
`var(--x, #fallback)` escape hatch — the mechanism that hid the ghosts — needs a **tier-4 lint**,
with the typed accessor as the sole sanctioned path.
Every theme variant is **complete-by-construction**: a theme is `Record<TokenName, Value>` (total),
so an incomplete variant fails to type-check (or fails a closure gate). `TokenName` is **generated
from the token source** (tier-3) so the vocabulary cannot drift between definition and use. And
there is **one theme-application writer** — a single `applyTheme(appearance, palette, highContrast)`
that owns *all* theme-affecting DOM state (`data-theme` attribute + palette `@layer` + high-contrast
class), invoked from one place on **both** initial load and change (tier-1 collapse of the scattered
application paths) — which structurally fixes the dead toggle and the not-replayed-on-load bug.

---

## 3. Long-term prevention (so it never relies on memory)

Mirror 548 §5: for each sub-authority reach the strongest tier that applies, default to collapse,
and back the type-level guarantee with an enforcement primitive **that already exists** so a future
change can't regress it.

| Sub-authority | Tier-1 collapse | Tier-2 unrepresentable | Tier-3 generate | Tier-4 gate (existing machinery) |
|---|---|---|---|---|
| **A Display** | one display projector; delete bypass render paths | `Presented<T>` / `present()` at the resolver+prop seam (spike-confirmed); **the Lit `html` slot itself is NOT type-checked** → held by the tier-4 lint | — | extend `eslint.config.js` `no-restricted-imports` (the *shipped* wire-types-barrel + registry-import bans are the exact pattern) to ban raw-id interpolation; a `presentation-purity` discipline-gate (530) |
| **B Observed-state** | one `computed` observed-state signal; delete per-surface re-derivation **+ delete the lossy `StatusSnapshot`, project the generated `StatusResponse`** | `Known<T> \| Unknown` total rendering | — *(already generated — `StatusResponse` exists; this is collapse, not generate)* | execution-surface-style register (553) declaring the FE state projection; gate that the FE consumes the generated status type, not a hand-written one |
| **C Theme/token** | one `applyTheme` writer | typed token accessor (ghost ref = compile error) | `TokenName` union generated from the token source | a `theme-token-closure` gate (530): every variant covers every `TokenName`; ban `var(--x,#fallback)` |

**The meta-point (548 §5.2):** each gate's *coverage* must itself be a projection of a catalog —
the consumer-drift gate (531) already made its `slots.json` coverage a projection of the substrate
universe — so a newly-added surface, token, or state field cannot silently escape the gate. The
prevention is not "remember to register"; it is "the registry enumerates from the authority."

---

## 4. What already exists to build on (extend, don't reinvent) + leverage order

The correct design is **mostly collapse + composition, not greenfield.** Already-shipped pieces to
project from:

- **A:** the ContributionManifest + multi-axis Provenance (548 §4.2/§4.3, 521/507); `<jf-op-button>`
  + `deriveLabel` (509) as the proven partial projector; the aggregate render dispatch (511) as the
  place a presentation projection plugs in.
- **B:** `AiStateStore` + its tri-state `computeConnection` seed (508); the proto
  `readiness`/`composites`/`stale` (already emitted — only stripped FE-side); 550's one-log/liveness
  authority; 548 §4.4's signal graph as the home for the projected signal.
- **C:** `tokens.css` (complete light/dark token sets already exist) + `themeState.applyTheme`.
- **Enforcement (all three):** the discipline-gate kernel (530), consumer-drift (531),
  execution-surface register (553), and the shipped ESLint barrel/registry bans.

**Leverage order** (sequencing only — this doc is theory, not a build plan): **B (observed-state)**
first — highest user-trust payoff and it makes degradation honest by construction; then **A
(display)** — broadest reach, mostly mechanical collapse onto an existing projector; then **C
(theme)** — **the largest migration (codebase-wide: 40 files / 217 refs; §5)** and the
highest-visibility breakage when wrong.

---

## 5. De-risk pass (2026-05-28) — measured reality + confidence

A confidence pass validated the load-bearing hypotheses before anything is built on this doc —
verified by the author (greps + direct reads + a throwaway TS spike), not by subagent report.

**Tier reality (the central correction).** A TS spike measured whether "unrepresentable-by-type"
actually holds in this Lit/TS stack:

- Branded `DisplayLabel` param / `token(name: TokenName)` accessor → **compile error** when handed a
  raw string / unknown token. ✓ tier-2 holds **at the typed resolver/accessor/prop seam.**
- `` html`${rawId}` `` and `` css`var(--ghost)` `` → **compile clean.** ✗ tier-2 does **not** hold at
  the Lit template-interpolation boundary (slots are `unknown`; CSS `var()` is an unchecked string).
- **Consequence:** §2/§3's "raw rendering / ghost token = compile error" is true only *at the seam*.
  The template needs a **tier-4 lint** (ban raw-id interpolation / literal `var(--…)`, with the
  typed projector/accessor as the sole sanctioned source). Net prevention = tier-2 at the seam **+**
  tier-4 lint at the template — strong, but not pure tier-2. 548's own caveat applies: gate/lint is
  ~the weakest tier, so the FE *render surfaces* sit nearer there than the first draft implied.

**Diagnosis — confirmed by direct reading (high confidence):**

- **B:** `statusPoll.ts` carries **zero** `readiness|composites|stale` (tri-state stripped);
  `aiStateStore.ts:115` seeds `installed:false` (concrete, not unknown). **And** the complete
  generated `StatusResponse` (with `readiness`/`composites`) **already exists** in `api/generated` —
  so B is a **tier-1 collapse** (delete the lossy `StatusSnapshot`, project the generated type), the
  most feasible outcome, not the "generate" the first draft assumed.
- **C — upgraded:** the 5 ghost tokens are **defined nowhere** in the FE yet used **217× across 40
  files** with uniformly **dark** fallbacks. So *every non-Dark theme is broadly broken*, not just
  Settings. C is a **codebase-wide** defect and a **large** migration — not "self-contained / low
  effort."
- **A:** the resolvers exist across ~14 files; the bypassing consumers (palette / rail-customization
  / activity) are confirmed live. Moderate consolidation.

**Blast radius (corrects "mostly composition"):** A ≈ moderate (consolidate ~14 resolver files + 3
bypass sites); B ≈ small–moderate (~9 status-consuming files; the target type already exists →
collapse); C ≈ **large** (40 files / 217 refs to migrate off raw `var()`).

**Still unverified / lower confidence (residual risk):**

- **B production frequency.** The confident-wrong states were *triggered* by 545's dev SSE drop. The
  *code path* (concrete defaults) is transport-independent and would recur on any production stream
  loss, but this was **not reproduced in the packaged app.** Design holds; urgency is inferred.
- **C hidden complications.** A 40-file migration may surface per-component layout assumptions baked
  into the dark fallbacks; not individually inspected.
- **Ergonomics.** The spike proved the types *work*; it did not prove the `Presented<T>` /
  typed-token authoring ergonomics are pleasant at scale — a real adoption risk for a large migration.

**Net confidence.** Lens + directions: high. Diagnosis: high (now first-hand). The "unrepresentable
/ prevents long-term" guarantee: **medium, and honestly bounded** — tier-2 at the seam, tier-4 lint
at the template; real but not absolute. C is bigger, and B is more feasible, than the first draft
implied.

---

## Appendix — empirical grounding (the 2026-05-28 browser audit, condensed)

The correct design above is not speculative; it is the structure that would have prevented a live
audit's 22 findings. Method: drove the real frontend in Chrome against a real backend with the LLM
active; 12 surfaces + shell. The findings map onto the three sub-authorities (this *is* the
proof-by-example that presentation has the two-authority defect):

**→ A. Display authority.** Q3 command palette shows raw `ops.*.label` keys; Q6 Settings
rail-customization shows raw `core.*-surface` ids; Q7 Activity shows raw routes / `noop` /
URL-encoded payloads; Q4 the nav rail has no accessible labels at all (no a11y-tree presence);
Q16 raw model id "Qwen_Qwen3.5-9B"; Q18 "(§4.E …)" internal doc-ref in a theme description. (= 504's
D6, internal-state-leaked, made structural.)

**→ B. Observed-state authority.** Q2 disconnected surfaces fail in opposite directions — Brain
"Not Installed" (fail-closed) while Health "All Operational" (fail-open) while cards show "0 files"
(zero-as-unknown); Q5 semantic search silently degrades to keyword-only with no plain signal,
though the backend composite already says retrieval = DEGRADED.

**→ C. Theme/token authority.** Q1 selecting the Light theme renders Settings card content
invisible (ghost tokens + hardcoded dark fallbacks); Q19 the High-contrast toggle is functionally
dead (class never applied); Q21 native date inputs unstyled in light.

**Localized / polish findings** (Q8 raw search-diagnostics shown to all users — *not* advanced-gated;
Q9 inspector renders raw markdown source though `<jf-markdown-block>` exists; Q11 no snippet term
highlighting; Q10 Chat-vs-Agent overlap; Q12 inspector persists across surfaces; Q13 "Lit chrome"
dev pill shipped to production; Q14/Q15/Q17/Q20/Q22) are real but do not change the structural
thesis; several (Q9, Q11, Q13) are localized correctness items that a scoped slice handles directly.

**Dev-environment caveat.** The audit ran under tempdoc 545's known Vite-proxy/SSE/CDP hang; the
status-stream-down conditions that exposed the B-cluster were partly induced by that dev defect, but
the *design* concern is real — any transient stream loss in production (startup, worker restart,
reconnect) reproduces it. To make the audit possible, 545's one-line dev-only fix (`agent: false` in
`modules/ui-web/vite.config.js`) was applied and is **left uncommitted** — it is not part of this
design and should be landed or reverted separately.

---

## Remediation outcome (implemented)

The three single-authority structures were implemented on `worktree-557-presentation`
(not merged), each slice landed as a verified increment (typecheck + `test:unit:run`
+ gates green per commit).

**A — display authority.** `shell-v0/display/present.ts` is the one projector:
`present(ref: EntityRef)` → branded `DisplayLabel`, with kinds
`operation | surface | effect | resource | condition | route`. Every render consumer
routes through it (OpButton, Shell rail/breadcrumb/doc-title, SettingsSurface
rail-customization, RowActions, ResourceView, HealthLitView, AdvisoryInboxDrawer,
healthEventActivityRow, EffectAuditLog, AiActivityDigest, MacroDryRun, the Activity
timeline in `ActionLedgerClient`, and the Health "Fixable now" panel). Producers
(operation→action registration, CommandPaletteProjection) keep resolving their own
keys — `present()` is a render-time projector. The surface case is the *complete*
authority (catalog `labelKey` → id-derived). Gate: `check-presentation-purity.mjs`
pins the wiring; the original ESLint `ops.*.label` ban stands.

**B — observed-state authority.** `aiStateStore` carries a 4-state `ConnectionPhase`
(`connecting | connected | stale | disconnected`), one `computeStaleness()` consumed
by both phase + connection (dedup), tri-state `Maybe<T>` index/runtime fields, and the
last-known raw `status`/`inference` snapshots. `stale` retains last-known values
(never wiped) and maps to `degraded`. HealthSurface dropped its own `/api/status` poll
and reads the store (B7); its operational verdict is driven by `readiness.composites`
+ `schema.reindexRequired` (B2), never fail-open.

**C — theme/token authority.** Codegenned closed `TokenName` vocabulary
(`gen-token-names.mjs` → `token-names.generated.ts`, 155 tokens) + typed `token()`
accessor. **C3 was re-scoped**: the original "migrate `var()` → `token()`" idea is
incompatible with Lit's `css\`\`` tag (it rejects `${string}` interpolation; `var()` is
literal CSS there, and the closure gate already guarantees name-safety). The real
single-source win — implemented — is stripping the 964 dead hardcoded fallbacks
(`var(--x, #fallback)` → `var(--x)`) so tokens.css/the palette is the only source; the
`strip-token-fallbacks.mjs --check` gate bans their reintroduction. One appearance
writer in `themeState` (`applyAppearance` + `restoreAppearanceOnBoot`) replays
data-theme + high-contrast on boot (previously only on Settings mount). `token()`
remains for JS-context refs.

**D — review + live.** Independent review (reviewer ≠ committer) returned
request-changes on one surviving Q7 leak (Health "Fixable now" rendered the raw
`conditionId`) + a test that pinned it; both fixed (new `condition` projector kind,
assertions flipped, gate pin added), plus a LOW closure-gate palette-regex fix. Live
render confirmed via `jseval ui-shot` (Playwright): Light theme renders with visible
content (the original §C break) and the StatusDeck shows real tri-state observed-state.
The Claude browser extension was unavailable in-session; rail-nav ui-shot steps hit a
pre-existing selector issue (logged to observations). Branch left ready for review; not
merged.

Gates added/standing: `theme-token-closure`, `presentation-purity`,
`gen-token-names --check`, `strip-token-fallbacks --check`.

---

## Phase 2 — closing the prevention-strength + user-visible gaps

A theoretical re-audit found phase 1 landed at **collapse + partial gate**, short of the
invariant's deepest clause (unrepresentability). The tempdoc's §5 already conceded tier-2
(compile-error) is **infeasible inside Lit `html`/`css` templates** (slots are `unknown`; `var()`
is an unchecked string). So phase 2 pushes each authority to the strongest *achievable* tier —
**tier-1 collapse + a tier-4 gate whose coverage is the full file/catalog universe** (the §5.2
meta-point) — and closes the one user-visible hole. The honest ceiling is recorded, not papered
over.

- **A (display).** A new full-file-scan rule in `check-presentation-purity.mjs` bans importing the
  label resolvers (`deriveTitleFromSurfaceId`, `describeEffect`) anywhere but the projector + their
  defining modules — so a NEW file that bypasses `present()` is caught automatically (not a
  hand-pin). The broad raw-id-text ban is **deliberately omitted** (false-positive-prone: `core.*-surface`
  / `justsearch://surface/` are legitimate id/route bindings) — the catchable vectors are the
  resolver-import ban + the `ops.*.label` text ban; runtime-data leaks rest on review + present()'s
  branded return at the TS function-call seams.
- **B (observed-state).** A new `check-observed-state-collapse.mjs` gate pins `StatusSnapshot` to the
  generated `StatusResponse` alias and bans any local `interface`/`type … = {…}` status redefinition.
  StatusDeck dropped its second `statusPoll` subscription (reads `aiState.status`). The **Q5 hole is
  closed**: `SearchSurface` surfaces `retrieval = DEGRADED` / `reindexRequired` as a banner, projected
  from the one authority.
- **C (theme).** `applyAppearance({theme, highContrast, paletteId})` is now the **single writer** for
  all three theme DOM layers; every change-path + boot + the dev hot-reload route through it. The
  closure gate also enforces **base-`:root` totality** (every referenced design token resolves in the
  default theme). Out of scope (documented): per-palette `Record<TokenName,Value>` totality — the
  layered cascade makes partial palettes correct; and the typed accessor for `css\`\`` (Lit limit).

**Verification.** typecheck clean; 2234 unit tests green; **five gates** green
(`presentation-purity`, `theme-token-closure`, `observed-state-collapse`, `gen-token-names --check`,
`strip-token-fallbacks --check`). Independent review (reviewer ≠ committer): **approve-with-nits**;
the actionable nits (N1 `type`-redef hole, N2 dev-bypass, N3 boot-merge test, N5 parser note) were
fixed. **Live browser batch (model loaded)**: the B3 banner renders against the *real* degraded
backend composite in dark + light; C1 theme switch + palette **persist across a full reload** via
`restoreAppearanceOnBoot` (boot-replay through the one writer); StatusDeck renders from its single
subscription. Branch left ready for review; not merged.

---

## Remaining work & honest status (post-phase-2)

The *structural core* of all three authorities is built, independently reviewed, and live-validated,
and two extra defects surfaced during validation were fixed (see below). But the work is **not
"prevented forever" yet** — the gaps below are recorded so the doc does not overclaim.

**P0 — the prevention is not enforced (the biggest gap).** The five gates
(`check-presentation-purity`, `check-theme-token-closure`, `check-observed-state-collapse`,
`gen-token-names --check`, `strip-token-fallbacks --check`) **are wired into nothing** — no CI
workflow, no gradle `:verify`, no `package.json`, no pre-merge runner. They only run when invoked by
hand. An unwired gate is memory-reliant, which contradicts this doc's whole "never relies on memory"
thesis: until they are registered (preferably under the discipline-gate kernel, tempdoc 530, *not* a
parallel ad-hoc mechanism — that would itself be a second gate-registration authority), the entire
tier-4 layer is theoretical.

**P1 — the theme authority is only partially closed, and the residual is un-gated.** C fixed ghost
tokens and stripped dead `var()` fallbacks, but components still carry **raw `rgba(...)`/hex literals
that bypass tokens entirely** (e.g. StatusDeck `.pill` backgrounds, HealthSurface `card-icon-box`
rgba, the unstyled date inputs = Q21). The closure gate only checks that `var()` refs are *defined* —
it cannot see a hardcoded color. So an un-themed, un-gated population of colors persists; migrating
them to tokens (and a gate that flags raw color literals in `css\`\``, with an allowlist for genuine
non-token translucency/shadows) is real remaining C work — same class as Q1, only partly closed.

**P2 — documented ceilings (stack-limited, not bugs).** Tier-2 is infeasible inside Lit templates
(§5): raw-id interpolation isn't statically catchable, the `DisplayLabel` brand is non-load-bearing
in templates, and the `token()` accessor can't reach `css\`\``. The plugin-SDK i18n-key migration
(`registerCommand(label)`) is deferred. These are the honest enforcement ceiling, not open bugs.

**P3 — original audit findings still open (~13 of 22).** Q4 (rail a11y, partial/unverified), Q16
(raw model id), Q18 (theme-description doc-ref), Q21 (date inputs in light → P1), and the polish
cluster Q8–Q15/Q17/Q20/Q22. Scoped out of the structural design; still open product issues.

**P4 — verification breadth.** B3 *banner-hidden-when-healthy* was never live-confirmed (the dev
backend is permanently degraded; only the unit test covers absence); Sepia / High-Vis / the
high-contrast toggle were validated *before* the C1 refactor, not after; the packaged Tauri shell was
never tested (all validation was dev-server + Chrome).

**P5 — closure mechanics.** Not merged (pre-merge `gradlew build` + merge outstanding); the
uncommitted 545 dev-proxy fix and two stray `synonyms.*.txt` changes still sit in the worktree; the
`observations.md` rail-nav entry was corrected (it was the TaskList flood, now fixed — see below).

**Defects found + fixed during validation (not in the original 22).**
- **The TaskList flood (high-severity, was in-frame the whole time).** `<jf-task-list>` rendered
  *every* task with no height bound / no row cap; the indexing-jobs bridge projects one task per live
  backend job, so a reindex (100+ jobs) grew the fixed overlay up the entire left edge over the nav
  rail. Fixed presentation-only: running-first, cap to 5 rows + "+K more", `.panel` `max-height` +
  scroll. **Meta-lesson:** I *saw and narrated* this defect across multiple validation passes but
  dismissed it as "pre-existing / not my scope" and mis-logged its cause — the exact
  "normalize the broken default" failure mode this doc warns about. Live UX validation must judge the
  *whole screen*, not just "is my change working."

### De-risk pass (post-phase-2, read-only + throwaway probe) — measured confidence

Before implementing any remaining item, a confidence pass measured the two uncertain ones:

- **P0 (wire gates) — confidence now HIGH.** The repo has two enforcement tiers: the heavy
  discipline-gate **kernel** (`scripts/governance/gates/<id>/`, ratchet gates) and a **lighter
  `scripts/ci/check-*.mjs` tier** wired as plain `ci.yml` steps (`check-ui-cycles.mjs`,
  `check-workflow-triggers.mjs`, …). My five gates are boolean checks in the lighter tier → the
  correct home is **5 `ci.yml` steps** inserted after the "UI cycle gate" (`run: node
  scripts/ci/check-<name>.mjs`, gated on `steps.changes.outputs.ui_web || …all`) + the CLAUDE.md
  pre-merge list. **No kernel registration** (that would be the wrong shape + a second gate-authority).
  Mechanical.
- **P1 (raw color) — confidence now MEDIUM, with a concrete shape.** A throwaway categorizing scan
  measured **312 raw color literals**: **34 (11%)** are `var(--known, …)` dead fallbacks the C3
  strip *missed* (its regex can't span parens) — mechanical/safe to strip; **82 (26%)** are
  neutral black/white/gray (shadows/overlays) — mostly legit; **196 (63%)** are colored accent
  literals — the real leaks, but they cluster on **~5 accent hues × ~6 alpha levels** (teal=
  `--accent-tint`, red=`--accent-danger`, amber=`--accent-warning`), NOT 196 bespoke colors. So the
  migration is a **bounded token-design decision** (alpha-graded accent tokens) + mechanical mapping,
  and a gate scoped to *colored* literals (ignoring the 82 neutrals) has ~0% false positives (a naive
  "ban all bare color" gate is 29% FP — the neutrals — so it must key on non-neutral hue). P1 splits
  into: extend the strip for the 34; define alpha-graded accent tokens; map the 196 per-component
  with multi-theme visual review; add the colored-only gate.
- **Q4 (rail a11y) — code-verified MEDIUM-HIGH.** The rail renders real `<button>` elements with
  `aria-label`/`title` via `present()` (`Shell.ts` ~2214) → accessible names exist. The original
  finding is likely stale/pre-flood-fix; a live a11y-tree read remains a 2-min confirm.
- **P4 (B3 banner-hidden) — env-blocked, accept unit test.** `retrieval=DEGRADED` on the dev box is
  driven by `lambdamart.not_configured` (permanent — no GPL reranker configured) + transient
  `chunk_embedding.in_progress`; a healthy composite isn't inducible here, so the *hidden* path
  can't be live-confirmed on this stack. The unit test (banner absent on READY) is the coverage.

### Closure (2026-05-29) — Phases 4 + 5 resolved

The remaining-work list above (P0–P5) and the Phase-4 decision-gated items were executed
end-to-end. Net state at closure (branch `worktree-557-presentation`, ~42 commits ahead of `main`,
**not merged** — merge is the user's call):

- **P0 (prevention now enforced).** The six FE gates are wired as `ci.yml` steps (after the UI cycle
  gate, gated on the `ui_web` path filter) and listed in CLAUDE.md's pre-merge checks. No kernel
  registration (deliberately the lighter `scripts/ci/check-*.mjs` tier, not a second gate authority).
- **P1 (theme/color authority closed).** `strip-token-fallbacks` extended for paren-spanning dead
  fallbacks (34); 24 alpha-graded accent tokens defined; 196 colored literals migrated to tokens
  per-directory with multi-theme browser review; `check-color-tokens.mjs` bans bare *colored* literals
  in `css\`\`` (neutrals allowlisted). Light-theme spot-check at closure confirmed no regression post-C1.
- **P2 (plugin-SDK i18n shipped, not deferred).** `Command.labelKey` + `commandLabel()` route plugin
  command labels through `present({kind:'resource'})`; optional 4th `labelKey` param threaded through
  the registration contract (back-compat: raw `label` still works). Legacy `searchCommands` updated to
  match the resolved label (review finding F2).
- **Q-cluster.** Q8 (uiMode store + advanced-gating), Q9 (inspector markdown), Q11 (snippet highlight),
  Q12 (inspector closes on surface change) — done + browser-verified earlier. **Q10** resolved as the
  *surface-label single-authority* root cause: all 13 CorePlugin labelKeys were on the unbacked
  `surface.*` namespace and silently id-derived; aligned to the catalog's `registry-surface.*`
  convention so the rail shows authored labels (Chat / AI Brain / System Health). Decision: keep Agent
  and Chat as separate surfaces (browser-validated distinct). The deeper Agent⇄Chat consolidation
  question + missing token-editor/command-palette catalog entries are logged to `observations.md`.
  Q14/Q15/Q17/Q20/Q22 had no preserved descriptions; a fresh browser re-audit found no new defects.
- **P4 (verification breadth).** Light + Dark validated post-C1 at closure; B3 banner-hidden remains
  unit-test-only (dev backend permanently degraded); packaged Tauri shell still untested.
- **P5 (closure).** Pre-merge `gradlew build -x test` → BUILD SUCCESSFUL. Independent review
  (reviewer ≠ committer) of P0/P2/Q10 → APPROVE-with-nits; both nits (F1 stale comment, F2 legacy
  fuzzy path) fixed. Stray `synonyms.*.txt` working-copy changes are CRLF→LF noise (no content diff),
  left uncommitted. The 545 vite fix sits in the *main* checkout's working tree, not this worktree.
  Full FE unit suite green (2244); all gates green. Dev settings restored to Advanced / Dark / Default.

### Merge-prep + browser re-audit (2026-05-29, second pass)

**Merge integration into `main` (prep done, not merged).** `main` had advanced **140 commits**
(548/550/543-fwd FE work) since this branch's 2026-05-27 base, so the merge is a *diverged* one, not
a fast-forward. Merged `main` *into* the branch in the worktree; resolved **10 conflicts** (docs =
union; ActionLedgerClient = my `present()`/`opLabel` gate label + main's new grant/effect/index
branches; AiActivityDigest/MacroDryRun/EffectAuditLog = main's `<jf-effect-line>`, which routes its
label through `describeEffect` — the same authority `present({kind:'effect'})` wraps, so
single-authority holds; ElicitHost/EffectAuditLog/MacroDryRun CSS = main's native `<dialog>` refactor;
TaskList = took main entirely — **550 Thesis III bounded projection supersedes the 557 cap-5 stopgap**
for the same flood). Two post-merge reconciliations: main's `EffectLine` imported `describeEffect`
directly (a projector bypass the purity gate caught) → routed through `present()`; main's new code had
**40 `var(--x,#fallback)`** the strip-gate bans → ran the codemod. **The branch now contains all of
main (45 ahead / 0 behind), so the eventual branch→main merge is clean/fast-forward.** Verification:
typecheck clean · all 6 gates green · **2296** unit tests · `gradlew build -x test` SUCCESSFUL · browser
on :5180 (rail, palette labels, native-`<dialog>` audit log, `<jf-effect-line>`, no flood). **Not
merged** — blocked on 3 working-tree blockers in `main` I must not clear unilaterally: uncommitted
`vite.config.js` (545 fix), untracked `docs/tempdocs/557-…md`, untracked `_spike557/`.

**Browser re-audit of the original 22 (2026-05-29).** Drove the merged FE on :5180 with the LLM
active. **15 of the 17 *described* issues are fixed and verified**: Q1 (Light content visible), Q2
(Health shows "Service degraded", not fail-open), Q3 (palette human op labels), Q4 (rail aria), Q5
(degradation banner), Q6 (Settings rail-customization human labels), Q8 (raw trace Advanced-gated), Q9
(inspector Answer renders markdown bullets via `jf-markdown-block`; Preview stays raw by design for
line/citation), Q10 ("Chat"/"Agent" distinct), Q11 (snippet highlight), Q12 (inspector closes on nav),
Q13 (no dev pill), Q16 (underscore gone), Q18 (clean theme descriptions), Q19 (high-contrast applies).

**Still broken (browser-confirmed): Q7 — raw routes / `noop`.** Two paths emit raw values:
`describeEffect` navigate case (`Navigate to ${effect.to}` — raw route) feeding the Effect Journal,
**and** `ActionLedgerClient`'s effect-fold (`ActionLedgerClient.ts:341,137`) which flattens a navigate
effect to `effectKind:'navigate'`+`subject:effect.to` and renders raw `"navigate <route>"` / `"noop"`
in the Activity surface (bypassing the projector). The catalog-aware humanizer **already exists** —
`present({kind:'route'})` → `routeToSurfaceId` → `surfaceLabel` (yields "Search"/"Chat", not the
Q10-jargon). **Blocker:** `present` imports `describeEffect`, so `describeEffect` can't import
`present` back (cycle) — the fix needs the route-humanizer **extracted** into a cycle-free shared
module that both import.

**Partial / unverifiable.** Q2 universality (every value `Known|Unknown`) is mechanical to extend but
hard to verify (needs an induced stream-down the dev SSE won't give reliably). Q16 "Qwen Qwen"
doubling is the model's real HF org+name (`Qwen_Qwen3.5-9B`) — cosmetic; **recommend won't-fix**.
**Q14/Q15/Q17/Q20/Q22 — descriptions are unrecoverable**: searched repo + git history, only unrelated
`docs/market-analysis/` + SVG matches; the originals were never written down. Blocked on re-spec or a
fresh-audit re-baseline.

**Confidence.** Q7: HIGH to fix + HIGH to verify (browser-checkable on Activity + Effect Journal); the
risk is *cleanliness* (cycle-free extraction, purity-gate-safe import, test churn on pinned label
strings, the noop humanize-vs-filter judgment) not correctness. Q14-cluster: ~zero (can't fix what
isn't defined). Q2 universality: MEDIUM to fix, LOW to live-verify (env-blocked). Q16: trivial but
recommend won't-fix.

### Final outcome (2026-05-29, third pass)

- **Q7 — FIXED.** The route-humanization went into the **display layer** (it turned out `describeEffect`
  is documented *pure*, so it can't consult catalogs, and `present` already imports it → a cycle the
  other direction): `present()`'s effect case special-cases navigate → `surfaceLabel(routeToSurfaceId(
  eff.to))`, and `ActionLedgerClient`'s effect-label branch routes a navigate's subject through
  `present({kind:'route'})` (noop → "No-op"). No extraction/new-module/cycle needed; `describeEffect`
  stayed pure (ui-cycles count unchanged at 19). **Browser-verified**: the Activity surface and the
  Effect Journal both read "Navigate to Search / System Health / Settings …" + "No-op" — no
  `justsearch://` leak. 2298 unit tests + all 6 gates green.
- **Dropped / skipped (user decision):** Q14/Q15/Q17/Q20/Q22 closed *not-reproducible* (specs lost);
  Q2 tri-state universality *deferred* (env-blocked live-verify); Q16 doubling *won't-fix* (model's
  real HF id). All recorded in `docs/observations.md`. Lower-visibility `describeChange`/MacroDryRun
  diff route leak logged as a minor follow-up.
- **Merge:** the branch (containing all of main) merges into `main` after clearing the 3 stale
  working-tree files in `main` (vite 545 fix, untracked 557 tempdoc, `_spike557`).
