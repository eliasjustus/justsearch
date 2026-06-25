---
title: "591 — Dependency hygiene triage: why the 10 open Dependabot PRs can't be band-aided, and the systemic root cause. An autonomous investigation of the open Dependabot backlog (Gradle plugins, GitHub Actions, Cargo/Tauri, npm) that finds the PRs are structurally unmergeable — not because of the individual bumps, but because the development line (local `main`) is 125 commits ahead of `origin/main` and unpushed, so Dependabot perpetually rebases against ancient code. Triages each PR, identifies the low-collision/high-collision/unverifiable subsets, and recommends the root fix (sync origin) over the manual cherry-pick treadmill."
status: implemented-mostly (2026-06-16) — per user direction, applied the still-applicable bumps directly to local main. LANDED (merged 5b20fbf2e…016d37a07): gradle build-tool plugins + kotlin 2.4.0 + dependency-analysis 3.15.0 + spotless 8.6.0, npm-dev, npm-shell (vite 8), GitHub Actions, and **npm-frontend 27/30 (vite 8, lingui 6, fast-check 4, …) — real-browser-validated (search + RAG chat, zero console errors)**. DEFERRED with verified reasons (§7): protobuf-plugin (task-wiring regression), gradle-wrapper (Windows jar-lock at merge), cargo-shell (local env can't compile Tauri), and FE TypeScript 6 + eslint 10 (separate migrations). The pre-existing `ui-bundle-budget` red is FE-feature-growth (this bump *reduces* the bundle ~38KB). Origin-sync + closing the stale PRs remain the user's call.
created: 2026-06-15
updated: 2026-06-16
---

# 591 — Dependency hygiene triage

## 0. Thesis

There are 10 open Dependabot PRs. The naive action ("merge the safe ones") is **not viable**, and
the reason is systemic, not per-PR: **local `main` is 125 commits ahead of `origin/main` and is never
pushed**, so Dependabot — which rebases against `origin/main` — produces PRs based on *ancient* code.
`git diff origin/main origin/dependabot/<branch>` for every PR shows hundreds-to-thousands of files
changed with massive deletions (recent governance scripts, FE surfaces) — i.e. the PR branches
predate large swaths of the live tree. Merging any of them would revert work. The individual bumps
*are* still applicable (local `main` is verified still on the old versions), but the delivery vehicle
is broken. The high-value fix is to **make Dependabot useful again** (sync `origin/main`), not to hand
cherry-pick stale bumps onto a 125-commit-ahead tree while three other agents are actively working it.

## 1. Method / evidence

- `git rev-list --count origin/main..main` = **125**; `main..origin/main` = **0**. Local `main`
  (dev line) strictly contains `origin/main` and is 125 commits ahead, unpushed.
- `gh pr list` → 10 open Dependabot PRs (below), all `mergeable: UNKNOWN` (GitHub hasn't recomputed
  against the stale base).
- Per-branch `git diff origin/main origin/dependabot/*` shows the branches are based on bases as old
  as 2026-06-12 (gradle PR merge-base) and earlier — predating recent scripts — so the two-dot diff
  is dominated by *reverted* live work, not the bump.
- Local `main`'s current versions were read from `gradle/libs.versions.toml` /
  `gradle/wrapper/gradle-wrapper.properties` / `.github/workflows/*` and confirmed still-old (so the
  bumps are real, not obsolete).

## 2. Per-PR triage

| PR | Group | What it bumps | Applies to local main? | Landing risk |
|----|-------|---------------|------------------------|--------------|
| #52 | gradle-deps (10) | spotless 8.3.0→8.6.0, kotlin.jvm 2.3.10→2.4.0, protobuf-plugin 0.9.6→0.10.0, dependency-analysis 3.6.1→**3.15.0**, gradle-wrapper 9.4.0→9.5.1, spotbugs 6.4.8→6.5.6, openrewrite 7.28.1→7.34.0, develocity 4.3.2→4.4.2, license-report 3.1.1→3.1.4, ben-manes 0.53.0→0.54.0 | Yes (all still old) | **Shared build-infra.** A spotless bump can reformat the repo-wide tree → conflicts with the 3 active worktrees; kotlin / dependency-analysis(9-minor jump) / protobuf-codegen / gradle-wrapper affect everyone's build. Build-verifiable but high-collision during active parallel work. |
| #67 | cargo-shell (10) | Rust/Tauri `modules/shell/src-tauri` deps | (Tauri shell) | Needs Rust toolchain to verify; touches the installer/shell. Defer. |
| #59 | npm-shell (7) | `modules/shell` npm (Tauri) | (Tauri shell) | Needs npm + shell verification. Defer. |
| #55 | npm-dev (4) | dev-only npm deps | (dev tooling) | Low product risk but needs npm verify; defer with the npm cluster. |
| #71 | npm-frontend (30) | `modules/ui-web` deps | (FE) | **Skip — direct collision** with the active shell-v0 work (`ui-web/package-lock.json` is already dirty in `main`); 30 updates = high churn. |
| #58 | github-actions | github/codeql-action 3→4 | (CI) | **Major** bump; CI is manual-only (ADR-0026) so unverifiable until dispatch; blind landing risks the next CI run. |
| #54 | github-actions | actions/cache 4→5 | local still v4 | Major; CI-unverifiable (as above). |
| #49 | github-actions | actions/upload-artifact 4→7 | local has both v4 **and** v7 | Major, multi-version skip; partially already applied; CI-unverifiable. |
| #53 | github-actions | softprops/action-gh-release 2→3 | local still v2 | Major; CI-unverifiable. |
| #41 | github-actions | dorny/test-reporter 2→3 | local still v2 | Major; CI-unverifiable. |

## 3. Why not just land the "safe" subset now

- **Gradle (#52):** the genuinely-verifiable category — but it is *shared build infrastructure*. A
  `spotless` version bump in particular tends to reformat the whole tree on the next `spotlessApply`,
  which directly contradicts the parallel-agent rule "untouched-code reformatting causes merge
  conflicts with other worktrees." Landing build-infra churn while 3 agents are mid-flight is the
  wrong time. (It is fine to do during a quiet window, or — better — via the root fix below.)
- **GitHub Actions (5):** all *major* bumps. CI is manual-only (ADR-0026), so I cannot verify them;
  landing them blind risks breaking the team's next dispatch for zero immediate benefit.
- **Cargo / npm-shell / npm-dev:** require their toolchains to verify and touch the installer/FE;
  out of cheap-verification reach.
- **npm-frontend:** direct collision with active FE work.

So every category is either unmergeable-as-PR, collision-risky to hand-apply right now, or
unverifiable — which is exactly why the per-PR approach is the wrong altitude.

## 4. Recommendation (the root fix)

1. **Sync `origin/main`** with the local dev line (push the 125 commits, or whatever the team's
   release cadence is). This is the actual fix: Dependabot then rebases against current code and
   regenerates **clean, mergeable** PRs, each verifiable on a real (manual) CI dispatch. This is a
   team/release decision (pushing 125 commits to a shared remote is outward-facing) — **not taken
   autonomously here.**
2. After the sync, land the regenerated PRs on their normal cadence: Gradle during a quiet window
   (so the spotless reformat doesn't collide), GitHub-Actions verified on a CI dispatch, npm-frontend
   coordinated with the FE owners.
3. The current stale PRs can be **closed** once #1 happens (Dependabot will recreate them), or left
   for Dependabot to auto-close on rebase.

## 5. Offered follow-ups (await user direction — each is a discrete next move)

- **(A)** Push local `main` → `origin` to unstick Dependabot (root fix; needs explicit go-ahead —
  outward-facing).
- **(B)** When the parallel agents quiesce, verify + land the Gradle group (#52) in isolation
  (apply the 10 bumps to `libs.versions.toml` + wrapper, regenerate lockfiles, full `build` + gates),
  reverting any individual bump that breaks.
- **(C)** Verify + land the GitHub-Actions bumps paired with a manual CI dispatch to confirm them.
- **(D)** Close the 10 stale PRs as superseded-by-rebase.

## 6. Scope / non-goals

- No dependency versions were changed by this doc. No PR was merged or closed. No push to `origin`.
- This is the honest output of "interrogate results before acting": the investigation found the
  naive action (merge the safe PRs) to be unsafe/unviable, and surfaced the systemic cause instead.

## 7. Implementation outcome (2026-06-16) — bumps applied directly to local main, per-category verified

Per user direction, the still-applicable bumps were applied **directly to local `main`** (not via the
stale PRs), each category a verified commit in `worktree-591-deps`, merged collision-safe.

### LANDED on main (merged, `5b20fbf2e` … `016d37a07`)
| Category | Bumps | Verification |
|---|---|---|
| Gradle build-tool plugins (1a) | develocity 4.4.2, openrewrite 7.34.0, license-report 3.1.4, ben-manes 0.54.0, spotbugs-plugin 6.5.6 | `build -x test` + full `test` suite green; verification-metadata +120 |
| Gradle (1b) | kotlin 2.4.0 (+ ui kotlin-stdlib), dependency-analysis 3.15.0 | `build -x test` + full `test` green; DAGP clean |
| Gradle (1c) | spotless 8.6.0 | spotlessCheck green — **no reformat** (google-java-format stays pinned 1.25.2, so the plugin bump is rule-neutral) |
| npm-dev | @modelcontextprotocol/sdk 1.29.0, ajv 8.20.0, markdownlint-cli 0.48.0 | `npm install` + `check:llmstxt` OK |
| npm-shell | react/react-dom 19.2.6, @tauri-apps/* 2.11.x, **vite 8 + @vitejs/plugin-react 6** (majors) | `npm run build` (vite 8) green, 0 vulns |
| GitHub Actions | cache v5, test-reporter v3, gh-release v3, + consistency (upload-artifact v7, codeql v4) | `check-workflow-triggers` green |
| **npm-frontend** (27/30, `016d37a07`) | **vite 7→8, @lingui/\* 5→6, fast-check 3→4, knip 5→6, rollup-visualizer 6→7, virtua 0.48→0.49, @types/dompurify 3→3.2** + minors (lit, marked, ses, zod, dompurify, lumino, ts-eslint, playwright, axe, tailwindcss-postcss, autoprefixer, happy-dom, pixelmatch) | typecheck + vitest 3005/3005 + vite-8 build green; **REAL-BROWSER (dev stack + Qwen3.5-9B): search 10 results w/ facets+virtua-0.49 list; RAG chat streamed a grounded answer w/ rendered markdown; ZERO console errors.** TS6 + eslint-10 kept OUT (separate migrations) |

Sole failing test across the suite throughout = the **pre-existing `UnreferencedCodeTest`** (dead-code
ArchUnit check on `AgentSession.{budgetGateHeld,contextGateHeld}`, `RouteResponseSchemas.declaredSchemaFiles`,
`NdjsonAppendStore.storeFile` — from other agents' recent merges; `.java`-free in this work). **`main`'s
full `build` (with `test`) is therefore red on dead code unrelated to dependencies** — should be cleaned up
separately (the 4 methods either wired or removed).

### DEFERRED (verified blockers, not landed)
- **protobuf-gradle-plugin 0.9.6→0.10.0** — 0.10.0 task-wiring regression (`processProtoResources`
  consumes `extractProtovalidateProtos` outputs without a declared dependency → Gradle implicit-dependency
  validation failure). Kept at 0.9.6.
- **gradle-wrapper 9.4.0→9.5.1** — verified green on 9.5.1 (regenerated kotlin-dsl 6.5.7 checksums), but the
  ff-merge persistently failed to replace the binary `gradle-wrapper.jar` in main's worktree (Windows
  `EINVAL` unlink — a JVM held it; not a stoppable daemon). Highest-risk/most-collision-prone bump (forces
  all agents to re-download gradle). Dropped from the merge; land in a quiet window via the wrapper task.
- **cargo-shell group (#67)** — targeted `cargo update` applied correctly, but **the local environment
  cannot compile the Tauri crate tree** (base-state transitives fail: `windows-core 0.61.2`→`windows_interface`
  E0463, then `icu_collections` `core::error::Error` bound errors — toolchain/env, not the bump). The shell
  compiles in CI (`build-installer.yml` sets up the full Rust/Tauri toolchain). Verify + land via CI.
- **npm-frontend group (#71)** — **27/30 LANDED + browser-validated** (see the LANDED table, `016d37a07`).
  Three held back as separate follow-ups, not bumps:
  - **TypeScript 5.9.3→6.0.3** — a TS6 migration: 6.0 deprecates `baseUrl`, then surfaces node-global +
    `.ts`-import-extension errors in test files (confirmed TS6-specific — base TS 5.9.3 typecheck is green).
    Needs a tsconfig `types`/`allowImportingTsExtensions` migration.
  - **eslint 9→10 + @eslint/js 9→10** — 8 new errors from eslint-10's stricter recommended rules
    (`no-useless-assignment`, `preserve-caught-error`, …). Aside: `npm run lint` is **already red on main**
    (base eslint 9 has 19 pre-existing errors).
  - **`ui-bundle-budget` is pre-existing red, NOT caused by this bump** — measured: current main *without*
    the bump builds to **3,247,196** (> the 3,150,000 hard cap; the FE feature growth blew the stale
    3,114,467 baseline); *with* the bump it's **3,209,438**, i.e. the dep bumps **reduce** the bundle ~38 KB
    (vite-8 minification + ses shrinking outweigh lingui-6). A baseline refresh for the feature growth is a
    separate FE-owned maintenance task.

### Still the user's call (unchanged from §5)
- CI-run verification of the GitHub-Actions bumps needs a push (local main is unpushed; origin is stale).
- The root fix (push `origin/main`) and closing the 10 stale PRs remain outward-facing decisions.

## 8. Dependabot SECURITY ALERTS triage + fix (2026-06-16)

§2–§7 covered the 10 **version-bump PRs**. Separately the repo had **61 open Dependabot security
alerts** (41 medium / 14 high / 6 low). A severity×scope pass collapses the headline: **42 are
development-tooling** transitives (build/test only — near-zero user risk), leaving **19 runtime**,
of which only a handful are genuinely actionable and user-facing.

### Reframe (61 alerts)
| | runtime (ships) | development |
|---|---:|---:|
| high | 5 | 9 |
| medium | 9 | 32 |
| low | 5 | 1 |

Ecosystems: 50 npm, 7 pip (model-prep scripts), 4 rust (Tauri shell).

### LANDED — `worktree-591-deps-sec` (`f7aec4588`): the user-facing runtime fixes
| Package | Was → now | Alerts closed | How |
|---|---|---|---|
| dompurify | 3.4.3 → **3.4.10** | 4 moderate + 2 low (XSS) | raised the floor `^3.4.3`→`^3.4.10` (direct dep) |
| fast-uri | 3.1.1 → **3.1.2** (all copies) | 1 **high** (host confusion) | `overrides` pin `^3.1.2` (transitive via ajv) |

dompurify is the **sole** sanitizer, in `MarkdownBlock.ts` (RAG-chat markdown), default
`DOMPurify.sanitize()` — **`IN_PLACE` mode is unused**, so the one **no-fix** dompurify CVE
(IN_PLACE nodeName mutation) is **unreachable** here.

Verification: typecheck clean; vitest **3010 green** (incl. `MarkdownBlock.test.ts`, which runs
dompurify 3.4.10 under happy-dom — a real DOM); vite build green; **npm-audit-ratchet PASS**
(ui-web high 9→5, aggregate high 25→8). Diff is tight: 2 `package.json` lines + 14 lock lines.
Browser validation deemed disproportionate: this is a patch-level security pin, **not** a
user-visible feature, and the exact sanitize path is exercised green in unit tests.

**Correction to §7 line 109:** that row listed `dompurify` among the FE-bump "minors," but
`npm install` respects the existing lock for within-caret minors — it never moved dompurify off
3.4.3. So those 6 dompurify alerts were **not** fixed by `016d37a07`; **this** commit is what
actually closes them. (The same caveat applies to the other within-caret minors that row listed —
they only moved if the lock had no prior pin.)

### NOT in this fix (analyzed, deliberately scoped out)
- **onnx ×5 + transformers ×1 (pip, `scripts/models/requirements.txt`)** — high-CVSS but every
  advisory is about **processing a malicious model file**. JustSearch ships **pre-converted**
  models and never feeds user-supplied ONNX into conversion; only devs run these scripts on
  trusted models → low real exploitability. `onnx→1.21.0` is clean hygiene (no collision) worth
  doing opportunistically; `transformers→5.0.0rc3` is a **pre-release major** (a migration, not a
  bump) — skip.
- **tauri 2.11.2 (+ glib, rand) — rust** — the medium "origin confusion" desktop-shell vuln is
  fixed by the **deferred cargo-shell bump** (§7: local env can't compile Tauri; lands via CI).
- **42 dev-scope (esbuild, ws, picomatch, brace-expansion, js-yaml, …)** — build/test tooling;
  patch opportunistically when the parent tool bumps (vite/eslint/lingui/playwright). `npm audit
  fix` deliberately **not** run — it would balloon the lock diff (collision surface with the active
  FE agent) for low-value dev-scope alerts.

### Pre-existing, FE-owned (logged to observations, not fixed here)
- **`ui-bundle-budget` red on HEAD** — HEAD (post-FE-bump) builds to **3,209,438** > hard cap
  **3,150,000** *before* this change; the +2.6 KB dompurify patch is not the cause (measured both
  ways). A baseline refresh / bundle reduction for the shell-v0 surface growth is FE-owned.
- **npm-audit-ratchet baseline can shrink** (root high 16→3, aggregate 25→8) — a deliberate
  rebalance, deferred (spans root+ui-web; deltas dominated by accumulated drift, not just this fix).
