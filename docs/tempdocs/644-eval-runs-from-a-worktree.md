---
title: "Worktree-safe stack-bound eval: complete tempdoc 283's resolved-asset contract so eval launched from an agent worktree runs under the engine set it declares. Today the eval launcher defaults asset resolution to the worktree's own (binary-less) models/, so the reranker (and per 283, SPLADE/embedding/GPU) silently turns off → wrong-but-plausible numbers → all live eval forced onto shared main. Design = (Axis 1) one default-correct, worktree→main asset resolver shared across all three launchers (converge the dev-runner/eval fork), + (Axis 2) a fail-closed instrument-integrity check that refuses to emit numbers when the realized engine set ≠ the intended one (data already exists in /api/status + run-evidence component_status_counts)."
type: tempdocs
status: implemented + live-verified (Axis 1 + Axis 2 + flatten_status fix; reranker turns on in a worktree, guard passes/refuses correctly against a real backend) — only full nDCG-equivalence numeric check deferred
created: 2026-06-24
updated: 2026-06-30
author: agent analysis — split out of tempdoc 635 §workflow-lessons #4 (the constraint that forces eval onto main); design phase 2026-06-30
related:
  - 283-worktree-gpu-runtime-path-unification     # the structural ancestor (done): named the root cause + established the resolved-asset/launcher contract seam this design completes for the reranker + the default
  - 635-contamination-resistant-eval-corpus       # where the constraint was found + recorded (workflow-lessons #4); a heavy live-eval consumer
  - 618-agent-developer-velocity-friction         # §2/§3: added the worktree→main models resolution for the DEV-runner launch path only (the working resolver to converge on)
  - ../../.claude/rules/branch-safety.md           # the worktree model this would make eval-compatible
---

> **Design settled 2026-06-30** (the STUB framing below is superseded by the **§Design** section).
> Origin: tempdoc 635's repeated need to run live eval, which it could only do on the shared `main`
> checkout. The supporting analysis (§Investigation, §Theorization) feeds the settled design; read
> **§Design — the settled long-term shape** for the conclusion.

# 644 — Make stack-bound eval run correctly from a worktree

## The idea
Parallel agents work in isolated git worktrees, but **stack-bound eval** (`corpus-fidelity`, `jseval run`
with cross-encoder + dense) effectively **cannot run in one today**. The harness auto-discovers the reranker
from the *worktree's own* repo root (`justsearch.repo.root → models/onnx/reranker`); a worktree does not carry
the LFS `models/` tree, so the reranker isn't found, the cross-encoder **silently turns off**, and the hybrid
numbers come out wrong (looking like a quality result, not a missing model). The idea: make the model /
reranker resolution **worktree-aware** — resolve from the **main checkout** (exactly as the dev-runner already
resolves `JUSTSEARCH_MODELS_DIR`) so an agent in a worktree gets the real default-on engine + reranker.

## The purpose / why it matters
- **It removes the forcing-function that pushes eval work onto shared `main`.** Because eval must run on main,
  eval-bearing tempdocs edit shared files on main, which is the *root* of the recurring multi-agent collision
  risk and per-commit coordination cost (635 §workflow-lessons #4/#5 — the `cli.py` hotspot near-miss was a
  direct downstream consequence). Fix this and eval-bound work can be isolated in a worktree like everything
  else.
- **It is a silent-wrong-result trap, not just an inconvenience.** A worktree eval run *succeeds* but with CE
  off → plausible-but-wrong hybrid numbers. An agent who doesn't know the constraint can publish a wrong
  result. Worktree-aware resolution (or, failing that, a loud refuse-if-reranker-absent) closes the trap.

## Scope boundary (for the design phase, not decided here)
Out of scope for this stub: *which* resolution mechanism (a worktree→main fallback in the gradle
`justsearch.repo.root` property, an env override, a symlink/junction at prepare-worktree time, or a
fail-loud guard), and whether GPU/`cuda12` variants are in scope. This file records only the idea + its
purpose; the design picks the mechanism. Until then, the operational rule stands (635 §workflow-lessons #4):
run live eval on the **main** checkout.

---

## Investigation — 2026-06-30 (agent takeover; verification, not yet design)

> Read the whole tempdoc, then traced the model-resolution path through the dev-runner, the jseval
> harness, the Gradle eval contract, and the reranker config. **The diagnosis holds, with two
> refinements to the mechanism and one new public-repo caveat.** No design chosen yet (per the
> "don't begin design/implementation" instruction); this section records what is true on `main` so the
> design phase starts from verified facts instead of the stub's prose.

### A. The mechanism, traced to source (the stub's diagnosis is correct)

The chain that silently turns the cross-encoder off in a worktree:

1. **The eval harness launches the backend from the *worktree* root.** `jseval run --start-backend`
   and `corpus-fidelity --start-backend` both call `backend.start_backend()`, which runs
   `:modules:ui:runHeadlessEval` with `cwd = REPO_ROOT` (`scripts/jseval/jseval/backend.py:97-116`).
   `REPO_ROOT` is resolved to the **active worktree** by walking up from CWD to the nearest `.git`
   entry (`scripts/jseval/jseval/_paths.py:30-49`) — by design, so eval acts on the worktree's code.
2. **Gradle defaults the models dir to `<that root>/models`.** `applyHeadlessEvalContract` sets
   `modelsDirProvider = env(JUSTSEARCH_MODELS_DIR) orElse <repoRoot>/models`
   (`modules/ui/build.gradle.kts:1918-1920`) and exports it as both the `JUSTSEARCH_MODELS_DIR` env var
   and the `justsearch.models.dir` / `justsearch.repo.root` system properties (lines 1948-2003). With
   no env override, the Worker is pointed at the **worktree's own** `models/`.
3. **A worktree's `models/` has the manifests but not the LFS binaries** — so reranker discovery
   misses. `OnnxModelDiscovery.resolve` walks `modelsDir → <dataDir>/models → <repoRoot>/models →
   <baseDir>/models`, checking `<root>/onnx/reranker/` for `model.onnx` + `tokenizer.json`
   (`modules/configuration/.../resolved/OnnxModelDiscovery.java:86-120`). The worktree dir has
   `tokenizer.json` but no `model.onnx`, so `isCompleteModelDir` returns false → discovery returns null.
4. **Null discovery silently disables the reranker.** `RerankerConfig.from()` sets
   `enabled = explicitEnabled != null ? … : (discovery != null && …)`; with `discovery == null` and no
   explicit `JUSTSEARCH_RERANK_ENABLED`, `enabled = false` and `modelPath = null`
   (`modules/reranker/.../RerankerConfig.java:70-97`). No exception, no startup failure — the run
   proceeds and emits plausible-but-CE-off hybrid numbers. **Confirmed silent-wrong-result trap.**

**Refinement 1 — the stub's "`justsearch.repo.root → models/onnx/reranker`" is imprecise.** The
operative variable is `JUSTSEARCH_MODELS_DIR` (which *defaults* to `<repoRoot>/models`), not
`justsearch.repo.root` directly. This matters for the design: the cleanest fix overrides
`JUSTSEARCH_MODELS_DIR` (one variable, already the dev-runner's lever), not the repo-root property
(which also moves SSOT, plugins manifest, server-exe dev-layout search, etc. — wider blast radius).

### B. The fix already exists for the *other* launch path — and is the proven pattern to copy

The dev-runner **already solves this** for the live dev stack: when `JUSTSEARCH_MODELS_DIR` is unset it
resolves the **main checkout** (`.git`-file → `gitdir:` → up 3 levels, `resolveMainRepoRoot()`,
`scripts/dev/dev-runner.cjs:32-46`) and prefers `<mainRepoRoot>/models` over the worktree's
(`scripts/dev/dev-runner.cjs:428-434`, tempdoc 618 §2). **The eval path simply never got the same
treatment** — `backend.py` doesn't set `JUSTSEARCH_MODELS_DIR`, and Gradle's default is worktree-local.

This is the core finding: **the gap is a missing replication of an existing, working resolver, not a
novel design problem.** `prepare-worktree.cjs:5-11,47` even advertises "models … resolve automatically
via the dev-runner" — true for `dev_start`, **false for `jseval … --start-backend`**, which is exactly
the eval path. And `cli.py:3347-3369` documents the corpus-fidelity backend as one that
"auto-discovers the reranker (default-on engine)" — the assumption that silently fails in a worktree.

### C. Historical precedent strengthens the "silent trap, not inconvenience" argument

This failure mode has already bitten, independent of worktrees: tempdoc 214 §481 records "All Phase B
nightly evals run with reranker and citation-scorer **DISABLED**" — a path-mismatch (not a config
default) silently disabled the reranker for an extended period before anyone noticed (214 §390, 215
line 180; fixed in `02fafeac`). So the danger the stub asserts is demonstrated, not hypothetical — and
it argues for the **fail-loud guard** as a complement to (not just an alternative to) auto-resolution.

### D. A loud guard is cheap — the diagnostic surface already exists

The stub frames "loud refuse-if-reranker-absent" as a fallback. Worth noting it is nearly free: the
backend already publishes reranker wiring on `/api/status`, and jseval already reads it —
`preflight.execute_preflight` reports `reranker_model_path`, `model_wiring.reranker.wired`, and
`crossEncoderSkipReason` (`scripts/jseval/jseval/preflight.py:128-151`; skip reasons documented in
`.claude/skills/search-quality/SKILL.md:1051-1054`). A pre-run assertion in `backend.py` /
`_run_single_iteration` ("CE requested but reranker not wired → abort") needs no new backend surface.
This makes a **belt-and-suspenders** design attractive: auto-resolve from main *and* refuse if the CE
engine the run claims is, in fact, off.

### E. NEW caveat — this public mirror has **no** model binaries at all

`F:\justsearch-public` (this checkout, the public mirror) has **no `model.onnx` anywhere** and **no Git
LFS configured** — `models/onnx/reranker/` holds only `config.json` / `tokenizer.json` / token maps
(verified: `git lfs ls-files` empty; `find models -name "model*.onnx"` empty). So here, **even the main
checkout cannot run reranker-bearing eval** — worktree-aware resolution is necessary but *not
sufficient* in the public repo. Implication for the design: the auto-resolve mechanism must degrade
honestly (the fail-loud guard from §D) rather than assume "main always has the binaries." If 644 is
ever implemented in the public repo, the model-provisioning question (LFS vs. download-on-demand vs.
out-of-scope) is a prerequisite, and should be called out — possibly as its own concern.

### F. Critical read of the four candidate mechanisms (decision still deferred)

| Mechanism (from the stub) | Assessment |
|---|---|
| **Worktree→main fallback in Gradle `justsearch.repo.root`** | Over-broad. Repo-root drives SSOT, plugins manifest, dev-layout server-exe search, etc. — moving all of them to main to fix *models* risks subtle cross-tree drift. Prefer scoping to `JUSTSEARCH_MODELS_DIR` only. |
| **Env override** | This is essentially "do what the dev-runner does." Cleanest locus is `backend.py` (mirror `dev-runner.cjs:428-434`: if `JUSTSEARCH_MODELS_DIR` unset, resolve main checkout via the same `.git`-file walk). Single source of truth concern: the worktree→main resolver now lives in JS (`resolveMainRepoRoot`) and would gain a Python twin — consider extracting it so the two can't drift. |
| **Symlink/junction at prepare-worktree time** | Materializes the trap-avoidance at setup, but Windows junctions into LFS dirs are exactly what `remove-worktree.cjs` already had to special-case (618 §2); adds a stateful step that can rot. Weaker than runtime resolution. |
| **Fail-loud guard** | Not mutually exclusive — see §D. Should ship *regardless* of which resolver is chosen, because §E means resolution can legitimately find nothing, and §C shows silent-off has escaped notice before. |

**Tentative lean (not a decision):** env-scoped resolution in `backend.py` (mirror the proven
dev-runner logic, ideally via a shared resolver) **+** a fail-loud CE-wiring assertion. Defer:
GPU/`cuda12` variants, and the public-repo model-provisioning prerequisite (§E).

### Open questions for the design phase
1. Shared resolver: extract `resolveMainRepoRoot` so JS (dev-runner) and Python (jseval) share one
   implementation, or accept a small Python duplicate? (AHA: they share a reason to change — the
   worktree `.git`-file layout — so unification is justified, not over-DRY.)
2. Is the fail-loud guard scoped to "CE explicitly requested" (`--ce` / hybrid modes) only, or any run
   whose reported engine set diverges from what was asked? (preflight already has the data for either.)
3. Public-repo model provisioning (§E) — in scope for 644, or split to a separate concern?

---

## Theorization — 2026-06-30 (broad exploration; NOT design or implementation)

> The brief: think widely before the design settles — alternative framings, tradeoffs, hidden
> assumptions, and whether 644 points to a recurring system shape. Everything below is option-space,
> deliberately divergent. None of it is decided. The §F "tentative lean" above is still the cheapest
> concrete path; this section exists so the design phase chooses it (or doesn't) with eyes open.

### T1. Four ways to frame the problem (each implies a different fix locus)

The stub frames it narrowly as **"models resolution is worktree-blind."** Three wider framings exist,
and the framing you pick determines where the fix lives and what else it catches:

- **(Locus) Resolution problem** — *the binaries are in main, the worktree looks locally.* Fix = the
  resolver (§F). Catches: the worktree trigger. Misses: every non-worktree way the same symptom occurs.
- **(Integrity) Silent-capability-degradation problem** — *a measurement instrument quietly changed
  configuration and still emitted a number.* The reranker is one of ~6 engines (dense, SPLADE, NER,
  reranker, LLM, GPU-vs-CPU) that can each silently drop. The worktree just makes one likely. Fix =
  an **instrument-integrity assertion** (declared engine set == realized engine set, else refuse).
  Catches: forgot `--ce`, LFS-not-pulled-on-main, GPU→CPU fallback, schema-disabled SPLADE — *all* of
  it, trigger-agnostic. This is the root-cause framing; the resolver is a symptom fix under it.
- **(Parity) Worktree-parity problem** — *a worktree faithfully clones tracked text but not LFS / built
  artifacts / untracked maintainer files.* 618 already patched three holes in this same boundary
  (models-for-dev-runner, llama-server baseline, node_modules); 644 is the **eval-models hole in the
  identical boundary.** Fix = treat parity as a *surface* (an enumerable matrix of "asset class ×
  workflow that needs it"), not a stream of per-hole patches. Catches: the *next* hole, before it bites.
- **(Provenance) Contaminated-comparison problem** — *eval numbers feed ratchets/gates/baselines; a
  number produced under a different engine set is non-comparable but looks comparable.* Fix = make the
  realized engine set (and device) a first-class dimension of the run manifest/provenance, and make the
  gates refuse cross-envelope comparison. Catches: silent drift at the *consumption* point, which is
  where the damage (a bad ratchet re-pin) actually lands.

These are not competitors so much as **defense layers at different points in the pipe**: resolve
correctly (production) → refuse if realized≠declared (instrument) → record realized conditions
(provenance) → refuse non-homogeneous comparison (consumption). The stub only occupies the first.

### T2. Solution directions beyond the stub's four

- **E. Capability contract on the run.** The run declares its required engine set; the backend already
  reports the realized set (`/api/status` → preflight, §D); harness aborts on mismatch. Generalizes the
  fail-loud guard from "reranker" to "any declared engine." Cheapest high-leverage addition.
- **F. Provenance-embedded engine set + comparison guard.** Add realized `{engines, device,
  model-shas}` to the result manifest (there's already `provenance.py` + manifest hashing +
  `cohort_baselines/`); have relevance/perf/leak gates refuse to compare across engine-set boundaries.
  This is the existing **schema-fingerprint refusal** pattern (gates already won't compare across schema
  changes) extended to the *runtime* dimension. Defends the thing that matters (the baseline) regardless
  of how the drift happened.
- **G. Converge the two launch paths.** The proximate cause is that the stack has **two bring-up
  paths** — `dev-runner.cjs` (worktree-aware) and `backend.py`→`runHeadlessEval` (not). Two paths for
  one job *will* drift; this is literally that drift. Option: have `jseval --start-backend` bring the
  stack up *through* the dev-runner with an **isolated state root** (`JUSTSEARCH_DEV_RUNNER_STATE_ROOT`
  already exists for exactly "throwaway stack that must not touch the shared lease"). Then the models
  fix — and every *future* environment fix — is inherited for free. Risk: drags lease/ownership
  semantics eval doesn't want; mitigated by the isolated state root, or by extracting only the
  *environment-resolution* slice (not the lease slice) into a shared module both consume. This is the
  **projection-vs-fork** discipline (CLAUDE.md) applied to stack bring-up: `runHeadlessEval`'s env
  block is a *fork* of the dev-runner's; forks drift; make one a projection.
- **H. Filesystem parity below all consumers.** Junction/symlink `<worktree>/models → <main>/models` at
  *worktree-creation* time (EnterWorktree/prepare-worktree), so every consumer that reads
  `<repoRoot>/models` "just works" with zero per-consumer changes — the most *general* fix because it
  repairs parity beneath the resolution layer entirely. Risk is operational, not conceptual: Windows
  junction fragility, LFS interaction, and the **cleanup-deletes-through-into-main** scar 618 already
  hit with node_modules junctions (`remove-worktree.cjs` had to unlink link-only). High generality,
  high blast radius — flagged as a "useful later" idea, not a lead.
- **I. Distinguish "absent" from "present-but-incomplete."** A worktree's `models/onnx/reranker/` has
  `tokenizer.json` **but no `model.onnx`** — a *distinctive partial signature* that means "binaries not
  materialized (LFS/worktree)," not "no models, intentional CPU run." Discovery currently collapses both
  to `null` (silent). Teaching discovery/preflight to emit a *different, loud* signal on the partial
  shape converts the exact worktree fingerprint into a legible error — and is reusable in installer /
  sandbox contexts where the same "manifest present, binary missing" state occurs.
- **J. Preflight-by-default.** preflight is a separate command today; making `jseval run` run a
  lightweight capability check first (auto-resolve to main, or refuse) folds E+§D into the default path
  so the trap can't be reached by omission.

### T3. Hidden assumptions worth surfacing before committing

1. **"Resolve from main" assumes main has the binaries.** §E disproves it for the public repo, and on
   the private repo `main` can have **LFS not pulled**. So "main" is a *heuristic source*, not a
   guarantee. The robust invariant is "resolve from *some* dir with **complete** binaries" (main, env
   path, shared cache, on-demand fetch) — don't hardcode "main" as the design's foundation.
2. **"Worktree" is assumed to be the trigger.** The same silent-CE-off happens on main (LFS, GPU
   fallback, forgot `--ce`). A worktree-scoped fix *under-solves*; the instrument/provenance guards
   (T1) are trigger-agnostic and catch the class.
3. **"Eval should always use the reranker" is false.** Leg-isolation modes (`vector,lexical,splade`)
   *intentionally* exclude CE. Any guard must key off the **intended** engine set per mode/flag
   (`--ce/--no-ce`, mode defaults in `cli.py`), not blanket-require reranker — otherwise it fails
   correct runs. The intent signal already exists; the guard must read it.
4. **Per-encoder resolution is assumed uniform — it isn't.** Reranker discovery passes
   `devSubdir=null` (no dev-layout fallback) while SPLADE passes a dev subdir
   (`OnnxModelDiscovery.resolve(...,"reranker",null)` vs SPLADE's). A fix touching reranker resolution
   should avoid widening this asymmetry; better, it's a latent cleanup (the encoders share a
   reason-to-change → the asymmetry is an un-unified fork).
5. **The cost is assumed to be "merge collisions on main" (635's framing).** There's a second,
   under-weighted benefit: **parallelism.** The shared dev-stack lease serializes eval — only one runs
   at a time. Worktree-isolated eval unlocks *concurrent* eval across agents, not just collision
   avoidance. This raises the value of the fix and argues for the isolated-stack direction (G/H) over a
   pure resolver tweak.
6. **"GPU out of scope is safe" may quietly reintroduce a provenance mismatch.** A worktree CPU-reranker
   run yields different *latency* (perf-gate) and possibly different *numerics* than the GPU baselines
   main produced. Even after the models fix, comparing a CPU-worktree run to a GPU-main baseline is
   apples-to-oranges on the perf axis. This is exactly why the **device** belongs in the provenance
   envelope (T1-provenance / F). Deferring GPU is fine; deferring *recording* the device is not.

### T4. The recurring shape (candidate principle — explicitly not yet adopted)

644 is the third instance of one underlying pattern, so it's worth naming the pattern even while
leaving the design open:

> **An eval/measurement run is an instrument; an instrument that silently runs under conditions other
> than the ones declared produces invalid data, not a degraded result. Therefore: (a) fail closed when
> realized conditions ≠ declared conditions, and (b) record realized conditions in provenance so
> downstream comparisons stay condition-homogeneous.**

This is "verify, don't guess" and "interrogate results" (CLAUDE.md) applied to the eval harness itself.
The codebase already lives this principle structurally elsewhere — schema-fingerprint refusals,
embedding-compat gating, the governance registers that turn implicit drift into explicit gate failure.
644's reranker-off is the same disease in a corner that lacks the antibody. Framed this way, the
"worktree models" headline is the *trigger that exposed a missing instrument-integrity invariant*, and
the highest-leverage work may be installing that invariant (T1-instrument + provenance) rather than only
patching the resolver.

A second, narrower recurring shape also deserves a name, because 618→644 is a clean two-point trend:

> **Worktree parity is a boundary, not a backlog.** Each fix (618's three, 644's one) is a leak in the
> same membrane: *worktrees inherit tracked text but not LFS / built artifacts / untracked files.* The
> membrane is enumerable. Whether it's worth a "worktree-parity matrix" (asset class × dependent
> workflow, à la the existing governance registers) is a judgment for later — but 644 is the data point
> that would justify starting one, and the `prepare-worktree.cjs` comment that *already lists* what a
> worktree does/doesn't inherit is the embryonic form of that matrix.

### T5. Carry-forward ideas (useful later even if not the chosen design)

- The **partial-model-dir signature** (`tokenizer.json` ∧ ¬`model.onnx`) as a precise "LFS/worktree
  not materialized" detector — reusable in installer + sandbox provisioning, not just eval (T2-I).
- **preflight already carries the realized engine/device data** — a default guard is wiring, not new
  surface (§D, T2-E/J).
- The **isolated-state-root dev-runner** (`JUSTSEARCH_DEV_RUNNER_STATE_ROOT`) as the seam that could let
  eval ride the worktree-aware launch path without taking on the lease model (T2-G).
- **Device-in-provenance** as the thing that keeps GPU-deferral honest (T3-6).
- The **encoder-resolution asymmetry** (reranker has no dev fallback) as a latent unify-the-fork cleanup
  to fold in if/when reranker resolution is touched (T3-4).

---

## Design — the settled long-term shape (2026-06-30)

> General, not implementation-level (per the brief). The size below is the outcome of matching scope
> to the problem, not a target. The decisive input from the adjacent-tempdoc read: **this is not a new
> design — it is the completion of tempdoc 283** ("Worktree GPU Runtime Path Unification", *done*),
> which already named the root cause and built the seam. 283's own root-cause sentence is 644's
> charter: *"JustSearch centralizes some runtime paths, but not all of them, and the missing pieces are
> exactly the ones that matter for … isolated worktree runs."* 283 swept embedding + SPLADE + ORT-CUDA
> and pushed them into the resolved-config / launcher contract; it **did not sweep the reranker**, and
> it left *which* asset root to use as **"manual env knowledge not encoded in the supported launcher
> contract"** (283 §Thesis). 644 closes exactly those two residuals.

### What the problem actually requires (and what it does not)

The tempdoc's real problem is two coupled failures of *one* run: (a) a worktree eval **runs under a
different engine set than intended** (reranker off; per 283, potentially SPLADE/embedding/GPU too) and
emits wrong-but-plausible numbers; (b) to avoid (a), all eval is **forced onto shared `main`**, which is
the multi-agent collision root (635 §wl-4) and also serializes eval behind the shared dev lease.

Fixing this requires exactly two structural pieces — no more, no less:

- It requires **default-correct asset resolution** (else the worktree can't run eval at all), and that
  resolution must be **single-sourced** (else it forks again and drifts — which is literally how the
  eval path diverged from the dev path).
- It requires the run to be **self-verifying** (else "resolve from main" — a heuristic that can
  legitimately find nothing: public repo §E, LFS unpulled, GPU absent — silently reintroduces (a); and
  §C shows silent-off has escaped notice for whole nightly-eval windows before).

It does **not** require: a worktree-parity registry, a provenance-schema extension, cross-run
comparison gates in relevance/perf/leak, or filesystem junctions. Those are either broader than this
run-level problem or higher-risk than runtime resolution (283 §Item 4 already concluded explicit
resolution beats auto-staging). They are recorded under §Reach / §Theorization as *recognized*, not
*built*.

### Axis 1 — One default-correct asset-resolution contract, shared across all three launchers

**Shape:** there is a single resolver that answers *"where do the large shared assets (models, and the
ORT-CUDA native path 283 already promoted to resolved config) live for this run?"* — and its answer is
**worktree-safe by default, with no manual env required.** All stack launchers consume that one
resolver instead of each defaulting by local convention.

This conforms to 283's seam rather than creating a parallel one. Concretely, three loci exist today and
must converge to *one contract* (not three forks):

1. `dev-runner.cjs` — already worktree-correct: `resolveMainRepoRoot()` (`.git`-file → `gitdir:` walk)
   + prefer `<mainRepoRoot>/models` over the worktree's (618 §2). **This is the working reference
   implementation.**
2. `applyHeadlessEvalContract` (Gradle) — the *fork* that defaults wrong: `modelsDir = env(JUSTSEARCH_
   MODELS_DIR) orElse <repoRoot>/models`, where `<repoRoot>` is the worktree. This is the locus the
   eval path (`jseval … --start-backend`, `corpus-fidelity`) inherits.
3. Production (Tauri `lib.rs`) — sets repo-root to the headless sidecar dir; out of scope but must stay
   consistent with the contract.

**Design directive (general):** the worktree→main asset-root resolution becomes the **default** of the
asset contract, expressed **once** and consumed by the eval launcher — i.e. make the eval contract's
asset root a *projection* of the same resolver the dev-runner uses, not a second hand-rolled default.
Scope the override to the **asset root** (`JUSTSEARCH_MODELS_DIR` and the ORT-native path 283 added),
**not** the whole `justsearch.repo.root` (which also moves SSOT/plugins/dev-exe search — wider blast
radius for no benefit; §F). Because all encoders resolve through the *same* `OnnxModelDiscovery`
(`modelsDir → dataDir → repoRoot → baseDir`, `onnx/<name>`), fixing the default **covers the reranker
283 missed for free**, and re-unifies the per-encoder asymmetry (T3-4) by construction rather than
per-subsystem.

Manual `JUSTSEARCH_MODELS_DIR` / explicit `JUSTSEARCH_RERANK_MODEL_PATH` remain the highest-precedence
override (the escape hatch for "point eval at a specific asset set"); the design only fixes the
*default*, which is where the trap lives.

### Axis 2 — Fail-closed instrument integrity (intended engine set == realized engine set, or refuse)

**Shape:** a stack-bound eval is a measurement instrument. Before it emits or records numbers, it
asserts that the **realized** engine set matches the **intended** one; on divergence it **fails
closed** (loud refuse) rather than publishing a number produced under undeclared conditions.

The whole data path for this **already exists** — Axis 2 is wiring, not new surface:

- **Intended** engine set is already declared by the run: `--ce/--no-ce` + per-mode defaults
  (`cli.py`), the leg modes (`vector,lexical,splade`) that *intentionally* exclude CE, `--embedding`,
  `--splade`, `--llm`. The guard keys off this so it never fails a *correctly* CE-off leg run (T3-3).
- **Realized** engine set is already reported twice over: `/api/status` → `preflight.execute_preflight`
  (`model_wiring.reranker.wired`, `crossEncoderSkipReason`, device/`*OrtCuda` availability), and the
  per-run `component_status_counts` (incl. `cross_encoder`) in `provenance.aggregate_run_evidence`.

**Design directive (general):** at the run boundary, compute intended-vs-realized and refuse on
mismatch — including the **device** dimension (a CPU-realized run when GPU was intended is a *different
instrument*, which is what keeps "GPU deferred" honest, T3-6). This is the floor that makes Axis 1 safe
where it legitimately can't succeed (public repo §E), and it is the antibody §C shows this corner has
lacked. Axis 2 ships **regardless** of Axis 1 — it is not a fallback, it is the integrity invariant.

### Why both, and why not more

Axis 1 makes worktree eval *work* (and unlocks **parallel** eval off the shared lease — T3-5, a benefit
the stub under-weighted). Axis 2 makes it *trustworthy* and is trigger-agnostic (catches forgot-`--ce`,
LFS-unpulled-main, GPU-fallback — not just worktrees). Neither alone is sufficient: Axis 1 without Axis
2 silently breaks again whenever main lacks binaries; Axis 2 without Axis 1 leaves the workflow blocked
(loud, but still can't eval from a worktree). Together they are the minimal complete structure for the
problem as stated. Everything larger is deferred to §Reach as recognized-not-built.

### Out of scope / prerequisites (unchanged from §E/§F)
- **GPU/`cuda12` variant resolution** — deferred; but Axis 2 must *record/refuse on* the device
  dimension so the deferral doesn't silently poison perf comparisons.
- **Public-repo model provisioning** (§E: no LFS, no binaries even on main) — a prerequisite for Axis 1
  to *succeed* here, not a thing Axis 1 can solve. Axis 2 degrades it honestly (loud refuse). Whether to
  provision public-repo models (LFS / download-on-demand / out-of-scope) is a separate concern; flag,
  don't fold in.

---

## Research pass — git worktree ↔ Git LFS mechanics (2026-06-30)

> Scope discipline: most of this design is internal architecture where the web adds nothing. **One**
> load-bearing assumption is externally verifiable and version-sensitive — *how* a worktree comes to
> hold pointer-files-not-binaries — and it informs Axis 1's **mechanism** (not the Axis-level design).
> A focused pass was run; it corrected a mechanism assumption and added an option to §F.

**Verified git mechanics:**
- A linked worktree shares **`$GIT_COMMON_DIR`** with the main checkout — the shared object store,
  **including LFS blobs**, lives there. Only the **working tree** (the checked-out files) is
  per-worktree and independent. So a worktree's `models/` showing only pointer files does **not** imply
  the binary objects are absent — they may already sit in the shared store, un-smudged into this
  working tree.
- Since **Git 2.16**, `git worktree add` runs the **post-checkout hook**, so LFS files *normally*
  materialize on worktree creation (older git left only pointers, requiring a manual `git lfs
  checkout`). Corollary diagnostic: when JustSearch worktrees *don't* get real models, the cause is a
  **configuration** state (LFS not installed/hooked, or `GIT_LFS_SKIP_SMUDGE` set, or — §E — no objects
  at all), not an inherent git limitation.
- `git lfs checkout` inside a worktree **does not re-download** — it copies blobs from the shared store
  into the worktree working tree (a second on-disk copy, **no network**).
- **Anti-pattern ruled out:** sharing `lfs.storage` across *distinct repositories* (not worktrees) is
  unsafe — a `git lfs prune` in one treats the other's blobs as dangling and deletes them (data loss).
  This kills any "point the worktree's LFS storage at main's" idea. Worktrees already share storage
  *natively* via the common dir, so that idea is also unnecessary.

**Impact on the mechanism menu (§F) — a 5th option, and a sharpened §E:**

- **New option K — materialize via `git lfs checkout` at prepare-worktree time.** Because the blobs are
  already in the shared object store, a checkout populates `<worktree>/models` with *real files*, no
  network, and then **every** consumer reading `<repoRoot>/models` works with zero per-consumer change
  (like the junction option H, but with real files → no delete-through-into-main risk that bit 618's
  node_modules junctions). Cost: a second on-disk copy of the model blobs per worktree (disk, not
  bandwidth), and the copy can go **stale** if main's models change. Requires LFS actually configured +
  objects present.
- **§E sharpened (public repo):** this checkout `F:\justsearch-public` has **no LFS configured and no
  blobs in the store** (`git lfs ls-files` empty; no `model.onnx` anywhere). So option K is a
  **non-starter here** — there is nothing to check out — and the runtime "resolve-from-main" resolver
  also has no complete source to resolve to. This **reinforces Axis 2** as the only mechanism that
  degrades honestly in the public repo: fail closed, loudly, when the intended engine can't be realized.
- **Net design impact:** unchanged at the Axis level. Axis 1's recommended **default mechanism remains
  the runtime asset resolver** (env-scoped `JUSTSEARCH_MODELS_DIR` → main checkout) because it (i) costs
  no extra disk, (ii) works whether or not LFS smudged the worktree, and (iii) needs no per-worktree
  setup step that can rot. Option K is recorded as a legitimate alternative **for the private repo**
  (where blobs exist) — appealing precisely because it fixes parity *below* all consumers — but its
  disk-copy + staleness cost and its public-repo inapplicability keep it the secondary choice.
- **Reframes the "worktree parity" shape (Reach §):** the parity gap is **partly self-inflicted**
  (smudge-skipped / LFS-not-hooked), not purely an inherent "worktrees don't carry assets" law. That
  narrows the candidate "worktree-parity register" — some leaks may be closeable by *fixing LFS
  configuration* once, rather than by per-workflow resolvers.

Sources: [git-worktree docs](https://git-scm.com/docs/git-worktree),
[git-lfs #545 — Investigate Git LFS and Git worktrees](https://github.com/git-lfs/git-lfs/issues/545),
[git-lfs #3664 — worktree checkout with hardlinks](https://github.com/git-lfs/git-lfs/issues/3664),
[git-lfs #4530 — sharing lfs.storage between distinct repositories](https://github.com/git-lfs/git-lfs/issues/4530),
[git-lfs-checkout(1)](https://manpages.debian.org/unstable/git-lfs/git-lfs-checkout.1.en.html).

---

## Reach — is this an instance of a principle, and where else does it apply?

> Separating *recognizing* a principle from *building* its general structure (the brief's deliberate
> split). Below: the principle named, its existing home in the system, candidate scope, and existing
> code that already violates it — **without** building the generalized structure now.

### This design conforms to an existing seam — it does not invent one

The resolved-config / launcher-contract seam is **already the system's chosen shape**:
`ResolvedConfig` + `ResolvedPathResolver` + the `applyHeadlessEvalContract` env block are the SSOT for
runtime paths, and 283 explicitly extended that seam (promoting the ORT-native path from a side-channel
into resolved config) under the banner *"fix structurally rather than patch individual launcher
cases."* 644 Axis 1 is **the same seam, finished** — the reranker subsystem 283 skipped and the default
283 left manual. Conforming (completing 283) rather than creating a parallel resolver is the correct
move; a new "eval asset resolver" would be exactly the fork that caused the bug.

### The principle (named plainly)

> **Instrument integrity under a single resolved contract:** a measurement run must execute under the
> configuration it declares, or refuse to report — and that configuration must come from *one* resolved
> contract consumed by every launcher, never from per-launcher convention.

This is two existing CLAUDE.md disciplines fused and aimed at the eval harness itself: **verify, don't
guess** (the run must *check* its realized config, not assume it) and **interrogate results** (a number
produced under silently-altered conditions is invalid data, not a degraded result). It also mirrors the
system's existing **schema-fingerprint refusal** pattern (the index already refuses to compare/serve
across an embedding-fingerprint mismatch — `embeddingCompatState`) — Axis 2 is that same fail-closed
shape applied to the *runtime engine* dimension instead of the *stored-vector* dimension.

### Candidate scope — where else the principle applies (recognized, not built)

1. **The eval comparison gates** (`relevance-gate`, `perf-gate`, `leak-gate`, `calibrate`). They compare
   a run against a baseline series **without checking engine-set / device homogeneity.** A CPU-worktree
   CE-off run can be diffed against a GPU-main CE-on baseline and silently re-pin a ratchet. The data to
   enforce homogeneity already exists (`component_status_counts`, the device fields). **This is an
   existing latent violation of the principle** — but fixing it is *broader than 644's run-level
   problem* (it's about baseline-series homogeneity across runs), so: **recorded here, not built.** If a
   future tempdoc tackles eval-provenance comparison, this is its charter.
2. **The two stack-launch paths** (dev-runner vs runHeadlessEval). "Two contracts for one job will
   drift" is the meta-shape; their convergence (Axis 1) is the conformance. Any *third* launcher added
   later (CI eval, a benchmark harness) must consume the same contract or it re-violates the principle.
3. **Other capability-gated features** beyond eval — RAG with the LLM silently off, citations with the
   scorer silently off — are the same "silent capability degradation" shape. They already surface skip
   reasons (`expansionSkipReason`, etc.) but do not uniformly *fail closed* when a caller declared the
   capability. Candidate scope for the integrity half of the principle; not a 644 deliverable.

### The narrower recurring shape (also recognized, not built)

**Worktree parity is a boundary, not a backlog.** 283 → 618 → 644 is a clean three-point trend: each
patched a different leak in the *same* membrane (worktrees inherit tracked text but not LFS / built /
untracked assets). A "worktree-parity matrix" (asset class × dependent workflow, in the style of the
existing governance registers that turn implicit drift into explicit gate failure) is the structure
this trend *would* justify — but the present problem needs the resolver + guard, not a registry.
**Recorded as candidate; the third data point is now on record so a future maintainer can decide if the
fourth warrants the register.** The `prepare-worktree.cjs` header comment that already enumerates what a
worktree does/doesn't inherit is the embryonic, prose form of that matrix.

---

## Pre-implementation confidence (2026-06-30 — investigation pass, no feature code written)

> A read-only confidence pass against the settled design, mapping each uncertainty to source evidence
> on this branch. Goal: reduce surprises before implementation. Every mechanism claim below is
> `file:line`-verified; the one thing that **cannot** be verified in this checkout (the live loop) is
> called out explicitly.

### Findings per uncertainty

- **U1 — single-source locus (resolved; high confidence).** There is **no shared chokepoint** between
  the two launchers: `dev-runner.cjs` launches the Head **directly from `installDist`** ("instead of
  `gradlew runHeadless`", `scripts/dev/dev-runner.cjs:1010`), assembling env in JS (incl. the
  already-correct main-models fallback, `:428-434`); the **eval** path uses `gradlew runHeadlessEval` →
  `applyHeadlessEvalContract` (Kotlin, `modules/ui/build.gradle.kts:2103-2109`). `runHeadless` and
  `runHeadlessEval` are also *separate* env blocks (`:2054-2101` vs `:1889-2052`). **Implication:** the
  "one shared resolver across launchers" ideal is *not* free (JS vs Kotlin, no common seam) and is
  **optional polish, not required**. The eval path has a clean **single locus** — fix
  `applyHeadlessEvalContract`'s `modelsDirProvider` default (or set `JUSTSEARCH_MODELS_DIR` in
  `backend.py`'s env before it invokes Gradle). The dev path is already correct (618). So Axis 1
  reduces to "give the eval contract the worktree→main default the dev-runner already has."
- **U2 — Head→Worker propagation (resolved; high confidence).** `WorkerSpawner` forwards **all**
  `JUSTSEARCH_*` env vars to the Worker subprocess (`WorkerSpawner.java:403-407`), and the resolved
  reranker path also flows via the worker config snapshot (`RerankerConfigTest
  .fromEnvReadsModelPathFromConfigStore`); 283 live-verified `justsearch.models.dir` reaching the
  worker snapshot. So setting `JUSTSEARCH_MODELS_DIR` at the launcher *does* reach the discovery code in
  the Worker. The fix lands at the right layer.
- **U3 — reranker layout + enable (resolved; high confidence).** Canonical layout is
  `<modelRoot>/onnx/<name>`; reranker passes `devSubdir=null`, so it resolves **only** at
  `<modelsDir>/onnx/reranker/` and (standard-layout branch) returns `autoDiscovered=true`
  (`OnnxModelDiscovery.java:90-96`), which makes `RerankerConfig.from` auto-enable
  (`RerankerConfig.java:86-90`). `isCompleteModelDir` is **file-existence only** (tests write `"stub"`
  content — `OnnxModelDiscoveryTest`), so pointing `JUSTSEARCH_MODELS_DIR` at a tree with
  `onnx/reranker/{model.onnx,tokenizer.json}` is sufficient to enable the reranker. The public repo's
  `models/onnx/reranker/` (tokenizer present, `model.onnx` absent) confirms the layout *and* the trap.
- **U4 — Axis 2 signal timing (resolved, with a refinement; medium-high confidence).** The status
  field `rerankerModelPath` is wired at **startup** (`DefaultWorkerAppServices.java:197-199`), so it is
  **observable pre-query** — Axis 2 can do a *pre-run* "model resolved?" check without lazy-init
  false-refusal. **Refinement/caveat:** that field is sourced from the **ChunkRerankerConfig** path
  (which falls back to the same `onnx/reranker` dir), so it is a reliable *proxy* for "a reranker model
  is present," but the **definitive** "the search cross-encoder actually executed" signal is post-query
  (`crossEncoderSkipReason` from the search executor + `component_status_counts["cross_encoder"]` in
  `provenance.aggregate_run_evidence`). Robust shape = **belt-and-suspenders**: pre-run model-present
  assertion (fast-fail before wasting a run) **+** post-run executed assertion (catch residual skips).
  Both data points exist.
- **U5 — CE intent rule (resolved, with an important nuance; high confidence).** Intent is already
  materialized: for **leg modes** the pipeline dict carries explicit `crossEncoderEnabled: false`
  (`retriever.py` MODE_PIPELINES; counterfactual legs `counterfactual.py:94-123`), and `--ce` injects
  `crossEncoderEnabled: True` (`run.py:194-199`). **Nuance that matters:** `hybrid` is a **SERVER_MODE**
  (`retriever.py:86-87`) — *not* in `MODE_PIPELINES` — so a plain `jseval run --modes hybrid` sends **no
  pipeline**, and CE-on is decided **server-side by reranker auto-discovery** (this is precisely why the
  trap is silent: the client never asserts CE for hybrid). So Axis 2's intent rule is: **CE-intended =
  `--ce` set, OR mode is a CE-bearing server mode (hybrid) — i.e. the "default-on engine" is expected.**
  Equivalently, the guard can key off "default-on engine unexpectedly absent": *if* `rerankerModelPath`
  is empty during a hybrid/CE run → refuse. Leg-isolation runs (explicit `crossEncoderEnabled:false`)
  are never flagged. This cleanly avoids false-firing.
- **U6 — public-repo live-loop limit (confirmed; the main residual).** This checkout has **no LFS and
  no model binaries** (`git lfs ls-files` empty; no `model.onnx`). So the end-to-end loop *worktree →
  resolver finds main models → reranker on → corrected hybrid nDCG* **cannot be exercised here.** Every
  link in the chain is independently mechanism-verified above, but the *composed* behavior on a repo
  with real models (the private `JustSearch`) is **verified by construction, not by execution**.

### Verified-here vs needs-private-repo-live-verification

| Claim | Status |
|---|---|
| Eval launcher defaults models to worktree `<repoRoot>/models` | ✅ verified (build.gradle.kts:1918-1920) |
| `JUSTSEARCH_MODELS_DIR` reaches Worker discovery | ✅ verified (WorkerSpawner:403-407; 283 live) |
| Reranker resolves+auto-enables at `onnx/reranker` | ✅ verified (discovery + config + tests) |
| Pre-run + post-run engine-realized signals exist | ✅ verified (status proto, IndexStatusOps, provenance) |
| CE intent is readable per (mode,`--ce`), hybrid=server-default | ✅ verified (retriever/run/counterfactual) |
| **Composed: worktree resolver fix → reranker on → correct numbers** | ⚠️ **needs private-repo live run** (no binaries here) |
| Option K (`git lfs checkout`) materializes models | ⚠️ **needs private-repo** (no LFS here) |

### Remaining-work confidence: **7.5 / 10**

Axis 1 is low-risk and well-referenced (≈8.5): a localized default change mirroring an already-working
resolver, with propagation confirmed. Axis 2 is slightly lower (≈7): the data and CLI/test patterns
exist, but the guard's correctness hinges on the intent nuances above (hybrid-server-default;
chunk-vs-search reranker signal source) — a classic *wrong-gate* surface where a false-fire would block
correct leg runs. The 1.5-point haircut from a perfect score is almost entirely **U6**: the inability to
live-verify the composed loop in this repo means the final proof must happen in the private checkout.

---

## Implementation — 2026-06-30 (Python layer; worktree `worktree-644-eval-worktree`)

> Status: **implemented in the jseval Python layer; unit-validated.** The composed positive loop
> (worktree → models resolve → reranker on → corrected nDCG) is **not runnable in this public mirror**
> (no LFS, no model binaries — U6/§E) and is deferred to the private repo. No UI surface (jseval is a
> CLI), so no browser validation applies.

### Axis 1 — default-correct asset resolution (landed)
- `scripts/jseval/jseval/_paths.py`: added `main_repo_root()` (worktree→main via the `.git`-file
  `gitdir:` walk, mirroring `dev-runner.cjs:32-46`) and `shared_models_dir()` (prefer `<main>/models`,
  else `<repoRoot>/models`, else `None` — mirroring `dev-runner.cjs:428-434`). Extends the file's
  existing worktree detection rather than adding a parallel resolver.
- `scripts/jseval/jseval/backend.py::start_backend`: in the env block, default `JUSTSEARCH_MODELS_DIR`
  to `shared_models_dir()` **only when unset** (lowest precedence; caller/env/run-config always wins).
  Flows to the Worker via Gradle's `env(JUSTSEARCH_MODELS_DIR)` top-precedence + `WorkerSpawner`'s
  blanket `JUSTSEARCH_*` forwarding.

### Axis 2 — fail-closed instrument integrity (landed)
- `scripts/jseval/jseval/preflight.py`: added `derive_intended_engines(modes, *, cross_encoder)` —
  **scoped to the reranker** (intended on `--ce` OR a CE-bearing server mode `hybrid`); dense/SPLADE are
  deliberately NOT hard-gated because their presence isn't startup-observable (live-debug finding) — and
  `assert_capabilities(status_or_url, intended, *, allow_degraded)` (reads the startup-stable realized
  signal `rerankerModelPath`; device is a warning-only dimension, never a refusal — GPU deferred).
  Extends `preflight`'s existing `execute_preflight` + errors-list pattern.
- `scripts/jseval/jseval/commands/_common.py`: added the shared `assert_run_capabilities(...)` helper
  (derives intent, reads realized, prints warnings, `sys.exit(1)` on an un-overridden refusal). Wired
  into `commands/run.py::_run_iteration` (top of the `try`, before the expensive ingest, inside the
  backend-stopping `finally`) and `commands/corpus.py::cmd_corpus_fidelity`. Added a `--allow-degraded`
  flag (default OFF = fail closed) to both `run` and `corpus-fidelity`. (Code lives under `commands/`
  after the tempdoc-645 cli.py split; the original change targeted the pre-split `cli.py`.)
- `scripts/jseval/jseval/readiness.py`: **`flatten_status` now flattens `worker.gpu`** — without this
  the realized-engine signals (`rerankerModelPath`/`spladeModelPath`/`embedBackend`/`*OrtCuda`) read as
  `None` and the guard refused every run (see Validation: the live-debug bug). Also repairs `preflight`'s
  GPU/`model_wiring` section, which was silently blind to the same omission.

### Validation evidence
- **Unit (guaranteed tier) — green.** New `tests/test_paths.py` (resolver: worktree→main, fallbacks,
  models-dir preference), extended `tests/test_backend.py::TestStartBackendModelsDirResolution`
  (defaults when unset, caller wins, none-resolvable), extended `tests/test_preflight.py`
  (`TestDeriveIntendedEngines` + `TestAssertCapabilities`: present→ok, CE-intended-but-absent→refuse,
  `--allow-degraded`→warn, leg-mode→no-refuse, CPU→warn, unreachable→refuse) **plus nested
  `worker.gpu` regression tests** + the `readiness.flatten_status` `gpu` fix. Targeted run: 73 passed.
  Full `jseval` suite after all fixes: **1034 passed, 2 failed**; one earlier failure
  (`test_corpus_governance::test_fidelity_does_not_clobber_existing_memory_independence`) was a *true
  interaction* — that test mocks the pipeline but has no backend, so the new guard correctly refused;
  fixed by neutralizing the guard in that merge-logic test (it has dedicated coverage in
  `test_preflight`). The remaining 2 failures
  (`test_correction_probe::TestLoadManifest`) are **pre-existing and unrelated** — a missing
  `scripts/jseval/jseval/data/correction-eval-queries.v1.json` (this public mirror is stripped of that
  data file; logged to the observations inbox). My diff touches neither.
- **Live CLI (real argparse → guard wiring, dead backend) — verified.** Real `jseval run` (no mocks):
  `--modes hybrid` → `Capability refusal: backend_unreachable` + exit 1; `--modes lexical` → 0 refusals
  (correct no-op, no false-fire); `--modes hybrid --allow-degraded` → 2 warnings, 0 refusals (downgrade
  works). Confirms the flag, intent derivation, and exit path fire in a real invocation.
- **Live full-backend, real Worker — VERIFIED, and it caught a real bug.** Running the positive path
  (after fetching the verified reranker weight — see below) exposed a defect that the unit tests and the
  negative path had both masked:
  - **Bug:** `readiness.flatten_status` flattened `worker.{core,enrichment,…,searchConfig}` but **not
    `worker.gpu`** — yet the realized-engine signals (`rerankerModelPath`, `spladeModelPath`,
    `embedBackend`, `*OrtCuda`) live under `worker.gpu`. So `status.get("rerankerModelPath")` was
    **always `None`**, and the guard refused *every* CE-intended run — even with the reranker loaded.
    The negative path "passed" for the **wrong reason** (it refused because the field was *always* None,
    not because it detected absence); the unit tests masked it by feeding already-flat dicts. This is a
    pre-existing latent bug — `preflight`'s entire GPU/`model_wiring` section had been silently blind too.
  - **Fix:** added `"gpu"` to `flatten_status`'s sub-record list (additive, collision-guarded;
    `readiness.py`). Repairs the guard *and* preflight. New regression tests feed the **real nested
    `worker.gpu` shape** through `assert_capabilities` (would fail pre-fix).
  - **Negative path (model absent), live:** `Resolved JUSTSEARCH_MODELS_DIR=…\models` → `Backend
    healthy` → `Capability refusal: reranker_intended_but_absent` + exit 1.
  - **Positive path (model present), live — same command, A/B:** `Resolved …\models` → `Backend
    healthy` → **`Capability warning: reranker_cpu_only`** + **no refusal, guard passes**. The worker
    log confirms the chain: `justsearch.models.dir=F:\justsearch-public\models` → `ONNX model
    'reranker': found at …\onnx\reranker` → `CrossEncoderReranker initialized`. This validates Axis 1
    (worktree→main resolution + propagation to the real Worker), Axis 2 (correct realized-signal read,
    present→pass / absent→refuse), and the device dimension (GPU-absent → warning, not refusal).
  - **How the weight was obtained here:** a fresh `justsearch-public` tree carries model **metadata
    only** by design (not a cutover defect) — ONNX weights are GitHub-Release artifacts
    (`eliasjustus/justsearch-releases`, tag `models-v1`), chat GGUF on HuggingFace, fetched at install
    by `AiInstallService`/`InstallPlanner` per `DownloadProfile`. For this validation the reranker
    `model.onnx` (FP32 CPU, 340,858,200 B) was downloaded from the release URL and **sha256-verified**
    against the registry pin (`CCF51DBA…`) into `<main>/models/onnx/reranker/`.
- **Still deferred — full nDCG *equivalence*.** Proven: the reranker turns ON in a worktree (guard
  passes, CrossEncoder initializes). Not yet measured here: that a complete worktree hybrid eval yields
  the **same nDCG@10** as a main-checkout run (needs the embedding+SPLADE weights materialized + a full
  ingest+query of a corpus, twice). This is now a numeric-equivalence check, not a "does it work" check —
  runnable in any tree once all encoder weights are present.

### Scope notes / deferred (unchanged)
- Manual `gradlew runHeadlessEval` from a worktree still defaults to the worktree's `models/` (the fix
  is in the jseval launcher, the supported eval entry point); workaround `JUSTSEARCH_MODELS_DIR=<main>/
  models`. Hardening the Gradle default is a possible follow-up.
- GPU/`cuda12` resolution deferred (device recorded/warned, not refused). Cross-run comparison-gate
  homogeneity (relevance/perf/leak) recognized in §Reach, not built.

---

## Future directions — post-implementation research (2026-07-01)

> Pure research/ideation pass after 644 shipped (PR #15). Goal: catalogue what the implemented
> mechanism *enables* — polish, simplify, extend, new UX, and the broader principle — grounded in (a) a
> codebase survey (3 parallel Explore agents) and (b) an industry survey (web). **Nothing here is
> committed beyond this doc; all items are options, no priority is forced.** Every codebase claim is
> `file:line`-anchored so a future implementer starts from facts. The app has **no users yet**, so
> these are free-to-pursue improvements, not fixes.

### What 644 actually produced (two reusable things, not just a fix)
1. **Worktree/environment-parity asset resolution** — resolve the main checkout's shared assets from a
   linked worktree (`_paths.main_repo_root`/`shared_models_dir`).
2. **An "instrument-integrity" pattern** — a measurement run refuses to emit numbers when the *realized*
   engine set ≠ the *declared* one (`preflight.assert_capabilities`) — plus the realized-engine signal
   it surfaced by teaching `readiness.flatten_status` to expose `worker.gpu.*`.

### Industry grounding (what the web survey established)
- **The principle has two established names.** Axis 2 is a **preflight check** (aviation / distributed
  systems / a literal "preflight checks for hardware accelerators" patent — *assert the environment;
  halt with a meaningful message; make no changes unless all checks pass*) and the eval-side twin of a
  **Kubernetes readiness probe** (*declare ready only when the model is loaded; 503 otherwise*). jseval
  already has a `preflight.py`; the framing fits.
- **644's fail-closed stance is *stronger* than the industry norm.** Experiment trackers (MLflow, W&B)
  *record* run conditions (git sha, env, GPU utilization, data version) so runs compare "on equal
  footing" — but they **display and let humans notice**; they rarely **hard-refuse** a non-comparable
  comparison. Keeping 644's refuse-don't-warn posture is a deliberate, defensible upgrade.
- **The silent CPU-fallback I hit is a known, unsolved class.** ONNX Runtime's `get_available_providers`
  reports *compile-time* providers, so `CUDAExecutionProvider` is "silently dropped without warning";
  the documented mitigations (`disable_cpu_ep_fallback=True`, or check `get_providers()` post-load; ORT
  feature-request #27177 for `get_loadable_providers`) are exactly 644's philosophy. Ollama / LM Studio
  have the *same* GPU→CPU silent-fallback UX gap (debug-logs only; `ollama ps` shows a GPU/CPU split) —
  so a user-facing "what's loaded and where" indicator is a recognized, poorly-served need.
- **IR-eval reproducibility is an active field** (repro_eval, Pyserini reference runs, the IR Experiment
  Platform, the BEIR leaderboard) — all about *standardizing the eval environment*, validating the
  "one resolved contract" half.

### Axis A — Polish / simplify (codebase survey)
- **A1 (latent same-class bug; high-value/low-effort).** `readiness.flatten_status` flattens a
  hardcoded allow-list of worker sub-records (`readiness.py:480-482`) that has **10** entries, but the
  wire contract has **11** dict sub-records — **`visualExtraction` is omitted** (`contracts/wire/
  status.proto:126`; `WorkerOperationalView.java:19-30`), so its OCR/visual fields read as `None` at top
  level — the *exact* class of bug 644 fixed for `gpu`. Fix: make `flatten_status` **data-driven**
  (flatten every dict-valued `worker` child) **+ a key-collision assertion** (today the allow-list only
  "works" because leaf views are prefixed, e.g. `chunkDocCount` not `docCount`) **+ a completeness test**
  driving all 11 sub-records (the missing test is why the omission recurred — `audit-without-test`).
- **A2 (resolver triplication, live drift).** The worktree→main `.git`-walk exists three times:
  `dev-runner.cjs:32` (JS), `_paths.py:56` (Python), `build.gradle.kts:1818` (Kotlin). JS+Python resolve
  a *relative* `gitdir:` against the repo root; **Kotlin treats it as absolute** (`build.gradle.kts:1828`)
  — a real divergence that breaks on a relative gitdir. Also `_paths.py:90`/`backend.py:100` hardcode
  `dev-runner.cjs:428-434` line-refs that will rot. Fix: cite by *symbol* not line; align the Kotlin
  relative-path handling; let JS↔Python share where feasible.
- **A3 (preflight consolidation).** `assert_capabilities` (`preflight.py:326-336`) and
  `execute_preflight`'s `model_wiring` (`preflight.py:108-135`) independently re-derive "is engine X
  loaded" from `/api/status` with **different predicates** (`*_wired` keys off `attempted`/`configured`;
  `realized` keys off model-path-non-empty) and each separately `_fetch_endpoint`+`flatten_status`. Fold
  into **one "realized-engine reader"** (fetch+flatten+realize once) consumed by both.
- **A4 (minor).** Dead `dense`/`splade` branches in `assert_capabilities` now that
  `derive_intended_engines` is reranker-only (`preflight.py:285-287` vs `:334-348`) — document as
  forward-compat or drop; `flatten_status` mutates its argument in place; `_fetch_status`/`_fetch_endpoint`
  near-duplicates; add the missing server-mode `derive_intended_engines` test.

### Axis B — Extend the principle to the consumption point (the §Reach "recognized-not-built", now concrete)
- **B1 (flagship; high-value, tiny change).** The run-comparison machinery never re-checks the guard,
  and the cohort identity is **blind to the reranker**: `run._snapshot_models` (`run.py:44-64`) folds
  `embed`/`splade`/`ner` model+device into `model_fingerprints` (which *is* in the cohort hash,
  `manifest.py:294`) **but not the reranker** (verified: no `reranker_model_path`/`reranker_gpu`). So two
  `hybrid` runs — one with the reranker loaded, one in a worktree where it silently fell off (the exact
  644 trap) — get the **same `manifest_hash`** and are **silently averaged into one cohort** by
  `history`/`calibrate`. **Fix: add `reranker_model_path` + `reranker_gpu` to `_snapshot_models`.** Since
  `model_fingerprints` is in the cohort hash, CE-on/off and reranker-CPU/GPU then get **distinct cohort
  identities by construction**, and every hash-keyed consumer (`calibrate.py:315` stability abort,
  `history.py:249,332` windowing, `bisection.py:70` axis) separates them for free. The data already
  exists at the guard point (`preflight.py:326,329`).
- **B2.** `gate.evaluate` validates a σ-envelope from one cohort against a run from another without
  asserting `manifest_hash == cohort_hash` even though it holds both (`gate.py:108` vs `:160`). Add the
  equality assertion.
- **B3.** Thread the realized engine set (`component_status_counts`, `provenance.py:186`) into
  `comparability.determine_comparability` (`comparability.py:8-37`) so the per-run `comparable` flag —
  the one signal every trend/history consumer already filters on — turns **false** when realized ≠
  intended. Closes the consumers that ignore `manifest_hash` entirely (`compare_runs`, `diff_gate`,
  `perf_gate`, `leak_gate`).
- **B4 (worker-level, externally-validated).** Adopt the ONNX mitigation at the encoder/session level —
  `disable_cpu_ep_fallback` or a post-create `getProviders()` check — so a **silent CPU fallback** (the
  root cause of the cross-encoder DEADLINE_EXCEEDED skip I observed) becomes a **loud, surfaced** signal
  at load time instead of a quiet quality cliff. This is the "make the instrument honest about its own
  device" complement to Axis 2.

### Axis C — New UX: surface the realized engine set to users (codebase survey of the Lit `shell-v0` FE)
> The realized-engine signals 644 exposed (`worker.gpu.{embedOrtCuda,spladeOrtCuda,rerankerOrtCuda}`,
> `embedBackend`, `crossEncoderSkipReason`) are **read nowhere in the production FE today** (only in
> generated/fixtures) — the Health chips are *presence-only* (the reranker chip = "model configured",
> not "loaded / on GPU", `display/facts.ts:154-164`). So there's a clean, additive opportunity.
- **C1 (low).** Upgrade the Reranker/SPLADE capability chips from **presence → placement** (GPU·CUDA vs
  CPU vs absent) from `*OrtCuda.available` (`display/facts.ts:154-176`; chips already declared in
  `builtinPresentations.ts:413-414`).
- **C2 (low).** A compact **GPU/CPU "ranking on …" badge** in the search header next to the
  retrieval-mode pill (`views/SearchSurface.ts:1163-1169`).
- **C3 (low).** **Failure-reason tooltips** — surface `*OrtCuda.failureReason` ("CUDA provider failed:
  missing cudnn64_9.dll") via the existing `ProjectedFact.provenance` channel (`display/facts.ts:46-47`).
- **C4 (medium).** A dedicated **"Retrieval engines" health card** — Embedder / SPLADE / Reranker /
  Cross-encoder, each with loaded? · placement · failure-reason — beside `renderGpu`
  (`views/HealthSurface.ts:1135`).
- **C5 (medium).** Promote **CE-skipped / reranker-on-CPU** from the dev-only diagnostic disclosure
  (`searchTraceExplain.ts:232-262`) into a user-facing **"results may be degraded" notice** (add
  `crossEncoderSkipReason` wording to `DEGRADATION_REASON_WORDING`; respects the
  `check-search-degradation-reason-codes` gate). Mirrors the Windows-search "results may be incomplete"
  transparency pattern.
- **C6 (medium).** An **Install-AI capability-completeness checklist** in `BrainSurface`
  (`views/BrainSurface.ts:1766`) showing configured-vs-realized per engine (`*OrtCuda.configured` vs
  `.available`) — so a user sees which engines the install *actually realized*.
- Lowest-effort/highest-leverage starter set: **C1 + C2 + C3** (all reuse existing chip/pill/provenance
  machinery), then **C4** as the dedicated panel.

### Axis D — The principle, named, with candidate scope (recognize ≠ build)
> **Instrument integrity under a single resolved contract:** *a measurement-or-serving instrument must
> run under the configuration it declares, or refuse to report — and that configuration must come from
> one resolved contract consumed by every launcher/consumer, never per-launcher convention.* It is the
> fusion of "verify, don't guess" + "interrogate results" applied to the harness itself, an instance of
> the established **preflight-check** and **readiness-probe** patterns, and the same fail-closed shape as
> the codebase's existing schema-fingerprint refusal (`embeddingCompatState`) applied to the *runtime
> engine* dimension instead of stored vectors.

Where the principle applies (candidate scope; existing code that violates it is flagged, not yet fixed):
- **Eval consumption point** — the comparison gates (Axis B). *Violates today* (B1-B3).
- **Worker EP selection** — silent CPU fallback (B4). *Violates today* (ONNX default fallback).
- **The UI** — the realized state exists but isn't shown (Axis C). *Gap today.*
- **Worktree parity is a boundary, not a backlog** (the narrower recurring shape, 283 → 618 → 644): the
  `flatten_status` omission (A1) and resolver triplication (A2) are themselves parity leaks. Whether the
  trend now justifies a small "worktree-parity register" (asset-class × dependent-workflow) is the open
  judgement — recorded, not built.

---

## Long-term design for the future-directions cluster — SETTLED 2026-07-01

> The §Future-directions catalog above is a list of *symptoms*. This section settles the *one* long-term
> design they share. Investigation (3 codebase agents + reading the adjacent records seam: 549/553/623,
> 636, 640, 613). **General design only — no implementation this pass.** The size below is an outcome of
> matching scope to the real defect, not a target.

### The one defect under Axes A–C: a forked "realized-capability record"
"Realized capability state" — *which retrieval engines actually loaded, on which device* — already has a
**single canonical source**: the backend `GpuDiagnosticsView` / `OrtCudaView` record exposed at
`/api/status` under `worker.gpu` (producer chain `GpuDiagnosticSuppliers` → `IndexStatusOps` →
`WorkerStatusMapper` → `GpuDiagnosticsView`; wire `contracts/wire/status.proto:267-274`). It is a
*backend-capability* concept, **distinct** from the per-query `SearchTrace` (which 549/553 already made
canonical + governed). The defect is that this record is **read as a fork by ≥5 consumers, each with a
different "is engine X realized" predicate**:

| Reader | Predicate | file:line |
|---|---|---|
| `preflight.execute_preflight` model_wiring | `*OrtCuda.attempted` OR model-path | `preflight.py:107-135` |
| `preflight.assert_capabilities` (644 guard) | model-path / backend non-empty | `preflight.py:290-360` |
| `run._snapshot_models` (cohort identity) | `*OrtCuda.available` (device) — **reranker omitted** | `run.py:44-64` |
| `provenance` component_status_counts | per-query `SearchTrace` stage executed (different concept, conflated) | `provenance.py:60-64,186` |
| FE `display/facts.ts` chips | reranker=model-path · splade=`enrichment.spladeEnabled` flag | `facts.ts:140-176` |

They **already disagree** (reranker: key-presence vs path vs device vs absent), and the cohort-identity
reader is reranker-blind — the live consequence 644's guard exists to prevent, reappearing one layer
down. Unlike SearchTrace and unlike FE affordance-availability, this backend record has **no register
and no fork-prevention gate** (`governance/` has `execution-surfaces` for SearchTrace and
`capability-availability-surfaces` for the FE `projectAvailability` authority — but nothing rooted on
`GpuDiagnosticsView`).

### The design: one governed realized-capability projection (conform to the existing seam — do not fork it)
This is **not a new subsystem.** It is the *capability/condition sibling* of the move 640 made for perf
("promote it into the canonical record; the ratchet/projection/calibrate/history/scorecard then follow
through the path quality already uses") and the move 553 made for SearchTrace ("one record, every surface
a governed projection, a gate stops the next fork"). Four parts, in scope order:

1. **One realized-capability projector (the single authority).** A pure function of the canonical
   `worker.gpu` record, defining *one* "realized" predicate and *one* device read. The five forks above
   collapse to projections of it: both preflight readers (A3), the cohort-identity read, and the FE
   chips. This subsumes the `flatten_status` allow-list drift (A1) — one *complete* reader replaces the
   hand-curated list, so the `gpu`→`visualExtraction` omission class cannot recur.
2. **Make the realized-engine set first-class in the canonical *measurement* record's identity** (the run
   manifest's cohort hash) — the 640 move applied to the *identity/conditions* half rather than the
   *metric* half. Capturing the reranker + a normalized device read there (B1) gives CE-on/off and
   CPU/GPU runs distinct cohort identities **by construction**, so the comparability verdict (B3), every
   hash-keyed consumer (`calibrate`/`history`/`bisection`), and the gates' `manifest==cohort` assertion
   (B2) separate non-homogeneous runs *for free*. "Realized conditions are first-class identity in the
   record" is the homogeneity guarantee 640's release-projection/calibrate-envelope already assume.
3. **FE capability/health surfaces project from the same authority.** Extend the existing 613 seam
   (`projectAvailability` + `governance/capability-availability-surfaces.v1.json`) so the new engine/
   device UX (C1–C6) *and* `projectAvailability` consume the one realized-capability projector instead of
   re-reading raw `worker.gpu`/`enrichment.*Enabled` (the 613-named fork-class) — and so availability
   wording and the engine chips can no longer diverge.
4. **Source integrity is the precondition** (B4). The projection is only as true as its source, so the
   backend must report the realized device honestly — surface a silent ONNX CPU fallback as a
   realized-state field (`disable_cpu_ep_fallback` / post-create `getProviders()`), the exact garbage-in
   the deadline-skip episode exposed.

**Drift protection (scope judgement).** The durable completion is a `capability-surfaces` register +
fork gate rooted on `GpuDiagnosticsView`, mirroring `execution-surfaces` for SearchTrace, so a 6th fork
fails the build. The fork has *already bitten* (5 disagreeing predicates + the reranker-blind cohort) —
553's exact "this stops the next fork" trigger — so the register+gate is **warranted to land with the
consolidation** (1–3), not after a future incident. The *generalized* shared kernel across all such
records is **not** this design's to build (see Reach / 625).

**Scope match & sequencing.** Parts 1–4 are a consolidation of an existing record onto one projection,
not a new measurement pipeline; each fork migrates to the projector independently, behind the seam
(553's "unification proceeds incrementally behind the gate"). The smallest first step that pays for
itself is **B1** (reranker into the cohort identity) — one field, amplified by machinery that already
keys on the hash. Like 640 (which spun out of 636's gap), this design is substantial enough to spin into
its own tempdoc if pursued; it is recorded here because it is 644's direct reach.

### Reach — the principle, and where it already applies
**This conforms to a seam that exists in three places already — it must not become a fourth parallel
version.** The "canonical record + governed projections + fork-prevention gate" seam: **553/549/623**
(SearchTrace), **640** (perf first-class in the measurement record), **636** (recall as an eval
projection), **613** (`projectAvailability`). The realized-capability record is the one sibling of this
family that still lacks the projection+gate; the design wraps the *existing* `GpuDiagnosticsView` source
using `projectAvailability` as the template.

**The principle, named plainly:** *Realized state is a single-authority projection of one canonical
record, never an independent re-derivation — and the conditions under which a measurement was taken are
first-class identity in that record.* It is the representation-drift/DRY principle (553) fused with the
canonical-measurement-record principle (640), and an instance of **tempdoc 625's** generalization
("every asserted measurement is a projection of a reproducible run; every projection of the same record
is governed") that 640 deferred until "the fork bites again." The capability fork *has* bitten — so this
is a further witness for 625, **not** a license to build 625's generalized kernel here.

**Candidate scope — where the principle applies, and existing violations (flagged, not fixed):**
- The 5 forks in the table above are the live violations (Axes A–C resolve them).
- The two-GPU-objects confusion — surfaces reading top-level `status.gpu` (inference GPU) vs
  `worker.gpu.*OrtCuda` (retrieval engines) interchangeably (`facts.ts:125-138` vs the engine reads) — is
  a sub-fork of the same record family.
- 640 already records the principle violated for perf (fixed), extraction-quality (F-009, open), and a
  ratchet that doesn't auto-run; this capability record is the next un-consolidated sibling.
- **625** is the generalized structure (shared register/gate kernel + asserted-measurement-provenance) —
  recognized, deferred; this design feeds it as one more witness, scope-matched to the one sibling the
  present problem requires.

**A genuinely separate shape (do not fold in):** the worktree-parity boundary (resolver triplication A2,
the parity-leak framing of A1) is the 283→618→644 "a worktree inherits tracked text but not built/LFS/
untracked assets" shape — orthogonal to the realized-capability record. It keeps its own §Reach note;
merging the two would be a false unification.

---

## Implementation — realized-capability design SHIPPED 2026-06-30

The settled design above is implemented (branch `worktree-644-capability`, 4 staged commits),
conforming to existing seams (extended, not forked). What landed:

- **A3 — one projector.** `preflight.project_realized_capability(status) → {engine: {present, device,
  detail}}` for reranker/dense/splade (+ `realized_engine_set`). `assert_capabilities` (the 644 guard)
  and `run._snapshot_models` both project from it. **Deviation from the plan:** `execute_preflight`'s
  `model_wiring` was *left intact* — its predicate is the stricter "ORT-status present" with an
  `init_failed` diagnostic that depends on the path-vs-status distinction; collapsing it onto the
  projector's path predicate would *regress* that diagnostic (AHA — only unify what shares a reason to
  change). So A3 unified the two genuinely-shared forks, not all three.
- **B1 — engine set first-class in cohort identity.** `_snapshot_models` records `realized_engines`
  (sorted present-engine list) + `reranker_model_path`/`reranker_gpu`. `realized_engines` +
  `reranker_model_path` enter `model_identity` (CE-on vs CE-off ⇒ distinct cohorts); the flaky device
  bit `reranker_gpu` joins `release._MODEL_EXECUTION_FLAGS` (stripped, like embed/splade_gpu).
- **A1 — data-driven `flatten_status`** (iterate worker children, one-level, sibling-collision assert);
  surfaces the formerly-omitted `visualExtraction` automatically.
- **B2/B3 — engine-set homogeneity gate.** `ratchet_kernel.assert_cohort_engines` — relevance/perf/leak
  refuse (exit 2) to compare a run whose `realized_engines` ≠ the baseline release's
  (`cohort.realized_engines`); keyed on the **narrow engine set** (git_sha-independent), backward-compat
  skip when unrecorded, `--allow-engine-mismatch` override. Mirrors `run_dataset_ok` + calibrate's
  cohort raise.
- **C — FE realized-capability surface.** `aiStateStore.computeRealized()` (exported authority) →
  `aiState.realized`; HealthSurface's AI Engine card gains a "Retrieval engines" block (per-engine
  GPU/CPU pill + failure tooltip). A light register `governance/realized-capability-surfaces.v1.json`
  + `scripts/ci/check-realized-capability.mjs` (the 613 capability-availability template) prevents a
  re-fork; wired into the CLAUDE.md shell-v0 pre-merge row.
- **B4 — source integrity** was already met (`*OrtCuda.{available,failureReason}` exist); the
  projections surface it.

**Validation (all tiers).** `gradlew build -x test` SUCCESSFUL; full jseval pytest green (the 2
`test_correction_probe` failures are a pre-existing gitignored data file); full ui-web unit suite (3401)
+ typecheck green; the applicable ui-web purity/token/a11y gates pass (`theme-token-closure` /
`accent-as-text` reds are pre-existing in `RecentsMenu.ts` / `ActionLedgerView.ts`, logged to the inbox).
**Live:** a worktree-resolved backend reported `realized_engines = [dense, reranker, splade]` with the
reranker on GPU; and the **real browser** (FE served against that backend) rendered the AI Engine card's
"Retrieval engines" block — **Reranker `GPU`**, Embeddings/SPLADE `loaded` (device unprobed ⇒ honest
"loaded", never a false "CPU"), confirming the tri-state device logic end-to-end.
