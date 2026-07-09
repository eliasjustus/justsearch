---
title: "CodeQL first manual run: alert triage, 5 fixes landed, 22 alerts remaining"
type: tempdoc
status: "in progress — PR #103 merged (ffa2d2d); post-merge CodeQL re-scan confirmed only 3/5 fixes auto-closed (20/21/22); alert #24 is fixed-but-CodeQL-won't-recognize, alert #1's first fix was insufficient and needed a follow-up commit (not yet pushed as of this writing) — see 'Confirmed outcome' below"
created: 2026-07-08
updated: 2026-07-08
related: [695]
---

# 698 — CodeQL first manual run: alert triage, 5 fixes landed, 22 remaining

## What this document is

`justsearch/codeql.yml` is `workflow_dispatch`-only (by design, matching ADR-0044's
"specialty workflows stay manual") and had never been run — zero executions in its history
before this session. This document records the first-ever manual dispatch of that workflow,
the resulting alert set, the triage applied to each alert, the 5 fixes made and verified, and
the 22 items still open. It is a triage/fix log, not a design decision — no ADR or canonical
doc changed.

**Public-visibility note:** CodeQL alerts on a public repository are visible to anyone who can
see the repo (unlike Dependabot/secret-scanning alerts, which stay restricted to write-access
collaborators even on public repos). The alerts described below are consequently already
public at
[github.com/eliasjustus/justsearch/security/code-scanning](https://github.com/eliasjustus/justsearch/security/code-scanning)
once this session's commit reaches `origin`.

## The run

- Triggered: `gh workflow run codeql.yml --ref main`
- Run: [actions/runs/28896727993](https://github.com/eliasjustus/justsearch/actions/runs/28896727993)
  — `conclusion: success` for both jobs (`Analyze Java`, `Analyze JavaScript/TypeScript`),
  completed 2026-07-08T20:47:03Z (job start 20:32:57–20:32:59, ~14 min wall time).
- Result: 27 open alerts via `gh api repos/eliasjustus/justsearch/code-scanning/alerts?state=open`.
  Re-checked at the time of writing this doc — still 27 open, because the 5 fixes below are
  pushed as [PR #103](https://github.com/eliasjustus/justsearch/pull/103) but not yet merged;
  alerts auto-close only when a subsequent CodeQL run scans code that no longer contains the
  pattern.

## Fixed and verified this session (5 alerts)

| Alert # | Rule | File:line | Fix | Verification evidence |
|---|---|---|---|---|
| 21 | `js/reflected-xss` | `modules/ui-web/dev-examples/plugin-scaffold/dev-server.cjs:54` | 404 handler now sets `Content-Type: text/plain; charset=utf-8` + `X-Content-Type-Options: nosniff` before echoing the request path, so the reflected path can no longer be MIME-sniffed as HTML/script. | `node --check modules/ui-web/dev-examples/plugin-scaffold/dev-server.cjs` → exit 0 (syntax only; this is a standalone dev script with no test harness) |
| 20 | `js/reflected-xss` | `modules/ui-web/dev-examples/checklist-tracker/dev-server.cjs:49` | Identical fix — both files share the same copy-pasted 404-handler template. | `node --check modules/ui-web/dev-examples/checklist-tracker/dev-server.cjs` → exit 0 |
| 22 | `js/insecure-randomness` | `modules/ui-web/src/shell-v0/plugin-api/capabilities/ai.ts:152` | `Math.random().toString(36)`-derived session ID replaced with `crypto.randomUUID()`. Traced the usage first: `getSessionTranscript(sessionId)` calls `GET /api/chat/sessions/{sessionId}/transcript` keyed solely by this ID (`ai.ts:183-190`), so a guessable ID was a plausible session-hijack vector for anything else able to reach the loopback API (e.g. another installed plugin). | `cd modules/ui-web && npx vitest run src/shell-v0/plugin-api/HostApiAi.test.ts` → 15/15 passed (the one relevant test, `HostApiAi.test.ts:145-150`, asserts ID uniqueness only, not format, so the change doesn't need a test update). `npm run typecheck` → exit 0 clean (this module is on the [known pre-existing TS5101-red baseline per `expected-state.v1.json`]; this run passed clean, i.e. strictly better than baseline, not regressed). |
| 24 | `js/prototype-pollution-utility` | `scripts/dev/justsearch-dev-mcp-harness.mjs:60-68` (`setDeep`) | Added an `UNSAFE_KEY_SEGMENTS` guard rejecting `__proto__`/`constructor`/`prototype` path segments, thrown as an error instead of silently polluting. **CodeQL re-scanned this after merge and still shows the alert `open`** (`most_recent_instance` on the merge commit points at the same assignment line, now shifted to line 73) — its taint-tracking query doesn't recognize an early-throw `.some()` + `Set.has()` guard as a sanitizer for this rule. Verified independently of CodeQL's opinion: a direct test calling `setDeep({}, '__proto__.polluted', 'PWNED')` throws before any assignment and `Object.prototype.polluted` stays `undefined` afterward — the guard is genuinely effective, this is a CodeQL static-recognition gap, not an unfixed vulnerability. Left as-is rather than chasing a CodeQL-recognized rewrite (e.g. `Object.create(null)` intermediates) — low value for a script with one hardcoded call site. | `node --check scripts/dev/justsearch-dev-mcp-harness.mjs` → exit 0. Live-attack test: `setDeep({}, '__proto__.polluted', 'PWNED')` → throws `setDeep: refusing unsafe path segment...`; `({}).polluted` → `undefined` (unpolluted) both before and after the attempt. |
| 1 | `js/redos` | `scripts/ci/verify-test-evidence-policy.mjs:216-224` (`elementAfter`) | **Two attempts — the first (merged in PR #103) was insufficient and was caught afterward, not before.** Attempt 1 bounded the regex's search window to 4000 chars (`ELEMENT_AFTER_WINDOW`). A closer adversarial-timing check (binary-searching input size after the fact) showed this doesn't work: a run of just ~150 whitespace characters — nowhere near the 4000-char bound — still took multiple seconds, because the blowup is exponential in the matched-and-failed substring, not the total input length; slicing the outer input doesn't bound that. Root cause: the character class `[\w<>\[\], ? extends super.&]` (used twice, for a generic-type signature) contained a literal space, overlapping with the adjacent `\s+`/`\s*` quantifiers — the classic two-quantifiers-matching-the-same-characters ReDoS shape. **Attempt 2 (follow-up commit, not yet merged as of this writing):** removed the space from both occurrences of that character class. The 4000-char bound was kept as defense-in-depth but is no longer the load-bearing fix. | `node scripts/ci/test-verify-test-evidence-policy.mjs` → `PASS` (existing test suite, unmodified — confirms no behavior change for legitimate input). Adversarial-timing, both patterns, run directly against the fixed regex: whitespace-run input up to 100,000 chars → 0-1ms; `@`-repetition input (CodeQL's own example for this alert) up to 20,000 chars → 0ms. Before attempt 2, the whitespace pattern exceeded 5 seconds at just ~150 chars, confirming attempt 1's "PASS" wasn't false — the existing tests just don't exercise pathological input, only correctness on real Java source. |

**Confirmed outcome (PR #103 merged as `ffa2d2d`; fresh CodeQL run
[actions/runs/28911958085](https://github.com/eliasjustus/justsearch/actions/runs/28911958085),
`conclusion: success`, analyzed commit `ffa2d2d`):** only 3 of the 5 auto-closed —
`gh api repos/eliasjustus/justsearch/code-scanning/alerts/{20,21,22}` → `state: fixed`. Alerts
`24` and `1` came back `state: open`, each for a different, now-understood reason (see their
rows above): #24 is CodeQL not recognizing the sanitizer idiom (code is actually safe); #1 was a
genuinely insufficient fix, corrected in a follow-up commit (not yet re-scanned as of this
writing — do not assume it closes without re-running CodeQL against the follow-up commit).
**Lesson:** "the alerts will auto-close once pushed" was itself an assumption that turned out
false for 2 of 5 — don't take an alert's open/fixed state as self-evident from "I edited the
flagged line"; check the actual post-merge scan result.

## Triaged, not fixed (4 individually-reviewed alerts)

Each was checked against its actual call site (not just the rule label) before deciding not to
fix it this session:

| Alert # | Rule | File:line | Assessment | Reasoning |
|---|---|---|---|---|
| 27 | `java/error-message-exposure` | `modules/ui/src/main/java/io/justsearch/ui/api/SseWriter.java:105` | Likely false positive | The flagged catch block (`SseWriter.java:116-121`) only reaches `log.warn(...)` server-side; the exception is never written into the HTTP response. Also mitigated by loopback-only binding (Hard Invariant #2) even if this read were wrong. |
| 26 | `js/clear-text-logging` | `scripts/agent-analytics/record-merge.mjs:79` | False positive | Logs a local session UUID truncated to 8 chars for merge-attribution telemetry — not a credential, token, or secret. |
| 25 | `js/shell-command-injection-from-environment` | `scripts/ci/report-build-attribution.mjs:336` | False positive as currently called | Traced `command` back to `opts.command` (`report-build-attribution.mjs:65`), which comes from fixed CLI args in the CI workflow invocation, not PR/branch-derived environment content. **Deferred hardening, not urgent:** `quoteCmdArg` (`report-build-attribution.mjs:217-221`) is a hand-rolled `cmd.exe` argument escaper; this class of custom Windows quoting has a known history of escape bugs (cf. CVE-2024-27980-style Node/Windows issues) and is worth replacing with a vetted escaping library if this script's inputs ever become less trusted. |
| 23 | `js/insecure-randomness` | `scripts/ci/run-agent-resume-replay-matrix.mjs:310` | False positive | Synthetic test-fixture session ID for a CI replay-matrix test, not a security boundary. |

## Not fixed — root-caused to one duplicated helper (18 alerts)

Alerts 2–19 (16 tagged `js/incomplete-multi-character-sanitization`, 2 tagged
`js/incomplete-sanitization`) all trace to variants of the same `stripComments`-style chained
regex helper (strip `<!-- -->`, then `/* */`, then doc-comment lines, then `//` line comments,
in that fixed order) — see e.g. `scripts/ci/check-atom-fork-ratchet.mjs:42-48` for one instance.

**This is broader than the 18 flagged alerts.** A repo-wide search
(`rg -l "stripComments|const norm = |replace\\(/<!--<"  scripts/`) found the same pattern
independently reimplemented in **46 files** under `scripts/ci/`, `scripts/governance/`,
`scripts/agent-analytics/`, and `scripts/dev/justsearch-dev-mcp/`. CodeQL only flags the 18 (of
46) whose stripped output feeds into a security-relevant decision (a governance gate's
pass/fail) — the other 28 uses of the same pattern were not analyzed as reaching a CodeQL sink,
not because they're safe.

**Why this matters beyond CodeQL's specific complaint:** a fixed-order chained strip can be
defeated by adversarially nested delimiters (e.g. content crafted so that stripping `/* */`
first leaves behind a reconstituted `//` sequence, or vice versa) — for a governance gate that
exists specifically to *detect real usage of something in source*, a bypassable comment-stripper
means a crafted source file could hide real usage from the gate. This is a "wrong-gate"-class
concern (per this repo's own `docs/reference/contributing/agent-postmortems.md` vocabulary),
not merely 18 unrelated code-quality nits.

**Follow-up, not attempted this session (scope too large for one sitting):**
1. Extract one canonical `stripComments`/`stripCommentsForSourceScan` helper (module location
   TBD — a `scripts/lib/` or similar shared location) with an order/algorithm that isn't
   defeatable by nested delimiters (e.g. a single-pass tokenizer rather than four sequential
   `.replace()` calls).
2. Migrate the 46 call sites to import it instead of reimplementing it locally.
3. Re-run CodeQL after the migration and confirm alerts 2–19 close.
4. This is exactly the class of "helper reimplemented instead of reused" this repo's own
   `explore-before-implementing` rule warns about — worth noting as a concrete instance the
   next time that rule needs a real example.

## Unverified assumptions (explicit)

- That the 5 fixes will make their corresponding GitHub alerts auto-close once pushed — not
  confirmed; needs a fresh CodeQL run against the merged commit (see Follow-up above).
- That alerts 25/26/27's "false positive" assessment holds for *all* call sites, not just the
  one each was traced to — each function (`runCommand`, the `console.log` in `record-merge.mjs`,
  `SseWriter.writeResult`) was checked at its actual (single, in each case) call site only.
- That the `js/incomplete-multi-character-sanitization` root-cause theory (shared duplicated
  helper) is correct for all 16 CodeQL-flagged instances of that specific rule — confirmed for
  `check-atom-fork-ratchet.mjs` by direct read; the other 15 (plus the 2 sibling
  `js/incomplete-sanitization` alerts) were inferred from the shared `rg` match, not
  individually read line-by-line.

## Remaining work / do not forget

1. Push the follow-up `elementAfter` regex fix (attempt 2, see alert #1 above) and re-run
   CodeQL to confirm it closes. Confirmed already: alerts 20/21/22 closed; alert 24 will very
   likely stay open indefinitely (CodeQL-recognition gap, not a real issue — see its row).
2. Decide on and schedule the `stripComments` unification (18+ alerts, 46 files) — largest
   remaining item by alert count, needs its own scoped session.
3. Optional hardening, not urgent: replace the hand-rolled `quoteCmdArg` in
   `report-build-attribution.mjs` with a vetted Windows-shell-escaping approach (alert #25).
4. Separately from CodeQL: earlier this session, `main`'s `license-and-notices` CI check
   (`.github/workflows/ci.yml:92-120`, `checkLicense` Gradle task) was found cancelled on commit
   `8cacb20` after running 25 minutes against a 20-minute job timeout. Re-checked at the time of
   writing: on the current `main` tip (`156088d`), the same check completed in 6 minutes with
   `conclusion: success` (`started_at` 23:24:23, `completed_at` 23:30:09 UTC) — no change to
   `.github/workflows/ci.yml` or the license-check logic happened in between (`git log` on that
   path shows nothing since `06a4c67`), so this reads as a one-off slow/flaky run rather than a
   persistent defect. **Follow-up:** no action needed unless it recurs; if it does, worth adding
   retry logic or investigating why `checkLicense --no-configuration-cache --no-parallel` runs
   slow on some CI invocations but not others.
