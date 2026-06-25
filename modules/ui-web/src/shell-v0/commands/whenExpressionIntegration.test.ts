// @vitest-environment happy-dom

/**
 * Tempdoc 508 §11.1 / §13.1 — integration tests verifying that the
 * four registries (CommandRegistry, ContextActionRegistry,
 * InspectorTabRegistry, KeybindingRegistry) consult the
 * WhenExpression evaluator against the live ShellContext.
 *
 * Tests the substrate end-to-end: register an item with `when`,
 * mutate the context, see the item appear/disappear in `list*` /
 * `searchCommands` output.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { makePluginProvenance, CORE_PROVENANCE } from '../primitives/provenance.js';
const PLUGIN_PROVENANCE = makePluginProvenance('test-plugin', '1.0.0', 'TRUSTED_PLUGIN');
import {
  registerCommand,
  unregisterCommand,
  searchCommands,
} from './CommandRegistry.js';
import {
  registerContextAction,
  listContextActions,
  __resetForTest as resetContextActions,
} from './ContextActionRegistry.js';
import {
  registerInspectorTab,
  listInspectorTabs,
  __resetForTest as resetInspectorTabs,
} from './InspectorTabRegistry.js';
import {
  updateShellContext,
  __resetShellContextForTest,
} from '../state/shellContextState.js';
import { __resetWhenCacheForTest } from './whenExpression.js';

beforeEach(() => {
  __resetShellContextForTest();
  __resetWhenCacheForTest();
  resetContextActions();
  resetInspectorTabs();
});

describe('CommandRegistry — when filter', () => {
  it('shows a command when its `when` is true', () => {
    registerCommand({
      id: 'test.scoped',
      label: 'Scoped Command',
      source: 'plugin',
      provenance: PLUGIN_PROVENANCE,
      when: 'activeSurface == core.search-surface',
      handler: () => {},
    });
    updateShellContext({ activeSurface: 'core.search-surface' });
    const results = searchCommands('');
    expect(results.some((r) => r.command.id === 'test.scoped')).toBe(true);
    unregisterCommand('test.scoped');
  });

  it('hides a command when its `when` is false', () => {
    registerCommand({
      id: 'test.scoped',
      label: 'Scoped Command',
      source: 'plugin',
      provenance: PLUGIN_PROVENANCE,
      when: 'activeSurface == core.search-surface',
      handler: () => {},
    });
    updateShellContext({ activeSurface: 'core.health-surface' });
    const results = searchCommands('');
    expect(results.some((r) => r.command.id === 'test.scoped')).toBe(false);
    unregisterCommand('test.scoped');
  });

  it('shows commands without `when` regardless of context', () => {
    registerCommand({
      id: 'test.always',
      label: 'Always Visible',
      source: 'plugin',
      provenance: PLUGIN_PROVENANCE,
      handler: () => {},
    });
    updateShellContext({ activeSurface: 'core.health-surface' });
    expect(searchCommands('').some((r) => r.command.id === 'test.always')).toBe(true);
    updateShellContext({ activeSurface: 'core.search-surface' });
    expect(searchCommands('').some((r) => r.command.id === 'test.always')).toBe(true);
    unregisterCommand('test.always');
  });

  it('selection-aware command gated by selectionCount', () => {
    registerCommand({
      id: 'test.pin-selected',
      label: 'Pin Selected',
      source: 'plugin',
      provenance: PLUGIN_PROVENANCE,
      when: 'selectionCount > 0',
      handler: () => {},
    });
    updateShellContext({ selectionCount: 0 });
    expect(searchCommands('').some((r) => r.command.id === 'test.pin-selected')).toBe(false);
    updateShellContext({ selectionCount: 3 });
    expect(searchCommands('').some((r) => r.command.id === 'test.pin-selected')).toBe(true);
    unregisterCommand('test.pin-selected');
  });
});

describe('ContextActionRegistry — when filter + enabled filter', () => {
  it('filters out actions whose `when` is false', () => {
    registerContextAction({
      id: 'test.export',
      context: 'search-result',
      label: 'Export',
      priority: 1,
      source: 'plugin',
      provenance: PLUGIN_PROVENANCE,
      when: 'export in selectionCapabilities',
      handler: () => {},
    });
    updateShellContext({ selectionCapabilities: 'open,pin' });
    expect(listContextActions('search-result').some((a) => a.id === 'test.export')).toBe(false);
    updateShellContext({ selectionCapabilities: 'open,pin,export' });
    expect(listContextActions('search-result').some((a) => a.id === 'test.export')).toBe(true);
  });

  it('applies enabled(payload) filter when payload is provided', () => {
    registerContextAction({
      id: 'test.delete',
      context: 'file',
      label: 'Delete',
      priority: 1,
      source: 'plugin',
      provenance: PLUGIN_PROVENANCE,
      handler: () => {},
      enabled: (payload) =>
        typeof payload === 'object' && payload !== null && (payload as { writable?: boolean }).writable === true,
    });
    expect(listContextActions('file', { writable: false }).some((a) => a.id === 'test.delete')).toBe(false);
    expect(listContextActions('file', { writable: true }).some((a) => a.id === 'test.delete')).toBe(true);
  });

  it('ignores enabled when no payload is given', () => {
    registerContextAction({
      id: 'test.delete',
      context: 'file',
      label: 'Delete',
      priority: 1,
      source: 'plugin',
      provenance: PLUGIN_PROVENANCE,
      handler: () => {},
      enabled: () => false,
    });
    // Without payload, enabled is not invoked — both shapes visible.
    expect(listContextActions('file').some((a) => a.id === 'test.delete')).toBe(true);
  });

  it('combines context match + when + enabled', () => {
    registerContextAction({
      id: 'test.compound',
      context: 'search-result',
      label: 'Compound',
      priority: 1,
      source: 'plugin',
      provenance: PLUGIN_PROVENANCE,
      when: 'selectionCount > 0',
      handler: () => {},
      enabled: (p) => (p as { ok?: boolean }).ok === true,
    });
    updateShellContext({ selectionCount: 1 });
    expect(listContextActions('search-result', { ok: true }).length).toBe(1);
    expect(listContextActions('search-result', { ok: false }).length).toBe(0);
    updateShellContext({ selectionCount: 0 });
    expect(listContextActions('search-result', { ok: true }).length).toBe(0);
  });
});

describe('InspectorTabRegistry — when filter', () => {
  it('hides a tab when `when` is false', () => {
    registerInspectorTab({
      id: 'test.tab',
      label: 'Test Tab',
      priority: 5,
      source: 'plugin',
      provenance: PLUGIN_PROVENANCE,
      when: 'selectionKind == search-hit',
      render: () => 'content',
    });
    updateShellContext({ selectionKind: 'browse-node' });
    expect(listInspectorTabs().some((t) => t.id === 'test.tab')).toBe(false);
    updateShellContext({ selectionKind: 'search-hit' });
    expect(listInspectorTabs().some((t) => t.id === 'test.tab')).toBe(true);
  });

  it('tabs without `when` always visible', () => {
    registerInspectorTab({
      id: 'test.always',
      label: 'Always Tab',
      priority: 5,
      source: 'core',
      provenance: CORE_PROVENANCE,
      render: () => 'content',
    });
    updateShellContext({ selectionKind: 'none' });
    expect(listInspectorTabs().some((t) => t.id === 'test.always')).toBe(true);
  });
});
