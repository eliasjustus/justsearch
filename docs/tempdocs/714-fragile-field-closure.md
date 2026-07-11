# 714 — Fragile-field closure: extend the rmwPolicy fail-fast to every field type + dead-field census

- **status:** seed — takeover pending (chartered 2026-07-11 from the 711 close-out; no
  investigation performed yet)
- **created:** 2026-07-11

## Charter question

Close the remaining third of the RMW bug-class that 711 opened: today's startup fail-fast
(`FieldMapper.validateRmwPolicies`) covers vector/splade *types* only — a fragile field of any
other type (text, keyword-without-docValues, future types) can still ship with no declared RMW
disposition and be silently destroyed. What does full closure look like, and which existing
fields are dead and should simply be deleted?

## Evidence that motivates the charter (verified, citable)

- `content_all` (`SSOT/catalogs/fields.v1.json` — type text, stored:false, docValues:false) is
  destroyed by every RMW and declares no policy. It currently has **no production writer**
  (711 audit: only `modules/benchmarks/` writes it — `EngineIndexBench.java:151`,
  `IndexingOverheadProfiler.java:89`), so today it is a latent trap plus a probable dead
  field, not a live bug. Observation logged 2026-07-11 (folded into the conditions store).
- 711's implementation deviation #4 explicitly scoped it out ("outside the design's
  vector/splade fragile scope — flagged, not fixed").
- The class-closure principle (711 §Reach: "declared RMW disposition ... enforced at the
  single write choke point, not by caller discipline") is only enforced for two field types;
  the principle's earning condition ("new fragile fields fail fast at startup until they
  declare") is not yet true for the general case.

## Candidate shape (for the design pass to confirm or replace)

1. Census every non-stored, non-docValues field in the catalog; classify: has production
   writer? derived-at-write? dead?
2. Dead fields (content_all candidate) → delete from catalog + schema + any writer, with the
   ssot-catalog-sync/regenSsotManifest procedure (711 Step 1 is the worked example).
3. Extend `validateRmwPolicies` to ALL field types, with a disposition vocabulary that covers
   the legitimate non-preserve cases (e.g. `recomputed-at-write` for genuinely derived fields)
   so the fail-fast has no permanent exemption list.

## Constraints / relations

- Small, bounded, no retrieval-semantics change expected; regression tests per 711's pattern
  (`RmwFieldPreservationTest`, startup-validation rejection tests).
- Touching `SSOT/catalogs/**` + `adapters-lucene/**` fires ssot-catalog-sync +
  check-language-agnostic-analysis (both procedures documented in 711 §Derisk E2).
- Related: tempdoc 711 (parent), F-032, tempdoc 553 (representation-drift register idiom).
