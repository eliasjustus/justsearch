---
title: "Content-addressed eval index cache: reuse a built index if and only if (corpus_signature × config_cohort_key) match byte-exactly — keep the fresh-build validity guarantee while amortizing the ~50-min/10k-doc rebuild that eval campaigns currently pay repeatedly for identical inputs"
type: tempdocs
status: "open — takeover (verdict GO, §F) + theorization (§G-§K) complete (2026-07-17). Design/derisk passes are next. No design or implementation yet."
created: 2026-07-17
author: agent (Fable orchestration), chartered at founder direction during the Phase-2 utility campaign ("open a new tempdoc for this. ill set a new agent on it")
category: eval-infrastructure / measurement-economics / index-lifecycle
related:
  - 704-measurement-substrate-correct-data-program   # pillar 4 (measurement economics: iteration speed bounds affordable correctness) + pillar 6 (isolated eval lane) — this lane serves both
  - 716-jseval-run-artifact-coherence                # RETIRED the old --clean protected-set index reuse — that was UNKEYED reuse; understand why before designing (this doc's central distinction)
  - 676-headless-eval-product-contract               # the eval-lane contract this cache would live inside
  - 624-agentic-retrieval-eval-rebuild               # the consumer whose campaigns motivated this (Step-2 + Phase-2, 2026-07-16/17)
  - 713-dense-authority-consolidation                # §M-3/M-5: the B-confidence incremental-reuse anomaly — the concrete cost of ambiguous index provenance
  - 717-intermittent-fresh-build-chunk-death         # fresh builds are not risk-free either; the cache must not mask this class
---

> NOTE: Noncanonical working tempdoc. STUB + charter. Verify every inherited claim against the
> cited tempdocs and current `main` before building on it.

# 751 — Content-addressed eval index cache

## The problem (measured, 2026-07-16/17)

Every eval run rebuilds its corpus index from scratch: backend `--clean` → ingest → full
enrichment (embeddings, SPLADE, chunks, NER). On a 10k-doc corpus that is **~50 minutes of GPU
wall-clock per build**. During the Step-2 + Phase-2 utility campaigns, `mixed/en-legal-clerc-10k-verbose`
was built **three times in ~12 hours** for the byte-identical corpus (`corpus_signature`
`7b108fc4…`) under the byte-identical engine config (same `config_cohort_key`). The rebuilds
bought nothing: same inputs, same config, same expected index modulo HNSW nondeterminism.
Campaign wall-clock is dominated by ingest, not by measured cells (Phase-2: ~50 min ingest vs
~40 min of cells per 10k run).

## Why fresh-build is currently correct (do not regress this)

A from-scratch ingest makes the run's identity pins **true by construction**: the index provably
derives from exactly the committed corpus bytes + the current engine config. Index reuse without
that proof has bitten repeatedly:

- **tempdoc 716** retired jseval's `--clean` protected-set reuse — *unkeyed* reuse ("hope
  nothing changed") produced runs whose artifacts were ambiguous about what they measured.
- **tempdoc 713 §M-3/M-5**: an incremental-reuse arm had to be downgraded to B-confidence and
  hand-probed (4293/4293 chunk vectors) to be usable at all.
- **F-032-class silent state**: an index can carry destroyed-but-status-COMPLETED data; reuse
  inherits unknowns invisibly.

## The design thesis (what makes this different from what 716 retired)

**Keyed reuse is the opposite of unkeyed reuse.** Cache key = `(corpus_signature ×
config_cohort_key [× index-relevant engine version])`. Cache HIT → adopt the built index
(provenance identical by construction, same guarantee as a fresh build); any mismatch or any
doubt → MISS → fresh build (fail-closed). The validity guarantee is preserved; only redundant
identical builds are eliminated. Bonus, not just economics: **within-campaign comparability
improves** — arms/runs sharing one physical index eliminate HNSW rebuild variance (the F-037
ranking-instability class) instead of resampling it per run.

## Hard requirements (inherit; the design must satisfy all)

1. **Fail-closed**: any pin mismatch, any unverifiable cache entry, any doubt → fresh build.
   Never a flag that forces adoption of a stale entry.
2. **Key completeness is the crux**: `config_cohort_key` must actually cover every index-shaping
   input (analyzer config, chunking params, embedding model identity, SPLADE model, field
   schema/`fields.v1.json`, enrichment settings, engine version where index format changes).
   Audit what the cohort key covers TODAY vs what shapes the index — any index-shaping input
   outside the key is a silent-staleness hole (the whole 716 failure class re-entering through
   the key). This audit is the first real work item.
3. **Post-build content attestation**: a cache entry should store verifiable facts from its
   build (doc count, chunk-vector count, per-field coverage — the 713/717 probe set) and
   re-verify cheap invariants at adoption time, so a 717-class silently-degenerate build cannot
   be immortalized in the cache.
4. **Run records stay honest**: a record produced against a cached index must carry the cache
   provenance (entry id, built-at, attestation) — a projection of the same pins, never a
   weakening of them.
5. **Locking/concurrency**: one Gradle/backend at a time is the repo convention; the cache must
   tolerate concurrent agents at least fail-closed (lock or copy-on-adopt, never share a live
   data dir between two backends).

## Scope / boundary

- Eval lane only (`runHeadlessEval` / jseval data dirs) — production index lifecycle is NOT this
  doc (676 owns the eval-lane contract this slots into).
- No change to what `--clean` means for a MISS: a miss is exactly today's behavior.
- The 624 campaigns are the first consumer; the powered-run economics (pillar 4) the motivation.

## First work items for the incoming agent

1. **Key-completeness audit** (requirement 2): enumerate index-shaping inputs vs
   `config_cohort_key` coverage; file the gap list. This decides feasibility — if the key can't
   be made complete cheaply, the cache is unsafe and this doc should close as won't-do with that
   finding.
2. Inventory where the eval backend's data dir lives per run (`jseval/backend.py`,
   `serve-eval-backend.py` in the step2-powered worktree's campaign tooling) and what "adopt an
   index" mechanically means (copy vs point-at; startup validation hooks available).
3. Design the entry layout + attestation record; then the standard theorize → design → derisk
   pipeline before any implementation.

## Evidence pointers

- Rebuild timings: step2-powered worktree `scripts/jseval/tmp/step2-powered/chain-step2.log` +
  `tmp/phase2/chain-phase2.log` (ingest phases, readiness progress lines).
- The founder-visible cost instance: legal-10k built 3× in 12h (2026-07-16 21:41, 2026-07-17
  03:21, 2026-07-17 08:02 chains).
- Observations inbox note (2026-07-17, session 109145ac): the keyed-vs-unkeyed distinction.

---

# Takeover investigation (2026-07-17, session 7b0aa2d9)

Three parallel audits (key-completeness, backend mechanics, inherited-claim verification) plus
first-hand spot-checks of every load-bearing claim below. **Verdict at the end: GO, with design
conditions.** Design/implementation has NOT started; this section is investigation only.

## §A. Key-completeness audit (charter work item 1 — the feasibility crux)

### A.1 `config_cohort_key` as it exists today is NOT a safe cache key

Computation site: `scripts/jseval/jseval/release.py:147` (`config_cohort_key(manifest)`), inputs
assembled live by `utility_calibrate.py:252` (`pin_config_cohort_key`). It hashes exactly:
`git_sha`, `eval_protocol_hash`, `policy_hash`, four "config-global" commit-metadata fps
(`schema_fp, similarity_fp, boosts_fp, grammar_hash` — `release.py:74-79`), and model identity
minus `*_gpu` flags (`release.py:141-144`).

Its own docstring (`release.py:148-156`) states the disqualifying design intent: it is a
**cross-corpus release-grouping key** (tempdoc 623 T-1/U1) that *deliberately excludes* the four
corpus-varying commit-metadata fps — `field_catalog_hash`, `index_schema_fp`, `analyzer_fp`,
`synonyms_hash` (`release.py:82-87`) — because including them "would refuse every multi-corpus
release." The cache needs the union of everything index-shaping; `config_cohort_key` was built to
be the intersection that survives corpus changes. **Opposite selection criteria.** The stub's
title formula "(corpus_signature × config_cohort_key)" is therefore wrong as literal code; it
survives only as shorthand for "corpus identity × a (new) complete config identity."

### A.2 Coverage matrix (index-shaping input → in `config_cohort_key`?)

| Index-shaping input | In key? | Evidence (index side / key side) |
|---|---|---|
| Committed engine code, Lucene version, YAML defaults | YES via `git_sha` | `release.py:159` |
| Embedding model **content** | YES | `EmbeddingFingerprint.java:94` (sha256 of onnx bytes) → `model_identity` |
| BM25 similarity params | YES | `SsotCommitMetadataSource.java:111` / `release.py:162` |
| `fields.v1.json` schema (`field_catalog_hash`) | **NO — excluded** | `SsotCommitMetadataSource.java:81` / `release.py:82-87` |
| Effective vector dimension (`index_schema_fp`) | **NO — excluded** | `SsotCommitMetadataSource.java:83-93` |
| Analyzer config (`analyzer_fp`) | **NO — excluded** | `SsotCommitMetadataSource.java:100,137-150` |
| Vector quantization (`vector_format` int8_sq/float32) | **NO — not in allow-list** | `SsotCommitMetadataSource.java:119-127`, toggle `EnvRegistry.java:1071` |
| SPLADE model content | **PARTIAL — path only** | `run.py:74`; content-hasher `SpladeFingerprint.java` exists but unused in `_snapshot_models` |
| NER model content | **PARTIAL — path only** | `run.py:78` |
| Embedding runtime knobs (context length, late-chunking, dim override) | **NO** | `EnvRegistry.java:295,327-340,932` — env/`-D` bypasses `git_sha` |
| Chunking params + chunk-vector/chunk-splade toggles | **NO** (runtime toggles bypass `git_sha`) | `EnvRegistry.java:1092-1094`, `ResolvedConfig.java:708-728` |
| HNSW M / efConstruction | **NO** (sysprops bypass `git_sha`) | `EnvRegistry.java:1061`, `ResolvedConfig.java:637-638` |
| Uncommitted working-tree edits (`git_dirty`) | **NO** — recorded but never hashed | `release.py:159` uses `git_sha` only |

Note `policy_hash` closes none of these: `/api/debug/session-policies` serializes
`PolicySnapshot` = `RuntimePolicy` + per-encoder `ModelSessionPolicy` — ORT session/GPU/arena/
threading policy only (`PolicySnapshot.java:28`, `RuntimePolicy.java:34`,
`ModelSessionPolicy.java:38`; verified first-hand). The 713 §M-3 confound
(`JUSTSEARCH_EMBED_LATE_CHUNKING_ENABLED`) is exactly a row of this gap table — the historical
incident and the audit agree on where the danger lives.

### A.3 Closability: bounded and cheap — feasibility crux resolved POSITIVE

The gap set is **not open-ended**: `git_sha` is a genuine catch-all for every *committed*
index-shaping input, so arbitrary engine changes need no per-file hashing. The residual set is
four concrete moves, all reusing existing plumbing:

1. **Fail closed on `git_dirty == true`** (single largest hole; a dirty tree means `git_sha` no
   longer pins the engine).
2. **Hash the resolved index-shaping runtime-config subset** (one fingerprint over
   `ResolvedConfig.index()` + embed-config: dim override, quantization, context length,
   late-chunking, chunk toggles, HNSW M/ef) to capture env/`-D` overrides that bypass `git_sha`.
3. **Content-fingerprint SPLADE + NER** (mirror `EmbeddingFingerprint.java:94`; a
   `SpladeFingerprint.java` already exists).
4. **Include the four excluded commit-metadata fps + `vector_format`** — they already exist in
   `/api/debug/commit-metadata`; they were only excluded for release-grouping reasons that don't
   apply here. (Nuance, resolved first-hand: `field_catalog_hash` is a *static* hash of
   `fields.v1.json` — `SsotCommitMetadataSource.java:81` — and `index_schema_fp` varies only via
   the runtime dim override; release.py's "corpus-dependent" label is empirical from runs that
   used per-corpus overrides, not intrinsic. For the cache they are config axis, include them.)

⇒ The design deliverable is a **purpose-built index-identity key**, NOT a reuse of
`config_cohort_key`. Roughly: `git_sha + (git_dirty==false gate) + resolved-index-config hash +
per-model content fingerprints (embed/splade/ner) + {field_catalog_hash, index_schema_fp,
analyzer_fp, vector_format}`.

### A.4 `corpus_signature` axis is already canonical — conform, don't fork

`scripts/jseval/jseval/corpus_identity.py:20`: `sha256(corpus.jsonl + qrels/test.tsv)`; single
definition used by run manifest, summary, and release pin. The cache calls this directly.

## §B. Mechanics (charter work item 2)

- **Data dir**: `backend.py:83-98` resolves `JUSTSEARCH_DATA_DIR` (default
  `tmp/headless-eval-data`); `--clean` is now an unconditional fail-closed whole-dir wipe with
  orphan-Worker sweep (`backend.py:105-113,183-254`) — the 716 carve-out is gone in code.
- **Index layout is a stable contract**: `<base>/state.json` + `indices/<genId>/` with sentinel +
  generation manifest (`IndexGenerationManager.java:28-42`, marked `PERMANENT COMPAT`).
- **Adoption precedents already exist in the Worker**: normal non-clean startup opens an existing
  generation without rebuilding (`KnowledgeServer.java:456-460` → `resolveFromState`,
  `IndexGenerationManager.java:393-405`); `tryAdoptSingleExistingGeneration`
  (`IndexGenerationManager.java:440-476`) adopts a lone generation dir with no `state.json`,
  refusing to guess when 0 or >1 candidates. "Adopt on hit" rides existing code paths.
- **The Worker independently validates on open** — belt-and-suspenders under the cache: embedding
  fingerprint stored in the Lucene commit vs current model (`KnowledgeServer.java:539-586`),
  schema mismatch (`:587-619`), recovery marker (`:513-537`); mismatch auto-triggers Blue/Green
  rebuild. Consequence: a wrong cache hit degrades to a *wasted copy + rebuild*, not a silently
  wrong measurement — for the inputs the Worker checks (embedding model, schema). The cache key
  must still cover the rest (analyzer, chunking, HNSW…), which the Worker does NOT re-check.
- **Concurrency**: `IndexRootLock` (sibling lock file *outside* the index dir — deliberately
  move/rename-friendly, `IndexRootLock.java:41-46`) + `AppInstanceLock` per data dir
  (`AppInstanceLock.java:27-206`). Copy-on-adopt into a fresh data dir composes cleanly with
  both; the cache store itself needs only atomic publish + a read lock convention.
- **Lucene portability** (web-verified): index directories are copyable/portable; replication is
  first-class. One hazard: never let an IndexWriter open the copy target during copy.

## §C. Evidence verification (motivation)

Confirmed from surviving marker mtimes + eval-results manifests + `chain-phase2.log` in the
step2-powered worktree: **3 distinct from-scratch builds of `mixed/en-legal-clerc-10k-verbose`**
(02:27→03:21 ≈54 min; 03:21→04:04 ≈43 min; 08:02→truncation ≥54 min, embedding restarted from
0% each time). The ~50 min/build claim holds. The "~12h window" is not directly verifiable from
surviving logs (observed span ≈6.5 h; `chain-step2.log` was overwritten by a later invocation) —
immaterial to the case. Consumer cadence is real: Phase-2 continues, 657 install-tier economics
and further size-trend cells are pre-registered follow-ons (624 §Phase-2), so identical
(corpus × config) rebuilds keep recurring.

## §D. Corrections to this charter's inherited claims (apply before design)

1. **The 716 framing above is wrong.** 716 never retired index reuse — the Lucene `index/` dir
   was never in the protected set and was always wiped by `--clean`; 716 retired a
   *calibration-metadata* carve-out (`cohort_baselines/`, `non_determinism_envelopes/`) by moving
   those files out of the data dir (716 §352-428). "Unkeyed reuse produced ambiguous artifacts"
   appears nowhere in 716. The **real** cautionary precedent for unkeyed index reuse is
   **713 §M-3/M-5**: pre-716 `--clean` accidentally reused the CONTROL's index generation for an
   ARM run whose `JUSTSEARCH_EMBED_LATE_CHUNKING_ENABLED` differed — B-confidence downgrade, a
   hand VecProbe (4293/4293), and a full extra GPU session (M-5 fresh-build probe) to resolve.
   Cite 713, not 716, as the incident; cite 716 only for the "one declared shape, no
   two-independent-lists drift" design principle.
2. **The F-037 cite is imprecise.** F-037's headline is the MCP evidence-pack document-universe
   fork (tempdoc 731). The HNSW rebuild-variance / rank-instability observation is a separate,
   still-open finding from 731 (register lines ~781-788). The comparability bonus is real
   (HNSW build randomness is inherent — layer assignment is stochastic) but should cite "731's
   HNSW rebuild-variance finding."
3. **Requirement 3's probe set is necessary but proven insufficient.** 717 (now RESOLVED on
   `main`) was a *query-time* degradation — index reported 100% chunk-vector coverage throughout;
   `parent_token_count` unpopulated → `SKIPPED_SHORT_CORPUS` → `chunk_merge` leg dropped at
   search time (717 §651-721). Count-based attestation (doc count, chunk-vector count, per-field
   coverage) would NOT have caught it. Attestation should add a cheap **behavioral canary**
   (e.g. a known query must return `chunk_merge` among observed legs) on build AND on adoption.
   Also: no reusable VecProbe module exists — the 713/717 probes were ad-hoc; the status surface
   (`chunkDocCount`, `chunkVectorCoveragePercent`, `chunkVectorsReady`) is real and readable via
   `/api/status` / `/api/debug/state`.

## §E. Displacement / duplication check

- **Nothing existing implements a built-index cache** — no tempdoc or code (grepped).
- **704 Pillar 3 (fail-closed validity envelopes)** already plans a preflight VALIDITY
  CERTIFICATE checking "corpus signature match … config cohort match" (704 §117-126, vehicle:
  675 executor v2) — the same two-axis identity, used as a run-provenance assertion rather than
  a build-skip decision. **Design condition: one identity primitive, two consumers** — the cache
  hit/miss decision and the validity certificate must project from the same key computation, not
  fork a second authority (execution-surfaces projection-vs-fork discipline).
- **`manifest.py:212` `manifest_hash`** already *claims* exactly this identity ("same git, same
  policy, same index identity, same models, same corpus" — `manifest.py:216-218`) but is
  computed **post-hoc from live backend snapshots** (`/api/status`, commit-metadata,
  session-policies) — unusable as-is for a pre-build cache decision. The design must reconcile
  three identity surfaces (`config_cohort_key`, `manifest_hash`, the new cache key) — ideally
  the cache key becomes the pre-computable core that the other two embed — or we ship a fourth
  drifting identity authority. **A pre-hoc-computable key whose inputs are currently emitted
  only by a running backend is the main unsolved design problem** (options: compute
  fingerprints statically in jseval; or a cheap backend "identity-only" boot mode; or
  build-once-then-key adoption for subsequent runs only).
- **`dataset_cache.py` (tempdoc 709)** is the in-repo structural template (content-verified hit,
  atomic publish) — but it is deliberately fail-**open** (raw downloaded bytes, cheap to
  re-fetch); 751 is deliberately fail-**closed**. Divergence is intentional; note it, don't
  copy the disposition.
- **Naming**: `cohort_hash`/`cohort_baselines/` already mean *statistical calibration cohort*
  (non-determinism envelopes). Pick a visibly distinct name for the cache key (e.g.
  `index_identity_key`), not another "cohort."

## §F. Verdict — GO, now, with design conditions

**Should this be done at all?** Yes. The motivation is measured and recurring (3× ~50-min
rebuilds of one identical corpus×config confirmed; more campaigns pre-registered), the benefit
is dual (economics + within-campaign comparability via shared physical index), and the
feasibility crux the charter itself named — key completeness — resolves **positive**: the gap
set is bounded, enumerated (§A.2), and closable with existing fingerprint plumbing (§A.3).
Adoption mechanics are favorable: the Worker already has adoption + validation paths, and its
own fingerprint checks mean a subset of wrong-hit classes degrade to a wasted rebuild rather
than a wrong measurement (§B).

**Why now?** Every campaign chain burns ~1-2 redundant GPU-hours per repeated corpus, and the
next campaigns (Phase-2 continuation, 657 economics, size-trend cells) are already planned.
Pillar 4's premise applies: iteration speed bounds affordable correctness.

**Cheapest validating/invalidating evidence?** Already exists — this investigation was it. The
three plausible kill-conditions were checked and all resolve in favor: (1) key can't be made
complete → refuted, §A.3; (2) no recurring consumer → refuted, §C; (3) adoption mechanically
infeasible / Lucene indexes not safely reusable → refuted, §B. No further evidence gathering is
needed before design.

**What it displaces/duplicates**: nothing shipped; but two planned/existing identity surfaces
(Pillar-3 validity certificate, `manifest_hash`) overlap — the design must unify, not fork (§E).

**Conditions the design pass must satisfy** (beyond the charter's five hard requirements):
1. Purpose-built index-identity key per §A.3 — do **not** reuse `config_cohort_key`; fail closed
   on `git_dirty`.
2. Solve pre-hoc computability (§E `manifest_hash` paragraph) — this is the hardest open design
   question, not the key contents.
3. One identity primitive shared with the Pillar-3 validity certificate (§E).
4. Attestation = counts + behavioral canary (§D.3).
5. Key computation and index-shaping config must derive from one declared source (716's actual
   lesson), so the key can't drift from the engine.
6. Distinct naming vs the calibration-cohort machinery (§E).

---

# Theorization (2026-07-17, session 7b0aa2d9 — pre-design)

Broadening pass before design settles. Nothing here is a decision; it maps the solution space,
surfaces assumptions the charter left implicit, and names the recurring shape this belongs to.

## §G. Four framings of the same problem

1. **Memoization**: index build as a function `build(corpus, config, engine) → index`, cache =
   memo table. Exposes the purity assumption: build is pure only *up to HNSW sampling* — see
   §I.3 for what reuse does to that distribution.
2. **Provenance**: the problem isn't rebuild cost per se, it's that we currently have only one
   proof that an index equals its declared inputs — *proof by construction* (fresh build). The
   cache adds a second proof form — *proof by input identity*. Attestation (§D.3) is a third,
   weaker form — *proof by observed behavior* — useful as a cross-check, insufficient alone
   (717: behavior probes miss what they don't probe).
3. **Lifecycle**: today's cost exists because `--clean` destroys and identity is unprovable
   afterwards. An alternative family keeps the artifact alive instead of caching it: keyed
   persistent data dirs (§H.2-b) or a backend kept running across same-key cells (§H.1). "Cache"
   is one point in a larger reuse-lifecycle space.
4. **Economics**: the cache competes with two other cost levers — making ingest faster
   (enrichment throughput, 691) and building less often via orchestration (§H.1). It composes
   with both; but if §H.1 alone captures most of the waste, the persistent cache's marginal
   value shrinks. The design should check the observed waste's split: builds #1→#2 were
   *within* one chain (same invocation, ~43 min saved by mere in-chain reuse); build #3 was a
   *new* chain (needs persistence to save).

## §H. Design-space axes (each independent; name them, don't decide yet)

### H.1 Tier 0 — orchestration reuse, no persistence
Keep the backend (or at least its data dir) alive across consecutive same-key cells within one
chain invocation. Identity is maintained by process continuity, not by a key; no store, no
adoption, no attestation machinery. Captures the #1→#2 class of waste (~half the observed
instance). Cheapest thing that could possibly work; also the natural v0 to gather hit-rate data
for the real cache. Its identity question doesn't vanish (per-arm config differences must still
compare equal) — it's the same key, minus persistence.

### H.2 What is the cached unit?
- (a) **The Lucene generation dir only** (`indices/<gen>/`): smallest unit, but adoption must
  reconstruct everything else the Worker derives during ingest (job-queue state, watched-roots
  registration, `state.json`) or the backend re-ingests anyway — the adopt step becomes a mini
  state-fabrication protocol that can drift from the Worker's real ingest.
- (b) **The whole data dir**: coarser but honest — the cache entry is exactly the post-build,
  post-enrichment, quiesced state a fresh build leaves behind. Adoption = point the backend at
  it (or a copy of it). No fabrication. Cost: entries are bigger; entry hygiene (logs, locks,
  PIDs inside) needs a quiesce/scrub step at publish time.
- (c) **Keyed persistent data dirs** (data-dir-as-entry, no copy): `eval-data/<key>/` IS the
  entry; hit = start backend against it directly. Zero copy cost, but the run *mutates* its own
  cache entry (see §I.4) — needs either post-run divergence re-attestation or acceptance that
  same-inputs mutation is benign.

### H.3 Adopt mechanics: copy vs point-at vs link
Copy-on-adopt isolates the store from mutation and composes trivially with `IndexRootLock` (the
sibling-lock placement was *deliberately* chosen to keep index dirs movable —
`IndexRootLock.java:41-46`). Point-at is free but mutable. Hardlink-farm (NTFS hardlinks per
segment file) exploits Lucene's write-once segment files for near-zero-cost "copies" — but
segment *deletion* (merges) and any writer opening would mutate shared inodes; only safe under a
proven no-writer adoption mode. Copy is the conservative default; a 10k index copy is minutes
against a 50-minute build.

### H.4 Engine-pin scope: full `git_sha` vs scoped tree-hash — the hit-rate/completeness dial
Full `git_sha` is maximally safe and maximally over-strict: **any** commit invalidates, including
docs-only and eval-protocol-only changes. This is not hypothetical — five commits landed on
`main` during the motivating 12h window, and the delta before rebuild #3 (#232, per-arm timeout
fix) was eval-protocol-only: a full-sha key would have missed on **the very instance that
chartered this doc**. The alternative is a **declared index-shaping path set** pinned by git
*tree hashes* (`git rev-parse HEAD:modules/adapters-lucene HEAD:modules/indexer-worker
HEAD:SSOT/catalogs …`) — invalidation only when index-shaping code changes. That converts the
completeness risk into a register-completeness problem, which is this repo's home turf (an
`index-shaping-paths` register + a gate that fails when a new index-relevant module/file class
appears outside it — same shape as `execution-surfaces`). Honest statement of the trade: the
scoped pin re-admits exactly one hole (an index-shaping edit landing outside the declared set),
and pays for it with most of the cache's real-world hit rate. Likely resolution: v1 ships
full-sha (safe, still catches within-chain and fixed-sha campaign reuse), v2 upgrades to the
scoped register **only if** measured hit-rate data shows full-sha starves the cache — a
falsifiable trigger, recorded per run (key computed both ways, "would-have-hit-under-scoped-pin"
logged before any scoped adoption is allowed).

### H.5 Trust tiers and a parity-sampling audit
Adoption need not be all-or-nothing across run classes. 704's two-tier measurement split
(standing smoke vs certified) offers a natural rollout: allow adoption for iteration/smoke tiers
first; certified/release runs keep fresh-build until the cache has evidence. The evidence
mechanism can be built in: **parity sampling** — on a configurable fraction of cache hits, ALSO
fresh-build and compare (metric deltas within the non-determinism envelope; attestation counts
equal). This converts "keyed adoption is as good as fresh" from a design argument into a
continuously collected measurement, and its failures are exactly the key-incompleteness alarms
requirement 2 needs. (It also naturally re-samples HNSW variance now and then — see §I.3.)

### H.6 Multi-sample entries
If a study *wants* build-distribution variance (non-determinism envelope calibration does), the
cache can hold N entries per key (`<key>/sample-<n>/`) instead of forcing sample-1 reuse.
Probably YAGNI for v1 — but the entry layout should not preclude it (a sample ordinal in the
path costs nothing now, redesign later costs a migration).

## §I. Hidden assumptions surfaced

1. **"Same machine" is silently assumed.** Embedding vectors baked into the index carry
   GPU/driver/ORT numerical identity; `config_cohort_key` *deliberately* excludes `*_gpu` flags
   as execution context, but for a cache, vector values ARE index content. Fine while the eval
   lane is one dev machine; the entry's attestation should still record hardware/ORT identity so
   a future cross-machine share fails closed instead of silently mixing float regimes.
2. **"Adopt" must be verified post-startup, or hits silently degrade to builds.** The Worker's
   own mismatch handling (§B) auto-starts a Blue/Green rebuild when an opened index disagrees
   with current config (`KnowledgeServer.java:539-619`). An adoption that trips this serves a
   *fresh* index while the harness records "cache hit" — wrong provenance in the run record and
   50 unexplained minutes. Adoption must end with an assertion: active generation id == adopted
   generation id, no migration started. (This is the `wrong-gate` class applied to caching.)
3. **Reuse changes what the measurement samples.** A fresh build per run samples the HNSW build
   distribution; shared-index runs condition on one draw. Within-campaign A/B: a feature
   (variance eliminated exactly where it confounds). Cross-campaign or absolute-level claims: a
   frozen-sample bias the run record must disclose (entry id makes runs conditional-on-entry
   distinguishable). The non-determinism-envelope machinery calibrates *rebuild* variance —
   envelopes calibrated fresh may understate agreement between two same-entry runs and vice
   versa; the design should state which comparisons remain envelope-covered.
4. **A run may mutate what it adopted.** Enrichment retries, job-queue writes, or a stray
   watched-roots event can write into an adopted index/data dir. Copy-on-adopt contains this;
   point-at (§H.2-c) must either re-attest after the run or prove read-only-ness. Related trap:
   publishing an entry from a data dir that hasn't fully quiesced (enrichment "complete" but
   final commits pending) immortalizes a moving target.
5. **`corpus_signature` covers `corpus.jsonl` + qrels, not the exploded corpus-dir** the ingest
   actually watches (10k files). The derivation corpus.jsonl → corpus-dir is committed code
   (covered by the engine pin) — but the design should assert corpus-dir is *regenerated or
   verified* on adoption, not trusted as a third unpinned artifact.
6. **Hit-rate is an empirical unknown.** The whole economic case silently assumes campaigns
   repeat keys. Observed: true within 12h at fixed config; unknown across weeks of active
   development (§H.4). v1 should instrument misses with a *miss-reason diff* (which key
   component differed) — that data decides v2 (scoped pin), eviction policy, and whether the
   cache earns its complexity.

## §J. Risks not in the charter's list

- **Cache poisoning** (717-class degenerate build published): attestation-at-publish + canary
  at adopt (§D.3); parity sampling (§H.5) as the backstop.
- **Silent adopt-then-migrate** (§I.2): loud assertion, never a log line.
- **Store growth**: multi-GB entries × corpora × configs; needs an eviction policy (LRU-by-key
  + pin-while-campaign-active) and a disk floor check before publish — mundane but the eval
  drive is shared with models and corpora.
- **Concurrent publish/adopt races**: two chains building the same key, or adopting during
  publish — atomic publish (build in `.tmp-<nonce>/`, rename into place; the `dataset_cache.py`
  pattern) + copy-on-adopt makes readers race-free without a lock service.
- **Complexity ratchet**: the cache adds a store, a key, an attestation record, and provenance
  plumbing to run records — all of which must be maintained. If §H.1 (in-chain reuse) plus
  fixed-sha campaign discipline captures most waste, stop there. The falsifier is cheap and
  should be pre-registered: instrument would-have-hit rates before building v2 scope.

## §K. The broader shape: derived-artifact derivation pins

This is the third rung of a ladder the repo is already climbing:

| Rung | Artifact | Pin | Mechanism |
|---|---|---|---|
| 709 `dataset_cache.py` | raw downloaded dataset bytes | content signature | fail-open cache |
| 741 | eval corpora | recipe + `corpus_signature` | derived-artifact recipes |
| **751** | **built indexes** | **corpus × config × engine identity** | **fail-closed keyed cache** |

The emerging invariant, stated once: **a derived artifact may be reused iff it carries a
complete, machine-checkable derivation pin, and the reuse decision is pin equality — anything
less is a fresh derivation.** Each rung differs only in what "complete pin" means and in failure
disposition (cheap-to-refetch bytes fail open; measurement-validity artifacts fail closed). If
751 ships, this invariant is worth a line in the eval-lane contract (676) rather than staying
folklore — and the identity algebra underneath it (§E: one primitive, projections for cache key
/ validity certificate / manifest) is the same projection-vs-fork discipline the
execution-surfaces register enforces for `SearchTrace`.

Not proposed here: generalizing the three mechanisms into one framework. They share a principle,
not a reason to change (AHA); 751 should *cite* the pattern, copy the atomic-publish idiom, and
stop there.
