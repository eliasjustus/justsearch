import { describe, it, expect } from 'vitest';
import {
  requestMemberTab,
  takeMemberTabIntent,
  subscribeMemberTab,
} from './memberTabIntent.js';

describe('memberTabIntent', () => {
  it('stores a one-shot pending intent that takeMemberTabIntent drains', () => {
    requestMemberTab('core.brain-surface', 'core.memory-surface');
    // drained once…
    expect(takeMemberTabIntent('core.brain-surface')).toBe('core.memory-surface');
    // …and not again (one-shot)
    expect(takeMemberTabIntent('core.brain-surface')).toBeUndefined();
  });

  it('returns undefined for a host with no pending intent', () => {
    expect(takeMemberTabIntent('core.unknown-host')).toBeUndefined();
  });

  it('notifies live subscribers synchronously (the already-active-host path)', () => {
    const seen: Array<[string, string]> = [];
    const unsub = subscribeMemberTab((h, m) => {
      seen.push([h, m]);
      return false; // did not "handle" → pending is still stored
    });
    requestMemberTab('core.system-surface', 'core.activity-surface');
    expect(seen).toEqual([['core.system-surface', 'core.activity-surface']]);
    // drain so this request does not leak into other tests
    takeMemberTabIntent('core.system-surface');
    unsub();
  });

  it('a live listener that HANDLES the request leaves NO stale pending (no wrong-tab leak)', () => {
    // The host that is already mounted returns true → no one-shot pending is stored, so a later
    // navigate-away-and-back will NOT drain a stale intent and open the wrong tab.
    const unsub = subscribeMemberTab((h) => h === 'core.brain-surface');
    requestMemberTab('core.brain-surface', 'core.memory-surface');
    expect(takeMemberTabIntent('core.brain-surface')).toBeUndefined();
    unsub();
  });

  it('stops notifying after unsubscribe', () => {
    let count = 0;
    const unsub = subscribeMemberTab(() => {
      count++;
    });
    requestMemberTab('core.library-surface', 'core.browse-surface');
    takeMemberTabIntent('core.library-surface');
    expect(count).toBe(1);
    unsub();
    requestMemberTab('core.library-surface', 'core.browse-surface');
    takeMemberTabIntent('core.library-surface');
    expect(count).toBe(1); // no further notifications
  });

  it('a throwing subscriber does not break the publish or other subscribers', () => {
    let reached = false;
    const u1 = subscribeMemberTab(() => {
      throw new Error('boom');
    });
    const u2 = subscribeMemberTab(() => {
      reached = true;
    });
    expect(() => requestMemberTab('core.settings-surface', 'core.presentation-gallery-surface')).not.toThrow();
    expect(reached).toBe(true);
    takeMemberTabIntent('core.settings-surface');
    u1();
    u2();
  });
});
