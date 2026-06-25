/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.benchmarks;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.benchmarks.util.BenchmarkUtils;
import io.justsearch.benchmarks.util.MachineFingerprint;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Engine-only vector indexing benchmark (Lane V).
 *
 * <p>Indexes a pre-embedded vector corpus (NDJSON with {doc_id, vector}) into Lucene and measures
 * time-to-searchable (ingest start through sentinel vector search).
 *
 * <p>Usage: java EngineVectorIndexBench --vectors=path/to/vectors.ndjson --out-dir=tmp/bench/vector
 */
@SuppressWarnings("PMD.UnusedAssignment") // Intentional timing variable assignments for benchmarking
public final class EngineVectorIndexBench {
  private static final Logger log = LoggerFactory.getLogger(EngineVectorIndexBench.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SENTINEL_DOC_ID = "__bench_sentinel__";

  private record QueryVector(String queryId, float[] vector) {}

  private enum TimerStart {
    BEFORE_CORPUS_READ,
    AFTER_CORPUS_READ,
    AFTER_RUNTIME_START
  }

  @SuppressWarnings("PMD.SystemPrintln")
  public static void main(String[] args) throws Exception {
    String vectorsPath = null;
    String outDir = "tmp/bench/vector";
    String corpusId = "";
    String timerStartRaw = "after_runtime_start";
    int batchSize = 1000;
    int queryLimit = 10;
    int queryCountLimit = 25;
    String queriesVectorsPath = null;
    String truthKnnPath = null;
    int hnswMOverride = 0;
    int efConstructionOverride = 0;
    int efSearchOverride = 0;
    Boolean quantizationEnabledOverride = null;

    for (String arg : args) {
      if (arg.startsWith("--vectors=")) {
        vectorsPath = arg.substring("--vectors=".length());
      } else if (arg.startsWith("--corpus=")) {
        vectorsPath = arg.substring("--corpus=".length());
      } else if (arg.startsWith("--out-dir=")) {
        outDir = arg.substring("--out-dir=".length());
      } else if (arg.startsWith("--corpus-id=")) {
        corpusId = arg.substring("--corpus-id=".length());
      } else if (arg.startsWith("--timer-start=")) {
        timerStartRaw = arg.substring("--timer-start=".length());
      } else if (arg.startsWith("--batch-size=")) {
        try {
          batchSize = Integer.parseInt(arg.substring("--batch-size=".length()));
        } catch (Exception ignored) {
          // keep default
        }
      } else if (arg.startsWith("--query-limit=")) {
        try {
          queryLimit = Integer.parseInt(arg.substring("--query-limit=".length()));
        } catch (Exception ignored) {
          // keep default
        }
      } else if (arg.startsWith("--query-count=")) {
        try {
          queryCountLimit = Integer.parseInt(arg.substring("--query-count=".length()));
        } catch (Exception ignored) {
          // keep default
        }
      } else if (arg.startsWith("--queries-vectors=")) {
        queriesVectorsPath = arg.substring("--queries-vectors=".length());
      } else if (arg.startsWith("--truth-knn=")) {
        truthKnnPath = arg.substring("--truth-knn=".length());
      } else if (arg.startsWith("--hnsw-m=")) {
        try {
          hnswMOverride = Integer.parseInt(arg.substring("--hnsw-m=".length()));
        } catch (Exception ignored) {
          // keep default
        }
      } else if (arg.startsWith("--ef-construction=")) {
        try {
          efConstructionOverride = Integer.parseInt(arg.substring("--ef-construction=".length()));
        } catch (Exception ignored) {
          // keep default
        }
      } else if (arg.startsWith("--ef-search=")) {
        try {
          efSearchOverride = Integer.parseInt(arg.substring("--ef-search=".length()));
        } catch (Exception ignored) {
          // keep default
        }
      } else if (arg.startsWith("--quantization-enabled=")) {
        String raw = arg.substring("--quantization-enabled=".length());
        if (!raw.isBlank()) {
          quantizationEnabledOverride = Boolean.parseBoolean(raw.trim());
        }
      }
    }

    if (vectorsPath == null || vectorsPath.isBlank()) {
      System.err.println("Usage: EngineVectorIndexBench --vectors=<ndjson> [--out-dir=<dir>]");
      System.exit(1);
    }

    TimerStart timerStart = TimerStart.AFTER_RUNTIME_START;
    if (timerStartRaw != null) {
      String norm = timerStartRaw.trim().toLowerCase(Locale.ROOT);
      if (norm.equals("before_corpus_read")) {
        timerStart = TimerStart.BEFORE_CORPUS_READ;
      } else if (norm.equals("after_corpus_read")) {
        timerStart = TimerStart.AFTER_CORPUS_READ;
      } else if (norm.equals("after_runtime_start") || norm.equals("default")) {
        timerStart = TimerStart.AFTER_RUNTIME_START;
      }
    }

    if (batchSize <= 0) {
      batchSize = 1000;
    }
    if (queryCountLimit < 0) {
      queryCountLimit = 0;
    }

    Path vectors = Paths.get(vectorsPath);
    if (!Files.exists(vectors)) {
      System.err.println("Vectors file not found: " + vectorsPath);
      System.exit(1);
    }

    if (corpusId == null || corpusId.isBlank()) {
      corpusId = vectors.getFileName().toString().replace(".ndjson", "");
    }

    Path outPath = Paths.get(outDir);
    Files.createDirectories(outPath);

    log.info("Starting engine-only vector indexing benchmark");
    log.info("  Vectors: {}", vectors.toAbsolutePath());
    log.info("  Corpus:  {}", corpusId);
    log.info("  Output:  {}", outPath.toAbsolutePath());
    log.info("  Timer:   {}", timerStart);
    log.info("  Batch:   {}", batchSize);
    log.info("  Query:   limit={}", queryLimit);
    if (queriesVectorsPath != null && !queriesVectorsPath.isBlank()) {
      log.info("  Query:   vectors={}", Paths.get(queriesVectorsPath).toAbsolutePath());
    }
    if (truthKnnPath != null && !truthKnnPath.isBlank()) {
      log.info("  Truth:   knn={}", Paths.get(truthKnnPath).toAbsolutePath());
    }
    if (hnswMOverride > 0) {
      log.info("  ANN:     hnsw_m={}", hnswMOverride);
    }
    if (efConstructionOverride > 0) {
      log.info("  ANN:     ef_construction={}", efConstructionOverride);
    }
    if (efSearchOverride > 0) {
      log.info("  ANN:     ef_search={}", efSearchOverride);
    }
    if (quantizationEnabledOverride != null) {
      log.info("  ANN:     quantization_enabled={}", quantizationEnabledOverride);
    }

    // Bench-local overrides are applied via system properties so they work reliably even when the
    // benchmark is launched from a Gradle daemon.
    if (hnswMOverride > 0) {
      System.setProperty("index.vector.hnsw.m", String.valueOf(hnswMOverride));
    }
    if (efConstructionOverride > 0) {
      System.setProperty(
          "index.vector.hnsw.ef_construction", String.valueOf(efConstructionOverride));
    }
    if (efSearchOverride > 0) {
      System.setProperty("index.vector.ef_search", String.valueOf(efSearchOverride));
    }
    if (quantizationEnabledOverride != null) {
      System.setProperty(
          "index.vector.quantization.enabled",
          String.valueOf(quantizationEnabledOverride));
    }

    JustSearchConfigurationLoader loader = new JustSearchConfigurationLoader();
    FieldCatalogDef catalog = loader.loadFieldCatalog();
    log.info("Loaded field catalog version: {}", catalog.version());

    Path indexPath = Files.createTempDirectory("bench-vector-index-");
    log.info("Using temp index path: {}", indexPath);

    long corpusReadStartNanos = 0;
    long corpusReadEndNanos = 0;
    long ingestStartNanos = 0;
    long ingestEndNanos = 0;

    long runtimeStartBeginNanos = 0;
    long runtimeStartEndNanos = 0;

    long indexBatchCallNanos = 0;
    long commitStartNanos = 0;
    long commitEndNanos = 0;
    long refreshStartNanos = 0;
    long refreshEndNanos = 0;
    long sentinelQueryStartNanos = 0;
    long sentinelQueryEndNanos = 0;

    int docCount = 0;
    int vectorDim = 0;
    float[] sentinelVector = null;
    boolean sentinelValidated = false;
    List<float[]> sampleQueryVectors = new ArrayList<>();

    // Capture heap and RSS baseline before any heavy work
    BenchmarkUtils.HeapSnapshot heapBefore = BenchmarkUtils.HeapSnapshot.capture();
    BenchmarkUtils.RssSnapshot rssBefore = BenchmarkUtils.RssSnapshot.capture();

    io.justsearch.configuration.resolved.ResolvedConfig.Index rcIdx =
        io.justsearch.configuration.resolved.ConfigStore.globalOrNull() != null
            ? io.justsearch.configuration.resolved.ConfigStore.global().get().index() : null;

    // Phase 2-3 Step E: builder.open() collapses construct + start into a single call.
    runtimeStartBeginNanos = System.nanoTime();
    io.justsearch.adapters.lucene.runtime.RunningRuntime runtime =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(catalog)
            .atPath(indexPath)
            .open();
    runtimeStartEndNanos = System.nanoTime();
    try {

      long benchStartNanos = runtimeStartEndNanos;

      if (timerStart == TimerStart.AFTER_CORPUS_READ) {
        // To exclude corpus read time, we must fully materialize vectors before indexing.
        List<Map<String, Object>> rows = new ArrayList<>();
        List<float[]> vectorsForQueries = new ArrayList<>();
        corpusReadStartNanos = System.nanoTime();
        try (BufferedReader reader = Files.newBufferedReader(vectors, StandardCharsets.UTF_8)) {
          String line;
          while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode node = MAPPER.readTree(line);
            String docId = node.path("doc_id").asText("");
            if (docId.isBlank()) continue;
            JsonNode vecNode = node.path("vector");
            if (!vecNode.isArray() || vecNode.isEmpty()) continue;

            int dim = vecNode.size();
            if (vectorDim == 0) {
              vectorDim = dim;
            } else if (dim != vectorDim) {
              throw new IllegalArgumentException(
                  "Vector dimension drift: expected " + vectorDim + ", got " + dim + " (doc_id=" + docId + ")");
            }

            float[] vec = new float[vectorDim];
            for (int i = 0; i < vectorDim; i++) {
              vec[i] = (float) vecNode.get(i).asDouble();
            }

            if (docId.equals(SENTINEL_DOC_ID)) {
              sentinelVector = vec;
            } else if (vectorsForQueries.size() < 24) { // Collect up to 24 sample vectors for query suite
              vectorsForQueries.add(vec);
            }

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put(SchemaFields.DOC_ID, docId);
            fields.put(SchemaFields.DOC_UID, docId + "#0");
            fields.put(SchemaFields.VECTOR, vec);
            fields.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
            fields.put(SchemaFields.MODIFIED_AT, System.currentTimeMillis());
            rows.add(fields);
          }
        }
        corpusReadEndNanos = System.nanoTime();
        docCount = rows.size();

        if (docCount <= 0) {
          throw new IllegalStateException("No vectors indexed from: " + vectors.toAbsolutePath());
        }
        if (sentinelVector == null) {
          throw new IllegalStateException("Sentinel doc_id '" + SENTINEL_DOC_ID + "' not found in: " + vectors.toAbsolutePath());
        }

        benchStartNanos = corpusReadEndNanos;

        List<IndexDocument> batch = new ArrayList<>(batchSize);
        for (var fields : rows) {
          batch.add(new IndexDocument(fields));
          if (batch.size() >= batchSize) {
            long bs = System.nanoTime();
            runtime.indexingCoordinator().indexBatch(batch);
            indexBatchCallNanos += System.nanoTime() - bs;
            batch.clear();
          }
        }
        if (!batch.isEmpty()) {
          long bs = System.nanoTime();
          runtime.indexingCoordinator().indexBatch(batch);
          indexBatchCallNanos += System.nanoTime() - bs;
          batch.clear();
        }

        // Stable query set: first N doc vectors in read order (sentinel added separately).
        sampleQueryVectors = new ArrayList<>(vectorsForQueries.size());
        sampleQueryVectors.addAll(vectorsForQueries);
      } else {
        if (timerStart == TimerStart.AFTER_RUNTIME_START) {
          benchStartNanos = runtimeStartEndNanos;
        }

        List<IndexDocument> batch = new ArrayList<>(batchSize);

        log.info("Indexing vectors...");
        ingestStartNanos = System.nanoTime();
        corpusReadStartNanos = ingestStartNanos;
        if (timerStart == TimerStart.BEFORE_CORPUS_READ) {
          benchStartNanos = ingestStartNanos;
        }

        try (BufferedReader reader = Files.newBufferedReader(vectors, StandardCharsets.UTF_8)) {
          String line;
          while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode node = MAPPER.readTree(line);
            String docId = node.path("doc_id").asText("");
            if (docId.isBlank()) continue;
            JsonNode vecNode = node.path("vector");
            if (!vecNode.isArray() || vecNode.isEmpty()) continue;

            int dim = vecNode.size();
            if (vectorDim == 0) {
              vectorDim = dim;
            } else if (dim != vectorDim) {
              throw new IllegalArgumentException(
                  "Vector dimension drift: expected " + vectorDim + ", got " + dim + " (doc_id=" + docId + ")");
            }

            float[] vec = new float[vectorDim];
            for (int i = 0; i < vectorDim; i++) {
              vec[i] = (float) vecNode.get(i).asDouble();
            }

            if (docId.equals(SENTINEL_DOC_ID)) {
              sentinelVector = vec;
            } else if (sampleQueryVectors.size() < 24) { // Collect up to 24 sample vectors for query suite
              sampleQueryVectors.add(vec);
            }

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put(SchemaFields.DOC_ID, docId);
            fields.put(SchemaFields.DOC_UID, docId + "#0");
            fields.put(SchemaFields.VECTOR, vec);
            fields.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
            fields.put(SchemaFields.MODIFIED_AT, System.currentTimeMillis());

            batch.add(new IndexDocument(fields));
            docCount++;

            if (batch.size() >= batchSize) {
              long bs = System.nanoTime();
              runtime.indexingCoordinator().indexBatch(batch);
              indexBatchCallNanos += System.nanoTime() - bs;
              batch.clear();
            }
          }
        }

        if (!batch.isEmpty()) {
          long bs = System.nanoTime();
          runtime.indexingCoordinator().indexBatch(batch);
          indexBatchCallNanos += System.nanoTime() - bs;
          batch.clear();
        }

        ingestEndNanos = System.nanoTime();
        corpusReadEndNanos = ingestEndNanos;
      }

      if (docCount <= 0) {
        throw new IllegalStateException("No vectors indexed from: " + vectors.toAbsolutePath());
      }
      if (sentinelVector == null) {
        throw new IllegalStateException("Sentinel doc_id '" + SENTINEL_DOC_ID + "' not found in: " + vectors.toAbsolutePath());
      }

      log.info("Committing...");
      commitStartNanos = System.nanoTime();
      runtime.commitOps().commitAndTrack();
      commitEndNanos = System.nanoTime();

      refreshStartNanos = System.nanoTime();
      runtime.commitOps().maybeRefreshBlocking();
      refreshEndNanos = System.nanoTime();

      log.info("Validating sentinel via vector search...");
      sentinelQueryStartNanos = System.nanoTime();
      int k = 10;
      // IMPORTANT: this validation must be robust across ANN sweeps.
      //
      // We intentionally validate using a fixed k=10 KnnFloatVectorQuery (not searchVector),
      // so time-to-searchable is not confounded by query-time oversampling knobs (index.vector.ef_search).
      var sr = runtime.readPathOps().search(new KnnFloatVectorQuery(SchemaFields.VECTOR, sentinelVector, k), k, null, io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE, null);
      sentinelQueryEndNanos = System.nanoTime();
      sentinelValidated =
          sr != null
              && sr.hits() != null
              && sr.hits().stream()
                  .anyMatch(
                      h ->
                          SENTINEL_DOC_ID.equals(h.docId())
                              || SENTINEL_DOC_ID.equals(h.fields().get(SchemaFields.DOC_ID)));
      String topDocId = null;
      try {
        if (sr != null && sr.hits() != null && !sr.hits().isEmpty()) {
          topDocId = sr.hits().getFirst().docId();
        }
      } catch (@SuppressWarnings("EmptyCatch") Exception ignored) {
      }
      log.info(
          "Sentinel validation: {} (k={}, hits={}, topHit={})",
          sentinelValidated ? "PASS" : "FAIL",
          k,
          sr == null || sr.hits() == null ? 0 : sr.hits().size(),
          topDocId);

      // Capture heap and RSS after indexing (peak estimate)
      BenchmarkUtils.HeapSnapshot heapAfterIndex = BenchmarkUtils.HeapSnapshot.capture();
      BenchmarkUtils.RssSnapshot rssAfterIndex = BenchmarkUtils.RssSnapshot.capture();

      // Capture heap and RSS after explicit GC (steady-state)
      System.gc();
      BenchmarkUtils.HeapSnapshot heapAfterGc = BenchmarkUtils.HeapSnapshot.capture();
      BenchmarkUtils.RssSnapshot rssAfterGc = BenchmarkUtils.RssSnapshot.capture();

      long endToSearchableNanos = System.nanoTime();
      long elapsedMs = (endToSearchableNanos - benchStartNanos) / 1_000_000;

      // Optional query latency suite (vector search only; uses vectors from the corpus).
      long querySuiteStartNanos = 0;
      long querySuiteEndNanos = 0;
      int queryCountEffective = 0;
      double queryP50Ms = 0.0;
      double queryP95Ms = 0.0;
      double recallAtK = 0.0;
      int recallQueryCount = 0;

      if (queryLimit > 0 && queryCountLimit > 0) {
        List<QueryVector> queries = new ArrayList<>();
        Map<String, java.util.Set<String>> truth = new LinkedHashMap<>();

        if (truthKnnPath != null && !truthKnnPath.isBlank()) {
          Path tk = Paths.get(truthKnnPath);
          if (!Files.exists(tk)) {
            throw new IllegalArgumentException("Truth KNN file not found: " + tk.toAbsolutePath());
          }
          try (BufferedReader reader = Files.newBufferedReader(tk, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
              if (line.isBlank()) continue;
              JsonNode node = MAPPER.readTree(line);
              String qid = node.path("query_id").asText("");
              String did = node.path("doc_id").asText("");
              int rank = node.path("rank").asInt(0);
              if (qid.isBlank() || did.isBlank()) continue;
              if (rank > 0 && rank > queryLimit) continue;
              truth.computeIfAbsent(qid, key -> new java.util.LinkedHashSet<>()).add(did);
            }
          }
          if (!truth.isEmpty()) {
            log.info("Loaded truth KNN: {} queries", truth.size());
          }
        }

        if (queriesVectorsPath != null && !queriesVectorsPath.isBlank()) {
          Path qvPath = Paths.get(queriesVectorsPath);
          if (!Files.exists(qvPath)) {
            throw new IllegalArgumentException("Query vectors file not found: " + qvPath.toAbsolutePath());
          }
          try (BufferedReader reader = Files.newBufferedReader(qvPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
              if (queries.size() >= queryCountLimit) break;
              if (line.isBlank()) continue;
              JsonNode node = MAPPER.readTree(line);
              String qid = node.path("query_id").asText("");
              JsonNode vecNode = node.path("vector");
              if (!vecNode.isArray() || vecNode.isEmpty()) continue;

              int dim = vecNode.size();
              if (dim != vectorDim) {
                throw new IllegalArgumentException(
                    "Query vector dimension mismatch: expected " + vectorDim + ", got " + dim + " (file=" + qvPath.toAbsolutePath() + ")");
              }
              float[] qv = new float[vectorDim];
              for (int i = 0; i < vectorDim; i++) {
                qv[i] = (float) vecNode.get(i).asDouble();
              }
              queries.add(new QueryVector(qid.isBlank() ? null : qid, qv));
            }
          }
        } else {
          for (float[] v : sampleQueryVectors) {
            if (queries.size() >= queryCountLimit) break;
            if (v != null) queries.add(new QueryVector(null, v));
          }
        }

        if (!queries.isEmpty()) {
          log.info("Running query latency suite ({} queries, limit={})...", queries.size(), queryLimit);

          // Warmup once (best-effort).
          for (QueryVector qv : queries) {
            runtime.readPathOps().searchVector(qv.vector(), queryLimit);
          }

          List<Double> latenciesMs = new ArrayList<>(queries.size());
          querySuiteStartNanos = System.nanoTime();
          for (QueryVector qv : queries) {
            long qs = System.nanoTime();
            var qsr = runtime.readPathOps().searchVector(qv.vector(), queryLimit);
            long qe = System.nanoTime();
            latenciesMs.add((qe - qs) / 1_000_000.0);

            // Recall is computed outside the latency timing (qe captured before this work).
            if (qv.queryId() != null && !truth.isEmpty()) {
              var truthSet = truth.get(qv.queryId());
              if (truthSet != null && !truthSet.isEmpty() && qsr != null && qsr.hits() != null) {
                int inter = 0;
                for (var h : qsr.hits()) {
                  String docId = null;
                  try {
                    Object v = h.fields().get(SchemaFields.DOC_ID);
                    if (v != null) docId = String.valueOf(v);
                  } catch (Exception ignored) {
                    // best-effort
                  }
                  if (docId == null || docId.isBlank()) {
                    try {
                      docId = h.docId();
                    } catch (Exception ignored) {
                      // best-effort
                    }
                  }
                  if (docId != null && truthSet.contains(docId)) {
                    inter++;
                  }
                }
                recallAtK += ((double) inter) / truthSet.size();
                recallQueryCount += 1;
              }
            }
          }
          querySuiteEndNanos = System.nanoTime();

          queryCountEffective = latenciesMs.size();
          queryP50Ms = BenchmarkUtils.percentile(latenciesMs, 0.50);
          queryP95Ms = BenchmarkUtils.percentile(latenciesMs, 0.95);
          if (recallQueryCount > 0) {
            recallAtK = recallAtK / recallQueryCount;
          }
          log.info(
              "Query latency: p50={} ms, p95={} ms", BenchmarkUtils.round3(queryP50Ms), BenchmarkUtils.round3(queryP95Ms));
          if (recallQueryCount > 0) {
            log.info("Recall@{}: {}", queryLimit, Math.round(recallAtK * 10000.0) / 10000.0);
          }
        }
      }

      long vectorBytesTotal = (long) docCount * vectorDim * 4L;
      double elapsedSecs = elapsedMs / 1000.0;
      double docsPerSec = elapsedSecs > 0 ? docCount / elapsedSecs : 0;
      double mbPerSec = elapsedSecs > 0 ? (vectorBytesTotal / (1024.0 * 1024.0)) / elapsedSecs : 0;

      long indexSizeBytes = 0;
      try {
        indexSizeBytes = BenchmarkUtils.directorySizeBytes(indexPath);
      } catch (Exception e) {
        // best-effort
      }

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("schema_version", 1);
      result.put("lane", "V");

      Map<String, Object> tool = new LinkedHashMap<>();
      tool.put("id", "justsearch");
      tool.put("version", BenchmarkUtils.shortSha(BenchmarkUtils.getGitSha()));
      tool.put("notes", "Engine-only Lucene vector indexing (pre-embedded corpus)");
      result.put("tool", tool);

      result.put("corpus_id", corpusId);

      Map<String, Object> corpusStats = new LinkedHashMap<>();
      corpusStats.put("doc_count", docCount);
      corpusStats.put("bytes_total", vectorBytesTotal);
      corpusStats.put("sentinel_doc_id", SENTINEL_DOC_ID);
      result.put("corpus_stats", corpusStats);

      Map<String, Object> metrics = new LinkedHashMap<>();
      metrics.put("time_to_searchable_ms", elapsedMs);
      metrics.put("docs_per_s", BenchmarkUtils.round2(docsPerSec));
      metrics.put("mb_per_s", BenchmarkUtils.round3(mbPerSec));
      metrics.put("index_size_bytes", indexSizeBytes);
      metrics.put("vector_dim", vectorDim);
      metrics.put("vector_bytes_total", vectorBytesTotal);
      if (queryCountEffective > 0) {
        metrics.put("query_count", queryCountEffective);
        metrics.put("query_p50_ms", BenchmarkUtils.round3(queryP50Ms));
        metrics.put("query_p95_ms", BenchmarkUtils.round3(queryP95Ms));
      }
      if (recallQueryCount > 0) {
        metrics.put("recall_k", queryLimit);
        metrics.put("recall_at_k", Math.round(recallAtK * 10000.0) / 10000.0);
        metrics.put("recall_query_count", recallQueryCount);
      }
      result.put("metrics", metrics);

      result.put("sentinel_validated", sentinelValidated);
      result.put("captured_at", Instant.now().toString());

      String gitSha = BenchmarkUtils.getGitSha();
      if (gitSha != null) {
        result.put("git_sha", gitSha);
      }

      result.put("machine_fingerprint", MachineFingerprint.capture().toMap());

      // Memory stats: heap (JVM only) and RSS (full process including mmap)
      Map<String, Object> memoryStats = new LinkedHashMap<>();
      memoryStats.put("heap_before", heapBefore.toMap());
      memoryStats.put("heap_after_index", heapAfterIndex.toMap());
      memoryStats.put("heap_after_gc", heapAfterGc.toMap());
      // RSS metrics (includes mmap'd Lucene segments)
      if (rssBefore.isAvailable()) {
        memoryStats.put("rss_before", rssBefore.toMap());
        memoryStats.put("rss_after_index", rssAfterIndex.toMap());
        memoryStats.put("rss_after_gc", rssAfterGc.toMap());
      }
      memoryStats.put("note", "heap=JVM only; rss=full process memory including mmap");
      result.put("memory_stats", memoryStats);

      Map<String, Object> timings = new LinkedHashMap<>();
      timings.put("runtime_start_ms", (runtimeStartEndNanos - runtimeStartBeginNanos) / 1_000_000);
      if (timerStart == TimerStart.AFTER_CORPUS_READ) {
        if (corpusReadEndNanos > corpusReadStartNanos) {
          timings.put("corpus_read_ms", (corpusReadEndNanos - corpusReadStartNanos) / 1_000_000);
        }
      } else {
        if (ingestEndNanos > ingestStartNanos && ingestStartNanos > 0) {
          long parseNanos = (ingestEndNanos - ingestStartNanos) - indexBatchCallNanos;
          if (parseNanos < 0) {
            parseNanos = 0;
          }
          timings.put("corpus_read_ms", parseNanos / 1_000_000);
        }
      }
      if (indexBatchCallNanos > 0) {
        timings.put("index_batch_ms", indexBatchCallNanos / 1_000_000);
      }
      if (commitEndNanos > commitStartNanos) {
        timings.put("commit_ms", (commitEndNanos - commitStartNanos) / 1_000_000);
      }
      if (refreshEndNanos > refreshStartNanos) {
        timings.put("refresh_ms", (refreshEndNanos - refreshStartNanos) / 1_000_000);
      }
      if (sentinelQueryEndNanos > sentinelQueryStartNanos) {
        timings.put("sentinel_query_ms", (sentinelQueryEndNanos - sentinelQueryStartNanos) / 1_000_000);
      }
      if (querySuiteEndNanos > querySuiteStartNanos && querySuiteStartNanos > 0) {
        timings.put("query_suite_ms", (querySuiteEndNanos - querySuiteStartNanos) / 1_000_000);
      }
      result.put("timings_ms", timings);

      String timingContract =
          switch (timerStart) {
            case AFTER_CORPUS_READ ->
                "time_to_searchable_ms includes indexing + commit + refresh + sentinel vector search (corpus read excluded; runtime start excluded)";
            case AFTER_RUNTIME_START ->
                "time_to_searchable_ms includes corpus read + indexing + commit + refresh + sentinel vector search (runtime start excluded)";
            case BEFORE_CORPUS_READ ->
                "time_to_searchable_ms includes corpus read + indexing + commit + refresh + sentinel vector search (runtime start excluded)";
          };
      result.put("timing_contract", timingContract);

      Map<String, Object> knobs = new LinkedHashMap<>();
      knobs.put("batch_size", batchSize);
      knobs.put("timer_start", timerStart.name().toLowerCase(Locale.ROOT));
      knobs.put("vectors_path", vectors.toAbsolutePath().toString());
      knobs.put("query_limit", queryLimit);
      knobs.put("ann_hnsw_m", rcIdx != null && rcIdx.vectorHnswM() != null ? rcIdx.vectorHnswM() : 16);
      knobs.put("ann_ef_construction", rcIdx != null && rcIdx.vectorHnswEfConstruction() != null ? rcIdx.vectorHnswEfConstruction() : 200);
      knobs.put("ann_ef_search_or_null", rcIdx != null ? rcIdx.vectorEfSearch() : null);
      knobs.put("ann_quantization_enabled", rcIdx != null && rcIdx.vectorQuantizationEnabled() != null ? rcIdx.vectorQuantizationEnabled() : false);
      if (queriesVectorsPath != null && !queriesVectorsPath.isBlank()) {
        knobs.put("queries_vectors_path", Paths.get(queriesVectorsPath).toAbsolutePath().toString());
      }
      if (truthKnnPath != null && !truthKnnPath.isBlank()) {
        knobs.put("truth_knn_path", Paths.get(truthKnnPath).toAbsolutePath().toString());
      }
      result.put("knobs", knobs);

      Path resultPath = outPath.resolve("result.json");
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(resultPath.toFile(), result);
      log.info("Wrote result to: {}", resultPath);

      log.info("=== Benchmark Complete ===");
      log.info("  Docs:         {}", docCount);
      log.info("  Dim:          {}", vectorDim);
      log.info("  Time:         {} ms", elapsedMs);
      log.info("  Throughput:   {:.2f} docs/s, {:.3f} MB/s", docsPerSec, mbPerSec);
      log.info("  Index bytes:  {}", indexSizeBytes);
      log.info("  Sentinel OK:  {}", sentinelValidated);
    } finally {
      runtime.close();
      BenchmarkUtils.deleteRecursively(indexPath);
    }
  }
}
