#!/usr/bin/env node

/**
 * PostToolUse hook — age-stamps a tempdoc when the agent Reads it.
 *
 * Mechanizes `tempdocs-are-dated-history` (tempdoc 620 Part V). Tempdocs are
 * append-only dated history, not current truth — a newer tempdoc or shipped code
 * supersedes an older one. When an agent Reads `docs/tempdocs/NNN-*.md`, inject the
 * doc's date/status + how many higher-numbered (newer) tempdocs exist, so staleness
 * is visible at the moment of reading instead of relying on the agent to recall the
 * rule and check.
 *
 * - Self-filters: only `Read` on `docs/tempdocs/NNN-*.md`; early-return otherwise.
 * - Fail-open; never blocks; ~one readdir + one bounded read.
 */

import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const TEMPDOCS_DIR = resolve(REPO_ROOT, 'docs', 'tempdocs');

function normalize(p) {
  return p.replace(/\\/g, '/');
}

/** The tempdoc number for a path under docs/tempdocs/, or null. */
function tempdocNumber(filePath) {
  const n = normalize(filePath || '');
  if (!n.includes('/docs/tempdocs/')) return null;
  const m = basename(n).match(/^(\d+)-.*\.md$/);
  return m ? parseInt(m[1], 10) : null;
}

/** Highest tempdoc number on disk, or null. */
function highestTempdoc() {
  try {
    let max = 0;
    for (const f of readdirSync(TEMPDOCS_DIR)) {
      const m = f.match(/^(\d+)-.*\.md$/);
      if (m) max = Math.max(max, parseInt(m[1], 10));
    }
    return max || null;
  } catch {
    return null;
  }
}

/** First-match value of a top-of-file YAML frontmatter field. */
function frontmatterField(content, field) {
  const m = content.match(new RegExp(`^${field}:\\s*["']?([^"'\\n]+)`, 'm'));
  return m ? m[1].trim() : null;
}

async function main() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  let input;
  try {
    input = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    return;
  }

  if (input.tool_name !== 'Read') return;
  const filePath = input.tool_input?.file_path;
  const num = tempdocNumber(filePath);
  if (num == null) return;

  let date = null;
  let status = null;
  try {
    const head = readFileSync(filePath, 'utf8').slice(0, 1500);
    date = frontmatterField(head, 'updated') || frontmatterField(head, 'created');
    status = frontmatterField(head, 'status');
  } catch {
    // still emit the number + newer-count even if the body can't be read
  }

  const max = highestTempdoc();
  const newer = max && max > num ? max - num : 0;

  const bits = [`tempdoc ${num}`];
  if (date) bits.push(`dated ${date}`);
  if (status) bits.push(`status: ${status}`);
  let msg = bits.join(' — ');
  if (max) msg += `; highest tempdoc on disk is ${max}${newer > 0 ? ` (${newer} newer-numbered exist)` : ''}`;
  msg +=
    '. Tempdocs are dated history, not current truth — newer tempdocs + shipped code supersede older ones; ' +
    'verify against `main` + canonical docs before trusting (rule:tempdocs-are-dated-history).';

  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: { hookEventName: 'PostToolUse', additionalContext: msg },
    }),
  );
}

main().catch(() => process.exit(0));
