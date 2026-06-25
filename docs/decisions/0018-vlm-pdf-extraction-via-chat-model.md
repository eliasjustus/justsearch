---
title: "VLM PDF Extraction via Chat Model"
type: decision
status: stable
description: "Use existing chat model's vision capability (Qwen 3.5 VLM) for PDF layout extraction instead of adding Docling as a Python sidecar."
date: 2026-03-23
---

# ADR-0018: VLM PDF Extraction via Chat Model

## Status
Accepted

## Context
Tika extracts text from PDFs but loses layout and table structure. Complex PDFs (scientific papers, financial reports) need structural understanding for quality retrieval. The system already runs a chat model (Qwen 3.5 VLM with mmproj) via llama-server for AI features.

Options considered:
- Dedicated OCR model (olmOCR-2, RolmOCR)
- Dedicated layout detector
- Docling Python sidecar (8.4 GB dependency)
- Reusing the existing chat model's vision capability

Pages are flagged for VDU processing by `TextQualityAnalyzer` when the quality score falls below 0.3, indicating poor text extraction (e.g., scanned pages, complex table layouts, image-heavy content).

## Decision
Use Vision Language Model (VLM) extraction via the existing chat model path (Qwen 3.5 + mmproj) for PDF layout extraction. Configuration: plain-text prompt, 100 DPI, temp=0, JPEG format. Gated behind `JUSTSEARCH_LAYOUT_ENABLED=true` (disabled by default). The VDU pipeline processes pages flagged by `TextQualityAnalyzer` with quality score < 0.3.

This approach reuses the existing llama-server infrastructure and mmproj vision projection, requiring zero new dependencies.

## Consequences

### Positive
- **Zero new dependencies**: Reuses existing llama-server + mmproj. No Python runtime, no new model downloads, no sidecar lifecycle management.
- **Higher quality**: VLM achieves 81.9% word overlap vs Docling's 71.5% on a 50-page stratified sample. VLM wins 30/50 pages in head-to-head comparison.
- **Deterministic output**: Verified by independent rerun (Run 1 confirmed by rerun 1v) -- same input produces same output at temp=0.
- **Consistent architecture**: Follows the existing pattern of routing work through llama-server rather than adding external processes.

### Negative
- **Requires Brain process**: VLM extraction is only available when the Brain (llama-server) is running. Not available in headless/CPU-only deployments without LLM.
- **Processing speed**: ~0.25s/page on CPU. Acceptable for the selective pipeline (only flagged pages), but not suitable for bulk full-document OCR.
- **GPU contention**: VDU processing competes with chat/generation for llama-server capacity. Mitigated by the breath-holding protocol and selective page flagging.

## Alternatives Considered

### Docling Python sidecar
Full-featured document understanding library with layout detection, table extraction, and OCR.

- **Pros**: Mature library, dedicated to document understanding, handles diverse document types.
- **Cons**: 8.4 GB dependency, Python subprocess lifecycle management, lower quality on this benchmark (71.5% vs 81.9% word overlap).
- **Rejected because**: The dependency size and sidecar complexity are disproportionate to the quality gain (which is actually negative -- VLM outperforms Docling).

### Dedicated OCR model (olmOCR-2, RolmOCR)
Specialized vision models trained specifically for document OCR.

- **Pros**: Purpose-built for OCR, potentially higher throughput.
- **Cons**: Cannot share llama-server -- would need a second inference server process, separate model management, and VRAM partitioning on the single GPU.
- **Rejected because**: Running two inference servers on a single consumer GPU (8-12 GB VRAM target) is impractical.

### Text-only LLM cleanup pass
Send extracted text through the LLM to fix formatting and structure.

- **Pros**: No vision capability needed, works with any LLM.
- **Cons**: LLMs hallucinate content not present in the original document. The model cannot "see" what was lost during text extraction, so it invents plausible but incorrect content.
- **Rejected because**: Hallucination risk is unacceptable for a retrieval system where factual accuracy matters.

### Document anchoring (visual-text alignment)
Align visual layout features with extracted text spans to reconstruct structure.

- **Pros**: Preserves exact text from extraction, uses vision only for layout.
- **Cons**: Over-engineered for the observed quality gap. The VLM approach is simpler and produces better results.
- **Rejected because**: The complexity is not justified given that straightforward VLM extraction already outperforms alternatives.
