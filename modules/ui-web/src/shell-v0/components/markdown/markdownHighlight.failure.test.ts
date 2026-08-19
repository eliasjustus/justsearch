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

vi.mock('./markdownHighlightRuntime.js', () => {
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
  });

  it('does not retry the failed import on every later render', async () => {
    // The load is attempted once; after that the surface stays plain silently rather than
    // re-attempting a failing dynamic import per frame of a stream.
    await loadHighlighter();
    const root = document.createElement('div');
    root.innerHTML = '<pre><code class="language-python">def f(): return 1</code></pre>';
    document.body.appendChild(root);
    highlightCodeBlocks(root);
    await new Promise((r) => setTimeout(r, 0));
    expect(root.querySelector('code')!.textContent).toBe('def f(): return 1');
    expect(await loadHighlighter()).toBeNull();
  });
});
