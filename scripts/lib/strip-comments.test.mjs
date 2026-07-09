/**
 * Differential + regression test for scripts/lib/strip-comments.mjs (tempdoc 698).
 *
 * Part 1 asserts byte-identical output against the original chained-regex implementation
 * across representative and edge-case input, so migrating the ~20 call sites doesn't change
 * behavior for real, legitimate source content.
 *
 * Part 2 demonstrates the actual bug being fixed: a cross-pass reconstitution that lets the
 * original implementation silently erase real, uncommented code.
 *
 * Run with: node scripts/lib/strip-comments.test.mjs
 */

import assert from 'node:assert/strict';
import { stripComments } from './strip-comments.mjs';

const ORIGINAL_WITH_HTML = (s) =>
  s
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\*.*$/gm, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1');

const ORIGINAL_NO_HTML = (s) =>
  s
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\*.*$/gm, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1');

const cases = [
  '// leading line comment\nconst x = 1;',
  '/* block */ const x = 1;',
  '/**\n * doc comment\n * @param x\n */\nfunction f(x) {}',
  '<!-- html comment --> <div></div>',
  'const url = "http://example.com"; // note',
  'a://not-a-comment-because-colon-before',
  'line1\nline2 // trailing\nline3',
  '/* multi\nline\nblock */\nafter',
  '<!-- multi\nline\nhtml -->\nafter',
  '',
  'no comments here at all',
  '/* unterminated block comment',
  '<!-- unterminated html comment',
  '// unterminated line comment',
  '/**/',
  '<!---->',
  '/*/',
  '//',
  '<!--',
  '-->',
  '*/',
  '/*',
  ' * stray doc-line outside any comment\ncode',
  'a/b/c',
  '1/2 + 3/4',
];

for (const c of cases) {
  assert.equal(stripComments(c, { withHtml: true }), ORIGINAL_WITH_HTML(c), `withHtml=true mismatch for ${JSON.stringify(c)}`);
  assert.equal(stripComments(c, { withHtml: false }), ORIGINAL_NO_HTML(c), `withHtml=false mismatch for ${JSON.stringify(c)}`);
}

// The bug this replaces: HTML-comment removal can bring a bare "/" and a later bare "*"
// together into a brand-new "/* ... */" span that step 2 then strips — hiding real code.
const bypass = '/<!-- hide -->* REAL_USAGE_X */';
assert.equal(ORIGINAL_WITH_HTML(bypass), '', 'sanity: confirms the original chain is genuinely vulnerable to this input');
assert.match(stripComments(bypass, { withHtml: true }), /REAL_USAGE_X/, 'the fixed version must not erase real code via cross-pass reconstitution');

console.log('strip-comments: PASS');
