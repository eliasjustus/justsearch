/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

/**
 * Request for browsing indexed folders (POST /api/knowledge/folders).
 *
 * <p>Stability: stable (API contract)
 */
public record FolderBrowseRequest(String parentPath, Integer maxFolders) {

  public FolderBrowseRequest {
    parentPath = parentPath == null ? "" : parentPath.trim();
  }
}
