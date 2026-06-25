package io.justsearch.app.services.observability.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("RuleCatalog")
final class RuleCatalogTest {

  @Test
  @DisplayName("missing classpath dir → empty catalog (no error)")
  void missingDirEmpty(@TempDir Path tmp) throws IOException {
    // Null parent classloader: pure isolation. Without this, the production
    // memory-pressure.yaml on the test classpath would leak in.
    URLClassLoader loader = new URLClassLoader(new URL[] {tmp.toUri().toURL()}, null);
    RuleCatalog catalog = RuleCatalog.fromClasspath("nonexistent/dir", loader);
    assertEquals(0, catalog.size());
  }

  @Test
  @DisplayName("loads a YAML rule from a filesystem-backed classpath")
  void loadsYamlFromFilesystem(@TempDir Path tmp) throws IOException {
    Path rulesDir = tmp.resolve("rules/operational-signals");
    Files.createDirectories(rulesDir);
    Files.writeString(
        rulesDir.resolve("memory-pressure.yaml"),
        """
        rule: memory-pressure
        kind: threshold
        emits:
          id: memory.pressure
          subject: head.memory
          reason: MemoryPressureHigh
          severity: WARNING
        expr_cel: 'true'
        for: 60s
        keep_firing_for: 30s
        """);

    // Null parent classloader: pure isolation. Without this, the production
    // memory-pressure.yaml on the test classpath would leak in.
    URLClassLoader loader = new URLClassLoader(new URL[] {tmp.toUri().toURL()}, null);
    RuleCatalog catalog = RuleCatalog.fromClasspath("rules/operational-signals", loader);

    assertEquals(1, catalog.size());
    Rule rule = catalog.findByName("memory-pressure");
    assertNotNull(rule);
    assertEquals("memory.pressure", rule.emits().id());
    assertTrue(catalog.rules().contains(rule));
  }

  @Test
  @DisplayName("loads multiple YAML rules and orders deterministically")
  void loadsMultipleRulesDeterministic(@TempDir Path tmp) throws IOException {
    Path rulesDir = tmp.resolve("rules/operational-signals");
    Files.createDirectories(rulesDir);
    Files.writeString(rulesDir.resolve("a.yaml"), simpleRule("rule-a", "a.b"));
    Files.writeString(rulesDir.resolve("b.yaml"), simpleRule("rule-b", "c.d"));

    // Null parent classloader: pure isolation. Without this, the production
    // memory-pressure.yaml on the test classpath would leak in.
    URLClassLoader loader = new URLClassLoader(new URL[] {tmp.toUri().toURL()}, null);
    RuleCatalog c1 = RuleCatalog.fromClasspath("rules/operational-signals", loader);
    RuleCatalog c2 = RuleCatalog.fromClasspath("rules/operational-signals", loader);

    assertEquals(2, c1.size());
    // Order must be stable across two loads (resource enumeration is sorted).
    assertEquals(
        c1.rules().stream().map(Rule::name).toList(),
        c2.rules().stream().map(Rule::name).toList());
  }

  @Test
  @DisplayName("malformed YAML aborts loading (build-time bug, not graceful degradation)")
  void malformedYamlAborts(@TempDir Path tmp) throws IOException {
    Path rulesDir = tmp.resolve("rules/operational-signals");
    Files.createDirectories(rulesDir);
    Files.writeString(
        rulesDir.resolve("malformed.yaml"),
        """
        rule: malformed
        kind: bogus_kind
        """);

    // Null parent classloader: pure isolation. Without this, the production
    // memory-pressure.yaml on the test classpath would leak in.
    URLClassLoader loader = new URLClassLoader(new URL[] {tmp.toUri().toURL()}, null);
    try {
      RuleCatalog.fromClasspath("rules/operational-signals", loader);
      org.junit.jupiter.api.Assertions.fail("expected exception");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("kind='bogus_kind'"));
    }
  }

  private static String simpleRule(String name, String id) {
    return """
        rule: %s
        kind: condition
        emits:
          id: %s
          subject: x
          reason: SimpleReason
          severity: INFO
        expr_cel: 'true'
        for: 0s
        keep_firing_for: 0s
        """
        .formatted(name, id);
  }
}
