---
title: "Observations channel, long-term design: the inbox is a peripheral-signal CHANNEL (many scoped writers → one consumer), and a channel is only complete with identity, derived status, routed destinations, and a named consumer — free-text append + egress-by-convention cannot deliver its own contract (measured: the 665 convention fired zero times while the store quadrupled). Design: keep 618's write path verbatim; add grouping at the fold (recurrence becomes the ranking signal, not a rule violation), probe-derived retirement (propose-then-accept), kind-routing to already-existing owned destinations (issues/, baseline-style expected-state, lesson delivery, owning tempdocs), and a named periodic consumer. Conforms to the canonical-authority-and-projection seam (622/623/625/646) — the inbox's 'still true / seen N times' state becomes a projection of evidence, not asserted prose. Design-recorded; supersedes the egress half of 665 on post-implementation evidence; write-path mechanics unchanged."
type: tempdocs
status: "IMPLEMENTED 2026-07-06 (§As-built, worktree 680-observations-channel — every design move, both absorbed items, and all five orphan teardowns shipped and validated; not yet a PR). The fork was resolved to option A (graded channel) by the implementation go-ahead; the fallback-to-scratchpad condition stands as designed. Earlier same-doc history: design settled + research pass + confidence pass 2026-07-05. Same-day targeted §Research pass: harness-native auto memory verified as NOT displacing the channel (machine-local, per-user, untriaged) but adding a named fork risk to the lessons lane; the field's shared-memory-layer convergence recorded as a deliberate, reasoned divergence; quarantine-list practice confirms the expected-state lane and adds an exit-discipline requirement to its pins. Same-day §Confidence pass: both falsifiers tested against the live inbox — identity model holds (77% mechanically anchorable; transitive merging refuted, propose-and-confirm adopted), probes strongly validated (7/7 writable, 2/7 groups already stale incl. a red-test group that now passes), delivery seam and blast radius confirmed small. Ready for an implementing session."
created: 2026-07-05
updated: 2026-07-06
author: "agent design-theorization pass (live repo inspection: docs/observations.md working-tree state, docs/observations.d/, scripts/agent-analytics/{note-observation,fold-observations}.mjs, docs/reference/issues/*, docs/reference/contributing/development-philosophy.md, .claude/rules/hooks-reference.md; full read of tempdoc 665 incl. its theorization/design/kill-list, tempdoc 646, tempdoc 618's Seam C record via 665's citations + branch-safety.md)"
category: agent-workflow / knowledge-lifecycle / observations / dx
related:
  - 665-observations-inbox-workflow-lifecycle-gaps   # direct predecessor — audited the same workflow, shipped the lifecycle fixes whose central egress bet this design supersedes WITH its post-implementation evidence; its write-path and trigger wiring are kept
  - 618-agent-developer-velocity-friction            # Seam C — the per-session shard + fold write path this design keeps verbatim; also the origin of the isolate-and-reconcile principle this design completes on the consumption side
  - 646-event-sourced-tempdoc-current-state          # sibling instance of the same seam (derived-not-asserted current state) AND the genre model for this doc: design recorded, machinery deferred to a real trigger
  - 653-public-main-history-hygiene                  # rules out CI-bot-commit folds; the routing/janitor outputs must stay human-ridden per its ride-along convention
  - 681-instruction-layer-rebaseline-relocate-compress-measured-delete # 2026-07-06 — owns the always-loaded rule-layer re-baseline; file-level coupling on the writer-facing rules text + subagent-guide brief (see §Coordination)
---

> **Design-recorded, not implemented.** This tempdoc settles the correct long-term design for the
> observations channel and names exactly what that design supersedes. It deliberately stays at design
> level (shapes, contracts, destinations), not implementation level (schemas, flags, exact scripts).
> Every quantitative claim below states its measurement method and is reproducible from this repo alone.

# 680 — Observations channel: grouped, evidence-derived, routed, consumed

## The idea this designs for

The agent workflow is a production system: many parallel, narrowly-scoped sessions (each under a
tempdoc contract, in a worktree, bound by `stay-focused-on-assigned-work`) and one human validator.
Scoped agents constantly notice true things outside their scope. The workflow needs those noticings to
neither become scope creep nor be lost. `docs/observations.md` is the organ for that — a
**peripheral-signal channel** — and its implied contract has three parties:

- **Writer**: any session, at near-zero cost ("one line is enough", "record and move on").
- **Store**: durable across parallel writers, deduplicated ("skip duplicates"), small enough to
  consult ("don't log anything already tracked").
- **Consumer**: someone who later processes each item — fix, promote to `docs/reference/issues/`,
  or delete (`resolve by deleting`).

The load-bearing finding of this pass: **the existing design specifies the writer meticulously and
never defines the consumer.** The Rules govern when to add, what format, what to skip. Processing is
owned by nobody, on no cadence, triggered by nothing. Tempdoc 665 saw this ("collection without
processing") and fixed the *plumbing* around it — shard durability hint, fold wired to merge-teardown,
a delete-on-resolve convention imported from the `issues/` tier — while deliberately declining
structure, betting that the `issues/` tier's zero-tooling convention would transfer.

## Evidence: the 665 bet has post-implementation data, and it failed

All measurements 2026-07-05, main checkout working tree, reproducible as stated.

- **Store size / egress.** `Select-String '^- \[ \]' docs/observations.md` → **460 open entries,
  0 checked-off**. 665's one-time cleanup (2026-07-01, recorded in its §Implementation) left ~97 open.
  Four days later the store is ~4.7× that (fold-imported backlog plus new intake — entries carry
  observation dates, so 341 of the 460 are dated pre-July), and the delete-on-resolve convention has
  produced **zero deletions** since it shipped.
- **Duplicate accumulation among OPEN items.** Counting entries mentioning one anchor string
  (`Select-String -Pattern <anchor> | Measure-Object`): `correction-eval-queries` **11**,
  `accent-as-text` **12**, `RecentsMenu` **8**, `VduEligibilityPdfFixturesTest` **7**,
  `HealthLitView` **6**, `TS5101|baseUrl` **6**. These are unresolved items independently re-logged by
  different sessions — which matters because 665's design predicted duplicate rot was "substantially a
  symptom of the unpruned backlog, not a separate defect." The post-cleanup data refutes that:
  duplicates accumulate among *open* items, because their cause is that checking is expensive and the
  same condition keeps being re-encountered.
- **Asserted status decays and misleads.** Entries at `docs/observations.md:167` and `:205`
  (2026-05-19/27) still assert, as current, that the dev-runner serves only the main checkout's
  build from a worktree — a state that shipped code has since changed (worktree-cwd resolution,
  tempdoc 606 era; current truth documented in `.claude/rules/branch-safety.md`). Nothing marks them
  stale; a reader taking them at face value plans around a constraint that no longer exists. The
  channel's rare readers are actively misled by it.

## Diagnosis: the design cannot deliver its own contract (structural, not compliance)

1. **The costs are inverted.** "Skip duplicates" asks every writer — dozens of sessions — to read an
   unbounded file so a consumer who doesn't exist has less to do. "Resolve by deleting" asks the
   *fixer* to delete a line written by a *different session weeks earlier*; the fixer doesn't know the
   line exists, because fixers don't read the inbox either. Both rules demand the expensive operation
   (reading) from the party the design promised it would be free for. The `issues/` convention works
   precisely because it lacks this property: there, the person deleting the entry is the person who
   was working *from* it. The transfer bet failed on exactly the property that differs.
2. **Recurrence is defined as noise when it is the channel's most valuable signal.** Seven sessions
   independently paying to rediscover the same red test is a measured tax ranking no human triage
   could produce as cheaply. The current rules ask writers to suppress it.
3. **One store flattens four artifact kinds with four different correct lifecycles.** The 460 entries
   cluster into: (a) **product defects** — lifecycle: until fixed; (b) **environment/baseline facts**
   ("test X is red on unmodified main", "this machine lacks tessdata") — lifecycle: until the state
   changes, and *machine-checkable*; (c) **workflow lessons** (recovery recipes, platform traps) —
   lifecycle: until mechanized into a hook or doc; (d) **deferred follow-ups** ("consider extending
   …") — lifecycle: until an owner decides. Free-text checkboxes give all four the same non-lifecycle.
4. **The dominant intake class shouldn't exist.** The largest single genre — "pre-existing red on
   main / red in this environment, not mine, verified via stash" — is not an observation; it is the
   verification tooling failing to answer "is this failure mine?", with every session paying a
   stash-and-verify ritual and then writing the answer somewhere the next session won't read. That
   fact's correct home is an expected-state record **consumed by the verification path itself** (the
   shape this repo already uses everywhere else: ratchet baselines, `class-size-exceptions.txt`,
   `KNOWN_UNREFERENCED` allowlists — a pinned expectation the check reads, so deviation-from-expected
   is what alarms, not raw redness). With that, the majority of current intake vanishes at the source.
5. **Status is asserted prose whose truth-value silently decays** (the `:167`/`:205` exhibit above).
   This is the repo's own asserted-vs-derived lesson (625/646) instantiated in the artifact meant to
   carry signal.

## What already exists, investigated before designing (extend / conform / keep / reject)

- **`note-observation.mjs` + `docs/observations.d/` shards (618 Seam C)** — KEEP VERBATIM. The write
  path is correct, tested, and contention-free; nothing here touches it.
- **`fold-observations.mjs` + its merge-teardown trigger + `observation-shard-hint` (665)** — EXTEND.
  The fold is the right single place for the new intelligence (identity, routing); the trigger wiring
  and durability hint stand.
- **`docs/reference/issues/` two-tier model** — CONFORM AND USE HARDER. It is the correct destination
  for defects; the design makes promotion the fold-time *default* for user-facing items instead of an
  exceptional act requiring scarce judgment (665 Finding 7: 8 files vs a 460-entry inbox).
- **Baseline/expected-state seam** (ratchet baseline files read by their own gates;
  `gradle/class-size-exceptions.txt`; test allowlists) — CONFORM. The environment/baseline lane is a
  new *instance* of this existing shape, not a new register species.
- **Propose-then-accept** (governance rebalance hints; 665's own report-only stale check) — CONFORM.
  All automated retirement below is proposal-only; a human applies deletions, consistent with the
  `issues/` precedent of human-applied removal and with 653 (no bot commits).
- **`consult-register.v1.json` / hook-hint delivery** — CONFORM. Workflow lessons route into the
  delivery pipeline (moment-of-relevance hints or `agent-lessons.md`), the repo's existing
  residence→delivery conversion, rather than aging as inbox prose.
- **Discipline-gate kernel as enforcement** — REJECT, same verdict and reasons as 665: wrong weight
  for a deliberately informal artifact. Nothing below CI-gates the inbox.
- **665's rejection of structured entries** ("a human checks items off by hand, keep pure markdown
  checkboxes") — SUPERSEDE, with cause: the premise is now empirically false. No human has ever
  checked items off at volume; the only successful cleanup (665's own) was script-assisted; the
  convention has since fired zero times against 460 entries. 665's own middle-ground option
  (markdown entries with a small parseable header) is adopted; its rule-of-three caution was right
  *then* — the bet it protected has since been falsified, which is the revisit trigger its own terms
  allow.

## The design

Target model, named plainly: the inbox today is an **event stream being treated as an issue list**.
The correct shape is the one error-monitoring systems converged on — cheap *events*, grouped by
identity into *conditions* that carry `seen ×N / first-seen / last-seen`, with resolution *derived*
where checkable and regressions reopening with history — expressed here entirely through seams this
repo already has.

1. **Writers write blind.** Delete the skip-duplicates rule. Capture stays one command, no reading
   required, ever. Re-observation is explicitly welcome: it is a vote.
2. **Identity lives at the fold.** The fold assigns each incoming entry to a group by a fingerprint
   (subject anchor — file/test/gate/command — plus symptom class) and merges re-observations into the
   existing group, incrementing `seen` and updating `last-seen`. Exact-line dedupe is subsumed.
   Recurrence count becomes the channel's ranking signal. (Anchor keying covers the observed duplicate
   classes, which cite literal file/test names; 665's de-risked embedding option remains the fallback
   if anchor keying proves too coarse — not built until then.)
3. **Status is derived where the claim is checkable.** A group may carry a *probe* — the command or
   condition that reproduces it. A janitor pass at an existing boundary computes still-true /
   no-longer-true; gone groups become **proposed deletions** a human applies. A re-observation of a
   resolved group reopens it with history (regression detection for free). Unprobeable groups are
   labeled as such — the store stops implying a truth-value it cannot back. This is the
   canonical-authority-and-projection seam applied to the inbox: "still true, seen N times, last seen
   date" becomes a projection of evidence, never hand-maintained prose.
4. **Route by kind at the fold; the inbox is a buffer, not a home.** Each kind has an owned
   destination and a dwell bound: defects → `issues/` / the domain registers; environment/baseline
   facts → the expected-state record consumed by verification tooling (killing intake class #4 at the
   source); workflow lessons → the delivery pipeline (hooks / agent-lessons / postmortems); deferred
   follow-ups → the owning tempdoc or register. A group that survives two processing cycles
   unprocessed moves to an explicit **parked** state with a reason — a dead-letter path — rather than
   silently aging into archaeology.
5. **The consumer is named and scheduled.** The channel's read model — new groups, top-N by seen
   count, proposed deletions, parked items — is the input to a periodic maintainer triage pass with a
   fixed cadence. This is the one element no mechanism can substitute for: a queue without a consumer
   is an archive in denial, whatever its tooling. The design's obligation is to make each consumed
   item cost seconds (claim + evidence + proposal), not archaeology.
6. **Minimal entry structure.** Entries gain a small parseable header (stable id, anchor, kind,
   first/last-seen, seen-count, optional probe) with prose free beyond it — 665's own named middle
   ground; still markdown, still git-diffable. Queue depth, age distribution, and intake rate become
   one script over the store instead of an unanswerable question.

**Scope-of-change note.** This is a redesign of the store's contract and lifecycle, not of the write
path, and it builds no new tool families: it extends one existing script (the fold), adds one janitor
pass anchored to an existing boundary, adopts one already-named entry format, and instantiates one
already-existing seam (expected-state baseline). The one-time migration of the current 460-entry
backlog into groups is **part of the implementing work, not a later sweep** (it is also the design's
first live test: if the 460 entries don't collapse cleanly into a much smaller set of grouped
conditions with kinds, the model is wrong — see §Falsification).

## The fork (the one genuine owner decision)

There are exactly two coherent designs, and the status quo is neither:

- **A. The graded channel (this design).** Worth it iff the named consumer (§5) actually exists —
  the machinery makes consumption cheap; it cannot make consumption happen.
- **B. The honest scratchpad** (665's "accept it" direction, taken seriously): declare the inbox
  lossy — TTL auto-expiry, no dedup promise, no check-first promise; anything that matters goes
  straight to `issues/`/registers at write time. Cheaper, lossy, honest.

The current design — queue promises on scratchpad mechanics — is the only indefensible option.
**Recommendation: A**, because the four destinations and the seams all exist already and the evidence
shows the signal is valuable (the recurrence data alone is a ranking the project currently pays for
and throws away). **Fallback condition, recorded now:** if, after implementation, two consecutive
months of read-model output go unconsumed, switch to B — apparatus without a reader is strictly worse
than honest lossiness, and keeping A alive in that state would be the exact spiral this repo's own
discipline warns against.

## What this design orphans (teardown belongs to the implementing work, not a later sweep)

1. **The "Skip duplicates" rule** (`docs/observations.md` Rules; mirrored in
   `development-philosophy.md`'s "Skip duplicates" clause) — deleted and replaced by "re-observation
   is welcome; identity is resolved at the fold."
2. **Delete-on-resolve as the *primary* egress** (665 §Design item 3; the wording it installed in
   `observations.md` Rules and `development-philosophy.md`) — demoted to what it demonstrably is, an
   opportunistic path; primary retirement becomes probe-derived proposals. The wording in both files
   changes accordingly.
3. **`fold-observations.mjs` exact-line dedupe and `countStaleResolved`** — subsumed by fingerprint
   grouping and derived-status proposals respectively; removed (not left as parallel half-mechanisms).
4. **The free-text entry format for OPEN items** — superseded by the keyed format; the one-time
   backlog migration is in-scope of implementation (see above). Historical/resolved content needs no
   migration — it is deleted or routed, per the design.
5. **665's structured-storage rejection** — superseded *as a decision*, with the falsifying evidence
   recorded here; 665 itself is dated history and is not edited (this tempdoc is its successor record,
   the same relationship 665 has to 618).

Nothing else 665 shipped is displaced: the durability hint, the merge-teardown fold trigger, and the
one-step-deletion *permission* all stand.

## Non-goals

Not a bug tracker or a kernel gate (the inbox stays noncanonical and un-CI-gated). No bot commits
(653's ride-along convention holds; all automated outputs are proposals a human rides along). No
NLP/embedding dedup up front (anchor keying first; 665's de-risked option is the named escalation).
No redesign of the write path, the shard mechanism, or the `issues/` tier itself. No new always-loaded
rules — the writer-facing rule set gets *shorter* (one rule deleted, none added).

## Falsification

This design is materially wrong if: (a) the backlog migration shows the 460 entries do NOT collapse
into a much smaller set of anchored groups (identity model wrong); (b) probes turn out writable for
only a trivial fraction of groups (derived status carries no weight — reverts the retirement story to
convention, i.e., option B); (c) grouped ranking doesn't change what gets fixed (recurrence signal
worthless); (d) the consumer never materializes (fallback condition above — switch to B).

## Reach

### Conforms to (instances of existing seams, not new ones)

- **Canonical-authority-and-projection** (622/623/625, 646's document-process instance): group status
  as a projection of evidence, never asserted. This design is that seam's *knowledge-channel*
  instance.
- **Isolate-and-reconcile** (618) — unchanged on the write side; this supplies the consumption side
  618 explicitly left open and 665 half-filled.
- **Expected-state baselines consumed by their own checks** (ratchet baselines, exceptions files,
  allowlists) — the environment lane is a new instance, not a new shape.
- **Propose-then-accept** (governance rebalance; report-only checks) — all automated retirement is
  proposal-only.
- **Residence→delivery** (620-era hint conversions) — the lessons lane.

### The principle, named plainly

**A cross-session channel is only a channel if it has: a named consumer with a cadence, identity
(grouping) for what flows through it, derived rather than asserted status, owned destinations per
kind, and a bounded dwell time. Absent any one of these, a declared channel degrades into an archive
that misleads its rare readers.** 618 named the write half (isolate-and-reconcile); 665 named the size
half (reconcile ≠ compact); this completes the contract on the consumption side. The property that
makes it non-optional here is the workflow's shape: many producers, one validator — conventions that
tax the validator's attention are exactly what this system cannot spare, so the channel contract must
be structural (a repo-local echo of the general structural-over-conventional lesson).

**Where else it would apply (candidate scope, recognized — NOT built now):**
- `tmp/agent-telemetry/events.ndjson` — written continuously, consumed by no standing read model
  (identity/consumer absent; it is local tooling state, so the cheap-archive reading may legitimately
  win there — the principle names the question, not the verdict).
- **Tempdoc `status:` fields** — asserted free-text current-state with no closure protocol; 646
  already records the derived-status design for exactly this, deferred on its own trigger. This
  design adds a second live instance of the same need; a third would meet 646's own rule-of-three bar.
- **The heavyweight domain registers'** delivery (opt-in skill loads for the largest payloads) — a
  consumer/cadence question, already partially tracked elsewhere; named here only as scope.

Per the recognize-vs-build discipline (646's precedent), none of these get generalized structure from
this tempdoc; the principle and scope are recorded so a future instance can conform instead of forking.

**What would show the principle earning its keep:** channels retrofitted to it stay bounded and
truthful over months (depth stable, stale-assertion exhibits like `:167`/`:205` structurally
impossible), and their recurrence data visibly redirects fixing effort; concretely for this instance —
duplicate-group growth stops, baseline-class intake collapses after the expected-state lane exists,
and triage cost per item drops to seconds.

**Retirement condition for the principle:** if a counterexample channel in this repo stays bounded,
truthful, and consumed for months on convention alone at comparable producer volume — or if two
retrofitted channels end up with their structured halves unused while staying healthy anyway — the
principle is over-claiming and should be demoted from "channel contract" to "symptoms checklist."
A principle without that exit would be one more self-justifying piece of apparatus, which is the
failure mode this whole design exists to avoid.

## Research pass (2026-07-05, targeted; informational orientation only)

Scoped to the design's genuinely fast-moving aspects — fleet memory channels and harness-native
memory — plus one cheap confirmation on known-failure practice. Stable foundations (error-monitoring
grouping semantics, queue/compaction theory) and the embedding-dedup question (covered by 665's own
research pass four days earlier) were deliberately not re-researched. **No external code, text, or
assets were copied or adapted into the repo or docs** — sources are cited by link only, so the
license-and-notices lane is not implicated.

- **Harness-native memory does not displace this channel — but it creates a fork risk the design must
  name.** Claude Code's auto memory (per the current official docs,
  [code.claude.com/docs/en/memory](https://code.claude.com/docs/en/memory)) is per-repository,
  **machine-local**, per-user, shared across worktrees, curated by the agent itself, with no
  grouping/lifecycle/triage and no team sharing. The observations channel's defining properties —
  repo-committed, fleet-and-maintainer-shared, human-triaged, provenance-carrying — have no native
  counterpart, so the build-vs-conform verdict stands: build (nothing to conform to). **Delta
  recorded:** the *lessons lane* now has a silent competitor — a workflow lesson can land in an
  agent's private auto memory instead of the shared channel, invisible to the fleet and drifting from
  `agent-lessons.md`. The design therefore states the boundary explicitly: shared-relevant lessons
  belong to the channel/delivery pipeline (the shared authority); auto memory is a private cache, not
  a destination. Convergence note, citable: the harness's own MEMORY.md mechanism (a size-capped
  index loaded every session, topic files read on demand) is the same bounded-read-model-over-
  unbounded-store shape as §Design item 5-6 — independent validation of the read-model move.
- **The field's converged fleet-memory pattern is a shared queryable memory layer with real-time
  multi-agent read/write and consolidation as a first-class operation** (e.g.
  [Mem0's 2026 state-of-memory report](https://mem0.ai/blog/state-of-ai-agent-memory-2026),
  [cognee on session persistence](https://www.cognee.ai/blog/guides/ai-memory-systems-persist-across-sessions),
  Letta/Graphiti-style graph memory). Consolidation/update-vs-add being first-class there
  independently validates identity-at-the-store (§Design item 2). **Divergence recorded with
  reason**, so a future pass doesn't "modernize" this into a retrieval layer without noticing the
  constraint: this repo's binding constraint is maintainer validation and public provenance, not
  retrieval — a git-committed, diffable, propose-then-accept store is the correct local answer even
  though the ecosystem's center of gravity is elsewhere. No convergence exists yet on repo-committed,
  human-triaged findings queues specifically; the niche remains bespoke.
- **Known-failure practice confirms the expected-state lane's shape and sharpens one semantic.**
  Current quarantine practice (e.g. [Harness's Test Analysis quarantine
  list](https://developer.harness.io/docs/continuous-integration/use-ci/run-tests/flaky-tests/) —
  a data file of entries with class name and start/end dates consumed by the pipeline;
  [Mergify](https://mergify.com/learn/test-quarantine) /
  [FlakyGuard](https://flakyguard.com/blog/how-to-quarantine-flaky-tests) on
  run-but-don't-block with tracked results and explicit return criteria) matches the lane's design:
  expected-state as data consumed by the check, deviation-from-expected as the alarm. **Delta
  recorded:** an expected-state pin must carry an exit discipline — an owner plus expiry, or a
  probe-based auto-exit (the pinned failure starts passing → propose removal) — because a pin without
  an exit condition is permanent baseline decay, the same failure mode §Reach's principle names for
  channels. This extends §Design item 4's dwell bound from the inbox buffer to the baseline lane
  itself.
- **Addendum (2026-07-06, workflow-review session): official confirmation of the poisoning risk
  class.** Anthropic's containment post ([How we contain Claude](https://www.anthropic.com/engineering/how-we-contain-claude),
  2026-05) names **persistent memory poisoning across sessions** as an emerging agent-risk class.
  That is a second, independent rationale for this design's propose-then-accept / human-applied-
  deletion stance: in a store many agents write and future agents read, a wrong (or poisoned)
  entry may *propose* state changes but must never silently become authority. Recorded so the
  stance reads as load-bearing, not merely conservative.

## Confidence pass (2026-07-05, same day — pre-implementation de-risking; no feature work)

Ran the design's own falsifier tests against the live inbox (throwaway scratchpad script + seven live
probes + seam/blast-radius reads). Measured, reproducible results:

- **Identity model (falsifier (a)) — holds, with one sharpening.** 356/460 open entries (77%) carry a
  mechanically extractable anchor (backticked path, test class, check/gate name). But naive
  *transitive* grouping (union-find over shared anchors) over-merges badly — one 83-entry hairball
  chained through shared file mentions, and a 33-entry `dev-runner.cjs` group conflating ~6 distinct
  conditions. **Design sharpened:** the fingerprint is (one primary anchor + symptom class), never a
  transitive closure; the fold *proposes* groups, the folding agent confirms/splits. Realistic
  distinct-condition count: roughly 150–250 from 460 entries.
- **Kind routing (script vs judgment).** Cheap keyword heuristics agree with a full-read hand
  classification on ~70% of a 40-entry sample (heuristic distribution: defect 206,
  environment/baseline 153, follow-up 79, lesson 22). So routing is propose-and-confirm, not fully
  mechanical — and the environment/baseline class being ~a third of the store confirms the
  expected-state lane removes a large intake share.
- **Probes (falsifier (b)) — strongly validated.** All 7 sampled groups were probe-writable as
  one-liners, all 7 probes discriminated, and **2 of 7 groups turned out already stale**: the
  dev-runner-serves-main entries (fix confirmed at `justsearch-dev-mcp/paths.mjs` `resolveRepoRoot`)
  and the `HealthLitView.test.ts` red-test group — which now passes 10/10 despite ~6 entries
  (re-logged as recently as three days prior) asserting it fails deterministically. A janitor would
  have proposed retiring ~10 entries today that the delete-on-resolve convention never touched.
  Still-true probes (correction-eval-queries absent, `models/*.onnx` untracked, theme-token-closure
  red, accent-as-text red, TS5101 typecheck break) all confirmed still-true — no false retirements.
- **Expected-state delivery seam — confirmed.** `pipe-mask-hint.mjs` is the exact existing template:
  a PreToolUse Bash advisory hook that receives the command text, pattern-matches load-bearing
  commands, and emits a non-blocking hint. A known-expected-state hint is a direct extension of this
  proven shape; no new mechanism family is needed.
- **Format blast radius — small and owned.** Only `note-observation.mjs`/`fold-observations.mjs` and
  their two test suites parse the entry format (all owned by this design's implementation). The
  `subagent-guide` injected brief needs a wording update in the same change; `observation-shard-hint`
  and `docs-granularity-hint` are path-level only; all other references are historical comments
  citing old entry numbers — which are *already* fragile under the current churn, and which stable
  group IDs would improve going forward.
- **Migration scriptability.** ~77% of entries get keyed automatically; ~104 unanchored entries plus
  hairball-splitting need judgment — one focused implementing session, in-scope per §Orphans.

## Coordination (2026-07-06)

Tempdoc 681 (instruction-layer re-baseline, created 2026-07-06) owns the always-loaded rule-layer
work. Coupling: 681 edits the same writer-facing rules text this design shortens and the same
`subagent-guide` brief-projection path this design's format change touches. If the two implementing
sessions overlap in time, coordinate file-level; otherwise whichever lands second rebases its
wording edits. This design's "one rule deleted, none added" posture is unchanged — 681 is where any
further rule-layer subtraction lives.

**Sensor dependency (recorded 2026-07-06, second pass):** 681's Move 3 (measured rule deletion)
uses the observations inbox as its recurrence sensor ("watch the inbox for the trimmed rules'
failure modes"). Against the current 460-entry ungrouped store that sensor is weak — recurrence of
a specific failure mode is exactly a fingerprint-group query this design provides. Sequencing
preference, not a hard block: this design's fold-time grouping lands before 681 begins Move 3
(681's Moves 1–2 are independent of it).

**Takeover assessment (2026-07-06, after surveying all documents edited 07-03..07-06):** this
design's implementing scope absorbs two small adjacent items and explicitly declines the rest.

*Absorbed (proposed — small, and both are things the implementation needs anyway):*
1. **First-consumer feedstock staging.** The one-time backlog migration (§Orphans item 4) already
   produces the first read-model view; the implementing session should stage it explicitly as the
   first periodic-triage input, so the named-consumer slot starts with a concrete, seconds-per-item
   worklist rather than an abstract obligation. (The habit itself is owner behavior and cannot be
   absorbed by any implementation — unchanged.)
2. **The posture-adjudication destination container.** A routed output class now exists with no
   destination: deliberate fail-open/posture decisions (a "softness portfolio" — owner-decided
   elsewhere, verified absent from the repo by grep 2026-07-06; a same-day closed analysis routed
   several adjudication candidates to it and is blocked on its existence). This is a *destination*
   in exactly this design's routing sense — a fifth-lane register for "deliberate posture, with a
   reason and a revisit condition" — so creating the small container (a short section in an existing
   reference doc, per its own decided shape; not a new register species) belongs to this
   implementation's routing work. The adjudication *content* stays with the owner/triage pass.

*Declined, with reasons (so non-takeover isn't read as ignorance of them):* the batched
stabilization repo-work items (heap measurement, upstream pinning, duplication-cluster collapse) —
product work, owner opens its tempdoc; contract-strictness / evidence-durability / census /
frozen-benchmark items — different domains with their own owners-to-be; a maintainer-private
prompt-library trim — deliberately parked behind an adherence baseline by its own record; the UI-audit and
engine-explainer tracks — separately owned and active.

**Reach evidence, one day in:** a same-day closed analysis document explicitly conformed to this
design's channel contract for its own close-out (treating itself as a burst producer, routing every
output to an owned destination, statuses living at destinations) — the principle's first
independent conforming instance, and its stated retirement condition already references this
design's fold-time routing. Two further candidate-scope instances appeared in maintainer-private notes
(small per-topic suspicion/open-question files) — recognized, not acted on.

665 (full: findings, theorization, design, kill-list, implementation record — this design keeps its
plumbing, supersedes its egress bet with its own post-implementation data); 618 (Seam C write path +
isolate-and-reconcile, via its rules-layer projection and 665's citations); 646 (the seam this
conforms to and the genre model for design-recorded-not-built); 653 (rules out bot-commit folds);
673's kernel-shape evaluation inherited via 665 (kernel rejected as enforcement weight here, same
verdict). Newer stubs 675–679 are eval-domain and do not touch this channel.

## As-built (2026-07-06, worktree `680-observations-channel` — all design items implemented)

**Shipped, per design move:**
1. **Blind writers** — `note-observation.mjs`/shards untouched; the skip-duplicates rule deleted
   everywhere it lived (observations.md Rules, `development-philosophy.md`, the `subagent-guide`
   brief), replaced by "re-observation is signal".
2. **Identity at the fold** — new `lib/observations-store.mjs` (grouped-store grammar, primary-
   anchor+symptom fingerprinting, occurrence merge, kind heuristics; 13 tests);
   `fold-observations.mjs` rewritten to fold shard entries into conditions (10 tests preserving the
   618 properties: crash-safe order, dry-run purity, idempotence, failed-delete recovery).
3. **Derived status** — `observations-triage.mjs --probe` janitor: probe exit 0 ⇒ writes
   `proposed-retire` (deletion stays human); live-verified against the real store (3 cheap probes,
   all correctly re-affirmed, zero false retirements; 6 tests).
4. **Kind routing + destinations** — kinds route per the Rules; the environment lane shipped as
   `expected-state.v1.json` (6 pins, each with an exitProbe or reviewBy — the research-pass exit
   discipline) delivered by the new `known-state-hint` PreToolUse Bash advisory (pipe-mask-hint
   template; 12 precision tests; registered in `governance/agent-hooks.v1.json` with a unit bite;
   wiring regenerated; hook-integrity gate green; runtime-probed firing and silent). The
   posture-adjudication destination shipped as `development-philosophy.md §Softness portfolio`
   (3 public-evidenced seeds).
5. **Named consumer feedstock** — `observations-triage.mjs` default mode is the read-model; first
   live run: depth 358 (343 open, 9 proposed-retire, 6 parked), top-by-seen 12×/12×/12×/9×/8×.
6. **Structure** — the one-time migration: 460 flat entries → 345 curated conditions (31 hand-merged
   cross-anchor clusters; 459 occurrences preserved; 1 parse artifact excluded), then the extended
   fold consumed 5 pending real shards (19 entries: 6 merged, 13 new) → 358 conditions.
   Re-fold verified idempotent.

**Orphan checklist (teardown rode along, per §Orphans):** (1) skip-duplicates rule — deleted in all
three homes ✓; (2) delete-on-resolve demoted to opportunistic path in both rule texts ✓; (3)
`insertIntoInbox` exact-line dedupe + `countStaleResolved` — removed from the fold ✓; (4) flat
`## Inbox` format — migrated; the fold now refuses (with a pointer) a pre-680 store ✓; (5) 665's
structured-storage rejection — superseded as a decision, recorded in this doc; 665 itself untouched
(dated history) ✓.

**Deviations, recorded honestly:** (a) kind confirmation is complete for all manually-curated and
multi-entry conditions; the ~300 singleton tail keeps fold-proposed `?` markers — incremental
confirmation at triage is exactly what the marker exists for, and bulk-stripping it would have
asserted review that didn't happen at depth. (b) No `hooks-reference.md` entry for the new hook:
that file is over its always-loaded budget and tempdoc 681 owns shrinking it; the hook's own
message self-documents (decision per 681's direction). (c) `.claude/settings.local.json` hook
wiring is per-checkout (gitignored); other checkouts pick the new hook up at their next
`gen-agent-hooks-wiring` run — the known regen gap is itself a condition in the store.
(d) Merge-time steps for whenever this branch is published: rebase, then run the fold once — flat
entries that accumulated on main fold into conditions by construction. (An earlier merge blocker —
the main checkout's untracked draft copy of this tempdoc, which git would refuse to merge over —
was RESOLVED 2026-07-07: the draft was hash-verified byte-identical to this branch's committed
baseline `c79c01d` and removed from main's working tree.)

**Validation:** all five suites green (store 13, fold 10, note 11, triage 6, hook 12);
hook-integrity gate green; live fold + janitor + read-model runs against the real store;
`check-tempdoc-numbers` green; full `gradlew build -x test` at close (see final commit).

## Session retrospective (2026-07-07 — future-agent-relevant residue; this doc stands without any private transcript)

**Where the work lives.** Branch `worktree-680-observations-channel` (worktree
`.claude/worktrees/680-observations-channel`), 8 commits on top of main `2ef7396`; no PR opened yet.
Exact validation battery, re-runnable from the worktree root:

```
node scripts/agent-analytics/lib/observations-store.test.mjs
node scripts/agent-analytics/fold-observations.test.mjs
node scripts/agent-analytics/note-observation.test.mjs
node scripts/agent-analytics/observations-triage.test.mjs
node scripts/agent-analytics/hooks/known-state-hint.test.mjs
node scripts/governance/run.mjs --gate hook-integrity --mode gate
node scripts/ci/check-tempdoc-numbers.mjs
./gradlew.bat build -x test -PskipWebBuild=true
```

**Verification evidence (closeout re-run 2026-07-07, from the worktree root — every claim below was
re-executed on the final branch state, not carried forward from earlier in the work):**

| Claim | Evidence (command → observed result) |
|---|---|
| Store lib correct | `node scripts/agent-analytics/lib/observations-store.test.mjs` → `13 passed` |
| Fold correct (618 properties preserved) | `node scripts/agent-analytics/fold-observations.test.mjs` → `10 passed` |
| Writer path untouched & green | `node scripts/agent-analytics/note-observation.test.mjs` → `11 passed` |
| Triage/janitor correct | `node scripts/agent-analytics/observations-triage.test.mjs` → `6 passed` |
| Hook precision | `node scripts/agent-analytics/hooks/known-state-hint.test.mjs` → `12 passed` |
| Hook registered/wired/bites | `node scripts/governance/run.mjs --gate hook-integrity --mode gate` → `hook-integrity: pass` |
| No tempdoc number collision | `node scripts/ci/check-tempdoc-numbers.mjs` → `OK — 392 distinct, 17 worktrees` |
| Janitor sane on real data | `observations-triage.mjs --probe` → 3 probes ran, 0 false retirements, 3 re-affirmed still-true; 3 expected-state exitProbes checked, 0 fired |
| Build green | `./gradlew.bat build -x test -PskipWebBuild=true` → `BUILD SUCCESSFUL` (asserted on output text) |
| Store live state at close | `observations-triage.mjs` → depth 361 (346 open, 9 proposed-retire, 6 parked; 320 kind-confirmations pending) — the As-built's "358" was the count at implementation time; two retrospective lessons + one follow-up were filed through the channel since |
| CLAUDE.md byte budget | `node scripts/ci/check-always-loaded-budget.mjs` → `ok CLAUDE.md 25050 / 25051 B` (was over on main; branch-safety + hooks-reference remain over — pre-existing, tempdoc 681's scope) |

**Unverified assumptions / deferred checks (each needs an owner-moment, not archaeology):**
1. **Live-session hook firing** — the `known-state-hint` wiring is generated into the gitignored
   per-checkout `.claude/settings.local.json`; gate-verified and stdin-probed, but an interactive
   session started AFTER the regen has not yet observed the advisory. Check: in a fresh session, run
   `npm run typecheck` and confirm the hint arrives. Other checkouts need their own
   `node scripts/codegen/gen-agent-hooks-wiring.mjs` run.
2. **`docs-validate.mjs` never ran against the new store format** — it crashes on a pre-existing
   malformed frontmatter in tempdoc 530 (itself a condition in the store). Assumption: the store's
   unchanged frontmatter keeps it valid once 530 is fixed.
3. **Full `gradlew test` suite not run** — no Java changed; assumption: markdown/scripts/json
   changes cannot break Java tests. `build -x test` (which includes `verifyGovernanceGates` per this
   repo's build wiring) was the gate.
4. **The ~320 singleton conditions carry fold-proposed `?` kinds** — deliberately unconfirmed;
   assumption: incremental confirmation at triage is sufficient and no routing decision depends on
   an unconfirmed kind before its triage.
5. **Expected-state match breadth** — the `gradlew test` regex fires on any gradle test command, so
   the VDU pin's claim may surface on unrelated test runs; assumption: advisory over-delivery is
   low-cost. Revisit if the hint reads as noise.

**Follow-ups filed as conditions in the store (not to be forgotten, findable via the read-model):**
the draft-commit hint hook proposal (rule-of-three met), the PowerShell BOM probe lesson, and the
frontmatter-essay survey lesson. First-triage-pass actions live in this section and §As-built (d).

**Known unrelated dirty work (do not attribute to this branch):** the MAIN checkout's working tree
carries other agents' state — untracked `models/*.onnx`, a modified `gradlew.bat`, and untracked
tempdoc drafts (681, 682). None of it is on this branch.

**CLAUDE.md byte discipline:** this branch's migration made four CLAUDE.md references to the old
`## Inbox` stale; they were fixed here at net-negative bytes (CLAUDE.md ends 1 B under its
always-loaded ceiling, vs over on main). Any wording change to always-loaded files must be
byte-budgeted (`node scripts/ci/check-always-loaded-budget.mjs`) and coordinated with tempdoc 681,
which owns that layer (rebase-second protocol per §Coordination).

**Lessons filed as conditions in the store** (via the channel itself): PowerShell 5.1 pipes prepend
a UTF-8 BOM to native stdin — probe hooks via `spawnSync` with the `input` option, never a PS pipe;
tempdoc frontmatter `status:` essays (e.g. 624's) make batch frontmatter surveys token-explosive —
truncate status fields when surveying (evidence toward 646's derived-current-state trigger).

**Process residue worth preserving** (what made this cycle cheap): the falsifier-driven confidence
pass before implementation — both design falsifiers were tested against live data for the cost of
one throwaway script and seven probes, and the implementation then hit zero design surprises; the
dry-run script became the migration tool (investigation artifacts reused as implementation); and
cross-tempdoc coordination via explicit coupling notes + a rebase-second protocol (§Coordination)
prevented collisions with two concurrently-opened tempdocs at zero merge cost. One trap to avoid:
tempdoc drafts left uncommitted on main are invisible to new worktrees (branch-from-HEAD covers
commits, not working-tree files) and can only be cross-referenced by editing an untracked file —
commit drafts early; they are dated working history and ride along per the publication convention.
