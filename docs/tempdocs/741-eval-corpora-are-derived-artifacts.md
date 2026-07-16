---
title: "741 — Eval corpora are derived artifacts stored as source; LFS is the wrong fix"
type: tempdoc
status: charter — investigated, decision taken (do NOT route corpora to LFS), real fix NOT implemented. Owner decision open (§5).
created: 2026-07-15
updated: 2026-07-15
related:
  - 707 (corpus-injection probe — the recipe/commitment design)
  - 719 (707 corpus materialization; fail-closed publication boundary)
  - 709 (shared dataset fetch cache — `jseval corpus-fetch-clerc|corpus-fetch-miracl`)
  - 739 (the follow-up set this was spun out of)
  - PR #200 (documented the repo's zero-LFS posture)
  - PR #201 (repaired the CRLF bake-in that made all 8 commitment manifests unverifiable)
---

# 741 — Eval corpora are derived artifacts stored as source

## 1. What happened

739's follow-up set proposed routing `scripts/jseval/*-corpora/**/*.jsonl` (21
files, 73MB) through Git LFS, to stop eval corpora accumulating as blobs. That
change was implemented, verified, and then **withdrawn before merge** on the
findings below. This doc records why, so the reasoning is not re-derived.

## 2. The measured problem is real

Corpora do accumulate. They are **not** regenerated in place — exactly one
in-place modification across 195 commits. They arrive as new sets:

| Set | Added | Size |
|---|---|---|
| `635-corpora` | 2026-06-25 | 52 MB |
| `624-corpora` | 2026-07-03 | 9 MB |
| `707-corpora` | 2026-07-14 | 12 MB |

Three sets in the repo's 20-day life, ~one per 10 days. `battlefield-en-scale-v1/docs.jsonl`
alone is 45MB — the largest object in the repo. At that rate this is ~700MB/year
of permanent pack growth.

## 3. Why LFS is the wrong fix

**(a) The data is derived, not source.** `707-corpora/*/recipe.json` is a
deterministic build spec: `method: "real-text-injection-v1"`, `seed: 707`, and an
explicit `gold_id -> host_id` host mapping. `corpus_generate.materialize_doc_entry`
builds from it; `corpus_certify.certify_materialized_family` /
`_validate_commitment` verify the result; `commitment.v1.json` pins the output
bytes by SHA256. 709's `jseval corpus-fetch-clerc|corpus-fetch-miracl` already
caches the *source* corpora outside git.

So the chain is complete without the output in git:
**fetch (cached source) + recipe.json (small, in git) -> materialize -> verify
against commitment.v1.json.** That the manifests are load-bearing is not a guess:
PR #201 exists solely to repair a CRLF bake-in that made all 8 of them
unverifiable.

You do not LFS-track build output. LFS would take derived, regenerable data and
entrench it somewhere *more* expensive — treating the symptom.

**(b) This repo deliberately has zero LFS.** `origin/main` contains **no**
LFS-tracked file. PR #200 (2026-07-15) states the posture: *"This public repo
never tracks model blobs (0 `.onnx` in its history; CI builds without them)… The
private repo LFS-tracks them — [that] was inherited here at v0.1.0 despite never
being true of this repo."* The LFS proposal would have made these corpora the
repo's first LFS content, adding a clone-time `git-lfs` dependency and a quota
surface that does not currently exist. On a public repo, LFS bandwidth is drawn
against the owner. **(Confidence: the mechanism is standard; current GitHub
limits were not re-verified — confirm before anyone reintroduces LFS here.)**

**(c) The proposal's stated justification was false.** It claimed the 73MB would
join "~4.9GB already in LFS", so ~1.5% more. That 4.9GB is a *local* `.git/lfs`
store from the gitignored models directory, not this repo's. Recorded here
because the error is the interesting part: a plausible number, measured from the
wrong place, nearly bought an architectural change.

## 4. What was verified before withdrawal

The LFS implementation itself was sound — it was withdrawn on architecture, not
defect:

- The 21 corpora converted to correct LFS pointers; `--renormalize` was scoped to
  the pathspec, not `.` (a whole-tree renormalize would have rewritten line
  endings repo-wide under `text=auto`).
- **No byte change.** Adding `-text` to files hashed by the commitment manifests,
  one hour after #201 repaired a CRLF bake-in in those same manifests, was the
  live risk. It did not fire: manifest hash, LFS pointer `oid`, and working-tree
  SHA256 were identical (`2223bd9b…`), and after withdrawal all **8/8** manifests
  still verify.
- `*.exe` was excluded and should stay excluded — see §6.

## 5. Open decision (owner)

The growth is real and unaddressed. Three paths, none taken here:

- **(A) Stop committing materialized corpora.** Keep `recipe.json` +
  `commitment.v1.json` + `meta.json` in git; materialize on demand from the
  709-cached source; verify against the commitment. Matches 707/719's apparent
  design intent and removes the growth at its cause. Largest change; touches the
  eval workflow, so it belongs to 707/719's owners, not to a passing hygiene fix.
- **(B) Accept the growth.** ~700MB/year of pack. Revisit if clone time becomes a
  real complaint.
- **(C) LFS anyway.** Rejected above; recorded so it is not re-proposed without
  answering §3.

**(A) is the architecturally right answer**, but it is a design decision about
eval reproducibility, not a hygiene chore, and it should not be taken as a
side-effect of a hint-hook fix.

## 6. Settled: `*.exe` stays out

`vc_redist.x64.exe` (25MB) was also proposed for LFS. Two independent reasons not
to, both measured:

- It would have **shipped a broken installer**: the file is listed in
  `tauri.conf.json` `"resources"`, and `build-installer.yml` checks out with
  plain `actions/checkout@v7` (no `lfs`), so the bundler would receive a 130-byte
  pointer, ship it as the VC++ redistributable, and still build green — nothing
  validates that file's contents.
- It is **not worth fixing later**: the file has exactly **one commit in the
  repo's entire history** (the initial release). The "stop new blobs" benefit is
  zero.

## 7. Evidence

| Claim | How |
|---|---|
| 3 corpora sets, sizes + dates (§2) | `git log --diff-filter=A --format=%ad --reverse -- scripts/jseval/<set>` for each; sizes via `git ls-tree -r -l origin/main -- <set>` |
| Not regenerated in place: 1 modification across 195 commits | `git log --diff-filter=M --oneline -- '*/docs.jsonl' '*/fabricated-docs.jsonl'` → one commit (`853e152b`, tempdoc 664); per-file `--diff-filter=A/M` on three sampled corpora → `A=1 M=0` each |
| `recipe.json` is a deterministic build spec | `git cat-file -p origin/main:scripts/jseval/707-corpora/de-miracl/1000-short-natural/recipe.json` → `method: real-text-injection-v1`, `seed: 707`, explicit `host_mapping` |
| Materialize + certify + commitment machinery exists | `corpus_generate.materialize_doc_entry:571`, `corpus_certify.certify_materialized_family:65`, `corpus_certify._validate_commitment:905` |
| 709 caches source corpora outside git | `CACHE_BACKED = /corpus-fetch-(?:clerc|miracl)/i` in `scripts/agent-analytics/hooks/dataset-cache-hint.mjs:36` |
| `origin/main` has zero LFS-tracked files | `git ls-tree -r --name-only origin/main \| grep -cE '\.(onnx\|gguf)$\|onnx_data'` → 0 |
| No byte change from the LFS attempt (§4) | manifest hash, LFS pointer `oid`, and working-tree SHA256 all `2223bd9b…` for `707-corpora/de-miracl/1000-short-natural/fabricated-docs.jsonl` |
| 8/8 manifests verify after withdrawal | SHA256 of each `fabricated-docs.jsonl` vs its `commitment.v1.json` `files` entry → 8 verified, 0 mismatched |
| `vc_redist.x64.exe` has one commit (§6) | `git log --oneline --follow -- modules/shell/src-tauri/resources/vc_redist.x64.exe` → 1 (`29579e51`, the initial release) |
| Installer bundles it without lfs (§6) | `tauri.conf.json` `"resources"` lists `resources/vc_redist.x64.exe`; `.github/workflows/build-installer.yml:25` uses bare `actions/checkout@v7` |


## 8. Unverified assumptions

- **Materialization was not run.** §3(a) infers reproducibility from `recipe.json`'s
  determinism (explicit seed + host mapping) and the existence of
  `materialize_doc_entry` / `certify_materialized_family` / `_validate_commitment`.
  **Nobody re-materialized a corpus and compared it to its commitment.** Option (A)
  is unsafe until someone does — if materialization is non-deterministic or needs an
  LLM, the outputs must stay committed and (B) is the only honest choice.
- **`624-corpora` / `635-corpora` were not checked for recipes.** Only `707-corpora`
  carries `recipe.json` + `commitment.v1.json`. The older sets may have no
  reproducibility path, in which case (A) applies only to 707-style corpora and the
  52MB `635` set stays regardless.
- **GitHub LFS limits not re-verified** (§3b).
