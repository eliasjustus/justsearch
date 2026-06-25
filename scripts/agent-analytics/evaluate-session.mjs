#!/usr/bin/env node

/**
 * LLM-as-judge session outcome evaluator.
 *
 * Reads session transcript JSONL files, condenses them to ~8K tokens,
 * sends to a judge model (Sonnet by default) with a structured rubric,
 * and writes outcome evaluations to outcomes.ndjson.
 *
 * Usage:
 *   node evaluate-session.mjs --session-id <id>   # Evaluate one session
 *   node evaluate-session.mjs --all               # Evaluate all unevaluated sessions
 *   node evaluate-session.mjs --all --force       # Re-evaluate all sessions
 *   node evaluate-session.mjs --dry-run           # Skip LLM call, synthetic output
 *   node evaluate-session.mjs --model haiku       # Override model (default: sonnet)
 *   node evaluate-session.mjs --json              # JSON to stdout
 *   node evaluate-session.mjs --fallback          # Classify transcript-less sessions via heuristics
 */

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

// Resolve claude CLI entry point. On Windows, .cmd shims can't be spawned
// without shell:true (which corrupts args containing special chars).
// Instead, resolve the underlying Node.js script and invoke it directly.
function resolveClaudeBin() {
  if (process.platform !== 'win32') return { bin: 'claude', args: [] };
  const npmPrefix = process.env.APPDATA
    ? path.join(process.env.APPDATA, 'npm')
    : path.join(process.env.HOME || '', '.npm-global');
  const cliJs = path.join(npmPrefix, 'node_modules', '@anthropic-ai', 'claude-code', 'cli.js');
  if (fs.existsSync(cliJs)) return { bin: process.execPath, args: [cliJs] };
  // Fallback: hope 'claude' is on PATH (works in non-Windows-like shells)
  return { bin: 'claude', args: [] };
}
const CLAUDE = resolveClaudeBin();
import {
  TELEMETRY_DIR, SESSIONS_DIR, SCORES_FILE,
  // tempdoc 622 §6.3: the LLM-judge is demoted to a RESIDUAL inference producer.
  // It writes the judge cache, NOT the fact-authoritative outcomes.ndjson (which
  // outcome-session.mjs owns, deriving hard facts from git/build/tempdoc/gates).
  JUDGE_OUTCOMES_FILE as OUTCOMES_FILE,
  repoRoot, loadEvents, groupBySession, loadNdjsonMap, loadSessionReports, round,
} from './lib/telemetry-io.mjs';

// --- Constants ---

const CONDENSE_CAP = 30_000; // Safety cap on condensed text (characters)
const JUDGE_TIMEOUT = 120_000; // 2 minutes for LLM call
const BASH_OUTPUT_CAP = 300; // Max chars of Bash output to include
const BUILD_KEYWORDS = /\b(pass|fail|error|success|build|test|compil|assert)\b/i;

const SYSTEM_PROMPT = `You are evaluating an agent coding session. You will receive a condensed transcript showing the user's request, the agent's tool calls, command outputs, and any errors encountered.

Respond in two parts:

PART 1 — Emit exactly one completion tag on its own line:
<COMPLETE> or <PARTIAL> or <FAILED> or <ABANDONED>

PART 2 — A JSON object (no markdown fences) with this schema:

{
  "task_intent": "One sentence: what was the agent trying to accomplish?",
  "task_completion": { "verdict": "complete"|"partial"|"failed"|"abandoned", "rationale": "why" },
  "task_type": "bugfix" | "feature" | "refactor" | "docs" | "investigation" | "chore" | "other",
  "tests_added": { "verdict": true/false, "rationale": "why" },
  "build_passed": { "verdict": true/false/null, "rationale": "why" },
  "changes_reverted": true/false,
  "confidence": "high" | "medium" | "low"
}

Definitions:
- task_intent: one sentence describing what the agent was trying to accomplish. Fill this FIRST — use it to ground your other judgments.
- task_completion.verdict: "complete" = fully accomplished; "partial" = meaningful progress but not finished (e.g., 3 of 5 subtasks done); "failed" = attempted but result is broken or incorrect at session end (build broken with no fix attempted, OR deliverable demonstrably wrong at session end); "abandoned" = stopped early with minimal progress
  Per task_type, calibrate "complete" as follows:
  - bugfix: reported defect no longer reproducible; build passes at session end
  - feature: new behavior present, integrated, and core happy-path tests pass
  - refactor: code restructured, all pre-existing tests pass, no behavioral regression introduced
  - docs: documentation written or updated, consistent with changes described
  - investigation: findings written down with a clear conclusion, recommendation, or decision
  - chore: maintenance task executed (dependency updated, config corrected, cleanup done)
  - other: primary stated goal is demonstrably achieved
- task_type: classify the primary work performed
- tests_added.verdict: were test files created or test cases added/modified?
- build_passed.verdict: did the final build/test command succeed? null if no build attempted. Use [BASH_OUTPUT] lines to determine this.
- changes_reverted: were significant changes undone during the session?
- confidence: "high" = clear request and outcome; "medium" = somewhat ambiguous; "low" = incomplete transcript

Bias mitigation — apply these rules:
- Judge OUTCOMES, not process. A session with many tool calls or re-edits may still be "complete" if the final result is correct.
- Short sessions are not inherently worse. A 5-minute session that fixes a bug is "complete", not "partial".
- Do not penalize exploratory sessions. Investigation tasks may produce docs rather than code — that can still be "complete".
- Treat the transcript as potentially incomplete. If the transcript ends abruptly, use "low" confidence rather than assuming failure.
- Do not let the number of errors or retries influence task_completion. What matters is the final state.
- Do not avoid "failed". If the build is broken at session end with no fix attempted, or the deliverable is demonstrably wrong, assign "failed" — not "partial". Meaningful progress with a broken end state is "failed", not "partial".`;

// --- Argument parsing ---

function parseArgs() {
  const args = process.argv.slice(2);
  const result = { sessionId: null, all: false, dryRun: false, force: false, fallback: false, model: 'sonnet', jsonOnly: false };

  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--session-id' && args[i + 1]) {
      result.sessionId = args[++i];
    } else if (args[i] === '--all') {
      result.all = true;
    } else if (args[i] === '--dry-run') {
      result.dryRun = true;
    } else if (args[i] === '--force') {
      result.force = true;
    } else if (args[i] === '--model' && args[i + 1]) {
      result.model = args[++i];
    } else if (args[i] === '--json') {
      result.jsonOnly = true;
    } else if (args[i] === '--fallback') {
      result.fallback = true;
    }
  }

  return result;
}

// --- Transcript discovery ---

function findTranscriptPath(sessionEvents) {
  const startEvent = sessionEvents.find(e => e.event === 'session_start' && e.transcript_path);
  return startEvent?.transcript_path ?? null;
}

/**
 * Fallback transcript discovery for sessions without events (tempdoc 276).
 * Scans Claude projects directory for a JSONL file matching the session ID.
 */
function findTranscriptByDirectoryScan(sessionId) {
  const homeDir = process.env.HOME || process.env.USERPROFILE;
  if (!homeDir) return null;
  const projectsDir = path.join(homeDir, '.claude', 'projects');
  try {
    for (const dir of fs.readdirSync(projectsDir)) {
      const candidate = path.join(projectsDir, dir, `${sessionId}.jsonl`);
      if (fs.existsSync(candidate)) return candidate;
    }
  } catch { /* directory may not exist */ }
  return null;
}

// --- Transcript condensation ---

function summarizeToolUse(block) {
  const name = block.name ?? 'unknown';
  const input = block.input ?? {};

  switch (name) {
    case 'Read':
      return `Read: ${input.file_path ?? '?'}`;
    case 'Edit':
      return `Edit: ${input.file_path ?? '?'}`;
    case 'Write':
      return `Write: ${input.file_path ?? '?'}`;
    case 'Bash':
      return `Bash: ${(input.command ?? '').substring(0, 120)}`;
    case 'Grep':
      return `Grep: "${input.pattern ?? '?'}" in ${input.path ?? '.'}`;
    case 'Glob':
      return `Glob: ${input.pattern ?? '?'}`;
    case 'Task':
      return `Task: ${input.description ?? input.subagent_type ?? '?'}`;
    default:
      return name;
  }
}

/**
 * Extract a brief excerpt from a tool_result content block.
 * Returns null if the content isn't worth including.
 */
function extractToolResultExcerpt(block, lastToolName) {
  const content = typeof block.content === 'string'
    ? block.content
    : (Array.isArray(block.content) ? block.content.map(c => c.text ?? '').join('') : '');

  // Always include errors
  if (block.is_error) {
    return `[TOOL_ERROR] ${content.substring(0, BASH_OUTPUT_CAP)}`;
  }

  // Include Bash output excerpts (build results, test results)
  if (lastToolName === 'Bash' && content.length > 0) {
    // For build/test output, include last N chars (results are at the end)
    const excerpt = content.length > BASH_OUTPUT_CAP
      ? content.substring(content.length - BASH_OUTPUT_CAP)
      : content;
    return `[BASH_OUTPUT] ${excerpt.trim()}`;
  }

  // Include tool results that contain build/test keywords (even from non-Bash tools)
  if (content.length < 500 && BUILD_KEYWORDS.test(content)) {
    return `[RESULT] ${content.substring(0, BASH_OUTPUT_CAP)}`;
  }

  return null;
}

function condenseTranscript(transcriptPath, report) {
  const parts = [];
  let meaningfulLines = 0;

  // Header — intentionally excludes process score to avoid anchoring the judge
  const duration = report?.duration_seconds
    ? round(report.duration_seconds / 3600, 1) + 'h'
    : 'unknown';
  parts.push('=== Session Metadata ===');
  parts.push(`Duration: ${duration}, Model: ${report?.model ?? 'unknown'}`);
  parts.push(`Tool calls: ${report?.tool_calls?.total ?? '?'}`);
  parts.push('');
  parts.push('=== Conversation ===');

  let content;
  try {
    content = fs.readFileSync(transcriptPath, 'utf8');
  } catch {
    return { condensed: parts.join('\n'), lineCount: 0 };
  }

  // Track the last tool_use name so we can contextualize tool_results
  let lastToolName = null;

  for (const line of content.split('\n')) {
    if (!line.trim()) continue;
    let entry;
    try { entry = JSON.parse(line); } catch { continue; }

    // Skip non-message types
    if (entry.type === 'progress' || entry.type === 'file-history-snapshot' ||
        entry.type === 'summary') continue;

    if (entry.type === 'user') {
      const msg = entry.message?.content;
      if (!msg) continue;

      if (typeof msg === 'string') {
        // Skip meta/system messages
        if (entry.isMeta) continue;
        // Truncate very large system outputs
        if (msg.length > 2000) {
          parts.push(`[USER] (${msg.length} chars, truncated) ${msg.substring(0, 1000)}`);
        } else {
          parts.push(`[USER] ${msg}`);
        }
        meaningfulLines++;
      } else if (Array.isArray(msg)) {
        // Tool result blocks — extract errors and Bash output
        for (const block of msg) {
          if (block.type === 'tool_result') {
            const excerpt = extractToolResultExcerpt(block, lastToolName);
            if (excerpt) {
              parts.push(excerpt);
              meaningfulLines++;
            }
          }
        }
      }
    }

    if (entry.type === 'assistant') {
      const msg = entry.message?.content;
      if (!Array.isArray(msg)) continue;

      for (const block of msg) {
        if (block.type === 'text' && block.text?.trim()) {
          parts.push(`[ASSISTANT] ${block.text.substring(0, 200).trim()}`);
          meaningfulLines++;
        }
        if (block.type === 'tool_use') {
          parts.push(`[TOOL] ${summarizeToolUse(block)}`);
          lastToolName = block.name;
          meaningfulLines++;
        }
        // Skip 'thinking' blocks
      }
    }

    // Safety cap
    if (parts.join('\n').length > CONDENSE_CAP) break;
  }

  const condensed = parts.join('\n').substring(0, CONDENSE_CAP);
  return { condensed, lineCount: meaningfulLines };
}

// --- Judge invocation ---

// Extract <COMPLETE>, <PARTIAL>, <FAILED>, or <ABANDONED> tag from response text.
const COMPLETION_TAG_RE = /<(COMPLETE|PARTIAL|FAILED|ABANDONED)>/i;

function extractCompletionTag(text) {
  const m = COMPLETION_TAG_RE.exec(text);
  return m ? m[1].toLowerCase() : null;
}

function callJudge(condensedTranscript, model) {
  // Single attempt — binary tag parsing makes retries unnecessary.
  // The completion tag is regex-extracted even if JSON is malformed.
  return callJudgeOnce(condensedTranscript, model, 1);
}

function callJudgeOnce(condensedTranscript, model, maxTurns) {
  // Use prompt-based JSON instead of --json-schema (which requires 2 turns).
  // --tools "" disables tool use so the model can't call project tools.
  // --system-prompt sets the rubric; stdin is the condensed transcript.
  const result = spawnSync(CLAUDE.bin, [
    ...CLAUDE.args,
    '-p',
    '--model', model,
    '--output-format', 'json',
    '--no-session-persistence',
    '--system-prompt', SYSTEM_PROMPT,
    '--tools', '',
    '--max-turns', String(maxTurns),
  ], {
    input: condensedTranscript,
    encoding: 'utf8',
    timeout: JUDGE_TIMEOUT,
    stdio: ['pipe', 'pipe', 'pipe'],
    env: { ...process.env, CLAUDECODE: undefined },
  });

  if (result.error) {
    return { evaluation: null, cost_usd: null, input_tokens: null, error: `spawn error: ${result.error.message}` };
  }

  if (result.status !== 0) {
    const stderr = (result.stderr ?? '').substring(0, 200);
    return { evaluation: null, cost_usd: null, input_tokens: null, error: `CLI exit ${result.status}: ${stderr}` };
  }

  try {
    const output = JSON.parse(result.stdout);
    if (output.is_error) {
      return { evaluation: null, cost_usd: output.total_cost_usd ?? null, input_tokens: null, error: `CLI error: ${output.result}` };
    }

    // Extract actual token count from CLI usage data
    const usage = output.usage ?? {};
    const inputTokens = (usage.input_tokens ?? 0)
      + (usage.cache_read_input_tokens ?? 0)
      + (usage.cache_creation_input_tokens ?? 0);

    // The response has two parts: a completion tag (<COMPLETE> etc.) and a JSON object.
    // Extract the tag via regex first (parse-proof), then try JSON for the rest.
    const resultText = (output.result ?? '').trim();

    if (!resultText) {
      return { evaluation: null, cost_usd: output.total_cost_usd ?? null, input_tokens: inputTokens, error: 'empty response from judge' };
    }

    // 1. Extract binary completion tag (always succeeds if model follows the prompt)
    const tagCompletion = extractCompletionTag(resultText);

    // 2. Try to parse the JSON portion (strip markdown fences and tag lines)
    let jsonText = resultText
      .replace(COMPLETION_TAG_RE, '')                                       // remove the tag
      .replace(/^```(?:json)?\s*\n?/, '').replace(/\n?```\s*$/, '')        // strip fences
      .trim();

    let evaluation = null;
    let jsonError = null;
    try {
      // Find the first { and last } to extract JSON from surrounding text
      const start = jsonText.indexOf('{');
      const end = jsonText.lastIndexOf('}');
      if (start !== -1 && end > start) {
        jsonText = jsonText.substring(start, end + 1);
      }
      const raw = JSON.parse(jsonText);

      // Normalize verdict objects: extract .verdict from structured fields
      const completionObj = raw.task_completion;
      const testsObj = raw.tests_added;
      const buildObj = raw.build_passed;

      evaluation = {
        task_intent: raw.task_intent ?? null,
        task_completion: typeof completionObj === 'object' ? completionObj?.verdict : completionObj,
        task_completion_rationale: typeof completionObj === 'object' ? completionObj?.rationale : null,
        task_type: raw.task_type,
        tests_added: typeof testsObj === 'object' ? testsObj?.verdict : testsObj,
        tests_added_rationale: typeof testsObj === 'object' ? testsObj?.rationale : null,
        build_passed: typeof buildObj === 'object' ? buildObj?.verdict : buildObj,
        build_passed_rationale: typeof buildObj === 'object' ? buildObj?.rationale : null,
        changes_reverted: raw.changes_reverted,
        confidence: raw.confidence,
        rationale: raw.rationale ?? null, // backward compat: old-format flat rationale
      };
    } catch (err) {
      jsonError = err.message;
    }

    // 3. If JSON failed but tag succeeded, build a minimal evaluation from the tag
    if (!evaluation && tagCompletion) {
      evaluation = {
        task_intent: null,
        task_completion: tagCompletion,
        task_completion_rationale: null,
        task_type: null,
        tests_added: null,
        tests_added_rationale: null,
        build_passed: null,
        build_passed_rationale: null,
        changes_reverted: null,
        confidence: 'low',
        rationale: `JSON parse failed (${jsonError}); completion extracted from tag only.`,
      };
    }

    // 4. If tag and JSON both succeeded but disagree, prefer the tag (more reliable)
    if (evaluation && tagCompletion && evaluation.task_completion !== tagCompletion) {
      evaluation.task_completion = tagCompletion;
    }

    // 5. Bias correction: if the judge says "partial" but the build is broken,
    // override to "failed". This catches the most common agreeableness bias
    // pattern (LLM judges avoid negative verdicts, TNR <25% per arXiv:2510.11822).
    if (evaluation &&
        evaluation.task_completion === 'partial' &&
        evaluation.build_passed === false &&
        evaluation.changes_reverted !== true) {
      evaluation.task_completion = 'failed';
      evaluation.task_completion_rationale =
        (evaluation.task_completion_rationale || '') +
        ' [Auto-corrected from "partial": build broken at session end.]';
    }

    if (!evaluation) {
      return { evaluation: null, cost_usd: output.total_cost_usd ?? null, input_tokens: inputTokens, error: `parse error: ${jsonError}` };
    }

    return {
      evaluation,
      cost_usd: output.total_cost_usd ?? null,
      input_tokens: inputTokens,
      error: null,
    };
  } catch (err) {
    return { evaluation: null, cost_usd: null, input_tokens: null, error: `parse error: ${err.message}` };
  }
}

function makeDryRunEvaluation() {
  return {
    task_intent: 'Dry-run synthetic session.',
    task_completion: 'complete',
    task_completion_rationale: 'Dry-run — no LLM call made.',
    task_type: 'investigation',
    tests_added: false,
    tests_added_rationale: null,
    build_passed: null,
    build_passed_rationale: null,
    changes_reverted: false,
    confidence: 'low',
    rationale: 'Dry-run synthetic evaluation — no LLM call made.',
  };
}

// --- NDJSON I/O ---

function writeOutcomes(records) {
  const outPath = path.join(repoRoot, TELEMETRY_DIR, OUTCOMES_FILE);
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  const content = records.map(r => JSON.stringify(r)).join('\n') + '\n';
  fs.writeFileSync(outPath, content, 'utf8');
}

function upsertOutcome(record) {
  const outPath = path.join(repoRoot, TELEMETRY_DIR, OUTCOMES_FILE);
  fs.mkdirSync(path.dirname(outPath), { recursive: true });

  let existing = [];
  try {
    existing = fs.readFileSync(outPath, 'utf8')
      .split('\n').filter(l => l.trim()).map(l => JSON.parse(l));
  } catch { /* start fresh */ }

  const idx = existing.findIndex(r => r.session_id === record.session_id);
  if (idx !== -1) {
    existing[idx] = record;
  } else {
    existing.push(record);
  }
  writeOutcomes(existing);
}

// --- G3 fallback heuristic classifier (tempdoc 276) ---

/**
 * Classify a session from report signals when no transcript is available.
 * Returns a task_type string or null if signals are insufficient.
 */
function classifyFromReport(report) {
  if (!report) return null;
  const totalCalls = report.tool_calls?.total ?? 0;
  const totalEdits = report.file_edits?.total ?? 0;

  // Too little activity to classify
  if (totalCalls < 10 && totalEdits === 0) return null;

  // 1. Git commit analysis — use dominant type if available
  const gitType = report.task_type;
  if (gitType && typeof gitType === 'object' && gitType.details) {
    const details = gitType.details;
    const total = Object.values(details).reduce((s, n) => s + n, 0);
    if (total > 0) {
      // Find dominant category (>40% of commits)
      const sorted = Object.entries(details).sort((a, b) => b[1] - a[1]);
      const [topKey, topCount] = sorted[0];
      if (topCount / total > 0.4) {
        const gitToType = { feat: 'feature', fix: 'bugfix', docs: 'docs',
          refactor: 'refactor', chore: 'chore', bench: 'investigation' };
        if (gitToType[topKey]) return gitToType[topKey];
      }
    }
  }

  // 2. File extension patterns from edits
  const editFiles = (report.file_edits?.by_file ?? []).map(f => f.file);
  const exts = editFiles.map(f => path.extname(f).toLowerCase()).filter(Boolean);
  const extSet = new Set(exts);
  const hasJava = extSet.has('.java') || extSet.has('.kt');
  const hasTs = extSet.has('.tsx') || extSet.has('.ts');
  const hasOnlyMd = extSet.size === 1 && extSet.has('.md');
  const hasTempdoc = editFiles.some(f => f.includes('tempdoc'));
  const builds = report.bash_commands?.build_count ?? 0;

  // Only markdown edits
  if (hasOnlyMd) {
    return hasTempdoc ? 'investigation' : 'docs';
  }

  // Java/Kotlin with builds → feature or implementation
  if (hasJava && builds > 5) return 'feature';

  // TypeScript/React → feature
  if (hasTs && totalEdits > 5) return 'feature';

  // Scripts only (.mjs, .cjs, .mts)
  const hasOnlyScripts = exts.length > 0 && exts.every(e => ['.mjs', '.cjs', '.mts', '.js'].includes(e));
  if (hasOnlyScripts) return 'chore';

  // High read:edit ratio with few edits → investigation
  const reads = report.file_reads?.total ?? 0;
  if (reads > 20 && totalEdits < 5) return 'investigation';

  // Fallback: if we have edits and builds, call it feature
  if (totalEdits > 10 && builds > 0) return 'feature';

  // Moderate edits without builds → could be anything, but investigation is safest
  if (totalEdits > 0) return 'investigation';

  return null;
}

// --- Build output record ---

function makeNullRecord(sessionId, reason) {
  return {
    ts: new Date().toISOString(),
    session_id: sessionId,
    task_intent: null,
    task_completion: null,
    task_completion_rationale: null,
    task_type: null,
    tests_added: null,
    tests_added_rationale: null,
    build_passed: null,
    build_passed_rationale: null,
    changes_reverted: null,
    confidence: null,
    rationale: null,
    eval_model: null,
    eval_cost_usd: null,
    eval_input_tokens: null,
    reason,
  };
}

function makeRecord(sessionId, evaluation, model, costUsd, inputTokens) {
  return {
    ts: new Date().toISOString(),
    session_id: sessionId,
    task_intent: evaluation.task_intent ?? null,
    task_completion: evaluation.task_completion,
    task_completion_rationale: evaluation.task_completion_rationale ?? null,
    task_type: evaluation.task_type,
    tests_added: evaluation.tests_added,
    tests_added_rationale: evaluation.tests_added_rationale ?? null,
    build_passed: evaluation.build_passed,
    build_passed_rationale: evaluation.build_passed_rationale ?? null,
    changes_reverted: evaluation.changes_reverted,
    confidence: evaluation.confidence,
    rationale: evaluation.rationale ?? null,
    eval_model: model,
    eval_cost_usd: costUsd,
    eval_input_tokens: inputTokens,
    reason: null,
  };
}

// --- Session evaluation ---

function evaluateSession(sessionId, sessionEvents, options) {
  const { model, dryRun, report, score } = options;

  // Find transcript — try events first, then directory scan fallback
  const transcriptPath = findTranscriptPath(sessionEvents)
    ?? findTranscriptByDirectoryScan(sessionId);
  if (!transcriptPath || !fs.existsSync(transcriptPath)) {
    // G3 fallback: classify from report heuristics if possible
    const fallbackType = classifyFromReport(report);
    if (fallbackType) {
      const record = makeNullRecord(sessionId, 'fallback_heuristic');
      record.task_type = fallbackType;
      record.rationale = `Classified from session report signals (no transcript available).`;
      record.confidence = 'low';
      return record;
    }
    return makeNullRecord(sessionId, 'no_transcript');
  }

  // Condense
  const { condensed, lineCount } = condenseTranscript(transcriptPath, report);
  const tokensApprox = Math.ceil(condensed.length / 4);

  // Skip trivial sessions (< 5 meaningful lines)
  if (lineCount < 5) {
    return makeNullRecord(sessionId, 'trivial_session');
  }

  // Dry run — skip LLM call
  if (dryRun) {
    return makeRecord(sessionId, makeDryRunEvaluation(), 'dry-run', null, tokensApprox);
  }

  // Call judge
  const { evaluation, cost_usd, input_tokens, error } = callJudge(condensed, model);
  if (error || !evaluation) {
    console.error(`  Judge error for ${sessionId.substring(0, 8)}: ${error}`);
    const record = makeNullRecord(sessionId, 'judge_error');
    record.rationale = error;
    record.eval_cost_usd = cost_usd;
    record.eval_input_tokens = input_tokens;
    return record;
  }

  return makeRecord(sessionId, evaluation, model, cost_usd, input_tokens);
}

// --- Output formatting ---

function formatHuman(record) {
  const sid = record.session_id.substring(0, 8);
  if (record.reason) {
    return `  ${sid}  (${record.reason})`;
  }
  const cost = record.eval_cost_usd != null ? ` $${record.eval_cost_usd.toFixed(4)}` : '';
  return `  ${sid}  ${record.task_completion}  ${record.task_type}  [${record.confidence}]${cost}  "${record.rationale}"`;
}

// --- Main ---

function main() {
  const args = parseArgs();

  if (!args.all && !args.sessionId && !args.fallback) {
    console.error('Usage: node evaluate-session.mjs --session-id <id> | --all [--force] | --fallback [--json]');
    process.exit(1);
  }

  console.error('Loading events...');
  const events = loadEvents();
  const sessions = groupBySession(events);
  const scores = loadNdjsonMap(path.join(repoRoot, TELEMETRY_DIR, SCORES_FILE));
  const reports = loadSessionReports();
  console.error(`Found ${sessions.size} sessions from events, ${reports.size} session reports`);

  if (args.sessionId) {
    const sessionEvents = sessions.get(args.sessionId) ?? [];
    if (sessionEvents.length === 0 && !reports.has(args.sessionId)) {
      console.error(`Session not found: ${args.sessionId}`);
      process.exit(1);
    }
    const result = evaluateSession(args.sessionId, sessionEvents, {
      model: args.model,
      dryRun: args.dryRun,
      report: reports.get(args.sessionId),
      score: scores.get(args.sessionId),
    });
    upsertOutcome(result);

    if (args.jsonOnly) {
      process.stdout.write(JSON.stringify(result, null, 2) + '\n');
    } else {
      console.log(formatHuman(result));
      console.log(`\nOutcome appended to ${path.join(TELEMETRY_DIR, OUTCOMES_FILE)}`);
    }
    return;
  }

  // --fallback mode: reclassify outcomes that have no task_type using heuristics
  if (args.fallback) {
    const existingOutcomes = loadNdjsonMap(path.join(repoRoot, TELEMETRY_DIR, OUTCOMES_FILE));
    let classified = 0;
    const updated = [];

    for (const [sid, outcome] of existingOutcomes) {
      if (outcome.task_type) { updated.push(outcome); continue; }
      const report = reports.get(sid);
      const fallbackType = classifyFromReport(report);
      if (fallbackType) {
        outcome.task_type = fallbackType;
        outcome.reason = 'fallback_heuristic';
        outcome.confidence = 'low';
        outcome.rationale = `Classified from session report signals (no transcript available).`;
        classified++;
        if (!args.jsonOnly) {
          console.log(`  ${sid.substring(0, 8)}  → ${fallbackType}  (heuristic)`);
        }
      }
      updated.push(outcome);
    }

    writeOutcomes(updated);
    if (args.jsonOnly) {
      const results = updated.filter(o => o.reason === 'fallback_heuristic');
      process.stdout.write(JSON.stringify(results, null, 2) + '\n');
    } else {
      console.log(`\nFallback-classified ${classified} sessions.`);
      console.log(`Outcomes written to ${path.join(TELEMETRY_DIR, OUTCOMES_FILE)}`);
    }
    return;
  }

  // --all mode: evaluate sessions that have reports (from events OR session dir)
  const existingOutcomes = loadNdjsonMap(path.join(repoRoot, TELEMETRY_DIR, OUTCOMES_FILE));
  const results = [];

  // Collect all session IDs that have reports (events-based + event-less)
  const allSessionIds = new Set([...sessions.keys(), ...reports.keys()]);

  for (const sid of allSessionIds) {
    if (!reports.has(sid)) continue;
    // Skip already-evaluated unless --force or --dry-run
    if (existingOutcomes.has(sid) && !args.force && !args.dryRun) continue;

    const sessionEvents = sessions.get(sid) ?? [];
    const result = evaluateSession(sid, sessionEvents, {
      model: args.model,
      dryRun: args.dryRun,
      report: reports.get(sid),
      score: scores.get(sid),
    });
    results.push(result);
  }

  // Merge with existing outcomes
  const allRecords = [...existingOutcomes.values()];
  for (const r of results) {
    const idx = allRecords.findIndex(x => x.session_id === r.session_id);
    if (idx !== -1) allRecords[idx] = r;
    else allRecords.push(r);
  }
  writeOutcomes(allRecords);

  if (args.jsonOnly) {
    process.stdout.write(JSON.stringify(results, null, 2) + '\n');
  } else {
    const evaluated = results.filter(r => !r.reason);
    const skipped = results.filter(r => r.reason);
    const totalCost = evaluated.reduce((s, r) => s + (r.eval_cost_usd ?? 0), 0);

    console.log(`Evaluated ${evaluated.length} sessions, skipped ${skipped.length}:`);
    if (totalCost > 0) console.log(`Total eval cost: $${totalCost.toFixed(4)}\n`);
    else console.log('');

    for (const r of results) {
      console.log(formatHuman(r));
    }
    console.log(`\nOutcomes written to ${path.join(TELEMETRY_DIR, OUTCOMES_FILE)}`);
  }
}

main();
