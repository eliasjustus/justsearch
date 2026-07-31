// SPDX-License-Identifier: Apache-2.0
// check-tauri-plugin-config — a registered Tauri plugin with a required config
// must have a non-null config block in the BASE tauri.conf.json.
//
// Regression home for Sandbox round 9 F1 (BLOCKER, 2026-07-31): lib.rs
// registered tauri_plugin_updater unconditionally while `plugins.updater`
// existed only in the tag-build overlay (tauri.updater.conf.json), so every
// non-tag build's shell panicked at startup —
//   PluginInitialization("updater", "... invalid type: null, expected struct Config")
// exit 101, before any window, backend spawn, or log line. The overlay itself
// was ALSO insufficient (no `pubkey`, which the plugin's serde Config requires
// with no default), so the first-ever tag build would have panicked identically.
// The base config now carries a fail-closed block (`pubkey: ""`, `endpoints: []`)
// whose values the shipped update path never uses — updater.rs `updater_for`
// overrides both from the authenticated release descriptor at runtime.
//
// The check is deliberately narrow: it pins the one plugin/panic pair that
// shipped a dead candidate, rather than guessing at every plugin's config
// requirements. If a second plugin ever fails this way, generalize then.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..', '..');
const libRs = readFileSync(
  resolve(root, 'modules/shell/src-tauri/src/lib.rs'),
  'utf8',
);
const conf = JSON.parse(
  readFileSync(resolve(root, 'modules/shell/src-tauri/tauri.conf.json'), 'utf8'),
);

const failures = [];

if (libRs.includes('tauri_plugin_updater')) {
  const updater = conf.plugins?.updater;
  if (updater === undefined || updater === null) {
    failures.push(
      'lib.rs registers tauri_plugin_updater but the BASE tauri.conf.json has no ' +
        '`plugins.updater` block — every non-overlay build panics at startup ' +
        '(round 9 F1). The overlay (tauri.updater.conf.json) does not cover ' +
        'dispatch/dev builds.',
    );
  } else {
    if (typeof updater.pubkey !== 'string') {
      failures.push(
        '`plugins.updater.pubkey` must be a string — the plugin Config requires ' +
          'the field with no serde default; its absence panics the shell at startup.',
      );
    }
    if (!Array.isArray(updater.endpoints)) {
      failures.push(
        '`plugins.updater.endpoints` must be an array (empty = fail-closed; the ' +
          'shipped update path supplies real endpoints at runtime from the ' +
          'authenticated descriptor).',
      );
    }
  }
}

if (failures.length > 0) {
  console.error('check-tauri-plugin-config: FAIL');
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(
  'check-tauri-plugin-config: OK — registered updater plugin has a valid base config block.',
);
