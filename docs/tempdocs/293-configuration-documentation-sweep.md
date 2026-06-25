---
title: "Tempdoc 293 — Configuration Documentation Sweep"
---

# Tempdoc 293 — Configuration Documentation Sweep

**Status:** Complete
**Created:** 2026-03-14
**Goal:** Bring the canonical configuration documentation up to date with all runtime knobs introduced by recent feature work, ensuring that operators and agents can discover and understand every tunable parameter.

## Context

Recent tempdocs (273–280, 283, 286) introduced numerous new configuration keys for SPLADE integration, CC fusion weights, branch fusion strategy, chunk-aware merge, model path resolution, and startup performance. These keys are registered in `ResolvedConfigBuilder` with env var and sysprop paths, but several are absent from the canonical environment variables reference doc.

## Known Gaps

### G1: 8+ new hybrid fusion env vars undocumented

The following env vars exist in `ResolvedConfigBuilder.contributeYamlHybridSearch()` but are absent from `docs/reference/configuration/environment-variables.md`:

| Env Var | Sysprop | Default | Purpose |
|---------|---------|---------|---------|
| `JUSTSEARCH_HYBRID_CC_WEIGHT_SPARSE` | `justsearch.hybrid.cc_weight_sparse` | 0.35 | CC sparse (BM25) weight |
| `JUSTSEARCH_HYBRID_CC_WEIGHT_DENSE` | `justsearch.hybrid.cc_weight_dense` | 0.35 | CC dense (KNN) weight |
| `JUSTSEARCH_HYBRID_CC_WEIGHT_SPLADE` | `justsearch.hybrid.cc_weight_splade` | 0.30 | CC SPLADE weight |
| `JUSTSEARCH_HYBRID_BRANCH_FUSION_STRATEGY` | `justsearch.hybrid.branch_fusion_strategy` | `cc` | Branch fusion algorithm (`cc` or `rrf`) |
| `JUSTSEARCH_HYBRID_BRANCH_CC_ZERO_EXCLUDE` | `justsearch.hybrid.branch_cc_zero_exclude` | `true` | Exclude zero-scored docs from CC |
| `JUSTSEARCH_HYBRID_BRANCH_CC_WEIGHT_WHOLE` | `justsearch.hybrid.branch_cc_weight_whole` | 0.50 | Whole-doc branch CC weight |
| `JUSTSEARCH_HYBRID_BRANCH_CC_WEIGHT_CHUNK` | `justsearch.hybrid.branch_cc_weight_chunk` | 0.50 | Chunk branch CC weight |
| `JUSTSEARCH_HYBRID_BRANCH_CHUNK_MIN_WEIGHT_MULTIPLIER` | `justsearch.hybrid.branch_chunk_min_weight_multiplier` | 0.25 | Minimum chunk branch weight for short docs |

### G2: `chunkAwareEnabled` has no env var override

`search.chunk_aware.enabled` is a YAML-only key (default `true`). Operators cannot toggle chunk-aware merge via env var or sysprop — only by editing YAML. This may be intentional (it's a significant behavioral change) but should at minimum be documented.

### G3: Other recently-added config keys to audit

Config keys from tempdocs 283 and 286 (SPLADE/reranker model paths, ResolvedPathResolver, ONNX paths) were partially documented. A sweep of `ResolvedConfigBuilder` for any remaining undocumented keys would close the gap.

### G4: Runtime config ownership matrix may be stale

`docs/reference/configuration/runtime-config-ownership-matrix.md` maps config surfaces to ownership. The new `ResolvedConfig.HybridSearch` fields may not be reflected.

## Action Items

- [x] Add all G1 env vars to `docs/reference/configuration/environment-variables.md` — added 11 new env vars under "Hybrid Fusion (CC / Branch)" section
- [x] Document `search.chunk_aware.enabled` (YAML-only) — added new "YAML-Only Keys" section in env vars doc
- [x] Sweep `ResolvedConfigBuilder` for any other undocumented keys from recent work — `JUSTSEARCH_HYBRID_CC_ALPHA` and `JUSTSEARCH_HYBRID_CC_ZERO_EXCLUDE` also found and added
- [x] Check runtime config ownership matrix for staleness — added 13 new rows for CC/branch/chunk-aware keys (manual addition; generator script only scans RuntimeConfig, not ResolvedConfigBuilder)
- [x] Verify `docs/explanation/18-adapters-lucene-deep-dive.md` §10 (Configuration Reference) — restructured §10.1 into CC, branch fusion, RRF, and low-signal sub-tables with all new parameters
