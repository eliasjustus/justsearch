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
      semantics). Each opt-in regenerates schema + TS/Zod and must keep the live
      fixture round-trips green. Deliverable includes the list of records that
      *cannot* opt in, with the reason, appended to the softness portfolio row.
- [ ] **Evidence-index consumption.** `tmp/agent-telemetry/evidence-index.ndjson`
      (session-keyed capture records, 683) is written but unread.
      `outcome-session.mjs` gains an `evidenceFact`: bundles linked to the session,
      pass/fail status — so an outcome can distinguish "merged" from "merged with
      machine-witnessed runtime evidence". Measurement-side only; no new states are
      invented beyond what the artifacts derive.
- [ ] **Settings consumers onto the validated boundary.** `LibrarySurface`,
      `SettingsSurface`, `BrainSurface`, and `themeState` still fetch
      `/api/settings/v2` directly; migrate them onto `getSettingsV2`/`updateSettingsV2`
      (`domains/settings.ts`, strict generated schema). Mechanical; FE suite green.
- [ ] **Post-merge MCP capture check.** `capture_evidence` resolves
      `modules/ui-web/scripts/capture-evidence-bundle.mjs` from the main checkout;
      now that 683 is merged, run one MCP-tool capture against a live dev run and
      confirm validator-OK + `session_id` stamp + index line (683 validated the CLI
      path only).
- [ ] **Release recompose rider (when the next full eval runs).** Not independent
      work: the next `jseval release` compose must carry the new leak/utility
      sections and a pattern-valid `release_id`, retiring the fallback baselines.
      Recorded here so it is not forgotten; executes inside whatever tempdoc drives
      that eval campaign.

## Acceptance

Per item, in-tree: schema-gen + regen checks green after each PreciseWire opt-in;
`outcome-session` unit evidence for the new fact; FE typecheck/tests green after the
consumer migration; one validator-OK MCP capture recorded. No item waits on another;
the doc closes when the first four are done (the fifth transfers to the eval tempdoc
that executes it).

## Out of scope

Liveness/census work (tempdoc 687); any new validation states or review tooling
beyond the derived `evidenceFact`.
