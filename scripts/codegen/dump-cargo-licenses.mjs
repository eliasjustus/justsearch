#!/usr/bin/env node
/**
 * Tempdoc 632 — dump the Tauri shell's Rust/Cargo dependency licenses for the NOTICE projection.
 *
 * The desktop shell (modules/shell/src-tauri) statically links its Cargo dependencies into the
 * shipped binary, so they are redistributed and must be attributed. `cargo metadata` is built-in
 * (no extra tooling), resolves against Cargo.lock, and carries each crate's SPDX `license`. Writes a
 * sorted {name, version, license}[] to build/cargo-licenses.json (mirrors the npm-dump pattern;
 * gen-notices.mjs reads this pre-generated dump rather than invoking cargo itself).
 *
 * Usage: node scripts/codegen/dump-cargo-licenses.mjs
 */
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const MANIFEST = join(REPO_ROOT, 'modules', 'shell', 'src-tauri', 'Cargo.toml');
const OUT = join(REPO_ROOT, 'build', 'cargo-licenses.json');

const raw = execFileSync(
  'cargo',
  ['metadata', '--format-version', '1', '--locked', '--manifest-path', MANIFEST],
  { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 },
);
const meta = JSON.parse(raw);
const crates = (meta.packages || [])
  .map((p) => ({ name: p.name, version: p.version, license: p.license || (p.license_file ? 'see license file' : 'UNKNOWN') }))
  .sort((a, b) => a.name.localeCompare(b.name) || a.version.localeCompare(b.version));

mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, JSON.stringify(crates, null, 0), 'utf8');
console.log(`dump-cargo-licenses: wrote ${crates.length} crates to ${OUT}`);
