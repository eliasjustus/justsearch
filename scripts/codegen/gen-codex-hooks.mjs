#!/usr/bin/env node
/** Generate the project Codex hook file from the shared hook manifest. */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..', '..');
export const MANIFEST = path.join(REPO_ROOT, 'governance', 'agent-hooks.v1.json');
export const OUTPUT = path.join(REPO_ROOT, '.codex', 'hooks.json');

export const CODEX_SUPPORTED_EVENTS = new Set([
  'SessionStart',
  'SessionEnd',
  'PreToolUse',
  'PostToolUse',
  'PreCompact',
  'SubagentStart',
  'SubagentStop',
  'UserPromptSubmit',
  'Stop',
]);

const POSIX_COMMAND = 'node "$(git rev-parse --show-toplevel)/scripts/agent-analytics/hooks/codex-hook-adapter.mjs"';
const WINDOWS_COMMAND = 'node "$(git rev-parse --show-toplevel)/scripts/agent-analytics/hooks/codex-hook-adapter.mjs"';

export function renderCodexHooks(manifest) {
  if (manifest.kind !== 'agent-hooks-manifest.v1') throw new Error(`unexpected manifest kind: ${manifest.kind}`);
  const hooks = {};
  for (const event of Object.keys(manifest.bindings ?? {})) {
    if (!CODEX_SUPPORTED_EVENTS.has(event)) continue;
    hooks[event] = [{
      hooks: [{
        type: 'command',
        command: POSIX_COMMAND,
        commandWindows: WINDOWS_COMMAND,
        timeout: event === 'PreCompact' ? 90 : 60,
        statusMessage: `Applying JustSearch ${event} policy`,
      }],
    }];
  }
  return JSON.stringify({ hooks }, null, 2) + '\n';
}

function main() {
  const manifest = JSON.parse(fs.readFileSync(MANIFEST, 'utf8'));
  const expected = renderCodexHooks(manifest);
  if (process.argv.includes('--check')) {
    const actual = fs.existsSync(OUTPUT) ? fs.readFileSync(OUTPUT, 'utf8').replace(/\r\n/g, '\n') : null;
    if (actual !== expected) {
      console.error('gen-codex-hooks --check: .codex/hooks.json is missing or drifted');
      process.exit(1);
    }
    console.log('gen-codex-hooks --check: OK');
    return;
  }
  fs.mkdirSync(path.dirname(OUTPUT), { recursive: true });
  fs.writeFileSync(OUTPUT, expected, 'utf8');
  console.log('gen-codex-hooks: wrote .codex/hooks.json');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
