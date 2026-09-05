#!/usr/bin/env node

/** Generate the tracked public Claude hook projection from agent-hooks.v1.json. */

import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

import {
  LOCAL_EXAMPLE_OUT,
  PUBLIC_BASE,
  readManifest,
  renderLocalExample,
  renderPublicTemplate,
} from './gen-agent-hooks-wiring.mjs';

export function generatePublicAgentHooks({ check = false } = {}) {
  const manifest = readManifest();
  const expectedPublic = renderPublicTemplate(manifest);
  const expectedLocalExample = renderLocalExample(manifest);
  const currentPublic = readFileSync(PUBLIC_BASE, 'utf8');
  const currentLocalExample = readFileSync(LOCAL_EXAMPLE_OUT, 'utf8');
  if (check) {
    const drifted = [];
    if (currentPublic !== expectedPublic) drifted.push('.claude/settings.json');
    if (currentLocalExample !== expectedLocalExample) drifted.push('.claude/settings.local.json.example');
    if (drifted.length > 0) {
      process.stderr.write(`gen-agent-hooks --check: ${drifted.join(', ')} missing or drifted\n`);
      return 1;
    }
    process.stdout.write('gen-agent-hooks --check: OK\n');
    return 0;
  }
  writeFileSync(PUBLIC_BASE, expectedPublic, 'utf8');
  writeFileSync(LOCAL_EXAMPLE_OUT, expectedLocalExample, 'utf8');
  process.stdout.write('gen-agent-hooks: wrote .claude/settings.json and .claude/settings.local.json.example\n');
  return 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exit(generatePublicAgentHooks({ check: process.argv.includes('--check') }));
}
