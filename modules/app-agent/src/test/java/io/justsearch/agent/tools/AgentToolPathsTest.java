package io.justsearch.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 875 Move 4 — path-containment semantics shared by the agent tools: containment is decided
 * on canonicalized (real-path) forms, and an ambiguous root reference is refused rather than
 * silently resolved.
 */
class AgentToolPathsTest {

  @TempDir Path tempDir;

  // --- validateAgainstRoots: canonicalization ---

  @Test
  void rejectsPathThatOnlyLooksInRootBeforeSymlinkResolution() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("indexed"));
    Path outside = Files.createDirectories(tempDir.resolve("outside"));
    Path secret = Files.writeString(outside.resolve("secret.txt"), "not yours");

    Path link = root.resolve("link");
    linkDirectory(link, outside);

    Path escaping = link.resolve("secret.txt");
    assertTrue(Files.exists(escaping), "Precondition: the escaping path resolves to the secret");
    assertTrue(
        escaping.normalize().startsWith(root),
        "Precondition: the escaping path looks in-root before link resolution");

    String rejection =
        AgentToolPaths.validateAgainstRoots(
            escaping.toString(), List.of(root.toString()), "parent_path");

    assertNotNull(
        rejection, "A symlink straddling the root boundary must be rejected: " + secret);
    assertTrue(
        rejection.contains("not under any indexed root folder"), "Unexpected message: " + rejection);
  }

  /**
   * Creates {@code link} pointing at {@code target}. Windows refuses {@code createSymbolicLink}
   * without developer mode / SeCreateSymbolicLink, so it falls back to a directory JUNCTION, which
   * needs no privilege and is the escape vector a real user is most likely to have on disk. Aborts
   * the test only if neither is available.
   */
  private static void linkDirectory(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
      return;
    } catch (IOException | UnsupportedOperationException e) {
      if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
        Assumptions.abort("Platform cannot create symbolic links: " + e.getMessage());
      }
    }
    try {
      Process p =
          new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
              .redirectErrorStream(true)
              .start();
      if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
        p.destroy();
        Assumptions.abort("Platform cannot create a directory junction either");
      }
    } catch (IOException e) {
      Assumptions.abort("Platform cannot create a directory junction either: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Assumptions.abort("Interrupted while creating a directory junction");
    }
  }

  @Test
  void canonicalizesBothSidesForRealPaths() throws IOException {
    // Link-free coverage of the canonicalizing path: both the candidate and each root go through
    // the closest-existing-ancestor real path, so a real in-root path (existing or not-yet-created)
    // validates and a real sibling directory does not.
    Path root = Files.createDirectories(tempDir.resolve("indexed"));
    Path sibling = Files.createDirectories(tempDir.resolve("sibling"));
    Path existing = Files.writeString(root.resolve("doc.txt"), "hello");
    Path notYetCreated = root.resolve("new").resolve("deep").resolve("file.txt");

    List<String> roots = List.of(root.toString());
    assertNull(
        AgentToolPaths.validateAgainstRoots(existing.toString(), roots, "path"),
        "An existing in-root path must validate");
    assertNull(
        AgentToolPaths.validateAgainstRoots(notYetCreated.toString(), roots, "path"),
        "A not-yet-created in-root path must validate (closest existing ancestor is the root)");
    assertNotNull(
        AgentToolPaths.validateAgainstRoots(
            sibling.resolve("doc.txt").toString(), roots, "path"),
        "A path in a sibling directory must be rejected");
  }

  /**
   * A root string that cannot become a {@link Path} at all — the deterministic, cross-platform
   * stand-in for the real-world unresolvable root (a detached network share, an ACL-blocked mount):
   * both reach {@code validateAgainstRoots} as "this root cannot be canonicalized". The NUL
   * character is rejected by the Windows and Unix path parsers alike.
   */
  private static String unresolvableRoot() {
    return "\0no-such-root";
  }

  @Test
  void oneUnresolvableRootDoesNotRejectPathsUnderTheOtherRoots() throws IOException {
    // The defect: canonicalizing each root INSIDE the shared try meant the first root that could
    // not be resolved aborted the whole loop, so a path under a later, perfectly valid root was
    // rejected. Order matters — the bad root is listed FIRST, before the real one.
    Path root = Files.createDirectories(tempDir.resolve("indexed"));
    Path inRoot = Files.writeString(root.resolve("doc.txt"), "hello");

    List<String> roots = List.of(unresolvableRoot(), root.toString());

    assertNull(
        AgentToolPaths.validateAgainstRoots(inRoot.toString(), roots, "path"),
        "An unresolvable root listed first must be skipped, not abort the containment loop");
    assertNotNull(
        AgentToolPaths.validateAgainstRoots(
            tempDir.resolve("sibling").resolve("doc.txt").toString(), roots, "path"),
        "Skipping a bad root must not turn into skipping containment altogether");
  }

  @Test
  void allRootsUnresolvableIsAnOrdinaryNotUnderAnyRootRejection() throws IOException {
    // The two canonicalization failures are different verdicts. Every ROOT unresolvable is simply
    // "no root matched" — the candidate itself resolved fine, so the honest message is the ordinary
    // one, not the fail-closed "could not be resolved for containment checking".
    Path somewhere = Files.createDirectories(tempDir.resolve("somewhere"));
    List<String> roots = List.of(unresolvableRoot());

    String rejection =
        AgentToolPaths.validateAgainstRoots(
            somewhere.resolve("doc.txt").toString(), roots, "path");

    assertNotNull(rejection, "No usable root means nothing is contained");
    assertTrue(
        rejection.contains("not under any indexed root folder"),
        "Unexpected message: " + rejection);
  }

  @Test
  void candidateThatCannotBeResolvedStillFailsClosed() throws IOException {
    // The other half of the distinction: skipping an unusable ROOT must not loosen the CANDIDATE
    // side. A candidate that cannot be resolved never proved containment, so it stays refused even
    // when every configured root is perfectly fine.
    Path root = Files.createDirectories(tempDir.resolve("indexed"));

    assertNotNull(
        AgentToolPaths.validateAgainstRoots(
            root.toString() + "\0bad", List.of(root.toString()), "path"),
        "An unresolvable candidate is never granted containment");
  }

  @Test
  void nonExistentPathsCompareConsistentlyOnBothSides() {
    // Canonicalization must not make a not-yet-existing root un-comparable: candidate and root are
    // resolved the same way, so the missing segments are re-appended to the same real ancestor.
    Path fictionalRoot = tempDir.resolve("never-created");
    List<String> roots = List.of(fictionalRoot.toString());

    assertNull(
        AgentToolPaths.validateAgainstRoots(
            fictionalRoot.resolve("docs").toString(), roots, "path"),
        "A path under a not-yet-existing root must still validate");
    assertNotNull(
        AgentToolPaths.validateAgainstRoots(
            tempDir.resolve("never-created-either").resolve("docs").toString(), roots, "path"),
        "A path outside a not-yet-existing root must still be rejected");
  }

  // --- resolveRelativePath: ambiguity ---

  @Test
  void duplicateRootNamesRefuseToResolve() {
    List<BrowseTool.RootInfo> roots =
        List.of(
            new BrowseTool.RootInfo(tempDir.resolve("a").resolve("docs").toString(), "docs"),
            new BrowseTool.RootInfo(tempDir.resolve("b").resolve("docs").toString(), "docs"));

    assertNull(
        AgentToolPaths.resolveRelativePath("docs/how-to", roots),
        "An ambiguous root name must not silently resolve to the first match");
    assertNull(
        AgentToolPaths.resolveRelativePath("docs", roots),
        "The bare ambiguous root name must not resolve either");
  }

  @Test
  void uniqueRootNameStillResolves() {
    Path docsRoot = tempDir.resolve("a").resolve("docs");
    List<BrowseTool.RootInfo> roots =
        List.of(
            new BrowseTool.RootInfo(docsRoot.toString(), "docs"),
            new BrowseTool.RootInfo(tempDir.resolve("b").resolve("projects").toString(), "projects"));

    assertEquals(
        docsRoot.resolve("how-to").toString(),
        AgentToolPaths.resolveRelativePath("docs/how-to", roots));
    assertEquals(docsRoot.toString(), AgentToolPaths.resolveRelativePath("docs", roots));
  }
}
