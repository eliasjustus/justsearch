#!/usr/bin/env node
/**
 * Every registered `/api/ai/install/*` route is documented in the API contract map.
 *
 * Round 16 (tempdoc 823/824 F4) hit a `POST /api/ai/install/repair` returning 400 and had nowhere
 * to look: the whole family was absent from `docs/reference/api-contract-map.md` — six routes that
 * have shipped since alpha, none of them documented. The gap was invisible because nothing
 * compares the routes the server binds against the routes the docs describe.
 *
 * Authority for "registered" is the route table itself (`AiRoutes.java`), not a generated manifest:
 * the generated `route-manifest.snapshot.json` does not currently list `plan-preview`, so trusting
 * it would reproduce exactly the class of blind spot this check exists to close.
 *
 * Usage: node scripts/ci/check-install-api-contract.mjs
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

export const ROUTES_FILE = 'modules/ui/src/main/java/io/justsearch/ui/api/routes/AiRoutes.java';
export const DOC_FILE = 'docs/reference/api-contract-map.md';
const PREFIX = '/api/ai/install/';

function repoRootFromCwd() {
  const markers = ['settings.gradle.kts', 'build.gradle.kts', '.git'];
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (markers.some((marker) => fs.existsSync(path.join(dir, marker)))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) break;
  }
  return process.cwd();
}

/** Routes bound under the install prefix, as `METHOD /path` strings. */
export function registeredInstallRoutes(routesSource) {
  const found = new Set();
  const re = /app\.(get|post|put|delete|patch)\(\s*"(\/api\/ai\/install\/[^"]+)"/g;
  for (const m of routesSource.matchAll(re)) {
    found.add(`${m[1].toUpperCase()} ${m[2]}`);
  }
  return [...found].sort();
}

/** The subset of `routes` the doc does not mention (path match; the method must be named too). */
export function undocumentedRoutes(routes, docText) {
  return routes.filter((route) => {
    const [method, routePath] = route.split(' ');
    if (!docText.includes(routePath)) return true;
    // The path can appear in prose; the row that documents it must also name its method.
    return !docText
      .split(/\r?\n/)
      .some((line) => line.includes(routePath) && line.includes(method));
  });
}

export function run(repoRoot) {
  const routesPath = path.join(repoRoot, ROUTES_FILE);
  const docPath = path.join(repoRoot, DOC_FILE);
  const errors = [];
  if (!fs.existsSync(routesPath)) {
    return [`${ROUTES_FILE} is missing — this check can no longer see which routes are bound.`];
  }
  if (!fs.existsSync(docPath)) {
    return [`${DOC_FILE} is missing.`];
  }
  const routes = registeredInstallRoutes(fs.readFileSync(routesPath, 'utf8'));
  if (routes.length === 0) {
    errors.push(
      `No ${PREFIX}* routes found in ${ROUTES_FILE} — either they moved (point this check at the`
        + ' new table) or the matcher broke; an empty route set must never pass silently.',
    );
    return errors;
  }
  const docText = fs.readFileSync(docPath, 'utf8');
  for (const route of undocumentedRoutes(routes, docText)) {
    errors.push(
      `${route} is registered but not documented in ${DOC_FILE} (add a row naming its method,`
        + ' request, success shape and error codes).',
    );
  }
  return errors;
}

function main() {
  const errors = run(repoRootFromCwd());
  if (errors.length === 0) {
    console.log('check-install-api-contract: OK');
    return;
  }
  console.error('check-install-api-contract: FAIL');
  for (const e of errors) console.error(`  - ${e}`);
  process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1].replace(/\\/g, '/')}`).href) {
  main();
}
