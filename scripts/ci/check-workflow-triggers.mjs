#!/usr/bin/env node
/**
 * Validate GitHub workflow triggers against workflow-signal-policy.v1.json.
 *
 * This intentionally uses a small line scanner instead of a YAML parser so it
 * can run before npm install and inside lightweight local checks.
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

function normalizeRel(p) {
  return p.replaceAll('\\', '/');
}

function stripOuterQuotes(value) {
  const trimmed = value.trim();
  if (
    (trimmed.startsWith("'") && trimmed.endsWith("'")) ||
    (trimmed.startsWith('"') && trimmed.endsWith('"'))
  ) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
}

function stripInlineComment(line) {
  let quote = null;
  for (let i = 0; i < line.length; i += 1) {
    const ch = line[i];
    if ((ch === '"' || ch === "'") && line[i - 1] !== '\\') {
      quote = quote === ch ? null : quote ?? ch;
    }
    if (ch === '#' && quote === null) {
      return line.slice(0, i);
    }
  }
  return line;
}

function leadingSpaces(line) {
  const match = /^ */.exec(line);
  return match ? match[0].length : 0;
}

function parseMappingEntry(trimmed) {
  const match = /^(['"]?)([A-Za-z_][\w-]*)\1\s*:\s*(.*)$/.exec(trimmed);
  return match ? { key: match[2], rest: match[3] } : null;
}

function parseInlineEvents(rest) {
  const events = [];
  const trimmed = rest.trim();
  if (!trimmed) return events;
  if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
    for (const item of trimmed.slice(1, -1).split(',')) {
      events.push(stripOuterQuotes(item));
    }
    return events;
  }
  if (trimmed.startsWith('{') && trimmed.endsWith('}')) {
    for (const item of trimmed.slice(1, -1).split(',')) {
      const key = parseMappingEntry(item.trim())?.key;
      if (key) events.push(key);
    }
    return events;
  }
  events.push(stripOuterQuotes(trimmed));
  return events;
}

export function scanWorkflow(file, repoRoot) {
  const rel = normalizeRel(path.relative(repoRoot, file));
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  let inOnBlock = false;
  let onIndent = -1;
  let sawOn = false;
  const events = new Map();

  function addEvent(event, lineNumber) {
    const normalized = stripOuterQuotes(event).trim();
    if (!normalized) return;
    if (!events.has(normalized)) events.set(normalized, lineNumber);
  }

  for (let i = 0; i < lines.length; i += 1) {
    const lineNumber = i + 1;
    const noComment = stripInlineComment(lines[i]).trimEnd();
    if (!noComment.trim()) continue;

    const indent = leadingSpaces(noComment);
    const trimmed = noComment.trim();

    if (inOnBlock && indent <= onIndent) {
      inOnBlock = false;
      onIndent = -1;
    }

    if (indent === 0) {
      const topLevelEntry = parseMappingEntry(trimmed);
      if (topLevelEntry?.key === 'on') {
        sawOn = true;
        onIndent = indent;
        const inlineEvents = parseInlineEvents(topLevelEntry.rest);
        inOnBlock = inlineEvents.length === 0;
        for (const event of inlineEvents) addEvent(event, lineNumber);
      }
      continue;
    }

    if (inOnBlock) {
      const listMatch = /^-\s*(['"]?)([A-Za-z_][\w-]*)\1\s*$/.exec(trimmed);
      if (listMatch && indent === onIndent + 2) {
        addEvent(listMatch[2], lineNumber);
        continue;
      }

      const entry = parseMappingEntry(trimmed);
      if (indent === onIndent + 2 && entry?.key) {
        addEvent(entry.key, lineNumber);
      }
    }
  }

  return { rel, sawOn, events };
}

function workflowEntries(policy) {
  return (policy.workflows || []).filter(
    (entry) => typeof entry.path === 'string' && normalizeRel(entry.path).startsWith('.github/workflows/')
  );
}

export function validateWorkflows({ repoRoot, policy }) {
  const workflowsDir = path.join(repoRoot, '.github', 'workflows');
  const errors = [];
  if (!fs.existsSync(workflowsDir)) return errors;

  const policyByPath = new Map();
  for (const entry of workflowEntries(policy)) {
    const rel = normalizeRel(entry.path);
    if (policyByPath.has(rel)) {
      errors.push({ rel, lineNumber: 1, message: `duplicate workflow policy entry for ${rel}` });
      continue;
    }
    policyByPath.set(rel, entry);
  }

  const files = fs
    .readdirSync(workflowsDir)
    .filter((name) => /\.(ya?ml)$/i.test(name))
    .map((name) => path.join(workflowsDir, name))
    .sort((a, b) => a.localeCompare(b));

  const seen = new Set();
  for (const file of files) {
    const scanned = scanWorkflow(file, repoRoot);
    seen.add(scanned.rel);
    const policyEntry = policyByPath.get(scanned.rel);
    if (!policyEntry) {
      errors.push({ rel: scanned.rel, lineNumber: 1, message: 'workflow file is missing from workflow-signal-policy.v1.json' });
      continue;
    }

    if (!scanned.sawOn) {
      errors.push({ rel: scanned.rel, lineNumber: 1, message: 'missing top-level on: block' });
      continue;
    }

    const expected = new Set(policyEntry.expectedTriggers || []);
    for (const event of expected) {
      if (!scanned.events.has(event)) {
        errors.push({ rel: scanned.rel, lineNumber: 1, message: `missing expected trigger from policy: ${event}` });
      }
    }
    for (const [event, lineNumber] of scanned.events) {
      if (!expected.has(event)) {
        errors.push({ rel: scanned.rel, lineNumber, message: `unexpected trigger not declared in policy: ${event}` });
      }
    }
  }

  for (const [rel, entry] of policyByPath) {
    if (!seen.has(rel)) {
      errors.push({ rel, lineNumber: 1, message: `policy entry for ${entry.name || rel} points at a missing workflow file` });
    }
  }

  return errors;
}

function loadPolicy(repoRoot) {
  const policyPath = path.join(repoRoot, 'scripts', 'ci', 'workflow-signal-policy.v1.json');
  return JSON.parse(fs.readFileSync(policyPath, 'utf8'));
}

function main() {
  const repoRoot = repoRootFromCwd();
  const errors = validateWorkflows({ repoRoot, policy: loadPolicy(repoRoot) });
  if (errors.length === 0) {
    console.log('check-workflow-triggers: OK (workflow triggers match workflow-signal-policy.v1.json)');
    return;
  }

  console.error('check-workflow-triggers: FAIL');
  console.error('Workflow triggers must match scripts/ci/workflow-signal-policy.v1.json.');
  for (const error of errors) {
    console.error(`- ${error.rel}:${error.lineNumber} ${error.message}`);
  }
  process.exitCode = 1;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
