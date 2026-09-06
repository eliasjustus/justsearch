# 897 — historical execution and investigation evidence

Preserved verbatim from `380eb7ad19e83f539326849b92590ee0fb4e2a02` during the 2026-09-06 reassessment. These dated records do not certify current-main integration. The main tempdoc retains the charter, design, task checklists, acceptance contract, measurement table, and current verdict.

<a id="e1"></a>

## E1 — P1 fixture execution

P1 expansion evidence (2026-09-03): ten generated rows now pass the production extractor oracle and
real-MIME routing classifier: EML, two-message MBOX, RTF, conforming EPUB 3, structured ODT, ordinary XLSX,
merged/multi-row XLSX, numeric/date/formula XLSX, PPTX-with-speaker-notes, and
ZIP-with-text/nested-XLSX. Exact hashes, annotated text, MIME, policy/adapter ids, structure counts,
required/absent markers, and machine-readable failure classes are pinned. The typed XLSX remains exact
under an explicit German format locale; the EPUB `mimetype` local header is first, STORED, and has no
extra field. Mail/ZIP embedded accounting, MBOX subject omission, and flattened spreadsheet semantics
are recorded as `KNOWN_GAP`, not passes.

P1 closure evidence (2026-09-03): the PPTX recipe is generated through POI, normalized into a sorted
STORED archive, reopened through POI to validate the slide/notes relationship graph, and pinned at
`36b0eb0b1768e684b8b0128bf35c6441c71106307852740330548c7c4710985e`. POI emitted one locale-derived
end-paragraph language attribute in each owned slide and notes entry; the fixture rewrite now requires
exactly those two sites and canonicalizes them to `en-US`. Default, `de-DE`, and an independent
`ja-JP`/`Pacific/Kiritimati` run preserve the same hash and exact extracted text. The checked JSON file is
an explicitly labeled projection of the typed Java oracle; its test rejects unknown/missing fields,
missing/extra/duplicate rows, duplicate keys/classes, and incorrect object/array container shapes.

Verification:

```text
./gradlew.bat :modules:worker-services:test --tests "*PolicyDrivenFormatCapabilityTest" --tests "*ExtractionRoutingTest"
```


<a id="e2"></a>

## E2 — P2 production and traversal execution

P2 matrix evidence (2026-09-03): one installed Worker accepted and indexed all ten generated rows. Each
row was selected by its unique marker plus exact normalized stored path, then paged through
`FetchDocumentSlice` to a stable declared UTF-16 length. The test asserts exact annotated content, MIME,
the complete structured-count object in `visual_extraction_evidence`, and one privacy-safe ledger event
with `SUCCESS_FULL` outcome/artifact status plus the pinned policy/parser ids. A scheduled MMF heartbeat
keeps the Worker lease alive during convergence and teardown is exception-safe. The focused opt-in system
test passed after independent refute-first review; P1's real-MIME routing test remains the complementary
isolation proof because AUTO may fall back to in-process at runtime.

P2 traversal evidence (2026-09-03): `SyncDirectoryIntegrationTest` now builds a representative miniature
vault/source tree. Exactly seven Markdown/Java/TypeScript/Python/JSON/JavaScript files are admitted and
searchable, including `.obsidian`, `build`, and `dist` content; `.git`, `node_modules`, `__pycache__`,
`.class`, and `.pyc` markers remain absent. The Worker test harness can remove inherited environment
keys, so this test launches with all three ingestion-skip override keys absent and exercises shipped
defaults. The full ten-test class passed both normally and when the Gradle test JVM inherited hostile
skip overrides. `verify-test-evidence-policy.mjs` remained green with no catalog edit.

Verification:

```text
./gradlew.bat :modules:system-tests:systemTest -PincludeSystemTests=true --tests "*FormatCapabilityMatrixE2ETest"
./gradlew.bat :modules:system-tests:systemTest -PincludeSystemTests=true --tests "*SyncDirectoryIntegrationTest"
```


<a id="e3"></a>

## E3 — P3 manifest execution

P3 manifest-primitive evidence (2026-09-03): `raw_corpus_manifest.py` is a fail-closed sibling of the
legacy best-effort signature. It builds an immutable canonical-JSON identity over the complete raw tree,
streams every SHA-256, rejects ambiguous/non-portable paths and non-regular links, and detects both
file-set and same-path stat drift across the final rescan. An explicit compatibility projection accepts
the checked `realdocs-v1` manifest shape without weakening the strict schema. The focused suite pins the
digest and root-relocation invariance and exercises missing/extra/duplicate/escaping/case-folding/Unicode,
schema/type/hash/size, symlink/reparse, and injected TOCTOU refusal paths. Independent refute-first review
found a lone-surrogate exception leak; UTF-8 validation and validator/projection regressions closed it.
Final focused verification: 50 passed, 4 platform-capability skips on Windows.

P3 propagation evidence (2026-09-03): one `RawCorpusContext` is resolved before backend startup and
passed unchanged through selector nomination/adoption, ingestion, normal and ingest-only run summaries,
run manifests, and the post-stop publication gate. Declared strict manifests are validated directly;
the immutable `realdocs-v1` shape crosses only the explicit legacy projection; absent pointers build a
current strict manifest. Manifest count is the raw ingest target count, and selector, ingest summary,
run summary, and run manifest carry the same digest/count/schema. Missing/invalid/escaping/absolute
manifest pointers, alternate explicit roots, byte/name/add/delete drift, and absent-or-equal-only
signature overrides fail closed. The existing best-effort `corpus_signature` and non-raw paths remain
conditional and unchanged.

Independent refute-first review found four bypasses in the first propagation draft. The final design
also binds the Worker's effective three-set admission policy into raw cache and run identity, rejects
historical selector pins for strict raw runs, validates supplied contexts against the requested dataset
and explicit root even on adopted/skip-ingest paths, and permits only contained repo-relative POSIX
manifest pointers. Post-fix focused verification passed 112 tests with 4 Windows capability skips plus
114 backend/cache/run/manifest tests; Python compilation and `git diff --check` are green.


<a id="e4"></a>

## E4 — P4 analyzer execution

P4 Enron-stage evidence (2026-09-03): `iter_enron_source_stages` now exposes the complete public-source
body pipeline before reservoir sampling. The planted six-member tar yields exactly 6 raw, 6 parsed, 5
eligible, and 4 retained events; the duplicate links to the first sorted source member. The production
fetcher consumes only retained events, records aggregate stage counts, and preserves the prior UTF-8
replacement/header split, body trim, word floor, SHA-first dedup, candidate ordering, reservoir RNG call
boundary, final id sort, raw-tar signature, shared-cache reuse, and exact emitted document bytes. Focused
verification passed 17 tests. Independent refute-first review found no P0–P2 issue and confirmed that no
member names, bodies, or hashes are newly persisted in provenance.

P4 analyzer-core evidence (2026-09-03): `duplicate_prevalence.py` now keeps byte-exact,
normalized-content-exact, and near-duplicate analysis separate. It pins NFC/whitespace normalization,
locale-neutral alphanumeric tokenization, tagged short-document shingles, fixed 64-bit SimHash with an
`h+1`-band Hamming guarantee, full-set Jaccard confirmation, single-linkage components, deterministic
component-resampling stability intervals, and a format/length/exact-status exhaustive slice that measures
candidate recall rather than assuming it. Empty extracted content is excluded with explicit denominators;
candidate explosion fails instead of truncating; the near verdict remains `UNDECIDED`. Production content
requires the exact supported snapshot schema, strict raw-manifest signature, full ordered-observation
commitment, self-consistent metadata digest, and count/opaque-id reconciliation. Source-body proxy results
retain the same privacy-safe observation commitment without pretending archive file count equals message
count. Outputs contain aggregate counts, distributions, and digests only—no ids, paths, or text. Focused
verification passed 35 tests plus Python compilation and whitespace checks. Independent refute-first review
found and closed threshold-type, count-only provenance, schema-version, malformed-Unicode, proxy-commitment,
and digest-test-efficacy gaps; the final closure review reported no remaining P0–P2 finding. The P4 checkbox
is closed by the schema and command evidence below.

P4 schema/command evidence (2026-09-03): `duplicate-prevalence.v1.schema.json` is closed at the root and
fixed nested objects, constrains both source modes, pins aggregate-only privacy and `UNDECIDED`, and requires
the exact Enron projection policy or a reconciled production snapshot. The flat `duplicate-prevalence`
command accepts strict `jseval.duplicate-prevalence-input.v1` JSON. Its legacy Enron path exhaustively
analyzes the pre-dedup eligible-body proxy; P7 supersedes that unbounded execution mode for full CMU-scale
runs after measuring its resource failure. `production-extracted` now uses the P6 bridge. Its
one-file raw root is bound by the strict P3 manifest before and after analysis. The adapter streams gzip
once to scratch, reads sorted canonical regular tar members without extracting paths, rejects traversal and
duplicate members, counts all four source stages, and in legacy v1 analyzes every eligible body before exact-body retention,
and atomically replaces an aggregate-only artifact. Projection identity now proves body-digest equality,
eligible/success count, retained/distinct-body count, archive signature, observation commitment, min-word
floor, member admission/order, and SHA-first retention. The command inventory was regenerated and checks at
92 commands. Canonical jseval documentation and its generated Claude/Codex skill projections were updated;
llms/skill regeneration checks passed. `docs-validate` reached only its known repository-wide advisory set.
Focused analyzer/schema/CLI/command-surface verification passed 84 tests. Independent review found and
closed invalid-root Unicode, atomic-test efficacy, stage-policy, body/digest, retained-count, schema-drift,
and proxy-binding issues; final closure review reported no remaining P0–P2 finding.

P4 review-packet evidence (2026-09-03): `duplicate_review_packet.py` emits a content-addressed,
explicitly `local-review-text` / `uncommitted-local-only` packet with stable pair ids, review text, null
labels, and no filesystem or archive-member metadata. It revalidates the aggregate artifact hash, exact
analyzer configuration, observation commitment, raw-manifest signature, and the nested Enron projection or
production extraction identity. Documents are partitioned before pair formation by global SimHash-candidate
connected components, keeping candidate families wholly within calibration or holdout and requiring at
least two documents in each. Candidate and noncandidate control frames partition the same deterministic
per-split exhaustive all-pairs universe. Threshold/format/length strata record independently checkable
population and selected counts, inclusion probabilities, and reciprocal weights. The packet cannot select a
threshold or report precision/recall/prevalence; it remains `UNLABELED` / `UNDECIDED`. Focused analyzer and
packet verification passed 55 tests. Independent review found and closed incompatible sampling universes,
nested-identity rebinding, family leakage, and empty-slice validity gaps; final closure review reported no
remaining P0–P2 finding. The flat command now optionally emits this sensitive packet from the same retained
observations used for the aggregate, with explicit quota/fraction/seed controls, different-destination
enforcement, a no-stdout-text regression, and v2 sample-scope binding. The packet remains local and
uncommitted; the pure generator and its acceptance contract are unchanged.


<a id="e5"></a>

## E5 — P5 identity execution

P5 evidence (2026-09-03): every ordinary run now preflights and writes an identity-only
`result_identity.v1.json` before lossy BEIR filename/stem normalization, with random run-local document ids,
strict delivered-position reconciliation, and no persisted raw paths or plain path hashes. Same-leaf files in
different directories and cross-format copies remain distinct while qrel/TREC ids and recall behavior stay
unchanged. `summary.json` independently anchors the sidecar nonce and complete canonical content hash before
artifact publication; identity failure leaves no partial run directory. The optional
`jseval.result-redundancy.v1` projection uses delivered `predictedDocIds[:10]`, separate from score-ranked
TREC recall, and fails closed on schema, order, corpus, analyzer, cluster, anchor, or replay mismatch.

Confirmed content-exact decoration requires production observations, a deterministic re-run of the P4
analyzer, a strict corpus signature, a keyed HMAC binding the private observation/alias catalog, and an
Ed25519 signature over the complete decorated sidecar. Only the HMAC and analyzer-committed verification key
persist; both private keys remain in local scratch. The summary anchor pins the verification key and analyzer
hash, rejecting assignment permutation, signed-pair replacement, and cross-run replay within the stated
run-artifact trust model. Rewriting the entire run directory including its designated summary trust anchor is
outside that model and would require an external signing authority. Near-duplicate clusters remain unavailable
until P7 calibration/holdout labels exist. Normal P5 runs intentionally emit identity-only sidecars; wiring the
Worker-owned P6 export to decorated-sidecar production is the next integration boundary. Focused verification
passed 113 tests; the dependency lock, Python compilation, whitespace check, docs index generation, and skill
projection generation/checks passed. Independent refute-first closure reran 56 tests and reported GO with no
remaining P0–P2 finding.


<a id="e6"></a>

## E6 — P6 snapshot and sibling corpus execution

P6 implementation evidence (2026-09-04): the existing Worker-owned
document-slice RPC now returns stored extraction identity, exact UTF-16 pagination metadata, and a stored
source SHA-256; Head adds only a token-protected, bounded parent-id listing route and never opens Lucene.
The Worker hashes each admitted source immediately before and after extraction, refuses a changed source,
and stores the stable digest through the SSOT field/catalog path. The production adapter reconciles the
strict manifest, complete Worker id set, Head/Worker readiness and quiescence, generation/build/count
identity, every scalar-safe content page, extraction provenance, and every stored digest during capture.
After the query interval it revalidates the raw manifest, readiness/quiescence, generation/build/count
identity, and complete Worker id set; unchanged commit/generation identity attests that the captured stored
digests were not replaced. A real Netty loopback gRPC test crosses protobuf serialization and reconstructs
non-BMP text from multiple pages; focused Java suites and Spotless are green.

The private `jseval run --duplicate-prevalence-input-spec` path is one in-process transaction on an owned
`--start-backend --clean --fresh-index` lifecycle. It rejects cache adoption, a caller-supplied corpus
signature, missing modes/ingestion, mismatched raw roots/base URLs, and skipping the mandatory redundancy
projection. Private paths, text, raw manifest digest, HMAC key, and Ed25519 private key remain only in
repr-suppressed in-memory carriers. Persisted summary, manifest, ingest summary, aggregate analysis, signed
result mapping, anchor, and projection share one keyed corpus commitment; a full run-directory privacy test
rejects the raw root, paths, unkeyed digest, and path-bearing Worker telemetry. The artifact boundary rejects
telemetry mirroring for content-exact runs, and the run orchestrator leaves those spans only in the local
backend scratch directory. The public/default run path remains identity-only and continues to mirror
telemetry normally.

Operational decision: exact extraction binding currently costs two additional sequential full-source reads
for every newly admitted or reindexed file, outside the parser timeout; unchanged files skip before hashing.
That cost is accepted for the P6/P7 measurement lane because it closes same-path/same-count stale-index and
mid-extraction mutation holes. P7 must measure the realdocs ingestion impact; if material, the standing
design must move to an equivalently strong bounded/single-pass binding rather than silently normalizing the
cost. Because legacy index entries lack `source_sha256`, every production snapshot requires a full clean
reindex.

Independent refute-first closure found one real privacy defect: ordinary telemetry mirroring copied Worker
spans whose `doc.path` attribute contained literal private source paths. The repair suppresses telemetry
mirroring in the private orchestrator and independently rejects any content-exact artifact write supplied a
Worker data directory before creating output. Both direct and complete-run regressions plant path-bearing
telemetry and verify refusal/absence plus a scan of every emitted file. The reviewer reran those tests and
the full artifact suite and returned GO with no remaining P0–P2 finding.

P3 sibling-corpus evidence (2026-09-04): `corpus-fetch-format-breadth` materialized the separate
`mixed/format-breadth-v1` single-source-per-format raw corpus through the shared `dataset_cache` without changing the immutable
`realdocs-v1` manifest. Its reviewed observed manifest pins the CMU Enron, Govdocs1, and NapierOne source
hashes plus every selected member: 16 untouched RFC822 messages from 16 sender domains, all nine RTF
members in Govdocs1 archives 000/001, and the first eight sorted nested ZIP members in NapierOne's
`ZIP-DEFLATE-tiny.zip`. The Napier archive SHA-256
`7702aa20914462aa39798c5c4659a2fa53e9ea90cf2cd9274cd433d03dbde8a2` exactly matched its upstream
sidecar. A second ordinary materialization from the observed manifest succeeded, and the selected 33-file
SHA set had zero overlap with the materialized 620-file `realdocs-v1` corpus. Raw source/output bytes remain
gitignored; only the recipe and observed manifest are repository artifacts. The recipe reports a
conservative producer/source-collection count of one for each covered format. This is real-input
deterministic production-path characterization, not the source-diverse representative robustness arm
required by §C.

The final refute-first review initially returned NO-GO on four fail-closed/claim issues: arbitrary review
packet destinations, an overlap check not bound to the immutable realdocs manifest, permissive first-run
manifest status handling, and source-diversity overclaim. The implementation now resolves and restricts
text-bearing packets to `scripts/jseval/tmp/` before analysis; projects and validates the complete legacy
realdocs manifest before comparing hashes; accepts only the exact closed unobserved stub or an observed
manifest; and reports a conservative one-source/producer count for each covered format. The reviewer reran
the 105-test focused suite, audited all 33 selected hashes against the observed manifest and all 620
realdocs files against their manifest, and returned GO with no remaining P0–P2 findings.


<a id="e7"></a>

## E7 — P7 corpus and adjudication execution

P7 live evidence (2026-09-04): `mixed/realdocs-v1` materialized all 620 pinned files (807 MB; 80 DOC,
50 DOCX, 311 PDF, 40 PPT, 30 PPTX, 60 XLS, 49 XLSX) and matched its committed manifest. A clean production
run on `5433890a` (`078f567a-db32-437e-8d42-da12c9433a6c`) ran about 24.8 minutes before stand-down and
honestly stopped at 597 indexed, 23 retry-backoff jobs, five terminal failures, and 115 pending visual
documents. Log/database forensics show that all 23 pending jobs failed both attempts on the sandbox
protocol: the parent accepts a hard 2 MiB response frame while extraction permits 10 million characters
and the child emits the whole JSON result. The terminal set is four `EXTRACTED_TEXT_TOO_LARGE` results plus
one PPT that crashes both structured and flat Tika/POI paths; the VDU count was slow but still decreasing.
The run also indexed main-checkout path identities while the source spec named the worktree corpus, which
would fail exact reconciliation even after readiness. The strict production adapter refused `/api/health`
before paging, so no realdocs duplicate-prevalence claim exists; the corpus was not pruned, switched to
in-process extraction, or allowed through relaxed readiness to manufacture one. Two complete
SHA-256 passes over all 620 source files took 1.388 seconds (1,108.6 MiB/s combined) on the same host, under
0.1% of the observed run time; the P6 source-binding reads are not the measured bottleneck. The legacy
full-Enron v1 pass was interrupted safely after reaching roughly 23 GB RSS on a 31.7 GB host without an
artifact, motivating the bounded census-plus-sample recharter above.

Post-repair production evidence (2026-09-04): a clean `mixed/realdocs-v1` rerun admitted 619 of the 620
manifest files. The four documents that previously failed as `EXTRACTED_TEXT_TOO_LARGE` extracted
successfully, directly exercising the policy-derived response budget. The remaining legacy PPT fails both
structured and flat Tika/POI parsing with the same `TikaException` rooted in
`ArrayIndexOutOfBoundsException`; it is therefore recorded—not hidden—as one exact terminal
`corrupt-or-unsupported-parser-input` source disposition. The eval backend cannot activate the local
inference runtime, so 121 visual-document updates remained pending and the strict adapter correctly emitted
no realdocs aggregate. That is an environment/evidence limitation, not an extraction-count mismatch.

The repaired late-chunk scheduler converged in four independent clean `mixed/legal-clerc-200` ingests: all
199 parents reached embedding, all 4,122 long-document chunk vectors reached completion, and SPLADE/NER
completed in roughly 241–273 seconds. A strict production snapshot then reconciled all 199 source files and
199 extraction observations with no failures or partial results. It measured zero byte-exact and zero
content-exact duplicate documents; near-duplicate threshold selection remains `UNDECIDED` as designed. The
ignored aggregate has SHA-256 `cd410c97940b4228d78e2be100fa621dcdff87b03878277742181e9d20af8ac7`
and canonical artifact hash `7312e6cc6e62aa571d4aa2bec1fd6ae318f0ac0b3370366cd51280d158b19131`.
The live adapter fixes found during these reruns project the real nested Worker debug envelope, accept only
the exact aggregate degradation caused by `inference.offline` while Head and Worker are ready, and wait a
bounded interval for final parent-id publication before the unchanged strict before/after capture checks.

The rechartered v2 Enron command then completed the full CMU archive in about one minute while remaining
below 1 GB observed working set. It enumerated 517,401 raw/parsed members, 352,208 eligible occurrences,
and 168,860 first-SHA-retained bodies; the deterministic 5,000-occurrence sample had inclusion probability
0.014196. The full census measured raw-body-exact prevalence 77.009% (271,231 duplicate occurrences) and
normalized-content-exact prevalence 77.626% (273,406 duplicate occurrences). These are source-body-proxy
census results, not Worker/Tika results. The frozen sample measured 4.46% raw exact and 4.54% content exact,
which demonstrates the expected mate-sampling bias and is not an archive prevalence estimate. Full-archive
near-duplicate prevalence remains explicitly unmeasured. Three complete runs were byte-identical at file
SHA-256 `4ebf994df979d4db2fe746fc6ecac3102eb56271a3769401fdc10a7e18ce492c`; the committed artifact's
internal canonical hash is `fe7587562421fe81488512e78313ea361cb6000e10e3eaa45442fbd0ca4141e7`.
The final refute-first pass first found and caused closure of two contract defects—the callable reservoir
ceiling exceeded 5,000, and sample-only scope labels were not forbidden on v1/production artifacts—then
returned GO with no remaining P0–P2 findings after fresh focused verification.

The new single-pass review option produced an ignored local `jseval.duplicate-review-packet.v1` bound to
the exact same Enron aggregate. Its aggregate companion was byte-identical to the committed census. The
4,055,944-byte packet has canonical hash
`62f4b3d38c8e5008f567dcf67efe0def3c2a45b377c809dbca98587982738ebb`, 69 unlabeled pairs (36 calibration,
33 holdout; 22 candidate-frame and 47 exhaustive-control), and confirms that candidate-connected families
do not cross the split. It remains at `scripts/jseval/tmp/897-p7/enron-review-packet.local.json`, which is
gitignored and must not be committed or published.

The human-review handoff is a deliberately narrow local annotation seam, not a general labeling platform.
`duplicate-review-label` opens a Tkinter-only window over the packet and starts no HTTP service. Pair order and
left/right orientation are deterministic packet-bound hashes, while split, sampling frame, similarity, stratum,
format, token count, and opaque ids stay hidden. The four judgments are `NEAR_DUPLICATE`,
`NOT_NEAR_DUPLICATE`, `UNCERTAIN`, and `ABSTAIN`. Each click atomically autosaves a separate strict,
text-free `jseval.duplicate-review-labels.v1` artifact under `scripts/jseval/tmp/`; reopening resumes at the
first unlabeled pair. Packet/analyzer hashes, exact pair population/order, vocabulary, and the label artifact's
own canonical hash are revalidated before any text is shown. Scoring remains a subsequent step: select a
threshold on calibration labels only, freeze it, then evaluate the disjoint holdout, with uncertain/abstained
counts reported rather than coerced to binary labels.

Labeling-workflow verification (2026-09-04): the real private packet validated as 69 records with zero labels
and resumed at pair 1; its new state contained 69 rows, zero forbidden metadata keys, and a valid canonical
hash. The focused Worker-adjacent/jseval regression set passed 263 tests with four expected Windows skips, and
Tk display initialization succeeded. Independent refute-first review found one P2 usability defect—the window
initially named but did not define both binary judgments. The shared on-screen instructions now define all four
judgments exactly, a GUI-surface regression locks them, and the reviewer returned GO after an independent
9-test rerun and compile check. A privacy comparison found no full or 80-character packet-text match in the
changed implementation, test, or documentation files.

The initial all-human workflow was rechartered after user review showed that forcing manual adjudication of
obvious pairs added work without adding useful evidence. The replacement keeps the experimental blinding but
uses conservative model triage for only high-confidence binary cases. Twelve already-saved human judgments
were preserved; of the remaining 57 pairs, blinded Codex triage auto-labeled 56 and left one for human review.
A text-free `jseval.duplicate-review-model-triage.v1` sidecar binds the packet/analyzer identity, every triage
disposition, and the exact before/after label-state hashes. The GUI accepts that sidecar and displays only its
human-review queue. This campaign must be reported as model-assisted adjudication, not as 69 independent human
labels; downstream calibration/holdout statistics must retain the sidecar as provenance.

Triage-workflow verification (2026-09-04): the focused regression set passed 266 tests with four expected
Windows skips; the pre-review state revalidated as 68 labeled rows and a one-pair human queue. Refute-first review
found and closed two issues before launch: human autosave originally changed the current hash that strict
sidecar validation expected, and interruption between the two provenance writes could strand a non-retryable
sidecar. Validation now reconstructs and verifies both frozen pre/post-triage states while permitting only
`HUMAN_REVIEW` rows to evolve. An identical prewritten sidecar is an idempotent recovery point, with best-effort
rollback after a failed labels write. Independent closure reran 12 focused tests, including human-label resume
and injected second-write plus cleanup failure, and returned GO with the real one-pair queue still valid.

Decision scoring rule frozen after label completion but before inspecting any calibration/holdout metric:
a predicted positive must be in the SimHash candidate frame and meet the candidate pair's full-shingle Jaccard
threshold. For each preregistered threshold, Horvitz–Thompson sampling weights form the binary confusion totals
over candidate and exhaustive-control frames. Select the threshold with maximum weighted calibration F1;
deterministic ties prefer higher weighted precision and then the higher threshold. Apply that one frozen
threshold to holdout exactly once. Report weighted precision, recall, and F1 plus raw confusion counts and
deterministic 2,000-draw within-stratum bootstrap percentile intervals. `UNCERTAIN` and `ABSTAIN` rows are
excluded from binary metrics and reported separately. Intervals describe review-sample uncertainty conditional
on these model-assisted labels; they do not measure model-label error or archive-population prevalence.

Decision evidence (2026-09-04): the user labeled the sole `HUMAN_REVIEW` pair
`NOT_NEAR_DUPLICATE`, completing all 69 rows with 12 preserved human judgments, 56 blinded model-auto
judgments, and one post-triage human judgment. All five preregistered thresholds tied on weighted calibration
F1 `0.976`, precision `1.0`, and recall `0.953125`; the frozen tie-break therefore selected `0.90`.
Applied once to the 33-pair holdout, that threshold produced raw confusion `TP=2, FP=0, TN=30, FN=1` and
weighted precision `1.0`, recall `0.985507`, and F1 `0.992701`. The deterministic 2,000-draw within-stratum
bootstrap had no undefined draws and returned conditional 95% percentile intervals: precision
`[1.0, 1.0]`, recall `[0.971429, 1.0]`, and F1 `[0.985507, 1.0]`. The aggregate-only local decision artifact
has canonical hash `66fcd1e8f433c82d89af34a77a4e645c00ad9c8b346efc58b78b20ef3610a907` and remains gitignored with its
packet, labels, and triage provenance. These figures characterize the labeled review sample conditional on
model-assisted adjudication. They do not estimate full-archive near-duplicate prevalence or independent
human-label accuracy.

Scoring verification (2026-09-04): 159 focused duplicate-analysis tests passed. Refute-first review initially
returned NO-GO because an output collision could overwrite an input or prior differing decision and because
undefined bootstrap draws were silently omitted. The writer now rejects all input collisions, permits only
content-identical idempotent reuse of an existing decision, and refuses differing or unreadable outputs. The
bootstrap reports requested, valid, and invalid draws and emits no bounds if any planned draw is undefined.
The same reviewer reran the scoring/command checks and returned GO with no remaining P0–P3 finding before
the real metrics were inspected.

A final full-diff review then found one further P2 idempotence edge: parsed Python equality treats JSON
`1`, `1.0`, and `true` as equal, so type-corrupted existing output with a stale self-hash could be accepted as
unchanged. Existing decisions now require a valid recomputed self-hash and byte-identical canonical JSON,
including types. The focused regression reproduces that corruption, and the expanded 897 suite passes 271
tests with four expected Windows capability skips.

The `mixed/legal-clerc-200` provenance gate passes locally: its pinned recipe/source revision and raw-source
signature agree with the materialized metadata; 200 nonempty citation-retrieval queries carry 200 evidence
references to 198 unique corpus documents with no missing ids. This licenses a query-visible run but is not
itself redundancy evidence. After the abandoned shared stack became safely reclaimable, an ignored local
raw projection preserved those query/qrel files and marked its own 199-file ingest root (198 opinions plus
the required sentinel) for the strict P6 identity path. The four-mode clean production run indexed all 199
files without failures, then became structurally non-convergent during long-document enrichment: parent
embedding stopped at 109/199, SPLADE at 114/199, NER at 89/199, and chunk vectors at 0/4,122 while repeated
cycles reported zero stage advancement and rewrote the same batch. The owned backend was stopped through
the registered sweep. No queries ran, no result-set or production-content aggregate was emitted, and no
fallback one-mode claim was manufactured after observing the failure.

Read-only follow-up localized this as a generic Worker scheduling defect rather than a 897-local adapter
failure. The stalled cycles report `windowUnits=0`: late-chunk routing first performs an expensive
single-pass probe that returns null above the 8,192-token ceiling, then tokenizes the same over-limit parents
again to classify them for windowing. That work spends the embed share/deadline before the first resumable
window runs, so `WindowedEmbedProgress` has nothing to retain. The reported 50 writes are chunk SPLADE
retry-only updates with chunk SPLADE disabled, which count as activity but intentionally not stage progress.
The smallest robust repair belongs to the Worker lane: route a known null/arena-OOM fallback directly to
windowed embedding and guarantee one real window slice before honoring the newly-spent share/deadline.


<a id="e8"></a>

## E8 — Worker repair investigation record

### Rechartered derisk record (2026-09-04)

- The default policy permits 10,485,760 UTF-16 units while the parent currently admits 2,097,152 bytes and
  the frame codec admits 67,108,864 bytes. A conservative response calculation covering default content,
  bounded metadata/warnings/evidence/error fields, and 64 KiB of JSON structure is 66,452,608 bytes, leaving
  656,256 bytes below the protocol ceiling. Therefore the shipped default is representable without changing
  the protocol; larger incompatible configured policies can fail at construction with an actionable error.
- `StructuredContentHandler` counts source SAX characters before `StructuredDocument.toAnnotatedText()`;
  table serialization repeats row and column labels. A small table can therefore be below the SAX cap and
  above the final content cap while `structured.truncated()` is false. A deterministic table-expansion test
  plus a direct surrogate-boundary assertion discriminates the repair.
- The legal-corpus stall follows a deterministic branch: `embedWithSpans` returns null or throws arena OOM,
  increments `unitsDone`, appends the document to the window candidates, and then the window lane immediately
  consults stop/share with `unitsDone > 0`. A fixture whose stop signal flips on the probe must show one window
  request in cycle one and a non-zero resume offset in cycle two, with no second single-pass call.
- Existing one-frame framing, child lifecycle, retry escalation, vector pooling, and later-stage reservation
  do not need redesign. VDU behavior is not presently proven defective. No settled experiment is being
  repeated; the live reruns answer only the newly changed production paths.

Implementation confidence after this pass: **9/10 for the two code repairs** and **8/10 for complete 897
closure**. The remaining uncertainty is the private PPT classification and wall-clock VDU completion, not
the identified seams. Use the current high-reasoning implementation lane plus bounded independent review;
no model/effort escalation is warranted unless the PPT proves a distinct parser defect.


<a id="e9"></a>

## E9 — Historical takeover verification and confidence

## Derisk record

The confidence-building pass was approved by the user’s explicit research → design → derisk instruction.
It did not implement feature work.

Completed checks:

- created and verified dedicated worktree `F:/justsearch-public-worktrees/897-format-breadth` on
  `codex/897-format-breadth` from `189719e0`; main checkout was left untouched;
- ran world-state before adopting the lane;
- read 897, 887, 686, 639, 786, 705, 709, 741, 314, and the search-quality register;
- inspected the production extraction composition, structured IR/handler/summary, skip policy, existing
  tests, jseval raw-files path, corpus cache/fetch code, and pinned 620-file manifest;
- checked Tika 3.2.3 official coverage, recursive parsing, algorithm references, and source-use terms;
- verified through jseval that no duplicate-rate command currently exists;
- checked shared runtime health. A foreign live jseval backend owned by another worktree was present, so
  no dev stack or Gradle campaign was started; this avoided contaminating another lane’s measurement.

Closeout verification: `git diff --check` passed. `node scripts/docs/docs-validate.mjs` did not start
because this fresh worktree has no installed `fast-glob` package; no dependency install was performed for
a tempdoc-only change. Frontmatter/file-reference inspection was completed directly, but the repository
docs validator remains an explicit unverified check for the implementation/PR lane.

The required final world-state rerun likewise did not start because `gray-matter` is not installed in the
checkout; the initial pre-adoption world-state run did complete. Manual closeout status confirms this
worktree is clean after commit and main retains only unrelated user/other-session changes. The registered
helper sweep reaped nothing and reported one intentionally ownerless `otlp-sink` singleton, which it left
running by policy. The closeout commit is local and unpushed; publication was not authorized.

### Remaining risks and gates

| Risk | Consequence | Gate before implementation claims completion |
|---|---|---|
| No proven production-faithful test harness for this exact matrix | a test may still bypass routing/admission | first implementation slice proves one text, one Office, and one archive fixture through the selected harness before expanding formats |
| Tika recursion concatenates content but JustSearch may lose embedded identity | “ZIP searchable” could overclaim attachment support | report content survival and identity preservation separately |
| archive depth/count policy is not enforced by the normal structured-Tika artifact path: recursive content survives while both counters stay zero | a valid nested ZIP can bypass declared resource guards | keep the fixture row `KNOWN_GAP`; route the product-policy defect to its owning lane instead of claiming archive-policy coverage here |
| `.msg` fixture generation/provenance is unresolved | licensing or nondeterministic binary | optional row; no vendoring until cleared |
| the representative sibling covers EML, RTF, and ZIP but not MBOX, EPUB, ODF, or MSG | breadth characterization cannot be generalized to uncovered real-source distributions | keep uncovered families explicitly deterministic-only until a source/terms/hash review clears a later recipe revision |
| current Enron fetch removes exact duplicates | prevalence would be understated | measurement reads a pre-filter immutable snapshot and regression-test a planted duplicate |
| raw-file corpus identity omits source bytes under the default signature | results could drift while appearing reproducible | canonical ordered relpath/size/SHA-256 manifest digest plus extraction-policy identity |
| extracted-text snapshot/export seam is not yet selected | offline command could silently measure source JSON or an incomplete browse projection rather than shipped extraction | command fails closed unless input metadata proves extraction policy/parser/version, manifest identity, and expected/exported doc-count reconciliation |
| ranked-hit ids can collapse to leaf filename stems | same-name paths/cross-format copies can join to the wrong cluster | collision-safe sidecar key; one-to-one reconciliation test including same-name files in two directories |
| model-assisted near-duplicate labels are not independent human ground truth | calibration can inherit model judgment error | retain the triage sidecar as provenance, report conditional metrics only, and do not infer archive prevalence or independent human accuracy |
| public corpora are not personal drives | external-validity overclaim | proxy wording unless an opt-in private aggregate run exists; private artifacts use content fingerprints and run-local opaque ids only, never paths or plain path hashes |

**Implementation confidence: 7/10.** The architecture and ownership seams are clear, and 686/709 remove
most corpus plumbing risk. The remaining uncertainty is concentrated in the production-faithful harness,
pre-filter Enron projection, and licensing/source selection—not in parser product code. Recommended
implementation setting: a high-capability coding model at high or xhigh reasoning (for example
`gpt-5.6-sol`), with bounded explorer/reviewer subagents for fixture provenance, harness tracing, and the
mandatory refute-first review. Ultra/max effort is not justified unless the first archive/mail smoke
reveals an embedded-resource or sandbox protocol redesign, which is outside this lane and would force a
new owner decision.

Post-implementation update: confidence in the rechartered Enron implementation is **9/10**. The prior
resource failure is structurally removed, three full-scale executions were byte-identical, the observed
working set remained below 1 GB, the aggregate schema validates, and the independent reviewer found and
verified closure of both residual contract defects. Confidence in **complete P7 evidence closure** is
**7/10**, because calibration/holdout scoring is now complete and independently reviewed, while the production
realdocs run still exposes genuine sandbox/VDU backlog and the legal result-set run exposes a non-converging
long-document enrichment loop. The model-assisted labels also remain weaker than independent human ground
truth.

Confidence in the rechartered tempdoc implementation is now **9/10**: the bounded Enron census, private
review-packet seam, and fail-closed sibling-corpus materializer all have focused regression coverage and
successful real executions. Confidence in complete P7 evidence closure is **7/10** for the independent runtime
blockers and model-assisted-label limitation above; that rating does not count those broader repairs as missing
897 code.


<a id="e10"></a>

## E10 — Historical closeout verification

Post-fix verification evidence (2026-09-04):

- The full-stack realdocs rerun exposed a second production-only defect before aggregate capture:
  raw Lucene scorer iteration returned deleted-but-unmerged parent versions, so the complete-ID export
  contained duplicates. Folder browsing, folder files, facets, and complete-ID enumeration now filter
  `liveDocs`; complete-ID enumeration reports an exact total with bounded page memory, fails closed on a
  missing required ID, and carries a Worker-epoch/reader-generation token across GPL continuation pages.
  The evaluation HTTP export is explicitly one-shot at offset zero. Boundary, retained-tombstone,
  generation-drift, malformed-token, GPL propagation, and HTTP regressions pass; an independent
  refute-first re-review returned GO with no remaining P0–P2 finding.
- Fresh affected Worker regressions passed for the sandbox, extractor, combined backfill, and
  long-document classes; a second post-review extractor/sandbox run passed 37 tests. The repository
  `build -x test` and `spotlessCheck` gate passed.
- The affected production/schema/core/result-identity jseval slice passed 109 tests after review fixes.
  The complete jseval suite reached 3,313 passed and 14 expected skips; its sole failure is an untouched,
  pre-existing cadence assertion that expects four summary fields while the existing implementation emits
  the two newer `commit_by_reason` fields as well.
- The final independent refute-first review initially found one P1 and two P2 defects: programmatic requests
  could bypass loopback/exclusion validation, Python case-folding did not match the Worker's Unicode Windows
  path key, and visual evidence described pre-clamp text. Source validation now runs before every owned HTTP
  client construction, path hashing uses the Worker's lowercase projection, and all structured/OCR evidence
  derives from the effective artifact. New regressions reproduce each defect. The same reviewer reran 67
  Python tests and 37 fresh Java tests and returned GO with no remaining P0–P2 finding. Its P3 suggestion for
  a maximally escaped near-frame serializer case remains optional hardening; the required derived-budget,
  above-2-MiB real-child, multibyte, and incompatible-policy regressions are present.
- `python -m pytest` over the duplicate review/schema/production/Enron/core, command-surface,
  format-breadth, corpus-fetch, result-identity, and strict raw-manifest test modules: 254 passed, 4 expected
  Windows capability skips;
- after decision scoring and its fail-closed fixes, the expanded same-scope suite passed 271 tests with the
  same 4 expected Windows capability skips;
- `python -m compileall -q jseval`: passed;
- first `corpus-fetch-format-breadth --write-manifest` plus a second ordinary
  `corpus-fetch-format-breadth`: both passed with 33 files and realdocs overlap zero; the latter validated
  the observed source/member manifest and immutable 620-file realdocs manifest;
- `llmstxt-generate` and `skills-sync` write/check, canonical-link verification, runtime-config matrix
  verification, command-inventory regeneration/check, module-dependency projection, and
  `git diff --check`: passed.
- A fresh `$review-changes` pass reran the real materializer and 254-test suite and returned GO with no
  P0–P2 findings. Its one P3 finding was a missing working-directory convention in the canonical
  `tmp/...` review-packet example; the reference now explicitly requires running examples from
  `scripts/jseval/`, which makes that path resolve inside the enforced private root.

<!-- End of the preserved 2026-09-04 execution record. -->
