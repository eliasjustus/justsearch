#!/usr/bin/env node
// Triage stale non-terminal tempdocs.
//
// "Stale" = current status is `open` or `active` AND the file hasn't
// been touched in 30+ days (per git log, not filesystem mtime).
//
// Per `docs/tempdocs/README.md`: stale tempdocs must reach a terminal
// state — resume work, mark `done`/`shipped`, or supersede.
//
// This script classifies each stale tempdoc by signal:
//
//   - CLOSED:     body contains explicit closure marker
//                 (e.g. "Resolved", "Closure:", "Done (YYYY-MM-DD)",
//                 "Bottom line:", "Promoted to") and no recent activity
//   - SUPERSEDED: body references a successor tempdoc by title or
//                 contains "Superseded by"
//   - INVESTIGATION:  doc shape is "Scope for Agent" / "Findings" /
//                 "Research" — an investigation that produced
//                 findings; safe to mark `done` (terminal by nature)
//   - ABANDONED:  TODO list with old dates, no closure, no successor —
//                 mark `done` with a note that scope was deferred
//   - AMBIGUOUS:  needs human judgment (recent references / unclear)
//
// Default: dry-run (prints classification). `--apply` writes status
// changes + adds a "Staleness review" note to the body explaining the
// transition.
//
// `--apply-class=CLOSED,SUPERSEDED,INVESTIGATION` to apply only certain
// classes (default if `--apply` is used: CLOSED + SUPERSEDED only).

import fs from 'node:fs';
import { execSync } from 'node:child_process';
import fg from 'fast-glob';
import path from 'node:path';

const STALE_DAYS = 30;
const today = new Date();

function daysAgo(dateStr) {
  if (!dateStr) return null;
  const d = new Date(dateStr);
  if (isNaN(d)) return null;
  return Math.floor((today - d) / (1000 * 60 * 60 * 24));
}

function readFrontmatterStatus(raw) {
  const m = raw.match(/^---\s*\n([\s\S]*?)\n---\s*(\n|$)/);
  if (!m) return null;
  const line = m[1].match(/^status:\s*(.+?)\s*$/m);
  return line ? line[1].trim() : null;
}

function readInlineStatus(raw) {
  const m = raw.match(/^\*\*Status\*\*:\s*(.+?)\s*$/m);
  return m ? m[1].trim() : null;
}

function lastGitDate(file) {
  try {
    const out = execSync(`git log -1 --format=%ad --date=format:%Y-%m-%d -- "${file}"`, {
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe'],
    }).trim();
    return out || null;
  } catch {
    return null;
  }
}

function classify(file, raw) {
  // Skip README + non-canonical-name files
  if (path.basename(file) === 'README.md') return null;

  // Get current status
  const yaml = readFrontmatterStatus(raw);
  const inline = readInlineStatus(raw);
  const statusRaw = yaml || inline;
  if (!statusRaw) return null;

  const base = statusRaw.toLowerCase().split(/[—–:;(\s]/, 1)[0].replace(/[*"]/g, '');
  if (base !== 'open' && base !== 'active') return null;

  // Get last git date
  const lastDate = lastGitDate(file);
  const ageDays = daysAgo(lastDate);
  if (ageDays === null || ageDays < STALE_DAYS) return null;

  // Heuristic classification
  const body = raw;

  // SUPERSEDED: only fire if the tempdoc itself declares it's been
  // superseded — header/metadata region only, NOT body tables (which
  // often mention OTHER docs being superseded by this one).
  const headRegion = body.slice(0, 1500);
  if (/^\*\*Status\*\*:\s*superseded/im.test(headRegion) ||
      /^status:\s*superseded/im.test(headRegion) ||
      /^\*\*Superseded by[:\s]/im.test(headRegion) ||
      /^\s*>?\s*Superseded by [`'"]?(tempdoc|`)/im.test(headRegion)) {
    return { file, ageDays, status: statusRaw, klass: 'SUPERSEDED' };
  }

  // CLOSED: explicit closure markers near end of body
  const last2000 = body.slice(-2000);
  if (/\b(Resolved|Closure|Closed|Done|Completed|Bottom line|Final outcome|Outcome):/i.test(last2000) ||
      /\bPromoted to (canonical|ADR|`docs\/)/i.test(body) ||
      /\*\*Outcome\b/i.test(last2000)) {
    return { file, ageDays, status: statusRaw, klass: 'CLOSED' };
  }

  // INVESTIGATION: research / audit / findings shape (terminal by nature)
  if (/^title:.*(Research|Audit|Investigation|Findings|Eval|Scan|Analysis)/im.test(body) ||
      /\b(Scope for Agent|Open Questions|Investigation log|Research Notes)\b/i.test(body)) {
    return { file, ageDays, status: statusRaw, klass: 'INVESTIGATION' };
  }

  // ABANDONED: contains TODO list and stale, no closure, no successor
  // We use a soft signal: stale > 60 days + status `open` + no recent edits.
  if (base === 'open' && ageDays > 60) {
    return { file, ageDays, status: statusRaw, klass: 'ABANDONED' };
  }

  // AMBIGUOUS: needs human judgment
  return { file, ageDays, status: statusRaw, klass: 'AMBIGUOUS' };
}

// ---- main ----

const apply = process.argv.includes('--apply');
const applyClassArg = process.argv.find(a => a.startsWith('--apply-class='));
const applyClasses = applyClassArg
  ? new Set(applyClassArg.slice('--apply-class='.length).split(','))
  : new Set(['CLOSED', 'SUPERSEDED']);

const files = await fg([
  'docs/tempdocs/**/*.md',
  '!docs/tempdocs/README.md',
]);

const buckets = {
  CLOSED: [],
  SUPERSEDED: [],
  INVESTIGATION: [],
  ABANDONED: [],
  AMBIGUOUS: [],
};

for (const f of files) {
  const raw = fs.readFileSync(f, 'utf8');
  const result = classify(f, raw);
  if (result) buckets[result.klass].push(result);
}

const total = Object.values(buckets).flat().length;
console.log(`Stale tempdocs (non-terminal, >${STALE_DAYS} days old): ${total}\n`);

for (const klass of ['CLOSED', 'SUPERSEDED', 'INVESTIGATION', 'ABANDONED', 'AMBIGUOUS']) {
  const items = buckets[klass];
  items.sort((a, b) => b.ageDays - a.ageDays);
  const applyMark = applyClasses.has(klass) ? '[would apply]' : '[manual]';
  console.log(`## ${klass} (${items.length}) ${apply ? applyMark : ''}`);
  for (const r of items) {
    console.log(`  ${r.ageDays}d  ${r.file}  (was: "${r.status}")`);
  }
  console.log('');
}

// Apply transitions
const TODAY = today.toISOString().slice(0, 10);

const CLOSURE_NOTES = {
  CLOSED: 'Body contains explicit closure markers; marking `done` as part of the staleness audit.',
  SUPERSEDED: 'Body declares a successor; marking `superseded` as part of the staleness audit.',
  INVESTIGATION: 'Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README\'s "investigation log that produced a decision" definition. Body content preserved as design history.',
  ABANDONED: 'Open with no closure activity in >60 days. Marking `done` to clear the staleness signal; body content preserved as design history. If this work should resume, open a new tempdoc per the title-linking convention.',
};

function appendClosureNote(raw, klass, ageDays) {
  // Avoid double-appending
  if (/^## Staleness review \(\d{4}-\d{2}-\d{2}\)$/m.test(raw)) return raw;

  const note = `

---

## Staleness review (${TODAY})

${CLOSURE_NOTES[klass]} Classification: ${klass}. Stale for ${ageDays} days at audit time.
`;
  // Ensure trailing newline
  const trimmed = raw.replace(/\s+$/, '');
  return trimmed + note + '\n';
}

if (apply) {
  let modified = 0;
  for (const klass of applyClasses) {
    for (const r of buckets[klass] || []) {
      const raw = fs.readFileSync(r.file, 'utf8');
      let next = raw;

      // Determine target status
      let target;
      switch (klass) {
        case 'SUPERSEDED': target = 'superseded'; break;
        case 'CLOSED':     target = 'done'; break;
        case 'INVESTIGATION': target = 'done'; break;
        case 'ABANDONED':  target = 'done'; break;
        default:           continue;
      }

      // Rewrite YAML status if present
      const fmMatch = next.match(/^(---\s*\n)([\s\S]*?)(\n---\s*(?:\n|$))/);
      if (fmMatch) {
        const [whole, open, block, close] = fmMatch;
        if (/^status:/m.test(block)) {
          const newBlock = block.replace(/^(status:\s*).+$/m, `$1${target}`);
          next = next.replace(whole, open + newBlock + close);
        }
      }
      // Rewrite inline **Status** if present
      next = next.replace(/^(\*\*Status\*\*:\s*).+$/m, `$1${target}`);

      // Append closure note
      next = appendClosureNote(next, klass, r.ageDays);

      if (next !== raw) {
        fs.writeFileSync(r.file, next);
        modified++;
      }
    }
  }
  console.log(`\nApplied: ${modified} tempdoc(s) marked terminal.`);
}
