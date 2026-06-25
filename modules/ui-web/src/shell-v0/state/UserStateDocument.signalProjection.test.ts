// @vitest-environment happy-dom
//
// Tempdoc 548 §1 / R1b — F10 regression + signal-core guardrail.
//
// F10 (from 547): subscribeProjection memoized by REFERENTIAL identity
// (`next !== previous`), so a selector like `doc => doc.list ?? []` returns a
// fresh `[]` every notify and fires the listener spuriously even when the
// projected value is unchanged. After the R1b conversion (document is a
// signal; projections memoize by VALUE), an unrelated mutation must NOT
// re-fire a structurally-equal projection.
//
// These tests pin the DESIRED post-fix behavior: they fail on the referential
// memoization and pass on the value-equality fix.

import { describe, it, expect, beforeEach } from 'vitest';
import {
  subscribeProjection,
  mutateDocument,
  getDocument,
  __resetUserStateForTest,
} from './UserStateDocument.js';

describe('UserStateDocument — R1b signal-core projection (F10)', () => {
  beforeEach(() => {
    __resetUserStateForTest();
  });

  it('does NOT re-fire a structurally-equal projection on an unrelated mutation', () => {
    const calls: unknown[] = [];
    // `?? []` selector: returns a fresh empty array each evaluation when the
    // field is absent — the exact F10 trigger.
    const unsub = subscribeProjection(
      (doc) => doc.acknowledgedAdvisories ?? [],
      (v) => calls.push(v),
    );
    expect(calls.length).toBe(1); // initial fire

    // Mutate an UNRELATED field. The projected value (empty advisories) is
    // structurally unchanged, so a value-equality memo must not re-fire.
    mutateDocument((d) => ({ ...d, activeThemeId: 'dark' }));
    expect(calls.length).toBe(1);

    // A second unrelated mutation — still no spurious fire.
    mutateDocument((d) => ({ ...d, activeThemeId: 'light' }));
    expect(calls.length).toBe(1);

    unsub();
  });

  it('DOES fire when the projected value actually changes', () => {
    const seen: string[] = [];
    const unsub = subscribeProjection(
      (doc) => doc.activeThemeId ?? '',
      (v) => seen.push(v),
    );
    const initialLen = seen.length; // initial fire delivers current value
    mutateDocument((d) => ({ ...d, activeThemeId: 'dark' }));
    mutateDocument((d) => ({ ...d, activeThemeId: 'dark' })); // same value → no fire
    mutateDocument((d) => ({ ...d, activeThemeId: 'light' }));
    expect(seen.slice(initialLen)).toEqual(['dark', 'light']);
    unsub();
  });

  it('getDocument() stays synchronously fresh after mutate (signal source of truth)', () => {
    expect(getDocument().activeThemeId).not.toBe('solarized');
    mutateDocument((d) => ({ ...d, activeThemeId: 'solarized' }));
    expect(getDocument().activeThemeId).toBe('solarized'); // sync read of the signal
  });
});
