package io.justsearch.systemtests.corpus;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import io.justsearch.indexing.chunking.ChunkSplitter;
import io.justsearch.indexing.chunking.ChunkSplitter.Chunk;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates frozen embedding vectors for the passage-retrieval corpus.
 *
 * <p>This standalone tool reads all corpus .txt files from the passage-retrieval directory, splits
 * long documents into chunks using {@link ChunkSplitter#splitWithMetadata}, and calls a
 * llama-server {@code /v1/embeddings} endpoint to produce real embedding vectors. The output is a
 * JSON manifest that {@link FrozenEmbeddingBackend} can load for deterministic integration tests.
 *
 * <h3>Usage</h3>
 *
 * <p>Start llama-server with an embedding model, then run:
 *
 * <pre>{@code
 * ./gradlew.bat :modules:system-tests:generatePassageVectors
 * }</pre>
 *
 * <p>Or with a custom server URL:
 *
 * <pre>{@code
 * ./gradlew.bat :modules:system-tests:generatePassageVectors -PllamaServerUrl=http://127.0.0.1:9090/v1/embeddings
 * }</pre>
 *
 * <p>For testing without llama-server, pass {@code --deterministic} to generate category-based
 * pseudo-random vectors instead of real embeddings.
 */
public final class PassageRetrievalVectorGenerator {

  private static final String DEFAULT_SERVER_URL = "http://127.0.0.1:8081/v1/embeddings";
  private static final String MODEL_NAME = "nomic-embed-text-v1.5";
  private static final int CHUNK_TARGET_TOKENS = 500;
  private static final int CHUNK_OVERLAP_TOKENS = 50;
  private static final int CHUNK_THRESHOLD_CHARS = 2000;
  private static final int DETERMINISTIC_DIMENSION = 768;

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  /** Query texts that need embeddings for the experiment. */
  private static final List<String> QUERY_TEXTS =
      List.of(
          "VRAM memory management",
          "database connection pooling best practices",
          "garbage collection tuning",
          "TLS certificate rotation");

  public static void main(String[] args) throws Exception {
    boolean deterministic = List.of(args).contains("--deterministic");
    String serverUrl = System.getProperty("llama.server.url", DEFAULT_SERVER_URL);

    Path corpusDir =
        Path.of("modules/system-tests/src/test/resources/corpus/passage-retrieval");
    Path outputPath =
        Path.of(
            "modules/system-tests/src/test/resources/corpus/"
                + "passage-retrieval-frozen-vectors.json");

    if (!Files.isDirectory(corpusDir)) {
      System.err.println("Corpus directory not found: " + corpusDir.toAbsolutePath());
      System.err.println("Run from the repository root directory.");
      System.exit(1);
    }

    // 1. Load corpus documents
    Map<String, String> documents = loadCorpus(corpusDir);
    System.out.printf("Loaded %d corpus documents%n", documents.size());

    // 2. Collect all texts that need embeddings
    Map<String, String> textsToEmbed = new LinkedHashMap<>();

    // Document-level: full content for doc-level VECTOR search
    for (var entry : documents.entrySet()) {
      textsToEmbed.put(entry.getKey(), entry.getValue());
    }

    // Chunk-level: chunk content for chunk-level VECTOR search
    // Uses splitWithMetadata() — the same method the integration test uses
    for (var entry : documents.entrySet()) {
      String content = entry.getValue();
      if (content.length() < CHUNK_THRESHOLD_CHARS) {
        System.out.printf(
            "  %s: %d chars (below threshold, no chunks)%n",
            entry.getKey(), content.length());
        continue;
      }

      List<Chunk> chunks =
          ChunkSplitter.splitWithMetadata(content, CHUNK_TARGET_TOKENS, CHUNK_OVERLAP_TOKENS);
      System.out.printf(
          "  %s: %d chars -> %d chunks%n", entry.getKey(), content.length(), chunks.size());

      for (Chunk chunk : chunks) {
        String chunkId = entry.getKey() + "#chunk_" + chunk.index();
        textsToEmbed.put(chunkId, chunk.content());
      }
    }

    // Query-level: query strings for lookup during search
    for (String query : QUERY_TEXTS) {
      textsToEmbed.put(query, query);
    }

    System.out.printf("Total texts to embed: %d%n", textsToEmbed.size());

    // 3. Generate embeddings
    Map<String, List<Double>> vectors;
    int dimension;

    if (deterministic) {
      System.out.println("Using deterministic mode (category-based pseudo-random vectors)");
      vectors = generateDeterministicVectors(textsToEmbed);
      dimension = DETERMINISTIC_DIMENSION;
    } else {
      System.out.printf("Connecting to llama-server at %s%n", serverUrl);
      vectors = generateRealVectors(textsToEmbed, serverUrl);
      dimension = vectors.values().iterator().next().size();
    }

    // 4. Write manifest
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("dimension", dimension);
    manifest.put("model", deterministic ? "deterministic-category" : MODEL_NAME);
    manifest.put("vectors", vectors);

    Files.writeString(outputPath, MAPPER.writeValueAsString(manifest));
    System.out.printf(
        "Wrote %d vectors (dim=%d) to %s%n",
        vectors.size(), dimension, outputPath.toAbsolutePath());
  }

  private static Map<String, String> loadCorpus(Path corpusDir) throws IOException {
    Map<String, String> docs = new LinkedHashMap<>();
    try (var stream = Files.list(corpusDir)) {
      stream
          .filter(p -> p.toString().endsWith(".txt"))
          .sorted()
          .forEach(
              p -> {
                String id = p.getFileName().toString().replace(".txt", "");
                try {
                  docs.put(id, Files.readString(p));
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    }
    return docs;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, List<Double>> generateRealVectors(
      Map<String, String> textsToEmbed, String serverUrl) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    Map<String, List<Double>> vectors = new LinkedHashMap<>();

    int count = 0;
    int total = textsToEmbed.size();

    for (var entry : textsToEmbed.entrySet()) {
      String id = entry.getKey();
      String text = entry.getValue();
      count++;

      System.out.printf("  [%d/%d] Embedding: %s (%d chars)%n", count, total, id, text.length());

      String requestBody =
          MAPPER.writeValueAsString(Map.of("model", MODEL_NAME, "input", text));

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(serverUrl))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();

      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        throw new RuntimeException(
            "Embedding request failed for '"
                + id
                + "': HTTP "
                + response.statusCode()
                + " - "
                + response.body());
      }

      Map<String, Object> responseMap = MAPPER.readValue(response.body(), Map.class);
      List<Map<String, Object>> data = (List<Map<String, Object>>) responseMap.get("data");
      List<Number> embedding = (List<Number>) data.get(0).get("embedding");

      vectors.put(id, embedding.stream().map(Number::doubleValue).toList());
    }

    return vectors;
  }

  /**
   * Generates deterministic vectors using category-based similarity. Documents about the same topic
   * get similar vectors; confusers get orthogonal vectors.
   */
  private static Map<String, List<Double>> generateDeterministicVectors(
      Map<String, String> textsToEmbed) {
    CorpusVectorGenerator gen = new CorpusVectorGenerator(DETERMINISTIC_DIMENSION);

    // Assign categories based on document purpose
    Map<String, String> categories = new LinkedHashMap<>();
    // Targets (long docs with buried passages)
    categories.put("long-vram-architecture", "vram");
    categories.put("long-http-protocols", "pooling");
    categories.put("long-jvm-internals", "gc");
    categories.put("long-container-orchestration", "tls");
    // Baselines (short, focused docs)
    categories.put("short-vram-tips", "vram");
    categories.put("short-connection-pooling", "pooling");
    categories.put("short-gc-tuning", "gc");
    categories.put("short-tls-cert-rotation", "tls");
    // Confusers (same keywords, different context)
    categories.put("long-gpu-compute-benchmarks", "vram-confuser");
    categories.put("long-microservice-observability", "pooling-confuser");
    categories.put("long-database-internals", "gc-confuser");
    categories.put("long-network-security-audit", "tls-confuser");

    // Map chunk IDs to parent categories
    Map<String, String> chunkCategories = new LinkedHashMap<>();
    for (var entry : textsToEmbed.entrySet()) {
      String id = entry.getKey();
      if (id.contains("#chunk_")) {
        String parentId = id.substring(0, id.indexOf("#chunk_"));
        chunkCategories.put(id, categories.getOrDefault(parentId, "unknown"));
      }
    }

    int seed = 0;
    for (var entry : textsToEmbed.entrySet()) {
      String id = entry.getKey();
      String category;

      if (categories.containsKey(id)) {
        category = categories.get(id);
      } else if (chunkCategories.containsKey(id)) {
        category = chunkCategories.get(id);
      } else if (id.contains("VRAM") || id.contains("vram")) {
        category = "vram";
      } else if (id.contains("pooling") || id.contains("connection")) {
        category = "pooling";
      } else if (id.contains("garbage") || id.contains("gc")) {
        category = "gc";
      } else if (id.contains("TLS") || id.contains("certificate")) {
        category = "tls";
      } else {
        category = "unknown";
      }

      gen.addText(id, category, seed++);
    }

    return new LinkedHashMap<>(gen.getVectors());
  }
}
