import assert from 'node:assert/strict';

import { countAny } from './enforcer.mjs';

// tempdoc 932 item 1: countAny must count real TS `any` casts (`as any`, `: any`, `<any>`)
// and NOT the English word "any" appearing in comments or strings/templates.

const proseOnly = [
  '// any documents to enrich',
  '/**',
  ' * The mechanism is per-collection, not a `justsearch-help` string check: any named',
  ' * non-default collection is marked through the same badge.',
  ' */',
  'export function noop(): void {}',
].join('\n');

const castsOnly = [
  'const a = (foo as any).bar;',
  'const b: any = 1;',
  'function f(x: any): any { return x; }',
  'const c = value as Array<any>;',
].join('\n');

const mixed = [
  '// any real casts below are the ones that count, this prose mention of any does not',
  '/** any change to this file should update the comment, not the assertion below */',
  'const s = "as any string literal, and : any inside a string, must not count";',
  'const t = `template any literal ${x} still not a cast`;',
  'const cast = (payload as any)?.docIds;',
  'const typed: any = cast;',
].join('\n');

assert.equal(countAny(proseOnly), 0, 'prose-only file (comments mentioning "any") must count 0');
assert.equal(countAny(castsOnly), 5, 'cast-only file must count every real any-cast');
assert.equal(countAny(mixed), 2, 'mixed file must count only the real casts, not the prose/string mentions');

console.log('ts-any countAny: all 3 checks passed');
