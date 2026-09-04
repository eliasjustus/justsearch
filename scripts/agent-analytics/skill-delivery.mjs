#!/usr/bin/env node
/**
 * Skill inventory + Codex transcript delivery evidence (tempdoc 928).
 *
 * This reader is intentionally asymmetric. An exact copy of today's checked-
 * in skill in a tool result proves full current-snapshot delivery. A missing
 * match does NOT prove failure because the transcript may predate the current
 * skill revision. Tool truncation, partial intent, and batching are therefore
 * reported as separate observable facts instead of collapsed into a score.
 *
 * Raw tool inputs/outputs are processed in memory and never included in the
 * returned report or written to disk.
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  DEFAULT_CODEX_HOME,
  listCodexToolExchanges,
} from './lib/ledger/codex-adapter.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_REPO_ROOT = path.resolve(HERE, '..', '..');
const DAY_MS = 24 * 60 * 60 * 1000;
const DEFAULT_WINDOW_DAYS = 30;

export const DELIVERY_CLASSIFICATIONS = [
  'proven_full_current',
  'tool_output_truncated',
  'partial_intent',
  'ambiguous_batched',
  'missing_output',
  'unproven',
];

function normalizeText(value) {
  return String(value ?? '').replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n');
}

function parseScalar(raw) {
  const value = raw.trim();
  if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
    if (value.startsWith('"')) {
      try {
        return JSON.parse(value);
      } catch {
        // Fall through to quote stripping for permissive frontmatter parsing.
      }
    }
    return value.slice(1, -1);
  }
  if (value === 'true') return true;
  if (value === 'false') return false;
  return value;
}

/** Minimal parser for the simple scalar frontmatter used by project skills. */
export function parseSkillFrontmatter(text) {
  const normalized = normalizeText(text);
  if (!normalized.startsWith('---\n')) return { frontmatter: {}, body: normalized };
  const end = normalized.indexOf('\n---\n', 4);
  if (end < 0) return { frontmatter: {}, body: normalized };

  const frontmatter = {};
  for (const line of normalized.slice(4, end).split('\n')) {
    const match = line.match(/^([A-Za-z0-9_-]+):\s*(.*?)\s*$/);
    if (match && match[2] && !['>', '>-', '|', '|-'].includes(match[2])) {
      frontmatter[match[1]] = parseScalar(match[2]);
    }
  }
  return { frontmatter, body: normalized.slice(end + '\n---\n'.length) };
}

function readIfPresent(file) {
  try {
    return fs.readFileSync(file, 'utf8');
  } catch (error) {
    if (error?.code === 'ENOENT') return null;
    throw error;
  }
}

/** Inventory the Claude source skills and their generated Codex projections. */
export function inventorySkills(repoRoot = DEFAULT_REPO_ROOT) {
  const sourceRoot = path.join(repoRoot, '.claude', 'skills');
  let entries;
  try {
    entries = fs.readdirSync(sourceRoot, { withFileTypes: true });
  } catch (error) {
    if (error?.code === 'ENOENT') return [];
    throw error;
  }

  const inventory = [];
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const name = entry.name;
    const sourcePath = path.join(sourceRoot, name, 'SKILL.md');
    const sourceText = readIfPresent(sourcePath);
    if (sourceText == null) continue;

    const projectedPath = path.join(repoRoot, '.agents', 'skills', name, 'SKILL.md');
    const projectionText = readIfPresent(projectedPath);
    const openAiPolicy = readIfPresent(path.join(repoRoot, '.agents', 'skills', name, 'agents', 'openai.yaml'));
    const parsed = parseSkillFrontmatter(sourceText);
    const description = typeof parsed.frontmatter.description === 'string'
      ? parsed.frontmatter.description
      : '';
    const explicitOnly = parsed.frontmatter['disable-model-invocation'] === true
      || /allow_implicit_invocation:\s*false/i.test(openAiPolicy ?? '');

    inventory.push({
      name,
      sourcePath: path.relative(repoRoot, sourcePath).replaceAll('\\', '/'),
      projectedPath: projectionText == null
        ? null
        : path.relative(repoRoot, projectedPath).replaceAll('\\', '/'),
      description,
      descriptionChars: description.length,
      bodyChars: parsed.body.length,
      totalChars: normalizeText(sourceText).length,
      approxTokens: Math.ceil(normalizeText(sourceText).length / 4),
      explicitOnly,
      sourceText: normalizeText(sourceText),
      projectionText: projectionText == null ? null : normalizeText(projectionText),
    });
  }
  return inventory.sort((a, b) => a.name.localeCompare(b.name));
}

/**
 * Find checked-in skill paths in a tool input. Repeated separators support
 * JavaScript command strings whose Windows paths contain escaped backslashes.
 */
export function findSkillTargets(input) {
  const targets = [];
  const seen = new Set();
  const regex = /(\.claude|\.agents)[\\/]+skills[\\/]+([A-Za-z0-9._-]+)[\\/]+SKILL\.md/gi;
  let match;
  while ((match = regex.exec(String(input ?? ''))) !== null) {
    const kind = match[1].toLowerCase() === '.agents' ? 'agents' : 'claude';
    const name = match[2];
    const key = `${kind}:${name.toLowerCase()}`;
    if (seen.has(key)) continue;
    seen.add(key);
    targets.push({ kind, name });
  }
  return targets;
}

const READ_INTENT_MARKER = /\b(?:Get-Content|Select-String|read_file|read_text_file|open_file|rg|cat|sed|head|tail)\b/i;

/** Exclude path mentions in patches/tests from the read-attempt denominator. */
export function isSkillReadExchange(exchange) {
  const input = String(exchange?.input ?? '');
  if (findSkillTargets(input).length === 0) return false;
  if (/apply_patch/i.test(exchange?.name ?? '')) return false;
  if (/tools\.apply_patch\s*\(/i.test(input)
      && !/tools\.(?:exec_command|read_file|read_text_file)\s*\(/i.test(input)) return false;
  return READ_INTENT_MARKER.test(input);
}

const TRUNCATION_MARKERS = [
  /(?:^|\n)Warning:\s*truncated output \(original token count:/i,
  /(?:^|\n)(?:…|\.\.\.)\s*\d+\s+tokens truncated\s*(?:…|\.\.\.)(?:\n|$)/i,
  /(?:^|\n)Output truncated\b/i,
];

const PARTIAL_INTENT_MARKERS = [
  /\bSelect-Object\b[^\n;}]*(?:-First|-Last|-Skip)/i,
  /\bGet-Content\b[^\n;}]*(?:-TotalCount|-Tail)/i,
  /\bsed\s+-n\b/i,
  /\b(?:head|tail)\s+(?:-[^\s]+\s+)?/i,
  /\b(?:offset|limit|line_start|line_end|start_line|end_line)\b\s*[:=]/i,
  /\b(?:Select-String|rg)\b[^\n;}]*(?:SKILL\.md|skills[\\/])/i,
];

function hasExplicitTruncation(outputText) {
  return TRUNCATION_MARKERS.some((pattern) => pattern.test(normalizeText(outputText)));
}

function hasPartialIntent(input) {
  return PARTIAL_INTENT_MARKERS.some((pattern) => pattern.test(String(input ?? '')));
}

function looksBatched(input, targetCount) {
  if (targetCount > 1) return true;
  const producers = String(input ?? '').match(
    /\b(?:Get-Content|Select-String|rg|cat|sed|head|tail)\b|\bgit\s+(?:diff|show|status)\b/gi,
  ) ?? [];
  return producers.length > 1;
}

export function classifySkillAttempt({ input, outputText, missingOutput, expectedText, targetCount = 1 }) {
  const output = normalizeText(outputText);
  const expected = normalizeText(expectedText);
  if (!missingOutput && expected.length > 0 && output.includes(expected)) return 'proven_full_current';
  if (missingOutput) return 'missing_output';
  if (hasExplicitTruncation(output)) return 'tool_output_truncated';
  if (hasPartialIntent(input)) return 'partial_intent';
  if (looksBatched(input, targetCount)) return 'ambiguous_batched';
  return 'unproven';
}

function emptyClassificationCounts() {
  return Object.fromEntries(DELIVERY_CLASSIFICATIONS.map((name) => [name, 0]));
}

function publicInventoryItem(skill) {
  return {
    name: skill.name,
    sourcePath: skill.sourcePath,
    projectedPath: skill.projectedPath,
    description: skill.description,
    descriptionChars: skill.descriptionChars,
    bodyChars: skill.bodyChars,
    totalChars: skill.totalChars,
    approxTokens: skill.approxTokens,
    explicitOnly: skill.explicitOnly,
  };
}

/** Build a privacy-safe aggregate report from an in-memory raw exchange list. */
export function buildSkillDeliveryReport({
  inventory,
  exchanges,
  filesScanned = 0,
  sessionsScanned = 0,
  skippedFiles = 0,
  since = null,
  until = null,
  repoName = null,
} = {}) {
  const byName = new Map(inventory.map((skill) => [skill.name.toLowerCase(), skill]));
  const classifications = emptyClassificationCounts();
  const classificationSessions = Object.fromEntries(
    DELIVERY_CLASSIFICATIONS.map((name) => [name, new Set()]),
  );
  const readSessions = new Set();
  const perSkill = new Map(inventory.map((skill) => [skill.name, {
    name: skill.name,
    attempts: 0,
    sessions: new Set(),
    classifications: emptyClassificationCounts(),
    classificationSessions: Object.fromEntries(
      DELIVERY_CLASSIFICATIONS.map((name) => [name, new Set()]),
    ),
  }]));
  let targetedExchanges = 0;
  let attempts = 0;

  for (const exchange of exchanges) {
    if (!isSkillReadExchange(exchange)) continue;
    const allTargets = findSkillTargets(exchange.input);
    const targets = allTargets.filter((target) => byName.has(target.name.toLowerCase()));
    if (targets.length === 0) continue;
    targetedExchanges += 1;
    if (exchange.sessionId) readSessions.add(exchange.sessionId);

    for (const target of targets) {
      const skill = byName.get(target.name.toLowerCase());
      const expectedText = target.kind === 'agents' ? skill.projectionText : skill.sourceText;
      const classification = classifySkillAttempt({
        input: exchange.input,
        outputText: exchange.outputText,
        missingOutput: exchange.missingOutput,
        expectedText,
        targetCount: targets.length,
      });
      attempts += 1;
      classifications[classification] += 1;
      if (exchange.sessionId) classificationSessions[classification].add(exchange.sessionId);
      const row = perSkill.get(skill.name);
      row.attempts += 1;
      row.classifications[classification] += 1;
      if (exchange.sessionId) row.classificationSessions[classification].add(exchange.sessionId);
      if (exchange.sessionId) row.sessions.add(exchange.sessionId);
    }
  }

  const safeInventory = inventory.map(publicInventoryItem);
  const inventoryTotalChars = safeInventory.reduce((sum, skill) => sum + skill.totalChars, 0);
  const bodyChars = safeInventory.reduce((sum, skill) => sum + skill.bodyChars, 0);
  const descriptionChars = safeInventory.reduce((sum, skill) => sum + skill.descriptionChars, 0);

  return {
    schemaVersion: 1,
    scope: {
      harness: 'codex-cli',
      repoName,
      transcriptWindow: { basis: 'rollout-file-mtime', since, until },
      proofTarget: 'current-checked-in-snapshot',
    },
    inventory: {
      skillCount: safeInventory.length,
      explicitOnlyCount: safeInventory.filter((skill) => skill.explicitOnly).length,
      totalChars: inventoryTotalChars,
      bodyChars,
      descriptionChars,
      approxTokens: Math.ceil(inventoryTotalChars / 4),
      skills: safeInventory.sort((a, b) => b.totalChars - a.totalChars || a.name.localeCompare(b.name)),
    },
    delivery: {
      rolloutFilesScanned: filesScanned,
      sessionsScanned,
      skippedFiles,
      toolExchangesScanned: exchanges.length,
      targetedExchanges,
      attempts,
      sessionsWithReadAttempts: readSessions.size,
      classifications,
      sessionsByClassification: Object.fromEntries(
        DELIVERY_CLASSIFICATIONS.map((name) => [name, classificationSessions[name].size]),
      ),
      bySkill: [...perSkill.values()]
        .map((row) => ({
          name: row.name,
          attempts: row.attempts,
          sessions: row.sessions.size,
          classifications: row.classifications,
          sessionsByClassification: Object.fromEntries(
            DELIVERY_CLASSIFICATIONS.map((name) => [name, row.classificationSessions[name].size]),
          ),
        }))
        .sort((a, b) => b.attempts - a.attempts || a.name.localeCompare(b.name)),
    },
    limitations: [
      'Exact containment proves delivery only for the current checked-in skill snapshot; historical revision drift remains unproven.',
      'An explicit tool-output truncation proves the combined result was capped, not which target section was omitted.',
      'Tool selection and tool-result delivery do not prove model attention, rule adherence, or task correctness.',
      'This slice audits Codex rollouts only; it makes no Claude delivery-parity claim.',
      'Multiple partial/windowed reads are not reconstructed into cumulative coverage; they may collectively cover a full historical skill.',
      'Raw prompts, tool inputs, and tool outputs are processed in memory and omitted from this aggregate report.',
    ],
  };
}

function parseDate(value, flag) {
  const millis = Date.parse(value);
  if (!Number.isFinite(millis)) throw new Error(`${flag} requires an ISO date or timestamp, got: ${value}`);
  return millis;
}

export function parseArgs(argv, nowMs = Date.now()) {
  const opts = {
    repoRoot: DEFAULT_REPO_ROOT,
    codexHome: DEFAULT_CODEX_HOME,
    sinceMs: nowMs - DEFAULT_WINDOW_DAYS * DAY_MS,
    untilMs: null,
    projectPattern: 'justsearch',
    json: false,
    help: false,
  };
  function takeValue(index, flag) {
    const value = argv[index];
    if (value == null || value.startsWith('--')) throw new Error(`${flag} requires a value`);
    return value;
  }
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--repo-root') opts.repoRoot = path.resolve(takeValue(++i, arg));
    else if (arg === '--codex-home') opts.codexHome = path.resolve(takeValue(++i, arg));
    else if (arg === '--since') opts.sinceMs = parseDate(takeValue(++i, arg), '--since');
    else if (arg === '--until') opts.untilMs = parseDate(takeValue(++i, arg), '--until');
    else if (arg === '--project-pattern') opts.projectPattern = takeValue(++i, arg);
    else if (arg === '--json') opts.json = true;
    else if (arg === '--help' || arg === '-h') opts.help = true;
    else throw new Error(`unknown argument: ${arg}`);
  }
  if (!opts.help && (!opts.repoRoot || !opts.codexHome || !opts.projectPattern)) {
    throw new Error('--repo-root, --codex-home, and --project-pattern require non-empty values');
  }
  if (!opts.help && opts.untilMs != null && opts.untilMs < opts.sinceMs) {
    throw new Error('--until must not be earlier than --since');
  }
  return opts;
}

function pct(numerator, denominator) {
  return denominator === 0 ? '0.0%' : `${(100 * numerator / denominator).toFixed(1)}%`;
}

function printHuman(report) {
  const inv = report.inventory;
  const delivery = report.delivery;
  console.log('Skill delivery evidence (Codex rollouts)');
  console.log(`Inventory: ${inv.skillCount} skills, ${inv.totalChars.toLocaleString()} chars (~${inv.approxTokens.toLocaleString()} tokens), ${inv.explicitOnlyCount} explicit-only`);
  console.log(`Catalog descriptions: ${inv.descriptionChars.toLocaleString()} chars`);
  console.log('\nLargest checked-in source skills:');
  for (const skill of inv.skills.slice(0, 10)) {
    console.log(`  ${skill.name.padEnd(22)} ${skill.totalChars.toLocaleString().padStart(10)} chars  ~${skill.approxTokens.toLocaleString().padStart(8)} tokens`);
  }

  console.log('\nTranscript evidence:');
  console.log(`  ${delivery.rolloutFilesScanned.toLocaleString()} rollout files in mtime window; ${delivery.sessionsScanned.toLocaleString()} matching-project sessions`);
  console.log(`  ${delivery.toolExchangesScanned.toLocaleString()} tool exchanges; ${delivery.targetedExchanges.toLocaleString()} targeted exchanges; ${delivery.attempts.toLocaleString()} skill-path attempts in ${delivery.sessionsWithReadAttempts.toLocaleString()} sessions`);
  for (const name of DELIVERY_CLASSIFICATIONS) {
    const count = delivery.classifications[name];
    console.log(`  ${name.padEnd(24)} ${count.toLocaleString().padStart(6)}  ${pct(count, delivery.attempts)}`);
  }

  const used = delivery.bySkill.filter((row) => row.attempts > 0);
  if (used.length > 0) {
    console.log('\nSkills with read evidence:');
    for (const row of used) {
      const proof = row.classifications.proven_full_current;
      const truncated = row.classifications.tool_output_truncated;
      const truncatedSessions = row.sessionsByClassification.tool_output_truncated;
      console.log(`  ${row.name.padEnd(22)} attempts=${String(row.attempts).padStart(4)} sessions=${String(row.sessions).padStart(3)} proven=${String(proof).padStart(3)} truncated-results=${String(truncated).padStart(3)} truncated-sessions=${String(truncatedSessions).padStart(3)}`);
    }
  }

  console.log('\nInterpretation: exact current-content containment is proof; every non-match is deliberately non-causal.');
}

function usage() {
  return [
    'Usage: node scripts/agent-analytics/skill-delivery.mjs [options]',
    '',
    '  --since <ISO>            rollout file mtime lower bound (default: trailing 30 days)',
    '  --until <ISO>            rollout file mtime upper bound',
    '  --repo-root <path>        repository whose checked-in skills are inventoried',
    '  --codex-home <path>       Codex home containing sessions/ rollouts',
    '  --project-pattern <regex> session cwd filter (default: justsearch)',
    '  --json                   emit the privacy-safe aggregate as JSON',
  ].join('\n');
}

export function main(argv = process.argv.slice(2)) {
  const opts = parseArgs(argv);
  if (opts.help) {
    console.log(usage());
    return;
  }
  const inventory = inventorySkills(opts.repoRoot);
  if (inventory.length === 0) throw new Error(`no source skills found under ${path.join(opts.repoRoot, '.claude', 'skills')}`);

  const raw = listCodexToolExchanges({
    codexHome: opts.codexHome,
    sinceMs: opts.sinceMs,
    untilMs: opts.untilMs,
    projectFilter: new RegExp(opts.projectPattern, 'i'),
  });
  const report = buildSkillDeliveryReport({
    inventory,
    exchanges: raw.exchanges,
    filesScanned: raw.filesScanned,
    sessionsScanned: raw.sessions.length,
    skippedFiles: raw.skipped.length,
    since: new Date(opts.sinceMs).toISOString(),
    until: opts.untilMs == null ? null : new Date(opts.untilMs).toISOString(),
    repoName: path.basename(opts.repoRoot),
  });
  if (opts.json) console.log(JSON.stringify(report, null, 2));
  else printHuman(report);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    console.error(`skill-delivery: ${error.message}`);
    process.exitCode = 1;
  }
}
