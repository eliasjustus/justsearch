---
title: "post-arc platform hygiene sweep — the small, enumerated debts the 2026-07-22 arc surfaced, bundled so they don't evaporate"
type: tempdocs
status: "chartered (2026-07-22). Bundle lane: each item is small, self-contained, and DONE-when-checked; the tempdoc exists so the list is a contract (tempdoc-is-your-contract) rather than scattered observations."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 2026-07-22 remaining-work map (founder-directed)
category: platform / hygiene
related:
  - 772 (installer payload)   # item 2's origin (ORT pack completeness check vs dev staging)
  - 767-camouflaged-injection-corpus-lane  # item 4's origin (harness drift observed during re-pins)
---

## §A. Items (each closes independently; the lane closes when all do)

1. **Tempdoc status-vs-merged linter.** 770's frontmatter still reads "NOT merged — awaiting
   owner" though #268 merged; stale statuses actively mislead agents (nearly did on
   2026-07-22). Build a light check: a tempdoc whose status contains a merge-pending marker
   ("NOT merged", "awaiting", "on branch") while a merged PR references its number gets
   flagged (report-mode first; gate-mode only if the signal proves precise — avoid a nag
   that trains its own discounting, per the docs-granularity-hint lesson).
2. **Dev ORT-pack re-stage helper.** 772 §J's completeness check strands pre-772 staging
   layouts → silent CPU fallback for all ONNX eval inference (observed + fixed by hand
   2026-07-22). Ship a script (or extend prepare-worktree) that completes/re-stages
   `tmp/ort-variant-test/<variant>` from the gradle-cache ORT jar + writes the version
   marker; document in common-workflows §Worktree mechanics.
3. **Corrections eval data file.** `test_correction_probe::TestLoadManifest` is red
   everywhere because `correction-eval-queries.v1.json` exists nowhere in git history
   (expected-state entry) — a user-facing feature with zero working eval. Author the
   dataset (small, seeded, leak-checked), turn the tests green, remove the expected-state
   exception (fix root cause, don't keep the suppression).
4. **Harness drift pair.** (a) Register catalog slugs vs jseval CLI dataset names diverge
   (`beir/scifact` in the catalog, `scifact` at the CLI — hit live 2026-07-22): make the
   CLI accept catalog slugs or fix the catalog, one authority either way. (b) The eval
   query path triggers the worker's `deprecated_mode_fallback` WARN on every request —
   jseval still sends the deprecated mode shape; migrate it to the pipeline-config shape
   so evals exercise what production runs.
5. **subagent-guide parks-to-wait line.** Five same-arc incidents of workers parking to
   "wait for events" (a stopped agent receives no events). Add the standing line to the
   SubagentStart baseline brief: synchronous end-to-end execution, bounded in-turn
   condition-polls, never park awaiting external events. One sentence, hook-delivered to
   every subagent type (the sole context carrier for Explore/Plan types).
6. **Dependabot queue triage.** Seven open dependency PRs (some months old) on a public
   repo: merge, supersede, or close each with a reason — supply-chain hygiene; the open
   queue itself is signal to outside readers.

## §B. Acceptance

- Every item checked off with its PR link or an explicit founder-approved "won't do +
  reason" recorded here (no silent drops).
- Item 1 ships report-mode with a measured false-positive check on the current tempdoc set
  before any gate-mode decision.
- Item 3's dataset passes the same leak discipline as any corpus (closed-book style check
  scaled to its size).

## §C. Notes

- Nothing here is design work; anything that grows past "small" gets evicted into its own
  charter rather than bloating this bundle (the bundle's value is that it stays cheap to
  finish).
