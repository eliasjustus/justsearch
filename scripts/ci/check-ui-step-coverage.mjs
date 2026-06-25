#!/usr/bin/env node
/**
 * ui-step-coverage gate — tempdoc 615 §6.1a (the gate that keeps the screenshot harness honest).
 *
 * 615's thesis: verification infrastructure is product infrastructure — a verifier pointed at deleted
 * code is the same failure class as a dangling guard (reports green while asserting nothing). The
 * `ui-shot`/`ui-check` step index (`scripts/jseval/jseval/ui_step_index.json`) was an UNGOVERNED
 * register that silently kept mapping the retired React stack (the `modules/ui-web/src/components`
 * directory of `.tsx` files, which no longer exists) while the live UI is Lit `shell-v0`. This gate
 * applies the same
 * positive-coverage discipline the product registers already obey (cf. check-declared-surfaces / 569 §16,
 * check-a11y-closure / 559) to the harness's own register:
 *
 *   (a) FRESHNESS — every source path the step index maps resolves on disk (under `srcRoot`). A
 *       deleted/renamed file fails here; the stale-React-path class becomes a build failure, not a
 *       silent green.
 *   (b) FRESHNESS — every `viewCoverage[].source` / `exempt[].source` in the register resolves on disk.
 *   (c) COVERAGE — every `placement: 'RAIL'` surface in CORE_SURFACES is covered by a declared view
 *       step here OR carries a declared exemption (positive coverage projected from the authority's
 *       catalog). A new rail surface without a screenshot step is a discovery-step (add a row or exempt
 *       it), not a silent coverage hole.
 *
 * HONEST SCOPE (mirrors check-declared-surfaces): this is a POSITIVE-COVERAGE catalog, not a behavioural
 * scan. It asserts the declared paths exist and the rail set is covered; it does NOT verify a step
 * actually renders the live UI (that is what running `jseval ui-check` against the dev stack does).
 * Lighter scripts/ci tier; wired as a ci.yml step + the CLAUDE.md pre-merge list.
 */
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const REGISTER = 'governance/ui-step-coverage.v1.json';
const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));

const norm = (p) => p.replace(/\\/g, '/');
const failures = [];

// (a) FRESHNESS — every source path the step index maps resolves on disk.
const stepIndex = JSON.parse(readFileSync(reg.stepIndex, 'utf8'));
let indexPaths = 0;
for (const key of Object.keys(stepIndex)) {
  indexPaths += 1;
  const abs = join(reg.srcRoot, key);
  if (!existsSync(abs)) {
    failures.push(
      `${reg.stepIndex}: maps "${key}" but ${norm(abs)} does NOT exist — the step index points at a ` +
        `deleted/renamed file (the retired-React-stack class). Re-point it at the live shell-v0 owner ` +
        `or drop the row (615 §6.1a-a).`,
    );
  }
}

// (b) FRESHNESS — every register source path resolves on disk.
for (const row of [...reg.viewCoverage, ...reg.exempt]) {
  if (!existsSync(norm(row.source))) {
    failures.push(
      `register row for "${row.surfaceId}" declares source ${row.source} which does NOT exist — ` +
        `align the register with the live source (615 §6.1a-b).`,
    );
  }
}

// (c) COVERAGE — every RAIL surface in CORE_SURFACES is covered or exempt.
const catalog = readFileSync(reg.surfaceCatalog, 'utf8');
// Capture each `id: 'core.X'` whose own object declares `placement: 'RAIL'` (stop at the next id).
const railRe = /id:\s*'(core\.[^']+)'(?:(?!id:\s*')[\s\S])*?placement:\s*'RAIL'/g;
const railIds = [...catalog.matchAll(railRe)].map((m) => m[1]);
const covered = new Set([
  ...reg.viewCoverage.map((r) => r.surfaceId),
  ...reg.exempt.map((r) => r.surfaceId),
]);
for (const id of railIds) {
  if (!covered.has(id)) {
    failures.push(
      `RAIL surface "${id}" (declared in ${reg.surfaceCatalog}) has NO covering view step and NO ` +
        `exemption in ${REGISTER} — add a viewCoverage row (a screenshot step) or an exempt row with a ` +
        `reason (615 §6.1a-c, discovery-step).`,
    );
  }
}

if (railIds.length === 0) {
  failures.push(
    `could not parse any RAIL surface from ${reg.surfaceCatalog} — the CORE_SURFACES shape changed; ` +
      `update the parser in this gate so coverage is not silently vacuous.`,
  );
}

// (d) FIXTURE COVERAGE — every non-fail-open parseWireContract endpoint in a captured view surface
//     has a deterministic fixtures decision (mapped in ui_fixtures._ROUTES OR fixtureExempt). 615 §37.1.
//     A missing fixture returns `{}` under --fixtures, trips the parse, and reads as an `app` console
//     error (a fixtures gap masquerading as a real bug — §33). This asserts the fixture<->contract
//     correspondence instead of leaving it to hand-maintained drift.
let fixtureChecked = 0;
if (reg.fixtureRoutesSource) {
  // Mapped routes: the `("/api/…", BODY)` tuple entries in _ROUTES (the ONE authority for what is mapped).
  const fixSrc = readFileSync(norm(reg.fixtureRoutesSource), 'utf8');
  const mappedNeedles = [...fixSrc.matchAll(/\(\s*["'](\/api\/[^"']+)["']\s*,/g)].map((m) => m[1]);
  const exemptSet = new Set((reg.fixtureExempt ?? []).map((r) => r.endpoint));
  // A strict endpoint E is covered if some _ROUTES needle is a substring of E (mirrors
  // ui_fixtures.fixture_body's `if needle in url`) or E is exempt.
  const isCovered = (ep) => exemptSet.has(ep) || mappedNeedles.some((n) => ep.includes(n));

  for (const row of reg.viewCoverage) {
    const src = readFileSync(norm(row.source), 'utf8');
    if (!src.includes('parseWireContract')) continue;
    // Resolve local `const NAME = '/api/…'` so the ENDPOINT-const indirection (LibrarySurface) is seen.
    const localConsts = new Map(
      [...src.matchAll(/\bconst\s+(\w+)\s*=\s*['"](\/api\/[^'"]+)['"]/g)].map((m) => [m[1], m[2]]),
    );
    const endpoints = new Set();
    for (const call of src.matchAll(/parseWireContract\s*\(/g)) {
      const region = src.slice(call.index, call.index + 400);
      // inline context-string endpoint, e.g. 'GET /api/health/events/stream (update)' → /api/health/events/stream
      for (const lit of region.matchAll(/['"](?:[A-Z]+\s+)?(\/api\/[^'"\s)?]+)/g)) endpoints.add(lit[1]);
      // const-identifier endpoint, e.g. parseWireContract(schema, body, ENDPOINT)
      for (const [name, ep] of localConsts) {
        if (new RegExp(`\\b${name}\\b`).test(region)) endpoints.add(ep);
      }
    }
    for (const ep of endpoints) {
      fixtureChecked += 1;
      if (!isCovered(ep)) {
        failures.push(
          `view surface "${row.surfaceId}" (${row.source}) strict-parses "${ep}" (parseWireContract, ` +
            `non-fail-open) but it is NOT mapped in ${reg.fixtureRoutesSource} _ROUTES and NOT in ` +
            `fixtureExempt — under --fixtures it returns {} and reads as an 'app' console error (a ` +
            `fixtures gap masquerading as a real bug, 615 §33). Add a _ROUTES fixture or a fixtureExempt ` +
            `row with a reason (615 §37.1 fixture-coverage).`,
        );
      }
    }
  }
}

if (failures.length > 0) {
  console.error('✗ ui-step-coverage gate FAILED:\n' + failures.map((x) => '  - ' + x).join('\n'));
  process.exit(1);
}
console.log(
  `✓ ui-step-coverage gate OK — ${indexPaths} step-index source path(s) all resolve on disk, all ` +
    `${railIds.length} RAIL core surface(s) are covered (${reg.viewCoverage.length} view step(s) + ` +
    `${reg.exempt.length} exemption(s)), and ${fixtureChecked} view-surface strict-parse endpoint(s) all ` +
    `have a fixtures decision (mapped or exempt). Freshness + positive-coverage + fixture-coverage; a ` +
    `deleted harness source path, an uncovered rail surface, or an unmapped strict endpoint fails here ` +
    `(615 §6.1a / §37.1).`,
);
