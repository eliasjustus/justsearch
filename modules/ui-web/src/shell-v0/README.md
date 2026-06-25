# Shell V0 (Lit-based, slice 3a.0+)

This directory holds the Lit / web-components frontend that will
eventually replace the React UI under `modules/ui-web/src/` (per
slice 3a.8 decommission, gated on slice 3a.0–3a.7 reaching parity).

During the React-Lit coexistence period (this whole tempdoc 421
Stage 3a effort), both codebases ship in the same module. Vite
treats Lit components as ESM modules; React + Lit can coexist
without integration friction since neither owns the DOM root in this
package.

## Layout

```
shell-v0/
├── README.md          (this file)
├── renderers/         (slice 3a.0 — JSON Forms Lit renderer set)
│   ├── controls/      (text, number, boolean, enum, date, time, array, object)
│   └── layouts/       (vertical, horizontal, group, categorization)
├── components/        (slice 3a.1 — generic Shell V0 components)
└── shell/             (slice 3a.1 — Shell V0 entry / layout / theme)
```

## Slice authority

- Renderer set: see `the renderer set in this directory (historical design: retired 421 draft, in git)`.
- Shell V0: future slice 3a.1 tempdoc (TBD).
- Stack pick rationale: `archive/source-tempdocs/421-stack.md`
  §"Library choices."

## Coexistence model

- React app entry (`main.tsx`, `App.tsx`, etc.) continues to ship as
  the user-facing UI through the entire Stage 3a effort.
- Shell V0 components are imported but NOT rendered into the React
  app. They're built as a parallel target.
- Shell V0 will eventually become the user-facing UI when slice 3a.8
  ships; at that point, the React directories delete and
  `shell-v0/` becomes the new root.

## Why no separate module?

Per slice 3a.0 §B.A.1: keeping shell-v0 as a subdirectory in
`modules/ui-web/` avoids the new-module governance overhead
(module-arch skill, ArchUnit, build-logic registration). Single Vite
config, single package.json, single node_modules. Reversible if the
pattern proves friction-prone (then promote to its own module per
the module-arch skill).
