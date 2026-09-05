---
title: "Enforce publication-record hygiene at the agent merge boundary"
type: tempdocs
status: "PUBLISHED — PR #675 MERGED; POST-MERGE CI PASSED"
created: 2026-09-05
updated: 2026-09-05
charter: "close the gap between the publication-record contract and the paths autonomous agents actually use, while fixing provider-residue detection"
related:
  - 921-separate-pr-review-record-from-public-squash-record
  - 929-publishing-process-waste-deep-investigation
---

# 933 — Enforce publication-record hygiene at the agent merge boundary

## 0. Problem and acceptance contract

Tempdoc 921 established a concise public PR body plus one managed review-record
comment, but the contract is still mostly instructional. A post-activation audit found
that managed records remained inconsistent, provider residue remained common, and
multiple PRs landed despite failing the repository's current validators. The current
open authored PR at the start of this work also fails those validators.

One confirmed detector defect contributes to that gap: the provider-residue matcher
recognizes plain `Generated with Claude Code` text and a legacy session URL, but not
the linked footer currently emitted by Claude Code.

This work is complete only when:

- the linked-provider footer and equivalent durable provider banners are rejected by
  the shared public-body validator without creating obvious Markdown false positives;
- ordinary documented autonomous-agent publication paths cannot request a merge without
  first proving the live PR title/body and managed review record satisfy the existing
  contract;
- the live check runs immediately before the merge request, validates the managed
  record's head/body fingerprints, and fails closed on unavailable or malformed GitHub
  evidence while retaining the explicit no-CAS limit below;
- the ordinary GitHub merge queue, squash settings, and durable managed-comment design
  remain the publication authority;
- both Codex and Claude instruction surfaces and canonical contributor documentation
  describe the same enforced boundary;
- focused regressions, governance verification, and an independent refute-first review
  show that the gate fires for the failure shapes observed after tempdoc 921;
- no push, PR creation/update, merge-queue mutation, or merge occurs in this session.

## 1. Initial evidence

- Current CI tests the projection and managed-comment tooling, but does not validate
  live pull-request publication state.
- Recent PRs #657 and #660 fail the current validators despite landing; PR #664 was
  likewise mergeable with an overlong title/body, provider banner, review structure,
  and no managed comment.
- The linked footer `Generated with [Claude Code](https://claude.com/claude-code)` is
  accepted by the current raw-text regular expression and reached `main` in PR #663.
- Tempdoc 921 deliberately rejected a write-capable workflow/required check until a
  trusted event, SHA, comment-update, and `merge_group` model was proven. This follow-up
  must not erase that constraint by assertion.

## 2. Design

The publication check belongs at the command that requests the consequential effect.
The repository already has that command substrate in `scripts/dev/run-gh.mjs`, while the
projection and managed-comment validators already share one pure contract library. Extend
those seams rather than add another publication representation:

1. Add an explicit `run-gh.mjs enqueue <pr>` operation. It first executes the existing
   squash preview and managed-review check with inherited output, stops on the first
   failure or spawn anomaly, and only then invokes the ordinary `gh pr merge <pr>` queue
   request. Accept only the PR number and optional repository slug; merge strategy,
   admin-bypass, subject, and body overrides are not part of this operation.
2. Add one shared, synchronous pre-tool hook that recognizes the ordinary shell forms of
   direct `gh pr merge` and the generic `run-gh.mjs pr merge` passthrough. It blocks those
   forms with a route to `run-gh.mjs enqueue`. The hook does not call GitHub itself; the
   executable effect gateway owns the live checks. This keeps hook latency and state near
   zero and makes the same rule project through the existing Claude and Codex adapters.
3. Make provider-residue recognition Markdown-semantic. Inspect visible inline content and
   link destinations outside opaque code and quoted examples, so the current linked Claude
   Code footer is detected without treating a literal example in a fence or blockquote as
   publication metadata.
4. Route both publish skills, the shared branch rule, and canonical agent guidance through
   the enqueue operation. The generic `run-gh` passthrough remains available for read-only
   and non-publication GitHub commands; only its unvalidated merge spelling is superseded.

The operation deliberately preserves tempdoc 921's sole-writer limitation. GitHub exposes
no compare-and-swap precondition for PR body or issue-comment updates, so the validators
and queue request form one short owner-controlled critical section, not an atomic host
transaction. The managed record's head/body fingerprints make stale evidence observable
at final preflight.

### 2.1 Why this is not a hosted required check

A required hosted check would be broader than the immediate autonomous-agent defect, but
the proof gap recorded by tempdoc 921 remains. GitHub documents that a merge-queue-required
Actions check must also run on `merge_group`, whose SHA is the synthetic group commit, and
that `issue_comment` and `pull_request_target` workflows run at the default-branch SHA.
Making comment edits refresh a check on the PR head therefore needs a write-capable custom
check. The `merge_group` payload does not expose a documented pull-request list. Current
queue ref names happen to contain one `pr-N` component, but that is not a published API;
read-only GraphQL probes of two retained failed group commits returned no associated pull
requests. A required check based on either signal would be a brittle new authority.

This tempdoc does not add workflow permissions, a Checks writer, a required context, or a
repository-setting mutation. It also does not claim to prevent a maintainer using the
GitHub UI, an ad-hoc REST/GraphQL mutation, or a deliberately obfuscated shell command.
Those remain host-policy/manual-review paths. The enforced scope is the ordinary CLI path
documented for autonomous JustSearch agents.

Primary platform references:

- https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows
- https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks
- https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target

### 2.2 Supersession and teardown

The raw `gh pr merge <N>` instruction in both publish skills, branch-safety guidance, and
canonical agent documentation is superseded and must be removed in this change. The
generic `run-gh.mjs pr merge` escape hatch is intentionally refused by the same hook.
Nothing from tempdoc 921's rejected custom queue transport or temporary PR-body swap is
revived.

### 2.3 Design reach

General principle: **the smallest repository-owned gateway that performs a consequential
remote effect should also own the last live validation of that effect's repository
invariants**. Prose and CI can explain or independently attest the rule, but neither should
be the only connection between an agent and the effect.

The candidate scope includes release publication, deployment, and baseline-acceptance
commands where a repository already owns an executable gateway. It does not justify a
generic command firewall: each new use needs an observed structural failure and a narrow
effect seam. The principle earns its keep if ordinary direct merge attempts are refused,
the gateway rejects stale/invalid live records, and subsequent agent-authored merges adopt
the managed record. Retire this particular gateway/guard when GitHub offers a repository-
native required check that can provably refresh on PR metadata and comment changes and
validate every PR represented by a merge group, or when JustSearch stops using the CLI
merge path.

## 3. Derisk

### 3.1 Confidence-building investigation

The main risks were false-positive command blocking, a fail-open child-process boundary,
duplicate publication authorities, and assuming a hosted event association that GitHub
does not promise. Investigation found:

- the shared hook manifest already maps Bash/exec commands into one synchronous blocking
  path for Claude and Codex, with pure unit-tested predicates as the established pattern;
- `run-gh.mjs` already owns vector-safe `gh` invocation and publication wait operations,
  so enqueue is a cohesive extension rather than a new wrapper layer;
- `pr-review-record check` already validates the public title/body, exact managed-comment
  cardinality and shape, head SHA, public-body fingerprint, authorship, and session IDs;
  `preview-squash-message` additionally validates the live repository `PR_TITLE` /
  `PR_BODY` squash settings;
- live negative controls on PR #664 fail for its 134-character projected subject,
  2,405-character body, provider/review residue, and missing managed comment, while PR
  #641 passes both validators;
- PR #663 proves the detector defect: its current linked Claude Code footer passes both
  validators even though the managed record exists and the footer landed;
- current branch protection requires the existing hosted lanes and the active ruleset
  requires the squash merge queue, but neither validates PR publication metadata;
- GitHub's documented `merge_group` contract supplies the group SHA/ref, not a PR list.
  Historical failed group commits `c1d3f63b...` and `bb794846...` no longer return any
  `associatedPullRequests`, so that GraphQL relation is not a durable recovery contract.

The command detector should parse only command-start executable shapes per shell segment,
including quoted Windows `gh.exe` paths and PowerShell's call operator, and must carry
negative fixtures for quoted prose, `echo`, `gh pr view`, `gh api`, and the approved
`run-gh.mjs enqueue` form. The enqueue implementation must treat a missing status, signal,
spawn error, or nonzero status from either validator as terminal and prove that `gh pr
merge` was not invoked.

### 3.2 Confidence

Implementation confidence is **8.5/10**. The repository already contains the validators,
gateway, cross-harness hook projection, and test patterns; the work is mainly careful
composition and boundary testing. Residual uncertainty is the inherent no-CAS window and
deliberate bypass surfaces, both of which must remain explicit rather than overclaimed.

Difficulty is moderate. A balanced agentic coding model with high reasoning is sufficient
(`gpt-5.6-terra`, high, from the active catalog); the strongest-capability tier is not
needed unless the shared hook parser reveals incompatible harness command shapes.

## 4. Implementation plan

### P1 — repair semantic provider-residue detection

- [x] Replace the raw provider regular expression in the shared projection library with
  Markdown-token-aware visible-text and link-destination inspection.
- [x] Add fail-before/pass-after fixtures for the linked Claude Code footer, legacy
  session links, alternate link labels, and opaque fenced/inline-code/blockquote examples.
- [x] Confirm the existing clean and historical negative-control fixtures retain their
  current findings.

### P2 — make enqueue the validated effect gateway

- [x] Add `run-gh.mjs enqueue <pr> [--repo owner/repo]` with strict argument parsing.
- [x] Run the live squash preview and managed-review check before the merge request;
  propagate each child's output and fail closed on nonzero status, missing status,
  signal, spawn error, or malformed invocation.
- [x] Invoke exactly `gh pr merge <pr>` plus an optional repository slug only after both
  checks pass. Do not accept merge strategy, admin bypass, title, or body overrides.
- [x] Unit-test exact command order, optional repository propagation, first- and
  second-preflight failure, spawn anomaly, and proof that no merge request occurs after
  any failed preflight.

### P3 — collapse ordinary autonomous merge paths onto the gateway

- [x] Add a narrow shared blocking hook with a pure command predicate for direct `gh pr
  merge` and `run-gh.mjs pr merge` shell forms.
- [x] Cover POSIX and Windows executable spellings, quoted paths, the PowerShell call
  operator, separators, and negative controls for quoted prose, echo, read-only `gh`
  commands, API reads, and `run-gh.mjs enqueue`.
- [x] Register the hook and its bite test in `governance/agent-hooks.v1.json`, regenerate
  Claude/Codex projections, and verify both harness adapters deliver the refusal.

### P4 — update the owned instruction and architecture surfaces

- [x] Replace raw merge instructions in `.agents/skills/publish`,
  `.claude/skills/publish`, `.claude/rules/branch-safety.md`, the canonical agent guide,
  and any maintained quick-reference surface with the validated enqueue command.
- [x] Amend ADR-0045 and its index/probes to state that repository-owned agent CLI merges
  cross the live publication preflight. Preserve the explicit UI/API and no-CAS limits.
- [x] Leave oversized tempdoc 921's dated evidence untouched and record its narrow
  workflow supersession in this tempdoc and ADR-0045 instead.
- [x] Run required docs/skill/hook regeneration and drift checks rather than hand-editing
  generated regions.

### P5 — verification and critical review

- [x] Run focused projection, review-record, run-gh, hook, adapter, hook-manifest, ADR,
  prompt-surface, and documentation checks.
- [x] Run the complete governance and agent-analytics suites, then repository compilation
  and relevant broader tests without overlapping another agent's Gradle build.
- [x] Exercise live read-only positive and negative PR controls. Do not invoke enqueue
  against a real PR in this session because the user excluded publication.
- [x] Run `review-changes` with an independent refute-first check if its instructions
  permit delegation, fix findings, then run `review-tempdoc-fit` and
  `capability-realization` again.
- [x] Commit the reviewed work locally with explicit paths and complete session closeout.
  Do not push, create/update a PR, enqueue, merge, or mutate repository settings.

Bounded independent work exists in the refute-first review and final capability audit.
Implementation itself shares the projection/gateway/hook contract and should remain with
one owner. Delegation is deferred until the review skill is loaded and explicitly allows
it under the active session restriction.

## 5. Implementation and verification

Implemented in the dedicated `codex/933-publication-realization` worktree. No
publication or repository-setting mutation was performed.

### 5.1 Delivered behavior

- `scripts/ci/lib/squash-message-projection.mjs` now recognizes visible provider
  banners and provider/session links across Markdown links and raw HTML. Fenced code,
  inline code, blockquotes, comments, and opaque HTML elements remain examples rather
  than metadata. HTML entities are interpreted as rendered text and only anchor `href`
  attributes count as link destinations.
- `scripts/dev/run-gh.mjs enqueue` accepts an unambiguous positive decimal PR number and
  optional `--repo owner/repo`, runs the live squash preview and managed-review check in
  order, fails closed on every child-process anomaly, and requests the ordinary merge
  queue only after both pass.
- `publication-merge-guard.mjs` blocks ordinary direct `gh pr merge` and generic
  `run-gh.mjs pr merge` shell forms across the maintained Claude and Codex hook
  projections. It covers common GitHub global-flag placements, Windows paths,
  PowerShell invocation, and ordinary Node runtime flags while exempting help and
  non-script evaluation modes.
- Both publish skills, branch-safety guidance, canonical agent documentation, ADR-0045,
  its probe registry, and hosted CI now point at and preserve the same gateway. A new
  tracked-settings generator also closes the stale public Claude hook-projection path
  discovered during implementation.

### 5.2 Verification evidence

- Focused contract tests: `node scripts/ci/test-squash-message-projection.mjs`,
  `node scripts/ci/test-preview-squash-message.mjs`,
  `node scripts/ci/test-pr-review-record.mjs`, `node scripts/dev/run-gh.test.mjs`
  (39 checks), `node scripts/agent-analytics/hooks/publication-merge-guard.test.mjs`,
  and `node scripts/agent-analytics/hooks/codex-hook-adapter.test.mjs` (13 checks) all
  pass.
- Governance and generation: `npm run test:governance` passes all 30 test files;
  `hook-integrity` and `adr-coverage` pass; `gen-agent-hooks.mjs --check`, focused and
  full `regen-all.mjs --check`, Codex-agent parity, skills sync, `llms.txt`, canonical
  links, workflow triggers, always-loaded budget, and the 129-surface prompt inventory
  all pass.
- The complete agent-analytics suite passed 50/50 before the final focused parser
  hardening. A later load-contended run was 48/50 only because the two documented
  wall-clock process-lifecycle fixtures observed a future process-table timestamp;
  `861-w5-agent-spawn-sweep.test.mjs` then passed 16/16 alone and
  `861-w5-remove-worktree-teardown.test.mjs` passed 5/5 alone.
- `./gradlew.bat build -x test --no-parallel` succeeds (252 tasks). Its preceding run
  observed one unrelated LambdaMART latency sample at 7.32 ms versus a 5 ms median
  threshold; the exact `LambdaMartBenchmarkTest` passed alone and the final full build
  passed.
- Live read-only controls: PR #641 passes both validators; PR #663 now fails exactly on
  `public-provider-banner`; PR #664 fails on subject length, body size, provider/review
  residue, and missing managed review record. Exit-code evidence was `0/0`, `1`, and
  `1/1`, respectively.
- The independent refute-first review found and drove fixes for flag-placement and Node
  runtime bypasses, raw-HTML rendering semantics, and ambiguous numeric PR syntax. Its
  final recheck reported no substantive defect within the documented ordinary
  autonomous CLI scope.
- The conceptual `review-tempdoc-fit` pass found and corrected an atomic/universal
  wording overclaim; after correction, the implementation matches the intended design.
  The no-edit `capability-realization` pass traced the detector to live PR controls, the
  gateway to every maintained agent publication instruction, and the refusal through
  both harness projections. It found no material gap; at that stage a real enqueue,
  harness restart, and hosted CI remained publication-phase observations rather than
  locally provable facts. The enqueue and hosted-CI observations are recorded below.

### 5.3 Deliberate design limits

- The validator-to-queue request remains a short sole-writer critical section rather
  than an atomic GitHub compare-and-swap transaction.
- UI merges, direct REST/GraphQL mutations, and deliberately obfuscated or exotic shell
  constructions are outside this repository hook's scope. The merge queue and host
  permissions remain the final authority.
- The validated `enqueue` path was exercised on PR #675. Both live publication-record
  checks passed before the gateway requested the merge queue, and the queued squash
  merge completed successfully.

### 5.4 Publication outcome

- PR [#675](https://github.com/justsearch-app/justsearch/pull/675) merged on
  2026-09-05 as `bc631dc253d0f40a8aa2eaebdacb39150eb06864`. The landed tree is
  byte-equivalent to the reviewed branch candidate, and the durable squash message
  contains the concise public rationale plus the session identifier without review
  residue.
- The first hosted PR run exposed an honest diff-gate failure: adding a supersession
  note grew already-oversized tempdoc 921. The fix left that historical tempdoc
  untouched, recorded supersession here and in ADR-0045, and passed the actual
  `check-tempdoc-size.mjs --base origin/main` gate before republishing.
- All required PR lanes passed. Merge-group CI run `33960726253` and post-merge `main`
  push CI run `33960990293` both succeeded against the landed commit.
