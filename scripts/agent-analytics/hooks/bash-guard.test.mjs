/**
 * Tempdoc 520 P0a/P0b/P0c — unit tests for bash-guard's decision logic.
 *
 * Exercises the pure `evaluateBashCommand(cmd, { isMain })` function (the I/O
 * wrapper `main()` is not invoked on import). Each P0 case is a defect the
 * fix closes; the regression cases assert the safety layers still fire.
 *
 * Run with: `node scripts/agent-analytics/hooks/bash-guard.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { evaluateBashCommand } from './bash-guard.mjs';

let passed = 0;
const failures = [];

/** Assert whether `cmd` is blocked under the given worktree context. */
function check(label, cmd, opts, expectBlock) {
  try {
    const verdict = evaluateBashCommand(cmd, opts);
    assert.equal(
      verdict.block,
      expectBlock,
      `${label}: expected block=${expectBlock} for \`${cmd}\` (isMain=${!!opts.isMain}), ` +
        `got block=${verdict.block}${verdict.layer ? ` [layer=${verdict.layer}]` : ''}`
    );
    passed += 1;
  } catch (e) {
    failures.push(e.message);
  }
}

const MAIN = { isMain: true };
const WT = { isMain: false };

// --- P0a: flagged file-reading commands are NOT blocked ---
check('P0a cat -n', 'cat -n file.txt', WT, false);
check('P0a head -n', 'head -n 50 file.log', WT, false);
check('P0a tail -n', 'tail -n 20 file.log', WT, false);
check('P0a grep -A', 'grep -A 3 "pattern" file.txt', WT, false);
check('P0a rg -i', 'rg -i needle src/app.ts', WT, false);
// Regression: bare (flagless) reads still redirect to Read/Grep.
check('P0a bare cat blocked', 'cat file.txt', WT, true);
check('P0a bare head blocked', 'head file.txt', WT, true);
check('P0a bare grep blocked', 'grep "pattern" file.txt', WT, true);

// --- P0b: chained commands (pipelines) bypass Layer 3 by design ---
check('P0b cat | head allowed', 'cat file.txt | head', WT, false);
check('P0b grep | wc allowed', 'grep x file.txt | wc -l', WT, false);
check('P0b cat > redirect allowed', 'cat tmpl.txt > out.txt', WT, false);
// Regression: git + sleep safety still fire even inside chains.
check('P0b force-push in chain still blocked', 'git push --force && echo done', WT, true);
check('P0b sleep in chain still blocked', 'sleep 5; echo hi', WT, true);

// --- P0c: single-file restore allowed; branch-switch / whole-tree blocked ---
check('P0c restore one file allowed (main)', 'git checkout -- README.md', MAIN, false);
check('P0c restore with ref allowed (main)', 'git checkout HEAD -- src/a.ts', MAIN, false);
check('P0c branch switch blocked (main)', 'git checkout main', MAIN, true);
check('P0c branch create blocked (main)', 'git checkout -b feature', MAIN, true);
check('P0c whole-tree restore blocked (main)', 'git checkout .', MAIN, true);
check('P0c whole-tree restore via -- blocked (main)', 'git checkout -- .', MAIN, true);
// In a worktree, checkout is unrestricted.
check('P0c checkout allowed in worktree', 'git checkout main', WT, false);

// --- Review C2: whole-tree checkout restores must NOT slip through the carve-out ---
check('C2 checkout -- ./ blocked (main)', 'git checkout -- ./', MAIN, true);
check('C2 checkout -- :/ blocked (main)', 'git checkout -- :/', MAIN, true);
check('C2 checkout -- * blocked (main)', 'git checkout -- *', MAIN, true);
check('C2 checkout -- src . (multi, whole-tree) blocked (main)', 'git checkout -- src .', MAIN, true);
check('C2 checkout HEAD -- specific path still allowed (main)', 'git checkout HEAD -- src/a.ts', MAIN, false);

// --- Review C1: git restore whole-tree with flags must be blocked ---
check('C1 restore --worktree . blocked (main)', 'git restore --worktree .', MAIN, true);
check('C1 restore -W . blocked (main)', 'git restore --staged --worktree .', MAIN, true);
check('C1 restore ./ blocked (main)', 'git restore ./', MAIN, true);
check('C1 restore specific path allowed (main)', 'git restore src/app.ts', MAIN, false);

// --- Review H1: +refspec force push blocked everywhere ---
check('H1 +refspec force push blocked', 'git push origin +HEAD:main', WT, true);
check('H1 +branch force push blocked', 'git push origin +master', MAIN, true);
check('H1 normal push allowed', 'git push origin HEAD:main', WT, false);

// --- Regression: existing safety guarantees intact ---
check('force push blocked everywhere (worktree)', 'git push --force', WT, true);
check('force push -f blocked', 'git push -f origin main', WT, true);
check('reset --hard blocked in main', 'git reset --hard origin/main', MAIN, true);
check('reset --hard allowed in worktree', 'git reset --hard origin/main', WT, false);
check('git clean -fd blocked in main', 'git clean -fd', MAIN, true);
check('git restore . blocked in main', 'git restore .', MAIN, true);
check('git switch blocked in main', 'git switch other', MAIN, true);
check('sleep 10 blocked', 'sleep 10', WT, true);
check('sleep 0.5 allowed (polling backoff)', 'sleep 0.5', WT, false);
check('git status allowed', 'git status', MAIN, false);
check('git commit allowed in main', 'git commit -m "msg"', MAIN, false);
check('npm build allowed', 'npm run build', MAIN, false);
check('empty command allowed', '', WT, false);

// --- Observation #32: force-push detection is quote-aware (no false positive on quoted DATA) ---
check('#32 force-push in double quotes not blocked', 'echo "git push --force"', WT, false);
check('#32 force-push in single quotes not blocked', "printf 'git push --force carefully'", WT, false);
check('#32 force-push in a commit message not blocked', 'git commit -m "reminder: never git push --force"', MAIN, false);
// Regression: a REAL force-push beside a quoted arg still blocks (the force-push itself is unquoted).
check('#32 real force-push beside quoted echo still blocked', 'git push --force && echo "done"', WT, true);
check('#32 bare real force-push still blocked', 'git push --force', WT, true);
// Escaped quotes inside a double-quoted string must still strip (this fix's own commit tripped this).
check('#32 force-push in escaped double-quotes not blocked', 'echo "he said \\"git push --force\\""', WT, false);

// --- Report ---
if (failures.length > 0) {
  console.error(`bash-guard.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`bash-guard.test: all ${passed} checks passed`);
