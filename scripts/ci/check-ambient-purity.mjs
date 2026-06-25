#!/usr/bin/env node
/**
 * check-ambient-purity — tempdoc 574 Phase 5 + §22.F (the SELF-COVERING prevention tier for Moves 1-2).
 *
 * Locks the delivery substrate + ambient authority so the per-window-fork / false-global class
 * (§15/§16) cannot return. The Class-B facets + the authority/base paths are PROJECTED from the
 * AmbientDeclaration catalog `governance/ambient-facets.v1.json` (§22.F) — adding a facet there
 * auto-extends both checks below, so the gate is self-covering (557 §5.2 coverage-projects-from-catalog;
 * the discovery step is adding the catalog row, not editing this gate):
 *
 *  (a) BAN — each catalog `classB` facet (`::-webkit-scrollbar` / `::selection` / `::placeholder` / the
 *      consolidated `@keyframes spin|jf-spin`) must live ONLY in the authority sheet, never re-authored
 *      inside a component's `static styles` (these shadow-scoped constructs have zero legitimate
 *      in-component site after Phase 2; it deliberately does NOT ban `@keyframes` wholesale — divergent
 *      local animations stay legitimate until reconciled).
 *  (b) POSITIVE COVERAGE — each catalog `classB` facet must be DEFINED in the authority (the sheet did
 *      not silently lose a facet the catalog promises).
 *  (c) JfElement base — every shell-v0 component must `extends JfElement` (not raw `LitElement`), so it
 *      receives the adopted ambient sheet by construction (Move 1).
 *
 * Comments are stripped before scanning so doc-comment prose mentioning these names never trips it.
 * Usage: node scripts/ci/check-ambient-purity.mjs
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const CATALOG = 'governance/ambient-facets.v1.json';
const ROOT = 'modules/ui-web/src/shell-v0';

/** Strip block + line comments so prose mentioning the banned tokens does not trip the gate. */
function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
}

/** repo-relative key (`modules/ui-web/src/...`) regardless of absolute scan root. */
const relKey = (p) => {
  const tail = p.replace(/\\/g, '/').split('modules/ui-web/src/')[1];
  return tail ? `modules/ui-web/src/${tail}` : p.replace(/\\/g, '/');
};

/**
 * Pure detection (574 §22.F): (a) BAN each catalog Class-B facet outside the
 * authority sheet, (c) every component `extends JfElement`, (b) the authority
 * defines every catalog facet. Rooted (`root`) so the 530 kernel can scan a
 * self-test fixture tree. Shared by the CLI and
 * `scripts/governance/gates/ambient-purity/`.
 */
export function detect({ root = '.' } = {}) {
  const reg = JSON.parse(readFileSync(resolve(root, CATALOG), 'utf8'));
  const AMBIENT = reg.authority;
  const JF_BASE = reg.base;
  const classB = reg.classB.map((f) => ({ ...f, re: new RegExp(f.banPattern) }));
  const treeRoot = resolve(root, ROOT);

  const files = [];
  (function walk(dir) {
    let entries;
    try {
      entries = readdirSync(dir);
    } catch {
      return;
    }
    for (const e of entries) {
      const p = join(dir, e).replace(/\\/g, '/');
      const s = statSync(p);
      if (s.isDirectory()) walk(p);
      else if (p.endsWith('.ts') && !p.endsWith('.test.ts')) files.push(p);
    }
  })(treeRoot);

  const violations = [];
  // (a) BAN + (c) JfElement base — scan every component.
  for (const abs of files) {
    const f = relKey(abs);
    const code = stripComments(readFileSync(abs, 'utf8'));
    if (f !== AMBIENT) {
      for (const { re, name } of classB) {
        if (re.test(code)) {
          violations.push({
            file: f,
            rule: 'ambient-outside-authority',
            message: `${f}: ambient construct \`${name}\` — move it to ${AMBIENT} (574 Move 1/2; ambient-facets catalog).`,
          });
        }
      }
    }
    if (f !== JF_BASE) {
      if (/\bextends\s+LitElement\b/.test(code) || /SignalWatcher\(\s*LitElement\s*\)/.test(code)) {
        violations.push({
          file: f,
          rule: 'raw-litelement-base',
          message: `${f}: \`extends LitElement\` — every shell-v0 component must \`extends JfElement\` (574 Move 1).`,
        });
      }
    }
  }

  // (b) POSITIVE COVERAGE — the authority defines every catalog Class-B facet.
  const authoritySrc = readFileSync(resolve(root, AMBIENT), 'utf8');
  for (const f of classB) {
    if (!authoritySrc.includes(f.presence)) {
      violations.push({
        file: AMBIENT,
        rule: 'authority-missing-facet',
        message:
          `${AMBIENT}: missing catalog facet \`${f.name}\` (presence \`${f.presence}\`) — the ambient-facets ` +
          `catalog declares it but the authority sheet does not provide it (574 §22.F positive coverage).`,
      });
    }
  }

  return { violations, classBCount: classB.length };
}

// ── CLI (back-compat) ─────────────────────────────────────────────────────────
if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const { violations, classBCount } = detect({});
  if (violations.length) {
    console.error(`ambient-purity gate FAIL (${violations.length}):\n  ${violations.map((v) => v.message).join('\n  ')}`);
    process.exit(1);
  }
  console.log(
    `ambient-purity gate OK — ${classBCount} catalog Class-B facet(s) banned outside + present in the ` +
      `authority; JfElement base enforced (574 §22.F, catalog-projected from ${CATALOG}).`,
  );
}
