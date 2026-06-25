package io.justsearch.ui.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.function.LongFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 627 (N1): the Head detects an unclean *previous* session from a leftover runtime manifest
 * whose owning process is gone — the cross-session crash signal the in-process supervisor cannot
 * observe itself. The live-process start probe is injected so every branch is deterministic without
 * spawning real processes.
 *
 * <p>The fixtures embed a polymorphic {@code reachability.transports} block exactly like the real
 * manifest. This is a regression guard: an earlier implementation deserialized the whole manifest into
 * {@code RuntimeManifest}, which threw on that block and silently classified clean (a live-validation
 * miss). The classifier now reads only {@code pid}/{@code startedAt} via a JSON tree, so the reachability
 * block must not affect detection.
 */
@DisplayName("RuntimeManifestPublisher — previous-shutdown classification")
final class RuntimeManifestPublisherShutdownClassifyTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String STARTED_AT = "2026-06-21T18:21:45.459361700Z";
  private static final long STARTED_AT_MS = Instant.parse(STARTED_AT).toEpochMilli();

  /** Probe for a dead PID. */
  private static final LongFunction<OptionalLong> DEAD = p -> OptionalLong.empty();

  /** Writes a manifest JSON with the given pid, the fixed startedAt, AND a reachability block. */
  private static Path writeManifest(Path dir, long pid) throws Exception {
    Path manifestPath = dir.resolve("runtime").resolve("manifest.json");
    Files.createDirectories(manifestPath.getParent());
    String json =
        "{\n"
            + "  \"schemaVersion\": 1,\n"
            + "  \"instanceId\": \"prev-instance\",\n"
            + "  \"pid\": " + pid + ",\n"
            + "  \"startedAt\": \"" + STARTED_AT + "\",\n"
            + "  \"dataDir\": \"" + dir.toString().replace("\\", "\\\\") + "\",\n"
            + "  \"lifecycle\": \"READY\",\n"
            + "  \"head\": { \"apiPort\": 1234, \"apiBaseUrl\": \"http://127.0.0.1:1234\" },\n"
            + "  \"reachability\": { \"transports\": [\n"
            + "    { \"kind\": \"http-rest\", \"url\": \"http://127.0.0.1:1234/api\", \"audience\": \"public\" },\n"
            + "    { \"kind\": \"sse\", \"url\": \"http://127.0.0.1:1234/api/stream\", \"audience\": \"public\" }\n"
            + "  ] }\n"
            + "}\n";
    Files.writeString(manifestPath, json);
    return manifestPath;
  }

  @Test
  @DisplayName("leftover manifest (with reachability) + dead PID → unclean, carries the predecessor PID")
  void deadPidIsUnclean(@TempDir Path dir) throws Exception {
    Path manifestPath = writeManifest(dir, 12345L);
    var verdict = RuntimeManifestPublisher.classifyPreviousShutdown(manifestPath, MAPPER, 999L, DEAD);
    assertTrue(verdict.unclean(), "a leftover manifest whose PID is dead means the predecessor crashed");
    assertEquals(12345L, verdict.pid().orElseThrow());
  }

  @Test
  @DisplayName("PID alive but start-instant mismatch (PID reused) → unclean")
  void reusedPidIsUnclean(@TempDir Path dir) throws Exception {
    Path manifestPath = writeManifest(dir, 12345L);
    LongFunction<OptionalLong> reused = p -> OptionalLong.of(STARTED_AT_MS + 60_000L); // different start
    var verdict =
        RuntimeManifestPublisher.classifyPreviousShutdown(manifestPath, MAPPER, 999L, reused);
    assertTrue(verdict.unclean(), "a recycled PID means the predecessor is gone — narrate the crash");
  }

  @Test
  @DisplayName("PID alive with matching start-instant (same live process) → clean")
  void sameLiveProcessIsClean(@TempDir Path dir) throws Exception {
    Path manifestPath = writeManifest(dir, 12345L);
    LongFunction<OptionalLong> sameStart = p -> OptionalLong.of(STARTED_AT_MS);
    var verdict =
        RuntimeManifestPublisher.classifyPreviousShutdown(manifestPath, MAPPER, 999L, sameStart);
    assertFalse(verdict.unclean(), "a genuinely-alive same process must not raise a false crash claim");
  }

  @Test
  @DisplayName("leftover manifest whose PID equals our own → clean (defensive)")
  void ownPidIsClean(@TempDir Path dir) throws Exception {
    Path manifestPath = writeManifest(dir, 12345L);
    var verdict = RuntimeManifestPublisher.classifyPreviousShutdown(manifestPath, MAPPER, 12345L, DEAD);
    assertFalse(verdict.unclean());
  }

  @Test
  @DisplayName("no leftover manifest (clean shutdown / first boot) → clean")
  void absentManifestIsClean(@TempDir Path dir) throws Exception {
    Path manifestPath = dir.resolve("runtime").resolve("manifest.json");
    var verdict = RuntimeManifestPublisher.classifyPreviousShutdown(manifestPath, MAPPER, 999L, DEAD);
    assertFalse(verdict.unclean(), "absent manifest = clean shutdown or first boot");
  }
}
