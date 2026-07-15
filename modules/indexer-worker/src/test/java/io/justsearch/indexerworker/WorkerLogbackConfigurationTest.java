package io.justsearch.indexerworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Pins the privacy fix: the Worker's {@code io.justsearch} logger defaults to INFO, not DEBUG,
 * when {@code JUSTSEARCH_LOG_LEVEL} is unset.
 *
 * <p>Several call sites (e.g. {@code ChunkSearchOps}, {@code HybridSearchOps},
 * {@code TextQueryOps}, {@code SearchExecutor}) intentionally log raw user query/chat text at
 * DEBUG/TRACE per the WARN/DEBUG split documented in
 * {@code docs/reference/contributing/logging-conventions.md} ("Query Text Redaction") — that
 * convention only holds if DEBUG is off by default. Before this fix, {@code logback.xml}
 * hardcoded {@code <logger name="io.justsearch" level="DEBUG" />}, so every Worker boot wrote
 * query text to {@code worker.log} in plaintext. {@code DiagnosticsServiceImpl} then bundles that
 * log file into the diagnostics export ZIP with path-only redaction
 * ({@code addDirectoryRedacted} → {@code addFileRedactedStreaming} → {@code redactPaths}) — no
 * query/content redaction — so the plaintext leaves the machine on every diagnostics export.
 *
 * <p>This test would have failed before the fix: it asserts {@link Level#INFO}, and the
 * pre-fix {@code logback.xml} resolved {@code io.justsearch} to {@link Level#DEBUG}
 * unconditionally, regardless of environment.
 */
final class WorkerLogbackConfigurationTest {

  @Test
  void logbackXmlIsLoadedFromClasspath() {
    // If no logback.xml were found, Logback's BasicConfigurator would kick in and the
    // io.justsearch/root level assertions below would observe DEBUG, not INFO — this just
    // confirms a real (non-null) LoggerContext backs those assertions.
    LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
    assertNotNull(ctx, "Logback LoggerContext must be present");
  }

  @Test
  void ioJustsearchDefaultsToInfoWhenEnvVarUnset() {
    // This process was launched by Gradle without JUSTSEARCH_LOG_LEVEL set (the CI/local test
    // task does not set it), so the ${JUSTSEARCH_LOG_LEVEL:-INFO} substitution in
    // modules/indexer-worker/src/main/resources/logback.xml must resolve to its default: INFO.
    assertNull(
        System.getenv("JUSTSEARCH_LOG_LEVEL"),
        "This test assumes JUSTSEARCH_LOG_LEVEL is unset in the test environment; if a build "
            + "config sets it, this test's premise no longer holds and it must be adapted.");

    Logger ioJustsearch = (Logger) LoggerFactory.getLogger("io.justsearch");
    assertEquals(
        Level.INFO,
        ioJustsearch.getLevel(),
        "io.justsearch must default to INFO so query/chat text logged at DEBUG/TRACE by "
            + "ChunkSearchOps/HybridSearchOps/TextQueryOps/SearchExecutor stays out of "
            + "worker.log by default (and therefore out of the diagnostics export ZIP, which "
            + "bundles worker.log with path-only redaction). If this reads DEBUG, the "
            + "logback.xml default regressed back to the pre-fix hardcoded DEBUG level.");
  }

  @Test
  void rootLevelIsInfo() {
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    assertEquals(Level.INFO, root.getLevel(), "Root logger must remain INFO.");
  }
}
