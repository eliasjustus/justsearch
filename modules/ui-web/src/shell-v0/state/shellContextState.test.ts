// @vitest-environment happy-dom

/**
 * Tempdoc 508 §11.1 / §13.1 — ShellContext state slice tests.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  getShellContext,
  subscribeShellContext,
  updateShellContext,
  __resetShellContextForTest,
} from './shellContextState.js';

beforeEach(() => {
  __resetShellContextForTest();
});

describe('shellContextState — defaults', () => {
  it('starts with empty/none defaults', () => {
    const ctx = getShellContext();
    expect(ctx.activeSurface).toBeNull();
    expect(ctx.activeProfile).toBe('default');
    expect(ctx.focusKind).toBe('none');
    expect(ctx.selectionKind).toBe('none');
    expect(ctx.selectionCount).toBe(0);
    expect(ctx.selectionCapabilities).toBe('');
    expect(ctx.inspectorOpen).toBe(false);
  });
});

describe('shellContextState — updates and subscription', () => {
  it('updateShellContext patches one field', () => {
    updateShellContext({ activeSurface: 'core.search-surface' });
    expect(getShellContext().activeSurface).toBe('core.search-surface');
    // Other fields preserved.
    expect(getShellContext().focusKind).toBe('none');
  });

  it('subscribers fire on change', () => {
    const listener = vi.fn();
    subscribeShellContext(listener);
    listener.mockClear();
    updateShellContext({ activeSurface: 'core.x' });
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener.mock.calls[0]![0].activeSurface).toBe('core.x');
  });

  it('subscribers receive initial snapshot on subscribe', () => {
    updateShellContext({ activeSurface: 'core.x' });
    const listener = vi.fn();
    subscribeShellContext(listener);
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener.mock.calls[0]![0].activeSurface).toBe('core.x');
  });

  it('no-op updates do not fire listeners', () => {
    updateShellContext({ activeSurface: 'core.x' });
    const listener = vi.fn();
    subscribeShellContext(listener);
    listener.mockClear();
    // Same value — should be a no-op.
    updateShellContext({ activeSurface: 'core.x' });
    expect(listener).not.toHaveBeenCalled();
  });

  it('unsubscribe stops further events', () => {
    const listener = vi.fn();
    const off = subscribeShellContext(listener);
    listener.mockClear();
    off();
    updateShellContext({ activeSurface: 'core.y' });
    expect(listener).not.toHaveBeenCalled();
  });
});

describe('shellContextState — composite updates', () => {
  it('selection patch updates kind + count + capabilities together', () => {
    updateShellContext({
      selectionKind: 'search-hit',
      selectionCount: 2,
      selectionCapabilities: 'open,pin,export',
    });
    const ctx = getShellContext();
    expect(ctx.selectionKind).toBe('search-hit');
    expect(ctx.selectionCount).toBe(2);
    expect(ctx.selectionCapabilities).toBe('open,pin,export');
  });
});
