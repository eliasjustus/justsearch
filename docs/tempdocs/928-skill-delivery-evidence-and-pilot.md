---
title: "Skill delivery evidence before skill slimming"
type: tempdocs
status: implemented
created: 2026-09-04
updated: 2026-09-05
lane: agent tooling / skills / observability
related:
  - 620-always-loaded-agent-doc-audit-and-prose-to-infrastructure
  - 743-workflow-reconsideration-program
  - 841-agent-prompt-cache-efficiency
  - 886-agent-token-efficiency-review
  - 920-codex-cli-dual-harness-migration
  - 922-manual-codex-skill-ownership
---

# 928 — Skill delivery evidence before skill slimming

## Goal

Make the current skill-delivery failure mode reproducibly measurable before changing the
skills themselves. The first slice inventories the checked-in skill surface and audits Codex
rollout transcripts for read attempts, exact delivery of the current skill snapshot, explicit
tool-output truncation, intentional partial reads, and ambiguous batching.

This is deliberately narrower than a skill-quality evaluator. It answers whether evidence
entered the tool result, not whether the model noticed, understood, or followed it.

## Why this precedes content changes

The user-provided Deep Research report is specifically an independent review of the
`capability-realization` skill. It recommends keeping that review lens with four narrow guards:
bound implied capabilities to concrete repository evidence; include fresh-agent activation,
setup, and recovery; treat absence of proof as unverified rather than a confirmed gap; and call
the discipline no-edit/non-destructive rather than ambiguously read-only. The current Codex and
Claude skill texts implement all four recommendations. The report does **not** establish a
general skill-slimming design. That separate guarded core-plus-reference hypothesis comes from
the repository's own history in tempdocs 620, 841, and 886 plus the local size/delivery evidence
below; it still does not establish which section can safely be removed from either
`search-quality` or `inference-runtime`.

The report's reasoning is useful but analogical: NASA IV&V/traceability, end-to-end testing,
MCP discovery-versus-call separation, and coding-agent review studies support the direction of
the skill, not its measured effect in JustSearch. The report explicitly acknowledges that no
empirical evaluation of this exact prompt exists. Its restraint is therefore correct: preserve
the small, abstract review lens and evaluate whether it catches real missed links over time;
do not expand it into a fixed seam catalog or score.

The local evidence exposes a prior blind spot. A selected skill can be read in the same shell
call as source files and diffs; when the combined result is capped, "skill selected" is not the
same fact as "skill delivered." Conversely, a historical result that does not contain today's
skill bytes may simply reflect an older revision. The reader must therefore fail closed.

## Current state at the first implementation

- `.claude/skills` is the editable source; `.agents/skills` is the generated Codex projection.
- The two register-backed skills dominate the skill body surface. `search-quality` is the first
  redesign candidate because its generated body is substantially larger than the ordinary tool
  result budget observed in Codex rollouts.
- The neutral ledger intentionally caps tool output at 65,536 characters and stores no raw
  content. That is correct for cost analytics, but insufficient for content-delivery proof.
- Codex rollouts preserve paired tool input/output. A narrow raw exchange reader is therefore
  warranted; it must not persist prompts, commands, or output in its report.

That implementation state was superseded by tempdoc 922 before this work was reviewed:
`.agents/skills` and `.claude/skills` are now independent, manually maintained Codex and Claude
authorities. The first reader's source/projection pairing is therefore no longer a valid model
of the repository.

## Capability-realization follow-up and theorization (2026-09-05)

The implemented reader is callable, privacy-safe at its report boundary, and conservative about
negative evidence, but three observed failures prevent treating its recorded baseline as a
reproducible capability:

1. A fixed `--since`/`--until` rerun changed from 199 files / 15,135 exchanges / 871 attempts to
   196 files / 14,826 exchanges / 859 attempts. File mtime is mutable when an old Codex session
   resumes, so the upper-bound selection can remove an entire historical transcript.
2. The inventory treats `.claude/skills` as source and `.agents/skills` as projection even though
   tempdoc 922 deliberately retired that ownership model. Current same-name files can differ
   materially and must be measured as independent harness surfaces.
3. "Checked-in" is inferred from directory presence. A locally present, untracked skill is
   therefore silently included in what claims to be a repository baseline.

### Alternatives considered

- **Transcript window:** file mtime is cheap but unstable. Session-first timestamp is stable for
  selecting a session, but later appended exchanges can still change a fixed-window result.
  Snapshotting the transcript at the upper bound and then filtering each exchange by its start
  event is stable for append-only rollouts and preserves the as-of-cutoff missing-output state.
  Immutable copied transcripts or a content-addressed manifest would survive deletion and
  retroactive rewrite too, but add a persistence workflow that this read-only slice does not yet
  justify. The honest claim is therefore event-time repeatability while source rollouts remain
  available, with source deletion/rewrite stated as a limitation.
- **Skill identity:** pairing same-name files makes cross-harness comparison convenient but
  invents an ownership relationship. The primary inventory unit should instead be
  `(harness, skill name, path)`. A later comparison can join same-name rows explicitly without
  calling either one the source.
- **Repository status:** filesystem enumeration answers what an agent can discover locally;
  `git ls-files` answers what is checked in. Both facts are useful and must be reported
  separately. Delivery evidence may target a locally present skill, while the reproducible
  repository surface counts only tracked files.
- **What transcript reads mean:** an exact `Get-Content` result proves bytes were returned by a
  tool, not that Codex's native skill loader selected the skill, nor that the model attended to
  or obeyed it. Native loading is not represented by a distinct event in the rollouts inspected
  so far. The tool-read audit remains valuable, but its name and prose must not imply native
  invocation telemetry.
- **Discoverability:** a component-table row is technically canonical yet weak for a fresh
  agent. A short task-oriented subsection linked through `docs/llms.txt`, plus a direct package
  command, gives humans and agents a route without adding an always-loaded instruction or an
  automatic gate.

### Research decision

An internet pass was warranted because Codex skill discovery and loading are product behavior,
not repository-owned facts. The official OpenAI skill documentation confirms that Codex builds
an initial catalog from each skill's name, description, and path, then loads the full `SKILL.md`
when selected; repository skills are discovered from `.agents/skills`, and same-name skills from
different locations are not merged. That supports an independent Codex inventory and reinforces
the need to keep catalog-description size separate from full-body size. No broader web research
is needed for Git tracking or rollout timestamp semantics because those contracts are owned by
this repository and the observed local transcript schema.

General principle: an evidence instrument must key identity to the current governed authority,
and every reproducibility claim must use an immutable—or explicitly stability-bounded—sampling
basis. Convenience joins and mutable metadata are not acceptable substitutes.

## Revised design

### Evidence classifications

For every tool exchange that names a locally present `.claude/skills/<name>/SKILL.md` or
`.agents/skills/<name>/SKILL.md` path:

1. `proven_full_current`: the normalized tool result contains the complete current contents of
   the named harness-specific file. This is positive proof for today's local snapshot.
2. `timestamp_indeterminate`: the paired output has no parseable event timestamp, so its
   eligibility at a fixed as-of cutoff cannot be proven even when its bytes match.
3. `tool_output_truncated`: the tool result carries an explicit truncation signal and does not
   contain the full current snapshot. This proves the result was capped, not that a particular
   skill section was necessarily omitted.
4. `partial_intent`: the tool call explicitly requests a window/range and full-current proof is
   absent.
5. `ambiguous_batched`: multiple target skills or multiple content-producing commands share one
   result, without stronger evidence.
6. `missing_output`: a read-like call has no paired result.
7. `unproven`: none of the above. This is not counted as failed delivery because historical file
   drift and unrecognised tool wrappers remain possible.

The ordering is asymmetric on purpose. Exact containment can prove a fact; absence cannot prove
the inverse. Newline normalisation is allowed, but fuzzy matching is not.

### Boundaries

- Codex only in this slice. Claude skill invocation is structurally different and already has
  partial ceremony analytics; claiming dual-harness parity from one parser would be false.
- Read-only. Human and JSON output contain aggregate counts and skill sizes, never raw transcript
  content, prompts, commands, or conversation text.
- Current-snapshot proof only. Historical Git reconstruction is deferred until the basic reader
  demonstrates value.
- No automatic gate and no skill rewrite. This is an evidence instrument, not a policy threshold.
- No composite "skill quality" score. Delivery, adherence, task outcome, latency, and token cost
  stay separate axes.

### Independent inventory authority

Enumerate `.agents/skills/*/SKILL.md` as `codex-cli` rows and
`.claude/skills/*/SKILL.md` as `claude-code` rows. Each row owns its own frontmatter, body,
explicit-only policy, current normalized text, and path. Do not use source/projection fields or
borrow one harness's description or body size for the other.

Parse frontmatter with the repository's established YAML dependencies. Preserve the directory
name as the filesystem identity, but validate Codex's declared `name` against it and diagnose
missing descriptions. A malformed or mismatched file must not be silently described as valid.

Run `git ls-files` once at the repository root and mark every `SKILL.md` and auxiliary policy
file as index-tracked, untracked, or unknown when Git status cannot be established. Compare the
tracked paths with `HEAD` separately. The report exposes current working-tree totals, index
membership, and the subset whose current skill/policy bytes match `HEAD`; it does not label
mutable working-tree bytes as historical Git-blob contents. Local delivery proof may still refer
to a clearly labelled untracked row because such a skill remains discoverable in the working
tree.

### Stable transcript sampling

The raw Codex exchange reader discovers both active `sessions/` and `archived_sessions/`. A
session id is not a safe file-deduplication key: resumed/forked histories can leave divergent
fragments with the same id. The reader therefore snapshots every fragment at `--until`, pairs
calls and outputs within it, unions exchanges by session id plus call identity/start time, and
counts identical or conflicting copies. It retains exchanges whose **start event** lies
inclusively inside the requested window. This choice makes a fixed upper-bound report invariant
when the same rollout is later appended: an output arriving after the cutoff cannot rewrite the
call's as-of-cutoff `missing_output` state. Calls with missing/invalid start timestamps are omitted
and counted. Undated outputs are retained but explicitly classified as
`timestamp_indeterminate`, because discarding them would turn a returned result into a false
`missing_output` claim.

The reader reports files and sessions that contributed in-window exchanges rather than using
file mtime as an evidentiary field. Moving or copying a rollout between active and archived
storage therefore does not double-count the sample. Equivalent ISO spellings are canonicalized
to epoch milliseconds before deduplication. Deletion and retroactive editing remain limitations;
conflicting copies of the same exchange are counted and quarantined rather than resolved by an
arbitrary longer/non-missing winner. Source-root availability is reported and the CLI fails
closed when neither active nor archived root is readable.

### Reuse and reach

Extend the Codex ledger adapter's raw, paired tool-exchange view rather than adding a second
rollout parser. The neutral `Call`/`ToolEvent` path keeps its existing mtime semantics and record
shape so unrelated cost readers do not change. The richer attribution reader alone gains
active-plus-archived discovery and as-of event-time filtering, matching tempdoc 886's boundary
between aggregate ledger data and analyses that need transcript content.

The shipped surface is one CLI, a direct package command, tests, and a task-oriented canonical
analytics-pipeline subsection discoverable from `docs/llms.txt`. It does not touch hooks, skill
content, harness registries, or runtime code.

### Broader reach and retirement

This design reaches beyond the original branch only where the evidence contract was already
wrong: native skill ownership, Git status, archived transcript discovery, and fixed-window
semantics. It deliberately does not generalize event-time filtering into the neutral ledger,
whose callers use different definitions of "in window."

The old Claude-source/Codex-projection fields, mtime-window wording, "largest source skills"
output, and first baseline are superseded and must be removed rather than preserved as a second
interpretation. The recorded first baseline remains as failed validation evidence, followed by a
new corrected baseline; it is not presented as a comparable trend because the population changes.

## Derisk result (2026-09-05)

The confidence-building pass tested the assumptions most likely to invalidate the revised plan:

- The fixed-window drift was reproduced against the current reader, so event-time sampling is a
  demonstrated requirement rather than speculative hardening.
- The local Codex store has 184 archived and 203 active sessions whose first timestamp falls in
  the recorded August-to-September window. Excluding `archived_sessions/` therefore omits a
  material part of the evidence population.
- There are 39 session ids present in more than one active/archive file. A content probe found
  only three exact duplicate pairs and 36 divergent groups (with either side sometimes longer),
  disproving the initial idea of choosing one file per session id. Exchange-level union and
  conflict accounting are required.
- The clean rebased worktree has 28 present and 28 Git-tracked skills in each harness tree. Git
  path queries therefore provide the intended checked-in boundary without inventing a registry.
- The existing exchange projection already records start and completion timestamps, so the new
  window can reuse observed fields and leave the neutral ledger untouched.

Remaining risks are bounded: rollouts may contain malformed or timestamp-less events; duplicate
fragments may disagree on the same call; and a historical file can still be deleted or edited.
The report will count each condition and state the last limitation rather than masking it.

**Implementation confidence: 8/10.** The design is now evidence-backed and locally testable, but
as-of pairing plus divergent-fragment union deserves a refute-first review. Recommended execution:
strongest-capability `gpt-6-astra` with high reasoning effort.

## Follow-up acceptance contract and implementation plan

- [x] In the raw Codex attribution reader, discover active and archived rollout fragments,
      construct an as-of-`until` view, filter exchange starts by event time, union duplicate
      exchange copies, and expose timestamp/copy/conflict coverage counts.
- [x] Prove with adapter tests that a fixed upper bound is unchanged after a later output is
      appended, an archived copy is not double-counted, divergent fragments contribute distinct
      exchanges, and timestamp-less exchanges are reported rather than silently sampled.
- [x] Replace the source/projection inventory with independent `codex-cli` and `claude-code`
      rows. Establish tracked status with one Git query, keep present/untracked rows visible, and
      classify delivery against the exact harness path that the tool input named.
- [x] Replace the schema-v1 aggregate and human wording with schema v2: per-harness present and
      tracked size totals, harness-qualified delivery rows, event-time scope, and explicit
      coverage/limitation fields. Delete the dead source/projection fields and mtime claims in the
      same change.
- [x] Add unit coverage for unequal same-name harness skills, tracked/untracked separation,
      exact-path proof, aggregate privacy, argument validation, and the direct CLI surface.
- [x] Add a direct `npm run analyze:skill-delivery -- ...` route and a task-oriented canonical
      documentation subsection. Update the analytics document description so regenerated
      `docs/llms.txt` exposes "skill delivery" to a fresh agent.
- [x] Run the corrected fixed-window audit twice in memory and require identical evidentiary
      aggregates. Record a new baseline as a population reset, preserving the old figures only as
      evidence of the retired reader's instability.
- [x] Run focused adapter/reader tests, the full agent-analytics suite, syntax and diff checks,
      skill/Codex governance checks, and the documentation-maintenance regeneration/check set.
- [x] Perform independent refute-first change review, tempdoc-fit review, and a fresh
      capability-realization pass; fix substantive findings before session closeout.

The adapter, inventory/report, and tests are logically separable, but they touch one schema and
must evolve atomically. Implementation delegation would add coordination risk, so no subagent is
planned before the independent review required by `review-changes`. There is no user-visible UI
and no dev-stack dependency.

## First implementation acceptance contract

- [x] Add a reusable Codex tool-exchange projection with paired full input/output and missing-
      output representation; preserve existing ledger behavior and output caps.
- [x] Add `scripts/agent-analytics/skill-delivery.mjs` with inventory, conservative path
      detection, evidence classification, aggregation, `--since`, `--until`, `--codex-home`,
      `--repo-root`, and `--json`.
- [x] Unit-test exact proof, CRLF normalisation, explicit truncation, partial intent, batching,
      missing output, current-snapshot drift, explicit-only inventory, and privacy of aggregates.
- [x] Run the reader against the local corpus and record the resulting limitations and baseline.
- [x] Document the reader in the canonical agent-analytics pipeline and run required doc
      regeneration/checks.
- [x] Run the focused tests, the complete agent-analytics suite, Codex skill parity/sync checks,
      and `git diff --check`.

## Deferred pilot

The next decision, after the reader is trusted, is a paired task pilot on `search-quality`:
current monolith, compact coherent core, guarded hybrid with deterministic section delivery,
unguarded references, and an always-loaded control. Outcomes must separately score selection,
delivery, required-rule recall, task correctness, latency, context tokens, and recovery cost.
Transcript-derived cases should include negatives (skill must not trigger), middle-position rules,
conflicting distractors, multi-file batching, and long-tail sessions near tool-output limits.

No content arm should replace the current skill before it is non-inferior on critical-rule recall
and task correctness. Savings alone are not an acceptance criterion.

## Superseded first baseline (2026-09-04)

Command, with a fixed upper bound so this implementation session cannot change its own baseline:

```powershell
node scripts/agent-analytics/skill-delivery.mjs `
  --since 2026-08-01 `
  --until 2026-09-04T10:00:00Z
```

- Inventory: 27 source skills, 615,688 characters (~153,922 chars/4 tokens), 5,720
  description characters, and four explicit-only skills.
- `search-quality` is 360,777 characters and `inference-runtime` is 111,592; together they are
  76.7% of the source skill surface in this checkout.
- Corpus: 199 rollout files in the mtime window, 179 matching-project sessions, and 15,135
  paired tool exchanges.
- The reader found 668 read exchanges naming a checked-in skill, producing 871 skill-path
  attempts in 133 sessions: 311 exact current-snapshot proofs, 137 explicitly truncated tool
  results, 242 intentional partial reads, 101 ambiguous batches, and 80 unproven results.
- `search-quality`: 229 attempts across 24 sessions, zero single-result current-snapshot proofs,
  and 53 explicitly truncated results across 18 sessions.
- `inference-runtime`: 49 attempts across 10 sessions, zero single-result current-snapshot
  proofs, and 15 explicitly truncated results across seven sessions.

This was not reproducible. The exact command rerun on 2026-09-05 produced 196 rollout files,
14,826 exchanges, 859 attempts, 308 proofs, and 131 truncated results because resumed transcripts
moved outside the file-mtime upper bound. These figures are retained as validation evidence for
the retired reader, not as a trend baseline.

These figures establish that selection and full single-result delivery are different events. They
do not establish that either large skill was never delivered cumulatively: agents often page
through it, and this first reader deliberately does not union multiple historical windows against
today's file. They also do not make the truncated-result subset causal: a batched result may have
contained the needed skill section before truncating later output.

## Corrected fixed-window baseline (2026-09-05)

The same August 1 through September 4 cutoff was rerun through schema v2. Two complete JSON runs
were parsed and compared in memory; the reports matched exactly. A final run after adding catalog
field accounting preserved all evidentiary counts.

- Native inventory: 28 present and Git-tracked skills in each harness. Codex has 617,523 skill
  characters, 5,913 description characters, and a 7,199-character lower bound for raw
  name/description/relative-path catalog fields. Claude has 620,557, 5,888, and 7,174
  respectively. Both have four explicit-only skills.
- Concentration: Codex `search-quality` is 360,794 characters and `inference-runtime` is 112,416;
  together they are 76.6% of the native Codex skill body surface. This supports prioritising
  those two for a guarded core-plus-reference pilot, not deleting their documentation.
- Corpus: 768 active/archive fragments existed as of the cutoff; 201 fragments contributed
  20,522 exchanges from 185 matching-project session ids. The audit found 820 targeted exchanges,
  1,071 harness-qualified skill-path attempts, and 142 sessions with attempts.
- Classifications: 67 exact current-file proofs (6.3%), 203 explicitly truncated results (19.0%),
  287 intentional partial reads (26.8%), 244 ambiguous batches (22.8%), zero missing outputs, and
  270 unproven historical/current mismatches (25.2%). These are tool-read outcomes, not native
  loader-selection or adherence rates.
- `codex-cli:search-quality`: 210 attempts in 24 sessions, zero exact current-file proofs, 48
  truncated results, and 112 intentional partial reads. `codex-cli:inference-runtime`: 51 attempts
  in 10 sessions, zero exact proofs, 16 truncated results, and 23 partial reads.
- Coverage diagnostics: both transcript roots were readable, zero sampled calls or outputs lacked
  timestamps, and no duplicate/conflicting
  exchange copies survived the project/time filters, even though the broader store contains
  divergent same-session fragments. The reader still performs union/conflict accounting.
- Usability: a cold scan of 786 physical fragments took roughly two minutes; the final cached run
  took about 90 seconds. This is acceptable for an on-demand audit, not for a hot-path hook or
  per-turn gate.

The initial catalog lower bound is especially important. It excludes framing, separators,
absolute path prefixes, personal/system skills, and plugin skills. It is already near the 8,000-
character fallback documented by OpenAI, so description concision and explicit-only policy are
discovery safeguards, not cosmetic cleanup. Body size is a different axis: progressive disclosure
keeps it out of the initial catalog, but a selected 90k-token skill can still dominate the task
context and manual tool-read paths visibly truncate it.

## First implementation verification

- `node scripts/agent-analytics/skill-delivery.test.mjs` — 11 passed.
- `node scripts/agent-analytics/lib/ledger/codex-adapter.test.mjs` — 29 passed.
- `node scripts/agent-analytics/lib/ledger/boundary.test.mjs` — 6 passed.
- `node scripts/agent-analytics/run-all-tests.mjs` — 66/66 test files passed.
- `node scripts/docs/llmstxt-generate.mjs --check` — 116 docs indexed, current.
- `node scripts/docs/skills-sync.mjs --check` — 27 Codex skills / 31 projected files; five
  generated skills from nine sources, current.
- Canonical-link, module-dependency, runtime-config-matrix, and nine Codex parity checks passed.
- `git diff --check` passed.

## Follow-up verification (2026-09-05)

- `skill-delivery.test.mjs`: 19 passed; `codex-adapter.test.mjs`: 34 passed;
  `boundary.test.mjs`: 6 passed. Node syntax checks and `git diff --check` passed.
- `run-all-tests.mjs`: 65/66 test files passed. The sole failure was the registered
  `world-state.test.mjs` wall-clock condition: all three real-CLI probes exceeded their 15-second
  subprocess limit. A direct `world-state.mjs --json` run exited zero but took 59.623 seconds
  while enumerating 43 registered worktrees. The subject under change passed both focused and
  boundary tests; no timeout, assertion, or test was weakened.
- Documentation maintenance passed: `llmstxt-generate --check` (119 docs), `skills-sync --check`
  (five generated Claude skills / nine sources), canonical links (160 files), canonical module
  dependencies, runtime-config matrix, and Markdown lint for the edited canonical/tempdoc files.
- Agent/governance checks passed: eight Codex parity checks, workflow triggers, 636 distinct
  tempdoc numbers across 43 worktrees with no collision, agent-instruction sync, and the
  always-loaded budget (62,920 / 63,084 bytes).
- Prompt-surface inventory found 163 surfaces and no suspicious stale tokens. It independently
  confirmed `search-quality` and `inference-runtime` as the four largest harness-specific skill
  surfaces.
- Two complete fixed-window schema-v2 reports matched exactly in memory. The final baseline run
  after catalog accounting preserved the 20,522 exchanges, 1,071 attempts, and all classification
  counts recorded above. The post-review final run additionally recorded the `justsearch` project
  pattern, two available transcript roots, zero missing/error roots, zero indeterminate timestamps,
  and zero duplicate/conflicting copies for this cutoff.

## Independent review outcome (2026-09-05)

The required refute-first review did not merely affirm the implementation. It found six material
problems, all fixed before closeout:

1. Schema v2 omitted the user-supplied project regex, so two reports could look comparable while
   selecting different projects. `scope.projectPattern` now records it and a unit test asserts it.
2. Divergent copies selected an arbitrary longer/non-missing winner. Equivalent timestamp
   spellings are now canonicalized to milliseconds and genuinely conflicting identities are
   quarantined, with adapter regressions for both cases.
3. "Tracked" totals used current working-tree bytes without saying whether they matched Git.
   Inventory now separates present bytes, index membership, and current rows matching `HEAD`.
   A read-only run against the dirty main checkout proved the distinction: each harness had 29
   present, 28 index-tracked, one untracked, 27 matching `HEAD`, and one modified tracked skill.
4. An undated output could be discarded and turn a returned result into `missing_output`.
   Undated outputs are retained and classified `timestamp_indeterminate`; undated starts remain
   omitted and counted.
5. The first hand-written frontmatter/policy parsers did not implement YAML semantics. The reader
   now uses the repository's existing `gray-matter` and `js-yaml` dependencies, validates Codex
   `name` against its directory, and reports invalid metadata.
6. A nonexistent Codex home silently produced a zero-activity report. The adapter exposes
   source-root diagnostics and the CLI now fails closed when neither rollout root is readable.

A final source-specific check then caught a seventh implementation bug introduced during those
fixes: Codex skills without the optional `agents/openai.yaml` policy were incorrectly marked as
not matching `HEAD`. The comparison now ignores an absent optional policy while still treating a
present untracked or modified policy as divergent, with a dedicated regression test.

The subsequent tempdoc-fit pass found the implementation aligned with the revised contract and
found no remaining content fix. The original source/projection model and file-mtime baseline stay
clearly superseded rather than being mixed into the new population.

## Capability-realization result (2026-09-05)

The implemented capabilities are connected through their intended paths:

- `npm run analyze:skill-delivery -- --help` reaches the direct package command and exposes all
  event-time, repository, Codex-home, project-filter, and JSON controls.
- `docs/llms.txt` line 46 describes skill-delivery evidence and routes a fresh reader to the
  task-oriented canonical section in `docs/explanation/21-agent-analytics-pipeline.md`.
- The fixed-window command exercises both active and archived stores and emits the privacy-safe
  aggregate; focused tests inspect the JSON to prove no raw command/output text survives.
- A dirty-main inventory demonstrates real working-tree/index/`HEAD` separation without modifying
  user-owned work. A temporary nonexistent Codex home exits one with a specific diagnostic.

The named `capability-realization` skill itself is not a slimming priority. Its current files are
about 2.1k characters / 530 estimated tokens per harness, with a 191-character catalog
description, valid metadata, and matched content semantics across Codex and Claude. In this fixed
window the explicit tool-read audit found four Codex-path attempts (one exact, one partial, two
batched) and three Claude-path attempts (two exact, one partial), with no truncated or missing
results. Those observations support ordinary deliverability but still do not measure native
loader selection or prove that the review changed an outcome.

The capability is therefore **realized for on-demand inventory and tool-read delivery auditing**.
Two boundaries remain deliberate rather than broken wiring: there is no event that proves native
skill-loader selection or model attention, and a full uncached transcript scan is too expensive
for a hook/per-turn gate. The deferred paired pilot remains the next evidence-producing decision;
no current skill should be slimmed solely from these observational counts.

## Session retrospective

### Environment and tooling

- The first supposedly fixed report used rollout file mtime. Resuming an old session changed that
  mutable metadata and moved whole files across the cutoff. Event-time snapshotting fixed the
  evidence definition; future transcript instruments should reject mtime before collecting a
  baseline unless mtime itself is the subject.
- A full uncached active/archive scan initially took roughly two minutes, while later warm runs
  were about 17–90 seconds. That variability makes repeated exploratory scans expensive. A future
  tool improvement should add a content-addressed fragment manifest or an explicit metadata-only
  inventory mode, with provenance, rather than putting this reader on a hot path.
- `run-all-tests.mjs` was red only because `world-state.test.mjs` gives each of three real-CLI
  probes 15 seconds while this machine had 43 registered worktrees; the CLI itself exited zero in
  59.623 seconds. The subject tests remained green. This known wall-clock condition should be
  fixed in world-state's test strategy, not hidden by weakening unrelated assertions here.

### Prompt and process

- The user's staged prompts were broad but coherent; none required a wording correction. The
  downloaded artifact does not contain enough evidence to identify the exact platform-policy
  classification behind the earlier blocked Deep Research attempt, so that cause remains
  unverified. The important process correction was to treat the report as a hypothesis source
  and local transcripts/code as the decision evidence.
- Running every workflow skill produced useful independent checkpoints, but overlapping design,
  derisk, review-fit, capability, and retro prose can become ceremonial. Preserve their distinct
  decisions in one tempdoc and avoid restating unchanged background in each phase.
- The independent reviewer materially improved the result. In particular, it attacked zero-result
  and duplicate-conflict semantics that happy-path verification had missed. Preserve a refute-first
  review before trusting a new analytics baseline.

### Repository and documentation

- Work began from a branch that predated tempdoc 922 and therefore assumed `.claude/skills` still
  generated `.agents/skills`. Reading current ownership docs and rebasing exposed the mistake.
  Future skill analytics must inventory the two manually maintained harness authorities
  independently from the start.
- A hand-written YAML parser appeared sufficient until a live Codex block-scalar description
  disproved it. Reuse repository parser dependencies for governed formats; never infer semantics
  from a small fixture.
- The late report-specific check exposed that an optional policy path had been treated as required
  in `HEAD` matching. Exercise both presence and absence for optional companion files; a fixture
  that always includes the companion can conceal exactly this class of inventory defect.
- The canonical component table alone was not a strong discovery route. The task-oriented section,
  direct package script, and generated `docs/llms.txt` description are worth preserving.

### Verification gaps and remaining work

- This audit observes explicit tool reads, not native skill-catalog selection, attention, adherence,
  or task success. Those remain separate evidence axes, and the report states the limitation.
- Exact proof compares historical tool output with today's file; a non-match cannot distinguish an
  old complete revision from incomplete delivery. Historical Git reconstruction remains deferred.
- The paired `search-quality` pilot described above is the next substantive work. It must include
  negative trigger cases and required-rule recall; body-size savings alone cannot authorize
  slimming.
- The main checkout remained untouched and contains unrelated user-owned modifications and
  untracked work, including the inference-runtime pair, a blast-radius skill pair, several other
  tempdocs/models/eval artifacts, and local Claude settings. They are not evidence owned by this
  branch and must not be cleaned, staged, or interpreted as part of tempdoc 928.

Recommended changes are straightforward: keep documentation-as-skills as the default model; trim
catalog descriptions before bodies; pilot guarded core-plus-reference structure for the two
dominant skills; add native-selection telemetry if the product exposes it; make future evidence
windows event-time based; and repair the world-state wall-clock test independently.

## Session closeout

- Implementation commit: `9254cecd` on local branch
  `codex/928-skill-delivery-eval`. No push, pull request, merge, or publish action was authorized
  or performed.
- `node scripts/agent-analytics/world-state.mjs` exited zero at
  `2026-09-05T10:03:08.128Z`. The worktree row was clean, `ACTIVE`, unpushed, 299 commits ahead
  and 22 behind its configured comparison ref. This is not a `DIRTY-IDLE` or
  `STRANDED-FINISHED` verdict; the divergence is recorded rather than modified during closeout.
- `node scripts/dev/agent-spawn-sweep.cjs --occasion session-closeout --session-id
  01a06b47-958f-7c11-b1ac-669d9feabddb` deleted nothing: it left one other-session `ui-shot`
  process as live-lease contention and reported the ownerless `otlp-sink` singleton, as designed.
- The first commit attempt encountered a zero-byte worktree `index.lock`. No Git process was
  running; the exact lock was removed after its absolute path and age were inspected, and the
  subsequent explicit-path commit passed the gitleaks hook.

There are no unverified implementation assumptions hidden behind the completed checklist. The
known suite-level world-state timeout, native-loader telemetry gap, historical-revision ambiguity,
source-retention limitation, and deferred paired pilot are all recorded above. A successor can
reproduce the current result with the documented npm command and fixed event-time window without
access to this private conversation or the downloaded research artifact.
