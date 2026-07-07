#!/usr/bin/env node
/**
 * Tempdoc 592 (rung 1) — generate the `hooks` block of .claude/settings.local.json
 * FROM the single-authority manifest governance/agent-hooks.v1.json.
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
 *   node scripts/codegen/gen-agent-hooks-wiring.mjs                         # write settings.local.json hooks
 *   node scripts/codegen/gen-agent-hooks-wiring.mjs --check                 # exit non-zero on drift
 *   node scripts/codegen/gen-agent-hooks-wiring.mjs --emit-public-template  # compose the public settings.json
 *       template (guards-only — drops the founder-analytics hooks; no permissions/env) into the cutover-package.
 *       The go-public flip swaps that template in as .claude/settings.json (tempdoc 631 #2 / F3).
 *   node scripts/codegen/gen-agent-hooks-wiring.mjs --emit-local-example    # write .claude/settings.local.json.example
 *       (the maintainer re-wire seed promised by 631 #2: FULL hooks — incl. the founder-analytics set the
 *       public template drops — plus documented permissions/env stubs. A maintainer copies it to
 *       settings.local.json (gitignored) to restore session attribution/telemetry in the public checkout.)
 *
 * Bootstrap: when .claude/settings.local.json is ABSENT (the post-cutover public checkout), the default
 * write and --check seed from settings.local.json.example (else the public settings.json base) instead of
 * crashing with ENOENT — so a maintainer with no local file yet can still generate one.
 */

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = join(__dirname, '..', '..');

const MANIFEST = join(REPO_ROOT, 'governance', 'agent-hooks.v1.json');
const SETTINGS = join(REPO_ROOT, '.claude', 'settings.local.json');

// Go-public item 2 / F3: the public template composes the safe public base (no
// permissions/env) + the manifest hooks block, written to the cutover-package prep area
// (NOT live .claude/, to avoid double-loading the hooks during continued private dev).
const PUBLIC_BASE = join(REPO_ROOT, '.claude', 'settings.json');
const PUBLIC_TEMPLATE_OUT = join(
  REPO_ROOT, 'docs', 'business', 'go-to-market', 'cutover-package', 'public-settings.json');

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
 * Compose the PUBLIC settings.json template (go-public item 2 / F3): the safe public base
 * (.claude/settings.json — worktree/MCP/plugins; never permissions or local env) + the hooks
 * block from the manifest. The cutover swaps this in AS .claude/settings.json once
 * settings.local.json (bypassPermissions/Bash(*) + local env) is removed — that swap keeps the
 * published discipline guards LIVE rather than shipping them unwired (the whole point of item 2).
 */
function renderPublicTemplate(manifest) {
  const safeBase = { ...JSON.parse(readFileSync(PUBLIC_BASE, 'utf8')) };
  delete safeBase.permissions; // never publish a permissions posture inherited from local config
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
  const base = { ...JSON.parse(readFileSync(PUBLIC_BASE, 'utf8')) };
  delete base.hooks; // regenerated below as the full set
  base.permissions = base.permissions ?? { allow: [], deny: [], ask: [] };
  base.env = base.env ?? {};
  return JSON.stringify({ ...base, hooks: renderHooksBlock(manifest) }, null, 2) + '\n';
}

/**
 * Resolve the base settings object the default write / --check regenerates the `hooks` block into.
 * Precedence: a maintainer's existing settings.local.json (never clobber their permissions/env) →
 * the committed example seed → the public settings.json base. The last two paths are the ENOENT
 * hardening: a public checkout with no local file yet can still generate one instead of crashing.
 */
function loadBaseSettings() {
  if (existsSync(SETTINGS)) return JSON.parse(readFileSync(SETTINGS, 'utf8'));
  if (existsSync(LOCAL_EXAMPLE_OUT)) {
    console.error('[gen-agent-hooks-wiring] settings.local.json absent — seeding from settings.local.json.example');
    return JSON.parse(readFileSync(LOCAL_EXAMPLE_OUT, 'utf8'));
  }
  console.error('[gen-agent-hooks-wiring] settings.local.json + .example absent — seeding from public settings.json base');
  return JSON.parse(readFileSync(PUBLIC_BASE, 'utf8'));
}

function main() {
  const check = process.argv.includes('--check');
  const manifest = readManifest();

  if (process.argv.includes('--emit-public-template')) {
    writeFileSync(PUBLIC_TEMPLATE_OUT, renderPublicTemplate(manifest));
    console.log('[gen-agent-hooks-wiring] wrote public template ' + relative(REPO_ROOT, PUBLIC_TEMPLATE_OUT));
    return;
  }

  if (process.argv.includes('--emit-local-example')) {
    writeFileSync(LOCAL_EXAMPLE_OUT, renderLocalExample(manifest));
    console.log('[gen-agent-hooks-wiring] wrote ' + relative(REPO_ROOT, LOCAL_EXAMPLE_OUT));
    return;
  }

  // The regen drift-guard is a maintainer-local check: a public checkout legitimately has no
  // settings.local.json (it is gitignored + analytics-only), so there is nothing to drift. Skip
  // cleanly rather than fail/crash — keeps `--check` honest where the file is intentionally absent.
  if (check && !existsSync(SETTINGS)) {
    console.log('[gen-agent-hooks-wiring] no .claude/settings.local.json (public checkout) — nothing to check; skipping');
    return;
  }

  const currentSettings = loadBaseSettings();
  const content = renderSettings(manifest, currentSettings);

  if (check) {
    const before = existsSync(SETTINGS) ? readFileSync(SETTINGS, 'utf8') : null;
    if (before !== content) {
      console.error('[gen-agent-hooks-wiring] CHECK FAILED: .claude/settings.local.json hooks block');
      console.error('  drifted from governance/agent-hooks.v1.json.');
      console.error('  Re-run `node scripts/codegen/gen-agent-hooks-wiring.mjs`, inspect, commit the regen.');
      process.exit(1);
    }
    console.log('[gen-agent-hooks-wiring] check passed — settings hooks block matches the manifest');
    return;
  }

  writeFileSync(SETTINGS, content, 'utf8');
  console.log('[gen-agent-hooks-wiring] wrote ' + relative(REPO_ROOT, SETTINGS) + ' (hooks block from manifest)');
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

export { MANIFEST, SETTINGS, readManifest, renderHooksBlock, renderSettings };
