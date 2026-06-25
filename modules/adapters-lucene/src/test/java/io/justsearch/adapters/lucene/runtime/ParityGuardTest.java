package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
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

  static class BadMeta implements CommitMetadataSource {
    private final Map<String, Object> base;
    BadMeta() { this.base = new SsotCommitMetadataSource().build(); }
    @Override public Map<String, Object> build() {
      Map<String, Object> m = new HashMap<>(base);
      // Flip similarity_fp only (a query-time scoring key) to a different value (64 hex chars).
      // similarity_fp is NOT a rebuild-requiring key, so the guard marks the shard read-only
      // rather than triggering a rebuild (tempdoc 581 §13). Rebuild-requiring keys (analyzer_fp,
      // index_schema_fp, schema_ver) are covered by parityGuardTriggersRebuildOnAnalyzerMismatch.
      m.put("similarity_fp", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
      return m;
    }
  }

  @Test
  void parityGuardMarksReadOnlyOnMismatch() throws Exception {
    Path dir = Files.createTempDirectory("lucene-parity-test");
    CommitMetadataValidator validator = new JsonSchemaCommitMetadataValidator();

    // First runtime writes a commit with GOOD metadata
    var r1 = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new GoodMeta(), validator).atPath(dir).open();
    r1.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(SchemaFields.DOC_ID, "parity-1", SchemaFields.DOC_UID, "parity-1#0")));
    r1.commitOps().commitAndTrack();
    r1.close();

    // Second runtime with BAD metadata: open() runs the parity guard and throws.
    var e =
        assertThrows(
            IllegalStateException.class,
            () ->
                io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                        FieldCatalogDef.forTesting(768), new BadMeta(), validator)
                    .atPath(dir)
                    .open());
    assertTrue(e.getMessage().contains("read-only"));
    assertTrue(
        e.getMessage().contains("metadata") || e.getMessage().contains("mismatch")
            || e.getMessage().contains("analyzer_fp") || e.getMessage().contains("similarity_fp"),
        "error should mention metadata mismatch, got: " + e.getMessage());
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
  void parityGuardTriggersRebuildOnAnalyzerMismatch() throws Exception {
    // A mismatch on a rebuild-requiring key (analyzer_fp) must surface as SCHEMA_MISMATCH so the
    // RuntimeSession recovery wrapper rebuilds the index (backup-first) instead of crashing the
    // worker read-only — analyzer/schema-catalog changes migrate transparently (tempdoc 581 §13).
    Path dir = Files.createTempDirectory("lucene-parity-rebuild");
    CommitMetadataValidator validator = new JsonSchemaCommitMetadataValidator();

    // Write a committed index stamped with the real (good) metadata.
    var r1 = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new GoodMeta(), validator).atPath(dir).open();
    r1.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(SchemaFields.DOC_ID, "parity-6", SchemaFields.DOC_UID, "parity-6#0")));
    r1.commitOps().commitAndTrack();
    r1.close();

    // Drive the guard directly with an expected analyzer_fp that differs from what was stored.
    Map<String, Object> expected = new HashMap<>(new SsotCommitMetadataSource().build());
    expected.put("analyzer_fp", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    IndexMetadataParityGuard guard = new IndexMetadataParityGuard(() -> dir, () -> expected);

    var e = assertThrows(IndexRuntimeIOException.class, guard::checkOnOpen);
    assertEquals(
        IndexRuntimeIOException.Reason.SCHEMA_MISMATCH,
        e.reason(),
        "analyzer_fp mismatch must surface as SCHEMA_MISMATCH so recovery rebuilds, not read-only");
  }
}
