#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const INSTALL_RE = /(?:^|[;&|]\s*|\s)npm\s+(?:ci|install)\b/i;
const EXPLICIT_POLICY_RE = /--audit(?:=|\s+)false\b/i;

function repoRootFrom(start) {
  for (let dir = path.resolve(start); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, 'settings.gradle.kts'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) throw new Error(`repository root not found from ${start}`);
  }
}

export function findImplicitWorkflowAudits(repoRoot) {
  const workflowDir = path.join(repoRoot, '.github', 'workflows');
  const findings = [];
  for (const name of fs.readdirSync(workflowDir).filter((entry) => /\.ya?ml$/i.test(entry)).sort()) {
    const rel = path.posix.join('.github', 'workflows', name);
    const lines = fs.readFileSync(path.join(workflowDir, name), 'utf8').split(/\r?\n/);
    for (let index = 0; index < lines.length; index += 1) {
      const text = lines[index];
      const trimmed = text.trimStart();
      const commandShaped = /^(?:-\s*)?run:\s*.*npm\s+/i.test(trimmed) ||
        /^(?:npm\s+|.*(?:&&|;)\s*npm\s+)/i.test(trimmed);
      if (trimmed.startsWith('#') || !commandShaped || !INSTALL_RE.test(text)) continue;
      if (!EXPLICIT_POLICY_RE.test(text)) findings.push({ path: rel, line: index + 1, text: text.trim() });
    }
  }
  return findings;
}

function main() {
  const repoRoot = repoRootFrom(path.dirname(fileURLToPath(import.meta.url)));
  const findings = findImplicitWorkflowAudits(repoRoot);
  if (findings.length === 0) {
    console.log('workflow-npm-audit-policy: OK — every workflow npm install declares --audit=false');
    return;
  }
  console.error('workflow-npm-audit-policy: FAIL — install-time audit transport is implicit:');
  for (const finding of findings) console.error(`- ${finding.path}:${finding.line}: ${finding.text}`);
  console.error('Use --audit=false; the npm-audit governance gate owns advisory evidence for production lockfiles.');
  process.exitCode = 1;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) main();
