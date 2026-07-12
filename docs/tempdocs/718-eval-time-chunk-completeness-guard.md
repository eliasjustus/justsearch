# 718 — Eval-time chunk-completeness validity guard: refuse to score a degenerate index

- **status:** IMPLEMENTED + MERGED 2026-07-11 (#154) — guard, embedded verdict, ratchet-gate enforcement, and `--allow-chunk-incompleteness` escape hatch all shipped. Post-717-fix takeover reassessment (2026-07-12) confirmed KEEP, fail-closed; one DORMANT false-positive fragility recorded (see §Takeover reassessment) — gated on the `/api/status` short-corpus-verdict enabler, not urgent.
- **created:** 2026-07-11
- **author-role:** orchestrator (Fable) — design/judgment; implementation delegatable to sonnet
- **relation:** containment for tempdoc 717 (the cure — merged #155 query-time fix + #156 liveDocs
  followup; 718 = the guard, merged #154); unblocks tempdoc 715 (release re-baseline); instance of
  tempdoc 704 Pillar 3 (fail-closed validity envelopes) and the 644 engine-mismatch / 716
  fail-closed-clean lineage.

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

## Design direction (theorize-level — superseded by §Settled design below, kept for the reasoning trail)

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
after it lands. **Retirement condition:** ~~717's root cause is fixed AND a build cannot produce a
partial-enrichment index by construction — then the guard is belt-and-braces and can relax to a warn.~~
**CORRECTED 2026-07-12 (see §Takeover reassessment):** this premise is written against the *wrong model* —
717 turned out to be **query-time**, not partial-enrichment (the index was fully enriched), so "a
partial-enrichment index is impossible" never becomes the operative condition. **Keep the guard
fail-closed** as a 717-regression guard + general immune response; do NOT relax to warn.

## Cheapest first step

Reproduce the detection oracle offline against the archived degenerate vs healthy summaries from this
session (712-ab run-1 OFF arm = degenerate; 712-ab2 both arms = healthy) — confirm a legs-based
predicate cleanly separates them — before wiring it into the run path. This is a pure-parse check on
existing artifacts, no runs.

## Settled design (2026-07-11 — grounded in the jseval-seam investigation, file:line in §Evidence)

### The anti-spoof oracle (the crux — resolves the Design §3 subtlety)

A missing chunk sub-system reads bit-for-bit identical to a legitimately chunk-free corpus at the
*pipeline output* layer: `chunkDocCount=0, chunkVectorCoveragePercent=0.0` both ways
(`LuceneRuntimeTypes.coveragePercent()` returns 0.0 when total==0 — no vacuous 100%). No
attempted-vs-completed counter exists. So the observed signal alone **cannot** distinguish
"short docs, nothing to chunk" from "build degenerated." The disambiguator must come from a
source the degeneracy cannot touch:

- **EXPECTED (offline, spoof-proof):** compute from the corpus *text* the count of documents whose
  content length ≥ the production chunk threshold (`ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS = 2000`,
  the same gate `IndexingDocumentOps` applies). This is a pure read of `corpus.jsonl`, computed
  before/independent of any ingest — the enrichment pipeline's own failure can never move it.
  `expected_chunk_docs > 0` ⟺ "this corpus SHOULD produce chunk docs."
- **OBSERVED (post-enrichment, ground truth):** `chunkDocCount` + `chunkVectorCoveragePercent` from
  `/api/status` `worker.enrichment.chunk.*` (a live Lucene `TermQuery(IS_CHUNK,true)` count — jseval
  already reads these in `readiness.py`), plus `chunk_merge ∈ per_mode.vector.pipeline_tracking.observed`
  as a query-time corroborator (server-projected from `searchTrace`, validated to separate this
  session's degenerate (0.34, no chunk_merge) from healthy (0.62, chunk_merge) runs — offline check,
  §Evidence).

- **VERDICT:** `expected>0 AND (observed chunkDocCount==0 OR coverage<floor OR chunk_merge absent)`
  → **degenerate** (fail closed). `expected==0` → **chunk-free** (legitimately, pass). Both signals
  present and consistent → **ok**.

**One dual-source-of-truth caveat, named:** the `2000` threshold lives in Java
(`ChunkDocumentWriter.java:28`); a jseval-side mirror can drift if that constant changes. Mitigation
(design decision): pin it in one jseval constant with a comment citing the Java site + a cross-check
note, and — cheapest durable fix — file a follow-up to expose the threshold via `/api/status` or a
config surface so the oracle reads it rather than mirrors it. Using a *conservative* margin (expect
chunks only when a doc materially exceeds 2000, e.g. ≥ 2× or the corpus has many long docs) further
de-sensitizes the guard to a small threshold change. Never make the guard stricter than the producer.

### Shape (conforms to the 644 idiom exactly — pure verdict + separate enforcement + named escape hatch)

1. **Pure verdict function** `chunk_completeness_verdict(expected_chunk_docs, observed_chunk_doc_count,
   observed_coverage_pct, chunk_merge_observed, *, coverage_floor) -> {expected, observed, verdict,
   reasons}`, verdict ∈ `{ok, degenerate, chunk-free}`, `reasons: list[str]` (never boolean-only —
   matches `ComparabilityResult`/`compare_engine_sets`).
2. **Embedded in the summary** — computed in `_build_summary` (`run.py:445-512`) from the offline
   corpus-length expectation + the status counts the harness already fetches, attached as a run-scoped
   `chunk_completeness` block (sibling of `manifest`/`corpus_identity`) so **every run self-documents**.
3. **Enforcement at the gate seam** — `assert_chunk_completeness(run_dir, *, allow_incomplete=False)`
   in `ratchet_kernel.py`, called right after `resolve_run_dir(...)` alongside `assert_cohort_engines`
   — the single shared seam all four ratchet gates pass through — so one insertion protects
   relevance/perf/leak/union-recall at once. On `degenerate` and not overridden: JSON-to-stderr
   `{"exit_code":2,"error":...,"expected":...,"observed":...}` + `sys.exit(2)` + inline remedy,
   mirroring `assert_cohort_engines` byte-for-byte in structure.
4. **Named escape hatch** — `--allow-chunk-incompleteness` flag per gate command +
   `JUSTSEARCH_ALLOW_CHUNK_INCOMPLETENESS=1` env var, mirroring `--allow-engine-mismatch` /
   `JUSTSEARCH_ALLOW_CROSS_CHECKOUT_JSEVAL`. Never silent.

### Why both seams (not one)

644 already runs both halves: an advisory `comparable`/`comparability_reasons` field in the summary
AND a fail-closed `assert_cohort_engines` at gates. 718 mirrors it: the embedded verdict makes every
run legible and greppable (and gives 715's re-baseline a per-corpus completeness record); the gate
enforcement is what actually stops a degenerate run from silently moving a ratchet floor or landing in
the release scorecard.

## Orphans (retired/retrofitted by this design — same PR)

- The **ad-hoc prose health-gate** I introduced in tempdocs 712 (§Step-4 "a run is valid only if
  `chunk_merge` in vector legs") and 713 (§M-5 quarantine) — replaced by this mechanism; those prose
  lines become a pointer to the `chunk_completeness` verdict + `assert_chunk_completeness`. (Both are
  already-merged docs; the pointer update rides in this PR's register/tempdoc edits, not a rewrite of
  the merged tempdocs.)
- Nothing in code is deleted — this is additive machinery. `readiness.py`'s existing
  `chunk_doc_count > 0` skip (`:159`) stays (it's the correct short-doc guard for *readiness*); 718
  adds the *expectation* half it lacks.

## Reach / principle (judged)

**Principle:** *a measurement harness must fail closed on a silently-degraded measurement SUBSTRATE
(the index content being measured), not only on a failed measurement or a bad environment.* The repo
already applies fail-closed to the *environment* (644 engine set, 716 dirty data dir / cross-checkout);
718 extends the same reflex to the *index content*. This is an **instance of 704 Pillar 3's stated
principle** ("assertions covered forbidden states, never expected states"; P3 explicitly names
"enrichment coverage" as a preflight check) — but 704 frames P3's *vehicle* as 675's executor-v2
"validity certificate," which is unimplemented. 718 is the narrow, shippable down-payment in the
`jseval` seam now, not a fork of that program.
- **Candidate scope beyond chunks:** missing-SPLADE-coverage and missing-NER-coverage indexes are the
  same class (an expected-enrichment lane silently absent). Do NOT build those now
  (`structural-defects-no-repeat`) — the chunk case is the twice-observed one; generalize only when a
  second enrichment-lane degeneracy is observed. The verdict function's signature is left shaped so a
  second lane could be added without a parallel structure.
- **Earning-its-keep evidence:** the guard fires on a real degenerate run (e.g. the next 717
  recurrence, or 715's re-baseline) before a human notices, and no wrong number reaches a
  register/scorecard/ratchet after it lands.
- **Retirement condition:** 675's executor-v2 unified validity certificate absorbs it (718 migrates in,
  not duplicates). ~~OR 717's root-cause fix makes a partial-enrichment index impossible by construction
  (then the guard relaxes to a warn — belt-and-braces).~~ **CORRECTED 2026-07-12:** the second clause is
  void — 717 was query-time, not partial-enrichment, so that condition never triggers (§Takeover
  reassessment). The 675-absorption clause remains the live retirement path.

## Implementation plan (delegable to a sonnet subagent; orchestrator reviews + verifies)

All in `scripts/jseval/` + docs; **no worker/enrichment code** (that is 717's lane — code-disjoint).

1. **Offline expectation helper** — a function computing `expected_chunk_docs` from a corpus's
   `corpus.jsonl` (count docs with `len(content) >= CHUNK_THRESHOLD_CHARS`), threshold pinned in one
   constant with a comment citing `ChunkDocumentWriter.java:28` + the dual-source note. Unit tests:
   long-doc corpus → expected>0; short-doc corpus → expected==0; boundary at the threshold.
2. **Pure verdict** `chunk_completeness_verdict(...)` (new module or `comparability.py`-adjacent),
   dataclass result `{expected, observed, verdict, reasons}`. Unit tests over the truth table
   incl. the two 0-chunk cases (chunk-free vs degenerate) — seeded from THIS session's archived
   summaries (712-ab OFF = degenerate, 712-ab2 = healthy) as fixtures.
3. **Embed in summary** — wire into `_build_summary` (`run.py:445-512`): pass the offline expectation
   (computed from the run's corpus) + the status chunk counts jseval already has at completion, attach
   the `chunk_completeness` block. Pin its presence with a run-shape test.
4. **Enforcement** — `assert_chunk_completeness(run_dir, *, allow_incomplete=False)` in
   `ratchet_kernel.py` mirroring `assert_cohort_engines` (JSON-to-stderr + exit 2 + remedy); insert the
   call at the shared gate seam after `resolve_run_dir` in all four ratchet gate commands
   (`commands/gates.py`). Escape hatch flag + env var. Tests: degenerate run → exit 2; chunk-free run →
   pass; override → pass with warning.
5. **Docs + retrofit** — document the guard in the jseval pipeline reference; repoint the 712/713 prose
   health-gate to it (register note); add the follow-up observation to expose the 2000 threshold via
   API so the oracle can stop mirroring the constant.
6. **Verify** — full jseval suite bare-exit-asserted (the 2 `test_correction_probe` reds are
   pre-registered). **Live smoke (GPU, orchestrator-run, sequenced after 717's agent):** one healthy
   legal-clerc run → verdict `ok` embedded + gate passes; assert the guard would have caught this
   session's degenerate arm by running the verdict over its archived summary (no new degenerate build
   needed — the fixture proves the catch).

## Design summary (plain language)

**Part 1 — what the design does.** Right now nothing stops the eval harness from scoring a
half-built index as if it were healthy — a bug (717) can silently produce an index missing its
chunk data, and every measurement (including the public release scorecard) would just report the
wrong, worse number. 718 gives the harness an immune response: before it trusts a run, it checks
"does this corpus have long documents that *should* have been chunked?" — computed straight from the
raw text, so the buggy pipeline can't lie about it — and compares that to what the index actually
contains. If long docs are present but the chunk data isn't, the harness refuses to certify the run
and says why, instead of silently publishing a bad number. It's the same fail-closed reflex the repo
already uses for a dirty workspace or a mismatched engine set, now pointed at the index content itself.

**Part 2 — the reach.** The real principle is broader than chunks: *a measurement tool should refuse
to measure a silently-broken subject, not just a broken environment.* The same hole exists for the
other enrichment lanes (keyword/entity coverage) — but I deliberately did not build those; the chunk
case is the one we've actually seen fail twice, and over-building for unseen cases is the exact
anti-pattern this codebase warns against. I shaped the verdict so a second lane could slot in later
without a parallel structure. And this is explicitly a down-payment on a bigger program (704's
"validity certificate," 675's executor) — small and shippable now, designed to be absorbed later
rather than compete with it, with a clear condition for when it should retire.

## Implementation + real-data validation (2026-07-11)

Implemented in `scripts/jseval/` (Python only; worker/enrichment code untouched — 717's lane):
`chunk_completeness.py` (`expected_chunk_docs`, `chunk_completeness_verdict`,
`ChunkCompletenessResult`), `run._compute_chunk_completeness` embedding the block into every
`summary.json`, `ratchet_kernel.assert_chunk_completeness` wired after `assert_cohort_engines` in all
four ratchet gates, `--allow-chunk-incompleteness` / `JUSTSEARCH_ALLOW_CHUNK_INCOMPLETENESS=1` escape
hatch. Full jseval suite green (1676 passed; the 2 `test_correction_probe` reds are pre-registered).

**Proven against this session's REAL degenerate run (no GPU needed — the definitive catch test):**
running the guard's own verdict over the archived summaries —
- `expected_chunk_docs(legal-clerc-200)` = **194** (offline, from corpus text — matches F-033's
  194/198-are-long-docs);
- 712-ab OFF (the run that actually degenerated this session) → **`degenerate`** (would have been
  refused): reasons = chunk_doc_count==0, coverage 0.0 < floor, chunk_merge absent;
- 712-ab2 OFF + ON (healthy) → **`ok`**.

So the guard demonstrably catches the exact failure it was built for. Enforcement is conservative:
it fires ONLY on a positively-`degenerate` verdict — missing block (pre-guard runs), `ok`, and
`chunk-free` all pass silently (backward-compatible, mirrors `compare_engine_sets`'s skip-on-unknown).

## Live smoke (2026-07-11) — the guard caught a real degeneracy end-to-end, and the multi-signal design was load-bearing

One fresh `--clean` legal-clerc-200 run from this worktree. The 717 anomaly **struck this very
build**, and the embedded guard caught it — a live end-to-end validation better than a clean pass:

- `summary.json` carried a real `chunk_completeness` block: `expected=194, observed=4293,
  verdict="degenerate", reasons=["chunk_merge absent from vector mode's pipeline_tracking.observed"]`.
  Embed path (offline expectation + status counts threaded through `_build_summary`) works on a real run.
- **The count/coverage signals were HEALTHY:** `chunkDocCount=4293, chunkEmbeddingCompletedCount=4293,
  chunkVectorCoveragePercent=100.0, pending=0, failed=0` — a fully-enriched chunk index. Only the
  `chunk_merge` query-time corroborator fired the verdict. **A count-only oracle would have certified
  this degenerate run as healthy** — the multi-signal design (index counts AND the query-time leg) is
  what catches this flavor. Strong validation of including `chunk_merge`, not just chunk counts.
- Retrieval was genuinely halved (vector nDCG 0.34 vs healthy 0.62), so the degeneracy is real, not a
  telemetry gap.

**This also refined tempdoc 717's hypothesis** (see 717 §Refinement): the chunk vectors are present
and 100%-covered, so the degeneracy is NOT a build-side chunk-vector-death (the framing 712/713 and
this author assumed) — it is a **query-time `chunk_merge` non-activation** despite a healthy chunk
index. 718's live smoke produced that clue as a byproduct of validating the guard.

## Takeover reassessment (2026-07-12, post-717-fix; investigation only — no code changed)

Taken over after tempdoc 717's root cause was found and FIXED + merged (#155/#156). This changes
718's context materially, so the verdict below is *keep + refine*, not *build*.

### Reality check: 718 is DONE, and the header status line is STALE
718 is **implemented and merged (#154)**: `chunk_completeness.py` (`expected_chunk_docs`,
`chunk_completeness_verdict`), `run._compute_chunk_completeness` embedding the block in every
`summary.json`, `ratchet_kernel.assert_chunk_completeness` wired after `assert_cohort_engines` at the
shared gate seam (all four ratchet gates), and the `--allow-chunk-incompleteness` /
`JUSTSEARCH_ALLOW_CHUNK_INCOMPLETENESS` escape hatch. Enforcement, embed, and escape hatch all
verified present in the shipped code. **The `status:` line ("open — ready for delegated
implementation") is stale and should be reconciled to "implemented + merged (#154)"** (same class of
staleness #157 just fixed for 705).

### Verdict: KEEP it, and KEEP it fail-closed — do NOT relax to warn
717's fix removed the *specific* cause of the `chunk_merge` non-activation (a SPLADE-load race left
`parent_token_count` unpopulated → `CorpusProfile` mis-classified a long corpus "short" →
`SKIPPED_SHORT_CORPUS`). But 718's own **retirement condition** ("717's fix makes a partial-enrichment
index impossible by construction → relax to warn") does **not** actually trigger, for two reasons:
1. The bug was **query-time, not partial-enrichment** (718's own live smoke proved the index was
   fully enriched — `chunkDocCount=4293, coverage=100%`). The retirement premise is written against
   the pre-correction *wrong model*; 717's fix does not make "a partial-enrichment index" the thing
   that's now impossible.
2. 717's fix removes the short-corpus cause but not *all* `chunk_merge`-absence causes. The guard's
   value shifts from "catches an active bug" to a cheap **regression guard for 717** (catches a
   re-manifestation before a human notices) + immune response to a future degenerate index. For a
   defect that silently *halves* retrieval quality, a fail-closed regression guard is worth keeping
   strict. **Recommendation: keep fail-closed; update the retirement condition to reflect the
   query-time reality.**

### The one substantive defect found: a live FALSE-POSITIVE risk on the gated corpus set
The oracle's expectation (`expected_chunk_docs` = count of corpus docs with `len(content) ≥ 2000`,
`chunk_completeness.py:66`) is **more permissive than the planner's actual `chunk_merge`
eligibility**. The planner runs `chunk_merge` only when `!CorpusProfile.isShortCorpus()` — i.e. the
corpus is not predominantly short *by median token count* (or by `chunkRate`). So a corpus that has
**some** ≥2000-char docs (→ `expected_chunk_docs > 0`) but is **predominantly short by median** will
have `chunk_merge` **legitimately** skipped (`SKIPPED_SHORT_CORPUS`, per F-036 hybrid-neutral), yet
`chunk_completeness_verdict` fires **`degenerate`** on `expected>0 AND not chunk_merge_observed`
(`run.py:552`, `chunk_completeness.py:137`) → the gate fails closed on a **healthy** index.
- **This is untested:** the only live/fixture validation was `legal-clerc-200` (194/198 docs long →
  never short → no false-positive). The **currently-gated** set includes mixed/short corpora that are
  exactly the risk shape: `mixed/enron-qa`, `mixed/miracl-de/fr/zh-2k`, `golden/needle-burial-v1`,
  `golden/util-smoke` (baseline files). None has been checked for "expected>0 but planner-short."
- **It's the same class of bug 717 was:** a proxy (here, the 2000-char length heuristic) that doesn't
  match the real gate (the planner's median-based short-corpus test) → wrong verdict on a healthy
  corpus. 717 fixed exactly this pattern on the *producer* side; 718's oracle re-introduces it on the
  *checker* side.
- **The obvious fix is a TRAP (corrected after gathering evidence, below).** I first proposed
  "honor `chunkMergeReason` — treat `SKIPPED_SHORT_CORPUS` as not-a-strike." **That is wrong:**
  `SKIPPED_SHORT_CORPUS` is the *exact signature of the 717 bug* (a long corpus mis-classified short
  → chunk_merge skipped). Treating that reason as legitimate would blind the guard to the very defect
  it exists to catch. The two are indistinguishable *by the skip reason alone* — the disambiguator
  has to be whether the corpus is *genuinely* short, which is precisely what the oracle's `expected`
  is supposed to encode.

### Offline evidence gathered (2026-07-12) — the false-positive is DORMANT, and the char-proxy fix is unsafe
Ran `expected_chunk_docs()` + the doc-length distribution over the materialized gated corpora
(median char length is the offline proxy for the planner's median-token short test, ~2000 chars ≈
~512 tokens):

| corpus | n | expected(≥2000) | median chars | planner verdict | false-positive? |
|---|---|---|---|---|---|
| mixed/enron-qa | 5485 | 2927 (53%) | 2151 | not short (median > 2000) → runs chunk_merge | no (borderline) |
| golden/needle-burial-v1 | 280 | 280 (100%) | 3912 | not short → runs | no |
| mixed/legal-clerc-200 | 198 | 194 (97%) | 28169 | not short → runs | no |
| mixed/miracl-de/fr/zh-2k, golden/util-smoke | — | — | not materialized | UNVERIFIED | plausible (miracl = short multilingual passages) |

**Findings:**
1. **The false-positive is not currently active** on any checkable gated corpus — all three are
   predominantly long enough that the planner runs `chunk_merge`, so the guard reads `ok`. This
   *invalidates the urgency* of a fix. The unverified miracl corpora are the only residual risk (a
   pure offline check when they materialize).
2. **A char-length-based `expected` fix cannot be made correct**, because the planner's short test is
   **token**-median and miracl is **multilingual** (CJK packs ~1 token/char vs English ~¼) — no
   char proxy tracks the token median across languages. Worse, a too-conservative `expected` trades
   the (dormant) false-*positive* for a false-*negative* — the guard would read a genuinely-long
   corpus as `expected==0 → chunk-free` and go **blind** to a real degeneracy on it. A false-negative
   in a fail-closed integrity guard is strictly worse than a false-positive.
3. **The only correct fix is the already-logged enabler:** expose the producer's short-corpus verdict
   (or the token threshold / `parent_token_count`) via `/api/status`, so the oracle reads "would the
   planner run chunk_merge on this corpus" from the producer instead of re-deriving it from a proxy.
   That is the same "expose `CHUNK_THRESHOLD_CHARS` via API" follow-up already in the inbox
   (obs `6a51b979…`), generalized. **Until that enabler exists, the fix should WAIT** — the fragility
   is dormant, and the `--allow-chunk-incompleteness` escape hatch covers the rare live case.

### Revised verdict (post-evidence)
- **KEEP 718, fail-closed, unchanged code** — the guard works and is not currently mis-firing.
- **The false-positive is a real but DORMANT design fragility**, not an active defect; **do not rush a
  char-proxy fix** (it risks a worse false-negative). Gate the proper fix on the API-exposure enabler.
- **The only warranted work now is documentation:** reconcile the stale `status:` line, correct the
  retirement condition to the query-time reality, and cross-reference 717's merged fix. (This section.)

### Cheapest evidence
- *Is the guard still needed?* Already proven — it fired on a real degenerate run (§Live smoke). Its
  post-717 value (regression guard) is demonstrated by 717's 4/4 healthy live builds all carrying
  `chunk_merge` → the guard now reads `ok` on healthy builds (no new run needed).
- *Is the false-positive risk live?* A pure **offline** check: run `expected_chunk_docs()` over each
  gated golden/mixed `corpus.jsonl` and compare against the planner's `isShortCorpus` verdict (median
  token count) for that corpus. Any "expected>0 but planner-short" corpus is a live false-positive.
  **This evidence does not yet exist** (validation covered only legal-clerc). It is cheap (no runs,
  no GPU) and is the first thing a design pass should gather.

### What it displaces / duplicates
- Retires the 712/713 ad-hoc prose `chunk_merge`-in-legs health-gate (this tempdoc's stated orphan).
  717's register edit (#155) *also* references retiring that convention — **complementary, not
  conflicting**: 717 makes the degenerate state not-arise; 718 automates the check that catches any
  residual/future occurrence. No duplication of machinery.
- Explicitly a narrow down-payment on **704 Pillar 3 / 675's executor-v2 validity certificate**
  (unimplemented) — to be *absorbed* there later, not a competing framework. Correctly scoped.

### Residual work — status after the 2026-07-12 takeover
1. ✅ **DONE (this pass):** reconciled the stale `status:` line → implemented+merged (#154); corrected
   the retirement condition to the query-time reality (§§below annotated); cross-referenced 717's
   merged fix (717 = cure, 718 = guard). Docs-only, no code changed.
2. ✅ **DONE (this pass) — evidence gathered, and it re-scoped the "fix":** the offline expected-vs-median
   check over the 3 materialized gated corpora (§Offline evidence above) shows the false-positive is
   **DORMANT** (all predominantly long → planner runs chunk_merge). It also revealed the proposed
   "honor `chunkMergeReason`" fix is a **TRAP** (`SKIPPED_SHORT_CORPUS` is the 717 signature) and that
   any char-length proxy risks a worse false-*negative* on multilingual corpora. **The false-positive
   fix is therefore GATED on the enabler below, not a quick honor-`chunkMergeReason` edit.**
3. **NOT DONE — the real enabler (durable follow-up):** expose the producer's short-corpus verdict (or
   `parent_token_count` / the token threshold) via `/api/status`, so the oracle reads "would the
   planner run chunk_merge on this corpus" from the producer instead of a proxy. Generalizes the
   already-logged obs shard `6a51b979…` ("expose CHUNK_THRESHOLD_CHARS via /api/status"). Also verify
   the false-positive on `mixed/miracl-{de,fr,zh}-2k` / `golden/util-smoke` when they materialize
   (pure offline check, no runs). Until then the `--allow-chunk-incompleteness` escape hatch covers
   the rare live case.
