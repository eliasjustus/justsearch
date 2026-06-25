---
title: "Silent staleness masquerading as a product bug — and the design that ends it: make every stale state a *reasoned observable at its owning layer* (conforming to the existing reason-code + canonical-authority-and-projection seams), aggregated for the validating agent at the dev-tooling layer (preflight/quick_health), never a new freshness god-endpoint. A stale infrastructure state (dead FE→backend binding, stale installed jar, cold index, clobbered lockfile — and, recursively, the agent tooling's OWN stale code, §H) must surface AS staleness at its own layer, never one layer up as '0 results' / 'feature broken'."
type: tempdoc
status: "MERGED to main. FE #1 live-validated in-browser; #2/#3/#4/Layer-A/C done; §G.1 (inline stack-staleness) + §H.1 (MCP self-freshness) done. Tauri #1 self-heal: COMPILE+bundle VERIFIED in CI (build-installer run 28062098035 SUCCESS, 2026-06-24) — local cargo was blocked by an OS Application Control policy. Remaining (both inherent, not code): (a) Tauri #1 packaged backend-restart e2e (needs a human running the installed app); (b) §H.1 mcpServerStale visible only after an MCP-server restart (running server predates the edit)."
created: 2026-06-23
updated: 2026-06-23
related:
  - 618-dev-stack-worktree-seams        # sibling dev-environment-friction surface (runtime staging, worktree teardown)
  - 630-os-sleep-resume-robustness      # staleness-after-sleep is the same family (a stale runtime presenting as broken)
---

# 637 — Silent staleness masquerading as a product bug

**Origin:** an agent-experience retrospective (2026-06-23). This is the single highest-time-cost issue I hit
across the 629 work — not a code defect, but an *environment-legibility* defect. It is written from the
validating agent's seat: the goal is to describe the issue plainly and then define the **theoretically
perfect workflow** that would have eliminated the wasted turns.

> **Current state (2026-06-23) — IMPLEMENTED on `worktree-637-staleness-legibility` (see §E); design history below.** This doc carries:
> §A (source-verified investigation of all four incidents), §B (long-term design — *reasoned observable at
> the owning layer*, aggregated at the dev-tooling layer, no backend god-endpoint), §C (reach / named
> principle), and §D (pre-implementation confidence pass). Net of de-risking: **#1** detection is nearly free
> (the `instanceId` per-boot id already exists and the FE already captures it) but is a real *product* bug
> whose prod recovery spans Tauri/Rust (the one un-spiked piece); **#2** is sound, gate-point moves to
> post-launch; **#3** already conforms (re-verify only); **#4** demoted (no cheap local lockfile check —
> the pre-merge CI gate already catches it). **Built, all gates + `gradlew build -x test` green, FE #1
> live-validated in-browser, and MERGED to `main` (fast-forward) on 2026-06-23.** The one residual is the
> Tauri/Rust #1 prod-recovery, whose local `cargo` build is blocked by a Windows Application Control policy
> (os error 4551) — it needs a CI/maintainer cargo build + a packaged backend-restart e2e (see §E).**

## The issue, plainly

When I validate a feature against the live dev stack, a **stale infrastructure state** repeatedly presented
itself **as a different problem** — usually a product or feature bug — at a layer *above* where the staleness
actually lived. So I debugged the wrong layer, often for many turns, before discovering the real cause was
"something was stale," not "something is broken."

The defining property is **the masquerade**: the failure surfaces one layer away from its cause, and the
intervening layer (a proxy, a cache, a default) actively *masks* it. A stale state should be the *easiest*
thing to notice; instead it was the hardest, because it wore the costume of a real bug.

## Incidents this session (proof-by-example, not a one-off)

1. **Dead FE→backend binding → looked like a search "No results" bug.** A browser tab held a backend port
   (`:63175`) from a *previous* dev-stack; after a restart the live backend was on a new port. The FE's
   absolute-URL calls failed ("Failed to fetch / Reconnecting…"), but my *relative* `/api` probes kept
   working through the Vite proxy — so the FE silently showed **empty results** while the backend was
   perfectly healthy. I spent **~8 browser turns** treating it as a UI/search defect before finding the
   buried "Reconnecting" string. **Cost: the most turns of the session.**

2. **Stale installed jar → looked like a meta-only data-loss *product bug*.** The dev stack served an old
   build (`installDist` reported UP-TO-DATE and didn't reinstall), so the running behavior was the *previous*
   version's. During the faithful-import validation this looked like a real "import restores meta only, loses
   the event ledger" defect — I nearly reported a product bug that was a stale jar.

3. **Cold / `BLOCKED_LEGACY` index → looked like search returning nothing.** A fresh index has no embedding
   fingerprint, so vector/hybrid queries are blocked until an auto-reindex completes; the *default* search
   mode is hybrid, so first-run searches returned weak/empty results that read as "search is broken" — when
   only `mode:text` BM25 worked during the warming window.

4. **Clobbered lockfile → silent dependency revert.** A neighbour's `resolveAndLockAll` reverted my added
   dependency from the lockfile with no signal; discovered only by chance.

These are **four distinct sources** (network binding, build artifact, index warmth, dependency lock) of the
*same* failure shape. That is what makes it a class, not an incident.

## Why it is expensive

- **Wrong-layer debugging.** Every minute spent on the UI/search/feature was spent at the wrong altitude;
  the cause was an infra state I wasn't looking at.
- **False bug reports narrowly avoided.** #2 and #1 both nearly became "I found a product bug" — the most
  expensive possible wrong turn, because it mis-scopes downstream work (the `audit-without-test` /
  confidently-wrong-claim cost class).
- **The mask defeats the obvious check.** Because a proxy/default/cache kept *something* working, the naive
  "is it reachable?" check passed, hiding the staleness.

## The root pattern (the thing to fix)

> **A stale state must surface AS staleness, at its own layer — it must never be able to impersonate a
> product defect one layer up.**

Today the environment violates this: staleness is *silent and displaced*. The fix is not any one of the four
sources — it is removing the **masquerade** as a category.

## The theoretical perfect workflow (this tempdoc's goal)

What the ideal would look like *for me, the validating agent*. Three layers, in priority order.

### A. One loud freshness preamble — before I debug anything
A single probe (one command / one endpoint, e.g. an extension of `/api/health` → `/api/debug/freshness`)
that answers **"is everything I am about to test actually fresh and live?"** and returns a per-source verdict
of `FRESH | STALE(reason, remedy) | WARMING(eta)`:

| Source | FRESH means | Today's silent failure it replaces |
|---|---|---|
| **Build artifact** | installed dist hash == current source hash | stale jar serving old behavior (#2) |
| **FE↔backend binding** | the FE's resolved backend == the live backend instance | dead-port "No results" (#1) |
| **Index warmth** | embeddings built, no `BLOCKED_LEGACY` | cold-index empty hybrid results (#3) |
| **Dependency locks** | lockfiles consistent with build files | clobbered-lock silent revert (#4) |

Any non-`FRESH` is printed **loudly with the exact remedy** (`reload the tab`, `run installDist`,
`index warming — ~Ns`, `re-run resolveAndLockAll`). The discipline this enables: **run the freshness probe
first; only debug behavior once every source reads FRESH.** (This is `verify-don't-guess` extended from
"lifecycle" to "freshness".)

### B. Each surface self-declares its own staleness at the point of use
So that even if I skip the preamble, the masquerade is structurally impossible:
- The **FE shows a blocking "DISCONNECTED — backend restarted, reload" banner**, never silent empty results,
  when its backend binding is dead. (A live-validation tab that can't reach its backend is the #1 trap.)
- **Search returns a typed status** (`INDEX_WARMING` / `EMBEDDINGS_REBUILDING`) distinct from an empty
  result set — "no matches" and "index not ready" must be *different* observable outcomes, never the same
  empty list.
- A **stale-jar dev start warns or refuses** ("serving a jar older than source — run installDist") instead
  of silently running old code.
- A **lockfile drift is surfaced** at the next build, not discovered by accident.

### C. A ground-truth-first affordance (and the matching agent discipline)
A single command that returns the **lowest-layer truth** ("does the backend/data actually return X?") so the
correct instinct is **data layer first, presentation last**. The agent-side half of the lesson: when a
validation shows an unexpected *negative* (no results, missing data, "feature broken"), the FIRST move is the
freshness probe + the API ground-truth — never UI/feature archaeology. In this session, the one time I
finally hit the search API directly, it *immediately* localized the fault to the FE; doing that first would
have saved ~8 turns.

## What partly exists today (so the gap is legible, not a greenfield)
- `/api/health` already reports lifecycle (`LIFECYCLE_STATE_DEGRADED` / `inference.offline`) and `/api/debug/state`
  exists — the freshness verdict (A) is a **sibling of these**, not new infrastructure.
- The cold-index state IS logged (`Embedding compatibility: BLOCKED_LEGACY … REBUILDING`) — it just isn't
  surfaced to the *caller* as a typed search status (B).
- The stale-jar trap is documented as a *pitfall in CLAUDE.md* — the gap is that documentation relies on the
  agent remembering it, whereas a freshness probe **enforces** it (~100% vs ~70%, the prose-vs-mechanism gap).
- The lockfile-drift hint exists post-edit (`lockfile-hint`) but does not catch a *neighbour's* clobber.

The through-line: **the signals largely exist but are scattered, silent at the caller, and displaced from
their cause.** The perfect workflow is mostly *consolidation + loudness + typed outcomes*, not new sensing.

## Non-goals
- Not a request to fix any single one of the four sources in isolation (that just moves the masquerade).
- Not multi-agent-coordination redesign (the lockfile clobber's *root* is shared-main; that is a separate
  concern — here it is only one instance of the staleness shape).
- No implementation is proposed yet; this tempdoc defines the **problem and the target workflow** so a future
  decision can scope the build (the freshness endpoint + the per-surface typed-staleness statuses).

---

## §A. Investigation against `main` (2026-06-23, takeover agent)

Autonomous source-verbatim verification of every load-bearing claim, before any design work. Method:
parallel read-only audits of the four sources + the existing health/debug surface; each claim re-checked
against `file:line`. **Headline: two of the four incidents are more solved on `main` than the tempdoc
credits, and one (#3) is largely not reproducible on `main` at all.** This *strengthens* the tempdoc's own
through-line — "the signals largely exist but are scattered, silent at the caller, and displaced" — to the
point of reframing the build as **almost entirely consolidation + loudness, with very little new sensing.**

### A.1 Per-incident verdict

| # | Incident | Verdict on `main` | Key evidence |
|---|---|---|---|
| 1 | Dead FE→backend binding → silent empty results | **CONFIRMED, well-described** | FE captures an *absolute* backend URL at boot (`modules/ui-web/src/api/http.ts:402,418,424,433`); polling reuses that captured base (`statusPoll.ts:31`, `aiStateStore.ts:607-608`). Failure is detected only *passively* by a 5 s staleness timer with a 15 s threshold (`aiStateStore.ts:587-611`, `STALE_THRESHOLD_MS=15_000`) and surfaces as a **small `statusLabel` "Reconnecting…"** (`shell-v0/state/verdict.ts:206`) in `LivenessReadout.ts:99` — *not* a blocking banner. The mask is real: in dev the Vite proxy serves *relative* `/api` (`vite.config.js:130-143`) so relative probes keep working while the captured absolute base is dead. |
| 2 | Stale installed jar → looked like data-loss product bug | **CONFIRMED, but sensing already exists** | The dev-runner launches straight from `installDist` output (`scripts/dev/dev-runner.cjs:1010-1015`). A **build stamp already exists**: `computeHeadDistStamp()` SHA-256s the installed jars (`dev-runner.cjs:498-519`) and the MCP server cross-checks the launch lease's stamp against the running Head's reported `manifest.head.buildStamp`, setting `ownership.backendStale=true` on mismatch (`scripts/dev/justsearch-dev-mcp/server.mjs:160-169`). So the *signal* is built; the gap is it is an **advisory flag, not loud/blocking**, and Gradle `UP-TO-DATE` can still skip `installDist`. Documented only as prose (`CLAUDE.md:234`). |
| 3 | Cold / `BLOCKED_LEGACY` index → "search returns nothing" | **LARGELY NOT REPRODUCIBLE on `main`** | Two load-bearing sub-claims are contradicted by source: **(a) the product default is TEXT, not hybrid** — FE quick pass sends `mode:'text'` (`shell-v0/state/searchState.ts:327`), the refined pass omits `mode`, and the Head defaults an omitted mode to `SEARCH_MODE_TEXT` (`app-services/.../worker/SearchPipelinePresets.java:25-27`, consumed at `KnowledgeSearchEngine.java:294`). BM25 works fine on a cold index. **(b) A typed status already exists end-to-end** — hybrid on `BLOCKED_LEGACY` *falls back to text* and sets `SearchTrace.Degradation.hybridFallback=true`/`hybridFallbackReason=LEGACY_INDEX_NO_FINGERPRINT` (`worker-services/.../GrpcSearchServiceDegradationSignalingTest.java:64-98`); only *vector-only* returns empty, and it too carries `vectorBlocked`+reason (`:27-61`). That degradation record flows worker→Head→HTTP (`app-api/.../knowledge/SearchTrace.java:61-67`, `KnowledgeSearchController.java:387-388`) and the FE *already renders a degradation banner* with worded reason codes incl. `LEGACY_INDEX_NO_FINGERPRINT` (`shell-v0/.../strategies/searchTraceExplain.ts:115-147`; test `SearchSurface.degradation.test.ts:77-94`). So "no-matches and not-ready are the same empty list" (the §B claim for search) is *already false* on `main`. |
| 4 | Clobbered lockfile → silent dependency revert | **CONFIRMED** | `lockfile-hint` fires only on *your own* `build.gradle.kts` Edit (`scripts/agent-analytics/hooks/lockfile-hint.mjs:34-43`) and emits a reminder — it does no drift check and cannot see a neighbour's worktree. Lockfile consistency is enforced **only in CI** (`.github/workflows/ci.yml:549-557` `resolveAndLockAll` + `git diff --exit-code`), explicitly *not* locally (`build.gradle.kts:647-648`). A neighbour's `resolveAndLockAll` merged to `main` can silently drop your dependency with no local signal. |

### A.2 What this does to the tempdoc's argument

- **The "class" survives but on three legs, not four.** #1 (binding), #2 (jar), #4 (lock) are real, distinct, and share the masquerade shape. #3 should be **demoted from a proof-instance to a cautionary footnote**: on `main` the product issues BM25 by default and already emits + renders a typed degradation status. The author's lived #3 was most plausibly a *secondary symptom of #2* (a stale jar predating the degradation-banner / text-default work) or *enrichment-not-done* (docs unembedded ⇒ weaker semantic recall, but BM25 still returns hits) — **not** a query-blocking masquerade. Recommend the author re-verify #3 against current `main`; if it cannot be reproduced, keep it only as "the shape would apply *if* a surface ever requested hybrid-by-default without surfacing fallback."
- **"Mostly consolidation, not new sensing" is an understatement.** The typed search status (§B bullet 2) and the build-stamp (§A.1 #2) already *exist*. Genuinely-new sensing is narrow: **(i)** an FE↔backend *binding-identity* check, and **(ii)** a *local* (pre-CI) lockfile-drift signal. Everything else is surfacing what is already measured, more loudly and in one place.

### A.3 Critical question for the proposed solution: is a single backend `/api/debug/freshness` the right owner?

The proposal (Layer A) is one backend endpoint returning a per-source verdict. **The four sources live at four
different layers, and a backend endpoint can only see one of them honestly:**

| Source | Who can actually observe it | Belongs in `/api/debug/freshness`? |
|---|---|---|
| Index warmth | the backend itself | **Yes** — already in `/api/status` (`worker.compatibility.embeddingCompatState`) + search degradation; a freshness verdict is a thin projection. |
| Build artifact (jar) | the dev-runner / MCP (it knows source vs installed) | **No** — the *running* Head cannot compare itself to source on disk; the stamp lives in the launch lease (`server.mjs:160-169`). Surface it in `quick_health`/`preflight`, not a backend route. |
| FE↔backend binding | only the **FE** (the backend doesn't know what URL the FE bound to) | **No, not directly** — the backend can only expose its own *instance identity* (boot nonce / port); the FE must compare. |
| Dependency locks | the **build** (Gradle), not any runtime | **No** — runtime; belongs in a local gradle check / hook. |

**Alternative design seed (for the future decision, not adopted here): the unifying layer is agent-tooling,
not a backend endpoint.** Realize Layer A as an extension of the existing `preflight` / `quick_health` MCP
tools — which *already* compute `backendStale` and can already read `/api/status` — aggregating: (1) build
stamp [have], (2) index warmth [have, project from `/api/status`], (3) lockfile drift [new local gradle
probe], (4) FE-binding identity [new, see below]. A backend `/api/debug/freshness` can then own exactly the
*one* source it can see truthfully (index warmth) and stay a real sibling of `/api/health`, instead of a
god-endpoint pretending to know about jars and lockfiles it structurally cannot observe.

**Binding-identity mechanism (kills the #1 masquerade cleanly):** have the backend expose a stable **boot
nonce** (or instance id) on `/api/health`; the FE records it when it first binds, and on every poll asserts
(a) the bound *absolute* base still answers and (b) the nonce is unchanged. If the nonce changes or the
absolute base dies *while a relative `/api` probe succeeds*, that is unambiguous "backend restarted on a new
port" → promote the current silent 15 s `statusLabel` into the **loud blocking "DISCONNECTED — reload"
banner** Layer B already asks for. This is the highest-value single build because #1 cost the most turns and
the passive sensing already exists — it just renders too quietly.

### A.4 Recommended scoping shape (for the author to ratify — no build started)

In rough priority by (turns-saved × buildability), if/when this is scoped:

1. **#1 FE binding** — loud blocking banner + backend boot-nonce identity check. Highest cost-of-pain,
   sensing already present, change is mostly presentation + one backend field.
2. **#2 jar** — promote `backendStale` from advisory flag to a loud line in `quick_health`/`preflight`/
   `dev_start` output (and consider refusing start on mismatch). No new sensing.
3. **#4 lockfile** — a *local* drift check (the CI step run locally, or a pre-merge hook) so a neighbour's
   clobber is caught before merge, not "by chance." New sensing, but small.
4. **#3 index warmth** — likely **already done**; at most, ratify that the degradation banner is loud enough
   and re-verify the original incident is gone. Do not build a new typed-status here without reproducing #3.

**Open questions for the user / author:**
- Re-verify #3 on current `main` — can the "search returns nothing on cold index" experience still be
  reproduced, or was it the stale jar (#2)? This decides whether #3 stays a proof-instance.
- Is the consolidation owner the **MCP/agent-tooling layer** (preflight/quick_health) — per A.3 — or do you
  still want a backend `/api/debug/freshness`? (Recommendation: agent-tooling aggregator + a narrow
  index-warmth-only backend projection.)
- Scope: all four, or the three confirmed legs (#1/#2/#4) with #3 reduced to a re-verification task?

> **These three open questions are now answered by the long-term design in §B** (owner = dev-tooling
> aggregator, not a backend endpoint; scope = the three confirmed legs + #3 as re-verification).

### A.5 External-practice check (targeted, #1 only)

A narrow internet pass was run on the *one* portable, unvalidated piece — the #1 binding-detection
mechanism and reconnection UX — because the other three legs are repo-internal plumbing where external
practice does not apply. It **confirmed the proposed direction without overturning anything** (a useful
negative result: the design rests on conventional ground, not invention).

- **The "boot-nonce" is the well-established *server incarnation / epoch / generation token* pattern.** In
  distributed systems this is the standard way a client detects that a peer restarted: the server attaches a
  per-incarnation token (a unique-per-boot id, or a monotonically increasing generation/epoch — the same
  device as a *fencing token*), the client records it and compares on each call; a mismatch means "the
  server is a new process." Refinement vs §A.3: the token need **not** be random — a monotonic generation
  number is the more common form and additionally lets the client tell *forward* (restarted) from *stale
  request* (older epoch). Sources: [fencing/epoch tokens — distributed lock](https://asrathore08.medium.com/system-design-distributed-lock-821da42054db),
  [incarnation numbers for detectable client/server restarts](https://snehasishroy.com/ensuring-exactly-once-execution-at-scale-in-distributed-systems).
- **Detection vs recovery are separate, and we already own the recovery half.** The epoch is the *detection*
  signal; *recovery* is re-resolving the backend port. The Vite proxy already discovers the port dynamically
  per request (`vite.config.js:130-143`), and `resolveApiEndpoint()` has the discovery chain
  (`http.ts:396-457`) — so the FE could simply **re-run port discovery on fetch failure** instead of trusting
  a captured absolute base forever. Cleanest composite: *epoch detects → re-resolve recovers → banner
  informs*, rather than any one alone.
- **The "loud banner" UX is the recognized consensus, with one refinement: disable write controls too.** The
  established connection-loss pattern is a visible "connection lost / reconnecting" banner **plus disabling
  write controls** (and, for data freshness, a "reload to get latest" affordance) — not a silent empty state.
  This matches Layer B and adds "disable writes while disconnected" as a concrete sub-requirement. Sources:
  [connection-lost banner + disabled writes pattern](https://www.greatfrontend.com/questions/system-design/news-feed-facebook),
  [home-assistant "Connection lost. Reconnecting…" UX issue](https://github.com/home-assistant/frontend/issues/8893).

**Net of the external pass:** no recommendation changed; the #1 mechanism gained a canonical name (epoch /
incarnation token), one refinement (monotonic generation over random nonce; disable writes during the
banner), and confirmation that detection (epoch) and recovery (re-resolve) are independent and we already
hold the recovery half. No further research is warranted for #2/#3/#4 — they are repo-internal.

---

## §B. Long-term design

The decisive result of mapping the codebase (below) is that **637 needs almost no new mechanism.** The
system already owns the exact shape this problem calls for; the masquerade is what happens where that shape
was *not* applied. So the design is a *conformance* design, not an invention — and its scope is set by that
fact, not chosen.

### B.1 What already exists (the seams to conform to, with `file:line`)

Three pre-existing facts reframe the whole tempdoc:

1. **A "reasoned-observable" seam already exists — implemented three times, deliberately *not* unified.**
   Each is the same shape: **typed state · closed reason-code enum · reason→wording (and sometimes →remedy,
   →severity) projection · a `.v1.json` register + a coverage gate** that enforces forward (every code is
   worded) + backward (no dead rows). The three instances:
   - **Lifecycle-readiness** — `LifecycleReasonCode` (`modules/app-api/.../lifecycle/LifecycleReasonCode.java`)
     → `LifecycleSnapshotV1` / `ReadinessComponentView{state,reasonCode,…,stale,stalenessMs}`
     (`.../status/ReadinessComponentView.java:12-14`) → FE `readinessNotice.ts` `CAUSE_ROWS`
     `{code,wording,remedy,severity}` (`modules/ui-web/src/shell-v0/state/readinessNotice.ts:61-200`),
     enforced by `check-readiness-reason-codes` + `governance/readiness-reason-codes.v1.json`.
   - **Search-degradation** — `SearchReasonCode` → `SearchTrace.Degradation`
     (`.../knowledge/SearchTrace.java:61-67`) → FE `searchTraceExplain.ts` wording map, enforced by
     `check-search-degradation-reason-codes` + register.
   - **Verdict** — `SystemHealthVerdict{kind,severity,reasons}` (`shell-v0/state/verdict.ts:63-68`,
     `kind` already includes **`unreachable`** and `transitioning`), derived from readiness, enforced by
     `check-verdict-derivation`.
   They are parallel by design (different producers / altitudes / wire shapes; "two vocabularies" per
   tempdoc 600). **There is no shared base type, and the audit confirms one is not wanted.**

2. **A dev-tooling *aggregator* already exists** — the `preflight` / `quick_health` MCP tools
   (`scripts/dev/justsearch-dev-mcp/server.mjs`). `quick_health` already returns an `ownership` projection
   whose **`backendStale` flag is exactly the stale-jar (#2) signal**, computed by the pure
   `computeOwnershipVerdict()` (`scripts/dev/lib/ownership-verdict.cjs`) from a build-stamp comparison
   (`dev-runner.cjs:498-519` ⇄ running `manifest.head.buildStamp`, `server.mjs:156-169`). The verdict shape
   is already `{verdict, grade, recommendedAction, …}` — i.e. *state + remedy*.

3. **Canonical-authority-and-projection (tempdoc 620) and the resume detector (tempdoc 630) are already
   live.** 620: every fact has *one* canonical owner; consumers **project, never fork/re-derive**, and
   `/api/status` is the canonical lifecycle signal. 630: `KnowledgeServerHealthMonitor` already detects
   suspend/resume (clock-immune Head-PID liveness + wall-clock-gap resume detector) and eagerly
   re-validates on wake.

### B.2 The design: one invariant, each source a reasoned observable at its *owning* layer

The invariant (this is the tempdoc's "root pattern", now stated in the system's own vocabulary):

> **Every state whose staleness can make a consumer mis-diagnose at a higher layer must be a *reasoned
> observable* (typed state + reason code + remedy) emitted at the layer that *owns* the fact, and *projected*
> — never re-derived — to its consumers. A fact may never be observable only by its downstream symptom.**

The masquerade is precisely the violation: a stale fact with a downstream symptom (empty results / "broken")
but **no typed observable at its own layer**. The fix is to close that gap per source, reusing the seam that
matches the source's altitude. Crucially, **the owner differs per source**, which is why a single backend
`/api/debug/freshness` endpoint is the *wrong* shape (it can honestly observe only one of the four — §A.3):

| Source | Owning layer (canonical author) | Conforms to which existing seam | New vs. reuse |
|---|---|---|---|
| **#3 index warmth** | backend (worker compat controller) | search-degradation **and** lifecycle-readiness (`embeddingReady`) | **Reuse — already done.** Typed degradation already flows + renders (§A.1). At most: re-verify loudness; no new code. |
| **#2 stale jar** | dev-tooling (only it sees source-vs-installed) | the ownership-verdict aggregator (B.1 #2) | **Reuse the signal, fix loudness.** `backendStale` already computed; promote it from a diagnostic flag to a *blocking* `preflight` check + a loud `recommendedAction` ("run installDist"). No new sensing. |
| **#4 lockfile drift** | the build (Gradle) | the governance-gate pattern (register + check), run *locally* | **Small new surface.** The drift check exists CI-only (`ci.yml:549-557`); give it a *local* pre-merge/hook surface so a neighbour's clobber is caught at the owning layer, not "by chance." |
| **#1 FE↔backend binding** | split: backend owns its *instance identity*; FE owns the *comparison* | lifecycle-readiness (one new reason code) + verdict (`unreachable` kind already exists) | **The one genuinely-new fact.** Backend emits a monotonic **incarnation token** on `/api/health` (new authoritative fact at its owner); FE records it, and on token-change or absolute-base-dead-while-relative-`/api`-lives, promotes the *already-present but silent* 15 s staleness (`aiStateStore.ts:587-611`, `verdict.ts:206`) into a **loud `unreachable` verdict** (banner + disabled writes, §A.5). Detection = incarnation; recovery = re-resolve port (we already own it). |

### B.3 Layer A (the agent's freshness preamble) = extend the aggregator, project don't fork

Layer A is realized by **extending the existing `preflight`/`quick_health` aggregator**, not a new endpoint.
It already holds #2 (`backendStale`). It gains: **#3** by *projecting* `embeddingCompatState`/`embeddingReady`
from `/api/status` (per 620/622 — read the canonical signal, don't re-derive), **#4** by invoking the local
lockfile-drift probe, and **#1** by comparing the live `/api/health` incarnation to the lease. The output is
the per-source `FRESH | STALE(reason, remedy) | WARMING(eta)` verdict the tempdoc asked for — but assembled
at the layer that can actually *see* all four, in the verdict shape the aggregator already speaks. Layer C
(agent discipline) is then a one-line extension of `verify-don't-guess` ("run the freshness preamble before
debugging behaviour"), with the preamble as its mechanized half.

### B.4 What this design deliberately does NOT build (scope discipline)

- **No `/api/debug/freshness` god-endpoint.** Three of four facts are not the backend's to observe (§A.3,
  B.2). The backend's only honest contribution is the #1 incarnation token (a one-field fact) and the
  already-existing #3 readiness/degradation.
- **No unifying `TypedStatus<S,R>` base type over the reason-code seams.** The audit shows they are parallel
  *by design* (two vocabularies, tempdoc 600); unifying them is structure the problem does not require, and
  would fight a deliberate boundary. We *add instances to* the seams (one new reason code for #1), we do not
  *merge* them.
- **No new staleness-after-sleep detector.** 630 already owns resume detection; #1's re-validation simply
  composes with it (a resume event is one trigger of the same incarnation re-check).
- **No re-build of #3.** It already conforms; building a second typed status there would be the
  representation-fork the codebase explicitly guards against.

The size of this design is small *because the problem turned out to be mostly already-solved* — the work is
closing three specific gaps (loudness for #2, locality for #4, one new fact + loud verdict for #1) by
conforming to seams that exist, not by adding a layer.

---

## §C. Reach of the design (principle, candidate scope, and what NOT to build)

### C.1 This design is an *instance* of two existing principles — conform, don't fork
- It is a new application of the **reasoned-observable seam** (B.1 #1): the staleness sources become typed
  observables with reason codes, joining lifecycle-readiness / search-degradation / verdict rather than
  forming a fourth parallel mechanism. The only governance addition is *one row* (a `binding.*` reason code
  in the readiness register for #1) — the seam's own register+gate then enforce its coverage for free.
- It is governed by the **canonical-authority-and-projection seam** (tempdoc 620): each source is authored at
  its one owning layer and *projected* to the aggregator/consumers (#3 projected from `/api/status`, #2 from
  the build stamp, #1 from `/api/health`), never re-derived. The aggregator (`preflight`/`quick_health`) is a
  *projection point*, not a second source of truth.

Conforming to both is itself the reason the design is small.

### C.2 The principle this problem reveals (name it; do not build it)

> **Observability-at-the-owning-layer (a.k.a. "no silent displacement"): a fact must be legible at its
> source, not only by its shadow. Any state whose failure/staleness produces a *downstream* symptom must be
> a reasoned observable at the layer that owns it — otherwise a proxy, default, cache, or fallback one layer
> up will mask the cause and impersonate a different (usually product-level) defect.**

This is a sharper, more general statement than "fail loud": it names *where* the signal must live (the owning
layer) and *why* the masquerade happens (an intervening proxy/default/cache/fallback). The four staleness
sources are one cluster of instances; the shape recurs anywhere a masking intermediary sits above an
unobserved fact.

### C.3 Candidate scope beyond this tempdoc (recorded, not built)

Where the same shape plausibly applies — and whether code already violates it:

- **Stale worktree base (tempdoc 618 §1).** A base-ref mismatch is observable today only by a confusing
  build/test outcome one layer up — a textbook masquerade. *Likely violation*; `verify-worktree-base` is the
  prose half, an owning-layer observable (assert base contains expected commit, loudly) is the missing
  mechanized half.
- **Fallback/default that masks a degraded mode.** The Vite proxy masking a dead absolute binding (#1) and a
  default search mode masking a degraded one are the in-tempdoc instances; the general risk is *any* silent
  fallback. The `ai-offline-isnt-a-wall` reference case is a cousin (an AI-offline fallback read as "feature
  verified up to the LLM"). *Partial violation* — handled case-by-case, no standing invariant.
- **Telemetry-as-source confusion (tempdocs 622/633).** A health/freshness signal re-derived from an event
  stream instead of read from `/api/status` would fork authority — the same anti-pattern 620/622 already
  name. *Guarded* by those tempdocs; 637's aggregator must stay a projection to avoid re-introducing it.

The *positive* example is instructive: **#3 already conforms** (typed degradation at the owning layer,
projected to a banner) — which is exactly why incident #3 is not reproducible on `main`. The principle is
already partly honoured; the staleness sources #1/#2/#4 are where it is violated.

### C.4 Deliberately separating "recognising" from "building"

Per the scope discipline: **C.2 is recorded as a principle, not turned into a generalized structure now.**
There is no call here to build a meta-register of "all reasoned observables", a unifying base type, or a
cross-cutting "owning-layer audit" gate — the present problem needs only the three targeted gap-closures of
§B. If the candidate scope in C.3 accumulates further confirmed instances (a second worktree-base masquerade,
say), *that* would be the moment to consider a standing check — not before. Recording the principle now is
what prevents the next instance from being debugged at the wrong layer again; building the abstraction now
would be the premature-abstraction failure the AHA/YAGNI discipline warns against.

### C.5 Suggested next step (for the user to ratify)
The design above is general and ready to scope into implementation phases (per priority in §A.4: #1 → #2 →
#4, with #3 a re-verification). No build has begun. Open ratification points remain the same three from §A.4,
now answered by §B but awaiting your sign-off — chiefly: confirm the aggregator (not a backend endpoint) as
Layer A's home, and confirm #3 is re-verification-only.

---

## §D. Pre-implementation confidence pass (2026-06-23, read-only)

Six leverage uncertainties were probed read-only against `main` (no dev stack was running —
`quick_health` returned `running:false` — so the optional live probe was skipped; code reading sufficed).
**Three findings materially correct §B; one shrinks #1's risk to almost nothing; one demotes #4.**

| U | Verdict | Evidence (`file:line`) | Effect on design |
|---|---|---|---|
| **U1** — per-boot identity exists? | **RESOLVED — already exists; #1 detection is nearly free** | `instanceId` is a per-boot UUID minted at every Head start (`modules/ui/.../runtime/RuntimeManifestPublisher.java:111`, on `RuntimeManifest`, `app-api/.../runtime/RuntimeManifest.java:40`), served at `GET /api/runtime/manifest`. **The FE already fetches and caches it** for restart detection (`modules/ui-web/src/api/http.ts:33-39,448-455`). | The "incarnation token" §B.2 #1 calls *new* is **not new** — it exists and the FE already captures it. No backend field needed. Remaining #1 work = FE *compare-on-poll + loud verdict*, plus prod recovery (see U3). |
| **U2** — `backendStale` sound + right gate point? | **RESOLVED — sound, but gate point in §B.2 #2 is WRONG** | Stamp is apples-to-apples: dev-runner computes it once and injects `-Djustsearch.head.stamp`; Java merely echoes it into the manifest (`dev-runner.cjs:498-510,1093` ⇄ `RuntimeManifestPublisher.java:384-395`) — no second computation. BUT `backendStale` is only computed **after launch**, in `quick_health`/`status` (`server.mjs:1598`); `preflight` never calls `buildOwnershipProjection` and `start` pre-launch has no manifest yet (`server.mjs:539,1443-1550`). | **Correction:** #2's loud check must live at `quick_health`/`status`/`reload` (post-launch), **not** at `preflight` as §B.2 #2 said. Also: stamp covers **head dist only**, not worker dist — a small uncovered sibling gap to note. |
| **U3** — dead binding: prod or dev-only? | **RESOLVED — it's a REAL PRODUCT bug, and #1 spans Tauri (Rust)** | Tauri caches the port once (`api_port` command never re-resolves, `modules/shell/src-tauri/src/lib.rs:889-903`) and the FE caches it too; in prod there is no Vite proxy to mask a dead absolute URL (the dev proxy re-resolves per request, `vite.config.js:87-111`). So on restart, prod users hit a dead binding. | #1's loud banner is a genuine **product** feature (good — higher value). But **recovery in prod requires a Tauri/Rust change** (re-invoke `api_port` / watch the manifest's `instanceId`), which §B understated. #1 = FE + Tauri, not FE-only. |
| **U4** — verdict integration + disable-writes seam | **RESOLVED — both small/reuse** | Feeding an incarnation-mismatch into the verdict is a new optional field on `VerdictInput` + an early-exit like the existing `disconnected` branch (`verdict.ts:117-131,145-187`); the `check-verdict-derivation` gate only polices the `retrieval ===` predicate, so it doesn't trip (`check-verdict-derivation.mjs`, `governance/verdict-derivation.v1.json:8`). Disable-writes seam exists: `Composer.submitDisabled` already gates chat on AI-offline (`Composer.ts:104,219`); SearchSurface input needs one small `?disabled` binding added. | Confirms §B.2 #1 FE integration is small. No new register/changeset. |
| **U5** — cheap local lockfile-drift check? | **RESOLVED — NOT feasible; demotes #4** | The only sound drift check is a full `resolveAndLockAll` (1–2 min; transitive closure is opaque to a declared-deps diff) (`LockingConventionsPlugin.kt:40-81`); `lockfile-hint.mjs` is a reminder only, no check. The sound catch already exists as the **pre-merge CI gate** (`ci.yml:549-557`), which *does* catch a stale/clobbered lockfile. | **Correction:** §B.2 #4's "small new local surface" overstates feasibility. A *cheap* per-build local check can't exist. #4 reduces to: run the existing pre-merge resolve **locally before merge** (slow but bounded) or simply rely on the CI gate that already catches it. Lowest-value leg. |
| **U6** — 630 overlap with #1? | **RESOLVED — disjoint, composes cleanly** | `KnowledgeServerHealthMonitor` resume path is purely Head↔Worker (gRPC `reconnect()` + `reindexPersistedRoots()`, `KnowledgeServerHealthMonitor.java:125-152`); nothing FE-facing. | No duplication. A resume-detected `instanceId` change is a natural trigger #1 can reuse — compose, don't rebuild. |

### D.1 Net design adjustments (these supersede the cited §B bullets)
1. **#1 detection is essentially free** — reuse the existing `instanceId` (U1); drop "backend emits a new token" from §B.2 #1.
2. **#1 is a product bug spanning Tauri/Rust** (U3) — recovery needs a Tauri manifest-watch / re-invoke; this is the *one* genuinely non-trivial remaining piece and the main residual unknown (Rust side not yet spiked).
3. **#2's loud check moves preflight → post-launch** (`quick_health`/`status`/`reload`) (U2); note head-dist-only coverage.
4. **#4 demoted** (U5) — no cheap local check; lean on the existing pre-merge CI gate or a slow opt-in pre-merge local resolve.
5. **#3 unchanged** (already conforms); **630 disjoint** (compose via `instanceId`).

### D.2 Confidence rating for the remaining work (0–10, per leg)
- **#1 FE binding — 7/10.** Detection is nearly free (instanceId exists + FE captures it), verdict + disable-writes seams confirmed small/reuse. The −3 is entirely the **Tauri/Rust prod-recovery path** (manifest-watch / re-invoke), which I have not spiked — that's the real residual surprise risk.
- **#2 stale jar — 8/10.** Stamp is sound, integration is small; the only correction (gate point) is now known. −2 for worker-dist coverage and "blocking vs warn" UX to settle.
- **#3 index warmth — 9/10.** Already conforms end-to-end; re-verification only.
- **#4 lockfile — 5/10.** Now well-understood but the news is *negative*: no cheap local check exists, so the leg is mostly "wire/loudify the existing pre-merge gate." Low risk, low value, slightly awkward home.
- **Overall remaining work — 7/10.** The dominant unknown is the Tauri/Rust recovery for #1; everything else is small, reuse-based, and now de-risked. Recommend a short Tauri-side spike (can the shell watch `manifest.json` for an `instanceId` change and signal the webview to re-resolve?) before committing #1's estimate.

### D.3 Neighbour-coordination (active work within ±20 tempdocs, 2026-06-23)
Checked tempdocs 617–657 modified in the last ~5 h + active worktrees in that range. **No blocking
interference; one file to coordinate, one re-verify-after dependency.**
- **636 (worktree `636-leg-arbitration`)** — query-adaptive fusion-leg arbitration. Despite the name
  ("arbitration"/our "verdict"), its files are at the *worker/fusion* layer
  (`adapters-lucene/HybridSearchOps.java`, `configuration/EnvRegistry.java`, `ResolvedConfig*`), **not** the
  FE verdict/shell-v0 files #1 touches — no FE collision. Two light couplings: (a) `EnvRegistry.java` is a
  shared high-churn file — if #2 adds a config key, expect a trivial merge (class-size changeset pattern);
  (b) 636 changes search-leg weighting, so run **#3's cold-index re-verification against post-636 `main`**
  (orthogonal concern — weighting vs blocked-state typing — but verify after it lands).
- **632 (go-public licensing)** — SPDX headers + license-allowlist gate + NOTICE generation. At most trivial
  header-line merges; the allowlist gate only fires on *new dependencies*, and 637's remaining work adds
  none → no bite.
- **629 (data-at-rest-encryption, CLOSED)** — settled, not active. Notable only as confirmation: it surfaces
  at-rest status through the **same CAUSE_ROWS / reasoned-observable seam** §B/§C tells #1 to conform to —
  reinforces conform-don't-fork; its dataDir work keeps `runtime/manifest.json` readable (#2 unaffected).
- **Out-of-range but adjacent (noted, not in the ±20 window):** worktree `612-polish` edits activity-feed
  components (`ActionLedgerView.ts`, `eventRow.ts`) — not #1's files; `604-liveness` has no current
  ui-web/shell-v0/tauri/dev changes. Neither touches #1's verdict/liveness-readout/binding files today.
- **#1's highest-risk surface (`modules/shell/src-tauri`, Rust) has zero neighbour activity** — the one
  un-spiked area is uncontended.

---

## §E. Implementation + validation log (2026-06-23, branch `worktree-637-staleness-legibility`)

All four legs implemented per §B, conforming to the existing seams (no god-endpoint, no new base type).
Commits on the worktree branch; design doc (§A–§D) committed separately on `main` (`94d609cca`).

| Leg | What shipped | Verification |
|---|---|---|
| **#1 FE loud-unreachable** | `verdict.ts` unreachable verdict carries `binding.unreachable` (feDerived, registered); `readinessNotice` mints a loud disconnected notice for `unreachable` (was `degraded`-only) → existing `SearchSurface` banner renders it `tone="error"`; search input + chat submit disabled while unreachable. | ✅ typecheck; ✅ 46 unit tests (verdict/readinessNotice/SearchSurface.degradation, incl. 3 new); ✅ gates `check-readiness-reason-codes`, `check-verdict-derivation`, `check-controls-a11y`, `check-presentation-purity`, `check-search-issuance`, `check-a11y-closure`; ✅ **LIVE BROWSER** (below). |
| **#1 Tauri self-heal** | `lib.rs`: `BackendState.instance_id`; `watch_manifest` detects a per-boot `instanceId` change, force-overrides the first-write-wins cached port, emits `justsearch://backend-restart`; FE (`main.jsx`) Tauri-only listener reloads to re-resolve. +3 Rust unit tests. | ⚠️ **cargo build/test BLOCKED locally** by a Windows Application Control policy (os error 4551 on freshly-built build-script binaries — same class as the scoop-shim quirk; logged to observations). Verified by review + the 3 unit tests (run where that policy is absent — CI/maintainer). FE half typechecks. **Runnable manual e2e**: `cargo test --lib` in `modules/shell/src-tauri`, then a packaged/`tauri dev` run restarting the backend and observing the webview re-bind. |
| **#2 stale-jar loud** | `server.mjs buildOwnershipProjection`: on `backendStale`, prepend a loud `recommendedAction` (run installDist) instead of a buried flag. | ✅ `node --check`; logic verified (no stale jar present this session, so the prepend path stays dormant → FRESH). |
| **Layer A freshness** | `quick_health` returns a `freshness` block (buildArtifact / indexWarmth projected from `/api/status` / feBinding / locks), schema `.passthrough()`. | ✅ `node --check`; **§F post-review: a logic bug (warming index mis-classified FRESH) was found by live `/api/status` verification and fixed**; the corrected mapping is verified against a live BLOCKED_LEGACY index (→ WARMING). See §F. |
| **#4 lockfile** | `lockfile-hint.mjs` now points at the local pre-merge full-resolve + `git diff` drift check (no cheap per-build check exists; CI gate is the backstop). | ✅ `node --check`. |
| **Layer C discipline** | `/dev-stack` skill documents the freshness preamble (run `quick_health` + API ground-truth FIRST on an unexpected negative). No new always-loaded CLAUDE.md rule (rule-bloat gate). | ✅ edited above the generated markers. |
| **#3 cold index** | Re-verify only (already conforms). | ✅ existing `SearchSurface.degradation` tests (index.blocked_legacy → typed banner) pass — same banner family as #1, confirming typed-degradation-not-silent-empty holds. |

**Live browser validation of #1 (the user-visible feature).** Worktree Vite (`:5173`) loaded with `?api_port=1`
(forces an absolute binding to a dead port). Initial state `connecting`: no banner, input enabled (no
false-fire). After the 15 s staleness threshold → verdict `unreachable`: a **loud red `tone="error"` banner**
rendered — *"Backend disconnected. Lost the connection to the search backend — reconnecting automatically.
Reload the app if this persists."* — with cause *"The connection to the search backend was lost"* and the
*Open Health* remedy; the status bar read *"Backend disconnected"*. DOM assertion confirmed
`searchInputDisabled: true`, banner `tone="error"`, `role="status"`. The disconnected masquerade is now a
loud, write-blocking, self-explaining state instead of a silent empty result.

**Honest limits carried forward:** (1) the Tauri/Rust prod self-heal is compile-blocked locally by the OS
Application Control policy — needs a CI/maintainer build to green the Rust tests + a packaged e2e restart
check; (2) the running MCP server process predates the Phase-3/4 `server.mjs` edits, so the *new*
`quick_health` code path could not be run end-to-end through MCP this session (itself a staleness instance —
fitting); instead its inputs + mapping were verified directly against a live `/api/status` (§F), and a
maintainer should re-run `quick_health` after an MCP restart to see the `freshness` block in situ; (3) #2's
stale-jar prepend stays dormant unless an actually-stale jar is present (none this session).

---

## §F. Post-review fixes (2026-06-23, after the §E close-out review)

A critical review + a live `/api/status` check surfaced two substantive issues; both fixed on the worktree
branch (then merged).

1. **Chat-surface disconnected banner rendered the wrong tone (live FE bug).** Broadening `readinessNotice`
   to mint a notice for `unreachable` (Phase 1) lit up a *third* consumer — `UnifiedChatView.renderDegradationBanner`
   — which hard-coded `tone="warning"`. So a dead binding (severity `error`) showed **red on search but amber
   on chat**: under-loud and a violation of the file's own "the two windows cannot disagree" invariant. **Fix:**
   render with `verdictTone(verdict.severity)` (the single tone authority `SearchSurface` already uses), so
   `unreachable`→`error`/red everywhere. +unit test asserting the chat banner is `tone="error"`. (Side benefit:
   the chat surface now also shows the loud disconnect banner — broader coverage.) **Live browser-verified**:
   chat surface bound to a dead port → after the staleness window, `chat-degradation` banner renders
   `tone="error"` (red) with "Backend disconnected" and the composer is `submit-disabled`.
2. **Freshness `indexWarmth` mis-classified a warming index as FRESH (logic bug, dev-tooling).** The Phase-4
   projection checked `if (compat === 'COMPATIBLE' || ready === true)` first. Live verification showed
   `embedding.ready` is `true` *even when* `embeddingCompatState === 'BLOCKED_LEGACY'` (reindexRequired), so a
   genuinely-warming index would have been reported **FRESH** — the precise silent-wrong-state #3 is about.
   **Fix:** check the BLOCKED/REBUILDING compat states FIRST; only fall back to `ready` when `compat` is absent.
   Verified against the live BLOCKED_LEGACY index → now correctly **WARMING**. This is exactly why the review's
   "never executed live" flag mattered: live verification converted a doc-overclaim into a caught bug.

---

## §G. Long-term design for the residual: structural vs. discipline-dependent masquerade-removal

The post-merge alignment review surfaced the one conceptual gap that survives the shipped work. The thesis
(root pattern + Non-goals) is that the masquerade is removed **as a category, structurally** — not by fixing
sources in isolation. Measured against that, the four legs land unevenly:

- **#1 (binding) and #3 (index) achieve *structural* removal.** Their owning layer self-declares on **every
  output a consumer acts on**: the FE re-reads the one verdict on every render (#1), and every search
  response carries the typed degradation (#3). The stale state is *un-missable* — there is no path where a
  consumer acts on the surface without the staleness riding along.
- **#2 (stale jar) and #4 (lockfile) achieve only *discipline-dependent* removal.** #2 surfaces loudly **only
  if the agent runs the `quick_health` preamble** (a *pull*); #4 surfaces via an advisory hint + the CI gate.
  Pull-preambles + habits are the ~70% prose-tier reliability this very tempdoc warns about elsewhere.

The correct long-term shape closes that asymmetry **only where the problem actually bites**, by the same seam
#1/#3 already use — *not* by inventing a new mechanism.

### G.1 #2 — make the dev-tooling layer self-declare staleness inline (the warranted upgrade)
The owning layer for the stale-jar fact is the dev MCP server (it alone holds running-stack truth — 620
canonical-authority). Today its freshness/`backendStale` verdict rides only on the **4 lifecycle/orientation
tools** (`quick_health`/`status`/`start`/`acquire_when_free`) via the 606 ownership projection. The
**stack-touching *action* tools an agent validates with** — `search_query` / `agent_chat` / `api_call` /
`capture_evidence` / `ingest` — return results from the running stack **without** that verdict. So an agent
validating against a stale jar sees a clean result and no staleness: the masquerade, intact, for everyone who
doesn't separately pull `quick_health`.

The design: a **shared `withStaleness(structured, ctx)` helper invoked in each stack-touching handler's
result-build path**, attaching the `backendStale`/ownership verdict inline when stale — the dev-tooling
analogue of "every FE render reads the verdict" (#1) and "every search response carries degradation" (#3).
This **completes the 606 ownership-projection seam** (widening its reach from 4 tools to the action tools),
not forks a new one — the same "complete a seam, not fork it" discipline tempdoc 618 §457/§479 applies to the
dev-stack surface. Scope is deliberately the *stack-touching* tools (where the masquerade bites), **not**
every tool — pushing it onto non-stack tools would be structure the problem does not include.

> **De-risk pass (2026-06-23, read-only) — design corrected + confidence raised.** Investigation of
> `scripts/dev/justsearch-dev-mcp/` resolved all seven implementation uncertainties:
> (Q1) `toToolResult` (server.mjs:397) is a **dumb formatter** with no run-context/IO — so the earlier
> "thread through `toToolResult`" phrasing was mechanically wrong; the real injection is the shared
> `withStaleness` helper above, called where each handler already holds its context.
> (Q2) `backendStale` via `buildOwnershipProjection` is **file-I/O only** (readRunJson + manifest read,
> **no `/api/status` fetch** — that lives in `quick_health`'s handler), so inline injection adds **no HTTP
> round-trip**; inject the cheap `backendStale`/ownership, *not* the full freshness block (index-warmth is
> already carried by #3's typed degradation in the search response).
> (Q3) The action tools already resolve `runJson` via the shared `safeReadRunJson` helper (server.mjs:432),
> so the context is **already in hand** — G.1 is one helper + ~6 one-line call-sites, **not** per-tool
> context plumbing.
> (Q4) The action-tool result branches are top-level `.passthrough()` (e.g. `SearchQueryOutputSchema`
> schemas.mjs:445,455) — adding a field is **schema-safe, zero schema edits**.
> (Q5) Match the existing `...(x ? {x} : {})` convention → **stale-only inclusion** (no clutter on FRESH).
> (Q6) Scope = the 6 stack-touching tools `search_query`/`agent_chat`/`api_call`/`fetch_api_json`/
> `ingest`/`capture_evidence`.
> (Q7) Verification limit: the running MCP server predates any edit, so G.1 can only be exercised live after
> an MCP restart — verify the helper's logic standalone/unit, then maintainer re-checks post-restart.
> **Net: G.1 is a small, seam-conforming change (one helper + ~6 call-sites + 0 schema edits). Confidence 8/10.**

> **IMPLEMENTED (2026-06-23).** Added the exported `withStaleness(structured, {mainRepoRoot,
> callerRepoRoot, callerSessionId})` helper (reads `active.json`, runs the existing
> `buildOwnershipProjection` — file-I/O only — and attaches `ownership` ONLY when `backendStale`, carrying
> the loud "STALE BACKEND … installDist" remedy; stale-only, post-parse attach so no schema interaction,
> fail-open). Wired the 6 stack-touching success returns (`fetch_api_json`/`api_call`/`search_query`/
> `ingest`/`capture_evidence`/`agent_chat`). **Verified:** `node --check` + a standalone fixture test —
> *stale* (lease stamp ≠ manifest stamp) ⇒ `ownership.backendStale=true` + remedy attached; *fresh* ⇒ no
> attach; tool result fields preserved — plus `gradlew build -x test` green. **Residual:** the running MCP
> server predates this edit, so the inline verdict is only visible after an MCP restart (a fitting staleness
> instance) — a maintainer re-runs an action tool against a deliberately-stale jar to see it in situ.

### G.2 #4 — no new structure warranted (the deliberate non-build)
The owning layer for lockfile drift is the build (Gradle), and a clobbered lockfile is **already structurally
prevented from reaching `main`** by the pre-merge CI gate (§A.1 #4 / U5). The only un-closed gap is *local*
"discovered by accident," and U5 proved no *cheap* local check exists (the sound check is a full resolve). So
the correctness invariant (main can't carry a clobbered lock) already holds at the merge layer; the local
hint is the right-sized affordance. **Adding a build-time check here would be structure the problem does not
require** — recorded as a deliberate non-build, the symmetric half of G.1's deliberate build.

Net: the residual long-term work is exactly **G.1** (one seam-completion); **G.2** is a no-op-by-design.
#1/#3 are already structurally complete; the Tauri/Rust #1 self-heal and the MCP-restart re-check are
verification residuals (env-blocked), not design gaps.

### G.3 Reach — the principle this residual sharpens
§C named **observability-at-the-owning-layer**. This residual adds a **delivery clause** the four legs make
unavoidable:

> **Self-declaration at the surface of use.** An owning layer must carry its staleness/ownership verdict
> *inline, on every output a consumer can act on* (a *push* at the point of use) — not only in a separate
> on-demand probe (a *pull*). A pull-preamble is discipline-dependent (~70%); inline self-declaration is
> structural (~100%). The two are not equivalent, and only the inline form removes the masquerade *as a
> category*.

- **Instance, not parallel structure.** #1 (every render reads the verdict) and #3 (every response carries
  degradation) already embody it; this is a refinement of the existing reasoned-observable seam, not a new one.
- **Existing violation (record; fixing it beyond G.1 is out of scope):** the stack-touching dev MCP **action
  tools** return results from a possibly-stale/wrong-owned stack with no inline disclosure — the concrete
  instance G.1 closes.
- **Candidate scope beyond this tempdoc (record, do NOT build now):** (a) the **AI-offline / inference**
  state — is it carried inline on every AI-touching tool response, or only via `status`? (likely a partial
  violation); (b) any agent-facing tool whose result is acted on while a relevant ownership/staleness
  condition is silently true. The 606 `displacedNotice` (pull-*at-next-action*) is a positive partial
  instance already moving toward push.
- **Deliberately NOT generalized now:** no "every tool self-declares every condition" framework. The present
  problem requires only G.1 (stack-touching freshness inline). The principle + candidate scope are recorded
  so the next instance is *recognized*, not so a generalized mechanism is built ahead of need.

---

## §H. Long-term design for the taken-over residuals (design-only; not yet built)

Two residuals remained after §G.1. Taking them over and theorizing the correct long-term shape:

### H.0 Triage — only ONE is an in-repo design problem
- **Residual A — Tauri/Rust #1 self-heal is locally unverifiable.** Root cause is a *machine* policy (Windows
  Application Control blocks executing freshly-built unsigned cargo build-script binaries — os error 4551),
  **not** an in-repo structure gap. No code design can override an OS policy; inventing repo structure for it
  would be "structure for a case the problem does not include." The correct long-term shape is **operational**:
  exercise the Tauri/Rust build in **CI** (manual-dispatch per ADR-0026), where the policy is absent. Recorded
  as operational-CI; **design-null** here.
- **Residual B — §G.1 needs a *silent* MCP-server restart.** This **is** a real design gap, and a striking
  one: the dev MCP server runs **its own stale code** after `server.mjs` (or an import) is edited, with **no
  signal**, until the harness reconnects it. That is the **stale-jar masquerade (#2) recursively applied to
  the agent-tooling layer** — the very tool that surfaces #2's stale jar was itself silently stale (it is why
  §F's bug-discovery was delayed and §G.1 can't be seen in-situ this session). This is the design subject.

### H.1 The design — MCP-server self-freshness (a recursive §G.1)
The dev MCP server is the **owning layer** for the fact "is my own loaded code fresh?" (620 canonical-authority:
only it knows its in-memory code vs the on-disk source). So it must **self-declare** that drift, exactly as
the backend jar (#2) and the index (#3) do:
- At boot, compute a `bootSourceStamp` over its **behavior-bearing source set** — a directory glob of
  `justsearch-dev-mcp/*.mjs` (server + `schemas`/`cli`/`files`/`log`/`paths`) **plus** the entry
  `justsearch-dev-mcp.mjs` and `../lib/ownership-verdict.cjs` (the createRequire'd dependency). `dev-runner.cjs`
  is **spawned, not loaded**, so editing it does not stale the MCP *process* — correctly excluded; node_modules
  excluded. *(De-risk R2: glob the dir, don't hardcode a list — auto-covers future modules, no under-hashing.)*
- *(De-risk R3)* Hash by **file content (sha256), NOT `name|size|mtime`.** `computeHeadDistStamp`'s mtime shape
  fits JAR *artifacts*, but **source** mtimes are touched by `git checkout`/`merge` with no content change →
  mtime would yield false STALE. The set is ~8 small files, so content-hashing per call is negligible.
- *(De-risk R1)* Inject in the **universal `toToolResult` formatter** (all 45 tool returns route through it; it
  is sync and needs no run-context — just the module-level `bootSourceStamp` vs current), **not** the
  stack-gated, 6-tool `withStaleness` seam. This makes self-stale **universal + stack-independent** with zero
  per-tool wiring: if `hashMcpSource() !== bootSourceStamp`, attach an `mcpServerStale` notice **naming the
  concrete remedy** (reconnect the `justsearch-dev` MCP server — session restart; `/mcp reload` when it ships;
  dev `kill -HUP $PPID`, per §H.4) and add an `mcpServer: FRESH | STALE` row to `quick_health`'s freshness block.
  Fail-open.
- **Conforms, does not fork:** it is the **third subject** of the §G.1/§G.3 self-declaration pattern (after the
  backend jar and the index) and the same "emit staleness as a legible verdict" family as 618 — but lands in
  `toToolResult` (universal, context-free), the cleaner seam the de-risk found. New mechanism: none.
- **Scope discipline:** behavior-bearing source only; stale-only attach (no noise when fresh); fail-open.
- **Honest limit it does *not* remove (by design):** the server still cannot restart *itself* — the harness
  owns the MCP lifecycle. But that is the same shape as #2 (the backend cannot un-stale its own jar): the win
  is making the staleness **loud and legible** so the agent/maintainer reconnects immediately instead of
  debugging stale behavior silently. Masquerade removed (legible), remedy stays manual.

> **IMPLEMENTED (2026-06-23).** `server.mjs`: `hashMcpSource()` (content-sha256 of the loaded-module glob —
> the MCP dir's `*.mjs` + the entry + `../lib/ownership-verdict.cjs`; mtime-gated re-hash; `dev-runner.cjs`
> excluded), `BOOT_SOURCE_STAMP` captured at module load, pure `mcpStaleNotice(bootStamp)`, and a fail-open
> attach in the universal `toToolResult` so **every** tool result carries `mcpServerStale` (naming the
> reconnect remedy) when the on-disk source has drifted from boot. **Verified:** `node --check` + a standalone
> test (stamp stable; `BOOT == current`; bogus-stamp → notice w/ remedy; current → null; null → null) +
> `gradlew build -x test` green. **Residual (unchanged):** the running MCP server predates this edit, so the
> notice is only visible after an MCP restart — a maintainer re-runs a tool post-reconnect to see it in situ.

### H.2 What this deliberately does NOT build
- No MCP-server hot-reload / auto-restart (lifecycle is the harness's; auto-restart is out of scope and risky).
- No generalized "every long-lived agent-tooling process self-stamps" framework — only the dev MCP server, the
  demonstrated-cost instance.
- Residual A stays operational-CI (no in-repo structure).

### H.3 Reach — a reflexivity principle the residual reveals
§G.3 named **self-declaration at the surface of use**; this residual adds the subject that makes it reflexive:

> **The legibility tool must be legible about itself.** A tool whose job is to surface staleness (or any
> invariant) is not exempt from that invariant — a long-lived agent-tooling process whose code/artifact can
> drift from its source while it keeps serving must self-declare its own drift, exactly as it declares others'.
> 637 began as "stale *backend* states impersonate *product* bugs"; the residual shows the same shape one level
> up — "stale *tooling* impersonates *correct tooling*."

- **Instance, not parallel structure.** Same `withStaleness` seam + `computeHeadDistStamp` shape; the MCP
  server is simply the third subject.
- **Candidate scope (record, do NOT build now):** other long-lived agent-tooling processes that serve stale
  code after edits with no signal — the `otlp-sink`, the read-only `serve-worktree-fe` server, any future
  long-lived helper. These are **candidate violations** of the reflexivity principle (the Vite dev server is
  largely exempt — HMR re-reads source per request). Recorded so the next instance is recognized.
- **Deliberately NOT generalized now:** the present residual requires only the dev MCP server's self-freshness
  (H.1). The reflexivity principle + its candidate scope are recorded; a generalized "tooling self-stamp"
  framework is premature until a second tooling instance proves the class.

### H.4 External-practice check (2026-06-23, targeted) — MCP/harness reload conventions
A narrow internet pass on the one fast-moving, portable, unvalidated surface (MCP server lifecycle + Claude
Code's MCP handling — where a Jan-2026 cutoff may lag). It **validated the premise and refined the remedy; no
design change**:
- **stdio MCP servers are loaded once at session start and are NOT auto-reloaded** (only HTTP/SSE auto-reconnect
  with backoff). `justsearch-dev` is stdio (`command: node` in `.mcp.json`), so "the running server keeps
  serving old code until a manual reconnect" is real and current — hot-reload-without-session-restart is an
  **open, unshipped** Claude Code feature ([#46426](https://github.com/anthropics/claude-code/issues/46426),
  [#17675](https://github.com/anthropics/claude-code/issues/17675),
  [#36643](https://github.com/anthropics/claude-code/issues/36643)). H.1's "self-declare → manual restart"
  shape is therefore correct, not obviated.
- **MCP has no native "my code is stale, restart me" signal.** It has `notifications/*_list_changed` (tool /
  prompt / resource *list* changes) and `notifications/message` (logging) — neither expresses "process code
  drifted from source" ([MCP spec](https://modelcontextprotocol.io/specification/2025-11-25)). So the bespoke
  inline notice via the §G.1 `withStaleness` seam is the conformant choice; an MCP **logging notification**
  (`notifications/message`, warn) is a viable *secondary* channel if Claude Code surfaces it to the agent.
- **Refinement folded in:** the self-declared notice should name the **concrete remedy** — reconnect the
  `justsearch-dev` MCP server (full session restart today; `/mcp reload` if/when it ships; the community
  `kill -HUP $PPID` `/reload` workaround) — so the agent knows *how* to clear it, not just *that* it is stale.
- **Net:** premise validated (stdio = manual reconnect, upstream auto-reload pending), remedy made concrete,
  logging-notification recorded as an alternative channel. No structural change to H.1.
