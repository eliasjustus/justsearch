import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { findImplicitWorkflowAudits } from './check-workflow-npm-audit-policy.mjs';

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workflow-npm-audit-policy-'));
fs.writeFileSync(path.join(root, 'settings.gradle.kts'), '', 'utf8');
fs.mkdirSync(path.join(root, '.github', 'workflows'), { recursive: true });
fs.writeFileSync(path.join(root, '.github', 'workflows', 'good.yml'), [
  'jobs:',
  '  test:',
  '    steps:',
  '      # npm ci in a comment is not a command',
  '      - name: Workflow npm install audit policy guard',
  '      - run: npm ci --audit=false',
  '      - run: npm install --global example --audit false',
  '',
].join('\n'), 'utf8');
assert.deepEqual(findImplicitWorkflowAudits(root), []);

fs.writeFileSync(path.join(root, '.github', 'workflows', 'bad.yaml'), [
  'jobs:',
  '  test:',
  '    steps:',
  '      - run: npm ci --ignore-scripts',
  '      - run: cd nested && npm install',
  '',
].join('\n'), 'utf8');
assert.deepEqual(findImplicitWorkflowAudits(root), [
  { path: '.github/workflows/bad.yaml', line: 4, text: '- run: npm ci --ignore-scripts' },
  { path: '.github/workflows/bad.yaml', line: 5, text: '- run: cd nested && npm install' },
]);

console.log('test-check-workflow-npm-audit-policy: PASS');
