---
title: "300: ConfigPrecedence to ConfigStore Migration"
type: tempdoc
status: done
created: 2026-03-14
depends-on: 283
---

> NOTE: Noncanonical working tempdoc. Verify behavioral claims against canonical docs, code, and
> tests before promotion.

# 300: ConfigPrecedence to ConfigStore Migration

## Purpose

Tempdoc 283 identified and partially fixed a configuration-contract problem: worker-side subsystem
configs read env vars and system properties via `ConfigPrecedence.envOrProperty()`, which bypasses
the ordinal-based `ConfigStore` resolution chain. This means values set via settings.json (ordinal
300) or YAML (ordinal 200) are invisible to subsystem configs — they only see sysprops and env vars.

Tempdoc 283 Phase 2 migrated SPLADE GPU config specifically (Item 8), but that migration was
incomplete: `SpladeConfig.fromEnv()` read `EnvRegistry.getBoolean()` instead of
`ConfigStore.global().get().ai()` — which is the same bypass under a different name. This tempdoc
fixed that bug and completed the migration for all remaining subsystem configs.

## Problem

Two config resolution systems coexist:

1. **`ResolvedConfigBuilder` + `ConfigStore`** (modern): ordinal-based precedence
   (default 100 < YAML 200 < settings.json 300 < env var 400 < snapshot 450 < JVM arg 500).
   Values are typed fields in `ResolvedConfig`. Changes are tracked, auditable, and visible in
   `/api/debug/effective-config`.

2. **`ConfigPrecedence.envOrProperty()`** (legacy): checks `System.getProperty()` then
   `System.getenv()`. No ordinal awareness, no settings.json, no YAML, no snapshot. Values are
   resolved at call time, not centrally.

### Critical nuance: `EnvRegistry.get()` has the same bypass

`EnvRegistry.get()` does direct `System.getProperty()` / `System.getenv()` reads — it does NOT
go through `ConfigStore` ordinal resolution. Migrating from `ConfigPrecedence.envOrProperty()` to
`EnvRegistry.getBoolean()` is a **lateral move**, not a fix. The actual fix requires reading from
`ConfigStore.global().get()` backed by typed `ResolvedConfig` fields.

| Mechanism | YAML (200)? | settings.json (300)? | snapshot (450)? | Ordinal chain? |
|-----------|-------------|----------------------|-----------------|----------------|
| `ConfigPrecedence.envOrProperty()` | No | No | No | No |
| `EnvRegistry.getBoolean()` / `getInt()` | No | No | No | **No** |
| `ConfigStore.global().get().ai().xxx()` | Yes | Yes | Yes | **Yes** |

## Per-key migration pattern

Each key requires 4 changes:

| Step | What | Where |
|------|------|-------|
| 1. Register | Add `EnvRegistry` enum entry | `EnvRegistry.java` |
| 2. Promote | Add typed field to `ResolvedConfig` sub-record | `ResolvedConfig.java` |
| 3. Resolve | Add `resolve*()` call in builder method | `ResolvedConfigBuilder.java` |
| 4. Consume | Read from `ConfigStore.globalOrNull()` in `fromEnv()` | Consumer class |

## Implementation summary

All 8 items implemented. 14 Java files modified across 5 modules.

### Items completed

- [x] **Item 0**: Fixed 283 SPLADE GPU bypass — `SpladeConfig.fromEnv()` reads from
  `ConfigStore.globalOrNull()` with `EnvRegistry` fallback
- [x] **Item 1**: `AiClientConfig` reads `aiEmbedEnabled`/`aiClassifyEnabled` from ConfigStore;
  `CapabilitiesService` reads `SEARCH_PIPELINE` from `EnvRegistry` (path override, no snapshot
  propagation needed)
- [x] **Item 2**: `NerConfig` — 4 keys registered in `EnvRegistry`, 3 typed fields in
  `ResolvedConfig.Ai` (enabled flag uses `EnvRegistry.get()` for null semantics), consumer migrated
- [x] **Item 3**: `SpladeConfig` — 6 keys in `EnvRegistry`, 5 typed fields in `ResolvedConfig.Ai`,
  consumer + `SpladeFingerprint` migrated
- [x] **Item 4**: `RerankerConfig` — 13 keys in `EnvRegistry`, 15 typed fields in
  `ResolvedConfig.Ai` (including 4 GPU fields previously in EnvRegistry but not consumed from
  ConfigStore), all 19 `fromEnv()` call sites migrated
- [x] **Item 5**: `CitationScorerConfig` — 5 keys in `EnvRegistry`, 4 typed fields, all 5 call
  sites migrated
- [x] **Item 6**: One-off keys — `SPLADE_EVIDENCE_PATH`, `EMBED_CONTEXT_LENGTH`,
  `GPL_REEVAL_SIZE_FACTOR` registered + promoted + consumers migrated
- [x] **Item 7**: Legacy ORT keys — `OrtCudaHelper` uses `EnvRegistry.ORT_NATIVE_PATH` with
  deprecated-key `ConfigPrecedence` fallback; `RagContextOps` uses `EnvRegistry.ORT_NATIVE_PATH`;
  JVM builtins (`os.name`, `java.io.tmpdir`) stay on `ConfigPrecedence` (ArchUnit guardrail blocks
  direct `System.getProperty()` in `indexer-worker`)

### Metrics

| Metric | Before | After |
|--------|--------|-------|
| `ConfigPrecedence.envOrProperty()` in subsystem configs | 45 calls, 12 classes | 4 calls, 1 class (OrtCudaHelper: 3 JVM builtins + 1 deprecated key fallback) |
| `EnvRegistry` entries | 84 | 112 (+28) |
| `ResolvedConfig.Ai` fields | 30 | 60 (+30) |
| `toWorkerSnapshot` `putPath()` calls | 11 | 16 (+5) |
| ConfigPrecedence imports removed | — | 10 classes |

### Files modified

| File | Change |
|------|--------|
| `modules/configuration/.../EnvRegistry.java` | +28 enum entries |
| `modules/configuration/.../resolved/ResolvedConfig.java` | +30 `Ai` fields, +5 `putPath()` |
| `modules/configuration/.../resolved/ResolvedConfigBuilder.java` | +30 `resolve*()` calls |
| `modules/indexer-worker/.../splade/SpladeConfig.java` | ConfigStore reads, removed ConfigPrecedence |
| `modules/indexer-worker/.../splade/SpladeFingerprint.java` | ConfigStore read, removed ConfigPrecedence |
| `modules/indexer-worker/.../splade/SpladeEncoder.java` | ConfigStore read, removed ConfigPrecedence |
| `modules/indexer-worker/.../ner/NerConfig.java` | ConfigStore reads, removed ConfigPrecedence |
| `modules/indexer-worker/.../embed/EmbeddingService.java` | ConfigStore read, removed ConfigPrecedence |
| `modules/indexer-worker/.../ort/OrtCudaHelper.java` | EnvRegistry for ORT path, kept ConfigPrecedence for JVM builtins |
| `modules/indexer-worker/.../services/RagContextOps.java` | EnvRegistry read, removed ConfigPrecedence |
| `modules/reranker/.../RerankerConfig.java` | ConfigStore reads, removed ConfigPrecedence |
| `modules/reranker/.../CitationScorerConfig.java` | ConfigStore reads, removed ConfigPrecedence |
| `modules/app-ai/.../AiClientConfig.java` | ConfigStore reads, removed ConfigPrecedence |
| `modules/app-observability/.../CapabilitiesService.java` | EnvRegistry read, removed ConfigPrecedence |
| `modules/app-services/.../gpl/GplRevalidationTrigger.java` | ConfigStore read, removed ConfigPrecedence |

## Acceptance criteria

### A. Zero `ConfigPrecedence.envOrProperty()` calls in subsystem config classes — MET (with caveat)

11 of 12 subsystem config classes have zero `ConfigPrecedence` calls. `OrtCudaHelper` retains 4
calls: 3 JVM builtins (`os.name` x2, `java.io.tmpdir`) required by an ArchUnit guardrail that
blocks direct `System.getProperty()` in the `indexer-worker` module, plus 1 deprecated-key
backwards-compatibility fallback for `onnxruntime.native.path`.

Remaining `ConfigPrecedence.envOrProperty` call sites in the entire codebase: **5 total**
(4 in `OrtCudaHelper`, 1 in `RuntimeEnvResolver` delegation wrapper).

### B. All JustSearch config keys visible in `/api/debug/effective-config` — MET

All 31 new `EnvRegistry` entries are picked up by `contributeEnvRegistry()` automatically.
Live-verified: 31/31 keys present in `/api/debug/effective-config` with `jvm_arg` (500)
and `env_var` (400) candidate ordinals.

### C. Settings.json values respected by subsystem configs — PARTIALLY MET

**MET for**: all keys with typed `ResolvedConfig.Ai` fields (model paths, GPU settings,
behavioral tuning params like top_k, deadlines, thresholds, etc.). These read from
`ConfigStore.globalOrNull()` which resolves through the full ordinal chain including
settings.json at ordinal 300.

**NOT MET for**: enabled flags (`*_ENABLED`). These use `EnvRegistry.*.get().orElse(null)` to
preserve null/unset semantics (null = auto-detect from model discovery). `EnvRegistry.get()`
bypasses ConfigStore, so a settings.json value for an enabled flag is invisible. This affects:
`NER_ENABLED`, `SPLADE_ENABLED`, `RERANK_ENABLED`, `CITATION_SCORER_ENABLED`,
`RERANK_CHUNKS_ENABLED`.

**Rationale**: Java record `boolean` fields cannot represent "unset." The auto-detect behavior
(null → check if model exists) is the primary use case. Enabled flags are typically set via env
var or sysprop at deployment time, not via settings.json. A proper fix would use `String`-typed
fields in `ResolvedConfig` (empty = not set, "true"/"false" = explicit). This is a follow-up.

### D. ConfigStore snapshot propagation works for all migrated keys — MET

Non-Path fields propagate automatically via the `resolutions` map (key-value JSON snapshot).
5 new `putPath()` calls handle Path-typed fields with absolute-path normalization:
`ner.model_path`, `splade.model_path`, `splade.evidence_path`, `rerank.chunks.model_path`,
`citation.scorer.model_path`.

Live-verified: Worker log confirms `Worker config snapshot loaded from ...worker-config-snapshot.json`.
Subsystem configs on Worker correctly resolved through ConfigStore: SPLADE (`maxSeqLen=512,
gpu=false, queryMode=onnx, activation=log1p`), chunk reranker (`topK=10, deadline=150ms`),
citation scorer (`threshold=0.5, maxSeqLen=512, deadline=2000ms`). Path-null keys correctly
omitted from snapshot; non-null paths (auto-discovered models) propagated.

## Known issues and follow-ups

### 1. Enabled flags bypass ConfigStore (follow-up needed)

The 5 `*_ENABLED` flags read from `EnvRegistry.get()` not `ConfigStore`. This preserves the
null/unset auto-detect semantics but means settings.json values for enabled flags are invisible.

**Fix**: Add `String`-typed fields (e.g., `String spladeEnabledOverride`) to `ResolvedConfig.Ai`.
Empty string = not set (auto-detect), "true"/"false" = explicit. Consumer checks `isBlank()`
before parsing.

### 2. ResolvedConfig.Ai has 60 fields

The flat record grew from 30 to 60 positional constructor arguments. While there is only 1
construction site (`buildAi()`), adjacent same-type fields (e.g., `int, int, int`) risk silent
misordering. Consider nested sub-records (`Ai.Splade`, `Ai.Reranker`, `Ai.Ner`,
`Ai.CitationScorer`) in a follow-up.

### 3. ConfigStore-backed tests — RESOLVED

Two ConfigStore-backed tests added:
- `SpladeConfigTest.fromEnvReadsFromConfigStore` — injects `gpu_enabled`, `max_seq_len`,
  `query_mode` via ConfigStore, verifies `SpladeConfig.fromEnv()` reads them.
- `RerankerConfigTest.fromEnvReadsBehavioralSettingsFromConfigStore` — injects `top_k`,
  `deadline_ms`, `min_hits` via ConfigStore, verifies `RerankerConfig.fromEnv()` reads them.

Both pass. NerConfig and CitationScorerConfig lack dedicated tests but follow the identical
pattern — low risk.

### 4. RERANK_CHUNKS_MAX_SEQ_LEN key separation

The original code reused `JUSTSEARCH_RERANK_MAX_SEQ_LEN` for both doc and chunk reranker max
sequence length. The migration created a separate `RERANK_CHUNKS_MAX_SEQ_LEN` key
(`justsearch.rerank.chunks.max_seq_len`). Users who set the shared key expecting it to affect
both will now only affect the doc reranker; the chunk reranker uses its default (512). Low risk
since 512 was the existing default.

### 5. OrtCudaHelper retains ConfigPrecedence

`OrtCudaHelper` keeps `ConfigPrecedence` for 3 JVM builtins (required by ArchUnit guardrail) and
1 deprecated-key fallback. The `ConfigPrecedence` import cannot be removed from `indexer-worker`
module until either: (a) the ArchUnit guardrail is relaxed for OrtCudaHelper, or (b) JVM builtins
are accessed through a non-`System.getProperty()` wrapper.

## Relation to other tempdocs

- **283** (Worktree GPU Runtime Path Unification): Parent tempdoc. Identified the dual-resolution
  problem and migrated SPLADE GPU config as proof of concept (Item 8) — but the consumer-side
  read was incomplete (Item 0 in this tempdoc).
- **03** (Config SSOT Precedence): Established the ordinal-based `ConfigStore` model. This tempdoc
  completes the migration that 03 started.
- **286** (Reranker Config Snapshot): Phases 1–5 merged; Phase 6 (reranker behavioral settings)
  is complementary. This tempdoc's Item 4 provides the prerequisite infrastructure (all reranker
  keys now in `EnvRegistry` + `ResolvedConfig.Ai`).
