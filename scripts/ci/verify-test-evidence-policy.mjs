#!/usr/bin/env node
/**
 * Verify ownership metadata for non-default test evidence.
 *
 * Stress tags remain governed by verify-stress-suite-policy.mjs. This policy
 * covers CI-skipped test sites and non-stress JUnit tags so broad unit-test CI
 * skips do not become anonymous coverage gaps.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const KIND = 'justsearch-test-evidence-policy.v1';
const REQUIRED_SKIP_FIELDS = ['path', 'element', 'tier', 'owner', 'reason', 'replacementEvidence', 'cadence'];
const REQUIRED_TAG_FIELDS = ['tag', 'tier', 'owner', 'reason', 'cadence'];

function repoRootFromCwd() {
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, 'settings.gradle.kts'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
  }
}

function parseArgs(argv) {
  const out = {
    root: null,
    policy: null,
    json: false,
    help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--root' && argv[i + 1]) out.root = argv[++i];
    else if (arg === '--policy' && argv[i + 1]) out.policy = argv[++i];
    else if (arg === '--json') out.json = true;
    else if (arg === '--help' || arg === '-h') out.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }
  return out;
}

function usage() {
  return [
    'Usage: node scripts/ci/verify-test-evidence-policy.mjs [--json] [--root DIR] [--policy FILE]',
    '',
    'Verifies CI-skipped Java tests and non-stress @Tag values against',
    'scripts/ci/test-evidence-policy.v1.json.',
  ].join('\n');
}

function normalizeRel(root, absPath) {
  return path.relative(root, absPath).split(path.sep).join('/');
}

function walkJava(root, dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const abs = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'build' || entry.name === '.gradle' || entry.name === 'target') continue;
      walkJava(root, abs, out);
    } else if (entry.isFile() && entry.name.endsWith('.java')) {
      const rel = normalizeRel(root, abs);
      if (rel.startsWith('modules/') && rel.includes('/src/')) out.push(abs);
    }
  }
  return out;
}

function readJavaString(text, start) {
  let i = start + 1;
  let value = '';
  while (i < text.length) {
    const ch = text[i];
    if (ch === '\\') {
      i += 2;
      continue;
    }
    if (ch === '"') return { value, end: i + 1 };
    value += ch;
    i += 1;
  }
  return null;
}

function stripCommentsAndStrings(text) {
  let i = 0;
  let mode = 'code';
  let out = '';
  while (i < text.length) {
    const ch = text[i];
    const next = text[i + 1];
    if (mode === 'code') {
      if (ch === '/' && next === '/') {
        mode = 'line';
        out += '  ';
        i += 2;
      } else if (ch === '/' && next === '*') {
        mode = 'block';
        out += '  ';
        i += 2;
      } else if (ch === '"') {
        const str = readJavaString(text, i);
        const end = str?.end ?? i + 1;
        out += ' '.repeat(end - i);
        i = end;
      } else if (ch === "'") {
        const start = i;
        i += 1;
        while (i < text.length) {
          if (text[i] === '\\') i += 2;
          else if (text[i] === "'") {
            i += 1;
            break;
          } else i += 1;
        }
        out += ' '.repeat(i - start);
      } else {
        out += ch;
        i += 1;
      }
    } else if (mode === 'line') {
      if (ch === '\n' || ch === '\r') {
        mode = 'code';
        out += ch;
      } else {
        out += ' ';
      }
      i += 1;
    } else if (mode === 'block') {
      if (ch === '*' && next === '/') {
        mode = 'code';
        out += '  ';
        i += 2;
      } else {
        out += ch === '\n' || ch === '\r' ? ch : ' ';
        i += 1;
      }
    }
  }
  return out;
}

function discoverTagsInJava(text) {
  const tags = [];
  let i = 0;
  let mode = 'code';
  while (i < text.length) {
    const ch = text[i];
    const next = text[i + 1];
    if (mode === 'code') {
      if (ch === '/' && next === '/') {
        mode = 'line';
        i += 2;
      } else if (ch === '/' && next === '*') {
        mode = 'block';
        i += 2;
      } else if (ch === '"') {
        const str = readJavaString(text, i);
        i = str?.end ?? i + 1;
      } else if (ch === "'") {
        i += 1;
        while (i < text.length) {
          if (text[i] === '\\') i += 2;
          else if (text[i] === "'") {
            i += 1;
            break;
          } else i += 1;
        }
      } else if (text.startsWith('@Tag', i)) {
        const start = i;
        let j = i + '@Tag'.length;
        while (/\s/.test(text[j] || '')) j += 1;
        if (text[j] !== '(') {
          i += 1;
          continue;
        }
        j += 1;
        while (/\s/.test(text[j] || '')) j += 1;
        if (text[j] !== '"') {
          i += 1;
          continue;
        }
        const str = readJavaString(text, j);
        if (str) tags.push({ tag: str.value, index: start, end: str.end });
        i = str?.end ?? i + 1;
      } else {
        i += 1;
      }
    } else if (mode === 'line') {
      if (ch === '\n' || ch === '\r') mode = 'code';
      i += 1;
    } else if (mode === 'block') {
      if (ch === '*' && next === '/') {
        mode = 'code';
        i += 2;
      } else {
        i += 1;
      }
    }
  }
  return tags;
}

function lineNumberAt(text, index) {
  let line = 1;
  for (let i = 0; i < index; i += 1) {
    if (text[i] === '\n') line += 1;
  }
  return line;
}

function elementAfter(cleanText, index) {
  const tail = cleanText.slice(index);
  const match = /(?:^|[\r\n])\s*(?:(?:@\w+(?:\([^)]*\))?\s*)|(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|default|synchronized)\s+))*([\w<>\[\], ? extends super.&]+?\s+)?(class|interface|enum|record|void|[\w<>[\], ? extends super.&]+)\s+([A-Za-z_]\w*)\s*(?:[({]|extends|implements)/s.exec(tail);
  if (!match) return '<unknown>';
  const kind = match[2];
  const name = match[3];
  return ['class', 'interface', 'enum', 'record'].includes(kind) ? `${kind} ${name}` : `method ${name}`;
}

function disabledAnnotationIsCiTrue(annotation) {
  return /@DisabledIfEnvironmentVariable\b/.test(annotation) &&
    /named\s*=\s*"CI"/.test(annotation) &&
    /matches\s*=\s*"true"/.test(annotation);
}

export function discoverEvidenceSubjects(root) {
  const ciSkippedSites = [];
  const tags = [];
  const modulesDir = path.join(root, 'modules');
  for (const abs of walkJava(root, modulesDir)) {
    const raw = fs.readFileSync(abs, 'utf8');
    const clean = stripCommentsAndStrings(raw);
    const rel = normalizeRel(root, abs);

    for (const match of raw.matchAll(/@DisabledIfEnvironmentVariable\s*\(([^)]*)\)/g)) {
      if (!disabledAnnotationIsCiTrue(match[0])) continue;
      ciSkippedSites.push({
        path: rel,
        line: lineNumberAt(raw, match.index),
        element: elementAfter(clean, match.index + match[0].length),
      });
    }

    for (const match of discoverTagsInJava(raw)) {
      tags.push({
        path: rel,
        line: lineNumberAt(raw, match.index),
        tag: match.tag,
        element: elementAfter(clean, match.end),
      });
    }
  }
  ciSkippedSites.sort((a, b) => a.path.localeCompare(b.path) || a.line - b.line);
  tags.sort((a, b) => a.tag.localeCompare(b.tag) || a.path.localeCompare(b.path) || a.line - b.line);
  return { ciSkippedSites, tags };
}

function missingFields(entry, fields) {
  return fields.filter((field) => typeof entry[field] !== 'string' || entry[field].trim() === '');
}

export function validateTestEvidencePolicy(policy, discovered) {
  const issues = [];
  if (policy.kind !== KIND) issues.push(`policy kind must be ${KIND}, got ${policy.kind}`);
  if (policy.version !== 1) issues.push(`policy version must be 1, got ${policy.version}`);
  if (!Array.isArray(policy.ciSkippedSites)) issues.push('policy ciSkippedSites must be an array');
  if (!Array.isArray(policy.declaredTags)) issues.push('policy declaredTags must be an array');

  const ciSkippedSites = Array.isArray(policy.ciSkippedSites) ? policy.ciSkippedSites : [];
  const declaredTags = Array.isArray(policy.declaredTags) ? policy.declaredTags : [];
  const policySites = new Map();
  const policyTags = new Map();

  for (const entry of ciSkippedSites) {
    const missing = missingFields(entry, REQUIRED_SKIP_FIELDS);
    if (missing.length > 0) issues.push(`ciSkippedSites entry ${entry.path || '<unknown>'} missing fields: ${missing.join(', ')}`);
    const key = `${entry.path || ''}#${entry.element || ''}`;
    if (policySites.has(key)) issues.push(`duplicate ciSkippedSites entry ${key}`);
    policySites.set(key, entry);
  }

  for (const entry of declaredTags) {
    const missing = missingFields(entry, REQUIRED_TAG_FIELDS);
    if (missing.length > 0) issues.push(`declaredTags entry ${entry.tag || '<unknown>'} missing fields: ${missing.join(', ')}`);
    if (entry.tag === 'stress') issues.push('declaredTags must not register stress; use stressSuitePolicy instead');
    if (entry.tag) {
      if (policyTags.has(entry.tag)) issues.push(`duplicate declaredTags entry ${entry.tag}`);
      policyTags.set(entry.tag, entry);
    }
  }

  for (const site of discovered.ciSkippedSites) {
    const key = `${site.path}#${site.element}`;
    if (!policySites.has(key)) {
      issues.push(`CI-skipped test site missing from policy: ${site.path}:${site.line} ${site.element}`);
    }
  }
  const discoveredSiteKeys = new Set(discovered.ciSkippedSites.map((site) => `${site.path}#${site.element}`));
  for (const entry of ciSkippedSites) {
    const key = `${entry.path || ''}#${entry.element || ''}`;
    if (entry.path && entry.element && !discoveredSiteKeys.has(key)) {
      issues.push(`policy ciSkippedSites entry has no discovered CI skip: ${key}`);
    }
  }

  const discoveredTags = new Set(discovered.tags.map((tag) => tag.tag));
  for (const tag of discoveredTags) {
    if (tag === 'stress') continue;
    if (!policyTags.has(tag)) issues.push(`JUnit tag missing from policy: ${tag}`);
  }
  for (const tag of policyTags.keys()) {
    if (!discoveredTags.has(tag)) issues.push(`policy declaredTags entry has no discovered tag: ${tag}`);
  }

  return {
    ok: issues.length === 0,
    issues,
    discovered,
  };
}

function main() {
  try {
    const opts = parseArgs(process.argv.slice(2));
    if (opts.help) {
      console.log(usage());
      return;
    }
    const root = path.resolve(opts.root || repoRootFromCwd());
    const policyPath = path.resolve(opts.policy || path.join(root, 'scripts', 'ci', 'test-evidence-policy.v1.json'));
    const policy = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
    const result = validateTestEvidencePolicy(policy, discoverEvidenceSubjects(root));
    if (opts.json) console.log(JSON.stringify(result, null, 2));
    else if (result.ok) console.log(`verify-test-evidence-policy: OK (${result.discovered.ciSkippedSites.length} CI skips, ${new Set(result.discovered.tags.map((t) => t.tag)).size} tags)`);
    else {
      console.error('verify-test-evidence-policy: FAIL');
      for (const issue of result.issues) console.error(`- ${issue}`);
    }
    if (!result.ok) process.exitCode = 1;
  } catch (error) {
    console.error(`verify-test-evidence-policy: ${error.message}`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
