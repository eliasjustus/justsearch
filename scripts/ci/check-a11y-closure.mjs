#!/usr/bin/env node
/**
 * a11y-closure gate — tempdoc 559 Authority II (accessibility as a projection).
 *
 * The collapse landed: landmark roles are no longer hand-stamped per region —
 * they PROJECT from a surface region's declared `Placement` through the one
 * `placementToLandmarkRole` mapper (shell-v0/display/landmarks.ts), and the
 * shell applies the projected role to each grid region. So this gate enforces
 * the *projection*, not literal `role="…"` strings:
 *
 *  (1) **Mapper coverage (catalog-projected, §5.2 meta-point).** Every value of
 *      the closed `Placement` union (PLACEMENTS, api/types/surface.ts) is handled
 *      by `placementToLandmarkRole` — a `case '<placement>':` exists for each. A
 *      NEW placement kind is thus forced to declare its landmark role (or
 *      explicit null) the moment it joins the union. (tsc already bans a missing
 *      case via the no-`default` exhaustive switch; this gate makes the
 *      catalog→landmark coverage an explicit, reviewable invariant.)
 *  (2) **Shell applies the projection.** The shell sources its landmark regions
 *      from the mapper — `placementToLandmarkRole('RAIL'|'STAGE'|'STATUS'|
 *      'DRAWER')` each appear — and `STAGE` (→ the sole `main`) exactly once
 *      (axe landmark-one-main). The page `banner` is chrome, not a placement, so
 *      it stays a literal `role="banner"`.
 *  (3) **Single-heading closure** — exactly one `<h1` in the shell chrome (the
 *      canonical surface-title heading; axe page-has-heading-one).
 *  (4) **Selection-list closure** — the result set is a semantic list of
 *      listitems carrying aria-current (the 526 selection projection; NOT
 *      listbox/option, which would trip nested-interactive on the row controls).
 *
 * Lighter `scripts/ci/check-*.mjs` tier (the 557 precedent), wired as a ci.yml
 * step + the CLAUDE.md pre-merge list — NOT a second gate-registration authority.
 */
import { readFileSync, readdirSync } from 'node:fs';

const SHELL = 'modules/ui-web/src/shell-v0/chrome/Shell.ts';
const VIEWS_DIR = 'modules/ui-web/src/shell-v0/views';
const SEARCH = 'modules/ui-web/src/shell-v0/views/SearchSurface.ts';
const LANDMARKS = 'modules/ui-web/src/shell-v0/display/landmarks.ts';
const SURFACE_TYPES = 'modules/ui-web/src/api/types/surface.ts';

const failures = [];
const read = (f) => {
  try {
    return readFileSync(f, 'utf8');
  } catch {
    failures.push(`a11y-closure: cannot read ${f}`);
    return '';
  }
};

const shell = read(SHELL);
const search = read(SEARCH);
const landmarks = read(LANDMARKS);
const surfaceTypes = read(SURFACE_TYPES);

// (1) Mapper coverage — parse the closed PLACEMENTS list and assert each value
// is a case in the landmark mapper.
const placementsBlock = surfaceTypes.match(
  /PLACEMENTS:\s*readonly\s+Placement\[\]\s*=\s*\[([\s\S]*?)\]/,
);
const placements = placementsBlock
  ? [...placementsBlock[1].matchAll(/['"`]([A-Z_]+)['"`]/g)].map((m) => m[1])
  : [];
if (placements.length === 0) {
  failures.push(
    `a11y-closure: could not parse the PLACEMENTS union from ${SURFACE_TYPES} (559 Authority II coverage check).`,
  );
}
for (const p of placements) {
  const re = new RegExp(`case\\s+['"\`]${p}['"\`]\\s*:`);
  if (!re.test(landmarks)) {
    failures.push(
      `a11y-closure: placementToLandmarkRole (landmarks.ts) has no case for placement '${p}' — ` +
        `every Placement must declare its landmark role (or explicit null). (559 Authority II catalog coverage)`,
    );
  }
}

// (2) Shell applies the projection. Each landmark region sources its role from
// the mapper; STAGE (the sole `main`) appears exactly once.
const LANDMARK_PLACEMENTS = ['RAIL', 'STAGE', 'STATUS', 'DRAWER'];
for (const p of LANDMARK_PLACEMENTS) {
  const re = new RegExp(`placementToLandmarkRole\\(\\s*['"\`]${p}['"\`]\\s*\\)`, 'g');
  const n = (shell.match(re) || []).length;
  if (n < 1) {
    failures.push(
      `a11y-closure: Shell.ts does not source its ${p} landmark from placementToLandmarkRole('${p}') (559 Authority II projection).`,
    );
  }
  if (p === 'STAGE' && n > 1) {
    failures.push(
      `a11y-closure: Shell.ts projects placementToLandmarkRole('STAGE') ${n}× — the main landmark must be unique (axe landmark-one-main).`,
    );
  }
}
// The page banner is chrome (not a placement) and stays a literal role.
if (!/role=(['"`])banner\1/.test(shell)) {
  failures.push(
    `a11y-closure: Shell.ts is missing the page \`role="banner"\` (the topbar chrome landmark; 559 Authority II).`,
  );
}

// (3) Single-heading closure. Count closing tags (robust against the word
// "h1" appearing in comments/prose — only a real element has a </h1>).
const h1Count = (shell.match(/<\/h1>/g) || []).length;
if (h1Count !== 1) {
  failures.push(
    `a11y-closure: Shell.ts must contain exactly one <h1> (the canonical surface-title heading; axe page-has-heading-one) — found ${h1Count}.`,
  );
}

// (4) Selection-list closure. Results are a semantic list of listitems carrying
// aria-current (NOT listbox/option — rows hold interactive sub-controls, so a
// leaf option role would trip nested-interactive; verified live, 559 §live-batch).
if (!/role=(['"`])list\1/.test(search)) {
  failures.push(
    `a11y-closure: SearchSurface.ts results must be a role="list" (559 Authority II selection projection).`,
  );
}
if (!/role=(['"`])listitem\1/.test(search) || !/aria-current=/.test(search)) {
  failures.push(
    `a11y-closure: SearchSurface.ts result rows must be role="listitem" with aria-current (559 Authority II).`,
  );
}

// (5) Embeddable-surface heading/landmark closure (tempdoc 571 §11 / 578). The shell topbar owns the
// single page <h1>, and STAGE is the sole `main`. A surface VIEW must therefore never emit its own
// <h1> or a `main` landmark — otherwise a host that composes it as a member (via <jf-surface-tabs>)
// would produce a second <h1>/main on the page (axe page-has-heading-one / landmark-one-main). This
// makes every view embeddable-by-construction (members demote to <h2>+).
let viewFiles = [];
try {
  viewFiles = readdirSync(VIEWS_DIR)
    .filter((f) => f.endsWith('.ts') && !f.endsWith('.test.ts') && !f.endsWith('.d.ts'))
    .map((f) => `${VIEWS_DIR}/${f}`);
} catch {
  failures.push(`a11y-closure: cannot read the views directory ${VIEWS_DIR} (578 member-demotion check).`);
}
for (const f of viewFiles) {
  const srcv = read(f);
  if (/<\/h1>/.test(srcv)) {
    failures.push(
      `a11y-closure: ${f} emits its own <h1> — the shell topbar owns the page heading. A surface view ` +
        `must use <h2>+ so it is embeddable as a host member without a second <h1> (571 §11 / 578).`,
    );
  }
  if (/role=(['"`])main\1/.test(srcv) || /<main[\s>]/.test(srcv)) {
    failures.push(
      `a11y-closure: ${f} emits a \`main\` landmark — STAGE is the sole main (axe landmark-one-main); ` +
        `a surface view must not add a second one (571 §11 / 578).`,
    );
  }
}

if (failures.length > 0) {
  console.error('✗ a11y-closure gate FAILED:\n' + failures.map((f) => '  - ' + f).join('\n'));
  process.exit(1);
}
console.log(
  `✓ a11y-closure gate OK — ${placements.length} placements covered by placementToLandmarkRole; ` +
    `shell landmarks projected from placement; single <h1>; results list present; ` +
    `${viewFiles.length} view files emit no own <h1>/main (embeddable as host members).`,
);
