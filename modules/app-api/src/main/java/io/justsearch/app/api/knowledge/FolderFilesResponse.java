/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

import java.util.List;
import java.util.Map;

/**
 * Response for listing files in a folder (POST /api/knowledge/folder-files).
 *
 * <p>Stability: stable (API contract)
 */
public record FolderFilesResponse(List<FileEntry> files, long totalCount, long tookMs) {

  public FolderFilesResponse {
    files = files == null ? List.of() : List.copyOf(files);
  }

  public static record FileEntry(String docId, Map<String, String> fields) {
    public FileEntry {
      fields = fields == null ? Map.of() : Map.copyOf(fields);
    }
  }
}
