package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 598 R3 — the embedding-fingerprint half of green cutover verification
 * ({@link KnowledgeServerMigrationOps#verifyGreenMetadata}, the IO-free core of the cutover guard).
 * A blue/green rebuild must not promote a green that lacks a current-model embedding fingerprint
 * (else the promoted generation serves BLOCKED_LEGACY despite a "successful" rebuild — the tempdoc
 * 593 §H trap); but a keyword-only rebuild (no embedding model resolvable) must still promote.
 */
class GreenCutoverEmbeddingFpVerifyTest {

  private static final Logger LOG = LoggerFactory.getLogger(GreenCutoverEmbeddingFpVerifyTest.class);
  private static final String EMBED_KEY = EmbeddingCompatibilityController.COMMIT_META_KEY;

  /** The real current schema fingerprint, so the schema check passes and we isolate the embed check. */
  private static String schemaFp() {
    return String.valueOf(new SsotCommitMetadataSource().build().get("index_schema_fp"));
  }

  private static Map<String, String> completeGreen() {
    Map<String, String> ud = new HashMap<>();
    ud.put("build_state", "COMPLETE");
    ud.put("index_schema_fp", schemaFp());
    return ud;
  }

  @Test
  @DisplayName("matching embedding fingerprint → green verifies")
  void matchingFpVerifies() {
    Map<String, String> ud = completeGreen();
    ud.put(EMBED_KEY, "abc123");
    assertTrue(KnowledgeServerMigrationOps.verifyGreenMetadata(ud, "abc123", LOG));
  }

  @Test
  @DisplayName("missing embedding fingerprint when a model is expected → green REJECTED")
  void missingFpWhenExpectedRejected() {
    assertFalse(KnowledgeServerMigrationOps.verifyGreenMetadata(completeGreen(), "abc123", LOG));
  }

  @Test
  @DisplayName("mismatched embedding fingerprint → green REJECTED")
  void mismatchedFpRejected() {
    Map<String, String> ud = completeGreen();
    ud.put(EMBED_KEY, "stale-model-sha");
    assertFalse(KnowledgeServerMigrationOps.verifyGreenMetadata(ud, "abc123", LOG));
  }

  @Test
  @DisplayName("no embedding model expected (keyword-only rebuild) → embedding check skipped, verifies")
  void noModelSkipsEmbeddingCheck() {
    assertTrue(KnowledgeServerMigrationOps.verifyGreenMetadata(completeGreen(), null, LOG));
    assertTrue(KnowledgeServerMigrationOps.verifyGreenMetadata(completeGreen(), "  ", LOG));
  }

  @Test
  @DisplayName("incomplete green (build_state != COMPLETE) → REJECTED even with a valid embedding fp")
  void incompleteGreenRejected() {
    Map<String, String> ud = completeGreen();
    ud.put("build_state", "BUILDING");
    ud.put(EMBED_KEY, "abc123");
    assertFalse(KnowledgeServerMigrationOps.verifyGreenMetadata(ud, "abc123", LOG));
  }
}
