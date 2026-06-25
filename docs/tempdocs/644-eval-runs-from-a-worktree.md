---
title: "Make stack-bound eval run correctly from an agent worktree: today the eval harness auto-discovers the reranker (and CE-on/dense engine) from the worktree's own repo root, where the LFS model files don't resolve → the cross-encoder silently turns off → wrong hybrid numbers; so all live eval is forced onto the shared main checkout. Make the model/reranker resolution worktree-aware (resolve from the main checkout, as JUSTSEARCH_MODELS_DIR already does) so a parallel agent can run eval in isolation. STUB / idea-only."
type: tempdocs
status: proposed — STUB / idea-only
created: 2026-06-24
author: agent analysis — split out of tempdoc 635 §workflow-lessons #4 (the constraint that forces eval onto main)
related:
  - 635-contamination-resistant-eval-corpus      # where the constraint was found + recorded (workflow-lessons #4); a heavy live-eval consumer
  - ../../.claude/rules/branch-safety.md          # the worktree model this would make eval-compatible
---

> **STUB / idea-only.** Captures the problem + why it matters; **no implementation specifics** (which
> resolution path, env var, or gradle property to change — that's the design phase). Origin: tempdoc 635's
> repeated need to run live eval, which it could only do on the shared `main` checkout.

# 644 — Make stack-bound eval run correctly from a worktree

## The idea
Parallel agents work in isolated git worktrees, but **stack-bound eval** (`corpus-fidelity`, `jseval run`
with cross-encoder + dense) effectively **cannot run in one today**. The harness auto-discovers the reranker
from the *worktree's own* repo root (`justsearch.repo.root → models/onnx/reranker`); a worktree does not carry
the LFS `models/` tree, so the reranker isn't found, the cross-encoder **silently turns off**, and the hybrid
numbers come out wrong (looking like a quality result, not a missing model). The idea: make the model /
reranker resolution **worktree-aware** — resolve from the **main checkout** (exactly as the dev-runner already
resolves `JUSTSEARCH_MODELS_DIR`) so an agent in a worktree gets the real default-on engine + reranker.

## The purpose / why it matters
- **It removes the forcing-function that pushes eval work onto shared `main`.** Because eval must run on main,
  eval-bearing tempdocs edit shared files on main, which is the *root* of the recurring multi-agent collision
  risk and per-commit coordination cost (635 §workflow-lessons #4/#5 — the `cli.py` hotspot near-miss was a
  direct downstream consequence). Fix this and eval-bound work can be isolated in a worktree like everything
  else.
- **It is a silent-wrong-result trap, not just an inconvenience.** A worktree eval run *succeeds* but with CE
  off → plausible-but-wrong hybrid numbers. An agent who doesn't know the constraint can publish a wrong
  result. Worktree-aware resolution (or, failing that, a loud refuse-if-reranker-absent) closes the trap.

## Scope boundary (for the design phase, not decided here)
Out of scope for this stub: *which* resolution mechanism (a worktree→main fallback in the gradle
`justsearch.repo.root` property, an env override, a symlink/junction at prepare-worktree time, or a
fail-loud guard), and whether GPU/`cuda12` variants are in scope. This file records only the idea + its
purpose; the design picks the mechanism. Until then, the operational rule stands (635 §workflow-lessons #4):
run live eval on the **main** checkout.
