import assert from 'node:assert/strict';

import { findMissingEdges, resolveEntry } from './check-lockfile-completeness.mjs';

const packages = {
  '': { name: 'root', dependencies: { a: '^1.0.0' } },
  'node_modules/a': { version: '1.2.3', dependencies: { b: '^2.0.0' } },
  'node_modules/b': { version: '2.0.1' },
  'node_modules/a/node_modules/b': { version: '2.9.9' },
};

// Nested copies win over the hoisted one, and the walk falls back up the chain.
assert.equal(resolveEntry(packages, 'node_modules/a', 'b'), 'node_modules/a/node_modules/b');
assert.equal(resolveEntry(packages, 'node_modules/b', 'a'), 'node_modules/a');
assert.equal(resolveEntry(packages, '', 'a'), 'node_modules/a');
assert.equal(resolveEntry(packages, 'node_modules/a', 'missing'), null);

assert.deepEqual(findMissingEdges(JSON.stringify({ packages })), []);

// The regression this gate exists for: an optional platform package survives the prune but
// its dependency edges do not, which is exactly what `npm ci` refuses.
const pruned = {
  '': { name: 'root', dependencies: { oxide: '^4.0.0' } },
  'node_modules/oxide': { version: '4.3.3', optional: true },
  'node_modules/oxide-wasm32-wasi': {
    version: '4.3.3',
    optional: true,
    cpu: ['wasm32'],
    dependencies: { '@emnapi/core': '^1.11.1', '@emnapi/runtime': '^1.11.1' },
  },
};
assert.deepEqual(findMissingEdges(JSON.stringify({ packages: pruned })), [
  { from: 'node_modules/oxide-wasm32-wasi', name: '@emnapi/core', range: '^1.11.1' },
  { from: 'node_modules/oxide-wasm32-wasi', name: '@emnapi/runtime', range: '^1.11.1' },
]);

// A link entry (workspace symlink) carries no resolutions of its own.
assert.deepEqual(findMissingEdges(JSON.stringify({
  packages: { '': {}, 'node_modules/w': { link: true, dependencies: { gone: '^1.0.0' } } },
})), []);

// An out-of-range resolution is NOT a finding: npm `overrides` violate ranges by design.
assert.deepEqual(findMissingEdges(JSON.stringify({
  packages: { '': { dependencies: { a: '^9.0.0' } }, 'node_modules/a': { version: '1.0.0' } },
})), []);

assert.throws(
  () => findMissingEdges(JSON.stringify({ lockfileVersion: 1 }), 'legacy-lock.json'),
  /legacy-lock\.json has no packages object/,
);

console.log('test-check-lockfile-completeness: PASS');
