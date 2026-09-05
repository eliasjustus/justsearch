#!/usr/bin/env node
/**
 * Generate Claude's tracked public/local-example hook projections from the
 * single-authority manifest governance/agent-hooks.v1.json.
 *
 * The manifest is the ONE source of hook wiring. This script projects its `bindings`
 * into the settings file, emitting every command in the cwd-invariant exec-form
 *   { "type":"command", "command":"node", "args":["${CLAUDE_PROJECT_DIR}/<hookDir>/<file>.mjs"], ... }
 * so a cwd-relative path (the tempdoc-592 crash class) is UNREPRESENTABLE — you cannot
 * hand-write a path the generator never emits. The hook-integrity gate then verifies the
 * generated wiring loads and (for blocking hooks) bites.
 *
 * Only the `hooks` key of settings is rewritten; all other keys (env/permissions/mcp) are
 * preserved verbatim. The wiring law (every binding's hookId resolves to a catalog entry
 * whose file exists) is validated HERE, at generation — not re-derived downstream.
 *
 * Usage:
 *   node scripts/codegen/gen-agent-hooks-wiring.mjs          # regenerate tracked public + local-example projections
 *   node scripts/codegen/gen-agent-hooks-wiring.mjs --check  # fail when either tracked projection drifts
 *   node scripts/codegen/gen-agent-hooks-wiring.mjs --emit-local-example
 *   node scripts/codegen/gen-agent-hooks-wiring.mjs --emit-public-template
 *
 * The last two flags remain compatibility aliases for focused regeneration.
 * An existing ignored settings.local.json is also regenerated/checked without
 * creating one in a public checkout.
 */

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = join(__dirname, '..', '..');

const MANIFEST = join(REPO_ROOT, 'governance', 'agent-hooks.v1.json');
const LOCAL_SETTINGS = join(REPO_ROOT, '.claude', 'settings.local.json');

// The tracked public projection contains universal hooks and a deny-only
// permission posture. Founder-local analytics remain in the local example.
const PUBLIC_SETTINGS = join(REPO_ROOT, '.claude', 'settings.json');

// The committable maintainer re-wire seed (631 #2): FULL hooks + documented permissions/env stubs.
// Committed (not gitignored) so a fresh maintainer clone has a seed; copying it to settings.local.json
// (which IS gitignored) restores the founder-analytics wiring the public template intentionally drops.
const LOCAL_EXAMPLE_OUT = join(REPO_ROOT, '.claude', 'settings.local.json.example');

// Founder-local-analytics hooks EXCLUDED from the public template (go-public item 2 / G3 /
// the "present-but-opt-in, not imposed" finding): each depends on founder-only infra (the
// justsearch-dev MCP, the local OTLP telemetry sink, the agent-analytics dispatch pipeline) and
// would auto-impose on every contributor who opens the public repo in Claude Code. The
// universally-safe discipline guards + hints stay wired, so the published guards are LIVE, not
// inert — transparency without imposition. (Schema is strict, so this policy lives here, not as a
// manifest `public:` field; mirror any new founder-analytics hook into this set.)
const PUBLIC_EXCLUDED_HOOKS = new Set([
  'dispatch', 'export-session-env', 'otlp-sink-ensure', 'mcp-session-inject',
]);

/** Read + validate the manifest. `manifest` is injectable so the wiring law is unit-testable. */
function readManifest(manifest = JSON.parse(readFileSync(MANIFEST, 'utf8'))) {
  if (manifest.kind !== 'agent-hooks-manifest.v1') {
    throw new Error(`manifest.kind must be 'agent-hooks-manifest.v1' (got ${manifest.kind})`);
  }
  const hookDir = manifest.hookDir;
  if (typeof hookDir !== 'string' || !hookDir) throw new Error('manifest.hookDir is required');
  const catalog = manifest.hooks ?? {};
  const bindings = manifest.bindings ?? {};

  // Wiring law: every binding's hookId resolves to a catalog entry whose file exists.
  for (const [event, groups] of Object.entries(bindings)) {
    for (const group of groups) {
      for (const h of group.hooks ?? []) {
        const entry = catalog[h.hookId];
        if (!entry) {
          throw new Error(`binding ${event} references unknown hookId '${h.hookId}' (not in manifest.hooks)`);
        }
        const file = join(REPO_ROOT, hookDir, entry.file);
        if (!existsSync(file)) {
          throw new Error(`hook '${h.hookId}' file not found: ${relative(REPO_ROOT, file)}`);
        }
      }
    }
  }
  return { hookDir, catalog, bindings };
}

/** Project one binding hook into a settings command entry (cwd-invariant exec-form). */
function renderHookEntry(hookDir, catalog, b) {
  const out = {
    type: 'command',
    command: 'node',
    args: ['${CLAUDE_PROJECT_DIR}/' + hookDir + '/' + catalog[b.hookId].file],
  };
  if ('if' in b) out.if = b.if;
  if ('timeout' in b) out.timeout = b.timeout;
  if ('async' in b) out.async = b.async;
  if ('asyncRewake' in b) out.asyncRewake = b.asyncRewake;
  return out;
}

/**
 * Build the settings `hooks` block from the manifest bindings.
 * `exclude` (a Set of hookIds) drops founder-local hooks for the public template; empty groups
 * and empty events are pruned so the public wiring contains only what it actually wires. The
 * default (empty set) renders ALL hooks — so the local settings.local.json path + `--check` are
 * byte-identical to before.
 */
function renderHooksBlock({ hookDir, catalog, bindings }, exclude = new Set()) {
  const hooks = {};
  for (const [event, groups] of Object.entries(bindings)) {
    const renderedGroups = [];
    for (const g of groups) {
      const entries = (g.hooks ?? [])
        .filter((b) => !exclude.has(b.hookId))
        .map((b) => renderHookEntry(hookDir, catalog, b));
      if (entries.length === 0) continue; // group emptied by the public filter
      const group = {};
      if ('matcher' in g) group.matcher = g.matcher;
      group.hooks = entries;
      renderedGroups.push(group);
    }
    if (renderedGroups.length > 0) hooks[event] = renderedGroups; // event with no surviving hooks
  }
  return hooks;
}

/** Produce the full settings.local.json content with a regenerated `hooks` block. */
function renderSettings(manifest, currentSettings) {
  const next = { ...currentSettings, hooks: renderHooksBlock(manifest) };
  return JSON.stringify(next, null, 2) + '\n';
}

/**
 * Compose the tracked public settings.json: preserve public worktree/MCP/plugin
 * configuration and native deny rules, remove local env/allow/ask posture, and
 * project only universally safe hooks.
 */
function renderPublicSettings(manifest, currentSettings = JSON.parse(readFileSync(PUBLIC_SETTINGS, 'utf8'))) {
  const safeBase = { ...currentSettings };
  // Publish the `deny` rules (tempdoc 930 row 4 moved force-push protection off a PreToolUse
  // guard onto native permissions — dropping them at cutover would leave the retirement with a
  // hole), but never an allow/ask posture inherited from local config.
  const deny = safeBase.permissions?.deny;
  if (Array.isArray(deny) && deny.length > 0) safeBase.permissions = { deny };
  else delete safeBase.permissions;
  delete safeBase.env; // never publish founder-local env
  return JSON.stringify(
    { ...safeBase, hooks: renderHooksBlock(manifest, PUBLIC_EXCLUDED_HOOKS) }, null, 2) + '\n';
}

/**
 * Compose the maintainer re-wire SEED (.claude/settings.local.json.example, 631 #2): the public base
 * (worktree/mcp/plugins) + the FULL hooks block (no exclusions — the founder-analytics hooks the public
 * template drops are present here, which is what re-enables session attribution/telemetry) + empty
 * permissions/env stubs a maintainer fills in per-machine. JSON has no comments, so the copy+customize
 * step is documented in MAINTAINING.md; the stubs just mark where the per-machine posture goes.
 */
function renderLocalExample(manifest) {
  const base = { ...JSON.parse(readFileSync(PUBLIC_SETTINGS, 'utf8')) };
  delete base.hooks; // regenerated below as the full set
  base.permissions = { allow: [], deny: [], ask: [], ...(base.permissions ?? {}) };
  base.env = base.env ?? {};
  return JSON.stringify({ ...base, hooks: renderHooksBlock(manifest) }, null, 2) + '\n';
}

/**
 * Resolve the base for an existing maintainer-local projection. Precedence:
 * local settings → committed example → public settings. The generator never
 * creates the ignored local file implicitly.
 */
function loadLocalSettings() {
  if (existsSync(LOCAL_SETTINGS)) return JSON.parse(readFileSync(LOCAL_SETTINGS, 'utf8'));
  if (existsSync(LOCAL_EXAMPLE_OUT)) {
    console.error('[gen-agent-hooks-wiring] settings.local.json absent — seeding from settings.local.json.example');
    return JSON.parse(readFileSync(LOCAL_EXAMPLE_OUT, 'utf8'));
  }
  console.error('[gen-agent-hooks-wiring] settings.local.json + .example absent — seeding from public settings.json base');
  return JSON.parse(readFileSync(PUBLIC_SETTINGS, 'utf8'));
}

function checkProjection(file, expected) {
  return existsSync(file) && readFileSync(file, 'utf8').replace(/\r\n/g, '\n') === expected;
}

function main() {
  const check = process.argv.includes('--check');
  const manifest = readManifest();
  const publicContent = renderPublicSettings(manifest);
  const exampleContent = renderLocalExample(manifest);

  if (process.argv.includes('--emit-public-template')) {
    writeFileSync(PUBLIC_SETTINGS, publicContent, 'utf8');
    console.log('[gen-agent-hooks-wiring] wrote ' + relative(REPO_ROOT, PUBLIC_SETTINGS));
    return;
  }

  if (process.argv.includes('--emit-local-example')) {
    writeFileSync(LOCAL_EXAMPLE_OUT, exampleContent, 'utf8');
    console.log('[gen-agent-hooks-wiring] wrote ' + relative(REPO_ROOT, LOCAL_EXAMPLE_OUT));
    return;
  }

  if (check) {
    const drifted = [];
    if (!checkProjection(PUBLIC_SETTINGS, publicContent)) drifted.push(relative(REPO_ROOT, PUBLIC_SETTINGS));
    if (!checkProjection(LOCAL_EXAMPLE_OUT, exampleContent)) drifted.push(relative(REPO_ROOT, LOCAL_EXAMPLE_OUT));
    if (existsSync(LOCAL_SETTINGS)) {
      const localContent = renderSettings(manifest, loadLocalSettings());
      if (!checkProjection(LOCAL_SETTINGS, localContent)) drifted.push(relative(REPO_ROOT, LOCAL_SETTINGS));
    }
    if (drifted.length > 0) {
      console.error('[gen-agent-hooks-wiring] CHECK FAILED: hook projections drifted from governance/agent-hooks.v1.json:');
      for (const file of drifted) console.error(`  - ${file}`);
      console.error('  Re-run `node scripts/codegen/gen-agent-hooks-wiring.mjs`, inspect, and commit tracked changes.');
      process.exit(1);
    }
    console.log('[gen-agent-hooks-wiring] check passed — Claude hook projections match the manifest');
    return;
  }

  writeFileSync(PUBLIC_SETTINGS, publicContent, 'utf8');
  writeFileSync(LOCAL_EXAMPLE_OUT, exampleContent, 'utf8');
  console.log('[gen-agent-hooks-wiring] wrote ' + relative(REPO_ROOT, PUBLIC_SETTINGS));
  console.log('[gen-agent-hooks-wiring] wrote ' + relative(REPO_ROOT, LOCAL_EXAMPLE_OUT));
  if (existsSync(LOCAL_SETTINGS)) {
    writeFileSync(LOCAL_SETTINGS, renderSettings(manifest, loadLocalSettings()), 'utf8');
    console.log('[gen-agent-hooks-wiring] wrote ' + relative(REPO_ROOT, LOCAL_SETTINGS));
  }
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isMain) {
  try {
    main();
  } catch (err) {
    console.error('[gen-agent-hooks-wiring] failed:', err.message);
    process.exit(1);
  }
}

export {
  MANIFEST,
  PUBLIC_SETTINGS,
  LOCAL_SETTINGS,
  LOCAL_EXAMPLE_OUT,
  readManifest,
  renderHooksBlock,
  renderSettings,
  renderPublicSettings,
  renderLocalExample,
};
