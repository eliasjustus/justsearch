---
title: "Dev agent-tool surface audit: what agents actually invoke on the justsearch-dev MCP server, what fails, what nobody has ever called, and the two structural reasons 81% of local-API traffic bypasses the surface entirely"
type: tempdocs
status: "OPEN — analysis complete and reproducible (2026-08-18); nothing implemented. Owner decisions pending on D1-D4 (§8). Hot-reload coherence RESOLVED 2026-08-18: verdict INCOHERENT, recommendation RETIRE (§5.6), independently re-verified against source by the orchestrator — D2 is now a go/no-go on the sweep, not an investigation. Adopts 6 inbox conditions (§7) that should be closed by this lane rather than re-logged."
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

### 4.2 Hot reload — RETIRE (P8; verdict landed 2026-08-18, awaiting D2)

`hotReload: true` on 1 of 92 starts; `reload` invoked 0 times. The §5.6 investigation returned
**INCOHERENT** and recommends **RETIRE**. Its load-bearing claims were re-verified independently
against source by the orchestrator (not taken on the auditor's word); all five held.

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

**Predictable evasion to reject:** "disable it now, sweep the code later." That is exactly the
follow-up-that-never-comes pattern `retire-with-a-sweep` names, and tempdoc 742's ~350-file corpus
is what it produces. Sweep in one change or decide to keep it.

**What retiring does NOT fix:** §6.2. Ownership still gates only `start`, so `ingest`,
`reindex`, `gc` and the migration endpoints remain callable against a peer's stack after the
sweep. Removing `reload` removes that hole's sharpest edge, not the hole.

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
- **D2 — hot reload: sweep it?** No longer blocked. §5.6 returned INCOHERENT; recommend **RETIRE**.
  Making it coherent needs six changes (compile root from the run record; per-run JDWP port plus an
  identity handshake; an ownership gate; `withStaleness`; jar/classes-dir reconciliation; JBR
  staging) — a multi-day rebuild of a capability with 0 invocations in 878 transcripts, competing
  against `installDist` + restart, which is correct by construction. Keeping it costs four silent
  corruption paths and a tool description that contradicts the code.
- **D3 — unify backend discovery?** Minimum (`quick_health` probes unowned backends) vs full
  (`jseval` registers in the run registry). Recommend the minimum first; it is the half that already
  cost a measurement.
- **D4 — govern the browser surface?** A short rule on when `claude-in-chrome` beats
  `jseval ui-shot`. Costs always-loaded bytes, which the 620 ratchet caps — so this is a genuine
  trade, not a free add.

## 9. Proposed sequencing

| # | Item | Depends on |
|---|---|---|
| P1 | Prune: `github` server; `agent_chat`, `capture_evidence`, `validate_evidence` wrappers; fold `status` into `quick_health`. Sweep the count in all four inventories (§6.3) in the same change. | D1 |
| P2 | Fix `start`: real error code for missing dist, `preflight { distFrom }`, bare-worktree-name resolution. | — |
| P3 | Allowlist gap: `/infra/capabilities`, `/infra/health`, `/mcp`, `/api/mcp/token`, `/api/chat/ask`, `/api/knowledge/retrieve-context`, `/api/memory`. Add `jsonPath` to `api_call`; `jsonPath` misses return available keys; `maxBytes` truncates instead of erroring; `ingest` accepts the session scratchpad. | — |
| P4 | `ai_activate` preconditions surfaced in `preflight`/`quick_health`; fix the eaten path separators. | — |
| P5 | `quick_health` probes for unowned backends (§6.1). | D3 |
| P6 | De-fork the doc/skill; add a `check-dev-mcp-doc-sync` gate asserting tool list + endpoint keys + allowlist against `server.mjs`; register `scripts/dev/` in the consult register. | P1-P5 |
| P7 | Repeatable reader in `scripts/agent-analytics/` so §3 is re-derivable after the corpus rolls. | — |
| P8 | Hot reload sweep (§4.2, §5.6). Inventory to remove in ONE change: tool registration `server.mjs:2549-2662`; `ReloadInputSchema` `schemas.mjs:734-744`; the `hotReload` start param + `--hot-reload` plumbing (`schemas.mjs:101-102`, `cli.mjs:352`, `dev-runner.cjs:154/213/1462-1465`); `scripts/dev/HotSwapPush.java`; `WorkerSpawner.addDevHotReloadFlags` + the `DEV_HOTRELOAD*` `EnvRegistry` rows; `DevReloadManager` + its `KnowledgeServer` gate and sentinel branch (`KnowledgeServer.java:698-702, 1625-1627`); the `worker-services/build.gradle.kts:100-109` doLast hook; the doc rows in `docs/reference/configuration/environment-variables.md` and `runtime-config-ownership-matrix.md`; and the false claim at `server.mjs:623`. Verify with a full `./gradlew.bat build -x test` + the dev-stack smoke, and grep the retiree names to confirm zero residue. | D2 |

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
