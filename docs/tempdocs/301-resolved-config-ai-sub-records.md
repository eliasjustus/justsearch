---
title: "301: ResolvedConfig.Ai Sub-Record Restructuring"
type: tempdoc
status: done
created: 2026-03-14
depends-on: 300
---

> NOTE: Noncanonical working tempdoc. Verify behavioral claims against canonical docs, code, and
> tests before promotion.

# 301: ResolvedConfig.Ai Sub-Record Restructuring

## Purpose

`ResolvedConfig.Ai` grew from 30 to 60 positional constructor arguments after tempdoc 300
(ConfigPrecedence to ConfigStore migration). Java records use positional constructors — adjacent
same-type fields (e.g., five consecutive `int` fields for reranker settings) can be silently
swapped without compiler error. This tempdoc restructures `Ai` into nested sub-records to provide
type-safe grouping.

## Problem

```java
public record Ai(
    Path serverExe,           // position 0
    int gpuLayers,            // position 1
    // ... 58 more fields ...
    double gplReevalSizeFactor // position 59
) {}
```

The `buildAi()` constructor call in `ResolvedConfigBuilder` passes 60 arguments in exact
positional order. Five adjacent `int` fields exist for reranker behavioral settings alone:

```java
int rerankTopK,                    // position 41
int rerankDeadlineMs,              // position 42
int rerankMinHits,                 // position 43
int rerankMaxSeqLen,               // position 44
int rerankMaxAvgDocLengthChars,    // position 45
```

Swapping any two compiles successfully but silently assigns wrong values. The same risk exists
for chunk reranker fields, SPLADE fields, and NER fields.

**Mitigating factors**: Only 1 construction site exists (`ResolvedConfigBuilder.buildAi()`).
Tests go through the builder automatically via `TestResolvedConfigHelper`. No reflection or
manual deserialization constructs `Ai` directly.

**Why act now**: The record will only grow as more subsystem config keys are promoted to
`ResolvedConfig`. The restructuring is easier to do now (before more accessor call sites
accumulate) than later.

## Proposed structure

Replace the flat `Ai` record with nested sub-records:

```java
public record Ai(
    // Core AI infrastructure (existing 20 fields)
    Path serverExe,
    int gpuLayers,
    Path embedModel,
    Path llmModelPath,
    boolean disabled,
    boolean llmEnabled,
    String llmMode,
    String llmBackend,
    int contextSize,
    String vlmModel,
    String mmprojModel,
    int embedDimensionOverride,
    int embedGpuLayers,
    int embedGpuMemMb,
    boolean aiEmbedEnabled,
    boolean aiClassifyEnabled,
    boolean useThinking,
    int reasoningBudget,
    String onnxruntimeVariantId,
    String serverExeSource,
    // VRAM thresholds
    long vramThreshold12gb,
    long vramThreshold8gb,
    long vramThreshold4gb,
    // Embedding
    String embedModelName,
    String embedBackend,
    int embedContextLength,
    // Sub-records
    Splade splade,
    Ner ner,
    Reranker reranker,
    CitationScorer citationScorer,
    double gplReevalSizeFactor
) {
    public record Splade(
        boolean gpuEnabled, int gpuDeviceId, int gpuMemMb,
        Path modelPath, int maxSeqLen, String queryMode,
        String activation, Path evidencePath
    ) {}

    public record Ner(
        Path modelPath, int maxSeqLen, double confidenceThreshold
    ) {}

    public record Reranker(
        Path modelPath, boolean gpuEnabled, int gpuDeviceId, int gpuMemMb,
        int topK, int deadlineMs, int minHits, int maxSeqLen,
        int maxAvgDocLengthChars,
        ChunkReranker chunks
    ) {
        public record ChunkReranker(
            Path modelPath, boolean gpuEnabled, int gpuDeviceId,
            int topK, int maxGpuCandidates, int deadlineMs,
            int minHits, int maxSeqLen, String order
        ) {}
    }

    public record CitationScorer(
        Path modelPath, double threshold, int maxSeqLen, int deadlineMs
    ) {}
}
```

This reduces the top-level `Ai` from 60 fields to ~28, and gives each subsystem a distinct type.

## Impact

### Accessor call sites

Every `ai.spladeGpuEnabled()` becomes `ai.splade().gpuEnabled()`. All consumer `fromEnv()`
methods that read from `ConfigStore.globalOrNull()` need updating. Count to verify before
implementation.

### ResolvedConfigBuilder.buildAi()

Splits into `buildAi()` + `buildSplade()` + `buildNer()` + `buildReranker()` +
`buildChunkReranker()` + `buildCitationScorer()`. Each sub-builder constructs its sub-record.

### toWorkerSnapshot

No change needed — the `putPath()` calls reference fields through the sub-records
(`ai.splade().modelPath()` instead of `ai.spladeModelPath()`).

### Tests

`TestResolvedConfigHelper` goes through the builder — automatically handles the restructuring.
`ConfigParityTest` only tests 2 top-level `Ai` fields — unaffected. Consumer tests that construct
configs via `fromEnv()` are also unaffected (they don't touch `ResolvedConfig.Ai` directly).

### Worker snapshot serialization

No change — the snapshot is key-value based. The restructuring is internal to the record hierarchy.

## Scope

### In scope
- Restructure `ResolvedConfig.Ai` into nested sub-records
- Update `ResolvedConfigBuilder.buildAi()` to construct sub-records
- Update all accessor call sites across the codebase

### Out of scope
- Adding new config keys (that's tempdoc 300's domain)
- Migrating enabled flags to String-typed fields (separate follow-up from 294)
- Restructuring other `ResolvedConfig` sub-records (`Llm`, `Index`, etc.)

## Implementation summary

9 Java files modified. 48 accessor call sites updated. 5 sub-builder methods extracted.

### Sub-records created

| Sub-record | Fields | Largest same-type adjacency |
|------------|--------|---------------------------|
| `Ai.Splade` | 8 | 2 (`int gpuDeviceId, int gpuMemMb`) |
| `Ai.Ner` | 3 | none (Path, int, double) |
| `Ai.Reranker` | 10 | 7 (`int gpuDeviceId..maxAvgDocLengthChars`) |
| `Ai.Reranker.ChunkReranker` | 9 | 4 (`int topK..minHits`) |
| `Ai.CitationScorer` | 4 | 2 (`int maxSeqLen, int deadlineMs`) |

### Metrics

| Metric | Before | After |
|--------|--------|-------|
| Top-level `Ai` fields | 60 | 31 (20 core + 3 VRAM + 2 embed + 2 misc + 4 sub-records) |
| `buildAi()` lines | 67 | 27 (delegates to 5 sub-builders) |
| Max same-type adjacency in `Ai` | 5 `int` fields | 3 `long` fields (VRAM thresholds) |

### Files modified

| File | Change |
|------|--------|
| `modules/configuration/.../ResolvedConfig.java` | 33 flat fields → 4 sub-record params + 5 nested record types; toWorkerSnapshot accessors updated |
| `modules/configuration/.../ResolvedConfigBuilder.java` | `buildAi()` split into 6 methods (`buildAi` + 5 sub-builders) |
| `modules/indexer-worker/.../splade/SpladeConfig.java` | 10 accessor updates (`ai.spladeXxx()` → `ai.splade().xxx()`) |
| `modules/indexer-worker/.../splade/SpladeFingerprint.java` | 2 accessor updates |
| `modules/indexer-worker/.../splade/SpladeEncoder.java` | 2 accessor updates |
| `modules/indexer-worker/.../ner/NerConfig.java` | 4 accessor updates |
| `modules/reranker/.../RerankerConfig.java` | 20 accessor updates (doc + chunk reranker) |
| `modules/reranker/.../CrossEncoderReranker.java` | 1 accessor update |
| `modules/reranker/.../CitationScorerConfig.java` | 5 accessor updates |

## Acceptance criteria

### A. Compilation succeeds with zero accessor changes missed — MET
All 48 call sites updated. `./gradlew.bat compileJava` passes.

### B. All existing tests pass — MET
All tests in affected modules pass. Pre-existing failures in unrelated modules
(`InferenceConfigFromEnvironmentTest`, `UnreferencedCodeTest`, `WorkerOperationalView` tests)
are unchanged.

### C. Top-level Ai field count reduced below 35 — MET
31 top-level fields (was 60). Maximum sub-record size is 10 (`Reranker`).

### D. Live runtime verification — MET

Sub-record accessors work end-to-end in the running system. Worker logs confirm all subsystem
configs resolved correctly through the new `ai.splade().xxx()`, `ai.reranker().xxx()`,
`ai.citationScorer().xxx()` accessor paths:
- SPLADE: `maxSeqLen=512, gpu=false, queryMode=onnx, activation=log1p`
- Chunk reranker: `topK=10, deadline=150ms, gpuEnabled=false`
- Citation scorer: `threshold=0.5, maxSeqLen=512, deadline=2000ms`
- OnnxModelDiscovery: auto-discovered reranker and citation-scorer models

## Known issues

### 1. Reranker sub-record has 7 adjacent int fields

Consolidating `gpuMemMb` (previously at position 20 in the flat Ai record, separated from
behavioral ints by 20 other fields) into the `Reranker` sub-record places it adjacent to
`gpuDeviceId` and `topK`, creating a 7-adjacent-int run (positions 2–8). The original flat
record had a 6-adjacent-int run for reranker behavioral fields.

**Mitigating factors**: The sub-record has 10 total fields (vs 60 in the flat record), only 1
construction site exists (`buildReranker()`), and the field names are semantically distinct.
Visual audit of 10 fields is far more tractable than 60. The risk is marginal and does not
warrant further restructuring (splitting Reranker into Reranker + RerankerGpu would add
complexity for minimal benefit).
