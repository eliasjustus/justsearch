---
classification: declared-growth
tempdoc: 916
---
Declares the **four TEMPORARY** configuration keys tempdoc 916 Part 1 (decision-review lane E) adds
as the instrument for the chunk-size campaign:

- `justsearch.chunking.sweep.target_tokens` (Int, no default) — target chunk size in estimated
  tokens. Unset → `ChunkSplitter.DEFAULT_CHUNK_TOKENS` (500).
- `justsearch.chunking.sweep.overlap_tokens` (Int, no default) — overlap between adjacent chunks.
  Unset → `ChunkSplitter.DEFAULT_OVERLAP_TOKENS` (50).
- `justsearch.chunking.sweep.min_tokens` (Int, no default) — the splitter's advance floor.
  Unset → `ChunkSplitter.MIN_CHUNK_TOKENS` (100).
- `justsearch.chunking.sweep.threshold_chars` (Int, no default) — the shortest document that is
  chunked at all. Unset → 2000.

**Why these are keys and not constants.** Chunk granularity is a *fingerprint* input: every arm of
the `{128, 256, 384, 500} × {0, 25, 50}` matrix is a full reindex, so the value has to be readable
by the WORKER at index time. The three ways to get it there were weighed in 916 §C.1. A raw
`System.getProperty` in `ChunkSplitter` is refused by `checkNoDirectJustsearchSysProp` — and routing
around a build gate to avoid declaring a key is precisely the evasion that gate exists for. A
build-time constant override means a recompile between arms, which is itself a confounder (the
argument 885 item 19 made for the NRT cadence keys). That leaves declared configuration, resolved on
the Head and delivered to the Worker through the ordinal-450 snapshot — the only channel that
crosses the process boundary, as the 885 [R1] defect established.

**Four, not three — a deliberate revision of §C.1's "+3/+3".** 916 §C.1 proposed three keys and
§C.2 said `CHUNK_THRESHOLD_CHARS` is "derived after, not a third axis". Both halves of that survive
and one is amended:

- `threshold_chars` is **still not a sweep axis** — it is held fixed at 2000 for all twelve arms,
  because it decides *which documents chunk at all* and varying it would change the population each
  arm is measured over. But §C.2 also commits to *deriving* it afterwards ("≈ 4× the chosen chunk
  size in chars, pinned with the short-corpus control"), and that derivation is itself a
  measurement. Giving it a key costs one declared pair and makes that stage a restart; withholding
  it makes the stage a rebuild, which is the confounder the whole design is avoiding. The key is
  deleted with the other three regardless of which value wins.
- `min_tokens` was not in §C.1 at all and is the more important addition. `ChunkSplitter` advances
  by `max(chunkLength − overlapChars, minChars)`. Measured on this branch
  (`ChunkingPolicyTest.minTokensCapsOverlapAtSmallTargets`, and the probe recorded in 916 §K.2): at
  a 128-token target the shipped floor of 100 tokens delivers a mean **133 chars** of overlap where
  the arm asked for ~190, and 57 chunks where an unbound floor gives 79. At 256, 384 and 500 the
  floor is already inert and scaling it changes nothing. So without this key the four 128-token arms
  would silently measure a different overlap than the one on their label, and the distortion would
  be *asymmetric across the matrix* — the worst kind of confounder, because it looks like a real
  effect of chunk size. The campaign sets `min_tokens = target / 5`, which reproduces the shipped
  100 exactly at the incumbent 500.

**Unset is bit-identical to today.** All four resolve to `null` when unset;
`ResolvedConfig.Index.effectiveChunk*()` then returns the shipped constant, and
`ChunkDocumentWriter.activePolicy()` returns `ChunkingPolicy.DEFAULT`.
`ChunkingPolicyTest.defaultPolicyReproducesPre916` asserts the policy entry point agrees with the
pre-916 int overload across three chunking modes, and `ChunkSizeSweepKeysTest` asserts that an unset
key is not even materialized into the Worker snapshot (a `putDefault` here would have made "unset"
distinguishable from today, which is why none of the four declares one).

**These keys never reach `main` — owner decision, 2026-09-03.** The branch carrying this changeset
(**PR #622**) is a **DRAFT campaign branch and is not intended to merge**. The final Part 1 PR
carries only the *chosen* `(target, overlap, min, threshold)` as constants, the chunker version
string, the fixture, the driver and the register updates — not these four keys, not the four
nullable `ResolvedConfig.Index` fields, not the `effectiveChunk*()` accessors, not
`ChunkSizeSweepKeysTest`, and not the campaign half of `ChunkingPolicyResolutionTest`.

So there is **nothing on `main` to delete afterwards, and the `config-surface` baseline on `main` is
never moved**. The baseline advance below exists so THIS branch is self-consistent and the gate is
satisfied *here* rather than deferred; it is not a promise about a future commit. That is strictly
stronger than 916 Part 2's "authorised on condition of deletion" shape — which was honoured
(`aa605ec3` removed both keys and returned the pin) but did require a window in which a parked key
could have been forgotten. Net permanent config surface after Part 1: **0, by construction.**

`ChunkingPolicy` and the `effectiveChunk*()` accessors are the only parts that could reasonably
survive deletion, and they should not: with the keys gone there is exactly one policy value, so the
record collapses back to the constants it wraps. The one piece that IS permanent is
`ChunkingPolicyResolutionTest.mirroredDefaultsDoNotDrift`, which is the price of
`modules/configuration` mirroring four numbers it cannot import.

## Baseline advance (same commit, tempdoc 883 rule)

`gates/config-surface/baseline.txt` moves in this commit, alongside the keys it accounts for:

| metric | was | now | delta |
| :--- | ---: | ---: | :--- |
| `env_sysprop_pairs` | 250 | **254** | +4 = exactly the four keys above |
| `yaml_keys` | 111 | 111 | unchanged — these are env/sysprop only. A YAML surface would be a *user-facing* knob, and a temporary campaign instrument must not look like one |
| `config_keys` | 56 | 56 | unchanged — no new `ConfigKey` entry (EnvRegistry-backed, not YAML-only) |

Measured with `node scripts/docs/generate-runtime-config-matrix.mjs` on this branch
(`yaml_keys=111 env_sysprop_pairs=254 config_keys=56 rows=310`). The pre-merge pin of 111/250 is what
`main` measured on 2026-09-02; this branch adds these four and no others, so the advance is fully
attributable. It applies to **this campaign branch only** — since the branch does not merge, `main`
stays at 111/250 and the ratchet's meaning on `main` is untouched.
