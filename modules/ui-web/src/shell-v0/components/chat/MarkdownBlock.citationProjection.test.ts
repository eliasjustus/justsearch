/**
 * Tempdoc 869 §2.5 — the CONFORMANCE guard for `evidence-fe-markdown-block`, the registered
 * projection of `ContextCitation` (→ `RetrievalCitation` → `Citation`, via `claimsToCitations`) into
 * the answer's inline marks.
 *
 * This is where 869's invariants live, rather than paperwork beside them. The renderer is the one
 * surface where three upstream authorities meet — the verifier (a `Citation`'s similarity and
 * sentence span), the model (the literal `[n]` it wrote), and the frame (577 Move 3) — and every
 * class it mints must be traceable to exactly one of them:
 *
 *  - I-2 REACHABILITY (C2): which citation-shaped text the mute may touch, in each frame. A ref that
 *    resolves to a source and was NOT verified is muted on a SOURCED answer too — that is the whole
 *    carve-out; an unresolvable `[7]`, a parenthesised `(2)`, and a label the weave rendered are
 *    prose or evidence and stay untouched.
 *  - I-2 ORDER: a literal the tier-2 upgrade claims is never also muted.
 *  - The muted span SAYS what it is — colour is not the carrier.
 *  - I-1 POSITION (C1): the model's literal is transparent to the weave and its whitespace is the
 *    model's, so a mark lands where the same text would put it with no literal written at all, and
 *    a strip leaves the prose the model's spacing rather than the renderer's.
 *  - I-1 COLOUR (C3): a mark's tier is a POSITIVE class. Tier 1 states its grounding; tier 2 states
 *    none, because none was measured — and "no class" therefore has to paint neutral, not strongest.
 *
 * @vitest-environment happy-dom
 */

import { describe, expect, it } from 'vitest';
import {
  MarkdownBlock as MarkdownBlockClass,
  type MarkdownBlock,
  type MarkdownCitation,
} from './MarkdownBlock.js';
import './MarkdownBlock.js';

/** The flattened `styles` array (846 §2.3 — the shared sheets plus this component's own rules). */
function markdownBlockCssText(): string {
  const styles = MarkdownBlockClass.styles as unknown;
  const sheets = Array.isArray(styles) ? styles : [styles];
  return sheets.map((s) => (s as { cssText: string }).cssText).join('\n');
}

/**
 * A resolved citation, shaped as the producers build it (`MarkdownBlock.test.ts`'s `mark`). The
 * `sentenceText` is what decides the tier: text the answer contains anchors a tier-1 mark, text it
 * does not contain anchors nothing and leaves the label to the tier-2 literal upgrade.
 */
function mark(sentenceText: string, label: number, sentenceIndex: number): MarkdownCitation {
  return {
    sentenceText,
    similarity: 0.8,
    sentenceIndex,
    label,
    detail: {
      parentDocId: `f:/docs/${label}.md`,
      startLine: 1,
      endLine: 5,
      startChar: 0,
      endChar: 0,
      excerpt: 'x',
    },
    hover: { excerpt: 'an excerpt', title: 'X', headingText: '' },
  };
}

interface Mount {
  text: string;
  citations?: MarkdownCitation[];
  sourceCount?: number;
  frame?: MarkdownBlock['frame'];
  streaming?: boolean;
}

async function mounted(opts: Mount): Promise<MarkdownBlock> {
  const el = document.createElement('jf-markdown-block') as MarkdownBlock;
  el.text = opts.text;
  el.citations = opts.citations ?? [];
  el.sourceCount = opts.sourceCount ?? 0;
  if (opts.frame !== undefined) el.frame = opts.frame;
  if (opts.streaming === true) el.isStreaming = true;
  document.body.appendChild(el);
  await el.updateComplete;
  await new Promise((r) => setTimeout(r, 0));
  return el;
}

const content = (el: MarkdownBlock): Element => el.renderRoot.querySelector('.md-content')!;
const muted = (el: MarkdownBlock): HTMLElement[] =>
  [...content(el).querySelectorAll<HTMLElement>('.pseudo-cite')];

/** The answer sentence the verifier grounded in every fixture below — it anchors a tier-1 mark. */
const ANCHORED = 'The kernel is a shared substrate.';
/** A sentence the answer does not contain: its label reaches the reader only through tier 2. */
const UNANCHORED = 'A sentence this answer never wrote.';

describe('I-2 reachability — the mute reaches a SOURCED answer, and only what resolves', () => {
  it('mutes an unverified resolvable ref and leaves prose, out-of-range refs and marks alone', async () => {
    // Five sources; the verifier stood behind two labels (5 anchored to a sentence, 4 reaching the
    // reader through the tier-2 upgrade of the model's own literal). The answer also carries `[2]`
    // (resolves to source 2 — nothing verified it), `[7]` (resolves to nothing: there is no seventh
    // source) and `(2)` (a number, not a ref notation this product ever mints).
    const el = await mounted({
      text: `${ANCHORED} The model wrote [2], then [7], then (2), then [4].`,
      citations: [mark(ANCHORED, 5, 0), mark(UNANCHORED, 4, 1)],
      sourceCount: 5,
    });

    const spans = muted(el);
    expect(spans.map((s) => s.textContent)).toEqual(['[2]']);
    expect(spans[0]!.dataset.claimedLabel).toBe('2');
    // The span SAYS what it is, in both channels — the muted colour is never the only carrier.
    expect(spans[0]!.title).toBe('The model cited source 2; the verifier did not confirm it');
    expect(spans[0]!.getAttribute('aria-label')).toBe(spans[0]!.title);
    // …and it stays inert: a mute is text with a name, not a control.
    expect(spans[0]!.getAttribute('role')).toBeNull();
    expect(spans[0]!.getAttribute('tabindex')).toBeNull();

    // Untouched, both of them — an unresolvable label and a parenthesised number are prose here.
    expect(content(el).textContent).toContain('[7]');
    expect(content(el).textContent).toContain('(2)');

    // The verified labels are MARKS: 5 woven at its sentence, 4 upgraded from the literal.
    const refs = [...content(el).querySelectorAll('.cite-ref')].map((r) => r.textContent);
    expect(refs.sort()).toEqual(['4', '5']);
    expect(spans.some((s) => s.textContent?.includes('4'))).toBe(false);
    el.remove();
  });

  it('mutes nothing when the block was given no source list (the default, and every citation-less site)', async () => {
    // `sourceCount = 0` makes the sourced arm vacuous BY CONSTRUCTION: nothing resolves, so nothing
    // is claimed. This is what keeps the five citation-less consumers rendering exactly as they did.
    const el = await mounted({ text: 'A grounded answer [2].' });
    expect(muted(el)).toHaveLength(0);
    expect(content(el).textContent).toContain('[2]');
    el.remove();
  });

  it('mutes nothing while the answer is still streaming — neither pass runs before it settles', async () => {
    const el = await mounted({
      text: `${ANCHORED} The model wrote [2].`,
      citations: [mark(ANCHORED, 5, 0)],
      sourceCount: 5,
      streaming: true,
    });
    expect(muted(el)).toHaveLength(0);
    expect(content(el).querySelectorAll('.cite-ref')).toHaveLength(0);
    expect(content(el).textContent).toContain('[2]');
    el.remove();
  });
});

describe('I-2 order — a literal the tier-2 upgrade claims is never also muted', () => {
  it('leaves zero muted spans when the only literal is one tier 2 upgrades', async () => {
    // The outcome is secured twice over, which is the point: the predicate excludes a label the
    // block rendered, AND the pass runs after `decorateCitations`. Reversing the order would hand
    // the upgrade a literal already wrapped in a span it does not skip.
    const el = await mounted({
      text: 'The model wrote [4] and nothing else worth citing.',
      citations: [mark(UNANCHORED, 4, 0)],
      sourceCount: 5,
    });
    expect(muted(el)).toHaveLength(0);
    expect([...content(el).querySelectorAll('.cite-ref')].map((r) => r.textContent)).toEqual(['4']);
    el.remove();
  });
});

describe('the ungrounded frame keeps its broad rule (577 Move 3, unchanged)', () => {
  it('mutes every citation-shaped token and names each one', async () => {
    const el = await mounted({
      text: 'JustSearch uses a 4-tier strategy [1] and chaos testing (2).',
      frame: 'ungrounded',
    });
    const spans = muted(el);
    expect(spans.map((s) => s.textContent)).toEqual(['[1]', '(2)']);
    for (const span of spans) {
      expect(span.title).toBe('Citation-shaped text in an answer that is not grounded in your files');
      expect(span.getAttribute('aria-label')).toBe(span.title);
    }
    // Only a BRACKETED token claims a label; `(2)` is a number the model happened to bracket.
    expect(spans[0]!.dataset.claimedLabel).toBe('1');
    expect(spans[1]!.dataset.claimedLabel).toBeUndefined();
    expect(content(el).querySelectorAll('.cite-ref')).toHaveLength(0);
    el.remove();
  });
});

/* ── I-1 POSITION (C1) — the literal is transparent, and its whitespace is the model's ────────── */

/** A `.md-content` text dump, marker glyphs included — what the reader actually sees. */
const text = (el: MarkdownBlock): string => content(el).textContent ?? '';

/** Every text node under `.md-content`, so a remnant can be found wherever a split left it. */
const textNodes = (el: MarkdownBlock): string[] => {
  const walker = document.createTreeWalker(content(el), NodeFilter.SHOW_TEXT);
  const out: string[] = [];
  let n: Node | null;
  while ((n = walker.nextNode())) out.push((n as Text).data);
  return out;
};

describe('I-1 position — the STRIP arm: the mark lands where no literal was written', () => {
  /**
   * Each row is a shape the derisk pass (§3.1 R1) rendered on the real renderer. The KEY is what the
   * verifier scored, so it is the sentence WITHOUT the model's literal — except the last row, the
   * shape the backend actually emits (867 §1.3), where the sentence iterator swept the literal into
   * the key. `expected` is the whole `.md-content` text: the mark's glyph is the label, and the
   * trailing newline is markdown-it's block separator.
   *
   * Two of these were broken before 869 C1: `word[1].` put the marker INSIDE the brackets, which
   * split the text node and left the tier-2 regex nothing to match (`word[11].` — the digit twice,
   * the brackets orphaned); `[1] The kernel…` left the block starting with a space.
   *
   * `word [1], and more.` moves: the mark now lands after the comma, exactly where the same prose
   * without the literal has always put it. That is what "transparent" means — the literal is not a
   * boundary the walk respects, so `word [1], x` and `word, x` place the mark identically.
   */
  const SHAPES: ReadonlyArray<{ id: string; input: string; key: string; expected: string }> = [
    { id: 'space before, period after', input: 'word [1].', key: 'word', expected: 'word.1\n' },
    { id: 'space on both sides', input: 'word [1] .', key: 'word', expected: 'word1 .\n' },
    { id: 'glued to the word (the corruption)', input: 'word[1].', key: 'word', expected: 'word.1\n' },
    {
      id: 'comma after',
      input: 'word [1], and more.',
      key: 'word',
      expected: 'word,1 and more.\n',
    },
    {
      id: 'block-leading (the orphaned space)',
      input: '[1] The kernel is a shared substrate.',
      key: 'The kernel is a shared substrate.',
      expected: 'The kernel is a shared substrate.1\n',
    },
    {
      id: 'the backend shape — the literal is IN the key',
      input: 'Trade-offs matter here [1]. Next sentence.',
      key: 'Trade-offs matter here [1].',
      expected: 'Trade-offs matter here.1 Next sentence.\n',
    },
  ];

  for (const shape of SHAPES) {
    it(`${shape.id}: "${shape.input}" renders "${shape.expected.replace(/\n/g, '\\n')}"`, async () => {
      const el = await mounted({ text: shape.input, citations: [mark(shape.key, 1, 0)] });
      // The citation anchored, so this is the STRIP arm: one woven mark, the literal removed.
      expect([...content(el).querySelectorAll('.cite-ref')].map((r) => r.textContent)).toEqual(['1']);
      expect(text(el)).toBe(shape.expected);
      el.remove();
    });
  }

  it('leaves no bracket remnant in any text node — the split-inside-the-literal defect', async () => {
    for (const shape of SHAPES) {
      const el = await mounted({ text: shape.input, citations: [mark(shape.key, 1, 0)] });
      for (const data of textNodes(el)) {
        expect(data, `${shape.id} — a text node kept a bracket`).not.toMatch(/[[\]]/);
      }
      el.remove();
    }
  });

  it('never creates a space before punctuation that the model did not write', async () => {
    // `word [1] .` is excluded on purpose: its space before the period is the MODEL's, and rule 3's
    // whole point is that the renderer does not edit the model's typography — only the one space the
    // literal it removed was holding.
    for (const shape of SHAPES.filter((s) => !/\s[.,;:!?]/.test(s.input.replace(/\s?\[\d+\]/g, '')))) {
      const el = await mounted({ text: shape.input, citations: [mark(shape.key, 1, 0)] });
      expect(text(el), `${shape.id}`).not.toMatch(/\s[.,;:!?]/);
      el.remove();
    }
  });
});

describe('I-1 position — the UPGRADE arm: the marker inherits the literal\'s exact place', () => {
  // The old `/\s?\[(\d+)\]/` consumed the space BEFORE the literal too, so a marker replacing a
  // mid-sentence `[4]` glued itself to the previous word (`trade-offs⁴` — 869 C1's headline). The
  // model's spacing is the model's: it survives on both sides of the swap.
  it('keeps the space the model wrote: "word [4] more" → "word 4 more"', async () => {
    const el = await mounted({ text: 'word [4] more', citations: [mark(UNANCHORED, 4, 0)] });
    expect([...content(el).querySelectorAll('.cite-ref')].map((r) => r.textContent)).toEqual(['4']);
    expect(text(el)).toBe('word 4 more\n');
    el.remove();
  });

  it('and invents none where the model wrote none: "word[4] more" → "word4 more"', async () => {
    const el = await mounted({ text: 'word[4] more', citations: [mark(UNANCHORED, 4, 0)] });
    expect(text(el)).toBe('word4 more\n');
    el.remove();
  });
});

/* ── I-1 COLOUR (C3) — the tier is a positive class, and tier 2 makes no claim ─────────────────── */

const GROUNDING_CLASSES = ['cite-grounded', 'cite-weak', 'cite-ungrounded'] as const;

describe('I-1 colour — tier 1 states its grounding, tier 2 states none', () => {
  /** One verified sentence (label 1) and one label reaching the reader only through its literal. */
  const both = async (): Promise<MarkdownBlock> =>
    mounted({
      text: `${ANCHORED} And a bare token [2] inline.`,
      citations: [mark(ANCHORED, 1, 0), mark(UNANCHORED, 2, 1)],
    });

  it('mints one mark of each tier, distinguishable without reading a colour', async () => {
    const el = await both();
    const refs = [...content(el).querySelectorAll<HTMLElement>('.cite-ref')];
    expect(refs.map((r) => r.textContent)).toEqual(['1', '2']);
    expect(refs.map((r) => r.dataset.citeTier)).toEqual(['sentence', 'source']);
    el.remove();
  });

  it('tier 1: exactly one grounding class, and the sentence it grounds is underlined', async () => {
    const el = await both();
    const tier1 = content(el).querySelector<HTMLElement>('[data-cite-tier="sentence"]')!;
    expect(GROUNDING_CLASSES.filter((c) => tier1.classList.contains(c))).toHaveLength(1);
    const spans = [...content(el).querySelectorAll('.cite-sentence')];
    expect(spans.map((s) => s.textContent)).toEqual([ANCHORED]);
    el.remove();
  });

  it('tier 2: NO grounding class, no underline, and a title that says what it asserts', async () => {
    const el = await both();
    const tier2 = content(el).querySelector<HTMLElement>('[data-cite-tier="source"]')!;
    // THE INVARIANT. A grounding class here would be a claim about a sentence nothing located; the
    // base rule's ink is what a mark that makes no such claim wears (the CSS half is asserted below).
    expect(GROUNDING_CLASSES.filter((c) => tier2.classList.contains(c))).toEqual([]);
    expect(tier2.className).toContain('cite-ref');
    expect(tier2.closest('.cite-sentence')).toBeNull();
    expect(tier2.title).toContain('the model cited this source');
    // …and the accessible NAME is untouched by the tier: it is a stable description of the control
    // (822 F6), so the two tiers announce identically and only the tooltip differs.
    expect(tier2.getAttribute('aria-label')).toBe('Citation 2 — open the cited passage');
    el.remove();
  });
});

describe('the tier ink is a POSITIVE class — absence is neutral, not strongest (869 §2.3)', () => {
  /** A rule's DECLARATIONS — comments stripped, because a comment naming the token it replaced is
   *  not a declaration and must not satisfy (or fail) an assertion about what the rule paints. */
  const ruleBody = (selector: string): string => {
    const m = new RegExp(`${selector.replace(/\./g, '\\.')}\\s*\\{([^}]*)\\}`).exec(
      markdownBlockCssText(),
    );
    expect(m, `${selector} must declare a rule`).toBeTruthy();
    return (m![1] as string).replace(/\/\*[\s\S]*?\*\//g, '');
  };

  it('the base rule paints the SOURCE tier, not the tint a verified mark earns', () => {
    const base = ruleBody('.cite-ref');
    expect(base).toContain('color: var(--md-cite-source-color');
    // The regression this exists to catch: putting the tint back on the base rule silently repaints
    // every tier-2 mark as the strongest tier, with no class and no test having changed.
    expect(base).not.toContain('--text-tint');
  });

  it('the grounded tier has a rule of its own, at the ink it always shipped', () => {
    expect(ruleBody('.cite-ref.cite-grounded')).toContain('--text-tint');
  });
});

describe('the muted idiom is READ, not dimmed (869 §3.6)', () => {
  it('takes its ink from a bridgeable hook and declares no opacity', () => {
    const rule = /\.pseudo-cite\s*\{([^}]*)\}/.exec(markdownBlockCssText());
    expect(rule, 'the .pseudo-cite rule').toBeTruthy();
    const body = rule![1] as string;
    // `opacity: .7` was a second, invisible authority over the composite contrast: on the v3 window
    // it put the muted token at ~3.0:1 dark / ~2.7:1 light. A window cannot lift what a fixed
    // opacity multiplies, so the value moves to a hook the window can re-point.
    expect(body).not.toContain('opacity');
    expect(body).toContain('color: var(--md-pseudo-cite-color');
  });
});
