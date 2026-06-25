/**
 * Tempdoc 511 §511-followup-D — `jf-` custom-element prefix
 * enforcement.
 *
 * Every Lit element registered via `customElements.define` in
 * shell-v0 production source must use the `jf-` tag prefix. The
 * convention is normative (every surface, primitive, and aggregate
 * component follows it); this test catches drift before a PR
 * introduces an unprefixed element.
 *
 * Implementation note: this test uses the same `fs.readFileSync`
 * source-scanning pattern as `themes/theme-coverage.test.ts`.
 * Comments are stripped before regex matching so JSDoc examples
 * referencing `customElements.define('foo', ...)` don't produce
 * false positives (caught during §511-indirect Spike C).
 *
 * Test files (`*.test.ts` / `*.test.tsx`) are intentionally
 * out of scope — they register sandbox stubs (`'foo'`,
 * `'legacy-shape-elem'`, etc.) that exercise edge cases.
 */

import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve, join } from 'node:path';

const SHELL_V0_DIR = resolve(__dirname, '..');

function listProductionTsFiles(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      listProductionTsFiles(full, acc);
    } else if (
      (entry.name.endsWith('.ts') || entry.name.endsWith('.tsx')) &&
      !entry.name.endsWith('.test.ts') &&
      !entry.name.endsWith('.test.tsx') &&
      !entry.name.endsWith('.d.ts')
    ) {
      acc.push(full);
    }
  }
  return acc;
}

/**
 * Strip block comments (`/* … *\/`) and line comments (`// …`) so
 * `customElements.define('foo', …)` examples inside JSDoc don't
 * trigger as registrations. Minimal stripper sufficient for this
 * scan; comments inside strings are exceedingly rare in this code
 * base and would not be confused with real registrations because
 * they wouldn't be at statement position.
 */
function stripComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/[^\n]*/g, '');
}

const DEFINE_RE = /customElements\s*\.\s*define\s*\(\s*['"]([^'"]+)['"]/g;

/**
 * Tempdoc 511-followup-D-patches — strict tag-name shape.
 *
 * `startsWith('jf-')` is too lenient: it accepts `jf-`, `jf-FOO`,
 * `jf--double`, `jf-trailing-`. The codebase convention is
 * `jf-<kebab-case>` with lowercase letters/digits and single
 * hyphens. This regex enforces that shape strictly. Custom elements
 * (per the HTML spec) must include at least one hyphen, so any
 * `jf-<x>` registration already satisfies the spec — this is
 * narrower than the spec, by intent.
 */
const STRICT_PREFIX_RE = /^jf-[a-z](?:[a-z0-9-]*[a-z0-9])?$/;

describe('custom-element prefix enforcement (shell-v0)', () => {
  it('every customElements.define call in production source registers a strictly-shaped `jf-<kebab>` tag', () => {
    const violations: Array<{ file: string; tag: string }> = [];
    for (const file of listProductionTsFiles(SHELL_V0_DIR)) {
      const source = stripComments(readFileSync(file, 'utf8'));
      DEFINE_RE.lastIndex = 0;
      let m: RegExpExecArray | null;
      while ((m = DEFINE_RE.exec(source)) !== null) {
        const tag = m[1];
        if (tag && !STRICT_PREFIX_RE.test(tag)) {
          violations.push({
            file: file.replace(SHELL_V0_DIR, '<shell-v0>'),
            tag,
          });
        }
      }
    }
    expect(violations).toEqual([]);
  });

  it('STRICT_PREFIX_RE accepts the canonical names and rejects malformed variants', () => {
    const accepted = [
      'jf-op',
      'jf-operation',
      'jf-resource',
      'jf-health-event',
      'jf-action-button',
      'jf-op-button',
      'jf-shell',
      'jf-rail',
      'jf-stage',
      'jf-confirm-dialog',
      'jf-x1',
    ];
    const rejected = [
      'jf-',               // empty suffix
      'jf-FOO',            // uppercase
      'jf--double',        // double hyphen
      'jf-trailing-',      // trailing hyphen
      'JF-operation',      // uppercase prefix
      'jfop',              // missing hyphen
      'jf-_x',             // underscore not allowed
      'foo-bar',           // wrong prefix
      'jf-1starts',        // suffix starts with digit
    ];
    for (const tag of accepted) {
      expect(STRICT_PREFIX_RE.test(tag), `expected accept: ${tag}`).toBe(true);
    }
    for (const tag of rejected) {
      expect(STRICT_PREFIX_RE.test(tag), `expected reject: ${tag}`).toBe(false);
    }
  });

  it('the scan finds the expected canonical components', () => {
    // Sanity check: regex + comment-strip + directory walk must find
    // the well-known core registrations. If this drops to zero, the
    // scan logic regressed — not a real cleanup.
    const tags = new Set<string>();
    for (const file of listProductionTsFiles(SHELL_V0_DIR)) {
      const source = stripComments(readFileSync(file, 'utf8'));
      DEFINE_RE.lastIndex = 0;
      let m: RegExpExecArray | null;
      while ((m = DEFINE_RE.exec(source)) !== null) {
        if (m[1]) tags.add(m[1]);
      }
    }
    expect(tags.has('jf-operation')).toBe(true);
    expect(tags.has('jf-resource')).toBe(true);
    expect(tags.has('jf-health-event')).toBe(true);
    expect(tags.has('jf-shell')).toBe(true);
    expect(tags.has('jf-health-surface')).toBe(true);
  });
});
