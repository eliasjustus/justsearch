import globals from 'globals'
import { defineConfig, globalIgnores } from 'eslint/config'

/**
 * Root ESLint config — the Node-side tooling corpus (`scripts/**`, `packaging/**`).
 *
 * Tempdoc 930 chunk F retired the `todo-fixme` kernel gate and moved TODO/FIXME coverage to lint
 * rules: ESLint `no-warning-comments` for `modules/ui-web`, PMD `CommentContent` for Java. That
 * left the corpus the retired gate DID scan but neither successor covered — the ~575 `.mjs`/`.cjs`
 * files under `scripts/**` plus the packaging JS (930 §22.2 follow-up 3). This config is that
 * successor. `*.ps1` is covered by `scripts/ci/check-ps1-warning-comments.mjs`, which mirrors the
 * same terms and the same suppression-list shape.
 *
 * Run: `npm run lint:scripts` (wired in CI next to the ui-web "Frontend lint" step).
 *
 * Existing markers are carried in `eslint-suppressions.json` (ESLint bulk suppressions) — the
 * same ratchet `modules/ui-web` uses. A NEW marker fails; removing one and re-running with
 * `--prune-suppressions` lowers the count.
 */

const CORPUS = ['scripts/**/*.{mjs,cjs,js}', 'packaging/**/*.{mjs,cjs,js}']

export default defineConfig([
  globalIgnores([
    '**/node_modules/**',
    '**/dist/**',
    'modules/**',
    'tools/**',
    // Generated bundles — a vendored dependency graph rolled into one file. Not hand-authored,
    // so a marker inside one is upstream's, not ours.
    '**/*.generated.mjs',
    '**/*.generated.cjs',
    // Gate/CI fixture trees: deliberately malformed or synthetic inputs that no one authors as
    // production tooling.
    'scripts/governance/_fixtures/**',
    'scripts/ci/_fixtures/**',
    'scripts/ci/fixtures/**',
    'scripts/agent-analytics/fixtures/**',
  ]),
  {
    files: CORPUS,
    languageOptions: {
      ecmaVersion: 'latest',
      globals: { ...globals.node },
    },
    linterOptions: {
      // This config deliberately enables a NARROW rule set (see below), so the corpus's existing
      // `eslint-disable` comments overwhelmingly name rules it does not run — 56 `no-console` and
      // 32 `no-await-in-loop` at the time of writing, neither of which is enabled anywhere in the
      // repo. Reporting those as unused would be 90+ warnings about rules we chose not to enable,
      // not about defects. `modules/ui-web`, whose rule set IS the one its directives were written
      // against, keeps the default report (and runs `--max-warnings=0`).
      reportUnusedDisableDirectives: 'off',
    },
    rules: {
      // The `todo-fixme` gate's successor for this corpus. `location: 'anywhere'` matches the
      // gate's behaviour (it regex-scanned whole files); unlike the gate this rule parses, so it
      // cannot score the marker word inside a string literal, a URL, or a filename.
      'no-warning-comments': [
        'error',
        { terms: ['todo', 'fixme', 'xxx', 'hack'], location: 'anywhere' },
      ],

      // `@eslint/js` recommended in full yields 156 errors on this corpus (no-unused-vars 62,
      // no-useless-assignment 38, no-empty 24, preserve-caught-error 16, no-useless-escape 10,
      // no-control-regex 2, no-regex-spaces 1) — a cleanup far larger than this PR, and one that
      // would collide with the other lanes editing `scripts/**`. Rather than take recommended and
      // bulk-disable the noisy half, this enables the subset that is ALREADY clean here and is
      // about genuine defects (wrong code) rather than debt or style. Every rule below was
      // measured at zero findings on 2026-09-05; widening the set is a follow-up, not a rewrite.
      'no-undef': 'error',
      'no-const-assign': 'error',
      'no-class-assign': 'error',
      'no-func-assign': 'error',
      'no-import-assign': 'error',
      'no-this-before-super': 'error',
      'no-dupe-args': 'error',
      'no-dupe-keys': 'error',
      'no-dupe-class-members': 'error',
      'no-dupe-else-if': 'error',
      'no-duplicate-case': 'error',
      'no-unreachable': 'error',
      'no-self-assign': 'error',
      'no-cond-assign': 'error',
      'no-fallthrough': 'error',
      'no-obj-calls': 'error',
      'no-sparse-arrays': 'error',
      'no-unsafe-negation': 'error',
      'no-unsafe-optional-chaining': 'error',
      'no-unsafe-finally': 'error',
      'no-invalid-regexp': 'error',
      'no-async-promise-executor': 'error',
      'no-constant-binary-expression': 'error',
      'no-useless-backreference': 'error',
      'no-useless-catch': 'error',
      'no-debugger': 'error',
      'require-yield': 'error',
      'use-isnan': 'error',
      'valid-typeof': 'error',
      'getter-return': 'error',
    },
  },
  {
    // `.cjs` and the package-less `packaging/mcpb/server/index.js` are CommonJS — they need
    // `require`/`module`/`__dirname`, which `globals.node` supplies.
    files: ['**/*.cjs', 'packaging/mcpb/server/index.js'],
    languageOptions: { sourceType: 'commonjs' },
  },
])
