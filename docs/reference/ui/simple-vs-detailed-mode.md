---
title: "Simple and Detailed UI modes"
type: reference
status: stable
description: "The app-wide disclosure-level contract, its Lit state authority, projections, and stable wire values."
---

# Simple and Detailed UI modes

JustSearch has one app-wide disclosure level with two user-facing choices:

| Mode | Purpose |
|---|---|
| **Simple** (default) | Keeps routine actions and plain-language status visible while withholding optional technical detail. |
| **Detailed** | Reveals diagnostics, raw identifiers, full paths, runtime configuration, and other power-user detail. |

**Detailed** is the only user-facing name for the second mode. The persisted API value remains
`advanced` for compatibility; code may therefore use the `UiMode` value `advanced` and the predicate
`isAdvancedMode()`, but rendered labels and user instructions say **Detailed**.

## Authority and projections

`modules/ui-web/src/shell-v0/state/uiModeState.ts` is the live frontend authority. It normalizes the
persisted `ui.mode` setting, publishes changes, and lets Lit surfaces subscribe. The mode controls in
the top bar, Settings, and AI Brain are projections of that same value; changing any one updates the
others immediately and sends the compatible value to `/api/settings/v2`. Persistence feedback follows
the owning control's existing lifecycle: the top bar is best-effort, Settings reports its save state,
and Brain serializes rapid choices and rolls back its latest choice when that save is rejected.

Consumers must read or subscribe through `uiModeState` rather than maintaining a second local mode.
The active projections include:

- `chrome/Shell.ts` for the top-bar control and rail visibility;
- `views/SettingsSurface.ts` for the Interface setting;
- `views/BrainSurface.ts` for AI setup versus runtime detail;
- search, chat, result, and document components that choose plain-language or technical detail.

## Lit usage

Use the shared store directly in Lit components:

```typescript
import {
  getUiMode,
  isAdvancedMode,
  setUiMode,
  subscribeUiMode,
} from '../state/uiModeState.js';
```

- Use `isAdvancedMode()` for an immediate disclosure decision.
- Use `subscribeUiMode()` when a mounted component must rerender after another control changes the mode.
- Use `setUiMode()` when publishing a successfully loaded or intentionally changed value.
- Keep the wire values `simple` and `advanced`; map them to **Simple** and **Detailed** at presentation sites.

The store defaults to Simple until the boot appearance restore loads persisted settings. A projecting
surface must not overwrite a newer shared choice with a slower local settings response.

## Verification

`uiModeState.test.ts` pins normalization and subscription behavior. Surface tests pin the visible
Simple/Detailed labels and mode-specific disclosure. Changes to a projecting surface also require the
affected `jseval ui-shot` measurement and the shell-v0 UI coverage check.
