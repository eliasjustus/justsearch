/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ssot.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Validates tempdoc YAML frontmatter {@code status:} values. Warns locally; fails in CI.
 *
 * <p>Replaces {@code scripts/docs/tempdocs-status-warn.mjs}.
 */
public final class DocsTempdocStatusCheck {

  private static final Set<String> ALLOWED = Set.of("open", "active", "done", "draft");
  private static final Pattern STATUS_LINE = Pattern.compile("^status:\\s*(.+)$");

  public static void main(String[] args) throws IOException {
    Path repoRoot = args.length > 0 ? Path.of(args[0]) : DocsApiDriftCheck.findRepoRoot();
    boolean ciMode = "true".equals(System.getenv("CI")) || "1".equals(System.getenv("CI"));
    Path tempdocsDir = repoRoot.resolve("docs/tempdocs");

    if (!Files.isDirectory(tempdocsDir)) {
      System.out.println("docsTempdocStatusWarn: no tempdocs directory found.");
      return;
    }

    List<Path> files;
    try (Stream<Path> walk = Files.walk(tempdocsDir)) {
      files = walk
          .filter(p -> p.toString().endsWith(".md"))
          .filter(p -> !p.getFileName().toString().equals("README.md"))
          .toList();
    }

    List<Issue> issues = new ArrayList<>();
    for (Path file : files) {
      String rel = repoRoot.relativize(file).toString().replace('\\', '/');
      String status = extractFrontmatterStatus(file);
      if (status == null) {
        issues.add(new Issue(rel, "(missing)", "missing status"));
      } else if (!ALLOWED.contains(status.toLowerCase())) {
        issues.add(new Issue(rel, status, "invalid status"));
      }
    }

    if (issues.isEmpty()) {
      System.out.println("docsTempdocStatusWarn: no invalid tempdoc statuses found.");
      return;
    }

    System.err.printf("docsTempdocStatusWarn: found %d tempdoc file(s) with invalid status values.%n", issues.size());
    System.err.printf("Allowed values: %s%n", String.join(", ", ALLOWED));
    if (ciMode) {
      System.err.println("CI mode: tempdoc status drift is treated as a failing governance check.");
    } else {
      System.err.println("Local mode: warn-only to avoid blocking active parallel streams.");
    }
    int maxLines = 30;
    for (Issue issue : issues.subList(0, Math.min(issues.size(), maxLines))) {
      System.err.printf("- %s: status='%s' (%s)%n", issue.file, issue.status, issue.reason);
    }
    if (issues.size() > maxLines) {
      System.err.printf("... %d more omitted.%n", issues.size() - maxLines);
    }
    System.exit(ciMode ? 1 : 0);
  }

  static String extractFrontmatterStatus(Path file) throws IOException {
    List<String> lines = Files.readAllLines(file);
    boolean inFrontmatter = false;
    for (String line : lines) {
      if (line.trim().equals("---")) {
        if (!inFrontmatter) {
          inFrontmatter = true;
          continue;
        } else {
          break; // end of frontmatter
        }
      }
      if (inFrontmatter) {
        Matcher m = STATUS_LINE.matcher(line);
        if (m.matches()) {
          return m.group(1).trim().replaceAll("^[\"']|[\"']$", "");
        }
      }
    }
    return null;
  }

  private record Issue(String file, String status, String reason) {}

  private DocsTempdocStatusCheck() {}
}
