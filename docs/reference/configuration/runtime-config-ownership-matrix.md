---
title: Runtime Config Ownership Matrix
type: reference
status: stable
description: "Canonical YAML/env/sysprop ownership and precedence map."
---

# Runtime Config Ownership Matrix

Generated from `modules/configuration/src/main/java/io/justsearch/configuration/EnvRegistry.java`, `modules/configuration/src/main/java/io/justsearch/configuration/ConfigKey.java`, and `modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfigBuilder.java` on 2026-06-29.

Precedence note:
1. `YAML > sysprop > env > default` where a YAML key and env/sysprop fallback both exist.
2. `YAML > default` for YAML-only keys (ConfigKey entries, no env var override).
3. `sysprop > env > default` for env/sysprop-only runtime knobs.

| YAML key | Env var | System property | EnvRegistry constant | Owner module | Precedence notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| egress.block_all | JUSTSEARCH_EGRESS_BLOCK_ALL | egress.block_all | EGRESS_BLOCK_ALL | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.auto_recovery | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.boosts | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.collections | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.commit.debounce_ms | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.commit.meta.enabled | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.commit.policy | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.directory.type | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.hybrid.adaptive_weights_enabled | JUSTSEARCH_HYBRID_ADAPTIVE_WEIGHTS_ENABLED | index.hybrid.adaptive_weights_enabled | HYBRID_ADAPTIVE_WEIGHTS_ENABLED | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.bm25_low_signal_top_score_threshold | JUSTSEARCH_INDEX_BM25_LOW_SIGNAL_TOP_SCORE_THRESHOLD | index.hybrid.bm25_low_signal_top_score_threshold | HYBRID_BM25_LOW_SIGNAL_TOP_SCORE_THRESHOLD | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.bm25_low_signal_total_hits_threshold | JUSTSEARCH_INDEX_BM25_LOW_SIGNAL_TOTAL_HITS_THRESHOLD | index.hybrid.bm25_low_signal_total_hits_threshold | HYBRID_BM25_LOW_SIGNAL_TOTAL_HITS_THRESHOLD | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.bm25_score_boost_weight | JUSTSEARCH_INDEX_BM25_SCORE_BOOST_WEIGHT | index.hybrid.bm25_score_boost_weight | HYBRID_BM25_SCORE_BOOST_WEIGHT | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.branch_cc_weight_chunk | JUSTSEARCH_HYBRID_BRANCH_CC_WEIGHT_CHUNK | index.hybrid.branch_cc_weight_chunk | HYBRID_BRANCH_CC_WEIGHT_CHUNK | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.branch_cc_weight_whole | JUSTSEARCH_HYBRID_BRANCH_CC_WEIGHT_WHOLE | index.hybrid.branch_cc_weight_whole | HYBRID_BRANCH_CC_WEIGHT_WHOLE | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.branch_cc_zero_exclude | JUSTSEARCH_HYBRID_BRANCH_CC_ZERO_EXCLUDE | index.hybrid.branch_cc_zero_exclude | HYBRID_BRANCH_CC_ZERO_EXCLUDE | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.branch_chunk_min_weight_multiplier | JUSTSEARCH_HYBRID_BRANCH_CHUNK_MIN_WEIGHT_MULTIPLIER | index.hybrid.branch_chunk_min_weight_multiplier | HYBRID_BRANCH_CHUNK_MIN_WEIGHT_MULTIPLIER | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.branch_fusion_strategy | JUSTSEARCH_HYBRID_BRANCH_FUSION_STRATEGY | index.hybrid.branch_fusion_strategy | HYBRID_BRANCH_FUSION_STRATEGY | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.candidate_limit_max | JUSTSEARCH_INDEX_HYBRID_CANDIDATE_LIMIT_MAX | index.hybrid.candidate_limit_max | HYBRID_CANDIDATE_LIMIT_MAX | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.cc_alpha | JUSTSEARCH_HYBRID_CC_ALPHA | index.hybrid.cc_alpha | HYBRID_CC_ALPHA | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.cc_weight_dense | JUSTSEARCH_HYBRID_CC_WEIGHT_DENSE | index.hybrid.cc_weight_dense | HYBRID_CC_WEIGHT_DENSE | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.cc_weight_sparse | JUSTSEARCH_HYBRID_CC_WEIGHT_SPARSE | index.hybrid.cc_weight_sparse | HYBRID_CC_WEIGHT_SPARSE | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.cc_weight_splade | JUSTSEARCH_HYBRID_CC_WEIGHT_SPLADE | index.hybrid.cc_weight_splade | HYBRID_CC_WEIGHT_SPLADE | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.cc_zero_exclude | JUSTSEARCH_HYBRID_CC_ZERO_EXCLUDE | index.hybrid.cc_zero_exclude | HYBRID_CC_ZERO_EXCLUDE | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.fusion_strategy | JUSTSEARCH_HYBRID_FUSION_STRATEGY | index.hybrid.fusion_strategy | HYBRID_FUSION_STRATEGY | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| - | JUSTSEARCH_HYBRID_LEG_ARBITRATION_ALPHA_DIVERGE | index.hybrid.leg_arbitration_alpha_diverge | HYBRID_LEG_ARBITRATION_ALPHA_DIVERGE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_HYBRID_LEG_ARBITRATION_BM25_INCOHERENCE_MIN | index.hybrid.leg_arbitration_bm25_incoherence_min | HYBRID_LEG_ARBITRATION_BM25_INCOHERENCE_MIN | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_HYBRID_LEG_ARBITRATION_ENABLED | index.hybrid.leg_arbitration_enabled | HYBRID_LEG_ARBITRATION_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_HYBRID_RERANK_POOL_RECALL_COMPLETE | index.hybrid.leg_recall_complete_enabled | HYBRID_RERANK_POOL_RECALL_COMPLETE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_HYBRID_RERANK_POOL_TOP_N | index.hybrid.leg_recall_complete_top_n | HYBRID_RERANK_POOL_TOP_N | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| index.hybrid.rrf_k | JUSTSEARCH_INDEX_RRF_K | index.hybrid.rrf_k | HYBRID_RRF_K | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.text_candidate_multiplier | JUSTSEARCH_INDEX_HYBRID_TEXT_CANDIDATE_MULTIPLIER | index.hybrid.text_candidate_multiplier | HYBRID_TEXT_CANDIDATE_MULTIPLIER | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.vector_candidate_multiplier | JUSTSEARCH_INDEX_HYBRID_VECTOR_CANDIDATE_MULTIPLIER | index.hybrid.vector_candidate_multiplier | HYBRID_VECTOR_CANDIDATE_MULTIPLIER | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.vector_low_signal_top_score_threshold | JUSTSEARCH_INDEX_VECTOR_LOW_SIGNAL_TOP_SCORE_THRESHOLD | index.hybrid.vector_low_signal_top_score_threshold | HYBRID_VECTOR_LOW_SIGNAL_TOP_SCORE_THRESHOLD | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.vector_only_cap_low_signal | JUSTSEARCH_INDEX_VECTOR_ONLY_CAP_LOW_SIGNAL | index.hybrid.vector_only_cap_low_signal | HYBRID_VECTOR_ONLY_CAP_LOW_SIGNAL | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.vector_rrf_weight | JUSTSEARCH_INDEX_VECTOR_RRF_WEIGHT | index.hybrid.vector_rrf_weight | HYBRID_VECTOR_RRF_WEIGHT | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.vector_rrf_weight_low_signal | JUSTSEARCH_INDEX_VECTOR_RRF_WEIGHT_LOW_SIGNAL | index.hybrid.vector_rrf_weight_low_signal | HYBRID_VECTOR_RRF_WEIGHT_LOW_SIGNAL | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.hybrid.vector_skip_min_chars | JUSTSEARCH_INDEX_VECTOR_SKIP_MIN_CHARS | index.hybrid.vector_skip_min_chars | HYBRID_VECTOR_SKIP_MIN_CHARS | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.integrity_check | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.merge.tiered.max_merged_segment_mb | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.merge.tiered.segs_per_tier | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| - | JUSTSEARCH_INDEX_MIGRATION_CUTOVER_MAX_FAILED_JOBS | index.migration.cutover.max_failed_jobs | INDEX_MIGRATION_CUTOVER_MAX_FAILED_JOBS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| index.nrt.max_stale_ms | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.nrt.target_max_stale_ms | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.ocr.enabled | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.ocr.languages | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.ocr.limits.max_image_dimension | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.ocr.limits.max_image_pixels | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.ocr.limits.max_pages | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.ocr.limits.per_file_timeout_ms | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.ocr.trigger.min_image_pixels | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.queue.max_depth | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.recovery.policy | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.schema_mismatch.policy | JUSTSEARCH_INDEX_SCHEMA_MISMATCH_POLICY | index.schema_mismatch.policy | INDEX_SCHEMA_MISMATCH_POLICY | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.similarity.text.b | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.similarity.text.k1 | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.similarity.text.type | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.soft_deletes.field | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.soft_deletes.retention.days | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.soft_deletes.retention.enabled | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.soft_deletes.retention.max_versions | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.sort | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.validation.mode | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.vector.dimension | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.vector.ef_search | JUSTSEARCH_INDEX_VECTOR_EF_SEARCH | index.vector.ef_search | INDEX_VECTOR_EF_SEARCH | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.vector.hnsw.ef_construction | JUSTSEARCH_INDEX_VECTOR_HNSW_EF_CONSTRUCTION | index.vector.hnsw.ef_construction | INDEX_VECTOR_HNSW_EF_CONSTRUCTION | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.vector.hnsw.m | JUSTSEARCH_INDEX_VECTOR_HNSW_M | index.vector.hnsw.m | INDEX_VECTOR_HNSW_M | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.vector.quantization.enabled | JUSTSEARCH_INDEX_VECTOR_QUANTIZATION_ENABLED | index.vector.quantization.enabled | INDEX_VECTOR_QUANTIZATION_ENABLED | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| index.watcher.debounce_ms | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.watcher.overflow.rescan_on_overflow | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.watcher.polling.interval_ms | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.watcher.queue.max_entries | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.watcher.strategy | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.writer.max_buffered_docs | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| index.writer.ram_buffer_mb | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| - | JUSTSEARCH_INDEXER_WORKER_VERSION | indexer.worker.version | INDEXER_WORKER_VERSION | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| infra.health.poll_interval_ms | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| infra.health.thresholds.ann_cache_ready_percent | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| infra.health.thresholds.nrt_stale_ms | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| infra.health.thresholds.translator_handshake_stale_ms | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| - | JUSTSEARCH_AGENT_BROWSE_DEFAULT_MAX_FOLDERS | justsearch.agent.browse.default_max_folders | AGENT_BROWSE_DEFAULT_MAX_FOLDERS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_ENABLED | justsearch.agent.context_compression.enabled | AGENT_CONTEXT_COMPRESSION_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_KEEP_LAST_RESULTS | justsearch.agent.context_compression.keep_last_results | AGENT_CONTEXT_COMPRESSION_KEEP_LAST_RESULTS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_MIN_CHARS | justsearch.agent.context_compression.min_chars | AGENT_CONTEXT_COMPRESSION_MIN_CHARS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_AGENT_MAX_COMPLETION_TOKENS | justsearch.agent.max_completion_tokens | AGENT_MAX_COMPLETION_TOKENS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_AGENT_MAX_TOOL_RESULT_CHARS | justsearch.agent.max_tool_result_chars | AGENT_MAX_TOOL_RESULT_CHARS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_AGENT_SEARCH_DEFAULT_LIMIT | justsearch.agent.search.default_limit | AGENT_SEARCH_DEFAULT_LIMIT | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_AGENT_SEARCH_DEFAULT_MODE | justsearch.agent.search.default_mode | AGENT_SEARCH_DEFAULT_MODE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_AI_CLASSIFY_ENABLED | justsearch.ai.classify.enabled | AI_CLASSIFY_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| justsearch.ai.deadlineMs | JUSTSEARCH_AI_DEADLINE_MS | justsearch.ai.deadlineMs | AI_DEADLINE_MS | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| - | JUSTSEARCH_AI_DISABLED | justsearch.ai.disabled | AI_DISABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_AI_EMBED_ENABLED | justsearch.ai.embed.enabled | AI_EMBED_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| justsearch.ai.host | JUSTSEARCH_AI_HOST | justsearch.ai.host | AI_HOST | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| justsearch.ai.port | JUSTSEARCH_AI_PORT | justsearch.ai.port | AI_PORT | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| - | JUSTSEARCH_API_PORT | justsearch.api.port | API_PORT | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_BGE_M3_ENABLED | justsearch.bgem3.enabled | BGE_M3_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_BGE_M3_GPU_DEVICE_ID | justsearch.bgem3.gpu_device_id | BGE_M3_GPU_DEVICE_ID | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_BGE_M3_GPU_ENABLED | justsearch.bgem3.gpu_enabled | BGE_M3_GPU_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_BGE_M3_GPU_MEM_MB | justsearch.bgem3.gpu_mem_mb | BGE_M3_GPU_MEM_MB | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_BGE_M3_MAX_SEQ_LEN | justsearch.bgem3.max_seq_len | BGE_M3_MAX_SEQ_LEN | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_BGE_M3_MODEL_PATH | justsearch.bgem3.model_path | BGE_M3_MODEL_PATH | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_BUILD_STAMP | justsearch.build.stamp | BUILD_STAMP | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_CITATION_MATCH_THRESHOLD | justsearch.citation.match_threshold | CITATION_MATCH_THRESHOLD | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_CITATION_SCORER_DEADLINE_MS | justsearch.citation.scorer.deadline_ms | CITATION_SCORER_DEADLINE_MS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_CITATION_SCORER_ENABLED | justsearch.citation.scorer.enabled | CITATION_SCORER_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_CITATION_SCORER_MAX_SEQ_LEN | justsearch.citation.scorer.max_seq_len | CITATION_SCORER_MAX_SEQ_LEN | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_CITATION_SCORER_MODEL_PATH | justsearch.citation.scorer.model_path | CITATION_SCORER_MODEL_PATH | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_CITATION_SCORER_THRESHOLD | justsearch.citation.scorer.threshold | CITATION_SCORER_THRESHOLD | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_CONFIG | justsearch.config | CONFIG_PATH | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_CONTEXT_SIZE | justsearch.context.size | CONTEXT_SIZE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_DATA_DIR | justsearch.data.dir | DATA_DIR | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_DEV_DEBUG_PORT | justsearch.dev.debug.port | DEV_DEBUG_PORT | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_DEV_HOTRELOAD | justsearch.dev.hotreload | DEV_HOTRELOAD | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_DEV_HOTRELOAD_CLASSES_DIR | justsearch.dev.hotreload.classesDir | DEV_HOTRELOAD_CLASSES_DIR | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_EMBED_BACKEND | justsearch.embed.backend | EMBED_BACKEND | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_EMBED_CONTEXT_LENGTH | justsearch.embed.context_length | EMBED_CONTEXT_LENGTH | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_EMBED_DIM | justsearch.embed.dimension | EMBED_DIMENSION_OVERRIDE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_EMBED_GPU_MEM_MB | justsearch.embed.gpu_mem_mb | EMBED_GPU_MEM_MB | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_EMBED_GPU_DEVICE_ID | justsearch.embed.gpu.device_id | EMBED_GPU_DEVICE_ID | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_EMBED_GPU_ENABLED | justsearch.embed.gpu.enabled | EMBED_GPU_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_EMBED_ONNX_MODEL_PATH | justsearch.embed.onnx.model_path | EMBED_ONNX_MODEL_PATH | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND | justsearch.extraction.sandbox.command | EXTRACTION_SANDBOX_COMMAND | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_EXTRACTION_SANDBOX_MODE | justsearch.extraction.sandbox.mode | EXTRACTION_SANDBOX_MODE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_FIELD_CATALOG | justsearch.fieldCatalog | FIELD_CATALOG | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_FILTER_NORM_ENABLED | justsearch.filter_norm.enabled | FILTER_NORM_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_GPL_REEVAL_SIZE_FACTOR | justsearch.gpl.reeval_size_factor | GPL_REEVAL_SIZE_FACTOR | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_GPU_ENABLED | justsearch.gpu.enabled | GPU_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_GPU_LAYERS | justsearch.gpu.layers | GPU_LAYERS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_HEAD_PID | justsearch.head.pid | HEAD_PID | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_HEAD_BUILD_STAMP | justsearch.head.stamp | HEAD_BUILD_STAMP | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_HEAD_TRACING_LEVEL | justsearch.head.tracing_level | HEAD_TRACING_LEVEL | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_HOME | justsearch.home | HOME | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_INDEX_BASE_PATH | justsearch.index.base_path | INDEX_BASE_PATH | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_INDEX_COLLECTION | justsearch.index.collection | INDEX_COLLECTION | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_INDEX_DEFAULT_LANGUAGE | justsearch.index.default_language | INDEX_DEFAULT_LANGUAGE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_INDEX_PARITY_ALLOW_MISMATCH | justsearch.index.parity.allow_mismatch | INDEX_PARITY_ALLOW_MISMATCH | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_INDEX_TRACING_LEVEL | justsearch.index.tracing_level | INDEX_TRACING_LEVEL | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| justsearch.indexer.deadlineMs | JUSTSEARCH_INDEXER_DEADLINE_MS | justsearch.indexer.deadlineMs | INDEXER_DEADLINE_MS | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| justsearch.indexer.host | JUSTSEARCH_INDEXER_HOST | justsearch.indexer.host | INDEXER_HOST | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| justsearch.indexer.maxInFlightBytes | JUSTSEARCH_INDEXER_MAX_INFLIGHT_BYTES | justsearch.indexer.maxInFlightBytes | INDEXER_MAX_INFLIGHT_BYTES | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| justsearch.indexer.port | JUSTSEARCH_INDEXER_PORT | justsearch.indexer.port | INDEXER_PORT | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| justsearch.indexer.queueSize | JUSTSEARCH_INDEXER_QUEUE_SIZE | justsearch.indexer.queueSize | INDEXER_QUEUE_SIZE | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| - | JUSTSEARCH_INFRA_HEALTH_HOST | justsearch.infra.health.host | INFRA_HEALTH_HOST | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_INFRA_HEALTH_PORT | justsearch.infra.health.port | INFRA_HEALTH_PORT | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_INGESTION_SKIP_DIRECTORY_NAMES | justsearch.ingestion.skip.directory_names | INGESTION_SKIP_DIRECTORY_NAMES | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_INGESTION_SKIP_EXTENSIONS | justsearch.ingestion.skip.extensions | INGESTION_SKIP_EXTENSIONS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_INGESTION_SKIP_PATTERNS | justsearch.ingestion.skip.patterns | INGESTION_SKIP_PATTERNS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LAMBDAMART_ENABLED | justsearch.lambdamart.enabled | LAMBDAMART_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LITE_MODE | justsearch.lite.mode | LITE_MODE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_ALLOW_REMOTE | justsearch.llm.allow_remote | LLM_ALLOW_REMOTE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_BACKEND | justsearch.llm.backend | LLM_BACKEND | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_BACKEND_SELECTOR | justsearch.llm.backend_selector | LLM_BACKEND_SELECTOR | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_BACKEND_SUPPORTS | justsearch.llm.backend_supports | LLM_BACKEND_SUPPORTS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_CONTEXT_LENGTH | justsearch.llm.context_length | LLM_CONTEXT_LENGTH | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_DEADLINE_MS | justsearch.llm.deadline_ms | LLM_DEADLINE_MS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_ENABLE_JSON_GUARD | justsearch.llm.enable_json_guard | LLM_ENABLE_JSON_GUARD | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| justsearch.llm.enabled | JUSTSEARCH_LLM_ENABLED | justsearch.llm.enabled | LLM_ENABLED | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| - | JUSTSEARCH_LLM_GPU_LAYERS | justsearch.llm.gpu_layers | LLM_GPU_LAYERS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_MAX_NEW_TOKENS | justsearch.llm.max_new_tokens | LLM_MAX_NEW_TOKENS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_MAX_PARALLEL | justsearch.llm.max_parallel | LLM_MAX_PARALLEL | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_MAX_SESSIONS | justsearch.llm.max_sessions | LLM_MAX_SESSIONS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_MAX_SLOTS | justsearch.llm.max_slots | LLM_MAX_SLOTS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_MIN_P | justsearch.llm.min_p | LLM_MIN_P | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| justsearch.llm.mode | JUSTSEARCH_LLM_MODE | justsearch.llm.mode | LLM_MODE | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| justsearch.llm.model_path | JUSTSEARCH_LLM_MODEL_PATH | justsearch.llm.model_path | LLM_MODEL_PATH | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| - | JUSTSEARCH_LLM_MODEL_SHA256 | justsearch.llm.model_sha256 | LLM_MODEL_SHA256 | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_QUEUE_CAPACITY | justsearch.llm.queue_capacity | LLM_QUEUE_CAPACITY | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_REASONING_BUDGET | justsearch.llm.reasoning_budget | REASONING_BUDGET | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_REMOTE_AUTH_TOKEN | justsearch.llm.remote_auth_token | LLM_REMOTE_AUTH_TOKEN | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_REMOTE_ENDPOINT | justsearch.llm.remote_endpoint | LLM_REMOTE_ENDPOINT | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_REP_PENALTY | justsearch.llm.rep_penalty | LLM_REP_PENALTY | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_REP_WINDOW | justsearch.llm.rep_window | LLM_REP_WINDOW | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_RNG_SEED | justsearch.llm.rng_seed | LLM_RNG_SEED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_SESSION_WARMUP_MS | justsearch.llm.session_warmup_ms | LLM_SESSION_WARMUP_MS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_SIMULATED_LATENCY_MS | justsearch.llm.simulated_latency_ms | LLM_SIMULATED_LATENCY_MS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_SUMMARY_CHUNK_OVERLAP | justsearch.llm.summary_chunk_overlap | LLM_SUMMARY_CHUNK_OVERLAP | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_SUMMARY_CHUNK_TOKENS | justsearch.llm.summary_chunk_tokens | LLM_SUMMARY_CHUNK_TOKENS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_TEMPERATURE | justsearch.llm.temperature | LLM_TEMPERATURE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_TEMPLATE_REDUCE | justsearch.llm.template_reduce | LLM_TEMPLATE_REDUCE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_TEMPLATE_ROOT | justsearch.llm.template_root | LLM_TEMPLATE_ROOT | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_TEMPLATE_SUMMARY | justsearch.llm.template_summary | LLM_TEMPLATE_SUMMARY | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_TEMPLATE_TRANSLATE | justsearch.llm.template_translate | LLM_TEMPLATE_TRANSLATE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_THREADS | justsearch.llm.threads | LLM_THREADS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_TOP_P | justsearch.llm.top_p | LLM_TOP_P | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_USE_THINKING | justsearch.llm.use_thinking | USE_THINKING | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_VRAM_AUTO_SCALE | justsearch.llm.vram_auto_scale | LLM_VRAM_AUTO_SCALE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_VRAM_FRACTION | justsearch.llm.vram_fraction | LLM_VRAM_FRACTION | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_VRAM_LIMIT_BYTES | justsearch.llm.vram_limit_bytes | LLM_VRAM_LIMIT_BYTES | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_LLM_VRAM_PROJECTED | justsearch.llm.vram_projected | LLM_VRAM_PROJECTED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_MCP_HOST_CONFIG | justsearch.mcp.host.config | MCP_HOST_CONFIG | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_MMPROJ_MODEL | justsearch.mmproj.model | MMPROJ_MODEL | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_MODELS_DIR | justsearch.models.dir | MODELS_DIR | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_NER_CONFIDENCE_THRESHOLD | justsearch.ner.confidence_threshold | NER_CONFIDENCE_THRESHOLD | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_NER_ENABLED | justsearch.ner.enabled | NER_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_NER_GPU_DEVICE_ID | justsearch.ner.gpu_device_id | NER_GPU_DEVICE_ID | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_NER_GPU_ENABLED | justsearch.ner.gpu_enabled | NER_GPU_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_NER_GPU_MEM_MB | justsearch.ner.gpu_mem_mb | NER_GPU_MEM_MB | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_NER_MAX_SEQ_LEN | justsearch.ner.max_seq_len | NER_MAX_SEQ_LEN | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_NER_MODEL_PATH | justsearch.ner.model_path | NER_MODEL_PATH | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH | justsearch.onnxruntime.native_path | ORT_NATIVE_PATH | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_ONNXRUNTIME_VARIANT_ID | justsearch.onnxruntime.variantId | ONNXRUNTIME_VARIANT_ID | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_ORT_PROFILING_DIR | justsearch.ort.profiling_dir | ORT_PROFILING_DIR | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_ORT_VERBOSE | justsearch.ort.verbose | ORT_VERBOSE_LOGGING | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_PATH_RESOLUTION_RETENTION_DAYS | justsearch.path_resolution.retention_days | PATH_RESOLUTION_RETENTION_DAYS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_POWER_FORCE_ENERGY_STATE | justsearch.power.force_energy_state | POWER_FORCE_ENERGY_STATE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_PROD | justsearch.prod | PROD_MODE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_QU_ENABLED | justsearch.qu.enabled | QU_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RAG_TOP_K | justsearch.rag.top_k | RAG_TOP_K | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_REPO_ROOT | justsearch.repo.root | REPO_ROOT | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_CHUNKS_DEADLINE_MS | justsearch.rerank.chunks.deadline_ms | RERANK_CHUNKS_DEADLINE_MS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_CHUNKS_ENABLED | justsearch.rerank.chunks.enabled | RERANK_CHUNKS_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_CHUNKS_GPU_DEVICE_ID | justsearch.rerank.chunks.gpu.device_id | RERANK_CHUNKS_GPU_DEVICE_ID | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_CHUNKS_GPU_ENABLED | justsearch.rerank.chunks.gpu.enabled | RERANK_CHUNKS_GPU_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_CHUNKS_MAX_GPU_CANDIDATES | justsearch.rerank.chunks.max_gpu_candidates | RERANK_CHUNKS_MAX_GPU_CANDIDATES | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_CHUNKS_MAX_SEQ_LEN | justsearch.rerank.chunks.max_seq_len | RERANK_CHUNKS_MAX_SEQ_LEN | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_CHUNKS_MIN_HITS | justsearch.rerank.chunks.min_hits | RERANK_CHUNKS_MIN_HITS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_CHUNKS_MODEL_PATH | justsearch.rerank.chunks.model_path | RERANK_CHUNKS_MODEL_PATH | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_CHUNKS_ORDER | justsearch.rerank.chunks.order | RERANK_CHUNKS_ORDER | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_CHUNKS_TOP_K | justsearch.rerank.chunks.top_k | RERANK_CHUNKS_TOP_K | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_DEADLINE_MS | justsearch.rerank.deadline_ms | RERANK_DEADLINE_MS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_ENABLED | justsearch.rerank.enabled | RERANK_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_GPU_MEM_MB | justsearch.rerank.gpu_mem_mb | RERANK_GPU_MEM_MB | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_GPU_DEVICE_ID | justsearch.rerank.gpu.device_id | RERANK_GPU_DEVICE_ID | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_GPU_ENABLED | justsearch.rerank.gpu.enabled | RERANK_GPU_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_MAX_AVG_DOC_LENGTH_CHARS | justsearch.rerank.max_avg_doc_length_chars | RERANK_MAX_AVG_DOC_LENGTH_CHARS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_MAX_SEQ_LEN | justsearch.rerank.max_seq_len | RERANK_MAX_SEQ_LEN | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_MIN_HITS | justsearch.rerank.min_hits | RERANK_MIN_HITS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_MODEL_PATH | justsearch.rerank.model_path | RERANK_MODEL_PATH | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RERANK_TOP_K | justsearch.rerank.top_k | RERANK_TOP_K | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_RULE_TICK_MS | justsearch.rule.tick.ms | RULE_TICK_MS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SEARCH_DEFAULT_LANGUAGE_POLICY | justsearch.search.default_language_policy | SEARCH_LANGUAGE_POLICY | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SEARCH_ENTITY_BOOST | justsearch.search.entity_boost | SEARCH_ENTITY_BOOST | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SEARCH_PIPELINE | justsearch.search.pipeline | SEARCH_PIPELINE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SEARCH_PROFILE | justsearch.search.pipeline.profile | SEARCH_PROFILE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SEARCH_QUERY_CLASSIFICATION_ENABLED | justsearch.search.query_classification.enabled | SEARCH_QUERY_CLASSIFICATION_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SEARCH_TITLE_BOOST | justsearch.search.title_boost | SEARCH_TITLE_BOOST | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SERVER_EXE | justsearch.server.exe | SERVER_EXE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SERVER_EXE_SOURCE | justsearch.server.exe.source | SERVER_EXE_SOURCE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SERVER_PORT | justsearch.server.port | SERVER_PORT | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SPARSE_MODEL | justsearch.sparse_model | SPARSE_MODEL | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SPLADE_ACTIVATION | justsearch.splade.activation | SPLADE_ACTIVATION | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SPLADE_ENABLED | justsearch.splade.enabled | SPLADE_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SPLADE_EVIDENCE_PATH | justsearch.splade.evidence_path | SPLADE_EVIDENCE_PATH | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SPLADE_GPU_DEVICE_ID | justsearch.splade.gpu_device_id | SPLADE_GPU_DEVICE_ID | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SPLADE_GPU_ENABLED | justsearch.splade.gpu_enabled | SPLADE_GPU_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SPLADE_GPU_MEM_MB | justsearch.splade.gpu_mem_mb | SPLADE_GPU_MEM_MB | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SPLADE_MAX_SEQ_LEN | justsearch.splade.max_seq_len | SPLADE_MAX_SEQ_LEN | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SPLADE_MODEL_PATH | justsearch.splade.model_path | SPLADE_MODEL_PATH | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SPLADE_QUERY_MODE | justsearch.splade.query_mode | SPLADE_QUERY_MODE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SSOT_PATH | justsearch.ssot.path | SSOT_PATH | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SUMMARY_EXECUTION_QUEUE_CAPACITY | justsearch.summary.execution_queue_capacity | SUMMARY_EXECUTION_QUEUE_CAPACITY | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SUMMARY_EXECUTION_THREADS | justsearch.summary.execution_threads | SUMMARY_EXECUTION_THREADS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SUMMARY_MAX_CHARACTERS | justsearch.summary.max_characters | SUMMARY_MAX_CHARACTERS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SUMMARY_MAX_TOKENS | justsearch.summary.max_tokens | SUMMARY_MAX_TOKENS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SUMMARY_MESSAGE_KEY | justsearch.summary.message_key | SUMMARY_MESSAGE_KEY | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SUMMARY_PIPELINE | justsearch.summary.pipeline | SUMMARY_PIPELINE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_SUMMARY_QUEUE_FULL_MESSAGE_KEY | justsearch.summary.queue_full_message_key | SUMMARY_QUEUE_FULL_MESSAGE_KEY | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_TELEMETRY_FLUSH_MS | justsearch.telemetry.flushMs | TELEMETRY_FLUSH_MS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_TELEMETRY_METRICS_EXEMPLARS | justsearch.telemetry.metrics.exemplars | TELEMETRY_METRICS_EXEMPLARS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_TELEMETRY_METRICS_MAX_MB | justsearch.telemetry.metrics.max_mb | TELEMETRY_METRICS_MAX_MB | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_TELEMETRY_METRICS_RETENTION_DAYS | justsearch.telemetry.metrics.retention.days | TELEMETRY_METRICS_RETENTION_DAYS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_TESSDATA_PATH | justsearch.tessdata.path | TESSDATA_PATH | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_TESSERACT_PATH | justsearch.tesseract.path | TESSERACT_PATH | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_TRANSLATOR_PIPELINE_CLASSIFY | justsearch.translator.pipeline.classify | TRANSLATOR_PIPELINE_CLASSIFY | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_TRANSLATOR_PIPELINE_EMBED | justsearch.translator.pipeline.embed | TRANSLATOR_PIPELINE_EMBED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_TRANSLATOR_PIPELINE_INTENT | justsearch.translator.pipeline.intent | TRANSLATOR_PIPELINE_INTENT | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_TRANSLATOR_REPO_ROOT | justsearch.translator.repoRoot | TRANSLATOR_REPO_ROOT | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_UI_AUTOMATION | justsearch.ui.automation.enabled | UI_AUTOMATION_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_UI_AUTOMATION_FORCE_DIAGNOSTICS | justsearch.ui.automation.forceDiagnostics | UI_AUTOMATION_FORCE_DIAGNOSTICS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_UI_AUTOMATION_REQUIRE_TRANSLATOR | justsearch.ui.automation.requireTranslator | UI_AUTOMATION_REQUIRE_TRANSLATOR | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_UI_EXCLUDE_PATTERNS | justsearch.ui.exclude_patterns | UI_EXCLUDE_PATTERNS | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_UI_SETTINGS_MODE | justsearch.ui.settings.mode | UI_SETTINGS_MODE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_UI_SETTINGS_READONLY | justsearch.ui.settings.readOnly | UI_SETTINGS_READONLY | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_VDU_QUALITY_THRESHOLD | justsearch.vdu.quality_threshold | VDU_QUALITY_THRESHOLD | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_VLM_MODEL | justsearch.vlm.model | VLM_MODEL | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_VLM_PROFILE | justsearch.vlm.profile | VLM_PROFILE | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_VRAM_THRESHOLD_12GB | justsearch.vram.threshold.12gb | VRAM_THRESHOLD_12GB | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_VRAM_THRESHOLD_4GB | justsearch.vram.threshold.4gb | VRAM_THRESHOLD_4GB | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_VRAM_THRESHOLD_8GB | justsearch.vram.threshold.8gb | VRAM_THRESHOLD_8GB | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_WORKER_CONFIG_SNAPSHOT | justsearch.worker.config_snapshot | WORKER_CONFIG_SNAPSHOT | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| - | JUSTSEARCH_POLICY_GPU_ACCELERATION_ENABLED | policy.gpu_acceleration_enabled | POLICY_GPU_ACCELERATION_ENABLED | modules/configuration (ResolvedConfigBuilder) | sysprop > env > default |
| rag.chunk_vectors.enabled | JUSTSEARCH_RAG_CHUNK_VECTORS_ENABLED | rag.chunk_vectors.enabled | RAG_CHUNK_VECTORS_ENABLED | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| rag.context.include_surrounding | JUSTSEARCH_RAG_INCLUDE_SURROUNDING_CONTEXT | rag.context.include_surrounding | RAG_INCLUDE_SURROUNDING | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| rag.diversify.mode | JUSTSEARCH_RAG_DIVERSIFY_MODE | rag.diversify.mode | RAG_DIVERSIFY_MODE | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| rag.max_chunks_per_article | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| rag.mmr.lambda | JUSTSEARCH_RAG_MMR_LAMBDA | rag.mmr.lambda | RAG_MMR_LAMBDA | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| rag.mmr.max_candidates | JUSTSEARCH_RAG_MMR_MAX_CANDIDATES | rag.mmr.max_candidates | RAG_MMR_MAX_CANDIDATES | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| rag.retrieve.mode | JUSTSEARCH_RAG_RETRIEVE_MODE | rag.retrieve.mode | RAG_RETRIEVE_MODE | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| rag.retrieve.overretrieve_factor | JUSTSEARCH_RAG_OVERRETRIEVE_FACTOR | rag.retrieve.overretrieve_factor | RAG_OVERRETRIEVE_FACTOR | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| rag.retrieve.top_k | JUSTSEARCH_RAG_RETRIEVE_TOP_K | rag.retrieve.top_k | RAG_RETRIEVE_TOP_K | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| search.chunk_aware.enabled | JUSTSEARCH_SEARCH_CHUNK_AWARE_ENABLED | search.chunk_aware.enabled | SEARCH_CHUNK_AWARE_ENABLED | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| search.chunk_aware.enabled | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| search.corrections.df_threshold | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| search.corrections.enabled | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| search.corrections.index_fallback_enabled | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| search.corrections.max_edit_distance | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| search.corrections.zero_hit_retry_enabled | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| search.cursor.legacy_enabled | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| search.paging.pit_ttl_ms | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| search.paging.strategy | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| search.paging.tiebreak_field | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| translator.health.maxBackoffMs | JUSTSEARCH_TRANSLATOR_MAX_BACKOFF_MS | translator.health.maxBackoffMs | TRANSLATOR_MAX_BACKOFF_MS | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| translator.health.refreshIntervalMs | JUSTSEARCH_TRANSLATOR_REFRESH_INTERVAL_MS | translator.health.refreshIntervalMs | TRANSLATOR_REFRESH_INTERVAL_MS | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| translator.health.stalenessAlertSeconds | JUSTSEARCH_TRANSLATOR_STALENESS_ALERT_SECONDS | translator.health.stalenessAlertSeconds | TRANSLATOR_STALENESS_ALERT_SECONDS | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| worker.limits.max_batch_size | JUSTSEARCH_WORKER_MAX_BATCH_SIZE | worker.limits.max_batch_size | WORKER_MAX_BATCH_SIZE | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| worker.limits.max_content_length | JUSTSEARCH_WORKER_MAX_CONTENT_LENGTH | worker.limits.max_content_length | WORKER_MAX_CONTENT_LENGTH | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| worker.limits.max_file_size | JUSTSEARCH_WORKER_MAX_FILE_SIZE | worker.limits.max_file_size | WORKER_MAX_FILE_SIZE | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| worker.limits.max_queue_depth | JUSTSEARCH_WORKER_MAX_QUEUE_DEPTH | worker.limits.max_queue_depth | WORKER_MAX_QUEUE_DEPTH | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| workers.ai.enabled | JUSTSEARCH_AI_ENABLED | workers.ai.enabled | AI_ENABLED | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
| workers.indexer.backpressure_mode | - | - | - | modules/configuration (ResolvedConfigBuilder) | YAML > default |
| workers.indexer.enabled | JUSTSEARCH_INDEXER_ENABLED | workers.indexer.enabled | INDEXER_ENABLED | modules/configuration (ResolvedConfigBuilder) | YAML > sysprop > env > default |
