---
title: "Instruction-layer re-baseline: take the always-loaded set back under its ceiling and re-fit it to the current model generation — relocate region-specific payloads to moment-of-relevance delivery, compress evasion-enumerations into reason-bearing principles, and delete generation-obsolete rules in measured tranches. Triggered by (a) the always-loaded-budget gate being RED on main (measured 2026-07-06: 86,094 B ≈ 21.5K tokens vs 83,187 B ceiling, three files over) and (b) the Claude 5 model generation (2026-06-09), whose official guidance says instructions tuned for prior models are often too prescriptive and can degrade output quality. Continues tempdoc 620's residence→delivery line. IMPLEMENTED 2026-07-07: 87,381 → 66,493 B (~21,845 → ~16,623 tok, −24%), all files green, ceilings re-pinned; see §Implementation record."
type: tempdocs
status: "open — IMPLEMENTED (2026-07-07, worktree 681-instruction-layer), WATCH WINDOW ACTIVE for the Move 2/3 tranche (restore-on-recurrence; terms in §Implementation record). All three moves + the de-risk guard rails shipped and validated; the 2c912ae bump re-ratchet obligation (§Owner-directed addition) is honored — ceilings re-pinned at 66,493 B total. Remaining: the watch window, and future tranches only if evidence earns them."
created: 2026-07-06
updated: 2026-07-07
author: agent workflow-review pass (live repo measurement: check-always-loaded-budget.mjs run 2026-07-06; official-source research pass over Anthropic docs/publications 2026-05-06..2026-07-06)
category: agent-workflow / instruction-layer / context-efficiency
related:
  - 620-claude-md-context-layer   # the direct precedent — relocated common-workflows.md out of the always-loaded layer, established residence→delivery conversions; this tempdoc is the continuation of that line
  - 680-observations-channel-grouped-evidence-derived-design   # adjacent workflow-layer work with a named file-level coupling (writer-facing rules text + subagent-guide brief) — see §Couplings
---

# 681 — Instruction-layer re-baseline: relocate, compress to reasons, measured deletion

## What this is and why now

The always-loaded instruction layer (root `CLAUDE.md` + `.claude/rules/*.md`) taxes every session
of every agent. It has two problems, one measured and one generation-triggered:

1. **It is over its own ceiling on `main`.** `node scripts/ci/check-always-loaded-budget.mjs`
   (run 2026-07-06, reproducible): total **86,094 B (~21,524 tokens) vs ceiling 83,187 B**;
   over-ceiling files: `CLAUDE.md` (+6 B), `.claude/rules/branch-safety.md` (+1,631 B),
   `.claude/rules/hooks-reference.md` (+1,287 B). The gate's own remediation text points at
   tempdoc 620 Moves 1/2 (migrate out, don't just trim).
2. **The model generation changed, and official guidance says to re-fit.** The Claude 5 family
   shipped 2026-06-09. Anthropic's current official guidance, all verified live 2026-07-06:
   - [Prompting Claude Fable 5](https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/prompting-claude-fable-5):
     capability improvements at this level are "a good prompt to re-evaluate which instructions,
     tools, and guardrails are still needed"; instructions/skills developed for prior models "are
     often too prescriptive... and can degrade output quality"; most behaviors can now be steered
     "with a brief instruction rather than enumerating each behavior by name."
   - [Claude Code best practices](https://code.claude.com/docs/en/best-practices): the
     "over-specified CLAUDE.md" named failure pattern — "Ruthlessly prune. If Claude already does
     something correctly without the instruction, delete it or convert it to a hook."
   - [Teaching Claude why](https://www.anthropic.com/research/teaching-claude-why) (2026-05-08):
     the mechanism — a few reason-bearing principles generalize far better than long enumerations
     of behaviors.
   - [Memory & rules docs](https://code.claude.com/docs/en/memory.md): `.claude/rules/` files can
     carry `paths` frontmatter (glob-scoped) so a rule loads only when matching files are touched —
     a native relocation target that did not exist when most of the current layer was written.

Under (2), shrinking this layer is not just cost reduction — over-specification is officially
framed as a *quality* defect (instructions get ignored; prescriptive scaffolding degrades output).
This consolidates a direction identified in the maintainer's private 2026-07 velocity analysis;
this tempdoc stands alone on the evidence above and does not depend on that document.

## Preconditions (implementing session starts here)

1. **Probe `paths` frontmatter support in the live harness** before designing around it (runtime
   probe outranks docs — `.claude/rules/agent-lessons.md` evidence chain). Create a throwaway
   path-scoped rule, touch a matching and a non-matching file, confirm load behavior in-session.
2. **Confirm `scripts/agent-analytics/lib/hard-invariants.mjs` parse invariants.** It live-projects
   CLAUDE.md's Hard Invariants into every subagent brief; any restructuring of that section must
   keep the projection green. Read its parsing assumptions first; add a regression test if none
   pins them.

## Design — three moves, in decreasing order of safety

**Move 1 — RELOCATE (safe; content still arrives, at its moment of relevance).** Region-specific
payloads leave the always-loaded set for path-scoped delivery. Mechanism preference order:
(a) `paths`-frontmatter rule (post-probe), (b) `governance/consult-register.v1.json` push,
(c) existing skill body. First candidates, chosen because each is large and relevant only when its
region is touched:
- The **ui-web gate table** in CLAUDE.md's Pre-merge section (the largest single region-specific
  block) → path-scoped to `modules/ui-web/src/**`.
- **`hooks-reference.md` per-hook detail** — an operational catalog, not load-bearing rules (the
  tier-register already excludes it from the meta-loop for exactly that reason). Keep a short
  "hooks exist; when one blocks, don't retry; kill switch" stub always-loaded; move per-hook
  bodies to delivery at the moment the hook actually fires (hooks can carry their own guidance in
  their block/hint messages — several already do).
- **`branch-safety.md` worktree lifecycle how-to** (creation/preparation/cleanup mechanics) →
  path/plan-triggered delivery; the hard rules (never checkout/destructive in main, force-push ban)
  stay always-loaded — they are the blocked-command tier's prose face.
- Per-pitfall detail rows in CLAUDE.md's Common Pitfalls that duplicate an existing hook-hint's
  delivery (e.g. lockfile, ssot-sync already have hooks + skills).

**Move 2 — COMPRESS (keep the rule and its *why*; cut the catalog).** Evasion-enumerations become
one reason-bearing line each. Example shape: `structural-defects-no-repeat`'s four named
"wait-for-more-evidence" aliases → "every deferral framing is the same move: converting a
correctness argument into a cost-benefit argument — don't, unless asked." **Tension recorded
honestly:** CLAUDE.md's own `before-appending-to-rules` gate says naming the predictable evasion
inline raises adherence — that guidance was written against prior-generation models, and the
official position now says enumeration degrades current-generation output. Neither claim has been
measured on this repo's workload; the resolution is per-tranche measurement (§Measurement), not
doctrine. Compression must preserve `<!-- rule:slug -->` anchors and tier-register alignment
(the `prose-tier-register` gate scans these files; every tier/wording change of a registered row
follows the register's changeset workflow).

**Move 3 — DELETE, in measured tranches (the only risky move; paced by evidence).** Rules that
describe behavior the current model generation does by default get retired — identified
empirically: a rule is a deletion candidate when (a) its failure mode has zero observations-inbox
entries in the current model era, AND (b) its enforcement tier is prose-only (hook/gate-tier rules
lose nothing by keeping their one-line prose face). Tranche-and-watch protocol: delete a small
tranche → watch the observations inbox and session telemetry for recurrence of exactly the trimmed
rules' failure modes over a multi-session window → restore any rule whose failure mode recurs
(that rule has *earned* its text; record the restoration). Each deletion is a `rule-retired`
changeset per the tier-register workflow.

**Sequencing note:** Move 1 alone likely clears the ceiling. Moves 2–3 are quality work under the
generation-refit rationale and proceed at their own pace; a green budget gate does not close this
tempdoc, but neither must all three moves land in one session.

## Boundaries — what this deliberately does NOT take over

- **Enforcer payback** (hooks / discipline gates / CI check scripts — "has it ever caught a real
  defect?"). Owned by periodic enforcement-layer payback passes. Boundary where they meet: when
  this work finds a prose rule whose claimed enforcement is stale, it *proposes*; a payback pass
  confirms/retires the enforcer.
- **Product-code subtraction** (god-file decomposition, dead-surface deletion). Different domain
  with its own precondition (runtime-reachability evidence); not instruction-layer work.
- **Tempdoc 680's implementation** (observations channel). Adjacent, not absorbed — see coupling.
- **The maintainer's session-prompt layer.** The same generation-refit rationale applies to it,
  but the trim is deliberately PARKED: a prompt-adherence baseline instrument was installed
  2026-07-06 and is currently accumulating sessions under the existing prompts; trimming now would
  contaminate the baseline it exists to collect. Revisit once it has data.

## Couplings (for the implementing session's collision check)

- **`hard-invariants.mjs`** — see §Preconditions 2. Breaking its parse silently degrades every
  subagent brief.
- **`prose-tier-register` gate** — scans CLAUDE.md + rules for anchors/sentences; every move here
  is visible to it. Budget for changeset authoring as real work, not overhead — it is the register
  working as designed.
- **`check-always-loaded-budget` ratchet** — after Move 1, run `--rebalance` so the ceiling
  ratchets DOWN to the new smaller footprint (the gate's stated protocol when a file genuinely
  shrank); do not leave reclaimed headroom for future growth to re-fill.
- **Tempdoc 680** — its implementing session deletes one writer-facing rule ("skip duplicates"),
  edits `docs/observations.md` Rules + `development-philosophy.md`, and rewords the
  `subagent-guide` brief. This tempdoc touches the same rule files and the same brief-projection
  path. If the two implementing sessions overlap in time, coordinate file-level; otherwise
  whichever lands second rebases its wording edits.

## Measurement and falsifiers

- **Success, Move 1:** budget gate green, ceiling re-pinned lower, and the relocated content
  demonstrably still delivered (probe: touch a ui-web file, confirm the gate-table guidance
  arrives; the relocation is a residence→delivery conversion, not a deletion).
- **Success, Moves 2–3:** no recurrence of trimmed rules' failure modes in the observations inbox
  / telemetry across the watch window; sessions do not regress on the behaviors the rules governed.
- **Falsifiers, recorded now:** (a) the `paths` probe fails → Move 1 falls back to consult-register
  + skills only (620's existing mechanisms — the move survives, smaller). (b) A deleted/compressed
  rule's failure mode recurs within its watch window → restore it; if this happens for a
  *majority* of a tranche, the official "prior-generation instructions degrade output" claim does
  not hold on this workload at this layer — stop Move 3, record the negative result (it is a
  finding, not a failure), and keep Moves 1–2 gains.
- **Non-goal:** this tempdoc adds no new always-loaded text beyond net-negative edits, and builds
  no new measurement apparatus — the budget gate, observations inbox, and existing telemetry are
  the sensors.

## De-risk findings (2026-07-06, pre-implementation confidence pass — no feature work done)

Seven probes against the live repo + harness. Everything below is measured/observed this session.

- **`paths` frontmatter: CONFIRMED, docs + live probe.** Official docs
  ([memory.md](https://code.claude.com/docs/en/memory.md)) state path-scoped rules "trigger when
  Claude reads files matching the pattern"; version-gated notes reference v2.1.198 and this
  machine runs **2.1.200**. Live probe (scratchpad project, `paths: ["src/**"]` rule with a marker
  token, headless `claude -p`): marker applied on a matching-file read, absent on a no-file-touch
  prompt. Move 1's preferred mechanism works on this harness, on Windows. Two doc deltas:
  `@import` does NOT reduce context (loads at launch — not a relocation mechanism), and the
  `InstructionsLoaded` hook can log exactly which rule files loaded — use it to verify delivery
  during implementation.
- **Budget gate mechanics (design delta — Move 1 scope grows slightly).** The baseline
  (`always-loaded-budget.v1.json`) is a **hardcoded file map, not a glob**: a NEW
  `.claude/rules/*.md` file is invisible to the check. So a path-scoped relocation target must be
  added to the gate's accounting (extend the checker to glob `.claude/rules/*.md` and exempt files
  with `paths:` frontmatter — small script change, in scope) or the gate silently under-counts.
  `--rebalance` shrink-only + `--bump` declared-growth semantics confirmed as designed.
- **Structural-consumers map (P2).** (a) `lib/hard-invariants.mjs`: parses `## Hard Invariants` +
  numbered list, ends at next `##`, strips anchors; fail-open (silent [] on parse failure); sole
  consumer `subagent-guide.mjs`; **no test pins the parser — add one in scope**. (b)
  `check-premerge-table.mjs`: locates the table by its header row, validates backticked
  script/gate refs resolve; **removing rows is safe**, but relocated rows' refs go unvalidated —
  extend it to also scan the relocated file (small, in scope). (c) `hook-integrity` gate: the hook
  layer's authority is `governance/agent-hooks.v1.json` (wiring/load/bite checks + tier-register
  `hook:` marker sync) — `hooks-reference.md` content is NOT gate-validated, so its relocation is
  low-risk.
- **prose-tier-register real friction is LOWER than its own doc claims (dry-run measured).**
  Removing an anchored section from CLAUDE.md and running the gate produced
  `orphan-grandfathered` at **note level — gate passes** (tier-register.md documents this case as
  a hard `orphan-register-row` failure; doc-vs-enforcer gap logged to the inbox, session shard
  06f94413). Practical consequence: Move 1 relocations that keep anchors inside `.claude/rules/**`
  (still in scanner scope) carry near-zero register cost; Move 3 row deletions still owe
  `rule-retired` changesets via the baseline-diff tier-discipline check.
- **hooks-reference relocation is cheap (P5, 2/2 sample).** Sampled hooks (`pipe-mask-hint`,
  `lockfile-hint`) emit complete, self-contained guidance at fire time — full remedy commands and
  rationale — duplicating their catalog entries. Keep the behavioral preamble (kill switch,
  "when blocked, adapt") + the bash-guard block tables; per-hook bodies can go, verifying per hook
  that the fire-time message carries the guidance before cutting its catalog entry.
- **Move 3's evidence test is answerable but WEAK (protocol delta).** Inbox greps run fine, but
  sampled keyword hits were unrelated environment notes — rule *violations* are not
  systematically logged, so "zero era entries" is weak positive evidence of obsolescence.
  Revised protocol: candidate selection = inbox grep + recent session-retro skim, tranches stay
  small, and the load-bearing safety is restore-on-recurrence, not confident pre-selection.
- **Collisions (P7, measured).** Branches touching CLAUDE.md/.claude/rules vs main:
  `worktree-664-publish-gate` (CLAUDE.md + agent-lessons + branch-safety + **adds a tier-register
  row**), `codex/public-main-history-policy` (branch-safety rewrite + CLAUDE.md), `worktree-644-capability`
  (1-line CLAUDE.md), stale `worktree-624-agent-utility-hardening` (branch-safety + hooks-reference;
  no active worktree). Sequencing: land 681's branch-safety/tier-register edits AFTER 664 and the
  history-policy branch merge, or budget rebase work.

**Net design deltas absorbed above:** budget-checker amendment added to Move 1 scope;
`hardInvariants()` regression test added to scope; `check-premerge-table` scan extension added to
scope; Move 3 evidence protocol revised. Confidence after this pass: 7/10 (from 4/10) — remaining
uncertainty is inherent (behavioral effect of Moves 2–3 is only measurable post-hoc via the
tranche watch) plus multi-branch coordination timing.

## Owner-directed addition (2026-07-07): model-routing & delegation economics — LANDED same day

> Appended by a different session at the owner's direction, drafted for THIS tempdoc rather than a
> new one because it edits the same always-loaded files this tempdoc owns. **STATUS UPDATE, same
> day: the owner directed immediate landing — the edits below are IN the working tree (compressed
> ~40% from the draft for budget; CLAUDE.md +1050 B and agent-lessons +243 B declared via
> `--bump` with reasons recorded in `baseline.bumps`; prose-tier-register gate green). Implementing
> session: do NOT re-land these; your remaining obligation from this section is only that Move 1's
> relocations re-ratchet the bumped ceilings back down (`--rebalance` after the relocations land),
> honoring the net-negative non-goal at tempdoc level rather than tranche level.** The draft text
> below is retained as the section's dated record; the landed (compressed) versions in CLAUDE.md /
> `context-efficiency.md` / `agent-lessons.md` are authoritative.

**Rationale (owner decision, 2026-07-07).** The current model generation's orchestrator is
expensive per token and implementation is the token-heavy half of the work; the ecosystem guidance
around the official workflow docs states the price-gradient plainly (orchestrator does
decomposition/sequencing/judging; workers are "scoped enough that Sonnet's quality is
indistinguishable"). Two repo-local facts sharpen it: subagents **inherit the parent model when
`model:` is unset** — observed in the 682 implementation session, whose two (excellent) delegated
implementations silently ran on the orchestrator's model — and the repo's own delegation section
already warns that subagents lack the discipline layer, so the routing rule must carry its
verification counterweight, not just the cost rule.

**Draft text — destined for CLAUDE.md's existing "Delegating to Subagents (Agent Tool)" section**
(extend the existing `rule:delegating-to-subagents` anchored section rather than adding a new
anchor: its sentences then live inside an already-registered section, minimizing
prose-tier-register churn; if the register's row-18 description is updated to mention routing,
that is a wording edit, not a tier change — confirm against the baseline-diff behavior measured in
§De-risk):

```markdown
**Model routing (delegation economics).** The orchestrator's sweet spot in this repo is
decomposition, briefs, design, and judging returned evidence; implementation is token-heavy —
delegate bounded, verifiable implementation chunks instead of implementing long stretches in the
main loop. **Set `model: "sonnet"` explicitly on implementation subagents** — subagents inherit
the parent model, so an unset `model` silently bills orchestrator-tier for worker-grade work.
Sonnet is the floor for any subagent whose findings you will rely on; haiku only where wrong
output is self-evident (pure mechanical retrieval). Keep inline, never delegate: brief-writing
and evidence judgment, shared-resource operations (dev stack, main checkout, merge/publish),
irreversible actions, and trivial edits where writing a brief plus reading a report costs more
than the change. Chunk long refactors into bounded delegations rather than handing them over
whole. Escalate on quality without asking: if a worker's output misses the bar, redo it with a
stronger model — judge the output, not the price tag; shipping mediocre work costs more than
tokens.
```

**Companion edits, same tranche:**
1. `.claude/rules/context-efficiency.md` — REPLACE the line `**Use `model: haiku`** for simple
   subagent tasks (file lookups, pattern searches).` with: `**Worker-model floor:** sonnet for any
   subagent whose findings you'll rely on; haiku only where wrong output is self-evident. (Routing
   rule lives in CLAUDE.md "Delegating to Subagents".)` — the old line predates the current model
   generation and contradicts the routing rule. (File is register-exempt; no changeset needed.)
2. `.claude/rules/agent-lessons.md` — one line under platform constraints: `**Time-to-complete is
   an architecture signal.** A delegated fix that lands in minutes is probably safe to take; one
   that needs an hour-plus of agent time for a "simple" request is telling you about the code, not
   the agent — read the diff and the architecture before merging, not just the outcome.`

**Explicitly out of scope, by owner decision (recorded so it isn't re-proposed):** effort-level
documentation (the owner sets effort at the harness and runs high; per-step effort guidance in the
instruction layer would be dead weight) and cross-vendor worker routing (Codex-style delegation —
the owner's subscription economics don't support it; the separate §9.5 review-only Codex pilot is
unaffected and remains the only cross-vendor channel). Also NOT adopted from the same source
material: model-conditional rule branches ("if you are model X…") — they multiply the surface this
tempdoc exists to shrink; the routing rule above is written model-agnostically ("orchestrator" /
"worker") so it survives model renames.

**Falsifier / earning-its-keep (no new apparatus):** the session-telemetry read model already
computes cost-per-shipped-merge. After ~2 months under this routing, that ratio should visibly
improve with no rise in post-merge fix rate (the §12 rework measure). If cost doesn't move, the
routing is ceremony — delete the paragraph. If cost improves but rework rises, the Sonnet floor is
too low for this repo's work — raise the floor, keep the structure.

## Implementation record (2026-07-07, worktree `681-instruction-layer`)

**Headline, measured:** always-loaded total **87,381 B (~21,845 tok) → 66,493 B (~16,623 tok), −24%**;
every file green; ceilings re-pinned via `--rebalance` (5 lowered; total ceiling now 66,493 B) —
which also discharges the §Owner-directed-addition obligation to re-ratchet the 2c912ae bumps.

**Move 1 — relocations (all delivery-verified, not just deleted):**
- `hooks-reference.md` 17,446 → 2,740 B. Rewritten as a behavioral contract (adapt-don't-retry,
  kill switch, blocking-guard summaries, one-line hint index); per-hook catalog bodies deleted
  after verifying each hook's fire-time message carries its guidance (sampled texts: pipe-mask,
  lockfile, docs-regen, bash-guard block messages, search-engine full recipe).
- ui-web gate table (3 rows) → `ui-web-gates` recipe row in `governance/consult-register.v1.json`;
  CLAUDE.md keeps a one-line pointer row. Delivery probe: crafted-stdin `consult-doc-hint` on a
  `modules/ui-web/src/` edit pushes the full 5-step recipe. `check-premerge-table.mjs` extended to
  scan register recipe strings; mutation check confirmed it FAILS on a broken recipe ref.
- `branch-safety.md` 12,285 → 10,204 B (under its 10,654 ceiling): worktree creation mechanics
  (config seeding, cuda12/shared-models resolution, outside-dev-runner env vars) →
  `docs/reference/contributing/common-workflows.md` §Worktree mechanics; compact recipe + pointer
  stays. Collision zones (Merge Workflow, bash-guard tables — touched by `worktree-664` /
  history-policy branches) deliberately untouched.
- CLAUDE.md Common Pitfalls: ssot + schema-drift rows compressed to hook/skill pointers.
- Budget checker hardened (fail-closed): globs `.claude/rules/*.md` and FAILS on any on-disk file
  absent from the baseline (`UNLISTED`) unless `paths:`-frontmatter-scoped or `compaction-state.md`;
  `--bump` extended to be the declared-ADDITION path (new file from ceiling 0). Closes the
  §De-risk blind spot where a new rules file was always-loaded AND unmeasured.

**Move 2 — compressions (anchors + tiers unchanged; no changesets owed, confirmed by gate run):**
`fix-root-causes-not-symptoms` (6 bullets → "failure invisible instead of impossible" + compact
clause; register row 6 wording updated), `structural-defects-no-repeat` (alias bullets → one
parenthetical), `tempdoc-is-your-contract`, `before-appending-to-rules`,
`delegating-to-subagents` (model-routing paragraph preserved verbatim). CLAUDE.md 26,094 → 22,053 B.

**Move 3 — tranche 1: one deletion.** `ask-when-uncertain` (register row 17, prose-only) retired —
anchor + section removed, row removed, changeset
`gates/prose-tier-register/.changesets/688-retire-ask-when-uncertain.md` (`tempdoc: 681`;
filename renumbered 681→688 by the cross-worktree number guard — 687 was claimed in-flight).
Evidence: zero inbox entries for failure-to-ask; harness-level guidance now governs ask-vs-proceed;
operator prompts steer against over-asking. **Watch window:** through ~2026-08-07, a failure-to-ask
incident in the inbox/retros restores the rule verbatim and records it as having earned its text.
Candidates evaluated and NOT deleted: `verify-your-work` (carries project-specific commands),
`one-branch-per-worktree` (structurally enforced but sits in a collision-zone list; churn > gain).

**Guard rails added:** `scripts/agent-analytics/lib/hard-invariants.test.mjs` pins the
`hardInvariants()` parse contract (section heading, numbered items, anchor stripping, content
needles) — green; crafted-stdin `subagent-guide` probe confirms all invariants project post-edit.

**Validation (all green, this worktree, 2026-07-07):** always-loaded-budget (pass, re-pinned);
prose-tier-register gate (pass; `declared-row-removal` note covered by the changeset);
hook-integrity gate (pass); check-premerge-table (pass + mutation-bite verified);
all `scripts/agent-analytics/**/*.test.mjs` (0 failures); check-tempdoc-numbers (OK, 16 worktrees);
`./gradlew.bat build -x test -PskipWebBuild=true` (BUILD SUCCESSFUL asserted on output text);
docs regen (`llmstxt-generate`, `skills-sync`) after the common-workflows.md edit. No user-visible
product surface was touched — browser validation not applicable.

**Unverified assumptions & deferred checks (honest ledger for the continuing agent):**
1. *Behavioral effect of Moves 2–3 is unverified by design* — only the watch window measures it
   (§Design). Sensor: observations inbox + session retros; restore-on-recurrence is the remedy.
2. *Fire-time-message sufficiency was verified verbatim for 5 hooks* (pipe-mask, lockfile,
   docs-regen, bash-guard, search-engine) *and by guidance-string density survey for the rest* —
   a hook whose message is thinner than its deleted catalog entry could exist. If a session is
   confused by a hook firing, enrich that hook's message (do not regrow the catalog).
3. *The budget checker's new `paths:`-frontmatter exemption branch has no automated test and no
   live exercicer yet* (no path-scoped rule file exists in-repo; the branch was reviewed, and the
   fail-closed UNLISTED path is exercised by every check run's green result). First real
   path-scoped rule file should confirm it is skipped, not flagged.
4. *Consult recipe delivery was verified by crafted-stdin invocation of `consult-doc-hint`, not by
   a harness-originated Edit event* — the hook's wiring itself was already live pre-681 (only its
   register data changed), and `hook-integrity` (green) covers wiring, so residual risk is low.
5. *Coordination debt:* `worktree-664-publish-gate` and the history-policy branch touch
   branch-safety.md/tier-register.md; 681 avoided their hunks, but whichever merges second may
   need a trivial rebase. The `worktree-624` branch (stale, no worktree) also touches these files.
