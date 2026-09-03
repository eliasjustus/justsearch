#!/usr/bin/env node
/**
 * Offline platform lifecycle evidence check (tempdoc 893 §D.1/P.1).
 *
 * The register is metadata layered over live repository pins. It deliberately has no copied
 * `version` field. A closed adapter set resolves exactly one pin from each named source, while
 * lifecycle evidence remains typed because vendors publish dates, release-relative policies,
 * rolling support, compatibility matrices, or no EOL at all.
 *
 * Structural/schema/pin-resolution errors always fail closed. Lifecycle findings are advisory in
 * report mode; gate mode additionally fails expired support and evidence overdue beyond its grace.
 */
import { appendFileSync, readFileSync } from 'node:fs';
import { isAbsolute, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';

export const REGISTER_PATH = 'governance/platform-lifecycle.v1.json';

const POLICY_KINDS = new Set([
  'fixed-date',
  'release-relative',
  'rolling',
  'compatibility-matrix',
  'no-published-eol',
]);
const SCOPES = new Set(['build', 'runtime', 'distribution', 'integration']);
const ADAPTERS = new Set([
  'gradle-java-toolchain',
  'gradle-wrapper-distribution',
  'toml-version',
  'cargo-lock-package',
  'github-actions-env',
  'kotlin-string-constant',
  'llama-cuda-asset',
  'json-pointer',
]);

function fail(message) {
  throw new Error(message);
}

function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function exactKeys(value, allowed, context) {
  if (!isRecord(value)) fail(`${context} must be an object`);
  for (const key of Object.keys(value)) {
    if (!allowed.includes(key)) fail(`${context} has unknown field \`${key}\``);
  }
}

function requiredString(value, context) {
  if (typeof value !== 'string' || value.trim() === '') fail(`${context} must be a non-empty string`);
}

function integerInRange(value, min, max, context) {
  if (!Number.isInteger(value) || value < min || value > max) {
    fail(`${context} must be an integer from ${min} through ${max}`);
  }
}

function isoDate(value, context) {
  requiredString(value, context);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) fail(`${context} must be an ISO date (YYYY-MM-DD)`);
  const date = new Date(`${value}T00:00:00Z`);
  if (Number.isNaN(date.valueOf()) || date.toISOString().slice(0, 10) !== value) {
    fail(`${context} is not a real calendar date`);
  }
  return date;
}

function httpsUrl(value, context) {
  requiredString(value, context);
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    fail(`${context} must be an absolute HTTPS URL`);
  }
  if (parsed.protocol !== 'https:') fail(`${context} must be an absolute HTTPS URL`);
}

function semanticVersion(value, context) {
  requiredString(value, context);
  if (!/^\d+\.\d+(?:\.\d+)?(?:[-+][0-9A-Za-z.-]+)?$/.test(value)) {
    fail(`${context} must be a semantic major.minor[.patch] version`);
  }
}

function validatePolicy(policy, context) {
  if (!isRecord(policy)) fail(`${context} must be an object`);
  if (!POLICY_KINDS.has(policy.kind)) {
    fail(`${context}.kind must be one of: ${[...POLICY_KINDS].join(', ')}`);
  }
  switch (policy.kind) {
    case 'fixed-date':
      exactKeys(policy, ['kind', 'supportUntil'], context);
      isoDate(policy.supportUntil, `${context}.supportUntil`);
      break;
    case 'release-relative':
      exactKeys(policy, ['kind', 'successorVersion', 'successorObservedOn'], context);
      semanticVersion(policy.successorVersion, `${context}.successorVersion`);
      isoDate(policy.successorObservedOn, `${context}.successorObservedOn`);
      break;
    case 'compatibility-matrix':
      exactKeys(policy, ['kind', 'basis'], context);
      requiredString(policy.basis, `${context}.basis`);
      break;
    case 'rolling':
    case 'no-published-eol':
      exactKeys(policy, ['kind'], context);
      break;
  }
}

function validatePinSource(pinSource, context) {
  exactKeys(pinSource, ['path', 'adapter', 'selector'], context);
  requiredString(pinSource.path, `${context}.path`);
  if (isAbsolute(pinSource.path) || pinSource.path.split(/[\\/]/).includes('..')) {
    fail(`${context}.path must be a repository-relative path without \`..\``);
  }
  if (!ADAPTERS.has(pinSource.adapter)) {
    fail(`${context}.adapter must be one of: ${[...ADAPTERS].join(', ')}`);
  }
  requiredString(pinSource.selector, `${context}.selector`);
  if (pinSource.adapter === 'gradle-java-toolchain' && pinSource.selector !== 'languageVersion') {
    fail(`${context}.selector must be \`languageVersion\` for gradle-java-toolchain`);
  }
  if (pinSource.adapter === 'gradle-wrapper-distribution' && pinSource.selector !== 'distributionUrl') {
    fail(`${context}.selector must be \`distributionUrl\` for gradle-wrapper-distribution`);
  }
  if (pinSource.adapter === 'llama-cuda-asset' && pinSource.selector !== 'llamaCudaAsset') {
    fail(`${context}.selector must be \`llamaCudaAsset\` for llama-cuda-asset`);
  }
  if (pinSource.adapter === 'json-pointer' && !/^\/(?:[^~/]|~[01])+(?:\/(?:[^~/]|~[01])+)*$/.test(pinSource.selector)) {
    fail(`${context}.selector must be a non-root RFC 6901 JSON pointer`);
  }
}

/** Strictly validates the complete register and returns it for convenient composition. */
export function validateRegister(register) {
  exactKeys(register, ['schemaVersion', 'supportWarningDays', 'evidenceGraceDays', 'platforms'], 'register');
  if (register.schemaVersion !== 1) fail('register.schemaVersion must equal 1');
  integerInRange(register.supportWarningDays, 0, 3650, 'register.supportWarningDays');
  integerInRange(register.evidenceGraceDays, 0, 3650, 'register.evidenceGraceDays');
  if (!Array.isArray(register.platforms) || register.platforms.length === 0) {
    fail('register.platforms must be a non-empty array');
  }
  const ids = new Set();
  for (const [index, row] of register.platforms.entries()) {
    const context = `register.platforms[${index}]`;
    exactKeys(
      row,
      [
        'id',
        'name',
        'scope',
        'pinSource',
        'evidenceOwner',
        'sourceUrl',
        'additionalSourceUrls',
        'sourceCheckedOn',
        'reviewBy',
        'policy',
        'note',
      ],
      context,
    );
    requiredString(row.id, `${context}.id`);
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(row.id)) fail(`${context}.id must be lowercase kebab-case`);
    if (ids.has(row.id)) fail(`${context}.id duplicates \`${row.id}\``);
    ids.add(row.id);
    requiredString(row.name, `${context}.name`);
    if (!SCOPES.has(row.scope)) fail(`${context}.scope must be one of: ${[...SCOPES].join(', ')}`);
    validatePinSource(row.pinSource, `${context}.pinSource`);
    requiredString(row.evidenceOwner, `${context}.evidenceOwner`);
    httpsUrl(row.sourceUrl, `${context}.sourceUrl`);
    if (row.additionalSourceUrls !== undefined) {
      if (!Array.isArray(row.additionalSourceUrls) || row.additionalSourceUrls.length === 0) {
        fail(`${context}.additionalSourceUrls must be a non-empty array when present`);
      }
      row.additionalSourceUrls.forEach((url, i) => httpsUrl(url, `${context}.additionalSourceUrls[${i}]`));
    }
    const checked = isoDate(row.sourceCheckedOn, `${context}.sourceCheckedOn`);
    const review = isoDate(row.reviewBy, `${context}.reviewBy`);
    if (review < checked) fail(`${context}.reviewBy must not precede sourceCheckedOn`);
    validatePolicy(row.policy, `${context}.policy`);
    if (row.policy.kind === 'release-relative') {
      const observed = isoDate(row.policy.successorObservedOn, `${context}.policy.successorObservedOn`);
      if (observed > checked) {
        fail(`${context}.policy.successorObservedOn must not follow sourceCheckedOn`);
      }
    }
    if (row.policy.kind === 'compatibility-matrix' && !row.additionalSourceUrls) {
      fail(`${context} compatibility-matrix evidence requires additionalSourceUrls`);
    }
    if (row.note !== undefined) requiredString(row.note, `${context}.note`);
  }
  return register;
}

function singular(matches, context) {
  if (matches.length !== 1) fail(`${context} resolved ${matches.length} pins; expected exactly 1`);
  const pin = matches[0];
  requiredString(pin, `${context} result`);
  return pin;
}

function allMatches(source, expression, capture = 1) {
  return [...source.matchAll(expression)].map((match) => match[capture]);
}

function extractTomlVersion(source, selector, context) {
  let inVersions = false;
  const matches = [];
  for (const line of source.split(/\r?\n/)) {
    const section = line.match(/^\s*\[([^\]]+)]\s*(?:#.*)?$/);
    if (section) {
      inVersions = section[1] === 'versions';
      continue;
    }
    if (!inVersions) continue;
    const entry = line.match(/^\s*(?:"([^"]+)"|([A-Za-z0-9_.-]+))\s*=\s*"([^"]+)"\s*(?:#.*)?$/);
    if (entry && (entry[1] ?? entry[2]) === selector) matches.push(entry[3]);
  }
  return singular(matches, context);
}

function extractCargoLockPackage(source, selector, context) {
  const matches = [];
  for (const block of source.split(/^\s*\[\[package]]\s*$/m).slice(1)) {
    const name = block.match(/^name\s*=\s*"([^"]+)"\s*$/m)?.[1];
    const version = block.match(/^version\s*=\s*"([^"]+)"\s*$/m)?.[1];
    if (name === selector && version) matches.push(version);
  }
  return singular(matches, context);
}

function decodePointerToken(token) {
  return token.replace(/~1/g, '/').replace(/~0/g, '~');
}

function extractJsonPointer(source, selector, context) {
  let value;
  try {
    value = JSON.parse(source);
  } catch (error) {
    fail(`${context} source is malformed JSON: ${error.message}`);
  }
  for (const token of selector.slice(1).split('/').map(decodePointerToken)) {
    if (!isRecord(value) && !Array.isArray(value)) fail(`${context} JSON pointer \`${selector}\` is unresolved`);
    if (!Object.prototype.hasOwnProperty.call(value, token)) {
      fail(`${context} JSON pointer \`${selector}\` is unresolved`);
    }
    value = value[token];
  }
  if (typeof value !== 'string' && typeof value !== 'number') {
    fail(`${context} JSON pointer \`${selector}\` must resolve to a string or number`);
  }
  return String(value);
}

/** Resolve exactly one pin using a named, closed adapter. */
export function extractPin(pinSource, source) {
  const context = `${pinSource.adapter}:${pinSource.path}#${pinSource.selector}`;
  switch (pinSource.adapter) {
    case 'gradle-java-toolchain':
      return singular(
        allMatches(source, /JavaLanguageVersion\.of\(\s*(\d+)\s*\)/g),
        context,
      );
    case 'gradle-wrapper-distribution': {
      const urls = allMatches(source, /^distributionUrl\s*=\s*(\S+)\s*$/gm).map((url) => url.replace(/\\:/g, ':'));
      const url = singular(urls, context);
      const version = /\/gradle-([0-9][0-9A-Za-z.+-]*)-(?:bin|all)\.zip(?:\?.*)?$/.exec(url)?.[1];
      if (!version) fail(`${context} distribution URL does not name a Gradle archive`);
      return version;
    }
    case 'toml-version':
      return extractTomlVersion(source, pinSource.selector, context);
    case 'cargo-lock-package':
      return extractCargoLockPackage(source, pinSource.selector, context);
    case 'github-actions-env':
      return singular(
        allMatches(
          source,
          new RegExp(`^\\s*${pinSource.selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}:\\s*["']?([^"'\\s#]+)["']?\\s*(?:#.*)?$`, 'gm'),
        ),
        context,
      );
    case 'kotlin-string-constant':
      return singular(
        allMatches(
          source,
          new RegExp(`^\\s*val\\s+${pinSource.selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*=\\s*"([^"]+)"\\s*$`, 'gm'),
        ),
        context,
      );
    case 'llama-cuda-asset': {
      const asset = singular(
        allMatches(source, /^\s*val\s+llamaCudaAsset\s*=\s*"([^"]+)"\s*$/gm),
        context,
      );
      const versions = allMatches(asset, /(?:^|-)cuda-(\d+\.\d+)(?:-|$)/g);
      return singular(versions, `${context} CUDA segment`);
    }
    case 'json-pointer':
      return extractJsonPointer(source, pinSource.selector, context);
    default:
      fail(`${context} uses an unsupported adapter`);
  }
}

function daysBetween(from, to) {
  return Math.round((to.valueOf() - from.valueOf()) / 86_400_000);
}

function majorMinor(version, context) {
  const match = /^(\d+)\.(\d+)/.exec(version);
  if (!match) fail(`${context} resolved pin \`${version}\` is not comparable as major.minor`);
  return [Number(match[1]), Number(match[2])];
}

function lineIsBefore(left, right) {
  return left[0] < right[0] || (left[0] === right[0] && left[1] < right[1]);
}

/** Evaluate validated lifecycle metadata against injected source contents and an explicit date. */
export function evaluateRegister(register, { readSource, asOf }) {
  validateRegister(register);
  const today = isoDate(asOf, 'asOf');
  const resolutions = [];
  const findings = [];
  for (const row of register.platforms) {
    const checkedOn = isoDate(row.sourceCheckedOn, `${row.id}.sourceCheckedOn`);
    if (checkedOn > today) {
      fail(`${row.id}: sourceCheckedOn ${row.sourceCheckedOn} is in the future relative to ${asOf}`);
    }
    if (
      row.policy.kind === 'release-relative' &&
      isoDate(row.policy.successorObservedOn, `${row.id}.successorObservedOn`) > today
    ) {
      fail(`${row.id}: successorObservedOn ${row.policy.successorObservedOn} is in the future relative to ${asOf}`);
    }
    let source;
    try {
      source = readSource(row.pinSource.path);
    } catch (error) {
      fail(`${row.id}: cannot read pin source \`${row.pinSource.path}\`: ${error.message}`);
    }
    if (typeof source !== 'string') fail(`${row.id}: pin source reader must return text`);
    const pin = extractPin(row.pinSource, source);
    resolutions.push({ id: row.id, name: row.name, pin, policyKind: row.policy.kind });

    const reviewDays = daysBetween(today, isoDate(row.reviewBy, `${row.id}.reviewBy`));
    if (reviewDays <= -register.evidenceGraceDays) {
      findings.push({
        id: row.id,
        category: 'evidence',
        severity: 'failure',
        message: `evidence review overdue beyond ${register.evidenceGraceDays}-day grace (reviewBy ${row.reviewBy})`,
      });
    } else if (reviewDays <= 0) {
      findings.push({
        id: row.id,
        category: 'evidence',
        severity: 'warning',
        message: `evidence review due or in grace (reviewBy ${row.reviewBy})`,
      });
    }

    if (row.policy.kind === 'fixed-date') {
      const supportDays = daysBetween(today, isoDate(row.policy.supportUntil, `${row.id}.supportUntil`));
      if (supportDays <= 0) {
        findings.push({
          id: row.id,
          category: 'support',
          severity: 'failure',
          message: `published support horizon reached (supportUntil ${row.policy.supportUntil})`,
        });
      } else if (supportDays <= register.supportWarningDays) {
        findings.push({
          id: row.id,
          category: 'support',
          severity: 'warning',
          message: `published support horizon is ${supportDays} day(s) away (supportUntil ${row.policy.supportUntil})`,
        });
      }
    } else if (row.policy.kind === 'release-relative') {
      const liveLine = majorMinor(pin, row.id);
      const successorLine = majorMinor(row.policy.successorVersion, `${row.id}.policy.successorVersion`);
      if (lineIsBefore(liveLine, successorLine)) {
        findings.push({
          id: row.id,
          category: 'support',
          severity: 'failure',
          message:
            `pin ${pin} is superseded by observed successor ${row.policy.successorVersion} ` +
            `(observed ${row.policy.successorObservedOn})`,
        });
      }
    }
  }
  return { asOf, resolutions, findings };
}

export function renderReport(result) {
  const lines = [
    `platform lifecycle evidence as of ${result.asOf}`,
    ...result.resolutions.map((row) => `  ${row.id}: pin=${row.pin}; policy=${row.policyKind}`),
  ];
  if (result.findings.length === 0) lines.push('  findings: none');
  else {
    lines.push('  findings:');
    for (const finding of result.findings) {
      lines.push(`    ${finding.severity.toUpperCase()} [${finding.category}] ${finding.id}: ${finding.message}`);
    }
  }
  return lines.join('\n');
}

export function shouldFail(mode, result) {
  if (mode !== 'report' && mode !== 'gate') fail('mode must be `report` or `gate`');
  return mode === 'gate' && result.findings.some((finding) => finding.severity === 'failure');
}

export function failureFindings(result) {
  return result.findings.filter((finding) => finding.severity === 'failure');
}

function escapeWorkflowCommand(value) {
  return String(value).replaceAll('%', '%25').replaceAll('\r', '%0D').replaceAll('\n', '%0A');
}

export function renderGithubAnnotations(result) {
  return failureFindings(result).map(
    (finding) =>
      `::warning title=Platform lifecycle ${escapeWorkflowCommand(finding.category)}::` +
      `${escapeWorkflowCommand(`${finding.id}: ${finding.message}`)}`,
  );
}

export function renderGithubSummary(result) {
  const failures = failureFindings(result);
  if (failures.length === 0) return '';
  return [
    '### Platform lifecycle attention required',
    '',
    `The advisory check found ${failures.length} failure-level lifecycle finding(s):`,
    '',
    ...failures.map((finding) => `- **${finding.id}** (${finding.category}): ${finding.message}`),
    '',
  ].join('\n');
}

function parseArgs(argv) {
  const options = { mode: 'gate', register: REGISTER_PATH, repoRoot: process.cwd(), asOf: new Date().toISOString().slice(0, 10) };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!['--mode', '--register', '--repo-root', '--as-of'].includes(arg)) fail(`unknown argument \`${arg}\``);
    if (i + 1 >= argv.length) fail(`${arg} requires a value`);
    const value = argv[++i];
    if (arg === '--mode') options.mode = value;
    else if (arg === '--register') options.register = value;
    else if (arg === '--repo-root') options.repoRoot = value;
    else options.asOf = value;
  }
  if (options.mode !== 'report' && options.mode !== 'gate') fail('--mode must be `report` or `gate`');
  return options;
}

function repositoryReader(repoRoot) {
  const absoluteRoot = resolve(repoRoot);
  return (relativePath) => {
    const absolute = resolve(absoluteRoot, relativePath);
    if (absolute !== absoluteRoot && !absolute.startsWith(`${absoluteRoot}${sep}`)) {
      fail(`pin source escapes repository root: ${relativePath}`);
    }
    return readFileSync(absolute, 'utf8');
  };
}

export function runCli(argv) {
  const options = parseArgs(argv);
  const readSource = repositoryReader(options.repoRoot);
  let register;
  try {
    register = JSON.parse(readSource(options.register));
  } catch (error) {
    fail(`cannot parse register \`${options.register}\`: ${error.message}`);
  }
  const result = evaluateRegister(register, { readSource, asOf: options.asOf });
  const output = renderReport(result);
  if (shouldFail(options.mode, result)) {
    console.error(`✗ platform-lifecycle gate FAILED\n${output}`);
    return 1;
  }
  const label = options.mode === 'report' ? 'report' : 'gate';
  const failures = failureFindings(result);
  if (options.mode === 'report' && failures.length > 0) {
    console.warn(`⚠ platform-lifecycle report ATTENTION REQUIRED (${failures.length} failure finding(s))\n${output}`);
    if (process.env.GITHUB_ACTIONS === 'true') {
      for (const annotation of renderGithubAnnotations(result)) console.log(annotation);
      if (process.env.GITHUB_STEP_SUMMARY) {
        appendFileSync(process.env.GITHUB_STEP_SUMMARY, renderGithubSummary(result), 'utf8');
      }
    }
    return 0;
  }
  console.log(`✓ platform-lifecycle ${label} OK\n${output}`);
  return 0;
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : '';
if (invokedPath === import.meta.url) {
  try {
    process.exitCode = runCli(process.argv.slice(2));
  } catch (error) {
    console.error(`✗ platform-lifecycle check INVALID: ${error.message}`);
    process.exitCode = 1;
  }
}
