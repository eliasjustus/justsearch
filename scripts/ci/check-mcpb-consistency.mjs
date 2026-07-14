#!/usr/bin/env node
/**
 * Fail-closed consistency gate for the MCPB release bundle.
 *
 * The MCPB's SHA-256 is a *published contract*: it lives in server.json
 * (`packages[0].fileSha256`, which the Official MCP Registry publish reads),
 * in the release's SHA256SUMS, and in the actual bundle bytes. MCP clients
 * fail-closed on a wrong fileSha256, so a drift silently breaks installs.
 * This gate refuses to let server.json and the committed bundle disagree.
 *
 * `mcpb pack` is nondeterministic (zip metadata), so the bundle is a committed,
 * reviewed artifact and is NEVER re-packed here — we only hash the committed
 * bytes and compare. (Tempdoc 726.)
 *
 * Checks:
 *  - PR-time (default): the committed bundle exists and
 *    sha256(bundle) === server.json.packages[0].fileSha256.
 *  - Release-path (`--release-version x.y.z`): additionally server.json.version
 *    === the version being cut, and the asset URL points at that tag's bundle.
 *
 * A content-freshness check (bundle payload vs tracked source) is a planned
 * fast-follow (726 §Derisk) — the mcpb-repack-hint is the interim backstop.
 *
 * Run: `node scripts/ci/check-mcpb-consistency.mjs [--release-version x.y.z]`
 *      (exits non-zero on failure). Test override: CHECK_MCPB_ROOT=<dir>.
 */

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

function repoRootFromCwd() {
  const override = process.env.CHECK_MCPB_ROOT;
  if (override) return override;
  const markers = ['settings.gradle.kts', 'build.gradle.kts', '.git'];
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (markers.some((marker) => fs.existsSync(path.join(dir, marker)))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) break;
  }
  return process.cwd();
}

function parseReleaseVersion(argv) {
  const i = argv.indexOf('--release-version');
  if (i === -1) return null;
  const v = argv[i + 1];
  if (!v || v.startsWith('--')) {
    return { error: '--release-version requires a value (e.g. --release-version 0.2.0)' };
  }
  return { version: v };
}

const BUNDLE_REL = 'packaging/mcpb/dist/justsearch-mcp.mcpb';
const SERVER_JSON_REL = 'packaging/mcpb/server.json';

function main() {
  const repoRoot = repoRootFromCwd();
  const errors = [];

  const bundlePath = path.join(repoRoot, BUNDLE_REL);
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
  }

  if (!fs.existsSync(bundlePath)) {
    // The bundle is a committed artifact (726 §B4) — it must be present.
    errors.push(
      `${BUNDLE_REL} is missing. It is a committed release artifact — build it with ` +
        '`npx -y @anthropic-ai/mcpb pack packaging/mcpb packaging/mcpb/dist/justsearch-mcp.mcpb` ' +
        'and commit it (and update server.json.fileSha256 to its hash).',
    );
  } else if (pkg && typeof pkg.fileSha256 === 'string') {
    const actual = crypto.createHash('sha256').update(fs.readFileSync(bundlePath)).digest('hex');
    const declared = pkg.fileSha256.toLowerCase();
    if (actual !== declared) {
      errors.push(
        `Bundle hash drift: sha256(${BUNDLE_REL}) = ${actual} but ` +
          `${SERVER_JSON_REL} packages[0].fileSha256 = ${declared}. ` +
          'Re-run `mcpb pack`, then set fileSha256 to the new bundle hash (they must match, ' +
          'or MCP clients fail-closed on install).',
      );
    }
  }

  if (releaseArg && releaseArg.version) {
    const version = releaseArg.version;
    if (server.version !== version) {
      errors.push(
        `Release-version mismatch: server.json.version = ${JSON.stringify(server.version)} ` +
          `but the release being cut is ${version}. Bump server.json.version to match.`,
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

export { repoRootFromCwd, parseReleaseVersion };
