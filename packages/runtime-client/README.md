# `@justsearch/runtime-client`

Generated, read-only Node client for the stable JustSearch Runtime Contract snapshot and lifecycle
surfaces. Runtime support begins at Node 20. Generation uses Node 22.18 or newer.

```ts
import { createRuntimeClient } from '@justsearch/runtime-client';

const client = await createRuntimeClient({ baseUrl: 'http://127.0.0.1:33221' });
const readiness = await client.getRuntimeReadiness();

if (readiness.status === 503) {
  console.log(readiness.data.lifecycle);
}
```

The async factory accepts an injected `fetch` for tests or native-host integration. Before returning
a client, it rejects non-loopback base URLs, disables redirects, reads the runtime manifest, and
fails closed unless the advertised Runtime Contract version is supported. The package does not expose
mutations, MCP, token bootstrap, probe `HEAD` methods, or the manifest SSE stream.
