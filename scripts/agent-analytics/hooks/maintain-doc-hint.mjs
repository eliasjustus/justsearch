#!/usr/bin/env node

/**
 * Stop "Maintain" hook — the exit-side complement to the Consult hook (tempdoc 579).
 *
 * When the agent tries to finish a turn, if it edited a governed region this session
 * WITHOUT touching that region's governing doc, block ONCE with a reason ("update the
 * doc, or say why not"). This makes updating the governing doc part of definition-of-
 * done, attacking drift at the source — the React→Lit failure was exactly: agent
 * changed shell-v0 code, the governing doc silently rotted.
 *
 * Consult (PreToolUse) pushes the doc going IN; Maintain (Stop) checks it coming OUT.
 * Both read the one shared GOVERNED_REGIONS map.
 *
 * Safety rails (a blocking hook must be conservative — over-blocking is friction):
 *  - blocks AT MOST ONCE per stop (`stop_hook_active` guard → no loops);
 *  - narrow scope (only GOVERNED_REGIONS, currently shell-v0 only);
 *  - explicit escape hatch in the reason (no behavior change → just say so);
 *  - honors JUSTSEARCH_DISABLE_HOOKS; FAIL-OPEN on any error (never blocks on a bug).
 */

import fs from 'node:fs';
import path from 'node:path';
import { GOVERNED_REGIONS, normalizePath } from '../lib/governed-regions.mjs';
import { runHook } from '../lib/hook-base.mjs';

const SCRIPT_DIR_RAW = path.dirname(new URL(import.meta.url).pathname);
const SCRIPT_DIR =
  process.platform === 'win32' ? SCRIPT_DIR_RAW.replace(/^\/([A-Za-z]:)/, '$1') : SCRIPT_DIR_RAW;
const REPO_ROOT = path.resolve(SCRIPT_DIR, '..', '..', '..');

// Find this session's transcript: prefer the Stop-input field; else look up the
// session_start telemetry event by session_id (the analyze-session.mjs pattern).
function resolveTranscriptPath(input) {
  if (input.transcript_path && fs.existsSync(input.transcript_path)) return input.transcript_path;
  try {
    const evFile = path.join(REPO_ROOT, 'tmp', 'agent-telemetry', 'events.ndjson');
    if (!fs.existsSync(evFile)) return null;
    const lines = fs.readFileSync(evFile, 'utf8').split('\n');
    for (let i = lines.length - 1; i >= 0; i--) {
      if (!lines[i].trim()) continue;
      let e;
      try {
        e = JSON.parse(lines[i]);
      } catch {
        continue;
      }
      if (e.event === 'session_start' && e.session_id === input.session_id && e.transcript_path) {
        return fs.existsSync(e.transcript_path) ? e.transcript_path : null;
      }
    }
  } catch {
    /* fall through to null — fail-open */
  }
  return null;
}

// Collect Edit/Write target file_paths from the transcript JSONL (normalized set).
function editedFiles(transcriptPath) {
  const files = new Set();
  let text;
  try {
    text = fs.readFileSync(transcriptPath, 'utf8');
  } catch {
    return files;
  }
  for (const line of text.split('\n')) {
    if (!line.trim()) continue;
    let obj;
    try {
      obj = JSON.parse(line);
    } catch {
      continue;
    }
    if (obj?.type !== 'assistant' || !Array.isArray(obj?.message?.content)) continue;
    for (const b of obj.message.content) {
      if (b?.type === 'tool_use' && (b.name === 'Edit' || b.name === 'Write') && b.input?.file_path) {
        files.add(normalizePath(b.input.file_path));
      }
    }
  }
  return files;
}

async function main() {
  // Kill switch + fail-open are handled by runHook (see module bottom).
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  let input;
  try {
    input = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    return;
  }

  // Infinite-loop guard: we already nudged once this stop — let it finish.
  if (input.stop_hook_active) return;

  const transcriptPath = resolveTranscriptPath(input);
  if (!transcriptPath) return; // can't inspect → fail-open (never block blind)

  const edited = editedFiles(transcriptPath);
  if (edited.size === 0) return;

  // A region is "unmaintained" if any edited file is in it AND none of its
  // governing docs were edited this session.
  const violations = [];
  for (const region of GOVERNED_REGIONS) {
    if (!region.maintain) continue; // consult-only DELIVERY rows never block (tempdoc 620 Move 2)
    if (![...edited].some((f) => region.match(f))) continue;
    const docTouched = region.docs.some((d) => edited.has(normalizePath(d.path)));
    if (!docTouched) violations.push(region);
  }
  if (violations.length === 0) return;

  // De-dupe per session: nudge ONCE per region per session. The transcript is
  // cumulative, so a session-wide check would otherwise re-fire at EVERY turn-end
  // (the `stop_hook_active` guard only dedupes within one forced continuation, NOT
  // across separate turns). State lives in a per-session marker file.
  const stateFile = path.join(
    REPO_ROOT,
    'tmp',
    'agent-telemetry',
    `maintain-nudged-${input.session_id || 'unknown'}.json`,
  );
  let nudged = [];
  try {
    nudged = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
  } catch {
    /* none recorded yet */
  }
  const fresh = violations.filter((v) => !nudged.includes(v.region));
  if (fresh.length === 0) return; // already nudged these regions this session

  // Record BEFORE nudging — if we can't persist, SKIP the nudge rather than risk
  // re-firing every turn (fail-open toward no friction).
  try {
    fs.mkdirSync(path.dirname(stateFile), { recursive: true });
    fs.writeFileSync(stateFile, JSON.stringify([...new Set([...nudged, ...fresh.map((v) => v.region)])]));
  } catch {
    return;
  }

  const reason = [
    'You edited governed code this session without updating its governing doc — update the doc, or reply that no doc change is needed and why:',
    ...fresh.map((v) => `  - ${v.region}: ${v.docs.map((d) => `\`${d.path}\``).join(', ')}`),
    '(Maintain hook — tempdoc 579. Keeps the doc honest at the source. Nudges once per region per session; if no documented behavior changed, just say so to proceed.)',
  ].join('\n');

  process.stdout.write(JSON.stringify({ decision: 'block', reason }));
}

runHook(import.meta.url, main);
