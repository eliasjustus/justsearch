#!/usr/bin/env node
/**
 * Fail-closed docs lint: RESEARCH.md's published agent-utility claim must match the
 * committed utility-comparison run records — never tempdoc prose, never a hand-edit.
 *
 * Purpose-built projector in the established shape (sibling of
 * scripts/docs/check-model-freshness.mjs / check-frontend-stack-claims.mjs), per the
 * `canonical-authority-and-projection` principle: a public claim is a projection of a
 * declared fact, never a hand-copied fork. Pre-registered as tempdoc 624 "Design
 * theorization #2" Design 2; its trigger (RESEARCH.md on `main` citing the finding)
 * has fired.
 *
 * Source of truth (committed JSON records, 2026-07-03 certified run):
 *   scripts/jseval/624-run-2026-07-03/out-cross-corpus/utility-comparison-cross-corpus.v1.json
 *   scripts/jseval/624-run-2026-07-03/out-en-judged/utility-comparison.v1.json
 *   scripts/jseval/624-run-2026-07-03/out-de-judged/utility-comparison.v1.json
 *
 * Checks, inside RESEARCH.md's <!-- agent-utility-claim:begin/end --> marker region:
 *   1. pooled n / accuracy delta / McNemar p match the cross-corpus record's primary arm;
 *   2. both per-corpus (delta, p) pairs match the two per-corpus records (order-free);
 *   3. the token-cost mean delta and CI95 match the cross-corpus record;
 *   4. overclaim guard: a cited record with comparability.comparable !== true requires
 *      an explicit comparability caveat in the region text;
 *   5. the region parses at all — a rewrite that breaks the claim grammar fails loudly
 *      instead of passing silently.
 *
 * Numeric matching honors the displayed precision: a claim printed with k decimals
 * matches when |claim - record| <= 0.5 * 10^-k (standard rounding), so "-0.027"
 * matches -0.0269 and "0.860" matches 0.859684 — but any real drift fails, naming
 * the drifted value.
 */

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

function repoRootFromCwd() {
  const markers = ["settings.gradle.kts", "build.gradle.kts", ".git"];
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (markers.some((m) => fs.existsSync(path.join(dir, m)))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) break;
  }
  return process.cwd();
}

export const RECORD_PATHS = {
  pooled: "scripts/jseval/624-run-2026-07-03/out-cross-corpus/utility-comparison-cross-corpus.v1.json",
  en: "scripts/jseval/624-run-2026-07-03/out-en-judged/utility-comparison.v1.json",
  de: "scripts/jseval/624-run-2026-07-03/out-de-judged/utility-comparison.v1.json",
};

const BEGIN_MARKER = "<!-- agent-utility-claim:begin -->";
const END_MARKER = "<!-- agent-utility-claim:end -->";

/** Extract the marker-delimited claim region; null when absent. */
export function extractClaimRegion(markdownText) {
  const begin = markdownText.indexOf(BEGIN_MARKER);
  const end = markdownText.indexOf(END_MARKER);
  if (begin === -1 || end === -1 || end < begin) return null;
  return markdownText.slice(begin + BEGIN_MARKER.length, end);
}

/** Normalize the region: strip blockquote prefixes, unify Unicode minus, collapse whitespace. */
function normalizeRegion(regionText) {
  return regionText
    .replace(/^\s*>\s?/gm, "")
    .replace(/−/g, "-") // U+2212 MINUS SIGN -> hyphen-minus
    .replace(/\*\*/g, "")
    .replace(/\s+/g, " ")
    .trim();
}

/** Parse the claim grammar into numeric strings (precision-preserving). Returns { errors, values }. */
export function parseClaimNumbers(regionText) {
  const text = normalizeRegion(regionText);
  const errors = [];
  const values = {};

  const pooled = text.match(/pooled n=(\d+) paired, Δ ([+-][\d.]+), McNemar p=([\d.]+)/);
  if (pooled) {
    values.pooledN = pooled[1];
    values.pooledDelta = pooled[2];
    values.pooledP = pooled[3];
  } else {
    errors.push("claim region no longer matches the pooled-accuracy grammar ('pooled n=<n> paired, Δ <d>, McNemar p=<p>')");
  }

  const perCorpus = text.match(/per-corpus Δ ([+-][\d.]+) \/ p=([\d.]+) and ([+-][\d.]+) \/ p=([\d.]+)/);
  if (perCorpus) {
    values.perCorpusPairs = [
      { delta: perCorpus[1], p: perCorpus[2] },
      { delta: perCorpus[3], p: perCorpus[4] },
    ];
  } else {
    errors.push("claim region no longer matches the per-corpus grammar ('per-corpus Δ <d> / p=<p> and <d> / p=<p>')");
  }

  const tokens = text.match(/mean Δ ([+-][\d,]+(?:\.\d+)?) unique tokens, CI95 \[([+-][\d,]+(?:\.\d+)?), ([+-][\d,]+(?:\.\d+)?)\]/);
  if (tokens) {
    values.tokensDelta = tokens[1].replace(/,/g, "");
    values.tokensCiLow = tokens[2].replace(/,/g, "");
    values.tokensCiHigh = tokens[3].replace(/,/g, "");
  } else {
    errors.push("claim region no longer matches the token-cost grammar ('mean Δ <d> unique tokens, CI95 [<lo>, <hi>]')");
  }

  return { errors, values };
}

/** True when the claimed numeric string matches the record value at the claim's displayed precision. */
export function matchesAtDisplayedPrecision(claimStr, recordValue) {
  const claim = Number(claimStr);
  if (!Number.isFinite(claim) || !Number.isFinite(recordValue)) return false;
  const dot = claimStr.indexOf(".");
  const decimals = dot === -1 ? 0 : claimStr.length - dot - 1;
  const tolerance = 0.5 * 10 ** -decimals + 1e-9;
  return Math.abs(claim - recordValue) <= tolerance;
}

/** Pull the primary comparison arm (addition B) out of a record's per-model measurement block. */
function primaryArm(modelBlock) {
  if (modelBlock.arms && modelBlock.primary_arm && modelBlock.arms[modelBlock.primary_arm]) {
    return modelBlock.arms[modelBlock.primary_arm];
  }
  return modelBlock;
}

function pooledArm(pooledRecord) {
  const models = Object.values(pooledRecord.measured ?? {});
  if (models.length !== 1) throw new Error(`pooled record: expected exactly 1 model block, found ${models.length}`);
  return primaryArm(models[0]);
}

function perCorpusArm(record) {
  const corpora = Object.values(record.measured ?? {});
  if (corpora.length !== 1) throw new Error(`per-corpus record: expected exactly 1 corpus block, found ${corpora.length}`);
  const models = Object.values(corpora[0]);
  if (models.length !== 1) throw new Error(`per-corpus record: expected exactly 1 model block, found ${models.length}`);
  return primaryArm(models[0]);
}

/**
 * Verify the claim region against the loaded records.
 * records = { pooled, en, de } (parsed JSON). Returns an array of error strings (empty = pass).
 */
export function verifyClaims(regionText, records) {
  const { errors, values } = parseClaimNumbers(regionText);
  if (errors.length > 0) return errors;

  const pooled = pooledArm(records.pooled);

  if (Number(values.pooledN) !== pooled.n_paired_observations) {
    errors.push(`pooled n drifted: claim says n=${values.pooledN}, record says ${pooled.n_paired_observations}`);
  }
  if (!matchesAtDisplayedPrecision(values.pooledDelta, pooled.accuracy.delta)) {
    errors.push(`pooled accuracy delta drifted: claim says ${values.pooledDelta}, record says ${pooled.accuracy.delta}`);
  }
  if (!matchesAtDisplayedPrecision(values.pooledP, pooled.accuracy.mcnemar_p)) {
    errors.push(`pooled McNemar p drifted: claim says ${values.pooledP}, record says ${pooled.accuracy.mcnemar_p}`);
  }
  if (!matchesAtDisplayedPrecision(values.tokensDelta, pooled.tokens_unique.delta_mean)) {
    errors.push(`token mean delta drifted: claim says ${values.tokensDelta}, record says ${pooled.tokens_unique.delta_mean}`);
  }
  const [ciLow, ciHigh] = pooled.tokens_unique.delta_ci95;
  if (!matchesAtDisplayedPrecision(values.tokensCiLow, ciLow)) {
    errors.push(`token CI95 lower bound drifted: claim says ${values.tokensCiLow}, record says ${ciLow}`);
  }
  if (!matchesAtDisplayedPrecision(values.tokensCiHigh, ciHigh)) {
    errors.push(`token CI95 upper bound drifted: claim says ${values.tokensCiHigh}, record says ${ciHigh}`);
  }

  // Per-corpus pairs are order-free in the claim: match each claimed (delta, p) pair
  // to a distinct per-corpus record; every record must be claimed by exactly one pair.
  const perCorpusRecords = [
    { name: "en", arm: perCorpusArm(records.en) },
    { name: "de", arm: perCorpusArm(records.de) },
  ];
  const claimedPairs = values.perCorpusPairs;
  const taken = new Set();
  for (const pair of claimedPairs) {
    const match = perCorpusRecords.find(
      (r) =>
        !taken.has(r.name) &&
        matchesAtDisplayedPrecision(pair.delta, r.arm.accuracy.delta) &&
        matchesAtDisplayedPrecision(pair.p, r.arm.accuracy.mcnemar_p),
    );
    if (match) {
      taken.add(match.name);
    } else {
      const available = perCorpusRecords
        .filter((r) => !taken.has(r.name))
        .map((r) => `${r.name}: Δ ${r.arm.accuracy.delta} / p=${r.arm.accuracy.mcnemar_p}`)
        .join("; ");
      errors.push(`per-corpus pair drifted: claim says Δ ${pair.delta} / p=${pair.p}, but no record matches (records: ${available})`);
    }
  }

  // Overclaim guard: a cited record that is not certified comparable must be caveated.
  const caveated = /comparab/i.test(regionText);
  for (const [name, record] of Object.entries(records)) {
    const comparable = record.comparability?.comparable;
    if (comparable !== true && !caveated) {
      errors.push(
        `overclaim: record '${name}' has comparability.comparable=${JSON.stringify(comparable)} but the claim region states no comparability caveat`,
      );
    }
  }

  return errors;
}

function main() {
  const repoRoot = repoRootFromCwd();

  const researchPath = path.join(repoRoot, "RESEARCH.md");
  if (!fs.existsSync(researchPath)) {
    console.log("check-agent-utility-claims: FAIL — RESEARCH.md is missing");
    process.exitCode = 1;
    return;
  }
  const region = extractClaimRegion(fs.readFileSync(researchPath, "utf8"));
  if (region === null) {
    console.log("check-agent-utility-claims: FAIL — RESEARCH.md has no <!-- agent-utility-claim:begin/end --> marker region");
    console.log("  The published agent-utility claim must live inside that region so this projector can own it.");
    process.exitCode = 1;
    return;
  }

  const records = {};
  for (const [name, rel] of Object.entries(RECORD_PATHS)) {
    const full = path.join(repoRoot, rel);
    if (!fs.existsSync(full)) {
      console.log(`check-agent-utility-claims: FAIL — source record missing: ${rel}`);
      process.exitCode = 1;
      return;
    }
    records[name] = JSON.parse(fs.readFileSync(full, "utf8"));
  }

  const errors = verifyClaims(region, records);
  if (errors.length === 0) {
    console.log("check-agent-utility-claims: OK (RESEARCH.md claim region matches the committed utility-comparison records)");
    return;
  }

  console.log(`check-agent-utility-claims: FAIL (${errors.length} error(s))`);
  console.log("  RESEARCH.md's agent-utility claim region no longer matches the committed run records.");
  console.log("  Fix the claim (or, for a new certified run, update RECORD_PATHS here) — never hand-edit numbers.");
  for (const e of errors) console.log(`- ${e}`);
  process.exitCode = 1;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
