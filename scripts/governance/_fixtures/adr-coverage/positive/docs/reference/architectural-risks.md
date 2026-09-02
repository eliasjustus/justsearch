---
title: Architectural Risk Register (fixture)
type: reference
status: stable
description: "Positive self-test fixture for the adr-coverage risk-instrument rules."
---

# Architectural Risk Register

Fixture register for the `adr-coverage` positive self-test. Both rows below must keep this
flavour at `pass`: one instrument resolves inside the fixture tree, and the other is an
explicit `none - <reason>`, which is a warning and never a failure.

If the section parser regresses (heading level, the `**Instrument:**` field name, the
`## RISK-NNN:` split), the register yields zero sections, `adr-coverage/risk-register-malformed`
fires, and this fixture flips to `fail` — which is the point of keeping a register here at all.

## Entry format

- **Instrument**: one reference; see the real register for the grammar.

## RISK-001: The sample file still says hello

**Category:** maintainability | **Status:** Accepted

**Trade-off:** Fixture row whose instrument resolves against the fixture tree.

**Impact:** None; this is a fixture.

**Reassess when:** Never — fixture.

**Instrument:** `test:sample/foo.txt#hello`

**Owner tempdoc:** none — fixture.

**Last reviewed:** 2026-09-02

## RISK-002: A fixture row with no instrument to name

**Category:** maintainability | **Status:** Monitoring

**Trade-off:** Fixture row exercising the warning-not-failure path.

**Impact:** None; this is a fixture.

**Reassess when:** Never — fixture.

**Instrument:** `none - a fixture row, so there is nothing real to check; this must warn, never fail.`

**Owner tempdoc:** none — fixture.

**Last reviewed:** 2026-09-02
