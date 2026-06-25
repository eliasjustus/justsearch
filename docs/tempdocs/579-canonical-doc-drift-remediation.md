---
title: Canonical Documentation Drift — Audit & Remediation Plan
type: tempdocs
status: active
created: 2026-06-13
updated: 2026-06-13
---

> NOTE: Noncanonical doc (notes/ideas). May drift. Verify against docs/explanation + docs/reference + code.

# 579 — Canonical Documentation Drift: Audit & Remediation Plan

## Purpose

A drift-class investigation + remediation spec. The starting signal was a git-history
analysis (code churn vs canonical-doc staleness); a verification pass confirmed the canonical
corpus **contradicts itself and the code** on the single most load-bearing architectural
fact — the UI framework. This doc:

1. Audits the canonical corpus (`docs/explanation/`, `docs/reference/`, `docs/how-to/`,
   `docs/decisions/`) against current code,
2. Separates **real drift** from **false positives** (every claim verified against `main` +
   source, not trusted from prose), and
3. Specifies, per doc, how it should be updated/rewritten.

This is a **plan**, not yet an implementation. Re-verify before acting if `main` has moved.

## Root cause (the headline)

Virtually all confirmed drift traces to **one event: the React → Lit frontend rewrite**
(the `421` kernel line → tempdocs 549–577; ADR-0032 graduated to canonical 2026-06-09). The
rewrite created/updated the *new* kernel docs (`27-frontend-presentation-kernel.md`,
`reference/ui/frontend-kernel/*`, ADR-0032/0033) but **did not back-propagate** to the
*older* architecture, how-to, and behavioral docs. A second, independent event — the chat
model swap (`Qwen3VL-8B-Thinking` → `Qwen3.5-9B`) — updated the runtime registry but not
`model-inventory.md`.

The deeper, systemic finding: the doc machinery has generation (`llmstxt-generate`,
`skills-sync`) and a canonical→noncanonical drift guard (`verify-canonical-doc-links.mjs`
rejects canonical docs that cite tempdocs), but **no guard that catches canonical-vs-CODE
drift**. A canonical doc can assert "React" forever and no gate fires. See §Systemic.

## Method

1. **Rank drift potential** = code churn (commits/area, 90d) ÷ doc staleness (last-commit date).
2. **Verify each candidate against code** — read the claim, confirm/refute against source.
   The churn gap alone produces false positives (`model-inventory` ONNX section, `08-observability`).
3. **Classify**: 🔴 real & high · 🟠 real & partial · 🟡 real & low · 🟢 not drift · ⚪ unverified.

### Churn-vs-staleness ranking (starting signal)

| Code area | 90-day commits | Governing canonical doc(s) | Doc last touched |
|---|---|---|---|
| `ui-web` (frontend) | 4,245 | `10-ui-ux-design.md` | 2026-02-10 |
| `ui-web` | 4,245 | `search-ui-behavior.md`, `secondary-views-behavior.md` | 2026-04-07 |
| `app-services` (search) | 1,706 | `23-search-pipeline-overview.md` | 2026-05-28 |
| `worker-services` | 820 | `03-knowledge-server.md`, `04-storage-engine.md` | 2026-05-28 |
| `app-inference` / models | 165 | `model-inventory.md` | 2026-04-08 |

The frontend dominated; verification confirmed the gap there is real and concentrated, while
the search/storage/observability docs the gap *also* flagged turned out accurate.

## Verified findings

Each entry: claim → code evidence → verdict → severity. Code truth for the frontend baseline:
`modules/ui-web/src` has **0 `.tsx`, 0 `react` imports, 146 `lit` imports, 775 `.ts`**; layout
lives in `modules/ui-web/src/shell-v0/`; ADR-0032 (accepted, 2026-06-09) = "the React stack was retired."

### 🔴 F1 — "React" asserted as the live frontend across the canonical set (CRITICAL, widest blast radius)

Docs that assert React as *current* (drift):

| Doc:line | Claim | Reality |
|---|---|---|
| `explanation/01-system-overview.md:24` | `React[Frontend]` (architecture diagram) | Lit web components |
| `explanation/01-system-overview.md:41` | `modules/ui-web` "(React frontend)" | Lit |
| `explanation/19-module-architecture.md:59` | `ui-web \| React frontend` | Lit |
| `explanation/07-ui-host-architecture.md` (frontmatter) | "React structure" | body line 25 already says "Lit web components" — **internally contradictory** |
| `how-to/develop-ui.md:124` | "The frontend is a React SPA served by Vite" | Lit SPA |
| `how-to/use-ui.md:204,226,254` | "React controlled components", "Frontend (React/Vite) hot-reloads" | Lit reactive controllers |

`01-system-overview.md` is the **first doc agents read** and it is factually wrong about the
entire UI stack. **Not drift** (correctly excluded): ADR-0012 (marked `Superseded`),
ADR-0032/0033 (the migration ADRs — they name React as the *retired* stack).

### 🔴 F2 — `search-ui-behavior.md` & `secondary-views-behavior.md` describe a retired React component tree (CRITICAL — near-total rewrite, not line edits)

These behavioral specs are written entirely against React artifacts that no longer exist:

- **`search-ui-behavior.md`**: `Stage.tsx` (:49), `ResultRow.tsx`, `VirtualResultList`,
  `LaunchpadGrid`, `ModeIndicator`, hooks `useSuggest`/`useSearchHistory`/`useMotionConfig`
  (:89,102,260,291), `useSearchStore`. None exist. Real surfaces: `shell-v0/views/SearchSurface.ts`,
  renderers under `shell-v0/renderers/`.
- **`secondary-views-behavior.md`**: `ActivityRail.tsx`, `LibraryView.tsx`, `BrainView.tsx`,
  `HealthView.tsx`, `SettingsView.tsx`, `HelpView.tsx`, hooks `useStartupProgress`/`useSettings`,
  Zustand stores, `startTransition()`. None exist. Real surfaces: `*Surface.ts` in `shell-v0/views/`.

Verdict: REAL DRIFT, structural. The *behaviors* may still hold (unverified per-claim — they
require running the UI), but every implementation anchor is wrong. These cannot be patched
line-by-line; they must be re-derived from `shell-v0`.

### 🟠 F3 — `10-ui-ux-design.md`: `ZoneGrid` phantom + zone-naming mismatch (HIGH; concept survives)

- ":25 — layout managed by the `ZoneGrid` component." `ZoneGrid` does **not** exist; the only
  reference in `ui-web/src` is a comment in `styles/tokens.css:536` ("`<ZoneGrid>` component; no
  current consumer"). Layout is now declarative via `shell-v0/layout/LayoutManifest.ts`.
- The A–E zone *letters* (Zone A Global Command … Zone E Status Deck) don't map to code's zone
  names (`rail`, `stage`, `statusBar`).
- **However** the 5-zone *conceptual model* survives (Activity Rail / Stage / Inspector / Status
  Deck are real in `shell-v0`, 465 references). So: concept accurate, named artifact + letter
  scheme + React framing stale. Verdict: REAL DRIFT on specifics; keep the model, re-anchor to
  `LayoutManifest`.

### 🔴 F4 — `model-inventory.md` chat/VLM section drifted (HIGH) — corrects a Phase-1 false-negative

The first pass spot-checked the ONNX/SPLADE section and (correctly) found it accurate, then
wrongly generalized "model-inventory is fine." Deeper verification:

| Doc:line | Claim | Reality (registry v2 + disk) |
|---|---|---|
| `:177` | Chat = `Qwen3VL-8B-Thinking-Q4_K_M.gguf`, id `Qwen/Qwen3-VL-8B-Thinking-GGUF` | `Qwen_Qwen3.5-9B-Q4_K_M.gguf` (`model-registry.v2.json:224`, on disk `models/Qwen_Qwen3.5-9B-Q4_K_M.gguf`) |
| `:202` | Vision proj = `mmproj-Qwen3VL-8B-Thinking-F16.gguf` | `mmproj-F16.gguf` (`model-registry.v2.json:234`, on disk) |
| `:173` | "defined in `model-registry.v1.json`" | only `model-registry.v2.json` exists |
| `:322-326` | a self-maintained "drift table" tracking registry vs scripts | itself stale — registry has since moved to Qwen3.5-9B |

The doc's **ONNX embedding + SPLADE sections remain accurate** (gte-multilingual-base,
embeddinggemma-300m, naver-splade-v3, ner/reranker/citation-scorer all present on disk). So this
is a **partial rewrite** (chat/VLM block + registry path + drift table), not a full one.

### 🟡 F5 — `02-process-coordination.md`: MMF layout table omits `RELOAD_SIGNAL` (LOW)

- Table (:27-38) marks offset `29-63` as reserved. `MmfWorkerSignalLayoutV1.java:19,42` defines
  `OFFSET_RELOAD_SIGNAL = 29` (dev hot-reload byte, written by Gradle, read by Worker). Verdict:
  REAL DRIFT, minor. Add the row, shift reserved to `30-63`.

### 🟡 F6 — ADR-0019 manifest not implemented as decided (LOW; already disclosed)

- ADR-0019 decides `cpu=model.onnx (FP32)`, `gpu=model_fp16.onnx`. The shipped
  `models/onnx/gte-multilingual-base/model_manifest.json` sets `cpu=model_fp16.onnx`. This is a
  **code/decision gap**, but `model-inventory.md:82` *already* documents it as a "Known manifest
  bug." So it is disclosed drift, not silent. Verdict: not a doc-remediation item — leave the
  disclosure, track the code fix separately (it's a real FP16-on-CPU perf bug per the doc).

## Verified NOT drift (false positives from the churn signal — do not touch)

- `01-system-overview.md` process model + entry points (`HeadlessApp`, `IndexerWorker`) — accurate.
- `06-configuration-ssot.md` (`EnvRegistry`/`ConfigKey`/`ResolvedConfigBuilder`, all ordinals) — accurate.
- `08-observability.md` — accurate; correctly reflects ADR-0027 retiring the legacy `Telemetry.*` surface.
- `09-testing-strategy.md` — named test classes exist; tiers accurate.
- `19-module-architecture.md` module table vs `settings.gradle.kts` — accurate **except** the one "React frontend" cell (F1).
- `api-contract-map.md` endpoints — present; no resurrected `/api/search` or `/api/settings`.
- `model-inventory.md` ONNX embedding + SPLADE sections — accurate (F4 scope is chat/VLM only).
- ADRs **0014** (pipeline-schema gone), **0017** (`app-ai`/`ai-worker` gone), **0018** (Qwen3.5 consistent) executed correctly; superseded ADRs (0004/0005/0009/0012/0040) correctly marked; README index consistent (42 ADRs).

## Remediation plan

Ordered by effort, smallest first. Each frontend edit triggers `ui-shot` / docs-regen hints —
run `node scripts/docs/llmstxt-generate.mjs` + `node scripts/docs/skills-sync.mjs` after, and
re-run `verify-canonical-doc-links.mjs`.

### Tier 1 — Targeted line edits (low risk, high value)

| Doc | Edit |
|---|---|
| `explanation/01-system-overview.md` | `:24` diagram node `React[Frontend]` → `Lit[Frontend]`; `:41` "(React frontend)" → "(Lit web-components frontend)". |
| `explanation/19-module-architecture.md` | `:59` "React frontend" → "Lit web-components frontend (non-Gradle project)". |
| `explanation/07-ui-host-architecture.md` | frontmatter `description` "React structure" → "Lit shell-v0 structure"; reconcile body so no React framing remains. |
| `explanation/02-process-coordination.md` | `:27-38` MMF table: add `29 \| 1 byte \| Reload Signal (dev) \| Gradle \| Worker`; change reserved to `30-63 \| 34 bytes`. |
| `reference/model-inventory.md` | Rewrite the Chat (`:177`) + Vision Projection (`:202`) blocks to `Qwen_Qwen3.5-9B-Q4_K_M.gguf` / `mmproj-F16.gguf`; fix `:173` `v1.json`→`v2.json`; refresh/retire the `:322-326` drift table. Leave ONNX/SPLADE sections untouched. |

### Tier 2 — Section rewrites (moderate)

| Doc | Edit |
|---|---|
| `how-to/develop-ui.md` | Rewrite the bootstrap (`:37` `main.jsx`/MSW), "React SPA" (`:124`), and Stores/Hooks (`:132-135` `src/stores` Zustand, `src/hooks`) sections — none of those dirs/patterns exist. Re-anchor to the Lit entry point, reactive controllers, and the actual MSW wiring. |
| `how-to/use-ui.md` | Replace the React-specific troubleshooting (`:204` controlled-component event quirk, `:226` "React-rendered content", `:254` "React/Vite") with the Lit-equivalent behavior. |
| `explanation/10-ui-ux-design.md` | Keep the 5-zone *model*; remove/replace `ZoneGrid` (`:25`) with `shell-v0/layout/LayoutManifest.ts`; reconcile the A–E letters with the code's `rail`/`stage`/`statusBar` names (either map them explicitly or adopt the code names). |

### Tier 3 — Near-total rewrites (high effort — candidate for a dedicated follow-up)

| Doc | Edit |
|---|---|
| `reference/search-ui-behavior.md` | Re-derive from `shell-v0/views/SearchSurface.ts` + `shell-v0/renderers/`. Every React component/hook anchor (F2) must be replaced. Behaviors should be re-verified live, not assumed. |
| `reference/secondary-views-behavior.md` | Re-derive from `shell-v0/views/*Surface.ts`. Same treatment. |

These two are large enough that they may warrant their own tempdoc/slice rather than riding
this one. Flagged as an open question below.

### Tier 4 — Systemic (prevent recurrence)

The drift wasn't caught because **no mechanism checks canonical-doc claims against code**.
Options, cheapest first:
1. **A cheap keyword lint**: no canonical doc *outside* `decisions/0012|0032|0033` may assert
   "React"/"JSX"/"Zustand"/".tsx" as current. This is a near-free gate that would have caught F1+F2.
   (prose-tier today; could be a `scripts/ci/check-*` gate — see `.claude/rules/tier-register.md`.)
2. **A doc-audit cadence**: the `/doc-audit` skill already exists; the gap is it isn't run on a
   trigger. Consider running it after any tempdoc that graduates an ADR (ADR-0032 graduating
   should have scheduled a back-propagation sweep).
3. **Frontend behavioral specs as projections**: `search-ui-behavior` / `secondary-views-behavior`
   drift because they hand-mirror component names. The repo's own "projection vs fork" discipline
   (governance registers) suggests these could be projected from the `shell-v0` surface catalog
   rather than hand-authored — out of scope here, but the right long-term shape.

## Adjacent (non-canonical) fixes worth bundling

Not canonical docs, but they repeat the F1 error and mislead onboarding:
- `CLAUDE.md` Architecture table / "React frontend" framing.
- `docs/llms.txt` hand-authored summary (if it names React).
- The `/start` skill onboarding summary ("Tauri shell + React frontend").

## Open questions (need user/architect decision)

1. **Scope of Tier 3.** Do `search-ui-behavior.md` + `secondary-views-behavior.md` get rewritten
   inside this tempdoc, or split into a dedicated follow-up slice? (They're ~80% of the effort and
   need live behavioral re-verification.)
2. **Zone naming (F3).** Should `10-ui-ux-design.md` keep the A–E letter scheme (mapping it to
   code names) or adopt the code's `rail`/`stage`/`statusBar` vocabulary as canonical?
3. **Tier 4 gate.** Is the cheap "no-React-in-canonical" lint worth adding now, or is the
   `/doc-audit` cadence preferred? (Per tier-register, a gate is ~100% vs prose ~70%.)
4. **ADR-0019 manifest bug (F6).** Doc-disclosed but unfixed — confirm it's tracked as a code
   issue elsewhere, else promote to `docs/reference/issues/`.

## Verification log

- Frontend baseline (0 .tsx / 0 react / 146 lit / shell-v0) — confirmed via grep+find on `modules/ui-web/src`.
- F1/F2/F3 — confirmed by frontend-cluster audit + direct grep of cited `file:line`.
- F4 — independently re-verified (not just trusted from subagent): `model-inventory.md:173,177,202`
  vs `model-registry.v2.json:220,224,234` vs `ls models/*.gguf`.
- F5 — `MmfWorkerSignalLayoutV1.java:19,42`.
- F6 — `model_manifest.json` cpu key + `model-inventory.md:82` disclosure.
- NOT-drift set — confirmed by architecture + ADR cluster audits against `settings.gradle.kts` and source.

## Implementation outcome (2026-06-13)

All four user-approved tiers were implemented on `main` (uncommitted pending review).

**Tier 1 (line edits):** `01-system-overview.md` (mermaid + module line), `19-module-architecture.md`,
`07-ui-host-architecture.md` (frontmatter), `02-process-coordination.md` (MMF `RELOAD_SIGNAL@29`),
`model-inventory.md` (chat→`Qwen_Qwen3.5-9B`, vision→`mmproj-F16.gguf`, registry `v1`→`v2`, drift-table refresh).

**Tier 2 (section rewrites):** `develop-ui.md` (MSW/bootstrap + Lit architecture overview, Zustand/hooks
removed), `use-ui.md` (React troubleshooting → Lit), `10-ui-ux-design.md` (`ZoneGrid`→`LayoutManifest.ts`,
`rail`/`stage`/`statusBar` mapping, `Toggle.tsx`→`ToggleSwitchRenderer.ts`).

**Tier 3 (behavioral specs — STRUCTURE-ONLY pass):** `search-ui-behavior.md` + `secondary-views-behavior.md`
re-anchored to `shell-v0` surfaces, React-only mechanics (`useEffect`, Zustand) stripped, post-rewrite
caveat banner added. **Deferred:** a live re-verification pass of each behavioral claim against the running
Lit UI (needs the dev stack) — tracked as the follow-up below.

**Tier 4 (prevention gate):** new `scripts/docs/check-frontend-stack-claims.mjs` (context-aware, exempts
historical framing + `reference/issues/`), wired into `.github/workflows/docs-lint.yml`, registered as
CLAUDE.md Hard Invariant #5 (`frontend-stack-is-lit`, `lint` tier) + tier-register row 33 + a
`new-rule-registered` changeset. Gate is **green** across all canonical docs.

**Spillover the lint caught beyond the original 4-cluster audit (now fixed):** `06-configuration-ssot.md`,
`08-observability.md`, `12-desktop-installer-and-sandbox-setup.md`, `13-ai-setup-and-verification.md`,
`module-deps.mjs` generator (+ regenerated doc), `agent-guide.md`, `slice-execution.md`,
`ui-user-readiness.md`, `library-indexing-activity-panel.md`. **Adjacent:** `CLAUDE.md` (×2). `llms.txt`
header and the `/start` skill were already clean.

**Corrections to the original audit:** F4 — `model-inventory.md` was wrongly cleared in Phase 1 (the ONNX
section was accurate but the chat/VLM section had drifted). The prevention lint proved its worth immediately
by surfacing ~8 drifted docs the static audit missed.

**Out-of-scope findings logged to `observations.md`:** (1) inference model-id mismatch (scripts reference
`Qwen3VL-8B-Instruct`, registry/disk use `Qwen3.5-9B`); (2) MSW browser-mock activation appears unwired;
(3) canonical link rot in ADRs 0038/0039 (`docs/421-*`/`docs/426-*` paths gone after 421-folder retirement).

**Tier-3 live pass (2026-06-13, ui-shot demo mode):** captured the actual rendered Lit surfaces.
Verified live — the search shell (Zone A command input, Zone B activity rail, Zone C stage empty-state
+ Modified date filters, Zone E status deck) and all five secondary surfaces (Library, Brain, Health,
Settings, Help) render and match the specs; both behavioral-spec banners updated to record this.
**Still open:** result-list / cursor-selection / Inspector-streaming interactions could not be exercised
in demo mode (results don't populate) — those need a real-backend ui-shot pass (the `search-results` /
`inspector-open` chain steps time out without populated results; a known harness limitation, not doc drift).

**Verification:** `check-frontend-stack-claims` OK (94 files); `prose-tier-register` gate pass;
`check-workflow-triggers` OK; `module-deps --check-canonical` OK; `llmstxt-generate`/`skills-sync`
regenerated. (Pre-existing, not introduced here: `verify-canonical-doc-links` fails on the 0038/0039 link
rot above; `tempdoc-status-check` fails on 575/576's non-canonical status values.)

## Meta-analysis: does the canonical-doc system earn its keep? (2026-06-13)

This remediation surfaced a larger question than the React→Lit drift: *if a whole tier of
docs can be wrong for months and nobody notices, what is that tier actually for?* The
investigation below is recorded as design history, not a closure claim — it ends in a theory
and four candidate directions, not a decision.

### Measured — agents barely read the canonical corpus

Classifying every `Read` file-path across this project's Claude Code transcripts
(`~/.claude/projects/F--JustSearch`, **63 sessions, 13,576 reads**):

| Read target | Count | Share |
|---|---:|---:|
| **Canonical docs** (`explanation`+`reference`+`how-to`+`decisions`) | **19** | **0.1%** |
| Tempdocs (noncanonical, "allowed to drift") | 224 | 1.6% |
| `observations.md` | 6 | — |
| **Code files** | 9,885 | **72.8%** |

Agents read the *noncanonical, drift-permitted* tempdocs **~12× more** than the maintained
canonical corpus. `explanation/` got **2 reads in 63 sessions**. Two corroborating structural
facts: (a) only **~9 of ~146** canonical docs have a delivery channel (wired into a skill via
`skills-sync`); the rest are reachable only if an agent chooses to navigate `llms.txt`. (b) The
React→Lit drift surviving months *is itself a measurement*: a doc tier in anyone's critical path
would have self-corrected on first wrong-info contact.

### Corroborated — external research, last ~3 months (Mar–May 2026)

An active 2026 arXiv cluster independently reproduces both the observation and the nuance:

- **Code-first is the recommended default, not a defect.** "MatClaw" ([2604.02688](https://arxiv.org/abs/2604.02688))
  — "RAG over **source code** is essential for sustained correctness in code-first agents."
- **Value lives in out-of-code knowledge only.** "Brief" / Context-Augmented Code Generation
  ([2605.08112](https://arxiv.org/abs/2605.08112)) measured Claude Code at **100% compliance on
  decisions visible in the codebase, 0–33% on decisions requiring out-of-code context** (+49pp
  when that context was supplied). This is a near-verbatim external replication of our
  73%-code / 0.1%-canonical split.
- **Generic context files that restate code are net-negative.** ETH "Evaluating AGENTS.md"
  ([2602.11988](https://arxiv.org/abs/2602.11988), Feb 2026) — repository context files show
  **no task-success improvement and >20% higher inference cost**; recommend "describe only
  minimal requirements." (Feb = ~4 months, just outside the strict 3-month window; flagged.)
- **Stale docs/knowledge measurably degrade agents.** "When LLMs Lag Behind" ([2604.09515](https://arxiv.org/abs/2604.09515))
  — the "context-memory conflict" our React→Lit case is an instance of. Justifies *mechanical*
  drift-prevention over hoping for self-correction.
- **The field is actively redesigning the delivery surface** — `GROUNDING.md` ([2604.21744](https://arxiv.org/abs/2604.21744)),
  agent-memory survey ([2603.07670](https://arxiv.org/abs/2603.07670)).

### Theory — the system optimizes the wrong axis

The doc system's primary axis is **canonical (must not drift) vs noncanonical (may drift)**, and
maintenance investment is allocated along it: all ~146 canonical docs get uniform "must not drift"
enforcement. But the evidence says **value is allocated along two *different* axes the system
ignores**:

1. **Code-recoverable?** — can an agent reconstruct this from `grep`+read of source? (the ETH/Brief result)
2. **Delivered?** — is the doc wired into a consumption channel (CLAUDE.md / a skill / a `llms.txt`
   path agents actually fetch), or orphaned behind manual navigation?

Place the corpus on that 2×2 and the misallocation is visible:

| | **Code-recoverable** | **Out-of-code (the "why", trade-offs, cross-cutting)** |
|---|---|---|
| **Delivered** (CLAUDE.md / skill) | Low value, but cheap to keep honest; some redundancy ok | **The crown jewels** — protect hardest, this is where agents fail without it |
| **Orphaned** (llms.txt-only) | **Liability zone** — high maintenance, ~0 reads, and *net-negative if ever loaded* (ETH) | Valuable knowledge that is **mis-delivered** — should be wired to a channel |

The current system applies one treatment across all four quadrants. It **over-invests** in the
code-recoverable+orphaned quadrant (most narrative `explanation/` docs that restate code — the
quadrant the literature says is net-negative when consumed) and **under-delivers** the
out-of-code+orphaned quadrant (the ADRs and architecture rationale that are exactly what Brief
showed agents miss). "Must not drift" conflates protecting *active consumption* with protecting a
*hypothetical future reader*, at identical cost but wildly different ROI.

The repo has already found the right mechanism elsewhere and just hasn't generalized it: the docs
that *cannot* drift are the **generated** ones — `llms.txt` (from frontmatter), `module-deps.md`
(from `settings.gradle.kts`), the 564 wire-schema types, the governance registers. "Projection,
not fork" applied to prose. The theory's end-state: bifurcate the corpus into **(a) generated
projections** of code/registers (drift-impossible, zero cost-of-being-wrong) and **(b) a small,
hand-authored, *delivered* core of genuinely out-of-code knowledge** (the why / decisions /
cross-cutting architecture) — and let the hand-authored, code-restating, orphaned middle either be
generated or *explicitly demoted to a human/archive tier with relaxed drift guarantees*.

A missing feedback loop makes this worse: the repo already collects the read-frequency telemetry
(`scripts/agent-analytics/context-attribution.mjs`) but nothing consumes it for doc lifecycle. A
doc read 0× in 63 sessions, protected by no gate, cited by no skill is a candidate for
generate / wire-to-a-channel / archive — yet canonical docs are currently immortal once created.

### Honest self-critique of *this* remediation

This pass gated **all** canonical docs uniformly for the React/Lit pattern — the same uniform-
investment move the theory critiques. The literature reframes *why* the gate is still worth it: not
"agents will now read correct docs" (they won't read them much), but (a) it protects the *delivered*
subset (`01-system-overview` is the `llms.txt` entry agents do hit; `07`/`12`/`19` feed skills),
(b) it prevents the net-negative case where a *wrong* doc gets loaded and degrades the agent
(ETH/conflict papers), and (c) human onboarding. The cost-proportionate version would have gated the
delivered/out-of-code core hard and *generated or relaxed* the orphaned code-restating tail. The gate
is necessary **because** usage is low — low usage cannot self-correct drift — which is the inversion
worth keeping: *low readership is the argument for mechanical drift-prevention, not against
maintaining the docs.*

### What this does NOT claim

- The telemetry is **agent-only**; humans (onboarding, "why is it this way" archaeology) are an
  unmeasured consumer, and ADRs/explanation earn their keep there even at ~0 agent reads.
- "Generate everything" is wrong: the highest-value docs (decisions, trade-offs, rejected
  alternatives) are precisely the *non*-code-derivable ones the field shows agents miss — those must
  be **protected and delivered**, not generated away.
- This is a theory with four candidate directions (consumption-tiering; convert code-restating prose
  to generated projections; wire valuable orphans into skills/CLAUDE.md; make read-frequency a
  standing relevance signal) — **not** a decision. Each is a fresh call for the maintainer.

## Correct long-term design — the Documentation Projection Kernel (2026-06-13)

> Genre: **design-theory** (per 557/559/567) — the correct end-state at the bar the category sets;
> feasibility, phasing, and sequencing deliberately disregarded; major rewrites in scope. **Not a plan.**

### The one root cause

Every concrete item in this tempdoc is the **same defect**: a fact whose single source of truth is
code or a register was **hand-authored as prose, so it forked**. React→Lit (the whole corpus), the
`model-inventory` chat/VLM block (a fork of `model-registry.v2.json`), the deferred behavioral
re-verification, the logged model-id / MSW / link-rot findings — none are "docs hygiene." They are the
repo's already-named **representation-fork class (553)** appearing in the one substrate not yet under
projection discipline. Drift is the *symptom*; hand-authoring a derivable fact is the *cause*.

### The principle the rest of the repo already lives by — not yet applied to docs

The presentation kernel (27) prevents drift with **Collapse > Generate > Gate** (prose ~70% is the
floor, used only when the higher tiers can't apply). The wire-contract substrate (564), `module-deps`,
`llms.txt`, and the governance registers are all *"projection, not fork."* **Documentation is the last
major substrate sitting almost entirely at the weakest tier** — hand-authored prose with two generators
and a drift-police gate (the one I just added) bolted on. The correct end-state brings docs under the
same ladder the repo already enforces everywhere else.

### Type every doc by knowledge-origin; origin dictates the mechanism

Replace the single *canonical / noncanonical* axis with a declared `origin` (a register +
frontmatter field, gated like every other repo register):

- **DERIVED** — a projection of one code/register/schema source (module lists, API/endpoint maps,
  config matrices, model inventory, the frontend stack fact, the zone/layout description, the
  search-pipeline stage list, the *behavioral surface inventory*). **Generated; hand-authoring it is a
  build error** (the `wire-type-single-authority` pattern). Drift-impossible by construction. Collapse/Generate tier.
- **DECISIONAL** — the *why*: ADRs, trade-offs, rejected alternatives, cross-cutting intent.
  **Not code-recoverable — the crown jewels** (Brief 2605.08112: exactly where agents fail without it).
  Hand-authored, peer-authoritative over a domain code cannot express, **protected and *delivered***.
  Correctness obligation: linked to the code/gate that enforces the decision, so an ADR contradicting its
  own enforcing gate is *detectable* (the `register-guard-resolution` meta-gate pattern, 576).
- **PROCEDURAL** — how-to / runbooks. **Executable where possible** — the command *is* the doc, run in
  CI, so it can't drift; the residual tacit part is small and DECISIONAL.
- **EPHEMERAL** — tempdocs, observations. Already correct; unchanged.

### Delivery is declared and gated; an orphaned agent-facing doc is unrepresentable

Every DERIVED/DECISIONAL/PROCEDURAL doc meant for agents **declares a retrieval channel** (a skill, a
CLAUDE.md index line, or a just-in-time trigger in frontmatter). A gate flags any doc tagged
agent-facing with no channel, and forces every doc to be either *delivered-to-agents* or *explicitly
human/archive*. This **deletes the "orphaned valuable doc" quadrant by construction** and matches the
context-engineering guidance the field converged on (lightweight always-loaded index + just-in-time
fetch). The skills system is the proven channel; the design generalizes it from 9 hardcoded docs to a
declared property.

### The hierarchy, made honest

Code + registers + contract tests **are** the source of truth. DERIVED docs are projections of it.
DECISIONAL docs are a **peer authority over *intent*** — a domain code cannot express — and therefore
**do not go stale when code changes** (a decision record is not a description of current state). Drift
survives only in the quadrant this design abolishes: hand-authored prose describing current code, which
becomes a build error. The nominal "canonical docs are the source of truth" is replaced by the honest
one the repo already half-states ("verify against code").

### Docs get a lifecycle; immortality ends

The read-frequency telemetry the repo already collects (`context-attribution.mjs`) feeds a standing
**warn-only relevance report** (mirroring `reliability-budget`). A doc read 0× / gated by nothing /
delivered nowhere is forced through **generate · deliver · archive**. The repo's ratchet pattern applied
to dead-doc inventory; canonical docs lose their immortality.

### The proof obligation (the deepest principle)

By the repo's own tier philosophy, *"a human wrote it carefully"* is the weakest guarantee. The
end-state: **every canonical doc carries a machine-verifiable correctness obligation appropriate to its
origin** — DERIVED is correct by construction (generated), PROCEDURAL by execution (CI runs its
commands), DECISIONAL by enforcement-linkage (its decision maps to a gate/test). A doc whose only
guarantee is careful authorship becomes the rare, flagged exception — not the default for ~146 docs.

### The 579 items, reframed as instances of the one cause

- **React→Lit gate (Tier 4):** a *stopgap*. "Frontend stack" is a DERIVED fact (recoverable from
  `package.json`/imports). A generated one-line stack-fact makes the React claim **unrepresentable** and
  supersedes the keyword gate — *generation eliminates the class the gate only patrols.*
- **`model-inventory` fork (F4) + the model-id observation:** the doc is a hand-authored projection of
  `model-registry.v2.json` that forked. End-state: generated from the registry; the fork cannot recur.
- **Deferred Tier-3 behavioral re-verification:** the search/secondary-view specs are *mostly* DERIVED
  (what the surfaces do — recoverable from the 559/565 surface catalogs + component tests). The correct
  answer is **not** "manually re-verify behaviors forever" but "project the behavioral surface inventory
  from the surface registry the repo already owns," shrinking the hand-authored remainder to the
  genuinely-tacit UX *intent* (DECISIONAL).
- **Link rot (0038/0039), tempdoc-status violations:** generated-index / declared-vocabulary concerns —
  already the repo's wheelhouse; fold into the projection/gate layer.

### Honest limits

- The **derive/decide line is per-doc judgment** (the register-covers-only-declared-concepts limit, 553).
  A behavioral spec is a *split*, not wholesale generation.
- **Generators are a new failure surface** — a broken generator silently emits wrong docs; they need the
  `--check` verify pattern (`module-deps` already does).
- **DECISIONAL correctness still rests on human review of intent** — the design shrinks that surface to
  where it is irreducible; it does not eliminate it.
- **Humans remain an unmeasured consumer** — the archive tier exists precisely so low-agent-read ≠ delete.

### One-line end-state

Bring documentation under the *projection-not-fork* discipline the rest of the repo already enforces:
**generate what code knows, hand-author only what code can't, deliver what agents need, and let
read-frequency retire the rest** — so the drift class this tempdoc fixed by hand becomes structurally
*unrepresentable* rather than merely *forbidden*.

## Refinement — documentation is a behavioral protocol, not an artifact (supersedes the generation lead, 2026-06-13)

> Maintainer steer: *"generators are not going to work; this has to be done by making Claude agents
> behave differently toward the documentation."* This corrects the section above — generation drops
> from lead mechanism to a thin correctness floor. Where this disagrees with the Projection Kernel
> framing, **this wins** (dated-history rule).

### Why the generation lead was aimed at the wrong quadrant

Generation can only touch **DERIVED** facts — which are also the facts an agent **least needs a doc
for, because it can just read the code** (the telemetry: 73% code, 0.1% canonical). So generation works
hardest exactly where the payoff is smallest and cannot touch the out-of-code knowledge where all the
value is. The deeper reframe: **in a codebase operated almost entirely by Claude agents, documentation is
not a file tree — it is the *protocol* by which successive agents hand knowledge to each other.** Generators
produce inert artifacts; they do not change the protocol (consult / trust / write-back / retire). The
correct design optimizes the *protocol*, i.e. agent behavior — not the artifacts.

### The hard constraint that makes "behavioral" actionable

"Make agents behave differently" **cannot mean telling them to.** That experiment already ran: CLAUDE.md
is loaded every session and already frames canonical docs as the source of truth, and agents read them
**0.1%** of the time. By the repo's own law (prose ~70%, wired ~100%) an exhortation-based fix is the
weakest tier and the data says it has already failed. So *behavioral* here must mean **changing the
agent's environment and affordances** (hooks / skills / gates / telemetry) — the move the repo already
makes everywhere else — not changing its instructions.

### The model — the agent's relationship to docs, wired at each step

**Consult → Maintain → Retire**, each an existing substrate mechanism, none a paragraph:

1. **Consult — push, not pull.** Docs are pull today (navigate `llms.txt` by choice; agents don't). Flip
   to push: a `PreToolUse(Edit)` hook on a governed region surfaces the *one* decision doc that governs
   it — `ssot-hint`/`ui-shot-hint` generalized from procedures to DECISIONAL knowledge. The relevant ADR
   arrives at the moment of the edit, zero agent discretion. ~100%.
2. **Maintain — updating the doc is definition-of-done.** Fixes drift at the source: today an agent
   changes code and the doc rots (React→Lit). The same hook that surfaced the doc makes *updating it* part
   of the task, enforced the way the React gate now forces it. Low readership stops mattering because the
   doc self-heals on every change that touches its area.
3. **Retire — the agent is the relevance sensor.** Feed the read-frequency telemetry back: a doc no agent
   ever consults is surfaced for deletion. The agents' own (non-)behavior is the dead-doc signal.

> **Weight cut (2026-06-15).** An earlier four-step model had a **Weight** step ("tell the agent how
> much to trust a doc vs. verify the code"). Dropped as useless: its only mechanism is a smarter *label*
> — the weak ~70% prose tier the rest of this design rejects — and it dissolves into the other moves.
> Consult already states a doc's authority when it pushes it (fold the trust signal there); generation
> removes the drift-prone "description" docs entirely (nothing left to distrust). The only real residue —
> "is this decision still in force?" — is better handled as enforcement-linkage (an ADR tied to the
> gate/test enforcing it, so a reversed-but-unmarked decision is machine-detectable), not a trust rating.
> **Go-forward model is the three steps above.**

### What survives from the Projection Kernel section (demoted, not deleted)

- **The origin typing (DERIVED / DECISIONAL / PROCEDURAL / EPHEMERAL) stays** — but its job changes from
  "decide what to generate" to **"decide what to push and how much to trust it"** (step 2). It is now an
  input to behavior, not a generation trigger.
- **Generation survives only as a thin correctness floor.** Pushing a *wrong* doc is worse than pushing
  none — a stale pushed doc makes the agent confidently wrong (the "When LLMs Lag Behind" context-memory
  conflict). So the few DERIVED facts that the Consult step would surface must be kept honest by a small
  gate/generator. A floor under what gets pushed, not a pillar.

### The honest hard part

Push has a **context cost**, and dumping docs at the agent is the exact thing ETH ([2602.11988](https://arxiv.org/abs/2602.11988))
measured as net-negative (+20% cost, no success gain). So the behavioral system cannot be "show more
docs" — it must be **surgical: the right *one* doc, at the right moment, for the right edit.** That
precision is the real engineering, and it is where this design lives or dies. It is also genuinely
harder than writing a generator — so "behavioral, not generative" is a claim about *correct leverage*,
not about *less work*.

### One-line end-state (revised)

Treat documentation as a **hand-off protocol between agents**, enforced by the substrate, not by
instructions: **push the one right doc at the moment of the edit, tell the agent how much to trust it,
require it be updated as part of the change, and let the agents' own reading behavior retire the dead** —
with a thin generation/gate floor only to keep what gets pushed from being wrong.

## Remaining-work implementation outcome (2026-06-13)

Plan (`~/.claude/plans/…`, approved) executed across three phases; each extends an existing pattern.

**Phase A — bounded fixes (committed).**
- `verify-prerequisites.mjs` now reads the chat/VLM + mmproj filenames from the registry SSOT
  (`model-registry.v2.json` package `id=chat`) — verified it resolves to the shipped
  `Qwen_Qwen3.5-9B-Q4_K_M.gguf` / `mmproj-F16.gguf`. (`inference-model-id.txt` is a gitignored
  runtime artifact — left alone.)
- ADR 0038/0039: the dead `archive/source-tempdocs/{426,421}` Source-Evidence citations reworded to
  "retired tempdoc, see git history." **Surfaced** that this is systemic — ~30 mixed pre-existing
  `verify-canonical-doc-links` failures (the archive substring class across ADRs 0031-0037 + genuinely
  moved/removed docs). **In scope — remaining work** (also logged to `observations.md`); see §Still open.
- MSW dead code removed (`src/mocks/{browser,handlers}.ts` had zero live importers + the `dev:mock`
  script); `develop-ui.md` rewritten to document the real `?demo=true` URL-param mode. Kept
  `fixtures.mjs` (used by `fixtures.test.ts`). Verified: typecheck clean, fixtures 6/6, markdownlint
  (repo config) + frontend-stack lint green.

**Phase B — live search-UI verification (committed).** Brought up the real stack (backend + worker +
10-doc corpus + `Meta-Llama-3.1-8B` online) and drove the real browser. Live-confirmed the deferred
Tier-3 behaviors demo mode couldn't: populated results + highlighted snippets + facet filters + result
count/timing + retrieval-mode badge; row click opens the Inspector (Preview/Context/Answer/Ask) with
extracted preview; Ask streams a grounded RAG answer with inline `[n]` citations + source chip; citation
click jumps to Preview and highlights the grounding passage. `search-ui-behavior.md` banner updated to
record verified-vs-spot-unverified (keyboard cursor, multi-select, hover-cards remain spot-unverified).

**Phase C — behavioral pilot (committed; Consult + Retire built, Maintain deferred; Weight cut).**
- **Consult hook** (`scripts/agent-analytics/hooks/consult-doc-hint.mjs`): PreToolUse(Edit|Write) that
  pushes the governing decision-doc when editing `modules/ui-web/src/shell-v0/**` (→ ADR-0032 + the
  presentation kernel). Mirrors `docs-regen-hint.mjs`; honors the kill switch; surgical. Registered in
  the tracked `.claude/settings.local.json`. Self-tested (governed emits, non-governed + kill-switch
  silent).
- **Dead-doc "Retire" report** (`scripts/ci/report-doc-relevance.mjs`): mirrors
  `report-reliability-budget.mjs` (warn-only), reuses `telemetry-io`. First run: **45 dead of 145**
  canonical docs (read 0× AND skill-unwired) across 522 transcripts / 16,102 reads — the generate/
  deliver/archive candidate list (with the honest "humans unmeasured" caveat).

  **Retire operationalized (2026-06-15).** Triaging those 45 proved **read-frequency alone is too weak a
  death signal** — ADRs (human archival), issue logs / how-to / contributing (situational), generated
  docs (drift-proof), and the Consult hook's own target were all flagged but legitimately kept. Sharpened
  the report to be **origin-tiered + delivery-aware + frontmatter-status-aware** (REVIEW vs EXPECTED-0-read),
  collapsing 45 → ~19 review candidates. Spot-verified the only genuinely-questionable few (15-enterprise-
  policy, 16-gpu-booster-pack, future-knowledge-extraction): all `status: draft`/`redirect` forward-specs/
  redirect stubs — **keep, zero archives warranted.** The whole 45-flag pass yielded **no deletions**: the
  signal's real role is a periodic human/agent *review prompt*, not an auto-archiver. A Retire that actually
  drives archival needs **0-read AND staleness** (a doc whose code-subject churned away) — the read-frequency
  half is built; the staleness half remains theory (ties to origin-typing).

**Still open — remaining work (in scope):** _none — all three remaining items shipped this pass (below)._
The behavioral kernel is now **Consult ✅ → Maintain ✅ → Retire ✅** (Weight cut as useless 2026-06-15,
see §model note). Optional future polish only: widen the governed-region set beyond `shell-v0`; the
optional LLM contradiction check on Maintain (defer until false-nudge friction is observed); the
generation pillar of the Projection Kernel.

**Done this pass (2026-06-15):**
- ✅ **Maintain step** (behavioral kernel) — built + **live-validated**. `Stop`-hook (`maintain-doc-hint.mjs`):
  when you finish a turn having edited a governed region without touching its governing doc, it **blocks once
  per region per session** (`{"decision":"block"}`) to update the doc or say why not — the exit-side complement
  to Consult. Both read one shared map (`scripts/agent-analytics/lib/governed-regions.mjs`, extracted from the
  Consult `CONSULT_MAP` — also closes the deferred "promote the map" item). Safety: two-layer de-dupe
  (`stop_hook_active` within a turn + a per-session marker file across turns), narrow scope (shell-v0), escape
  hatch, kill switch, fail-open. Self-tested (block / silent-when-doc-touched / non-governed / loop-guard /
  kill-switch / cross-turn-dedupe); registered in `Stop[]`; documented in `hooks-reference.md`.
  **Live caught (2026-06-15):** the first build only had the `stop_hook_active` guard — which dedupes within
  one forced continuation but NOT across turns, so the cumulative transcript made it re-fire every turn-end.
  The live block during the implementation turn exposed this friction defect; fixed with the per-session marker.
  (Both Consult and Maintain fired correctly in a real session — Consult on the edits, Maintain at turn-end.)
- ✅ **Consult-step de-dupe — follow-up (2026-06-21).** The Maintain step got per-session-per-region
  de-dupe above; the **Consult hook never did** — an asymmetry that re-pushed the *same* governing doc on
  **every** edit in a region (ADR-0032 on each `shell-v0` edit, the REST-API recipe on each controller
  edit), surfaced as pure context waste in a real multi-edit session (tempdoc 622 implementation pass). The
  governing doc doesn't change between the 1st and the Nth edit in a region, so re-delivering it is the same
  "re-fire across [edits]" defect the Maintain marker fixed for turns. **Fix:** `consult-doc-hint.mjs` now
  mirrors the Maintain marker — a per-session `consult-nudged-<session>.json` records each region delivered,
  and each region's pointer is pushed **once per session** (record-before-emit; *delivery-first* on persist
  failure, so a missed write never silences the hook — the opposite bias to Maintain's friction-first skip).
  The Consult→Maintain pair now shares one once-per-region-per-session discipline. Validated: 1st edit emits /
  2nd silent / `{}`-stdin load-probe exits 0 / `hook-integrity` unaffected.
  **Convergence note:** the dedup itself shipped under **620** (commit `0f5c02887`, 2026-06-21) — a concurrent
  agent independently made the identical fix. The remaining hardening is the **compaction-safe complement**:
  both `consult-nudged-*` and `maintain-nudged-*` markers are now cleared on **PreCompact** (`compact-save.mjs`,
  beside the existing read-count / repeat-buffer resets), so when a compaction summary evicts a delivered
  doc-pointer the hooks **re-deliver** it on the next edit in the region — the markers should not outlive the
  context they were guarding against re-injecting.
- ✅ **Canonical link-rot debt** — all 54 `verify-canonical-doc-links` failures cleared: the
  `archive/source-tempdocs/` substring class fixed at root (tightened the linter's `extractDocsPathRefs`
  regex with a `(?<![\w-])` boundary so it stops matching `docs/` mid-path); the genuine canonical→tempdoc
  refs reworded to prose `(tempdoc NNN)` pointers; 3 missing-doc refs (`dag-runner-operations`,
  `benchmark-eval-contract`, `validate-performance`) reworded to existing targets. Linter green (146 files).
- ✅ **`nomic` prereq staleness** — `verify-prerequisites.mjs` nomic GGUF check → `required:false` (legacy
  fallback, not shipped, not on the search hot path). Now `[WARN] … (optional)`, not `[FAIL]`.

(Both were also tracked in `observations.md`.)
