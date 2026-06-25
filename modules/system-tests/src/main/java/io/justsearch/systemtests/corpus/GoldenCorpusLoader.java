/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.corpus;

import static java.nio.charset.StandardCharsets.UTF_8;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads and provides access to the Golden Corpus and truth manifest.
 *
 * <p>The Golden Corpus is a curated set of documents designed to test
 * various aspects of search relevance:
 * <ul>
 *   <li><b>Lexical Truth</b> - Documents that should be found by keyword match</li>
 *   <li><b>Semantic Truth</b> - Documents that should be found by semantic similarity</li>
 *   <li><b>Hybrid Trap</b> - Documents that benefit from RRF fusion</li>
 * </ul>
 */
public final class GoldenCorpusLoader {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final TruthManifest manifest;
  private final Map<String, String> documentContents; // id -> content

  /**
   * Creates a loader with the given manifest and document contents.
   */
  public GoldenCorpusLoader(TruthManifest manifest, Map<String, String> documentContents) {
    this.manifest = manifest;
    this.documentContents = Map.copyOf(documentContents);
  }

  /**
   * Loads the Golden Corpus from the default classpath resources.
   */
  public static GoldenCorpusLoader loadDefault() throws IOException {
    return loadFromClasspath(
        "/manifests/golden-corpus-truth.json",
        "/corpus/standard/"
    );
  }

  /**
   * Loads the Golden Corpus from classpath resources.
   *
   * @param manifestResource Path to the truth manifest JSON
   * @param corpusDir Base path for corpus document files
   * @return Configured GoldenCorpusLoader
   */
  public static GoldenCorpusLoader loadFromClasspath(
      String manifestResource,
      String corpusDir) throws IOException {

    // Load manifest
    try (InputStream is = GoldenCorpusLoader.class.getResourceAsStream(manifestResource)) {
      if (is == null) {
        throw new IOException("Manifest not found: " + manifestResource);
      }
      TruthManifest manifest = parseManifest(is);

      // Load document contents
      Map<String, String> contents = new java.util.HashMap<>();
      for (DocumentInfo doc : manifest.documents()) {
        String resourcePath = corpusDir + doc.file().replace("corpus/standard/", "");
        try (InputStream docStream = GoldenCorpusLoader.class.getResourceAsStream(resourcePath)) {
          if (docStream != null) {
            contents.put(doc.id(), new String(docStream.readAllBytes(), UTF_8));
          } else {
            // Try alternative path
            String altPath = "/" + doc.file();
            try (InputStream altStream = GoldenCorpusLoader.class.getResourceAsStream(altPath)) {
              if (altStream != null) {
                contents.put(doc.id(), new String(altStream.readAllBytes(), UTF_8));
              }
            }
          }
        }
      }

      return new GoldenCorpusLoader(manifest, contents);
    }
  }

  /**
   * Loads the Golden Corpus from filesystem paths.
   *
   * @param manifestPath Path to the truth manifest JSON
   * @param corpusDir Base directory for corpus document files
   * @return Configured GoldenCorpusLoader
   */
  public static GoldenCorpusLoader loadFromFilesystem(
      Path manifestPath,
      Path corpusDir) throws IOException {

    TruthManifest manifest = parseManifest(Files.newInputStream(manifestPath));

    Map<String, String> contents = new java.util.HashMap<>();
    for (DocumentInfo doc : manifest.documents()) {
      Path docPath = corpusDir.resolve(doc.file());
      if (Files.exists(docPath)) {
        contents.put(doc.id(), Files.readString(docPath));
      }
    }

    return new GoldenCorpusLoader(manifest, contents);
  }

  private static TruthManifest parseManifest(InputStream is) throws IOException {
    JsonNode root = MAPPER.readTree(is);

    // Parse documents
    List<DocumentInfo> documents = new ArrayList<>();
    JsonNode docsNode = root.path("corpus").path("documents");
    for (JsonNode docNode : docsNode) {
      List<String> keywords = new ArrayList<>();
      for (JsonNode kw : docNode.path("keywords")) {
        keywords.add(kw.asText());
      }
      documents.add(new DocumentInfo(
          docNode.path("id").asText(),
          docNode.path("file").asText(),
          docNode.path("category").asText(),
          keywords,
          docNode.path("semantic_category").asText()
      ));
    }

    // Parse queries
    List<QueryInfo> queries = new ArrayList<>();
    for (JsonNode queryNode : root.path("queries")) {
      List<String> topDocs = new ArrayList<>();
      for (JsonNode td : queryNode.path("expected").path("top_docs")) {
        topDocs.add(td.asText());
      }
      List<String> excludedDocs = new ArrayList<>();
      for (JsonNode ed : queryNode.path("expected").path("not_in_top_3")) {
        excludedDocs.add(ed.asText());
      }
      queries.add(new QueryInfo(
          queryNode.path("id").asText(),
          queryNode.path("text").asText(),
          queryNode.path("mode").asText(),
          topDocs,
          excludedDocs,
          queryNode.path("expected").path("recall_at_3").asDouble(0.9),
          queryNode.path("expected").path("ndcg_at_3").asDouble(0.8)
      ));
    }

    // Parse metrics thresholds
    JsonNode metricsNode = root.path("metrics");
    double recallThreshold = metricsNode.path("recall_threshold").asDouble(0.9);
    double ndcgThreshold = metricsNode.path("ndcg_threshold").asDouble(0.8);

    return new TruthManifest(documents, queries, recallThreshold, ndcgThreshold);
  }

  /**
   * Returns the truth manifest.
   */
  public TruthManifest manifest() {
    return manifest;
  }

  /**
   * Returns all documents in the corpus.
   */
  public List<DocumentInfo> documents() {
    return manifest.documents();
  }

  /**
   * Returns all queries with expected results.
   */
  public List<QueryInfo> queries() {
    return manifest.queries();
  }

  /**
   * Returns the content of a document by ID.
   */
  public String getContent(String docId) {
    return documentContents.get(docId);
  }

  /**
   * Returns the set of relevant document IDs for a query.
   */
  public Set<String> getRelevantDocs(String queryId) {
    for (QueryInfo query : manifest.queries()) {
      if (query.id().equals(queryId)) {
        return new HashSet<>(query.expectedTopDocs());
      }
    }
    return Set.of();
  }

  /**
   * Returns documents filtered by category.
   */
  public List<DocumentInfo> getDocumentsByCategory(String category) {
    return manifest.documents().stream()
        .filter(d -> d.category().equals(category))
        .toList();
  }

  // === Record types ===

  /**
   * Full truth manifest structure.
   */
  public record TruthManifest(
      List<DocumentInfo> documents,
      List<QueryInfo> queries,
      double recallThreshold,
      double ndcgThreshold
  ) {}

  /**
   * Document metadata from the manifest.
   */
  public record DocumentInfo(
      String id,
      String file,
      String category,
      List<String> keywords,
      String semanticCategory
  ) {}

  /**
   * Query with expected results.
   */
  public record QueryInfo(
      String id,
      String text,
      String mode,
      List<String> expectedTopDocs,
      List<String> excludedDocs,
      double expectedRecall,
      double expectedNdcg
  ) {}
}
