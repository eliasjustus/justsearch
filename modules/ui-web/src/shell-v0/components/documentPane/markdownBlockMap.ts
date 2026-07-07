// SPDX-License-Identifier: Apache-2.0
/**
 * markdownBlockMap — pure module: source markdown → an ordered list of top-level block
 * descriptors, each carrying the exact source line range it renders from.
 *
 * Tempdoc "Search Thread" stage S6 prep (Reading Stage). `DocumentPane`'s Rendered mode needs to
 * map a `highlightRange` (a passage's source line span) onto the rendered DOM so the right block(s)
 * can be tinted + scrolled to — which means every rendered block must carry its origin line range.
 *
 * Parser choice: the app's markdown pipeline is `marked` + `DOMPurify` (see
 * `components/chat/MarkdownBlock.ts`) — `markdown-it` is NOT a dependency of this package
 * (checked `modules/ui-web/package.json`), so its `token.map` line-tracking isn't available. Rather
 * than add a new dependency, this module implements a CONSERVATIVE, fence-aware block splitter and
 * renders each split through the same `marked`+`DOMPurify` pipeline the rest of the app uses — one
 * markdown authority, just fed one block at a time instead of the whole document.
 *
 * Splitting rule (deliberately conservative — see the honest limits below):
 *   - A fenced code block (``` or ~~~) is ALWAYS one block, however many blank lines it contains
 *     internally — fences never split (the one hard invariant this module must uphold).
 *   - An ATX heading (`#` .. `######`) is ALWAYS its own single-line block, even with no blank line
 *     separating it from adjacent text (CommonMark: a heading always terminates a paragraph).
 *   - Everything else is grouped by BLANK-LINE delimiting: a run of consecutive non-blank,
 *     non-fence-opening, non-heading lines is one block (a paragraph, a list — nested or not, a
 *     table, or a blockquote all fall out of this by construction, since none of those constructs
 *     contain a blank line in the common case).
 *   - Blank lines between blocks are gaps: no descriptor covers them, satisfying the "every source
 *     line maps into exactly one block, OR a gap between blocks" contract.
 *
 * Honest limits (this is NOT a CommonMark block parser): a table or list that starts immediately
 * after a paragraph with NO intervening blank line (a GFM/lazy-continuation edge case) is grouped
 * into the same block as that paragraph rather than split — `marked` still renders the combined
 * chunk correctly as a document fragment, but the line range covers the whole run. Setext headings
 * (`Title\n=====`) are not specially recognized; they render as part of their paragraph block, not a
 * heading block.
 */
import { Marked } from 'marked';
import DOMPurify from 'dompurify';

const md = new Marked({ breaks: true, gfm: true });

/** One top-level rendered block and the exact 0-based, inclusive source line range it came from. */
export interface MarkdownBlockDescriptor {
  /** 0-based, inclusive first source line of this block. */
  readonly startLine: number;
  /** 0-based, inclusive last source line of this block. */
  readonly endLine: number;
  /** Sanitized HTML for this block alone (safe to insert via `unsafeHTML`). */
  readonly html: string;
}

const FENCE_OPEN_RE = /^\s{0,3}(`{3,}|~{3,})/;
const ATX_HEADING_RE = /^\s{0,3}#{1,6}(\s|$)/;

function isClosingFence(line: string, fenceChar: string, fenceLen: number): boolean {
  const re = new RegExp(`^\\s{0,3}${fenceChar}{${fenceLen},}\\s*$`);
  return re.test(line);
}

/** Render one block's source text through the app's markdown pipeline (marked → DOMPurify). */
function renderBlockHtml(text: string): string {
  const raw = text.trim() ? (md.parse(text, { async: false }) as string) : '';
  return DOMPurify.sanitize(raw);
}

/**
 * Produce the ordered block map for `source`. Empty/whitespace-only input yields `[]`.
 *
 * Contract (verified in `markdownBlockMap.test.ts`):
 *   - Descriptors are ordered by `startLine`, strictly increasing, non-overlapping.
 *   - A fence's internal blank lines never cause a split.
 *   - Concatenating `[startLine, endLine]` ranges (plus the blank-line gaps between them) covers
 *     every line index `0..source.split('\n').length - 1` exactly once.
 */
export function markdownBlockMap(source: string): MarkdownBlockDescriptor[] {
  const lines = source.split('\n');
  const n = lines.length;
  const blocks: MarkdownBlockDescriptor[] = [];

  const flush = (start: number, endInclusive: number): void => {
    if (start > endInclusive) return;
    const text = lines.slice(start, endInclusive + 1).join('\n');
    if (text.trim() === '') return;
    blocks.push({ startLine: start, endLine: endInclusive, html: renderBlockHtml(text) });
  };

  let i = 0;
  while (i < n) {
    const line = lines[i] ?? '';

    if (line.trim() === '') {
      i++;
      continue;
    }

    const fenceMatch = line.match(FENCE_OPEN_RE);
    if (fenceMatch) {
      const fenceChar = fenceMatch[1]!.charAt(0);
      const fenceLen = fenceMatch[1]!.length;
      const start = i;
      i++;
      while (i < n && !isClosingFence(lines[i] ?? '', fenceChar, fenceLen)) {
        i++;
      }
      if (i < n) i++; // consume the closing fence line itself; EOF-without-close stops naturally
      flush(start, i - 1);
      continue;
    }

    if (ATX_HEADING_RE.test(line)) {
      flush(i, i);
      i++;
      continue;
    }

    const start = i;
    i++;
    while (i < n) {
      const next = lines[i] ?? '';
      if (next.trim() === '') break;
      if (FENCE_OPEN_RE.test(next)) break;
      if (ATX_HEADING_RE.test(next)) break;
      i++;
    }
    flush(start, i - 1);
  }

  return blocks;
}
