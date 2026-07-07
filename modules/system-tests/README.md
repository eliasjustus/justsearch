# system-tests

Multi-tier testing suite for integration, chaos, and soak testing.

## Overview

The `system-tests` module provides comprehensive system-level testing beyond unit tests. It includes integration tests (Golden Corpus relevance), system/chaos tests (process coordination, failure injection), and soak tests (memory leak detection). Tests are organized into separate source sets with different timeouts.

## Test Tiers

| Tier | Timeout | Purpose |
|------|---------|---------|
| `test` | Default | Unit tests for test utilities |
| `integrationTest` | 5 min | Golden Corpus, HTTP API, AI quality |
| `systemTest` | 1 hour | Chaos, process coordination, VDU |
| `soakTest` | 4 hours | Memory leak detection |

## Key Classes

| Class | Responsibility |
|-------|----------------|
| `ManifestCorpusLoader` | Loads a document corpus + truth manifest — generic, shared by 2 unrelated test manifests (not "golden corpus"-specific despite its former name); `RelevanceMetrics`, not this class, is the piece additionally shared with `RagQualityEvalTest` |
| `GoldenCorpusTest` | Relevance testing against golden queries — fusion/ranking-code correctness against frozen vectors, not real embedding-model retrieval quality (see `jseval` for that) |
| `RelevanceMetrics` | Computes precision, recall, MRR — pure arithmetic; meaningful only relative to whatever ranked list a caller passes in |
| `RrfFusionHarness` | Tests RRF fusion ranking |
| `SoakTestRunner` | Long-running memory tests |
| `NmtMemoryTracker` | Native memory tracking |
| `HandleLeakDetector` | Detects handle leaks |
| `WorkerProcessManager` | Manages worker processes |
| `ManagedProcess` | Process lifecycle wrapper |
| `FileIntruder` | Chaos test file operations |
| `GrpcTestClient` | gRPC test client |

## Running Tests

```bash
# Integration tests
./gradlew :modules:system-tests:integrationTest

# System tests (chaos)
./gradlew :modules:system-tests:systemTest

# Soak tests
./gradlew :modules:system-tests:soakTest
```

## Dependencies

**Depends on:**
- `app-services` - App orchestration
- `indexer-worker` - Worker coordination
- `ipc-common` - gRPC IPC
- `test-support` - Test utilities
- `ai-bridge`, `indexing` - Test fixtures
