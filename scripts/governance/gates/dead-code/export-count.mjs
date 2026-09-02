/**
 * Whole-file-finding normalization for the `dead-code` gate (tempdoc 910 item 1).
 *
 * Knip reports a module in exactly one of two shapes, and they are NOT the same
 * unit:
 *
 *   - the module has NO consumer at all  -> one `files[]` entry, i.e. count 1;
 *   - the module has SOME consumer       -> one entry per unused export/type.
 *
 * The ratchet stores a single number per path, so those two shapes share a
 * numeric slot. Importing one symbol from a whole-file-unused module therefore
 * flips its row from 1 to N with no new dead code, and the gate reads that as
 * `dead-code/silent-growth`. Measured on 2026-09-02: 23 rows of
 * `gates/dead-code/baseline.txt` were pinned at the whole-file `1`, and a
 * one-symbol import of `src/api/domains/browse.ts` moved it 1 -> 2 (the file
 * declares 4 exports; knip reported 2 unused once one was consumed).
 *
 * Normalization: a whole-file finding counts as the module's OWN declared
 * export count, floored at 1. That number is an upper bound on any per-export
 * count knip can later report for the same module, so the 1 -> N flip becomes
 * N -> (<= N) — a shrink, never growth — while a genuinely new dead export
 * still pushes the count past the pin.
 *
 * "Own declared" is deliberate and was measured, not assumed. Probes on
 * 2026-09-02 against knip 6.20.0:
 *
 *   - a named import THROUGH a pure `export * from './x'` barrel
 *     (`src/api/index.ts`) removes the barrel's row entirely — knip attributes
 *     the still-unused names to the ORIGIN module, never to the barrel. So the
 *     barrel must NOT be credited with its transitive surface (that would have
 *     pinned `src/api/index.ts` at 105 instead of 1).
 *   - named re-exports the module writes itself (`export { A } from './x'`,
 *     `export type { B } from './y'`) ARE attributed to that module, so they
 *     count.
 */

import { createRequire } from 'node:module';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Loads the TypeScript compiler. Resolved from the knip project's own
 * `node_modules` first: whenever `tmp/knip-report.json` is producible, knip —
 * and therefore its `typescript` peer — is installed there. Falls back to the
 * repo-root resolution so a root-level install also works.
 *
 * @returns {{ ts: object|null, error: string|null }}
 */
export function loadTypeScript({ repoRoot, projectRoot }) {
  const candidates = [];
  if (projectRoot) candidates.push(resolve(repoRoot, projectRoot, 'package.json'));
  candidates.push(resolve(repoRoot, 'package.json'));
  let lastError = null;
  for (const anchor of candidates) {
    try {
      return { ts: createRequire(anchor)('typescript'), error: null };
    } catch (err) {
      lastError = err;
    }
  }
  return { ts: null, error: lastError ? lastError.message : 'typescript not resolvable' };
}

function isFile(p) {
  try {
    return existsSync(p) && statSync(p).isFile();
  } catch {
    return false;
  }
}

/**
 * Resolves a knip report path (relative to the knip project dir) to an absolute
 * file. Real reports are project-relative (`src/api/domains/browse.ts`); the
 * gate's self-test fixtures are repo-root-relative
 * (`modules/ui-web/src/Foo.ts`), so both roots are tried.
 *
 * @returns {string|null}
 */
export function resolveReportPath({ repoRoot, projectRoot, reportedPath }) {
  const roots = [];
  if (projectRoot) roots.push(resolve(repoRoot, projectRoot));
  roots.push(repoRoot);
  for (const root of roots) {
    const abs = resolve(root, reportedPath);
    if (isFile(abs)) return abs;
  }
  return null;
}

/**
 * Counts the export bindings a module declares itself. Syntactic only — no
 * program, no type checker, no module resolution — which is what makes it
 * both fast and non-transitive.
 *
 * @param {object} ts loaded TypeScript compiler
 * @param {string} absPath
 * @returns {number}
 */
export function countDeclaredExports(ts, absPath) {
  const source = ts.createSourceFile(
    absPath,
    readFileSync(absPath, 'utf8'),
    ts.ScriptTarget.Latest,
    /* setParentNodes */ true,
  );
  const names = new Set();
  let anonymousDefaults = 0;

  for (const statement of source.statements) {
    // `export default <expr>` and `export = <expr>`
    if (ts.isExportAssignment(statement)) {
      names.add('default');
      continue;
    }

    if (ts.isExportDeclaration(statement)) {
      // `export { a, b as c } from './x'` / `export type { T }` — attributed
      // to THIS module by knip, so counted here.
      if (statement.exportClause && ts.isNamedExports(statement.exportClause)) {
        for (const element of statement.exportClause.elements) names.add(element.name.text);
        continue;
      }
      // `export * as ns from './x'` introduces one binding.
      if (statement.exportClause && ts.isNamespaceExport(statement.exportClause)) {
        names.add(statement.exportClause.name.text);
        continue;
      }
      // Bare `export * from './x'` introduces no binding knip attributes here
      // (measured; see the module header). Deliberately not followed.
      continue;
    }

    const flags = ts.getCombinedModifierFlags(statement);
    if ((flags & ts.ModifierFlags.Export) === 0) continue;

    if (ts.isVariableStatement(statement)) {
      for (const declaration of statement.declarationList.declarations) {
        names.add(declaration.name.getText(source));
      }
      continue;
    }

    if (statement.name) {
      names.add(statement.name.getText(source));
      continue;
    }

    // `export default class {}` / `export default function () {}` — exported,
    // named nothing.
    if ((flags & ts.ModifierFlags.Default) !== 0) anonymousDefaults += 1;
  }

  return names.size + anonymousDefaults;
}

/**
 * Normalized count for a knip whole-file finding: the module's own declared
 * export count, floored at 1 so a module with no exports at all (a script, a
 * pure `export *` barrel) keeps its "this entire file is dead" row.
 *
 * @returns {{ count: number|null, reason: string|null }} `count: null` means
 *   the finding could not be normalized and the caller must fail closed —
 *   falling back to 1 would silently restore the trap this module exists to
 *   remove.
 */
export function normalizeWholeFileCount({ ts, tsError, repoRoot, projectRoot, reportedPath }) {
  if (!ts) {
    return { count: null, reason: `TypeScript compiler unavailable (${tsError ?? 'unknown'})` };
  }
  const abs = resolveReportPath({ repoRoot, projectRoot, reportedPath });
  if (!abs) {
    return { count: null, reason: 'file named by the whole-file finding is not on disk' };
  }
  try {
    return { count: Math.max(countDeclaredExports(ts, abs), 1), reason: null };
  } catch (err) {
    return { count: null, reason: `could not parse: ${err.message}` };
  }
}
