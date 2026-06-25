/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.benchmarks;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.benchmarks.util.BenchmarkUtils;
import io.justsearch.benchmarks.util.MachineFingerprint;
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
import java.util.Map;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.MMapDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Raw Lucene indexing benchmark — no JustSearch overhead.
 *
 * <p>Measures the bare Lucene ceiling: StandardAnalyzer, no field catalog validation, no commit
 * metadata, no soft deletes, no DocValues, no per-field analyzers. Just IndexWriter + Document +
 * TextField.
 *
 * <p>Compare with {@link EngineIndexBench} (Claim A) to measure the cost of JustSearch's schema
 * and metadata layer.
 */
public final class RawLuceneIndexBench {
  private static final Logger log = LoggerFactory.getLogger(RawLuceneIndexBench.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SENTINEL_DOC_ID = "__bench_sentinel__";

  @SuppressWarnings("PMD.SystemPrintln")
  public static void main(String[] args) throws Exception {
    String corpusPath = null;
    String outDir = "tmp/bench/raw-lucene";

    for (String arg : args) {
      if (arg.startsWith("--corpus=")) {
        corpusPath = arg.substring("--corpus=".length());
      } else if (arg.startsWith("--out-dir=")) {
        outDir = arg.substring("--out-dir=".length());
      }
    }

    if (corpusPath == null || corpusPath.isBlank()) {
      System.err.println("Usage: RawLuceneIndexBench --corpus=<ndjson> [--out-dir=<dir>]");
      System.exit(1);
    }

    Path corpus = Paths.get(corpusPath);
    if (!Files.exists(corpus)) {
      System.err.println("Corpus file not found: " + corpusPath);
      System.exit(1);
    }

    Path outPath = Paths.get(outDir);
    Files.createDirectories(outPath);

    log.info("Starting raw Lucene indexing benchmark (no JustSearch overhead)");
    log.info("  Corpus: {}", corpus.toAbsolutePath());

    // Read corpus
    List<String[]> docs = new ArrayList<>(); // [docId, title, body]
    long bytesTotal = 0;

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
        docs.add(new String[] {docId, title, body});
        bytesTotal += body.getBytes(StandardCharsets.UTF_8).length;
      }
    }
    long corpusReadEndNanos = System.nanoTime();

    int docCount = docs.size();
    log.info("Read {} documents ({} bytes)", docCount, bytesTotal);

    // Create bare IndexWriter — no field catalog, no metadata, no soft deletes
    Path indexPath = Files.createTempDirectory("raw-lucene-bench-");
    MMapDirectory directory = new MMapDirectory(indexPath);
    IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
    config.setRAMBufferSizeMB(256.0);
    config.setUseCompoundFile(false);

    long writerOpenStartNanos = System.nanoTime();
    IndexWriter writer = new IndexWriter(directory, config);
    long writerOpenEndNanos = System.nanoTime();

    // Index all documents — bare Document with TextField, no validation
    log.info("Indexing {} documents (raw Lucene, StandardAnalyzer, no overhead)...", docCount);
    long indexStartNanos = System.nanoTime();
    for (String[] doc : docs) {
      Document luceneDoc = new Document();
      luceneDoc.add(new StringField("doc_id", doc[0], Field.Store.YES));
      luceneDoc.add(new TextField("title", doc[1], Field.Store.YES));
      luceneDoc.add(new TextField("content", doc[2], Field.Store.NO));
      writer.addDocument(luceneDoc);
    }
    long indexEndNanos = System.nanoTime();

    // Commit
    long commitStartNanos = System.nanoTime();
    writer.commit();
    long commitEndNanos = System.nanoTime();

    // Refresh + sentinel validation
    long refreshStartNanos = System.nanoTime();
    DirectoryReader reader = DirectoryReader.open(writer);
    long refreshEndNanos = System.nanoTime();

    long sentinelStartNanos = System.nanoTime();
    IndexSearcher searcher = new IndexSearcher(reader);
    var topDocs = searcher.search(new TermQuery(new Term("doc_id", SENTINEL_DOC_ID)), 1);
    boolean sentinelValidated = topDocs.totalHits.value() > 0;
    long sentinelEndNanos = System.nanoTime();

    long totalEndNanos = System.nanoTime();
    long totalMs = (totalEndNanos - writerOpenEndNanos) / 1_000_000;

    // Cleanup
    reader.close();
    writer.close();
    directory.close();

    // Calculate metrics
    double totalSecs = totalMs / 1000.0;
    double docsPerSec = totalSecs > 0 ? docCount / totalSecs : 0;
    double mbPerSec = totalSecs > 0 ? (bytesTotal / (1024.0 * 1024.0)) / totalSecs : 0;
    long indexBatchMs = (indexEndNanos - indexStartNanos) / 1_000_000;
    double batchDocsPerSec = indexBatchMs > 0 ? (docCount * 1000.0) / indexBatchMs : 0;

    long indexSizeBytes = 0;
    try {
      indexSizeBytes = BenchmarkUtils.directorySizeBytes(indexPath);
    } catch (Exception e) {
      // best-effort
    }

    log.info("=== Raw Lucene Benchmark Complete ===");
    log.info("  Documents:       {}", docCount);
    log.info("  Total time:      {} ms", totalMs);
    log.info("  Index batch:     {} ms ({} docs/sec)", indexBatchMs, Math.round(batchDocsPerSec));
    log.info("  Commit:          {} ms", (commitEndNanos - commitStartNanos) / 1_000_000);
    log.info("  Overall:         {} docs/sec", Math.round(docsPerSec));
    log.info("  Sentinel:        {}", sentinelValidated ? "PASS" : "FAIL");

    // Build result JSON
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schema_version", 1);
    result.put("claim", "A-raw");
    result.put("mode", "raw_lucene");
    result.put("corpus_id", corpus.getFileName().toString().replace(".ndjson", ""));

    Map<String, Object> corpusStats = new LinkedHashMap<>();
    corpusStats.put("doc_count", docCount);
    corpusStats.put("bytes_total", bytesTotal);
    result.put("corpus_stats", corpusStats);

    result.put("time_to_searchable_ms", totalMs);
    result.put("docs_per_s", Math.round(docsPerSec * 100) / 100.0);
    result.put("mb_per_s", Math.round(mbPerSec * 1000) / 1000.0);
    result.put("batch_docs_per_s", Math.round(batchDocsPerSec * 100) / 100.0);
    result.put("index_size_bytes", indexSizeBytes);
    result.put("sentinel_validated", sentinelValidated);
    result.put("captured_at", Instant.now().toString());

    Map<String, Object> timings = new LinkedHashMap<>();
    timings.put("corpus_read_ms", (corpusReadEndNanos - corpusReadStartNanos) / 1_000_000);
    timings.put("writer_open_ms", (writerOpenEndNanos - writerOpenStartNanos) / 1_000_000);
    timings.put("index_batch_ms", indexBatchMs);
    timings.put("commit_ms", (commitEndNanos - commitStartNanos) / 1_000_000);
    timings.put("refresh_ms", (refreshEndNanos - refreshStartNanos) / 1_000_000);
    timings.put("sentinel_query_ms", (sentinelEndNanos - sentinelStartNanos) / 1_000_000);
    result.put("timings_ms", timings);

    String gitSha = BenchmarkUtils.getGitSha();
    if (gitSha != null) result.put("git_sha", gitSha);
    result.put("machine_fingerprint", MachineFingerprint.capture().toMap());

    result.put("knobs", Map.of(
        "analyzer", "StandardAnalyzer",
        "ram_buffer_mb", 256,
        "compound_file", false,
        "fields", "doc_id(StringField) + title(TextField) + content(TextField)",
        "no_docvalues", true,
        "no_soft_deletes", true,
        "no_commit_metadata", true,
        "no_field_catalog_validation", true
    ));

    Path resultFile = outPath.resolve("result.json");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(resultFile.toFile(), result);
    log.info("Wrote result to: {}", resultFile);

    // Cleanup temp index
    try (var stream = Files.walk(indexPath)) {
      stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
      });
    }
  }
}
