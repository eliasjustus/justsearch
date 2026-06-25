package io.justsearch.indexing.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F3: Drift-prevention test for SECTION_SEPARATOR constant.
 *
 * <p>Validates that:
 * <ol>
 *   <li>ContextBudgeter defines the canonical separator value</li>
 *   <li>TokenAwareBudgeter delegates to ContextBudgeter (no duplicate definition)</li>
 * </ol>
 *
 * <p>This test prevents drift where different classes define their own separator
 * literals that could diverge over time.
 */
@DisplayName("SeparatorConstantDrift")
class SeparatorConstantDriftTest {

  @Test
  @DisplayName("ContextBudgeter defines canonical separator value")
  void canonicalSeparatorValueIsCorrect() {
    // The canonical separator value must be "\n\n---\n\n"
    // This is the single source of truth for all RAG context formatting
    assertEquals("\n\n---\n\n", ContextBudgeter.SECTION_SEPARATOR,
        "Canonical separator must be '\\n\\n---\\n\\n'");
  }

  @Test
  @DisplayName("TokenAwareBudgeter uses ContextBudgeter.SECTION_SEPARATOR (no duplicate)")
  void tokenAwareBudgeterDelegatesToCanonical() {
    // TokenAwareBudgeter.SECTION_SEPARATOR should be the exact same reference
    // as ContextBudgeter.SECTION_SEPARATOR (not a duplicate String literal)
    assertSame(
        ContextBudgeter.SECTION_SEPARATOR,
        TokenAwareBudgeter.SECTION_SEPARATOR,
        "TokenAwareBudgeter.SECTION_SEPARATOR must delegate to ContextBudgeter.SECTION_SEPARATOR");
  }

  @Test
  @DisplayName("Both classes expose same separator value")
  void bothClassesExposeIdenticalValue() {
    // Even if String interning happens, the values must be equal
    assertEquals(
        ContextBudgeter.SECTION_SEPARATOR,
        TokenAwareBudgeter.SECTION_SEPARATOR,
        "Separator values must be identical");
  }

  /**
   * Allowlisted production files that may contain the raw separator literal.
   * Any new file containing {@code "\n\n---\n\n"} must either use the canonical constant
   * or be added here with justification.
   */
  private static final Set<String> RAW_LITERAL_ALLOWLIST =
      // SelectionContextInjector pre-dates this worktree and already shipped on main with the raw
      // literal (no canonical constant is reachable across its module boundary). Allowlisted here via
      // the test's sanctioned escape; the structural follow-up (a shared separator constant usable
      // from app-services) is logged in observations.md.
      Set.of("ContextBudgeter.java", "DocumentService.java", "SelectionContextInjector.java");

  @Test
  @DisplayName("No raw separator literals outside allowlisted files")
  void noRawSeparatorLiteralsOutsideAllowlist() throws IOException {
    // Resolve project root: user.dir is typically modules/indexing during Gradle test
    Path userDir = Path.of(System.getProperty("user.dir"));
    Path projectRoot = userDir;
    // Walk up until we find settings.gradle.kts (project root marker)
    while (projectRoot != null && !Files.exists(projectRoot.resolve("settings.gradle.kts"))) {
      projectRoot = projectRoot.getParent();
    }
    if (projectRoot == null) {
      // Can't determine project root — skip gracefully in unusual environments
      return;
    }

    Path modulesDir = projectRoot.resolve("modules");
    assertTrue(Files.isDirectory(modulesDir), "modules/ directory must exist");

    // The raw literal as it appears in Java source: "\n\n---\n\n"
    String rawLiteral = "\"\\n\\n---\\n\\n\"";

    // Walk all src/main/**/*.java files and check for the raw literal
    List<String> violations;
    try (Stream<Path> paths = Files.walk(modulesDir)) {
      violations =
          paths
              .filter(p -> p.toString().endsWith(".java"))
              .filter(p -> p.toString().contains("src" + p.getFileSystem().getSeparator() + "main"))
              .filter(
                  p -> {
                    try {
                      return Files.readString(p).contains(rawLiteral);
                    } catch (IOException e) {
                      return false;
                    }
                  })
              .filter(p -> !RAW_LITERAL_ALLOWLIST.contains(p.getFileName().toString()))
              .map(p -> modulesDir.relativize(p).toString())
              .sorted()
              .collect(Collectors.toList());
    }

    assertTrue(
        violations.isEmpty(),
        "Found raw separator literal \"\\n\\n---\\n\\n\" outside allowlisted files. "
            + "Use ContextBudgeter.SECTION_SEPARATOR instead.\nViolating files:\n  "
            + String.join("\n  ", violations));
  }
}
