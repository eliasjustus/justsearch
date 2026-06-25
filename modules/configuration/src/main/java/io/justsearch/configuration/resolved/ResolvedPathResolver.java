/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Resolves shared runtime path roots from {@link ResolvedConfig}.
 *
 * <p>Centralizes base-dir and model-root resolution so Head, Worker, inference bootstrap, and
 * model discovery use the same path contract.
 */
public final class ResolvedPathResolver {

  private ResolvedPathResolver() {}

  /** Resolves the primary runtime base directory. */
  public static Path resolveBaseDir(ResolvedConfig config, String userDir) {
    Path home = normalize(config != null ? config.paths().home() : null);
    if (home != null) return home;

    Path dataDir = normalize(config != null ? config.paths().dataDir() : null);
    if (dataDir != null) return dataDir;

    Path repoRoot = resolveExplicitRepoRoot(config);
    if (repoRoot != null) return repoRoot;

    if (userDir != null && !userDir.isBlank()) {
      return normalize(Path.of(userDir));
    }
    return normalize(Path.of("."));
  }

  /** Returns the preferred model root. */
  public static Path resolveModelsDir(ResolvedConfig config, Path baseDir) {
    List<Path> roots = resolveModelRoots(config, baseDir);
    if (!roots.isEmpty()) {
      return roots.get(0);
    }
    return normalize(baseDir).resolve("models");
  }

  /**
   * Returns ordered model-root candidates.
   *
   * <p>Order:
   *
   * <ol>
   *   <li>Explicit {@code justsearch.models.dir}
   *   <li>Local {@code <dataDir>/models}
   *   <li>Explicit repo-root fallback {@code <repoRoot>/models}
   *   <li>Base-dir fallback {@code <baseDir>/models}
   * </ol>
   */
  public static List<Path> resolveModelRoots(ResolvedConfig config, Path baseDir) {
    LinkedHashSet<Path> roots = new LinkedHashSet<>();

    Path explicitModelsDir = normalize(config != null ? config.paths().modelsDir() : null);
    if (explicitModelsDir != null) {
      roots.add(explicitModelsDir);
    }

    Path dataDir = normalize(config != null ? config.paths().dataDir() : null);
    if (dataDir != null) {
      roots.add(dataDir.resolve("models"));
    }

    Path repoRoot = resolveExplicitRepoRoot(config);
    if (repoRoot != null) {
      roots.add(repoRoot.resolve("models"));
    }

    Path normalizedBaseDir = normalize(baseDir);
    if (normalizedBaseDir != null) {
      roots.add(normalizedBaseDir.resolve("models"));
    }

    return List.copyOf(roots);
  }

  /** Returns the explicitly-configured repo root, or {@code null} if absent. */
  public static Path resolveExplicitRepoRoot(ResolvedConfig config) {
    return normalize(config != null ? config.paths().repoRoot() : null);
  }

  private static Path normalize(Path path) {
    if (path == null) return null;
    return path.toAbsolutePath().normalize();
  }
}
