---
title: "Improvement landscape register: breadth-first survey of un-chartered improvement areas, existence-checked per item, bucketed for owner / opus takeover / fable theorization"
type: tempdocs
status: "REGISTER COMPLETE + 13 LANES CHARTERED (2026-09-02) — 75 items existence-checked by 10 pinned opus workers; decision-age audit (§S) applied; handoff tempdocs 888-900 written for the opus lanes (L1,L3,L5,L6,L7,L8,L11,L12,L13,L14,L15,L17,L19); fable lanes L4/L9/1.1-re-read pending; owner lanes L2/L10/L16/L18 awaiting decision"
created: 2026-09-02
updated: 2026-09-02
author: agent (Fable orchestration), founder-directed 2026-09-02 ("determine what the remaining areas of work are, to improving the repo/project in general from any aspects … breadth first … theorize all angles with potential of providing meaningful general improvements")
category: program-umbrella / planning-register
excludes:
  - 882-886 decision-review lanes 0/A/B/C and planned D (index identity + migration), E (search-quality re-derivation), F (engine merge) — the founder's running lane; items overlapping it are marked ⇢882-lane
  - 886-agent-token-efficiency-review — the founder's second running lane
related:
  - 98-deferred-items-from-completed-tempdocs   # earlier one-off harvests, for lineage
  - 214-neglected-gap-analysis
  - 238-long-term-development-issues
  - 512-codebase-investigation-and-critique
  - 799-structural-health-theorization
  - 821-root-cause-debt-charter                 # §2c was the last cross-tempdoc harvest
  - 762-agent-utility-analysis-program          # the umbrella pattern this register follows
---

> Umbrella. This tempdoc is the founder's selection document. §T is the method and its limits,
> §X the cross-cutting findings, §R the register (one row per item, verdict + bucket), §L the
> proposed lanes to charter, §Z the routed out-of-scope findings, and the Appendix carries the
> condensed per-item evidence so a takeover agent does not have to re-derive it. Per-item
> tempdocs are created only when a lane is picked up — this register is the only file this
> program opens on its own.

# 887 — Improvement landscape register

## §A. Why this exists

The project has 634 tempdocs and two founder-run review lanes (decision re-examination, 882-885;
agent token efficiency, 886). This register answers the complementary question: **which areas has
the project never chartered at all, or chartered and left inert?** It is breadth-first by
instruction — worth is not judged here; the founder picks.

Prior gap passes (98, 214, 238, 512, 799, 821) each did this once at their date. This one differs
in two ways: every item was **existence-checked against `main` with `file:line` evidence** by a
pinned opus worker (not theorized from titles), and every row carries a **bucket** that says who
can act on it next.

## §T. Method and honest limits

1. The orchestrator theorized ~75 candidate angles from all 634 tempdoc titles plus the docs index,
   then grouped them into 10 context-sharing groups.
2. Ten opus workers ran one read-only existence check per group with a fixed schema
   (verdict / evidence / owning tempdocs / gap / open items / confidence). Verdict vocabulary:
   **SHIPPED** (working mechanism on main), **PARTIAL** (exists, named gap), **ABSENT** (nothing
   beyond mentions), **SUPERSEDED** (a deliberate decision rejected or retired the direction).
3. The orchestrator spot-checked the five most surprising load-bearing claims directly
   (PMD skip default, no FE typecheck/vitest in any workflow, `.env` exempt from skip policy,
   CPU-only posture in canonical docs, regen gates absent from CI) — all five confirmed.
4. Limits: negative findings ("absent") rest on grep coverage of `modules/**`, `docs/**`,
   `governance/**`, `scripts/**`, `.github/**` and could miss a narrowly-named artifact; four
   verification-substrate negatives (7.3, 7.4, 7.6, 7.7) were derived from reading wiring plus
   enumerating every invocation site, not from executing the gates. Any repo-wide grep must
   exclude `.claude/worktrees/**` — hits there are copies of `main`.

**Decision-age rule** (founder instruction 2026-09-02, applied after the first pass): a SUPERSEDED
verdict, or a DROP that rests on a deliberate rejection/deferral, must carry the decision's **date
and stated grounds**. If the decision is older than ~4 months, was made by an early-numbered
tempdoc, or its stated premise has since changed, the row is flagged **RE-EXAMINE** and moves
out of DROP. §S holds the audit.

**Bucket rule** (decided before the checks ran, so the checks could not bend it):

| Bucket | Meaning |
|---|---|
| **DROP** | shipped or superseded; nothing to charter (residue may be routed in §Z) |
| **OPUS** | mechanism known, verification runnable, scope bounded → an opus agent can take it over from a tempdoc |
| **FABLE** | at least one of: a product-shape decision, a conflict with a hard invariant or ADR, cross-group coupling, real research need → theorize first |
| **OWNER** | blocked on a founder decision or purchase (certificate, platform, privacy stance, infra) before any agent work is useful |

## §X. Cross-cutting findings (the register's headline)

**X1. "Built but wired to nothing" is a repo-wide class, not a per-item defect.** Ten separate
mechanisms are fully implemented on main and enforce nothing because no workflow, schedule, or
default invokes them:

| Mechanism | Where it exists | Why inert |
|---|---|---|
| PMD (22 rules) + SpotBugs/FindSecBugs | `JvmBaseConventionsPlugin.kt:187-215`, `SpotBugsConventionsPlugin.kt:27-63` | tasks hang off `check`; no workflow runs `check`/`build`; `skipPmd` defaults true off-CI; SpotBugs `failOnError` set nowhere |
| Frontend typecheck + 463 vitest files | `modules/ui-web/package.json` | zero matches for `vitest|test:unit|typecheck` in `.github/workflows/`; the `ui-web-gates` recipe line lacks the marker `run-ui-web-gates.mjs` parses |
| Live axe gate | `scripts/jseval/jseval/ui_a11y_gate.py:11-12` | "runnable gate, not a CI-wired kernel gate" |
| Mutation-strength ratchet (`test-efficacy`, 18 seams) | `MutationConventionsPlugin.kt`, `gates/test-efficacy/` | nothing runs `:pitest` or produces `pit-strength-report.v1.json` (`ci.yml:210-218` admits it) |
| Flake census | `scripts/ci/report-flake-trend.mjs` | no workflow; retries absorb flakes silently (`ci.yml:700-701`) |
| jseval pytest (131 of 132 files) | `scripts/jseval/tests/` | `ci.yml:88` "runs in CI NOWHERE"; owner decision per 802 |
| Codegen idempotency gates (5 of 7) + `ssot-catalog-sync` | `scripts/ci/check-*-regen.mjs`, kernel | only `check-notices-regen` runs (`ci.yml:413`); `run-ui-web-gates.mjs:8-10` records this exact class sitting RED for weeks |
| Perf ratchet (`jseval perf-gate`) | `scripts/jseval/jseval/perf_gate.py` | advisory hook nudge only; needs a GPU runner |
| Soak suite (4 h, NMT + handle leaks) | `modules/system-tests/src/soakTest/` | opt-in flag, no scheduled runner |
| Int8 HNSW quantization; LambdaMART from real clicks | `JustSearchCodec.java:39-44`; `ResolvedConfigBuilder.java:1430` | default-off; the click→label→train chain is complete and never fires on a default install |

Consequence: `CLAUDE.md`'s "Build fails on PMD + Spotless" is true only for Spotless (§Z-1).
This class splits cleanly by infrastructure need: **tier 0** (runs on `ubuntu-latest` today:
typecheck, vitest, eslint, regen gates, ssot-sync, kernel gates, `check` with PMD/SpotBugs,
ruff/mypy, clippy) and **tier 1** (needs a self-hosted Windows/GPU runner: perf-gate, PIT, soak,
full pytest, upgrade matrix). Tier 0 is an opus lane; tier 1 is an owner infra decision first.

**X2. Accepted gaps lost their register.** `03-knowledge-server.md:283` names three gaps
"known and deliberately deprioritized" (junction duplicates, exclude patterns applied
post-indexing, unextractable files indexed as placeholder with no user signal) and routes them to
`docs/observations.md` — retired by 872 with none of its 565 notes read. 44% of tempdocs 700-880
carry an open-items section and the sampled ones (880 §C) are verifiably unacted-on (Appendix
10.7). There is no collector; the CLAUDE.md routing rule is prose-tier. Lane L15.

**X3. One owner decision unblocks a whole group.** Code signing (mode-config only, vendor
sandbox-validated), winget (skeleton committed), and update-integrity release qualification all
wait on a certificate purchase plus a GA tag (760 frontmatter). Lane L16.

**X4. Three premises in the orchestrator's own theorization were wrong**, which is why the
existence check was worth running: (a) a CPU-only inference tier is shipped, not fail-closed —
the fail-closed note in `branch-safety.md` is about dev-worktree cuda staging; (b) DirectML/Vulkan
was evaluated and rejected (311), not neglected — though on a premise that has since expired (§S); (c) end-user help content exists and is
searchable (`SSOT/docs/help/`, `HelpSurface.ts`).

**X5. Search results have no abstention concept.** Chat has one grounding authority
(`AnswerFrame`), extraction has a three-band gate (677), but a top-N of weak hits renders
identically to a strong one (`Sv3ResultsStatus` is emptiness-only, no score floor). Lane L9.

## §S. Decision-age audit (every superseded / rejected / deferred item)

| item | decision | date | stated grounds | premise today | disposition |
|---|---|---|---|---|---|
| 1.1 non-NVIDIA | 311 rejected DirectML EP | 2026-03-16 | 2× slower than CUDA for transformers, sequential-only, leak reports; "AMD/Intel GPU support … not relevant for JustSearch's NVIDIA target" (`311:119`, `:489` "JustSearch (NVIDIA-only)") | **Premise changed.** The comparison was DirectML-vs-CUDA; for an AMD/Intel owner the alternative is the CPU tier. Since March the project shipped a CPU tier + booster-pack model (ADR-0019, 772), went public, and 761 (2026-07-21) raised a llama.cpp **Vulkan pivot** as an open supply-chain/ADR decision. llama.cpp Vulkan and ORT non-CUDA EPs have moved since. | **RE-EXAMINE** → L18 (FABLE): "non-NVIDIA acceleration, 2026 re-read" covering Vulkan for llama.cpp and DirectML/other for encoders, measured against the CPU tier, not CUDA |
| 9.2 MSIX / Store | ADR-0024 rejected MSIX | 2026-04-06, `last_reviewed: 2026-09-02` | Store review friction, EV/organization cert required | Signing friction is the same blocker L16 must clear anyway; Store handles signing once a publisher identity exists. **Reviewed by the founder's lane B one day ago** — flagged, not re-opened here | flag to 884; revisit when L16's cert decision lands |
| 6.6 UI localization | 742 removed Lingui | 2026-07-16 | catalog 100% dead after the Lit rewrite | Not a product decision — a dead-code sweep; ADR-0043 explicitly leaves UI language open | stays OWNER (L18); reworded from SUPERSEDED to "unowned" |
| 5.3 near-dup / version family | 314 abandoned; 639 stub | 2026-03-17 (staleness audit 2026-05-18) | "Deprioritize until real-user duplicate evidence exists" (`314:55`) | Evidence path now exists (public release, demo corpus, feedback capture) — the precondition is satisfiable | stays OPUS-measure (L13); measurement is the re-examination |
| 6.2 / 9.3 Explorer menu, file associations, browser extension | 191 deferred ("Long-term C", "marginal value", "Long-term K") | 2026-02-17 | effort/value guesses pre-launch, pre-MCP | 6.5 months old, earliest-model era; the product center moved to an agent-facing runtime (654), which changes the value of OS/host integrations | **RE-EXAMINE** → L18; treat 191's rankings as stale input, not decisions |
| 9.7 rollback | 617 D5 locked forward-only | 2026-06-20 (updated 2026-07-31) | user-consented update tier makes "reinstall newer" acceptable | Recent, reasoned, still true | stays DROP |
| 7.2 concurrency gate | 398 parked | ~2026-04 | "pick up reactively when `NativeSessionHandle`'s state machine is next touched" | Since then 819, 843, 862 each fixed a race one-off — the trigger has effectively fired three times without the gate being built | stays FABLE(short) → OPUS (L11); the parking condition is met |
| 4.2 int8 default | 640 excluded `index_size_bytes` from ratchet; float32 kept "for backwards compatibility" | 2026-06 | merge non-determinism; compatibility | Blue/green re-embed (doc 11) makes a codec default change migratable; measurement first | stays OPUS-measure → OWNER (L6) |

## §R. Register

Columns: id · item · verdict · bucket · lane (§L) · one-line gap. Evidence per row in the Appendix.
`⇢882-lane` = overlaps a founder decision-review lane; coordinate before chartering.

### Group 1 — Inference reach

| id | item | verdict | bucket | lane | gap |
|---|---|---|---|---|---|
| 1.1 | Non-NVIDIA inference (DirectML/Vulkan/ROCm) | SUPERSEDED (2026-03, stale premise) | **RE-EXAMINE** → FABLE | L18 | rejected in 311 on an "NVIDIA-only target" premise that no longer holds (§S); only residue is a ROCm chip-label regex (`governance/chip-facts.v1.json:10`); 761's Vulkan pivot is the live thread |
| 1.2 | CPU-only degraded tier | SHIPPED | DROP | — | works (8k window, FP32 encoders); `05-ai-architecture.md:83-85` describes CUDA as "deferred to v3" while the cuda pack ships → §Z-2 |
| 1.3 | Low-spec / laptop adaptation | PARTIAL | OPUS | L19 | only VRAM drives adaptation; Worker heap is a fixed constant (`WorkerSpawner.java:461`), battery gates only VDU pacing, thermal not sensed |
| 1.4 | ARM64 Windows | ABSENT | OWNER | L18 | no build/ORT/llama target; platform decision |
| 1.5 | GPU driver events (reset, VRAM contention) | PARTIAL | OPUS | L19 | recovery is process-crash / session-*init* only; no device-lost classification on `session.run`, free VRAM sampled once per launch |
| 1.6 | Brain preload / warm policy | SHIPPED | DROP | — | spec/reconcile model (737), autostart overrides, restart ETA |
| 1.7 | llama-server capability adoption | PARTIAL | OPUS + FABLE | L19 | structured output + prompt cache used; **no speculative decoding** (draft-model pack decision → FABLE), **no slot pinning / `cache_prompt`** with `-np>1` (OPUS) |
| 1.8 | Long-context vs retrieval budget | SHIPPED (derivation) | OPUS ⇢882-E | L19 | ADR-0047 derives budgets from the live window; the 32k top rung is a hand-set budget with no periodic quality sweep |

### Group 2 — OS and filesystem realities

| id | item | verdict | bucket | lane | gap |
|---|---|---|---|---|---|
| 2.1 | Cloud placeholders, junctions, long paths, network/removable | PARTIAL | OPUS | L3 | OneDrive `RECALL_ON_DATA_ACCESS` handled + ledgered; **no** reparse/junction classification, no `\\?\` long-path handling, no network/removable gating; placeholder ledger has no FE consumer (419 FE slice unbuilt); junction-duplicate gap was an accepted gap whose register is gone (X2) |
| 2.2 | Protected / permission-denied paths | PARTIAL | OPUS | L3 | walk survives `AccessDeniedException` but the only trace is `log.debug` (`SyncDirectoryOps.java:323`); no ledger row, no CFA/UAC awareness — the "silently invisible" state `CloudPlaceholderRecorder` exists to prevent |
| 2.3 | Disk-full / low-disk | PARTIAL | FABLE(short) → OPUS | L7 | three uncoordinated thresholds (Lucene string-sniff, SQLite fail-open probe, telemetry two-tier); `INDEX_DISK_FULL` is PERMANENT with no resume path; product behavior undecided |
| 2.4 | Antivirus / Defender / SAC | PARTIAL | OPUS | L3 | build-time SAC preflight + user doc exist; no runtime AV-failure detection, no shipped exclusion guidance (`-XX:-UsePerfData` is the sole runtime accommodation) |
| 2.5 | Process / background I/O priority | ABSENT | OPUS ⇢882-C | L7 | no `SetPriorityClass`/`PROCESS_MODE_BACKGROUND_BEGIN` anywhere; job object sets only kill-on-close (`WindowsJobObject.java:172`); 885's duty cycle is application-level |
| 2.6 | Port collisions, second instance, WebView2 | SHIPPED | DROP (OWNER-minor) | — | ephemeral fallback, `AppInstanceLock`, single-instance plugin; WebView2 is Evergreen-online only (`cut-a-release.md:305`) — offline bootstrapper is an installer-size call |
| 2.7 | Pathological inputs at scale | PARTIAL | OPUS | L3 | size caps + deterministic adversarial suite; no multi-GB e2e, no millions-of-files test (200k delete-detection cap is a skip), no deep nesting, no bidi/NFC filename handling |
| 2.8 | Process sandboxing (Tika/Worker) | PARTIAL | OPUS ⇢882-C | L7 | extraction child has `-Xmx` + parent-PID watchdog only; job-object memory/process limits declared but never set (`WindowsJobObject.java:74-77`); no restricted token / AppContainer |
| 2.9 | Non-Windows portability | PARTIAL | OWNER | L18 | 761 holds a complete `file:line` inventory (Linux L, WSL M); "no implementation chartered"; `docs/comparison.md:20` still says Windows-only |

### Group 3 — Data safety and privacy

| id | item | verdict | bucket | lane | gap |
|---|---|---|---|---|---|
| 3.1 | Sensitive-content policy | ABSENT | FABLE | L4 | default exclusions are junk/VCS only; **`.env` is affirmatively exempted from skipping** (`IngestionSkipPolicy.java:39,134`); no classifier, no redaction before search/RAG/MCP serving; 875 treats `.ssh` as consent, not content |
| 3.2 | File lifecycle propagation | PARTIAL | OPUS | L5 | per-root forget exists (`RootLifecycleOps.removeWatchedPath`); **no per-file forget**, deletion is sweep-based, citations in saved conversations and ledger refs are never invalidated, MCP-ingested out-of-root docs are unreachable by any prune (811) |
| 3.3 | Backup / export / portability / uninstall | PARTIAL | OPUS + FABLE | L5 | encrypted `justsearch-backup/v1` export exists but only when encryption is unlocked; no plaintext/per-conversation export (OPUS); no data-dir relocation / portable mode (FABLE); uninstall leaves data dir + models with no prompt (OPUS) |
| 3.4 | Unified threat model | PARTIAL | FABLE | L4 | `docs/reference/security/threat-model.md` is current (STRIDE, 4 assets, trust boundaries) but omits **prompt injection via indexed documents** — the adversary that reaches the LLM and file tools; no runtime control |
| 3.5 | Real-usage-driven eval + privacy | SHIPPED | DROP (OWNER-note) | — | disposition capture is **default-on, local-only, disclosed** (`FeedbackCaptureSettings.java:32`); consumption gated on real usage; founder may want to restate the default-on stance in a privacy register |
| 3.6 | User-facing crash / problem reporting | PARTIAL | OWNER | L17 | redacted local bundle exists (297/658); any submit path conflicts with `NON-GOALS.md:8` "no phone-home" — owner stance needed |
| 3.7 | MCP client auth + scoping | SHIPPED | DROP | — | session token covers `POST /mcp`, Origin validation, tier×family consent; all clients share one identity (per-client scopes = future, unowned) |

### Group 4 — Storage and performance internals

| id | item | verdict | bucket | lane | gap |
|---|---|---|---|---|---|
| 4.1 | Index maintenance | PARTIAL | OPUS ⇢882-D | L6 | old `indices/<gen>/` reclaimed only by manual `core.index-gc` (`IndexGenerationManager.java:752` "not currently invoked by default"); WAL unbounded (no checkpoint/size pragma); `forceMerge` never called in prod; log rotation OK |
| 4.2 | Index footprint reduction | PARTIAL | OPUS (measure) → OWNER | L6 | int8 HNSW built, default-off; the only bytes/doc number is arithmetic (`18-adapters-lucene-deep-dive.md:129`); no matryoshka code, no stored-field compression choice, no doc-side SPLADE pruning; 640 excluded `index_size_bytes` from the ratchet |
| 4.3 | Cold-open latency | ABSENT | OPUS | L6 | Worker start → first search on a large index is unmeasured; ONNX session load documented only as a hazard (`spawn-isolated-test-backend.md:112`); no warmup step |
| 4.4 | Soak / leak stability | PARTIAL | OPUS + OWNER-infra | L2 | 4 h suite with NMT/handle assertions exists, opt-in, unscheduled; asserts nothing about index-generation/log growth |
| 4.5 | Search QoS under indexing | PARTIAL | DROP → 885 | — | owned by 885 (duty cycle merged, "after" arms unrun, p99 uncaptured) — fold p99 into 885, do not re-charter |
| 4.6 | Perf ratchets in CI | PARTIAL | OPUS + OWNER-infra | L2 | `perf-gate` bites with a pinned baseline; no workflow runs it (advisory hook only); 647 floors need a release recompose |
| 4.7 | Idle footprint | PARTIAL | OPUS | L6 | one 6-month-old idle-CPU spot check (278); no target for idle CPU/GPU/RSS/disk; llama-server keeps GPU resident at idle (unquantified); VDU offline trigger deliberately consumes idle |

### Group 5 — Retrieval features

| id | item | verdict | bucket | lane | gap |
|---|---|---|---|---|---|
| 5.1 | Freshness / temporal signals | PARTIAL | OPUS ⇢882-E | L8 | 5%-cap decay + `modified_at` range filter shipped; **no FE date-range control**; temporal intent only behind default-off QU LLM; recency prior strength is a ranking question (E) |
| 5.2 | Table / spreadsheet retrieval | PARTIAL | FABLE (blocked on 686) | L13 | tables flattened to header-annotated text (`StructuredDocument.appendTable`); no cell unit, no numeric typing, no in-content range query; 705 closed evidence-starved |
| 5.3 | Version-family collapse | ABSENT | OPUS (measure) → FABLE | L13 | 314 ABANDONED, 639 purpose-only stub "measure first"; nothing dedups or diversifies the returned set |
| 5.4 | Collection-level intelligence | ABSENT | FABLE | L18 | folder counts/bytes only (`FolderBrowseEngine`); nothing summarizes what a folder is about |
| 5.5 | Unified abstention / confidence | PARTIAL | FABLE | L9 | three unrelated vocabularies (chat `AnswerFrame`, VDU bands, results emptiness); search results have no relevance floor (X5) |
| 5.6 | Format breadth | PARTIAL | OPUS (+OWNER for audio/video) | L13 | Tika auto-detect = policy (no allowlist); only PDF/Office/text/CSV/JSON/images exercised by any test; eml/mbox/epub/zip-content untested; no transcription pipeline |
| 5.7 | Multimodal query | ABSENT | OWNER/FABLE | L18 | VLM is ingest-side text extraction only; no image/audio query surface, no non-text embedding space |
| 5.8 | Learning from usage | PARTIAL | OWNER + OPUS | L8/L18 | click→label→LambdaMART chain complete but `justsearch.lambdamart.enabled=false` and needs 20 contrast groups (OWNER: enable? needs eval); **no query-history store** (OPUS, 851 deleted the query trail) |

### Group 6 — Product UX surface

| id | item | verdict | bucket | lane | gap |
|---|---|---|---|---|---|
| 6.1 | Launcher-style quick search | PARTIAL | OPUS | L8 | tray, autostart, close-to-tray, single-instance focus shipped; **no global hotkey, no separate palette window**; 191 §A already specifies the design |
| 6.2 | OS integration | PARTIAL | OWNER (RE-EXAMINE 191's 2026-02 rankings) | L18 | deep-link, drag-drop, open/reveal shipped; Explorer context menu needs a shell-extension DLL (191 "Long-term C"); no file associations ("marginal", 191); no preview surface — the deferrals predate launch and the agent-runtime product center (§S) |
| 6.3 | Search UX primitives | PARTIAL | OPUS | L8 | pins shipped; **query history absent**, no size/folder filter, boolean syntax reachable by MCP/API but not the composer (`querySyntax` unset → SIMPLE) while `SSOT/docs/help/search-syntax.md:13-25` documents it → §Z-7; no grouping/dedup (→5.3) |
| 6.4 | User-controlled indexing policy | ABSENT | FABLE ⇢882-C | L10 | settings schema has only `pauseIndexingDuringAi`; no pause/schedule/battery/CPU lever; 885 made pacing deliberately non-user-facing; 813 still deciding whether cancel is honest |
| 6.5 | Accessibility on Lit stack | PARTIAL | OPUS | L1/L8 | three static gates in CI; live axe gate local-only (X1); no `forced-colors`; no assistive-tech testing (human); 200 is pre-Lit |
| 6.6 | UI localization | ABSENT (torn down 2026-07 as dead code, not decided) | OWNER | L18 | Lingui + a German catalog were verified dead and removed (742); ADR-0043 explicitly leaves UI language open — unblocked but unowned |
| 6.7 | End-user documentation / help | SHIPPED | DROP | — | five help files auto-ingested into `justsearch-help`, Help view, Launchpad cards |
| 6.8 | Product glossary authority | ABSENT | OPUS | L14 | 509 solved operation *button labels* only; no glossary for Head/Body/Brain, run/operation/job, passage/chunk, leg/lane; 509 left F-22/F-25 naming collisions unresolved |

### Group 7 — Verification substrate

| id | item | verdict | bucket | lane | gap |
|---|---|---|---|---|---|
| 7.1 | Continuous fuzzing | ABSENT | OPUS | L12 | no Jazzer/JQF target, corpus, or job for Tika, proto/gRPC, HTTP, MCP JSON-RPC; only deterministic hostile fixtures + one MMF random-byte chaos thread; 410 invariant 12 asks for it |
| 7.2 | Systematic concurrency audit | ABSENT | FABLE(short) → OPUS | L11 | five races fixed one-off (386/402/819/843/862); 8 `@GuardedBy`-family annotations repo-wide; no JCStress, no lock-order inventory; 398's gate parked ~2026-04 "until the state machine is next touched" — touched three times since (§S); virtual threads adopted in 12 files |
| 7.3 | Nullness + static analysis | PARTIAL | OPUS | L1/L11 | **Error Prone is the only Java analyzer that gates**; PMD/SpotBugs unreachable (X1); no NullAway/JSpecify; CodeQL `workflow_dispatch` only, Java+JS scope |
| 7.4 | Test-suite trustworthiness | PARTIAL | OPUS + OWNER-infra | L2 | PIT + `test-efficacy` ratchet fully built, fed by nothing (X1); 9 jqwik property classes shipped; retry configured, flake census unwired, no quarantine |
| 7.5 | Frontend verification depth | PARTIAL | OPUS | L1 | typecheck + 463 vitest files run in **no** workflow (X1); live axe local-only; visual regression is baseline-less by policy; `check-ui-step-coverage` claims CI wiring it lacks → §Z-4 |
| 7.6 | Meta-tests for gates and hooks | SHIPPED / PARTIAL | OPUS | L1 | gate logic well-tested and CI-run; hook **bite** thin — 26 of 39 hooks have no bite entry, 8 more satisfied by `existsSync(testPath)` |
| 7.7 | Non-Java code quality | PARTIAL | OPUS (+OWNER for pytest cost) | L1 | Python: no ruff/mypy, 131/132 tests unrun; Rust: `cargo test` only, no clippy/fmt/audit, 4 `unsafe` blocks uninventoried; Node: eslint configured, invoked nowhere |
| 7.8 | Cross-version upgrade matrix | PARTIAL | OWNER-infra | L2 | per-store N-1 migrations unit-tested; app-level install-old→upgrade only ever human-run in Windows Sandbox (734); hosted runners cannot run Sandbox |

### Group 8 — Codebase hygiene and toolchain

| id | item | verdict | bucket | lane | gap |
|---|---|---|---|---|---|
| 8.1 | Java encapsulation | PARTIAL | FABLE(short) → OPUS | L11 | zero `module-info.java`, 2 `package-info.java`; ArchUnit enforces dependency direction only; no api/internal convention |
| 8.2 | Comment-debt census + 378 | SHIPPED / PARTIAL | OPUS | L14 | `todo-fixme` ratchet at 0 for prod Java+FE; the 36 remaining markers live in tests/scripts outside its globs; 378 workaround inventory stale since 2026-04 → close it |
| 8.3 | Codebase-health time-series | PARTIAL | OPUS | L14 | ratchets hold current value only; class-size/clone/exception ratchets retired at go-public (`build.gradle.kts:268`); no complexity metric; the only trend machinery measures agents |
| 8.4 | Feature-flag / rollout policy | ABSENT | FABLE + OWNER(532) | L14 | no flag registry, owner, expiry, or retirement gate; `config-surface` ratchet caps count only; 532 ship-or-retract still `open` since 2026-05 |
| 8.5 | Toolchain currency | SHIPPED | DROP (2 one-liners → L1) | — | JDK 25, Gradle 9.6.1, config-cache on, AOT cache shipped end-to-end, reproducible archives, 1772 verified components; `verify-signatures=false`, config-cache problems `warn` |
| 8.6 | Platform EOL calendar | ABSENT | OPUS | L14 | only prose rows inside 792; nothing warns when a pin crosses a support date; 792 §25 predicted this |
| 8.7 | Executable docs / OpenAPI | PARTIAL | OPUS | L14 | `GET /api/meta/openapi.json` composed from the route manifest (583) but never committed or CI-diffed; per-route schemas "a separate charter"; no doc-snippet execution |
| 8.8 | Generated-artifact hygiene | PARTIAL | OPUS | L1 | 8 generators, 7 regen gates, `ssot-catalog-sync` — 5 regen gates + ssot-sync run in no workflow (X1) |

### Group 9 — Distribution and release

| id | item | verdict | bucket | lane | gap |
|---|---|---|---|---|---|
| 9.1 | Code signing | PARTIAL | OWNER | L16 | three-mode signing + verify + sign-once mirrors wired and dormant; shipped builds unsigned pending cert/vendor purchase (760) |
| 9.2 | Package-manager distribution | PARTIAL | OWNER → OPUS | L16 | winget skeleton committed, unsubmitted, no automation; MSIX/Store rejected (ADR-0024, 2026-04, re-reviewed by lane B 2026-09-02 — flagged in §S, not re-opened here); Scoop/Chocolatey absent |
| 9.3 | Integration plugins | ABSENT | OWNER/FABLE (RE-EXAMINE 191's 2026-02 deferral) | L18 | PowerToys Run/Raycast/Obsidian appear only as prior art; browser extension is 191 "Long-term K" (pre-launch, pre-MCP ranking, §S) |
| 9.4 | Release notes / roadmap | PARTIAL | OPUS + OWNER(content) | L16 | `CHANGELOG.md` has only `[Unreleased]`, nothing reads or gates it; release body = blurb + `--generate-notes`; no roadmap doc |
| 9.5 | NOTICE / license automation | SHIPPED | DROP | — | `gen-notices.mjs` projects Gradle+npm+Cargo+models, CI-gated; SPDX headers deferred (632 G) |
| 9.6 | Model lifecycle pipeline | PARTIAL | OPUS (dep: 826/819) | L16 | every step exists as a separate tool; nothing sequences build→verify→registry→re-embed→eval→notices; 826 reports the fingerprint never persists; an interrupted `build-ner.py` went undetected until a 2.69× regression |
| 9.7 | Update integrity | PARTIAL | DROP | — | Ed25519 descriptor + minisign + SHA256SUMS round-trip shipped; rollback is a locked non-goal (617 D5); release qualification pending on GA |

### Group 10 — Project operations and ecosystem

| id | item | verdict | bucket | lane | gap |
|---|---|---|---|---|---|
| 10.1 | Human contributor onboarding | PARTIAL | OPUS | L17 | CONTRIBUTING + tiered onramp + doctor shipped; no devcontainer, Windows-only bootstrap, `good-first-issue` is a phrase with no label/list |
| 10.2 | Ownership / bus factor | PARTIAL | OWNER + OPUS | L17 | CODEOWNERS = one name; no succession/takeover doc; cutover runbook lives off-repo |
| 10.3 | Product KPI / north-star | ABSENT | OWNER/FABLE | L18 | `NON-GOALS.md:8` forbids phone-home; no metric defined; a local-only observation could satisfy both — needs owner intent first |
| 10.4 | Community intake | SHIPPED | DROP | — | templates, SECURITY.md with SLAs, CoC, SUPPORT.md, CLA bot |
| 10.5 | Contract lifecycle policy | SHIPPED | OPUS (small) | L17 | SemVer policy, 90-day window, changelog; no on-wire deprecation signal (header / per-tool `deprecated` flag) |
| 10.6 | Client SDKs | PARTIAL | FABLE(short) → OPUS | L17 | MCPB bundle + copy-paste configs shipped; no runtime-contract client in any language, no LangChain/LlamaIndex work; plugin SDK unpublished (npm 2FA) |
| 10.7 | Open-items harvest | PARTIAL | OPUS | L15 | six one-off harvests; no collector; 77 of 175 tempdocs (700-880) have open-items sections; 880 §C items verifiably unacted-on (X2) |
| 10.8 | ADR consequences follow-through | PARTIAL | OPUS ⇢882-B | L15 | `adr-coverage` checks premises + 183-day review; nothing parses `## Consequences`/`## Follow-up`; ADR-0013's follow-up stale-and-superseded |

## §L. Proposed lanes (for founder selection)

Each lane becomes a tempdoc only when picked. Bucket = who acts first. **Chartered 2026-09-02:** the 13 OPUS lanes have handoff tempdocs 888-900 (number after the arrow); L4, L9 and the 1.1 re-read are fable work in the orchestrating session; L2, L10, L16, L18 wait on the founder.

| lane | name | items | bucket | notes |
|---|---|---|---|---|
| **L1** → **888** | CI enforcement closure, tier 0 (no new infra) | 7.5, 7.3 (PMD/SpotBugs via `check`), 7.7 (ruff/mypy/clippy/eslint), 8.8, 7.6 (hook bites), 6.5 (axe gate), 8.5 one-liners | OPUS | highest value-per-effort in the register; one PR per sub-item; SpotBugs needs a baseline triage first |
| **L2** | CI enforcement closure, tier 1 (self-hosted / GPU) | 4.6, 7.4, 4.4, 7.7 pytest, 7.8 | OWNER → OPUS | needs a runner decision (cost) before any agent work |
| **L3** → **889** | Filesystem reality | 2.1, 2.2, 2.4, 2.7 | OPUS | junction/symlink/long-path classification, access-denied ledger row, AV guidance, scale + Unicode matrix; 410's open rows are the spec |
| **L4** → **901** | Sensitive content + injection adversary | 3.1, 3.4 | FABLE → OPUS (design settled) | design settled 2026-09-02 in `901-sensitive-content-policy-and-injection-adversary` (worktree `901-sensitive-content`, separate branch): SSOT policy register, `.env` reversal, hidden-dir skip, single admission authority incl. the watcher arm, extraction-time span masking + reconcile on policy bump, corpus framer + hierarchy clause, argument-origin gate escalation, `core.remember` origin tag, MCP origin marking, threat-model rows; owner confirmations K1-K4 pending; opus chunks C1-C6 |
| **L5** → **891** | File lifecycle and data portability | 3.2, 3.3 | OPUS (+1 FABLE sub-item) | per-file forget, citation invalidation, out-of-root prune, uninstall prompt, plaintext export; relocation/portable mode theorized separately |
| **L6** → **895** | Index maintenance and footprint | 4.1, 4.2, 4.3, 4.7 | OPUS ⇢882-D | generation GC policy, WAL bounds, bytes/doc + cold-open + idle measurements; int8 default is an owner call after measurement |
| **L7** → **896** | Background citizenship | 2.5, 2.8, 2.3 | OPUS ⇢882-C | OS priority classes, job-object limits, one low-disk policy; must coordinate with 885 items 3/19 |
| **L8** → **890** | Launcher and search primitives | 6.1, 6.3, 5.1 (FE date filter), 5.8 (query history) | OPUS | 191 §A is the launcher design; fixes the help-vs-composer syntax defect (§Z-7) |
| **L9** → **902** | Unified abstention authority | 5.5 | FABLE → OPUS (design settled) | design settled 2026-09-02 in `902-unified-abstention-authority`: the premise "three vocabularies to unify" was wrong — faithfulness, capability, completion, extraction trust are distinct questions and stay separate; the missing axis is **retrieval adequacy** (`STRONG/WEAK/NONE/UNKNOWN`) stamped once on the canonical `SearchTrace` from existing signals, projected beside the frame on search, chat, agent, MCP; pre-generation abstention on NONE; 779 becomes a consumer; owner confirmations K1-K3; floors are lane E's to re-derive |
| **L10** | User indexing policy | 6.4 | FABLE ⇢882-C | interacts with 885 duty cycle and 813 cancel honesty |
| **L11** → **900** | Static-analysis and concurrency conventions | 7.2, 7.3 (NullAway pilot), 8.1 | FABLE(short) → OPUS | decide `@GuardedBy` convention, api/internal packages, JSpecify adoption; then ArchUnit + one-module NullAway pilot |
| **L12** → **894** | Continuous fuzzing | 7.1 | OPUS | Jazzer targets: Tika (sandbox child), MCP JSON-RPC, HTTP; scheduled job |
| **L13** → **897** | Format breadth corpus + duplicate measurement | 5.6, 5.3, 5.2 (later) | OPUS | depends on 686's real binary corpus; assert extraction, not just no-crash; measure duplicate rate before 639 design |
| **L14** → **893** | Hygiene registers | 8.6, 8.7 (spec snapshot), 8.2, 8.3, 6.8, 8.4 (policy draft) | OPUS | EOL register + check, committed OpenAPI snapshot + CI diff, ratchet globs, health NDJSON, glossary, flag policy draft for owner |
| **L15** → **892** | Open-items collector + ADR follow-up | 10.7, 10.8 | OPUS ⇢882-B | collector script over tempdoc open-items sections; extend `adr-coverage` to `## Follow-up`; one-time harvest feeds this register |
| **L16** | Release unblock | 9.1, 9.2, 9.4, 9.6 | OWNER → OPUS | cert purchase + GA tag, then winget automation, CHANGELOG gate, model-swap runbook |
| **L17** → **899** | Project operations | 10.1, 10.2, 10.5, 10.6, 3.6 | OPUS + OWNER | devcontainer, labels, succession doc (owner inputs), on-wire deprecation, SDK language decision, crash-report stance |
| **L18** | Product bets (theorization menu) | **1.1 (non-NVIDIA re-read)**, 1.4, 2.9, 5.4, 5.7, 5.8 (enable), 6.2, 6.6, 9.3, 10.3 | OWNER/FABLE | each is a direction decision; none should be chartered without the founder picking it; 1.1, 6.2, 9.3 carry stale early-era rejections/deferrals (§S) — the re-read is the deliverable, not a presumption either way |
| **L19** → **898** | Inference runtime residuals | 1.3, 1.5, 1.7, 1.8 | OPUS (+1 research) | RAM-derived heap, battery-aware pacing beyond VDU, device-lost classification, slot pinning; speculative decoding is a model-pack research item |

Suggested first picks if the founder wants a default: **L1** (inert enforcement, zero infra),
**L4** (the `.env` finding is a live privacy defect), **L3** (silent-invisible files), **L8**
(the launcher design already exists), **L15** (stops the register itself from becoming the pile).

## §Z. Routed out-of-scope findings (verified, not fixed here)

Per `log-pre-existing-issues`, these are one-line-fixable doc/comment drifts found during the
checks. They are bundled as the first OPUS chunk of whichever lane is chartered first (or a
"lane 0" hygiene PR in the 882 pattern) rather than fixed in this docs-only PR.

| # | file | drift | fix |
|---|---|---|---|
| Z-1 | `CLAUDE.md` Quick Commands | "Build fails on PMD + Spotless" — PMD never runs (X1) | "Build fails on Spotless whitespace/newline checks (PMD/SpotBugs attach to `check`, which no default path runs)" — or wire them (L1) and leave the sentence |
| Z-2 | `docs/explanation/05-ai-architecture.md:83-85` | "GPU-accelerated runtimes … deferred to v3" while the `cuda-runtime` pack is a published asset (772) | rewrite the v1 note to "CPU runtime bundled; CUDA pack downloadable" |
| Z-3 | `governance/ui-a11y-baseline.v1.json` description | claims a "TS e2e accessibility-audit gate" consumer; real consumers are `ui_measure.py`, `ui_a11y_gate.py`, `regen_a11y_baseline.py` | name the Python consumers |
| Z-4 | `scripts/ci/check-ui-step-coverage.mjs` header + tempdoc 615 | claims "wired as a ci.yml step"; it is in neither `ci.yml` nor the `ui-web-gates` recipe | fix header; wire in L1 |
| Z-5 | `modules/worker-core/.../IngestionSkipPolicy.java:30-38,127-131` | cites `observations.md #181` as the `EXEMPT_NAMES` rationale; store retired (872) | inline the rationale or point at L4's tempdoc |
| Z-6 | `modules/adapters-lucene/.../JustSearchCodec.java:16-17,73-74` | mojibake `Ã—` for `×` (cp1252 round-trip, `utf8-bulk-edits` class) | replace with `x` |
| Z-7 | `SSOT/docs/help/search-syntax.md:13-25` vs `TextQueryOps.java:93` | help documents phrase/field/boolean syntax; the composer never sends `querySyntax: 'lucene'`, so operators are escaped | either expose the toggle (L8) or correct the help text |
| Z-8 | `docs/tempdocs/410-…md` frontmatter | `status: active` while §Status says "SUBSTANTIVELY COMPLETE … 2026-04-26" | set status |
| Z-9 | `docs/tempdocs/378-workaround-inventory.md:7` | "In progress" since 2026-04-08, unverified "17 active" count | close or regenerate (L14) |
| Z-10 | `WorkerScanOps.java:201` / `SyncDirectoryOps.java:282` | unreadable files dropped with no ledger row — asymmetric with `CloudPlaceholderRecorder` | L3 item 2.2 |

---

## Appendix — condensed per-item evidence

Pointers are `path:line` on `main` at 67ee6052 (2026-09-02). Kept short; the worker reports were
~2,500 words per group and are not preserved elsewhere, so the load-bearing pointers live here.

### A1. Inference reach

- **1.1** `311-runtime-alternatives-research.md` §2 rejects DirectML ("significantly slower than CUDA for transformer"); zero `DirectML|Vulkan|ROCm|OpenVINO|CoreML` hits in `modules/`; only provider is `SessionOptionsApplier.java:8` `OrtCUDAProviderOptions`; 754 removed the dead `vulkan/metal` config enum; `840:107` classes non-NVIDIA cuda-runtime as "not supported here".
- **1.2** `05-ai-architecture.md:83-85` CPU-only llama-server bundled by default; `ContextWindowPolicy.java:49-50` `CPU_TOP_RUNG = 8192`; `NativeSessionHandle.java:658-666` GPU init failure → CPU session, 60 s retry; ADR-0019 ships FP32 CPU encoder variants; embedding default is CPU (`05:167`).
- **1.3** VRAM adaptive: `LlamaServerOps.java:253-291` NVML → rung; `EnvRegistry.java:346-353` thresholds. Battery: `EnergyState.java:17-42`, consumed only by `VduPacingPolicy.java:20`, `VduOfflineTriggerSampler.java:96`. RAM: `WorkerSpawner.java:461-464` `-Xms=-Xmx=config.workerHeapSize()`; `getTotalMemorySize` used only in `MachineFingerprint.java:50`. No thermal probe.
- **1.4** only macOS aarch64 classifier branches (`api-contract-projection-java/build.gradle.kts:107`, `app-launcher/build.gradle.kts:108`) and unused npm optional deps.
- **1.5** `BrainSupervisionPolicy.java:5-38` crash cap/backoff; `NativeSessionHandle.java:77-80,230-242` session-init retry; ADR-0004 single-tenant GPU lease between own processes; catch is on session creation, not `session.run`; free VRAM read once at launch.
- **1.6** `RuntimeReconciler.java:207-209,466`; `InferenceWiring.java:69-96` autostart overrides; `BrainSurface.restartEta.test.ts`.
- **1.7** argv `LlamaServerOps.java:330-402` (`--jinja`, `--reasoning-format`, `-np`, `-kvu`, `-fa on`, `-fit off`); structured output `OnlineModeOps.java:743-752,975-980`; prompt cache disabled only in VDU mode (`:271,345`); zero hits for `cache_prompt|id_slot|--slot-save-path|--model-draft|speculative`.
- **1.8** ADR-0047 (stable, `last_reviewed: 2026-09-02`, probes in `governance/adr-probes.v1.json`); `TokenEstimation.java:119,146`; `RAGContext.java:136-143,259-282`; `ContextWindowPolicy.java:22-31` "top rung is a BUDGET, not a fit".

### A2. OS and filesystem

- **2.1** `SyncDirectoryOps.java:58-59,424-439` `RECALL_ON_DATA_ACCESS`; `CloudPlaceholderRecorder.java:19-60` ledger row, 24 h dedup; `WorkerScanOps.java:185` `walkFileTree` default link options; `PathNormalizer.java:19-30` blanket lowercase; `RootLifecycleOps.java:47,163` dead-UNC off-thread; `03-knowledge-server.md:277-283` accepted gaps routed to the retired observations store; `library-indexing-activity-panel.md` `status: planning`, `CLOUD_PLACEHOLDER` absent from `modules/ui-web/src`. Open: `410:288,374,705,1087,1923`.
- **2.2** `WorkerIngestionAuthority.java:28-43` → `UNREADABLE`; `SyncDirectoryOps.java:323-329`, `WorkerScanOps.java:238-245` `visitFileFailed` → `log.debug` + `CONTINUE`; no `AccessDeniedException` classification in ingestion (`IndexRootLock.java:43` unrelated); `FileIntruder.java` tortures the data dir, not corpus roots. Open: `410:1087` policy row.
- **2.3** `LuceneRuntimeUtils.java:159-160` string sniff → `DISK_FULL` → `ApiErrorCode.INDEX_DISK_FULL` PERMANENT (`ApiErrorHandler.java:230`); `SqliteJobQueue.java:1898-1912` fail-open probe; `NdjsonMetricExporter.java:195-215` two-tier; `FreeSpaceCheck.java:28-55` install preflight.
- **2.4** `package-installer-win.ps1:271-285` SAC preflight; `verify-your-download.md:54-72`; `sandbox-guest-silent-test.ps1:213-268`; `lib.rs:742` `-XX:-UsePerfData`; `check-installer-execution-level.mjs:29,215` SmartScreen deferred to signing; only `Add-MpPreference` is `check-bench-state.ps1:27`.
- **2.5** `WindowsJobObject.java:32,44,53,172-175`; zero `PRIORITY_CLASS|BELOW_NORMAL|PROCESS_MODE_BACKGROUND|setPriority` hits; `885:45` decision 3 (application-level gauge + duty cycle). Open: `885:555` duty counter.
- **2.6** `LocalApiServer.java:336-357,1027`; `KnowledgeServer.java:98,263,749` ephemeral + MMF publish; `KnowledgeServerBootstrap.java:198-213` `AppInstanceLock`; `Cargo.toml:25` + `lib.rs:1470-1496` single-instance; `cut-a-release.md:305` WebView2 online-only.
- **2.7** `PolicyDrivenTikaExtractor.java:92-106` size caps; `AdversarialCorpusIngestionTest.java:78-338`; `WorkerScanOps.java:292-306` watermarks; `SyncDirectoryOps.java:44-52` 200k cap → `delete_detection_unverified`.
- **2.8** `WorkerSpawner.java:154,200` job object kill-on-close; `ExtractionSandboxCommand.java:75-96` `-Xmx` + serial GC + argfile; `ExtractionSandboxChild.java:32,123` parent-PID gate; `885` thesis "sandbox built but unreachable as shipped" (chunk 2 landed).
- **2.9** `761:55-91` three-bucket inventory (portable / needs port / needs replacement), R1 "no official Linux-CUDA llama-server prebuilt"; CI already builds + unit-tests on `ubuntu-latest` (`ci.yml:189,246,261,275`); `191:324,368` WebView comparison; `docs/comparison.md:20`.

### A3. Data safety and privacy

- **3.1** `IngestionSkipPolicy.java:28-61` (junk/VCS only), `:39` `EXEMPT_NAMES = Set.of(".env", ".gitignore")`, `:127-134` exemption short-circuits all skip rules; `ExcludeMatcher.java:11-20` user globs only (`EnvRegistry.java:1179`, no shipped default); zero classifier/redaction hits for `.ssh|id_rsa|credential|keepass|1password|Login Data|.pem`; 875 §C.9 second ingest surface uncontained.
- **3.2** `PruneOps.java:54,112-131` `pruneByPathPrefix` (chunks carry parent `path`); `RootLifecycleOps.java:365-405` `removeWatchedPath` = `core.remove-watched-root`; `SyncOps.java:105-222` sweep-based; no `ENTRY_DELETE` watch; `ReadDocumentTool.java:206` maps `not_found` at read time; `AgentRunStore` persists messages with no liveness pass; 811 frontmatter: MCP-ingested `collection=null` docs unreachable by prune.
- **3.3** `ConversationBackupController.java:23-70` + `LocalApiServer.java:693` export/import (409 unless unlocked); `DataKeyManager.java:83-84`; `installer-hooks.nsh:87-90` uninstall touches only the empty policy dir; zero `relocat|migrateDataDir|portable` hits.
- **3.4** `docs/reference/security/threat-model.md:20-55` (STRIDE, assets, boundaries, privacy-claim drift guards); ADR-0046 adversaries; one injection-adjacent hit (`:121` misbehaving MCP client); zero `prompt injection` hits in `app-services`/`app-agent` main; 767's "injection" is corpus payload integrity.
- **3.5** `ResultDisposition.java:13-40`; `FeedbackCaptureSettings.java:19-51` default-on, fail-closed on corrupt state, `PRIVACY_NOTE` at `:32-36`; `LabelProjection.java:16-40`; `StoreCatalog.java:22`.
- **3.6** `StatusRoutes.java:44` `POST /api/diagnostics/export` → local zip (`packs.ts:235`); 297 redaction; `CrashReporter.java:32`; zero `submit.*report|upload.*diagnos|support@` hits; `threat-model.md:44-50` no exporter.
- **3.7** `ApiSecurityFilters.java:422-467` token required for `POST /mcp` in prod; `setupMcpOriginValidation` `:238-241`; `mcp-production-server.md:140-152,380-399`; `CoreIntentSourceCatalog.java:62,133-154` `UNTRUSTED` tier.

### A4. Storage and performance

- **4.1** `ComponentsFactory.java:193-236` `TieredMergePolicy`; `SqliteJobQueue.java:249-254` WAL + `auto_vacuum=2`, `:1483-1516` incremental vacuum at >25% waste, no `wal_autocheckpoint`/`journal_size_limit`; `KnowledgeServer.java:1681-1735` 24 h cleanup; `IndexGenerationManager.java:752,799` GC only via `MigrationControlOps.java:270-288` `core.index-gc`; `forceMerge` only in tests + `VectorQuantizationGate.java:191`; logback `maxHistory 7` / `totalSizeCap 256MB`.
- **4.2** `04-storage-engine.md:178` quantization flag default off; `JustSearchCodec.java:39-48`; `ComponentsFactory.java:182`; `18-adapters-lucene-deep-dive.md:129-130` derived table; `SpladeEncoder.java:1161-1176` `pruneByBeta` query-side only; zero matryoshka code hits; `640:617` `index_size_bytes` excluded.
- **4.3** `spawn-isolated-test-backend.md:112-115`; `perf_gate.py:13-17` "no warmup machinery is needed"; `server.mjs:2235-2252` `indexWarmth` = needs-reindex, not residency; `302:24` JVM-only numbers.
- **4.4** `SoakSuiteTest.java:30-43,207-375`; `system-tests/build.gradle.kts:30,379-380` `includeSoakTests`; `SoakTestRunner.java:17-23`; `09-testing-strategy.md:272-275`; no workflow.
- **4.5** `run.py:78-85` search-load arms; `885:511` p50/p95/max table (no p99), `:536-542`, `:1599,1623` after-arms unrun; `ForegroundLoadInterceptor.java:17`; zero `setPriority|setMaxMergesAndThreads` hits.
- **4.6** `perf_gate.py:1-30`; `search-quality-register.md:2879` advisory tier; `ci.yml:73-100` only `test_release.py`; `search-engine-hint.mjs:102-106`; 647 status "needs a release recompose".
- **4.7** `278-decision-log.md:299-342` idle CPU spot check (2026-03); `KnowledgeServerHealthMonitor.java:159` 10 s idle cadence; `VduOfflineTriggerSampler.java:19`; `PowerStatusView.java:9-16`; `StatusLifecycleHandler.java:1177` llama-server GPU-resident at idle.

### A5. Retrieval features

- **5.1** `SearchResultMapper.java:229-240` decay (5% cap), `SearchPipelinePresets.java:65,127`; `QueryFilterBuilder.java:196,257` `modified_at` range; `QueryUnderstandingService.java:108-112` behind `JUSTSEARCH_QU_ENABLED`; zero `dateRange|modifiedAfter` in `ui-web/src`; `BrowseSurface.ts:39` `lastIndexed` folder-only.
- **5.2** `StructuredDocument.java:33-36,215-251` header-aware triplet text; `StructuredContentExtractor.java:26`; `ChunkSplitter.java:325-326` `Mode.CSV`; no numeric content field in `fields.v1.json`; 705 "no ground-truth extraction-quality metric exists".
- **5.3** `314:34-40` unchecked, "ABANDONED"; `639` stub "measurement, not a fix"; only path-level dedup (`FolderBrowseEngine.java:203`, `SuggestOps.java:45`).
- **5.4** `FolderBrowseEngine.java:72,95`; no `summarizeFolder|folderSummary` symbol; 511's "aggregate" is UI health aggregation.
- **5.5** `evidenceProjection.ts:89` `AnswerFrame`, `sv3-honesty.ts:12-15` "ONE answer-frame authority"; `VduAbstentionGate.java:11,54`; `sv3-results.ts:26,85` emptiness-only; `check-search-degradation-reason-codes.mjs:5-22` reports *why degraded*, not *how good*.
- **5.6** `23-search-pipeline-overview.md:92` AutoDetect "1,400+ formats", sandbox child for PDF/Office/archive/image; `IngestionSkipPolicy.java:42` skip list; `ContentExtractor.java:19-45` caps; `NastyCorpusTest.java:185-230` no-crash only; zero `epub|.eml|mbox|.pst` test hits; 686 OPEN "no real binary-document corpus".
- **5.7** `VisualRoutingDecision.java:20` VDU = text extraction (ADR-0018); no CLIP/Whisper-class model in the registry.
- **5.8** `searchState.ts:168-206` disposition POSTs; `KnowledgeSearchController.java:67-101`; `LabelProjection.java:17-25`; `LambdaMartTraining.java:64-100` `MIN_CONTRAST_GROUPS = 20`; `ResolvedConfigBuilder.java:1430` default `false`; `KnowledgeSearchEngine.java:206`; zero `recentQueries|queryHistory` hits; `851` deleted `queryTrail.ts`.

### A6. Product UX

- **6.1** `lib.rs:1405-1440` tray, `:1513` autostart (`--minimized`), `:1540` close-to-tray; `SettingsSurface.ts:1010-1096`; no `tauri-plugin-global-shortcut` in `Cargo.toml`/capabilities (`191:56` removed 2026-02-18 "re-added when global search bar is implemented"); single `app.windows` entry; `CommandPalette.ts` in-DOM; `191:142-158` §A design; `191:369` Wayland caveat.
- **6.2** `Cargo.toml:26` deep-link, `lib.rs:1459-1497`, `TauriDeepLinkSource.ts`; `dragDetect.ts`, `DragOverlay.ts`; `lib.rs:1083,1090` open/reveal; `installer-hooks.nsh` no `Classes` keys; `191:248-250,578` context menu Long-term C; `191:130` associations "marginal".
- **6.3** `pinnedSearchState.ts:63-130` pins; `facetChips.ts:22-27` (kind/mime/language/author), `searchFiltersState.ts:13-16` date only; `TextQueryOps.java:93` SIMPLE escapes operators; `KnowledgeSearchController.java:290-294` accepts `querySyntax`; only `'lucene'` caller `searchState.ts:414` (facet probe); `McpToolSurface.java:334-341` exposes Lucene syntax to agents; `851` deleted query trail; `RecentsMenu.ts` is a surface trail.
- **6.4** `settings-v2.v1.json` property list; `pauseIndexingDuringAi` (`:67`, `UiSettingsV2.java:16`); `IndexingRoutes.java:23-24` migration pause/resume (not ingest); `885` §Scope 3 "never a full pause … no new Head→Worker signals"; `813` cancel honesty open.
- **6.5** `check-a11y-closure.mjs`, `check-controls-a11y.mjs`, `check-contrast-matrix.mjs` via `ci.yml:154-155`; `ui_a11y_gate.py:11-12` local-first; ~15 `prefers-reduced-motion` sites; zero `forced-colors`; `200` cites `VirtualResultList.tsx` (React); 853 round 2 (13 issues, 3 SERIOUS fixed), `check-controls-a11y` accepted RED F-11.
- **6.6** `i18n.ts:1-8` Lingui removed (742); `742:436-443,128` German catalog existed; ADR-0043:80-87 scopes UI out; `index.html:2` `lang="en"`; only `SSOT/messages/errors.en.json`, `SSOT/prompts/en/`.
- **6.7** `SSOT/docs/help/*.md` (5 files); `KnowledgeServerBootstrap.java:890-949` `tryIngestHelpFiles` v2 marker; `IngestCollectionPolicy.java:37-43`; `HelpSurface.ts`; `search-ui-behavior.md:365-372,616-646`.
- **6.8** no `glossary|terminology|lexicon` artifact; `writing-docs-for-ai.md:63` anti-glossary guidance; `01-system-overview.md:24-112` sole definition site; `OperationCatalog` + `OpButton.ts` (509) for button labels only; 509 F-22/F-25 unresolved.

### A7. Verification substrate

- **7.1** no `jazzer|jqf` hits, no fuzz workflow; `AdversarialCorpusIngestionTest.java`, `NastyCorpusTest.java` deterministic; `MmfTestHarness.java:215-232` `startFuzzer`; `ui.py:315` `ui-fuzz`; `410:1350-1353,1990`.
- **7.2** no jcstress; 8 `@GuardedBy|@ThreadSafe|@NotThreadSafe|@Immutable` across 5 files; 12 files `ofVirtual`; `SearchOrchestratorVirtualThreadContextRegressionTest.java`; `398` §0 parked; stress tests `NativeSessionHandleConcurrentStressTest`, `LifecycleStressTest`, `VduConcurrentTriggerTest`; `stress-test-hint.mjs:5-8`.
- **7.3** `ErrorProneConventionsPlugin.kt` (`error("InvalidLink")`, `assemble` in `ci.yml:452`); `JvmBaseConventionsPlugin.kt:187-215` `skipPmd` default + `check`; `config/pmd/ruleset.xml` 22 rules; `SpotBugsConventionsPlugin.kt:27-63`; no `NullAway|jspecify`; `codeql.yml:3` dispatch-only; workflows' Gradle invocations: `checkLicense`, `assemble`, `:modules:*:test`, `integrationTest`, `installDist` — never `check`/`build`. 698 two fixes unpushed.
- **7.4** `MutationConventionsPlugin.kt` PIT 1.25.3 per `governance/logic-seams.v1.json` (18 seams); `registry.v1.json:8-32` `test-efficacy`; `gates/test-efficacy/strength-baseline.v1.json`; `ci.yml:210-218` "other kernel gates need inputs … this fast job does not build"; 9 jqwik classes; `JvmBaseConventionsPlugin.kt:120-142` retry; `report-flake-trend.mjs` unwired; `ci.yml:700-701`.
- **7.5** `ci.yml:154-155` → `run-ui-web-gates.mjs` (~24 static gates); recipe's typecheck/vitest line lacks the parser marker (`run-ui-web-gates.mjs:34-42`); zero workflow hits for `vitest|test:unit|typecheck`; 463 `*.test.ts`; `a11y-runner.sh` `--exit 0`; `visual-regression.cjs:1-16` baselines not committed; `ui-a11y-baseline.v1.json` description stale (§Z-3); `check-ui-step-coverage.mjs` header stale (§Z-4).
- **7.6** 28 `*.test.mjs` in `scripts/ci/`, 15 in `scripts/governance/gates/` via `run-all-tests.mjs` (`ci.yml:223-224`), `--self-test --mode gate` (`ci.yml:229-230`); ~20 hook tests via `ci.yml:118-119`; `governance/agent-hooks.v1.json` 39 hooks: 5 real bites, 8 `unit`, 26 none; `hook-integrity/enforcer.mjs:229-231` `existsSync(testPath)`; 21 hook scripts without a test sibling.
- **7.7** `scripts/jseval/pyproject.toml` no ruff/mypy; `ci.yml:88-100` one pytest file; `ci.yml:645` `cargo test --lib --locked` only; 4 `unsafe` in `updater.rs`; `ui-web/package.json:11` `lint` never invoked; no eslint config for root `scripts/**`.
- **7.8** `JobQueueMigrationTest.java:80-115`, `FileMemoryStoreTest.java:66-78`, `EmbeddingFingerprintLegacyUnattestedVectorsMigrationTest.java`; `ci.yml:102-108` harness self-tests only; `617` §10.12 Sandbox GUI-gated; `734` rounds 9-12 manual upgrade-from-release PASS.

### A8. Hygiene and toolchain

- **8.1** zero `module-info.java`, 2 `package-info.java`; `UnreferencedCodeTest.java:445,534`; `NativeSessionHandleBuilderVisibilityTest.java:11`; `19-module-architecture.md` no visibility rule.
- **8.2** `gates/todo-fixme/enforcer.mjs:3`, baseline 0 lines, globs `modules/**/src/main/**/*.java` + `ui-web/src/**`; 21 TODO / 12 FIXME / 3 XXX outside globs; `check-suppression-ratchet.mjs`; `378:7` "In progress" 2026-04-08.
- **8.3** `gates/*/baseline.txt` current-value TSVs (`git-base` diff); `build.gradle.kts:268` size/count ratchets removed at 634; `analyze-trends.mjs`, `baseline-economics.mjs`, `ci-walltime-trend.yml` measure agents/CI only; no complexity metric.
- **8.4** zero `featureflag|feature_flag`; `gates/config-surface/enforcer.mjs:5,16-21` (only gate wired to public CI, `ci.yml:217-218`); `256` done, `532` open since 2026-05-20.
- **8.5** `JvmBaseConventionsPlugin.kt:69` JDK 25; `gradle-wrapper.properties` 9.6.1; `gradle.properties` config-cache `warn`; `modules/ui/build.gradle.kts:1012-1061` AOT record/create, consumed `WorkerSpawner.java:446` + `lib.rs:750`; `ArchivingReproduciblePlugin.kt:11-12`; `verification-metadata.xml` 1772 components, `verify-signatures=false`; 792 §3-4 unexecuted.
- **8.6** only `792:42` Node EOL prose; `dependabot.yml` monthly (792 §2 "never bumped a single Java library"); no `renovate.json`; 792 §25.
- **8.7** `OpenApiController.java:20-33` (`GET /api/meta/openapi.json`, structural only, runtime endpoint); `OpenApiControllerTest.java:34`; `route-manifest.snapshot.json` committed sibling; `docs-lint.yml:3` dispatch-only, `verify-canonical-doc-links.mjs` also in `ci.yml:350`; no doctest.
- **8.8** 8 generators in `scripts/codegen/`, matching `check-*-regen.mjs`; `registry.v1.json` `ssot-catalog-sync`; `buf.gen.yaml:4-8` TS proto retired; `ci.yml:338,413` only two regen references; `run-ui-web-gates.mjs:8-10` prior RED-for-weeks incident; `634:135`, `631:145`.

### A9. Distribution and release

- **9.1** `cut-a-release.md:310-323` three modes, fail-closed under `JUSTSEARCH_REQUIRE_SIGNING`; `sign-windows.ps1:208` all PEs; `sign-vendored-mirrors.yml:1-13` + `packaging/signed-mirrors.v1.json`; `verify-your-download.md:12-13,54-82` "currently unsigned"; 760 "remaining owner-gated: cert/vendor decision".
- **9.2** `packaging/winget/README.md:1-8` skeleton with `TODO-GA` placeholders; ADR-0024:100-101 MSIX rejected; no workflow step; no Scoop/Chocolatey.
- **9.3** `191:439-447,577` browser extension Long-term K; `191:146`, `512:592`, `521:1276-1298`, `extension-substrate-conventions.md:106` prior-art mentions only.
- **9.4** `CHANGELOG.md:1-20` scaffold; `build-installer.yml:469-479` blurb + `--generate-notes`, draft-first; `cut-a-release.md:298-308` manual list; no `ROADMAP*`; no CI reference to `CHANGELOG`.
- **9.5** `gen-notices.mjs:1-50,314`; `check-notices-regen.mjs:3-5`; `modules/ui/build.gradle.kts:425-440` `generateOnnxNotice`; `dump-cargo-licenses.mjs`; 632 Stage G deferred.
- **9.6** `11-index-schema-migration.md:51-55` fingerprint + `BLUE_GREEN_MIGRATE`; `model-inventory.md:262-273` build/verify scripts; `inference-runtime-register.md:138,206` `build.json` provenance + the interrupted-build incident; `model-registry.v2.json:21-99`; 826 "never persists", BLOCKED on 819; 840 phases 2-6 remain.
- **9.7** `updater.rs:758-759,797-838,906-918` Ed25519 verify; `cut-a-release.md:183-186`; `build-installer.yml:490-500` round-trip; `ResumableFetch.java:76`, `DownloadExecutor.java:248-271`; `617:188-192,252-253` forward-only.

### A10. Project operations

- **10.1** `CONTRIBUTING.md:5-11,47-95,146-158`; `onramp-smoke.yml:1-6` dispatch-only; `scripts/setup/bootstrap-node-win.ps1` only; no `.devcontainer/`; `633:58`.
- **10.2** `.github/CODEOWNERS:1-15` single owner; `MAINTAINING.md:1-102`; `SUPPORT.md:3-4`; zero `bus factor|succession|hand-over` hits; 634 runbook off-repo.
- **10.3** `NON-GOALS.md:8-9`; `README.md:200`; `08-observability.md:1234` perf KPIs only; 832 measures eval lanes, not product.
- **10.4** `.github/ISSUE_TEMPLATE/*`, `PULL_REQUEST_TEMPLATE.md`, `SECURITY.md:10-27`, `CODE_OF_CONDUCT.md`, `SUPPORT.md:7-24`, `.github/cla.yml`.
- **10.5** `runtime-contract.md:60-102` stability policy, 90-day window, 0.2.0 override row, surface classification; `RuntimeContract.java`, `McpContractVersions.java`; `28-runtime-contract.md:38-48`; `packaging/mcpb/manifest.json:6` now 0.2.0 (832 D.1 drift fixed).
- **10.6** `mcp-production-server.md:42-83`; `packaging/mcpb/*`, `pack-mcpb.mjs`, `check-mcpb-consistency.mjs`; `packages/plugin-api-ts/README.md:1-20` (authoring SDK, unpublished); zero `langchain|llamaindex` design hits; 660 `open` since 2026-06-28.
- **10.7** 98/214/238/512/799/821 §2c one-offs; no register in `governance/` (56 files); `check-tempdoc-status-staleness.mjs:14-17` report-only; sample of 13 tempdocs 700-880: 4 with open-items sections (`725:696,1505`; `735:521`; `860:1030`; `880:786`); 77/175 overall; `880:812` → 532 still open, `880:818` `AgentTimeoutsTest.java:224` still carries the stale citation, `880:826` item in no later tempdoc.
- **10.8** `governance/adr-probes.v1.json:5` (43 probes, premises), `reviewStaleDays: 183`, `adr-coverage/enforcer.mjs:365-369,490-496`; only 2/48 ADRs have `## Follow-up`, 36/48 have `## Consequences`; ADR-0011 retired 2026-09-02 (`RemoteShard` zero occurrences); ADR-0013 follow-up (FST compiler) never implemented, superseded by 0043 but text stands; ADR-0026 stress cadence accepted-unmitigated; ADR-0042 has no Consequences section.
