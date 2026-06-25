/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Centralized repository-root / SSOT discovery logic.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>System property / env var: {@code justsearch.repo.root} / {@code JUSTSEARCH_REPO_ROOT}</li>
 *   <li>System property / env var: {@code justsearch.ssot.path} / {@code JUSTSEARCH_SSOT_PATH} (derive repo root)</li>
 *   <li>Auto-discovery: traverse up from CWD looking for {@code SSOT/}</li>
 * </ol>
 *
 * <p>This class intentionally lives in {@code modules/configuration} so other modules do not need to call
 * {@link System#getProperty(String)} / {@link System#getenv(String)} directly.
 */
public final class RepoRootLocator {
  private RepoRootLocator() {}

  /**
   * Finds the repository root directory.
   *
   * @return the repository root path (never null)
   * @throws IllegalStateException if repository root cannot be determined
   */
  public static Path findRepoRoot() {
    // 1) Explicit repo root
    Path explicit = resolveExplicitDir(EnvRegistry.REPO_ROOT);
    if (explicit != null) {
      return explicit;
    }

    // 2) Explicit SSOT path: derive repo root (SSOT is typically at repo/SSOT)
    Path ssot = resolveExplicitDir(EnvRegistry.SSOT_PATH);
    if (ssot != null) {
      // SSOT path might be the SSOT directory itself.
      Path parent = ssot.getParent();
      if (parent != null && Files.isDirectory(parent.resolve("SSOT"))) {
        return parent;
      }
      // Or SSOT path might be the repo root itself.
      if (Files.isDirectory(ssot.resolve("SSOT"))) {
        return ssot;
      }
    }

    // 3) Auto-discover by traversing up from CWD.
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      if (Files.isDirectory(current.resolve("SSOT"))) {
        return current;
      }
      current = current.getParent();
    }

    throw new IllegalStateException(
        "Repository root not found. Set JUSTSEARCH_REPO_ROOT or -Djustsearch.repo.root, "
            + "or set JUSTSEARCH_SSOT_PATH / -Djustsearch.ssot.path, "
            + "or run from within a repo that contains SSOT/.");
  }

  /**
   * Non-throwing variant useful for optional features.
   *
   * @return the repository root path, or null if not found
   */
  public static Path findRepoRootOrNull() {
    try {
      return findRepoRoot();
    } catch (IllegalStateException e) {
      return null;
    }
  }

  private static Path resolveExplicitDir(EnvRegistry key) {
    if (key == null) return null;
    String raw = key.get().orElse(null);
    if (raw == null || raw.isBlank()) return null;
    Path p = Path.of(raw);
    return Files.isDirectory(p) ? p : null;
  }
}
