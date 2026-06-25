package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.RequiredFieldsCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import io.justsearch.indexing.runtime.CommitMetadataValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.store.MMapDirectory;
import org.junit.jupiter.api.Test;

class CommitMetadataIntegrationTest {
  @Test
  void commitStampsUserData() throws Exception {
    Path dir = Files.createTempDirectory("lucene-commit-test");
    CommitMetadataSource meta = new SsotCommitMetadataSource();
    CommitMetadataValidator validator = new JsonSchemaCommitMetadataValidator();

    var r = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), meta, validator).atPath(dir).open();
    r.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(SchemaFields.DOC_ID, "commit-1", SchemaFields.DOC_UID, "commit-1#0")));
    r.commitOps().commitAndTrack();
    r.close();

    try (var d = new MMapDirectory(dir); var reader = DirectoryReader.open(d)) {
      IndexCommit c = reader.getIndexCommit();
      Map<String, String> ud = c.getUserData();
      assertNotNull(ud.get("schema_ver"));
      assertNotNull(ud.get("analyzer_fp"));
      assertNotNull(ud.get("schema_fp"));
      assertNotNull(ud.get("field_catalog_hash"));
      assertNotNull(ud.get("synonyms_hash"));
      assertEquals(
          expectedMetaKeys(meta),
          ud.keySet().stream().filter(key -> !key.startsWith("commit_")).collect(Collectors.toSet()));
      // Compare a known value
      String expectedSchemaVer = String.valueOf(meta.build().get("schema_ver"));
      assertEquals(expectedSchemaVer, ud.get("schema_ver"));
      assertEquals("COMPLETE", ud.get("build_state"));
    }
  }

  private static java.util.Set<String> expectedMetaKeys(CommitMetadataSource meta) {
    java.util.Set<String> keys = new HashSet<>(meta.build().keySet());
    keys.add("build_state");
    return keys;
  }

  @Test
  void metadataSourceSupplierInvokedPerBuild() throws Exception {
    Path dir = Files.createTempDirectory("lucene-meta-supplier");
    AtomicInteger supplierCalls = new AtomicInteger();
    Supplier<CommitMetadataSource> supplier =
        () -> {
          supplierCalls.incrementAndGet();
          return () ->
              Map.of(
                  "schema_ver", "1.0.0",
                  "analyzer_fp", "fp",
                  "similarity_fp", "sim",
                  "boosts_fp", "boosts");
        };
    CommitMetadataValidator validator = metadata -> {};

    var runtime = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), supplier, validator).atPath(dir).open();
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "commit-supplier-1",
                SchemaFields.DOC_UID, "commit-supplier-1#0")));
    runtime.commitOps().commitAndTrack();
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "commit-supplier-2",
                SchemaFields.DOC_UID, "commit-supplier-2#0")));
    runtime.commitOps().commitAndTrack();
    runtime.close();

    // Supplier should be invoked once per commit (parity check is skipped on a fresh index).
    assertEquals(2, supplierCalls.get());
  }

  @Test
  void commitMetadataDisabledSkipsStamping() throws Exception {
    Path dir = Files.createTempDirectory("lucene-commit-disabled");
    Path cfg = Files.createTempFile("app-config-", ".yaml");
    Files.writeString(
        cfg,
        """
        index:
          commit:
            meta:
              enabled: false
          vector:
            dimension: 768
        """);
    String previous = System.getProperty("justsearch.config");
    System.setProperty("justsearch.config", cfg.toString());
    AtomicInteger validatorCalls = new AtomicInteger();
    CommitMetadataSource source = () -> Map.of("schema_ver", "ignored");
    CommitMetadataValidator validator = metadata -> validatorCalls.incrementAndGet();
    try {
      var runtime = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), source, validator).atPath(dir).open();
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(SchemaFields.DOC_ID, "commit-0", SchemaFields.DOC_UID, "commit-0#0")));
      runtime.commitOps().commitAndTrack();
      runtime.close();

      try (var d = new MMapDirectory(dir); var reader = DirectoryReader.open(d)) {
        Map<String, String> userData = reader.getIndexCommit().getUserData();
        assertTrue(userData.isEmpty(), "Expected no commit metadata when disabled");
      }
      assertEquals(0, validatorCalls.get(), "Validator should not be invoked when metadata disabled");
    } finally {
      if (previous == null) {
        System.clearProperty("justsearch.config");
      } else {
        System.setProperty("justsearch.config", previous);
      }
    }
  }

  @Test
  void commitWithInvalidMetadataFails() throws Exception {
    CommitMetadataSource badSource =
        () -> {
          Map<String, Object> m = new HashMap<>();
          m.put("schema_ver", "1.0.0");
          // Intentionally omit required fields like schema_fp
          return m;
        };
    CommitMetadataValidator validator = new RequiredFieldsCommitMetadataValidator();
    Path dir = Files.createTempDirectory("lucene-invalid-meta");
    var runtime =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4), badSource, validator).atPath(dir).open();
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(SchemaFields.DOC_ID, "invalid-1", SchemaFields.DOC_UID, "invalid-1#0")));
    assertThrows(IllegalStateException.class, runtime.commitOps()::commitAndTrack);
    runtime.close();
  }
}
