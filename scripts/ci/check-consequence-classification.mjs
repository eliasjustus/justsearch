#!/usr/bin/env node
/**
 * consequence-classification gate — tempdoc 805 §G.2.
 *
 * A surface that CLAIMS what a degradation did to search ("showing keyword results") must derive that
 * claim from the ONE classifier `classifyConsequence` (state/readinessNotice.ts) and word it from that
 * module's exported caveat constants — never re-derive it from `verdict.severity` with a locally
 * re-authored literal. Round 11 measured that fork in TWO copies at once (the search banner and
 * `availability.ts`'s affordance caveat), both claiming a keyword fallback while the build's own search
 * trace showed dense retrieval AND the cross-encoder executing.
 *
 * Two halves, against `governance/consequence-classification.v1.json`:
 *   (a) CLAIM CONTAINMENT — the keyword-fallback claim phrases appear in no file under `scanRoot`
 *       except the authority module (allowlisted: searchTraceExplain's trace-OUTCOME line, and test
 *       files, which assert the claim's presence/absence). Comment-stripped, so a doc mention of the
 *       phrase does not trip the gate.
 *   (b) POSITIVE COVERAGE — every registered consumer imports the classifier or a caveat constant, so a
 *       registered consumer cannot silently drop back to a private severity-derived claim.
 *
 * Honest limit (as with capability-availability / verdict-derivation): coverage guards the REGISTERED
 * consumers' seam, not a brand-new unregistered claim surface (a new one is a discovery-step register
 * row); containment is literal-phrase matching, so a semantically-equivalent re-wording passes —
 * free-text wording judgment stays prose-tier.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, posix } from 'node:path';
import { stripComments as stripCommentsShared } from '../lib/strip-comments.mjs';

const REGISTER = 'governance/consequence-classification.v1.json';

/** Strip // and block comments so a doc mention of a claim phrase doesn't trip the scan. */
export function stripComments(src) {
  return stripCommentsShared(src, { withHtml: false });
}

/** Repo-relative, forward-slashed path (the register's own path style). */
const norm = (p) => p.split('\\').join('/');

/** Every file under `root` with a scanned extension, repo-relative + forward-slashed. */
export function listSourceFiles(root, { readDir = readdirSync, stat = statSync } = {}) {
  const out = [];
  const walk = (dir) => {
    for (const entry of readDir(dir)) {
      const full = join(dir, entry);
      if (stat(full).isDirectory()) walk(full);
      else if (/\.(ts|tsx|js|mjs)$/.test(entry)) out.push(norm(full));
    }
  };
  walk(root);
  return out.sort();
}

/** Is `file` exempt from claim containment (the authority, an allowlisted file, or a test)? */
export function isAllowed(file, { authorityFile, allowFiles, allowTestFiles }) {
  const f = norm(file);
  if (f === norm(authorityFile)) return true;
  if (allowFiles.some((a) => norm(a) === f)) return true;
  if (allowTestFiles && /\.(test|spec)\.[a-z]+$/.test(posix.basename(f))) return true;
  return false;
}

/**
 * Pure containment check. Returns failure strings (empty = pass).
 * `files` are repo-relative paths; `readFile` resolves them.
 */
export function checkContainment({ files, phrases, authorityFile, allowFiles, allowTestFiles, readFile }) {
  const failures = [];
  for (const file of files) {
    if (isAllowed(file, { authorityFile, allowFiles, allowTestFiles })) continue;
    const code = stripComments(readFile(file));
    for (const phrase of phrases) {
      if (code.includes(phrase)) {
        failures.push(
          `re-authored claim: \`${file}\` contains the degradation-consequence claim "${phrase}" — that ` +
            `claim may live ONLY in the classifier's module (${authorityFile}). Import the exported caveat ` +
            `constant instead of re-authoring the wording, and derive WHICH claim applies from ` +
            `\`classifyConsequence(reasonCodes)\` — never from \`verdict.severity\` (tempdoc 805 §G.2: a ` +
            `severity-derived copy claimed a keyword fallback over a trace showing dense retrieval live).`,
        );
      }
    }
  }
  return failures;
}

/** Pure coverage check: every registered consumer routes through the authority. Returns failures. */
export function checkConsumerCoverage({ consumers, symbol, caveatExports, readFile }) {
  const failures = [];
  for (const file of consumers) {
    let src;
    try {
      src = readFile(file);
    } catch {
      failures.push(`unresolved: registered consumer \`${file}\` does not exist — fix the path in ${REGISTER}.`);
      continue;
    }
    const code = stripComments(src);
    const consumes =
      new RegExp(`\\b${symbol}\\s*\\(`).test(code) || caveatExports.some((c) => new RegExp(`\\b${c}\\b`).test(code));
    if (!consumes) {
      failures.push(
        `fork: registered consumer \`${file}\` neither calls \`${symbol}(...)\` nor uses one of the ` +
          `exported caveat constants (${caveatExports.join(', ')}) — it must CONSUME the one consequence ` +
          `classification rather than re-deriving the consequence locally (that is the round-11 defect this ` +
          `gate closes). Consume the classifier, or (if it no longer claims a degradation consequence) drop ` +
          `it from ${REGISTER}.`,
      );
    }
  }
  return failures;
}

function main() {
  const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));
  const { file: authorityFile, symbol, caveatExports } = reg.authority;
  const authoritySrc = readFileSync(authorityFile, 'utf8');

  // Seam integrity — the authority still exports the classifier + every declared caveat constant.
  const missing = [];
  if (!new RegExp(`export function ${symbol}\\b`).test(authoritySrc)) missing.push(`function ${symbol}`);
  for (const c of caveatExports) {
    if (!new RegExp(`export const ${c}\\b`).test(authoritySrc)) missing.push(`const ${c}`);
  }
  if (missing.length > 0) {
    console.error(
      `✗ consequence-classification gate FAILED: ${authorityFile} no longer exports ${missing.join(', ')} — ` +
        `the seam moved; update ${REGISTER} (and every consumer with it).`,
    );
    process.exit(1);
  }

  const files = listSourceFiles(reg.scanRoot);
  const readFile = (f) => readFileSync(f, 'utf8');
  const failures = [
    ...checkContainment({
      files,
      phrases: reg.claimPhrases,
      authorityFile,
      allowFiles: reg.allow.files,
      allowTestFiles: reg.allow.testFiles === true,
      readFile,
    }),
    ...checkConsumerCoverage({ consumers: reg.consumers, symbol, caveatExports, readFile }),
  ];
  if (failures.length > 0) {
    console.error(
      '✗ consequence-classification gate FAILED (tempdoc 805 §G.2):\n' + failures.map((x) => '  - ' + x).join('\n'),
    );
    process.exit(1);
  }
  console.log(
    `✓ consequence-classification gate OK — ${files.length} scanned file(s) under ${reg.scanRoot}; the ` +
      `keyword-fallback claim lives only in ${authorityFile}; ${reg.consumers.length} registered consumer(s) ` +
      `derive their consequence from \`${symbol}\`.`,
  );
}

if (process.argv[1] && process.argv[1].replace(/\\/g, '/').endsWith('check-consequence-classification.mjs')) {
  main();
}
