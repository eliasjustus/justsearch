/**
 * Rule-file anchor scanner — prose-tier-register gate (tempdoc 530 §Meta-loop).
 *
 * Walks the scoped rule files (CLAUDE.md + the three load-bearing files
 * under `.claude/rules/`), extracts all `<!-- rule:<slug> -->` HTML-comment
 * anchors, and returns the set of slugs and the source file each lives in.
 *
 * The enforcer cross-references this set with the `Slug` column of the
 * tier-register. Anchors without a register row → `new-untagged-rule`;
 * register slugs without an anchor → `orphan-register-row`.
 */

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

/** Pattern that identifies a "load-bearing" sentence requiring a tier tag. */
export const MUST_NEVER_ALWAYS_PATTERN =
  /\b(must|must not|never|always|do not|you must|cannot|may not)\b/i;

/**
 * Operational docs that describe procedures rather than load-bearing rules.
 * Per the tempdoc 530 §Meta-loop survey, these are excluded from the rule-file
 * scope. The exclude list is explicit so a future addition under
 * `.claude/rules/` automatically extends scope unless added here.
 *
 * `tier-register.md` is the register itself — excluded so the scanner doesn't
 * detect its own rule-row text as candidate "must/never/always" sentences.
 *
 * `compaction-state.md` is auto-generated, gitignored, ephemeral session state
 * (compact-restore.mjs writes it on compaction and deletes it at session end —
 * it never exists in a clean CI checkout). It is not a rule file, and its
 * modified-files list can contain paths whose NAME embeds a trigger word (e.g.
 * tempdoc `620-always-loaded-…`), which would otherwise false-positive the
 * untagged-sentence scan (tempdoc 620 Part VI closure).
 */
export const EXCLUDED_RULE_FILES = new Set([
  '.claude/rules/tier-register.md',
  '.claude/rules/context-efficiency.md',
  '.claude/rules/hooks-reference.md',
  '.claude/rules/compaction-state.md',
]);

/**
 * Discover the rule-file scope at runtime. Returns `CLAUDE.md` plus every
 * `.claude/rules/*.md` minus the explicit excludes. Pass an explicit `files`
 * list to override (used by Pass-5 fixtures).
 *
 * @param {string} sourceRoot
 * @returns {string[]} repo-relative paths
 */
export function discoverRuleFiles(sourceRoot) {
  const out = [];
  const claudeMd = 'CLAUDE.md';
  if (existsSync(resolve(sourceRoot, claudeMd))) out.push(claudeMd);
  const rulesDir = resolve(sourceRoot, '.claude/rules');
  if (existsSync(rulesDir)) {
    let entries;
    try {
      entries = readdirSync(rulesDir, { withFileTypes: true });
    } catch {
      entries = [];
    }
    for (const ent of entries) {
      if (!ent.isFile() || !ent.name.endsWith('.md')) continue;
      const rel = `.claude/rules/${ent.name}`;
      if (EXCLUDED_RULE_FILES.has(rel)) continue;
      out.push(rel);
    }
  }
  return out;
}

/** Back-compat — Pass-4 hardcoded list. Now derived at runtime. */
export const DEFAULT_RULE_FILES = [
  'CLAUDE.md',
  '.claude/rules/branch-safety.md',
  '.claude/rules/agent-lessons.md',
  '.claude/rules/slice-execution.md',
];

/**
 * Scan rule files for `<!-- rule:<slug> -->` anchors.
 *
 * @param {{sourceRoot: string, files?: string[]}} options
 * @returns {Array<{slug: string, source: string}>}
 */
export function scanRuleAnchors(options) {
  const { sourceRoot } = options;
  const files = options.files ?? discoverRuleFiles(sourceRoot);
  const anchors = [];
  const re = /<!--\s*rule:([a-z][a-z0-9-]*)\s*-->/g;
  for (const rel of files) {
    const full = resolve(sourceRoot, rel);
    if (!existsSync(full)) continue;
    const content = readFileSync(full, 'utf8');
    for (const m of content.matchAll(re)) {
      anchors.push({ slug: m[1], source: rel });
    }
  }
  return anchors;
}

/**
 * Scan rule files for must/never/always sentences and group by enclosing
 * anchor scope.
 *
 * "Enclosing anchor" = the `<!-- rule:<slug> -->` anchor most recently seen
 * earlier in the file. Sentences before any anchor (or inside code blocks)
 * are reported with anchor `null`.
 *
 * @param {{sourceRoot: string, files?: string[]}} options
 * @returns {Array<{source: string, line: number, sentence: string, anchor: string|null}>}
 */
export function scanRuleSentences(options) {
  const { sourceRoot } = options;
  const files = options.files ?? discoverRuleFiles(sourceRoot);
  const sentences = [];
  const anchorRe = /<!--\s*rule:([a-z][a-z0-9-]*)\s*-->/;
  for (const rel of files) {
    const full = resolve(sourceRoot, rel);
    if (!existsSync(full)) continue;
    const content = readFileSync(full, 'utf8');
    const lines = content.split(/\r?\n/);
    let currentAnchor = null;
    let inCodeBlock = false;
    let i = 0;
    for (const raw of lines) {
      i++;
      const line = raw;
      // Track code-block boundaries. Lines inside ```...``` aren't rules.
      if (/^\s*```/.test(line)) {
        inCodeBlock = !inCodeBlock;
        continue;
      }
      if (inCodeBlock) continue;
      // Update current anchor (anchors on a line are scope-starts).
      const m = line.match(anchorRe);
      if (m) currentAnchor = m[1];
      // Detect must/never/always sentences.
      if (MUST_NEVER_ALWAYS_PATTERN.test(line)) {
        // Strip the anchor comment from the recorded sentence so the
        // text-set comparison is stable across cosmetic changes.
        const cleaned = line.replace(/<!--[^>]*-->/g, '').trim();
        if (cleaned.length === 0) continue;
        sentences.push({
          source: rel,
          line: i,
          sentence: cleaned,
          anchor: currentAnchor,
        });
      }
    }
  }
  return sentences;
}

/**
 * Extract structured "resolves to" markers from each row of tier-register.md.
 *
 * A row's `Resolves to` column carries one or more space- or comma-separated
 * tokens of the form `<kind>:<token>` where kind is `gate`, `hook`, or
 * `archunit`. Tokens may be backtick-delimited.
 *
 * @param {string} content
 * @returns {Array<{rowId: string, slug: string, tier: string, markers: Array<{kind: string, token: string}>}>}
 */
export function extractResolvesToMarkers(content) {
  const rows = [];
  const lines = content.split(/\r?\n/);
  let inTable = false;
  // We'll need to know which column is "Resolves to" — table header parser
  // approach: track headers per table.
  let headers = [];
  for (const raw of lines) {
    const line = raw.trim();
    if (!line.startsWith('|')) {
      inTable = false;
      headers = [];
      continue;
    }
    const cells = line
      .split('|')
      .slice(1, -1)
      .map(c => c.trim());
    if (cells.every(c => /^[-: ]+$/.test(c))) {
      inTable = true;
      continue;
    }
    if (!inTable) {
      // header row
      headers = cells.map(c => c.toLowerCase());
      continue;
    }
    // Data row
    if (cells.length < 4) continue;
    if (!/^\d+$/.test(cells[0])) continue;
    const rowId = `rule-${cells[0]}`;
    const slug = (cells[1] ?? '').replace(/`/g, '').trim();
    const tier = (cells[3] ?? '').replace(/`/g, '').split(/\s/)[0];
    const resolvesIdx = headers.indexOf('resolves to');
    const markers = [];
    if (resolvesIdx !== -1 && cells[resolvesIdx]) {
      const cell = cells[resolvesIdx];
      for (const tokMatch of cell.matchAll(/`(gate|hook|archunit):([^`]+)`/g)) {
        markers.push({ kind: tokMatch[1], token: tokMatch[2].trim() });
      }
    }
    rows.push({ rowId, slug, tier, markers });
  }
  return rows;
}

/**
 * Parse the `Slug` column from tier-register.md markdown tables.
 *
 * Tables in the register now have the shape (Pass-5):
 *
 *   | # | Slug | Rule | Tier | Resolves to | Catches violations via |
 *
 * We extract the slug (column index 1) for every numbered row.
 *
 * @param {string} content
 * @returns {Set<string>}
 */
export function extractRegisterSlugs(content) {
  const slugs = new Set();
  const lines = content.split(/\r?\n/);
  let inTable = false;
  for (const raw of lines) {
    const line = raw.trim();
    if (!line.startsWith('|')) {
      inTable = false;
      continue;
    }
    const cells = line
      .split('|')
      .slice(1, -1)
      .map(c => c.trim());
    if (cells.every(c => /^[-: ]+$/.test(c))) {
      inTable = true;
      continue;
    }
    if (!inTable) continue;
    if (cells.length < 2) continue;
    // First cell is `#`. Skip non-numeric rows (sub-headers).
    if (!/^\d+$/.test(cells[0])) continue;
    const slugCell = cells[1].replace(/`/g, '').trim();
    if (slugCell) slugs.add(slugCell);
  }
  return slugs;
}
