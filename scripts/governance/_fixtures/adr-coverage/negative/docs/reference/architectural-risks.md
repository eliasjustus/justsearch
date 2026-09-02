---
title: Architectural Risk Register (fixture)
type: reference
status: stable
description: "Negative self-test fixture register for the adr-coverage gate."
---

# Architectural Risk Register

Fixture register for the `adr-coverage` NEGATIVE self-test. It is here so this flavour keeps
failing for the probe rule ONLY (see `docs/decisions/0001-sample.md`): since tempdoc 884
review S3, a gate config that declares a `riskRegister` and does not have one fails with
`adr-coverage/risk-register-missing`, and a negative fixture that failed for two unrelated
reasons would no longer discriminate.

The single row below warns and never fails, so it contributes no failure of its own.

## RISK-001: A fixture row with no instrument to name

**Category:** maintainability | **Status:** Monitoring

**Trade-off:** Fixture row exercising the warning-not-failure path.

**Impact:** None; this is a fixture.

**Reassess when:** Never — fixture.

**Instrument:** `none - a fixture row, so there is nothing real to check; this must warn, never fail.`

**Owner tempdoc:** none — fixture.

**Last reviewed:** 2026-09-02
