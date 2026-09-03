package io.justsearch.indexerworker.splade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 915 §C — a model directory with no model file in it is a determinate "no SPLADE model
 * here", not an unanswered question.
 *
 * <p>The distinction is load-bearing in one direction only, which is why it is worth a test: an
 * INDETERMINATE answer makes {@code IndexFingerprint.compute} return empty, so nothing is stamped
 * and nothing is compared — the parity check silently switches itself off and stays off. Reading a
 * missing file as "no answer" would do that on every install that has no SPLADE model, which is
 * most of them.
 */
final class SpladeFingerprintTriStateTest {

  @AfterEach
  void reset() {
    SpladeFingerprint.invalidate();
  }

  private static void publishConfigWithSpladePath(Path path) {
    ConfigStore.setGlobal(
        new ConfigStore(
            ResolvedConfig.builder()
                .contributeBaseSources()
                .putDefault("justsearch.splade.model_path", path.toAbsolutePath().toString())
                .build()));
  }

  @Test
  void aDirectoryWithNoModelFileIsNotConfiguredRatherThanIndeterminate(@TempDir Path tempDir)
      throws Exception {
    Path emptyModelDir = tempDir.resolve("splade-empty");
    Files.createDirectories(emptyModelDir);
    publishConfigWithSpladePath(emptyModelDir);
    SpladeFingerprint.invalidate();

    assertTrue(
        SpladeFingerprint.get().isEmpty(), "no model file means no digest");
    assertTrue(
        SpladeFingerprint.modelPath().isEmpty(),
        "a directory with no model file must not present itself as a resolved model path");

    IndexFingerprint.ModelFingerprint fp =
        IndexFingerprint.ModelFingerprint.of(
            SpladeFingerprint.modelPath().isPresent(), SpladeFingerprint.get());
    assertEquals(
        IndexFingerprint.ModelState.NOT_CONFIGURED,
        fp.state(),
        "an absent SPLADE model must leave index_fingerprint computable, not switch parity off");
  }
}
