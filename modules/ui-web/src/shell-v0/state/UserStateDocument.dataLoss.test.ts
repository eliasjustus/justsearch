// @vitest-environment happy-dom

/**
 * Regression tests for the two silent data-loss paths in
 * UserStateDocument (static-analysis findings F9 + F10, 2026-05-25;
 * see docs/tempdocs/547-ui-web-static-analysis-findings.md).
 *
 * F9: `parseDocument` returns null for an unknown `version`, and
 *     `ensureInitialized` treats that as "malformed → fall through",
 *     ultimately resetting to defaults AND overwriting the stored
 *     document. A document written by a NEWER build (valid JSON,
 *     version > current) is therefore silently destroyed on boot —
 *     the exact "downgrade scenario" the in-code comment claims is
 *     protected against.
 *
 * F10: `saveDocument` swallows persistence failures (quota / serialize)
 *      while `mutateDocument` still applies the in-memory change + fires
 *      listeners — so a failed write presents as success and the change
 *      vanishes on next reload, with no diagnostic.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  __resetUserStateForTest,
  __resetInMemoryStateForTest,
  __DOCUMENT_STORAGE_KEY,
  __getLastPersistError,
  getDocument,
} from './UserStateDocument.js';
import { getViewerAudience, setViewerAudience } from './viewerAudienceState.js';

describe('UserStateDocument — silent data-loss paths', () => {
  beforeEach(() => {
    __resetUserStateForTest();
  });

  it('F9: boot does NOT overwrite a newer-schema document', () => {
    // A document a future build wrote — valid JSON, version 3 with an
    // unknown field. The current build understands v1/v2 only.
    const futureDoc = {
      version: 3,
      activeProfileId: 'default',
      profiles: {},
      futureOnlyField: 'must survive a downgrade',
    };
    localStorage.setItem(__DOCUMENT_STORAGE_KEY, JSON.stringify(futureDoc));
    __resetInMemoryStateForTest();

    // Reading the document this session is fine (falls back to defaults)...
    expect(getDocument().version).toBe(2);

    // ...but the stored newer document must be left intact so the newer
    // build reads it back unharmed. Pre-fix this is overwritten with a
    // v2 defaults blob (version 2, futureOnlyField gone).
    const persistedRaw = localStorage.getItem(__DOCUMENT_STORAGE_KEY);
    expect(persistedRaw).not.toBeNull();
    const persisted = JSON.parse(persistedRaw as string);
    expect(persisted.version).toBe(3);
    expect(persisted.futureOnlyField).toBe('must survive a downgrade');
  });

  it('F10: a persistence failure is surfaced, not swallowed', () => {
    // Initialize cleanly first (this save succeeds), then make writes fail.
    getDocument();
    const spy = vi
      .spyOn(localStorage, 'setItem')
      .mockImplementation(() => {
        throw new Error('QuotaExceededError');
      });

    setViewerAudience('OPERATOR');

    // The in-memory mutation still applies (we don't silently revert)...
    expect(getViewerAudience()).toBe('OPERATOR');
    // ...but the failed write is now observable instead of swallowed.
    expect(__getLastPersistError()).toBeInstanceOf(Error);

    spy.mockRestore();
  });
});
