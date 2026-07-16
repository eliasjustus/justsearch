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

## Findings

(populated by the audit below)
