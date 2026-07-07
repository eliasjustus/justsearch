#!/usr/bin/env node
/**
 * Tempdoc 684: unit test for remove-worktree.cjs's deleteTree — the broken
 * `\\?\` long-path fallback (path-syntax throw on a non-absolute /
 * forward-slashed path) and the missing held-handle retry/report.
 *
 * Run with: node scripts/dev/test-remove-worktree.cjs
 * Exits non-zero on any failure.
 */
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn } = require('child_process');

let passed = 0;
const failures = [];
function check(label, condition) {
  if (condition) {
    passed += 1;
  } else {
    failures.push(label);
  }
}

async function main() {
  // Requiring the module must not trigger removal (require.main guard).
  const mod = require('./remove-worktree.cjs');
  check('module exports deleteTree', typeof mod.deleteTree === 'function');

  if (process.platform !== 'win32') {
    console.log('test-remove-worktree: skipping held-handle assertions on non-win32');
    console.log(`test-remove-worktree: ${passed} passed`);
    return;
  }

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'rm-wt-test-'));
  fs.writeFileSync(path.join(tmpDir, 'marker.txt'), 'hold me');

  // Spawn a child whose cwd is inside tmpDir to hold a directory handle.
  const child = spawn(process.execPath, ['-e', 'setTimeout(() => {}, 5000)'], {
    cwd: tmpDir,
    stdio: 'ignore',
  });

  // Give the child a moment to actually start and hold the cwd handle.
  await new Promise((resolve) => setTimeout(resolve, 500));

  let firstResult;
  let threw = false;
  try {
    firstResult = mod.deleteTree(tmpDir, { attempts: 2, retryDelayMs: 150 });
  } catch (err) {
    threw = true;
    console.error(`deleteTree threw unexpectedly: ${err.message}`);
  }

  check('deleteTree does not throw a path-syntax error on a held handle', !threw);
  // With a held cwd handle, Node's rmSync ultimately fails on Windows (EBUSY/EPERM)
  // and/or the directory is still present because the process holds its cwd there.
  check(
    'deleteTree returns a boolean (false or true) rather than throwing, while held',
    typeof firstResult === 'boolean',
  );

  // Kill the holder, then retry — should now succeed via bounded retry or plain rmSync.
  child.kill();
  await new Promise((resolve) => setTimeout(resolve, 500));

  const secondResult = mod.deleteTree(tmpDir, { attempts: 5, retryDelayMs: 200 });
  check('deleteTree succeeds once the holder is gone', secondResult === true);
  check('directory no longer exists after successful deleteTree', !fs.existsSync(tmpDir));

  // Cleanup safety net in case the assertion above already proved it's gone.
  if (fs.existsSync(tmpDir)) {
    try {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    } catch { /* best-effort */ }
  }

  if (failures.length) {
    console.error(`test-remove-worktree: ${failures.length} FAILED / ${passed} passed`);
    for (const f of failures) console.error('  ✗ ' + f);
    console.log('test-remove-worktree: FAIL');
    process.exit(1);
  }
  console.log(`test-remove-worktree: ${passed} passed`);
  console.log('test-remove-worktree: PASS');
}

main().catch((err) => {
  console.error(err);
  console.log('test-remove-worktree: FAIL');
  process.exit(1);
});
