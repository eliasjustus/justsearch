---
title: "506 — Horizon 3: Ecosystem"
---

# 506 — Horizon 3: Ecosystem

**Date**: 2026-05-17
**Status**: draft
**Predecessor**: 504 §Long-term — Horizon 3 (extracted)
**Related**: 486 (consumer feature inventory), 421 (framework kernel),
505 (Horizon 2)
**Horizon**: ≈ 12+ months out

---

## Premise

The framework's substrate axes — `Audience`, `riskTier`, `executors`,
`Operation` registry, `Resource` categories, trust lattice, citation
substrate, recovery cross-link — are not just internal abstractions.
They are **the same axes other AI tools, plugin authors, and
multi-account / multi-corpus systems need**. The framework is
pre-wired for a much larger surface than the current single-process
product.

Horizon 3 is the *ecosystem* horizon: JustSearch as a citizen of a
broader AI-tool / plugin / multi-corpus / agentic-OS world. Each
item below extends substrate the framework has already built or has
already roadmapped in 486.

Horizon 3 is downstream of both Horizons 1 and 2. Premature ecosystem
work multiplies the surface area of any inconsistency below.

---

## The thesis

Horizon 1 makes JustSearch internally coherent. Horizon 2 makes it
*compositional*. Horizon 3 makes it **federated** — JustSearch is no
longer the boundary of the user's knowledge system; it is one node
in a larger one.

Two framework facts make this possible without rewriting:

- **The Operation registry is already protocol-shaped.** Operations
  carry `audience`, `executors: {UI, AGENT, CLI}`, `riskTier`,
  `confirmStrategy`. MCP 2025-11-25 elicitation maps mechanically
  onto `INLINE_CONFIRM` / `TYPED_CONFIRM`. Exposing the registry to
  external AI tools is wiring, not redesign.
- **Trust + audience axes already gate visibility.** The same axes
  that hide OPERATOR Operations from agents in 504 today gate
  per-corpus, per-plugin, per-account visibility tomorrow.

---

## Items

### H3-1 — MCP exposure (486 F8)

JustSearch becomes a knowledge substrate that Claude, Cursor, and
other AI tools mount via MCP. The Operation registry, the Resource
catalog, and the chat shapes are the surfaces that map to MCP
tools / resources / prompts.

The trust lattice's `INLINE_CONFIRM` / `TYPED_CONFIRM` cells already
align with MCP 2025-11-25 elicitation — the protocol asks for user
confirmation in the same way the local UI does. Implementation is
substrate-light; the work is in the binding layer + an opt-in
configuration story.

This is the **strategic** Horizon 3 item. It changes JustSearch's
positioning from "a local search product" to "a local knowledge
substrate that other tools mount". The local-first brand is preserved
— data never leaves the machine; only typed capability bindings are
exposed.

### H3-2 — Persistent agent advisor (486 F23 + G143)

LLM condition-observer loop that emits advisories into the
`core.advisory-agent-observation` Resource (slice 490 substrate
shipped). Renders via existing inbox + a new `<jf-advisor-panel>`
side-panel surface. Pairs with 486 G143 (conversation memory +
dreaming) for an agent with a sense of "what's going on lately".

Differs from the current agent surface in that it's *proactive* —
the agent surfaces observations the user didn't ask for. Defaults
off, rate-limited, opt-in per class.

### H3-3 — Multi-corpus / multi-account (486 G109)

Library switcher: work / personal / project-X. Per-corpus
permissions. Federated search across local corpora. The watched-root
model already supports multi-source; multi-corpus is the namespacing
+ visibility model on top.

Naturally extends Horizon 2's workspace primitive — each workspace
binds to a corpus context.

### H3-4 — Predictive health

Today: Health surface reports current conditions (open, recoverable,
resolved). Tomorrow: it **forecasts** conditions — "disk filling, 3d
to full", "embed-queue trending up, likely backlog by tomorrow",
"GPU memory pressure rising". Bridges 486 G158 (recovery
cross-link) with F21 (LLM explain-condition) and F23 (advisor).

The TIMESERIES Resource Category already exists; the missing piece
is the trend-analysis layer that converts samples to forecast events.

### H3-5 — Plugin marketplace (486 G142 FE)

Backend tier-compat validator is shipped. Frontend
`mergePluginShapeContributions` deferred until a plugin needs one
(486 §G142). At Horizon 3 scale, plugins contribute Operations +
shapes + renderers + themes. The audience-gated registry is the
filter layer.

Plugins are a **discipline test** for the entire framework: every
metadata axis that should gate plugins is one a plugin author will
exploit. Doubles as a forcing function for D3 (metadata not
surfaced).

### H3-6 — Visual identity + accessibility

Theme catalog has dead refs today (504 F-16: `core.nord.json` /
`core.sepia-focus.json`). At V1.0 scale, a real visual identity is
worth the spend:

- Density modes (Settings already has the toggle; needs a real
  design system behind it).
- Iconography system (Lucide is used today; needs a real catalog +
  customization story).
- Accessibility audit (WCAG AA at minimum; shadow-DOM screen-reader
  testing; high-contrast mode real verification — 504 didn't probe
  it live).
- Motion + interaction language (consistent timing curves, focus
  states, micro-interactions).

Lowest *priority* of the Horizon 3 items but probably highest
*visibility* once shipped.

---

## Cross-horizon discipline (referenced from 504)

The five disciplines from 504 §Long-term hold in Horizon 3 with one
addition specific to ecosystem work:

1. Substrate-to-surface latency.
2. Surface metadata, don't hide it.
3. First-class empty / degraded states.
4. One name per capability.
5. LLM + structured workspace (the middle path).
6. **(H3-specific)** Federation honors the local-first brand. Every
   item that exposes data outside the process is opt-in, per-corpus,
   with explicit user gesture. No silent egress, no default-on
   sharing.

---

## Risks

- **AI-everywhere drift.** Each LLM-mediated H3 item (H3-1 MCP,
  H3-2 advisor, H3-4 forecast) is a vector for "AI is doing
  something behind my back". Local-first means defaults off,
  observer loops rate-limited, MCP exposure opt-in per corpus.
- **Plugin trust model.** Marketplace plugins are the largest
  third-party trust surface JustSearch will have. Plugin tier
  validation (already in framework) is necessary but not sufficient
  — a sandboxing thesis is needed before plugin code runs.
- **Forecast UX is hard.** Predictive health (H3-4) easily becomes
  alarmist or wrong. Calibration + suppression rules + user
  feedback loop are part of the work, not an afterthought.
- **MCP protocol evolution.** MCP is itself evolving. H3-1 binds
  JustSearch to an external protocol version — versioning + upgrade
  story matter.
- **Multi-corpus state explosion.** H3-3 multiplies every workspace,
  every pinned search, every saved view by N corpora. Needs a
  thoughtful per-corpus default model.

---

## Next-step gates

Same convention as 505: this document doesn't unlock work. Items open
only when:

| Gate | Required before |
|---|---|
| Horizons 1 + 2 mostly executed | any H3 item |
| Local-first / privacy thesis re-stated for federation | H3-1, H3-2, H3-3 |
| Plugin sandboxing thesis | H3-5 |
| Forecast calibration + suppression model | H3-4 |
| MCP version-binding strategy | H3-1 |

A dedicated slice spawns from H3 when the gate is met *and* a
specific item is user-ratified. This document is **not** the
ratification.

---

## What this is not

- **Not a feature list.** Each item is a *direction* with a single
  paragraph of intent. Real slices will have their own substrate
  audits, consumer specs, and Pass-8 verification.
- **Not a competitive-positioning document.** The "JustSearch as
  knowledge substrate" framing (H3-1) is a product *opportunity*
  the substrate makes available; whether it becomes a strategic
  bet is outside the audit's scope.
- **Not load-bearing for V1.0.** Horizon 3 is post-V1 work by
  construction. Treat as a forecast of what the framework lets
  the team build, not a roadmap of what gets built.
