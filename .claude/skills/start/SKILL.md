---
description: Orient to the codebase and verify session attribution
context: fork
agent: Explore
---

## 1. Verify session attribution

Read `tmp/agent-telemetry/current-session-id` and report the session ID.
This file is written automatically by the SessionStart hook. If it exists,
workflow attribution is active — no manual export needed.

If the file is missing, tell the user: "Session ID file not found —
the SessionStart hook may not have fired. Workflow runs will be unattributed."

## 2. Understand the app

Read @docs/llms.txt and @docs/explanation/01-system-overview.md to understand the app we are building in this repo.

Summarize:
- What the app does
- Key architecture decisions
- Main components and how they interact
