<!-- Sidecar of docs/tempdocs/915-lane-d-index-fingerprint-identity-and-reindex-bundle.md — moved here per the tempdoc size-cap split (930 §19.3 F4, 2026-09-05). Headings below are unchanged from the main file; this directory is exempt from check-tempdoc-size.mjs. -->

## Cross-lane requests

- **Lane E** — `ChunkSplitter.ALGORITHM_VERSION` is new and additive (`ChunkSplitter.java:91-99`).
  If your PR touches `ChunkSplitter`, expect a one-constant merge. Bump it when the splitting
  *algorithm* changes with the token counts unchanged; the token counts themselves are already
  fingerprint inputs, so your chunk-size number is picked up automatically once either PR merges.
- **Lane B** — RISK-011 is now instrumented at `tempdoc:915#C Design (Phase 1), tightened` and is
  deliberately left **Monitoring**, not closed; the notes say why. ADR-0007's entity-boost amendment
  stays a Phase 3 concern; nothing in Phase 1 touches the entity fields.
- **Lane C** — Phase 1 touches `IndexGenerationManager` (`worker-core`) and `IndexStatusOps`
  (`worker-services`), which are not in lane D's declared file list but are on the migration /
  status path the brief's sweep instruction reaches. Flagging in case of overlap.
- **Programme owner** — see the two open questions below.

### PRs

- **#620** — `feat(915): one truthful index fingerprint, blue/green as the production default (lane D
  phase 1)`. **Merged** at `b9b1c2c0`. It carries the whole of Phase 1 plus the wave-1 fold-ins and
  eight rounds of review/validation fixes. Phase 2 is intentionally split into Worker-side PR-A and
  Head-side PR-B (§P2.D); Phase 3 remains separate.

### Items: done, deviated, skipped

**Done as specified:** A1-A3, A5-A13 and W1-W5 (§A checkboxes, evidence per row in §D).

**Deviated, with the reason recorded where the deviation lives:**

- **A4** — `SsotCommitMetadataSource` no longer sources the index's identity from
  `SSOT/versions/catalog.json`, but `grammar_ver` / `template_ver` were **not** deleted: they are
  observability with live consumers, and the index's identity does not depend on them (§C.6, O1 —
  closed by owner decision). The coupling is cut; the fields stay.
- **A2 input list** — grew during the review round beyond the original draft: `ner_model_sha256`,
  `threshold_chars`, `preview.max_chars`, `ChunkSplitter.ALGORITHM_VERSION`, and
  `analysis.lucene_version` / `analysis.icu_version` were added, and HNSW `m`/`ef_construction` are
  hashed as the **effective** values rather than the raw nullable config. Each addition is a physical
  input that was missing, not scope creep; the `lucene_version`/`icu_version` pair is deliberately
  coarse (one rebuild per library bump) and says so in `11-index-schema-migration.md`.
- **A9** — the brake bounds auto-rebuilds per `index_fingerprint` (`MAX_AUTO_REBUILD_ATTEMPTS = 3`),
  and the exhausted state **serves Blue read-only** rather than refusing to start. That is more than
  "rate-limit"; it came out of delta-review B3 and is what makes the state observable at all.

**Skipped, and why:** nothing on the Phase 1 list. The two things NOT done are scheduled work, not
skips: the live blue/green loop was open item O3 until the validator ran it (now closed by the
2026-09-03 arms), and the size/RSS measurements belong to Phase 3.

### Evidence

- **Static:** §G (full kernel 35 gates / 1 inherited `ts-any` fail; `check-readiness-reason-codes`
  56 emittable / 50 worded; ui-web 40/40; 6267 FE unit tests; full JVM suite green under
  `cleanTest --no-build-cache`).
- **Falsification:** §F, F1-F5 and G30-G59b — every new or modified guarantee broken once, watched
  fail with the observed assertion text, restored from byte copies. Two driver defects were caught by
  the driver's own zero-XML invariant rather than by luck (§F rounds 5 and 8).
- **Live, by an independent validator (not the implementer):**
  - `419aadb7` — the seven-arm run at `12955fe9`. Arms 0, 2-6 PASS; arm 1 produced D1-D4.
  - `51c7e1c2` — re-validation at `403f4b30`. Arms 1, 3, 4, 5, 6 PASS (D1-D4, O14, the
    budget-cleared-by-hand path and the legacy upgrade all confirmed live); one FAIL, R1.
  - `56e75cd7` — arm 2 re-run at `c06d8b25`. All ten assertions PASS; one residual, R2, fixed here.
  - Arm 2's headline fact: under `FAIL_CLOSED` the index was left **byte-identical** — `state.json`
    SHA-256 `E3BF2686…` before and after, 26 index files identical by name and size.

### Measurements

The only Phase 1 measurement is the **fingerprint input list itself** — what is in the hash and what
is deliberately out. It is documented as current truth in
`docs/explanation/11-index-schema-migration.md` § "Index fingerprint (`index_fingerprint`)": in are
the catalog schema version, the per-field physical projection, the analyzer definitions,
`vector_format`, effective HNSW `m`/`ef_construction`, the chunking parameters + splitter algorithm
version, `preview.max_chars`, `analysis.lucene_version`/`icu_version`, and the three model shas; out
are `rmwPolicy` annotations, all query-time scoring (BM25 `k1`/`b`, boosts, `ef_search`), and the
search-intent grammar / prompt packs / templates.

**No index-size or RSS numbers are reported, by design** — they are Phase 3's subject, and quoting a
number here that nothing measured would be worse than the gap.

**The search-quality register was not updated, because Phase 1 changed no number.** Every exclusion
above is exclusion of a *query-time* lever from an *index-identity* hash: retrieval behaviour,
fusion, reranking and the eval baselines are untouched. The register is for numbers that moved.

### Cross-lane

- **Lane E — authorised, four lines, after #620 merges.** `SsotCommitMetadataSource` reads the
  chunking constants from `ChunkSplitter`; lane E may change those reads to the `effectiveChunk*()`
  accessors. It is a mechanical four-line edit and lane D has no objection — but it must land
  **after** #620, not as a conflicting concurrent edit, because the same file is rewritten by A4.
  Also standing: `ChunkSplitter.ALGORITHM_VERSION` is new and additive; bump it when the splitting
  *algorithm* changes with token counts unchanged (token counts are already fingerprint inputs).
- **Lane B** — RISK-011 instrumented at `tempdoc:915#C`, deliberately left **Monitoring**.
- **Lane C** — Phase 1 touches `IndexGenerationManager` (worker-core) and `IndexStatusOps`
  (worker-services), outside lane D's declared file list but on the migration/status path.
- **Lane F** — two things to know. (1) The worker↔Head fatal-reason channel now carries **two**
  codes, `index_corrupt` and `index_schema_mismatch`, and the Head **latches** either on read because
  the marker is one-shot; anything new that consumes `WorkerFatalReasonMarker` must not assume a
  second read is possible. (2) `BootRecoveryDecision` gained `Veto.INDEX_FATAL`, so a fatal index
  cause now short-circuits the respawn ladder for **both** axes — a behaviour change on the
  corruption axis too, and the operator hatch is the documented exemption.

### Residue routed

- **O2** (proto/FE field names still say `schema_fp`) → UI/wire lane, TRACKED.
- **O8** (braked ingest queue is unbounded and silent) → lane C / 885 successor.
- **O9** (`TYPED_CONFIRM` + `X-JustSearch-Session` for `core.rebuild-index` undocumented) →
  dev-tooling lane.
- **O10** (`SearcherManager not available` health-check noise on deferred-open boots, pre-existing)
  → lane C.
- **D4** (`commit_by_reason` never carries `migration/cutover` live) → 912 metrics lane; the
  ≤30-line half that was in files lane D owns (the cutover flush) is done here.
- **`falsify-restore-from-backup`** → postmortem #29 + the `agent-lessons.md` handle list, paid for
  by a trim elsewhere in that file (the byte budget is ratcheted).
