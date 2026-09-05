#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import { directPublicationMerge } from './publication-merge-guard.mjs';

for (const command of [
  'gh pr merge 933',
  'gh.exe pr merge 933',
  'gh --repo justsearch-app/justsearch pr merge 933',
  'gh pr --repo justsearch-app/justsearch merge 933',
  'gh -R justsearch-app/justsearch pr merge 933',
  'gh -Rjustsearch-app/justsearch pr merge 933',
  '& "F:\\scoop\\apps\\gh\\current\\bin\\gh.exe" pr merge 933',
  'node scripts/dev/run-gh.mjs pr merge 933',
  'node --no-warnings scripts/dev/run-gh.mjs pr merge 933',
  'node --require node:fs scripts/dev/run-gh.mjs pr merge 933',
  'node --experimental-loader node:fs scripts/dev/run-gh.mjs pr merge 933',
  'node -rnode:fs scripts/dev/run-gh.mjs pr merge 933',
  'node -- scripts/dev/run-gh.mjs pr merge 933',
  'node.exe "F:\\repo\\scripts\\dev\\run-gh.mjs" pr merge 933',
  'git status && gh pr merge 933',
  'Write-Output ready | gh pr merge 933',
]) assert.equal(directPublicationMerge(command), true, command);

for (const command of [
  '',
  'gh pr view 933',
  'gh api repos/justsearch-app/justsearch/pulls/933',
  'gh pr merge --help',
  'gh --help pr merge 933',
  'node -p scripts/dev/run-gh.mjs pr merge 933',
  'node --eval scripts/dev/run-gh.mjs pr merge 933',
  'node scripts/dev/run-gh.mjs enqueue 933',
  'node scripts/dev/run-gh.mjs merge-wait 933',
  'Write-Output "gh pr merge 933"',
  'echo \'node scripts/dev/run-gh.mjs pr merge 933\'',
  '# gh pr merge 933',
]) assert.equal(directPublicationMerge(command), false, command);

const script = fileURLToPath(new URL('./publication-merge-guard.mjs', import.meta.url));
const blocked = spawnSync(process.execPath, [script], {
  encoding: 'utf8',
  input: JSON.stringify({
    hook_event_name: 'PreToolUse',
    tool_name: 'Bash',
    tool_input: { command: 'gh pr merge 933' },
  }),
});
assert.equal(blocked.status, 2);
assert.match(blocked.stderr, /run-gh\.mjs enqueue/);

const allowed = spawnSync(process.execPath, [script], {
  encoding: 'utf8',
  input: JSON.stringify({
    hook_event_name: 'PreToolUse',
    tool_name: 'Bash',
    tool_input: { command: 'node scripts/dev/run-gh.mjs enqueue 933' },
  }),
});
assert.equal(allowed.status, 0);
assert.equal(allowed.stderr, '');

console.log('publication-merge-guard.test: PASS');
