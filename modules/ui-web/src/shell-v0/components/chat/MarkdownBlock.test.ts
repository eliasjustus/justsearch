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
  MarkdownBlock as MarkdownBlockClass,
  type MarkdownBlock,
  type MarkdownCitation,
} from './MarkdownBlock.js';
import './MarkdownBlock.js';
// Tempdoc 822 §3d — the density fixtures resolve through the REAL gate, not a hand-built citation
// list, so the test fails if the provenance split is ever undone upstream of the renderer.
import { claimsToCitations } from './citationResolve.js';
import type { Claim, RetrievalCitation } from './citationTypes.js';

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

// ---------------------------------------------------------------------------
// Tempdoc 687 R3a — literal "[n]" token normalization (one citation notation)
// + R1c grounding-mark inversion (P2: mark the exception, not the rule).
// ---------------------------------------------------------------------------
describe('MarkdownBlock 687 R3a — literal [n] normalization', () => {
  async function mounted(text: string, cites = [mark('The kernel is a shared substrate.')]) {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = text;
    el.citations = cites;
    document.body.appendChild(el);
    await el.updateComplete;
    await new Promise((r) => setTimeout(r, 0));
    return el;
  }

  it('strips a literal [n] whose citation already carries a rendered marker', async () => {
    const el = await mounted('The kernel is a shared substrate. [1] More prose.');
    const content = el.renderRoot.querySelector('.md-content')!;
    expect(content.querySelectorAll('.cite-ref').length).toBe(1); // the woven marker only
    expect(content.textContent).not.toContain('[1]');
    el.remove();
  });

  it('upgrades a literal [n] to a marker when the sentence match failed', async () => {
    // sentenceText will not match, so no woven marker — the literal token becomes the marker.
    const el = await mounted('Unmatched prose with a bare token [1] inline.', [
      mark('A sentence that does not appear at all.'),
    ]);
    const content = el.renderRoot.querySelector('.md-content')!;
    expect(content.querySelectorAll('.cite-ref').length).toBe(1);
    expect(content.textContent).not.toContain('[1]');
    el.remove();
  });

  it('leaves [n] untouched inside code and when no citation matches the number', async () => {
    const el = await mounted('Use `arr[1]` in code. A quote says [7] here. The kernel is a shared substrate.');
    const content = el.renderRoot.querySelector('.md-content')!;
    expect(content.querySelector('code')!.textContent).toContain('arr[1]');
    expect(content.textContent).toContain('[7]'); // only 1 citation exists — [7] is not a citation
    el.remove();
  });
});

describe('MarkdownBlock 687 R1c — grounding-mark inversion', () => {
  it('a high-similarity sentence gets the grounded class (whose CSS is now unmarked)', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = 'The kernel is a shared substrate.';
    el.citations = [mark('The kernel is a shared substrate.', 0.9)];
    document.body.appendChild(el);
    await el.updateComplete;
    await new Promise((r) => setTimeout(r, 0));
    const span = el.renderRoot.querySelector('.cite-sentence');
    expect(span?.className).toContain('grounding-grounded');
    el.remove();
  });
});

/* ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Tempdoc 822 §3c + §3d — the tier palette and THE UNDERLINE-DENSITY ACCEPTANCE TEST.
 *
 * §3d makes 687 R1c's falsifier executable: the component's own principle is "mark the exception,
 * not the rule", and the gap report measured 98.5 % of body characters underlined. X = 25 % — one
 * sentence in four on a typical 4-8 sentence answer, the largest density at which "look at the
 * marked one" is still an instruction. The bound is NOT a cap on the renderer: the adversarial
 * fixture below proves a genuinely weak answer still renders weak everywhere.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════ */

/** The X of §3d — a design bound on a fixture, not a claim about the live score distribution. */
const MAX_UNDERLINE_DENSITY = 0.25;

const DENSITY_SOURCES: RetrievalCitation[] = [0, 1, 2].map((i) => ({
  parentDocId: `docs/s${i}.md`,
  chunkIndex: i,
  chunkTotal: 3,
  startChar: 0,
  endChar: 80,
  score: 0.8,
  excerpt: `passage ${i}`,
  startLine: 1 + i,
  endLine: 4 + i,
  headingText: 'Notes',
  headingLevel: 2,
}));

/**
 * The realistic fixture of §3d: a 6-sentence answer the cross-encoder verified on five sentences
 * (0.81, 0.74, 0.69, 0.63, 0.55) while the streaming lexical matcher ALSO fired low-overlap deltas —
 * three on sentences the cross-encoder had already scored (the doubly-matched case, where the old
 * `Math.max` let the wrong scale win) and one on the sentence it never verified. This is the shape
 * the gap report measured, minus the defect.
 *
 * DEVIATION, stated: §3d's prose asks for "4 lexical-only deltas on sentences the matcher did not
 * verify" while also specifying 5 verified claims in a 6-sentence answer — only one such sentence
 * exists. Both routes a lexical score could take are therefore exercised instead (doubly-matched and
 * never-verified), which is strictly the stronger fixture.
 */
const REALISTIC_SENTENCES = [
  'The kernel is a shared substrate for every governed projection.',
  'Its catalogs are generated from one schema and never hand-edited.',
  'The worker owns all index IO and the head never touches Lucene.',
  'Search analysis stays locale invariant across the whole corpus.',
  'Reranking runs on the cross encoder before the answer is drafted.',
  'Operators can tune the retrieval budget from the settings surface.',
];

/** Verified similarity per sentence index; a missing entry = the matcher never verified it. */
const REALISTIC_VERIFIED = new Map<number, number>([
  [0, 0.81],
  [1, 0.74],
  [2, 0.69],
  [3, 0.63],
  [4, 0.55],
]);
/** Lexical word-overlap ratios that arrived as `rag.citation_delta` — never a tier input. */
const REALISTIC_LEXICAL = new Map<number, number>([
  [1, 0.1],
  [2, 0.18],
  [3, 0.25],
  [5, 0.33],
]);

function realisticClaims(): Claim[] {
  return REALISTIC_SENTENCES.map((sentenceText, i) => ({
    sentenceIndex: i,
    sentenceText,
    verifiedScore: REALISTIC_VERIFIED.get(i) ?? null,
    lexicalScore: REALISTIC_LEXICAL.get(i) ?? 0,
    verifiedRefs: [i % 3],
    lexicalRefs: [],
  }));
}

/**
 * The pre-822 merge, reproduced exactly: ONE score per claim, `Math.max` across the two scales. Used
 * to measure the same fixture's density BEFORE the gate, so the test states what it fixed rather
 * than merely passing.
 */
function preGateClaims(): Claim[] {
  return realisticClaims().map((c) => ({
    ...c,
    verifiedScore: Math.max(c.verifiedScore ?? 0, c.lexicalScore),
  }));
}

async function renderAnswer(sentences: readonly string[], claims: Claim[]): Promise<MarkdownBlock> {
  const el = document.createElement('jf-markdown-block') as MarkdownBlock;
  el.text = sentences.join(' ');
  el.citations = claimsToCitations(claims, DENSITY_SOURCES);
  document.body.appendChild(el);
  await el.updateComplete;
  await new Promise((r) => setTimeout(r, 0));
  return el;
}

/**
 * The gap report's measure, made runnable: the fraction of the answer BODY's characters that carry a
 * dotted underline. Only `grounding-weak` and `grounding-ungrounded` draw one (`grounding-grounded`
 * is `border-bottom: none` since 687 R1c). The `[n]` superscripts are excluded from the denominator:
 * they are marks, not body prose.
 */
function underlineDensity(el: MarkdownBlock): { underlined: number; body: number; ratio: number } {
  const content = el.renderRoot.querySelector('.md-content')!;
  const markChars = [...content.querySelectorAll('.cite-ref')].reduce(
    (n, m) => n + (m.textContent?.length ?? 0),
    0,
  );
  const body = (content.textContent?.length ?? 0) - markChars;
  const underlined = [
    ...content.querySelectorAll('.cite-sentence.grounding-weak, .cite-sentence.grounding-ungrounded'),
  ].reduce((n, s) => n + (s.textContent?.length ?? 0), 0);
  return { underlined, body, ratio: body === 0 ? 0 : underlined / body };
}

describe('MarkdownBlock 822 §3d — the underline-density acceptance test', () => {
  it('realistic answer: exactly one weak span, no ungrounded span, and underlines ≤ 25 % of the body', async () => {
    const el = await renderAnswer(REALISTIC_SENTENCES, realisticClaims());
    const content = el.renderRoot.querySelector('.md-content')!;

    // (1) the 0.55 sentence is the one weak span; nothing renders ungrounded.
    expect(content.querySelectorAll('.cite-sentence.grounding-weak').length).toBe(1);
    expect(content.querySelectorAll('.cite-sentence.grounding-ungrounded').length).toBe(0);
    expect(
      content.querySelector('.cite-sentence.grounding-weak')?.textContent?.trim(),
    ).toBe(REALISTIC_SENTENCES[4]);

    // (2) the density bound.
    const { ratio } = underlineDensity(el);
    expect(ratio).toBeLessThanOrEqual(MAX_UNDERLINE_DENSITY);

    // (3) no mark traces to a lexical-only claim: five verified claims ⇒ five marks, and the
    // never-verified sentence carries neither a mark nor a tier wrap.
    expect(content.querySelectorAll('.cite-ref').length).toBe(5);
    const lexicalOnly = REALISTIC_SENTENCES[5]!;
    const wrapped = [...content.querySelectorAll('.cite-sentence')].map((s) => s.textContent?.trim());
    expect(wrapped).not.toContain(lexicalOnly);
    expect(content.textContent).toContain(lexicalOnly); // the prose is intact — it is simply unmarked
    el.remove();
  });

  it('the SAME fixture breached the bound before the gate — the density is the defect being fixed', async () => {
    const el = await renderAnswer(REALISTIC_SENTENCES, preGateClaims());
    const { ratio } = underlineDensity(el);
    // With one score per claim and `Math.max` across the two scales, the never-verified sentence's
    // 0.33 word overlap reads `ungrounded` and the lexical deltas underline prose the cross-encoder
    // never backed. The bound fails — which is exactly what 687 R1c's falsifier is for.
    expect(ratio).toBeGreaterThan(MAX_UNDERLINE_DENSITY);
    el.remove();
  });

  it('adversarial answer: an all-weak answer marks EVERY sentence — the bound is not a blanket cap', async () => {
    const sentences = [
      'The first claim rests on a passage that only partly supports it.',
      'The second claim is likewise thin against its cited passage.',
      'A third statement sits just above the matcher cutoff as well.',
      'The fourth is no better supported than the three before it.',
      'A fifth assertion also scores barely over the cutoff line.',
      'The sixth and last claim is equally weakly grounded.',
    ];
    const claims: Claim[] = sentences.map((sentenceText, i) => ({
      sentenceIndex: i,
      sentenceText,
      verifiedScore: [0.52, 0.54, 0.55, 0.56, 0.57, 0.58][i]!,
      lexicalScore: 0,
      verifiedRefs: [i % 3],
      lexicalRefs: [],
    }));
    const el = await renderAnswer(sentences, claims);
    const content = el.renderRoot.querySelector('.md-content')!;

    expect(content.querySelectorAll('.cite-sentence.grounding-weak').length).toBe(6);
    const { ratio } = underlineDensity(el);
    // A genuinely weak answer must LOOK weak: the honest render exceeds X, and no render-time rule
    // collapses it (822 §3d rejects B11 — a density cap would hide a miscalibrated scorer).
    expect(ratio).toBeGreaterThan(MAX_UNDERLINE_DENSITY);
    expect(ratio).toBeGreaterThan(0.9);
    el.remove();
  });

  it('a lexical-only answer renders as plain prose — no marks, no underlines, no counts', async () => {
    const sentences = REALISTIC_SENTENCES.slice(0, 3);
    const claims: Claim[] = sentences.map((sentenceText, i) => ({
      sentenceIndex: i,
      sentenceText,
      verifiedScore: null,
      // Word overlap can reach 1.0 on a short passage — under the pre-822 merge that read
      // `grounded`, the STRONGEST tier, from a coverage ratio.
      lexicalScore: [0.33, 0.75, 1][i]!,
      verifiedRefs: [0],
      lexicalRefs: [],
    }));
    const el = await renderAnswer(sentences, claims);
    const content = el.renderRoot.querySelector('.md-content')!;
    expect(content.querySelectorAll('.cite-ref').length).toBe(0);
    expect(content.querySelectorAll('.cite-sentence').length).toBe(0);
    expect(underlineDensity(el).ratio).toBe(0);
    el.remove();
  });
});

describe('MarkdownBlock 822 §3c — the ungrounded mark has its own color', () => {
  it('a claim below the cutoff renders a mark classed cite-ungrounded', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = 'The kernel is a shared substrate.';
    el.citations = [mark('The kernel is a shared substrate.', 0.2)];
    document.body.appendChild(el);
    await el.updateComplete;
    await new Promise((r) => setTimeout(r, 0));
    const ref = el.renderRoot.querySelector('.cite-ref');
    expect(ref?.className).toContain('cite-ungrounded');
    el.remove();
  });

  /**
   * The tier palette is MONOTONIC as a source invariant: grounded ≠ weak ≠ ungrounded. Asserted on
   * the stylesheet text rather than on computed values because happy-dom does not cascade shadow
   * styles — the computed-value check runs live in the browser (§5.4 S2). Before 822 §3c there was
   * no `cite-ungrounded` rule at all and the WEAKEST tier inherited the STRONGEST tier's blue.
   */
  it('declares a distinct color for each of the three mark tiers', () => {
    const cssText = (MarkdownBlockClass.styles as { cssText: string }).cssText;
    const colorOf = (selector: string): string => {
      const m = new RegExp(`${selector.replace(/[.]/g, '\\.')}\\s*\\{([^}]*)\\}`).exec(cssText);
      expect(m, `${selector} must declare a rule`).not.toBeNull();
      const c = /color:\s*([^;]+);/.exec(m![1]!);
      expect(c, `${selector} must declare a color`).not.toBeNull();
      return c![1]!.trim();
    };
    const grounded = colorOf('.cite-ref');
    const weak = colorOf('.cite-ref.cite-weak');
    const ungrounded = colorOf('.cite-ref.cite-ungrounded');
    expect(new Set([grounded, weak, ungrounded]).size).toBe(3);
    // The warning role's TEXT member — `check-accent-as-text` forbids an `--accent-*` fill token as a
    // text color, and sv3 bridges `--text-warning` and `--accent-warning` to one `--warning-foreground`.
    expect(ungrounded).toBe('var(--text-warning)');
  });

  it('keeps the mark palette and the sentence-body palette saying the same thing', () => {
    const cssText = (MarkdownBlockClass.styles as { cssText: string }).cssText;
    // The body channel's weakest tier is already the warning role; the mark now agrees with it.
    expect(cssText).toContain('1px dotted var(--accent-warning)');
    expect(cssText).toMatch(/\.cite-ref\.cite-ungrounded\s*\{\s*color:\s*var\(--text-warning\);/);
  });
});
