#!/usr/bin/env node
/**
 * search-degradation-reason-codes gate — tempdoc 602 R6.
 *
 * The per-query search-trace DEGRADATION reason vocabulary is ONE closed
 * authority — the search-side sibling of the readiness-reason-codes gate (NOT
 * merged: two vocabularies, per tempdoc 600 PART IX). The producer is the worker
 * `SearchReasonCode` enum, whose `.name()` wire strings populate the three
 * user-tier degradation fields (vectorBlockedReason / hybridFallbackReason /
 * spladeSkipReason). The FE `DEGRADATION_REASON_WORDING` map (searchTraceExplain.ts)
 * words them. Before this gate the FE interpolated the raw code to the user
 * ("semantic ranking blocked (LEGACY_INDEX_NO_FINGERPRINT)") — the Nielsen-#9
 * "no error codes" violation.
 *
 * Correspondence enforced against `governance/search-degradation-reason-codes.v1.json`:
 *  - FORWARD (no raw code to users): every `SearchReasonCode` member NOT in
 *    `noWordingExempt` has a `DEGRADATION_REASON_WORDING` key. (Exempt = the 11
 *    chunk-merge codes, which feed the chunk-merge STAGE reason — a diagnostic-tier
 *    facet rendered raw per the 577 cut — never the three user-tier fields.)
 *  - BACKWARD (no dead/typo rows): every wording key is a real enum member OR a
 *    declared `feDerived` code.
 *
 * Honest limit (as with the readiness sibling): the producer→field mapping is
 * captured by the curated `noWordingExempt` allow-list, not computed from Java — a
 * wrong future exemption is reviewable, far better than silent hand-sync.
 */
import { readFileSync } from 'node:fs';

const REGISTER = 'governance/search-degradation-reason-codes.v1.json';

/**
 * Extract the bare enum-constant identifiers from a `enum NAME { A, B, C; ... }`
 * source. Unlike LifecycleReasonCode (whose members carry a `("code")` string),
 * SearchReasonCode's wire form IS the constant identifier (`.name()`), so we read
 * the constant list itself — bounded to before the first method so identifiers in
 * method bodies (e.g. `return EMBEDDING_COMPATIBILITY_UNKNOWN;`) are not counted.
 */
export function extractEnumConstants(javaSrc, enumName) {
  const at = javaSrc.indexOf(`enum ${enumName}`);
  const fromEnum = at >= 0 ? javaSrc.slice(at) : javaSrc;
  const brace = fromEnum.indexOf('{');
  const body = brace >= 0 ? fromEnum.slice(brace + 1) : fromEnum;
  // The constant list runs until the first member declaration (method/field).
  const methodAt = body.search(/\n\s*(?:public|private|protected|static|final|@)\b/);
  const region = methodAt >= 0 ? body.slice(0, methodAt) : body;
  const codes = new Set();
  // A constant is a whole line that is just an UPPER_SNAKE identifier + `,` or `;`
  // (comment / javadoc lines do not start with an uppercase identifier).
  const re = /^\s*([A-Z][A-Z0-9_]*)\s*[,;]/gm;
  let m;
  while ((m = re.exec(region)) !== null) codes.add(m[1]);
  return codes;
}

/** Extract the keys of the `<table>` Record<string,string> object in a TS source. */
export function extractWordingKeys(tsSrc, table) {
  const decl = tsSrc.indexOf(table);
  if (decl < 0) return new Set();
  // Start at the object's opening brace so the declaration identifier + the
  // `Record<string, string>` annotation are excluded.
  const brace = tsSrc.indexOf('{', decl);
  const slice = brace >= 0 ? tsSrc.slice(brace) : tsSrc.slice(decl);
  const end = slice.indexOf('\n};');
  const region = end >= 0 ? slice.slice(0, end) : slice;
  const codes = new Set();
  // Each entry is on its own line `  KEY: '...'` (quoted or bare UPPER_SNAKE key).
  // Line-anchored (multiline) so a key after a comment line is matched too;
  // comment lines (`  // …`) and values never start with an UPPER_SNAKE + `:`.
  const re = /^[ \t]*(?:'([A-Z][A-Z0-9_]*)'|([A-Z][A-Z0-9_]*))\s*:/gm;
  let m;
  while ((m = re.exec(region)) !== null) codes.add(m[1] ?? m[2]);
  return codes;
}

/** Pure correspondence check. Returns an array of failure strings (empty = pass). */
export function checkCorrespondence({ enumCodes, wordingCodes, noWordingExempt, feDerived }) {
  const failures = [];
  const exempt = new Set(noWordingExempt);
  const fe = new Set(feDerived);

  // FORWARD — every non-exempt emittable code must be worded.
  for (const code of enumCodes) {
    if (exempt.has(code)) continue;
    if (!wordingCodes.has(code)) {
      failures.push(
        `forward: SearchReasonCode \`${code}\` can populate a user-tier degradation field but has no ` +
          `DEGRADATION_REASON_WORDING entry in searchTraceExplain.ts — a degraded search-explain line ` +
          `would render the raw \`(${code})\` to the user. Add a wording entry, or declare it in ` +
          `${REGISTER} \`noWordingExempt\` if it only feeds the chunk-merge stage (with a one-line rationale).`,
      );
    }
  }

  // BACKWARD — every worded code must be a real emittable code or a declared FE-derived one.
  for (const code of wordingCodes) {
    if (enumCodes.has(code) || fe.has(code)) continue;
    failures.push(
      `backward: DEGRADATION_REASON_WORDING has a key \`${code}\` that is neither a SearchReasonCode ` +
        `member nor a declared \`feDerived\` code in ${REGISTER} — a dead or mistyped row. Remove it, ` +
        `fix the code, or declare it FE-derived.`,
    );
  }
  return failures;
}

function main() {
  const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));
  const enumCodes = extractEnumConstants(readFileSync(reg.producer.file, 'utf8'), reg.producer.symbol);
  const wordingCodes = extractWordingKeys(readFileSync(reg.consumer.file, 'utf8'), reg.consumer.table);

  if (enumCodes.size === 0 || wordingCodes.size === 0) {
    console.error(
      `✗ search-degradation-reason-codes gate FAILED: could not extract codes ` +
        `(enum=${enumCodes.size}, wording=${wordingCodes.size}) — the producer/consumer seam moved; ` +
        `update ${REGISTER}.`,
    );
    process.exit(1);
  }

  const failures = checkCorrespondence({
    enumCodes,
    wordingCodes,
    noWordingExempt: (reg.noWordingExempt ?? []).map((e) => e.code),
    feDerived: (reg.feDerived ?? []).map((e) => e.code),
  });

  if (failures.length > 0) {
    console.error(
      '✗ search-degradation-reason-codes gate FAILED (tempdoc 602 R6):\n' +
        failures.map((x) => '  - ' + x).join('\n'),
    );
    process.exit(1);
  }
  console.log(
    `✓ search-degradation-reason-codes gate OK — SearchReasonCode↔DEGRADATION_REASON_WORDING correspond ` +
      `(${enumCodes.size} enum codes, ${wordingCodes.size} worded; ` +
      `${(reg.noWordingExempt ?? []).length} chunk-merge codes exempt); no raw reason code can reach the ` +
      `search-explain user line.`,
  );
}

// Run as CLI only (not when imported by a test). Basename check is robust cross-platform.
if (
  process.argv[1] &&
  process.argv[1].replace(/\\/g, '/').endsWith('check-search-degradation-reason-codes.mjs')
) {
  main();
}
