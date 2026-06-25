---
title: "Automated Claude CLI Integration"
type: tempdoc
status: done
created: 2026-02-20
---

> NOTE: Noncanonical doc (investigation + plan). May drift. Verify against code before acting.

# 224: Automated Claude CLI Integration

## Purpose

This tempdoc explores using `claude --print` (Claude Code CLI non-interactive mode) as
a general-purpose automation building block across the repository. Any script — CI, eval,
dev tooling, operational — can invoke a frontier LLM for single-shot analysis tasks without
an API key (covered by the Claude Code subscription).

The core question: **where in this repo's workflows would automated LLM calls provide
value that mechanical/deterministic approaches cannot?**

## Origin

Extracted from tempdoc 216 Phase 4b (LLM-as-judge for agent battery). The investigation
proved the invocation pattern works and established cost/latency baselines. This tempdoc
generalizes that capability beyond eval.

**Predecessor:** tempdoc 216 §IV.12 (Claude Code CLI as LLM-as-Judge investigation).
All empirical testing, invocation pattern validation, and cost analysis was done there.

## Core Capability

Claude Code CLI can be invoked non-interactively from Node.js/PowerShell scripts via:

```bash
type <prompt-file> | claude --print - --output-format json --max-turns 1 --no-session-persistence
```

This gives any eval script access to a frontier model (Sonnet 4.6) for single-shot
analysis tasks. No API key needed — the Claude Code subscription covers it.

**Validated constraints (from tempdoc 216 §IV.12 empirical tests):**
- Prompt must be piped from a file (inline `-p "prompt"` fails on Windows due to cmd.exe quoting)
- Do NOT use `--json-schema` (triggers multi-turn agentic behavior, 2.5× cost)
- Ask for JSON in the prompt text, parse `result` field, strip markdown code fences
- ~12 seconds per call, ~$0.07 per call (Sonnet 4.6)
- `CLAUDECODE` env var must be cleared when testing from within an active Claude Code session
  (not an issue for standalone overnight/CI runners)

---

## Area 1: Eval & Benchmark Pipeline

### 1.1 Agent Battery Judge (original Phase 4b scope)

**What:** Score each of the 16 agent battery scenarios on coherence, faithfulness, and
hallucination detection.

**Why:** The current `required_facts` / `expectedKeywords` checks are binary (present or
absent). The judge adds continuous quality scoring and catches failure modes that substring
matching cannot: incoherent responses that contain all required facts, novel hallucinations
not in the `mustNotContain` list, and quality gradients (partial credit).

**Input per scenario:** ~400-700 tokens (prompt + finalResponse + requiredFacts + toolSequence)
**Cost:** 16 scenarios × ~$0.07 = ~$1.12 per run
**Latency:** ~3 minutes sequential

**Output:** `agent-battery-judge-manifest.v1` with per-scenario scores and aggregate means.

**Implementation:** New script `scripts/ci/run-agent-battery-judge.mjs` (~200-300 lines).
Reads an agent-battery manifest, invokes claude per scenario, writes judge manifest to
`tmp/agent-evidence/_summaries/`.

### 1.2 Regression Explainer

**What:** When Track G or Claim A detects a regression (score dropped between runs), invoke
the LLM with the actual query/document pairs that regressed and produce a plain-language
explanation of *why* the regression happened.

**Why:** Current regression detection says "query X: nDCG dropped from 0.85 to 0.72" — a
number. The explainer could say "query 'machine learning algorithms': document Y dropped
from rank 2 to rank 7, likely because the updated tokenizer splits hyphenated terms
differently." This turns a "something broke" signal into an actionable diagnosis.

**Data availability (from research):** The manifests themselves only contain aggregate
regression counts (`decision_regression_count`). Per-query granular detail lives in the
`diff_decision_json` file referenced by the manifest's `diff_decision_json` path field.
The regression explainer must:
1. Read the manifest to find `diff_decision_json` path
2. Load that decision JSON to get per-query regression details
3. Optionally load the `baseline_selected_path` and `candidate_selected_path` suite files
   for the actual query/document data

**Input:** The regressed query, its top-K results from both the baseline and current run,
the relevance labels. ~1000-2000 tokens per regressed query.

**Cost:** Typically 1-5 regressions per run × ~$0.07 = ~$0.35 per run.

**Output:** Per-query explanation written as a companion artifact alongside the manifest.

### 1.3 Overnight Run Narrative

**What:** After the scorecard is built, invoke the LLM with the full scorecard JSON and
produce a 10-15 line human-readable summary of the overnight run results.

**Why:** Reading raw scorecard JSON every morning is tedious. A narrative like:
"3/3 families ran. Track G regressed on 2 queries (both multi-word technical terms).
Agent battery stable at 43.8%, same 9 failures as last run. RAG eval passed, nDCG
steady at 0.71." — is immediately useful and takes 5 seconds to read.

**Input:** The full scorecard JSON (~2-5K tokens depending on lane count).
**Cost:** Single call, ~$0.07.
**Latency:** ~12 seconds.

**Output:** Markdown summary written alongside the scorecard, or printed to overnight
runner stdout.

### 1.4 Search Result Quality Evaluation (future)

**What:** Beyond nDCG (which measures ranking against predetermined relevance labels),
evaluate whether the top-K results for a query are actually *useful* for answering it.

**Why:** nDCG can show a high score for results that are technically relevant but
unhelpful in practice (e.g., tangentially related documents ranked highly). An LLM can
assess "would a user searching for X find these results useful?" — a qualitative dimension
that automated metrics miss.

**Scope:** This is a larger effort — needs careful prompt design and integration with
the search eval pipeline. Lower priority than use cases 1-3.

### 1.5 Agent Scenario Generation (future)

**What:** Use the LLM to read the codebase documentation and corpus, then generate new
agent battery scenarios with prompts, required_facts, and expectedKeywords.

**Why:** The current 16 scenarios are manually written. Automated generation could expand
coverage to edge cases and query types that a human didn't think to test.

**Scope:** Research-stage. Needs careful validation that generated scenarios have correct
ground truth. Lower priority.

### 1.6 Cross-Lane Correlation (future)

**What:** Feed results from all lanes into the LLM and ask it to identify patterns across
lanes — e.g., "agent battery failures on search-dependent scenarios correlate with Track G
regressions on similar query types."

**Why:** Individual lane analysis misses systemic issues that manifest across multiple
evaluation dimensions. Currently requires manual inspection.

**Scope:** Requires all lanes to produce rich enough data. Depends on use cases 1.1-1.3 being
implemented first.

---

## Area 2: Agent Development Quality

### 2.1 Agent Session Analysis

**What:** Feed an agent session's telemetry (`score-session.mjs` output) to Claude and
produce a diagnosis of inefficiency patterns — why the session scored poorly, what the
agent did wrong, what it should have done differently.

**Why:** `score-session.mjs` produces boolean signals (unbounded reads, bash file-ops,
thrashing, context pressure) and a composite 0-100 score. But these signals describe
*symptoms*, not *causes*. An LLM can read the actual sequence of tool calls and explain
"the agent read the same 500-line file 4 times because it forgot the content after
compaction" — actionable insight that a numeric score cannot provide.

**Input:** Session score JSON + the raw events NDJSON (~5-20K tokens per session).
**Cost:** ~$0.07-0.14 per session (1-2 calls depending on event volume).

**When valuable:** When tuning CLAUDE.md rules or agent discipline. The diagnosis says
"agents keep making this specific mistake" which directly informs rule changes.

### 2.2 CLAUDE.md Rule Effectiveness

**What:** After a batch of agent sessions, ask Claude to evaluate whether the CLAUDE.md
rules are being followed and which rules are ineffective (violated frequently without
consequence) or missing (recurring failures with no corresponding rule).

**Why:** The CLAUDE.md file is the primary lever for agent behavior. Currently, rule
effectiveness is assessed by manual observation. An LLM could systematically scan sessions
and report: "Rule X ('verify before implementing') was violated in 4/10 sessions —
agents compiled but didn't run tests."

**Input:** Multiple session score JSONs + CLAUDE.md content.
**Cost:** Single call, ~$0.07-0.14.

**When valuable:** After accumulating 10+ session transcripts. Needs enough data to
identify patterns.

---

## Area 3: CI / Build Pipeline

### 3.1 Build Failure Triage

**What:** When `./gradlew.bat build` or a CI workflow fails, feed the build output to
Claude and get a structured diagnosis: what failed, likely root cause, suggested fix.

**Why:** Build failures produce verbose output (Gradle stacktraces, PMD violations,
Spotless diffs, test failures). Finding the actual error in 200 lines of output takes
time. An LLM can extract the signal: "PMD violation in FooController.java:42 —
unused import of `java.util.List`."

**Input:** Build stderr/stdout (~1-5K tokens after truncation).
**Cost:** ~$0.07 per failure.

**When valuable:** This is useful immediately — build failures happen daily during
development. The question is whether the developer is already at the terminal reading
the output (in which case the LLM adds nothing) or whether this runs in CI where the
output is buried in workflow logs.

### 3.2 Dependency Update Analysis

**What:** When `report-lock-skew.mjs` detects dependency version skew, or when Dependabot
proposes updates, feed the skew report to Claude and get: which skews are safe to ignore,
which might cause breaking changes, suggested resolution order.

**Why:** Dependency skew reports are mechanical — "module A uses guava 33.1, module B
uses guava 33.0." An LLM could add: "guava 33.1 removed `@Beta` from `ImmutableList.builder()`
which you use in 3 files — this is a breaking change."

**Input:** Lock-skew JSON + changelog excerpts.
**Cost:** ~$0.07 per analysis.

**When valuable:** Only when dependency skew actually occurs and the changelog is accessible.
Limited by the LLM's knowledge cutoff for newer library versions.

---

## Area 4: Release Governance

### 4.1 Release Readiness Narrative

**What:** Feed the resilience governance artifacts (budget snapshot, lane triage,
control-loop conformance, evidence index) to Claude and produce a human-readable release
readiness assessment.

**Why:** The RR-219 governance system produces 4+ JSON artifacts per nightly run. The
`build-runtime-resilience-lane-triage.mjs` already has a markdown output, but it's a
mechanical report. An LLM narrative could synthesize across all artifacts: "Release gate
status: WARN. Two resilience controls are non-conformant (retry budget exceeded on gRPC
calls, activation timeout too aggressive). Recommended: increase retry budget before
shipping."

**Input:** 3-4 governance JSONs (~3-8K tokens total).
**Cost:** Single call, ~$0.07.

**When valuable:** When approaching a release and the governance artifacts need human
interpretation. Currently a single developer reviews these — the LLM saves interpretation
time.

---

## Area 5: Documentation & Knowledge

### 5.1 Tempdoc Staleness Detection

**What:** Periodically scan all tempdocs, compare their stated status against the codebase
(are referenced files still present? Are described gaps still open?), and flag stale docs.

**Why:** The tempdocs directory has 35+ documents. Some are active, some are complete,
some are outdated. Manually auditing staleness is tedious. An LLM could read each tempdoc's
acceptance criteria and check whether the described work still applies.

**Input:** Tempdoc content + targeted file existence checks.
**Cost:** ~$0.07 per tempdoc, ~$2.50 for all 35.

**When valuable:** Quarterly cleanup. Not a recurring automation — more of an on-demand
audit tool.

### 5.2 Canonical Doc Drift Detection

**What:** Compare canonical docs (`docs/explanation/`, `docs/reference/`) against the
actual codebase and flag sections that have drifted (describe behavior that no longer
matches the code).

**Why:** Canonical docs are the source of truth for architecture and API contracts. They
can silently drift as code changes. An LLM could read a doc section + the referenced code
and flag: "Section 3.2 says Head communicates with Body via REST, but the code uses gRPC."

**Input:** Doc section + relevant code files.
**Cost:** Variable, depends on doc/code size.

**When valuable:** After major refactors. Not a continuous automation.

---

## Value Assessment (2026-02-20)

### Area 1: Eval & Benchmark Pipeline

| Use Case | Effort | Value | Trigger |
|----------|--------|-------|---------|
| 1.1 Agent battery judge | ~1 day | Low now | When passing scenarios have quality issues (not currently happening) |
| 1.2 Regression explainer | ~0.5 day | Medium | When regressions become frequent enough to warrant automated triage |
| 1.3 Overnight narrative | ~0.5 day | Low | When team grows or scorecard becomes more complex |
| 1.4 Search result quality | ~2 days | Low | When nDCG/recall prove insufficient for quality signal |
| 1.5 Scenario generation | ~2 days | Low | When 16 scenarios prove insufficient for coverage |
| 1.6 Cross-lane correlation | ~1 day | Low | When multiple lanes have rich LLM-augmented data |

### Area 2: Agent Development Quality

| Use Case | Effort | Value | Trigger |
|----------|--------|-------|---------|
| 2.1 Session analysis | ~0.5 day | **Medium-high** | When tuning CLAUDE.md rules — explains *why* agents fail, not just *that* they fail |
| 2.2 Rule effectiveness | ~0.5 day | Medium | After accumulating 10+ session transcripts |

### Area 3: CI / Build Pipeline

| Use Case | Effort | Value | Trigger |
|----------|--------|-------|---------|
| 3.1 Build failure triage | ~0.5 day | **Medium** | Useful now in CI where output is buried in workflow logs |
| 3.2 Dependency update analysis | ~0.5 day | Low | Only when skew occurs and changelogs are accessible |

### Area 4: Release Governance

| Use Case | Effort | Value | Trigger |
|----------|--------|-------|---------|
| 4.1 Release readiness narrative | ~0.5 day | Medium | When approaching release and governance artifacts need synthesis |

### Area 5: Documentation & Knowledge

| Use Case | Effort | Value | Trigger |
|----------|--------|-------|---------|
| 5.1 Tempdoc staleness detection | ~0.5 day | Medium | On-demand quarterly cleanup |
| 5.2 Canonical doc drift detection | ~1 day | Medium | After major refactors |

### Highest-value candidates

**2.1 (Agent session analysis)** stands out: it addresses a real current need (understanding
why agents are inefficient), the input data exists (`score-session.mjs` + event NDJSON),
and the output (actionable diagnosis) directly improves CLAUDE.md rules. This is the only
use case where the LLM adds insight that is genuinely hard to get any other way.

**3.1 (Build failure triage)** is immediately useful in CI but marginal for local development
where the developer already sees the output.

Everything else is either solving a problem that doesn't currently exist, or adding a
convenience layer over data that's already human-readable.

---

## Technical Architecture

### Shared Infrastructure

All use cases share the same invocation pattern. A shared utility function:

```js
// scripts/ci/lib/llm-judge.mjs (or similar)
import { execSync } from 'node:child_process';
import { writeFileSync, readFileSync, unlinkSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

/**
 * Invoke Claude CLI for a single-shot LLM analysis.
 * @param {string} prompt - The full prompt text
 * @param {object} [opts] - Options
 * @param {string} [opts.model='sonnet'] - Model to use
 * @param {number} [opts.timeoutMs=60000] - Timeout in milliseconds
 * @returns {{ result: string, usage: object, cost: number }}
 */
export function invokeClaude(prompt, opts = {}) {
  const { model = 'sonnet', timeoutMs = 60_000 } = opts;
  const tmpFile = join(tmpdir(), `claude-judge-${Date.now()}-${Math.random().toString(36).slice(2)}.txt`);
  try {
    writeFileSync(tmpFile, prompt, 'utf8');
    const raw = execSync(
      `type "${tmpFile}" | claude --print - --output-format json --max-turns 1 --model ${model} --no-session-persistence`,
      { timeout: timeoutMs, encoding: 'utf8', shell: 'cmd.exe', windowsHide: true }
    );
    const parsed = JSON.parse(raw);
    if (parsed.is_error) throw new Error(`Claude CLI error: ${parsed.result}`);
    return {
      result: extractJson(parsed.result),
      rawResult: parsed.result,
      usage: parsed.usage,
      cost: parsed.total_cost_usd ?? 0,
    };
  } finally {
    try { unlinkSync(tmpFile); } catch {}
  }
}

/** Strip markdown code fences and parse JSON from model output */
function extractJson(text) {
  const fenceMatch = text.match(/```(?:json)?\s*\n([\s\S]*?)\n\s*```/);
  const jsonStr = fenceMatch ? fenceMatch[1] : text;
  return JSON.parse(jsonStr.trim());
}
```

### Per-Use-Case Scripts

| Script | Consumes | Produces |
|--------|----------|----------|
| `run-agent-battery-judge.mjs` | agent-battery manifest | `agent-battery-judge-manifest.v1` |
| `run-regression-explainer.mjs` | Track G / Claim A manifest with regressions | explanation annotations on manifest |
| `run-overnight-narrative.mjs` | scorecard JSON | `overnight-narrative-<timestamp>.md` |

### Overnight Runner Integration

Add as post-processing steps in `overnight-rag-ai-queue-win.ps1` after the scorecard is
built (Phase 5 in the runner flow). All LLM steps are non-blocking (warn on failure, don't
set `$hadFailures`). Skippable via `-SkipLlmAnalysis` flag.

**Overnight runner phase structure (from research):**
```
Phase 1-4: Lane execution (Claim A → Track G → RAG eval → Agent battery)
Phase 5:   Scorecard build (non-blocking, --discover-dir tmp/agent-evidence/_summaries)
Phase 6:   LLM post-processing (NEW — all non-blocking)
  6a: Overnight narrative (reads scorecard JSON)
  6b: Regression explainer (reads manifests with regressions)
  6c: Agent battery judge (reads agent-battery manifest)
Final:     Summary with all phase metadata
```

**Integration pattern:** Follow the existing non-blocking pattern used by the scorecard step:
```powershell
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try { & node scripts/ci/run-overnight-narrative.mjs --scorecard-json $scorecardPath ... }
finally { $ErrorActionPreference = $prevEap }
# Log warning on failure, don't set $hadFailures
```

**Shared utilities available:** `scripts/ci/lib/benchmark-ci-common.ps1` provides
`Write-JsonFileNoBom`, `Read-JsonBestEffort`, `Get-RunnerFingerprintExtended`.

---

## Verification Plan (Agent Battery Judge)

Test against the real overnight manifest (`agent-live-battery-manifest-overnight-20260219-225114.attempt01.json`,
16 scenarios, 7 pass / 9 fail on `required_facts`).

**Execution:**
```bash
cmd /c "set CLAUDECODE=& node scripts/ci/run-agent-battery-judge.mjs --manifest tmp/agent-evidence/_summaries/agent-live-battery-manifest-overnight-20260219-225114.attempt01.json"
```

**Acceptance criteria:**
1. All 16 scenarios scored (no errors, no missing entries)
2. Each per-scenario judge response parses to expected schema
3. The 7 passing scenarios should have higher mean `faithfulness_score` than the 9 failing ones
4. Total cost ~$1.12 (16 × ~$0.07)
5. Judge manifest written with correct kind, timestamps, aggregates, and per_scenario array

---

## Research Findings (2026-02-20)

### Scorecard JSON Structure

The consolidated scorecard (`benchmark-scorecard.v1`) has these key sections for LLM
consumption:

- **`lanes[]`** — Per-lane objects with: `lane`, `present`, `gate_status` (pass/fail/null),
  `comparable`, `regression_count`, `non_comparable_count`, `warnings[]`, `generated_at`.
  Agent-battery lanes additionally have `agent_pass_rate`, `agent_infra_failure`,
  `agent_total`, `agent_passed`, `agent_failed`, `agent_scenario_count`.
- **`trends.lanes[]`** — Per-lane trend with: `regression_signal_count`,
  `regression_rate`, `non_comparable_rate`, `insufficient_history`, `warnings[]`.
- **`release_readiness`** — `status` (pass/warn/fail), `reasons[]`, `missing_lanes[]`.

The scorecard is ~2-5K tokens — fits easily in a single LLM call for the narrative.

### Regression Detail Location

Manifests for Track G and Claim A contain `diff_decision_json` (a file path) pointing to
the detailed per-query regression analysis. The manifests themselves only have aggregate
`decision_regression_count`. To build a regression explainer, the script must:
1. Check `decision_regression_count > 0` in the manifest
2. Load `diff_decision_json` for per-query details
3. Optionally load `baseline_selected_path` and `candidate_selected_path` for the actual
   suite data with query/document pairs

### Agent Battery Per-Scenario Fields

Each scenario in the manifest's `scenarios[]` array contains:
- `prompt` — the user's question/task
- `finalResponse` — agent's answer (capped 8192 chars)
- `requiredFacts[]`, `expectedKeywords[]`, `mustNotContain[]` — ground truth
- `toolSequence[]` — tools called in order
- `toolTraces[]` — detailed tool invocation traces
- `status` (pass/fail), `reason` (structured error code)
- `iterationsUsed`, `toolCallsExecuted`, `totalTokensUsed`, `durationMs`

### Existing Script Infrastructure

- **Shared utilities:** `scripts/ci/lib/benchmark-ci-common.ps1` (PowerShell),
  `scripts/lib/json-utils.mjs` (Node.js BOM-aware JSON reading)
- **No existing `scripts/ci/lib/` for Node.js** — the `lib/` directory in `scripts/ci/`
  only has the PowerShell common module. The new `invokeClaude()` utility would be the first
  Node.js shared module in this path.
- **Output directory:** All manifests go to `tmp/agent-evidence/_summaries/` (the scorecard's
  `--discover-dir` path).

## Open Questions

1. **Judge consistency:** LLM-as-judge scores can vary between runs. Accept single-run
   variance for now; consider multi-run averaging if variance proves problematic.
2. **Scorecard integration:** Should the judge manifest be a 7th scorecard lane, or an
   additive extension on the existing agent-battery lane?
3. **Narrative placement:** Should the overnight narrative be a file on disk, printed to
   stdout, or both?

---

## Work Log

- 2026-02-20: Tempdoc created. Extracted Phase 4b from tempdoc 216 and expanded scope to
  include regression explanation, overnight narrative, search quality evaluation, scenario
  generation, and cross-lane correlation. All empirical testing of Claude Code CLI invocation
  was done in tempdoc 216 §IV.12.
- 2026-02-20: Researched overnight runner structure, manifest formats, scorecard JSON shape,
  and regression detail location (`diff_decision_json`). Updated architecture sections.
- 2026-02-20: Broadened scope from eval-only to repo-wide. Surveyed 12 CI workflows and
  225+ scripts. Added 5 areas (eval, agent quality, CI, release governance, documentation)
  with 12 use cases total. Critical value assessment identified 2.1 (agent session analysis)
  as highest-value candidate — addresses real current need, input data exists, output
  directly improves CLAUDE.md rules.

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 68 days at audit time.

