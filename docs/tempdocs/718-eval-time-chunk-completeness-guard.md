# 718 — Eval-time chunk-completeness validity guard: refuse to score a degenerate index

- **status:** open — CHARTER + INITIAL DESIGN 2026-07-11 (design-level; no implementation yet)
- **created:** 2026-07-11
- **author-role:** orchestrator (Fable) — design/judgment; implementation delegatable to sonnet
- **relation:** containment for tempdoc 717 (the cure); unblocks tempdoc 715 (release
  re-baseline); instance of tempdoc 704 Pillar 3 (fail-closed validity envelopes) and the
  644 engine-mismatch / 716 fail-closed-clean lineage.

## Problem

Every retrieval measurement in the 691/711/712/713 campaign was only trustworthy because a human
manually checked whether the run's `vector` mode carried a `chunk_merge` leg. That check caught two
silent **degenerate indexes** (tempdoc 717: a fresh `--clean` ingest sometimes ships an index whose
entire chunk sub-system is absent — vector nDCG 0.34 not 0.62 — with no error, COMPLETED status,
passing gates). The check is ad-hoc prose in two tempdocs, run by eye, on some runs, by one person.

**The exposure this leaves open:** any eval consumer that reads a degenerate index scores it as if it
were healthy and publishes the wrong number silently — the release scorecard (715), the
relevance/union-recall ratchets, the fidelity certifier, a founder A/B. The 717 bug is intermittent,
so this is not hypothetical rare-tail: it fired twice in one session's incidental measuring. The
measurement substrate has no immune response to a half-built index.

This is a **measurement-integrity** defect distinct from 717's **enrichment-correctness** defect:
717 asks "why does the build come out degenerate"; 718 asks "why does the eval harness ever *believe*
a degenerate build." Containment is valuable even if 717's root cause takes weeks — and the guard's
detection oracle *is* 717's cheapest-evidence harness (loop N builds, flag the degenerate ones).

## Design direction (theorize-level; to be tightened in a design pass)

A **chunk-completeness envelope** enforced at the eval boundary, fail-closed by default, matching the
repo's existing validity-envelope idiom rather than inventing a parallel one.

1. **The invariant to assert.** For a corpus that *has* chunk documents (the producer emitted
   `chunk_*` docs), a healthy run's `vector` mode must observe the `chunk_merge` leg (and, more
   fundamentally, the index must contain a non-trivial count of `chunk_vector`s). The
   already-computed `per_mode.<mode>.pipeline_tracking.observed` leg set is the cheap signal;
   an on-disk `chunk_vector` count (711-style read-only Lucene probe) is the ground-truth signal.
   The design pass decides which tier the guard uses (legs are free but indirect; a count is
   authoritative but needs index access) — likely legs as the fast gate, count as the escalation.

2. **Where it lives.** Candidate seam: the run-completion path in `jseval` that already writes
   `summary.json`, and/or the gate run-discovery path (716 just unified the data-dir root there).
   The guard emits a `chunk_completeness: {expected, observed, verdict}` block into the run manifest
   and **fails closed** (non-zero / refuses to certify) when a chunk-bearing corpus produced no chunk
   leg — with an escape hatch mirroring 644's `--allow-engine-mismatch` / 716's cross-checkout
   override, for the rare intentional no-chunk run.

3. **"Corpus has chunks" detection.** Must not false-positive on genuinely chunk-free corpora
   (short-doc sets where nothing splits). Signal options: the ingest/enrichment stats already report
   chunk counts; or a corpus-metadata flag; or "did the producer emit any `chunk_embedding_status`
   docs." The design pass picks the one that can't be spoofed by the very degeneracy it guards
   against (e.g. if the bug also suppresses chunk-doc *creation*, a "0 chunk docs" reading is
   ambiguous — must distinguish "no chunks because short docs" from "no chunks because degenerate").
   This is the subtlest correctness point in the design.

4. **Retrofit the ad-hoc convention.** The 712/713 tempdocs' prose health-gate
   ("valid only if `chunk_merge` in vector legs") is replaced by this mechanism; the prose becomes a
   pointer to the guard. Register/ratchet baselines re-affirmed under the guard so a future
   degenerate run can't silently move a floor.

## What this is NOT (scope restraint)

- NOT a fix for 717 — no worker/enrichment code (`CombinedEnrichmentBackfillOps` et al.) is touched;
  that is 717's lane, worked by a separate agent. 718 is `scripts/jseval/` + docs only, code-disjoint.
- NOT a general "index health" framework — it guards the one measured, twice-observed failure mode
  (missing chunk sub-system), not speculative index pathologies. Widen only if a second silent
  index-degeneracy class is observed (per `structural-defects-no-repeat`).
- NOT a GPU consumer — detection is static (legs from an existing summary, or a read-only index
  probe); no new pipeline runs are part of the guard itself.

## Reach / principle (candidate, to be judged in the design pass)

Candidate principle: **"a measurement harness must fail closed on a silently-degraded measurement
substrate, not only on a failed measurement."** The repo already applies this to the *environment*
(644 engine set, 716 dirty data dir); 718 extends it to the *index content* being measured. Where
else it applies: any eval that assumes a fully-enriched index (splade coverage, NER coverage — a
missing-NER or missing-splade index is the same class). **Earning-its-keep evidence:** the guard
fires on a real degenerate run before a human notices, and no wrong number reaches a register/scorecard
after it lands. **Retirement condition:** 717's root cause is fixed AND a build cannot produce a
partial-enrichment index by construction — then the guard is belt-and-braces and can relax to a warn.

## Cheapest first step

Reproduce the detection oracle offline against the archived degenerate vs healthy summaries from this
session (712-ab run-1 OFF arm = degenerate; 712-ab2 both arms = healthy) — confirm a legs-based
predicate cleanly separates them — before wiring it into the run path. This is a pure-parse check on
existing artifacts, no runs.

## Open questions for the design pass

- Legs-gate vs on-disk-count vs both (fast/indirect vs authoritative/heavier)?
- The "corpus has chunks" oracle that survives the degeneracy it guards (Design §3 subtlety).
- Boundary with 704 Pillar 3: is 718 an *instance* to fold into that program, or its own guard that
  P3 later generalizes? (Read 704 before deciding — avoid forking its frame.)
- Does the guard belong in `jseval run` (per-run), the gates (per-consume), or both?
