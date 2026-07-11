# 711 — RMW field-preservation + 691/710 follow-up designs

- **status:** open — DESIGN SETTLED + DERISKED 2026-07-11, ready for implementation (§Derisk results)
- **created:** 2026-07-11
- **provenance:** the four undesigned follow-ups from the 691/710 close-out (founder takeover
  directive 2026-07-11). Items: (1) RMW structural fix, (2) battlefield difficulty re-baseline,
  (3) ONNX `metadata_props` capability stamping, (4) jseval `--clean` defect.
- **relation:** 710 documents the RMW invariant + live evidence (§RMW live evidence; Wave-1 log
  line 578-581 records the battlefield saturation); 691 N-4 records the `--clean` incident;
  710 S-C.R/S-C.D name the `metadata_props` hardening rung; 704 Pillar 1 is the successor
  corpus program battlefield questions route into.
- **evidence basis:** four independent read-only file:line audits (2026-07-11, this session):
  RMW write-path sweep, battlefield/704 exposure sweep, capability-resolver/build-script sweep,
  jseval clean-path sweep. Load-bearing citations inline below.

---

## Item 1 — RMW field preservation (the structural fix for the vector-destruction bug-class)

### Problem (verified state, not hypothesis)

`WritePathOps.readModifyWrite` (`modules/adapters-lucene/.../WritePathOps.java:266-371`)
reconstructs the document from **stored fields only** (`:276-298`) and replaces it wholesale via
`updateDocument` (`:368`). Every non-stored field absent from the caller's update map is silently
destroyed. Three field classes are affected (`SSOT/catalogs/fields.v1.json`): `vector` and
`chunk_vector` (`stored:false, docValues:false` — no recovery lane at all) and `splade`
(same, but its *status* fields are docValues-recoverable, which is what the existing bespoke
mitigation exploits).

The protections that exist today are **per-call-site discipline, not structure**:

- SPLADE: a threaded `preserveSplade` boolean + doc-values status restore + reset-to-PENDING
  guard (`WritePathOps.java:306-351`) — built for one field after tempdoc-312 BUG-1.
- VECTOR: the combined-pass bundling (`CombinedEnrichmentBackfillOps` — one RMW carrying
  VECTOR+SPLADE+NER together, 691's F-030 fix) protects **only that path**.
- `updateDocumentPaths` (`:431-493`) self-compensates by resetting `EMBEDDING_STATUS=PENDING`.

The audit found the bug-class **still live** outside the protected path:

- `NerBackfillOps` (individual backfill mode, which is still a real runtime mode — observed
  `backfillMode:"individual"` live in the Wave-1 acceptance) RMWs without VECTOR **after**
  embeddings are ready (`BackfillScheduler.java:208-229` gates it on `embeddingsReady`) — the
  worst-case ordering: vector exists, then is destroyed, doc stays COMPLETED-but-vectorless.
- `SpladeBackfillOps` interleaved runs unconditionally during primary indexing
  (`IndexingLoop.java:609`) and never carries VECTOR.
- `GrpcIngestService.updateVduResult` `SUCCESS_EMPTY`/`FAILED` branches (`:692-705`) RMW with
  only `VDU_*` fields and do **not** reset `EMBEDDING_STATUS` — silent *permanent* vector loss
  (not even re-queued). Same shape in `KnowledgeServerMigrationOps` (`:427,451,600`).
- No test anywhere asserts VECTOR survives an RMW from these sites; no code path in the repo
  reads Lucene vectors back (zero `KnnVectorsReader`/`FloatVectorValues` usage).

### Design: declared RMW disposition, enforced at the choke point

One preservation step inside `readModifyWrite` itself — the single funnel every RMW already
flows through (`IndexingCoordinator` is the declared sole Lucene-mutation entry point,
`IndexingCoordinator.java:24-28`) — driven by a **per-field declared disposition** in the field
catalog rather than by caller memory:

1. **Catalog extension** (`fields.v1.json`): every field that is neither stored nor
   docValues-backed must declare an `rmwPolicy`:
   - `preserve-reread` — the write path re-reads the field's current value from the index at
     the same searcher snapshot and carries it into the new document when the caller's update
     map doesn't supply it. Applies to `vector` / `chunk_vector`: Lucene exposes per-doc
     float-vector read-back (`LeafReader.getFloatVectorValues` + advance), the searcher lease
     and Lucene docid are already in hand at `:269-276`, and the cost is one ~768-float copy
     per RMW — this makes RMW **lossless** for vectors, structurally.
   - `reset-status:<statusField>` — the field's data cannot be cheaply re-read (SPLADE's
     `FeatureField` weights live in postings); on drop, the declared status field is reset to
     PENDING so backfill re-derives it. This is the *existing* SPLADE lane, generalized and
     declared instead of bespoke.
2. **Startup fail-fast**: catalog validation (precedent: `FieldMapper.validatePrimaryKeySupport`,
   `FieldMapper.java:169-184`) rejects any non-stored, non-docValues field **without** a declared
   `rmwPolicy`. A new fragile field cannot ship without declaring its RMW disposition — the
   failure mode becomes impossible to reintroduce silently, not merely discouraged.
3. **Regression tests per destroyer site**: NER-after-embed in individual mode, interleaved
   SPLADE over a vector-bearing doc, VDU `SUCCESS_EMPTY` over a vector-bearing doc — each must
   end with the vector intact (or, for reset-lane fields, status=PENDING). These are the tests
   the audit found missing.

**Considered and rejected:**
- *Reject-on-drop* (fail the RMW instead of preserving): moves the burden back to callers and
  breaks legitimate vector-ignorant writers (VDU, migration). Preservation fixes the class.
- *Store vectors twice* (BinaryDocValues shadow copy): doubles vector write/storage cost to
  recover data Lucene can already return; `KnnFloatVectorField` has no store option by design.
- *"Bundle everywhere" discipline*: that is the current state minus the unprotected sites;
  discipline is exactly what failed (F-030).

**Orphans (deleted/subsumed by this design, same PR):**
- The `preserveSplade` boolean parameter threading through `IndexingCoordinator`/`WritePathOps`
  and every call site — subsumed by the declared `reset-status` policy.
- The bespoke SPLADE restore/reset block (`WritePathOps.java:306-351`) — becomes the generic
  policy engine's `reset-status` lane.
- `updateDocumentPaths`'s inline `EMBEDDING_STATUS=PENDING` compensations (`:449-452,481-482`)
  — obsolete once vectors are preserved (content-change paths that *should* re-embed set
  PENDING deliberately, which remains caller semantics, not loss compensation).
- The combined-pass bundling **stays** — it remains the right *performance* shape (one RMW per
  doc); it just stops being load-bearing for correctness.

**Verification shape:** unit tests above + one live individual-mode pipeline run on a chunked
corpus asserting non-zero vector-mode retrieval afterward (the F-030 symptom as the oracle),
plus the standard relevance/union-recall/leak gates. Perf check: enrichment docs/s unchanged
within noise (re-read cost is per-RMW-lacking-vector, bounded and small).

## Item 2 — battlefield-en-v1: scope-and-record, not regenerate

### Verified state

Battlefield pins **nothing**: it appears in no gate/ratchet/baseline file
(`relevance-ratchet-baselines.v1.json`, `union-recall-gate-baselines.v1.json`,
`leak-gate-baselines.v1.json`, `release.v1.json` — all clean) and has **no Dataset Catalog row**
in the search-quality register. Its difficulty certification only ever targeted **hybrid-mode**
nDCG@10 (fidelity band `[0.30,0.85]`, measured 0.4143 "hard" — `corpus_fidelity.py:29-37`,
`metadata.json:26-44`); vector mode was never calibrated. The post-691 saturation
(vector nDCG@10 = 1.0000, 26/26 golds at rank 1) is recorded in 710's Wave-1 log (main,
`710:578-581`). Current uses: throughput profiling (691) and the arm-invalidated 624
agent-utility campaign records.

### Design

The saturation breaks nothing that gates, so the scope-matched move is **record-and-scope**:

1. **Register**: add `golden/battlefield-en-v1` (and `-de-v1`) Dataset Catalog rows with their
   certified hybrid fidelity numbers and an explicit **mode-scope note**: certified for
   hybrid-band difficulty only; vector mode saturated at HEAD defaults post-F-031 — unusable as
   a dense-retrieval discriminator; valid for throughput profiling and hybrid-band work.
2. **One certification re-measurement** at HEAD defaults capturing *per-mode* nDCG into the
   corpus `metadata.json` fidelity block (the existing `retrieval_ndcg_by_mode` map already has
   the shape — it just only contains `hybrid` today), so the saturation is a recorded corpus
   property, not a side observation in a tempdoc log.
3. **No regeneration now.** Making fabricated-prose battlefield "harder for dense" invests in
   the corpus class 704 Pillar 1 is chartered to supersede (real-text distractor mass +
   fabricated fact injection). Difficulty work routes there. **Trigger to revisit:** a concrete
   need for battlefield as a dense discriminator *before* Pillar 1 ships — then retune via the
   existing deterministic generator (`corpus_generate.generate()`, seed/doc_words/n_chains, the
   624 calibration mechanism) + re-certify, following the 664 procedural-provenance pattern.

**Orphans:** none (additive). The 624-era implicit assumption "in-band = valid for all modes"
is retired by the mode-scope note.

## Item 3 — ONNX `metadata_props` capability stamping (deferred 710 rung)

### Verified state

`ModelCapabilityResolver` (main, via 710 Wave 2) resolves per-fact with order: manifest
`capabilities` → ecosystem files → (dimension-only graph probe) → legacy sidecar → WARN. It
already opens a short-lived probe session (`OrtSessionAssembler.probeModelNames` /
`probeOutputTensorInfo`) for the dimension fact. No code reads ONNX `metadata_props` today
(zero `getMetadata` usage; ORT 1.24.3 exposes `OrtSession.getMetadata().getCustomMetadataMap()`).
Build scripts: `build-embedding.py`/`build-splade.py` already hold an in-memory `onnx.ModelProto`
at save time; `build-ner.py`/`build-crossencoder.py` download pre-built files and never open
them as model objects. No script writes `model_manifest.json` (hand-authored).

### Design

1. **Stamping step, build-time, forward-only.** A shared helper in `scripts/models/_common.py`
   stamps reverse-DNS keys (`io.justsearch.pooling_mode`, `.context_length`,
   `.embedding_dimension`, `.cpu_precision`/`.gpu_precision`, `.document_prefix`/`.query_prefix`)
   into `metadata_props`, **sourced from the model dir's `model_manifest.json` capabilities
   section** — one authoring surface, no duplicate truth; the stamp is a projection of the
   manifest at build time. Wired into all four build scripts (the two download-only scripts gain
   a load/stamp/save step). `build.json` records the stamping.
2. **Resolver rung**: read `metadata_props` **between** manifest and ecosystem files, piggybacked
   on the existing probe session (one `getMetadata()` call inside the already-open `try` block —
   no extra session). Manifest stays first (it is the human-editable override); a
   manifest-vs-embedded disagreement WARNs (cheap staleness cross-check, consistent with the
   resolver's existing disagreement-WARN posture).
3. **No retroactive restamp of committed models.** Restamping all four model dirs now would churn
   multi-hundred-MB LFS blobs to embed facts the manifests already deliver. Embedded metadata
   earns its keep on the *next natural rebuild* (708's encoder outcome, 657 pack authoring) and
   for third-party models arriving without sidecars. This is the honest scope match for an
   "optional hardening" rung (710 S-C.D's own framing).

**Orphans:** none deleted now (the legacy sidecar rungs already carry tombstone status from
Wave 2). The rung ordering decision ("embedded outranks *sidecar*, not manifest") supersedes the
looser wording in 710 S-C.R.

## Item 4 — jseval `--clean`: fail-closed wipe + orphan-worker kill

### Verified state (three cooperating defects, all file:line-confirmed)

1. `stop_backend()` (`scripts/jseval/jseval/backend.py:159-184`) taskkills only the head process
   tree; the Worker JVM survives (observed twice, `docs/observations.md:546-548`), holding the
   Lucene index open and able to rewrite `watched_roots.json`.
2. `--clean`'s wipe (`backend.py:71-89`) is best-effort with **silently swallowed failures**
   (`shutil.rmtree(..., ignore_errors=True)`, bare `except OSError: pass`) — it logs "Cleaning…"
   and proceeds even when `index/` and `watched_roots.json` survive (691 N-4 incident: two A/B
   arms confounded).
3. A surviving `watched_roots.json` then hits the idempotent re-add no-op
   (`RootLifecycleOps.java:262-271`) so the next arm's corpus is never re-ingested while stale
   docs are still served (`BLOCKED_LEGACY` churn, `IndexStatusOps.java:959`).

Docs over-promise: `jseval-pipeline-reference.md:215` says "wipes data dir" unconditionally;
neither the protected subdirs nor the silent-failure mode is documented.

### Design

Make the wipe **fail-closed and self-clearing**, in `start_backend`'s clean path:

1. **Orphan detection + kill before wipe**: identify surviving backend JVMs bound to the target
   data dir (worker + head remnants) and kill them — discovery keyed on the data dir path, not
   on the remembered Popen handle (which is exactly what misses the worker child today). The
   `stop_backend` kill should likewise become tree+worker-complete so orphans stop being created.
2. **Verify-after-wipe**: after deletion, re-scan the data dir; any surviving non-protected entry
   is a **hard error** naming the survivors (and the holder process where determinable) — the run
   aborts instead of silently proceeding into a confounded measurement. One kill-and-retry cycle
   before the hard failure.
3. **Docs alignment**: `jseval-pipeline-reference.md` documents the protected subdirs
   (`cohort_baselines/`, `non_determinism_envelopes/`) and the fail-closed contract;
   `common-workflows.md`'s stale-index recipe inherits the corrected semantics.
4. **Tests**: unit coverage for the failure path (survivor injected → hard error), alongside the
   existing happy-path test (`test_backend.py:263-292`).

`--reset` (in-process index reset via API, mutually exclusive with `--start-backend`) is a
different, correctly-documented lane — untouched.

**Named alternative (not built now):** per-run ephemeral data dirs with calibration state
(`cohort_baselines/` etc.) relocated outside them would make wiping obsolete entirely; that is a
larger jseval restructure and the retirement path for this design, not its first step.

**Orphans:** the docs' unconditional-wipe claim (corrected in place). No code orphan.

---

## Reach judgment — principles, where else they apply, earning/retirement conditions

1. **Declared RMW disposition** (Item 1) — *"a field that cannot survive the write path's
   read-modify-write must declare, in the field catalog, what happens when a write omits it —
   enforced at the single write choke point, not by caller discipline."* This is the
   register/declared-concept idiom (execution-surfaces gate, tempdoc 553's projection-vs-fork
   discipline) applied to the write path. Existing violations: every site in the Item-1 table.
   **Earning its keep:** zero new vectorless-COMPLETED incidents; the `preserveSplade` threading
   and per-site compensations actually deleted; new fragile fields fail fast at startup until
   they declare. **Retire when:** the write path becomes append-only/single-writer per doc, or
   an index engine change makes non-stored-field RMW lossless natively.
2. **Fail-closed destructive cleanup** (Item 4) — *"a destructive prerequisite must verify its
   postcondition and abort on failure; a cleanup that cannot confirm it cleaned is a
   measurement-validity bug, not a convenience bug."* Instance of the `green-masked-destructive`
   lesson. Candidate scope beyond jseval: any script that wipes state then measures (corpus
   cache `prepare_corpus` skip, `obs:ingest-drift`, is an adjacent instance). **Earning:** the
   next locked-file incident surfaces as an immediate named error, zero confounded arms.
   **Retire when:** per-run ephemeral data dirs land.
3. **Mode-scoped corpus certification** (Item 2) — *"a difficulty certification is only valid
   for the mode it measured; using a corpus as a discriminator in an uncertified mode is an
   uncontrolled variable."* All golden corpora certify hybrid-only today, so this applies to
   `needle-burial-v1` and the battlefield siblings alike. **Earning:** prevents a repeat of
   dense-leg conclusions drawn on a saturated corpus. **Retire when:** 704 Pillar 1 ships
   per-mode (or mode-aware) certification as part of corpus fidelity.
4. **Self-describing artifacts** (Item 3) — *"capability facts should travel inside the
   artifact they describe; sidecars drift, embedded metadata cannot be separated from the
   weights."* GGUF precedent; forward-only application is deliberate (LFS economics). **Earning:**
   a future model swap or 657 pack resolves capabilities with zero WARNs and zero hand-authored
   sidecars. **Retire when:** manifests become mandatory and gate-enforced for every model dir —
   embedded metadata is then redundant belt-and-braces.

## Derisk results (2026-07-11 — five probes: one live experiment + four evidence sweeps)

### E1 — vector re-read: PROVEN (the Item-1 load-bearing assumption holds)

Throwaway JUnit experiment in `modules/adapters-lucene` (isolated worktree, run via Gradle,
deleted after capture; 5 sub-cases, 5 passed):

- Lucene **10.4.0**; API is ordinal-based: `LeafReader.getFloatVectorValues(field)` →
  `KnnVectorValues.DocIndexIterator.advance(leafDocId)` → `.index()` → `vectorValue(ord)`
  (no direct `vectorValue(docId)`; leaf resolved via `docBase` subtraction, exactly the
  lookup `readModifyWrite` already performs).
- **Bit-exact fidelity**: 768-dim vectors re-read with float-by-float `==` equality, including
  after a full RMW-preserve simulation (stored-field reconstruction + re-read vector carried
  into `updateDocument`).
- **Same-lease semantics match stored fields**: a searcher lease sees pre-update vector state
  until refresh — a second same-doc RMW in one batch reads pre-batch state for vectors and
  stored fields alike; no special-casing needed.
- **Cost**: 5000 single-doc re-reads (fresh TermQuery + fresh iterator each) at one snapshot,
  2 segments: 173 ms total ≈ **35 µs/read**, dominated by the term lookup the RMW does anyway.
- **Implementation must-cover**: the missing-vector branch (`getFloatVectorValues == null` or
  `advance() != target` — docs indexed without a vector, the common mid-ingest case) was only
  defensively exercised; it needs a real fixture in the shipped tests.

### E2 — Item 1 blast radius (all file:line-verified)

- `preserveSplade` threading: **19 plumbing sites** (the `IndexWriteOperation` envelope records,
  `IndexingCoordinator` overloads, `WritePathOps` signatures) + **17 literal-`true` call sites**
  (EmbeddingBackfillOps ×8, NerBackfillOps ×3, GrpcIngestService ×3, MigrationOps ×2,
  SpladeBackfillOps ×1) + ~6 test files (`BatchUpdateIntegrationTest`'s 6 dedicated regression
  tests are the primary rewrite target).
- Catalog machinery: `field-catalog.schema.json` has `additionalProperties: false` at both
  levels — `rmwPolicy` **requires a schema edit** or `ssotValidate` fails the build (good:
  fail-closed by default). Jackson side is `ignoreUnknown=true`, so the POJO
  (`FieldCatalogDef.FieldDef`) and `FieldMapper.FieldDef` must gain the attribute explicitly.
  Procedure: update **both** catalog copies (ssot-catalog-sync gate does parsed deep-equal),
  `regenSsotManifest`, `:modules:ssot-tools:test`. `check-language-agnostic-analysis` only
  inspects field *ids* — indifferent to the new attribute.
- Chunk parity: NER/SPLADE never run on chunk docs (zero `is_chunk` references in either ops
  class); `chunk_embedding_status` is stored+docValues (safe); `splade_status` is the sole
  non-stored/docValues-recoverable status (the field the whole `preserveSplade` mechanism was
  built for); `ner_status` is stored (safe).
- Individual-mode reachability: combined-vs-individual is **runtime-availability-driven, not
  config-gated** (`BackfillScheduler:293-332`: `availCount < 2` → individual) — reachable at
  shipped defaults during encoder warm-up/degraded windows. **Correction recorded against the
  E2 sweep's own aside**: `preserveSplade=true` protects SPLADE *status* only; per the §Item-1
  audit there is no vector-preserving code in `readModifyWrite`, so vector destruction by
  NER/SPLADE-only RMWs in individual mode remains a real (frequency-unquantified) path — the
  design's regression tests pin it either way.

### E3 — Item 3: all APIs proven

- ORT Java 1.24.3 jar (javap-verified): `OrtSession.getMetadata()` →
  `OnnxModelMetadata.getCustomMetadata() : Map<String,String>` (+`getCustomMetadataValue`).
- Live python round-trip on a scratchpad copy of `models/onnx/ner/model.onnx` (135 MB,
  smallest): `metadata_props.add()` → save → reload → **all props intact**, and
  `onnxruntime.InferenceSession(...).get_modelmeta().custom_metadata_map` reads them —
  write-path and read-path proven end-to-end. Size delta for two props: **+67 bytes**.
- All committed models < 1.5 GB (largest: gte `model.onnx` at 1.17 GiB — under the 2 GB
  protobuf ceiling but closest; watch on future swaps). `*.onnx` LFS-tracked by root
  `.gitattributes`; runtime caches gitignored. (Side observation, logged to the inbox shard:
  the main checkout currently shows the model files as *untracked* — pre-existing LFS-state
  oddity, not touched.)

### E4 — Item 4: a better discovery primitive than designed

- The worker already writes `pid=<pid>\nstarted_at=<Instant>` into
  `<dataDir>/index/default.index.lock` (`IndexRootLock.java:82-93`) — **data-dir-scoped by
  construction**, with dead-PID and PID-reuse detection logic already proven Java-side
  (`ProcessHandle` start-time cross-check; unit-tested). jseval's orphan kill should parse this
  file and cross-check via `psutil.Process(pid).create_time()` — `psutil>=5.9` is already a
  hard jseval dependency. Secondary confirmation: the worker cmdline carries
  `-Djustsearch.data.dir=<path>` (`WorkerSpawner.java:474`), so a cmdline-substring filter on
  the exact eval data dir is the double-check before any kill.
- Multi-session safety confirmed: the dev stack defaults to `modules/ui-web/.dev-data`
  (`dev-runner.cjs:243-255`) and jseval's `tmp/headless-eval-data` is **per-worktree**
  (resolved under the invoking checkout's root) — disjoint by construction. The repo's
  existing `cleanup.ps1` kill-all-justsearch-java pattern is explicitly NOT to be copied
  (unscoped).
- `stop_backend()` gap confirmed: it takes only `proc`, not the data dir — the fix threads
  `resolved_data` through so stop itself becomes worker-complete.
- **Design amendment**: kill-then-wipe destroys both forensic artifacts (`logs/worker.log` and
  the `.index.lock` pid record live inside the wiped dir) — the kill step must log the
  discovered PID/cmdline (and optionally copy the log tail) into jseval's own output before
  wiping.
- Failure-path test: a **real held-open file handle** makes rmtree fail natively on Windows
  (share-mode locking) — higher-fidelity than monkeypatching; existing `test_backend.py`
  fixtures (`tmp_path` + mocked Popen/health) accommodate it directly.

### E5 — Item 2: zero code touches needed

- `jseval corpus-fidelity --dataset battlefield-en-v1 --modes hybrid,vector --embedding
  --start-backend --clean` already measures every mode passed and writes
  `retrieval_ndcg_by_mode` for all of them (`corpus_fidelity.py:92,126-129`); the committed
  metadata has one key only because the prior run passed one mode.
- Merge semantics are safe: `cmd_corpus_fidelity` merges into the existing fidelity block
  (`corpus.py:299-307`); certify-owned blocks (closed-book, determinism, collisions) are
  untouched — **one fidelity re-run suffices**, no re-certify.
- Cost: ~2–4 min (390 docs ≈ 2 min enrichment at ~3 docs/s post-691, retrieval over 26 queries
  in seconds, haiku leak probe tens of seconds). Two modes share one ingest.
- **Sequencing coupling**: the run's `--start-backend` *requires* `--clean` — Item 4's defect.
  Land Item 4 first, or wipe `tmp/headless-eval-data` manually once more for this run.
- Register procedure confirmed: add Dataset Catalog rows + Last Validated/Validated By, then
  `node scripts/docs/skills-sync.mjs` (with a `--check`/diff pass — known append-vs-replace
  drift risk, `docs/observations.md:561-563`).

### Confidence + implementation difficulty (derisk verdict)

| Item | Confidence (0–10) | Residual risk | Difficulty |
|---|---|---|---|
| 1 RMW preservation | **8** | live-mode behavior + perf at corpus scale (regression tests + one live run cover it); wide-but-mechanical 36-site fan-out; SSOT schema procedure | the one genuinely hard item: write-path surgery + policy engine + catalog/schema + test rewrite |
| 2 battlefield | **9** | needs one dev-stack run; depends on Item 4 (or one manual wipe) | trivial (measurement + register edit) |
| 3 metadata stamping | **9** | wiring `onnx` load/stamp/save into the two download-only build scripts; Java-side test needs a stamped fixture (stamp a tiny test model, not a committed one) | small, well-bounded |
| 4 fail-closed clean | **8** | real-orphan kill behavior only reasoning-verified (the held-open-handle test covers the wipe half; the kill half gets verified live in one eval run) | small-medium (Python + one Java-adjacent discovery contract) |

Overall arc confidence: **8.5/10** — every load-bearing unknown was converted to measured
evidence; what remains is execution risk, concentrated in Item 1.

**Model/effort routing for implementation** (delegation economics per CLAUDE.md): Item 1's
core (policy engine in `WritePathOps`/`FieldMapper`, catalog+schema change, the 6-test
`BatchUpdateIntegrationTest` rewrite) → **opus**, high effort — correctness-critical write-path
surgery where a wrong-but-green outcome is expensive; Item 1's fan-out (36 call-site
simplifications + remaining test updates) and all of Items 2–4 → **sonnet**, medium effort,
with orchestrator review per chunk. Nothing here needs haiku.

## Execution grouping (derisk-confirmed)

- **PR-1 (Java, heavy):** Item 1 — catalog `rmwPolicy` + preservation engine + orphan deletions
  + regression tests (incl. the missing-vector fixture) + live verify. Rides with this tempdoc.
- **PR-2 (Python, small):** Item 4 — fail-closed clean + lock-file-keyed orphan kill (with
  pre-wipe evidence capture) + docs + held-open-handle test. **Lands before Item 2's run.**
- **PR-3 (Python+Java, small):** Item 3 — stamping helper + resolver rung + stamped-fixture test.
- **PR-4 (docs/measurement, small):** Item 2 — one `corpus-fidelity --modes hybrid,vector` run
  + register rows + skills-sync.

Implementation must branch from current `main` (this design was authored in the stale
`691-takeover` worktree; the Wave-2 resolver classes live on main, not here).

## §Item 1 implementation log (2026-07-11)

Implemented on branch `worktree-711-rmw` off `origin/main` f12ded5. Five commits, each
green (compile + affected module tests): Step 0 characterization, Step 1 catalog+schema,
Step 2 parse+validate, Steps 3–5 engine+teardown+tests.

### Step-0 finding (the data-loss truth, empirically confirmed)

Both silent-loss bugs reproduce against pre-engine `main` (green characterization tests,
then flipped):
- **(a) vector destruction** — a NER-style RMW (`updates = entity_persons_raw` only) on a
  vector-bearing doc drops the vector: after the RMW `searchVector` returns the doc **0**
  times (it had 1 hit before).
- **(b) second silent-loss bug, CONFIRMED** — with `preserveSplade=true`, a doc holding
  SPLADE FeatureField data **and** `splade_status=COMPLETED` loses the FeatureField data
  (a `FeatureField.newSaturationQuery` count goes 1 → **0**) while `splade_status` **stays
  COMPLETED**. The doc thus claims to be SPLADE-encoded but carries zero SPLADE postings.
  This is now fixed: the reset-status lane downgrades COMPLETED → PENDING whenever the
  `splade` field is absent from the update map, forcing a re-encode (data still can't be
  re-read, but the status no longer lies).

### What changed

- **Catalog + schema**: `rmwPolicy` (string, `preserve-reread` | `reset-status:<statusFieldId>`)
  added to `field-catalog.schema.json` (pattern-constrained) and to both `fields.v1.json`
  copies: `vector`/`chunk_vector` → `preserve-reread`, `splade` → `reset-status:splade_status`.
  Manifest regenerated; ssot-catalog-sync deep-equal holds.
- **Parse + fail-fast**: `FieldCatalogDef.FieldDef` and `FieldMapper.FieldDef` carry
  `rmwPolicy`; `FieldMapper.validateRmwPolicies()` (wired next to `validatePrimaryKeySupport`
  in `ComponentsFactory`) requires every non-stored/non-docValues `vector`/`splade` field to
  declare a parseable policy, requires a `reset-status` target to exist and be docValues-backed,
  and forbids a policy on any stored/docValues (non-fragile) field. Unknown policy → ISE.
- **Engine** (`WritePathOps.readModifyWrite` → `applyRmwPolicies`): for each catalog field with
  an `rmwPolicy` absent from the caller's updates — `preserve-reread` re-reads the float vector
  (Lucene 10.4 ordinal read-back, derisk E1; defensive `.clone()`) and carries it forward
  (null ⇒ vectorless doc ⇒ no-op); `reset-status` drives the status field
  (COMPLETED|missing → PENDING + retry reset; FAILED/non-terminal preserved; caller status wins).
  The bespoke SPLADE restore/reset/safety-net block was deleted.
- **Teardown**: `preserveSplade` removed from `IndexWriteOperation` records,
  `IndexingCoordinator`, and `WritePathOps` (collapsed to 2-arg); all **17** literal-`true`
  caller sites updated. `grep preserveSplade` over `src/main` = **0** (over `src/test` = 0 too).
  `updateDocumentPaths`' vector/NER loss-compensation resets (`EMBEDDING_STATUS`/`NER_STATUS`
  =PENDING + retry) removed — a move preserves the vector and the stored NER fields.

### Tests (all green)

- New `RmwFieldPreservationTest` (**8**): vector survives NER-style RMW; vector survives
  VDU-only RMW; missing-vector RMW no-op; same-doc-twice-in-one-batch preserves vector;
  SPLADE-drop → status downgrade; chunk_vector survives; startup fail-fast rejects an
  undeclared fragile field; startup fail-fast rejects a non-docValues reset-status target.
  Vector assertions are bit-exact (`assertArrayEquals`) via a low-level float-vector read-back.
- `BatchUpdateIntegrationTest`: the 6 `preserveSplade` tests rewritten to the reset-status
  semantics (COMPLETED→PENDING downgrade single + batch, FAILED preserved, non-terminal PENDING
  preserved, caller-override wins, missing→PENDING heal); catalog gained a `splade` field so the
  lane fires; race test de-`preserveSplade`d.
- `PathUpdateIntegrationTest`: the 3 loss-compensation tests **flipped** to the preserve
  contract (embedding/chunk-embedding stay COMPLETED; ner_status not reset) and renamed —
  design-mandated (the resets were removed), not a weakening.
- Mock/signature updates: `NerBackfillOpsTest`, `EmbeddingBackfillOpsTest`,
  `CombinedEnrichmentBackfillOpsTest`, `BgeM3BackfillOpsTest`, `BackfillSchedulerModeRecordingTest`,
  `IndexingCoordinatorDispatchTest`, `OpsAbsorbedLogicTest` (VduStatusTransitionsTest needed none —
  already 2-arg).
- Verified: `:adapters-lucene:test` (507 green), `:worker-services:test`, `:indexer-worker:test`,
  `:configuration:test`, `:ssot-tools:test` all green; `build -x test -PskipWebBuild=true` green.
  Live-stack pipeline verification (design's F-030 oracle + perf) is left to the orchestrator.

### Deviations from the design (with rationale)

1. **Retry-field derivation by convention.** The policy grammar names only the status field, so
   the paired retry counter is derived `<prefix>_status` → `<prefix>_retry_count` and used only
   if that field exists in the catalog (`splade_status` → `splade_retry_count`). The design said
   "restore its retry counter" without specifying the linkage.
2. **Safety-net folded in.** The old standalone "missing splade_status → PENDING heal" is now the
   `existingStatus == null` arm of the reset-status lane (same outcome, one code path).
3. **`FieldCatalogDef.FieldDef` delegating 8-arg constructor** (rmwPolicy=null) added so the ~60
   positional callers (`forTesting`/`forChunkTesting`/tests) compile unchanged; only the
   vector/chunk_vector factory rows pass the 9-arg with `preserve-reread`.
4. **`content_all` left out of scope** (logged to the observations inbox): it is `text`,
   stored:false, docValues:false, so it too is dropped by RMW — but it is neither `vector` nor
   `splade`, so it is outside the design's fragile scope and declares no policy. Flagged, not fixed.

## Items 2–4 implementation log (2026-07-11, orchestrator record; work on sibling branches)

- **Item 4 (branch `worktree-711-jseval`, commits 04dfb72/4084bd0):** fail-closed `--clean` +
  double-keyed orphan-Worker sweep (lock-file PID/start-time AND exact
  `-Djustsearch.data.dir=` cmdline; single-key match refuses to kill), forensics logged before
  wipe/kill, hard error naming survivors after one kill-and-retry cycle; `stop_backend` now
  takes the data dir and sweeps. 12 new tests incl. a real Windows held-open-handle failure
  test; full jseval suite 1596 passed (2 pre-existing registered reds). **Live-proven the same
  day:** during the Item-2 back-to-back fidelity runs, the sweep detected and killed a real
  orphaned Worker (PID 33916) left by the first run's stop — the exact defect class, caught in
  production conditions on its first outing.
- **Item 2 (branch `worktree-711-jseval`, commit 5f8c758):** per-mode re-measure at HEAD
  defaults via `jseval corpus-fidelity --modes hybrid,vector --embedding --start-backend
  --clean` (both corpora materialized from committed sources in the worktree; zero code
  touches, as derisk E5 predicted). **battlefield-en-v1: hybrid 0.9517, vector 1.0000 — out of
  band in BOTH modes post-F-031** (design expected only vector saturation; hybrid saturated
  too), no longer a difficulty discriminator; **battlefield-de-v1: hybrid 0.5924 (exact match
  to the 624 certification), vector 0.58 — in-band, remains valid.** Register Dataset Catalog
  rows added with mode-scope notes; the durable record is the register (the materialized
  `datasets/` metadata is gitignored — derisk E5's "committed metadata.json" was corrected
  here: only `scripts/jseval/624-corpora/*/meta.json` generation provenance is committed).
- **Item 3 (branch `worktree-711-stamping`, commits 73bcfc5/c174bce):** `stamp_capabilities` +
  `load_manifest_capabilities` in `scripts/models/_common.py`, wired post-hoc into all four
  build scripts (uniform load/stamp/save; `stamped_metadata_keys` recorded in build.json;
  graceful skip for manifest-less dirs); resolver rung between manifest and ecosystem files in
  all five fact methods, one probe session per resolve via new
  `OrtSessionAssembler.probeCustomMetadata`; manifest wins with disagreement WARN. Two tiny
  committed fixtures (429/145 bytes) + 3 new tests; ort-common suite green incl. the live gte
  resolution test. No committed model was restamped (forward-only per design).
