---
title: "Lane E — search-quality re-derivation: chunking, small-to-big, parameter sweeps (Parts 1/2 closed; Part 3 parked; Part 4 delegated)"
type: tempdocs
status: "CLOSED (2026-09-04) — Part 1 retains 500/50/100 after 32 clean, admissible runs and a formal early-stop proof; its unchanged 2000-character threshold is analytically characterized as a prefilter, not quality-optimized. Part 2 is REFUTED and fully REVERTED. Part 3 is operationally PARKED after an inadmissible legal A/B: the observed standard-profile record regressed gold-span visibility, but changing, unequal background enrichment prevents causal attribution. The landing branch reverts the candidate and a local safety branch preserves it for a terminal-index, anchor-aware rerun. Part 4 launches no Lane-E sweep: fusion/reranker work remains with 854, while the otherwise reachable BM25, efSearch and title-boost candidates lack a pre-registered hypothesis and held-out selection protocol. All residue is explicitly routed; no Lane-E benchmark remains."
created: 2026-09-03
updated: 2026-09-04
lane: E (decision re-examination programme, wave 2)
model: opus (implementation)
parent: 00-program-overview (decision re-examination programme, 2026-09-01 audit)
coordination: "→ lane D handoff is complete: retain 500/50/100, retain the analytically justified but not quality-optimized writer threshold at 2000, and keep algorithm version v1; main already fingerprints all five static inputs. → tempdoc 854 owns the W2 pool/limit coupling guard and clean reranker-window work, so Part 4 does not duplicate those experiments. → Lane A's per-request ContextBudget merged in ca1e75fa. Lane E's decision work is complete and Part 3/4 are not F-lane blockers; only normal Lane-E branch integration remains before F consumes it."
related:
  - 774-passage-first-retrieval-program   # the chunk-branch levers this Part 2 lever sits beside
  - 854-fusion-residue-lane               # CHARTER; owns the W2-fix, not this
  - 912-wave1-residue-worker-watcher-and-commit-floor  # commit-floor facts; cadence numbers here are recorded, not acted on
  - 885-decision-review-lane-c-runtime-lifecycle-and-isolation  # detached-driver method, game-contamination rule
---

# Lane E — search-quality re-derivation

The 2026-09-01 audit found four decision clusters in the retrieval path that were set early, mostly
by weaker agents, and never re-derived. This tempdoc is lane E's contract. It restates the brief's
items as a checklist (§A), records a source-verbatim verification of every `file:line` claim the
brief makes (§B — the brief was written before wave 1 landed, so every citation is a hypothesis),
plans and executes the chunk-size campaign (§C, §K, §L), and implements and measures Part 2 (§D–§G).

---

## §A Scope checklist

### Part 1 — target/overlap campaign and threshold follow-up CLOSED

Prerequisite: none technical; it is deferred behind Part 2 only because Part 2 ships independently
and Part 1 costs machine-days. Preparation is logged in §K and the 2026-09-03/04 execution and
decision are logged in §L.

- [x] **Instrument built** — four TEMPORARY `EnvRegistry` keys at ordinal 400, resolved onto
      `ResolvedConfig.Index` and reaching the Worker through the ordinal-450 snapshot, with
      deletion committed in the changeset (§K.1). *Four, not §C.1's three: `min_tokens` is a
      measured confounder, see §K.2.*
- [x] **Doc-length distributions measured** (§C.3's open question, §K.3) — the two multilingual
      corpora chunk 0.6% / 0.3% of their documents and are **controls, not arms**;
      `ohr-bench-clean` is promoted from stretch goal to third arm corpus. 45 runs, not 72.
- [x] **RAG question-set fixture built** — `scripts/jseval/916-corpora/rag-qa-v1/`, 150 qrel-derived
      question/gold-doc/gold-span triples, no LLM, digests pinned (§K.4).
- [x] **Campaign driver retained for evidence analysis** — `scripts/jseval/916_chunk_sweep.py`
      remains resumable/admissibility-filtered for its original throwaway branch, but `run` now
      refuses the shipping tree because the temporary Worker-bound keys are gone. It cannot silently
      label default indexes as challenger arms.
- [x] **Decision rule pre-registered BEFORE any arm runs** (§K.5).
- [x] Reconcile `OnnxEmbeddingEncoder.chunkOverlap` (128) with the splitter overlap (50) or
      document why they differ — **resolved as "document why", with a named invariant** (§K.2).
- [x] Sweep `DEFAULT_CHUNK_TOKENS ∈ {128, 256, 384, 500} × overlap ∈ {0, 25, 50}` with
      sentence-aligned boundaries: full legal and OHR matrices plus every Enron arm still capable
      of adoption. Seven decision-inert Enron cells were pruned under §L.4's recorded deviation.
- [x] Metrics per executed arm: nDCG@10 + R@10 and leg-union recall; SPLADE truncation rate; index
      size; indexing throughput. `R@50` never existed in jseval; §K.5 records that correction.
- [x] RAG answer quality disposition: not run. It can only veto a clause-1–5 survivor, and §L proves
      there is none; running it cannot promote an arm.
- [x] Deliver `(chunk_tokens, overlap, min_tokens) = (500, 50, 100)` with σ-aware evidence. No
      production constant moves. The four campaign keys are deleted, and Lane D's merged
      fingerprint already reads the static values plus `ALGORITHM_VERSION = "v1"`.
- [x] **Threshold follow-up:** §C.2/§K.5's ambiguous post-selection method is superseded. The shipped
      value is the canonical typical-prose conversion
      `TokenEstimation.charsForTokens(ChunkSplitter.DEFAULT_CHUNK_TOKENS) = 2000`: a cheap
      one-typical-window writer prefilter. `ChunkSplitter` and the writer's one-chunk guard still
      decide whether chunk documents exist. This is analytically derived and operationally
      characterized, **not quality-optimized**; CJK can map 500 tokens to a different character
      span, and no per-language threshold lever is introduced (§L.6).

### Part 2 — parent collapse fix (CLOSED — built, measured three times, REFUTED, REVERTED)

Every item below was **done**, and the answer the work produced is that the mechanism does not help.
The proposal is therefore reverted, not shipped: this PR leaves production code byte-identical to
`main` and keeps the characterization tests plus the measurement.

- [x] Over-fetch chunk hits by a configurable multiple before cutting. *(built; reverted §J.4)*
- [x] Aggregate per parent with `max + λ·Σ(decayed rest)`, λ configurable, λ=0 ≡ today. *(built;
      reverted)*
- [x] Cut to the collapse cap only after aggregation. *(built; reverted)*
- [x] Collapse stays deterministic for the eval gate — **this survives**, now pinned by
      `SearchExecutorChunkCollapseCharacterizationTest` against the shipped collapse.
- [x] Two config keys through `EnvRegistry` / `ResolvedConfigBuilder`, `config-surface` changeset and
      baseline advanced in the same commit. *(built; **removed** in the revert, and the
      `config-surface` pin returns to 111 / 250 in that same commit — the growth was authorised only
      on condition of deletion if the mechanism did not ship.)*
- [x] Unit tests for the aggregator, incl. a pre-916 oracle for the λ=0 equivalence claim. *(deleted
      with the mechanism; the oracle's purpose — proving λ=0 ≡ today — is moot once λ does not
      exist.)*
- [x] Integration test through the collapse on a real chunked Lucene index. *(deleted with the
      mechanism.)*
- [x] Falsification: every new test broken once, observed failing, restored (§F). 17/17.
- [x] Same-index A/B on the chunked corpora + a short-corpus control, ratchets green (§G).
- [x] Pre-registered ship/park rule written before the measurement ran (§D.7).
- [x] Register finding `F-056` (§H) — now recorded as **refuted**, with every run id.
- [x] **Owner-authorised λ sweep (§I)** — rule committed before running (`96a088f5`); 18 arms,
      17 admissible; parked.
- [x] **Independent review (§J), BL-1: the aggregate never reached the branch blend**, so §G and §I
      measured set membership, not aggregation. Fixed, rule re-registered and committed before the
      run (`52d35001`, 7m37s before the first arm), decisive A/B run: **10 arms, 10 admissible, no
      λ passes, leak worsens somewhere at every λ → REVERT** (§J.4).
- [x] **Kept:** `SearchExecutorChunkCollapseCharacterizationTest` (8 tests) pinning the shipped
      collapse, including audit finding 2 as an executable statement of the limitation, and the
      committed A/B driver `scripts/jseval/916_collapse_ab.py`.

### Part 3 — small-to-big retrieval (DESIGN + RULE FROZEN; candidate PARKED)

Lane A's per-request `ContextBudget` merged in `ca1e75fa` (#599). A source-level design pass and an
independent refute-first review now agree on the smallest safe implementation. Retrieval and
ranking continue to produce **anchor chunks**. Only after final ranking/diversification, Worker
assembly may turn an anchor into one larger **context unit** consisting of that anchor followed by
its immediate next persisted chunk. The next chunk is admitted only when it belongs to the same
parent, has the exact same `(heading_level, heading_text)` tuple, is neither excluded nor another
selected anchor, and has valid monotone absolute offsets whose overlap text agrees exactly. The
context is anchor-first and the stored overlap is emitted once. Any missing or inconsistent
metadata degrades to the anchor alone.

Forward-only is deliberate, not a quality claim. Both Worker and Head budgeters retain prefixes.
Prepending a predecessor could therefore consume a partial-section budget before the cited anchor
appears; protecting an anchor in the middle would require a wider offset contract through Worker,
the app API and Head cutting. The shipped 50-token overlap already carries limited backward
context. One immediate successor yields at most the natural two-chunk window without creating a
radius/size setting. Synthetic/virtual chunk hits stay anchor-only.

`RagContextOps` will represent `(anchor, context)` separately: sections contain the actual expanded
context while `usedHits`, ranking scores, quality counts and citations retain the anchor. The
existing positional invariant—section `i` ⇔ citation `i` ⇔ source-array entry `i`—must remain exact.
Head will pass the same positive `ContextBudget.inputBudget()` for explicit-`docIds` retrieval as it
already does for open retrieval, removing that conversation path's stale 200K-character opt-out;
protocol callers that explicitly send zero keep the legacy character fallback. After Head applies
its final cut, citation verification sources must carry the literal visible section text, because
an ordinal lookup can recover only the anchor and not its appended neighbour. Inclusion status is
measured against the anchor span: an intact anchor is `INCLUDED` even if an expanded suffix is cut;
cutting inside the anchor is `PARTIAL`.

No proto field, response array or configuration key is added. `ContextChunk` remains the cited
anchor and `ContextSection.content` already carries the prompt-visible context unit, so the wire
gate is not triggered. Exact neighbour lookup is batched under one Lucene searcher after ranking;
the request `ContextBudget` remains the sole final cap.

#### Part 3 bounded A/B rule — written before the baseline

The overnight evidence window is intentionally bounded to the existing 50-query
`mixed/legal-clerc-200` RAG fixture. It uses one retained-geometry index for both arms, `top_k=10`,
`max_context_tokens=4096`, and the required `standard` chat profile. Baseline runs before production
edits; the after arm uses the same materialized queries and index. Enron's roughly 38-minute rebuild
is deferred as confirmation, not silently represented as completed.

Primary measurement is deterministic **gold-span-in-visible-context rate**, newly reported by
`tier2-eval`; it directly tests whether the added context exposes the fixture's answer span. The
same record also reports answer `has_intersection`, errors, `context_truncated`, included context
sections, context size and retrieval latency. It does **not** preserve included anchor identities or
an anchor count; that post-run discovery makes this specific pair permanently incomplete for clause
5. Commit `7e032ad9` subsequently makes canonical `tier2-eval` capture and validate ordered anchor
identities/counts and produce a fail-closed paired comparison. Exact/substring answer scores remain
diagnostic because the fixture answer is a passage span rather than a canonical short answer.

The candidate earns a **GO** only if all of these hold:

1. The structural tests prove anchor/citation identity, overlap de-duplication, heading/exclusion/
   selected-neighbour guards, synthetic fallback, budget cutting and literal verification text.
2. Gold-span-in-context does not regress and at least one baseline miss becomes visible. No newly
   visible span means **PARK**: correct machinery without measured fixture utility is insufficient.
3. `has_intersection` loses no more than one of 50 queries (the evaluator uses temperature 0.1),
   after-arm errors do not exceed baseline, and paired wins/losses are disclosed.
4. Average retrieval latency rises by no more than `max(25 ms, 25% of baseline)`. A breach is
   **PARK** pending a deeper latency study rather than waived from a single run.
5. `context_truncated` and included-anchor changes are reported, not optimized away. Any truncation
   increase accompanied by anchor starvation or a quality loss fails clauses 2–3; the boolean alone
   is not a rejection because both arms obey the same 4096-token cap.

- [x] Separate retrieval unit from context unit in `RagContextOps` assembly in the parked candidate.
- [x] Add forward immediate-neighbour expansion with the fail-soft guards above in the parked
      candidate.
- [x] Align scoped Head retrieval and literal citation verification with the one-budget contract in
      the parked candidate.
- [x] Run the frozen legal A/B without post-result threshold edits. The closest standard-profile
      pair is inadmissible because enrichment changed differently during the arms; result:
      **NO-VERDICT, operationally PARK** (§Part 3.1).
- [ ] Re-evaluate the `parent_token_count` ramps once small-to-big exists; retire with a sweep if
      their reason is gone (coordinate with 854, which owns the branch-weight ramp).

#### Part 3.1 — implementation evidence, bounded A/B and PARK decision

**Structural implementation and review.** The candidate kept ranked/cited anchors separate from
prompt-visible context units, batched exact successor lookup under one Worker searcher, admitted
only a same-parent immediate successor with identical heading metadata and exact overlap, and made
Head citation verification use the literal post-cut visible section. Focused verification passed:
`RAGContextTest`, `RemoteDocumentServiceCitationLookupPolicyTest`,
`RagContextOpsSmallToBigTest`, `GrpcSearchServiceRetrieveContextTest` and
`ChunkSearchIntegrationTest`, followed by the locked full `build -x test` (251 tasks, 42 seconds).
After the final malformed-int64 regression was added, the focused Worker pair passed again (55
tasks, 15 seconds). Independent refute-first review found and closed three P1/P2 defects before the
live run: dropped-section lookup, non-additive estimator overflow, and expansion across another
selected anchor. The final implementation review reported no remaining P0/P1. These candidate-only
tests and code are preserved together at `6aa99a37`; the landing branch intentionally reverts them.

**Why the first attempts are not evidence.** A foreign registered eval interrupted the first fresh
baseline at query 3; its partial output was discarded. Direct standard-profile startup then left
the 12 GB GPU with insufficient headroom: one retrieval succeeded and eight consecutive requests
hit the backend's 15-second deadline, while generated answers repeatedly hit the evaluator's
120-second retry limit. Compact-profile retrieval completed, but background enrichment advanced
and diverged during the two arms (baseline 23/50 gold spans, candidate 19/50; four apparent wins,
eight losses), so that pair is diagnostic only. No partial or failed output is used below.

**Closest production-profile pair.** The supported runtime sequence was compact startup to let the
Worker allocate its ONNX sessions, followed by switching to the required `standard` profile before
the first measured request. Both arms began from SHA-256-identical copies of the same retained
index (131 files, 37,357,250 bytes), used the same 50-query materialization, `top_k=10`,
`max_context_tokens=4096`, query order and Qwen 3.5 9B model, and completed 50/50 with zero errors.
Raw records:

- baseline: `scripts/jseval/tmp/916-part3-rag/baseline-standard-warm-v1/tier2-eval.json`, SHA-256
  `5B132C1B2A5031940EC2FC9213EE23760B24FF285A25C8368486EED6D6E6F125`;
- candidate: `scripts/jseval/tmp/916-part3-rag/candidate-standard-warm-v1/tier2-eval.json`, SHA-256
  `8C5F664D7D3C820C68119AC0638AD1F38D13E7F58139F6B8CA9AAEAC2A156FCC`.

| frozen signal | baseline | candidate | paired/change | admissible reading |
|---|---:|---:|---:|---|
| gold span in visible context | 31/50 (62%) | 25/50 (50%) | 1 miss→hit, 7 hit→miss | would FAIL clause 2 |
| answer `has_intersection` | 2/50 (4%) | 2/50 (4%) | 0 wins, 0 losses | passes clause 3 |
| errors | 0 | 0 | unchanged | passes clause 3 |
| `context_truncated` | 49/50 | 50/50 | one false→true | report; no independent veto |
| mean retrieval latency | 2533.70 ms | 2587.04 ms | +53.34 ms (+2.11%) | passes 3167.13 ms ceiling |
| `retrieval_chunks` | 448 total / 8.96 mean | 282 / 5.64 | lower on 49/50 | context-unit shape changed; **not an anchor count** |
| approximate context tokens | 4071.36 mean | 4100.02 | +28.66 | both remained at the cap |

**Admissibility failure.** The two byte-identical inputs did not remain the same logical index.
Shortly before the first request, observed parent `(embedding, SPLADE, NER)` queue state was
approximately `(22,106,5)` for baseline and `(30,202,5)` for candidate; it continued mutating to
`(114,115,40)` and `(114,124,71)` respectively by arm end. Chunk embeddings stayed 2/4124.
Additional enrichment can add a gold anchor or add competitors that displace it, so it is not a
monotone favourable bias and can plausibly reverse a six-query net difference. The saved evaluator
records omit anchor ids and literal contexts, so none of the eight changed gold-span outcomes can
be attributed between changed ranking input and neighbour expansion/budget cutting.

**Verdict: NO-VERDICT; operationally PARK.** If admissible, the result would reject the candidate
decisively on clause 2 and would show near-universal reduction in included sections. It is not
admissible enough to claim a causal regression, but it cannot earn a GO. The landing branch removes
the candidate in `0f95a8d6`; local branch
`codex/lane-e-part3-parked-candidate-20260904` preserves it at `6aa99a37`. The evaluator-side
prerequisite is closed in `7e032ad9`: new records retain ordered structured anchor identities,
validate producer counts and decision-bearing response fields, fingerprint the evaluator protocol,
and compare only evaluator-compatible records while explicitly leaving same-index and enrichment-
stability admission checks external. Reopen only with (1) a terminal, non-changing retained index
throughout both arms, (2) the same standard-profile/runtime sequence, and (3) the same pre-registered
thresholds. Completing the 4,122 pending chunk embeddings was the hour-scale path explicitly skipped
in this bounded window; no Enron confirmation or full SciFact ratchet is claimed.

**Evaluator verification.** From `scripts/jseval`, the final focused command
`python -m pytest tests/test_agent_retrieval_eval.py tests/test_compact_model_guard.py
tests/test_tier2_context_truncated.py tests/test_tier2_anchor_comparison.py -q` passed 82 tests in
0.57 seconds. `python -m compileall -q jseval`, `python -m jseval tier2-eval --help`, and
`git diff --check` also passed. An independent refute-first review found five initial and two
follow-up fail-closed/protocol-coupling defects; all received production-seam regressions, and the
final re-review reported no P0/P1/P2 findings.

### Part 4 — parameter sweep (CLOSED AS ROUTED; no Lane-E campaign)

The original candidate list is dispositioned rather than silently left pending:

- **854 owns fusion and reranker depth.** Its measured pool/window arms are already parked. Before
  another A/B it requires the pool/limit splice coupling to be guarded or decoupled and canonical
  `jseval` support for the clean `PipelineConfig.crossEncoderWindow` lever. Lane E does not duplicate
  those cells or reinterpret their confounded results.
- **BM25 `k1`/`b`, `efSearch`, and `title_boost` are reachable, but reachability is not a research
  hypothesis.** This lane has no pre-registered target class, held-out selection protocol, or
  interaction rule for them. Launching the old grids now would be post-hoc parameter fishing. Any
  future campaign needs a new explicit charter and owner before a run.
- **`efConstruction` and HNSW `M` remain Lane-D fingerprint inputs, not query-time Lane-E levers.**
- **No Part-4 results table or baseline update is produced.** Tempdoc 235's historical “No sweep”
  rows are not falsely marked calibrated; the reason they remain unresolved is now explicit.

This is a terminal Lane-E disposition. Part 4 neither blocks Lane F nor authorizes background
benchmarking while 854's structural prerequisites remain open.

---


**§B (pre-implementation pass: source-verbatim verification of every `file:line` claim in the brief)** moved to `docs/tempdocs/916-evidence/pre-implementation-verification.md` (size-cap split, 930 §19.3 F4).

**§C (Part 1 campaign plan: design only — NOT executed)** moved to `docs/tempdocs/916-evidence/part1-campaign-design-and-execution.md` (size-cap split, 930 §19.3 F4).

**§D (Part 2 — design and implementation log)** moved to `docs/tempdocs/916-evidence/part2-design-and-critical-pass.md` (size-cap split, 930 §19.3 F4).

**§E (post-implementation critical pass)** moved to `docs/tempdocs/916-evidence/part2-design-and-critical-pass.md` (size-cap split, 930 §19.3 F4).

## §F Falsification record

Every new test was broken once with a targeted mutation, run in isolation, observed failing, and
restored. Drivers: `tmp/falsify-916.py`, `tmp/falsify-916b.py` (gitignored; they restore the file in
a `finally` block). **17 of 17 failed as expected; 0 did-not-fail.**

| test | mutation applied | verdict | observed |
| :--- | :--- | :--- | :--- |
| `defaultsReproducePre916` | expected top score 0.90f → 0.91f | FAILED-AS-EXPECTED | `AssertionFailedError at …AggregationTest.java:87` |
| `overfetchWithoutLambdaIsOrderPreserving` | expected order p-mid/p-spread swapped | FAILED-AS-EXPECTED | `…:97` |
| `spreadEvidenceOvertakesAtLambda` | expected order reverted to max-only | FAILED-AS-EXPECTED | `…:107` |
| `cutAfterAggregation` | ON expectation set to the OFF result | FAILED-AS-EXPECTED | `…:121` |
| `remainderIsBounded` | expected order reversed | FAILED-AS-EXPECTED | `…:136` |
| `permutationOfEqualScoredSiblings` | compared against a wrong literal order | FAILED-AS-EXPECTED | `…:149` |
| `deterministicAcrossInvocations` | first call switched to (1, 0.0) | FAILED-AS-EXPECTED | `…:160` |
| `tiesKeepFusedOrder` | expected docId order instead of fused order | FAILED-AS-EXPECTED | `…:172` |
| `parentlessHitIsItsOwnParent` | expected order reversed | FAILED-AS-EXPECTED | `…:182` |
| `overfetchClampedToOne` | comparison arm changed to (5, 0.9) | FAILED-AS-EXPECTED | `…:191` |
| `configReachesTheCollapseParameters` | expected multiplier 5 → 1 | FAILED-AS-EXPECTED | `…IndexIntegrationTest.java:156` |
| `fixturePrecondition` | `>= COLLAPSE_LIMIT` → `<` | FAILED-AS-EXPECTED | `…:169` |
| `aggregationRecoversTheSpreadParent` | ON expectation set to the OFF list | FAILED-AS-EXPECTED | `…:192` |
| `chunkCollapseAggregationDefaults` | expected default multiplier 1 → 5 | FAILED-AS-EXPECTED | `ResolvedConfigBuilderTest.java:967` |
| `chunkCollapseAggregationFromYaml` | expected YAML-resolved 5 → 1 | FAILED-AS-EXPECTED | `…:984` |
| `chunkCollapseAggregationClamped` | expected clamp 1.0 → 4.5 (unclamped) | FAILED-AS-EXPECTED | `…:1002` |
| `chunkCollapseAggregationEnvRegistryKeys` | config key string corrupted | FAILED-AS-EXPECTED | `…:1008` |

---


**§G (measurements: Part 2 A/B protocol, replicate spread, verdict against the pre-registered rule)** moved to `docs/tempdocs/916-evidence/part2-measurements-and-review.md` (size-cap split, 930 §19.3 F4).

**§I (λ sweep, owner-authorised follow-up arm — PRE-REGISTERED BEFORE RUNNING)** moved to `docs/tempdocs/916-evidence/part2-measurements-and-review.md` (size-cap split, 930 §19.3 F4).

**§J (independent review, NEEDS-FIXES — the lever did not do what its javadoc said)** moved to `docs/tempdocs/916-evidence/part2-measurements-and-review.md` (size-cap split, 930 §19.3 F4).

**§K (Part 1 execution log — preparation window, 2026-09-03, NO backend)** moved to `docs/tempdocs/916-evidence/part1-campaign-design-and-execution.md` (size-cap split, 930 §19.3 F4).

**§L (Part 1 result — incumbent retained; threshold rationale closed analytically, 2026-09-03/04)** moved to `docs/tempdocs/916-evidence/part1-result.md` (size-cap split, 930 §19.3 F4).

## §H Register updates made

- **`F-056`** added to `docs/reference/search-quality-register.md`: **audit finding 2 measured and
  refuted at both the set-membership and the score-aggregation level**, with every run id, across
  three index builds and two chunked corpora. It records the verdict, the four structural findings
  that outlast it (the CC pool floor is exactly 0.0; σ(R@10)=0 but not σ(nDCG); the `degraded-ce`
  upward bias above the 2% tolerance; scifact as a genuine inert control), and the withdrawn claims
  from the §J review.
- **No baseline row changed, and now none can have.** The verdict is REVERT: production code is
  byte-identical to `main`, so no shipped default and no canonical number moved. The register's own
  rule did its job — a number changes only with a run id and a σ statement, and here the σ statement
  says the effect is not there.
- **F-056 is a closing entry, not an open thread.** It exists so the next reader of the 2026-09-01
  audit finds the measurement instead of re-running the campaign. What would reopen it is named
  there: a different aggregation shape, or a corpus whose evidence is genuinely spread over many
  mid-ranked passages — not another λ point.
- Skill mirror regenerated with `node scripts/docs/skills-sync.mjs`.
- **`F-057`** records Part 1's 32 admissible runs, calibration, decision-preserving early stop and
  retained 500/50/100 geometry. It explicitly distinguishes the campaign's 2000 ms deadline from
  the shipped 200 ms default. It also records the separate threshold disposition: the unchanged
  2000-character prefilter is analytically tied to one canonical typical-prose 500-token window,
  operationally exercised, and not quality-optimized.

---

## Cross-lane requests

**Current resolution (2026-09-04).** Lane D merged before this closeout and main fingerprints
target, overlap, minimum, threshold and `ALGORITHM_VERSION` directly from `ChunkSplitter`. Part 1
retains 500/50/100 and `v1`; the shipped 2000-character threshold is now explicitly characterized
as an analytic one-typical-window prefilter rather than a quality optimum, so the historical request
below is superseded and no cross-lane edit remains. Tempdoc 854 retains ownership of the W2
pool/limit and clean reranker-window work; Part 4 will not duplicate it.

- **HISTORICAL, SUPERSEDED — Lane D request as recorded before Lane D merged.**
  `SsotCommitMetadataSource.java:179-185` on `origin/worktree-lane-D` builds
  `IndexFingerprint.Chunking` from **five bare static reads** — `ChunkSplitter.DEFAULT_CHUNK_TOKENS`,
  `DEFAULT_OVERLAP_TOKENS`, `MIN_CHUNK_TOKENS`, `CHUNK_THRESHOLD_CHARS`, `ALGORITHM_VERSION` —
  while the HNSW block three lines above it (`:172-178`) correctly resolves through
  `rc.index().effectiveVectorHnswM()`. As written, an index built by a lane-E sweep arm would be
  fingerprinted with the SHIPPED chunk parameters, i.e. the fingerprint would say 500/50/100/2000
  for an index actually chunked at 128/25/26/2000 — silently mixable, which is the exact failure
  your fingerprint exists to prevent. **Requested change, four lines, mirroring your own HNSW
  pattern:** read `rc.index().effectiveChunkTargetTokens()` / `…OverlapTokens()` / `…MinTokens()` /
  `…ThresholdChars()` (these accessors exist on lane E's branch and fall back to the same
  `ChunkSplitter` constants when unset, so your `rc == null` guard still works and an un-swept build
  fingerprints identically to today). **Which base was verified:** the accessors were built and
  tested on lane E's base `305bb039`, where **no chunking fingerprint exists at all** — the reads
  are entirely lane D's unmerged addition (`git show 305bb039:…SsotCommitMetadataSource.java`
  contains no `ChunkSplitter` reference). So lane E cannot make this change without editing lane D's
  file, and programme rule 4 says lane E hands lane D numbers, not diffs.
  **DECIDED by the owner 2026-09-03, and this is an explicit, recorded exception to programme rule
  4:** the four-line change is **authorised as an orchestrator-approved cross-lane edit made in lane
  E's final Part 1 PR**, after #620 merges and lane E rebases onto it. Lane D's tempdoc carries the
  mirror note. Until then the sweep is unaffected — every arm is a `--clean` rebuild and the
  fingerprint is never read *across* arms, so a fingerprint that records the shipped constants
  cannot mislead a comparison that never consults it.
  (1) Part 1 will hand you a chosen
  `(chunk_tokens, overlap, threshold)`; it is not ready yet and Part 2 does not block on it.
  (2) Please accept or amend a **chunker version string** as a fingerprint input —
  proposal `ChunkSplitter.CHUNKER_VERSION = "v2-<tokens>-<overlap>-<mode>"`, derived from the three
  constants so it cannot drift from them. Lane E will not edit the fingerprint (programme rule 4).
  (3) Part 2 changes **no** index shape and needs no rebuild — it is query-time only, so it can merge
  in either order relative to D.
- **Tempdoc 854 (fusion residue).** W1/W2 are partly merged, but the W2 pool/limit coupling and clean
  reranker-window work remain owned there. Part 4 therefore does not re-sweep global rerank top-k or
  start fusion cells. A bounded efSearch experiment is technically separable, but Part 4 lacks a
  pre-registered hypothesis and held-out selection protocol, so Lane E closes without launching it.
- **Owner — all three decisions ANSWERED, recorded here so Part 1 inherits them.**
  (1) §C.4's RAG question-set fixture: **yes, but as Part 1 step 0**, not as part of Part 2.
  (2) §C.1's temporary `EnvRegistry` keys for the chunk-size campaign: **acceptable**, on the
  condition already in the plan — deleted in the PR that lands the chosen constants, with the
  `config-surface` pin returned in that same commit. Part 2 is the worked precedent for that
  condition being real: the keys were authorised, the mechanism did not ship, and both keys and the
  pin came back out in the landing commit (§J.4).
  (3) Whether to keep the two parked keys: **moot** — the §J review found the mechanism inert at
  defaults, the re-run refuted it, and the standing instruction was revert-together-with-the-keys.

## Open items

1. **Parts 1 and 2 are CLOSED; Part 3 is PARKED; Part 4 is CLOSED AS ROUTED.** The 32
   admitted runs and pruning proof retain 500/50/100
   (§L). The threshold follow-up is also closed without a sweep: 2000 is the canonical
   four-chars-per-token conversion of the selected 500-token target, used only as a cheap
   one-typical-window prefilter; actual emission still requires the content-aware splitter to return
   more than one chunk. It is analytically derived, operationally exercised and not
   quality-optimized (§L.6). Part 3's window policy, explicit-docIds semantics and candidate were
   implemented and structurally reviewed, but its live A/B is inadmissible because enrichment did
   not remain fixed (§Part 3.1). The candidate is removed from the landing branch and preserved only
   in a local parked checkpoint pending a terminal-index rerun with anchor-aware evidence. Part 4
   launches no campaign: 854 owns fusion/reranker depth, and other candidates require a new charter
   with a pre-registered hypothesis and held-out protocol.
2. **CLOSED by §K.3** — doc-length distributions are measured for all six corpora, and they
   reassign three of them: `miracl-de-2k` (0.6% chunked) and `miracl-fr-2k` (0.3%) become
   no-regression controls rather than arms, and `ohr-bench-clean` (62.8%) becomes the third arm
   corpus. Note the tension recorded there with §J's "scifact is chunk-free": 14.4% of scifact
   documents DO exceed the writer threshold, so that claim is about the corpus-profile gate at
   search time, not about the writer.
3. **ANSWERED by §K.2, second opinion still owed.** The encoder's 128 is a model-token window
   stride inside one encode, not a document splitter; `createChunks` never sees a document
   boundary and its input is either a stored `CHUNK_CONTENT` or a whole parent. Resolved to
   documentation plus the named invariant in §K.2. This is a second *reading*, not an independent
   *reviewer* — an independent confirmation is still the honest requirement.
4. **CLOSED** — the two private copies in `modules/system-tests`
   (`PassageRetrievalVectorGenerator`, `PassageRetrievalIntegrationTest`) now derive from
   `ChunkSplitter`'s static constants instead of restating three literals.
5. **Commit/reopen cadence numbers recorded in §G are not acted on** — the floor is
   `BackfillScheduler.CYCLE_BUDGET_MS`, chartered as 912 open items 8/9.
6. **Still open, and partially worked around.** A `datasets/` junction exists in the lane-E
   worktree, so `mixed/*` resolves; BEIR does not, because `beir/scifact` lives in the shared
   `ir_datasets` cache under the MAIN checkout and `dataset_cache.apply_ir_datasets_home()`
   resolves to the worktree's own empty cache root. `916_doc_length_profile.py` and the fixture
   generator both fall back to the main checkout's cache root explicitly; that fallback is a
   workaround in two scripts, not a fix. **`prepare-worktree.cjs` does not make `datasets/`
   available in a worktree** (lane 0's file, so not edited here). `jseval` resolves `<repo-root>/datasets/mixed/<name>/corpus.jsonl`, which only
   exists in the main checkout, so every `mixed/*` corpus fails in a worktree. Fix: seed a junction
   the way models and the cuda runtime already are (`node scripts/dev/prepare-worktree.cjs` already
   resolves both from the main checkout — this is the same pattern, one more path).
7. **Worked around in the driver, not fixed at source.** `916_chunk_sweep.py` sets
   `JSEVAL_HEALTH_TIMEOUT_SEC=300` in its child environment. The underlying defect stands:
   **`JSEVAL_HEALTH_TIMEOUT_SEC` defaults to 120 s**, below a cold worktree backend boot (~150 s
   observed). The first campaign attempt lost three arms to it before the cause was clear. Either
   raise the default or have `backend.py` distinguish "still starting" from "failed".
8. **CLOSED by §J.4 — Part 2 is finished, not parked.** §I swept the λ axis but, per the §J
   review, measured only set membership; §J fixed the emission and re-ran, and no λ passes the
   pre-registered rule while `leak_rate` worsens somewhere at every λ. Mechanism and keys are
   reverted. What would reopen the question is **not** another λ point but a different aggregation
   shape — the §D.4 pool floor is why low-λ arithmetic cannot reach the parents this was meant to
   rescue — or a corpus whose evidence really is spread over many mid-ranked passages, which neither
   chunked corpus in the register turns out to be.
9b. **CE deadline is a campaign gate, decided BEFORE the sweep (§K.9).** The 256/50 smoke arm
   voided on `degraded-ce` from 21/200 `DEADLINE_EXCEEDED` (incumbent: 1/200) — halving the chunk
   size roughly doubles the passages the cross-encoder reranks. If most small-chunk arms void, the
   sweep measures nothing. Fix a single arm-invariant CE deadline large enough for the 128-token
   arm and record it as a campaign constant, or budget re-runs; do not tune it per arm, which would
   put a second lever inside the experiment.

9. **`ce_coverage` degradation on legal is an UPWARD bias above the 2% tolerance, not noise** (§I.6,
   F-056 finding 4). Four of four legal void arms beat their clean comparators at drop rates
   4.5–20.5%, and F-055 supplies the mechanism. Below the tolerance the sign reverses and the effect
   is sub-0.001 jitter. Any future campaign on legal that averages over CE drops will get a wrong
   answer with a plausible-looking mean.
10. **The `wrong-gate` lesson from §J.6 is not yet routed anywhere durable.** "Verify the quantity
   you compute is the quantity the next stage consumes" is a generalisation of an existing named
   handle rather than a new one, and CLAUDE.md's `before-appending-to-rules` gate says edit the
   existing line rather than add prose. Flagged for the owner: `wrong-gate` in
   `agent-postmortems.md` currently covers wrong *gates/flags*; this case is a wrong *output field*
   with the same shape. Lane E did not edit that file, being outside its scope.

11. **CLOSED — `scripts/jseval/tests/test_run.py::test_execute_run_always_emits_a_cadence_block`
   had gone red on main.** It asserted `summary["cadence"]` equals an exact four-key dict
   (`test_run.py:1004`) while the emitter also writes `commit_by_reason` and
   `commit_by_reason_total` (`jseval/cadence.py:197-198`, shipped in `33ffc3bb`, which did not
   update the test). Verified pre-existing by removing every 916 Part 1 file and re-running the
   test alone. Routed by **extending the existing `jseval-pytest-missing-optional-deps-local-env`
   pin** rather than adding a second one: `known-state-hint.test.mjs` requires every pin's
   `exitProbe` to route back to exactly one pin, and the existing pin already matches every
   `python -m pytest` invocation, so a second entry is ambiguous by construction. The extended
   claim distinguishes the two reds by SHAPE (collection error = missing optional deps; assertion
   inside a collected test = this one) **with the fix tracked here**, per `log-pre-existing-issues`:
   The expected dict now includes the two `commit_by_reason` keys; the targeted closeout test is the
   verification. Main being red was a defect, not a fact to remember.

12. **`R@50` does not exist in jseval and the campaign cannot report it** (§K.5's deviation note).
   Emitted metrics are `nDCG@10, AP@10, RR@10, R@10, P@1` (`scripts/jseval/jseval/scoring.py:9`)
   and the retriever's default depth is 10 (`retriever.py:105,197`). The decision rule uses R@10 +
   `leg_union_recall` instead. Making R@50 real is an owner decision with a real cost (every arm at
   `--top-k 50`, no comparability with existing baselines) and is recorded here rather than
   silently substituted.

13. **CLOSED by §K.9 and §L — the driver ran the campaign and now fails closed.** It confirmed that
   the temporary keys reached the writer and captured 32 admitted arms. The attempted resume exposed
   the marker-propagation defects in §L.5; non-zero arms and decision-bearing nulls now prevent
   corpus/run completion, and generated chains stop on non-zero child exits. §K.10.3 also corrects
   the original Tier-2 premise: Tier 2 does not use the title matcher, while Tier-1 binding was
   verified without an LLM. Superseded text:
   **The sweep driver had never driven a real arm.** `916_chunk_sweep.py`'s roll-up math is tested
   against synthetic run trees only; its `--start-backend --clean --pipeline` path, the SPLADE
   evidence sidecar wiring (`JUSTSEARCH_SPLADE_EVIDENCE_PATH`, default null) and the index-size /
   docs-per-second keys it reads are all unexercised against a live run. First action of the sweep
   window: one throwaway arm on the cheapest corpus, read `arm-metrics.json`, confirm every field
   is populated — before launching an overnight batch that would otherwise produce 45 rows of
   `null`.

14. **Part 3 rerun requirement is explicit, not an invitation to repeat the same contaminated
    pair.** The evaluator prerequisite is CLOSED in `7e032ad9`: `tier2-eval` now retains ordered
    anchor identities/counts, fingerprints its protocol/config, fails closed on malformed evidence,
    and attaches an evaluator-compatible paired comparison through `--baseline-results`. The
    comparator does not claim experimental admissibility: it emits `external-check-required` for
    same-index identity and enrichment stability. The remaining prerequisite is a terminal index
    whose enrichment counters do not move for either 50-query arm. The standard 9B
    profile is required during measurement. A compact-start → standard-switch may allocate the
    Worker models safely on a 12 GB GPU, but its pre-measurement enrichment must also be identical.
    The hour-scale completion of 4,122 pending chunk embeddings was deliberately not run in this
    bounded window.

15. **CLOSED by §L.8 — the OHR reconciliation mismatches were an instrument defect, and the fix is
    in.** The 105/962 mismatches came from a left-anchored `parts[2]` TREC read truncating the 109
    OHR doc ids that contain a space, not from anything the run did. `scripts/jseval/jseval/trec.py`
    now owns one right-anchored parser and one tab-delimiting writer; all three in-repo readers use
    it. Corrected: `final_recall` 0.8784 → 0.9875, `leg_union_recall` 0.8794 → 0.9886, `LEG_MISS`
    115 → 10, mismatches 105 → 0 — and all four per-leg recalls now equal the harness's
    `ir_measures` `R@10` exactly. **The Part 1 verdict is unchanged** (the rule's metrics never read
    the TREC file; the offset is constant across all 12 OHR arms, so every delta holds). Remaining
    non-item: the 12 OHR arms' *archived* `staged_recall_accounting.json` files still carry the
    biased absolutes; they were deliberately not rewritten, and §L.8 is the correction of record.

## Report-back

- **Part 1 target/overlap verdict: RETAIN 500/50/100.** Thirty-two clean, admissible runs establish the
  incumbent noise band, the full legal and OHR matrices, and every Enron cell still capable of
  satisfying the two-of-three adoption rule. The best surviving Enron delta is +0.006093 against a
  required >0.0136; no challenger can be adopted (§L).
- **Execution deviation:** seven decision-inert Enron cells, downstream controls and RAG judging
  were skipped only after the necessary-condition proof was reconstructed. This early stop was not
  pre-registered and is disclosed as such. The 2000 ms CE deadline was campaign-only; the shipped
  200 ms control remained inside the incumbent band. No release baseline or production constant
  changes.
- **Part 1 landing shape:** the temporary four-key override path and its config-surface growth are
  removed. The static policy remains fingerprinted by Lane D with algorithm version `v1`. The
  qrel-derived RAG fixture, historical evidence analyzer, guarded runner and evidence record remain.
  The fixed 2000-character threshold stays shipped as the canonical typical-prose conversion of one
  500-token window and as a prefilter only; characterization pins the writer's additional
  single-chunk guard. This closes the threshold follow-up analytically and operationally, not as a
  quality optimization.
- **PR:** one, open, **not merged** — retitled
  `test(916): characterize the chunk-collapse and close audit finding 2 as refuted (lane E part 2)`.
  It contains **no production-code change**: `git diff origin/main -- 'modules/*/src/main'` is empty.
- **Verdict: Part 2 is REFUTED and REVERTED**, by the rule committed in `52d35001` at 03:34:57 UTC,
  7m37s before the first arm of the decisive A/B (`20260903T034234`). Ten arms, ten admissible
  (`ce_coverage: ok` + `comparable` on every one), replicate spread 0.0000 on R@10, 24 clean machine
  signatures. No λ beats the noise floor on both chunked corpora, and `leak_rate` worsens on at least
  one corpus at **every** λ tested — condition 2 fails outright, independently of the R@10 clause.
- **Part 3 verdict: NO-VERDICT; operationally PARK and remove from the landing branch.** Both
  standard-profile arms completed 50/50 with zero errors, but enrichment changed differently during
  the serial runs. The observed record would fail the frozen rule (gold span 31/50→25/50, one paired
  win/seven losses; truncation 49/50→50/50; included sections 8.96→5.64), while generated-answer
  intersection stayed 2/50 and retrieval latency rose only 53.34 ms. Those numbers are evidence
  against a GO, not causal evidence of a regression. §Part 3.1 records hashes and the exact rerun
  admission conditions.
- **What three campaigns actually established.** §G and §I measured whether a wider collapse scan
  changes set membership: it does not help. The §J review then found the aggregate was a dead sort
  key, so §J re-measured whether score aggregation helps: it does not either, and it costs leak. The
  answer to the 2026-09-01 audit's finding 2 is therefore **negative at both levels**, on two chunked
  corpora across three independent index builds — not "unknown", and not "parked pending evidence".
- **Items:** Parts 1 and 2, including Part 1's threshold follow-up, are closed. Part 3's Lane-A
  dependency is merged; its anchor-first immediate-successor candidate is structurally complete but
  parked and removed from the landing branch after an inadmissible A/B. It is not an F-lane blocker.
  The evaluator-side rerun prerequisite is closed in `7e032ad9`; the comparison proves evaluator
  compatibility only. A terminal retained index plus externally verified enrichment stability is
  still required to revisit the parked decision.
  Part 4 is closed as routed: 854 owns fusion/reranker depth and no other sweep was sufficiently
  specified to launch.
  Three implementation deviations are recorded in §D with their reasons; all three are moot now that
  the mechanism is reverted. One brief premise was **wrong**: over-fetching the chunk *hits* already
  existed at ×10 (§B.12); the starved stage was the collapse scan.
- **What survives, and it is not nothing.** (a) `SearchExecutorChunkCollapseCharacterizationTest`,
  8 tests, pinning a collapse that had **no direct unit coverage before this lane** — including audit
  finding 2 as an executable statement of the limitation, plus determinism, best-score carry,
  fused-order tie-breaking, parentless hits and sibling-evidence merging. (b) `F-056`, closing the
  audit finding with every run id. (c) The committed A/B driver
  `scripts/jseval/916_collapse_ab.py`, so the admissibility filter that decides whether an arm counts
  is auditable rather than living in gitignored `tmp/` — a direct §J review finding.
- **Evidence:** 17 new tests were written and every one was broken once and observed failing before
  being restored (§F table); 9 of them died with the mechanism, 8 remain. Every metric claim in §G,
  §I and §J carries a `2026MMDDTHHMMSS_<corpus>` run id. **No throughput or latency number from any
  of these campaigns is cited as evidence anywhere** — §G's index build was contended, so those
  numbers are void by construction.
- **Corrections I had to make to my own earlier claims**, all raised by the independent review and
  all now fixed in place: the legal +0.0050 R@10 was index-build-specific and is **withdrawn**
  (BL-2, three builds, appeared once); "legal identical to four decimals at every λ" was wrong at
  λ0.05 (BL-3, −0.0017 nDCG); "σ is 0" is an **R@10** claim and does not extend to nDCG@10, where
  sub-tolerance CE drops move the fifth decimal (BL-4); and the void-arm count was 3, not 5 (§I.6).
- **Cross-lane status:** Lane D is resolved—main fingerprints the retained static tuple and `v1`.
  Tempdoc 854 continues to own the impure pool/limit and reranker-window work; §D.4's finding that
  branch fusion min-max normalizes each branch (pool floor exactly 0.0) remains a durable constraint.
- **Residue found outside scope and where it was routed:**
  1. **`prepare-worktree.cjs` does not provide `datasets/`** — every `mixed/*` corpus lives only in
     the main checkout, so any worktree running jseval fails with `FileNotFoundError: corpus.jsonl
     not found at <worktree>/datasets/mixed/<name>/corpus.jsonl`. Worked around with a gitignored
     junction; routed to open item 6 with the concrete fix, since that file is lane 0's.
  2. **`JSEVAL_HEALTH_TIMEOUT_SEC` defaults to 120 s**, below a cold worktree backend boot (~150 s
     observed); the first campaign attempt lost three arms to it. Open item 7.
  3. **The `wrong-gate` handle does not currently cover a wrong *output field*** — same shape,
     different surface. Open item 10, flagged rather than edited: `agent-postmortems.md` is outside
     lane E's scope.
- **What the next lane must know:** (a) **check `ce_coverage` before believing any delta on this
  machine** — it voided arms here and one would have reversed the sign of a headline; above the 2%
  tolerance on legal a degraded arm is biased **upward**, below it the drops are sub-0.001 jitter;
  (b) **σ(R@10) is 0 on clean arms but σ(nDCG@10) is not**, so quote the right one; (c) the **CC pool
  floor is exactly 0.0**, which bounds every "aggregate the passage evidence" design, not just this
  one; (d) **scifact is `chunk-free`** and therefore a genuine inert control for any chunk-branch
  change — use it; (e) **verify that the quantity you compute is the quantity the next stage
  consumes.** Two full campaigns here measured the wrong question because the aggregate was emitted
  as a sort key while branch fusion blends scores.

## M. Post-closeout `review-changes` pass (2026-09-04)

The independent refute-first review returned **NEEDS-FIXES** on three evaluator surfaces and found
no security or privacy defect. All three findings are resolved without restarting a benchmark:

1. The default console now exposes the decision-bearing rates and paired anchor transitions; the
   evidence is no longer visible only in JSON or an output directory.
2. `--baseline-results` is parsed and structurally validated before the candidate run starts.
   Canonical CLI paired runs also require a non-empty served-model identity and preflight exact
   config/protocol/query-sequence compatibility before creating the retrieval client.
3. Paired-comparison schema v2 replaces the ambiguous `compatible` claim with
   `evaluator_compatible` and an explicit `experimental_admissibility: external-check-required`
   record. Logical index identity and equal, stable enrichment remain run-admission evidence outside
   this offline comparator.

Post-fix verification: the focused jseval suite passed **85/85**; `compileall`, `tier2-eval --help`,
`git diff --check`, and the full docs-maintenance check sequence passed. The already-fresh
`ChunkThresholdCharacterizationTest` Gradle target remained green and no Java production path changed
in this fix. A second read-only review returned **GO**, with no remaining P0/P1/P2 finding in the fix
set. No benchmark or dev stack was started. The Part 3 verdict does not change: its candidate remains
parked until a terminal-index, standard-profile rerun can meet those external admission checks.
