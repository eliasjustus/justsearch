/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.agent.api.registry.OperationResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Proof for tempdoc 877 §2.6: {@link HandlerJson} replaces 19 per-handler {@code ObjectMapper}
 * fields (three spellings) and 9 duplicated {@code "Invalid args: "} failure sites with one
 * holder each.
 */
final class HandlerJsonTest {

  @Test
  void invalidArgsMatchesTheOriginalPerHandlerMessageVerbatim() {
    OperationResult result = HandlerJson.invalidArgs(new RuntimeException("boom"));

    assertFalse(result.success());
    assertEquals("Invalid args: boom", result.message());
  }

  /**
   * Guards the fold: exactly one {@code ObjectMapper}/{@code JsonMapper} construction site may
   * exist among this package's handler sources, and it must live in {@link HandlerJson} — a
   * re-introduced per-handler mapper fails this test instead of silently sprawling again.
   */
  @Test
  void exactlyOneMapperConstructionSiteExistsInThePackageAndItIsHandlerJson() throws IOException {
    Path handlersDir = findHandlersSourceDir();
    assumeTrue(
        handlersDir != null,
        "SKIPPED: could not locate the handlers source dir by walking up from "
            + Path.of("").toAbsolutePath()
            + " to a directory containing settings.gradle.kts");

    Pattern constructionSite =
        Pattern.compile("new ObjectMapper\\(\\)|new JsonMapper\\(\\)|JsonMapper\\.builder\\(\\)");

    List<String> hits;
    try (Stream<Path> files = Files.list(handlersDir)) {
      hits =
          files
              .filter(p -> p.getFileName().toString().endsWith(".java"))
              .flatMap(p -> matchingLines(p, constructionSite))
              .toList();
    }

    assertEquals(1, hits.size(), "expected exactly one mapper construction site: " + hits);
    assertTrue(
        hits.get(0).startsWith("HandlerJson.java:"),
        "the one construction site must live in HandlerJson.java: " + hits);
  }

  /**
   * Lines matching {@code pattern}, excluding javadoc-continuation lines (trimmed content
   * starting with {@code *}) — otherwise this file's own javadoc, which names the three
   * spellings in prose, would match itself.
   */
  private static Stream<String> matchingLines(Path file, Pattern pattern) {
    try {
      return Files.readAllLines(file).stream()
          .filter(line -> !line.strip().startsWith("*"))
          .filter(line -> pattern.matcher(line).find())
          .map(line -> file.getFileName() + ":" + line.strip());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Walks up from the working directory to the repo root (holds {@code settings.gradle.kts}). */
  private static Path findHandlersSourceDir() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      if (Files.exists(dir.resolve("settings.gradle.kts"))) {
        Path candidate =
            dir.resolve("modules")
                .resolve("app-services")
                .resolve("src")
                .resolve("main")
                .resolve("java")
                .resolve("io")
                .resolve("justsearch")
                .resolve("app")
                .resolve("services")
                .resolve("registry")
                .resolve("operations")
                .resolve("handlers");
        return Files.isDirectory(candidate) ? candidate : null;
      }
      dir = dir.getParent();
    }
    return null;
  }
}
