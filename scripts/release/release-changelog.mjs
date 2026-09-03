#!/usr/bin/env node
/**
 * Changelog authority for release preparation and GitHub Release body composition.
 *
 * All four operations use the same parser:
 *
 *   node scripts/release/release-changelog.mjs check [--changelog CHANGELOG.md]
 *   node scripts/release/release-changelog.mjs extract --version 1.2.3 [--changelog CHANGELOG.md]
 *   node scripts/release/release-changelog.mjs prepare --version 1.2.3 --date 2026-09-03
 *     [--changelog CHANGELOG.md] [--write | --out prepared.md]
 *   node scripts/release/release-changelog.mjs compose --version 1.2.3
 *     --trust-notice-file trust.md --generated-notes-file generated.md
 *     [--changelog CHANGELOG.md] [--out release-notes.md]
 *
 * `prepare` prints to stdout unless `--write` or `--out` is supplied. `extract` and `compose`
 * likewise print their result unless `--out` is supplied. No operation depends on wall-clock time.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const DEFAULT_CHANGELOG = resolve(REPO_ROOT, 'CHANGELOG.md');
const RELEASE_HEADING = /^## \[([^\]]+)] - (\d{4}-\d{2}-\d{2})$/;
const UNRELEASED_HEADING = /^## \[Unreleased]$/;
const SEMVER = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/;
const ALLOWED_CATEGORIES = new Set([
  'Added',
  'Changed',
  'Deprecated',
  'Removed',
  'Fixed',
  'Security',
]);

export class ChangelogError extends Error {
  constructor(message) {
    super(message);
    this.name = 'ChangelogError';
  }
}

function normalized(text) {
  return String(text).replace(/\r\n?/g, '\n').replace(/^\uFEFF/, '');
}

function stripHtmlComments(text) {
  return text.replace(/<!--[\s\S]*?-->/g, '');
}

/** Separates one or more trailing HTML comments used as changelog authoring guidance. */
function detachFooter(text) {
  let coreEnd = text.length;
  let footerStart = text.length;
  while (true) {
    const prefix = text.slice(0, coreEnd);
    const trimmedEnd = prefix.search(/\s*$/);
    const withoutSpace = prefix.slice(0, trimmedEnd);
    if (!withoutSpace.endsWith('-->')) break;
    const commentStart = withoutSpace.lastIndexOf('<!--');
    if (commentStart < 0) break;
    footerStart = commentStart;
    coreEnd = commentStart;
  }
  return {
    core: text.slice(0, footerStart).trimEnd(),
    footer: text.slice(footerStart).trim(),
  };
}

/** Returns only Markdown-visible text for heading recognition. */
function visibleLines(text) {
  const lines = text.split('\n');
  let inComment = false;
  let fence = null;
  return lines.map((line) => {
    const trimmed = line.trimStart();
    const fenceMatch = trimmed.match(/^(`{3,}|~{3,})/);
    if (!inComment && fenceMatch) {
      const marker = fenceMatch[1][0];
      if (fence === marker) fence = null;
      else if (fence === null) fence = marker;
      return '';
    }
    if (fence !== null) return '';

    let result = '';
    let cursor = 0;
    while (cursor < line.length) {
      if (inComment) {
        const close = line.indexOf('-->', cursor);
        if (close < 0) return result;
        inComment = false;
        cursor = close + 3;
      } else {
        const open = line.indexOf('<!--', cursor);
        if (open < 0) return result + line.slice(cursor);
        result += line.slice(cursor, open);
        inComment = true;
        cursor = open + 4;
      }
    }
    return result;
  });
}

function parseSemver(version) {
  const match = SEMVER.exec(version);
  if (!match) throw new ChangelogError(`invalid release version: ${version}`);
  const prerelease = match[4]?.split('.') ?? [];
  for (const item of prerelease) {
    if (/^\d+$/.test(item) && item.length > 1 && item.startsWith('0')) {
      throw new ChangelogError('numeric prerelease identifiers must not contain leading zeroes');
    }
  }
  return {
    major: Number(match[1]),
    minor: Number(match[2]),
    patch: Number(match[3]),
    prerelease,
  };
}

function comparePrerelease(left, right) {
  if (left.length === 0 && right.length === 0) return 0;
  if (left.length === 0) return 1;
  if (right.length === 0) return -1;
  for (let index = 0; index < Math.max(left.length, right.length); index += 1) {
    if (left[index] === undefined) return -1;
    if (right[index] === undefined) return 1;
    const leftNumeric = /^\d+$/.test(left[index]);
    const rightNumeric = /^\d+$/.test(right[index]);
    if (leftNumeric && rightNumeric) {
      const leftNumber = BigInt(left[index]);
      const rightNumber = BigInt(right[index]);
      if (leftNumber !== rightNumber) return leftNumber > rightNumber ? 1 : -1;
    } else if (leftNumeric !== rightNumeric) {
      return leftNumeric ? -1 : 1;
    } else {
      if (left[index] !== right[index]) return left[index] > right[index] ? 1 : -1;
    }
  }
  return 0;
}

/** SemVer precedence: positive when left is newer than right. */
export function compareSemver(leftVersion, rightVersion) {
  const left = parseSemver(leftVersion);
  const right = parseSemver(rightVersion);
  for (const key of ['major', 'minor', 'patch']) {
    if (left[key] !== right[key]) return Math.sign(left[key] - right[key]);
  }
  return comparePrerelease(left.prerelease, right.prerelease);
}

function assertRealIsoDate(date) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
    throw new ChangelogError(`invalid release date: ${date}`);
  }
  const parsed = new Date(`${date}T00:00:00.000Z`);
  if (Number.isNaN(parsed.valueOf()) || parsed.toISOString().slice(0, 10) !== date) {
    throw new ChangelogError(`invalid release date: ${date}`);
  }
}

function validateSectionBody(section) {
  const visible = stripHtmlComments(section.body);
  const categoryMatches = [...visible.matchAll(/^###\s+(.+)$/gm)];
  const unknown = categoryMatches
    .map((match) => match[1].trim())
    .filter((category) => !ALLOWED_CATEGORIES.has(category));
  if (unknown.length > 0) {
    throw new ChangelogError(
      `${section.label} uses unsupported Keep a Changelog categories: ${unknown.join(', ')}`,
    );
  }
  const categoryNames = categoryMatches.map((match) => match[1].trim());
  const duplicates = categoryNames.filter((name, index) => categoryNames.indexOf(name) !== index);
  if (duplicates.length > 0) {
    throw new ChangelogError(`${section.label} repeats category: ${duplicates[0]}`);
  }
  const hasText = visible.trim().length > 0;
  const hasEntry = /^-\s+\S/m.test(visible);
  if (hasText && (categoryMatches.length === 0 || !hasEntry)) {
    throw new ChangelogError(`${section.label} must contain categorized bullet entries`);
  }
  return hasEntry;
}

function validateInlineLinks(markdown) {
  const lines = visibleLines(markdown);
  for (let index = 0; index < lines.length; index += 1) {
    let cursor = 0;
    while (true) {
      const middle = lines[index].indexOf('](', cursor);
      if (middle < 0) break;
      const open = lines[index].lastIndexOf('[', middle);
      const close = lines[index].indexOf(')', middle + 2);
      const target = close < 0 ? '' : lines[index].slice(middle + 2, close).trim();
      if (open < 0 || close < 0 || !target || /[\u0000-\u001F]/.test(target)) {
        throw new ChangelogError(`invalid inline Markdown link on line ${index + 1}`);
      }
      cursor = close + 1;
    }
  }
}

/**
 * Parses and validates a Keep a Changelog document.
 *
 * Empty Unreleased content is valid. Every released version must have at least one categorized
 * bullet entry. Released sections must be unique and newest-first by SemVer and release date.
 */
export function parseChangelog(markdown) {
  const source = normalized(markdown);
  validateInlineLinks(source);
  const { core, footer } = detachFooter(source);
  const lines = core.split('\n');
  const visible = visibleLines(core);
  const headings = [];

  for (let index = 0; index < visible.length; index += 1) {
    const line = visible[index].trimEnd();
    if (!line.startsWith('## ')) continue;
    if (UNRELEASED_HEADING.test(line)) {
      headings.push({ index, kind: 'unreleased', heading: line });
      continue;
    }
    const releaseMatch = RELEASE_HEADING.exec(line);
    if (!releaseMatch) {
      throw new ChangelogError(`invalid level-two changelog heading on line ${index + 1}: ${line}`);
    }
    headings.push({
      index,
      kind: 'release',
      heading: line,
      version: releaseMatch[1],
      date: releaseMatch[2],
    });
  }

  const unreleasedHeadings = headings.filter((heading) => heading.kind === 'unreleased');
  if (unreleasedHeadings.length !== 1) {
    throw new ChangelogError(
      `expected exactly one ## [Unreleased] heading; found ${unreleasedHeadings.length}`,
    );
  }
  if (headings[0]?.kind !== 'unreleased') {
    throw new ChangelogError('## [Unreleased] must be the first level-two changelog section');
  }

  const sections = headings.map((heading, position) => {
    const end = headings[position + 1]?.index ?? lines.length;
    return {
      ...heading,
      body: lines.slice(heading.index + 1, end).join('\n').trim(),
      label: heading.kind === 'unreleased' ? '[Unreleased]' : `[${heading.version}]`,
    };
  });
  const unreleased = sections[0];
  unreleased.nonEmpty = validateSectionBody(unreleased);

  const versions = sections.slice(1);
  const seenVersions = new Set();
  for (const section of versions) {
    parseSemver(section.version);
    assertRealIsoDate(section.date);
    if (seenVersions.has(section.version)) {
      throw new ChangelogError(`duplicate release version: ${section.version}`);
    }
    seenVersions.add(section.version);
    section.nonEmpty = validateSectionBody(section);
    if (!section.nonEmpty) {
      throw new ChangelogError(`release version ${section.version} is empty`);
    }
  }
  for (let index = 1; index < versions.length; index += 1) {
    const newer = versions[index - 1];
    const older = versions[index];
    if (compareSemver(newer.version, older.version) <= 0) {
      throw new ChangelogError(
        `release versions must be newest-first: ${newer.version} is not newer than ${older.version}`,
      );
    }
    if (newer.date < older.date) {
      throw new ChangelogError(
        `release dates must be newest-first: ${newer.date} precedes ${older.date}`,
      );
    }
  }

  return {
    source,
    preamble: lines.slice(0, headings[0].index).join('\n').trimEnd(),
    footer,
    unreleased,
    versions,
  };
}

export function checkChangelog(markdown) {
  return parseChangelog(markdown);
}

/** Returns the exact requested version's body, ending in one newline. */
export function extractVersion(markdown, version) {
  parseSemver(version);
  const parsed = parseChangelog(markdown);
  const matches = parsed.versions.filter((section) => section.version === version);
  if (matches.length === 0) throw new ChangelogError(`release version ${version} was not found`);
  if (matches.length > 1) throw new ChangelogError(`duplicate release version: ${version}`);
  if (!matches[0].nonEmpty) throw new ChangelogError(`release version ${version} is empty`);
  return `${matches[0].body.trim()}\n`;
}

function renderParsed(parsed, newRelease = null) {
  const chunks = [parsed.preamble, '## [Unreleased]'];
  if (newRelease) {
    chunks.push(`## [${newRelease.version}] - ${newRelease.date}\n\n${newRelease.body.trim()}`);
  }
  for (const section of parsed.versions) {
    chunks.push(`${section.heading}\n\n${section.body.trim()}`);
  }
  if (parsed.footer) chunks.push(parsed.footer);
  return `${chunks.join('\n\n')}\n`;
}

/** Moves all current Unreleased entries into a new, newest release section. */
export function prepareRelease(markdown, { version, date }) {
  parseSemver(version);
  assertRealIsoDate(date);
  const parsed = parseChangelog(markdown);
  if (!parsed.unreleased.nonEmpty) {
    throw new ChangelogError('[Unreleased] is empty; there is nothing to release');
  }
  if (parsed.versions.some((section) => section.version === version)) {
    throw new ChangelogError(`release version ${version} already exists`);
  }
  if (parsed.versions.length > 0) {
    const latest = parsed.versions[0];
    if (compareSemver(version, latest.version) <= 0) {
      throw new ChangelogError(`new release ${version} must be newer than ${latest.version}`);
    }
    if (date < latest.date) {
      throw new ChangelogError(`new release date ${date} precedes ${latest.date}`);
    }
  }
  const prepared = renderParsed(parsed, {
    version,
    date,
    body: parsed.unreleased.body,
  });
  parseChangelog(prepared);
  return prepared;
}

/** Builds byte-stable GitHub Release notes from the three explicit inputs. */
export function composeReleaseBody({ markdown, version, trustNotice, generatedNotes }) {
  const curated = extractVersion(markdown, version).trim();
  const trust = normalized(trustNotice).trim();
  const generated = normalized(generatedNotes).trim();
  if (!trust) throw new ChangelogError('trust notice is empty');
  if (!generated) throw new ChangelogError('generated release notes are empty');
  return [
    trust,
    `## Changes in ${version}\n\n${curated}`,
    `## Complete change history\n\n${generated}`,
  ].join('\n\n') + '\n';
}

function usage() {
  return `usage:
  release-changelog.mjs check [--changelog PATH]
  release-changelog.mjs extract --version X.Y.Z [--changelog PATH] [--out PATH]
  release-changelog.mjs prepare --version X.Y.Z --date YYYY-MM-DD [--changelog PATH] [--write | --out PATH]
  release-changelog.mjs compose --version X.Y.Z --trust-notice-file PATH --generated-notes-file PATH [--changelog PATH] [--out PATH]`;
}

function parseArgs(argv) {
  const [command, ...tokens] = argv;
  if (!command || command === '--help' || command === '-h') return { help: true };
  const options = { command };
  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    if (token === '--write') {
      options.write = true;
      continue;
    }
    if (!token.startsWith('--')) throw new ChangelogError(`unexpected argument: ${token}`);
    const name = token.slice(2).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
    const value = tokens[index + 1];
    if (!value || value.startsWith('--')) throw new ChangelogError(`missing value for ${token}`);
    options[name] = value;
    index += 1;
  }
  return options;
}

function emit(text, options, changelogPath) {
  if (options.write) {
    if (options.out) throw new ChangelogError('--write and --out are mutually exclusive');
    writeFileSync(changelogPath, text, 'utf8');
  } else if (options.out) {
    writeFileSync(resolve(options.out), text, 'utf8');
  } else {
    process.stdout.write(text);
  }
}

export function main(argv = process.argv.slice(2)) {
  try {
    const options = parseArgs(argv);
    if (options.help) {
      console.log(usage());
      return 0;
    }
    if (!['check', 'extract', 'prepare', 'compose'].includes(options.command)) {
      throw new ChangelogError(`unknown command: ${options.command}\n${usage()}`);
    }
    if (options.write && options.command !== 'prepare') {
      throw new ChangelogError('--write is only valid with prepare');
    }
    const changelogPath = resolve(options.changelog ?? DEFAULT_CHANGELOG);
    const markdown = readFileSync(changelogPath, 'utf8');

    if (options.command === 'check') {
      checkChangelog(markdown);
      console.log(`[release-changelog] OK — ${changelogPath}`);
      return 0;
    }
    if (!options.version) throw new ChangelogError('--version is required');
    if (options.command === 'extract') {
      emit(extractVersion(markdown, options.version), options, changelogPath);
      return 0;
    }
    if (options.command === 'prepare') {
      if (!options.date) throw new ChangelogError('--date is required');
      emit(
        prepareRelease(markdown, { version: options.version, date: options.date }),
        options,
        changelogPath,
      );
      return 0;
    }
    if (!options.trustNoticeFile) throw new ChangelogError('--trust-notice-file is required');
    if (!options.generatedNotesFile) {
      throw new ChangelogError('--generated-notes-file is required');
    }
    const trustNotice = readFileSync(resolve(options.trustNoticeFile), 'utf8');
    const generatedNotes = readFileSync(resolve(options.generatedNotesFile), 'utf8');
    emit(
      composeReleaseBody({ markdown, version: options.version, trustNotice, generatedNotes }),
      options,
      changelogPath,
    );
    return 0;
  } catch (error) {
    console.error(`[release-changelog] ERROR — ${error.message}`);
    return 1;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  process.exit(main());
}
