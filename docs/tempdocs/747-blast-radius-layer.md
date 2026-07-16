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

### Disposition

This scoped-first-step tempdoc is **investigation-complete**; its concrete remediations are
either founder-gated (credentials, deny-rule application) or belong to an existing gate
(`check-workflow-triggers` extension). Recommend it stays open pending the founder's calls
above rather than shipping code this session — there is no safe-to-auto-apply code change
here that doesn't touch the founder's credential posture or local config.
