#!/usr/bin/env node
/**
 * Deterministic MCPB packer (tempdoc 726 keystone).
 *
 * Replaces `npx @anthropic-ai/mcpb pack`, which is NONDETERMINISTIC (it embeds
 * per-pack mtimes), with a byte-stable zip built from source. Determinism is what
 * lets the bundle stop being a committed binary: the consistency gate re-packs from
 * source and compares the hash to server.json.fileSha256, so "edited source, forgot
 * to re-hash" is caught structurally (that was v1's deferred freshness gap).
 *
 * Determinism guarantees:
 *  - STORED entries (compression method 0) — no deflate, so no cross-zlib-version
 *    byte variance between local and CI. A 19 KB bundle needs no compression.
 *  - Fixed DOS mtime (1980-01-01 00:00:00), sorted entries, no extra fields/comment.
 *  - CRC-32 computed here (no dependency on zlib.crc32 availability).
 *
 * Contents: manifest.json + server/** only (the .mcpbignore definition).
 *
 * CLI:
 *   node scripts/ci/pack-mcpb.mjs                 -> print the bundle's sha256
 *   node scripts/ci/pack-mcpb.mjs <outfile>       -> write the bundle to <outfile>
 *   node scripts/ci/pack-mcpb.mjs --sync          -> write the hash into server.json.fileSha256
 * Test override: CHECK_MCPB_ROOT=<dir>.
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const MCPB_DIR = 'packaging/mcpb';
const SERVER_JSON_REL = `${MCPB_DIR}/server.json`;
const DOS_DATE = 0x0021; // 1980-01-01
const DOS_TIME = 0x0000; // 00:00:00

export function repoRootFromCwd() {
  const override = process.env.CHECK_MCPB_ROOT;
  if (override) return override;
  const markers = ['settings.gradle.kts', 'build.gradle.kts', '.git'];
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (markers.some((m) => fs.existsSync(path.join(dir, m)))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) break;
  }
  return process.cwd();
}

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/** manifest.json + server/** (recursive), sorted by archive name. */
function collectEntries(repoRoot) {
  const base = path.join(repoRoot, MCPB_DIR);
  const entries = [{ name: 'manifest.json', abs: path.join(base, 'manifest.json') }];
  const serverBase = path.join(base, 'server');
  const walk = (dir, rel) => {
    const items = fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => (a.name < b.name ? -1 : 1));
    for (const e of items) {
      const abs = path.join(dir, e.name);
      const arc = rel ? `${rel}/${e.name}` : e.name;
      if (e.isDirectory()) walk(abs, arc);
      else entries.push({ name: `server/${arc}`, abs });
    }
  };
  if (fs.existsSync(serverBase)) walk(serverBase, '');
  entries.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  return entries;
}

/** Build the deterministic .mcpb zip. Returns { buffer, sha256, entries }. */
export function packMcpb(repoRoot = repoRootFromCwd()) {
  const entries = collectEntries(repoRoot);
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const ent of entries) {
    const data = fs.readFileSync(ent.abs);
    const nameBuf = Buffer.from(ent.name, 'utf8');
    const crc = crc32(data);

    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0); // local file header sig
    lh.writeUInt16LE(20, 4); // version needed
    lh.writeUInt16LE(0, 6); // flags
    lh.writeUInt16LE(0, 8); // method: STORED
    lh.writeUInt16LE(DOS_TIME, 10);
    lh.writeUInt16LE(DOS_DATE, 12);
    lh.writeUInt32LE(crc, 14);
    lh.writeUInt32LE(data.length, 18); // compressed size (== uncompressed for STORED)
    lh.writeUInt32LE(data.length, 22); // uncompressed size
    lh.writeUInt16LE(nameBuf.length, 26);
    lh.writeUInt16LE(0, 28); // extra length
    locals.push(lh, nameBuf, data);

    const ch = Buffer.alloc(46);
    ch.writeUInt32LE(0x02014b50, 0); // central dir header sig
    ch.writeUInt16LE(20, 4); // version made by
    ch.writeUInt16LE(20, 6); // version needed
    ch.writeUInt16LE(0, 8); // flags
    ch.writeUInt16LE(0, 10); // method: STORED
    ch.writeUInt16LE(DOS_TIME, 12);
    ch.writeUInt16LE(DOS_DATE, 14);
    ch.writeUInt32LE(crc, 16);
    ch.writeUInt32LE(data.length, 20);
    ch.writeUInt32LE(data.length, 24);
    ch.writeUInt16LE(nameBuf.length, 28);
    ch.writeUInt16LE(0, 30); // extra length
    ch.writeUInt16LE(0, 32); // comment length
    ch.writeUInt16LE(0, 34); // disk number start
    ch.writeUInt16LE(0, 36); // internal attrs
    ch.writeUInt32LE(0, 38); // external attrs
    ch.writeUInt32LE(offset, 42); // local header offset
    centrals.push(ch, nameBuf);

    offset += lh.length + nameBuf.length + data.length;
  }
  const localPart = Buffer.concat(locals);
  const centralPart = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0); // EOCD sig
  eocd.writeUInt16LE(0, 4); // this disk
  eocd.writeUInt16LE(0, 6); // cd start disk
  eocd.writeUInt16LE(entries.length, 8); // entries this disk
  eocd.writeUInt16LE(entries.length, 10); // total entries
  eocd.writeUInt32LE(centralPart.length, 12); // cd size
  eocd.writeUInt32LE(localPart.length, 16); // cd offset
  eocd.writeUInt16LE(0, 20); // comment length

  const buffer = Buffer.concat([localPart, centralPart, eocd]);
  const sha256 = crypto.createHash('sha256').update(buffer).digest('hex');
  return { buffer, sha256, entries: entries.map((e) => e.name) };
}

function syncServerJson(repoRoot, sha256) {
  const p = path.join(repoRoot, SERVER_JSON_REL);
  const raw = fs.readFileSync(p, 'utf8');
  const next = raw.replace(/("fileSha256"\s*:\s*")[0-9a-fA-F]{64}(")/, `$1${sha256}$2`);
  if (next === raw) {
    if (!/"fileSha256"\s*:/.test(raw)) throw new Error(`${SERVER_JSON_REL} has no fileSha256 field to sync`);
    return false; // already up to date
  }
  fs.writeFileSync(p, next);
  return true;
}

function main() {
  const args = process.argv.slice(2);
  const repoRoot = repoRootFromCwd();
  const { buffer, sha256, entries } = packMcpb(repoRoot);

  if (args.includes('--sync')) {
    const changed = syncServerJson(repoRoot, sha256);
    console.log(
      changed
        ? `pack-mcpb: --sync updated ${SERVER_JSON_REL} fileSha256 -> ${sha256}`
        : `pack-mcpb: --sync no change (${SERVER_JSON_REL} already ${sha256})`,
    );
    return;
  }

  const outfile = args.find((a) => !a.startsWith('--'));
  if (outfile) {
    fs.writeFileSync(outfile, buffer);
    console.log(`pack-mcpb: wrote ${outfile} (${buffer.length} bytes, sha256 ${sha256}, ${entries.length} entries)`);
  } else {
    console.log(sha256);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
