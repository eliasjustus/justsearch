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
 *
 * I-1 (mark position, tier colour) is 869 PR-B's half and joins this file with it.
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
