/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ssot.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Warn-only scan for removed endpoints in noncanonical docs (tempdocs, future-features).
 * Lines marked with {@code HISTORICAL:} or {@code IDEA:} are exempt. Never fails the build.
 *
 * <p>Replaces {@code scripts/docs/noncanonical-removed-endpoints-warn.mjs}.
 */
public final class DocsNoncanonicalEndpointsWarn {

  private static final Pattern REMOVED_SEARCH = Pattern.compile("/api/search\\b");
  private static final Pattern REMOVED_SETTINGS = Pattern.compile("/api/settings(?!/v2)\\b");
  private static final Pattern MARKED = Pattern.compile("\\b(HISTORICAL|IDEA):", Pattern.CASE_INSENSITIVE);
  private static final int MAX_HITS = 40;

  public static void main(String[] args) throws IOException {
    Path repoRoot = args.length > 0 ? Path.of(args[0]) : DocsApiDriftCheck.findRepoRoot();
    List<Path> dirs = List.of(
        repoRoot.resolve("docs/tempdocs"),
        repoRoot.resolve("docs/future-features"));

    List<Path> files = new ArrayList<>();
    for (Path dir : dirs) {
      if (!Files.isDirectory(dir)) continue;
      try (Stream<Path> walk = Files.walk(dir)) {
        walk.filter(p -> p.toString().endsWith(".md")).forEach(files::add);
      }
    }

    int totalHits = 0;
    int unmarkedHits = 0;
    List<String> samples = new ArrayList<>();

    for (Path file : files) {
      String rel = repoRoot.relativize(file).toString().replace('\\', '/');
      List<String> lines = Files.readAllLines(file);
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        if (!hasRemovedEndpoint(line)) continue;
        totalHits++;
        if (!MARKED.matcher(line).find()) {
          unmarkedHits++;
          if (samples.size() < MAX_HITS) {
            samples.add("- %s:%d :: %s".formatted(rel, i + 1, line.stripTrailing()));
          }
        }
      }
    }

    if (totalHits == 0) {
      System.out.println("noncanonical-removed-endpoints-warn: OK (no removed endpoints found)");
      return;
    }
    if (unmarkedHits == 0) {
      System.out.printf(
          "noncanonical-removed-endpoints-warn: OK (found %d removed-endpoint reference(s), all marked HISTORICAL:/IDEA:)%n",
          totalHits);
      return;
    }
    System.out.printf(
        "noncanonical-removed-endpoints-warn: WARN (found %d removed-endpoint reference(s); %d unmarked)%n",
        totalHits, unmarkedHits);
    System.out.println("Mark these lines with HISTORICAL: or IDEA: to reduce agent confusion:");
    samples.forEach(System.out::println);
  }

  private static boolean hasRemovedEndpoint(String line) {
    return REMOVED_SEARCH.matcher(line).find() || REMOVED_SETTINGS.matcher(line).find();
  }

  private DocsNoncanonicalEndpointsWarn() {}
}
