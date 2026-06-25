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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Claim A engine-only indexing benchmark.
 *
 * <p>Measures JustSearch Lucene runtime throughput without pipeline overhead (no watched roots, job
 * queue, breath-holding, or content extraction).
 *
 * <p>Usage: java EngineIndexBench --corpus=path/to/docs.ndjson --out-dir=tmp/bench/claim-a
 */
@SuppressWarnings("PMD.UnusedAssignment") // Intentional timing variable assignments for benchmarking
public final class EngineIndexBench {
  private static final Logger log = LoggerFactory.getLogger(EngineIndexBench.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SENTINEL_DOC_ID = "__bench_sentinel__";

  private enum TimerStart {
    BEFORE_CORPUS_READ,
    AFTER_CORPUS_READ,
    AFTER_RUNTIME_START
  }

  @SuppressWarnings("PMD.SystemPrintln")
  public static void main(String[] args) throws Exception {
    // Parse arguments
    String corpusPath = null;
    String outDir = "tmp/bench/claim-a";
    String timerStartRaw = "after_runtime_start";
    String queriesPath = null;
    int queryLimit = 10;
    int batchSize = 0;

    for (String arg : args) {
      if (arg.startsWith("--corpus=")) {
        corpusPath = arg.substring("--corpus=".length());
      } else if (arg.startsWith("--out-dir=")) {
        outDir = arg.substring("--out-dir=".length());
      } else if (arg.startsWith("--timer-start=")) {
        timerStartRaw = arg.substring("--timer-start=".length());
      } else if (arg.startsWith("--queries=")) {
        queriesPath = arg.substring("--queries=".length());
      } else if (arg.startsWith("--query-limit=")) {
        try {
          queryLimit = Integer.parseInt(arg.substring("--query-limit=".length()));
        } catch (Exception ignored) {
          // keep default
        }
      } else if (arg.startsWith("--batch-size=")) {
        try {
          batchSize = Integer.parseInt(arg.substring("--batch-size=".length()));
        } catch (Exception ignored) {
          // keep default
        }
      }
    }

    if (corpusPath == null || corpusPath.isBlank()) {
      System.err.println("Usage: EngineIndexBench --corpus=<ndjson> [--out-dir=<dir>]");
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

    Path corpus = Paths.get(corpusPath);
    if (!Files.exists(corpus)) {
      System.err.println("Corpus file not found: " + corpusPath);
      System.exit(1);
    }

    Path outPath = Paths.get(outDir);
    Files.createDirectories(outPath);

    log.info("Starting Claim A engine-only benchmark");
    log.info("  Corpus: {}", corpus.toAbsolutePath());
    log.info("  Output: {}", outPath.toAbsolutePath());
    log.info("  Timer:  {}", timerStart);

    // Load field catalog from SSOT
    JustSearchConfigurationLoader loader = new JustSearchConfigurationLoader();
    FieldCatalogDef catalog = loader.loadFieldCatalog();
    log.info("Loaded field catalog version: {}", catalog.version());

    // Create temporary index directory
    Path indexPath = Files.createTempDirectory("bench-index-");
    log.info("Using temp index path: {}", indexPath);

    // Capture heap and RSS baseline before any heavy work
    BenchmarkUtils.HeapSnapshot heapBefore = BenchmarkUtils.HeapSnapshot.capture();
    BenchmarkUtils.RssSnapshot rssBefore = BenchmarkUtils.RssSnapshot.capture();

    // Read corpus and prepare documents
    List<IndexDocument> documents = new ArrayList<>();
    long bytesTotal = 0;
    int docCount = 0;

    long corpusReadStartNanos = System.nanoTime();
    try (BufferedReader reader = Files.newBufferedReader(corpus)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) continue;

        JsonNode node = MAPPER.readTree(line);
        String docId = node.path("doc_id").asText("");
        String title = node.path("title").asText("");
        String body = node.path("body").asText("");

        if (docId.isBlank() || body.isBlank()) continue;

        Map<String, Object> fields = new HashMap<>();
        fields.put(SchemaFields.DOC_ID, docId);
        fields.put(SchemaFields.DOC_UID, docId + "#0");
        fields.put(SchemaFields.TITLE, title);
        fields.put(SchemaFields.CONTENT, body);
        fields.put(SchemaFields.CONTENT_ALL, title + " " + body);
        fields.put(SchemaFields.MODIFIED_AT, System.currentTimeMillis());

        documents.add(new IndexDocument(fields));
        bytesTotal += body.getBytes(StandardCharsets.UTF_8).length;
        docCount++;
      }
    }
    long corpusReadEndNanos = System.nanoTime();

    log.info("Read {} documents from corpus ({} bytes)", docCount, bytesTotal);
    int effectiveBatchSize = batchSize > 0 ? batchSize : documents.size();
    if (effectiveBatchSize <= 0) effectiveBatchSize = 1;
    log.info("Index batch size: {}", effectiveBatchSize);

    // Create and start runtime
    // Phase 2-3 Step E: builder.open() collapses construct + start into a single call.
    // Boot timing now measures construct-and-start as one window.
    long runtimeStartBeginNanos = System.nanoTime();
    io.justsearch.adapters.lucene.runtime.RunningRuntime runtime =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(catalog).atPath(indexPath).open();
    long runtimeStartEndNanos = System.nanoTime();

    long benchStartNanos =
        switch (timerStart) {
          case BEFORE_CORPUS_READ -> corpusReadStartNanos;
          case AFTER_CORPUS_READ -> corpusReadEndNanos;
          case AFTER_RUNTIME_START -> runtimeStartEndNanos;
        };

    boolean sentinelValidated = false;
    long indexBatchStartNanos = 0;
    long indexBatchEndNanos = 0;
    long commitStartNanos = 0;
    long commitEndNanos = 0;
    long refreshStartNanos = 0;
    long refreshEndNanos = 0;
    long sentinelQueryStartNanos = 0;
    long sentinelQueryEndNanos = 0;
    long querySuiteStartNanos = 0;
    long querySuiteEndNanos = 0;

    double queryP50Ms = 0.0;
    double queryP95Ms = 0.0;
    int queryCount = 0;

    try {
      // Index documents in batches (default: one batch, unless overridden).
      log.info("Indexing {} documents...", documents.size());
      indexBatchStartNanos = System.nanoTime();
      for (int i = 0; i < documents.size(); i += effectiveBatchSize) {
        int end = Math.min(i + effectiveBatchSize, documents.size());
        runtime.indexingCoordinator().indexBatch(documents.subList(i, end));
      }
      indexBatchEndNanos = System.nanoTime();

      // Commit
      log.info("Committing...");
      commitStartNanos = System.nanoTime();
      runtime.commitOps().commitAndTrack();
      commitEndNanos = System.nanoTime();

      // Ensure searchability before stopping the clock.
      // maybeRefresh() is best-effort; for the Lucene runtime we can block to ensure the sentinel is visible.
      refreshStartNanos = System.nanoTime();
      runtime.commitOps().maybeRefreshBlocking();
      refreshEndNanos = System.nanoTime();

      // Validate sentinel document is searchable
      log.info("Validating sentinel document searchability...");
      sentinelQueryStartNanos = System.nanoTime();
      var searchResult = runtime.textQueryOps().searchText(SENTINEL_DOC_ID, 1, null);
      sentinelQueryEndNanos = System.nanoTime();
      sentinelValidated = searchResult.totalHits() > 0;
      log.info(
          "Sentinel validation: {} (hits={})", sentinelValidated ? "PASS" : "FAIL", searchResult.totalHits());

      // Capture heap and RSS after indexing (peak estimate)
      BenchmarkUtils.HeapSnapshot heapAfterIndex = BenchmarkUtils.HeapSnapshot.capture();
      BenchmarkUtils.RssSnapshot rssAfterIndex = BenchmarkUtils.RssSnapshot.capture();

      // Capture heap and RSS after explicit GC (steady-state)
      System.gc();
      BenchmarkUtils.HeapSnapshot heapAfterGc = BenchmarkUtils.HeapSnapshot.capture();
      BenchmarkUtils.RssSnapshot rssAfterGc = BenchmarkUtils.RssSnapshot.capture();

      long endNanos = System.nanoTime();
      long elapsedMs = (endNanos - benchStartNanos) / 1_000_000;

      log.info("Indexing complete (sentinel checked) in {} ms", elapsedMs);

      // Optional: run a small fixed query set and record p50/p95 latency.
      if (queriesPath != null && !queriesPath.isBlank()) {
        Path qPath = Paths.get(queriesPath);
        if (Files.exists(qPath)) {
          List<String> queries =
              Files.readAllLines(qPath, StandardCharsets.UTF_8).stream()
                  .map(String::trim)
                  .filter(s -> !s.isEmpty())
                  .collect(Collectors.toList());

          if (!queries.isEmpty()) {
            log.info("Running query latency suite ({} queries, limit={})...", queries.size(), queryLimit);
            // Warmup once (best-effort).
            for (String q : queries) {
              runtime.textQueryOps().searchText(q, queryLimit, null);
            }

            querySuiteStartNanos = System.nanoTime();
            List<Double> latenciesMs = new ArrayList<>(queries.size());
            for (String q : queries) {
              long qs = System.nanoTime();
              runtime.textQueryOps().searchText(q, queryLimit, null);
              long qe = System.nanoTime();
              latenciesMs.add((qe - qs) / 1_000_000.0);
            }
            querySuiteEndNanos = System.nanoTime();

            queryCount = latenciesMs.size();
            queryP50Ms = BenchmarkUtils.percentile(latenciesMs, 0.50);
            queryP95Ms = BenchmarkUtils.percentile(latenciesMs, 0.95);
            log.info("Query latency: p50={} ms, p95={} ms", BenchmarkUtils.round3(queryP50Ms), BenchmarkUtils.round3(queryP95Ms));
          }
        } else {
          log.warn("Queries file not found: {}", qPath.toAbsolutePath());
        }
      }

      // Calculate metrics
      double elapsedSecs = elapsedMs / 1000.0;
      double docsPerSec = elapsedSecs > 0 ? docCount / elapsedSecs : 0;
      double mbPerSec = elapsedSecs > 0 ? (bytesTotal / (1024.0 * 1024.0)) / elapsedSecs : 0;

      long indexSizeBytes = 0;
      try {
        indexSizeBytes = BenchmarkUtils.directorySizeBytes(indexPath);
      } catch (Exception e) {
        // best-effort
      }

      // Build result
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("schema_version", 1);
      result.put("claim", "A");
      result.put("mode", "0"); // lexical-only for now
      result.put("corpus_id", corpus.getFileName().toString().replace(".ndjson", ""));

      Map<String, Object> corpusStats = new LinkedHashMap<>();
      corpusStats.put("doc_count", docCount);
      corpusStats.put("bytes_total", bytesTotal);
      corpusStats.put("sentinel_doc_id", SENTINEL_DOC_ID);
      result.put("corpus_stats", corpusStats);

      result.put("time_to_searchable_ms", elapsedMs);
      result.put("docs_per_s", Math.round(docsPerSec * 100) / 100.0);
      result.put("mb_per_s", Math.round(mbPerSec * 1000) / 1000.0);
      result.put("index_size_bytes", indexSizeBytes);
      result.put("sentinel_validated", sentinelValidated);
      if (queryCount > 0) {
        result.put("query_count", queryCount);
        result.put("query_p50_ms", BenchmarkUtils.round3(queryP50Ms));
        result.put("query_p95_ms", BenchmarkUtils.round3(queryP95Ms));
      }
      result.put("captured_at", Instant.now().toString());

      Map<String, Object> timings = new LinkedHashMap<>();
      timings.put("corpus_read_ms", (corpusReadEndNanos - corpusReadStartNanos) / 1_000_000);
      timings.put("runtime_start_ms", (runtimeStartEndNanos - runtimeStartBeginNanos) / 1_000_000);
      if (indexBatchEndNanos > indexBatchStartNanos) {
        timings.put("index_batch_ms", (indexBatchEndNanos - indexBatchStartNanos) / 1_000_000);
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

      // Git SHA (best-effort)
      String gitSha = BenchmarkUtils.getGitSha();
      if (gitSha != null) {
        result.put("git_sha", gitSha);
      }

      // Machine fingerprint
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

      // Knobs
      Map<String, Object> knobs = new LinkedHashMap<>();
      knobs.put("parallelism", 1); // single-threaded batch
      knobs.put("batch_size", effectiveBatchSize);
      knobs.put("timer_start", timerStart.name().toLowerCase(Locale.ROOT));
      if (queriesPath != null && !queriesPath.isBlank()) {
        knobs.put("queries_path", queriesPath);
        knobs.put("query_limit", queryLimit);
      }
      result.put("knobs", knobs);

      // Write result
      Path resultPath = outPath.resolve("result.json");
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(resultPath.toFile(), result);
      log.info("Wrote result to: {}", resultPath);

      // Summary
      log.info("=== Benchmark Complete ===");
      log.info("  Documents:    {}", docCount);
      log.info("  Time:         {} ms", elapsedMs);
      log.info("  Throughput:   {:.2f} docs/s, {:.3f} MB/s", docsPerSec, mbPerSec);
      log.info("  Sentinel OK:  {}", sentinelValidated);

    } finally {
      runtime.close();
      // Clean up temp index
      BenchmarkUtils.deleteRecursively(indexPath);
    }
  }
}
