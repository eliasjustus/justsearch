#!/usr/bin/env node
/**
 * Tempdoc 727: batch friction/timewaste miner over local Claude Code session transcripts.
 *
 * Sibling to evaluate-session.mjs (which judges task COMPLETION) — this judges PROCESS
 * friction instead: what wasted turns/tokens/time during a session, independent of whether
 * the task ultimately succeeded. Reuses the same condense-then-judge-via-headless-CLI shape
 * as evaluate-session.mjs, with a different rubric and a friction-tuned condenser (keeps
 * more error/hook-block/warning content; evaluate-session.mjs deliberately hides process
 * signals from its judge to avoid anchoring the completion verdict on them).
 *
 * Reads raw transcripts directly from the local Claude Code projects directory (these are
 * NOT the same as tmp/agent-telemetry/events.ndjson — they're the full JSONL conversation
 * logs Claude Code itself writes per session). Output is cached per-session in
 * tmp/agent-telemetry/friction-results/<sessionId>.json (skips sessions already processed;
 * delete a file to force re-judging it).
 *
 * Usage:
 *   node mine-friction.mjs [--limit N] [--concurrency N] [--project-dir <path>]
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { discoverProjectDirs, DEFAULT_PROJECTS_ROOT } from './lib/transcript-store.mjs';

const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const repoRoot = path.resolve(SCRIPT_DIR, '..', '..');
const OUT_DIR = path.join(repoRoot, 'tmp', 'agent-telemetry', 'friction-results');
const CONDENSE_CAP = 45_000;
const ERROR_EXCERPT_CAP = 800;
const BASH_OUTPUT_CAP = 500;
const JUDGE_TIMEOUT_MS = 150_000;

function parseArgs() {
  const args = process.argv.slice(2);
  const get = (name, def) => {
    const i = args.indexOf(name);
    return i !== -1 && args[i + 1] ? args[i + 1] : def;
  };
  return {
    limit: parseInt(get('--limit', '0'), 10),
    concurrency: parseInt(get('--concurrency', '6'), 10),
    // No hand-picked default dir anymore (tempdoc 886 §12 PR 5b) — omitting
    // --project-dir now discovers EVERY /justsearch/i-matching project dir on
    // this machine via lib/transcript-store.mjs's discoverProjectDirs, the same
    // multi-dir convention overhead-taxonomy.mjs already uses (main checkout +
    // every worktree, each its own distinct project dir under Claude Code's
    // slugging). --project-dir, when passed, still narrows to exactly one dir.
    projectDir: get('--project-dir', null),
  };
}

/**
 * Every `<sessionId>.jsonl` main transcript under `dirPath` (one flat
 * directory, no subagent-path enumeration — mine-friction judges main
 * transcripts only).
 */
function sessionFilesIn(dirPath) {
  let files;
  try {
    files = fs.readdirSync(dirPath).filter(f => f.endsWith('.jsonl'));
  } catch {
    return [];
  }
  return files.map(f => ({ sessionId: f.replace(/\.jsonl$/, ''), transcriptPath: path.join(dirPath, f) }));
}

/**
 * Resolve the set of (sessionId, transcriptPath) pairs to mine: an explicit
 * `--project-dir` narrows to that one directory (old single-dir behaviour,
 * kept for callers pointing at a fixture/test dir or a mismatched checkout
 * path); otherwise every discovered justsearch project dir is unioned.
 */
function discoverSessionFiles(projectDir) {
  if (projectDir) return { sessionFiles: sessionFilesIn(projectDir), dirsScanned: [projectDir] };
  const dirs = discoverProjectDirs(DEFAULT_PROJECTS_ROOT);
  const sessionFiles = dirs.flatMap((d) => sessionFilesIn(d.path));
  return { sessionFiles, dirsScanned: dirs.map((d) => d.path) };
}

function resolveClaudeBin() {
  if (process.platform !== 'win32') return { bin: 'claude', args: [] };
  const npmPrefix = process.env.APPDATA
    ? path.join(process.env.APPDATA, 'npm')
    : path.join(process.env.HOME || '', '.npm-global');
  const cliJs = path.join(npmPrefix, 'node_modules', '@anthropic-ai', 'claude-code', 'cli.js');
  if (fs.existsSync(cliJs)) return { bin: process.execPath, args: [cliJs] };
  return { bin: 'claude', args: [] };
}
const CLAUDE = resolveClaudeBin();

const SYSTEM_PROMPT = `You are auditing an agent coding session transcript for PROCESS FRICTION — time, turns, or tokens wasted for reasons other than the inherent difficulty of the task. You do NOT judge whether the task succeeded; you judge what went inefficiently along the way.

Look specifically for: repeated/failed tool calls on the same thing, build or test failures that took multiple attempts to fix, the agent being blocked by a hook/permission/guard and having to adjust, the agent making a wrong assumption that was later corrected (by the user or by discovering evidence), redundant re-reading or re-exploration of the same file/concept, environment or tooling quirks (stale artifacts, wrong working directory, path issues, flaky commands), the user having to correct or redirect the agent, and any "discovery tax" moment where the agent had to re-derive something that should have been already known/documented.

Respond with ONLY a JSON object (no markdown fences, no other text):

{
  "session_summary": "one sentence: what was this session doing",
  "no_friction_detected": true/false,
  "friction_incidents": [
    {
      "category": "short kebab-case tag, e.g. repeated-build-failure, tool-error-loop, hook-block-friction, stale-artifact, wrong-assumption-corrected, discovery-tax, permission-denial, environment-quirk, redundant-exploration, user-correction, retry-loop, other",
      "description": "1-2 sentences: what happened and why it cost time/turns",
      "estimated_cost": "low" | "medium" | "high",
      "evidence": "short quote or close paraphrase from the transcript"
    }
  ],
  "biggest_single_timewaste": "one sentence naming the single largest friction point in this session, or null if none stood out"
}

If the session shows no real friction (clean, direct execution), set no_friction_detected: true and friction_incidents: [].`;

function summarizeToolUse(block) {
  const name = block.name ?? 'unknown';
  const input = block.input ?? {};
  switch (name) {
    case 'Read': return `Read: ${input.file_path ?? '?'}`;
    case 'Edit': return `Edit: ${input.file_path ?? '?'}`;
    case 'Write': return `Write: ${input.file_path ?? '?'}`;
    case 'Bash': return `Bash: ${(input.command ?? '').substring(0, 140)}`;
    case 'Grep': return `Grep: "${input.pattern ?? '?'}" in ${input.path ?? '.'}`;
    case 'Glob': return `Glob: ${input.pattern ?? '?'}`;
    case 'Task': return `Task: ${input.description ?? input.subagent_type ?? '?'}`;
    default: return name;
  }
}

function extractToolResultExcerpt(block, lastToolName) {
  const content = typeof block.content === 'string'
    ? block.content
    : (Array.isArray(block.content) ? block.content.map(c => c.text ?? '').join('') : '');
  if (!content) return null;
  if (block.is_error) {
    return `[TOOL_ERROR] ${content.substring(0, ERROR_EXCERPT_CAP)}`;
  }
  // Friction signals that show up in successful-looking tool_results too
  // (hook blocks, denials, warnings) — don't gate these on is_error.
  if (/\b(blocked|denied|BLOCKED|DENIED|hook|guard|OWNER_CONFLICT|Permission denied|not allowed|refus)/i.test(content.substring(0, 300))) {
    return `[FLAGGED] ${content.substring(0, ERROR_EXCERPT_CAP)}`;
  }
  if (lastToolName === 'Bash') {
    const excerpt = content.length > BASH_OUTPUT_CAP ? content.substring(content.length - BASH_OUTPUT_CAP) : content;
    return `[BASH_OUTPUT] ${excerpt.trim()}`;
  }
  return null;
}

function condenseTranscript(transcriptPath) {
  const parts = [];
  let content;
  try {
    content = fs.readFileSync(transcriptPath, 'utf8');
  } catch {
    return null;
  }
  let lastToolName = null;
  for (const line of content.split('\n')) {
    if (!line.trim()) continue;
    let entry;
    try { entry = JSON.parse(line); } catch { continue; }
    if (entry.type === 'progress' || entry.type === 'file-history-snapshot' || entry.type === 'summary') continue;

    if (entry.type === 'user') {
      const msg = entry.message?.content;
      if (!msg) continue;
      if (typeof msg === 'string') {
        if (entry.isMeta) continue;
        parts.push(msg.length > 1500 ? `[USER] (${msg.length} chars, truncated) ${msg.substring(0, 1200)}` : `[USER] ${msg}`);
      } else if (Array.isArray(msg)) {
        for (const block of msg) {
          if (block.type === 'tool_result') {
            const excerpt = extractToolResultExcerpt(block, lastToolName);
            if (excerpt) parts.push(excerpt);
          }
        }
      }
    }
    if (entry.type === 'assistant') {
      const msg = entry.message?.content;
      if (!Array.isArray(msg)) continue;
      for (const block of msg) {
        if (block.type === 'text' && block.text?.trim()) {
          parts.push(`[ASSISTANT] ${block.text.substring(0, 300).trim()}`);
        }
        if (block.type === 'tool_use') {
          parts.push(`[TOOL] ${summarizeToolUse(block)}`);
          lastToolName = block.name;
        }
      }
    }
    if (parts.join('\n').length > CONDENSE_CAP) break;
  }
  return parts.join('\n').substring(0, CONDENSE_CAP);
}

function callJudge(condensed) {
  return new Promise((resolve) => {
    const child = spawn(CLAUDE.bin, [
      ...CLAUDE.args,
      '-p',
      '--model', 'sonnet',
      '--output-format', 'json',
      '--no-session-persistence',
      '--system-prompt', SYSTEM_PROMPT,
      '--tools', '',
      '--max-turns', '1',
    ], { env: { ...process.env, CLAUDECODE: undefined } });

    let stdout = '', stderr = '';
    const timer = setTimeout(() => { child.kill(); }, JUDGE_TIMEOUT_MS);
    child.stdout.on('data', (d) => { stdout += d; });
    child.stderr.on('data', (d) => { stderr += d; });
    child.on('close', (code) => {
      clearTimeout(timer);
      if (code !== 0) return resolve({ error: `exit ${code}: ${stderr.substring(0, 200)}` });
      try {
        const output = JSON.parse(stdout);
        if (output.is_error) return resolve({ error: `CLI error: ${output.result}` });
        let text = (output.result ?? '').trim()
          .replace(/^```(?:json)?\s*\n?/, '').replace(/\n?```\s*$/, '').trim();
        const start = text.indexOf('{');
        const end = text.lastIndexOf('}');
        if (start !== -1 && end > start) text = text.substring(start, end + 1);
        const parsed = JSON.parse(text);
        resolve({ evaluation: parsed, cost_usd: output.total_cost_usd ?? null });
      } catch (e) {
        resolve({ error: `parse error: ${e.message}; raw: ${stdout.substring(0, 200)}` });
      }
    });
    child.stdin.write(condensed);
    child.stdin.end();
  });
}

async function processOne(sessionId, transcriptPath) {
  const outPath = path.join(OUT_DIR, `${sessionId}.json`);
  if (fs.existsSync(outPath)) return { sessionId, skipped: true };
  const condensed = condenseTranscript(transcriptPath);
  if (!condensed || condensed.length < 200) {
    fs.writeFileSync(outPath, JSON.stringify({ sessionId, tooSmall: true }, null, 2));
    return { sessionId, tooSmall: true };
  }
  const result = await callJudge(condensed);
  fs.writeFileSync(outPath, JSON.stringify({ sessionId, ...result }, null, 2));
  return { sessionId, ok: !result.error };
}

async function main() {
  const opts = parseArgs();
  fs.mkdirSync(OUT_DIR, { recursive: true });

  if (opts.projectDir && !fs.existsSync(opts.projectDir)) {
    console.error(`Project transcript directory not found: ${opts.projectDir}`);
    process.exit(1);
  }

  const { sessionFiles: allSessionFiles, dirsScanned } = discoverSessionFiles(opts.projectDir);
  if (allSessionFiles.length === 0) {
    console.error(opts.projectDir
      ? `No transcripts found under ${opts.projectDir}`
      : `No transcripts found under any justsearch project dir in ${DEFAULT_PROJECTS_ROOT}`);
    console.error('Pass --project-dir explicitly if this repo checkout path differs from the one the transcripts were recorded under.');
    process.exit(1);
  }

  const currentSessionId = process.env.CLAUDE_SESSION_ID || null;
  let sessionFiles = currentSessionId
    ? allSessionFiles.filter((s) => s.sessionId !== currentSessionId)
    : allSessionFiles;
  if (opts.limit > 0) sessionFiles = sessionFiles.slice(0, opts.limit);

  console.log(`Processing ${sessionFiles.length} transcripts from ${dirsScanned.length} project dir(s), concurrency=${opts.concurrency}`);

  let idx = 0, done = 0;
  const worker = async () => {
    while (idx < sessionFiles.length) {
      const { sessionId, transcriptPath } = sessionFiles[idx++];
      try {
        const r = await processOne(sessionId, transcriptPath);
        done++;
        console.log(`[${done}/${sessionFiles.length}] ${sessionId} ${r.skipped ? '(cached)' : r.tooSmall ? '(too small)' : r.ok ? 'OK' : 'ERROR'}`);
      } catch (e) {
        done++;
        console.log(`[${done}/${sessionFiles.length}] ${sessionId} EXCEPTION: ${e.message}`);
      }
    }
  };
  await Promise.all(Array.from({ length: opts.concurrency }, worker));
  console.log('DONE');
}

// Guarded entry point (886 PR 5b): importing this module for its exports must
// never start a mining run — an unguarded main() here made two real `claude`
// judge calls when a sanity `import()` touched the file.
if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) main();
