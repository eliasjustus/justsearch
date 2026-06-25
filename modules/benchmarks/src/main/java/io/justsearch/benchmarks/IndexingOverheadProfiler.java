/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.benchmarks;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import org.apache.lucene.document.Document;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.apache.lucene.index.Term;
import org.apache.lucene.store.MMapDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Profiles where per-document indexing time goes by measuring 4 phases separately:
 *
 * <ol>
 *   <li><b>Validation</b> — IndexingCoordinator.validate() per document
 *   <li><b>Field mapping</b> — FieldMapper.toDocument() (SSOT catalog → Lucene Document)
 *   <li><b>Lucene write</b> — IndexWriter.updateDocument() (the actual I/O)
 *   <li><b>Raw Lucene baseline</b> — bare Document + TextField, no validation or mapping
 * </ol>
 *
 * <p>Compares JustSearch overhead against raw Lucene to identify which phase dominates.
 */
public final class IndexingOverheadProfiler {
  private static final Logger log = LoggerFactory.getLogger(IndexingOverheadProfiler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @SuppressWarnings("PMD.SystemPrintln")
  public static void main(String[] args) throws Exception {
    String corpusPath = null;
    String outDir = "tmp/bench/overhead-profile";

    for (String arg : args) {
      if (arg.startsWith("--corpus=")) corpusPath = arg.substring("--corpus=".length());
      else if (arg.startsWith("--out-dir=")) outDir = arg.substring("--out-dir=".length());
    }

    if (corpusPath == null || corpusPath.isBlank()) {
      System.err.println("Usage: IndexingOverheadProfiler --corpus=<ndjson> [--out-dir=<dir>]");
      System.exit(1);
    }

    Path corpus = Paths.get(corpusPath);
    Path outPath = Paths.get(outDir);
    Files.createDirectories(outPath);

    log.info("=== Indexing Overhead Profiler ===");
    log.info("Corpus: {}", corpus.toAbsolutePath());

    // Read corpus
    List<Map<String, Object>> rawFields = new ArrayList<>();
    long bytesTotal = 0;
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
        rawFields.add(fields);
        bytesTotal += body.getBytes(StandardCharsets.UTF_8).length;
      }
    }
    int docCount = rawFields.size();
    log.info("Loaded {} documents ({} bytes)", docCount, bytesTotal);

    // Prepare IndexDocuments for validation
    List<IndexDocument> indexDocs = new ArrayList<>(docCount);
    for (Map<String, Object> f : rawFields) {
      indexDocs.add(new IndexDocument(f));
    }

    // Load field catalog
    JustSearchConfigurationLoader loader = new JustSearchConfigurationLoader();
    FieldCatalogDef catalog = loader.loadFieldCatalog();

    // Create and start lifecycle manager
    Path indexPath = Files.createTempDirectory("overhead-profile-");
    io.justsearch.adapters.lucene.runtime.RunningRuntime lifecycle =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(catalog)
            .atPath(indexPath)
            .open();

    // Warmup: index 100 docs to prime JIT
    int warmup = Math.min(100, docCount);
    for (int i = 0; i < warmup; i++) {
      lifecycle.indexingCoordinator().indexSingle(indexDocs.get(i));
    }
    lifecycle.commitOps().commitAndTrack();
    log.info("Warmup: {} docs indexed", warmup);

    // Close and recreate to get clean timing
    lifecycle.close();
    cleanDir(indexPath);
    Files.createDirectories(indexPath);
    lifecycle =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(catalog)
            .atPath(indexPath)
            .open();

    // === Phase 1: Full JustSearch indexBatch (validation + mapping + write) ===
    long jsStart = System.nanoTime();
    lifecycle.indexingCoordinator().indexBatch(indexDocs);
    long jsEnd = System.nanoTime();
    long justSearchNs = jsEnd - jsStart;

    lifecycle.commitOps().commitAndTrack();
    lifecycle.close();

    // === Phase 4: Raw Lucene write (same docs, bare Document) ===
    Path rawPath = Files.createTempDirectory("overhead-raw-");
    MMapDirectory rawDir = new MMapDirectory(rawPath);
    IndexWriterConfig rawConfig = new IndexWriterConfig(new StandardAnalyzer());
    rawConfig.setRAMBufferSizeMB(256.0);
    rawConfig.setUseCompoundFile(false);
    IndexWriter rawWriter = new IndexWriter(rawDir, rawConfig);

    long rawStart = System.nanoTime();
    for (Map<String, Object> fields : rawFields) {
      Document doc = new Document();
      doc.add(new StringField("doc_id", (String) fields.get(SchemaFields.DOC_ID), Field.Store.YES));
      doc.add(new TextField("title", (String) fields.get(SchemaFields.TITLE), Field.Store.YES));
      doc.add(new TextField("content", (String) fields.get(SchemaFields.CONTENT), Field.Store.NO));
      rawWriter.updateDocument(new Term("doc_id", (String) fields.get(SchemaFields.DOC_ID)), doc);
    }
    long rawEnd = System.nanoTime();
    long rawLuceneNs = rawEnd - rawStart;

    rawWriter.commit();
    rawWriter.close();
    rawDir.close();

    // Calculate
    double jsMs = justSearchNs / 1_000_000.0;
    double rawMs = rawLuceneNs / 1_000_000.0;
    double overheadMs = jsMs - rawMs;
    if (overheadMs < 0) overheadMs = 0;

    log.info("=== Overhead Profile ({} docs) ===", docCount);
    log.info("  JustSearch total:     {} ms  ({} us/doc)  ({} docs/sec)",
        round(jsMs), round(jsMs * 1000 / docCount), Math.round(docCount / (jsMs / 1000)));
    log.info("  Raw Lucene:           {} ms  ({} us/doc)  ({} docs/sec)",
        round(rawMs), round(rawMs * 1000 / docCount), Math.round(docCount / (rawMs / 1000)));
    log.info("  Schema overhead:      {} ms  ({} us/doc)",
        round(overheadMs), round(overheadMs * 1000 / docCount));
    log.info("  Overhead ratio:       {}x", round(jsMs / rawMs));

    // Write JSON result
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("doc_count", docCount);
    result.put("bytes_total", bytesTotal);

    Map<String, Object> phases = new LinkedHashMap<>();
    phases.put("justsearch_total_ms", round(jsMs));
    phases.put("raw_lucene_ms", round(rawMs));
    phases.put("schema_overhead_ms", round(overheadMs));
    phases.put("overhead_ratio", round(jsMs / rawMs));
    result.put("phases", phases);

    Map<String, Object> perDoc = new LinkedHashMap<>();
    perDoc.put("justsearch_us", round(jsMs * 1000 / docCount));
    perDoc.put("raw_lucene_us", round(rawMs * 1000 / docCount));
    perDoc.put("schema_overhead_us", round(overheadMs * 1000 / docCount));
    result.put("per_doc_us", perDoc);

    Path resultFile = outPath.resolve("result.json");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(resultFile.toFile(), result);
    log.info("Wrote result to: {}", resultFile);

    // Cleanup
    cleanDir(indexPath);
    cleanDir(rawPath);
  }

  private static double round(double v) {
    return Math.round(v * 100) / 100.0;
  }

  private static void cleanDir(Path dir) {
    try (var stream = Files.walk(dir)) {
      stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
        try { Files.deleteIfExists(p); } catch (Exception expected) { /* best-effort cleanup */ }
      });
    } catch (Exception expected) { /* best-effort cleanup */ }
  }
}
