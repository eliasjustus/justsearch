#!/usr/bin/env node
/**
 * Enforce ADR-0026: GitHub workflows are manually dispatched only.
 *
 * This intentionally uses a small line scanner instead of a YAML parser so it
 * can run before npm install and inside lightweight local checks.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const REQUIRED_EVENT = 'workflow_dispatch';

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

function scanWorkflow(file, repoRoot) {
  const rel = normalizeRel(path.relative(repoRoot, file));
  const errors = [];
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
        continue;
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

  if (!sawOn) {
    errors.push({ rel, lineNumber: 1, message: 'missing top-level on: block' });
  }
  if (!events.has(REQUIRED_EVENT)) {
    errors.push({ rel, lineNumber: 1, message: `missing required trigger: ${REQUIRED_EVENT}` });
  }
  for (const [event, lineNumber] of events) {
    if (event !== REQUIRED_EVENT) {
      errors.push({ rel, lineNumber, message: `unexpected trigger: ${event}` });
    }
  }

  return errors;
}

function main() {
  const repoRoot = repoRootFromCwd();
  const workflowsDir = path.join(repoRoot, '.github', 'workflows');
  if (!fs.existsSync(workflowsDir)) {
    console.log('check-workflow-triggers: OK (no .github/workflows directory)');
    return;
  }

  const files = fs
    .readdirSync(workflowsDir)
    .filter((name) => /\.(ya?ml)$/i.test(name))
    .map((name) => path.join(workflowsDir, name))
    .sort((a, b) => a.localeCompare(b));

  const errors = files.flatMap((file) => scanWorkflow(file, repoRoot));
  if (errors.length === 0) {
    console.log(`check-workflow-triggers: OK (workflows=${files.length})`);
    return;
  }

  console.error('check-workflow-triggers: FAIL');
  console.error('ADR-0026 requires workflow_dispatch-only GitHub workflows.');
  for (const error of errors) {
    console.error(`- ${error.rel}:${error.lineNumber} ${error.message}`);
  }
  process.exitCode = 1;
}

main();
