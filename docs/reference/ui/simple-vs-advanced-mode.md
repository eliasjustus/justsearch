---
title: Simple vs Advanced UI Mode
area: ui
status: current
---

# Simple vs Advanced UI Mode

JustSearch has two UI modes that control the visibility of power-user features. This document is the canonical reference for what each mode shows.

## Philosophy

| Mode | Target User | Goal |
|------|-------------|------|
| **Simple** (default) | Casual users | Clean, focused interface with only essential controls |
| **Advanced** | Power users | Full access to technical details, bulk operations, and telemetry |

Users switch modes in **Settings > Interface**.

## How to Check UI Mode in Code

Always use the `useUiMode()` hook:

```typescript
import { useUiMode } from '../../hooks/useUiMode';

function MyComponent() {
  const { isAdvanced } = useUiMode();

  return (
    <>
      {isAdvanced && <PowerUserFeature />}
      {!isAdvanced && <SimplifiedView />}
    </>
  );
}
```

**Do not** access `settings.ui.mode` directly — the hook handles:
- Default value normalization (`undefined` → `'simple'`)
- URL override for testing (`?demo_ui_mode=advanced`)

## Element Visibility by Mode

### Search View

| Element | Simple | Advanced |
|---------|--------|----------|
| Query syntax toggle (Basic/Lucene) in filter bar | Hidden | Visible |
| Path prefix filter input | Hidden | Visible |
| MIME base facet toggles | Hidden | Visible |

### Library View

| Element | Simple | Advanced |
|---------|--------|----------|
| "Reindex All" button | Hidden | Visible |
| Exclude patterns section | Hidden | Visible |
| Add Folder button | Visible | Visible |

### AI Brain View

| Element | Simple | Advanced |
|---------|--------|----------|
| BrainSimplePanel (one-click install) | Visible | Hidden |
| "Recommended next step" guide | Hidden | Visible |
| Embedding compatibility card | Hidden | Visible |
| Schema compatibility card | Hidden | Visible |
| Chunk vector status card | Hidden | Visible |
| Policy Helper panel | Hidden | Visible |
| Inference Settings (sliders) | Hidden | Visible |

### Inspector Panel (Context Indicator)

| Element | Simple | Advanced |
|---------|--------|----------|
| Context state pill (Safe/Near limit/Too large) | Visible | Hidden |
| Actionable guidance ("deselect N files") | Visible | Hidden |
| RAG status pill (RAG/Partial/Fallback) | Hidden | Visible |
| Retrieval mode badge (HYBRID/BM25) | Hidden | Visible |
| Truncation indicator | Hidden | Visible |
| Full token breakdown | Hidden | Visible |
| Context details panel (collapsible) | Hidden | Visible |

## Testing with URL Override

For development and testing, you can force a UI mode via URL parameter:

```text
http://localhost:5173?demo_ui_mode=advanced
http://localhost:5173?demo_ui_mode=simple
```

This override:
- Takes precedence over stored settings
- Works in all components using `useUiMode()` hook
- Does **not** work in components that bypass the hook (legacy code)

## Adding New Mode-Conditional UI

1. Import the hook: `import { useUiMode } from '../../hooks/useUiMode';`
2. Call it in your component: `const { isAdvanced } = useUiMode();`
3. Use `isAdvanced` in JSX conditionals
4. Update this document with the new element

**Naming convention:** Use `isAdvanced` as the variable name for grep consistency.

## Known Limitations

1. **Evidence capture enforces simple mode:** ~~All POST handlers in the EBv1 capture harness set `mode: 'simple'`, so mode persistence could not be tested in evidence captures.~~ Resolved (tempdoc 638) — the EBv1 capture harness was removed.

2. **URL override takes precedence over UI:** When `?demo_ui_mode=X` is active, clicking mode buttons in Settings updates the store but the URL override still controls the display. This is intentional but may confuse testers.

## Implementation Files

| File | Role |
|------|------|
| `hooks/useUiMode.ts` | Canonical hook (single source of truth) |
| `stores/systemTypes.ts` | `UISettings.mode` type definition |
| `api/settings.ts` | `/api/settings/v2` endpoint integration |
