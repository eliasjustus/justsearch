#!/usr/bin/env node
/**
 * check-logic-seams — register-integrity validator for governance/logic-seams.v1.json (tempdoc 555).
 *
 * The logic-seams register drives PIT scoping (conventions.mutation) and the test-efficacy ratchet.
 * An unvalidated register fails OPEN: a typo'd targetClass FQCN selects 0 mutations
 * (failWhenNoMutations=false) and the seam reads as vacuously absent/100%. This check closes that by
 * asserting, for every seam:
 *   - targetClass resolves to a real modules/<m>/src/main/java/<pkg>/<Class>.java
 *   - every targetTest resolves to a real src/test/java (or sibling sourceSet) file
 *   - the seam's gradlePath module applies `id("conventions.mutation")` (else PIT never runs for it)
 *   - the seam id is unique and has a floor in gates/test-efficacy/strength-baseline.v1.json
 *
 * Cheap (file-existence only — no PIT), so it runs in the normal CI gate job and locally.
 * Usage: node scripts/ci/check-logic-seams.mjs [--mode warn|gate]
 */

import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');

const mode = process.argv.includes('--mode')
  ? process.argv[process.argv.indexOf('--mode') + 1]
  : 'warn';

const moduleName = gradlePath => gradlePath.split(':').filter(Boolean).slice(-1)[0];
const fqcnToPath = fqcn => fqcn.replace(/\./g, '/') + '.java';

function classFile(mod, fqcn) {
  return resolve(REPO_ROOT, `modules/${mod}/src/main/java/${fqcnToPath(fqcn)}`);
}
// Tests may live in test / integrationTest / systemTest sourceSets.
function testFileExists(mod, fqcn) {
  const rel = fqcnToPath(fqcn);
  return ['test', 'integrationTest', 'systemTest'].some(ss =>
    existsSync(resolve(REPO_ROOT, `modules/${mod}/src/${ss}/java/${rel}`)),
  );
}

const findings = [];
const add = (seam, detail) => findings.push({ seam, detail });

const register = JSON.parse(
  readFileSync(resolve(REPO_ROOT, 'governance/logic-seams.v1.json'), 'utf8'),
);
const baseline = JSON.parse(
  readFileSync(resolve(REPO_ROOT, 'gates/test-efficacy/strength-baseline.v1.json'), 'utf8'),
);
const baselineSeams = baseline.seams ?? {};

const seen = new Set();
for (const seam of register.seams ?? []) {
  const id = seam.id ?? '(missing id)';
  if (seen.has(id)) add(id, `duplicate seam id '${id}'`);
  seen.add(id);

  const mod = moduleName(seam.gradlePath ?? '');
  if (!mod) {
    add(id, `missing/invalid gradlePath`);
    continue;
  }

  if (!seam.targetClass || !existsSync(classFile(mod, seam.targetClass))) {
    add(id, `targetClass '${seam.targetClass}' does not resolve to a file in module '${mod}'`);
  }

  for (const t of seam.targetTests ?? []) {
    if (!testFileExists(mod, t)) add(id, `targetTest '${t}' not found in module '${mod}'`);
  }
  if ((seam.targetTests ?? []).length === 0) add(id, `no targetTests declared (PIT has no green suite to measure)`);

  const buildFile = resolve(REPO_ROOT, `modules/${mod}/build.gradle.kts`);
  if (!existsSync(buildFile)) {
    add(id, `module build file not found: modules/${mod}/build.gradle.kts`);
  } else if (!readFileSync(buildFile, 'utf8').includes('conventions.mutation')) {
    add(id, `module '${mod}' does not apply id("conventions.mutation") — PIT will never run for this seam`);
  }

  if (!(id in baselineSeams)) {
    add(id, `no baseline floor in gates/test-efficacy/strength-baseline.v1.json (run --rebalance)`);
  }
}

if (findings.length === 0) {
  console.log(`[check-logic-seams] OK — ${(register.seams ?? []).length} seam(s) valid.`);
  process.exit(0);
}

console.error(`[check-logic-seams] ${findings.length} register-integrity violation(s):`);
for (const f of findings) console.error(`  [${f.seam}] ${f.detail}`);
console.error('\nThe logic-seams register (tempdoc 555 Pillar A) must stay consistent with the code it');
console.error('governs, or the test-efficacy gate fails open. Fix the register / module wiring above.');
process.exit(mode === 'gate' ? 1 : 0);
