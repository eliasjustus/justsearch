/* SPDX-License-Identifier: Apache-2.0 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

import {
  assertRuntimeContractCompatible,
  createRuntimeClient,
  SUPPORTED_RUNTIME_CONTRACT_VERSIONS,
} from '../dist/index.js';

test('generated readiness operation preserves the typed lifecycle 503 response', async () => {
  let request;
  const client = createRuntimeClient({
    baseUrl: 'http://127.0.0.1:33221',
    fetch: async (input, init) => {
      request = { input: input.toString(), init };
      return new Response(
        JSON.stringify({
          ready: false,
          lifecycle: 'LIFECYCLE_STATE_STARTING',
          instanceId: 'instance-1',
        }),
        { status: 503, headers: { 'content-type': 'application/json' } },
      );
    },
  });

  const response = await client.getRuntimeReadiness();

  assert.equal(response.status, 503);
  assert.equal(response.data.ready, false);
  assert.equal(response.data.lifecycle, 'LIFECYCLE_STATE_STARTING');
  assert.equal(request.input, 'http://127.0.0.1:33221/api/runtime/ready');
  assert.equal(request.init.method, 'GET');
});

test('separate clients retain their injected transport across concurrent calls', async () => {
  const seen = [];
  const makeFetch = (label) => async (input) => {
    await Promise.resolve();
    seen.push(`${label}:${input}`);
    return new Response(JSON.stringify({ alive: true, pid: null, instanceId: null }), {
      status: 200,
    });
  };
  const first = createRuntimeClient({ baseUrl: 'http://127.0.0.1:31001', fetch: makeFetch('a') });
  const second = createRuntimeClient({ baseUrl: 'http://localhost:31002', fetch: makeFetch('b') });

  await Promise.all([first.getRuntimeLiveness(), second.getRuntimeLiveness()]);

  assert.deepEqual(seen.sort(), [
    'a:http://127.0.0.1:31001/api/runtime/live',
    'b:http://localhost:31002/api/runtime/live',
  ]);
});

test('factory rejects non-loopback or credential-bearing base URLs', () => {
  assert.throws(() => createRuntimeClient({ baseUrl: 'https://example.com' }), /loopback/);
  assert.throws(() => createRuntimeClient({ baseUrl: 'http://user:pass@127.0.0.1:33221' }), /credentials/);
  assert.throws(() => createRuntimeClient({ baseUrl: 'http://127.0.0.1:33221?token=x' }), /query/);
  assert.throws(() => createRuntimeClient({ baseUrl: 'http://127.0.0.1:33221/prefix' }), /path/);
});

test('runtime contract compatibility is explicit and fail-closed', () => {
  const openapi = JSON.parse(
    readFileSync(new URL('../openapi/runtime-client.openapi.json', import.meta.url), 'utf8'),
  );
  assert.deepEqual(
    SUPPORTED_RUNTIME_CONTRACT_VERSIONS,
    openapi['x-justsearch-runtime-contract'].supportedVersions,
  );
  assert.doesNotThrow(() =>
    assertRuntimeContractCompatible({ runtimeContract: { version: '0.2.0' } }),
  );
  assert.throws(() => assertRuntimeContractCompatible({}), /missing/);
  assert.throws(
    () => assertRuntimeContractCompatible({ runtimeContract: { version: '0.3.0' } }),
    /version: 0.3.0/,
  );
});
