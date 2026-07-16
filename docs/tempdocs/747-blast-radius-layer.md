---
title: "747 — Blast-radius layer 2, scoped first step: credential inventory + least-privilege + deny-rule gap audit (743 P-D)"
type: tempdocs
status: "open — implementation started 2026-07-16 (session f7580e17); founder-approved via 743's disposition record (P-D, scoped first step)"
created: 2026-07-16
author: agent session f7580e17 (Fable 5)
category: agent-process / enforcement / safety
related:
  - 743 (parent program — P-D evidence: R3's 2026 incident record; the failure mode shifted from blocked-command-shaped to blast-radius-shaped; explicitly a CONSCIOUS principle-5 override on rare-event-safety grounds, per Phase-4 review)
  - .claude/rules/tier-register.md (this adds a second enforcement layer under the hook layer)
---

# 747 — Blast-radius layer 2 (P-D, scoped first step)

## Charter

Add the containment layer the hook layer doesn't provide. R3's evidence: 2026's worst
agent incidents (including frontier-model production-DB deletion via an overprivileged
token, April 2026) were not blocked-command failures but reach failures — the agent acted
in good faith with more privilege than anyone realized it had. The 2026 industry consensus
is layered (command hooks + OS/credential containment); this repo has layer 1 only.
Additive safety: nothing existing is weakened (D-2 untouched).

**Scoped first step (this tempdoc):**
1. **Credential inventory** — every credential/token reachable from an agent session on
   this machine (gh token + its scopes, .env files, MCP server configs, OTel settings,
   keychain-adjacent files), with reach (what could an agent do with it) and least-privilege
   recommendation. Inventory records NAMES/SCOPES/LOCATIONS only — never values.
2. **Deny-rule gap audit** — R4 found `Read` deny rules also block `Edit` (≥v2.1.208) but
   NOT `Write`/`NotebookEdit`; audit `.claude/settings*.json` deny rules for paths protected
   against reading but writable, and close the gaps.
3. **Least-privilege actions** — implement what's safe locally (deny-rule additions);
   escalate what requires founder action (e.g., re-scoping the gh token is a founder
   operation on their account).

**Deferred to a later step (recorded, not started):** sandbox/devcontainer trial for
unattended overnight runs (per disposition: trials on the next 707-style campaign).

## Findings (2026-07-16 audit)

**Credential-specific inventory (token kinds, scopes, exact locations, reach ranking) was
delivered to the founder out-of-band in session chat and is deliberately NOT recorded here** —
this tempdoc is public-bound (ADR-0045), and a map of where secrets sit on the founder's
machine is exactly what shouldn't be published, even without values. The structural /
config-hygiene findings that are safe to record:

1. **Permission layer is effectively open.** `.claude/settings.json` has no `permissions`
   key; `.claude/settings.local.json` (gitignored, local) has empty `allow`/`deny`/`ask`;
   global `~/.claude/settings.json` runs `defaultMode: "bypassPermissions"`. So the
   deny-rule gap the audit was sent to find (Read-deny-doesn't-cover-Write, v2.1.208) is
   moot in the narrow sense — there are no deny rules at all — but the STRUCTURAL gap is
   larger: the hook layer (git/build-focused) is the only filesystem backstop. This is the
   layer-2 hole P-D exists to start closing.
2. **Proposed deny-rule additions** (additive safety; to `.claude/settings.local.json`, the
   local gitignored file — NOT auto-applied, see escalation): deny Read+Write+NotebookEdit on
   `.mcp.json`, `.env*`, `**/*.pem`, `**/*credentials*`. Caveat carried from the audit: the
   permission-glob dialect's handling of absolute out-of-project paths (`F:/...`) is
   unverified against current docs — confirm before relying on it for anything outside the
   repo tree; out-of-tree secrets may need OS ACLs, not settings.json.
3. **Self-hosted Actions runner** (sibling of the repo, registered on the PUBLIC repo) is
   contained TODAY only because its one workflow is `workflow_dispatch`-only. Recommended
   standing guard: a CI check failing the build if any self-hosted job's `on:` ever gains
   `pull_request`/`pull_request_target`/`issue_comment` (would become RCE-from-any-public-PR
   on the founder's machine). The repo already has `check-workflow-triggers` in the pre-merge
   table — extending it is the natural home.

### Escalated to founder (require founder action — this session did NOT act on them)

- **A plaintext credential lives in a local gitignored config** — should move to an
  env-var/keyring reference; and a `gh` token is broader-scoped than day-to-day agent work
  needs. Both are the founder's account/machine to remediate. Details in chat.
- **Whether to apply the proposed deny rules** — they touch the founder's local config in a
  security-sensitive way with an unverified glob dialect; presented for approval, not
  auto-applied.

### Deferred (recorded, not started)

Sandbox/devcontainer trial for unattended overnight runs — next 707-style campaign.

### Implemented this session — self-hosted-runner trigger guard

`scripts/ci/check-workflow-triggers.mjs` extended (rides existing CI wiring — same
script already in the pre-merge table): a **policy-independent HARD invariant** —
`SELF_HOSTED_FORBIDDEN_TRIGGERS` (`pull_request`, `pull_request_target`,
`pull_request_review`, `pull_request_review_comment`, `issue_comment`) fails the build if
any workflow with a self-hosted `runs-on:` carries one. Independence from the per-workflow
`expectedTriggers` allowlist is the point: editing the workflow AND the policy together
(which satisfies the existing allowlist match) still fails here.

`usesSelfHostedRunner()` is **fail-closed via a hosted-label allowlist** (post-review
redesign): a `runs-on` value is self-hosted unless every label is a known GitHub-hosted
label (`ubuntu-*`/`windows-*`/`macos-*`, incl. versioned + `-arm`) or an unresolvable
`${{ }}` expression. This closes two evasions an independent review reproduced against the
first (token-only) draft: **A1** custom-label-only targeting (`runs-on: justsearch-perf` —
the repo's OWN runner label, no literal `self-hosted` token) and **A2** the runner-group /
block-`labels:` mapping form. Both now caught (regression tests + live re-probe). Comment
mentions still don't false-positive; hosted arm/versioned runners pass; real-repo run stays
green (`docs-lint.yml` = self-hosted + dispatch-only).

**Sole remaining documented limit (the one place it does NOT fail closed, because it
genuinely can't resolve the value):** a self-hosted runner reached via a `runs-on:
${{ matrix.os }}` expression — no such usage exists today (`ci.yml`'s only matrix `runs-on`
is ubuntu/windows-latest). If a matrix ever includes a self-hosted value, this needs a
matrix-aware follow-up.

### Disposition

This scoped-first-step tempdoc's one safe-to-auto-apply remediation — the
self-hosted-runner trigger guard — is **implemented and shipping this session**. The
remaining remediations are founder-gated (credential rotation/re-scoping, deny-rule
application) and recorded above as escalations. Tempdoc stays open pending those founder
calls; the sandbox/devcontainer half is deferred to the next overnight campaign.
