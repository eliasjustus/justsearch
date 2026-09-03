/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Boots a real {@link KnowledgeServer} over a prepared generation layout.
 *
 * <p>Shared because the properties tempdoc 915 needs verified are properties of a BOOT, not of a
 * class: whether a mismatch is detected before the open mode is chosen, whether the Worker still
 * binds a port after the rebuild brake is spent. Every one of them is invisible to a unit test —
 * that is what open item O7 and the §C.8 regression both turned on.
 */
final class WorkerBootFixture {

  private WorkerBootFixture() {}

  /** The catalog the Worker itself loads, so a "matching" seed really does match. */
  static FieldCatalogDef productionCatalog() {
    return new JustSearchConfigurationLoader().loadFieldCatalog();
  }

  /**
   * Commits {@code docs} documents into {@code path}. {@code fingerprintOverride} of {@code null}
   * stamps what this runtime would write (a MATCHING index); a hex string stamps a foreign shape;
   * {@link #NO_FINGERPRINT} stamps none at all, which is what every pre-upgrade index looks like.
   */
  static final String NO_FINGERPRINT = "<<absent>>";

  static void seed(Path path, String fingerprintOverride, int docs) throws Exception {
    Map<String, Object> meta = new HashMap<>(new SsotCommitMetadataSource().build());
    if (NO_FINGERPRINT.equals(fingerprintOverride)) {
      meta.remove(IndexFingerprint.COMMIT_META_KEY);
    } else if (fingerprintOverride != null) {
      meta.put(IndexFingerprint.COMMIT_META_KEY, fingerprintOverride);
    }
    Map<String, Object> frozen = Map.copyOf(meta);
    try (RunningRuntime r =
        IndexSchema.fromCatalog(
                productionCatalog(), () -> frozen, new JsonSchemaCommitMetadataValidator())
            .atPath(path)
            .open()) {
      for (int i = 0; i < docs; i++) {
        r.indexingCoordinator()
            .indexSingle(
                new IndexDocument(
                    Map.of(
                        SchemaFields.DOC_ID, "seed-" + i,
                        SchemaFields.DOC_UID, "seed-" + i + "#0",
                        SchemaFields.CONTENT, "seeded document " + i)));
      }
      r.commitOps().commitAndTrack(CommitReason.DRAIN);
    }
  }

  /** Publishes a config pinning the data dir, the index base and the mismatch policy. */
  static void publishConfig(Path dataDir, Path indexBase, String policy) {
    ResolvedConfig rc =
        new ResolvedConfigBuilder()
            .contributeBaseSources()
            .putDefault("justsearch.data.dir", dataDir.toAbsolutePath().toString())
            .putDefault("justsearch.index.base_path", indexBase.toAbsolutePath().toString())
            // Un-prefixed on purpose (ResolvedConfigBuilder:1545). Getting it wrong is silent: the
            // policy falls back to the dev default and the branch under test never runs.
            .putDefault("index.schema_mismatch.policy", policy)
            .build();
    ConfigStore.setGlobal(new ConfigStore(rc));
  }

  static WorkerConfig workerConfig(Path dataDir) {
    ResolvedConfig rc = ConfigStore.global().get();
    return new WorkerConfig(
        "127.0.0.1",
        0,
        30_000L,
        128,
        64 * 1024 * 1024,
        dataDir,
        rc.search().collection(),
        60_000L,
        "0.0.0-test",
        new SsotCommitMetadataSource().build(),
        "test-manifest",
        500L,
        "block");
  }

  /** A data directory with an initialised generation layout. */
  static Layout layout(Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path indexBase = dataDir.resolve("index");
    Files.createDirectories(dataDir);
    IndexGenerationManager genManager = new IndexGenerationManager(indexBase);
    var l = genManager.initializeOrLoad();
    return new Layout(
        dataDir,
        indexBase,
        genManager,
        genManager.resolveGenerationPathStrict(l.state().active_generation()));
  }

  record Layout(
      Path dataDir, Path indexBase, IndexGenerationManager genManager, Path activePath) {}
}
