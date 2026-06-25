# Scaffold Plugin

Minimal working JustSearch plugin. Copy this directory and modify to
start a new plugin.

## What it does

- Registers a custom element `<scaffold-panel>` that renders a heading
  and a button.
- Contributes a surface (`vendor.scaffold.panel-surface`) to the rail.
- Registers a command palette entry (`scaffold.greet`) — try `Ctrl+K`
  → "Scaffold: Say Hello".

## How to load it

### Browser dev mode

1. Run `node dev-server.cjs` in this directory (serves on
   `http://localhost:3001`). The `.cjs` extension is required —
   `modules/ui-web` is `"type": "module"`, so a `.js` server would be
   parsed as ESM and `require` would throw.
2. In JustSearch's Settings → Plugins → "Load from URL" input, paste
   `http://localhost:3001/plugin.js` and click Load.
3. Edit `plugin.js` and re-click Load to reinstall (or rely on
   hot-reload if running in Tauri with the plugin installed to disk).

### Tauri / production

Drop this directory into `~/.justsearch/plugins/scaffold/` and restart
JustSearch. Hot-reload is automatic — saving `plugin.js` re-installs
the plugin.

## Authoring guide

- The exported value is a factory function. It evaluates inside an SES
  Compartment with restricted endowments: `customElements`,
  `HTMLElement`, `localStorage`, `document`, `setTimeout`,
  `setInterval`, `console`. **No `window`. No `fetch`** — use
  `host.fetch()` instead.
- The factory returns a `PluginManifest`. The `register(host)` hook
  receives a `PluginHostApi` instance — your gateway to fetch,
  operations, notifications, search state, navigation, theming, etc.
- Custom element tags must start with `<plugin-id>-` (e.g.,
  `scaffold-panel` for id `scaffold`).
- Surface ids must be `vendor.<x>.<y>` (or `core.<y>`) to be
  router-navigable — the router's `SurfaceRef` regex
  (`^(core|vendor\.[a-z][a-z0-9-]*)\.[a-z][a-z0-9-]*$`) rejects a bare
  `<plugin-id>.<suffix>`, leaving the surface admitted but unreachable.
- Return a `PluginContribution` from `register` to declare surfaces,
  status bar items, inspector tabs, and context menu actions.

## State preservation across hot-reload

Use `host.getSetting(key)` / `host.setSetting(key, value)` to persist
plugin state in `UserStateDocument`. Values survive hot-reload cycles.

## Trust tiers

- **CORE** — built-in plugins; full Host API access; no sandbox.
- **TRUSTED_PLUGIN** — Sigstore-verified; full Host API; SES sandbox.
- **UNTRUSTED_PLUGIN** — unsigned; SES sandbox; reduced Host API
  (read-only fetch to allowlisted endpoints, no keybinding, no file
  picker, no reveal-in-explorer).
