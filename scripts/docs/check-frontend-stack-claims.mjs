/**
 * Fail-closed docs lint: no canonical or public-facing doc may assert the RETIRED
 * React frontend stack as if it were current.
 *
 * The frontend was rewritten from React to Lit web components (ADR-0032,
 * graduated 2026-06-09). Canonical architecture / how-to / reference docs drifted
 * because nothing checked their claims against code (tempdoc 579). This gate closes
 * that hole: it flags present-tense React / Zustand / `.tsx` assertions in the
 * canonical trees, while exempting lines that frame those tokens historically
 * (the migration is legitimately *described* as past).
 *
 * Trees scanned (canonical corpus + public surface):
 * - docs/explanation/  docs/reference/  docs/how-to/
 * - docs/business/  +  root README.md  (the public blast radius — tempdoc 650 §A)
 * (docs/decisions/ is NOT scanned — the migration ADRs 0012/0032/0033 name React
 *  as the retired stack by design.)
 *
 * Conservative by construction:
 * - whole-word matching (so "reactive" / "reaction" never match),
 * - fenced code blocks are skipped,
 * - any line carrying a historical marker (retired / superseded / decommission /
 *   "not React" / ADR-0032 / etc.) is exempt,
 * - `.jsx` is NOT forbidden (the real entry point is legitimately `src/main.jsx`).
 */

import fs from "node:fs";
import path from "node:path";

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

const normalizeRel = (p) => p.replaceAll("\\", "/");

// Present-tense frontend-stack tokens that must not appear as a current claim.
// `react` is matched ONLY in framework contexts ("React UI", "the React frontend",
// "React/Vite", "React-rendered", …) so the agent "ReAct" reasoning pattern and the
// English verb "react (to …)" do not false-positive.
const FORBIDDEN = [
  {
    id: "react",
    re: /\bthe\s+react\b|\breact[-\s]*(ui|frontend|spa|app|component|components|stack|renders?|rendered|based|\/?vite|controlled|router?)\b/i,
    label: "React",
  },
  { id: "zustand", re: /\bzustand\b/i, label: "Zustand" },
  { id: "tsx", re: /\.tsx\b/i, label: ".tsx component" },
];

// A line is exempt when it frames the token historically (the migration may be
// described as past / as an absence), or names a migration ADR.
const HISTORICAL =
  /\b(retired|superseded|decommission(?:ed)?|deprecat(?:ed|ion)|formerly|former|legacy|no longer|not react|instead of react|replaced|removed|historical|stale|evaluated|was\s+(?:a\s+)?react|used to)\b|adr[-\s]?00(12|32|33)|\b(no|not\s+a|without|there\s+is\s+no)\s+zustand\b/i;

function main() {
  const repoRoot = repoRootFromCwd();
  const dirs = [
    path.join(repoRoot, "docs", "explanation"),
    path.join(repoRoot, "docs", "reference"),
    path.join(repoRoot, "docs", "how-to"),
    // Public-surface drafts (positioning, funding applications) are outside the
    // canonical corpus but still make present-tense stack claims to outsiders
    // (tempdoc 650 §A: the React falsehood survived precisely because the public
    // surface sat outside this gate's scope).
    path.join(repoRoot, "docs", "business"),
  ];
  // The #1 public descriptor — the front door must not lie while CI is green.
  const extraFiles = [path.join(repoRoot, "README.md")];
  const files = [...dirs.flatMap((d) => walk(d)), ...extraFiles.filter((f) => fs.existsSync(f))]
    .filter((p) => p.toLowerCase().endsWith(".md"))
    // docs/reference/issues/ are historical bug/decision logs that legitimately
    // record old (React-era) file paths in the context of a past defect; they are
    // not current-behaviour claims, so they are out of this gate's scope.
    .filter((p) => !normalizeRel(p).includes("/reference/issues/"));

  const errors = [];
  for (const f of files) {
    const relFile = normalizeRel(path.relative(repoRoot, f));
    const lines = fs.readFileSync(f, "utf8").split(/\r?\n/);
    let inFence = false;

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      if (/^\s*```/.test(line)) { inFence = !inFence; continue; }
      if (inFence) continue;
      if (HISTORICAL.test(line)) continue;

      for (const tok of FORBIDDEN) {
        if (tok.re.test(line)) {
          errors.push({ relFile, lineNumber: i + 1, label: tok.label, text: line.trim().slice(0, 120) });
        }
      }
    }
  }

  if (errors.length === 0) {
    console.log(`check-frontend-stack-claims: OK (files=${files.length})`);
    return;
  }

  console.log(`check-frontend-stack-claims: FAIL (files=${files.length} errors=${errors.length})`);
  console.log("  The frontend is Lit, not React (ADR-0032). Fix the claim, or frame it historically");
  console.log("  (retired / superseded / 'not React' / cite ADR-0032) if it legitimately describes the past.");
  for (const e of errors) {
    console.log(`- ${e.relFile}:${e.lineNumber} :: ${e.label} :: ${e.text}`);
  }
  process.exitCode = 1;
}

main();
