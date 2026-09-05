import fs from 'fs';
import fg from 'fast-glob';
import matter from 'gray-matter';
import { execFileSync } from 'node:child_process';

const files = await fg(['docs/**/*.md', '!docs/_**/*']);

// Blank out fenced code blocks so shell/YAML comments inside them are not read as headings.
function stripFencedCode(md) {
  const out = [];
  let fence = null;
  for (const line of md.split(/\r?\n/)) {
    const m = line.match(/^\s{0,3}(`{3,}|~{3,})/);
    if (fence) {
      if (m && m[1][0] === fence[0] && m[1].length >= fence.length) fence = null;
      out.push('');
      continue;
    }
    if (m) {
      fence = m[1];
      out.push('');
      continue;
    }
    out.push(line);
  }
  return out.join('\n');
}

let issues = [];

for (const f of files) {
  const raw = fs.readFileSync(f, 'utf8');
  // Tempdocs are append-only dated history with a freeform front matter contract
  // (docs/tempdocs/README.md \u00A7Frontmatter) and no heading contract at all. They get the
  // front-matter checks (other tooling parses that) and nothing else: applying canonical heading
  // rules to files the repo forbids rewriting produced ~80 permanent findings (tempdoc 932), and
  // one "encoding" hit there is a U+FFFD kept on purpose as recorded evidence (743 \u00A7piped-python).
  const isTempdoc = /^docs\/tempdocs\//.test(f.replaceAll('\\', '/'));
  if (!isTempdoc && raw.includes('\uFFFD')) {
    issues.push({ file: f, kind: 'encoding', msg: 'Found U+FFFD replacement character' });
  }
  // A single malformed front matter must be a FINDING, not a crash that hides every other
  // result (872: two tempdocs each killed the whole run for weeks, unreported).
  let fm;
  try {
    fm = matter(raw);
  } catch (e) {
    issues.push({ file: f, kind: 'frontmatter-parse', severity: 'error', msg: `Front matter does not parse: ${e.reason || e.message}` });
    continue;
  }
  const data = fm.data || {};
  // Detect duplicate front-matter keys (best-effort, top-level only)
  if (fm.matter && fm.matter.startsWith('---')) {
    const linesFM = fm.matter.split(/\r?\n/).slice(1); // skip leading ---
    const keyCounts = new Map();
    for (const line of linesFM) {
      if (/^---\s*$/.test(line)) break; // end of front matter
      if (/^\s*#/.test(line)) continue; // comment
      if (/^\s*-\s/.test(line)) continue; // list item
      const m = line.match(/^([A-Za-z0-9_\-]+):\s*(.*)$/);
      if (m) {
        const k = m[1];
        const c = keyCounts.get(k) || 0;
        keyCounts.set(k, c + 1);
      }
    }
    for (const [k, c] of keyCounts.entries()) {
      if (c > 1) {
        issues.push({ file: f, kind: 'frontmatter-duplicate-key', severity: 'error', msg: `Duplicate front-matter key: ${k}` });
      }
    }
  }
  if (isTempdoc) continue;
  // Skip generated/lint files already excluded
  const content = fm.content || '';
  const lines = content.split(/\r?\n/);
  // Heading checks read the prose only: a `# comment` line inside a fenced shell block is not
  // a heading (tempdoc 932 — this counted 37 "H1 headings" in jseval-pipeline-reference.md).
  const prose = stripFencedCode(content);
  let idx = 0;
  while (idx < lines.length && /^\s*$/.test(lines[idx])) idx++;
  const first = lines[idx] || '';
  // Enforce: exactly one H1 and it must match front matter title
  const h1Matches = prose.match(/^#\s+(.+?)\s*$/gm) || [];
  if (h1Matches.length === 0) {
    issues.push({ file: f, kind: 'heading', severity: 'error', msg: 'Missing top-level H1' });
  }
  if (h1Matches.length > 1) {
    issues.push({ file: f, kind: 'heading', severity: 'error', msg: `Multiple H1 headings (${h1Matches.length})` });
  }
  // Two rules were removed (tempdoc 932) because they enforced conventions this repo never
  // adopted and so permanently masked this script's exit code:
  //  - `heading-case` (Title Case H1/H2): the repo writes sentence-case headings; 7164 findings.
  //  - "H1 must equal front-matter `title`": `title` is the short index label (what
  //    llmstxt-generate.mjs indexes) and the H1 is the display heading — 48/49 ADRs carry an
  //    `ADR-NNNN:` prefix, 23/25 explanation docs a `NN.` prefix; 400 findings.
  // Do not replace either with the opposite convention — neither has been decided.
  if (/^#\s+Untitled\s*$/.test(first)) {
    issues.push({ file: f, kind: 'heading', severity: 'error', msg: 'Top-level H1 is "Untitled"' });
  }

  // Conditional requirements for normative docs
  const status = String(data.status || '').toLowerCase();
  if (status === 'normative') {
    const requiredFields = ['summary', 'audience', 'stability', 'version_introduced'];
    for (const req of requiredFields) {
      if (!data[req] || String(data[req]).trim() === '') {
        issues.push({ file: f, kind: 'meta', severity: 'error', msg: `Missing required field for normative doc: ${req}` });
      }
    }
    // Require key sections to exist
    const mustHaveHeadings = ['at a glance', 'constraints', 'interfaces', 'examples'];
    const text = content.toLowerCase();
    for (const h of mustHaveHeadings) {
      if (!new RegExp(`^##\\s+${h}($|\n)`, 'm').test(text)) {
        issues.push({ file: f, kind: 'structure', severity: 'error', msg: `Missing required heading: ## ${h}` });
      }
    }

    // Detect exact duplicate bullets within a MUSTs list block under At a glance
    for (let i = 0; i < lines.length; i++) {
      if (/^\s*-\s*MUSTs/i.test(lines[i])) {
        const seen = new Set();
        let j = i + 1;
        for (; j < lines.length; j++) {
          const m = lines[j].match(/^\s*-\s+(.*\S)\s*$/);
          if (!m) break;
          const item = m[1];
          if (seen.has(item)) {
            issues.push({ file: f, kind: 'musts-duplicate', severity: 'error', msg: `Duplicate MUST bullet: ${item}` });
          } else {
            seen.add(item);
          }
        }
      }
    }
  }

  // Soft requirements: min tags and aliases (warn only)
  const tags = Array.isArray(data.tags) ? data.tags : [];
  const aliases = Array.isArray(data.aliases) ? data.aliases : [];
  const isLegacy = String(data.status || '').toLowerCase() === 'legacy';
  if (!isLegacy) {
    if (tags.length < 3) {
      issues.push({ file: f, kind: 'tags', severity: 'warn', msg: `Few tags (${tags.length}); expected at least 3` });
    }
    if (aliases.length < 2) {
      issues.push({ file: f, kind: 'aliases', severity: 'warn', msg: `Few aliases (${aliases.length}); expected at least 2` });
    }
  }
}

if (issues.length > 0) {
  const errors = issues.filter((i) => (i.severity || 'error') === 'error');
  const warns = issues.filter((i) => (i.severity || 'error') === 'warn');
  if (warns.length > 0) {
    console.warn('Docs validation warnings:');
    for (const i of warns) console.warn(`- ${i.file}: [${i.kind}] ${i.msg}`);
  }
  if (errors.length > 0) {
    console.error('Docs validation errors:');
    for (const i of errors) console.error(`- ${i.file}: [${i.kind}] ${i.msg}`);
    process.exit(1);
  }
}

execFileSync(process.execPath, ['scripts/docs/verify-runtime-config-matrix.mjs'], {
  stdio: 'inherit',
});
// tempdoc-status-check was retired (tempdoc 618 §14): its canonical-status set
// diverged from practice, it was never CI-wired, and it sat permanently red on
// ~20 tempdocs — an always-red, unread check hides real signal. The canonical
// status values remain documented as advisory guidance in docs/tempdocs/README.md.
console.log('Docs validation completed. No errors.');
