---
title: "402 — Layer 6 tier completion + invariant audit expansion"
---

# 402 — Layer 6 tier completion + invariant audit expansion

## 0. Status

**In progress.** Phases P1–P6 shipped; P7–P8 remaining. See §8
Progress log for deviations from plan.

This tempdoc completes the Layer 6 work deferred by tempdoc 400
Phase 1. It is purely additive: the two shipped tiers
(`@BuildContract`, `@AdvisoryContract`) stay as they are; this doc
scopes the two deferred tiers (`@BootContract`, `@SampleContract`)
plus the invariant-audit expansion that was prevented by the
`ort-common` module-dep gap.

**Non-triggers — preserved as anti-pattern discipline:**

- "More invariant tags would be nice" — every new annotation must
  name a specific invariant traceable to a tempdoc rationale.
- "Runtime assertions everywhere" — explicitly the failure mode
  tempdoc 400 §15 preventions warned against. Tier every invariant
  deliberately; not every method needs a contract.
- "Auto-migrate existing @AdvisoryContract → @SampleContract" —
  they are different contracts. Advisory is log-only; Sample is
  runtime-sampled assertion. Re-tag case by case.

**Created:** 2026-04-22.
**Depends on:** tempdoc 400 §23.6 gap analysis.

---

## 1. Context

Tempdoc 400 §8.6 designed a 4-tier contract taxonomy:

- `@BuildContract` — ArchUnit-enforced; blocks merges on violation.
- `@BootContract` — composition-root verified at startup; fails
  startup on violation.
- `@SampleContract(every = N)` — runtime-sampled assertion; emits
  `contract.violation` span event on failure.
- `@AdvisoryContract` — log-only; feeds drift detection.

Phase 1 shipped 2 of 4 (`@BuildContract`, `@AdvisoryContract`). The
two runtime-enforcement tiers were deferred because they required
"runtime-harness infrastructure with no codebase precedent" (§8.6
scope-reduction). Phase 5 refactored the annotations into
`modules/core-contracts` so they could reach more modules, but only
one live usage exists today (`SessionPoliciesController` with
`@AdvisoryContract`). Zero `@BuildContract` usages are live.

Tempdoc 400 §23.6 flagged this gap as "two of four tiers missing, no
follow-up tempdoc tracks the remaining work." The retrospective §15
prevention rule "claim-vs-code drift" is **partially held** because
the runtime-enforcement tiers are absent — invariants that need
runtime enforcement fall back to source comments.

This tempdoc closes that gap.

---

## 2. Current state inventory

### 2.1 Live annotation usage

| Annotation | Usage count | Sites |
|---|---|---|
| `@BuildContract` | 0 | — |
| `@AdvisoryContract` | 1 | `SessionPoliciesController` (signal: `session.policies.worker_unreachable`) |
| `@BootContract` | 0 | not defined |
| `@SampleContract` | 0 | not defined |

### 2.2 Unannotated invariants (candidates for tier-assignment)

Surveyed from tempdocs 397 §14.25–§14.28 + tempdoc 400 Phase 2/6:

| Site | Invariant | Today | Target tier |
|---|---|---|---|
| `InferenceCompositionRoot.compose` | §7.6 — single entry for all encoder construction | docstring comment | `@BuildContract` (ArchUnit can verify no other construction site) |
| `NativeSessionHandle` package-private Builder | §14.27 / §14.28 U1 — one construction path | source comment | `@BuildContract` (ArchUnit on `<init>` call-sites; currently blocked by `ort-common → core-contracts` dep gap — see §3.1) |
| `ClosurePropertyTest` | §7.5 — encoder closure property over I/O packages | ArchUnit test itself | self-enforcing; no annotation needed |
| `SessionPoliciesController` late-bind volatile field | §14.28 U4 — Worker RPC client late-bound | already `@AdvisoryContract` | unchanged |
| `LocalApiServer.lateBindKnowledgeServer` hand-maintained controller list | Phase 2.1 learning | none | `@BuildContract` (reflection-based check: every `*Controller` with a nullable client must be in the list) |
| `SearchOrchestrator.setActiveGenerationSupplier` wiring from `DefaultWorkerAppServices` | Phase 6 / 6.7 supplier resolution | none | `@BootContract` (fail startup if supplier is null after composition) |
| Projection registry bootstrap | LR4-a / Phase 3 — registry must be re-populated on test reset | behavioral invariant in `projections/__init__.py` | `@BuildContract` (Python-side; see §5 cross-language note) |

Estimated distribution after audit:
- `@BuildContract` ~4-6 sites
- `@BootContract` ~3-4 sites
- `@AdvisoryContract` ~2-4 sites
- `@SampleContract` ~2-3 sites (each must justify its sample rate
  against a documented cost budget)

---

## 3. Design decisions (locked)

Each decision addresses one blocker. All locked before implementation
begins; no re-litigation during commit sequence.

### 3.1 Module-dep resolution for `ort-common`

**Decision:** `ort-common` adds `implementation(project(":modules:
core-contracts"))`. **Zero circularity risk** because
`core-contracts` is dep-free (verified against its build.gradle.kts).
Agent-investigation hypothesis about circularity was speculation
that wasn't tested. Adding the dep is a 1-line change.

**Scope:** 1 LOC in `modules/ort-common/build.gradle.kts`. Unlocks
annotating `NativeSessionHandle` + every other `ort-common` site
that should carry a contract marker.

### 3.2 `@BootContract` mechanism

**Decision:** Validator registry + `BootContractRunner.validateAll()`
invoked at composition root startup. Fail-fast on first violation.

**Shape:**

```java
// Annotation
@Retention(RUNTIME)
@Target({TYPE, METHOD, CONSTRUCTOR})
public @interface BootContract {
  String description();
  String tempdoc();  // e.g. "397 §14.27 U1"
  String validator();  // fully-qualified validator class name
}

// Validator interface
public interface BootContractValidator {
  void validate() throws ContractViolation;
}

// Registry (static init + classpath scan)
public final class BootContractRegistry {
  static void registerAll();  // scans core-contracts-annotated sites
  static List<BootContractValidator> validators();
}

// Runner (invoked from HeadlessApp.main + IndexerWorker.main)
public final class BootContractRunner {
  public static void validateAll() {
    for (var v : BootContractRegistry.validators()) {
      try { v.validate(); }
      catch (ContractViolation e) {
        log.fatal("Boot contract violation: {}", e); System.exit(1);
      }
    }
  }
}
```

**Rationale:**
- Fail-fast because partial-violation startup is worse than no
  startup. An invariant that fails quietly at boot sets up a
  downstream runtime failure whose root cause is untraceable.
- Validator registry (not annotation processor) because the codebase
  has no annotation processor infrastructure. ArchUnit-style
  reflection at registration time matches `ClosurePropertyTest`'s
  pattern.
- One entry point per application (HeadlessApp.main +
  IndexerWorker.main) that calls `BootContractRunner.validateAll()`
  before any service starts.

**Rejected alternative:** AspectJ/bytecode weaving. Too heavy; no
precedent in codebase.

### 3.3 `@SampleContract` mechanism

**Decision:** Manual call-site invocation with atomic sample counter.
Span-event emitter shape matches
`contract_violations.py` expectations.

**Shape:**

```java
// Annotation
@Retention(RUNTIME)
@Target({METHOD, CONSTRUCTOR})
public @interface SampleContract {
  String description();
  String tempdoc();
  int every() default 1000;  // sample rate
  String validator();
}

// Call site (explicit, no AspectJ magic)
if (ContractSampler.shouldSample(SampleKey.ENCODER_LEASE_HELD)) {
  if (!lease.isHeld()) {
    ContractEmitter.emit(
        "encoder.lease.held",
        "400 LR2-b",
        "Lease not held on ORT session.run");
  }
}
```

**ContractSampler shape:**
```java
public final class ContractSampler {
  private static final ConcurrentMap<SampleKey, AtomicLong> counters
      = new ConcurrentHashMap<>();

  public static boolean shouldSample(SampleKey key) {
    int every = key.sampleRate();
    return counters.computeIfAbsent(key, k -> new AtomicLong())
        .incrementAndGet() % every == 0;
  }
}
```

**Emitter shape** (must match consumer in
`projections/contract_violations.py` L22-30):
```java
public final class ContractEmitter {
  public static void emit(String tier, String tempdoc, String desc) {
    Span.current().addEvent("contract.violation",
        Attributes.of(
            AttributeKey.stringKey("contract.tempdoc"), tempdoc,
            AttributeKey.stringKey("contract.tier"), tier,
            AttributeKey.stringKey("contract.description"), desc));
  }
}
```

**Allowlist additions** (to `NdjsonSpanExporter.ALLOWED_ATTRS`):
`contract.tempdoc`, `contract.tier`, `contract.description`.

**Rationale:**
- Manual call-site invocation mirrors the `maybeSpan()` pattern
  already used for tracing gates. Per-call cost: one atomic
  increment (~2-5 ns uncontended).
- Atomic counter vs probabilistic sampling: deterministic, easier
  to reason about in tests, and the integer rate parameter gives
  operators a direct cost knob.
- Event shape is consumer-locked — `contract_violations.py` already
  reads these three attrs; the emitter must match or the projection
  silently drops the events (D-1 class).

**Rejected alternatives:**
- AspectJ wrapping (same rejection as §3.2).
- Annotation processor generating wrapper methods (heavy infra;
  no precedent).
- Probabilistic sampling (harder to reason about for deterministic
  gates).

### 3.4 Invariant audit scope

**Decision:** The audit expansion is scoped to the table in §2.2,
ordered by risk:

1. **High (fail-loud required):** `InferenceCompositionRoot.compose`,
   `NativeSessionHandle` construction — these invariants are
   referenced in multiple tempdocs; violation means a whole class
   of bugs.
2. **Medium (log-loud sufficient):** `SearchOrchestrator.
   setActiveGenerationSupplier` wiring, `LocalApiServer.
   lateBindKnowledgeServer` controller list.
3. **Low (advisory only):** projection registry bootstrap
   (Python-side; cross-language note in §5).

Each annotation target carries a one-line tempdoc reference so the
rationale survives refactors.

**Out of scope:**
- Retroactive tier-assignment to every method in the codebase. The
  §2.2 table is exhaustive for tempdoc 400's invariant inventory.
  Future tempdocs add rows.
- Migration of existing `@AdvisoryContract` usages. They stay;
  Sample is additive.
- A new projection for boot-contract failures. Boot violations
  fail-fast, so there is no stream to aggregate.

---

## 4. Implementation sequencing

Sketch only — full §14-style sequencing happens when this tempdoc
graduates to an implementation plan.

| Phase | Status | Delta | Gate |
|---|---|---|---|
| P1 | ✓ `483c30498` | Add `ort-common → core-contracts` dep | ort-common compiles; `./gradlew build -x test` green |
| P2 | ✓ `1b4e10a22` + `caf50ce13` | Define `@BootContract` + `BootContractValidator` + `BootContractRegistry` + `BootContractRunner` in `core-contracts` | `modules:core-contracts:test` green |
| P3 | ✓ (uncommitted) | Wire `BootContractRunner.validateAll()` into `HeadlessApp.main` + `IndexerWorker.main` | headless-eval + knowledge-worker start cleanly |
| P4 | ✓ (uncommitted) | Annotate `InferenceCompositionRoot.compose` (pre-existing) + `NativeSessionHandle.Builder` with `@BuildContract` | ArchUnit rule + boot validator green on dev stack |
| P5 | ✓ (uncommitted) | Define `@SampleContract` + `SampleKey` + `ContractSampler` + `ContractEmitter` in `core-contracts` | unit tests green; `contract.violation` event shape matches projection consumer |
| P6 | ✓ (uncommitted) | Extend `NdjsonSpanExporter` to emit span events + allowlists (`ALLOWED_EVENT_NAMES`, `ALLOWED_EVENT_ATTRS`) | tracing contract test round-trips the 3 attrs + drops unknown event names |
| P7 | pending | Annotate high-priority sites from §2.2 (Boot + Build + Sample tiers) | `jseval` live smoke emits `contract.violation` events on healthy run (zero); injected test violation surfaces in `contract_violations` projection |
| P8 | pending | LR6-c projection + nightly gate regen | projection consumed on nightly workflow; schema unchanged |

**Estimated scope:** ~800-1200 LOC across `core-contracts`,
`app-launcher`, `ort-common`, `worker-services`, telemetry
allowlist, 2 unit test classes, 1 contract test, 1 projection
integration test.

---

## 5. Verification gates

**P1:** `./gradlew.bat :modules:ort-common:compileJava` green —
verifies the dep add didn't introduce circularity.

**P2-P3:** `./gradlew.bat :modules:core-contracts:test` green +
headless-eval + Worker both start cleanly with
`BootContractRunner.validateAll()` wired.

**P4:** ArchUnit rule for `@BuildContract`-annotated construction
sites catches deliberately-planted violation in a unit test.

**P5-P7:** `pytest scripts/jseval/tests/
test_projection_contract_violations.py` green against synthetic
`contract.violation` span events + live smoke emits zero on healthy
run.

**P8:** Nightly gate workflow's projection roster includes
`contract_violations` in the required-projections-present check.

**Cross-language note (§4 projection registry bootstrap):** the
Python-side projection bootstrap in `scripts/jseval/jseval/
projections/__init__.py` has an invariant (registry re-population
on test reset) that can't be tagged with a Java annotation. This
tempdoc does NOT cross the language boundary — the Python invariant
is tracked separately in the rate_timeline-style contract tests
added in tempdoc 400 §23.8 follow-up Workstream C. Listed in §2.2
for completeness; no annotation action.

---

## 6. Non-goals (explicit)

- Not a migration of existing `@AdvisoryContract` usage.
- Not retroactive coverage of every method in the codebase.
- Not a GC/retention policy for `contract.violation` span events —
  that's tempdoc 403 scope.
- Not a UI surface for contract violations. The projection + nightly
  gate are the operator interface. (Frontend integration was
  explicitly out of scope in tempdoc 400 §16.)
- Not an ADR. The design decisions in §3 are scoped to this
  feature; if they prove load-bearing across future tempdocs, an
  ADR can capture them later.

---

## 7. References

- **Tempdoc 400 §8.6 LR6-a/b/c/d** — original tier taxonomy design.
- **Tempdoc 400 §22.1 Issue A** — the `ort-common` module-dep claim
  this tempdoc resolves in §3.1.
- **Tempdoc 400 §23.6** — retrospective gap analysis that
  identified this as the Layer 6 follow-up.
- **Tempdoc 400 §30.1 (Phase 6 / 6.1)** — the
  `projections/_errors.ndjson` pattern that this tempdoc's
  `@SampleContract` emitter complements (projection failures
  already flow into contract_violations; this tempdoc adds the
  runtime side).
- **Tempdoc 397 §14.25–§14.28** — invariant sources for §2.2 audit.
- **`modules/core-contracts/src/main/java/io/justsearch/contracts/`**
  — where the new annotations land (dep-free leaf module).
- **`scripts/jseval/jseval/projections/contract_violations.py`** —
  consumer of the `contract.violation` span-event shape defined in
  §3.3.
- **`modules/app-launcher/src/test/java/io/justsearch/app/launcher/
  ClosurePropertyTest.java`** — reference pattern for the
  ArchUnit-style reflection walk used by `BootContractRegistry`.
- **`modules/ort-common/src/main/java/io/justsearch/ort/
  NativeSessionHandle.java`** — primary site unblocked by §3.1.

---

## 8. Progress log

### P1 — ort-common → core-contracts dep (commit `483c30498`)

Shipped. Added `compileOnly(project(":modules:core-contracts"))` to
`modules/ort-common/build.gradle.kts`. Unlocked §2.2 annotation
sites in ort-common. Zero-circularity design held.

### P2 — @BootContract machinery (commits `1b4e10a22` + `caf50ce13`)

Shipped. The initial commit added `BootContract` annotation,
`BootContractValidator` SPI, `BootContractRegistry` (ServiceLoader),
`BootContractRunner.validateAll()` with a static-mutable exit-action
seam. The follow-up commit closed two silent-failure holes in the
machinery — exactly the defect class this tempdoc exists to prevent:

- `@BootContract.validator()` was dead documentation — runtime
  dispatched through ServiceLoader, not the string. A typo'd FQN or
  missing `META-INF/services` entry would have left the invariant
  silently unchecked. **Closed:** added `BootContractAudit.
  auditAnnotatedClass(Class<?>)` — every module that adds a
  `@BootContract` should include a one-line audit test.
- Empty-registry no-op was invisible. A classpath regression that
  dropped every SPI file would have failed silently. **Closed:**
  runner now emits an INFO log line `"BootContractRunner: N
  validators registered, M violations"` on every invocation.

Also removed the static-mutable exit-action seam in favor of a pure
`findViolations()` returning `List<ContractViolation>` plus a thin
`validateAll()` that logs + `System.exit(1)` on violations. Tests
now inspect the returned list directly instead of observing
`exited[0]` flags.

### P3 — wire BootContractRunner into both main()s (uncommitted)

Shipped. Call sites:

- `HeadlessApp.java:357` — after the `"Local API Server started on
  port {}"` log, before `latch.await()`.
- `IndexerWorker.java:96-98` — between `serverFactory.create(config)`
  and `server.start()`.

**Plan defect caught by integration tests:** the implementation plan
said "No `build.gradle.kts` edits." That was wrong — both
`modules/ui/build.gradle.kts` and
`modules/indexer-worker/build.gradle.kts` had
`compileOnly(project(":modules:core-contracts"))`, which kept
`BootContractRunner` off the runtime classpath. On first test run,
the Worker process crashed with exit code 1 before writing its port
(`NoClassDefFoundError` on `BootContractRunner`). Fix: promoted both
to `implementation`. Lockfile regen was CRLF-only (no new transitive
deps).

**Lesson for P7:** when wiring a runtime call to a `core-contracts`
class, audit the runtime classpath for every module the call lives
in. Don't infer from "compileOnly is already present" that a runtime
call will work.

**HeadlessApp race — logged for P7:** `validateAll()` in
`HeadlessApp.main` runs AFTER the API server is already bound and
serving (the `LocalApiServer.builder(...).build()` at line 289 binds
the socket; `validateAll()` is at line 357). For the current empty
validator set this has zero effect. When P7 adds real Boot
validators, a validation failure could race with early client
requests — the JVM exits but the port was already accepting. Two
resolution options:

- Move `validateAll()` earlier, before `LocalApiServer.builder(...).
  build()`. Tension: earlier placement means validators can't
  observe the late-bound knowledge server state (which is one of
  the §2.2 Boot-tier invariants).
- Introduce a two-phase API server (bind → activate) so
  `validateAll()` can run between bind and request-serving.

Decision deferred to P7 when the first real Boot validator lands
and forces the choice.

### P4 — @BuildContract on NativeSessionHandle.Builder (uncommitted)

Shipped. Also noted during planning that the plan's second P4 target
(`InferenceCompositionRoot.compose`) was already annotated in an
earlier tempdoc (`InferenceCompositionRoot.java:92-99`), so P4
effectively just added the Builder annotation. Co-located enforcer
test `NativeSessionHandleBuilderVisibilityTest` pins package-private
visibility via reflection — a future widening (`public Builder`)
fails the test, not just code review.

**Meta-observation — `@BuildContract.enforcer` is a string pointer
with no audit.** The same silent-failure class that P2's follow-up
closed for `@BootContract` still applies to `@BuildContract`: the
`enforcer` attribute can name a JUnit test that doesn't exist (or
that was renamed/deleted) and nothing catches it. `InferenceCompositionRoot.compose`'s annotation names
`enforcer = "ClosurePropertyTest + InferenceSurfaceTest"` — the
first exists; the second is not verified by current audit
machinery. **Deferred:** a `BuildContractAudit.auditAnnotatedClass()`
mirroring `BootContractAudit` should land alongside P7, where the
new annotation sites would otherwise multiply the silent-failure
surface. Out of scope for P3/P4 to avoid plan creep.

### P5 — @SampleContract machinery (uncommitted)

Shipped. Four new main-source classes + three test files in
`modules/core-contracts/`:

- **`SampleContract.java`** — annotation (`description`, `tempdoc`,
  `every=1000`, `validator=""`). Targets METHOD + CONSTRUCTOR only
  (unlike `@BootContract` which also targets TYPE — sample sites are
  per-invocation by construction).
- **`SampleKey.java`** — immutable record `(name, sampleRate)`.
  Constructor rejects `null` name and `sampleRate < 1` so
  misconfigured sites fail at class-load, not at first invocation.
- **`ContractSampler.java`** — `ConcurrentMap<SampleKey, AtomicLong>`
  counter + `shouldSample(SampleKey)` gate. Package-private
  `reset()` for test isolation; production code must never reset
  (would perturb sample phase for live traffic).
- **`ContractEmitter.java`** — attaches a `contract.violation` event
  to `Span.current()` with three `AttributeKey<String>` constants
  (`TEMPDOC_KEY`, `TIER_KEY`, `DESCRIPTION_KEY`). The event name and
  attribute keys are **consumer-locked** against
  `scripts/jseval/jseval/projections/contract_violations.py` —
  asserted in `ContractEmitterTest.eventNameAndAttrKeysMatchConsumerLock`.

**Build changes:**
- `modules/core-contracts/build.gradle.kts` — promoted
  `libs.opentelemetry.api` from no-dep to main-source
  `implementation`. Updated header to "Pure JDK + SLF4J + OTel API".
  Added `libs.opentelemetry.sdk` + `libs.opentelemetry.sdk.testing`
  as `testImplementation` so `ContractEmitterTest` can use
  `InMemorySpanExporter`.
- `gradle/libs.versions.toml` — new `opentelemetry-sdk-testing` alias.
- `gradle/verification-metadata.xml` — SHA256 entries for the new
  `opentelemetry-sdk-testing-1.60.1.jar` + `.module` artifacts.
- `modules/core-contracts/gradle.lockfile` — regenerated.

**Plan defect caught during implementation:** the plan didn't mention
`verification-metadata.xml`. Introducing a new artifact (not just a
version-ref alias to an existing dep) requires adding SHA256 hashes,
not just a lockfile regen. Fix: ran `./gradlew
--write-verification-metadata sha256` and retried the lockfile
regen. Same class of plan-oversight as the P3 `compileOnly` trap —
a dep-system constraint that's only visible when the dep resolution
actually runs.

**Tests** (15 new cases total):
- `SampleContractTest` — 3 cases: attribute round-trip, default
  values (`every=1000`, empty `validator`), runtime retention +
  target check.
- `ContractSamplerTest` — 7 cases: Nth-call firing pattern,
  rate-1 fires-every-time, separate keys don't cross-contaminate,
  reset clears counters, zero/negative/null-name rejection, and a
  4-thread × 1000-iteration concurrent correctness check asserting
  **exactly** 400 fires (atomic counter is deterministic, not
  probabilistic — matches tempdoc §3.3 "easier to reason about in
  tests" rationale).
- `ContractEmitterTest` — 5 cases: event attaches with correct
  shape, no-op on missing span context, no-op when span started
  but not `makeCurrent`'d (documented edge case, pinned), empty
  strings accepted, consumer-lock literal-string assertions.

### P6 — NDJSON event export (uncommitted)

Shipped. **Significant scope growth vs the original tempdoc §4
entry**, which said "Extend `NdjsonSpanExporter.ALLOWED_ATTRS` with
`contract.*`" — a one-line edit.

**The tempdoc was wrong.** `NdjsonSpanExporter.export()` did not emit
span events at all. The output JSON had `trace_id`, `span_id`,
`parent_span_id`, `name`, `start`, `end`, `duration_ms`, `status`,
`attrs` — but no `events` array. Meanwhile
`contract_violations.py:104` iterates `span.get("events")` expecting
`[{"name":"contract.violation","attrs":{...}}]`. The emitter→consumer
path was dead by design until P6 — adding `contract.*` to
`ALLOWED_ATTRS` would have filtered nothing because there was
nothing to filter. Same plan-defect pattern as P3
(`compileOnly` → `implementation`) and P5
(`verification-metadata.xml`): an assumption in the tempdoc that
only survives until the code is actually read.

**What P6 shipped:**
- `NdjsonSpanExporter.java` — new `ALLOWED_EVENT_NAMES =
  Set.of("contract.violation")` + `ALLOWED_EVENT_ATTRS =
  Set.of("contract.tempdoc", "contract.tier",
  "contract.description")`. `export()` now iterates
  `sd.getEvents()`, filters by event name, filters event attrs by
  key, and emits `"events":[…]` in the NDJSON line. Empty array is
  always present for schema stability.
- Separate event allowlists (not reuse of `ALLOWED_ATTRS`) so
  event-schema evolution stays independent of span-schema
  evolution. A span-level `contract.tier` attr wouldn't pass the
  filter and vice versa — deliberate.
- `TracingLocalExportTest` — new
  `writesContractViolationEventsThroughAllowlist` test: positive
  assertions (event + 3 attrs round-trip) AND negative assertions
  (disallowed event name `"other.event"` + its attrs are dropped).

**Pre-existing silent-drop behavior preserved.** The existing test
`writesRootAndStageSpansWithAttrs` calls `stage.addEvent("skip",
...)` and never asserted the event round-tripped. It still passes —
P6's filter drops `"skip"` exactly as the old exporter dropped
every event. No regression; the behavior was always "events dropped,"
the bug was that nobody noticed nothing was consuming them. P6 makes
this explicit via the allowlist.

**Not yet validated end-to-end.** No production call site invokes
`ContractEmitter.emit` today — that's P7. Until then, the full
emit-→export-→projection chain is only covered by piece-wise unit
tests: `ContractEmitterTest` (emit → SDK SpanData), the new
`writesContractViolationEventsThroughAllowlist`
(span-with-addEvent → exporter output), and the existing Python
`test_projection_contract_violations.py` (NDJSON fixture →
aggregate). The shapes at each boundary are asserted by literal
strings, so a rename on any side would fail the test immediately.
But a live jseval run exercising the entire chain is P7's gate.

### P2–P6 critical-analysis cleanup pass (uncommitted)

After P6 shipped, a code-level critical-analysis pass surfaced 20
observations across the four shipped phases (P2, P3, P4, P5, P6).
Four were fixed in a follow-up cleanup; the rest are either
non-issues, deliberate design choices, or deferred to P7 with
rationale recorded below.

**Fixes shipped:**

- **Issue 10 — `ContractEmitter` null handling**: added
  `@throws NullPointerException` javadoc documenting that null args
  are rejected by `Attributes.of` before any event is attached, and
  added `ContractEmitterTest.emitRejectsNullArgsWithNpe` pinning
  the behavior. A future null-coercion refactor (e.g. "treat null as
  empty string") now fails this test instead of silently changing
  the consumer contract.
- **Issue 11 — `SampleKey` dynamic-key leak**: added javadoc note
  warning that counter map entries are never evicted, so sites must
  declare `SampleKey` as `private static final` constants. Dynamic-key
  patterns (e.g. per-request `SampleKey` construction) leak a counter
  entry per distinct key.
- **Issue 5/6 — HeadlessApp race documented**: the race is verified
  real (`LocalApiServer.java:334` calls Javalin's `start()` inside the
  constructor, so `builder.build()` returns a live server). Added an
  inline comment above `BootContractRunner.validateAll()` in
  `HeadlessApp.main` naming the race and pointing at this tempdoc.
  Resolution (move earlier vs two-phase API server) is P7's call.
- **Issue 1 — `BootContractRegistry.reset()` regression test**:
  the reset behavior works today (line 60 nulls the cache), but no
  test pinned it. Added `registryResetCausesReDiscoveryOnNextValidatorsCall`
  that asserts a fresh `ServiceLoaderProbeValidator` instance after
  reset — a future refactor that accidentally retains the cached
  list now fails this test.

**Deferred to P7 with rationale:**

- **Issue 3 — `BootContractRunner.validateAll()` calls `System.exit`**:
  integration tests that exercise `main()` would kill the JUnit JVM
  if a real validator fails. Today no real validators are registered,
  so this is hypothetical. When the first P7 Boot validator lands,
  the first failing integration test will force a decision (sysprop
  opt-out, throw-exception-instead, or test-specific fail-mode).
  Premature to fix now per CLAUDE.md "don't design for hypothetical
  future requirements."
- **Issue 7 — `@BuildContract.enforcer` not audited**: downgraded from
  the original claim. `@BuildContract` has no dispatch machinery —
  the `enforcer` string is pure documentation. A typo or renamed-test
  is doc drift, not a silent runtime failure. Different severity than
  `@BootContract.validator` (which ServiceLoader-dispatches through
  the FQN). A `BuildContractAudit` mirroring `BootContractAudit` is
  still worth considering as tooling alongside P7, but the silent-failure
  class is milder: the invariant stays enforced (by the test body) as
  long as the test runs; only navigation from source-to-enforcer rots.
- **Issue 12 — `SampleContract.validator` default empty**: matches
  `@BuildContract.enforcer`'s shape. If a future `SampleContractAudit`
  ever lands, an empty default becomes a hole. Tied to the same
  tooling decision as Issue 7.
- **Issue 15 — downstream consumers of `traces.ndjson`**: verified
  non-issue. Grep of `scripts/jseval/` shows no consumer reads the
  pre-existing `"skip"` event attrs; only `reason_code` appears in
  `ALLOWED_ATTRS` at the span level (not the event level). P6's
  event-name filter silently drops `"skip"` events with no consumer
  regression.
- **Issue 19 — end-to-end Java→NDJSON→Python integration test**:
  requires a real call site to emit from. P7 work.
- **Issue 20 — `ContractEmitter.emit` positional-string typo risk**:
  three positional strings (tier, tempdoc, description) with same
  type make swaps undetectable at compile time. A named-parameter or
  builder API would prevent this. Premature to refactor before P7
  reveals call-site density; if typos become a pattern during P7,
  revisit then.

**Process observation — Issue 18 (systemic):** three plan defects
landed during P3–P6 (compile-only classpath, `verification-metadata.xml`,
missing event export). Common cause: plan was written before reading
source end-to-end. Each was caught by build/test gates but would have
been prevented by a read-before-plan discipline. For P7, read every
target file in full before writing the implementation plan.

**Indirect-change pass — `cpu_fallback.triggered` unblocked
alongside P6 cleanup (uncommitted):** reviewing my P6 change for
second-order implications surfaced that `cpu_fallback.triggered`
events from `NativeSessionHandle#reportCpuSessionFailure` and
`EncoderOrtRunSpans#emitCpuFallbackEvent` — referenced by tempdoc
400 LR2-c and consumed by `cpu_fallback_counts.py` — were being
silently dropped pre-P6 (the exporter never emitted events) AND
would remain dropped post-P6 (my allowlist only contained
`contract.violation`). Same structural bug class P6 exists to fix;
completing the fix for the second production emitter is one line
(add `"cpu_fallback.triggered"` to `ALLOWED_EVENT_NAMES`,
`"fallback.cause"` + `"fallback.encoder"` to `ALLOWED_EVENT_ATTRS`)
plus a mirror round-trip test in `TracingLocalExportTest`. Scope
discipline per CLAUDE.md's "structural defects don't need repeat
incidents" rule.

**Module-deps canonical regenerated (uncommitted):** P3's
`compileOnly` → `implementation` promotions for `core-contracts` in
`modules/ui` and `modules/indexer-worker` are now reflected in
`docs/reference/architecture/module-deps.md` via
`scripts/architecture/module-deps.mjs --update-canonical`. Diff is
clean: `core-contracts` moves off the orphan-nodes list into the
production dep graph, and the two promoting modules' dep lists gain
an entry.
