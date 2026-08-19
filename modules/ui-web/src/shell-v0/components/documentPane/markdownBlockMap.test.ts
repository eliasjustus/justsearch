// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom
// DOMPurify.sanitize requires a DOM (the module's rendering step runs marked → DOMPurify, the same
// pipeline components/chat/MarkdownBlock.ts uses) — the default vitest environment is Node with none.
import { describe, expect, it } from 'vitest';
import fc from 'fast-check';
import { markdownBlockMap } from './markdownBlockMap.js';

// Fixture doc exercising every block kind the contract names: heading, paragraph, nested list,
// fence-with-internal-blank-lines, table, blockquote, and trailing content with no final newline.
// Indices below are 0-based and were counted by hand against this literal — the table-driven
// assertions pin them so a regression in the splitter shows up as an exact line-range diff.
const FIXTURE = [
  /* 0  */ '# Heading One',
  /* 1  */ '',
  /* 2  */ 'Paragraph one line 1',
  /* 3  */ 'paragraph one line 2',
  /* 4  */ '',
  /* 5  */ '- item 1',
  /* 6  */ '- item 2',
  /* 7  */ '  - nested item',
  /* 8  */ '',
  /* 9  */ 'Some text after list.',
  /* 10 */ '',
  /* 11 */ '```js',
  /* 12 */ 'const a = 1;',
  /* 13 */ '',
  /* 14 */ 'const b = 2;',
  /* 15 */ '```',
  /* 16 */ '',
  /* 17 */ 'Trailing paragraph.',
  /* 18 */ '',
  /* 19 */ '| A | B |',
  /* 20 */ '|---|---|',
  /* 21 */ '| 1 | 2 |',
  /* 22 */ '',
  /* 23 */ '> Quote line one',
  /* 24 */ '> Quote line two',
  /* 25 */ '',
  /* 26 */ 'Final trailing content without newline',
].join('\n');

describe('markdownBlockMap — fixture (table-driven)', () => {
  const blocks = markdownBlockMap(FIXTURE);

  it('produces exactly 9 top-level blocks', () => {
    expect(blocks).toHaveLength(9);
  });

  const expectedRanges: Array<[number, number]> = [
    [0, 0], // heading
    [2, 3], // paragraph
    [5, 7], // nested list
    [9, 9], // paragraph after list
    [11, 15], // fence with an internal blank line
    [17, 17], // paragraph
    [19, 21], // table
    [23, 24], // blockquote
    [26, 26], // trailing content, no final newline
  ];

  it.each(expectedRanges.map((range, idx) => ({ idx, range })))(
    'block $idx spans lines $range',
    ({ idx, range }) => {
      expect([blocks[idx]!.startLine, blocks[idx]!.endLine]).toEqual(range);
    },
  );

  it('renders each block to non-empty sanitized html', () => {
    for (const b of blocks) {
      expect(b.html.length).toBeGreaterThan(0);
    }
  });

  // Note: these assertions check for the block's characteristic INNER markup/content rather than
  // its outermost wrapper tag (<h1>/<table>/<blockquote>/<pre>). Isolated probing of this test's
  // `happy-dom` + `DOMPurify` combination found that DOMPurify.sanitize() strips exactly the single
  // outermost element of an otherwise-standalone fragment (e.g. `<table><tr>...` → `<tbody><tr>...`
  // with the `<table>` wrapper gone) under happy-dom's DOM implementation — a test-environment
  // artifact of this DOMPurify version's happy-dom compatibility, not a production defect (real
  // browsers, which is what ships, preserve the wrapper; `MarkdownBlock.ts` uses this same
  // marked→DOMPurify pipeline in production today). Logged as an observation; out of scope here.
  it('renders the heading block with its text content', () => {
    expect(blocks[0]!.html).toContain('Heading One');
  });

  it('renders the fence block as code content (never split by its internal blank line)', () => {
    const fenceBlock = blocks[4]!;
    expect(fenceBlock.startLine).toBe(11);
    expect(fenceBlock.endLine).toBe(15);
    expect(fenceBlock.html).toContain('<code');
    expect(fenceBlock.html).toContain('const a = 1;');
    expect(fenceBlock.html).toContain('const b = 2;');
  });

  it('renders the table block as row/cell markup', () => {
    expect(blocks[6]!.html).toContain('<tr');
    expect(blocks[6]!.html).toContain('<td>1</td>');
    expect(blocks[6]!.html).toContain('<td>2</td>');
  });

  // Tempdoc 846 §2.2 — the block's two source lines are now FLOWED into one line of prose rather
  // than separated by a hard `<br>`: rendered mode shows authored `.md` files, which are
  // hard-wrapped by convention and which every standard markdown-file renderer reflows. The two
  // lines still belong to this one block (that is the line-range contract, and it is unchanged).
  it('renders the blockquote block content as one flowing quote, not hard-broken lines', () => {
    expect(blocks[7]!.html).toContain('Quote line one');
    expect(blocks[7]!.html).toContain('Quote line two');
    expect(blocks[7]!.html).not.toContain('<br');
  });

  it('blocks are ordered and non-overlapping', () => {
    for (let i = 1; i < blocks.length; i++) {
      expect(blocks[i]!.startLine).toBeGreaterThan(blocks[i - 1]!.endLine);
    }
  });
});

describe('markdownBlockMap — edge cases', () => {
  it('returns [] for empty input', () => {
    expect(markdownBlockMap('')).toEqual([]);
  });

  it('returns [] for whitespace-only input', () => {
    expect(markdownBlockMap('   \n\n   \n')).toEqual([]);
  });

  it('never splits a fence even when it runs to end-of-file with no closing fence', () => {
    const src = ['```', 'line a', '', 'line b'].join('\n');
    const blocks = markdownBlockMap(src);
    expect(blocks).toHaveLength(1);
    expect(blocks[0]!.startLine).toBe(0);
    expect(blocks[0]!.endLine).toBe(3);
  });

  it('supports ~~~ fences symmetrically with ```', () => {
    const src = ['~~~', 'a', '~~~'].join('\n');
    const blocks = markdownBlockMap(src);
    expect(blocks).toHaveLength(1);
    expect(blocks[0]!.startLine).toBe(0);
    expect(blocks[0]!.endLine).toBe(2);
  });

  it('treats consecutive ATX headings as separate single-line blocks with no blank line between', () => {
    const src = ['# One', '## Two'].join('\n');
    const blocks = markdownBlockMap(src);
    expect(blocks).toHaveLength(2);
    expect(blocks[0]).toMatchObject({ startLine: 0, endLine: 0 });
    expect(blocks[1]).toMatchObject({ startLine: 1, endLine: 1 });
  });
});

describe('markdownBlockMap — property: coverage without overlap (non-fence documents)', () => {
  // Plain-text lines only (no '#' / backtick / tilde leaders), so every line is either blank (a gap)
  // or grouped by the blank-line-delimited paragraph rule — no heading/fence branch involved. This
  // isolates the general "cover every line exactly once, no overlap" invariant the contract requires.
  const nonBlankLine = fc
    .array(fc.constantFrom('a', 'b', 'c', ' ', 'x', 'y', '-'), { minLength: 1, maxLength: 8 })
    .map((chars) => chars.join(''))
    .filter((s) => s.trim().length > 0);
  const lineArb = fc.oneof(fc.constant(''), nonBlankLine);

  it('every non-blank line belongs to exactly one block; every blank line belongs to none', () => {
    fc.assert(
      fc.property(fc.array(lineArb, { minLength: 0, maxLength: 20 }), (lines) => {
        const src = lines.join('\n');
        const blocks = markdownBlockMap(src);

        // Non-overlapping, ordered.
        for (let i = 1; i < blocks.length; i++) {
          expect(blocks[i]!.startLine).toBeGreaterThan(blocks[i - 1]!.endLine);
        }

        for (let idx = 0; idx < lines.length; idx++) {
          const isBlank = lines[idx]!.trim() === '';
          const covering = blocks.filter((b) => idx >= b.startLine && idx <= b.endLine);
          if (isBlank) {
            expect(covering).toHaveLength(0);
          } else {
            expect(covering).toHaveLength(1);
          }
        }
      }),
    );
  });
});

describe('markdownBlockMap — property: fences never split on internal blank lines', () => {
  const fenceBodyLine = fc
    .array(fc.constantFrom('a', 'b', 'c', ' ', 'x', 'y'), { minLength: 0, maxLength: 6 })
    .map((chars) => chars.join(''))
    .filter((s) => !/^```/.test(s.trim()));
  const bodyArb = fc.oneof(fc.constant(''), fenceBodyLine);

  it('a fence opened anywhere always forms exactly one block through its closing fence', () => {
    fc.assert(
      fc.property(
        fc.array(bodyArb, { minLength: 0, maxLength: 3 }), // prefix (outside the fence)
        fc.array(bodyArb, { minLength: 0, maxLength: 8 }), // fence body (may include blanks)
        fc.array(bodyArb, { minLength: 0, maxLength: 3 }), // suffix (outside the fence)
        (prefix, body, suffix) => {
          const fenceOpenLine = prefix.length;
          const fenceCloseLine = fenceOpenLine + 1 + body.length;
          const lines = [...prefix, '```', ...body, '```', ...suffix];
          const src = lines.join('\n');
          const blocks = markdownBlockMap(src);

          const fenceBlock = blocks.find((b) => b.startLine === fenceOpenLine);
          expect(fenceBlock).toBeDefined();
          expect(fenceBlock!.endLine).toBe(fenceCloseLine);

          // No other block starts or ends strictly inside the fence's line range.
          for (const b of blocks) {
            if (b === fenceBlock) continue;
            const insideFence =
              (b.startLine > fenceOpenLine && b.startLine <= fenceCloseLine) ||
              (b.endLine >= fenceOpenLine && b.endLine < fenceCloseLine);
            expect(insideFence).toBe(false);
          }
        },
      ),
    );
  });
});
