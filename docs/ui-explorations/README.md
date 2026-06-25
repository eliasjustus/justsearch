# UI Explorations

> **Status: noncanonical.** Nothing in this folder represents committed UI direction. Material here is exploratory — alternatives generated to inform later decisions, not a spec to follow.

## What this folder is for

Snapshots of holistic UI direction explorations — typically multiple distinct concepts produced in a single round, each with its own visual mockups and written rationale. The goal of any round is to surface alternatives to the current UI, not to redesign it.

## What this folder is NOT

- **Not a redesign log.** No commitment to ship anything here.
- **Not a design system.** Tokens, components, and patterns live with the implementation.
- **Not a feature proposal.** Use `docs/future-features/` for those.
- **Not implementation work.** Use `docs/tempdocs/` for that.
- **Not canonical truth.** Canonical UI behavior lives under `docs/explanation/` and `docs/reference/`.

## Layout convention

One folder per round, dated:

```
docs/ui-explorations/
  YYYY-MM-DD-<theme-slug>/
    brief.md                       # the brief that commissioned this round
    direction-1-<slug>/
      rationale.md                 # 1-page rationale for this direction
      images/
        01-first-open.png
        02-searching.png
        03-reading.png
        04-asking-ai.png
        05-managing.png
    direction-2-<slug>/
    direction-3-<slug>/
    direction-4-<slug>/
    comparison.md                  # cross-direction comparison summary
```

Notes:

- **Direction slugs** should come from the round's own framing (e.g., `power-tool`, `reading-room`, `native-utility`). Numeric prefixes preserve the order they were produced in.
- **Image naming**: prefix with the moment number from the brief (`01-…` through `05-…`) so files sort in narrative order.
- **The brief is archived inside the round folder.** The brief drifts between rounds; the snapshot here is the version that produced this round's output.

## Index of rounds

| Round | Date | Theme | Notes |
|-------|------|-------|-------|
| `2026-04-26-concept-exploration` | 2026-04-26 | First open-ended concept exploration. Four distinct UI directions. | First round. |

## Adding a new round

1. Create `YYYY-MM-DD-<theme-slug>/`.
2. Copy the brief that was used into `brief.md` inside the round folder.
3. Add one folder per direction with `rationale.md` and an `images/` subfolder.
4. Add `comparison.md` at the round level if a cross-direction summary exists.
5. Update the index above.

## How agents should treat this material

- **Reference, not requirement.** A direction here may be wildly different from the current UI. That doesn't mean the current UI is wrong, or that the direction will ship.
- **Read the rationale before the mockups.** The rationale states the underlying belief; the mockups are one expression of it.
- **Don't mistake exploration for decision.** If you're proposing UI changes and want to invoke an exploration as supporting evidence, say "previously explored" — not "the chosen direction."
