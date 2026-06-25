#!/usr/bin/env node
/**
 * Tempdoc 564 facet 4d — the mandate gate: a migrated wire type's second copy is
 * UNREPRESENTABLE.
 *
 * The 564 thesis is that drift closes structurally, not by patrol: once a wire record is the
 * single generated projection (record → JSON Schema → {TS, Zod}), a hand-authored second copy
 * of it must be *build-refused*, not merely linted. This gate enforces exactly that, scoped to
 * the records actually migrated (the codegen `TARGETS` registry, so it auto-extends as surfaces
 * land — the "transition ratchet" is simply "not yet in TARGETS").
 *
 * For each generated root type name (e.g. `KnowledgeSearchResponse`, `StatusResponse`), it scans
 * the FE source OUTSIDE the generated-output set for a hand `interface <Name>` / `type <Name> =`
 * *declaration* (re-exports `export type { Name } from '…/generated/…'` are allowed — they point
 * at the single authority). Any hand declaration fails the build: the second copy is refused.
 *
 * The generated-output set (allowed to declare these names) = `api/generated/**` — this includes
 * both the schema-types authority and the parallel `wire-types.ts` projection (also generated, not
 * hand-authored; it is demoted incrementally as consumers move, per the plan).
 *
 * Invoked by:
 *   node scripts/ci/check-wire-type-single-authority.mjs
 */

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = join(__dirname, '..', '..');
const FE_SRC = join(REPO_ROOT, 'modules', 'ui-web', 'src');
const GENERATED_DIR = join(FE_SRC, 'api', 'generated'); // the allowed declaration site

const GEN_SCRIPT = join(REPO_ROOT, 'scripts', 'codegen', 'gen-wire-schema-types.mjs');

function listTsFiles(dir, acc) {
  for (const entry of readdirSync(dir)) {
    const p = join(dir, entry);
    const st = statSync(p);
    if (st.isDirectory()) {
      if (p === GENERATED_DIR) continue; // skip the generated set — it IS the authority
      listTsFiles(p, acc);
    } else if (/\.tsx?$/.test(p) && !/\.test\.tsx?$/.test(p) && !/\.d\.ts$/.test(p)) {
      acc.push(p);
    }
  }
  return acc;
}

async function main() {
  const mod = await import(pathToFileURL(GEN_SCRIPT).href);
  const rootNames = mod.TARGETS.map((t) => t.rootName);
  if (rootNames.length === 0) {
    console.log('[wire-type-single-authority] no migrated wire types yet — nothing to enforce');
    return;
  }

  const files = listTsFiles(FE_SRC, []);
  const violations = [];
  for (const file of files) {
    const text = readFileSync(file, 'utf8');
    for (const name of rootNames) {
      // A hand *declaration* of the migrated type only:
      //   - `interface <Name>` (interface declaration), or
      //   - `type <Name> =` (type-alias declaration; the `=` is what distinguishes a declaration
      //     from an inline `import { type <Name> }` specifier or an `export type { <Name> }`
      //     re-export — both of which point at the single generated authority and are allowed).
      const decl = new RegExp(
        '(^|\\n)[ \\t]*(export[ \\t]+)?(interface[ \\t]+' +
          name +
          '\\b|type[ \\t]+' +
          name +
          '[ \\t]*=)',
        'g',
      );
      let m;
      while ((m = decl.exec(text)) !== null) {
        const line = text.slice(0, m.index + (m[1] === '\n' ? 1 : 0)).split('\n').length;
        violations.push({ file: relative(REPO_ROOT, file).split(sep).join('/'), name, line });
      }
    }
  }

  if (violations.length > 0) {
    console.error(
      '[wire-type-single-authority] MANDATE VIOLATION — a hand-authored copy of a migrated wire type:',
    );
    for (const v of violations) {
      console.error(`  - ${v.file}:${v.line} declares '${v.name}'`);
    }
    console.error(
      '\nA migrated wire record is the SINGLE generated projection. Delete the hand copy and import\n' +
        'the generated type from modules/ui-web/src/api/generated/schema-types/ (re-export with\n' +
        "`export type { Name } from '…/generated/…'` if a stable path is needed). The second copy is\n" +
        'unrepresentable by mandate (tempdoc 564 facet 4d).',
    );
    process.exit(1);
  }
  console.log(
    `[wire-type-single-authority] passed — ${rootNames.length} migrated wire type(s) have a single ` +
      'generated authority (' +
      rootNames.join(', ') +
      ').',
  );
}

main().catch((err) => {
  console.error('[wire-type-single-authority] failed:', err);
  process.exit(1);
});
