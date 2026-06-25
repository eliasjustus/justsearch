---
title: "Agent developer-velocity: the parallel-agent substrate seams — worktree↔environment correspondence (Seams A/B, shipped) + shared-agent-state isolation (Seam C, designed) (friction catalogue + long-term design)"
type: tempdocs
status: active
created: 2026-06-20
updated: 2026-06-21
author: agent reflection (cross-session friction report), filed by agent
category: dx / agent-tooling / worktrees / dev-stack / velocity
related:
  - external-agent-harness-context-engineering
  - subagents-no-inheritance
  - explore-before-implementing
  - after-compaction-verify
  - never-checkout-in-main
---

# 618 — Improve agent developer-velocity: the recurring friction that wastes turns

> **What this document is.** A problem-statement SEED, filed from an agent's cross-session reflection on
> what actually cost turns and wall-clock time while shipping feature work (tempdoc 610 and its merges).
> It is **not investigated and not designed** — it catalogues observed friction so a future pass can decide
> which items are worth fixing. The filing agent will not work on it. **Every item below should be
> re-verified against the current codebase/harness before any design** — some are environment-specific,
> some may already be addressed, and a few are agent-discipline issues rather than tooling defects.

## Problem

Agents working in this repo repeatedly lose turns and time to **recurring, predictable friction** that is
orthogonal to the actual task — worktree physical-artifact overhead, a broken local model runtime, multi-agent
shared-`main` attribution, and a long tail of small tool-interaction quirks. None of these are hard blockers
individually, but together they are a steady tax on every feature session, and at least one (a worktree base-ref
trap) can silently cost a dozen-plus turns. The purpose of this tempdoc is to capture that friction so the
highest-leverage items can be reduced.

The catalogue below is grouped by impact. Where a root cause is visible it is named; where a cheap mitigation
is obvious it is noted — but these are pointers, not commitments.

## §1 — High-cost systemic traps

- **`EnterWorktree`-from-`origin` × unpushed-local-`main` (the most expensive single trap).** `EnterWorktree`'s
  default base ref is `fresh` = `origin/<default-branch>`. When local `main` has unpushed commits (e.g. a
  just-merged feature not yet pushed), a new worktree branches from a **stale base that lacks that feature** —
  so an agent can spend many turns building on top of code that "isn't there," then a long stretch diagnosing
  the contradiction before recovering by merging local `main` in. Two reasonable defaults (push-only-when-asked
  + branch-from-origin) compose into a silent landmine.
  - *Candidate directions (unverified):* default `EnterWorktree` to local `HEAD` for in-flight feature work;
    OR a cheap standing discipline — immediately after entering a worktree, assert the base contains the
    expected work (`git log -1 -- <known file>` / grep a known symbol) **before** writing code. Possibly a
    `EnterWorktree` post-create check that warns when `origin/main` ≠ local `main`.
  - *Cross-ref:* this is the kind of seam-assertion the `after-compaction-verify` rule already embodies for
    branch/dir; the same shape (verify-the-base) would prevent this.

- **Same-named files across `main` + N worktrees invite self-conflation.** An agent reading
  `ConversationEngine.java` in the main checkout and again in a worktree mentally merged the two, masking the
  base-ref bug above. *Direction:* agent discipline (cite full paths in reasoning), or tooling that makes the
  active worktree unmistakable in tool output.

## §2 — The worktree "physical-artifact" tax (every session)

- **Long-path failure on worktree deletion.** `git worktree remove --force` fails with *"Filename too long"*
  on deep `node_modules` paths and leaves the directory behind; recovery needs a Windows long-path delete
  (PowerShell `Remove-Item -LiteralPath "\\?\<path>" -Recurse -Force`). Reproduced across multiple sessions.
  *Direction:* a documented/automated long-path cleanup step in the worktree-removal path (with a junction
  safety-check first, since worktrees may contain junctions to shared `models`/`native-bin`/`node_modules`).
- **Per-worktree build artifacts.** Each worktree needs its own `node_modules` (manual junction, which can
  later materialize as a real dir), its own `:modules:ui:installDist` before the dev stack will start, and
  access to shared `models`/`native-bin`. The `models` junction is often **blocked** because the worktree's
  `models/` already holds tracked manifest files (a real dir, can't junction over).
  *Direction:* a single "prepare worktree for dev" helper that wires node_modules + models/native-bin access
  deterministically (the `JUSTSEARCH_MODELS_DIR` guidance exists but doesn't reach the MCP-spawned dev-runner).
- **Recurring stray uncommitted noise.** `package-lock.json` npm peer/optional churn and CRLF-only
  fixture/model files reliably reappear as "modified" after build/worktree ops, and must be identified and
  reverted before each merge so they don't pollute it. *Direction:* `.gitattributes` EOL pinning for the
  recurring CRLF files; investigate the lockfile churn source.

## §3 — The local model runtime (caps the best verification tier)

- **`ai_activate` → "Variant not installed: cuda12."** The llama-server runtime variant is absent where the
  runtime resolves it (`native-bin/` empty), although a binary exists at `modules/ui/build/llama-server/stage/`.
  Observed failing in **both** the main checkout and a worktree, so live LLM-behavior validation (does an
  excluded turn actually drop from the prompt? does compaction summarize? does the budget meter move on a real
  turn?) could not run at all — forcing a fall back to DOM-injection + unit tests. This permanently caps the
  highest-value verification tier and is not fixable by a feature agent without touching shared resources.
  *Direction:* make the dev-stack startup stage/resolve the variant deterministically (or surface a clear
  install path); a known-good local model+variant baseline the harness can guarantee.
- **Dev-stack startup multi-step friction.** Takeover required `:modules:ui:installDist` (a full vite build)
  before `start --distFrom` would succeed ("Head dist not found"). *Direction:* fold the needed dist build
  into the start path, or make the error actionable inline.

## §4 — Multi-agent shared-`main` overhead

- **"Is this red mine?" attribution.** Nearly every merge surfaced class-size reds (and once a
  `checkNoDirectJustsearchSysProp`/OCR red) that were **not** the merging agent's — each needing
  committed-vs-working-tree diffing to prove provenance before proceeding; one initially looked like the
  agent's own merge breaking the build. *Direction:* gate output that distinguishes "your diff caused this"
  from "pre-existing / other-agent WIP" (committed-baseline vs working-tree attribution).
- **Uncommitted/staged ambiguity + `main` advancing mid-task.** Another agent's in-progress `observations.md`
  and a multi-file WIP meant the merging agent had to verify disjointness manually and **could not** perform a
  normal "log the pre-existing issue to `observations.md`" because the file was contended; `main` also advanced
  (a new commit landed on top of the merge) during cleanup. *Direction:* clearer signals for contended files;
  a safe append-only path for `observations.md` that doesn't capture a neighbor's uncommitted changes.

## §5 — Small but recurring tool-interaction friction (death by a thousand cuts)

- **`Edit` exact-match failures** — whitespace/indentation/em-dash/line-wrap mismatches force a re-read + retry;
  likely the single most frequent micro-waste. *Direction:* fuzzier-but-safe match, or always Read-then-Edit a
  tight window.
- **`javascript_tool` async-return quirk** — `async` IIFEs returning an `await`ed value serialized as `{}`, so
  a successful browser action *looked* like it failed; cost confused retries until split into act-then-read.
- **Bash CWD persistence** — a `cd modules/ui-web` for npm left later relative `git add` paths doubled
  (`modules/ui-web/modules/ui-web/…`). *Direction:* prefer absolute paths; or per-call CWD.
- **Heredoc shell-quoting** — a `cat >> … <<'EOF'` for a long doc block failed on quoting; fell back to `Edit`.
- **`Read` auto-limit on very large files** — `UnifiedChatView.ts` (4000+ lines) forces constant
  offset/limit/grep navigation; the file's size is itself friction and is *why* its dual-render-path bug-class
  exists (see tempdoc 610 §F.3). *Direction:* this is also an argument for decomposing the largest FE files.

## §6 — The subagent-trust tax

- **Load-bearing subagent claims can be confidently wrong.** A confidence pass (source-verifying subagent
  findings) caught **two false cost claims** that would have mis-scoped work: "real `promptTokens` already flow
  via `onUsage`" (they are *discarded*) and "EPHEMERAL→PERSISTENT promotion is manifest-cheap" (it is a
  directory copy). Subagent output is a *starting point*, not a result — the `audit-without-test` /
  `subagents-no-inheritance` risk is real and recurring, and re-verification costs turns. *Direction:* this is
  largely agent discipline (already documented), but it argues for cheaper source-verification tooling and for
  briefing subagents to cite primary-source line evidence by default.

## Scope note for whoever picks this up

- **Re-verify before designing.** Several items are environment-specific (the model variant, long-path limits
  are Windows-specific) and some may already be fixed. Confirm each against current state.
- **Prioritize by frequency × cost.** The §1 base-ref trap (rare but very expensive) and the §2/§3 per-session
  taxes (frequent, moderate) are likely the highest leverage; §5 is high-frequency but low-per-incident.
- **Prefer cheap seam-assertions over new structure.** The worst trap (§1) is preventable by a single
  verify-the-base check, not new machinery — match the fix scope to each problem, per the repo's standing
  anti-over-build discipline.
- This document records *observations*, not a design. No investigation beyond the filing agent's own session
  experience has been done.

---

# Investigation (2026-06-20, take-over agent)

> **What changed.** The seed above was a problem-statement only. This section is the re-verification the seed
> asked for: every item was checked against the current `main` checkout, the live harness, the build graph, and
> primary-source Claude Code docs/tool-schemas. Status is **`investigated`**, not `complete` — verification +
> concrete, scoped fixes are written down here; *applying* the behaviour-changing ones (shared `settings.json`,
> build-task change, dev-runner change) is left for the user to green-light (`ask-when-uncertain`: these alter
> behaviour for **all** agents/worktrees, not just this task).

## Methodology & evidence tiers

Findings are tagged by how strongly they are established, to defend against the §6 subagent-trust failure mode
(which this investigation itself dogfoods — see "Subagent claims flagged" below):

- **T1 (primary, in-context):** read directly from a tool schema in *this* session, from `git`, or from repo
  source. Highest confidence — no intermediary.
- **T2 (primary, external):** official Claude Code docs / GitHub issues fetched by a `claude-code-guide`
  subagent with cited URLs. Trusted for *existence* of features; version numbers / issue IDs treated as
  unverified (see flag note).
- **T3 (reproduced):** observed live in this session (e.g. an orphaned worktree on disk).

Each item also gets a **fix-tier** for *who can act*:

- **A — repo-fixable:** code/config we own in this repo (gradle, dev-runner, `.gitattributes`).
- **B — harness-config-fixable:** Claude Code `settings.json` / hooks — we own these files too, but they
  change harness behaviour.
- **C — agent-discipline / upstream-harness:** not fixable in-repo; mitigated by a documented discipline or an
  upstream Claude Code change.

## Verdict per item

### §1 — base-ref trap — **CONFIRMED, armed live, cheap fix exists (fix-tier B)**

- **The trap is real and the seed's mechanism is exactly right.** T1: the `EnterWorktree` tool schema in this
  session states verbatim — *"The base ref is governed by the `worktree.baseRef` setting: `fresh` (default)
  branches from origin/<default-branch>; `head` branches from your current local HEAD."*
- **It is armed in this repo right now.** T1: `worktree.baseRef` is **unset** in project `.claude/settings.json`,
  in `.claude/settings.local.json`, and in `~/.claude/settings.json` → the `fresh`=origin default is in force.
  And `git rev-list --count origin/main..main` = **13**: local `main` is 13 commits ahead of `origin/main`. So a
  worktree created today would branch from a base missing 13 merged commits — the landmine, loaded.
- **The seed under-scoped the fix — it is a one-liner, not new machinery.** T2 (official docs, cited): the
  fix is to set, in committed project `.claude/settings.json` (shared by every agent):
  ```json
  { "worktree": { "baseRef": "head" } }
  ```
  This makes "branch from the freshest local work" the default *by construction*, which is exactly correct for
  **this** repo's merge model (`branch-safety.md`: `main` is the local integration point, pushed only when asked,
  worktrees are built *on top of* merged local work — origin is the stale ref here, not the fresh one).
- **Critical assessment / why keep the seam-assertion too.** Don't treat the setting as sufficient on its own.
  Two reasons for defence-in-depth: (a) the setting has a history of version-specific bugs in the harness
  (subagent-reported, unverified — flagged below), so a build where it is silently ignored must still be
  survivable; (b) `head` only fixes "origin is stale"; it does **not** fix "local `main` itself lacks the one
  feature this task needs." Both collapse to the same cheap check the `after-compaction-verify` rule already
  embodies: **immediately after entering a worktree, assert the base contains the expected work before writing
  code** — e.g. `git log -1 --oneline -- <known-file>` or grep a known symbol. This is ~1 turn and converts a
  dozen-turn silent failure into an immediate, legible one.
- **Recommended:** set `worktree.baseRef: "head"` (primary, fix-tier B) **and** add the post-enter base-assertion
  to `branch-safety.md`'s worktree section as a standing discipline (fix-tier C). The two compose; neither alone
  is complete.

### §1b — same-named files across worktrees — **PLAUSIBLE, agent-discipline (fix-tier C)**

- Not separately reproducible (it is a reasoning-conflation failure, not a tool defect), but the *enabling
  condition* is real and live: T1 `git worktree list` shows 4 trees (`main` + 604/609r/612), each with its own
  `ConversationEngine.java`/`UnifiedChatView.ts`. The only durable mitigation is discipline — **cite full
  absolute paths in reasoning** — which `context-efficiency.md` already half-implies ("don't `cd` to another
  worktree"). Low-leverage; fold a one-line "cite the full worktree-qualified path when reasoning about a file
  that exists in multiple trees" note into that rule if anywhere.

### §2 — worktree physical-artifact tax — **CONFIRMED (mixed fix-tiers)**

- **Long-path delete failure — CONFIRMED live (T3).** `.claude/worktrees/587-gpu-capability-resolver/` exists
  on disk but is **not** a registered git worktree (`.git/worktrees/` holds only 604/609r/612) — a textbook
  leftover from a failed `git worktree remove` that the seed describes. This is *real, present, and someone
  else's*: per `branch-safety.md` rule #6 I did **not** delete it; flagging for the user. Cleanup needs the
  documented Windows long-path delete (`Remove-Item -LiteralPath "\\?\<path>" -Recurse -Force`) after a junction
  safety-check.
  - *Fix direction (T2 confirms feasibility, fix-tier B):* `WorktreeCreate`/`WorktreeRemove` hooks **exist**
    (named in the `EnterWorktree` schema itself, T1; input schemas confirmed T2). A `WorktreeRemove` command hook
    can run a junction-safe `\\?\`-prefixed recursive delete so removal never leaves an orphan. There is **no**
    dedicated *post-create* hook (T2) — the closest is putting setup logic inside `WorktreeCreate` — which also
    bears on the next bullet.
- **Per-worktree build artifacts / no prep helper — CONFIRMED (T1, fix-tier A/B).** No worktree-prep script
  exists (searched `scripts/**`; only incidental token matches). The `JUSTSEARCH_MODELS_DIR` guidance lives in
  prose (`CLAUDE.md`, `branch-safety.md`) but nothing wires it into the MCP-spawned dev-runner. A single
  "prepare-worktree-for-dev" helper (node_modules junction + `models`/`native-bin` access + the `installDist`
  the dev stack needs) is genuinely unbuilt and is the seed's correct direction. Could be a `scripts/dev/`
  helper invoked manually, or hung off a `WorktreeCreate` hook for automation.
- **Recurring uncommitted noise — ROOT CAUSE FOUND for the lockfile half (T1, fix-tier A).** The
  `package-lock.json` churn is **not** an EOL problem — `git diff` shows it toggling npm `"peer": true` /
  `"optional"` metadata and pruning platform-specific optional `@emnapi/*` packages. Root cause: the gradle web
  build runs **`npm install`** (`build-logic/src/main/kotlin/conventions/NpmTasks.kt:46`,
  `commandLine(npm, "install")`), and `npm install` *rewrites* the lockfile. **Fix:** switch the build-time
  install task to **`npm ci`** (install strictly from the lockfile, never rewrite it; errors if lock and
  `package.json` drift). Keep `npm install` only for the explicit lockfile-regen workflow (`/lockfile`). This is
  a ~1-line change with one caveat: `npm ci` requires lock↔package.json to already be in sync (it deletes
  `node_modules` first), which is correct for a reproducible build but means a stale lock fails loudly instead of
  silently self-healing — arguably the desired behaviour.
  - The **CRLF** half is *already* mostly handled: `.gitattributes` pins `* text=auto eol=lf` plus explicit
    `eol=lf` for the synonym DSL files that historically showed as "M forever" (observations #32/#214). If
    specific fixture/model files still reappear as CRLF-modified, the fix is the same shape — add an explicit
    `eol=lf` (or `binary`) line for those paths. No new mechanism needed; just an additive `.gitattributes`
    entry per offending glob as they are identified.

### §3 — `ai_activate` "Variant not installed: cuda12" — **CONFIRMED, root cause isolated, MORE fixable than the seed believed (fix-tier A)**

This is the highest-value correction to the seed. The seed called it *"not fixable by a feature agent without
touching shared resources."* The investigation shows a **precise dev-only path gap** with a cheap, in-repo fix.

- **Recurring across sessions (T1):** the exact string appears in tempdocs 549, 583, 610 and the seed — four
  independent sessions. Not a one-off.
- **Root cause (T1, source-traced):** two paths that must agree, don't, in dev:
  1. **Where the binary is built:** the gradle build stages llama-server into
     `modules/ui/build/llama-server/stage/` (CPU exe present, verified on disk) and the CUDA variant into
     `…/stage/variants/cuda12/` (gated by `includeCudaVariant`, which defaults `true` — `modules/ui/build.gradle.kts:377`).
  2. **Where the runtime looks:** `RuntimeActivationService.resolveVariantsRoot()`
     (`modules/app-services/.../ai/runtime/RuntimeActivationService.java:989`) resolves variants from
     `{aiHome}/native-bin/llama-server/variants/` then, in dev, falls back to
     `{repoRoot}/modules/ui/native-bin/llama-server/variants/`. And the dev-runner
     (`scripts/dev/dev-runner.cjs:354-364`) probes for the exe only under
     `modules/ui/native-bin/llama-server/variants/cuda12/…` and repo-root `native-bin/llama-server/…`.
  - **The gap:** `modules/ui/native-bin/` is **empty** and repo-root `native-bin/` holds only `tesseract`
     (both verified on disk). The `stage → native-bin` copy happens **only** in `bundleSidecarResources`
     (`modules/ui/build.gradle.kts:1363-1366`, `into("native-bin/llama-server")`) — i.e. only for the *packaged
     Tauri sidecar*, never for the dev stack. So on any fresh checkout/worktree the dev runtime resolves an
     empty variants root → `RUNTIME_VARIANT_NOT_INSTALLED` (`RuntimeActivationService.java:379`).
- **Two sub-problems, distinct fixes:**
  - *CPU/`default` path (cheap):* the CPU baseline **is** staged at `…/stage/llama-server.exe`. A dev-runner
    fallback that also probes `modules/ui/build/llama-server/stage/{llama-server.exe, variants/cuda12/…}` (a
    ~4-line addition to the `dev-runner.cjs:354` probe chain), **or** a small gradle `syncLlamaToDevNativeBin`
    task that copies `build/llama-server/stage/**` → `modules/ui/native-bin/llama-server/**`, makes activation
    work without touching any shared resource. The G17 `default`-variant fallback
    (`RuntimeActivationService.java:371-376`) already lets a flat baseline serve as `default`.
  - *`cuda12` path (heavier):* the CUDA prebuilt is a large download and was **not** present in this checkout
    (`…/stage/variants/` absent). Genuine GPU activation needs that download + stage→native-bin. But the more
    reliable dev target is to have the harness/dev-runner request the **`default`** variant (CPU baseline) for
    "is the LLM tier reachable?" verification, reserving `cuda12` for explicit GPU runs — which removes the
    blocker for the *verification* use-case the seed actually cared about (`use-every-verification-tier`).
- **Recommended:** add the dev-runner stage-dir fallback (fix-tier A, cheap, unblocks the verification tier) and
  file the cuda12 download/stage-to-native-bin as the heavier follow-up. This materially changes the seed's
  conclusion: the *verification-tier* blocker **is** feature-agent-fixable.
- **Dev-stack `installDist` friction (sub-item):** confirmed real and already partly documented — `CLAUDE.md`
  Common Pitfalls already warns "dev stack runs stale jar; run `:modules:ui:installDist` explicitly." Folding the
  needed dist build into the `start --distFrom` path (or making the "Head dist not found" error,
  `dev-runner.cjs:964`, print the exact `installDist` command) is the actionable inline-error direction.

### §4 — multi-agent shared-`main` overhead — **CONFIRMED live (T3, fix-tier A/C)**

- **Demonstrated by this very session.** On entry, the `main` checkout held 40+ modified files **none of which
  this task touched** (other agents' WIP), plus untracked tempdocs 611/614/616/617 and a new
  `VduCapabilityState.java`. "Is this red mine?" attribution is a real, current tax — exactly the seed's claim.
- **`observations.md` contention is real too:** I could not follow the standing `log-pre-existing-issues` rule
  for an incidental finding (the stale "Build the React app" comment at `modules/ui/build.gradle.kts:203`,
  post-ADR-0032) because `observations.md` is itself contended in the working tree — the precise failure the seed
  names. (Logged here instead.)
- *Fix directions:* (a) for attribution, a thin wrapper that reports gate reds with **committed-baseline vs
  working-tree** provenance (`git stash`-free diff: `git diff <merge-base> -- <file>` vs `git diff` — fix-tier
  A); (b) for `observations.md`, the `CLAUDE.md` rule already prescribes an **append-only `echo >>`** which is
  the safe pattern — the residual risk is only that a *staging/commit* sweeps a neighbour's concurrent edit, so
  the discipline fix is "stage `observations.md` in isolation, never `git add -A`." Mostly discipline (C) with a
  small tooling assist possible (A).

### §5 — small recurring tool friction — **MIXED; mostly upstream-harness / discipline (fix-tier C), one repo lever (A)**

| Item | Status | Tier | Note |
|---|---|---|---|
| `Edit` exact-match failures | Real, upstream | C | Harness behaviour; mitigation is discipline — Read a tight window immediately before Edit, copy bytes verbatim incl. indentation/em-dashes. Not repo-fixable. |
| `javascript_tool` async `{}` | Real, upstream (MCP) | C | claude-in-chrome quirk; mitigation (act-then-read split) is already the documented browser-automation pattern in `CLAUDE.md`. |
| Bash CWD persistence footgun | Real, T1-confirmed | C | The Bash tool **does** persist CWD across calls (stated in the tool description in-context). The doubled-path bug is therefore an agent error: **prefer absolute paths** (already in `context-efficiency.md`). Note: subagent reports a v2.1.144 regression where CWD *resets* — unverified — which only strengthens "use absolute paths." |
| Heredoc shell-quoting | Real, upstream | C | Use `Write`/`Edit` for long blocks instead of `cat >> <<'EOF'`; the bash-guard hook already nudges away from bare redirects. |
| `Read` auto-limit on huge files | Real, T1-confirmed | **A** | `UnifiedChatView.ts` is now **5421 lines** (verified) — *bigger* than the seed's "4000+". This is the one §5 item with a real repo lever: **decompose the largest FE files**. Cross-refs tempdoc 610 §F.3 (the dual-render-path bug-class *caused* by the file's size) and the untracked tempdoc 614 (UI IA separation). The `intervene` hook's auto-limit is a symptom; the file size is the cause. |

### §6 — subagent-trust tax — **CONFIRMED as standing risk; already governed (fix-tier C)**

- The seed's two cited false claims are the documented `audit-without-test` / `subagents-no-inheritance`
  failure mode, already load-bearing in `CLAUDE.md` and `agent-lessons.md`. No new rule needed (the
  `before-appending-to-rules` gate would reject a duplicate). The one cheap, additive improvement: **brief
  subagents to cite primary-source file:line evidence by default**, so re-verification is a glance not a
  re-derivation — fold one clause into the existing `delegating-to-subagents` rule rather than adding a new rule.
- **This investigation dogfooded it.** See the flag note below — the `claude-code-guide` subagent returned
  confident version numbers and GitHub issue IDs that I deliberately did **not** promote to T1.

## Subagent claims flagged (NOT relied upon)

Per §6, these `claude-code-guide` outputs are recorded as **unverified** and must not be treated as fact without
an independent check; the recommendations above are built only on the T1 facts that survive without them:

- "`worktree.baseRef` introduced in v2.1.133; **ignored / regressed in v2.1.144** (issue #60588)." If true, it
  means the setting may not take effect on some harness versions — which is *already* covered by keeping the
  §1 base-assertion as defence-in-depth, so the recommendation is robust either way. **Action for user:** before
  relying on the setting, confirm it takes effect on the installed Claude Code version (create one worktree,
  check its base).
- Specific issue IDs (#42837 Bash-CWD regression, #31471 statusLine, #60235/#60588) and exact dates — treat as
  leads, not citations.
- The exact `{"worktree":{"baseRef":"head"}}` nesting is T2; confirm via `/config` or the `update-config` skill
  (which knows the canonical shape) before committing it.

## Prioritized recommendations (frequency × cost)

| # | Item | Fix | Tier | Cost | Leverage |
|---|---|---|---|---|---|
| 1 | §1 base-ref trap (armed: main 13 ahead of origin) | Set `worktree.baseRef:"head"` in committed `.claude/settings.json` **+** add post-enter base-assertion to `branch-safety.md` | B + C | ~5 min | **Highest** — disarms the dozen-turn silent trap by construction |
| 2 | §3 `ai_activate` verification blocker | Dev-runner stage-dir exe fallback (`dev-runner.cjs:~354`) and/or a `syncLlamaToDevNativeBin` gradle task; request `default` variant for LLM-reachability checks | A | small | High — restores the top verification tier on fresh checkouts |
| 3 | §2 package-lock churn | `npm install` → `npm ci` in `NpmTasks.kt` build task | A | ~1 line | Medium — removes per-build merge-polluting noise |
| 4 | §2 worktree cleanup/prep | `WorktreeRemove` hook doing junction-safe `\\?\` long-path delete; a `scripts/dev/prepare-worktree` helper | A/B | medium | Medium — removes per-session tax + orphan-dir accumulation |
| 5 | §4 attribution | gate-red provenance wrapper (committed-baseline vs working-tree) | A | medium | Medium (multi-agent only) |
| 6 | §5 large-file decomposition | decompose `UnifiedChatView.ts` (5421 lines) — see 610 §F.3 / 614 | A | large | Medium but couples to a known bug-class |
| 7 | §6 subagent briefing | add "cite primary-source file:line by default" clause to `delegating-to-subagents` | C | tiny | Low-medium |

## Open questions for the user (before applying anything behaviour-changing)

1. **Apply #1 now?** Setting `worktree.baseRef:"head"` in committed `.claude/settings.json` changes worktree
   creation for **every** agent. Recommended, but it is a shared-config decision — confirm before I edit it.
2. **`npm ci` switch (#3):** acceptable that a drifted lockfile will now *fail the build* instead of silently
   self-healing? (I think yes — it is the more correct, reproducible behaviour.)
3. **Orphaned `.claude/worktrees/587-gpu-capability-resolver/`:** it is another agent's leftover; want me to
   remove it via the long-path delete, or leave it for that session's owner?
4. **Scope of follow-through:** this tempdoc is now `investigated` with scoped fixes. Which of the prioritized
   items (if any) should become committed work in *this* tempdoc vs. handed to dedicated follow-ups?

---

# Additional session findings (2026-06-20, tempdoc-612 agent)

> A second cross-session reflection, filed after shipping **tempdoc 612** (a FE-only Activity-feed change:
> implement → browser-validate → merge into a moving, multi-agent `main`). Same disclaimer as the seed —
> *observations, re-verify before designing.* Most items **corroborate** the catalogue above with fresh
> evidence; **§7 is genuinely new and was this session's single largest time sink.** Tiers per the
> methodology above (T1 in-context primary / T3 reproduced-live; fix-tier A repo / B harness-config / C
> discipline).

## §7 — NEW high-cost: no first-class path to live-validate a worktree's FRONTEND

§3 frames dev-stack friction around the *model runtime* and *startup*; this is a different gap and, for a
FE-only task, it cost ~15 turns for what should be ~2. **Root cause (T1/T3):** the MCP dev-runner serves
**both backend and Vite from the main checkout**, so a worktree's changed FE is never what the running stack
serves. To see worktree FE in a real browser the agent must hand-roll a *second* Vite
(`VITE_JUSTSEARCH_API_PORT=<backend> npx vite --port <free> --strictPort`) pointed at a backend — and that
path then compounded:

- **Backend contention** — the only running stack was owned by another active session (CONTENTION), so I
  could not start my own; had to proxy my worktree Vite into theirs read-only (the `start` was correctly
  refused). FE-only changes don't *need* a fresh backend, but there is no sanctioned "borrow the running
  backend, serve my FE" path.
- **Port collision** — `:5174` was already taken by *another* agent's worktree Vite; had to scan to a free
  port (`--strictPort` to fail-fast rather than silently pick another).
- **Per-origin `localStorage`** — the data that drives the feature under test (the FE effect journal) lives
  in `localStorage`, which is per-origin; on the new port's origin it was empty, so the scenario didn't
  exist until regenerated.
- **Full-app boot HUNG** on the worktree Vite — blank page, `jf-shell` *defined but never mounted*, **no
  console error** — several turns of DOM/console/manifest diagnosis before pivoting to mounting the single
  changed component directly via injected JS against the proxied live stream (which gave a clean, real
  validation immediately).

*Direction (unverified):* a one-command `serve-worktree-fe --api-port <p>` helper that picks a free port,
pins the proxy target, and asserts it serves the worktree's code (grep a known symbol over the served
module) — the FE sibling of §3's "prepare worktree for dev," and arguably higher-frequency for FE work than
the model-runtime blocker. Failing that, a documented **"mount-the-changed-component-directly"** validation
recipe (it sidesteps the boot hang entirely and is a reliable real-browser check for a single component).

## §8 — NEW small tool-interaction items (extend §5)

- **`/api/action-ledger` (and likely other read-only diagnostic GETs) are not on the MCP
  `justsearch_dev_api_call` allowlist** (T1 — the tool returned the allowlist on rejection). Forced a
  browser-side `fetch` workaround for a read-only verification fetch. *Direction:* widen the allowlist to
  read-only diagnostic GETs, or add a generic guarded `GET /api/*` read path.
- **`browser_batch` rapid navigations race the SPA boot** (T3) — chaining 5 hash-route navigations + a
  screenshot in one batch captured a blank page because the app had not mounted. *Direction:* agent
  discipline (one nav, then readiness-poll, then capture); worth a one-line note in the browser guidance.
- **`sleep ≥1s` hard-block catches benign one-shot waits too** (T1, `bash-guard`) — `sleep 4` to let Vite
  warm was rejected; restructured to condition-polling / the browser `wait` action. The guard is correct;
  the existing "condition-poll / jseval" guidance is the answer, it just adds a turn for legitimate waits.

## §9 — Corroboration of existing items (fresh evidence; no new direction)

| Existing item | This session's evidence |
|---|---|
| **§1 base-ref trap** | Hit it: local `main` was **57 commits ahead of `origin`**, and the file I was extending (`messageRouting.ts`) **did not exist in `origin/main` at all** — a starker instance than the seed's 13. A pre-write base check caught it; manual `git worktree add … HEAD` recovered. Strongly confirms the `baseRef:"head"` + post-enter base-assertion recommendation. |
| **§2 long-path / junction** | The `node_modules` **junction** (created to skip a slow `npm ci`) is itself a deletion hazard — `git worktree remove` / `rm -rf` can delete *through* the link into main's real `node_modules`. Safe recipe: `rmdir` the junction **first**, verify main's `node_modules` survived, *then* remove the worktree. Worth folding into the §2/#4 cleanup helper as an explicit junction-first step. |
| **§4 observations.md contention** | **Reproduced as actual data loss** — a note I appended to `observations.md` was silently wiped when another session reset/committed that file; only noticed during the merge, had to re-add it. Concrete support for the "safe append-only path for `observations.md`" direction. Also: `main` advanced **+14 commits** with heavy other-agent uncommitted WIP mid-task — handled by resolving the merge in the **isolated worktree** then fast-forwarding `main` (touching only my files, leaving the neighbor's WIP intact); that is a reusable safe-merge recipe worth documenting alongside §4. |
| **§5 `javascript_tool` `{}` quirk** | Confirmed independently — async IIFEs returned `{}`; top-level `await` fixed it. |

---

# Long-term design (2026-06-20, design pass)

> Design theory, scoped to the problem this catalogue actually has — written in the same spirit as 606
> ("long-term design theory, scoped to the problem the system actually has; no code lands until the user
> approves a direction"). It investigates what already exists first, conforms to it rather than forking a
> parallel mechanism, and deliberately separates *recognizing a principle* from *building generalized
> structure*. It is general, not implementation-level. (Filed via the editor, not a heredoc — this section's
> first append attempt failed on §5's exact heredoc-quoting friction. Dogfood noted.)

## The one diagnosis under the catalogue

Strip the 9 sections to their load-bearing cause and the high-cost items collapse into **one** shape:

> **The development environment silently fails to correspond to the worktree the agent is actually working
> in, and nothing asserts that correspondence at the point the agent starts relying on it.**

- **§1 base-ref trap** — the worktree's *base commit* does not correspond to the work the agent expects
  (origin lacks 13 / 57 unpushed commits; the target file doesn't exist in the base at all). Correspondence:
  `worktree base ↔ expected work`.
- **§3 model-runtime** — the *resolved* llama-server path (`native-bin/…`) does not correspond to where the
  build *staged* it (`build/llama-server/stage/…`). Correspondence: `built artifact ↔ runtime resolution path`.
- **§3 / §7 dev-stack** — the *running* services do not correspond to the worktree's code: the dist that
  launched is main's, not the worktree's (the §3 `installDist` staleness, already named in 606 D2); and the
  served Vite is main's, not the worktree's FE (§7). Correspondence: `running services ↔ worktree source`.
- **§2** — the worktree's *prepared substrate* (node_modules / models / native-bin access) does not correspond
  to what the dev tooling needs; and teardown leaves orphans because removal doesn't correspond to what was
  created (junctions, long paths). Correspondence: `prepared environment ↔ tooling requirements`.

§4 (shared-`main` contention/attribution) and §5/§6 (harness quirks / subagent trust) are a **different**
class — they are not correspondence failures, and the design below deliberately does **not** absorb them
(see *Scope boundaries*). The structural core of 618 is §1 + §2 + §3 + §7.

## What already exists (investigated, not assumed) — and why this is *completing* a seam, not forking one

This repo already enforces "**correspondence asserted, not assumed**" pervasively — but almost entirely for
*product* artifacts and *runtime* state, and only at *one* point for the dev environment:

| Domain | Existing correspondence-assertion seam | Evidence |
|---|---|---|
| Product wire/contract | discipline-gate kernel **producer↔consumer** gates (readiness-reason-codes, search-degradation, capability-availability, contract-projection, wire-type-single-authority, …) | `CLAUDE.md` gate list |
| SSOT source↔copy | `ssot-catalog-sync` gate (root `fields.v1.json` ↔ classpath copy) | `CLAUDE.md` |
| Running app readiness | `verify-dont-guess` invariant + `/api/health`, `/api/debug/state`, `/infra/capabilities`, `ReadinessDimension` | Hard Invariant #4 |
| Agent session lifecycle | hooks at **every** transition — SessionStart, Pre/PostToolUse, PreCompact, SubagentStart/Stop, Stop, SessionEnd | `.claude/settings.local.json` |
| **Dev-stack ↔ worktree** | **606**: lease records build **provenance** (worktree + commit); verdict **cross-checks lease-claimed vs backend-reported** provenance; `distFrom`, `rebuildFirst`, `displacedNotice` | 606 §D2 / Piece 2; `branch-safety.md` §Shared Dev Stack |

The last row is the key finding: **606 already started exactly this seam for the dev-stack jar** — "the dist
that launched is main's, not the worktree's" is 606's D2, and its fix is provenance-on-the-lease plus a
wire cross-check (assert what is *actually* running matches what is *claimed*). §7's own proposed FE fix
("grep a known symbol over the served module") is that identical cross-check applied to Vite.

So the design is **not a new authority**. It is: *generalize the worktree-correspondence assertion 606 built
for the dev-stack jar to the remaining dev-environment seams, and instantiate the one agent-lifecycle
transition the repo hooks everywhere except* — worktree create/remove.

## The design — worktree↔environment correspondence, across the two lifecycle seams an agent crosses

One principle, applied at the transitions where correspondence is first relied upon. No central object; two
distinct seams that share the principle (recognize the shape; do not fuse the implementations).

### Seam A — Worktree lifecycle (currently prose-only → instantiate the unused harness hooks)

The repo governs every agent-lifecycle transition with a hook **except** worktree create/remove
(`WorktreeCreate` / `WorktreeRemove` exist in the harness — named in the `EnterWorktree` schema — but are
unwired). This is the missing half.

- **Create — set correspondence right by construction, then assert it.** Default `worktree.baseRef:"head"`
  (committed `.claude/settings.json`) so the base corresponds to the freshest *local* work by default —
  correct for this repo's local-`main`-is-the-integration-point model. Then a `WorktreeCreate` hook (a) does
  the deterministic *prepare* step §2 wants (node_modules / models / native-bin access — junction-aware), and
  (b) emits the base-correspondence assertion as a **legible verdict** (does the base contain expected work?),
  converting §1's silent dozen-turn failure into an immediate, named one. Defense-in-depth matters because the
  setting has had harness-version bugs — the assertion holds even when the config silently doesn't.
- **Remove — teardown corresponds to what create made.** A `WorktreeRemove` hook does junction-first,
  long-path-safe (`\\?\`) teardown so removal never deletes *through* a junction into main's real
  `node_modules` (§9's hazard) and never orphans a directory (the live `.claude/worktrees/587-…` leftover).

This conforms to the repo's existing hook-based lifecycle governance; it adds no new governance layer, it
fills two empty transition points with the same kind of hook already used at the other seven.

### Seam B — Dev-stack lifecycle (already worktree-aware via 606 → extend its coverage)

606 made the dev-stack *jar* worktree-provenance-aware. Two correspondence gaps remain on the same seam, and
belong to the same provenance/cross-check authority — extend it, do not replace it:

- **Model-runtime resolution (§3).** The dev-stack readiness check (`preflight`, the existing authority) and
  the dev-runner exe-probe should assert `built-artifact ↔ resolved-path` correspondence — i.e. resolve the
  llama-server variant from where the build actually stages it, or fail with the exact stage→native-bin
  remedy — instead of letting `ai_activate` dead-end at runtime. This restores the top verification tier and
  is, contrary to the seed's pessimism, feature-agent-fixable for the `default`/CPU verification case.
- **FE serving (§7).** The dev-stack should have a sanctioned *worktree-FE* mode — "serve this worktree's
  built FE against a (possibly borrowed, read-only) backend, on a free port, and **assert the served module
  is the worktree's code**." This is 606's running↔worktree cross-check, applied to Vite. It also subsumes the
  §7 sub-frictions (a sanctioned borrow-the-backend path removes the CONTENTION dead-end for FE-only work; a
  free-port + `--strictPort` removes the collision).

### Narrow correctness (not a seam): build idempotence

§2 lockfile churn is *not* lifecycle structure — it is a build step (`npm install`) mutating its declared
input. The scoped fix is `npm install → npm ci` for the build task (regen stays a separate workflow). It
belongs here only because it currently *manufactures a false correspondence-violation signal* (a "modified"
lockfile that isn't yours), polluting §4's attribution problem. Fix it as correctness, not as design.

## Scope boundaries (what this design deliberately does NOT build)

Matched to the problem, per the standing anti-over-build discipline and 606's own out-of-scope ethos:

- **No worktree contention/takeover/queue/reaper layer.** Worktrees are *isolated* (one per agent) — the 606
  acting-layer (arbitration of a single shared resource) has **no worktree analog**. Seam A needs only the
  *sensing/readiness* half (assert + report), never arbitration.
- **No new authority object / no "unified dev-environment framework."** Seams A and B share a *principle*, not
  an implementation. Building a generalized correspondence engine now would be the premature abstraction the
  user warned against.
- **§4 and §5/§6 stay out of the structural core.** §4 is shared-state *contention* (the dev-stack's class,
  already isolated *by* worktrees); its residual friction (attribution, `observations.md` data-loss) is a
  small tooling assist + discipline, not this seam. §5/§6 are upstream-harness / agent-discipline.

## What each catalogue item maps to

| Item | Resolved by | Kind |
|---|---|---|
| §1 base-ref trap | Seam A: `baseRef:"head"` + WorktreeCreate base-correspondence assertion | conform (harness config + unused hook) |
| §2 prepare / junction / long-path teardown | Seam A: WorktreeCreate prepare + WorktreeRemove safe-teardown | conform (unused hooks) |
| §2 lockfile churn | Narrow correctness: `npm ci` | one-line fix |
| §3 model-runtime resolution | Seam B: extend `preflight` / dev-runner artifact-correspondence | extend 606/preflight |
| §3 installDist staleness · §7 FE serving | Seam B: worktree-FE mode + running↔worktree cross-check | extend 606 |
| §4, §5, §6 | out of core — discipline + small assists | unchanged |

## The principle and its reach (recognized, not built)

**Principle — *dev-environment ↔ worktree correspondence is asserted, not assumed.*** It is a *specialization*
of the system-wide invariant this repo already enforces for product artifacts and runtime state: **two states
that must correspond must have their correspondence asserted at the point of use** (the gate kernel's
producer↔consumer family, `ssot-catalog-sync`, the runtime health/readiness authorities, and 606's
provenance cross-check are all instances). 618 is the recognition that **the agent's own development substrate
— the highest-frequency source of wasted turns — is the under-guarded mirror of a principle the product code
guards obsessively.**

- **Where else it applies (candidate scope, not a mandate to build):**
  - `installDist` staleness (already documented as a *prose* pitfall in `CLAUDE.md`) — a current violation:
    `staged jar ↔ running jar` correspondence assumed, not asserted. 606 Piece 2 is the targeted fix; the
    principle says it is the *same* shape as §1/§3/§7.
  - Any future `build-output ↔ runtime-resolution-path` pair (the §3 shape) — e.g. ORT CUDA variant, tesseract
    runtime, AOT caches — each resolves from a path a build must populate.
  - The FE generated-types / schema pipeline already *honors* it (gate-enforced) — a positive example showing
    the principle is real and worth conforming to.
- **Existing violations to name (do not fix beyond 618's scope):** §1, §3, §7, and the `installDist`-staleness
  pitfall are all live instances; the SSOT dual-copy and FE-wire pipelines are the guarded counter-examples.
- **Deliberately not generalized now.** Per the user's separation of *recognizing* from *building*: this pass
  records the principle and its candidate scope. The present problem (618) requires only Seam A's two hooks +
  Seam B's two extensions — it does **not** require a generalized "correspondence authority," and building one
  would violate the same anti-over-build discipline 616 invokes ("the bar for new scaffolding is: removes a
  real recurring failure without colliding with what we have"). If a third independent `build-output ↔
  runtime-path` violation appears, that is the trigger to consider lifting §3's fix into a shared dev-side
  readiness check — not before.

## Relation to the prioritized recommendations above

The earlier table stays valid as the *execution order*; this section supplies the *why they cohere*: items
1, 2, 4 are Seam A + Seam B (one principle), item 3 is the narrow correctness fix, items 5–7 are the
out-of-core assists. The design does not enlarge the recommended work — it explains that the high-leverage
subset is a single seam being completed, not seven unrelated patches.

---

# Confidence / risk register (2026-06-20, de-risk pass)

> A pre-implementation pass that targeted the *load-bearing assumptions* the design rests on (not the
> diagnoses, which are T1-solid). Each probe was read-only or a reversible/throwaway experiment; **no feature
> work was done.** Evidence tiers as above (T1 in-context primary / T2 official-docs primary / T3
> reproduced-live). Outcome: most assumptions verified; **two design adjustments** surfaced, and **one live
> proof remains blocked by stack contention.**

## What was checked, and what it changed

- **Seam A base-ref (§1) — VERIFIED (T2 primary docs), confidence raised.** The official worktrees doc states
  the exact shape `{"worktree":{"baseRef":"head"}}`, values `"fresh"`/`"head"` only, default `origin/HEAD`
  (falling back to local HEAD if no remote). **Bonus:** subagent `isolation:"worktree"` worktrees use the
  *same* setting — so the one line also closes the subagent base-ref trap that `branch-safety.md` notes. The
  subagent-reported regression (#60588) is **not corroborated** by primary docs → stays unverified; the
  post-enter base-assertion remains the defense-in-depth that makes the recommendation robust regardless.
- **Seam A prep/teardown (§2) — ADJUSTED (T2).** The "instantiate the unused WorktreeCreate/WorktreeRemove
  hooks" framing was too clean:
  - `WorktreeCreate` **replaces** the default `git worktree` logic entirely (the hook must *create* the
    worktree and return its path; failure/missing-path fails creation). So it is a heavy "own-creation" hook,
    not a light post-create prep step — and it **disables `.worktreeinclude`**.
  - `WorktreeRemove` is **side-effect-only / oriented at non-git VCS**; it **cannot replace** git's own
    removal, and Claude's cleanup runs `git worktree remove` — the exact command that fails on long paths. So
    teardown **cannot** be fixed by "just add a WorktreeRemove hook."
  - **Native helper found:** `.worktreeinclude` (gitignore-syntax) copies *gitignored files* into new
    worktrees — good for small config (`.env`-style), but it copies files, not the `node_modules`/`models`
    junctions, and is mutually exclusive with a WorktreeCreate hook.
  - **Revised Seam A:** lean on `baseRef:"head"` (config, clean) for §1; do prep/teardown as **standalone
    scripts** (a `prepare-worktree` for junctions + a junction-aware long-path teardown), with `.worktreeinclude`
    for small config. Hooks are optional automation wrappers only where their contract fits — not the backbone.
- **Seam A teardown primitives (§2) — VERIFIED (T3 experiment).** In a throwaway dir: `.NET`
  `Directory.Delete(junction, false)` removes the link and the target's sentinel **survives**; even
  `Directory.Delete(junction, true)` (recursive) did **not** follow through the junction (sentinel survived) —
  i.e. `.NET` delete is junction-safe, unlike `git worktree remove`/`rm -rf`; and a **463-char** path was
  created and deleted via `\\?\`/`.NET`. So a small `.NET`-based teardown script is a sound, safe primitive.
- **§2 npm churn — VERIFIED (T1/T3).** `npm ci --dry-run` → "up to date", exit 0: lock↔package.json are in
  sync, so `npm ci` would succeed today and (being non-rewriting) is exactly what prevents the churn.
  **Blast radius = 1** — only `modules/ui/build.gradle.kts` consumes `NpmInstallTask`.
- **§3 model-runtime (Seam B) — mechanism VERIFIED (T1), live self-test UNVERIFIED (contention).**
  - The MCP tool does `const variantId = input.variantId ?? 'cuda12'` (`server.mjs:2249`) — it **accepts a
    `variantId`**, so `ai_activate {variantId:"default"}` routes to the G17 CPU-baseline fallback
    (`RuntimeActivationService.java:371`). The §3 verification-tier fix is therefore *even cheaper* than the
    design said: request `"default"` + make the CPU baseline resolvable.
  - A chat model **is** configured (`llmModelPath = …/Qwen_Qwen3.5-9B-Q4_K_M.gguf`, present) — the self-test
    precondition is satisfiable.
  - `preflight` is a clean **additive checklist** (per-check boolean + actionable `details`, `ready` = AND;
    `server.mjs:1440`) — adding a `llamaVariantResolvable` check (with the stage→native-bin remedy) is
    idiomatic "extend, don't replace." It already emits the `installDist` remedy for `headDist`.
  - **Blocked:** the gold-standard live test (copy stage→native-bin, `ai_activate {variantId:"default"}`,
    confirm a *successful* self-test) could not run — `quick_health` shows the one shared stack is **CONTENTION**
    (owned by the `609r-substrate` session). Not taken over, per `branch-safety.md`. The residual unknown is
    purely "does the CPU self-test launch cleanly on this box (DLLs/CPU inference)" — everything upstream is
    confirmed. (Aside: this contention is itself live §4 evidence and a live demo of 606's provenance verdict.)

## Per-area confidence (0–10) for the remaining work

| Area | Confidence | Why |
|---|---|---|
| §1 `baseRef:"head"` (Seam A core) | **9** | Primary-source-confirmed one-liner; also fixes subagent worktrees; only the unverified regression keeps it off 10 (mitigated by the assertion). |
| §2 `npm ci` (narrow fix) | **9** | `npm ci` verified green today; blast radius = 1. |
| §3 model-runtime (Seam B) | **7** | Fix is cheaper than thought and fully traced + preflight cleanly extensible; −3 only for the contention-blocked live self-test proof. |
| §2 worktree prep/teardown (Seam A rest) | **6** | Teardown primitives verified safe; but the clean-hook framing was wrong → needs script-based prep/teardown design (more work than "wire two hooks"). |
| §4 / §5 / §6 (out-of-core assists) | **7** | Well-understood, low-risk, low-ambition; mostly discipline + small tooling. |

**Overall remaining-work confidence: ~7.5 / 10.** High for the three cheap, high-leverage fixes (baseRef,
npm ci, the §3 `default`-variant + preflight check); medium for the Seam-A prep/teardown *structure* (a design
adjustment, now scoped: scripts not hooks) and the single live self-test that contention deferred. No probe
refuted the design; two sharpened it and one is pending a free dev stack.

---

# Implementation (2026-06-20)

Status: **implemented** (structural core + cheap rule/doc additions). §5 deferred (below). All changes made in
the main checkout, scoped to own files (the §4 safe pattern). Live validation ran against a freshly-started
dev stack (the contention had cleared).

## What shipped

- **§2 npm churn → `npm ci`** — `build-logic/.../conventions/NpmTasks.kt` (`commandLine(npm, "ci")` + intent
  doc). Only consumer is `modules/ui`. *Validated:* `build-logic:compileKotlin` green; `npm ci --dry-run`
  green.
- **§1 base-ref** — `.claude/settings.json` gains `{"worktree":{"baseRef":"head"}}` (Edit-tool
  schema-accepted). Plus Hard Rule #7 `verify-worktree-base` in `branch-safety.md` + register row #35 in
  `tier-register.md`. *Validated:* `prose-tier-register` gate **passes** (clean anchor+row+anchored-sentence —
  no changeset needed, contrary to the workflow note).
- **§3 model-runtime** — `dev-runner.cjs` now (a) auto-stages `build/llama-server/stage/** → native-bin`
  (`ensureLlamaStagedInNativeBin`, main-checkout fallback) and (b) resolves `JUSTSEARCH_MODELS_DIR` from the
  **main** checkout (reusing `mainRepoRoot`); a gradle `stageLlamaToDevNativeBin` Sync task is the manual
  primitive; `preflight` gains a report-only `llamaVariantResolvable` check (`server.mjs` + `schemas.mjs`).
  *Validated LIVE (gold-standard, the blocker from two prior passes):* staged native-bin (default + cuda12) →
  `ai_activate` → **"GPU runtime activated"** (25.8s) → `agent_chat` → **"PONG"**. The verification tier the
  seed called "permanently capped" is **restored**.
- **§2 worktree teardown** — `scripts/dev/remove-worktree.cjs` (junction-unlink-first + long-path `.NET`
  fallback + `git worktree prune`). *Validated end-to-end:* junction unlinked link-only, 483-char path
  deleted, **sentinel target survived** (no delete-through), dir gone.
- **§2 worktree prepare** — `scripts/dev/prepare-worktree.cjs` (`npm ci` + installDist; `--no-dist`).
  *Validated:* syntax + its two commands independently verified.
- **§7 serve-worktree-fe** — `scripts/dev/serve-worktree-fe.cjs` (free-port pick, borrow running backend
  read-only, serve worktree FE, print branch/path provenance). *Validated LIVE + browser:* served on 5175,
  `/api` proxy reached the borrowed backend, **the full app rendered** (status bar showed backend `:58500` +
  "Online — Qwen Qwen3.5-9B") — no blank-page hang.
- **§6** — `CLAUDE.md` `delegating-to-subagents` gains the "cite primary-source `file:line` by default" clause.
- **§4** — `branch-safety.md` Merge Workflow gains a "working on shared `main` safely" note (resolve in
  worktree → fast-forward; stage `observations.md` in isolation; prefer `remove-worktree.cjs`).

## Live validation caught two real bugs (fixed)

`serve-worktree-fe` failed live twice before passing — exactly why live validation was required: (1) backend
detection read `apiPort`/`ports` but run.json exposes **`apiPortActual`**; (2) the free-port probe checked only
IPv4 `127.0.0.1`, missing a port held on IPv6 `[::1]` (the §7 `:5174` collision) — replaced with a dual-stack
connect-probe. Both fixed and re-validated.

## §3 regression caught in review — native-bin is RUNTIME-owned, not build-managed (fixed)

A second agent reported that the §3 native-bin mirroring **clobbers an Install-AI'd cuda12 GPU runtime**.
Verified and **substantially correct** — the first cut had a real design error:

- `native-bin/llama-server/` is the **runtime install directory**: "Install AI" extracts the cuda12 variant to
  `native-bin/llama-server/variants/cuda12/` (`AiInstallService`) and it persists across sessions. It is **not**
  a build-output dir.
- **The decisive bug:** Install-AI installs the *variant only* — no flat `llama-server.exe` baseline. The first
  cut's dev-runner guard checked the *flat baseline*, so on an Install-AI'd (variant-only) machine the guard was
  false and the copy ran **every start**, overwriting `variants/cuda12`. The gradle `Sync` task was worse — Sync
  *prunes*, so with a CPU-only stage it would *delete* the variant. And cuda12 has **no fallback** (G17 covers
  only `default`) + GPU hosts auto-select cuda12 → a clobber **hard-breaks activation** with no recovery short of
  the ~3 GB re-install.
- *(Corrections to the report, for the record: `cpSync` overwrites but does not prune — only the gradle `Sync`
  prunes; the gradle task was never auto-wired, so the every-build claim applied to the dev-runner copy; and the
  cited first-clobber mtime predated this code. The substance — dev-staging must not own native-bin — holds.)*

**Fix:** dev-staging now treats native-bin as read-only whenever it holds **any** runtime (flat baseline **or**
any `variants/*/llama-server.exe` — `hasAnyLlamaRuntime`), and only ever copies a **CPU baseline (flat files,
never `variants/`)** into a **genuinely empty** native-bin. GPU cuda12 is exclusively Install-AI's domain. The
pruning gradle `Sync` task (`stageLlamaToDevNativeBin`) was **removed**; the preflight remedy now points to
"Install AI" for GPU and the auto-staged CPU baseline for `default`-variant verification. *Validated:* unit test
of the guard (variant-only / empty / flat-baseline cases all correct), syntax, gradle config green.
**Lesson:** the §3 live validation passed only because the build had *downloaded* cuda12 — masking the
variant-only-install clobber. Confirmation-bias trap (`interrogate-results`): a green activation hid the
destructive path.

## Scope notes / honest limits

- **`.worktreeinclude` was evaluated and dropped:** `.mcp.json` and `.claude/settings.local.json` are *tracked*
  (no ignore rule) and no `.env` exists — so there is no gitignored config a worktree needs. Adding it would be
  structure for an absent case.
- **Worktree hooks not used:** the de-risk pass showed `WorktreeCreate` *replaces* git creation and
  `WorktreeRemove` is side-effect-only / can't fix git's long-path failure — so prep/teardown are standalone
  scripts (the correct primitive), not hooks.
- **preflight `llamaVariantResolvable` validated statically only:** the running MCP server loaded its code at
  session start, so the live `preflight` tool ran pre-edit code; the new check's logic was simulated against
  the (now-populated) native-bin and the schema/handler are syntax-clean. It takes effect on the next MCP
  server restart.
- **§5 `UnifiedChatView.ts` (5421-line) decomposition — DEFERRED** to a dedicated effort (large,
  behavior-neutral, couples to 610 §F.3 / 614). Logged to `observations.md`.

---

# Design-alignment review (2026-06-20)

A conceptual check of the implementation against the **Long-term design** section's intended outcomes (not
code style). Verdict: **the structural core (§1+§2+§3+§7) is built, validated, and conforms to the design's
"correspondence asserted, not assumed" principle and its scope boundaries — nothing the design said to avoid
was built.** The §3 regression fix *improved* alignment (dev-staging no longer manages cuda12, matching the
design's "the cheap fix is the `default`/CPU case; cuda12 is the heavier follow-up / Install-AI's domain").

**Real gaps / deviations flagged:**

- **§3 `installDist` sub-item — PARTIAL.** The design named: *fold installDist into the start path, or make the
  `dev-runner.cjs` "Head dist not found" error print the exact command.* `prepare-worktree.cjs` covers the
  outcome (one command → dists), but the named inline-error fix was outstanding. **Now closed** — the
  "Head dist not found" error message was made actionable (prints the exact `installDist` / `prepare-worktree`
  commands).
- **§1b (same-named-file conflation) — DONE.** Added the "cite full worktree-qualified paths when a file
  exists in several trees" note to `context-efficiency.md` (Worktree Awareness).
- **§8 small items — DONE.** The read-only `api_call` GET allowlist now includes `/api/action-ledger`
  (`server.mjs`, verified-real route); the `browser_batch` boot-race and `sleep`-guard one-shot-wait notes
  were added to `agent-lessons.md` (the home for harness-behaviour lessons). *(Takes effect for the MCP
  allowlist on the next MCP-server restart — same session-start-load caveat as the preflight check.)*

**Mechanism deviations (outcome still met; defensible):**

- `prepare-worktree.cjs` uses **`npm ci`**, not a `node_modules` **junction** as the seed imagined — delivers
  the same "one-command dev-ready worktree" outcome while *avoiding* the junction-deletion hazard §9 warned of.
- `serve-worktree-fe.cjs` asserts "served = worktree code" **by Vite's cwd + printed branch/path provenance**,
  not a symbol-grep over the bundle — by-construction correspondence is stronger than a grep.
- The §1 base-correspondence **assertion is a prose rule** (`verify-worktree-base`, ~70% adherence), not
  automated — the design's de-risk pass established the worktree hooks can't carry it, so prose is the
  design-endorsed choice; `baseRef:"head"` is the by-construction half.

## Indirect changes made alongside (discoverability / doc-coherence)

The implementation added behaviour and tools that other docs referenced or now contradict; fixed in the same
pass so the change doesn't silently drift:

- **`branch-safety.md`** — documented `prepare-worktree.cjs` (Creating) and `serve-worktree-fe.cjs` (Shared Dev
  Stack) so the new scripts are discoverable; updated the **"Shared models"** guidance — the dev-runner now
  resolves `JUSTSEARCH_MODELS_DIR` from the main checkout automatically (the manual export is now a fallback,
  not a required step); the "based on HEAD" creation note is now *accurate* given `baseRef:"head"`.
- **`dev-runner.cjs`** — the "Head dist not found" startup error now prints the actionable remedy (§3 sub-item).
- **npm `ci` note** — the build now runs `npm ci`; recorded where the lockfile workflow is described so a
  drifted lock failing the build (rather than silently self-healing) is expected, not surprising.
- **`develop-ui.md`** — added a "Worktree FE" mode row pointing at `serve-worktree-fe.cjs`; ran the canonical
  docs regen (`llmstxt-generate` + `skills-sync`).
- **`observations.md`** — appended a resolution breadcrumb for the §3 native-bin-clobber entry another agent
  filed (now fixed), plus a NEW §4 meta-finding caught live in this pass (below).

**New §4 instance caught live (and self-corrected):** running the canonical-docs regen (`skills-sync`) over a
multi-agent **dirty `main`** silently regenerated `inference-runtime/SKILL.md` from *another agent's*
uncommitted VDU/OCR source-doc WIP — i.e. repo-wide regen propagates neighbours' unfinished work into
generated artifacts. Caught via a scoped `git status`, reverted the single generated file (their source edits
untouched). Reinforces §4's "stage in isolation" discipline and adds a concrete mitigation candidate: scope
`skills-sync` to changed sources, or only regen when the relevant sources are clean. Logged to `observations.md`.

---

# Additional session findings (2026-06-21, 621-decomposition + charter agent)

> A multi-session arc (621 decomposition → pre-merge build → merge onto a fast-moving multi-agent `main` →
> 610-rebase reconciliation) surfaced a genuinely **new high-value theme — verification-trust gaps** — plus
> fresh corroboration/extension of §4 (shared-`main`) and §5/§8 (tool friction). The §10 items are the
> dangerous ones: they do not waste a turn, they ship a *wrong "green."*

## §10 — NEW high-cost theme: verification that *looks* complete but isn't ("false green")
The recurring failure mode this arc was a verification step reporting success while the thing it "verified"
was wrong. Three distinct, live-witnessed mechanisms:

- **§10a — Piped exit code masks the real one (fix-tier C discipline; harness-amplified).** A backgrounded
  `./gradlew build -x test 2>&1 | tail -25` produced a completion notification of **"exit code 0"** — but
  that is the *pipeline's* exit (`tail`'s), not gradlew's; the build had actually **FAILED** at
  `verifyGovernanceGates`. One step from fast-forwarding `main` on a broken build, caught only by reading the
  text ("BUILD FAILED"). The harness reports the *last pipe stage's* code. **Fix:** never pipe a
  command-under-test through `tail`/`grep`/`head` when the exit matters (run it bare, or `set -o pipefail`,
  or assert on the output text). Strong candidate for an **`agent-lessons.md` platform-constraint line**
  (sibling of the `Read`-truncation / bash-guard entries).
- **§10b — A DOM unit test green on an *unreachable* seed → shipped a real bug (fix-tier C discipline).** A
  reload-render test seeded the thread with `shapeId:'core.rag-ask'`, but the real auto-restore seeds the
  placeholder `core.free-chat`; the test passed while the **live UI mislabelled a Document-Q&A answer as
  "Chat — this mode does not search your documents."** Only the live browser caught it. A hand-crafted seed
  that does not mirror the *actual* producer's data flow gives confident-but-wrong green — the unit-tier
  instance of the existing **`static-green ≠ live-working`** handle, and concrete evidence that the live-UI
  tier is non-optional for reload/persistence behaviour (as the project already requires).
- **§10c — Subset-gate / deferred full-suite blind spot (fix-tier C).** Running the FE discipline gates
  *individually* (composition / run-renderers / steering / …) felt thorough but **missed `execution-surface`**
  (an extracted file became an unregistered evidence referencer), invisible until the full
  `verifyGovernanceGates` at merge; likewise the full `npm run test:unit:run` wasn't run until the review
  pass (it was clean — but that was luck-adjacent). **Lesson:** a chosen *subset* of gates/tests is not "the
  gates passed." The full kernel + full suite are the only honest "done" — run them *before* declaring
  complete, not at merge.

## §11 — Extension/corroboration of §4 (shared-`main`) and §5/§8 (tool friction)
- **§11a (extends §4, fix-tier A lever) — `build -x test` can't isolate *your* breakage from ambient
  breakage.** `main` carried ~144 uncommitted WIP files + committed in-flight refactors already failing
  **6 discipline gates**; proving 5 were ambient and 1 was mine cost several turns of filtering SARIF
  findings by my filenames. On an actively-refactored shared `main` the pre-merge gate answers "is main
  clean?" (no) but not "is *my diff* clean?" — the question that actually gates a merge. **Candidate lever:**
  a `--changed-only` / diff-scoped mode for the discipline kernel that filters findings to the merging
  branch's touched paths.
- **§11b (extends §4) — fast-forward race.** `main` advanced **twice** mid-merge (a neighbour committing
  every few minutes), so `--ff-only` failed and the merge-into-worktree-then-ff dance had to repeat. Argues
  for a guarded "re-merge + ff in one step, retry-on-race" helper.
- **§11c (extends §3/§4) — dev-stack takeover thrash.** The single shared stack was re-taken repeatedly;
  reclaiming it **twice** (with a stop-and-ask) and re-paying `ai_activate` (~11–25s) each time. The 606
  lease handles *abandoned* owners but not active ping-pong between two agents that both need the one stack.
- **§11d (extends §5/§8, fix-tier C) — background-server lifecycle is non-obvious.** Keeping
  `serve-worktree-fe` alive took 3 tries: `timeout 70 …` (self-killed at 70s), `node … | grep &` (the inner
  `&` orphaned it on shell exit), then bare `node …` as the `run_in_background` **main** process. A
  persistent server must be the bare main command — no wrapper, no `&`. Candidate `agent-lessons.md` line.
- **§11e (corroborates §8) — `vitest` teardown noise + the `Edit` read-state tax.** Every `vitest` run buries
  the `Tests N passed` line under a wall of happy-dom `AbortError` teardown traces (grep-filtered every
  time); and the `Edit` tool demands a re-read of the *same* logical file across the worktree↔`main` path
  boundary and after any `node` file-surgery ("File has been modified since read") — hit live while filing
  this very section.

**Routing note:** §10a, §10b, §11d are cross-cutting *platform/discipline* constraints (not 618-design
items) — their durable home is `agent-lessons.md` (§10a/§11d) and the `static-green ≠ live-working` /
`audit-without-test` family in `agent-postmortems.md` (§10b/§10c). Captured here as the friction-catalogue
inbox; promotion to those files is the follow-up (deferred — `observations.md`/`agent-lessons.md` are
currently contended multi-agent WIP, per §9's data-loss caution).

---

# Additional session findings (2026-06-21, tempdoc-620 agent)

> Friction across the multi-session **tempdoc-620** work (always-loaded agent-doc audit +
> prose→infrastructure). Continues the global §-numbering (the 621 block above used §10–§11, so these are
> §12+). Same fix-tier convention (A repo · B harness-config · C discipline/upstream). Cross-references the
> 621 agent's §10/§11 and the original §4/§5/§6 where they overlap. Dominant cost was §12.

## §12 — Shared-`main`: silent data-loss + memory-based attribution (extends §4; complements §11a)

Committing a ~35-file changeset into a `main` checkout shared with 3–4 agents hit failure modes sharper than
§4's "ambiguous/blocked" framing — **silent data-loss**, plus attribution that bit at *staging* time:

- **`observations.md` append silently *wiped*.** §4 noted you often *can't* log to a contended
  `observations.md`; here I *did* append and a neighbour's `docs(615)` commit overwrote the file, **losing my
  line with no signal** (found only by re-grepping). The honor-system "commit immediately" doesn't survive the
  turns between append and commit. *(fix-tier A/C: a real append-only path — apply a one-line patch, or a
  per-agent inbox merged later.)*
- **`llms.txt` reset to HEAD, wiping my generated change.** Distinct from §4's regen-*propagation* hazard
  (≈ line 796): here my generated edit was *reset away* (caught only because `git diff --stat` was unexpectedly
  empty), dropping the `common-workflows` index entry I had added. Generated files on a contended tree lose data
  **both** ways — they absorb neighbours' WIP *and* get reset out from under you.
- **Attribution bites at *staging* time, not only gate-red time (complements §11a).** §11a wants a
  `--changed-only` kernel mode so "is *my diff* red?" is answerable; the mirror problem is "*which files are
  even mine to stage?*" — `git status` shows the whole 100+-file shared tree, and the compaction-state's "files
  modified in this session" is the *whole-tree* diff (mislabeled). I reconstructed my changeset **from memory**
  and diff/grep-verified each file for contamination. Several turns; real risk of missing-a-file or staging a
  neighbour's WIP. *(Direction: surface the harness's actual per-session edited-file set as a stageable
  manifest.)*
- **Index churn / no partial `git add`.** A staged deletion vanished between turns, and `git add <35 paths>`
  **aborted the whole batch** on one stale pathspec — small, but compounds the above.
- **Root cause (mostly my discipline error).** All of this is downstream of running a multi-session task *in the
  `main` checkout* instead of a worktree, where the whole-worktree diff *is* the changeset and neighbours can't
  clobber your files. §1's `baseRef:"head"` lowers the cost of *using* a worktree; the missing complement is
  making editing-straight-in-`main` the harder path (e.g. a soft nudge when a first `Edit` lands in the main
  checkout). *(I even hit "File has been modified since read" twice while filing THIS section — §11e, live.)*

## §13 — Governance/ratchet tools fail-loop on *justified* change (NEW)

The governance instruments (several built this session for 620) produce fail→manual-fix loops or false reds:

- **Shrink-only ratchet, no `--init` for declared growth.** `always-loaded-budget` only ratchets down; every
  *justified* growth (documenting a real hook, a legend row) fails `--check` and forces a hand-edit of the
  baseline JSON — ~4 fail/bump/recheck loops this session. *(fix-tier A: a `--bump <file> --reason` path
  recording an intentional raise, vs. silent manual surgery.)*
- **A scanner that scans its own ephemeral output → false red.** `prose-tier-register` scanned the gitignored,
  auto-generated `compaction-state.md`; its modified-files list contained the filename `620-always-loaded-…`,
  and "**always**" tripped the must/never/always sentence-scan (a SARIF-debug cycle to localize). *Fixed this
  session* (excluded the file), but it was live for any agent whose work touched an "always/never/must"-named
  path. *(fix-tier A, done.)*
- **A register that forks its own authority.** The tier vocabulary was hand-listed in the register prose *and*
  in code (`ALLOWED_TIERS`) — they drifted (prose claimed 5 tiers after code had 6). Caught only in critical
  review. *Fixed* (prose now references the authority). General lesson: a register that validates everything
  *except its own prose's enumerations* still drifts.

## §14 — Authoring-time validation gaps + an always-red check (NEW)

- **`consult-register` has no authoring lint.** A `pathIncludes` may name a non-existent path (silent *dead*
  delivery), and first-match **shadowing** is undetected — I shipped a shadowed token and caught it only in
  review. A tiny check (paths resolve; no earlier region shadows a later one) would catch both at authoring.
  *(fix-tier A; 620 substrate.)*
- **`tempdoc-status-check` is red on ~15 tempdocs → signal-blind.** Its canonical set
  `{open,active,done,shipped,superseded,draft}` is divorced from practice (`implemented`/`merged`/`investigated`/
  `charter` are used freely) and isn't CI-wired, so it is permanently red and unread — my own tempdoc shipped
  non-canonical `in-progress`. An always-red check hides real signal. *(Logged to `observations.md`; owes a
  re-canonicalize-or-retire decision — fix-tier A.)*

## §15 — Small tool items + corroborations (extends §5/§8; corroborates §6/§11e)

- **NEW — the "task tools haven't been used recently" reminder fires after *nearly every tool call*** for work
  that never used tasks — repeated, never-relevant attention tax. *(fix-tier C, upstream/harness.)*
- **NEW — `CRLF will be replaced by LF` warnings** on `.mjs` edits (Windows line-ending churn) — noise + a
  spurious-diff risk. *(fix-tier A: `.gitattributes` `eol` normalization.)*
- **NEW — `git rm` balks at locally-modified files** (fall back to `rm`+`git add`); uncertainty whether
  `git rm -f` trips the bash-guard `-f`/force-push matcher cost a beat. *(fix-tier A: state the guard's exact
  scope in `hooks-reference.md`.)*
- **Corroborates §5 (Read auto-limit):** hit repeatedly on the *always-loaded* files I was shrinking
  (`tier-register` 14 KB, `hooks-reference` 11 KB) — another argument that oversized always-loaded docs are
  self-friction.
- **Corroborates §6 + §11e:** an Explore subagent "confirmed" a CLAUDE.md claim by citing the same claim
  (circular) — the §6 re-verification tax; and the `Edit` "File has been modified since read" on a contended
  shared doc (§11e) recurred filing this very section.

---

# Take-over re-verification & critical assessment (2026-06-21)

> **What this section is.** A take-over pass that does what the seed's scope-note demanded of *any* picker-up:
> *"re-verify before designing — some may already be fixed."* The doc is `status: implemented`, but it
> accreted three later "Additional session findings" blocks (612 / 621 / 620 agents, §7–§15) whose follow-ups
> were explicitly **deferred**. This pass (a) re-verifies the shipped core against *current* `main`, (b) builds
> a precise ledger of what genuinely remains, and (c) critiques the design and the deferral pattern itself. **No
> implementation or design work — investigation only, per the user.** Evidence is T1 (read this session from
> source/git) unless noted.

## Part 1 — the shipped core still holds (re-verified against current `main`)

Every implementation claim in the *Implementation (2026-06-20)* section was checked against the live tree:

| Claim | Current state | Verdict |
|---|---|---|
| §1 `worktree.baseRef:"head"` in committed settings | present, `.claude/settings.json:2-3` | ✅ holds |
| §1 `verify-worktree-base` rule + tier-register row #35 | both present (`branch-safety.md` Hard Rule #7; `tier-register.md` row 35) | ✅ holds |
| §2 build install → `npm ci` | `NpmTasks.kt:51` `commandLine(npm, "ci")` | ✅ holds |
| §2 prepare/teardown scripts | `scripts/dev/{prepare-worktree,remove-worktree,serve-worktree-fe}.cjs` all present | ✅ holds |
| §3 dev-runner auto-stage + models-dir resolution | `dev-runner.cjs` `ensureLlamaStagedInNativeBin`/`hasAnyLlamaRuntime` (L356/383/413), `JUSTSEARCH_MODELS_DIR` (L428-431) | ✅ holds |
| §3 native-bin clobber fix (read-only guard) | `hasAnyLlamaRuntime` guard present; pruning `stageLlamaToDevNativeBin` Sync task gone | ✅ holds |
| §3 preflight `llamaVariantResolvable` | `server.mjs:1529-1555`, `schemas.mjs:749` | ✅ holds |
| §8 `/api/action-ledger` GET allowlisted | `server.mjs:905` | ✅ holds |
| §13 `prose-tier-register` excludes `compaction-state.md` | `scanner.mjs:40` | ✅ holds (observations #514 is now stale) |
| §6 subagent "cite file:line" clause; §1b full-path note | present in `CLAUDE.md` / `context-efficiency.md` | ✅ holds |

**§1 is now MORE armed, not less — strengthening the fix's value.** `git rev-list --count origin/main..main`
= **120** this session (the doc recorded 13, then 57). Local `main` is 120 commits ahead of `origin`. Without
`baseRef:"head"`, a worktree today would branch from a base missing 120 merged commits. The by-construction
fix is now the only thing standing between every new worktree and a guaranteed stale base — its value has
grown ~10× since it was written. (Caveat unchanged: `head` fixes "origin is stale," not "local main lacks the
one feature this task needs"; the prose base-assertion remains the necessary defense-in-depth.)

**Conclusion: the structural core (§1+§2+§3+§7) is real, present, and durable.** Nothing in the shipped core
has rotted. The doc's `implemented` status is accurate *for the core*.

## Part 2 — what genuinely remains (verified deferral ledger)

The remaining work is almost entirely the **deferred promotions** the later blocks parked here, plus two
stale-but-unresolved tooling decisions. Verified current status of each:

| Item | Proposed home | Current status (T1) | Still open? |
|---|---|---|---|
| §10a piped-exit masks real exit code | `agent-lessons.md` platform line | not present in `agent-lessons.md` | **OPEN** |
| §11d background-server-must-be-bare-main | `agent-lessons.md` | not present | **OPEN** |
| §10b unreachable-seed unit-green / §10c subset-gate blind spot | `agent-postmortems.md` (`static-green` / `audit-without-test` family) | only the older slice-447 `static-green` case exists; these two not promoted | **OPEN** |
| §12 `observations.md` silent data-loss → real append-only path | repo (one-line patch) + discipline | no append-only mechanism added; honor-system `echo >>` unchanged | **OPEN** (see Part 3·A) |
| §14 `tempdoc-status-check` re-canonicalize-or-retire | repo decision | still red on ~15 tempdocs, still not CI-wired; logged **5×** in observations (#241/#400/#415/#515/#522) | **OPEN** (see Part 3·B) |
| §13 always-loaded-budget `--init/--bump` for declared growth | repo | still shrink-only `--rebalance`; no intentional-raise path (`check-always-loaded-budget.mjs:36`) | **OPEN** |
| §14 `consult-register` authoring lint (paths resolve + no shadowing) | repo | no lint script exists | **OPEN** |
| §4 `skills-sync` regen propagates dirty-`main` neighbour WIP | repo (scope to changed sources) | unchanged; logged (#509) | **OPEN** |
| §15 `.gitattributes` `eol` for `.mjs`; `git rm` guard-scope doc; task-tool reminder | repo / docs / upstream | unchanged | **OPEN (low value)** |
| §5 `UnifiedChatView.ts` decomposition | dedicated effort | **5421 → 4235 lines** — shrunk ~1186 via the 621-decomposition arc; partial | **PARTIAL (owned by 621)** |
| Open-Q #3 orphan `.claude/worktrees/587-gpu-capability-resolver/` | user decision | **still on disk**, still unregistered (`git worktree list` shows only main/604/612) | **OPEN (needs user)** |

So "what's left of 618" is **not the design** (that shipped and holds) — it is a **promotion/cleanup tail**:
move ~5 lessons to their durable homes, make 2 stale tooling decisions, do 1 trivial cleanup. None of it is
new structure. This matters for scoping the take-over: 618's *design* is done; its *follow-through* is not.

## Part 3 — critical assessment (questioning assumptions)

### A. The meta-finding the doc never names: §12 is the *root* that blocks its own follow-ups

The doc treats §12 (`observations.md` silent data-loss on a contended `main`) as one friction item among
many, fix-tier "A/C, deferred." I think that under-rates it, for three compounding reasons:

1. **It is reproduced as actual data-loss at least three independent times** — §4 ("could not log"), §9
   ("reproduced as actual data loss… silently wiped"), §12 ("append silently *wiped*… `llms.txt` reset to
   HEAD"). Per the repo's own `structural-defects-no-repeat` rule, *one* documented silent loss proves the
   bug-class; this has three. The honor-system "`echo >>` then commit immediately" demonstrably does **not**
   survive the turns between append and commit.
2. **It is circular — it blocks resolving the *other* deferred items.** Every later block ends with the same
   sentence: *"promotion deferred — observations.md / agent-lessons.md are currently contended multi-agent
   WIP."* The reason §10a/§11d/§14 were never promoted is the §12 hazard. You cannot safely write down the
   fix-need for a broken logging surface using that same broken surface.
3. **The duplicate observations rows are live proof.** #515 and #522 are near-verbatim duplicate logs of the
   *same* `tempdoc-status-check` finding, 1 ID apart — exactly what append-without-coordination produces. The
   catalogue is dogfooding its own defect.

**Assessment:** §12 is the highest-leverage *unsolved* item in the whole document, and it is mis-filed as a
mid-tier discipline note. The recurring honor-system framing ("mostly my discipline error… use a worktree")
keeps deflecting to behaviour when the evidence says the *mechanism* is the defect. A genuine append-only path
(per-agent inbox file `docs/observations.d/<session>.md` merged by a tiny concat step, OR `git
notes`-as-inbox) is a small, bounded fix that would unblock the entire deferral tail. I'd elevate this from
"§12, deferred" to "the one structural item still worth building."

### B. `tempdoc-status-check`: a five-times-logged decision that is cheaper to make than to keep deferring

Logged **2026-05-25, -06-11, -06-13, and twice on -06-20**. Each logging cost an agent a turn; each reader
who hits the red check loses trust in the whole check-suite ("always-red hides real signal," the doc's own
§14 words). The decision is binary and cheap: re-canonicalize the vocabulary (`docs/tempdocs/README.md` +
`tempdoc-status-normalize.mjs` exists already — a normalize pass) **or** retire the check. It is a
~30-minute resolution that has been deferred for a month. This is the clearest "stop re-logging, just
decide" item in the ledger. (My read: re-canonicalize — `implemented`/`investigated`/`charter` are *useful*
real states, and a normalizer script already exists; retiring loses a genuine signal.)

### C. The §1 fix left a lighter automation path unexplored — `SessionStart`, not `WorktreeCreate`

The de-risk pass correctly killed the `WorktreeCreate` hook idea (it *replaces* git creation — too heavy) and
`WorktreeRemove` (side-effect-only — can't fix git's long-path delete), and concluded the base-assertion must
stay **prose** (~70% adherence). But it never evaluated the hook the repo *already* uses for exactly this
class of "assert something at the start of work": **`SessionStart`**. A `--worktree`-launched session fires
`SessionStart` (the same transition `compact-restore` already hooks), and a 3-line check there —
`git rev-list --count origin/main..HEAD` plus a merge-base recency probe — would surface "your base is N
commits behind `main`" as a legible banner at the exact moment the agent starts relying on the base. It
cannot know the *task-specific* "expected file" (so it doesn't fully replace the prose rule), but the generic
"base is stale" signal is precisely what would have caught the 120-/57-commit and file-absent cases. This is
genuinely additive defense-in-depth above prose-tier for the new-session path, and it sidesteps both rejected
worktree hooks. **Worth flagging as an unexplored alternative** — not a claim it should be built, but the
design closed the automation door one hook too early.

### D. The "correspondence asserted, not assumed" framing is sound but slightly over-dignifies the work

The *Long-term design* section erects a system-wide principle (Seam A/B, a candidate-scope table, a mapping
to the gate-kernel producer↔consumer family) over what shipped as: one config line, three standalone scripts,
one build-task word, one preflight boolean, a few prose rules. The doc is admirably self-aware (it explicitly
refuses to build a "generalized correspondence authority" and names the anti-over-build boundary). My only
caution: the principle reads as more load-bearing than the artifacts are, and a future agent could mistake
"the correspondence principle" for a mandate to build the generalized engine the doc warned against. The
guard-rails are present, so this is a **minor** note — but the honest one-line summary of 618 is "a handful
of small, well-targeted dev-env fixes," not "a new architectural seam." Keep the principle as *retrospective
coherence*, not as a charter for more structure.

### E. Scope drift: §10–§15 don't belong to 618's design and being parked here makes them inert

§1–§9 are one coherent theme (worktree↔environment correspondence). §10–§15 are a *different* theme
(verification-trust / false-green) plus a governance-tooling grab-bag that mostly belongs to **tempdoc 620's**
substrate, not 618. The doc's own routing note admits this ("their durable home is `agent-lessons.md` …
`agent-postmortems.md`"). The cost of leaving them parked is not cosmetic: **a friction catalogue is not where
any agent looks for a platform constraint.** §10a ("piped exit masks the real one — one step from
fast-forwarding `main` on a broken build") protects nobody while it sits in a §11 sub-bullet. The promotions
are the *point*, not paperwork — until §10a/§11d reach `agent-lessons.md` and §10b/§10c reach
`agent-postmortems.md`, the lessons cannot change behaviour. This is the strongest argument that the remaining
work is worth doing despite the doc being "implemented."

### F. One finding deserves promotion to a *named* postmortem, not a one-liner: the §3 confirmation-bias trap

The native-bin clobber regression is the doc's richest transferable lesson: live `ai_activate` passed
**GREEN** only because the build had *already downloaded* cuda12, masking a destructive variant-only-install
path — a textbook `interrogate-results` failure where a green result hid the wrong cause. It is currently
captured only inline. It is a better-than-average candidate for a named `agent-postmortems.md` case (richer
than the `static-green` one-liner), because the failure mode — "a passing verification masked the destructive
branch because the environment happened to satisfy the precondition" — generalizes well beyond this repo.

## Part 4 — recommended take-over shape (for user decision; NOT yet executed)

The design is done; the take-over is a **promotion + decision + cleanup** pass, naturally three buckets:

1. **Promote the parked lessons to where they work** (low risk, high "actually protects someone" value):
   §10a + §11d → `agent-lessons.md`; §10b/§10c + §3-confirmation-bias → `agent-postmortems.md` (the latter as
   a named case). Gated by the `before-appending-to-rules` test (most are platform constraints, which pass).
2. **Make the two stale tooling decisions** (cheap, overdue): resolve `tempdoc-status-check` (Part 3·B —
   recommend re-canonicalize via the existing normalizer) and decide on the always-loaded-budget `--bump`
   path. Both close repeatedly-re-logged observations.
3. **The one structural candidate** (needs user green-light, per `ask-when-uncertain`): a real append-only
   `observations.md` path (Part 3·A) — the item that unblocks bucket 1 and stops the recurring data-loss.
   Plus trivial cleanup: the orphan 587 worktree dir (Open-Q #3) via the now-existing `remove-worktree.cjs`.

Explicitly **out of scope for 618**: §5 `UnifiedChatView.ts` decomposition (owned by the 621 arc, already
halved), and any generalized "correspondence authority" (the design forbids it). The unexplored `SessionStart`
base-check (Part 3·C) is a *candidate to discuss*, not assumed work.

---

# Long-term design — Seam C: shared-agent-state isolation & reconciliation (2026-06-21, design pass)

> **What this section is.** A long-term design pass over the one piece of 618's remaining work that warrants
> *design* rather than execution: the §12/§4 cluster — **agent-written shared state silently lost on a
> contended multi-agent `main`** (observations.md appends clobbered; generated docs absorbing/resetting
> neighbours' WIP). It investigates what already exists, conforms to it, and matches scope to the real problem.
> **No code. No implementation. Design theory only**, in the spirit of the existing *Long-term design* section
> above. It deliberately **revises a scope boundary that section drew** — see *Why this re-opens §4* below.

## Why this re-opens what the first design pass closed

The original *Long-term design* (2026-06-20) explicitly excluded §4 from the structural core: *"§4 is
shared-state contention (the dev-stack's class, already isolated by worktrees); its residual friction
(attribution, observations.md data-loss) is a small tooling assist + discipline, not this seam."* That call
was **correct on the evidence available when it was written** — §4 was then "ambiguous / blocked," a
discipline problem. **§12 (filed 2026-06-21, one day later) changed the evidence**: the same surface produced
*reproduced silent data-loss* three independent times (§4 "couldn't log", §9 "reproduced as actual data
loss", §12 "append silently wiped + `llms.txt` reset to HEAD"), it is **re-logged 5×** in observations.md
(#241/#400/#415/#515/#522, with #515≡#522 a duplicate the contention itself produced), and it is **circular**
— it blocks the promotion of every other deferred item (each later block defers "because observations.md is
contended"). Two things foreclose the original "discipline + small assist" disposition:

1. **The discipline fix was tried and demonstrably failed.** "`echo >>` then stage in isolation, never
   `git add -A`" is *already* the documented rule (`branch-safety.md:175-177`) — and the loss recurred under
   it three times. Honor-system (~70%) does not survive the turns between append and commit.
2. **The user's standing directive for this pass is "no short-term fixes."** That explicitly removes the
   discipline-only option the first pass chose.

So §12 is reclassified from "discipline residue of §4" to **a structural seam in its own right** — the second
parallel-agent substrate seam (Seam C), distinct from the Seam A/B *correspondence* family. This is not
contradicting the first design; it is the first design's own scope-note (*"re-verify before designing — some
items may have sharpened"*) operating as intended.

## The problem, stated structurally

Strip §12 + the §4-regen sibling to their load-bearing shape:

> **An artifact that multiple parallel agents mutate lives as a single shared file in the contended `main`
> checkout, with no isolation between writers and no reconciliation boundary — so one agent's commit/reset
> silently overwrites another's uncommitted change.**

- **observations.md (the inbox)** — N agents `echo >>` the *same bytes of the same file*; a neighbour's
  commit/reset of that file drops an un-committed append with no signal. Mutation surface: **append-target**.
- **Generated docs on dirty `main` (skills-sync / llmstxt, §4 line ~796 / obs #509)** — regen is
  marker-bounded and faithfully *projects its sources*; the defect is that the **sources are dirty** (a
  neighbour's uncommitted canonical-doc edit), so a regenerating agent bakes that WIP into the generated
  artifact and stages it. Mutation surface: **generation-source**, but the same root: a shared mutable surface
  read/written across un-isolated parallel writers.

Both are the **inverse of the worktree-isolation invariant** the repo already lives by (`never-share-worktree`,
`one-branch-per-worktree`, "stage your own files", "resolve in the worktree then fast-forward"). Worktrees
isolate *code* per agent and reconcile at the merge boundary; these two artifacts are the files that *escape*
that isolation — they are shared-mutable in `main` by construction.

## What already exists — this is a CONFORM, not a new mechanism

The repo enforces "**isolate per writer, reconcile at a boundary**" in at least three places already; the
write-target version of it is exactly what observations.md is missing. (Evidence gathered this pass.)

| Existing substrate | Shape | Evidence |
|---|---|---|
| **Worktree isolation** (the macro case) | one mutable tree per agent; reconcile by merge→fast-forward | `branch-safety.md` Hard Rules; `never-share-worktree` |
| **Governance changesets** | drop a per-change **shard** `gates/<id>/.changesets/<id>.md`; never mutate the shared baseline during authoring; reconcile at gate-time | `changeset-loader.mjs:34-124`; baseline only moves on `--rebalance` |
| **Agent-telemetry** | per-session files `<name>-<sessionId>.{json,txt}` (atomic `.pid.tmp`→rename) + shared **append-only NDJSON keyed by `session_id`** (`events.ndjson`, `session-merges.ndjson`); SessionEnd cleanup | `hook-base.mjs:133-142`; `record-merge.mjs:59-61`; `dispatch.mjs:83-91` |
| **Dev-stack per-session activity stamp** | per-session `tmp/dev-runner/sessions/<sessionId>.json`, merge-on-read patch | `hook-base.mjs:52-80` |
| **Cross-worktree collision check** | discover all worktrees via `git worktree list`, scan disjoint files; disjoint new files never git-conflict | `check-tempdoc-numbers.mjs` |

**observations.md is the lone agent-written operational artifact that does NOT shard** (confirmed by sweep:
every other parallel-agent surface — telemetry, session markers, dev-stack ownership, changesets — already
isolates per session). The closest precedent is the **governance changeset**: a *committed, reviewed* shard
reconciled into a *committed, reviewed* canonical file. That is precisely observations.md's nature (a
committed inbox that humans/agents triage and check off), so the changeset pattern — not the ephemeral-NDJSON
pattern — is the right one to conform to.

## Seam C — the design (general, not implementation-level)

**Invariant to restore:** *every agent-written inbox note lands in a file owned by exactly one session; the
canonical inbox is a reconciled view over those shards, never a directly-shared append target.* Three parts,
each conforming to an existing substrate above:

1. **Write path → per-session shard (isolation by construction).** An inbox note is appended to a
   *session-owned* shard, not to shared `observations.md`. Because no two sessions write the same file, a
   concurrent commit/reset *cannot* drop another's note — the same guarantee worktree isolation gives code and
   `.changesets/` gives baselines. The note rides the agent's own commit in its own worktree (the
   isolation-by-construction half), so it reaches git without ever contending. *(Storage choice is an
   implementation detail kept open here: a tracked `docs/observations.d/<session>.md` shard — the
   changeset-style, immediately git-visible option — or a gitignored `tmp/.../observations-<session>.ndjson`
   shard reconciled later — the telemetry-style option. The changeset-style fits a committed/triaged artifact
   better; the design does not depend on which is picked.)*
2. **Reconcile path → deterministic, conflict-free fold at a boundary.** A reconciliation step concatenates
   shards into the canonical `## Inbox` of `observations.md`, ordered stably (by session/date), append-only.
   Because shards are disjoint files, the fold is **conflict-free by construction** (the same property
   `check-tempdoc-numbers` and `.changesets/` rely on). The natural boundary already exists: the
   **merge-workflow teardown**, where `record-merge.mjs` already runs per `branch-safety.md` — i.e. reconcile
   the leaving agent's shard into the inbox at the same point its merge is recorded. (A periodic/CI fold is the
   fallback if a session never merges.)
3. **Read path → span shards + canonical.** "Check observations before fixing a pre-existing issue" reads the
   canonical file *and* the live shards (a `grep` over `observations.d/` is strictly better than over one
   241 KB file). The CLAUDE.md `log-pre-existing-issues` rule, the `subagent-guide` hook injection, and
   `branch-safety.md` change their *target* from "`echo >>` the shared file" to "append to your session shard"
   (ideally via a ~20-line `note-observation` helper that resolves the session id the hooks already read) —
   a rule-text change, not new governance.

This is small: a shard location + a one-line change to the append rule's target (+ optional tiny helper) + a
concat step on an existing teardown hook. It introduces **no new authority object** — it instantiates the
*existing* shard+reconcile pattern on the one surface that lacks it.

## The other remaining items collapse onto the same principle (no extra design needed)

Investigating Seam C surfaced that **most of 618's remaining tail is the same shape**, already covered by the
changeset substrate — so they need *decisions/execution*, not new design:

- **§13 always-loaded-budget has no declared-growth path (shrink-only `--rebalance`).** The repo's answer to
  "a baseline must move for a justified reason" is *already* the **governance changeset** (class-size-exceptions
  works exactly this way). Seam C's principle says: a *justified* budget growth should be a
  changeset-classified declared change, not a silent `--bump`/manual JSON surgery. **Conform to changesets**,
  don't invent `--init`.
- **Promotion tail (§10a/§11d → `agent-lessons.md`, §10b/c → `agent-postmortems.md`).** Appending a rule to
  these files *already* rides the `prose-tier-register` changeset path (anchor + register row + classified
  changeset). So the promotions **already conform** — they were blocked only by the contended-`main` write
  hazard that Seam C removes. Once observations.md stops eating notes, the promotions are pure execution.
- **§4 regen-on-dirty-main.** Same principle, generation-surface variant: regen must run against an *isolated*
  (clean) view of its sources and stage only the agent's own change — i.e. regenerate in the worktree, fold by
  fast-forward, exactly as code already does. This is **named as a Seam-C-adjacent violation, not built now**
  (see *Reach* — second instance, different fix shape, no shared abstraction warranted yet).
- **§14 `tempdoc-status-check`** is a *decision*, not a seam (re-canonicalize via the existing
  `tempdoc-status-normalize.mjs`, or retire). Recommend re-canonicalize. **Orphan 587 dir / §15 micro-items**
  are cleanup. None need design.

## Scope boundaries — what Seam C deliberately does NOT build

Per the standing anti-over-build discipline and the first design's own ethos:

- **No generalized "shared-agent-state framework" / no central reconciliation engine.** Seam C instantiates an
  *existing* pattern on *one* surface (observations.md). The changeset substrate already covers baselines and
  rule files; telemetry covers session state. Building a unifying authority over all of them now is the
  premature abstraction 622's discipline forbids ("generalize only on a second concrete instance" — and even
  with a second instance, see Reach, the *fix shapes differ* enough that a shared engine isn't yet earned).
- **No worktree contention/locking layer.** Shards are disjoint; isolation is by construction, so there is
  nothing to lock — exactly why worktrees need no lock.
- **No change to the curated `observations.md`'s role.** It remains the committed, triaged archive (items get
  `[x]`'d there); shards are only the contention-free write buffer that reconciles into it. Non-destructive,
  additive — the 241 KB history is untouched.
- **Attribution (§11a/§12 "which red/file is mine") stays out.** That is a *sensing* assist (diff-scoped gate
  output), a different problem from the *write-isolation* Seam C solves; leave it as the small tooling assist
  the first pass named.

## Reach — the principle, where else it applies, and what already violates it

**Principle (named plainly): _parallel-agent shared state is isolated per session and reconciled at a
coordination boundary — never mutated in place while shared._** (Shorthand: the *single-writer-per-shard*
invariant, or *isolate-and-reconcile*.)

This is **not a new principle** — it is the **worktree-isolation invariant generalized from worktrees (code)
to any agent-mutable artifact.** The repo already honors it in ≥3 places (governance changesets, agent-telemetry
per-session files, dev-stack per-session stamp) and states it macro-scale in `branch-safety.md`. So the correct
posture is *conform*, and the design above does exactly that — it adds no parallel mechanism.

- **Honored (positive instances):** worktree isolation; `.changesets/` baselines; telemetry session files;
  606's dev-stack lease/provenance; 622's session-keyed outcome join. These prove the principle is real and
  load-bearing.
- **Violated (live):**
  1. **observations.md inbox** — the high-frequency, reproduced, deferral-blocking violation. *Seam C fixes
     this one — it is the instance the present problem requires.*
  2. **regen-on-dirty-`main`** (skills-sync / llmstxt projecting a neighbour's uncommitted source). Second
     concrete instance; **named, not built** — its fix is isolate-the-generation, a *different shape* from
     shard-the-append, so a shared abstraction would be premature even though two instances now exist.
  3. **shared append-only rule files** (`agent-lessons.md` etc.) — same contention class, lower frequency;
     already *mostly* covered by the prose-tier-register changeset path, so largely self-conforming.
- **Candidate scope to watch (do not build):** any future agent-written artifact that ends up a single shared
  mutable file in `main` — per-module finding registers (621's standing-findings charter is a local instance),
  a shared agent-quality baseline (622 Layer C), a future shared "agent inbox" of any kind. Each should be
  *born* as a shard+reconcile surface, not retrofitted after the first data-loss.

**Recognition vs. building, kept separate (deliberate):** the present problem (618) requires conforming
**exactly one** violation — observations.md (Seam C). The other two violations and the candidate scope are
*recorded* here so the insight isn't lost, but **not built**: the second instance (regen) has a different fix
shape, and the rule-file case is already changeset-covered — neither yet earns a generalized engine. If a
*third* violation appears whose fix shape matches Seam C's shard+reconcile exactly (i.e. another shared
*append-target*, not a generation-source), that is the trigger to consider lifting Seam C into a small shared
"session-inbox" helper — not before. This is the same `structural-defects-no-repeat` / 622 "generalize on the
second matching instance" discipline the repo already applies.

## What this changes in 618's status

The doc's structural core is now **three seams, not one**: Seam A (worktree lifecycle correspondence —
**shipped**), Seam B (dev-stack lifecycle correspondence — **shipped**), and **Seam C (shared-agent-state
isolation — *designed here, not built*)**. The title is updated to reflect the two-seam-family structure. Seam
C's design is settled and conforming; **implementation is deferred to user green-light** (it changes the
inbox-write path for every agent — a shared-substrate decision, `ask-when-uncertain`). The promotion tail and
the two tooling decisions remain execution/decisions that Seam C unblocks but does not itself perform.

---

# De-risk pass before implementation (2026-06-21)

> A read-only/throwaway-probe pass that pressure-tested the **load-bearing assumptions** under the remaining
> work (not the diagnoses, which are T1-solid). Goal: reduce surprises before any build. **No feature work —
> every probe was a source read, a `git`/`env` query, or a dir scan; nothing in the repo was mutated except
> this record.** Evidence tiers as elsewhere (T1 in-context primary / T3 reproduced-live). Outcome: **Seam C's
> core assumptions all HOLD** (one refinement); **two §13/§14 tail items are harder than the design's glib
> phrasing implied** and are reclassified.

## Seam C — core assumptions (all verified; one design refinement)

- **P1.1 session-id at write time — HOLDS strongly (T1).** A plain agent-run command CAN resolve the session
  id with zero new machinery: the `export-session-env.mjs` SessionStart hook writes it to
  `tmp/agent-telemetry/current-session-id` (the cross-platform-robust path, since `CLAUDE_ENV_FILE` export is
  broken on Windows — `export-session-env.mjs:8-12,61-77`), and `record-merge.mjs:28-34` already reads exactly
  that pointer (`readSessionId()`). The env probe also showed `JUSTSEARCH_AGENT_SESSION_ID` and
  `CLAUDE_CODE_SESSION_ID` live in the Bash environment on this box. **Seam C's shard helper resolves the
  session id the identical way `record-merge` does — a proven, in-use pattern.** Biggest unknown → de-risked.
- **P1.2 reconcile-boundary reliability — HOLDS, with a design refinement (T1).** `record-merge.mjs` is the
  reconcile-at-merge precedent, but it is **best-effort and merge-gated** (non-fatal skip if no session id —
  `record-merge.mjs:47-51`; only runs at merge per `branch-safety.md`). For *telemetry* that's fine; for the
  *inbox* a non-merging / crashed / compacted session would strand its notes if the fold were the only
  durability. **Refinement:** make the **shards themselves tracked + committed in the agent's own worktree**
  (the `.changesets/` model — see P1.4), so a note is durable in git the instant the agent commits, *independent
  of whether the fold ever runs*. The fold into the curated `## Inbox` becomes **convenience/cleanup, not a
  correctness requirement.** This is strictly safer than the original "reconcile at teardown" framing and
  removes the "what if the boundary never fires" failure mode entirely.
- **P1.3 migration surface — HOLDS, narrow (T1).** `observations.md` has a single deterministic insertion
  section, `## Inbox` (line 28; structure: frontmatter → `# Observations` → `## Rules` → `## Inbox` →
  `## Post-push handoff`). The *write-path* references that must change are only **three**: the `CLAUDE.md`
  `log-pre-existing-issues` rule (`echo >>`), the `branch-safety.md` safe-append note, and the
  `subagent-guide.mjs` injection. All other matches are historical tempdoc mentions (no migration). The
  partial-migration risk is therefore small and bounded.
- **P1.4 "conform to changesets" — VERIFIED real, not pattern-matching (T1).** `.changesets/` dirs are
  **tracked** (`.gitkeep` + `README.md` per gate, e.g. `gates/adr-coverage/.changesets/`); a new
  `docs/observations.d/` would be **tracked by default** (`git check-ignore` → not ignored). So a per-session
  *tracked* shard dir is a byte-for-byte conform to the existing committed-shard precedent — and it is exactly
  what the P1.2 durability refinement needs. The "conform not fork" claim is sound; the tracked-vs-tmp storage
  choice is now **decided: tracked**, changeset-style.

## Promotion tail & tooling decisions — two surprises, reclassified

- **P2.1 promotion authoring gate — FEASIBLE with a now-explicit constraint (T1).** `agent-lessons.md` **is in
  the `prose-tier-register` scanner scope** (not excluded — `scanner.mjs:36-41,74-79`), and the sentence-scan
  flags `must|never|always|do not|cannot|may not` for any sentence whose *enclosing anchor* (nearest preceding
  `<!-- rule:slug -->`) is `null` (`scanner.mjs:103-155`). §10a as worded ("**never** pipe…") would trip
  `untagged-sentence`. **Cheap conforming path exists:** the file's existing un-anchored platform bullets
  (browser_batch, sleep-guard, Read-truncation) pass **by being phrased descriptively** (no trigger words) —
  so §10a/§11d should be reworded in that same declarative style ("piping a command-under-test through `tail`
  reports `tail`'s exit code, not the command's"), OR given a real anchor + tier-register row + `new-rule`
  changeset. So the promotion is **execution with a known authoring rule**, not "append a bare bullet." (§10b/c
  → `agent-postmortems.md` is unaffected — that file is outside the scanner scope.)
- **P2.2 §13 always-loaded-budget — the design's "conform to changesets" was ASPIRATIONAL (T1, corrects the
  design).** `check-always-loaded-budget.mjs` has **no changeset wiring** — only `--rebalance` (shrink) and
  `check` (`:36,56`). There is no declared-growth path to "conform" to; building one *is* new substrate. The
  file's own comment points the cleaner way: *"fold into the prose-tier-register gate once the doc set
  stabilizes"* (the kernel that **does** have changesets). **Reclassification:** §13 is **small substrate work
  (fold into the kernel, or add a classified-growth path), not a trivial decision.** Correction recorded
  against the Seam-C design's claim that §13 "conforms automatically."
- **P2.3 §14 tempdoc-status — blast radius bigger AND the easy option is FORECLOSED (T1, corrects my earlier
  rec).** `docs/tempdocs/README.md:36-47` is explicit and recent: *"Use **only** these values… Do not invent
  custom status values… If work is partially done, the tempdoc is `active`."* So my earlier "just re-canonicalize
  (bless `implemented`/`investigated`/`charter`)" **contradicts the stated design** — expanding the set is the
  wrong move. The real options are: **(a) normalize the offenders to the canonical set** (`implemented`→
  `done`/`shipped`, `investigated`/`in-progress`→`active`, …) via the existing `tempdoc-status-normalize.mjs`
  — but that touches **~20 tempdocs across ~14 non-canonical values** (`6 implemented, 3 proposed, 2 complete,
  2 closed, 1 each merged/investigated/in-progress/implemented-mostly/…`), several owned by other agents
  (contention risk); or **(b) retire the check.** *(Dogfood note: 618 itself carries non-canonical
  `status: implemented` — it is one of the ~20 offenders.)* The decision is a genuine judgment call, not the
  cheap one-liner I implied.
- **P3.1 orphan 587 dir — HOLDS (T1).** `remove-worktree.cjs:3-12` is *explicitly* built for this exact case
  ("`.claude/worktrees/587-…` that is no longer a registered worktree"): junction-unlink-link-only → .NET
  long-path delete → best-effort `git worktree prune`. Cleanup is mechanically safe; only the go-ahead is
  pending (another session's leftover, `branch-safety.md` rule #6).

## Per-item confidence after the de-risk pass (0–10)

| Item | Before | After | Why moved |
|---|---|---|---|
| Seam C — session-id write path (P1.1) | 5 | **9** | proven pointer-file mechanism, used by record-merge |
| Seam C — reconcile durability (P1.2) | 5 | **8** | refined to tracked-shard durability; boundary no longer load-bearing |
| Seam C — migration completeness (P1.3) | 6 | **9** | single insertion point; only 3 write-path files |
| Seam C — conform-to-changeset (P1.4) | 7 | **9** | verified tracked-shard precedent; storage decided |
| Promotion tail (P2.1, §10/§11→lessons/postmortems) | 6 | **8** | feasible; authoring constraint now explicit + cheap path found |
| §13 always-loaded-budget (P2.2) | 6 | **5** | "conforms" was false; small substrate work, not a decision |
| §14 tempdoc-status (P2.3) | 6 | **6** | scoped, but easy option foreclosed; genuine judgment call |
| Orphan 587 cleanup (P3.1) | 7 | **9** | tool explicitly handles this exact orphan |

**No probe refuted Seam C's core; one (P1.2) refined it to a strictly safer shape, and two (P2.2/P2.3)
corrected over-optimistic tail claims in the design above.** The Seam-C design section's two affected
sentences ("§13 conforms automatically"; "§14 re-canonicalize via the normalizer") are **superseded by this
pass** — read them with these corrections.

---

# Implementation (2026-06-21, take-over agent)

> All remaining 618 work built and validated in the **main checkout with isolated staging** (the §4 safe
> pattern — a docs/scripts-only change with no build/dev-stack need, so the worktree-prep tax was skipped;
> only my own files were touched). Validation is node gates + unit tests + a live fold test — **none of this
> is user-visible UI, so no browser validation applies** (stated and honoured). Left uncommitted for review.

## What shipped, per phase

- **Seam C — per-session inbox shards + reconcile (the structural core).** New tracked `docs/observations.d/`
  (`.gitkeep` + `README.md`, mirroring `gates/*/.changesets/`); new `scripts/agent-analytics/note-observation.mjs`
  (resolves the session id via the `current-session-id` pointer exactly as `record-merge.mjs:28-34`, with
  env + worktree-hash fallbacks; appends to *your* shard) and `fold-observations.mjs` (append-only,
  deduplicating, idempotent; writes `observations.md` first then deletes consumed shards, so a crash loses
  nothing). The three write-path references migrated: `CLAUDE.md` `log-pre-existing-issues` rule,
  `branch-safety.md` shared-`main` note, `subagent-guide.mjs` injection — all now point at the helper.
  *Validated:* **21 new unit tests green** (session-id resolution + fallbacks, entry format, disjoint-shard
  no-conflict, fold idempotence, surviving-shard dedupe, malformed/empty tolerance); **live end-to-end** — the
  real CLI wrote a real shard, and a fold against a *copy* of the real 241 KB `observations.md` landed both
  entries under `## Inbox`, preserved its BOM, and consumed the shards. **Then dogfooded for real** (see
  below).
- **§13 — declared-growth path (`--bump`).** `check-always-loaded-budget.mjs` gained
  `--bump <file> --reason "<why>"`: raises one ceiling to current size and records `{file,from,to,reason,ts}`
  in `baseline.bumps` — an explicit, auditable raise replacing silent JSON surgery, keeping `--rebalance`
  shrink-only. *Validated by using it for real* on this very change's justified Seam-C documentation growth
  (CLAUDE.md +205 B, branch-safety.md +264 B, agent-lessons.md +1392 B, each with a reason); idempotent no-op
  when within ceiling confirmed. This **corrects the design's "§13 conforms automatically" claim** — there was
  no changeset path, so the path was built (matched scope; not the heavier kernel fold).
- **Promotion tail (§10a/§11d, §10b/§10c/§3).** `agent-lessons.md` gained §10a (piped-exit masks the real
  code) and §11d (background server = bare `run_in_background` main) as **descriptively-phrased** platform
  bullets (no must/never/always triggers — `prose-tier-register` **green**). `agent-postmortems.md` gained 3
  named cases — `unreachable-seed-green` (§10b), `subset-isnt-the-suite` (§10c), `green-masked-destructive`
  (§3) — each a handle-registry row + one-paragraph narrative + citation, with matching handles in
  `agent-lessons.md`'s principle list. *Regen correctly skipped:* the edit changed only case bodies (not
  frontmatter), and `agent-postmortems.md` is not a skills-sync source, so `llms.txt`/skills are unaffected —
  running regen would only have risked the §4 dirty-`main` propagation hazard.
- **§14 — retired `tempdoc-status-check` (per user decision).** Deleted `tempdoc-status-check.mjs` +
  `tempdoc-status-normalize.mjs`; removed the invocation from `docs-validate.mjs` (with a why-comment);
  softened `docs/tempdocs/README.md` status section to **advisory** (vocabulary kept as guidance, no longer
  enforced). No dangling references remain.
- **§15 / cleanup.** Orphan `587-gpu-capability-resolver` removed via `remove-worktree.cjs` (junction-safe;
  main's `node_modules` survived — verified). `.mjs` eol: **no edit** — already LF via the default
  `* text=auto eol=lf`; the CRLF warning is benign renormalization, not a missing attribute.

## Live multi-agent contention hit — and handled by the very fix being shipped

This session *reproduced* §4/§12 live and routed around it correctly, which is the strongest possible
validation of Seam C:

- `hooks-reference.md` and `observations.md` both carried **another agent's uncommitted WIP** in the shared
  main checkout (the always-loaded-budget `--check` flagged hooks-reference's +632 B; `git diff` showed
  observations.md +4 lines — neither mine). Per `branch-safety.md` rule #6 I **did not edit either contended
  file**.
- Consequently two items were **deferred and logged via `note-observation.mjs` (dogfooding Seam C)** instead
  of editing a contended file: the §15 `hooks-reference.md` bash-guard-scope note (the matcher is
  `git push`-anchored — `bash-guard.mjs:29` — so `git rm -f` is not blocked), and the discovered **second
  orphan** `.claude/worktrees/597-chat-count`. A third shard entry logged a **pre-existing** `docs-validate`
  breakage (tempdoc 530's malformed `updated:` YAML, present in HEAD — out of scope). These three entries now
  sit in `docs/observations.d/<session>.md`, contention-free, awaiting a fold — the mechanism working in
  production on its first day.
- The stale `tempdoc-status-check` observations rows (#241/#400/#415/#515/#522) are now obsolete but their
  **checkoff is deferred** — `observations.md` is contended, and editing it is the exact anti-pattern Seam C
  removes. Prune them when the file is next curated by a non-contending owner (or after a fold cycle).

## Validation summary

| Check | Result |
|---|---|
| Seam C unit tests (21) | ✅ green |
| Seam C live end-to-end (real CLI + fold vs real-file copy, BOM-safe) | ✅ |
| `prose-tier-register` gate (CLAUDE.md + agent-lessons.md edits) | ✅ pass |
| `always-loaded-budget --check` | ✅ my files within declared ceilings; only the **other agent's** `hooks-reference.md` remains over (not mine) |
| `--bump` declared-growth path | ✅ used for real, idempotent |
| `node --check` all new/edited `.mjs` | ✅ |
| `docs-validate` | ⚠️ pre-existing YAMLException on tempdoc 530 (HEAD, not mine — logged); my retire-edit verified not the cause |
| Browser validation | n/a — nothing user-visible |
| `./gradlew` build | n/a — zero Java/FE/build-graph files changed |

## My changeset (stage these; the rest of the working tree is ambient multi-agent WIP)

New: `docs/observations.d/{.gitkeep,README.md,<session>.md}`, `scripts/agent-analytics/{note-observation,fold-observations}.mjs` (+ `.test.mjs`).
Modified: `CLAUDE.md`, `.claude/rules/{agent-lessons,branch-safety}.md`, `docs/reference/contributing/agent-postmortems.md`, `docs/tempdocs/{README,618-…}.md`, `scripts/agent-analytics/hooks/subagent-guide.mjs`, `scripts/ci/{check-always-loaded-budget.mjs,always-loaded-budget.v1.json}`, `scripts/docs/docs-validate.mjs`.
Deleted: `scripts/docs/{tempdoc-status-check,tempdoc-status-normalize}.mjs`.
**Do NOT stage** (ambient WIP): `hooks-reference.md`, `observations.md`, `settings.local.json`, `governance/agent-hooks.v1.json`, tempdoc 622, the `docs/business/**` tree, other agents' new tempdocs, `otlp-sink-ensure.mjs`.

## Status of the 618 tail

All planned items are **done or safely deferred-with-a-logged-home**: Seam C (built+validated), §13 (built),
promotion tail (done), §14 (retired), 587 cleanup (done). Deferred (contention / out-of-scope, each logged to
a shard): §15 hooks-reference note, 597 orphan, the stale-row pruning, the pre-existing docs-validate/530
breakage. Frontmatter moved `implemented → active` (substantially complete; merge pending — and 618 was
itself a §14 offender, now corrected). Merge left to the user (resolve in worktree → fast-forward, staging
only the files listed above).

