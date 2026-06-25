#!/usr/bin/env node
/**
 * Inventory repo prompt-like surfaces.
 *
 * This is a drift-control aid, not an agent quality metric. It reports where
 * prompt or instruction text lives, how large each surface is, whether it has
 * generated markers, and whether it contains suspicious stale tokens.
 */

import fs from "node:fs";
import path from "node:path";

const GENERATED_START = "<!-- generated:start";
const GENERATED_END = "<!-- generated:end";

const STALE_PATTERNS = [
  "justsearch_dev_",
  "ToolDefinition",
  "ToolRegistry",
  "Agent Live Eval",
  "RR219",
  "scripts/gate.ps1",
  "modules/app-ai",
  "modules/ai-bridge",
  "modules/ai-worker",
  "io/justsearch/aibridge",
  "VersionHandshakeClient",
];

function repoRootFromCwd() {
  let dir = process.cwd();
  for (;;) {
    if (
      fs.existsSync(path.join(dir, "settings.gradle.kts")) ||
      fs.existsSync(path.join(dir, ".git"))
    ) {
      return dir;
    }
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
    dir = parent;
  }
}

function walk(dir, predicate) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, ent.name);
    if (ent.isDirectory()) {
      out.push(...walk(full, predicate));
    } else if (!predicate || predicate(full)) {
      out.push(full);
    }
  }
  return out;
}

function uniqueSorted(values) {
  return [...new Set(values)].sort((a, b) => a.localeCompare(b, "en"));
}

function existing(root, rels) {
  return rels.map((rel) => path.join(root, rel)).filter((file) => fs.existsSync(file));
}

function collectSurfaces(root) {
  const surfaces = [];

  for (const file of existing(root, ["AGENTS.md", "CLAUDE.md", "docs/llms.txt"])) {
    surfaces.push({ file, kind: "repo-agent-root" });
  }

  for (const file of walk(path.join(root, ".claude", "skills"), (f) => f.endsWith("SKILL.md"))) {
    surfaces.push({ file, kind: "skill" });
  }

  for (const file of walk(path.join(root, ".claude", "rules"), (f) => f.endsWith(".md"))) {
    surfaces.push({ file, kind: "rule" });
  }

  for (const file of existing(root, [".claude/settings.json", ".claude/settings.local.json"])) {
    surfaces.push({ file, kind: "hook-routing" });
  }

  for (const file of walk(path.join(root, "scripts", "agent-analytics", "hooks"), (f) => f.endsWith(".mjs"))) {
    surfaces.push({ file, kind: "hook" });
  }

  for (const file of existing(root, ["scripts/ci/url-probe-system-prompt.md"])) {
    surfaces.push({ file, kind: "ci-eval-prompt" });
  }

  for (const file of walk(path.join(root, "scripts", "sandbox"), (f) => f.endsWith(".md"))) {
    surfaces.push({ file, kind: "sandbox-prompt" });
  }

  for (const file of walk(path.join(root, "SSOT", "prompts"), (f) => /\.(json|mustache|txt)$/i.test(f))) {
    surfaces.push({ file, kind: "product-ssot-prompt" });
  }

  for (const file of walk(path.join(root, "modules", "prompt-support"), (f) => f.endsWith(".java"))) {
    surfaces.push({ file, kind: "product-prompt-support" });
  }

  return uniqueSorted(surfaces.map((s) => `${s.kind}\0${s.file}`)).map((entry) => {
    const [kind, file] = entry.split("\0");
    return { kind, file };
  });
}

function analyze(root, surface) {
  const raw = fs.readFileSync(surface.file, "utf8");
  const rel = path.relative(root, surface.file).replaceAll("\\", "/");
  const lines = raw.length === 0 ? 0 : raw.split(/\r\n|\r|\n/).length;
  const words = raw.trim() === "" ? 0 : raw.trim().split(/\s+/).length;
  const generated = raw.includes(GENERATED_START) || raw.includes(GENERATED_END);
  const staleTokens = STALE_PATTERNS.filter((pattern) => raw.includes(pattern));
  return {
    ...surface,
    rel,
    lines,
    words,
    generated,
    staleTokens,
  };
}

function renderMarkdown(rows) {
  const totalWords = rows.reduce((sum, row) => sum + row.words, 0);
  const staleRows = rows.filter((row) => row.staleTokens.length > 0);
  const generatedRows = rows.filter((row) => row.generated);

  const out = [];
  out.push("# Prompt Surface Inventory");
  out.push("");
  out.push(`Surfaces: ${rows.length}`);
  out.push(`Total words: ${totalWords}`);
  out.push(`Generated-marker surfaces: ${generatedRows.length}`);
  out.push(`Surfaces with suspicious tokens: ${staleRows.length}`);
  out.push("");
  out.push("## Largest Surfaces");
  out.push("");
  out.push("| Surface | Kind | Lines | Words | Generated | Suspicious tokens |");
  out.push("| --- | --- | ---: | ---: | --- | --- |");

  for (const row of [...rows].sort((a, b) => b.words - a.words).slice(0, 30)) {
    out.push(
      `| \`${row.rel}\` | ${row.kind} | ${row.lines} | ${row.words} | ${
        row.generated ? "yes" : "no"
      } | ${row.staleTokens.join(", ") || "-"} |`,
    );
  }

  if (staleRows.length > 0) {
    out.push("");
    out.push("## Suspicious Tokens");
    out.push("");
    for (const row of staleRows) {
      out.push(`- \`${row.rel}\`: ${row.staleTokens.join(", ")}`);
    }
  }

  out.push("");
  return out.join("\n");
}

function main() {
  const root = repoRootFromCwd();
  const rows = collectSurfaces(root).map((surface) => analyze(root, surface));
  if (process.argv.includes("--json")) {
    process.stdout.write(JSON.stringify(rows, null, 2) + "\n");
    return;
  }
  process.stdout.write(renderMarkdown(rows));
}

main();
