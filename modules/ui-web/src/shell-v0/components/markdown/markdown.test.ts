// SPDX-License-Identifier: Apache-2.0
/**
 * @vitest-environment happy-dom
 *
 * Tempdoc 846 — the markdown substrate: one configured parser, one typography ramp, one code theme.
 *
 * What each section pins is a behaviour the two consumers used to answer differently (or not at
 * all), so a regression here is a re-fork, not a cosmetic drift.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import DOMPurify from 'dompurify';
import { createMarkdownRenderer } from './markdownRenderer.js';
import { markdownCodeHighlight, markdownTypography } from './markdownStyles.js';
import {
  highlightCodeBlocks,
  isHighlighterLoaded,
  loadHighlighter,
  resetHighlighterForTest,
} from './markdownHighlight.js';
import { MarkdownBlock as MarkdownBlockClass } from '../chat/MarkdownBlock.js';
import type { Citation, MarkdownBlock } from '../chat/MarkdownBlock.js';
import '../chat/MarkdownBlock.js';
import { DocumentPane as DocumentPaneClass } from '../documentPane/DocumentPane.js';
import type { DocumentPane } from '../documentPane/DocumentPane.js';
import '../documentPane/DocumentPane.js';

/** A soft-wrapped paragraph — one paragraph to markdown, two lines to `breaks: true`. */
const SOFT_WRAPPED = 'The lease is renewed on every acquire,\nand released when the run ends.';

/**
 * The same text behind a lead paragraph. Under happy-dom, DOMPurify's `<remove></remove>` prefix
 * trick mis-parses and the sanitiser UNWRAPS the first element of every fragment (verified in
 * `MarkdownBlock.geometry.test.ts`: `<h1>a</h1><h2>b</h2>` sanitizes to `a<h2>b</h2>`). Any
 * assertion that counts or selects the FIRST rendered element would therefore be measuring the test
 * DOM, not the renderer — so fixtures that need their first element put a throwaway one in front.
 */
const LEAD = 'A lead paragraph the sanitiser can eat.';
const SOFT_WRAPPED_FIXTURE = `${LEAD}\n\n${SOFT_WRAPPED}`;

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

function cssTextOf(styles: unknown): string {
  const sheets = Array.isArray(styles) ? styles : [styles];
  return sheets.map((s) => (s as { cssText: string }).cssText).join('\n');
}

/* ── §2.1 the one configured parser ───────────────────────────────────────────────────────────── */

describe('createMarkdownRenderer — the options live in one place', () => {
  it('turns a soft-wrapped paragraph into ONE paragraph when breaks is off', () => {
    const html = createMarkdownRenderer({ breaks: false }).render(SOFT_WRAPPED_FIXTURE);
    expect(html).not.toContain('<br');
    // The lead is unwrapped by the sanitiser under happy-dom (see LEAD), so the ONE `<p>` left is
    // the soft-wrapped paragraph — proving its two source lines did not become two paragraphs.
    expect(html.match(/<p>/g)).toHaveLength(1);
    expect(html).toContain('every acquire,\nand released');
  });

  it('turns the same text into hard line breaks when breaks is on', () => {
    // The positive control: without it, "no <br>" would pass against a renderer that can never
    // emit one, and the parameter would be proving nothing.
    expect(createMarkdownRenderer({ breaks: true }).render(SOFT_WRAPPED)).toContain('<br');
  });

  it('renders the GFM dialect (tables, fences, task lists) for every consumer', () => {
    const md = createMarkdownRenderer({ breaks: false });
    expect(md.render('| a | b |\n| --- | --- |\n| 1 | 2 |')).toContain('<td>1</td>');
    expect(md.render('```js\nconst a = 1;\n```')).toContain('class="language-js"');
    expect(md.render('- [x] done')).toContain('type="checkbox"');
  });

  it('sanitizes — the parse→sanitize pair cannot be half-copied by a new consumer', () => {
    // Asserted on the CALL, not on the output: happy-dom's DOM is not faithful enough for
    // DOMPurify to actually strip (it does not even remove a `<script>` there), so an
    // output-shaped assertion would be testing the environment. What this file can prove is that
    // no parse leaves the factory unsanitized — which is the invariant that was duplicated.
    const spy = vi.spyOn(DOMPurify, 'sanitize');
    const html = createMarkdownRenderer({ breaks: false }).render('Text <script>x</script>.');
    expect(spy).toHaveBeenCalledTimes(1);
    expect(spy.mock.calls[0]![0]).toContain('<script>x</script>');
    expect(typeof html).toBe('string');
    spy.mockRestore();
  });

  it('returns empty HTML for empty input', () => {
    expect(createMarkdownRenderer({ breaks: false }).render('')).toBe('');
  });
});

/* ── §2.2 breaks is OFF for model-generated answers ───────────────────────────────────────────── */

describe('MarkdownBlock renders model prose as prose (846 §2.2)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('does NOT insert a <br> into a soft-wrapped model paragraph', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = SOFT_WRAPPED_FIXTURE;
    document.body.appendChild(el);
    await settle(el);
    const content = el.shadowRoot!.querySelector('.md-content')!;
    expect(content.querySelector('br')).toBeNull();
    expect(content.querySelectorAll('p')).toHaveLength(1); // the lead is unwrapped (see LEAD)
    el.remove();
  });

  it('still separates real paragraphs (a blank line is not a soft wrap)', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = `${LEAD}\n\nFirst paragraph.\n\nSecond paragraph.`;
    document.body.appendChild(el);
    await settle(el);
    expect(el.shadowRoot!.querySelectorAll('.md-content p')).toHaveLength(2);
    el.remove();
  });
});

/* ── §2.5 the strip is conditional on having sources to show ──────────────────────────────────── */

const CITED: Citation = {
  sentenceText: 'The lock is held for the run.',
  similarity: 0.9,
  sentenceIndex: 0,
  label: 1,
  detail: {
    parentDocId: 'f:/docs/x.md',
    startLine: 1,
    endLine: 2,
    startChar: 0,
    endChar: 30,
    excerpt: 'The lock is held for the run.',
  },
  hover: { excerpt: 'x', title: 'X', headingText: 'H' },
};

const WITH_TRAILING_LIST = 'The lock is held for the run.\n\nSources:\n[1] The lease doc';

describe('trailing-citation strip only fires when the UI has sources to show (846 §2.5)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('PRESERVES a model-written trailing list when the block has no citations', async () => {
    // The behaviour change: the strip's justification is that the interface presents the sources
    // itself. With no citations it presents nothing, so deleting the model's list would replace
    // information with silence.
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = WITH_TRAILING_LIST;
    document.body.appendChild(el);
    await settle(el);
    expect(el.shadowRoot!.querySelector('.md-content')!.textContent).toContain('The lease doc');
    el.remove();
  });

  it('STRIPS the same list once the block carries a citation', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = WITH_TRAILING_LIST;
    el.citations = [CITED];
    document.body.appendChild(el);
    await settle(el);
    const text = el.shadowRoot!.querySelector('.md-content')!.textContent ?? '';
    expect(text).not.toContain('The lease doc');
    expect(text).toContain('The lock is held for the run.');
    el.remove();
  });
});

/* ── §2.4 syntax highlighting, and its fallbacks ──────────────────────────────────────────────── */

function fixture(html: string): HTMLElement {
  const root = document.createElement('div');
  root.innerHTML = html;
  document.body.appendChild(root);
  return root;
}

describe('fenced-code highlighting (846 §2.4)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    resetHighlighterForTest();
  });

  it('leaves every block plain until the lazy chunk resolves, then highlights it', async () => {
    const root = fixture('<pre><code class="language-js">const a = 1;</code></pre>');
    expect(isHighlighterLoaded()).toBe(false);
    highlightCodeBlocks(root);
    // Synchronously after the call the chunk has not resolved: plain monospace, untouched text.
    expect(root.querySelector('.hljs-keyword')).toBeNull();
    expect(root.querySelector('code')!.textContent).toBe('const a = 1;');

    await loadHighlighter();
    await new Promise((r) => setTimeout(r, 0));
    expect(isHighlighterLoaded()).toBe(true);
    expect(root.querySelector('.hljs-keyword')).toBeTruthy();
    // Highlighting is presentation only — the code itself is unchanged.
    expect(root.querySelector('code')!.textContent).toBe('const a = 1;');
  });

  it('falls back to plain for an unknown language, and says so', async () => {
    await loadHighlighter();
    const root = fixture('<pre><code class="language-brainfuck">+[-&gt;+&lt;]</code></pre>');
    highlightCodeBlocks(root);
    const code = root.querySelector('code') as HTMLElement;
    expect(code.dataset.hl).toBe('plain');
    expect(code.querySelector('span')).toBeNull();
  });

  it('falls back to plain for a fence that names no language', async () => {
    await loadHighlighter();
    const root = fixture('<pre><code>just some text</code></pre>');
    highlightCodeBlocks(root);
    const code = root.querySelector('code') as HTMLElement;
    expect(code.dataset.hl).toBe('plain');
    expect(code.textContent).toBe('just some text');
  });

  it('resolves a grammar through its alias and records the canonical name', async () => {
    await loadHighlighter();
    const root = fixture('<pre><code class="language-py">def f(): return 1</code></pre>');
    highlightCodeBlocks(root);
    // `hljs` reports the grammar's display name for the alias it resolved — the marker records
    // WHICH grammar ran, which is the thing worth being able to read off the DOM.
    expect((root.querySelector('code') as HTMLElement).dataset.hl).toBe('Python');
  });

  it('escapes markup in the code it highlights — the guard on not re-sanitizing', async () => {
    // §2.4 decided the highlighted HTML is NOT run back through DOMPurify, on the grounds that the
    // input is textContent and `hljs` escapes what it is given. That is a SECURITY decision resting
    // on a library behaviour, so it gets a regression test: a future `hljs` bump that stops escaping
    // must fail here rather than ship. Built programmatically — assigning this string to innerHTML
    // would let the DOM parser close the block and make the fixture prove nothing.
    await loadHighlighter();
    const root = document.createElement('div');
    const pre = document.createElement('pre');
    const code = document.createElement('code');
    code.className = 'language-js';
    code.textContent = 'const a = 1; // </code></pre><script>alert(1)</script>';
    pre.appendChild(code);
    root.appendChild(pre);
    document.body.appendChild(root);

    highlightCodeBlocks(root);
    const el = root.querySelector('code') as HTMLElement;
    expect(el.dataset.hl).toBeTruthy();
    expect(el.dataset.hl).not.toBe('plain'); // the grammar really ran
    expect(el.innerHTML).not.toContain('<script');
    expect(el.innerHTML).toContain('&lt;script');
    // The block is still one <code> — nothing escaped its container.
    expect(root.querySelectorAll('code')).toHaveLength(1);
    expect(root.querySelector('script')).toBeNull();
    // …and the reader still sees the original text.
    expect(el.textContent).toBe('const a = 1; // </code></pre><script>alert(1)</script>');
  });

  it('is idempotent — a second pass does not re-wrap an already-highlighted block', async () => {
    await loadHighlighter();
    const root = fixture('<pre><code class="language-json">{"a": 1}</code></pre>');
    highlightCodeBlocks(root);
    const first = root.querySelector('code')!.innerHTML;
    highlightCodeBlocks(root);
    expect(root.querySelector('code')!.innerHTML).toBe(first);
  });

  // The load-FAILURE fallback lives in `markdownHighlight.failure.test.ts`: making the chunk
  // genuinely unavailable needs a file-scoped `vi.mock`, and asserting it against a chunk that
  // loads fine would pass for the wrong reason.

  it('highlights a settled answer in MarkdownBlock, and skips a streaming one', async () => {
    document.body.innerHTML = '';
    await loadHighlighter();

    const streaming = document.createElement('jf-markdown-block') as MarkdownBlock;
    streaming.isStreaming = true;
    streaming.text = `${LEAD}\n\n\`\`\`js\nconst a = 1;\n\`\`\``;
    document.body.appendChild(streaming);
    await settle(streaming);
    // Mid-stream the fence is still churning, so the block stays plain (no `data-hl` at all).
    expect(
      (streaming.shadowRoot!.querySelector('pre code') as HTMLElement | null)?.dataset.hl,
    ).toBeUndefined();
    streaming.remove();

    const settled = document.createElement('jf-markdown-block') as MarkdownBlock;
    settled.text = `${LEAD}\n\n\`\`\`js\nconst a = 1;\n\`\`\``;
    document.body.appendChild(settled);
    await settle(settled);
    expect(settled.shadowRoot!.querySelector('.md-content .hljs-keyword')).toBeTruthy();
    settled.remove();
  });
});

/* ── §2.3 both surfaces wear the same ramp ────────────────────────────────────────────────────── */

describe('the typography ramp is shared, not copied (846 §2.3)', () => {
  it('is the SAME stylesheet object in both consumers', () => {
    // Identity, not similarity: two `css` templates with equal text would still be two authorities.
    const chat = MarkdownBlockClass.styles as unknown as readonly unknown[];
    const pane = DocumentPaneClass.styles as unknown as readonly unknown[];
    expect(Array.isArray(chat)).toBe(true);
    expect(Array.isArray(pane)).toBe(true);
    expect(chat).toContain(markdownTypography);
    expect(pane).toContain(markdownTypography);
    expect(chat).toContain(markdownCodeHighlight);
    expect(pane).toContain(markdownCodeHighlight);
  });

  it('carries the rules the inspector used to leave to the user agent', () => {
    const text = cssTextOf(DocumentPaneClass.styles);
    expect(text).toContain('.md-content pre');
    expect(text).toContain('.md-content blockquote');
    expect(text).toContain(':host([prose]) .md-content h1');
    expect(text).toContain(':host([prose]) .md-content table');
  });

  it('reads the shared block-gap token for the pane’s own block wrapper', () => {
    // The wrapper survives (one block per wrapper is what carries the line range), but its rhythm
    // is no longer a private literal.
    const rule = /\.blocks \.block \{([^}]*)\}/.exec(cssTextOf(DocumentPaneClass.styles))![1]!;
    expect(rule).toContain('var(--md-block-gap)');
  });
});

describe('DocumentPane rendered mode wears the ramp (846 §2.3)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  async function renderedPane(markdown: string): Promise<DocumentPane> {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: true,
        status: 200,
        json: async () => ({ content: markdown, textProvenance: null }),
      })),
    );
    const el = document.createElement('jf-document-pane') as DocumentPane;
    document.body.appendChild(el);
    el.docPath = 'f:/docs/guide.md';
    await new Promise((r) => setTimeout(r, 0));
    await el.updateComplete;
    return el;
  }

  it('scopes the rendered blocks with the class the shared rules select', async () => {
    const el = await renderedPane('# Title\n\nBody text.\n\n- item a\n- item b');
    const blocks = el.shadowRoot!.querySelector('.blocks') as HTMLElement;
    expect(blocks).toBeTruthy();
    // Without this class NONE of the shared `.md-content …` rules can reach the rendered markup —
    // which is exactly the state this tempdoc found the inspector in.
    expect(blocks.classList.contains('md-content')).toBe(true);
    // Each block is sanitized on its own, so under happy-dom each loses its OUTER element (see
    // LEAD); the list items are what survives, and they are what `.md-content li` dresses.
    expect(blocks.querySelectorAll('li')).toHaveLength(2);
    expect(blocks.textContent).toContain('Title');
    el.remove();
  });

  it('opts into the prose variant on its host, so heading/table rules apply', async () => {
    const el = await renderedPane('# Title\n\nBody text.');
    // The variant is selector-gated on the host attribute; unset, a document renders unstyled.
    expect(el.hasAttribute('prose')).toBe(true);
    el.remove();
  });

  it('renders a soft-wrapped file paragraph as flowing text, not ragged lines', async () => {
    // The behaviour change of §2.2 on the pane side: an authored `.md` file is hard-wrapped by
    // convention, and `breaks: true` was rendering it as a column of forced short lines.
    const el = await renderedPane(SOFT_WRAPPED);
    const blocks = el.shadowRoot!.querySelector('.blocks')!;
    expect(blocks.querySelector('br')).toBeNull();
    expect(blocks.querySelectorAll('.block')).toHaveLength(1);
    el.remove();
  });

  it('highlights fenced code in a rendered document', async () => {
    resetHighlighterForTest();
    await loadHighlighter();
    const el = await renderedPane('Intro line.\n\n```python\ndef f():\n    return 1\n```');
    await new Promise((r) => setTimeout(r, 0));
    expect(el.shadowRoot!.querySelector('.blocks .hljs-keyword')).toBeTruthy();
    el.remove();
  });
});
