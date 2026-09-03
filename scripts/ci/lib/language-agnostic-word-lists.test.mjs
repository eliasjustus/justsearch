import assert from 'node:assert/strict';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { scanAuthoredWordLists } from './language-agnostic-word-lists.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const fixtureScope = resolve(
  here,
  '..',
  '_fixtures',
  'language-agnostic-analysis',
  'negative',
  'modules',
  'worker-services',
  'src',
  'main',
  'java',
);
const findings = scanAuthoredWordLists({
  scopes: [fixtureScope],
  minEntries: 6,
  naturalLanguageWordPattern: "^[\\p{L}][\\p{L}'’.-]*$",
});

assert.equal(findings.length, 1);
assert.match(findings[0], /SearchPlanner\.java/);
console.log('✓ language-agnostic word-list self-test: planner-scoped authored list is rejected.');
