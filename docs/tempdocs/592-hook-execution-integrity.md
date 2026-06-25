---
title: "592 — Hook-layer enforcement integrity: closing the rung-3 masquerade in the agent-hook substrate. A frontend session that `cd`s into modules/ui-web parks the persisted Bash cwd off-root; the cwd-relative hook commands in .claude/settings.local.json (`node scripts/agent-analytics/hooks/X.mjs`) then resolve against modules/ui-web, where the hooks dir does not exist, so EVERY hook crashes at module-load and FAILS OPEN — silently bypassing the bash-guard safety block (rule #23) and the maintain-doc-hint governance block in exactly the sessions they govern. The trigger is a one-line path bug; the class is that the `hook` enforcement tier is claimed ~100% in the tier-register but is actually unverified (rung 3 in 576's ladder): nothing checks a hook is wired, loads, or bites — only that its file exists. The correct long-term design is to complete tempdoc 576 §3's anticipated `{hook}` guard arm: a single-authority hook manifest whose cwd-invariant wiring is a generated projection (rung 1, mis-wire unrepresentable), a kernel meta-gate that load- and fixture-bite-tests every blocking hook (rung 2, the 555-mutation analog), and a no-silent-downgrade fail-open contract (576 §5) so a hook that cannot run announces it loudly instead of silently permitting the guarded action."
status: implemented (2026-06-16) — Phases 1–6 landed on branch `worktree-592-hook-integrity` (6 commits). Rung 1 (cwd-invariant exec-form commands + single-authority `governance/agent-hooks.v1.json` → generated settings projection + `--check`), rung 2 (`hook-integrity` 530 gate: wiring + cwd-invariant + load + bite, real subprocess bites for bash-guard + maintain-doc-hint), the no-silent-downgrade `runHook` contract, dev-layer `hook_failure` telemetry, and the committed registry projection (hook-integrity now in the GovernanceView roster). All gates / self-tests / unit tests green. **One validation deferred to post-merge**: the browser pixel-render of the new roster row — the shared dev runner roots at the main checkout (served `gateCount 35`, `source: F:\JustSearch\tmp\…`), so it runs main's code, not this worktree's unmerged commits, and the worktree has no FE `node_modules` to build a standalone UI; the running-product API serving the new gate was verified at the jar/projection level instead. See §10 (as-built) for outcomes + the two design corrections.
created: 2026-06-16
updated: 2026-06-16
---

# 592 — Hook-layer enforcement integrity

## 0. The issue in one paragraph

Agent sessions intermittently emit a repeating `Stop hook error … Cannot find module
'F:\JustSearch\modules\ui-web\scripts\agent-analytics\hooks\maintain-doc-hint.mjs' …
MODULE_NOT_FOUND`. The trigger is a one-line path bug: every hook command in
`.claude/settings.local.json` is a **cwd-relative** `node scripts/agent-analytics/hooks/X.mjs`,
and the Bash tool's working directory **persists** across calls. The prescribed frontend
workflow (`cd modules/ui-web && npm run typecheck && …`, mandated by CLAUDE.md / `/ui-check`)
parks the session cwd off the repo root, so Node resolves the relative path against
`modules/ui-web` — where `scripts/agent-analytics/hooks/` does not exist — and the hook crashes
**at module-load, before any hook code runs.** Because the crash is uniform and pre-`main()`, it
hits *every* hook, and it **fails open**: the `bash-guard` safety block (exit-2) and the
`maintain-doc-hint` governance block (`{"decision":"block"}` JSON) both silently do not fire — in
exactly the frontend sessions they are meant to govern.

## 1. The reframe — this is not a typo, it is a rung-3 masquerade

The path bug is the *trigger*. The *class* is named precisely by tempdoc **576 (The
Enforcement-Strength Ladder)**. 576 §2 places every invariant on a rung; **rung 3 —
"Guarded, unverified: a gate/test exists but might be a placeholder / never-failing assertion …
the danger zone — looks like rung 2, enforces like rung 5."** That is exactly the state of the
entire hook layer:

- The `tier-register` assigns `bash-guard` (rules #19/#23/#25) tier `hook` = **~100% adherence**,
  and uses that strength to justify "encode it as a hook, not prose."
- But the only thing any check verifies about a hook is that **its file exists**
  (`prose-tier-register` resolves a `hook:<filename>` marker to a file under
  `scripts/agent-analytics/hooks/`). Nothing verifies the hook is **wired** into settings, that it
  **loads**, or that it **bites** (actually blocks a violating input).
- So a hook that crashes on load *looks like* rung 2 (a blocking guard) and *enforces like* rung 5
  (a human might notice the error text). The repo's strongest-claimed enforcement tier is, in
  aggregate, unverified.

576 §3 ("Maturation gap A — the guard-integrity meta-invariant") even **anticipated this exact
arm**: it proposes the guard field become "a shared **discriminated union** — `{test}|{gate}|
`**`{hook}`**`|{archunit}`" and says "every declared guard must provably **resolve *and* bite**, or
it is honestly relabelled rung 4/5." The `{hook}` arm was named but never completed: hook
resolution is still the weak file-exists check. **Tempdoc 592 is the completion of 576 §3's
`{hook}` arm — lifting the hook tier from rung 3 to rung 2.**

## 2. What already exists (extend, do not replace)

The investigation found the substrate is ~80% present; this design is an extension, honoring
547/AHA ("only unify what shares a reason to change"):

| Existing mechanism | What it gives us | The gap 592 fills |
|---|---|---|
| **530 discipline-gate kernel** (`scripts/governance/run.mjs`, `registry.v1.json`, truth-table model, SARIF, changesets, self-test fixtures) | One kernel, gates differ by `kind:`; a meta-gate is just another gate-class | A `hook-integrity` gate-class joins the registry |
| **`prose-tier-register` meta-gate** (validates the tier-register itself; resolves `hook:`/`gate:`/`archunit:` markers) | The precedent for "a gate that checks the governance layer"; already half-resolves hooks | Extend its hook resolution from *exists* → *wired + loads + bites* |
| **`register-guard-resolution` meta-pass + shared `guard-resolver`** (576 §3, one resolver over all registers, discriminated guard tokens, no-silent-downgrade) | The discriminated-union guard model with a `{hook}` arm reserved | Implement the `{hook}` arm's resolution semantics |
| **register family** (`execution-surfaces.v1.json` etc.: single-authority declaration → generated projection → anti-drift gate; 553 rung-1) | The rung-1 mechanism: a typed projection makes the bad state unrepresentable | A `governance/agent-hooks.v1.json` manifest whose wiring projection is generated, not hand-written |
| **`hook-base.mjs` `runHook()` wrapper** (520-P1a; direct-run guard + kill-switch + fail-open in one) | One shared entry-point every hook already routes through | The single place to make fail-open **loud + attributed** for blocking hooks |
| **`dispatch.mjs` NDJSON telemetry** (530 Layer 3 half-built: `lib/history`, `lib/dashboard`) | A live event stream already records hook firings | Record hook **load-failures** so live fail-open is visible, not silent |
| **520 hook-hardening** (P0 regex fixes, P1 shared lib + kill-switch + unit tests) | Hooks already have pure-function extraction + per-hook unit tests | The missing tier above unit tests: a *system* check that the wired hook runs |

Nothing here is a new system. The hook layer is simply the **largest un-laddered enforcement
surface left in the repo** — every other enforcement family (ratchets, registers, prose rules)
already has a rung and a no-silent-downgrade guard; hooks never got one.

## 3. The core distinction the design must encode

"A hook file exists" is three properties short of "a hook enforces." For any declared hook, rung 2
requires:

1. **Wired** — it is registered in the live `settings.local.json` hooks block for the correct
   event + matcher. (Today: unverified — a hook can exist as a file and be wired to nothing, or be
   wired but renamed.)
2. **Loads** — the configured command resolves and the module imports without throwing, **under
   the invocation the harness actually uses** (i.e. cwd-independent). (Today: the failing
   property — the relative path is cwd-fragile.)
3. **Bites** — for a *blocking* hook, a known-violating fixture input actually produces the block
   signal (exit 2 / `{"decision":"block"}`); for an *advisory* hook, the expected hint. (Today:
   unverified — the analog of 555's "the test exists vs the test would catch the bug.")

And one contract spanning all three, from 576 §5 (**no silent downgrade**): when a blocking hook
**cannot** satisfy (2), it must fail **loud and attributed**, never silently open. A crashed
safety guard that silently permits `git reset --hard` is a silent slide from rung 2 to rung 5 —
precisely the move the kernel already forbids for ratchet baselines, here un-generalized to hooks.

## 4. The long-term design — the ladder applied to the hook layer

576 §2 frames the substrate's whole job as three things; the design is their completion *for
hooks*:

### (I) Locate — a single-authority hook manifest (rung 1)

Today the hook layer has **three disconnected representations**: the hook files, the
`settings.local.json` wiring, and the `tier-register` `hook:` markers. Three authorities drift —
the classic representation-drift class the register family (553) exists to kill. The rung-1 move
is to make one of them canonical:

- A **`governance/agent-hooks.v1.json` manifest** is the single authority: each entry declares the
  hook id, its file, the event(s) + matcher it binds to, its rung/role (`blocking` vs `advisory`),
  and (for blocking hooks) the violating fixture that proves bite.
- The **`settings.local.json` hooks block becomes a generated projection** of the manifest (or, if
  full generation is undesirable for a machine-local file, a gate asserts the block is
  *equivalent* to the manifest projection). The generator emits **cwd-invariant commands** by
  construction (absolute / `$CLAUDE_PROJECT_DIR`-anchored, with the Windows shell-expansion form
  resolved once, centrally). **A cwd-fragile relative path becomes unrepresentable** — you cannot
  hand-write the bug the generator never emits. This is the 553 single-authority-projection
  mechanism, applied to hook wiring.
- The `tier-register` `hook:` markers resolve to **manifest entries**, not bare files — so a rule
  claiming `hook` tier must point at a hook the manifest proves is wired.

This single move dissolves the entire trigger: relative-path crashes, mis-wired hooks, and
renamed-but-still-referenced hooks all become manifest-integrity failures at build time rather
than silent live fail-opens.

### (II) Prevent silent downgrade — the `hook-integrity` meta-gate (rung 2) + the loud-failure contract

- A **`hook-integrity` kernel gate** (a new `kind:` on the 530 registry, sibling to
  `prose-tier-register` / `register-guard-resolution`) does the rung-2 verification the file-exists
  check never could:
  - **Resolution + wiring:** every manifest hook is wired in settings and vice-versa (no orphan
    file, no dangling wiring); the command path is cwd-invariant.
  - **Load:** spawn each hook with an empty/benign payload and assert it does not crash on load.
  - **Bite:** for each `blocking` hook, feed its declared violating fixture and assert the block
    signal fires; for advisory hooks, assert the expected hint. This is the **direct analog of
    555's mutation-efficacy ratchet** ("does the guard kill the mutant?") — the only mechanism in
    the repo that proves *bite*, here pointed at hooks instead of seam tests. 520 already extracted
    each hook's decision logic into importable pure functions with per-hook unit tests; the gate
    adds the missing **system tier** — the *wired* command runs and blocks, not just the unit.
- **The no-silent-downgrade contract**, centralized in `hook-base.runHook()`: a blocking hook that
  throws internally must convert the failure into a **loud, attributed signal** (a stderr line the
  agent sees: "bash-guard FAILED to run — destructive-git protection is OFF this call" + a
  telemetry record), never a bare stack trace that the harness treats as a permissive non-block.
  We cannot change the harness rule that a non-2 exit = "tool proceeds" (Wall 2 — see §6), but we
  *can* guarantee the failure is never **silent**. This generalizes 576 §5's anti-silent-downgrade
  invariant from ratchet baselines to the hook tier.

### (III) Make legible — hook-execution telemetry (530 Layer 3)

`dispatch.mjs` already writes an NDJSON event stream. Extend it so a **hook load-failure in a live
session is a first-class recorded event**, surfaced via the existing `lib/history` / `lib/dashboard`
(530 Layer 3) and the `/api/governance/state` read surface (576 §15). The current state — the only
evidence a blocking hook silently failed is a transient error line the user happened to see — is
the legibility gap 530 Layer 3 was built to close; hooks were simply never wired into it.

## 5. Why this structure, not the cheap fix

The cheap fix (absolute paths in `settings.local.json`) eliminates *this* crash and **should land
immediately** — it closes the active safety-block fail-open today, and it is the rung-1 first step.
But by 576 §3's own argument, the cheap version "leaves rung 3 undetectable in aggregate: the next
register, or the next agent, reintroduces a placeholder and no single check sees it." Applied here:
the next renamed hook, the next load-time `throw`, the next hook wired to the wrong event, or the
next agent who re-introduces a relative path silently fails open again, and nothing detects it. The
repo's settled discipline (`structural-defects-no-repeat`: one documented silent bug proves the
class) and its treatment of *every other* enforcement family say the structurally-correct end-state
is the meta-invariant — "no hook may silently sit on rung 3" as a **property of the system**, not a
convention re-litigated each time `settings.local.json` is edited.

## 6. What this is NOT — honest residue (Walls 1 & 2)

Following 576's load-bearing honesty about what the ladder *cannot* close:

- **It does not change harness semantics.** Claude Code treats a non-2-exit hook as a non-blocking
  error and runs the tool. We make crash-on-load impossible (rung 1) and remaining failures loud
  (the contract); we do **not** make live fail-open *impossible*. Fail-closed-on-crash is not ours
  to choose at the harness level.
- **It does not prove a hook catches *every* violation** (Rice's theorem; the Wall-1 residue 553/
  555 are honest about). The bite test proves the hook blocks **sampled** violating fixtures — the
  same honesty as mutation testing sampling mutants. Floor-raising within declared scope, not a
  totality proof.
- **Subagents run with no hooks at all** (520 independent-review note). That is an unfixable
  platform constraint; it stays rung 5 (prose: "don't delegate destructive git").
- **`settings.local.json` is machine-local.** Full generation may be undesirable; the equivalence
  gate (manifest ≡ live wiring) is the fallback that gives the same rung-1 guarantee without owning
  the file.
- **Bash-tool cwd persistence is harness behavior.** We do not try to fix it; we make our
  invocation immune to it. (Per CLAUDE.md `stay-focused`: the controllable surface is our own
  invocation, not the harness.)

## 7. Relationship to adjacent tempdocs

- **576 (Enforcement-Strength Ladder)** — the parent frame. 592 occupies the `{hook}` arm of 576
  §3's guard-integrity meta-invariant, which 576 named but left unbuilt. The rungs, the
  no-silent-downgrade invariant (§5), and the cost-to-game framing (§17.0) all transfer directly.
- **520 (Claude Code Hooks Hardening)** — the direct ancestor. 520 fixed hook *logic* defects (P0)
  and added the shared `hook-base`/`runHook` + per-hook unit tests + kill-switch (P1). 592 is the
  next tier 520 explicitly did not build: 520 verifies a hook's *function* in isolation; 592
  verifies the *wired hook actually runs and blocks* as a system. The `runHook` wrapper 520 created
  is exactly where 592's loud-failure contract lands.
- **530 (discipline-gate kernel)** — the host. `hook-integrity` is a new gate-class on the same
  registry/truth-table/SARIF/changeset substrate; the telemetry work completes 530 Layer 3 for the
  hook surface.
- **555 (mutation testing)** — the rung-2 "bite" precedent. The hook bite test is the
  mutation-efficacy idea ("the test would catch the bug") pointed at hooks.
- **553 (register family)** — the rung-1 "single-authority projection" precedent the hook manifest
  reuses.

## 8. Confirmed facts (evidence)

| Claim | How confirmed |
|---|---|
| Hook exists only at repo root, not under `modules/ui-web` | `scripts/agent-analytics/hooks/maintain-doc-hint.mjs` present; `modules/ui-web/scripts/` has no `agent-analytics/` |
| All hook commands are cwd-relative `node scripts/agent-analytics/hooks/…` | every entry in `.claude/settings.local.json` `hooks.{SessionStart,PreToolUse,PostToolUse,PostToolUseFailure,PreCompact,SubagentStart,SubagentStop,UserPromptSubmit,InstructionsLoaded,Stop,SessionEnd}` |
| Crash is at module-load (uniform, pre-`main()`) → hits every hook | `MODULE_NOT_FOUND` is thrown by Node's loader before the imported `main()` runs |
| `maintain-doc-hint` blocks via JSON; its fail-open catch is downstream of load | `maintain-doc-hint.mjs:149` (`{"decision":"block"}`), `:152` (`main().catch(() => process.exit(0))`) — the catch never engages on a load crash |
| `bash-guard` blocks via exit code 2; a load crash exits 1 → tool proceeds | `bash-guard.mjs` header `2 = block`; non-2 exit is a non-blocking harness error |
| Wiring has no single authority; hook integrity is unverified | no hook manifest exists; `settings.local.json` is the sole wiring; `prose-tier-register` resolves `hook:` markers by **file-existence only** (`gates/prose-tier-register/` resolver), not wiring/load/bite |
| 576 anticipated a `{hook}` guard arm but left it unbuilt | 576 §3: guard field as "`{test}|{gate}|{hook}|{archunit}`"; "must provably resolve *and* bite" |
| Hooks' internal `repoRoot` is already cwd-independent | `lib/hook-base.mjs` derives `repoRoot` from `import.meta.url`, not cwd — the fragility is *only* in the settings invocation, so the rung-1 fix is contained to wiring |

## 9. Sequencing (conceptual, not implementation)

1. **Rung-1 invocation fix (now, independently):** make all hook commands cwd-invariant. Stops the
   active safety-block fail-open immediately and is the first increment of the manifest projection.
2. **The manifest + generated/validated wiring (rung 1 proper):** `agent-hooks.v1.json` as single
   authority; the cwd-fragile path becomes unrepresentable; tier-register `hook:` markers resolve
   to manifest entries.
3. **The `hook-integrity` meta-gate (rung 2):** load + bite tests for every wired hook, as a 530
   gate-class; completes 576 §3's `{hook}` arm.
4. **The loud-failure contract + telemetry (no-silent-downgrade + 530 Layer 3):** `runHook` makes
   blocking-hook failures loud/attributed; `dispatch` records live load-failures.

Each step is independently valuable and independently shippable; together they move the hook tier
from "claimed ~100%, actually unverified" to "verified rung 2, with the residue (§6) named."

## 10. As-built (2026-06-16, branch `worktree-592-hook-integrity`)

Phases 1–6 implemented. Commits, in order:

1. **Phase 1 — cwd-invariant commands.** All 35 hook commands in `.claude/settings.local.json`
   rewritten to the exec-form `{command:"node", args:["${CLAUDE_PROJECT_DIR}/…"]}` (docs-recommended,
   Windows-shell-fallback-safe). matcher/if/async/timeout preserved.
2. **Phase 2 — single-authority manifest + generated projection.** `governance/agent-hooks.v1.json`
   (+ `.schema.json`) is now the one authority; `scripts/codegen/gen-agent-hooks-wiring.mjs` projects
   it into the settings hooks block (cwd-invariant by construction) with a `--check` mode +
   `scripts/ci/check-agent-hooks-wiring-regen.mjs`. Byte-identical round-trip against Phase 1 verified.
3. **Phase 3 — `hook-integrity` 530 gate.** `scripts/governance/gates/hook-integrity/*` + registry row
   + self-test fixtures + 16-check truth-table unit test. Verdicts: wiring, cwd-invariant (regression
   teeth), load, bite, tier-register-sync. Real subprocess bites for **bash-guard**
   (force-push → exit 2) and **maintain-doc-hint** (governed-edit → `{"decision":"block"}`); the 3
   state-dependent siblings reference their 520 unit tests. Wired into `ci.yml`.

   **Correction C (load probe strengthened — closes review finding #1).** The load verdict first used
   `node --check` (syntax-only), which the review found weaker than §4.II's intent ("spawn with a
   benign payload, assert no crash on load") — a hook with valid syntax but a broken/missing import
   would pass yet crash at runtime. The probe now spawns each hook for real with `{}` on stdin +
   `JUSTSEARCH_DISABLE_HOOKS=1` + a timeout: the module's full import graph must resolve before
   `main()` runs (kill-switch hooks skip `main`; the 13 hint hooks read `{}`+EOF and return), so a
   broken import exits non-zero pre-`main` and is caught, while a clean hook exits 0. A dedicated
   `enforcer.test.mjs` proves the delta (a broken-import hook the old `node --check` passed now fails).
   No hook files changed; localized to the enforcer's measurement.
4. **Phase 4 — no-silent-downgrade contract.** `runHook` now looks up role from the manifest and, for
   a BLOCKING hook whose `main()` throws, emits a loud attributed stderr line + a `hook_failure`
   telemetry event (advisory stays quiet). `maintain-doc-hint` migrated onto `runHook` (also fixes a
   latent import-time stdin hang). Pure core unit-tested.
5. **Phase 5 — dev-layer legibility.** `analyze-session.mjs` aggregates `hook_failure` into the session
   report (`{total, blocking, by_hook}`). End-to-end verified.
6. **Phase 6 — product-UI surface.** Regenerated the committed `governance-state.json` projection so the
   `hook-integrity` gate joins the GovernanceView roster (gateCount 35→36).

**Correction A (supersedes §6 bullet "settings.local.json is machine-local").** `.claude/settings.local.json`
is **git-tracked** and the sole hook authority (project `settings.json` + user `settings.json` define no
hooks) — so full generation of its hooks block is clean and version-controlled, not a fallback.

**Correction B (refines §4.III "Make legible").** Surfacing per-session `hook_failure` events in the
product `/api/governance/state` would be a **layering violation**: that endpoint derives ONLY from
committed authorities (the registry projection + build SARIF) and never reads dev-local
`tmp/agent-telemetry`. So per-session failures are surfaced in the agent-analytics dev layer (§Phase 5)
+ the live loud stderr (§Phase 4); the sound product-UI surface is the **gate itself** in the roster
(§Phase 6), a pure registry projection.

**Deferred validation.** The literal browser pixel-render of the new roster row is post-merge: the shared
dev runner roots at the main checkout (verified: it served `gateCount 35` / `source: F:\JustSearch\tmp\…`),
so it cannot run this worktree's unmerged code, and the worktree lacks FE `node_modules`. The new gate's
presence in the served projection was verified at the jar level (`gateCount 36`, `hook-integrity` present
in `ui-*.jar`'s `governance/governance-state.json`). To pixel-verify: merge → run the stack from `main` →
open GovernanceView → confirm the `hook-integrity` row.
