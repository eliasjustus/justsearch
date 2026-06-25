#!/usr/bin/env node
/**
 * EvidenceBundle v1 validator (structure + hashing + invariants).
 *
 * Contract:
 * - docs/tempdocs/13/00-evidencebundle-v1-spec.md
 *
 * Usage:
 *   node scripts/evidence/validate-evidencebundle-v1.mjs <bundleDir>
 *
 * Notes:
 * - Uses only repo-root deps (Ajv) + Node stdlib.
 * - Focuses on invariants that prevent drift: layout, hashing/path rules, placeholder rules,
 *   and browser-network scope.
 */

import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { createHash } from 'node:crypto';
import Ajv from 'ajv';
import addFormats from 'ajv-formats';

function printUsageAndExit(code = 1) {
  // eslint-disable-next-line no-console
  console.error('Usage: node scripts/evidence/validate-evidencebundle-v1.mjs <bundleDir>');
  process.exit(code);
}

function toPosixRelPath(relPath) {
  return relPath.split(path.sep).join('/');
}

function ensureRelativeSafe(relPosixPath) {
  if (!relPosixPath || typeof relPosixPath !== 'string') throw new Error('invalid relative path');
  if (relPosixPath.includes('\\')) throw new Error(`invalid path separator in path: ${relPosixPath}`);
  if (relPosixPath.startsWith('/')) throw new Error(`path must be relative: ${relPosixPath}`);
  if (/^[A-Za-z]:/.test(relPosixPath)) throw new Error(`path must not contain drive prefix: ${relPosixPath}`);
  const parts = relPosixPath.split('/');
  if (parts.some((p) => p === '..')) throw new Error(`path must not contain '..': ${relPosixPath}`);
  return relPosixPath;
}

function canonicalizeRelPath(input) {
  const raw = String(input ?? '');
  const p = raw.replace(/\\/g, '/').replace(/^\.\//, '');
  // Allow trailing slash for directory-style entries in missing_artifacts; normalize away.
  const trimmed = p.endsWith('/') ? p.slice(0, -1) : p;
  return ensureRelativeSafe(trimmed);
}

async function sha256FileHex(filePath) {
  const buf = await fsp.readFile(filePath);
  return {
    sha256: createHash('sha256').update(buf).digest('hex'),
    bytes: buf.byteLength,
  };
}

async function listFilesRecursive(rootDir) {
  const out = [];
  const walk = async (dir) => {
    const entries = await fsp.readdir(dir, { withFileTypes: true });
    for (const ent of entries) {
      const full = path.join(dir, ent.name);
      if (ent.isDirectory()) {
        await walk(full);
        continue;
      }
      if (ent.isFile()) out.push(full);
    }
  };
  await walk(rootDir);
  return out;
}

function jsonParseOrThrow(text, context) {
  try {
    return JSON.parse(text);
  } catch (err) {
    throw new Error(`Invalid JSON (${context}): ${err?.message || String(err)}`);
  }
}

function isApiScopedUrl(urlStr) {
  try {
    const u = new URL(urlStr);
    if (u.pathname === '/api' || u.pathname.startsWith('/api/')) return true;
    if (u.pathname === '/version') return true;
    return false;
  } catch {
    return false;
  }
}

function fail(errors) {
  // eslint-disable-next-line no-console
  console.error(['EvidenceBundle v1 validation FAILED:', ...errors.map((e) => `- ${e}`)].join('\n'));
  process.exit(1);
}

async function main() {
  const bundleDirArg = process.argv[2];
  if (!bundleDirArg || bundleDirArg === '--help' || bundleDirArg === '-h') printUsageAndExit(0);

  const bundleDir = path.resolve(process.cwd(), bundleDirArg);
  const errors = [];

  if (!fs.existsSync(bundleDir) || !fs.statSync(bundleDir).isDirectory()) {
    fail([`Bundle dir does not exist or is not a directory: ${bundleDir}`]);
  }

  // run-metadata.json is the only unconditional requirement (we can't validate without it).
  const runMetaPath = path.join(bundleDir, 'run-metadata.json');
  if (!fs.existsSync(runMetaPath) || !fs.statSync(runMetaPath).isFile()) {
    fail([`Missing required file: run-metadata.json`]);
  }

  // Load run-metadata.json
  let meta = null;
  try {
    const metaText = await fsp.readFile(runMetaPath, 'utf8');
    meta = jsonParseOrThrow(metaText, 'run-metadata.json');
  } catch (err) {
    errors.push(err?.message || String(err));
    fail(errors);
  }

  // Minimal schema validation (structural); we intentionally keep this compact and invariant-focused.
  const ajv = new Ajv({ allErrors: true, allowUnionTypes: true });
  addFormats(ajv);

  const schema = {
    type: 'object',
    additionalProperties: true,
    required: ['schema', 'evidence_bundle_version', 'scenario', 'run_id', 'started_at', 'finished_at', 'status', 'inputs', 'harness', 'determinism_budget', 'artifacts'],
    properties: {
      schema: { const: 'run-metadata.v1' },
      evidence_bundle_version: { const: 'EvidenceBundle/v1' },
      scenario: { type: 'string', minLength: 1 },
      run_id: { type: 'string', minLength: 1 },
      started_at: { type: 'string' },
      finished_at: { type: 'string' },
      status: { enum: ['passed', 'failed'] },
      inputs: {
        type: 'object',
        additionalProperties: true,
        required: ['api_base_url'],
        properties: {
          api_base_url: { type: 'string', minLength: 1 },
          ui_url: { type: 'string' },
        },
      },
      missing_artifacts: { type: 'array', items: { type: 'string' } },
      placeholder_artifacts: {
        type: 'array',
        items: {
          type: 'object',
          additionalProperties: true,
          required: ['path', 'reason'],
          properties: {
            path: { type: 'string', minLength: 1 },
            reason: { type: 'string', minLength: 1 },
          },
        },
      },
      artifacts: {
        type: 'array',
        items: {
          type: 'object',
          additionalProperties: true,
          required: ['type', 'path', 'sha256', 'bytes'],
          properties: {
            type: { type: 'string', minLength: 1 },
            path: { type: 'string', minLength: 1 },
            sha256: { type: 'string', pattern: '^[0-9a-f]{64}$' },
            bytes: { type: 'number' },
          },
        },
      },
    },
  };

  const validate = ajv.compile(schema);
  if (!validate(meta)) {
    for (const e of validate.errors || []) {
      errors.push(`run-metadata.json schema error: ${e.instancePath || '(root)'} ${e.message}`);
    }
  }

  // Status invariants
  const missingRaw = Array.isArray(meta.missing_artifacts) ? meta.missing_artifacts : [];
  const missing = [];
  for (const m of missingRaw) {
    try {
      missing.push(canonicalizeRelPath(m));
    } catch (err) {
      errors.push(`Invalid missing_artifacts entry '${m}': ${err?.message || String(err)}`);
    }
  }
  if (meta.status === 'passed' && missing.length > 0) {
    errors.push(`status=passed but missing_artifacts is non-empty: ${missing.join(', ')}`);
  }

  // Required paths semantics:
  // - status=passed: all required paths MUST exist.
  // - status=failed: required paths MAY be missing, but every missing required path MUST be listed in missing_artifacts.
  const required = [
    'api-status.json',
    'api-health.json',
    'browser-console.json',
    'browser-network.json',
    'diagnostics-export.json',
    'diagnostics.zip',
    'ui-screenshots',
  ];

  const requiredMissing = [];
  for (const rel of required) {
    const full = path.join(bundleDir, rel);
    if (!fs.existsSync(full)) {
      requiredMissing.push(rel);
      continue;
    }
    if (rel === 'ui-screenshots') {
      if (!fs.statSync(full).isDirectory()) errors.push(`Expected directory: ${rel}`);
    } else {
      if (!fs.statSync(full).isFile()) errors.push(`Expected file: ${rel}`);
    }
  }

  if (meta.status === 'passed') {
    for (const rel of requiredMissing) errors.push(`Missing required path: ${rel}`);
  } else if (meta.status === 'failed') {
    const missingSet = new Set(missing);
    for (const rel of requiredMissing) {
      if (!missingSet.has(rel)) {
        errors.push(`status=failed but missing required path is not listed in missing_artifacts: ${rel}`);
      }
    }
    // Optional consistency check: missing_artifacts should not list paths that actually exist.
    for (const m of missing) {
      const full = path.join(bundleDir, m.split('/').join(path.sep));
      if (fs.existsSync(full)) {
        errors.push(`missing_artifacts lists a path that exists: ${m}`);
      }
    }
  }

  // Path invariants + hashing invariants
  const artifacts = Array.isArray(meta.artifacts) ? meta.artifacts : [];

  // run-metadata.json MUST NOT appear in artifacts
  if (artifacts.some((a) => a?.path === 'run-metadata.json')) {
    errors.push('run-metadata.json must not appear in run-metadata.json.artifacts[]');
  }

  // artifacts[] SHOULD be sorted by path (warn-level, but we treat as error to keep bundles diffable).
  for (let i = 1; i < artifacts.length; i += 1) {
    const prev = artifacts[i - 1]?.path;
    const cur = artifacts[i]?.path;
    if (typeof prev === 'string' && typeof cur === 'string' && prev.localeCompare(cur) > 0) {
      errors.push('run-metadata.json.artifacts[] must be sorted lexicographically by path');
      break;
    }
  }

  // All artifact paths must be normalized relpaths using forward slashes.
  for (const a of artifacts) {
    try {
      ensureRelativeSafe(a.path);
    } catch (err) {
      errors.push(`Invalid artifacts[].path '${a?.path}': ${err?.message || String(err)}`);
    }
  }

  // Every file in bundle (excluding run-metadata.json) must appear in artifacts[] with correct sha256+bytes.
  let files = [];
  try {
    files = await listFilesRecursive(bundleDir);
  } catch (err) {
    errors.push(`Failed to list files in bundle: ${err?.message || String(err)}`);
  }

  const relFiles = files
    .map((abs) => path.relative(bundleDir, abs))
    .map((rel) => ensureRelativeSafe(toPosixRelPath(rel)))
    .filter((rel) => rel !== 'run-metadata.json');

  const artifactByPath = new Map();
  for (const a of artifacts) artifactByPath.set(a.path, a);

  for (const rel of relFiles) {
    const a = artifactByPath.get(rel);
    if (!a) {
      errors.push(`File missing from artifacts[]: ${rel}`);
      continue;
    }
    try {
      const { sha256, bytes } = await sha256FileHex(path.join(bundleDir, rel.split('/').join(path.sep)));
      if (a.sha256 !== sha256) errors.push(`sha256 mismatch for ${rel}: expected ${sha256}, got ${a.sha256}`);
      if (Number(a.bytes) !== bytes) errors.push(`bytes mismatch for ${rel}: expected ${bytes}, got ${a.bytes}`);
    } catch (err) {
      errors.push(`Failed to hash ${rel}: ${err?.message || String(err)}`);
    }
  }

  // Placeholder invariants: file exists and has a hash entry.
  const placeholders = Array.isArray(meta.placeholder_artifacts) ? meta.placeholder_artifacts : [];
  for (const p of placeholders) {
    if (!p?.path) continue;
    try {
      ensureRelativeSafe(p.path);
    } catch (err) {
      errors.push(`Invalid placeholder_artifacts[].path '${p?.path}': ${err?.message || String(err)}`);
      continue;
    }
    const full = path.join(bundleDir, p.path.split('/').join(path.sep));
    if (!fs.existsSync(full) || !fs.statSync(full).isFile()) {
      errors.push(`placeholder_artifacts path does not exist as file: ${p.path}`);
    }
    if (!artifactByPath.has(p.path)) {
      errors.push(`placeholder_artifacts path missing from artifacts[]: ${p.path}`);
    }
  }

  // Browser-network invariants: API-only scope.
  try {
    const netText = await fsp.readFile(path.join(bundleDir, 'browser-network.json'), 'utf8');
    const net = jsonParseOrThrow(netText, 'browser-network.json');
    if (!Array.isArray(net)) {
      errors.push('browser-network.json must be a JSON array');
    } else {
      for (const ev of net) {
        const url = ev?.url;
        if (typeof url !== 'string') {
          errors.push('browser-network.json entry missing url');
          continue;
        }
        if (!isApiScopedUrl(url)) {
          errors.push(`browser-network.json contains out-of-scope url (expected /api* or /version): ${url}`);
        }
      }
    }
  } catch (err) {
    errors.push(err?.message || String(err));
  }

  if (errors.length > 0) fail(errors);

  // eslint-disable-next-line no-console
  console.log('EvidenceBundle v1 validation OK:', bundleDir);
}

main().catch((err) => {
  // eslint-disable-next-line no-console
  console.error('Validator crashed:', err);
  process.exit(2);
});


