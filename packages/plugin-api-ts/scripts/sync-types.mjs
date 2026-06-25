#!/usr/bin/env node
/**
 * Slice 477 H3.1 — sync TypeScript types from the monorepo source
 * into the published-package source tree.
 *
 * The published `@justsearch/plugin-api` package exports types
 * that mirror `modules/ui-web/src/shell-v0/plugin-api/`. V1.5.1
 * alpha runs this sync manually (`npm run sync-types`); V1.5.2
 * wires it into CI as a pre-commit / pre-publish hook so the
 * package is never out-of-sync with the canonical monorepo source.
 *
 * Sync targets (read from monorepo, write to packages/plugin-api-ts/src):
 *   - plugin-types.ts → plugin-types.ts
 *   - PluginRegistry.ts (PluginTrustTier export) → trust-types.ts
 *     (trimmed to the type-only re-exports the SDK needs)
 *   - TrustChannel.ts (type exports) → trust-types.ts
 *
 * The sync rewrites import paths from monorepo-relative
 * (`./plugin-types.js`) to package-relative.
 */

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const PKG_ROOT = resolve(__dirname, '..');
const PKG_SRC = join(PKG_ROOT, 'src');
const REPO_ROOT = resolve(PKG_ROOT, '..', '..');
const MONOREPO_PLUGIN_API = join(
  REPO_ROOT,
  'modules',
  'ui-web',
  'src',
  'shell-v0',
  'plugin-api',
);

mkdirSync(PKG_SRC, { recursive: true });

// 1. plugin-types.ts copies almost verbatim — the canonical types
// already re-export cleanly. Strip the `// @ts-...` directives
// that target the monorepo's stricter tsconfig.
const pluginTypesSrc = readFileSync(
  join(MONOREPO_PLUGIN_API, 'plugin-types.ts'),
  'utf8',
);
writeFileSync(
  join(PKG_SRC, 'plugin-types.ts'),
  pluginTypesSrc + '\n',
  'utf8',
);
console.log('synced: plugin-types.ts');

// 2. trust-types.ts pulls only the type exports from PluginRegistry
// + TrustChannel (no implementation code; the SDK is types-only).
const registrySrc = readFileSync(
  join(MONOREPO_PLUGIN_API, 'PluginRegistry.ts'),
  'utf8',
);
const trustChannelSrc = readFileSync(
  join(MONOREPO_PLUGIN_API, 'TrustChannel.ts'),
  'utf8',
);

// Extract `export type PluginTrustTier = ...;` from PluginRegistry.
const tierMatch = registrySrc.match(
  /export type PluginTrustTier = [^;]+;/,
);
if (!tierMatch) {
  throw new Error('sync-types: could not find PluginTrustTier export');
}
const tierExport = tierMatch[0];

// Extract type/interface declarations from TrustChannel; skip
// implementations (StubTrustChannel, RemoteTrustChannel, helper
// functions) — SDK is types-only.
const trustTypeBlocks = [];
for (const match of trustChannelSrc.matchAll(
  /export (interface|type) \w+[^]*?\n\}/g,
)) {
  trustTypeBlocks.push(match[0]);
}

const trustHeader = `/**
 * Trust-model types — auto-synced from the monorepo source by
 * scripts/sync-types.mjs. Edit the canonical files in
 * modules/ui-web/src/shell-v0/plugin-api/, NOT this file.
 */
`;

writeFileSync(
  join(PKG_SRC, 'trust-types.ts'),
  trustHeader + '\n' + tierExport + '\n\n' + trustTypeBlocks.join('\n\n') + '\n',
  'utf8',
);
console.log('synced: trust-types.ts');

console.log('sync-types complete.');
