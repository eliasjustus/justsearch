---
title: "Frontend kernel — shell store & layout"
type: reference
status: stable
description: "The shell store layout and state ownership."
date: 2026-06-09
---

# Frontend kernel — shell store & layout

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft's `10-kernel/` set (authored ~2026-05; the rewrite shipped per tempdoc 563). References to
> the draft's removed planning material (`slices/`, `20-systems/`, `archive/`, …) are historical.
> ADR links point to `docs/decisions/`; sibling kernel docs are in this folder.


The shell store is the frontend single source of truth for framework data after
handshake.

## Store Ownership

The shell store owns:

- primitive registries
- capability state
- resource snapshots and stream state
- operation invocation state
- plugin metadata and lifecycle state
- user layout and presentation preferences
- local diagnostics buffer

Product surfaces may own ephemeral UI state:

- focused item
- open panel
- scroll position
- transient form draft
- local selection before commit

They may not own backend readiness, operation availability, runtime mode, or
global capability truth.

## Registry Consumption

Surfaces consume framework data through typed selectors, not ad hoc fetches.

The allowed pattern is:

- shell loads and validates registry/resource data
- surface selects data through a typed consumer
- surface renders or invokes through kernel-provided actions

The forbidden pattern is:

- each surface re-fetches registry data
- each surface interprets backend capability fields itself
- each surface has a private fallback model for backend-owned state

## Layout Persistence

Layout persistence stores presentation state, not product truth.

Examples:

- panel sizes
- docked/undocked view state
- density
- hidden optional widgets
- selected theme
- recent command list

Layout files require:

- schema version
- shell version that wrote them
- plugin state namespaces
- safe defaults
- import/export path
- downgrade handling

## Cache Policy

Framework caches must expose staleness.

Cache categories:

- immutable build assets
- i18n catalogs
- primitive registries
- resource snapshots
- plugin bundles
- layout/settings documents

Stale data may be useful, but stale data must not be visually indistinguishable
from current truth.

