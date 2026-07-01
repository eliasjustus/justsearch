---
title: "Observations-inbox workflow — lifecycle gaps beyond the parallel-write fix, settled to a design direction that aligns retirement with the issues/ tier's already-working convention"
type: tempdocs
status: "implemented 2026-07-01 (fifth pass, same day as passes 1-4) — shard-durability Stop hook, fold wired to merge-teardown, delete-on-resolve convention shipped, one-time cleanup applied (removed 199 resolved entries + merged the accidental second inbox), fold-observations.mjs gained a report-only stale-entry check. Two corrections found during implementation are recorded in the Implementation section: the design pass's SessionEnd hook choice was wrong (corrected to Stop) and the Post-push handoff section was much larger and more substantive than Finding 8 assumed (merged, not dissolved)."
created: 2026-07-01
updated: 2026-07-01
author: agent critical-analysis + design-theorization pass (live repo inspection: docs/observations.md, docs/observations.d/, scripts/agent-analytics/{note-observation,fold-observations,record-merge}.mjs, docs/reference/issues/, docs/reference/contributing/development-philosophy.md, scripts/governance/ discipline-gate kernel, git history of the shard directory and the fold script)
related:
  - 618-agent-developer-velocity-friction   # designed + shipped the per-session shard + fold mechanism (Seam C) this tempdoc audits the surrounding lifecycle of
  - 646-event-sourced-tempdoc-current-state # sibling precedent for this tempdoc's own method: recognize a principle, record the design, defer building generalized machinery until a real trigger
  - 530-class-size-ratchet-automation       # origin of the discipline-gate kernel (shard/changeset + baseline + rebalance) evaluated here as a candidate model and found mismatched in weight for this artifact
  - 653-public-main-history-hygiene         # public-history convention (tempdoc/doc edits ride along with the change that motivated them) that rules out an automated bot-commit fold as a direction
---

> **CURRENT STATE (2026-07-01, third pass) — DESIGN SETTLED, NOT IMPLEMENTED.** Read this first. The first pass
> (§Findings) found the *mechanism* tempdoc 618 built (per-session shards, conflict-free fold) sound and
> well-tested, but the *lifecycle around it* has gaps: shard durability depends on a manual step nothing
> enforces, the fold step has no automated trigger despite 618 proposing one, the destination file is not
> pruned despite its own stated rule, and the file has grown large enough to break the "check before you act"
> premise the whole workflow depends on. The second pass (§Theorization) surveyed reframings, candidate
> directions, and hidden assumptions without choosing one. **This third pass (§Design) settles a concrete,
> general design direction**, after finding that the codebase already has a working, zero-tooling precedent
> for the missing half of the lifecycle (`docs/reference/issues/`'s "delete on resolve, don't mark closed"
> convention) — so the design **extends and aligns with two already-existing mechanisms** (that convention, and
> the already-existing merge-teardown boundary) rather than introducing new ones. §Reach then judges whether
> this generalizes: it names the underlying principle plainly, checks where else it currently applies (one
> other place — the same artifact, from its canonical-doc side), and deliberately does not build shared
> structure beyond this one artifact, per the recognize-vs-build discipline tempdoc 646 already models.
>
> **UPDATE (2026-07-01, fifth pass) — IMPLEMENTED.** §Implementation (foot of document) records what shipped:
> the wording fix, the merge-teardown wiring, the new `observation-shard-hint` Stop hook, the report-only
> stale-entry check in `fold-observations.mjs`, and a one-time historical cleanup of `docs/observations.md`
> itself. Two corrections surfaced during implementation and are recorded there rather than silently folded
> into the earlier passes: the design pass picked the wrong hook event (`SessionEnd` → corrected to `Stop`),
> and Finding 8's characterization of "Post-push handoff" as a small stale dump was wrong — full investigation
> found it was a much larger accidental second inbox, fixed by merging rather than dissolving.

# 665 — Observations-inbox workflow: lifecycle gaps beyond the parallel-write fix

## The idea / what prompted this

Tempdoc 618 (`agent-developer-velocity-friction`, Seam C) replaced a single shared `## Inbox` append target
in `docs/observations.md` with per-session shard files (`docs/observations.d/<session>.md`), reconciled into
the canonical file by a manual `fold-observations.mjs --apply` step. That fix targeted one specific failure
mode: two parallel agents both appending to the same shared file, one agent's commit/reset silently dropping
the other's note (reproduced 3× per 618 §12).

The user asked for a critical analysis of the workflow "regarding observations.md" — not scoped to the
parallel-write mechanism specifically, but the workflow generally, from multiple angles. This tempdoc records
what that broader pass found, live against the current repo state (2026-07-01), not against 618's design intent.

## Method

Read the current scripts (`scripts/agent-analytics/{note-observation,fold-observations}.mjs` + their `.test.mjs`
suites), the current `docs/observations.md` (610 lines / 274,615 bytes) including its `## Rules` and `## Inbox`
sections and the trailing `## Post-push handoff (2026-05-18)` section, the current contents of
`docs/observations.d/` (one file, `5555b628-7af4-4620-8718-7e57aacfa94d.md`), `git log` over both the shard
directory and `fold-observations.mjs` to see actual fold cadence, `docs/reference/issues/` (the formal-promotion
destination named in the Rules), and `.github/` for any CI wiring of the fold step. Findings below are anchored
to specific lines / commits, not summarized from memory.

## Findings

### 1. Shard durability depends on a step nothing enforces — and it is failing live, right now

`note-observation.mjs:87-103` (`appendObservation`) only writes to disk. It does not stage or commit the
shard. Tempdoc 618's durability argument (§P1.2) is that the shard "rides the agent's own commit in its own
worktree" — i.e., durability is assumed to come from the agent's normal commit of its task work, not from any
enforced step. But `branch-safety.md` instructs "stage your own files explicitly (`git add <paths>`), not
`git add -A`," and the injected subagent brief (`scripts/agent-analytics/hooks/subagent-guide.mjs:72-75`) tells
agents *how* to log an observation but never tells them to stage or commit the resulting shard file.

Live evidence, not hypothetical: `docs/observations.d/5555b628-7af4-4620-8718-7e57aacfa94d.md` is **untracked**
in the working tree as of this session (`git status` `??`), containing two real findings from a tempdoc-643
investigation (stale "default off" comments at `HybridSearchOps.java:477`, `SearchExecutor.java:758`,
`EnvRegistry.java:972`, `ResolvedConfigBuilder.java:1492-1497`, `RerankerConfig.java:59`,
`KnowledgeSearchEngine.java:158-161`). If this worktree is torn down or the note is simply forgotten, those two
findings vanish — the identical loss mode 618 set out to fix, moved one layer down (session→commit boundary
instead of file→file clobber) and now quieter because it does not manifest as a visible diff conflict.

### 2. The fold step has no automated trigger, despite 618 proposing one

Tempdoc 618 §P1.2 explicitly names the fallback: "a periodic/CI fold... if a session never merges." This was
never built. `grep -r fold-observations .github/` returns no matches. Every fold to date
(`1bb814c` "fold 15 session shards," `a85c3b9` "fold observation shards (post-merge maintenance)", earlier
folds pre-dating the public-release squash at `29579e5`) is a manually-authored one-off "chore(observations)"
PR. The reconcile step is exactly as honor-system as the append step it replaced — it is simply less dangerous
to skip (shards accumulate visibly instead of a note vanishing invisibly, modulo Finding 1).

### 3. The destination file is not pruned, despite its own stated rule

`docs/observations.md:18` states the rule: "Prune checked items when the list gets long." As of this session,
the `## Inbox` section (`docs/observations.md:28-265`) holds 224 entries, of which **127 (57%) are already
`[x]` resolved** — some closed as far back as 2026-06-11 (the bash-guard quote-awareness fix at
`docs/observations.md:33`) or 2026-05-21 (several HealthSurface fixes around `docs/observations.md:277-286`).
None have been pruned. Fold PRs land entries in batches of 15-41 at a time (`1bb814c`: 41 entries from 15
shards) with no corresponding prune step in the same workflow, so mechanizing the *append* half accelerated
growth without any matching pressure on the *cleanup* half of the same stated process.

### 4. The file has grown large enough to break the "check before you act" premise it depends on

`docs/observations.md` is 610 lines / 274,615 bytes. This was hit directly during this analysis: a plain
`Read` of the full file exceeds the tool's token ceiling, and a subsequent unqualified `Read` was
auto-truncated to 200 lines by the platform's own size guard. The workflow's stated purpose — "skip
duplicates: don't log anything already tracked" (`docs/observations.md:15`), and 618 §3's read-path design
("check observations before fixing a pre-existing issue... reads the canonical file *and* the live shards") —
depends on an agent being able to cheaply see the whole file. At current size, a routine glance sees the
`## Rules` section plus roughly the first third of `## Inbox`, and misses the back two-thirds of `## Inbox`
plus the entire trailing `## Post-push handoff` section entirely, unless the agent already knows to paginate
with an explicit offset.

### 5. Entries do not follow the file's own "one line" format rule, which is the proximate cause of Finding 4

`docs/observations.md:13` states: "One line is enough." Measured across the 224 current `## Inbox` entries:
median length 422 characters, mean 489, max 1,731; **81 of 224 (36%) exceed 500 characters**. A meaningful
share are full incident write-ups with root-cause analysis, verification steps, and resolution history folded
into a single checklist bullet (e.g. the `claude-code#68619` subagent-recursion mitigation entry at
`docs/observations.md:32` runs to several hundred words). The file has drifted from a lightweight triage inbox
into a growing set of embedded postmortems formatted as checkboxes.

### 6. Duplicate rot is real and self-admitted

Tempdoc 618 itself records: "the stale `tempdoc-status-check` observations rows (#241/#400/#415/#515/#522) are
now obsolete" (618:1469 in its final-state section) — the same underlying issue logged five separate times
across the file's history before anyone noticed. This is the predictable consequence of Finding 4: the
"skip duplicates" rule cannot be followed by an agent that cannot cheaply see the whole file to check.

### 7. The formal-promotion pathway is essentially unused relative to inbox volume

`docs/observations.md:19` names the escape valve: "Promote to `docs/reference/issues/` if an observation needs
formal tracking with code evidence and severity." That directory holds exactly 8 files
(`backend-tech-debt.md`, `decisions.md`, `documentation.md`, `gpu-detection.md`, `installer.md`,
`search-accessibility.md`, `ui-ux.md`, `README.md`), last touched 2026-06-25. The inbox alone (excluding
`## Post-push handoff`) holds 224 entries. Almost nothing graduates from the informal, noncanonical pile into
the structured registers that exist for exactly this purpose — findings live and (sometimes) die inside the
one sprawling file instead.

### 8. Scope has crept past "behavioral bugs users hit"

`docs/observations.md:14` states: "Skip structural commentary... unless it directly caused a problem." The
trailing `## Post-push handoff (2026-05-18)` section (`docs/observations.md:266-*`) is itself evidence this
isn't followed: it is a month-old, partially-resolved dump of process/task-management notes — "decide whether
to merge, finish, or remove three worktrees," "reconcile tempdoc number collisions," "finish an in-flight
citation-rule cleanup" — rather than user-facing behavioral bugs. It also was never folded away or renamed
once several of its items resolved; it just sits under its original stale dated heading as a second,
uncurated pile parallel to `## Inbox`.

## What is working

The shard write/fold *mechanics* themselves are solid. `note-observation.test.mjs` and
`fold-observations.test.mjs` cover session-id resolution and its fallback chain, entry formatting, per-session
shard isolation, exact-line dedup, idempotent re-fold, and shard-survives-a-failed-delete recovery (21 tests,
all passing per 618's validation record). The concurrent-multi-agent-clobber failure mode 618 was built to fix
does appear closed at the mechanical level — no evidence of a repeat since Seam C shipped.

## Net assessment

618 solved *write contention*. It did not close the loop from "note written" → "shard committed" →
"shard folded" → "resolved entries pruned" / "graduated to a formal issue" — each handoff in that chain is
still a manual step nothing prompts or enforces, and the destination file's own unenforced growth has now
outpaced the tooling's ability to let an agent cheaply consult it, which undermines the duplicate-avoidance
and pre-existing-issue-check purposes the whole workflow exists to serve.

## Theorization — possible directions (second pass, 2026-07-01)

This section is intentionally exploratory: it surveys ways to think about the problem and candidate directions
without selecting one. Nothing here is a proposal to implement. Per `ask-when-uncertain`, a choice among these
would need explicit user direction before any design work starts.

### Reframing the problem

The first pass described the findings as "lifecycle gaps" in one specific workflow. A few other lenses are
worth holding alongside that framing, because each suggests different fixes:

- **Write-ahead-log without compaction.** The per-session shards are, structurally, a write-ahead log: cheap,
  append-only, safe-by-construction entries. The canonical `## Inbox` is supposed to be the compacted view of
  that log. Every log-structured storage design (LSM trees, event-sourced systems, WAL+snapshot databases)
  pairs the append path with a compaction/garbage-collection process that runs on its own trigger — size,
  age, or a schedule — independent of whether any particular writer remembers to ask for it. Read this way,
  the missing piece isn't "someone forgot to prune"; it's that no compaction trigger exists at all, so the
  system is only half-built relative to the pattern it resembles.
- **Inbox-zero / collection-vs-processing.** In personal productivity systems, *capturing* an item is
  deliberately kept frictionless, and *processing* the capture (deciding: act, defer, delegate, discard) is a
  separate, scheduled ritual with an owner. This workflow nailed the capture half (that's what Seam C is) and
  never built the processing ritual — "curate periodically" is asked of nobody in particular, on no schedule,
  triggered by nothing. Under this framing the fix isn't about git mechanics at all; it's about assigning a
  cadence and an owner to the triage step, the same way any GTD-style system fails the moment "review your
  inbox weekly" has no calendar entry behind it.
- **Unowned shared mutable state.** Nearly every other shared, agent-written surface in this codebase names
  either an automated owner (a gate that rebalances a baseline, a hook that cleans up at session end) or a
  periodic ritual anchored to an existing event (merge teardown). The *reconciled* observations file has
  neither: nothing owns deciding when it gets folded, pruned, or mined for promotions. Under this framing the
  gap is a missing ownership assignment, not a missing script.
- **A queue without queue guarantees.** The workflow behaves like an at-least-once delivery queue (multiple
  producers, one eventual consumer) implemented entirely out of markdown files and git commits, without any of
  the guarantees a real queue would name explicitly: a retention policy (TTL / max depth), a dead-letter path
  for items nobody ever processes, and observable queue depth. None of those are unreasonable to want here —
  they're just not currently named, so nobody can tell whether the queue is "healthy" without reading the
  whole file by hand.
- **Accept it as a low-stakes scratchpad and stop asking more of it.** The honest alternative to all of the
  above: `docs/observations.md` is explicitly marked noncanonical and "allowed to drift." Perhaps the right
  response is not to add machinery, but to *narrow* what the workflow promises — stop treating "check the inbox
  first to avoid duplicates" as a real guarantee, and accept some duplication and drift as the acknowledged
  cost of keeping the capture step nearly free. This is a legitimate direction, not a concession — a
  frictionless capture tool and a reliable, queryable one are different things, and trying to be both without
  extra investment is how a design ends up serving neither well.

### Candidate directions (grouped by what they attack, not evaluated)

**Attacking the trigger (nothing currently fires the fold/prune step):**
- Anchor the fold to an existing event rather than inventing a new one — 618 already named merge-teardown
  (next to the existing merge-recording step) as the natural boundary; that proposal was never carried through.
- A size- or age-based hint (analogous to the existing hook-hint pattern used elsewhere in this repo) that
  surfaces once the shard directory or the inbox crosses some threshold, rather than requiring anyone to
  remember unprompted.
- A CI-driven fold. Worth naming the friction this would introduce: it implies an automated process writing
  back to a repo whose docs-publication conventions currently favor deliberate, human-authored history
  (tempdoc/observations edits are expected to ride along with the PR that motivated them, per the existing
  publication-granularity convention) — an automated bot commit is a different shape of history than that
  convention currently describes, and would need to be reconciled with it, not just bolted on.

**Attacking the format/retention contract (the file grows without bound regardless of trigger):**
- A structural cap on entry length, forcing detail to live in its natural home (a commit, a tempdoc, a linked
  doc) with only a pointer and a one-line summary in the inbox itself — closer to how the more disciplined
  entries already read in practice.
- An explicit staleness horizon: an entry unresolved past some age must be either promoted, re-affirmed, or
  dropped, rather than sitting inert indefinitely. Worth flagging a real risk here: this rhymes with the
  "wait for more evidence before deciding" pattern this project's own conventions warn against elsewhere — the
  difference (if any) is that a horizon *forces* a decision rather than deferring one, but that distinction
  should be checked carefully before treating it as a clean escape from that concern, not assumed.

**Attacking the storage model itself:**
- Move to a structured, machine-checkable format (e.g., one small record per line with a stable id, a status,
  and a first-seen date) instead of free-text checkboxes — would make "how many are stale," "is this a
  duplicate," and "how big is the backlog" mechanically answerable instead of eyeballed. The real tension: this
  project's own prior design reasoning (recorded in tempdoc 618) preferred a human-triaged, git-diffable
  markdown file over an opaque structured log precisely *because* a human checks items off by hand — moving to
  structured data trades that ergonomic for queryability, and that trade should be made deliberately, not by
  default.
- A middle ground: keep the free-text markdown entries, but require a small machine-parseable header per entry
  (id / date / status) without changing the prose format otherwise — cheaper to adopt than a full structured
  log, though it only partially buys back the queryability a fuller structural change would.

**Attacking the ownership/ritual (someone has to actually decide, not just run a script):**
- Treat fold+prune as a recurring, named chore with an expected cadence, the same way other periodic
  maintenance in this project is named rather than left implicit.
- Borrow the "propose, then a human accepts" shape already used elsewhere in this codebase for baseline
  changes: a script proposes candidate prunes/promotions and a person approves them, rather than requiring
  someone to notice the need and act unprompted in the first place.

None of the above is evaluated here for feasibility, cost, or fit — they are listed as the space of directions
worth having in view before a design is chosen.

### Hidden assumptions worth surfacing

- **That durability follows automatically from "the agent commits its own work."** This was 618's central
  assumption and it is not actually guaranteed by anything in the surrounding workflow (Finding 1) — worth
  treating as an assumption to test or replace, not a fact to build further on.
- **That the compliance failure is the problem, not the rule.** The "one line is enough" rule is violated by
  over a third of current entries, but many of those longer entries have visibly been useful to later readers
  (they record exactly the verification steps or root cause someone needed). It's possible the rule is wrong
  for the kind of content people actually want to capture, rather than people being wrong to violate it — in
  which case "enforce the rule" and "give long content a proper home and keep the pointer short" are different
  fixes aimed at different diagnoses, and it's worth being sure which diagnosis is correct before picking either.
- **That promotion to a formal issue register is the natural relief valve for volume.** Promotion requires
  exactly the scarce resource — someone's judgment about severity and evidence — that isn't available for
  routine triage either. Recommending "promote more" doesn't add that resource; it just relocates where the
  backlog would pile up next.
- **That the growth problem is about entry length.** Even strictly one-line entries would eventually hit the
  same wall at a large enough count; length is what made the wall arrive sooner, not the only thing that could
  cause it. Any fix aimed only at shortening entries should be checked against whether it actually bounds the
  file's size, or merely delays the same outcome.

### Does this point to a broader principle?

Tempdoc 618 named a system-wide pattern — *isolate-and-reconcile*: shared mutable state written by parallel
agents should be split into per-session shards and reconciled at a coordination boundary, never mutated in
place while shared. It applied that pattern to several existing surfaces in this codebase (worktrees,
governance changesets, agent-telemetry files, dev-stack session stamps) as precedent, and then extended it to
observations.

This tempdoc's findings suggest a second, complementary half to that principle, specifically about what
happens *after* reconciliation: **a conflict-free write path does not by itself bound the size of, or supply a
retirement discipline for, the destination it reconciles into.** Isolating writers solves contention; it says
nothing about compaction. If nothing separately answers "what shrinks the reconciled store, and what triggers
that," the store grows without bound regardless of how clean the writes into it are — which is exactly what
happened here.

Whether this is a genuine gap in the other isolate-and-reconcile surfaces 618 named, or whether those surfaces
already have a retirement discipline this analysis simply didn't check, is an open question — it was not
investigated as part of this pass and should not be assumed either way. If it turns out several of them share
the same gap, that would raise this from an observations-specific fix to a repo-wide pattern worth naming
explicitly (something like: *every isolate-and-reconcile surface needs an explicit, separately-triggered
answer to what retires or compacts the reconciled destination — not just a conflict-free way to write into
it*). That is a question for a future pass, not a conclusion of this one.

## Design (third pass, 2026-07-01)

### What already exists, investigated before designing anything new

Per the instruction to check for a usable existing design before adding one, three existing mechanisms in this
codebase were evaluated as candidate models for the missing half of the observations lifecycle (retirement):

- **The discipline-gate kernel** (`scripts/governance/`, tempdoc 530): per-item shards (`.changesets/<id>.md`)
  reconciled against a canonical baseline, with an explicit `--rebalance` operation that tightens the baseline
  when reality improves. This is the closest *shaped* precedent in the repo for "shard → canonical store → an
  explicit third operation distinct from ordinary writes." But it is the wrong artifact match: the kernel
  exists to CI-fail builds over numeric/structural ratchets (dependency counts, mutation-test strength,
  catalog-sync), and its changesets are never deleted — they persist forever, semantically retired (excluded
  from future counts once "at baseline") but not removed, which makes the kernel itself an archival mechanism,
  not a queue-shrinking one. Reusing it here would mean CI-gating an artifact the project has deliberately kept
  informal and human-triaged (docs/observations.md is explicitly marked "noncanonical, allowed to drift" —
  the same tier-register that documents the kernel also records, for a structurally similar problem, "'skipped
  item' detection would need NLP... stays prose" — i.e., this class of judgment call is already treated as
  intentionally un-mechanized elsewhere in this same codebase). **Rejected as a literal model; its shape
  (write-path plus a distinct, less-frequent structural operation) still informed the design below.**
- **The agent-telemetry aggregate log** (`tmp/agent-telemetry/events.ndjson`, currently several thousand lines
  and growing without any rotation). This looked at first like a second instance of "reconciled destination
  grows forever," but it is not a comparable case: the file lives under `tmp/` and is not committed to git, so
  its growth has no shared-history or shared-review cost, and it is written and read by tooling, not curated by
  humans. Unbounded local machine-log growth is a normal, low-cost shape; it is not evidence of the same defect
  class as a growing, committed, human-read markdown file.
- **`docs/reference/issues/`**, the sibling tier the project's own canonical documentation already names for
  the same domain. `docs/reference/contributing/development-philosophy.md` states the rule for that tier
  plainly: *"Resolved issues are deleted from the issue file, not marked closed."* Checking this against the
  actual files (`docs/reference/issues/*.md`, 8 files, 1,085 lines combined) found **zero** `[x]`-style
  resolved-but-kept entries — the convention is followed completely, with no gate, no hook, and no script
  enforcing it. This is a working, in-repo, zero-tooling proof that "delete on resolve" is achievable by
  convention alone for this class of artifact.

The same canonical document also states the corresponding (weaker) rule for the Inbox: *"Resolved observations
get checked off and periodically pruned."* That is a **two-step** version of the same idea — check it off now,
prune it later — and the two-step version is the one that has not been followed (Finding 3: 127 of 224 entries
checked off, none pruned). The one-step version, used one tier over, has a clean track record in this same
repository.

### The design

The design has two independent halves, matching the two lifecycle directions a shard-and-reconcile system has
— entries flowing in, and entries flowing back out — plus a durability fix that sits underneath both. All three
pieces extend mechanisms that already exist; none introduces a new tool, tier, or gate.

1. **Shard durability (write path).** Extend the existing hook-hint family (the same class of mechanism as the
   already-shipped `maintain-doc-hint`, `docs-granularity-hint`, `lockfile-hint`) with a check, at an existing
   session-lifecycle point, for an uncommitted shard belonging to the *current* session under
   `docs/observations.d/`. This reuses the session-id resolution `note-observation.mjs` already implements and
   the shared hook plumbing (`hook-base.mjs`) every other hint already uses. It is scoped to the current
   session's own file only, consistent with the existing rule against touching files another agent created.
   Like every hint-tier mechanism in this repo, it raises salience; it does not block, and does not claim a
   guarantee stronger than the rest of the hint tier already carries.

2. **Intake trigger (fold).** Wire `fold-observations.mjs` to the boundary tempdoc 618 itself already named and
   never connected: the existing merge-teardown step, where `record-merge.mjs` already runs per the documented
   merge workflow. This is completion of a previously-recorded, previously-approved design decision, not a new
   one — and it avoids the alternative of a CI-driven automated commit, which would conflict with this
   project's public-history convention that a docs change rides along with the human-authored change that
   motivated it, rather than arriving as a bot commit.

3. **Retirement (egress path) — the actual gap.** Align the Inbox's own resolution convention with the
   `docs/reference/issues/` tier's already-proven one: **when an observation is resolved, remove its line
   entirely instead of checking it off and leaving it in place.** The permanent record of the fix already lives
   where it belongs — the commit that made the fix, and, where the fix was substantial, the tempdoc that
   designed it — so the Inbox does not need to also serve as a permanent record once an item is resolved. This
   requires a small wording correction to `development-philosophy.md`'s current two-step phrasing (which invites
   the observed drift) so it states the one-step version already used successfully one tier over, plus the same
   correction mirrored into `docs/observations.md`'s own Rules section. As a light, optional backstop — not a
   replacement for the convention fix — `fold-observations.mjs` is a natural place to add a small additive
   report (not an automatic deletion) that surfaces resolved-but-still-present entries at the same
   already-scheduled fold pass, so the retirement judgment call has a prompted moment rather than depending
   purely on unprompted memory. This keeps both lifecycle directions anchored to the same trigger and the same
   tool, rather than adding a second script and a second boundary.

   This also resolves, as a side effect rather than a separately engineered fix, two of the first pass's other
   findings: the file-size problem (Finding 4) is addressed because a working set of only currently-open items
   is far smaller than an ever-growing archive of resolved ones (currently 97 open vs. 224 total), and much of
   the entry-length problem (Finding 5) recedes because most of the longest entries in the current file are
   exactly the resolved ones carrying post-mortem detail that would no longer accumulate. Duplicate rot
   (Finding 6) and promotion under-use (Finding 7) are also substantially a symptom of the same unpruned
   backlog, not separate defects needing their own machinery.

4. **The stray "Post-push handoff" section (Finding 8)** is the same failure mode — a declared-temporary
   holding area that was never actually retired — showing up a second time in the same file, for a structural
   reason (a leftover dated heading) rather than an unchecked box. It should be dissolved under the same
   discipline: its still-relevant items either become ordinary Inbox entries or get tracked in a proper
   tempdoc; it does not need separate machinery either.

### Directions considered and set aside

- **CI automation that commits the fold/prune result.** Conflicts with the existing public-history convention
  that docs changes ride along with the change that prompted them (tempdoc 653); would also need to solve
  attribution/authorship questions this repo has not needed to solve for any other docs surface.
- **Adopting the discipline-gate kernel wholesale for observations.** Wrong enforcement weight for an artifact
  the project has deliberately kept informal; see above.
- **Moving to a structured (NDJSON-style) log instead of markdown checkboxes.** Tempdoc 618 already considered
  and deliberately rejected this shape for this exact artifact, on the grounds that a human-triaged,
  git-diffable markdown file fits the committed/curated precedent better than an opaque log a human cannot
  check off by hand. Nothing in this pass's findings changes that trade-off; structured storage is not
  reconsidered here.
- **A hard entry-length ratchet.** Substantially mooted by the retirement fix (§3 above) once resolved entries
  stop accumulating; the residual concern for still-open entries is left as a prose norm, consistent with how
  this repo already treats comparably fuzzy judgment calls ("stays prose" is itself the recorded outcome for a
  structurally similar case in `.claude/rules/tier-register.md`).

## Reach: does this point to a broader principle?

**Naming it plainly.** An artifact that declares itself a transient, working store — an inbox, a queue, a
to-do list, anything whose own stated purpose includes "items leave once handled" — needs an actual, followed
exit path symmetric to its intake path. Absent one, it does not stay small by default; it defaults to behaving
like every *other* artifact in a codebase like this one, most of which are correctly permanent (tempdocs,
resolved-but-retained governance changesets, local telemetry logs) precisely because they are archives by
design. A declared queue with no enforced exit silently becomes an archive it never intended to be.

**How this relates to the pattern tempdoc 618 already named.** 618's isolate-and-reconcile principle is about
safe concurrent *writes* into a shared destination. This is a distinct, complementary concern about what
happens to that destination's *size* afterward — and it only applies when the reconciled destination is itself
a declared queue. It is not a correction to 618, and it does not apply to every isolate-and-reconcile surface:
this pass specifically checked the agent-telemetry aggregate log and the governance kernel's changesets, and
both are correctly archival (no fix needed, no principle violated) rather than a second instance of the same
gap.

**Checked scope, stated honestly.** A search of `.claude/rules/*.md` and the canonical `docs/reference/` and
`docs/decisions/` trees for other self-declared "prune periodically" / "clean up" language over a document or
register (as opposed to ordinary application-level cache/index pruning, which is a different concern and out of
scope here) turned up exactly one other mention — `development-philosophy.md`'s own restatement of this same
Inbox's rule, not a separate artifact — and one artifact that already conforms without being asked twice
(`docs/reference/issues/`). No other current violation of this principle was found in this pass.

**Conclusion on reach.** The principle is real and worth naming, but its current scope in this codebase is
exactly the one artifact this tempdoc is about. Per the instruction to separate recognizing a principle from
building structure for it, no repo-wide "declared-transience" check or gate is proposed here — that would be
structure built for a pattern observed exactly once. If a third self-declared-but-unenforced transient artifact
turns up later (the same rule-of-three bar tempdoc 618 applied to its own shard pattern, and tempdoc 646 applied
to its projection pattern), that would be the trigger to reconsider shared structure — not before.

## Research pass (fourth pass, 2026-07-01)

Before finalizing the design, three questions were checked against current external practice, scoped to the
parts of the design that touch genuinely fast-moving areas rather than settled fundamentals (log compaction,
write-ahead-log-plus-snapshot, and GTD-style inbox triage are decades-old and stable; no external check was
needed for those). **No external code, text, or third-party assets were copied or adapted into this repository
or its docs as part of this pass** — the checks below are informational orientation only, cited by link, not
material brought into the codebase; the license-and-notices concern this instruction raised does not apply
here.

- **Is "duplicate detection would need NLP, so it stays prose" (the repo's existing stance, `.claude/rules/tier-register.md`
  row 13) still the right call?** Checked against current practice: semantic duplicate detection over text
  backlogs using embeddings is now mainstream in production issue trackers — Linear's own engineering blog
  describes shipping exactly this, embedding each issue and searching for near-duplicates at creation time
  ([Using AI to detect similar issues](https://linear.app/now/using-ai-to-detect-similar-issues)) — and is an
  active academic area, from classical duplicate-bug-report-detection surveys
  ([Duplicate Bug Report Detection: How Far Are We?](https://arxiv.org/pdf/2212.00548)) through LLM-assisted
  approaches like Cupid and recent benchmark datasets combining detection, retrieval-augmented generation, and
  triage ([GitBugs](https://arxiv.org/pdf/2504.09651), [Mining Issue Trackers](https://arxiv.org/pdf/2403.05716)).
  **This refines, rather than overturns, Finding 6 / the earlier hidden-assumption note**: duplicate detection
  for this artifact is no longer *technically* out of reach — this project already runs local embedding models
  for search (`models/onnx/gte-multilingual-base/`), so a similarity check would not need new infrastructure,
  only a small script reusing an existing model. It remains **not worth building now**: the current open-entry
  count (97, and smaller still once retirement per §Design is followed) is far below the scale where Linear's
  own problem (large multi-workspace backlogs) justifies a dedicated index, and the duplicate rot actually
  observed (Finding 6, `tempdoc-status-check` rows) was a symptom of the unpruned-backlog problem this design
  already fixes, not an unrelated gap needing its own tooling. Recorded here as a **de-risked, cheap future
  option** — worth a look only if the open-entry count grows large enough on its own merits to make eyeballing
  unreliable again, not proposed as a next step.
- **Does anything in Claude Code's current hook system change the shard-durability design (§Design item 1)?**
  Checked against the current hooks reference and recent write-ups
  ([Hooks reference — Claude Code Docs](https://code.claude.com/docs/en/hooks),
  [Claude Code Hooks: Complete Guide to All 12 Lifecycle Events](https://claudefa.st/blog/tools/hooks/hooks-guide))
  — the hook system gained async and HTTP-callback hook types in early 2026, neither of which changes anything
  here. The check does sharpen which existing event is the right anchor: `SessionEnd` — described in current
  documentation as firing once when a session terminates, unable to block, and well suited to an "end-of-session
  check" — is the correct anchor for an uncommitted-shard reminder, not `Stop` (which fires every turn and would
  nag repeatedly during normal work). This project's own hook set already uses `SessionEnd` for exactly this
  shape of once-per-session cleanup (`compact-restore.mjs` removing its state file at `SessionEnd`, per
  `.claude/rules/hooks-reference.md`), so anchoring the new check there is a direct extension of an
  already-used, already-correct pattern rather than a new one.
- **Is the underlying isolate-and-reconcile approach (git worktrees / per-session shards) still the right family
  of solution, or has the wider field moved somewhere else?** Current sources describe git worktrees as "becoming
  the standard isolation mechanism" for parallel AI coding agents in 2026
  ([Git Worktrees for AI Coding](https://www.mindstudio.ai/blog/git-worktrees-parallel-ai-coding-agents),
  [How to Run a Multi-Agent Coding Workspace](https://www.augmentcode.com/guides/how-to-run-a-multi-agent-coding-workspace)),
  and merge conflicts from parallel agentic changes are now enough of a recognized problem to have a dedicated
  research dataset ([AgenticFlict](https://arxiv.org/pdf/2604.03551)). This is external confirmation, not a
  design change: this repository's existing worktree-plus-shard approach is aligned with where the field has
  converged, not behind it, so nothing here motivates revisiting the write-path half of the design.

## Status (superseded by §Implementation below — kept as dated history of the design pass)

Design settled at a general level, refined by a scoped external-research pass; nothing has been implemented. No
code, hook, or documentation file has been changed as part of this tempdoc. Proceeding to implementation —
including the exact hook mechanics (anchored at `SessionEnd`, per the research pass), the wording change to
`development-philosophy.md` and `docs/observations.md`, wiring `fold-observations.mjs` to merge-teardown, and
cleaning up the "Post-push handoff" section — needs the user's explicit go-ahead first, per
`tempdoc-is-your-contract` and `ask-when-uncertain`.

## Implementation (fifth pass, 2026-07-01)

Implemented in worktree `665-observations-lifecycle`, following a plan approved via Plan Mode. Two corrections
to the design pass surfaced during investigation, before any code was written; both are recorded here rather
than silently absorbed into the earlier sections, since those sections are dated history of the design as it
stood before implementation.

### Correction 1: the design pass named the wrong hook event

The design/research passes (§Design item 1, §Research pass) anchored the shard-durability reminder at
`SessionEnd`, on the strength of external Claude Code hook documentation describing `SessionEnd` as suited to
"an end-of-session check." Re-reading this repository's own existing `SessionEnd` hook
(`scripts/agent-analytics/hooks/compact-restore.mjs`) before implementing showed it only ever performs silent
file cleanup and never emits `additionalContext` — because by the time `SessionEnd` fires, the session has
already ended and nothing reads the hook's output. A reminder the agent needs to *act on* (stage and commit a
file) has to fire while the session is still live. `maintain-doc-hint.mjs` already solves exactly this shape — a
non-blocking, once-per-session nudge on `Stop`, deduplicated via `stop_hook_active` plus a per-session marker
file — so the shipped hook (`observation-shard-hint.mjs`) follows that precedent instead. This is exactly the
class of mistake the `wrong-gate` discipline (`docs/reference/contributing/agent-postmortems.md`) names: trusting
that a symbol/event exists and fits, rather than checking the set-site/precedent in the target codebase.

### Correction 2: "Post-push handoff" was not what Finding 8 assumed

Finding 8 (first pass) characterized `## Post-push handoff (2026-05-18)` as a small, stale, mostly-resolved
dump of process notes, based on a ~25-line sample. Reading the full section before implementing (345 lines,
lines 266–610 of the pre-cleanup file) found something different: genuine behavioral/bug-report entries running
from 2026-05-18 through at least 2026-06-21 — in parallel with `## Inbox`'s own entries (2026-05-05 through
2026-06-30) for most of that period, with 72 resolved and 262 open entries. The two sections were the same kind
of content, split by a historical accident (an agent once appended after a stray heading instead of under
`## Inbox`, and subsequent agents kept extending whichever section they found at the bottom of the file). The
fix implemented was **merge, not dissolve**, and deliberately mechanical: delete already-`[x]`-resolved lines
(safe, syntactic), relocate the remaining `[ ]` lines verbatim under `## Inbox`, and do **not** adjudicate
whether any of the 262 open items are still true on current `main` — that is a large, unrelated audit spanning
ui-bundle budgets, ts-any gates, wire-contract drift, and dead-code-gate parsing bugs, out of this tempdoc's
scope per `log-pre-existing-issues` / `stay-focused-on-assigned-work`.

### A live instance of the exact durability gap this tempdoc analyzes

The orphaned shard `docs/observations.d/5555b628-....md`, flagged as live evidence during the first pass, was
gone from the main checkout by the time implementation began — not folded, not committed, just no longer
present anywhere. Its two findings did not exist in `docs/observations.md` either: this was not a hypothetical
risk, it was the failure mode actually occurring, mid-investigation. The content was recoverable only because
it had been read verbatim into this session's own transcript earlier; it was reconstructed and folded in for
real via `fold-observations.mjs --apply`, exercising the exact trigger this tempdoc wires up. Had the content
not already been captured, it would have been lost permanently. This is stronger evidence for Finding 1 than
the tempdoc originally had, and it is the closest thing to a controlled demonstration that the fix is needed,
not merely theorized.

### What shipped

- **Retirement convention** — `docs/reference/contributing/development-philosophy.md` and `docs/observations.md`
  `## Rules` both now say to delete a resolved observation, matching the already-working
  `docs/reference/issues/` convention ("deleted, not marked closed"), instead of the two-step
  "check off, then prune later" wording that had gone unfollowed for 127+ entries.
- **One-time historical cleanup** — applied the new convention retroactively and merged the accidental second
  inbox. `docs/observations.md` went from 628 lines (610 at the start of this tempdoc, +18 from the recovered
  shard and wording edits) to 427: 199 resolved entries removed (127 from `## Inbox`, 72 from
  `## Post-push handoff`), the `## Post-push handoff (2026-05-18)` heading removed, and its 262 surviving open
  entries relocated verbatim into one unified `## Inbox`. Verified with a scratchpad migration script,
  cross-checked against a pre-migration backup (spot-checked start/middle/end, confirmed a multi-line
  continuation entry with indented sub-bullets survived intact, confirmed zero `[x]` and zero "Post-push"
  residue afterward). This does **not** solve the file-size problem outright — 377 open entries remain, a real
  backlog outside this tempdoc's scope — but it removes the structural duplication and the resolved-entry noise
  that made the file harder to scan than it needed to be.
- **Fold wired to merge-teardown** — `.claude/rules/branch-safety.md`'s Merge Workflow step 4 now runs
  `node scripts/agent-analytics/fold-observations.mjs --apply`, completing the boundary tempdoc 618 §P1.2
  proposed and never connected.
- **`fold-observations.mjs` gained a report-only stale-entry check** (`countStaleResolved`) — prints a count of
  any `[x]` entries still present in `## Inbox` after a fold, matching the design's explicit "report, not
  auto-delete" choice; 3 new unit tests.
- **`observation-shard-hint.mjs`** (new, `Stop` event, advisory/non-blocking) — reminds an agent once per
  session if its own shard is uncommitted, reusing `note-observation.mjs`'s session-id resolver and
  `maintain-doc-hint.mjs`'s dedupe-marker pattern. Registered in `governance/agent-hooks.v1.json` (the single
  wiring authority; `.claude/settings.local.json` is generated from it, not hand-edited, and is gitignored —
  every checkout regenerates its own), documented in `.claude/rules/hooks-reference.md`. No new
  `tier-register.md` row: confirmed other advisory hooks (`consult-doc-hint`, `governance-hint`) also have none,
  since this hook delivers an existing expectation rather than a new anchored rule.
- **Budget ratchet** — `.claude/rules/branch-safety.md` and `.claude/rules/hooks-reference.md` both bumped via
  the sanctioned `--bump --reason` mechanism (`branch-safety.md` was already over its ceiling from unrelated
  prior work; the bump reason cites both that and this tempdoc's addition honestly).

### Verification

`note-observation.test.mjs` (11), `fold-observations.test.mjs` (13, incl. 3 new), `observation-shard-hint.test.mjs`
(6, new) all green. `hook-integrity` gate: pass (new hook's wiring, cwd-invariant form, and load all verified).
`always-loaded-budget` check: pass after the two bumps. `prose-tier-register` gate: pass (no new rule anchors
added, confirmed unaffected). `./gradlew.bat build -x test`: `BUILD SUCCESSFUL`. No product UI surface was
touched (everything is under `scripts/agent-analytics/`, `.claude/rules/`, `docs/`), so no browser/dev-server
verification applied.

### Deliberately not done

Per the design pass's own reach judgment, no repo-wide "declared-transience" gate or check was built — the
principle is recorded (§Reach) but its confirmed current scope is this one artifact. Auditing the 262 open
entries carried over from "Post-push handoff" (or the 377 open entries now in `## Inbox` overall) for whether
they are still true on current `main` was explicitly out of scope and not attempted.

### Handoff — where this work lives, and one thing to know before relying on it

Committed on branch `worktree-665-observations-lifecycle` (worktree `.claude/worktrees/665-observations-lifecycle`),
commit `62a0248` plus a small follow-up commit folding one more observation found while closing out this
tempdoc (see below). Open a PR from this branch if one does not already exist by the time this is read; check
GitHub for its merge state before assuming any of this is on `main` yet.

**One caveat that matters for anyone verifying `observation-shard-hint` after this merges**: hook wiring is
generated, not automatic. `governance/agent-hooks.v1.json` is the tracked, committed authority, but
`.claude/settings.local.json` (what Claude Code actually reads) is gitignored and generated per-checkout by
`node scripts/codegen/gen-agent-hooks-wiring.mjs`. Merging this tempdoc's manifest change does **not** by itself
make the new hook fire in any *other* existing worktree or checkout — each one needs that command run once
locally first. This is not new to this tempdoc (every hook in the manifest has always worked this way, and
there is no regen-reminder hook for manifest edits, unlike `lockfile-hint` for `build.gradle.kts` or
`docs-regen-hint` for canonical docs) — it was simply re-discovered while wiring this hook, and is now logged
as its own one-line entry in `docs/observations.md`'s Inbox so it isn't forgotten as a separate, unrelated gap.
Do not assume `observation-shard-hint` is "live everywhere" post-merge without checking this.

**Git state at session end**: the worktree is clean (nothing uncommitted). The main checkout has pre-existing
ambient state unrelated to this tempdoc — a modified `gradlew.bat` and several untracked `models/**/*.onnx`
files — present before this session started and not touched by it; left as-is per the rule against altering
files in the main checkout that this session did not create.
