/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.util;

import java.nio.file.Path;
import io.justsearch.configuration.RepoRootLocator;

/**
 * Utility for locating the repository root at runtime.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>System property: {@code -Djustsearch.repo.root=/path}</li>
 *   <li>Environment variable: {@code JUSTSEARCH_REPO_ROOT=/path}</li>
 *   <li>System property: {@code -Djustsearch.ssot.path=/path} (derives repo root from SSOT path)</li>
 *   <li>Environment variable: {@code JUSTSEARCH_SSOT_PATH=/path}</li>
 *   <li>Auto-discovery: traverse up from CWD looking for SSOT directory</li>
 * </ol>
 */
public final class RepoPaths {
  private RepoPaths() {}

  /**
   * Finds the repository root directory.
   *
   * <p>Checks explicit configuration first, then falls back to auto-discovery.
   *
   * @return the repository root path
   * @throws IllegalStateException if repository root cannot be determined
   */
  public static Path findRepoRoot() {
    return RepoRootLocator.findRepoRoot();
  }
}
