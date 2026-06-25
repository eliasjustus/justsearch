package io.justsearch.configuration.resolved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;
import io.justsearch.configuration.EnvRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ResolvedConfigBuilder} ordinal-chain resolution and EnvRegistry wiring.
 *
 * <p>Verifies the core resolution mechanism: higher ordinal wins, source tracing captures all
 * candidates, typed helpers parse correctly, and EnvRegistry contribution registers all entries.
 */
@DisplayName("ResolvedConfigBuilder")
final class ResolvedConfigBuilderTest {

  // ==================== Ordinal Chain Resolution ====================

  @Nested
  @DisplayName("Ordinal chain resolution")
  class OrdinalChain {

    @Test
    @DisplayName("Higher ordinal wins over lower ordinal")
    void higherOrdinalWins() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.put("test.key", ResolvedConfigBuilder.ORDINAL_DEFAULT, "default", null, "low");
      builder.put(
          "test.key", ResolvedConfigBuilder.ORDINAL_ENV_VAR, "env_var", "TEST_KEY", "high");

      ConfigResolution r = builder.resolve("test.key");
      assertEquals("high", r.value());
      assertEquals("env_var", r.sourceName());
      assertEquals(400, r.sourceOrdinal());
      assertEquals("TEST_KEY", r.sourceDetail());
    }

    @Test
    @DisplayName("JVM arg (500) beats env var (400)")
    void jvmArgBeatsEnvVar() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.put("test.key", ResolvedConfigBuilder.ORDINAL_ENV_VAR, "env_var", "TEST", "env");
      builder.put("test.key", ResolvedConfigBuilder.ORDINAL_JVM_ARG, "jvm_arg", "test.key", "jvm");

      ConfigResolution r = builder.resolve("test.key");
      assertEquals("jvm", r.value());
      assertEquals("jvm_arg", r.sourceName());
      assertEquals(500, r.sourceOrdinal());
    }

    @Test
    @DisplayName("Blank value is treated as absent — lower ordinal wins")
    void blankValueSkipped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.put("test.key", ResolvedConfigBuilder.ORDINAL_JVM_ARG, "jvm_arg", "test.key", "  ");
      builder.put(
          "test.key", ResolvedConfigBuilder.ORDINAL_ENV_VAR, "env_var", "TEST", "actual_value");

      ConfigResolution r = builder.resolve("test.key");
      assertEquals("actual_value", r.value());
      assertEquals("env_var", r.sourceName());
      assertEquals(400, r.sourceOrdinal());
    }

    @Test
    @DisplayName("Null value is treated as absent")
    void nullValueSkipped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.put("test.key", ResolvedConfigBuilder.ORDINAL_JVM_ARG, "jvm_arg", "test.key", null);
      builder.put(
          "test.key", ResolvedConfigBuilder.ORDINAL_DEFAULT, "default", null, "fallback");

      ConfigResolution r = builder.resolve("test.key");
      assertEquals("fallback", r.value());
      assertEquals("default", r.sourceName());
      assertEquals(100, r.sourceOrdinal());
    }

    @Test
    @DisplayName("No sources returns null value with 'none' source")
    void noSourcesReturnsNull() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();

      ConfigResolution r = builder.resolve("nonexistent.key");
      assertNull(r.value());
      assertEquals("none", r.sourceName());
      assertEquals(0, r.sourceOrdinal());
      assertFalse(r.isResolved());
    }

    @Test
    @DisplayName("All candidates are recorded in considered list")
    void allCandidatesRecorded() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.put("test.key", ResolvedConfigBuilder.ORDINAL_DEFAULT, "default", null, "def");
      builder.put("test.key", ResolvedConfigBuilder.ORDINAL_YAML, "yaml", "app.yaml", "yaml_val");
      builder.put("test.key", ResolvedConfigBuilder.ORDINAL_ENV_VAR, "env_var", "TEST", null);

      ConfigResolution r = builder.resolve("test.key");
      // Winner is YAML (200) because env_var (400) is null
      assertEquals("yaml_val", r.value());
      assertEquals("yaml", r.sourceName());
      assertEquals(200, r.sourceOrdinal());

      // All 3 candidates recorded, ordered by descending ordinal
      assertEquals(3, r.considered().size());
      assertEquals(400, r.considered().get(0).ordinal());
      assertEquals(200, r.considered().get(1).ordinal());
      assertEquals(100, r.considered().get(2).ordinal());
    }
  }

  // ==================== Typed Resolution Helpers ====================

  @Nested
  @DisplayName("Typed resolution helpers")
  class TypedHelpers {

    @Test
    @DisplayName("resolveInt parses valid integer")
    void resolveIntValid() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("port", "8080");
      assertEquals(8080, builder.resolveInt("port", 0));
    }

    @Test
    @DisplayName("resolveInt returns default for unparseable value")
    void resolveIntInvalid() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("port", "not_a_number");
      assertEquals(9999, builder.resolveInt("port", 9999));
    }

    @Test
    @DisplayName("resolveBoolean recognizes true, 1, yes")
    void resolveBooleanTrueValues() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("a", "true");
      builder.putDefault("b", "1");
      builder.putDefault("c", "yes");
      builder.putDefault("d", "YES");
      assertTrue(builder.resolveBoolean("a", false));
      assertTrue(builder.resolveBoolean("b", false));
      assertTrue(builder.resolveBoolean("c", false));
      assertTrue(builder.resolveBoolean("d", false));
    }

    @Test
    @DisplayName("resolveBoolean returns default for unset key")
    void resolveBooleanDefault() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      assertTrue(builder.resolveBoolean("unset", true));
      assertFalse(builder.resolveBoolean("unset", false));
    }

    @Test
    @DisplayName("resolvePath creates Path from string")
    void resolvePathValid() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("dir", "/tmp/test");
      assertEquals(Path.of("/tmp/test"), builder.resolvePath("dir", null));
    }

    @Test
    @DisplayName("resolvePath returns default for unset key")
    void resolvePathDefault() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      Path def = Path.of("/default");
      assertEquals(def, builder.resolvePath("unset", def));
    }
  }

  // ==================== Build ====================

  @Nested
  @DisplayName("Build")
  class Build {

    @Test
    @DisplayName("build() produces ResolvedConfig with all sub-records")
    void buildProducesCompleteConfig() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.data.dir", "/tmp/data");
      builder.putDefault("justsearch.api.port", "9090");
      builder.putDefault("justsearch.llm.enabled", "true");
      builder.putDefault("justsearch.search.pipeline.profile", "bm25");
      builder.putDefault("justsearch.prod", "true");

      ResolvedConfig config = builder.build();

      assertNotNull(config.paths());
      assertNotNull(config.ports());
      assertNotNull(config.ai());
      assertNotNull(config.search());
      assertNotNull(config.telemetry());
      assertNotNull(config.policy());
      assertNotNull(config.ui());
      assertNotNull(config.watcher());
      assertNotNull(config.ocr());
      assertNotNull(config.index());
      assertNotNull(config.rag());
      assertNotNull(config.hybridSearch());
      assertNotNull(config.worker());
      assertNotNull(config.resolutions());

      assertEquals(Path.of("/tmp/data"), config.paths().dataDir());
      assertEquals(9090, config.ports().apiPort());
      assertTrue(config.ai().llmEnabled());
      assertEquals("bm25", config.search().profile());
      assertTrue(config.policy().prodMode());
      // 391/E-J-N8: embed GPU mem default raised from 2048 → 3072 to
      // accommodate gte-multilingual-base FP16 activations (post-358).
      assertEquals(3072, config.ai().embedding().gpuMemMb());
    }

    @Test
    @DisplayName("reranker defaults from EnvRegistry flow through resolution chain")
    void rerankerDefaultsFromEnvRegistry() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();
      ResolvedConfig config = builder.build();
      // These values come from EnvRegistry declared defaults at ordinal 100.
      // Cross-check: resolved value must equal the EnvRegistry declared default.
      // This catches drift in EITHER direction: changing EnvRegistry without updating this test,
      // or removing the EnvRegistry default (which would fall through to the builder fallback).
      assertEquals(
          Integer.parseInt(EnvRegistry.RERANK_MAX_SEQ_LEN.defaultValue()),
          config.ai().reranker().maxSeqLen());
      assertEquals(
          Integer.parseInt(EnvRegistry.RERANK_GPU_MEM_MB.defaultValue()),
          config.ai().reranker().gpuMemMb());
      assertEquals(
          Boolean.parseBoolean(EnvRegistry.RERANK_GPU_ENABLED.defaultValue()),
          config.ai().reranker().gpuEnabled());
      assertEquals(
          Integer.parseInt(EnvRegistry.RERANK_TOP_K.defaultValue()),
          config.ai().reranker().topK());
      assertEquals(
          Integer.parseInt(EnvRegistry.RERANK_DEADLINE_MS.defaultValue()),
          config.ai().reranker().deadlineMs());
      assertEquals(
          Integer.parseInt(EnvRegistry.RERANK_MIN_HITS.defaultValue()),
          config.ai().reranker().minHits());
      assertEquals(
          Integer.parseInt(EnvRegistry.RERANK_MAX_AVG_DOC_LENGTH_CHARS.defaultValue()),
          config.ai().reranker().maxAvgDocLengthChars());
    }

    @Test
    @DisplayName("embedGpuMemMb honors explicit override")
    void embedGpuMemMbExplicitOverride() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.embed.gpu_mem_mb", "2048");

      ResolvedConfig config = builder.build();

      assertEquals(2048, config.ai().embedding().gpuMemMb());
    }

    @Test
    @DisplayName("indexBasePath is derived from dataDir when not explicitly set")
    void indexBasePathDerived() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.data.dir", "/tmp/data");

      ResolvedConfig config = builder.build();

      assertEquals(
          Path.of("/tmp/data/index/default"),
          config.paths().indexBasePath());
    }

    @Test
    @DisplayName("explicit indexBasePath overrides derived value")
    void explicitIndexBasePath() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.data.dir", "/tmp/data");
      builder.put(
          "justsearch.index.base_path",
          ResolvedConfigBuilder.ORDINAL_JVM_ARG,
          "jvm_arg",
          "justsearch.index.base_path",
          "/custom/index");

      ResolvedConfig config = builder.build();

      assertEquals(Path.of("/custom/index"), config.paths().indexBasePath());
    }

    @Test
    @DisplayName("contributed JVM indexBasePath beats settings contribution")
    void explicitIndexBasePathBeatsSettings() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.data.dir", "/tmp/data");
      builder.putSettings("justsearch.index.base_path", "/settings/index");
      builder.put(
          "justsearch.index.base_path",
          ResolvedConfigBuilder.ORDINAL_JVM_ARG,
          "jvm_arg",
          "justsearch.index.base_path",
          "/cli/index");

      ResolvedConfig config = builder.build();

      assertEquals(Path.of("/cli/index"), config.paths().indexBasePath());
      assertEquals("jvm_arg", config.resolution("justsearch.index.base_path").sourceName());
    }

    @Test
    @DisplayName("resolutions map contains all contributed keys")
    void resolutionsMapComplete() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.data.dir", "/tmp");
      builder.putDefault("justsearch.api.port", "8080");

      ResolvedConfig config = builder.build();

      assertNotNull(config.resolution("justsearch.data.dir"));
      assertNotNull(config.resolution("justsearch.api.port"));
      assertEquals("/tmp", config.resolution("justsearch.data.dir").value());
    }
  }

  // ==================== EnvRegistry Integration ====================

  @Nested
  @DisplayName("EnvRegistry integration")
  class EnvRegistryIntegration {

    @Test
    @DisplayName("contributeEnvRegistry registers all EnvRegistry entries")
    void contributeEnvRegistryRegistersAll() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();
      ResolvedConfig config = builder.build();

      // Every EnvRegistry entry should have a resolution (may be unset, but tracked)
      for (EnvRegistry entry : EnvRegistry.values()) {
        ConfigResolution r = config.resolution(entry.configKey());
        assertNotNull(
            r,
            "Missing resolution for " + entry.name() + " (configKey=" + entry.configKey() + ")");
        // Each entry should have at least 2 candidates (jvm_arg + env_var)
        assertTrue(
            r.considered().size() >= 2,
            entry.name()
                + " should have at least 2 candidates, has "
                + r.considered().size());
      }
    }

    @Test
    @DisplayName("contributeEnvRegistry resolution matches EnvRegistry.get() for all entries")
    void parityWithEnvRegistryGet() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();
      ResolvedConfig config = builder.build();

      int divergences = 0;
      for (EnvRegistry entry : EnvRegistry.values()) {
        String legacyValue = entry.get().orElse(null);
        ConfigResolution r = config.resolution(entry.configKey());
        String resolvedValue = r != null ? r.value() : null;

        // Entries with defaultValue() will resolve to the default when no env var/sysprop
        // is set, while legacy entry.get() returns null. This is expected — the defaultValue
        // fills the gap between the two paths.
        String expectedResolved =
            legacyValue != null ? legacyValue : entry.defaultValue();
        if (!java.util.Objects.equals(expectedResolved, resolvedValue)) {
          divergences++;
          System.err.println(
              "PARITY DIVERGENCE: "
                  + entry.name()
                  + " expected="
                  + expectedResolved
                  + " resolved="
                  + resolvedValue);
        }
      }

      assertEquals(
          0,
          divergences,
          divergences + " EnvRegistry entries diverged between legacy and resolved paths");
    }
  }

  // ==================== YAML Contribution ====================

  @Nested
  @DisplayName("YAML contribution")
  class YamlContribution {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private JsonNode parseYaml(String yaml) {
      try {
        return YAML_MAPPER.readTree(yaml);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Test
    @DisplayName("contributeYaml reads watcher config from YAML")
    void watcherConfig() {
      String yaml =
          """
          index:
            watcher:
              strategy: polling
              debounce_ms: 500
              polling:
                interval_ms: 2000
              queue:
                max_entries: 1000
          """;
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeYaml(parseYaml(yaml));
      ResolvedConfig config = builder.build();

      assertEquals("polling", config.watcher().strategy());
      assertEquals(500, config.watcher().debounceMs());
      assertEquals(2000, config.watcher().pollingIntervalMs());
      assertEquals(1000, config.watcher().queueMaxEntries());
    }

    @Test
    @DisplayName("contributeYaml reads OCR config including languages list")
    void ocrConfig() {
      String yaml =
          """
          index:
            ocr:
              enabled: true
              languages:
                - eng
                - deu
              trigger:
                min_image_pixels: 10000
              limits:
                max_pages: 50
          """;
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeYaml(parseYaml(yaml));
      ResolvedConfig config = builder.build();

      assertEquals(true, config.ocr().enabled());
      assertEquals(java.util.List.of("eng", "deu"), config.ocr().languages());
      assertEquals(10000, config.ocr().triggerMinImagePixels());
      assertEquals(50, config.ocr().maxPages());
    }

    @Test
    @DisplayName("contributeYaml reads hybrid search config")
    void hybridSearchConfig() {
      String yaml =
          """
          index:
            hybrid:
              rrf_k: 45
              vector_rrf_weight: 0.80
              vector_skip_min_chars: 6
              branch_fusion_strategy: rrf
              branch_cc_weight_chunk: 0.65
              branch_chunk_min_weight_multiplier: 0.50
          """;
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeYaml(parseYaml(yaml));
      ResolvedConfig config = builder.build();

      assertEquals(45, config.hybridSearch().rrfK());
      assertEquals(0.80, config.hybridSearch().vectorRrfWeight(), 0.001);
      assertEquals(6, config.hybridSearch().vectorSkipMinChars());
      assertEquals("rrf", config.hybridSearch().branchFusionStrategy());
      assertEquals(0.65, config.hybridSearch().branchCcWeightChunk(), 0.001);
      assertEquals(0.50, config.hybridSearch().branchChunkMinWeightMultiplier(), 0.001);
    }

    @Test
    @DisplayName("contributeYaml reads RAG config")
    void ragConfig() {
      String yaml =
          """
          rag:
            retrieve:
              mode: hybrid
              top_k: 10
              overretrieve_factor: 5
            diversify:
              mode: mmr
            mmr:
              lambda: 0.7
              max_candidates: 30
          """;
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeYaml(parseYaml(yaml));
      ResolvedConfig config = builder.build();

      assertEquals("hybrid", config.rag().retrieveMode());
      assertEquals(10, config.rag().retrieveTopK());
      assertEquals(5, config.rag().overretrieveFactor());
      assertEquals("mmr", config.rag().diversifyMode());
      assertEquals(0.7, config.rag().mmrLambda(), 0.001);
      assertEquals(30, config.rag().mmrMaxCandidates());
    }

    @Test
    @DisplayName("contributeYaml reads worker limits")
    void workerConfig() {
      String yaml =
          """
          worker:
            limits:
              max_batch_size: 5000
              max_queue_depth: 50000
              max_file_size: 52428800
          """;
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeYaml(parseYaml(yaml));
      ResolvedConfig config = builder.build();

      assertEquals(5000, config.worker().maxBatchSize());
      assertEquals(50000L, config.worker().maxQueueDepth());
      assertEquals(52428800L, config.worker().maxFileSize());
    }

    @Test
    @DisplayName("contributeYaml reads index writer and vector config")
    void indexConfig() {
      String yaml =
          """
          index:
            writer:
              ram_buffer_mb: 256
            commit:
              policy: deferred
              debounce_ms: 1000
              meta:
                enabled: false
            vector:
              dimension: 384
              hnsw:
                m: 16
                ef_construction: 200
              ef_search: 128
              quantization:
                enabled: true
          """;
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeYaml(parseYaml(yaml));
      ResolvedConfig config = builder.build();

      assertEquals(256, config.index().writerRamBufferMb());
      assertEquals("deferred", config.index().commitPolicy());
      assertEquals(1000, config.index().commitDebounceMs());
      assertFalse(config.index().commitMetadataEnabled());
      assertEquals(384, config.index().vectorDimension());
      assertEquals(16, config.index().vectorHnswM());
      assertEquals(200, config.index().vectorHnswEfConstruction());
      assertEquals(128, config.index().vectorEfSearch());
      assertTrue(config.index().vectorQuantizationEnabled());
    }

    @Test
    @DisplayName("YAML llm.enabled is visible to buildAi via justsearch.llm.enabled key")
    void yamlLlmEnabledVisibleToBuildAi() {
      String yaml =
          """
          llm:
            enabled: true
            model_path: /models/llama.gguf
            mode: remote
          """;
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeYaml(parseYaml(yaml));
      ResolvedConfig config = builder.build();

      assertTrue(config.ai().llmEnabled(), "YAML llm.enabled should be visible to buildAi()");
      assertEquals(
          Path.of("/models/llama.gguf"),
          config.ai().llmModelPath(),
          "YAML llm.model_path should be visible to buildAi()");
      assertEquals("remote", config.ai().llmMode(), "YAML llm.mode should be visible to buildAi()");
    }

    @Test
    @DisplayName("putSettings contributes at ordinal 300 (below env, above YAML)")
    void putSettingsOrdinal() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.data.dir", "/default");
      builder.putSettings("justsearch.data.dir", "/from-settings");

      ConfigResolution r = builder.resolve("justsearch.data.dir");
      assertEquals("/from-settings", r.value());
      assertEquals("settings.json", r.sourceName());
      assertEquals(300, r.sourceOrdinal());
    }

    @Test
    @DisplayName("putSettings ignores null and blank values")
    void putSettingsIgnoresBlank() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("key.a", "default");
      builder.putSettings("key.a", null);
      builder.putSettings("key.a", "  ");

      ConfigResolution r = builder.resolve("key.a");
      assertEquals("default", r.value());
      assertEquals("default", r.sourceName());
    }

    @Test
    @DisplayName("defaults are used when YAML is empty")
    void defaultsUsed() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      ResolvedConfig config = builder.build();

      // RAG defaults
      assertEquals("auto", config.rag().retrieveMode());
      assertEquals(5, config.rag().retrieveTopK());
      assertEquals(0.5, config.rag().mmrLambda(), 0.001);
      // HybridSearch defaults
      assertEquals(60, config.hybridSearch().rrfK());
      assertEquals(0.75, config.hybridSearch().vectorRrfWeight(), 0.001);
      // Worker defaults
      assertEquals(10_000, config.worker().maxBatchSize());
      assertEquals(100_000L, config.worker().maxQueueDepth());
      // AI defaults — must match RuntimePolicyConfigFactory defaults
      assertTrue(config.ai().llmEnabled(), "llmEnabled default must be true (matches factory)");
      assertEquals("remote", config.ai().llmMode(), "llmMode default must be 'remote' (matches factory)");
    }
  }

  // ==================== Worker Snapshot ====================

  @Nested
  @DisplayName("Worker snapshot")
  class WorkerSnapshot {

    @Test
    @DisplayName("toWorkerSnapshot and contributeWorkerSnapshot round-trip")
    void roundTrip(@org.junit.jupiter.api.io.TempDir Path tempDir) {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.data.dir", "/data");
      builder.putDefault("justsearch.api.port", "9090");
      builder.putDefault("justsearch.llm.enabled", "true");

      ResolvedConfig original = builder.build();
      Path snapshotFile = tempDir.resolve("worker-snapshot.json");
      original.toWorkerSnapshot(snapshotFile);

      // Worker-side: load snapshot at ordinal 450
      ResolvedConfigBuilder workerBuilder = new ResolvedConfigBuilder();
      workerBuilder.contributeWorkerSnapshot(snapshotFile);
      ResolvedConfig workerConfig = workerBuilder.build();

      assertEquals(Path.of("/data").toAbsolutePath().normalize(), workerConfig.paths().dataDir());
      assertEquals(9090, workerConfig.ports().apiPort());
    }

    @Test
    @DisplayName("worker snapshot includes derived and explicit path values")
    void workerSnapshotIncludesResolvedPaths(@org.junit.jupiter.api.io.TempDir Path tempDir) {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.data.dir", "/data");
      builder.putDefault("justsearch.models.dir", "/shared/models");
      builder.putDefault("justsearch.repo.root", "/repo");
      builder.putDefault("justsearch.rerank.model_path", "/models/onnx/reranker");

      ResolvedConfig config = builder.build();
      Path snapshotFile = tempDir.resolve("worker-snapshot.json");
      config.toWorkerSnapshot(snapshotFile);

      java.util.Map<String, String> snapshot = ResolvedConfig.loadWorkerSnapshot(snapshotFile);
      assertEquals(Path.of("/data").toAbsolutePath().normalize().toString(), snapshot.get("justsearch.data.dir"));
      assertEquals(
          Path.of("/data").toAbsolutePath().normalize().resolve("index").resolve("default").toString(),
          snapshot.get("justsearch.index.base_path"));
      assertEquals(
          Path.of("/shared/models").toAbsolutePath().normalize().toString(),
          snapshot.get("justsearch.models.dir"));
      assertEquals(
          Path.of("/repo").toAbsolutePath().normalize().toString(),
          snapshot.get("justsearch.repo.root"));
      assertEquals(
          Path.of("/models/onnx/reranker").toAbsolutePath().normalize().toString(),
          snapshot.get("justsearch.rerank.model_path"));
    }

    @Test
    @DisplayName("worker snapshot ordinal 450 beats env (400) but loses to JVM (500)")
    void snapshotOrdinalPriority(@org.junit.jupiter.api.io.TempDir Path tempDir) {
      // Create a snapshot with a known value
      ResolvedConfigBuilder headBuilder = new ResolvedConfigBuilder();
      headBuilder.putDefault("test.key", "from-snapshot");
      ResolvedConfig headConfig = headBuilder.build();
      Path snapshotFile = tempDir.resolve("snapshot.json");
      headConfig.toWorkerSnapshot(snapshotFile);

      // Worker loads snapshot (450) + a default (100) + a JVM arg (500)
      ResolvedConfigBuilder workerBuilder = new ResolvedConfigBuilder();
      workerBuilder.putDefault("test.key", "from-default");
      workerBuilder.contributeWorkerSnapshot(snapshotFile);
      workerBuilder.put(
          "test.key", ResolvedConfigBuilder.ORDINAL_JVM_ARG, "jvm_arg", "test.key", "from-jvm");

      ConfigResolution r = workerBuilder.resolve("test.key");
      assertEquals("from-jvm", r.value()); // JVM (500) beats snapshot (450)
      assertEquals(500, r.sourceOrdinal());
    }

    @Test
    @DisplayName("missing snapshot file is ignored")
    void missingFileIgnored(@org.junit.jupiter.api.io.TempDir Path tempDir) {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.data.dir", "/fallback");
      builder.contributeWorkerSnapshot(tempDir.resolve("nonexistent.json"));

      ResolvedConfig config = builder.build();
      assertEquals(Path.of("/fallback"), config.paths().dataDir());
    }

    @Test
    @DisplayName("snapshot round-trips Windows paths containing backslash-n and backslash-r")
    void roundTripsWindowsPaths(@org.junit.jupiter.api.io.TempDir Path tempDir) {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      // Paths with segments that look like escape sequences after escaping
      builder.put("test.path", 500, "test", "test", "C:\\new\\results");
      builder.put("test.unc", 500, "test", "test", "\\\\server\\share");
      builder.put("test.rpath", 500, "test", "test", "C:\\reports\\new");
      builder.put("test.tab", 500, "test", "test", "C:\\tmp\\test");

      ResolvedConfig config = builder.build();
      Path snapshotFile = tempDir.resolve("snapshot.json");
      config.toWorkerSnapshot(snapshotFile);

      ResolvedConfigBuilder workerBuilder = new ResolvedConfigBuilder();
      workerBuilder.contributeWorkerSnapshot(snapshotFile);

      ConfigResolution r1 = workerBuilder.resolve("test.path");
      assertEquals("C:\\new\\results", r1.value(), "backslash-n segment corrupted");

      ConfigResolution r2 = workerBuilder.resolve("test.unc");
      assertEquals("\\\\server\\share", r2.value(), "UNC path corrupted");

      ConfigResolution r3 = workerBuilder.resolve("test.rpath");
      assertEquals("C:\\reports\\new", r3.value(), "backslash-r segment corrupted");

      ConfigResolution r4 = workerBuilder.resolve("test.tab");
      assertEquals("C:\\tmp\\test", r4.value(), "backslash-t segment corrupted");
    }
  }

  // ==================== Auto-Detected Values (ordinal 150) ====================

  @Nested
  @DisplayName("contributeAutoDetected (ordinal 150)")
  class AutoDetected {

    @Test
    @DisplayName("auto-detected value at ordinal 150 is available")
    void autoDetectedValueAvailable() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeAutoDetected(java.util.Map.of("justsearch.gpu.enabled", "true"));
      ConfigResolution r = builder.resolve("justsearch.gpu.enabled");
      assertEquals("true", r.value());
      assertEquals(150, r.sourceOrdinal());
    }

    @Test
    @DisplayName("env var at ordinal 400 overrides auto-detected at 150")
    void envVarOverridesAutoDetected() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeAutoDetected(java.util.Map.of("test.gpu", "true"));
      builder.put("test.gpu", ResolvedConfigBuilder.ORDINAL_ENV_VAR, "env_var",
          "TEST_GPU", "false");
      ConfigResolution r = builder.resolve("test.gpu");
      assertEquals("false", r.value());
      assertEquals(400, r.sourceOrdinal());
    }

    @Test
    @DisplayName("sysprop at ordinal 500 overrides auto-detected at 150")
    void syspropOverridesAutoDetected() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeAutoDetected(java.util.Map.of("test.gpu", "true"));
      builder.put("test.gpu", ResolvedConfigBuilder.ORDINAL_JVM_ARG, "jvm_arg",
          "test.gpu", "false");
      ConfigResolution r = builder.resolve("test.gpu");
      assertEquals("false", r.value());
      assertEquals(500, r.sourceOrdinal());
    }

    @Test
    @DisplayName("null map is ignored")
    void nullMapIgnored() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeAutoDetected(null);
      builder.putDefault("justsearch.data.dir", "/fallback");
      // Should not throw
      ResolvedConfig config = builder.build();
      assertEquals(Path.of("/fallback"), config.paths().dataDir());
    }

    @Test
    @DisplayName("empty map is a no-op")
    void emptyMapNoOp() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeAutoDetected(java.util.Map.of());
      builder.putDefault("justsearch.data.dir", "/fallback");
      ResolvedConfig config = builder.build();
      assertEquals(Path.of("/fallback"), config.paths().dataDir());
    }
  }

  // ==================== Clamping and Validation ====================

  @Nested
  @DisplayName("Clamping and validation")
  class ClampingValidation {

    @Test
    @DisplayName("RAG top_k is clamped to [1, 50]")
    void ragTopKClamped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("rag.retrieve.top_k", "100");
      assertEquals(50, builder.build().rag().retrieveTopK());

      builder = new ResolvedConfigBuilder();
      builder.putDefault("rag.retrieve.top_k", "0");
      assertEquals(1, builder.build().rag().retrieveTopK());
    }

    @Test
    @DisplayName("RAG overretrieve_factor is clamped to [1, 10]")
    void ragOverretrieveFactorClamped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("rag.retrieve.overretrieve_factor", "20");
      assertEquals(10, builder.build().rag().overretrieveFactor());

      builder = new ResolvedConfigBuilder();
      builder.putDefault("rag.retrieve.overretrieve_factor", "-5");
      assertEquals(1, builder.build().rag().overretrieveFactor());
    }

    @Test
    @DisplayName("RAG mmr_lambda is clamped to [0.0, 1.0]")
    void ragMmrLambdaClamped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("rag.mmr.lambda", "2.0");
      assertEquals(1.0, builder.build().rag().mmrLambda(), 0.001);

      builder = new ResolvedConfigBuilder();
      builder.putDefault("rag.mmr.lambda", "-0.5");
      assertEquals(0.0, builder.build().rag().mmrLambda(), 0.001);
    }

    @Test
    @DisplayName("RAG mmr_max_candidates is clamped to [1, 200]")
    void ragMmrMaxCandidatesClamped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("rag.mmr.max_candidates", "500");
      assertEquals(200, builder.build().rag().mmrMaxCandidates());
    }

    @Test
    @DisplayName("HybridSearch candidate_limit_max is clamped to >= 1")
    void hybridCandidateLimitClamped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("index.hybrid.candidate_limit_max", "0");
      assertEquals(1, builder.build().hybridSearch().candidateLimitMax());
    }

    @Test
    @DisplayName("HybridSearch vector_rrf_weight is clamped to [0.0, 1.0]")
    void hybridVectorRrfWeightClamped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("index.hybrid.vector_rrf_weight", "1.5");
      assertEquals(1.0, builder.build().hybridSearch().vectorRrfWeight(), 0.001);

      builder = new ResolvedConfigBuilder();
      builder.putDefault("index.hybrid.vector_rrf_weight", "-0.3");
      assertEquals(0.0, builder.build().hybridSearch().vectorRrfWeight(), 0.001);
    }

    @Test
    @DisplayName("HybridSearch multipliers are clamped to >= 1")
    void hybridMultipliersClamped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("index.hybrid.text_candidate_multiplier", "0");
      builder.putDefault("index.hybrid.vector_candidate_multiplier", "-5");
      ResolvedConfig config = builder.build();
      assertEquals(1, config.hybridSearch().textCandidateMultiplier());
      assertEquals(1, config.hybridSearch().vectorCandidateMultiplier());
    }

    @Test
    @DisplayName("Index migration cutover max_failed_jobs is clamped to >= -1")
    void indexMigrationCutoverClamped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("index.migration.cutover.max_failed_jobs", "-10");
      assertEquals(-1, builder.build().index().migrationCutoverMaxFailedJobs());
    }

    @Test
    @DisplayName("Ports are clamped to [0, 65535]")
    void portsClamped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.api.port", "99999");
      builder.putDefault("justsearch.server.port", "70000");
      ResolvedConfig config = builder.build();
      assertEquals(65535, config.ports().apiPort());
      assertEquals(65535, config.ports().serverPort());
    }
  }

  // ==================== Schema Mismatch Policy Normalization ====================

  @Nested
  @DisplayName("Schema mismatch policy normalization")
  class SchemaMismatchPolicy {

    @Test
    @DisplayName("lowercase input normalizes to uppercase canonical form")
    void lowercaseNormalizesToUppercase() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("index.schema_mismatch.policy", "rebuild_backup_first");

      ResolvedConfig config = builder.build();
      assertEquals("REBUILD_BACKUP_FIRST", config.index().schemaMismatchPolicy());
    }

    @Test
    @DisplayName("mixed-case input normalizes to uppercase canonical form")
    void mixedCaseNormalizesToUppercase() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("index.schema_mismatch.policy", "Rebuild_Backup_First");

      ResolvedConfig config = builder.build();
      assertEquals("REBUILD_BACKUP_FIRST", config.index().schemaMismatchPolicy());
    }

    @Test
    @DisplayName("kebab-case input normalizes to uppercase canonical form")
    void kebabCaseNormalizesToUppercase() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("index.schema_mismatch.policy", "rebuild-backup-first");

      ResolvedConfig config = builder.build();
      assertEquals("REBUILD_BACKUP_FIRST", config.index().schemaMismatchPolicy());
    }

    @Test
    @DisplayName("fail_closed variants all normalize to FAIL_CLOSED")
    void failClosedVariants() {
      for (String variant : new String[] {"fail_closed", "FAIL_CLOSED", "fail-closed", "fail"}) {
        ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
        builder.putDefault("index.schema_mismatch.policy", variant);

        assertEquals(
            "FAIL_CLOSED",
            builder.build().index().schemaMismatchPolicy(),
            "Expected FAIL_CLOSED for input: " + variant);
      }
    }

    @Test
    @DisplayName("blue_green_migrate variants all normalize")
    void blueGreenVariants() {
      for (String variant :
          new String[] {
            "blue_green_migrate", "blue-green-migrate", "blue_green", "blue-green"
          }) {
        ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
        builder.putDefault("index.schema_mismatch.policy", variant);

        assertEquals(
            "BLUE_GREEN_MIGRATE",
            builder.build().index().schemaMismatchPolicy(),
            "Expected BLUE_GREEN_MIGRATE for input: " + variant);
      }
    }

    @Test
    @DisplayName("null/blank defaults to REBUILD_BACKUP_FIRST in non-prod mode")
    void nullDefaultsToRebuildInNonProd() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      // Do not set index.schema_mismatch.policy — leave null
      // Do not set justsearch.prod — defaults to false

      ResolvedConfig config = builder.build();
      assertEquals("REBUILD_BACKUP_FIRST", config.index().schemaMismatchPolicy());
    }

    @Test
    @DisplayName("null/blank defaults to FAIL_CLOSED in prod mode")
    void nullDefaultsToFailClosedInProd() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.prod", "true");

      ResolvedConfig config = builder.build();
      assertEquals("FAIL_CLOSED", config.index().schemaMismatchPolicy());
    }
  }

  // ==================== ConfigResolution ====================

  @Nested
  @DisplayName("ConfigResolution")
  class ConfigResolutionTests {

    @Test
    @DisplayName("toLogString formats resolved value with source info")
    void toLogStringResolved() {
      ConfigResolution r =
          new ConfigResolution(
              "justsearch.data.dir",
              "/tmp/data",
              "env_var",
              400,
              "JUSTSEARCH_DATA_DIR",
              java.util.List.of());
      assertEquals(
          "justsearch.data.dir=/tmp/data (env_var:JUSTSEARCH_DATA_DIR, ordinal=400)",
          r.toLogString());
    }

    @Test
    @DisplayName("toLogString formats unset value")
    void toLogStringUnset() {
      ConfigResolution r =
          new ConfigResolution(
              "justsearch.data.dir", null, "none", 0, null, java.util.List.of());
      assertEquals("justsearch.data.dir=<unset>", r.toLogString());
    }
  }

  // ==================== Architectural Coverage (tempdoc 347) ====================

  @Nested
  @DisplayName("Architectural coverage (tempdoc 347)")
  class ArchitecturalCoverage {

    @Test
    @DisplayName("Every EnvRegistry configKey is unique")
    void configKeysAreUnique() {
      java.util.Map<String, EnvRegistry> seen = new java.util.LinkedHashMap<>();
      for (EnvRegistry entry : EnvRegistry.values()) {
        String key = entry.configKey();
        EnvRegistry existing = seen.put(key, entry);
        assertNull(existing,
            () -> "Duplicate configKey '" + key + "' between "
                + existing.name() + " and " + entry.name());
      }
    }

    @Test
    @DisplayName("Every EnvRegistry sysProp is unique")
    void syspropsAreUnique() {
      java.util.Map<String, EnvRegistry> seen = new java.util.LinkedHashMap<>();
      for (EnvRegistry entry : EnvRegistry.values()) {
        String key = entry.sysProp();
        EnvRegistry existing = seen.put(key, entry);
        assertNull(existing,
            () -> "Duplicate sysProp '" + key + "' between "
                + existing.name() + " and " + entry.name());
      }
    }

    @Test
    @DisplayName("Every EnvRegistry envVar is unique")
    void envVarsAreUnique() {
      java.util.Map<String, EnvRegistry> seen = new java.util.LinkedHashMap<>();
      for (EnvRegistry entry : EnvRegistry.values()) {
        String key = entry.envVar();
        EnvRegistry existing = seen.put(key, entry);
        assertNull(existing,
            () -> "Duplicate envVar '" + key + "' between "
                + existing.name() + " and " + entry.name());
      }
    }

    @Test
    @DisplayName("contributeEnvRegistry registers every entry in the ordinal chain")
    void contributeEnvRegistryRegistersAll() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();

      java.util.Set<String> registeredKeys = new java.util.TreeSet<>();
      // Build to populate allResolutions; build() resolves all entries.keySet()
      ResolvedConfig config = builder.build();
      registeredKeys.addAll(config.resolutions().keySet());

      for (EnvRegistry entry : EnvRegistry.values()) {
        assertTrue(registeredKeys.contains(entry.configKey()),
            () -> "EnvRegistry." + entry.name() + " configKey '" + entry.configKey()
                + "' not found in resolved keys. "
                + "contributeEnvRegistry() should register it.");
      }
    }

    @Test
    @DisplayName("build() resolves all keys without error when only EnvRegistry is contributed")
    void buildSucceedsWithEnvRegistryOnly() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();
      ResolvedConfig config = builder.build();
      assertNotNull(config);
      assertNotNull(config.resolutions());
      assertFalse(config.resolutions().isEmpty());
    }

    @Test
    @DisplayName("No configKey collides with a different entry's sysProp")
    void noConfigKeySysPropCollision() {
      // If entry A's configKey == entry B's sysProp (and A != B),
      // contributeEnvRegistry would register two different entries under the same key.
      java.util.Map<String, EnvRegistry> bySysProp = new java.util.HashMap<>();
      for (EnvRegistry entry : EnvRegistry.values()) {
        bySysProp.put(entry.sysProp(), entry);
      }
      for (EnvRegistry entry : EnvRegistry.values()) {
        if (entry.configKey().equals(entry.sysProp())) continue; // no override, safe
        EnvRegistry collision = bySysProp.get(entry.configKey());
        assertNull(collision,
            () -> "EnvRegistry." + entry.name() + " configKey '" + entry.configKey()
                + "' collides with EnvRegistry." + collision.name() + " sysProp");
      }
    }

    @Test
    @DisplayName("Every key resolved by build() has an EnvRegistry or ConfigKey entry (reverse direction)")
    void everyResolvedKeyHasRegistryEntry() {
      // Build with EnvRegistry contributions so all entries are registered in the ordinal chain.
      // build() then calls build*() methods which call resolve*() for their keys.
      // resolvedKeys() captures every key that resolve() touched.
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();
      builder.build();

      java.util.Set<String> knownConfigKeys = new java.util.HashSet<>();
      for (EnvRegistry entry : EnvRegistry.values()) {
        knownConfigKeys.add(entry.configKey());
      }
      for (io.justsearch.configuration.ConfigKey entry
          : io.justsearch.configuration.ConfigKey.values()) {
        knownConfigKeys.add(entry.configKey());
      }

      java.util.List<String> uncovered = new java.util.ArrayList<>();
      for (String resolvedKey : builder.resolvedKeys()) {
        if (!knownConfigKeys.contains(resolvedKey)) {
          uncovered.add(resolvedKey);
        }
      }
      assertTrue(uncovered.isEmpty(),
          "Keys resolved by build*() methods without an EnvRegistry or ConfigKey entry: "
              + uncovered + ". Add an entry with configKey matching each key.");
    }

    @Test
    @DisplayName("Master gpu.enabled=true falls through to per-encoder gpuEnabled when per-key unset (Bug D)")
    void masterFallthroughEnablesPerEncoderGpu() {
      // Tempdoc 374 alpha.16 fix D — root-cause investigation. Round-6 sandbox
      // agent reported worker had master justsearch.gpu.enabled=true but
      // embed.gpuEnabled=false. resolveEmbedGpuEnabled has master fallback
      // logic (line 966-976). This test pins it.
      String prevMaster = System.getProperty("justsearch.gpu.enabled");
      String prevEmbed = System.getProperty("justsearch.embed.gpu.enabled");
      String prevSplade = System.getProperty("justsearch.splade.gpu_enabled");
      String prevNer = System.getProperty("justsearch.ner.gpu_enabled");
      String prevPolicy = System.getProperty("policy.gpu_acceleration_enabled");
      try {
        System.setProperty("justsearch.gpu.enabled", "true");
        System.clearProperty("justsearch.embed.gpu.enabled");
        System.clearProperty("justsearch.splade.gpu_enabled");
        System.clearProperty("justsearch.ner.gpu_enabled");
        System.clearProperty("policy.gpu_acceleration_enabled");

        ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
        builder.contributeEnvRegistry();
        ResolvedConfig config = builder.build();

        assertTrue(config.ai().embedding().gpuEnabled(),
            "embedding.gpuEnabled must fall through to master=true when per-key unset");
        assertTrue(config.ai().splade().gpuEnabled(),
            "splade.gpuEnabled must fall through to master=true when per-key unset");
        assertTrue(config.ai().ner().gpuEnabled(),
            "ner.gpuEnabled must fall through to master=true when per-key unset");
        assertTrue(config.ai().reranker().gpuEnabled(),
            "reranker.gpuEnabled defaults true via EnvRegistry default");
      } finally {
        restoreSysprop("justsearch.gpu.enabled", prevMaster);
        restoreSysprop("justsearch.embed.gpu.enabled", prevEmbed);
        restoreSysprop("justsearch.splade.gpu_enabled", prevSplade);
        restoreSysprop("justsearch.ner.gpu_enabled", prevNer);
        restoreSysprop("policy.gpu_acceleration_enabled", prevPolicy);
      }
    }

    private void restoreSysprop(String key, String prev) {
      if (prev != null) System.setProperty(key, prev);
      else System.clearProperty(key);
    }

    @Test
    @DisplayName("Master fallthrough via worker snapshot at ord 450 (mimics round-6 sandbox)")
    void masterFallthroughViaWorkerSnapshot(@org.junit.jupiter.api.io.TempDir Path tempDir)
        throws Exception {
      // Round-6 sandbox: worker-config-snapshot.json has justsearch.gpu.enabled=true but
      // embed.gpuEnabled=false at the worker. Mimic exact worker setup: snapshot at 450 +
      // envRegistry (no sysprop, no per-feature key set anywhere).
      String prevMaster = System.getProperty("justsearch.gpu.enabled");
      String prevEmbed = System.getProperty("justsearch.embed.gpu.enabled");
      String prevSplade = System.getProperty("justsearch.splade.gpu_enabled");
      String prevNer = System.getProperty("justsearch.ner.gpu_enabled");
      String prevPolicy = System.getProperty("policy.gpu_acceleration_enabled");
      try {
        // Crucially: master is in the SNAPSHOT, NOT a sysprop. Per-feature keys unset everywhere.
        System.clearProperty("justsearch.gpu.enabled");
        System.clearProperty("justsearch.embed.gpu.enabled");
        System.clearProperty("justsearch.splade.gpu_enabled");
        System.clearProperty("justsearch.ner.gpu_enabled");
        System.clearProperty("policy.gpu_acceleration_enabled");

        // Write a snapshot file with master=true (and a few other realistic keys).
        Path snapshotFile = tempDir.resolve("worker-config-snapshot.json");
        String snapshotJson = "{\n"
            + "  \"justsearch.gpu.enabled\": \"true\",\n"
            + "  \"justsearch.gpu.layers\": \"99\",\n"
            + "  \"justsearch.rerank.gpu.enabled\": \"true\"\n"
            + "}\n";
        java.nio.file.Files.writeString(snapshotFile, snapshotJson);

        ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
        builder.contributeAutoDetected(java.util.Map.of()); // empty (worker probe failed)
        builder.contributeWorkerSnapshot(snapshotFile);
        builder.contributeEnvRegistry();
        ResolvedConfig config = builder.build();

        assertTrue(config.ai().embedding().gpuEnabled(),
            "embed.gpuEnabled at worker must fall through to master via snapshot ord 450");
        assertTrue(config.ai().splade().gpuEnabled(),
            "splade.gpuEnabled at worker must fall through to master via snapshot ord 450");
        assertTrue(config.ai().ner().gpuEnabled(),
            "ner.gpuEnabled at worker must fall through to master via snapshot ord 450");
      } finally {
        restoreSysprop("justsearch.gpu.enabled", prevMaster);
        restoreSysprop("justsearch.embed.gpu.enabled", prevEmbed);
        restoreSysprop("justsearch.splade.gpu_enabled", prevSplade);
        restoreSysprop("justsearch.ner.gpu_enabled", prevNer);
        restoreSysprop("policy.gpu_acceleration_enabled", prevPolicy);
      }
    }

    @Test
    @DisplayName("GPU policy gate vetoes per-model GPU when policy is false")
    void gpuPolicyGateVetoesPerModelGpu() {
      String prevSplade = System.getProperty("justsearch.splade.gpu_enabled");
      String prevPolicy = System.getProperty("policy.gpu_acceleration_enabled");
      try {
        System.setProperty("justsearch.splade.gpu_enabled", "true");
        System.setProperty("policy.gpu_acceleration_enabled", "false");

        ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
        builder.contributeEnvRegistry();
        ResolvedConfig config = builder.build();

        assertFalse(config.ai().splade().gpuEnabled(),
            "splade.gpuEnabled must be false when policy gate is false, "
                + "even if SPLADE_GPU_ENABLED=true");
        assertFalse(config.ai().embedding().gpuEnabled(),
            "embedding.gpuEnabled must be false when policy gate is false");
      } finally {
        if (prevSplade != null) System.setProperty("justsearch.splade.gpu_enabled", prevSplade);
        else System.clearProperty("justsearch.splade.gpu_enabled");
        if (prevPolicy != null) System.setProperty("policy.gpu_acceleration_enabled", prevPolicy);
        else System.clearProperty("policy.gpu_acceleration_enabled");
      }
    }
  }

  // ==================== Worker-boot base composition (tempdoc 628) ====================

  @Nested
  @DisplayName("contributeBaseSources — shared worker-boot composition (tempdoc 628)")
  class BaseSourcesComposition {

    @Test
    @DisplayName("contributeBaseSources reads index.auto_recovery from YAML (the standalone-worker fix)")
    void baseSourcesContributesYaml(@TempDir Path tmp) throws Exception {
      Path yaml = tmp.resolve("application.yaml");
      Files.writeString(yaml, "index:\n  auto_recovery: true\n  recovery:\n    policy: FAIL_CLOSED\n");
      String key = EnvRegistry.CONFIG_PATH.sysProp();
      String prev = System.getProperty(key);
      System.setProperty(key, yaml.toString());
      try {
        // The exact standalone-worker shape after the fix: auto-detected + base (env + YAML).
        ResolvedConfig config =
            new ResolvedConfigBuilder()
                .contributeAutoDetected(Map.of())
                .contributeBaseSources()
                .build();
        assertTrue(
            config.index().indexAutoRecovery(),
            "contributeBaseSources must contribute YAML so standalone recovery is enabled");
        assertEquals("FAIL_CLOSED", config.index().indexRecoveryPolicy());
      } finally {
        if (prev == null) {
          System.clearProperty(key);
        } else {
          System.setProperty(key, prev);
        }
      }
    }

    @Test
    @DisplayName("Pre-fix shape (env only, no YAML) silently defaults index.auto_recovery to false")
    void envOnlyDefaultsFalse() {
      // The exact tempdoc 628 defect: the standalone worker composed auto+env WITHOUT yaml, so
      // index.auto_recovery (a YAML-only key) silently defaulted to false and recovery was disabled.
      ResolvedConfig config =
          new ResolvedConfigBuilder()
              .contributeAutoDetected(Map.of())
              .contributeEnvRegistry()
              .build();
      assertFalse(
          config.index().indexAutoRecovery(),
          "documents the pre-fix divergence: env-only standalone defaulted recovery off");
    }
  }
}
