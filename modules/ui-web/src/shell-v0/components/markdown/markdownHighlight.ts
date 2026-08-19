// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 846 §2.4 — syntax highlighting for fenced code, applied after render.
 *
 * This module is what the components import, and it is deliberately tiny: the highlighter itself
 * lives behind the dynamic `import()` below, so the main bundle carries a loader and a DOM walk,
 * not a grammar set.
 *
 * Why a post-render DOM pass rather than a `marked` renderer hook: the parse is synchronous (a
 * stream re-renders per frame) while the highlighter arrives asynchronously. A renderer hook would
 * have to either block the first paint on a chunk load or permanently give up on the blocks that
 * rendered before it arrived. The pass instead highlights what it can NOW and re-visits the same
 * root once the chunk resolves.
 *
 * Fallbacks — every one of them renders as the plain monospace the app renders today:
 *   - the highlighter has not loaded yet (first code block on screen);
 *   - the fence names no language, or a language outside the registered set;
 *   - the chunk fails to load, or a grammar throws.
 */
import type { HLJSApi } from 'highlight.js';

/** Set once the chunk has resolved; `null` until then (and after a failed load). */
let highlighter: HLJSApi | null = null;
/** Single-flight: many code blocks on screen must not each trigger a chunk load. */
let loading: Promise<HLJSApi | null> | null = null;
/** A failed load is not retried on every render — the surface stays plain, silently. */
let failed = false;

/** True once the highlighter chunk is resolved and usable (the synchronous path). */
export function isHighlighterLoaded(): boolean {
  return highlighter !== null;
}

/**
 * Load the highlighter chunk (idempotent, single-flight). Resolves to `null` if it cannot be
 * loaded — callers treat that exactly like "no language": plain monospace.
 */
export function loadHighlighter(): Promise<HLJSApi | null> {
  if (highlighter) return Promise.resolve(highlighter);
  if (failed) return Promise.resolve(null);
  loading ??= import('./markdownHighlightRuntime.js')
    .then((mod) => {
      highlighter = mod.createHighlighter();
      return highlighter;
    })
    .catch(() => {
      // A missing chunk (offline-broken install, a build that dropped it) degrades the code block,
      // never the answer around it.
      failed = true;
      return null;
    })
    .finally(() => {
      loading = null;
    });
  return loading;
}

/** `<pre><code class="language-x">` → `x`; a bare `<code>` inside `<pre>` yields `null`. */
function fencedLanguage(code: Element): string | null {
  for (const cls of code.classList) {
    if (cls.startsWith('language-')) return cls.slice('language-'.length).toLowerCase();
  }
  return null;
}

function applyTo(hljs: HLJSApi, root: ParentNode): void {
  // Both halves are load-bearing. `pre > code` is the fenced block as `marked` emits it; the
  // language-class form catches the same block where the `<pre>` did not survive whatever inserted
  // it (`DocumentPane` sanitizes each block separately, and a sanitizer may unwrap the outer
  // element). A language class is only ever emitted for a FENCE — an inline chip never carries one —
  // so the second selector cannot reach inline code.
  for (const code of root.querySelectorAll('pre > code, code[class*="language-"]')) {
    const el = code as HTMLElement;
    // Idempotent: a re-render rebuilds the subtree (so the marker goes with it), while an
    // already-marked block is one this pass has settled — highlighted or deliberately plain.
    if (el.dataset.hl) continue;
    // A fenced block's content is escaped TEXT, so element children mean something else already
    // wove into it (a citation mark, a highlight from an earlier pass). Rewriting `innerHTML` would
    // discard that work — this pass yields instead. Matters for the DEFERRED path: when the chunk
    // resolves after the citation weave has run, the guard is what keeps the weave.
    if (el.children.length > 0) continue;
    const lang = fencedLanguage(el);
    const resolved = lang ? hljs.getLanguage(lang) : undefined;
    if (!lang || !resolved) {
      el.dataset.hl = 'plain';
      continue;
    }
    try {
      // `ignoreIllegals` — a grammar that rejects its input must not throw away a code block the
      // reader can otherwise read (truncated model output, a snippet quoted mid-expression).
      const { value } = hljs.highlight(el.textContent ?? '', {
        language: lang,
        ignoreIllegals: true,
      });
      // Not re-sanitized, and deliberately so: the input is `textContent` (never markup — the
      // markdown was already parsed and sanitized by the shared factory), and `hljs` escapes what
      // it is given, so there is no unescaped-HTML path into this assignment. The sanitizer belongs
      // where untrusted markdown ENTERS the pipeline, which is `markdownRenderer.ts`.
      el.innerHTML = value;
      el.dataset.hl = resolved.name ?? lang;
    } catch {
      el.dataset.hl = 'plain';
    }
  }
}

/**
 * Highlight every fenced code block under `root`, now if the highlighter is loaded and otherwise as
 * soon as it is. Safe to call on every settled render: already-settled blocks are skipped.
 *
 * Callers pass the container element they render INTO (which survives re-renders), not a snapshot
 * of its children — so the deferred pass writes into the live tree. A root that has since been
 * replaced simply gets no visible effect, and the render that replaced it calls this again.
 */
export function highlightCodeBlocks(root: ParentNode | null | undefined): void {
  if (!root) return;
  if (highlighter) {
    applyTo(highlighter, root);
    return;
  }
  if (failed) return;
  void loadHighlighter().then((hljs) => {
    if (hljs) applyTo(hljs, root);
  });
}

/** Test seam: forget the loaded highlighter so a test can exercise the not-yet-loaded path. */
export function resetHighlighterForTest(): void {
  highlighter = null;
  loading = null;
  failed = false;
}
