#!/usr/bin/env node
/**
 * check-installer-execution-level.mjs (D3, tempdoc 728-followup)
 *
 * Asserts the NSIS installer is configured as a PER-USER install
 * (`bundle.windows.nsis.installMode === "currentUser"` in tauri.conf.json),
 * which is what makes the install run without requesting Windows elevation
 * (no UAC prompt) — per ADR-0024.
 *
 * Why this exists: a Sandbox round's UAC observation is operator-only and
 * unreliable when the round's own terminal is already elevated (no UAC
 * prompt can appear at all in that case, regardless of what the installer
 * requests — see sandbox-CLAUDE.md's UAC protocol and
 * collect-evidence.ps1's elevation self-check). This script instead verifies
 * the STRUCTURAL fact mechanically, on every CI run, independent of any
 * sandbox's elevation state.
 *
 * SCOPE — do not oversell this: it proves ONLY that the installer does not
 * request elevation. It says NOTHING about unsigned-publisher warnings or
 * SmartScreen — those depend on code signing (deferred by owner budget, see
 * the comment at scripts/sandbox/sandbox-launch.py's generate_wsb, ~line
 * 507-517) and remain the Sandbox's job to observe whenever elevation
 * posture allows it.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

function repoRootFromCwd() {
  const markers = ['settings.gradle.kts', 'build.gradle.kts', '.git'];
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (markers.some((marker) => fs.existsSync(path.join(dir, marker)))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) break;
  }
  return process.cwd();
}

const CONFIG_REL = path.join('modules', 'shell', 'src-tauri', 'tauri.conf.json');
const EXPECTED_INSTALL_MODE = 'currentUser';

export function checkInstallerExecutionLevel(repoRoot) {
  const configPath = path.join(repoRoot, CONFIG_REL);
  if (!fs.existsSync(configPath)) {
    return { ok: false, message: `${CONFIG_REL} not found at ${configPath}` };
  }

  let config;
  try {
    config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  } catch (err) {
    return { ok: false, message: `${CONFIG_REL} is not valid JSON: ${err.message}` };
  }

  const installMode = config?.bundle?.windows?.nsis?.installMode;
  if (installMode !== EXPECTED_INSTALL_MODE) {
    return {
      ok: false,
      message:
        `${CONFIG_REL}: bundle.windows.nsis.installMode is ${JSON.stringify(installMode)}, ` +
        `expected ${JSON.stringify(EXPECTED_INSTALL_MODE)}. A non-"currentUser" install mode ` +
        '(e.g. "perMachine") makes the NSIS installer request Windows elevation (a UAC prompt) ' +
        'on every install — per ADR-0024 the installer must be per-user.',
    };
  }

  return {
    ok: true,
    message: `${CONFIG_REL}: bundle.windows.nsis.installMode is "currentUser" — the installer does not request elevation.`,
  };
}

const SCOPE_NOTE =
  'SCOPE: this proves only that the installer does not request elevation. It says nothing ' +
  'about unsigned-publisher warnings or SmartScreen, which depend on code signing and remain ' +
  "the Sandbox round's job to observe whenever elevation posture allows it.";

function main() {
  const repoRoot = repoRootFromCwd();
  const result = checkInstallerExecutionLevel(repoRoot);

  if (result.ok) {
    console.log(`check-installer-execution-level: OK — ${result.message}`);
    console.log(SCOPE_NOTE);
    return;
  }

  console.error('check-installer-execution-level: FAIL');
  console.error(`- ${result.message}`);
  console.error(SCOPE_NOTE);
  process.exitCode = 1;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
