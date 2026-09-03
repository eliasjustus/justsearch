# `@justsearch/runtime-client`

Generated, read-only Node client for the stable JustSearch Runtime Contract snapshot and lifecycle
surfaces. Runtime support begins at Node 20. Generation uses Node 22.18 or newer.

```ts
import { createRuntimeClient } from '@justsearch/runtime-client';

const client = createRuntimeClient({ baseUrl: 'http://127.0.0.1:33221' });
const readiness = await client.getRuntimeReadiness();

if (readiness.status === 503) {
  console.log(readiness.data.lifecycle);
}
```

The factory accepts an injected `fetch` for tests or native-host integration. It rejects non-loopback
base URLs. The package does not expose mutations, MCP, token bootstrap, probe `HEAD` methods, or the
manifest SSE stream.
