---
title: Agent Discipline Rules — CLAUDE.md Improvements
status: done
created: 2026-02-18
---

# Agent Discipline Rules

Research and implementation of CLAUDE.md rules to address three observed agent behavioral failures.

## Problems Observed

1. **No codebase exploration before implementing.** Agents write new code from scratch without checking for existing utilities, helpers, and patterns. Leads to duplicated infrastructure across modules.
2. **Premature tempdoc closure.** Agents declare remaining tempdoc items "not worth it" or "too difficult" and attempt to close work early, even though the user has already deemed the work necessary.
3. **Focus drift.** When asked "what next?", agents propose entirely new work streams instead of consulting the active tempdoc for remaining items.

## Research

External sources consulted:

- [Anthropic: Claude Code Best Practices](https://code.claude.com/docs/en/best-practices) — "Explore first, then plan, then code" workflow; "address the root cause, don't suppress the error"; verification as highest-leverage practice.
- [GitHub Issue #6159](https://github.com/anthropics/claude-code/issues/6159) — Agent stops mid-task, leaves own todo list incomplete. Phases 4-6 unchecked.
- [GitHub Issue #25397](https://github.com/anthropics/claude-code/issues/25397) — User created 38 rules to combat premature completion. Impulse signatures: "I'll also...", "While I'm at it...". Root cause: training toward helpfulness encourages declaring completion.
- [Addy Osmani: The 80% Problem](https://addyo.substack.com/p/the-80-problem-in-agentic-coding) — Agents scaffold well but fail the final 20% requiring deep understanding.
- [Pete Hodgson: Why Your AI Coding Assistant Keeps Doing It Wrong](https://blog.thepete.net/blog/2025/05/22/why-your-ai-coding-assistant-keeps-doing-it-wrong-and-how-to-fix-it/) — "New-hire problem": agents don't know about existing infrastructure.
- [GitClear analysis](https://medium.com/@anoopm75/the-uncomfortable-truth-about-ai-coding-tools-what-reddit-developers-are-really-saying-f04539af1e12) — Duplicated code blocks increased 8x in 2024 due to AI agents.
- [IEEE Spectrum](https://spectrum.ieee.org/ai-coding-degrades) — 43% of AI patches fixed the primary issue but introduced new failures.
- [Codeaholicguy: Tokens and Context Windows](https://codeaholicguy.com/2026/02/14/tokens-context-windows-and-why-your-ai-agent-feels-stupid-sometimes/) — Attention dilution causes goal drift in long sessions.
- [HumanLayer: Writing a good CLAUDE.md](https://www.humanlayer.dev/blog/writing-a-good-claude-md) — Progressive disclosure, keep under 300 lines, minimize distraction.

## Gap Analysis

After research, identified five missing rules ranked by impact:

| Gap | Impact | Description |
|-----|--------|-------------|
| Fix root causes, not symptoms | High | No rule about what to do when builds/tests fail. Agents paper over errors. |
| Don't degrade existing code | High | No rule against removing tests, weakening assertions, deleting error handling. |
| Verify before declaring done | Medium-high | Build commands listed as reference but not mandated as behavioral requirement. |
| Ask when uncertain | Medium | No guidance on when to ask vs. assume for ambiguous requirements. |
| Scope of changes | Medium | Task-level focus covered but not code-level scope (noisy diffs from unrelated changes). |

## Implementation

All changes applied to `CLAUDE.md` under the `## Agent Discipline` section. Six subsections in final order:

1. **Explore Before Implementing** — pre-existing, unchanged.
2. **Fix Root Causes, Not Symptoms** — new. Explicit banned behaviors (commenting out code, weakening assertions, deleting tests, adding suppressions, broadening catches, removing validation). Covers gaps 1+2.
3. **Verify Your Work** — new. Mandates compilation + module tests before moving on. References Verification Workflow for commands. Covers gap 3.
4. **Tempdoc Is Your Contract** — pre-existing, unchanged.
5. **Stay Focused on Your Assigned Work** — expanded with "keep diffs minimal" bullet covering code-level scope (gap 5).
6. **Ask When Uncertain** — new. Covers ambiguous requirements, architectural choices, cross-module impact (gap 4).
