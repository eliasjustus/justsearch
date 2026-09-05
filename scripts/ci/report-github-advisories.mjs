#!/usr/bin/env node

import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  GITHUB_ADVISORY_PROVIDER,
  GITHUB_ADVISORY_REPORT_SCHEMA,
  GITHUB_API_VERSION,
  REQUIRED_ADVISORY_TARGETS,
  packageSpecsFromLockfileText,
  queryGitHubAdvisories,
  sha256,
} from './lib/github-advisory-report.mjs';

export async function collectAdvisoryTarget({ repoRoot, target, query = queryGitHubAdvisories, token = '' }) {
  try {
    const lockfileText = await readFile(path.resolve(repoRoot, target.lockfile), 'utf8');
    const specs = packageSpecsFromLockfileText(lockfileText);
    const advisories = await query(specs, { token });
    return {
      target_id: target.targetId,
      lockfile: target.lockfile,
      lockfile_sha256: sha256(lockfileText),
      package_versions: specs.length,
      available: true,
      error: null,
      advisories,
    };
  } catch (error) {
    return {
      target_id: target.targetId,
      lockfile: target.lockfile,
      lockfile_sha256: null,
      package_versions: null,
      available: false,
      error: error instanceof Error ? error.message : String(error),
      advisories: [],
    };
  }
}

function parseArgs(argv) {
  const args = { out: 'tmp/github-advisory-report.json' };
  for (let index = 0; index < argv.length; index += 1) {
    if (argv[index] === '--out') args.out = argv[++index];
    else if (argv[index].startsWith('--out=')) args.out = argv[index].slice('--out='.length);
    else throw new Error(`Unknown option: ${argv[index]}`);
  }
  if (!args.out) throw new Error('--out requires a path');
  return args;
}

export async function buildReport({ repoRoot, token = '' }) {
  const targets = [];
  for (const target of REQUIRED_ADVISORY_TARGETS) {
    targets.push(await collectAdvisoryTarget({ repoRoot, target, token }));
  }
  return {
    schema: GITHUB_ADVISORY_REPORT_SCHEMA,
    generated_at: new Date().toISOString(),
    source: { provider: GITHUB_ADVISORY_PROVIDER, api_version: GITHUB_API_VERSION },
    targets,
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
  const report = await buildReport({
    repoRoot,
    token: process.env.GITHUB_TOKEN || process.env.GH_TOKEN || '',
  });
  const output = path.resolve(repoRoot, args.out);
  await mkdir(path.dirname(output), { recursive: true });
  await writeFile(output, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  for (const target of report.targets) {
    const detail = target.available ? `${target.advisories.length} advisories` : `UNAVAILABLE: ${target.error}`;
    process.stdout.write(`${target.target_id}: ${detail}\n`);
  }
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    process.stderr.write(`[report-github-advisories] ${error instanceof Error ? error.stack : String(error)}\n`);
    process.exitCode = 1;
  });
}
