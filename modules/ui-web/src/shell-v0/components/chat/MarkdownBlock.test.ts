/**
 * Slice 497 — MarkdownBlock mend pass tests + Tempdoc 565 §3.C citation decoration.
 *
 * Tests the mendMarkdown function that auto-closes unclosed markdown
 * syntax during streaming, and the inline `[n]` citation decoration.
 *
 * @vitest-environment happy-dom
 */

import { describe, expect, it } from 'vitest';
import {
  mendMarkdown,
  stripTrailingCitationBlock,
  type MarkdownBlock,
  type MarkdownCitation,
} from './MarkdownBlock.js';
import './MarkdownBlock.js';

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

function mark(sentenceText: string, similarity = 0.8, label = 1): MarkdownCitation {
  return {
    sentenceText,
    similarity,
    label,
    detail: { parentDocId: 'f:/docs/x.md', startLine: 1, endLine: 5, startChar: 0, endChar: 0, excerpt: 'x' },
    hover: { excerpt: 'an excerpt', title: 'X', headingText: '' },
  };
}

describe('mendMarkdown', () => {
  it('returns text unchanged when no unclosed syntax', () => {
    const text = '# Hello\n\nThis is **bold** and `code`.\n\n```js\nconst x = 1;\n```\n';
    expect(mendMarkdown(text)).toBe(text);
  });

  it('closes an unclosed code fence with matching backticks', () => {
    const text = 'paragraph\n\n```javascript\nconst x = 1;\nmore code';
    const mended = mendMarkdown(text);
    expect(mended).toContain('```');
    expect(mended.match(/```/g)!.length).toBe(2);
  });

  it('closes an unclosed tilde fence', () => {
    const text = 'paragraph\n\n~~~python\nimport os';
    const mended = mendMarkdown(text);
    expect(mended).toContain('~~~');
    expect(mended.match(/~~~/g)!.length).toBe(2);
  });

  it('matches fence length for longer fences', () => {
    const text = 'paragraph\n\n````\ncode here';
    const mended = mendMarkdown(text);
    expect(mended.endsWith('````')).toBe(true);
  });

  it('does not close fences that are already paired', () => {
    const text = '```js\ncode\n```\n\nMore text.';
    expect(mendMarkdown(text)).toBe(text);
  });

  it('closes unclosed bold (**)', () => {
    const text = 'This is **bold text without closing';
    const mended = mendMarkdown(text);
    expect(mended).toBe(text + '**');
  });

  it('does not close bold when already paired', () => {
    const text = 'This is **bold** text.';
    expect(mendMarkdown(text)).toBe(text);
  });

  it('closes unclosed italic (*)', () => {
    const text = 'This is *italic without closing';
    const mended = mendMarkdown(text);
    expect(mended).toBe(text + '*');
  });

  it('closes unclosed inline code (`)', () => {
    const text = 'This has `unclosed code';
    const mended = mendMarkdown(text);
    expect(mended).toBe(text + '`');
  });

  it('does not close inline markers when inside a code fence', () => {
    const text = '```\nThis has **unclosed bold inside code';
    const mended = mendMarkdown(text);
    // Should close the fence but NOT close the bold (it's inside code)
    expect(mended).toContain('```\nThis has **unclosed bold inside code\n```');
    expect(mended.match(/\*\*/g)!.length).toBe(1);
  });

  it('handles empty text', () => {
    expect(mendMarkdown('')).toBe('');
  });

  it('handles text with no markdown', () => {
    const text = 'Just plain text with no special characters.';
    expect(mendMarkdown(text)).toBe(text);
  });
});

describe('stripTrailingCitationBlock (565 §13.8 — UI is the source authority)', () => {
  it('strips a trailing scored "Citations:" list, preserving the answer + inline marks', () => {
    const text =
      'The kernel is a shared substrate [1]. It composes into governed projections [2].\n\n' +
      'Citations: [1] AI Architecture (score: 1.00)\n[2] Governed Projections (score: 0.91)';
    expect(stripTrailingCitationBlock(text)).toBe(
      'The kernel is a shared substrate [1]. It composes into governed projections [2].',
    );
  });

  it('strips a "Sources:" and a "References:" heading too (case-insensitive)', () => {
    expect(stripTrailingCitationBlock('Answer text [1].\n\nSources:\n- [1] Doc A')).toBe('Answer text [1].');
    expect(stripTrailingCitationBlock('Answer text [1].\n\nREFERENCES:\n[1] Doc A')).toBe('Answer text [1].');
  });

  it('strips an ATX-heading or bold-wrapped citation list', () => {
    expect(stripTrailingCitationBlock('Body [1].\n\n## Citations\n[1] Doc')).toBe('Body [1].');
    expect(stripTrailingCitationBlock('Body [1].\n\n**Sources:**\n[1] Doc')).toBe('Body [1].');
  });

  it('does NOT strip a heading that lacks any [n] reference (not a citation list)', () => {
    const text = 'See the design.\n\nSources of truth:\nThe single authority is the projection.';
    expect(stripTrailingCitationBlock(text)).toBe(text);
  });

  it('does NOT strip a mid-prose "Sources:" sentence (no trailing-to-EOF list shape)', () => {
    const text = 'Sources: the index and the model. We fuse them [1], then rerank. Final answer here.';
    expect(stripTrailingCitationBlock(text)).toBe(text);
  });

  it('leaves text with inline marks but no trailing list untouched', () => {
    const text = 'The answer cites two passages [1] and [2] inline, with no appended list.';
    expect(stripTrailingCitationBlock(text)).toBe(text);
  });

  it('handles empty / undefined-ish input', () => {
    expect(stripTrailingCitationBlock('')).toBe('');
  });
});

describe('MarkdownBlock citation decoration (565 §3.C)', () => {
  it('weaves an inline [n] mark for a matched sentence', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = 'The kernel is a shared substrate. A second sentence.';
    el.citations = [mark('The kernel is a shared substrate.')];
    document.body.appendChild(el);
    await settle(el);
    const marks = el.shadowRoot?.querySelectorAll('.cite-ref');
    expect(marks?.length).toBe(1);
    expect(marks?.[0]?.textContent).toBe('1');
    el.remove();
  });

  it('skips gracefully when the cited sentence is absent from the answer', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = 'Totally different content here.';
    el.citations = [mark('A sentence that does not appear at all.')];
    document.body.appendChild(el);
    await settle(el);
    expect(el.shadowRoot?.querySelectorAll('.cite-ref').length ?? 0).toBe(0);
    el.remove();
  });

  it('matches across markdown emphasis markers', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = 'The **kernel** is a shared substrate.';
    el.citations = [mark('The **kernel** is a shared substrate.')];
    document.body.appendChild(el);
    await settle(el);
    expect(el.shadowRoot?.querySelectorAll('.cite-ref').length).toBe(1);
    el.remove();
  });

  // Tempdoc 565 §15.C fix — a cited sentence that crosses inline markup now still tier-colors its
  // text runs (multi-node body wrap), instead of silently getting the mark only.
  it('tier-colors the cited-sentence body across inline markup (multi-node wrap)', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = 'The **kernel** is a shared substrate.';
    el.citations = [mark('The **kernel** is a shared substrate.', 0.8)]; // 0.8 → high → 'grounded'
    document.body.appendChild(el);
    await settle(el);
    const wraps = el.shadowRoot?.querySelectorAll('.cite-sentence.grounding-grounded');
    // the text runs flanking the <strong> are each wrapped — more than one, all grounded-tier
    expect((wraps?.length ?? 0)).toBeGreaterThan(1);
    // the <strong> markup survives intact (no cross-element corruption)
    expect(el.shadowRoot?.querySelector('.md-content strong')?.textContent).toBe('kernel');
    el.remove();
  });

  it('dispatches citation-select with the deep-link detail on click', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = 'Click target sentence.';
    el.citations = [mark('Click target sentence.')];
    let detail: { parentDocId?: string } | null = null;
    el.addEventListener('citation-select', (e) => {
      detail = (e as CustomEvent).detail as { parentDocId?: string };
    });
    document.body.appendChild(el);
    await settle(el);
    (el.shadowRoot?.querySelector('.cite-ref') as HTMLElement | null)?.click();
    expect(detail).toBeTruthy();
    expect(detail!.parentDocId).toBe('f:/docs/x.md');
    el.remove();
  });
});

// Tempdoc 577 §2.12 Move 3 — pseudo-citation neutralization in an ungrounded answer.
describe('MarkdownBlock ungrounded frame — citation-shaped text is neutralized', () => {
  it('wraps model-authored [n]/(n) markers in a muted, non-interactive span when frame=ungrounded', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = 'JustSearch uses a 4-tier strategy [1] and chaos testing (2).';
    el.frame = 'ungrounded';
    document.body.appendChild(el);
    await settle(el);
    const pseudo = el.shadowRoot?.querySelectorAll('.pseudo-cite');
    expect(pseudo?.length).toBe(2); // [1] and (2)
    expect(Array.from(pseudo ?? []).map((s) => s.textContent)).toEqual(['[1]', '(2)']);
    // They are NOT the real clickable cite-refs — no accent superscript, no handlers.
    expect(el.shadowRoot?.querySelectorAll('.cite-ref').length ?? 0).toBe(0);
    el.remove();
  });

  it('leaves citation-shaped text untouched in a grounded answer (default frame)', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = 'A grounded answer [1].';
    // default frame = 'grounded' — no neutralization.
    document.body.appendChild(el);
    await settle(el);
    expect(el.shadowRoot?.querySelectorAll('.pseudo-cite').length ?? 0).toBe(0);
    el.remove();
  });
});
