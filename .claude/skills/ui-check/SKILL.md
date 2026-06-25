---
description: "TRIGGER when: editing the Lit `shell-v0` frontend (`modules/ui-web/src/shell-v0/**`), modifying UI layout/styles, capturing UI screenshots, or doing visual/measurement verification of frontend changes. Loads the ui-shot/ui-check reference: the measurement companion (`.measure.json`), the live-shell-v0 step registry, server/backend requirements, the coverage gate, and worktree auto-serve."
user-invocable: true
---

# UI Check & Visual + Measurement Feedback

`jseval ui-shot <step>` captures the **live Lit `shell-v0` UI** and writes a PNG **plus** a structured
`<step>.measure.json` fact-sheet (tempdoc 615). The thesis is **measurement over vision**: judge correctness
from the *facts* (accessibility tree, axe violations, geometry, console), and use the PNG only for the overall
look.

> The harness drives the REAL Lit app — NOT the retired React stack. There is **no mock-data demo mode**
> anymore: view/chrome steps render without a backend (the tool auto-serves a Vite), but anything with data —
> search, inspector, AI/citation — needs the **dev stack running** (and AI steps need `ai_activate`).

## Quick reference

```bash
jseval ui-shot home              # capture one step → prints PNG path + a one-line MEASURE fact summary
jseval ui-shot search-results
jseval ui-shot citation-highlight
jseval ui-shot --list            # list all steps
jseval ui-shot --affected modules/ui-web/src/shell-v0/views/SearchSurface.ts
jseval ui-check                  # batch-capture all steps (~60s+), diff vs baseline
```

The CLI prints the measurement facts after each capture, e.g.:
```
tmp/ui-shot/home.png
  measure: tmp/ui-shot/home.measure.json
  a11y 63 landmarks · geometry 7 els · axe 1 violations (1 serious) · console 0 errors · overflow none
```
**Read the `.measure.json` for a correctness judgment from facts; `Read` the PNG only for gestalt.** A PNG eats
~1–2k tokens of context; the fact summary is cheaper and more reliable for "is this right."

### The measurement companion (`<step>.measure.json`) — tempdoc 615 §6.2
Default-on (`--no-measure` to skip). Captured from the same page the screenshot comes from:
- **a11y_landmarks** — shadow-piercing accessibility roles/labels/headings (the live perception channel; the
  native `page.accessibility.snapshot()` returns None on this shadow-DOM app, so a deep `shadowRoot` walk is used)
- **axe** — WCAG violations (the same axe-core bundle the e2e harness ships)
- **geometry** — bounding rects + key computed styles for landmarks/stage/rail/inspector + document overflow flags
- **console_errors** — console.error / pageerror collected over the step

### Common options
| Flag | Default | Notes |
|---|---|---|
| `--ui-url` | `http://localhost:5173` | a **non-default** value (e.g. `http://127.0.0.1:5173`) bypasses worktree auto-serve and hits that server directly — use this to target a running dev stack |
| `--no-measure` | off | skip the measurement companion (PNG only) |
| `--timeout-ms` | 30000 | per-step timeout; AI/citation steps need ~200000–320000 (model latency) |
| `--cooldown-ms` | 250 | settle time before each screenshot |
| `--output-dir` | `tmp/ui-shot/` | |

(`--no-demo` is legacy — the `?demo=true` mock-data branch is inert; data always comes from the live backend.)

## The six instruments — which verb when (tempdoc 615 §11)

`ui-shot` is the one you reach for most, but it is one of **six** verbs. Each answers a different question;
the rot they were built to avoid is being forgotten (§26 discoverability). Pick by the question you have:

| Question you have | Verb | Needs | Output / exit |
|---|---|---|---|
| "Show me / let me verify ONE surface" | `jseval ui-shot <step>` (+`.measure.json`) | served FE (auto-serve); dev stack for data/AI steps | PNG + measure.json + 1-line fact summary |
| "Did I break a11y closure anywhere?" | `jseval ui-a11y-gate` | auto-serve (`--fixtures`) | exit 0 clean · 1 a NEW axe violation vs `governance/ui-a11y-baseline.v1.json` · 2 capture error |
| "Did this change MOVE/REMOVE anything I didn't intend?" | `jseval ui-diff <before.measure.json> <after.measure.json>` | two captures (shoot before, edit, shoot after) | semantic changelog (landmark removed · element moved/resized >4px · new axe rule · overflow flip · real console); exit 0 same · 1 changed |
| "Critique this surface against THIS product's design system" | `jseval ui-critic <step>` | auto-serve (`--fixtures`) | prints a GROUNDED critique **prompt** (facts + `design-reference.v1.json` + rubric) — feed it to a model / `agent_chat` |
| "Hunt edge-state bugs a human won't patiently click" | `jseval ui-fuzz` | auto-serve (`--fixtures`); ~80s | fuzzes search × {data-variant × viewport × theme}, flags anomalous cells; exit 0 clean · 1 flagged |
| "Trace the interaction trajectory into a step" | `jseval ui-shot <step> --trace` | served FE | per-step trace of the chain leading to `<step>` (limited to existing harness chain steps) |

Rules of thumb: judge correctness from `ui-shot`'s `.measure.json` facts (cheap, reliable), not the PNG;
gate a11y regressions with `ui-a11y-gate` (local-first, ADR-0026 — not CI-wired, so run it); use `ui-diff`
when deliberately iterating on one surface; `ui-critic`/`ui-fuzz` are deeper, situational passes. The
`ui-shot-hint` PostToolUse hook surfaces the *contextually-relevant* verb when you edit a `shell-v0` file.

## Server & data requirements (there is NO mock data)
| Step kind | Needs | Notes |
|---|---|---|
| Views / chrome — `home`, `library`, `settings`, `health`, `help`, `ai-brain` | a served frontend only | the tool auto-serves Vite; renders chrome + empty surfaces with no backend |
| Search / inspector — `search-results`, `inspector-open`, `multi-select`, `context-menu`, `filters-chips` | dev stack (worker) | search returns real data |
| AI chain — `streaming`, `summarize-done`, `qa-response`, `citation-highlight` | dev stack + `ai_activate` | the 9B model; latency-/GPU-contention-sensitive — may need a retry on a freshly-activated model |

The live shell lands on the **chat** surface by default; every view step navigates to its rail surface first,
and the harness's app-ready signal is the **rail** (not `search-input`).

## The agent feedback loop
Editing `modules/ui-web/src/shell-v0/**` fires the `ui-shot-hint` PostToolUse hook, which names the affected
steps (from `ui_step_index.json`). Run the suggested `jseval ui-shot <step>`, then read its `.measure.json`
(facts) and/or the PNG (gestalt).

## Coverage + freshness gate — tempdoc 615 §6.1a
`node scripts/ci/check-ui-step-coverage.mjs` (register `governance/ui-step-coverage.v1.json`; wired in ci.yml +
the CLAUDE.md pre-merge list) keeps the harness honest: every source path the step index maps MUST resolve on
disk (a deleted/renamed file is a build failure — this is what stops the index silently rotting back to dead
code, as it did against the retired React stack), and every `placement:'RAIL'` surface in `CORE_SURFACES` must
have a covering view step or a declared exemption. Run it after editing `shell-v0/**` or the harness.

## Worktree auto-serve
In a worktree, `ui-shot` auto-starts its own Vite on :5174 (a `node_modules` junction to the main checkout),
persisted in `tmp/ui-shot-server.json`; the `ui-shot-cleanup` SessionEnd hook kills it. That auto-served Vite
has **no backend** (data steps will show 502s) — to drive a running dev stack instead, pass
`--ui-url http://127.0.0.1:5173` (a non-`localhost:5173` string bypasses the auto-serve).

## Step registry
55 steps. **Chain** (shared browser, `depends_on`): `search-results` → {`filters-chips`, `inspector-open`,
`multi-select`, `context-menu`}; `inspector-open` → `streaming` → `summarize-done` → `citation-highlight`.
**Isolated** (own browser, parallel): the view steps (dark+light), density/mode variants (`search-results-*`,
`search-*-mode`), CDP pseudo-states (`row-hover`, `input-focus`), and the `shell-v0-demo*` / `presentation-demo*`
standalone demos. Run `jseval ui-shot --list` for the authoritative set.

## File-to-step index (live shell-v0)
`scripts/jseval/jseval/ui_step_index.json` (gated by check-ui-step-coverage). Key mappings:
| File | Steps |
|---|---|
| `shell-v0/views/SearchSurface.ts` | search-results, filters-chips, multi-select, search-results/mode variants … |
| `shell-v0/components/InspectorPane.ts` | inspector-open, streaming, summarize-done, qa-response, citation-highlight |
| `shell-v0/components/chat/MarkdownBlock.ts` | citation-highlight |
| `shell-v0/components/ContextMenu.ts` | context-menu |
| `shell-v0/chrome/Shell.ts`, `shell-v0/plugin-api/CorePlugin.ts` | all view/nav steps (home, library, ai-brain, health, settings, help) |
| `shell-v0/views/{HealthSurface,LibrarySurface,BrainSurface,SettingsSurface,HelpSurface}.ts` | the matching view step |

## Key files
| File | Purpose |
|---|---|
| `scripts/jseval/jseval/ui_shot.py` | single-step capture, auto-serve, `--affected` |
| `scripts/jseval/jseval/ui_check.py` | batch capture + the step registry |
| `scripts/jseval/jseval/ui_measure.py` | the measurement companion (a11y/axe/geometry/console) |
| `scripts/jseval/jseval/ui_selectors.py` | live shell-v0 selector constants (role/testid/surface-id) |
| `scripts/jseval/jseval/ui_step_index.json` | file→step map (gated) |
| `scripts/ci/check-ui-step-coverage.mjs` + `governance/ui-step-coverage.v1.json` | coverage/freshness gate |
| `scripts/agent-analytics/hooks/ui-shot-hint.mjs` / `ui-shot-cleanup.mjs` | edit-hint hook / server cleanup |

## Known limitations
- **No mock-data mode** — data/AI steps need the live dev stack (+ `ai_activate` for AI).
- **AI legs are latency-/GPU-contention-sensitive** — the 9B model can unload under VRAM pressure; re-activate and retry.
- **Glassmorphism**: Playwright renders without a compositor — blur appears flat.
- **DPI**: 1× only (2× exceeds the API's 2000px image cap).
- **Motion**: static frames can't show transitions — assert motion *structurally* (the `--duration-*` / `--ease-*` CSS tokens) rather than watching it.
