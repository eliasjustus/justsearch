#!/usr/bin/env node

import assert from 'node:assert/strict';

import {
  extractUrls,
  parseUrl,
  canonicalizeArgs,
  canonicalUrlString,
  resolveAgainstCatalog,
  validateArgs,
  compareToExpected,
  compareToExpectedList,
  evaluateUrlEmission,
} from './agent-battery-url-scorer.mjs';

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const catalog = {
  operations: [
    {
      id: 'core_search_index',
      presentation: { title: 'Search the knowledge index' },
      intf: {
        inputs: {
          type: 'object',
          properties: {
            query: { type: 'string' },
            limit: { type: 'integer', minimum: 1, maximum: 100 },
          },
          required: ['query'],
          additionalProperties: false,
        },
      },
    },
    {
      id: 'core_browse_folders',
      presentation: { title: 'Browse indexed folders' },
      intf: {
        inputs: {
          type: 'object',
          properties: { parent_path: { type: 'string' } },
          additionalProperties: false,
        },
      },
    },
    {
      id: 'bulk_reindex',
      presentation: { title: 'Bulk reindex' },
      // No intf.inputs — schema absent
    },
  ],
  surfaces: [
    { id: 'library' },
    { id: 'activity' },
  ],
};

// ---------------------------------------------------------------------------
// extractUrls
// ---------------------------------------------------------------------------

assert.deepEqual(extractUrls(''), [], 'empty string yields no URLs');
assert.deepEqual(extractUrls('no urls here'), [], 'plain text yields no URLs');
assert.deepEqual(extractUrls(null), [], 'null input is safe');

{
  const out = extractUrls('check this: justsearch://op/core_search_index?query=hello');
  assert.equal(out.length, 1);
  assert.equal(out[0].raw, 'justsearch://op/core_search_index?query=hello');
}

{
  const out = extractUrls('[search](justsearch://op/core_search_index?query=hello) for results');
  assert.equal(out.length, 1, 'Markdown link form extracts the URL');
  assert.equal(out[0].raw, 'justsearch://op/core_search_index?query=hello');
}

{
  const out = extractUrls('first justsearch://surface/library and second justsearch://op/bulk_reindex done');
  assert.equal(out.length, 2);
  assert.equal(out[0].raw, 'justsearch://surface/library');
  assert.equal(out[1].raw, 'justsearch://op/bulk_reindex');
}

{
  const out = extractUrls('[a](justsearch://surface/library) [b](justsearch://surface/activity?filter=failed)');
  assert.equal(out.length, 2, 'Multiple Markdown links extract independently');
  assert.equal(out[1].raw, 'justsearch://surface/activity?filter=failed');
}

// ---------------------------------------------------------------------------
// parseUrl
// ---------------------------------------------------------------------------

assert.equal(parseUrl(null), null);
assert.equal(parseUrl(''), null);
assert.equal(parseUrl('not a url'), null);
assert.equal(parseUrl('http://example.com'), null, 'rejects non-justsearch scheme');
assert.equal(parseUrl('justsearch://op'), null, 'rejects missing id');
assert.equal(parseUrl('justsearch://op/'), null, 'rejects empty id after slash');
assert.equal(parseUrl('justsearch://op/foo/bar'), null, 'rejects multi-segment id');
assert.equal(parseUrl('justsearch://other/foo'), null, 'rejects unknown kind');
assert.equal(parseUrl('justsearch://op/has spaces'), null, 'rejects non-URL-safe id (encoded space rejected after decode)');

{
  const p = parseUrl('justsearch://op/core_search_index');
  assert.deepEqual(p, { kind: 'op', id: 'core_search_index', args: {} });
}

{
  const p = parseUrl('justsearch://surface/library');
  assert.deepEqual(p, { kind: 'surface', id: 'library', args: {} });
}

{
  const p = parseUrl('justsearch://op/core_search_index?query=hello&limit=5');
  assert.deepEqual(p, {
    kind: 'op',
    id: 'core_search_index',
    args: { query: 'hello', limit: '5' },
  });
}

{
  const p = parseUrl('justsearch://op/x?tag=a&tag=b&tag=c');
  assert.deepEqual(p, { kind: 'op', id: 'x', args: { tag: ['a', 'b', 'c'] } });
}

{
  const p = parseUrl('justsearch://op/x?q=hello%20world');
  assert.equal(p.args.q, 'hello world', 'decodes URL-encoded args');
}

// ---------------------------------------------------------------------------
// canonicalizeArgs / canonicalUrlString
// ---------------------------------------------------------------------------

assert.equal(canonicalizeArgs({}), '');
assert.equal(canonicalizeArgs(null), '');
assert.equal(canonicalizeArgs({ a: '1' }), 'a=1');

assert.equal(
  canonicalizeArgs({ b: '2', a: '1' }),
  'a=1&b=2',
  'sorts keys',
);

assert.equal(
  canonicalizeArgs({ tag: ['a', 'b'] }),
  'tag=a&tag=b',
  'flattens array values',
);

assert.equal(
  canonicalizeArgs({ q: 'hello world' }),
  'q=hello%20world',
  're-encodes special characters',
);

assert.equal(
  canonicalUrlString({ kind: 'op', id: 'foo', args: {} }),
  'justsearch://op/foo',
);

assert.equal(
  canonicalUrlString({ kind: 'op', id: 'foo', args: { b: '2', a: '1' } }),
  'justsearch://op/foo?a=1&b=2',
);

// ---------------------------------------------------------------------------
// 548 S4-A — query verb (justsearch://query?q=<text>)
// ---------------------------------------------------------------------------

assert.deepEqual(
  parseUrl('justsearch://query?q=health'),
  { kind: 'query', query: 'health', args: {} },
  'parses a bare query URL',
);
assert.deepEqual(
  parseUrl('justsearch://query?q=rust&lang=en'),
  { kind: 'query', query: 'rust', args: { lang: 'en' } },
  'parses query with refinement args',
);
assert.equal(parseUrl('justsearch://query'), null, 'rejects query with no q param');
assert.equal(parseUrl('justsearch://query?q='), null, 'rejects query with blank q');
assert.equal(parseUrl('justsearch://query/foo?q=x'), null, 'rejects query with non-empty path');

assert.equal(
  canonicalUrlString({ kind: 'query', query: 'health', args: {} }),
  'justsearch://query?q=health',
  'canonicalizes a bare query',
);
assert.equal(
  canonicalUrlString({ kind: 'query', query: 'rust', args: { lang: 'en' } }),
  'justsearch://query?lang=en&q=rust',
  'canonicalizes query with refinements (keys sorted)',
);

assert.equal(
  compareToExpected(parseUrl('justsearch://query?q=health'), 'justsearch://query?q=health'),
  true,
  'query exact match',
);
assert.equal(
  compareToExpected(parseUrl('justsearch://query?q=health'), 'justsearch://query?q=other'),
  false,
  'query text mismatch',
);
assert.equal(
  compareToExpected(parseUrl('justsearch://surface/core.search-surface?query=health'), 'justsearch://query?q=health'),
  false,
  'surface-nav does NOT satisfy a query expectation (kind mismatch)',
);

// ---------------------------------------------------------------------------
// 548 §4.5 — answer verb (justsearch://answer?q=<question>)
// ---------------------------------------------------------------------------
assert.deepEqual(
  parseUrl('justsearch://answer?q=what%20is%20rust'),
  { kind: 'answer', prompt: 'what is rust', shape: 'core.rag-ask', args: {} },
  'parses a bare answer URL with the default shape',
);
assert.deepEqual(
  parseUrl('justsearch://answer?q=summarize&shape=core.summarize&lang=en'),
  { kind: 'answer', prompt: 'summarize', shape: 'core.summarize', args: { lang: 'en' } },
  'parses answer with explicit shape + refinement args',
);
assert.equal(parseUrl('justsearch://answer'), null, 'rejects answer with no q param');
assert.equal(parseUrl('justsearch://answer?q='), null, 'rejects answer with blank q');
assert.equal(parseUrl('justsearch://answer/foo?q=x'), null, 'rejects answer with non-empty path');

assert.equal(
  canonicalUrlString({ kind: 'answer', prompt: 'what is rust', shape: 'core.rag-ask', args: {} }),
  'justsearch://answer?q=what%20is%20rust',
  'canonicalizes a bare answer (default shape omitted)',
);
assert.equal(
  canonicalUrlString({ kind: 'answer', prompt: 'x', shape: 'core.summarize', args: { lang: 'en' } }),
  'justsearch://answer?lang=en&q=x&shape=core.summarize',
  'canonicalizes answer with explicit shape + refinements (keys sorted)',
);

assert.equal(
  compareToExpected(parseUrl('justsearch://answer?q=health'), 'justsearch://answer?q=health'),
  true,
  'answer exact match',
);
assert.equal(
  compareToExpected(parseUrl('justsearch://answer?q=health'), 'justsearch://answer?q=other'),
  false,
  'answer prompt mismatch',
);
assert.equal(
  compareToExpected(
    parseUrl('justsearch://answer?q=health&shape=core.summarize'),
    'justsearch://answer?q=health',
  ),
  false,
  'answer shape mismatch',
);

// ---------------------------------------------------------------------------
// resolveAgainstCatalog
// ---------------------------------------------------------------------------

{
  const op = parseUrl('justsearch://op/core_search_index');
  const found = resolveAgainstCatalog(op, catalog);
  assert.equal(found?.id, 'core_search_index');
}

{
  const surface = parseUrl('justsearch://surface/library');
  const found = resolveAgainstCatalog(surface, catalog);
  assert.equal(found?.id, 'library');
}

{
  const unknown = parseUrl('justsearch://op/does_not_exist');
  assert.equal(resolveAgainstCatalog(unknown, catalog), null);
}

{
  // op-id that happens to match a surface-id: should not resolve as op
  const wrongKind = parseUrl('justsearch://op/library');
  assert.equal(resolveAgainstCatalog(wrongKind, catalog), null, 'partition is honored');
}

assert.equal(resolveAgainstCatalog(null, catalog), null);
assert.equal(resolveAgainstCatalog(parseUrl('justsearch://op/x'), null), null);

// ---------------------------------------------------------------------------
// validateArgs
// ---------------------------------------------------------------------------

{
  const p = parseUrl('justsearch://op/core_search_index?query=hello');
  const entry = resolveAgainstCatalog(p, catalog);
  const r = validateArgs(p, entry);
  assert.equal(r.ok, true, 'required arg present');
}

{
  const p = parseUrl('justsearch://op/core_search_index?query=hello&limit=5');
  const entry = resolveAgainstCatalog(p, catalog);
  const r = validateArgs(p, entry);
  assert.equal(r.ok, true, 'integer arg coerced from string');
}

{
  const p = parseUrl('justsearch://op/core_search_index');  // missing required `query`
  const entry = resolveAgainstCatalog(p, catalog);
  const r = validateArgs(p, entry);
  assert.equal(r.ok, false, 'missing required arg rejected');
  assert.ok(Array.isArray(r.errors) && r.errors.length > 0);
}

{
  const p = parseUrl('justsearch://op/core_search_index?query=hi&unknown=x');
  const entry = resolveAgainstCatalog(p, catalog);
  const r = validateArgs(p, entry);
  assert.equal(r.ok, false, 'additionalProperties rejected');
}

{
  const p = parseUrl('justsearch://op/core_search_index?query=hi&limit=999');
  const entry = resolveAgainstCatalog(p, catalog);
  const r = validateArgs(p, entry);
  assert.equal(r.ok, false, 'integer bounds violated');
}

{
  const p = parseUrl('justsearch://op/bulk_reindex');  // no schema declared
  const entry = resolveAgainstCatalog(p, catalog);
  const r = validateArgs(p, entry);
  assert.equal(r.ok, null, 'no schema → null result, not failure');
  assert.equal(r.reason, 'no_schema');
}

// ---------------------------------------------------------------------------
// compareToExpected
// ---------------------------------------------------------------------------

{
  const p = parseUrl('justsearch://op/core_search_index?query=hello');
  assert.equal(
    compareToExpected(p, 'justsearch://op/core_search_index?query=hello'),
    true,
    'exact match',
  );
}

{
  const p = parseUrl('justsearch://op/core_search_index?limit=5&query=hello');
  assert.equal(
    compareToExpected(p, 'justsearch://op/core_search_index?query=hello&limit=5'),
    true,
    'arg order normalized',
  );
}

{
  const p = parseUrl('justsearch://op/core_search_index?query=hello');
  assert.equal(
    compareToExpected(p, 'justsearch://op/core_browse_folders?query=hello'),
    false,
    'id mismatch',
  );
}

{
  const p = parseUrl('justsearch://surface/library');
  assert.equal(
    compareToExpected(p, 'justsearch://op/library'),
    false,
    'kind mismatch',
  );
}

{
  const p = parseUrl('justsearch://op/x?a=1');
  assert.equal(compareToExpected(p, 'justsearch://op/x?a=2'), false, 'args mismatch');
  assert.equal(compareToExpected(p, 'justsearch://op/x'), false, 'extra arg mismatch');
}

// ---------------------------------------------------------------------------
// compareToExpectedList
// ---------------------------------------------------------------------------

{
  const ps = [
    parseUrl('justsearch://surface/library'),
    parseUrl('justsearch://op/core_search_index?query=hi'),
  ];
  assert.equal(
    compareToExpectedList(ps, [
      'justsearch://op/core_search_index?query=hi',
      'justsearch://surface/library',
    ]),
    true,
    'order-independent match',
  );
}

{
  const ps = [
    parseUrl('justsearch://surface/library'),
    parseUrl('justsearch://surface/library'),
  ];
  assert.equal(
    compareToExpectedList(ps, [
      'justsearch://surface/library',
      'justsearch://surface/library',
    ]),
    true,
    'multiplicity preserved (two identical needed and given)',
  );
  assert.equal(
    compareToExpectedList(ps, [
      'justsearch://surface/library',
      'justsearch://surface/activity',
    ]),
    false,
    'one expected URL missing from emissions',
  );
}

assert.equal(compareToExpectedList([], []), true, 'empty expected matches empty emissions');
assert.equal(compareToExpectedList([parseUrl('justsearch://surface/x')], []), true, 'empty expected matches any emissions');

// ---------------------------------------------------------------------------
// evaluateUrlEmission — top-level
// ---------------------------------------------------------------------------

function makeTranscript(response) {
  return { finalResponse: response };
}

// All-good case
{
  const scenario = { expectedURL: 'justsearch://op/core_search_index?query=hello' };
  const transcript = makeTranscript(
    'I will search for you. [search](justsearch://op/core_search_index?query=hello)',
  );
  const r = evaluateUrlEmission(scenario, transcript, catalog);
  assert.equal(r.urlPresent, true);
  assert.equal(r.schemeValid, true);
  assert.equal(r.opIdResolves, true);
  assert.equal(r.argsValidate, true);
  assert.equal(r.semanticMatch, true);
  assert.deepEqual(r.failures, []);
}

// 548 S4-A — query verb scores as a clean pass (opIdResolves N/A → true, no schema)
{
  const scenario = { expectedURL: 'justsearch://query?q=health' };
  const transcript = makeTranscript(
    'Let me search for that. [search](justsearch://query?q=health)',
  );
  const r = evaluateUrlEmission(scenario, transcript, catalog);
  assert.equal(r.urlPresent, true);
  assert.equal(r.schemeValid, true);
  assert.equal(r.opIdResolves, true, 'query has no catalog id — resolution is N/A → true');
  assert.equal(r.argsValidate, null, 'query refinements have no inputSchema');
  assert.equal(r.semanticMatch, true);
  assert.deepEqual(r.failures, []);
}

// No URL emitted
{
  const scenario = { expectedURL: 'justsearch://op/core_search_index?query=hello' };
  const transcript = makeTranscript('I cannot help with that.');
  const r = evaluateUrlEmission(scenario, transcript, catalog);
  assert.equal(r.urlPresent, false);
  assert.equal(r.schemeValid, false);
  assert.equal(r.opIdResolves, false);
  assert.equal(r.argsValidate, null);
  assert.equal(r.semanticMatch, false);
}

// Unknown op id
{
  const scenario = {};
  const transcript = makeTranscript('Try [this](justsearch://op/imaginary_op?x=1)');
  const r = evaluateUrlEmission(scenario, transcript, catalog);
  assert.equal(r.urlPresent, true);
  assert.equal(r.schemeValid, true);
  assert.equal(r.opIdResolves, false);
  assert.equal(r.argsValidate, null, 'no entry to validate against');
  assert.equal(r.semanticMatch, null, 'no expectation declared');
  assert.ok(r.failures.some((f) => f.startsWith('unknown_id:')));
}

// Args invalid
{
  const scenario = {};
  const transcript = makeTranscript('[s](justsearch://op/core_search_index?limit=5)');  // missing query
  const r = evaluateUrlEmission(scenario, transcript, catalog);
  assert.equal(r.urlPresent, true);
  assert.equal(r.opIdResolves, true);
  assert.equal(r.argsValidate, false);
  assert.ok(r.failures.some((f) => f.startsWith('args_invalid:')));
}

// No schema available → argsValidate null but everything else passes
{
  const scenario = { expectedURL: 'justsearch://op/bulk_reindex' };
  const transcript = makeTranscript('[reindex](justsearch://op/bulk_reindex)');
  const r = evaluateUrlEmission(scenario, transcript, catalog);
  assert.equal(r.urlPresent, true);
  assert.equal(r.opIdResolves, true);
  assert.equal(r.argsValidate, null);
  assert.equal(r.semanticMatch, true);
}

// expectedURLs list
{
  const scenario = {
    expectedURLs: [
      'justsearch://surface/activity?filter=failed',
      'justsearch://op/bulk_reindex',
    ],
  };
  const transcript = makeTranscript(
    'Opening [failures](justsearch://surface/activity?filter=failed) and queueing [reindex](justsearch://op/bulk_reindex).',
  );
  const r = evaluateUrlEmission(scenario, transcript, catalog);
  assert.equal(r.semanticMatch, true);
}

// expectedURLs list — emitted in wrong order is fine
{
  const scenario = {
    expectedURLs: [
      'justsearch://surface/activity?filter=failed',
      'justsearch://op/bulk_reindex',
    ],
  };
  const transcript = makeTranscript(
    'Queueing reindex: justsearch://op/bulk_reindex, then opening justsearch://surface/activity?filter=failed',
  );
  const r = evaluateUrlEmission(scenario, transcript, catalog);
  assert.equal(r.semanticMatch, true, 'order-independent');
}

// expectedURLs list — one expected URL missing
{
  const scenario = {
    expectedURLs: [
      'justsearch://surface/activity?filter=failed',
      'justsearch://op/bulk_reindex',
    ],
  };
  const transcript = makeTranscript('Only this: justsearch://op/bulk_reindex');
  const r = evaluateUrlEmission(scenario, transcript, catalog);
  assert.equal(r.semanticMatch, false);
}

// No expectation → semanticMatch null
{
  const scenario = {};
  const transcript = makeTranscript('justsearch://op/core_search_index?query=hi');
  const r = evaluateUrlEmission(scenario, transcript, catalog);
  assert.equal(r.semanticMatch, null);
}

// ---------------------------------------------------------------------------
// Done
// ---------------------------------------------------------------------------

console.log('agent-battery-url-scorer.mjs: all tests passed');
