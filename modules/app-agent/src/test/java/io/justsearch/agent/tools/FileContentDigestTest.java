/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 909 items 7/8 — the content identity a COPY-undo deletes against.
 *
 * <p>The size cap matters as much as the hash: hashing is a full extra read of what was just
 * copied, on the tool-call thread and again at undo, so a multi-gigabyte copy is not verified at
 * all. What the cap must NOT do is silently skip verification and delete anyway — an unverifiable
 * copy has to reach the same preserve branch a pre-v2 journal reaches, which is what
 * "no digest" means downstream.
 */
final class FileContentDigestTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("a file's digest changes with its content, not with its timestamp")
  void fileDigestTracksContentNotMtime() throws IOException {
    Path file = tempDir.resolve("doc.txt");
    Files.writeString(file, "original");
    String before = FileContentDigest.of(file);
    assertTrue(before.startsWith("sha256:"), before);

    // The exact defect this exists for: a write that restores the original timestamp.
    var asWritten = Files.getLastModifiedTime(file);
    Files.writeString(file, "the user's edit");
    Files.setLastModifiedTime(file, asWritten);

    assertNotEquals(before, FileContentDigest.of(file));
    assertFalse(FileContentDigest.matches(file, before));
  }

  @Test
  @DisplayName("a tree's digest is stable across walk order and changes on a nested edit")
  void treeDigestCoversNestedContent() throws IOException {
    Path tree = tempDir.resolve("tree");
    Files.createDirectories(tree.resolve("a"));
    Files.createDirectories(tree.resolve("b"));
    Files.writeString(tree.resolve("a").resolve("1.txt"), "one");
    Files.writeString(tree.resolve("b").resolve("2.txt"), "two");

    String before = FileContentDigest.of(tree);
    assertTrue(before.startsWith("tree-sha256:"), before);
    assertEquals(before, FileContentDigest.of(tree), "the digest must be order-stable");

    Files.writeString(tree.resolve("b").resolve("2.txt"), "two, edited");
    assertNotEquals(before, FileContentDigest.of(tree));
  }

  @Test
  @DisplayName("a file over the cap records NO digest rather than an unverified delete licence")
  void oversizedFileIsNotDigested() throws IOException {
    Path file = tempDir.resolve("big.bin");
    Files.writeString(file, "0123456789");

    assertNull(FileContentDigest.of(file, 4), "over the bound: no identity is recorded");
    assertNotNull(FileContentDigest.of(file, 10), "at the bound: still verified");
  }

  @Test
  @DisplayName("a tree over the cap is sized from metadata and not hashed")
  void oversizedTreeIsNotDigested() throws IOException {
    Path tree = tempDir.resolve("big-tree");
    Files.createDirectories(tree);
    Files.writeString(tree.resolve("a.txt"), "0123456789");
    Files.writeString(tree.resolve("b.txt"), "0123456789");

    assertNull(FileContentDigest.of(tree, 15), "20 bytes of content against a 15-byte bound");
    assertNotNull(FileContentDigest.of(tree, 20), "at the bound: still verified");
  }

  /**
   * The downstream contract the cap depends on: "no digest" must be un-matchable. If {@code matches}
   * treated a null/blank record as a pass, an over-cap or pre-v2 copy would be DELETED unverified —
   * which is the whole failure this guard exists to end.
   */
  @Test
  @DisplayName("an absent recorded digest never matches, so it can never license a delete")
  void absentRecordNeverMatches() throws IOException {
    Path file = tempDir.resolve("doc.txt");
    Files.writeString(file, "content");

    assertFalse(FileContentDigest.matches(file, null));
    assertFalse(FileContentDigest.matches(file, ""));
    assertFalse(FileContentDigest.matches(file, "   "));
    assertTrue(FileContentDigest.matches(file, FileContentDigest.of(file)));
  }

  @Test
  @DisplayName("a missing path has no digest")
  void missingPathHasNoDigest() {
    assertNull(FileContentDigest.of(tempDir.resolve("nope.txt")));
  }
}
