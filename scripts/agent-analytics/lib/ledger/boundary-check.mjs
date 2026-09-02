/**
 * lib/ledger/boundary-check.mjs — pure boundary-rule checker for
 * `lib/ledger/*.mjs` source text (tempdoc 886 §12 PR 1, independent-review
 * fix-up SHOULD-FIX 4). Extracted out of `boundary.test.mjs` so the rule is
 * an importable, independently-testable function rather than inline test
 * logic — a test can prove the CHECKER itself catches a violation (the
 * negative cases in `boundary.test.mjs`), not just that today's files pass.
 *
 * The rule (886 §10.4): nothing under `lib/ledger/` may read `governance/`,
 * `CLAUDE.md`, or `tmp/agent-telemetry` paths, resolve a repo root via a
 * relative `'..', '..', '..'` climb, or import anything other than a
 * `node:` builtin, a sibling `./`-relative ledger file, or exactly
 * `../transcript-store.mjs` / `../transcript-cost.mjs`.
 */

const FORBIDDEN_STRINGS = ['governance/', 'CLAUDE.md', 'tmp/agent-telemetry'];
const REPO_ROOT_CLIMB_RE = /['"]\.\.['"]\s*,\s*['"]\.\.['"]\s*,\s*['"]\.\.['"]/;
const NODE_BUILTIN_RE = /^node:/;
const ALLOWED_EXACT_SPECIFIERS = new Set(['../transcript-store.mjs', '../transcript-cost.mjs']);

/**
 * Matches BOTH shapes an ES import can take:
 *   - side-effect: `import '../../../x.mjs';` (no `from`)
 *   - named/default, possibly MULTI-LINE: `import {\n  a\n} from '../x.mjs';`
 * The optional `(?:[\s\S]*?from\s+)?` group is what makes both shapes match
 * one pattern: it is skipped entirely for a side-effect import (the next
 * token is directly the quoted specifier), and for a named/default import it
 * lazily consumes everything — including newlines, via `[\s\S]`, since a
 * multi-line brace list must not defeat the match — up to the nearest
 * ` from ` before a quote.
 */
const IMPORT_RE = /import\s+(?:[\s\S]*?from\s+)?['"]([^'"]+)['"]/g;
/**
 * Second-pass review (2026-09-02) found three import edges the pattern above
 * does not see. Two are closed here: a re-export (`export { a } from '…'`,
 * `export * from '…'`) is an import edge written with a different keyword,
 * and a dynamic `import('…')` has no whitespace after `import`. The third —
 * `createRequire(import.meta.url)('…')` — is closed by forbidding the
 * `createRequire` call outright: nothing in the ledger has a reason to use
 * CommonJS require at all.
 */
const REEXPORT_RE = /export\s*(?:\*(?:\s+as\s+\w+)?|\{[^}]*\})\s*from\s*['"]([^'"]+)['"]/g;
const DYNAMIC_IMPORT_RE = /import\s*\(\s*['"]([^'"]+)['"]/g;
const CREATE_REQUIRE_RE = /createRequire\s*\(/;

/**
 * Return every boundary-rule violation found in `sourceText` (from a file
 * named `filename`, used only to label the returned strings) — an empty
 * array means clean. Pure: no filesystem access, so a test can hand it an
 * arbitrary string.
 */
export function findBoundaryViolations(sourceText, filename) {
  const violations = [];

  for (const s of FORBIDDEN_STRINGS) {
    if (sourceText.includes(s)) violations.push(`${filename}: forbidden string "${s}"`);
  }

  if (REPO_ROOT_CLIMB_RE.test(sourceText)) {
    violations.push(`${filename}: repo-root climb via '..','..','..'`);
  }

  if (CREATE_REQUIRE_RE.test(sourceText)) {
    violations.push(`${filename}: createRequire() escape hatch (CommonJS require is not allowed in the ledger)`);
  }

  const specs = [
    ...[...sourceText.matchAll(IMPORT_RE)].map((m) => m[1]),
    ...[...sourceText.matchAll(REEXPORT_RE)].map((m) => m[1]),
    ...[...sourceText.matchAll(DYNAMIC_IMPORT_RE)].map((m) => m[1]),
  ];
  for (const spec of specs) {
    const ok = NODE_BUILTIN_RE.test(spec) || ALLOWED_EXACT_SPECIFIERS.has(spec) || spec.startsWith('./');
    if (!ok) violations.push(`${filename}: disallowed import "${spec}"`);
  }

  return violations;
}
