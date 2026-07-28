---
title: "792: Stack Currency — Round 3 audit, and the design for measuring stack change"
type: tempdoc
status: "audit complete; design settled; theorized; derisked (2026-07-28). No implementation licensed. Part I is a machine-checked currency audit; Part II designs how a stack change becomes measurable; Part III judges the design's reach; Part IV records the alternatives Part II foreclosed and the assumptions it rests on (§20/§21 are open corrections, not decoration); Part V reports the seven-item derisking pass — §9's mechanism turned out to be already shipped for one axis (623 U7), §11's derivation is a binary not a four-way tier, and two migration hazards are confirmed concrete."
created: 2026-07-28
updated: 2026-07-28
related:
  - 236-dependency-update-audit          # Round 2 (2026-03-09) — this round's predecessor
  - 501-runtime-manifest-design          # the closure rule that decides where stack identity lives
  - 654 (runtime contract)               # projection-not-fork precedent
  - 682 (llama-server build pin)         # declared-vs-realized precedent; already covers the largest gap
  - 644 / 553                            # representation-drift class; realized-capability single reader
  - 664 (baseline-shift convention)      # the floor-relaxation guard this design extends
---

# 792 — Stack Currency: Round 3 audit, and the design for measuring stack change

**Part I** measures how current the stack is. **Part II** designs the thing Part I's backlog cannot
be executed without: a way to tell whether a stack change moved anything. **Part III** judges
whether Part II's shape is an instance of something larger.

Predecessor: `236-dependency-update-audit.md` (Round 2, completed 2026-03-09).

---

# Part I — Audit

**Method:** machine-checked against upstream registries on 2026-07-28 — Maven Central
`maven-metadata.xml`, Gradle Plugin Portal `m2`, crates.io API, npm registry via `npm outdated`,
GitHub Releases API, `nodejs/Release` `schedule.json`. Reproduction notes in the Appendix. No
version claim below is from memory.

## 1. Verdict

**The platform layer is current. The library layer is not, and the mechanism meant to keep it
current has never once worked.**

| Layer | Verdict | Evidence |
|---|---|---|
| Gradle wrapper | **Current** | 9.6.1 = latest (2026-06-26) |
| JDK toolchain | **Current** | 25 (LTS); latest patch `jdk-25.0.4+7` 2026-07-27 |
| Node | **Current (deliberate LTS)** | CI pins 24.14.0; v24 "Krypton" Active LTS → maintenance 2026-10-20, EOL 2028-04-30. v26 is not LTS until 2026-10-28. |
| Rust / Tauri | **Current** | tauri 2.11.5, tauri-build 2.6.3, wry 0.55.1, tao 0.35.3, tokio 1.53.1, serde 1.0.229 — all == crates.io latest; rustc 1.95.0 |
| npm (root, shell) | **Current** | root 2 patch-level; shell 0 outdated |
| CI actions | **Mostly current** | 3 majors available, each with an open Dependabot PR |
| **Java libraries** | **Stale — 32 behind, 6 across a major line** | §3 |
| **Vendored native payloads** | **Stale — llama.cpp ~4 months behind** | §4 |
| Python (jseval) | Floor-pinned, unbounded, no lock | §6 |
| Model stack | Not assessed — quality question, not a version question | §7 |

## 2. Root cause: Dependabot has never bumped a single Java *library*

`.github/dependabot.yml` declares `package-ecosystem: gradle`, weekly, group `gradle-deps`,
`patterns: ["*"]`. It runs, it merges — and **every Gradle PR it has ever opened bumped only Gradle
*plugin* IDs. Not one `[libraries]` entry in `gradle/libs.versions.toml`.**

| PR | Date | Changed |
|---|---|---|
| #8 (merged `25cdd035`) | 2026-06-30 | openrewrite, develocity, spotless, protobuf-plugin, dependency-analysis, spotbugs — **6 plugins, 0 libraries** |
| #61, #102, #186 (closed) | Jul | #186's body: 6 plugins, 0 libraries |
| #306 (merged `b505fed6`) | 2026-07-28 | spotless, spotbugsPlugin, kotlin, dependencyAnalysis, ben-manes — **5 plugins, 0 libraries** |

So the weekly Gradle lane is live and green and merged as recently as today, while Lucene, Tika,
gRPC, Jackson, ONNX Runtime, protobuf and ~25 others drift. **The lane's green signal is actively
misleading** — it reads as "dependency maintenance is covered."

**Candidate causes — not discriminated; this is the next investigation, not a conclusion:**

1. **Dependency verification.** `gradle/verification-metadata.xml` carries 1,795 `<component>`
   entries. Dependabot cannot regenerate checksums. If its updater probes resolution before opening
   a PR, library candidates would be dropped while plugin-portal coordinates — resolved through
   `pluginManagement`, a different path — survive.
2. **Version-catalog `module = "g:a"` shorthand.** Every `[libraries]` entry uses that form rather
   than separate `group`/`name` keys. If dependabot-core's TOML parser skips it, the observed
   plugins-only behaviour follows exactly.

Both are cheap to discriminate: hand-author one library-bump PR and see whether verification is the
blocker; and/or convert one entry to `group`/`name` form and watch the next weekly run.

**Mitigation available today:** `com.github.ben-manes.versions` is already applied
(`build.gradle.kts:11`). Nothing schedules `dependencyUpdates`.

## 3. Java libraries — 32 behind, 6 across a major line

Lockfiles (`modules/*/gradle.lockfile`) agree with the catalog, so these are the versions actually
resolved, not merely declared.

### 3.1 Major-line gaps

| Dependency | Pinned | Latest | Round-2 disposition | Status now |
|---|---|---|---|---|
| `org.junit:junit-bom` (+ platform) | 5.14.4 / 1.14.4 | **6.1.2** | Deferred — "requires Java 17 min" (236 §158) | **Blocker gone** — toolchain is 25 (`JvmBaseConventionsPlugin.kt:68`) |
| `io.javalin:javalin` | 6.7.0 | **7.2.2** | Deferred — "javax → jakarta audit" (236 §339) | **Blocker gone** — 236 §380 found *zero* `javax.servlet` usage and called v7 "low-effort" |
| `io.netty:netty-bom` | 4.1.131.Final | 4.2.16.Final | **Deliberate 4.1-line hold** (236 §36/§72) | Hold stands; the 4.1 line itself is behind (**4.1.136.Final**) |
| `build.buf:protovalidate` | 0.7.0 | **1.2.2** | not covered | latest 0.x is 0.14.0 |
| `com.github.oshi:oshi-core` | 6.10.0 | **7.4.2** | not covered | latest 6.x is 6.12.0 |
| `io.soabase.record-builder` | 52 | **53** | not covered | annotation processor |

Both headline Round-2 deferrals were deferred on *named* blockers, and Round-2's own evidence has
since dissolved both. They should be re-scoped, not re-deferred.

### 3.2 Same-line gaps with runtime reach

`onnxruntime` / `_gpu` 1.24.3 → **1.28.0** · `grpc-core` 1.79.0 → 1.83.0 · `protobuf-java` 4.33.5 →
4.35.1 · `lucene-core` 10.4.0 → 10.5.0 · `tika-core` 3.2.3 → 3.3.2 · `jackson-databind` 3.1.0 →
3.2.1 · `logback-classic` 1.5.32 → 1.6.1 · `log4j-to-slf4j` 2.24.3 → 2.26.1 · `opentelemetry-*`
1.60.1 → 1.64.0 · `opentelemetry-instrumentation` 2.26.0-alpha → 2.30.0-alpha · `sqlite-jdbc`
3.51.2.0 → 3.53.2.1 · `netty-bom` (within 4.1) → 4.1.136.Final.

### 3.3 Remainder and current set

Remaining behind: `archunit` 1.4.2 · `mockito-core` 5.23.0 · `jackson-annotations` 2.22 ·
`slf4j-api` 2.0.18 · `error_prone_core` 2.50.0 · `spotbugs` (tool) 4.10.3 · `commonmark` 0.29.0 ·
`lz4-java` 1.11.1 · `typescript-generator-core` 4.1.1 · `dev.cel:cel` 0.13.1 · `handlebars` 4.5.3 ·
`jinjava` 2.8.4 · `guava` 33.6.0-jre · `json-schema-validator` 3.0.6 · `commons-codec` 1.22.0.

At latest (14): jqwik · assertj · logstash-logback-encoder · findsecbugs · djl-tokenizers ·
directory-watcher · victools jsonschema-generator · HdrHistogram · commons-math3 · commons-text ·
protobuf-gradle-plugin · kotlin · spotless · dependency-analysis.

Terminal: `net.jcip:jcip-annotations 1.0` — last released 2006, no successor. Compile-only
annotations; harmless, but dead, not current.

### 3.4 Gradle plugins

All at portal-latest **except** `net.ltgt.errorprone` **4.0.1 → 5.1.0**. (Checked on the Gradle
Plugin Portal; Central's mirror of that coordinate stops mid-history and reports a misleadingly old
latest — see Appendix.)

## 4. Vendored native payloads — the largest single gap

| Payload | Pinned | Latest | Gap |
|---|---|---|---|
| **llama.cpp `llama-server`** | **b8571** (2026-03-28) | **b10167** (2026-07-28) | ~4 months |
| Tesseract OCR | 5.5.0 (`5.5.0.20241111` UB-Mannheim build) | 5.5.3 (2026-07-24) | 3 patches; the *build* is from Nov 2024 |

Pin sites: `modules/ui/build.gradle.kts:357` (+ sha256 at :362) and
`packaging/runtime/tesseract-windows.v1.json:6`. Both flow into the installer, so a bump is a
supply-chain event — new sha256, a `packaging/signed-mirrors.v1.json` entry, and the per-pin-bump
procedure in `docs/how-to/cut-a-release.md`. That friction is presumably why it hasn't moved; it is
also why it must be scheduled rather than left to opportunity. **No automation covers a hardcoded
release-asset URL** — Dependabot has no ecosystem for one.

## 5. Frontend & CI

**ui-web** — 21 outdated, all minor/patch, and the whole set is already proposed in open Dependabot
PR **#304**. This lane works. Exceptions worth separate judgment: `typescript` **6.0.3 → 7.0.2** (a
major line, outside #304's range); `virtua` 0.49.1 → 0.50.0; `knip` 6.20.0 → 6.29.0.
`@types/dompurify` is a phantom — installed 3.2.0 exceeds published latest 3.0.5 because dompurify
ships its own types now.

**npm audit** — root 14 findings (9 high), ui-web 6 (5 high), 0 critical. Both sit *below* the
ratchet baseline, so `check-npm-audit-ratchet.mjs` passes — but that baseline was generated
**2026-02-20** and gates only `high,critical`. Five months of slack has accumulated in a mechanism
designed to have none. All live highs are transitive and all are cleared by #304's range: `postcss`,
`fast-uri` (the existing `overrides.fast-uri` no longer covers it), `picomatch`, `brace-expansion`.

**CI actions** — checkout@v7, setup-java@v5, setup-python@v7, upload-artifact@v7, cache@v6,
codeql-action@v4, rust-cache@v2 all at latest major. Three majors sitting unmerged: setup-node v6→v7
(#228), download-artifact v7→v8 (#59), gitleaks-action v2→v3 (#60).
`contributor-assistant/github-action` is at latest, but upstream's last release was 2024-09-26 — a
lightly-maintained dependency on the CLA path.

## 6. Python (jseval)

`scripts/jseval/pyproject.toml` declares `requires-python = ">=3.11"` and floor-only constraints
with **no upper bounds and no lockfile**; CI pins 3.13, the local interpreter is 3.14.4. This is not
*stale*, it is *unpinned* — a different and arguably worse posture: eval results are not reproducible
across machines or across time, and an upstream breaking release lands silently in the measurement
harness. Given jseval produces the numbers behind search-quality decisions, a lock is worth more
here than any single bump in §3. **Part II makes this structural, not incidental** — an unpinned
measurement harness is a stack axis that cannot be recorded.

## 7. Not assessed this round

- **Model-stack currency.** `docs/reference/model-inventory.md` is the authority. Judging the active
  model set is a *quality* question with eval evidence, not a version-string question; it belongs to
  the `/search-quality` register.
- **Whether any §3 gap carries a live CVE.** npm was audited; the Java side was not.

---

# Part II — Design: making a stack change measurable

## 8. The problem, stated precisely

A dependency bump is a change to **the stack underneath the measurement**. Our measurement apparatus
models corpus, models, policy, eval protocol, git, and hardware. It does not model the stack.

Two consequences, both load-bearing for Part I's backlog:

1. **No attribution.** `git_sha` is the only proxy. It moves on every commit, so it can never
   separate "the bump did it" from "the code did it."
2. **Incomplete coverage.** The sha256 pin binds llama-server to git *for the installer*, but the
   eval path resolves a **shared staged cuda12 binary from the main checkout**
   (`branch-safety.md`). Tempdoc 682 built `LlamaServerBuildCheck` — expected-vs-actual — precisely
   because those diverge. CUDA/cuDNN natives and the OCR runtime are outside git entirely.

So today, running the §3 backlog produces numbers we cannot attribute: the exact
correlation-as-causation failure `interrogate-results` names.

**Framing.** This is **change-detection**, not optimization. Almost no bump improves a measured
number; the null is "nothing moved", most bumps confirm it, and the value is entirely in the rare
one that doesn't. The design must buy *cheap sensitivity to change*, not precision on effect size.

## 9. Where stack identity belongs — decided by an existing rule, not by preference

Tempdoc 501 §6 states a closure rule, mechanically enforced by
`scripts/ci/check-runtime-manifest-closure.mjs`: every answer to *"how does non-JVM consumer C find
runtime fact F?"* must be *"F is a field on the runtime manifest."* jseval is a non-JVM consumer.
"What stack is this process running" is a runtime fact.

**Therefore stack identity is a field on the runtime manifest.** This is not a new decision; it is
the existing decision applied. The alternative — jseval scraping versions for itself — is the
representation-fork the 553 class names, and CLAUDE.md's projection-vs-fork discipline forbids it.

Three existing precedents fix the *shape*, so nothing here is invented:

- **`RuntimeContract` (654)** — "projection, not fork": a coarse umbrella that *reads each
  constituent version from its existing single source* and "invents no version of its own." Stack
  identity takes the same stance: it projects, it does not re-derive.
- **`AiInfo.serverBuildExpected` / `serverBuildActual` (682)** — the declared-vs-realized pair,
  where divergence is the signal. This already covers the single largest gap in Part I §4.
- **`ModeInfo.intent` / `realized` (657)** — the same idiom a third time.

**Scope of what the manifest carries.** Not an SBOM. The manifest carries the axes that can *move a
measured number* — the JVM/toolchain, the Worker's retrieval-relevant runtime, the ONNX Runtime and
its native provider, the llama build (project 682's field; do not duplicate it), the OCR runtime,
and the measurement harness's own interpreter/dependency lock (§6). Enumerating all ~50 coordinates
is a licensing/notice concern, and that tooling already exists.

**Why not fold this into `RuntimeContract`.** Different reason to change. `RuntimeContract` is a
*promise*: coarse, external, bumped only on a break. Stack identity is *composition*: fine-grained,
internal-facing, changes on every bump. Per AHA — only unify what shares a reason to change. Folding
them would make every dependency bump look like a contract event.

## 10. Identity is question-relative — the seam already exists three times

This is the part that decides whether §9 helps or paralyzes.

Adding a stack axis to one global identity hash would mean **every bump orphans the σ envelope** —
fresh cohort, no envelope, no significance test, N repeat runs per bump. Unaffordable. That would
kill the design.

The resolution is already in the codebase, discovered independently three times and never named:

| Site | Slice | Why it differs |
|---|---|---|
| `manifest.py:212` `_compute_cohort_hash` | full config surface minus volatile fields | scopes the non-determinism envelope |
| `bisection.py:63` `_COHORT_IDENTITY_AXES` | 9 named axes | what a metric shift can be *attributed to* |
| `release.py` `config_cohort_key` | config-*global* subset; excludes dataset family + corpus-dependent fingerprints | including them "would refuse every multi-corpus release (the U1 finding)" |
| `release.py` `_MODEL_EXECUTION_FLAGS` | strips `*_gpu` from model identity | device is context, not identity |

Each was derived painfully and locally. Together they say something none of them says alone:
**there is no single "are these runs comparable?" question, so there is no single identity.**

**Design.** Declare the slices in one place; each call site projects from it. This is not a
framework — it is the shape `preflight.realized_engine_set` already established as "the one
realized-capability reader... instead of each re-deriving [it] with a different predicate (tempdoc
644 — the representation-drift class, 553)."

The rule the declaration must encode, and the reason it is worth declaring at all:

- **Attribution slice** — always carries stack. Free, and it is what bisection needs.
- **Envelope slice** — carries stack *only for axes the stack can reach*. A JUnit bump must not
  invalidate a relevance envelope; an ONNX Runtime bump must.
- **Release-composition slice** — carries stack *identity*, excludes stack *execution context*,
  exactly as it already does for models.

Doing this by hand at three sites, for one new axis, is precisely how the drift in the table above
happened. The present change forces an edit at all three regardless — which is what makes declaring
them proportionate now rather than speculative.

## 11. Routing: derive reachability, declare only what the graph cannot see

Measurement is rationed. It cannot run in public CI: the self-hosted `justsearch-perf` runner is
`workflow_dispatch`-only by ADR-0026's runner-availability rationale and today runs only docs-lint,
and jseval needs the shared single-owner GPU stack. So *which* bumps get measured must be a
mechanical property, not a per-PR judgment call that decays under volume.

**The build graph already knows most of it.** The lockfiles record per-coordinate configuration
membership — a coordinate present only on `testRuntimeClasspath` cannot reach a measured number; one
on the Worker's `runtimeClasspath` can. Reachability is therefore *derived*, with a small declared
list of exceptions for what the graph cannot see: annotation processors that emit runtime code, deps
whose effect arrives through native libraries, and the vendored payloads (§4) that appear in no
dependency graph at all.

Precedent for derive-over-declare is in the same gate that enforces §9:
`check-runtime-manifest-closure.mjs` derives its allowlist from the producer's source "instead of
being a hardcoded literal… no drift between the source-of-truth and the rule allowlist."

**What a reachability class buys is evidence, not ceremony.** The cheapest instrument runs first and
should terminate most bumps: *does the resolved transitive closure change at all?* A bump with an
identical closure is a different risk class from one that pulls a new Netty. Only what survives that
filter, and is reachable, earns a live-stack run.

**Batching follows from the same property.** Batch within a reachability class, never across it.
Where attribution is worthless (test/build-only), batching is free. Where attribution is the entire
point (retrieval and inference reach), batches destroy it.

## 12. A stack change is never a floor-relaxation justification

`baseline_shift.py` already ports the changeset-justification convention into jseval's ratchets, and
its own header records the incident that motivated it: a relevance floor "re-baselined in the same
recompose… an unguarded relaxation, laundered through a re-pin command with no safeguard."

A steady stream of bumps is an *engine* for that failure: each nudges a metric, each re-pin is
individually defensible, and the floor walks downhill. The design adds one rule: **a stack change
cannot license relaxing a floor.** If a bump pushes a metric past a floor, that is a finding to
investigate or revert.

Predictable evasion, named inline: *"the new version is simply tuned differently, so the baseline
should follow it."* That sentence is the laundering, restated.

## 13. What this design orphans

Deletion belongs to this tempdoc's work, not a later sweep.

1. **`release._MODEL_EXECUTION_FLAGS`** — an exclusion tuple that exists *only* because stack facts
   (`embed_gpu`, `splade_gpu`, `ner_gpu`, `reranker_gpu`) had no record of their own and were filed
   under models. With a stack record, both the misfiling and the tuple go.
2. **`model_fingerprints.realized_engines`** and the `*_gpu` keys (`run.py:80`,
   `ratchet_kernel.py:120`, `release.py:387`) — execution/stack facts keyed under models. They move.
3. **Per-call-site hand-rolled axis lists** at the three §10 sites, once the slice declaration lands.

**Explicitly not orphaned:** `preflight.realized_engine_set` stays. It is already the correct single
reader; only the filing location of its output moves. Removing it would recreate the drift it fixed.

## 14. Scope refusals

Named so a later reader can see they were decided, not overlooked:

- **No new benchmark harness.** jseval covers the measurement; the gap is identity, not capability.
- **No per-bump CI evaluation.** Infeasible (§11), and designing for it would be fiction.
- **No SBOM on the runtime manifest.** Notice/licensing tooling already enumerates dependencies.
- **No general identity framework.** §10 declares slices that already exist; it does not build
  machinery for slices that don't.

## 15. Sequence

1. Discriminate the Dependabot Gradle cause (§2) — without it, §3 regenerates.
2. Land stack identity on the runtime manifest (§9) and the slice declaration (§10), absorbing §13's
   orphans in the same change.
3. Merge the sitting PRs: #59, #60, #228, #304. Re-baseline the npm audit ratchet after #304.
4. Sweep the derived not-reachable class as one batch (§11).
5. Use **ONNX Runtime 1.24.3→1.28.0, Lucene 10.4→10.5, and llama b8571→current** as the validation
   set for the measurement itself — three changes we independently expect to move something. If the
   apparatus cannot detect a shift across ~4 months of llama builds, the apparatus is wrong, and
   that is worth learning where we know the answer is not "no change."
6. Re-scope JUnit 6 and Javalin 7 (§3.1 — both Round-2 blockers are gone).
7. Lock jseval's Python dependencies (§6).
8. Schedule `dependencyUpdates` until §2 is fixed.

---

# Part III — Reach

## 16. The principle

> **Identity is a question-relative projection, not a global property. A system that compares runs
> needs declared identity slices — one per comparison question — not one identity.**

This is not proposed; it is **observed**. §10 documents four independent instances already in the
codebase, each derived locally, none aware of the others, with no shared vocabulary. The `U1
finding` — that including corpus fingerprints "would refuse every multi-corpus release" — is the
principle discovered the expensive way.

**It is an unregistered instance of a registered class.** CLAUDE.md's projection-vs-fork rule and
the 553 representation-drift class already govern "a second authority that will drift," and
`governance/execution-surfaces.v1.json` registers the search-execution instance. Run-identity slices
are the same class, unregistered.

## 17. Where else it applies

- **The search-quality register.** Every claim of the form "run A beats run B" rests on an identity
  slice that today is implicit in whichever comparator produced it.
- **The agent-utility campaign (782 → 791).** 791 exists to make a preregistration freeze "ONE
  coherent protocol instead of five conflicting ones" — that *is* this decision, in campaign
  vocabulary. Cell/cohort comparability across arms, strata, and scales is a slice choice.
- **Corpus certification (707)** and the certification policy's subset-awareness — same shape.
- **UI visual-regression baselines**, where "same screen" is a slice over viewport, theme, and font
  stack.

**Existing violation:** the three hand-rolled slices in §10. Naming them is the point; consolidating
beyond what §10 requires is not licensed here.

## 18. What would show the principle earning its keep, and when to retire it

**Earning its keep:**

- A stack change that moves a metric is attributed to a stack axis on first investigation, rather
  than after a bisection detour through unrelated commits.
- No re-baseline changeset cites a version bump as its justification (§12).
- An envelope survives a bump in a class that cannot reach it — i.e. the slice split does the work
  §10 claims, instead of every bump forcing re-establishment.

**Retirement condition:** if across roughly four bump cycles no metric shift is ever attributed to a
stack axis, *and* no envelope is ever wrongly invalidated, then the slice declaration is apparatus
rather than structure. Collapse it back to one hash and delete it, along with the stack axes that
never discriminated anything.

## 19. A second, smaller principle

> **Derive the allowlist from the graph that already knows; declare only what the graph cannot see.**

Already practised by `check-runtime-manifest-closure.mjs` (allowlist derived from producer source to
prevent drift) and adopted by §11 for reachability. **Retirement condition:** if the declared
exception list grows to rival the derived set, the derivation is reading the wrong source, and the
right response is to change the source — not to keep extending exceptions.

---

# Part IV — Theorization

Part II settled *a* design. This part records what it foreclosed, what it assumes, and the framings
that would have produced a different answer. Nothing here is licensed work; it exists so a later
reader can see which choices were made rather than defaulted into.

## 20. The dominant failure mode may be categorical, not statistical

Part II is built around *"did a number move."* But the one bump-induced failure this repo has
actually recorded was not a metric shift — it was a crash: llama-server drift silently invalidated a
flag's syntax, and bare `-fa` was rejected at startup (374 §3130). The same shape recurs across the
plausible list: a Jackson serialization change, a Tika parser behaving differently on one file type,
a Lucene codec change that only manifests on rebuild. None of these move nDCG@10 — they break, or
they change one document.

If categorical failures dominate, then the reachability classes of §11 should route bumps to
*different instruments*, not to different amounts of the same instrument. Replay and differential
testing (`grep_replay.py`, `shadow_eval.py` already exist) detect "the output changed for this
input" far more cheaply and far more sensitively than an aggregate metric ever will. An aggregate
metric is, by construction, the *least* sensitive detector of a change that affects few documents.

This is the strongest candidate correction to Part II, and it is testable against history: the §15
validation set (ORT, Lucene, llama) should reveal which instrument actually catches what.

## 21. The unexplored fork: detect-then-attribute vs. measure-then-merge

Part II implicitly chose **measure-then-merge** — classify each bump, require evidence proportional
to its class, then land it. The alternative was never argued:

**Detect-then-attribute.** Let bumps land unmeasured. Run a standing stack-heartbeat evaluation on a
cadence. When it trips, use `bisection.py` plus the new stack axis to attribute retrospectively.

The economics favour the second more than is comfortable: cost becomes N runs per *period* rather
than per *bump*, which is the right shape under §11's rationing constraint; it uses machinery that
already exists rather than machinery to be built; and the stack axis — the expensive part of Part
II — is arguably *more* valuable here, because retrospective attribution is exactly what it enables.
What it costs is time-to-detection (a bad bump lives on `main` for up to one period) and a bisection
run when the heartbeat trips.

The two are not exclusive: reachability classes could gate only the small set that can reach
inference or retrieval, with everything else falling to the heartbeat. That hybrid is probably the
real answer, and Part II should not be read as having ruled it out.

## 22. A second, orthogonal reachability axis: untrusted input

§11's axis is *"can this reach a measured number."* There is a second axis Part II does not model:
*"does this process bytes an attacker can influence."* The two sets are different.

For a loopback-only, local-first application, most transitive CVE classes — DoS via crafted request,
path traversal on a server, host confusion in a URL parser — have no attacker in the threat model.
That is why Part I's audit counts look more alarming than they are. But the extraction and parsing
layer is the genuine exception: it consumes arbitrary user files, including files a user did not
author. Tika and its parser stack, image codecs, the PDF path, and the OCR runtime are the subset
where lag is a real exposure rather than a hygiene score.

Naming this axis changes priority ordering, and it cuts across §11's: extraction ranks high on both
axes; the transport and storage layers rank high on measurement reach and low on input reach; test
and build tooling ranks on neither. A design that models only one axis will systematically
mis-prioritise the other.

## 23. Currency is a means, not an end

Part I treats "behind" as a defect. That premise deserves challenge. The ends are three: exposure
(§22), compounding migration debt, and specific upstream fixes we want. A deliberate posture of
*"lag by N, except for the untrusted-input set"* is coherent, cheaper, and honest — and it is a
better fit for a pre-1.0 project than continuous currency.

Under that framing one item reclassifies sharply. Four months behind on llama.cpp is not hygiene
debt; upstream moves quickly on inference throughput and quality, so it is closer to *unclaimed
product improvement*. That is a different argument for the same action, with a different priority,
and it belongs to the product lane rather than to a dependency sweep.

## 24. Introducing the stack axis is itself a stack change

The unglamorous risk that would bite first. Every existing baseline and composed release was formed
without a stack axis. Once the axis exists, "absent" and "populated" are different values, so every
historical baseline becomes formally non-comparable to any new run — which forces exactly the
wholesale recomposition §12 says a stack change must never justify.

The design needs a declared transition stance before implementation, not after: either grandfather
absent stack identity as *unknown-but-comparable* (accepting that pre-axis runs can never be
attributed), or take one deliberate, changeset-justified recomposition and date it. Drifting into
this unstated is how the guard in §12 gets breached by the very change that introduces it.

## 25. A record that never fires becomes decoration

Tempdoc 682's expected-vs-actual llama build divergence is recorded, surfaced on the manifest — and
only `LOG.warn`s, deduplicated (`ServerPropsOps.java:92`). Nothing fails. It is a record, not a
guard.

A stack fingerprint inherits that fate by default, and `retire-with-a-sweep` names the consequence:
residue outliving its reason becomes false authority. So the fingerprint needs a firing condition
theorized alongside it. Candidates worth weighing: divergence between declared and realized stack
*invalidates a measurement run* rather than warning; and a comparison between runs whose stack
differs within a reaching class *refuses* rather than silently averaging — the same stance
`realized_engines` already takes for a silently-degraded engine set.

## 26. Sensitivity-selected canaries instead of full evaluations

For change-*detection*, a random evaluation set is inefficient. The informative queries are the ones
near a decision boundary — fusion near-ties, rerank order flips, chunk-merge thresholds — where a
small change in scoring produces an observable change in output. A canary set selected for
sensitivity could detect stack change at a fraction of a full run's cost, and the machinery to
select one exists (`spot_check.py`, `counterfactual.py`, `corpus_query_strata.py`,
`drift_calibration.py`).

The risk is the standard one for canaries: a frozen set becomes the thing that is optimised for, and
stops representing the distribution. Any canary set needs a re-selection cadence, or it decays into
a ritual.

## 27. Reachability by classpath over-approximates

§11's derivation has a weakness worth stating plainly: nearly everything on the Worker's runtime
classpath is "reachable" by that test, including logging. The derived class may therefore be close
to useless on its own. Two refinements to weigh later: intersect classpath membership with
used-versus-declared analysis (the dependency-analysis plugin already computes it), or accept the
over-approximation and let the transitive-closure diff do the actual discrimination. If the second
is true, the closure diff is the primary instrument and the classification is decoration — which
would simplify §11 considerably.

## 28. Reproducibility is the limit case

The correct answer, in the limit, is not to *record* what stack ran but to make any historical stack
*re-runnable* — at which point attribution stops being a record-keeping problem and becomes an
experiment. That is out of reach here: Windows, GPU drivers, vendored native payloads, and no
environment lock. Naming it explains why fingerprints are the settled compromise rather than the
ideal, and gives a target if hermetic environments ever become cheap.

## 29. A broader principle candidate: a green signal should declare what it does not cover

Distinct from Part III's identity principle, and arguably the wider one.

Part I §2's finding is not really about Dependabot. It is about an automation that runs, passes, and
covers far less than its passing implies. The same shape appears repeatedly:

- the npm-audit ratchet passes on a five-month-old baseline that gates only two severities;
- `docs-lint` runs on a runner whose availability the repo itself treats as unreliable;
- retired discipline gates survive as prose that reads like enforcement (`independent-review`,
  `ux-audit-closure`);
- the tier register's `prose-only` rows carry a stated ~70% adherence, but a passing build says
  nothing about them either way.

In each case the *absence of a red signal* is read as coverage. Candidate invariant: **an automated
signal declares its own scope, and that declaration is itself checkable.** The repo's register
culture is already the right substrate — this would be a register of what each lane claims to cover,
cross-checked against what it demonstrably touches.

**Evidence it would earn its keep:** a coverage gap gets found by reading a declaration rather than
by an audit like Part I. **Retirement condition:** if the declarations are authored once and never
consulted when a signal is trusted, they are documentation of documentation — delete them.

## 30. Follow-up worth separating

**#793 — discriminate the Dependabot Gradle library gap.** Small, concrete, separable from
everything above, and it blocks the value of the rest: until libraries flow, the measurement design
has almost nothing to measure. Two candidate causes, both cheap to test (Part I §2).

---

# Part V — Derisking pass (2026-07-28)

Seven uncertainties were named before implementation and investigated read-only. Two produced
corrections to Part II; two confirmed hazards that must be handled *at introduction* rather than
later; one materially reduced the estimated cost.

## R1 — Why Dependabot never bumps a Java library — *partially resolved; hypotheses re-ranked*

**Cause 2 (the `module = "g:a"` shorthand) is now strongly disfavoured.** Dependabot demonstrably
parses and *edits* `libs.versions.toml` — PR #306 rewrote five `[versions]` entries and one inline
`[plugins]` version. A parser that could not read the file could not have done that.

**A third cause emerged and now leads.** Upstream `dependabot/dependabot-core` issue #12557
documents that Gradle **dependency lockfiles combined with a version catalog** is a known-fragile
combination (reported 2025-07, closed via PR #12853). The reported symptom differs from ours — there
the TOML updated but the lockfile did not — but it establishes the interaction as a real failure
surface.

The internal correlation is strong and, as far as this evidence goes, exact:

| Coordinate class | In a `gradle.lockfile`? | Ever bumped by Dependabot? |
|---|---|---|
| spotless, spotbugs, dependency-analysis, ben-manes, protobuf-plugin, kotlin **plugin marker** | no | **yes** |
| lucene-core (12 lockfiles), grpc-core (10), and every other `[libraries]` entry | yes | **never** |

A plausible reading is that after the #12853 fix, Dependabot skips coordinates it cannot
lockfile-reconcile rather than opening a PR that would break the lock. **Cause 1 (dependency
verification) is not excluded** — `verification-metadata.xml` was present at the initial public
release, so this repo has no "before" period to compare against, and that discriminator is
unavailable here.

**The discriminating experiment is sharper now**: not "does it parse the catalog" (it does), but
"does it skip coordinates that appear in a `gradle.lockfile`." That belongs to #793.

## R2 — Can realized stack identity be observed? — *resolved, and better than assumed*

**The path Part II §9 proposes already exists end-to-end, for one axis, shipped.** Tempdoc 623 U7
built it:

- `OnnxSessionCache.getOrtVersion()` calls `OrtEnvironment.getEnvironment().getVersion()` — a
  **genuinely realized** runtime query, not a declared constant.
- `GrpcHealthService` publishes it as `effective_config["ort.version"]`, riding the existing
  Head→Worker divergence-detection map (tempdoc 329) with **no new proto field**.
- The Head consumes it (`RemoteKnowledgeClient.java:795` → `WorkerDebugView` → `/api/debug/state`),
  jseval reads it (`release.py:220`), and it reaches a composed release and from there the public
  benchmark page (`gen-public-benchmark.mjs:66`) and the register headline
  (`register-headline-sync.mjs:61`).

The source comment states the intent almost verbatim as Part II's thesis: *"Exposed so the
benchmark-release hardware projection can record 'what ORT produced these eval numbers'."*

**Consequences for the design.** §9 is not "build a new mechanism" — it is **generalize instance #1**.
The cross-process problem is smaller than feared: for dependencies shared by both processes the
version is readable from either (the comment says so explicitly for ORT); only Worker-exclusive
libraries need the channel, and the channel is proven. Risk here drops sharply.

**But the misfiling is already spreading.** `ort_version` is stored under `hardware` in
`release.v1.json` — a software fact in a hardware record, the same class of error §13 deletes for
`*_gpu` under `model_fingerprints`. §13's orphan list must therefore grow to include
`release.py:220`'s `hardware.ort_version` — **and that cleanup has public blast radius**, because two
generators render it into public-facing text. The migration must preserve those claims' content.

## R3 — Cost of adding a `RuntimeManifest` field — *resolved; moderate*

The chain is documented and bounded: add field → `compileJava` (builder auto-regenerates) → update
the production construction site → update the controller's manual map → `:modules:app-api:updateSchemas`
→ frontend contract tests → module tests, plus `RuntimeManifestSchemaCompatibilityTest` and
`check-runtime-manifest-closure`. Two cost reducers found: the manual v1 contract baselines
(`debug-state-v1.json` and siblings) are **deliberately not updated on additive fields**, and
`WorkerDebugView` already carries `effective_config` — so the `/api/debug/state` path needs no new
record at all.

## R4 — Absent stack identity compares *equal* — *resolved; hazard confirmed*

The dangerous class predicted in §24 exists, in the axis comparator itself:

```
a = manifest_a.get(axis);  b = manifest_b.get(axis);  if a != b: diffs.append(axis)
```

Two pre-axis manifests both yield `None`, `None != None` is false, and **no difference is recorded** —
so runs from genuinely different stacks would read as identical on the stack axis. Worse than
refusing to compare. A second function in the same module behaves differently again
(`build_synthetic_manifest` omits absent keys from the hash rather than hashing them as null), so
introducing the axis produces two inconsistent absent-handling behaviours unless both are addressed
together.

**The correct precedent is one file away.** `ratchet_kernel.compare_engine_sets` refuses a comparison
on mismatch with an explicit exit code, a legible error, and a named override
(`--allow-engine-mismatch`), and treats the unknown case as *skip* rather than *equal*. Conform to
that; do not invent a rule.

## R5 — Introducing the axis invalidates persisted cohort keys — *resolved; hazard is immediate*

Not theoretical. `release.v1.json` persists the cohort as an **opaque hash**:
`cohort.config_cohort_key = "e985404f…"`, alongside `git_sha`, `eval_protocol_hash`, and the
`hardware` block. `perf-ratchet-baselines.v1.json` points at it via `current_release`.

So changing what enters that hash makes every stored key non-recomputable, and the baseline →
release → cohort chain breaks. A transition stance is **mandatory before implementation**, not
optional. Asymmetry worth noting: `release.v1.json` carries a `schema_version`; the jseval **run**
manifest carries none — so the release side has a migration signal and the run side does not.

## R6 — Classpath reachability derivation — *resolved; forces a correction to §11*

Part IV §27's suspicion is confirmed. Measured configuration membership across all 34 module
lockfiles:

| Coordinate | Configurations |
|---|---|
| `junit-jupiter-api` | test/integrationTest/soakTest/systemTest **only** |
| `lucene-core` | compileClasspath, runtimeClasspath, + all test variants |
| `logback-classic` | compileClasspath, runtimeClasspath, + all test variants |

**The derivation yields exactly one bit — test/build-only vs. runtime — and nothing finer.** Lucene
and Logback are indistinguishable by configuration, and module spread does not rescue it (Lucene 12
modules, Logback 13). A concrete false negative also appeared: `onnxruntime` sits only on *compile*
classpaths because `onnxruntime_gpu` is the runtime variant, so a naive "is it on `runtimeClasspath`"
test misclassifies the single most measurement-relevant dependency in the catalog.

**Correction to §11:** the derived class is a binary, not a four-way tier. That is still worth having
— the test/build-only half is roughly half the backlog and is exactly the batch case — but the
retrieval/inference distinction must be **declared**, and the transitive-closure diff carries more of
the discriminating load than §11 credited it with.

## R7 — Precedent for a run-invalidating check — *resolved; two found*

1. `OnnxSessionCache` already treats an ORT version change as **cache-invalidating**, not merely
   loggable — a stack-version comparison that acts.
2. `preflight.assert_capabilities` compares *intended* against *realized* and returns refusals,
   fail-closed.

Both are the shape §25 asked for, in the repo's own vocabulary. The fingerprint's firing condition
should conform to `assert_capabilities` rather than to 682's warn-only precedent.

## Out-of-scope finding logged

`lucene-core:10.4.0` is on `modules/ui`'s `compileClasspath` **and** `runtimeClasspath`, while the
tier register's row for Hard Invariant #1 states "Lucene types are not on Head's classpath." The
ArchUnit guardrail may still correctly forbid Head *imports* — the substantive invariant — but the
register's stated mechanism is inaccurate as written. Logged to the observations inbox; not
investigated further (out of scope per `log-pre-existing-issues`).

## Net effect on the design

- §9 downgrades from "new mechanism" to "generalize a shipped one" — **largest risk retired**.
- §11 loses its four-way derivation and keeps a binary — **simplification, and a correction**.
- §13's orphan list grows by `hardware.ort_version`, now with public-facing blast radius.
- §24's migration hazard is confirmed concrete at two sites (R4, R5) and must be handled at
  introduction.
- §25's firing condition has an existing guard to conform to.
- Part I §2's root cause is re-ranked but not closed; it remains separable as #793.

---

# Part VI — Direction (settled 2026-07-28)

Two decisions were open after Part V. Both are now taken, and they make the work **smaller** than
Part II implied. This part supersedes §15's sequence.

## 30. Decision 1 — detect-then-attribute, not measure-then-merge

**Part II's implicit choice is reversed.** Part IV §21 named the alternative; Part V's evidence
settles it, because the two halves of the original design moved in opposite directions:

| Half | Part II assumed | Part V measured |
|---|---|---|
| *Record* what stack ran | new mechanism to build | **already shipped for one axis** (623 U7), flowing to a release artifact and the public benchmark |
| *Gate* each bump by class | derivable from the build graph | **derivation yields one bit**, not four tiers — the retrieval/inference split must be hand-declared and hand-maintained |

Investing in the cheap, half-built, universally-useful half and skipping the expensive,
weakly-grounded half is the same conclusion from both directions. So:

- **Build:** stack identity, and the identity-slice declaration it forces (§10).
- **Do not build:** the reachability register and the per-bump evidence gate (§11's four-way tiering).
- **Detection** comes from a standing evaluation on a cadence; **attribution** comes retrospectively
  from `bisection.py` plus the stack axis, which is precisely what that axis makes possible.

The one bit R6 *did* yield is kept, because it is free and it identifies the batch case: coordinates
that appear only on test/build configurations cannot reach a measured number, and the existing suite
is already their measurement.

**What would falsify this.** If, once the axis exists, a metric shift is repeatedly traced to a bump
that a pre-merge class check would plainly have caught, the gating half was worth building after all
and §11 should be revived. Recorded so the reversal is testable rather than merely asserted.

## 31. Decision 2 — currency posture: deliberate lag, except at the untrusted-input boundary

Part I treats "behind" as a defect uniformly. Part IV §22/§23 challenged that, and the challenge is
accepted.

This application is local-first and loopback-only: for most of the dependency surface there is no
attacker in the threat model, which is why the audit's raw counts overstate urgency. The genuine
exception is the layer that consumes **bytes the user did not author** — document extraction and
parsing, image codecs, the PDF path, and the OCR runtime. That is where lag is exposure rather than
an untidy number.

**Posture:** stay current at the untrusted-input boundary; lag deliberately elsewhere, updating on
value (a fix we want, a migration that gets more expensive with time) rather than on staleness.

This re-sorts Part I's backlog by something real, and it retires the implicit obligation to drive 32
counters to zero.

## 32. What leaves this tempdoc

- **llama.cpp (§4).** Under §31 this is not hygiene debt. Upstream moves quickly on inference
  throughput and quality, so the bump is a *product improvement* that should be prioritised against
  other product work, not ranked by staleness inside a dependency audit. It leaves 792 and belongs
  wherever inference quality is owned.
- **The routine backlog sweep.** Ordinary PRs. It does not need a tempdoc.

## 33. Lanes chartered from here

Following the pattern 788 established days earlier (theorization → separately-numbered chartered
lanes), 792 stops here as the **audit and design of record** and fissions:

| Lane | Scope | Why separate |
|---|---|---|
| **#793** | Discriminate why Dependabot never bumps a Java library | Small, self-contained, and it gates the value of everything else — until libraries move there is nothing to measure |
| **#794** | Stack identity + identity slices + the migration hazards (R4/R5) | The substrate work, and the only part carrying real correctness risk |

**Ordering.** #793 first — it is cheap and unblocking. Then the test/build-only batch (free, proves
the flow end-to-end). Then #794. **ONNX Runtime, Lucene and llama.cpp are held back deliberately**:
they are the validation set for whether the axis works, and bumping them before it exists spends the
only three signals we are confident are non-null.

---

## Appendix — reproduction

Registry probes (scratchpad scripts, not committed): Maven Central `maven-metadata.xml` per
coordinate; Gradle Plugin Portal
`https://plugins.gradle.org/m2/<id>/<id>.gradle.plugin/maven-metadata.xml`;
`crates.io/api/v1/crates/<name>`; `npm outdated --json` in `/`, `/modules/ui-web`, `/modules/shell`;
`api.github.com/repos/<r>/releases/latest`; `nodejs/Release` `schedule.json`.

Two systematic traps, both corrected by hand above: naive dotted-segment version sorting mis-ranks
legacy alphabetic tags (Guava's `r09`), and several coordinates' Central metadata stops mid-history
because publication moved to the Gradle Plugin Portal (`net.ltgt.gradle:gradle-errorprone-plugin`,
`com.github.ben-manes:gradle-versions-plugin`) — Central reports a misleadingly old "latest" for
those, and the portal is authoritative.
