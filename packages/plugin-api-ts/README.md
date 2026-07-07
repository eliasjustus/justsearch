# @justsearch/plugin-api

TypeScript SDK for authoring JustSearch plugins (V1.5 contract).

## Status

**1.5.1-alpha** — published-package metadata is in place; the
package is built locally via `npm run build`. **Actual publish
to npm is V1.5.2** — requires user-provisioned npm 2FA + access
token, which is outside the autonomous-implementation scope of
slice 477 H3.1.

## What's in this package

The published types mirror
`modules/ui-web/src/shell-v0/plugin-api/` from the JustSearch
monorepo. V1.5.1 alpha syncs types via `npm run sync-types`
(reads from the monorepo source). V1.5.2 will publish as a
real package; at that point `npm install @justsearch/plugin-api`
will pull a versioned snapshot.

Exported types:

- `PluginManifest`, `PluginCapabilities`, `PluginRegisterFn`,
  `PluginUnregisterFn` — the V1.5 plugin contract shape
- `PluginHostApi`, `SurfacePortHandler`, `SurfacePortContext` —
  host-mediated APIs for plugin contributions
- `PluginSurfaceContribution` — surface manifest entry shape
- `PLUGIN_CONTRACT_VERSION` — current host contract version
  (V1.5.1: `'1.1'`)
- `PluginTrustTier`, `TrustVerdict`, `TrustEvidence` — V1.5.1
  H2.3 trust model types

## Authoring a plugin

See the
[how-to guide](https://github.com/eliasjustus/justsearch/blob/main/docs/how-to/write-a-plugin.md)
for a full walkthrough.

Quick start (TypeScript):

```ts
import type { PluginManifest } from '@justsearch/plugin-api';

// Define the plugin in TS for editor support; ship the compiled JS.
const manifest: PluginManifest = {
  id: 'myplugin',
  version: '1.0.0',
  displayName: 'My Plugin',
  contractVersion: '1.1',
  tagNamespace: 'myplugin',
  capabilities: {
    surfaces: [/* ... */],
  },
  register(host) {
    // ...
  },
};

export default manifest;
```

Then compile with `tsc` and wrap the compiled JS in the V1.5.1
factory shape (see how-to guide §"The plugin source contract").

## V1.5.1 alpha contract

The plugin runs in a SES Compartment with explicit endowments.
Plugin code cannot reach `window`, `fetch`, or other host
globals unless the host endows them. The plugin source contract
is an **expression** (not an ES module), evaluating to either:

- A `PluginManifest` object directly, OR
- A factory function `(endowments) => PluginManifest`.

See `docs/how-to/write-a-plugin.md` for the migration from V1.5
alpha (ES module + default export) to V1.5.1 (factory expression).

## Tier model

Plugins run at `UNTRUSTED_PLUGIN` by default. `TRUSTED_PLUGIN`
requires backend signature verification (V1.5.2+ via Sigstore).
The default `UNTRUSTED` tier provides:

- `localStorage` writes scoped to `plugin:<id>:` namespace
- `customElements.define()` rejected for tags outside `<id>-*`
- All other endowments same as TRUSTED

See `TrustChannel` types in this package + the substrate docs
for the full model.

## License

Apache-2.0.
