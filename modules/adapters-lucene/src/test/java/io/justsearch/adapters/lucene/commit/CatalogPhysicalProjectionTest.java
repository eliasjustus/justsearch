package io.justsearch.adapters.lucene.commit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.adapters.lucene.commit.IndexFingerprint.Chunking;
import io.justsearch.adapters.lucene.commit.IndexFingerprint.FieldShape;
import io.justsearch.adapters.lucene.commit.IndexFingerprint.Hnsw;
import io.justsearch.adapters.lucene.commit.IndexFingerprint.Inputs;
import io.justsearch.adapters.lucene.commit.IndexFingerprint.ModelFingerprint;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 915 §C — the specific defect tempdoc 804 named: {@code index_schema_fp} was the SHA-256
 * of the catalog <em>file</em>, so any byte edit flipped it. Three post-v0.1.0 catalog edits — a
 * dead-field deletion and three {@code rmwPolicy} annotations — each demanded a full reindex of an
 * index that was physically identical to what the runtime would have written.
 *
 * <p>The fix is that the fingerprint hashes a <em>physical projection</em> instead. This pins both
 * halves of that: an annotation-only edit is invisible, and every property that does reach Lucene
 * is not.
 */
final class CatalogPhysicalProjectionTest {

  private static final String WITHOUT_ANNOTATION =
      """
      {
        "version": "1.0.0",
        "fields": [
          {"id": "content", "type": "text", "stored": true, "docValues": false,
           "analyzer": "icu_default", "roles": ["content"]},
          {"id": "vector", "type": "vector", "stored": false, "docValues": false,
           "roles": ["vector"], "vector": {"dimension": 768}}
        ]
      }
      """;

  /** Identical, except each field carries an {@code rmwPolicy} annotation and a doc comment. */
  private static final String WITH_ANNOTATION =
      """
      {
        "version": "1.0.0",
        "fields": [
          {"id": "content", "type": "text", "stored": true, "docValues": false,
           "analyzer": "icu_default", "roles": ["content"],
           "rmwPolicy": "preserve-reread"},
          {"id": "vector", "type": "vector", "stored": false, "docValues": false,
           "roles": ["vector"], "vector": {"dimension": 768},
           "rmwPolicy": "preserve-reread-or-reset:embedding_status"}
        ]
      }
      """;

  private static JsonNode parse(String json) {
    return JsonMapper.builder().build().readTree(json);
  }

  private static Inputs inputsFor(String catalogJson, Integer effectiveDimension) {
    return new Inputs(
        "1.0.0",
        SsotCommitMetadataSource.projectFields(
            parse(catalogJson), effectiveDimension),
        "analyzer-fp",
        "float32",
        new Hnsw(16, 100),
        new Chunking(500, 50, 100, "v1"),
        ModelFingerprint.notConfigured(),
        ModelFingerprint.notConfigured());
  }

  private static String fingerprint(String catalogJson) {
    return IndexFingerprint.compute(inputsFor(catalogJson, null)).orElseThrow();
  }

  @Test
  void anRmwPolicyAnnotationDoesNotCostTheUserAReindex() {
    assertEquals(
        fingerprint(WITHOUT_ANNOTATION),
        fingerprint(WITH_ANNOTATION),
        "rmwPolicy drives runtime read-modify-write preservation and never changes what is written"
            + " to disk; a fingerprint that moved on it would repeat tempdoc 804's over-trigger");
  }

  @Test
  void theProjectionDropsRmwPolicyEntirely() {
    List<FieldShape> shapes =
        SsotCommitMetadataSource.projectFields(
            parse(WITH_ANNOTATION), null);
    assertEquals(2, shapes.size());
    assertEquals("content", shapes.get(0).id());
    assertEquals("icu_default", shapes.get(0).analyzer());
    assertEquals(List.of("content"), shapes.get(0).roles());
    assertNull(shapes.get(0).vectorDimension(), "a text field carries no vector shape");
    assertEquals(768, shapes.get(1).vectorDimension());
    assertEquals(
        "euclidean",
        shapes.get(1).vectorSimilarity(),
        "a vector field with no declared similarity records Lucene's actual default, so declaring"
            + " one later is a real fingerprint change rather than a silent no-op");
  }

  @Test
  void aStoredFlagFlipIsAReindex() {
    String storedFalse = WITHOUT_ANNOTATION.replace("\"stored\": true", "\"stored\": false");
    assertNotEquals(
        fingerprint(WITHOUT_ANNOTATION),
        fingerprint(storedFalse),
        "stored decides whether the value is written to disk at all");
  }

  @Test
  void aRoleChangeIsAReindex() {
    String extraRole = WITHOUT_ANNOTATION.replace("[\"content\"]", "[\"content\", \"filter\"]");
    assertNotEquals(
        fingerprint(WITHOUT_ANNOTATION),
        fingerprint(extraRole),
        "roles decide the Lucene field construction — 'filter' picks a keyword/doc-values shape");
  }

  @Test
  void aDeletedFieldIsAReindex() {
    String withExtraField =
        WITHOUT_ANNOTATION.replace(
            "\"roles\": [\"vector\"], \"vector\": {\"dimension\": 768}}",
            "\"roles\": [\"vector\"], \"vector\": {\"dimension\": 768}},\n"
                + "    {\"id\": \"title\", \"type\": \"text\", \"stored\": true, \"docValues\": false,"
                + " \"roles\": [\"content\"]}");
    assertNotEquals(
        fingerprint(WITHOUT_ANNOTATION),
        fingerprint(withExtraField),
        "adding or removing a field changes what documents carry");
  }

  @Test
  void theRuntimeVectorDimensionOverridesTheCatalogDeclaration() {
    assertNotEquals(
        fingerprint(WITHOUT_ANNOTATION),
        IndexFingerprint.compute(inputsFor(WITHOUT_ANNOTATION, 1024)).orElseThrow(),
        "when the Worker builds 1024-dim vectors the fingerprint must say 1024, not the catalog's"
            + " declared 768 — otherwise a BGE-M3 index and a nomic index look identical");
    assertEquals(
        1024,
        SsotCommitMetadataSource.projectFields(
                parse(WITHOUT_ANNOTATION), 1024)
            .get(1)
            .vectorDimension());
  }

  @Test
  void anOverrideDoesNotInventAVectorShapeForNonVectorFields() {
    assertNull(
        SsotCommitMetadataSource.projectFields(
                parse(WITHOUT_ANNOTATION), 1024)
            .get(0)
            .vectorDimension(),
        "the override applies to fields that declare a dimension, not to every field");
  }
}
