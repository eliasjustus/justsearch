import assert from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { deflateRawSync } from 'node:zlib';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
  checkAsset,
  listZipEntryNames,
  missingRequiredFiles,
  readRequiredDlls,
} from './check-ort-native-asset.mjs';

/**
 * Builds a real (tiny) zip archive from {name: contentString}. Written by hand rather than shelled
 * out to a zip tool so the test runs identically on any machine — and so the reader under test is
 * exercised against bytes it did not produce.
 */
function buildZip(files) {
  const localParts = [];
  const centralParts = [];
  let offset = 0;
  for (const [name, content] of Object.entries(files)) {
    const nameBuf = Buffer.from(name, 'utf8');
    const raw = Buffer.from(content, 'utf8');
    const comp = deflateRawSync(raw);
    const crc = crc32(raw);

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4); // version needed
    local.writeUInt16LE(0, 6); // flags
    local.writeUInt16LE(8, 8); // deflate
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(comp.length, 18);
    local.writeUInt32LE(raw.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    localParts.push(local, nameBuf, comp);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(8, 10);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(comp.length, 20);
    central.writeUInt32LE(raw.length, 24);
    central.writeUInt16LE(nameBuf.length, 28);
    central.writeUInt32LE(offset, 42);
    centralParts.push(central, nameBuf);

    offset += local.length + nameBuf.length + comp.length;
  }
  const localBuf = Buffer.concat(localParts);
  const centralBuf = Buffer.concat(centralParts);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(Object.keys(files).length, 8);
  eocd.writeUInt16LE(Object.keys(files).length, 10);
  eocd.writeUInt32LE(centralBuf.length, 12);
  eocd.writeUInt32LE(localBuf.length, 16);
  return Buffer.concat([localBuf, centralBuf, eocd]);
}

function crc32(buf) {
  let c = ~0;
  for (const byte of buf) {
    c ^= byte;
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
  }
  return (~c) >>> 0;
}

const REQUIRED = [
  'onnxruntime.dll',
  'onnxruntime4j_jni.dll',
  'onnxruntime_providers_shared.dll',
  'onnxruntime_providers_cuda.dll',
];

function fullPack(prefix = '') {
  const files = {};
  for (const dll of REQUIRED) files[prefix + dll] = 'MZ-fake';
  files[prefix + 'ort-native-version.txt'] = '1.24.3';
  return files;
}

test('the required DLL set comes from OrtCudaHelper, not a second hardcoded list', () => {
  const { dlls, marker } = readRequiredDlls();
  assert.deepEqual(dlls, REQUIRED);
  assert.equal(marker, 'ort-native-version.txt');
});

test('a complete flat archive passes', () => {
  const names = listZipEntryNames(buildZip(fullPack()));
  assert.deepEqual(missingRequiredFiles(names, REQUIRED), []);
});

test('a complete archive wrapped in one top-level directory passes', () => {
  const names = listZipEntryNames(buildZip(fullPack('cuda12/')));
  assert.deepEqual(missingRequiredFiles(names, REQUIRED), []);
});

test('the round-11 shape — the CUDA provider DLL missing — is reported by name', () => {
  const files = fullPack();
  delete files['onnxruntime_providers_cuda.dll'];
  const names = listZipEntryNames(buildZip(files));
  assert.deepEqual(missingRequiredFiles(names, REQUIRED), ['onnxruntime_providers_cuda.dll']);
});

test('several missing DLLs are all listed', () => {
  const files = fullPack();
  delete files['onnxruntime.dll'];
  delete files['onnxruntime4j_jni.dll'];
  const names = listZipEntryNames(buildZip(files));
  assert.deepEqual(missingRequiredFiles(names, REQUIRED), [
    'onnxruntime.dll',
    'onnxruntime4j_jni.dll',
  ]);
});

test('a DLL nested more than one directory deep does not count as present', () => {
  // OrtCudaHelper resolves each DLL directly under the pack dir, so a deeply-nested copy would not
  // be found at runtime either — accepting it here would make the check green for a broken pack.
  const files = fullPack('cuda12/');
  delete files['cuda12/onnxruntime_providers_cuda.dll'];
  files['cuda12/nested/onnxruntime_providers_cuda.dll'] = 'MZ-fake';
  const names = listZipEntryNames(buildZip(files));
  assert.deepEqual(missingRequiredFiles(names, REQUIRED), ['onnxruntime_providers_cuda.dll']);
});

test('checkAsset reads a real file from disk end to end', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'ort-asset-'));
  const good = path.join(dir, 'ort-native-cuda12-v1.24.3.zip');
  await writeFile(good, buildZip(fullPack()));
  const okResult = checkAsset(good);
  assert.deepEqual(okResult.missing, []);
  assert.equal(okResult.markerMissing, false);

  const files = fullPack();
  delete files['onnxruntime_providers_cuda.dll'];
  delete files['ort-native-version.txt'];
  const bad = path.join(dir, 'broken.zip');
  await writeFile(bad, buildZip(files));
  const badResult = checkAsset(bad);
  assert.deepEqual(badResult.missing, ['onnxruntime_providers_cuda.dll']);
  assert.equal(badResult.markerMissing, true);
});

test('a non-zip file fails loudly rather than reporting "nothing missing"', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'ort-asset-'));
  const notZip = path.join(dir, 'not-a-zip.zip');
  await writeFile(notZip, Buffer.from('this is not a zip archive at all'));
  assert.throws(() => checkAsset(notZip), /end-of-central-directory/);
});
