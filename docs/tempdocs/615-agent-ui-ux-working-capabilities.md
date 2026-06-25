---
title: "Agent UI/UX verification: measurement *alongside* vision, conforming to the existing a11y-baseline + determinism seams"
type: tempdocs
status: "MERGED TO MAIN (commit `f79df1ad9`, 2026-06-19) — §6.1 + §6.2 SHIPPED & live-validated; §6.4 (Judge-quality design-critic + state-matrix fuzzer) DEFERRED BY DESIGN (recognized, YAGNI per §6.4/§7); the optional RAIL→all-product-surface coverage tightening is a noted non-blocking refinement. 615 is FUNCTIONALLY COMPLETE per its own scope dispositions. ── PHASE 1+2 IMPLEMENTED & LIVE-VALIDATED (2026-06-19) — §6.1a freshness/coverage gate (`check-ui-step-coverage` + register, wired CI + CLAUDE.md; `ui_step_index.json` re-pointed to live shell-v0 + gate-locked). §6.1b harness migration to live Lit COMPLETE: 17 steps validated by real screenshots (6 views + search-results + inspector-open + the real-AI chain streaming/summarize-done + qa-response + filters-chips/multi-select/context-menu/selection-preserved + density/mode); root cause was the app-ready gate waiting on `search-input` while the live shell lands on chat (fixed to wait on the rail). All React-era residue removed from the harness CODE (dead `TID_*`/`__JUSTSEARCH_STORES__`/setups/`_ensure_file_selected` deleted; `action-panel`/context-budget/`error-retryable`/`button-active` steps retired as no-shell-v0-equivalent; 55 steps build clean). REMAINING: `citation-highlight` reliability (needs the agent answer to cite a source + a stable chain under GPU contention) and Phase 3 §6.2 measurement artifacts (a separate unit). `mocks/fixtures.mjs` orphan logged to observations. NOTE: §8's 'harness boots dead React' was WRONG — `?demo=true` is inert; the harness already booted the live Lit shell. Shipped APP has zero React; residue was only this harness + a vestigial `main.jsx` filename. §6.2 UPDATE (2026-06-19): the measurement-first thesis (P2) is now IMPLEMENTED + LIVE-VALIDATED — `ui_measure.py` writes a `<step>.measure.json` companion (shadow-piercing a11y landmarks + axe + geometry/overflow + console) next to every capture, validated read-only against the running stack (facts match the rendered UI; see §10). `citation-highlight` is ALSO live-validated (Answer tab renders inline `[1]`/`[2]` `.cite-ref` marks on a real grounded agent answer; §10). §6.2 complete — P2 satisfied end-to-end."
created: 2026-06-19
updated: 2026-06-19
author: agent analysis (theory pass + design pass) + implementation pass, filed by agent
category: frontend / ux / agent-tooling / visual-verification / dx
related:
  - ui-check-visual-verification
  - frontend-presentation-kernel
  - frontend-stack-is-lit
  - register-guard-resolution
  - evidence-bundle-determinism-budget
  - presentation-authority-gates
---

# 615 - Agent UI/UX verification: measurement *alongside* vision, conforming to the existing a11y-baseline + determinism seams

> Design note (2026-06-19). §1–§4 are the original theory/inventory/finding/research pass. §6–§7 are the
> design result: the tempdoc's ideas are **instances of two seams the system already has**, so the design
> conforms to them rather than building parallel structure. §5 now just routes into §6. Read §6 for the
> design and §7 for its reach. **For current implementation status, jump to §9 "As-built status" — it is the
> live truth and supersedes the §8 confidence-pass framing.** §12 is an honest **dogfooding critique** that
> *corrects* the token-economics and "measurement replaces vision" claims made earlier in this doc — read it
> before trusting §Thesis / §2 / P2 at face value. **§26 grades the §11 layer's practicality and names the
> substrate fragility; §27 is the long-term *design* for that fragility — the FE-serve readiness contract,
> which conforms to (and completes the readiness half of) tempdoc 618 §7 / Seam B rather than forking it.**
>
> **CURRENT STATE (2026-06-21), supersedes the older framing above for §37+:** §37–§38 shipped levers (b)
> fixture↔contract coverage gate + (c) discoverability (skill + hook). §39 is forward research (recorded, not
> built). §40–§42 designed/de-risked an *a11y render-truth closure* — **but §43 overturned its premise** (the
> alleged "nameless controls" are correctly named; the signal was a false positive), so that build is
> **withdrawn** and a small measurement-trust fix shipped instead (axe is the a11y-name authority). **§43 is
> the live truth for the §40–§42 arc — read it before treating §40–§42 as actionable.** Net: 615's substrate
> work is complete; the a11y render-truth "remaining work" resolved to recognize-not-build (no real violation).

> What this document is. An open-ended theory + capability survey, not an implementation plan. It records
> (a) an honest inventory of the visual/interactive capabilities an agent has in this repo today, (b) a
> four-faculty theory of where those capabilities are impaired and what the agent-native augmentation is,
> (c) one concrete repo finding that needs action regardless, and (d) external (June 2026) reporting that
> independently converges on the same thesis. It deliberately does not prescribe a build.

## Thesis (one line)

Stop trying to give the agent better *eyesight*; give it better *instruments* — structured measurement,
a real interactive loop, exhaustive edge-case coverage, and a product-specific design reference — because
that plays to what an agent is good at (facts, parallelism, exhaustiveness, cheap-to-hold context) instead
of what it is bad at (single low-DPI frames, no motion, token-expensive images).

## 1. Capability inventory (what exists today)

Grounded in a survey of the repo's actual tooling, organized by how it serves a visual/interactive loop.

| Tier | Tooling | Strength | Documented limits |
|---|---|---|---|
| 1 — static screenshot | `jseval ui-shot <step>` / `jseval ui-check` (Playwright; 47 steps; file→step index; `ui-shot-hint` PostToolUse hook; worktree auto-serve on :5174) | Fast (~300–600ms warm), agent `Read`s the PNG inline | Demo/mock data by default; glassmorphism renders flat (no compositor); 1× DPI only (2× exceeds the 2000px API cap); **static frames — no motion/transition/hover-timing** |
| 2 — live interactive | claude-in-chrome MCP (`navigate`, `computer`, `read_page`, `find`, `form_input`, `read_console_messages`, `read_network_requests`, `javascript_tool`, `gif_creator`) | Only tier that observes behavior over time | Needs live backend; manual setup; disconnected from the fast loop; underused |
| 3 — live stack | justsearch-dev MCP (`start`/`reload` ~2–3s hot push, `ai_activate` ~11s, `agent_chat`, `search_query`, `api_call`, `capture_evidence`/`validate_evidence`) | Closes the loop all the way to the LLM and real data shapes | Shared single stack; ownership/lease coordination |
| 0 — substrate | `Read` renders PNGs; Edit/Write/Grep/Glob; **governance gates** (`check-contrast-matrix`, `check-a11y-closure`, axe, `check-layout-purity`, theme-token closure) | Enforces UX invariants mechanically, with zero looking | Only covers what is encoded as a rule |

## 2. The four-faculty theory

UI/UX work needs four faculties. Each is impaired differently for an agent, and each has an agent-native
substitute that is often *better* than imitating a human eye.

### Perceive — vision is a lossy single-frame straw; measurement is the real sense
- The PNG is one of the agent's *weakest* instruments (low-DPI, single viewport, no motion, token-expensive,
  often demo data). The agent reads numbers far more reliably than it judges subpixel composition.
- Leverage: a **visual-properties probe** alongside every capture — computed-style + geometry → alignment
  grids, spacing-rhythm (on the 4px scale?), overflow/clipping, z-order, focus order, layout-shift deltas.
  These are *facts*. The repo already proves the pattern with the contrast/a11y/layout gates.
- **Screenshot for gestalt, measurement for correctness.** Today the agent over-relies on the PNG for both.
- Motion is checkable *structurally* even though the agent can't watch it: the repo tokenizes `--duration-*`
  / `--ease-*`, so "does this animation obey the motion system" is a CSS assertion. Pair with keyframe
  sampling (record flow → extract N frames → contact sheet) for a filmstrip the agent *can* read.

### Act — the interactive tier is severed from the fast tier
- ui-shot (fast/static) and claude-in-chrome (interactive/slow/manual) don't compose, so behavior rarely
  gets tested.
- Unifying idea: a "step" should be an **interaction trajectory** (type → click → wait → assert) that yields
  *both* a filmstrip *and* a measurement trace (console errors, network timing, focus path, layout shift).
  All primitives already exist; they are just not composed into one trajectory abstraction.

### Judge — the agent can verify rendering, but has no anchor for *quality*
- "No overflow, AA contrast, renders" is checkable; "right hierarchy / matches the product's voice" is not,
  because there is no **product-specific design reference** to diff against.
- Leverage: a curated **golden reference gallery** ("what good looks like here") + a **design-critic pass**
  that critiques against *this* system's tokens/patterns, not generic Material heuristics. This is a genuine
  LLM strength: describe the UI back, check the description against intent (self-consistency).

### Remember — every session re-perceives from scratch
- Baselines are pass/fail pixel diffs, not semantic. Leverage: a **perceptual changelog** — "the primary
  button moved 8px and dropped to 3.9:1 contrast" — what changed and whether it's intentional.

### Agent-as-worker meta-layer
1. **Exhaustiveness is the superpower.** A human won't patiently render {long filename, empty, 10k results,
   error, RTL} × {viewports} × {themes} × {lifecycle states}; the agent can, in parallel. UX bugs live in
   those edge states, and demo mode hides them. A **state-matrix fuzzer** is probably the highest-value
   agent-native capability here.
2. **Images are expensive context.** Each PNG burns tokens, so visual working memory is tiny — another
   reason to prefer measurement-first. (External reporting now quantifies this; see §4.)
3. **Tool freshness is a real failure mode.** See §3 — a stale index silently makes the agent "verify"
   nothing. Any capability needs a freshness invariant or it rots.

## 3. Concrete repo finding (action item regardless of the theory)

The flagship visual harness is **largely pointed at deleted code.** `scripts/jseval/jseval/ui_step_index.json`
and the bulk of the 47 `ui-check` steps map to `modules/ui-web/src/components/**/*.tsx` — the **retired React
stack** (ADR-0032). That directory no longer exists; the live UI is `modules/ui-web/src/shell-v0/`. Only a
handful of `required=False` steps (`shell-v0-demo*`, `presentation-demo*`) target the current stack, and
being non-required they do not gate. The `ui-shot-hint` hook is keyed off the same stale index.

Consequence: the agent's primary "see what I built" tool mostly screenshots components that aren't shipped,
while the real UI has thin, non-blocking coverage — and the agent can confidently report a verification that
looked at the wrong thing.

Suggested follow-up (not in scope of this theory doc): re-aim `ui_step_index.json` + the `ui-check` step
registry at `shell-v0`, promote shell-v0 steps to required, and add a **freshness gate** that fails if any
step targets a path that does not exist on disk.

## 4. External validation (research, June 2026)

A web survey of last-month reporting independently converges on this doc's central bet. Highlights:

- **Structured data beats screenshots for agents — quantified.** A clean accessibility-tree snapshot is
  ~4,000 tokens vs ~50,000 for the equivalent screenshot (a page yields 15,000+ tokens of visual noise).
  Directly supports "measurement over vision" and "images are expensive context."
- **Empirical proof structure drives success.** A 2026 UC Berkeley / U. Michigan study: agent task success
  dropped 78% → 42% with a degraded accessibility tree; ~half of failures were missing *structural* info,
  not pixels.
- **Hybrid is the settled architecture.** Production agents (OpenAI Atlas, Microsoft Playwright MCP,
  Perplexity Comet) lead with accessibility/DOM data and use vision for disambiguation — "screenshot for
  gestalt, measurement for correctness."
- **Vision precision is improving selectively.** Anthropic Computer Use added a **Zoom Action** (inspect
  small elements at high res before acting) — a real answer to the DPI/precision limit, via *selective*
  zoom rather than uniformly bigger frames.
- **observe-think-act (Sense-Think-Act) is now the named standard**, standardized over MCP — i.e., the
  "interaction trajectory" loop is the assumed default, not a frontier idea.
- **Semantic visual-diffing is already a product category** (Applitools Visual AI, Percy Visual Review
  Agent): "is this shift *meaningful*?" rather than pixel diff — the "perceptual changelog" exists
  commercially, and pixel-diffing is widely reported as more noise than signal.
- **Design-quality judgment was productized as a reference, not innate taste** (Lovable, V0, Magic Patterns,
  Uizard, Builder 2.0 "visual verification built in") — matches the "golden reference gallery" idea.
- **Accessibility is now also agent-legibility** ("your website is an API for AI agents"): the repo's
  existing a11y gates are not just compliance — they are the perception channel this doc argues for.

Caveat: most sources are stamped 2026 broadly; the clearly within-the-month items are the MarkTechPost
coding-agents roundup (2026-06-10), Google Antigravity (2026-05-19), and the browser-automation
state-of-2026 reports.

### Sources
- https://www.browserless.io/blog/state-of-ai-browser-automation-2026
- https://isagentready.com/en/blog/how-ai-agents-see-your-website-the-accessibility-tree-explained
- https://nohacks.co/blog/how-ai-agents-see-your-website
- https://www.marktechpost.com/2026/06/10/ai-coding-agents-development-platforms-2026/
- https://www.builder.io/blog/best-llms-for-coding
- https://dev.to/drizzdev/mobile-visual-regression-testing-in-2026-why-vision-ai-catches-what-script-based-tools-miss-2bfm
- https://getautonoma.com/blog/visual-regression-testing-tools
- https://percy.io/blog/ai-visual-testing-tools
- https://annebovelett.eu/your-website-is-now-an-api-for-ai-agents-and-youre-not-ready/

## 5. Where to go next → see §6 (design) and §7 (reach)

The original "open questions" are resolved by the design below. The short version: the §3 fix and the
Perceive/Act/Remember faculties are **not new infrastructure** — they are instances of two seams already in
the repo. Judge-quality and the state-matrix fuzzer are recognized but deliberately **not built now** (§6.4,
§7).

## 6. Long-term design

### 6.0 What the investigation found (the two seams already exist)

A survey of the repo's verification infrastructure shows the tempdoc's central thesis is **already encoded in
two places** — the design's job is to make the agent's UI/UX loop *conform* to them, not to invent a parallel
mechanism.

- **Seam A — register → gate → guard-resolution** (governance kernel, `scripts/governance/run.mjs` +
  `governance/*.v1.json`). A *register* is a declared catalog of entries; a *gate* asserts positive coverage
  and that every declared reference resolves. The universal `register-guard-resolution` gate (tempdoc 576
  §3.1) already fails the build on a **dangling** `gate:`/`test:` reference and on a **bare/unguarded** entry,
  with a guard-strength hierarchy (real-guard > accountable-exempt > forbidden-bare) and a no-silent-downgrade
  rule. The presentation-authority gates (559: `check-a11y-closure`, `check-layout-purity`,
  `check-adaptive-closure`, `check-declared-surfaces`, `check-contrast-matrix`) are all *catalog projections*
  in this same shape — they already "convert visual properties to assertable measurements," but **statically**
  (from source/tokens), never from a running render.
- **Seam B — EvidenceBundle + determinism-budget** (`scripts/dev/justsearch-dev-mcp/` + `scripts/evidence/`).
  The `capture_evidence` / `validate_evidence` tools produce a structured bundle (`run-metadata.json` +
  content-addressed `artifacts[]` with sha256, already including `browser-console.json`,
  `browser-network.json`, `ui-screenshots/`). Critically, its **determinism budget** already counts
  `assert.screenshot_only.count` and `log.scrape_unstructured.count` as *budgeted liabilities* — i.e.
  "don't assert on a pixel; produce structured evidence" is **already a validated policy** in this repo.

The decisive observation: the `ui-check` / `ui-shot` harness sits **outside both seams**. Its Step registry +
file→step index (`scripts/jseval/jseval/ui_step_index.json`) is an **ungoverned register** (only the *hook
wiring* is governed, in `agent-hooks.v1.json` — not the entries), and it captures **only screenshots** and
asserts only on capture-success — so it *structurally violates* Seam B's screenshot-only-is-a-liability
stance. §3 (steps aimed at deleted React paths) is the visible symptom of being ungoverned.

### 6.1 Conform §3 + coverage to Seam A (the part the problem requires now)

Bring the visual harness's coverage under the same register-guard-resolution discipline the product registers
already obey. At the general level:

- Each step **declares the surface it covers and the source path(s) it depends on**; a gate fails if any
  declared path does not resolve on disk. A deleted path becomes a *dangling reference* — the exact failure
  class `register-guard-resolution` already catches — so §3 can never silently recur.
- **Coverage is projected from the live surface catalog**, not hand-listed: every `shell-v0` surface in the
  authoritative surface catalog (`CoreSurfaceCatalog`) must have a covering step, the way `check-a11y-closure`
  projects landmark coverage from `PLACEMENTS` and `check-declared-surfaces` projects renderer coverage from
  `declared-surfaces.v1.json`. The "barely covered shell-v0 / non-required steps" problem dissolves: coverage
  becomes an obligation, not an option.

This is the whole of what the *present* problem requires. It is a re-aim + a coverage gate, sized to a live
defect plus its recurrence-prevention — no more.

### 6.2 Conform Perceive / Act / Remember to Seam B (settled principle, incremental build)

These three faculties are **one move**: capture structured measurement as additional EvidenceBundle
*artifacts* from the same Playwright page the screenshot already comes from, and let assertions target those
artifacts instead of the PNG. This satisfies the determinism budget that today has a stick (`screenshot_only`
is penalized) but no carrot (no structured artifact exists to assert against).

- **Perceive** → a *visual-properties artifact* (DOM geometry, computed styles for the touched elements, the
  accessibility tree) joins `browser-console.json`/`browser-network.json` in the bundle. Same shape, same
  hashing, same validator. (This is also the industry consensus from §4: assert on the a11y-tree/DOM, attach
  the screenshot.)
- **Act** → the Step already carries `setup` (a scripted interaction) and `depends_on` chains; a *trajectory*
  is a `setup` that **records its measurement trace**. The missing piece is trace-capture (a bundle-artifact
  concern), not a new step abstraction.
- **Remember** → bundle artifacts are already content-addressed; a *perceptual changelog* is a **semantic diff
  of two bundles' structured artifacts** ("primary button moved 8px, contrast 4.6→3.9"), expressed over
  geometry/contrast/a11y deltas — not a pixel diff. The repo's ui-check baseline-diff is the degenerate
  pixel-only version of this.

The screenshot demotes to a **non-asserting attachment** (gestalt sanity only) — which is exactly the
determinism budget's stated intent. Build order is incremental: add the artifacts first; assertions migrate
onto them over time. The principle is settled; the structure is small and additive, so no rewrite.

### 6.3 Reuse the a11y substrate as the perception channel

Do **not** build a second structured-perception channel. The accessibility tree the 559 gates already treat
as truth *is* the perception channel §6.2 needs; the `check-contrast-matrix` color math is design-rule-as-
measurement already. The design's perception artifacts should be the same data these gates consume, captured
live instead of statically.

### 6.4 Deliberately NOT built now: Judge-quality and the state-matrix fuzzer

- **Judge-quality** (visual hierarchy, rhythm, brand feel) is the one faculty that genuinely does **not**
  reduce to a gate — it needs the running render + the LLM + a product reference. The live tier already exists
  (`ai_activate` + claude-in-chrome). The "golden reference," *if/when* it is built, is itself a register (a
  catalog of approved states) and would conform to Seam A — but the present problem does not require it, so it
  stays an agent-driven, reference-anchored activity, not new structure. (YAGNI: building a "design-critic
  engine" now is premature abstraction.)
- **State-matrix fuzzer** (the exhaustiveness bet): the substrate already exists — `isolated` steps run in
  parallel, and §6.1 makes them a governed register. A fuzzer is a *generated* cross-product of steps over
  {data-extreme × viewport × theme × lifecycle}. Recognized as the highest-value agent-native capability, but
  it is an extension of the governed registry, not a precondition for it. Defer until §6.1/§6.2 land.

### 6.5 Scope summary (asymmetry is intentional)

| Faculty / item | Disposition | Seam |
|---|---|---|
| §3 stale index + shell-v0 coverage | **Build now** — required by a live defect | A (register-guard-resolution + projected coverage) |
| Perceive / Act / Remember | **Build incrementally** — settled principle, additive artifacts | B (evidence artifacts + determinism budget) |
| Perception channel | **Reuse, don't duplicate** | 559 a11y/contrast substrate |
| Judge-quality | **Do not build** — agent + live tier + reference; register-shaped if ever | (A, latent) |
| State-matrix fuzzer | **Defer** — substrate exists, not a precondition | A (generated steps) |

## 7. Reach & principle

Two principles surface that reach beyond this tempdoc. Both are **named, not built** — recording the insight
without manufacturing the generalized structure.

### P1 — Verification infrastructure is product infrastructure; hold it to the same coverage/freshness discipline.
A verifier pointed at deleted code is the **same failure class as a dangling guard**: a gate that reports
green while asserting nothing. The repo already enforces this for *product* registers (execution-surfaces,
operation-surfaces, declared-surfaces …) via `register-guard-resolution`. The tempdoc reveals that the
**agent's own tooling registers escape it** — `ui_step_index.json` is the live violation (§3). Candidate
scope: *every hand-maintained index that pairs a declared key to a code path.* `scripts/agent-analytics/lib/
governed-regions.mjs` is an adjacent candidate (it pairs region predicates to governing docs) — lower risk
because it matches by `includes()` predicate rather than literal path, but it is unchecked. **Do not** build a
universal index-governor now; §6.1 applies the existing gate to the one register the present problem needs.

### P2 — Assert on measurement; the screenshot is a non-asserting attachment.
Already a settled invariant *inside* the evidence bundle (the determinism budget's `assert.screenshot_only`
counter). The reach: it should generalize from evidence-bundles to the `ui-check` harness, which **violates it
by construction** today (it asserts only on capture-success of a PNG). §6.2 is the conformance path. The
external research (§4) is the same principle arrived at independently: structure-first (a11y-tree ≈4k tokens
vs screenshot ≈50k), vision as selective fallback.

### The deeper shape both are instances of
Both reduce to a rule the codebase already lives by (tempdoc 553 representation-drift, 576 guard-resolution):
**a dependency you cannot see drifting will drift silently — make it a declared, resolved, positively-covered
reference so drift becomes a build failure.** The tempdoc's only new contribution is noticing this rule has
not yet been applied to the agent's *perception and verification* layer, which currently sits outside it. That
is the recurring invariant to remember; the immediate work (§6.1) is just its first application here.

## 8. Pre-implementation findings (confidence pass, 2026-06-19)

A read-only investigation + live capture attempt, run **before** any feature work, to test the design's
load-bearing assumptions. Verdicts are backed by cited files / greps / an actual rendered screenshot, not the
subagent reports §6 was built on. **Net: the design's *direction* holds, but §6.1's *scope* was understated —
the harness defect is worse than "stale index," while two pieces of good news (a light gate tier; an existing
Node evidence substrate) partly offset.**

| # | Uncertainty | Verdict | Evidence |
|---|---|---|---|
| U1 | Harness renders live Lit, or dead React? | **Refuted the cheap-fix framing** | `:5174` root = **blank white page** (React `#root`/`main.jsx` entry, demo dead). All 5 jseval captures (`home`, `search-results`, `inspector-open`, `presentation-demo`) fail identically waiting for `search-input` because `_demo_url` appends `?demo=true` → the dead React demo. Live Lit shell *does* render (pre-existing `System · JustSearch` tab on `:5173`, surface-hash routing) — but the harness is not wired to it. |
| U2 | Machine-readable coverage-projection source? | **Partial** | `CoreSurfaceCatalog` is Java (`app-observability`); FE gates project from TS (`surface.ts` `PLACEMENTS`, `landmarks.ts`) / JSON registers. Surface ids (`core.search-surface`) live in TS router/plugins — projectable. But many chain steps are *interactions* (inspector/summarize/citation), not surfaces — real cardinality mismatch; coverage can't be a clean 1:1 surface→step projection. |
| U3 | Does "declared path resolves on disk" = `register-guard-resolution`? | **Refuted (design imprecise)** | `guard-resolver.mjs` resolves only `gate:`/`test:` **tokens**, not arbitrary path keys. The correct mechanism is a light positive-coverage gate in the `check-declared-surfaces` shape, **not** the kernel meta-pass. §6.1's "conform to register-guard-resolution" is corrected to "conform to the 569/559 positive-coverage gate shape." |
| U4 | Is EvidenceBundle capture implemented end-to-end? | **Confirmed gap; substrate exists elsewhere** | The MCP-referenced producer `modules/ui-web/scripts/capture-evidence-bundle.mjs` is **absent**, so `capture_evidence` is a stub. BUT a real determinism/evidence harness exists in `modules/ui-web/e2e/` + `scripts/lib/determinism-budget.cjs`, and real bundles were produced (`agent-review/ui-screenshots/…`, Feb 2026). So §6.2 builds on an *existing Node substrate*, not greenfield — but not via the MCP path. |
| U5 | Is `assert.screenshot_only` enforced or aspirational? | **Confirmed dormant** | Validator (`validate-determinism-budget-v1.mjs`) + budget lib are real code, but the only CI wiring (`build-installer.yml`) depends on the **absent** producer — so the policy is currently *dormant*, not actively failing builds. P2's "settled invariant" is real-in-code but not-live. |
| U6 | One Playwright stack or two? | **Two** | Python (`scripts/jseval/jseval/ui_*.py`, the ui-check/ui-shot harness) and Node (`modules/ui-web/e2e/`, the evidence/determinism harness). §6.2 measurement-capture lives in one of them; they do not share a page. |
| U7 | Gate-authoring cost? | **Confirmed cheap** | `check-declared-surfaces`/`check-a11y-closure` are standalone `scripts/ci/*.mjs` wired directly in `ci.yml` (`node scripts/ci/check-…mjs`) — **no** truth-table/fixture/registry ceremony. §6.1's coverage gate is a light script, not a kernel gate. |

### Scope corrections to §6

- **§6.1 is larger than stated.** Not "re-aim `ui_step_index.json`." The harness's *runtime entry* (`?demo=true` → dead React) must move to the Lit entry (`?shell-demo=1` / surface-hash routing), and ~half the chain-step selectors (inspector/summarize/citation/health/action — testids **absent** in shell-v0) must be rewired to the live Lit testids (the 180 that exist, e.g. `agent-activity-panel`, `ai-digest`, `authorization-*`). The file→step index re-aim is the *smallest* part. Offsetting good news: the **gate** that prevents recurrence is cheap (U7), and search/nav steps already target live Lit (`search-input`, `core.*-surface` exist).
- **§6.1 mechanism corrected** (U3): a light `scripts/ci` positive-coverage gate (569/559 shape), not the `register-guard-resolution` token meta-pass. P1's "same gate" is directionally right (positive-coverage discipline) but the *specific* gate named was wrong.
- **§6.2 has a missing producer but a real substrate** (U4/U6): build measurement-capture into the existing `modules/ui-web/e2e/` Node harness (which already carries the determinism budget), and treat the MCP `capture_evidence` stub as a separate wiring gap. Two stacks means the Python ui-shot loop and the Node evidence loop stay distinct unless deliberately unified.
- **P2 is real-in-code but dormant** (U5): the "settled invariant" framing should read "encoded but not currently enforced (producer absent)."

### Critical confidence rating: **5 / 10**

The investigation *raised* my understanding while *lowering* my confidence that the work is small. The two
factors that move it most:

- **Down:** U1 — the visual harness is wired to a dead React entry, so §6.1 is a harness *migration* (entry +
  ~half the selectors), not an index re-aim; and the running dev servers were flaky/stale (`:5173` erroring,
  `:5174` serving a blank React page, CDP renderer timeouts), so even *verifying* a fix will be friction-heavy
  until a clean Lit dev-serve path is established.
- **Up:** U7 + U4 — the recurrence-preventing gate is a cheap, well-patterned `scripts/ci` script, and §6.2
  rides an existing Node evidence/determinism substrate rather than greenfield. The *direction* (measurement-
  first, positive-coverage gate, reuse a11y substrate) survived contact with the evidence intact.

A 5 reflects: clear path, no architectural surprises, but real execution friction and a doubled §6.1 scope.
The single highest-leverage next de-risk before implementing would be to **establish a clean, current Lit
dev-serve + one passing live-Lit screenshot** (the experiment the stale `:5174` server blocked today) — that
alone would move the rating to ~7.

## 9. Implementation pass (2026-06-19) — as-built + a corrected mechanism + a live blocker

### Mechanism correction (supersedes §8's pivotal U1 framing)
The §8 "harness boots dead React" conclusion was **wrong in mechanism**. `main.jsx` has **no `?demo=true`
branch**, so `_demo_url`'s appended `?demo=true` is **inert** — the harness already falls through to the
production `<jf-shell>` Lit boot. The §8 blank `:5174` page was a *stale dev server*, not a React entry. The
real blockers are three, all confirmed by reading the live source + the harness:
1. **No demo-data mode exists** (only `.test.ts` mocks); the production shell needs a **live backend** for any
   data-bearing step.
2. **Default landing surface changed** from search (React) to **`core.unified-chat-surface`** (Lit), so the
   `home`/`search` view steps must navigate to the search rail first — they previously assumed search was the
   landing.
3. **The chain steps are demo-era simulations** — `setup_streaming`/`setup_summarize_done`/`setup_citation`
   wait on `"Generating..."` / `"This is a simulated AI summary"` and `demo_stream_delay_ms`, none of which
   exist anymore; plus `_density_setup`/`_mode_setup`/`_ensure_file_selected` drive a dead React global
   `window.__JUSTSEARCH_STORES__`. The chain is a **rewrite against the live AI backend**, not a selector
   repoint.

### Built + validated (Phase 1 — §6.1a, fully green)
- New gate `scripts/ci/check-ui-step-coverage.mjs` + register `governance/ui-step-coverage.v1.json` (cloned
  from the `check-declared-surfaces` shape). **(a) freshness:** every source path the step index maps must
  resolve on disk; **(b) coverage:** every `placement:'RAIL'` surface in `CORE_SURFACES` is covered by a view
  step or a declared exemption. Wired into `.github/workflows/ci.yml` + the CLAUDE.md pre-merge list.
- **Validated both ways:** passes on the fixed registry (25 paths resolve, 5 RAIL surfaces covered); a bogus
  path makes it fail with the precise stale-path message. `check-workflow-triggers` green.
- Rewrote `scripts/jseval/jseval/ui_step_index.json` from the retired `components/**/*.tsx` paths to live
  `shell-v0/**` owners (gate-enforced now, so this class can't silently recur).

### Built + LIVE-VALIDATED (Phase 2 — §6.1b view migration; stack taken over with user authorization)
With the user's explicit authorization, the dev stack was taken over (`takeover:"warn"`), a clean stack
started from `main`, and the model activated (`ai_activate` → Qwen3.5-9B). Source-grounded edits + the live
fixes below were then **validated by reading real screenshots**:
- `ui_selectors.py`: repointed the absent React testids to live hooks (`inspector-pane` →
  `[role="region"][aria-label="Inspector"]`; citation → `.cite-ref`/`[data-cite-key]`; health → rail
  `data-surface-id`).
- `ui_check.py` root-cause fix: `_run_isolated_step`'s app-ready gate waited for `search-input`, which only
  exists once the search surface is active — but the live shell lands on **chat**, so *every* isolated step
  hung. Changed the readiness signal to the **rail** (present on every surface). This single fix unblocked all
  view steps.
- `_view_setup` now navigates **every** view (incl. home/search) to its surface, with a **hash-route fallback**
  (`#justsearch://surface/<id>`) for off-rail DEEPLINK surfaces (Health/Help). `_type_and_search` navigates to
  the search surface first. Retired the `action-panel` steps (no shell-v0 equivalent).
- **PROVEN LIVE (real screenshots, real backend data):** `home`, `library`, `settings`, `health`, `help`,
  `ai-brain` all render the live Lit surfaces (e.g. Health shows Files 636 / GPU DETECTED 8.84-of-11.99 GB /
  "Processing 34 items running"); `search-results` captured "Top 50 of 417 matches" with real files. The
  migration's core is validated end-to-end through the browser.

### AI chain — REWRITTEN + functionally validated live
`inspector-open` (row-click → `jf-inspector-pane`), `streaming`/`summarize-done` (rewritten to drive the
**real** `/api/chat/agent` SSE: switch to the Ask tab → type → Ctrl+Enter → answer streams into
`<jf-markdown-block>`) are validated by a real screenshot: `summarize-done.png` shows the model streaming
"JustSearch is a local-first knowledge search engine that indexes your files…" in the live inspector Answer
tab (genuine output, not the retired `"simulated AI summary"`). `citation-highlight` rewired to the live
`.cite-ref` contract. The chain works end-to-end; its e2e *reliability* is limited by 9B-model first-token
latency under **shared-GPU contention** (a parallel worktree session — `tempdoc-610` — was using the same
stack/model), which intermittently pushes generation past the wait window. No code defect — an environmental
contention limit on the AI legs.

### Remaining work (a mechanical long-tail + Phase 3)
The non-AI interaction steps still carry React-era couplings and are **entangled with the dead-code cleanup**
(removing the unused `TID_*` constants / `__JUSTSEARCH_STORES__` refs only lands when their steps are
rewritten): `filters-chips` (live filters are an always-visible `filter-from/-to/-clear` row, no toggle),
`multi-select` / `context-menu` (live row-click `selectionState` + the `ContextMenu` element, not the old
checkbox + `__JUSTSEARCH_STORES__` global), `qa-response`, and the `density`/`mode` setups (move off
`__JUSTSEARCH_STORES__` to the live Settings path). Plus `citation-highlight` reliability on a non-contended
stack, and Phase 3 (§6.2 measurement artifacts). This is a clearly-scoped mechanical tail best finished in one
pass on a stack that isn't being cycled by a parallel session.

### As-built status (current truth, supersedes the §8 framing)

| Item | State | Evidence |
|---|---|---|
| §6.1a coverage+freshness gate (`check-ui-step-coverage.mjs` + register, CI + CLAUDE.md) | **Done, validated** | green on fix; fails on injected dead path |
| `ui_step_index.json` re-pointed to live `shell-v0` owners | **Done, gate-locked** | gate freshness check |
| View steps (home/library/settings/health/help/ai-brain) | **Done, live-validated** | 6 real screenshots, real data |
| `search-results` | **Done, live-validated** | "Top 50 of 417 matches" |
| `inspector-open` (row-click → `jf-inspector-pane`) | **Done, live-validated** | inspector PNG |
| AI chain `streaming`/`summarize-done` (real `/api/chat/agent` SSE) | **Rewritten, live-validated** | `summarize-done.png` real streamed answer |
| `qa-response` (live Ask flow) | **Rewritten, live-validated** | `qa-response.png` real agent answer |
| `filters-chips`, `multi-select`, `context-menu`, `selection-preserved` | **Rewritten, live-validated** | facet chip / multi-row selected / real context menu / row-click select PNGs |
| `density` / `mode` (`search-results-compact`, `search-advanced-mode`) | **Rewritten, live-validated** | live Settings round-trip → search PNGs |
| Dead-code cleanup (`TID_*`, `__JUSTSEARCH_STORES__`, dead setups, `_ensure_file_selected`, `# React app` comment) | **Done** | grep: 0 React-era refs in harness code; 55 steps build clean |
| Retired (no shell-v0 equivalent): `action-panel*`, `context-near-limit/too-large/details-expanded`, `error-retryable`, `button-active` | **Done** | removed steps + setups |
| `citation-highlight` (`.cite-ref` contract) | **Rewritten, LIVE-VALIDATED** | Answer tab shows inline `[1]`/`[2]` `.cite-ref` marks on a real grounded answer (§10); AI-latency-sensitive, may need a retry on a fresh model |
| `mocks/fixtures.mjs` (orphaned React-era demo data) | **Logged** to `docs/observations.md` (deletion candidate, out of harness scope) | — |
| §6.2 measurement-first companion (`ui_measure.py`) — **the thesis / P2** | **DONE, live-validated** | every capture writes `<step>.measure.json` (shadow-piercing a11y landmarks + injected axe + geometry/overflow + console); see §10 |

**17 steps live-validated by real screenshots** (6 views + search-results + inspector-open + streaming/summarize-done + qa-response + filters-chips + multi-select + context-menu + selection-preserved + search-advanced-mode + search-results-compact). The harness is migrated and the React-era residue is gone from the harness code; only `citation-highlight` reliability and Phase 3 remain.

**One-pass-to-finish note:** the remaining tail is mechanical (step rewrites against the live model) but
needs a stack **not being cycled by a parallel worktree session**, so the AI legs validate reliably and the
GPU isn't contended. That is the only real precondition left.

## 10. §6.2 measurement-first — IMPLEMENTED & LIVE-VALIDATED (2026-06-19); P2 satisfied

The tempdoc's titular thesis (measurement over vision / P2) is now built. `scripts/jseval/jseval/ui_measure.py`
captures a structured **measurement companion** next to every screenshot, wired into the ui-shot/ui-check
capture path (`_capture_shot` → `capture_measure`, console sink threaded at every page-creation site;
`ShotResult`/`EvalResult.to_dict`/`execute_ui_shot` carry it; cli prints a one-line fact summary; default-on,
`--no-measure` to skip). It **reuses** rather than reinvents: the same axe-core bundle the e2e harness ships,
a console-collector port of `e2e/ai-harness.ts`, and **shadow-piercing** geometry/a11y probes (the native
`page.accessibility.snapshot()` returns None on this Lit shadow-DOM app, so a deep `shadowRoot`-recursing walk
is the authoritative perception channel — §6.3).

**Live-validated (read-only against the running stack, worker-only — no takeover):** `ui-shot home` produced
`home.measure.json` whose facts MATCH the rendered UI, cross-checked against the PNG:
- **a11y_landmarks (63)** — `role=banner` "JustSearch", `h1 "Search"`, `jf-rail role=navigation "Surfaces"`,
  `div role=main "Main content"`, `input role=searchbox "Search files"`, `role=tablist "Search scope"`
  (Documents / Agent history) — exactly the search surface.
- **geometry (7 els)** — banner `(0,0,1280,40)`, rail `(0,40,52,680)`, main stage `(52,40,1228,652)`, `h1`,
  rail button, `jf-stage` — correct layout; `overflow none`.
- **axe** — 24 passes + 1 critical `aria-valid-attr-value` on `jf-shell,jf-stage,jf-search-surface` (real).
- **console (43)** — `Failed to load resource … 502 Bad Gateway` (the auto-serve had no backend; correctly
  captured), matching the "Connecting…" status bar in the PNG.

The screenshot is now the **gestalt attachment**; the `<step>.measure.json` is the asserted fact sheet — the
agent makes a correctness judgment from the small JSON without opening the PNG. **P2 is satisfied.** ("Add the
artifacts first; assertions migrate over time" — the summary surfaces advisory flags `console-errors` /
`axe-serious`; a gate failing on them is the noted next increment, not built.)

### §6.2 `citation-highlight` — LIVE-VALIDATED (2026-06-19)
The `citation-highlight` rewrite (wait for stream-complete → `.cite-ref`; retrieval-grounded question that
forces a search-tool call so `sources`+`citations` attach) is now **confirmed live**. After the contended
dev stack was *released* (lease went stale → reaped), a fresh stack was started from a free state (not a
takeover of an active owner), `ai_activate`'d, and `python -m jseval ui-shot citation-highlight
--ui-url http://127.0.0.1:5173` run. The captured PNG shows the inspector **Answer tab** with a real grounded
agent answer about `write-a-plugin.md` carrying **inline `[n]` `.cite-ref` marks** — e.g. "…creating custom
plugins **[1]**. The guide walks through writing a complete V1.5 plugin from scratch **[2]**." (the grounded
agent ran 2 search tools + ~42 answer chunks; `sources`+`citations` attached on the `done` SSE → MarkdownBlock
rendered the marks). A measurement companion was captured alongside (125 a11y landmarks, axe 0 violations).
Caveat: the AI legs are latency-/contention-sensitive (the 9B model unloads under VRAM pressure from parallel
sessions), so the chain can need a retry on a freshly-activated model — but the pipeline + render are proven.

## 11. What the measurement substrate unlocks — research-grounded extension space (2026)

> Forward-looking research (2026-06-19), not a build plan. Now that §6.2 shipped, the harness emits a
> structured, diffable, assertable **fact-set** per UI state (`<step>.measure.json`: shadow-piercing a11y
> landmarks + axe + geometry/overflow + console). That one affordance turns "the UI" from *pixels you look
> at* into **data you can query, diff, assert, reason over, generate at scale, and feed back**. Each verb is
> a concrete extension; all six are validated by current (June 2026) research. Context: no users, not
> production — value is **agent/DX capability** (the agent IS the only user), not user features.

### Meta-frame: the substrate is the *verifier* in a closed UI-building loop
The 2026 frontier in agent work is **"loop engineering"** — designing the build → **measure** → critique →
repair → iterate loop, where *"the verifier is the bottleneck, not the model"* and you *"define 'done' in
measurable terms, then let the loop run against your bar."* The tempdoc's thesis (measurement over vision) IS
that bar: `measure.json` is the machine-checkable definition of "is this UI right." So the deepest unlock is
not any single feature — it is a **closed, autonomous UI self-improvement loop** with no human eyeball in the
inner loop.

### The six verbs (what · 2026 grounding · class · feasibility)

| Verb | Extension | 2026 grounding | Class | Feasibility |
|---|---|---|---|---|
| **DIFF** | **Perceptual changelog** — semantic diff of two `measure.json` ("button moved 8px, contrast 4.6→3.9, new axe violation, heading order broke") not a pixel diff | Industry moved to **DOM/a11y-structure diffing** to separate real change from rendering artifact; catches hidden interactive elements + bad heading order | Extend (script over existing artifacts) | **High** |
| **ASSERT** | **Live UX quality gate** — baseline that fails on NEW axe violations / console errors / contrast regressions / overflow / focus-order breaks; the *rendered* counterpart to the static 559 gates | "Visual + a11y regression wired into CI"; "evaluation is a continuous cycle, not a one-time gate" | Extend | **High** |
| **REASON** | **Design-critic (the deferred Judge §6.4)** — LLM critiques `measure.json` vs a machine-readable design reference + rubric, iteratively | UICrit / "Visual Prompting for Design Critique" critique **from JSON grounded in bounding-box regions** (= my a11y+geometry); **AdaRubric** task-adaptive rubrics; Anthropic: "unit tests for correctness + **LLM rubrics for quality**" | New | **Medium** (needs reference, small) |
| **GENERATE** | **State-matrix fuzzer (Exhaustiveness superpower)** — steps over {data-extreme × viewport × theme × lifecycle}, measure each, surface anomalies | Generative/model-based fuzzing (unlimited cases); **DeepUIFuzz** fuzzes style dims; AI fuzzing for empty/long/error states | New | **Medium** |
| **TRACE** | **Interaction-trajectory measurement (half-built Act)** — measure-trace *over* a flow (network timing, console, layout-shift), not at rest | observe-think-act is settled; agent eval **scores multi-step trajectories** | Extend | **Medium** |
| **HARDEN** | **(a) Self-healing selectors** — resolve by a11y role+name (from the measure substrate) not brittle testids; **(b) MCP-serve `measure.json`** so any agent queries the live UI as data | Teams spend **40–60% of QA time** on tests broken by routine UI changes; 2026 fix = **intent-based resolution** + Playwright v1.56 test agents; design systems served to LLMs via **MCP** | Polish + Extend | **High** |

### The machine-readable design reference (the missing piece for REASON)
The Judge faculty stalled on "no product-specific design reference to diff against." 2026 gives it a proven
form: Google Labs open-sourced **DESIGN.md** (machine-readable tokens + human rationale); **Indeed** A/B-tested
five encodings and **JSON won** (best accuracy, ~80% fewer tokens — *"JSON is a contract: explicit keys,
values, no ambiguity"*), served via MCP, with the caveat to layer **always-on foundation rules**
(typography/spacing/color) under **on-demand component specs** or the LLM hallucinates foundations. JustSearch
already has the foundation (the token system + 559/576 presentation authorities) — a small
`design-reference.v1.json` (tokens always-on + per-surface spec) is all REASON needs.

### Two standout, high-ROI ideas (agent-as-only-user)
1. **The harness hardens itself.** The a11y tree `measure.json` already captures is *exactly* what
   intent-based / self-healing selectors need. Resolving steps by role+accessible-name instead of testids
   would have made this session's whole React→Lit selector migration **a non-event** — and forecloses that
   brittleness class. Highest DX ROI; reuses the substrate already built.
2. **A closed design-loop.** generate a surface → `measure.json` → LLM critic vs `design-reference.v1.json`
   (+ a11y/contrast facts) → fix → re-measure — autonomous, because the bar is measurable, not a screenshot.
   The four faculties (Perceive/Act/Judge/Remember) finally composed into one loop.

### Polish/simplify on the *current* code (cheap, do alongside)
- **Denoise `console_errors`** — dev/HMR/proxy-502 noise dominated the home run (43); categorize by
  source/severity so the flag means "real error."
- **Richer summary facts** — add the §2 Perceive leverage still unbuilt: 4px spacing-grid adherence,
  heading-hierarchy order, focus/tab order, z-order conflicts (all derivable from the a11y+geometry captured).
- **Schema-version `measure.json`** for stable diffing (DIFF depends on it).
- **Perf** — axe adds ~1–2s/step; keep default-on for single `ui-shot`, opt-out for the ~60s `ui-check` batch.

### Recommended sequence (no rush; all viable)
HARDEN (self-healing selectors) → DIFF (perceptual changelog) → ASSERT (live quality gate) → REASON
(design-critic + reference) → GENERATE (state-matrix fuzzer) → TRACE (trajectory). The first three are cheap
`extend`/`polish` over artifacts that already exist; the last three are the "new faculties" the tempdoc
deferred, now de-risked by concrete 2026 patterns.

### Sources (2026)
- Semantic DOM/visual diff: saucelabs.com/.../20-best-visual-testing-tools-of-2026 · mabl.com/blog/visual-ai-context-aware-regression-detection
- LLM design critique / rubrics: arxiv.org/pdf/2412.16829 (Visual Prompting for Design Critique) · arxiv.org/html/2603.21362v1 (AdaRubric) · arxiv.org/html/2509.16779v2 (UI gen from designer feedback)
- Machine-readable design systems: intodesignsystems.substack.com/p/ai-design-system-mcp-example (Indeed) · medium.com/@mohitphogat/your-design-system-isnt-ai-readable-yet · DESIGN.md (Google Labs/Stitch)
- UI fuzzing: DeepUIFuzz (ICSE-2025 SBFT) · blackduck.com/blog/unlimited-test-cases--the-power-of-generative-fuzzing
- Self-healing tests: shiplight.ai/blog/what-is-self-healing-test-automation · testdino.com/blog/playwright-ai-ecosystem · medium.com/qawolf/the-6-types-of-ai-self-healing-in-test-automation
- Loop engineering / closed-loop eval: aibuilderclub.com/blog/loop-engineering-guide-2026 · emergentmind.com/topics/closed-loop-generation-and-evaluation

## 12. Dogfooding critique (2026-06-20) — using the tool against its own goal (agent velocity)

> The tool exists to improve **agent velocity + experience**. I dogfooded it (cold + warm `ui-shot` captures,
> read the `measure.json` AND the PNG) and graded it honestly against that goal. Several findings *correct
> claims made earlier in this very tempdoc* — recorded here so the doc doesn't keep selling them.

### What genuinely helps velocity (real wins)
- **Zero setup** — no server was running; the tool auto-started its own Vite. No `npm run dev` ceremony.
- **Warm loop is fast** — ~2.1s/capture once the server is up (cold start ~36s, dominated by Vite).
- **axe catches bugs the eye misses** — on `home` it flagged a *critical* invalid-ARIA-value on the search
  surface and a *serious* contrast failure on the task-list "RUNNING" badges — both invisible to a screenshot
  glance, both actionable with selectors. The tool's strongest, most defensible win.
- **The one-line summary triages well** — `library` clean (0/0/0) vs `home` not, without opening anything.

### Where it does NOT live up to its own pitch (corrections to earlier sections)
1. **The headline token-economics claim is FALSE at this scale.** The thesis (§Thesis, §2, §4, P2) leans on
   "images are expensive, measurement is cheaper" (the research's a11y-tree ≈4k vs screenshot ≈50k tokens).
   Measured reality here: `home.measure.json` is **18 KB (~4,500 tokens)** while the viewport PNG is only
   **~1,300 tokens**. The 50k figure was for *full-page hi-res* shots; our PNGs are capped at 1280×720, so the
   **picture is cheaper than the JSON**. The real cheap artifact is the **~50-token summary line**, not the
   JSON blob — which should be drilled into, never loaded wholesale. *Earlier sections oversold this.*
2. **The `console_errors` flag is noise and non-deterministic.** `home` → 23 errors (all dev "502" from the
   no-backend auto-serve); `library` → 0, same setup, seconds apart. A flag that fires differently run-to-run
   on environment noise trains the agent to ignore it — worse than not having it. (§11 "denoise console" was
   right but understated: in the default no-backend mode the flag is *useless by construction*.)
3. **Captures are NOT clean/deterministic.** The `home` PNG had a Tasks popover AND a first-run walkthrough
   modal both open, cluttering the surface — the harness doesn't dismiss transient overlays. The contrast
   violation it "found" is literally *from* the un-dismissed Tasks popover. Non-deterministic captures
   undermine trust and any future DIFF (§11) — determinism is a prerequisite the build skipped.
4. **Facts AUGMENT the screenshot; they do not REPLACE it.** The PNG instantly showed "a Tasks overlay is
   covering the results and a modal is up" — a state the facts never surfaced as a problem. So §6.2's "demote
   the screenshot to a non-asserting gestalt attachment" / P2 is **too strong**. Honest model: facts add an
   a11y/structure layer the eye misses; the eye catches state/clutter/aesthetics the facts miss. *Augment, not
   replace.*
5. **It doesn't close the loop.** It produces facts; the agent still interprets and decides. No baseline ⇒
   "faster triage," not "automated catch" — a smaller velocity win than "measurement-first" implies. (The §11
   ASSERT idea is what closes it; this reframes ASSERT from "nice extension" to "the missing half.")
6. **Minor waste** — 36s cold start; the `a11y_native` field is always `None` on this shadow-DOM app (pure
   JSON bloat — drop it).

### Honest velocity verdict
A **modest, narrow** gain: a useful **adjunct** that surfaces a11y/contrast/structure red flags the screenshot
can't, with a fast warm loop and no setup. NOT the "stop looking, just measure" leap the thesis sold. The
irony worth recording: **ten minutes of dogfooding found more real problems than the whole build +
live-validation pass did** — which is itself evidence *for* the measurement approach, just delivered by honest
use rather than by the framing.

### Concrete fixes (sharpened, prioritized — supersede the §11 polish list)
1. **Denoise the console flag** — filter dev/HMR/proxy-502 noise so the flag means "real error" (cheapest;
   removes a false-positive generator).
2. **Dismiss transient overlays before capture** (walkthrough modal, Tasks popover) — makes screenshots AND
   facts deterministic; a prerequisite for DIFF/baselines.
3. **Summary-first, JSON-on-demand** — the printed summary is the product; trim the artifact (drop
   `a11y_native`, cap the landmark list) so the full JSON isn't a token trap when an agent does read it.
4. **A baseline/quality-gate (§11 ASSERT)** — fail on a *new* axe violation, not just report a count; this is
   what turns the tool from "faster triage" into "automated catch," the actual velocity multiplier.
5. Reframe the doc's thesis from "measurement *over* vision" to "measurement *alongside* vision" (token
   economics + the augment-not-replace finding).

## 13. Long-term design — make the measurement *trustworthy + actionable* by conforming to seams that already exist

> Design pass (2026-06-20), general not implementation-level. §12's dogfooding reframed the remaining problem:
> it is **not** "build the §11 capability catalog" — it is that the agent's measurement loop is **noisy,
> non-deterministic, and non-asserting**, so an agent learns to ignore it. The correct design is the one that
> makes the facts trustworthy + actionable. The investigation found that **two of the three pieces already
> exist** elsewhere — so most of this is *conform, don't duplicate*.

### 13.0 What the investigation found (the surprise)
- **A live axe-assertion gate with a per-view "known violations" baseline ALREADY EXISTS** —
  `modules/ui-web/e2e/accessibility-audit.spec.ts` renders each view, runs axe, and asserts *"a violation not
  in `KNOWN_RULE_BASELINE[view]` fails"* (e.g. `search: {aria-required-children, color-contrast}`). That is
  exactly the "fail on a NEW violation, not a count" model §11 ASSERT wanted — already built.
- **A determinism discipline ALREADY EXISTS** — `DeterminismRecorder` + the determinism budget (allowlisted
  `ui_screenshot_cooldown` / `wait_for_ui_ready` backoff reasons; `sleep.fixed`/`wait.unbounded` forbidden).
- **Both are DORMANT** (the Node e2e suite is not wired into CI per ADR-0026) and live in a **different (Node)
  stack** from the agent's Python `ui-shot`.
- **My §6.2 `ui_measure.py` DUPLICATED the axe capture without the baseline or the determinism practice** —
  which is precisely why the dogfood reported "2 violations" undifferentiated (the search `color-contrast` is
  *baselined/known*; the critical `aria-valid-attr-value` is *new* — the tool couldn't tell them apart).
- **No harness dismisses transient overlays** (the first-run walkthrough, the Tasks/indexing popover) — the
  one genuinely-missing primitive, absent in BOTH stacks.

### 13.1 The design (three moves; scoped to §12's problem)
1. **Clean-state capture — the genuinely-new primitive, the prerequisite.** Introduce ONE "settle to a clean,
   deterministic state" step (seed-away / dismiss the known transient overlays, then wait on an explicit UI-
   readiness signal — conforming to the determinism practice's allowlisted waits, not fixed sleeps). The
   dogfood proved this is load-bearing: `home` was captured with the walkthrough + Tasks popover open, and the
   "contrast violation" it found came *from* the un-dismissed popover. Without a reproducible state, every
   downstream idea (baseline, DIFF) is meaningless. Small, required, and missing everywhere.
2. **Baseline-relative assertion via ONE shared authority — conform, don't duplicate.** Extract the per-view
   known-violation baseline (today hardcoded in the TS spec) into ONE language-neutral register
   (`governance/*.v1.json`, the codebase's dominant shape), consumed by BOTH the TS e2e gate AND the Python
   `ui-shot` measurement. The agent's fact summary then flags **NEW vs baseline** ("aria-valid-attr-value: NEW
   — investigate"; "color-contrast: known") instead of an undifferentiated count. This is what makes the facts
   *actionable*, and it reuses the existing baseline model + register seam rather than inventing a second copy
   (two copies would immediately drift — see §13.3).
3. **Signal-clean, summary-first artifact (fold in the §12 minor fixes).** Clean-state capture (move 1) already
   removes the console-502 storm (a controlled state has no failing backend calls); categorize any residual
   console errors so the flag means "real." Keep the **summary** as the product (cheap + actionable), the full
   JSON as drill-down, drop the dead `a11y_native` field, and keep the **screenshot first-class** (augment, not
   replace — §12's correction to P2).

### 13.2 Scope discipline (what the present problem does NOT require)
NOT the rest of the §11 catalog — DIFF/perceptual-changelog, REASON/design-critic, GENERATE/fuzzer, TRACE are
separate *future capabilities*, not the trust fix. NOT merging the two harnesses. NOT wiring the dormant e2e
gate into CI (a separate ADR-0026 manual-CI question). The present problem is the **agent loop's trust +
actionability**; the three moves are exactly that and no more.

### 13.3 Reach & principle
This design is an instance of the codebase's dominant seam — register → gate → one-authority / the
representation-drift rule (553/576: *"a dependency you cannot see drifting will drift silently — make it one
declared, positively-covered reference"*). But it reveals a **more specific recurring shape worth naming**:

> **P3 — One rendered-UI invariant checked at multiple tiers must project from ONE authority, or the tiers
> silently disagree about what "passing" means.** A UI invariant (accessibility, contrast, layout) typically
> has a *static source-proxy* (fast, checks the model), a *live CI gate* (checks the render), and now a *live
> agent-loop* (checks the render in the dev loop). Three checkers of the same truth. If each carries its own
> notion of "acceptable," they diverge. The static gate is a proxy; the live render is ground truth.

**Candidate scope + an EXISTING violation already in tree:**
- **a11y** — static `check-a11y-closure` + live `e2e/accessibility-audit` (+ baseline) + agent `ui-shot`. Only
  the e2e tier has a baseline; the agent tier has none → the §12 noise. (Move 2 fixes exactly this tier.)
- **contrast — a concrete, recorded violation of P3 *today*:** static `check-contrast-matrix` passes (the
  tokens clear WCAG AA), yet `e2e/accessibility-audit` *baselines `color-contrast` as a KNOWN live violation on
  five surfaces* (search/settings/library/brain/health). So the static proxy says "fine" while the render
  fails, and the live baseline silently accepts the gap. The dogfood's `home` color-contrast finding is an
  instance. **No single authority reconciles the token-derived invariant with the rendered result.**
- **layout/overflow** — static `check-layout-purity` exists; no live tier yet.

**Do NOT build the generalized version now.** The present problem only requires the *a11y baseline* to become
one authority shared by the agent loop + e2e (Move 2). Building a unified authority that reconciles all three
invariants across all three tiers (incl. resolving the static-vs-live contrast divergence) is a larger,
separate effort — recorded here as the principle + its scope, deliberately *not* built. (Separating
"recognizing P3" from "building P3 everywhere" is the point.)

## 14. Pre-implementation findings (confidence pass, 2026-06-20) — the surprise: *determinism is the unsolved crux, not the moves*

A read-only investigation + the §12 dogfood evidence, run **before** any §13 feature work, to test the
design's load-bearing assumptions. Verdicts are source-cited. **Net: confidence in §13 as written drops — the
three moves all presuppose a *deterministic measured state* that does not exist for shell-v0 and is non-trivial
to create. The investigation also surfaced the likely-correct determinism strategy (route-mocking) the design
missed.**

| # | Uncertainty | Verdict | Evidence |
|---|---|---|---|
| F1 | Move 3 console-noise fix mis-designed | **Confirmed (flaw)** | The 502s are env-class — the auto-serve Vite proxies `/api` to *whatever stack is running* (or 502s if none). Dismissing overlays adds no backend. Right fix = categorize env `/api` 4xx/5xx vs real JS errors. |
| U1 | Do `ui-shot` + e2e measure the SAME state? | **Refuted — worse than feared** | (a) demo mode is **inert** for shell-v0 — `resolveApiEndpoint` (`src/api/http.ts`) has no `?demo`→`'demo'` path; the e2e baseline was calibrated against the OLD working-demo state (`KNOWN_COLOR_CONTRAST_HTML_SNIPPETS=['Searching','Demo mode']`), so it is **stale** vs current shell-v0. (b) the auto-serve backend is **non-deterministic** (proxy-to-whatever). So there is no shared, controlled state to baseline against. |
| U2 | Overlay-dismissal mechanism | **Partial** | Walkthrough: **seedable** — persisted in `UserStateDocument.walkthroughState.dismissed` → `localStorage['justsearch.userState.v2']`; an `init_scripts` seed suppresses it. Tasks popover: **NOT seedable** — driven by the backend SSE `GET /api/indexing-jobs/stream` (`indexingJobsBridge.ts`); a clean state needs an *idle backend* (no jobs), i.e. backend control, not an init-script. |
| U3 | Baseline granularity | **Partial** | The e2e baseline is per-*view* and covers only ~6 of `ui-shot`'s steps (no `help`, none of the finer interaction steps `search-results`/`inspector-open`/…). Sharing it covers a fraction. |
| U4 | Is the dogfood `aria-valid-attr-value` real? | **Unresolved (env-confounded)** | The selector (`…jf-search-surface,.q`) is the search input, not the popover — so *likely* real — but the capture state was uncontrolled (proxy backend + overlays), so it can't be attributed cleanly until determinism exists. |
| U5 | Shared-register feasibility | **Confirmed OK** | `modules/ui-web/tsconfig.json` has `resolveJsonModule: true`; a `governance/*.v1.json` is importable. (Path resolution from `e2e/` is the only minor detail.) |

### The reframe (what the investigation actually taught)
§13 framed three independent moves. The truth: **all three depend on a deterministic measured state, and that
state does not exist.** Establishing it is the real work, and §13 underestimated it:
- The walkthrough is dismissible (localStorage seed), but the **Tasks popover and the data itself are
  backend-driven**, and the harness's auto-serve proxies to a non-deterministic backend. So "clean-state"
  needs a *controlled backend state*, not an init-script.
- The two candidate determinism foundations I'd assumed are both heavier than thought: **(A) a controlled dev
  stack** (idle, no jobs, AI-active) is heavyweight + contention-prone; **(B) restoring demo mode** is NOT a
  small wiring fix — only **1 of 11 `src/api/domains/*`** still has a `'demo'` branch (`inference.ts`), so the
  shell-v0 demo data layer would need re-implementing across ~10 domains. *(Correction: my §9 / the how-to
  notes implied demo restoration was just "re-wire `resolveApiEndpoint`" — it is not; the data layer is the
  gap.)*

### The strategy the design missed (likely-correct, recorded for the next pass)
**Playwright route-mocking** — intercept `/api/*` at the harness layer and serve fixtures — gives a
deterministic, no-backend, no-demo-mode-in-app state that BOTH `ui-shot` and the e2e suite could share. That
is the same "one shared authority" shape as P3 (one fixture set, two harnesses), cleaner than app-level demo
restoration, and the standard way modern Playwright a11y/visual suites achieve determinism. It also makes the
e2e baseline **re-generatable** against a known state (fixing U1's staleness). This reframes §13's prerequisite
from "dismiss overlays" to "establish a route-mocked deterministic fixture state, shared by both harnesses."

### Corrections to §13 (folded back)
- **Move 1** is much larger than "dismiss overlays" — it is "establish a deterministic state," whose right
  form is route-mocked fixtures (not an init-script, not app demo-mode).
- **Move 2's baseline is stale** (calibrated to dead demo state) + partial coverage — it needs *regeneration*
  against the new deterministic state, then extraction, then sharing.
- **Move 3's console fix is env-categorization**, independent of clean-state (confirmed flaw).

### Critical confidence rating: **4 / 10**
The *direction* (conform to the existing baseline + determinism seam, don't duplicate) survived; but §13's
moves rest on a deterministic state that doesn't exist, and creating it is the hard, under-scoped part. The two
factors that move the rating:
- **Down:** determinism is unsolved and non-trivial (backend-driven overlays + non-deterministic proxy + a
  dead demo data layer); the e2e baseline I planned to reuse is stale; Move 1 is really a determinism-substrate
  project, not an init-script.
- **Up:** the investigation produced the likely-correct path (shared route-mocked fixtures), the walkthrough is
  cleanly seedable, U5 is fine, and the assertion model (baseline-relative) is sound once a deterministic state
  exists. The single highest-leverage next de-risk before implementing: **prototype a route-mocked deterministic
  `ui-shot home` capture** (mock `/api/*` with fixtures, seed the walkthrough-dismissed flag) and confirm it is
  reproducible run-to-run with zero env console noise — that one experiment would move the rating to ~7.

## 15. Shipped (2026-06-20) — the determinism-independent fixes (bucket ②)

The §12/§14 critique split the remaining work by whether it depends on the unsolved determinism substrate.
The fixes that **do not** depend on it shipped here, leaving the determinism-gated design (§13 + the §11
catalog) for a later pass. All three are live-validated against a real `ui-shot home` capture.

- **Console-error categorization (§12 #1 / §14 F1 — the confirmed flaw).** `ui_measure.py` now classifies
  every console entry into `env-network` (no-backend 502s, `/api/*` fetch failures — the auto-serve proxy
  noise), `dev-noise` (Vite/HMR), or `app` (genuine JS errors / `pageerror`). The summary flag fires only on
  `app`-category errors, and the one-line summary reads `console N real (+M env)`. This is exactly F1's fix
  ("categorize env `/api` 4xx/5xx vs real JS errors"), and it is **independent of clean-state** as §14 found —
  it does not wait on the determinism substrate. Live: `home` went from a misleading `console-errors` flag on
  44 entries to `0 real (+44 env)`, flag dropped; only the genuine `axe-serious:1` remains.
- **Dropped the dead `a11y_tree`/`a11y_native` field (§12 #6).** Playwright's native
  `accessibility.snapshot()` returns `None` on this shadow-DOM Lit app, so the field was pure JSON bloat; the
  shadow-piercing `a11y_landmarks` is the authoritative channel and stays.
- **Real-vs-env split in both the artifact and the summary.** Each `console_errors[]` entry carries a
  `category`; the summary adds `console_real` / `console_env`; `ui_shot.py` prints the split.
- **Regression test** (`tests/test_ui_measure.py`, 7 cases, green) pins the classifier buckets — the failure
  mode is a *silent wrong value* (mis-bucket → the flag lies), so it gets a test per the audit-without-test
  discipline.

NOT shipped (correctly deferred — all determinism-gated per §14): clean-state capture, the shared
baseline register (§13 Move 1/2), and the entire §11 capability catalog. The next de-risk is §14's
route-mocked deterministic capture prototype — **run in §16 below, and it passed.**

## 16. §14 de-risk experiment RESULT (2026-06-20) — route-mocking works; rating 4 → 7

§14's single highest-leverage next step was: *prototype a route-mocked deterministic `ui-shot home` capture
and confirm it is reproducible run-to-run with zero env console noise — that one experiment would move the
rating to ~7.* It is built (`scripts/jseval/experiments/route_mock_home.py`, a throwaway experiment kept OUT
of the harness production path) and **run live twice. It passed.**

**Result (two consecutive runs, no backend, app demo-mode NOT restored):**
- **env console noise: 0** — the no-backend 502 storm (§12/§14 F1) is fully eliminated by intercepting
  `/api/*` and serving fixtures.
- **a11y landmarks: 60, byte-identical run-to-run; geometry byte-identical; axe-serious: 0.** That is the
  operational definition of "deterministic measured state" the three §13 moves all presuppose — **it exists**,
  reachable via route-mocking, *without* the heavyweight controlled-dev-stack or the ~10-domain app demo-mode
  rebuild §14 feared.

**Three findings the experiment produced (each a real sharpening, not just a green check):**
1. **The FE parse boundary is non-fail-open, so fixtures must be schema-valid — an empty `{}` is WORSE than a
   502.** First run stubbed every `/api/*` with `{}`; the shell never mounted the rail (`/api/status` +
   `/api/knowledge/search` failed the generated-schema parse → boot broke). Serving the repo's captured
   `__fixtures__/*-live.json` fixed it. This confirms §14's "fixtures, captured from a stack" — the substrate
   is route-mock + **valid** fixtures, not stubs.
2. **A route glob `**/api/**` over-matches the FE's own `/src/api/*.ts` Vite modules** (the source tree is
   `src/api/`), serving them as JSON → "Failed to load module script". The correct matcher is a path predicate
   `urlparse(url).path.startswith('/api/')`. (A trap any real implementation must avoid — now documented.)
3. **A classifier bug in §15's just-shipped bucket ②**, caught by dogfooding this experiment: `[WireContract]`
   schema-mismatch errors mention `/api/` and were being hidden as `env-network`, but they are REAL app-tier
   contract signals (backend drift). Fixed (`_APP_MARKERS` wins over env) + regression test added (8 cases
   green). With the fix the experiment honestly reports the 4 residual as `4 real (+0 env)` — the flag now
   means something: it caught the genuine contract gaps from my *empty registry* stubs (`/api/registry/*`,
   which lack a captured fixture — closing them is the "capture the remaining fixtures" step).

**Updated confidence rating: 7 / 10** (was 4). The determinism crux §14 called "the unsolved, under-scoped
part" is now *solved in prototype*: a deterministic, zero-env-noise, byte-stable capture is achievable with
route-mock + schema-valid fixtures, no backend, no app-demo rebuild. What's left to reach the full §13 design
is now *clearly scoped mechanical work*, not an unknown: (a) capture the remaining `/api/registry/*` (+ any
other boot-path) fixtures from a stack into one shared fixture set; (b) wire route-mock + the walkthrough seed
into the harness's real capture path (§13 Move 1); (c) extract the e2e `KNOWN_RULE_BASELINE` into the shared
register and regenerate it against this deterministic state (§13 Move 2). The remaining −3 is execution
surface area + the fixture-freshness question (captured fixtures drift from the live contract — they need
their own freshness discipline, an instance of P1), not architectural risk.

## 17. §13 Move 1 BUILT (2026-06-20) — the deterministic mode is now a real, opt-in harness primitive

§16's prototype is promoted out of `experiments/` into the harness as an **additive, opt-in** mode, so the
live AI-chain steps (which need a real model) are untouched while the structural steps get a deterministic,
zero-noise capture.

**Built + live-validated:**
- **`scripts/jseval/jseval/ui_fixtures.py`** — the reusable route-mock primitive: serves schema-valid bodies
  for every `/api/*` (captured `__fixtures__/*-live.json` for the boot-critical status/search/settings;
  minimal-valid EMPTY catalogs for `/api/registry/{operations,resources,diagnostic-channels}` built from the
  Zod required-key shape in `types/registry.ts` + `types/diagnostic.ts`), seeds the dismissed `welcome`
  walkthrough, and matches via a path predicate (`/api` root only, NOT the FE's own `/src/api/*.ts` modules).
  Both experiment-found traps are encoded in code + comments so they can't recur.
- **Opt-in wiring**: a `fixtures: bool` threaded `cli --fixtures → execute_ui_shot → _run_single_shot →
  _run_isolated_step` (+ the shared-chain context). Default OFF — zero behavior change for existing runs.
- **`tests/test_ui_fixtures.py`** (6 cases): pins the path predicate (incl. the `/src/api` exclusion), the
  registry catalogs' required keys, and the walkthrough seed. 14 jseval ui-measure/fixtures tests green.
- **LIVE (real harness path, two runs)**: `jseval ui-shot home --fixtures` → **console 0 real (+0 env)**
  (both the 502 storm AND the registry WireContract errors gone), **a11y 59 landmarks byte-identical
  run-to-run**, geometry stable. The capture is now fully deterministic through the shipped command, not just
  a throwaway script.

**The deterministic mode immediately paid off — it made a real finding trustworthy.** In the clean state, axe
reports ONE reproducible critical `aria-valid-attr-value` on the search input (`jf-search-surface .q`) — the
§14 U4 finding, no longer env-confounded. It is now a trustworthy flag (the whole point of §13). Logged to
`docs/observations.md` (out of tooling scope — a presentation-authority fix).

**Fixture-freshness (the §16 −3 / P1 instance) is largely already solved, not new work.** `modules/ui-web/
src/api/contract.test.ts` already validates the three captured `*-live.json` fixtures against the FE Zod
schemas (and they are regenerated by the Java contract test), so the boot-critical fixtures the route-mock
serves cannot silently drift from the contract. The registry catalogs are empty + minimal-valid-by-
construction and pinned by `test_ui_fixtures.py`. So the only residual freshness surface is "did the registry
schema's REQUIRED keys change" — which would break the FE first. No redundant freshness gate is warranted.

**Remaining for the full §13 (now genuinely just Move 2):** extract the e2e `KNOWN_RULE_BASELINE` into ONE
shared `governance/*.v1.json`, regenerate it against this deterministic state, and have both the TS e2e gate
and the Python `ui-shot` flag NEW-vs-known. With Move 1 shipped + freshness covered, this is the last
structural piece before the §11 capability layer (ASSERT/DIFF/HARDEN/…) becomes cheap.

## 18. §13 Move 2 BUILT (2026-06-20) — the a11y baseline is now ONE authority shared by both tiers

§13's last structural piece. The per-view known-violation baseline, previously a hardcoded literal in the
dormant e2e spec, is now ONE shared register both tiers project from — so the agent loop and the CI gate
cannot silently disagree about what "passing" means (§13.3 / P3).

**Built + validated:**
- **`governance/ui-a11y-baseline.v1.json`** — the one authority: per-surface `{uiShotStep, e2eViews,
  knownRules}`, REGENERATED against the deterministic `--fixtures` state (not the dead demo state the e2e was
  calibrated to). Regenerator: `scripts/jseval/experiments/regen_a11y_baseline.py` (recapture + rewrite).
- **Python `ui-shot` consumes it** (`ui_measure.py`): the summary now splits axe violations **NEW vs known**
  per surface — `axe 0 NEW (1 known)` instead of an undifferentiated `1 serious`. A NEW (non-baselined)
  violation is the loud `axe-NEW:<ids>` flag; accepted debt stays quiet; a step with no baseline entry falls
  back to the raw count. This is the §12 dogfood's core ask — the tool can finally tell the known
  `color-contrast` from a genuinely new bug.
- **TS e2e gate consumes the SAME register** (`accessibility-audit.spec.ts`): the hardcoded
  `KNOWN_RULE_BASELINE` literal is gone, replaced by a runtime projection of the register's `e2eViews →
  knownRules`. No second copy to drift.
- **Tests**: Python baseline-loader tests (21 ui-measure/fixtures green); FE `npm run typecheck` clean.
- **LIVE**: `ui-shot home --fixtures` → `axe 0 NEW (1 known)`, no loud flag (the search
  `aria-valid-attr-value` is recorded debt); `inspector-open --fixtures` (no baseline) → raw-count fallback.

**P3 made concrete (and partially resolved).** Regenerating against the deterministic state exposed exactly
the divergence §13.3 predicted: the tiers had disagreed because they captured DIFFERENT states. The fresh
ground truth differs from the stale e2e literal — search shows `aria-valid-attr-value` (not
`aria-required-children`+`color-contrast`); settings shows `select-name` (not `color-contrast`);
library/brain are now clean. Two real a11y bugs surfaced and were logged to `observations.md` (search
`aria-valid-attr-value`, settings `select-name`) — out of tooling scope.

**The honest residual (the unresolved half of P3, deliberately deferred per §13.3).** Both tiers now READ one
authority, but the dormant e2e suite still CAPTURES the stale demo state, so until it adopts the same
route-mock fixtures (port `ui_fixtures.py` into `ai-harness.ts`) the two tiers read one register while
observing different states. It is dormant (not in CI per ADR-0026), so the mismatch is latent, not failing —
and it is documented in the e2e spec + here. Closing it (one captured state for both) is the "build P3
everywhere" work §13.3 scoped OUT of the present problem.

**§13 is now structurally complete** (Move 1 + Move 2 + freshness). The agent's measurement loop is
trustworthy (deterministic, zero env noise) AND actionable (NEW-vs-known). The §11 capability layer
(ASSERT/DIFF/HARDEN/REASON/GENERATE/TRACE) now has the deterministic, baselined substrate it was waiting on.

## 19. §11 capability layer — build log (2026-06-20)

Implementing the deferred §11 verbs now that the substrate exists. Each is a minimal-viable, live-validated
unit; the §11 table is the spec, this is the as-built.

### ASSERT — `ui-a11y-gate` (DONE, live-validated) — the §12 "missing half"
Turns NEW-vs-known from a *report* into a *gate that fails*. `jseval ui-a11y-gate` captures every surface in
the shared baseline register in the deterministic `--fixtures` state and exits **1 on any NEW** (non-baselined)
axe violation, **0** when only accepted debt remains, **2** on a capture error.
- The NEW-vs-known split is extracted into ONE shared pure helper `ui_measure.split_new_vs_known` consumed by
  both the capture summary AND the gate (no second copy — the Move-2 discipline). The gate computes NEW itself
  from the register's `knownRules` against the capture's raw axe violations, so the register is its single
  input (not coupled to whichever baseline `ui_measure` loaded at import).
- Files: `jseval/ui_a11y_gate.py` (new), `jseval/cli.py` (`ui-a11y-gate` command, cloned from the
  `relevance-gate` shape), `jseval/ui_measure.py` (extracted helper), `tests/test_ui_a11y_gate.py` (8 cases).
- LIVE: against the real baseline → **exit 0**, all 6 surfaces `ok`. With search's `knownRules` overridden to
  empty → the live `aria-valid-attr-value` surfaces as **NEW → exit 1** naming the rule. The gate bites.
- Local-first (ADR-0026): a runnable gate, not CI-wired — matches the repo's local-first discipline. This is
  the rendered-UI counterpart to the static 559 presentation gates.

## 20. §18 P3 residual CLOSED (2026-06-20) — the e2e tier now captures the deterministic state

§18 left an honest residual: both tiers READ the shared baseline, but the e2e suite still CAPTURED the stale
demo state. Closed by porting the route-mock fixtures into the e2e harness so both tiers capture ONE state.
- **`navigateToFixtureMode` + `gotoFixtureSurface`** added to `e2e/ai-harness.ts` — the TS twin of
  `ui_fixtures.py`: the SAME `__fixtures__/*-live.json` + minimal-valid empty registry catalogs + walkthrough
  seed + `/api`-root-only matcher. One fixture set, two harnesses (the P3 shape).
- `accessibility-audit.spec.ts`: the 5 view-audit `beforeEach` hooks switched from `navigateToDemoMode`+
  `waitForDemoData` to `navigateToFixtureMode` (+ rail/hash-route nav for the off-rail Health surface); the
  stale demo-coupled `KNOWN_COLOR_CONTRAST_HTML_SNIPPETS` contrast test removed (color-contrast is covered by
  the register-baselined audits).
- **Two real bugs found + fixed while closing this** (both in the dormant suite, exposed once it actually
  loaded the live shell): (a) an `@axe-core/playwright` AxeBuilder injection crash ("Cannot assign to read
  only property 'get' of WeakMap" — the Lit shell freezes an intrinsic) → replaced with a direct
  `addScriptTag`+`axe.run` injection (`runAxeViolations`), the SAME method the Python tier uses, which also
  aligns both tiers on one axe invocation; (b) the off-rail Health surface has no rail button → added the
  `justsearch://surface/<id>` hash-route fallback mirroring `ui_check._goto_surface`.
- **LIVE**: `npx playwright test accessibility-audit -g "has no WCAG violations"` → **5 passed (30s)** —
  search/settings/library/brain/health all capture the fixture state and assert ⊆ the shared register's
  knownRules. Both tiers now read AND capture one state. `npm run typecheck` clean.
- Out of scope (logged to observations): the suite's NON-audit tests (form labels, toggles, keyboard-nav,
  screen-reader blocks) are separately React-era stale — a follow-up cleanup, not 615's a11y-baseline concern.

### DIFF — `ui-diff` perceptual changelog (DONE, live-validated)
A SEMANTIC diff of two `ui-measure.v1` captures (not a pixel diff): what meaningfully changed — a landmark
removed, an element moved/resized past 4px, a NEW axe rule (or one fixed), overflow flipped, real console
errors. Reuses the schema-versioned artifact `capture_measure` already writes; no new capture machinery.
- Files: `jseval/ui_diff.py` (new — `diff_measures`/`diff_files`/`format_diff`), `jseval/cli.py` (`ui-diff
  <before> <after>` command; exit 0 = no change, 1 = changed, so it composes in scripts),
  `tests/test_ui_diff.py` (8 cases). Output is ASCII-sanitized (UI-sourced labels can carry non-ASCII).
- LIVE: two `home --fixtures` captures → **"no semantic change" (exit 0)** — determinism = empty diff, the
  ground-truth check; `home` vs `library` → real changelog (`axe fixed: aria-valid-attr-value`, landmarks
  -6/+10, console d+1) **exit 1**. The perceptual changelog the §11 DIFF row called for.

### HARDEN — self-healing selectors (DONE, live-validated) — §11 standout idea #1
Resolve elements by accessible ROLE + NAME first, falling back to the `data-testid` — so the testid churn
that made this session's React→Lit migration painful can't break a step that has a role+name (and vice
versa). The a11y tree the measurement substrate already records IS the resolution key ("the harness hardens
itself").
- `ui_selectors.Selector` (frozen dataclass) `{role, name, testid}` with an async `locate(page)` that returns
  the role+name match when present (Playwright's role engine pierces OPEN shadow roots — verified live) else
  the testid locator, never None. `SEARCH_INPUT = Selector('searchbox','Search files','search-input')`;
  `ui_check._type_and_search` now resolves the input through it.
- Files: `jseval/ui_selectors.py`, `jseval/ui_check.py`, `tests/test_ui_selectors.py` (4 cases pinning the
  role-wins / testid-fallback / error-fallback order).
- LIVE: on the active search surface, `get_by_role('searchbox', name='Search files')` → **count 1**; with a
  **deliberately broken testid**, the Selector still resolved via role+name and typed the query (self-healing
  proven); `ui-shot search-results --fixtures` captures green through the new path.

### REASON — design-critic (DONE, loop validated) — the deferred Judge §6.4
The one faculty that doesn't reduce to a gate (visual hierarchy / structure / brand feel): an LLM critiques
the captured facts against a machine-readable design reference + rubric. Grounded, not free-floating taste.
- `governance/design-reference.v1.json`: the always-on foundation (token scales projected from
  `token-names.generated.ts` — font-size/z/duration/ease + the 4px grid), the shell + per-surface structural
  expectations, and a 6-dimension rubric. JSON encoding (Indeed's §11 finding: JSON won as the design-system
  format).
- `jseval/ui_critic.py`: `compact_facts` (the dense fact subset, per the §12 token lesson), `assemble_prompt`
  (reference + facts + a strict JSON output contract), `parse_critique` (tolerant JSON extraction; a non-JSON
  reply is wrapped, never crashes), `critique(step, surface, capture_fn, model_fn)` — the model call INJECTED
  so the logic is testable + the CLI can wire either the live model or `--facts-only`.
- `jseval ui-critic <step>`: captures `--fixtures`, prints the **grounded critique prompt** (the agent/dev
  model produces the critique — "unit tests for correctness + LLM rubrics for quality"). 7-case test.
- LIVE: `ui-critic home` emitted a real **7.5 KB grounded prompt** from a real fixtures capture; the loop was
  closed end-to-end (grounded prompt → a real model → `parse_critique` → structured issues) — the critique
  correctly flagged the real critical `aria-valid-attr-value` and confirmed landmark completeness, each citing
  an actual measured fact. **Honest limit:** the local-9B-via-`agent_chat` leg is **environment-blocked** — the
  llama runtime variant (`cuda12`/`cpu`) is not installed in this checkout (`installedVariants:[]`; the binary
  is absent under `modules/ui/native-bin/llama-server/variants/`), a dev-env packaging gap, not a 615 issue.
  The loop is proven with a real model (the agent — the only "user"); wiring the 9B path is mechanical once the
  runtime variant is installed.

### GENERATE — state-matrix fuzzer (DONE, live-validated) — the exhaustiveness superpower §2
Render a surface across a cross-product of states a human won't patiently check —
{data-variant × viewport × theme} — measure each, flag the cells with anomalies. UX bugs live in edge states;
the agent renders them all in one command.
- `ui_fixtures` extended with a `variant` axis (`default`, `empty` — the data-extreme becomes a fixture
  transform, not a backend state; the route-mock substrate makes this free). `jseval/ui_fuzz.py`: iterates the
  matrix on ONE shared browser (new context per cell), captures each via `capture_measure`, and flags
  per-cell anomalies — NEW axe vs the search baseline (reusing `split_new_vs_known`), overflow, real console
  errors, or a render-failure (a cell that can't reach the surface is itself reported, not a crash).
- Files: `jseval/ui_fixtures.py` (variants), `jseval/ui_fuzz.py` (new), `jseval/cli.py` (`ui-fuzz`,
  exit 1 on any flagged cell), `tests/test_ui_fuzz.py` (7 cases: variant transform + anomaly detection).
- LIVE: `ui-fuzz` rendered the full **8-cell matrix** ({default,empty} × {desktop,narrow} × {dark,light}),
  59 landmarks each, **0 flagged** — the search surface holds up across data/viewport/theme (the narrow 560px
  cell renders cleanly, no overflow). The exhaustiveness capability the §11 GENERATE row called for.
- Hardening note: an early run was flaky (per-cell browser churn + an 8s rail-wait under cold load) — fixed
  by sharing one browser and restoring the 15s readiness wait; deterministic since.

### TRACE — interaction-trajectory measurement (DONE, live-validated) — the half-built Act §2
Measure *over* a flow, not at rest: snapshot a PRE measure before a step's interaction and diff it against the
POST capture, so the {pre, post} delta records what the trajectory CHANGED (landmarks appearing, layout shift,
console/overflow delta) — reusing the DIFF engine, no new diff logic.
- `_write_trace` (reuses `ui_diff.diff_measures`) writes `<step>.trace.json` (`ui-trace.v1`: pre_url, post_url,
  delta). Wired into BOTH the isolated path (`--trace` flag → `_run_isolated_step`, for navigation
  trajectories) AND the shared-chain path (`_run_shared_steps` `trace_target`, for interaction steps like
  inspector-open).
- Files: `jseval/ui_check.py` (`_write_trace` + both capture paths), `jseval/ui_shot.py` (threaded `trace`),
  `jseval/cli.py` (`--trace`), `tests/test_ui_trace.py` (2 cases).
- LIVE: `ui-shot library --fixtures --trace` → trajectory `#core.unified-chat-surface → #core.library-surface`,
  delta landmarks +8/−5; `ui-shot inspector-open --fixtures --trace` → **`geometry appeared:
  ['jf-inspector-pane']`** — the trace captured the inspector pane opening across the row-click, not just the
  end state. The observe-think-act trajectory measurement the §11 TRACE row called for.

## 25. §11 capability layer — COMPLETE (2026-06-20)

All six §11 verbs are built + live-validated, plus the §18 P3 residual closed (§20). The agent's UI/UX loop
now spans the full faculty set on the deterministic substrate: **ASSERT** (gate on a NEW a11y violation),
**DIFF** (semantic perceptual changelog), **HARDEN** (self-healing role+name selectors), **REASON** (grounded
design-critic), **GENERATE** (state-matrix fuzzer), **TRACE** (interaction-trajectory delta). Each is a
runnable `jseval` command over the route-mocked deterministic capture, reusing one measurement substrate +
one baseline authority + one diff engine — no parallel structure. 615 is functionally complete end-to-end:
the measurement loop is trustworthy (§13), actionable (ASSERT), diff-able (DIFF/TRACE), self-hardening
(HARDEN), exhaustive (GENERATE), and quality-judged (REASON). Only honest, documented limits remain (the
local-9B critic leg pending a runtime-variant install; the e2e suite's non-audit React-era tests; richer
GENERATE variants) — all logged, none architectural.

## 26. Dogfooding critique of the §11 layer (2026-06-20) — practicality for agents, honestly

> §25 says "complete + validated." True, but validation ≠ practicality. This section *corrects* §25's
> optimism: I dogfooded the whole layer and graded it for what it's worth to a **naive agent in a fresh
> session**, not to me-the-author babysitting a server. Read this before trusting §19–§25 at face value.

### The finding that undercuts almost everything: the substrate is fragile, and I hid it
Every tool rests on the `--fixtures` capture → the Vite auto-serve, and that layer is brittle. This session,
**I papered over it by hand**: `_resolve_ui_url` raced and returned a dead `:5173` while a working server sat
on `:5174` (so `ui-fuzz` reported "8 cells render-failed" with nothing wrong); a stale Vite served a page
where `goto` succeeded but `jf-shell` never mounted (looks like a UI bug, isn't); I "fixed" the fuzz by
**manually killing PIDs + passing `--ui-url http://localhost:5174`**. A fresh agent has none of that context —
it will hit render-failures and chase a phantom bug or give up. **I validated as the author driving the
server, which is exactly the §12 blind spot.** I added six capabilities ON TOP of a shaky foundation instead
of hardening the foundation — the wrong allocation for agent velocity.

### Second problem: discoverability — a tool the agent doesn't know to use is worth ~0
Six separate subcommands (`ui-a11y-gate`/`ui-diff`/`ui-fuzz`/`ui-critic`/`--trace`/the role+name selectors).
Only `ui-shot` is *pushed* (the `ui-shot-hint` hook). Nothing surfaces the other five; a capability buried in
a tempdoc is invisible. In practice an agent uses `ui-shot` + maybe `ui-a11y-gate` and the rest rot — the
exact "tool freshness" failure mode §3 was about, re-created in my own work.

### Per-tool honest grades (for a naive agent, not the author)
- **HARDEN** — the genuine keeper. Zero new agent action, invisible robustness, forecloses the migration pain
  that motivated the tempdoc. Best practicality-to-ceremony ratio; also the least "capability" (it just keeps
  `ui-shot` from breaking).
- **ASSERT** — practical but discipline-dependent: local-first (not CI-wired → ~70% prose adherence), needs a
  working server, and asserts against a baseline regenerated from a DEGENERATE fixture state (empty registries
  → the captured UI isn't the populated app). Medium.
- **DIFF** — situational; a three-command dance (capture-before, capture-after, diff) an agent won't do
  reflexively. Better than pixel-diff when deliberately iterating on one surface.
- **REASON** — the most speculative. Local-model leg env-blocked; the design reference is thin; an LLM rubric
  over a 7.5KB prompt is soft + expensive. The unanswered question: **what does it buy over "the agent looks at
  the PNG + the measure summary and reasons directly"?** Not convinced it earns its cost.
- **GENERATE** — high theoretical value, low hit rate, high cost (~80s for an 8-cell run usually "0 flagged",
  2-variant data axis). A hunt-a-specific-edge-bug tool, not a routine one.
- **TRACE** — neat, narrow: only traces interactions that already exist as harness steps; an agent can't trace
  an arbitrary click. Limited to the harness vocabulary.

### Token/effort economics (still the §12 truth)
`measure.json` is BIGGER than the capped PNG. The product is the one-line summary; the JSON blobs
(measure/trace) are drill-down an agent rarely loads. "Assert on structured facts" is half-right — the
*summary* is the win; the artifacts are mostly weight. Running the full suite on a change costs real tokens +
minutes for marginal benefit over reading a screenshot + summary.

### Meta-judgment: breadth where the need was foundation depth
Honest ranking by real agent value:
1. **Server-lifecycle robustness (UNBUILT)** — would lift *every* tool; the highest-value thing, and I skipped
   it. A `ui-serve` that guarantees a clean mounted app or fails loudly with the reason.
2. **HARDEN + the measure summary line** — already paying off, invisible.
3. **ASSERT** — worth a pre-commit hook so discipline doesn't gate it.
4. **DIFF** — keep, situational.
5. **REASON / GENERATE / TRACE** — impressive + validated, but built because the plan said "all remaining
   work," not because an agent will reach for them. A velocity-first call would have deferred all three.

**If the goal is genuinely agent velocity**, trade REASON+GENERATE+TRACE back for: (a) a robust server
lifecycle; (b) fixtures reflecting a realistic POPULATED state, not empty registries; (c) a hook/skill that
surfaces ASSERT/DIFF so the agent knows they exist. The capabilities are real and tested — but their
practicality is gated by a foundation I left fragile and a discoverability gap I didn't close. *This critique
supersedes §25's "functionally complete" framing: the layer is complete, but only HARDEN/ASSERT (and the
deterministic summary) are practical-by-default today; the rest await foundation + discoverability work.*

## 27. Long-term design for the substrate fragility (2026-06-20) — the FE-serve readiness contract; *conform to 618's seam, don't fork it*

> §26 named the highest-value unbuilt thing: "a `ui-serve` that guarantees a clean mounted app or fails loudly
> with the reason." This section is the **design** for it — general, not implementation. The headline result is
> a *conform*: the fragility is not a new problem, and the fix is not a new authority. It is the **readiness
> half** of a seam tempdoc **618 §7 / Seam B has already diagnosed and named.** Read §27.4 for the reach.

### 27.1 The problem, stated precisely (two defects + one structural cause)

Every §11 tool rests on `--fixtures` → the Vite auto-serve in `ui_shot.py` (`_resolve_ui_url` /
`_start_vite_server`). Stripped to mechanism, its fragility is **two defects over one structural cause**:

- **Defect 1 — the readiness predicate is a cheap proxy, not the real condition.** "Server ready" is defined
  as **HTTP-200 on the root URL** (`urllib.request.urlopen(url)`; `_is_server_alive` does the same). But what
  every capture actually needs is "**the app mounted** (`jf-shell` present in the DOM) and is serving **this
  worktree's fresh code**." HTTP-200 passes for all the states that produce phantom failures: a stale Vite
  serving a broken bundle, a compile-erroring Vite serving an error overlay, a lingering dead socket, *another*
  worktree's server on the same port. The gap between "HTTP answers" and "shell mounted" is exactly where §26's
  "`goto` succeeded but `jf-shell` never mounted — looks like a UI bug, isn't" lives. (618 §7 hit the identical
  symptom independently: "`jf-shell` *defined but never mounted*, **no console error**.")
- **Defect 2 — failure is silent.** Vite's stdout/stderr are `DEVNULL`'d; every failure path degrades to
  `return ui_url` (the dead default `:5173`). The agent is handed a dead URL with **no reason**. This is the
  precise inverse of §26's ask ("fails loudly with the reason").
- **Structural cause — the FE-serve responsibility is forked.** Two implementations own "bring up a Vite
  serving this worktree's FE": the **sanctioned** `scripts/dev/serve-worktree-fe.cjs` (618 §7: free-port scan,
  dual-stack connect-probe, `--strictPort`, borrow-the-backend read-only, prints branch+source+url — the robust
  one) and the **ad-hoc** `ui_shot._start_vite_server` (hardcoded `:5174`, trusts `:5173` if it answers,
  HTTP-200 readiness, `DEVNULL` failure — the fragile one). Two forks of one job **drift** — the same
  one-authority failure class this repo polices everywhere, and the class P1 (§7) is about: *verification
  infrastructure held to the product's discipline*.

### 27.2 What already exists (investigated — the design is mostly *reconciliation*)

- **`serve-worktree-fe.cjs` already has the robust provenance half.** Free-port-from-5174 + `--strictPort`
  (fails fast, no silent drift), a Windows-correct dual-loopback connect-probe (not a false-free bind-probe),
  backend auto-borrow from the dev-runner lease, and a branch+source+url print that makes "the served code is
  *this* worktree's" legible. It is missing only: (a) the *machine-checkable* app-mounted readiness gate, and
  (b) a programmatic (non-foreground) contract the Python capture loop can consume.
- **The app-mounted predicate is already computed — in the wrong layer.** `ui_check.py` already waits on the
  rail (`S.rail_css(S.RAIL_SURFACE_SEARCH)` visible ≤15s) as its app-ready signal (the §9 "wait on the rail"
  fix), and the measurement substrate already shadow-pierces to confirm `jf-shell`/landmarks. But that wait
  runs **inside each capture path, downstream** of the serve layer's HTTP-200 handoff. So when the shell never
  mounts, the timeout is misattributed to the *capture* ("render-failed") instead of the *serve* layer ("app
  never mounted: <reason>"). The predicate exists; it is one layer too low.
- **618 §7 / Seam B has already named this exact seam.** 618's "one diagnosis": *the dev environment silently
  fails to correspond to the worktree, and nothing asserts that correspondence at the point of reliance.* Its
  **Seam B — FE serving**: "a sanctioned worktree-FE mode — serve this worktree's FE against a (borrowed,
  read-only) backend, on a free port, and **assert the served module is the worktree's code**." That is the
  *provenance* predicate. 615's substrate fragility is the **same seam**, surfacing the *second* predicate
  618's design didn't need (it served a page for a *human* to eyeball; 615's capture loop is automated and must
  gate on mount mechanically).

### 27.3 The design — one FE-serve authority with a two-predicate readiness contract

Match the structure to the problem: **collapse the fork into one FE-serve authority, and complete its readiness
contract with the two predicates the capture-consumer actually relies on.** No new subsystem.

1. **One authority.** The robust `serve-worktree-fe.cjs` is the seed; the Python auto-serve **consumes** it
   (shells out / shares a serve-lib) instead of maintaining a parallel weaker copy. One place owns port
   selection, boot, and the readiness handoff. This alone kills §26's `:5173`↔`:5174` race (one owner, free-port
   scan, never "trust 5173 because it answered").
2. **Predicate A — provenance (618's half, "is it *my* code?").** Free-port + `--strictPort` + the
   running↔worktree cross-check (606's provenance shape applied to Vite). Catches the stale/foreign-server class.
3. **Predicate B — readiness (615's half, "did the app *mount*?", the part the present problem adds).** The URL
   is **not handed to any consumer** until the served app has mounted — `jf-shell` defined *and* attached, the
   rail present — within a deadline. This is the existing capture-loop predicate **hoisted up into the serve
   contract**, so a mount failure is owned and named by the serve layer.
4. **Fail loud, with the reason.** On a readiness-deadline miss, the authority returns a **structured failure
   that names the cause** — Vite never booted (surface the now-un-`DEVNULL`'d stderr), booted but the shell
   never mounted (surface the error-overlay / boot-exception text — the §16 lesson that an empty `{}` fails the
   non-fail-open FE parse boundary makes this concrete), or a foreign server holds the port (provenance
   mismatch). The capture commands report "**cannot capture: <reason>**" instead of emitting a phantom
   "render-failed." This is the literal "or fails loudly with the reason" half of §26's ask.

Net effect: the three concrete bugs §26 papered over by hand (the port race, the silent dead-URL fallback, the
HTTP-200-not-mounted phantom) become **structurally impossible or loudly-diagnosed** — for a *naive* agent in a
fresh session, not just the author babysitting PIDs.

### 27.2a Scope discipline (what the present problem does NOT require)

- **No FE lease / ownership / takeover / reaper.** 618 already established it (and re-states it as a scope
  boundary): worktrees are *isolated* — one agent's FE-serve per worktree, each on its own free port. The single
  shared resource (the backend stack) already has the 606 lease; the FE server has no contention analog. A lease
  here is structure for a case the problem does not include.
- **No new MCP tool, no "dev-environment framework."** Local-first Python + the existing `cjs` helper. Building
  a generalized correspondence/readiness engine now is the premature abstraction 618 and 616 both warn against.
- **No backend orchestration.** Predicate A *borrows* a backend read-only (or runs fixtures with none); it never
  starts one.

### 27.4 Reach & principle (a conform result — recognized, not built)

**This design is not a new principle; it is an instance of one 618 already named, and a scaled-down instance of
a shape the *backend* lifecycle already embodies.**

- **It IS 618's seam.** 618's principle — *dev-environment ↔ worktree correspondence is asserted, not assumed* —
  is itself a specialization of the repo-wide invariant *two states that must correspond must have their
  correspondence asserted at the point of use* (the gate kernel's producer↔consumer family, `ssot-catalog-sync`,
  the runtime `ReadinessDimension`/health authorities, 606's provenance cross-check). 615's FE-serve fix is the
  **readiness completion** of 618's **provenance** Seam B. **Conformance rule: these do not get two
  implementations.** Whichever lands first, the other is an *extension of the same authority* — provenance and
  readiness are the two predicates of **one** FE-serve contract. (This is the explicit "conform, don't fork"
  outcome: there must be exactly one answer to "bring up this worktree's FE for an automated consumer.")
- **The recurring shape it surfaces, named plainly: *a readiness check must assert the condition the consumer
  actually depends on, not a cheap proxy for it.*** HTTP-200 is the proxy; "app mounted" is the condition. This
  is the same **liveness ≠ readiness** distinction the **backend** lifecycle already draws — the dev-runner
  separates "HTTP up ~15s" from "worker ready ~40s," and the product has a first-class `ReadinessDimension`
  authority. **The FE-serve uniquely lacks any readiness authority** — so every capture-consumer hand-rolls one
  per call (the §27.2 "predicate in the wrong layer"). The FE-serve is the *under-guarded mirror* of the
  backend's `ReadinessDimension`.
- **Candidate scope (record; do not build the generalization now).** The "TCP/HTTP-up as a proxy for usable"
  shape recurs wherever a readiness check is cheap-proxy rather than consumer-truthful: `ui_shot._is_server_alive`
  (already in scope), `serve-worktree-fe.cjs`'s own missing mount-check, and any test that treats the
  `awaitPort` port-open signal (CLAUDE.md pitfall guidance) as "service ready." **Existing violation already on
  record:** 618 §7's "defined but never mounted." Per the user's separation of *recognizing* a principle from
  *building* general structure: this is the recognition + its candidate scope. The present problem requires only
  the one FE-serve seam's two-predicate contract; a shared "dev-side readiness authority" is warranted only if a
  third independent proxy-readiness violation appears outside the FE-serve — not before. (618 draws the same
  "third independent instance is the trigger" line for its `build-output ↔ runtime-path` shape.)

**Title:** unchanged — the doc's title already frames the work as *conforming to existing seams*, and this is one
more conformance (to 618's worktree-correspondence seam), not a new thesis.

### 27.5 External check (2026) — narrow pass on the one volatile aspect, and how it sharpens Predicate B

Most of §27 is settled engineering I am *conforming* to (liveness ≠ readiness is ~2016-era k8s-probe doctrine;
one-authority, free-port/`--strictPort`, fail-loud are stable), so a broad research pass would be motion
without leverage — and §4 already did the external pass on the *measurement/perception* layer. The **one**
genuinely-active 2026 frontier my design touches is **Predicate B: how an automated browser loop detects "app
mounted and interactive," not merely "navigated/loaded."** A surgical search confirmed the design and surfaced
one real refinement:

- **The convention validates Predicate B's shape.** 2026 best practice for SPA-readiness is explicitly *"wait
  for the actual UI state you care about — a specific post-render UI element,"* not navigation/load lifecycle
  events (the SPA `load` event "fires very early because the shell is small"). My rail/`jf-shell`-present
  predicate **is** the settled shape — good, this is a conform, not a miss.
- **`networkidle` is discouraged** ("unreliable in modern apps with analytics/polling/websocket background
  traffic"). This independently **confirms rejecting HTTP/network signals** as the readiness proxy — the exact
  Defect-1 correction — and means Predicate B must *not* be re-expressed as a network-quiescence check.
- **The refinement — the hydration gap.** Element-*present* ≠ element-*interactive*: the node can be attached
  while the script that makes it actionable hasn't run, so a click no-ops. The robust predicate is "element
  **visible + actionable** (Playwright auto-wait's enabled/stable checks), optionally a content assertion," not
  merely `state="attached"`. Concrete consequence for the build: the readiness gate should assert the shell/rail
  is **visible** (as `ui_check`'s rail wait already does, `state="visible"`) — and the weaker
  `.jf-shell-dock` `state="attached"` wait (`ui_check.py:533`) is the kind of attached-not-interactive check
  this warns against. Predicate B inherits the *stronger* of the two, not the weaker.
- **No external convention for the fail-loud-diagnosis half** (Defect 2): the search found no settled pattern for
  "dev server up but app failed to mount → name the reason" in agent harnesses. So §27.3's structured-failure
  design is novel-but-uncontradicted — fine, but flagged as un-cross-checked (its correctness rests on internal
  reasoning, not an external standard to conform to).
- **Tangential datapoint (logged, out of §27 scope):** Microsoft now recommends **Playwright *CLI* over *MCP*
  for coding agents — ~4× fewer tokens** (~27k vs ~114k per task). Irrelevant to the readiness contract, but a
  real signal for §12/§26's token-economics thread on the §11 tooling's cost; recorded here, not actioned.

Sources: [BrowserStack — Playwright waitForLoadState (2026)](https://www.browserstack.com/guide/playwright-waitforloadstate),
[Mastering Playwright in 2026 (CodeStax)](https://codestax.medium.com/mastering-playwright-in-2026-a-hands-on-guide-to-modern-browser-automation-0546edbe9cff),
[Playwright MCP 2026 (Morph)](https://www.morphllm.com/playwright-mcp).

> **Status of §27:** ~~design only~~ **BUILT + fully browser-validated (2026-06-20, §29 + §30).** The §29
> happy-path environment-block is RESOLVED in §30 (spawn root-cause fixed: `node <vite-entry>` not a detached
> `.cmd` shim) — a green auto-served capture of the live shell is confirmed. Supersedes §26's loose "a
> `ui-serve`" phrasing with a precise two-predicate contract; binds the build to 618 Seam B so the two cannot
> fork.

## 28. Pre-implementation de-risk (confidence pass, 2026-06-20)

> A read + throwaway-experiment pass targeting §27's six load-bearing assumptions **before** building. No
> production code touched; probes lived under `tmp/derisk-615/` (deleted after). It used a self-started neutral
> Vite on a free port (`:5185`) — the foreign session's `:5173`/`:5174` + active dev-runner lease were left
> untouched. Outcome: the two make-or-break assumptions (U2, U1) **confirmed**, one over-promise (U3)
> **honestly downgraded**, and three design elements **forced** from optional to required.

| # | Assumption | Verdict | Evidence |
|---|---|---|---|
| U2 | Data strategy is the consumer's concern; served Vite is a neutral shell | **CONFIRMED+ (stronger than hoped)** | `install_fixtures` mocks at the Playwright `ctx.route` layer (`ui_fixtures.py:121-137`), intercepting `/api/*` *before* Vite's proxy 502 (`vite.config.js:133`). MSW has **no consumer** in FE src (grep empty), and `vite.config.js` branches only on `command`, never `mode` — so the auto-serve's `VITE_MSW=true`+`--mode mock` are **inert cargo-cult**. The served Vite is *already* a neutral shell server; `serve-worktree-fe.cjs`'s plain `vite` embodies it. |
| U1 | Detached server + a readable reason channel is achievable on Windows | **CONFIRMED feasible** | Probe: a `DETACHED_PROCESS\|CREATE_NO_WINDOW` child **survived the parent** AND its stderr **captured to a file** (`child_alive_after_capture=True`, captured text present). Neither existing path proves this (cjs=`inherit`/foreground, ui_shot=`DEVNULL`). |
| U3 | "Fail loud **with the reason**" is achievable | **PARTIAL — design posture downgraded** | `vite-error-overlay` detection had **no false positive** (null when healthy) → a usable channel; Vite compile/boot errors hit stderr, and U1 proved stderr is capturable → the §26 "stale/broken Vite" class **has** a reason (stderr tail + overlay). BUT corrupting a boot-critical contract (`/api/status`→`{}`) did **not** prevent the rail from mounting, and the truly-silent "defined but never mounted" class (§26/618 "no console error") is a JS/bundle failure whose reason may be **absent from console**. **Adjustment:** promise *"fail loud with the **best-available** reason, always including the captured Vite-stderr tail"* — never "the reason" unconditionally. |
| U4a | HTTP-200 ≠ app-mounted (the gap is real) | **CONFIRMED live** | HTTP-200 returned in ~1s in every scenario, while a real mount needed up to **15s** of rail-waiting. The gap the whole design rests on is real and large. |
| U4b | The `:5173`/`:5174` race is as diagnosed | **CHARACTERIZED (code-read) + live instance** | `_resolve_ui_url`/`_is_server_alive` use a 2s `urlopen` liveness check; under load a false-negative triggers `_start_vite_server`, which collides on the hardcoded `:5174` (`--strictPort`) → returns `None` → falls back to the dead default `:5173`. A 3-weak-link chain, all readiness-proxy. **Live instance observed:** both `:5173` *and* `:5174` UP with a **foreign active lease** — exactly the ambiguity that makes "trust `:5173`" unsafe (it is up, but serving another session's code). |
| U5 | The rail/`jf-shell` readiness element is universal | **CONFIRMED + refined** | The rail `[data-surface-id="core.search-surface"]` mounts **visible** on a clean boot, and is shell *chrome* — it mounted **even when boot data was corrupted**, so it is robust across data states (no empty-registry flake). Refinement: `jf-shell` was **in the DOM even when not ready** (custom-element-defined ≠ interactive — the §27.5 hydration gap, observed live), so the predicate must be **rail visible**, not `jf-shell`-present or `state="attached"`. The legacy `.jf-shell-dock` `attached` wait (`ui_check.py:533`) is in the dead `?shell-demo=1` branch, not the live path. |
| U6 | "No lease; worktrees isolated" holds | **CONFIRMED, with a required element** | The PID file `tmp/ui-shot-server.json` is CWD-relative (per-worktree → no file collision), so single-consumer-per-worktree **holds, no lease needed**. BUT the hardcoded `:5174` collides across worktrees (`--strictPort` → second serve fails). `serve-worktree-fe.cjs` already free-port-scans → **free-port scan is required, not optional** (it is the lease's substitute). |

### Design adjustments forced by the pass (fold into the §27 build)

1. **Base the one authority on `serve-worktree-fe.cjs`'s neutral plain-`vite`** (U2) — add detached spawn +
   stderr-to-file (U1) + a readiness handshake; the Python loop consumes it.
2. **Readiness predicate = rail *visible*** (`[data-surface-id="core.search-surface"]`), **not** `jf-shell`-present
   or `state="attached"` (U5 — element-present ≠ interactive).
3. **Free-port scan is REQUIRED** (U6/U4b), and the "trust `:5173` if it answers" branch in `_resolve_ui_url`
   must go (the live foreign-lease observation proves it unsafe).
4. **Failure contract = "best-available reason, always incl. the Vite-stderr tail"** (U3) — not an unconditional
   "the reason"; the silent never-mount class may have no console signal.
5. **Drop the inert `VITE_MSW=true` / `--mode mock`** from the serve path (U2 — cargo-cult, no consumer).

### Critical confidence rating: **8 / 10** (remaining §27 implementation)

The two make-or-break assumptions (U2 clean reconciliation, U1 reason-channel feasibility) verified; the
predicate (U5) and the no-lease scope (U6) confirmed; the failure diagnosis (U4) characterized with a live
witness; and the one over-promise (U3) honestly downgraded *before* it could ship as a false guarantee. The
residual −2 is real and named: (a) the truly-silent "defined but never mounted" class has **no guaranteed
legible reason** — the design now promises "best-available," which is honest but means some failures still read
as "app never mounted within Ns; cause not captured"; (b) the extract-vs-share factoring between
`serve-worktree-fe.cjs` (Node) and the Python loop is an execution choice not yet prototyped end-to-end (U1
proved the OS capability, not the full handshake). Both are *execution* risk on a now-de-risked design, not
open design questions. (Methodology: `interrogate-results` — the U3 "corrupting status didn't break mount" was
investigated, not reported as a clean pass; it became the U5 chrome-robustness finding + the U3 downgrade.)

## 29. As-built — §27 FE-serve readiness contract IMPLEMENTED (2026-06-20)

Built per the §28-adjusted design. Touched files: `scripts/jseval/jseval/ui_shot.py` (serve),
`scripts/jseval/jseval/ui_check.py` (readiness gate), `scripts/jseval/jseval/ui_fuzz.py` (serve-failure
surface), `scripts/jseval/tests/test_ui_serve.py` (fresh, 10 tests), `scripts/dev/serve-worktree-fe.cjs`
(cross-reference comment only). No FE source changed.

### What shipped (the two-predicate contract)
- **Robust native serve** (`_start_vite_server`): FREE-PORT scan (`_pick_free_port` — the cjs's dual-loopback
  connect-probe ported to Python; no more hardcoded `:5174`, U6); NEUTRAL vite (dropped the inert
  `VITE_MSW`/`--mode mock`, U2); stderr CAPTURED to `tmp/ui-shot-vite-<port>.log` (never `DEVNULL`); provenance
  (`branch`/`head`) + `stderr_log` on the server-info.
- **`_resolve_ui_url`** no longer trusts a foreign `:5173`; it serves our own provenance-guaranteed server or
  honors an explicit `--ui-url`.
- **Fail-loud, two kinds** — both surface as a clear result, never a phantom "render-failed":
  - serve-start failure → `ServeStartError` → `cannot serve: <reason>` (cold-start ceiling raised 15s→60s; on
    failure we do NOT fall back to `:5173`).
  - app-never-mounts → `_await_app_ready` (the ONE readiness gate, rail **visible** per U5) → `AppNotMountedError`
    → `cannot capture '<step>': app shell never mounted within 15s; <best-available reason>` (Vite stderr tail /
    `vite-error-overlay` text / honest fallback, per the U3 downgrade). Wired into the isolated path, the shared
    chain, and the `_run_eval` orchestrator.
- **Architecture (as planned):** one serve *contract*, one path per consumer class (automated = this module;
  human = the `.cjs`), drift-guarded by `test_ui_serve.py` — NOT one cross-language process (a deliberate,
  reasoned deviation from §27.4's literal "one process": cross-language detached-spawn would *add* the fragility
  §27 removes). The `.cjs` carries a symmetric cross-reference comment.

### Validation (73 ui_ tests green; live behavior confirmed)
- ✅ **Free-port** — a forced fresh start picked `:5175` (skipping the foreign-occupied `:5174`) — the U6 fix live.
- ✅ **Fail-loud (serve-start)** — `cannot serve: Vite did not accept connections on :5175 within 60s` (clean,
  attributed — not a phantom).
- ✅ **Fail-loud (not-mounted)** — `ui-shot home --ui-url <non-app HTTP-200 server>` →
  `cannot capture 'home': app shell never mounted within 15s; no Vite stderr or error overlay captured …`.
- ✅ **Readiness PASS + green capture** — capture against a real mounted app produced `home.png` with 59
  landmarks / 0 axe-NEW (readiness gate passes, capture proceeds).
- ✅ **stderr-capture** — a throwaway detached-`node` probe surfaced a real `ERR_MODULE_NOT_FOUND` into the log.

### Honest limit (environment-blocked, NOT a §27 defect)
The **happy-path auto-spawn of our OWN Vite could not be live-validated in this session**: the main checkout's
`modules/ui-web/node_modules` is incomplete (`.bin` empty, ~82 pkgs; vite present but its deps missing →
`ERR_MODULE_NOT_FOUND`), so **every** serve path fails to start a fresh Vite — bash `npx vite`, the original
harness, and the new code alike. Compounded by the documented session quirk that a detached `npx.cmd`/`cmd npx`
spawn dies immediately (scoop-shim-unreachable, `agent-lessons.md`). Both are environment defects (logged to
`observations.md`), independent of the §27 contract logic — which is unit-tested (10 tests) and whose four
behaviors are confirmed live. Fix to unblock the happy path: `cd modules/ui-web && npm ci` (not run here — it
mutates shared `main` infra while a foreign dev-stack session is active). The contract's *value* (loud,
attributed failures + free-port + provenance) is delivered and proven; the residual is the dev-env install, not
the design.

> **RESOLVED by §30 (2026-06-20):** the "happy-path environment-blocked" caveat above is closed. The real
> spawn root-cause was found (a detached `.cmd` shim dies on Windows; `node <vite-entry>` does not) and fixed,
> and a green auto-served capture of the live shell is now browser-validated. See §30.

## 30. As-built — §27 usability gap CLOSED (2026-06-20)

> **⚠ CORRECTED by §31 (2026-06-20).** This section's headline — "the spawn root-cause was found and fixed" —
> is **wrong**. A controlled experiment (§31) showed the `node`-spawn change was **not** what unblocked the
> happy path; the **install self-healing** was. `node`-spawn is a real *failure-legibility* improvement, not the
> happy-path fix. Read §31 before trusting §30's causal claim.

§29's critical analysis was right: §27 made the harness *fail loud* but not *work*. This closes the gap — the
auto-serve now actually delivers "a clean mounted app" (§26's #1 ask), end-to-end browser-validated.

### Root cause (the spawn, not just the install)
Two causes, separated: (1) **fragile spawn** — the harness launched `npx.cmd vite` **detached**, and a Windows
`.cmd` batch shim dies immediately when detached (the §29 "no stderr captured" silent death). The dev-runner
(`dev-runner.cjs:1117`) runs Vite as `npm run dev` → `.bin/vite` → **`node` executes vite's JS**; the
detached-safe analog is to skip the shim and run **`node <vite-entry>`** directly (`node.exe` is a real PE that
detaches cleanly with captured stderr — §28 U1). (2) **incomplete install** yielded a thin reason.

### What shipped (`ui_shot.py`, `_start_vite_server`; tests in `test_ui_serve.py`)
- **Launch via `node <vite-entry>`** — `_resolve_vite_entry` reads `node_modules/vite/package.json` `bin` (→
  `bin/vite.js`); `node` resolved via `shutil.which`. Drops `npx.cmd` entirely. Everything else (free-port,
  neutral, captured stderr, provenance, 60s poll, readiness gate) is unchanged.
- **Actionable broken-install reason** — a pre-flight check raises `ServeStartError("FE deps
  missing/incomplete … run \`node scripts/dev/prepare-worktree.cjs\` (or \`npm ci\`)")`, replacing the §29 thin
  "no stderr captured". The post-spawn stderr tail is now populated too (node writes real errors to it).
- 13 serve unit tests (was 10): vite-entry resolution, the actionable broken-install reason, and the updated
  contract assertion (cmd is `[node, <entry>, --port, --strictPort]`, no `npx`/`--mode mock`/`VITE_MSW`).

### Browser validation (the success bar — confirmed)
- ✅ **Happy path** — `ui-shot home --fixtures` from a clean env auto-started Vite via `node` (my own server,
  pid-owned port `:5174`, provenance `main`/`9ccf6e8c9`), the rail mounted, and the **PNG shows the real live
  Lit shell** (header + nav rail + search surface + chips + date filters + status bar). axe `1 known` (the
  baseline search-surface issue) confirms it's the populated app, not a blank page.
- ✅ **Server persistence** — a 2nd `ui-shot library --fixtures` reused the same server (pid unchanged), 63
  landmarks.
- ✅ **Fail-loud still holds** — broken-install → the actionable `FE deps missing … run prepare-worktree`
  reason (live probe); not-mounted → the §29 `cannot capture … app shell never mounted` (unchanged path).
- The foreign dev-stack session's ports were never touched (my server took a *free* port; `--strictPort`
  guarantees no collision).

### Honest note
The session's `node_modules` self-healed between §29 and §30 (a parallel `npm ci` completed: `.bin` repopulated,
vite resolvable), so the happy path could be validated without me mutating shared `main`. The **code fix** (the
`node`-spawn + actionable reason) is what makes it robust regardless: on a *broken* install it now names the
remedy loudly; on a *complete* one it serves and captures. §27's usability outcome is delivered — the §11 tools
are genuinely "lifted", not just honest about failing.

## 31. Experiment pass — correcting §30's causal claim (2026-06-20)

> §30 claimed a "spawn root-cause … found and fixed." My own dogfooding critique flagged that I never
> *isolated* the spawn change from the install self-heal (I changed two variables, validated once). Three cheap
> experiments resolved it — and overturned the §30 headline. This is the `interrogate-results` discipline
> applied to my own work.

### E1 — Causal isolation (decisive): `node`-spawn was NOT the unblocker
On the *now-healed* install, ran both detached spawns sequentially on free ports: **`npx.cmd vite` (old)
bound** AND **`node <entry>` (new) bound**. So on a *complete* install the original `npx.cmd` path works fine
— the §29/§30 "npx.cmd dies, empty log" was caused by the **broken install**, not a fundamental
`.cmd`-shim-dies-detached property. **What actually closed the happy-path gap was the install self-healing, not
my code.** §30's "spawn root-cause" framing is corrected here.
- **`node`-spawn's *real*, narrower value (still genuine):** on a *broken* install, the §30 probe showed
  `node` surfaces a legible `ERR_MODULE_NOT_FOUND` to the captured stderr, where `npx.cmd` died *silently*
  (empty log). So `node`-spawn improves failure **legibility** (it feeds the fail-loud reason channel) — it is
  not the thing that makes a broken install serve (nothing is). Reframed from "the fix" to "a legibility
  improvement"; the change stays, on those honest terms.

### E2 — The "actionable reason" had a coverage gap (now fixed)
The §30 pre-flight only catches an *absent* vite; on the *real* §29 state (vite bin present, deps missing) it
**passes**, then `node` fails at runtime and the timeout reason showed the raw error without naming the remedy.
Fix: the timeout `ServeStartError` now detects a module-resolution error in the stderr tail
(`ERR_MODULE_NOT_FOUND` / `Cannot find module|package`) and appends *"FE deps look incomplete; run
prepare-worktree / npm ci"* — so the incomplete-deps case is as actionable as the absent case. New unit test
pins it (14 serve tests).

### E3 — The `library` `console-real:1` I waved off was REAL (logged, not fixed)
Reading the capture's `console_errors`: `[WireContract] /api/indexing-roots/substrate did not match the
generated schema (contract drift)`. Cause: that endpoint has no entry in `ui_fixtures._ROUTES`, so `--fixtures`
serves `{}` and the FE parse boundary rejects it — a **fixtures gap**, not an app bug, but it pollutes the
`console_real` signal (it reports as category `app`). Logged to `observations.md` (out of serve scope). This
vindicates the critique that I "validated as the author and waved off a real signal."

### Net correction
The happy-path *fix* was the environment (install completion), not my spawn change — §30 overstated it. My code
still earns its place on honest, narrower terms: (a) `node`-spawn makes the broken-install failure *legible*
instead of silent; (b) the timeout reason now *names the remedy* for the incomplete-deps case. The §27/§30
contract value (loud, attributed, free-port, provenance) stands; the **causal attribution** is what needed
fixing — and now does.

## 32. Candidate follow-ups surfaced this session (2026-06-20) — triaged, not yet built

A reflective pass over the session's findings, separating real in-domain issues from scope-creep polish.

### Issue A — Fixtures incompleteness ⇒ the `console_real` signal is untrustworthy (STRONGEST candidate)
E3 (§31) found `/api/indexing-roots/substrate` is absent from `ui_fixtures._ROUTES`, so `--fixtures` serves
`{}`, the FE logs `[WireContract] … contract drift`, and `ui_measure._classify_console` tags it category
`app`. Two compounding defects:
1. The fixture allowlist is a small hand-maintained set; **any** endpoint a surface calls that isn't mapped
   yields a real console error. One was found by accident on `library` — there are likely more on other
   surfaces (investigation below).
2. A *fixtures gap* reads as an *app error*, so the `console_real` flag (which **ASSERT** gates on) conflates
   "real bug" with "incomplete fixture" — directly undermining the determinism/trust the whole §11 layer
   rests on (the §12 finding #2 noise problem, in a new guise).
This is in-domain, found this session, and fixing it strengthens the foundation (not just correctness).
**Investigation: §33.**

### Issue B — Discoverability (highest *adoption* leverage, but a known prior gap)
§26 + the §31-followup critique both rank this top: five capabilities (`ui-a11y-gate` / `ui-diff` /
`ui-critic` / `ui-fuzz` / `--trace`) exist but only `ui-shot` is hook-pushed, so the rest rot. The single change
most likely to make the toolset *used* by a fresh agent. Larger lift (a hook/skill design); recorded as the big
lever, not taken here.

### Explicitly NOT doing (anti-scope-creep, recorded for the next agent)
- **Fast incomplete-deps detection** (fail-fast vs the 60s timeout before the §31 remedy fires) — saves ~60s in
  a rare failure; not worth it.
- **A gate enforcing one-authority across `ui_shot` ↔ `serve-worktree-fe.cjs`** — real but low-frequency drift;
  a gate for two serve paths is over-engineering. The comment + test is proportionate.
- **Reverting the `node`-spawn change** — E1 showed it isn't the unblocker, but its failure-legibility value is
  real; keep it on the §31 narrower terms, don't churn.

## 33. Issue-A investigation (2026-06-20) — the gap is REAL but much SMALLER than feared, and the tool found a real bug

Investigated Issue A (§32) statically + empirically. Outcome: a healthy **de-escalation** plus a genuine
product-bug find — exactly what good investigation does.

### Static scan overstated it; empirical reality is surgical
- **Static:** the shell calls **~25 distinct unmapped `/api/*` endpoints** across non-search surfaces
  (Brain's `ai/*`/`inference/*`, Health's `condition-recovery-index`/`metrics/*`, Settings'
  `plugins/allowlist`/`authorizations/grants`, Browse's `indexing/roots`/`knowledge/folders`, registry
  `workflows`/`surfaces`/`shapes`/`witness`, …) vs the **6 mapped** in `_ROUTES` (status, knowledge/search,
  settings/*, registry/{operations,resources,diagnostic-channels} — i.e. exactly the home/search path).
- **Empirical** (captured home/library/health/settings/ai-brain under `--fixtures`): only **ONE** endpoint
  actually drifts — `library` → `/api/indexing-roots/substrate`. `ai-brain` = **0** errors, `health` = 0,
  `home` = 0. Most unmapped endpoints **don't** trip the non-fail-open `parseWireContract` (loose parse /
  SSE streams / lazy-load / not fired on initial render), so `{}` is swallowed silently.
- **So the "`console_real` untrustworthy" concern is real but tiny** — 1 false-positive endpoint, not 25. The
  cheap fix is one schema-valid fixture for `/api/indexing-roots/substrate` (recorded in `observations.md`).

### The deeper root is fixtures DATA realism (a known §26 gap), not `_ROUTES` coverage
The drift and the Settings finding (below) are both artifacts of the **degenerate empty-fixtures state** (empty
registries, `{}` for unmapped) — apparent "app" signals realistic data wouldn't produce. This is §26's
"fixtures reflect a degenerate empty state, not the populated app" — confirmed as the real substrate limit. A
fuller fix is realistic populated fixtures (deferred; bigger initiative), not per-endpoint patching.

### The classifier is RIGHT — and it caught a genuine bug
`ui_measure._classify_console` correctly tags `[wirecontract]`/a11y signals as `app` (the §15 intent: in
production, drift is real and the agent must see it). The default-to-`app` is defensible. **Evidence it works:**
the `settings` capture surfaced **2× `[jf-control] no accessible name … (559 Authority V)`** — a real a11y
signal (nameless control) the measurement layer caught, *not* a WireContract/data drift. This vindicates the
§12 "10-min dogfooding finds real bugs" thesis — the tool surfaced a defect I'd otherwise miss.
- **Honest caveat:** it needs **live disambiguation** — under `--fixtures` a control whose label is data-driven
  could read as nameless purely from empty data. So it's *either* a real a11y bug the 559 static gate missed
  *or* an empty-data artifact. Logged to `observations.md` for a live (non-fixtures) confirm — I did not start a
  dev stack to resolve it now (scope).

### Net
Issue A is real but **right-sized small** (1 drift endpoint). The classifier needs no change. The genuine
substrate limit is fixtures-data realism (§26, deferred). And the investigation surfaced a candidate real a11y
bug on Settings — the highest-value output of the pass. Concrete next steps (NOT taken here, per "investigate
not fix"): (a) add the `indexing-roots/substrate` fixture; (b) live-confirm the Settings nameless-control;
(c) treat fixtures-data realism as the real (larger) follow-up.

## 34. As-built — provenance hole in the reuse gate FIXED (2026-06-20)

A critical review of the §30/§31 changes found that §27's **provenance** predicate ("the served code is this
worktree's") was **not enforced on the reuse path** — and fixed it.

### The defect
`_is_server_alive` decided to reuse a tracked server from only `root`-path match + `port` HTTP-responds. It
**never checked the recorded `pid` was alive**, so a stale `server-info` whose port was later taken by a
FOREIGN process false-reused that process → capture against unknown code. §27/§30 *recorded* `pid`+`provenance`
but the gate ignored both, leaving them decorative. (Observed: §30's first run silently reused a stale `:5174`.)

### The fix (`ui_shot.py`, `ui_check.py`)
- **Fix 1 (MAJOR) — pid-liveness.** New `_pid_alive(pid)` (Windows `tasklist /FI "PID eq …"`, mirroring the
  `backend.py` taskkill pattern; POSIX `os.kill(pid,0)`). `_is_server_alive` now returns `False` unless the
  recorded pid is alive — a dead/absent pid ⇒ start a fresh, provenance-guaranteed server. The recorded `pid`
  is now load-bearing.
- **Fix 2 (SECONDARY) — reason-source guard.** `_await_app_ready` only attaches the server-info stderr tail
  when the captured `page.url` port matches the server-info `port` — so an external `--ui-url` (or a stale
  server-info) can't attach an unrelated server's stderr as the failure reason.

### Validation
- 18 serve unit tests (was 14): `_pid_alive` (self=alive, spawned-then-reaped=dead), `_is_server_alive`
  rejects a dead pid even when the port responds, and the reason-guard skips a mismatched-port server. Full
  suite 81 green.
- **Live impostor test (the decisive one):** auto-served (pid 41824, `:5174`) → killed the vite, LEFT the
  server-info, started a non-app HTTP impostor ON `:5174` → re-ran `ui-shot home --fixtures`. The reuse gate
  detected the dead pid, **refused the impostor**, and started a FRESH server (pid 39148, `:5175`), capturing
  the real app (59 landmarks, not the impostor page). Before the fix this reused the impostor and failed the
  readiness gate.

### Worktree-path validation (Fix 3) — PASSED live
The §30 `node`-spawn was only validated in `main`. Closed the gap with a real prepared-worktree run (the
dominant case; avoids the unprepared junction-fallback's node_modules-junction cleanup hazard, 618 §2):
`git worktree add` from HEAD → `prepare-worktree.cjs --no-dist` (own `npm ci`) → `ui-shot home --fixtures`.
**Result: green** (59 landmarks, axe 1-known = the real app), and the server-info confirmed
`root=…\wt-615val\modules\ui-web` (the **worktree's** FE, not main's) with provenance `{branch: HEAD, head:
90b32f47e}` recorded correctly. So `node <vite-entry>` serves the worktree's own code end-to-end, and §34
provenance is recorded per-worktree. Removed via the sanctioned `remove-worktree.cjs` (main `node_modules`
intact). Residual: the unprepared **junction fallback** is validated-by-equivalence only (rare degraded path;
not live-run to avoid the junction-deletion hazard) — recorded as the one acknowledged low-risk gap.

## 35. As-built — provenance HARDENING (2026-06-20): airtight reuse + reason

§34 left two named residuals; this closes both (opted in for airtight provenance, accepting a small reuse-path
cost). The §34 review noted both were closeable with a command-line verification of the running process — done.

### What shipped (`ui_shot.py`, `ui_check.py`)
- **`_pid_is_our_vite(pid, port)`** (+ `_process_cmdline` — Windows `Get-CimInstance Win32_Process`, POSIX
  `/proc/<pid>/cmdline`→`ps`): the reused pid must be a **node-vite serving the recorded port** (`'vite'` and
  `--port <port>` in its cmdline). `_is_server_alive` now requires pid-alive **and** `_pid_is_our_vite` before
  trusting the port — closing §34's residual: the OS recycling our recorded pid to an unrelated process (or a
  different-port vite) while a foreign server holds our port can no longer false-accept. (Port-based, not
  root-path-based, so it's robust across main / prepared-worktree / junction-fallback.)
- **Reason path** (`_await_app_ready`): now also requires the server-info to be **live** (`_pid_alive`) before
  using its stderr — closing §34's Fix-2 residual where an external `--ui-url` sharing the exact port of a
  *stale dead-pid* server-info could attach a misleading stderr tail.

### Validation
- 21 serve unit tests (was 18): `_pid_is_our_vite` (vite-on-port=ours; non-vite / wrong-port / dead=not-ours),
  `_is_server_alive` rejects an alive-but-recycled pid that isn't our vite, the reason path skips a stale
  dead-pid server. Full suite 84 green.
- **Live no-regression (the risk):** the cmdline gate must still ACCEPT our real vite, else reuse breaks into a
  cold start every call. Confirmed: auto-serve (pid 28960) → a 2nd `ui-shot` **reused the same pid** — the gate
  accepts our own vite. (The §34 impostor path is unchanged: a dead pid short-circuits at `_pid_alive` before
  the cmdline gate.)

### Cost (honest)
The cmdline gate adds one `Get-CimInstance` call (~hundreds of ms) on the reuse check — once per `ui-shot`
invocation when a tracked server exists. Negligible against a multi-second capture; the price of airtight
provenance. No residual edge remains for the reuse/reason paths.

## 36. Conceptual self-assessment — does the §27–§35 arc serve THIS doc's goals? (2026-06-20)

A step back to judge the whole serve-substrate arc against the tempdoc's *intent*, not its own internal
success criteria. Honest verdict: **the narrow goal is satisfied and exceeded; the broad goal is only ~⅓
served, and the arc is mis-weighted toward the one lever I'd already built.**

### Satisfied (and then some)
- **§26's literal #1 ask — "a robust server lifecycle: guarantees a clean mounted app or fails loudly with the
  reason"** — fully delivered (§30 works · §27 fail-loud · §34/§35 airtight provenance), browser-validated.
- **Design conforms, not forks** — §27 conformed to 618's correspondence seam (the doc's §6–§7 principle).

### Real mismatches vs the doc's BROADER goal (usable, trusted, adopted measurement tools)
1. **One lever built and over-hardened; the two higher-leverage ones untouched.** §26 named *three* velocity
   levers: (a) robust server lifecycle, (b) **realistic populated fixtures**, (c) **discoverability** (a
   hook/skill surfacing ASSERT/DIFF). I built (a), then went *deeper* into (a) (§34 hole, §35 cmdline
   hardening) while (b) and (c) — which §26 *and* my own §32/§33 triage rate higher for the actual goal —
   remain unbuilt. The post-§31 critique said "the top lever is discoverability… I built solid plumbing," then
   I built more plumbing. *Caveat: §34 was a found bug and §35 was user-directed — prompted depth, not
   autonomous gold-plating — but the cumulative weight still tilts hard to lever (a).*
2. **The TRUST half is still degenerate at the data layer (diagnosed, not fixed).** §11's frame is "the
   substrate is the verifier." I hardened the *serve* layer, but §33 showed the **empty-fixtures DATA** still
   pollutes the `console_real`/ASSERT signals the verbs consume (a real WireContract drift + a nameless-control
   warning surfacing as `app` errors). The verifier is robust; its *inputs* are not — exactly lever (b).
3. **Proportionality tension with the doc's "modest adjunct" framing (§12).** §35's airtight provenance
   (cmdline verification for a negligible-probability recycled-pid edge) is deeper than the doc's YAGNI spirit
   invites for the serve layer of a tool it rates as modest — correct and user-requested, but worth naming.

### Forward steer (recorded, not built)
The remaining value clearly lives in **(b) fixtures-data realism** and **(c) discoverability**, NOT more
serve-layer hardening. Lever (a) is done; further investment there has sharply diminishing returns against this
doc's actual purpose. Next agent: pick (b) or (c). (Concrete (b) seed already in `observations.md`: the
`/api/indexing-roots/substrate` fixture + the broader populated-state gap; (c) is a `ui-shot-hint`-style
hook/skill surfacing the other five verbs.)

## 37. Long-term design for levers (b) + (c) (2026-06-20) — both are the *same* correspondence seam §6.1/§27 already use; conform, don't fork

> §36 named the two remaining levers as forward steers. This section is the **design** for them — general, not
> implementation. The headline is the same shape as §27's: neither (b) nor (c) is a new problem or a new
> authority. Each is an **implicit correspondence that drifts silently** and must be *asserted*, and the repo
> already has the home for that assertion. (b) conforms to **Seam A** (`register-guard-resolution`) — it is to
> the *data axis* exactly what §6.1's `check-ui-step-coverage` was to the *source-path axis*. (c) conforms to
> the two **push-surfaces that already exist** (the `/ui-check` skill + the `ui-shot-hint` hook) — both are
> merely *incomplete*. Read §37.4 for the reach: §3, (a), (b), (c) are four instances of one invariant.

### 37.0 What the investigation found (current state of both levers)

- **(b) fixtures.** `ui_fixtures._ROUTES` is a **hand-maintained 6-endpoint allowlist** (status · knowledge/search
  · settings · registry {operations,resources,diagnostic-channels}) with hand-captured `__fixtures__/*-live.json`
  snapshots; **there is no record/capture mechanism** — the bodies were lifted once from the §16 experiment and
  committed. `fixture_body()` returns `"{}"` for every unmapped endpoint. So a surface that calls an endpoint not
  in the allowlist gets `{}`, the non-fail-open `parseWireContract` logs `[WireContract] … contract drift`, and
  `ui_measure._classify_console` tags it `app` — **a missing fixture is byte-indistinguishable from a real app
  bug.** §33 right-sized the *coverage* miss to **1 live endpoint** (`/api/indexing-roots/substrate`), but the
  *mechanism* is unchecked: the next surface/endpoint to land drifts the same silent way.
- **(c) discoverability.** Two surfaces already *push* capability knowledge to the agent — the `/ui-check`
  **skill** (loaded on trigger; its description is auto-injected every session) and the `ui-shot-hint`
  **PostToolUse hook** (fires on a `shell-v0/**` edit). Both are **incomplete by the same omission**: the skill
  documents `ui-shot`/`ui-check`/`measure` but **none of** `ui-a11y-gate`/`ui-diff`/`ui-critic`/`ui-fuzz`/`--trace`
  (all confirmed real `@main.command`s in `cli.py`); the hook emits only `jseval ui-shot <step>`. The five verbs
  are real, tested, and **invisible** — the exact "tool freshness ⇒ worth ~0" failure mode §2/§3 named, recreated.

### 37.1 Lever (b) — design: assert the *fixture ↔ contract* correspondence (NOT enrich the data)

The disciplined reframing. §36 called (b) "fixtures-data **realism**," which reads as "make the fixtures serve
rich populated state." That is a real but **larger** want the present problem does **not** require: the §11
measurement verbs assert **structural** facts (a11y landmarks, axe, layout/overflow, contrast) that hold on an
empty surface as well as a populated one; the one thing the empty state actually *breaks* is the `console_real`
trust signal, via silent fixture-drift. So the problem the tempdoc has is a **correspondence/closure** problem,
not a **data-richness** problem. Match the design to that.

**The design (two moves, both conforming to existing seams):**

1. **Separate "intentionally empty" from "missing" in the fixture handler.** Today both collapse to `"{}"`. A
   fixture the harness *chose* to leave empty (an empty registry catalog) is a legitimate deterministic state; a
   fixture for an endpoint nobody mapped is a *gap*. The handler should know the difference — serve a schema-valid
   empty body for *declared* endpoints, and make an *undeclared* `/api/*` call **loud** (a distinct tag the
   classifier can route to `fixture-gap`, not `app`). This is the actual trust fix: it un-pollutes `console_real`
   so ASSERT stops conflating "incomplete fixture" with "real bug."
2. **Bring the fixture set under Seam A (`register-guard-resolution`), exactly as §6.1 did the step index.** The
   `_ROUTES` allowlist is an **ungoverned register** — the same diagnosis §6.0 made of the step index before
   §6.1 governed it. The proportionate structural version is a check that the fixture register **covers every
   `/api/*` endpoint the shell-v0 surfaces actually call** (the call sites are statically discoverable in
   `modules/ui-web/src/**`, as §33's static scan already proved). A surface that calls an unmapped endpoint
   becomes a **dangling reference** — the precise failure class `register-guard-resolution` already catches — so
   fixture-drift fails at check-time with a named endpoint, instead of surfacing as a phantom runtime "app error"
   a fresh agent chases. (`structural-defects-no-repeat` applies: §33's one documented silent drift proves the
   class; the check is warranted now, not "wait for a second instance.")

**Explicitly NOT building (recognized, scope-matched):**
- **A record/replay ("VCR/cassette") fixture engine** that captures `/api/*` from a populated live stack. This is
  the genuine path to *populated* realism, and it is the right design **if and when** a verb needs to assert on
  rich data (e.g., a future GENERATE over realistic result sets). The present problem (structural facts + a
  polluted trust signal) does not require it. Record the pattern; don't build the engine. *Seed for the day it's
  warranted:* the EvidenceBundle/`capture_evidence` machinery (Seam B) already captures structured artifacts from
  a running stack — a record mode would be a consumer of it, not new infrastructure.
- **Generating fixtures from the tempdoc-564 wire schemas.** Tempting (the FE validates against generated
  schemas; a minimal valid instance per schema would be drift-proof by construction) but only a handful of the
  ~25 endpoints *have* generated schemas, so it solves a fraction at real cost. The correspondence-check above
  catches the same drift class without a JSON-Schema-faker. Note it as the eventual "drift-proof by construction"
  ceiling; the check is the proportionate floor.

**The minimal-now vs structural cut.** The *strictly minimal* fix is just move 1's missing-vs-empty split plus
adding the one `/api/indexing-roots/substrate` fixture (already in `observations.md`). The *structural* fix adds
move 2's coverage check. Recommend shipping both: move 1 alone leaves the register hand-maintained, and new
endpoints land in `shell-v0` routinely, so the drift **will** recur — which is exactly the condition
`structural-defects-no-repeat` says to design against, not the condition ("low historical rate") it says to wait
on.

### 37.2 Lever (c) — design: *complete the two push-surfaces that already exist* (NOT a new registry)

The whole of (c) is: the capability set the agent *can* reach must correspond to what the agent's discovery
surfaces *show* it. Two such surfaces already exist and already push; both are simply missing the five verbs.

**The design (two moves, both on existing surfaces):**

1. **Complete the `/ui-check` skill body with a "which verb when" decision table.** The agent's real need is not
   six command synopses — it is *which instrument to reach for*: `ui-shot`+`measure` for "see/verify one
   surface"; `ui-a11y-gate` for "did I break a11y closure"; `ui-diff` for "did this change move anything I didn't
   intend" (capture-before/after); `ui-critic` for a design-reference critique; `ui-fuzz` for edge-state hunting;
   `--trace` for an interaction trajectory. The skill description is auto-injected every session, so completing
   it is the highest-leverage, lowest-ceremony move — it puts the verbs where the agent already looks.
2. **Make the `ui-shot-hint` hook context-aware about *which* verb.** Today it always emits `ui-shot <step>`. It
   already classifies the edited file (it maps file→steps); it can map file→*verb* just as cheaply: a
   token/style/`*.styles.ts` edit also suggests `ui-a11y-gate`/`ui-critic`; an edit to a surface already captured
   this session suggests `ui-diff`. Same hook, same lookup shape, no new mechanism.

**Explicitly NOT building (recognized, scope-matched):** a generalized **capability registry** with per-verb
trigger rules / a marketplace-style discovery layer. For six verbs behind one skill + one hook that already
exist, that is structure for a problem this size does not have. (616 §0 makes the same call from the other
direction: this repo already over-invests in the skills/hooks/gates layer; the bar for *new* scaffolding is
"removes a recurring failure without colliding with what we have" — completing two existing surfaces clears that
bar; a new registry collides with it.)

### 37.3 Remaining verification item (not design — a live pass owed)

§33 surfaced a candidate real a11y bug — Settings renders **2× `[jf-control] no accessible name` (559 Authority
V)** — that needs **live (non-fixtures) disambiguation**: under `--fixtures` a data-driven label could read as
nameless purely from empty data, so it is *either* a real bug the 559 static gate missed *or* an empty-data
artifact. Logged in `observations.md`. This is a dev-stack smoke (start stack → capture `settings` live →
re-read the `console_real` a11y signal), owed regardless of whether (b)/(c) are built. Note: lever (b) move 1
(missing-vs-empty split) would itself *reduce* this ambiguity by tagging empty-data artifacts distinctly — a
reason to sequence (b) before re-running this check.

### 37.4 Reach — §3, (a), (b), (c) are four instances of ONE invariant the system already names

Stepping back from the two designs: this is not two unrelated levers. **Every substrate fragility in 615 is the
same shape** — an *implicit correspondence between two things that drift independently*, where the drift, left
unasserted, **masquerades as a product signal** and sends a fresh agent chasing a phantom:

| # | Correspondence (A ↔ B) | Drift masquerades as | Asserted by | State |
|---|---|---|---|---|
| §3 | step index ↔ source paths on disk | "verified" (looked at deleted React) | `check-ui-step-coverage` (Seam A) | **built** (§6.1) |
| (a) | served code ↔ *this* worktree's code | a UI bug / unknown-code capture | pid+cmdline provenance gate | **built** (§27/§34/§35) |
| (b) | fixture set ↔ FE's actual `/api/*` calls | an app error (`console_real`) | fixture coverage check (Seam A) | **built** (§38, extends §6.1 gate) |
| (c) | capability set ↔ the agent's discovery surfaces | the tool not existing (worth ~0) | complete skill + hook | **built** (§38) |

**The invariant, named plainly: *every adjunct-tooling correspondence must be asserted, not assumed — or it
silently rots, and the rot reads as a real signal.*** This is the generalization of the "**tool freshness**"
failure mode the tempdoc itself flagged in §2/§3 ("a stale index silently makes the agent verify nothing"). It
is **the same seam §27 conformed to** (618 §7 / Seam B: "dev-environment ↔ worktree correspondence asserted, not
assumed") — and the enforcement home already exists for the assertable cases (Seam A:
`register → gate → guard-resolution`). So 615 did not discover a principle; it kept rediscovering *one* principle
on four different axes.

**Candidate scope beyond 615 (named, NOT built — recognizing ≠ abstracting):**
- **`__fixtures__/*-live.json` ↔ the tempdoc-564 generated wire schemas.** A captured fixture can drift from the
  contract it is meant to satisfy; the generated-schema authority *could* validate the fixtures (the "drift-proof
  by construction" ceiling noted in §37.1). **Latent violation today** — fixtures are hand-captured, schema-checked
  by nothing.
- **Skill descriptions ↔ the actual CLI verbs / canonical docs.** `skills-sync.mjs` already projects docs→skills,
  but nothing projects *CLI-verbs*→skill — which is why (c) could rot. The (c) fix is a point repair; the general
  version is "the skill's capability list is a projection of the verb registry, not a hand-list."
- **Any "captured golden" ↔ its source of truth** (a11y baselines, evidence bundles): same class.

**The disciplined line (per the brief): record the invariant + its candidate scope; do NOT build a generalized
"correspondence kernel" now.** The present problem requires exactly the two assertions in §37.1–§37.2 (and §3/(a)
are already built). A unified abstraction over all correspondence checks is structure for cases 615 does not yet
include — premature. Seam A is already the shared mechanism for the assertable ones; new members join it
one register at a time (as §6.1 did), which is the conformant, non-abstracting path. The value captured here is
the *recognition* — the next agent who hits a "phantom signal" in adjunct tooling should first ask "*which
correspondence drifted?*" before debugging the surface, and should reach for Seam A rather than invent a check.

## 38. As-built — levers (b) + (c) IMPLEMENTED & validated (2026-06-20)

The §37 design, built and validated. Both levers conformed to existing seams (no new gate, no new push
mechanism), and the §33 owed live check is resolved. The §37.4 reach table is updated: (b)/(c) now **built**.

### (b) Fixture↔contract correspondence — DONE
- **Move 1 (the trust fix).** Added `("/api/indexing-roots/substrate", '{"items":[],"count":0}')` to
  `ui_fixtures._ROUTES` (schema-valid empty `listResponseSchema`). **Browser-validated:** `ui-shot library
  --fixtures` → `.measure.json` `console_errors: []` (was 1× `[WireContract]` tagged `app`), and the PNG
  renders Library's real **"No watched folders"** empty-roots state, not a broken parse. The fixtures gap that
  §33 found is closed.
- **Move 2 (the structural assertion).** Extended the **existing** `check-ui-step-coverage` gate (not a new
  gate) with a **fixture-coverage clause** + a `fixtureExempt[]` array in `ui-step-coverage.v1.json`. It
  extracts the `parseWireContract` (non-fail-open) endpoints from each `viewCoverage[].source` view file
  (resolving the `ENDPOINT`-const indirection) and fails unless each is mapped in `_ROUTES` (the single
  authority, regexed from the Python source — no dual list) or exempt. **Gate green:** 2 view-surface strict
  endpoints decided (`/api/indexing-roots/substrate` mapped; `/api/health/events/stream` exempt — SSE served
  as an empty event-stream). **Negative test:** removing the exemption makes the gate fail with the precise
  named endpoint + exit 1; restored → green. The next strict-parse endpoint added to a view can no longer
  drift silently.
- **Regression test.** `test_ui_fixtures.py::test_indexed_roots_substrate_is_mapped_and_schema_valid` pins the
  route + body; full `test_ui_fixtures.py` 7 green.
- **Scope held:** no classifier-sink plumbing, no record/replay engine, no schema-faker (all §37.1
  recognized-not-built). The exemptions encode §33's empirical "doesn't trip on initial render" finding.

### §33 owed live-verify — RESOLVED: it is a REAL a11y bug
Started a dev stack (worker-ready, no model needed) and captured `settings` **live, no `--fixtures`**
(`--ui-url http://127.0.0.1:5173`). The **2× `[jf-control] no accessible name` (559 Authority V) PERSIST with
the real backend** → not an empty-data artifact but a **genuine a11y defect the 559 static gate missed** — the
measurement layer caught a real bug (vindicating the §12 dogfooding thesis). Logged to `observations.md` as a
product fix for `SettingsSurface.ts` (out of scope here per `log-pre-existing-issues`). Stack stopped after.

### (c) Discoverability — DONE (completed both existing push-surfaces)
- **Move 1 (the skill).** Added a **"which instrument when" decision table** to the hand-authored
  `/ui-check` SKILL.md covering all six verbs (question answered · server/fixtures needed · exit-code
  contract). `skills-sync --check` OK (ui-check is not a synced skill — undisturbed). **4/6 verbs
  live-confirmed this session:** `ui-shot` (library/settings captures), `ui-a11y-gate` (exit 0, clean vs
  baseline), `ui-diff` (exit 0, "no semantic change — determinism holds"), `ui-critic` (prints the grounded
  critique prompt); `ui-fuzz`/`--trace` documented verbatim from their `cli.py` docstrings + prior §11/§26
  live validation.
- **Move 2 (the hook).** Extended `ui-shot-hint.mjs` with context-relevant verb hints (same hook, same
  lookup): a style/token/theme edit surfaces `ui-a11y-gate` + `ui-critic` **even when the file maps to no
  ui-shot step** (the highest-gain case — style files aren't in the index); a covered-surface edit adds the
  `ui-diff` before/after hint. **Validated 3 crafted-payload cases:** indexed view → steps + diff (no style
  block); style file not in index → style block fires; non-style non-indexed FE file → silent.
- **Scope held:** no capability registry / marketplace (§37.2 recognized-not-built).

### Net
615's two remaining velocity levers are built, each as an extension of an existing seam: (b) is one more
clause in the §6.1 coverage gate (the data axis joining the source-path axis under Seam A); (c) is the
completion of the two surfaces that already push (skill + hook). The §33 live check resolved to a real bug,
now logged. The serve-substrate over-investment §36 flagged is balanced: the two higher-leverage levers are
now done with minimal, conforming structure. **615's substrate work (a/b/c) is complete.** The candidate
scope in §37.4 (fixtures↔564-schemas; skill↔verb-registry projection; captured-golden↔source) remains
recorded-not-built by design.

## 39. Forward-looking research — what the measurement substrate ENABLES (2026-06-21)

> A pure-research/ideation pass (no code): now that the substrate (deterministic fixture captures + the
> structured `.measure.json` facts + the six verbs + the coverage-gate invariant) is built, what could it
> *become*? Four targeted web-research threads (2025-26 primary sources) pressure-tested the strongest ideas
> for **validate-or-conform**, not a field survey (§4 did that). The headline: **the structured a11y/measurement
> layer we built for verification is the same layer the 2026 industry has converged on for agent *perception*
> and agent-driven *repair*.** We accidentally built one half of an agent-UI substrate; the research says the
> other halves are now mainstream, and tells us what shapes to conform to. Per the doc's discipline: this
> section **records principles + a researched menu with dispositions** — it does not build, and it keeps the
> tempdoc's YAGNI/conform-to-seams stance.

### 39.0 How this was researched (and why one round)
Four parallel threads, each asked for primary-cited findings + an explicit *validate/challenge + conform-to*
verdict: (A) autonomous UI self-repair loops + LLM-critic reliability; (B) measurement-based visual regression
+ design-token conformance linting; (C) the a11y tree as an agent perception channel; (D) state-space UI
exploration / fuzzing. One round sufficed — each reached a clear validate-and-conform verdict with named
failure modes; what remains is judgment, not more facts. (A 2nd round would have diminishing returns; the gaps
left are design choices, not unknowns.)

### 39.1 The menu, by axis (research verdict · conform-to · disposition)

**EXTEND — the standout (NEW UX, highest reach): the a11y/measurement capture pointed INWARD as the app's own
agent's perception channel.** JustSearch *has* a built-in agent (operations/resources, `UnifiedChatView`,
tool-calling). The same shadow-pierced a11y snapshot `ui_measure.py` captures for dev verification is — per
Thread C — *exactly* the channel 2026 production agents use to operate UIs (Playwright MCP, OpenAI Atlas/CUA
all key on the ARIA tree's role+name+state; Anthropic Computer Use's pixel-first approach is the cited outlier;
~12× token advantage; ~78% task success on an accessible page vs ~half on a degraded one). The thesis
**"accessibility = agent legibility — one investment, two payoffs"** is mainstream (Google web.dev, NN/g), not
ours. **Conform to:** emit role+name+state shaped like Playwright's `aria_snapshot` (the de-facto MCP
observation format) — do **not** invent a bespoke landmark schema; this is *inbound* perception, distinct from
the *outbound* MCP-Apps/AG-UI/A2UI protocols (flag MCP Apps as adjacent only if the chat surface ever renders
rich tool UI). **Disposition:** recognize the big version (wire the live agent's perception to the a11y tree)
— **not built**; the *cheap, proportionate first slice* is a reframe: our existing a11y `.measure.json` already
**is** the agent-legibility audit for this app — use it to drive **accessible-name quality** (the §33 Settings
nameless-control is precisely the defect that blinds *both* a screen-reader and an agent). Failure modes to
design against (all sourced): closed shadow roots (audit that shell-v0 uses *open* roots — it must, since the
harness works), cross-boundary ARIA IDREF breakage (prefer self-contained `aria-label` over cross-root
`aria-labelledby` for agent-critical landmarks), snapshot staleness (re-snapshot after each action), and
name-quality as *the* bottleneck.

**EXTEND — highest near-term concrete value: the generate-and-verify UI self-repair loop.** Thread A
**strongly validated** it and found we are *ahead* of the commercial frontier: Antigravity/v0/Lovable verify
with **pixels + a human**; our deterministic, backend-free, baseline-relative `.measure.json` is a *more
rigorous, CI-gateable oracle*. The closest precedent is academic — **AccessGuru** (re-run-the-detector-until-
score-zero, GPT-4 cut violation scores ~84%, regression-safe) and Microsoft's shipped Playwright **Healer**
(a11y-tree oracle, >75% on selector failures, explicitly can't heal what it can't observe). **Conform to:**
AccessGuru's control flow (iterate-until-the-oracle-says-zero, re-prompt on residual); **oracle = re-run the
same detector that found the defect** (axe→fix→axe, mechanical, not LLM-judged); **two authorities** — the LLM
*proposes* the edit, the measure file *decides* pass/fail; the **semantic-diff verb is what upgrades a single
fix into a verified-no-regression loop**. **Disposition:** the components all exist (capture · measure ·
critic · diff · gate) and the *manual* loop already works (agent runs ui-critic → edits → ui-diff verifies);
the increment is orchestration. Recognize as a near-term capability; the **proof-of-concept = repair the logged
§33 Settings nameless-control *through* the loop** (capture → axe/name violation → edit `SettingsSurface` →
re-capture → diff+gate confirm fixed, no regression) — which doubles as fixing a real bug. **Not built here.**

**EXTEND — strongest novelty as a gate: a *rendered-layer* design-token conformance check.** Thread B's
sharpest finding: **every** existing design-token linter (Atlassian, MetaMask, Kong, Stylelint) operates at
the **source layer** (literals in JSX/CSS) — asserting that the *computed/rendered* value lands on an approved
token scale (4px grid; token spacing/font/z/duration) is an **unfilled niche**, and it catches off-scale
values that survive source linting (computed from `calc()`, inherited, theme-resolved, third-party). We already
capture computed styles (`ui_measure`) and already declare the scales (`design-reference.v1.json`), so this is
the design-system analog of the a11y gate — a natural Seam-A extension. **Conform to:** Chromatic's
"only new/changed in this PR" baseline-delta semantics; the DTCG `.tokens.json` / Style Dictionary format for
the token authority. **Honest caveat:** needs a noise-tuning phase (legitimate sub-grid values: hairlines,
borders) the source linters don't. **Disposition:** recognize as a real candidate gate — **not built**;
medium effort, genuine differentiation.

**EXTEND — cheapest high-ROI, and the tempdoc's own "highest-value" call: enrich the fuzzer's variant axis with
pseudolocalization.** Thread D **validated** generalizing `ui-fuzz` (§6.4's deferred state-matrix fuzzer) and —
importantly — **vindicated the tempdoc's deferral choices**: it should be a *curated* matrix (Storybook-stories
model), **not** a blind cross-product (Chromatic itself rejects that) and **not** an automated GUI crawler
(empirically weak at targeted bugs); invariant-anomalies as the oracle (which `ui-fuzz` already does) *is* the
property-based-testing model. The single highest-ROI addition is **Google's pseudolocale recipe** (`psaccent` =
accents+expander+brackets for hardcoded-string/truncation/overflow bugs; `psbidi` = fakebidi for RTL/mirroring)
— a proven, deterministic generator that replaces ad-hoc "long filename"/"RTL" variants. Then prioritize
variants by empirical bug-yield (empty ✓ → oversized/huge → error/malformed), and reach for pairwise/PICT only
once axes exceed ~3. **Disposition:** the pseudolocale variant is a small, well-precedented extension of the
existing `VARIANTS` axis — the most actionable single item here. LLM-generated adversarial data is a credible
*fixture generator* (not the oracle, not the driver) — recognize, defer.

**EXTEND — closes the "Remember" faculty: a perceptual changelog.** Thread B confirmed the category ships
(Percy's Visual Review Agent: plain-English "what changed where" in PRs; Chromatic's per-PR axe-delta). Our
`ui-diff` is the deterministic/measured version — *more precise and free* ("button moved 8px, contrast
4.6→3.9" is a measured fact, not a model guess), *narrower* (only what we extract). **Conform to:** Chromatic's
baseline-delta semantics; run `ui-diff` across many surfaces for a branch → one human-readable UX changelog.
**Disposition:** recognize — **not built**; medium value (the verb exists; this is cross-surface orchestration).

**POLISH (conformance debts the research surfaced).** Two "don't reinvent" items: (1) emit the **a11y slice**
of `.measure.json` as **EARL + JSON-LD** (the W3C ACT interchange standard; axe-core already maps to it) so the
facts are interoperable rather than bespoke; (2) align `design-reference.v1.json` to the **DTCG token format**.
Low effort, real interop value. **Disposition:** recognize as conformance polish.

**SIMPLIFY.** The §12/§26 token economics still hold — `measure.json` is bigger than the capped PNG, and the
*summary line* is the actual product. A "summary-first, drill-down-on-demand" artifact shape (lean default,
full facts on request) is the right simplification; the two-Playwright-stack split (U6) is **not** worth
unifying (the doc already judged this). **Disposition:** recognize the summary-first polish; leave the stacks.

### 39.2 Reach — two principles the research surfaces (named, NOT built)

**P3 — the verification substrate and the agent-operation substrate are the *same* accessibility/measurement
layer; build it once, point it both ways.** *[SUPERSEDED by §40.0 — the app's own agent operates via a typed
`OperationCatalog`, not by reading its UI, so the inward turn is a downgrade/fork for self-operation. The
surviving, corrected form is §40: the measurement substrate is the render-truth verifier of the
operation→control→name projection, for human + EXTERNAL-agent legibility.]* This is the inward turn of §4's "accessibility = agent legibility,"
now backed by 2026 convergence evidence (Thread C). We built *perceive* (a11y snapshot) and *verify*
(measure/diff/gate) as dev tooling; the identical layer is what lets the app's **own** agent perceive its own
UI, and what makes the self-repair loop's oracle work. **Candidate scope:** the app's agent system
(operations/resources/`UnifiedChatView`) could consume the same role+name+state snapshot the harness emits; the
dev harness is then the *audit instrument* for that legibility. **Existing latent gap:** accessible-name
quality (the §33 Settings bug is one instance) degrades *both* payoffs at once. **Do not build** the unified
agent-perception layer now — record the principle; the proportionate first move is treating the a11y measure as
a legibility audit and fixing name quality.

**P4 — the measurement oracle generalizes from "did I break it?" to "is it good?" only as far as the *facts*
reach; the LLM-critic is a soft, large-delta-only advisory, never a numeric gate.** Thread A's reliability
evidence is unambiguous: LLM UI-judgment is trustworthy for *direction on large deltas* (75–93%) but
**near-random (~50%) on similar UIs**, reads ~1 Likert point high, and underestimates aesthetic/emotional
quality. This **reinforces the tempdoc's own §6.4 call** ("Judge-quality genuinely does not reduce to a gate")
with external data, and it is the boundary that keeps any self-repair loop honest: the *deterministic* facts
(axe/contrast/geometry/overflow/console) carry the gate; the critic only proposes and ranks. The named failure
mode to design against everywhere: **oracle blind spots = silent non-closure** — "axe is green" must never be
allowed to imply "the UI is good."

### 39.3 Disposition summary (nothing built; everything recorded)

| Idea | Axis | Research verdict | Conform to | Disposition |
|---|---|---|---|---|
| A11y measure as the app's own agent's perception channel | new-UX | **validated** (industry converged) | `aria_snapshot` role+name+state | **standout — recognize; cheap slice = legibility audit + name-quality** |
| Generate-and-verify UI self-repair loop | extend | **validated; we're ahead of frontier** | AccessGuru loop + two-authorities | recognize; PoC = fix §33 bug through the loop |
| Rendered-layer design-token conformance gate | extend | **validated (unfilled niche)** | Chromatic delta + DTCG | recognize (real candidate gate) |
| Pseudolocale fuzzer variants | extend | **validated; vindicates §6.4** | Google psaccent/psbidi; pairwise>3 axes | **most actionable single item** — recognize |
| Perceptual changelog across surfaces | extend | **validated (category ships)** | Chromatic baseline-delta | recognize |
| EARL/JSON-LD a11y + DTCG token format | polish | **conform, don't reinvent** | W3C ACT / DTCG | recognize (conformance debt) |
| Summary-first artifact economics | simplify | (internal §12/§26) | — | recognize |

### 39.4 Sources (2025-26; primary unless flagged)
- Self-repair / oracle: AccessGuru `arxiv.org/html/2507.19549v1`; Playwright Healer ecosystem `testdino.com/blog/playwright-ai-ecosystem`; "MLLM as a UI Judge" `arxiv.org/html/2510.08783v1`; LLM-judge failure taxonomy `arxiv.org/pdf/2511.19933`; Antigravity `developers.googleblog.com/build-with-google-antigravity…`.
- Measurement-diff / token linting: Chromatic a11y `chromatic.com/features/accessibility-test`; Argos diff `argos-ci.com/docs/diff-algorithm`; W3C EARL `w3.org/WAI/standards-guidelines/act/report/earl/`; DTCG via USWDS `designsystem.digital.gov/design-tokens/`; Atlassian token lint `atlassian.design/components/eslint-plugin-design-system/…`.
- A11y-as-agent-perception: Google `web.dev/articles/ai-agent-site-ux`; MCP Apps `blog.modelcontextprotocol.io/posts/2026-01-26-mcp-apps/`; OpenAI CUA `openai.com/index/computer-using-agent/`; NN/g "AI Agents as Users" `nngroup.com/articles/ai-agents-as-users/`; shadow-DOM a11y (Igalia) `alice.pages.igalia.com/blog/how-shadow-dom-and-accessibility-are-in-conflict/`.
- State exploration: Chromatic Modes `chromatic.com/docs/modes/`; Storybook tests `storybook.js.org/docs/writing-tests`; Google pseudolocalization `opensource.googleblog.com/2011/06/pseudolocalization…`; pairwise `pairwise.org`.
(Aggregator/SEO sources — testdino, nohacks, pagup, bug0, WorkOS — were treated as lower-confidence and used only where they corroborated a primary.)

## 40. Long-term design — the measurement substrate is the *rendered-truth tier of presentation closure* (2026-06-21)

> **⚠ BUILD WITHDRAWN BY §43 (principle retained).** §40 proposes building an *a11y* render-truth closure on
> the strength of one alleged violation (the §41 Settings "nameless" controls). §43 proved that violation was a
> measurement artifact — the controls are correctly named, axe already detects real cases — so **the a11y
> render-truth closure is NOT built** (recognize-not-build; no proven violation). The §40 *principle*
> (source-truth ≠ render-truth) still stands as a recognized shape; only its proposed build is withdrawn. Read
> §43 before treating §40.1–§40.4 as actionable.

> §39 was a researched menu. This section is the **design** that the menu, checked against the actual codebase,
> resolves to — general, not implementation. The investigation that drove it **overturns §39's standout** (see
> §40.0), and what replaces it is smaller, sharper, and conforms to a seam the system already lives by. The
> design is modest because the present problem is modest (one bug-*class*); its *reach* is not.

### 40.0 The decisive investigation — the app's agent does NOT perceive its own UI (so §39's standout is wrong)
§39's standout was "point the a11y-tree capture *inward* as the app's own agent's perception channel." A
source-grounded investigation of the agent architecture refutes it for self-operation:
- **The agent's action channel is a typed registry, not the UI.** One `OperationCatalog` →
  `AgentOperationEmitter` projects to OpenAI function-calling tools; the agent invokes operations by structured
  arguments, filtered to `ExecutorTag.AGENT`. It never clicks/types on rendered elements.
- **The agent's perception channel is structured state, not the render.** Its context is the indexed-roots
  list + condition-recovery context + tool results as JSON (`AgentPromptComposer`). It consumes **zero** a11y
  tree / DOM / screenshots — by design, which is what lets agent runs go headless/offline.
So the app's own agent already has a **superior, typed** legibility channel; routing it through its own a11y
tree would be a **downgrade and a fork** of the operation registry. §39's standout is **rejected for
self-operation.** (The "accessibility = agent legibility" thesis survives — but for *external* consumers:
human screen-readers and *external* automation agents that lack the operation API. The two-payoffs framing is
correct; the consumer was misidentified.)

### 40.1 The corrected design — measurement as the render-truth tier of an existing single authority
The investigation surfaced the real seam. The system already has a **single authority** for "what a capability
is and what it's called": the `OperationCatalog`. It **projects** two ways (the tempdoc-22 / 621 pattern,
which the adjacent-doc review confirmed is the load-bearing one): to the **agent** (function-calling tools) and
to the **UI** (`<jf-control operation-id>`, whose *accessible name* is literally
`present({kind:'operation', id})` — Control.ts:9-12). The `<jf-control>` primitive is built so that a nameless
interactive element is *"unrepresentable through it"* (559 Authority V), and static gates (`controls-a11y`,
`check-a11y-closure`) enforce that at the **source**.

**And yet the §33 Settings control rendered nameless, live.** That is the whole design in one fact: a strong
*source-truth* guarantee ("nameless is unrepresentable by construction") and a strong static gate, and the name
**still dropped at the render** — because the name's *input* (the operation's label, or the presence of an
operationId/label/slot at all) is itself runtime/projection-dependent, and **no tier checks the render**.

So the measurement substrate's correct long-term role is the **rendered-truth tier of presentation closure**:
the system's presentation gates (559/569: a11y-closure, contrast-matrix, layout-purity, declared-surfaces) all
assert invariants from **source/registers**; the measurement substrate (`.measure.json`: accessible names,
contrast, geometry, overflow, console) is the **counterpart tier that asserts the same invariants from the
running render** — and the two must agree. It is **not a new authority and not a new perception channel**; it
is the *verifier that the single-authority projection (OperationCatalog → control → accessible name) survives
all the way to pixels.* The tempdoc said this in §6.0 without naming it: the presentation gates "convert
visual properties to assertable measurements, but **statically** … never from a running render." The
measurement substrate is the running-render half, and §33 is the proof the static half alone is insufficient.

### 40.2 Scope — what the present problem requires, and what it does not
**Requires (proportionate to the bug-class §33 proved):** elevate the substrate's a11y/legibility facts from an
*incidental console warning during an unrelated capture* to a **first-class, run-on-purpose render-truth
closure check** on the one correspondence that matters — *every rendered interactive control resolves to an
accessible name* (which, for operation-backed controls, must be the projection of the `OperationCatalog`). This
is the render-truth twin of the existing static `check-a11y-closure`; it closes exactly the gap that let the
Settings bug ship. The mechanism largely **exists** (`ui-a11y-gate` captures surfaces + the 559 runtime
`no accessible name` signal + `ui_measure` classification) — the design is to *promote the existing signal to
a deliberate closure assertion*, not to build new apparatus.

**Names its one honest dependency:** a render-truth a11y check is only trustworthy on a **non-degenerate
render** — under empty `--fixtures` a data-driven label can read as nameless purely from missing data (the §33
false-positive risk). So this tier depends on either realistic populated fixtures (the deferred fixtures-data
realism, §37.1/§33) **or** a live run. The design **names** this coupling rather than pretending the empty
fixture state suffices; it is the one precondition, and it tells you *why* fixtures-realism matters (it is what
makes the render-truth tier honest).

**Does NOT require (recognized, not built — YAGNI against both §39's enthusiasm and the research's):**
- **A new "UI-facts registry + governance gate"** parallel to `run-renderers.v1.json` (the adjacent-doc review
  tempted exactly this). One render-truth a11y gap does **not** warrant a second register; the existing
  559 a11y-closure register + the ui-step-coverage register already hold the authority, and the measurement
  substrate *extends* them to the rendered tier. A bespoke measurement-facts registry would be premature
  abstraction.
- **The §39 consumers** (self-repair loop, rendered-token gate, perceptual changelog, pseudolocale fuzzer):
  these are *downstream consumers* of the same render-truth facts, each its own later decision. The long-term
  design is the **tier** (render-truth closure); the consumers ride it. Recorded in §39, not built.
- **Pointing the app's agent at its own a11y tree** — rejected (§40.0).

### 40.3 Reach — one conformance, one named principle
**This design is an instance of a seam the system already has — conform, don't fork.** The
single-authority-projection pattern (tempdoc-22/621: one `OperationCatalog` → many emitters; "live and record
converge at the model layer, never at render-time"; registered projection authorities, gate-guarded) is
exactly what the measurement substrate must respect: the accessible name is a *projection* of the one
authority, and the substrate's job is to *verify the projection reached the render* — not to mint a second
name, a second authority, or a second a11y register. The measurement substrate is a **read-side verifier over
the canonical projection**, which is precisely the role 621 reserves for read-side projections (separate from
the view-orchestration host). So this design adds **no new authority**; it conforms to the existing one.

**The principle it reveals (named, NOT built): *source-truth ≠ render-truth — a presentation invariant checked
only at the source can silently fail at the render, and only a real render reveals it.*** This is the
specialization of §37.4's "correspondence asserted, not assumed" to the **source↔render axis**: the static
presentation gates assert the *source* obeys an invariant; the *render* can still violate it because its inputs
(data, theme resolution, projection results) are runtime-bound. **Candidate scope — every static presentation
gate has a render-truth twin:**
- **a11y** — `check-a11y-closure` (source) vs the rendered nameless control. **Violated today** (the §33
  Settings bug is the proof instance).
- **design tokens** — §39's "rendered-layer token conformance" is the *same shape*: source token-linting vs the
  rendered off-scale value (computed from `calc()`/theme/inheritance). **Latent.**
- **contrast** — `check-contrast-matrix` (token math at source) vs the actual theme-resolved rendered contrast.
  **Latent.**
The measurement substrate is the *one place* a render-truth twin should be asserted from (each twin a
projection over `.measure.json`, never a second render-checker). **But build only the a11y twin now** — it is
the only one with a proven violation (§33). The token/contrast twins are recorded candidates; manufacturing the
generalized "render-truth tier for all static gates" before a second violation exists would be the premature
abstraction this tempdoc has refused at every step. Recognize the principle and its scope; build the one rung
the present problem stands on.

### 40.4 Net
The long-term design is **not** a new capability and **not** the §39 menu — it is the *recognition of what the
substrate already is*: the render-truth verification tier that the static presentation gates have always been
missing, guarding the operation→control→accessible-name projection that the §33 bug proved can silently drop.
It conforms to the single-authority-projection seam (22/621), needs no new register, and requires building only
the a11y render-truth closure (scoped to the proven bug-class, gated on a non-degenerate render). Everything
else — the §39 consumers, the token/contrast render-twins, the agent-perception inward turn — is recognized and
deliberately deferred. (§39's standout is superseded by §40.0; §39's other rows stand as a downstream-consumer
menu riding this tier.)

## 41. User-facing consequences of the §40 design — inspected live (2026-06-21)

> **⚠ PREMISE SUPERSEDED BY §43.** This section treats the 2 Settings controls as real nameless defects. A
> deeper investigation (§43) proved they are correctly named ("Load"/"Grant family", per axe + the accname
> slot-flattening algorithm); the signal it relied on is a false positive. Read §43 before trusting §41.

> A user-facing-consequences pass on the §40 design, grounded in a **live browser inspection** (clean dev
> stack, real render), not the tempdoc alone. The honest framing first, because it governs everything below.

### 41.0 Is there user-facing work? — indirect/frontend-relevant: YES, but no end-user UI of its own
The §40 design is a **verification tier** (a dev/CI render-truth a11y check). It has **no direct end-user
screen** — nothing in it renders to a user. But it is **frontend-relevant and *indirectly* user-facing**: its
whole purpose is the *accessibility of rendered controls*, and its proof instance (§33) is a real shipped UX
defect. So this is not a "skip — purely internal" case; the user-facing surface is the **a11y standard the tier
enforces** and the **real defects it catches** — which I confirmed by inspecting the live UI.

### 41.1 What the live inspection found (don't judge from the tempdoc alone)
On a clean dev stack, navigated to the Settings surface, a shadow-piercing scan (the same check the §40 tier
would run) found **exactly 2 nameless visible controls out of 541** — matching §33 precisely:
- **the "Load" button** (PLUGINS section — load a plugin from a URL), and
- **the "Grant family" button** (AUTHORIZATIONS section).
Both render **visible text** ("Load", "Grant family") but their operable `<button>` has **no accessible name**
(no `aria-label`, no slotted text reaching the name, no `operation-id`). Screenshot evidence captured; the
"Load" button was outline-marked live to confirm it is the visible affordance, not a hidden node. By contrast,
the neighbouring "Revoke" buttons *are* named, and a different surface (Governance) had 521 controls all named
— so this is **not** a universal atom failure; it is specific to how these two are built.

**The sharp, dangerous shape this reveals:** it is **not** "a control with no name" — it is **a control with a
*visible label* but *no accessible name*** (a WCAG 2.5.3 "label in name" failure). That is the *worst* class
because it is invisible to every cheaper tier: a sighted developer sees "Load" and moves on (visual review
passes), the source uses the correct primitive (source review passes), and — decisively — the atom
(`Button.ts`) is *itself correctly designed*, carrying an explicit rule: *"the slot text IS the accessible
name; set `label` only on icon-only buttons."* Source-correct, static-gate-passing, atom-guaranteed
"nameless is unrepresentable by construction" — **and the rendered name is still empty.** Only a tier that
reads the *rendered accessible name* sees it. This is a second, sharper instance of §40's source-truth ≠
render-truth principle, found in the wild.

### 41.2 The user-facing design conclusion (theorized)
Because the tempdoc's design is a verification tier, the "correct frontend/user-facing design" is **not a new
UI to build — it is the user-facing *standard* the tier enforces, sharpened by what the inspection showed:**

1. **The standard: every visible interactive affordance must carry an accessible name that *equals or contains
   its visible label* (WCAG 2.5.3), verified at the RENDER.** Not "has some name" — the rendered accessible
   name must match the visible text. This is the user-facing contract the §40 tier exists to keep: the shipped
   app's controls are legible to screen-reader users, keyboard users, *and* external agents (the §39 thread-C
   "accessibility = legibility for human + external agents" payoff, in its correct outward-facing form).
2. **The tier's check should target the visible-label-≠-accessible-name class specifically**, because that is
   the class no cheaper tier catches (§41.1). A render where a control has visible text but an empty AX name is
   the high-value signal; a genuinely empty control (no text either) is rarer and louder.
3. **The honest data dependency stands (§40.2):** this check is only trustworthy on a non-degenerate render —
   but note these two defects reproduced **identically on both the populated (§33) and the clean stack**, so
   they are structural, not empty-data artifacts. That is itself useful: a defect stable across data states is
   exactly what a render-truth gate can assert without needing realistic fixtures; data-dependent labels are
   the ones that need the deferred fixtures-realism.

### 41.3 Scope — what is 615's, and what is not
The **fixes** for the two controls (give "Load" / "Grant family" an accessible name that matches their visible
label — whether via the consumer passing proper slot text or a nested-slot name-drop fix in the Settings usage)
are **SettingsSurface product work**, already logged to `docs/observations.md` — **not** 615 tooling work. 615's
user-facing design contribution is the *tier and the standard*, plus the sharpened "visible-label ≠
accessible-name" framing the live inspection produced. No new end-user UI is added by this tempdoc; the
user-facing improvement is realized when the tier (catching this class) drives the product fixes that make the
rendered controls accessible. (Title unchanged — "measurement alongside vision" still holds; §41 only sharpens
*the user-facing standard* that measurement enforces.)

## 42. Pre-implementation de-risk — the §40/§41 a11y render-truth closure (2026-06-21)

> **⚠ PREMISE SUPERSEDED BY §43.** This de-risk pinned the 559 signal as "the detector" and judged it
> data-independent — but did **not** verify the controls' real computed accessible name. §43 did, and found the
> 559 signal is a **false positive** (the controls are named). The conclusion "build a 559-based gate" is
> withdrawn; axe already provides correct render-truth a11y detection. Read §43.

> A confidence-building pass before implementing the §40/§41 check (NO feature built). Read-only investigation
> + two deterministic `--fixtures` experiments. Net: confidence rose materially — the mechanism mostly exists,
> the detector is pinned, and the biggest worry (a live-data dependency) **did not materialize**.

### 42.1 Six uncertainties → resolutions (each cited)
| # | Uncertainty | Resolution | Evidence |
|---|---|---|---|
| U1 | Does **axe** catch the nameless buttons, or only the **559 console signal**? | **Only the 559 signal.** axe misses them. | settings `--fixtures` capture: axe = `['select-name']` (no `button-name`); the 2 buttons show only as `559_nameless=2`. `ui-a11y-gate` returned **exit 0 "clean"** despite them. |
| U2 | Is the check **data-independent**, or does it need realistic fixtures / a live stack? | **Data-independent in practice.** | Under `--fixtures` (empty data) the 559 count is settings **2**, all other 5 surfaces **0** — only the 2 real structural defects, **zero** empty-data false positives. Matches §33 live + §41 clean-stack. |
| U3 | Mechanism home + closure-vs-ratchet? | `ui-a11y-gate` is an **axe-rule-id ratchet** (NEW-vs-`knownRules`) that **never consumes the 559 signal** → the nameless-button class is **currently ungated**. The render-truth closure is a genuinely new predicate, not redundant. | `ui_a11y_gate.evaluate` reads only `axe.violations`; the green exit confirms the gap. |
| U4 | Does `ui_measure` capture per-control name+text, or must it be built? | **Neither needs full AX re-computation.** The primitive's own DEV-gated 559 signal (`!resolvedName() && !textContent`, Control.ts:545) is already captured in `console_errors` (classified `app`). Ride the signal, don't re-derive names. | `ui_measure.capture_measure` captures landmarks/axe/geometry/**console**; `_classify_console` tags `[jf-control]` → `app`. |
| U5 | Is **Settings** in the gate's surface set? | **Yes** — `settings` is a baseline surface (`uiShotStep:"settings"`, knownRules `["select-name"]`). The gate already runs where the defect lives. | `governance/ui-a11y-baseline.v1.json`. |
| U6 | Overlap with the static a11y gates? | **Complement, no conflict.** `check-a11y-closure` = landmark-role *projection* (source). `check-controls-a11y` = every `<jf-control>` **literal in source** resolves a name. The 2 defects are `<jf-button>` whose name drops across the **nested slot at render** — structurally invisible to a source scan. The render-truth tier fills exactly that render-time gap (same 559 Authority V intent, render tier). | `check-a11y-closure.mjs`; `check-controls-a11y.mjs:222-248`. |

### 42.2 Confirmed shape of the remaining work (now low-surprise)
Capture covered surfaces under `--fixtures` (exists) → **add one gate predicate: fail if a covered surface's
measure carries a `[jf-control] no accessible name` console signal.** Reuses the captured, classified data; the
only new logic is the predicate + its surface loop (mirrors `ui-a11y-gate`). Complements the static
`check-controls-a11y` at the render boundary; needs no new register (joins the existing a11y-baseline authority).

### 42.3 Residual risks (none blocking; all named)
- **R1 — future data-driven false positive.** A *future* control with a data-driven label empty under
  `--fixtures` could fire a spurious 559 (none today). Mitigation in hand: a `knownRules`-style exemption or the
  §41 "require visible text" refinement. Low.
- **R2 — sequencing/tolerance (the real coordination point).** A *hard-closure* gate (zero nameless) goes green
  only once the 2 existing defects are fixed — and that fix is **SettingsSurface product work, out of 615's
  tooling scope** (logged). So the gate must either wait on that fix or carry the 2 as documented known-debt
  with a fix ticket. Cross-boundary dependency, not a technical risk.
- **R3 — coverage is initial-render only.** The tier sees controls that render on initial surface load (the 2
  defects do). A nameless control behind a tab/modal/interaction escapes without extending the step. Inherent
  harness scope; medium; not a surprise.
- **R4 — DEV-only signal.** The 559 console.error is tree-shaken in production (Control.ts:546). Correct for a
  dev/CI verification tier (the harness serves dev builds); it is not and should not be a prod runtime check.

### 42.4 Critical confidence rating: **8 / 10**
Up from "design recorded, unverified." The two factors that moved it most:
- **Up:** the detector is pinned (559 signal, not axe) and the **data-independence held empirically** (2 real,
  0 false across 6 surfaces under `--fixtures`) — so the check is a small predicate on already-captured data in
  the existing deterministic path, with no live-stack/fixtures-realism dependency and no gate overlap.
- **Down (−2):** the gate's *value* depends on the 2 product fixes landing (R2, a cross-scope coordination), and
  coverage is initial-render-only (R3) with a low residual future-false-positive (R1). No architectural
  surprises remain; the open items are a sequencing decision and known scope limits, not unknowns.

## 43. CORRECTION — the "2 nameless controls" were a measurement artifact; §41/§42 premise overturned (2026-06-21)

> **This section supersedes §41 and §42's premise.** A pre-implementation investigation (the *next* tier of
> the de-risk) checked the one thing §42 had assumed rather than verified: the controls' **real computed
> accessible name**. It is not empty. The "defect" was a false positive in two naive detectors. The planned
> feature work (a 559-based render-truth gate + "fixing" the 2 controls) was therefore **not built** — it would
> have gated on phantoms. A small measurement-trust fix was shipped instead. This is the surprise the de-risk
> existed to catch, caught before implementation — the `interrogate-results` discipline paying off.

### 43.1 The finding (two authoritative methods agree: the controls ARE named)
- **Real accessible name, computed by the W3C accname slot-flattening algorithm** (`assignedNodes({flatten:true})`,
  run live in the browser on the inner `<button>`): **"Load"** and **"Grant family"** — *not empty*. A screen
  reader announces them correctly.
- **axe-core** (Experiment A, §42) found **no `button-name` violation** on Settings — axe implements the real
  accname algorithm, so it agrees the buttons are named. (It *did* flag `select-name` — a genuinely nameless
  `<select>` — proving axe reliably catches real nameless controls.)
- **Root cause of the phantom:** `jf-control`'s DEV self-check (`Control.ts:545` — `!resolvedName() &&
  !this.textContent`) reads the control's *own* `textContent`, which for a nested `jf-button` is the **empty
  forwarded `<slot>`** (`Button.ts:184`). So it FALSE-POSITIVES on the legitimate slot-text-only button pattern
  (which `Button.ts:16-21` itself recommends). The §41 probe used the same `textContent` heuristic and inherited
  the same error. The *real* name flattens through the slots fine; the cheap proxies don't see it.

### 43.2 What this invalidates
- **§41 "2 real nameless controls" — WRONG.** They are correctly named; there is no user-facing a11y defect.
- **§40/§42 "build a 559-based render-truth closure" — DO NOT BUILD.** Gating on the 559 signal would gate on
  false positives. The render-truth a11y-name detector the design wanted **already exists and is correct**:
  **axe-core's `button-name`/control-name rules**, already run by `ui_measure`/`ui-a11y-gate`. The intended
  capability was already present; §40/§42 mistook a measurement artifact for a missing capability.
- **§40's *principle* (source-truth ≠ render-truth) still stands** — but it now has **no proven violation**, so
  per this tempdoc's own discipline (build only the rung a real violation stands on) it is **recognize-not-build**.
  The one alleged violation was the measurement's own bug, not the product's.

### 43.3 What was shipped instead (the proportionate, in-scope fix)
A measurement-trust fix, not a feature: **axe is now the single render-truth a11y-name authority in the
harness, and the unreliable `[jf-control]` self-check no longer counts as a real defect.**
- `ui_measure._classify_console` gained a **`framework-selfcheck`** bucket: `[jf-control] no accessible name`
  is classified informational (counted in `console_env`), **excluded from `console_real`** — because axe is
  authoritative and these are false-positive-prone. A genuinely nameless control still surfaces via axe's
  `button-name` in `axe[]`, so no coverage is lost (verified: axe still flags the real `select-name`).
- **Before/after (settings `--fixtures`):** `console_real` **2 → 0** (the 2 phantoms now bucket as
  `framework-selfcheck`); `axe[]` = `['select-name']`, **no `button-name`** (buttons stay named); +unit test
  pinning the new bucket (`test_ui_measure.py`). This un-pollutes the `console_real` trust signal the §11/§12
  thesis rests on — the **§33 trust-pollution class, in a11y guise** (a non-defect masquerading as a real one).

### 43.4 Logged as product follow-up (out of 615 tooling scope)
`jf-control`'s self-check false-positives on the doc-recommended slot-text-only `jf-button` pattern. Product
options (logged to `observations.md`, not done here): give the 2 buttons a `label` (the `Revoke` pattern at
`SettingsSurface.ts:2160`; WCAG-2.5.3-clean since label == visible text) to silence it, or refine the
self-check to account for slotted content. It is dev-console noise, not an accessibility defect.

### 43.5 Lesson
The `console_real`/559 signal is a **framework self-check, not ground truth**; axe (the real accname algorithm)
is the authority for "is this control named." Two tiers disagreeing is the signal — and the cheaper tier
(`textContent`) was wrong. §41/§42 trusted it; §43 verified it. The same naive-`textContent` heuristic appears
in the §41 probe and `Control.ts` — both should defer to axe/accname for any accessible-name judgment.

### 43.6 Independent review (verdict: PARTIAL — reversal confirmed, rationale tightened)
A second-agent adversarial review (reviewer ≠ implementer) **confirmed** the core reversal — the buttons are
correctly named and the 559 signal is a false positive for the nested-jf-button pattern (mechanism verified
against `Button.ts`/`Control.ts`; axe's `button-name` is a *negative* rule that emits a violation when it
cannot name a reached element, and the app's Lit shadow roots are open + axe-traversable, so "no button-name
violation" genuinely confirms a name, not a silent miss). But it flagged a real **overreach** in the committed
rationale: "axe is THE single render-truth a11y-name authority / no coverage lost" is too strong — axe's reach
is bounded by *captured-surface × captured-state × visibility*, whereas the (unreliable) 559 self-check ran on
*every* control universally. The honest justification, now reflected in the `_classify_console` comment + test:
**we demote the signal because it is false-positive-prone and noisy, not because coverage is proven complete;**
the real backstop for genuinely-nameless controls is the **build-time `controls-a11y` gate** (statically forbids
a nameless top-level `<jf-control>`) plus axe on captured surfaces. **Acknowledged residual** (review): a
nameless *interpolated* control on an *un-captured* surface/state is caught by neither — but it was only ever
"caught" by this same false-positive-prone self-check, so the right fix is strengthening the static gate / axe
coverage, not trusting a noisy signal. Durable-evidence gap the review named (recorded, not built): the
accname-flatten check that proves the buttons are named is *ephemeral* (a one-off probe), not an in-tree test;
a Vitest `computeAccessibleName` assertion on a mounted `jf-button` would make it `audit-without-test`-clean.
