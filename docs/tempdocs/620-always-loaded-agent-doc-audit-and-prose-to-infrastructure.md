---
title: "The always-loaded agent docs: critical audit + subjecting the layer to the codebase's own projection / delivery / ratchet seams"
type: tempdocs
status: active
created: 2026-06-20
updated: 2026-06-20
author: agent analysis (always-loaded context audit), filed by agent
category: dx / agent-tooling / context-engineering / governance / agent-docs
related:
  - external-agent-harness-context-engineering   # 616 — delivery > governance; GOVERNED_REGIONS
  - agent-developer-velocity-friction             # 618 — the catalogue → design shape this mirrors
  - governance-critique-agent-lens                # 582 — is the discipline machine worth it
  - canonical-doc-drift-remediation               # 579 — canonical-doc drift (sibling, different layer)
  - before-appending-to-rules
  - delegating-to-subagents
  - subagents-no-inheritance
---

# 620 — The always-loaded agent docs: a critical audit, and subjecting the layer to the codebase's own governance seams

> **What this document is.** Two linked passes over the documentation that enters **every** agent
> session's context window unconditionally — `CLAUDE.md` + the seven `.claude/rules/*.md` files (~1,000
> lines). **Part I** is a critical audit: correctness defects, over-claims, bloat, redundancy, and one
> structural reason the bloat is invisible to its own guard. **Part II** is a design pass on the question
> "how much of this should be *infrastructure* (hooks / gates / skills) instead of always-loaded prose?"
> **Part III** settles the long-term design (three conforming moves); **Part IV** judges its reach;
> **Part V** is the enforcement-tier conversion (which prose rules can become hook/lint/codegen). Every
> external-state claim was verified against source, the gate enforcers, and tempdocs 616/618/582/579 —
> `verify-dont-guess` applied to the docs themselves.

## Status (2026-06-20) — what landed vs deferred

**Implemented + verified** (gate-green throughout; **committed to main as `1773864b8`, 2026-06-21** — 35 files, +2002/−223):
- **Audit (Part I):** 5 correctness fixes to always-loaded rules (tier-register self-contradiction, the false
  subagent `bash-guard` claim, the `agent-guide` dangling refs, the `git checkout` carve-out, the stale
  large-file list).
- **Moves 1–3 (Parts II–III):** the `consult-register` delivery substrate (Move 2); the Pre-merge block
  compressed + Skills section evicted (Move 1); the always-loaded **budget ratchet** (Move 3). Always-loaded
  set **~25,200 → ~20,600 tokens (≈ −18%)**, now ratchet-protected against regrowth.
- **Part V — 4 enforcement-tier conversions:** `subagent-guide` invariant de-fork (fixed a live 4-of-6
  drift), `compact-restore` emits worktree/branch, the `tempdoc-age-hint` Read hook, and the suppression
  ratchet.
- **Part VI — post-implementation critical-analysis pass + closure:** found Part V's *enforcement* moves had
  outrun its *documentation* (3 mechanisms shipped, register/catalog left stale). Closed the bookkeeping
  (reconciled 4 tier-register catch-columns, documented `tempdoc-age-hint`, de-duplicated worktree-verify,
  collapsed the dual size-authority) and fixed a `prose-tier-register` false-positive on the ephemeral
  `compaction-state.md`. Surfaced two design questions left for the user: a `delivery`/`hook-hint` tier (VI.B)
  and whole-file residence relocation (VI.D). Net reduction eased −18% → **≈ −15%** as correctness docs were
  added back.
- **Phases 1–8 — remaining-work execution (plan-approved):** `common-workflows.md` relocated to a canonical
  doc + consult-register **recipe** rows (Move 2 completed); branch-safety dev-stack subsection → `/dev-stack`;
  Common-Pitfalls + worktree de-dups; the **`hook-hint`** delivery tier (VI.B, with a changeset); a Pre-merge
  drift `--check` validator (rec #2). Always-loaded now **≈ −26% vs session start** (74,575 B). #13 + the
  Tier-2 partials deferred with reason (below). See "Phases 1–8" at the foot of this doc.

**Deferred (named, not dropped):** `slice-execution.md` relocation (methodology, no path trigger); #35
`verify-worktree-base` (no deterministic hook trigger); the Tier-2 partials #7/#24/#26 (false-positive /
reminder-only, 582 R1); #13 tempdoc-completeness (host check non-functional vs practice — Phase 7). Judgment
rules stay prose; the retired gates #29/#30/#31 are not re-mechanized.

**New infrastructure:** `governance/consult-register.v1.json`; `scripts/ci/{always-loaded-budget,suppression-ratchet}*`;
`scripts/agent-analytics/lib/hard-invariants.mjs` + `scripts/agent-analytics/hooks/tempdoc-age-hint.mjs`.

## Problem

The always-loaded set is the only documentation that taxes **every** session regardless of task — it is
paid for on a pure-backend bugfix and a frontend-only tweak alike. CLAUDE.md quotes Anthropic verbatim —
*"Bloated CLAUDE.md files cause Claude to ignore your actual instructions!"* — and defines its own
inclusion gate (*"would a fresh agent on a different task need this? If it's specific to one slice / one
module / one failure mode, it's not a CLAUDE.md rule"*). Measured against that gate, **roughly 45% of the
~1,000 always-loaded lines do not qualify**: they are task-specific, role-specific (for *editing* the
rules, not doing the work), or duplicate an on-demand skill. Meanwhile a handful of the rules that *are*
load-bearing carry factual defects that actively mis-teach. The volume both **dilutes** the six Hard
Invariants that are the file's reason to exist and **mis-states** what is mechanically enforced.

## Methodology & evidence tiers (per 618)

- **T1 (primary, in-context):** read directly from repo source, a gate enforcer, a tool schema, or `git` in
  this session. Highest confidence.
- **T2 (corroborated by tempdoc):** a recent tempdoc's investigated finding agrees.
- **T3 (argued from the doc's own stated standard):** no external check needed; the finding is that the
  corpus violates a principle it states about itself.

Each finding also carries the 618 **fix-tier** for *who acts*: **A** repo-fixable (prose edit / new hook /
gate) · **B** harness-config (`settings.json`) · **C** agent-discipline / upstream.

---

# Part I — The audit

## A. Correctness defects — these mis-teach the agent (fix-tier A)

1. **`tier-register.md` self-contradicts on whether its own gate exists. [T1]**
   Header (lines 3–5): *"The meta-loop gate that enforces this register's completeness is a follow-up; this
   file is the data that future gate will consume."* Footer ("What the meta-loop gate enforces"): *"The
   `prose-tier-register` gate now enforces all of the following."* The gate **exists** —
   `scripts/governance/gates/prose-tier-register/enforcer.mjs`, registered at `governance/registry.v1.json:137`.
   **The header is the stale half** (written at seed time, tempdoc 530 §Meta-loop; never updated when the
   gate landed). Fix: replace the header sentence with "now enforced by the `prose-tier-register` gate (see
   below)."

2. **`tier-register.md` row 18 states a false enforcement. [T1 + T2]**
   *"(`bash-guard` blocks destructive git in subagent shells, partial coverage)."* It cannot: parent
   PreToolUse hooks **do not fire in subagents** — stated in CLAUDE.md's own *Delegating to Subagents*
   section, anchored in `agent-lessons.md` (`parent-hooks-dont-fire-in-subagents`), and reaffirmed by the
   newest governance tempdoc **616:253-254** (*"parent hooks (`bash-guard`) don't fire in subagents"*).
   `bash-guard` gives **0%** subagent coverage, not "partial." Fix: change the parenthetical to "no
   subagent coverage — hooks don't fire in subagents; the inline brief is the only control there."

3. **`agent-guide.md` cites a CLAUDE.md section that no longer exists, ×2. [T1]**
   Lines 108 and 390 reference *CLAUDE.md "Verification Workflow" step 5*. CLAUDE.md has **no** "Verification
   Workflow" section and no numbered steps. Origin traced: it *was* a numbered section (tempdocs 218/353/367
   cite "step 5 + step 6"); it was restructured into "Quick Commands → Pre-merge script checks," and the
   `agent-guide` pointers were never updated. Fix: repoint both to "Quick Commands → Pre-merge script checks."
   (`agent-guide.md` is not itself always-loaded, but it is the always-loaded layer's primary linked entry
   and the dangling ref is in scope as drift *from* the always-loaded set.)

4. **`branch-safety.md` ↔ `hooks-reference.md` disagree on `git checkout`. [T1]**
   branch-safety's "Enforced by bash-guard.mjs" table (line 106, current) lists `git checkout` flatly as
   blocked. `hooks-reference.md` records the tempdoc-520-P0c carve-out: whole-tree `git checkout -- .` is
   blocked **but single-file `git checkout -- <path>` is allowed**. An agent reading branch-safety alone
   believes single-file restore is blocked when it is not. Fix: add the single-file carve-out to
   branch-safety's table row (one clause).

5. **`context-efficiency.md` "known large files" list is stale. [T1]**
   It names Shell.ts / SearchSurface.ts / SummaryController.java / LuceneIndexRuntime.java / analyze-session.mjs
   but **omits `UnifiedChatView.ts` — 5,436 lines** (verified `wc -l`), the single largest FE file and the
   one 618 §5 flags as the worst Read-friction source (and the cause of the 610 dual-render bug-class). The
   list misdirects away from the actual worst offender. Fix: add `UnifiedChatView.ts`; consider generating
   this list from a `wc -l` threshold rather than hand-maintaining it.

6. **`tier-register.md` row 16 mis-attributes its own enforcement. [T1, medium confidence]**
   It marks `before-appending-to-rules` as tier `gate` → `gate:prose-tier-register`. But that gate enforces
   register **integrity** (anchors ↔ rows ↔ markers); it does **not** enforce the rule's actual content —
   the three-question judgment ("broad applicability / already-said / enforcement question") an agent runs
   *before* appending. That judgment is unmechanizable and effectively `prose-only`; the row over-claims a
   `gate`. Fix: split the row — the *append-time judgment* is `prose-only`; the *register-consistency* half
   is the `gate`. (This matters: row 16 is the one row asserting the bloat process is gate-enforced, and
   Part I.E shows it is not.)

## B. Over-claims — the doc promises more than the mechanism delivers

7. **The sentence-scan does less than the register claims. [T1, from `scanner.mjs:107-148`]**
   tier-register claim #3: *"every must/never/always/do not sentence … falls inside an anchored section."*
   Actual logic: each imperative is attributed to *the nearest `<!-- rule: -->` anchor positionally above it
   in the file* — even an unrelated one — and (per the changeset/baseline model) only **net-new** sentences
   are flagged. So the real guarantee is "has *some* anchor above it," not "inside *this rule's* section,"
   and not "every." Fix: reword claim #3 to the true guarantee, or tighten the scanner to require the anchor
   to be section-local.

8. **Hard Invariant #4 points at the debug dump, not the canonical signal. [T1]**
   It names `/api/debug/state` + `/api/health` + `/infra/capabilities` for lifecycle — all real routes
   (verified) — but **omits `/api/status`**, which the canonical docs (`08-observability`, "Status as the
   primary health signal") designate **the** primary lifecycle signal (`api-contract-map.md:21,47`). The
   always-loaded invariant steers agents to the secondary surface. Fix: add `/api/status` as the primary;
   keep `/api/debug/state` as the detailed dump.

## C. Bloat / wrong-tier — correct content, wrong place (fix-tier A, relocate not delete)

All references here were verified **current** (33/33 cited `check-*.mjs` exist; 11/11 governance gate ids
live; `docsApiDriftCheck` resolves) — so this is **bloat, not rot**: relocate, don't rewrite.

| Item | Lines | Why it fails broad-applicability | Destination |
|---|---|---|---|
| CLAUDE.md "Pre-merge script checks" block | ~40 | ~30 of ~40 bullets are `ui-web`/`shell-v0`-specific, each tied to one tempdoc/slice | `/ui-check` (or a ui-governance skill); **better**: generate from `governance/registry.v1.json` on demand (Part II) |
| `common-workflows.md` | ~120 | The tier-register itself classes it non-load-bearing procedures; most sessions add no gRPC method / config key. Its own new budget marker says "promote to a skill" if a workflow exceeds ~10 steps | Skill(s) / PostToolUse recipe push |
| `tier-register.md` | ~150 | Meta-documentation about rule *provenance*; useful only when *editing* rules. Excluded from its own gate's scan | PreToolUse consult-push when editing a rule file |
| `branch-safety.md` "Shared Dev Stack" subsection | ~60 | Relevant only when starting the dev stack; duplicates the `/dev-stack` skill **and** the MCP tool's own `recommendedAction` response (606) | `/dev-stack` skill; rely on the self-describing tool response |
| `slice-execution.md` (quick-ref) | ~96 | Slice-work-only; opens by pointing at its own 1,280-line full version | Skill / open-time push |

Tally ≈ **470 of ~1,000 always-loaded lines** are relocation candidates. (Line items are individually
contestable — the point is the aggregate, not any single row.)

## D. Redundancy inside the always-loaded context (drift surface)

- **CLAUDE.md "## Skills" (~23 lines) duplicates the harness-auto-injected skill registry** — the
  session-start system-reminder already lists every skill with its trigger. Two copies in the *same* context
  window guarantee eventual drift.
- **The blocked-git-command list appears 3×:** branch-safety "Hard Rules" prose, branch-safety "Enforced by
  bash-guard.mjs" table, hooks-reference's bash-guard section.
- **Worktree directory table** in both CLAUDE.md "Parallel Agents" and branch-safety.
- **Dev-stack ownership** in branch-safety **and** the `/dev-stack` skill **and** the tool response.

## E. The structural gap — *why* the bloat accumulates (the keystone finding)

The maintainers rely on `before-appending-to-rules` / the `prose-tier-register` gate to **prevent** CLAUDE.md
bloat (616 §B.3b: mass-retrofitting "would fail the bloat gate"). But that gate only governs **anchored
`<!-- rule: -->` sentences**. The "Pre-merge script checks" list and the `common-workflows` procedures are
**not anchored rules** — so the bloat-prevention mechanism *structurally cannot see the largest bloat
sources.* The guard guards the wrong surface. This is why ~45% non-qualifying content coexists with a gate
designed to stop exactly this — and it is the through-line that makes Part II necessary: you cannot
prose-discipline your way out; the budget has to be defended by infrastructure.

> The user has begun exactly this defence: per-file `<!-- budget: ~N tokens -->` markers now head five of
> the seven rules files (`agent-lessons` ~1500, `common-workflows` ~2000, `branch-safety` ~1800,
> `context-efficiency` ~600, `slice-execution` ~1200), several with a "promote to a skill" escape hatch.
> Part II is the systematic version of that instinct. (Gap: CLAUDE.md, `tier-register.md`, and
> `hooks-reference.md` have **no** budget marker yet — and CLAUDE.md is the largest always-loaded file.)

## F. Reconciliation with the repo's own audit (honesty)

616 §7.2 judged the tier-register *"mature/well-curated"* and concluded *"the leverage is in delivery
(context), not more enforcement (governance)."* No conflict: (a) 616's pass was an **enforcement-tier-upgrade**
scan (can a `prose-only` row become a `hook`?), blind to header/footer contradictions or wrong
"Catches-violations-via" notes — different axis than this correctness audit; (b) 616's *delivery* finding
concerns the **on-demand canonical docs read 0×** (46 of 146) — a *different layer* than the always-loaded
set. Both hold, and they compose: Part II is where this audit's "relocate the bloat" meets 616's "deliver
the right doc just-in-time."

## G. What was checked and CLEARED (calibration — negatives are findings, not pattern-matching)

- `/api/debug/state` is **current**, not legacy (`api-contract-map.md:749`; 618:466 lists it as a current
  readiness source) — my initial "looks stale" suspicion was **wrong**.
- All **33** cited `check-*.mjs`, **11** governance gates, and `docsApiDriftCheck` are **live** → the
  Pre-merge block is bloat, not rot.
- Invariant #4's three endpoints are all real routes.

---

# Part II — Migrating prose into infrastructure (hooks / gates / skills)

## The reframe: the always-loaded file is a *delivery mechanism*, and the worst one

Every always-loaded line pays its context cost on **100%** of sessions but delivers value only on the
fraction where it is relevant. Infrastructure inverts this: a hook or skill pays its cost **only when its
trigger fires**, and (for `hook`/`gate`) at ~100% adherence vs prose's ~70% (the tier-register's own
numbers). So the migration is not "delete docs" — it is **moving from *always-loaded, sometimes-relevant*
to *just-in-time, always-relevant.***

The organizing question for each always-loaded item is **two-axis**:

1. **Is it a CONSTRAINT ("never/always do X") or CONTEXT ("when doing X, here's how / here's the doc")?**
2. **Is its trigger MECHANICALLY DETECTABLE** (a tool call / file path / command pattern), **or does it
   require JUDGMENT** (no observable trigger)?

These four quadrants route to four different homes:

| | Mechanically detectable | Requires judgment |
|---|---|---|
| **Constraint** | **→ PreToolUse blocking hook or gate.** Delete the prose; keep at most a 1-line pointer. (e.g. destructive-git → `bash-guard`; "head never touches Lucene" → ArchUnit.) Adherence 70→100%. | **→ stays prose, but DELIVER just-in-time** at the moment of the closest observable action, not always-loaded. (e.g. "explore before implementing" pushed on `Write` of a new file.) |
| **Context** | **→ PostToolUse hint / skill, keyed to the path.** (e.g. edit `*Routes.java` → push the "add a REST endpoint" recipe; edit `SSOT/catalogs` → `ssot-hint`.) | **→ on-demand skill** the agent loads when it recognizes the domain. (Already done: `/search-quality`, `/dev-stack`, …) |

**The keep-always-loaded residue** is then small and principled: only what must shape the agent's **plan
before its first tool call** — because a hook fires *after* the action, too late to prevent the wrong plan.
That residue is essentially the **six Hard Invariants + orientation pointers + the worktree/branch safety
basics**. Everything else has a just-in-time home.

## What infrastructure already exists (build on it, don't fork — `explore-before-implementing`)

The repo already has the full delivery substrate; the migration is mostly *extending* it, not building new:

- **PreToolUse blocking hooks** — `bash-guard.mjs` (git/sleep/bare-grep). The model for "constraint →
  block."
- **PostToolUse hint hooks** — `ssot-hint`, `ui-shot-hint`, `governance-hint`, `docs-regen-hint`,
  `lockfile-hint`, `test-edit-hint`, `seam-hint`, `stress-test-hint`. The model for "context → push on
  edit." **`seam-hint` already proves judgment-constraint delivery works**: on `Write` of a new pure-logic
  Java class it asks "is this a law-bearing seam to register?" — exactly the "explore/justify at the moment
  of the action" pattern.
- **PreToolUse consult-push** — `consult-doc-hint` pushes the ONE governing decision-doc when you edit a
  governed region, driven by `GOVERNED_REGIONS` (`scripts/agent-analytics/lib/governed-regions.mjs`).
  **This is the keystone mechanism** and 616 §7.2's headline: it currently covers **1 region (shell-v0) /
  2 docs**, while **46 high-value canonical docs are read 0× and wired nowhere.**
- **Stop hooks** — `maintain-doc-hint` blocks turn-end if you edited a governed region without updating its
  doc. The model for "exit-side enforcement."
- **Skills** — 15 on-demand bundles; the model for "deep domain context, loaded when relevant."
- **Discipline gates + ArchUnit + lint** — the CI-side ~100% tier.
- **Self-describing tool responses** — the 606 dev-stack tools return `recommendedAction`, making the prose
  that explains them redundant. A general lever: *push the guidance into the tool's own output.*
- **`settings.json` permission rules** — 616 §6.2: `Agent(model:…)` can enforce subagent-model policy that
  is currently prose-only in `context-efficiency.md`.

## Concrete routing of the always-loaded content

| Always-loaded item | Quadrant | Target infrastructure | Net |
|---|---|---|---|
| Pre-merge `check-*.mjs` list (~40 ln) | Context / detectable | Extend `governance-hint` (or a new `ui-governance-hint`) to surface the relevant gate set on `ui-web/**` edits; **generate** the canonical list from `governance/registry.v1.json` so it can't drift | ~40 ln → ~1-line pointer; delivered at 100% relevance |
| `common-workflows.md` recipes (~120 ln) | Context / detectable | PostToolUse path-keyed push (edit `*Routes.java` → REST recipe; `*.proto` → gRPC recipe) or fold into `/api-record`, `/module-arch` | always-loaded → just-in-time |
| `tier-register.md` (~150 ln) | Context / detectable | `consult-doc-hint` on edit of `CLAUDE.md` / `.claude/rules/**`; gate already enforces its integrity at CI | always-loaded → push-on-rule-edit |
| branch-safety "Shared Dev Stack" (~60 ln) | Context / detectable | `/dev-stack` skill + the tool's `recommendedAction` | drop the always-loaded copy |
| `slice-execution.md` quick-ref (~96 ln) | Context / judgment-ish | Skill loaded when picking up a slice; or push on open of a slice-structured tempdoc | always-loaded → on-demand |
| `verify-worktree-base` (#35, new) | Constraint / detectable | A `WorktreeCreate`/post-enter hook that runs the base-assertion automatically (618 §1 floated exactly this) | prose 70% → hook ~100% |
| `after-compaction-verify` | Constraint / detectable | `compact-restore` (SessionStart) already runs post-compaction; have it print `pwd` + `git branch` | prose → automatic |
| "Explore before implementing" | Constraint / judgment | Generalize `seam-hint`: a `Write`-of-new-file PreToolUse nudge "did you check for an existing helper?" | stays prose but delivered at the moment |
| Subagent-model guidance (`context-efficiency`) | Constraint / detectable | `Agent(model:…)` permission rule in `settings.json` (616 §6.2) | prose → config-enforced |
| The 6 Hard Invariants | Constraint / mixed | **KEEP always-loaded** (plan-shaping), but most already have an `archunit`/`lint` backstop — the prose is the *early-warning*, the gate is the guarantee | unchanged |

## The limits — when NOT to move to a hook (name the failure modes)

A migration plan that ignores these will regress:

1. **Subagents don't inherit hooks** (`subagents-no-inheritance`; 616:253). Moving a rule *entirely* into a
   PreToolUse hook makes it **invisible to subagents** — the hook tier is ~100% for the main session but
   **0%** for subagents. So a constraint that subagents must also honor (e.g. destructive-git discipline)
   cannot rely on the hook alone; it still needs the **gate** (CI catches at merge) or the **inline brief**.
   Net: hooks are a main-session delivery upgrade, not a universal one.
2. **Hint fatigue is the same dilution, relocated.** Too many PostToolUse pushes and agents tune them out —
   re-creating the always-loaded problem one layer down. 616 §7.2 measured **0 block/deny events in 8.6k**,
   so there is headroom *now*, but each new hint spends it. Budget the hint surface like context.
3. **`additionalContext` from SessionStart is unreliable** (`agent-lessons.md`) — you cannot simply relocate
   always-loaded prose into a SessionStart injection and call it delivered.
4. **A hook fires *after* the tool call.** Anything that must shape the **plan** (the Hard Invariants, "is
   this the right module at all") is too late as a hook and must stay always-loaded. This is the principled
   floor on how small the always-loaded set can get.
5. **Hooks are code; prose is cheap.** A hint hook needs a handler + a path filter + a test; the repo's
   shared `hook-base.mjs` lowers this cost but it is non-zero. Migrate the *high-frequency* items first
   (618's frequency × cost discipline); leave rarely-triggered prose as prose.
6. **False-positive blocks ARE friction.** A blocking hook for a judgment-y constraint (e.g. "explore
   first") would mis-fire constantly; judgment constraints get *non-blocking nudges*, never blocks.

Part II established *that* content should move and *where each piece goes*. Part III settles the
*long-term design* — and the central finding of the investigation is that **none of it is new
machinery.**

---

# Part III — Long-term design

## The one diagnosis under the tempdoc

Strip Parts I and II to their load-bearing cause and every symptom collapses into one sentence:

> **The always-loaded agent-doc layer is the single substrate in this codebase still exempt from the
> three governance disciplines the rest of the codebase already runs under.**

Everywhere *else*, the codebase enforces three seams (verified present, not theorized):

- **Projection, not fork** — a fact with a canonical home is *generated/derived*, never hand-duplicated
  (the representation-drift principle, tempdoc 553). Live instances: `docs/llms.txt`,
  `docs/reference/governance-state.md`, `runtime-config-matrix`, the wire-schema-type codegen — each a
  doc/artifact *projected* from an authority inside a `generated:start/end` fence with a regen + `--check`
  companion.
- **Region-keyed delivery** — context is delivered *when its region is touched*, not dumped globally
  (`GOVERNED_REGIONS` → `consult-doc-hint`/`maintain-doc-hint`, tempdoc 579).
- **Ratcheted budgets** — a number that should shrink is enforced to only shrink, never honor-system
  (`class-size`, `ui-bundle-budget`, `npm-audit-ratchet`, `exception-count` baselines).

The always-loaded docs do **none** of these. They hand-**fork** the gate list / skill list / large-file
list / tier-register "Resolves to" (→ the Part I.A drift + Part I.C bloat-drift); they always-load
**region-specific** content instead of delivering it (→ Part II's relocation list); and their budget is
**honor-system prose** (the new `<!-- budget -->` markers are toothless comments → Part I.E). The Part-I
defects are not incidental — they are the *predicted* failure signature of a substrate outside these
seams, exactly as 553 predicts drift for any forked representation.

**So the correct long-term design is not a doc-management framework. It is: bring the exempt substrate
under the regime that already exists.** The scope is "subject one layer to three established seams," and
the size of the change is therefore small by *outcome*, not by target — almost entirely *extend*, with one
*fold-in*.

## The design: three conforming moves that jointly enforce one boundary

### Move 1 — Projection, not fork (conform to the generated-fence doc family)

The machine-derivable content in the always-loaded files becomes a **generated projection** inside a
`generated:start/end` fence, each with a regen script + a `--check` companion — structurally identical to
how `llms.txt` and `governance-state.md` already work. Authorities already exist:

- Pre-merge checks list ⟵ `governance/registry.v1.json` (likely *already* projected by
  `governance-state.md` — extend, don't author).
- Skills index ⟵ the skills directory (`skills-sync.mjs` already exists).
- "Known large files" ⟵ a `wc -l` threshold over the tree.
- tier-register "Resolves to" staleness ⟵ already half-checked by the prose-tier-register marker
  resolver; extend it to flag a *stale* (not just unresolvable) entry.

A generated list **cannot drift** — this dissolves Part-I.A #1/#3/#5 and the Part-I.C bloat-drift *by
construction*, the same guarantee projection buys everywhere else.

### Move 2 — Region-keyed delivery (promote the map the code already says to promote)

Promote `GOVERNED_REGIONS` to `governance/consult-register.v1.json` — the promotion its own header comment
already prescribes ("promote … only if it grows"). The register maps **code region → {governing docs,
just-in-time recipe, relevant gate-set}**, consumed by the *existing* `consult-doc-hint` (Consult) +
`maintain-doc-hint` (Maintain), and extended to `governance-hint` (push the gate-set on an edit). The
relocated Part-I.C content (common-workflows recipes, dev-stack guidance, slice quick-ref) lands here as
delivery rows. This is the **same register** that closes 616 §7.2's 46-docs-read-0× gap — so the audit's
"relocate the bloat" and 616's "deliver the unread docs" are one register, approached from two ends.

### Move 3 — Budget ratchet, folded into the proven meta-gate (conform to ratchet + 582 R3)

The `<!-- budget -->` markers become teeth via a per-file token-budget baseline
(`always-loaded-budget.v1.json`) enforced **by extending the existing `prose-tier-register` gate** — *not*
a new gate. This is load-bearing: 582 R3 says **freeze the meta-tier count** (no net-new gate-governing
gates beyond `exception-count` + `prose-tier-register`), and 582 R4 says finish wiring rather than author
anew. Folding the budget check into the already-wired prose-tier-register honors both. `--rebalance`
shrinks the baseline as Moves 1–2 migrate content out — the budget becomes a one-way ratchet down, like
every other budget in the repo.

### The boundary the three moves jointly defend (the actual output)

Together they don't just *describe* a tiering — they make it **hold by construction**. The design output is
a **residence rule for agent knowledge**, enforced rather than exhorted:

- **Always-loaded** = only what must shape the agent's *plan before its first tool call* (the Hard
  Invariants + worktree/branch safety + a *generated* index of what the delivery layer covers). A hook
  fires *after* the tool call, so plan-shaping content is the principled, non-shrinkable floor.
- **Just-in-time (consult-register → hooks)** = region/action-triggered constraints and context.
- **On-demand (skills) + CI (gates/archunit/lint)** = deep procedure + the ~100% enforcement floor.

Move 1 stops the always-loaded files from re-forking; Move 2 gives evicted content a real home; Move 3
stops the budget regrowing. Remove any one and a Part-I symptom returns — which is the scope argument:
three is *matched*, not padded.

## Scope discipline (explicit over/under-build check)

- **Not over-built:** zero new frameworks, zero new meta-gates, zero new delivery engines. Move 1 adds
  members to an existing generator family; Move 2 promotes a map the code already anticipates; Move 3
  folds a check into an existing gate. I explicitly do **not** propose a general "all docs must project"
  gate (Part IV explains why that would be premature).
- **Not under-built:** Part I proves drift (needs Move 1), 616 proves delivery failure (needs Move 2),
  Part I.E proves the budget is undefended (needs Move 3). Each move retires a *documented, present*
  symptom; none is speculative.

---

# Part IV — Reach (recognize the principle; don't build the general structure)

## Is this design an instance of an existing seam? — Yes, three.

The design is not novel shape; it is the **projection-not-fork** seam (553), the **region-keyed-delivery**
seam (579/616), and the **ratchet** seam (530) applied to a layer that had escaped all three. Conforming is
the whole point: the agent-doc layer becomes *subject to the same governance as the code it documents.*
There is no parallel mechanism to justify.

## Does it reveal a principle with reach beyond this problem? — Yes; named and scoped, not built.

**Principle (doc-as-projection):** *A document that restates a fact owned by a machine-readable authority
is a fork, and forks drift; documentation must **project from** authority, not **duplicate** it.* This is
553's representation-drift principle with one widened claim: its scope includes **prose documentation**, not
only code records/types/schemas. The codebase already applies it to exactly three docs (llms.txt,
governance-state, runtime-config-matrix) — proving the pattern works — but treats that as incidental rather
than as a principle with a name.

**Where else it already applies (candidate scope), and existing violations:**

- `docs/reference/api-contract-map.md` — restates routes that live in `*Routes.java`. **Fork; drifts**
  (this is the same class as Part-I.A #3, the "Verification Workflow" dangling ref).
- CLAUDE.md "## Skills" — restates the skills directory. **Fork** (Part I.D).
- The "known large files" list — restates the filesystem. **Fork** (Part I.A #5).
- The tier-register "Resolves to" column — restates gate/hook existence. **Partial fork** the
  prose-tier-register gate already half-guards.
- Any hand-maintained "list of X" in canonical docs where X has a machine home (module lists; the env-var
  table is *already* projected by runtime-config-matrix — the positive proof).

**Deliberately not built now:** a general "every doc list must be a generated projection" gate. The present
problem only requires projecting the *always-loaded* forks (Move 1). A repo-wide doc-projection gate is
exactly the speculative, evidence-free meta-gate 582 R3 warns against. Recording the principle + its scope,
and deferring the general structure until a second concrete instance demands it, is the
"recognize ≠ build" discipline (and the `structural-defects-no-repeat` counterpart: one documented drift
instance proves the *bug-class* — but the *general gate* is abstraction, warranted only when a second
instance needs it).

**A second, orthogonal axis worth recording (don't build):** the tier-register today tracks an
*enforcement* tier (prose/hook/gate/…). This design surfaces a distinct **residence** tier (always-loaded
/ just-in-time / on-demand) — *where* knowledge lives, orthogonal to *how strongly* it's enforced. Bloat is
a residence-tier mismatch. Candidate generalization: every tier-register row could one day carry a
residence tier beside its enforcement tier. **Note only** — there is no present need to model residence for
rules outside the always-loaded set.

---

## Prioritized recommendations (frequency × cost, per 618; each maps to a Move)

| # | Item | Move | Tier | Cost | Leverage |
|---|---|---|---|---|---|
| 1 | Part I.A correctness defects (#1-#5) — they actively mis-teach | — | A | ~30 min | High — a wrong rule is worse than no rule; do regardless of the design |
| 2 | Project the Pre-merge list (+ skills index, large-file list) into `generated` fences from their authorities (extend `governance-state.md` / `skills-sync` family) | 1 | A | small | **Highest** — kills the largest bloat *and* its drift by construction |
| 3 | Promote `GOVERNED_REGIONS` → `governance/consult-register.v1.json`; map the Part-I.C relocations + 616's read-0× docs | 2 | A | medium | **Highest** — the one delivery substrate for everything evicted |
| 4 | Fold a per-file token-budget ratchet into `prose-tier-register` (no new gate — 582 R3) | 3 | A | medium | High — defends the budget by construction |
| 5 | Evict Part-I.C blocks to skills + the consult-register (common-workflows, tier-register, dev-stack, slice quick-ref) | 1+2 | A | medium | High — ~470 ln off every session |
| 6 | `verify-worktree-base` + `after-compaction-verify` → hooks | (JIT) | A/B | small | Medium — 70→100% on two safety rules |
| 7 | De-dup Part I.D (Skills section, 3× git list, worktree table) | 1 | A | small | Medium |

## Implementation log

- **2026-06-20 — Part I.A correctness fixes landed (rec #1).** The five wrong-rule defects were corrected
  in place; `node scripts/governance/run.mjs --gate prose-tier-register --mode gate` → **pass** (0 findings):
  - #1 `tier-register.md` header — rewritten from "the gate is a follow-up / future" to "now live" (the
    `prose-tier-register` gate exists), resolving the header↔footer self-contradiction.
  - #2 `tier-register.md` row 18 — the false "`bash-guard` blocks destructive git in subagent shells,
    partial coverage" replaced with "no subagent coverage — parent hooks don't fire in subagents."
  - #3 `agent-guide.md` ×2 (lines 108, 390) — the dangling *"Verification Workflow step 5"* refs repointed
    to *"Quick Commands → Pre-merge script checks."* (Body-only cross-reference fix; does not change the
    title/path/frontmatter that `llms.txt` projects, so no regen required — deliberately not running a
    global regen on shared `main`.)
  - #4 `branch-safety.md` — the `git checkout` blocked-row now records the single-file `git checkout --
    <path>` carve-out (tempdoc 520 P0c), matching `hooks-reference.md`.
  - #5 `context-efficiency.md` — `UnifiedChatView.ts` (~5,400 ln, the largest) added to the "known large
    files" list.
- **2026-06-20 — Move 2 (delivery substrate) landed + verified (rec #3).**
  - Created `governance/consult-register.v1.json` — the promotion `governed-regions.mjs` anticipated.
  - Refactored `scripts/agent-analytics/lib/governed-regions.mjs` into a *loader* that compiles each
    register row's declarative `pathIncludes` matcher into the `{region, docs, match}` shape the two hook
    consumers expect, so `consult-doc-hint.mjs` / `maintain-doc-hint.mjs` are **unchanged**. Fail-open on a
    bad/absent register (a hook must never crash).
  - Added a `maintain` opt-in flag (one-line guard in `maintain-doc-hint.mjs`): `shell-v0` keeps its
    blocking behavior; **consult-only DELIVERY rows never block**. Seeded 3 delivery rows for 616's
    read-0× architecture docs (`app-agent`→22, `adapters-lucene`→18, `app-inference`/`ort-common`→24).
  - Verified (tier-1, crafted hook stdin): consult delivers the right doc per region; non-governed paths
    emit nothing; maintain flags correct (only `shell-v0` blocks); register valid JSON.
- **2026-06-20 — Move 1 (partial): Skills section evicted.** CLAUDE.md "## Skills" per-skill descriptions
  removed as a fork of the harness-injected skill list (a *pointer* beats generation when the harness
  already delivers the list every session); kept the unique "registers update-before-closing" rule.
  `prose-tier-register` gate re-run → **pass**.
- **2026-06-20 — Move 1 (Pre-merge block) landed + verified.** CLAUDE.md's ~41-line verbose per-gate
  block compressed to a ~22-line `Edited subject → Check(s)` table — every gate→trigger mapping preserved
  (incl. `check-ui-step-coverage`), only the paragraphs dropped (their detail lives in each script header +
  `governance/registry.v1.json`). Chose **compression over full generation** because the authority is
  incomplete — the ui-web `check-*.mjs` aren't registry gates, so a clean generated projection isn't
  available without first registering them (noted as the path to the drift-proof form). `prose-tier-register`
  gate → **pass**. This is the single biggest lever: CLAUDE.md 43,875 → 24,604 B.
- **2026-06-20 — Move 3 (budget ratchet) landed + verified.** Built as a standalone CI lint (same tier as
  the doc-gen `--check` family — **not** a new kernel meta-gate, so 582 R3-clean):
  - `scripts/ci/always-loaded-budget.v1.json` — per-file byte ceilings = current sizes; ratchets DOWN only.
  - `scripts/ci/check-always-loaded-budget.mjs` — `--check` fails on over-ceiling; `--rebalance` shrinks
    ceilings as content migrates out. **Verified:** passes at current sizes; a −200 B ceiling demo correctly
    exits 1 with an `OVER` report (teeth confirmed).
  - Budget markers added to the 3 unmarked always-loaded files (CLAUDE.md, hooks-reference, tier-register).
  - The R3-clean *wiring* (fold the same check into the `prose-tier-register` enforcer, vs the current
    standalone lint) is noted in the baseline `$comment` as a follow-up once the doc set stabilizes — left
    standalone now to avoid surgery on the shared enforcer mid-session.
- **Net size this session: 100,832 → 81,789 B (~25,208 → ~20,447 tok), ≈ −19%**, gate green throughout,
  with the ratchet now preventing regrowth.
- **STILL NOT done — `common-workflows.md` relocation** (~8 KB / 121 ln). Blast radius into 3 canonical docs
  (0038/0039/frontend-kernel-05) + the scanner's `EXCLUDED_RULE_FILES` + the tier-register mention; a
  careful pass with docs-regen, deferred rather than rushed on a co-edited `main`.
- **Constraint recorded: `tier-register.md` cannot be relocated** out of `.claude/rules/` — the gate's
  `baseline.path` pins it there (`registry.v1.json`). Stays always-loaded.
- **2026-06-20 — Part V enforcement-tier conversions landed + verified (the 4 worthwhile ones).** Plan-mode
  approved; each conforms to an existing seam, none is a new kernel gate (582 R3).
  1. **`subagent-guide` invariant de-fork (bug fix).** New `lib/hard-invariants.mjs` live-reads CLAUDE.md's
     `## Hard Invariants`; `subagent-guide.mjs` projects from it (was hard-forked at 4-of-6). Verified: the
     hook now emits **all 6** incl. Lit + language-agnostic; also fixed a stale `BrainView.tsx` (retired-React)
     large-file ref. Doc-accuracy: `agent-lessons` #subagents-no-inheritance + `hooks-reference` now record the
     auto-injected baseline brief (the docs had understated it).
  2. **`#22 after-compaction-verify` → `compact-restore`.** `buildContext()` now emits the worktree dir +
     branch (mechanizing the prose check). Verified: block renders; existing `compact-restore.test.mjs` passes.
  3. **`#32 tempdocs-are-dated-history` → `tempdoc-age-hint.mjs`** (new PostToolUse Read hook, wired in
     `settings.local.json`). Verified: age-stamps a tempdoc read with date/status + newer-count; silent on
     non-tempdocs and on Edit; JSON valid.
  4. **`#6 fix-root-causes` (annotation subset) → suppression ratchet.** New `check-suppression-ratchet.mjs`
     + baseline; counts line-start `@Disabled` + own-line `// noinspection`, **excludes** `@DisabledOnOs`/`@DisabledIf`
     (legit conditional) and Javadoc/string refs. Verified: baseline = 3 real annotations; bare `@Disabled`
     fails, `@DisabledOnOs` passes. (`@SuppressWarnings` excluded by design — 411 legit uses.)
- **All checks green after Part V:** `prose-tier-register` pass, `always-loaded-budget` pass (re-baselined to
  the final 82,432 B / ~20,608 tok — still ~18% under session start), `suppression-ratchet` pass.
- **Deferred (Part V.2 Tier-2 partials):** #7 verify-your-work Stop-proxy, #13 tempdoc checkbox lint,
  #24/#26/#35 guards — lower value, recorded not built. **Judgment rules stay prose. Retired gates
  (#29/#30/#31) not re-mechanized.**

## Open questions for the user (before applying anything)

1. **Scope of *this* tempdoc:** land the Part I.A correctness fixes here (cheap, low-risk), and hand the
   three-Move design (recs #2-#5) to a dedicated follow-up slice? Or charter the whole thing here?
2. **Residence floor:** is the Part-III residence rule's tier-1 (Hard Invariants + safety basics +
   *generated* hook/skill index, ~⅓ of today) the right always-loaded floor, or do you want more/less hot?
3. **Projection dependency:** OK to make the Pre-merge / skills / large-file lists *generated* projections
   (Move 1), accepting a codegen + `--check` dependency, as `llms.txt`/`governance-state.md` already do?
4. **Budget teeth:** fold the token-budget ratchet into `prose-tier-register` (Move 3, per 582 R3) — turning
   the `<!-- budget -->` markers you started adding into a gate?
5. **The issues you found:** fold yours in here so the audit is the union, not just my pass.

---

# Part V — Enforcement-tier conversion: which prose rules can become infrastructure

Parts I–IV addressed *where* content lives and *how much*. This part addresses the orthogonal **enforcement
axis**: which `prose-only` rules (~70% adherence, per the tier-register) have a deterministic trigger that
could lift them to `hook`/`gate`/`lint`/`archunit` (~100%). This is the governance-axis pass 616 §7.1.D
named. It is held against two cautions from the repo's own evidence: **616 §7.2** ("leverage is in delivery,
not more enforcement; 0 block events in 8.6k; few low-hanging upgrades") and **582 R1/R3** (a gate must earn
its maintenance; do not author speculative governance / new kernel meta-gates). So the bar is the *few*
high-value conversions, not a hook for every rule.

## V.0 — The reframe: infrastructure already exists that the docs don't reflect — and that forks them

`scripts/agent-analytics/hooks/subagent-guide.mjs` is a **`SubagentStart` hook** (wired in
`.claude/settings.local.json`) that already injects a project-aware brief into every subagent — including a
copy of the Hard Invariants. Three consequences, all verified this session:

1. **The docs understate existing mechanization.** Rules #18 (`delegating-to-subagents`) and #27
   (`subagents-no-inheritance`) say "subagents inherit nothing, brief inline." True for CLAUDE.md/rules/parent
   hooks — but a *curated baseline* (invariants, platform, key patterns) **does** reach subagents via this
   hook. The rule should say "the baseline is auto-injected by `subagent-guide`; brief the *task-specific*
   part."
2. **The hook is undocumented** — `hooks-reference.md` does not mention `subagent-guide` (nor `dispatch`,
   `export-session-env`). Doc-completeness gap.
3. **The hook hand-forks the invariants and has already drifted.** It carries invariants 1–4 but **not #5
   (frontend-is-Lit) or #6 (language-agnostic)** — a subagent doing frontend work is never told "Lit, not
   React." This is the Part IV **doc-as-projection** violation, now *inside infrastructure*: a hook that
   embeds doc content forks it and drifts. **Fix: generate `subagent-guide`'s invariant block from CLAUDE.md's
   Hard Invariants** (single authority), and document the hook.

## V.1 — Tier 1: genuine gaps worth converting (deterministic trigger + real catch value + low cost)

1. **#22 `after-compaction-verify` → extend `compact-restore`.** *Verified gap*: `compact-restore.mjs` fires on
   SessionStart/PostCompact but does **not** emit `pwd`/`git branch`. Have it print them (and a worktree-base
   assertion). Near-zero cost, the hook already fires on the right event, and it **fully retires the prose
   rule** (the agent no longer has to remember to check). Best candidate.
2. **#6 `fix-root-causes` (the annotation anti-patterns) → a diff lint.** *Verified gap*: no
   `@Disabled`/`@SuppressWarnings`/`// noinspection` check exists. A NEW such annotation in a diff without a
   justifying comment → fail (a shrinking ratchet, like the others). Deterministic and catches the exact
   "silence the failure" move the rule forbids. The *semantic* anti-patterns (`assertEquals→assertNotNull`,
   `catch(IOException)→catch(Exception)`, deleting failing code) stay prose — harder to detect reliably — but
   the three annotation forms are clean.
3. **#32 `tempdocs-are-dated-history` → a PostToolUse Read-of-tempdoc age-stamp hook.** The tier-register
   *itself* flags this ("could upgrade to `hook` if a PostToolUse tempdoc-age-stamp hook lands"). Trigger: a
   `Read` whose path is under `docs/tempdocs/`; inject "dated YYYY-MM-DD; N newer tempdocs exist; verify
   against `main`." Real value — 618 documents the stale-tempdoc trap.

## V.2 — Tier 2: partial mechanization (catch a proxy / deliver at the moment, can't fully verify)

| Rule | Mechanism | Why partial |
|---|---|---|
| #18/#27 subagent brief | `subagent-guide` **already exists** — de-fork its invariants + document it (V.0) | baseline auto-injected; task-specific brief stays judgment |
| #13 `tempdoc-is-your-contract` | extend `scripts/docs/tempdoc-status-check.mjs` to fail when `status: complete` has unchecked `- [ ]` | catches checkbox completeness, not "did you really implement it" |
| #7 `verify-your-work` | Stop-hook proxy: edited Java/TS this session, ran no `gradlew`/`npm` build | proxy for "did you verify"; risk of false nudges |
| #35 `verify-worktree-base` | a `WorktreeCreate` hook warning on `origin/main ≠ local main` (618 §1) | config (`baseRef:head`) is done; hook is belt-and-suspenders; can't know task intent |
| #24 `never-delete-untracked-in-main` | extend `bash-guard` to block `rm`/`Remove-Item` of untracked-in-main | can't know "you didn't create it"; rare |
| #26 `pre-merge-gradle-build` | PreToolUse on `git merge` (extend `bash-guard`) reminder/guard | can't force a green build; reminder only |

## V.3 — Stays prose (genuine judgment, or deliberately demechanized — do NOT convert)

- **Judgment, no deterministic trigger:** #11 `interrogate-results`, #12 `structural-defects-no-repeat`,
  #17 `ask-when-uncertain`, #10 `critical-analysis-pass`, #4 `verify-dont-guess`, #8 `use-every-verification-tier`,
  #14 `stay-focused`, #15 `log-pre-existing-issues`. (#5 `explore-before-implementing` is partly delivered by
  `seam-hint` on new-class Write — leave at that.)
- **Deliberately retired — do not re-mechanize:** #29 `bidirectional-pass`, #30 `independent-reviewer-required`,
  #31 `ux-audit-closure` were gate-enforced then **retired** (tempdoc 563). Re-adding gates reverses an explicit
  decision and violates 582 R1/R3. They are honor-system *by choice*.

## V.4 — Contents (not rules) already redundant with infrastructure → trim to pointer

Several **Common Pitfalls** rows duplicate a hook/gate that already delivers the same reminder:
- "Classpath catalog drift" — the `ssot-catalog-sync` **gate** (~100%) **and** `ssot-hint` already cover it;
  the always-loaded prose is triply-redundant.
- "Stale lockfiles" — `lockfile-hint`. "Schema/fixture drift" — `updateSchemas` (+ the contract gates).
Trim these to pointers; the infrastructure is the authority and already fires at the moment of the edit.

## V.5 — Reconciliation + the principle this reveals

**Agrees with 616 §7.2.** Of ~25 `prose-only` rules, only **~3** (V.1) are clean, high-value conversions; the
rest are partial-proxy or genuine judgment. So this is *not* a governance land-grab — it confirms 616's
"few low-hanging upgrades; leverage is in delivery." The Tier-1 three are worth doing precisely because each
has a deterministic trigger AND a documented failure it would catch.

**New reach for the Part IV `doc-as-projection` principle.** V.0 shows the principle applies not only to *docs*
that restate a machine-owned fact, but to **infrastructure that embeds doc content**: `subagent-guide` hand-copies
the invariants and drifted (4 of 6). Candidate scope: any hook/gate/codegen that inlines a rule, invariant, or
catalog that has a canonical home (the `subagent-guide` invariants; any future hint that pastes rule text).
**Recorded, not built:** do not author a general "infrastructure must project its embedded doc content" gate —
the present problem requires only de-forking the one drifted instance (`subagent-guide`). One concrete instance
proves the bug-class; the general gate waits for a second (`structural-defects-no-repeat` applied to itself).

---

# Part VI — Post-implementation critical-analysis pass (2026-06-20)

Per the bidirectional-pass discipline (`slice-execution.md` — post-impl critical-analysis), a second walk
over what Parts I–V actually *shipped*, with the "what would catch what the verification missed?" lens. The
headline is uncomfortable and worth stating plainly: **Part V's *enforcement* moves outran its
*documentation* moves.** Three mechanisms landed; the two artifacts whose whole job is to record "which
mechanism enforces which rule" (`tier-register.md`, `hooks-reference.md`) were not updated to cite them. This
reproduces, *inside 620's own deliverable*, the doc-as-projection drift 620 set out to kill — the same
self-referential irony as V.0's `subagent-guide` finding, one layer up.

## VI.A — Self-inflicted: mechanisms shipped, register/catalog left stale (fix-tier A, immediate closure) [T1]

- **tier-register row 32 `tempdocs-are-dated-history`** still reads `prose-only` with the note *"could upgrade
  to `hook` if a PostToolUse tempdoc-age-stamp hook lands."* That hook **landed this session**
  (`scripts/agent-analytics/hooks/tempdoc-age-hint.mjs`, wired `settings.local.json:186-189`, observed firing
  live on every Read of this file). The row's own predicted condition is now true and the row was not touched.
- **tier-register row 6 `fix-root-causes-not-symptoms`** — catch column still *"Code review; ArchUnit cannot
  detect…"*, omitting `check-suppression-ratchet.mjs`, which now mechanizes the `@Disabled` / `// noinspection`
  subset.
- **tier-register row 22 `after-compaction-verify`** — catch column still *"Agent discipline post-compaction"*,
  omitting that `compact-restore.mjs` now writes the worktree+branch block automatically.
- **`hooks-reference.md` omits `tempdoc-age-hint` entirely** (grep-confirmed); the suppression ratchet is
  documented in no always-loaded file; and `hooks-reference.md:37`'s *description* of `compact-restore` still
  says *"After compaction: Verify your worktree and branch: pwd and git branch"* — i.e. the catalog's account
  of the hook doesn't mention the block the hook now emits.
- **tier-register row 27 `subagents-no-inheritance`** catch column doesn't mention the `subagent-guide`
  baseline injection (the prose body in `agent-lessons.md` was updated; the register row wasn't).

**The meta-point (keystone of this pass):** the `prose-tier-register` gate enforces tier-**change** discipline
(a row's tier can't move without a `.changesets/` entry) but has **no** check for *"a mechanism now exists that
the row should cite."* So the register can silently *under*-describe reality — and Part V demonstrated exactly
that failure mode in the same work that argued against fork/drift. Note these are **not** tier *changes*
requiring a changeset (a delivery hint arguably doesn't lift a row off `prose-only` — see VI.B); the correct
fix is updating the **"Catches violations via"** columns + notes, not the tier values.

## VI.B — Conceptual gap the work exposed: the tier vocabulary has no "delivery" rung [T3]

The five tiers are `gate / hook / archunit / lint` (~100%) vs `prose-only` (~70%), where `hook` ≡ ~100%. But
the hooks Part V built — `tempdoc-age-hint`, the `compact-restore` block (and the pre-existing
`consult-doc-hint` / `ssot-hint` / `seam-hint`) — are **delivery hints**: they raise a prose rule's salience at
its moment of relevance, but the agent can still ignore them (~85%, *between* the two existing rungs). 616
§7.2's own thesis is *"leverage is delivery, not enforcement"* — yet the register has no rung that can record
*"prose rule + delivery hook."* So rows 22/32 aren't merely stale; the register **structurally cannot classify
them honestly**. Candidate fix: a sixth tier `hook-hint` / `delivery` (~85%), distinct from blocking `hook`.
This touches the gate's tier enum and every affected row → it is a **design decision needing its own changeset
+ a line in the discipline-gate-kernel doc**. Recorded, not built — but it is the cleanest resolution of VI.A,
because it gives the stale rows a tier they can honestly move *to*.

## VI.C — Two size authorities now disagree (minor, self-inflicted by Move 3) [T1]

`agent-lessons.md:1` carries an inline `<!-- budget: ~1500 tokens -->` marker. The enforced ceiling in
`always-loaded-budget.v1.json:7` is **6,822 B ≈ 1,705 tok** (by the json's own `bytes/4` convention). The file
is ~14% over its own inline marker yet passes the ratchet. Two authorities for one fact (file size), already
drifted — Move 3 added the byte ratchet without reconciling the pre-existing inline token markers (a mini
doc-as-projection violation). Fix: point the inline marker at the ratchet (single authority) or drop the number.

## VI.D — The bigger, mostly pre-existing finding: whole *files* fail CLAUDE.md's own inclusion gate [T3]

Part I.C named relocation candidates, but the trim that *shipped* attacked bloat **within** files. It never
acted on the prior question: which whole files deserve an always-loaded slot at all? Against the budget
ceilings:

| File | Bytes | Universal, or task-specific? |
|---|---|---|
| `tier-register.md` | 13,207 (2nd largest) | Meta-governance reference; needed only when editing rules |
| `hooks-reference.md` | 11,043 | Each hook re-explains itself when it fires |
| `common-workflows.md` | 8,112 | Only when doing one of those specific workflows |
| `slice-execution.md` | 4,582 | Only for multi-phase slice work |

≈ **31% of the 82 KB budget** is reference-on-demand, loaded every session regardless of task. Caveat already
recorded in the Implementation log: `tier-register.md` **cannot** leave `.claude/rules/` (the gate's
`baseline.path` pins it). So the actionable relocation frontier is `common-workflows` + `slice-execution` +
the dev-stack subsection — i.e. **rec #5 / Move 1+2, still deferred**. This pass re-confirms rec #5 is the
largest remaining lever, and that the Part-IV **residence-tier** axis (where knowledge lives) — not more
within-file trimming — is the real next step.

## VI.E — A claim Move 1 introduced that needs out-of-repo verification (fix-tier C) [T1 for the negative]

The Skills compression replaced the explicit per-skill trigger descriptions with *"The full skill list with
triggers is auto-injected into your session context every session."* Verified this session: **no project hook
injects skills** (grep of `scripts/agent-analytics/hooks/` — none). The claim therefore rests on a native
Claude Code harness feature (the system-reminder available-skills surfacing). If that native injection carries
skill **names + descriptions but not the triggers** the old section had, the compression traded concrete
routing guidance for an over-claim. Cannot be settled from the repo — **flag to verify** against a fresh
session's actual injected context before trusting the compressed wording.

## VI.F — Residual redundancy not removed [T1]

The worktree-verify instruction now appears in **three** places: `branch-safety.md` rule 4,
`context-efficiency.md` "Worktree Awareness," and the `compact-restore` hook that writes the block. Part V added
the hook without thinning the two prose copies — the redundancy class Part I.D targeted, persisting for this
one fact (consistent with VI.A: mechanism shipped, prose-it-should-have-thinned didn't).

## VI.G — What this pass CLEARS (calibration — not all teardown)

The ~18% trim is real and ratchet-protected; the `subagent-guide` de-fork is a genuine correctness fix (4→6
live-projected, verified); the `consult-register` substrate is a clean projection. The criticism is narrow and
bounded: Part V's enforcement moves outran its documentation moves — a **bookkeeping lag, not a design error**.

## Closure actions (ranked)

| # | Action | From | Cost | Needs changeset? |
|---|---|---|---|---|
| 1 | Reconcile the stale tier-register catch-columns (rows 6/22/27/32) + add `tempdoc-age-hint` to `hooks-reference.md` + thin the duplicated worktree-verify prose | VI.A, VI.F | ~15 min | No (no tier *change*) |
| 2 | Collapse the two size authorities (inline marker → ratchet) | VI.C | one-line | No |
| 3 | Decide on a `delivery`/`hook-hint` tier; if adopted, retier the delivery-backed rows | VI.B | design | **Yes** (tier-change) |
| 4 | Whole-file residence relocation (`common-workflows` + `slice-execution` + dev-stack subsection) | VI.D | medium | n/a (= rec #5) |
| 5 | Verify the skills-auto-injection claim in a fresh session | VI.E | trivial | No |

Items 1–2 are pure closure of this session's own work (no design decision, no changeset) and are the natural
next commit; 3–5 are decisions/larger work for the user to weigh.

## VI closure — landed (2026-06-20)

Closure items 1–2 (plus the gate bug they surfaced) implemented + verified; 3–5 left as decisions per above.

- **VI.A — register/catalog reconciled.** tier-register **catch-columns** updated for rows **6** (now cites
  `check-suppression-ratchet.mjs`), **22** (the `compact-restore` worktree block), **27** (the `subagent-guide`
  baseline injection), **32** (`tempdoc-age-hint.mjs` — "the predicted hook landed"). Tiers stay `prose-only` —
  a delivery hint is not ~100% enforcement (VI.B) — so **no tier change → no changeset**. `hooks-reference.md`
  gains a `tempdoc-age-hint` section, and its `compact-restore` entry now records the worktree block it writes.
- **VI.F — worktree-verify de-duplicated.** `branch-safety.md` rule 4 and `context-efficiency.md` "Worktree
  Awareness" now defer to the `compact-restore` block (branch-safety stays the authoritative safety rule; raw
  `pwd`/`git branch` kept only as the non-compaction fallback).
- **VI.C — size authorities collapsed.** All **8** inline `<!-- budget -->` markers rewritten to name
  `always-loaded-budget.v1.json` as the sole numeric authority (dropped the independent `~N tokens` numbers that
  had already drifted — agent-lessons measured ~1,705 tok against its `~1500` marker).
- **Gate bug surfaced + fixed (it was blocking clean local verification).** Running `prose-tier-register`
  locally flagged an `untagged-sentence` inside the **gitignored, ephemeral** `.claude/rules/compaction-state.md`
  — the substring "always" in *this tempdoc's own filename* (`620-always-loaded-…`) appears in that file's
  modified-files list. The scanner was scanning a generated file it should never scan; added
  `compaction-state.md` to `EXCLUDED_RULE_FILES` (same pattern as the existing four). **CI was never affected**
  (the file is gitignored / absent in a clean checkout) — the fix just stops the local false-positive recurring
  for any agent.
- **Budget re-baselined (deliberate, conspicuous).** The VI.A doc additions (+ some pre-existing `main` drift)
  pushed the set to **85,456 B (~21,364 tok)**; the shrink-only ratchet correctly refused `--rebalance`, so the
  baseline was raised by hand to current sizes (the tool's intended "growth is a manual, reviewed act" path).
  **Honest net:** vs the session-start 100,832 B this is still **≈ −15%** (eased from −18% before this pass) —
  correctness docs were added back, the trade Part I names ("a wrong rule is worse than a missing one"). The
  files that grew (`tier-register`, `hooks-reference`) are themselves VI.D relocation candidates, so the
  long-term move is to **evict** them, not keep trimming words.
- **Verified:** `prose-tier-register` → pass (0 findings); `always-loaded-budget --check` → pass.

---

# Phases 1–8 — remaining-work execution (2026-06-20)

Plan-mode approved; all **BUILD** items implemented + verified. Each conforms to an existing seam
(projection / region-keyed delivery / ratchet) — no new framework, no new kernel meta-gate (582 R1/R3).
**No user-visible feature shipped**, so no browser/dev-stack/local-model validation applies; validation was
gate runs + crafted-stdin hook tests + `--check` regens.

**Phase 1 — consult-register recipe delivery (enabler).** Extended the existing delivery substrate to carry
a just-in-time `recipe` (numbered steps), not only doc-pointers: documented the field in
`consult-register.v1.json`; `governed-regions.mjs` already forwarded it via `...row` (no change needed);
`consult-doc-hint.mjs` now renders it. Verified (crafted stdin): a `.proto` Write surfaces the gRPC recipe;
a shell-v0 Edit still surfaces the doc-pointers (regression intact); a non-governed path stays silent.

**Phase 2 — `common-workflows.md` relocated out of always-loaded (VI.D / rec #5 — the −8 KB lever).** Moved
the full recipes to canonical `docs/reference/contributing/common-workflows.md` (on-demand, llms.txt-indexed);
added 4 path-keyed `recipe` rows to the consult-register (gRPC→proto, REST→ui/api, config→Env/Config/
ResolvedConfigBuilder, agent-tool→registry/operations) so the triggerable procedures deliver just-in-time.
Deleted the `.claude/rules/` copy and updated its three back-references (scanner `EXCLUDED_RULE_FILES`, budget
baseline key, tier-register "operational docs" section); repointed the 3 canonical docs
(0038/0039/frontend-kernel-05) and the CLAUDE.md pitfall ref to the new path; added a CLAUDE.md Pointers
entry. (Also synced the tier-register scope note to include `compaction-state.md`, stale since VI.A.)

**Phase 3 — branch-safety "Shared Dev Stack" → `/dev-stack` (VI.D).** The skill source `mcp-dev-tools.md`
already had the error-code table; added the two missing pieces (the 606 ownership-verdict model + the
`serve-worktree-fe.cjs` live-validate), regenerated the skill, and trimmed the 86-line branch-safety
subsection to a ~9-line pointer keeping only the multi-agent *safety* essentials (phrased trigger-word-free,
since branch-safety is sentence-scanned). branch-safety: 12,917 → 9,499 B.

**Phase 4 — Common-Pitfalls trim + worktree-table de-dup (rec #7 / V.4) + VI.E.** Trimmed the 3 pitfall rows
already enforced by a hook/gate to cite the authority (classpath→`ssot-catalog-sync` gate + `ssot-hint`;
lockfiles→`lockfile-hint`; schema/fixture→`updateSchemas` + `test-edit-hint`); collapsed the CLAUDE.md
"Parallel Agents" worktree table to one prose line (dup of branch-safety). VI.E: reworded the Skills
over-claim ("full list with triggers … every session") to a claim that holds. The git-command "3×" (Part
I.D) was examined and **declined** — the three instances are role-differentiated (normative anchored rule vs
enforcement-mapping table vs hook-catalog), not pure duplication; trimming would cost legibility.

**Phase 5 — Pre-merge drift `--check` validator (rec #2, honest form).** New
`scripts/ci/check-premerge-table.mjs` parses the CLAUDE.md table and asserts every `check-*` script + every
`--gate <id>` resolves (script under `scripts/`, gate id in `registry.v1.json`). Full *generation* stays
blocked by the authority gap (ui-web checks aren't registry gates — Move 1), so this is the proportionate
guard. Teeth verified (a bogus token → exit 1); passes at HEAD (36 script + 11 gate refs); added its own
table row.

**Phase 6 — `hook-hint` delivery tier (VI.B).** Added the tier to `truth-table.mjs` `ALLOWED_TIERS` +
`enforcer.mjs` marker-required set; retiered rows 22 (`hook:compact-restore.mjs`) and 32
(`hook:tempdoc-age-hint.mjs`) from `prose-only` → `hook-hint`; added the legend row; authored
`gates/prose-tier-register/.changesets/620-delivery-tier.md` (`tier-change`). Verified load-bearing: the gate
passes with the changeset and fails `silent-tier-change` without it. Scope held to the 2 clear cases (row 6
keeps its real `lint` subset; row 5's `seam-hint` delivery is partial over a broad judgment rule).

**Phase 7 — #13 tempdoc-completeness guard: DEFERRED after investigation.** `tempdoc-status-check.mjs`'s
canonical set `{open,active,done,shipped,superseded,draft}` is already red against current practice (tempdocs
use `implemented`/`merged`/`investigated`/…) and isn't CI-wired; a checkbox sub-check scoped to `done`/`shipped`
would match **zero** current tempdocs. Making it useful means re-canonicalizing the status vocabulary across
20+ tempdocs + the README contract — a separate cross-cutting concern, out of scope for the always-loaded
layer. Logged the staleness to `docs/observations.md`; built nothing.

**Phase 8 — finalize.** Regenerated llms.txt (115 docs, incl. the new canonical doc) + skills; reconciled the
budget (branch-safety −3.4 KB banked via `--rebalance`; CLAUDE.md +239 B / tier-register +498 B raised
deliberately for the new validator row + the `hook-hint` legend). Fixed this tempdoc's own status to the
canonical `active` (it was the non-canonical `in-progress`).

## Final metrics + verification
- **Always-loaded set: 74,575 B (~18,644 tok)** — vs the pre-task 85,456 B (**−12.7%**) and the session-start
  100,832 B (**≈ −26%**). `common-workflows.md` (8.2 KB) fully evicted; branch-safety −3.4 KB.
- **All green:** `prose-tier-register` (pass; 2 info declared-tier-change), `always-loaded-budget --check`,
  `check-suppression-ratchet`, `check-premerge-table`, `llmstxt-generate --check` (115), `skills-sync --check`
  (6), consult-register JSON valid (8 regions), and every crafted-stdin hook test.

## Deferred — carried forward with reason (ratified by plan approval)
- `slice-execution.md` relocation — methodology, no path trigger to key delivery on; smallest file.
- #35 `verify-worktree-base` — no deterministic hook trigger (`EnterWorktree` isn't a wired tool); the
  `baseRef:"head"` config is the by-construction half.
- #7 / #24 / #26 Tier-2 partials — false-positive / reminder-only; 582 R1 (a guard must earn its maintenance).
- #13 tempdoc-completeness — host check non-functional vs practice (Phase 7); the fix is out of scope.
- The general "all docs project" gate (Part IV — await a 2nd instance); judgment rules stay prose; retired
  gates #29/#30/#31 not re-mechanized (tempdoc 563).

## New / changed infrastructure
- **NEW** `docs/reference/contributing/common-workflows.md` (canonical, on-demand).
- **NEW** `scripts/ci/check-premerge-table.mjs` (Pre-merge table drift validator).
- **NEW** `gates/prose-tier-register/.changesets/620-delivery-tier.md`.
- **EXTENDED** `governance/consult-register.v1.json` (+`recipe` field, +4 recipe rows);
  `consult-doc-hint.mjs` (recipe render); `mcp-dev-tools.md` (verdict model + FE live-validate);
  `truth-table.mjs` + `enforcer.mjs` (`hook-hint` tier); `scanner.mjs` (drop `common-workflows` from
  `EXCLUDED_RULE_FILES`, keep `compaction-state.md`).

---

## Phases 1–8 review (2026-06-21)

A post-implementation critical-analysis pass over the Phases 1–8 changes (the bidirectional discipline).
**One substantive defect + one minor cleanup found and fixed; the rest verified OK against source.**

- **Fixed (substantive) — tier-register self-contradiction on the allowed tier set.** Phase 6 added
  `hook-hint` to the gate enum (`truth-table.mjs` `ALLOWED_TIERS`), the Format legend, and rows 22/32, but
  the "What the meta-loop gate enforces" item #1 still hard-listed the old 5 tiers — a hand-list fork of
  the authority, the exact drift this tempdoc targets. Fixed projection-correctly: item #1 now *references*
  the Format legend / `ALLOWED_TIERS` instead of re-listing, so it cannot drift on the next tier change.
- **Fixed (minor) — dead shadowed trigger token.** `consult-doc-hint` is first-match; the
  `workflow-agent-tool` recipe's `OperationCatalog` token was shadowed by the earlier `app-agent` doc
  region (`OperationCatalog.java` lives in `app-agent-api/`), so it never fired. Removed the dead token; the
  recipe still fires via `AgentToolsOperationCatalog` + `registry/operations/` (app-services), and editing
  `OperationCatalog.java` surfaces the (relevant) app-agent architecture doc.
- **Verified OK (refuted suspicions):** all 4 recipe trigger paths are real (`indexing.proto`,
  `*Routes.java` under `ui/api/`, the 3 config files, the operation catalogs/handlers) → recipes fire;
  `maintain-doc-hint` skips `docs:[]`/no-`maintain` recipe rows (line 109) → no spurious Stop-block.
- **Noted, not changed (decisions):** the REST recipe's `ui/api/`-package trigger is broader than the
  routing table's `*Routes.java` example but defensible (the recipe is relevant to api-package work; broad
  region triggers are the established consult-register pattern); `check-premerge-table` / budget /
  suppression ratchets stay unwired from `ci.yml`, consistent with the 620 "manual-now, fold-later" posture
  (not a defect).
- **Re-verified green:** `prose-tier-register`, `always-loaded-budget --check`, `check-suppression-ratchet`,
  `check-premerge-table`, `llmstxt-generate --check`, `skills-sync --check`, consult-register JSON valid.
