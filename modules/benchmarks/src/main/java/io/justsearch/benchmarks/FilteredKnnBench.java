/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.benchmarks;

import tools.jackson.databind.ObjectMapper;

import io.justsearch.benchmarks.util.BenchmarkCli;
import io.justsearch.benchmarks.util.BenchmarkUtils;
import io.justsearch.benchmarks.util.MachineFingerprint;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filtered kNN microbenchmark (Tier 2 uncertainty gate).
 *
 * <p>Measures Lucene kNN latency under different filter shapes/sizes (doc_id IN, path prefix) to
 * establish the safe operating envelope for filtered vector search.
 *
 * <p>Usage:
 * <pre>
 *   java FilteredKnnBench --out-dir=tmp/bench/filtered-knn --doc-count=20000 --vector-dim=768
 * </pre>
 */
public final class FilteredKnnBench {

  private static final Logger log = LoggerFactory.getLogger(FilteredKnnBench.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static void main(String[] args) throws Exception {
    String outDir = "tmp/bench/filtered-knn";
    int docCount = 20_000; // Default document count for synthetic corpus
    int vectorDim = 768; // Standard embedding dimension (e.g., MiniLM, MPNet)
    String kValuesCsv = "10"; // Default k value; supports multiple via CSV (e.g., "1,10,100,1000")
    int warmup = 5; // JVM JIT warmup iterations before measurement
    int iterations = 50; // Latency measurement samples per scenario
    String docIdInSizesCsv = "10,100,1000,10000";
    String parentIdInSizesCsv = null;
    String pathPrefix = "d:/bench/folder0/";
    boolean chunkMode = false;
    int chunksPerParent = 10;
    String efSearchValuesCsv = ""; // Empty means use system default; supports CSV (e.g., "50,100,200")

    for (String arg : args) {
      if (arg.startsWith("--out-dir=")) {
        outDir = arg.substring("--out-dir=".length());
      } else if (arg.startsWith("--doc-count=")) {
        docCount = Integer.parseInt(arg.substring("--doc-count=".length()));
      } else if (arg.startsWith("--vector-dim=")) {
        vectorDim = Integer.parseInt(arg.substring("--vector-dim=".length()));
      } else if (arg.startsWith("--k-values=")) {
        // New: CSV list of k values to test (e.g., "1,10,100,1000")
        kValuesCsv = arg.substring("--k-values=".length());
      } else if (arg.startsWith("--k=")) {
        // Legacy: single k value (kept for backward compatibility)
        kValuesCsv = arg.substring("--k=".length());
      } else if (arg.startsWith("--warmup=")) {
        warmup = Integer.parseInt(arg.substring("--warmup=".length()));
      } else if (arg.startsWith("--iterations=")) {
        iterations = Integer.parseInt(arg.substring("--iterations=".length()));
      } else if (arg.startsWith("--docid-in-sizes=")) {
        docIdInSizesCsv = arg.substring("--docid-in-sizes=".length());
      } else if (arg.startsWith("--parentid-in-sizes=")) {
        parentIdInSizesCsv = arg.substring("--parentid-in-sizes=".length());
      } else if (arg.startsWith("--path-prefix=")) {
        pathPrefix = arg.substring("--path-prefix=".length());
      } else if (arg.equals("--chunk-mode")) {
        chunkMode = true;
      } else if (arg.startsWith("--chunk-mode=")) {
        chunkMode = Boolean.parseBoolean(arg.substring("--chunk-mode=".length()));
      } else if (arg.startsWith("--chunks-per-parent=")) {
        chunksPerParent = Integer.parseInt(arg.substring("--chunks-per-parent=".length()));
      } else if (arg.startsWith("--ef-search-values=")) {
        // New: CSV list of ef_search values to test (e.g., "50,100,200")
        efSearchValuesCsv = arg.substring("--ef-search-values=".length());
      } else if (arg.startsWith("--ef-search=")) {
        // Legacy: single ef_search value (kept for backward compatibility)
        efSearchValuesCsv = arg.substring("--ef-search=".length());
      }
    }

    if (docCount < 1) {
      throw new IllegalArgumentException("--doc-count must be > 0");
    }
    if (vectorDim < 1) {
      throw new IllegalArgumentException("--vector-dim must be > 0");
    }
    // Parse k values (supports CSV like "1,10,100,1000")
    List<Integer> kValues = BenchmarkCli.parsePositiveIntCsv(kValuesCsv);
    if (kValues.isEmpty()) {
      kValues = List.of(10); // Default to k=10 if parsing fails
    }

    // Parse ef_search values (supports CSV like "0,50,100")
    // 0 means "use system default" - parseNonNegativeIntCsv allows 0 values
    List<Integer> efSearchValues = BenchmarkCli.parseNonNegativeIntCsv(efSearchValuesCsv);
    if (efSearchValues.isEmpty()) {
      efSearchValues = List.of(0); // 0 = use system default
    }

    if (iterations < 1) {
      throw new IllegalArgumentException("--iterations must be > 0");
    }
    if (chunksPerParent < 1) {
      throw new IllegalArgumentException("--chunks-per-parent must be > 0");
    }

    if (parentIdInSizesCsv == null || parentIdInSizesCsv.isBlank()) {
      parentIdInSizesCsv = docIdInSizesCsv;
    }

    Path outPath = Paths.get(outDir);
    Files.createDirectories(outPath);

    // Create temporary index directory (deleted after run).
    Path indexPath = Files.createTempDirectory("bench-filtered-knn-index-");
    log.info("Using temp index path: {}", indexPath);

    io.justsearch.adapters.lucene.runtime.RunningRuntime runtime = null;
    try {
      FieldCatalogDef catalog =
          chunkMode ? FieldCatalogDef.forChunkTesting(vectorDim) : FieldCatalogDef.forTesting(vectorDim);
      runtime =
          io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(catalog)
              .atPath(indexPath)
              .open();

      if (chunkMode) {
        log.info(
            "Indexing {} chunk docs (vectorDim={}, chunksPerParent={})...",
            docCount,
            vectorDim,
            chunksPerParent);
        indexChunkDocs(runtime, docCount, vectorDim, chunksPerParent);
      } else {
        log.info("Indexing {} docs (vectorDim={})...", docCount, vectorDim);
        indexDocs(runtime, docCount, vectorDim);
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefresh();

      long indexDirBytes = 0L;
      try {
        indexDirBytes = BenchmarkUtils.directorySizeBytes(indexPath);
      } catch (IOException e) {
        log.warn("Failed to compute index directory size", e);
      }

      // Random seed 999: Query vector generation
      // Different from corpus seed (123) to avoid query-in-corpus bias
      float[] queryVector = BenchmarkUtils.randomVector(new Random(999), vectorDim);

      List<Map<String, Object>> scenarios = new ArrayList<>();

      // Loop over ef_search values (outer) and k values (inner) to test HNSW search quality/speed tradeoffs
      for (int efSearch : efSearchValues) {
        // Apply ef_search for this iteration (0 means use system default)
        if (efSearch > 0) {
          System.setProperty("index.vector.ef_search", String.valueOf(efSearch));
          log.info("Set ef_search={} for this iteration", efSearch);
        } else {
          System.clearProperty("index.vector.ef_search");
          log.info("Using system default ef_search for this iteration");
        }

        String efSuffix = efSearchValues.size() > 1 ? "_ef" + efSearch : "";

        for (int k : kValues) {
          String kSuffix = kValues.size() > 1 ? "_k" + k : ""; // Only add suffix if testing multiple k
          String suffix = efSuffix + kSuffix; // Combined suffix for scenario naming

          if (!chunkMode) {
            // Baseline: unfiltered kNN
            scenarios.add(
                runScenario(
                    runtime,
                    "unfiltered" + suffix,
                    "none",
                    docCount,
                    queryVector,
                    k,
                    warmup,
                    iterations,
                    null));

            // doc_id IN (...) filters (sweep)
            for (int size : BenchmarkCli.parsePositiveIntCsv(docIdInSizesCsv)) {
              int effective = Math.min(size, docCount);
              Query filter = docIdInFilter(effective);
              long filterHits = countMatches(runtime, filter);
              scenarios.add(
                  runScenario(
                      runtime,
                      "doc_id_in_" + effective + suffix,
                      "doc_id_in",
                      filterHits,
                      queryVector,
                      k,
                      warmup,
                      iterations,
                      filter));
            }

            // path prefix filter
            Query pathFilter = new PrefixQuery(new Term(SchemaFields.PATH, pathPrefix));
            long pathHits = countMatches(runtime, pathFilter);
            scenarios.add(
                runScenario(
                    runtime,
                    "path_prefix" + suffix,
                    "path_prefix",
                    pathHits,
                    queryVector,
                    k,
                    warmup,
                    iterations,
                    pathFilter));
          } else {
            int parentCount = Math.max(1, (docCount + chunksPerParent - 1) / chunksPerParent);
            // Random seed 4242: Parent ID shuffling for random vs sequential filter comparison
            List<String> parentIdsShuffled = shuffledIds("doc-", parentCount, 4242);

            Query isChunkFilter = new TermQuery(new Term(SchemaFields.IS_CHUNK, "true"));

            // Baseline: unfiltered (should still effectively search only chunk docs since parent docs have no vectors).
            scenarios.add(
                runScenario(
                    runtime,
                    "chunks_unfiltered" + suffix,
                    "none",
                    docCount,
                    queryVector,
                    k,
                    warmup,
                    iterations,
                    null));

            // Baseline: chunk-only filter (represents "always include is_chunk=true" safety belt).
            long isChunkHits = countMatches(runtime, isChunkFilter);
            scenarios.add(
                runScenario(
                    runtime,
                    "chunks_is_chunk" + suffix,
                    "is_chunk",
                    isChunkHits,
                    queryVector,
                    k,
                    warmup,
                    iterations,
                    isChunkFilter));

            // parent_doc_id IN (...) filters (Phase-6-like scoping shape)
            for (int size : BenchmarkCli.parsePositiveIntCsv(parentIdInSizesCsv)) {
              int effectiveParents = Math.min(size, parentCount);

              Query firstParents = parentDocIdInFilter(effectiveParents);
              Query filterFirst = andFilters(isChunkFilter, firstParents);
              long filterHitsFirst = countMatches(runtime, filterFirst);
              scenarios.add(
                  runScenario(
                      runtime,
                      "parent_doc_id_in_first_" + effectiveParents + suffix,
                      "parent_doc_id_in",
                      filterHitsFirst,
                      queryVector,
                      k,
                      warmup,
                      iterations,
                      filterFirst));

              Query randomParents = parentDocIdInFilter(parentIdsShuffled, effectiveParents);
              Query filterRandom = andFilters(isChunkFilter, randomParents);
              long filterHitsRandom = countMatches(runtime, filterRandom);
              scenarios.add(
                  runScenario(
                      runtime,
                      "parent_doc_id_in_random_" + effectiveParents + suffix,
                      "parent_doc_id_in",
                      filterHitsRandom,
                      queryVector,
                      k,
                      warmup,
                      iterations,
                      filterRandom));
            }

            // path prefix filter (user filter shape) applied to chunk docs
            Query pathFilter = new PrefixQuery(new Term(SchemaFields.PATH, pathPrefix));
            Query chunkPathFilter = andFilters(isChunkFilter, pathFilter);
            long pathHits = countMatches(runtime, chunkPathFilter);
            scenarios.add(
                runScenario(
                    runtime,
                    "chunk_path_prefix" + suffix,
                    "path_prefix",
                    pathHits,
                    queryVector,
                    k,
                    warmup,
                    iterations,
                    chunkPathFilter));

            // combined filter shape: parent scope + user filter
            for (int size : BenchmarkCli.parsePositiveIntCsv(parentIdInSizesCsv)) {
              int effectiveParents = Math.min(size, parentCount);
              Query firstParents = parentDocIdInFilter(effectiveParents);
              Query combinedFilter = andFilters(isChunkFilter, firstParents, pathFilter);
              long combinedHits = countMatches(runtime, combinedFilter);
              scenarios.add(
                  runScenario(
                      runtime,
                      "parent_in_first_" + effectiveParents + "_and_path_prefix" + suffix,
                      "parent_doc_id_in_and_path_prefix",
                      combinedHits,
                      queryVector,
                      k,
                      warmup,
                      iterations,
                      combinedFilter));
            }
          }
        } // end for (int k : kValues)
      } // end for (int efSearch : efSearchValues)

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("schema_version", 1);
      result.put("kind", "filtered-knn-bench.v1");
      result.put("captured_at", Instant.now().toString());

      String gitSha = BenchmarkUtils.getGitSha();
      if (gitSha != null) {
        result.put("git_sha", gitSha);
      }

      result.put("machine_fingerprint", MachineFingerprint.capture().toMap());

      Map<String, Object> index = new LinkedHashMap<>();
      index.put("doc_count", docCount);
      index.put("vector_dim", vectorDim);
      index.put("index_dir_bytes", indexDirBytes);
      if (chunkMode) {
        int parentCount = Math.max(1, (docCount + chunksPerParent - 1) / chunksPerParent);
        index.put("parent_doc_count", parentCount);
        index.put("total_doc_count", docCount + parentCount);
      }
      result.put("index", index);

      Map<String, Object> knobs = new LinkedHashMap<>();
      knobs.put("k_values", kValues);
      knobs.put("warmup", warmup);
      knobs.put("iterations", iterations);
      knobs.put("docid_in_sizes", docIdInSizesCsv);
      knobs.put("parentid_in_sizes", parentIdInSizesCsv);
      knobs.put("path_prefix", pathPrefix);
      knobs.put("chunk_mode", chunkMode);
      knobs.put("chunks_per_parent", chunksPerParent);
      knobs.put("ef_search_values", efSearchValues);
      knobs.put(
          "ef_search_note",
          "ef_search=0 means use system default (no override); positive values override HNSW ef_search");
      result.put("knobs", knobs);

      result.put("scenarios", scenarios);

      Path jsonPath = outPath.resolve("result.json");
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), result);
      log.info("Wrote result to: {}", jsonPath);

      Path mdPath = outPath.resolve("summary.md");
      Files.writeString(mdPath, renderMarkdown(result), StandardCharsets.UTF_8);
      log.info("Wrote summary to: {}", mdPath);

    } finally {
      if (runtime != null) {
        runtime.close();
      }
      BenchmarkUtils.deleteRecursively(indexPath);
    }
  }

  private static void indexDocs(io.justsearch.adapters.lucene.runtime.RunningRuntime runtime, int docCount, int dim) {
    // Random seed 123: Deterministic corpus generation for reproducibility
    // Different from query seed (999) to avoid query-in-corpus bias
    Random rnd = new Random(123);
    for (int i = 0; i < docCount; i++) {
      String docId = "doc-" + i;
      String path = "d:/bench/folder" + (i % 10) + "/doc-" + i + ".txt";
      float[] vec = BenchmarkUtils.randomVector(rnd, dim);

      Map<String, Object> fields = new HashMap<>();
      fields.put(SchemaFields.DOC_ID, docId);
      fields.put(SchemaFields.DOC_UID, docId + "#0");
      fields.put(SchemaFields.PATH, path);
      fields.put(SchemaFields.CONTENT, "lorem ipsum");
      fields.put(SchemaFields.MIME, "text/plain");
      fields.put(SchemaFields.MIME_BASE, "text/plain");
      fields.put(SchemaFields.FILE_KIND, "text");
      fields.put(SchemaFields.LANGUAGE, "en-US");
      fields.put(SchemaFields.VECTOR, vec);
      fields.put(SchemaFields.INDEXED_AT, System.currentTimeMillis());

      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    }
  }

  private static void indexChunkDocs(
      io.justsearch.adapters.lucene.runtime.RunningRuntime runtime,
      int chunkDocCount,
      int dim,
      int chunksPerParent) {

    int parentCount = Math.max(1, (chunkDocCount + chunksPerParent - 1) / chunksPerParent);

    // Index parent docs without vectors (kNN should operate only over chunk docs).
    for (int i = 0; i < parentCount; i++) {
      String docId = "doc-" + i;
      String path = "d:/bench/folder" + (i % 10) + "/doc-" + i + ".txt";

      Map<String, Object> fields = new HashMap<>();
      fields.put(SchemaFields.DOC_ID, docId);
      fields.put(SchemaFields.DOC_UID, docId + "#0");
      fields.put(SchemaFields.PATH, path);
      fields.put(SchemaFields.CONTENT, "lorem ipsum parent");
      fields.put(SchemaFields.MIME, "text/plain");
      fields.put(SchemaFields.MIME_BASE, "text/plain");
      fields.put(SchemaFields.FILE_KIND, "text");
      fields.put(SchemaFields.LANGUAGE, "en-US");
      fields.put(SchemaFields.INDEXED_AT, System.currentTimeMillis());

      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    }

    // Index chunk docs with vectors + parent linkage.
    // Random seed 123: Deterministic corpus generation for reproducibility
    // Different from query seed (999) to avoid query-in-corpus bias
    Random rnd = new Random(123);
    for (int i = 0; i < chunkDocCount; i++) {
      int parentIndex = i / chunksPerParent;
      int chunkIndex = i % chunksPerParent;
      int totalForParent = Math.min(chunksPerParent, chunkDocCount - (parentIndex * chunksPerParent));

      String chunkId = "chunk-" + i;
      String parentId = "doc-" + parentIndex;
      String path = "d:/bench/folder" + (parentIndex % 10) + "/doc-" + parentIndex + ".txt";

      float[] vec = BenchmarkUtils.randomVector(rnd, dim);

      Map<String, Object> fields = new HashMap<>();
      fields.put(SchemaFields.DOC_ID, chunkId);
      fields.put(SchemaFields.DOC_UID, chunkId + "#0");
      fields.put(SchemaFields.PATH, path);
      fields.put(SchemaFields.MIME, "text/plain");
      fields.put(SchemaFields.MIME_BASE, "text/plain");
      fields.put(SchemaFields.FILE_KIND, "text");
      fields.put(SchemaFields.LANGUAGE, "en-US");

      fields.put(SchemaFields.IS_CHUNK, "true");
      fields.put(SchemaFields.PARENT_DOC_ID, parentId);
      fields.put(SchemaFields.CHUNK_INDEX, (long) chunkIndex);
      fields.put(SchemaFields.CHUNK_TOTAL, (long) totalForParent);
      fields.put(SchemaFields.CHUNK_CONTENT, "chunk " + i + " of " + parentId);
      fields.put(SchemaFields.VECTOR, vec);

      fields.put(SchemaFields.INDEXED_AT, System.currentTimeMillis());

      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    }
  }

  private static Map<String, Object> runScenario(
      io.justsearch.adapters.lucene.runtime.RunningRuntime runtime,
      String name,
      String filterType,
      long filterHits,
      float[] queryVector,
      int k,
      int warmup,
      int iterations,
      Query filter) {

    // Warmup
    for (int i = 0; i < warmup; i++) {
      runtime.readPathOps().searchVector(queryVector, k, filter);
    }

    List<Long> samplesNs = new ArrayList<>(iterations);
    for (int i = 0; i < iterations; i++) {
      long start = System.nanoTime();
      runtime.readPathOps().searchVector(queryVector, k, filter);
      long end = System.nanoTime();
      samplesNs.add(end - start);
    }

    // Note: BenchmarkUtils.percentileLong uses R-7 linear interpolation (fixed algorithm)
    double p50 = BenchmarkUtils.percentileLong(samplesNs, 0.50) / 1_000_000.0;
    double p95 = BenchmarkUtils.percentileLong(samplesNs, 0.95) / 1_000_000.0;
    double p99 = BenchmarkUtils.percentileLong(samplesNs, 0.99) / 1_000_000.0;
    double min = BenchmarkUtils.percentileLong(samplesNs, 0.0) / 1_000_000.0;
    double max = BenchmarkUtils.percentileLong(samplesNs, 1.0) / 1_000_000.0;

    Map<String, Object> latency = new LinkedHashMap<>();
    latency.put("unit", "ms");
    latency.put("min", BenchmarkUtils.round3(min));
    latency.put("p50", BenchmarkUtils.round3(p50));
    latency.put("p95", BenchmarkUtils.round3(p95));
    latency.put("p99", BenchmarkUtils.round3(p99));
    latency.put("max", BenchmarkUtils.round3(max));
    latency.put("samples", iterations);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("name", name);
    out.put("filter_type", filterType);
    out.put("filter_hits", filterHits);
    out.put("latency", latency);
    return out;
  }

  private static Query docIdInFilter(int size) {
    if (size <= 0) {
      return new MatchAllDocsQuery();
    }
    BytesRef[] refs = new BytesRef[size];
    for (int i = 0; i < size; i++) {
      refs[i] = new BytesRef("doc-" + i);
    }
    return new TermInSetQuery(SchemaFields.DOC_ID, Arrays.asList(refs));
  }

  private static Query parentDocIdInFilter(int size) {
    if (size <= 0) {
      return new MatchAllDocsQuery();
    }
    BytesRef[] refs = new BytesRef[size];
    for (int i = 0; i < size; i++) {
      refs[i] = new BytesRef("doc-" + i);
    }
    return new TermInSetQuery(SchemaFields.PARENT_DOC_ID, Arrays.asList(refs));
  }

  private static Query parentDocIdInFilter(List<String> shuffledParentIds, int size) {
    if (size <= 0) {
      return new MatchAllDocsQuery();
    }
    int effective = Math.min(size, shuffledParentIds.size());
    BytesRef[] refs = new BytesRef[effective];
    for (int i = 0; i < effective; i++) {
      refs[i] = new BytesRef(shuffledParentIds.get(i));
    }
    return new TermInSetQuery(SchemaFields.PARENT_DOC_ID, Arrays.asList(refs));
  }

  private static Query andFilters(Query... filters) {
    if (filters == null || filters.length == 0) {
      return null;
    }
    BooleanQuery.Builder qb = new BooleanQuery.Builder();
    boolean hasAny = false;
    for (Query f : filters) {
      if (f == null) continue;
      qb.add(f, BooleanClause.Occur.FILTER);
      hasAny = true;
    }
    return hasAny ? qb.build() : null;
  }

  private static List<String> shuffledIds(String prefix, int count, int seed) {
    List<String> ids = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      ids.add(prefix + i);
    }
    Random rnd = new Random(seed);
    for (int i = ids.size() - 1; i > 0; i--) {
      int j = rnd.nextInt(i + 1);
      String tmp = ids.get(i);
      ids.set(i, ids.get(j));
      ids.set(j, tmp);
    }
    return ids;
  }

  private static long countMatches(io.justsearch.adapters.lucene.runtime.RunningRuntime runtime, Query filter) {
    if (filter == null) {
      return 0;
    }
    var r = runtime.readPathOps().search(filter, 1, Set.of(SchemaFields.DOC_ID), io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE, null);
    return r.totalHits();
  }

  private static String renderMarkdown(Map<String, Object> result) {
    @SuppressWarnings("unchecked")
    Map<String, Object> index = (Map<String, Object>) result.getOrDefault("index", Map.of());
    @SuppressWarnings("unchecked")
    Map<String, Object> knobs = (Map<String, Object>) result.getOrDefault("knobs", Map.of());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> scenarios = (List<Map<String, Object>>) result.getOrDefault("scenarios", List.of());

    StringBuilder sb = new StringBuilder();
    sb.append("# Filtered kNN Microbenchmark\n\n");
    sb.append("- captured_at: ").append(result.get("captured_at")).append("\n");
    if (result.containsKey("git_sha")) {
      sb.append("- git_sha: ").append(result.get("git_sha")).append("\n");
    }
    sb.append("- doc_count: ").append(index.get("doc_count")).append("\n");
    if (index.containsKey("total_doc_count")) {
      sb.append("- total_doc_count: ").append(index.get("total_doc_count")).append("\n");
      sb.append("- parent_doc_count: ").append(index.get("parent_doc_count")).append("\n");
    }
    sb.append("- vector_dim: ").append(index.get("vector_dim")).append("\n");
    sb.append("- index_dir_bytes: ").append(index.get("index_dir_bytes")).append("\n");
    sb.append("- k_values: ").append(knobs.get("k_values")).append("\n");
    sb.append("- warmup: ").append(knobs.get("warmup")).append("\n");
    sb.append("- iterations: ").append(knobs.get("iterations")).append("\n");
    sb.append("- docid_in_sizes: ").append(knobs.get("docid_in_sizes")).append("\n");
    sb.append("- parentid_in_sizes: ").append(knobs.get("parentid_in_sizes")).append("\n");
    sb.append("- path_prefix: ").append(knobs.get("path_prefix")).append("\n");
    sb.append("- chunk_mode: ").append(knobs.get("chunk_mode")).append("\n");
    sb.append("- chunks_per_parent: ").append(knobs.get("chunks_per_parent")).append("\n");
    sb.append("- ef_search_values: ").append(knobs.get("ef_search_values")).append("\n\n");

    sb.append("| scenario | filter_type | filter_hits | p50_ms | p95_ms | p99_ms | max_ms |\n");
    sb.append("|---|---|---:|---:|---:|---:|---:|\n");
    for (Map<String, Object> sc : scenarios) {
      @SuppressWarnings("unchecked")
      Map<String, Object> latency = (Map<String, Object>) sc.getOrDefault("latency", Map.of());
      sb.append("| ")
          .append(sc.getOrDefault("name", ""))
          .append(" | ")
          .append(sc.getOrDefault("filter_type", ""))
          .append(" | ")
          .append(sc.getOrDefault("filter_hits", 0))
          .append(" | ")
          .append(latency.getOrDefault("p50", ""))
          .append(" | ")
          .append(latency.getOrDefault("p95", ""))
          .append(" | ")
          .append(latency.getOrDefault("p99", ""))
          .append(" | ")
          .append(latency.getOrDefault("max", ""))
          .append(" |\n");
    }
    return sb.toString();
  }
}
