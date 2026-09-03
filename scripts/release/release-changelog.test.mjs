import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import {
  ChangelogError,
  checkChangelog,
  composeReleaseBody,
  extractVersion,
  parseChangelog,
  prepareRelease,
} from './release-changelog.mjs';

const PREAMBLE = `# Changelog

All notable changes are documented here.`;

function changelog(sections, footer = '') {
  return `${PREAMBLE}\n\n${sections.join('\n\n')}${footer ? `\n\n${footer}` : ''}\n`;
}

const ENTRY = `### Added
- A useful feature.`;
const CLI = fileURLToPath(new URL('./release-changelog.mjs', import.meta.url));

test('valid structure parses newest-first releases and ignores commented template headings', () => {
  const source = changelog([
    `## [Unreleased]\n\n### Fixed\n- A pending fix.`,
    `## [1.2.0] - 2026-08-02\n\n${ENTRY}`,
    `## [1.1.0] - 2026-08-01\n\n### Fixed\n- An older fix.`,
  ], `<!--\n## [2.0.0-alpha.NN] - YYYY-MM-DD\n-->`);
  const parsed = checkChangelog(source);
  assert.equal(parsed.unreleased.nonEmpty, true);
  assert.deepEqual(parsed.versions.map(({ version }) => version), ['1.2.0', '1.1.0']);
});

test('empty Unreleased is structurally valid', () => {
  const parsed = parseChangelog(changelog([
    '## [Unreleased]',
    `## [1.0.0] - 2026-08-01\n\n${ENTRY}`,
  ]));
  assert.equal(parsed.unreleased.nonEmpty, false);
});

test('extract rejects a missing requested version', () => {
  const source = changelog(['## [Unreleased]', `## [1.0.0] - 2026-08-01\n\n${ENTRY}`]);
  assert.throws(() => extractVersion(source, '1.0.1'), /was not found/);
});

test('duplicate versions fail structural validation', () => {
  const source = changelog([
    '## [Unreleased]',
    `## [1.0.0] - 2026-08-02\n\n${ENTRY}`,
    `## [1.0.0] - 2026-08-01\n\n${ENTRY}`,
  ]);
  assert.throws(() => checkChangelog(source), /duplicate release version: 1\.0\.0/);
});

test('an empty requested release fails closed', () => {
  const source = changelog(['## [Unreleased]', '## [1.0.0] - 2026-08-01']);
  assert.throws(() => extractVersion(source, '1.0.0'), /release version 1\.0\.0 is empty/);
});

test('release preparation rolls Unreleased into an exact dated version and retains footer', () => {
  const footer = `<!--\n## [2.0.0-alpha.NN] - YYYY-MM-DD\n-->`;
  const source = changelog([`## [Unreleased]\n\n${ENTRY}`], footer);
  const prepared = prepareRelease(source, { version: '1.0.0', date: '2026-08-01' });
  const parsed = parseChangelog(prepared);
  assert.equal(parsed.unreleased.nonEmpty, false);
  assert.equal(extractVersion(prepared, '1.0.0'), `${ENTRY}\n`);
  assert.equal(parsed.footer, footer);
});

test('release preparation refuses an empty rollover and an existing version', () => {
  const empty = changelog(['## [Unreleased]', `## [1.0.0] - 2026-08-01\n\n${ENTRY}`]);
  assert.throws(
    () => prepareRelease(empty, { version: '1.1.0', date: '2026-08-02' }),
    /nothing to release/,
  );
  const pending = changelog([
    `## [Unreleased]\n\n### Fixed\n- Pending.`,
    `## [1.0.0] - 2026-08-01\n\n${ENTRY}`,
  ]);
  assert.throws(
    () => prepareRelease(pending, { version: '1.0.0', date: '2026-08-02' }),
    /already exists/,
  );
});

test('composition includes trust notice, exact curated version, and generated appendix', () => {
  const source = changelog([
    '## [Unreleased]',
    `## [1.0.0] - 2026-08-01\n\n${ENTRY}`,
  ]);
  const body = composeReleaseBody({
    markdown: source,
    version: '1.0.0',
    trustNotice: 'Verify the installer checksum before running it.\n',
    generatedNotes: '## What changed\n\n- Full generated list.\n',
  });
  assert.equal(body, `Verify the installer checksum before running it.

## Changes in 1.0.0

${ENTRY}

## Complete change history

## What changed

- Full generated list.
`);
});

test('composition is byte-identical across retries and newline conventions', () => {
  const source = changelog([
    '## [Unreleased]',
    `## [1.0.0] - 2026-08-01\n\n${ENTRY}`,
  ]);
  const args = {
    markdown: source,
    version: '1.0.0',
    trustNotice: 'Trust notice\r\n',
    generatedNotes: 'Generated notes\r\n',
  };
  const first = composeReleaseBody(args);
  const second = composeReleaseBody({ ...args, trustNotice: 'Trust notice\n' });
  assert.equal(first, second);
  assert.equal(Buffer.compare(Buffer.from(first), Buffer.from(second)), 0);
});

test('malformed headings, order drift, dates, and categories fail structural checks', () => {
  const cases = [
    [changelog(['## Unreleased']), /invalid level-two changelog heading/],
    [changelog(['## [Unreleased]', `## [1.0.0] - 2026-02-30\n\n${ENTRY}`]), /invalid release date/],
    [changelog([
      '## [Unreleased]',
      `## [1.0.0] - 2026-08-01\n\n${ENTRY}`,
      `## [1.1.0] - 2026-07-01\n\n${ENTRY}`,
    ]), /must be newest-first/],
    [changelog(['## [Unreleased]', '## [1.0.0] - 2026-08-01\n\n### News\n- Item.']), /unsupported/],
    [changelog(['## [Unreleased]', `## [1.0.0] - 2026-08-01\n\n### Added\n- [Broken](link`]), /invalid inline Markdown link/],
  ];
  for (const [source, expected] of cases) {
    assert.throws(() => checkChangelog(source), expected);
  }
});

test('public failures use the typed changelog error', () => {
  assert.throws(() => checkChangelog('not a changelog'), ChangelogError);
});

test('CLI check, extract, prepare, and compose share the parser contract', () => {
  const dir = mkdtempSync(join(tmpdir(), 'release-changelog-'));
  const changelogPath = join(dir, 'CHANGELOG.md');
  const preparedPath = join(dir, 'prepared.md');
  const trustPath = join(dir, 'trust.md');
  const generatedPath = join(dir, 'generated.md');
  const releaseBodyPath = join(dir, 'release-body.md');
  writeFileSync(changelogPath, changelog([`## [Unreleased]\n\n${ENTRY}`]));
  writeFileSync(trustPath, 'Trust notice.\n');
  writeFileSync(generatedPath, 'Generated notes.\n');

  const run = (...args) => spawnSync(process.execPath, [CLI, ...args], { encoding: 'utf8' });
  const checked = run('check', '--changelog', changelogPath);
  assert.equal(checked.status, 0, checked.stderr);

  const prepared = run(
    'prepare', '--changelog', changelogPath, '--version', '1.0.0',
    '--date', '2026-08-01', '--out', preparedPath,
  );
  assert.equal(prepared.status, 0, prepared.stderr);
  assert.equal(parseChangelog(readFileSync(preparedPath, 'utf8')).unreleased.nonEmpty, false);

  const extracted = run('extract', '--changelog', preparedPath, '--version', '1.0.0');
  assert.equal(extracted.status, 0, extracted.stderr);
  assert.equal(extracted.stdout, `${ENTRY}\n`);

  const composed = run(
    'compose', '--changelog', preparedPath, '--version', '1.0.0',
    '--trust-notice-file', trustPath, '--generated-notes-file', generatedPath,
    '--out', releaseBodyPath,
  );
  assert.equal(composed.status, 0, composed.stderr);
  assert.match(readFileSync(releaseBodyPath, 'utf8'), /## Complete change history/);
});
