// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 846 §2.1 — the ONE configured markdown parser.
 *
 * Before this module the app carried two independently-constructed `Marked` instances (the chat
 * renderer's and the inspector block-mapper's), each followed by its own `DOMPurify.sanitize` call.
 * They were configured by copy, so a change to one reached the other only if someone remembered.
 *
 * Everything the two consumers legitimately SHARE lives here — the GFM dialect, synchronous
 * parsing, and the sanitize step that must follow every parse. The one thing they legitimately
 * DIFFER on is a required parameter (`breaks`), so a new consumer has to state its answer at the
 * call site instead of inheriting somebody else's (§2.2: a default is what produced the state this
 * tempdoc fixes).
 *
 * Parse GRANULARITY is deliberately NOT a parameter: `MarkdownBlock` parses a whole answer,
 * `markdownBlockMap` parses one top-level block at a time so each rendered block carries its source
 * line range. Both call the same renderer, just with different amounts of text.
 */
import { Marked } from 'marked';
import DOMPurify from 'dompurify';

export interface MarkdownRendererOptions {
  /**
   * Treat a single newline inside a paragraph as a hard line break (`<br>`).
   *
   * Required, with no default. ON is chat-message ergonomics — a human's Enter is a line break.
   * OFF is how markdown is defined and how model output and `.md` files must be read: prose that
   * soft-wraps is one paragraph, not a column of ragged lines (tempdoc 846 §2.2).
   */
  readonly breaks: boolean;
}

/** A configured parser whose output is already sanitized and safe for `unsafeHTML`. */
export interface MarkdownRenderer {
  /** Parse `source` as GFM and return sanitized HTML (`''` for empty input). */
  render(source: string): string;
}

/**
 * Build a markdown renderer. Hold the result at module scope in the consumer — constructing a
 * `Marked` per render would rebuild its rule tables on every frame of a stream.
 */
export function createMarkdownRenderer(options: MarkdownRendererOptions): MarkdownRenderer {
  const md = new Marked({ gfm: true, breaks: options.breaks });
  return {
    render(source: string): string {
      if (!source) return '';
      // `async: false` at the call site rather than in the constructor: it is what makes the
      // return type a string rather than a promise, so it belongs to THIS call.
      const raw = md.parse(source, { async: false }) as string;
      // The invariant this factory exists to make un-forgettable: HTML the app did not itself
      // escape is never handed to `unsafeHTML` unsanitized.
      return DOMPurify.sanitize(raw);
    },
  };
}
