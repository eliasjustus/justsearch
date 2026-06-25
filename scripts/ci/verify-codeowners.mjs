#!/usr/bin/env node
/**
 * Validate CODEOWNERS coverage for required repository paths.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const scriptDir =
  process.platform === 'win32' ? SCRIPT_DIR.replace(/^\/([A-Za-z]:)/, '$1') : SCRIPT_DIR;
const repoRoot = path.resolve(scriptDir, '..', '..');

const REQUIRED_PATTERNS = [
  '*',
  '/.github/**',
  '/docs/**',
  '/scripts/**',
  '/SSOT/**',
  '/modules/ui/**',
  '/modules/ui-web/**',
  '/modules/indexer-worker/**',
  '/modules/adapters-lucene/**',
  '/modules/app-services/**',
  '/modules/configuration/**',
  '/modules/ipc-common/**',
  '/modules/system-tests/**',
  '/modules/reranker/**',
  '/modules/app-inference/**',
];

function usage(code = 1) {
  // eslint-disable-next-line no-console
  console.error(
    [
      'Usage:',
      '  node scripts/ci/verify-codeowners.mjs [options]',
      '',
      'Options:',
      '  --file <path>   CODEOWNERS file path (default: .github/CODEOWNERS)',
      '  -h, --help',
    ].join('\n'),
  );
  process.exit(code);
}

function parseArgs(argv) {
  const out = {
    file: path.join(repoRoot, '.github', 'CODEOWNERS'),
  };
  const args = [...argv];
  const takeValue = (idx) => {
    const token = args[idx];
    const eq = token.indexOf('=');
    if (eq !== -1) return token.slice(eq + 1);
    return args[idx + 1] ?? null;
  };
  const consumeNext = new Set();

  for (let i = 0; i < args.length; i += 1) {
    if (consumeNext.has(i)) continue;
    const token = args[i];
    if (token === '-h' || token === '--help') usage(0);
    if (!token.startsWith('--')) continue;

    const key = token.split('=')[0];
    const hasInlineValue = token.includes('=');
    const value = takeValue(i);
    if (!hasInlineValue) consumeNext.add(i + 1);

    switch (key) {
      case '--file':
        out.file = path.resolve(value || out.file);
        break;
      default:
        throw new Error(`Unknown option: ${token}`);
    }
  }

  return out;
}

function parseCodeowners(content) {
  const map = new Map();
  const lines = content.split(/\r?\n/);
  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const tokens = line.split(/\s+/).filter(Boolean);
    if (tokens.length === 0) continue;
    const pattern = tokens[0];
    const owners = tokens.slice(1);
    map.set(pattern, owners);
  }
  return map;
}

function hasAtLeastOneOwner(ownerTokens) {
  return ownerTokens.some((token) => token.startsWith('@') || token.includes('@'));
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  const codeownersPath = options.file;

  if (!fs.existsSync(codeownersPath)) {
    // eslint-disable-next-line no-console
    console.error(`CODEOWNERS file not found: ${codeownersPath}`);
    process.exit(1);
  }

  const content = fs.readFileSync(codeownersPath, 'utf8');
  const parsed = parseCodeowners(content);
  const missingPatterns = [];
  const ownerlessPatterns = [];

  for (const pattern of REQUIRED_PATTERNS) {
    if (!parsed.has(pattern)) {
      missingPatterns.push(pattern);
      continue;
    }
    const owners = parsed.get(pattern);
    if (!hasAtLeastOneOwner(owners)) {
      ownerlessPatterns.push(pattern);
    }
  }

  if (missingPatterns.length > 0 || ownerlessPatterns.length > 0) {
    if (missingPatterns.length > 0) {
      // eslint-disable-next-line no-console
      console.error('Missing required CODEOWNERS patterns:');
      for (const pattern of missingPatterns) {
        // eslint-disable-next-line no-console
        console.error(`  - ${pattern}`);
      }
    }
    if (ownerlessPatterns.length > 0) {
      // eslint-disable-next-line no-console
      console.error('Required patterns without owner tokens:');
      for (const pattern of ownerlessPatterns) {
        // eslint-disable-next-line no-console
        console.error(`  - ${pattern}`);
      }
    }
    process.exit(1);
  }

  // eslint-disable-next-line no-console
  console.log(
    `CODEOWNERS verification passed: ${REQUIRED_PATTERNS.length} required patterns present with owners.`,
  );
}

main();

