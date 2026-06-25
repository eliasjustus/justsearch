---
title: NER Model Agnosticism — BioTagDecoder Introspection
status: superseded
created: 2026-02-16
updated: 2026-03-22
---

> NOTE: Noncanonical doc (notes/ideas). May drift. Verify against code.

# 207: NER Model Agnosticism

## Origin

Extracted from tempdoc 205 (Model Spread Optimization) item D-5. The coupling was observed during the model spread audit but is unrelated to the chat/VDU/embedding/reranker model spread work. It belongs to the indexing pipeline (tempdoc 185 scope).

## Problem

`BioTagDecoder` (`modules/indexer-worker/.../ner/BioTagDecoder.java`, 181 lines) hardcodes the BIO label scheme for `dslim/bert-base-NER`:

```
0=O, 1=B-MISC, 2=I-MISC, 3=B-PER, 4=I-PER, 5=B-ORG, 6=I-ORG, 7=B-LOC, 8=I-LOC
```

4 switch statements and 9 label constants are coupled to this specific model. Swapping to a different NER model (different label count, different entity types) requires rewriting `BioTagDecoder`.

## Current scope

- **Model**: `dslim/bert-base-NER` (ONNX, runs in Worker process during indexing)
- **Functionality**: Extracts person, organization, and location entities from document text
- **Callers**: `BertNerInference.java` → `NerService.java` (Worker indexing pipeline)
- **Entity types supported**: PER, ORG, LOC (MISC filtered out)

## What model-agnosticism would require

1. Extract label map to a config record (~15 lines)
2. Parse label mapping from ONNX model metadata or sidecar config (~40 lines)
3. Replace 4 switch statements with map lookups (~15 lines)
4. Fallback to current hardcoded defaults if metadata absent (~10 lines)

**Estimated scope**: ~60-80 lines across 2 files.

## Why it's deferred

- Only one NER model is supported. No second model exists to validate against.
- ONNX metadata is not standardized for label mappings — introspection is model-specific.
- Current code is correct, well-tested (`BioTagDecoderTest.java`), and maintainable for its single use case.
- Premature abstraction risk: building generic infrastructure for a single concrete case.

**Trigger to revisit**: When a second NER model is needed (different entity types, different label scheme).

## Status update (2026-03-22)

The core work described here was implemented in **tempdoc 334 item 15** (NER model
swap to `dslim/distilbert-NER`): `BioTagDecoder` was refactored from hardcoded label
indices to `LabelMapping` loaded from `config.json` `id2label`, supporting both
bert-base-NER and distilbert-NER label orderings. This pattern is further generalized
in **tempdoc 338 (Config-Driven Model Decoding)**.
