package io.justsearch.systemtests.corpus;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * One-time generator for the RAG eval frozen vector manifest.
 *
 * <p>Run manually to regenerate:
 * <pre>
 * ./gradlew.bat :modules:system-tests:test --tests "*.RagEvalVectorGeneratorTest.generateVectors"
 * </pre>
 * Then copy {@code build/rag-eval-vectors.json} to
 * {@code src/test/resources/corpus/rag/rag-eval-vectors.json}.
 */
class RagEvalVectorGeneratorTest {

  @Test
  void generateVectors() throws Exception {
    var generator = new CorpusVectorGenerator(768);

    // Category assignments for corpus documents
    Map<String, String> docCategories = new LinkedHashMap<>();
    docCategories.put("hikari-connection-pool", "database");
    docCategories.put("data-pipeline-architecture", "database");
    docCategories.put("transformer-architecture", "ml");
    docCategories.put("gradient-descent", "ml");
    docCategories.put("puppy-vaccination", "animals");
    docCategories.put("java-generics", "programming");

    // Add document vectors with unique seeds per doc
    int seed = 0;
    for (var entry : docCategories.entrySet()) {
      generator.addText(entry.getKey(), entry.getValue(), seed++);
    }

    // Query-to-category mapping (category matches expected_source_docs[0])
    Map<String, String> queryCategories = new LinkedHashMap<>();
    // Existing queries (rag-001..rag-015)
    queryCategories.put(
        "What is the recommended maximum pool size for HikariCP on a 4-core server?", "database");
    queryCategories.put(
        "What is the attention formula used in the Transformer architecture?", "ml");
    queryCategories.put("What are the default beta values for the Adam optimizer?", "ml");
    queryCategories.put(
        "When should puppies receive their first rabies vaccination?", "animals");
    queryCategories.put("What is type erasure in Java generics?", "programming");
    queryCategories.put(
        "What is the difference between ETL and ELT data pipeline patterns?", "database");
    queryCategories.put(
        "Who created HikariCP and when did it become the Spring Boot default?", "database");
    queryCategories.put(
        "What are the parameters of the original Transformer base model?", "ml");
    queryCategories.put(
        "How does Apache Kafka ensure reliable data delivery in streaming pipelines?", "database");
    queryCategories.put("What does the PECS principle mean in Java generics?", "programming");
    queryCategories.put(
        "What reliability strategies do HikariCP and Apache Kafka share in common for handling failures?",
        "database");
    queryCategories.put(
        "What is the maximum number of concurrent connections PostgreSQL supports on a server with 16GB RAM?",
        "database");
    // New semantic-only queries (rag-013, rag-014, rag-015)
    queryCategories.put(
        "How should canine immunization schedules be structured for young dogs?", "animals");
    queryCategories.put("What techniques help neural networks learn faster?", "ml");
    queryCategories.put(
        "How do applications maintain persistent storage connections efficiently?", "database");
    // Phase 6 expansion queries (rag-016..rag-024), total queries = 24
    queryCategories.put(
        "What are HikariCP default values for connectionTimeout, idleTimeout, and maxLifetime?",
        "database");
    queryCategories.put(
        "In which year was the Transformer introduced, and where was the paper published?", "ml");
    queryCategories.put(
        "Which Apache technology in the corpus provides exactly-once delivery semantics since version 0.11?",
        "database");
    queryCategories.put(
        "Which came first: Adam optimizer or the Transformer architecture?", "ml");
    queryCategories.put(
        "Name one timeout mechanism from HikariCP and one retry-safety mechanism from streaming pipelines.",
        "database");
    queryCategories.put(
        "What GPU model did Vaswani et al. use to train the original Transformer?", "ml");
    queryCategories.put(
        "Who coined the PECS mnemonic, and who created HikariCP?", "programming");
    queryCategories.put(
        "Why do Java collections lose parameter type details once compiled?", "programming");
    queryCategories.put(
        "When can puppies safely socialize in public areas after vaccinations?", "animals");

    // Add query vectors with distinct seeds (offset from doc seeds)
    int querySeed = 100;
    for (var entry : queryCategories.entrySet()) {
      generator.addText(entry.getKey(), entry.getValue(), querySeed++);
    }

    // Export manifest
    Path outputPath = Path.of("build/rag-eval-vectors.json");
    generator.exportManifest(outputPath, "deterministic-category");
    System.out.println("Vector manifest written to: " + outputPath.toAbsolutePath());

    // Sanity checks: same-category similarity should be high, cross-category should be low
    var dbVec0 = generator.generateCategoryVector("database", 0);
    var dbVec1 = generator.generateCategoryVector("database", 1);
    var mlVec0 = generator.generateCategoryVector("ml", 0);
    var animVec0 = generator.generateCategoryVector("animals", 0);
    var progVec0 = generator.generateCategoryVector("programming", 0);

    double sameCategory = CorpusVectorGenerator.cosineSimilarity(dbVec0, dbVec1);
    double crossDbMl = CorpusVectorGenerator.cosineSimilarity(dbVec0, mlVec0);
    double crossDbAnim = CorpusVectorGenerator.cosineSimilarity(dbVec0, animVec0);
    double crossDbProg = CorpusVectorGenerator.cosineSimilarity(dbVec0, progVec0);

    System.out.printf("Same category (database 0 vs 1): %.4f%n", sameCategory);
    System.out.printf("Cross category (database vs ml): %.4f%n", crossDbMl);
    System.out.printf("Cross category (database vs animals): %.4f%n", crossDbAnim);
    System.out.printf("Cross category (database vs programming): %.4f%n", crossDbProg);

    assertTrue(sameCategory > 0.9, "Same-category similarity should be > 0.9, got " + sameCategory);
    assertTrue(
        Math.abs(crossDbMl) < 0.2,
        "Cross-category similarity should be < 0.2, got " + crossDbMl);
  }
}
