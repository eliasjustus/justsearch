#!/usr/bin/env node
/**
 * message-classes gate — tempdoc 613 §5.2/§8.
 *
 * The FE-LOCAL message-class vocabulary (the `emitEphemeralToast` classId, 559 Authority III) is ONE
 * closed authority: `LOCAL_MESSAGE_CLASSES` (messageClasses.ts) declares each local transient's class
 * + policy, and emit sites NAME a class rather than passing a free-form string. The union type on
 * `EphemeralToastSpec.classId` is the compile-time half (Collapse); this gate is the 602 §D.0
 * backstop, enforcing declaration↔emit correspondence against `governance/message-classes.v1.json`:
 *
 *  - FORWARD (no free-form/typo'd local class): every classId emitted via `emitEphemeralToast({classId:
 *    '...'})` is a declared `LOCAL_MESSAGE_CLASSES` member.
 *  - BACKWARD (no dead declarations): every declared class — except the omitted `defaultClass` and any
 *    `emitExempt` — is actually emitted somewhere.
 *
 * Scope: FE-LOCAL emit only. The wire `AdvisoryEvent.classId` stays a `string` (it also carries backend
 * advisory classes — `operation.completed`, … — whose chrome lives in `AdvisoryClassChrome.ts`).
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const REGISTER = 'governance/message-classes.v1.json';

/** Keys of the `LOCAL_MESSAGE_CLASSES` object literal (each policy row begins `'<id>': { renderHint`). */
export function extractDeclaredClasses(tsSrc) {
  const start = tsSrc.indexOf('LOCAL_MESSAGE_CLASSES');
  const slice = start >= 0 ? tsSrc.slice(start) : tsSrc;
  const end = slice.indexOf('} as const');
  const region = end >= 0 ? slice.slice(0, end) : slice;
  const ids = new Set();
  const re = /'([^']+)':\s*\{\s*renderHint/g;
  let m;
  while ((m = re.exec(region)) !== null) ids.add(m[1]);
  return ids;
}

/** classIds emitted as object-literal `classId: '...'` (the emit form; NOT `event.classId === '...'`). */
export function extractEmittedClassIds(tsSrc) {
  const ids = new Set();
  const re = /\bclassId:\s*'([^']+)'/g;
  let m;
  while ((m = re.exec(tsSrc)) !== null) ids.add(m[1]);
  return ids;
}

/** Pure correspondence check. Returns an array of failure strings (empty = pass). */
export function checkCorrespondence({ declared, emitted, defaultClass, emitExempt }) {
  const failures = [];
  const exempt = new Set([defaultClass, ...emitExempt]);
  for (const id of emitted) {
    if (!declared.has(id)) {
      failures.push(
        `forward: classId \`${id}\` is emitted via emitEphemeralToast but is NOT a declared ` +
          `LOCAL_MESSAGE_CLASSES member — a free-form/typo'd local message class. Add a row in ` +
          `messageClasses.ts (its supersede + default tone), or fix the classId.`,
      );
    }
  }
  for (const id of declared) {
    if (exempt.has(id)) continue;
    if (!emitted.has(id)) {
      failures.push(
        `backward: LOCAL_MESSAGE_CLASSES declares \`${id}\` but no emitEphemeralToast call emits it — ` +
          `a dead declaration. Remove it, or (if it is the omitted default) declare it as ` +
          `\`defaultClass\`/\`emitExempt\` in ${REGISTER}.`,
      );
    }
  }
  return failures;
}

/** Recursively collect *.ts files under `dir`, skipping *.test.ts and `skipFile`. */
function collectTsFiles(dir, skipFile, out = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    const st = statSync(full);
    if (st.isDirectory()) {
      collectTsFiles(full, skipFile, out);
    } else if (entry.endsWith('.ts') && !entry.endsWith('.test.ts')) {
      const norm = full.replace(/\\/g, '/');
      if (!norm.endsWith(skipFile)) out.push(full);
    }
  }
  return out;
}

function main() {
  const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));
  const declared = extractDeclaredClasses(readFileSync(reg.declaration.file, 'utf8'));

  // Derive the scan root from the glob prefix (before `/**`), repo-root-relative.
  const scanRoot = (reg.emitScanGlobs[0] ?? '').split('/**')[0];
  const declSuffix = reg.declaration.file.replace(/\\/g, '/');
  const emitted = new Set();
  for (const file of collectTsFiles(scanRoot, declSuffix)) {
    for (const id of extractEmittedClassIds(readFileSync(file, 'utf8'))) emitted.add(id);
  }

  if (declared.size === 0) {
    console.error(
      `✗ message-classes gate FAILED: could not extract LOCAL_MESSAGE_CLASSES keys from ` +
        `${reg.declaration.file} — the declaration moved; update ${REGISTER}.`,
    );
    process.exit(1);
  }

  const failures = checkCorrespondence({
    declared,
    emitted,
    defaultClass: reg.defaultClass,
    emitExempt: reg.emitExempt ?? [],
  });

  if (failures.length > 0) {
    console.error(
      '✗ message-classes gate FAILED (tempdoc 613 §5.2/§8):\n' +
        failures.map((x) => '  - ' + x).join('\n'),
    );
    process.exit(1);
  }
  console.log(
    `✓ message-classes gate OK — LOCAL_MESSAGE_CLASSES↔emit correspond ` +
      `(${declared.size} declared classes, ${emitted.size} emitted classIds); ` +
      `no free-form local message class can reach the toast channel.`,
  );
}

if (process.argv[1] && process.argv[1].replace(/\\/g, '/').endsWith('check-message-classes.mjs')) {
  main();
}
