// @vitest-environment happy-dom

/**
 * §32 U2 — undoable-operation side-map tests.
 */

import { beforeEach, describe, it, expect } from 'vitest';
import {
  recordEffect,
  markUndoableOperation,
  getUndoableOperation,
  __resetJournalForTest,
} from './index.js';
import { CORE_PROVENANCE } from '../../primitives/provenance.js';

beforeEach(() => {
  __resetJournalForTest();
});

describe('§32 U2 — undoable-operation side-map', () => {
  it('associates a journal entry with a backend execution and retrieves it', () => {
    const entry = recordEffect(
      { kind: 'invoke-operation', operationId: 'core.file-operations' },
      CORE_PROVENANCE,
      { originator: 'agent' },
    );
    expect(getUndoableOperation(entry.id)).toBeUndefined();
    markUndoableOperation(entry.id, 'core.file-operations', 'exec-123');
    expect(getUndoableOperation(entry.id)).toEqual({
      operationId: 'core.file-operations',
      executionId: 'exec-123',
    });
  });

  it('reset clears the side-map', () => {
    const entry = recordEffect({ kind: 'noop' }, CORE_PROVENANCE);
    markUndoableOperation(entry.id, 'op', 'e');
    __resetJournalForTest();
    expect(getUndoableOperation(entry.id)).toBeUndefined();
  });
});
