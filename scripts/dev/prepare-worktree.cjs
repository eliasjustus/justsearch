#!/usr/bin/env node
/**
 * Tempdoc 618 §2: make a git worktree dev-ready in one command.
 *
 * A worktree is a fresh checkout: it needs its own `node_modules` (for FE typecheck/test/Vite) and
 * its own installDist before the dev stack will start. What it does NOT need a setup step for:
 *   - models: the dev-runner resolves JUSTSEARCH_MODELS_DIR from the MAIN checkout (618 §2);
 *   - llama-server: the dev-runner auto-stages it into native-bin on start (618 §3);
 *   - config: .mcp.json / .claude/settings.local.json are tracked, so every checkout already has
 *     them (no .worktreeinclude needed for this repo).
 *
 * Usage (run from inside the worktree):
 *   node scripts/dev/prepare-worktree.cjs            # npm ci + installDist
 *   node scripts/dev/prepare-worktree.cjs --no-dist  # FE-only (skip the Java dists)
 */
'use strict';
const path = require('path');
const { spawnSync } = require('child_process');

const repoRoot = path.resolve(__dirname, '..', '..');
const noDist = process.argv.includes('--no-dist');
const isWin = process.platform === 'win32';
const npm = isWin ? 'npm.cmd' : 'npm';
const gradle = isWin ? 'gradlew.bat' : './gradlew';

function run(cmd, args, cwd) {
  console.error(`[prepare-worktree] $ ${cmd} ${args.join(' ')}  (cwd: ${cwd})`);
  const r = spawnSync(cmd, args, { cwd, stdio: 'inherit', shell: true });
  if (r.status !== 0) {
    console.error(`[prepare-worktree] FAILED (${r.status}): ${cmd} ${args.join(' ')}`);
    process.exit(r.status || 1);
  }
}

// 1. FE deps — npm ci (clean, lockfile-pinned; same command the build task now uses).
run(npm, ['ci'], path.join(repoRoot, 'modules', 'ui-web'));

// 2. Dists the dev stack launches from (skippable for FE-only work).
if (!noDist) {
  run(gradle, [':modules:ui:installDist', ':modules:indexer-worker:installDist'], repoRoot);
}

console.error('[prepare-worktree] done — models + llama-server resolve automatically via the dev-runner.');
