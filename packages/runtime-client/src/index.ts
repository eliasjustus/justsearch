/* SPDX-License-Identifier: Apache-2.0 */
import * as generated from './generated/client.js';
import { SUPPORTED_RUNTIME_CONTRACT_VERSIONS } from './generated/runtime-contract.js';
import {
  resolveRuntimeTransport,
  withRuntimeTransport,
  type RuntimeTransportOptions,
} from './transport.js';

export type * from './generated/client.js';
export { SUPPORTED_RUNTIME_CONTRACT_VERSIONS } from './generated/runtime-contract.js';
export type { RuntimeFetch, RuntimeHttpResponse, RuntimeTransportOptions } from './transport.js';

type AsyncOperation = (...args: never[]) => Promise<unknown>;
type GeneratedOperations = {
  [Key in keyof typeof generated as (typeof generated)[Key] extends AsyncOperation ? Key : never]:
    (typeof generated)[Key];
};

export type RuntimeClient = GeneratedOperations;

export function assertRuntimeContractCompatible(manifest: {
  runtimeContract?: { version?: string } | null;
}): void {
  const version = manifest.runtimeContract?.version;
  if (
    typeof version !== 'string' ||
    !(SUPPORTED_RUNTIME_CONTRACT_VERSIONS as readonly string[]).includes(version)
  ) {
    throw new RangeError(
      `Unsupported JustSearch runtime contract version: ${String(version ?? 'missing')}; ` +
        `supported ${SUPPORTED_RUNTIME_CONTRACT_VERSIONS.join(', ')}`,
    );
  }
}

export function createRuntimeClient(options: RuntimeTransportOptions): RuntimeClient {
  const transport = resolveRuntimeTransport(options);
  return new Proxy(generated, {
    get(target, property, receiver) {
      const value = Reflect.get(target, property, receiver) as unknown;
      if (typeof value !== 'function') return value;
      return (...args: unknown[]) =>
        withRuntimeTransport(transport, () =>
          (value as (...operationArgs: unknown[]) => Promise<unknown>)(...args),
        );
    },
  }) as RuntimeClient;
}
