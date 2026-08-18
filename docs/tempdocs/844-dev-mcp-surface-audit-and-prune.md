---
title: "Dev agent-tool surface audit: what agents actually invoke on the justsearch-dev MCP server, what fails, what nobody has ever called, and the two structural reasons 81% of local-API traffic bypasses the surface entirely"
type: tempdocs
status: "OPEN — analysis complete and reproducible (2026-08-18); nothing implemented. Owner decisions pending on D1-D4 (§8). Hot-reload coherence RESOLVED 2026-08-18: verdict INCOHERENT, independently re-verified against source (§5.6). Recommendation REVISED same day from RETIRE to FIX-THEN-SURFACE after owner challenge: the retire estimate did not survive re-examination and the value model (warm models across a code change, not a saved spawn) was never weighed — see §4.2. THEORIZED 2026-08-18 (§11): the endstate thesis is a single run registry as keystone, three tool classes with per-class middleware, and specialization into arbitration over transport; #845 reserved as the candidate registry lane. DIRECTION SET 2026-08-18 (12): the through-line is that the surface asserts what it has not verified (7 instances, 1 property); criterion = a dev tool must not report state it did not verify. 12.3 re-sequences 9 by that criterion and drops P3b (allowlist) + defers P8. IN EXECUTION on branch worktree-844-dev-surface-honesty. Adopts 6 inbox conditions (§7) that should be closed by this lane rather than re-logged."
created: 2026-08-18
author: agent session b73007cd (Opus 5, 1M context) — chartered by the owner after a session-opening question about the state of dev-related MCP capabilities
category: agent-process / dev-tooling / mcp
related:
  - 254-mcp-dev-tools-issues              # the last dev-MCP audit (status done, 2026-03-03) — superseded by this doc
  - 305-hot-reload                        # shipped hot reload 2026-03-14, before worktrees/distFrom/leases matured
  - 606-dev-stack-takeover-owner-liveness # the ownership/verdict model, fully adopted (92/92 starts)
  - 684-dev-workflow-tooling-hardening-batch # sibling surface (scripts/dev), same maintenance neighbourhood
  - 735-agent-surface-seam-consolidation  # owns the PRODUCT MCP surface — explicitly NOT this doc's scope
  - 770-agent-tool-surface-economy-lane   # product tool-surface economy — likewise out of scope here
  - 841-agent-prompt-cache-efficiency     # same corpus, adjacent method; its "no context bloat" finding bounds §6.4
  - 271-backend-lifecycle-isolation       # the ownership/interference model §11.3 extends; already flagged raw runHeadless
  - 542-operation-scoped-lease-taxonomy   # "extending the 271 ownership model" — the op-lease registry §11.3 builds on
---

> **Scope boundary.** This document is about the **dev** agent-tool surface — the
> `justsearch-dev` MCP server that agents use to drive the local stack, plus the
> non-MCP surfaces that compete with it. The **product** MCP surface (JustSearch
> exposing search to external agents) is owned by 735 / 770 /
> `843-deepseek-harness-relevance-and-mcp-interop` and is deliberately untouched here.
> Where the two meet (§5.3: `/mcp` is unreachable from the dev tools), the finding is
> stated as a dev-surface gap, not a product question.

## 1. Method, and what it can and cannot support

Corpus: `~/.claude/projects/F--justsearch-public*/**/*.jsonl` — 878 transcripts spanning
~66 main agent sessions, ~6 weeks (the corpus starts ~2026-07-04 and runs to 2026-08-18).
This is the same substrate `841-agent-prompt-cache-efficiency` used.

Extraction: parse each transcript, index `tool_use` blocks by `id`, join `tool_result`
blocks by `tool_use_id`. Invocation counts are `tool_use` blocks only — a naive
`grep` over tool names double-counts, because each call appears once as the call and
again as the result reference, and every session's deferred-tool listing mentions all
16 names twice more. Error classification: `is_error === true`, or a result body
matching the literal ok-false or error-object markers.

**Honest limits.**

- Counts are *invocations*, not agents' intent — a tool never called may be unknown, broken,
  or genuinely unneeded, and the three are not distinguishable from counts alone. Each
  zero-use finding below therefore carries a separate cause, evidenced independently.
- The ok-false regex has a small false-positive rate: one `tail_log`-style result was
  classified as an error because the *worker log content it returned* contained the literal
  string. Error counts are therefore an upper bound, ±1 per tool.
- Byte totals in §6.4 are **bytes, not tokens**. Screenshot results are base64 images, which
  tokenize as images (~1-2k tokens each) regardless of their byte size. The byte ordering
  holds; do not convert it to a token claim without re-measuring.
- The corpus rolls. These numbers are re-derivable only while the transcripts survive.
  A repeatable reader (`scripts/agent-analytics/`, on the model of `cache-efficiency.mjs`)
  is proposed as P7 rather than assumed.

## 2. Inventory: four servers, and three competing non-MCP surfaces

| Server | Configured in | Tools | Note |
|---|---|---:|---|
| `justsearch-dev` | `.mcp.json` -> `scripts/dev/justsearch-dev-mcp/` (4,264 LoC) | 16 | project-owned; the subject |
| `github` | `.mcp.json`, `npx @modelcontextprotocol/server-github` | 26 | PAT is the placeholder literal (§4.1) |
| `claude-in-chrome` | harness | 24 | ungoverned by any repo rule (§6.4) |
| `context7` + Gmail/Drive/Calendar | user scope / connectors | 43 | incidental |

The dev surface does not stand alone. The same jobs are served by **`jseval`**
(91 subcommands, including an `ops` group with `dev`, `preflight`, `search`, `logs`,
`log-path`), by **`dev-runner.cjs`** invoked directly, and by **raw `curl`**. §6.1 shows
this is not redundancy-in-name-only: the surfaces cannot see each other's backends.

## 3. Measured usage — `justsearch-dev`, 379 invocations

| Tool | calls | sessions | err% | dominant failure |
|---|---:|---:|---:|---|
| `start` | 92 | 16 | 11% | `UNHANDLED` x7 — "Head dist not found" |
| `stop` | 76 | 16 | 0 | |
| `quick_health` | 61 | 20 | 0 | |
| `fetch_api_json` | 40 | 9 | 18% | `jsonPath` miss; `response_too_large` |
| `ai_activate` | 35 | 7 | **43%** | "Variant not installed: cuda12" x9 |
| `ingest` | 24 | 10 | 13% | `path` vs `paths`; repoRoot confinement |
| `api_call` | 22 | 8 | **45%** | path-not-allowlisted x5 |
| `preflight` | 10 | 9 | 0 | |
| `tail_log` | 10 | 6 | 10% | (likely the ±1 false positive) |
| `search_query` | 8 | 5 | 13% | |
| `status` | 1 | 1 | 0 | |
| `agent_chat`, `reload`, `capture_evidence`, `validate_evidence`, `acquire_when_free` | **0** | 0 | — | never invoked |

Parameter adoption on `start`: `leaseDurationSec` **92/92** (735 G6 landed completely),
`distFrom` **68/92** (worktree-staged launch is the dominant mode), `hotReload` **1/92**.

`tools/list` payload: 16.3 KB (~4.1k tokens) + 1.5 KB `instructions`. The five zero-use
tools are 5.0 KB of that — **31% of the schema payload for 0% of the traffic.**

## 4. Prune candidates

### 4.1 `github` MCP server — remove from `.mcp.json` (P1)

One invocation in six weeks, and it **errored**. It cannot work: `.mcp.json` still carries the
literal placeholder PAT, identical to `.mcp.json.example`. Meanwhile agents made 478
`run-gh.mjs` and 347 raw `gh` calls — the `exec-substrate-hint` hook already routes them there.
Cost of keeping it: an `npx -y` package fetch + process spawn on every session start, and 26 tool
names in every prompt, for zero capability. The pinned package is also the archived upstream,
superseded by GitHub's own server.

### 4.2 Hot reload — FIX-THEN-SURFACE (P8; awaiting D2)

`hotReload: true` on 1 of 92 starts; `reload` invoked 0 times. The §5.6 investigation returned
**INCOHERENT**, and its load-bearing claims were re-verified independently against source (not
taken on the auditor's word); all five held. The *diagnosis* below stands in full.

**The recommendation was revised from RETIRE to FIX-THEN-SURFACE** (owner challenge, same day,
after re-examining the code the retire estimate rested on). Two things did not survive that
re-examination:

- **The estimate.** RETIRE rested on a "multi-day rebuild" figure driven mainly by the
  mixed-classpath item. But the Worker is launched with `-cp <workerLibDir>/*`
  (`WorkerSpawner.java:583-586`), and `addDevHotReloadFlags` already computes the exact classes
  dir 40 lines below (`:944-947`) and passes it as the sysprop nothing reads. Putting that path
  ahead of the jar wildcard, inside the existing `DEV_HOTRELOAD` gate, is the fix — which is what
  305 Phase 2's child classloader was for, reached by classpath ordering instead. Of the six
  repair items, two are trivial (ownership gate, `withStaleness`), two are small (compile root
  from the run record; classes-dir ordering), one is small-medium (per-run JDWP port plus an
  identity check — and the already-computed `DEV_HOTRELOAD_CLASSES_DIR` can serve as the identity
  token), and the sixth (JBR staging for structural changes) should simply be **dropped**:
  method-bodies-only is honest and already reported via `structuralChangeDetected`.
- **The value model.** RETIRE compared hot reload against "a restart" generically. What
  `DevReloadManager` actually preserves is `ModelContext` — embedding service, compat controller,
  NER, SPLADE — across a service reconstruction (`DevReloadManager.java:130-150`). In a repo whose
  warm path is ~40s to worker-ready largely because of ONNX loading, the capability is
  *keeping models warm across a code change*, not saving a process spawn. That was never weighed.

The zero-usage figure cannot arbitrate this: the feature is off by default and its documentation
describes a mode that does not exist, so 0/878 measures visibility, not value.

**Conditions attached to the repair**, so this does not become an open-ended rebuild:

1. **A live regression test is now required.** The audit's "a live test would add nothing" was
   sound *for retiring* — it cannot change an incoherence verdict, and demonstrating the cross-tree
   case means corrupting a peer's stack. It does not survive the switch to repairing:
   `DevReloadManager` has never been exercised in 878 transcripts, and "reconstruct services while
   keeping models alive" is where subtle state bugs live. Per `audit-without-test`, done means a
   green test that edits a method body, reloads, and asserts new behaviour over HTTP with encoders
   still warm.
2. **The ownership gate lands regardless of D2** — §6.2 covers `ingest`, `reindex`, `gc` and
   migration too, and must not ride on the hot-reload decision.
3. **Default it on and set a falsifier.** If the reload path is still unused ~6 weeks after the
   repair ships with `hotReload` defaulted true, retire it then, on clean evidence.

The capability is not merely unused — it is unsafe when used, and its documentation asserts a
behavior the code refutes:

- **The advertised degraded mode does not exist.** `server.mjs:623` tells agents "Without
  hotReload on start, reload still pushes bytecode (method-body changes only)". But
  `WorkerSpawner.addDevHotReloadFlags` returns early unless `EnvRegistry.DEV_HOTRELOAD` is true
  (`WorkerSpawner.java:940-941`), and that same early return guards the `-agentlib:jdwp` flag
  (`:952`). With no JDWP listener there is nothing to push to. 91 of 92 starts are in this state.
- **305 Phase 2 was never built.** `DEV_HOTRELOAD_CLASSES_DIR` is written at
  `WorkerSpawner.java:947` and declared at `EnvRegistry.java:954-955`. Those are its *only two*
  references outside build output — nothing reads it. The child classloader the sysprop exists to
  feed does not exist.
- **`HotSwapPush` has no target identity check.** It sets hostname `127.0.0.1` and a port and
  attaches (`HotSwapPush.java:85-91`). Whatever is listening on 5005 receives the bytecode.
- **`reload` has neither an ownership nor a staleness projection.** `buildOwnershipProjection` /
  `withStaleness` call sites end at `server.mjs:1879` / `:2451`; the reload handler begins at
  `:2551`. Confirms `docs/observations.md:354` at the code level.

**Predictable evasions to reject, in both directions.** If the decision is to retire: "disable it
now, sweep the code later" is the follow-up-that-never-comes pattern `retire-with-a-sweep` names,
and tempdoc 742's ~350-file corpus is what it produces — sweep in one change or keep it. If the
decision is to repair: "ship the fix, add the test after" is the same move wearing the opposite
costume, and condition 1 above exists to pre-empt it.

**Neither path closes §6.2 on its own.** Ownership gates only `start`, so `ingest`, `reindex`,
`gc` and the migration endpoints stay callable against a peer's stack whether `reload` is repaired
or removed. Retiring `reload` removes that hole's sharpest edge, not the hole. §11.4 argues the
class-wide fix is the same work either way.

### 4.3 `capture_evidence` / `validate_evidence` — retire the tools, KEEP the format (P1)

Zero calls, and the cause is recorded: `capture_evidence` **crashes on Windows** with a
libuv fail-fast (`Assertion failed: !(handle->flags & UV_HANDLE_CLOSING), src/win/async.c`)
after capturing api-status/api-health, forcing a fallback to manual HTTP
(`docs/observations.md:580`, 2026-07-07).

**Precision required here.** The EvidenceBundle *substrate* is live:
`scripts/evidence/validate-evidencebundle-v1.mjs` is used by the `installer_verify` job
(`.github/workflows/build-installer.yml:515-545`), which auto-enables bundle capture whenever
`CI` is set in the environment. Pruning the MCP wrappers must not sweep the schema, the
validator, or the CI path. This is the inverse of the usual `retire-with-a-sweep` error:
over-sweeping, not under-sweeping.

### 4.4 `agent_chat` — retire (P1)

Zero calls. Superseded in practice by browser-driven UI validation (1,217 `claude-in-chrome`
calls) and by `jseval` harnesses. No recorded defect; simply unused.

### 4.5 `status` -> fold into `quick_health` (P1)

1 call vs 61 for `quick_health`, whose own description tells agents to prefer it. Fold as
`quick_health { detail: "full" }` rather than keeping two orientation tools.

### 4.6 Explicitly NOT a prune candidate: `acquire_when_free`

Zero calls, but it is the documented remedy for `OWNER_CONFLICT`, which fired exactly once
in the corpus. It is unused because contention is rare, not because it is wrong. Keep — and
fix the fact that it is missing from every document that lists the tools (§6.3).

## 5. Fix / extend items, ranked by measured pain

### 5.1 `start`'s worktree-dist failure — 7 of 10 `start` errors (P2)

Agents pass `distFrom: <worktree>`; `start` fails with error code **`UNHANDLED`** carrying a
perfectly actionable message ("Head dist not found at ...ui.bat. Make this checkout dev-ready:
`node scripts/dev/prepare-worktree.cjs`"). Two distinct defects:

1. A known, expected, recoverable condition is classified as an unhandled exception, and is
   not among the four admission error codes the reference doc documents.
2. `preflight` advertises that it checks "worker dist built" — but takes no `distFrom`, so it
   checks the *invoking* checkout while `start` checks the *target*. Preflight passes; start
   fails. That is a false green.

Two further failures were `INVALID_DIST_FROM` from passing a bare worktree name
(`"round14"`, `"validate-main"`) instead of a path — resolve those instead of rejecting them.

The fix was already proposed in the inbox on **2026-07-02** (`obs:dev-runner-drift`,
"worth a hook-hint or MCP tool default when sessionId resolves to a worktree cwd") and never
done. It has since produced 7 measured failures.

### 5.2 `ai_activate`'s 43% failure rate (P4)

9 of 15 failures are `Variant not installed: cuda12` on fresh worktree data dirs — the same
condition logged in a prior session's shard (`bccfc163`) on 2026-08-14 ("the two provisioning axes
(runtime exe vs. installed package) are not distinguished in the failure message",
`scripts/dev/dev-runner.cjs:457`). Three more are "No chat model configured", whose workaround
(`POST /api/settings/v2` with `llm.modelPath`) has been sitting in the inbox since 2026-06-06
marked "worth a /dev-stack skill note".

This matters more than its call count suggests: CLAUDE.md's `use-every-verification-tier` rule
**mandates** `ai_activate` before declaring an AI-facing feature verified. A mandated tool that
fails 43% of the time on a precondition trains agents to accept `AI_OFFLINE` as a wall — the
exact failure the rule exists to prevent. The precondition belongs in `preflight`/`quick_health`,
not in a post-hoc error.

One of the errors also carries a real bug: a "Configured model does not exist" message rendering
the path as `F:justsearch-publicmodelsQwen_Qwen3.5-9B-Q4_K_M.gguf` — **path separators eaten**
somewhere in the message-formatting or config-resolution path.

### 5.3 The 81% bypass (P3 for coverage; the preference half is §6.1)

268 raw HTTP calls to the local API from Bash vs 62 through the MCP tools.

| Path | Bash hits | Reachable via MCP? |
|---|---:|---|
| `/mcp` | 43 | NO — not allowlisted |
| `/api/status` | 30 | yes (`fetch_api_json`) |
| `/api/knowledge/status` | 21 | yes (`api_call`) |
| `/api/health` | 19 | yes (`fetch_api_json`) |
| `/api/mcp/token` | 10 | NO |
| `/api/knowledge/search` | 9 | yes (`search_query`) |
| `/api/knowledge/retrieve-context`, `/api/memory`, `/api/knowledge/disposition`, `/v1/models`, `/api/chat/ask`, `/api/ai/install/plan-preview`, `/api/inference/runtime/manifest` | 25 | NO |

Two separable problems. **Coverage**: the NO rows are simply missing, and `/mcp` +
`/api/mcp/token` at 53 combined hits is the single largest gap — agents dogfooding the product
MCP endpoint cannot reach it from the dev tools. Add also `/infra/capabilities` and
`/infra/health`, which **CLAUDE.md Hard Invariant #4 names as the way to verify `host.*`
contract versions** and which the dev surface cannot reach at all (already logged,
`docs/observations.md:356`, 2026-07-16). A Hard Invariant pointing at an endpoint the sanctioned
tooling cannot call is a contradiction that should not survive this lane.

**Preference**: even covered endpoints are hit with `curl`. §6.1 explains most of it.

### 5.4 Projection asymmetry and the expensive-failure default (P3)

- `fetch_api_json` has `jsonPath`; `api_call` does not — an agent tried and got
  `unrecognized_keys`. This matters because search results average 10 KB.
- `fetch_api_json`'s `jsonPath` is a naive dot-path `reduce` (no array indexing), and **on a miss
  it discards the parsed JSON and returns the raw text tail** — the most expensive possible
  failure mode. Returning the available keys at that level instead converts a token bomb into a
  hint. 4 of 7 `fetch_api_json` errors are this.
- `maxBytes` reads as a truncation budget but is a hard fetch cap that errors `response_too_large`.
  Agents passed `maxBytes: 2000`, and `outputMode: "compact"` with `maxBytes: 3000`, to *reduce*
  output and made it fail instead — twice. Either truncate with a notice, or rename it.

### 5.5 `ingest` vs the mandated scratchpad (P3)

`ingest` rejects any path outside `repoRoot`. The harness *mandates* the session scratchpad for
temporary files. An agent staged a corpus there, as instructed, and was refused
(`ingest path must be under repoRoot`). Two agents also passed `path` instead of `paths`.
Direct rule conflict; allow the session scratchpad, or say why not.

### 5.6 Hot reload — INCOHERENT (verdict 2026-08-18)

The hypothesis in the first draft of this section — that `reload` resolves its *target* from one
root and its *bytecode* from another — is **CONFIRMED**, and the picture is worse than the
hypothesis.

`reload` reads `dataDir` from the global run record under `mainRepoRoot`
(`server.mjs:2573-2576`) but derives `classesDir`, `hotSwapScript` and the Gradle `cwd` from the
caller's `repoRoot` (`:2582-2583`, `:2592`), which is `process.cwd()` frozen at MCP-server launch
(`paths.mjs:29-42`; `.mcp.json` sets neither cwd nor env). `start` honours `distFrom` by swapping
to an effective root (`server.mjs:700-721`); `reload` has no equivalent. Under `distFrom` — 68 of
92 starts — the two roots are different trees **by construction**.

**The three cases:**

| Case | Outcome |
|---|---|
| (a) main-checkout stack, main-checkout caller | works — but only with `hotReload: true`; otherwise silently misfires |
| (b) `distFrom: A`, caller in worktree A | works only if the MCP server's cwd happened to be A *and* `hotReload: true` — coincidence, not design |
| (c) `distFrom: A` by agent A, caller agent B in worktree B | **silently misfires**: B's bytecode is redefined into A's JVM, reported as success |

**Four silent failure modes** (none surfaces as an error to either agent):

1. **Cross-tree injection.** `redefineClasses` matches by class *name* only
   (`HotSwapPush.java:111-118`), so B's divergent bytecode replaces A's in-memory code.
2. **Falsified build stamp.** On `hotSwapOk` the handler copies the *caller's* dist stamp into the
   *owner's* data dir (`server.mjs:2632-2640`), defeating 371's stale-JVM detection for everyone
   afterwards. (Precision: this write *is* gated on `hotSwapOk` — but see #4 for why that gate is
   weak.)
3. **Unauthorized service teardown.** The signal write (`:2645-2652`) is gated only on
   `signalFile` being non-null — **not** on `hotSwapOk`. A deliberate comment at step 3 says
   "Continue to signal write so service reconstruction still happens", so even a *failed* push
   quiesces and reconstructs a peer's Worker services.
4. **False success with zero classes pushed.** If no changed class is loaded in the target VM,
   `HotSwapPush` prints "None … are loaded", exits 0 and still updates its marker file
   (`HotSwapPush.java:121-123, 150`) — so the next call sees nothing changed either, and
   `hotSwapOk` is `true` throughout.

**Mixed-classpath state is reachable even in case (b).** The running Worker's classpath is the
`installDist` jar set; the push comes from `build/classes/java/main`. Only already-loaded classes
are redefined; the remainder later load from the **stale jar** — while the stamp says current.

**Was it ever coherent?** Briefly. 305 (2026-03-14) assumed one checkout, one stack, one agent,
one JDK, and verified exactly that. Four things invalidated it: worktrees + `distFrom` split the
launch tree from the caller tree; the lease/ownership model added an owner concept `reload` never
adopted; 696's JDK pinning hard-selects Temurin ≥24 while structural hot-swap needs a JBR that
nothing in the repo stages, leaving method-bodies-only; and Phase 2's classloader was never built.

**A live test would add nothing.** Every step is statically decidable, and the only way to
demonstrate case (c) is to corrupt a peer's stack. One item is UNVERIFIED and does not need a run:
whether this machine's ambient `JAVA_HOME` happens to point at a JBR build — the repo stages none,
so Temurin is the default.

## 6. Structural findings

### 6.1 Two dev-stack lifecycles that cannot see each other

The MCP tools resolve the backend from the dev-runner's `run.json`. `jseval` starts its own
backend on a hardcoded `33221` (`scripts/jseval/jseval/backend.py:21`; `commands/_common.py:14-22`
documents the two base URLs as deliberately not a dedupe target). Agents mention `jseval` in 1,406
Bash commands against 379 total MCP calls — so during the single most common activity, the MCP
tools are structurally blind and `curl` is the rational choice.

An `apiPort` escape hatch exists on the read tools (`resolveApiBaseUrl`,
`server.mjs:588-592`) and would bridge this today, but it is documented nowhere and was used 8
times — all against port 8090, never 33221.

This has already cost a measurement. A prior session shard (`bccfc163`), 2026-08-14: *"A runHeadlessEval
Head+Worker started outside the dev-runner is invisible to `quick_health` (lease only knows runs
it started), so a 'free' verdict can precede a 100%-GPU neighbour — contaminated a measurement
round."*

Minimum fix: `quick_health` probes for *any* JustSearch backend on the known ports and reports
unowned ones. Better fix: `jseval`'s backend registers in the same run registry, so one authority
answers "what is running".

### 6.2 Ownership gates only `start`

`docs/observations.md:354` (seen: 3, first 2026-06-17): a non-owner can `POST /api/knowledge/ingest`,
`/api/indexing/reindex|gc|migration`, or `reload` against a peer's running stack with no owner
check. Ownership grants the right to *spawn*, not exclusivity over the *mutating* surface. Under
3-4 parallel agents this is a live corruption path, and §5.6 may show `reload` is its sharpest edge.

### 6.3 The reference doc is forked into the skill, and both are wrong

`docs/reference/contributing/mcp-dev-tools.md` and `.claude/skills/dev-stack/SKILL.md` share
**114 identical lines**, including the same tool table and the same *incorrect* endpoint mapping
(`effective_config` -> `/api/config/effective`; the code maps it to `/api/debug/effective-config`,
`server.mjs:930`). Both are at `mcp-dev-tools.md:77` and `SKILL.md:145` — the same wrong line in
two files is the signature of a copy, not of two independent descriptions.

Tool count as stated: **15** in the reference doc ("exactly these tools"), **15** in the skill,
**15** in the server's own `initialize.instructions` (`server.mjs:611`), **14** in the harness
header (`justsearch-dev-mcp-harness.mjs:7`). Actual: **16**. `acquire_when_free` is registered
(`server.mjs:1857`) and appears in none of the four inventories.

Also drifted out of the doc: `/api/indexing-roots/substrate`, `/api/indexing-roots/preview`,
`/api/indexing-jobs/failed/by-prefix`, `/api/action-ledger` are allowlisted in code and absent
from the reference's allowlist table.

**Nothing in `governance/consult-register.v1.json` covers `scripts/dev/`.** This is exactly the
`representation-drift` class the register machinery exists for, in the one place the register does
not reach — so every fix in §5 will silently re-drift unless a sync check lands with it.

### 6.4 Cost is inverted relative to governance

Tool-result **bytes** across the corpus (73.3 MB total): `claude-in-chrome` is **49.7 MB (68%)**
from 647 calls (`browser_batch` 172.9 KB/call, `computer` 39.1 KB/call). All of `justsearch-dev`
is **0.3 MB (0.4%)** from 379 calls. *(Bytes, not tokens — see §1. Images tokenize differently;
the ordering holds, the multiple does not.)*

Browser use is concentrated in 17 sessions and is mostly local (`127.0.0.1` dev UI), i.e.
overlapping `jseval ui-shot`'s territory — which writes PNGs to disk and returns a `.measure.json`,
and whose own skill says to judge from the measure facts, not the PNG. The remainder is genuine
external design research (motion, magicui, tailwindui) that `ui-shot` cannot do.

Meanwhile CLAUDE.md and `.claude/rules/*.md` say **nothing at all** about the browser surface.
The most expensive agent capability is the least governed one. This is not an argument to ban it —
it is an argument that the always-loaded layer is allocating its bytes by habit rather than by cost.
Note `841-agent-prompt-cache-efficiency` measured no context bloat overall, so this is a
*composition* question, not a volume alarm.

## 7. Inbox conditions this lane adopts

These are already in the conditions store and should be **closed by this lane, not re-logged**.
Several are at `seen: 3-4` — the repeat counts are evidence the surface has an unworked backlog,
not that the notes were unclear.

| Condition | Lands in |
|---|---|
| `obs:dev-runner-drift` — `distFrom` default drift, hook-hint proposed 2026-07-02 | §5.1 |
| `obs:server` — ownership gates only `start` (seen: 3) | §6.2 |
| `observations.md:356` — `/infra/capabilities` + `/infra/health` unreachable | §5.3 |
| `observations.md:580` — `capture_evidence` libuv crash on Windows | §4.3 |
| `observations.md:355` + `:470` — fresh worktree has no chat model; settings/v2 workaround undocumented | §5.2 |
| shard `bccfc163` 2026-08-14 x2 — cuda12 variant message; runHeadlessEval invisible to `quick_health` | §5.2, §6.1 |

## 8. Owner decisions

- **D1 — prune `github` from `.mcp.json`?** Recommend yes (§4.1). No capability is lost; it has none.
- **D2 — hot reload: repair or sweep?** No longer blocked. §5.6 returned INCOHERENT; §4.2 now
  recommends **FIX-THEN-SURFACE** — five scoped items (JBR staging dropped), a required live
  regression test, default-on, and a ~6-week falsifier. Estimated at a focused day with tests,
  not the multi-day rebuild the first pass assumed. The capability being bought is warm models
  across a code change, not a saved process spawn. Choosing RETIRE instead is still defensible if
  the answer to "does anyone iterate on Worker Java against a live stack?" is no — but that is an
  empirical question the falsifier settles, and §11.6 argues the repair is mostly Class-C
  middleware that §6.2 needs regardless.
- **D3 — unify backend discovery?** Minimum (`quick_health` probes unowned backends) vs full
  (`jseval` registers in the run registry). Recommend the minimum first; it is the half that already
  cost a measurement.
- **D4 — govern the browser surface?** A short rule on when `claude-in-chrome` beats
  `jseval ui-shot`. Costs always-loaded bytes, which the 620 ratchet caps — so this is a genuine
  trade, not a free add.

## 9. Proposed sequencing

> **Execution order superseded by §12.3.** The table below defines the *items* (P-ids are stable
> references used throughout this document); §12.3 re-orders them by the honesty criterion and
> drops two — P3b (allowlist additions) and, for now, P8. Read §12.3 for what to do and in what
> order; read this table for what each item is.

| # | Item | Depends on |
|---|---|---|
| P1 | Prune: `github` server; `agent_chat`, `capture_evidence`, `validate_evidence` wrappers; fold `status` into `quick_health`. Sweep the count in all four inventories (§6.3) in the same change. | D1 |
| P2 | Fix `start`: real error code for missing dist, `preflight { distFrom }`, bare-worktree-name resolution. | — |
| P3 | Allowlist gap: `/infra/capabilities`, `/infra/health`, `/mcp`, `/api/mcp/token`, `/api/chat/ask`, `/api/knowledge/retrieve-context`, `/api/memory`. Add `jsonPath` to `api_call`; `jsonPath` misses return available keys; `maxBytes` truncates instead of erroring; `ingest` accepts the session scratchpad. | — |
| P4 | `ai_activate` preconditions surfaced in `preflight`/`quick_health`; fix the eaten path separators. | — |
| P5 | `quick_health` probes for unowned backends (§6.1). | D3 |
| P6 | De-fork the doc/skill; add a `check-dev-mcp-doc-sync` gate asserting tool list + endpoint keys + allowlist against `server.mjs`; register `scripts/dev/` in the consult register. | P1-P5 |
| P7 | Repeatable reader in `scripts/agent-analytics/` so §3 is re-derivable after the corpus rolls. | — |
| P8 | Hot reload — act on D2 (§4.2, §5.6). **If FIX-THEN-SURFACE (recommended):** resolve the compile root from the run record (mirror `start`'s swap at `server.mjs:700-721`); put the classes dir ahead of the jar wildcard inside the existing `DEV_HOTRELOAD` gate (`WorkerSpawner.java:583-586`, using the path already computed at `:944-947`); add the ownership gate + `withStaleness` (Class-C middleware, §11.4 — §6.2 needs these regardless); record a per-run JDWP port in `run.json` and have `HotSwapPush` verify the target VM's `DEV_HOTRELOAD_CLASSES_DIR` matches its own source tree before pushing; drop JBR/structural. Then default `hotReload` on, land the live regression test (§4.2 condition 1), and set the ~6-week falsifier. **If RETIRE:** remove in ONE change — tool registration `server.mjs:2549-2662`; `ReloadInputSchema` `schemas.mjs:734-744`; the `hotReload` start param + `--hot-reload` plumbing (`schemas.mjs:101-102`, `cli.mjs:352`, `dev-runner.cjs:154/213/1462-1465`); `scripts/dev/HotSwapPush.java`; `WorkerSpawner.addDevHotReloadFlags` + the `DEV_HOTRELOAD*` `EnvRegistry` rows; `DevReloadManager` + its `KnowledgeServer` gate and sentinel branch (`KnowledgeServer.java:698-702, 1625-1627`); the `worker-services/build.gradle.kts:100-109` doLast hook; the doc rows in `docs/reference/configuration/environment-variables.md` and `runtime-config-ownership-matrix.md`; and the false claim at `server.mjs:623` — then grep the retiree names to confirm zero residue. Either path: full `./gradlew.bat build -x test` + the dev-stack smoke. | D2 |

P6 is the one that must not be dropped: without it, P2-P5 re-drift and this audit gets rewritten
in six months, as it was rewritten from `254-mcp-dev-tools-issues` (2026-03-03, status done).

## 10. Reproducing §3

Parse `~/.claude/projects/F--justsearch-public*/**/*.jsonl`; per file, build a map from
`tool_use.id` to `tool_use.name` for names starting `mcp__`, then join `tool_result.tool_use_id`
back to it. Count calls from `tool_use` blocks only. Classify errors as `is_error === true`, or a
joined result body containing the ok-false or error-object markers, and pull the error code from
the `code` field. For §5.3, scan `Bash`/`PowerShell` `tool_use` inputs for a `127.0.0.1:<port>`
prefix followed by a path, and histogram the path. For §6.4, sum result-content lengths (text
blocks plus `source.data` for images) per tool name.

## 11. Theorization — what the endstate should be

> Not design. This section explores framings, tensions and alternative directions before
> anything is settled. Several ideas here are recorded because they may be useful later even
> if they are not the answer. Where a claim is speculative it is marked as such.

### 11.1 The reframe: three control planes over one machine, with no shared model of it

§2 lists the surfaces as if the problem were redundancy. It is not. `justsearch-dev`,
`jseval` and `dev-runner.cjs` do overlap, but the damage does not come from overlap — it comes
from the fact that **none of them can see what the others started**. MCP knows only runs it
spawned; `jseval` knows port 33221; a `runHeadlessEval` JVM is invisible to both.

Read that way, most of §5 and §6 are one defect wearing six costumes:

| Symptom | Underlying absence |
|---|---|
| §5.1 `start` fails on a dist it never checked | no authority says which tree a run was built from |
| §5.6 `reload` compiles in one tree, pushes into another | same |
| §6.1 `quick_health` reports "free" next to a busy GPU | no authority enumerates unregistered runs |
| §6.2 ownership gates only `start` | no authority to check at the other call sites |
| §5.3 agents reach for `curl` | no authority to point a tool at |

That suggests the endstate is not "fewer tools" or "better tools" but **one authority for what is
running, projected everywhere and re-derived nowhere.**

### 11.2 What is this surface actually *for*? — and a pattern that predicts bypass

Three candidate purposes, and the measured data discriminates between them:

- **Convenience** (saves typing `curl`) — *refuted*. 81% of local-API traffic bypasses it (§5.3).
- **Legibility** (structured, projected state instead of log-grepping) — *partly supported*.
  `quick_health` is the third most-used tool at 0% error.
- **Arbitration** (leases, ownership verdicts, admission control) — *strongly supported*, and it
  is the only purpose nothing else can serve.

Sorting the tools by altitude — in the sense of the `surface-altitude` axis this repo already
applies to product surfaces — the pattern is sharp:

| Altitude | Tools | Calls | Error | Bypassed? |
|---|---|---:|---:|---|
| Arbitration | `start`, `stop`, `quick_health`, `preflight`, `acquire_when_free` | 239 | 0-11% | no — no alternative exists |
| Transport | `fetch_api_json`, `api_call` | 62 | 18-45% | yes, ~4:1 by `curl` |
| Domain | `search_query`, `ingest` | 32 | 13% | yes, by `jseval` |

**Conjecture: tool altitude predicts bypass.** A transport-altitude tool competes directly with
`curl` and loses on composability — it cannot be piped, filtered, retried in a loop, or embedded
in a script. A domain-altitude tool competes with `jseval` and loses on depth. An
arbitration-altitude tool has no competitor, because arbitration requires shared state that only a
long-lived authority can hold.

If that conjecture survives scrutiny, the endstate is: **specialize hard into arbitration and
projection; stop competing on transport.** Note this argues *against* part of §5.3 — adding
allowlist entries may be treating the symptom. See §11.5.

### 11.3 The keystone: a run registry

The single structural idea this document points at. One authority answering, for every
JustSearch process on the machine:

*what is it, who owns it, which tree was it built from, which ports does it hold, what is its
health, when was it last touched, and is it GPU-bound?*

Written by whoever spawns a run (`dev-runner`, `jseval`, `runHeadlessEval`, a bare
`gradlew runHeadless`), read by everything else. Consequences, roughly in order of value:

- `quick_health` reports unregistered neighbours instead of a false "free" (§6.1 — this already
  cost one measurement round).
- `reload` and every other mutating operation resolve their inputs from the run record rather
  than from a cwd frozen at session-inject time (§5.6).
- `preflight` can check the target tree, not the invoking one (§5.1).
- Ownership becomes checkable at every call site, not just `start` (§6.2).
- `ui-shot`'s auto-serve and `serve-worktree-fe` can bind to a known backend rather than sniffing.

This is the same medicine as the `execution-surfaces` register and `553-representation-drift`,
applied to **runtime state** instead of to types: one canonical source, everything else a
projection. That parallel is worth taking seriously — it suggests the register pattern this repo
already trusts for representations generalizes to processes.

**Prior art — this extends an existing lineage rather than starting one.**
`271-backend-lifecycle-isolation` (done, 2026-03-10) established the multi-agent ownership and
interference model, and already named *"raw `:modules:ui:runHeadless` use without full isolation
overrides"* as a risk. `542-operation-scoped-lease-taxonomy` (done, 2026-05-21) — subtitled
"extending the 271 ownership model" — added a Layer-2 op-lease registry at
`tmp/dev-runner/op-leases.json`. Both work: lease adoption is 92/92.

What neither covers is the part this section is about. Both register what the dev-runner *started*;
neither enumerates what it did not — `jseval`'s 33221 backend, a bare `runHeadless`, a stale JVM
squatting a port. That is the gap the shard of 2026-08-14 recorded as a contaminated measurement,
and 271 flagged its seed five months before jseval's backend widened it. So the registry idea is
an extension in the same line 542 already walked, not a new invention — and the fact that 271's
foreign-process risk stayed open across two follow-on lanes is itself mild evidence it needs a lane
rather than another passing mention.

Open question: is registration *enforceable*? A register only covers what registers itself. A bare
`gradlew runHeadless` will always be able to skip it. So the registry probably needs a discovery
fallback (port scan, or process enumeration by command line) to stay honest — which is also how it
would surface the neighbours it was never told about. Speculative; unmeasured.

### 11.4 Three tool classes, with enforced middleware per class

If the registry exists, the tool surface falls out of it naturally:

- **Class A — arbitration.** Owns the registry. `start`, `stop`, `acquire_when_free`,
  `quick_health`, `preflight`. Already the healthiest part of the surface.
- **Class B — read projections.** Cheap, composable, never fail expensively. Must satisfy: a
  missing path returns *available keys*, not a raw dump (§5.4); size limits truncate with a notice
  rather than erroring; every projection carries staleness.
- **Class C — mutations against a run.** `ingest`, `reindex`, `gc`, `migration/*`,
  `worker/restart`, and `reload`. Every one of these should pass the same middleware:
  **run resolution -> ownership check -> staleness projection.** Today each does its own thing or
  nothing at all.

Class C is the interesting one, because it closes §6.2 and §5.6 with a single mechanism instead of
per-tool patches. It also gives a principled answer to a question the current design answers
arbitrarily: *which operations need a lease?* Answer: exactly the ones that mutate a run.

### 11.5 The allowlist may be a tax, not a control

Worth stating plainly because it cuts against §5.3's proposed fix. `api_call` rejects any path not
on an explicit allowlist. Five of its ten observed failures were rejections of **legitimate dev
endpoints**. Meanwhile any agent that wants a non-allowlisted endpoint simply uses `curl`, which
they did 268 times.

**A safety control with a trivial, sanctioned bypass is not a control — it is a tax on the
compliant path.** The allowlist cannot prevent an agent from calling an endpoint; it can only make
the good path worse than the bypass.

If the real fear is *accidental destructive calls against someone else's stack*, then the
mechanism is wrong twice over: the risk is not "which path" but "which run, and whose". That is
Class C's ownership middleware, not a path list. A plausible endstate: **drop path allowlisting;
keep the loopback guard; classify operations as read vs mutating; gate mutations on ownership.**
Fewer moving parts, no maintenance list to drift, and it defends against the thing that can
actually happen.

Counter-argument worth preserving: an allowlist is also documentation — it tells an agent what is
*intended* for dev use. That value is real but could be served by a projection ("here are the
endpoints this stack exposes") rather than by a gate that refuses.

### 11.6 Hot reload is a symptom, not an independent defect

Under §11.3/§11.4, hot reload stops being a special case. Every one of its confirmed failures is
an instance of a class problem: it resolves its root from the wrong place (no registry), it does
not check ownership (no Class C middleware), it falsifies a staleness stamp (no staleness
projection), and it attaches to a port without identity (no run record to identify against).

That reframes the repair. Rather than patching `reload`'s root resolution specifically, give it
the resolution and middleware that every Class C tool should have — and hot reload gets fixed as a
side effect of fixing the class.

It also clarifies what is genuinely *good* about it and worth preserving: `DevReloadManager`
carries `ModelContext` across a service reconstruction, so encoders stay loaded
(`DevReloadManager.java:130-150`). In a repo whose warm restart is ~40s largely because of ONNX
model loading, **preserving loaded models across a code change is the actual value proposition** —
not "avoiding a restart" in the abstract. Any endstate that keeps hot reload should keep it for
that reason; any endstate that drops it should acknowledge that is the capability being dropped.

Unresolved tension worth flagging honestly: CLAUDE.md routes implementation to delegated workers
who mostly verify with unit tests plus a build. If very few agents iterate on Worker Java against
a live stack, hot reload is excellent and almost unused regardless of quality. That is an
empirical question, not an architectural one, and it should be settled by a falsifier rather than
by argument.

### 11.7 Alternative direction: allocate instead of arbitrate

A hidden assumption runs through the whole surface: **one dev stack at a time.** The entire
ownership apparatus — leases, verdicts, takeover policies, `acquire_when_free`, contention
handling, displaced notices — exists to arbitrate access to a singleton.

But *why* is it a singleton? Memory and ports are the stated reasons; GPU is the real one. A
no-AI backend on an allocated port may not need to be exclusive at all. If so, a large part of the
arbitration machinery is solving a self-imposed constraint, and the endstate could be:

- **GPU-bound runs**: arbitrate (the lease model, unchanged — it works, 92/92 adoption).
- **Everything else**: allocate a port, register it, run as many as fit.

This would dissolve rather than solve most contention friction, and it fits the parallel-agent
reality (3-4 sessions) better than a queue does. Speculative and unmeasured — the memory ceiling
on this machine is the obvious thing that could kill it, and nobody has measured how many non-AI
backends fit. Recorded because it is the kind of assumption that goes unexamined precisely because
the machinery around it works well.

### 11.8 A better north star than tool count

Tool deferral changes the economics: names are cheap in the prompt, schemas load on demand. So
"prune to save tokens" is a weak argument — §3's 31%-of-schema-payload figure is real but small.

The dominant cost of a bad tool is not its schema, it is **failure**: a 45%-error tool costs a
round trip, a recovery decision, and often a fallback to `curl` anyway. So the metric the endstate
should optimize is **first-call success rate**, not tool count. That reframes several items:
`api_call` at 45% is a worse problem than five unused tools; `ai_activate` at 43% is worse than
both. It also gives P7's reader a purpose beyond reproducing this audit — it becomes the standing
instrument for the metric.

### 11.9 The browser/harness division

`claude-in-chrome` is 68% of tool-result bytes and is mentioned in no repo rule. The endstate
principle that seems right: **use the instrumented harness for anything you will assert on; use
the browser for things you are only looking at.** An assertion taken from a screenshot is not
reproducible and cannot be gated; `jseval ui-shot`'s `.measure.json` is both. That leaves the
browser owning genuine exploration — external design research, unfamiliar flows, one-off
debugging — which is what a meaningful share of its use already is.

This is a rule about *evidence*, not about cost, which is why it belongs in the always-loaded layer
despite the 620 byte ratchet: it changes what counts as verification.

### 11.10 The dev surface should consume the product surface's own research

The most under-exploited asset here. This project has spent several lanes (725, 732, 735, 770)
measuring what makes an MCP surface legible and cheap for an agent consumer — response shape,
delivery tiers, truncation cliffs, payload bytes that carry no content, actionable errors. **None
of it has ever been applied to the dev surface**, which is an MCP surface with agent consumers.

`fetch_api_json` returning a raw text tail on a `jsonPath` miss (§5.4) is the same defect class
770 measured on the product side. The dev tools' error messages are mostly not actionable, which
is exactly what 725 fixed for product responses. The endstate should treat the dev surface as a
*consumer* of those findings — and, more interestingly, as their **testbed**: it is the one MCP
surface whose agent population is fully observable in transcripts, which the product surface's is
not. That is a genuinely useful asymmetry and it has never been used.

### 11.11 Hidden assumptions, and what would falsify this

- **"Agents should use MCP tools."** Maybe not. For a coding agent with a shell, a CLI may simply
  be the better substrate, and the honest endstate might be a *minimal* MCP surface (arbitration
  only) plus a good CLI. §11.2's altitude conjecture points this way. Falsifier: if transport-tool
  usage does not recover after §5.4's projection fixes, the tools are not the problem — the
  modality is.
- **"Usage reflects value."** Contaminated for anything off-by-default (`hotReload`, 1/92) or
  undocumented (`apiPort`, 8 uses). Zero usage of an invisible feature measures visibility.
- **"The singleton is necessary."** §11.7.
- **"The allowlist protects something."** §11.5 — no evidence of prevented harm; measured evidence
  of friction.
- **"This audit is representative."** One machine, one owner, ~6 weeks, a corpus that rolls.
  Everything here is about *this* development environment.

### 11.12 Candidate follow-up

The run-registry idea (§11.3) is larger than this lane and outlives it — it touches `dev-runner`,
`jseval`, the MCP surface, `ui-shot`'s auto-serve and the ownership model. It would be the third
step in the 271 -> 542 line (see §11.3 prior art), extending ownership from *runs we started* to
*every run on the machine* — not a new concept, and it should be framed that way to avoid forking
authority away from 542's op-lease registry. If it is worth its own lane, **#845 is the next free
number** (verified against `world-state.mjs`; note #843 is already
double-claimed across worktrees, and #840/#842 have live merge-gate collisions). Not created here
— whether the registry is a lane or just this lane's P5 is an owner call, and creating a second
tempdoc speculatively is exactly the kind of residue `retire-with-a-sweep` warns about.

## 12. Direction — the finding under the findings

### 12.1 One missing property, not seven bugs

Every significant defect in this document is the same defect: **the tooling asserts things it has
not verified.**

| Assertion | Reality |
|---|---|
| `preflight`: "worker dist built" | it checked the invoking tree; `start` uses `distFrom`'s |
| `quick_health`: "free" | it can only see runs it started (§6.1) |
| `reload`: `hotSwapOk: true` | true when zero classes were pushed, and when the JVM is not the one it compiled for |
| `reload`: build stamp written | makes a stale JVM report as current (§5.6) |
| tool description: "still pushes bytecode without hotReload" | no JDWP listener exists at all (§4.2) |
| reference doc + skill: "exactly these 15 tools", `effective_config` -> `/api/config/effective` | 16 tools; the endpoint is `/api/debug/effective-config` (§6.3) |
| `start`: `UNHANDLED` | a condition it fully understands, with a remedy in the same message (§5.1) |

Seven items, one property. And the property has a name in this repo already: the product surface
has extensive machinery for never claiming more than it knows — citation-coverage honesty,
degradation reason codes, `searchTraceExplain`, the honest-baseline work, and `verify-don't-guess`
as a Hard Invariant. **The dev surface has none of it.** The tools agents use to verify the
product are held to a lower evidentiary standard than the product.

### 12.2 The criterion

> **A dev tool must not report state it did not verify, and must not report success it did not
> confirm.** Where it cannot verify something, it says so — an explicit unknown beats a confident
> default.

This is a filter, not a project. Its value is as much in what it excludes as in what it selects:
it says *stop*, which a sorted error column never does.

### 12.3 Re-sequencing by the criterion

Applied to §9, the order changes and two items drop out:

| Order | Item | Kills which false claim | Verdict |
|---|---|---|---|
| 1 | **P6** doc/skill de-fork + `check-dev-mcp-doc-sync` gate + consult-register entry | false inventory — and it is the only item that stops every other fix re-drifting | promoted from last to first |
| 2 | **P5** `quick_health` reports backends it did not start | false "free" — the one that already cost a measurement round | promoted |
| 3 | **P2** `start` error code + `preflight { distFrom }` | false green, false severity | unchanged |
| 4 | **P4** `ai_activate` preconditions in `preflight`/`quick_health`; eaten path separators | a mandated tool that surprises 43% of the time | unchanged |
| 5 | **P3a** projection honesty only: `jsonPath` miss returns available keys; `maxBytes` truncates with a notice; `api_call` gains `jsonPath` | dishonesty by omission — a miss currently returns the most expensive possible payload | narrowed |
| 6 | **P7** repeatable reader in `scripts/agent-analytics/` | nothing — but it is the instrument the falsifier needs | kept, and now load-bearing |
| 7 | **P1** prunes (`github` server; `agent_chat`, `capture_evidence`, `validate_evidence` wrappers; fold `status`) | nothing — hygiene, but cheap and it shrinks what P6 must keep true | demoted |
| — | **P3b** allowlist additions | **dropped** — §11.5: a control with a sanctioned bypass is a tax, not a control |
| — | **P8** hot reload | **still D2** — not an honesty fix; blocked on an owner decision |

Two drops matter more than the promotions. Adding allowlist entries would have treated the
symptom of a mechanism §11.5 argues should not exist. And hot reload, despite being the most
interesting thing in this document, is not on the critical path of anything.

### 12.4 What not to build

**Not the registry as a project.** §11.3 is the right *shape*, but the honest version is a
fraction of the work: `quick_health` saying *"one run I own; plus an unidentified JVM holding
33221 and the GPU, which I did not start"* is honest, cheap, and captures nearly all the value. A
cross-surface registry with enforced registration is a cathedral for a surface taking ~6 calls per
session.

Keep the proportion in view: `justsearch-dev` is **0.4%** of tool-result bytes (§6.4). The work
actually happens in `bash` and `jseval`. Harm here is not proportional to volume — one contaminated
measurement is expensive — but that argues for cheap honesty, not for infrastructure.

### 12.5 Where the actual upside is

The strongest idea in §11 is §11.10, and it is worth more than the whole fix list: **this is the
only MCP surface whose agent population is fully observable.**

725, 624 and 770 spent real campaign money trying to answer why agents do or do not invoke an MCP
surface, what response shape they tolerate, and where the truncation cliff bites. Those questions
are structurally hard on the product side because external agents' transcripts are not visible.
Here they are — ~66 sessions of ground truth, continuously, for free, over the same protocol with
the same kind of consumer.

That asymmetry has never been used. The direction worth taking after the honesty work is to stop
treating the dev surface as a maintenance cost and start treating it as the instrument that
de-risks product-surface questions *before* they are worth a campaign. P7's reader is the first
step of that, which is why it survives the re-sequencing despite fixing no bug.

### 12.6 Falsifier

**First-call success rate** (P7's reader is the instrument). If it does not move after items 1-5
land, then the tools were never the problem — the modality is, and the right answer is to shrink
the MCP surface to arbitration only and let agents use the shell for everything else. §11.2's
altitude conjecture already predicts that outcome; this would confirm it, and the response is
written down here in advance so the result cannot be re-interpreted after the fact.
