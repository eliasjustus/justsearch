#!/usr/bin/env node
/**
 * Tempdoc 842 §2.2/§2.4 — dev-side fetch for the compact chat-model package.
 *
 * The registry (`model-registry.v2.json`) is the system's model-identity authority (sha256,
 * size, license, targetDir); this script projects off it rather than forking a parallel "dev
 * models manifest" (§2.2). It is deliberately NOT routed through `AiInstallService` — 840 is
 * dismantling that class, and dev-side fetching needs none of its system-mutation duties. This
 * is a small, dependency-free script in the same family as `doctor.mjs` / `verify-prerequisites.mjs`,
 * which already read the registry directly.
 *
 * Downloads the `chat-compact` package's variant(s) + supportingFiles into
 * `<modelsDir>/compact/`, sha256-verified — the exact path `justsearch.dev.start` checks for
 * (dev-runner.cjs) when the effective chat profile is "compact". `models/compact/` may not
 * exist yet on a fresh checkout; `<modelsDir>/compact/Qwen3.5-4B-Q4_K_M.gguf` is the file the
 * dev runner's warning names this script as the remedy for.
 *
 * The `chat-compact` package itself may not exist yet in the registry while the companion Java
 * slice (which adds it) lands in parallel — that is reported as a clear, non-crashing error.
 *
 * Usage:
 *   node scripts/dev/fetch-compact-model.mjs [--dry-run]
 *
 * Dependency-free: Node built-ins only (fs, https, crypto, path, url).
 */
'use strict';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import https from 'node:https';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, '..', '..');
const dryRun = process.argv.includes('--dry-run');

const REGISTRY_REL = ['modules', 'configuration', 'src', 'main', 'resources', 'ai', 'model-registry.v2.json'];
const PACKAGE_ID = 'chat-compact';
const MAX_REDIRECTS = 5;
const DOWNLOAD_TIMEOUT_MS = 120_000;

/** Resolve the MAIN checkout root (worktrees carry a `.git` FILE pointing at the real gitdir). */
function resolveMainRepoRoot() {
  try {
    const dotGit = path.join(repoRoot, '.git');
    const st = fs.statSync(dotGit);
    if (st.isFile()) {
      const m = fs.readFileSync(dotGit, 'utf8').trim().match(/^gitdir:\s*(.+)$/);
      if (m) return path.resolve(repoRoot, m[1], '..', '..', '..');
    }
  } catch { /* not a worktree */ }
  return repoRoot;
}
const mainRepoRoot = resolveMainRepoRoot();

/**
 * Same precedence as dev-runner.cjs's resolveAiDevEnv: an explicit JUSTSEARCH_MODELS_DIR wins
 * unconditionally; otherwise prefer the main checkout's models/ (holds the real binaries — a
 * worktree's models/ tracks manifests only) over this checkout's own models/.
 */
function resolveModelsDir() {
  if (process.env.JUSTSEARCH_MODELS_DIR) return process.env.JUSTSEARCH_MODELS_DIR;
  const mainModels = path.join(mainRepoRoot, 'models');
  const localModels = path.join(repoRoot, 'models');
  if (fs.existsSync(mainModels)) return mainModels;
  if (fs.existsSync(localModels)) return localModels;
  return mainModels; // neither exists yet — fetch creates it under the main checkout, as usual
}

function readRegistry() {
  const p = path.join(repoRoot, ...REGISTRY_REL);
  let raw;
  try {
    raw = fs.readFileSync(p, 'utf8');
  } catch (err) {
    throw new Error(`Cannot read model registry at ${p}: ${err.message}`);
  }
  try {
    return JSON.parse(raw);
  } catch (err) {
    throw new Error(`Model registry at ${p} is not valid JSON: ${err.message}`);
  }
}

function findChatCompactPackage(registry) {
  const pkg = (registry.packages || []).find((p) => p.id === PACKAGE_ID);
  if (!pkg) {
    throw new Error(
      `No "${PACKAGE_ID}" package in the model registry ` +
      `(${path.join(...REGISTRY_REL)}). This is expected while the companion Java slice ` +
      `(tempdoc 842) that adds the package has not landed yet — nothing to fetch. ` +
      `Re-run this script once that package exists.`,
    );
  }
  return pkg;
}

/** Flatten a package's variants + supportingFiles into one list of downloadable files. */
function packageDownloadList(pkg) {
  const files = [];
  for (const v of pkg.variants || []) files.push(v);
  for (const f of pkg.supportingFiles || []) files.push(f);
  if (files.length === 0) {
    throw new Error(`"${PACKAGE_ID}" package declares no variants or supportingFiles — nothing to fetch.`);
  }
  return files;
}

/** GET a URL, following redirects (github releases / huggingface resolve URLs both 3xx). */
function httpsGetFollow(url, redirectsLeft = MAX_REDIRECTS) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, { timeout: DOWNLOAD_TIMEOUT_MS }, (res) => {
      const { statusCode, headers } = res;
      if (statusCode >= 300 && statusCode < 400 && headers.location) {
        res.resume(); // drain
        if (redirectsLeft <= 0) {
          reject(new Error(`Too many redirects fetching ${url}`));
          return;
        }
        const nextUrl = new URL(headers.location, url).toString();
        resolve(httpsGetFollow(nextUrl, redirectsLeft - 1));
        return;
      }
      if (statusCode !== 200) {
        res.resume();
        reject(new Error(`HTTP ${statusCode} fetching ${url}`));
        return;
      }
      resolve(res);
    });
    req.on('timeout', () => req.destroy(new Error(`Timed out fetching ${url}`)));
    req.on('error', reject);
  });
}

/** Stream a URL to `<targetPath>.part`, hashing as it goes; returns { sizeBytes, sha256 }. */
async function streamDownload(url, targetPath) {
  const partPath = `${targetPath}.part`;
  await fsp.mkdir(path.dirname(targetPath), { recursive: true });
  const res = await httpsGetFollow(url);
  const hash = crypto.createHash('sha256');
  let sizeBytes = 0;
  const out = fs.createWriteStream(partPath);
  await new Promise((resolve, reject) => {
    res.on('data', (chunk) => {
      hash.update(chunk);
      sizeBytes += chunk.length;
    });
    res.on('error', reject);
    out.on('error', reject);
    out.on('finish', resolve);
    res.pipe(out);
  });
  return { partPath, sizeBytes, sha256: hash.digest('hex') };
}

/** sha256 of a file via a stream — readFileSync would blow Node's ~2 GiB Buffer ceiling on the
 *  chat model itself (observed: `ERR_FS_FILE_TOO_LARGE` on a 2.7 GB file). */
function sha256HexOfFile(targetPath) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    const stream = fs.createReadStream(targetPath);
    stream.on('data', (chunk) => hash.update(chunk));
    stream.on('error', reject);
    stream.on('end', () => resolve(hash.digest('hex')));
  });
}

/** Does an already-present file match the registry's declared size + sha256? */
async function existingMatches(targetPath, expectedSizeBytes, expectedSha256Lower) {
  try {
    const st = fs.statSync(targetPath);
    if (typeof expectedSizeBytes === 'number' && st.size !== expectedSizeBytes) return false;
  } catch {
    return false; // doesn't exist
  }
  const actual = await sha256HexOfFile(targetPath);
  return actual === expectedSha256Lower;
}

async function fetchOne(file, targetDir) {
  const filename = file.filename;
  const targetPath = path.join(targetDir, filename);
  const expectedSha256Lower = String(file.sha256 || '').toLowerCase();
  const expectedSizeBytes = typeof file.sizeBytes === 'number' ? file.sizeBytes : undefined;

  if (!file.downloadUrl) {
    console.error(`  SKIP  ${filename} — no downloadUrl in registry`);
    return { filename, ok: false, skipped: true };
  }

  if (await existingMatches(targetPath, expectedSizeBytes, expectedSha256Lower)) {
    console.log(`  OK    ${filename} — already present, sha256 verified`);
    return { filename, ok: true, alreadyPresent: true };
  }

  if (dryRun) {
    console.log(`  PLAN  ${filename} <- ${file.downloadUrl}`);
    return { filename, ok: true, planned: true };
  }

  console.log(`  FETCH ${filename} <- ${file.downloadUrl}`);
  const { partPath, sizeBytes, sha256 } = await streamDownload(file.downloadUrl, targetPath);

  if (expectedSizeBytes != null && sizeBytes !== expectedSizeBytes) {
    await fsp.rm(partPath, { force: true });
    throw new Error(`${filename}: size mismatch (expected ${expectedSizeBytes}, got ${sizeBytes})`);
  }
  if (expectedSha256Lower && sha256 !== expectedSha256Lower) {
    await fsp.rm(partPath, { force: true });
    throw new Error(`${filename}: sha256 mismatch (expected ${expectedSha256Lower}, got ${sha256})`);
  }

  await fsp.rename(partPath, targetPath);
  console.log(`  DONE  ${filename} — verified (${sizeBytes} bytes)`);
  return { filename, ok: true };
}

async function main() {
  const registry = readRegistry();
  const pkg = findChatCompactPackage(registry);
  const files = packageDownloadList(pkg);

  const modelsDir = resolveModelsDir();
  const targetDir = path.join(modelsDir, 'compact');

  console.log(`[fetch-compact-model] package=${PACKAGE_ID} files=${files.length} targetDir=${targetDir}${dryRun ? ' (dry-run)' : ''}`);

  const results = [];
  for (const file of files) {
    // eslint-disable-next-line no-await-in-loop
    results.push(await fetchOne(file, targetDir));
  }

  const failed = results.filter((r) => !r.ok);
  if (failed.length > 0) {
    console.error(`[fetch-compact-model] ${failed.length} file(s) failed.`);
    process.exitCode = 1;
    return;
  }
  console.log(`[fetch-compact-model] ${dryRun ? 'dry-run complete' : 'all files present and verified'}.`);
}

main().catch((err) => {
  console.error(`[fetch-compact-model] ERROR: ${err.message}`);
  process.exitCode = 1;
});
