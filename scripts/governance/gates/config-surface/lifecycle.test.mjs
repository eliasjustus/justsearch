import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { validateConfigLifecycle } from './lifecycle.mjs';

function withFixture(run) {
  const repoRoot = mkdtempSync(path.join(tmpdir(), 'config-lifecycle-'));
  try {
    writeFileSync(path.join(repoRoot, 'evidence.md'), '# Evidence\n');
    const matrix = {
      envRegistryPath: 'EnvRegistry.java',
      configKeyPath: 'ConfigKey.java',
      rows: [
        { declaration: 'EnvRegistry.STABLE', lifecycleStage: 'permanent' },
        { declaration: 'EnvRegistry.PILOT', lifecycleStage: 'experimental' },
      ],
    };
    const overlay = {
      schemaVersion: 1,
      entries: [
        {
          declaration: 'EnvRegistry.PILOT',
          owner: 'Search pipeline',
          rationale: 'Bounded experiment',
          introducedOn: '2026-01-01',
          lastReviewedOn: '2026-08-01',
          reviewBy: '2026-12-01',
          exitCriteria: { promoteWhen: 'Metric is green', removeWhen: 'Metric is red' },
          evidenceLink: 'evidence.md#result',
        },
      ],
    };
    run({ repoRoot, matrix, overlay });
  } finally {
    rmSync(repoRoot, { recursive: true, force: true });
  }
}

function rules(findings) {
  return findings.map((finding) => finding.ruleId);
}

test('accepts exact non-permanent coverage with complete current metadata', () => {
  withFixture(({ repoRoot, matrix, overlay }) => {
    assert.deepEqual(
      validateConfigLifecycle({ matrix, overlay, repoRoot, today: '2026-09-03' }),
      [],
    );
  });
});

test('fails closed on missing, duplicate, and orphan lifecycle metadata', () => {
  withFixture(({ repoRoot, matrix, overlay }) => {
    overlay.entries = [
      { ...overlay.entries[0], declaration: 'EnvRegistry.STABLE' },
      { ...overlay.entries[0], declaration: 'EnvRegistry.STABLE' },
      { ...overlay.entries[0], declaration: 'EnvRegistry.REMOVED' },
    ];
    const result = rules(
      validateConfigLifecycle({ matrix, overlay, repoRoot, today: '2026-09-03' }),
    );
    assert.ok(result.includes('config-surface/lifecycle-metadata-missing'));
    assert.ok(result.includes('config-surface/lifecycle-metadata-duplicate'));
    assert.equal(
      result.filter((rule) => rule === 'config-surface/lifecycle-metadata-orphan').length,
      2,
    );
  });
});

test('fails closed on invalid stages and duplicate declarations', () => {
  withFixture(({ repoRoot, matrix, overlay }) => {
    matrix.rows.push({ declaration: 'EnvRegistry.STABLE', lifecycleStage: 'permanent' });
    matrix.rows.push({ declaration: 'ConfigKey.UNKNOWN', lifecycleStage: 'pilot' });
    const result = rules(
      validateConfigLifecycle({ matrix, overlay, repoRoot, today: '2026-09-03' }),
    );
    assert.ok(result.includes('config-surface/lifecycle-declaration-duplicate'));
    assert.ok(result.includes('config-surface/lifecycle-stage-invalid'));
  });
});

test('fails closed on incomplete, incoherent, overdue, and missing-evidence rows', () => {
  withFixture(({ repoRoot, matrix, overlay }) => {
    Object.assign(overlay.entries[0], {
      owner: '',
      introducedOn: '2026-08-02',
      lastReviewedOn: '2026-08-01',
      reviewBy: '2026-08-31',
      evidenceLink: 'missing.md',
    });
    const result = rules(
      validateConfigLifecycle({ matrix, overlay, repoRoot, today: '2026-09-03' }),
    );
    assert.ok(result.includes('config-surface/lifecycle-metadata-incomplete'));
    assert.ok(result.includes('config-surface/lifecycle-date-incoherent'));
    assert.ok(result.includes('config-surface/lifecycle-evidence-missing'));

    overlay.entries[0].owner = 'Search pipeline';
    overlay.entries[0].introducedOn = '2026-01-01';
    const overdue = rules(
      validateConfigLifecycle({ matrix, overlay, repoRoot, today: '2026-09-03' }),
    );
    assert.ok(overdue.includes('config-surface/lifecycle-review-overdue'));
  });
});

test('rejects malformed top-level input instead of silently skipping validation', () => {
  withFixture(({ repoRoot, matrix }) => {
    assert.deepEqual(
      rules(validateConfigLifecycle({ matrix, overlay: {}, repoRoot, today: '2026-09-03' })),
      ['config-surface/lifecycle-input-malformed'],
    );
  });
});
