#!/usr/bin/env node
import fs from 'node:fs/promises';
import path from 'node:path';
import fg from 'fast-glob';

function usage() {
  console.log('Usage: node scripts/anchor-audit.mjs --root docs [--write] [--fix-links]');
}

function normSlashes(p) { return p.replace(/\\/g, '/'); }

const args = process.argv.slice(2);
if (!args.includes('--root')) {
  usage();
}
const ROOT = args.includes('--root') ? args[args.indexOf('--root') + 1] : 'docs';
const WRITE = args.includes('--write');
const FIX_LINKS = args.includes('--fix-links');

const rootDir = normSlashes(ROOT);
const patterns = [ `${rootDir}/**/*.md`, `!${rootDir}/_**/*` ];
const mdFiles = await fg(patterns, { dot: false });

const fileToAnchors = new Map(); // lowercased path -> Set(anchors)
const fileToAllIds = new Map();  // lowercased path -> Map(id -> count)
const fileContents = new Map();  // canonical path -> content

function canonicalDocPath(p) {
  let rel = normSlashes(p);
  if (!rel.startsWith('docs/')) rel = `docs/${rel.replace(/^\.?\/?/, '')}`;
  return rel;
}

function stripCode(raw) {
  // Remove fenced code blocks (``` or ~~~) and inline code to avoid false positives
  let s = raw.replace(/(^|\n)```[\s\S]*?\n```/g, '$1');
  s = s.replace(/(^|\n)~~~[\s\S]*?\n~~~/g, '$1');
  s = s.replace(/`[^`]*`/g, '');
  return s;
}

function extractAnchors(content) {
  const scan = stripCode(content);
  const anchors = new Set();
  const allIds = new Map();
  const reHeadWithId = /^#{1,6}\s+.*?\s*\{#([a-z0-9_-]+)\}\s*$/gim;
  const reHeadGeneral = /^#{1,6}\s+(.+?)\s*$/gim; // capture heading text for implicit slug IDs
  const reHtml = /<a\s+id="([a-z0-9-]+)"(?:\s+class="[^"]*")?\s*><\/a>/gim;

  // Explicit IDs
  for (const m of scan.matchAll(reHeadWithId)) {
    anchors.add(m[1]);
    allIds.set(m[1], (allIds.get(m[1]) || 0) + 1);
  }

  // Implicit slug IDs (like MkDocs/GitHub)
  for (const m of scan.matchAll(reHeadGeneral)) {
    // Skip headings that already declare an explicit ID to avoid double counting
    if (m[0].includes('{#')) continue;
    const text = m[1]
      .replace(/\{#([a-z0-9-]+)\}\s*$/, '') // strip explicit ID if present
      .replace(/`([^`]*)`/g, '$1'); // strip backticks
    const id = slugify(text);
    if (id && !anchors.has(id)) {
      anchors.add(id);
      allIds.set(id, (allIds.get(id) || 0) + 1);
    }
  }

  // HTML anchors
  for (const m of scan.matchAll(reHtml)) {
    anchors.add(m[1]);
    allIds.set(m[1], (allIds.get(m[1]) || 0) + 1);
  }
  return { anchors, allIds };
}

function findLinks(content) {
  const scan = stripCode(content);
  const links = []; // {full, hrefPath, anchor, startIdx, endIdx}
  const reLink = /\[[^\]]*\]\(([^)#\s]+)?#([^)#\s]+)[^)]*\)/g; // captures path? and anchor
  for (const m of scan.matchAll(reLink)) {
    links.push({ full: m[0], hrefPath: m[1] || '', anchor: m[2], startIdx: m.index, endIdx: m.index + m[0].length });
  }
  return links;
}

function resolveLink(fromPath, hrefPath, anchor) {
  const baseDir = path.posix.dirname(fromPath);
  const joined = hrefPath ? path.posix.normalize(path.posix.join(baseDir, hrefPath)) : fromPath;
  return { toPath: canonicalDocPath(joined).toLowerCase(), anchor: (anchor || '').toLowerCase() };
}

function slugify(text) {
  return text.toLowerCase().normalize('NFKD')
    .replace(/[^a-z0-9\s-]/g, '')
    .trim()
    .replace(/[\s-]+/g, '-');
}

let errors = [];

// Load files and anchors
for (const f of mdFiles) {
  const full = await fs.readFile(f, 'utf8');
  const canonical = canonicalDocPath(f);
  fileContents.set(canonical, full);
  const { anchors, allIds } = extractAnchors(full);
  fileToAnchors.set(canonical.toLowerCase(), anchors);
  fileToAllIds.set(canonical.toLowerCase(), allIds);
}

// Load alias map (optional)
let aliasMap = {};
try {
  const aliasRaw = await fs.readFile('docs/_config/anchor-aliases.json', 'utf8');
  aliasMap = JSON.parse(aliasRaw);
} catch {}

// Validate links and duplicates
const updates = new Map(); // path -> updated content
for (const [p, content] of fileContents.entries()) {
  // Duplicate ID detection
  const idCounts = fileToAllIds.get(p.toLowerCase());
  if (idCounts) {
    for (const [id, count] of idCounts.entries()) {
      if (count > 1) errors.push({ type: 'duplicate-id', file: p, id, count });
    }
  }

  const links = findLinks(content);
  if (links.length === 0) continue;
  let updated = content;
  let changed = false;
  for (const lnk of links) {
    // Skip external links (http/https/mailto or any scheme)
    if (lnk.hrefPath && /^(?:[a-z]+:)/i.test(lnk.hrefPath)) {
      continue;
    }
    const { toPath, anchor } = resolveLink(p, lnk.hrefPath, lnk.anchor);
    const targetAnchors = fileToAnchors.get(toPath);
    if (!targetAnchors) {
      errors.push({ type: 'missing-file', from: p, to: toPath });
      continue;
    }
    let anchorOk = targetAnchors.has(anchor);
    let candidate = null;
    if (!anchorOk) {
      // Try alias map using target path
      const relTo = toPath.replace(/^docs\//, '');
      const aliasForFile = aliasMap[relTo] || aliasMap[path.posix.basename(relTo)];
      if (aliasForFile && Object.prototype.hasOwnProperty.call(aliasForFile, anchor)) {
        candidate = aliasForFile[anchor];
        anchorOk = targetAnchors.has(candidate);
      }
    }
    if (!anchorOk) {
      // Heuristic: if any anchor startsWith requested anchor, suggest canonical
      candidate = candidate || [...targetAnchors].find(a => anchor && a && anchor.startsWith(a));
      if (candidate && FIX_LINKS) {
        const fixed = lnk.full.replace(/#([^)#\s]+)[^)]*/, `#${candidate}`);
        updated = updated.slice(0, lnk.startIdx) + fixed + updated.slice(lnk.endIdx);
        changed = true;
      } else {
        errors.push({ type: 'missing-anchor', from: p, to: toPath, anchor });
      }
    }
  }
  if (changed) updates.set(p, updated);
}

// Optionally write added IDs for headings missing one (bootstrap) — limited scope: warn only
if (WRITE) {
  // For simplicity in this first version, we only warn about headings missing IDs.
  // A future enhancement can safely insert {#slug} by parsing headings per-file.
  console.warn('NOTE: --write mode not yet inserting IDs automatically in this version.');
}

// Persist updates
for (const [p, updated] of updates.entries()) {
  await fs.writeFile(p, updated, 'utf8');
  console.log('Fixed links in', p);
}

if (errors.length > 0) {
  for (const e of errors) {
    if (e.type === 'duplicate-id') console.error(`Duplicate id '${e.id}' appears ${e.count}x in ${e.file}`);
    if (e.type === 'missing-file') console.error(`Missing file for link: ${e.from} -> ${e.to}`);
    if (e.type === 'missing-anchor') console.error(`Missing anchor: ${e.from} -> ${e.to}#${e.anchor}`);
  }
  process.exit(1);
} else {
  console.log('Anchor audit passed: all anchors resolve.');
}


