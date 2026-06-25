#!/usr/bin/env node

/**
 * SessionEnd hook — kills any ui-shot Vite dev server started for this worktree.
 *
 * Reads tmp/ui-shot-server.json, checks if the PID is alive, kills it.
 * Async, best-effort — never blocks session teardown.
 */

import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const scriptDir = process.platform === 'win32'
  ? SCRIPT_DIR.replace(/^\/([A-Za-z]:)/, '$1')
  : SCRIPT_DIR;
const repoRoot = path.resolve(scriptDir, '..', '..', '..');

function killProcess(pid) {
  try {
    if (process.platform === 'win32') {
      // On Windows, SIGTERM doesn't work for detached processes.
      // taskkill /T kills the process tree (Vite spawns child processes).
      execSync(`taskkill /F /T /PID ${pid}`, { stdio: 'ignore' });
    } else {
      process.kill(pid, 'SIGTERM');
    }
  } catch {
    // Already dead — fine
  }
}

// ui-shot writes the PID file relative to the jseval CWD, which is the
// repo root (or worktree root).  Check both locations.
const candidates = [
  path.join(repoRoot, 'tmp', 'ui-shot-server.json'),
  path.join(repoRoot, 'scripts', 'jseval', 'tmp', 'ui-shot-server.json'),
];

for (const serverFile of candidates) {
  try {
    const info = JSON.parse(fs.readFileSync(serverFile, 'utf8'));
    const pid = info?.pid;
    if (!pid) continue;

    killProcess(pid);
    fs.unlinkSync(serverFile);
  } catch {
    // File doesn't exist or can't be read — nothing to clean up
  }
}
