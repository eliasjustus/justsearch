/* SPDX-License-Identifier: Apache-2.0 */
import { spawnSync } from 'node:child_process';
import { readFileSync, unlinkSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const committed = join(root, 'src', 'generated', 'client.ts');
const candidate = join(root, 'src', 'generated', 'client.check.ts');
const committedContract = join(root, 'src', 'generated', 'runtime-contract.ts');
const candidateContract = join(root, 'src', 'generated', 'runtime-contract.check.ts');

try {
  const result = spawnSync(process.platform === 'win32' ? 'npm.cmd' : 'npm', ['run', 'generate'], {
    cwd: root,
    env: {
      ...process.env,
      RUNTIME_CLIENT_GENERATED_TARGET: './src/generated/client.check.ts',
      RUNTIME_CLIENT_CONTRACT_TARGET: './src/generated/runtime-contract.check.ts',
    },
    encoding: 'utf8',
  });
  if (result.status !== 0) {
    process.stdout.write(result.stdout ?? '');
    process.stderr.write(result.stderr ?? '');
    process.exit(result.status ?? 1);
  }
  if (!readFileSync(committed).equals(readFileSync(candidate))) {
    console.error('runtime-client generated output is stale; run npm run generate in packages/runtime-client');
    process.exitCode = 1;
  } else if (!readFileSync(committedContract).equals(readFileSync(candidateContract))) {
    console.error('runtime-client contract version output is stale; run npm run generate in packages/runtime-client');
    process.exitCode = 1;
  } else {
    console.log('runtime-client generated output is deterministic and current');
  }
} finally {
  try {
    unlinkSync(candidate);
  } catch (error) {
    if (error?.code !== 'ENOENT') throw error;
  }
  try {
    unlinkSync(candidateContract);
  } catch (error) {
    if (error?.code !== 'ENOENT') throw error;
  }
}
