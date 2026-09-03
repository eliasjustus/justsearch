package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import io.justsearch.indexing.runtime.CommitMetadataValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParityGuardTest {
  static class GoodMeta implements CommitMetadataSource {
    @Override public Map<String, Object> build() { return new SsotCommitMetadataSource().build(); }
  }

  @Test
  void parityGuardAllowsWritesWhenMetadataMatches() throws Exception {
    Path dir = Files.createTempDirectory("lucene-parity-ok");
    CommitMetadataValidator validator = new JsonSchemaCommitMetadataValidator();

    var goodMeta = new GoodMeta();
    var r1 = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), goodMeta, validator).atPath(dir).open();
    r1.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(SchemaFields.DOC_ID, "parity-3", SchemaFields.DOC_UID, "parity-3#0")));
    r1.commitOps().commitAndTrack();
    r1.close();

    var r2 = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), goodMeta, validator).atPath(dir).open();
    // Should not throw
    r2.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(SchemaFields.DOC_ID, "parity-4", SchemaFields.DOC_UID, "parity-4#0")));
    r2.commitOps().commitAndTrack();
    r2.close();
  }

  @Test
  void parityGuardCatchesBoostsMismatch() throws Exception {
    Path dir = Files.createTempDirectory("lucene-parity-boosts");
    CommitMetadataValidator validator = new JsonSchemaCommitMetadataValidator();

    var base = new SsotCommitMetadataSource().build();
    CommitMetadataSource good = () -> base;
    CommitMetadataSource badBoosts = () -> {
      Map<String, Object> m = new HashMap<>(base);
      m.put("boosts_fp", "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
      return m;
    };

    var r1 = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), good, validator).atPath(dir).open();
    r1.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(SchemaFields.DOC_ID, "parity-5", SchemaFields.DOC_UID, "parity-5#0")));
    r1.commitOps().commitAndTrack();
    r1.close();

    var e =
        assertThrows(
            IllegalStateException.class,
            () ->
                io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                        FieldCatalogDef.forTesting(768), badBoosts, validator)
                    .atPath(dir)
                    .open());
    assertTrue(e.getMessage().contains("read-only"));
    assertTrue(
        e.getMessage().contains("metadata") || e.getMessage().contains("mismatch")
            || e.getMessage().contains("boosts_fp"),
        "error should mention metadata mismatch, got: " + e.getMessage());
  }

  @Test
  void parityGuardTriggersRebuildOnFingerprintMismatch() throws Exception {
    // A mismatch on index_fingerprint must surface as SCHEMA_MISMATCH so the recovery path acts on
    // it — under the production default that means blue/green, with Blue still serving reads
    // (tempdoc 915 §C). This is the half of the two-key split that costs a rebuild.
    Path dir = Files.createTempDirectory("lucene-parity-rebuild");
    CommitMetadataValidator validator = new JsonSchemaCommitMetadataValidator();

    // Write a committed index stamped with the real (good) metadata.
    var r1 = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new GoodMeta(), validator).atPath(dir).open();
    r1.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(SchemaFields.DOC_ID, "parity-6", SchemaFields.DOC_UID, "parity-6#0")));
    r1.commitOps().commitAndTrack();
    r1.close();

    // Drive the guard directly with an expected fingerprint that differs from what was stored.
    Map<String, Object> expected = new HashMap<>(new SsotCommitMetadataSource().build());
    expected.put(
        "index_fingerprint", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    IndexMetadataParityGuard guard = new IndexMetadataParityGuard(() -> dir, () -> expected);

    var e = assertThrows(IndexRuntimeIOException.class, guard::checkOnOpen);
    assertEquals(
        IndexRuntimeIOException.Reason.SCHEMA_MISMATCH,
        e.reason(),
        "index_fingerprint mismatch must surface as SCHEMA_MISMATCH so recovery rebuilds,"
            + " not read-only");
  }

  /**
   * Tempdoc 915 §C.5a — the one predicate that decides "this index has no recorded shape, and that
   * matters". Both the open-time guard and {@code IndexStatusOps}'s reported compatibility state
   * call it, because two independently written versions of this rule is exactly how a brand-new
   * install gets told to rebuild an index that has nothing in it yet.
   */
  @Test
  void anEmptyIndexWithoutAFingerprintIsNotAMigrationCandidate() {
    assertFalse(
        ParityDiagnostics.isIndexWithoutRecordedFingerprint("", 0L),
        "an empty index has no content that could have been written under the wrong shape");
    assertFalse(
        ParityDiagnostics.isIndexWithoutRecordedFingerprint(null, 0L),
        "absent and blank are the same absence");
    assertTrue(
        ParityDiagnostics.isIndexWithoutRecordedFingerprint("", 1L),
        "an index already holding documents of unrecorded shape needs the one-time rebuild");
    assertFalse(
        ParityDiagnostics.isIndexWithoutRecordedFingerprint("recorded-shape", 1L),
        "a recorded shape is compared, not migrated blind");
  }

}
