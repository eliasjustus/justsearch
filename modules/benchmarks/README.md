# benchmarks

Performance microbenchmarks for indexing, vector search, quantization, and reranking.

## Overview

The `benchmarks` module contains JMH-based microbenchmarks for measuring performance of critical operations. Benchmarks cover indexing throughput, filtered kNN search, vector quantization accuracy gates, and reranker deadline compliance.

## Benchmark Classes

| Class | Purpose |
|-------|---------|
| `EngineIndexBench` | Indexing throughput microbenchmark |
| `FilteredKnnBench` | kNN search under varying filter selectivity |
| `VectorQuantizationGate` | Quantized vs float16 accuracy comparison |
| `RerankerDeadlineBench` | Cross-encoder deadline compliance |

## Running Benchmarks

```bash
# Run all benchmarks
./gradlew :modules:benchmarks:jmh

# Run specific benchmark
./gradlew :modules:benchmarks:jmh -Pjmh.includes="EngineIndexBench"
```

## Dependencies

**Depends on:**
- `adapters-lucene` - Lucene config
- `configuration` - Index profiles
- `indexing` - Indexing API
- `reranker` - Reranker benchmarks
- JMH - Benchmarking framework
