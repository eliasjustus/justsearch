# `@justsearch/runtime-client`

Generated, read-only Node client for the stable JustSearch Runtime Contract snapshot and lifecycle
surfaces. Runtime support begins at Node 20. Generation uses Node 22.18 or newer.

## Installation and status

Install a released version only when the
[npm registry lists `@justsearch/runtime-client`](https://www.npmjs.com/package/@justsearch/runtime-client):

```text
npm install @justsearch/runtime-client
```

If npm reports that the package is not found, no registry release is available yet; do not treat a
source checkout as a published package. Repository contributors can validate the package from the
repository root:

```text
npm ci --prefix packages/runtime-client --ignore-scripts
npm --prefix packages/runtime-client run check:regen
npm --prefix packages/runtime-client test
npm --prefix packages/runtime-client run check:pack
```

## Find the running service

A native client first resolves the JustSearch data directory using the
[cross-language runtime-manifest discovery contract](https://github.com/justsearch-app/justsearch/blob/main/docs/explanation/23-runtime-manifest.md),
reads `<dataDir>/runtime/manifest.json`, verifies the live process identity, and passes
`head.apiBaseUrl` to the client factory. Do not guess a port or scrape logs. The
[Runtime Contract reference](https://github.com/justsearch-app/justsearch/blob/main/docs/reference/runtime-contract.md#generated-node-client)
defines the supported operations and compatibility policy.

After the package is published, the application call is:

```ts
import { createRuntimeClient } from '@justsearch/runtime-client';

export async function inspectReadiness(baseUrl: string): Promise<void> {
  const client = await createRuntimeClient({ baseUrl });
  const readiness = await client.getRuntimeReadiness();

  if (readiness.status === 503) {
    console.log(readiness.data.lifecycle);
  }
}
```

The async factory accepts an injected `fetch` for tests or native-host integration. Before returning
a client, it rejects non-loopback base URLs, disables redirects, reads the runtime manifest, and
fails closed unless the advertised Runtime Contract version is supported. The package does not expose
mutations, MCP, token bootstrap, probe `HEAD` methods, or the manifest SSE stream.

Report package problems through the
[JustSearch issue tracker](https://github.com/justsearch-app/justsearch/issues) without including
private document contents, credentials, or unreviewed diagnostic data.
