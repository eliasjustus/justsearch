// SPDX-License-Identifier: Apache-2.0
/**
 * @vitest-environment happy-dom
 *
 * Tempdoc 846 §2.4 — the adverse precondition, in its own file because the only honest way to make
 * the lazily-imported chunk unavailable is to mock the module, and `vi.mock` is file-scoped.
 *
 * A code block whose highlighter cannot load must render exactly like one whose language is
 * unknown: readable plain monospace. Testing this against a chunk that actually loads would pass
 * for the wrong reason.
 */
import { describe, expect, it, vi } from 'vitest';

/** Hoisted so the `vi.mock` factory (which vitest lifts above the imports) can count its own calls. */
const chunk = vi.hoisted(() => ({ attempts: 0 }));

vi.mock('./markdownHighlightRuntime.js', () => {
  chunk.attempts += 1;
  throw new Error('chunk unavailable');
});

import {
  highlightCodeBlocks,
  isHighlighterLoaded,
  loadHighlighter,
} from './markdownHighlight.js';

describe('a highlighter that cannot load degrades to plain, never to a broken render', () => {
  it('leaves the code intact and reports itself unloaded', async () => {
    const root = document.createElement('div');
    root.innerHTML = '<pre><code class="language-js">const a = 1;</code></pre>';
    document.body.appendChild(root);

    highlightCodeBlocks(root);
    expect(await loadHighlighter()).toBeNull();
    await new Promise((r) => setTimeout(r, 0));

    expect(isHighlighterLoaded()).toBe(false);
    expect(root.querySelector('code')!.textContent).toBe('const a = 1;');
    expect(root.querySelector('span')).toBeNull();
    // The mock really was reached — otherwise "degrades to plain" would be true of a test in which
    // nothing was ever attempted.
    expect(chunk.attempts).toBeGreaterThan(0);
  });

  it('does not retry the failed import on every later render', async () => {
    // The load is attempted ONCE; after that the surface stays plain silently rather than
    // re-attempting a failing dynamic import per frame of a stream. Asserted on the factory's call
    // count, because "still plain" is equally true of a module that re-throws on every render —
    // which is the behaviour this short-circuit exists to prevent.
    const before = chunk.attempts;
    await loadHighlighter();
    const root = document.createElement('div');
    root.innerHTML = '<pre><code class="language-python">def f(): return 1</code></pre>';
    document.body.appendChild(root);
    highlightCodeBlocks(root);
    await new Promise((r) => setTimeout(r, 0));
    highlightCodeBlocks(root);
    expect(await loadHighlighter()).toBeNull();
    expect(root.querySelector('code')!.textContent).toBe('def f(): return 1');
    expect(chunk.attempts).toBe(before);
  });
});
