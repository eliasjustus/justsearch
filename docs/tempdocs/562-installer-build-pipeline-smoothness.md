---
title: "Installer build pipeline — making clean-environment builds smooth"
status: open
created: 2026-05-30
updated: 2026-06-25
parent: 374
related: [374, 375, 376, 226, 629]
---

# 562. Installer build pipeline — making clean-environment builds smooth

A roadmap for reducing the friction of producing a JustSearch NSIS installer.
Grounded in a real incident: the 2026-05-30 installer build (a single requested
"build a new installer" turned into a 5-blocker, 3-iteration, ~70-minute
debugging session). Parent: the `app-packaging-and-distribution` tempdoc (374);
siblings `sandbox-validation` (375), `cpu-gpu-inference-strategy` (376),
`shadow-jar-alternative` (226).

This is **analysis + roadmap**, not an implementation. Status `open` — no item
started. See §Confidence for the de-risking work that must precede implementation.

## The unifying thesis

**The installer build is the only routine thing that exercises "clean-environment"
assumptions — clean dependency resolution, a clean toolchain, clean storage — but
it is slow (~20 min), manual-only ([ADR-0026](../decisions/0026-manual-ci-triggering.md)),
and monolithic, so those assumptions rot silently and then all fail at once,
expensively, at the moment an installer is actually needed.**

The fix philosophy is **shift-left + fail-fast**: make the *cheap subset* of
"would a clean build work?" run frequently and instantly, so the expensive full
build becomes a formality rather than a debugging session.

## Proof-by-example — the 2026-05-30 build cascade

One "build a new installer" request hit five independent blockers in sequence,
each an invisible clean-environment assumption that had drifted:

| # | Blocker | Why it stayed invisible until now | Class |
|---|---------|-----------------------------------|-------|
| 1 | **Smart App Control blocked the local Rust compile** (`os error 4551`) | SAC ran for weeks in *evaluation* mode (non-blocking); auto-flipped to *enforcement*, so unsigned cargo build-scripts began being blocked. `VerifiedAndReputablePolicyState=1`. | Toolchain/env drift |
| 2 | **`package-installer-win.ps1` threw "MSVC C++ Build Tools not found"** | The `vswhere -requires …VC.Tools.x86.x64` query returned empty even though `cl.exe` was present at `…\BuildTools\VC\Tools\MSVC\14.44.35207\…`. Component-metadata false-negative. | Build-script brittleness |
| 3 | **`cel-bom-0.5.1.pom` failed dependency verification** (CI clean build) | Local builds resolve the BOM via warm cache + Gradle Module Metadata (`.module`); only a *clean* download fetches the `.pom`, which had no checksum in `verification-metadata.xml`. Introduced when `cel-java` landed (430 Phase-8-prep) without regenerating verification metadata. | Latent dep-metadata gap |
| 4 | **Artifact upload hit the storage quota** | ~11 expired 741 MB `windows-installer` artifacts (~8 GB) had accumulated; nobody prunes them. The upload step (no `if`) ran before the release-attach step and short-circuited the job (a condition-only `if` keeps the implicit `success()` gate). | Storage entropy |
| 5 | **3× ~20-min blind iterations** | "Build installer" is one monolithic CI step; GitHub does not expose an in-progress step's logs, so each failure required a full run to surface and `--log-failed` spelunking to read. | Observability |

Artifacts of the eventual success (kept for traceability):
- `fb2af6d47` — `fix(deps)`: add `cel-bom-0.5.1.pom` sha256 to `verification-metadata.xml` (cross-verified against Maven Central's published `.sha1`).
- `295feb83d` — `ci(installer)`: `continue-on-error` on the artifact-upload step so the job always reaches the release attach.
- Release `v2.0.0-alpha.27` — the lean 778 MB CPU-only installer, attached as a release asset (separate storage from Actions artifacts).

## Prioritized roadmap

> **Revised by the 2026-05-30 de-risking pass** — see §Confidence below: item 1's fix
> is now precise (`vswhere -all`); item 3 needs no regen (cel-bom fix suffices); item 5
> is feasible but deferred; item 6 folds into item 8. Items 1/2/4/7 are go.

### Tier 1 — cheap, high-leverage, near-zero-risk

1. **Fix the MSVC prereq false-negative** in `scripts/ci/package-installer-win.ps1`
   (`Assert-TauriWindowsBuildPrereqs`). When `vswhere -requires …VC.Tools.x86.x64`
   returns empty, fall back to detecting `cl.exe` under a VS install before
   throwing. Today it threw with `cl.exe` present.
2. **Add a SAC-enforcement preflight** to the build path: read
   `HKLM:\SYSTEM\CurrentControlSet\Control\CI\Policy\VerifiedAndReputablePolicyState`;
   if `=1`, emit one clear actionable line up front ("Smart App Control is
   enforcing → the local Rust build will be blocked; disable SAC or use CI")
   instead of a cryptic `os error 4551` ~15 min into the Rust compile.
3. **Close the dep-verification gap at its source.** The `/lockfile` skill +
   `lockfile-hint` hook document regenerating `gradle.lockfile` on dep change but
   are silent on `verification-metadata.xml` — exactly the omission that produced
   blocker #3. Add `--write-verification-metadata sha256` to the documented
   dep-change workflow.
4. **Stop storage entropy.** Lower the installer artifact `retention-days`
   (currently 30) in `build-installer.yml`, and add an auto-prune (a tiny
   scheduled workflow keeping the last N `windows-installer` artifacts). Today's
   54 expired artifacts were deleted by hand.

### Tier 2 — structural

5. **Split the monolithic "Build installer" step** in `build-installer.yml` into
   discrete steps (ui-web build → `bundleSidecar` → tauri/rust → nsis) for
   step-level progress and instant failure localization. Requires teasing apart
   what `package-installer-win.ps1` currently orchestrates as one phase.
6. **A fast clean-resolution preflight** — a job/command running
   `gradlew … --refresh-dependencies` against a cold Gradle cache (no Rust/NSIS)
   that would have caught blocker #3 in ~2 min instead of ~20. Runnable locally
   too. (Complements `scripts/ci/report-lock-skew.mjs`, which covers lockfiles,
   not verification metadata.)
7. **Make the release the canonical distribution.** Stop double-storing to both
   Actions artifact (quota-limited) and release; keep the artifact only for
   untagged dev dispatches with short retention.

### Tier 3 — strategic (ADR-level; propose, do not unilaterally change)

8. **Reconsider manual-only CI *for the installer specifically*.** A nightly or
   on-tag automated installer build would surface clean-env regressions
   continuously rather than at crunch time. Touches
   [ADR-0026](../decisions/0026-manual-ci-triggering.md).
9. **Code-sign the installer** (and pursue Microsoft SmartScreen/SAC reputation).
   The eventual end-state that makes SAC a non-issue for *end users* too, not
   just builds — but a real cost (cert + signing infra). Flagged as horizon, not
   a quick win.

## Confidence / de-risking — RESOLVED (2026-05-30)

A read-only investigation (2 Explore agents + direct verification) plus four
experiments (E1–E4; plan at `~/.claude/plans/noble-discovering-toucan.md`) resolved
the open uncertainties **before** any implementation. Outcomes per item:

- **Item 1 — HIGH, precise fix.** Root cause of the "MSVC not found" false-negative:
  the VS BuildTools install is `isComplete=0` / `isLaunchable=0`, which `vswhere`
  hides unless `-all` is passed; `cl.exe` 14.44 is present and Rust links fine.
  **Fix: add `-all` to the `vswhere -requires …VC.Tools.x86.x64` query** in
  `Assert-TauriWindowsBuildPrereqs` (a `cl.exe`-existence check is an optional
  secondary guard). Not a symptom-mask. (E1)
- **Item 3 — no wholesale regen; the cel-bom fix suffices.** An audit's "254
  `.module`-without-`.pom` gaps → full `--write-verification-metadata` regen" was a
  **false alarm**: direct verification shows every BOM/platform component (junit-bom,
  netty-bom, jackson-bom ×14, aws/google/gax BOMs, and the now-fixed cel-bom) **has
  its `.pom`**; the 254 are benign `.module`-only libraries whose `.pom` Gradle never
  fetches. The real risk class is *transitive* BOM imports lacking a `.pom`; **cel-bom
  was the lone case**, proven fixed by the green CI build. E5 (merge-safety) is moot.
- **Item 5 — feasible but low-ROI.** An audit called Tauri's `beforeBuildCommand` an
  unskippable "HARD blocker." **E4 disproved it:** `tauri build --config <file with
  beforeBuildCommand:"">` skips the hook (the run jumped straight to the Rust compile,
  no Vite/Gradle). Splitting into explicit web/gradle/tauri CI steps is mechanically
  possible, but carries a CI-only tauri-config-variant maintenance cost. Deprioritize
  unless step-level CI visibility becomes a real need.
- **Item 6 — redesign, then fold into item 8.** A *static* check (build-file
  `platform()` usages, or `.module`-without-`.pom`) **cannot** catch the actual
  failure mode: cel-bom was a *transitive* BOM import invisible to build files, and a
  `.module`-gap scan yields 254 false positives. The only reliable detector is a
  **clean full resolution** — which the installer build already performs. So item 6
  collapses into **item 8**: run a clean-resolution / installer build on a schedule,
  not a separate preflight. (E2)
- **Items 4 & 7 — HIGH, safe.** The `windows-installer` Actions artifact has no
  downstream consumer (the release attaches the `.exe` from disk). Cutting retention
  or dropping the artifact entirely is safe.
- **Item 2 — HIGH.** SAC detection (`VerifiedAndReputablePolicyState`) is proven.

**Net go/no-go.** Safe-to-implement now (high confidence, precise): **items 1, 2, 4,
7**. Item 3 reduces to "keep the cel-bom fix" (no regen). Item 5 is feasible but
**deferred**. Item 6 **folds into item 8** (scheduled clean build). Two subagent
overstatements — the 254-gap alarm and the Tauri "hard blocker" — were caught by
direct verification; that is itself evidence the de-risking pass earned its keep.

## Staleness / promotion

Open roadmap. When items ship, promote the durable facts (build prereqs, SAC
behavior, dep-verification workflow) into `docs/explanation/12-desktop-installer-and-sandbox-setup.md`
and the `/installer` + `/lockfile` skills, and record verified bugs in
`docs/reference/issues/installer.md`.

## 2026-06-25 — preflight session: SAC recurrence, build-path decision, sandbox-goals expansion

Picked up from a "build a new installer + sandbox-verify" request. Ran the
shift-left preflight this doc advocates **before** committing to a ~20-min build.
Three findings, recorded as dated history (verify against `main` before trusting):

1. **Blocker #1 (SAC) has recurred — the thesis holds.** Direct probe:
   `HKLM:\…\CI\Policy\VerifiedAndReputablePolicyState = 1` (enforcing) on the
   build host (2026-06-25). The Tier-1 fix (commit `3cb6935f7`) worked as
   designed — the package-script preflight now *fails fast* with guidance instead
   of dying `os error 4551` ~15 min into the Rust compile. But the underlying
   env-drift is exactly the "clean-environment assumption rots silently, then
   fails the moment an installer is needed" cascade. SAC is a one-way switch
   (off needs a Windows reset to restore), so disabling it for a local build is a
   real security tradeoff, not a toggle.
   → **Decision: build on CI** (`build-installer.yml`, clean `windows-latest`, no
   SAC). This is **item 8's argument** (scheduled/CI installer build) arriving by
   necessity rather than choice. VS BuildTools 2022 present; item 1's
   `vswhere -all` fix not exercised this run.

2. **A bare local build needs the lean flags too, not just SAC-off.** The package
   script does **not** default to lean — CI passes `ORG_GRADLE_PROJECT_includeCuda=false`
   + `_skipOnnxModels=true` as env. A bare local `package-installer-win.ps1`
   builds the full ~7 GB sidecar → G21 NSIS 32-bit failure. So a local build is
   **two** preconditions (SAC-off AND lean env). Candidate `/installer` skill
   promotion: the skill documents the SAC prereq but not the lean-flag
   requirement for local runs.

3. **Sandbox validation goals had drifted behind the FE** (sibling concern,
   tempdoc 375 territory). Audited the live FE (dev stack + claude-in-chrome)
   against the staged sandbox prompts (`scripts/sandbox/sandbox-{CLAUDE,start-SKILL}.md`,
   rewritten 2026-06-18 `c84de94e2`). They were journey-complete for the alpha.27
   backend mission but had **no journeys** for four user-facing trust surfaces
   shipped since: Security & Privacy (encryption status + irreversible
   chat-passphrase flow, 629), the chat escalation ladder's
   Documents/Structured/**Agent "delegate a task"** rungs (561/565), Memory
   inspect/forget (561), and Appearance/Skins (569). Added those as journeys + a
   risk item + report-section rows to both staged prompts (they reach the sandbox
   via launch-from-disk, no commit required). These map to the privacy/threat-model
   claims the go-public work (631–634) publishes, so a misleading surface there is
   high-severity.

**Doc-drift noted (not fixed, per `log-pre-existing-issues`):**
`docs/reference/issues/installer.md` (updated 2026-04-07) is stale — INS-001's
SAC auto-disable workaround was *removed* and INS-005 was *reframed* as
deliberate terminal-failure design, both by `c84de94e2`. Needs a separate
reconciliation pass.

**State (start of run): build not started.** Chosen path: CI dispatch → download
artifact → stage → launch sandbox in **fresh-install** mode (`JUSTSEARCH_MODELS_DIR`
unset, Rule 14). Windows Sandbox feature enablement on the host is **unconfirmed**
(needs elevation) — a hard precondition for the second half.

### Build-run outcome (same day) — blocker #4 recurred, twice, then a release cut

The CI build path hit **blocker #4 (storage entropy) — the live recurrence this
doc warned about.** Sequence, recorded for the next person:

- **Two clean `workflow_dispatch` builds on `main` (28164157569, 28165346176)
  both went green but produced NO artifact.** Upload failed with
  `Failed to CreateArtifact: Artifact storage quota has been hit … recalculated
  every 6-12 hours`. The `continue-on-error` on the upload step (commit
  `295feb83d`) means the **job stays green with no artifact** on a non-tag run —
  the failure is silent unless you check the artifacts API / step log. Account
  free-tier storage (~500 MB) was full from one stale 781 MB `windows-installer`
  + an 81 MB `ci-failure-reports`.
- **Pruning didn't unblock an immediate re-run.** Deleted the two large
  artifacts (live usage → 0 MB), re-dispatched — and the 2nd build hit the
  **same** quota error, because the usage counter is recalculated on GitHub's
  6-12 h cadence, not on delete. So "prune + re-run" is **not** a same-session
  recovery; the recalc lag defeats it.
- **Recovery that worked: cut the `v2.0.0-alpha.28` release tag.** Release-asset
  storage (`softprops/action-gh-release`) is **separate from the Actions
  artifact quota**, so it uploaded fine. Mechanics: bump `gradle.properties`
  → `sync-version.ps1 -RequireReleaseSemver` (propagates tauri.conf.json /
  package.json / Cargo.toml; hand-synced `Cargo.lock` shell-crate line) → commit
  `be138b8f0` → push tag.
- **Correction to a stale claim:** 374 G3 says the workflow "triggers on `v*`
  tag push." It does **not** — `build-installer.yml` `on:` is `workflow_dispatch`
  only; the release-attach `if: startsWith(github.ref,'refs/tags/v')` fires when
  you **dispatch against the tag ref** (`gh workflow run … --ref v2.0.0-alpha.28`,
  run 28166230627). Pushing the tag alone does nothing.
- **Result:** release `v2.0.0-alpha.28` (prerelease) with
  `JustSearch_2.0.0-alpha.28_x64-setup.exe` (813 MB), downloaded + staged into
  the fresh-install sandbox share (`tmp/sandbox/JustSearch-Validation.wsb`).

**Roadmap implications (new evidence for existing items):**
- **Item 4 (stop storage entropy) is under-built.** The 7-day retention cut
  (`598feb…`/562) wasn't enough — a single stale installer refilled the quota,
  and there is still **no auto-prune**. The recalc lag means manual pruning is
  not a same-day fix, so the auto-prune (keep last N) should be treated as
  required, not optional.
- **Item 7 (make the release the canonical distribution) just proved itself.**
  Release storage was the *only* working egress under a full artifact quota.
  This is evidence to promote item 7: prefer release-attach over the Actions
  artifact even for dev builds, or drop the artifact upload entirely.
- The `continue-on-error` + non-tag dispatch combination is a **silent-failure
  trap**: green job, no output. A dispatch that intends to produce a downloadable
  installer should either go via a tag (release) or assert the artifact exists.

### Sandbox validation outcome — alpha.28 fresh-install (2026-06-25)

The launched sandbox ran a full **fresh-install** round (`MODELS_DIR` unset; real
9.99 GB / 7-package Install AI). **7/7 sanity ladder PASS** (47.4 tok/s idle chat,
cross-encoder 191 ms, 100% embed+SPLADE, `FINGERPRINT_MATCH` — no alpha.16
regression), **clean build hygiene** (23 unique alpha.28 jars, no 500s, no stale
jars), **no high-severity issues**. The four sandbox journeys added this session
were all exercised and were the **strongest positives** (chat-passphrase flow +
Agent pre-run disclosure) — i.e. the goal-expansion paid off. Evidence:
`C:\Users\<user>\Desktop\evidence\VALIDATION-REPORT-alpha28.md` + per-finding `.txt`.

> **Disposition:** these are **product / UI-trust findings, not build-pipeline
> defects** — recorded here as the run's outcome, but the actionable home is
> `docs/reference/issues/installer.md` (already stale — folds into the
> reconciliation pass) and the go-public first-run-robustness/threat-model work
> (633). #1 + #2 below are the go-public gating fixes; signing is the external
> dependency (cert).

**Medium (8):**
1. **Brain shows installed AI as "Not Installed" after restart** (Restart/return).
   `/api/ai/install/status` resets to `installedFully:false, packages:[]` after
   restart; Brain UI keys off it instead of `runtime.installedVariants` (which is
   correct). Implies 10 GB data loss + forces terms re-acceptance; recovery is
   download-free (~15 s). Root-caused, single-fix. **#1 priority.** (`60`,`61`)
2. **"Reconnecting…" on Health during install while REST is 200/READY** — header/
   retrieval/status-bar stuck ~30 s while all REST endpoints 200 / head+worker
   READY. (`14`,`15`)
3. **The 9.99 GB model download is invisible on Health** (secondary to #2) — the
   install's dominant background work shows only on Brain. (`14`,`15`)
4. **"Add Folder" CTA occluded by "Navigated to Library" toast** — revealed only
   after ~6 s auto-dismiss. (`36`/`37`)
5. **Welcome tour never mentions Install AI** — the 4-step tour ignores the
   AI-offline blocker the user is staring at. (`04`–`07`)
6. **Command palette has no "Start AI Install"** — typing "install" pre-install
   returns only "Cancel AI Install". (`09`)
7. **Unsigned installer + app** (Authenticode NotSigned) — no in-sandbox prompt
   (mapped-folder install lacks mark-of-the-web), but a real download would trip
   SmartScreen "Unknown publisher". Flagged for **signed-build re-validation**;
   the sandbox cannot reproduce the MOTW path. (`16`)
8. **Firewall prompt names the JRE, not JustSearch** — launch-time alert for
   "OpenJDK Platform binary / Eclipse Adoptium"; app works on loopback after
   declining. (`58`,`59`)

**Low (9):**
9. First view dual-labels — header "Chat — ask anything" while active tab is
   "Search". (`03`)
10. Terms dialog vague + no terms link — "several GB" (real 9.99 GB shown only
    later); accept "upstream model terms" with no link to read them. (`11`)
11. Offline rungs have no per-rung tooltip (only a global reason banner). (`35`)
12. "LambdaMART" jargon on a user-facing degraded-search banner. (`35`)
13. Stale "AI INSTALL — Downloading …gguf [running]" card persists post-completion
    while the status bar reads "Online — Qwen". (`45`)
14. "Queue 0 · Up to date" coexists with "INDEXING 3152 QUEUED" on one surface. (`45`)
15. **Conversation preview visible while chat is locked** ("What is the relationship
    be…") — titles/previews may be unencrypted metadata. *Elevate toward MED pending
    a code check — touches the encryption claim (633).* (`62`)
16. "Source: shell-property · LOW" internal source/confidence enum leaked on the
    Security & Privacy surface. (`18`,`19`)
17. Skin "Import + certify" of the prefilled JSON gives no visible feedback. (`29`–`31`)

**Explicit non-issues (agent judged acceptable):** Memory "Forget" has no confirm
dialog (low-stakes/re-teachable); "Settings (declared)" skin renders Settings dark
while content light (by design, legible); chat model doesn't auto-start on cold boot
(`AUTOSTART=false` by design — needs an activate call).

**Environment / self-work caveats (issues with the agent's *own* validation):**
- **alpha.28:** no separate environment report; inline scope-limits only — didn't
  click Install AI first on the restart finding (re-download risk), didn't run a
  write task on the Agent rung (avoided mutating SciFact), couldn't reproduce the
  MOTW/SmartScreen path (sandbox limitation → signed-build re-validation flagged).
- **alpha.27 (prior round, `findings-running.md`):** a real tooling-friction report
  — **WebView2 synthetic clicks didn't register reliably**, forcing API-initiated
  fallbacks (`/api/ai/install/start`, `/api/knowledge/ingest`) and **caveating
  several "no UI feedback" findings as low-confidence** ("might be my click
  injection, not the app"); the watched-folder add was never exercised that round.
  Plus: `POST /api/ai/runtime/activate` 400s on an empty body (needs
  `{"variantId":"cuda12"}`). The alpha.28 round did **not** hit the click problem,
  so its UI findings are higher-confidence (real interaction, not API fallback).

### Theorization — taking over the alpha.28 findings (2026-06-25, pre-design)

> **Status: theorization only.** No implementation, no settled design. This records
> directions, framings, tradeoffs, hidden assumptions, and a candidate broader
> principle, grounded in a read-only code investigation (3 parallel subagents,
> `file:line` cited). The point is to widen the option space *before* the design is
> fixed — and to honestly mark what is NOT one problem.

#### The grounded code facts (what the investigation actually found)

1. **Brain "Not Installed" — ephemeral copy vs durable truth.** `installStatus.installedFully`
   is in-memory session state (`AiInstallService.java:85`; set `true` only at the end of an
   install run, `:450`; **never rehydrated on boot** — and `AiInstallStatus.java:12` *comments*
   "persisted to disk for crash recovery", which **does not exist** — a doc/code fork that is
   itself a landmine). The durable truth already lives in
   `RuntimeActivationService.listInstalledVariants()` (`:905-950`, recomputed from disk on every
   `/api/ai/runtime/status` call) and in `InstallPlanner.isAlreadyInstalled()` (`:158-160`, live
   `Files.isRegularFile`, but only reachable via `startInstall()`). The FE
   `BrainSurface.deriveAiState()` (`:1022-1024`) keys "installed" off `installStatus.installedFully`
   ∥ `onnxFeatures.modelActive` and **never consults `runtimeStatus.variants`** — the one source
   that is correct after restart.
2. **"Reconnecting…" — channel liveness mistaken for backend reachability.** It is a *poll-staleness
   clock* (`aiStateStore.ts:174` `STALE_THRESHOLD_MS=15_000`), fed **only** by `/api/status` (10 s)
   + `/api/inference/status`; if either heavy poll misses one 15 s window the verdict flips to
   `channel-stale → "Reconnecting…"` (`verdict.ts:208-209`). **There is no independent `/api/health`
   probe in the verdict path** — the cheap liveness endpoint that stays 200 under load is never
   consulted. "System idle" (`SystemSelfView.ts:183`) collapses to `true` when the SSE task feed is
   mid-reconnect *and* the status poll is stale, while the Queue card (a different data path) still
   shows work — the visible self-contradiction.
3. **Locked-chat preview — sensitive content forked outside the encryption boundary (REAL leak,
   confirmed).** Backend is correct: message bodies + `meta.firstUserMessage` are AES-256-GCM sealed
   and `listSessions` returns `firstUserMessage:""` while locked. But the FE writes a plaintext
   `firstMessage: text.slice(0,100)` to `localStorage` at send time
   (`conversationListStore.ts:55-62`, `UnifiedChatView.ts:3995-3996`) and the resume card renders it
   directly (`UnifiedChatView.ts:550-554,1607`) — **bypassing the encryption boundary, never cleared
   on lock, surviving restart.** This upgrades finding #15 from LOW to a real MED privacy leak.

#### The recurring shape (the candidate broader principle)

All three — and most of the truthfulness cluster — are the **same shape**: *a canonical authority
exists, but a user-facing surface (or a cache) reads/writes a **second, divergent copy** that the
authority can contradict.*

- Brain: ephemeral session memory instead of durable disk truth.
- Reconnecting/idle: a **degradable transport's** liveness used as a proxy for **backend
  reachability / presence-of-work**.
- Preview leak: a **client cache** standing outside the **server-side encryption boundary**.

This is the codebase's own **projection-vs-fork / representation-drift** discipline (CLAUDE.md
"Explore Before Implementing"; tempdoc 553; the `execution-surfaces` register+gate) — but applied to
**lifecycle/status *display* and data-classification**, where it is currently *ungoverned*. Equivalently
it is **`verify-don't-guess` (Hard Invariant #4) pushed to the presentation layer**: the UI must reflect
*verified* backend state, not a guessed/cached/remembered proxy. Candidate invariant to test against
(not yet adopt):

> **A user-facing surface projects the single canonical authority for the state it shows, and never
> asserts a state a truer-or-cheaper source contradicts.** Corollaries: durable facts (installed,
> encrypted-content) derive from durable ground-truth, not session memory or client caches; *liveness*
> (is my data fresh?) is distinct from *reachability* (is the backend up?) and from *activity* (is work
> running?) — never collapse them into one alarming label; **unknown ≠ the optimistic default**
> (tri-state — the codebase already names this in `slice-execution.md`).

#### But do NOT over-unify — the honest cluster taxonomy (AHA / YAGNI)

The principle covers ~60–70% of findings, not all. Forcing one design over everything would violate
"only unify what shares a reason to change." Four distinct classes:

- **C1 — Source-of-truth / projection-vs-fork** (Brain-not-installed, "System idle", stale install
  card, dual-labels, the preview leak as its security instance). *Shared reason to change.* Root fix:
  derive the display from the one authority.
- **C2 — Liveness vs correctness** (Reconnecting). *Sibling, not the same fix:* the cure is not "read
  the right source" but **model two concepts** (stream-lag vs backend-down) and key the scary state off
  a ground-truth probe. The verdict/stability machinery (`verdict.ts`) already exists to extend.
- **C3 — Presentation-vocabulary leaks** ("LambdaMART", "shell-property · LOW", the `core.start-ai-install`
  op-id from the alpha.27 round). The codebase **already has gates** here (`check-presentation-purity`,
  `check-message-classes`) — so these are **coverage gaps in an existing discipline**, not new work.
- **C4 — Packaging / OS trust-signaling** (unsigned binaries, firewall names the JRE, MOTW/SmartScreen).
  **Not truthfulness at all** — external/OS-facing, signing-dependent (374 G4). Must stay its own track;
  do not bend it into the source-of-truth frame.

#### Solution directions per cluster (options, not decisions)

**C1.**
- *Point-fix:* FE reads the correct field (Brain also checks `runtimeStatus.variants.length>0`). Cheap,
  FE-only, ships the alpha. Risk: the divergent source survives → the next surface repeats the bug.
- *Projection-fix:* backend makes "installed" a **derived predicate over durable state** —
  `getStatus()` recomputes (or boot-rehydrates) `installedFully` from on-disk detection, fixing every
  consumer at once. Requires **separating two concepts currently crammed into one field**: *"is
  installed"* (durable, disk-derived) vs *"install-run status"* (ephemeral, session). The bug is that
  `installedFully` serves both. Honest sub-task: implement (or delete) the *documented-but-missing*
  disk persistence.
- *Framing shift:* "installed" is a **function of disk**, not a **remembered event of this session**.

**C2.**
- Add an independent `/api/health` probe to the verdict; show "Reconnecting/disconnected" only when
  health **also** fails. A slow `/api/status` while `/api/health` is 200 → "Updating…/Busy", not
  "Reconnecting". (Three-state: *reachable* / *fresh* / *busy*.)
- Or: widen the staleness threshold during *known-heavy* ops (the FE knows an install/ingest is
  running) — cheaper, but risks masking a real disconnect mid-op.
- Or: a dedicated lightweight heartbeat decoupled from the heavy `/api/status`. For "idle": when the
  activity channels are stale/unknown, render "Updating…", **never "idle"** (tri-state; the
  `LifecycleSnapshotTap` "unknown ≠ healthy" instinct already exists in the worker).

**C3.** Likely just close the gate gaps (extend `presentation-purity`/`message-classes` coverage to the
leaking surfaces) — small, mechanical, high-leverage.

**C4 (preview leak under C1-security).** Options: stop storing message content client-side and derive the
resume preview from the already-guarded backend (Source B) — removes the fork; or clear/guard the
`localStorage` cache on lock; or encrypt the cache. Framing: **sensitive data has one authoritative store
with the boundary; client caches are forks that must not exist or must inherit the boundary** — a declared
**data-classification** (ties straight to the go-public threat-model, 633).

#### Enforcement / useful-later ideas (explicitly NOT to build yet)

- **A status-surface source-of-truth register**, analogous to `execution-surfaces`: each user-facing
  status/lifecycle claim declares its single canonical source; a gate fails the build when a surface reads
  a non-canonical source. *Attractive but possibly over-engineered* — per AHA, justify by recurrence. The
  counter-evidence FOR it: one validation round surfaced ~6 instances, which is how `execution-surfaces`
  earned its keep (553).
- **A truthfulness oracle** in the sandbox/CI loop: render a surface under each scenario (offline,
  installing, ingesting, restarted, locked) and assert the displayed state matches `/api/*` ground-truth.
  **The sandbox agent is doing exactly this by hand every round** — that is the current oracle, and it is
  fragile for a go-public cadence. This is the UI-truthfulness analogue of the eval/perf ratchets
  (`search-engine-hint`).
- **A client-persistence lint** (no message bodies / sensitive content in `localStorage`).

#### Tradeoffs, risks, hidden assumptions

- **The one assumption under every finding:** *"the source I read == the truth."* Each bug is a place that
  is false. Naming it is the contribution; the register/oracle ideas are just ways to make the assumption
  checkable.
- **Over-unification risk:** several findings are genuine one-liners (clear the stale card, rename an
  enum). Don't grow a "truthfulness framework" to swat them. Symptom-fix the go-public-gating three
  (Brain, Reconnecting, preview-leak) now; treat the register/oracle as a *separate structural bet* to be
  argued on its own merits.
- **Hiding real failures:** making "Reconnecting" calmer must not mask a true backend-down — hence the
  ground-truth-probe two-tier, not just a threshold bump.
- **Perf:** disk-derived install-status per call is negligible (runtime-status already does it).
- **Cross-module surface:** fixes span Head (Java), FE (Lit), and the Worker/SSE — ownership and the
  unit-of-fix (symptom vs class) are the first decisions to make.
- **Doc/code forks are landmines:** the `AiInstallStatus` "persisted to disk" comment is false; a future
  agent may trust it. Treat stale comments as defects in this class too.

#### Where this points (for a later, separate decision)

This theorization has outgrown "installer build pipeline." If the source-of-truth principle is adopted, it
deserves **its own tempdoc + a canonical home** (an `explanation/` doc on "presentation projects verified
state", sibling to the execution-surfaces story), with 562 retaining only the pointer. The broader shape —
*projection-vs-fork is not search-specific; it recurs anywhere a surface can read a convenient divergent
copy instead of the authority* — is the reusable idea worth carrying forward even if the specific fixes
ship as point-fixes first.

### Long-term design — conform to and complete the presentation kernel (2026-06-25, post-investigation)

> **Supersedes the pre-design directions above where they differ.** A read-only investigation (3
> subagents, `file:line` cited) found that **the design I was theorizing toward already exists and is
> largely built.** So the correct long-term design is **not** a new mechanism — it is *conform to the
> existing presentation kernel and complete two of its authority-models*. The size of the change is small
> **because the structure the problem requires is already present**; that is the right scope, not a target.

#### The decisive finding: the authority-per-concept design already exists

`docs/explanation/27-frontend-presentation-kernel.md` already establishes the exact invariant — *"a given
visual or behavioural concept has exactly one authority,"* enforced by the **Collapse > Generate > Gate**
ladder (make the fork unrepresentable; else generate it un-forkably; else gate the build). The realized
authorities and their guards already exist:

- **`state/aiStateStore.ts`** (tempdoc 508) — documented verbatim as *"the single observed-state
  authority"* (`:3-28`, `:155-163`); composes the status + inference polls + activity into one snapshot
  surfaces subscribe to. It already carries `runtime.installed: Maybe<boolean>` and `connection.reachable`.
- **`state/verdict.ts`** (595) — the single readiness→verdict derivation, **gate-enforced** by
  `check-verdict-derivation` so no surface can re-fork it.
- **`state/known.ts`** — the `Maybe<T> = Known | Unknown` tri-state contract (`whenKnown`,
  `renderObserved`): *unknown is a first-class state, not the optimistic default.*
- **Gate family** already guarding named authorities: `check-observed-state-collapse` (which **names the
  exact defect class** — *"no data != 0 files / Not Installed / All Operational"*), `check-inflight-liveness`
  (**already covers "brain-install stalled"**), `check-capability-availability`, `check-presentation-purity`,
  `check-message-single-model`, `check-run-renderers`, `transient/modal-arbitration`.

**Therefore the alpha.28 findings are conformance gaps and incomplete authority-models inside a correct
design — not missing structure.** This is the answer to "match scope to the problem": the structure exists;
the work is to *finish conforming to it*.

#### The design = three conformance/completion moves (general, not implementation)

1. **Installed-state — complete the authority; stop the cross-boundary fork.** The bug is that **one field,
   `installedFully`, serves two distinct concepts**: *"is installed"* (durable, a function of disk) and
   *"the install run finished this session"* (ephemeral). Design: *"is installed"* must be a **projection
   over durable ground-truth** (disk — `RuntimeActivationService.listInstalledVariants()` already does this
   on every call), exposed as the tri-state `Maybe<boolean>` the store already has; the install-run tracker
   stays ephemeral and is *named* as run-status, not installed-status. The FE (`BrainSurface`) **consumes
   `aiStateStore.runtime.installed`** instead of re-polling `/api/ai/install/status` and reading
   `installedFully` directly (`BrainSurface.ts:631,1022-1024`) — *finishing a migration `SystemSelfView`
   already made.* After restart the install-tracker's view is **`Unknown`, never `false`** → renders
   "Checking.../Installed - idle", never "Not Installed". This is precisely
   `check-observed-state-collapse`'s named defect, applied to the install authority.

2. **Connection — split reachability from freshness; complete the verdict model.** The kernel **already
   declares** (`explanation/27:73`) that *reachability is owned by the cheap poll (`connection.reachable`),
   not any SSE channel.* The gap is inside `verdict.ts`: it collapses poll-**staleness** (`channel-stale`)
   straight into *"Reconnecting..."* without consulting reachability. Design: the verdict distinguishes the
   three states the model already half-holds — **reachable+fresh** ("Connected"), **reachable+stale**
   ("Updating.../Catching up", benign under load), **unreachable** ("Reconnecting", the only alarming one).
   *"Reconnecting" must require an actual reachability failure* (a cheap liveness signal independent of the
   heavy `/api/status` poll), not mere staleness of a heavy poll while the backend is busy. This **extends
   the existing verdict authority; it adds no new one.** The "System idle" contradiction is the same tri-state
   completion: an empty SSE task-feed mid-reconnect is **`Unknown`, not idle** → "Updating...", per `known.ts`.

3. **Sensitive content — one at-rest authority (the encryption boundary).** A *distinct* principle
   (data-classification, not display-state) but the **same single-authority shape**: server-side `StoreCipher`
   is the authority for sensitive-content-at-rest. The conversation domain is correctly separate from
   `aiStateStore`, but the `localStorage` recent-sessions cache **forks plaintext content outside the
   boundary** (`conversationListStore.ts:55-62`). Design: the resume preview derives from the
   already-guarded backend (`listSessions` returns `""` while locked) **or** the client cache **inherits the
   boundary** (cleared/locked on lock). Declare what may live client-side as an explicit
   **data-classification** — directly feeds the go-public threat-model (633).

#### Scope match — what the present problem does NOT require

- **No new FE store, no new projection layer** — `aiStateStore` is the right one and has high adherence
  (Health/SystemSelfView/Security/UnifiedChat already consume it). The investment is *completing the one
  migration `BrainSurface` left unfinished*, not building.
- **No new register is required** for the alpha.28 instances. The kernel's positive-catalog +
  completing the two authority-models + closing the bypasses covers them. The conforming *enforcement* move
  is to **extend an existing gate's coverage** (`check-observed-state-collapse` to the install-state
  authority; `check-inflight-liveness` already covers brain-install) — not author a parallel mechanism.

### Reach — recognize the principle, defer the general structure

**This is not a new principle. It is the third domain-instance of one the system already names.** The
projection-vs-fork / **single-canonical-authority** invariant governs all three layers with the same shape —
*canonical record → registered projections → gate forbids the unregistered fork*:

| Domain | Canonical authority | Mechanism |
|---|---|---|
| Search-execution | `SearchTrace` | `execution-surfaces` register + gate (553) |
| Operation-lifecycle | `IndexingJobLifecycle` | `operation-surfaces` register + gate |
| **Presentation-state** | `aiStateStore`/`verdict` | presentation kernel: Collapse > Generate > Gate (27) |

So **conform, don't parallelize**: the design above is "finish conforming to kernel 27," and 27 is itself
the FE instance of the backend register+gate law.

**The new facet this problem reveals (the actual contribution):** the fork can **cross the
backend↔frontend boundary**, and that crossing is the **under-governed seam** — each existing mechanism
governs *one* side:
- a **durable** backend fact represented as an **ephemeral** session value (`installedFully`) that the FE
  then displays as truth;
- **sensitive** server-encrypted content cached in **plaintext** client-side (`localStorage`).

**Shared honest-limit across every one of these gates (backend and FE):** they are **positive-catalog** —
they guard *named* authorities and catch only forks that use the exact scanned symbol/predicate. A *net-new*
surface that reads a raw endpoint or `localStorage` to derive displayed state is **import-invisible**. The
backend register+gate already mitigates this (`forbiddenReintroduction` denylist + `unrelatedStores`
positive classification); **the FE gate family has no equivalent** for "a new surface re-derives displayed
state."

**Candidate generalization — named, scoped, deliberately NOT built now:** a **displayed-state source
register** for the FE, a sibling of `execution-surfaces`/`operation-surfaces` — every surface that displays
a lifecycle/status value declares its single authority; a gate fails on a surface reading a raw endpoint /
`localStorage` for displayed state (closing the positive-catalog gap on the FE side and the
cross-boundary-fork seam). **The present problem does not yet require it:** completing two authority-models +
extending `check-observed-state-collapse` handles the alpha.28 instances. Apply the same bar the
`execution-surfaces` register had to clear — it earned its keep only *after* representation-drift recurred
(553). **Build the register only if displayed-state forks keep recurring; record the candidate now, defer
the structure.** (Existing violations beyond alpha.28 to watch: `ApiExplorerView` raw `/api/status` [dev
tool, low]; the absence of a *call-site* tri-state gate — `Maybe<T>` can be consumed unsafely via an
optimistic `orElse` with no scan.)

**Graduation note (scope hygiene):** this design + principle has outgrown "installer build pipeline." It
should **graduate into its own implementation tempdoc**, and the durable part (the cross-boundary-fork seam
+ the candidate displayed-state-source register) belongs as an **extension of `docs/explanation/27`**, the
kernel doc — not as a retitle of 562, which would orphan its installer-build subject. 562 keeps the pointer.
*(Hence: title unchanged — the design graduates rather than subsumes.)*

### User-facing design — proportionate, honest status (2026-06-25, live-grounded)

> All three design moves are **directly user-visible** (each is a label/tone/CTA a user reads). So this was
> grounded in the **live UI** (dev stack + screenshots), not the tempdoc alone. The decisive live finding:
> the **calm, honest vocabulary this design needs already exists in `verdict.ts` and is already used on the
> very screens that misbehave** — so the user-facing design is "route the right cause to the right *existing*
> word and tone," not coin new copy (which the kernel's single-label authority forbids anyway).

#### What the live UI actually shows (evidence)

- **"Reconnecting…" reproduced trivially.** With a perfectly healthy dev backend, the System Health surface
  showed **"● Reconnecting…"** (amber) in *three* places — header badge, Connection→Retrieval row, status bar
  — while the **same screen** proved reachability: *Index state: Ready* (green), API endpoint live, *Uptime
  41s*, GPU *Detected*, INDEXING *Processing 48 items [running]*. The only trigger was enrichment load
  starving the `/api/status` poll; it cleared to "Offline" the moment the queue drained. **Incoherence on one
  screen:** the Files/Size cards already render the *same* staleness as the calm **"Last known"**, while the
  badge escalates it to the alarming **"Reconnecting…"**.
- **The vocabulary already has the calm states.** `verdict.ts:193-224`: `checking`→"Checking…",
  `unreachable`→"Backend disconnected", and reasons `catching-up`→**"Catching up…"** and
  `worker-restart`→**"Restarting…"** — with an explicit existing principle (tempdoc 627 comment) that a
  *routine self-heal must read calm, not as "Service degraded."* The info-vs-warn split is already there
  (`Reduced capability` vs `Service degraded`).
- **Brain installed-state.** Dev (models on disk) shows "Install AI [idle]" + "Runtime Mode: offline"; the
  alarming **"● Not Installed — Install AI models to get started [Install AI]"** card is the post-restart
  state (evidence `60`). The honest "offline ≠ missing" language already exists elsewhere — Health's *"Ask AI
  about your documents — The local AI model is offline · Open Health."*
- **Locked-chat preview.** The resume card renders the first message verbatim ("Continue your last
  conversation? — '…' [Continue] [Start fresh]") from `localStorage`, with no lock gate.

#### The user-facing principle: an *alarm budget* — spend alarm only when warranted

Every one of these is the same UX defect: **the presentation renders an alarming or pessimistic label for a
benign, transient, or merely-unknown reality.** Each false alarm spends user trust — and this product's whole
pitch (local, private, honest) is trust, so false alarms are a *product* regression, not cosmetics. The
design rule (a presentation-tone corollary of the single-authority design): **the authority produces the
honest state; the presentation renders it with *proportionate* tone — calm for routine/transient/unknown,
alarm only for states that are actually bad and actionable — and never claims a pessimistic state under
uncertainty.** Concretely, reusing existing vocabulary:

1. **Connection (move 2) — calm staleness vs real disconnect.**
   - *reachable + fresh* → no badge / "All systems operational" (green).
   - *reachable + stale-under-load* → **"Catching up…"** (calm `info` tone) + cards stay **"Last known"** —
     the badge now *agrees* with the cards instead of contradicting them. **Not** amber "Reconnecting…".
   - *unreachable* (the genuinely alarming case) → **"Reconnecting…" / "Backend disconnected"** (amber/red).
   - The user-visible win: the scary word appears only when the backend is actually gone; routine enrichment
     load reads as "Catching up…", matching reality. Conforms to the 627 calm-self-heal precedent.

2. **Installed-state (move 1) — "Installed · offline · Activate", never "Not Installed".** A returning user
   must never be told to **re-download** what they have. The states (existing tones):
   - *unknown, disk not yet checked* → **"Checking…"** (calm) — never the pessimistic "Not Installed".
   - *installed, runtime not started* → **"AI ready — not started"** with an **[Activate]** CTA (start what
     you have), aligned to Health's "the local AI model is offline" language and the existing INFERENCE-MODE
     *Online* control. **The CTA changes from "Install AI" (download, ~10 GB) to "Activate" (start, ~15 s).**
   - *genuinely not installed, disk empty* → **"Not Installed" [Install AI]** stays correct.
   The single most important visible change: the **CTA + label distinguish "start" from "download."**

3. **Activity (move 1, "System idle") — "Updating…", never a false "idle".** When the task/activity channel
   is *unknown* (SSE mid-reconnect), render **"Updating…/Checking…"**, not **"System idle — nothing running."**
   Unknown is not idle (`known.ts` `renderObserved`).

4. **Locked-chat preview (move 3) — honor the lock everywhere content can appear.** When history is locked,
   the resume card shows a **locked affordance** — e.g. *"Continue your last conversation? 🔒 Locked — unlock
   to view" [Unlock]* — with **no message content**, deriving the preview from the backend (which already
   returns `""` while locked) rather than the plaintext cache. A user who encrypted their chat will *notice*
   a snippet leaking on the start screen; honoring the lock here is a visible trust signal, and the security
   correctness is the move-3 boundary fix.

#### Scope match (user-facing)

- **No new components, no new copy system.** Reuse `verdict.ts`'s kinds/reasons (`checking`,
  `catching-up`, `unreachable`), the `Reduced capability`/`Last known` calm treatments, the existing
  Activate/inference control, and the lock affordance. New *user-visible strings* are minimal and must be
  added through the single label authority (`present()`), not hand-written on surfaces — else they violate
  `check-presentation-purity`.
- The visible deltas are: **(a)** "Reconnecting…" → "Catching up…" under load; **(b)** "Not Installed
  [Install AI]" → "AI ready — not started [Activate]" after restart; **(c)** false "System idle" →
  "Updating…"; **(d)** locked resume card hides content behind [Unlock]. All four are *re-routings to states
  the design language already owns* — which is why the change is small and conformant.

*(Design note for later: the recurring user-facing shape — "calm/alarm tone must be proportionate to the
verified state, and unknown must never render as the pessimistic default" — is the presentation-tone face of
the single-authority+tri-state principle, and belongs in the kernel doc (`explanation/27`) alongside it when
this graduates. It is the same "alarm budget" instinct the 627 self-heal and the info-vs-warn split already
encode; these three findings are where it isn't yet applied.)*

### Confidence-building verification — assumptions tested before implementation (2026-06-25)

> Read-only verification + handler-reading (no feature code), to catch surprises before implementation.
> **Two load-bearing wiring claims I'd been carrying from subagent summaries turned out wrong** — and the
> design as literally written would have *failed mid-implementation*. But the deeper thesis ("the backend
> signals exist; the work is FE-side") **held**, and the corrected approach is feasible. Verdicts:

| # | Assumption | Verdict | Evidence | Corrected scope |
|---|---|---|---|---|
| R1 | FE can "consume `aiStateStore.runtime.installed`" (correct after restart) | **Refuted as written; feasible differently** | `setInstallState()` is called **only** from `aiStateStore.test.ts:37` — *never in production* → `runtime.installed` is **always `UNKNOWN`** (`aiStateStore.ts:191,298,647`). BUT the disk-derived authority `RuntimeActivationService.listInstalledVariants()` reaches the FE as `runtimeStatus.variants`, which `BrainSurface` already fetches. | Move 1 = `deriveAiState()` reads `runtimeStatus.variants.length>0` as the installed authority (extending the existing §2.B guard at `BrainSurface.ts:1030`). **FE-only; backend signal already exists.** Not the store field. |
| R2 | An independent reachability signal exists to split "Catching up…" from "Reconnecting…" | **Refuted as written; feasible differently** | `connection.reachable = !neverConnected && !stale` (`aiStateStore.ts:271`) — derived from the *same* staleness clock; `catching-up` is a *distinct* backend reconcile signal (`verdict.ts:110`, tempdoc 630), not the load case. BUT a dedicated cheap liveness endpoint **already exists**: `GET /api/runtime/live` returns 200 from an in-memory manifest read, no worker round-trip (`RuntimeProbeController.handleLive:59-66`). | Move 2 = add a lightweight FE poller for `/api/runtime/live`; feed `reachable` from **that**, so the verdict can tell "stale-but-live" (→ "Catching up…/Updating…") from "not live" (→ "Reconnecting…"). **FE-only; backend signal already exists.** Not a re-route of `reachable`. |
| R3 | The lock state is known where the resume card renders | **Confirmed (the risk is real)** | `historyLocked` is set only from `resumed.locked` *after* the Continue click returns 423 (`UnifiedChatView.ts:1503`); the resume **card** reads `localStorage` in the constructor (`conversationListStore.ts:48-62`), before any lock signal. | Move 3 needs more than an in-place gate: either store only `sessionId` + async-derive the preview from the backend (`listSessions` already returns `""` while locked), or surface a boot-time global lock signal. **Medium FE.** The *locked-transcript* half is already solid (`reasonFor('conversations.locked')`, §L4). |
| R4 | Brain install labels route through `present()` | **Partial** | "Not Installed"/"AI Offline" are **hardcoded inline** (`BrainSurface.ts:1057-1069`), not `present()`-routed — so editing them is low-risk and won't trip `check-presentation-purity` (which guards `ops.*.label` keys + resolver imports). Should reuse `reasonFor` vocabulary for consistency. | Low risk; string-level. |
| R5 | Tests exist to make a fix bite | **Confirmed** | `aiStateStore.test.ts`, `verdict.test.ts`, `BrainSurface.{sparkline,restartEta,reindex-coherence}.test.ts`, `conversationListStore.test.ts`, `UnifiedChatView.test.ts` all exist. | Add targeted regression tests per move. |
| R6 | "Not Installed after restart" reproduces in dev | **Unverified (likely env-specific)** | Dev uses repo model-discovery (Brain showed "Install AI [idle]", not the "Not Installed" card). Evidence basis stays sandbox-screenshot + code. | Honest limit; not a blocker. |

**Net effect on confidence.** The verification *earned its keep*: the design's two pivotal moves named the
wrong FE signals (an unpopulated store field; a staleness-derived `reachable`) — implementing as written
would have been a mid-build surprise. The corrected design is **feasible and FE-scoped** because the hard
backend halves already exist (`runtimeStatus.variants` disk-derived; `/api/runtime/live` cheap+independent;
the locked-transcript vocabulary; the verdict precedence machinery). Scope is *small-medium FE per move*, not
a one-line re-route and not backend work.

**Residual uncertainties (the honest remainder):**
- **Move 3 has an unresolved design fork** — async-preview-from-backend vs a boot-time lock signal. Needs a decision.
- **Move 2 adds a second poll loop** (liveness) — verdict precedence (`live-but-stale` vs `initial-load` vs `unreachable`) and a small perf/complexity cost to validate; the `catching-up` precedence slot at `verdict.ts:110` shows the machinery supports it.
- **Not empirically load-tested:** I substituted decisive handler-reading for the live experiments (E1/E2). `/api/runtime/live` is independent *by construction* (in-memory read), but a 2-minute "does it stay 200 while `/api/status` starves under ingest" check is a cheap first step at implementation time.
- Move 1 must keep *"installing"* (in-progress) distinct from *"installed"* (durable) so the progress UI still works off the ephemeral tracker.

**Confidence rating for the remaining implementation work: 7/10.** (Pre-verification it was an illusory ~higher, resting on two wrong wiring claims; post-verification feasibility is confirmed, the approach is corrected, backend signals are located, and tests exist — held back from 8–9 only by the move-3 approach fork, the unvalidated second-poller precedence/perf, and the absence of a live load-confirmation.)

### Implementation outcome (2026-06-25, worktree `562-truthfulness`)

- **Move 1 (installed-state) — implemented.** Not the FE re-wire (the `variants` signal is too loose — the
  bundled CPU `default` exists pre-download). Instead the design's sanctioned **backend projection**:
  `AiInstallService.getStatus()` recomputes the session-ephemeral `installedFully` once from on-disk model
  presence via the existing `InstallPlanner` (a returning user with models on disk reads "AI Offline / Start
  AI", never a false "Not Installed" + re-download). Guarded so it never clobbers a real install run.
  Verified: compiles, **unit tests pass** (`AiInstallServiceDiskRecomputeTest`); the live positive path is
  sandbox-only (dev's repo layout ≠ the registry install layout — R6).
  **Post-review hardening (critical-analysis pass):** (A) the one-shot guard is now consumed only on a
  *definitive* plan result — a transient first-call failure retries (capped) instead of permanently stranding
  the false "Not Installed"; (B) the plan→installed decision was extracted to a package-private
  `applyInstalledFromPlan()` seam so the **positive** recompute path now has a green unit test (models present
  → `installedFully` true; remaining-downloads → stays false), closing the `audit-without-test` gap.
- **Move 3 (locked-resume leak) — implemented + LIVE-VERIFIED.** The recent-session pointer no longer caches
  message content; the resume preview derives from the lock-safe backend list; `getRecentSessions` purges
  legacy plaintext at rest. Browser-confirmed: a seeded legacy plaintext entry was purged on read and never
  rendered.
- **Move 2 (connection "Catching up") — REVERTED; split to tempdoc 649.** Live validation **invalidated the
  premise**: the "Reconnecting under load" bug is **browser HTTP/1.1 connection-pool exhaustion** (5+ SSE
  streams saturate Chrome's ~6-per-host limit; the backend is provably responsive via a fresh connection),
  not backend slowness. Adding a `/api/runtime/live` poll can't fix it (it queues behind the same exhausted
  pool) and worsens the pressure. The real fix likely supersedes `explanation/27`'s "reachability via poll,
  not SSE" decision, so it is deferred to its own design pass — **tempdoc 649** (general idea + derived issue
  recorded; no solution chosen).
