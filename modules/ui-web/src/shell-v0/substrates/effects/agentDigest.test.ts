// @vitest-environment happy-dom

/**
 * §32 U3 — summarizeAgentActivity tests.
 */

import { beforeEach, describe, it, expect } from 'vitest';
import {
  recordEffect,
  summarizeAgentActivity,
  __resetJournalForTest,
} from './index.js';
import { CORE_PROVENANCE } from '../../primitives/provenance.js';

beforeEach(() => {
  __resetJournalForTest();
});

describe('§32 U3 — summarizeAgentActivity', () => {
  it('counts agent actions by kind; excludes user-originated and rejected', () => {
    recordEffect({ kind: 'navigate', to: '#a' }, CORE_PROVENANCE, {
      originator: 'user',
    }); // user — excluded
    recordEffect({ kind: 'toast', message: 'x' }, CORE_PROVENANCE, {
      originator: 'agent',
    });
    recordEffect({ kind: 'toast', message: 'y' }, CORE_PROVENANCE, {
      originator: 'agent',
    });
    recordEffect({ kind: 'navigate', to: '#b' }, CORE_PROVENANCE, {
      originator: 'agent',
    });
    recordEffect({ kind: 'open-pane', paneId: 'p' }, CORE_PROVENANCE, {
      originator: 'agent',
      pendingOutcome: 'rejected',
    }); // vetoed — excluded

    const d = summarizeAgentActivity(0);
    expect(d.total).toBe(3);
    expect(d.byKind).toEqual({ toast: 2, navigate: 1 });
    expect(d.latestId).toBeGreaterThan(0);
  });

  it('sinceId excludes entries at or before the cursor', () => {
    const e1 = recordEffect({ kind: 'toast', message: 'a' }, CORE_PROVENANCE, {
      originator: 'agent',
    });
    recordEffect({ kind: 'toast', message: 'b' }, CORE_PROVENANCE, {
      originator: 'agent',
    });
    const d = summarizeAgentActivity(e1.id);
    expect(d.total).toBe(1); // only the second entry
  });

  it('returns empty digest when there is no agent activity', () => {
    recordEffect({ kind: 'toast', message: 'u' }, CORE_PROVENANCE, {
      originator: 'user',
    });
    const d = summarizeAgentActivity(0);
    expect(d.total).toBe(0);
    expect(d.byKind).toEqual({});
  });
});
