---
title: "524 — `core-contracts` C-018 audit"
---

# 524 — `core-contracts` C-018 audit

*Renumbered from 519 on 2026-05-18 (per `docs/tempdocs/README.md`
§"Renumbering procedure") to free 519 for `head-composition-graph`.
Prose citations using the slug `core-contracts-c018-audit` continue
to resolve.*

**Date**: 2026-05-18
**Status**: done
**Source**: requested as the follow-on action from tempdoc 512 §B2
("substrate-without-consumer candidates").
**Related**: `.claude/rules/agent-lessons.md` §"Substrate discipline"
(the C-018 rule and its §X.11 refinement); `docs/reference/contributing/slice-execution.md`
(Pass 6/7/8 verification protocol); tempdoc 400 §22 Issue A (the
LR6-a refactor that motivated `core-contracts`); tempdoc 402 P2/P5
(the BootContractRunner + ContractEmitter additions).

---

## What was asked

Tempdoc 512 §B2 identified three types in `modules/core-contracts`
as having "0 consumers outside the module" via a Java-source grep:

- `ContractSampler`
- `BootContractRegistry`
- `BootContractValidator`

The proposed action: apply the C-018 ("substrate-without-consumer")
rule. Either confirm the zero-callsite finding and delete, or surface
a legitimate use that justifies retention.

## Methodology

The initial grep was too narrow. A fuller audit needs to check:

1. Internal module use (does the suspect type have callers inside its
   own module, even if not outside?)
2. Reflection / ServiceLoader / config-file discovery (consumers via
   path-string mechanisms invisible to typed-import grep)
3. Non-Java consumers (Python projections, doc references, SSOT entries)
4. Test fixtures (do tests demonstrate intended use even when
   production placement is absent?)
5. Annotation usage (are annotation types consumed at use-sites that
   don't import the annotation directly?)
6. Production callsites by method invocation, not just by type
   reference (e.g., `ClassName.staticMethod(...)` form rather than
   field-of-type-ClassName form)

The audit performed all six. Findings below.

## Findings per type

### `ContractSampler`

**Static type references:** 4 files reference the type — 1 test
(`ContractSamplerTest`), 3 production cousins inside the module
(`ContractEmitter.java`, `SampleContract.java`, `SampleKey.java`).
Each cousin references via JavaDoc example, not method invocation.

**Method invocations** (`ContractSampler.shouldSample` /
`ContractSampler.reset`): **only the in-module test file**
(`ContractSamplerTest`). Zero production callsites of
`shouldSample` exist anywhere in the codebase.

**JavaDoc intent:** the class explicitly documents anticipated
production placement:
> "Microbenchmark before placing a sample site in a hot inner loop
> (per-token, per-doc, per-index-entry)."

The pattern is **substrate-prepared-for-future-placement** — a
sample-gate primitive ready to be installed in encoder inner loops
when the placement work is done. Tempdoc 402 §3.3 is the design
record.

**`@SampleContract` annotation:** 3 usages, all test-only.

**Verdict: NOT a C-018 violation.** The substrate has internal
consumers (the module's own cousin types) and explicit deferred
production placement documented in its JavaDoc.

### `BootContractValidator`

**Static type references:** the interface is referenced by 6 files
inside `core-contracts` (BootContract, BootContractAudit,
BootContractRegistry, BootContractRunner, ContractViolation, and the
test) plus is the SPI for ServiceLoader discovery via the
`META-INF/services/io.justsearch.contracts.BootContractValidator`
file convention.

**Production implementations** (`implements BootContractValidator`):
**zero outside of `BootContractTest.java`'s 5 test fixtures**
(`UnregisteredValidator`, `PassingValidator`, `FailingValidator`,
`ThrowingValidator`, `ServiceLoaderProbeValidator`).

**`META-INF/services/io.justsearch.contracts.BootContractValidator`
file:** present only in `src/test/resources/` (test fixture). No
production-classpath entry exists.

**Runtime effect:** `BootContractRunner.validateAll()` is called at
`IndexerWorker.java:99` (Worker boot). At runtime, ServiceLoader
finds zero implementations on the production classpath and the
runner exits without producing violations. **The validation pass is
currently a no-op in production.**

**Verdict: NOT a C-018 violation.** The type is consumed by
`BootContractRegistry` and `BootContractRunner` inside the module,
and is the SPI surface for future production validators. The
**runtime no-op state is substrate-prepared-without-placement**, not
substrate-without-consumer.

### `BootContractRegistry`

**Static type references:** consumed by 4 files inside `core-contracts`
(BootContract, BootContractAudit, BootContractRunner, BootContractTest).

**Method calls** (`BootContractRegistry.reset` /
`BootContractRegistry.validators`): only in-module tests use these
methods directly; production uses indirect via
`BootContractRunner.validateAll()` and `findViolations(List)`.

**Verdict: NOT a C-018 violation.** Consumed by sibling types within
the module; serves as the ServiceLoader discovery surface for
`BootContractValidator`.

## Cross-cutting findings

### Production callsite that DID exist (grep miss in tempdoc 512)

```
modules/indexer-worker/.../IndexerWorker.java:99 — BootContractRunner.validateAll();
```

This is the production callsite that the initial type-name grep
missed (the type appears as a `static.method()` form). The Worker
process **does** call into the contract-governance chain at boot.
The chain runs; finds no validators; returns. Behaviorally a no-op
today, but the consumer wiring exists.

### Annotation-based callers outside the module

`@BuildContract` is applied at production sites:
- `modules/ort-common/src/main/java/io/justsearch/ort/NativeSessionHandle.java` (1 usage)
- `modules/telemetry/src/test/java/io/justsearch/telemetry/TracingLocalExportTest.java` (2 usages, test)
- Others not enumerated; total 47 occurrences across 16 files.

So the annotations themselves (`@BuildContract`, `@AdvisoryContract`,
`@SampleContract`, `@BootContract`) are real consumers — they're
applied at production use-sites without importing the runtime types.
This is another reason the initial grep undercounted.

### Python projection consumer

Per `modules/core-contracts/build.gradle.kts`:
> "Tempdoc 402 P5: `ContractEmitter` uses `io.opentelemetry.api.trace.Span#addEvent`
> to emit `contract.violation` events consumed by the Python projection
> `scripts/jseval/jseval/projections/contract_violations.py`."

The Python projection is a non-Java consumer that the Java grep
fundamentally cannot see. ContractEmitter's output flows: OTel
span event → NDJSON exporter → Python projection. This is the kind
of cross-language consumer that breaks typed-import audits.

## Verdict (overall)

**No deletions warranted.** All three suspect types have legitimate
consumers when the audit method is broadened beyond
single-language-typed-import:

- `ContractSampler` — internal cousin types; deferred production
  placement per JavaDoc + tempdoc 402 §3.3.
- `BootContractValidator` — sibling types in module; ServiceLoader
  SPI surface; called transitively via `BootContractRunner.validateAll`
  at `IndexerWorker` boot.
- `BootContractRegistry` — sibling types in module; ServiceLoader
  discovery surface for `BootContractValidator`.

The §X.11 refinement of C-018 from `agent-lessons.md` explicitly
covers this case:
> "C-018 governs new substrate slots that nobody reads, NOT
> type-system refactors where every existing callsite is already a
> consumer. Conflating 'no NEW consumer' with 'no consumer at all'
> caused [...] full existing consumer surface from day one."

The tempdoc 512 §B2 finding was an over-strict application of C-018
based on too narrow a grep. It is corrected by this audit.

## Secondary finding (not a deletion, worth flagging)

The BootContract chain is currently a **runtime no-op in production**:

- `IndexerWorker.java:99` calls `BootContractRunner.validateAll()`
- `BootContractRunner` asks `BootContractRegistry` for validators
- `BootContractRegistry` uses `ServiceLoader<BootContractValidator>`
- No production `META-INF/services/io.justsearch.contracts.BootContractValidator`
  file exists; no production class implements `BootContractValidator`
- The call returns `0 violations`; the Worker boots normally

This is the **expected** state per tempdoc 400 LR6-a / tempdoc 402's
deferred-placement design — the substrate exists *to be populated
by future validators*. The chain is wired and waiting.

The same is true for `ContractSampler` — the sample-gate primitive
is wired but no production callsites place sample points yet.

**This is not a C-018 violation but it is information the project
should track.** If, six months from now, no validators have been
registered and no sample sites have been placed, the substrate
becomes a candidate for re-evaluation: is the deferred placement
still planned, or has the design pivoted in a way that makes the
substrate orphaned?

Recommendation: file an observations.md entry that captures this
as a "watch this surface" item rather than a "delete this" item.
Tempdoc 519 (this audit) is the rationale.

### Corroborating evidence (added after audit body)

Two pieces of evidence confirm the runtime no-op is the *intended*
interim state, not accidental:

1. **`modules/indexer-worker/build.gradle.kts:20`** documents the
   wiring explicitly:
   > "Runtime-scope: IndexerWorker.main calls
   > BootContractRunner.validateAll() (tempdoc 402 P3)"

   The boot-time validation chain is codified in the build script,
   not just inferred from a `main()` body.

2. **`scripts/jseval/jseval/projections/contract_violations.py`
   docstring (tempdoc 400 LR6-c) states the deferred-placement
   contract directly**:
   > "Until the deferred contract tiers land (`@SampleContract` +
   > `@BootContract`), no `contract.violation` events are emitted
   > in production and the projection returns an empty aggregate.
   > **That is the intended shape**: when the runtime tiers ship,
   > the projection starts returning non-empty data with zero
   > downstream changes."

   The Python consumer is forward-compatible by design: empty
   aggregate today, populated aggregate when placement lands, zero
   downstream changes either way.

This is *direct, primary-source evidence* that the
substrate-prepared-without-placement framing is the system's own
stated intent — not the audit's reframe. The C-018 §X.11 refinement
is doing its job by ruling out exactly this kind of false-positive
deletion.

### Bundle/distribution note

The `core-contracts-2.0.0-alpha.27-SNAPSHOT.jar` is bundled in the
installer payload (`modules/shell/src-tauri/resources/headless/lib/`).
The module ships to end users even though most of its runtime work
is currently no-op. This is consistent with the deferred-placement
design — the substrate must be present at install time so that
*later* placements can activate without re-installing.

## Methodology lessons for future C-018 audits

For the next agent running a similar audit, the audit method that
nearly produced a false-positive deletion has these failure modes
to guard against:

1. **Typed-import grep undercounts ServiceLoader-discovered
   consumers.** Always check `META-INF/services/<fqn>` files
   alongside type-reference greps.
2. **Annotation types' "consumers" are the use-sites, not import
   sites.** `@BuildContract` is applied at sites that may not
   import `io.justsearch.contracts.BuildContract` directly (because
   annotation types can be auto-imported by the IDE or used
   fully-qualified).
3. **Internal-module callers count.** A type with zero outside
   callers but six in-module callers is *not* substrate-without-
   consumer; it's a module-internal helper. C-018 is about substrate
   slots without readers, not module-internal infrastructure.
4. **Cross-language consumers are invisible.** Python projections,
   NDJSON exporters, generated docs — none of these show in a Java
   grep. Read the module's `build.gradle.kts` and any associated
   tempdoc design docs for the full consumer list.
5. **Method-invocation grep ≠ type-reference grep.** A type may be
   referenced by JavaDoc / field type / parameter type while the
   actual `.method()` call surface is different. Both need checking.
6. **Substrate-prepared-without-placement is a distinct shape from
   substrate-without-consumer.** The former has wired consumers but
   zero populators; the latter has no consumers at all. Only the
   second is C-018.

## What this audit produced

- **No code deletions.** No files removed; no module trimmed.
- **One correction to tempdoc 512** (B2 / D4 / Phase 2 synthesis
  references to "core-contracts has 3 zero-consumer types"). The
  correction will be applied to tempdoc 512 in a follow-up edit.
- **One observation flagged** for `docs/observations.md`: the
  BootContract chain + ContractSampler are runtime-inert today;
  worth tracking whether placement materializes.
- **Methodology notes** above for future C-018 audits to avoid the
  same false-positive shape.

The §X.11 refinement to C-018 in `agent-lessons.md` is doing real
work: it explicitly named this category of false positive and
provided the distinguishing test ("if you delete one endpoint,
does the cross-reference become orphaned or disappear with its
source?"). For these three types, the cross-references disappear
with their source — they're internal infrastructure, not orphaned
slots.

## What this audit is *not*

- It is not a verdict on whether the BootContract / SampleContract
  substrate will eventually earn out. The substrate may produce
  value when validators and sample sites are placed, or it may
  remain inert. This audit measures *today's* consumer state and
  finds it consistent with deferred-placement design, not C-018.
- It is not a critique of the original tempdoc 512 §B2 finding —
  the finding correctly identified a surface worth auditing. The
  audit then refined the conclusion.
- It is not authorization to retire C-018 or the §X.11 refinement.
  Both are necessary; the refinement is what made this audit
  resolve correctly.
