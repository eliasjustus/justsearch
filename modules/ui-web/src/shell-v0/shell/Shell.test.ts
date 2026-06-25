// @vitest-environment happy-dom

/**
 * Tests for the V0 Shell — Lumino DockPanel wrapper with id-keyed
 * pane registry and JSON-safe layout serialization.
 *
 * Visual layout behavior (drag-drop, split sizing, tab activation
 * pixel coords) needs the dev server / a real browser; these tests
 * cover the data-model contract:
 *  - addPane / removePane / hasPane / paneIds
 *  - duplicate-id rejection
 *  - saveLayout returns id-string-based config
 *  - restoreLayout reconstructs from id-string config (round-trip)
 *  - missing-pane ids are silently dropped on restore (graceful
 *    degradation when a pane was retired between save and restore)
 *  - dispose tears everything down
 */

import { describe, expect, it } from 'vitest';
import { Shell, type SerializedLayout } from './Shell.js';

function makePane(id: string): {
  id: string;
  title: string;
  content: HTMLElement;
} {
  const el = document.createElement('div');
  el.textContent = `pane:${id}`;
  return { id, title: id, content: el };
}

describe('Shell — pane registry', () => {
  it('addPane registers a pane by id', () => {
    const shell = new Shell();
    shell.addPane(makePane('alpha'));
    expect(shell.hasPane('alpha')).toBe(true);
    expect(shell.paneIds()).toEqual(['alpha']);
    shell.dispose();
  });

  it('addPane rejects duplicate ids with a clear error', () => {
    const shell = new Shell();
    shell.addPane(makePane('alpha'));
    expect(() => shell.addPane(makePane('alpha'))).toThrow(
      /already registered/i,
    );
    shell.dispose();
  });

  it('removePane returns true for known ids and detaches the widget', () => {
    const shell = new Shell();
    shell.addPane(makePane('alpha'));
    expect(shell.removePane('alpha')).toBe(true);
    expect(shell.hasPane('alpha')).toBe(false);
    shell.dispose();
  });

  it('removePane returns false for unknown ids', () => {
    const shell = new Shell();
    expect(shell.removePane('ghost')).toBe(false);
    shell.dispose();
  });

  it('paneIds reflects insertion order', () => {
    const shell = new Shell();
    shell.addPane(makePane('alpha'));
    shell.addPane(makePane('beta'));
    shell.addPane(makePane('gamma'));
    expect(shell.paneIds()).toEqual(['alpha', 'beta', 'gamma']);
    shell.dispose();
  });
});

describe('Shell — saveLayout / restoreLayout round-trip', () => {
  it('saveLayout returns a tab-area config when all panes are tabs', () => {
    const shell = new Shell();
    shell.addPane(makePane('alpha'));
    shell.addPane(makePane('beta'));
    const layout = shell.saveLayout();
    expect(layout.main?.type).toBe('tab-area');
    if (layout.main?.type === 'tab-area') {
      expect(layout.main.widgets).toEqual(['alpha', 'beta']);
    }
    shell.dispose();
  });

  it('restoreLayout reconstructs a saved tab-area layout', () => {
    const shell = new Shell();
    shell.addPane(makePane('alpha'));
    shell.addPane(makePane('beta'));
    const saved = shell.saveLayout();
    // Re-create a fresh shell with the same panes and restore.
    const shell2 = new Shell();
    shell2.addPane(makePane('alpha'));
    shell2.addPane(makePane('beta'));
    shell2.restoreLayout(saved);
    const restored = shell2.saveLayout();
    expect(restored.main?.type).toBe(saved.main?.type);
    if (
      restored.main?.type === 'tab-area' &&
      saved.main?.type === 'tab-area'
    ) {
      expect(restored.main.widgets).toEqual(saved.main.widgets);
    }
    shell.dispose();
    shell2.dispose();
  });

  it('saveLayout reflects a split layout when panes were added with split modes', () => {
    const shell = new Shell();
    shell.addPane(makePane('alpha'));
    shell.addPane(makePane('beta'), { mode: 'split-right', refId: 'alpha' });
    const layout = shell.saveLayout();
    expect(layout.main?.type).toBe('split-area');
    if (layout.main?.type === 'split-area') {
      expect(layout.main.orientation).toBe('horizontal');
      // The split has two children (each a tab-area), one with alpha,
      // the other with beta.
      const ids = layout.main.children.flatMap((c) =>
        c.type === 'tab-area' ? c.widgets : [],
      );
      expect(ids.sort()).toEqual(['alpha', 'beta']);
    }
    shell.dispose();
  });

  it('restoreLayout silently drops references to panes that were not re-added', () => {
    const original: SerializedLayout = {
      main: {
        type: 'tab-area',
        widgets: ['alpha', 'beta', 'ghost'], // 'ghost' is missing
        currentIndex: 2,
      },
    };
    const shell = new Shell();
    shell.addPane(makePane('alpha'));
    shell.addPane(makePane('beta'));
    shell.restoreLayout(original);
    const restored = shell.saveLayout();
    expect(restored.main?.type).toBe('tab-area');
    if (restored.main?.type === 'tab-area') {
      expect(restored.main.widgets).toEqual(['alpha', 'beta']);
      // currentIndex was 2 (out of 3); after dropping ghost, the
      // index must clamp to within [0, 1].
      expect(restored.main.currentIndex).toBeLessThanOrEqual(1);
      expect(restored.main.currentIndex).toBeGreaterThanOrEqual(0);
    }
    shell.dispose();
  });

  it('restoreLayout with empty / null main is tolerated', () => {
    const shell = new Shell();
    shell.addPane(makePane('alpha'));
    expect(() => shell.restoreLayout({ main: null })).not.toThrow();
    shell.dispose();
  });

  it('serialized layout is JSON.stringify-roundtrippable', () => {
    const shell = new Shell();
    shell.addPane(makePane('alpha'));
    shell.addPane(makePane('beta'), { mode: 'split-right', refId: 'alpha' });
    const layout = shell.saveLayout();
    const json = JSON.stringify(layout);
    const parsed = JSON.parse(json) as SerializedLayout;
    expect(parsed).toEqual(layout);
    shell.dispose();
  });
});

describe('Shell — attach / detach', () => {
  it('attachTo + detach toggles the dock panel in the DOM', () => {
    const shell = new Shell();
    shell.addPane(makePane('alpha'));
    const host = document.createElement('div');
    document.body.appendChild(host);

    shell.attachTo(host);
    expect(host.querySelector('.jf-shell-dock')).not.toBeNull();

    shell.detach();
    expect(host.querySelector('.jf-shell-dock')).toBeNull();

    shell.dispose();
    host.remove();
  });

  it('attachTo is idempotent', () => {
    const shell = new Shell();
    const host = document.createElement('div');
    document.body.appendChild(host);
    shell.attachTo(host);
    expect(() => shell.attachTo(host)).not.toThrow();
    shell.dispose();
    host.remove();
  });

  it('detach is idempotent', () => {
    const shell = new Shell();
    expect(() => shell.detach()).not.toThrow();
    expect(() => shell.detach()).not.toThrow();
    shell.dispose();
  });
});

describe('Shell — dispose', () => {
  it('dispose clears the pane registry and makes the shell unusable', () => {
    const shell = new Shell();
    shell.addPane(makePane('alpha'));
    shell.addPane(makePane('beta'));
    shell.dispose();
    expect(shell.paneIds()).toEqual([]);
    expect(shell.hasPane('alpha')).toBe(false);
  });
});
