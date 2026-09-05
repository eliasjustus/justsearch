#!/usr/bin/env node
/**
 * Skill inventory + Codex transcript delivery evidence (tempdoc 928).
 *
 * This reader is intentionally asymmetric. An exact copy of today's named
 * harness skill file proves full current-snapshot delivery. A missing
 * match does NOT prove failure because the transcript may predate the current
 * skill revision. Tool truncation, partial intent, and batching are therefore
 * reported as separate observable facts instead of collapsed into a score.
 *
 * Raw tool inputs/outputs are processed in memory and never included in the
 * returned report or written to disk.
 */

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import matter from 'gray-matter';
import yaml from 'js-yaml';
import {
  DEFAULT_CODEX_HOME,
  listCodexToolExchanges,
} from './lib/ledger/codex-adapter.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_REPO_ROOT = path.resolve(HERE, '..', '..');
const DAY_MS = 24 * 60 * 60 * 1000;
const DEFAULT_WINDOW_DAYS = 30;
const SKILL_SURFACES = [
  { harness: 'codex-cli', root: '.agents/skills', kind: 'agents' },
  { harness: 'claude-code', root: '.claude/skills', kind: 'claude' },
];

export const DELIVERY_CLASSIFICATIONS = [
  'proven_full_current',
  'timestamp_indeterminate',
  'tool_output_truncated',
  'partial_intent',
  'ambiguous_batched',
  'missing_output',
  'unproven',
];

function normalizeText(value) {
  return String(value ?? '').replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n');
}

/** Parse skill YAML with the same established dependency used by repository docs tooling. */
export function parseSkillFrontmatter(text) {
  const normalized = normalizeText(text);
  const parsed = matter(normalized);
  return { frontmatter: parsed.data ?? {}, body: normalizeText(parsed.content) };
}

function readIfPresent(file) {
  try {
    return fs.readFileSync(file, 'utf8');
  } catch (error) {
    if (error?.code === 'ENOENT') return null;
    throw error;
  }
}

function gitTrackedPaths(repoRoot) {
  try {
    const output = execFileSync(
      'git',
      ['ls-files', '-z', '--', '.agents/skills', '.claude/skills'],
      { cwd: repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 5000 },
    );
    let changedOutput = null;
    try {
      changedOutput = execFileSync(
        'git',
        ['diff', '--name-only', '-z', 'HEAD', '--', '.agents/skills', '.claude/skills'],
        { cwd: repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 5000 },
      );
    } catch {
      // An unborn repository can have index membership without a HEAD comparison.
    }
    return {
      known: true,
      paths: new Set(output.split('\0').filter(Boolean).map((file) => file.replaceAll('\\', '/'))),
      headComparisonKnown: changedOutput != null,
      changedPaths: new Set((changedOutput ?? '').split('\0').filter(Boolean).map((file) => file.replaceAll('\\', '/'))),
    };
  } catch {
    return { known: false, paths: new Set(), headComparisonKnown: false, changedPaths: new Set() };
  }
}

/** Inventory the independent Codex and Claude skill authorities in this worktree. */
export function inventorySkills(repoRoot = DEFAULT_REPO_ROOT, tracking = null) {
  const tracked = tracking ?? gitTrackedPaths(repoRoot);
  const inventory = [];
  for (const surface of SKILL_SURFACES) {
    const surfaceRoot = path.join(repoRoot, ...surface.root.split('/'));
    let entries;
    try {
      entries = fs.readdirSync(surfaceRoot, { withFileTypes: true });
    } catch (error) {
      if (error?.code === 'ENOENT') continue;
      throw error;
    }

    for (const entry of entries) {
      if (!entry.isDirectory()) continue;
      const name = entry.name;
      const skillFile = path.join(surfaceRoot, name, 'SKILL.md');
      const skillText = readIfPresent(skillFile);
      if (skillText == null) continue;
      const relativePath = path.relative(repoRoot, skillFile).replaceAll('\\', '/');
      const policyFile = surface.harness === 'codex-cli'
        ? path.join(surfaceRoot, name, 'agents', 'openai.yaml')
        : null;
      const policyText = policyFile == null ? null : readIfPresent(policyFile);
      const policyPath = policyFile == null
        ? null
        : path.relative(repoRoot, policyFile).replaceAll('\\', '/');
      const parsed = parseSkillFrontmatter(skillText);
      const normalized = normalizeText(skillText);
      const declaredName = typeof parsed.frontmatter.name === 'string'
        ? parsed.frontmatter.name
        : null;
      const description = typeof parsed.frontmatter.description === 'string'
        ? parsed.frontmatter.description
        : '';
      const metadataErrors = [];
      if (surface.harness === 'codex-cli' && declaredName == null) {
        metadataErrors.push('missing string frontmatter name');
      } else if (declaredName != null && declaredName !== name) {
        metadataErrors.push(`frontmatter name ${JSON.stringify(declaredName)} does not match directory ${JSON.stringify(name)}`);
      }
      if (description.length === 0) metadataErrors.push('missing non-empty string frontmatter description');
      const explicitOnly = surface.harness === 'codex-cli'
        ? yaml.load(policyText ?? '')?.policy?.allow_implicit_invocation === false
        : parsed.frontmatter['disable-model-invocation'] === true;
      const trackedPath = tracked.known ? tracked.paths.has(relativePath) : null;
      const policyTracked = policyPath == null ? null : (tracked.known ? tracked.paths.has(policyPath) : null);
      const effectivePolicyPath = policyText == null && policyTracked !== true ? null : policyPath;
      const currentMatchesHead = tracked.headComparisonKnown
        ? trackedPath === true
          && !tracked.changedPaths.has(relativePath)
          && (effectivePolicyPath == null || (
            policyTracked === true && !tracked.changedPaths.has(effectivePolicyPath)
          ))
        : null;

      inventory.push({
        harness: surface.harness,
        kind: surface.kind,
        root: surface.root,
        name,
        declaredName,
        metadataValid: metadataErrors.length === 0,
        metadataErrors,
        path: relativePath,
        tracked: trackedPath,
        currentMatchesHead,
        policyPath: effectivePolicyPath,
        policyTracked,
        description,
        descriptionChars: description.length,
        bodyChars: parsed.body.length,
        totalChars: normalized.length,
        approxTokens: Math.ceil(normalized.length / 4),
        explicitOnly,
        text: normalized,
      });
    }
  }
  return inventory.sort((a, b) => a.harness.localeCompare(b.harness) || a.name.localeCompare(b.name));
}

/**
 * Find locally inventoried skill-path syntax in a tool input. Repeated separators support
 * JavaScript command strings whose Windows paths contain escaped backslashes.
 */
export function findSkillTargets(input) {
  const targets = [];
  const seen = new Set();
  const regex = /(\.claude|\.agents)[\\/]+skills[\\/]+([A-Za-z0-9._-]+)[\\/]+SKILL\.md/gi;
  let match;
  while ((match = regex.exec(String(input ?? ''))) !== null) {
    const kind = match[1].toLowerCase() === '.agents' ? 'agents' : 'claude';
    const harness = kind === 'agents' ? 'codex-cli' : 'claude-code';
    const name = match[2];
    const key = `${kind}:${name.toLowerCase()}`;
    if (seen.has(key)) continue;
    seen.add(key);
    targets.push({ kind, harness, name });
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

export function classifySkillAttempt({
  input,
  outputText,
  missingOutput,
  outputTimestampUnknown = false,
  expectedText,
  targetCount = 1,
}) {
  const output = normalizeText(outputText);
  const expected = normalizeText(expectedText);
  if (outputTimestampUnknown) return 'timestamp_indeterminate';
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
    harness: skill.harness,
    root: skill.root,
    name: skill.name,
    declaredName: skill.declaredName,
    metadataValid: skill.metadataValid,
    metadataErrors: skill.metadataErrors,
    path: skill.path,
    tracked: skill.tracked,
    currentMatchesHead: skill.currentMatchesHead,
    policyPath: skill.policyPath,
    policyTracked: skill.policyTracked,
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
  sessionsScanned = 0,
  skippedFiles = 0,
  fragmentsDiscovered = 0,
  fragmentsContributing = 0,
  unreadableFragments = 0,
  malformedLines = 0,
  untimestampedExchanges = 0,
  untimestampedOutputs = 0,
  duplicateExchangeCopies = 0,
  conflictingExchangeCopies = 0,
  sourceRootsAvailable = null,
  sourceRootsMissing = null,
  sourceRootErrorCount = null,
  since = null,
  until = null,
  repoName = null,
  projectPattern = null,
} = {}) {
  const keyOf = (harness, name) => `${harness}:${name.toLowerCase()}`;
  const byKey = new Map(inventory.map((skill) => [keyOf(skill.harness, skill.name), skill]));
  const classifications = emptyClassificationCounts();
  const classificationSessions = Object.fromEntries(
    DELIVERY_CLASSIFICATIONS.map((name) => [name, new Set()]),
  );
  const readSessions = new Set();
  const perSkill = new Map(inventory.map((skill) => [keyOf(skill.harness, skill.name), {
    harness: skill.harness,
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
    const targets = allTargets.filter((target) => byKey.has(keyOf(target.harness, target.name)));
    if (targets.length === 0) continue;
    targetedExchanges += 1;
    if (exchange.sessionId) readSessions.add(exchange.sessionId);

    for (const target of targets) {
      const skill = byKey.get(keyOf(target.harness, target.name));
      const classification = classifySkillAttempt({
        input: exchange.input,
        outputText: exchange.outputText,
        missingOutput: exchange.missingOutput,
        outputTimestampUnknown: exchange.outputTimestampUnknown,
        expectedText: skill.text,
        targetCount: allTargets.length,
      });
      attempts += 1;
      classifications[classification] += 1;
      if (exchange.sessionId) classificationSessions[classification].add(exchange.sessionId);
      const row = perSkill.get(keyOf(skill.harness, skill.name));
      row.attempts += 1;
      row.classifications[classification] += 1;
      if (exchange.sessionId) row.classificationSessions[classification].add(exchange.sessionId);
      if (exchange.sessionId) row.sessions.add(exchange.sessionId);
    }
  }

  const safeInventory = inventory.map(publicInventoryItem);
  const surfaceStats = SKILL_SURFACES.map((surface) => {
    const skills = safeInventory.filter((skill) => skill.harness === surface.harness);
    const trackedSkills = skills.filter((skill) => skill.tracked === true);
    const trackingKnown = skills.every((skill) => skill.tracked !== null);
    const headComparisonKnown = skills.every((skill) => skill.currentMatchesHead !== null);
    const checkedInSkills = skills.filter((skill) => skill.currentMatchesHead === true);
    return {
      harness: surface.harness,
      root: surface.root,
      trackingKnown,
      presentSkillCount: skills.length,
      trackedSkillCount: trackingKnown ? trackedSkills.length : null,
      untrackedSkillCount: trackingKnown ? skills.length - trackedSkills.length : null,
      invalidMetadataSkillCount: skills.filter((skill) => !skill.metadataValid).length,
      headComparisonKnown,
      checkedInSkillCount: headComparisonKnown ? checkedInSkills.length : null,
      modifiedTrackedSkillCount: headComparisonKnown
        ? trackedSkills.filter((skill) => skill.currentMatchesHead === false).length
        : null,
      presentTotalChars: skills.reduce((sum, skill) => sum + skill.totalChars, 0),
      currentTotalCharsOnTrackedPaths: trackingKnown
        ? trackedSkills.reduce((sum, skill) => sum + skill.totalChars, 0)
        : null,
      checkedInTotalChars: headComparisonKnown
        ? checkedInSkills.reduce((sum, skill) => sum + skill.totalChars, 0)
        : null,
      presentDescriptionChars: skills.reduce((sum, skill) => sum + skill.descriptionChars, 0),
      currentDescriptionCharsOnTrackedPaths: trackingKnown
        ? trackedSkills.reduce((sum, skill) => sum + skill.descriptionChars, 0)
        : null,
      checkedInDescriptionChars: headComparisonKnown
        ? checkedInSkills.reduce((sum, skill) => sum + skill.descriptionChars, 0)
        : null,
      presentCatalogFieldCharsLowerBound: skills.reduce(
        (sum, skill) => sum + skill.name.length + skill.path.length + skill.descriptionChars,
        0,
      ),
      currentCatalogFieldCharsOnTrackedPathsLowerBound: trackingKnown
        ? trackedSkills.reduce(
          (sum, skill) => sum + skill.name.length + skill.path.length + skill.descriptionChars,
          0,
        )
        : null,
      checkedInCatalogFieldCharsLowerBound: headComparisonKnown
        ? checkedInSkills.reduce(
          (sum, skill) => sum + skill.name.length + skill.path.length + skill.descriptionChars,
          0,
        )
        : null,
      presentExplicitOnlyCount: skills.filter((skill) => skill.explicitOnly).length,
      currentExplicitOnlyCountOnTrackedPaths: trackingKnown
        ? trackedSkills.filter((skill) => skill.explicitOnly).length
        : null,
      checkedInExplicitOnlyCount: headComparisonKnown
        ? checkedInSkills.filter((skill) => skill.explicitOnly).length
        : null,
    };
  });
  const trackingKnown = safeInventory.every((skill) => skill.tracked !== null);
  const trackedSkills = safeInventory.filter((skill) => skill.tracked === true);

  return {
    schemaVersion: 2,
    scope: {
      harness: 'codex-cli',
      repoName,
      projectPattern,
      transcriptWindow: { basis: 'exchange-start-event-time', snapshotAsOfUntil: true, since, until },
      proofTarget: 'current-local-harness-skill-file',
    },
    inventory: {
      snapshot: 'current-working-tree',
      gitMembership: 'index',
      trackingKnown,
      presentSkillCount: safeInventory.length,
      trackedSkillCount: trackingKnown ? trackedSkills.length : null,
      untrackedSkillCount: trackingKnown ? safeInventory.length - trackedSkills.length : null,
      surfaces: surfaceStats,
      skills: safeInventory.sort((a, b) => (
        a.harness.localeCompare(b.harness)
        || b.totalChars - a.totalChars
        || a.name.localeCompare(b.name)
      )),
    },
    delivery: {
      rolloutFragmentsDiscovered: fragmentsDiscovered,
      rolloutFragmentsContributing: fragmentsContributing,
      sessionsScanned,
      skippedFiles,
      unreadableFragments,
      malformedLines,
      untimestampedExchanges,
      untimestampedOutputs,
      duplicateExchangeCopies,
      conflictingExchangeCopies,
      sourceRootsAvailable,
      sourceRootsMissing,
      sourceRootErrorCount,
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
          harness: row.harness,
          name: row.name,
          attempts: row.attempts,
          sessions: row.sessions.size,
          classifications: row.classifications,
          sessionsByClassification: Object.fromEntries(
            DELIVERY_CLASSIFICATIONS.map((name) => [name, row.classificationSessions[name].size]),
          ),
        }))
        .sort((a, b) => (
          b.attempts - a.attempts
          || a.harness.localeCompare(b.harness)
          || a.name.localeCompare(b.name)
        )),
    },
    limitations: [
      'Exact containment proves delivery only for the current local harness-specific skill file; historical revision drift remains unproven.',
      'An explicit tool-output truncation proves the combined result was capped, not which target section was omitted.',
      'Tool selection and tool-result delivery do not prove model attention, rule adherence, or task correctness.',
      'This slice audits Codex rollouts only; it makes no Claude delivery-parity claim.',
      'A Codex transcript read of .agents or .claude content is tool-read evidence, not native skill-loader selection telemetry.',
      'Multiple partial/windowed reads are not reconstructed into cumulative coverage; they may collectively cover a full historical skill.',
      'Fixed event-time windows are append-stable, but source rollout deletion or retroactive editing can still change a rerun.',
      'Outputs without a parseable timestamp are retained but classified as timestamp_indeterminate because their as-of eligibility cannot be proven.',
      'Conflicting copies of the same session/call/start identity are quarantined rather than selected arbitrarily.',
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
  for (const surface of inv.surfaces) {
    const tracked = surface.trackingKnown
      ? `${surface.trackedSkillCount} index-tracked, ${surface.untrackedSkillCount} untracked`
      : 'Git tracking unknown';
    const checkedIn = surface.headComparisonKnown
      ? `${surface.checkedInSkillCount} current files match HEAD, ${surface.modifiedTrackedSkillCount} tracked paths modified`
      : 'HEAD comparison unknown';
    console.log(`Inventory ${surface.harness}: ${surface.presentSkillCount} present (${tracked}; ${checkedIn}; ${surface.invalidMetadataSkillCount} invalid metadata), ${surface.presentTotalChars.toLocaleString()} current chars, ${surface.presentDescriptionChars.toLocaleString()} description chars, >=${surface.presentCatalogFieldCharsLowerBound.toLocaleString()} catalog-field chars`);
  }
  console.log('\nLargest present skills by harness:');
  for (const surface of inv.surfaces) {
    console.log(`  ${surface.harness} (${surface.root})`);
    for (const skill of inv.skills.filter((row) => row.harness === surface.harness).slice(0, 10)) {
      const status = skill.tracked === null
        ? 'Git status ?'
        : (!skill.tracked ? 'untracked' : (skill.currentMatchesHead ? 'matches HEAD' : 'modified/index-only'));
      console.log(`    ${skill.name.padEnd(22)} ${skill.totalChars.toLocaleString().padStart(10)} chars  ~${skill.approxTokens.toLocaleString().padStart(8)} tokens  ${status}`);
    }
  }

  console.log('\nTranscript evidence:');
  console.log(`  ${delivery.rolloutFragmentsContributing.toLocaleString()} of ${delivery.rolloutFragmentsDiscovered.toLocaleString()} active/archive fragments present as of the cutoff contributed; ${delivery.sessionsScanned.toLocaleString()} matching-project sessions`);
  console.log(`  ${delivery.toolExchangesScanned.toLocaleString()} tool exchanges; ${delivery.targetedExchanges.toLocaleString()} targeted exchanges; ${delivery.attempts.toLocaleString()} skill-path attempts in ${delivery.sessionsWithReadAttempts.toLocaleString()} sessions`);
  console.log(`  source roots available=${delivery.sourceRootsAvailable}; missing=${delivery.sourceRootsMissing}; errors=${delivery.sourceRootErrorCount}`);
  console.log(`  unreadable fragments=${delivery.unreadableFragments.toLocaleString()}; malformed lines=${delivery.malformedLines.toLocaleString()}; omitted untimestamped starts=${delivery.untimestampedExchanges.toLocaleString()}; indeterminate untimestamped outputs=${delivery.untimestampedOutputs.toLocaleString()}`);
  console.log(`  duplicate copies=${delivery.duplicateExchangeCopies.toLocaleString()}; quarantined conflicting copies=${delivery.conflictingExchangeCopies.toLocaleString()}`);
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
      console.log(`  ${(row.harness + ':' + row.name).padEnd(34)} attempts=${String(row.attempts).padStart(4)} sessions=${String(row.sessions).padStart(3)} proven=${String(proof).padStart(3)} truncated-results=${String(truncated).padStart(3)} truncated-sessions=${String(truncatedSessions).padStart(3)}`);
    }
  }

  console.log('\nInterpretation: exact current-content containment is proof; every non-match is deliberately non-causal.');
}

function usage() {
  return [
    'Usage: node scripts/agent-analytics/skill-delivery.mjs [options]',
    '',
    '  --since <ISO>            exchange-start lower bound (default: trailing 30 days)',
    '  --until <ISO>            exchange-start upper bound and transcript as-of time',
    '  --repo-root <path>        repository whose native skill trees are inventoried',
    '  --codex-home <path>       Codex home containing active/archived rollouts',
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
  if (inventory.length === 0) throw new Error(`no skills found under .agents/skills or .claude/skills in ${opts.repoRoot}`);

  const raw = listCodexToolExchanges({
    codexHome: opts.codexHome,
    sinceMs: opts.sinceMs,
    untilMs: opts.untilMs,
    projectFilter: new RegExp(opts.projectPattern, 'i'),
  });
  if (raw.sourceRootsAvailable === 0) {
    throw new Error(`no readable Codex rollout roots under ${opts.codexHome}`);
  }
  const report = buildSkillDeliveryReport({
    inventory,
    exchanges: raw.exchanges,
    fragmentsDiscovered: raw.fragmentsDiscovered,
    fragmentsContributing: raw.fragmentsContributing,
    sessionsScanned: raw.sessions.length,
    skippedFiles: raw.skipped.length,
    unreadableFragments: raw.unreadableFragments,
    malformedLines: raw.malformedLines,
    untimestampedExchanges: raw.untimestampedExchanges,
    untimestampedOutputs: raw.untimestampedOutputs,
    duplicateExchangeCopies: raw.duplicateExchangeCopies,
    conflictingExchangeCopies: raw.conflictingExchangeCopies,
    sourceRootsAvailable: raw.sourceRootsAvailable,
    sourceRootsMissing: raw.sourceRootsMissing,
    sourceRootErrorCount: raw.sourceRootErrors.length,
    since: new Date(opts.sinceMs).toISOString(),
    until: opts.untilMs == null ? null : new Date(opts.untilMs).toISOString(),
    repoName: path.basename(opts.repoRoot),
    projectPattern: opts.projectPattern,
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
