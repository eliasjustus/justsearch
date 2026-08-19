// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 849 §3 (R1) — the evidence reader's character arithmetic.
 *
 * A citation's PRIMARY coordinate is a document-relative character span (`startChar` inclusive,
 * `endChar` exclusive); the line numbers that used to travel with it were derived from that span by
 * the producer, in a 1-based system, and consumed by a 0-based one. This module is the conversion
 * that removes the hop: the reader maps characters to ITS OWN 0-based line index over the text it
 * actually fetched, so no line number has to survive a process boundary.
 *
 * It also owns the two searches the reader needs, both whitespace- and case-insensitive, because the
 * text an extractor produced and the text a chunker quoted differ in whitespace far more often than
 * in words:
 *
 *  - {@link locateWitness} — does the citation's excerpt actually appear at the anchored offsets?
 *    A failure means the offsets are stale, and the reader suppresses the highlight rather than
 *    tinting text it cannot confirm (§3 R1.4, review D-11).
 *  - {@link locateText} — where inside the cited chunk is the claim-matched sentence? This is a
 *    search WITHIN an already-anchored span, not an anchor of its own: the design rejects
 *    excerpt-text search as a primary anchor precisely because repeated text would pick the wrong
 *    occurrence, and bounding it to the chunk is what keeps that from applying here.
 */

/** A half-open character span: `start` inclusive, `end` exclusive. */
export interface CharSpan {
  readonly start: number;
  readonly end: number;
}

/**
 * The MAXIMUM leading run of the excerpt that has to reappear at the anchored offsets — a cap, not
 * a guarantee: `slice(0, 48)` of a 12-character excerpt is 12 characters. Capping it keeps a
 * re-extraction that re-wrapped or re-punctuated the tail of a passage from reading as a move.
 */
const WITNESS_CHARS = 48;

/**
 * …and the MINIMUM that makes the run evidence at all. Below this a match distinguishes nothing:
 * "Introduction" or a repeated table header reappears in a wholly rewritten document, so confirming
 * on it would be the pane certifying a location it did not actually check. Such an excerpt is
 * treated as NO USABLE WITNESS — the same confirm-by-absence an empty excerpt gets (see
 * {@link locateWitness}) — because the honest statement is "this citation carries nothing that can
 * testify", not "this citation was verified".
 */
const WITNESS_MIN_CHARS = 24;

/**
 * How far into the cited span the witness may sit. The excerpt is a WORD-CLAMPED PREFIX of the
 * chunk's own content (`RagContextOps.clampExcerptToWordBoundary(content, 240)`), so in an unchanged
 * document it starts at the span's first non-whitespace character. Allowing it anywhere in the span
 * would confirm a document where text was INSERTED before the passage — the excerpt is still in
 * range, and the tint has silently shifted by the length of the insertion. The slack covers leading
 * whitespace and punctuation drift from re-extraction, nothing structural.
 */
const WITNESS_DRIFT_CHARS = 64;

interface NormalizedText {
  /** Lowercased, whitespace-collapsed. */
  readonly text: string;
  /** `origin[i]` is the index in the ORIGINAL string that normalized character `i` came from. */
  readonly origin: readonly number[];
}

const WHITESPACE = /\s/;

function normalize(source: string, from: number, to: number): NormalizedText {
  const chars: string[] = [];
  const origin: number[] = [];
  let pendingSpace = false;
  for (let i = from; i < to; i += 1) {
    const ch = source[i] as string;
    if (WHITESPACE.test(ch)) {
      pendingSpace = chars.length > 0;
      continue;
    }
    if (pendingSpace) {
      chars.push(' ');
      origin.push(i);
      pendingSpace = false;
    }
    // ONE code unit in can be TWO out: `'İ'.toLowerCase()` (Turkish dotted capital İ) is
    // `'i' + U+0307`, and it is the only length-changing lowercase in U+0000-U+2FFFF. Pushing the
    // result as a single entry desynced `origin` from `text` from that point on, so `locateText`
    // read past the end of `origin` and returned `end: NaN` — a highlight that silently vanished or
    // landed on the wrong line. Every produced unit maps back to the ONE original index it came from.
    const lowered = ch.toLowerCase();
    for (let unit = 0; unit < lowered.length; unit += 1) {
      chars.push(lowered[unit] as string);
      origin.push(i);
    }
  }
  return { text: chars.join(''), origin };
}

/**
 * The 0-based index of the line containing `index` — the reader's own coordinate, counted over the
 * text the reader actually holds. An index at or past the end counts the whole text.
 */
export function lineIndexOfChar(text: string, index: number): number {
  const stop = Math.max(0, Math.min(index, text.length));
  let line = 0;
  for (let i = 0; i < stop; i += 1) {
    if (text[i] === '\n') line += 1;
  }
  return line;
}

/** The 0-based inclusive line span covering a half-open character span. */
export function lineSpanOfChars(text: string, span: CharSpan): { startLine: number; endLine: number } {
  const start = Math.max(0, Math.min(span.start, text.length));
  const end = Math.max(start, Math.min(span.end, text.length));
  return {
    startLine: lineIndexOfChar(text, start),
    endLine: lineIndexOfChar(text, Math.max(start, end - 1)),
  };
}

/**
 * Find `needle` inside `text[from, to)`, ignoring case and whitespace shape, and report the span in
 * the ORIGINAL text's coordinates. `null` when it is not there.
 */
export function locateText(text: string, needle: string, from: number, to: number): CharSpan | null {
  const start = Math.max(0, Math.min(from, text.length));
  const stop = Math.max(start, Math.min(to, text.length));
  const target = normalize(needle, 0, needle.length).text;
  if (target.length === 0) return null;
  const haystack = normalize(text, start, stop);
  const at = haystack.text.indexOf(target);
  if (at < 0) return null;
  return {
    start: haystack.origin[at] as number,
    end: (haystack.origin[at + target.length - 1] as number) + 1,
  };
}

/**
 * The staleness check (§3 R1.4). `true` means the citation's excerpt still begins AT THE START of
 * the span its offsets name, so the offsets can be trusted to address the passage they were
 * computed for.
 *
 * Two ways to have no verdict, both returning `true`, and both stated rather than implied: an EMPTY
 * excerpt, and one too SHORT to be evidence ({@link WITNESS_MIN_CHARS}). Absence of a witness is not
 * evidence of a move — the absence discipline this design inherits from `SourceExamination` says a
 * producer that said nothing does not get a verdict assumed on its behalf. Only a witness that could
 * have testified and FAILED suppresses the highlight.
 */
export function locateWitness(text: string, span: CharSpan, excerpt: string): boolean {
  // The producer appends an ellipsis when it clamps at 240 chars; that is its mark, not the text's.
  const normalizedExcerpt = normalize(excerpt, 0, excerpt.length).text.replace(/[….]+$/u, '').trim();
  if (normalizedExcerpt.length < WITNESS_MIN_CHARS) return true;
  const witness = normalizedExcerpt.slice(0, WITNESS_CHARS);
  const found = locateText(text, witness, span.start, span.end);
  if (found === null) return false;
  return found.start - span.start <= WITNESS_DRIFT_CHARS;
}
