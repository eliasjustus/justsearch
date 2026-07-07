---
title: "683 substrate completion: PreciseWire required-fidelity onboarding sweep across the generated wire surfaces, evidence-index consumption in outcome analytics (merged ≠ verified gets its machine-witnessed bit), settings consumers onto the validated boundary, and the post-merge MCP capture-evidence check — bounded mechanical completion of substrate 683 shipped."
type: tempdocs
status: "open — scoped, not started (follow-up B of tempdoc 683; each item carries its own acceptance and none blocks the others)"
created: 2026-07-07
author: agent session (scoped from tempdoc 683's deferred-checks ledger; every item cites the substrate it completes)
category: contracts / verification / analytics
related:
  - 683-wire-contract-and-verification-hardening   # parent — built the substrate these items consume
  - 564-wire-record-schema-pipeline                # the PreciseWire opt-in seam lives in its config
---

# 688 — 683 substrate completion

## Why

Tempdoc 683 hardened the wire boundary and made verification artifacts durable, but
deliberately deferred the consumer-side completions (its §Deferred ledger). Each item
below finishes a consumer for substrate that already exists; none requires new
machinery.

## Work items

- [ ] **PreciseWire onboarding sweep.** Strict unknown-key rejection is global;
      required-field fidelity is per-record opt-in via the `PreciseWire` marker
      (`WireSchemaConfig.isRequiredOnWire`). Walk the generated wire surfaces and
      decide per record whether its fields can honestly be `required` (registry wire
      views already opt in; SettingsV2 deliberately must NOT — nulls carry merge
      semantics). **Evidence rule (hard requirement, learned post-683): a green
      fixture round-trip is NOT sufficient evidence for an opt-in** — fixtures are
      single-sample and a sometimes-null field passes `required` against a fixture
      that happens to carry a value, then fails in dev at runtime. Every opt-in must
      cite emit-site evidence: the producing controller/serializer's null/omission
      behavior (`@JsonInclude(NON_NULL)` sites, nullable paths) per promised field.
      The per-record decision list gets a second-pass sanity check before
      regeneration (orchestrator or reviewer ≠ implementer). Each opt-in regenerates
      schema + TS/Zod and keeps the fixture round-trips green. Deliverable includes
      the list of records that *cannot* opt in, with the reason, appended to the
      softness portfolio row.
- [ ] **Evidence-index consumption.** `tmp/agent-telemetry/evidence-index.ndjson`
      (session-keyed capture records, 683) is written but unread.
      `outcome-session.mjs` gains an `evidenceFact`: bundles linked to the session,
      pass/fail status — so an outcome can distinguish "merged" from "merged with
      machine-witnessed runtime evidence". Measurement-side only; no new states are
      invented beyond what the artifacts derive. **Start-of-work caution:** the
      analytics resolver layer changed 2026-07-07 (env-first session attribution,
      shared resolver) — rebase and re-read `record-merge.mjs`/`outcome-session.mjs`
      before designing the join; do not trust older line citations.
- [ ] **Settings consumers onto the validated boundary.** `LibrarySurface`,
      `SettingsSurface`, `BrainSurface`, and `themeState` still fetch
      `/api/settings/v2` directly; migrate them onto `getSettingsV2`/`updateSettingsV2`
      (`domains/settings.ts`, strict generated schema). Mostly mechanical, one real
      edge: **`themeState` fetches on the early boot path**, and the validated
      boundary THROWS in dev on contract mismatch — the migration changes a boot
      failure mode. Verification must include a fixture-mode boot check
      (`jseval ui-shot home --fixtures` clean) in addition to the FE suite.
- [ ] **Post-merge MCP capture check — now a known small investigation, not a
      checkbox.** First attempt (2026-07-07) failed on two stacked causes: the
      long-running `justsearch-dev` MCP server was stale (predates the 683 merge;
      needs an owner-side MCP reconnect to pick up new source) AND the spawn died
      with a libuv fail-fast assertion (`UV_HANDLE_CLOSING`, exit 3221226505).
      After a reconnect, re-run one MCP-tool capture against a live dev run and
      confirm validator-OK + `session_id` stamp + index line. If the libuv crash
      persists on fresh server code, treat it as a real defect in the
      `cli.mjs` spawn/teardown path and debug there (the direct CLI script path is
      proven working — validator-OK live bundles exist from both 683 and 689).
- [ ] **Census fixture journey (added 2026-07-07 from the 689 adjudication).** The
      only way to witness the declaration-renderer component group (form-control
      primitives, `jf-metric-card`, `jf-table`) is a resource/schema declaration
      fixture the harness currently lacks. Add ONE minimal declaration fixture (a
      resource carrying a schema URL exercising each form-control primitive once)
      reachable in a ui-shot step, so census re-runs can witness the group and
      `GET /api/schemas/{name}` gets journey coverage. Smallest possible fixture —
      this is census enablement, not a form-features showcase. (AI-run census legs
      remain separately blocked by the worktree runtime-activation gap —
      inbox-flagged 2026-07-07 as an UNOWNED blocker after confirming it sits
      outside tempdoc 684's scope.)
- [ ] **Release recompose rider (when the next full eval runs).** Not independent
      work: the next `jseval release` compose must carry the new leak/utility
      sections and a pattern-valid `release_id`, retiring the fallback baselines.
      Recorded here so it is not forgotten; executes inside whatever tempdoc drives
      that eval campaign.

## Acceptance

Per item, in-tree: schema-gen + regen checks green after each PreciseWire opt-in;
`outcome-session` unit evidence for the new fact; FE typecheck/tests green after the
consumer migration; one validator-OK MCP capture recorded. No item waits on another;
the doc closes when all items except the release-recompose rider are done (that
rider transfers to the eval tempdoc that executes it); the fixture-journey item
additionally needs one census-style witness run showing the form-control group
mounted.

## Out of scope

Liveness/census work (tempdoc 689); any new validation states or review tooling
beyond the derived `evidenceFact`.
