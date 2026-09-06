---
title: "Format capability characterization and duplicate-prevalence measurement: production-path extraction fixtures, representative cached corpora, and calibrated non-redundancy evidence"
type: tempdocs
status: "IMPLEMENTING (2026-09-06) — task-only current-main integration and measurement reconciliation"
created: 2026-09-02
updated: 2026-09-06
lane: 887 L13
model: opus (takeover)
parent: 887-improvement-landscape-register
related:
  - 686-real-pdf-corpus-and-tika-pressure-measurement   # complete: mixed/realdocs-v1 has 620 pinned real files
  - 705-document-extraction-improvement-and-tax-reduction
  - 786-extraction-quality-scorecard                    # complete extraction-quality measurement; do not duplicate
  - 639-candidate-set-integrity-ann-recall-and-result-dedup
  - 314-simhash-dedup                                   # abandoned; its fixed threshold is not an authority
  - 666-real-eval-corpus-acquisition
  - 709-shared-dataset-fetch-cache
  - 741-eval-corpora-are-derived-artifacts
---

# 897 — Format capability characterization and duplicate-prevalence measurement

## Integration execution — 2026-09-06

Execution evidence: [current-main integration](897-evidence/current-main-integration.md).

The user authorized proceeding with the reassessment's sequencing. Work now uses the dedicated
`codex/897-current-main` checkout from `fdf50933e`; `codex/897-format-breadth` at `6c7177dfa` and the
verified runtime archive remain recovery evidence. The imported delta is `189719e0..6c7177dfa` only.

Design: preserve main's identity/revision authorities and merge-state handling, then capture production
observations after all requested index mutations. Source-byte, stored-content, and normalized comparison
hashes retain their distinct meanings. Existing bounded snapshot and staged-recall seams remain owners.
The superseded mapper implementation and retired envelope references are removed; campaign output is
kept ignored under the current data policy. Near-duplicate decisions remain cohort-bound and cannot be
transferred from Enron to legal/realdocs by reusing a numeric threshold.

Reach: snapshot identity must follow all deliberate mutations and remain stable across observation.
The settle/capture/query/revalidate regression proves the ordering earns its keep. No general framework
is warranted; retire any extra coordination once the existing owner guarantees the same ordering.

- [x] I1: reconcile textual and semantic integration conflicts; preserve all current-main behavior.
- [ ] I2: validate focused/full jseval, affected Java and installed-Worker tests, build, PMD and projections;
  independently review the combined production changes before corpus execution.
- [ ] I3: reconcile calibrated-decision consumption and supported result-redundancy scope with executable
  tests; preserve completed adjudication and explicitly bound any still-unmeasured cohort.
- [ ] I4: validate a fresh inference-capable production path, then complete realdocs extraction/VDU and
  subsequent enrichment; emit a reconciled aggregate with exact terminal exclusions.
- [ ] I5: capture legal query-visible redundancy with all retrieval modes and stable snapshot identity;
  run comparable SciFact regression gates against authoritative floors without baseline changes.
- [ ] I6: reconcile acceptance and §M, update 639/register/canonical docs, regenerate/check documentation,
  finish independent review and commit the authorized work. No PR, push, merge or publication.

## Reassessment — 2026-09-06 (basis for integration)

**GO: this work is still worth completing now. NO-GO: blindly resume the former evidence-only plan,
restore the old index as current evidence, or publish the existing branch. LITE-CLASS: no.**
This pass investigates and records; it does not implement, rebase, start a backend, change baselines,
or reopen completed labeling. No user decision is pending for this reassessment.

### Repository and integration facts

- Verified this dedicated checkout on `codex/897-format-breadth` at `380eb7ad19e83f539326849b92590ee0fb4e2a02`,
  initially clean. The task is exactly 24 commits after `189719e08966b654bc214955bf7766f7df72b473`.
  Fetched `origin/main`; the comparison is pinned to `fdf50933e` (2026-09-06). Local main is divergent and
  dirty, so it is not the integration target. World-state ran before adopting this checkout.
- The reported 57 commits behind and 144 overlapping paths describe ancestry-wide history, which includes
  already-squashed work. The task-only delta is **115 paths, 23,169 insertions, 343 deletions**. Of those,
  **34** differ between the task base and current main. Treat inherited commits separately from task work.
- A non-checkout transplant probe,
  `git merge-tree --write-tree --name-only --no-messages --merge-base=189719e0 origin/main 380eb7ad`,
  returned eight conflicts: both jseval skills, the jseval reference, the repro manifest, `FieldMapper.java`,
  `commands/run.py`, `run.py`, and `projections/staged_recall_accounting.py`. Its diagnostic tree is
  `e1a5cb9bcecfc5ca36e3831b5df244d99ceea3de`. This deliberately models the task delta; it is not an ordinary
  branch merge or a tested integration. No index, worktree, or branch was changed by the probe.
- Clean textual merges are insufficient: diagnostic-tree `FieldMapperTest.java:95` still calls the old
  one-argument `toDocument`, while current-main `FieldMapper.java:154` requires two arguments. This is a
  source-level compile incompatibility found without claiming that an integrated build ran.

### What remains necessary, and what current main supersedes

| Area | Reassessment and primary-source evidence |
|---|---|
| Format fixtures and extraction repairs | Still useful and not present on main. Tika remains 3.2.3. Main's `PersistentExtractionSandbox.java:56,103` still uses the 2 MiB response default; the final exact-ID/liveDocs changes are also absent from main's `FolderBrowseEngine.java`. Retain the characterization boundaries and regression intent. |
| Stored-only keyword mapper | Main already supplies this capability for content revisions (`FieldMapper.java:516-521`). 897's duplicate mapper branch is superseded; its source-byte field is not. `source_sha256` binds raw bytes, main's `content_sha256` binds stored text, and the analyzer's normalized digest binds its comparison view. They are different facts. |
| Enrichment repair | Main changed chunk-SPLADE enrollment, flag handling, chunk reconstruction and write paths. Preserve those changes. Its `CombinedEnrichmentBackfillOps.java:603-611,771-782` still routes late parents through the probe and consults spent `unitsDone` before window progress, so 897's resumability repair is not proven superseded. Reverify the combined behavior; the old legal run does not certify it. |
| Identity and snapshots | Main now owns stable UIDs and stored-content revisions (`docs/explanation/04-storage-engine.md:91-132`). 897's raw source binding and private run-local aliases serve distinct purposes. Reconcile them with current revision/parent-slice behavior rather than replacing one hash with another. |
| Run orchestration | Main added chunk-SPLADE readiness, `index_state_at_query`, and `--settle-index`, retired envelope production, and replaced the TREC reader. The conflict at `run.py` is between pre-query index settling and private snapshot capture; both invariants must survive. A forced settle after capture would invalidate its generation/commit identity. Preserve the new TREC import in staged recall accounting. |
| Verification and publication data | Main's jseval suite is now required CI, PMD includes test sources, and the historical cadence assertion is fixed (`tests/test_run.py:1302-1311`). The old sole-failure waiver no longer applies. Main's `scripts/jseval/README.md:7-27` prohibits new committed campaign outputs: 897's committed Enron run must be preserved locally and reconciled with that policy before publication. No new exception is justified here. |

### Archive: preserved evidence, unfinished runtime

The retained recovery ref is `codex/archive/897-realdocs-vdu` at `d8c564a49`, one task commit before
the final exact-ID snapshot repair. Cleanup preserved 13,732 ordinary files (4,037,283,701 bytes) in
`897-realdocs-vdu.d8c564a49.tar.gz` (1,330,319,913 bytes). This pass recomputed the archive SHA-256:
`15f64739dfe63862fa35306ec7ed7c4d5824eb27c1c53b84b2b44bae22a62850`, matching the cleanup manifest.
It also verified hashes and sizes of eight selected runtime/log/telemetry members while reading them
directly from the archive. No archive files were extracted over a checkout; no Lucene index was opened.

- The substantial full-stack root's latest Worker metrics, at **2026-09-05 13:51:31.367 UTC**, report
  **49 pending VDU, 553 pending embeddings, and one pending/ready job**. Those gauges establish that the
  saved run was unfinished, not why each item remained. The archived metric member SHA-256 is
  `4148af41fd1acbd9e46ba9db68c7b845973553edc0c0a7035634649f7aeab70b`.
- A second, twice-nested checkout-relative data root reports zero pending VDU but only five documents
  indexed by that process; it cannot establish completion of the 620-source cohort. Its metric SHA-256 is
  `86a302b3683fa4fd3152425089f8f0d1ad1b9c7950cb5df174522c25b8965096`.
- Runtime manifests span multiple instances and READY/DEGRADED states. They are historical snapshots,
  not current health. Fresh MCP `quick_health {detail:"full"}` returned ABSENT, no foreign runs, and
  inference REFUSED. This pass acquired no lease and launched no stack.
- Recovery instructions belong to the cleanup outcome and adjacent per-file manifest. The local audit
  is `scripts/jseval/tmp/897-reassessment/archive-inspection.json`; its inspector verifies the archive
  digest and selected member hashes without exposing source text. Reusing the archive for historical
  forensics is valid; treating it as a current-main compatible or already-quiescent index is not.

### Evidence claims that survive, and gaps the old close state missed

| Claim | Current disposition |
|---|---|
| Enron full exact census | Retained artifact passes its hash and JSON schema again: file SHA `4ebf994d…e492c`, canonical `fe758756…141e7`. The 77.009%/77.626% figures remain eligible-body proxy census results, not Worker-extracted or personal-drive prevalence. No rerun is needed merely because Git history advanced. |
| Enron adjudication | The aggregate decision is intact: file SHA `480a6ca8…b282`, canonical `66fcd1e8…10a907`. Threshold 0.90 and the model-assisted holdout result remain conditional on those labels. This pass checked the self-hash without selecting or evaluating the threshold again. |
| Legal extracted prevalence | The retained 199-document artifact passes its hash/schema: file SHA `cd410c97…af8ac7`, canonical `7312e6cc…b19131`. It supports the historical zero byte/content-exact result. It contains no recovered live query-redundancy result. |
| Realdocs | No validated production aggregate. The original 620 source / 619 indexed / one classified terminal exclusion remains a historical extraction result. The later archive does not close readiness. VDU completion must also include the enrichment it triggers and a fresh complete snapshot reconciliation. |
| Calibrated near-duplicate measurement | **Not connected to prevalence or result decoration.** `duplicate_prevalence.py:1661-1666` always emits `UNDECIDED`; `result_identity.py:701-709` only accepts normalized-content-exact clusters with a null threshold. Completed scoring is a separate capability. Any near-duplicate result/prevalence claim requires a provenance-bound consumer and appropriate corpus calibration; Enron labels do not automatically validate legal or Office/PDF thresholds. |
| Representative format breadth | The 33-file EML/RTF/ZIP sibling remains one source collection per format. That is useful real-input characterization, not source-diverse representative coverage. Do not silently graduate it to the stronger §C claim. |
| Personal corpus / 639 | The available cohorts are research proxies even when processed privately. There is no authorized personal-corpus query-visible result in this evidence. Product dedup remains deferred; 639's ANN work remains separate. |

### Verification and next decision boundary

Fresh retained-branch command, from `scripts/jseval/`, completed **292 passed, 4 skipped in 5.19s**:

```text
python -m pytest tests/test_duplicate_prevalence.py tests/test_duplicate_prevalence_schema.py tests/test_duplicate_prevalence_production.py tests/test_duplicate_prevalence_enron_command.py tests/test_duplicate_review_packet.py tests/test_duplicate_review_labels.py tests/test_duplicate_review_scoring.py tests/test_format_breadth_corpus.py tests/test_raw_corpus_manifest.py tests/test_result_identity.py tests/test_projections_staged_recall_accounting.py -q
```

This is evidence for the old branch's measurement core, not for current-main compatibility. No fresh
Gradle, installed-Worker, full jseval, or SciFact campaign ran. Integration needs those checks, including
the new PMD/required-suite surfaces, before the existing acceptance checkboxes can be reconciled.
SciFact regression evidence must use current authoritative floors and comparable model/corpus settings;
paired runs need all `lexical,vector,splade,hybrid` modes and verified equal merge state. Record actual
settle success, because the current helper can warn and continue on failure. Do not revive retired
envelope gates or change baselines to close this lane.

**Cheapest decisive evidence already obtained:** the task-only merge probe, source-level API mismatch,
archive tail gauges, and fresh focused tests show a valuable surviving implementation that needs
integration before new production claims. Waiting on the old VDU job would answer neither compatibility
nor the missing query result. The next stage is a separate current-main integration/reconciliation
exercise preserving `380eb7ad` as a recovery point, followed by bounded production validation. This is
a sequencing verdict, not a new implementation design or an authorization to publish.

Historical bulk execution records were moved verbatim to [897 evidence](897-evidence/execution-history.md)
so this tempdoc can meet main's 800-line cap while retaining its design and acceptance checklists.
Documentation checks: the shipped size checker reports 765 lines; frontmatter parsing, ten evidence
anchors, byte-content preservation of all ten moved blocks (newline-normalized), and `git diff --check`
pass. The own-session helper sweep deleted nothing and found no task-owned helper to stop.
Research checked [Git's merge-tree contract](https://git-scm.com/docs/git-merge-tree) for the non-checkout
probe and [Tika's pinned format catalog](https://tika.apache.org/3.2.3/formats.html); neither substitutes
for the repository's production-path tests. PR, merge, publication, and baseline changes remain unauthorized.


## Historical takeover verdict (superseded by the 2026-09-06 reassessment)

**GO now, after rechartering.** The evidence gap is real and 686 has removed the old corpus
dependency, but the original charter mixed two jobs whose evidence requirements conflict:

1. a deterministic, known-answer **format capability characterization**, and
2. a representative, distribution-sensitive **duplicate-prevalence measurement**.

Those must be separate cohorts joined by one report, not one “real corpus with planted markers.”
The former needs small generated fixtures with exact expected output. The latter needs untouched
real distributions; planting markers, pre-deduplicating, or using fabricated/injected corpora would
bias the very rate being measured.

**LITE-CLASS: no.** This is measurement/tooling and test design, not pure teardown, rename, or
configuration deletion.

The cheapest decisive evidence is a one-file-per-capability smoke through the **production extraction,
index, and lexical-search path**, with a second direct assertion on the structured extraction result.
That evidence does not exist today. The nearest test, `OfficeMarkerSearchabilityTest`, calls the flat
`ContentExtractor` and manually builds/indexes a Lucene document
(`modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OfficeMarkerSearchabilityTest.java:86-106,171`).
It cannot prove production policy, sandbox routing, structured extraction, or directory-walk admission.

This work should therefore proceed, but only under the design and gates below. It must not add a
product extractor or product deduplication policy.

## What the takeover found

### Stale premises corrected

- **686 is complete, not open.** `mixed/realdocs-v1` already has a pinned manifest of 620 real files
  and jseval raw-binary ingestion support (`docs/tempdocs/686-real-pdf-corpus-and-tika-pressure-measurement.md:4,154,206`).
  Its actual extension coverage is 311 PDF, 80 DOC, 60 XLS, 50 DOCX, 49 XLSX, 40 PPT, and 30 PPTX.
  It contains no mail, EPUB, ODF, RTF, archive, notes-vault, or source-tree members.
- **692 is not the corpus-provenance authority.** The active seams are 666 (corpus recipes), 709
  (shared cache), 741 (derived artifacts, not LFS), and the jseval corpus identity/certification code.
  692 is about the Worker not ingesting its own runtime artifacts.
- **786 already measured the PDF extraction tax.** This lane is capability breadth and duplicate
  prevalence; it must not rerun or reframe the OHR-Bench clean/Tika/GOT/MinerU result.
- **314 is historical, not a threshold authority.** Its SimHash design was abandoned because FiQA
  and NFCorpus supplied no real-user duplicate evidence. A hard `simhash-64 <= 3 bits` rule cannot
  become ground truth merely by being copied into a new command.
- **639 owns candidate-set integrity.** Search-quality Q-013 requires any result-set redundancy
  projection to extend `staged_recall_accounting`, not create a second recall instrument
  (`docs/reference/search-quality-register.md:3024-3029`).

### Current implementation facts

- The repository pins Apache Tika **3.2.3** (`gradle/libs.versions.toml:10,69-70`). Tika’s own 3.2.3
  format catalog says the standard parser package supports EPUB, RTF, archives including ZIP,
  RFC822 mail, mbox, Outlook MSG/PST, ODF, and several source-code types. It also cautions that
  detection covers more formats than extraction, so “Tika detects it” is not a searchability proof:
  <https://tika.apache.org/3.2.3/formats.html>.
- The production policy path is `PolicyDrivenTikaExtractor` → `StructuredContentExtractor`, wrapped
  by the timebox/sandbox composition (`PolicyDrivenTikaExtractor.java:69-74,101-116,153-163` and
  `ExtractionSandboxFactory.java:205-242`). The structured extractor uses `AutoDetectParser` and a
  `ParseContext` (`StructuredContentExtractor.java:55-61,149-166`). This is the seam the capability
  proof must exercise.
- Tika archive/mail parsing can recurse into embedded resources. Tika documents that recursive
  parsing needs explicit resource accounting and can retain contents in memory; its package parser
  passes archive entries to a second parsing stage. JustSearch currently concatenates SAX events into
  one `StructuredDocument` and does not retain embedded-resource identity. Therefore a ZIP marker may
  be searchable while its internal path/provenance is lost. Sources:
  <https://tika.apache.org/3.2.3/api/org/apache/tika/parser/RecursiveParserWrapper.html> and
  <https://tika.apache.org/3.2.3/formats.html#compression-and-packaging-formats>.
- Existing structured tests are partly observational: DOCX/HTML tests compute heading/triplet booleans
  and only print them (`StructuredExtractionIntegrationTest.java:91-114,243-265`); XLSX only asserts
  non-empty content (`:122-131`). These must not be treated as structure gates.
- The table IR has no merged-cell, multi-row-header, or numeric type model. It treats any table with
  more than one row as header-bearing and serializes the first row as headers
  (`modules/indexing/src/main/java/io/justsearch/indexing/extraction/StructuredDocument.java:34-39,215-235`).
  The SAX handler also stores only stripped cell strings and ignores nested tables
  (`StructuredContentHandler.java:48-55,80-103,153-184`). Merged/multi-row expectations are therefore
  characterization findings, not an already-supported contract.
- An Obsidian vault and a source repository are **directory-layout/admission cases**, not new document
  formats. Markdown/source content is nominally handled. Walks skip explicit directory basenames such
  as `.git`, `node_modules`, and `__pycache__`, and compiled extensions such as `.class`/`.pyc`; they do
  **not** skip every dot-directory or common `build`/`dist` directories. In particular, `.obsidian` is
  traversed and non-hidden files such as `.obsidian/app.json` are currently admissible because the
  per-file check examines the file basename (`IngestionSkipPolicy.java:26-60,125-140` and
  `SyncDirectoryOps.java:266-283`). The matrix must measure that current boundary, not assume vault
  metadata or all build output is excluded.
- The existing Enron fetcher is unsuitable for exact-duplicate prevalence: it hashes bodies and removes
  repeats before reservoir sampling (`scripts/jseval/jseval/corpus_fetch.py:421-456`). Keep that command’s
  established behavior; a measurement reader must inspect the cached upstream population before that
  filter or use a new non-mutating projection.
- jseval `raw_files` ingestion currently points directly at a corpus directory without materialization or
  a sidecar (`scripts/jseval/jseval/ingest.py:213-258`), while the default corpus signature is defined over
  `corpus.jsonl` plus qrels (`corpus_identity.py:20-59`). A raw-file run can therefore lack identity for
  the bytes it measured. 897 must bind its artifacts to a canonical raw manifest containing relative path,
  size, and SHA-256; a hash over an absent `corpus.jsonl` is not sufficient.

## Research conclusions

Internet research was warranted because parser coverage and source-use terms change independently of
the repository.

1. **Parser support is plausible, not proven.** Apache Tika 3.2.3 officially lists the target parsers
   and explains archive second-stage parsing. That validates a characterization study, not a product
   support claim. The exact JustSearch route and output still require local evidence.
2. **Generated committed fixtures are the safe default.** Apache Tika is Apache-2.0, but its repository
   license contains additional notices for some bundled test documents. No upstream binary fixture may
   be copied merely because it lives in that repository; verify its per-file provenance first:
   <https://github.com/apache/tika/blob/main/LICENSE.txt>.
3. **Downloaded EPUBs should remain derived/cache-backed.** Project Gutenberg permits broad use for
   many books but distinguishes unrestricted works, permission-posted copyrighted works, and trademark
   conditions. Any selected book needs a per-item check; do not commit a bulk sample under a blanket
   “public domain” assertion: <https://www.gutenberg.org/policy/license>.
4. **Near-duplicate thresholds are empirical.** SimHash was designed as a scalable candidate method for
   web crawling, not as a universal semantic label. Shingle similarity supplies an interpretable
   resemblance measure, and the threshold still trades false positives against false negatives. Use a
   labeled audit to calibrate it rather than declaring Hamming distance 3 correct a priori. Sources:
   <https://research.google/pubs/detecting-near-duplicates-for-web-crawling/> and
   <https://nlp.stanford.edu/IR-book/html/htmledition/near-duplicates-and-shingling-1.html>.

No external code, text, or binary asset was copied during this takeover.

## Revised design

### A. Evidence product 1 — deterministic capability matrix

Build a small, repository-owned fixture recipe whose source text and expected markers are committed;
materialized binary fixtures may be committed only when the recipe is deterministic, reviewable, and
passes the repository’s license/notices policy. “At least 20 documents per format” is retired: it adds
runtime and repository weight without improving a known-answer capability assertion.

The matrix has three classes:

| Class | Cases | Required proof |
|---|---|---|
| Container/document | `.eml`, `.mbox`, `.epub`, `.zip` with nested text + Office, `.odt`, `.rtf`; `.msg` only if provenance-clean fixture generation or a cleared sample exists | MIME/parser route, non-empty extraction, marker survives production index/search |
| Structure-sensitive | `.xlsx` simple headers, merged/multi-row headers, typed numerics; `.pptx` speaker notes; heading/list/table document | direct annotated-text + `StructuredDocumentSummary` assertions, then marker search |
| Layout/admission | miniature Obsidian vault and Java/TypeScript/Python tree | notes/source admitted; explicit skip directories/extensions excluded; `.obsidian` metadata and `build`/`dist` behavior characterized rather than presumed |

Each row records: fixture recipe/version, expected MIME family, admission outcome, parser id, sandbox route,
keyword marker result, structural assertion result, and failure class. The first characterization may
record an explicit `KNOWN_GAP`; subsequent tests pin it in a checked expected-state file so a future
regression cannot masquerade as an unchanged limitation and a future improvement cannot pass unnoticed.

### B. Two test layers, neither substituting for the other

1. **Production-path lexical proof.** Use the existing local Worker-process pattern in
   `RichDocumentIntegrationTest` for direct source submission and search; add a `SyncDirectoryIntegrationTest`
   companion only for Obsidian/source-tree traversal claims. Pin the real route:
   `WorkerScanOps` → extraction sandbox routing → `JobBatchExtractor` artifact validation → indexing/search.
   After search, fetch the indexed document slice and assert the stored annotated content and parsed
   `visual_extraction_evidence`. Do not use `TestDocumentBuilder` or direct Lucene writes.
2. **Fast diagnostic characterization.** For structure-sensitive cases, call
   `PolicyDrivenTikaExtractor.extractArtifact` and assert `StructuredDocumentSummary` counts plus the
   **exact post-Tika annotated serialization**. `StructuredExtractionResult` exposes aggregate counts and
   flattened annotated text, not table cells or the `StructuredDocument`; a count/marker assertion alone
   cannot prove merged-header fidelity. A test-only IR seam is permissible only if exact serialization
   cannot locate the loss; do not add a product API for this measurement. This layer localizes a failure;
   only the Worker-process + fetched-slice layer is acceptance evidence.

Failure taxonomy is fixed and machine-readable: `ADMISSION_SKIPPED`, `MIME_OR_PARSER_UNSUPPORTED`,
`SANDBOX_ROUTE_LOSS`, `EMBEDDED_CONTENT_OR_IDENTITY_LOSS`, `STRUCTURE_FLATTENED`, `CHUNK_OR_INDEX_LOSS`,
and `SEARCH_MISS`. Diagnostic output must not include corpus paths or extracted private text.

### C. Evidence product 2 — representative robustness corpus

Do not merge this with the fixture corpus. Reuse `mixed/realdocs-v1` unchanged for Office/PDF robustness.
Add only small recipes/manifests for any newly approved sources; downloaded bytes remain in the shared
709 cache and materialized corpora remain gitignored per 741. Counts should be justified by the statistic
being estimated, not a uniform `n >= 20` convention.

Create format breadth as a sibling recipe/corpus; do not mutate the immutable `realdocs-v1` manifest.
New fetchers must use jseval’s canonical verified `dataset_cache` rather than cloning the older standalone
cache resolution in `fetch-realdocs-corpus.py`.

The real-characterization arm must be **source-diverse** and report per-format sample and producer counts.
One generated file proves compatibility with one producer; it does not establish real-world breadth.
`mixed/realdocs-v1` supplies that arm only for PDF/Office, so EML/MBOX/EPUB/ZIP/ODF/RTF remain
deterministic-characterization-only until their own cleared, source-diverse samples exist.

For EPUB/mail/ODF/RTF robustness, source acquisition is a gate:

- prefer a pinned, redistributable archive with a content hash and explicit terms;
- otherwise fetch/cache without redistribution and record the exact source/terms;
- if neither is available, report the format as deterministic-characterization-only rather than inventing
  representativeness;
- `.msg` is optional until a provenance-clean source or deterministic generator is proven.

The fixture cohort may diagnose capability but must never contribute to prevalence estimates.

### D. Table fidelity is a bounded characterization

Use three owned fixtures: ordinary one-row headers, merged/multi-row headers, and numeric/date/formula
cells. Report both the extracted text and the structural counts. The expected current result is that the
ordinary case preserves useful header/value pairs while merged/multi-row semantics and numeric typing
are not represented. This lane records that boundary; it does not alter the IR or claim that string
rendering preserves types.

### E. Duplicate-prevalence measurement

Add one jseval measurement command under the existing command registry. It operates on an immutable
corpus snapshot and emits a versioned, content-addressed JSON record. For `raw_files`, the content address
is the canonical manifest digest over ordered `(relative_path, size, sha256)` entries plus extraction-policy
identity; never fall back to the normal `corpus.jsonl`/qrels signature when those files are absent. It must
distinguish:

1. **byte-exact files** — SHA-256 of raw bytes;
2. **content-exact documents** — SHA-256 of a documented normalized extracted-text view;
3. **near-duplicate content** — candidates from a scalable fingerprint (SimHash and/or MinHash), confirmed
   against full token-shingle Jaccard similarity at thresholds calibrated on a labeled pair sample;
4. **filename version-family hints** — a separate heuristic rate, never counted as duplicate truth without
   content confirmation.

Pre-register the normalization, shingle width, fingerprint/candidate method, clustering rule, denominator,
threshold sweep, and random seed. Split labeled pairs into calibration and holdout sets. On a tractable
slice, compare candidate generation with exhaustive all-pairs Jaccard to measure candidate recall; otherwise
an approximate first stage can silently bias prevalence downward. Report holdout precision/recall and
uncertainty, not the calibration score as validation.

Report document prevalence and cluster-size distributions with denominators and bootstrap intervals.
For **query-bearing** corpora, apply the same confirmed cluster ids to each query’s top 10 and report unique
clusters@10, redundant hits@10, and queries affected. Before joining clusters to results, define a
collision-safe document key and a run-local fingerprint sidecar: current jseval result normalization can
collapse hits to lowercase leaf filename stems, which aliases same-name files in different directories and
cross-format copies. Reconcile every ranked hit to exactly one sidecar identity and fail on zero/multiple
matches. This result-set projection extends the canonical `staged_recall_accounting` artifact/reconciliation
seam named by Q-013; it does not mint a rival answer to candidate survival.

#### Cohorts and interpretation

- `mixed/realdocs-v1`: valid real-file proxy for Office/PDF duplicate prevalence.
- raw CMU Enron cache: valid only through a new read-only, seeded snapshot taken **before** the existing
  fetcher’s exact-body dedup. Do not change `corpus-fetch-enron-raw` semantics. The source iterator decodes
  with replacement, normalizes line endings, strips headers/body, applies a minimum-word floor, and then
  deduplicates. Report separate denominators for raw members, parsed bodies, eligible bodies, and sampled
  bodies. Raw/body proxy statistics are not production-extracted prevalence; that claim requires ingesting
  raw messages through the Worker and exporting the stored extracted content.
- format fixture corpus: diagnostic/calibration only; excluded from prevalence headlines.
- demo, fabricated 669, and injected legal/email 1k/10k: excluded from real-world prevalence because
  their construction/template/injection geometry is designed, not sampled. They may be used only as
  command self-tests with known duplicate clusters.
- optional owner-provided local root: the only route to a claim about a “personal corpus.” It must emit
  aggregate counts/hashes only, never paths or text, and its artifacts stay uncommitted. Without it, all
  conclusions must say “research-corpus proxy,” not “personal-corpus rate.”

`mixed/realdocs-v1` is ingest-only and has no query set, so it cannot contribute a top-10 row. The required
query-bearing research proxy is `mixed/legal-clerc-200` if its recipe, queries/qrels, and source terms still
validate at execution; otherwise the top-10 headline stays explicitly unmeasured and the product gate
cannot advance. Top-10 redundancy is reported only for real query-bearing corpora whose result identities
reconcile to the fingerprint sidecar; injected 1k/10k queries/results remain diagnostic.

897 absorbs only 639’s **non-redundancy measurement**. ANN recall stays wholly in 639. A product-design
recommendation requires both prevalence and query-visible redundancy on an authorized personal corpus,
because 314 already rejected curated benchmarks as a proxy for personal-file prevalence. Without that
input, 897 may recommend only “keep product dedup deferred” or “collect personal-corpus evidence”; it may
not graduate 639 into a product dedup design. A high proxy-corpus rate with no top-10 effect is not a product
case, and a small proxy rate that occupies top-10 slots is only a reason to seek personal-corpus evidence.

## What this design reuses, supersedes, and does not own

### Reuses

- 666 corpus recipes/identity, 709 shared downloads, 741 derived-artifact policy;
- 686 `raw_files` ingestion and immutable `mixed/realdocs-v1` manifest;
- the production extraction-policy/sandbox composition, not a test-only substitute;
- 636/639 `staged_recall_accounting` as the result-set projection authority;
- the search-quality register as the final evidence index.

### Supersedes in this tempdoc

- the stale “depends on 686 / build the shared asset here” instruction;
- uniform `>= 20` known-marker files per format;
- `OfficeMarkerSearchabilityTest` as the test template (it remains useful for its narrower direct-index
  lexical contract, but is not extended for this proof);
- fixed `simhash-64 <= 3` as the near-duplicate label;
- headline prevalence runs on demo/fabricated/injected or pre-deduplicated corpora;
- updating completed tempdoc 686 with a new corpus location.

### Does not own

- product dedup/collapse/ranking behavior (639, only after this evidence);
- ANN recall implementation (639, through staged recall accounting);
- new extractor or Tika configuration, table IR/numeric typing, OCR/VLM quality, transcription, or
  relevance-baseline changes.

No code/configuration/docs are orphaned by this design. If implementation creates a new test that fully
subsumes a narrow existing assertion, delete or fold that assertion in the same change instead of leaving
two authorities.

## Design reach

**Principle: separate capability proof from prevalence evidence.** A generated fixture is excellent at
answering “can this route preserve X?” and structurally incapable of answering “how often does X occur?”;
an untouched real corpus has the inverse tradeoff because its ground truth is incomplete.

Candidate scope: embedded attachments, OCR/VDU routing, metadata preservation, archive recursion, and
future media transcription. Existing format tests partially violate the principle by printing structural
observations without asserting them, while the old 897 charter tried to make one marker-planted corpus do
both jobs. Do not build a generalized framework now; the two explicit 897 products are enough.

The principle earns its keep if (a) a direct parser test passes while production-route capability fails,
or (b) the representative prevalence result changes the 639 product decision despite deterministic
fixtures passing. Retire it if repeated lanes show a single provenance-clean corpus can provide exact,
stable ground truth and an unbiased population estimate without fixture mutation; until then, combining
the cohorts would erase the distinction the measurement needs.

## Implementation plan (2026-09-03)

The plan skill and three bounded plan reviews converged on seven serial slices. A slice is complete only
when its own checks are green; later evidence and §M must not be claimed from an earlier slice. Run only
one Gradle build at a time and check shared runtime ownership before Worker/system tests.

### P1 — Deterministic fixture oracle and fast diagnostic characterization

- [x] Add `FormatCapabilityFixtureFactory` to `worker-services` test fixtures. Generate owned EML, MBOX,
  RTF, EPUB, ODT, ZIP-with-text/nested-XLSX, three XLSX structure cases, and PPTX-with-notes at test time.
  Use stable ZIP entry order, STORED entries, a fixed representable DOS timestamp, explicit CRC/size, and
  owned marker text; commit no opaque third-party binaries. `.msg` remains optional.
- [x] Add `expected-state.v1.json` with recipe version, SHA-256, markers, MIME family, expected annotated
  fragments/counts, embedded-content/identity expectations, and PASS/KNOWN_GAP classification.
- [x] Add `PolicyDrivenFormatCapabilityTest` over `PolicyDrivenTikaExtractor.extractArtifact`; assert
  generated hashes, MIME/content, structured evidence, parser/policy, and exact annotated serialization.
- [x] Extend the existing extraction routing test for AUTO file-family selection. Do not claim that this
  proves a particular child process handled an E2E file: startup failure may fall back to in-process.
- [x] First prove RTF + simple XLSX + valid ZIP. The owned recipes are byte-deterministic and
  content-addressed; the production extractor matches the pinned MIME/marker/annotated-text oracle; the
  AUTO routing classifier sends all three to the isolated side. ZIP child content survives, while child
  labels survive only in rendered text and machine-readable resource identity/count/depth remain an
  explicit `KNOWN_GAP`. Focused verification passed 8 tests on 2026-09-03.

Historical execution/verification: [E1 — P1 fixture execution](897-evidence/execution-history.md#e1).

### P2 — Composite production-path acceptance

- [x] Add a single `FormatCapabilityMatrixE2ETest` in the opt-in `systemTest` source set, reusing one
  installed Worker for all format rows. Submit documents over gRPC, wait for queue/doc convergence,
  TEXT-search the unique marker, fetch the complete stored slice, and assert MIME/annotated content plus
  `visual_extraction_evidence`.
- [x] Extend `GrpcTestClient` only as needed to query recent privacy-safe ingestion events; reconcile the
  path hash to assert parser/policy/outcome. Pair this E2E evidence with the P1 routing-classifier test.
- [x] Add a traversal-only companion to `SyncDirectoryIntegrationTest` for the Obsidian/source tree. Pin
  current policy: `.git`/`node_modules`/`__pycache__` and compiled extensions skip; `.obsidian` and common
  `build`/`dist` content are characterized, not presumed skipped.
- [x] Register any new local-only evidence site in `scripts/ci/test-evidence-policy.v1.json`. No new
  per-site registration is needed: both tests live in the existing opt-in `systemTest` source set, and
  the checked policy already declares that evidence tier.

Historical execution/verification: [E2 — P2 production and traversal execution](897-evidence/execution-history.md#e2).

### P3 — Strict raw-corpus identity

- [x] Add a strict raw manifest builder/validator beside, not inside, the existing best-effort
  `corpus_signature`: canonical relative paths, size, SHA-256, deterministic canonical-JSON digest, and
  rejection of missing, extra, duplicate, escaping, or case-colliding paths.
- [x] Thread the strict identity through raw run provenance, index identity/cache selection, raw ingestion,
  and dataset counting. Keep legacy non-raw corpus identity behavior unchanged.
- [x] Preserve `realdocs-v1`; any format-breadth corpus is a sibling recipe using canonical `dataset_cache`.

Verification covers manifest ordering and every fail-closed case, plus equality of run/cache/ingest identity.

Historical execution/verification: [E3 — P3 manifest execution](897-evidence/execution-history.md#e3).

### P4 — Duplicate analyzer core, CLI, and pre-dedup source stages

- [x] Add `duplicate_prevalence.py`, a versioned schema, and an `analysis.py` command. Emit byte-exact and
  normalized-content-exact clusters, parameterized shingle candidates, full-Jaccard confirmation,
  denominators, cluster distributions, bootstrap intervals, and complete provenance.
- [x] Refactor Enron acquisition around one pure staged iterator (raw member → parsed body → eligible body
  → retained post-dedup document). Preserve current fetch bytes/semantics and measure pre-filter stages
  separately; never label source-body statistics as production Tika extraction.
- [x] Generate a deterministic stratified review packet. Pre-register normalization, shingles, threshold
  sweep, seed, and clustering; keep near-duplicate verdict `UNDECIDED` until calibration/holdout labels exist.
- [x] Register/regenerate the jseval command inventory and update canonical jseval documentation using the
  docs-maintenance workflow.

Historical execution/verification: [E4 — P4 analyzer execution](897-evidence/execution-history.md#e4).

### P5 — Collision-safe result identity and non-redundancy projection

- [x] Capture a run-local sidecar from raw delivered responses before legacy `resolve_doc_id` stem
  normalization. Use opaque run-local ids and one-to-one reconciliation; do not change BEIR/qrel ids.
- [x] Extend `staged_recall_accounting` with an optional schema-versioned `result_redundancy` section over
  delivered `predictedDocIds`. Existing recall outputs and score-sorted TREC semantics stay unchanged.
- [x] Test same leaf names in different directories/cross-format copies, absent/ambiguous sidecar entries,
  delivered-order top-10 accounting, and schema rejection.

Historical execution/verification: [E5 — P5 identity execution](897-evidence/execution-history.md#e5).

### P6 — Production extracted-content snapshot

- [x] Select and prove a Worker-owned export path; Head/Python never reads Lucene. Enumerate collision-safe
  ids, page complete stored content, reconcile expected/exported counts and ids to the strict manifest and
  ingest state, and stamp the uncommitted snapshot with build/parser/policy identity.
- [x] Do not rely on folder-browse defaults/direct-child pagination. Until this slice is green, P4 may report
  raw-byte/source-body results only—not production content-exact or near-duplicate prevalence.
- [x] For private roots, persist no paths, path hashes, or text; only aggregate statistics, content
  fingerprints, and run-local opaque ids may escape the local scratch area.

Historical execution/verification: [E6 — P6 snapshot and sibling corpus execution](897-evidence/execution-history.md#e6).

### P7 — Corpus execution, calibration, report, and handoff

P7 analyzer preregistration (frozen before corpus output inspection): locale-neutral normalization;
five-token shingles; fixed 64-bit SimHash; Hamming radius 3 with four disjoint bands; full-shingle Jaccard
threshold sweep `[0.50, 0.60, 0.70, 0.80, 0.90]`; exhaustive stratified slice size 620; 2,000 bootstrap
draws; seed 897; and a fail-closed 5,000,000-candidate-pair ceiling. Connected components use the already
implemented deterministic single-linkage rule. The ceiling is a resource/silent-truncation guard, not a
threshold; exceeding it yields no prevalence claim. Real-file production extraction and the Enron
eligible-body proxy use the same analyzer settings, while their source-kind claims remain explicitly
different.

P7 Enron execution recharter (frozen after the resource failure, before any prevalence output): enumerate
the entire canonical archive and retain exact full-stream stage counts plus raw-body and normalized-content
digest censuses, but bound the near-duplicate analyzer to an Algorithm R reservoir of 5,000 eligible-body
occurrences sampled before first-body-SHA retention with seed 897. The artifact must distinguish
`all-eligible-body-occurrences` census scope from `frozen-uniform-eligible-body-sample` analyzer scope.
Archive-level raw/content-exact prevalence may come from the census; sampled content/near-duplicate blocks
are descriptive of the frozen sample only, and archive-population near-duplicate prevalence remains
unmeasured. The sample cannot be presented as an unbiased duplicate-prevalence estimator because unsampled
mates disappear. This supersedes only unbounded v1 full-archive execution, not complete stage enumeration,
the pre-dedup measurement point, or the established post-dedup corpus fetcher.

Historical execution/verification: [E7 — P7 corpus and adjudication execution](897-evidence/execution-history.md#e7).

Worker-blocker takeover recharter (2026-09-04): the user explicitly directed 897 to take ownership of the
repairs needed to finish its production measurements. **GO now on a staged Worker repair; NO-GO on treating
every observed non-ready document as one defect. LITE-CLASS: no.** The sandbox transport mismatch is proven:
the extraction policy permits 10 million characters, the child serializes one whole JSON response, the
parent caps that response at 2 MiB, and the shared frame codec permits 64 MiB. The repair must derive a
worst-case UTF-8-plus-metadata response budget from the effective extraction policy and fail construction if
it cannot fit the protocol ceiling; raising an unrelated magic constant is insufficient. Separately,
structured table annotation can expand content after the SAX character count, while the final clamp currently
runs only when the handler already reported truncation. Add a Unicode-safe post-serialization clamp regression
before treating the four `EXTRACTED_TEXT_TOO_LARGE` files as fixed. Reproduce the single PPT privately and
preserve its exception class before deciding whether it is a product defect or an honest corrupt/unsupported
input. The realdocs VDU backlog was still decreasing and therefore requires completion-time observation, not
a speculative VDU change. The independently proven legal-corpus enrichment stall remains the second repair:
route known over-limit/null or arena-OOM late-chunk outcomes directly into resumable windowing and guarantee
one window unit before the cycle budget can expire. No user decision or manual work is required for this
sequence.

### Rechartered Worker design (2026-09-04)

The two proven blockers stay separate because they have different authorities and failure semantics.

1. **Extraction response authority.** `TikaExtractionPolicy` remains the content/metadata authority and
   `SandboxFrames.MAX_FRAME_BYTES` remains the protocol ceiling. Replace the parent's superseded fixed
   2 MiB default with a response budget derived from the effective policy and the response schema's bounded
   string fields, using six encoded bytes per UTF-16 code unit as the conservative JSON-escape bound plus a
   fixed structural allowance. Construction fails when that bound exceeds the protocol ceiling. The
   package-private explicit-byte constructor remains only as a protocol-failure test seam. This preserves the
   one-frame protocol and does not create a second extraction cap.
2. **Final representation authority.** The SAX counter limits parser-emitted characters, not annotated output:
   table triplet serialization can repeat row/column labels after that count. Clamp the final structured
   `ExtractionResult.content` against the same policy after annotation regardless of the handler's truncation
   flag, do not split a UTF-16 surrogate pair, and promote the artifact to `SUCCESS_PARTIAL`. This supersedes
   the conditional-only clamp that could emit an over-policy `SUCCESS_FULL` artifact.
3. **Resumable fallback authority.** `WindowedEmbedProgress` remains the in-memory authority for partial
   long-document vectors. A late-chunking parent with matching partial progress bypasses the whole-document
   single-pass probe on later cycles and enters the window lane directly. On the first null/arena-OOM
   fallback, the pre-window stop/share checks use actual window progress—not the already-spent single-pass
   unit—so at least one real window slice is recorded. Subsequent checks retain the existing cycle/share
   behavior and SPLADE/NER reservation. This supersedes repeated single-pass tokenization/inference attempts
   that left the progress map empty.
4. **Measurement population.** Product fixes do not silently prune the corpus. The PPT is reproduced against
   the private pinned source and classified from its exception. If it is a genuine parser defect, repair and
   regress it; if it is corrupt/unsupported input, record it as an explicit terminal source failure and adapt
   the measurement only with a reconciled source/analyzable/excluded denominator—never by deleting it or
   pretending 619 extracted documents equal the 620-file source population. The decreasing VDU queue gets
   an observation/wait, not speculative code.

Design reach: whenever one component produces a bounded representation for another, the producer's maximum
valid payload must fit a consumer budget derived from the same authority. Any transformation that can expand
content must re-apply that authority after the transformation. A time-budgeted resumable scheduler must pay
for at least one resumable unit after a non-resumable classification/probe; activity that cannot survive the
cycle boundary is not sufficient progress.

Historical execution/verification: [E8 — Worker repair investigation record](897-evidence/execution-history.md#e8).

### Rechartered implementation plan (2026-09-04)

- [x] Add a policy-derived sandbox response-budget calculation, fail-fast protocol-ceiling validation, and
  real child-process regressions for ASCII and multibyte responses above the former 2 MiB ceiling plus an
  incompatible-policy rejection test. Keep the explicit small-ceiling test seam.
- [x] Apply a Unicode-safe final structured-content clamp, mark it partial, and add table-expansion and
  surrogate-boundary regressions. Confirm partial artifacts still validate and index through existing paths.
- [x] Route matching partial late-chunking parents directly to windowing; guarantee the fallback's first real
  window before stop/share; add deterministic null-fallback and arena-OOM/resume tests that also preserve
  SPLADE/NER scheduling and one bundled write.
- [x] Run focused extraction and enrichment tests, then the affected Worker module suite and compile gate.
  If a check fails, triage the cause rather than weakening the regression.
- [x] Reproduce the pinned PPT privately and retain exception class/stack evidence without committing source
  bytes or paths. Repair only a proven product defect; otherwise add a strict, aggregate denominator contract
  for terminal source failures and its jseval tests.
- [ ] Rerun `mixed/realdocs-v1` to VDU quiescence under a full inference-capable stack. Its extraction rerun is
  complete, but the eval backend cannot activate inference and correctly left the production aggregate
  unmeasured. `mixed/legal-clerc-200` enrichment and strict production capture are complete; query-result
  decoration was not recovered from the final integrated run.
- [x] Update the inference runtime register and any 897/jseval canonical documentation required by shipped
  behavior, run docs regeneration/checks, full scoped verification, `git diff --check`, and an independent
  refute-first review. Remove no superseded corpus/config/baseline; the only teardown is the fixed 2 MiB
  production default and repeated-probe behavior. Do not push, merge, publish, or change baselines.

- [x] Acquire only provenance-cleared, cache-backed real-input samples whose actual producer/source counts
  are reported. Keep the single-source EML/RTF/ZIP cohort and unavailable families explicitly
  deterministic-only; `.msg` remains optional. A future representative robustness claim still requires
  source-diverse samples under §C.
- [ ] Run corpus prevalence on immutable `realdocs-v1` and the pre-dedup Enron stages. Run query-visible
  redundancy on `mixed/legal-clerc-200` only if its identity, source terms, and query/qrel provenance pass;
  otherwise record it unmeasured.
- [x] Obtain disjoint model-assisted calibration/holdout labels; measure approximate candidate recall against
  exhaustive all-pairs Jaccard on a tractable slice. Do not tune and validate on the same labels, and retain
  the weaker-than-independent-human labeling limitation.
- [ ] Fill §M from content-addressed artifacts. Update 639 only with non-redundancy evidence and the
  evidence-permitted recommendation; ANN recall remains in 639. Update the search-quality register row
  only after artifacts exist.

### Teardown and verification closure

- Keep `OfficeMarkerSearchabilityTest`: it remains a narrower flat-extractor/direct-index lexical contract
  and is not the 897 acceptance authority. Do not create another test with that same narrow scope.
- Once exact P1 assertions subsume the console-only DOCX/XLSX/HTML observations in
  `StructuredExtractionIntegrationTest`, fold or delete only those assertions in the same change; retain
  PDF-specific coverage and any non-overlapping flat-vs-structured comparison.
- No corpus/config/baseline teardown occurs in P1–P6. `realdocs-v1`, current Enron fetch outputs, legacy
  corpus signatures, BEIR ids, and existing recall fields are compatibility contracts.
- Final verification: focused Worker/jseval/system tests, affected module suites, `build -x test`, command
  inventory/governance checks, docs-maintenance checks for canonical docs, `git diff --check`, and mandatory
  refute-first review. No PR, merge, or publication without explicit authorization.

## Revised acceptance contract

- [ ] Deterministic fixture recipes and expected-state manifest cover every non-optional matrix row;
  all committed binary assets have per-file provenance/license treatment and SHA-256.
- [ ] Production-path tests assert admission → extraction policy/route → index → keyword search. No
  `TestDocumentBuilder`, direct Lucene write, or console-only boolean is accepted as this proof.
- [ ] Structure-sensitive cases assert summary counts and exact annotated fragments independently.
- [ ] ZIP/email embedded-content behavior records both marker survival and whether resource identity is
  preserved; these are not collapsed into one pass/fail bit.
- [ ] Representative sources are recipe/cache backed. Materialized bulk corpora remain uncommitted.
- [ ] Every raw-file measurement is bound to a canonical `(relative path, size, SHA-256)` manifest digest
  and extraction-policy identity; a null or qrels-only corpus signature fails closed.
- [ ] New jseval command has deterministic unit fixtures, schema validation, corpus identity, algorithm
  version/normalization/seed/threshold provenance, and fails closed on missing extraction inputs.
- [x] Near-duplicate thresholds have a labeled calibration sample with precision/recall (or an explicit
  non-decision if labels are insufficient), a disjoint holdout, and an exhaustive candidate-recall check on
  a tractable slice; SimHash Hamming distance is not treated as truth.
- [ ] Ranked-result joins use a collision-safe document key and fingerprint sidecar and fail closed on
  ambiguous/missing identities; leaf filename stems are forbidden as the join authority.
- [ ] Any production-content snapshot reconciles its exported count and opaque ids to the expected manifest
  and ingest state; folder-browse pagination/direct-child behavior cannot silently omit nested documents.
- [ ] §M reports per-format capability and per-corpus prevalence with denominators/intervals; synthetic,
  pre-deduplicated, and private cohorts are visibly separated.
- [ ] Real-format characterization reports per-format source diversity; unsupported source coverage is
  labeled rather than generalized from one generated producer.
- [ ] 639 receives only the non-redundancy evidence and a recommendation allowed by the evidence gate;
  ANN recall remains in 639. The search-quality register gains a “format breadth / duplicate prevalence”
  row. No baseline changes.
- [ ] Focused Worker and jseval tests pass; if a corpus test exceeds 60 seconds, tag/document it in
  `docs/explanation/09-testing-strategy.md` and run the repository’s docs-maintenance sequence.
- [ ] Refute-first review verifies that every claimed production layer is actually exercised and that
  the measurement cannot pass on a filtered/deduplicated input by mistake.

Historical execution/verification: [E9 — Historical takeover verification and confidence](897-evidence/execution-history.md#e9).

## §M — measurement results

Partial, from checked deterministic state and content-addressed aggregate artifacts. Missing production rows
remain visibly unmeasured rather than being inferred from proxies; the completed near-duplicate row is
explicitly model-assisted rather than described as independent human ground truth.

### M.1 Format capability

| Case | n | admitted | production marker found | structure/identity result | failure class | artifact |
|---|---:|---:|---:|---|---|---|
| EML multipart + attachment | 1 | 1 | 1 | body/attachment content survives; subject/embedded identity not preserved | `EMBEDDED_CONTENT_OR_IDENTITY_LOSS` | `expected-state.v1.json` SHA-256 `7eec7a9e…77d8b` |
| MBOX two messages + attachment | 1 | 1 | 1 | bodies/attachment survive; subjects absent and embedded identity not preserved | `MIME_OR_PARSER_UNSUPPORTED`; `EMBEDDED_CONTENT_OR_IDENTITY_LOSS` | same deterministic oracle |
| RTF | 1 | 1 | 1 | exact text contract passes | `NONE` | same deterministic oracle |
| EPUB | 1 | 1 | 1 | heading/list counts and annotated text pass | `NONE` | same deterministic oracle |
| ODT | 1 | 1 | 1 | heading/list/table counts and annotated text pass | `NONE` | same deterministic oracle |
| XLSX ordinary | 1 | 1 | 1 | table/heading and annotated text pass | `NONE` | same deterministic oracle |
| XLSX merged headers | 1 | 1 | 1 | searchable markers survive; merged semantics flatten | `STRUCTURE_FLATTENED` | same deterministic oracle |
| XLSX typed cells | 1 | 1 | 1 | searchable values survive; typed/formula semantics flatten | `STRUCTURE_FLATTENED` | same deterministic oracle |
| PPTX speaker notes | 1 | 1 | 1 | slide and speaker-note markers survive | `NONE` | same deterministic oracle |
| ZIP with TXT + XLSX | 1 | 1 | 1 | both markers survive; only rendered labels preserve child identity | `EMBEDDED_CONTENT_OR_IDENTITY_LOSS` | same deterministic oracle |

### M.2 Duplicate prevalence and result-set redundancy

| Cohort | n docs | byte exact | content exact | calibrated near-dup | version hint confirmed | queries / redundant@10 | artifact |
|---|---:|---:|---:|---:|---:|---|---|
| CMU Enron eligible-body proxy — full exact census | 352,208 | 77.009% | 77.626% | unmeasured | n/a | no query set | `scripts/jseval/897-run-2026-09-04/enron-duplicate-prevalence.v1.json`; SHA-256 `4ebf994d…e492c` |
| CMU Enron — frozen uniform analyzer sample | 5,000 | 4.46% descriptive | 4.54% descriptive | threshold `0.90`; model-assisted holdout precision `1.0`, recall `0.985507`, F1 `0.992701`; archive prevalence unmeasured | n/a | no query set | census artifact plus ignored decision hash `66fcd1e8…10a907` |
| `mixed/realdocs-v1` production extraction | 620 source / 619 indexed / 1 exact terminal exclusion | unmeasured | unmeasured | unmeasured | n/a | ingest-only | extraction defects fixed; inference-disabled eval backend left 121 VDU pending, so no aggregate |
| `mixed/legal-clerc-200` production extraction | 199 | 0.0% | 0.0% | `UNDECIDED`; sweep observed no confirmed edge | unmeasured | 200 / unmeasured | ignored aggregate SHA-256 `cd410c97…af8ac7`; canonical hash `7312e6cc…b19131` |

## Historical close state (2026-09-04; current state is above)

Historical execution/verification: [E10 — Historical closeout verification](897-evidence/execution-history.md#e10).

**BLOCKED ON YOU**

- Nothing remains for the user in the 897 labeling or threshold-selection workflow. The sole uncertain pair
  was labeled `NOT_NEAR_DUPLICATE`; calibration-only selection and one-time holdout evaluation are complete.
  The user has now explicitly authorized taking ownership of the Worker repairs needed before the two
  production measurements can be rerun; no further scope decision is required.

**PROCEEDING / DONE**

- Done: takeover investigation, internet research, codebase/adjacent-work reconciliation, revised design,
  supersession list, reach analysis, risks, confidence rating, explicit go verdict, and the committed
  seven-slice implementation plan.
- Done: P1 deterministic fixture/oracle slice (ten rows total): EML/MBOX/EPUB/ODT,
  ordinary/merged/typed XLSX, PPTX speaker notes, and nested ZIP, with real-MIME AUTO route selection,
  exact annotated output, locale regression, EPUB package
  conformance checks, strict checked JSON projection, and explicit embedded/subject/flattening gap
  classifications.
- Done: P2 installed-Worker matrix plus shipped-default traversal-policy acceptance.
- Done: P3 strict manifest primitive and one-object propagation through raw cache, ingestion, counting,
  and provenance, plus the separate 33-file `mixed/format-breadth-v1` recipe, reviewed observed manifest,
  cache-backed materializer, and zero-overlap check against immutable `realdocs-v1`.
- Done: P4–P6 duplicate analyzer, pre-dedup Enron projection, review-packet and identity chain,
  collision-safe result redundancy, Worker-owned production snapshot, in-process private run join, and
  independent refute-first P6 closure.
- Done: P7 full-archive Enron exact census plus deterministic bounded sample, schema-checked committed
  aggregate, production `realdocs-v1` fail-closed characterization and post-repair extraction rerun,
  legal-corpus provenance gate, converged enrichment, strict 199-document production snapshot, and
  independent refute-first closure.
- Done: review-packet CLI emission reuses the same private observation capture and binds v2 sample scope;
  the emitted text-bearing packet is mechanically restricted to the gitignored `scripts/jseval/tmp/` root
  and cannot share the aggregate destination.
- Done: private blinded human-labeling UI, text-free provenance-bound autosave state, deterministic resume, and
  fail-closed label-state validation.
- Done: conservative blinded model triage, text-free before/after provenance binding, preservation of existing
  human judgments, and uncertainty-only GUI queue. The one uncertain judgment is complete.
- Done: frozen calibration-only threshold selection, one-time holdout evaluation, aggregate-only decision
  artifact, conditional bootstrap uncertainty, and refute-first scoring review.
- Done: Worker sandbox response-budget/post-annotation clamp repair, evidence-driven PPT classification,
  resumable late-chunk scheduling repair, denominator-aware production adapter, and legal production capture.
- The former evidence-only continuation is superseded by the 2026-09-06 reassessment above.
  Current-main integration, measurement-scope reconciliation, fresh production evidence, and regression
  verification remain; the removed VDU checkout is not a resumable live stack.
- Not authorized: PR, merge, publication, or baseline changes.
