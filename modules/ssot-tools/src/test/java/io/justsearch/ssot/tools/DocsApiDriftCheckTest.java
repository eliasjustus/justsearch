package io.justsearch.ssot.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 556 (F-C1.8) — the drift guard scans {@code docs/reference} (not just {@code explanation}),
 * bans the migrated {@code /api/agent/*} prefix, rejects deleted-class {@code .java} citations, and
 * honours the inline {@code drift-allow} opt-out.
 */
class DocsApiDriftCheckTest {

  private static void writeDoc(Path repoRoot, String relPath, String body) throws IOException {
    Path f = repoRoot.resolve("docs").resolve(relPath);
    Files.createDirectories(f.getParent());
    Files.writeString(f, body);
  }

  @Test
  void cleanDocsProduceNoViolations(@TempDir Path repoRoot) throws IOException {
    writeDoc(repoRoot, "reference/clean.md", "Call `POST /api/chat/agent` and `GET /api/settings/v2`.\n");
    DocsApiDriftCheck.Result r = DocsApiDriftCheck.findViolations(repoRoot);
    assertTrue(r.violations().isEmpty(), () -> "unexpected: " + r.violations());
    assertEquals(1, r.filesScanned());
  }

  @Test
  void rejectsLegacyAgentPrefixInReferenceDir(@TempDir Path repoRoot) throws IOException {
    // reference/ is the dir the old guard did NOT scan — this is the F-C1.8 regression guard.
    writeDoc(repoRoot, "reference/api.md", "- `POST /api/agent/run/stream` starts a run.\n");
    DocsApiDriftCheck.Result r = DocsApiDriftCheck.findViolations(repoRoot);
    assertEquals(1, r.violations().size());
    assertTrue(r.violations().get(0).contains("/api/agent/*"));
  }

  @Test
  void rejectsDeletedClassFileCitation(@TempDir Path repoRoot) throws IOException {
    writeDoc(repoRoot, "explanation/04.md", "Writes to `.../runtime/LuceneIndexRuntime.java`.\n");
    DocsApiDriftCheck.Result r = DocsApiDriftCheck.findViolations(repoRoot);
    assertEquals(1, r.violations().size());
    assertTrue(r.violations().get(0).contains("LuceneIndexRuntime.java"));
  }

  @Test
  void rejectsRenamedBootstrapClassFileCitation(@TempDir Path repoRoot) throws IOException {
    // AppFacadeBootstrap was renamed to HeadAssembly (tempdoc 519); a .java path citation is stale.
    writeDoc(repoRoot, "how-to/spawn.md", "Entry: `.../app/services/AppFacadeBootstrap.java`.\n");
    DocsApiDriftCheck.Result r = DocsApiDriftCheck.findViolations(repoRoot);
    assertEquals(1, r.violations().size());
    assertTrue(r.violations().get(0).contains("AppFacadeBootstrap.java"));
  }

  @Test
  void bareClassNameInProseIsAllowed(@TempDir Path repoRoot) throws IOException {
    // Migration prose may mention the bare name; only `.java` file-path citations fail.
    writeDoc(repoRoot, "explanation/hist.md", "Formerly LuceneIndexRuntime, now decomposed into ops.\n");
    assertTrue(DocsApiDriftCheck.findViolations(repoRoot).violations().isEmpty());
  }

  @Test
  void driftAllowMarkerSuppressesAMatch(@TempDir Path repoRoot) throws IOException {
    writeDoc(
        repoRoot,
        "decisions/0001.md",
        "Historically `/api/agent/run/stream`. <!-- drift-allow:/api/agent -->\n");
    assertTrue(DocsApiDriftCheck.findViolations(repoRoot).violations().isEmpty());
  }

  @Test
  void settingsV2IsNotFlaggedButLegacySettingsIs(@TempDir Path repoRoot) throws IOException {
    writeDoc(repoRoot, "reference/legacy.md", "Reset via `POST /api/settings` (old).\n");
    DocsApiDriftCheck.Result r = DocsApiDriftCheck.findViolations(repoRoot);
    assertEquals(1, r.violations().size());
    assertTrue(r.violations().get(0).contains("/api/settings (v1)"));
  }
}
