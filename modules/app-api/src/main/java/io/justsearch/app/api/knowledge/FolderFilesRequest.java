/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

import java.util.List;

/**
 * Request for listing files in a folder (POST /api/knowledge/folder-files).
 *
 * <p>Stability: stable (API contract)
 */
public record FolderFilesRequest(String folderPath, Integer limit, List<String> projection) {

  public FolderFilesRequest {
    folderPath = folderPath == null ? "" : folderPath.trim();
    projection = projection == null ? List.of() : List.copyOf(projection);
  }
}
