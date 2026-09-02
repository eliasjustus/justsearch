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
 * export surface, floored at 1, so the 1 -> N flip becomes N -> (<= N) — a
 * shrink, never growth — while a genuinely new dead export still pushes the
 * count past the pin.
 *
 * "Export surface", not "export count", and the difference is load-bearing.
 * The enforcer sums EVERY array-valued key on a knip row, and knip reports
 * unused enum members under `enumMembers` and unused namespace members under
 * `namespaceMembers` — entries a whole-file finding never shows. Counting only
 * top-level bindings therefore under-counts and the bound breaks. Measured on
 * 2026-09-02 against knip 6.20.0 with the repo's OWN config (this is in the
 * DEFAULT report, not an opt-in): a module declaring
 * `export enum ScratchEnum {A,B,C}` plus two consts reports the whole-file `1`
 * while unused; consume `ScratchEnum.A` from anywhere and it reports
 * `enumMembers: [B, C]` + `exports: [scratchOne, scratchTwo]` = 4. Counting
 * top-level bindings alone gives 3, and 3 -> 4 is silent-growth with no new
 * dead code — the very trap this module exists to remove, one level down.
 * So members of exported enums / namespaces / classes are counted too.
 *
 * The bound is conservative by design where knip's positions are mutually
 * exclusive: an entirely-unused enum is reported once under `types` (its
 * name), while a used enum reports only its unused members — so counting name
 * AND members over-counts by one rather than risking an under-count.
 *
 * KNOWN LIMITS, stated rather than papered over. Three ways the bound can be
 * escaped, all absent from this project's report today:
 *
 *   - `duplicates` (the same binding exported under two names). Bounding it
 *     needs binding-level resolution rather than the syntactic walk below.
 *   - `classMembers` — see `countReportableMembers` for the measurement and
 *     the trigger to revisit.
 *   - THE TWO BRANCHES DO NOT USE THE SAME UNIT. The per-export branch
 *     (`enforcer.mjs:150-155`) sums EVERY array key on the row, including the
 *     non-export categories `dependencies`, `devDependencies`, `unlisted`,
 *     `unresolved` and `binaries`, while the whole-file branch (`:143-147`)
 *     replaces the row with this export count and drops them — so a module
 *     that gained >= 2 non-export findings after ceasing to be whole-file
 *     could still exceed its pin. Measured 2026-09-02: 0 of the 23 whole-file
 *     rows carry a non-export category, and only 3 rows repo-wide do at all
 *     (`package.json` dependencies/devDependencies, `Shell.ts` and
 *     `runtimeConformance.ts` one `unlisted` each), so the escape is currently
 *     unreachable rather than merely unlikely.
 *
 * If any of the three appears, a module carrying it could read as growth on
 * gaining a consumer, and this is the comment to come back to.
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
 * Counts the members knip reports against an exported enum or namespace
 * (`enumMembers`, `namespaceMembers`). Nested namespaces recurse.
 *
 * `classMembers` is DELIBERATELY not counted, and the line is empirical rather
 * than principled — say so rather than dress it up. Measured 2026-09-02 on the
 * repo's own config: the default report emits
 * `dependencies, devDependencies, enumMembers, exports, files, types, unlisted`
 * — `enumMembers` yes, `classMembers` no. Counting class members would pin
 * `src/shell-v0/components/SelectionActionsMenu.ts` at 19 instead of 1: ONE
 * exported Lit class, whose 19 members are 9 properties + 9 methods + a
 * constructor, and the constructor is unnamed so knip could not report it —
 * 18 nameable members, plus the class name itself. `retainedScroll.ts` and
 * `ResolutionStats.ts` likewise go 1 -> 7 (3 properties + 3 methods + an
 * unnamed constructor = 6 nameable, plus the class name). Each number counts
 * the exported NAME as well as its members. That is a large standing allowance
 * bought for a category knip does not emit here — the same over-permissiveness
 * this module rejects for transitive star barrels, so it is rejected here too.
 *
 * TRIGGER TO REVISIT: if `modules/ui-web/knip.config.ts` ever sets
 * `include: ['classMembers']`, this function must count them and the affected
 * baseline rows must be re-pinned in the same PR — otherwise the 1 -> N trap
 * returns for every exported class.
 *
 * @returns {number}
 */
function countReportableMembers(ts, statement, source) {
  if (ts.isEnumDeclaration(statement)) return statement.members.length;

  if (ts.isModuleDeclaration(statement) && statement.body && ts.isModuleBlock(statement.body)) {
    let total = 0;
    for (const inner of statement.body.statements) {
      if ((ts.getCombinedModifierFlags(inner) & ts.ModifierFlags.Export) === 0) continue;
      if (ts.isVariableStatement(inner)) {
        total += inner.declarationList.declarations.length;
        continue;
      }
      if (inner.name) total += 1;
      total += countReportableMembers(ts, inner, source);
    }
    return total;
  }

  return 0;
}

/**
 * Counts the export surface a module declares itself: top-level exported
 * bindings PLUS the members knip can report against them. Syntactic only — no
 * program, no type checker, no module resolution — which is what makes it both
 * fast and non-transitive.
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
  let members = 0;

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
      members += countReportableMembers(ts, statement, source);
      continue;
    }

    // `export default class {}` / `export default function () {}` — exported,
    // named nothing.
    if ((flags & ts.ModifierFlags.Default) !== 0) {
      anonymousDefaults += 1;
      members += countReportableMembers(ts, statement, source);
    }
  }

  return names.size + anonymousDefaults + members;
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
