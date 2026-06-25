// SPDX-License-Identifier: Apache-2.0
/**
 * Browse domain API - Folder browsing for indexed content
 */

import { request } from '../http';
import { parseWireContract } from '../schemas';
// Tempdoc 564 Phase B (4b): the folder browse/files responses are generated wire-contract
// projections (record → JSON Schema → {TS, Zod}); the hand types + fail-open `.loose()` Zod retired.
import {
  folderBrowseResponseSchema,
  type FolderBrowseResponse,
} from '../generated/schema-types/folder-browse-response';
import {
  folderFilesResponseSchema,
  type FolderFilesResponse,
} from '../generated/schema-types/folder-files-response';

export type { FolderBrowseResponse } from '../generated/schema-types/folder-browse-response';
export type { FolderFilesResponse } from '../generated/schema-types/folder-files-response';

// ============================================
// API Functions
// ============================================

export async function fetchFolders(
  baseUrl: string,
  parentPath: string,
  maxFolders = 200,
  signal?: AbortSignal,
): Promise<FolderBrowseResponse> {
  const data = await request<unknown>(baseUrl, '/api/knowledge/folders', {
    method: 'POST',
    body: { parentPath, maxFolders },
    signal,
  });
  return parseWireContract(folderBrowseResponseSchema, data, 'POST /api/knowledge/folders');
}

export async function fetchFolderFiles(
  baseUrl: string,
  folderPath: string,
  limit = 200,
  signal?: AbortSignal,
): Promise<FolderFilesResponse> {
  const data = await request<unknown>(baseUrl, '/api/knowledge/folder-files', {
    method: 'POST',
    body: {
      folderPath,
      limit,
      projection: ['path', 'filename', 'file_kind', 'size_bytes', 'modified_at', 'indexed_at', 'vdu_status'],
    },
    signal,
  });
  return parseWireContract(folderFilesResponseSchema, data, 'POST /api/knowledge/folder-files');
}
