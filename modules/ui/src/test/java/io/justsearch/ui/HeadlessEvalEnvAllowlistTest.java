package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.configuration.EnvRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression pin for the eval-mode env-var forwarding contract (validation finding 2026-04-26,
 * fix shipped as commit {@code 8bc40fdd1}).
 *
 * <p>Production env-inheritance works naturally — every env var in the operator's shell reaches
 * the launched JVM. Eval mode (Gradle {@code runHeadlessEval}) is different: {@code
 * applyHeadlessEvalContract} in {@code modules/ui/build.gradle.kts} maintains a whitelist
 * ({@code HEADLESS_AI_ENV_VARS}) and forwards only listed env vars. Anything not on the list is
 * silently filtered, so an env-var-driven feature can pass every unit test, work in production,
 * and still be invisible to operators running eval-mode harnesses (jseval, validation scripts,
 * search-quality regression suites).
 *
 * <p>The original gap: tempdoc 410 Slice B added three {@code JUSTSEARCH_INGESTION_SKIP_*} env
 * keys to {@code EnvRegistry}, the {@code IngestionSkipPolicy} consumed them correctly, every
 * unit test passed — but the keys weren't on the eval-mode allowlist, so eval-mode operators
 * could never exercise the new feature. The validation harness empirically discovered the
 * gap. This test prevents recurrence by self-discovering every {@code JUSTSEARCH_INGESTION_*}
 * key from {@code EnvRegistry} and asserting each appears literally in the build script.
 */
class HeadlessEvalEnvAllowlistTest {

  @Test
  @DisplayName(
      "Every JUSTSEARCH_INGESTION_* env key from EnvRegistry is forwarded by the eval-mode allowlist")
  void everyIngestionEnvKeyReachesEvalLaunch() throws IOException {
    Set<String> ingestionKeys = new LinkedHashSet<>();
    for (EnvRegistry key : EnvRegistry.values()) {
      if (key.envVar().startsWith("JUSTSEARCH_INGESTION_")) {
        ingestionKeys.add(key.envVar());
      }
    }
    assertFalse(
        ingestionKeys.isEmpty(),
        "EnvRegistry should declare at least one JUSTSEARCH_INGESTION_* env key — this test"
            + " becomes meaningless if the prefix changes; update the test alongside the rename.");

    String script = stripKotlinComments(Files.readString(resolveBuildScript()));

    StringBuilder missing = new StringBuilder();
    for (String key : ingestionKeys) {
      // Match the literal quoted string the build script uses to declare the allowlist entry.
      // Comment-stripping above ensures we don't accept a commented-out reference as proof of
      // allowlist membership — that was the design flaw observed during pin-verification on
      // 2026-04-26 (the test passed against a build script that had the key only inside a
      // line comment).
      if (!script.contains("\"" + key + "\"")) {
        missing.append("\n  - ").append(key);
      }
    }
    if (missing.length() > 0) {
      fail(
          "modules/ui/build.gradle.kts must forward these JUSTSEARCH_INGESTION_* env keys via"
              + " HEADLESS_AI_ENV_VARS — eval mode (runHeadlessEval) silently filters anything not"
              + " on that list, so unit tests pass while operators see a feature that does"
              + " nothing. Missing keys:"
              + missing
              + "\n\nFix: add each missing key to the HEADLESS_AI_ENV_VARS list in"
              + " modules/ui/build.gradle.kts (~line 1620).");
    }
  }

  /**
   * Strips Kotlin comments (line and block) so commented-out allowlist entries don't satisfy
   * the contains-check. Catches the failure mode observed during pin-verification: a developer
   * comments out an entry while debugging and the test still passes.
   */
  private static String stripKotlinComments(String script) {
    String withoutBlock = script.replaceAll("(?s)/\\*.*?\\*/", "");
    StringBuilder out = new StringBuilder(withoutBlock.length());
    for (String line : withoutBlock.split("\\r?\\n", -1)) {
      int idx = line.indexOf("//");
      out.append(idx < 0 ? line : line.substring(0, idx)).append('\n');
    }
    return out.toString();
  }

  /**
   * Resolves {@code build.gradle.kts} relative to the module root. Gradle runs tests with the
   * module directory as the working dir, so the relative path is the canonical resolution. The
   * absolute-path conversion improves the failure message if the file is missing.
   */
  private static Path resolveBuildScript() {
    Path candidate = Path.of("build.gradle.kts").toAbsolutePath();
    if (!Files.exists(candidate)) {
      throw new IllegalStateException(
          "build.gradle.kts not found at " + candidate + " — Gradle test working dir convention"
              + " (cwd = module root) appears to have changed; update resolveBuildScript().");
    }
    return candidate;
  }
}
