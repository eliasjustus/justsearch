---
title: "Chat composer needs an extensible add-action entry point"
type: tempdocs
status: open
created: 2026-06-18
author: user walkthrough notes, filed by agent
category: frontend / ux / chat / composer / extensibility
related:
  - chat-composer-extraction
  - extension-substrate-projection-and-delivery
  - plugin-ecosystem-substrate
  - llm-interaction-correct-structure
  - search-and-agent-window-convergence
  - search-window-competitive-uiux-research
  - extension-substrate
---

# 611 - Chat composer needs an extensible add-action entry point

> What this document is. A short problem statement from a user walkthrough. It records the idea for a
> future design pass only; it deliberately does not prescribe implementation.

## Problem

The chat composer needs a clear entry point, such as a plus action, for additional chat capabilities.
Likely candidates include adding documents, choosing structured-response modes, inserting templates, or
opening related context tools without crowding the main input.

This should be treated as a next-turn contribution entry point, not as a hardcoded drawer of miscellaneous
chat features. Prior composer work established `<jf-composer>` as a narrow shared input primitive, and
the extension substrate establishes that discoverable capabilities should be declared, composed, and
consumed through registries rather than hand-authored per surface. The add action should therefore be
evaluated as a consumer of contribution-backed actions, templates, context additions, and mode helpers.

## Why it matters

As chat gains capabilities, placing every action directly in the composer will make the primary input
noisy and hard to scan. Hiding capabilities elsewhere makes them undiscoverable. A single add-action
entry point can give secondary capabilities a predictable home.

## Boundary

This is not the same as the `chat-conversation-control-primitives` tempdoc. That tempdoc is about editing
or navigating conversation history. This one is about how the user introduces new material or mode
choices into the next turn.

The plus entry point should not absorb every affordance just because it is secondary. It is a home for
adding material or constraints to the next turn: documents, selected context, prompt/template inserts,
schema helpers, structured-output setup, or contribution-backed context tools. Conversation history
controls such as copy, edit, branch, retry, rewind, and compaction belong to the transcript control layer.

Nor should it hide safety-significant crossings. The one-input interaction model keeps Search as the base
tier, Ask/Documents as an escalation, Structured as distinct because it needs schema input, and Agent as
explicit because it can act. The add action may help prepare or configure those crossings, but it should
not make Agent delegation or live run controls feel like incidental menu items.

Later design work should decide what belongs behind the add action, what remains visible in the composer,
and how documents or structured responses relate to existing interaction tiers. This document only opens
the entry-point problem.
