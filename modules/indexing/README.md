# indexing

Core indexing engine managing document chunking, pipeline coordination, and RAG context budgeting.

## Overview

The `indexing` module provides the fundamental indexing infrastructure for JustSearch. It handles document chunking for RAG, defines indexing pipeline abstractions, and manages token-aware context budgets. This module defines interfaces that are implemented by `adapters-lucene`.

## Key Classes

| Class | Responsibility |
|-------|----------------|
| `LuceneIndexer` | Core Lucene integration for indexing operations |
| `IndexApi` | Minimal interface for indexing operations |
| `IndexRuntime` | Runtime interface for index operations |
| `ChunkSplitter` | Splits documents into chunks for RAG |
| `ChunkIds` | Generates deterministic chunk identifiers |
| `TokenAwareBudgeter` | Manages token budgets for RAG context |
| `ContextBudgeter` | Interface for context budget allocation |
| `MmrSelector` | Maximal Marginal Relevance for chunk ranking |
| `IndexingPipelineLoader` | Loads indexing pipelines from SSOT |

## Dependencies

**Depends on:**
- `pipeline-schema` - Pipeline definitions
- Jackson - JSON/YAML parsing

**Depended on by:**
- `adapters-lucene` - Implements IndexRuntime
- `app-indexing` - Orchestrates indexing operations
- `indexer-worker` - Worker process indexing
