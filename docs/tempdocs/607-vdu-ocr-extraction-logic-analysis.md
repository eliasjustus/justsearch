---
title: "Document extraction routing authority (Tika/OCR/VDU)"
tempdoc: 607
status: active
created: 2026-06-18
updated: 2026-06-21
relates-to: [589, 593, 598, 600, 602, 612, 613, 205]
author: agent
category: indexing / extraction / vdu / ocr / ingestion / diagnosability / product-shape
---

# 607 - Document extraction routing authority (Tika/OCR/VDU)

> **Current state.** Baseline Worker-owned OCR routing has landed on `main`, and the follow-up packaging
> pass now restores an app-owned Tesseract payload from the pinned runtime manifest. The older issue-catalog
> sections below are preserved as design history; treat them as **pre-implementation evidence**, not a
> fresh description of the current worktree.

## Current status after implementation

Implemented in the current worktree:

- Worker extraction now owns the Tika -> OCR routing decision.
- Structured Tika remains the first pass; bounded Tika/Tesseract OCR is attempted only for low-quality
  OCR-eligible PDFs/images when the runtime probe allows it.
- OCR provenance is durable through `extraction_method=OCR_TIKA`.
- VDU successful text replacement records `extraction_method=VDU`; failed or completed-empty VDU keeps
  the best baseline content.
- Preview provenance now distinguishes `tika`, `ocr`, `vdu`, `vdu_pending`, `vdu_processing`, and
  `vdu_failed`, and no longer treats `vdu_processed=true` as proof that VDU produced the active text.
- Worker status and readiness can expose OCR capability blockers, including the local no-Tesseract case.
- Default product config now treats OCR as baseline-enabled; missing Tesseract reports
  `ocr.engine_missing`, not `ocr.disabled`.
- The Worker OCR runtime can discover app-owned Tesseract under `JUSTSEARCH_HOME/native-bin/tesseract`,
  app data homes, and the bundled headless/native-bin layout before falling back to PATH.
- The desktop shell restores an optional bundled `native-bin/tesseract` payload into AI Home, then
  launches Head/Worker with that directory on PATH and `TESSDATA_PREFIX` pointing at packaged tessdata.
- The UI sidecar bundle restores the pinned Windows Tesseract runtime into a verified staging directory,
  copies the Tesseract notice/manifest with it, and runs `verifyTesseractRuntime` before release-side
  bundling.
- A focused resolver test covers app-owned runtime discovery and language probing.
- A pinned Windows runtime manifest now declares Tesseract 5.5.0.20241111 plus English tessdata, hashes,
  sizes, source URLs, validation command, and notice text.
- A real OCR success test now exists: it generates an image with text, runs only when the packaged
  PATH/TESSDATA_PREFIX projection is present, and asserts Worker-owned `ocr.succeeded_total` and
  `ocr.time_ms` metrics.
- OCR metrics have been re-homed to the live Worker extraction route while preserving the metric names:
  `ocr.succeeded_total`, `ocr.time_ms`, `ocr.failed_total`, and `ocr.skipped_total`.
- The dormant Head-side `app-indexing` OCR processor/metric scaffold has been removed so Worker
  extraction is the only live OCR metric authority.
- Visual demand is split: `visualTextNeededCount` remains baseline readable-text demand, and
  `visualEnrichmentNeededCount` tracks VDU enrichment demand. Documents also carry `vdu_demand_kind`
  (`baseline_text` or `visual_enrichment`).
- OCR success always clears baseline readable-text demand; OCR-success PDFs can still queue VDU as
  `visual_enrichment` independently of OCR text quality.
- Legacy pending VDU rows without `vdu_demand_kind` are counted as baseline visual-text demand so
  retrieval readiness remains compatible after upgrade.
- VDU global blockers now have a Head-owned capability overlay that can project `vdu.ai_offline`,
  `vdu.insufficient_vram`, `vdu.missing_mmproj`, and `vdu.circuit_open` into Worker status/readiness.
- The Head VDU capability overlay clears stale blockers when no VDU work is pending.
- Readiness severity is split by impact: OCR and baseline-VDU blockers degrade retrieval only while
  baseline visual text is still missing; enrichment-only VDU blockers degrade `aiFeatures` through
  `visualDocumentUnderstanding`.
- Canonical docs now describe Worker OCR routing, demand splitting, runtime/config ownership, and metric
  ownership. This tempdoc remains the design/history note.
- Real browser validation proves the packaged-runtime Search and Inspector path end to end: generated OCR
  image/PDF fixtures index as `OCR_TIKA`, are searchable by OCR-only tokens, a real search-result click
  opens Inspector, and Inspector shows `Text source OCR`, OCR confidence, fallback route, and OCR text.
- User-facing design passes checked the current Chat/Search/Health/Inspector UX and recorded where OCR
  and VDU state should appear for users. The latest live pass validated the real Search -> Inspector
  OCR provenance/evidence path.
- The validation pass caught and fixed two authority leaks: ambient Tika image OCR is suppressed in the
  structured baseline pass, and `ExtractorContributionRegistry` now preserves the underlying extraction
  artifact parser id instead of collapsing provenance to the registry wrapper.
- Worker extraction now emits a compact `visual_extraction_evidence` JSON summary alongside
  `extraction_quality_score`. The evidence records only routing-relevant facts: page count, text quality,
  chars/page, OCR language, text-layer coverage, missing-readable-text pages, mixed-PDF flag, structured
  element counts, image-page count, layout complexity, and chosen route.
- The structured extraction seam now produces a document summary, and PDFs are additionally analyzed with
  PDFBox for text-layer/image-page evidence. This keeps routing evidence inside the Worker extraction
  authority instead of adding a parallel parser.
- Mixed PDFs are now detected. When OCR is available and within guards, the Worker can OCR only pages that
  lack readable text, append that page OCR to the active searchable content, and record the route as
  selective OCR.
- VDU demand is now evidence-based rather than "OCR-success PDF means enrichment." Baseline demand is set
  only when searchable text remains missing/poor; enrichment demand is set only when compact evidence
  shows useful visual/layout signals.
- The preview API returns optional `visualExtractionEvidence`, and the Inspector renders a small text-source
  explanation with route, OCR language, quality, missing visual pages, and layout complexity when present.
- OCR confidence is now populated as compact document-level evidence from bounded Tesseract TSV output
  after OCR succeeds. The evidence stores normalized mean confidence, low-confidence word count, and
  OCR word count; it does not store word boxes or page-level OCR metadata.
- VDU enrichment demand now uses OCR confidence as one evidence signal. Low mean confidence or a high
  ratio of weak OCR words can queue `visual_enrichment`, while high-confidence plain OCR remains baseline
  searchable text with `VDU_STATUS_NOT_NEEDED`.
- The Inspector preview source explanation now includes OCR confidence when present.
- VDU demand routing is now isolated in a small Worker-owned decision helper instead of being embedded in
  document-write operations. The helper keeps baseline-text demand and enrichment-only demand separate.
- Worker extraction now shares the readable-page threshold and page-signal vocabulary across structured
  summaries and PDF visual analysis.
- Compact evidence now also records optional OCR truncation, fallback route, and OCR guard/skip reason.
- The Stage retention cache now refreshes `host_` and `api-base` on retained surface elements. This fixed
  the live Search -> Inspector validation path without adding a new frontend surface.

Still open:
- The public evidence remains compact and document-level. Full OCR boxes, full per-page OCR metadata, and
  versioned text-layer history remain future scope.
- The VDU enrichment predicate is now evidence-based, but still deliberately compact. It recognizes broad
  layout/table/mixed-visual signals; it is not yet a trained or user-demand-aware classifier for diagrams,
  charts, screenshots, or forms.
- Browser validation of simulated unavailable states is still partial: the missing-engine/backend status
  paths are covered by focused tests and earlier checks, but VDU-unavailable browser simulation was not
  exhaustively re-run in this final packaged-runtime pass.
- The release manifest pins English OCR only. Additional languages and non-Windows runtime packaging are
  future work.

This means the correct short product read is: **the routing authority is implemented and hardened for
release packaging, Worker metrics, demand splitting, compact routing evidence, mixed-PDF handling, and VDU
blocker projection.** The remaining work is mostly unavailable-state validation breadth and future-quality
refinement, not a missing core authority.

## Visual extraction polish pass (2026-06-21)

This pass completed the remaining v1 polish around evidence vocabulary, routing shape, and live UI proof.

Implemented:

- VDU demand routing moved into a typed Worker-owned decision helper. `IndexingDocumentOps` now applies
  the helper's result instead of embedding the routing policy in document-write code.
- Structured summaries and PDF visual analysis now share the readable-page threshold and page-signal
  vocabulary.
- `visual_extraction_evidence` remains compact and document-level, but now includes optional routing facts
  for content truncation, OCR fallback route, and OCR skip/guard reason.
- High-confidence plain OCR no longer queues VDU enrichment merely because the source was visual.
  Enrichment remains limited to stronger evidence such as low OCR confidence, high weak-word ratio,
  table/form-like structure, mixed-PDF/layout value, or poor post-OCR text quality.
- Mixed PDF evidence keeps `mixedPdf=true` while reporting `pagesMissingReadableText=0` after selective OCR
  fills the missing pages.
- Inspector source details now show fallback/truncation/skip evidence when present, alongside OCR
  confidence.
- The retained Stage surface cache now refreshes `host_` and `api-base` every render. This fixed a live
  validation failure where a retained Search surface could render results but fail to open Inspector.

Validation:

- Focused Worker extraction/routing tests passed, including OCR skip evidence, VDU demand, OCR confidence,
  and status/readiness projection.
- `:modules:ui:verifyTesseractRuntime` passed.
- `node scripts/ci/check-readiness-reason-codes.mjs` passed.
- Frontend typecheck passed.
- Focused frontend tests passed for Stage retention, Search retention, and Inspector evidence rendering.
- A real browser pass against the dev stack indexed generated OCR fixtures, searched for an OCR-only token,
  clicked the real result row, observed `GET /api/preview` returning 200, and verified Inspector displayed
  `Text source OCR`, `96% OCR confidence`, `direct tesseract fallback`, and the OCR-only token.

## Post-implementation alignment review (2026-06-20)

The implemented work satisfies the central v1 outcome of this tempdoc.

Conceptually aligned outcomes:

- The app no longer treats VDU as the only practical path for scanned/text-like visual documents.
  Worker extraction now owns the baseline route: normal Tika first, bounded Tika/Tesseract OCR second
  when the first pass is weak and the file is OCR-eligible.
- OCR is product baseline searchability, not just an explanation feature. It writes durable
  `extraction_method=OCR_TIKA`, uses the Worker extraction artifact/provenance path, and can make
  image-text files searchable before VDU runs.
- VDU remains Head/Brain enrichment. Successful non-empty VDU may supersede active content with
  `extraction_method=VDU`; failed and completed-empty outcomes preserve the best baseline text.
- Preview provenance is now based on active extraction method plus VDU state instead of treating
  `vdu_processed=true` as proof that VDU produced the current text.
- OCR capability is honestly reported. Missing/disabled/missing-language OCR appears as readiness/status
  degradation when visual text work exists, rather than silently failing or pretending OCR is off by
  product choice.
- The packaged-runtime follow-up closes the first operational gap: an app-owned `native-bin/tesseract`
  payload is restored from the pinned manifest, projected into the process environment, bundled by the
  sidecar task, and validated by a real OCR success test.
- The release-readiness pass closes the second operational gap: pinned runtime metadata, packaging
  validation, Worker OCR metrics, demand splitting, VDU blocker projection, and readiness severity now
  follow the same authority.
- Real UI validation closed the OCR-success Search gap. Preview/Inspector provenance is exposed through
  the preview API and covered by Inspector unit tests, but still needs a clean live browser Inspector pass.

Remaining conceptual mismatches:

- **OCR confidence is now present but deliberately compact.** The system derives document-level confidence
  from bounded Tesseract TSV output and uses it for explanation/routing. It still does not store word boxes,
  page-level confidence maps, or versioned OCR artifacts.
- **VDU enrichment selection is still conservative.** The system now consumes compact evidence instead of
  using the old OCR-success-PDF placeholder, but future work can improve detection for forms, diagrams,
  charts, screenshots, and user-demand-driven enrichment without changing the authority.
- **Unavailable-state browser validation is still incomplete.** Focused compile/tests/gates, packaged
  Tesseract validation, readiness/status tests, and real OCR-success Search -> Inspector browser validation
  pass. Simulated VDU-unavailable UI validation still needs a targeted browser pass.
- **Runtime scope is English/Windows-first.** The manifest pins English tessdata for the Windows runtime.
  More languages and other platforms should be added as separate declared artifacts.

Short judgment: the implementation matches the tempdoc's routing-authority design. The remaining work is
not a missing core edge; it is VDU-unavailable UI validation and future-quality refinement.

## Authority-gap fix pass (2026-06-20)

This pass closed the substantive gaps found in the latest critical review:

- `:modules:ui:verifyTesseractRuntime` now restores the pinned Windows Tesseract installer and
  `eng.traineddata` from the manifest, extracts the runtime into a build staging directory, verifies
  declared file hashes/sizes, and requires `eng` from `tesseract.exe --list-langs`.
- Sidecar bundling now consumes that verified staging directory instead of depending on a pre-existing
  local `native-bin/tesseract` folder.
- Worker VDU demand routing now treats `OCR_TIKA` as satisfying baseline text, and evaluates the v1
  enrichment predicate separately; OCR-success PDFs can still queue VDU as `visual_enrichment`.
- `visualTextNeededCount` remains compatible with old indexes by counting legacy `VDU_STATUS=PENDING`
  rows without `vdu_demand_kind` as baseline visual-text demand.
- The Head VDU capability overlay clears stale blockers when `OfflineCoordinator` sees no pending VDU
  work.
- The old Head-side `app-indexing` OCR processor/metric scaffold and tests were removed; Worker
  extraction is now the only live OCR metric authority.

Validation in this pass:

- `:modules:ui:verifyTesseractRuntime` passed with manifest-driven download/extraction/staging.
- Focused Worker tests passed for OCR/VDU demand routing and legacy visual-demand counting.
- Focused app-services VDU tests passed, including stale blocker clearing.
- `:modules:app-indexing:test` passed after removing the dormant OCR scaffold.

## Research and opportunity pass after implementation (2026-06-20)

This pass asked what the implemented authority makes possible next. It is not a request to broaden the
current implementation immediately; it records useful future directions while the design is fresh.

Method:

- Re-read this tempdoc and the current implemented-status sections.
- Re-inspected the existing OCR/VDU seams in Worker extraction, status/readiness, UI provenance, canonical
  docs, and observability docs.
- Rechecked external references for Tika/Tesseract/OCRmyPDF/Paperless behavior:
  [Apache Tika OCR](https://cwiki.apache.org/confluence/display/tika/tikaocr),
  [Tesseract command-line output](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html),
  [OCRmyPDF advanced modes](https://ocrmypdf.readthedocs.io/en/latest/advanced.html),
  [paperless-ngx usage](https://docs.paperless-ngx.com/usage/),
  [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast), and
  [Tesseract user manual](https://tesseract-ocr.github.io/tessdoc/).

External signals that matter:

- Tika still treats OCR as a Tesseract-backed capability that must be installed and tested. JustSearch's
  app-owned runtime manifest and Health/readiness reporting are therefore the right product shape.
- Tesseract can emit searchable PDF, hOCR, and TSV output. hOCR/TSV include word boxes and confidence
  values, which could later become quality signals without adding a full versioned text-layer store.
- OCRmyPDF's `skip`, `redo`, and `force` modes show that mature OCR systems eventually need page-level
  decisions for mixed PDFs. A document-level route is acceptable for v1, but it will be too coarse for
  long-term quality.
- OCRmyPDF also uses timeout and image-size guards, reinforcing that bounded inline OCR is the right
  default for baseline searchability.
- paperless-ngx follows a familiar product path: OCR only when text is missing, preserve the original,
  create searchable derived text/PDF, then use the result for matching/classification. JustSearch should
  preserve originals and treat derived OCR artifacts as search/diagnostic aids, not as replacement files.
- `tessdata_fast` is a deliberate speed/accuracy compromise for Tesseract 4/5. More languages should be
  declared and visible to the user, not silently bundled or auto-downloaded.

Most useful future directions:

1. **Page-level visual routing.** Keep the document-level `vdu_demand_kind` summary, but eventually let
   mixed PDFs decide per page whether structured text, OCR, or VDU is needed. This would reduce wasted OCR
   on digital pages and reduce missed OCR on scanned pages inside otherwise digital PDFs.
2. **OCR quality sidecar.** Store a compact derived quality record from OCR output, such as average word
   confidence, low-confidence span counts, bounding-box coverage, page count, and detected language. Use it
   for routing, Health, and preview diagnostics. Do not introduce a full historical text-layer store unless
   a later feature truly needs it.
3. **Sharper VDU enrichment predicate.** OCR success should not automatically mean VDU is useful forever.
   Good future signals include low OCR confidence, dense tables/forms, charts/diagrams, many non-text
   image regions, screenshots, and user demand such as a search miss or preview/AI action.
4. **Visual text inspector UX.** The preview already has the right provenance seam. It could become more
   useful with a small "why this text" inspector: source (`tika`, `ocr`, `vdu`), OCR language, confidence
   summary, pending enrichment, and the exact blocker when OCR/VDU is unavailable.
5. **Health/Settings language management.** English Windows OCR is enough for v1. A later settings surface
   should show installed OCR languages, configured requested languages, missing language blockers, and the
   speed/accuracy profile of the installed tessdata pack.
6. **Demand-prioritized VDU.** VDU is expensive, so future scheduling should prefer documents that the user
   is actively searching, previewing, or asking AI about over background enrichment-only backlog.
7. **Optional derived searchable artifact.** Do not mutate originals. A future export/debug feature could
   create a searchable PDF or sidecar text artifact, but JustSearch should not become a document-management
   archive system unless product scope changes.
8. **Evaluation corpus.** Build a small permanent visual-document fixture set: text-layer PDF, image-only
   PDF, mixed PDF, screenshot, receipt, table/form, chart/diagram, multilingual document, and damaged OCR
   text. Track searchability gain, OCR time, confidence, VDU usefulness, and user-visible blocker wording.

Ideas to avoid for now:

- Do not run VDU on every OCR-success document by default. That keeps costs high and hides the real
  question: when does richer visual understanding actually help?
- Do not ship every Tesseract language pack. Language scope should be explicit, validated, and visible.
- Do not create a generic multimodal media framework yet. The current problem only needs baseline text
  versus visual enrichment demand.
- Do not add Activity-feed or toast notifications for normal OCR/VDU backlog. Health, preview, and
  readiness surfaces are the right channels.

Short product read: the implemented authority makes JustSearch ready to move from "can this visual file
be indexed at all?" toward "how good is the extracted text, why was this route chosen, and when is
expensive visual understanding worth spending?"

## External volatility research check (2026-06-20)

This pass checked whether the design should be re-evaluated against fast-moving external work. The answer
is yes: the volatile area is not baseline OCR itself, but the boundary between OCR, layout-aware document
parsing, visual retrieval, and multimodal RAG.

Research plan used:

- Check stable extraction/runtime sources first: Tesseract release/docs, Apache Tika OCR strategy, and
  OCRmyPDF's OCR routing/guard behavior.
- Check active research directions second: OCR-free visual document retrieval, multimodal document RAG,
  document-parsing VLMs, PDF linearization, and benchmark churn.
- Use only primary-ish sources for conclusions: official docs/release notes and papers/project pages.

Sources checked:

- [Tesseract release notes](https://tesseract-ocr.github.io/tessdoc/ReleaseNotes.html) and
  [Tesseract downloads](https://tesseract-ocr.github.io/tessdoc/Downloads.html)
- [Apache Tika OCR docs](https://cwiki.apache.org/confluence/display/tika/tikaocr) and
  [Tika 3.2 `PDFParserConfig.OCRStrategyAuto`](https://tika.apache.org/3.2.0/api/org/apache/tika/parser/pdf/PDFParserConfig.OCRStrategyAuto.html)
- [OCRmyPDF advanced features](https://ocrmypdf.readthedocs.io/en/latest/advanced.html)
- [ColPali](https://arxiv.org/abs/2407.01449),
  [ViDoRe V3](https://arxiv.org/abs/2601.08620),
  [PaddleOCR-VL](https://arxiv.org/abs/2510.14528),
  [MinerU2.5](https://arxiv.org/abs/2509.22186),
  [olmOCR](https://arxiv.org/html/2502.18443v3), and
  [PureDocBench](https://arxiv.org/html/2605.07492v1)

Findings:

1. **Baseline OCR is still the right first product layer.** Tesseract remains a maintained OCR runtime, but
   its official Windows packaging story still depends on third-party installers. That validates the
   declared runtime-manifest design. Tesseract has newer releases than the currently pinned baseline, so the
   manifest should be reviewed periodically, but this does not change the architecture.
2. **Tika is moving toward page-level OCR decisions.** Tika's auto OCR strategy can decide based on page
   character counts and unmapped Unicode. That supports JustSearch's long-term page-level routing idea:
   document-level demand remains the summary, but page-level evidence should eventually drive OCR/VDU.
3. **Mature OCR systems treat guards and modes as first-class.** OCRmyPDF's skip/redo/force modes, tagged
   PDF behavior, per-page timeout, and large-image skip reinforce the current bounded inline OCR design.
4. **Visual document retrieval is the fastest-moving adjacent field.** ColPali/ViDoRe and newer visual RAG
   work show that some document search can bypass OCR by indexing page images directly. This does not
   replace OCR for JustSearch v1 because it is heavier and less transparent, but it means future VDU work
   should not be limited to "better text replacement"; visual retrieval may become a separate enrichment.
5. **Document parsing VLMs are becoming smaller and more specialized.** PaddleOCR-VL and MinerU2.5 show a
   clear direction toward compact models that parse text, layout, tables, formulas, and charts. This
   supports the design's evidence-based VDU predicate: VDU should be queued for layout/visual value, not
   just because OCR text quality was low.
6. **Evaluation is unstable.** New benchmarks and critiques show that document parsing scores can saturate
   or depend on benchmark quality. JustSearch should not chase a single external leaderboard. The safer
   product move is a small internal visual-document fixture corpus that measures searchability, latency,
   quality evidence, VDU usefulness, and user-visible blocker wording.

Design implication:

- Keep the implemented v1 boundary: Worker OCR creates baseline searchable text; Head/Brain VDU remains
  expensive enrichment.
- Strengthen the future design around compact extraction evidence: page-level text presence, confidence,
  language fit, layout complexity, tables/forms/charts, and user demand.
- Do not add a generic multimodal framework now. Record visual retrieval as a future candidate capability,
  separate from v1 OCR and from VDU text replacement.
- Periodically revisit the pinned Tesseract runtime and Tika OCR strategy, because those are operational
  dependencies, not fixed design facts.

## User-facing design pass (2026-06-20)

This tempdoc is user-facing indirectly and directly. The routing decision changes whether scanned or
image-text files are searchable, what source label appears in preview, which degraded-capability reasons
appear in Search/Chat/Health, and which future settings users may need for OCR languages/runtime repair.

Live UI evidence checked:

- Dev stack was already running through `scripts/dev/dev-runner.cjs` on UI port `5173` with backend API
  port `52485`.
- Claude Chrome / in-app browser DOM inspection was used for the live app. Its screenshot API timed out
  repeatedly, so screenshots for this pass were captured with local Playwright against the same live URL.
- Screenshots:
  - `modules/ui-web/tmp/tempdoc-607-ux-pass/01-chat-degraded-banner-clean.png`
  - `modules/ui-web/tmp/tempdoc-607-ux-pass/02-system-health-readiness-clean.png`
  - `modules/ui-web/tmp/tempdoc-607-ux-pass/03-search-surface-after-nav.png`
  - Prior OCR validation reference: `tmp/ocr-vdu-ui-validation/ui-ocr-validation-with-source.png`

Observed UI patterns:

- Chat and Search already use the shared readiness banner pattern for retrieval-impacting degraded
  capability. OCR blockers belong there only when baseline visual text is still missing.
- System Health already has the right "what can I do right now?" capability-row shape. OCR availability,
  missing language packs, missing runtime, and VDU paused/offline should be expressed there before adding
  any new surface.
- The Search surface is a work surface, not a diagnostics page. It should warn about missing OCR only when
  searchability is materially affected; VDU enrichment-only backlog should not make Search look broken.
- The Inspector already has the right first seam: `Text source Tika/OCR/VDU/...`. It is too small for future
  quality evidence, but it is the right place to explain the active text source for one document.
- Existing navigation/toast behavior is visually interruptive enough that normal OCR/VDU backlog must not
  create Activity-feed or toast noise. Health, Search/Chat banners, and Inspector details are sufficient.

Correct user-facing design:

1. **Search and Chat show only retrieval-impacting visual extraction problems.** If OCR is missing and
   scanned/image-text documents still lack baseline text, the banner should say in plain language that
   scanned text may not be searchable yet and point to Health or repair. If OCR has produced baseline text
   and only VDU enrichment is pending or blocked, Search should stay calm.
2. **System Health owns capability diagnosis and repair.** Health should show one visual-text capability
   area with separate meanings: OCR baseline text extraction, VDU/visual understanding enrichment, baseline
   documents waiting, enrichment documents waiting, missing runtime, missing language, AI offline,
   insufficient VRAM, missing vision projector, or circuit-open backoff. This should reuse the existing
   readiness reason-code vocabulary.
3. **Inspector owns per-document provenance.** The current `Text source OCR` badge is correct for v1. The
   long-term Inspector should expand that into a compact details row or disclosure, not a modal: active text
   source, whether the document is searchable now, OCR language, quality/confidence summary when available,
   VDU state, and the blocker/remedy if visual enrichment is paused.
4. **Settings should appear only when language/runtime choice exists.** English Windows OCR can remain
   implicit for v1. Once more languages are supported, settings should show installed OCR languages,
   requested languages, missing language blockers, and the speed/accuracy profile of the bundled tessdata.
5. **Use user terms first and diagnostic terms second.** UI copy should prefer "text from image" and
   "visual understanding" for user-facing labels, while keeping `OCR`, `Tika`, and `VDU` available as compact
   badges or diagnostic details. `VDU` alone is too internal for a primary user explanation.
6. **Completed-empty or failed VDU must never look like active VDU text.** The user should see the active
   baseline source plus a small detail such as "visual understanding found no better text" or "visual
   understanding failed; showing OCR text." This preserves trust in the preview.

Design consequence: the backend authority should stay as implemented, but future polish should make the
three user surfaces clearer: Search/Chat for search-impacting blockers, Health for capability and repair,
and Inspector for document-level explanation. The design should not create a new "OCR dashboard" or a
separate visual media framework.

## Settled long-term design refinement (2026-06-20)

The title remains correct: the durable object is still the **document extraction routing authority**.
The current investigation found usable system seams, so the long-term design should extend those seams
instead of replacing them.

Current usable seams:

- `PolicyDrivenTikaExtractor` is already the right Worker-owned routing point for structured Tika first,
  bounded Tika/Tesseract OCR second.
- `ExtractionArtifact` / `ValidatedExtractionArtifact` already form the parser trust boundary and carry
  parser provenance. They are the natural place to attach future compact extraction-quality evidence.
- `SchemaFields.EXTRACTION_METHOD`, `EXTRACTION_QUALITY_SCORE`, `VDU_STATUS`, and `vdu_demand_kind`
  already separate active text provenance from pending visual work.
- `VisualExtractionStatus` / `VisualExtractionView` already carry OCR capability, baseline visual-text
  demand, enrichment demand, and VDU blockers into status/readiness.
- `StatusLifecycleHandler` already routes baseline visual-text blockers to retrieval and enrichment-only
  VDU blockers to `visualDocumentUnderstanding` / `aiFeatures`.
- `PreviewController` and `InspectorPane` already expose the first user-facing provenance seam: "Text
  source Tika/OCR/VDU".
- `packaging/runtime/tesseract-windows.v1.json`, `verifyTesseractRuntime`, and the legal/runtime docs
  already match the declared-runtime-artifact pattern.

The long-term design should settle into seven responsibilities:

1. **Worker owns baseline searchable text.** Structured Tika and bounded Tika/Tesseract OCR stay inside
   Worker extraction because this is where documents become indexed artifacts. OCR remains a fallback for
   scanned/text-like visual content, not a Head-side enrichment job.
2. **Worker also owns visual text quality evidence.** The current `TextQualityAnalyzer` score is enough
   for v1, but the long-term model needs a compact quality summary: page count, text density, OCR language,
   confidence summary, low-confidence span count, image/page guard outcome, and coarse layout complexity.
   This should be a sidecar/metadata record attached to the extraction artifact or indexed document, not a
   second content store and not a historical text-layer archive.
3. **Document-level demand remains the public summary; page-level routing can exist underneath.** Mixed
   PDFs eventually need per-page decisions: some pages may have a good text layer, some need OCR, and some
   may benefit from VDU. The existing document-level fields (`extraction_method`, `VDU_STATUS`,
   `vdu_demand_kind`, `visualTextNeededCount`, `visualEnrichmentNeededCount`) should remain the summary
   surface. Page-level details are evidence for routing and diagnostics, not a new user-facing media
   framework.
4. **Head/Brain owns VDU enrichment and VDU capability facts.** VDU remains asynchronous enrichment that
   can supersede active content only on successful non-empty output. Failed and completed-empty VDU must
   stay outcome state, not active text provenance. VDU global blockers stay capability/readiness state,
   not mass per-document failures.
5. **The VDU enrichment predicate consumes evidence, not file extension alone.** The v1 predicate
   (`OCR_TIKA` PDF -> possible `visual_enrichment`) is intentionally conservative. Long term, VDU should
   be queued when evidence says richer visual understanding is useful: low OCR confidence, tables/forms,
   charts/diagrams, screenshot-like pages, large non-text visual regions, or user demand from search,
   preview, or AI actions.
6. **Capability is projected through existing readiness/status authorities.** OCR blockers affect
   retrieval only while baseline searchable text is missing. VDU enrichment blockers affect AI features.
   Health/readiness and the preview inspector are the right surfaces. Activity-feed entries, document-card
   copies, and toast spam are the wrong surfaces for normal OCR/VDU backlog.
7. **Runtime and language scope stays declared.** Tesseract/tessdata remain app-owned runtime artifacts
   with pinned source/version/hash/license and validation. English Windows OCR is the current baseline.
   More languages or non-Windows runtimes should be added as declared artifacts plus user-visible language
   capability, not silent best-effort downloads.

The correct next shape is therefore **quality-aware visual extraction routing**: the app first asks
"can I produce baseline searchable text cheaply and reliably?", then "is the text good enough?", then
"would expensive VDU add useful visual/layout meaning?" The answer is stored as active provenance,
compact quality evidence, and demand/capability status through the authorities that already exist.

This remains a general design, not an implementation prescription. It deliberately does not introduce a
universal multimodal indexing framework, a new notification model, or a versioned text-layer store,
because the current problem only requires active provenance, quality evidence, pending demand, runtime
capability, and user-legible explanation.

### Design reach: principle recognized, not generalized now

This design is an instance of an existing JustSearch principle:

> **Evidence-summary before expensive enrichment.** A cheap baseline authority should produce the active
> artifact plus enough compact evidence to decide whether an expensive or asynchronous enrichment is
> actually needed. Downstream surfaces consume the evidence through existing readiness/provenance
> authorities; they do not reconstruct the decision and they do not invent parallel messages.

Where this already exists:

- `598` applies the same idea to retrieval: the Worker embedding-compat boundary decides whether dense
  search is serviceable, and callers project that capability instead of guessing.
- `600` applies it to degraded capability causes: raw causes must enter the one readiness/wording channel
  instead of leaking as separate strings.
- `599` applies it to folder status: folder-facing progress should project from the job/folder authority,
  not from unrelated global counters.
- `612` / `613` apply it to messaging: the right surface is derived from meaning facets and surface
  identity, not re-decided at every emission site.

Where the current OCR/VDU code still violates or only partially satisfies the principle:

- `TextQualityAnalyzer` is still a single text-only score. It cannot explain page-level OCR confidence,
  language mismatch, layout complexity, or why VDU was still useful after OCR.
- `shouldQueueVisualEnrichment` is still mostly a PDF-extension predicate. It is a placeholder for
  evidence-based enrichment demand, not the long-term design.
- Mixed PDFs are still routed at document granularity. This is acceptable for v1, but too coarse for the
  final quality model.
- The preview inspector shows text source, but not yet the evidence behind the source or the pending
  enrichment/blocker state.

Candidate scope beyond this tempdoc:

- Search/RAG confidence and reranking could use the same evidence-summary rule, but only when a concrete
  feature needs it.
- NER/SPLADE/embedding backfills may also need "baseline available vs enrichment useful" wording, but do
  not require a generalized enrichment framework now.
- Runtime artifact/language management can reuse the declared-runtime pattern, but should not become a
  generic plugin/package manager here.

Record the principle; do not build a generalized structure now. The present tempdoc only needs the
OCR/VDU instance: visual extraction quality evidence, demand splitting, and provenance/readiness
projection.

## 0. Takeover investigation update (2026-06-19)

### Method

- Re-read this tempdoc and the tempdoc rules.
- Re-read the local OCR future-feature note and ADR-0018.
- Traced the Worker extraction path, extractor contribution/sandbox/artifact substrate, VDU marking
  path, Head-side VDU processing path, OCR scaffolding, config, metric registration, status/readiness
  projection, and runtime dependency lockfiles.
- Read adjacent/relevant tempdocs: 589, 593, 598, 600, 602, 606, 608, 612, and 613. 593/602 preserve
  the user symptom, 598/600 preserve the capability-truthfulness pattern, and 612/613 constrain the
  surface design away from feed/toast noise.
- Checked primary external docs for Apache Tika OCR, Tika's `TesseractOCRParser`, Tess4J, and
  llama.cpp multimodal/mmproj behavior.
- Ran focused probes:
  - `Get-Command tesseract -ErrorAction SilentlyContinue` returned no executable in this environment.
  - `.\gradlew.bat :modules:worker-services:test --tests "io.justsearch.indexerworker.loop.VduEligibilityPdfFixturesTest" :modules:app-services:test --tests "io.justsearch.app.services.vdu.VduBatchProcessorTest"` passed.

### Evidence map

- Worker marks VDU need in
  `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/IndexingDocumentOps.java`
  (`VDU_ELIGIBLE_EXTENSIONS`, `VDU_QUALITY_THRESHOLD`, `markVduIfNeeded`).
- The Worker extraction composition boundary is
  `modules/worker-services/src/main/java/io/justsearch/indexerworker/extract/ExtractorContributionRegistry.java`,
  built through `ExtractionSandboxFactory` and `TimeboxedContentExtractor`.
- The parser-result/provenance carrier is `ExtractionArtifact` /
  `ValidatedExtractionArtifact`, with existing fields for extraction status, policy id, parser id,
  parser warnings, truncation, and reason code.
- The pre-implementation active structured Tika path was
  `modules/worker-services/src/main/java/io/justsearch/indexerworker/extract/StructuredContentExtractor.java`;
  it configured PDF marked-content extraction, not Tika OCR.
- OCR scaffolding is in
  `modules/app-indexing/src/main/java/io/justsearch/app/indexing/ocr/`; production searches only found
  constructor usage in tests.
- OCR metrics are cataloged at
  `modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java`.
- VDU global blockers live in
  `modules/app-services/src/main/java/io/justsearch/app/services/vdu/OfflineCoordinator.java` and
  `modules/app-services/src/main/java/io/justsearch/app/services/vdu/VduBatchProcessor.java`.
- Vision capability and projector selection are in
  `modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceConfig.java` and
  `modules/app-services/src/main/java/io/justsearch/app/services/vdu/VduProcessor.java`.
- Existing global diagnosability flows through `/api/status` readiness composites, backend
  `LifecycleReasonCode`, frontend `readinessNotice.CAUSE_ROWS`, and the `aiStateStore` verdict path.

### Corrected conclusions

At the time of takeover, the original thesis was directionally right: **JustSearch had no dependable
default text-extraction path for image-only/scanned documents when VDU could not run.** But several
details needed tightening:

- **Tika OCR is classpath-present, not product-wired.** The runtime lockfiles include
  `org.apache.tika:tika-parser-ocr-module:3.2.3`, so "Tika has no OCR" was too broad. The product does
  not configure `TesseractOCRConfig`, does not set a PDF OCR strategy, and this environment has no
  `tesseract` binary on PATH. So the practical result is still no active OCR, but the failure is wiring
  and packaging, not absence of the Tika parser module.
- **OCR scaffolding is Head-side and dormant.** `OcrProcessor` and `OcrGuards` are only instantiated in
  tests. `config.ocr()` is resolved by configuration code but has no production readers. There is no
  production OCR engine supplier. The classes are useful guard/concurrency scaffolding, not a complete
  extraction path.
- **`OcrMetricCatalog` is registered.** `HeadlessApp` includes the OCR metric namespace and
  definitions. The metrics are still operationally dead because the emitters live in the dormant
  processor/guards.
- **The VDU eligibility predicate is verified.** Worker-side ingest marks a document `VDU_PENDING` when
  its extension is VDU-eligible and `TextQualityAnalyzer.computeQualityScore(extraction.content())` is
  below the configured threshold. The fixture test verifies text-layer PDFs become `VDU_STATUS_NOT_NEEDED`
  and image-only PDFs become `VDU_STATUS_PENDING`.
- **VDU has per-document terminal states once processing is attempted, but global blockers do not become
  durable document reasons.** Missing files, per-document exceptions, and blank VDU output are marked
  failed. AI mode transition failure, insufficient VRAM, and an open circuit breaker can leave the queue
  pending or skipped without a durable reason visible at the queue/capability level.
- **The UI/API has some per-doc status surfaces, but not a capability explanation.** `vduStatus` and
  `textProvenance` exist for document inspection. The missing piece is a global/user-legible reason for
  "these documents need VDU/OCR, but the current runtime cannot do it."

### Terminology and product model

Use these terms when discussing the gap:

- **Machine-readable text**: text already present in the file format or text layer, such as a `.txt`,
  `.docx`, HTML file, or text-layer PDF.
- **Image-only/scanned document**: a PDF or image where text exists visually as pixels, but not as
  selectable/searchable text.
- **Visual document**: a broader category where useful information is visual, including screenshots,
  forms, diagrams, charts, photos, scans, and mixed layout documents.
- **Text extraction**: producing searchable text from a file.
- **Document understanding**: producing structure, layout, relationships, summaries, or meaning from the
  visual/document form.

OCR and VDU share a broad product goal: make visual/non-machine-readable material indexable and useful.
They should not be treated as the same tool:

- **OCR** transcribes visible characters. It is the cheap baseline for scanned pages, screenshots of
  text, receipts, forms, and image-only PDFs where the valuable content is still mostly text-like.
- **VDU** interprets visual documents. It can also read text, but its stronger justification is layout,
  tables, forms, charts, diagrams, mixed visual/text context, and meaning-based descriptions where OCR
  alone would miss the important structure.

Therefore the desired product model is **both OCR and VDU, selected by file/extraction state**:

1. Use normal text extraction when a good machine-readable text layer exists.
2. Use OCR when the text layer is absent/low-quality and the visual content is text-like.
3. Use VDU when layout/semantic understanding is needed, or when the content is visual but not merely
   text transcription.
4. Allow OCR and VDU to both apply to the same document: OCR provides baseline searchability; VDU adds
   richer structure/meaning when available.

## 1. Pre-implementation runtime shape

Before the implementation pass, there were three would-be document-text paths, and they were not one
coherent capability:

1. **Tika extraction in the Worker.**
   Worker ingestion called the Tika-based extractor and indexed what it got. For an image-only PDF/PNG
   in the verified fixture path, that text was empty or very short. JustSearch's Tika integration did
   not opt into OCR.

2. **VDU post-ingest extraction in the Head.**
   Worker marks low-quality eligible documents `VDU_PENDING`; later, `OfflineCoordinator` invokes
   `VduBatchProcessor`, which invokes `VduProcessor` through the llama-server vision path. This is live,
   but it depends on the AI service being brought online with a vision-capable model/projector and enough
   VRAM.

3. **OCR scaffolding in app-indexing.**
   `OcrProcessor`, `OcrGuards`, skip reasons, config keys, and metrics exist, but there is no production
   engine and no production instantiation. The scaffolding sits outside the Worker extraction flow that
   currently owns document text ingestion.

The product consequence was simple: **when Tika extracted no text and VDU could not run, the user had no
fallback that made image/scanned text searchable.** The current worktree addresses the routing and
capability-reporting part of this problem, but native OCR packaging remains open.

## 2. Pre-implementation OCR findings and disposition

- **O1 - No production instantiation.** `new OcrProcessor` and `new OcrGuards` appear in tests, not in
  production wiring.
- **O2 - No production OCR engine.** `OcrProcessor.Request` accepts a caller-supplied
  `Supplier<String> operation`; production code does not supply Tesseract, Tess4J, Tika OCR, or any
  other real engine.
- **O3 - Config is resolved but not consumed.** `index.ocr.*` keys resolve into `ResolvedConfig.Ocr`,
  but no production code uses `config.ocr()` to gate or configure extraction.
- **O4 - Tika OCR module is present but not activated.** Lockfiles include Tika's OCR parser module. The
  pre-implementation parser context configured marked PDF content extraction, but not
  `TesseractOCRConfig`, Tesseract paths, tessdata paths, language selection, or PDF OCR strategy.
- **O5 - No Tesseract executable found in this environment.** A local PATH probe found no `tesseract`.
  That does not prove the installer never bundles it, but it proves the current developer/runtime shell
  would not satisfy Tika's default external-process requirement.
- **O6 - Metrics are cataloged but dead.** `OcrMetricCatalog` is in the telemetry catalog, but
  `ocr.succeeded_total`, `ocr.failed_total`, `ocr.skipped_total`, and `ocr.time_ms` will not move until a
  production processor/guard path runs.
- **O7 - Location matters.** The existing OCR package is in `app-indexing`, while active extraction is
  Worker-side. Wiring OCR in the Head would fight the current ownership boundary unless OCR becomes a
  separate explicit post-ingest capability rather than the extraction fallback.

Current disposition:

- O1/O2/O7 are resolved for the production routing path by adding Worker-owned Tika OCR rather than
  wiring the dormant Head-side `app-indexing` OCR processor.
- O3 is resolved for production extraction/status: Worker code now consumes `config.ocr()`.
- O4 is resolved at the parser-wiring level: the Worker now configures Tika OCR and PDF OCR strategy.
- O5 is resolved for the Windows/English baseline: the app-owned runtime is discoverable, the sidecar
  bundle can include it, and packaging validation requires `eng` from `tesseract --list-langs` when the
  runtime is staged.
- O6 is resolved for production OCR: the Worker extraction route owns the live `ocr.*` metric emissions.
  The older Head-side OCR scaffold remains historical/dormant unless separately removed.

## 3. VDU findings and disposition

- **V1 - AI-offline mode transition can strand pending work.** `OfflineCoordinator.processVduPhase()`
  attempts to switch AI online. If that throws, it logs and returns. Pending docs are not reclassified
  with a reason.
- **V2 - Global VRAM gate returns before per-doc updates.** `VduBatchProcessor` checks VRAM before
  querying/processing pending docs. If the requirement is not met, it returns `0`; documents remain
  pending from the user's point of view.
- **V3 - Circuit breaker can skip the rest of a batch without durable skip reasons.** Once the breaker is
  open, the processor breaks out and leaves remaining pending docs untouched.
- **V4 - Missing mmproj disables vision capability.** `InferenceConfig` can end up with no projector
  path. `VduProcessor` then fails when vision capability is absent, or the system may never become
  suitable for VDU. If processing reaches the per-doc exception path, the document can fail; if the
  blocker is global, the queue can remain unexplained.
- **V5 - Per-doc failures are better than global blockers.** Missing source files, processor exceptions,
  blank extracted text, and update failures have explicit per-doc behavior. The weakness is not all VDU
  failure handling; it is the lack of durable state for capability-level blockers.
- **V6 - VDU mode exit remains a risk area.** `VduProcessor` attempts to leave VDU mode in a `finally`
  block and logs exit failure. A later fix should confirm whether the Brain can remain in a degraded
  vision-safe mode and whether health/repair catches it.
- **V7 - Model/profile override safety is not the main 607 problem.** Env/file overrides can still create
  mismatched VLM/model projector combinations, and `PADDLE_OCR_VL` is selectable if files exist, but the
  larger product issue is that VDU is treated as both enrichment and the only practical scanned-text
  fallback.

Current disposition:

- VDU overwrite/provenance semantics are improved: success records active VDU provenance, failed and
  completed-empty outcomes preserve baseline content, and preview no longer overclaims VDU.
- VDU global blocker producers now feed a Head-owned capability overlay for AI-offline, insufficient
  VRAM, missing mmproj, and circuit-open states. These project through status/readiness instead of
  mass-failing documents for retryable global blockers.

## 4. External research check

- Apache Tika documents OCR through Tesseract and shows that using OCR requires Tesseract installation
  plus explicit configuration for language, paths, timeout, and PDF OCR strategy:
  <https://cwiki.apache.org/confluence/display/tika/tikaocr>
- Tika's `TesseractOCRParser` Javadoc says the parser is powered by `tesseract-ocr`; enabling it requires
  a `TesseractOCRConfig` in the parse context, and Tesseract must be installed/on PATH or supplied via a
  configured root path:
  <https://tika.apache.org/3.1.0/api/org/apache/tika/parser/ocr/TesseractOCRParser.html>
- Tess4J is a Java/JNA wrapper around the Tesseract OCR API. It is a viable Java integration option, but
  it does not remove the native Tesseract/tessdata packaging problem:
  <https://github.com/nguyenq/tess4j>
- llama.cpp's multimodal docs match the JustSearch mmproj dependency: local multimodal models use
  `--mmproj`, and `--no-mmproj` disables multimodal capability:
  <https://github.com/ggml-org/llama.cpp/blob/master/docs/multimodal.md>

## 5. Adjacent tempdoc synthesis

The closest owners sharpen the scope of 607 rather than replacing it:

- **593 and 602** preserve the user-facing symptom: image/scanned text is not reliably searchable, and
  "OCR off by default" was previously treated as a product-default residue. 607 refines that into a
  routing/capability problem: the product has no active baseline path for scanned text when VDU cannot
  run.
- **589** is relevant only as a guardrail: the dormant `OcrProcessor` already had concurrency/timeout
  concerns, and native OCR can be hard to interrupt. Any real OCR path must stay inside the extraction
  sandbox/timebox discipline instead of becoming an unbounded side path.
- **598 and 600** establish the important design pattern: retrieval/AI capabilities must not overclaim.
  One observed capability truth, stable reason codes, and user-legible remedies are already the local
  answer for "feature exists but cannot currently serve."
- **612 and 613** constrain the surface. OCR/VDU capability state should not become activity-feed noise
  or a stack of toasts. It belongs in document provenance, queue/status surfaces, and the existing
  readiness/degradation vocabulary where the impact is global.
- **606 and 608** are adjacent mainly by number, but they reinforce the same method: name the missing
  axis, reuse the existing authority when it is viable, and keep the design as small as the actual
  problem allows.

## 6. Long-term design theory

The correct long-term object is a **document extraction routing authority**. It is not a new application
layer, not a new notification system, and not a replacement for Tika or VDU. It is the policy and state
boundary that decides, records, and explains how a file becomes searchable text.

### 6.1 What already exists and should be extended

The existing Worker extraction design is usable and should be extended:

- `ExtractorContributionRegistry` already gives the Worker a composable extractor authority. It currently
  has one core Tika catch-all, but the shape is right for declaring OCR as another trusted/core
  contribution when the baseline extraction result requires it.
- `ExtractionSandboxFactory`, `TimeboxedContentExtractor`, and `ExtractionArtifact` already provide the
  security, timeout, artifact-status, parser-id, policy-id, warning, truncation, and reason-code frame.
  OCR should enter through this frame or an explicitly equivalent Worker-owned frame.
- `IndexingDocumentOps` already centralizes parent document construction and VDU eligibility. The current
  `markVduIfNeeded` predicate is too narrow to be the whole routing authority, but it is the right place
  to observe the final baseline text quality and decide whether visual enrichment remains pending.
- VDU already has a Head/Brain post-ingest queue, retry count, per-document statuses, and update RPC.
  That should remain the enrichment/backfill path, not be collapsed into initial extraction.
- `/api/status` readiness composites, `LifecycleReasonCode`, `readinessNotice.CAUSE_ROWS`, and the
  `aiStateStore` verdict path already form the global capability explanation channel. VDU/OCR blockers
  should extend that vocabulary when they affect searchability; they should not invent a parallel banner
  or activity mechanism.

The existing design is therefore not obsolete. The defect is that OCR lives outside the real extraction
authority, while VDU owns some document state without owning a global capability explanation.

### 6.2 Problem statement

JustSearch needs to answer three different questions separately:

1. **What searchable text do we have now?**
2. **What better extraction/understanding work is still pending?**
3. **Can the current runtime perform that pending work, and if not, why?**

Today those questions are partially conflated. `content` holds whatever baseline Tika produced, VDU may
later overwrite it, `vdu_status` says whether VDU is pending/processing/completed/failed/not needed, and
`textProvenance` is derived mostly from VDU status. OCR is configured and metric-cataloged in fragments,
but it is not part of the production answer to any of the three questions.

### 6.3 Routing law

The durable policy should be ordered but not exclusive:

```text
machine-readable text is good enough
  -> index Tika/native text as the baseline

text layer is empty/low quality and the file is visual/text-like
  -> run OCR as the baseline text fallback when OCR capability is available

baseline text is still weak, or the document needs layout/visual semantics
  -> queue VDU enrichment when vision capability is available

VDU is blocked
  -> keep the best baseline text searchable and expose the blocker as capability state
```

The key product rule is: **OCR provides baseline searchability for text-like visual documents; VDU
provides richer visual/document understanding.** VDU can improve or supersede OCR/Tika text later, but
VDU availability should not decide whether a normal scanned page can ever become searchable.

This does not require a perfect classifier. The routing authority can start from file type, MIME, Tika
quality, page/image metadata, OCR confidence/length, and known runtime capability. What matters is that
the decision is recorded as a policy decision, not inferred later from a blank `content` field or a
stuck VDU queue.

### 6.4 State and provenance law

The current schema is close, but the meaning needs to become more general:

- `extraction_method` / parser provenance should name the active baseline text source, not only
  Tika-vs-VDU. It needs room for OCR and for "VDU superseded baseline text."
- `vdu_status` should remain VDU-specific. It is an enrichment/backfill queue state, not the whole
  extraction state.
- If OCR is synchronous/bounded inside Worker extraction, it does not need a long-lived `ocr_status` at
  first. Its outcome can live in extraction provenance, warnings, metrics, and `extraction_reason_code`.
  If OCR later becomes asynchronous, only then should it grow a VDU-like status model.
- Preview/API `textProvenance` should stop being derived solely from VDU status. It should project the
  active text source and pending enrichment separately: for example, "searchable text from OCR; VDU
  pending/blocked" is a different state from "no usable text until VDU succeeds."
- The served `content` field can remain the active searchable text, but the system should preserve enough
  provenance to know whether Tika, OCR, or VDU produced it. A full versioned text-layer store is not
  required by this tempdoc; a clear active-source plus pending/superseded enrichment record is.

### 6.5 Capability and legibility law

Capability blockers belong at two altitudes:

- **Per-document:** document-specific failures after processing starts, such as missing source file,
  OCR timeout on that document, malformed PDF, VDU empty output, or VDU max retries.
- **Queue/runtime:** global blockers that explain why pending work cannot run now, such as OCR disabled,
  OCR engine missing, OCR language pack missing, AI offline, missing mmproj/projector, insufficient VRAM,
  VDU circuit open, or VDU mode transition failure.

The queue/runtime altitude should extend the existing readiness/reason-code pattern. The user should be
able to see that search is degraded for a class of documents because a concrete capability is disabled
or missing. Unknown or future reason codes should still degrade to Health, matching the 600-era
readiness behavior.

This should not be implemented as local one-off copy in document cards, Activity feed entries, or
navigation toasts. Document inspection should show document provenance; global search trust should use
the existing status/readiness/degradation channel.

### 6.6 Ownership boundary

OCR belongs to the Worker extraction boundary if JustSearch promises scanned/text-like visual documents
are searchable without a vision model. That is where files become indexed text, where Tika already runs,
where sandbox/timebox policy exists, and where extraction artifacts become durable index fields.

The existing Head-side `app-indexing` OCR scaffolding can contribute useful ideas: guards, metrics, skip
reasons, and tests. It should not simply be wired into Head as the main fallback, because that would make
baseline searchability depend on a second post-ingest path competing with VDU. Either move/adapt the OCR
engine integration into the Worker extraction substrate, or explicitly model it as a Worker-owned
asynchronous extraction stage. In both cases, the authority remains the same.

VDU belongs to Head/Brain because it consumes the local multimodal AI runtime and competes with online AI
mode. Its output can replace or enrich searchable text, but its queue and blockers must be explained as
post-ingest enrichment capability, not hidden inside baseline extraction.

### 6.7 Long-term refinement after the implementation pass

The remaining design should complete the existing seams, not add an OCR subsystem beside them.

1. **Runtime artifact capability.** Tesseract and tessdata should be treated like other local runtime
   artifacts: llama-server binaries, model files, mmproj files, and native acceleration libraries. The
   app should have a declared install/discovery path, a bounded probe, a language-pack check, and a
   stable capability result. A config flag alone is not capability; PATH discovery alone is not product
   ownership. The long-term shape is: declared app-owned artifact first, explicit override second, system
   PATH as a development/user escape hatch, and one visual-extraction capability fact projected through
   the existing status/readiness path.

2. **Visual extraction demand.** The routing authority should separate three facts:
   - the active searchable text source (`TIKA_STRUCTURED`, `OCR_TIKA`, or `VDU`);
   - pending visual work demand;
   - current runtime capability to perform that pending work.

   The current v1 mostly uses low baseline text quality as the demand signal. That is enough for scanned
   text, but it is not the whole VDU story. Long term, there are two demands: **baseline readable text is
   missing** and **richer visual/layout understanding may be useful**. OCR can satisfy the first while VDU
   remains pending for the second. The design should record that distinction only where the product needs
   it; it should not become a universal media-understanding framework.

3. **VDU blocker projection.** VDU global blockers should be produced where they are observed:
   AI/offline transition failure in the Head/Inference boundary, missing mmproj in the model/projector
   configuration boundary, insufficient VRAM in the VRAM/runtime gate, and circuit-open in the VDU
   processor/batch boundary. They should project as queue/runtime capability facts, not as mass
   per-document failures and not as Activity/toast spam. Per-document VDU failures remain document
   state; global VDU inability remains capability state. Because those facts are observed outside the
   Worker, the long-term design needs an explicit Head-side overlay/merge into the existing
   `visualExtraction` view instead of a second VDU status API.

4. **Readiness impact.** OCR blockers are retrieval-impacting when documents lack baseline searchable
   text. VDU blockers are enrichment/AI-impacting when OCR or Tika already supplied baseline text, and
   retrieval-impacting only for documents whose usable text still depends on VDU. This may require a split
   projection rather than one permanently retrieval-owned `VISUAL_TEXT_EXTRACTION` dimension, but only if
   the existing readiness substrate cannot express the distinction cleanly.

5. **Metrics and canonical docs.** OCR metrics should follow the Worker extraction route that now emits
   the real work, even if the dormant Head-side catalog supplies names or semantics. Canonical docs should
   be promoted only after Tesseract/tessdata packaging and one real OCR-success browser validation exist,
   because otherwise the product promise would overstate current operational readiness.

## 7. Design reach: principle recognized, not generalized now

This tempdoc is an instance of a broader JustSearch rule:

> **Separate work demand, runtime capability, and active artifact provenance.**

Plainly: "there is work worth doing," "the current runtime can do it," and "this is the artifact the user
is currently relying on" are different facts. They should not be inferred from each other.

A more precise wording for 607 is: **capability truth is merged from the boundary that can observe it,
then projected through one legibility authority.** Worker can observe baseline OCR capability and text
quality. Head/Brain can observe VDU runtime blockers. The UI should see one coherent visual-extraction
capability/provenance story, not parallel local guesses.

This principle already exists elsewhere in the system:

- 598 applies it to retrieval: dense search demand/intent, dense-service capability, and actual executed
  search path must not be conflated.
- 600 applies it to degradation legibility: a cause must flow through the one readiness reason-code
  authority instead of leaking as logs or raw side-channel fields.
- 608 applies the same shape to command controls: availability before activation and busy acknowledgement
  after activation are separate axes.
- 612/613 apply it to message surfaces: durable Activity, transient push messages, and ambient state
  projections are separate surfaces over related facts, not one notification bucket.

Candidate future scope:

- visual extraction (`OCR_TIKA` baseline vs VDU enrichment);
- AI/model runtime artifacts such as llama-server, chat models, mmproj, ONNX/CUDA DLLs, and Tesseract;
- retrieval feature availability such as dense, SPLADE, reranker, and embeddings;
- background queues that can have pending work blocked by a global runtime condition.
- preview/status/API surfaces that need to distinguish active artifacts from queued improvements.

Known local violations or incomplete conformances:

- Tesseract/tessdata are not yet declared app-owned runtime artifacts, so OCR capability currently rests
  on PATH probing plus honest degradation.
- VDU global blockers still need concrete producers and a Head-side merge into the visual-extraction
  capability surface.
- VDU demand is still too close to "low baseline text quality"; it does not yet cleanly distinguish
  baseline searchability from richer visual understanding.
- The old Head-side OCR metric/processor scaffold remains a parallel-looking structure. It should be
  mined for useful semantics or retired once Worker-owned OCR metrics are real.

Do **not** build a generic "capability framework" from this recognition now. The existing readiness,
Worker status, schema provenance, and message-routing authorities are enough for 607. The principle is
recorded so future work can conform to the same shape instead of creating new side channels.

## 8. Resolved design choices and remaining design questions

Resolved by this implementation pass:

- **Product promise:** OCR is treated as baseline searchability for scanned/text-like visual documents,
  not merely optional explanation/enrichment.
- **OCR integration choice:** v1 uses Tika OCR/Tesseract in the Worker extraction path, not Tess4J and
  not the Head-side `app-indexing` OCR scaffolding.
- **Inline vs asynchronous OCR:** v1 uses inline bounded OCR during Worker extraction.
- **VDU overwrite semantics:** VDU may supersede baseline content only on successful non-empty text;
  failed and completed-empty outcomes preserve baseline content/provenance.
- **Capability impact severity:** OCR blockers can degrade retrieval only when documents still need
  visual text extraction; VDU-only blocker vocabulary exists but needs concrete producers.

Still open:

- How the Windows app should bundle, install, discover, and update Tesseract plus tessdata.
- Whether Worker OCR metrics should reuse/adapt the dormant OCR metric catalog or get a Worker-owned
  metric namespace.
- Whether VDU demand needs an explicit "visual enrichment useful" signal once OCR succeeds on text-like
  documents.
- Whether OCR should later grow an asynchronous path if inline OCR proves too slow under real packaged
  runtime validation.

## 9. Historical confidence-building pass (2026-06-19)

This pass was deliberately non-implementing. It checked the design assumptions against code, external
primary docs, and targeted existing tests before the implementation pass. Several concerns it found are
now resolved; the section remains as rationale for why the implementation took the shape it did.

### 9.1 Confidence increased

- **Worker ownership is the right default for OCR.** The Tika artifact -> validated artifact -> document
  construction path is already Worker-owned, sandboxed/timeboxed, and provenance-bearing. OCR as baseline
  searchability would fit that authority better than a Head-side post-ingest side path.
- **Readiness/reason-code extension is structurally viable.** `ReadinessDimension`,
  `LifecycleReasonCode`, `StatusLifecycleHandler`, `readinessNotice.CAUSE_ROWS`, and
  `check-readiness-reason-codes.mjs` already form a gated producer/consumer vocabulary. OCR/VDU blocker
  reasons can reuse this mechanism if their composite/severity is decided carefully.
- **Tika OCR is technically present but policy/package-heavy.** Worker runtime lockfiles include
  `tika-parser-ocr-module`, and official Tika docs confirm Tika OCR is configured through
  `TesseractOCRConfig` plus PDF `OCR_STRATEGY` / rendering settings. At the time of this pass, the
  extractor only configured marked-content PDF parsing and did not set OCR config. This validated the
  "wiring and packaging, not missing jar" conclusion.
- **No local Tesseract binary was found.** `Get-Command tesseract -ErrorAction SilentlyContinue`
  returned no executable, so a local OCR implementation must account for binary discovery/bundling and
  tessdata/language packs.

### 9.2 Confidence decreased before implementation

- **`extraction_method` was weaker than expected.** It was always written as `TIKA_STRUCTURED` during
  document construction. `SchemaFields.EXTRACTION_METHOD_VDU` existed, but a source search found no
  production writer for it, and `updateVduResult` did not update `extraction_method` when VDU replaced
  `content`.
- **Preview provenance is not a durable source-of-truth.** `/api/preview` derives `textProvenance` from
  VDU metadata only. It does not read `extraction_method`, parser id, or extraction reason fields.
- **`vdu_processed` is ambiguous.** The production update path sets `vdu_processed=true` for successful
  text, successful-empty, failed, and legacy processed outcomes. `PreviewController.computeTextProvenance`
  returns `vdu` immediately when `vdu_processed=true`, before looking at `vdu_status`. That means the
  pre-implementation provenance model could overstate VDU as the active text source for failed/empty
  outcomes.
- **Some VDU tests are weaker than they look.** `VduBatchProcessorTest` uses a local
  `TestableVduBatchProcessor` copy whose blank-text behavior marks documents `FAILED`, while production
  `VduBatchProcessor` emits `SUCCESS_EMPTY` and the Worker maps that to `COMPLETED_EMPTY`. The test is
  useful for broad flow, but it is not a strong guard for production VDU outcome semantics.

Current disposition:

- The `extraction_method`, preview-provenance, and `vdu_processed` ambiguity concerns were addressed in
  the implementation pass.
- The VDU test-shape concern is partly addressed by focused preview/provenance tests, but the deeper
  Head/Brain VDU global-blocker behavior still needs producer-level coverage.

### 9.3 Risks that remain after implementation

- **OCR packaging risk:** Tika OCR is wired, but useful OCR still depends on native Tesseract/tessdata
  packaging, language selection, and path discovery.
- **OCR latency risk:** inline bounded OCR is the v1 design, but real packaged validation may show that
  asynchronous OCR is needed for large/scanned corpora.
- **VDU blocker risk:** the reason-code vocabulary exists, but VDU global blockers are not yet fully
  projected into the visual extraction capability surface.
- **Promotion risk:** canonical docs do not yet describe the new behavior; this tempdoc remains the
  design-history surface until promotion happens.

### 9.4 Confidence checks run

- `.\gradlew.bat :modules:worker-services:test --tests "io.justsearch.indexerworker.loop.VduEligibilityPdfFixturesTest"`
- `.\gradlew.bat :modules:app-services:test --tests "io.justsearch.app.services.vdu.VduBatchProcessorTest"`
- `.\gradlew.bat :modules:ui:test --tests "io.justsearch.ui.api.PreviewControllerTest"`
- `node scripts/ci/check-readiness-reason-codes.mjs`

All passed. The UI test run emitted existing frontend build warnings, but exited successfully.

## 10. Implementation pass (2026-06-19)

The first implementation pass shipped the Worker-owned routing authority described above.

Implemented:

- Worker Tika extraction is now two-pass: structured extraction first, then bounded Tika/Tesseract OCR
  for low-quality OCR-eligible PDFs/images when OCR config and runtime probes allow it.
- OCR uses Worker extraction artifacts and parser provenance instead of the dormant Head-side
  `app-indexing` OCR processor.
- Extraction provenance now distinguishes `TIKA_STRUCTURED`, `OCR_TIKA`, and VDU-superseded text.
- VDU failed and completed-empty outcomes preserve existing content and no longer render as active VDU
  text in preview.
- Worker status now exposes visual extraction capability, including OCR enabled/available state,
  blocked reason, engine name, and count of documents still needing visual text extraction.
- Readiness reason codes and frontend cause rows now cover OCR blockers and VDU blocker vocabulary.
- The default product config now treats OCR as enabled baseline capability; if Tesseract is absent, the
  system reports `ocr.engine_missing` rather than silently behaving as if OCR were intentionally off.
- Generated status schema/types/fixtures were updated for `visualExtraction`.
- Focused tests were added for preview provenance, readiness degradation, Worker status mapping, process
  sandbox propagation, and existing extraction behavior.

Validation run:

- `.\gradlew.bat :modules:worker-services:compileJava :modules:app-services:compileJava :modules:ui:compileJava :modules:app-api:compileJava`
- `.\gradlew.bat :modules:ui:test --tests PreviewControllerTest --tests StatusLifecycleHandlerTest`
- `.\gradlew.bat :modules:app-services:test --tests WorkerStatusMapperTest`
- `.\gradlew.bat :modules:app-api:test --tests StatusRecordSchemaTest --tests StatusWireContractConformanceTest`
- `.\gradlew.bat :modules:worker-services:test --tests PolicyDrivenTikaExtractorTest --tests ProcessExtractionSandboxTest --tests ExtractionSandboxFactoryTest`
- `.\gradlew.bat :modules:app-api:updateSchemas -PupdateSchemas=true`
- `node scripts/ci/check-readiness-reason-codes.mjs`
- `npm run typecheck`
- `npm run test:unit:run`
- `npm run check:wire-schema-types-regen`
- Local backend/browser validation with a fresh dev data directory:
  - `/api/status` reported `ocrEnabled=true`, `ocrEngineAvailable=false`, `ocrEngine=tesseract`,
    `ocrBlockedReason=ocr.engine_missing`, and `visualTextNeededCount=1` when Tesseract was absent.
  - The frontend readiness surface showed "OCR is unavailable because Tesseract is not installed or not
    on PATH", with no browser console errors and no OCR toast spam.

Important limitation:

- This machine still has no `tesseract` executable on PATH, so the OCR-success path was not validated
  end-to-end with real scanned text. The implemented runtime probe does surface `ocr.engine_missing`
  instead of silently failing. Packaging/bundling of Tesseract and tessdata remains the operational
  prerequisite for making OCR baseline searchability actually work on ordinary user machines.

## 11. Remaining work

- Add OCR confidence capture from Tesseract hOCR/TSV output and feed only the compact summary into
  `visual_extraction_evidence`; do not store full OCR boxes/text metadata unless a later feature requires
  it.
- Continue improving the VDU enrichment predicate using compact evidence and user demand. Good candidates
  are charts, diagrams, screenshots, forms, low-confidence OCR, and search/preview/AI actions that show the
  user needs richer visual understanding.
- Expand preview/Health explanations only through existing provenance/readiness surfaces: text source, OCR
  language/confidence summary, pending enrichment, and blocker reason. Do not introduce feed/toast messages
  for normal backlog.
- Re-run targeted real UI validation for VDU-unavailable states. OCR-success browser validation and the
  missing-engine degraded path have both been validated; the latest browser pass also confirmed the live
  Search readiness banner.
- Add declared runtime artifacts for more OCR languages and non-Windows platforms if product scope
  requires them.
- Keep growing the small visual-text fixture corpus so future OCR/VDU routing changes can be judged by
  searchability, latency, confidence, VDU usefulness, and user-visible blocker wording.

## 14. Packaged Tesseract follow-up (2026-06-19)

This pass closed the operational gap between "Tika OCR is wired" and "the app can actually run OCR from
an app-owned Tesseract runtime."

Implemented:

- `TikaOcrRuntime` now resolves Tesseract from explicit overrides, `JUSTSEARCH_HOME`, app data homes,
  bundled headless/repo roots, and finally PATH. It also resolves sibling/explicit tessdata directories
  and uses `TESSDATA_PREFIX` when probing available languages.
- First-pass structured Tika extraction explicitly disables OCR so normal extraction cannot accidentally
  invoke ambient Tesseract before the routing authority has decided to use OCR.
- The OCR pass still owns the provenance decision; successful OCR returns `parserId=tika-policy-ocr`,
  which maps to `extraction_method=OCR_TIKA`.
- The Tauri shell restores `native-bin/tesseract` into AI Home when the bundle contains it, and launches
  Head with packaged Tesseract on PATH and packaged tessdata as `TESSDATA_PREFIX`. Tika 3.2.3 needs this
  environment projection because executable/tessdata paths live on the parser instance created from
  process configuration, not on the per-request `TesseractOCRConfig`.
- The sidecar bundle includes a locally staged `native-bin/tesseract` directory when present.
- Tests now cover app-owned runtime resolution, language probing, and optional real OCR success.

Local validation:

- Staged a local, untracked Tesseract 5.5.0.20241111 runtime under `native-bin/tesseract` from the Scoop
  package and added `eng.traineddata` from `tessdata_fast`.
- Direct packaged command succeeded: `native-bin/tesseract/tesseract.exe --list-langs` reported `eng`,
  and direct OCR over the generated fixture image returned text close to `ALPHA DOCUMENT`.
- Real Worker OCR artifact test passed when launched with packaged `PATH` and `TESSDATA_PREFIX`.
- Normal focused Worker tests passed without that environment; the real OCR test skipped as intended.
- `:modules:ui:help -PskipWebBuild=true -PskipOnnxModels=true` passed, checking the touched Gradle build
  script.
- `cargo check` for the Tauri shell was blocked before checking project code by Windows Smart App
  Control / Application Control (`os error 4551`) on a Cargo build script. This matches the known local
  build-environment blocker, not an observed Rust compile failure in the change.

Important packaging note:

- The staged `native-bin/tesseract` payload is a local ignored packaging artifact, matching the existing
  native-bin workflow. The tracked source change makes the app use such a payload when present; release
  hardening still needs pinned hashes/notices and a formal artifact-source decision.

Post-outage recovery (2026-06-20):

- Recreated the ignored app-owned runtime payload at `native-bin/tesseract` from the local Scoop
  Tesseract 5.5.0.20241111 install.
- Restored `eng.traineddata` from upstream `tesseract-ocr/tessdata_fast`; SHA-256:
  `7D4322BD2A7749724879683FC3912CB542F19906C83BCC1A52132556427170B2`.
- Direct packaged probe succeeded again: `native-bin/tesseract/tesseract.exe --list-langs` reported
  `eng`.
- Real Worker OCR artifact test passed again when launched with packaged `PATH` and
  `TESSDATA_PREFIX`.

## 15. Release hardening implementation pass (2026-06-20)

This pass completed the remaining authority edges from the final implementation plan.

Implemented:

- Tesseract 5.5.0.20241111 plus `eng.traineddata` is declared in a pinned Windows runtime manifest with
  hashes, sizes, source URLs, validation command, and notice text.
- `:modules:ui:verifyTesseractRuntime` validates the staged runtime and fails packaging when
  `tesseract.exe --list-langs` cannot see `eng`.
- Visual demand is split into baseline readable-text demand and VDU enrichment demand through
  `visualTextNeededCount`, `visualEnrichmentNeededCount`, and `vdu_demand_kind`.
- VDU global blockers are produced by Head/Brain authorities and overlaid into the visual extraction
  status/readiness path without mass-failing documents for retryable global causes.
- Worker extraction now owns the `ocr.*` metric emissions. The live Tika OCR route emits the preserved
  metric names.
- Raster image baseline extraction now suppresses ambient Tika/Tesseract OCR text. The explicit bounded
  OCR route owns image OCR text, metrics, and `OCR_TIKA` provenance.
- `ExtractorContributionRegistry` preserves underlying extraction artifacts, so the extension-substrate
  wrapper no longer collapses parser provenance to `ExtractorContributionRegistry`.
- The inspector preview renders the preview API's `textProvenance` as a visible text-source badge, so
  OCR provenance is user-visible instead of API-only.

Validation:

- Packaged runtime validation passed: `:modules:ui:verifyTesseractRuntime`.
- Focused Worker OCR tests passed, including the registry path with the packaged Tesseract environment.
- Focused UI preview test passed and `npm run typecheck` passed.
- Real dev-stack validation passed with `JUSTSEARCH_DATA_DIR=tmp/ocr-vdu-ui-validation/data` and packaged
  Tesseract on `PATH` / `TESSDATA_PREFIX`: a generated image-text file indexed as `OCR_TIKA`, searched
  successfully for `nebula inkwell`, preview returned `textProvenance=ocr`, and the browser showed the
  inspector badge `Text source OCR`.
- Browser screenshot evidence: `tmp/ocr-vdu-ui-validation/ui-ocr-validation-with-source.png`.

## 16. Quality-aware visual routing implementation pass (2026-06-20)

This pass completed the next design step: routing is no longer based only on "did Tika return enough
plain text?" or "did OCR succeed on a PDF?" It now records compact evidence for why a route was chosen and
uses that evidence to decide whether VDU is baseline-required, enrichment-useful, or not needed.

Implemented:

- Added Worker-owned `visual_extraction_evidence` as bounded JSON on extraction artifacts, validated
  artifacts, sandbox responses, Lucene documents, preview metadata, and frontend preview types.
- Added `StructuredDocumentSummary` so the existing structured extraction seam exposes page count, text
  volume, structured element counts, missing-readable pages, mixed-PDF status, and layout complexity
  without a second extraction parser.
- Added `PdfVisualAnalyzer` using PDFBox to detect text-layer coverage, image-bearing pages, and mixed
  PDFs.
- Added mixed-PDF selective OCR: when a PDF has both readable text pages and missing-readable pages, the
  Worker preserves structured text and OCRs only the missing-readable pages when the OCR runtime and guards
  allow it.
- Replaced the old broad OCR-success-PDF enrichment rule with an evidence-based predicate. Baseline demand
  means searchable text is still missing/poor; enrichment demand means baseline text exists but compact
  evidence shows useful visual/layout signals.
- Extended preview output with optional `visualExtractionEvidence`, and updated the Inspector to show a
  concise source explanation using the existing preview surface.

Validation:

- Focused Worker extraction/routing tests passed for structured extraction evidence, OCR evidence,
  mixed-PDF detection, OCR-success demand clearing, enrichment demand, low-quality baseline demand, and
  legacy pending VDU counting.
- Focused preview/API tests passed for parsed `visualExtractionEvidence`.
- Frontend typecheck and Inspector unit tests passed for the new source/evidence display.
- Schema/type gates passed: readiness reason-code governance, wire schema type regeneration check, and API
  client regeneration check.
- The live browser pass confirmed the Search surface renders with real results and the Health/Search
  degraded-readiness banner remains visible and legible. Deep Inspector validation in that browser session
  was limited by slow in-app browser shadow-DOM inspection; the Inspector evidence display is covered by
  the focused UI/unit/API tests and the earlier OCR-success screenshot pass.

Known limits after this pass, as superseded by section 18:

- OCR confidence was not extracted in this pass. Section 18 closes that gap with bounded Tesseract TSV
  capture after OCR success.
- The public evidence remains document-level and compact. Page-level routing is used internally for
  selective mixed-PDF OCR, but full per-page boxes/text are intentionally not stored.
- Enrichment detection is still heuristic. It is better than the previous placeholder, but not a complete
  visual-document classifier.

## 17. Confidence-building pass after quality-aware routing (2026-06-20)

This pass was intentionally non-implementing. It checked the remaining uncertainty after compact evidence,
mixed-PDF routing, and Inspector evidence display landed.

What is now more certain:

- **OCR confidence is technically available from the packaged runtime.** Direct Tesseract output over a
  generated image showed TSV word rows with numeric `conf` values and hOCR word spans with `x_wconf`.
  This proves a compact confidence summary can be derived without storing full OCR boxes/text metadata.
- **The current app evidence schema already has the right fields.** `visual_extraction_evidence` includes
  `ocrMeanConfidence`, `ocrLowConfidenceWordCount`, and `ocrWordCount`; at the time of this pass, the open
  gap was population, not a schema redesign. Section 18 later closed that population gap.
- **The current Tika route is text-first.** `StructuredContentExtractor.extractWithOcr` returns structured
  text through Tika's SAX path. It did not expose hOCR/TSV confidence output directly. Section 18 therefore
  chose the bounded side-call design to packaged Tesseract for confidence summaries.
- **Evidence and demand behavior still passes focused tests.** Worker extraction/routing tests passed for
  structured evidence, OCR evidence, mixed-PDF detection, OCR demand clearing, enrichment demand,
  low-quality baseline demand, and legacy pending VDU counting.
- **VDU blocker routing still passes focused tests.** Producer/status/readiness tests passed for the VDU
  capability overlay, stale blocker clearing, retrieval-vs-`aiFeatures` routing, and Health event mapping.
- **Frontend/API evidence rendering still passes focused tests.** Preview API tests, Inspector unit tests,
  readiness wording tests, and frontend typecheck all passed.
- **Packaging remains valid.** `:modules:ui:verifyTesseractRuntime` passed, so the packaged Windows English
  OCR runtime remains staged and `eng` is visible.
- **Generation gates are clean from the right package root.** `check-readiness-reason-codes.mjs`,
  `modules/ui-web` `check:wire-schema-types-regen`, and `modules/ui-web` `check:api-client-regen` passed.

What still lowers confidence:

- **OCR confidence capture needs a small integration design.** Direct Tesseract TSV/hOCR works, but adding
  it to the Worker route must avoid running OCR twice unnecessarily, must respect existing time/page/image
  guards, and must keep confidence capture non-blocking for baseline searchability.
- **The live browser validation path was blocked by backend reachability.** The existing in-app browser
  shell mounted, but its `api_port=52485` backend was unreachable (`/api/status` failed), so the browser
  stayed in Connecting state. That prevented a real browser pass for simulated OCR/VDU unavailable states
  in this run. The focused UI/API tests cover the rendering logic, but live unavailable-state validation
  still needs a healthy dev stack.
- **The enrichment predicate is still heuristic.** It is evidence-based now, but chart/form/screenshot
  usefulness will need a fixture/evaluation pass before changing queue behavior with high confidence.

Checks run:

- Direct packaged Tesseract OCR over a generated image, plus TSV and hOCR output inspection.
- `.\gradlew.bat --no-configuration-cache :modules:worker-services:test --tests
  "io.justsearch.indexerworker.extract.PolicyDrivenTikaExtractorTest" --tests
  "io.justsearch.indexerworker.loop.ops.IndexingDocumentOpsVduDemandTest" --tests
  "io.justsearch.indexerworker.services.IndexStatusOpsVisualExtractionTest"`
- `.\gradlew.bat --no-configuration-cache :modules:ui:test --tests
  "io.justsearch.ui.api.StatusLifecycleHandlerTest" :modules:app-services:test --tests
  "io.justsearch.app.services.vdu.OfflineCoordinatorTest" --tests
  "io.justsearch.app.services.vdu.VduBatchProcessorTest" --tests
  "io.justsearch.app.services.worker.WorkerStatusMapperTest" --tests
  "io.justsearch.app.services.observability.health.HealthEventEmitCoverageTest"`
- `.\gradlew.bat --no-configuration-cache :modules:ui:test --tests
  "io.justsearch.ui.api.PreviewControllerTest" --tests
  "io.justsearch.ui.api.StatusLifecycleHandlerTest"`
- `.\gradlew.bat --no-configuration-cache :modules:app-api:test --tests
  "io.justsearch.app.api.status.StatusRecordSchemaTest"`
- `.\gradlew.bat --no-configuration-cache :modules:ui:verifyTesseractRuntime`
- `npm run typecheck` in `modules/ui-web`
- `npm run test:unit:run -- InspectorPane.test.ts readinessNotice.test.ts` in `modules/ui-web`
- `node scripts\ci\check-readiness-reason-codes.mjs`
- `npm run check:wire-schema-types-regen` and `npm run check:api-client-regen` in `modules/ui-web`

Confidence rating after this pass:

**7.5 / 10 for the remaining work.** Confidence is high that the current authority is stable and that OCR
confidence can be added as compact evidence. It stays below 8 because confidence capture still needs an
integration choice, live unavailable-state browser validation needs a reachable backend, and enrichment
classification remains heuristic.

## 18. OCR confidence evidence implementation (2026-06-20)

This pass implemented the remaining compact-confidence edge without changing the OCR/VDU authority shape.

What changed:

- Added a Worker-owned Tesseract TSV confidence helper under the extraction package. It uses
  `TikaOcrRuntime.resolve()`, runs the packaged `tesseract` executable with the active OCR language and a
  bounded timeout, parses TSV word rows, and returns only a compact summary.
- Populated existing `visual_extraction_evidence` fields: `ocrMeanConfidence` normalized to `0.0..1.0`,
  `ocrLowConfidenceWordCount`, and `ocrWordCount`.
- Kept confidence optional. If the TSV side-call fails, times out, or returns no usable words, OCR text
  still indexes normally and the confidence fields are omitted.
- Wired confidence into both OCR success paths:
  - full OCR runs one optional confidence pass over the OCR source;
  - selective mixed-PDF OCR computes confidence over the rendered missing-text page images while those
    bounded temp images already exist, then aggregates the page summaries.
- Fixed the packaged-runtime resolver edge discovered during live validation: app-owned `tesseract.exe`
  now resolves its colocated `tessdata` before ambient `TESSDATA_PREFIX`, so a local Scoop/global
  Tesseract install cannot accidentally pair the packaged executable with the wrong language directory.
- Added a bounded direct Tesseract fallback behind the Worker OCR route. If Tika OCR returns blank text or
  throws on an otherwise admitted OCR image/PDF, the Worker can still use the same resolved packaged
  runtime to produce baseline searchable text. This fallback is not a second OCR authority; it is inside
  the existing `OCR_TIKA` route and shares the same guards/provenance.
- Preserved mixed-PDF evidence after selective OCR. `pagesMissingReadableText` can correctly become `0`
  after OCR fills the missing page, while `mixedPdf` stays true as a routing/explanation fact.
- Extended the evidence-based VDU demand predicate: low mean OCR confidence (`< 0.70`) or a high weak-word
  ratio (`>= 20%` below `0.60`) can queue `visual_enrichment`.
- Updated the Inspector preview source line to include OCR confidence when the preview API returns it.

Validation in this pass:

- `.\gradlew.bat --no-configuration-cache :modules:worker-services:test --tests
  "io.justsearch.indexerworker.extract.TikaOcrRuntimeTest" --tests
  "io.justsearch.indexerworker.extract.OcrConfidenceExtractorTest" --tests
  "io.justsearch.indexerworker.extract.PolicyDrivenTikaExtractorTest" --tests
  "io.justsearch.indexerworker.loop.ops.IndexingDocumentOpsVduDemandTest"` passed.
- `.\gradlew.bat --no-configuration-cache :modules:ui:test --tests
  "io.justsearch.ui.api.PreviewControllerTest" --tests
  "io.justsearch.ui.api.StatusLifecycleHandlerTest"` passed.
- `npm run test:unit:run -- InspectorPane.test.ts` passed in `modules/ui-web`; Vitest still printed
  unrelated background `ECONNREFUSED :3000` noise after the successful test result.
- `npm run typecheck`, `npm run check:wire-schema-types-regen`, and `npm run check:api-client-regen`
  passed in `modules/ui-web`.
- `node scripts\ci\check-readiness-reason-codes.mjs` passed.
- `.\gradlew.bat --no-configuration-cache :modules:ui:verifyTesseractRuntime` passed.
- Live dev-stack validation passed for baseline searchability:
  - clean stack started on API `63967` / UI `5173`;
  - generated image fixture `ocr-quartz-river-620.png` indexed as `OCR_TIKA`, returned
    `textProvenance="ocr"`, content `QUARTZ RIVER 620 / OCR ONLY TOKEN`, and evidence including
    `ocrMeanConfidence=0.96`, `ocrWordCount=6`;
  - generated mixed fixture `mixed-polaris-ledger-620.pdf` preserved digital text, appended
    `--- OCR page 2 --- POLARIS LEDGER 620 / MIXED OCR TOKEN`, returned OCR confidence evidence, and
    left VDU as enrichment demand rather than baseline text demand;
  - browser Search UI found both OCR-only tokens and showed them in result snippets.
- Live browser Inspector validation was not completed. The clean profile's welcome walkthrough overlay
  blocked Inspector/result-row activation through the in-app browser automation surface. The same preview
  path is covered by `PreviewControllerTest` and `InspectorPane.test.ts`, and the preview API returned the
  confidence evidence used by the Inspector source line.

Still deliberately not added:

- Full OCR word boxes, page-level confidence storage, and versioned OCR artifacts.
- Additional OCR languages or non-Windows runtime manifests.
- A trained or user-demand-aware VDU enrichment classifier.

## 19. OCR/VDU authority review-gap fix pass (2026-06-21)

This pass corrected the substantive mismatches found after OCR confidence landed:

- VDU enrichment no longer queues merely because OCR evidence reports an image page or
  `layoutComplexity=mixed_visual`. Plain high-confidence scanned text can remain searchable baseline text
  with `VDU_STATUS_NOT_NEEDED`; enrichment remains reserved for stronger signals such as low OCR
  confidence, high weak-word ratio, tables/forms, mixed-PDF value, or low post-OCR text quality.
- OCR-success PDF evidence now treats baseline readable text as recovered. Image-only/full-OCR PDF paths
  report `pagesMissingReadableText=0` after OCR succeeds, while selective mixed-PDF OCR still preserves
  `mixedPdf=true` as a routing/explanation fact.
- Direct Tesseract fallback is now bounded by the extraction policy. Fallback output is capped, rendered
  PDF fallback stops when the merged text reaches the cap, page images go through the existing image
  guards, and capped fallback artifacts are marked truncated.
- Explicit OCR runtime overrides are authoritative. Invalid `JUSTSEARCH_TESSERACT_PATH` /
  `justsearch.tesseract.path` reports `ocr.engine_missing` instead of falling back, and invalid/empty
  explicit tessdata or empty language probes report `ocr.language_missing`.
- The current-status summary was corrected: live browser Search validation passed; live Inspector
  validation remains a pending browser pass, with current coverage from Preview API and Inspector tests.

Validation for this pass is recorded in the implementation turn rather than implied here; the intended
focused checks are Worker OCR/routing/runtime tests, Preview/status API tests, Tesseract runtime
verification, frontend type/schema checks, and readiness reason-code governance.

## 20. Deep opportunity pass after OCR/VDU authority landed (2026-06-21)

This pass asked what the implemented routing authority makes possible now that the core design exists.
It is intentionally research/documentation only. It does not request immediate feature work.

Research method:

- Re-read this tempdoc's current-status, alignment, user-facing design, evidence, confidence, and latest
  review-gap sections.
- Re-inspected the current local seams: `VisualExtractionEvidence`, `StructuredDocumentSummary`,
  `PdfVisualAnalyzer`, `PolicyDrivenTikaExtractor`, `IndexingDocumentOps`, Preview API, Inspector source
  display, canonical extraction docs, and the OCR future-feature note.
- Checked current external signals from official docs and papers: Apache Tika PDF/OCR config,
  Tesseract command-line/data/image-quality docs, OCRmyPDF advanced OCR modes/guards, Docling and MinerU
  document-parsing docs, ColPali/ViDoRe visual document retrieval work, storage-efficient visual retrieval
  work, and multimodal document-RAG surveys.

External signals that matter now:

- Apache Tika exposes OCR rendering strategy, DPI, image type, OCR strategy, and auto-strategy controls.
  That supports the current Worker-owned route and suggests future polish should tune page/render policy
  inside the existing extractor, not build another OCR subsystem.
- OCRmyPDF treats OCR as page-aware, guarded work with skip/redo/force modes, per-page timeout, and
  image-size limits. This reinforces the current bounded inline OCR design and points toward future
  page-level decisions for mixed PDFs.
- Tesseract can emit plain text, searchable PDF, hOCR, and TSV; its docs also emphasize input quality
  and language-data choice. JustSearch should keep using TSV confidence as compact evidence, and later
  consider image-quality/preprocessing signals before adding heavier models.
- Docling and MinerU show the active direction for document parsing: reading order, tables, formulas,
  images, chart-like content, scanned-PDF detection, OCR, and structured Markdown/JSON outputs. This
  supports the idea that VDU should be triggered by layout/visual value, not by OCR success alone.
- ColPali/ViDoRe and later visual-document retrieval work show a separate future path: indexing page
  images directly for retrieval. That is not a replacement for baseline OCR in JustSearch, because it is
  heavier and less transparent, but it could become a future visual-enrichment backend.
- Storage-efficient visual retrieval research exists because page-image embeddings can be expensive.
  That is a warning against adding visual retrieval casually before the app has a clear demand signal and
  evaluation corpus.
- Multimodal document-RAG surveys keep pointing at the same hard problems: layout, tables, charts,
  granularity, efficiency, robustness, and evaluation. The current compact evidence path is the right
  place to measure those problems before choosing a larger architecture.

Local design read:

- The biggest win from the implementation is not "OCR works." It is that extraction now has an authority
  that can explain why a document used structured text, OCR, or VDU.
- The code now has enough evidence to make better routing decisions, but the evidence remains coarse.
  `pagesMissingReadableText=0` after OCR is correct for baseline searchability, while fields such as
  `mixedPdf`, `imagePageCount`, confidence, and layout complexity preserve why enrichment may still help.
- The current VDU predicate is deliberately small and testable. That is good, but it should eventually
  become a named routing/evidence decision object rather than staying as ad hoc logic inside indexing ops.
- The Inspector source line is the right first UX seam, but it is close to becoming too compressed. Future
  UX should keep one-line calm by default and add a small disclosure only when the user wants more detail.
- Health/readiness remains the right place for capability repair. Search/Chat should only show visual
  extraction problems when retrieval is actually affected.

Most useful next improvements, in priority order:

1. **Truth and test polish.** Finish the live browser Inspector pass and VDU-unavailable simulation so the
   product status matches the API/unit coverage. Keep correcting doc wording when validation is partial.
2. **Evidence clarity.** Add only small fields that answer real routing questions: OCR truncation, guard
   skip reason, language mismatch, page-render fallback used, and maybe coarse input-quality flags such as
   low DPI/very small text. Avoid storing OCR boxes or full page metadata until a feature needs them.
3. **Named visual routing decision.** Move the evidence-to-demand decision into a small Worker-owned
   routing component with a typed result: baseline text satisfied, baseline text missing, enrichment
   useful, blocker, reason. This would simplify future tests and keep `IndexingDocumentOps` from becoming
   the policy owner.
4. **Page-level internal routing.** Keep document-level fields as the public summary, but let the extractor
   decide per page for mixed PDFs: preserve good digital pages, OCR missing text pages, and mark visual
   enrichment only for pages with tables/forms/charts/low confidence.
5. **Evaluation corpus.** Build a stable fixture set before adding smarter models: high-confidence scan,
   noisy scan, mixed PDF, table/form, chart/diagram, screenshot, multilingual document, damaged text layer,
   and long PDF. Track searchability gain, OCR time, confidence, truncation, VDU usefulness, readiness
   wording, and preview clarity.
6. **User-demand-prioritized VDU.** VDU should eventually prefer documents the user searched for, previewed,
   asked Chat about, or that caused a search miss. Background enrichment-only backlog should stay lower
   priority.
7. **Language/runtime UX.** When languages expand beyond Windows English, add a Settings/Health repair
   path that shows installed OCR languages, requested languages, missing language packs, and speed/accuracy
   tradeoffs. Do not silently auto-download large language packs.
8. **Optional derived artifact, not original mutation.** A future "export/searchable PDF" or debug sidecar
   could help users verify OCR output, but JustSearch should not become a document-management archive
   unless product scope changes.
9. **Visual retrieval as a separate future capability.** If page-image retrieval becomes practical for the
   app's hardware and storage profile, treat it as a new enrichment index. Do not fold it into OCR or VDU
   text replacement semantics.

Simplifications worth considering:

- Unify duplicated readability thresholds and page-signal vocabulary across `PdfVisualAnalyzer` and
  `StructuredDocumentSummary`.
- Make the visual evidence JSON writer/parser less stringly typed at internal boundaries, while still
  storing compact JSON as the public indexed field.
- Keep route labels stable and user-copy separate: `ocr_selective` is a good machine route; "text from
  image pages" is better user wording.
- Keep OCR metrics Worker-owned, but group them in extraction observability docs by route and blocker so
  a future maintainer can see whether OCR helped, skipped, timed out, or was unavailable.

Ideas to avoid:

- Do not queue VDU for every OCR document. That would make the expensive path noisy again.
- Do not broaden this into a generic media framework. The active problem is still document text and visual
  document understanding, not all audio/video/image indexing.
- Do not add visual retrieval until there is a storage/performance budget and a local evaluation set.
- Do not expose raw internal codes as primary UX. Use "text from image", "visual understanding", and
  repair language first; keep OCR/Tika/VDU as diagnostic badges.

Short conclusion: the implementation turns visual extraction from a binary "Tika failed, maybe VDU" path
into an explainable routing authority. The best next work is not a bigger model. It is cleaner evidence,
page-aware decisions, validation breadth, and calmer user-facing explanations. Larger document-AI ideas
should be evaluated against a JustSearch fixture corpus before they become architecture.

## 21. Settled long-term design after opportunity pass (2026-06-21)

The title remains correct. The durable object is still the **document extraction routing authority**. The
next design step is not a new OCR subsystem, not a generic multimodal/media framework, and not a visual
retrieval index. It is to make the existing authority clearer, more page-aware internally, and more honest
at the user-facing projection points.

Investigation base for this pass:

- Re-read this tempdoc and the tempdoc rules.
- Re-read adjacent/relevant owners:
  - `engine-correctness-debt`: earlier Head-side OCR work had timeout/ownership problems; current Worker
    OCR route is the right authority.
  - `semantic-search-unreachable-single-gpu`: capability should be projected from the authority that can
    actually run it, not guessed by the UI.
  - `degradation-cause-not-observable`: degraded causes need one worded reason-code channel, not raw or
    forked status strings.
  - `activity-feed-non-user-action-semantics` and `notification-model-unification`: do not duplicate
    normal background state into Activity/toasts; route by meaning and altitude.
  - `ui-information-architecture-separation`: Search/Chat, Health, Activity, Settings, and Inspector each
    have different task homes.
  - `agent-ui-ux-working-capabilities`: live UI validation should combine screenshots with structured
    measurements/evidence, and stale validation claims should be corrected.
- Re-inspected the current seams in code:
  - `PolicyDrivenTikaExtractor` already owns structured -> OCR -> fallback routing and budget enforcement.
  - `VisualExtractionEvidence`, `StructuredDocumentSummary`, and `PdfVisualAnalyzer` already carry compact
    routing evidence.
  - `IndexingDocumentOps.markVduIfNeeded` currently owns the evidence -> VDU demand decision.
  - `IndexStatusOps`, `VisualExtractionStatus`, `WorkerStatusMapper`, and `StatusLifecycleHandler` already
    project baseline text demand separately from enrichment demand.
  - `VduCapabilityState` is the existing Head-owned VDU blocker overlay.
  - `PreviewController` and `InspectorPane` already expose active text provenance and compact evidence.

### 21.1 Correct long-term shape

The long-term design should settle into five responsibilities:

1. **Worker extraction owns baseline searchable text.** Structured Tika, OCR, direct bounded fallback, and
   mixed-PDF selective OCR stay under `PolicyDrivenTikaExtractor`/Worker extraction. Head/Brain must not
   become a second baseline extraction authority.
2. **Worker extraction owns compact visual extraction evidence.** The evidence remains bounded,
   document-level, and routing-focused for the public/indexed field. It can grow only by small facts that
   directly affect routing or explanation: truncation, guard skip reason, language fit, OCR fallback used,
   page-render fallback used, coarse input quality, and maybe page-summary counts. It should not store OCR
   boxes, full per-page text, or versioned text history until a concrete feature requires them.
3. **A named visual routing decision should sit between evidence and schema fields.** Today the
   evidence-to-demand logic lives directly inside `IndexingDocumentOps.markVduIfNeeded`. Long term, that
   should become a small Worker-owned decision object or helper that returns a typed answer:
   baseline text satisfied, baseline text missing, visual enrichment useful, blocker/reason, and the
   public demand kind to write. This is not a new subsystem; it is the current policy made explicit so it
   can be tested and evolved without turning indexing ops into the policy owner.
4. **Page-level routing may exist internally; document-level fields remain the public summary.** Mixed PDFs
   are the clearest reason: one page can have good digital text, another can need OCR, and another can be a
   table/chart/form that may justify VDU. The extractor can make page-level decisions under the hood, but
   the indexed contract should remain compact: active `content`, `extraction_method`,
   `visual_extraction_evidence`, `vdu_status`, and `vdu_demand_kind`.
5. **User-facing surfaces project the same facts at the right altitude.** Search/Chat show only
   retrieval-impacting blockers. Health owns capability repair and queue/blocker diagnosis. Inspector owns
   per-document provenance and evidence explanation. Activity/toasts stay quiet for normal OCR/VDU backlog.
   Settings should appear only when language/runtime choice exists.

### 21.2 What to improve next, without broadening scope

The next good design slice is **quality-aware routing polish**, not a larger architecture:

- Finish validation truth: live browser Inspector pass and VDU-unavailable UI simulation.
- Extract a named visual routing decision from `IndexingDocumentOps` while preserving the same schema fields.
- Unify the duplicated readable-page threshold/page-signal vocabulary between `PdfVisualAnalyzer` and
  `StructuredDocumentSummary`.
- Add only routing-relevant evidence fields, especially truncation and guard/fallback reason.
- Build the fixture corpus before adding smarter VDU or visual retrieval: high-confidence scan, noisy scan,
  mixed PDF, table/form, chart/diagram, screenshot, multilingual document, damaged text layer, and long PDF.
- Keep runtime expansion declared and user-visible: extra language packs and non-Windows runtimes should be
  pinned artifacts with Health/Settings repair, not implicit downloads.

### 21.3 What not to build yet

- No generic multimodal media framework. This tempdoc owns documents that need text extraction or visual
  document understanding, not audio/video/arbitrary media indexing.
- No visual retrieval index yet. ColPali-like page-image retrieval is a real future path, but it has a
  different storage/performance profile and should be evaluated against a JustSearch fixture corpus first.
- No full OCR sidecar/history store yet. Active content plus compact evidence is enough for the current
  problem.
- No Activity-feed or toast stream for ordinary OCR/VDU backlog. That would violate the notification and
  Activity principles in `notification-model-unification` and `activity-feed-non-user-action-semantics`.

### 21.4 Reach judgment: the principle and its candidate scope

This design is an instance of a broader shape already present elsewhere in the system:

**Principle: active artifact, pending demand, and capability blockers must be separate facts projected from
one authority.**

In this tempdoc:

- Active artifact: the current searchable text and `extraction_method`.
- Pending demand: `vdu_demand_kind`, `visualTextNeededCount`, `visualEnrichmentNeededCount`.
- Capability blockers: `ocr.*` and `vdu.*` reason codes.
- Projection surfaces: Search/Chat retrieval readiness, Health capability diagnosis, Inspector provenance.

The same shape appears elsewhere:

- `semantic-search-unreachable-single-gpu`: active retrieval mode, dense-serviceability demand, and
  embedder/blocker reasons must not be reconstructed separately by UI callers.
- `degradation-cause-not-observable`: a degraded capability needs one reason-code authority and one wording
  projection, not several partial vocabularies.
- `notification-model-unification`: push events and pull/ambient state are different facts; do not route a
  standing condition as a duplicate toast.
- `activity-feed-non-user-action-semantics`: the durable ledger is complete, while the default feed is only
  a projection over meaningful demand for attention.

Candidate scope beyond this tempdoc:

- Any feature that has a durable active artifact, an expensive pending improvement, and runtime capability
  blockers. Obvious candidates are dense retrieval/backfill, chunk/vector enrichment, model download/use,
  and future visual retrieval.
- Existing violations to watch for: any UI copy that infers capability from a queue count alone; any
  background task that marks per-item failure for a global retryable blocker; any duplicate toast/Activity
  row that restates a Health/readiness condition; any schema field that makes "processed" sound like "active
  artifact came from this processor."

Do not build a generalized framework now. The useful action is to keep the principle visible and apply it
locally when a concrete owner shows the same three facts. In this tempdoc, the concrete next structure is
only the named visual routing decision inside Worker extraction.

## 22. User-facing design pass after settled design (2026-06-21)

This pass re-read the current settled design and inspected the existing app UI. It did not change
implementation code. The question was whether the visual extraction authority has user-visible consequences
that the long-term design must account for.

It does. Users do not care about the internal route names first; they care whether scanned files are
searchable, whether Search/Chat is degraded, whether Health tells them what to fix, and whether the
Inspector explains where a document's visible text came from.

Live UI evidence from this pass:

- Started an isolated dev stack with `scripts/dev/dev-runner.cjs` on UI port `5173`.
- Captured browser screenshots in `tmp/tempdoc-607-ux-design/`:
  - `01-chat-search-entry.png`: Chat/Search entry with the shared degraded-readiness banner.
  - `02-system-health.png`: System Health with capability rows, queue counts, recent events, and repair
    actions.
  - `03-search-surface.png`: Search work surface with the same readiness banner but no OCR-specific
    diagnostics panel.
  - `04-search-results.png`: Search failure after the isolated dev stack exited during the pass.
- Read the nearby UI/API code paths for readiness wording, status projection, preview provenance, and the
  Inspector source line.

What the live UI showed:

- **Search/Chat already have the right altitude.** The existing degraded-readiness banner is the right
  surface only when visual extraction affects retrieval. It should not become a detailed OCR/VDU status
  panel.
- **Health already has the right repair shape.** The current Health screen has capability rows, queue
  counts, "what can I do right now?" actions, and recent events. OCR runtime/language problems and VDU
  global blockers should be projected there.
- **Search should stay work-focused.** The Search surface should warn only when scanned text may not be
  searchable. Enrichment-only VDU backlog should not make Search look broken.
- **Inspector remains the per-document explanation surface.** The code/API seam is already present through
  `textProvenance` and `visualExtractionEvidence`. This pass did not complete a live browser Inspector
  validation because the isolated dev stack exited before preview inspection. Do not claim that validation
  as done.
- **Toasts and Activity are the wrong default channel.** The observed UI already has enough transient
  navigation/toast noise. Normal OCR/VDU backlog should remain ambient Health/status state, not repeated
  user interruptions.

Correct user-facing design from this pass:

1. **Search and Chat should speak in searchability terms.** If OCR is unavailable and baseline visual text
   is still missing, say that text inside scanned/image files may not be searchable yet and link toward
   Health/repair. If OCR already produced baseline text and only VDU enrichment is pending or blocked,
   keep Search/Chat calm.
2. **Health should be the capability and repair home.** It should distinguish baseline OCR extraction from
   VDU visual understanding, show baseline-needed and enrichment-needed counts separately, and word
   blockers such as missing Tesseract, missing language data, AI offline, insufficient VRAM, missing
   vision projector, or circuit-open backoff as repairable capability state.
3. **Inspector should explain one document, not the whole system.** The compact explanation should use
   user terms first: "text layer", "text from image", and "visual understanding". Diagnostic terms such as
   OCR, Tika, and VDU can remain as badges or details. When confidence/evidence exists, show it as a
   small detail, for example OCR language, confidence, quality, and whether visual understanding is pending
   or failed.
4. **Failed or completed-empty VDU should always preserve trust.** If VDU failed or found no better text,
   the Inspector should say that the app is showing the baseline text source, not imply that VDU produced
   the active text.
5. **Settings should wait for real choice.** English Windows OCR can remain implicit. A settings surface
   becomes useful only when users can install/select OCR languages, repair runtime paths, or choose
   different OCR speed/accuracy profiles.

Design implication:

- The backend authority remains right, but each public surface needs a different projection of the same
  facts: Search/Chat for retrieval impact, Health for capability repair, Inspector for document provenance,
  and Activity/toasts only for exceptional user-action outcomes.
- The future named visual routing decision should eventually return not only machine demand fields, but
  also a small explanation class that UI copy can translate without exposing raw route codes.
- A future validation checklist for this tempdoc must include live browser coverage for Search/Chat
  degraded OCR, Health OCR/VDU blockers, Inspector OCR provenance/evidence, Inspector failed/completed-empty
  VDU, and absence of Activity/toast spam.

## 23. Confidence-building pass after user-facing design (2026-06-21)

This pass was intentionally non-implementing. It checked the remaining uncertainty after the settled
design/user-facing pass: whether the current seams and tests still make implementation low-surprise, and
whether live UI validation can now cover the previously weak points.

### What is now more certain

- **The remaining work is validation/polish, not core authority repair.** Re-reading the current tempdoc
  and code showed the expected seams still exist: Worker extraction owns baseline text/evidence,
  `IndexingDocumentOps.markVduIfNeeded` owns the current evidence-to-demand rule, Head owns the VDU
  capability overlay, `StatusLifecycleHandler` owns retrieval-vs-`aiFeatures` severity, and
  `PreviewController`/`InspectorPane` own document provenance display.
- **Focused backend/frontend tests still pin the important behavior.** The pass ran Worker extraction,
  OCR-confidence, OCR-runtime, VDU-demand, visual-status, Preview API, Status lifecycle, Worker status
  mapper, OfflineCoordinator, Inspector, readiness wording, typecheck, readiness reason-code governance,
  and Tesseract runtime verification checks. All passed.
- **Live status confirms the idle happy path.** A clean dev stack showed OCR enabled and available with
  `visualTextNeededCount=0`, `visualEnrichmentNeededCount=0`, `visualTextExtraction=READY`, and
  `visualDocumentUnderstanding=READY`. Retrieval was degraded only by chunk embeddings/LambdaMART, not by
  OCR/VDU.
- **Live Search/Chat/Health projection matches the design altitude.** Browser screenshots were captured in
  `tmp/tempdoc-607-confidence/browser/`. Chat/Search showed the shared degraded-readiness banner rather
  than an OCR/VDU diagnostics panel. Health showed capability rows, queue counts, AI offline state, recent
  events, and repair/action space.
- **Live Preview API returns the document evidence contract.** For
  `f:\justsearch\ssot\docs\help\getting-started.md`, `/api/preview` returned `textProvenance="tika"`,
  `vduStatus="NOT_NEEDED"`, and compact `visualExtractionEvidence` with `route="structured"`,
  `textQualityScore=1.0`, `pagesMissingReadableText=0`, and `layoutComplexity="none"`.

Checks run:

- `.\gradlew.bat --no-configuration-cache :modules:worker-services:test --tests
  "io.justsearch.indexerworker.extract.PolicyDrivenTikaExtractorTest" --tests
  "io.justsearch.indexerworker.extract.OcrConfidenceExtractorTest" --tests
  "io.justsearch.indexerworker.extract.TikaOcrRuntimeTest" --tests
  "io.justsearch.indexerworker.loop.ops.IndexingDocumentOpsVduDemandTest" --tests
  "io.justsearch.indexerworker.services.IndexStatusOpsVisualExtractionTest"`
- `.\gradlew.bat --no-configuration-cache :modules:ui:test --tests
  "io.justsearch.ui.api.PreviewControllerTest" --tests
  "io.justsearch.ui.api.StatusLifecycleHandlerTest" :modules:app-services:test --tests
  "io.justsearch.app.services.worker.WorkerStatusMapperTest" --tests
  "io.justsearch.app.services.vdu.OfflineCoordinatorTest"`
- `.\gradlew.bat --no-configuration-cache :modules:ui:verifyTesseractRuntime`
- `node scripts\ci\check-readiness-reason-codes.mjs`
- `npm run test:unit:run -- InspectorPane.test.ts readinessNotice.test.ts` in `modules/ui-web`
- `npm run typecheck` in `modules/ui-web`
- Clean dev-stack/browser pass with `apiPort=53887`, UI port `5173`, and isolated data under
  `tmp/tempdoc-607-confidence/data2`.

### What still lowers confidence

- **Live Inspector activation is still not proven through the browser.** The result row and preview API
  work, but in-app browser automation did not get the Inspector pane open from a live Search result during
  this pass. The welcome walkthrough overlay also remained visible after using its Dismiss control. The
  current confidence for Inspector provenance still comes from `PreviewControllerTest`,
  `InspectorPane.test.ts`, and the live `/api/preview` payload.
- **VDU-specific unavailable-state UI is still not live-simulated.** The clean stack had no pending VDU
  work and no VDU blocker, so Health did not show a concrete `vdu.*` degraded state. The backend/frontend
  severity logic is covered by unit tests, but a browser pass with real pending enrichment plus a real
  `vdu.ai_offline`, `vdu.insufficient_vram`, `vdu.missing_mmproj`, or `vdu.circuit_open` producer remains
  open.
- **The next implementation slice still needs a careful extraction, not a rewrite.** Pulling a named
  visual-routing decision out of `IndexingDocumentOps` looks feasible because the policy is already local,
  but it should be validated with regression fixtures before moving logic.
- **The UX copy still needs one small translation layer.** Internal route terms are reliable for tests and
  diagnostics, but user-facing copy should continue to say "text layer", "text from image", and "visual
  understanding" first.

### Confidence rating

**8 / 10 for implementing the remaining planned polish.** Confidence is high because the authority seams
are in place and focused tests/gates passed. It stays below 9 because live browser Inspector activation and
VDU-specific unavailable-state simulation are still not proven end to end.

## 12. Confidence-building pass for remaining work (2026-06-19)

This pass was intentionally non-implementing. It reduced surprises for the remaining work without adding
feature code.

### Evidence that increases confidence

- **Runtime-artifact precedent is strong.** The shell already creates AI Home, restores bundled
  `native-bin/llama-server` into it, preserves user-writable `models/` and `native-bin/`, sets
  `JUSTSEARCH_HOME`, and forwards native-path hints such as ONNX Runtime CUDA. The model registry already
  declares downloadable artifacts with filenames, hashes, sizes, URLs, install roots, and supporting
  files. Tesseract/tessdata should conform to this existing artifact pattern.
- **OCR packaging is feasible, but must be declared.** Tesseract and the official tessdata repositories
  are Apache-2.0. Tika exposes Tesseract executable/root and tessdata/language configuration. The risky
  part is not licensing; it is choosing a Windows binary source, version/update policy, and app-owned
  install layout. Current official Tesseract docs still route Windows users to third-party installers
  rather than a current official Windows installer.
- **Local runtime probe confirms the open gap.** No `tesseract` executable was found on PATH or in common
  Windows install directories, `TESSDATA_PREFIX` is unset, and no app-owned
  `%LOCALAPPDATA%/JustSearch/native-bin/tesseract` or `%APPDATA%/io.justsearch.shell/native-bin/tesseract`
  directory exists.
- **Current OCR code proves the next seam.** `TikaOcrRuntime` currently checks explicit
  `justsearch.tesseract.path` / `TESSERACT_PATH`, then PATH, and runs `tesseract --list-langs` for
  language probing. It does not yet know app-owned runtime roots or tessdata paths. `StructuredContentExtractor`
  configures Tika language/timeout and PDF OCR strategy, but does not set Tesseract path or tessdata path.
- **VDU blocker sources are identifiable.** `vdu.ai_offline` maps to `OfflineCoordinator` /
  `ModeTransitionException`; `vdu.insufficient_vram` maps to `VduBatchProcessor` /
  `GpuCapabilitiesService` + `VramRequirements`; `vdu.missing_mmproj` maps to
  `OnlineAiRuntimeIntrospection.hasVisionCapability()` / `InferenceConfig.mmprojPath`; and
  `vdu.circuit_open` maps to the `VduBatchProcessor` circuit breaker.
- **The wire/status surface is partially ready.** `VisualExtractionStatus` and `VisualExtractionView`
  already include `vduBlockedReason`; `WorkerStatusMapper` maps it; generated frontend schema/types include
  it; and `readinessNotice.CAUSE_ROWS` already words the VDU reason codes.

### Evidence that lowers or qualifies confidence

- **VDU blocker ownership crosses processes.** The current `visualExtraction` snapshot is sourced from
  Worker status, but VDU global blockers are observed in Head/Brain code. The implementation needs an
  explicit merge/overlay path or feedback channel. Simply "setting the Worker field" is not an available
  local edit at the observation sites.
- **Readiness consumption is incomplete.** `StatusLifecycleHandler` currently reads only
  `visual.ocrBlockedReason()` for the `VISUAL_TEXT_EXTRACTION` component. Even if producers set
  `vduBlockedReason`, the readiness envelope would ignore it until the component/composite projection is
  extended.
- **Demand semantics still need a product decision.** The current count uses pending VDU status as
  `visualTextNeededCount`. That is acceptable for "baseline visual text is missing," but it does not yet
  distinguish "OCR made the file searchable" from "VDU enrichment is still useful." This should be kept
  narrow and not generalized into arbitrary media understanding.
- **Real OCR success remains unproven.** The current machine cannot run a Tesseract-backed success test.
  The existing tests cover routing, preview provenance, readiness wording, and sandbox config propagation;
  they do not prove Tika OCR extracts real scanned text with app-owned Tesseract/tessdata.
- **Old VDU tests are stale in wording.** `VduBatchProcessorTest` still describes blank VDU output as
  `FAILED`, while production now uses completed-empty semantics through the Worker update path. The
  confidence pass did not fix that because this task was not feature implementation, but it is a test
  hygiene risk around VDU outcome semantics.
- **No live UI stack was running.** `dev-runner` reported no active run, `/api/status` and Vite were not
  reachable. The missing-engine degraded UI path was validated during the implementation pass; this pass
  only re-checked fixtures, generated schema/types, and readiness-code gates.

### Focused checks run

- `Get-Command tesseract`, `where.exe tesseract`, `TESSDATA_PREFIX`, and common Windows/app-owned
  Tesseract directories: no runtime found.
- Source reads: shell runtime restore, model registry/prerequisite checks, OCR config resolution,
  `TikaOcrRuntime`, `StructuredContentExtractor`, VDU coordinator/batch/processor paths, status mapper,
  proto/schema/type surfaces, and readiness projection.
- Focused tests:
  - `.\gradlew.bat :modules:worker-services:test --tests "io.justsearch.indexerworker.extract.PolicyDrivenTikaExtractorTest" --tests "io.justsearch.indexerworker.extract.ProcessExtractionSandboxTest"`
  - `.\gradlew.bat :modules:ui:test --tests "io.justsearch.ui.api.StatusLifecycleHandlerTest" --tests "io.justsearch.ui.api.PreviewControllerTest"`
  - `.\gradlew.bat :modules:app-services:test --tests "io.justsearch.app.services.worker.WorkerStatusMapperTest" --tests "io.justsearch.app.services.vdu.VduBatchProcessorTest"`
  - `node scripts/ci/check-readiness-reason-codes.mjs`

All passed. The UI build emitted existing Vite warnings; the app-services test run emitted existing JVM
Unsafe deprecation warnings.

### Test design for the eventual implementation

- Add a Tesseract runtime-discovery unit test using fake executable/tessdata directories to prove
  discovery order, app-owned roots, explicit override, missing-engine, and missing-language behavior.
- Add an optional/integration OCR fixture that runs only when a real Tesseract runtime is available and
  proves a scanned/image-text document produces `OCR_TIKA` searchable content.
- Add VDU blocker projection tests for each reason code at the producer/merge boundary and at
  `StatusLifecycleHandler`, including the composite-routing distinction: OCR blockers affect retrieval
  when baseline searchable text is missing; VDU-only enrichment blockers should stay out of retrieval
  unless no baseline searchable text exists.
- Add one status fixture/schema test with `vduBlockedReason` populated and one frontend readiness wording
  check so the field cannot silently become dead.

### Confidence rating

**7 / 10 for implementing the remaining work.** The architecture path is clear and existing seams are
usable. Confidence is held below 8 because the Tesseract binary/tessdata packaging source and real
OCR-success behavior are still unvalidated, and because VDU blockers cross the Head/Worker status
boundary in a way that needs a deliberate merge design.

## 15. Confidence-building pass after design refinement (2026-06-20)

This pass was also non-implementing. It checked the remaining design risks after the packaged-runtime
follow-up and after the long-term design refinement.

What is now more certain:

- **VDU blocker wiring has a clear first implementation seam.** The proto/API/type path already carries
  `vduBlockedReason`, and `WorkerStatusMapper` preserves it. The gap is not schema vocabulary. The gap is
  that the Worker currently emits OCR capability only, while Head/Brain observes VDU blockers.
- **Readiness still ignores the VDU field.** `StatusLifecycleHandler` already normalizes all VDU reason
  codes, but the `VISUAL_TEXT_EXTRACTION` component reads only `visual.ocrBlockedReason()`. A populated
  `vduBlockedReason` would reach the UI status object but not the readiness envelope until that projection
  changes.
- **The VDU blocker producers are still identifiable and local.** `vdu.ai_offline` belongs around
  `OfflineCoordinator` / `ModeTransitionException`; `vdu.insufficient_vram` belongs around
  `VduBatchProcessor` / `GpuCapabilitiesService`; `vdu.missing_mmproj` belongs around vision capability
  introspection; `vdu.circuit_open` belongs around the VDU batch circuit breaker.
- **The frontend wording path is ready.** `readinessNotice.CAUSE_ROWS` words OCR and VDU reason codes,
  and generated status types include `visualExtraction.vduBlockedReason`.
- **Real OCR success is no longer the main uncertainty.** With `PATH` and `TESSDATA_PREFIX` projected the
  same way the shell does, the real Worker OCR artifact test executed and passed against the local
  app-owned `native-bin/tesseract` payload.
- **The packaging uncertainty is policy, not mechanics.** Tesseract works when staged, but official
  Tesseract documentation still points Windows users to third-party installers for current versions.
  Release work therefore needs a deliberate artifact-source, hash, license/notice, and remediation
  decision rather than another resolver tweak.
- **OCR metrics are a relocation/ownership problem.** Worker metric catalogs and wire-format regression
  tests already exist. The dormant `app-indexing` OCR catalog has useful names and tags, but it is
  registered in Head while the live OCR route runs in Worker extraction.

What still lowers confidence:

- **The Head/Worker status merge still needs a deliberate shape.** The code has a Worker-derived
  `visualExtraction` view and Head-observed VDU facts, but no existing visual-extraction overlay object.
  Adding one is smaller than a new subsystem, but it must avoid stale blockers and double ownership.
- **Retrieval vs enrichment severity is not automatic.** The current readiness dimension permanently
  belongs to the `retrieval` composite. If VDU-only blockers should affect `aiFeatures` when OCR/Tika
  already supplied baseline text, the implementation needs either a narrow split dimension or a careful
  projection rule.
- **Demand semantics remain product-sensitive.** The current count is pending VDU documents. That is a
  weak proxy for "baseline readable text is missing" after OCR can succeed. A better split should stay
  narrow and avoid becoming a general media-understanding taxonomy.
- **Release packaging depends on an external-source decision.** The code can bundle and restore a staged
  Tesseract payload, but choosing and pinning a Windows binary source is a product/release-policy step.

Checks run:

- Direct packaged Tesseract probe without `TESSDATA_PREFIX`: executable was found but listed zero
  languages, confirming the environment projection matters.
- Direct packaged Tesseract probe with `TESSDATA_PREFIX=native-bin/tesseract/tessdata`: listed `eng`.
- `node scripts/ci/check-readiness-reason-codes.mjs` passed.
- `.\gradlew.bat :modules:ui:test --tests "io.justsearch.ui.api.StatusLifecycleHandlerTest"
  :modules:app-services:test --tests "io.justsearch.app.services.worker.WorkerStatusMapperTest"
  :modules:worker-services:test --tests "io.justsearch.indexerworker.extract.TikaOcrRuntimeTest"
  --tests "io.justsearch.indexerworker.extract.PolicyDrivenTikaExtractorTest"` passed.
- Forced real OCR proof with shell-style `PATH` and `TESSDATA_PREFIX`:
  `.\gradlew.bat :modules:worker-services:test --tests
  "io.justsearch.indexerworker.extract.PolicyDrivenTikaExtractorTest.realTesseractRuntimeProducesOcrArtifactForImageText"
  --rerun-tasks` passed.
- `npm run test:unit:run -- src/shell-v0/state/readinessNotice.test.ts` passed.

Confidence rating after this pass:

**7.5 / 10 for implementing the remaining work.** Confidence improved because the real OCR path is proven
with the packaged runtime projection and the VDU/readiness gaps are now precisely located. It stays below
8 because the Head/Worker merge, retrieval-vs-enrichment projection, and release-grade Windows Tesseract
source policy still require careful decisions before implementation.

## 13. Boundaries

- This tempdoc owns visual/text document extraction routing: Tika/native text, OCR baseline fallback, and
  VDU enrichment/understanding.
- It does not design universal multimodal indexing for audio/video or arbitrary media understanding.
- It does not replace the `ocr-scanned-documents` future-feature note; it defines the architecture needed
  before that feature can be honestly implemented.
- It does not replace tempdocs about general AI reachability, UI diagnosability, notifications, Activity
  feed semantics, or model selection. Those documents remain adjacent owners.
- All work from this takeover stayed in this tempdoc; no new tempdoc was created.
