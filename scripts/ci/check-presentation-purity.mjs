#!/usr/bin/env node
/**
 * presentation-purity gate — tempdoc 557 §2.A / §3 (+ phase-2 §A1/A2).
 *
 * Guards the display authority: no raw identifier reaches the user.
 *  (1) No raw operation label key `ops.*.label` is hand-written in production
 *      FE source — those come from the wire catalog; hand-writing one leaks a
 *      raw key to the user (the Q3 command-palette defect).
 *  (2) The display-authority resolvers stay wired at the known consumer sites
 *      (regression pins for the §2.A routing).
 *  (3) **Resolver-import collapse (phase-2 A1):** the scattered label resolvers
 *      (`deriveTitleFromSurfaceId`, `describeEffect`) are the PRIVATE
 *      implementation of the one projector — no production file outside the
 *      projector + the resolvers' own defining modules may import them. This
 *      makes "a second labelling authority" a gate failure, not a convention,
 *      and the coverage is the full file universe (the meta-point: a NEW file
 *      that re-imports a resolver is caught automatically, not via a hand-pin).
 *
 * Ceiling note (§5): the raw-id-interpolation vector (`html`${rawId}``) is NOT
 * reliably statically catchable — `core.*-surface` / `justsearch://surface/`
 * appear legitimately as id/route bindings, so a broad text ban would be
 * false-positive-prone. The resolver-import ban + the `ops.*.label` text ban
 * are the catchable vectors; runtime-data leaks rely on review + present()'s
 * branded return at the TS function-call seams.
 *
 * Complements the ESLint `no-restricted-syntax` lint (which guards template
 * interpolation specifically); this catches the key anywhere in source and
 * pins the resolver wiring.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, extname } from 'node:path';

const SRC = 'modules/ui-web/src';
const files = [];
(function walk(d) {
  for (const e of readdirSync(d)) {
    const p = join(d, e);
    const s = statSync(p);
    if (s.isDirectory()) {
      if (e === 'node_modules' || e === 'generated') continue;
      walk(p);
    } else if (['.ts', '.tsx'].includes(extname(p))) files.push(p);
  }
})(SRC);

const isTest = (f) => /\.(test|spec)\.tsx?$/.test(f) || f.includes('__fixtures__');
const failures = [];

// (1) raw operation-label-key literal leak
const OPS = /['"`]ops\.[a-z0-9-]+\.label['"`]/;
for (const f of files) {
  if (isTest(f)) continue;
  const m = readFileSync(f, 'utf8').match(OPS);
  if (m) {
    failures.push(
      `raw operation label key ${m[0]} hand-written in ${f} — resolve via localizeResourceKey / deriveLabel (§2.A)`,
    );
  }
}

// (2) resolver-wiring regression pins
const pins = [
  {
    file: 'modules/ui-web/src/shell-v0/substrates/actions/index.ts',
    needs: 'localizeResourceKey',
    why: 'operation Action titles must be localized, not the raw labelKey (§2.A / Q3)',
  },
  {
    file: 'modules/ui-web/src/shell-v0/views/SettingsSurface.ts',
    needs: 'present(',
    why: 'rail-customization labels must route through the display projector, not raw ids (§2.A / Q6)',
  },
  {
    file: 'modules/ui-web/src/shell-v0/operations/ActionLedgerClient.ts',
    needs: 'present(',
    why: 'activity-timeline rows must show human labels via the projector, not raw operation/surface ids (§2.A / Q7)',
  },
  {
    file: 'modules/ui-web/src/shell-v0/components/OpButton.ts',
    needs: 'present(',
    why: 'operation buttons must derive labels via the display projector (§2.A)',
  },
  {
    file: 'modules/ui-web/src/shell-v0/views/HealthSurface.ts',
    needs: 'present(',
    why: 'the "Fixable now" condition labels must route through the projector, not raw condition ids (§2.A / Q7 — review finding)',
  },
];
for (const p of pins) {
  if (!readFileSync(p.file, 'utf8').includes(p.needs)) {
    failures.push(`${p.file} no longer references ${p.needs} — ${p.why}`);
  }
}

// (3) resolver-import collapse (phase-2 A1): the scattered label resolvers are
// the projector's PRIVATE implementation. Only the projector + the resolvers'
// own defining modules may import them. Full-file-scan coverage — a new file
// that re-imports a resolver is caught automatically (the meta-point).
const RESOLVER_ALLOW = new Set([
  'modules/ui-web/src/shell-v0/display/present.ts',
  'modules/ui-web/src/shell-v0/utils/deriveRichLabel.ts', // defines deriveTitleFromSurfaceId
  'modules/ui-web/src/shell-v0/substrates/effects/describe.ts', // defines describeEffect
]);
// Match a named import of one of the banned resolvers (not deriveRichLabel /
// describeChange, which are distinct sanctioned functions).
const RESOLVER_IMPORT =
  /import\s*(?:type\s*)?\{[^}]*\b(deriveTitleFromSurfaceId|describeEffect)\b[^}]*\}\s*from/;
for (const f of files) {
  if (isTest(f)) continue;
  const norm = f.replace(/\\/g, '/');
  if (RESOLVER_ALLOW.has(norm)) continue;
  const m = readFileSync(f, 'utf8').match(RESOLVER_IMPORT);
  if (m) {
    failures.push(
      `${f} imports the label resolver \`${m[1]}\` directly — render via present() (the one projector). ` +
        `The resolvers are present()'s private implementation (§2.A / phase-2 A1).`,
    );
  }
}

if (failures.length) {
  console.error('presentation-purity FAIL:');
  for (const f of failures) console.error('  ' + f);
  process.exit(1);
}
console.log(
  'presentation-purity OK — no raw operation-label leaks; resolvers collapsed to the projector; display wiring pinned.',
);
