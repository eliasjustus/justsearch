#!/usr/bin/env node
/**
 * readiness-reason-codes gate — tempdoc 600 PART IX/X.
 *
 * The degradation-cause reason vocabulary is ONE closed authority: the producer
 * (`StatusLifecycleHandler`) emits readiness reason codes from the closed
 * `LifecycleReasonCode` enum, and the FE `CAUSE_ROWS` table (`readinessNotice.ts`)
 * words them. Before this gate the two were hand-synced and DRIFTING — an unworded
 * code in a degraded verdict rendered the raw `Degraded: <code>` string to the user
 * (reproduced live for `gpu.saturated`; the Nielsen-#9 "no error codes" violation).
 *
 * This gate makes that unrepresentable by enforcing correspondence against the
 * register `governance/readiness-reason-codes.v1.json`:
 *
 *  - FORWARD (no raw code to users): every `LifecycleReasonCode` member that is NOT
 *    in `noWordingExempt` must have a `CAUSE_ROWS` row. (Exempt = codes that only
 *    ever drive a non-degraded verdict, or live on a non-verdict composite — they
 *    never reach `wordCauses`, so a row would be dead UI; PART X.)
 *  - BACKWARD (no dead/typo rows): every `CAUSE_ROWS` code is a real enum member OR
 *    a declared `feDerived` code (e.g. `no_documents`, 596 §17).
 *
 * Honest limit (as with the sibling gates): the producer↔composite mapping is
 * captured by the curated `noWordingExempt` allow-list, not computed from Java —
 * a wrong future exemption is reviewable, far better than silent hand-sync.
 */
import { readFileSync } from 'node:fs';

const REGISTER = 'governance/readiness-reason-codes.v1.json';

/** Extract `NAME("code.string")` enum-member code strings from the LifecycleReasonCode source. */
export function extractEnumCodes(javaSrc) {
  const codes = new Set();
  // Match enum constants of the shape  IDENT("some.code")  — the only `IDENT("...")` form in this file.
  const re = /\b[A-Z][A-Z0-9_]*\s*\(\s*"([^"]+)"\s*\)/g;
  let m;
  while ((m = re.exec(javaSrc)) !== null) codes.add(m[1]);
  return codes;
}

/** Extract `code: '...'` values from the CAUSE_ROWS array of readinessNotice.ts. */
export function extractCauseRowCodes(tsSrc) {
  const start = tsSrc.indexOf('CAUSE_ROWS');
  const slice = start >= 0 ? tsSrc.slice(start) : tsSrc;
  const codes = new Set();
  const re = /\bcode:\s*'([^']+)'/g;
  let m;
  // Stop at the array terminator `];` that closes CAUSE_ROWS.
  const end = slice.indexOf('\n];');
  const region = end >= 0 ? slice.slice(0, end) : slice;
  while ((m = re.exec(region)) !== null) codes.add(m[1]);
  return codes;
}

/** Pure correspondence check. Returns an array of failure strings (empty = pass). */
export function checkCorrespondence({ enumCodes, causeRowCodes, noWordingExempt, feDerived }) {
  const failures = [];
  const exempt = new Set(noWordingExempt);
  const fe = new Set(feDerived);

  // FORWARD — every non-exempt emittable code must be worded.
  for (const code of enumCodes) {
    if (exempt.has(code)) continue;
    if (!causeRowCodes.has(code)) {
      failures.push(
        `forward: reason code \`${code}\` is emittable (LifecycleReasonCode member) but has no ` +
          `CAUSE_ROWS row in readinessNotice.ts — a degraded verdict carrying it would render the raw ` +
          `\`Degraded: ${code}\` string to the user. Add a CAUSE_ROWS row (plain wording + severity, ` +
          `optional remedy), or declare it in ${REGISTER} \`noWordingExempt\` if it never reaches a ` +
          `degraded verdict (with a one-line rationale).`,
      );
    }
  }

  // BACKWARD — every worded code must be a real emittable code or a declared FE-derived one.
  for (const code of causeRowCodes) {
    if (enumCodes.has(code) || fe.has(code)) continue;
    failures.push(
      `backward: CAUSE_ROWS has a row for \`${code}\`, which is neither a LifecycleReasonCode member ` +
        `nor a declared \`feDerived\` code in ${REGISTER} — a dead or mistyped row (the user would never ` +
        `see it, or it shadows a typo). Remove it, fix the code, or declare it FE-derived.`,
    );
  }
  return failures;
}

function main() {
  const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));
  const enumCodes = extractEnumCodes(readFileSync(reg.producer.file, 'utf8'));
  const causeRowCodes = extractCauseRowCodes(readFileSync(reg.consumer.file, 'utf8'));

  if (enumCodes.size === 0 || causeRowCodes.size === 0) {
    console.error(
      `✗ readiness-reason-codes gate FAILED: could not extract codes ` +
        `(enum=${enumCodes.size}, CAUSE_ROWS=${causeRowCodes.size}) — the producer/consumer seam moved; ` +
        `update ${REGISTER}.`,
    );
    process.exit(1);
  }

  const failures = checkCorrespondence({
    enumCodes,
    causeRowCodes,
    noWordingExempt: (reg.noWordingExempt ?? []).map((e) => e.code),
    feDerived: (reg.feDerived ?? []).map((e) => e.code),
  });

  if (failures.length > 0) {
    console.error(
      '✗ readiness-reason-codes gate FAILED (tempdoc 600 PART IX/X):\n' +
        failures.map((x) => '  - ' + x).join('\n'),
    );
    process.exit(1);
  }
  console.log(
    `✓ readiness-reason-codes gate OK — producer↔CAUSE_ROWS correspond ` +
      `(${enumCodes.size} emittable codes, ${causeRowCodes.size} worded rows); no raw code can reach the ` +
      `degradation banner.`,
  );
}

// Run as CLI only (not when imported by the test). Basename check is robust cross-platform.
if (process.argv[1] && process.argv[1].replace(/\\/g, '/').endsWith('check-readiness-reason-codes.mjs')) {
  main();
}
