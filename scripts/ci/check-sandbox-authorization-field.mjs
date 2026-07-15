#!/usr/bin/env node
/**
 * check-sandbox-authorization-field.mjs
 *
 * Fails the build if the retired `POST /api/authorizations/approve` field
 * name `authorizationId` reappears in the Sandbox's `cohort:mcp` TYPED_CONFIRM
 * procedure docs. The server's field is `pendingId`
 * (`AuthorizationController.handleApprove`, modules/ui/src/main/java/io/justsearch/ui/api/
 * AuthorizationController.java — reads `body.get("pendingId")`); `authorizationId` 400s.
 *
 * This is not a hypothetical drift risk: `scripts/sandbox/mcp-client-README.md`
 * and `governance/sandbox-coverage.v1.json`'s `cohort:mcp` procedure both
 * shipped `authorizationId` once already, a real round followed it verbatim,
 * every approve attempt 400'd, and the round filed the result as a HIGH
 * product regression ("approve never executes the operation") against what
 * was actually a documentation defect. A doc-only fix repeats that failure
 * mode silently the next time someone edits these files — this is the
 * fail-closed backstop, scoped narrowly to the two files that got it wrong.
 *
 * Run: `node scripts/ci/check-sandbox-authorization-field.mjs`
 *      (exits non-zero on failure). Test override: CHECK_SANDBOX_AUTH_FIELD_ROOT=<dir>.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

function repoRootFromCwd() {
  const markers = ['settings.gradle.kts', 'build.gradle.kts', '.git'];
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (markers.some((marker) => fs.existsSync(path.join(dir, marker)))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) break;
  }
  return process.cwd();
}

const BANNED_FIELD = 'authorizationId';

const SCANNED_FILES = [
  path.join('scripts', 'sandbox', 'mcp-client-README.md'),
  path.join('governance', 'sandbox-coverage.v1.json'),
];

/**
 * Scans SCANNED_FILES for the literal retired field name. Returns per-line
 * violations (file + line number) so a failure is immediately actionable,
 * not just a file-level flag.
 */
export function checkSandboxAuthorizationField(repoRoot) {
  const violations = [];
  for (const rel of SCANNED_FILES) {
    const full = path.join(repoRoot, rel);
    if (!fs.existsSync(full)) {
      violations.push(`${rel}: file not found at ${full} (scanned file went missing — update SCANNED_FILES?)`);
      continue;
    }
    const text = fs.readFileSync(full, 'utf8');
    const lines = text.split(/\r?\n/);
    lines.forEach((line, idx) => {
      if (line.includes(BANNED_FIELD)) {
        violations.push(
          `${rel}:${idx + 1}: contains the retired field name "${BANNED_FIELD}" — ` +
            'POST /api/authorizations/approve takes "pendingId", not "authorizationId" ' +
            '(AuthorizationController.handleApprove reads body.pendingId). The wrong name 400s.',
        );
      }
    });
  }
  return { ok: violations.length === 0, violations };
}

function main() {
  const repoRoot = process.env.CHECK_SANDBOX_AUTH_FIELD_ROOT
    ? path.resolve(process.env.CHECK_SANDBOX_AUTH_FIELD_ROOT)
    : repoRootFromCwd();
  const { ok, violations } = checkSandboxAuthorizationField(repoRoot);

  if (ok) {
    console.log(
      'check-sandbox-authorization-field: OK — no occurrences of the retired "authorizationId" field name.',
    );
    return;
  }

  console.error('check-sandbox-authorization-field: FAIL');
  for (const v of violations) console.error(`- ${v}`);
  process.exitCode = 1;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
