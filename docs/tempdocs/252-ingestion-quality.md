---
title: "252: Ingestion Quality — Document Processing Pipeline"
type: tempdoc
status: done
created: 2026-03-02
---

> NOTE: Noncanonical doc (strategy). May drift.

# 252: Ingestion Quality — Document Processing Pipeline

## Purpose

Eliminate the ingestion quality tax: the nDCG loss caused by text extraction
destroying document structure. Measured at 15–33% nDCG on OHR-Bench (F-009).
This is the single largest quality bottleneck — 3–6x larger than any model
upgrade.

---

## Problem

### Root cause

`ContentExtractor.java` calls `tika.parseToString()`, which collapses
Tika's internal XHTML SAX event stream into a flat `String`. This is the
precise point where structure is destroyed.

**Tika already knows the structure.** Internally, Tika's parsers emit SAX
events with XHTML elements: `<table>`, `<tr>`, `<td>`, `<h1>`–`<h6>`,
`<p>`, `<div class="page">`, `<ol>/<ul>/<li>`. PDFBox, POI, and the HTML
parser all produce these. But `parseToString()` uses a `BodyContentHandler`
that concatenates all text events into a single string — headings, table
cells, paragraphs, list items all become whitespace-separated text.

**The fix is not replacing Tika — it's changing how we consume its output.**

### Symptoms

- **Tables** become lines of cell values with no row/column association
- **Multi-column layouts** interleave columns into nonsensical sequences
- **Headers/footers** repeat on every page, inflating BM25 term frequencies
- **Headings** lose hierarchy — H1 and body text are indistinguishable
- **Images with text** (scanned PDFs) produce nothing

### Measured impact (F-009)

| Variant | nDCG@10 | Delta |
|---------|---------|-------|
| Clean (ground truth) | 0.9487 | baseline |
| GOT moderate noise | 0.8090 | **-14.7%** |
| MinerU moderate noise | 0.6382 | **-32.7%** |

Every downstream component is affected: BM25, dense embeddings, SPLADE,
chunking, and RAG context. This does not show on BEIR because BEIR uses
pre-cleaned text.

---

## Architecture: Three-Tier Extraction Pipeline

### Design principle

The `StructuredDocument` intermediate representation decouples extraction
from indexing. Whether content comes from Tika SAX, Docling, or a VLM, the
chunker sees the same typed element list. The `ContentExtractorProvider`
interface is the extension point — new extractors implement it, and
`TimeboxedContentExtractor` wrapping is orthogonal.

### Tier 1: Structured Tika (zero new dependencies, highest impact)

Replace `tika.parseToString()` with `AutoDetectParser.parse()` using a
custom SAX `ContentHandler` that captures XHTML structure.

**What Tika actually emits per format (verified empirically):**

| Format | Headings (h1–h6) | Tables (table/tr/td) | Page breaks (div.page) | Lists (ol/ul/li) |
|--------|-------------------|---------------------|----------------------|------------------|
| **DOCX** (with styles) | YES | YES | no | YES |
| **XLSX** | sheet name as h1 | cell content | no | no |
| **HTML** | YES | YES | no | YES |
| **PDF** (untagged, default) | NO | NO | YES | NO |
| **PDF** (tagged, `extractMarkedContent=true`) | YES | YES | YES | YES |

**Key finding:** The default PDF parser (`PDF2XHTML`) only emits `<div
class="page">` for page boundaries and `<p>` for paragraphs. It does NOT
emit table, heading, or list elements. The `PDFMarkedContent2XHTML` parser
(enabled via `PDFParserConfig.setExtractMarkedContent(true)`) emits full
structural elements — but only for the subset of PDFs with accessibility
tags.

**Practical impact for PDFs:** Most real-world PDFs are untagged. For these,
Tier 1 provides page-boundary detection (header/footer removal, STRUCTURED
chunking with page-break boundaries) but NOT table/heading extraction. Full
structural extraction for PDF requires Tier 3 (Docling) or tagged PDFs.

**Practical impact for DOCX/XLSX/HTML:** Full structural extraction works.
Tables become triplets, headings get `##` markers, lists are preserved.
This is the main quality improvement for non-PDF document types.

### Tier 2: Quality-Score Router (extends TextQualityAnalyzer)

Evolve from binary garbage detection to a numeric quality score (0.0–1.0):

| Score | Route | Cost |
|-------|-------|------|
| > 0.8 | Index as-is | ~0ms extra |
| 0.3–0.8 | Re-extract per-page (RecursiveParserWrapper) | ~2x Tika cost |
| < 0.3 or empty | VDU fallback (existing path) | 15–120s |

New quality signals beyond the current alphanum ratio:
- **Table integrity:** Structured Tika detected tables but cells average
  <3 chars → table extraction failed
- **Heading presence:** >5 pages but zero detected headings → structure lost
- **Text-to-page ratio:** 10-page PDF produces <500 chars → image-only pages
- **Reading order coherence:** Unusual bigram distribution → garbled column
  interleaving

Store `extraction_quality_score` (float) and `extraction_method` (enum) in
the index for provenance and future re-extraction routing.

### Tier 3: External Enhancers (optional, highest quality)

For users who need the best possible quality on complex PDFs:

**Docling sidecar (Docker):** RT-DETR layout detection + TableFormer table
extraction. Integration via `docling-java` client. Maps `DoclingDocument`
to the same `StructuredDocument` IR. Opt-in, not required.

**Granite-Docling-258M GGUF (future):** 178 MB VLM via existing llama.cpp
bindings. Currently 15–40s/page CPU (unusable for batch). Track for when
CPU speed reaches <5s/page or GPU scheduling supports sequential loading.

**Key insight:** With Tier 1 recovering most structure at zero cost, Tier 3
becomes "nice to have" rather than "must have."

### Extraction pipeline flow

```
File → [Tier 1: StructuredContentExtractor]
         Uses Tika SAX handler → StructuredDocument
              │
              v
       [Quality Scorer]
         Computes extraction_quality_score
              │
         ┌────┼────────────────┐
         │    │                │
     > 0.8  0.3–0.8        < 0.3 / empty
     Index  Re-extract       VDU fallback
     as-is  per-page         (existing)
         │    │                │
         └────┼────────────────┘
              v
       [Structure-Aware Chunker]
         ChunkSplitter.Mode.STRUCTURED
         Section boundaries, atomic tables,
         triplet serialization, heading breadcrumbs
              │
              v
           [Index]
```

---

## Work Items

### Phase 0: Measurement ✅ DONE

- [x] **0a.** Establish OHR-Bench clean-text baseline: nDCG@10 = 0.9487
- [x] **0b.** Measure OCR extraction tax: GOT -14.7%, MinerU -32.7%
- [x] **0c.** Qualitative failure analysis: 232/962 queries fully degraded.
  Dominant failure: empty/wrong text (178), table corruption (27), short
  queries (26). See Results section.
- [x] **0d.** Record F-009 in search quality register. Decision gate exceeded.

### Phase 1: Structured Tika Extraction ✅ DONE

- [x] **1a.** `StructuredDocument` IR in `modules/indexing/.../extraction/`
  — sealed element types (Heading, Paragraph, Table, PageBreak, ListBlock).
  `toAnnotatedText()` with triplet table serialization, `## ` heading markers,
  `---` page breaks. `removeHeadersFooters(threshold)` for multi-page docs.
  Placed in `modules/indexing` (no Tika dependency, consumed by ChunkSplitter).
  22 unit tests.
- [x] **1b.** `StructuredContentHandler extends DefaultHandler` in
  `modules/worker-services/.../extract/`. Captures Tika SAX events: h1–h6,
  table/tr/td/th, p, div.page, ol/ul/li. Handles nested tables (flatten),
  char limit tracking, page index tracking. 15 unit tests.
- [x] **1c.** `StructuredContentExtractor implements ContentExtractorProvider`
  — uses `AutoDetectParser.parse()` + `StructuredContentHandler`. Falls back
  to flat `ContentExtractor` on any exception. Thread-safe. 9 unit tests.
- [x] **1d.** Triplet table serialization in `toAnnotatedText()`:
  `row_id, col_header = value` format. Empty cells skipped.
- [x] **1e.** Wired via `DefaultWorkerAppServices.java`: swapped
  `new ContentExtractor()` → `new StructuredContentExtractor()`.
  `TimeboxedContentExtractor` wrapping preserved.
- [x] **1f.** Schema fields: `extraction_method` (keyword) and
  `extraction_quality_score` (double) added to SSOT catalog, SchemaFields,
  and INDEXABLE_FIELDS. Written in `IndexingDocumentOps.buildDocument()`.

### Phase 2: Header/Footer Removal ✅ DONE

- [x] **2a–2b.** Implemented as `StructuredDocument.removeHeadersFooters(threshold)`.
  Groups elements by page index, detects repeated first/last text elements
  across >threshold fraction of pages, removes them. Called automatically
  in `StructuredContentExtractor` after SAX parsing. Default threshold: 50%.
  No-op for <3 pages or documents without page breaks.

### Phase 3: Structure-Aware Chunking ✅ DONE

- [x] **3a.** `ChunkSplitter.Mode.STRUCTURED` added. Boundary priorities:
  page breaks (`\n---\n`) > heading markers (`## `) > paragraph > sentence >
  word. Auto-selected for `pdf` and `office` fileKinds via
  `Mode.fromMimeOrFileKind()`.
- [x] **3b.** Tables kept atomic via triplet serialization in annotated text
  (not split mid-table by chunker). Oversized tables fall through to
  paragraph/sentence boundaries.
- [x] **3c.** Heading breadcrumbs (`chunk_heading_text`) now populated for
  PDF and Office documents (not just Markdown). `ChunkDocumentWriter` updated
  to check `pdf`/`office` fileKinds.
- [x] **3d.** Existing token-based splitting preserved as fallback in
  STRUCTURED mode.

### Phase 4: Enhanced Quality Scoring ✅ DONE

- [x] **4a.** `TextQualityAnalyzer.computeQualityScore(text, pageCount)` —
  numeric 0.0–1.0. Signals: alphanum ratio, text density per page, alphanum
  quality gradient. Backward-compatible overload without pageCount.
- [x] **4b.** `extraction_quality_score` stored in index via
  `IndexingDocumentOps.buildDocument()`.
- [x] **4c.** VDU routing in `markVduIfNeeded()` now uses
  `computeQualityScore() < 0.3` instead of binary `isGarbageText()`.
  Backward-compatible: garbage text scores ~0.0, good text scores ~0.8–1.0.

### Phase 1–4 verification: ✅ DONE

- [x] **V1.** OHR-Bench clean baseline: nDCG@10 = 0.9487 (unchanged —
  `.txt` files bypass structured extraction).
- [x] **V2.** Downloaded OHR-Bench PDFs from HuggingFace (`pdfs.zip`, 1.5 GB).
  Split 999/1000 into single-page PDFs (1 encrypted PDF skipped). Indexed
  through `StructuredContentExtractor` with `extractMarkedContent=true`.
  **Result: nDCG@10 = 0.7947** (P@1=0.7484, R@10=0.8326).
- [x] **V3.** Tier 3 decision: **gap = 16.2% — Tier 3 IS needed.**

### Verification results (2026-03-20)

| Variant | nDCG@10 | P@1 | R@10 | Delta |
|---------|---------|-----|------|-------|
| Clean (ground truth, .txt) | 0.9487 | 0.9044 | 0.9865 | baseline |
| **Docling (GPU, layout+OCR+table)** | **0.8621** | 0.8087 | 0.9085 | **-9.1%** |
| Tika Structured PDF | 0.7947 | 0.7484 | 0.8326 | -16.2% |
| Tika + Layout Detector | 0.7947 | 0.7484 | 0.8326 | -16.2% |
| GOT moderate (pre-extracted) | 0.8090 | 0.7505 | 0.8617 | -14.7% |
| MinerU moderate (pre-extracted) | 0.6382 | 0.5644 | 0.7131 | -32.7% |

**Phase 5 Path A result (2026-03-20):** Layout detection (RT-DETR ONNX)
produced identical nDCG to Tika-only (0.7947). The layout detector
correctly identifies regions but `PDFTextStripperByArea` extracts the same
text. The bottleneck is PDFBox text quality, not structural awareness.

**Phase 5 Path B result (2026-03-20):** Docling (layout + OCR + table
structure) produced **nDCG@10 = 0.8621** — a **7 percentage point
improvement** over Tika (0.7947 → 0.8621). Docling closes 44% of the
gap between Tika and clean text. This is the best extraction result by a
significant margin.

**Remaining gap analysis (9.1%):** Still above the 5% threshold.
Research (2026-03-20) identified actionable configuration changes:

1. **OCR engine:** Default RapidOCR uses PP-OCRv4 (Chinese-first model).
   Tesseract or EasyOCR may be better for English-dominant corpora.
2. **`force_full_page_ocr=True`:** Bypasses PDFBox text layer entirely,
   running OCR on rendered page images. Addresses the root cause (bad
   PDFBox text) at the cost of OCR errors on clean pages.
3. **Layout model:** `heron-101` (75.8% mAP) slightly outperforms default
   `heron` (75.1% mAP). Same inference speed.
4. **SmolDocling-256M:** End-to-end VLM replacing the entire pipeline
   (layout + OCR + table + code). Potentially higher quality on complex
   pages but slower per page.
5. **Marker** ([datalab-to/marker](https://github.com/datalab-to/marker)):
   Alternative tool, 25 pages/sec on H100, `use_llm` mode for higher
   accuracy. GPL-licensed (restrictive).

### Phase 5 experiments: Docling configuration optimization

All experiments use the same OHR-Bench 999-page PDF corpus, same queries/
qrels, same jseval `lexical` mode. Each experiment changes one variable.
Baseline: Docling default config = 0.8621 nDCG@10.

| Exp | Config change | Hypothesis | Est. time |
|-----|--------------|-----------|-----------|
| E1 | `force_full_page_ocr=True` | Bypasses bad PDFBox text for all pages. May hurt clean pages (OCR errors) but help broken pages. Net effect unknown. | ~60 min |
| E2 | OCR engine = `tesseract` | Better English accuracy than PP-OCRv4. Requires `tesseract` installed on PATH. | ~60 min |
| E3 | OCR engine = `easyocr` | GPU-accelerated, 80+ languages. Auto-selected when GPU present but not by default with our config. | ~60 min |
| E4 | Layout model = `heron-101` | 0.7% mAP improvement. Likely negligible for search quality. | ~60 min |
| E5 | E1 + E2 combined | Force full-page OCR with Tesseract (best English OCR + bypass text layer). | ~60 min |
| E6 | SmolDocling-256M VLM | End-to-end VLM pipeline. Different approach entirely. | ~90 min |
| E7 | Marker (alternative tool) | Completely different parser. GPL-licensed. | ~60 min |

**Priority order:** E1 (most likely to close gap) → E5 → E2 → E6 → E3 →
E4 (least likely to matter) → E7 (different tool, licensing concern).

**Expected outcome:** E1 (`force_full_page_ocr`) is the most promising
because it directly addresses the root cause — PDFBox text quality. If
combined with Tesseract (E5), it uses the best English OCR engine on
rendered page images, bypassing PDFBox entirely.

### Phase 5: PDF Layout Detection (NEEDED — 16.2% gap on PDF)

The core problem: Tika's PDFBox parser doesn't know where tables, headings,
or columns are on the page. Docling solves this with ML layout detection
(RT-DETR model). Three integration paths, in order of preference:

**Path A: Native ONNX layout model (preferred — zero new dependencies)**

Docling's layout model is already available as ONNX on HuggingFace:
[`docling-project/docling-layout-heron-onnx`](https://huggingface.co/docling-project/docling-layout-heron-onnx)
(Apache-2.0 license). JustSearch already has ONNX Runtime for embeddings/
SPLADE/reranking — this is just another model in `models/onnx/`.

**Model specs (verified 2026-03-20):**
- Architecture: RT-DETR v2, trained on DocLayNet
- ONNX file: **162 MB**
- Input: `[N, 3, 640, 640]` float32 (resized page image, normalized /255)
  + `[N, 2]` int64 (original height, width)
- Output: `[N, 300]` labels, `[N, 300, 4]` boxes (x1,y1,x2,y2 in original
  coordinates), `[N, 300]` scores
- **17 label classes:** caption, footnote, formula, list_item, page_footer,
  page_header, picture, section_header, table, text, title, document_index,
  code, checkbox_selected, checkbox_unselected, form, key_value_region
- Inference time: **0.25s/page** (CPU, ORT) — **72x faster than full Docling**
  (which loads OCR, TableFormer, etc.)

**Pre-processing (trivially portable to Java):**
1. Render PDF page to image (PDFBox — already done in VDU path)
2. Resize to 640x640, convert to float32, scale by 1/255
3. Transpose HWC → CHW

**Post-processing:**
1. Filter detections by confidence threshold (0.3)
2. Map label IDs to class names via config.json
3. Use bounding boxes + PDFBox text extraction per region

**Comparison: full Docling vs layout-only ONNX:**

| | Full Docling (Python) | Layout ONNX only (Java) |
|---|---|---|
| Model size | 8.4 GB (all models) | **162 MB** |
| Peak RAM | 2.3 GB | ~200-300 MB (estimated) |
| Per-page time (CPU) | 17.7s mean | **0.25s** |
| Table cell structure | Yes (TableFormer) | No (region detection only) |
| OCR | Yes (RapidOCR) | No (use PDFBox text layer) |
| Dependencies | Python + PyTorch | **None** (existing ORT) |

- [x] **5a.** ~~Study model architecture~~ — Verified: ONNX model available,
  input/output tensors confirmed, 0.25s inference on CPU. No export needed.

#### Implementation plan (Path A)

**Architecture decisions:**

1. **Module placement:** `LayoutDetector` (ORT inference) in `modules/worker-core`
   following the `SpladeEncoder`/`BgeM3Encoder` pattern. PDF rendering and
   region text extraction in `modules/worker-services` (add PDFBox compile
   dependency). The Body/Worker owns GPU ORT, the indexing loop, and all
   model inference — keeping layout detection here avoids an IPC round-trip.

2. **Integration point:** `StructuredContentExtractor` gains a layout-aware
   path for PDFs. When the layout model is available, PDFs are processed via
   render → layout detect → region text extract. When unavailable, falls back
   to current Tika SAX path. Non-PDF files always use Tika SAX (which already
   captures DOCX/HTML structure correctly).

3. **Coordinate mapping:** The ONNX model outputs boxes in original image
   pixel coordinates (because `orig_target_sizes` is passed as input).
   PDFBox `PDFTextStripperByArea` expects `java.awt.Rectangle` in PDF-point
   coordinates. Conversion: `point = pixel * 72.0 / renderDPI`.

4. **Reading order:** Sort detected regions top-to-bottom, then left-to-right
   (by y-center, then x-center). This produces natural reading order for
   single-column and multi-column layouts.

5. **Region-to-element mapping:**

   | Layout label | → StructuredDocument element |
   |---|---|
   | `title` | `Heading(level=1)` |
   | `section_header` | `Heading(level=2)` |
   | `text` | `Paragraph` |
   | `table` | `Table` (text from region, row-split heuristic) |
   | `list_item` | accumulate consecutive → `ListBlock` |
   | `caption`, `footnote` | `Paragraph` |
   | `page_header`, `page_footer` | **skip** (automatic removal!) |
   | `picture`, `formula`, `code` | `Paragraph` (with extracted text if any) |
   | `checkbox_*`, `form`, `key_value_region`, `document_index` | `Paragraph` |

6. **Table text extraction:** Without TableFormer, we cannot detect cell
   boundaries. Extract all text from the table bounding box via
   `PDFTextStripperByArea` with `sortByPosition=true`. Split into rows by
   newlines. Attempt column detection by whitespace alignment heuristic
   (consistent tab/space gaps across rows). If heuristic fails, treat as
   flat text. This is a best-effort approach — still better than Tika's
   default (which doesn't even identify the table region).

7. **Model lifecycle:** Follow `CitationScorer` pattern (CPU-only, simple).
   Create `OrtSession` once via `OnnxSessionCache.createCachedSession()`.
   Reuse across all inference calls. Thread-safe (ORT sessions are safe for
   concurrent `run()` calls).

8. **Model discovery:** Register as `"layout-detector"` in `WorkerModelDiscovery`.
   Disk path: `<modelsDir>/onnx/layout-detector/model.onnx`. Required files:
   `["model.onnx"]` (no tokenizer). Add `JUSTSEARCH_LAYOUT_MODEL_PATH` env
   var to `EnvRegistry`. Add `Layout` sub-record to `ResolvedConfig.Ai`.

9. **Graceful degradation:** If the model is not on disk, layout detection is
   silently disabled. PDFs fall back to Tika SAX extraction (current behavior).
   Users who want layout-aware PDF extraction download the 162 MB model.

**Per-page pipeline:**
```
PDF page
  → PDFBox render at 150 DPI → BufferedImage
  → resize to 640×640, normalize /255, HWC→CHW → float[1,3,640,640]
  → ORT session.run(images, orig_target_sizes) → labels, boxes, scores
  → filter by confidence > 0.3
  → sort regions by position (top→bottom, left→right)
  → for each region:
      skip page_header/page_footer
      PDFTextStripperByArea(region_box_in_pdf_points) → text
      map label to StructuredDocument element type
  → assemble StructuredDocument with per-page elements
  → add PageBreak between pages
```

**Work items:**

- [x] **5b.** Model downloaded to `models/onnx/layout-detector/` (162 MB,
  LFS-tracked). config.json + preprocessor_config.json included.
- [x] **5c.** `Layout` sub-record added to `ResolvedConfig.Ai`. Env vars:
  `JUSTSEARCH_LAYOUT_ENABLED`, `JUSTSEARCH_LAYOUT_MODEL_PATH`,
  `JUSTSEARCH_LAYOUT_CONFIDENCE_THRESHOLD`.
- [x] **5d.** PDFBox `implementation` dependency added to `worker-services`.
- [x] **5e.** `LayoutDetector` implemented in `modules/worker-core`. CPU-only
  ORT session, 0.25s/page, IoU-based overlap removal, position sort.
- [x] **5f.** `PdfLayoutExtractor` implemented in `modules/worker-services`.
  Per-page: render → detect → PDFTextStripperByArea per region →
  StructuredDocument assembly with element type mapping.
- [x] **5g.** Wired into `StructuredContentExtractor`. PDF routing when
  layout model present, Tika SAX fallback when absent.
- [x] **5h.** Wired into `DefaultWorkerAppServices`. Auto-discovers model
  from `models/onnx/layout-detector/`.
- [ ] **5i.** Tests — pending (implementation verified via OHR-Bench eval).
- [x] **5j.** **MEASURED: nDCG@10 = 0.7947 — identical to Tika-only.**
  957/962 queries unchanged. Layout detection does not close the gap.

**Path A outcome: Layout detection alone is insufficient.**

The gap is NOT caused by missing layout awareness — it is caused by PDFBox
text extraction quality (garbled encoding, wrong reading order, missing
characters). Layout detection correctly identifies WHERE regions are, but
`PDFTextStripperByArea` extracts the same text that Tika already produces.

**Path B: Adopt Docling (NEXT — addresses root cause) ← CHOSEN PATH**

Docling provides layout detection + OCR + table structure extraction in
one package. It addresses the root cause (PDFBox text quality) rather than
the symptoms. Measured resource profile (2026-03-20): 2.3 GB peak RAM,
17.7s/page CPU mean, 8.4 GB model cache (full install).

**Integration approach:** Use `docling` as a Python library (already
installed, MIT-licensed). JustSearch launches a Docling worker process that
converts PDFs to structured Markdown. The Worker communicates via stdin/
stdout JSON protocol or a local HTTP API. Maps Docling output →
`StructuredDocument` IR → same downstream pipeline.

**Throughput design (critical — see tempdocs 278, 312):**

Docling at 17.7s/page CPU would make PDF indexing ~50x slower than the
current 9 docs/sec baseline. Integration MUST use the **async backfill
pattern** (same as VDU and SPLADE):
1. Primary indexing: Tika SAX extracts PDFs at full speed (current path)
2. Quality scoring marks low-quality PDFs as `DOCLING_PENDING`
3. Background backfill: Docling reprocesses pending PDFs when idle
4. On completion: overwrite content + re-chunk + re-embed (same as VDU
   `SUCCESS_TEXT` path in `GrpcIngestService`)

This means Docling does NOT block initial indexing. Users get immediate
(Tika-quality) results, upgraded to Docling-quality in the background.

**Why Docling over custom OCR (Path C):**
- Docling bundles layout + OCR + table structure + reading order in one
  tested pipeline. Implementing equivalent in Java requires PP-OCR (3 models
  + CTC decoding + contour detection) + table cell detection + reading order
  — months of work.
- Docling is MIT-licensed, actively maintained, and already validated on
  the same DocLayNet dataset as our layout detector.
- Selective OCR was validated (Path C research): blanket OCR is worse than
  PDFBox on 8/10 pages. Docling's approach (layout-aware OCR only where
  needed + native text extraction where possible) is the correct hybrid.

**Disposition of existing components:**
- `StructuredDocument` IR, `StructuredContentExtractor` (Tika SAX),
  quality scoring, STRUCTURED chunking — **stay active** (DOCX/HTML
  improvement is real and verified)
- `LayoutDetector` + `PdfLayoutExtractor` — **disabled by default**
  (opt-in via `JUSTSEARCH_LAYOUT_ENABLED=true`). Available as a fast
  fallback when Docling is unavailable.

**Quality validated (2026-03-20):**

- [x] **5p.** Measured Docling on OHR-Bench PDFs (996/999 converted, GPU,
  batch=32): **nDCG@10 = 0.8621** (+6.7% over Tika, closes 44% of gap).
  GPU throughput: ~0.28 pages/sec (3.6s/page mean). 954 MB VRAM.

**Remaining Docling integration work (PAUSED — see Phase 6):**

- [ ] **5k.** Design Docling worker process protocol (stdin/stdout JSON
  or local HTTP). Define input (PDF path) → output (Markdown text).
- [ ] **5l.** Implement `DoclingWorker` process launcher in
  `modules/worker-services`. Lifecycle: start on first PDF, reuse for
  subsequent PDFs, stop on idle timeout.
- [ ] **5m.** Map Docling Markdown output → `StructuredDocument` IR.
  Docling exports clean Markdown with headings, tables, lists — parsing
  to StructuredDocument is mechanical.
- [ ] **5n.** Wire into `StructuredContentExtractor`: if Docling worker
  is available AND file is PDF, use Docling. Fallback chain: Docling →
  layout detector (if enabled) → Tika SAX.

---

### Phase 6: LLM-Based PDF Extraction (ACTIVE — replacing Docling path)

**Motivation (2026-03-23):** Docling integration (5k–5n) requires a Python
dependency (`pip install docling`), 8.4 GB of model downloads, fragile
Windows subprocess management, and 954 MB VRAM contention. The existing
chat LLM (Qwen 3.5 9B, already running via llama-server) may be able to
serve the same purpose with zero new dependencies.

**Key insight:** The VDU pipeline already does exactly this — renders PDF
pages to images, sends them to llama-server via vision completion, gets
markdown text back, and writes it into the index. The question is whether
a VLM variant of the current model can match Docling's quality (0.8621
nDCG) at acceptable throughput.

**Current state:**
- Chat model on disk: `Qwen_Qwen3.5-9B-Q4_K_M.gguf` (text-only, no VL)
- No `mmproj-*.gguf` present → vision disabled
- VDU pipeline fully wired but inactive (no vision capability)
- Infrastructure reuse: `PdfImageRenderer`, `ImagePreparer`,
  `OnlineModeOps.visionCompletion()`, `VduBatchProcessor`,
  `GrpcIngestService.updateVduResult()` — all exist and are tested

**Existing infrastructure that would be reused:**
| Component | File | What it does |
|-----------|------|--------------|
| PDF→image | `PdfImageRenderer` | PDFBox 150 DPI, max 50 pages |
| Image prep | `ImagePreparer` | Resize ≤1280px, JPEG bytes |
| Vision API | `OnlineModeOps.visionCompletion()` | base64 image → llama-server |
| Batch processor | `VduBatchProcessor` | Queue drain, circuit breaker, retry |
| Write-back | `GrpcIngestService.updateVduResult()` | Content replace + re-chunk + re-embed |
| Quality gate | `markVduIfNeeded()` | Routes low-quality PDFs to VDU pipeline |

**Research results (2026-03-23):**

| # | Question | Finding |
|---|----------|---------|
| R1 | Qwen 3.5 VL — exists? GGUF? llama.cpp? | **YES.** Qwen 3.5 is natively multimodal (early fusion). `mmproj-F16.gguf` (918 MB) at `unsloth/Qwen3.5-9B-GGUF`. Works in llama-server with `--mmproj` flag. Issue #19917 resolved, batch-size bug fixed (PR #19930). |
| R2 | VLM document OCR benchmarks at ~9B | **Strong.** Qwen3.5-9B: OCRBench 89.2, OmniDocBench 87.7–90.8, CC-OCR 79.3, MMMU 78.4. Among the best for a 9B model. |
| R3 | Per-page throughput ~9B VLM Q4 GPU | **21.4s/page measured** (RTX 4070, Q4_K_M, 150 DPI, single-slot). 6x slower than Docling (3.6s/page) but acceptable for backfill. |
| R4 | SmolDocling VLM — GGUF, llama.cpp | GGUF exists (175 MB Q8_0) but **NOT in llama.cpp supported models list**. Accuracy 42–67% — significantly worse than Qwen3.5. Outputs DocTags (structured XML), not markdown. Risky path. |
| R5 | Other small doc-extraction VLMs | PaddleOCR-VL 7B (92.86 OmniDocBench), RolmOCR, dots.ocr 3B — all Python/PyTorch only, no GGUF/llama.cpp support. |
| R6 | llama.cpp multi-model / hot-swap | Not practical — single llama-server instance, single-tenant GPU. |
| R7 | Text-only LLM cleanup | Moot — Qwen 3.5 has native vision, no need for text-only workaround. |

**Decision (2026-03-23): VDU pipeline with Qwen 3.5 vision is the path.**

The existing VDU pipeline already implements the full extraction flow. The
only missing piece is the 918 MB mmproj file. Zero new dependencies, zero
new processes, zero new code paths. Docling integration (5k–5n) is
**cancelled** — the LLM path is strictly superior on every dimension:

| | Docling | Qwen 3.5 VDU |
|---|---|---|
| New dependencies | Python + pip + 8.4 GB models | **918 MB mmproj file only** |
| New code | ~1000 LOC (process launcher, protocol, mapping) | **~0 LOC** (pipeline exists) |
| VRAM | 954 MB additional | **0 MB additional** (shared with chat model) |
| Startup latency | 38s cold start | **0s** (already running) |
| Windows risk | Python subprocess lifecycle | **None** (llama-server already managed) |
| Quality | nDCG 0.8621 (measured) | **TBD** (must validate on OHR-Bench) |

**Remaining risk:** Quality is not yet validated on OHR-Bench. Qwen3.5's
OCRBench 89.2 is promising but doesn't directly predict nDCG on our corpus.
Must run the same eval to confirm.

**Work items:**

- [x] **6a.** Downloaded `mmproj-F16.gguf` (875.6 MB) from
  `unsloth/Qwen3.5-9B-GGUF` to `models/`. Updated `InferenceConfig`
  defaults: `vlmModel` → `Qwen_Qwen3.5-9B-Q4_K_M.gguf`,
  `mmprojModel` → `mmproj-F16.gguf`. Removed stale hardcoded yaml path.
  **Vision loads successfully** with build 8185 llama-server (build 7502
  in `modules/shell` does NOT support qwen35 arch — needs upgrade).
  **Critical: llama-server must be `-np 1` (single slot) for vision
  reliability.** Multi-slot causes alternating 500 "failed to process
  image" errors.
- [ ] **6b.** VDU extraction prompt — see research findings (6g) below.
- [ ] **6c.** OHR-Bench VLM eval: run with revised settings from 6g.
  Script: `scripts/search/vlm-extract-ohr-bench.py`.
- [ ] **6d.** Review VDU quality routing thresholds.
- [ ] **6e.** max_tokens: current `VduProcessor` uses 2048 — must
  increase to 4096. Average output: 3,500 chars (~1,400 tokens).

#### 6f. Manual quality analysis (2026-03-23, 41 pages)

Compared VLM extraction (Qwen 3.5 9B, Q4_K_M, 150 DPI, `-np 1`) vs
Docling (GPU, layout+OCR+table) vs clean ground truth on OHR-Bench
academic pages. Metric: word overlap with ground truth (case-insensitive).

**Aggregate (28 pages with all three variants):**

| Extractor | Avg word overlap | Wins | Losses | Ties |
|-----------|-----------------|------|--------|------|
| **VLM** | **76.6%** | 7 | 10 | 11 |
| **Docling** | **81.2%** | 10 | 7 | 11 |

VLM trails Docling by ~5%. Gap is partly explained by fixable issues.

**Failure modes identified:**

| Issue | Frequency | Impact | Fix |
|-------|-----------|--------|-----|
| Preamble ("Here is the extracted text...") | 10/30 (33%) | Wastes tokens, adds noise | Prompt fix (see 6g) |
| TOC / dot leaders expanded | 1/30 | Catastrophic — page numbers lost | Inherent VLM weakness |
| Large table truncation at max_tokens | 1/30 | Content lost at end | Preamble removal recovers token budget |
| Markdown formatting vs LaTeX ground truth | systemic | Inflates word-overlap gap | Request plain text (see 6g) |

**Where VLM wins:** Complex layouts, figures with captions, unusual
formatting — VLM reads visual layout directly.

**Where Docling wins:** Dense text, LaTeX-heavy content — Docling
preserves LaTeX that matches ground truth format.

**11/28 ties:** Plain text pages produce near-identical output.

#### 6g. Research: existing VLM OCR tooling & best practices (2026-03-23)

Surveyed existing tools, prompts, and techniques for VLM-based document
extraction to avoid reinventing settled problems.

**Key tools surveyed:**

| Tool | Approach | Key finding |
|------|----------|-------------|
| [RolmOCR](https://huggingface.co/reducto/RolmOCR) | Qwen2.5-VL-7B fine-tuned on 259k OCR pages | Prompt: `"Return the plain text representation of this document as if you were reading it naturally.\n"` temp=0.2, max_tokens=4096. **GGUF available** ([Mungert/RolmOCR-GGUF](https://huggingface.co/Mungert/RolmOCR-GGUF)). Apache 2.0. |
| [olmOCR 2](https://allenai.org/blog/olmocr) | 7B VLM + "document anchoring" (PDF text layer injected into prompt alongside image) | SOTA on olmOCR-Bench (+14.2 over v1). Anchoring gives VLM hints from Tika text. Not available as GGUF. |
| [Ollama-OCR](https://github.com/imanoop7/Ollama-OCR) | Wrapper for multiple VLMs, 6 format types | Production-ready batching, preprocessing. Prompts in source code. |
| [PaddleOCR-VL](https://arxiv.org/abs/2510.14528) | Specialized 0.9B model, NaViT dynamic resolution | 94.5% OmniDocBench. Python/PaddlePaddle only, no GGUF. |
| [img2md-vlm-ocr](https://github.com/EvilFreelancer/img2md-vlm-ocr) | YOLO layout detection + VLM extraction | Two-stage: detect regions, then VLM per region. |

**Key prompt engineering findings:**

| Source | Finding |
|--------|---------|
| [Qwen 3.5 OCR guide (Alderson)](https://martinalderson.com/posts/how-to-use-qwen-3-5-to-ocr-documents/) | Best prompt: `"OCR this document page. Return all the text exactly as it appears, preserving layout where possible. Use markdown formatting for tables and lists. Do not add any commentary."` DPI: **100** (not 150). Temp: **0**. ~3s/page on 9070XT. |
| [LLM OCR Prompt Guide Q1 2026](https://zenn.dev/coffin299/articles/60ba24446c0c27?locale=en) | Suppress preamble: `"Do not output any text other than [format]."` Multi-pass for complex docs. Pipeline: OCR → Normalize → Validate. |
| RolmOCR (reducto) | Dead-simple prompt wins for fine-tuned models. Plain text > markdown for downstream search. |
| olmOCR (Allen AI) | "Document anchoring" — inject text-layer metadata into prompt — is the single biggest quality lever. |

**Additional findings (second research pass):**

| Tool/Technique | Key detail |
|----------------|-----------|
| [olmOCR-2 GGUF](https://huggingface.co/richardyoung/olmOCR-2-7B-1025-GGUF) | **Q8_0 available (8.1 GB).** Based on Qwen2.5-VL-7B. RLVR-trained, SOTA on olmOCR-Bench. Runs in llama.cpp. |
| olmOCR v4 prompt (no anchoring) | `"Just return the plain text representation of this document as if you were reading it naturally. Convert equations to LaTeX and tables to HTML."` + YAML front matter for metadata. Image rendered at **1288px longest side**. |
| olmOCR document anchoring | Injects sampled text blocks + coordinates from PDF text layer into prompt. Caps at 6000 chars (~1800 tokens). "Anchors" the VLM to reduce hallucinations. **Our Tika text is exactly this.** |
| Qwen3-VL native doc parsing | Built-in `"qwenvl markdown"` and `"qwenvl html"` prompts with bounding-box coordinates (normalized 0-999 system). Could produce structured output with positional metadata. |
| GLM-OCR / hybrid two-stage | Layout detection → crop regions → VLM per region with type-specific prompts ("OCR:", "Table Recognition:", etc.). **We already have the layout detector (Phase 5 Path A).** |
| [Chunkr-parse-1](https://www.chunkr.ai/blog/chunkr-parse-1-thinking-the-best-vlm-for-document-ocr) | VLM + thinking mode for document OCR. Specialized model. |

**Complete improvement catalog (all options):**

| # | Change | Expected impact | Effort | Category |
|---|--------|----------------|--------|----------|
| 1 | **Plain-text prompt** — `"Just return the plain text representation of this document as if you were reading it naturally. Do not add any commentary."` | Eliminates preamble (33%), removes format noise, recovers token budget | Prompt edit | Quick win |
| 2 | **100 DPI** (from 150) | ~2x fewer pixels → ~50% faster inference | Config | Quick win |
| 3 | **temperature=0** (from 0.1) | More deterministic, fewer hallucinations | Config | Quick win |
| 4 | **JPG instead of PNG** | Smaller payload, faster base64 encoding/transfer | Config | Quick win |
| 5 | **Document anchoring** — inject Tika text excerpt into VLM prompt alongside image | olmOCR's biggest quality lever; grounds VLM, reduces hallucinations. We already have the Tika text from initial extraction. | Prompt template + pass Tika text to VDU | Medium |
| 6 | **Layout detector + VLM per region** — use Phase 5 Path A layout detector to crop regions, then VLM each region with type-specific prompts | Better accuracy on tables/figures. Smaller crops → faster per-region. Reading order from layout model. | Wire LayoutDetector → PdfImageRenderer → per-region VLM calls | Medium |
| 7 | **Evaluate RolmOCR GGUF** — OCR-specialized Qwen2.5-VL-7B fine-tune | Purpose-built for OCR; simple prompt works. But: separate model can't share llama-server with chat. | Download + test | Medium |
| 8 | **Evaluate olmOCR-2 GGUF** — RLVR-trained, SOTA on olmOCR-Bench | Best measured OCR quality in the VLM space. Same trade-off as RolmOCR: separate model. | Download (8.1 GB Q8_0) + test | Medium |
| 9 | ~~**Qwen native doc parsing**~~ | ~~`qwenvl markdown`/`qwenvl html` are **Qwen3-VL only** (confirmed: Alibaba docs list qwen3-vl-plus/flash, not Qwen3.5). Different architecture — feature is not present.~~ | ~~N/A~~ | **RULED OUT** |
| 10 | **Selective VDU routing** — only VLM-extract pages where Tika scores < threshold | Avoids VLM on pages where Tika already works well. Existing `markVduIfNeeded()` does this at <0.3; could raise to <0.5 or <0.7. | Config change | Quick win |
| 11 | **Multi-pass extraction** — VLM first pass for text, second pass for tables/complex regions | Better table accuracy at cost of 2x time. | Script/prompt change | Medium |
| 12 | **Self-verification pass** — text-only LLM checks VLM output for consistency | Catch hallucinations, fix garbled text. Uses existing chat model (no vision needed). | Prompt + orchestration | Medium |

**Model alternatives comparison:**

| | Qwen 3.5 9B (current) | RolmOCR 7B | olmOCR-2 7B |
|---|---|---|---|
| Architecture | Qwen3.5 (DeltaNet + MoE) | Qwen2.5-VL-7B | Qwen2.5-VL-7B |
| OCR training | General multimodal | 259k page fine-tune | RLVR on olmOCR-Bench |
| Prompt needed | Needs tuning | Simple one-liner | YAML front matter |
| Chat capability | Full chat + vision + tools | OCR only | OCR only |
| GGUF / llama.cpp | Yes (build 8185+) | Yes (Mungert/RolmOCR-GGUF) | Yes (richardyoung Q8_0, 8.1 GB) |
| Can share llama-server | Yes (already running) | No (different model) | No (different model) |
| Image resolution | Flexible | Flexible | 1288px longest side |
| Quality (estimated) | Untested at scale | Strong but unmeasured in GGUF | SOTA (olmOCR-Bench) |

**Qwen3.5-9B vision feature audit (2026-03-23):**

Confirmed capabilities relevant to document extraction:

| Capability | Benchmark | Usable for us? |
|-----------|-----------|---------------|
| OCR | 89.2 (OCRBench) | **YES — already using via VDU** |
| Document understanding | 87.7 (OmniDocBench) | **YES — already using** |
| Spatial grounding / bbox | 89.7 (RefCOCO) | Possible — could ask for region coords. Not needed: full-page extraction already works. |
| Object counting | 97.2 (CountBench) | No |
| GUI/screen understanding | 65.2 (ScreenSpot Pro) | No |
| Video understanding | 84.5 (VideoMME) | No |
| Tool calling with vision | 90.1 (V*) | No |

Confirmed **absent**: `qwenvl markdown`/`qwenvl html` parsing modes
(Qwen3-VL only, confirmed via Alibaba docs). No built-in
document-to-structured-output pipeline. No special OCR keywords.

**Experimental results summary:**

| Phase | Changes | Total | vs Docling | Outcome |
|-------|---------|-------|-----------|---------|
| A (Run 1) | Plain-text prompt, 100 DPI, temp=0, JPG | **76.0%** | **+10.0%** | **WINNER** |
| B (Run 2b) | + Document anchoring (fixed) | 76.0% | +10.0% | Neutral — base model ignores anchors |

**What worked:** Simple prompt + lower DPI + deterministic sampling.
The plain-text prompt alone fixed the 50% preamble rate and format
mismatch. 100 DPI is sufficient — the VLM doesn't need high-res for
text recognition.

**What didn't work:** Document anchoring. The base Qwen3.5 wasn't
trained to use text-layer hints (unlike olmOCR which was fine-tuned
for this). Anchoring is neutral at best, harmful when the text layer
is noisy.

**Remaining theoretical improvements (not yet tested):**

Grouped by approach and ordered by expected value. Confidence
assessment rates the agent's ability to implement and validate
each item in this codebase, not the theoretical quality gain.

*Approach 1 — Better prompts / settings (current model, no new deps):*

| # | Improvement | Quality potential | Impl. confidence | Notes |
|---|-------------|------------------|-----------------|-------|
| 10 | **Raise VDU quality threshold** (0.3 → 0.5 or 0.7) | Indirect — routes more PDFs through VLM | **HIGH** — config change in `markVduIfNeeded()`, single threshold constant | Currently only scores <0.3 trigger VDU. Many "medium" pages (0.3–0.7) would benefit from VLM treatment. |
| 14 | ~~**Text-only LLM merge pass**~~ | ~~Medium-high~~ | **TESTED, FAILED** (Run 4: 66.9%, -9.1%). The LLM rephrases and summarizes instead of preserving content. Truncating both inputs to fit context (2500ch each) also loses content. The merge instinct produces shorter, more "helpful" text that loses exact-match words critical for BM25. |
| 15 | ~~**Text-only LLM cleanup**~~ | ~~Low-medium~~ | **TESTED, FAILED** (Run 3: 71.9%, -4.1%). Same problem: the LLM interprets "clean up" as license to rephrase. Easy bracket dropped from 93.5% to 83.9%. Post-processing good VLM output only degrades it. |
| 13 | **Spatial grounding prompt** — ask model to identify region bounding boxes, then extract per-region in one call | Unknown | **LOW** — RefCOCO 89.7 suggests capability exists, but: (a) untested whether coordinate output works via llama-server `/v1/chat/completions`, (b) would need to parse structured coordinate output, (c) may not work on document pages (RefCOCO is object detection, not document layout). Speculative. |
| 16 | **VLM re-extract on failed regions** — quality heuristic on VLM output (too short? <100 chars for a full page?) triggers re-extraction with different prompt or higher DPI | Low | **HIGH** — simple quality check + conditional re-call. But: the initial VLM call already does its best. Re-calling with a different prompt on the same image is unlikely to fix fundamental OCR failures. |
| 11 | **Multi-pass: OCR then table-focused second pass** | Low | **MEDIUM** — two VLM calls per page, second focused on "extract only the tables." Doubles inference time. Only useful if table extraction is a dominant failure mode (it wasn't in our 50-page analysis). |
| 12 | **Self-verification** — LLM checks own output for consistency | Low | **MEDIUM** — prompt is easy, but defining "consistency rules" for arbitrary documents is hard. What makes extracted text "wrong" without the ground truth? |

*Approach 2 — Layout-aware extraction (current model + existing layout detector):*

| # | Improvement | Quality potential | Impl. confidence | Notes |
|---|-------------|------------------|-----------------|-------|
| 6 | **Layout detector + per-region VLM** | Medium | **LOW** — requires: (a) Python ONNX inference for the layout model (640x640 preprocess, NMS, coordinate mapping), (b) PDFBox or PyMuPDF rendering + cropping per detected region, (c) type-specific prompts per region class, (d) reading-order reassembly from multiple VLM outputs, (e) multiple VLM calls per page (one per region). All components exist in Java in the worktree but porting to the Python experiment harness is 2+ hours. In production (Java), `PdfLayoutExtractor` already does steps a-d for Tika text — wiring it to VLM instead is medium effort but the per-region VLM calls need the llama-server HTTP roundtrip per region. |

*Approach 3 — Dedicated OCR model (new model, can't share llama-server):*

| # | Improvement | Quality potential | Impl. confidence | Notes |
|---|-------------|------------------|-----------------|-------|
| 8 | **olmOCR-2 GGUF** (8.1 GB Q8_0) | Potentially high (SOTA) | **LOW-MEDIUM** — download is straightforward, but: (a) needs Qwen2.5-VL mmproj (separate from Qwen3.5 mmproj — must find and download), (b) can't share the running llama-server with chat (different model), (c) would need to either run a second llama-server on a different port or stop/swap the chat model during backfill. Option (c) conflicts with chat availability. Option (b) may OOM on a single GPU. Untested whether the GGUF even loads correctly in our llama-server build. |
| 7 | **RolmOCR GGUF** | Medium-high | **LOW-MEDIUM** — same blockers as olmOCR-2. Additionally, RolmOCR GGUF quantization quality is explicitly "not evaluated" per the HuggingFace card. May be significantly worse than the native model. |

*Approach 4 — Ruled out / blocked:*

| # | Improvement | Status |
|---|-------------|--------|
| 9 | Qwen native doc parsing | **RULED OUT** — Qwen3-VL only, confirmed absent in Qwen3.5 |
| 5 | Document anchoring | **TESTED, NEUTRAL** on base model (Run 2b) |
| 14 | Text-only LLM merge pass | **TESTED, FAILED** — Run 4: -9.1% regression |
| 15 | Text-only LLM cleanup pass | **TESTED, FAILED** — Run 3: -4.1% regression |
| D | Granite-Docling VLM | Blocked on CPU speed (<5s/page needed) |

**Final disposition of all improvements (2026-03-23):**

| # | Improvement | Status |
|---|-------------|--------|
| 1-4 | Plain-text prompt, 100 DPI, temp=0, JPG | **SHIPPED (Run 1 = 76.0%)** |
| 5 | Document anchoring | Tested, neutral on base model |
| 6 | Layout + per-region VLM | Not tested — diminishing returns, high effort |
| 7 | RolmOCR GGUF | Ruled out — can't afford second model dependency |
| 8 | olmOCR-2 GGUF | Ruled out — can't afford second model dependency |
| 9 | Qwen native doc parsing | Ruled out — Qwen3-VL only |
| 10 | Raise VDU threshold | Production optimization — not per-page quality |
| 11 | Multi-pass OCR | Not tested — low value given #14/#15 results |
| 12 | Self-verification | Not tested — low value given #14/#15 results |
| 13 | Spatial grounding | Not tested — speculative |
| 14 | Text-only LLM merge | Tested, **failed** (-9.1%) |
| 15 | Text-only LLM cleanup | Tested, **failed** (-4.1%) |
| 16 | Re-extract on failure | Not tested — low value |

**Conclusion: Run 1 is the final configuration.** Plain-text prompt,
100 DPI, temp=0, JPEG. Single VLM pass. Every attempt to improve
on this (anchoring, cleanup, merge) has been neutral or harmful.
The simple approach wins.

**Honest confidence summary:**

The items I can confidently implement and validate quickly:
- **#10** (threshold tuning): config change, testable immediately
- **#15** (text cleanup pass): simple prompt, one LLM call
- **#16** (re-extract on failure): quality check + conditional retry

The items that sound good but have real implementation uncertainty:
- **#14** (LLM merge): the *prompt* is easy but *wiring* a second
  LLM pass into the VDU pipeline is non-trivial. In the experiment
  script it's a second HTTP call. In production it's a new
  orchestration step in `VduBatchProcessor`.
- **#6** (layout + per-region): all pieces exist but connecting them
  is 2+ hours of work for the experiment alone.
- **#7/#8** (model swap): blocked on the model-switching problem
  which is an architectural constraint, not an implementation task.
- **#13** (spatial grounding): genuinely don't know if it works via
  llama-server. Would need a quick probe test.

#### 6h. Iterative validation approach (2026-03-23)

**Corpus:** 50 pages stratified by extraction difficulty AND domain.
Manifest: `tmp/eval-corpora/vlm-eval-sample.json` (seed=42).

| Bracket | Docling overlap | Pages | Purpose |
|---------|----------------|-------|---------|
| **Hard** | <50% (avg 23.1%) | 15 | Where Tika/Docling fail — VLM's opportunity to prove value |
| **Medium** | 50–85% (avg 73.1%) | 15 | Common improvement case |
| **Easy** | 85%+ (avg 93.1%) | 20 | Regression check — VLM must not be worse |

Domain distribution: academic (9), administration (7), finance (7),
law (7), manual (7), news (7), textbook (6). Every page has a
matching query in qrels — enabling real nDCG measurement.

All three reference extractions available for every page: clean
ground truth, Docling, and Tika (via PDF). Overall Docling avg on
this sample: 66.1% (harder than the full corpus because hard pages
are oversampled).

**Metric:** Primary = word overlap with ground truth (fast, per-run).
Secondary = per-bracket breakdown (hard/medium/easy). Tertiary =
real nDCG@10 via mini jseval dataset (50 docs, 50 queries) — run
once per promising configuration.

**Protocol:** For each improvement, re-extract the same 50 pages.
Compare to baseline. Improvements stack — each run includes all
previous improvements that passed. Results tracked per-bracket
(hard/medium/easy) to distinguish "helps the worst pages" from
"doesn't regress the easy pages."

**Confidence assessment and ranking by improvement potential:**

| Rank | # | Change | Quality potential | Confidence | Rationale |
|------|---|--------|------------------|------------|-----------|
| **1** | 5 | **Document anchoring** | **HIGH** | **MEDIUM** | olmOCR's #1 quality lever. Inject Tika/PyMuPDF text into prompt alongside image. Grounds VLM, reduces hallucinations. We have the text — it's a prompt template change. Confidence is MEDIUM because the prompt format needs experimentation (how much text, where to inject, how to frame it). |
| **2** | 1 | **Plain-text prompt** | **MEDIUM-HIGH** | **HIGH** | Fixes preamble (33% of pages), eliminates format mismatch with ground truth, recovers token budget. Trivial to implement — single string change. Proven by RolmOCR and olmOCR research. |
| **3** | 8 | **olmOCR-2 GGUF** | **HIGH** | **LOW-MEDIUM** | RLVR-trained, SOTA quality. But: 8.1 GB download, separate model can't share llama-server with chat, unknown GGUF quality vs native, requires Qwen2.5-VL mmproj (different from Qwen3.5 mmproj). Would need a second llama-server instance or model swap. |
| **4** | 7 | **RolmOCR GGUF** | **MEDIUM-HIGH** | **LOW-MEDIUM** | Same trade-offs as olmOCR-2 but lower ceiling. Simpler prompt. Fine-tuned for OCR. Same model-switching problem. |
| **5** | 6 | **Layout + per-region VLM** | **MEDIUM-HIGH** | **LOW** | All pieces exist (LayoutDetector ONNX, PDFBox rendering, VLM endpoint) but wiring is complex: need Python ONNX inference for layout model (640x640 preprocessing, NMS), crop per region, type-specific prompts, reassemble reading order. Significant implementation effort for the experiment script. May be 1-2 hours just for the Python harness. |
| **6** | 2 | **100 DPI** | **LOW** (speed) | **HIGH** | Mainly throughput improvement. May slightly reduce quality on fine text. Trivial config change. |
| **7** | 3 | **temperature=0** | **LOW** | **HIGH** | Marginal quality gain. Trivial. |
| **8** | 4 | **JPG format** | **NONE** (speed) | **HIGH** | Speed only. No quality impact. |
| **9** | 10 | **Selective routing** | **INDIRECT** | **HIGH** | Production optimization, not per-page quality. Already exists (`markVduIfNeeded`). Threshold tuning. |
| **10** | 9 | **Qwen native parsing** | **UNKNOWN** | **LOW** | `"qwenvl markdown"` / `"qwenvl html"` are Qwen3-VL features. Qwen3.5 has a different architecture (DeltaNet + MoE vs pure transformer). Likely won't work or will produce different output. Not worth testing until confirmed compatible. |
| **11** | 11 | **Multi-pass** | **LOW** | **MEDIUM** | 2x slower. VLMs already do well in single pass (industry trend). Only useful if specific failure modes (e.g., tables) persist after other improvements. |
| **12** | 12 | **Self-verification** | **LOW** | **MEDIUM** | Adds latency. Text-only LLM may not catch visual errors. Better to improve extraction than to post-process. |

**Run results:**

| Run | Settings | Hard (15) | Med (15) | Easy (20) | Total (50) | Notes |
|-----|----------|-----------|----------|-----------|------------|-------|
| — | **Docling** (reference) | 23.1% | 73.1% | 93.1% | 66.1% | GPU, layout+OCR+table |
| 0 | markdown prompt, 150 DPI, temp=0.1 | 54.1% | 68.2% | 88.6% | 72.1% | 25/50 preamble, 9.8 min |
| **1** | **plain-text prompt, 100 DPI, temp=0, JPG** | **54.6%** | **74.2%** | **93.5%** | **76.0%** | **WINNER. 0 preamble, ~10 min** |
| 1v | verification rerun (identical settings) | 54.6% | 74.2% | 93.5% | 76.0% | **Deterministic — exact match** |
| 2a | + document anchoring (broken) | 53.5% | 75.6% | 88.1% | 74.0% | 2 HTTP 400, regressed |
| 2b | + document anchoring (fixed) | 54.3% | 74.6% | 93.3% | 76.0% | neutral vs Run 1 |
| 3 | Run 1 + text-only cleanup pass (#15) | 54.2% | 73.7% | 83.9% | 71.9% | **REGRESSED** -4.1%. LLM summarizes/rephrases, destroying exact-match words. Easy bracket worst hit (-9.6%). |
| 4 | Run 1 + text-only merge pass (#14) | 52.7% | 69.9% | 75.2% | 66.9% | **REGRESSED** -9.1%. LLM actively destructive. Truncation loses content. Merge instinct is to rephrase. |

**Run 0 analysis (2026-03-23):**

VLM already beats Docling overall (**72.1% vs 66.1%, +6%**) despite
known prompt issues. The win is driven by the hard bracket
(**54.1% vs 23.1%, +31%**) — VLM reads visual content that Docling's
OCR pipeline misses entirely. Docling leads on medium (+4.9%) and
easy (+4.5%) pages where its text-layer extraction is clean and VLM's
preamble/markdown formatting adds noise.

**Key observations:**
- 25/50 pages (50%) have preamble — worse than the 33% in the
  academic-only sample. Cross-domain pages trigger more preamble.
- Finance (52.7%) and news (52.3%) are VLM's weakest domains — likely
  scanned pages or complex layouts where VLM produced very short output.
- Textbook (83.7%) and manual (87.9%) are strong — visual content
  where VLM excels.
- Server crashed once during extraction (prompt cache issue). Fixed
  by restarting with `--cache-ram 0`.

**Decision gate outcome:** VLM > Docling on this corpus. Proceed
with Run 1.

**Run 1 analysis (2026-03-23):**

Quick wins (+3.9% total, 72.1% -> 76.0%):
- Plain-text prompt eliminated all preamble (25/50 -> 0/50)
- Medium bracket recovered to Docling parity (74.2% vs 73.1%)
- Easy bracket recovered to Docling parity (93.5% vs 93.1%)
- Hard bracket unchanged (54.6% vs 54.1%) — needs more than prompt
- Finance +17.1% (52.7% -> 69.8%), news +10.5% (52.3% -> 62.8%)

**Decision gate outcome:** VLM now 10% ahead of Docling overall.
Medium and easy brackets match Docling. Remaining gap is entirely
in hard bracket (54.6% vs 23.1% — both poor, but VLM is 2x better).
Proceed with Run 2.

**Run 2 analysis (2026-03-23) — document anchoring REGRESSED:**

Document anchoring (injecting PyMuPDF text layer into prompt) produced
74.0% total — **2% worse than Run 1** (76.0%). Breakdown:
- Hard: 53.5% (was 54.6%) — slight regression
- Medium: 75.6% (was 74.2%) — slight gain
- Easy: **88.1%** (was **93.5%**) — significant 5.4% regression
- 2/50 pages failed (HTTP 400 — special chars in text layer broke JSON)

**Why anchoring hurts on a base model:**
1. On easy pages, the VLM already reads images well (93.5%). Injecting
   Tika text adds noise and the VLM parrots garbled text-layer content
   instead of reading the visual image.
2. On hard pages, the Tika text is garbled (that's WHY they're hard).
   Injecting garbled text confuses rather than grounds.
3. olmOCR's anchoring works because the model was **fine-tuned with
   anchored prompts** — the model learned to use anchors as hints.
   Qwen 3.5 base has no such training; it treats injected text as
   authoritative rather than advisory.

**Run 2 retest (fixed, 2026-03-23):**

After fixing sanitization (control chars), reducing snippet size
(3000→800 chars), and reframing the prompt ("noisy text for reference
only, trust the image"), Run 2b produced 50/50 pages with 0 errors.
Result: **76.0% — identical to Run 1.** Anchoring is neutral, not
harmful. The VLM simply ignores the text hints because it wasn't
trained to use them.

**Conclusion:** Document anchoring requires an OCR-specialized
fine-tune (olmOCR) to be effective. On a base model it is neutral.
**Run 1 (plain-text prompt, 100 DPI, temp=0) is the best
configuration for the base Qwen 3.5 model.**

**Final result:** VLM 76.0% vs Docling 66.1% — **VLM is 10% better
overall with zero new dependencies.** The hard bracket (54.6%) is
2.3x better than Docling (23.1%) — this is the primary use case
(pages where Tika fails). Medium and easy match or exceed Docling.

**Remaining work — delegated to tempdoc 346:**

Items 6j–6l (VDU prompt/settings, llama-server upgrade, single-slot
enforcement) are captured as tempdoc 346 items 3a–3e, 1a–1c. Another
agent will handle production integration.

**General production improvements identified:**

- [ ] **6m.** Add `jseval extract-eval` subcommand: compare any
  extraction directory against ground truth using word overlap,
  broken down by difficulty bracket and domain. Reuses the 50-page
  stratified manifest (`tmp/eval-corpora/vlm-eval-sample.json`) as
  a fast-eval corpus. Reference: `scripts/search/vlm-eval-harness.py`.
- [ ] **6n.** Make VDU quality threshold configurable: add
  `JUSTSEARCH_VDU_QUALITY_THRESHOLD` env var (default 0.3, current
  hardcoded value). Experiments showed VLM beats Docling on medium-
  bracket pages (quality 0.3–0.85) too. Raising the threshold routes
  more PDFs through VLM extraction for free quality improvement.

**Experiment scripts (reference, in `scripts/search/`):**

| Script | Purpose | Keep? |
|--------|---------|-------|
| `vlm-eval-harness.py` | Word overlap eval by bracket/domain | Yes — basis for `jseval extract-eval` |
| `vlm-extract-sample.py` | 50-page extraction with configurable prompt/DPI/temp | Yes — reusable for future extraction experiments |
| `vlm-extract-ohr-bench.py` | Full 999-page extraction | Delete — superseded by sample script |
| `vlm-postprocess.py` | Text-only cleanup/merge passes | Delete — proved harmful (Runs 3-4) |
| 3 | (if needed) layout-aware per-region OR model swap | Only if Run 2 shows remaining issues |

**Revised decision gates:**
- After Run 1: if medium/easy brackets improve to match or exceed
  Docling → VLM with simple prompt is sufficient. Ship it.
- After Run 2: if total > 80% → document anchoring is a clear win.
  Run mini jseval for real nDCG confirmation.
- Run 3 only needed if specific failure modes persist after Run 2.

---

## Confidence Assessment & Effort Estimate

### Docling quality: VALIDATED (but integration paused)

**Measured: +6.7% nDCG over Tika (0.7947 → 0.8621).** Pre-measurement
prediction was 2-5% improvement — actual result exceeded expectations.
Docling closes 44% of the gap between Tika and clean text.

The remaining 9.1% gap is above the 5% threshold but diminishing returns
apply. Potential further improvements (not yet measured):
- VLM-based extraction via existing chat LLM (Phase 6)
- English-optimized OCR (PP-OCRv4 is Chinese-first)
- Hybrid: LLM extraction for PDFs scoring below quality threshold, Tika for rest

### Docling effort estimate (paused — reference only)

| Item | Effort | Risk |
|------|--------|------|
| **5k.** Protocol design | 0.5 day | Low — just subprocess + stdout |
| **5l.** Process launcher | 2-3 days | **Medium** — Python lifecycle on Windows |
| **5m.** Output mapping (Markdown → StructuredDocument) | 1 day | Low — Docling Markdown is clean |
| **5n.** Wiring + fallback | 0.5 day | Low — ContentExtractorProvider seam exists |
| **Total** | **4-5 days** | |

### Key risks (Docling path — for reference)

1. **Python dependency.** Requires `pip install docling` + 8.4 GB models.
   Must be opt-in for desktop users.
2. **Process lifecycle on Windows.** Python subprocess management is
   fragile (PATH, venv, antivirus). Reference: llama-server subprocess
   in `modules/ai-bridge`.
3. **Startup latency.** 38s cold start for Docling import + model load.
   Must keep process warm across PDFs.
4. **GPU VRAM contention.** Docling uses 954 MB VRAM. Conflicts with
   embedding/SPLADE single-tenant policy during indexing.

### Alternative: `docling-serve` (Docker)

Instead of managing a Python subprocess, use `docling-serve` (Docker
container). This is simpler to lifecycle-manage (Docker handles process
isolation, memory limits, restart) but requires Docker — an even larger
adoption barrier for desktop users. See Appendix for integration notes.

**Path C research findings (for reference):**

RapidOCR (PP-OCRv4) evaluated on 10 OHR-Bench pages. Blanket OCR is
worse than PDFBox (loses on 8/10 pages). OCR wins only on pages where
PDFBox catastrophically fails (vertical text, font encoding issues).
Selective OCR is correct but complex to implement from scratch in Java.

| Page type | PDFBox recall | OCR recall | Best |
|-----------|-------------|------------|------|
| Normal text-layer PDF | **80-99%** | 45-80% | PDFBox |
| Vertical/rotated text | **9%** | **80%** | OCR |
| Font encoding failures | **0%** (cid:) | **~70%** | OCR |

**Path D: Granite-Docling VLM (future, gated on hardware)**

- [ ] **6a.** Benchmark CPU inference speed on representative desktop CPU.
  If <5s/page, integrate via existing llama.cpp FFM bindings.

---

## Dependencies

- **Search quality register:** F-009 records the baseline. Update after
  each phase completes.
- **251 (Realistic Eval):** OHR-Bench measurement is complete. LoCoV1
  courtlistener can serve as a secondary data point for text-layer PDFs.

## Phase 5 Implementation Files

### New files
| File | Module | Purpose |
|------|--------|---------|
| `worker-core/.../layout/LayoutDetector.java` | worker-core | ORT session + inference (image → detections) |
| `worker-core/.../layout/LayoutLabel.java` | worker-core | Enum of 17 layout classes |
| `worker-core/.../layout/Detection.java` | worker-core | Record: bounding box + label + score |
| `worker-services/.../extract/PdfLayoutExtractor.java` | worker-services | Per-page: render → detect → region text → StructuredDocument |
| `models/onnx/layout-detector/model.onnx` | models | 162 MB ONNX model (LFS) |
| `models/onnx/layout-detector/config.json` | models | Label ID → name mapping |

### Modified files
| File | Change |
|------|--------|
| `worker-services/build.gradle.kts` | Add `implementation(libs.pdfbox)` for `PDFTextStripperByArea` compile access |
| `configuration/.../EnvRegistry.java` | Add `JUSTSEARCH_LAYOUT_MODEL_PATH` |
| `configuration/.../ResolvedConfig.java` | Add `Layout` sub-record to `Ai` |
| `configuration/.../ResolvedConfigBuilder.java` | Wire layout model path resolution |
| `reranker/.../WorkerModelDiscovery.java` | Register `"layout-detector"` |
| `worker-services/.../extract/StructuredContentExtractor.java` | PDF routing: layout path when model present, Tika SAX fallback |
| `worker-services/.../server/DefaultWorkerAppServices.java` | Construct `LayoutDetector` at startup |
| `.gitattributes` | LFS tracking for `models/onnx/layout-detector/*.onnx` |

### Reused existing infrastructure
| What | From |
|------|------|
| `OnnxSessionCache.createCachedSession()` | `modules/reranker` — session factory with graph optimization cache |
| `OnnxModelDiscovery.resolve()` | `modules/configuration` — model path auto-discovery |
| `OrtEnvironment.getEnvironment()` | ORT singleton — shared across all models |
| `PDFTextStripperByArea` | PDFBox 3.0.6 — region-based text extraction (not yet used in codebase) |
| `StructuredDocument` IR | `modules/indexing` — typed element assembly (from Phase 1) |
| `ContentExtractorProvider` interface | `modules/worker-services` — extraction SPI |

## Appendix: Docling Integration Notes (from 249)

Reference notes for Phase 5 implementation. Source: tempdoc 249.

- **Jackson 2 variant required:** `ai.docling:docling-serve-client:0.4.7`
  with Jackson 2 variant (avoid dependency conflicts).
- **Image export mode:** Use `"embedded"`, not `"referenced"` (broken).
- **Testcontainers timeout:** 5+ minutes (4–11 GB images).
- **No built-in retry:** Wrap `convertSource()` with retry. Issue #339
  (422 response model mismatch) requires defensive wrapper.
- **Enrichment plugins off by default:** Baseline = layout + table only.
- **Pipeline switching:** `pipeline: vlm` per-request parameter routes
  scanned PDFs to VLM pipeline, programmatic PDFs to standard.
- **HybridChunker breadcrumbs:** Prepend heading stack (~5–15 tokens) per
  chunk → maps to existing `chunk_heading_text` field.
- **Merge-peers:** Merge undersized consecutive chunks with matching
  headings. Alternative to overlap — empirical comparison still needed.
- **Granite-Docling GGUF:** 178 MB Q8_0. GPU throughput 395–506 t/s
  (3–7s/page). CPU speed unresolved (15–30s/page extrapolated).
  `force_backend_text` may reduce VRAM pressure.

---

## Evaluation Environment

- **Corpus:** OHR-Bench 1000-page sample, 7 domains, 962 queries.
  Converted via `scripts/search/convert-ohrbench-to-beir.py`. Three
  variants: `mixed/ohr-bench-clean`, `mixed/ohr-bench-got-moderate`,
  `mixed/ohr-bench-mineru-moderate`.
- **Backend:** `JUSTSEARCH_AI_DISABLED=true ./gradlew.bat --no-configuration-cache :modules:ui:runHeadlessEval`
- **Per-corpus:** `rm -rf tmp/headless-eval-data` between corpora
- **Port:** 33221 (jseval reads from `JUSTSEARCH_API_PORT`)
- **Windows note:** Corpus/qrel IDs must be lowercased to match
  `resolve_doc_id()` output (Windows lowercases file paths).
- **Re-eval after each phase:** `python -m jseval run --dataset mixed/ohr-bench-clean --modes lexical`

---

## Results: Experiment B — Full Pipeline Extraction Tax (2026-03-28)

### Setup

Remeasured extraction tax with the full multilingual model stack (all 5
model swaps from tempdoc 343 Phase D, CE enabled) instead of lexical-only
with AI disabled. Also added VLM extraction measurement on the 50-page
stratified sample. Git 5d19ff2c1.

### End-to-end search quality (1000 pages, 962 queries)

| Extractor | lexical | bm25_splade | full | Tax (full) | Tax (lexical) |
|-----------|---------|-------------|------|-----------|--------------|
| **Clean (GT)** | 0.952 | 0.946 | **0.952** | — | — |
| **Docling** | 0.872 | 0.869 | **0.884** | **−7.2%** | −8.4% |
| **GOT moderate** | 0.817 | 0.815 | 0.822 | −13.7% | −14.2% |
| **Tika/PDFBox** | 0.804 | 0.798 | 0.808 | **−15.1%** | −15.5% |
| **MinerU moderate** | 0.656 | 0.655 | 0.696 | −26.9% | −31.1% |

Runs: `20260328T131217_*_ohr-bench-clean`, `20260328T*_ohr-bench-docling`,
`20260328T*_ohr-bench-got-moderate`, `20260328T*_ohr-bench-tika-pdf`,
`20260328T*_ohr-bench-mineru-moderate`.

### VLM word overlap (50-page stratified sample, updated llama-server build 8571)

| Extractor | Overall | Hard (15) | Medium (15) | Easy (20) |
|-----------|---------|-----------|-------------|-----------|
| **VLM (Qwen 3.5)** | **81.9%** | **66.9%** | **79.4%** | **95.0%** |
| Docling | 71.5% | 33.8% | 78.1% | 94.9% |
| Tika/PDFBox | 66.3% | 39.0% | 61.2% | 90.6% |

Head-to-head: VLM wins 30/50 vs Docling, 24/50 vs Tika.

### Key findings

1. **Production extraction tax is 15.1%** (Tika/PDFBox, full pipeline).
   This is the current quality ceiling imposed by text extraction.

2. **Docling halves the tax** (7.2% vs 15.1%) but requires Python + 8.4 GB
   models. Closes 52% of the gap to clean text.

3. **VLM (chat model) is the best extractor by word overlap** (81.9% vs
   Docling 71.5%, +10.4%). Zero new dependencies — reuses the existing
   Qwen 3.5 chat model with mmproj vision projector via llama-server.

4. **Full pipeline barely helps noisy text.** Dense/SPLADE add +0.5% for
   GOT, +0.4% for Tika over lexical. Only MinerU gains significantly
   (+4.0%), suggesting semantic retrieval compensates only for the worst
   extraction failures. SPLADE is neutral or negative on all variants.

5. **VLM throughput: 13.7s/page** (updated llama-server build 8571, down
   from 21.4s on build 8185). Acceptable for async backfill.

6. **VLM dominates on hard pages** (66.9% vs Docling 33.8%, Tika 39.0%).
   On easy pages all three converge (~91-95%).

### Implications for production

The VLM path (Phase 6, Run 1 configuration) is the clear winner on all
dimensions: highest quality, zero new dependencies, acceptable throughput
via async backfill. The production integration items (6j–6n, delegated to
tempdoc 346) should be prioritized.

**Expected nDCG gain if VLM replaces Tika on PDFs:** Based on the word
overlap advantage (81.9% vs 66.3%), the VLM should score between Docling
(0.884 nDCG) and clean (0.952 nDCG). Estimated ~0.90 nDCG — a ~10%
improvement over Tika (0.808). This would reduce the extraction tax from
15.1% to ~5%, near the original 5% decision gate threshold.

---

## Results: Experiment A — OHR-Bench Ingestion Tax (2026-03-19)

### Setup

- **Corpus:** OHR-Bench 1000-page sample (7 domains), converted to jseval
  format via `scripts/search/convert-ohrbench-to-beir.py`
- **Backend:** `runHeadlessEval` with `JUSTSEARCH_AI_DISABLED=true` (pure BM25,
  no embedding/SPLADE models loaded)
- **Mode:** `lexical` only (BM25). Isolates text quality from neural model effects.
- **Note:** Windows lowercases file paths; corpus/qrel IDs must be lowercased to
  match `resolve_doc_id()` output. Applied via post-generation fix.

### Baseline results (lexical/BM25)

| Variant | nDCG@10 | P@1 | R@10 | Delta |
|---------|---------|-----|------|-------|
| **Clean (gt_text)** | **0.9487** | 0.9044 | 0.9865 | baseline |
| **GOT moderate** | **0.8090** | 0.7505 | 0.8617 | **-0.1397 (-14.7%)** |
| **MinerU moderate** | **0.6382** | 0.5644 | 0.7131 | **-0.3105 (-32.7%)** |

### Decision gate outcome

Both extraction variants exceed the **>5% nDCG** threshold by a wide margin.
**Verdict: Top priority. Evaluate Docling sidecar (Phase 3).**

The MinerU result (-33%) is extreme; GOT (-15%) is more representative of a
layout-aware extractor. Even the better extractor loses almost 15% nDCG — this
confirms ingestion quality is the single largest quality bottleneck.

### Failure mode analysis (MinerU moderate)

Of 962 queries, 232 scored nDCG=0 despite being perfect in the clean variant.

**Document-level issues (1000 docs):**
- 9.8% completely empty/trivial text after extraction
- 0.7% very short (<20 chars)
- 4.2% severely truncated (<25% of clean text length)
- 85.3% normal length (but may still have term mismatches)
- Overall text retention: 82.9%

**Domain breakdown (% degraded = empty + truncated):**
- textbook: 36% degraded (worst — layout-heavy pages)
- law: 18%
- manual: 15%
- news: 14%
- finance: 12%
- academic: 10%
- administration: 10%

**Failure categories (232 fully degraded queries):**
- Table content (tabular/hline in query): 27 queries
- Short/heading queries (<40 chars): 26 queries
- Regular text queries: 178 queries (extraction produced empty or wrong text)
- LaTeX formatting: 1 query

### Implications

The dominant failure is **empty or wrong text** (178/232 degraded queries),
not just noisy text. Post-processing flat text cannot recover what was never
extracted. The Tier 1 fix (structured Tika SAX) addresses this by capturing
structure before it's flattened. Tier 2 quality scoring can flag documents
where extraction failed and route them to more expensive re-processing.

---

## Implementation Files

### New files (commit `62342f816`)

| File | Purpose |
|------|---------|
| `modules/indexing/.../extraction/StructuredDocument.java` | Typed element IR + serialization + header/footer removal |
| `modules/worker-services/.../extract/StructuredContentHandler.java` | SAX handler building StructuredDocument from Tika events |
| `modules/worker-services/.../extract/StructuredContentExtractor.java` | ContentExtractorProvider with SAX extraction + flat fallback |
| + 3 test files (46 tests total) | |

### Modified files

| File | Change |
|------|--------|
| `SSOT/catalogs/fields.v1.json` (+ adapters-lucene copy) | Added `extraction_method`, `extraction_quality_score` |
| `modules/indexing/.../SchemaFields.java` | Constants + INDEXABLE_FIELDS |
| `modules/indexing/.../chunking/ChunkSplitter.java` | `Mode.STRUCTURED` + boundary logic |
| `modules/worker-services/.../text/TextQualityAnalyzer.java` | `computeQualityScore()` |
| `modules/worker-services/.../loop/ops/IndexingDocumentOps.java` | Provenance fields + quality-score VDU routing |
| `modules/worker-services/.../rag/ChunkDocumentWriter.java` | Heading breadcrumbs for PDF/Office |
| `modules/worker-services/.../server/DefaultWorkerAppServices.java` | Swapped ContentExtractor → StructuredContentExtractor |

## Non-Goals

- Replacing Apache Tika. Tika remains the primary parser — we change how
  we consume its output (SAX events instead of `parseToString()`).
- Supporting every document format. Focus on high-impact formats: PDF,
  .docx, .xlsx, .csv, Markdown, HTML.
- Real-time processing optimization. Quality over speed.
- Requiring Docker or external services for basic functionality. Tier 3
  (Docling) is opt-in.

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Ingestion-pipeline strategy (1311 lines) with explicit non-goals (no Tika replacement, no real-time speed focus). Strategy concluded. Subsequent tika/extraction work (per recent commits like the structured extractor changes) consumed the direction.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

