---
title: "CodeQL first manual run: alert triage — 18 of 27 alerts have code fixes, only 3 confirmed closed"
type: tempdoc
status: "in progress — PR #103 (ffa2d2d) + PR #108 (652fd17) merged; plus two uncommitted-as-of-writing fixes: alert #25 (cross-spawn) and the stripComments unification (12 alerts across 11 files, corrected from an original mis-scoped '18 alerts / 46 files' claim — see that section). Final CodeQL-confirmed state so far: 3/27 auto-closed (20/21/22); alert #24 fixed-but-CodeQL-won't-recognize; alert #1 fixed (root-caused, re-verified) but still shows open for an unexplained reason — do not treat as resolved; alerts #25 and the 12 stripComments alerts fixed but not yet re-scanned; 6 alerts wrongly folded into the original stripComments story still need individual triage; 3 alerts are false positives, left as-is"
created: 2026-07-08
updated: 2026-07-08
related: [695]
---

# 698 — CodeQL first manual run: alert triage — 18 of 27 alerts have code fixes, only 3 confirmed closed

## What this document is

`justsearch/codeql.yml` is `workflow_dispatch`-only (by design, matching ADR-0044's
"specialty workflows stay manual") and had never been run — zero executions in its history
before this session. This document records the first-ever manual dispatch of that workflow,
the resulting alert set, the triage applied to each alert, and the fixes made across several
passes (including two corrections caught only on revisit, not before merge — see the alert #1
and stripComments sections). It is a triage/fix log, not a design decision — no ADR or canonical
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
  Final state after both PR #103 and PR #108 merged and two subsequent CodeQL re-scans: **24
  still open** — only 3 (20/21/22) confirmed closed. See "Confirmed outcome, round 1/2" below for
  the full accounting.

## Code fixes made this session (6 alerts; only 3 confirmed closed on GitHub — see rows for #24, #1, #25)

| Alert # | Rule | File:line | Fix | Verification evidence |
|---|---|---|---|---|
| 21 | `js/reflected-xss` | `modules/ui-web/dev-examples/plugin-scaffold/dev-server.cjs:54` | 404 handler now sets `Content-Type: text/plain; charset=utf-8` + `X-Content-Type-Options: nosniff` before echoing the request path, so the reflected path can no longer be MIME-sniffed as HTML/script. | `node --check modules/ui-web/dev-examples/plugin-scaffold/dev-server.cjs` → exit 0 (syntax only; this is a standalone dev script with no test harness) |
| 20 | `js/reflected-xss` | `modules/ui-web/dev-examples/checklist-tracker/dev-server.cjs:49` | Identical fix — both files share the same copy-pasted 404-handler template. | `node --check modules/ui-web/dev-examples/checklist-tracker/dev-server.cjs` → exit 0 |
| 22 | `js/insecure-randomness` | `modules/ui-web/src/shell-v0/plugin-api/capabilities/ai.ts:152` | `Math.random().toString(36)`-derived session ID replaced with `crypto.randomUUID()`. Traced the usage first: `getSessionTranscript(sessionId)` calls `GET /api/chat/sessions/{sessionId}/transcript` keyed solely by this ID (`ai.ts:183-190`), so a guessable ID was a plausible session-hijack vector for anything else able to reach the loopback API (e.g. another installed plugin). | `cd modules/ui-web && npx vitest run src/shell-v0/plugin-api/HostApiAi.test.ts` → 15/15 passed (the one relevant test, `HostApiAi.test.ts:145-150`, asserts ID uniqueness only, not format, so the change doesn't need a test update). `npm run typecheck` → exit 0 clean (this module is on the [known pre-existing TS5101-red baseline per `expected-state.v1.json`]; this run passed clean, i.e. strictly better than baseline, not regressed). |
| 24 | `js/prototype-pollution-utility` | `scripts/dev/justsearch-dev-mcp-harness.mjs:60-68` (`setDeep`) | Added an `UNSAFE_KEY_SEGMENTS` guard rejecting `__proto__`/`constructor`/`prototype` path segments, thrown as an error instead of silently polluting. **CodeQL re-scanned this after merge and still shows the alert `open`** (`most_recent_instance` on the merge commit points at the same assignment line, now shifted to line 73) — its taint-tracking query doesn't recognize an early-throw `.some()` + `Set.has()` guard as a sanitizer for this rule. Verified independently of CodeQL's opinion: a direct test calling `setDeep({}, '__proto__.polluted', 'PWNED')` throws before any assignment and `Object.prototype.polluted` stays `undefined` afterward — the guard is genuinely effective, this is a CodeQL static-recognition gap, not an unfixed vulnerability. Left as-is rather than chasing a CodeQL-recognized rewrite (e.g. `Object.create(null)` intermediates) — low value for a script with one hardcoded call site. | `node --check scripts/dev/justsearch-dev-mcp-harness.mjs` → exit 0. Live-attack test: `setDeep({}, '__proto__.polluted', 'PWNED')` → throws `setDeep: refusing unsafe path segment...`; `({}).polluted` → `undefined` (unpolluted) both before and after the attempt. |
| 1 | `js/redos` | `scripts/ci/verify-test-evidence-policy.mjs:216-224` (`elementAfter`) | **STILL OPEN on GitHub after two merged attempts — do not treat as resolved.** Attempt 1 (PR #103, `ffa2d2d`) bounded the regex's search window to 4000 chars (`ELEMENT_AFTER_WINDOW`). A closer adversarial-timing check (binary-searching input size after the fact) showed this doesn't work: a run of just ~150 whitespace characters — nowhere near the 4000-char bound — still took multiple seconds, because the blowup is exponential in the matched-and-failed substring, not the total input length; slicing the outer input doesn't bound that. Root cause found: the character class `[\w<>\[\], ? extends super.&]` (used twice, for a generic-type signature) contained a literal space, overlapping with the adjacent `\s+`/`\s*` quantifiers — the classic two-quantifiers-matching-the-same-characters ReDoS shape. Attempt 2 (PR #108, `652fd17`) removed the space from both occurrences; the 4000-char bound was kept as defense-in-depth. **This empirically fixed the specific attack found** (see verification column) **but a fresh CodeQL run after merge still shows the alert `open`** — see "Confirmed outcome, round 2" below. Unresolved: either a different backtracking-prone construct remains in the same regex, or this is a static-recognition gap like #24's. Not chased further this session — needs either deeper investigation or a full rewrite away from nested quantifiers. | `node scripts/ci/test-verify-test-evidence-policy.mjs` → `PASS` (existing test suite, unmodified — confirms no behavior change for legitimate input). Adversarial-timing, both patterns, run directly against the fixed regex: whitespace-run input up to 100,000 chars → 0-1ms; `@`-repetition and `"0final @"`-repetition input (CodeQL's own example text for this alert) up to 20,000 chars → 0ms both before and after the fix (neither pattern actually reproduced a hang — the real adversarial input was the whitespace run, found independently). Before attempt 2, the whitespace pattern exceeded 5 seconds at just ~150 chars. |
| 25 | `js/shell-command-injection-from-environment` | `scripts/ci/report-build-attribution.mjs:334-345` (`runCommand`) | **Investigated and fixed, not just deferred** (was originally triaged "false positive as currently called, deferred hardening" — revisited on request). The hand-rolled `quoteCmdArg()` + manual `cmd.exe /d /s /c` invocation for `.bat`/`.cmd` targets on Windows was removed entirely and replaced with [`cross-spawn`](https://github.com/moxystudio/node-cross-spawn) (`^7.0.6`), already present in the dependency tree as a transitive dependency of `@modelcontextprotocol/sdk` and now promoted to a direct `devDependency`. `cross-spawn` is the same library npm/yarn use internally for exactly this problem; its escaping algorithm implements the reference Windows-quoting rules from qntm.org/cmd and was itself hardened against ReDoS in its own history (`moxystudio/node-cross-spawn#160`) — a validating precedent for choosing it. Investigation also found the previous code path was **dead code with zero coverage**: the only live CI caller (`.github/workflows/ci.yml:166`) invokes `./gradlew` (no `.bat` suffix) on `ubuntu-latest`, so the `.bat`+`cmd.exe` branch never executed in production; the existing test suite covered `buildReport()`'s formatting logic with `.bat`-shaped command arrays but never actually spawned one. Not a live vulnerability today, but a real gap now closed rather than left for "if this script's inputs ever become less trusted." | `node scripts/ci/test-report-build-attribution.mjs` → `PASS`, including two new tests added for this fix (previously zero coverage of the `.bat` path): (1) realistic Gradle-style arguments, including a Windows path containing spaces, round-trip byte-exact through a real `.bat` file spawned via `cross-spawn.sync`; (2) a classic injection payload (`legit-arg & echo INJECTED > "<marker-file>"`) passed as one argument does not create the marker file — confirmed the payload cannot break out of its argument slot to run a second command, and the argument following it still arrives intact. |

**Confirmed outcome, round 1 (PR #103 merged as `ffa2d2d`; CodeQL run
[actions/runs/28911958085](https://github.com/eliasjustus/justsearch/actions/runs/28911958085),
`conclusion: success`, analyzed commit `ffa2d2d`):** only 3 of the 5 auto-closed —
`gh api repos/eliasjustus/justsearch/code-scanning/alerts/{20,21,22}` → `state: fixed`. Alerts
`24` and `1` came back `state: open`.

**Confirmed outcome, round 2 (PR #108 merged as `652fd17`, the root-caused regex fix for alert
#1; CodeQL run [actions/runs/28913033476](https://github.com/eliasjustus/justsearch/actions/runs/28913033476),
`conclusion: success`, analyzed commit `652fd17`):** alert `#1` is **still `state: open`** —
total open-alert count stayed at 24, unchanged, even after an extended wait (~4 min of polling)
to rule out GitHub's alert-recomputation lag (the mechanism that made 20/21/22 take a short but
nonzero time to flip after round 1). This is not the same situation as #24 (a known, understood
CodeQL-recognition gap) — **it is an open question.** What's actually verified: the specific
attack that motivated the fix (a run of ~150+ whitespace characters causing multi-second
backtracking) no longer reproduces — direct regex execution against the fixed pattern is 0-1ms
up to 100,000 characters. What's *not* verified: whether CodeQL's static analyzer is (a) still
seeing a genuinely different backtracking-prone construct in the same regex that hasn't been
found/tested, or (b) hitting the same kind of static-recognition limitation as #24. Do not
assume either explanation without further investigation — this needs a person, or a full rewrite
of `elementAfter`'s regex away from nested quantifiers entirely (a manual character-scanning loop
instead of one large regex), which would sidestep the question rather than resolve it.

**Lesson:** "the alerts will auto-close once pushed" was itself an assumption that turned out
false for 2 of 5 after round 1, and even a *root-caused, empirically re-verified* fix (alert #1,
round 2) still didn't close its alert — don't take an alert's open/fixed state as self-evident
from "I edited the flagged line" or even "I found and fixed a real bug at that line"; check the
actual post-merge scan result, and don't stop investigating just because the first re-check looks
plausible.

## Triaged, not fixed (3 individually-reviewed alerts)

Each was checked against its actual call site (not just the rule label) before deciding not to
fix it this session:

| Alert # | Rule | File:line | Assessment | Reasoning |
|---|---|---|---|---|
| 27 | `java/error-message-exposure` | `modules/ui/src/main/java/io/justsearch/ui/api/SseWriter.java:105` | Likely false positive | The flagged catch block (`SseWriter.java:116-121`) only reaches `log.warn(...)` server-side; the exception is never written into the HTTP response. Also mitigated by loopback-only binding (Hard Invariant #2) even if this read were wrong. |
| 26 | `js/clear-text-logging` | `scripts/agent-analytics/record-merge.mjs:79` | False positive | Logs a local session UUID truncated to 8 chars for merge-attribution telemetry — not a credential, token, or secret. |
| 23 | `js/insecure-randomness` | `scripts/ci/run-agent-resume-replay-matrix.mjs:310` | False positive | Synthetic test-fixture session ID for a CI replay-matrix test, not a security boundary. |

## Fixed — the duplicated `stripComments` helper, unified (originally mis-scoped as 18 alerts / 46 files; corrected below)

**Correction to the original triage in this section (caught on revisit, not before):** the
original pass claimed all 18 `js/incomplete-(multi-character-)sanitization` alerts traced to one
duplicated helper across "46 files," found via a loose search
(`rg -l "stripComments|const norm = |replace\\(/<!--<"  scripts/`). Both numbers were wrong:

- The loose `rg` pattern over-matched on the generic `const norm = ` fragment (used by unrelated
  path-normalization code, e.g. `scripts/agent-analytics/hooks/intervene.mjs`) and on bare
  mentions of the word "stripComments". The **precise** match count (files containing the actual
  4-step or 3-step chained-regex body) was **20 files**, not 46.
- Of the original 18 CodeQL alerts, only **12** (across 11 distinct files — `check-controls-a11y.mjs`
  has 2 flagged lines) actually trace to this duplicated helper. The other **6** alerts
  (`textHelpers.ts:7`'s `stripHtml`, `sarif-to-markdown.mjs:91`'s markdown pipe-escaper,
  `check-shape-view-coverage.mjs:92`'s dynamic-regex-metachar escaper, and
  `prose-tier-register/{scanner,enforcer}.mjs`'s simpler one-line `<!--[^>]*-->` anchor-comment
  strip) are **unrelated, individually-distinct sanitization issues** that happen to share the
  same CodeQL rule family — they were incorrectly folded into this story and still need their own
  individual triage (not attempted this session; see Remaining work).
- A further repo-wide sweep (beyond the 20 CodeQL-visible files) found **5 more files** using
  variant shapes of the identical bug (a 2-step "block comment, then line comment" chain, with no
  `://`-guard on the line-comment step, in `check-modality-contract.mjs`, `check-thread-event-kinds.mjs`,
  `check-realized-capability.mjs`, `check-capability-availability.mjs`, `check-ambient-purity.mjs`) —
  none of these were CodeQL-flagged (their output apparently doesn't reach a CodeQL-recognized
  sink), but they're the same defect. **Total files actually fixed this pass: 25** (20 + 5), not
  46 and not 18.

**Also corrected: the original recommended fix was wrong.** The first framing of this problem
recommended "extract one canonical shared regex-based helper." That's not the right fix — the
user pushed back (rightly): this is a well-known, already-solved problem, and a real single-pass
scanner is not a novel invention needed here. It was inline-invented anyway *because* nobody
checked first — a textbook instance of this repo's own `explore-before-implementing` rule (the
repo's dependency tree already carries the TypeScript compiler, Babel's parser, Acorn, and Espree
— any of which trivially and unambiguously locates JS/TS comments via a real grammar, not text
matching). Since this corpus mixes JS/TS scanning with Java-source scanning (which those parsers
don't cover) and Markdown/HTML-comment scanning, the pragmatic fix landed on: a small, correct,
**single-pass, mode-tracked character scanner** (`scripts/lib/strip-comments.mjs`) — the same
technique already used elsewhere in this exact repo for the identical problem (see below) —
rather than either a four-pass regex chain or pulling in a full language parser per call site.

**The correct pattern already existed in this codebase, unused by the 25 duplicated copies:**
`scripts/ci/verify-test-evidence-policy.mjs`'s own `stripCommentsAndStrings()` (used to produce
`clean` text for `discoverEvidenceSubjects`, in the very same file whose `elementAfter` regex
was this tempdoc's alert #1) is *already* a correct single-pass, mode-tracked scanner — proof
that the safe pattern was one file-read away the whole time.

**The concrete bug, demonstrated (not just asserted):** the chained-regex version processes
comment kinds in a fixed order, and each `.replace()` call scans the *output* of the previous
call — so removing one comment kind can bring previously non-adjacent characters together into
a brand-new comment-looking sequence that never existed in the original text, hiding real code:

```
stripComments('/<!-- hide -->* REAL_USAGE_X */')
// original chained-regex version: ''                       (REAL_USAGE_X silently erased)
// scripts/lib/strip-comments.mjs: '/* REAL_USAGE_X */'      (REAL_USAGE_X preserved)
```

For a governance gate whose entire job is "does this source actually use symbol X", an
attacker (or an accident) that erases `REAL_USAGE_X` from the gate's view defeats the gate
outright — a "wrong-gate"-class concern (this repo's own
`docs/reference/contributing/agent-postmortems.md` vocabulary), not a cosmetic nit.

**Verification, not just a differential-test claim:**
- `node scripts/lib/strip-comments.test.mjs` → `PASS` — 25 representative + edge-case inputs
  (unterminated comments, division operators, `://` URLs, empty comments, etc.) produce
  byte-identical output to the original chained-regex implementation, for both the
  `withHtml: true` (11 files) and `withHtml: false` (9 files) variants — plus the bypass
  demonstration above, confirming the original is vulnerable and the replacement is not.
- All **25** migrated gate scripts run clean (`exit 0`) against the real repository tree
  post-migration.
- The two pre-existing dedicated tests that import a migrated file's `stripComments` export
  directly (`check-realized-capability.test.mjs`, `check-capability-availability.test.mjs`) →
  both `PASS`.
- **4 direct before/after spot-checks** (`check-atom-fork-ratchet`, `check-language-agnostic-analysis`,
  `check-ambient-purity`, `check-realized-capability` — spanning all three variant shapes: 4-step,
  3-step, and the 2-step block+line variant) via `git stash push -- <file>` isolating one file at a
  time: byte-identical stdout/stderr and identical exit code before and after migration, confirming
  the fix is behavior-preserving for real, legitimate repository content — not just for
  synthetic test cases.

**Not attempted this session — the 6 individually-distinct alerts wrongly folded into this
story originally** (see Remaining work): `textHelpers.ts`, `sarif-to-markdown.mjs`,
`check-shape-view-coverage.mjs`, and `prose-tier-register/{scanner,enforcer}.mjs` each need
their own individual triage, unrelated to this fix.

## Unverified assumptions (explicit)

- ~~That the 5 fixes will make their corresponding GitHub alerts auto-close once pushed~~ —
  **resolved, turned out false for 2 of 5.** See "Confirmed outcome" above: only 20/21/22
  closed; #24 won't (CodeQL-recognition gap, code is safe); #1 didn't close even after a
  second, root-caused, re-verified fix — genuinely unresolved, see Remaining work's alert-#1 item.
- That alerts 26/27's "false positive" assessment holds for *all* call sites, not just the
  one each was traced to — each function (the `console.log` in `record-merge.mjs`,
  `SseWriter.writeResult`) was checked at its actual (single, in each case) call site only.
  (Alert 25 is no longer in this bucket — it moved from "false positive, deferred" to an
  actual fix; see its row in the fixes table.)
- ~~That the `js/incomplete-multi-character-sanitization` root-cause theory (shared duplicated
  helper) is correct for all 16 CodeQL-flagged instances... inferred, not individually read~~ —
  **resolved.** All 25 files with the actual duplicated pattern (not 46 — that count was also an
  artifact of a loose search) were individually read and migrated; 4 spot-checked directly
  against their pre-migration behavior. The theory held for 12 of the original 18 alerts; the
  other 6 turned out to be unrelated issues incorrectly folded in (see the stripComments
  section above) — that was the actual gap in the original assumption, not under-verification of
  the ones that *were* real.

## Remaining work / do not forget

1. **Alert #1 is the open item that actually needs work.** Two merged attempts, the second
   root-caused and empirically re-verified against the specific attack found, and the GitHub
   alert *still* shows `open` after both a normal wait and an extended ~4-minute poll (ruling out
   simple recomputation lag — compare: 20/21/22 flipped within that kind of window in round 1).
   Next step is not "wait longer" — it's either (a) find what CodeQL's static analyzer is still
   seeing in the regex that the empirical testing hasn't reproduced, or (b) stop trying to patch
   the existing regex and rewrite `elementAfter` as a manual character-scan loop instead of one
   large regex, which sidesteps nested-quantifier backtracking by construction rather than
   patching around it.
2. **Done, not yet pushed as of this writing:** the `stripComments` unification (see its section
   above) — 25 files migrated to `scripts/lib/strip-comments.mjs`, differential-tested,
   spot-checked. Commit and re-run CodeQL to confirm the 12 previously-flagged alerts
   (3/4/5/6/7/8/9/10/11/12/13/14) close.
3. **New from the stripComments correction:** 6 alerts wrongly folded into the original
   "stripComments" story still need their own individual triage — `textHelpers.ts:7` (`stripHtml`,
   a naive single-pass HTML-tag stripper, different concern entirely), `sarif-to-markdown.mjs:91`
   (markdown pipe-escaping), `check-shape-view-coverage.mjs:92` (regex-metachar escaping for a
   dynamically-built `RegExp`), and `prose-tier-register/{scanner,enforcer}.mjs` (a simpler,
   single-step `<!--[^>]*-->` anchor-comment strip — related in spirit but not the same bug, and
   not migrated to the shared helper since its call sites are line-scoped, not full-file).
4. **Done, not yet pushed as of this writing:** alert #25's `quoteCmdArg`/manual-`cmd.exe`
   replacement with `cross-spawn` (see its row above) — commit this and re-run CodeQL to confirm
   it closes. Also worth: wiring `scripts/ci/test-report-build-attribution.mjs` into an actual CI
   job (investigation for this fix found it isn't currently run by any workflow — it was only
   ever run manually) so its now-meaningful `.bat`-path coverage doesn't silently bit-rot.
5. **Alert #1 is still the one open item that actually needs work.** Two merged attempts, the
   second root-caused and empirically re-verified against the specific attack found, and the
   GitHub alert *still* shows `open` after both a normal wait and an extended ~4-minute poll
   (ruling out simple recomputation lag — compare: 20/21/22 flipped within that kind of window in
   round 1). Next step is not "wait longer" — it's either (a) find what CodeQL's static analyzer
   is still seeing in the regex that the empirical testing hasn't reproduced, or (b) stop trying
   to patch the existing regex and rewrite `elementAfter` as a manual character-scan loop instead
   of one large regex, which sidesteps nested-quantifier backtracking by construction rather than
   patching around it.
