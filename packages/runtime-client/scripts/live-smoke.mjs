/* SPDX-License-Identifier: Apache-2.0 */
import assert from 'node:assert/strict';

import { assertRuntimeContractCompatible, createRuntimeClient } from '../dist/index.js';

const baseUrl = process.argv[2] ?? process.env.JUSTSEARCH_RUNTIME_URL;
if (!baseUrl) {
  throw new Error('Pass the JustSearch runtime base URL as the first argument');
}

const client = await createRuntimeClient({ baseUrl });
const manifest = await client.getRuntimeManifest();
const mirror = await client.getWellKnownRuntimeManifest();
const readiness = await client.getRuntimeReadiness();
const liveness = await client.getRuntimeLiveness();
const health = await client.getLifecycleHealth();
const status = await client.getLifecycleStatus();

assert.equal(manifest.status, 200);
assert.equal(mirror.status, 200);
assert.deepEqual(mirror.data, manifest.data);
assertRuntimeContractCompatible(manifest.data);
assert.ok([200, 503].includes(readiness.status));
assert.equal(liveness.status, 200);
assert.ok([200, 503].includes(health.status));
assert.equal(status.status, 200);

console.log(
  `runtime-client live smoke passed (contract ${manifest.data.runtimeContract.version}, ` +
    `readiness ${readiness.status}, health ${health.status})`,
);
