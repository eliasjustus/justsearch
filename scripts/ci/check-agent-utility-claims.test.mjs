/**
 * Tests for the agent-utility claims projector (tempdoc 624 Design 2): it PASSES when
 * RESEARCH.md's marker region matches the committed utility-comparison records, and
 * FAILS naming the drifted value on any number drift, grammar break, or overclaim
 * (comparability.comparable !== true cited without a caveat).
 *
 * Run: `node scripts/ci/check-agent-utility-claims.test.mjs` (exits non-zero on failure)
 */
import assert from "node:assert/strict";
import {
  extractClaimRegion,
  matchesAtDisplayedPrecision,
  parseClaimNumbers,
  verifyClaims,
} from "./check-agent-utility-claims.mjs";

let passed = 0;
const failures = [];
const ok = (label, cond) => {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (e) {
    failures.push(e.message);
  }
};

// --- fixtures mirroring the committed record shape (values from the 2026-07-03 run) ---
const arm = (delta, p, n, tokensDelta, ci) => ({
  accuracy: { delta, mcnemar_p: p },
  tokens_unique: { delta_mean: tokensDelta, delta_ci95: ci },
  n_paired_observations: n,
});
const pooledRecord = (a) => ({
  measured: { haiku: { ...a, arms: { addition_b: a }, primary_arm: "addition_b" } },
  comparability: { comparable: true },
});
const perCorpusRecord = (a) => ({
  measured: { "golden/x": { haiku: { ...a, arms: { addition_b: a }, primary_arm: "addition_b" } } },
  comparability: { comparable: true },
});

const records = () => ({
  pooled: pooledRecord(arm(-0.0269, 0.476422, 260, 448.7730769, [-1466.542885, 2375.890769])),
  en: perCorpusRecord(arm(-0.0692, 0.200185, 130, 1425.04, [-1712.66, 4513.12])),
  de: perCorpusRecord(arm(0.0154, 0.859684, 130, -527.49, [-2843.57, 1836.67])),
});

const CLAIM = `
> retrieval added to its existing file tools showed **no measurable effect on accuracy** (pooled n=260
> paired, Δ −0.027, McNemar p=0.476; per-corpus Δ −0.069 / p=0.200 and +0.015 / p=0.860) **and no
> measurable token-cost difference** (mean Δ +449 unique tokens, CI95 [−1467, +2376]).
`;

// --- precision matcher ---
ok("matches at 3 decimals: -0.027 vs -0.0269", matchesAtDisplayedPrecision("-0.027", -0.0269));
ok("matches at 3 decimals: 0.860 vs 0.859684", matchesAtDisplayedPrecision("0.860", 0.859684));
ok("matches at 0 decimals: +449 vs 448.773", matchesAtDisplayedPrecision("+449", 448.773));
ok("rejects real drift: -0.027 vs -0.094", !matchesAtDisplayedPrecision("-0.027", -0.094));
ok("rejects half-step drift: 0.476 vs 0.478", !matchesAtDisplayedPrecision("0.476", 0.478));

// --- region extraction ---
ok(
  "extracts marker region",
  extractClaimRegion("a\n<!-- agent-utility-claim:begin -->X<!-- agent-utility-claim:end -->\nb") === "X",
);
ok("null when markers absent", extractClaimRegion("no markers here") === null);

// --- grammar parse (Unicode minus + blockquote wrapping) ---
{
  const { errors, values } = parseClaimNumbers(CLAIM);
  ok("claim grammar parses without errors", errors.length === 0);
  ok("pooled delta parsed with hyphen-minus", values.pooledDelta === "-0.027");
  ok("token CI bounds parsed", values.tokensCiLow === "-1467" && values.tokensCiHigh === "+2376");
}

// --- end-to-end verify: pass ---
ok("verifies the signed claim against matching records", verifyClaims(CLAIM, records()).length === 0);

// --- drift detection names the drifted value ---
{
  const r = records();
  r.pooled.measured.haiku.arms.addition_b.accuracy.delta = -0.094;
  const errs = verifyClaims(CLAIM, r);
  ok("pooled delta drift fails", errs.length > 0);
  ok("pooled delta drift is named", errs.some((e) => e.includes("pooled accuracy delta drifted")));
}
{
  const r = records();
  r.en.measured["golden/x"].haiku.arms.addition_b.accuracy.mcnemar_p = 0.03;
  const errs = verifyClaims(CLAIM, r);
  ok("per-corpus p drift fails", errs.some((e) => e.includes("per-corpus pair drifted")));
}
{
  const r = records();
  r.pooled.measured.haiku.arms.addition_b.tokens_unique.delta_ci95 = [-100, 100];
  const errs = verifyClaims(CLAIM, r);
  ok("token CI drift is named", errs.some((e) => e.includes("token CI95 lower bound drifted")));
}
{
  const r = records();
  r.pooled.measured.haiku.arms.addition_b.n_paired_observations = 175;
  const errs = verifyClaims(CLAIM, r);
  ok("pooled n drift is named", errs.some((e) => e.includes("pooled n drifted")));
}

// --- per-corpus order-freedom ---
{
  const swapped = CLAIM.replace(
    "Δ −0.069 / p=0.200 and +0.015 / p=0.860",
    "Δ +0.015 / p=0.860 and −0.069 / p=0.200",
  );
  ok("per-corpus pairs match in either order", verifyClaims(swapped, records()).length === 0);
}

// --- grammar break fails loudly ---
{
  const rewritten = CLAIM.replace("McNemar p=0.476", "with strong significance");
  const errs = verifyClaims(rewritten, records());
  ok("a rewrite that breaks the claim grammar fails", errs.some((e) => e.includes("pooled-accuracy grammar")));
}

// --- overclaim guard ---
{
  const r = records();
  r.de.comparability.comparable = false;
  const errs = verifyClaims(CLAIM, r);
  ok("non-comparable record without caveat fails as overclaim", errs.some((e) => e.startsWith("overclaim: record 'de'")));
  const caveated = `${CLAIM}\n> One caveat: the German record is not fully comparable across arms.`;
  ok("caveat in the region satisfies the overclaim guard", verifyClaims(caveated, r).length === 0);
}

if (failures.length > 0) {
  console.error(`check-agent-utility-claims.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`- ${f}`);
  process.exitCode = 1;
} else {
  console.log(`check-agent-utility-claims.test: OK (${passed} passed)`);
}
