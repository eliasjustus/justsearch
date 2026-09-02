# `scripts/agent-analytics/` — agent hooks + maintainer analytics

Two kinds of thing live here:

- **`hooks/` + `lib/`** — the Claude Code discipline hooks: blocking *guards* (e.g. preventing
  destructive git in the main checkout) and just-in-time *hints*. The hook **wiring** lives in
  `.claude/settings.json`; the shared helpers are in `lib/`.
- **Everything else** (`otlp-sink.py`, `*-session.mjs`, `generate-index.mjs`, `otlp-viewer/`, …)
  — **maintainer** telemetry/analytics tooling for measuring agent-assisted development.

**Contributors don't need any of this** — it is published for transparency (see
[`/MAINTAINING.md`](../../MAINTAINING.md)). The analytics tooling is maintainer-only and is not
wired to run on a fresh clone; telemetry capture is local-only and never leaves the machine.

## Retired: process-hygiene scoring (PHI)

`score-session.mjs`, `correlate-signals.mjs`, `scores.ndjson` and
`scripts/ci/check-agent-quality-trend.mjs` are **gone** (tempdoc 858 §7). Session *reports* stay —
only the composite score on top of them retired. **Read
[`docs/explanation/21-agent-analytics-pipeline.md` § Retired: process-hygiene
scoring](../../docs/explanation/21-agent-analytics-pipeline.md#retired-process-hygiene-scoring)
before proposing a new per-session quality score**: it carries what the old one measured
(composite r = 0.064 at N = 116, tempdoc 277 §C4, against real per-type signal-level effects),
why the composite is the part that failed, and the caveat that the signal definitions drifted
afterwards so those numbers no longer describe a runnable metric.

## Signature census (743 P-L)

`mine-friction.mjs`/`analyze-session.mjs` (the alive 727 friction-mining pass) judge whole
sessions via an LLM. `signature-census.mjs` is the cheap, mechanical complement: it scans every
session in a window for a small seeded table of known recurring error signatures (the `& "gh.exe"`
PowerShell-call-operator class, cp1252/`UnicodeEncodeError`, quoting-EOF, `gh` exit-code
misreads, deferred-tool schema-not-loaded, `/tmp`-vs-scratchpad path misses, edit-not-read) and
counts them — no LLM call, no judgment. Run it as part of the periodic mining pass:

```
node scripts/agent-analytics/signature-census.mjs --since <window-start>
```

It is semi-automatic by design (tempdoc 743 P-L): the census only **proposes** counts. Each
signature whose count clears the ratchet threshold (≥5 in the window) gets a **disposition** at
the next mining-pass review session — exactly one of **root-fix** (a P-K-class exec-substrate
fix), **fire-time hint** (a new redirect hook, registered the normal way — `agent-hooks.v1.json`
+ tier-register + `hook-integrity` gate), or explicit **wontfix**. Census output must **never**
land in always-loaded prose (`CLAUDE.md`/`.claude/rules/`) — the always-loaded-budget ratchet is
the guard against that self-poisoning failure mode. Falsifier: two consecutive mining passes
whose dispositions nobody implements means the loop is dead weight — stop running it.

Shares its transcript discovery/parse substrate (`lib/transcript-store.mjs`) with the rescued
`overhead-taxonomy.mjs` (T1 overhead measurement) and `transcript-spine.mjs` (evidence-lane
per-turn condenser) — see that module's header for the discovery-layout caveat.

## Cache efficiency (841)

`cost-session.mjs` and `baseline-economics.mjs` answer *how much* was spent.
`cache-efficiency.mjs` answers *why a cache write was paid for*, which a single
`cache_write_tokens` total cannot show:

```
node scripts/agent-analytics/cache-efficiency.mjs           # human-readable
node scripts/agent-analytics/cache-efficiency.mjs --json    # machine-readable
node scripts/agent-analytics/cache-efficiency.mjs --since 2026-08-01
```

It splits cache-write into **extension** (the normal per-turn delta), **invalidation**
(the readable prefix shrank — the cached body was lost) and **cold start**, then attributes
each invalidation to compaction, a model switch, TTL expiry, or an honest
`in-ttl-undetermined` residual. That split is the point: on the corpus it was built against,
~557 invalidation events cost more cache-write than ~62,000 turns of ordinary extension, and
no existing report could see it.

**Known limit, stated because it is load-bearing:** `in-ttl-undetermined` is not a
placeholder for a cause someone forgot to fill in. Transcripts record token *usage*, not the
prompt prefix, so client-side breakpoint re-anchoring and server-side eviction are
indistinguishable from this data. Do not "improve" the classifier by guessing a cause for
that bucket.

### Pricing coverage — what this catches and what it cannot

Every run ends with a pricing-coverage block, and a model with no `PRICING` row is printed as
a loud `!!` warning. That exists because `findPricing` **fails closed**: a missing model is
not mis-priced, it is priced at `$0` and silently vanishes from every total. `claude-opus-5`
went missing exactly this way and hid a third of all spend.

**The asymmetry matters:** this catches a *missing* model automatically. It cannot catch a
*wrong price*. A stale rate produces a plausible number nobody questions — which is how a
cancelled Sonnet-5 price cliff sat in the table two weeks from silently overpricing every
Sonnet-5 turn by 50%. The rates in `lib/transcript-cost.mjs` carry the date they were last
verified against `platform.claude.com/docs/en/about-claude/pricing`; re-check them when a
model ships or a promotional rate is announced, because nothing here will tell you.

## Session ledger (886)

Every reader above speaks Claude Code's own transcript shape. Tempdoc 886 found that the
thing that actually sets the bill — context tokens re-presented per API call — is the same
idea whether the harness is Claude Code or OpenAI Codex CLI; only the field names differ.
`lib/ledger/` is the harness-neutral projection of that idea:

```
import { listCalls } from './lib/ledger/index.mjs';
const { calls, toolEvents, sessions } = listCalls({ harnesses: ['claude-code', 'codex-cli'], sinceMs: Date.parse('2026-08-01') });
```

**Window semantics, `sinceMs`/`untilMs` (886 §12 PR 2 fix, independent review):** these bound
TWO different things. Each adapter uses them as a cheap file-mtime prefilter before parsing
(unchanged). `listCalls` then ALSO applies them as a per-CALL filter on `windowBy: 'ts'` (the
default) — a `Call`/`ToolEvent` survives only if its OWN `ts` falls inside the window. This
matters because a file's mtime is its LAST write: a `--since 2026-08-01` request previously kept
every call in any file touched on-or-after that date, including calls from weeks earlier in a
long-lived session (measured: 5,541 calls dated before `--since` leaked into a 2026-08-01 query
before this fix). A call with a null/unparsable `ts` cannot be judged, so it is KEPT — never
silently dropped — and counted in the result's `unfilterableTs`. Pass `windowBy: 'mtime'` to opt
back into the old file-level-only semantics. When neither `sinceMs` nor `untilMs` is given, no
per-call filtering runs and the result shape is unchanged (no `unfilterableTs` key).

A `Call` carries `{harness, provider, project, sessionId, callId, lineage, ts, model, tokens,
contextTokens, compactionBoundary}`; a `ToolEvent` carries `{harness, sessionId, callRef, role,
name, inputChars, outputChars, isError, ts}`. **Absent token axes are `null`, never `0`** —
Codex has no billable cache write, Claude has no reasoning-token axis, and a reader summing a
`null` as zero spend would be quietly wrong in the direction that hides cost, not inflates it.

Per-harness adapters:

- `lib/ledger/claude-adapter.mjs` wraps `lib/transcript-store.mjs` discovery. Dedups by
  `message.id` (Claude writes one JSONL line per content block, all sharing one id, with
  identical `usage` repeated on each) while still registering every line's `tool_use` blocks —
  a block can land on a line AFTER the one that carried the usage snapshot, so registering
  before the dedup skip is what keeps the tool_result→tool-name join intact. Subagent lineage
  (`spawn` vs `fork`) comes from the sibling `subagents/*.meta.json`.
- `lib/ledger/codex-adapter.mjs` reads `~/.codex/sessions/**/rollout-*.jsonl` directly (no
  shared discovery module exists for Codex yet). Every parsing rule is corpus-verified (886
  §11's derisk pass, 51,740 `token_count` events / 289 sessions): `input_tokens` already
  *includes* `cached_input_tokens` (the OpenAI convention); `last_token_usage` is a per-call
  delta, and a `token_count` event whose cumulative total exactly repeats the previous one is
  dropped as a duplicate, not counted as a new call (1,482 such repeats in that corpus); tool
  outputs are capped at 64k chars with a `truncated` flag (some run past 750k uncapped). Every
  Codex `Call` has `lineage.kind = 'main'` — `inter_agent_communication_metadata` is a real
  signal, but on real payloads (`{trigger_turn: false}`) it names no PARENT, so no per-call
  lineage edge is derivable from it; that fact surfaces instead as the session-level
  `session.multiAgent` boolean. A `compacted` line with no following `token_count` event still
  gets a synthetic zero-token boundary `Call` (`Call.synthetic = true` — every other `Call` is
  `synthetic: false`). A file with no usable `sessionId` (missing/empty `session_meta.payload.id`)
  is the ONE documented skip condition — collected into the returned `skipped: [{file, reason}]`
  array, not silently dropped; any OTHER parsing exception propagates rather than being caught.
  `session.selfCheck` reports `{deltaInputSum, maxCumulativeInput, resets, repeatsDropped}` —
  `maxCumulativeInput` (renamed from an earlier `finalCumulativeInput`) is the largest cumulative
  input-token counter observed, not merely the last one, and `resets` counts how many times that
  cumulative counter DECREASED (a resumed thread restarting its counter).

**`resume` and `thread` are RESERVED lineage vocabulary — no adapter in this PR produces
either.** `record.mjs`'s `VALID_LINEAGE_KINDS` documents the evidence each would need: `thread`
needs a real PARENT id in the payload (not just a boolean flag asserting multi-agent
communication happened); `resume` needs an explicit resumed-FROM linkage (a Codex rollout
naming the prior rollout it continues, or a Claude Code transcript carrying `--resume`'s source
sessionId). Neither harness's log carries that evidence today.

`lib/ledger/tool-roles.mjs` maps each harness's own tool names onto one shared role vocabulary
(`read`/`edit`/`shell`/`search`/`spawn`/`wait`/`web`/`other`) so a cross-harness reader never
needs a tool-name switch statement per harness. The Codex table is a corpus vocabulary snapshot
(2026-09-02, 50,259 real calls/tool events) — `agent_message` maps to `spawn` for table
completeness even though the adapter no longer emits a `ToolEvent` for it (its payloads are
plain assistant reply text, not tool activity).

**Boundary rule, enforced not just documented (886 §10.4):** this library is machine-level —
every project on the machine could use it — while the hooks/gates elsewhere in this directory
are this repo's own policy. Nothing under `lib/ledger/` may read `governance/`, `CLAUDE.md`, or
`tmp/agent-telemetry` paths, or resolve a repo root via a relative `'..','..','..'` climb.
`lib/ledger/boundary-check.mjs` exports the pure checker (`findBoundaryViolations`);
`lib/ledger/boundary.test.mjs` runs it over every real file in the directory AND over crafted
violation shapes (a side-effect import, a multi-line `import {...} from`) to prove the checker
itself catches them, not just that today's files happen to pass.

`cache-efficiency.mjs` is the first migrated consumer — its `--harness` flag (default
`claude-code`) selects the provider, and file discovery now comes from the ledger's
`listClaudeTranscriptFiles` instead of a second hand-rolled directory walk. Fixtures for both
adapters live under `fixtures/claude/` and `fixtures/codex/` — synthetic content only, no real
prompts or paths.

## Context residency and spawn economics (886)

`context-residency.mjs` and `spawn-economics.mjs` are the second wave of ledger consumers
(tempdoc 886 §12 PR 2) — they productionise three throwaway scripts (`tmp/tokeff/{deep,deep3,
deep4}.mjs`) that first measured the variable §2 of that tempdoc found no existing reader
tracked: context tokens re-presented **per API call**, not cache-hit rate.

```
node scripts/agent-analytics/context-residency.mjs --since 2026-08-01 --harness claude-code
node scripts/agent-analytics/spawn-economics.mjs --since 2026-08-01 --top 20
node scripts/agent-analytics/cost-session.mjs --reconcile
```

`context-residency.mjs` reads the neutral ledger for three harness-neutral sections: per-call
context distribution by harness × lineage-kind (main vs spawn/fork) × model (p50/p75/p90/p99/
max, ctx/out ratio); share of context tokens and cache-read-priced cost above `--cap` (default
200000 — fails closed on an unpriced model, never a silent `$0`); the compaction ledger
(trigger, pre/post tokens, durationMs). A fourth section — compounded residency, where every
context piece (prefix, a tool result, a tool_use input, user/assistant text, thinking) is
charged again on EVERY call it stays resident for, reset at a compaction boundary — is
`claude-code`-only: it reads raw transcripts directly, the same precedent `cache-efficiency.mjs`
set for content the neutral `Call`/`ToolEvent` record has no axis for (a plain text block's
size). Every section excludes `synthetic` calls.

**A real bug this surfaced (fixed in `lib/ledger/claude-adapter.mjs`):** a genuine Claude Code
compaction is TWO consecutive boundary-flagged lines — a `system`/`compact_boundary` line
carrying `compactMetadata`, immediately followed by a `user`/`isCompactSummary:true` line that
carries none. The adapter unconditionally overwrote its captured metadata on every
boundary-flagged line, so the SECOND (metadata-less) line silently erased the first's real
trigger/preTokens/postTokens/durationMs before any `Call` ever saw them — 0 of 11 real
compaction events in the local corpus carried `compactMetadata` before the fix. The original
PR 1 fixture only exercised a single-line boundary, so its test never caught this.

`spawn-economics.mjs` joins the lineage every Claude `spawn`/`fork` Call already carries
(`agentType`, requested vs actual model, parent session — sourced from `subagents/*.meta.json`
by the claude-adapter) to per-call COST (priced per token axis, not from `contextTokens` alone)
and `firstUserMessageChars` (the opening turn's character count, read off the raw spawn
transcript — same no-neutral-axis rationale as `context-residency.mjs`'s compounded-residency
section). **Not a clean "brief length"** (independent review, 886 §12 PR 2): a skill-invoked
subagent's opening turn is the skill body ("Base directory for this skill: …"), not an
Agent-tool brief, so this axis is a mixed proxy across both call shapes, named accordingly.
Tables: requested→actual model, by `agentType`, run-length buckets (`[0-10,10-30,30-60,60-120,
120-250,250-500,500+]`, so the `120-250` bucket is `calls >= 120`) with cost share, top-N by
cost. Codex has no per-spawn lineage yet (every Call is `lineage.kind:'main'`), so a Codex
session with `session.multiAgent` is reported as one row in a separate "multi-agent sessions"
table rather than a fabricated per-spawn split.

`cost-session.mjs --reconcile` compares the OTLP-costed set (`--source otlp`'s harness-computed
dollars) against the transcript-priced set, per session shared by both — `otlp$`,
`transcript$`, `delta%`, and named residue causes (`otlp:unknown-model` /
`transcript:unknown-model` — including Claude's own literal `<synthetic>` model-name turns).
Sessions present on only one side are listed separately, not folded into a misleading delta.
The comparison logic (`reconcileSessions`) is pure and injectable — its test never touches
`tmp/agent-telemetry/`.
