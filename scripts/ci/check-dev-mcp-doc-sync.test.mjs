/**
 * Tests for the dev-MCP doc-sync gate (scripts/ci/check-dev-mcp-doc-sync.mjs).
 *
 * A gate that cannot fail reads as coverage. These exercise the BITE in both directions — a tool
 * registered but undocumented, a doc row with no tool behind it, a stale count, a wrong endpoint
 * path (the exact defect tempdoc 844 §6.3 found), an allowlist entry missing from the doc — plus
 * the degenerate "the table moved" cases that must fail loudly instead of passing. The last block
 * runs the real gate against the real tree, spawning the real MCP server.
 *
 * Run: `node scripts/ci/check-dev-mcp-doc-sync.test.mjs` (exits non-zero on failure)
 */
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

import {
  compare,
  documentedAllowlist,
  documentedEndpointMap,
  documentedToolCount,
  documentedToolNames,
  registeredToolNames,
  run,
  tableUnderHeading,
} from './check-dev-mcp-doc-sync.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

let passed = 0;
const failures = [];
const ok = (label, cond) => {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (error) {
    failures.push(error.message);
  }
};

/* --- a minimal, self-consistent fixture ---------------------------------- */

const TOOLS = ['justsearch.dev.start', 'justsearch.dev.stop'];
const ENDPOINTS = { status: '/api/status', health: '/api/health' };
const ALLOWLIST = [
  { path: '/api/settings/v2', methods: ['GET', 'POST'] },
  { path: '/api/preview', methods: ['GET'] },
];

function doc({ tools = TOOLS, count = 2, endpoints = ENDPOINTS, allow = ALLOWLIST } = {}) {
  const toolRows = tools.map((t) => `| \`${t}\` | does a thing |`).join('\n');
  const endpointRows = Object.entries(endpoints).map(([k, v]) => `| \`${k}\` | \`${v}\` |`).join('\n');
  const allowRows = allow.map((e) => `| \`${e.path}\` | ${e.methods.join(', ')} |`).join('\n');
  return [
    '# MCP Dev Tools Workflow',
    '',
    '## Available Tools',
    '',
    `The dev MCP surface exposes exactly these **${count}** tools:`,
    '',
    '| Tool | Purpose |',
    '|------|---------|',
    toolRows,
    '',
    'Some trailing prose.',
    '',
    '## Predefined JSON Endpoints',
    '',
    '| Key | Endpoint |',
    '|-----|----------|',
    endpointRows,
    '',
    '## Generic API Calls',
    '',
    '| Path | Methods |',
    '|------|---------|',
    allowRows,
    '',
  ].join('\n');
}

const check = (overrides = {}) => compare({
  docText: overrides.docText ?? doc(overrides.docOpts),
  toolNames: overrides.toolNames ?? TOOLS,
  endpointMap: overrides.endpointMap ?? ENDPOINTS,
  allowlist: overrides.allowlist ?? ALLOWLIST,
});

/* --- parsing -------------------------------------------------------------- */

ok('the tool table is parsed from under its heading', JSON.stringify(documentedToolNames(doc())) === JSON.stringify(TOOLS));
ok('the prose count is parsed', documentedToolCount(doc()) === 2);
ok('the endpoint table is parsed', documentedEndpointMap(doc()).effective_config === undefined
  && documentedEndpointMap(doc()).status === '/api/status');
ok('the allowlist table is parsed with methods', JSON.stringify(documentedAllowlist(doc())['/api/settings/v2']) === JSON.stringify(['GET', 'POST']));
ok('a heading with no table under it returns null', tableUnderHeading('## Available Tools\n\njust prose\n\n## Next\n', 'Available Tools') === null);

/* --- the happy path ------------------------------------------------------- */

ok('a doc that matches the surface passes', check().length === 0);

/* --- bite: tools --------------------------------------------------------- */

{
  // A tool was ADDED to the server and nobody touched the doc.
  const errors = check({ toolNames: [...TOOLS, 'justsearch.dev.brand_new'], docOpts: { count: 3 } });
  ok('a registered-but-undocumented tool fails the gate', errors.length === 1);
  ok('…and the message names the tool', errors[0].includes('justsearch.dev.brand_new'));
}

{
  // A tool was REMOVED from the server and the doc still advertises it.
  const errors = check({ toolNames: ['justsearch.dev.start'], docOpts: { count: 1 } });
  ok('a documented-but-unregistered tool fails the gate', errors.length === 1);
  ok('…and the message names the phantom row', errors[0].includes('justsearch.dev.stop') && errors[0].includes('NOT registered'));
}

{
  const errors = check({ toolNames: [...TOOLS, 'justsearch.dev.brand_new'] });
  ok('the stale prose count is reported separately from the missing row', errors.length === 2
    && errors.some((e) => e.includes('claims 2 tools') && e.includes('registers 3')));
}

ok('an empty tool list fails loudly instead of passing', check({ toolNames: [], docOpts: { tools: [], count: 0 } })
  .some((e) => e.includes('empty surface must never pass silently')));

ok('a missing Available Tools table fails', check({ docText: '# doc\n\n## Predefined JSON Endpoints\n' })
  .some((e) => e.includes('could not find the "## Available Tools" table')));

ok('a missing count sentence fails', check({ docText: doc().replace(/exposes exactly these \*\*2\*\* tools/, 'exposes some tools') })
  .some((e) => e.includes('could not find the tool-count sentence')));

/* --- bite: endpoint keys and their paths --------------------------------- */

{
  // The §6.3 defect verbatim: the doc names a path the code does not use.
  const errors = check({ docOpts: { endpoints: { status: '/api/knowledge/status', health: '/api/health' } } });
  ok('a wrong endpoint PATH fails even though the key is present', errors.length === 1);
  ok('…and the message names both paths and says the code wins',
    errors[0].includes('/api/status') && errors[0].includes('/api/knowledge/status') && errors[0].includes('code is the authority'));
}

ok('an endpoint key missing from the doc fails',
  check({ endpointMap: { ...ENDPOINTS, debug_state: '/api/debug/state' } })
    .some((e) => e.includes('"debug_state"') && e.includes('missing from the endpoint table')));

ok('an endpoint key documented but not accepted fails',
  check({ docOpts: { endpoints: { ...ENDPOINTS, ghost: '/api/ghost' } } })
    .some((e) => e.includes('documents endpoint key "ghost"')));

/* --- bite: the api_call allowlist ---------------------------------------- */

ok('an allowlisted path missing from the doc fails',
  check({ allowlist: [...ALLOWLIST, { path: '/api/action-ledger', methods: ['GET'] }] })
    .some((e) => e.includes('/api/action-ledger') && e.includes('missing from the allowlist table')));

ok('a doc row for a path that is NOT allowlisted fails',
  check({ docOpts: { allow: [...ALLOWLIST, { path: '/api/nope', methods: ['GET'] }] } })
    .some((e) => e.includes('/api/nope') && e.includes('would be refused')));

ok('a method-set mismatch fails',
  check({ docOpts: { allow: [{ path: '/api/settings/v2', methods: ['GET'] }, ALLOWLIST[1]] } })
    .some((e) => e.includes('allowlist methods for /api/settings/v2 differ')));

/* --- the real repo, with the real server --------------------------------- */

{
  const names = await registeredToolNames(REPO_ROOT);
  ok('the real MCP server answers tools/list with a non-empty set', names.length > 0);
  ok('…and it no longer registers the tempdoc 844 P1 prunes', !names.some((n) => [
    'justsearch.dev.status',
    'justsearch.dev.agent_chat',
    'justsearch.dev.capture_evidence',
    'justsearch.dev.validate_evidence',
  ].includes(n)));
  ok('…and it still registers acquire_when_free (kept: the OWNER_CONFLICT remedy)',
    names.includes('justsearch.dev.acquire_when_free'));

  const errors = await run(REPO_ROOT);
  ok(`the gate passes on the current tree${errors.length ? ` — got: ${errors.join(' | ')}` : ''}`, errors.length === 0);
}

if (failures.length > 0) {
  console.error(`check-dev-mcp-doc-sync.test: FAIL (${failures.length})`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`check-dev-mcp-doc-sync.test: OK (${passed} assertions)`);
