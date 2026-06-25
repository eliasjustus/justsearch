// @vitest-environment happy-dom

/**
 * Regression test for F18 (see docs/tempdocs/547): user keybindings lose
 * their required `provenance` across a reload.
 *
 * The persisted shape (UserStateDocument Profile.keybindingOverrides +
 * parseKeybindingOverrides) is {key, commandId, source} — it drops
 * `provenance` (required since tempdoc 543) and `when`. loadUserOverrides
 * casts that poor shape to KeybindingEntry[] (`as unknown as`), hiding the
 * gap, and loadPersistedKeybindings registered `{...entry, source:'user'}` —
 * so after a reload every user binding had `provenance: undefined`.
 *
 * Fix: loadPersistedKeybindings stamps a provenance fallback on load so the
 * required-provenance invariant holds. (`when` remains non-persisted by
 * schema — a documented limitation, not addressed here.)
 */

import { describe, it, expect, beforeEach } from 'vitest';
import {
  registerKeybinding,
  listKeybindings,
  loadPersistedKeybindings,
  __resetForTest,
} from './KeybindingRegistry.js';
import {
  __resetUserStateForTest,
  __resetInMemoryStateForTest,
} from '../state/UserStateDocument.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';

describe('KeybindingRegistry — user-binding provenance survives reload (F18)', () => {
  beforeEach(() => {
    __resetUserStateForTest();
    __resetForTest();
  });

  it('reloaded user keybindings carry a defined provenance', () => {
    // Register a user override; this persists to UserStateDocument.
    registerKeybinding({
      key: 'ctrl+k',
      commandId: 'shell.go-to-search',
      source: 'user',
      provenance: CORE_PROVENANCE,
    });

    // Simulate a reload: clear the in-memory binding map AND the in-memory
    // UserStateDocument so the next read re-parses localStorage (which
    // strips keybinding overrides to {key, commandId, source}).
    __resetForTest();
    __resetInMemoryStateForTest();

    loadPersistedKeybindings();

    const reloaded = listKeybindings().find((b) => b.key === 'ctrl+k');
    expect(reloaded).toBeDefined();
    expect(reloaded?.source).toBe('user');
    // Pre-fix: undefined (the persisted shape dropped provenance and
    // loadPersistedKeybindings didn't re-stamp it).
    expect(reloaded?.provenance).toBeDefined();
  });
});
