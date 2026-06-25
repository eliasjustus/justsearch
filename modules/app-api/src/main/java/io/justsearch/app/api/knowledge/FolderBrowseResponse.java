/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

import java.util.List;

/**
 * Response for folder enumeration (POST /api/knowledge/folders).
 *
 * <p>Stability: stable (API contract)
 */
public record FolderBrowseResponse(List<Folder> folders, long tookMs, boolean truncated) {

  public FolderBrowseResponse {
    folders = folders == null ? List.of() : List.copyOf(folders);
  }

  public static record Folder(
      String path, String name, long fileCount, long totalSizeBytes, long lastIndexedAt) {}
}
