// @vitest-environment node

/**
 * A backtick inside a `css` tagged template TERMINATES it, and the damage lands far from the cause:
 * either an opaque parse error pointing hundreds of lines away, or — worse — a module that parses
 * and throws `css(...).deck is not a function` at import time. I hit this three times writing the
 * comments in `SearchV2View`'s stylesheet during tempdoc 818 §6g, each time caught only by running
 * the suite and reading a confusing trace.
 *
 * This file reads the SOURCE TEXT and imports nothing from the view, which is the whole point: the
 * obvious version of this guard — assert `SearchV2View.styles` contains no backtick — is VACUOUS,
 * because a stray backtick prevents the module from loading at all, so the assertion can only run in
 * the case where it has nothing to catch. A check that passes exactly when it is not needed is the
 * proxy-assertion shape this tempdoc spent five rewrites removing; it does not get to be added back
 * as a convenience.
 *
 * The stylesheet is unusually comment-dense by design (its rules carry the reasoning for laws L7 and
 * L13), so the hazard is structural rather than careless, and it is one scan to remove.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));

/** The text between `css\`` and the closing backtick of that template. */
function styleTemplates(source: string): string[] {
  const out: string[] = [];
  const opener = /\bcss`/g;
  let match: RegExpExecArray | null;
  while ((match = opener.exec(source)) !== null) {
    const start = match.index + match[0].length;
    const end = source.indexOf('`', start);
    out.push(source.slice(start, end < 0 ? source.length : end));
    opener.lastIndex = end < 0 ? source.length : end + 1;
  }
  return out;
}

describe('818 search-v2 — no stray backtick inside a css template', () => {
  const files = ['SearchV2View.ts'];

  it.each(files)('%s keeps every css template intact', (file) => {
    const source = readFileSync(join(HERE, file), 'utf8');
    const templates = styleTemplates(source);
    expect(templates.length, 'no css template found — this guard would be vacuous').toBeGreaterThan(
      0,
    );

    // A template that ended early is one that swallowed a backtick from a comment. The tell is that
    // the text does not reach the rules the file is supposed to declare.
    const longest = templates.reduce((a, b) => (a.length >= b.length ? a : b));
    expect(longest, 'the stylesheet template ended early — a comment backtick closed it').toContain(
      '.run-controls',
    );
  });

  it('the scan can actually FAIL — a comment backtick is detected', () => {
    // Anti-vacuity, on the guard itself: prove the detector reacts to the defect rather than to
    // nothing. This is the mutation the real file must never contain.
    const mutated = 'const s = css`\n  /* a `backtick` in a comment */\n  .run-controls { flex: 0 0 auto; }\n`;';
    const templates = styleTemplates(mutated);
    const longest = templates.reduce((a, b) => (a.length >= b.length ? a : b));
    expect(longest).not.toContain('.run-controls');
  });
});
