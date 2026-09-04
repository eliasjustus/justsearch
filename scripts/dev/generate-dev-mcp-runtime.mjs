#!/usr/bin/env node
/**
 * Generate the hermetic third-party runtime used by the required justsearch-dev MCP.
 *
 * The application server remains readable repository source. Only its SDK/Zod dependency
 * boundary is projected into a committed ESM file, so a fresh worktree can initialize before a
 * root npm install. The projection is lockfile-derived, byte-checked, and accompanied by complete
 * package license text. Run with --check in CI; run without it after dependency changes.
 */

import crypto from 'node:crypto';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { builtinModules } from 'node:module';
import { fileURLToPath } from 'node:url';

import { build, version as esbuildVersion } from 'esbuild';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = path.resolve(HERE, '..', '..');
export const ENTRY_REL = 'scripts/dev/justsearch-dev-mcp/runtime-entry.mjs';
export const RUNTIME_REL = 'scripts/dev/justsearch-dev-mcp/runtime.generated.mjs';
export const LEGAL_REL = 'scripts/dev/justsearch-dev-mcp/runtime.generated.LEGAL.txt';
const LOCK_REL = 'package-lock.json';
const ALLOWED_LICENSES = new Set(['MIT', 'ISC', 'BSD-2-Clause', 'BSD-3-Clause', 'Apache-2.0']);
const NODE_BUILTINS = new Set(builtinModules.map((name) => name.replace(/^node:/, '')));
const ALLOWED_EXTERNAL_IMPORTS = new Set(['node:process']);
const LICENSE_FILE_RE = /^(?:licen[cs]e|copying|notice)(?:\..+)?$/i;

function normalizeLf(text) {
  return String(text).replace(/\r\n?/g, '\n');
}

function normalizeGeneratedText(text) {
  return `${normalizeLf(text).replace(/[ \t]+$/gm, '').replace(/\n+$/, '')}\n`;
}

function sha256(bytes) {
  return crypto.createHash('sha256').update(bytes).digest('hex');
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function lockVersion(lock, packageName) {
  const key = `node_modules/${packageName}`;
  const version = lock?.packages?.[key]?.version;
  if (typeof version !== 'string' || !version) {
    throw new Error(`generate-dev-mcp-runtime: ${LOCK_REL} has no exact version for ${key}`);
  }
  return version;
}

function packageIdentityFromInput(inputPath) {
  const normalized = String(inputPath).replace(/\\/g, '/');
  const marker = '/node_modules/';
  const withLeadingSlash = normalized.startsWith('node_modules/') ? `/${normalized}` : normalized;
  const index = withLeadingSlash.lastIndexOf(marker);
  if (index === -1) return null;
  const tail = withLeadingSlash.slice(index + marker.length);
  const parts = tail.split('/');
  if (!parts[0]) return null;
  const name = parts[0].startsWith('@') ? `${parts[0]}/${parts[1] || ''}` : parts[0];
  if (!name || name.endsWith('/')) return null;
  const packageRootRel = withLeadingSlash.slice(1, index + marker.length) + name;
  return { name, packageRootRel };
}

function packageRepository(packageJson) {
  const value = packageJson?.repository;
  if (typeof value === 'string') return value;
  if (value && typeof value.url === 'string') return value.url;
  return null;
}

function findLegalFiles(packageRoot) {
  return fs.readdirSync(packageRoot, { withFileTypes: true })
    .filter((entry) => entry.isFile() && LICENSE_FILE_RE.test(entry.name))
    .map((entry) => entry.name)
    .sort((a, b) => a.localeCompare(b, 'en'));
}

export function buildLegalProjection({ metafile, repoRoot = REPO_ROOT } = {}) {
  const identities = new Map();
  for (const inputPath of Object.keys(metafile?.inputs ?? {})) {
    const identity = packageIdentityFromInput(inputPath);
    if (!identity) continue;
    const previous = identities.get(identity.name);
    if (previous && previous.packageRootRel !== identity.packageRootRel) {
      throw new Error(
        `generate-dev-mcp-runtime: multiple installed roots for ${identity.name}: `
          + `${previous.packageRootRel}, ${identity.packageRootRel}`,
      );
    }
    identities.set(identity.name, identity);
  }
  if (identities.size === 0) {
    throw new Error('generate-dev-mcp-runtime: esbuild metafile contains no bundled packages');
  }

  const packages = [...identities.values()].map(({ name, packageRootRel }) => {
    const packageRoot = path.resolve(repoRoot, packageRootRel);
    const packageJsonPath = path.join(packageRoot, 'package.json');
    const packageJson = readJson(packageJsonPath);
    const license = packageJson.license;
    if (typeof license !== 'string' || !ALLOWED_LICENSES.has(license)) {
      throw new Error(
        `generate-dev-mcp-runtime: ${packageJson.name || name}@${packageJson.version || '?'} `
          + `has unsupported license ${JSON.stringify(license)}`,
      );
    }
    const legalFileNames = findLegalFiles(packageRoot);
    if (legalFileNames.length === 0) {
      throw new Error(
        `generate-dev-mcp-runtime: ${packageJson.name || name}@${packageJson.version || '?'} `
          + 'has no LICENSE/COPYING/NOTICE file',
      );
    }
    const legalFiles = legalFileNames.map((file) => {
      const text = normalizeLf(fs.readFileSync(path.join(packageRoot, file), 'utf8')).trim();
      if (!text) throw new Error(`generate-dev-mcp-runtime: ${name}/${file} is empty`);
      return { file, text };
    });
    return {
      name: packageJson.name || name,
      version: packageJson.version,
      license,
      legalFiles,
      repository: packageRepository(packageJson),
    };
  }).sort((a, b) => a.name.localeCompare(b.name, 'en') || a.version.localeCompare(b.version, 'en'));

  const lines = [
    'JustSearch justsearch-dev MCP generated runtime — third-party notices',
    '',
    `Generated by scripts/dev/generate-dev-mcp-runtime.mjs using esbuild ${esbuildVersion}.`,
    'This file covers every third-party package represented in runtime.generated.mjs.',
    '',
  ];
  for (const pkg of packages) {
    lines.push('================================================================================');
    lines.push(`${pkg.name}@${pkg.version}`);
    lines.push(`License: ${pkg.license}`);
    if (pkg.repository) lines.push(`Repository: ${pkg.repository}`);
    for (const legalFile of pkg.legalFiles) {
      lines.push(`Legal file: ${legalFile.file}`);
      lines.push('--------------------------------------------------------------------------------');
      lines.push(legalFile.text);
    }
    lines.push('');
  }
  return { text: `${lines.join('\n')}\n`, packages };
}

function assertExternalImports(metafile) {
  const external = [];
  for (const output of Object.values(metafile?.outputs ?? {})) {
    for (const imported of output.imports ?? []) {
      if (imported.external) external.push(imported.path);
    }
  }
  const unique = [...new Set(external)].sort();
  const nonBuiltin = unique.filter((specifier) => !specifier.startsWith('node:'));
  if (nonBuiltin.length > 0) {
    throw new Error(
      `generate-dev-mcp-runtime: non-builtin external import(s): ${nonBuiltin.join(', ')}`,
    );
  }
  const unknownBuiltins = unique.filter(
    (specifier) => specifier.startsWith('node:') && !NODE_BUILTINS.has(specifier.slice('node:'.length)),
  );
  if (unknownBuiltins.length > 0) {
    throw new Error(
      `generate-dev-mcp-runtime: unknown node builtin external import(s): ${unknownBuiltins.join(', ')}`,
    );
  }
  const unapproved = unique.filter((specifier) => !ALLOWED_EXTERNAL_IMPORTS.has(specifier));
  if (unapproved.length > 0) {
    throw new Error(
      `generate-dev-mcp-runtime: unapproved external import(s): ${unapproved.join(', ')}`,
    );
  }
  return unique;
}

export async function generateDevMcpRuntime({ repoRoot = REPO_ROOT, entryRel = ENTRY_REL } = {}) {
  const lock = readJson(path.join(repoRoot, LOCK_REL));
  const sdkVersion = lockVersion(lock, '@modelcontextprotocol/sdk');
  const zodVersion = lockVersion(lock, 'zod');
  const lockedEsbuildVersion = lockVersion(lock, 'esbuild');
  if (lockedEsbuildVersion !== esbuildVersion) {
    throw new Error(
      `generate-dev-mcp-runtime: loaded esbuild ${esbuildVersion}, lockfile requires ${lockedEsbuildVersion}`,
    );
  }
  const header = [
    '// GENERATED FILE — DO NOT EDIT.',
    '// Source: scripts/dev/justsearch-dev-mcp/runtime-entry.mjs',
    '// Regenerate: node scripts/dev/generate-dev-mcp-runtime.mjs',
    `// Locked inputs: @modelcontextprotocol/sdk@${sdkVersion}, zod@${zodVersion}, esbuild@${esbuildVersion}`,
  ].join('\n');

  const result = await build({
    absWorkingDir: repoRoot,
    entryPoints: [entryRel],
    outfile: RUNTIME_REL,
    bundle: true,
    platform: 'node',
    format: 'esm',
    packages: 'bundle',
    treeShaking: true,
    minify: false,
    sourcemap: false,
    charset: 'utf8',
    legalComments: 'none',
    metafile: true,
    write: false,
    logLevel: 'silent',
    banner: { js: header },
  });
  if (result.outputFiles?.length !== 1) {
    throw new Error(`generate-dev-mcp-runtime: expected one output, got ${result.outputFiles?.length ?? 0}`);
  }
  // Package sources can carry trailing spaces even though the generated program is stable. Keep
  // tracked output compatible with the repository's diff hygiene and exactly one final newline.
  const bundle = Buffer.from(normalizeGeneratedText(result.outputFiles[0].text), 'utf8');
  const externalImports = assertExternalImports(result.metafile);
  const legal = buildLegalProjection({ metafile: result.metafile, repoRoot });
  return {
    bundle,
    legal: Buffer.from(normalizeGeneratedText(legal.text), 'utf8'),
    packages: legal.packages,
    externalImports,
    sha256: sha256(bundle),
  };
}

async function writeAtomic(file, bytes) {
  await fsp.mkdir(path.dirname(file), { recursive: true });
  const temporary = `${file}.${process.pid}.tmp`;
  await fsp.writeFile(temporary, bytes);
  try {
    await fsp.rename(temporary, file);
  } catch (error) {
    if (process.platform !== 'win32') throw error;
    await fsp.rm(file, { force: true });
    await fsp.rename(temporary, file);
  } finally {
    await fsp.rm(temporary, { force: true });
  }
}

export function compareGeneratedOutput({ expected, actual, label }) {
  if (Buffer.compare(Buffer.from(expected), Buffer.from(actual)) === 0) return null;
  return `${label} is stale (expected sha256=${sha256(expected)}, actual sha256=${sha256(actual)})`;
}

export async function checkGeneratedRuntime({ repoRoot = REPO_ROOT, generated } = {}) {
  const next = generated ?? await generateDevMcpRuntime({ repoRoot });
  const errors = [];
  for (const [rel, expected] of [[RUNTIME_REL, next.bundle], [LEGAL_REL, next.legal]]) {
    const absolute = path.join(repoRoot, rel);
    let actual;
    try {
      actual = await fsp.readFile(absolute);
    } catch (error) {
      errors.push(`${rel} is missing or unreadable: ${error?.message || error}`);
      continue;
    }
    const mismatch = compareGeneratedOutput({ expected, actual, label: rel });
    if (mismatch) errors.push(mismatch);
  }
  return { ok: errors.length === 0, errors, generated: next };
}

async function main() {
  const check = process.argv.slice(2).includes('--check');
  const generated = await generateDevMcpRuntime();
  if (check) {
    const result = await checkGeneratedRuntime({ generated });
    if (!result.ok) {
      console.error('generate-dev-mcp-runtime: FAIL');
      for (const error of result.errors) console.error(`- ${error}`);
      process.exitCode = 1;
      return;
    }
    console.log(
      `generate-dev-mcp-runtime: OK (${generated.bundle.length} bytes, ${generated.packages.length} packages, `
        + `sha256=${generated.sha256})`,
    );
    return;
  }

  await writeAtomic(path.join(REPO_ROOT, RUNTIME_REL), generated.bundle);
  await writeAtomic(path.join(REPO_ROOT, LEGAL_REL), generated.legal);
  console.log(
    `generate-dev-mcp-runtime: wrote ${RUNTIME_REL} and ${LEGAL_REL} `
      + `(${generated.packages.length} packages, sha256=${generated.sha256})`,
  );
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  main().catch((error) => {
    console.error(`generate-dev-mcp-runtime: FAIL: ${error?.stack || error}`);
    process.exitCode = 1;
  });
}
