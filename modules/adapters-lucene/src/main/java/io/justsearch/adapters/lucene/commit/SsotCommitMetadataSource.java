/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.commit;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds commit metadata from SSOT artifacts with deterministic hashing and canonical JSON.
 */
public final class SsotCommitMetadataSource implements CommitMetadataSource {
  private static final ObjectMapper M =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
  private final File repoRoot;
  private final SsotAnalyzerRegistry analyzerRegistry;
  private final SsotAnalyzerRegistry.AnalyzerFingerprintingService fingerprintingService;
  private volatile String cachedAnalyzerFingerprint;
  private volatile Integer vectorDimensionOverride;

  public SsotCommitMetadataSource() {
    this.repoRoot = resolveRepoRoot();
    this.analyzerRegistry = new SsotAnalyzerRegistry();
    this.fingerprintingService = new SsotAnalyzerRegistry.AnalyzerFingerprintingService();
  }

  /**
   * Sets a vector dimension override that modifies the {@code index_schema_fp} fingerprint.
   *
   * <p>When the effective vector dimension differs from the SSOT catalog's declared dimension
   * (e.g., 1024 for BGE-M3 vs 768 for nomic-embed), this override ensures the schema fingerprint
   * changes, triggering the parity check and schema migration on startup.
   *
   * <p>The raw {@code field_catalog_hash} is unaffected — it always reflects the on-disk file.
   *
   * @param dimension the effective vector dimension (e.g., 1024)
   */
  public void setVectorDimensionOverride(int dimension) {
    this.vectorDimensionOverride = dimension;
  }

  private static File resolveRepoRoot() {
    java.nio.file.Path root = JustSearchConfigurationLoader.repoRootStatic();
    if (root == null) {
      throw new IllegalStateException("Repository root not found (no SSOT directory)");
    }
    return root.toFile();
  }

  @Override
  public Map<String, Object> build() {
    try {
      Map<String, Object> out = new LinkedHashMap<>();

      // versions/catalog.json
      JsonNode versions = M.readTree(file("SSOT/versions/catalog.json"));
      String schemaVer = versions.path("intent_v1").path("schema_ver").asText();
      String grammarVer = versions.path("intent_v1").path("grammar_ver").asText();
      int templateVer = versions.path("intent_v1").path("template_ver").get(0).asInt();

      // required hashes (canonical JSON for JSON, raw bytes concatenation for text/gbnf)
      out.put("schema_ver", schemaVer);
      out.put("schema_fp", sha256Json(file("SSOT/schemas/domain/search-intent.schema.json")));
      String fieldCatalogHash = sha256Json(file("SSOT/catalogs/fields.v1.json"));
      out.put("field_catalog_hash", fieldCatalogHash);
      // index_schema_fp: incorporates runtime overrides (e.g., vector dimension) that affect
      // the effective schema without modifying the on-disk catalog file.
      String indexSchemaFp = fieldCatalogHash;
      Integer dimOverride = this.vectorDimensionOverride;
      if (dimOverride != null) {
        indexSchemaFp =
            sha256Bytes(
                (fieldCatalogHash + ":vectorDim=" + dimOverride)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      out.put("index_schema_fp", indexSchemaFp);
      // Per-language synonym lists were removed in tempdoc 581 §13 / ADR-0043 (native
      // multilingual, no per-language levers). synonyms_hash is retained as a commit-metadata /
      // observability identity field (consumed by telemetry spans + jseval) and is now the
      // SHA-256 of the empty synonym set. It is NOT a parity key, so this value change does not
      // affect existing on-disk indices.
      out.put("synonyms_hash", sha256Bytes(new byte[0]));
      out.put("analyzer_fp", analyzerFingerprint());

      // grammar/templates/prompts
      out.put("grammar_ver", grammarVer);
      out.put("grammar_hash", sha256Bytes(Files.readAllBytes(file("SSOT/artifacts/grammars/intent_v1.gbnf").toPath())));
      out.put("template_ver", templateVer);
      out.put("prompt_pack_hash", sha256Concat(List.of(
          file("SSOT/prompts/en/intent.v1.json"),
          file("SSOT/prompts/en/summary.v1.json"))));

      // Optional descriptors: similarity_fp (from config, defaults applied) and boosts_fp
      out.put("similarity_fp", sha256Bytes(similarityDescriptorFromConfig().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      // boosts fingerprint from app-config index.boosts (deterministic)
      String boostsJson = boostsCanonicalJson();
      out.put("boosts_fp", sha256Bytes(boostsJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

      // feature toggle for grammar (default ON in this slice)
      out.put("grammar_on", true);

      // F6: Vector quantization format stamp (storage optimization, not parity-checked)
      try {
        ResolvedConfig rc = resolvedConfigOrFallback();
        boolean quantized =
            rc != null && Boolean.TRUE.equals(rc.index().vectorQuantizationEnabled());
        out.put("vector_format", quantized ? "int8_sq" : "float32");
      } catch (Exception e) {
        out.put("vector_format", "float32"); // fallback for tests
      }

      return Map.copyOf(out);
    } catch (IOException e) {
      throw new IllegalStateException("Failed building commit metadata from SSOT", e);
    }
  }

  private File file(String relative) { return new File(repoRoot, relative); }

  private String analyzerFingerprint() {
    String fp = cachedAnalyzerFingerprint;
    if (fp != null) {
      return fp;
    }
    synchronized (this) {
      fp = cachedAnalyzerFingerprint;
      if (fp == null) {
        fp = fingerprintingService.fingerprint(analyzerRegistry, analyzerRegistry.analyzerIds());
        cachedAnalyzerFingerprint = fp;
      }
    }
    return fp;
  }

  private static String sha256Json(File jsonFile) throws IOException {
    JsonNode node = M.readTree(jsonFile);
    byte[] canonical = canonicalJson(node);
    return sha256Bytes(canonical);
  }

  private static String sha256Concat(List<File> files) throws IOException {
    List<File> sorted = files.stream().sorted((a, b) -> a.getPath().compareTo(b.getPath())).collect(Collectors.toList());
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    for (File f : sorted) bos.write(Files.readAllBytes(f.toPath()));
    return sha256Bytes(bos.toByteArray());
  }

  private static String sha256Bytes(byte[] b) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(b);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte x : digest) sb.append(String.format("%02x", x));
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] canonicalJson(JsonNode node) throws IOException {
    try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
        JsonGenerator g = M.tokenStreamFactory().createGenerator(bos)
            .configure(StreamWriteFeature.AUTO_CLOSE_TARGET, true)) {
      M.writeTree(g, node);
      return bos.toByteArray();
    }
  }

  private static String similarityDescriptorFromConfig() {
    try {
        ResolvedConfig rc = resolvedConfigOrFallback();
        ResolvedConfig.Index idx = rc != null ? rc.index() : null;
        String cls = org.apache.lucene.search.similarities.BM25Similarity.class.getName();
        float k1 = idx != null && idx.similarityTextK1() != null
            ? idx.similarityTextK1().floatValue() : 0.9f;
        float b = idx != null && idx.similarityTextB() != null
            ? idx.similarityTextB().floatValue() : 0.4f;
        return cls + "(k1=" + trimFloat(k1) + ",b=" + trimFloat(b) + ")";
    } catch (Exception e) {
        return org.apache.lucene.search.similarities.BM25Similarity.class.getName() + "(k1=0.900,b=0.400)";
    }
  }

  /** Resolves config from ConfigStore if available, otherwise builds from RuntimeConfig. */
  private static ResolvedConfig resolvedConfigOrFallback() {
    ConfigStore store = ConfigStore.globalOrNull();
    if (store != null) {
      ResolvedConfig cfg = store.get();
      if (cfg != null) return cfg;
    }
    io.justsearch.configuration.resolved.ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeBaseSources();
    return builder.build();
  }

  private static String trimFloat(float v) {
    // Produce a stable short decimal for descriptor
    return String.format(java.util.Locale.ROOT, "%.3f", v);
  }

  private static String boostsCanonicalJson() throws IOException {
    try {
      Map<String, Double> boosts = resolvedConfigOrFallback().index().boosts();
      // boosts is already TreeMap-backed (deterministic key order) from ResolvedConfig
      return M.writeValueAsString(boosts);
    } catch (Exception e) {
      // Fallback for tests
      return "{}";
    }
  }
}
