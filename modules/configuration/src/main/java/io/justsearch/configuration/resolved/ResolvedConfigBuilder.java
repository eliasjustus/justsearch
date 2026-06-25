/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builder for {@link ResolvedConfig} using ordinal-chain resolution.
 *
 * <p>Each config source has a fixed numeric ordinal. Higher ordinal wins. The builder collects
 * value contributions from all sources via {@link #put}, then resolves each key during {@link
 * #build()} by selecting the highest-ordinal source with a non-blank value.
 *
 * <p>Resolution priority (from SmallRye pattern):
 *
 * <table>
 * <tr><th>Ordinal</th><th>Source</th><th>Rationale</th></tr>
 * <tr><td>500</td><td>{@code -D} JVM argument</td><td>Operator override — always wins</td></tr>
 * <tr><td>450</td><td>Worker config snapshot</td><td>Head→Worker propagation</td></tr>
 * <tr><td>400</td><td>Environment variable</td><td>Scripting/CI override (12-factor)</td></tr>
 * <tr><td>350</td><td>CI profile overrides</td><td>CI-specific config file</td></tr>
 * <tr><td>300</td><td>{@code settings.json}</td><td>User preference (GUI-set)</td></tr>
 * <tr><td>200</td><td>YAML {@code application.yaml}</td><td>Application profile defaults</td></tr>
 * <tr><td>150</td><td>Auto-detected values</td><td>GPU capabilities, platform paths</td></tr>
 * <tr><td>100</td><td>Programmatic default</td><td>Hardcoded fallback</td></tr>
 * </table>
 *
 * <p>Every resolved value carries a {@link ConfigResolution} trace recording all sources considered
 * and the winner. This enables the {@code /api/effective-config} diagnostic endpoint.
 *
 * @see ResolvedConfig
 */
public final class ResolvedConfigBuilder {

  private static final Logger LOG = LoggerFactory.getLogger(ResolvedConfigBuilder.class);
  private static final ObjectMapper COMPOSITE_JSON = JsonMapper.builder().build();

  // ==================== Ordinal Constants ====================

  /** {@code -D} JVM argument — operator override, always wins. */
  public static final int ORDINAL_JVM_ARG = 500;

  /** Worker config snapshot — Head→Worker propagation. */
  public static final int ORDINAL_WORKER_SNAPSHOT = 450;

  /** Environment variable — scripting/CI override (12-factor). */
  public static final int ORDINAL_ENV_VAR = 400;

  /** CI profile overrides. */
  public static final int ORDINAL_CI_PROFILE = 350;

  /** {@code settings.json} — user preference set via GUI. */
  public static final int ORDINAL_SETTINGS_JSON = 300;

  /** YAML {@code application.yaml} — application profile defaults. */
  public static final int ORDINAL_YAML = 200;

  /** Auto-detected values (GPU capabilities, platform paths). */
  public static final int ORDINAL_AUTO_DETECT = 150;

  /** Programmatic default — hardcoded fallback. */
  public static final int ORDINAL_DEFAULT = 100;

  // ==================== Static Factories ====================

  /**
   * Builds a worker-side {@link ResolvedConfig} from the Head→Worker config snapshot sysprop.
   *
   * <p>Reads the {@code justsearch.worker.config_snapshot} system property. If set, loads the
   * snapshot at ordinal 450 and contributes EnvRegistry entries. Returns null if the sysprop is
   * not set or blank.
   *
   * <p>This method centralizes the sysprop read in {@code modules/configuration}, keeping
   * {@code modules/indexer-worker} free of direct {@code System.getProperty} calls.
   *
   * @return a resolved config from the worker snapshot, or null if no snapshot path is configured
   */
  public static ResolvedConfig loadWorkerSnapshotFromSysprop() {
    return loadWorkerSnapshotFromSysprop(Map.of());
  }

  /**
   * Builds a worker-side {@link ResolvedConfig} with optional auto-detected values.
   *
   * @param autoDetected auto-detected hardware values (ordinal 150), or empty map to skip
   * @return a resolved config from the worker snapshot, or null if no snapshot path is configured
   */
  public static ResolvedConfig loadWorkerSnapshotFromSysprop(Map<String, String> autoDetected) {
    String snapshotProp = System.getProperty("justsearch.worker.config_snapshot"); // SYS-PROP-LEGACY-COMPAT
    if (snapshotProp == null || snapshotProp.isBlank()) return null;
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    builder.contributeAutoDetected(autoDetected);  // 347: ordinal 150
    builder.contributeWorkerSnapshot(Path.of(snapshotProp));  // ordinal 450 (wins over 150)
    builder.contributeEnvRegistry();
    LOG.info("Worker config snapshot loaded from {}", snapshotProp);
    return builder.build();
  }

  /**
   * Loads the raw Head→Worker config snapshot as a flat key→value map.
   *
   * <p>Reads the {@code justsearch.worker.config_snapshot} system property. If set, loads the
   * JSON snapshot file and returns its contents. Returns an empty map if the sysprop is unset,
   * blank, or the file cannot be read.
   *
   * <p>This is used for startup validation (item 4 of tempdoc 331) to compare the raw snapshot
   * values against the Worker's fully resolved {@link ResolvedConfig}, detecting any divergence
   * caused by JVM arg overrides at ordinal 500.
   *
   * @return raw snapshot key-value map, or empty map if no snapshot is available
   */
  public static Map<String, String> loadRawWorkerSnapshotFromSysprop() {
    String snapshotProp = System.getProperty("justsearch.worker.config_snapshot"); // SYS-PROP-LEGACY-COMPAT
    if (snapshotProp == null || snapshotProp.isBlank()) return Map.of();
    return ResolvedConfig.loadWorkerSnapshot(Path.of(snapshotProp));
  }

  // ==================== Internal State ====================

  private record SourceEntry(String sourceName, String sourceDetail, String rawValue) {}

  /**
   * All contributed values, keyed by config key. For each key, a TreeMap ordered by descending
   * ordinal holds the source entries. The first entry with a non-blank value wins during
   * resolution.
   */
  private final Map<String, TreeMap<Integer, SourceEntry>> entries = new LinkedHashMap<>();

  /**
   * Keys resolved during {@link #build()} by typed helpers ({@code resolveString}, etc.).
   * Only populated when {@link #buildPhaseActive} is true (during {@code build*()}).
   * Exposed via {@link #resolvedKeys()} for architectural tests (tempdoc 347 D7).
   */
  private final java.util.Set<String> resolvedKeys = new java.util.LinkedHashSet<>();

  /** True during the build*() phase of {@link #build()}. Guards resolvedKeys population. */
  private boolean buildPhaseActive;

  // ==================== Source Contribution ====================

  /**
   * Contributes a value from a source at the given ordinal.
   *
   * <p>Multiple sources can contribute values for the same key at different ordinals. During {@link
   * #build()}, the highest-ordinal source with a non-blank value wins.
   *
   * @param key config key (e.g., "justsearch.data.dir")
   * @param ordinal source priority (use the ORDINAL_* constants)
   * @param sourceName human-readable source name (e.g., "env_var")
   * @param sourceDetail source detail for tracing (e.g., "JUSTSEARCH_DATA_DIR")
   * @param value the value from this source, or null if not available
   * @return this builder for chaining
   */
  public ResolvedConfigBuilder put(
      String key, int ordinal, String sourceName, String sourceDetail, String value) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(sourceName, "sourceName");
    entries
        .computeIfAbsent(key, k -> new TreeMap<>(Comparator.reverseOrder()))
        .put(ordinal, new SourceEntry(sourceName, sourceDetail, value));
    return this;
  }

  /**
   * Convenience: contributes a programmatic default (ordinal 100).
   *
   * @param key config key
   * @param value default value
   * @return this builder for chaining
   */
  public ResolvedConfigBuilder putDefault(String key, String value) {
    return put(key, ORDINAL_DEFAULT, "default", null, value);
  }

  /**
   * Convenience: contributes a value from settings.json (ordinal 300).
   *
   * <p>Used by HeadlessApp to contribute UI settings values at the correct ordinal. Only
   * contributes non-null, non-blank values.
   *
   * @param key config key
   * @param value value from settings.json (null or blank values are ignored)
   * @return this builder for chaining
   */
  public ResolvedConfigBuilder putSettings(String key, String value) {
    if (value == null || value.isBlank()) return this;
    return put(key, ORDINAL_SETTINGS_JSON, "settings.json", key, value);
  }

  /**
   * Contributes all {@link EnvRegistry} entries as sources.
   *
   * <p>For each entry, registers:
   *
   * <ul>
   *   <li>System property value at ordinal 500 (JVM arg)
   *   <li>Environment variable value at ordinal 400 (env var)
   *   <li>Default value at ordinal 100 (if the entry defines one)
   * </ul>
   *
   * <p>Uses the entry's {@link EnvRegistry#configKey()} as the ordinal chain key. For most entries
   * this equals {@code sysProp()}, but entries may define a separate YAML-style config key that
   * differs from the JVM system property name (e.g., {@code index.vector.hnsw.m} as the config
   * key, resolved from the corresponding system property).
   *
   * @return this builder for chaining
   */
  public ResolvedConfigBuilder contributeEnvRegistry() {
    for (EnvRegistry entry : EnvRegistry.values()) {
      String key = entry.configKey();
      put(key, ORDINAL_JVM_ARG, "jvm_arg", entry.sysProp(), System.getProperty(entry.sysProp()));
      put(key, ORDINAL_ENV_VAR, "env_var", entry.envVar(), System.getenv(entry.envVar()));
      if (entry.defaultValue() != null) {
        putDefault(key, entry.defaultValue());
      }
    }
    return this;
  }

  /**
   * Contributes values from a Head→Worker config snapshot at ordinal 450.
   *
   * <p>The snapshot is a JSON file written by {@link ResolvedConfig#toWorkerSnapshot(Path)} on the
   * Head process. The Worker loads it during startup to inherit the Head's resolved configuration
   * without relying on env var forwarding.
   *
   * @param snapshotPath path to the snapshot file (null or nonexistent files are ignored)
   * @return this builder for chaining
   */
  public ResolvedConfigBuilder contributeWorkerSnapshot(Path snapshotPath) {
    if (snapshotPath == null) return this;
    Map<String, String> snapshot = ResolvedConfig.loadWorkerSnapshot(snapshotPath);
    for (Map.Entry<String, String> entry : snapshot.entrySet()) {
      put(entry.getKey(), ORDINAL_WORKER_SNAPSHOT, "worker_snapshot", snapshotPath.toString(),
          entry.getValue());
    }
    return this;
  }

  /**
   * Contributes auto-detected hardware values at ordinal 150.
   *
   * <p>Auto-detected values have the lowest explicit-override priority. They are overridden by
   * YAML (200), settings.json (300), env vars (400), worker snapshots (450), and sysprops (500).
   * This is the intended slot for GPU auto-detection: when CUDA DLLs are found on the filesystem,
   * the caller probes and feeds the result here so the master GPU switch defaults to {@code true}
   * without requiring explicit env var configuration.
   *
   * @param detected map of config keys to auto-detected values (e.g., {@code justsearch.gpu.enabled → true})
   * @return this builder for chaining
   */
  public ResolvedConfigBuilder contributeAutoDetected(Map<String, String> detected) {
    if (detected == null) return this;
    for (Map.Entry<String, String> entry : detected.entrySet()) {
      put(entry.getKey(), ORDINAL_AUTO_DETECT, "auto_detected", "hardware_probe", entry.getValue());
    }
    return this;
  }

  /**
   * Contributes YAML configuration values at ordinal 200.
   *
   * <p>For mixed-source keys (those also overridable via env/sysprop but not in EnvRegistry), also
   * registers the env var at ordinal 400 and sysprop at ordinal 500.
   *
   * @param root the parsed YAML root JsonNode
   * @return this builder for chaining
   */
  public ResolvedConfigBuilder contributeYaml(JsonNode root) {
    if (root == null || root.isMissingNode()) return this;
    contributeYamlWatcher(root);
    contributeYamlOcr(root);
    contributeYamlIndex(root);
    contributeYamlPolicy(root);
    contributeYamlRag(root);
    contributeYamlWorker(root);
    contributeYamlHybridSearch(root);
    contributeYamlSearch(root);
    contributeYamlIndexComposite(root);
    contributeYamlWorkers(root);
    contributeYamlCollections(root);
    contributeYamlInfra(root);
    // app.data_dir → justsearch.data.dir (EnvRegistry key for paths resolution)
    putYaml(EnvRegistry.DATA_DIR.sysProp(), root, "app.data_dir");
    return this;
  }

  /**
   * Contributes the standard worker-relevant source set that every config-resolution entry point
   * needs: the {@link #contributeEnvRegistry() EnvRegistry} sources and the {@link #contributeYaml
   * YAML root} (loaded via {@link JustSearchConfigurationLoader#loadYamlRoot()}).
   *
   * <p>Centralizing this base composition is the structural realization of the tempdoc 331
   * "resolve once; divergence is impossible" contract: a boot site that calls this cannot silently
   * omit a base source (the defect tempdoc 628 surfaced — the standalone worker had omitted
   * {@code contributeYaml}, defaulting all YAML config and disabling recovery). Callers add only
   * their own extra sources on top — auto-detected hardware ({@link #contributeAutoDetected}) for
   * hardware-aware boots, and settings.json (Head-only, via {@code contributeUiSettings}).
   *
   * @return this builder for chaining
   */
  public ResolvedConfigBuilder contributeBaseSources() {
    contributeEnvRegistry();
    JustSearchConfigurationLoader.loadYamlRoot().ifPresent(this::contributeYaml);
    return this;
  }

  private void contributeYamlWatcher(JsonNode root) {
    putYaml("index.watcher.strategy", root, "index.watcher.strategy");
    putYamlInt("index.watcher.debounce_ms", root, "index.watcher.debounce_ms");
    putYamlBoolean("index.watcher.overflow.rescan_on_overflow", root,
        "index.watcher.overflow.rescan_on_overflow");
    putYamlInt("index.watcher.polling.interval_ms", root, "index.watcher.polling.interval_ms");
    putYamlInt("index.watcher.queue.max_entries", root, "index.watcher.queue.max_entries");
  }

  private void contributeYamlOcr(JsonNode root) {
    putYamlBoolean("index.ocr.enabled", root, "index.ocr.enabled");
    putYamlInt("index.ocr.trigger.min_image_pixels", root, "index.ocr.trigger.min_image_pixels");
    putYamlInt("index.ocr.limits.per_file_timeout_ms", root, "index.ocr.limits.per_file_timeout_ms");
    putYamlInt("index.ocr.limits.max_pages", root, "index.ocr.limits.max_pages");
    putYamlInt("index.ocr.limits.max_image_dimension", root,
        "index.ocr.limits.max_image_dimension");
    putYamlInt("index.ocr.limits.max_image_pixels", root, "index.ocr.limits.max_image_pixels");
    // OCR languages is a list — stored as comma-joined string
    JsonNode langs = root.path("index").path("ocr").path("languages");
    if (langs.isArray()) {
      List<String> langList = new ArrayList<>();
      for (JsonNode n : langs) {
        if (n.isTextual()) langList.add(n.asText());
      }
      if (!langList.isEmpty()) {
        put("index.ocr.languages", ORDINAL_YAML, "yaml", "index.ocr.languages",
            String.join(",", langList));
      }
    }
  }

  private void contributeYamlIndex(JsonNode root) {
    putYamlInt("index.writer.ram_buffer_mb", root, "index.writer.ram_buffer_mb");
    putYamlInt("index.writer.max_buffered_docs", root, "index.writer.max_buffered_docs");
    putYamlInt("index.queue.max_depth", root, "index.queue.max_depth");
    putYamlInt("index.commit.debounce_ms", root, "index.commit.debounce_ms");
    putYaml("index.commit.policy", root, "index.commit.policy");
    putYamlBoolean("index.commit.meta.enabled", root, "index.commit.meta.enabled");
    putYamlInt("index.nrt.target_max_stale_ms", root, "index.nrt.target_max_stale_ms");
    putYamlInt("index.nrt.max_stale_ms", root, "index.nrt.max_stale_ms");
    putYaml("index.soft_deletes.field", root, "index.soft_deletes.field");
    putYamlBoolean("index.soft_deletes.retention.enabled", root,
        "index.soft_deletes.retention.enabled");
    putYamlInt("index.soft_deletes.retention.days", root, "index.soft_deletes.retention.days");
    putYamlInt("index.soft_deletes.retention.max_versions", root,
        "index.soft_deletes.retention.max_versions");
    putYamlInt("index.vector.dimension", root, "index.vector.dimension");
    putYamlInt("index.vector.hnsw.m", root, "index.vector.hnsw.m");
    putYamlInt("index.vector.hnsw.ef_construction", root, "index.vector.hnsw.ef_construction");
    putYamlInt("index.vector.ef_search", root, "index.vector.ef_search");
    putYamlBoolean("index.vector.quantization.enabled", root,
        "index.vector.quantization.enabled");
    putYamlBoolean("index.auto_recovery", root, "index.auto_recovery");
    // 347: env/sysprop overrides for vector, schema, migration now handled by EnvRegistry entries
    // (contributeEnvRegistry registers them at ordinals 400/500 using configKey()).
    putYaml("index.schema_mismatch.policy", root, "index.schema_mismatch.policy");
    putYaml("index.integrity_check", root, "index.integrity_check");
    putYaml("index.recovery.policy", root, "index.recovery.policy");
    // Directory, merge-tiered, similarity text (YAML-only, used by adapters-lucene)
    putYaml("index.directory.type", root, "index.directory.type");
    putYamlInt("index.merge.tiered.segs_per_tier", root, "index.merge.tiered.segs_per_tier");
    putYamlInt("index.merge.tiered.max_merged_segment_mb", root,
        "index.merge.tiered.max_merged_segment_mb");
    putYaml("index.similarity.text.type", root, "index.similarity.text.type");
    putYamlDouble("index.similarity.text.k1", root, "index.similarity.text.k1");
    putYamlDouble("index.similarity.text.b", root, "index.similarity.text.b");
    putYaml("index.validation.mode", root, "index.validation.mode");
    putYaml(EnvRegistry.INDEX_DEFAULT_LANGUAGE.sysProp(), root, "index.default_language");
  }

  private void contributeYamlPolicy(JsonNode root) {
    putYamlBoolean("egress.block_all", root, "egress.block_all");
    putYamlBoolean("justsearch.llm.enabled", root, "llm.enabled");
    putYaml("justsearch.llm.model_path", root, "llm.model_path");
    putYaml("justsearch.llm.mode", root, "llm.mode");
    // 347: policy GPU acceleration now handled by EnvRegistry.POLICY_GPU_ACCELERATION_ENABLED
    // (with configKey "policy.gpu_acceleration_enabled").
  }

  private void contributeYamlRag(JsonNode root) {
    putYaml("rag.retrieve.mode", root, "rag.retrieve.mode");
    putYamlInt("rag.retrieve.top_k", root, "rag.retrieve.top_k");
    putYamlInt("rag.retrieve.overretrieve_factor", root, "rag.retrieve.overretrieve_factor");
    putYaml("rag.diversify.mode", root, "rag.diversify.mode");
    putYamlDouble("rag.mmr.lambda", root, "rag.mmr.lambda");
    putYamlInt("rag.mmr.max_candidates", root, "rag.mmr.max_candidates");
    putYamlBoolean("rag.context.include_surrounding", root, "rag.context.include_surrounding");
    putYamlBoolean("rag.chunk_vectors.enabled", root, "rag.chunk_vectors.enabled");
    // 347: RAG env/sysprop overrides now handled by EnvRegistry entries.
  }

  private void contributeYamlWorker(JsonNode root) {
    putYamlInt("worker.limits.max_batch_size", root, "worker.limits.max_batch_size");
    putYamlLong("worker.limits.max_queue_depth", root, "worker.limits.max_queue_depth");
    putYamlInt("worker.limits.max_content_length", root, "worker.limits.max_content_length");
    putYamlLong("worker.limits.max_file_size", root, "worker.limits.max_file_size");
    // 347: worker limit env/sysprop overrides now handled by EnvRegistry entries.
  }

  private void contributeYamlHybridSearch(JsonNode root) {
    putYamlInt("index.hybrid.rrf_k", root, "index.hybrid.rrf_k");
    putYamlInt("index.hybrid.vector_skip_min_chars", root, "index.hybrid.vector_skip_min_chars");
    putYamlInt("index.hybrid.candidate_limit_max", root, "index.hybrid.candidate_limit_max");
    putYamlInt("index.hybrid.text_candidate_multiplier", root,
        "index.hybrid.text_candidate_multiplier");
    putYamlInt("index.hybrid.vector_candidate_multiplier", root,
        "index.hybrid.vector_candidate_multiplier");
    putYamlDouble("index.hybrid.vector_rrf_weight", root, "index.hybrid.vector_rrf_weight");
    putYamlDouble("index.hybrid.bm25_score_boost_weight", root,
        "index.hybrid.bm25_score_boost_weight");
    putYamlDouble("index.hybrid.vector_low_signal_top_score_threshold", root,
        "index.hybrid.vector_low_signal_top_score_threshold");
    putYamlDouble("index.hybrid.bm25_low_signal_top_score_threshold", root,
        "index.hybrid.bm25_low_signal_top_score_threshold");
    putYamlInt("index.hybrid.bm25_low_signal_total_hits_threshold", root,
        "index.hybrid.bm25_low_signal_total_hits_threshold");
    putYamlInt("index.hybrid.vector_only_cap_low_signal", root,
        "index.hybrid.vector_only_cap_low_signal");
    putYamlDouble("index.hybrid.vector_rrf_weight_low_signal", root,
        "index.hybrid.vector_rrf_weight_low_signal");
    putYaml("index.hybrid.fusion_strategy", root, "index.hybrid.fusion_strategy");
    putYamlDouble("index.hybrid.cc_alpha", root, "index.hybrid.cc_alpha");
    putYamlBoolean("index.hybrid.cc_zero_exclude", root, "index.hybrid.cc_zero_exclude");
    putYamlDouble("index.hybrid.cc_weight_sparse", root, "index.hybrid.cc_weight_sparse");
    putYamlDouble("index.hybrid.cc_weight_dense", root, "index.hybrid.cc_weight_dense");
    putYamlDouble("index.hybrid.cc_weight_splade", root, "index.hybrid.cc_weight_splade");
    putYamlBoolean(
        "index.hybrid.adaptive_weights_enabled", root, "index.hybrid.adaptive_weights_enabled");
    putYaml("index.hybrid.branch_fusion_strategy", root, "index.hybrid.branch_fusion_strategy");
    putYamlBoolean(
        "index.hybrid.branch_cc_zero_exclude", root, "index.hybrid.branch_cc_zero_exclude");
    putYamlDouble("index.hybrid.branch_cc_weight_whole", root, "index.hybrid.branch_cc_weight_whole");
    putYamlDouble("index.hybrid.branch_cc_weight_chunk", root, "index.hybrid.branch_cc_weight_chunk");
    putYamlDouble(
        "index.hybrid.branch_chunk_min_weight_multiplier",
        root,
        "index.hybrid.branch_chunk_min_weight_multiplier");
    // 347: hybrid search env/sysprop overrides now handled by EnvRegistry entries.

    // Register programmatic defaults so they appear in startup logs and
    // /api/debug/effective-config (ordinal 100 = lowest priority, any explicit source wins)
    putDefault("index.hybrid.fusion_strategy", "cc");
    putDefault("index.hybrid.cc_weight_sparse", "0.60");
    putDefault("index.hybrid.cc_weight_dense", "0.20");
    putDefault("index.hybrid.cc_weight_splade", "0.20");
    putDefault("index.hybrid.adaptive_weights_enabled", "false");
    putDefault("index.hybrid.branch_fusion_strategy", "cc");
    putDefault("index.hybrid.branch_cc_weight_whole", "0.50");
    putDefault("index.hybrid.branch_cc_weight_chunk", "0.50");
    putDefault("index.hybrid.branch_chunk_min_weight_multiplier", "0.25");
  }

  private void contributeYamlSearch(JsonNode root) {
    JsonNode searchRoot = root.path("search");
    if (searchRoot.isMissingNode()) return;
    putYamlFromNode("search.cursor.legacy_enabled", searchRoot, "cursor.legacy_enabled");
    putYamlFromNodeLower("search.paging.strategy", searchRoot, "paging.strategy");
    putYamlLongClampedFromNode("search.paging.pit_ttl_ms", searchRoot, "paging.pit_ttl_ms", 1L);
    putYamlFromNode("search.paging.tiebreak_field", searchRoot, "paging.tiebreak_field");
    // Also check top-level tiebreak_field as fallback
    String tiebreak = readYamlText(searchRoot, "tiebreak_field");
    if (tiebreak != null && resolve("search.paging.tiebreak_field").value() == null) {
      put("search.paging.tiebreak_field", ORDINAL_YAML, "yaml", "search.tiebreak_field",
          tiebreak);
    }
    putYamlIntFromNode("search.hybrid.bm25_k", searchRoot, "hybrid.bm25_k");
    putYamlIntFromNode("search.hybrid.ann_k", searchRoot, "hybrid.ann_k");
    putYamlFromNode("search.hybrid.auto_embed", searchRoot, "hybrid.auto_embed");
    putYamlFromNode("search.rerank.enabled", searchRoot, "rerank.enabled");
    putYamlIntFromNode("search.rerank.k", searchRoot, "rerank.k");
    putYamlIntFromNode("search.rerank.reduced_k", searchRoot, "rerank.reduced_k");
    putYamlFromNode("search.facets.enabled", searchRoot, "facets.enabled");
    putYamlFromNode("search.corrections.enabled", searchRoot, "corrections.enabled");
    putYamlIntFromNode("search.corrections.df_threshold", searchRoot, "corrections.df_threshold");
    putYamlIntClampedFromNode("search.corrections.max_edit_distance", searchRoot,
        "corrections.max_edit_distance", 0, 2);
    putYamlFromNode("search.corrections.zero_hit_retry_enabled", searchRoot,
        "corrections.zero_hit_retry_enabled");
    putYamlFromNode("search.corrections.index_fallback_enabled", searchRoot,
        "corrections.index_fallback_enabled");
    putYamlFromNode("search.chunk_aware.enabled", searchRoot, "chunk_aware.enabled");
    putDefault("search.chunk_aware.enabled", "true");
    // Facet fields list
    JsonNode fieldsNode = searchRoot.path("facets").path("fields");
    if (fieldsNode.isArray()) {
      List<String> fields = new ArrayList<>();
      for (JsonNode n : fieldsNode) {
        if (n.isTextual() && !n.asText().isBlank()) fields.add(n.asText());
      }
      if (!fields.isEmpty()) {
        put("search.facets.fields", ORDINAL_YAML, "yaml", "search.facets.fields",
            String.join(",", fields));
      }
    }
    putYaml(EnvRegistry.SEARCH_LANGUAGE_POLICY.sysProp(), root, "search.default_language_policy");
  }

  private void contributeYamlIndexComposite(JsonNode root) {
    // index.sort — store as JSON string for round-trip through flat resolution map
    JsonNode sortNode = root.path("index").path("sort");
    if (sortNode.isArray() && !sortNode.isEmpty()) {
      put("index.sort", ORDINAL_YAML, "yaml", "index.sort", sortNode.toString());
    }
    // index.boosts — variable-key map, store as JSON string
    JsonNode boostsNode = root.path("index").path("boosts");
    if (boostsNode.isObject() && !boostsNode.isEmpty()) {
      put("index.boosts", ORDINAL_YAML, "yaml", "index.boosts", boostsNode.toString());
    }
  }

  private void contributeYamlWorkers(JsonNode root) {
    // workers.ai.* — enabled is YAML-only; host/port/deadline use EnvRegistry sysProp keys
    putYamlBoolean("workers.ai.enabled", root, "workers.ai.enabled");
    putYaml("justsearch.ai.host", root, "workers.ai.host");
    putYamlInt("justsearch.ai.port", root, "workers.ai.port");
    putYamlLong("justsearch.ai.deadlineMs", root, "workers.ai.deadlineMs");

    // workers.indexer.*
    putYamlBoolean("workers.indexer.enabled", root, "workers.indexer.enabled");
    putYaml("justsearch.indexer.host", root, "workers.indexer.host");
    putYamlInt("justsearch.indexer.port", root, "workers.indexer.port");
    putYamlLong("justsearch.indexer.deadlineMs", root, "workers.indexer.deadlineMs");
    putYamlInt("justsearch.indexer.queueSize", root, "workers.indexer.queueSize");
    putYamlInt("justsearch.indexer.maxInFlightBytes", root, "workers.indexer.maxInFlightBytes");
    putYaml("workers.indexer.backpressure_mode", root, "workers.indexer.backpressure_mode");

    // translator.health.*
    putYamlLong("translator.health.refreshIntervalMs", root, "translator.health.refreshIntervalMs");
    putYamlLong("translator.health.maxBackoffMs", root, "translator.health.maxBackoffMs");
    putYamlLong("translator.health.stalenessAlertSeconds", root,
        "translator.health.stalenessAlertSeconds");
  }

  private void contributeYamlCollections(JsonNode root) {
    JsonNode colsNode = root.path("index").path("collections");
    if (colsNode.isArray() && !colsNode.isEmpty()) {
      put("index.collections", ORDINAL_YAML, "yaml", "index.collections", colsNode.toString());
      // Contribute first collection's name to Search.collection at ordinal 200
      JsonNode firstName = colsNode.path(0).path("name");
      if (firstName.isTextual() && !firstName.asText().isBlank()) {
        put("justsearch.index.collection", ORDINAL_YAML, "yaml",
            "index.collections[0].name", firstName.asText());
      }
    }
  }

  private void contributeYamlInfra(JsonNode root) {
    putYamlLong("infra.health.poll_interval_ms", root, "infra.health.poll_interval_ms");
    putYamlLong("infra.health.thresholds.nrt_stale_ms", root,
        "infra.health.thresholds.nrt_stale_ms");
    putYamlLong("infra.health.thresholds.translator_handshake_stale_ms", root,
        "infra.health.thresholds.translator_handshake_stale_ms");
    putYamlInt("infra.health.thresholds.ann_cache_ready_percent", root,
        "infra.health.thresholds.ann_cache_ready_percent");
    putYaml(EnvRegistry.INFRA_HEALTH_HOST.sysProp(), root, "infra.health.grpc.host");
    putYaml(EnvRegistry.INFRA_HEALTH_PORT.sysProp(), root, "infra.health.grpc.port");
  }

  // ==================== YAML Read Helpers ====================

  /** Reads a text value from a dotted YAML path. */
  private String readYamlText(JsonNode root, String dottedPath) {
    JsonNode node = descendYaml(root, dottedPath);
    return node != null && node.isTextual() ? node.asText() : null;
  }

  /** Reads an int value from a dotted YAML path. */
  private Integer readYamlInt(JsonNode root, String dottedPath) {
    JsonNode node = descendYaml(root, dottedPath);
    return node != null && node.isNumber() ? node.intValue() : null;
  }

  /** Reads a long value from a dotted YAML path. */
  private Long readYamlLong(JsonNode root, String dottedPath) {
    JsonNode node = descendYaml(root, dottedPath);
    return node != null && node.isNumber() ? node.longValue() : null;
  }

  /** Reads a double value from a dotted YAML path. */
  private Double readYamlDouble(JsonNode root, String dottedPath) {
    JsonNode node = descendYaml(root, dottedPath);
    return node != null && node.isNumber() ? node.doubleValue() : null;
  }

  /** Reads a boolean value from a dotted YAML path. */
  private Boolean readYamlBoolean(JsonNode root, String dottedPath) {
    JsonNode node = descendYaml(root, dottedPath);
    return node != null && node.isBoolean() ? node.booleanValue() : null;
  }

  /** Descends into a JsonNode by dotted path segments. */
  private JsonNode descendYaml(JsonNode root, String dottedPath) {
    JsonNode current = root;
    for (String segment : dottedPath.split("\\.")) {
      current = current.path(segment);
      if (current.isMissingNode()) return null;
    }
    return current.isMissingNode() || current.isNull() ? null : current;
  }

  /** Puts a YAML text value at ordinal 200. */
  private void putYaml(String key, JsonNode root, String yamlPath) {
    String value = readYamlText(root, yamlPath);
    if (value != null) put(key, ORDINAL_YAML, "yaml", yamlPath, value);
  }

  private void putYamlInt(String key, JsonNode root, String yamlPath) {
    Integer value = readYamlInt(root, yamlPath);
    if (value != null) put(key, ORDINAL_YAML, "yaml", yamlPath, value.toString());
  }

  private void putYamlLong(String key, JsonNode root, String yamlPath) {
    Long value = readYamlLong(root, yamlPath);
    if (value != null) put(key, ORDINAL_YAML, "yaml", yamlPath, value.toString());
  }

  private void putYamlDouble(String key, JsonNode root, String yamlPath) {
    Double value = readYamlDouble(root, yamlPath);
    if (value != null) put(key, ORDINAL_YAML, "yaml", yamlPath, value.toString());
  }

  private void putYamlBoolean(String key, JsonNode root, String yamlPath) {
    Boolean value = readYamlBoolean(root, yamlPath);
    if (value != null) put(key, ORDINAL_YAML, "yaml", yamlPath, value.toString());
  }

  /** Puts a YAML text value using a pre-descended node (for search config). */
  private void putYamlFromNode(String key, JsonNode node, String yamlPath) {
    String value = readYamlText(node, yamlPath);
    if (value != null) put(key, ORDINAL_YAML, "yaml", yamlPath, value);
  }

  /** Like {@link #putYamlFromNode} but normalizes the value to lowercase (for enum-like keys). */
  private void putYamlFromNodeLower(String key, JsonNode node, String yamlPath) {
    String value = readYamlText(node, yamlPath);
    if (value != null) put(key, ORDINAL_YAML, "yaml", yamlPath, value.toLowerCase(Locale.ROOT));
  }

  private void putYamlIntFromNode(String key, JsonNode node, String yamlPath) {
    Integer value = readYamlInt(node, yamlPath);
    if (value != null) put(key, ORDINAL_YAML, "yaml", yamlPath, value.toString());
  }

  private void putYamlIntClampedFromNode(
      String key, JsonNode node, String yamlPath, int min, int max) {
    Integer value = readYamlInt(node, yamlPath);
    if (value != null) {
      int clamped = Math.max(min, Math.min(max, value));
      put(key, ORDINAL_YAML, "yaml", yamlPath, Integer.toString(clamped));
    }
  }

  private void putYamlLongClampedFromNode(
      String key, JsonNode node, String yamlPath, long min) {
    Long value = readYamlLong(node, yamlPath);
    if (value != null) {
      long clamped = Math.max(min, value);
      put(key, ORDINAL_YAML, "yaml", yamlPath, Long.toString(clamped));
    }
  }

  // ==================== Resolution ====================

  /**
   * Resolves a single key by iterating sources in descending ordinal order.
   *
   * <p>The first source with a non-blank value wins. All sources are recorded in the {@link
   * ConfigResolution#considered()} list for tracing.
   *
   * @param key the config key to resolve
   * @return resolution result (never null; value may be null if no source provided one)
   */
  ConfigResolution resolve(String key) {
    if (buildPhaseActive) {
      resolvedKeys.add(key);
    }
    TreeMap<Integer, SourceEntry> sources = entries.get(key);
    if (sources == null || sources.isEmpty()) {
      return new ConfigResolution(key, null, "none", 0, null, List.of());
    }

    List<SourceCandidate> considered = new ArrayList<>();
    String winnerValue = null;
    String winnerSource = "none";
    int winnerOrdinal = 0;
    String winnerDetail = null;

    for (Map.Entry<Integer, SourceEntry> e : sources.entrySet()) {
      int ordinal = e.getKey();
      SourceEntry se = e.getValue();
      considered.add(new SourceCandidate(se.sourceName(), ordinal, se.rawValue()));
      if (winnerValue == null && se.rawValue() != null && !se.rawValue().isBlank()) {
        winnerValue = se.rawValue();
        winnerSource = se.sourceName();
        winnerOrdinal = ordinal;
        winnerDetail = se.sourceDetail();
      }
    }

    return new ConfigResolution(
        key, winnerValue, winnerSource, winnerOrdinal, winnerDetail, considered);
  }

  // ==================== Typed Resolution Helpers ====================

  /** Resolves a key as a string, returning the default if unresolved. */
  public String resolveString(String key, String defaultValue) {
    ConfigResolution r = resolve(key);
    return r.value() != null ? r.value().trim() : defaultValue;
  }

  /** Resolves a key as a lower-case string (for enum-like values), returning the default if unresolved. */
  private String resolveStringLower(String key, String defaultValue) {
    String v = resolveString(key, defaultValue);
    return v != null ? v.toLowerCase(Locale.ROOT) : null;
  }

  /** Resolves a key as an integer, returning the default if unresolved or unparseable. */
  public int resolveInt(String key, int defaultValue) {
    String v = resolveString(key, null);
    if (v == null) return defaultValue;
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      LOG.debug("Invalid integer for '{}': '{}', using default {}", key, v, defaultValue);
      return defaultValue;
    }
  }

  /** Resolves a key as a long, returning the default if unresolved or unparseable. */
  public long resolveLong(String key, long defaultValue) {
    String v = resolveString(key, null);
    if (v == null) return defaultValue;
    try {
      return Long.parseLong(v.trim());
    } catch (NumberFormatException e) {
      LOG.debug("Invalid long for '{}': '{}', using default {}", key, v, defaultValue);
      return defaultValue;
    }
  }

  /** Resolves a key as a double, returning the default if unresolved, unparseable, or non-finite. */
  public double resolveDouble(String key, double defaultValue) {
    String v = resolveString(key, null);
    if (v == null) return defaultValue;
    try {
      double d = Double.parseDouble(v.trim());
      if (!Double.isFinite(d)) {
        LOG.debug("Non-finite double for '{}': '{}', using default {}", key, v, defaultValue);
        return defaultValue;
      }
      return d;
    } catch (NumberFormatException e) {
      LOG.debug("Invalid double for '{}': '{}', using default {}", key, v, defaultValue);
      return defaultValue;
    }
  }

  /**
   * Resolves a key as a boolean, returning the default if unresolved.
   *
   * <p>Recognized true values: "true", "1", "yes", "on" (case-insensitive).
   */
  public boolean resolveBoolean(String key, boolean defaultValue) {
    String v = resolveString(key, null);
    if (v == null) return defaultValue;
    String norm = v.trim().toLowerCase(Locale.ROOT);
    return "true".equals(norm) || "1".equals(norm) || "yes".equals(norm) || "on".equals(norm);
  }

  /** Resolves a key as a {@link Path}, returning the default if unresolved. */
  public Path resolvePath(String key, Path defaultValue) {
    String v = resolveString(key, null);
    return v != null ? Path.of(v) : defaultValue;
  }

  /**
   * Returns all keys that {@link #resolve(String)} was called with during {@link #build()}.
   *
   * <p>This includes keys resolved by the initial {@code entries.keySet()} sweep AND keys resolved
   * by {@code build*()} methods' typed helpers. Used by architectural tests (tempdoc 347 gap 2)
   * to verify the reverse direction: every resolved key has an {@code EnvRegistry} entry.
   *
   * @return unmodifiable set of resolved keys (empty before {@code build()} is called)
   */
  public java.util.Set<String> resolvedKeys() {
    return java.util.Collections.unmodifiableSet(resolvedKeys);
  }

  // ==================== Build ====================

  /**
   * Resolves all contributed keys and constructs the immutable {@link ResolvedConfig}.
   *
   * <p>Derived values (e.g., {@code indexBasePath} from {@code dataDir}) are computed from
   * already-resolved values. All resolutions are logged at INFO (resolved) or DEBUG (unset).
   *
   * @return the immutable config snapshot
   */
  public ResolvedConfig build() {
    buildPhaseActive = true;

    // Resolve all contributed keys
    Map<String, ConfigResolution> allResolutions = new LinkedHashMap<>();
    for (String key : entries.keySet()) {
      allResolutions.put(key, resolve(key));
    }

    // Build sub-records from resolved values
    ResolvedConfig.Paths paths = buildPaths();
    ResolvedConfig.Ports ports = buildPorts();
    ResolvedConfig.Ai ai = buildAi();
    ResolvedConfig.Llm llm = buildLlm();
    ResolvedConfig.Agent agent = buildAgent();
    ResolvedConfig.Summary summary = buildSummary();
    ResolvedConfig.Translator translator = buildTranslator();
    ResolvedConfig.Search search = buildSearch();
    ResolvedConfig.Telemetry telemetry = buildTelemetry();
    ResolvedConfig.Policy policy = buildPolicy();
    ResolvedConfig.Ui ui = buildUi();
    ResolvedConfig.Watcher watcher = buildWatcher();
    ResolvedConfig.Ocr ocr = buildOcr();
    ResolvedConfig.Index index = buildIndex();
    ResolvedConfig.Rag rag = buildRag();
    ResolvedConfig.HybridSearch hybridSearch = buildHybridSearch();
    ResolvedConfig.Worker worker = buildWorker();
    ResolvedConfig.Collections collections = buildCollections();
    ResolvedConfig.WorkerAi workerAi = buildWorkerAi();
    ResolvedConfig.WorkerIndexer workerIndexer = buildWorkerIndexer();
    ResolvedConfig.InfraHealth infraHealth = buildInfraHealth();
    ResolvedConfig.InfraGrpc infraGrpc = buildInfraGrpc();

    ResolvedConfig config =
        new ResolvedConfig(
            paths, ports, ai, llm, agent, summary, translator,
            search, telemetry, policy, ui,
            watcher, ocr, index, rag, hybridSearch, worker,
            collections, workerAi, workerIndexer,
            infraHealth, infraGrpc,
            allResolutions);

    buildPhaseActive = false;

    // Log key resolutions
    logResolutions(allResolutions);

    return config;
  }

  // ==================== Sub-record Builders ====================

  private ResolvedConfig.Paths buildPaths() {
    Path dataDir = resolvePath("justsearch.data.dir", null);
    Path indexBasePath = resolvePath("justsearch.index.base_path", null);
    // Derive indexBasePath from dataDir + primary collection name if not explicitly set
    if (indexBasePath == null && dataDir != null) {
      String collection = resolveString("justsearch.index.collection", "default");
      indexBasePath = dataDir.resolve("index").resolve(collection);
    }
    return new ResolvedConfig.Paths(
        dataDir,
        indexBasePath,
        resolvePath("justsearch.home", null),
        resolvePath("justsearch.models.dir", null),
        resolvePath("justsearch.ssot.path", null),
        resolvePath("justsearch.repo.root", null),
        resolvePath("justsearch.onnxruntime.native_path", null));
  }

  private ResolvedConfig.Ports buildPorts() {
    return new ResolvedConfig.Ports(
        clampPort(resolveInt("justsearch.api.port", 8080)),
        clampPort(resolveInt("justsearch.server.port", 0)));
  }

  /** Clamp port to valid range [0, 65535]. Port 0 means ephemeral/auto-assign. */
  private static int clampPort(int port) {
    return Math.max(0, Math.min(65535, port));
  }

  /**
   * Normalizes a schema mismatch policy string using the same alias mappings as {@link
   * io.justsearch.configuration.runtime.RuntimeConfig.SchemaMismatchPolicy#from}. Returns the
   * canonical enum name as a string, or the production-aware default if null/blank.
   */
  private static String normalizeSchemaMismatchPolicy(String raw, boolean isProd) {
    if (raw == null || raw.isBlank()) {
      return isProd ? "FAIL_CLOSED" : "REBUILD_BACKUP_FIRST";
    }
    return switch (raw.trim().toLowerCase(Locale.ROOT)) {
      case "fail_closed", "fail-closed", "fail" -> "FAIL_CLOSED";
      case "rebuild_backup_first", "rebuild-backup-first", "rebuild" -> "REBUILD_BACKUP_FIRST";
      case "blue_green_migrate", "blue-green-migrate", "blue_green", "blue-green" ->
          "BLUE_GREEN_MIGRATE";
      default -> raw.trim();
    };
  }

  /**
   * Normalizes the open-time integrity-check tier (tempdoc 628 G1). Default is {@code STRUCTURAL} — a
   * bounded verification of the small commit/segment-info file checksums, cheap enough to run on every
   * open. {@code FULL} additionally checksums every segment data file (catches silent body bit-rot at
   * O(index size) cost); {@code OFF} disables verification (status becomes UNVERIFIED, never silently
   * "healthy").
   */
  private static String normalizeIntegrityCheck(String raw) {
    if (raw == null || raw.isBlank()) {
      return "STRUCTURAL";
    }
    return switch (raw.trim().toLowerCase(Locale.ROOT)) {
      case "off", "none", "false", "disabled" -> "OFF";
      case "structural", "footer", "segments" -> "STRUCTURAL";
      case "full", "all", "checksum" -> "FULL";
      default -> "STRUCTURAL";
    };
  }

  /**
   * Normalizes the orchestration-layer corruption-recovery policy (tempdoc 628 Stage B / G2). Default
   * is {@code BACKUP_REBUILD} — on corruption, back up the damaged index (never delete), serve degraded,
   * and rebuild from the source files still on disk. {@code BACKUP_ONLY} recovers to empty without an
   * auto-rebuild; {@code FAIL_CLOSED} never auto-recovers (the conservative, manual-only posture).
   */
  private static String normalizeRecoveryPolicy(String raw) {
    if (raw == null || raw.isBlank()) {
      return "BACKUP_REBUILD";
    }
    return switch (raw.trim().toLowerCase(Locale.ROOT)) {
      case "fail_closed", "fail-closed", "fail" -> "FAIL_CLOSED";
      case "backup_only", "backup-only", "backup" -> "BACKUP_ONLY";
      case "backup_rebuild", "backup-rebuild", "rebuild", "rebuild_from_source" -> "BACKUP_REBUILD";
      default -> "BACKUP_REBUILD";
    };
  }

  private ResolvedConfig.Ai buildAi() {
    return new ResolvedConfig.Ai(
        resolvePath("justsearch.server.exe", null),
        resolveInt("justsearch.gpu.layers", 0),
        resolvePath("justsearch.llm.model_path", null),
        resolveBoolean("justsearch.ai.disabled", false),
        resolveBoolean("justsearch.llm.enabled", true),
        resolveString("justsearch.llm.mode", "remote"),
        resolveString("justsearch.llm.backend", ""),
        resolveInt("justsearch.context.size", 8192),
        resolveString("justsearch.vlm.model", ""),
        resolveString("justsearch.mmproj.model", ""),
        resolveBoolean("justsearch.ai.classify.enabled", true),
        resolveBoolean("justsearch.llm.use_thinking", true),
        resolveInt("justsearch.llm.reasoning_budget", 0),
        resolveString("justsearch.onnxruntime.variantId", ""),
        resolveString("justsearch.server.exe.source", ""),
        resolveLong("justsearch.vram.threshold.12gb", 0L),
        resolveLong("justsearch.vram.threshold.8gb", 0L),
        resolveLong("justsearch.vram.threshold.4gb", 0L),
        resolveDouble("justsearch.gpl.reeval_size_factor", 2.0),
        buildEmbedding(),
        buildSplade(),
        buildNer(),
        buildReranker(),
        buildCitationScorer(),
        buildBgeM3(),
        buildProfiling(),
        resolveString("justsearch.sparse_model", "splade"),
        resolveBoolean("justsearch.dev.hotreload", false));
  }

  /**
   * Builds {@link ResolvedConfig.Ai.Profiling} from the diagnostic env/sysprop surface. Added
   * in tempdoc 397 §14.24 FB; replaces the {@code System.getenv} reads previously embedded in
   * {@link io.justsearch.ort.SessionOptionsApplier}.
   */
  private ResolvedConfig.Ai.Profiling buildProfiling() {
    return new ResolvedConfig.Ai.Profiling(
        resolvePath("justsearch.ort.profiling_dir", null),
        resolveBoolean("justsearch.ort.verbose", false));
  }

  private ResolvedConfig.Ai.BgeM3 buildBgeM3() {
    return new ResolvedConfig.Ai.BgeM3(
        resolveNullableBoolean("justsearch.bgem3.enabled"),
        resolvePath("justsearch.bgem3.model_path", null),
        resolveInt("justsearch.bgem3.max_seq_len", 8192),
        resolveModelGpuEnabled("justsearch.bgem3.gpu_enabled"),
        resolveInt("justsearch.bgem3.gpu_device_id", 0),
        resolveInt("justsearch.bgem3.gpu_mem_mb", 3072));
  }

  private ResolvedConfig.Ai.Embedding buildEmbedding() {
    return new ResolvedConfig.Ai.Embedding(
        resolveNullableBoolean("justsearch.ai.embed.enabled"),
        resolveString("justsearch.embed.backend", "auto"),
        resolveEmbedGpuEnabled(),
        resolveInt("justsearch.embed.gpu.device_id", 0),
        // 391/E-J-N8: raised from 2048 → 3072 to accommodate
        // gte-multilingual-base (628 MB FP16, post-358) activations —
        // 2048 MB fragments under the larger MLP intermediate tensors
        // (10 BFCArena failures observed in 391's 2026-04-19 re-measurement).
        // Must match OnnxEmbeddingEncoder.DEFAULT_GPU_MEM_MB.
        resolveInt("justsearch.embed.gpu_mem_mb", 3072),
        resolveInt("justsearch.embed.context_length", 2048));
  }

  /**
   * Resolves embedding GPU enabled state.
   * Priority: per-model key > master switch > false.
   * AND'd with the enterprise policy gate (admin veto).
   */
  private boolean resolveEmbedGpuEnabled() {
    boolean enabled;
    String gpuEnabledStr = resolveString("justsearch.embed.gpu.enabled", null);
    if (gpuEnabledStr != null) {
      String norm = gpuEnabledStr.trim().toLowerCase(Locale.ROOT);
      enabled = "true".equals(norm) || "1".equals(norm) || "yes".equals(norm);
    } else {
      enabled = resolveMasterGpuEnabled();
    }
    return enabled && resolvePolicyGpuAllowed();
  }

  /**
   * Resolves GPU enabled for a model using three-priority: per-model key > master switch > false.
   * AND'd with the enterprise policy gate (admin veto).
   */
  private boolean resolveModelGpuEnabled(String perModelKey) {
    boolean enabled;
    String perModel = resolveString(perModelKey, null);
    if (perModel != null) {
      enabled = resolveBoolean(perModelKey, false);
    } else {
      enabled = resolveMasterGpuEnabled();
    }
    return enabled && resolvePolicyGpuAllowed();
  }

  /** Resolves the master GPU switch ({@code justsearch.gpu.enabled}). */
  private boolean resolveMasterGpuEnabled() {
    return resolveBoolean("justsearch.gpu.enabled", false);
  }

  /**
   * Resolves the enterprise GPU policy gate. Default true (permissive).
   * Only {@code "false"} disables GPU acceleration. This is an admin veto
   * that overrides all per-model and master switches.
   */
  private boolean resolvePolicyGpuAllowed() {
    return resolveBoolean("policy.gpu_acceleration_enabled", true);
  }

  private ResolvedConfig.Ai.Splade buildSplade() {
    return new ResolvedConfig.Ai.Splade(
        resolveNullableBoolean("justsearch.splade.enabled"),
        resolveModelGpuEnabled("justsearch.splade.gpu_enabled"),
        resolveInt("justsearch.splade.gpu_device_id", 0),
        resolveInt("justsearch.splade.gpu_mem_mb", 4096),
        resolvePath("justsearch.splade.model_path", null),
        resolveInt("justsearch.splade.max_seq_len", 512),
        resolveString("justsearch.splade.query_mode", "onnx"),
        resolveString("justsearch.splade.activation", "log1p"),
        resolvePath("justsearch.splade.evidence_path", null));
  }

  private ResolvedConfig.Ai.Ner buildNer() {
    return new ResolvedConfig.Ai.Ner(
        resolveNullableBoolean("justsearch.ner.enabled"),
        resolvePath("justsearch.ner.model_path", null),
        resolveInt("justsearch.ner.max_seq_len", 512),
        resolveDouble("justsearch.ner.confidence_threshold", 0.7),
        resolveModelGpuEnabled("justsearch.ner.gpu_enabled"),
        resolveInt("justsearch.ner.gpu_device_id", 0),
        resolveInt("justsearch.ner.gpu_mem_mb", 512));
  }

  // Fallback values below are dead code — EnvRegistry declares defaults for all reranker keys,
  // which contributeEnvRegistry() registers at ordinal 100. These fallbacks exist only as a safety
  // net if an EnvRegistry default is accidentally removed. The source of truth is EnvRegistry.
  private ResolvedConfig.Ai.Reranker buildReranker() {
    return new ResolvedConfig.Ai.Reranker(
        resolveNullableBoolean("justsearch.rerank.enabled"),
        resolvePath("justsearch.rerank.model_path", null),
        resolveBoolean("justsearch.rerank.gpu.enabled", true),
        resolveInt("justsearch.rerank.gpu.device_id", 0),
        resolveInt("justsearch.rerank.gpu_mem_mb", 2048),
        resolveInt("justsearch.rerank.top_k", 20),
        resolveInt("justsearch.rerank.deadline_ms", 200),
        resolveInt("justsearch.rerank.min_hits", 5),
        resolveInt("justsearch.rerank.max_seq_len", 512),
        resolveInt("justsearch.rerank.max_avg_doc_length_chars", 16000),
        buildChunkReranker());
  }

  private ResolvedConfig.Ai.Reranker.ChunkReranker buildChunkReranker() {
    return new ResolvedConfig.Ai.Reranker.ChunkReranker(
        resolveNullableBoolean("justsearch.rerank.chunks.enabled"),
        resolvePath("justsearch.rerank.chunks.model_path", null),
        resolveBoolean("justsearch.rerank.chunks.gpu.enabled", false),
        resolveInt("justsearch.rerank.chunks.gpu.device_id", 0),
        resolveInt("justsearch.rerank.chunks.top_k", 10),
        resolveInt("justsearch.rerank.chunks.max_gpu_candidates", 50),
        resolveInt("justsearch.rerank.chunks.deadline_ms", 150),
        resolveInt("justsearch.rerank.chunks.min_hits", 3),
        resolveInt("justsearch.rerank.chunks.max_seq_len", 512),
        resolveString("justsearch.rerank.chunks.order", "auto"));
  }

  private ResolvedConfig.Ai.CitationScorer buildCitationScorer() {
    return new ResolvedConfig.Ai.CitationScorer(
        resolveNullableBoolean("justsearch.citation.scorer.enabled"),
        resolvePath("justsearch.citation.scorer.model_path", null),
        resolveDouble("justsearch.citation.scorer.threshold", 0.5),
        resolveInt("justsearch.citation.scorer.max_seq_len", 512),
        resolveInt("justsearch.citation.scorer.deadline_ms", 2000));
  }

  private ResolvedConfig.Llm buildLlm() {
    return new ResolvedConfig.Llm(
        resolveString("justsearch.llm.model_sha256", ""),
        resolveInt("justsearch.llm.gpu_layers", 0),
        resolveLong("justsearch.llm.deadline_ms", 0L),
        resolveInt("justsearch.llm.max_parallel", 1),
        resolveInt("justsearch.llm.max_sessions", 0),
        resolveLong("justsearch.llm.session_warmup_ms", 0L),
        resolveInt("justsearch.llm.queue_capacity", 0),
        resolveDouble("justsearch.llm.vram_fraction", 0.0),
        resolveLong("justsearch.llm.vram_projected", 0L),
        resolveInt("justsearch.llm.max_slots", 0),
        resolveLong("justsearch.llm.vram_limit_bytes", 0L),
        resolveBoolean("justsearch.llm.vram_auto_scale", true),
        resolveLong("justsearch.llm.simulated_latency_ms", 0L),
        resolveInt("justsearch.llm.threads", 0),
        resolveInt("justsearch.llm.context_length", 0),
        resolveInt("justsearch.llm.max_new_tokens", 0),
        resolveDouble("justsearch.llm.temperature", 0.0),
        resolveDouble("justsearch.llm.top_p", 0.0),
        resolveDouble("justsearch.llm.min_p", 0.0),
        resolveDouble("justsearch.llm.rep_penalty", 0.0),
        resolveInt("justsearch.llm.rep_window", 0),
        resolveBoolean("justsearch.llm.enable_json_guard", false),
        resolveLong("justsearch.llm.rng_seed", 42L),
        resolveString("justsearch.llm.backend_selector", ""),
        resolveBoolean("justsearch.llm.allow_remote", false),
        resolveString("justsearch.llm.remote_endpoint", ""),
        resolveString("justsearch.llm.remote_auth_token", ""),
        resolveString("justsearch.llm.backend_supports", ""),
        resolveInt("justsearch.llm.summary_chunk_tokens", 0),
        resolveInt("justsearch.llm.summary_chunk_overlap", 0),
        resolveString("justsearch.llm.template_root", ""),
        resolveString("justsearch.llm.template_translate", ""),
        resolveString("justsearch.llm.template_summary", ""),
        resolveString("justsearch.llm.template_reduce", ""));
  }

  private ResolvedConfig.Agent buildAgent() {
    return new ResolvedConfig.Agent(
        resolveInt("justsearch.agent.search.default_limit", 3),
        resolveString("justsearch.agent.search.default_mode", ""),
        resolveInt("justsearch.agent.browse.default_max_folders", 20),
        resolveInt("justsearch.agent.max_tool_result_chars", 4000),
        resolveInt("justsearch.agent.max_completion_tokens", 1024),
        resolveBoolean("justsearch.agent.context_compression.enabled", true),
        resolveInt("justsearch.agent.context_compression.min_chars", 200),
        resolveInt("justsearch.agent.context_compression.keep_last_results", 1));
  }

  private ResolvedConfig.Summary buildSummary() {
    return new ResolvedConfig.Summary(
        resolveString("justsearch.summary.pipeline", ""),
        resolveInt("justsearch.summary.max_characters", 0),
        resolveInt("justsearch.summary.max_tokens", 0),
        resolveString("justsearch.summary.message_key", ""),
        resolveString("justsearch.summary.queue_full_message_key", ""),
        resolveInt("justsearch.summary.execution_threads", 0),
        resolveInt("justsearch.summary.execution_queue_capacity", 0));
  }

  private ResolvedConfig.Translator buildTranslator() {
    return new ResolvedConfig.Translator(
        resolveString("justsearch.translator.pipeline.intent", ""),
        resolveString("justsearch.translator.pipeline.embed", ""),
        resolveString("justsearch.translator.pipeline.classify", ""),
        resolveString("justsearch.translator.repoRoot", ""),
        buildTranslatorHealth());
  }

  private ResolvedConfig.Search buildSearch() {
    return new ResolvedConfig.Search(
        resolveString("justsearch.search.pipeline.profile", null),
        resolveString("justsearch.search.pipeline", null),
        resolveString("justsearch.index.collection", "default"),
        resolveBoolean("justsearch.search.query_classification.enabled", true),
        resolveDouble("justsearch.search.title_boost", 3.0),
        resolveDouble("justsearch.search.entity_boost", 0.0),
        resolveBoolean("search.chunk_aware.enabled", true),
        resolveBoolean("justsearch.lambdamart.enabled", false),
        buildSearchCorrections(),
        buildSearchPaging());
  }

  private ResolvedConfig.Search.Corrections buildSearchCorrections() {
    return new ResolvedConfig.Search.Corrections(
        resolveBoolean("search.corrections.enabled", false),
        resolveInt("search.corrections.df_threshold", 1),
        resolveInt("search.corrections.max_edit_distance", 1),
        resolveBoolean("search.corrections.zero_hit_retry_enabled", false),
        resolveBoolean("search.corrections.index_fallback_enabled", false));
  }

  private ResolvedConfig.Search.Paging buildSearchPaging() {
    return new ResolvedConfig.Search.Paging(
        resolveBoolean("search.cursor.legacy_enabled", false),
        resolveString("search.paging.strategy", "search_after"),
        resolveLong("search.paging.pit_ttl_ms", 60_000L),
        resolveString("search.paging.tiebreak_field", null));
  }

  private ResolvedConfig.Telemetry buildTelemetry() {
    return new ResolvedConfig.Telemetry(
        resolveLong("justsearch.telemetry.flushMs", 60_000),
        resolveInt("justsearch.telemetry.metrics.max_mb", 50),
        resolveInt("justsearch.telemetry.metrics.retention.days", 30),
        resolveBoolean("justsearch.telemetry.metrics.exemplars", false));
  }

  private ResolvedConfig.Policy buildPolicy() {
    return new ResolvedConfig.Policy(
        resolveBoolean("egress.block_all", false),
        resolveBoolean("justsearch.prod", false),
        resolveBoolean("justsearch.index.parity.allow_mismatch", false),
        resolveString(EnvRegistry.SEARCH_LANGUAGE_POLICY.sysProp(), "explicit_or_default"));
  }

  private ResolvedConfig.Ui buildUi() {
    return new ResolvedConfig.Ui(
        resolveString("justsearch.ui.settings.mode", null),
        resolveBoolean("justsearch.ui.automation.enabled", false),
        resolveBoolean("justsearch.ui.automation.requireTranslator", false),
        resolveBoolean("justsearch.ui.automation.forceDiagnostics", true));
  }

  private ResolvedConfig.Watcher buildWatcher() {
    return new ResolvedConfig.Watcher(
        resolveString("index.watcher.strategy", null),
        resolveNullableInt("index.watcher.debounce_ms"),
        resolveNullableBoolean("index.watcher.overflow.rescan_on_overflow"),
        resolveNullableInt("index.watcher.polling.interval_ms"),
        resolveNullableInt("index.watcher.queue.max_entries"));
  }

  private ResolvedConfig.Ocr buildOcr() {
    String langStr = resolveString("index.ocr.languages", null);
    List<String> languages =
        langStr != null ? List.of(langStr.split(",")) : List.of();
    return new ResolvedConfig.Ocr(
        resolveNullableBoolean("index.ocr.enabled"),
        languages,
        resolveNullableInt("index.ocr.trigger.min_image_pixels"),
        resolveNullableInt("index.ocr.limits.per_file_timeout_ms"),
        resolveNullableInt("index.ocr.limits.max_pages"),
        resolveNullableInt("index.ocr.limits.max_image_dimension"),
        resolveNullableInt("index.ocr.limits.max_image_pixels"));
  }

  private ResolvedConfig.Index buildIndex() {
    return new ResolvedConfig.Index(
        resolveNullableInt("index.writer.ram_buffer_mb"),
        resolveNullableInt("index.writer.max_buffered_docs"),
        resolveNullableInt("index.queue.max_depth"),
        resolveNullableInt("index.commit.debounce_ms"),
        resolveString("index.commit.policy", null),
        resolveBoolean("index.commit.meta.enabled", true),
        resolveNullableInt("index.nrt.target_max_stale_ms"),
        resolveNullableInt("index.nrt.max_stale_ms"),
        resolveString("index.soft_deletes.field", null),
        resolveNullableBoolean("index.soft_deletes.retention.enabled"),
        resolveNullableInt("index.soft_deletes.retention.days"),
        resolveNullableInt("index.soft_deletes.retention.max_versions"),
        resolveNullableInt("index.vector.dimension"),
        resolveNullableInt("index.vector.hnsw.m"),
        resolveNullableInt("index.vector.hnsw.ef_construction"),
        resolveNullableInt("index.vector.ef_search"),
        resolveNullableBoolean("index.vector.quantization.enabled"),
        resolveBoolean("index.auto_recovery", false),
        normalizeSchemaMismatchPolicy(
            resolveString("index.schema_mismatch.policy", null),
            resolveBoolean("justsearch.prod", false)),
        normalizeIntegrityCheck(resolveString("index.integrity_check", null)),
        normalizeRecoveryPolicy(resolveString("index.recovery.policy", null)),
        Math.max(-1, resolveInt("index.migration.cutover.max_failed_jobs", -1)),
        resolveString("index.directory.type", null),
        resolveNullableInt("index.merge.tiered.segs_per_tier"),
        resolveNullableInt("index.merge.tiered.max_merged_segment_mb"),
        resolveString("index.similarity.text.type", null),
        resolveNullableDouble("index.similarity.text.k1"),
        resolveNullableDouble("index.similarity.text.b"),
        resolveString("index.validation.mode", null),
        resolveString(EnvRegistry.INDEX_DEFAULT_LANGUAGE.sysProp(), "en-US"),
        resolveString(EnvRegistry.INDEX_TRACING_LEVEL.sysProp(), "none"),
        parseIndexSort(resolveString("index.sort", null)),
        parseBoosts(resolveString("index.boosts", null)));
  }

  private ResolvedConfig.Collections buildCollections() {
    String json = resolveString("index.collections", null);
    if (json == null || json.isBlank()) return new ResolvedConfig.Collections(List.of());
    try {
      JsonNode arr = COMPOSITE_JSON.readTree(json);
      if (!arr.isArray()) return new ResolvedConfig.Collections(List.of());
      List<ResolvedConfig.CollectionCfg> out = new ArrayList<>();
      for (int i = 0; i < arr.size(); i++) {
        JsonNode node = arr.get(i);
        String name = node.path("name").asText(null);
        if (name == null || name.isBlank()) continue;
        List<Path> roots = new ArrayList<>();
        JsonNode rootsNode = node.path("roots");
        if (rootsNode.isArray()) {
          for (int j = 0; j < rootsNode.size(); j++) {
            JsonNode rn = rootsNode.get(j);
            if (rn.isTextual() && !rn.asText().isBlank()) {
              roots.add(Path.of(rn.asText()));
            }
          }
        }
        String strategy = null;
        JsonNode watcherNode = node.path("watcher");
        if (watcherNode.isObject()) {
          String s = watcherNode.path("strategy").asText(null);
          if (s != null && !s.isBlank()) strategy = s;
        }
        out.add(new ResolvedConfig.CollectionCfg(name, roots, strategy));
      }
      return new ResolvedConfig.Collections(List.copyOf(out));
    } catch (Exception e) {
      LOG.warn("Failed to parse index.collections JSON: {}", e.getMessage());
      return new ResolvedConfig.Collections(List.of());
    }
  }

  private ResolvedConfig.WorkerAi buildWorkerAi() {
    return new ResolvedConfig.WorkerAi(
        resolveBoolean("workers.ai.enabled", false),
        resolveString("justsearch.ai.host", "127.0.0.1"),
        resolveInt("justsearch.ai.port", 50061),
        resolveLong("justsearch.ai.deadlineMs", 1_500L));
  }

  private ResolvedConfig.WorkerIndexer buildWorkerIndexer() {
    return new ResolvedConfig.WorkerIndexer(
        resolveBoolean("workers.indexer.enabled", false),
        resolveString("justsearch.indexer.host", "127.0.0.1"),
        resolveInt("justsearch.indexer.port", 50071),
        resolveLong("justsearch.indexer.deadlineMs", 5_000L),
        Math.max(1, resolveInt("justsearch.indexer.queueSize", 64)),
        Math.max(1, resolveInt("justsearch.indexer.maxInFlightBytes", 512 * 1024 * 1024)),
        resolveString("workers.indexer.backpressure_mode", null));
  }

  private ResolvedConfig.InfraHealth buildInfraHealth() {
    return new ResolvedConfig.InfraHealth(
        resolveLong("infra.health.poll_interval_ms", 5_000L),
        resolveLong("infra.health.thresholds.nrt_stale_ms", 30_000L),
        resolveLong("infra.health.thresholds.translator_handshake_stale_ms", 120_000L),
        Math.max(0, Math.min(100, resolveInt(
            "infra.health.thresholds.ann_cache_ready_percent", 75))));
  }

  private ResolvedConfig.InfraGrpc buildInfraGrpc() {
    return new ResolvedConfig.InfraGrpc(
        resolveString(EnvRegistry.INFRA_HEALTH_HOST.sysProp(), "127.0.0.1"),
        Math.max(0, Math.min(65535, resolveInt(
            EnvRegistry.INFRA_HEALTH_PORT.sysProp(), 7443))));
  }

  private ResolvedConfig.Translator.Health buildTranslatorHealth() {
    return new ResolvedConfig.Translator.Health(
        Math.max(1, resolveLong("translator.health.refreshIntervalMs", 300_000L)),
        Math.max(1, resolveLong("translator.health.maxBackoffMs", 30_000L)),
        Math.max(1, resolveLong("translator.health.stalenessAlertSeconds", 1_800L)));
  }

  private static List<ResolvedConfig.Index.IndexSortItem> parseIndexSort(String json) {
    if (json == null || json.isBlank()) return List.of();
    try {
      JsonNode arr = COMPOSITE_JSON.readTree(json);
      if (!arr.isArray()) return List.of();
      List<ResolvedConfig.Index.IndexSortItem> out = new ArrayList<>();
      for (int i = 0; i < arr.size(); i++) {
        JsonNode n = arr.get(i);
        String field = n.path("field").asText(null);
        if (field == null || field.isBlank()) continue;
        Boolean reverse = n.has("reverse") ? n.path("reverse").asBoolean(false) : null;
        String type = n.has("type") ? n.path("type").asText(null) : null;
        out.add(new ResolvedConfig.Index.IndexSortItem(field, reverse, type));
      }
      return List.copyOf(out);
    } catch (Exception e) {
      LOG.warn("Failed to parse index.sort JSON: {}", e.getMessage());
      return List.of();
    }
  }

  private static Map<String, Double> parseBoosts(String json) {
    if (json == null || json.isBlank()) return Map.of();
    try {
      JsonNode obj = COMPOSITE_JSON.readTree(json);
      if (!obj.isObject()) return Map.of();
      TreeMap<String, Double> out = new TreeMap<>();
      for (var entry : obj.properties()) {
        if (entry.getValue().isNumber()) {
          out.put(entry.getKey(), entry.getValue().asDouble());
        } else if (entry.getValue().isTextual()) {
          try {
            out.put(entry.getKey(), Double.parseDouble(entry.getValue().asText()));
          } catch (NumberFormatException ignored) {
            LOG.debug("Skipping invalid boost '{}': not a number", entry.getKey());
          }
        }
      }
      return java.util.Collections.unmodifiableMap(out);
    } catch (Exception e) {
      LOG.warn("Failed to parse index.boosts JSON: {}", e.getMessage());
      return Map.of();
    }
  }

  private ResolvedConfig.Rag buildRag() {
    return new ResolvedConfig.Rag(
        resolveStringLower("rag.retrieve.mode", "auto"),
        Math.max(1, Math.min(50, resolveInt("rag.retrieve.top_k", 5))),
        Math.max(1, Math.min(10, resolveInt("rag.retrieve.overretrieve_factor", 3))),
        resolveStringLower("rag.diversify.mode", "position"),
        Math.max(0.0, Math.min(1.0, resolveDouble("rag.mmr.lambda", 0.5))),
        Math.max(1, Math.min(200, resolveInt("rag.mmr.max_candidates", 20))),
        resolveBoolean("rag.context.include_surrounding", false),
        resolveBoolean("rag.chunk_vectors.enabled", true),
        resolveInt("justsearch.rag.top_k", 5),
        resolveString("justsearch.citation.match_threshold", ""),
        Math.max(1, Math.min(10, resolveInt("rag.max_chunks_per_article", 2))));
  }

  private ResolvedConfig.HybridSearch buildHybridSearch() {
    return new ResolvedConfig.HybridSearch(
        resolveInt("index.hybrid.rrf_k", 60),
        resolveInt("index.hybrid.vector_skip_min_chars", 4),
        Math.max(1, resolveInt("index.hybrid.candidate_limit_max", 100)),
        Math.max(1, resolveInt("index.hybrid.text_candidate_multiplier", 10)),
        Math.max(1, resolveInt("index.hybrid.vector_candidate_multiplier", 10)),
        Math.max(0.0, Math.min(1.0, resolveDouble("index.hybrid.vector_rrf_weight", 0.75))),
        Math.max(0.0, resolveDouble("index.hybrid.bm25_score_boost_weight", 0.002)),
        Math.max(0.0, Math.min(1.0, resolveDouble("index.hybrid.vector_low_signal_top_score_threshold", 0.40))),
        Math.max(0.0, resolveDouble("index.hybrid.bm25_low_signal_top_score_threshold", 0.0)),
        Math.max(0, resolveInt("index.hybrid.bm25_low_signal_total_hits_threshold", 0)),
        Math.max(0, resolveInt("index.hybrid.vector_only_cap_low_signal", 3)),
        Math.max(0.0, Math.min(1.0, resolveDouble("index.hybrid.vector_rrf_weight_low_signal", 0.25))),
        resolveStringLower("index.hybrid.fusion_strategy", "cc"),
        Math.max(0.0, Math.min(1.0, resolveDouble("index.hybrid.cc_alpha", 0.5))),
        resolveBoolean("index.hybrid.cc_zero_exclude", false),
        Math.max(0.0, Math.min(1.0, resolveDouble("index.hybrid.cc_weight_sparse", 0.60))),
        Math.max(0.0, Math.min(1.0, resolveDouble("index.hybrid.cc_weight_dense", 0.20))),
        Math.max(0.0, Math.min(1.0, resolveDouble("index.hybrid.cc_weight_splade", 0.20))),
        resolveStringLower("index.hybrid.branch_fusion_strategy", "cc"),
        resolveBoolean("index.hybrid.branch_cc_zero_exclude", true),
        Math.max(0.0, Math.min(1.0, resolveDouble("index.hybrid.branch_cc_weight_whole", 0.50))),
        Math.max(0.0, Math.min(1.0, resolveDouble("index.hybrid.branch_cc_weight_chunk", 0.50))),
        Math.max(
            0.0,
            Math.min(
                1.0,
                resolveDouble("index.hybrid.branch_chunk_min_weight_multiplier", 0.25))),
        // Tempdoc 580 §13.3 — per-query adaptive CC-weight selection (default off; static weights win).
        resolveBoolean("index.hybrid.adaptive_weights_enabled", false),
        // Tempdoc 636 Design v2 — per-query leg arbitration on the 2-way CC alpha. DEFAULT ON
        // (user decision 2026-06-24): graded +125% nDCG@10 on the buried-fact target; a −1.4%
        // regression on real email (mixed/enron-qa) is the accepted tradeoff for that gain. Set the
        // env/sysprop to false to disable. Fires only on its conditional trigger (dense bounded-
        // confident + legs diverge + BM25 incoherent), so it is already query-adaptive.
        resolveBoolean("index.hybrid.leg_arbitration_enabled", true),
        Math.max(
            0.0,
            Math.min(
                1.0, resolveDouble("index.hybrid.leg_arbitration_alpha_diverge", 0.7))),
        // Tempdoc 636 review fix — BM25-incoherence gate (top2/top1 ≥ this to fire); default 0.9
        // (calibrated: needle win preserved, courtlistener regression cut from −23% to ~−2%).
        Math.max(
            0.0,
            Math.min(
                1.0, resolveDouble("index.hybrid.leg_arbitration_bm25_incoherence_min", 0.9))),
        // Tempdoc 636 Design v3 — recall-complete rerank pool (DEFAULT ON, graded 2026-06-24):
        // guarantee each leg's top-N candidates reach the cross-encoder's rerank window. Keyword-
        // neutral (never down-weights a leg), unlike Design v2. Graded A/B: +98% nDCG@10 on the
        // needle-burial-v1 buried-fact target, −0.04% (neutral) on mixed/enron-qa real email →
        // earns default-on. Set the env/sysprop to false to disable.
        resolveBoolean("index.hybrid.leg_recall_complete_enabled", true),
        Math.max(1, resolveInt("index.hybrid.leg_recall_complete_top_n", 10)));
  }

  private ResolvedConfig.Worker buildWorker() {
    return new ResolvedConfig.Worker(
        resolveInt("worker.limits.max_batch_size", 10_000),
        resolveLong("worker.limits.max_queue_depth", 100_000L),
        resolveInt("worker.limits.max_content_length", 10 * 1024 * 1024),
        resolveLong("worker.limits.max_file_size", 100L * 1024 * 1024));
  }

  // ==================== Nullable Typed Resolution Helpers ====================

  /** Resolves a key as a nullable Integer. Returns null if unresolved. */
  private Integer resolveNullableInt(String key) {
    String v = resolveString(key, null);
    if (v == null) return null;
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      LOG.debug("Invalid integer for '{}': '{}'", key, v);
      return null;
    }
  }

  /** Resolves a key as a nullable Double. Returns null if unresolved. */
  private Double resolveNullableDouble(String key) {
    String v = resolveString(key, null);
    if (v == null) return null;
    try {
      return Double.parseDouble(v.trim());
    } catch (NumberFormatException e) {
      LOG.debug("Invalid double for '{}': '{}'", key, v);
      return null;
    }
  }

  /** Resolves a key as a nullable Boolean. Returns null if unresolved. */
  private Boolean resolveNullableBoolean(String key) {
    String v = resolveString(key, null);
    if (v == null) return null;
    String norm = v.trim().toLowerCase(Locale.ROOT);
    return "true".equals(norm) || "1".equals(norm) || "yes".equals(norm);
  }

  private void logResolutions(Map<String, ConfigResolution> resolutions) {
    for (ConfigResolution r : resolutions.values()) {
      if (r.isResolved()) {
        LOG.info("Config: {}", r.toLogString());
      } else {
        LOG.debug("Config: {}", r.toLogString());
      }
    }
  }
}
