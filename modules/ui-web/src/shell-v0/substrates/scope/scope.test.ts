/**
 * Scope substrate unit tests — Tempdoc 543 §3.B.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { serializeScope, restoreScope, getScope } from './index.js';
import {
  __resetShellContextForTest,
  updateShellContext,
  getShellContext,
} from '../../state/shellContextState.js';

describe('ScopeSnapshot serialize/restore (§3.B)', () => {
  beforeEach(() => {
    __resetShellContextForTest();
  });

  describe('serializeScope', () => {
    it('returns empty-ish snapshot for default state (only activeProfileId)', () => {
      const snap = serializeScope();
      expect(snap.activeProfileId).toBe('default');
      expect(snap.activeCorpusId).toBeUndefined();
      expect(snap.activeLibraryId).toBeUndefined();
      expect(snap.preferredModelId).toBeUndefined();
      expect(snap.activeAgentRole).toBeUndefined();
      expect(snap.enabledPluginIds).toBeUndefined();
      expect(snap.audience).toBeUndefined();
    });

    it('captures all deferred-slot values when set', () => {
      updateShellContext({
        activeCorpusId: 'corpus-a',
        activeLibraryId: 'lib-b',
        preferredModelId: 'model-c',
        activeAgentRole: 'role-d',
        enabledPluginIds: 'p1,p2',
        audience: 'DEVELOPER',
      });
      const snap = serializeScope();
      expect(snap.activeCorpusId).toBe('corpus-a');
      expect(snap.activeLibraryId).toBe('lib-b');
      expect(snap.preferredModelId).toBe('model-c');
      expect(snap.activeAgentRole).toBe('role-d');
      expect(snap.enabledPluginIds).toBe('p1,p2');
      expect(snap.audience).toBe('DEVELOPER');
    });

    it('omits ephemeral fields (selection/focus/inspector/palette)', () => {
      updateShellContext({
        focusKind: 'input',
        selectionKind: 'search-hit',
        selectionCount: 3,
        inspectorOpen: true,
        paletteOpen: true,
      });
      const snap = serializeScope() as Record<string, unknown>;
      expect(snap.focusKind).toBeUndefined();
      expect(snap.selectionKind).toBeUndefined();
      expect(snap.selectionCount).toBeUndefined();
      expect(snap.inspectorOpen).toBeUndefined();
      expect(snap.paletteOpen).toBeUndefined();
    });
  });

  describe('restoreScope (replace mode)', () => {
    it('applies snapshot fields to live context', () => {
      restoreScope({ activeCorpusId: 'corpus-x', audience: 'OPERATOR' });
      const ctx = getShellContext();
      expect(ctx.activeCorpusId).toBe('corpus-x');
      expect(ctx.audience).toBe('OPERATOR');
    });

    it('clears all 6 deferred slots when absent from snapshot (replace semantics)', () => {
      updateShellContext({
        activeCorpusId: 'corpus-existing',
        activeLibraryId: 'lib-existing',
        preferredModelId: 'model-existing',
        activeAgentRole: 'role-existing',
        enabledPluginIds: 'p1,p2',
        audience: 'AGENT',
      });
      restoreScope({});
      const ctx = getShellContext();
      // IDs clear to null; flat-key strings clear to ''.
      expect(ctx.activeCorpusId).toBeNull();
      expect(ctx.activeLibraryId).toBeNull();
      expect(ctx.preferredModelId).toBeNull();
      expect(ctx.activeAgentRole).toBeNull();
      expect(ctx.enabledPluginIds).toBe('');
      expect(ctx.audience).toBe('');
    });

    it('preserves activeProfile when snapshot omits activeProfileId', () => {
      updateShellContext({ activeProfile: 'research' });
      restoreScope({ activeCorpusId: 'c' });
      expect(getShellContext().activeProfile).toBe('research');
    });

    it('updates activeProfile when snapshot carries activeProfileId', () => {
      restoreScope({ activeProfileId: 'work' });
      expect(getShellContext().activeProfile).toBe('work');
    });
  });

  describe('restoreScope (patch mode)', () => {
    it('only sets fields present in snapshot; preserves others', () => {
      updateShellContext({
        activeCorpusId: 'corpus-keep',
        audience: 'OPERATOR',
      });
      restoreScope({ activeLibraryId: 'lib-new' }, 'patch');
      const ctx = getShellContext();
      expect(ctx.activeCorpusId).toBe('corpus-keep');
      expect(ctx.audience).toBe('OPERATOR');
      expect(ctx.activeLibraryId).toBe('lib-new');
    });
  });

  describe('round-trip', () => {
    it('serialize→restore preserves all set deferred slots', () => {
      updateShellContext({
        activeCorpusId: 'c',
        activeLibraryId: 'l',
        preferredModelId: 'm',
        activeAgentRole: 'r',
        enabledPluginIds: 'a,b',
        audience: 'USER',
      });
      const snap = serializeScope();
      __resetShellContextForTest();
      restoreScope(snap);
      const ctx = getShellContext();
      expect(ctx.activeCorpusId).toBe('c');
      expect(ctx.activeLibraryId).toBe('l');
      expect(ctx.preferredModelId).toBe('m');
      expect(ctx.activeAgentRole).toBe('r');
      expect(ctx.enabledPluginIds).toBe('a,b');
      expect(ctx.audience).toBe('USER');
    });
  });

  describe('getScope', () => {
    it('returns the live ShellContext (identity with getShellContext today)', () => {
      expect(getScope()).toBe(getShellContext());
    });
  });
});

describe('Default ShellContext deferred slots (§3.B)', () => {
  beforeEach(() => {
    __resetShellContextForTest();
  });

  it('defaults to null for nullable slots, "" for string slots', () => {
    const ctx = getShellContext();
    expect(ctx.activeCorpusId).toBeNull();
    expect(ctx.activeLibraryId).toBeNull();
    expect(ctx.preferredModelId).toBeNull();
    expect(ctx.activeAgentRole).toBeNull();
    expect(ctx.enabledPluginIds).toBe('');
    expect(ctx.audience).toBe('');
  });
});
