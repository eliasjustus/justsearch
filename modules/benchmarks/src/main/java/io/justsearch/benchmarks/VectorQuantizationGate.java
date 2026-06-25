/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.benchmarks;

import tools.jackson.databind.ObjectMapper;
import io.justsearch.adapters.lucene.runtime.JustSearchCodec;
import io.justsearch.benchmarks.util.BenchmarkUtils;
import io.justsearch.benchmarks.util.MachineFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104HnswScalarQuantizedVectorsFormat;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quantization gate (Tier 2 uncertainty gate).
 *
 * <p>Creates a tiny index under each mode, then commit/close/reopen and run a kNN query:
 * - Float32 HNSW vs scalar-quantized HNSW
 * - compound file on vs off
 *
 * <p>This turns "quantized vectors are broken on Windows" into a verified yes/no with
 * a reproducible artifact.
 */
public final class VectorQuantizationGate {

  private static final Logger log = LoggerFactory.getLogger(VectorQuantizationGate.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  // HNSW M=16: Number of bidirectional links per node (Lucene default)
  // Higher M = better recall but slower builds and larger index
  private static final int DEFAULT_M = 16;
  // HNSW efConstruction=100: Build-time quality factor (Lucene default)
  // Higher values improve recall at cost of slower indexing
  private static final int DEFAULT_EF_CONSTRUCTION = 100;

  public static void main(String[] args) throws Exception {
    String outDir = "tmp/bench/quantization-gate";
    int docCount = 5_000;
    int vectorDim = 768;
    int k = 10;
    boolean keepIndex = false;

    for (String arg : args) {
      if (arg.startsWith("--out-dir=")) {
        outDir = arg.substring("--out-dir=".length());
      } else if (arg.startsWith("--doc-count=")) {
        docCount = Integer.parseInt(arg.substring("--doc-count=".length()));
      } else if (arg.startsWith("--vector-dim=")) {
        vectorDim = Integer.parseInt(arg.substring("--vector-dim=".length()));
      } else if (arg.startsWith("--k=")) {
        k = Integer.parseInt(arg.substring("--k=".length()));
      } else if (arg.equals("--keep-index")) {
        keepIndex = true;
      }
    }

    if (docCount < 1) {
      throw new IllegalArgumentException("--doc-count must be > 0");
    }
    if (vectorDim < 1) {
      throw new IllegalArgumentException("--vector-dim must be > 0");
    }
    if (k < 1) {
      throw new IllegalArgumentException("--k must be > 0");
    }

    Path outPath = Paths.get(outDir);
    Files.createDirectories(outPath);

    // Random seed 999: Query vector generation
    // Different from corpus seed (123) to avoid query-in-corpus bias
    float[] queryVector = BenchmarkUtils.randomVector(new Random(999), vectorDim);

    List<Mode> modes =
        List.of(
            new Mode("float_cfs_on", false, true),
            new Mode("float_cfs_off", false, false),
            new Mode("quant_cfs_on", true, true),
            new Mode("quant_cfs_off", true, false));

    List<Map<String, Object>> results = new ArrayList<>();
    for (Mode mode : modes) {
      results.add(runMode(mode, docCount, vectorDim, k, queryVector, keepIndex));
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("schema_version", 1);
    out.put("kind", "quantization-gate.v1");
    out.put("captured_at", Instant.now().toString());

    out.put("machine_fingerprint", MachineFingerprint.capture().toMap());

    Map<String, Object> knobs = new LinkedHashMap<>();
    knobs.put("doc_count", docCount);
    knobs.put("vector_dim", vectorDim);
    knobs.put("k", k);
    knobs.put("keep_index", keepIndex);
    out.put("knobs", knobs);

    out.put("modes", results);

    Path jsonPath = outPath.resolve("result.json");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), out);
    log.info("Wrote result to: {}", jsonPath);

    Path mdPath = outPath.resolve("summary.md");
    Files.writeString(mdPath, renderMarkdown(out), StandardCharsets.UTF_8);
    log.info("Wrote summary to: {}", mdPath);
  }

  private record Mode(String name, boolean quantized, boolean useCompoundFile) {}

  private static Map<String, Object> runMode(
      Mode mode,
      int docCount,
      int vectorDim,
      int k,
      float[] queryVector,
      boolean keepIndex) {

    Map<String, Object> r = new LinkedHashMap<>();
    r.put("name", mode.name);
    r.put("quantized", mode.quantized);
    r.put("use_compound_file", mode.useCompoundFile);

    Path indexPath = null;
    try {
      indexPath = Files.createTempDirectory("bench-quant-gate-" + mode.name + "-");
      r.put("index_path", keepIndex ? indexPath.toString() : "");

      // Step 1: index + commit
      KnnVectorsFormat format =
          mode.quantized
              ? new Lucene104HnswScalarQuantizedVectorsFormat(DEFAULT_M, DEFAULT_EF_CONSTRUCTION)
              : new Lucene99HnswVectorsFormat(DEFAULT_M, DEFAULT_EF_CONSTRUCTION);

      try (Directory dir = FSDirectory.open(indexPath)) {
        IndexWriterConfig cfg = new IndexWriterConfig(new StandardAnalyzer());
        cfg.setUseCompoundFile(mode.useCompoundFile);
        cfg.setCodec(new JustSearchCodec(format));
        TieredMergePolicy mp = new TieredMergePolicy();
        if (mode.useCompoundFile) {
          mp.setNoCFSRatio(1.0);
          mp.setMaxCFSSegmentSizeMB(Double.POSITIVE_INFINITY);
        } else {
          mp.setNoCFSRatio(0.0);
          mp.setMaxCFSSegmentSizeMB(0.0);
        }
        cfg.setMergePolicy(mp);
        // RAM buffer 8MB: Small buffer to encourage multiple segments + merges,
        // where compound/codec interactions tend to surface
        cfg.setRAMBufferSizeMB(8.0);

        try (IndexWriter w = new IndexWriter(dir, cfg)) {
          // Random seed 123: Deterministic corpus generation for reproducibility
          // Different from query seed (999) to avoid query-in-corpus bias
          Random rnd = new Random(123);
          for (int i = 0; i < docCount; i++) {
            Document d = new Document();
            d.add(new StringField("doc_id", "doc-" + i, Field.Store.YES));
            d.add(new KnnFloatVectorField("vector", BenchmarkUtils.randomVector(rnd, vectorDim)));
            w.addDocument(d);
          }
          w.commit();
          w.forceMerge(1);
          w.commit();
        }
      }

      // Capture files after commit/close
      List<String> files;
      try (var stream = Files.list(indexPath)) {
        files = stream.map(p -> p.getFileName().toString()).sorted().toList();
      }
      r.put("files", files);
      r.put("has_vemq", files.stream().anyMatch(s -> s.endsWith(".vemq")));
      r.put("has_veq", files.stream().anyMatch(s -> s.endsWith(".veq")));
      r.put("has_cfs", files.stream().anyMatch(s -> s.endsWith(".cfs")));

      // Step 2: reopen + kNN query
      try (Directory dir = FSDirectory.open(indexPath);
           DirectoryReader reader = DirectoryReader.open(dir)) {
        IndexSearcher searcher = new IndexSearcher(reader);
        Query q = new KnnFloatVectorQuery("vector", queryVector, k);
        TopDocs td = searcher.search(q, k);
        r.put("search_hits", td.scoreDocs.length);
      }

      r.put("ok", true);
      return r;

    } catch (Exception e) {
      r.put("ok", false);
      r.put("error", e.toString());
      return r;

    } finally {
      if (!keepIndex && indexPath != null) {
        try {
          BenchmarkUtils.deleteRecursively(indexPath);
        } catch (IOException ignored) {
          // best-effort cleanup
        }
      }
    }
  }

  private static String renderMarkdown(Map<String, Object> out) {
    @SuppressWarnings("unchecked")
    Map<String, Object> knobs = (Map<String, Object>) out.getOrDefault("knobs", Map.of());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> modes = (List<Map<String, Object>>) out.getOrDefault("modes", List.of());

    StringBuilder sb = new StringBuilder();
    sb.append("# Vector Quantization Gate\n\n");
    sb.append("- captured_at: ").append(out.get("captured_at")).append("\n");
    sb.append("- doc_count: ").append(knobs.get("doc_count")).append("\n");
    sb.append("- vector_dim: ").append(knobs.get("vector_dim")).append("\n");
    sb.append("- k: ").append(knobs.get("k")).append("\n\n");

    sb.append("| mode | quantized | compound | ok | hits | has_vemq | has_cfs | error |\n");
    sb.append("|---|---:|---:|---:|---:|---:|---:|---|\n");
    for (Map<String, Object> m : modes) {
      sb.append("| ")
          .append(m.getOrDefault("name", ""))
          .append(" | ")
          .append(m.getOrDefault("quantized", false))
          .append(" | ")
          .append(m.getOrDefault("use_compound_file", false))
          .append(" | ")
          .append(m.getOrDefault("ok", false))
          .append(" | ")
          .append(m.getOrDefault("search_hits", 0))
          .append(" | ")
          .append(m.getOrDefault("has_vemq", false))
          .append(" | ")
          .append(m.getOrDefault("has_cfs", false))
          .append(" | ")
          .append(String.valueOf(m.getOrDefault("error", "")).replace("|", "\\|"))
          .append(" |\n");
    }
    return sb.toString();
  }
}
