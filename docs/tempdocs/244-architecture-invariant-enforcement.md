---
title: "244: Strengthen architecture invariant enforcement"
---

# 244: Strengthen architecture invariant enforcement

**Status:** Complete
**Parent:** tempdoc 238 (F6, F7)
**Goal:** Add mechanical enforcement for two hard invariants that are currently convention-only: (1) codebase-wide ArchUnit rule preventing non-Lucene-owner modules from importing `org.apache.lucene..` classes, and (2) negative test asserting legacy endpoints (`/api/search`, `/api/settings`) return 404 at the Javalin routing level.

---

## Background

Two of the four hard invariants in CLAUDE.md lack mechanical enforcement:

- **"Head never touches Lucene"** — `IndexWriterOwnershipTest` blocks `IndexWriter` constructor calls, but there is no rule preventing any module from importing Lucene read-path classes (`IndexReader`, `IndexSearcher`, `Query`, etc.). The invariant holds today by convention for all modules except the two legitimate Lucene owners.
- **"No legacy endpoints"** — `docsApiDriftCheck` prevents *documentation* from referencing `/api/search` or `/api/settings`, but nothing prevents a developer from re-adding those routes to Javalin. The build would pass.

Both are regression-prevention rules: they pass today and exist to catch future violations.

### Blocking analysis (from 238 investigation)

Tempdoc 238 marked F6 as "blocked on F2 (RuntimeConfig extraction -> tempdoc 239)". This was based on the concern that `modules/ui` compile-depends on `adapters-lucene` (for `RuntimeConfig` and `IndexRuntimeIOException`).

**The block does not apply to the rule as scoped.** The ArchUnit rule targets `org.apache.lucene..` package imports, not `io.justsearch.adapters.lucene..` imports. No module outside the two legitimate Lucene owners imports any `org.apache.lucene` class today, so the rule passes immediately. The broader concern — UI depending on adapter types at all — is a separate invariant addressed by tempdocs 239 (RuntimeConfig extraction) and 242 (IPC error type migration).

### Current Lucene import landscape (verified)

| Module | `org.apache.lucene` imports | Legitimate? |
|--------|---------------------------|-------------|
| `adapters-lucene` | Many | Yes — is the Lucene adapter |
| `indexer-worker` | 4 files (HighlightingOps, CitationMatchOps, SearchOrchestrator, TextAnalysisUtils) | Yes — Worker process owns Lucene at runtime |
| `app-search` | 1 file (LuceneSearchClient) | Yes — direct Lucene search client for ANN/vector search |
| All other 12 modules | Zero | Must stay zero |

---

## Item 1: ArchUnit rule — non-owner modules must not depend on Lucene classes (F6)

### What

Add an `@ArchTest` rule asserting that classes outside the three Lucene owner modules (`adapters-lucene`, `indexer-worker`, `app-search`) must not depend on classes in `org.apache.lucene..`. This protects all 12 clean modules (ui, app-services, app-api, app-inference, app-agent, core, configuration, ipc-common, ai-bridge, ai-worker, app-launcher, telemetry) — not just the UI module.

### Where

`modules/app-launcher/src/test/java/io/justsearch/app/launcher/IndexWriterOwnershipTest.java` — same file, same concern ("Head never touches Lucene"). The existing rule blocks `IndexWriter` construction from non-adapter code; the new rule blocks *any* Lucene class dependency from non-owner modules.

### Implementation

```java
@ArchTest
static final ArchRule onlyLuceneOwnersMayDependOnLuceneClasses =
    noClasses()
        .that()
        .resideInAnyPackage("io.justsearch..")
        .and()
        .resideOutsideOfPackage("io.justsearch.adapters.lucene..")
        .and()
        .resideOutsideOfPackage("io.justsearch.indexerworker..")
        .and()
        .resideOutsideOfPackage("io.justsearch.app.search..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.apache.lucene..")
        .as("only adapters-lucene, indexer-worker, and app-search may depend on Lucene classes");
```

### Why codebase-wide, not UI-only

The original tempdoc 238 framed F6 as a UI-specific rule. But the invariant is architectural — Lucene should be contained to its three owner modules. A UI-only rule would miss violations in `app-services`, `app-inference`, or `app-agent`, which are closer to the data layer and more likely targets for accidental Lucene imports. The codebase-wide rule protects 12 modules instead of 1, at zero additional cost.

The existing `IndexWriterOwnershipTest.luceneIndexWriterOwnedByAdapters` already uses this pattern (all of `io.justsearch..` minus `adapters.lucene..`). The new rule extends the same pattern from IndexWriter-only to all Lucene classes, with `indexer-worker` and `app-search` as additional exceptions.

### Verification

- `./gradlew.bat :modules:app-launcher:test --tests '*IndexWriterOwnershipTest*'`
- Rule should pass (only adapters-lucene, indexer-worker, and app-search import Lucene today).

### Future scope (not this tempdoc)

After tempdocs 239 and 242 complete, a second rule could ban `io.justsearch.ui..` from depending on `io.justsearch.adapters.lucene..` entirely. That rule would fail today because `ApiErrorHandler` imports `IndexRuntimeIOException` and `HeadlessApp`/`LocalApiServer`/`DebugStateController` import `RuntimeConfig`.

---

## Item 2: Negative test — legacy endpoints return 404 (F7)

### What

Add a unit test asserting that `POST /api/search`, `GET /api/settings`, and `POST /api/settings` return 404 when sent to a Javalin app wired with the real route registration methods.

All three endpoints were removed in commit 1ce7bee4 (2026-01-06). The `docsApiDriftCheck` bans both `/api/search` and `/api/settings` (any method) in documentation — this test adds the corresponding runtime enforcement.

### Where

New file: `modules/ui/src/test/java/io/justsearch/ui/api/LegacyEndpointGuardTest.java`

Follows the established pattern from `LocalApiCorsPolicyTest` and `LocalApiUiTokenPolicyTest`: spin up a minimal Javalin instance, register routes, send HTTP requests, assert status codes.

### Test pattern: call real route registration with null/mock controllers

The CORS and token tests use stub Javalin apps. For F7, we need a stronger pattern: the test must call the **real** `*Routes.register()` methods so that adding `/api/search` to any routes file causes the test to fail.

Route registration methods accept controllers/handlers but don't invoke them during registration — they only capture references for later dispatch. This means we can pass nulls or no-op lambdas safely. The route paths get registered; the handlers never fire (we're testing 404 paths that don't match any route).

```java
@DisplayName("Legacy endpoint resurrection prevention")
class LegacyEndpointGuardTest {

  private HttpClient client;
  private Javalin app;
  private int port;

  @BeforeEach
  void setup() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    app = Javalin.create(cfg -> cfg.showJavalinBanner = false);
    registerRealRoutes(app);
    app.start("127.0.0.1", 0);
    port = app.port();
  }

  @AfterEach
  void teardown() {
    if (app != null) { app.stop(); app = null; }
  }

  /** Register the real route set with null/no-op controllers. */
  private static void registerRealRoutes(Javalin app) {
    Handler noop = ctx -> {};
    // Mirror LocalApiServer.setupRoutes() — call real register() methods.
    // Controllers are null/no-op because we only care about path registration.
    StatusRoutes.register(app, null, noop, noop, null, null, null, null);
    IndexingRoutes.register(app, null);
    DebugRoutes.register(app, null, null, null, noop, null, null);
    AiRoutes.register(app, null, null, null, null, null);
    InferenceRoutes.register(app, noop, noop, noop, noop, noop, noop, noop);
    KnowledgeRoutes.register(app, null, null);
    AgentRoutes.register(app, null);
  }

  @Test
  @DisplayName("POST /api/search returns 404 (removed — use POST /api/knowledge/search)")
  void legacySearchPostReturns404() throws Exception { /* ... */ }

  @Test
  @DisplayName("GET /api/settings returns 404 (removed — use GET /api/settings/v2)")
  void legacySettingsGetReturns404() throws Exception { /* ... */ }

  @Test
  @DisplayName("POST /api/settings returns 404 (removed — use POST /api/settings/v2)")
  void legacySettingsPostReturns404() throws Exception { /* ... */ }
}
```

**Why this is stronger than a bare Javalin app:** A bare app returns 404 for everything — the test would pass vacuously. By registering the real routes, we prove that `/api/search` is absent from the *actual* route set. If a developer adds it to any `*Routes.register()` method, the test breaks.

**Null safety note:** If any `register()` method NPEs on a null controller during path setup (not expected — Javalin captures handler closures lazily), replace with a Mockito mock. The UI module already has Mockito in test scope.

### Verification

- `./gradlew.bat :modules:ui:test --tests '*LegacyEndpointGuardTest*'`
- All three tests should pass (legacy endpoints are not registered).

---

## Implementation checklist

- [x] Add `onlyLuceneOwnersMayDependOnLuceneClasses` rule to `IndexWriterOwnershipTest.java`
- [x] Run `app-launcher` tests to confirm the new rule passes
- [x] Create `LegacyEndpointGuardTest.java` with real route registration and 404 assertions for `POST /api/search`, `GET /api/settings`, `POST /api/settings`
- [x] Switch to Mockito mocks for controllers that NPE on null (10 of 14 are `final`)
- [x] Run `ui` tests to confirm the new tests pass
- [x] Run full `./gradlew.bat test` to confirm no regressions
- [x] Run `./gradlew.bat spotlessApply` before final commit

---

## Post-implementation quality issues

### Issue A: Old `luceneIndexWriterOwnedByAdapters` rule is now redundant

The new `onlyLuceneOwnersMayDependOnLuceneClasses` rule blocks *all* Lucene class dependencies from non-owner modules, which strictly subsumes the old rule (which only blocks `IndexWriter` constructor calls from non-adapter code). Both rules live in the same file. The old rule also has a narrower exception list (only `adapters-lucene`, not `indexer-worker` or `app-search`) — but this is invisible because `indexer-worker` and `app-search` don't construct `IndexWriter` anyway.

**Research finding:** Keep both rules. The narrow rule's value is in its error message — when someone constructs `IndexWriter` outside the adapter, they get a failure that names the exact problem (write-lock ownership violation) rather than a generic "depends on Lucene class." Add `.because()` annotations to both rules to document the distinct intents.

**Fix:** Add `.because()` to both rules. No deletion.

### Issue B: No mechanism to prevent exception erosion

Three exceptions (`adapters-lucene`, `indexer-worker`, `app-search`) is already a lot. Adding a fourth requires only one `.resideOutsideOfPackage()` line — there's no friction or review gate beyond code review. Over time, exceptions accumulate and the rule weakens.

**Research finding:** `FreezingArchRule` is wrong for this — it freezes the *violation set*, not the rule definition. Adding a new exception actually makes FreezingArchRule pass more easily, hiding erosion. The effective pattern is: extract the exception list to a named constant array, then add a separate `@Test` asserting the array size. This forces a two-point change (add package + update size assertion), making exception growth visible in code review.

**Fix:** Extract exception packages to a `LUCENE_OWNER_PACKAGES` constant. Add a size-guard test:
```java
private static final String[] LUCENE_OWNER_PACKAGES = {
    "io.justsearch.adapters.lucene..",   // Primary Lucene adapter (index read/write)
    "io.justsearch.indexerworker..",      // Worker process owns Lucene lifecycle
    "io.justsearch.app.search..",         // ANN/vector search client
};

@Test
void luceneOwnerAllowlistSizeIsControlled() {
    assertEquals(3, LUCENE_OWNER_PACKAGES.length,
        "Adding a Lucene owner requires updating this assertion and documenting the reason");
}
```

### Issue C+D: `registerRealRoutes()` coupling, heavy mocks, per-test server

The test manually mirrors `setupRoutes()` with 14 mocks and starts/stops an HTTP server per test method.

**Research finding:** Javalin 6.3.0 exposes `app.unsafeConfig().pvt.internalRouter.allHttpHandlers()` which returns all registered routes as `ParsedEndpoint` objects (path + method + handler). This is the same API used by Javalin's built-in `RouteOverviewPlugin`. It works without starting the HTTP server.

**Fix:** Rewrite the test to use route inspection instead of HTTP requests:
- Call real `*Routes.register()` with mocks (still needed for registration)
- Instead of starting a server and sending HTTP requests, inspect the route list and assert legacy paths are absent
- No `HttpClient`, no `app.start()`, no `app.stop()`
- Switch from `@BeforeEach`/`@AfterEach` to `@BeforeAll`/`@AfterAll` (or just a static setup)

This fixes Issue C (coupling) partially — the test still mirrors `setupRoutes()`, but at least it no longer needs an HTTP server. The coupling is inherent to the approach of calling real `register()` methods; the alternative (inspecting `LocalApiServer` directly) requires constructing the full server, which is heavier.

**Caveat:** `unsafeConfig().pvt.internalRouter` is an internal API. It works in Javalin 6.3.0 and is used by Javalin's own RouteOverviewPlugin, but could change in Javalin 7. Add a comment noting this.

### Issue E: No positive sanity assertion — test passes vacuously if route registration fails

The three legacy-absent assertions all use `assertFalse(set.contains(...))`. If `registerRealRoutes()` silently fails (e.g., a null guard skips registration, or a mock change causes a swallowed exception), the route set is empty and every `assertFalse` passes — giving false confidence.

**Discovery:** Adding a sanity check for `POST /api/knowledge/search` immediately failed. `KnowledgeRoutes.register()` and `AgentRoutes.register()` both have null guards (`if (controller == null) return`) that silently skip all route registration when passed null. The original test was passing null for both, meaning those two route files' paths were never registered — the test was silently incomplete.

**Fix:** Added a `replacementEndpointsAreRegistered()` test that asserts `POST /api/knowledge/search` and `GET /api/settings/v2` are present. Changed `KnowledgeRoutes` and `AgentRoutes` calls to use mocks instead of null. Fixed `KnowledgeRoutes` Logger parameter (was null, NPE on `log.info()` after registration).

### Issue F (known, inherent): `registerRealRoutes()` coupling to `LocalApiServer.setupRoutes()`

The test manually mirrors `setupRoutes()` with 7 route class calls. If someone adds an 8th routes file to `LocalApiServer` but not to the test, routes from the new file aren't checked. This is inherent to the approach of calling real `register()` methods — the alternative (constructing the full `LocalApiServer`) requires wiring the entire application context.

This is the same coupling present in `LocalApiCorsPolicyTest` and `LocalApiUiTokenPolicyTest`. Not addressed — documented as accepted trade-off.

## Quality fix checklist

- [x] Research Issue A: keep both rules, add `.because()` annotations
- [x] Research Issue B: extract allowlist constant + size-guard assertion
- [x] Research Issue C+D: use Javalin route inspection API, drop HTTP server
- [x] Implement Issue A fix: add `.because()` to both ArchUnit rules
- [x] Implement Issue B fix: extract `LUCENE_OWNER_PACKAGES` constant + size-guard test (used indexed access from constant in rule — DRY without fluent API loop)
- [x] Implement Issue C+D fix: rewrite `LegacyEndpointGuardTest` to use route inspection
- [x] Implement Issue E fix: add positive sanity assertion, fix null-guard route registration gaps
- [x] Run full test suite to verify

---

## Purpose evaluation

**Goal (from line 5):** Add mechanical enforcement for two hard invariants that are currently convention-only.

### Item 1 (F6): "Head never touches Lucene" — fulfilled

The `onlyLuceneOwnersMayDependOnLuceneClasses` rule catches any `org.apache.lucene` class dependency from any of the 12 non-owner modules at build time. The `LUCENE_OWNER_PACKAGES` constant with size guard creates friction against exception erosion. The existing `luceneIndexWriterOwnedByAdapters` rule is preserved with its narrower scope and distinct `.because()` message.

**What it catches:** A developer adding `import org.apache.lucene.search.IndexSearcher` to any non-owner module gets a build failure naming the exact rule and the architectural reason.

**What it doesn't catch:** Dependencies on `io.justsearch.adapters.lucene..` types (e.g., `RuntimeConfig`, `IndexRuntimeIOException`). This is a separate invariant addressed by tempdocs 239 and 242.

### Item 2 (F7): "No legacy endpoints" — fulfilled with known coupling caveat

The `LegacyEndpointGuardTest` asserts that `POST /api/search`, `GET /api/settings`, and `POST /api/settings` are absent from the real route set. The positive sanity assertion (`replacementEndpointsAreRegistered`) prevents vacuous passing. Route inspection via Javalin's internal API avoids HTTP server overhead.

**What it catches:** A developer adding `app.post("/api/search", handler)` to any of the 7 registered route files gets a test failure.

**What it doesn't catch:** A developer adding a legacy path in a new 8th routes file that isn't mirrored in `registerRealRoutes()` (Issue F — inherent coupling, same as other route-level tests in the codebase).

### Residual risks (accepted)

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Javalin 7 breaks internal router API | Medium (major version) | Test fails at compile time, forces update | Documented in Javadoc; fails loudly |
| New routes file not added to test | Low (rare event) | Silent gap for that file only | Same pattern as CORS/token tests; code review |
| Size guard bypassed with literal string | Very low | Exception added without documentation | Code review; constant + guard make intent visible |
