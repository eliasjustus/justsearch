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
 * How much of the excerpt has to reappear at the anchored offsets for the reader to trust them.
 * Long enough that boilerplate ("Introduction", a table header) cannot satisfy it by accident;
 * short enough that a re-extraction which re-wrapped or re-punctuated the tail of a passage does
 * not read as a move.
 */
const WITNESS_CHARS = 48;

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
    chars.push(ch.toLowerCase());
    origin.push(i);
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
 * The staleness check (§3 R1.4). `true` means the citation's excerpt still begins inside the span
 * its offsets name, so the offsets can be trusted to address the passage they were computed for.
 *
 * An EMPTY excerpt returns `true`: absence of a witness is not evidence of a move, and the absence
 * discipline this design inherits from `SourceExamination` says a producer that said nothing does
 * not get a verdict assumed on its behalf. Only a witness that FAILS suppresses the highlight.
 */
export function locateWitness(text: string, span: CharSpan, excerpt: string): boolean {
  const normalizedExcerpt = normalize(excerpt, 0, excerpt.length).text.replace(/[….]+$/u, '').trim();
  if (normalizedExcerpt.length === 0) return true;
  const witness = normalizedExcerpt.slice(0, WITNESS_CHARS);
  return locateText(text, witness, span.start, span.end) !== null;
}
