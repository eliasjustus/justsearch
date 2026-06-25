/**
 * describeEffect unit tests — 543-fwd idea #4 (undo-label).
 *
 * The labels feed the undo-confirmation toast, the mass-undo preview, and the
 * macro dry-run diff, so the phrasing is asserted here once.
 */

import { describe, it, expect } from 'vitest';
import { describeEffect, describeChange } from './describe.js';
import type { Effect } from '../effect.js';

describe('describeEffect (543-fwd #4)', () => {
  it('labels every Effect kind with a human-readable phrase', () => {
    const cases: ReadonlyArray<[Effect, string]> = [
      [{ kind: 'noop' }, 'No-op'],
      [{ kind: 'navigate', to: '#/search' }, 'Navigate to #/search'],
      [{ kind: 'open-pane', paneId: 'knowledge' }, 'Open pane "knowledge"'],
      [{ kind: 'close-pane', paneId: 'knowledge' }, 'Close pane "knowledge"'],
      [{ kind: 'toast', message: 'hi' }, 'Show message: hi'],
      [
        { kind: 'invoke-operation', operationId: 'core.search-index' },
        'Run core.search-index',
      ],
      [
        { kind: 'set-selection', surfaceId: 'results', ids: ['a', 'b'] },
        'Select 2 items in results',
      ],
      [
        { kind: 'set-selection', surfaceId: 'results', ids: ['a'] },
        'Select 1 item in results',
      ],
      [
        { kind: 'clear-selection', surfaceId: 'results' },
        'Clear selection in results',
      ],
      [{ kind: 'focus-element', selector: '#q' }, 'Focus #q'],
      [{ kind: 'scroll-to', selector: '#hit-3' }, 'Scroll to #hit-3'],
      [{ kind: 'open-modal', modalId: 'settings' }, 'Open dialog "settings"'],
      [{ kind: 'close-modal', modalId: 'settings' }, 'Close dialog "settings"'],
      [{ kind: 'copy-to-clipboard', text: 'x' }, 'Copy to clipboard'],
      [
        { kind: 'set-form-value', formId: 'f', path: 'a.b', value: 1 },
        'Set a.b in form "f"',
      ],
      [
        { kind: 'undo-operation', operationId: 'op', executionId: 'e1' },
        'Undo op',
      ],
      [
        { kind: 'data-result', operationId: 'op', resultKey: 'k', result: {} },
        'Result from op',
      ],
      [
        { kind: 'data-error', operationId: 'op', resultKey: 'k', error: 'boom' },
        'Error from op',
      ],
    ];
    for (const [effect, expected] of cases) {
      expect(describeEffect(effect)).toBe(expected);
    }
  });

  it('uses a placeholder for an empty identifier rather than empty quotes', () => {
    expect(describeEffect({ kind: 'open-pane', paneId: '' })).toBe(
      'Open pane (unnamed)',
    );
  });
});

describe('describeChange (543-fwd #12 dry-run diff)', () => {
  it('gives before+after for symmetric + value-mutating effects', () => {
    expect(describeChange({ kind: 'open-pane', paneId: 'inspector' })).toEqual({
      before: 'closed',
      after: 'pane "inspector" open',
    });
    expect(describeChange({ kind: 'close-modal', modalId: 'm' })).toEqual({
      before: 'open',
      after: 'dialog "m" closed',
    });
    expect(
      describeChange({ kind: 'set-form-value', formId: 'f', path: 'a.b', value: 9, previousValue: 3 }),
    ).toEqual({ before: '3', after: 'a.b = 9' });
    expect(
      describeChange({ kind: 'set-form-value', formId: 'f', path: 'a', value: 'x' }),
    ).toEqual({ before: '(unset)', after: 'a = x' });
  });

  it('gives after-only (no before) for actions without a prior state', () => {
    expect(describeChange({ kind: 'navigate', to: '#/s' })).toEqual({ after: 'view → #/s' });
    expect(describeChange({ kind: 'invoke-operation', operationId: 'core_search_index' })).toEqual({
      after: 'runs core_search_index',
    });
    expect(describeChange({ kind: 'toast', message: 'hi' })).toEqual({ after: 'message: hi' });
    expect(describeChange({ kind: 'noop' }).before).toBeUndefined();
  });
});
