---
title: "UI information architecture needs clearer task separation"
type: tempdocs
status: open
created: 2026-06-18
author: user walkthrough notes, filed by agent
category: frontend / ux / information-architecture / surfaces / taxonomy
related:
  - surface-altitude-governing-axis
  - window-taxonomy-convergence
  - search-and-agent-window-convergence
  - surface-tab-state-preservation
  - chat-composer-add-action-entrypoint
  - activity-feed-non-user-action-semantics
  - notification-model-unification
  - frontend-presentation-kernel
---

# 614 - UI information architecture needs clearer task separation

> What this document is. A short problem statement from a user walkthrough. It records a broad product
> concern that may either become its own design pass or route into existing taxonomy work. It deliberately
> does not prescribe implementation.

## Problem

The UI needs clearer task separation at the user-facing information-architecture level. JustSearch already
has a major taxonomy substrate in `window-taxonomy-convergence`: one task, one window; facet-windows fold
into task homes; altitude distinctions such as PRODUCT, DIAGNOSTIC, and TRUST must remain legible. The
remaining walkthrough concern is that the current product can still feel blurry even after those structural
moves, because labels, entry points, tabs, drawers, notifications, Activity, document workflows, and
structured-response affordances do not always teach the same mental model.

This is therefore a coherence problem over the existing taxonomy, not a new taxonomy theory. The question
is whether the app consistently communicates where each task belongs: finding and asking in the work
surface; corpus management in Library; system health, background behavior, and audit/trust in the System
and Activity family; configuration and appearance in Settings; and chat-adjacent contribution/output tools
such as documents and structured responses in the interaction flow.

## Why it matters

Weak separation makes the app harder to learn and harder to return to. A user should be able to predict
where a task starts, where its supporting controls live, and where its consequences can be reviewed. When
that grammar is unclear, individual UI fixes do not add up to a coherent product: a better notification,
Activity entry, tab, or chat affordance may still feel misplaced if it teaches a different home for the
same task.

The issue is especially important because several adjacent surfaces are converging. Search, Documents,
Structured responses, and Agent work already share the one interaction-window direction; Library and
Browse are corpus facets; System, Health, Logs, and Activity are observability/trust facets. The user-facing
IA has to make those relationships obvious without collapsing meaningful differences such as transient
search state versus durable conversation history, or diagnostic health versus audit/trust history.

## Boundary

This is strongly adjacent to the `window-taxonomy-convergence` tempdoc and should not duplicate it. That
tempdoc owns the rail/window taxonomy and the host/member composition history. This tempdoc only preserves
the new walkthrough-level observation that logical separation remains a user issue even when the structural
taxonomy is mostly known.

Concrete sub-problems should route to narrower docs where they already exist: tab/task-state persistence to
`surface-tab-state-preservation`; chat contribution and output entry points to
`chat-composer-add-action-entrypoint`; Activity inclusion semantics to
`activity-feed-non-user-action-semantics`; notification routing to `notification-model-unification`; and
search/ask/document/agent convergence to `search-and-agent-window-convergence`.

Later design work should decide whether this becomes a focused IA audit over the shipped taxonomy, or an
umbrella that only coordinates narrower docs. This document only opens the cross-surface mental-model
concern.
