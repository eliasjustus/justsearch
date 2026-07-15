# 717 — Intermittent fresh-build chunk-death: a fresh --clean ingest sometimes silently ships an index with no chunk_merge leg

- **status:** RESOLVED + MERGING. Live probe found the real root cause (NOT vector loss — a
  SPLADE-load race leaves `parent_token_count` unpopulated → long corpus mis-classified "short" →
  `chunk_merge` leg skipped; see §Live probe). **The fix** (A: always-populate `parent_token_count`;
  B: `isShortCorpus` fail-open on unreliable token data) is implemented, independently reviewed, and
  live-validated 3/3 fresh builds healthy (vector ≈0.62, `chunk_merge` present; §Resolution). Founder
  decision (2026-07-12): **merge BOTH** the short-corpus fix AND the Phase-1 `chunk_vector`
  presence-truthful hardening — the latter is *complementary F-032/714-lineage hardening* (closes a
  "status lies" gap), NOT the fix for this bug and does not catch it. Both combined onto the merge
  branch; the standalone `717-phase1-hardening-ref` is retained as history. **Complements 718's
  already-merged containment guard (#154)** — 718's eval-time chunk-completeness guard *catches* a
  degenerate index (fails closed); this tempdoc *prevents* it (root-cause fix). The deferred
  "eval-guard" follow-up (§Resolution D-4/C) is therefore already delivered by 718.
- **created:** 2026-07-11
- **updated:** 2026-07-11

## Charter question

Why does a fresh `--clean` full-pipeline ingest of the same corpus **sometimes** produce a live
index whose entire chunk sub-system is absent — every mode missing the `chunk_merge` leg, dense
retrieval scoring ~0.34 instead of ~0.62 — with no error, no failed status, no OOM? What makes
it intermittent, and what makes it silent?

## Evidence (measured, this session — the reason this is chartered, not a hunch)

The anomaly was hit repeatedly while measuring unrelated tempdocs, on the **shipped default
path** (late-chunking on, RMW preservation #139 present):

| run | fresh build | outcome | vector nDCG@10 | chunk_merge leg |
|---|---|---|---|---|
| 713 control (first arm) | yes | **degenerate** | 0.3403 | absent |
| 713 §M-5 probe | yes | healthy | 0.6185 | present |
| 712 A/B-1 OFF arm | yes | **degenerate** | 0.3403 | absent (all modes) |
| 712 A/B-2 both arms | yes | healthy (first try) | 0.6187 / 0.6184 | present |

So: **not deterministic** (M-5 refuted "always dead"), **not never** (two independent degenerate
hits). The degenerate state is a strict quality halving that ships silently — the index reports
COMPLETED, gates that don't pin the chunk leg pass, and only a per-mode leg inspection or a
vector-nDCG cliff reveals it. Distinct from F-032 (which was a *deterministic* RMW destruction,
fixed in #139); this is a *timing/nondeterminism* survivor of that class or a separate one.

## Suspected mechanism (for the takeover to confirm or refute — do not assume)

The 712 A/B observations logged adjacent shard entries that are candidate roots:
- Combined-pass parent lane stamps parent `EMBEDDING_STATUS`/`NER_STATUS=COMPLETED` onto chunk
  docs picked up via the splade-status query (`CombinedEnrichmentBackfillOps.java:330`) — a chunk
  doc can be marked parent-COMPLETED before its chunk vector is durably written.
- A chunk doc pending both chunk_embedding and splade sits in BOTH combined-pass caches and can
  be popped twice into one batch (`CombinedEnrichmentBackfillOps.java:199-260`) — double-embed /
  race on the same doc's write.
- The `--pipeline` enrichment-complete wait may return before chunk vectors are committed/merged
  under some interleaving (the "COMPLETED without data" family).

## Cheapest evidence (the takeover's first question)

Loop a fresh `--clean` legal-clerc build N times (say 10) capturing, per run: the vector-mode
legs, a read-only Lucene chunk-vector count (711-style probe), and the combined-pass worker
counters — to (a) measure the hit rate, (b) correlate degenerate runs with a specific counter/log
signature, (c) determine whether a `--pipeline` wait-condition tightening or a
readiness-gate on the chunk leg makes it disappear. A cheap same-corpus repeat harness; no code
change needed to gather the rate.

## Why it matters / scope

This silently halves retrieval quality on chunked-corpus deployments some fraction of the time,
and every fresh-build measurement in the 691/711/712/713 arc is now suspect unless its chunk leg
was health-verified. A fix likely belongs to the F-032 lineage
(`CombinedEnrichmentBackfillOps` / the `--pipeline` readiness contract), but the diagnosis must
come first (`audit-without-test`: a regression test that reproduces the degenerate index is the
bar before any fix).

## Relations

- tempdoc 711 (F-032 RMW preservation — the deterministic sibling, fixed), 712 (§Step-4 A/B where
  it recurred), 713 (§M-5 where it was first quarantined), 691 (late-chunking / combined pass).
- Register: F-032, F-035, F-036; the health-gate convention (`chunk_merge` in vector legs) that
  712/713 adopted is the interim guard until this is fixed.

## §Refinement (2026-07-11, from tempdoc 718's live smoke) — hypothesis CORRECTED: query-time, not build-time

A fresh `--clean` legal-clerc build for 718's live smoke hit the anomaly and was fully instrumented
this time. The result **falsifies this charter's original "chunk vectors dead" framing** (inherited
from 712/713, where the degenerate index was wiped before probing):

- The degenerate build had `chunkDocCount=4293, chunkEmbeddingCompletedCount=4293,
  chunkVectorCoveragePercent=100.0, pending=0, failed=0` — **the chunk vectors are all present and
  100% covered.** The build side (CombinedEnrichmentBackfillOps chunk production + vector write) worked.
- Yet vector nDCG was 0.34 (vs healthy 0.62) and `chunk_merge` fired for **zero** queries.

**So the degeneracy is QUERY-TIME, not build-time:** a fully-enriched chunk index (vectors present +
covered) whose `chunk_merge` leg silently fails to activate at search time. The investigation should
therefore start at the **query-time chunk-merge activation path** — `SearchExecutor` (the
`search/chunk_merge` span at `SearchExecutor.java:527`), `SearchPlanner.planChunkMerge`,
`ChunkSearchOps.searchChunksSplade`/chunk-vector retrieval, and whatever gates whether the chunk
branch is consulted — NOT the enrichment/write path (which this evidence exonerates). Candidate
mechanisms: a readiness/visibility race where the searcher opens before the chunk segment is
merged/visible; a chunk-vector reader init that silently no-ops on a fresh index; a `chunkVectorsReady`
flag consulted at query planning that is stale-false right after a fresh build.

**Cheapest first evidence (updated):** the loop-N-fresh-builds harness (still valid) should capture,
per run, BOTH the `chunk_completeness` block (718 now embeds it) AND — on a degenerate hit — probe
whether the chunk vectors are queryable directly (a manual chunk-vector ANN query) vs merely present,
to localize the query-time gate that's misfiring. F-032's build-side lineage is likely NOT the culprit.

> **CONFIRMED + ROOT-CAUSED by this tempdoc's live probe (§Live probe below).** 718's §Refinement was
> right: the degeneracy is query-time. This tempdoc's probe reached the same finding independently and
> localized it exactly — the `chunk_merge` leg is dropped via `SKIPPED_SHORT_CORPUS` because a
> SPLADE-load race leaves `parent_token_count` unpopulated → `CorpusProfile` mis-classifies a long
> corpus "short". Fixed (parent_token_count fallback + `isShortCorpus` fail-open); live-validated
> healthy. **718 ships the containment guard (eval fails closed on a degenerate index, #154); this
> tempdoc ships the cure** — complementary. The charter's own vector-loss framing (§Charter → §T-8)
> below is retained as dated history, then superseded by §Live probe / §Resolution.

## Takeover investigation (2026-07-11, static code trace; no runtime probe yet)

**Verdict up front: DO IT, NOW — but the first move is diagnosis, and the cheapest
diagnostic is *not* the proposed 10× loop.** Rationale, evidence, and a sharper first
experiment below. Status stays **seed** (no code changed).

### T-1. What "chunk_merge leg absent" concretely is
`search/chunk_merge` (`SearchExecutor.java:527`) runs chunk KNN + `searchChunksText` +
`searchChunksSplade` (`:827,839`) over the chunk sub-corpus and fuses into the parent result.
The KNN sub-leg retrieves over the non-stored `chunk_vector` `KnnFloatVectorField`. If the index
holds **zero live `chunk_vector` fields**, that sub-leg returns nothing and the branch does not
contribute → dense nDCG collapses to the parent-only ~0.34 (matches F-032's 0.3401 pin exactly).
So the degenerate state is: **chunk docs exist and are marked done, but their `chunk_vector`
fields are absent.** The two attractors (0.34 / 0.62) are binary because it is all-or-nothing at
the corpus level.

### T-2. Chunk docs are created inline with the parent → the "chunkDocCount==0 skip" race (M-A) is unlikely
`JobBatchWriter.write()` indexes the parent (`:126`) then **synchronously** writes its chunk docs
in the same job (`:131` → `IndexingDocumentOps.indexChunks` → `ChunkDocumentWriter.regenerateChunks`).
Chunk docs are born with `CHUNK_EMBEDDING_STATUS=PENDING` (`ChunkDocumentWriter.java:175`) and
`SPLADE_STATUS=PENDING` (`:179`); embedding is deferred to backfill (`JobBatchWriter.java:116`).
So by the time the index is IDLE + queue-quiescent + parent embedding 100%, chunk docs are already
visible (`chunkDocCount>0`). The pipeline-complete predicate only *skips* the chunk check when
`chunk_doc_count == 0` (`readiness.py:158-160`) — an unlikely window for a chunkable corpus.
**M-A is deprioritized** (keep as a fallback only for corpora with a late-visibility/commit gap).

### T-3. **Decisive finding — the readiness gate is structurally blind to the F-032 class**
The `--pipeline` completion predicate checks `chunkVectorCoveragePercent >= 99.9`
(`readiness.py:159`). That field is **derived from `chunk_embedding_status=COMPLETED` counts, not
from actual `chunk_vector` presence**:
- `IndexCountOps.queryChunkEmbeddingCounts()` counts `IS_CHUNK=true` docs whose
  `CHUNK_EMBEDDING_STATUS == COMPLETED` (`IndexCountOps.java:142-147`); `coveragePercent` =
  completed/total.
- This flows verbatim to the snapshot: `IndexStatusOps.buildEnrichment()` sets
  `ChunkCoverage.coveragePercent` from those status counts (`IndexStatusOps.java:621-628`) →
  `ChunkCoverageGroup`/`ChunkCoverageView.chunkVectorCoveragePercent` (`ChunkCoverageGroup.java:23`,
  `ChunkCoverageView.java:10`, doc-commented "Chunk vector coverage … for RAG readiness").

`chunk_embedding_status=COMPLETED` is the **exact field F-032 proved can read COMPLETED while the
vector is absent** ("status lies"; register F-032, `search-quality-register.md:652-663`). So the
gate that is supposed to guarantee a live chunk leg verifies a proxy that cannot see the very
failure it guards. **This explains "the index reports COMPLETED, gates that don't pin the chunk
leg pass."** It is a real defect independent of the intermittent root cause, and hardening it
(count actual `chunk_vector` KNN presence, à la the 711/713 `VecProbe`) is the cheapest way to
convert *silent* → *loud*.

### T-4. #139 genuinely closed the deterministic single-RMW destruction
`chunk_vector` is `rmwPolicy=preserve-reread` (`fields.v1.json`), so a subset-field RMW that omits
it re-reads the vector at the held searcher snapshot and carries it forward
(`WritePathOps.applyRmwPolicies:333-338`, `readFloatVector:675-691`). A single RMW reconstructs
stored status **and** re-reads the vector from the **same** snapshot (`updateDocumentsBatch:566-570`),
so within one write, `chunk_embedding_status` and `chunk_vector` are mutually consistent — a
`preserve-reread` null co-occurs with the same snapshot's status reading PENDING (self-healing),
not COMPLETED. So the *deterministic* destruction is gone. This is why the anomaly is now
*intermittent*, not *always*.

### T-5. The two "suspected mechanisms" in the charter, assessed
- **Double-pop (`CombinedEnrichmentBackfillOps.java:199-260`) — REAL but appears wasteful, not
  fatal.** A chunk doc pending both `chunk_embedding` (→`chunkIdCache`, `:203-213`) and `splade`
  (→`parentIdCache` via the `SPLADE_STATUS=PENDING` query, `:180-188`) is enrolled in **both**
  caches and can be popped twice into one batch's `pendingIds` (`:221-229`). Both occurrences hit
  the `isChunkDoc` branch (`chunkDocIds.contains`, `:285`), so it is double-*embedded* into one
  shared `updatesByDocId` entry — redundant GPU work, but the single bundled write **includes**
  `chunk_vector`, so no loss. Confirmed structurally; not yet shown to destroy data.
- **Parent-lane stamping (`:334-360`) — REAL, mostly benign, one latent smell.** A chunk doc
  popped only via the splade query lands in the parent lane with `content==null`; it gets
  `EMBEDDING_STATUS`/`NER_STATUS` **added** as COMPLETED (spurious parent-schema fields on a chunk
  doc) and `SPLADE_STATUS→COMPLETED` (`:334-359`). Retrieval reads `chunk_*` fields, so these
  spurious parent statuses don't directly kill the vector — but they do author parent-schema
  representation onto chunk docs, and the RMW that writes them re-reads `chunk_vector` via
  `preserve-reread` (harmless *if* the snapshot sees the vector; see T-4).

### T-6. What is NOT yet explained (honest limit)
The precise intermittent path from "status COMPLETED" to "vector absent corpus-wide" is **not
pinned by static reading**. Candidates that survive T-4's single-snapshot-consistency argument and
need a runtime probe to confirm/refute:
1. a **commit/refresh vs. teardown** visibility gap — vectors written+status-set but the segment
   holding them not durably committed before `--clean`/eval snapshots the index;
2. a **late re-index** of a parent (`regenerateChunks` deletes existing chunks first,
   `ChunkDocumentWriter.java:87`) firing after backfill drained, recreating PENDING chunks that
   never re-embed;
3. a `preserve-reread`/segment-topology edge in `readFloatVector` (`getFloatVectorValues==null` on
   a segment that should carry the field, `:681-684`) under a specific merge state.

### T-7. Verdict, cheapest evidence, displacement
- **Should it be done at all?** Yes. Measured (2 independent degenerate hits, ~50% of a 4-run
  incidental sample), silent, corpus-wide halving of dense retrieval on the shipped default path;
  poisons every fresh-build number in the 691/711/712/713/704 arc. This is a structural-defect
  class (one incident suffices; `rule:structural-defects-no-repeat`), not a speculative
  abstraction.
- **Now?** Yes — diagnosis-first. Do **not** lead with the proposed 10× fresh-build loop; that
  measures *how often*, a second-order question. Lead with two cheaper moves: **(a)** the static
  fact in T-3 already invalidates the assumption that a COMPLETED pipeline implies a live chunk
  leg — no run needed; **(b)** capture **one** degenerate build with a *simultaneous* read-only
  `chunk_vector` KNN count (the 711/713 `VecProbe` — tool already exists) beside the status-based
  `chunkVectorCoveragePercent` and the combined-pass `worker.log` counters. If status-% is ~100
  while the live vector count is 0, T-3's "status lies / gate blind" is confirmed and the search
  narrows to commit/teardown vs. late-reindex (T-6). This is one run, not ten, and it tests the
  *mechanism*, not the rate.
- **Cheapest validating/invalidating evidence — does it exist?** Partially. The incidental 4-row
  table + the 713 §M-5 healthy `VecProbe` exist but are not a controlled correlate (M-5 only
  refuted "always dead", not "never" — `717:26`). The single degenerate-capture-with-VecProbe does
  **not** yet exist; the tooling for it does.
- **What it displaces/duplicates.** Does not duplicate F-032 (deterministic, fixed) — it extends
  that lineage. It would **displace** the interim "`chunk_merge`-in-legs" health-gate convention
  (712/713) by making the readiness gate itself presence-truthful. It is a **prerequisite** for
  trusting tempdoc 704's fresh-build measurement program. No overlapping tempdoc owns this; 717 is
  the right home.

### T-8. Suggested first slice for the design phase (not yet started)
1. Make readiness/pipeline-complete verify **actual `chunk_vector` presence** (KNN field count),
   not `chunk_embedding_status` counts — closes the silence in T-3 regardless of root cause, and is
   the `audit-without-test` regression anchor (a gate that would have failed the degenerate index).
2. Then run experiment T-7(b) to localize the residual intermittent write/commit path (T-6).

## Theorization (2026-07-11, pre-design — options and open forks, not a settled design)

### TH-1. Separate three defects; "stop the lie" may outrank "diagnose the race"
What reads as one bug is three separable ones:
- **D1 — the silence:** the completion/readiness gate verifies a `*_status` proxy, not the
  artifact (T-3). A *truthfulness* defect in the gate; real regardless of the race.
- **D2 — the race:** something intermittently yields "status COMPLETED, vector absent."
  A *write/commit correctness* defect.
- **D3 — measurement trust:** every fresh-build number in the 691/711/712/713/704 arc is suspect.
  A *scientific-validity* defect downstream of D1+D2.

They decouple. **Fixing D1 converts a silent quality-halving into a loud, reproducible failure**
— which is most of the value, because a loud failure is a debuggable, un-shippable one. So the
highest-leverage first move is plausibly "stop the lie," not "reproduce the timing." A loud gate
also *is* the regression anchor D2's eventual fix needs. This reframes the tempdoc's own "cheapest
first question" (a rate-measuring loop) as second-order.

### TH-2. Severity-defining fork: is the degenerate index DURABLE or a TRANSIENT the eval caught?
Not yet known, and it changes both severity and fix location:
- **Transient (eval-timing):** the backend was still embedding chunks when the eval snapshotted;
  the completion wait (`stable_polls_required=2`, 2 s interval — `readiness.py`) returned early on
  a slow-GPU run. Real desktop users (who don't `--clean`-then-immediately-eval) would never see
  it; the index self-heals if left running. Fix = tighten the wait condition. Lower user impact,
  but still corrupts D3.
- **Durable (on-disk corruption):** vectors are terminally absent even if the worker keeps
  running. Real shipping bug on chunked deployments. Fix = write/commit path.

**Cheapest disambiguator (one run, no code):** after a degenerate eval, leave the worker up, wait,
and re-probe the live `chunk_vector` count. Appears → transient; stays 0 → durable. F-032 was
durable (on-disk probe = 0), but that was the *deterministic* sibling; this one is unproven either
way. This fork should be resolved before the write-path is touched.

### TH-3. Second fork: vectors ABSENT vs. PRESENT-but-leg-skipped-at-query-time
We have inferred "vectors absent" from the F-032 analogy, not measured it for *this* anomaly. An
untested alternative: the vectors are present but the chunk KNN leg is intermittently **skipped at
search time** — e.g., a serve-time chunk-readiness capability gated on the same status-derived
coverage (`RagContextOps.isChunkVectorCoverageReady`, `:1077`) reads "not ready" and the executor
drops the leg. Same 0.34 symptom, entirely different fix (search-path gating, not write-path).
**The degenerate-capture (T-7b) must therefore record both** the live `chunk_vector` count *and*
whether the chunk leg was attempted, to split write-side loss from read-side skip.

### TH-4. A unified race theory: double-pop is the *exposure surface*, commit/refresh timing is the *trigger*
A single RMW is self-consistent (T-4), so divergence needs a *multi-write across a visibility
boundary*. The pieces compose:
- Chunk docs are born `SPLADE_STATUS=PENDING` **unconditionally** (`ChunkDocumentWriter.java:179`),
  independent of whether chunk-splade is enabled (default OFF per F-036). That PENDING status
  enrolls every chunk doc in the parent/splade cache in addition to the chunk-embed cache — so
  every chunk doc receives a **second, vector-omitting RMW** that leans on `preserve-reread`
  (the double-pop / parent-stamping of T-5). This is the exposure surface: it exists on the
  default path for the whole corpus.
- Whether that second RMW *preserves* or *drops* the vector depends on whether its searcher
  snapshot sees the vector the chunk-embed lane wrote — a function of commit cadence
  (`CombinedEnrichmentBackfillOps.java:634`, commit every 5 batches), suspended NRT refresh during
  the tight loop, and GPU timing. That is the timing-dependent trigger → intermittent by
  construction.

If this composite holds, a structural mitigation removes the exposure *without* pinning the exact
timing: **birth chunk docs with only the statuses that map to currently-enabled enrichments** (or
exclude chunk docs from the parent/splade enrichment queries). That collapses the double-pop, the
parent-stamping, and the splade-COMPLETED-without-data smell in one move. Attractive because it
attacks a *root smell* (status born for work that won't run) rather than a symptom. Risk: must keep
the chunk-splade-ON path and VDU/migration chunk-creation paths correct.

### TH-5. Candidate solution directions (menu, with tradeoffs — not yet chosen)
1. **Presence-truthful readiness gate** (count actual `chunk_vector` KNN fields). Truthful; O(n)
   over vectors, so decouple it from the frequent `/api/status` poll (run once at quiescence) and
   guard the short-doc/no-vector-field case so it can't hang (cf. the pre-394 disabled-stage hang).
2. **Status⇔payload divergence tripwire.** Assert `completed_status_count == vector_presence_count`
   at each commit/quiescence boundary and alarm on divergence. Cheapest *diagnostic* — self-reports
   at the exact moment D2 fires; doubles as the tripwire that keeps it from regressing. Risk: false
   alarms inside legitimate mid-batch windows → sample only at commit boundaries.
3. **Contract fix at the write boundary.** A status may become COMPLETED only if its non-stored
   payload is in the *same durable commit*. Strongest, structural; touches the write path.
4. **Remove the exposure (TH-4).** Birth-status hygiene / exclude chunks from parent lanes.
5. **Eval-program guard (D3).** Bake a per-run chunk-liveness assertion into jseval so no future
   eval silently runs on a dead-chunk index — the 712/713 "health-gate convention" formalized.
   Independent of the engine fix; quarantines measurement damage immediately.

These are not mutually exclusive: (5) protects the arc today; (1)/(2) make it loud; (3)/(4) fix
root. A likely sequencing is 5 → 1or2 → capture → 3or4, but that's for the design phase.

### TH-6. The broader invariant this lineage points to
F-032 → 711 → 712 → 717 are all the same shape: **a durable `*_status` field that claims the
presence of a non-stored data-bearing field, trusted by a consumer that never checks the data**
("receipt vs. goods"). The candidate invariant (to *articulate*, not yet design):

> **Artifact-truthful readiness** — no readiness/completion signal for an enrichment may be derived
> from its `*_status` field alone when the payload is non-stored; it must be corroborated by the
> payload's actual presence, or by a write-time invariant that guarantees status⇔payload agreement
> at every commit.

This would retroactively cover parent `vector` and `splade`, not just `chunk_vector` — all three
are non-stored + status-counted, so all three are latent F-032s; chunk merely has the largest
quality cliff and (via TH-4) the most write exposure. The principle is a sibling of the existing
"verify, don't guess" hard invariant and of the search-result-count-truthfulness work (tempdoc 597):
don't report a claim you haven't verified against the artifact. Whether to generalize now or fix
chunk first and generalize later is a scope decision for design — flagged, not settled.

### TH-7. Hidden assumptions worth challenging
- *"Same corpus + same command ⇒ deterministic."* False: FS scan order, thread scheduling, GPU
  arena timing, the stale-resolver re-index path (`JobBatchWriter.java:101`), and commit cadence
  are all nondeterministic. Intermittency is *expected*; don't over-invest in reproducing the exact
  timing — invest in making any occurrence loud (TH-1) and any divergence self-reporting (TH-5.2).
- *"The fix belongs to the write path."* Maybe the contract (TH-5.3) or the search path (TH-3).
- *"Chunk-death is rare."* The incidental sample was ~50% (2/4). Treat as common until a controlled
  rate says otherwise — and per `rule:structural-defects-no-repeat`, rate is not the trigger anyway.
- *"A late stale re-index can't matter on a clean build."* `regenerateChunks` deletes existing
  chunks first (`ChunkDocumentWriter.java:87`); a re-index firing after backfill drains would
  recreate PENDING chunks the completion gate (if made truthful) would still catch, but a status-
  only gate would not. Worth checking a degenerate run's `worker.log` for a late
  `deleteChunksForParentDocId` burst — cheap corroboration for TH-2's durable branch.

## Design (2026-07-11, pre-implementation; scope matched to the problem, not to a fix size)

### D-0. The consolidating fact the design is built on
Every distinct failure path this investigation surfaced converges on one necessary condition:
**`CHUNK_EMBEDDING_STATUS=COMPLETED` on a chunk whose `chunk_vector` is absent.** The readiness
gate cannot block, the serve-time gate cannot fail closed, and the coverage number cannot dip —
because all three read that status, not the vector. Enumerated producers of that lie (any one
suffices; the probe picks which is live):
- **P1 — blank-content mark-COMPLETED at enrollment.** A chunk doc whose `CHUNK_CONTENT` comes
  back null/blank from the batched fetch is marked `CHUNK_EMBEDDING_STATUS=COMPLETED` with no embed
  (`CombinedEnrichmentBackfillOps.java:292-300`). Same family as 712's splade-COMPLETED-without-
  encoding, on the chunk-embed field.
- **P2 — preserve-reread yields null (primary-source-backed front-runner; see §Research).** A
  later vector-omitting RMW (the double-pop / parent-splade lane, TH-4) re-reads `chunk_vector`;
  `LeafReader.getFloatVectorValues(field)` returning **null is documented-normal API behavior**,
  and our lane treats null as "nothing to preserve" and silently no-ops — unlike the `reset-status`
  lane, it does **not** downgrade the status, so it stays COMPLETED
  (`WritePathOps.applyRmwPolicies:333-338`, `readFloatVector:681-688`). Given `updateDocument`
  atomicity + single-snapshot consistency (T-4, confirmed), the reachable trigger is a
  merge/segment-metadata edge (Lucene #13626-class) where the vector field's per-leaf lookup misses
  on a doc that should carry it — intermittent by segment layout, durable once the rewrite persists.
- **P3 — never-written / commit-or-generation loss.** Embed reports success but the KNN field is
  not durably in the committed segment the eval snapshots (`--clean`/generation boundary, commit
  cadence `CombinedEnrichmentBackfillOps.java:634`). NOTE: the *partial-doc* variant (stored fields
  land, vector doesn't, inside one `updateDocument`) is **refuted** by confirmed per-snapshot
  atomicity (§Research Q1); P3 survives only as a whole-segment/generation-level loss.
- **P4 — late re-index** recreating chunks after the gate passed (TH-7).

This is the design's leverage: **one truthful-completion change neutralizes all four producers at
once (fail-closed), independent of which is live.** The producer-specific fix is a second,
narrower slice the probe scopes — not a prerequisite for the first.

### D-1. The spine: artifact-truthful chunk completion (make the signal verify the vector)
Replace, at every consumer that gates on it, "chunk complete = count of
`CHUNK_EMBEDDING_STATUS=COMPLETED`" with "chunk complete = count of chunks with an **actual live
`chunk_vector`**." Three consumers, one new capability:

1. **New worker capability — a live `chunk_vector` presence count.** No such thing exists today:
   the only code that reads actual float vectors outside search KNN is `WritePathOps.readFloatVector`
   (verified — repo-wide `getFloatVectorValues` has exactly that one non-search caller). Add a
   segment-iterating count (`FloatVectorValues` per leaf, minus deletions) in the adapters-lucene
   count surface (`IndexCountOps`), beside the existing status counts. Cost is O(live vectors); it
   runs at quiescence/readiness, **not** on every `/api/status` poll — decouple frequency from the
   truthful check so short/no-vector corpora don't pay and can't hang (mirror the pre-394 disabled-
   stage guard).
2. **Pipeline readiness (`jseval` `readiness.py`).** The `chunk_vectors_not_complete` predicate
   (`:157-160`) consumes the new presence-derived percentage instead of the status-derived
   `chunkVectorCoveragePercent`. Keep the `chunk_doc_count>0` guard but base it on chunk-doc
   presence; add the symmetric "chunk docs exist but zero vectors" as an explicit not-complete
   reason so Shape-1 and Shape-2 (T-6 / SearchPlanner `SKIPPED_NO_CHUNK_DOCS:265`) are both loud.
3. **Serve-time gate (`RagContextOps.isChunkVectorCoverageReady:1077`).** Same substitution —
   fail closed on true vector coverage, not status coverage.

The status counts (`IndexCountOps.queryChunkEmbeddingCounts`) are **not deleted** — they remain
legitimate *progress* telemetry. What is displaced is their role as the *gating/readiness* signal.

### D-2. Naming truth (a small but load-bearing correction)
`ChunkCoverageView.chunkVectorCoveragePercent` (doc-commented "Chunk vector coverage … for RAG
readiness", `ChunkCoverageView.java:10`) is today computed from `chunk_embedding_status` counts
(`IndexCountOps.java:142-147` → `IndexStatusOps.java:621-628`). The name promises vector presence;
the value delivers status presence. Either make the field actually vector-derived (preferred, D-1)
or rename it to `chunkEmbeddingCompletedPercent` and add a separate honest vector field. Leaving a
field named for an artifact while it reports a proxy is the exact ambiguity that let the silence
survive three tempdocs.

### D-3. The producer slice (probe-gated, mechanism-specific)
After D-1 makes any occurrence loud, run the T-7(b) capture to pick the live producer, then fix
just that one:
- **If P2 (preserve-reread null):** this is the hole 714 left open — 714 closed "undeclared
  fragile fields are destroyed"; it did **not** close "a *declared* `preserve-reread` field can
  still be dropped when the re-read misses, and unlike `reset-status` it leaves the status lying."
  The conforming fix is to unify the two dispositions: **`preserve-reread` gains the `reset-status`
  fallback** — on a null re-read of a field whose paired status reads COMPLETED, downgrade that
  status to PENDING (self-healing re-queue) instead of silently preserving nothing. This reuses the
  existing `applyResetStatus` seam (`WritePathOps.java:357-386`) rather than inventing apparatus,
  and it closes the fragile-field class on the *value-missing* axis 714 closed on the *policy-
  missing* axis.
- **If P1 (blank-content mark):** a null `CHUNK_CONTENT` for a chunk doc is a *fetch/consistency
  anomaly*, not a legitimately empty chunk (the writer skips blanks, `ChunkDocumentWriter.java:110`,
  and `chunk_content` is stored so RMW can't drop it) — so marking COMPLETED there is wrong; it
  should re-queue (leave PENDING) or fail loudly, never claim done without data.
- **If P3/P4:** commit-durability / re-index-ordering fix in the write or job-lifecycle path.

### D-4. Eval-program guard (conforms to 704 Pillar 3, not a parallel mechanism)
The 712/713 interim "`chunk_merge`-in-legs" convention is a hand-checked positive control. Fold it
into 704 Pillar 3's **fail-closed validity envelope** (owned by 675's executor): a run whose
chunked corpus produces zero live chunk vectors is INVALID and must not emit a quality number.
D-1's presence count is the machinery that envelope asserts on. This **orphans** the ad-hoc
convention (see D-6).

### D-3a. Research resolution (2026-07-11) — how it moved the design
A bounded authoritative-source pass (Lucene 10.4.0 javadoc, apache/lucene issues, changelog)
settled the write-path question that D-3 hinged on:
- **`IndexWriter.updateDocument` delete+add is atomic per reader snapshot** (10.4.0 javadoc). No
  documented path persists a doc's stored fields without its `KnnFloatVectorField` inside one call.
  → the *partial-doc* form of P3 is **refuted**; T-4's single-snapshot consistency argument is
  confirmed at the engine level.
- **`LeafReader.getFloatVectorValues(field)` returns `null` as normal API behavior** when no vector
  is indexed for that field on that leaf (10.4.0 javadoc). Lucene has itself mishandled this null
  (#13162 NPE; #13626 merge-time `fieldEntry==null` NPE). → **P2 is the front-runner**: our
  preserve lane's silent no-op on null is exactly the documented footgun, and it leaves the status
  lying. This makes D-3's "if P2" fix (unify `preserve-reread` with the `reset-status` fallback)
  the highest-value producer fix and grounds it in a real, documented hazard rather than a
  hypothesis.
- **`FloatVectorValues.vectorValue(ord)` may return a shared/reused buffer — callers must clone.**
  Our `readFloatVector` already `.clone()`s (`WritePathOps.java:690`) → no action, audited safe.
- **Lucene #15068 (cited in `CombinedEnrichmentBackfillOps.java:627-630`) is an MMapDirectory mmap
  *resource leak*, fixed in 10.x, NOT a data-loss/vector-loss issue.** The commit-cadence comment
  over-attributes it as a vector-loss rationale. Out of scope for this tempdoc — logged as an
  observation (comment accuracy only; behavior is fine).
- **External practice corroborates D-1:** Elasticsearch exposes a `dense_vector` indexed-count stat
  independent of any document/status flag; OpenSearch's k-NN community tracks the *absence* of that
  stat as a gap. Verifying real vector coverage rather than a status proxy is established
  mature-system practice — D-1 conforms to it, it is not novel apparatus.
- Sources are Apache-Lucene docs/issues (ASF-2.0); only URLs are cited, no text/code copied.

### D-5. Scope boundary (what this design deliberately does NOT do)
- It does not generalize the presence-truthful gate to parent `vector`/`splade` now (TH-6 names
  them as latent siblings). Chunk is the instance with a measured cliff and measured recurrence;
  the others are unmeasured. Generalize when/if a sibling is observed, per the principle's earning
  condition below — not preemptively.
- It does not retune fusion, weights, or chunk semantics (F-035/F-036 stand).

### D-6. What this orphans (deletion/tombstoning belongs to THIS tempdoc)
1. The interim "`chunk_merge` present in vector legs" health-gate **convention** (712 §Step-4,
   713 §M-5) — superseded by D-1+D-4; tombstone the convention note in the register's health-gate
   line once the envelope lands.
2. The **gating role** of `chunk_embedding_status` counts in `readiness.py` and
   `RagContextOps.isChunkVectorCoverageReady` — replaced by presence (the counts survive as
   telemetry).
3. The misleading name/semantics of `chunkVectorCoveragePercent` (D-2) — rename or re-derive.

### D-7. Regression anchor (the `audit-without-test` bar)
A test that builds an index in the degenerate shape — chunk docs with `CHUNK_EMBEDDING_STATUS=
COMPLETED` but no `chunk_vector` — and asserts that (a) the readiness gate reports NOT complete and
(b) the serve-time gate fails closed. This is authored from the *state*, not from a reproduced
race, so it does not depend on catching the intermittency; it pins the truthful-completion
contract directly. The producer fix (D-3) adds its own producer-specific regression once the probe
names it.

## Design reach — principle, scope, evidence, retirement

**Principle (named): Artifact-truthful readiness.** A readiness / completion / serve-gating signal
for an enrichment whose payload is a **non-stored** field must be corroborated by the payload's
*actual presence*, never derived from the paired `*_status` field alone. A status is a claim; the
gate must check the goods.

**Relation to existing seams (conform, don't fork).** This is the **consumer-side complement of
the fragile-field write-side invariant** (711/714: every non-stored data-bearing field declares an
RMW disposition, fail-fast at startup). 714 guarantees the write path won't *silently* drop the
field; this guarantees no *consumer* certifies "done" without checking it survived. Same bug-class
(F-032 "status lies"), opposite end of the pipe. On the eval side it conforms to 704 Pillar 3's
fail-closed validity envelope rather than adding a second positive-control mechanism.

**Candidate scope beyond chunks (named, not built).** The same status→artifact gap exists for
parent `vector` and `splade` — both non-stored, both status-counted at
`IndexCountOps.queryEmbeddingCounts`/`querySpladeFeatureCounts`, both gate-consumed. They are
latent instances of the same principle. Existing code already *violates* the principle for all
three non-stored fields; chunk is merely the one with a measured failure. Do not build the
generalized gate now.

**Evidence it would earn its keep.** After D-1 ships: (a) any future degenerate build fails the
readiness/serve gate *loudly* instead of shipping a silent 0.34 — i.e., the silent-halving class
stops reaching eval/users; (b) the D-7 regression stays green; (c) if a parent-`vector` or
`splade` sibling ever surfaces, the principle already names the fix site. If, across the next
several fresh-build campaigns, no degenerate index is ever caught by the truthful gate *and* none
slips past it either, the principle is confirmed and quiescent.

**Retirement condition.** Retire the principle (fold back to status-only signals) if the write-side
fragile-field closure (714) is ever proven *complete and sufficient* — i.e., a durable proof or
long empirical run shows `*_status=COMPLETED` can no longer diverge from payload presence under any
interleaving. At that point status *is* artifact-truthful and the separate presence check is
redundant apparatus. Until such a proof exists, the divergence is possible and the consumer-side
check earns its place.

## Derisk (2026-07-11, read-only verification; no feature code written)

Five implementation uncertainties were resolved by targeted read-only traces (three parallel
agents + direct reads). Result: the **D-1 spine is well-scoped with no surprise gates**; **D-3
fits the existing seam cleanly**; the only genuinely new code is a `liveDocs`-filtered vector count.

- **U1 — plumbing scope.** The pipeline gate the eval hits is `wait_pipeline_complete` →
  `_check_pipeline_complete_conditions` reading `chunkDocCount`/`chunkVectorCoveragePercent`
  (`readiness.py:157-160`); `--clean --pipeline` reaches it via `ingest_and_wait` (`ingest.py:83`).
  `indexing.proto` (`ChunkCoverage`, `:723-731`) lives under `modules/ipc-common`, **outside
  `contracts/**`** → **no `wire` gate**; the change adds a *query*, not a persisted `fields.v1.json`
  field → **no `ssot-catalog-sync`**. Two viable scopes: **(min)** redefine the existing
  `coveragePercent`/`vectorsReady` to be presence-derived worker-side (~3 files: `IndexCountOps`,
  `IndexStatusOps`, `RagContextOps`; no proto/view/schema churn; also fixes the D-2 naming lie —
  `readiness.py` already reads `chunkVectorCoveragePercent`); **(add)** a new
  `vector_present_count` proto field (~8 files + `:app-api:updateSchemas` + the `api-record`
  parity test). Recommend **min** unless a consumer genuinely needs status-% kept separate. The
  `extras` map (`WorkerStatusCache`) is **not** a shortcut — it reads from the same proto and feeds
  a different endpoint (`/api/knowledge/status`) the eval never polls.
- **U2 — the presence count.** Canonical per-leaf idiom already exists 3× (`WritePathOps.readFloatVector`,
  `RmwFieldPreservationTest.readVector`): `getFloatVectorValues(field)` → `DocIndexIterator` +
  `liveDocs.get(doc)`. `FloatVectorValues.size()` is O(1)/segment but **counts tombstoned vectors**
  (HNSW isn't purged until merge) → wrong; the correct live count enumerates + filters `liveDocs`,
  cost O(vectors present per segment) — **same complexity class as the existing status TermQuery
  counts**, i.e. no new cost tier. Reuse the reader-version `volatile` cache pattern
  (`IndexCountOps.getOrComputeCorpusProfile:285-309`) so the per-query serve gate doesn't regress.
  **Caveat:** do **not** copy `computeCorpusProfile`'s scorer loop — it omits `liveDocs` (pre-existing
  bug, logged to the inbox; counts deleted-but-unmerged parents).
- **U3 — D-3 policy + 714.** 714 (#144) is **merged and in my base** (no wait; its "unmerged"
  frontmatter line is stale). Vocabulary is exactly `preserve-reread` (vector-only) +
  `reset-status:<docValues target>`; a combined `preserve-reread-or-reset:<status>` is a clean,
  bounded addition: new constant + `validateRmwPolicies` branch + `applyRmwPolicies` branch +
  **schema regex** (`field-catalog.schema.json:32-36`, currently
  `^(preserve-reread|reset-status:[a-z0-9_]+)$`). No vector→status naming convention exists, so the
  status field **must be encoded in the policy string**. `chunk_embedding_status` /
  `embedding_status` are **`docValues=True`** → the reset-status target requirement is satisfied and
  `applyResetStatus` can correctly preserve FAILED. Reuses the existing `applyResetStatus` seam.
- **U4 — fix site** confirmed (see U1): `wait_pipeline_complete` in `readiness.py`.
- **U5 — regression harness: GO.** `RmwFieldPreservationTest` stands up a real ephemeral index with
  `createRuntimeWithChunkVector()`; the degenerate-state test is a **one-line omission** of the
  existing `chunkVectorSurvivesRmw()` (skip the `CHUNK_VECTOR` put, keep
  `CHUNK_EMBEDDING_STATUS=COMPLETED`), asserting the new count reports not-vector-complete while the
  status field still reads COMPLETED. `CombinedEnrichmentBackfillOpsTest` is the wrong harness
  (mocked index, no real KNN field).

**Confidence (0–10):** **D-1 spine = 8** (plumbing mapped, no scary gates, count idiom canonical +
cheap, fix site + regression test pinned; residual = the min/add scope pick and rerouting all three
consumers consistently). **D-3 producer fix = 8 mechanically** (fits the seam, docValues satisfied,
clear 4-point change + one regression), **but its *necessity* is probe-gated** — the T-7(b)
degenerate-capture must confirm P2 is the live producer before D-3 ships, else it may fix a
non-cause. **Overall remaining work ≈ 7.5–8.**

**Difficulty / model recommendation.**
- **D-1 spine** — moderate, mechanical, multi-module but fully mapped with canonical patterns and a
  defined test → **sonnet, medium-high effort**; the plan should pin the min-vs-add scope decision
  so the worker just executes.
- **D-3 producer fix** — small but **correctness-critical write-path** (a wrong policy string
  fails startup for everyone; a wrong apply branch silently loses vectors — the exact class we're
  closing). → **opus (or sonnet-high with mandatory independent review)** for the
  `WritePathOps`/`FieldMapper` apply+validate core, sonnet for the catalog/schema edits; the
  degenerate regression (U5) is the merge gate. **Gate D-3 on the T-7(b) probe first.**

## Implementation — Phase 1 landed (2026-07-11, branch `worktree-717-chunk-death`, unmerged)

Ship-now core (D-1 + D-2 + D-3 code-evident producers + D-7 + D-6). All local unit verification
green; live probe (Phase 2) pending. No PR opened.

**1a — presence count.** `IndexCountOps.queryChunkVectorPresenceCount()` (+
`LuceneRuntimeTypes.ChunkVectorPresence`) counts live docs with an actual `chunk_vector` by
enumerating each leaf's `FloatVectorValues` filtered by `liveDocs` (tombstones excluded — `.size()`
would overcount), reader-version cached like `getOrComputeCorpusProfile`.

**1b — consumers rerouted (worker-side only, min scope).** `IndexStatusOps.buildEnrichment`
computes `ChunkCoverage.coveragePercent`/`vectorsReady` from presence; `completed/pending/failed`
stay status-derived as progress telemetry. `RagContextOps.isChunkVectorCoverageReady` counts real
vectors. **No proto/view/schema change** — the existing `chunkVectorCoveragePercent` field the
jseval readiness gate already reads is now presence-truthful, so `readiness.py` needed **no change**
(its `chunk_vectors_not_complete` block now trips on a degenerate build). This also resolves D-2:
the field named for vectors now means vectors.

**1c-P1.** `CombinedEnrichmentBackfillOps` blank-`CHUNK_CONTENT` chunk now escalates via
`computeChunkEmbeddingFailureUpdate` (retry, FAIL at max) instead of marking COMPLETED-without-data.

**1c-P2.** New `preserve-reread-or-reset:<statusField>` rmwPolicy: `FieldMapper` constant +
validation (vector-only, docValues target, shared `validateResetTarget` helper); `WritePathOps`
apply branch (re-read; on null → `applyResetStatus`); schema regex; catalog (both copies) —
`chunk_vector`→`chunk_embedding_status`, `vector`→`embedding_status`; test builders
(`FieldCatalogDef`, `RmwFieldPreservationTest`) migrated for fidelity.

**1d — regression tests (all green).** `RmwFieldPreservationTest`: degenerate-state presence
detection; P2 null-re-read downgrades COMPLETED→PENDING; P2 present-vector still preserved; two
startup-validation rejections. `CombinedEnrichmentBackfillOpsTest`: P1 blank-chunk escalates, not
COMPLETED.

**1e — teardown.** Register F-036 caveat annotated: the presence-truthful gate supersedes the
712/713 hand-checked `chunk_merge`-leg convention (pending live validation). Repro manifest
regenerated (idempotent).

**Verification run:** `spotlessApply`; `build -x test` (all modules); module suites
`adapters-lucene` / `worker-services` / `configuration` / `app-api` / `app-services` green;
`readiness.py` pytest 38-green; gates `language-agnostic-analysis` + `ssot-catalog-sync` pass.

**Critical-analysis note (known side-effect).** `vector` (parent) now carries the combined policy
too (per plan D-3), so a *blank-content* parent (legitimately `embedding_status=COMPLETED` with no
vector) will reset→PENDING→re-COMPLETE once on any *unrelated* RMW that touches it — bounded,
self-correcting churn (one status write, no real re-embed), not a correctness issue; parent
embedding coverage is unaffected (still status-derived). Chunks don't have this case post-P1.

**Full-suite catch (verify-your-work paid off).** The full `./gradlew.bat test` (not just the
touched modules) caught a real bug the targeted tests + static review both missed:
`GrpcIngestServiceVduHardeningTest` REJECTED_SUSPECT_TEXT / SUCCESS_EMPTY failed because the P2
fallback reused `applyResetStatus`, which force-PENDINGs a *null* status (SPLADE's "heal missing
status" semantics) — so a parent that never claimed embedding (VDU-rejected/empty, mid-ingest) was
spuriously enrolled for embedding. **Fix:** the `preserve-reread-or-reset` lane heals **only** a
genuine COMPLETED-with-no-vector lie (`STATUS_COMPLETED` gate in `WritePathOps.applyRmwPolicies`);
null / PENDING / FAILED are left untouched (plain preserve-reread behavior). My P2 tests only
covered the COMPLETED case, which is why the gap slipped past them and the review — the full suite
was the backstop.

**Independent review (reviewer ≠ implementer, refute-first).** No blockers, no correctness bugs;
the write-path logic, liveDocs counting, threshold conversions, and dispatch order were verified,
and the acknowledged parent-`vector` churn was independently confirmed bounded/correct. Two
test-fidelity gaps it flagged were closed: (a) the parent-`vector` fixture was migrated to the
production `preserve-reread-or-reset:embedding_status` policy + a parent reset test added (the
production parent policy previously had zero coverage — `unreachable-seed-green`); (b) a multi-cycle
blank-content chunk test now proves the P1 path reaches `CHUNK_EMBEDDING_STATUS=FAILED` at max
retries. Confirmed invariant: `ChunkDocumentWriter:110` skips blank chunks, so a blank read in the
combined pass is genuinely an anomaly (P1's premise). Inherited note (not this change): the
reader-version count cache shares the pre-existing corpus-profile cache's non-atomic check-then-set
pattern — out of scope.

**Deferred to Phase 2+ (per plan):** the live T-7(b) probe (dev stack), P3/P4 (non-code-evident
producers), and D-4 (jseval per-run chunk-liveness envelope guard).

## Live probe (Phase 2, 2026-07-11) — the charter's vector-loss framing is REFUTED; actual root cause found

Ran the faithful probe on `mixed/legal-clerc-200` (corpus.jsonl sha256 `630f5376…` — the same bytes
as the F-032/712/713 runs), fresh `--clean --pipeline` build against the Phase-1 code
(`git_sha=bba1cfe`). **The degenerate state reproduced on the first build** — and the diagnosis is
the opposite of the charter's assumption.

### Evidence chain (each step measured, not inferred)
1. **Reproduced:** vector nDCG@10 = **0.3403**, hybrid 0.4891; observed vector legs
   `[dense, query_classification]` — `chunk_merge` **absent**. Exact degenerate signature.
2. **The build shipped "healthy":** the run COMPLETED (exit 0); the Phase-1 loud gate did **not**
   block it. Status snapshot: `chunkDocCount=4293`, `chunkVectorCoveragePercent=100.0` (this is the
   Phase-1 **presence-truthful** metric — so the vectors are genuinely all present),
   `chunkVectorsReady=True`, `chunkAwareEnabled=True`, `chunkEmbeddingCoverage=1.0`.
3. **Chunks + vectors are healthy — NOT lost.** This directly **refutes** the F-032 / vector-loss
   framing (T-1..T-8, D-0..D-3, TH-4) for this intermittent bug. The chunk sub-system is fully built.
4. **Deterministic on the index, not a stale cache:** a *fresh backend* restarted over the same
   on-disk index re-produced 0.3403 with the same legs — refuting the stale in-memory
   corpus-profile-cache hypothesis (TH-2/TH-3 "leg-skipped" fork was right; the "vectors absent"
   fork was wrong).
5. **The actual skip reason (debug search `searchTrace`): `SKIPPED_SHORT_CORPUS`.** The planner
   dropped the chunk branch because `corpusSupportsChunks() = hasChunkDocs && !isShortCorpus` was
   false — `isShortCorpus()` was **true**.
6. **Why short-classified:** `isShortCorpus() = medianTokenCount() < 512 || chunkRate() < 0.05`
   (`CorpusProfile.java:53-55`). `chunkRate = 4293/198 = 21.7` (not <0.05), so the trigger is
   `medianTokenCount() < 512`. `medianTokenCount()` returns **0 when `docsWithTokenCount == 0`**
   (`:36-47`). A corpus that chunked 198 docs into 4293 pieces (~21×) cannot truly have median <512
   tokens — so `parent_token_count` is unpopulated on the parents (empirically, parent hits return
   `parent_token_count=None`).
7. **Why `parent_token_count` is missing — the intermittency source:** `deriveParentMetadata`
   sets it **only if the SPLADE encoder is ready at index time** (`IndexingDocumentOps.java:378-390`:
   `parentTokenCount = null; if (spladeEncoder != null) parentTokenCount = spladeEncoder.tokenCount(...)`).
   On a fresh `--clean` build the SPLADE encoder loads asynchronously; a small corpus (198 docs) can
   be fully indexed **before** SPLADE is ready → no parent gets `parent_token_count` →
   `docsWithTokenCount≈0` → median 0 → short → `SKIPPED_SHORT_CORPUS` → chunk_merge dropped → 0.34.
   **Intermittent** exactly because it is a SPLADE-load-vs-indexing race; builds where SPLADE is
   ready in time populate the field → not-short → chunk_merge runs → 0.62.

### What this means for the Phase-1 code
The Phase-1 work (D-1 presence-truthful gate, D-3 P1/P2 producer fixes, D-7 tests) is **correct
hardening of a real but DIFFERENT problem** (vector-presence truthfulness + two latent status-lie
holes). It does **not** fix this bug and does **not** catch it: the gate correctly reports 100%
vector coverage because the vectors *are* present — the failure is a serve-time planner
mis-classification, invisible to a vector-presence gate. This is the value of gating the real fix on
the probe (which the plan did): the "code-evident producers" P1/P2 were plausible but are not the
live cause.

### The real fix (design sketch — NOT yet implemented; awaiting founder direction)
The bug is the **same *artifact-truthful* principle this tempdoc already named** (§Design reach),
now on a third field: the short-corpus gate trusts a proxy (`parent_token_count`) that can be
missing and **fails CLOSED against chunks** on missing data (`median 0 → short`). Candidate fixes,
not mutually exclusive:
- **A — populate `parent_token_count` independent of the SPLADE-load race** (root cause): compute it
  from a cheap always-available tokenizer/char estimate at index time, or backfill it when SPLADE
  becomes ready (it is deferred-enrichable like embeddings). Preferred — removes the race entirely.
- **B — make `isShortCorpus` robust to missing token data** (defense in depth): a corpus with a high
  `chunkRate` (chunks exist ⇒ docs exceeded the chunk threshold ⇒ not short) must not be called
  "short" just because token counts are absent; treat `docsWithTokenCount≈0` as *unknown → fail-OPEN
  for chunks*, not *short*. This is the artifact-truthful move: don't let a missing proxy silently
  drop a leg.
- **C — the loud gate should cover leg-eligibility, not just vector presence:** a fresh chunked-corpus
  build whose planner would emit `SKIPPED_SHORT_CORPUS` while chunk vectors are 100% present is the
  real degenerate signal D-1/D-4 should assert on (extends D-1 beyond vector presence).

### Founder decision needed (§Next)
1. Keep the Phase-1 branch as independent hardening (own commit/PR or fold into 714's fragile-field
   lineage) vs. drop it — it is correct but orthogonal to 717's actual bug.
2. Authorize the real fix (A ± B ± C) as 717's implementation, re-scoped around the SPLADE-race /
   short-corpus mis-classification. A is the smallest root-cause fix; B+C harden the class.
Probe artifacts: `tmp/717-probe/run1/…/summary.json` (degenerate), `tmp/717-probe/requery/…`
(fresh-backend re-repro), `searchTrace stage 5 = SKIPPED_SHORT_CORPUS`.

## Resolution & re-scope (2026-07-11) — fix A + B on `717-shortcorpus-fix`

Founder authorized (2026-07-11): set Phase-1 aside, implement A + B.

**Decision 1 — Phase-1 vector-presence hardening: NOT merged.** It is correct code but addresses a
class already closed write-side (F-032 fixed; no live producer) and orthogonal to this bug (the
degenerate build passes its 100%-coverage gate). Preserved on branch `717-phase1-hardening-ref`
(commits `f571fbc`/`dc8f2b3`/`bba1cfe`/`72aae31`) for reference; not on the fix branch.
- **Durable insight recorded (not built):** 714's fragile-field closure has a *value-missing* gap —
  a declared `preserve-reread` field whose index re-read returns null (a documented-normal
  `getFloatVectorValues` outcome; Lucene #13162/#13626) silently drops and can leave its status
  lying. A ready implementation (`preserve-reread-or-reset:<status>`, healing only a COMPLETED lie)
  exists on `717-phase1-hardening-ref` if a live producer ever appears. Per the design-skill
  discipline: recognize the principle, defer the structure until a real need exists.

**Decision 2 — the real fix (this branch, `717-shortcorpus-fix`, from base `2fb2b01`):**
- **A (root cause) — `parent_token_count` is never null.** `deriveParentMetadata`
  (`IndexingDocumentOps.java:378-390`) fell back to `null` when the SPLADE encoder wasn't ready;
  add an always-available estimate fallback so the field is always populated (keeps the exact SPLADE
  count when SPLADE is ready). Removes the race at the source.
- **B (harden the class) — `isShortCorpus` fails OPEN for chunks on missing data.**
  `CorpusProfile.isShortCorpus()` treated `medianTokenCount()==0` (== *unknown*, from
  `docsWithTokenCount==0`) as *short*. Change so the token-median test only fires when token data
  actually exists (`docsWithTokenCount > 0`); with no data, only `chunkRate < 0.05` can classify
  short — so a corpus that produced chunks (chunkRate high) is never called short on absent tokens.
  Fail-open is the safe direction: running chunk_merge on a genuinely short corpus is
  hybrid-neutral (F-036), whereas skipping it on a long corpus halves quality (the 0.34 we saw).
- **C (leg-eligibility gate) — deferred.** Once A+B fix the root, the heavy "readiness asserts
  planner leg-eligibility" is YAGNI. A cheap per-run "chunked corpus but `chunk_merge` skipped →
  invalid" assertion is worthwhile measurement insurance but belongs with 704/675, not here.
- **Verification:** unit — `CorpusProfile` with `docsWithTokenCount==0` + high `chunkRate` →
  `isShortCorpus()==false`; `deriveParentMetadata(spladeEncoder=null,…)` → non-null token count.
  Live — a fresh `--clean` build on `legal-clerc-200` must now show the `chunk_merge` leg and
  vector nDCG ≈ 0.62 (the probe that found the bug is the regression check).

### Live validation (2026-07-11, fix @ `51b6f80`) — FIXED, deterministically
Full unit suite green (`./gradlew.bat test` BUILD SUCCESSFUL). Two consecutive fresh `--clean
--pipeline` builds on `legal-clerc-200` (same corpus/command that reproduced the degenerate 0.3403
on its *first* try with the old code):

| build | vector nDCG@10 | vector legs | hybrid nDCG@10 |
|---|---|---|---|
| old code (run1) | **0.3403** (degenerate) | `[dense, query_classification]` — no chunk leg | 0.4891 |
| fix1 (`51b6f80`) | **0.6196** | `[branch_fusion, chunk_merge, dense, query_classification]` | 0.5593 |
| fix2 (`51b6f80`) | **0.6197** | (chunk_merge present) | — |

2/2 healthy with the fix vs degenerate-on-first-try without it, and the fix is deterministic by
construction (`parent_token_count` is now always populated, so the corpus profile never sees a 0
median). The intermittent chunk-death is closed. Matches the historical healthy pin (0.6185/0.6187,
hybrid 0.5588/0.5592).

### Independent review (reviewer ≠ implementer) — no blockers; two hardenings applied
The refute-first review confirmed both fixes correct (truth-table walk clean, tests pin the fix,
race fully closed — `deriveParentMetadata` is the sole `parent_token_count` writer and now
non-null on every path). Actioned findings:
- **Finding 2 (applied):** `content.length()/4` gave exactly 500 tokens at the 2000-char chunk
  threshold (< 512) — a corpus of borderline-chunking docs (or CJK, fewer chars/token) could still
  read short. Changed the estimate to `length/3` so any *chunked* doc (≥ 2000 chars) reads > 512 for
  any script (errs toward "long"/fail-open). Added an ASCII+CJK boundary test.
- **Finding 1 (applied):** `isShortCorpus` now requires MAJORITY token coverage
  (`docsWithTokenCount * 2 >= parentDocCount`) before trusting the median — a minority covered
  subset can't drag a large corpus short. Added a partial-coverage regression.
- **Findings 4/3 (noted, no code change):** feeding an *estimate* (vs. the prior `null`) to the
  SPLADE/chunk-branch length-multiplier fusion weights is a behavior change during the SPLADE
  cold-start window — but empirically benign: the live nDCG is at/above the historical pin
  (vector 0.6196/0.6197 vs 0.6185; hybrid 0.5593 vs 0.5588), so the estimate-fed weighting did not
  regress quality. The estimate persists (no retroactive backfill to the exact SPLADE count once
  warm) — consistent with the pre-existing design where the race-affected `null` was also permanent.
- **Finding 5 (logged):** the estimate is written into feedback/telemetry `parent_token_count`
  indistinguishably from an exact count (no provenance flag) — out-of-scope observation for anyone
  analysing that field's distribution offline.

The hardened merge-candidate (`c93818b`) was itself re-run fresh: **vector nDCG 0.6202,
`chunk_merge` present; hybrid 0.5626** — 3/3 healthy fresh builds across the fix (0.6196 → 0.6197 →
0.6202), verifying the exact code that would merge (not just an invariance argument).

**Status: fix implemented + independently reviewed (two hardenings applied) + live-validated
end-to-end on the merge-candidate `717-shortcorpus-fix @ c93818b`.**

**Merging BOTH (founder decision 2026-07-12).** The Phase-1 `chunk_vector` presence-truthful
hardening (714-lineage; §Implementation) is folded onto the merge branch as *complementary*
hardening — NOT the fix, and it does not catch this bug. The combined merge-candidate
(`90b980b` = short-corpus fix + presence hardening) was itself live-validated fresh: **vector
0.6199 / hybrid 0.5623, `chunk_merge` present, chunkVectorCoveragePercent 100%** — the first live
exercise of the presence gate (it gated on chunk vectors during enrichment and passed at
completion, no hang). Full combined unit suite green; catalog + language-agnostic gates pass.
4/4 fresh builds healthy across the fix. Ready to publish.
