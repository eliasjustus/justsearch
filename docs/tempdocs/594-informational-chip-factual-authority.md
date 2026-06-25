---
title: "594 — Display authority, deeper: the VALUE-PROJECTION half (factual content of informational chips). A 558/595-style depth round on tempdoc 557's FIRST projection (Display). The deepened finding (§9): present() is the Display authority's NAME half — it projects every label from one declaration — but there is NO VALUE half, so a displayed runtime/build fact is sourced+formatted ad hoc at every site: a chip BAKES a literal ('Embeddings 768-d', 'GPU cuda12'), a status-bar metric rolls bespoke per-metric value code, a health card formats inline. The chip literal (zero binding to the value it names) is the degenerate extreme of one missing authority. The correct long-term structure is the symmetric completion of 557 §2.A: ONE Fact authority that resolves a fact-ref into (name, value, presence, confidence), with build-time facts GENERATED from the SSOT catalog and runtime facts PROJECTED from the 587 host-capability substrate via aiStateStore. Surfaced by the 593 walkthrough ('Embeddings 384-d' chip vs the real 768-d model)."
type: tempdocs
status: "IMPLEMENTED & MERGED to main (merge 1ecfb9b9d, 2026-06-17) — all §4 Moves (1a/1b/2) + the full §11 UX (5/5 principles) shipped + live-browser-verified; see §15 as-built. The design-theory passes below are dated history. ── design-theory (correct end-state stated; feasibility/phasing deliberately out of scope per the 557/587/595 genre). Three passes complete: (§8) investigation + critical analysis — fact-sources traced, the build-time-constant vs runtime-fact authority split established; (§9) long-term design theorization — the value-projection half of the Display authority (the Fact catalog + projector), reusing the 587 capability substrate + the SSOT-codegen pattern, with the 595 boundary (leaf fact vs derived verdict) drawn; (§10) pre-implementation de-risking — every load-bearing assumption verified against the generated FE wire types / gates / codegen wiring, one wiring gotcha surfaced (declaration-based `signature()`), confidence 8/10; (§11) live UI inspection — the running chip strip shows live/static/capability facts as visually-identical unsourced grey pills, so correctness (Move 1a/1b) is necessary but insufficient: the frontend design adds provenance-/confidence-legible rendering, a ternary presence state, an altitude-aware on/off policy, and fact-priority overflow; (§12) effort estimate calibrated to repo cadence (full line ~3-4 focused days, structural core ~1.5-2 days); (§13) cross-tempdoc coordination — no work BLOCKS 594; one ordering preference (595's transition authority before 594's §11 unknown-state) + one convergence point (594's Fact catalog as 596's capability source), both in the UX/observed-state layer; (§14) confidence pass #2 (UX/coordination layer) — U1-U8 resolved mostly favorably: real mount code-confirmed, GPU confidence vocab small+closed, provenance affordance is a REUSE of the existing `title`/ProvenanceChip pattern (measure-safe), altitude-aware policy feasible with one hint field, ternary presence standalone (not blocked on 595); gotchas: Fact catalog needs own labelKey, 594 binds fine-grained `status.worker/gpu.*` (not the coarse `capabilities` graph 596 uses → 596-coupling softened), live visual still unexercised. Full §9+§11 line confidence 7.5/10, structural core 8/10. An interim symptom patch (384-d → 768-d) landed on a working tree; this doc is about the STRUCTURE that let it ship wrong, not the one literal."
created: 2026-06-16
author: agent
extends: tempdoc 557 §2.A (Display authority — the present() projection + the prevention ladder Collapse > Unrepresentable > Generate > Gate). THIS DOC EXTENDS 557, it does not re-derive it. The §9 deepening: 557 §2.A built the NAME half of the Display authority and never built the VALUE half — this doc theorizes it.
category: frontend / ux / design-theory / single-authority / presentation / display-projection
related:
  - tempdoc 557 (presentation = three single-authority projections: Display / Observed-state / Theme; the ladder). The framework this gap lives in; Display is its FIRST projection and present() is only its name half. OPEN.
  - tempdoc 595 (Observed-state authority, deeper — the system-health verdict + missing 'transitioning' state). THE SIBLING: 595 is the depth round on 557's SECOND projection (Observed-state), this is the depth round on its FIRST (Display). §9.5 draws the boundary: 594 projects a LEAF fact's value; 595 derives a VERDICT/phase. Same lineage, same 558 shape, adjacent facets. **Soft upstream dependency** for §11.3 #3 (the ternary 'unknown/reconnecting' chip state should consume 595's transition authority, not reinvent it — §13.1). OPEN.
  - tempdoc 596 (Operability authority, deeper — typed availability + reachable reason). THE THIRD SIBLING (Display/Observed-state/Operability depth rounds on 557). 596 §5 scopes the chip strip OUT ("Owned by 594"); but 596 §9 *composes 594's projected facts* as unavailability-reason inputs — so 594's Fact catalog is the natural single capability authority 596's reason-projector should read (§13.2). 596 is implementation-DECOUPLED today (reads aiStateStore raw, its §10 C4). OPEN.
  - tempdoc 597 (search result-count truthfulness). Picks up a 594-§5-DEFERRED item (the '136 results' / facet-count divergence) but is a DIFFERENT lineage (search-response contract / `SearchTrace`, not the Display authority) — no interference with the chip strip (§13.3). OPEN.
  - tempdoc 587 (host-capability sensing substrate — the backend (value, source, confidence) Effective view for GPU/CUDA/host facts). THE RUNTIME-FACT SOURCE: §9.2/§9.4 — a host-fact chip is the FE terminus of 587's capability substrate, which already carries provenance+confidence to the wire (so the honest chip surfaces that, never a fabricated 'cuda12'). Phase 1 SHIPPED.
  - tempdoc 558 (presentation authority, deeper — the depth-coverage PRECEDENT: "run an audit against merged 557 and still find fresh user-visible instances of the authorities not biting." This doc is round 2 of the same shape). CLOSED.
  - tempdoc 569 (functional-core authored presentation — the DeclaredSurface engine + the liveness/overflow facets this doc dissects; "author declares, engine owns"). SHIPPED.
  - tempdoc 575 (the observed-happening register + gen-liveness-constants codegen — the register+generate pattern §9.2 reuses for the build-time-fact source). SHIPPED.
  - tempdoc 559 (the StatusBarItem/metric registry — name declared on the registry, value still bespoke in StatusDeck; the same value-projection gap one surface over, §9.1). OPEN.
  - tempdoc 593 (UX walkthrough — the live observation that surfaced the 'Embeddings 384-d' chip; §E/§I). CLOSED.
  - tempdoc 504 (systematic UX audit taxonomy — D6 "internal-state-leaked" is the adjacent symptom; this is its inverse: external-fact-FROZEN-into-prose).
  - CLAUDE.md `structural-defects-no-repeat` (one documented silent bug proves the class; 593 documents several — the bar is cleared).
---

# 594 — Display authority, deeper: the value-projection half (factual content of informational chips)

> **Reading order.** §1–§7 are the original move-level proposal (the chip entry point). §8 is the
> investigation + critical analysis against `main`. **§9 is the deepened design-theory centerpiece —
> the correct long-term structure** (the value-projection half of the Display authority); §3's
> "deepened thesis" and §4 are the move-level approximation of it. If you read one section, read §9.

> **What this document is.** A *design theory* for one specific place the JustSearch frontend's
> Display authority does not yet bite — and the correct long-term structure for it. It is **not** a
> new framework. Tempdoc 557 already stated the correct framework (presentation = three
> single-authority projections: **Display**, **Observed-state**, **Theme**) **and it is merged into
> `main`**. This doc inherits 557's invariant and ladder wholesale and adds depth where a live
> walkthrough proved the framework does not yet reach.

> **The meta-finding (why this doc exists).** The 593 walkthrough — run against the merged 557/569
> presentation kernel — still found a user-visible **factually wrong** value on the System Health
> surface: a chip reading **"Embeddings 384-d"** when the shipped model (`gte-multilingual-base`) is
> **768-d** (canonical: `SSOT/catalogs/fields.v1.json` declares the vector field `dimension: 768`,
> two sites). It shipped wrong, survived every CI gate, and is invisible to the 557 purity gates.
> That is not bad luck — it is a structural gap, named precisely below.

---

## 1. The defect, and why it is structural (not a typo)

The wrong value lives in `HEALTH_STATS_BODY.overflow` — the Health surface's "enrichment-capability
strip" (`modules/ui-web/src/shell-v0/themes/builtinPresentations.ts:407`):

```ts
overflow: [
  { id: 'cap-embed',  label: 'Embeddings 384-d', priority: 90, pinned: true },  // ← wrong literal
  { id: 'cap-splade', label: 'SPLADE',           priority: 70 },
  { id: 'cap-rerank', label: 'Reranker',         priority: 60 },
  { id: 'cap-ner',    label: 'NER',              priority: 50 },
  { id: 'cap-gpu',    label: 'GPU cuda12',       priority: 40 },  // ← asserts an accelerator
  { id: 'cap-vec',    label: 'Float32 vectors',  priority: 30 },  // ← asserts vector precision
],
```

Every one of these is a **hardcoded string** that *asserts a runtime fact* — vector dimension,
SPLADE/reranker/NER availability, the accelerator (`cuda12`), the vector precision (`Float32`).
None is bound to the value it names. `"GPU cuda12"` renders identically on a CPU-only host. The
demo carries the same shape (`shell-v0/demo/presentation-demo.ts:327`, "Embeddings 384-d").

**This clears `structural-defects-no-repeat` already**: 593 documents not one but *several*
instances of the same class (the wrong dimension, plus the whole strip's hardware/precision claims),
and 504's D-taxonomy named the adjacent symptom years prior. One silent bug proves a class; this is
a class with multiple living members.

---

## 2. Root cause, dissected against the 569 facet model

`DeclaredSurface` (569 §14) wraps each surface in single-authority facets. The drift guarantee is
**per facet**, and the code comments state it precisely
(`modules/ui-web/src/shell-v0/components/DeclaredSurface.ts`):

| Facet | What the **author** declares | What the **engine** owns | Ladder rung | Drift-proof? |
|---|---|---|---|---|
| `liveness` | a **signal ref** (`'core.retrieval'`) | derives the tri-state from the observed-state authority → *"a faked 'healthy' indicator is **unrepresentable**"* | **rung-2 (Unrepresentable)** | ✅ |
| `overflow` (geometry) | items + **priority** | the **clip** (`OverflowController`) → *"cannot naked-clip the bar"* | **rung-2 (Unrepresentable)** | ✅ (clip only) |
| **`overflow` item *content*** | **`label: string`** — free text | nothing | **rung-0** | ❌ |

The kernel applied rung-2 to the **state** facet (liveness) and the **layout** facet (the clip), but
the **informational content** of a chip — the words `384-d`, `cuda12`, `Float32` — is still a raw
string literal. The 557 ladder (Collapse > Unrepresentable > Generate > Gate) was never applied to
*"a label that states a fact."*

### 2.1 Why the existing 557 gates cannot catch it

The 557 enforcement (`check-presentation-purity.mjs` / `check-observed-state-collapse.mjs`) works by
intercepting **values that flow through render** and forcing them through `present()` / the
observed-state authority. A chip whose fact is a **baked string constant** flows **no value** — there
is nothing for a purity gate to intercept. The fact was *frozen into prose at authoring time*, below
the layer the gates inspect. **That is exactly why "384-d" passed every gate and shipped wrong.**

This is the inverse of 504's D6 "internal-state-leaked": there, real internal state leaks into the UI
unformatted; here, an **external fact is frozen into the UI as un-sourced prose**. Same authority is
missing — Display — pointed the other way.

---

## 3. The thesis

**Narrow thesis (the chip, the entry point).** Extend the rung-2 "unrepresentable fake" treatment
from `liveness` to **informational chips**: an item that asserts a fact declares a **fact ref**, and
the engine fills the value — so `Embeddings {dim}` derives `dim` from its authority and **cannot** be
authored as a wrong literal. Purely-decorative labels stay free text but **lose the right to state
numbers/hardware**. This stays inside 569's "author declares, engine owns" contract.

**Deepened thesis (the real structure — §9).** The chip is one symptom of a larger gap: tempdoc 557
§2.A built `present()` as the Display authority's **name** half ("every *label* is a projection of
one declaration") and **never built its value half.** So every displayed runtime/build *value* is
sourced and formatted ad hoc — the chip bakes a literal (the extreme: a value with *no* source at
all), the status-bar metric rolls bespoke per-metric code, the health card formats inline. The
correct long-term structure is the **symmetric completion of the Display authority**: one **Fact**
projector that resolves a fact-ref into `(name, value, presence, confidence)`, the value-side sibling
of `present()` — with **build-time** facts *generated* from the SSOT catalog and **runtime** facts
*projected* from the 587 host-capability substrate via `aiStateStore`. The chip facet is then this
authority's first adopter; the literal becomes unrepresentable because the factual form is a *ref*,
not a string.

---

## 4. Design (two moves, smallest footprint first)

> **Refinement after the 2026-06-16 investigation (§8).** Move 1 below is re-cut into **1a** and
> **1b** because the strip carries *two* fact-kinds with *two* correct authorities (§8.2): a
> build-time constant (the dimension) belongs to the **SSOT catalog** via the **Generate** rung,
> while runtime/per-host facts (GPU, precision, capability presence) belong to **`aiStateStore`** via
> the **Unrepresentable** rung. The original Move 1 (single observed-state signal for everything)
> mis-routes the dimension — see §8.2. The sub-headings below are annotated accordingly.

### Move 1a — build-time constants via Generate-from-SSOT (the cheap real close for the dimension)

The embedding dimension is a per-build constant whose single authority is `SSOT/catalogs/fields.v1.json`
(`vector.dimension: 768`), **not** a runtime signal (§8.1 confirms it is on no wire). Project it into a
TS constant with a codegen sibling of `gen-liveness-constants.mjs` (whose own header: "the constants ARE
the register — drift is impossible by construction"), so `Embeddings {EMBED_VECTOR_DIM}-d` is un-wrong
by construction with **zero backend change**. This is strictly smaller than the Move 1b path and is the
correct close for the literal that actually shipped wrong.

### Move 1b — runtime facts via a value-bearing chip variant (the Generate/Unrepresentable rung)

Give `DeclaredAdaptiveItem` an optional derived form. Sketch (names provisional, to be settled in
impl):

```ts
// Today (rung-0) — unconditional, renders identically on a CPU host:
{ id: 'cap-gpu', label: 'GPU cuda12', priority: 40 }

// Proposed (rung-2): the author declares a template + a signal ref; the engine fills {value}
// and renders the chip absent when the capability is absent.
{ id: 'cap-gpu', factLabel: 'GPU ({value})', signal: 'core.gpu.accelerator', priority: 40 }
```

> Note (§8.3): "exactly as liveness does" understates this. `LivenessReadout` reads ONE hardcoded
> field (`statusTier`) and `present()` resolves **names only, never values** — there is no
> signal-ref→value resolver today. Move 1b must add one (a registry mapping a signal id to a
> `StatusSnapshot` value-path + formatter). The runtime values themselves already exist on
> `status.worker.*` (§8.1); the **dimension does not** and is Move 1a, not 1b.

- The engine reads `signal` from the **observed-state authority** (the same `aiStateStore` /
  capabilities graph the liveness facet already consumes — 586 confirmed `aiStateStore` is the
  documented single authority the Brain/Health surfaces read) and substitutes `{value}`.
- A capability the host genuinely *lacks* renders the chip absent/greyed (engine-owned), so
  `"GPU cuda12"` cannot appear on a CPU host.
- `label: string` (free text) **remains** for genuinely decorative, fact-free chips. The rule is not
  "no static labels" — it is "a static label may not **state a runtime fact**."

The exact signal ids (`core.embed.dim`, accelerator, precision, SPLADE/NER/reranker presence) are an
impl-phase discovery step; §6 lists the candidate set and the open question of where each value is
already exposed (the embed dimension is in `fields.v1.json` + presumably a status/capabilities wire —
to be traced, per `audit-without-test`, before wiring).

### Move 2 — the gate that makes the rung-0 fake unrepresentable (the Gate rung)

A new FE check (sibling to the 557 purity gates) that scans declared chip/overflow `label` strings
for a **fact-shaped token** with no signal binding, and fails the build:

- dimension: `/\b\d+-?d\b/i` (e.g. `384-d`, `768d`)
- accelerator: `/\bcuda ?\d+\b/i`, `/\brocm\b/i`, `/\bmetal\b/i`
- precision: `/\bfloat ?(16|32)\b/i`, `/\bint8\b/i`, `/\bfp(16|32)\b/i`
- (extensible; the catalog of fact-shapes is itself a small register so coverage is auditable per
  548 §5.2 "a gate's coverage must project from a catalog")

A label matching a fact-shape **must** use the `factLabel` + `signal` form. Decorative labels that
match nothing pass untouched. This is the rung that turns "next stale hardware label" from a silent
ship into a red build — the actual structural close, not the spot-fix.

### Ladder placement

Per 557's ladder, **Move 1 is the real fix** (Unrepresentable/Generate — the value cannot be wrong
because it is derived), and **Move 2 is the backstop** (Gate — catches an author who reaches for a
raw literal). Both, in that priority order. A spot-fix of the literal is rung-0 "patch the symptom"
and is explicitly *not* the close.

---

## 5. Scope boundary (what this is NOT — guarding against over-unification)

The 593 walkthrough surfaced several "FE shows untrustworthy info" findings. Honesty per AHA/YAGNI:
**only the ones that share THIS root belong here.** In/out:

**IN (same root — a fact under Display authority):**
- The `cap-embed` 384-d literal + the whole `HEALTH_STATS_BODY.overflow` hardware/precision strip.
- Any other declared chip whose label states a number/hardware/precision/capability as a literal
  (discovery step in §6).

**OUT (different roots — do NOT bundle; that would be the "coping label" anti-pattern CLAUDE.md
warns against):**
- **Facet count > result count** (593 §8/§10) — a search-response *semantics/labeling* question
  (facets count the matching corpus, results count a page). Possibly correct-but-confusing; not a
  Display-authority drift. Its own finding.
- **Result count capped at exactly 136** (593 §10) — pagination/display-cap. Separate.
- **"Service degraded" header vs "✓ ALL SYSTEMS OPERATIONAL" footer** (593 §C) — an **Observed-state**
  authority gap (two independent health rollups), not Display. Same *family* (557), different facet;
  belongs in a 559/observed-state follow-up, not here.
- **Tasks panel froze its snapshot while open** (593 §5) — a subscription/liveness binding bug, again
  Observed-state, not Display.

Keeping this doc to the single Display-content thesis is the point. The siblings are real but each is
its own trace.

---

## 6. Open questions / decisions for the user (before impl)

1. **Move 1 + Move 2, or Move 1 only?** Move 1 fixes today's strip; Move 2 prevents the next one.
   Recommendation: both (the bug-class is multiply-evidenced). But Move 2 is the larger lift (a new
   gate + its catalog) and could be a follow-up phase.
2. **Signal-source discovery (must precede wiring, per `audit-driven-fixes-need-test`).** For each
   fact-shaped chip — embed dimension, accelerator, vector precision, SPLADE/NER/reranker presence —
   confirm the value is *already* exposed on an observed-state signal the FE reads, or note it needs a
   new status field. The embed dimension: in `fields.v1.json`; is it on `/api/status` /
   capabilities? To be traced.
3. **Decorative-vs-factual judgment call.** `"SPLADE"` / `"Reranker"` / `"NER"` name a *capability*
   (on/off), not a number. Do they count as "fact-asserting" (must bind to a presence signal) or
   "decorative" (a static feature name)? Proposed: a capability *presence* claim is a fact (a CPU host
   with reranker disabled should not show "Reranker"), so it binds. Confirm.
4. **Doc maintenance.** This extends 557; on impl, 557 §2.A gains a sub-section (or a cross-ref) noting
   Display authority now covers chip factual content. ADR-0032 / explanation/27 unaffected
   (no architecture/authority *model* change — Display authority's *reach* widens within the existing
   model).

---

## 7. As-built so far (interim)

- **Symptom patch landed (working tree, not committed):** `Embeddings 384-d` → `768-d` in both
  `builtinPresentations.ts:407` and `presentation-demo.ts:327`. Typecheck clean; 3010/3010 FE unit
  tests green. This corrects the *one* visible literal; it does **not** close the class (the next
  stale dimension/hardware label ships identically until Move 1/2 land). Logged in
  `docs/observations.md` (the strip is entirely hardcoded).
- **Nothing in §4 implemented.** This is a design proposal awaiting the §6 decisions.

---

## 8. Investigation findings & critical analysis (agent takeover, 2026-06-16)

Autonomous investigation of the codebase against the §4 design and §6 open questions. The headline
conclusion: **the thesis is right in shape but mis-assigns the authority for its own headline
defect.** The chip strip carries *two distinct kinds of fact* with two distinct correct authorities,
and §4's single "observed-state signal" framing collapses them. Details below; every claim is
primary-source cited.

### 8.1 Fact-source trace — the answer to §6 Q2 (must-precede-wiring, per `audit-driven-fixes-need-test`)

For each chip, is the real value *already* on an observed-state signal the FE reads
(`aiStateStore` → `status: StatusSnapshot` / `inference: InferenceSnapshot`), or does it need a new
field? Traced through `StatusResponse` (the generated wire type `statusPoll.ts` validates) and the
backend views:

| Chip | Fact kind | Already on a FE-read signal? | Path / source |
|---|---|---|---|
| `cap-embed` **768-d** | **build-time constant** | **NO** — not on any wire | canonical = `SSOT/catalogs/fields.v1.json` `vector.dimension: 768`; nowhere on `/api/status`/`/api/health`/capabilities |
| `cap-gpu` **cuda12** | runtime, per-host | **PARTIAL** — presence yes, version no | `status.worker.gpu` (`GpuDiagnosticsView`) + `status.gpu.cudaFunctional` (`GpuStatusView.java:33`, tempdoc 587); the **"cuda12" version string is NOT exposed** (only a `cudaFunctional` boolean + `driverVersion`) |
| `cap-vec` **Float32** | runtime, per-index | **YES** | `status.worker.vectorFormat.vectorFormatActual` (`VectorFormatView.java`) |
| `cap-splade` **SPLADE** | runtime, per-config | **YES** | `status.worker.enrichment.spladeEnabled` (`EnrichmentProgressView.java:29`) |
| `cap-rerank` **Reranker** | runtime, per-config | **YES** | `status.worker.gpu.rerankerOrtCuda.available` / `rerankerModelPath` (`GpuDiagnosticsView.java`) |
| `cap-ner` **NER** | runtime, per-config | **YES** | `status.worker.enrichment.nerEnabled` (`EnrichmentProgressView.java:30`) |

**The irony that should drive the design:** the *one* fact that actually shipped wrong and motivated
this whole doc — the embedding **dimension** — is the *only* fact **not** exposed on the runtime
wire. Every runtime-varying chip (GPU/precision/SPLADE/reranker/NER) is already reads-from-`status`
material; the dimension is not, because it isn't a runtime-varying quantity at all.

### 8.2 Critical finding A — the headline fact has the WRONG authority (the central correction)

§3/§4 assert one authority ("the observed-state authority ... the same `aiStateStore` the liveness
facet consumes"). But the strip mixes two fact-kinds that do not share a reason to change (AHA — do
not over-unify):

- **Per-host / per-run runtime facts** — GPU present *on this host*, SPLADE/NER/reranker enabled *in
  this config*, vector precision *actually in this index*. These genuinely vary at runtime, exactly
  like liveness tone. Their authority **is** `aiStateStore`. Move 1 fits them perfectly.
- **Per-build constants** — the embedding **dimension** (768). This is identical on every host
  running this build; it does not "observe" anything. Its single authority is the **SSOT catalog**
  (`fields.v1.json`), the same artifact the Lucene vector field is built against. Routing it through
  a *runtime status field* would be authority-laundering: the backend would just re-copy the catalog
  value onto the wire, *re-introducing* the very author→fact gap one layer down (someone still types
  `768` into the view-builder). The drift isn't eliminated, it's relocated.

  The correct rung for a build-time constant is **Generate** (557 ladder), and the codebase already
  has the exact pattern: `gen-liveness-constants.mjs` projects a governance register into both a TS
  and a Java constant so "the constants ARE the register — drift is impossible by construction"
  (its own header); `gen-text-tokens.mjs` / `gen-wire-schema-types.mjs` are siblings. A
  `gen-field-constants.mjs` (or extending an existing generator) emitting `EMBED_VECTOR_DIM = 768`
  **from `fields.v1.json`** makes `Embeddings {EMBED_VECTOR_DIM}-d` un-wrong by construction, with
  **zero backend change**, and is *strictly smaller* than the runtime-signal path §4 implies for it.

  **Recommendation:** split Move 1 into **1a (Generate, from SSOT)** for build-time constants — the
  dimension — and **1b (Unrepresentable, from `aiStateStore`)** for runtime facts. 1a is the real,
  cheap close for the literal that shipped wrong; §4 currently routes it through 1b's heavier
  machinery for no benefit.

### 8.3 Critical finding B — "exactly as liveness does" understates Move 1b's lift

§3 says the chip "declares a signal ref (exactly as liveness does), and the engine fills the value."
That is not what liveness does. `LivenessReadout` reads **one hardcoded field** (`state.statusTier`
→ tone; `LivenessReadout.ts:23-30`) and projects only the *name* via `present({kind:'metric'})`.
And `present()` resolves **labels only — never values** (`present.ts`: every `EntityRef` case returns
a `label`, none reads a snapshot value). There is **no generic `signal-ref → live value` resolver in
the codebase today.**

So Move 1b needs a genuinely new primitive: a registry mapping a signal id
(`core.gpu.cudaFunctional`) to a `StatusSnapshot` value-path + a formatter, plus engine logic to
(a) substitute `{value}` and (b) render the chip **absent/greyed when the capability is absent**
(§4's CPU-host requirement). This is a value-projection sibling of `present()`, not a one-line reuse.
Worth scoping honestly in §4 — it is the larger half of the work, and it is the half §4 under-bills.

### 8.4 Critical finding C — the strongest *live* lie is "GPU cuda12", not the dimension

The dimension was wrong-by-a-number (and is now patched). But `cap-gpu: 'GPU cuda12'` and
`cap-vec: 'Float32 vectors'` are **unconditional** — they render byte-identical on a CPU-only host
with no CUDA and on an int8 index. That is a *wrong-on-this-machine* claim, arguably worse than a
wrong-number, and it is **still live** post-patch. `docs/observations.md:65` already recorded the
sibling "GPU tile honesty" work and explicitly flagged that honest per-component GPU attribution
"requires aggregating embed/SPLADE/reranker/inference per-component cuda status — separate substrate
work." That substrate now **exists** (the `GpuDiagnosticsView` per-component `*OrtCuda` fields from
tempdoc 587) — so Move 1b for `cap-gpu`/`cap-vec` is unblocked and is the higher-severity target.
Caveat from §8.1: the literal **"cuda12"** version token is *not* on the wire (only a boolean +
driver version) — so 1b should render `GPU (CUDA)` / `GPU (CPU)` from `cudaFunctional`, and **drop
the fabricated "12"** unless a version field is added. Don't bind to a value that doesn't exist.

### 8.5 Critical finding D — Move 2 (the regex fact-shape gate) is a defensible backstop with two real risks

As the *Gate* rung (catch an author who hand-types a literal after 1a/1b exist), Move 2 is sound and
consistent with the ladder. Two concerns to settle at impl:

1. **False positives on decorative text.** `/\b\d+-?d\b/i` matches `"2-D preview"`, `"3D"`,
   `"Add"` is safe but `"HD"`-style tokens, a future `"5-day window"` chip → `"5-d"`. A free-text
   regex over author prose is inherently heuristic. Mitigate by (a) anchoring to the *catalog of
   fact-shapes as a register* (§4 already proposes this, per 548 §5.2) and (b) an explicit
   `decorative: true` opt-out escape hatch for a chip that legitimately matches but asserts nothing.
2. **Demo/fixture scope.** `presentation-demo.ts:327` carries the same shape *by design* (it's a
   showcase). The gate must scope to production declarations (or treat the demo as a fixture), or it
   red-builds on illustrative content. Name the scope explicitly (cf. the 557 gates' own scoping).

Net: Move 2 is worth doing **after** 1a/1b, narrow, register-driven, with an opt-out — not a
free-text scan with no escape hatch.

### 8.6 Answers / recommendations for §6

1. **Move 1 + Move 2, or Move 1 only?** Do **Move 1a now** (the cheap real close for the dimension —
   codegen from `fields.v1.json`, no backend touch). Do **Move 1b** for `cap-gpu`/`cap-vec` next
   (highest-severity live lie, substrate already exists). **Move 2 is a follow-up** backstop, narrow
   and register-driven. The bug-class is multiply-evidenced (§1), so all three are justified — but in
   that severity/cost order, not §4's "1 then 2" framing that lumps the dimension into 1b.
2. **Signal-source discovery — done (§8.1).** Dimension: **not on the wire**, derive from SSOT
   (Move 1a). GPU presence / precision / SPLADE / NER / reranker: **on `status.worker.*`** (Move 1b).
   GPU *version* string "cuda12": **not exposed** — drop it or add a field.
3. **Decorative-vs-factual for SPLADE/Reranker/NER.** Agree with the doc's lean: a *presence* claim
   is a fact (a config with reranker disabled must not show "Reranker"), and the binding values exist
   (`enrichment.spladeEnabled` / `nerEnabled`, `gpu.rerankerOrtCuda.available`). So these are
   Move 1b, not decorative.
4. **Doc maintenance.** Unchanged from §6.4, with one addition: if Move 1a lands, note in
   557 §2.A that Display authority's "Generate" rung now covers **build-time** factual content
   (SSOT-derived constants), distinct from the runtime "Unrepresentable" rung (1b) — the two-kind
   split is the durable lesson.

### 8.7 Net verdict on the proposal

The diagnosis (§1-§2) is correct and the bug-class is real and live (§8.4). The thesis (§3) is
directionally right. The single substantive design defect is the **conflation of two fact-kinds
under one authority** (§8.2): the doc's own headline defect (the dimension) is a build-time constant
best fixed by **Generate-from-SSOT**, not the runtime-signal path §4 prescribes — which is both
heavier *and* leaves a relocated drift gap. Recommend re-cutting Move 1 into 1a (SSOT codegen) +
1b (runtime signal) before implementation; the rest of the design stands.

---

## 9. The correct long-term structure (design-theory deepening, 2026-06-16)

> **Genre note** (per 557/587/595). This section states the *correct end-state* at the bar the
> category sets. Feasibility, phasing, and migration cost are deliberately out of scope; major
> refactors are in scope. §4/§8 are the move-level proposal + its critique; **this is the structure
> they are an approximation of.** Current-behaviour claims are cited to `main` (§8.1, the Explore
> trace, and the file:line citations inline); re-verify before relying.

### 9.1 The real gap: the Display authority has a NAME half and no VALUE half

557 §2.A is explicit — "every **label** is a projection of one declaration" — and `present()`
delivers exactly that: an `EntityRef` → a branded `DisplayLabel` **name**, for operations, surfaces,
metrics, conditions, routes, workflows. It deliberately resolves **names only**; no `EntityRef` case
reads a value (`present.ts:109-164`). The Display projection was completed for *what a thing is
called* and **never built for *what a thing currently says***.

That missing half is not a chip problem — it is system-wide, and the chip is merely its degenerate
extreme. The same gap, three severities:

| Site | How the displayed VALUE is sourced today | Severity |
|---|---|---|
| **Enrichment chips** (`HEALTH_STATS_BODY.overflow`) | a **baked literal** — *no source at all* (`'GPU cuda12'`) | extreme (the value can be flatly false) |
| **Status-bar metrics** (`StatusDeck.renderCoreItem`, ~`299-377`) | **bespoke per-metric inline code** reading `aiState` + a local `formatBytes` | medium (correct but un-unified, drift-prone, `StatusBarItem` has **no `valueSource` field** — only `accessibleLabel`, the *name*, is declared, per 559) |
| **Health metric cards** (`MetricCardRenderer` fed by `HealthSurface`) | parent computes + formats the string, passes a pre-formatted `value` | medium (same gap, plus `formatBytes` duplicated across surfaces) |

The pattern is unmistakable: **the name half is centralised (`present`), the value half is
scattered.** 559 already moved the *name* of a status metric onto the registry and through
`present({kind:'metric'})`; it left the *value* in the renderer. The chips simply skipped even
having a source. So the durable structure is to **complete the Display authority's value half**, with
the chip as its first and most clarifying adopter.

### 9.2 The end-state: a Fact as a first-class observable + ONE Fact projector

A *fact* displayed in the UI has four parts: a **name** (what it is), a **value** (the datum), a
**presence** (does it apply on this host/config?), and — for sensed host facts — a **confidence/
provenance** (how sure, from which probe). The correct structure makes a fact a **registered
entity**, and adds the value-side sibling of `present()`:

- **A Fact catalog (register).** Each fact declares `id`, a `name` (a `labelKey`, reusing the
  metric-name authority), a **source binding**, a **presence** predicate, a **format**, and an
  optional **confidence** binding. This is the *same register+coverage shape* as
  `observed-happening.v1.json` (575), the 587 capability catalog, and the execution-surface register
  — a new fact is a discovery-step row, not a free literal. Coverage is auditable (548 §5.2).
- **A Fact projector — `projectFact(ref) → { name, value, present, confidence? }`.** The value-side
  twin of `present()`. It is the ONE place a displayed value is resolved; it reuses
  `present({kind:'metric'})` for the name. It dispatches on the source kind (below). It does **not**
  bloat `present()` itself (whose docstring deliberately scopes it to names) — it is a sibling under
  the same Display-authority umbrella, which now has two faces: *name* (`present`) and *value*
  (`projectFact`).
- **Two source kinds, each mapping to an authority that ALREADY EXISTS** (extend, never replace):
  1. **`constant` — a build-time fact, GENERATED from the SSOT catalog.** The embedding dimension is
     a per-build property of `fields.v1.json` (`vector.dimension: 768`), identical on every host; its
     authority is the catalog, not a runtime poll. The 557 **Generate** rung + the existing
     `gen-liveness-constants.mjs` pattern ("the constants ARE the register — drift is impossible by
     construction") project it into a FE constant. The fact's value is that constant. (§8.2.)
  2. **`observed` — a runtime fact, PROJECTED from `aiStateStore`.** GPU/accelerator, vector
     precision, SPLADE/NER/reranker presence vary per host/config and live on the status wire
     (`status.worker.*`, §8.1). The 557 **Unrepresentable** rung: the chip names the fact, the engine
     reads the live value, an *absent* capability renders the chip absent (so `'GPU cuda12'` cannot
     appear on a CPU host). For **host** facts (GPU/CUDA) this binding is the **FE terminus of the
     587 host-capability substrate**, whose `Effective` view already carries `(value, source,
     confidence)` to the wire (`GpuStatusView.cudaFunctional`/`source`/`confidence`, 587 §12). The
     honest chip therefore surfaces **provenance**, not a fabricated `"cuda12"` (which is on no wire,
     §8.1) — confidence-carrying is not gold-plating here, it is *consuming an authority the backend
     already ships*.

  Engine-config facts (SPLADE/NER/precision) are the same `observed` kind, one source-path over —
  no third mechanism.

### 9.3 The chip facet binds a fact-ref, so the literal is unrepresentable

`DeclaredAdaptiveItem` gains a factual form that carries a **fact-ref, not a string**
(`{ fact: 'core.gpu.accelerator' }` rather than `{ label: 'GPU cuda12' }`). The `DeclaredSurface`
engine renders `projectFact(ref)` — name + value — and **omits the chip when `present === false`**.
The decorative `{ label: string }` form **remains** for genuinely fact-free chips. Because the
factual form has **no string slot**, a wrong literal has nowhere to live: this is the 557 ladder's
**Unrepresentable** rung done properly (rung-2), which **dominates the Gate rung** — §4's Move 2
regex scanner degrades from "the structural close" to a *thin backstop* catching a decorative `label`
that smuggles a fact-shaped token, and even that is better expressed as **register coverage** (every
fact-shaped concept must be a catalog row) than a free-text regex over author prose (§8.5).

### 9.4 Why two authorities, not one — the durable lesson

The single correction §8 makes, stated structurally: a displayed fact's authority is chosen by its
**reason to change** (AHA), not by where it renders.

- **Build-time constants** change only when the *build/SSOT* changes → authority = the **SSOT
  catalog**, via **Generate**. Routing them through a runtime status field is authority-laundering:
  the backend would re-copy the catalog onto the wire, re-opening the same author→fact gap one layer
  down (§8.2). The dimension — the doc's own headline defect — is exactly this kind, and is the
  clearest proof the two kinds must not share one mechanism.
- **Runtime/host facts** change per host/run → authority = **`aiStateStore`** (host facts: the **587
  capability substrate**), via **Unrepresentable**.

`projectFact` unifies the *projection* (one resolver, one render path) while keeping the *sources*
distinct — exactly 587's discipline (one resolver over authority-ordered sources; single-source
facts carry zero merge ceremony).

### 9.5 Boundary with 595 (the sibling) — a LEAF fact vs a DERIVED verdict

594 and 595 are the two depth rounds on 557's first two projections, and they share `aiStateStore` as
a substrate, so the cut between them must be explicit:

- **594 projects a LEAF fact's *value*** — a measured/declared datum with a single source (a
  dimension, a GPU type, a precision, a capability's on/off). `projectFact` *reads* observed-state; it
  never *computes a rollup*.
- **595 derives a *verdict/phase*** — a computed rollup over many leaves (the system-health verdict;
  the missing `transitioning` state), which is the **Observed-state** authority's job (557 §2.B), and
  belongs on `aiStateStore` as a derived value every surface consumes.

So "is the reranker enabled?" is a 594 leaf fact (a chip). "Is the system healthy / mid-transition?"
is a 595 verdict (a header badge). A capability-*presence* chip binds a leaf; it does **not** consult
or reproduce the health verdict. Keeping `projectFact` strictly leaf-level is what prevents 594 from
re-growing a fourth health-verdict interpreter (595 §1.1's exact failure).

### 9.6 Scope discipline (AHA / YAGNI guard, mirroring 587 §7)

The justification is **empirical recurrence**, not aesthetics: the value half is scattered across
chips (×6 literals), every status-bar metric (bespoke), the health cards (inline), and a duplicated
`formatBytes` — the *same* "value displayed without a single projection authority" loss, several
documented members (`structural-defects-no-repeat`, the bar 587 also cleared). Guards, verbatim from
587's spirit:

1. **Make the single-source case free.** Most facts have one trivial source; the catalog row + a
   one-line binding is the whole cost — no merge/precedence ceremony unless a fact genuinely has
   competing probes (only host facts, via 587, do).
2. **Only *facts* go through the projector.** Decorative text stays free `label: string` (594 §5
   preserved). The projector is for values that assert a measurable/declared truth.
3. **Don't force-migrate.** The chip strip is the *clarifying* first adopter (it has the worst gap);
   StatusDeck metric values and the health cards are the *natural* unification, not a forced one —
   they already read `aiStateStore`, they just don't go through one projector.

### 9.7 End-state, one paragraph

The Display authority (557 §2.A) is completed when it has **both halves**: `present()` projects every
**name**, and a sibling `projectFact()` projects every **value**, from one **Fact catalog** whose
entries bind to the right authority by the fact's reason-to-change — **SSOT-generated constants** for
build-time facts, **`aiStateStore` / the 587 capability substrate** (provenance- and
confidence-carrying) for runtime facts. A chip, a status-bar metric, and a health card then all
*consume* a fact-ref through the one projector; a value displayed with **no source** (the baked chip
literal) or with a **forked, hand-rolled source** (the bespoke metric code) both become
unrepresentable. The build-time/runtime split (§9.4) and the leaf/verdict boundary with 595 (§9.5)
are the two lines that keep this from over-unifying. The chip that shipped `"Embeddings 384-d"` is
not fixed by a better literal or a regex — it is fixed by being the first fact to have an authority.

---

## 10. Pre-implementation de-risking (read-only verification pass, 2026-06-16)

> Confidence-building pass before any implementation: the §9/§4 design rests on assumptions that were
> traced via subagent + schema files in §8, but **not** confirmed against the *frontend-facing*
> reality (the generated TS, the gates, the codegen wiring). This section verifies the load-bearing
> ones with read-only evidence and records the residual risks. **No feature code was written.**

### 10.1 Verified assumptions (every load-bearing one held)

| # | Assumption | Verdict | Evidence (file:line) |
|---|---|---|---|
| A | Runtime facts exist on the **FE generated** `StatusResponse` (not just backend Java), with usable optionality | **CONFIRMED** | `modules/ui-web/src/api/generated/schema-types/status-response.ts`: `gpu.{cudaFunctional,source,confidence,available}?` (l.101-114 — the 587 fields **were regenerated**, not stale); `worker.enrichment.{spladeEnabled,nerEnabled}?: boolean` (l.273/278); `worker.vectorFormat.vectorFormatActual?: string\|null`; `worker.gpu.{rerankerOrtCuda?.available, rerankerModelPath?, nerGpuEnabled?}` |
| A′ | The embedding **dimension** is NOT on the wire (forcing the SSOT authority, not a runtime field) | **CONFIRMED absent** | no `dimension`/`vectorDim` field anywhere in the generated type → independently validates the build-time-constant split of §8.2/§9.4 |
| B | A new value-projector (`projectFact`) is **not** fought by the presentation-purity gate / ESLint | **CONFIRMED additive** | `check-presentation-purity.mjs` + eslint `no-restricted-syntax` forbid only raw **label/id** leaks (`ops.*.label`, `present()` bypass) — never **values**. `StatusDeck` already renders raw live values (`docsDisp`/`sizeDisp`/`memUsedDisp`) directly and passes the gate → value-rendering is currently unguarded, so the projector is purely additive |
| C | SSOT→FE codegen for the dimension follows an established, CI-checked pattern | **CONFIRMED** | `gen-liveness-constants.mjs` → `modules/ui-web/src/api/generated/*.ts` with a `--check` mode wired into `ci.yml` via `check-liveness-constants-regen.mjs`. A `gen-field-constants.mjs` reading **root** `SSOT/catalogs/fields.v1.json` mirrors it exactly (caveat: no generator reads `SSOT/catalogs/` yet — it would be the first, though the shape is identical) |
| C′ | `fields.v1.json` carries one clean dimension | **CONFIRMED** | `vector` and `chunk_vector` both `dimension: 768`; root + classpath copy agree; zero divergence risk |
| D | `OverflowController` tolerates conditionally-absent chips | **MOSTLY — see 10.2** | `adaptiveBar.ts` re-measures on `widths.length !== items.length` (l.145) and on `signature()` change (l.117-127); omitting a chip from the DOM changes the item count → re-measure |
| E | "GPU cuda12" has an honest replacement value | **CONSTRAINED** | only `cudaFunctional` (bool) + `source`/`confidence` + `driverVersion` exist — **no CUDA runtime version "12"** on the wire. The honest chip is `GPU · CUDA (<source>)` / `GPU` / absent; the fabricated "12" is dropped (confirms §8.4) |

### 10.2 Surprise surfaced (new finding — fold into the §9.3 design)

**`DeclaredSurface.signature()` is declaration-based, not presence-based.** It is
`(declaration.overflow ?? []).map(i => i.id).join('|')` (`DeclaredSurface.ts:94`) — a *static* string
over the declared items. When a chip is omitted because a **live capability flips** (the §9.3
"absent on a CPU host" behaviour), the declaration is unchanged, so `hostUpdated()` early-returns
(`adaptiveBar.ts:119`) and does **not** clear cached widths; re-clip then relies only on the
count-check in `recompute()` (scheduled by the ResizeObserver), so the strip is *eventually* but not
*immediately* consistent on a flip. **Fix is one line** — the signature must include live presence
(join only the *present* chip ids). Minor, but a real correctness note now recorded rather than
discovered mid-implementation. (This is the §9.3 facet's one concrete wiring obligation.)

### 10.3 Residual risks (after de-risking)

1. **Tri-state presence semantics.** The runtime fields are `boolean | undefined` (+ `status: null`
   until first poll). The presence predicate must distinguish *known-false* (→ hide chip) from
   *unknown* (loading/null → neutral or last-known), reusing the existing `Maybe`/`Known`/`Unknown`
   patterns (`aiStateStore` / `state/known.ts`). A bounded design decision, not a blocker.
2. **Fact-catalog/projector exact shape** — genuinely new code (per-fact path-accessor + formatter).
   Additive and unguarded (finding B), so low risk; the shape is an impl detail.
3. **Confidence/provenance UX in a small chip** — how to surface 587's `source`/`confidence` (tooltip
   vs inline) is a deferrable UX question, not a structural one.
4. **Live tier not exercised.** Assumptions were confirmed *statically* (generated wire types, gates,
   codegen). The chips are static literals today, so the data path is confirmed but not *visually*
   re-rendered live. A `jseval ui-shot` of the Health surface against a running stack is the one
   remaining verification tier — recommended early in implementation, per
   `use-every-verification-tier` / `static-green ≠ live-working`.

### 10.4 Confidence rating

**8 / 10** for the remaining design + implementation work. Every structural assumption verified
favorably; both source authorities (SSOT codegen, the generated status wire incl. the 587 fields)
exist and are CI-pattern-backed; the new projector is additive (no gate conflict). The −2 is the
unexercised live tier (10.3 #4) and two bounded-but-unsettled design decisions (tri-state presence;
the `signature()` one-liner from 10.2).

---

## 11. Frontend / user-facing design (after live UI inspection, 2026-06-16)

> §1-§10 reason about *correctness* and *authority*. This section is the missing **user-facing**
> half — written **after** inspecting the running UI in the browser, not from the tempdoc alone. It
> records what the strip actually looks like, the UX problems that survive even a perfectly-correct
> Move 1a/1b, and the corrected frontend design.

### 11.1 What was inspected (live evidence)

The real System/Health surface could not be rendered live — the local dev backend was flaky
(surface stuck on **"Loading System…"**, status bar flipping to **"Reconnecting…"**), which is
itself a relevant observation (see 11.4). The chip strip was captured instead via the
`?presentation-demo=1` route, which renders the **same `DeclaredSurface` adaptive-strip component**
the Health surface uses (presentation-demo §7 "Co-projected liveness + overflow"). Observed:

- A **liveness readout** — a green status **dot** + `Retrieval · Online — Qwen Qwen3.5-9B`. The dot
  is a real provenance affordance: it signals *"this value is live."*
- A row of **visually identical** grey pills:
  `Indexed 12,340 docs · Queue 3 jobs · GPU 41% · Memory 2.1 GB · Uptime 4h 12m · Embeddings 768-d ·
  Reranker on · +1`.
- The bottom **status bar** (the mature sibling pattern, §9.1) rendering live values with the same
  vocabulary: `● CONN · 605 · 51.1 MB · 230.2 MB · [Online — Qwen Qwen3.5-9B]`.

### 11.2 The user-facing problem the §9 design does NOT yet solve

**Correctness ≠ trust.** Every pill — a live metric (`GPU 41%`), a build constant
(`Embeddings 768-d`), and a capability claim (`Reranker on`) — renders as the **same grey pill: same
size, same weight, same colour, no icon.** A user cannot tell which values are *live*, which are
*static*, or which are *trustworthy*. Move 1a/1b make the values **correct**, but the user still has
**no way to see that a fact is bound to an authority** rather than typed by an author. The liveness
readout has a trust signal (the dot); the fact chips have none. So the deepest user-facing defect is
**provenance illegibility**, and it is *not* fixed by making the literal correct — it needs a
rendering treatment, not just a value source. This is the user-facing reason Move 1/§9 is necessary
but **insufficient on its own.**

### 11.3 Corrected frontend design — five principles (extends §9.3's "absent chip" with the UX it implies)

1. **Provenance must be legible, not just correct.** A *bound* fact chip should carry a lightweight
   "sourced" affordance the *decorative* chips lack — minimally an inspectable tooltip, and ideally a
   subtle leading marker echoing the liveness dot. The 587 substrate already ships `source` +
   `confidence` for host facts (§8.1) — that is exactly the tooltip content:
   *"GPU · CUDA — via NVML (high confidence)."* This converts an unverifiable assertion into an
   **inspectable, sourced** fact, which is the whole point of giving it an authority.

2. **Confidence-aware rendering (new — §9.2 carries confidence but never designed showing it).** A
   `LOW`/`UNKNOWN`-confidence host fact must render its uncertainty (muted, or a trailing `?`), not
   assert flatly. The user should see *"GPU · CUDA?"* under low confidence rather than a confident
   claim that may be wrong. Showing uncertainty honestly is strictly better than a confident lie —
   and it is the user-facing realisation of 587's "confidence survives to the decision."

3. **Three-state presence, not two (seen live via "Reconnecting…").** §9.3's rule is "present → show,
   absent → hide." The live UI shows a **third** state: *unknown* (status not yet polled / stale /
   reconnecting). A fact chip in the unknown state must render a **placeholder / last-known-with-
   staleness cue**, never a fabricated or stale-but-confident value (the §10.3 tri-state risk,
   confirmed in the wild). This is the chip-level echo of 595's transition state: *unknown ≠ absent ≠
   false.* The presence predicate is genuinely ternary.

4. **Off-state is information on a DIAGNOSTIC surface (a real design fork the strip hides).** §9.3
   says an absent capability → no chip. That is right for an **ambient** strip (less clutter, no
   lie). But the Health/System surface is a **diagnostic** surface (571 altitude `DIAGNOSTIC`): there,
   *"Reranker: disabled"* is exactly what the user needs to understand degraded search quality —
   silently hiding it is a *loss of diagnostic information.* So the rule is **altitude-dependent**:
   ambient strips **hide** absent capabilities; the diagnostic surface **shows on/off explicitly**
   (`Reranker on` / `Reranker off`). The current strip conflates the two (a capability rendered as a
   bare ambient pill on a diagnostic surface). This fork must be decided per surface, not globally.

5. **Overflow must not silently clip a fact (the `+1` hazard).** The strip collapses its tail to
   `+1` — a capability the user needs to know about can vanish into the overflow. Worse, when a chip
   becomes *absent* (capability flips off, §9.3), the strip reflows and a previously-hidden chip
   surfaces — visible churn. The fix: **fact chips outrank decorative chips in the overflow priority
   order** (pin/priority by fact-ness, not author whim), so a real capability is never the thing
   hidden behind `+1`; and the reflow on a presence flip should be non-jarring (this is the same
   `signature()`-must-include-presence wiring from §10.2, now with a UX rationale).

### 11.4 Two adjacent UI observations (logged, not in scope — own traces)

- **The strip's chip set drifts from the real surface.** The demo renders
  `Indexed/Queue/GPU%/Memory/Uptime/Embeddings/Reranker`; the real `HEALTH_STATS_BODY` declares
  `Embeddings/SPLADE/Reranker/NER/GPU cuda12/Float32`. The demo is therefore **not a faithful
  preview** of the surface it illustrates — a minor authoring-drift finding (→ observations, not this
  doc's Display-authority root).
- **"Loading System…" + "Reconnecting…" with no content** is the 595 transition-state gap seen on
  the very surface this doc concerns — a healthy-but-reconnecting backend renders the whole surface
  as a perpetual spinner. **Out of scope here (it is 595's Observed-state root, §9.5), recorded for
  cross-reference.**

### 11.5 Net user-facing conclusion

Making the chip values correct (Move 1a/1b) is necessary but does **not** by itself fix the user's
experience: the live UI shows that live, static, and capability facts are **visually
indistinguishable and unsourced**, so the user cannot tell a trustworthy bound fact from typed prose
even once the prose is accurate. The correct frontend design therefore adds, on top of §9's value
authority, a **provenance- and confidence-legible rendering** (11.3 #1-#2), a **ternary presence
state** (#3), an **altitude-aware on/off policy** (#4), and **fact-priority overflow** (#5). The
`projectFact` answer shape from §9.2 — `{ name, value, present, confidence }` — is exactly the data
this rendering needs; §11 is the specification of *how that answer is shown to a human.*

---

## 12. Effort / complexity estimate (calibrated to repo cadence, 2026-06-16)

> Calibrated against git history for comparable shipped tempdocs (587 GPU resolver: 1 day / 1 feat
> commit / 16 files / ~360 LOC; 569 declaration engine: ~2.5 days, first burst 62 files / 5,580 LOC;
> 575 register+gate+codegen: ~1.5 days). This repo ships ~10+ tempdocs/week at ~100-200 commits/day —
> implementation **bursts are fast**; raw-intuition human-team estimates run ~5-10× too high here.

Decomposed (focused-implementation wall-clock, in this repo's cadence):

| Scope | Comparable to | Estimate |
|---|---|---|
| **Move 1a only** (codegen the dimension; correctness close) | a slice of 575's `gen-*` + a 2-line chip edit | **~0.5 day**, 1-3 commits, small diff |
| **+ Move 1b** (the Fact catalog + `projectFact` + chip facet + tri-state + the §10.2 `signature()` fix) — structural core | a 559/569 facet slice (new component + catalog + engine wiring) | **+~1-1.5 days**, ~10-20 commits |
| **+ Move 2** (the backstop gate + its register) | a 575 gate slice | **+~0.5 day** |
| **+ §11 UX** (provenance marker, confidence rendering, ternary presence, altitude on/off, fact-priority overflow) | visual work with `ui-shot` iteration | **+~1-1.5 days** (slowest per-LOC: visual tuning + the flaky-backend friction hit in §11.1) |

**Full §9 + §11 line: ~3-4 focused days, ~20-35 commits**, likely spread over ~1 week of calendar
time given parallel-worktree interleaving. The **structural core (Move 1a + 1b) alone: ~1.5-2 days.**

**Accelerant:** 594 already carries §8 (investigation) + §9 (design) + §10 (de-risking — every
load-bearing assumption verified, the exact codegen pattern + wire fields confirmed) + §11 (UX). The
587 precedent shows a thorough confidence-building pass → same-day ship; this heavy prep should
compress the above. **Risk factors that could extend it:** `projectFact` is genuinely new (no
existing signal-ref→value resolver, §8.3); §11 UX needs visual iteration on a flaky local stack; the
work lives in the governed `shell-v0` region (consult/maintain-doc hooks + several presentation gates
must stay green). Net: **moderate complexity, low-to-moderate schedule risk** — the design is
unusually well-specified for its size, which is the single biggest predictor of a fast burst here.

---

## 13. Cross-tempdoc coordination & interference (2026-06-16)

Scan of the active design line surrounding 594 (the 593-walkthrough cohort, all design-theory, none
yet implemented; no worktrees open). 594 is one of a **three-sibling depth round on 557** — Display
(594) / Observed-state (595) / Operability (596) — plus the separate-lineage 597. None of these
*blocks* 594's structural core (Move 1a codegen + 1b `projectFact` for the *correctness* of facts).
The interference is concentrated in the **§11 UX layer** and is *coordination*, not conflict:

### 13.1 595 — a soft UPSTREAM dependency for the ternary-presence rendering (the one to watch)

594 §11.3 #3 / §10.3 require a **third "unknown/reconnecting" chip state** (not just present/absent).
That state IS exactly what 595 is formalizing: a first-class `transitioning`/unknown observed-state on
`aiStateStore` (595 Move 2). Both docs read/extend the **same `aiStateStore`**. Coordination rule:
**594's "unknown" chip rendering should bind to 595's transition authority once it exists, not
hand-roll its own predicate.** If 594 ships first, its unknown-state predicate is provisional and
should be refactored onto 595's state when 595 lands; if 595 ships first, 594 consumes it directly.
Conceptual boundary is clean (594 = leaf fact value; 595 = derived verdict/phase — §9.5); the only
real coupling is this shared "unknown" notion. **Lowest-friction order: 595 before (or with) 594's
§11.** Not a blocker for Move 1a/1b.

### 13.2 596 — shared capability substrate; a long-term CONVERGENCE point (mild duplication risk)

594's `projectFact` (capability → displayed *fact*) and 596's availability projector (capability →
*reason* for unavailability) both encode "is capability X present, and why." 596 §9 explicitly names
**594's projected facts as its reason inputs** — so the correct long-term shape is: **594's Fact
catalog is the single capability-presence authority that 596's reason-projector reads**, rather than
two parallel layers re-resolving capability state from `aiStateStore` (which would be the exact
forked-source anti-pattern 594 itself argues against, §9.1). 596 §10 C4 *deliberately decoupled*
itself (reads `aiStateStore` raw today) to avoid blocking on the unbuilt 594 — so there is **no
short-term conflict**, but if both implement independently the capability-reading logic forks.
**Coordination rule: whichever builds the capability layer first should expose it as the shared
authority the other consumes.** Both also have adjacent §11 rendering on the same surfaces (chat
affordance bar / Health) and should share a visual language (596's reason hint ≈ 594's
provenance/confidence affordance). Convergence opportunity, not a blocker.

### 13.3 597 — no interference (different lineage, picks up a 594-deferred item)

597 owns the search-surface "N results" truthfulness defect — a **search-response contract** root
(`totalHits` semantics, the canonical `SearchTrace`), explicitly *not* a 557 presentation sibling. It
is the home for the items 594 §5 pushed OUT (the 136-cap / facet-count divergence). It touches the
search surface + backend, **not** the Health-surface chip strip or the Display value-projector. No
interaction with 594's remaining work.

### 13.4 Net

No current work **blocks** 594. One **ordering preference** (595 before 594's §11 unknown-state) and
one **convergence point** (594's Fact catalog as 596's capability source) are the only couplings, both
in the UX/observed-state layer and both already anticipated by the sibling docs' own boundary sections.
594's structural core (Move 1a + 1b correctness) is independent of all three and can proceed first.

---

## 14. Confidence pass #2 — the UX / coordination layer (2026-06-16)

> §10 de-risked the structural core (wire fields, gates, codegen, overflow). This pass targets the
> §11 (UX) + §13 (coordination) layer it never touched. Read-only investigation; **no feature code.**
> Result: most §11 unknowns resolve favorably, with three small new gotchas and one boundary
> correction.

### 14.1 Verified / refuted (U1–U8)

| # | Uncertainty | Verdict | Evidence |
|---|---|---|---|
| U1 | Real chip-strip mount (vs the §11 demo proxy) | **CONFIRMED in code; live visual still unexercised** | `HEALTH_STATS_BODY` mounts live via `activeBodyFor(HEALTH_STATS_REGION)` → `<jf-declared-surface>` at `HealthSurface.ts:779-791`; Health is a member tab of `core.system-surface` (`SystemSurface.ts`), **not** relocated by 577/578. Live render retried but the browser was contended by a concurrent session (CDP timeouts) — the identical component was rendered via the demo in §11.1, so the gap is only the production data, not the component |
| U2 | 587 confidence/source value vocabulary | **CONFIRMED — small, closed** | `GpuCapabilities.Confidence` ∈ `{HIGH, MEDIUM, LOW, UNKNOWN}`; `Cuda.source` ∈ `{"nvml", "nvidia-smi", "cuda-driver-api", "none"}` (`GpuCapabilities.java:14-18,58-64`, `GpuCapabilitiesService.java:120-156`). Needs a small humanization map for the tooltip; `functional=null`+`UNKNOWN` = not-probed → render absent/unknown |
| U3 | Confidence is GPU-only | **CONFIRMED** | only the 587 GPU `Cuda` axis carries `(functional, source, confidence)`; SPLADE/NER/precision/dimension are plain bool/string. So `projectFact`'s `confidence?` is genuinely optional and the §11.3 #2 affordance must be **omitted** (not "unknown") for the non-GPU majority |
| U4 | Altitude reachable at chip-render time (§11.3 #4) | **FEASIBLE — one new hint** | Health = `DIAGNOSTIC` (`CoreSurfaceCatalog.java:641-663`); altitude is on the FE `Surface` type (`surface.ts:114-131`) and read via `getSurface(id).altitude` (`SystemSurface.ts:82`). But `DeclaredSurface` gets only a `SurfaceBodyDeclaration` (no altitude) — so §11.3 #4 needs **one optional `altitude` hint threaded through `SurfaceBodyDeclaration`**, passed by `HealthSurface`. Modest, not infeasible |
| U5 | Ternary presence without 595 (§13.1) | **STANDALONE-EXPRESSIBLE** | `aiStateStore` exposes `phase ∈ {connecting, connected, stale, disconnected}` + `status: …|null` + optional leaf fields → "unknown" is derivable today (`status===null` / leaf `undefined` → unknown; `false` → absent; `true` → present). 595 would only *upgrade* "unknown" to a richer "transitioning(rebuild)"; 594 is **not blocked** |
| U6 | Provenance affordance vs OverflowController | **REUSE — measure-safe** | existing pattern: `ProvenanceChip.ts:99` / `ProvenanceBadge.ts:151` use native `title=` on a span/chip for rich metadata tooltips (zero added width → OverflowController unaffected); works on a **non-disabled** span (unlike 596's disabled-button case); `LivenessReadout`'s `role="img"`+`aria-label` is the a11y precedent. Net-new component **not** required (a *visible* leading dot would add width — prefer `title`) |
| U7 | Fact-name source (§9.2 "reuse `present({kind:'metric'})`") | **INCOMPLETE — needs own `labelKey`** | `present({kind:'metric'})` = `getStatusBarItem(id)?.accessibleLabel ?? humanizeId(id)`; chips aren't registered StatusBarItems → it would just humanize the id. So the **Fact catalog must carry its own `labelKey`** (or register facts as metrics). §9.2's reuse claim only holds for already-registered metrics |
| U8 | 596 convergence reads the same fields (§13.2) | **PARTIAL — store-level, not field-level** | both read one `aiStateStore` (✓ single authority), but 596 gates on the **coarse** `capabilities.{chat,rag,extract}` + `readiness.reasonCodes`, while 594's fine-grained facts (SPLADE/NER/reranker/precision/GPU) live in `status.worker.*` / `status.gpu.*` — a **different field tier**. So §13.2's "594's Fact catalog as 596's capability source" is an *opportunity*, not a drop-in: they share the store, not the field set |

### 14.2 New gotchas + assets surfaced (fold into the design)

- **G1 (U7):** the Fact catalog owns its `labelKey` — §9.2's metric-name reuse is only partial. Small.
- **G2 (U8):** 594 binds to the **fine-grained `status.worker.*`/`status.gpu.*` fields (representation #2)**, *not* the coarse `aiState.capabilities` 4-flag graph (representation #1) that `CapabilityPills`/596 use. This **softens** the §13.2 596-coupling (different tiers) and is the correct binding target for Move 1b.
- **G3 (U4):** §11.3 #4 (altitude-aware on/off) costs **one optional `altitude` field on `SurfaceBodyDeclaration`** + a `HealthSurface` pass-through — a known, bounded plumbing add, not a blocker.
- **Reusable assets:** `ProvenanceChip`/`ProvenanceBadge` (the `title`-tooltip provenance pattern — directly applicable to §11.3 #1) and `CapabilityPills` (tempdoc 510 — an existing coarse state-styled pill renderer; precedent for presence/absence pill styling, adjacent to 596).

### 14.3 Updated confidence

**Full §9 + §11 line: ~7.5 / 10** (up from the going-in 6.5). The §11 UX layer de-risked well — the
provenance affordance is a reuse (U6), the confidence vocabulary is small+closed (U2), altitude is
feasible (U4), ternary presence is standalone (U5). The remaining −2.5: the **live visual is still
unexercised** (browser/backend contention — `use-every-verification-tier` gap), the **altitude hint
+ fact-`labelKey`** plumbing (G1/G3), and the **softened-but-real 595/596 couplings** (UX-layer
ordering/convergence, §13). **Structural core (Move 1a + 1b) stays ~8/10** from §10. Net: the design
is well-specified; the residual risk is concentrated in the visual/UX tier and is coordination-shaped,
not blocking.

---

## 15. As-built (implementation, 2026-06-17)

Implemented in worktree `worktree-594-chip-facts` (branch `worktree-594-chip-facts`; NOT merged). All
four §4 moves + the §11 UX shipped as the structural Fact-authority design (no spot-fixes).

### 15.1 What landed (per move)

- **Move 1a — Generate from SSOT.** `scripts/codegen/gen-field-constants.mjs` reads
  `SSOT/catalogs/fields.v1.json` (asserts `vector` == `chunk_vector` dimension), emits
  `EMBED_VECTOR_DIM` → `modules/ui-web/src/api/generated/field-constants.ts`, with `--check`
  idempotency. CI wrapper `scripts/ci/check-field-constants-regen.mjs` wired into `ci.yml`. The chip
  derives the dimension; a wrong "384-d" is impossible by construction.
- **Move 1b — the value-projection authority.** New `modules/ui-web/src/shell-v0/display/facts.ts`:
  `projectFact(id, aiState) → { name, value, presence, provenance? }` over a `FACTS` catalog
  (`constant` source for the dimension; `observed` source reading the fine-grained `status.gpu.*` /
  `status.worker.{enrichment,gpu,vectorFormat}.*` fields, §14 G2). **Ternary presence**
  (present/absent/unknown). `DeclaredAdaptiveItem` gained `fact?` (and `label?` relaxed);
  `DeclaredSurface` subscribes to `aiStateStore` (only when a factual chip is declared), projects each
  factual chip, **omits absent**, **mutes unknown**, and the overflow **`signature()` now includes the
  live rendered set** (§10.2/§14 G3 fix). `HEALTH_STATS_BODY` + the demo strip rebound to fact-refs.
- **§11 UX.** Provenance/confidence rendered as the chip `title` (reusing the `ProvenanceChip`
  pattern — GPU shows "via NVML · high confidence"; non-GPU facts carry none). **Altitude-aware
  on/off**: `SurfaceBodyDeclaration` gained `altitude?`, threaded by `HealthSurface` from the surface
  catalog (`getSurface('core.health-surface')?.altitude`, NOT re-declared) — a DIAGNOSTIC surface
  renders an absent capability as a muted "<name> off"; ambient omits. **Fact-priority overflow**: a
  fact chip outranks a decorative one in the clip order. The presentation conformance validator
  (`presentationDeclaration.ts`) updated to require **exactly one** of `label`/`fact` per chip.
- **Move 2 — backstop gate.** `governance/chip-facts.v1.json` (fact-shape catalog + declared factIds)
  + `scripts/ci/check-chip-fact-authority.mjs` (a fact-shaped `label:` literal with no `fact`-ref
  fails; every declared factId must resolve in the catalog). Wired into `ci.yml` (ui_web-conditional).

### 15.2 Validation surfaced (this transcript)

- `gen-field-constants.mjs --check` → **pass**.
- `cd modules/ui-web && npm run typecheck` → **clean**; `npm run test:unit:run` → **3024/3024 pass,
  317 files** (incl. new `facts.test.ts` 10/10 + `DeclaredSurface.test.ts` fact tests + the updated
  `coreDeclaredContract.test.ts`).
- `check-chip-fact-authority.mjs` → **pass** (negative-tested: an injected fact-shaped literal fails,
  then reverted); `check-presentation-purity.mjs` → **pass** (projectFact is value-only/additive).

### 15.3 Design corrections discovered during implementation

- The presentation **runtime conformance validator** (`presentationDeclaration.ts`) hard-required a
  string `label` on every overflow item — not surfaced in §8/§10/§14. Updated to `id` + exactly-one
  of `label`/`fact`. (Caught by `coreDeclaredContract.test.ts`, the `audit-driven-fixes-need-test`
  value: a static design pass missed it; the test bit.)
- The §11 confidence affordance is GPU-only (§14 U3 confirmed in build): `provenance` is omitted for
  the non-GPU majority, so the title is absent rather than an empty/implied-unknown hint.

### 15.4 Live browser verification (real UI, real backend — NO dev-stack takeover)

The shared dev stack was owned by another agent session, so — per the run directive (do not take
over) — the worktree's own FE was served on `:5176` with `VITE_JUSTSEARCH_API_PORT=62848`, proxying
`/api` **read-only** to the running backend. This verifies the worktree code against real data without
reclaiming the stack. Real `/api/status` at verification time: GPU available + `cudaFunctional=true`,
`source=nvml`, `confidence=HIGH`; splade/ner enabled; reranker model configured; `vectorFormatActual=FLOAT32`.

Live-DOM read of the System Health `INDEX` chip strip (`jf-declared-surface`):

| chip | text | title (provenance) | presence |
|---|---|---|---|
| embed | `Embeddings 768-d` | — | present |
| splade | `SPLADE` | — | present |
| reranker | `Reranker` | — | present |
| ner | `NER` | — | present |
| **gpu** | `GPU CUDA` | **`via NVML · high confidence`** | present |
| precision | `Vectors Float32` | — | present |

**Result: PASS.** Every chip is host-accurate (matches real status), the dimension is **768 from the
SSOT constant** (the original `384-d` defect is structurally closed), the GPU chip reads **`CUDA`**
(never the fabricated `cuda12`) and carries the **provenance tooltip** `via NVML · high confidence`,
and SPLADE/NER/Reranker reflect the real (all-enabled) config. The whole strip is now a projection of
authority, not baked prose. (All 6 present because this host genuinely has GPU+CUDA+all enrichment;
the absent/unknown/diagnostic-off paths are pinned by the unit tests, §15.2.)

### 15.5 Status: COMPLETE. Implementation + unit/gate validation + live browser verification all green
on `worktree-594-chip-facts` (not merged, per directive). Out-of-scope siblings remain: the "Service
degraded" header visible during verification is the 595 observed-state-verdict gap (§9.5/§13.3), not
this doc's Display root.

### 15.6 Follow-up — §11.3 #2 confidence-aware rendering CLOSED (2026-06-17)

A conceptual review found the first pass carried confidence only as tooltip *prose* (`provenance`), not
as a structured field that drives the visual — so a low-confidence GPU would have rendered as flatly as
a high-confidence one. Closed: `ProjectedFact` now carries a structured `confidence?: FactConfidence`
(`high`/`medium`/`low`/`unknown`, mapped from the 587 GPU merge); the chip facet renders a
**low/unknown-confidence** present fact with a trailing **"?"** + a dotted-underline cue
(`data-confidence`), while high/medium render flatly. **§11 is now fully covered (5/5 principles).**
Validation: `facts.test.ts`/`DeclaredSurface.test.ts` pin both paths; full suite **3028/3028**; gates
green; **live browser** (real NVML/HIGH host) confirmed the GPU chip wired to `data-confidence="high"`
with no "?" (no regression). The LOW visual is unit-proven — it cannot be produced on an NVML host
without a backend takeover (forbidden), so it was not reproduced live (honest ceiling).

---

## 16. Futures & research — what the Fact authority enables (ideation, 2026-06-17)

> **Read §17 for the disciplined cut.** This section is the raw research/ideation input ("all ideas
> viable"); §17 then scopes it to the problem 594 *actually* has and rejects the speculative parts.

> Post-merge ideation: now that `projectFact` exists, what does this substrate make *possible*? This
> section is **research + design futures, not committed work** — no production users, no rush; all
> ideas are viable, none is scheduled. Grounded in a web-research pass across uncertainty
> visualization, data-provenance/lineage UI, observability dashboards, design-system status patterns,
> calm technology, and explainable-UI/trust-calibration literature (sources named inline).

### 16.1 The reframe: this is a *provenance + trust-calibration* substrate

The research vocabulary names what 594 quietly built. In **explainability/provenance** terms (MIT
*Data Intelligence* provenance review; Georgetown/Alibaba provenance-enabled-XAI), a value is
trustworthy when it can answer *who/what/when/where it came from* — exactly `projectFact`'s
`{ source, confidence }`. And the crucial **trust-calibration** finding (Eleken XAI; the XAI
literature): *the goal is not to make users trust more — it is **appropriate** trust* (trust the
high-confidence fact, doubt the low-confidence one). That is precisely what 594's confidence-aware
rendering does. So the substrate is not "nicer chips" — it is the seam through which the whole app can
become *appropriately trustable*. That reframing is what makes the ideas below worth more than polish.

### 16.2 The research, distilled (the patterns that actually map)

- **Show uncertainty explicitly, never hide it; calibrate, don't inflate trust** (uncertainty-viz
  literature; Wilke *Fundamentals of Data Visualization*). → mark the *uncertain* fact, leave the
  certain one plain.
- **Status = color + shape + symbol, never color alone; needs an aria text alternative** (Carbon
  status-indicator pattern). → a visual "?" is invisible to screen readers (a real gap, below).
- **Freshness ≠ render time** — "last updated" can be silently stale; surface *source liveness* so a
  consumer can judge fitness before acting (IBM/Sifflet/Metaplane data-freshness writing). → freshness
  must come from the source's `observed_at`, not the poll.
- **Provenance becomes a *trust signal* and propagates** — a view inherits its source's certification
  (Power BI Promoted/Certified; Atlan/Snowflake lineage). → a fact's trust tier IS its 587 confidence.
- **Make values inspectable — click a number to reach its source** (SeekrFlow drill-to-source;
  explAIner). → every fact is already inspectable by construction.
- **Calm technology** (Amber Case's 8 principles): minimal attention · ambient · peripheral⇄center
  movement · **graceful degradation** · non-verbal status · minimum-viable. → the health strip is a
  *peripheral* surface; the inspector is its *center*; degradation should be honest, not alarming.

### 16.3 Ideas by axis (critically filtered)

**Polish (improve what shipped)**
- **P1 — Fix the provenance/confidence accessibility gap (real defect).** The provenance hover uses a
  native `title`, and the low-confidence cue is a *visual* "?" — both are invisible to assistive tech
  (Carbon: status needs a text alternative). Ironically this is the *same* defect tempdoc 596 found on
  `title`-on-disabled buttons. Fix: an `aria` description carrying source+confidence as words ("GPU,
  CUDA, low confidence, via nvidia-smi"), and a proper focusable popover rather than `title`.
- **P2 — Keep the confidence cue calm, not chromatic.** The uncertainty-viz literature offers
  red/yellow/green confidence tiers — *reject* them for chips: color-only fails a11y and reads as an
  alarm. The muted dotted-underline + "?" is the calmer, a11y-safer encoding; keep marking only the
  *exception* (low/unknown), per calm-tech "mark the uncertain, not the certain."

**Simplify**
- **S1 — Derive the provenance prose from the structured fields.** `provenance` (a string) and
  `confidence` (structured) overlap — the tooltip text "via NVML · high confidence" is reconstructable
  from `{ source, confidence }`. Generating the prose from the structured pair (one source of truth)
  removes the duplication, consistent with the substrate's own "derive, don't bake" thesis.

**Extend (apply the substrate wider)**
- **E1 — Complete the §9.7 migration (highest structural payoff).** Move StatusDeck's bespoke
  per-metric value code and the Health metric cards onto `projectFact`. Kills the duplicated
  `formatBytes`/inline formatting, and every metric (docs, memory, size, queue) gains
  presence/provenance/confidence/freshness *for free*. This is already the design's stated end-state;
  the research just confirms the payoff (one inspectable value authority).
- **E2 — Grow the fact catalog along the 587 capability axis** (RAM, disk, CPU, OS, driver version).
  Each is a confidence-carrying host fact in 587's model. **Dependency (honest):** 587 Phase 1 shipped
  *GPU only*; RAM/disk/CPU are 587 design-theory, not on the FE wire yet — so this extension is gated
  on 587's later phases. The fact catalog is the natural FE terminus when they land.
- **E3 — Freshness as a first-class fact dimension.** Add `asOf`/freshness to the projected fact from
  the status wire's `observed_at` (already present — buildable now). A fact can then render "as of 4s
  ago" or mute when its source goes stale — turning the binary present/unknown into an honest
  *liveness gradient*, and giving the 595 "reconnecting" state a concrete "last seen" anchor.

**New UX (uniquely enabled by the substrate)**
- **N1 — A universal "trust layer" / fact inspector (the flagship).** Because *every* fact now carries
  `{ source, confidence, freshness }`, any displayed fact can become inspectable: hover/focus → a small
  card with name, value, source, confidence, freshness, and "how derived" (drill-to-source, per
  SeekrFlow/explAIner). This answers 594's *founding* problem — "the user can't tell which facts are
  trustworthy" (§11) — at **app scale**, not just the chip. It is the calm-tech "periphery→center"
  move made literal: the strip is the glance, the inspector is the detail.
- **N2 — Self-describing diagnostics export.** Structured facts (vs baked prose) make a machine-readable
  provenance dump trivial — iterate the catalog → every displayed fact's value+source+confidence+
  freshness as a support/bug-report artifact. A genuinely new capability that literal strings could
  never offer.
- **N3 — Facts drive *honest graceful degradation* (the 594→596 bridge).** Calm-tech principle #6:
  "work even when it fails." An *absent* capability fact is the input to a 596 unavailability *reason*
  ("reranker off → search is keyword-weighted, not 'best results'"). The fact substrate becomes the
  app's single source for degrading its own claims honestly instead of asserting flatly. This is the
  coherent "honest UI" theme uniting 594 (facts) · 595 (transition state) · 596 (availability reasons).

### 16.4 Non-goals (what the research says NOT to do)

- **No heavyweight uncertainty charts** (error bars, hypothetical-outcome plots) — those fit big data
  viz, not a compact inline chip (uncertainty-viz "match technique to context").
- **No chromatic confidence tiers on chips** (red/yellow/green) — color-only + alarm-prone (Carbon a11y).
- **No "✓ verified" on every authoritative fact** — calm tech marks the *exception*, not the norm;
  badging every fact is clutter and trust-*inflation*, the opposite of calibration.
- **Don't over-DRY** — only values that assert a measurable/declared truth are "facts"; decorative
  labels stay `present()`/free text (the §5 / §9.6 scope discipline holds).

### 16.5 One line

The Fact authority's real prize is not better chips — it is that the app can now make **every
displayed fact appropriately trustable**: inspectable to its source, honest about its confidence and
freshness, and able to degrade its own claims gracefully. The flagship build is **N1 (the trust-layer
inspector)** on top of **E1 (finish the §9.7 migration)**; **E3 (freshness)** and **P1 (a11y)** are
small, independently valuable, buildable now.

---

## 17. The correct long-term design, scoped to the actual problem (2026-06-17)

> §16 was open ideation. This section applies the discipline: **the design's scope must match the
> problem 594 *actually has now* — no speculative abstraction, no generality for hypothetical needs.**
> The audit below is verbatim against `main`; the conclusion shrinks §16's seven ideas to **one**
> required structural close + **one** correctness fix, and explicitly defers or rejects the rest.

### 17.1 The real remaining problem (proven, not asserted)

`projectFact` was built as *the* single Display-value authority, but a source audit shows it is a
**second** authority with exactly **one adopter**:

- `projectFact`/`ProjectedFact` is imported only by `DeclaredSurface` (the chip strip) + its test.
- `StatusDeck` (`renderCoreItem`), `HealthSurface` (the metric-card array), and `BrainSurface` each
  still source their values from `aiState` and format them **inline**, bypassing the authority.
- **Three independent `formatBytes` exist** (StatusDeck / HealthSurface / BrainSurface) — and they have
  **already drifted** (`'0'` vs `'0 B'` for the zero case). That is the exact *representation-drift*
  class this codebase governs against — a live, if cosmetic, instance.

So the founding problem of 594 (a displayed value sourced+formatted ad hoc at every site, §9.1) is
**unsolved everywhere except the one strip.** That is the genuine remaining structure-shaped problem.
It is real (proven duplication + an actual drift), and it is the §9.7 end-state still open.

### 17.2 The correct structure (and only this)

Make `projectFact` genuinely *the* value authority by **migrating the bypass sites to consume it**:
the status-bar metrics and the Health/Brain cards become entries in the **one Fact catalog** (observed
reads + their formatters), and the three `formatBytes` collapse into the fact formatter. *(§18
implementation correction: Brain's `formatBytes` formats download/model/VRAM byte values in lists —
NOT single observed-state facts — so Brain is NOT a fact site; it only adopts the shared formatter.
The fact sites are the status metrics files/size/memory, shared by StatusDeck + HealthSurface; queue
is also not a fact — genuinely shell-divergent. See §18.)* The visual
shells — chip vs status-bar item vs metric card — **stay distinct** (they are genuinely different
layouts; the 559 §8 "bespoke leaf *contents*" cut says do not unify them into one component). The rule
is **share the value semantics, keep the shell.** When the four sites consume one projector, the
authority is finally single (the §9.7 close) and the drift class is structurally gone.

This is right-sized: it is warranted by a *documented, drifting* duplication, and it is bounded
(catalog entries + four call-sites + deleting three helpers). It is not a rewrite, and it does not
touch the shells.

### 17.3 What this problem does NOT yet require (the discipline)

- **Do NOT extract a shared *trust-rendering* primitive yet.** The trust affordances (presence-mute,
  confidence "?", provenance) live chip-locally today. The metrics being migrated (docs, memory, size,
  queue) are **exact single-source counts with no confidence/provenance** — they carry no trust
  ambiguity, so they render plainly and need none of that machinery. There is currently exactly **one**
  trust-bearing site (the chip). A shared trust-render primitive is therefore structure for a case the
  problem does not yet include (a *second* trust-bearing site). The `ProjectedFact` shape already
  carries `confidence?`/`provenance?` as *optional* — that is the forward seam; the rendering is shared
  only **when** a second trust-bearing adopter actually appears, not before. (This is the precise line
  between "the value migration, which is required" and "a trust-render abstraction, which is not.")
- **Freshness is NOT a 594 dimension** (§16 E3, rejected as owned elsewhere). A build-time constant is
  never stale; a sensed value's staleness is the **Observed-state** authority's concern — tempdoc 595's
  transition/stale state. The correct design is that the fact-rendering's `unknown` presence *consumes*
  595's staleness signal (the §13.1 boundary), not that every fact grows an `asOf`. Adding freshness to
  facts that have no staleness ambiguity is speculative completeness.
- **The "trust-layer inspector" is NOT a feature to build** (§16 N1, reframed). Inspectability is an
  *emergent property* of the provenance affordance, not a separate global overlay. The only real work
  is making that affordance correct (next), after which every fact is inspectable wherever it renders.
  A dedicated provenance-explorer is structure for a demand that does not exist.
- **Graceful degradation is NOT new 594 structure** (§16 N3). It is the already-drawn §13.2 convergence
  (594's facts feed 596's availability *reasons*), owned jointly with tempdoc 596 — not built here.
- **Diagnostics export (§16 N2): rejected.** No user, no use-case — speculative flexibility.

### 17.4 The one current correctness defect (fix in place, not deferred)

The chip's provenance uses a native `title` and the low-confidence cue is a *visual* "?" — both are
**invisible to assistive tech** (Carbon: status needs a text alternative). This is the *same* defect
tempdoc 596 found on `title`-on-disabled controls. It is a real bug in shipped code, fixed where it
lives (an `aria` text alternative for confidence + a focusable, AT-reachable provenance affordance) —
not a design-theory question and not deferred. (This is §16 P1, kept; it also *is* the whole of §16 N1.)

### 17.5 One line

The only structure 594 still genuinely needs is to **finish being one authority**: migrate the four
bypass value-sites onto `projectFact` (deleting the drifting `formatBytes` triplet) and fix the chip's
provenance/confidence accessibility. Everything else in §16 — a shared trust-render primitive, a
freshness dimension, an inspector overlay, a diagnostics export — is either premature (no second
trust-bearing adopter), owned by a sibling (595 freshness, 596 degradation), or speculative (export),
and is correctly **left unbuilt**.

---

## 18. As-built — the §17 value-authority close (2026-06-17)

Implemented on `worktree-594-finish`. The §17 structural close + the §17.4 a11y fix shipped; the §17
discipline held (nothing speculative built).

### 18.1 What landed
- **One shared formatter** — new `shell-v0/display/format.ts` (`formatBytes` + `formatCount`). The
  **three divergent `formatBytes`** (StatusDeck/HealthSurface/BrainSurface, drifted `'0'`/`'0 B'`) are
  **deleted**; all consumers import the one util. This closes the proven drift class.
- **Status metrics → facts** — `core.files`/`core.size`/`core.memory` added to the Fact catalog
  (value-only `observed` reads, tri-state). `StatusDeck` and `HealthSurface` now source those values
  from `projectFact` (keeping their own tone/icon/sub/aria shells, 559 §8). The two doc-count read
  paths (`aiState.index.documentCount` vs `status.worker.core.indexedDocuments`) are unified — both
  resolve to the same source, confirmed.
- **Scope corrections found in implementation** (the "investigate before implementing" discipline
  catching over-reach in §17.2): **Brain** is *not* a fact site (its `formatBytes` formats
  download/model/VRAM list values — it only adopts the shared formatter); **queue** is *not* a fact
  (StatusDeck needs the raw number for its pending+embed composite + hide-if-0; genuinely
  shell-divergent). Both correctly left shell-local.
- **Chip a11y (§17.4)** — a chip-local `chipAria()` builds a words-based `aria-label`
  (presence/confidence/provenance), so the visual "?" + `title` (invisible to AT) are now reachable.
  **Not** extracted as a shared trust-render primitive (§17.3 — no second trust-bearing adopter).

### 18.2 Validation (surfaced in the transcript)
- `npm run typecheck` clean; full suite **3040/3040** (+9: format/metric-fact/StatusDeck-value/chip-aria);
  `gen-field-constants --check` + `chip-fact-authority` + `presentation-purity` gates green.
- **Live browser** (worktree FE proxied read-only to the real backend, no stack takeover): the
  **status bar** shows the migrated values matching `/api/status` — files **606**, size **51.7 MB**
  (exact, shared formatter), memory live-correct; the **GPU chip `aria-label`** reads
  **"GPU CUDA, via NVML, high confidence"** (the a11y fix); no regression. (The Files/Size/Memory
  *cards* are not surfaced in the current System composition — pre-existing layout — but consume the
  identical verified `projectFact` values.)

### 18.3 594 is now structurally complete
`projectFact` is the single value authority for the facts that display it; the drift class is gone;
the one a11y defect is fixed. The remaining §16 ideas stay deferred/rejected per §17.3/§17.5.
