// @vitest-environment happy-dom

/**
 * Tempdoc 508-followup §ζ1 — profile-switch + cross-consumer reset
 * atomicity integration test.
 *
 * Exercises the four consumers wired by main.jsx:
 *   - KeybindingRegistry source='user' entries
 *   - searchFiltersState
 *   - searchState
 *   - inspectorState
 *
 * Each consumer's reset is registered on subscribeProfileSwitch; the
 * test asserts that switching profiles drops state from all four
 * consumers in a single tick (no torn reads).
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import {
  __resetUserStateForTest,
  createProfile,
  setActiveProfileId,
  subscribeProfileSwitch,
} from './UserStateDocument.js';
import {
  registerKeybinding,
  listKeybindings,
  rebindUserKeybindings,
  __resetForTest as __resetKeybindings,
} from '../commands/KeybindingRegistry.js';
import {
  __resetSearchFiltersForTest,
  setFilterRange,
  getFilters,
  clearFilters,
  hasActiveFilter,
} from './searchFiltersState.js';
import {
  setQuery,
  getSearchState,
  resetSearchState,
} from './searchState.js';
import {
  setSelected,
  setActiveTab,
  getInspectorState,
  resetInspectorState,
} from './inspectorState.js';
import { __resetSelectionForTest } from './selectionState.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';

let unsubProfile: () => void = () => {};

beforeEach(() => {
  __resetUserStateForTest();
  __resetKeybindings();
  __resetSearchFiltersForTest();
  __resetSelectionForTest();
  resetSearchState();
  // Reset inspector via its public API (no dedicated reset-for-test).
  resetInspectorState();

  // Wire the four consumers exactly as main.jsx does in production.
  unsubProfile = subscribeProfileSwitch(() => {
    rebindUserKeybindings();
    clearFilters();
    resetSearchState();
    resetInspectorState();
  });
});

afterEach(() => {
  unsubProfile();
});

describe('Profile switch — cross-consumer reset atomicity (§ζ1)', () => {
  it('switching to a fresh profile resets all four consumer slices', () => {
    // Create the 'work' profile FIRST so the per-profile keybinding
    // overrides on 'default' don't leak into 'work' via createProfile's
    // basedOn=active copy semantics (§11.3 convention).
    createProfile('work', 'Work');
    // Seed transient state on the default profile.
    registerKeybinding({ key: 'ctrl+k', commandId: 'shell.go-to-search', source: 'user', provenance: CORE_PROVENANCE });
    setFilterRange(1000, 2000);
    setQuery('rotate weight matrix');
    setSelected({ id: 'h1', title: 'Doc 1', path: '/d1' });

    expect(listKeybindings().filter((k) => k.source === 'user')).toHaveLength(1);
    expect(hasActiveFilter(getFilters())).toBe(true);
    expect(getSearchState().query).toBe('rotate weight matrix');
    expect(getInspectorState().selected).not.toBeNull();
    expect(getInspectorState().isOpen).toBe(true);

    setActiveProfileId('work');

    // After switch: all four consumers cleared.
    // (Keybindings: 'work' was created before the user override, so
    // it has no source='user' bindings; the default's binding is
    // dropped from the in-memory registry.)
    expect(listKeybindings().filter((k) => k.source === 'user')).toHaveLength(0);
    expect(hasActiveFilter(getFilters())).toBe(false);
    expect(getSearchState().query).toBe('');
    expect(getSearchState().results).toHaveLength(0);
    expect(getInspectorState().selected).toBeNull();
    expect(getInspectorState().isOpen).toBe(false);
    expect(getInspectorState().activeTab).toBe('preview');
  });

  it('switch is idempotent — no-op when target is current', () => {
    createProfile('work', 'Work');
    setActiveProfileId('work');
    // Seed state.
    setQuery('q');
    setFilterRange(100, 200);
    let switchCount = 0;
    const off = subscribeProfileSwitch(() => switchCount++);
    setActiveProfileId('work'); // no-op
    expect(switchCount).toBe(0);
    expect(getSearchState().query).toBe('q'); // not reset
    expect(hasActiveFilter(getFilters())).toBe(true);
    off();
  });

  it('switch back restores the previous profile slices but transient state stays cleared', () => {
    // Create 'work' first so it has no user-source bindings copied
    // from default.
    createProfile('work', 'Work');
    // Default profile has user binding + filter + selection.
    registerKeybinding({ key: 'ctrl+k', commandId: 'shell.go-to-search', source: 'user', provenance: CORE_PROVENANCE });
    setFilterRange(100, 200);

    setActiveProfileId('work');
    setActiveProfileId('default');

    // Per-profile slices reflect the default profile's persisted state
    // (keybindings persist per-profile).
    expect(listKeybindings().filter((k) => k.source === 'user')).toHaveLength(1);
    // Transient state (filters, search query, inspector) does NOT
    // persist — it's deliberately cleared on every switch.
    expect(hasActiveFilter(getFilters())).toBe(false);
    expect(getSearchState().query).toBe('');
    expect(getInspectorState().selected).toBeNull();
  });

  it('inspector tab resets to preview even when the previous tab was non-default', () => {
    setSelected({ id: 'h1', title: 'X', path: '/x' });
    setActiveTab('answer');
    expect(getInspectorState().activeTab).toBe('answer');
    createProfile('work', 'Work');
    setActiveProfileId('work');
    expect(getInspectorState().activeTab).toBe('preview');
  });
});
