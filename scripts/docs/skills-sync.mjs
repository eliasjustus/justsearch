/**
 * Sync canonical documentation into skill files.
 *
 * Usage:
 *   node scripts/docs/skills-sync.mjs          # regenerate generated sections in all skill files
 *   node scripts/docs/skills-sync.mjs --check   # verify committed files match generated (CI)
 *
 * Only the section between <!-- generated:start --> and <!-- generated:end --> markers is touched.
 * The hand-authored frontmatter and header remain editable directly in the SKILL.md files.
 *
 * Each skill declares which canonical docs to include. Source files are concatenated in order,
 * separated by horizontal rules. Frontmatter is stripped from source files before inclusion.
 */

import fs from "node:fs";
import path from "node:path";
import matter from "gray-matter";

const MARKER_START =
  "<!-- generated:start — do not edit between markers; run: node scripts/docs/skills-sync.mjs -->";
const MARKER_END = "<!-- generated:end -->";

/* ------------------------------------------------------------------ */
/* Skill manifest — which canonical docs feed into which skill         */
/* ------------------------------------------------------------------ */

const SKILLS = [
  {
    skillPath: ".claude/skills/search-quality/SKILL.md",
    sources: [
      "docs/reference/search-quality-register.md",
      "docs/explanation/23-search-pipeline-overview.md",
      "docs/reference/contracts/search-pipeline-invariants.md",
    ],
  },
  {
    skillPath: ".claude/skills/inference-runtime/SKILL.md",
    sources: [
      "docs/reference/inference-runtime-register.md",
      "docs/explanation/05-ai-architecture.md",
      "docs/explanation/17-ai-bridge-deep-dive.md",
    ],
  },
  {
    skillPath: ".claude/skills/jseval/SKILL.md",
    sources: [
      "docs/reference/jseval-pipeline-reference.md",
    ],
  },
  {
    skillPath: ".claude/skills/dev-stack/SKILL.md",
    sources: [
      "docs/reference/contributing/mcp-dev-tools.md",
    ],
  },
  {
    skillPath: ".claude/skills/installer/SKILL.md",
    sources: [
      "docs/explanation/12-desktop-installer-and-sandbox-setup.md",
    ],
  },
  {
    skillPath: ".claude/skills/module-arch/SKILL.md",
    sources: [
      "docs/explanation/19-module-architecture.md",
    ],
  },
];

/* ------------------------------------------------------------------ */
/* Helpers                                                             */
/* ------------------------------------------------------------------ */

function repoRootFromCwd() {
  const cwd = process.cwd();
  const markers = ["settings.gradle.kts", "build.gradle.kts", ".git"];
  for (let dir = cwd; ; dir = path.dirname(dir)) {
    for (const m of markers) {
      if (fs.existsSync(path.join(dir, m))) return dir;
    }
    const parent = path.dirname(dir);
    if (parent === dir) break;
  }
  return cwd;
}

/** Read a markdown file and strip its YAML frontmatter. */
function readStrippingFrontmatter(filePath) {
  const raw = fs.readFileSync(filePath, "utf8");
  const { content } = matter(raw);
  return content.trim();
}

/* ------------------------------------------------------------------ */
/* Main                                                                */
/* ------------------------------------------------------------------ */

function main() {
  const root = repoRootFromCwd();
  const isCheck = process.argv.includes("--check");
  let allOk = true;
  let totalSources = 0;

  for (const skill of SKILLS) {
    const skillPath = path.join(root, skill.skillPath);

    if (!fs.existsSync(skillPath)) {
      console.error(`ERROR: ${skill.skillPath} does not exist`);
      process.exitCode = 1;
      return;
    }

    const existing = fs.readFileSync(skillPath, "utf8");
    const startIdx = existing.indexOf(MARKER_START);
    const endIdx = existing.indexOf(MARKER_END);

    if (startIdx === -1 || endIdx === -1) {
      console.error(
        `ERROR: ${skill.skillPath} is missing generation markers.`
      );
      process.exitCode = 1;
      return;
    }

    const before = existing.slice(0, startIdx);
    const after = existing.slice(endIdx + MARKER_END.length);

    // Concatenate source files
    const sections = [];
    for (const src of skill.sources) {
      const srcPath = path.join(root, src);
      if (!fs.existsSync(srcPath)) {
        console.error(`ERROR: source file ${src} does not exist`);
        process.exitCode = 1;
        return;
      }
      const content = readStrippingFrontmatter(srcPath);
      sections.push(`<!-- source: ${src} -->\n\n${content}`);
      totalSources++;
    }

    const generated = [
      MARKER_START,
      "",
      ...sections.join("\n\n---\n\n").split("\n"),
      "",
      MARKER_END,
    ].join("\n");

    const output = before + generated + after;

    if (isCheck) {
      if (existing !== output) {
        console.error(
          `skills-sync --check: FAIL — ${skill.skillPath} is out of date`
        );
        allOk = false;
      }
    } else {
      fs.writeFileSync(skillPath, output, "utf8");
    }
  }

  if (isCheck) {
    if (allOk) {
      console.log(
        `skills-sync --check: OK (${SKILLS.length} skills, ${totalSources} sources)`
      );
    } else {
      console.error(
        "Run: node scripts/docs/skills-sync.mjs   to regenerate"
      );
      process.exitCode = 1;
    }
  } else {
    console.log(
      `skills-sync: wrote ${SKILLS.length} skills (${totalSources} sources synced)`
    );
  }
}

main();
