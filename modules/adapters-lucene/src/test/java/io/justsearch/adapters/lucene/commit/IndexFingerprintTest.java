package io.justsearch.adapters.lucene.commit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.IndexFingerprint.Chunking;
import io.justsearch.adapters.lucene.commit.IndexFingerprint.FieldShape;
import io.justsearch.adapters.lucene.commit.IndexFingerprint.Hnsw;
import io.justsearch.adapters.lucene.commit.IndexFingerprint.Inputs;
import io.justsearch.adapters.lucene.commit.IndexFingerprint.ModelFingerprint;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 915 §C — what {@code index_fingerprint} must and must not react to.
 *
 * <p>The key it replaces was wrong in both directions, so both directions are pinned here: a change
 * that alters the bytes on disk has to move it (or a stale index goes undetected), and a change that
 * does not has to leave it alone (or every annotation edit costs users a full reindex, which is what
 * tempdoc 804 documented).
 */
final class IndexFingerprintTest {

  @AfterEach
  void resetProviders() {
    IndexFingerprint.resetModelFingerprintProviders();
  }

  private static FieldShape textField() {
    return new FieldShape(
        "content", "text", true, false, false, "icu_default", List.of("content"), null, null);
  }

  private static FieldShape vectorField(int dimension, String similarity) {
    return new FieldShape(
        "vector", "vector", false, false, false, null, List.of("vector"), dimension, similarity);
  }

  private static Inputs baseline() {
    return new Inputs(
        "1.0.0",
        List.of(textField(), vectorField(768, "euclidean")),
        "analyzer-fp-1",
        "float32",
        new Hnsw(16, 100),
        new Chunking(500, 50, 100, "v1"),
        ModelFingerprint.present("a".repeat(64)),
        ModelFingerprint.notConfigured());
  }

  private static String fingerprintOf(Inputs inputs) {
    return IndexFingerprint.compute(inputs).orElseThrow();
  }

  @Test
  void identicalInputsGiveIdenticalFingerprints() {
    assertEquals(fingerprintOf(baseline()), fingerprintOf(baseline()));
  }

  @Test
  void fieldOrderIsNotAnInput() {
    Inputs reordered =
        new Inputs(
            "1.0.0",
            List.of(vectorField(768, "euclidean"), textField()),
            "analyzer-fp-1",
            "float32",
            new Hnsw(16, 100),
            new Chunking(500, 50, 100, "v1"),
            ModelFingerprint.present("a".repeat(64)),
            ModelFingerprint.notConfigured());
    assertEquals(
        fingerprintOf(baseline()),
        fingerprintOf(reordered),
        "the catalog's field order is authoring convenience, not index shape");
  }

  @Test
  void everyPhysicalInputMovesTheFingerprint() {
    Inputs base = baseline();
    String baseFp = fingerprintOf(base);

    assertNotEquals(
        baseFp,
        fingerprintOf(
            new Inputs(
                "2.0.0",
                base.fields(),
                base.analyzerFingerprint(),
                base.vectorFormat(),
                base.hnsw(),
                base.chunking(),
                base.embeddingModel(),
                base.spladeModel())),
        "catalog_schema_version");

    assertNotEquals(
        baseFp,
        fingerprintOf(
            new Inputs(
                base.catalogSchemaVersion(),
                List.of(textField(), vectorField(1024, "euclidean")),
                base.analyzerFingerprint(),
                base.vectorFormat(),
                base.hnsw(),
                base.chunking(),
                base.embeddingModel(),
                base.spladeModel())),
        "vector dimension");

    assertNotEquals(
        baseFp,
        fingerprintOf(
            new Inputs(
                base.catalogSchemaVersion(),
                List.of(textField(), vectorField(768, "dot_product")),
                base.analyzerFingerprint(),
                base.vectorFormat(),
                base.hnsw(),
                base.chunking(),
                base.embeddingModel(),
                base.spladeModel())),
        "vector similarity — phase 3 flips this and must invalidate float32 EUCLIDEAN indexes");

    assertNotEquals(
        baseFp,
        fingerprintOf(
            new Inputs(
                base.catalogSchemaVersion(),
                base.fields(),
                "analyzer-fp-2",
                base.vectorFormat(),
                base.hnsw(),
                base.chunking(),
                base.embeddingModel(),
                base.spladeModel())),
        "analyzer fingerprint");

    assertNotEquals(
        baseFp,
        fingerprintOf(
            new Inputs(
                base.catalogSchemaVersion(),
                base.fields(),
                base.analyzerFingerprint(),
                "int8_sq",
                base.hnsw(),
                base.chunking(),
                base.embeddingModel(),
                base.spladeModel())),
        "vector storage format");

    assertNotEquals(
        baseFp,
        fingerprintOf(
            new Inputs(
                base.catalogSchemaVersion(),
                base.fields(),
                base.analyzerFingerprint(),
                base.vectorFormat(),
                new Hnsw(32, 100),
                base.chunking(),
                base.embeddingModel(),
                base.spladeModel())),
        "HNSW m");

    assertNotEquals(
        baseFp,
        fingerprintOf(
            new Inputs(
                base.catalogSchemaVersion(),
                base.fields(),
                base.analyzerFingerprint(),
                base.vectorFormat(),
                new Hnsw(16, 200),
                base.chunking(),
                base.embeddingModel(),
                base.spladeModel())),
        "HNSW ef_construction");

    assertNotEquals(
        baseFp,
        fingerprintOf(
            new Inputs(
                base.catalogSchemaVersion(),
                base.fields(),
                base.analyzerFingerprint(),
                base.vectorFormat(),
                base.hnsw(),
                new Chunking(400, 50, 100, "v1"),
                base.embeddingModel(),
                base.spladeModel())),
        "chunk target tokens");

    assertNotEquals(
        baseFp,
        fingerprintOf(
            new Inputs(
                base.catalogSchemaVersion(),
                base.fields(),
                base.analyzerFingerprint(),
                base.vectorFormat(),
                base.hnsw(),
                new Chunking(500, 50, 100, "v2"),
                base.embeddingModel(),
                base.spladeModel())),
        "chunker algorithm version");

    assertNotEquals(
        baseFp,
        fingerprintOf(
            new Inputs(
                base.catalogSchemaVersion(),
                base.fields(),
                base.analyzerFingerprint(),
                base.vectorFormat(),
                base.hnsw(),
                base.chunking(),
                ModelFingerprint.present("b".repeat(64)),
                base.spladeModel())),
        "embedding model digest");

    assertNotEquals(
        baseFp,
        fingerprintOf(
            new Inputs(
                base.catalogSchemaVersion(),
                base.fields(),
                base.analyzerFingerprint(),
                base.vectorFormat(),
                base.hnsw(),
                base.chunking(),
                base.embeddingModel(),
                ModelFingerprint.present("c".repeat(64)))),
        "SPLADE model digest");
  }

  /**
   * "A model was never configured here" and "a model is configured but I could not read it" are
   * different answers, and only the first is an answer. Conflating them is how a transiently
   * unreadable model file would cost a user a full reindex.
   */
  @Test
  void anIndeterminateModelYieldsNoFingerprintAtAll() {
    Inputs base = baseline();
    Inputs indeterminateEmbedding =
        new Inputs(
            base.catalogSchemaVersion(),
            base.fields(),
            base.analyzerFingerprint(),
            base.vectorFormat(),
            base.hnsw(),
            base.chunking(),
            ModelFingerprint.indeterminate(),
            base.spladeModel());
    assertTrue(IndexFingerprint.compute(indeterminateEmbedding).isEmpty());

    Inputs indeterminateSplade =
        new Inputs(
            base.catalogSchemaVersion(),
            base.fields(),
            base.analyzerFingerprint(),
            base.vectorFormat(),
            base.hnsw(),
            base.chunking(),
            base.embeddingModel(),
            ModelFingerprint.indeterminate());
    assertTrue(IndexFingerprint.compute(indeterminateSplade).isEmpty());
  }

  @Test
  void notConfiguredIsADeterminateAnswerDistinctFromAnyDigest() {
    Inputs base = baseline();
    Inputs spladePresent =
        new Inputs(
            base.catalogSchemaVersion(),
            base.fields(),
            base.analyzerFingerprint(),
            base.vectorFormat(),
            base.hnsw(),
            base.chunking(),
            base.embeddingModel(),
            ModelFingerprint.present("c".repeat(64)));
    assertNotEquals(
        fingerprintOf(base),
        fingerprintOf(spladePresent),
        "installing a SPLADE model changes what is written into the sparse fields");
  }

  @Test
  void modelFingerprintOfMapsTheThreeStatesCorrectly() {
    assertEquals(
        IndexFingerprint.ModelState.NOT_CONFIGURED,
        ModelFingerprint.of(false, Optional.of("x")).state(),
        "no model file means not configured, whatever a stale digest says");
    assertEquals(
        IndexFingerprint.ModelState.INDETERMINATE,
        ModelFingerprint.of(true, Optional.empty()).state(),
        "a model file we cannot digest is indeterminate, not absent");
    assertEquals(
        IndexFingerprint.ModelState.PRESENT, ModelFingerprint.of(true, Optional.of("x")).state());
  }

  /**
   * The canonical JSON is what gets hashed, so it is also what an operator reads when a mismatch
   * costs them a rebuild. Pin that it carries the named inputs and none of the excluded ones.
   */
  @Test
  void canonicalJsonCarriesTheNamedInputsAndNoQueryTimeConfig() {
    String json = new String(IndexFingerprint.canonicalJson(baseline()), StandardCharsets.UTF_8);
    for (String expected :
        List.of(
            "rendering_version",
            "catalog_schema_version",
            "analyzer_fp",
            "vector_format",
            "hnsw",
            "ef_construction",
            "chunking",
            "algorithm_version",
            "embedding_model_sha256",
            "splade_model_sha256",
            "similarity",
            "doc_values",
            "multi_valued",
            "roles")) {
      assertTrue(json.contains(expected), "canonical JSON should carry " + expected + ": " + json);
    }
    for (String forbidden : List.of("boosts", "k1", "similarity_fp", "ef_search", "rmwPolicy")) {
      assertFalse(
          json.contains(forbidden),
          "canonical JSON must not carry query-time or runtime-policy input " + forbidden);
    }
  }

  @Test
  void aProviderThatThrowsIsIndeterminateNotAbsent() {
    IndexFingerprint.installModelFingerprintProviders(
        () -> {
          throw new IllegalStateException("model store unavailable");
        },
        ModelFingerprint::notConfigured);
    assertEquals(
        IndexFingerprint.ModelState.INDETERMINATE,
        IndexFingerprint.embeddingModel().state(),
        "a provider that fails has told us nothing, not that the model is gone");
  }

  @Test
  void theDefaultProvidersAreNotConfigured() {
    assertEquals(
        IndexFingerprint.ModelState.NOT_CONFIGURED, IndexFingerprint.embeddingModel().state());
    assertEquals(
        IndexFingerprint.ModelState.NOT_CONFIGURED, IndexFingerprint.spladeModel().state());
  }
}
