/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ssot.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fails the build if canonical docs reference removed legacy API endpoints or cite deleted classes.
 *
 * <p>Replaces {@code scripts/docs/docs-api-drift-check.mjs}.
 *
 * <p>Scope: all markdown under {@code docs/explanation}, {@code docs/reference}, {@code docs/how-to},
 * and {@code docs/decisions}. A line may opt out of a single rule with a trailing
 * {@code <!-- drift-allow:<token> -->} marker (used for deliberate migration-history mentions), where
 * {@code <token>} is a substring of the banned name (e.g. {@code /api/agent} or {@code RuntimeConfig}).
 *
 * <p>Two rule families:
 *
 * <ul>
 *   <li><b>Banned endpoints</b> — removed/renamed Local API routes (tempdoc 491 moved the agent surface
 *       to {@code /api/chat/*}); docs must describe current routes.
 *   <li><b>Deleted-class file citations</b> — a {@code <ClassName>.java} citation of a class that no
 *       longer exists. Prose mentions of the bare class name (migration context) are allowed; only
 *       {@code .java} file-path citations fail, because you cannot cite a deleted file as a current
 *       source.
 * </ul>
 */
public final class DocsApiDriftCheck {

  private static final List<BannedEndpoint> BANNED = List.of(
      new BannedEndpoint("/api/search", Pattern.compile("/api/search\\b"),
          "Use POST /api/knowledge/search (see LocalApiServer routes)."),
      new BannedEndpoint("/api/settings (v1)", Pattern.compile("/api/settings(?!/v2)\\b"),
          "Use GET/POST /api/settings/v2."),
      new BannedEndpoint("/api/agent/*", Pattern.compile("/api/agent/"),
          "Agent API moved to /api/chat/agent/* and /api/chat/sessions/* (tempdoc 491)."));

  /** Deleted classes whose {@code .java} file-path citation in docs is always stale. */
  private static final List<BannedClass> BANNED_CLASSES = List.of(
      new BannedClass("LuceneIndexRuntime",
          "Deleted (tempdoc 320). Read=ReadPathOps, write/commit=WritePathOps+CommitOps, "
              + "lifecycle=RunningRuntime+RuntimeSession (adapters-lucene/.../runtime/)."),
      new BannedClass("RuntimeConfig",
          "Deleted (tempdoc 314). Use ConfigStore / ResolvedConfig (modules/configuration)."),
      new BannedClass("DefaultAppFacade",
          "Deleted (tempdoc 502/519). Use HeadAssembly + typed service graphs."),
      new BannedClass("SearchPipelineExecutor",
          "Deleted (tempdoc 313). Use SearchOrchestrator (worker-services)."),
      new BannedClass("SearchStageType",
          "Deleted (tempdoc 221). Pipeline stage-type enum removed."),
      new BannedClass("AppFacadeBootstrap",
          "Renamed to HeadAssembly (tempdoc 519). Use HeadAssembly."));

  private static final Pattern ALLOW = Pattern.compile("<!--\\s*drift-allow:([^\\s>]+)\\s*-->");

  /** Markdown subtrees under {@code docs/} that are scanned. */
  static final List<String> SCANNED_SUBDIRS = List.of("explanation", "reference", "how-to", "decisions");

  public static void main(String[] args) throws IOException {
    Path repoRoot = args.length > 0 ? Path.of(args[0]) : findRepoRoot();
    Result result = findViolations(repoRoot);
    if (!result.violations().isEmpty()) {
      System.err.println("docs-api-drift-check: FAILED");
      result.violations().forEach(System.err::println);
      System.exit(1);
    }
    System.out.println("docs-api-drift-check: OK (" + result.filesScanned() + " files scanned)");
  }

  /** Scans the canonical doc subtrees under {@code repoRoot/docs} and returns drift violations. */
  static Result findViolations(Path repoRoot) throws IOException {
    Path docsDir = repoRoot.resolve("docs");
    List<Path> targets = new ArrayList<>();
    for (String sub : SCANNED_SUBDIRS) {
      Path dir = docsDir.resolve(sub);
      if (Files.isDirectory(dir)) {
        try (var stream = Files.walk(dir)) {
          stream
              .filter(Files::isRegularFile)
              .filter(p -> p.toString().endsWith(".md"))
              .forEach(targets::add);
        }
      }
    }
    targets.sort(null);

    List<String> errors = new ArrayList<>();
    for (Path file : targets) {
      List<String> lines = Files.readAllLines(file);
      String rel = repoRoot.relativize(file).toString().replace('\\', '/');
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        String allow = allowToken(line);
        for (BannedEndpoint b : BANNED) {
          if (b.pattern.matcher(line).find() && !allowed(allow, b.name)) {
            errors.add("- %s:%d references banned endpoint %s%n  line: %s%n  fix:  %s"
                .formatted(rel, i + 1, b.name, line.stripTrailing(), b.hint));
          }
        }
        for (BannedClass c : BANNED_CLASSES) {
          if (line.contains(c.name + ".java") && !allowed(allow, c.name)) {
            errors.add("- %s:%d cites deleted class file %s.java%n  line: %s%n  fix:  %s"
                .formatted(rel, i + 1, c.name, line.stripTrailing(), c.hint));
          }
        }
      }
    }
    return new Result(List.copyOf(errors), targets.size());
  }

  /** Outcome of a scan: the human-readable violation messages and the number of files scanned. */
  record Result(List<String> violations, int filesScanned) {}

  private static String allowToken(String line) {
    var m = ALLOW.matcher(line);
    return m.find() ? m.group(1) : null;
  }

  private static boolean allowed(String allowToken, String bannedName) {
    return allowToken != null && bannedName.contains(allowToken);
  }

  static Path findRepoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      if (Files.exists(dir.resolve("settings.gradle.kts"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    return Path.of("").toAbsolutePath();
  }

  private record BannedEndpoint(String name, Pattern pattern, String hint) {}

  private record BannedClass(String name, String hint) {}

  private DocsApiDriftCheck() {}
}
