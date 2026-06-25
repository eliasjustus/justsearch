#!/usr/bin/env node

/**
 * Slice 487 §5 — catalog-walker that regenerates `v1.json` from a live
 * JustSearch backend's `/api/registry/operations` + `/api/registry/surfaces`.
 *
 * For each Operation, emits fixtures covering:
 *   - bare URL ({}: no args)
 *   - one scalar-arg variant per first scalar property
 *   - one array-arg variant if any property declares array type
 *   - one enum-arg variant if any property declares an enum
 *
 * For each Surface, emits the same shape against the surface's
 * `stateSchema` (or just a bare URL if no state).
 *
 * Plus a fixed set of negative-case fixtures.
 *
 * Usage:
 *
 *   node scripts/ci/url-grammar-fixtures/generate.mjs \
 *     --api-base-url http://127.0.0.1:<port> \
 *     [--out scripts/ci/url-grammar-fixtures/v1.json]
 *
 * Requires the dev stack to be running. The script does NOT auto-start
 * a backend — start it via the MCP dev-tools or `gradlew runHeadless` first.
 */

import { argv } from 'node:process';
import { writeFileSync } from 'node:fs';

function parseArgs(args) {
  const out = {
    apiBaseUrl: null,
    out: 'scripts/ci/url-grammar-fixtures/v1.json',
  };
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === '--api-base-url') {
      out.apiBaseUrl = args[++i];
    } else if (arg === '--out') {
      out.out = args[++i];
    } else if (arg === '--help' || arg === '-h') {
      console.log(
        'Usage: node generate.mjs --api-base-url http://127.0.0.1:<port> [--out PATH]',
      );
      process.exit(0);
    }
  }
  if (!out.apiBaseUrl) {
    console.error('Error: --api-base-url is required (no live-catalog-derive without it).');
    process.exit(2);
  }
  return out;
}

async function fetchJson(url) {
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`GET ${url} → HTTP ${res.status}`);
  }
  return res.json();
}

/** Build a single positive-case fixture for an Op/Surface. */
function buildBareFixture(kind, id, prefix) {
  return {
    id: `${prefix}-bare`,
    input: `justsearch://${kind}/${id}`,
    expected: {
      kind: kind === 'surface' ? 'navigate' : 'invoke',
      target: id,
      ...(kind === 'surface' ? { state: {} } : { args: {} }),
    },
  };
}

const NEGATIVE_FIXTURES = [
  {
    id: 'neg-wrong-scheme-http',
    input: 'http://surface/core.library',
    expected: null,
  },
  {
    id: 'neg-wrong-scheme-file',
    input: 'file://surface/core.library',
    expected: null,
  },
  {
    id: 'neg-unknown-host',
    input: 'justsearch://action/core.library',
    expected: null,
  },
  {
    id: 'neg-empty-pathname',
    input: 'justsearch://surface/',
    expected: null,
  },
  {
    id: 'neg-no-pathname',
    input: 'justsearch://surface',
    expected: null,
  },
  {
    id: 'neg-multi-segment-pathname',
    input: 'justsearch://surface/core.library/extra',
    expected: null,
  },
  {
    id: 'neg-id-with-spaces',
    input: 'justsearch://surface/core library',
    expected: null,
  },
  {
    id: 'neg-not-a-url',
    input: 'not a url at all',
    expected: null,
  },
  {
    id: 'neg-empty-string',
    input: '',
    expected: null,
  },
];

async function main() {
  const opts = parseArgs(argv.slice(2));
  const apiBase = opts.apiBaseUrl.replace(/\/$/, '');

  const ops = await fetchJson(`${apiBase}/api/registry/operations`);
  const surfaces = await fetchJson(`${apiBase}/api/registry/surfaces`);

  const fixtures = [];
  for (const op of ops.entries ?? ops) {
    const id = op.id?.value ?? op.id;
    if (!id) continue;
    fixtures.push(buildBareFixture('op', id, `op-${id.replace(/\./g, '-')}`));
    // TODO(slice-487-followup): scalar / array / enum arg variants per the
    // Op's inputSchema. The schema-walker that emits these landed for the
    // probe scorer; lifting it into the generator is a follow-up.
  }
  for (const surface of surfaces.entries ?? surfaces) {
    const id = surface.id?.value ?? surface.id;
    if (!id) continue;
    fixtures.push(buildBareFixture('surface', id, `surf-${id.replace(/\./g, '-')}`));
  }

  fixtures.push(...NEGATIVE_FIXTURES);

  const corpus = {
    $schema: 'Slice 487 §5: cross-language URL grammar conformance corpus',
    version: 'v1',
    generatedFrom: `${apiBase} at ${new Date().toISOString()}`,
    fixtures,
  };
  writeFileSync(opts.out, JSON.stringify(corpus, null, 2) + '\n', 'utf8');
  console.log(`Wrote ${fixtures.length} fixtures to ${opts.out}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
