#!/usr/bin/env node
/**
 * Dead-doc relevance report — the "Retire" signal of the tempdoc 579
 * behavioral-protocol pilot.
 *
 * Canonical docs are immortal once created, yet agents read them ~0.1% of the
 * time (this session: 19 canonical reads / 13,576). A doc that is read 0x AND
 * wired into no skill is a generate / deliver / archive candidate — it earns no
 * keep on agent-consumption grounds (humans are unmeasured; see caveat in output).
 *
 * Warn-only by default: always exits 0 (mirrors report-reliability-budget.mjs).
 * Pass --fail-on-dead to make a non-empty dead set a failure (not wired anywhere).
 *
 * Method (reuses the agent-analytics telemetry substrate):
 *  1. Discover transcripts from telemetry session events (loadEvents/groupBySession).
 *  2. Tally every Read tool_use whose file_path is a canonical doc.
 *  3. Cross-reference the auto-delivery channels (skills-sync + the Consult hook).
 *  4. Tier by origin: REVIEW (genuinely reviewable) vs EXPECTED-0-read (ADRs / issue
 *     logs / how-to / contributing / generated — 0-read is normal, keep).
 *  5. Emit tmp/doc-relevance/latest.{json,md}.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { loadEvents, groupBySession, repoRoot } from '../agent-analytics/lib/telemetry-io.mjs';

const CANONICAL_DIRS = ['docs/explanation', 'docs/reference', 'docs/how-to', 'docs/decisions'];
const OUT_JSON = path.join(repoRoot, 'tmp', 'doc-relevance', 'latest.json');
const OUT_MD = path.join(repoRoot, 'tmp', 'doc-relevance', 'latest.md');

function walkMd(dir) {
  const out = [];
  const abs = path.join(repoRoot, dir);
  if (!fs.existsSync(abs)) return out;
  for (const ent of fs.readdirSync(abs, { withFileTypes: true })) {
    const rel = `${dir}/${ent.name}`;
    if (ent.isDirectory()) out.push(...walkMd(rel));
    else if (ent.name.toLowerCase().endsWith('.md')) out.push(rel);
  }
  return out;
}

// Docs auto-delivered to agents: skills (skills-sync) + the Consult hook
// (consult-doc-hint pushes its governing docs at edit time — also a delivery channel).
function deliveredDocs() {
  const out = new Set();
  for (const rel of [
    'scripts/docs/skills-sync.mjs',
    'scripts/agent-analytics/hooks/consult-doc-hint.mjs',
  ]) {
    const p = path.join(repoRoot, rel);
    if (!fs.existsSync(p)) continue;
    const src = fs.readFileSync(p, 'utf8');
    for (const m of src.matchAll(/(docs\/[A-Za-z0-9_.\-/]+\.md)/g)) out.add(m[1]);
  }
  return out;
}

// Transcript paths from telemetry session events (main + subagent).
function transcriptPaths() {
  const paths = new Set();
  const sessions = groupBySession(loadEvents());
  for (const events of sessions.values()) {
    for (const e of events) {
      if (e.event === 'session_start' && e.transcript_path) paths.add(e.transcript_path);
      if (e.event === 'subagent_stop' && e.agent_transcript_path) paths.add(e.agent_transcript_path);
    }
  }
  return [...paths];
}

const READ_RE = /"name":\s*"Read"[\s\S]*?"file_path":\s*"((?:[^"\\]|\\.)*)"/g;
const norm = (p) => p.replace(/\\\\/g, '/').replace(/\\/g, '/');

function tallyReads(transcripts) {
  const counts = new Map(); // canonical rel path -> read count
  let totalReads = 0;
  let transcriptsRead = 0;
  for (const tp of transcripts) {
    if (!fs.existsSync(tp)) continue;
    transcriptsRead++;
    const text = fs.readFileSync(tp, 'utf8');
    for (const m of text.matchAll(READ_RE)) {
      totalReads++;
      const fp = norm(m[1]);
      const rel = CANONICAL_DIRS.map((d) => {
        const i = fp.indexOf(d + '/');
        return i >= 0 ? fp.slice(i) : null;
      }).find(Boolean);
      if (rel) counts.set(rel, (counts.get(rel) ?? 0) + 1);
    }
  }
  return { counts, totalReads, transcriptsRead };
}

// Read-frequency alone is too weak a death signal: ADRs (human "why" archaeology),
// issue logs + how-to runbooks (situational), and generated docs (projections that
// can't drift) are all legitimately 0-read. Classify those as EXPECTED, leaving the
// genuinely-reviewable tail (description-style explanation/reference docs).
function isGenerated(doc) {
  try {
    const head = fs.readFileSync(path.join(repoRoot, doc), 'utf8').slice(0, 2000);
    return /<!--\s*generated|is \*\*generated\*\*|do not edit between markers/i.test(head);
  } catch {
    return false;
  }
}
function frontmatterStatus(doc) {
  try {
    const head = fs.readFileSync(path.join(repoRoot, doc), 'utf8').slice(0, 600);
    const m = head.match(/^status:\s*"?([a-z-]+)"?/im);
    return m ? m[1].toLowerCase() : null;
  } catch {
    return null;
  }
}
function classifyDead(doc) {
  if (doc.startsWith('docs/decisions/')) return 'decision (human archival)';
  if (doc.startsWith('docs/reference/issues/')) return 'issue log (situational)';
  if (doc.startsWith('docs/reference/contributing/')) return 'contributing/process (situational)';
  if (doc.startsWith('docs/how-to/')) return 'how-to (situational)';
  if (isGenerated(doc)) return 'generated (projection)';
  // Self-declared non-current docs (draft specs, redirect stubs) — 0-read is expected.
  const st = frontmatterStatus(doc);
  if (st && /^(draft|redirect|deprecated|superseded)$/.test(st)) return `non-stable status (${st})`;
  return 'review';
}

function main() {
  const failOnDead = process.argv.includes('--fail-on-dead');
  const canonical = CANONICAL_DIRS.flatMap(walkMd);
  const wired = deliveredDocs();
  const { counts, totalReads, transcriptsRead } = tallyReads(transcriptPaths());

  const rows = canonical.map((doc) => ({
    doc,
    reads: counts.get(doc) ?? 0,
    skillWired: wired.has(doc),
  }));
  // 0-read AND no delivery channel, split by origin: REVIEW = the genuinely
  // reviewable tail; EXPECTED = classes where 0-read is normal (don't archive).
  const allDead = rows
    .filter((r) => r.reads === 0 && !r.skillWired)
    .map((r) => ({ ...r, klass: classifyDead(r.doc) }))
    .sort((a, b) => a.doc.localeCompare(b.doc));
  const review = allDead.filter((r) => r.klass === 'review');
  const expected = allDead.filter((r) => r.klass !== 'review');

  const report = {
    generatedFromTranscripts: transcriptsRead,
    totalReadCalls: totalReads,
    canonicalDocCount: canonical.length,
    skillWiredCount: rows.filter((r) => r.skillWired).length,
    readAtLeastOnce: rows.filter((r) => r.reads > 0).length,
    reviewCount: review.length,
    expectedZeroReadCount: expected.length,
    reviewCandidates: review,
    expectedZeroRead: expected,
  };

  fs.mkdirSync(path.dirname(OUT_JSON), { recursive: true });
  fs.writeFileSync(OUT_JSON, JSON.stringify(report, null, 2));

  const md = [
    '# Doc relevance — generate / deliver / archive candidates',
    '',
    `Transcripts scanned: **${transcriptsRead}** · Read calls: **${totalReads}** · Canonical docs: **${canonical.length}**`,
    `Read at least once: **${report.readAtLeastOnce}** · Delivered (skill or Consult hook): **${report.skillWiredCount}**`,
    '',
    `## Review candidates (${review.length}) — 0-read, unwired, and not an expected-0-read class`,
    '',
    '> The genuinely reviewable tail: description-style docs read 0x and delivered nowhere.',
    '> Per doc: **generate** (if code-derivable), **wire** into a skill/CLAUDE.md (valuable out-of-code',
    '> knowledge), or **archive**. Read-frequency cannot prove deadness — a 0-read doc may still be',
    '> situational; this is a human/agent review prompt, not an auto-archiver.',
    '',
    ...(review.length ? review.map((r) => `- \`${r.doc}\``) : ['_(none)_']),
    '',
    `## Expected 0-read (${expected.length}) — keep, not dead`,
    '',
    '> 0-read is normal for these classes (humans are an unmeasured consumer): ADRs are "why"',
    '> archaeology, issue logs + how-to are situational, generated docs are drift-proof projections.',
    '',
    ...(expected.length ? expected.map((r) => `- \`${r.doc}\` — _${r.klass}_`) : ['_(none)_']),
    '',
  ].join('\n');
  fs.writeFileSync(OUT_MD, md);

  console.log(`report-doc-relevance: ${review.length} review candidate(s) (+ ${expected.length} expected-0-read) of ${canonical.length} canonical docs ` +
    `(transcripts=${transcriptsRead}, reads=${totalReads}). Wrote ${path.relative(repoRoot, OUT_MD)}.`);

  if (failOnDead && review.length > 0) process.exitCode = 1;
}

main();
