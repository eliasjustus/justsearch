import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { GENERATORS, runRegenSet, selectGenerators } from './regen-all.mjs';
import {
  renderHooksBlock,
  renderLocalExample,
  renderPublicSettings,
} from '../codegen/gen-agent-hooks-wiring.mjs';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

/** A spawnSync stand-in that replays the given results and records the calls. */
function recorder(...results) {
  const calls = [];
  return {
    calls,
    run(command, args, options) {
      calls.push({ command, args, options });
      return results.shift() ?? { status: 0 };
    },
  };
}

const clean = [
  { id: 'alpha', script: 'scripts/codegen/gen-alpha.mjs', args: [], source: 'a.json' },
  { id: 'beta', script: 'scripts/codegen/gen-beta.mjs', args: ['--flag'], source: 'b.json' },
];

// No generator drifts -> exit 0, every generator run, each with --check appended.
{
  const fixture = recorder({ status: 0 }, { status: 0 });
  const result = runRegenSet({ generators: clean, run: fixture.run });
  assert.deepEqual(result, { status: 0, failed: null });
  assert.equal(fixture.calls.length, 2);
  assert.match(fixture.calls[0].args[0], /gen-alpha\.mjs$/);
  assert.equal(fixture.calls[0].args.at(-1), '--check');
  assert.deepEqual(fixture.calls[1].args.slice(1), ['--flag', '--check'], 'per-generator args are kept');
}

// One generator drifts -> that child's status is the runner's status, and the set STOPS there.
{
  const fixture = recorder({ status: 3 }, { status: 0 });
  const result = runRegenSet({ generators: clean, run: fixture.run });
  assert.deepEqual(result, { status: 3, failed: 'alpha' });
  assert.equal(fixture.calls.length, 1, 'a later generator must not mask the first drift');
}

// A drift in a LATER generator still propagates (the first one passing is not a pass for the set).
{
  const fixture = recorder({ status: 0 }, { status: 5 });
  const result = runRegenSet({ generators: clean, run: fixture.run });
  assert.deepEqual(result, { status: 5, failed: 'beta' });
  assert.equal(fixture.calls.length, 2);
}

// A spawn that never ran is a failure, not a pass: `status` is null when `error` is set.
{
  const fixture = recorder({ status: null, error: new Error('spawn failed') });
  const result = runRegenSet({ generators: clean, run: fixture.run });
  assert.deepEqual(result, { status: 1, failed: 'alpha' });
}

// Regenerate mode omits --check so the generator writes instead of comparing.
{
  const fixture = recorder({ status: 0 }, { status: 0 });
  runRegenSet({ generators: clean, run: fixture.run, check: false });
  assert.ok(!fixture.calls[0].args.includes('--check'));
  assert.ok(!fixture.calls[1].args.includes('--check'));
}

// The real set must name real generators. A renamed or deleted generator would otherwise turn the
// whole regen set into a silent pass on a spawn error nobody reads.
{
  assert.ok(GENERATORS.length >= 8, `expected the full regen set, got ${GENERATORS.length}`);
  const ids = new Set();
  for (const gen of GENERATORS) {
    assert.ok(!ids.has(gen.id), `duplicate generator id ${gen.id}`);
    ids.add(gen.id);
    assert.ok(
      existsSync(join(REPO_ROOT, gen.script)),
      `regen-all names a generator that does not exist: ${gen.script}`,
    );
  }
}

// --only / --except are a LANE SELECTOR. A typo must abort, not silently select nothing: a
// `--only notice` that quietly ran zero generators would report the set as green.
{
  assert.deepEqual(
    selectGenerators(['--check', '--only', 'beta'], clean).map((g) => g.id),
    ['beta'],
  );
  assert.deepEqual(
    selectGenerators(['--check', '--except=beta'], clean).map((g) => g.id),
    ['alpha'],
  );
  assert.deepEqual(selectGenerators(['--check'], clean).map((g) => g.id), ['alpha', 'beta']);
  assert.throws(() => selectGenerators(['--only', 'typo'], clean), /no generator with id `typo`/);
  assert.throws(() => selectGenerators(['--except', 'typo'], clean), /no generator with id `typo`/);
}

// The one entry the CI lanes split on must exist under that exact id.
assert.ok(
  GENERATORS.some((g) => g.id === 'notices'),
  'ci.yml selects the license-report lane with `--only notices`',
);

// The Claude generator owns two different tracked projections. Public settings
// keep native deny rules and universal hooks only; the local example contains
// the complete binding set without carrying a local allow/ask/env posture into
// the public file.
{
  const manifest = {
    hookDir: 'scripts/agent-analytics/hooks',
    catalog: {
      'compact-restore': { file: 'compact-restore.mjs' },
      dispatch: { file: 'dispatch.mjs' },
    },
    bindings: {
      SessionStart: [
        { hooks: [{ hookId: 'compact-restore', timeout: 5 }] },
        { hooks: [{ hookId: 'dispatch', timeout: 5, async: true }] },
      ],
    },
  };
  const currentPublic = {
    worktree: { baseRef: 'head' },
    permissions: {
      allow: ['Bash(*)'],
      deny: ['Bash(git push --force*)'],
      ask: ['Read(*)'],
    },
    env: { LOCAL_ONLY: 'secret' },
    hooks: { OldEvent: [] },
  };

  const publicProjection = JSON.parse(renderPublicSettings(manifest, currentPublic));
  assert.deepEqual(publicProjection.permissions, { deny: ['Bash(git push --force*)'] });
  assert.equal('env' in publicProjection, false);
  assert.equal(JSON.stringify(publicProjection.hooks).includes('compact-restore.mjs'), true);
  assert.equal(JSON.stringify(publicProjection.hooks).includes('dispatch.mjs'), false);

  const localProjection = JSON.parse(renderLocalExample(manifest));
  assert.equal(JSON.stringify(localProjection.hooks).includes('compact-restore.mjs'), true);
  assert.equal(JSON.stringify(localProjection.hooks).includes('dispatch.mjs'), true);
  assert.deepEqual(localProjection.permissions.deny, [
    'Bash(git push --force*)',
    'Bash(git push -f*)',
  ]);
  assert.deepEqual(renderHooksBlock(manifest, new Set(['dispatch'])), publicProjection.hooks);
}

console.log(`regen-all.test: all checks passed (${GENERATORS.length} generators enumerated)`);
