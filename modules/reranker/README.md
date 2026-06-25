# reranker

Cross-encoder reranker using ONNX Runtime for deadline-aware document/chunk reranking.

## Overview

The `reranker` module provides neural reranking using cross-encoder models. It uses ONNX Runtime for inference with support for both CPU and GPU backends. Reranking respects deadline budgets, streaming results as they're scored and truncating when time runs out.

## Key Classes

| Class | Responsibility |
|-------|----------------|
| `CrossEncoderReranker` | Main reranking implementation |
| `RerankerConfig` | Configuration (model path, sequence length, deadline) |
| `RerankerTokenizer` | Text tokenization for cross-encoder input |

## Configuration

| Property | Description |
|----------|-------------|
| `model_path` | Path to ONNX cross-encoder model |
| `max_sequence_length` | Maximum input sequence length (default: 512) |
| `deadline_ms` | Maximum time for reranking (streaming truncation) |

## Dependencies

**Depends on:**
- `core` - Domain types
- `configuration` - Config loading
- ONNX Runtime - ML inference
- DJL Tokenizers - Text encoding

**Depended on by:**
- `indexer-worker` - Reranking in search pipeline
- `benchmarks` - Reranker performance testing
