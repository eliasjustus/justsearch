/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

/**
 * Registry of internal YAML-only config keys.
 *
 * <p>These keys are resolved via {@code ResolvedConfigBuilder.build*()} methods from YAML
 * sources (ordinal 200) and programmatic defaults (ordinal 100). Unlike {@link EnvRegistry},
 * they are NOT registered at ordinal 400/500 by {@code contributeEnvRegistry()} — operators
 * cannot override them via environment variables or system properties.
 *
 * <p>Use {@link EnvRegistry} for operator-facing config that should be overridable via env
 * vars. Use this enum for internal tuning knobs that are only configurable via YAML.
 *
 * <p>Added by tempdoc 347 D1 to separate the two concerns that were previously merged in
 * {@code EnvRegistry}.
 */
public enum ConfigKey {

    // -- File watcher --
    INDEX_WATCHER_RESCAN_ON_OVERFLOW("index.watcher.overflow.rescan_on_overflow"),

    // -- OCR --
    INDEX_OCR_LANGUAGES("index.ocr.languages"),
    INDEX_OCR_ENABLED("index.ocr.enabled"),
    INDEX_OCR_PER_FILE_TIMEOUT_MS("index.ocr.limits.per_file_timeout_ms"),
    INDEX_OCR_MAX_PAGES("index.ocr.limits.max_pages"),
    INDEX_OCR_MAX_IMAGE_DIMENSION("index.ocr.limits.max_image_dimension"),
    INDEX_OCR_MAX_IMAGE_PIXELS("index.ocr.limits.max_image_pixels"),
    INDEX_OCR_RENDER_DPI("index.ocr.limits.render_dpi"),
    INDEX_OCR_WORKERS("index.ocr.workers"),

    // -- Index writer --
    INDEX_WRITER_RAM_BUFFER_MB("index.writer.ram_buffer_mb"),
    INDEX_WRITER_MAX_BUFFERED_DOCS("index.writer.max_buffered_docs"),
    INDEX_QUEUE_MAX_DEPTH("index.queue.max_depth"),
    INDEX_COMMIT_DEBOUNCE_MS("index.commit.debounce_ms"),
    INDEX_COMMIT_META_ENABLED("index.commit.meta.enabled"),
    INDEX_NRT_TARGET_MAX_STALE_MS("index.nrt.target_max_stale_ms"),
    INDEX_NRT_MAX_STALE_MS("index.nrt.max_stale_ms"),
    INDEX_SOFT_DELETES_FIELD("index.soft_deletes.field"),
    INDEX_SOFT_DELETES_RETENTION_ENABLED("index.soft_deletes.retention.enabled"),
    INDEX_SOFT_DELETES_RETENTION_DAYS("index.soft_deletes.retention.days"),
    INDEX_SOFT_DELETES_RETENTION_MAX_VERSIONS("index.soft_deletes.retention.max_versions"),
    INDEX_VECTOR_DIMENSION("index.vector.dimension"),
    INDEX_AUTO_RECOVERY("index.auto_recovery"),
    INDEX_INTEGRITY_CHECK("index.integrity_check"),
    INDEX_RECOVERY_POLICY("index.recovery.policy"),
    INDEX_DIRECTORY_TYPE("index.directory.type"),
    INDEX_MERGE_SEGS_PER_TIER("index.merge.tiered.segs_per_tier"),
    INDEX_MERGE_MAX_MERGED_SEGMENT_MB("index.merge.tiered.max_merged_segment_mb"),
    INDEX_SIMILARITY_TEXT_TYPE("index.similarity.text.type"),
    INDEX_SIMILARITY_TEXT_K1("index.similarity.text.k1"),
    INDEX_SIMILARITY_TEXT_B("index.similarity.text.b"),
    INDEX_VALIDATION_MODE("index.validation.mode"),
    INDEX_SORT("index.sort"),
    INDEX_BOOSTS("index.boosts"),

    // -- Search --
    SEARCH_CHUNK_AWARE_ENABLED("search.chunk_aware.enabled"),
    SEARCH_EVIDENCE_PREVIEW_ENABLED("search.evidence_preview.enabled"),
    SEARCH_CORRECTIONS_ENABLED("search.corrections.enabled"),
    SEARCH_CORRECTIONS_DF_THRESHOLD("search.corrections.df_threshold"),
    SEARCH_CORRECTIONS_MAX_EDIT_DISTANCE("search.corrections.max_edit_distance"),
    SEARCH_CORRECTIONS_ZERO_HIT_RETRY("search.corrections.zero_hit_retry_enabled"),
    SEARCH_EVIDENCE_SPAN_ENABLED("search.evidence_span.enabled"),
    SEARCH_EVIDENCE_SPAN_ENTITY_SIGNAL("search.evidence_span.entity_signal"),
    SEARCH_MCP_DELIVERY_BUDGET_BYTES("search.mcp_delivery.budget_bytes"),
    SEARCH_MCP_DELIVERY_ENTITY_CARRIAGE_ENABLED("search.mcp_delivery.entity_carriage_enabled"),
    SEARCH_MCP_DELIVERY_ENTITY_CARRIAGE_MAX_CHARS("search.mcp_delivery.entity_carriage_max_chars"),
    SEARCH_MCP_FRAMING_CONTINUATION("search.mcp_framing.continuation_enabled"),
    SEARCH_MCP_FRAMING_EVIDENCE_NOT_ANSWER("search.mcp_framing.evidence_not_answer_enabled"),
    SEARCH_MCP_FRAMING_CALIBRATED_ABSENCE("search.mcp_framing.calibrated_absence_enabled"),
    SEARCH_MCP_FRAMING_THIN_RESULT_FLOOR_BYTES("search.mcp_framing.thin_result_floor_bytes"),

    // -- Worker indexer --
    INDEXER_BACKPRESSURE_MODE("workers.indexer.backpressure_mode"),

    // -- Infra health --
    INFRA_HEALTH_POLL_INTERVAL_MS("infra.health.poll_interval_ms"),
    INFRA_HEALTH_NRT_STALE_MS("infra.health.thresholds.nrt_stale_ms"),
    INFRA_HEALTH_TRANSLATOR_STALE_MS("infra.health.thresholds.translator_handshake_stale_ms"),
    INFRA_HEALTH_ANN_CACHE_READY_PCT("infra.health.thresholds.ann_cache_ready_percent"),

    // -- Collections --
    INDEX_COLLECTIONS("index.collections"),

    // -- RAG --
    RAG_MAX_CHUNKS_PER_ARTICLE("rag.max_chunks_per_article");

    private final String configKey;

    ConfigKey(String configKey) {
        this.configKey = configKey;
    }

    /** Returns the config key used in the ordinal chain (same as the YAML path). */
    public String configKey() {
        return configKey;
    }
}
