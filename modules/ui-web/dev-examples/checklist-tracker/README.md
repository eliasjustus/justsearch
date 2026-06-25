# Checklist Tracker — first real third-party plugin (tempdoc 560 §28 / 533)

A genuinely third-party, URL-loaded, **writing** plugin. It exists to validate the plugin delivery
path that the compiled-in Token Editor bypassed: **untrusted load → operator approval → TRUSTED →
SES-sandboxed, own-element surface, persistent state.** It is a substrate-validation consumer, not a
bundled product feature.

## Run

```bash
node dev-server.cjs        # serves plugin.js on http://127.0.0.1:3002/plugin.js
```

Then in JustSearch: **Settings → Plugins → Load from URL** → `http://127.0.0.1:3002/plugin.js`.

## What you should see (the delivery path)

1. **Loads UNTRUSTED** — the row shows `untrusted`, **0 surfaces**: its own `<checklist-panel>` element
   is dropped by the §4.4 presentation constraint (an untrusted plugin may only mount `jf-*`). An
   "Approve & trust" action appears.
2. **Approve & trust** — the operator approves the source's SHA-256 into the persisted backend
   allowlist (`POST /api/plugins/allowlist`); the plugin reloads and the backend verdict comes back
   **TRUSTED**. The row now shows `trusted` / `1 surface(s)` and a **Checklist** item appears in the rail.
3. **Use it** — add/check/delete items; state persists via `host.settings` (plugin-namespaced,
   ADR-0035-safe). It **survives reload** and the approval **survives a restart** (file-persisted).

## Design notes

- **Persistence:** `host.settings.setSetting/getSetting` — the plugin authors only its own scoped
  state, never backend truth (Operations/Resources/Prompts stay host-owned per ADR-0035).
- **Why TRUSTED is required:** it ships its own UI element, so it is the natural forcing function for
  the operator-approval trust ceremony (short of full Sigstore, which stays deferred).
