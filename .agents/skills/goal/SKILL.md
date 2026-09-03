---
name: goal
description: >-
  Create or continue one native Codex persisted goal with a concrete,
  verifiable completion condition. Rare — run manually only.
---
# Persisted Goal

Use Codex's native goal capability. Explicitly invoking this skill authorizes
creating a goal for the requested work; it does not authorize merging,
publishing, destructive operations, or an unrelated expansion of scope.

1. Call `get_goal` before creating another. If the existing unfinished
   goal is the same objective, continue it. If it conflicts, surface the conflict
   instead of replacing it.
2. Derive one concise objective from the active tempdoc or agreed plan. Describe
   a concrete end state, not a sequence of implementation steps.
3. Make the result verifiable. Require the relevant tests and, for
   user-visible behavior, successful live/browser verification with concrete
   evidence in the final report.
4. Call `create_goal` with that objective. Set a token budget only when the user
   explicitly requested one.
5. Work until the objective is genuinely achieved. Call `update_goal` with
   `complete` only when no required work remains; use `blocked` only under the
   native goal tool's blocking rules.

Use a separate worktree when implementation would otherwise modify `main`.
Prefer structural fixes over short-term workarounds. If another agent owns the
development stack, continue all work that does not require the stack. If final
live verification remains unavailable, do not report success: state plainly
which condition remains unmet and what ownership change is required.
