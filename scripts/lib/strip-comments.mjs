/**
 * Single-pass, mode-tracked comment stripper for the "scan code, not prose" pattern used by
 * ~20 `scripts/ci/check-*.mjs` governance gates (tempdoc 698, CodeQL js/incomplete-multi-
 * character-sanitization). Replaces the chained-regex version those files used to each carry
 * their own copy of:
 *
 *   s.replace(/<!--[\s\S]*?-->/g, '')
 *    .replace(/\/\*[\s\S]*?\*\//g, '')
 *    .replace(/^\s*\*.*$/gm, '')
 *    .replace(/(^|[^:])\/\/.*$/gm, '$1')
 *
 * That chain is vulnerable to a cross-pass reconstitution bug: each `.replace()` call scans the
 * OUTPUT of the previous call, so removing one comment kind can bring previously non-adjacent
 * characters together into a NEW comment-looking sequence that wasn't in the original text —
 * hiding real, uncommented code from the gate. See strip-comments.test.mjs for a concrete
 * worked example: an HTML comment sitting between a bare slash and a bare asterisk gets removed
 * first, and the now-adjacent slash+asterisk are then read as opening a block comment that
 * swallows real code after it — even though no such block comment existed in the original text.
 *
 * This version tracks comment state directly against the ORIGINAL text in one left-to-right
 * scan, so a later "pass" can never observe artifacts left behind by an earlier one — there are
 * no separate passes.
 *
 * Faithful to the original's edge-case behavior (verified via a differential test against the
 * original chain — see strip-comments.test.mjs) — including its one real limitation: an
 * unterminated block or HTML comment opener is left untouched rather than commenting out the
 * rest of the file, matching the original non-greedy regexes' behavior on unterminated input.
 */

export function stripComments(text, { withHtml = true } = {}) {
  let out = '';
  let i = 0;
  const n = text.length;
  let mode = 'code';
  while (i < n) {
    const ch = text[i];
    if (mode === 'code') {
      if (withHtml && ch === '<' && text[i + 1] === '!' && text[i + 2] === '-' && text[i + 3] === '-') {
        if (text.indexOf('-->', i + 4) !== -1) {
          mode = 'html';
          i += 4;
          continue;
        }
      } else if (ch === '/' && text[i + 1] === '*') {
        if (text.indexOf('*/', i + 2) !== -1) {
          mode = 'block';
          i += 2;
          continue;
        }
      } else if (ch === '/' && text[i + 1] === '/' && text[i - 1] !== ':') {
        mode = 'line';
        i += 2;
        continue;
      }
      out += ch;
      i += 1;
    } else if (mode === 'html') {
      if (ch === '-' && text[i + 1] === '-' && text[i + 2] === '>') {
        mode = 'code';
        i += 3;
      } else {
        i += 1;
      }
    } else if (mode === 'block') {
      if (ch === '*' && text[i + 1] === '/') {
        mode = 'code';
        i += 2;
      } else {
        i += 1;
      }
    } else if (mode === 'line') {
      if (ch === '\n' || ch === '\r') {
        mode = 'code';
        out += ch;
      }
      i += 1;
    }
  }
  // Safe as an isolated final pass: no real comment structure remains at this point for a
  // stray leading-asterisk continuation line to interact with.
  return out.replace(/^\s*\*.*$/gm, '');
}
