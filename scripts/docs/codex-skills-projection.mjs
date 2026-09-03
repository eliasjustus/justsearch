#!/usr/bin/env node
/**
 * Deterministic Claude Code -> Codex repository-skill projection.
 *
 * `.claude/skills` remains the authoring surface while the project supports
 * both harnesses. Codex discovers `.agents/skills`, whose SKILL.md contract
 * requires a name and description and whose explicit-only policy belongs in
 * agents/openai.yaml. Generated Codex files are committed and never edited by
 * hand; this projection is the drift boundary.
 */

import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import matter from 'gray-matter';

const GENERATED_NOTICE =
  '<!-- generated from .claude/skills by scripts/docs/codex-skills-projection.mjs; do not edit -->';
const CODEX_NOTE =
  '> Codex projection: `$skill-name` is the equivalent of a Claude `/skill-name` invocation. ' +
  'When this workflow names a Claude-only tool, use the available Codex capability that preserves the same policy and acceptance criteria.';

function repoRootFromCwd() {
  let dir = process.cwd();
  for (;;) {
    if (fs.existsSync(path.join(dir, 'settings.gradle.kts')) || fs.existsSync(path.join(dir, '.git'))) {
      return dir;
    }
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
    dir = parent;
  }
}
function normalizeNewlines(text) {
  return text.replace(/\r\n/g, '\n');
}

function projectedSkill(sourcePath, skillName) {
  const parsed = matter(normalizeNewlines(fs.readFileSync(sourcePath, 'utf8')));
  const description = parsed.data.description;
  if (typeof description !== 'string' || description.trim() === '') {
    throw new Error(`${path.relative(process.cwd(), sourcePath)} has no non-empty description`);
  }

  const data = {
    name: skillName,
    description,
  };
  for (const key of ['license', 'compatibility', 'metadata']) {
    if (parsed.data[key] != null) data[key] = parsed.data[key];
  }

  const body = `${GENERATED_NOTICE}\n\n${CODEX_NOTE}\n\n${parsed.content.trimStart()}`;
  return matter.stringify(body, data).replace(/\r\n/g, '\n').replace(/\s*$/, '\n');
}

function expectedFiles(root) {
  const sourceRoot = path.join(root, '.claude', 'skills');
  const targetRoot = path.join(root, '.agents', 'skills');
  const expected = new Map();

  const dirs = fs.readdirSync(sourceRoot, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .sort((a, b) => a.name.localeCompare(b.name, 'en'));

  for (const dir of dirs) {
    const source = path.join(sourceRoot, dir.name, 'SKILL.md');
    if (!fs.existsSync(source)) continue;
    expected.set(path.join(targetRoot, dir.name, 'SKILL.md'), projectedSkill(source, dir.name));

    const parsed = matter(fs.readFileSync(source, 'utf8'));
    if (parsed.data['disable-model-invocation'] === true) {
      expected.set(
        path.join(targetRoot, dir.name, 'agents', 'openai.yaml'),
        'policy:\n  allow_implicit_invocation: false\n',
      );
    }
  }
  return { targetRoot, expected };
}

function onDiskFiles(dir) {
  if (!fs.existsSync(dir)) return [];
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...onDiskFiles(full));
    else if (entry.isFile()) out.push(full);
  }
  return out;
}

export function syncCodexSkills({ root = repoRootFromCwd(), check = false } = {}) {
  const { targetRoot, expected } = expectedFiles(root);
  const actual = new Set(onDiskFiles(targetRoot));
  const drift = [];

  for (const [file, content] of expected) {
    actual.delete(file);
    const before = fs.existsSync(file) ? normalizeNewlines(fs.readFileSync(file, 'utf8')) : null;
    if (before === content) continue;
    if (check) {
      drift.push(path.relative(root, file).replaceAll('\\', '/'));
    } else {
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(file, content, 'utf8');
    }
  }

  for (const extra of actual) {
    if (check) {
      drift.push(path.relative(root, extra).replaceAll('\\', '/'));
    } else {
      fs.rmSync(extra);
    }
  }

  if (!check) {
    // Remove now-empty directories left when an explicit-only policy disappears.
    for (const dir of fs.readdirSync(targetRoot, { withFileTypes: true }).filter((e) => e.isDirectory())) {
      const agents = path.join(targetRoot, dir.name, 'agents');
      if (fs.existsSync(agents) && fs.readdirSync(agents).length === 0) fs.rmdirSync(agents);
    }
  }

  const skillCount = [...expected.keys()].filter((file) => file.endsWith(`${path.sep}SKILL.md`)).length;
  if (drift.length > 0) {
    throw new Error(`Codex skill projection drift:\n${drift.map((f) => `  - ${f}`).join('\n')}`);
  }
  return { skillCount, fileCount: expected.size };
}

function main() {
  const check = process.argv.includes('--check');
  const result = syncCodexSkills({ check });
  console.log(
    `codex-skills-projection ${check ? '--check: OK' : 'wrote'} ` +
    `(${result.skillCount} skills, ${result.fileCount} files)`,
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main();
  } catch (error) {
    console.error(`codex-skills-projection: ${error.message}`);
    process.exit(1);
  }
}
