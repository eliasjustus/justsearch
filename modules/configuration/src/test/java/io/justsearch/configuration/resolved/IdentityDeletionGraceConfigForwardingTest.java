/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.EnvRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 931 §C.6 — the forwarding proof for {@code index.identity.deletion_grace_ms}.
 *
 * <p>Same shape and same reason as {@code CommitTimerConfigForwardingTest}: the document-identity
 * store lives in the WORKER, so this key must arrive through the ordinal-450 config snapshot. A
 * knob whose only setter lives on the Head is unreachable by construction, so the whole Head →
 * snapshot → Worker path is walked rather than asserting the key exists.
 */
final class IdentityDeletionGraceConfigForwardingTest {

  private static final String KEY = "index.identity.deletion_grace_ms";

  @Test
  @DisplayName("the key is declared in EnvRegistry with a 30-day default")
  void keyIsDeclaredWithTheDocumentedDefault() {
    assertEquals(KEY, EnvRegistry.INDEX_IDENTITY_DELETION_GRACE_MS.sysProp());
    assertEquals(
        "JUSTSEARCH_INDEX_IDENTITY_DELETION_GRACE_MS",
        EnvRegistry.INDEX_IDENTITY_DELETION_GRACE_MS.envVar());
    assertEquals("2592000000", EnvRegistry.INDEX_IDENTITY_DELETION_GRACE_MS.defaultValue());
    assertEquals(
        Duration.ofDays(30).toMillis(),
        Long.parseLong(EnvRegistry.INDEX_IDENTITY_DELETION_GRACE_MS.defaultValue()),
        "the documented default is 30 days; a digit slip here silently changes when a replacement"
            + " file inherits the previous document's feedback");
  }

  @Test
  @DisplayName("the default resolves onto ResolvedConfig.Index")
  void defaultResolvesOntoTheRecord() {
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    builder.contributeEnvRegistry();

    assertEquals(
        Duration.ofDays(30).toMillis(), builder.build().index().identityDeletionGraceMs());
    assertEquals(
        ResolvedConfig.Index.DEFAULT_IDENTITY_DELETION_GRACE_MS,
        builder.build().index().identityDeletionGraceMs(),
        "the registry default and the code default must be the same number, not two");
  }

  @Test
  @DisplayName("the value exceeds int range and must resolve as a long")
  void thirtyDaysDoesNotFitInAnInt() {
    assertTrue(
        ResolvedConfig.Index.DEFAULT_IDENTITY_DELETION_GRACE_MS > Integer.MAX_VALUE,
        "resolving this key as an int would silently truncate the default window");
  }

  @Test
  @DisplayName("a Head-side override survives the worker snapshot round-trip the Worker reads")
  void overrideReachesTheWorkerThroughTheSnapshot(@TempDir Path tmp) throws IOException {
    ResolvedConfigBuilder head = new ResolvedConfigBuilder();
    head.contributeEnvRegistry();
    head.put(KEY, 500, "jvm_arg", KEY, "86400000");
    ResolvedConfig headConfig = head.build();
    assertEquals(86_400_000L, headConfig.index().identityDeletionGraceMs());

    Path snapshot = tmp.resolve("worker-config-snapshot.json");
    headConfig.toWorkerSnapshot(snapshot);
    assertTrue(
        Files.readString(snapshot).contains(KEY),
        "the key must actually be written to the snapshot");

    ResolvedConfigBuilder worker = new ResolvedConfigBuilder();
    worker.contributeWorkerSnapshot(snapshot);
    worker.contributeEnvRegistry();

    assertEquals(
        86_400_000L,
        worker.build().index().identityDeletionGraceMs(),
        "the Worker must see the Head's window, not the default");
  }

  @Test
  @DisplayName("an unparseable value falls back to the default rather than to zero")
  void unparseableValueFallsBackToTheDefault() {
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    builder.contributeEnvRegistry();
    builder.put(KEY, 500, "jvm_arg", KEY, "thirty days");

    assertEquals(
        ResolvedConfig.Index.DEFAULT_IDENTITY_DELETION_GRACE_MS,
        builder.build().index().identityDeletionGraceMs(),
        "a typo must not collapse the window to zero and re-mint every returning file");
  }
}
