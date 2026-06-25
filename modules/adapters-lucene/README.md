# adapters-lucene

Lucene adapter implementing indexing/search interfaces with schema validation and runtime configuration.

## Overview

The `adapters-lucene` module provides the Lucene implementation of indexing and search interfaces defined in `indexing`. It handles field mapping, schema validation, analyzer configuration, and index runtime management. This is where the actual Lucene IndexWriter/IndexSearcher operations occur.

## Key Classes

| Class | Responsibility |
|-------|----------------|
| `LuceneIndexRuntime` | Main Lucene index runtime (4000+ lines) |
| `IndexRuntimeFactory` | Creates configured index runtimes |
| `RuntimeConfig` | Index runtime configuration |
| `FieldMapper` | Maps documents to Lucene fields |
| `JustSearchCodec` | Custom Lucene codec with vector support |
| `SsotAnalyzerRegistry` | Loads analyzers from SSOT |
| `ShardCoordinator` | Coordinates multi-shard operations |
| `SingleShardCoordinator` | Single-shard implementation |
| `IndexMetadataParityGuard` | Validates index metadata consistency |
| `ParityDiagnostics` | Parity check diagnostics |
| `SafeIndexPathOps` | Safe filesystem operations |
| `SsotCommitMetadataSource` | Commit metadata from SSOT |
| `JsonSchemaCommitMetadataValidator` | Validates commit metadata |
| `TelemetrySoftDeletesMergePolicy` | Merge policy with telemetry |

## Dependencies

**Depends on:**
- `configuration` - RuntimeConfig
- `indexing` - Indexing abstractions
- `infra-core` - Boost/similarity configuration
- `core` - Domain types
- Lucene Core + Analysis

**Depended on by:**
- `app-search` - Search runtime
- `indexer-worker` - Worker indexing
- `benchmarks` - Index benchmarks
