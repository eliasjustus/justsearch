#!/usr/bin/env node
/**
 * Tempdoc 618 §2: make a git worktree dev-ready in one command.
 *
 * A worktree is a fresh checkout: it needs its own `node_modules` (for FE typecheck/test/Vite) and
 * its own installDist before the dev stack will start. What it does NOT need a setup step for:
 *   - models: the dev-runner resolves JUSTSEARCH_MODELS_DIR from the MAIN checkout (618 §2);
 *   - GPU runtime: the dev-runner resolves the shared cuda12 llama-server from the MAIN checkout
 *     (tempdoc 656 Move 2), so every worktree references one copy — no per-worktree download.
 *
 * GPU runtime is GPU-only by design (tempdoc 656): dev never provisions a CPU llama-server (a CPU
 * fallback runs the 9B model ~10x slower and saturates every core, DOSing concurrent worktrees). To
 * make the shared GPU runtime available, run ONCE at the MAIN checkout:
 *   ./gradlew :modules:ui:stageLlamaCudaVariant     # ~600 MB one-time download of the cuda12 build
 * After that the dev-runner auto-populates the shared native-bin and every worktree references it.
 * If it is absent, inference is cleanly unavailable (fails closed) — search still works.
 *
 * What it DOES need (post-cutover): .mcp.json and .claude/settings.local.json are gitignored
 * (maintainer-local, not tracked) — whether a fresh worktree starts with them depends on whether
 * your base checkout already had them, so don't rely on it. This script seeds both from their
 * committed `.example` files if missing (never overwrites an existing one) — fill in per-machine
 * values (github PAT, permissions/env) in the copies afterward. See MAINTAINING.md.
 *
 * Usage (run from inside the worktree):
 *   node scripts/dev/prepare-worktree.cjs            # npm ci + installDist
 *   node scripts/dev/prepare-worktree.cjs --no-dist  # FE-only (skip the Java dists)
 */
'use strict';
const fs = require('fs');
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

function seedFromExample(exampleRelPath, destRelPath) {
  const dest = path.join(repoRoot, destRelPath);
  const example = path.join(repoRoot, exampleRelPath);
  if (fs.existsSync(dest)) {
    console.error(`[prepare-worktree] ${destRelPath} already exists — leaving it as-is`);
    return;
  }
  if (!fs.existsSync(example)) {
    console.error(`[prepare-worktree] WARNING: ${exampleRelPath} not found — cannot seed ${destRelPath}`);
    return;
  }
  fs.copyFileSync(example, dest);
  console.error(`[prepare-worktree] seeded ${destRelPath} from ${exampleRelPath}`);
}

// 0. Seed maintainer-local config (gitignored; a fresh worktree may or may not start with it).
seedFromExample('.mcp.json.example', '.mcp.json');
seedFromExample(path.join('.claude', 'settings.local.json.example'), path.join('.claude', 'settings.local.json'));
console.error('[prepare-worktree] if .mcp.json was just created: the justsearch-dev server needs no secret and works immediately; set GITHUB_PERSONAL_ACCESS_TOKEN in it only if you want the github MCP server too.');

// 1. FE deps — npm ci (clean, lockfile-pinned; same command the build task now uses).
run(npm, ['ci'], path.join(repoRoot, 'modules', 'ui-web'));

// 2. Dists the dev stack launches from (skippable for FE-only work).
if (!noDist) {
  run(gradle, [':modules:ui:installDist', ':modules:indexer-worker:installDist'], repoRoot);
}

console.error('[prepare-worktree] done — models + the shared cuda12 GPU runtime resolve automatically via the dev-runner (run `./gradlew :modules:ui:stageLlamaCudaVariant` once at the main checkout to provision the GPU runtime; GPU-only by design — tempdoc 656).');
