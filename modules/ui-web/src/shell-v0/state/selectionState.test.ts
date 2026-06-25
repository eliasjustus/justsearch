// @vitest-environment happy-dom

/**
 * Tempdoc 508 §11.2 / §13.2 — selection state + ShellContext projection.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  setSingleSelection,
  setSelection,
  clearSelection,
  getSelection,
  subscribeSelection,
  DEFAULT_CAPABILITIES_BY_KIND,
  __resetSelectionForTest,
  type SelectionItem,
} from './selectionState.js';
import {
  getShellContext,
  __resetShellContextForTest,
} from './shellContextState.js';

beforeEach(() => {
  __resetSelectionForTest();
  __resetShellContextForTest();
});

describe('selectionState — defaults + clear', () => {
  it('starts empty', () => {
    const sel = getSelection();
    expect(sel.items).toEqual([]);
    expect(sel.primaryIndex).toBe(0);
    expect(sel.surfaceId).toBeNull();
  });

  it('clearSelection is a no-op when already empty', () => {
    const listener = vi.fn();
    subscribeSelection(listener);
    listener.mockClear();
    clearSelection();
    expect(listener).not.toHaveBeenCalled();
  });
});

describe('selectionState — single-item set', () => {
  it('setSingleSelection captures the item', () => {
    const item: SelectionItem = {
      kind: 'search-hit',
      hitId: 'h1',
      title: 'Doc 1',
      path: '/a/b.md',
      capabilities: DEFAULT_CAPABILITIES_BY_KIND['search-hit'],
    };
    setSingleSelection(item, 'core.search-surface');
    const sel = getSelection();
    expect(sel.items).toEqual([item]);
    expect(sel.surfaceId).toBe('core.search-surface');
  });

  it('ShellContext reflects single-item selection', () => {
    setSingleSelection(
      {
        kind: 'search-hit',
        hitId: 'h1',
        title: 't',
        path: 'p',
        capabilities: new Set(['open', 'pin']),
      },
      'core.search-surface',
    );
    const ctx = getShellContext();
    expect(ctx.selectionKind).toBe('search-hit');
    expect(ctx.selectionCount).toBe(1);
    expect(ctx.selectionCapabilities).toBe('open,pin');
  });

  it('clearing selection resets ShellContext flat keys', () => {
    setSingleSelection(
      {
        kind: 'search-hit',
        hitId: 'h1',
        title: 't',
        path: 'p',
        capabilities: new Set(['open']),
      },
      null,
    );
    clearSelection();
    const ctx = getShellContext();
    expect(ctx.selectionKind).toBe('none');
    expect(ctx.selectionCount).toBe(0);
    expect(ctx.selectionCapabilities).toBe('');
  });
});

describe('selectionState — multi-item descriptor', () => {
  it('setSelection captures multiple items', () => {
    const items: SelectionItem[] = [
      {
        kind: 'search-hit',
        hitId: 'h1',
        title: 't1',
        path: 'p1',
        capabilities: new Set(['open']),
      },
      {
        kind: 'search-hit',
        hitId: 'h2',
        title: 't2',
        path: 'p2',
        capabilities: new Set(['pin']),
      },
    ];
    setSelection({ items, primaryIndex: 1, surfaceId: 'core.search-surface' });
    const sel = getSelection();
    expect(sel.items).toHaveLength(2);
    expect(sel.primaryIndex).toBe(1);
  });

  it('capabilities are unioned across items', () => {
    setSelection({
      items: [
        {
          kind: 'search-hit',
          hitId: 'h1',
          title: 't',
          path: 'p',
          capabilities: new Set(['open', 'pin']),
        },
        {
          kind: 'search-hit',
          hitId: 'h2',
          title: 't',
          path: 'p',
          capabilities: new Set(['export', 'pin']),
        },
      ],
      primaryIndex: 0,
      surfaceId: null,
    });
    const caps = getShellContext().selectionCapabilities;
    // Comma-separated, sorted, union.
    expect(caps).toBe('export,open,pin');
  });

  it('selectionCount tracks item count', () => {
    setSelection({
      items: [
        {
          kind: 'browse-node',
          path: '/a',
          nodeKind: 'file',
          capabilities: new Set(['open']),
        },
        {
          kind: 'browse-node',
          path: '/b',
          nodeKind: 'file',
          capabilities: new Set(['open']),
        },
        {
          kind: 'browse-node',
          path: '/c',
          nodeKind: 'directory',
          capabilities: new Set(['reveal-in-explorer']),
        },
      ],
      primaryIndex: 0,
      surfaceId: null,
    });
    expect(getShellContext().selectionCount).toBe(3);
  });
});

describe('selectionState — kind reporting on mixed selections', () => {
  it('reports the primary item kind', () => {
    setSelection({
      items: [
        {
          kind: 'browse-node',
          path: '/a',
          nodeKind: 'directory',
          capabilities: new Set(['open']),
        },
        {
          kind: 'search-hit',
          hitId: 'h2',
          title: 't',
          path: 'p',
          capabilities: new Set(['pin']),
        },
      ],
      primaryIndex: 0,
      surfaceId: null,
    });
    expect(getShellContext().selectionKind).toBe('browse-node');
  });
});

describe('inspectorState.setSelected — bridge propagates kind (§13 A2)', () => {
  beforeEach(() => {
    __resetSelectionForTest();
    __resetShellContextForTest();
  });

  it('default (no kind) produces a search-hit SelectionItem', async () => {
    const { setSelected } = await import('./inspectorState.js');
    setSelected({ id: 'h1', title: 'Doc', path: '/x' });
    const sel = getSelection();
    expect(sel.items).toHaveLength(1);
    expect(sel.items[0]!.kind).toBe('search-hit');
    expect(getShellContext().selectionKind).toBe('search-hit');
  });

  it('kind: "browse-node" produces a browse-node SelectionItem', async () => {
    const { setSelected } = await import('./inspectorState.js');
    setSelected({ id: 'n1', title: 'folder', path: '/a/b', kind: 'browse-node' });
    const sel = getSelection();
    expect(sel.items[0]!.kind).toBe('browse-node');
    if (sel.items[0]!.kind === 'browse-node') {
      expect(sel.items[0]!.path).toBe('/a/b');
    }
    expect(getShellContext().selectionKind).toBe('browse-node');
  });

  it('kind: "plugin-item" produces a plugin-item SelectionItem', async () => {
    const { setSelected } = await import('./inspectorState.js');
    setSelected({ id: 'p1', title: 'P', path: '', kind: 'plugin-item' });
    const sel = getSelection();
    expect(sel.items[0]!.kind).toBe('plugin-item');
  });

  it('unknown kind falls back to search-hit (back-compat)', async () => {
    const { setSelected } = await import('./inspectorState.js');
    setSelected({ id: 'x', title: 'T', path: '/p', kind: 'unknown-kind-XYZ' });
    expect(getSelection().items[0]!.kind).toBe('search-hit');
  });

  it('setSelected(null) clears the selection', async () => {
    const { setSelected } = await import('./inspectorState.js');
    setSelected({ id: 'x', title: 't', path: '/y' });
    expect(getSelection().items).toHaveLength(1);
    setSelected(null);
    expect(getSelection().items).toHaveLength(0);
    expect(getShellContext().selectionKind).toBe('none');
  });
});

describe('selectionState — subscribe', () => {
  it('fires with current state on subscribe', () => {
    setSingleSelection(
      {
        kind: 'plugin-item',
        pluginId: 'p',
        itemId: 'i',
        label: 'L',
        capabilities: new Set(),
      },
      null,
    );
    const listener = vi.fn();
    subscribeSelection(listener);
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener.mock.calls[0]![0].items[0]!.kind).toBe('plugin-item');
  });
});
