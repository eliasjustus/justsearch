---
title: "Skill delivery evidence before skill slimming"
type: tempdocs
status: implemented
created: 2026-09-04
updated: 2026-09-04
lane: agent tooling / skills / observability
related:
  - 620-always-loaded-agent-doc-audit-and-prose-to-infrastructure
  - 743-workflow-reconsideration-program
  - 841-agent-prompt-cache-efficiency
  - 886-agent-token-efficiency-review
  - 920-codex-cli-dual-harness-migration
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

The user-provided Deep Research report supports a guarded hybrid: keep a coherent task-ready
core while routing large supporting material deterministically. That direction agrees with the
repository's own history in tempdocs 620, 841, and 886. It does not establish which section can
be removed safely from either `search-quality` or `inference-runtime`.

The local evidence exposes a prior blind spot. A selected skill can be read in the same shell
call as source files and diffs; when the combined result is capped, "skill selected" is not the
same fact as "skill delivered." Conversely, a historical result that does not contain today's
skill bytes may simply reflect an older revision. The reader must therefore fail closed.

## Current state

- `.claude/skills` is the editable source; `.agents/skills` is the generated Codex projection.
- The two register-backed skills dominate the skill body surface. `search-quality` is the first
  redesign candidate because its generated body is substantially larger than the ordinary tool
  result budget observed in Codex rollouts.
- The neutral ledger intentionally caps tool output at 65,536 characters and stores no raw
  content. That is correct for cost analytics, but insufficient for content-delivery proof.
- Codex rollouts preserve paired tool input/output. A narrow raw exchange reader is therefore
  warranted; it must not persist prompts, commands, or output in its report.

## Design

### Evidence classifications

For every tool exchange that names a checked-in `.claude/skills/<name>/SKILL.md` or
`.agents/skills/<name>/SKILL.md` path:

1. `proven_full_current`: the normalized tool result contains the complete current contents of
   the named source/projection. This is positive proof for today's snapshot.
2. `tool_output_truncated`: the tool result carries an explicit truncation signal and does not
   contain the full current snapshot. This proves the result was capped, not that a particular
   skill section was necessarily omitted.
3. `partial_intent`: the tool call explicitly requests a window/range and full-current proof is
   absent.
4. `ambiguous_batched`: multiple target skills or multiple content-producing commands share one
   result, without stronger evidence.
5. `missing_output`: a read-like call has no paired result.
6. `unproven`: none of the above. This is not counted as failed delivery because historical file
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

### Reuse and reach

Extend the Codex ledger adapter with a raw, paired tool-exchange view rather than adding a second
rollout walker/parser. The neutral `Call`/`ToolEvent` record remains unchanged. The new reader is
an explicit attribution consumer of richer raw data, matching tempdoc 886's boundary between
aggregate ledger data and analyses that need transcript content.

The shipped surface is one CLI plus tests and a canonical analytics-pipeline entry. It does not
touch hooks, skill projection, registries, or runtime code.

## Acceptance contract and plan

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

## Reproducible baseline (2026-09-04)

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

These figures establish that selection and full single-result delivery are different events. They
do not establish that either large skill was never delivered cumulatively: agents often page
through it, and this first reader deliberately does not union multiple historical windows against
today's file. They also do not make the truncated-result subset causal: a batched result may have
contained the needed skill section before truncating later output.

## Verification

- `node scripts/agent-analytics/skill-delivery.test.mjs` — 11 passed.
- `node scripts/agent-analytics/lib/ledger/codex-adapter.test.mjs` — 29 passed.
- `node scripts/agent-analytics/lib/ledger/boundary.test.mjs` — 6 passed.
- `node scripts/agent-analytics/run-all-tests.mjs` — 66/66 test files passed.
- `node scripts/docs/llmstxt-generate.mjs --check` — 116 docs indexed, current.
- `node scripts/docs/skills-sync.mjs --check` — 27 Codex skills / 31 projected files; five
  generated skills from nine sources, current.
- Canonical-link, module-dependency, runtime-config-matrix, and nine Codex parity checks passed.
- `git diff --check` passed.
