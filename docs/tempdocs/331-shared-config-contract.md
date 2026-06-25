---
title: "331: Head↔Worker Shared Config Contract"
type: tempdoc
status: done
created: 2026-03-21
updated: 2026-03-22
---

> NOTE: Noncanonical doc (architecture). May drift.

# 331: Head↔Worker Shared Config Contract

## Root Cause

The Head and Worker are separate JVMs that must agree on configuration
but have no shared config contract. Configuration reaches each process
through different mechanisms (env vars, system properties, YAML,
auto-discovery). There is no single moment where both processes confirm
they're operating with the same values. The system fails silently when
they disagree.

## Origin

Tempdoc 312 retrospective → tempdoc 329 mitigations. 329 fixed the
symptoms (forwarding registry, divergence warning, tests) but not the
structural cause: the Head and Worker resolve config independently and
can arrive at different values through different discovery paths.

## Scope

Design and implement a shared config contract so that:
1. Config resolution happens once and the result is shared
2. Divergence is impossible (not just warned about)
3. Adding a new config key cannot silently break Head↔Worker agreement

## Current Architecture (from investigation)

The worker-config-snapshot mechanism already approximates "resolve once,
share result":

1. Head builds `ResolvedConfig` (ordinals: sysprop=500, snapshot=450,
   env=400, settings=300, yaml=200, default=100)
2. Head writes `toWorkerSnapshot()` → flat `Map<String,String>` JSON
   (all resolved keys including 12 absolute-path overrides)
3. Worker loads snapshot at ordinal 450, then runs
   `contributeEnvRegistry()` on top (sysprop=500, env=400, default=100)

The snapshot IS the full resolved config — not a subset. YAML values
are captured at ordinal 450. The Worker never reads YAML directly.

## Divergence Points (5 identified)

| Point | Mechanism | Risk |
|-------|-----------|------|
| **1. Worker re-resolves env vars** | `contributeEnvRegistry()` reads Worker JVM env at ordinal 400; snapshot has Head value at 450 — snapshot wins. BUT if env var changed between Head build and Worker spawn, mismatch. | Low (env rarely changes mid-startup) |
| **2. Worker re-resolves sysprops** | Forwarded `-D` flags arrive at ordinal 500, overriding snapshot at 450. These SHOULD match Head's ordinal 500 values since they come from `collectEnvRegistrySystemProperties()`. | Low (329 §1 fixed forwarding) |
| **3. Auto-discovery runs independently** | `EmbeddingConfig.fromEnv()`, `SpladeConfig.fromEnv()` walk the filesystem in the Worker. Model paths ARE in the snapshot, but `autoDiscovered` flag isn't. | Medium (caused the 312 `enabled=false` bug) |
| **4. ORT native path derived in WorkerSpawner** | If not explicitly set, WorkerSpawner derives from llama-server variant ID and injects as `-D` at ordinal 500. Head's `ResolvedConfig` may not have this value. | Medium |
| **5. Runtime config changes** | `ConfigStoreRebuilder.rebuild()` does NOT rewrite the snapshot. Worker uses stale snapshot after settings change. | High (Worker can't pick up live changes) |

## Design

### Strategy: make the snapshot authoritative, eliminate re-resolution

The snapshot already contains the right values. The problem is that
the Worker re-resolves on top of it, potentially arriving at different
values through auto-discovery or env var changes.

### Work Items

### [x] 1. Include auto-discovery results in the snapshot

**Done (2026-03-22).** `EmbeddingConfig.fromEnv()` now reads the
snapshot's resolved `justsearch.embed.onnx.model_path` from `ConfigStore`
and passes it as the explicit path to `EmbeddingOnnxModelDiscovery.resolve()`.
This skips redundant filesystem discovery in the Worker and ensures the
Worker uses the same model path the Head resolved.

Implementation: `EmbeddingConfig.fromEnv()` in `modules/worker-core`.
Reads `store.get().resolution("justsearch.embed.onnx.model_path")` before
calling `EmbeddingOnnxModelDiscovery.resolve(snapshotModelPath)`.

### [x] 2. Worker skips re-resolution for snapshot-covered keys

**Done (2026-03-22).** By passing the snapshot model path as the explicit
argument to `EmbeddingOnnxModelDiscovery.resolve()`, auto-discovery is
skipped when the snapshot has a value. The `enabled` check now also includes
`|| snapshotModelPath != null` so snapshot-provided paths correctly enable
embedding even without an env var override.

Note: The approach is narrower than originally designed — rather than
rewriting `contributeEnvRegistry()` globally, the fix is targeted to
`EmbeddingConfig.fromEnv()` where divergence was confirmed. The ordinal
chain (sysprop=500 > snapshot=450 > env=400) still correctly allows
operator overrides via `-D` flags.

### [ ] 3. Rewrite snapshot on runtime config change

**Deferred** — requires broader audit of `ConfigStore` consumers to
determine which ones cache config at init time and can't pick up live
changes via `ConfigStore.update()`.

### [x] 4. Validate snapshot→Worker agreement at startup

**Done (2026-03-22).** `IndexerWorker.validateSnapshotAgreement()` loads
the raw snapshot (via `ResolvedConfigBuilder.loadRawWorkerSnapshotFromSysprop()`)
and compares it against the resolved `ConfigStore` for five critical keys:
`embed.onnx.model_path`, `onnxruntime.native_path`, `models.dir`,
`splade.model_path`, `index.base_path`. Logs WARN with source name and
ordinal when values differ.

Also added `ResolvedConfigBuilder.loadRawWorkerSnapshotFromSysprop()` to
expose the raw snapshot map publicly from the `configuration` module.

## Confidence Assessment

**High confidence (85%)** for items 1-2. The snapshot mechanism is
well-understood, the ordinal chain is clean, and the changes are
localized to `ResolvedConfigBuilder` and `EmbeddingConfig.fromEnv()`.

**Medium confidence (60%)** for item 3. The snapshot rewrite is simple
but the live `ReloadConfig` gRPC method requires understanding how
`ConfigStore.update()` propagates to downstream consumers (many read
config at init time and cache it). May need a broader audit of config
consumers.

**High confidence (90%)** for item 4. It's a diagnostic check with
no behavioral impact.

## Verification

### Automated (CI-safe)

- [x] `EmbeddingConfigTest` — 4 tests guard explicit-path-enabled logic
- [x] `WorkerSpawnerConfigPropagationTest` — 3 tests guard forwarding
- [ ] **New test needed:** set `EMBED_ONNX_MODEL_PATH` in snapshot but
  NOT as env var or sysprop. Verify `EmbeddingConfig.fromEnv()` returns
  `enabled=true` with the snapshot path. This validates item 1 directly.

### Live verification (requires backend)

**331 §1 — snapshot model path used before auto-discovery:**

1. Start backend with `JUSTSEARCH_EMBED_ONNX_MODEL_PATH=models/onnx/embeddinggemma-300m`
2. Ingest 10 docs, verify embedding works (coverage > 0)
3. Restart WITHOUT the env var — only the snapshot has the model path
4. Check Worker log for `ONNX model 'embeddinggemma-300m': explicit path set to`
   (should appear, proving snapshot value was used)
5. Check `/api/status` — `embeddingReady` should be true

Previously: Worker would fall back to auto-discovery, find nomic at
`models/onnx/embedding/`, silently use wrong model. Now: Worker reads
snapshot, gets EmbeddingGemma.

**331 §4 — startup validation:**

1. Start backend normally
2. Check Worker log for "Forwarding N system properties" (329 §2)
3. Check Worker log — should NOT have any "Config divergence" WARN
4. Manually set a sysprop to a different value than the snapshot:
   add `-Djustsearch.models.dir=/bogus` to the Worker command line
5. Check Worker log for WARN: "Config divergence for justsearch.models.dir"

### What these verify against original issues

| Original issue | Verification |
|----------------|-------------|
| Worker loaded wrong model silently | §1 live test: snapshot path takes priority |
| No error on config mismatch | §4 live test: WARN logged on divergence |
| `EmbeddingConfig` explicit path → disabled | `EmbeddingConfigTest` regression guard |

## Dependencies

- **329 (Head→Worker Config Pipeline):** Prerequisite (forwarding
  registry, divergence warning). Must be merged first.
- **312 (Primary Indexing Throughput):** Origin of the findings.

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Head↔Worker shared config contract design, building on 329. Subsequent config snapshot + divergence-warning work (per `docs/explanation/01-system-overview.md` Head→Worker Config Propagation section) implemented the contract.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

