/**
 * Insert “noncanonical” banners into docs/tempdocs and docs/future-features.
 *
 * Rules:
 * - If file starts with YAML frontmatter (--- ... ---), insert banner AFTER frontmatter block.
 * - Otherwise insert banner at the top.
 * - Tempdocs 13/* gets a special “draft spec packet” banner.
 *
 * Idempotent: if the banner already exists, do nothing.
 */

import fs from "node:fs";
import path from "node:path";

const repoRoot = process.cwd();

const BANNER_NONCANONICAL =
  "> NOTE: Noncanonical doc (notes/ideas). May drift. Verify against docs/explanation + docs/reference + code.";
const BANNER_DRAFT_SPEC_PACKET =
  "> NOTE: Draft spec packet. May drift. Canonical behavior is defined by code + docs/explanation/reference.";

function isMarkdown(p) {
  return p.toLowerCase().endsWith(".md");
}

function walk(dir) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, ent.name);
    if (ent.isDirectory()) out.push(...walk(full));
    else out.push(full);
  }
  return out;
}

function hasFrontmatter(lines) {
  return lines.length > 0 && lines[0].trim() === "---";
}

function findFrontmatterEnd(lines) {
  // lines[0] is --- already.
  for (let i = 1; i < lines.length; i++) {
    if (lines[i].trim() === "---") return i;
  }
  return -1;
}

function insertAfterFrontmatter(lines, banner) {
  const end = findFrontmatterEnd(lines);
  if (end < 0) return null;

  // Insert after the closing --- and one blank line.
  const before = lines.slice(0, end + 1);
  const after = lines.slice(end + 1);

  // Ensure there is exactly one blank line before banner (for readability).
  const normalizedAfter =
    after.length > 0 && after[0].trim() === "" ? after.slice(1) : after;

  return [...before, "", banner, "", ...normalizedAfter];
}

function insertAtTop(lines, banner) {
  return [banner, "", ...lines];
}

function alreadyHasBanner(lines, banner) {
  return lines.some((l) => l.trim() === banner.trim());
}

function processFile(filePath) {
  const rel = path.relative(repoRoot, filePath).replaceAll("\\", "/");

  const banner = rel.startsWith("docs/tempdocs/13/")
    ? BANNER_DRAFT_SPEC_PACKET
    : BANNER_NONCANONICAL;

  const raw = fs.readFileSync(filePath, "utf8");
  const lines = raw.split(/\r?\n/);

  if (alreadyHasBanner(lines, banner)) return { rel, changed: false };

  let nextLines = null;
  if (hasFrontmatter(lines)) {
    nextLines = insertAfterFrontmatter(lines, banner);
  }
  if (!nextLines) {
    nextLines = insertAtTop(lines, banner);
  }

  const next = nextLines.join("\n");
  if (next !== raw) {
    fs.writeFileSync(filePath, next.endsWith("\n") ? next : next + "\n", "utf8");
    return { rel, changed: true };
  }
  return { rel, changed: false };
}

function main() {
  const targets = [
    path.join(repoRoot, "docs", "tempdocs"),
    path.join(repoRoot, "docs", "future-features"),
  ];

  const mdFiles = targets.flatMap((d) => walk(d)).filter(isMarkdown);

  let changed = 0;
  for (const f of mdFiles) {
    const res = processFile(f);
    if (res.changed) changed++;
  }

  console.log(`insert-noncanonical-banners: files=${mdFiles.length} changed=${changed}`);
}

main();


