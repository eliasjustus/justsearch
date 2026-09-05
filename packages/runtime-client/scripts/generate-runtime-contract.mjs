/* SPDX-License-Identifier: Apache-2.0 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const openapi = JSON.parse(readFileSync(join(root, 'openapi', 'runtime-client.openapi.json'), 'utf8'));
const versions = openapi['x-justsearch-runtime-contract']?.supportedVersions;
if (
  !Array.isArray(versions) ||
  versions.length === 0 ||
  versions.some((version) => typeof version !== 'string' || version.length === 0)
) {
  throw new Error('OpenAPI must declare non-empty x-justsearch-runtime-contract.supportedVersions');
}

const target = process.env.RUNTIME_CLIENT_CONTRACT_TARGET ?? './src/generated/runtime-contract.ts';
const source = `/* SPDX-License-Identifier: Apache-2.0 */
// Generated from openapi/runtime-client.openapi.json. Do not edit.
export const SUPPORTED_RUNTIME_CONTRACT_VERSIONS = Object.freeze(${JSON.stringify(versions)} as const);
`;
writeFileSync(join(root, target), source, 'utf8');
