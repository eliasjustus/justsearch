/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 811 (C-2a) — the ONE authority for ad-hoc-ingest collection tagging. Pre-811 both ingest
 * surfaces passed a literal {@code null}, so their documents were unlabeled and unprunable.
 */
@DisplayName("IngestCollectionPolicy")
final class IngestCollectionPolicyTest {

  private static Path p(String s) {
    return Path.of(s).toAbsolutePath().normalize();
  }

  @Test
  @DisplayName("a path under no watched root resolves to mcp-ingest (was null pre-811)")
  void outOfRootGetsDefaultTag() {
    assertEquals(
        IngestCollectionPolicy.OUT_OF_ROOT,
        IngestCollectionPolicy.resolve(null, p("build/tmp/811/loose/file.txt"), List.of()));
  }

  @Test
  @DisplayName("a path under a watched root inherits that root's collection")
  void inRootInheritsCollection() {
    var roots = List.of(new IngestCollectionPolicy.RootBinding(p("build/tmp/811/docs"), "notes"));
    assertEquals(
        "notes",
        IngestCollectionPolicy.resolve(null, p("build/tmp/811/docs/deep/file.md"), roots));
  }

  @Test
  @DisplayName("an in-root path under a root with NO collection stays on the index default (null)")
  void inRootWithNoCollectionStaysDefault() {
    var roots = List.of(new IngestCollectionPolicy.RootBinding(p("build/tmp/811/docs"), null));
    assertNull(IngestCollectionPolicy.resolve(null, p("build/tmp/811/docs/file.md"), roots));
  }

  @Test
  @DisplayName("the most specific (deepest) containing root wins")
  void deepestRootWins() {
    var roots =
        List.of(
            new IngestCollectionPolicy.RootBinding(p("build/tmp/811"), "outer"),
            new IngestCollectionPolicy.RootBinding(p("build/tmp/811/docs"), "inner"));
    assertEquals("inner", IngestCollectionPolicy.resolve(null, p("build/tmp/811/docs/f.md"), roots));
  }

  @Test
  @DisplayName("a sibling sharing only a textual prefix is NOT contained")
  void textualPrefixIsNotContainment() {
    var roots = List.of(new IngestCollectionPolicy.RootBinding(p("build/tmp/811/docs"), "notes"));
    assertEquals(
        IngestCollectionPolicy.OUT_OF_ROOT,
        IngestCollectionPolicy.resolve(null, p("build/tmp/811/docs-archive/f.md"), roots),
        "Path.startsWith is element-wise; 'docs-archive' must not match root 'docs'");
  }

  @Test
  @DisplayName("an explicit collection wins over root inheritance and is trimmed")
  void explicitWins() {
    var roots = List.of(new IngestCollectionPolicy.RootBinding(p("build/tmp/811/docs"), "notes"));
    assertEquals(
        "research",
        IngestCollectionPolicy.resolve("  research  ", p("build/tmp/811/docs/f.md"), roots));
  }

  @Test
  @DisplayName("absent collection normalizes to null; blank is rejected")
  void normalizeRequested() {
    assertNull(IngestCollectionPolicy.normalizeRequested(null));
    assertEquals("notes", IngestCollectionPolicy.normalizeRequested(" notes "));
    var blank = assertThrows(
        IllegalArgumentException.class, () -> IngestCollectionPolicy.normalizeRequested("   "));
    assertTrue(blank.getMessage().contains("non-empty"), blank.getMessage());
  }

  @Test
  @DisplayName("reserved app-internal collections are rejected, case-insensitively")
  void reservedRejected() {
    for (String reserved : List.of("agent-history", "justsearch-help", "Agent-History", " AGENT-HISTORY ")) {
      var e = assertThrows(
          IllegalArgumentException.class,
          () -> IngestCollectionPolicy.normalizeRequested(reserved),
          reserved + " must be rejected");
      assertTrue(e.getMessage().contains("reserved"), e.getMessage());
    }
  }

  @Test
  @DisplayName("isDeletable refuses reserved collections, the default bucket, and blanks")
  void deletability() {
    assertTrue(IngestCollectionPolicy.isDeletable(IngestCollectionPolicy.OUT_OF_ROOT));
    assertTrue(IngestCollectionPolicy.isDeletable("work-notes"));
    assertFalse(IngestCollectionPolicy.isDeletable("agent-history"));
    assertFalse(IngestCollectionPolicy.isDeletable("justsearch-help"));
    assertFalse(IngestCollectionPolicy.isDeletable("default"));
    assertFalse(IngestCollectionPolicy.isDeletable("DEFAULT"));
    assertFalse(IngestCollectionPolicy.isDeletable(""));
    assertFalse(IngestCollectionPolicy.isDeletable(null));
  }
}
