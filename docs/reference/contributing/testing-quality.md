---
title: Test Quality Guidelines
type: reference
status: stable
description: "Anti-patterns to avoid and high-value test patterns to prefer."
---

# Test Quality Guidelines

Before writing a test, ask: **"What bug would this catch?"** If you can't articulate a realistic bug scenario, the test is likely trivial.

## Trivial Test Anti-Patterns (Avoid)

| Anti-pattern | Example | Why it's useless |
|--------------|---------|------------------|
| Getter/setter tests | `assertEquals(x, obj.getX())` after `setX(x)` | Tests Java, not your code |
| Constructor-sets-field | `assertNotNull(new Foo(x).getField())` | Can't fail unless JVM is broken |
| Mock-wiring tests | Verify mock was called with exact args | Tests your test setup, not behavior |
| Tautology assertions | `assertEquals(list.size(), list.size())` | Always passes |
| Coverage padding | Execute code path but assert nothing meaningful | Inflates coverage, catches nothing |

## High-Value Test Patterns (Prefer)

| Pattern | Example | What it catches |
|---------|---------|-----------------|
| Boundary conditions | Empty input, max size, off-by-one | Edge case bugs |
| Error paths | Invalid input -> expected exception | Missing validation |
| State transitions | A->B->C sequences, concurrent access | State machine bugs |
| Contract preservation | Output satisfies documented invariants | API contract drift |
| Regression anchors | Reproduces a specific past bug | Prevents bug recurrence |

## AI Eval Gate Rules (Deterministic by Default)

For AI/RAG regression gates, prefer data-driven deterministic checks over fuzzy scoring.

| Rule | Do | Avoid |
|------|----|-------|
| Ambiguity handling | Encode allowed alternatives in manifest data (for example `not found||not specified`) | Ad-hoc semantic judge logic in CI gate conditions |
| Threshold ownership | Keep gate thresholds in the manifest (`modules/system-tests/src/test/resources/manifests/rag-eval-truth.v1.json`) | Hard-coded threshold constants spread across test code |
| Threshold changes | Include baseline/diff evidence when changing thresholds (why change is needed, expected impact) | Silent threshold relaxation without artifact evidence |
| CI gate style | Gate on deterministic metrics (coverage, forbidden fact rate, faithfulness, etc.) | Block merges on subjective free-form judgment text |

## Value-Law Verification

Some functions obey an **executable law** — a round-trip (`decode(encode(x)) == x`), a conservation
invariant (chunk offsets reconstruct their content; `length() <= budget`), idempotence, a precedence
total-order, framing-invariance, a numeric bound. For these, prefer the stronger option in this order:

1. **Make the violation unrepresentable by type.** A smart-constructor that rejects bad inputs beats a
   test that checks for them — the bad value can never reach a caller. Precedents: `StreamId` (regex
   validated), `ResumeTokenCodec.Decoded` (compact constructor rejects `seq < 0`, which makes the codec
   round-trip *total* over its domain).
2. **For a free-oracle law, write a randomized property test.** A *free* oracle means the input itself
   is the spec (no re-implementation needed): round-trip, conservation, idempotence, framing-invariance,
   metamorphic relations. Worked examples in this repo: `modules/ui-web/src/api/sse.pbt.test.ts`
   (fast-check, framing-invariance), `ChunkTilingPropertyTest` (jqwik, chunk-offset conservation — it
   surfaced two real `ChunkSplitter` bugs), `JqwikSmokeTest` (surrogate-safe truncation). Keep property
   tests cheap so they run in the normal suite, not behind an opt-in tag.
3. **Call out a weak oracle in review.** If the only way a test can check the law is by re-deriving the
   function it tests, it is green-but-empty — flag it; don't ship it silently. Statistical *quality*
   metrics (nDCG and friends) are not invariants and belong in the eval harness, not here.

A register/gate for these laws was prototyped and removed (tempdoc 554) — the discipline is the guidance
above, not machinery.

## Self-Review Prompt

After writing tests, ask:
1. If I delete the implementation, do these tests fail?
2. If I introduce a subtle bug, will any test catch it?
3. Am I testing behavior or implementation details?

For **law-bearing seams** registered in `governance/logic-seams.v1.json`, question 2 is no longer just
a prompt — it is mechanized: the `test-efficacy` discipline gate (tempdoc 555) injects faults (PIT
mutation testing) and ratchets the share that a seam's tests kill. See
`docs/reference/contributing/discipline-gate-kernel.md`.
