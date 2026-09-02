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
              + " modules/ui/build.gradle.kts.");
    }
  }

  /**
   * The indexing-cadence knobs an eval arm selects per run (tempdoc 885 item 19 and its tracked
   * commit-timer follow-up, made configurable in #605).
   *
   * <p>Listed explicitly rather than self-discovered by prefix: most of {@code EnvRegistry} has no
   * business on the eval allowlist, so a prefix rule here would either over-forward or drift into
   * meaninglessness. These are the keys an arm sets to differ from control, which is exactly the
   * set whose silent absence produces a WRONG measurement rather than a failure — the arm runs the
   * default and the comparison table reads "no difference". 885's own window lost an arm to that
   * shape twice.
   */
  private static final Set<String> CADENCE_ARM_ENV_KEYS =
      Set.of(
          "JUSTSEARCH_INDEX_NRT_MODE",
          "JUSTSEARCH_INDEX_NRT_BACKGROUND_REOPEN_MS",
          "JUSTSEARCH_INDEX_NRT_ON_DEMAND_MAX_STALE_MS",
          "JUSTSEARCH_BACKFILL_COMMIT_INTERVAL_MS",
          "JUSTSEARCH_BACKFILL_MAX_DOCS_BEFORE_COMMIT",
          "JUSTSEARCH_INDEX_COMMIT_TIMER_INTERVAL_MS",
          "JUSTSEARCH_EXTRACTION_SANDBOX_MODE");

  @Test
  @DisplayName("Every indexing-cadence arm knob is forwarded by the eval-mode allowlist")
  void everyCadenceArmEnvKeyReachesEvalLaunch() throws IOException {
    String script = stripKotlinComments(Files.readString(resolveBuildScript()));

    StringBuilder missing = new StringBuilder();
    for (String key : CADENCE_ARM_ENV_KEYS) {
      // Each key must ALSO still be declared in EnvRegistry — a key forwarded by the build script
      // but deleted from the registry is a dead entry, and one declared but unforwarded is the
      // silent-drop this pins. Both directions are checked so the pair cannot drift apart.
      boolean declared = false;
      for (EnvRegistry candidate : EnvRegistry.values()) {
        if (candidate.envVar().equals(key)) {
          declared = true;
          break;
        }
      }
      if (!declared) {
        missing.append("\n  - ").append(key).append(" (no longer declared in EnvRegistry)");
      } else if (!script.contains("\"" + key + "\"")) {
        missing.append("\n  - ").append(key).append(" (declared, but not on the allowlist)");
      }
    }
    if (missing.length() > 0) {
      fail(
          "modules/ui/build.gradle.kts must forward every indexing-cadence knob via"
              + " HEADLESS_AI_ENV_VARS. Eval mode (runHeadlessEval, and therefore jseval"
              + " --start-backend) silently filters anything not on that list, so the arm launches"
              + " with the DEFAULT value and its comparison against control reads 'no difference'"
              + " for the wrong reason — a wrong measurement, not a visible failure. Problems:"
              + missing
              + "\n\nFix: add each missing key to HEADLESS_AI_ENV_VARS in"
              + " modules/ui/build.gradle.kts, in the tempdoc 885 cadence block.");
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
