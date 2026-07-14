#!/usr/bin/env node
/**
 * Fail-closed consistency gate for the MCPB release bundle (tempdoc 726).
 *
 * The MCPB's SHA-256 is a *published contract*: it lives in server.json
 * (`packages[0].fileSha256`, read by the Official MCP Registry publish), in the
 * release SHA256SUMS, and in the bundle bytes. MCP clients fail-closed on a wrong
 * fileSha256, so a drift silently breaks installs.
 *
 * The bundle is NOT committed — it is built deterministically from source
 * (`pack-mcpb.mjs`, STORED zip, fixed mtime). This gate **re-packs from source**
 * and compares the hash to server.json.fileSha256, so it catches BOTH:
 *  - integrity: server.json's hash matches the real bundle, and
 *  - freshness: editing manifest.json / server/** without re-syncing the hash is a
 *    FAIL (the fresh pack no longer matches the stored hash) — v1's deferred gap.
 * Fix on failure: `node scripts/ci/pack-mcpb.mjs --sync` (then commit server.json).
 *
 * With `--release-version x.y.z` it also asserts server.json.version + asset URL
 * match the version being cut.
 *
 * Run: `node scripts/ci/check-mcpb-consistency.mjs [--release-version x.y.z]`
 *      (exits non-zero on failure). Test override: CHECK_MCPB_ROOT=<dir>.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

import { packMcpb, repoRootFromCwd } from './pack-mcpb.mjs';

const SERVER_JSON_REL = 'packaging/mcpb/server.json';

function parseReleaseVersion(argv) {
  const i = argv.indexOf('--release-version');
  if (i === -1) return null;
  const v = argv[i + 1];
  if (!v || v.startsWith('--')) {
    return { error: '--release-version requires a value (e.g. --release-version 0.2.0)' };
  }
  return { version: v };
}

function main() {
  const repoRoot = repoRootFromCwd();
  const errors = [];
  const serverJsonPath = path.join(repoRoot, SERVER_JSON_REL);

  const releaseArg = parseReleaseVersion(process.argv.slice(2));
  if (releaseArg && releaseArg.error) {
    console.error('check-mcpb-consistency: FAIL');
    console.error(`- ${releaseArg.error}`);
    process.exitCode = 1;
    return;
  }

  if (!fs.existsSync(serverJsonPath)) {
    console.error('check-mcpb-consistency: FAIL');
    console.error(`- ${SERVER_JSON_REL} is missing.`);
    process.exitCode = 1;
    return;
  }

  let server;
  try {
    server = JSON.parse(fs.readFileSync(serverJsonPath, 'utf8'));
  } catch (e) {
    console.error('check-mcpb-consistency: FAIL');
    console.error(`- ${SERVER_JSON_REL} is not valid JSON: ${e.message}`);
    process.exitCode = 1;
    return;
  }

  const pkg = Array.isArray(server.packages) ? server.packages[0] : undefined;
  if (!pkg || typeof pkg.fileSha256 !== 'string') {
    errors.push(`${SERVER_JSON_REL} has no packages[0].fileSha256 to verify against.`);
  } else {
    // Rebuild the bundle from source deterministically; the hash must match server.json.
    let fresh;
    try {
      fresh = packMcpb(repoRoot).sha256;
    } catch (e) {
      errors.push(`Could not pack the MCPB from source: ${e.message}`);
    }
    if (fresh) {
      const declared = pkg.fileSha256.toLowerCase();
      if (fresh !== declared) {
        errors.push(
          `MCPB hash drift: the source packs to ${fresh} but ${SERVER_JSON_REL} ` +
            `packages[0].fileSha256 = ${declared}. If you edited manifest.json / server/**, ` +
            'run `node scripts/ci/pack-mcpb.mjs --sync` and commit server.json (they must match, ' +
            'or MCP clients fail-closed on install).',
        );
      }
    }
  }

  if (releaseArg && releaseArg.version) {
    const version = releaseArg.version;
    if (server.version !== version) {
      errors.push(
        `Release-version mismatch: server.json.version = ${JSON.stringify(server.version)} ` +
          `but the release being cut is ${version}. Bump gradle.properties + run sync-version.ps1.`,
      );
    }
    const id = pkg && typeof pkg.identifier === 'string' ? pkg.identifier : '';
    if (!id.includes(`/v${version}/`)) {
      errors.push(
        `Release-asset URL mismatch: packages[0].identifier (${id || '<missing>'}) ` +
          `does not point at the /v${version}/ release tag.`,
      );
    }
    if (!id.endsWith('justsearch-mcp.mcpb')) {
      errors.push(
        `packages[0].identifier must end with the bundle filename justsearch-mcp.mcpb (got ${id || '<missing>'}).`,
      );
    }
  }

  if (errors.length === 0) {
    const scope = releaseArg && releaseArg.version ? ` (release ${releaseArg.version})` : '';
    console.log(`check-mcpb-consistency: OK${scope}`);
    return;
  }

  console.error('check-mcpb-consistency: FAIL');
  for (const error of errors) {
    console.error(`- ${error}`);
  }
  process.exitCode = 1;
}

main();

export { parseReleaseVersion };
