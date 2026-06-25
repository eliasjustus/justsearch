#!/usr/bin/env node

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import { spawnSync } from 'node:child_process';

const scriptDir = path.dirname(new URL(import.meta.url).pathname);
const normalizedScriptDir =
  process.platform === 'win32'
    ? scriptDir.replace(/^\/([A-Za-z]:)/, '$1')
    : scriptDir;
const converterScript = path.join(normalizedScriptDir, 'convert-mldr-en-to-beir.mjs');
const repoRoot = path.resolve(normalizedScriptDir, '..', '..');

function writeGzip(filePath, content) {
  fs.writeFileSync(filePath, zlib.gzipSync(Buffer.from(content, 'utf8')));
}

function readJsonl(filePath) {
  return fs
    .readFileSync(filePath, 'utf8')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => JSON.parse(line));
}

function main() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-mldr-convert-'));
  const sourceDir = path.join(tempRoot, 'source');
  const outDir = path.join(tempRoot, 'beir');
  fs.mkdirSync(sourceDir, { recursive: true });

  const corpusGz = path.join(sourceDir, 'corpus.jsonl.gz');
  const queriesGz = path.join(sourceDir, 'test.jsonl.gz');
  const qrelsTsv = path.join(sourceDir, 'qrels.tsv');

  writeGzip(
    corpusGz,
    [
      JSON.stringify({ docid: 'doc-1', title: 'Doc One', text: 'Body one' }),
      JSON.stringify({ _id: 'doc-2', contents: 'Body two' }),
      JSON.stringify({ _id: '', text: 'skip me' }),
      'not-json',
    ].join('\n'),
  );
  writeGzip(
    queriesGz,
    [
      JSON.stringify({ query_id: 'q-1', query: 'What is one?' }),
      JSON.stringify({ _id: 'q-2', text: 'What is two?' }),
      JSON.stringify({ qid: '', text: 'skip me' }),
      'not-json',
    ].join('\n'),
  );
  fs.writeFileSync(
    qrelsTsv,
    [
      'query-id\tcorpus-id\tscore',
      'q-1\tdoc-1\t1',
      'q-2\tdoc-2\t2',
      '',
    ].join('\n'),
    'utf8',
  );

  const run = spawnSync(
    'node',
    [
      converterScript,
      '--corpus-gz',
      corpusGz,
      '--queries-gz',
      queriesGz,
      '--qrels-tsv',
      qrelsTsv,
      '--download-dir',
      path.join(tempRoot, 'dl'),
      '--out-dir',
      outDir,
      '--overwrite',
    ],
    {
      cwd: repoRoot,
      encoding: 'utf8',
      timeout: 30_000,
    },
  );

  assert.equal(run.status, 0, `converter should succeed: ${run.stderr}\n${run.stdout}`);

  const corpusRows = readJsonl(path.join(outDir, 'corpus.jsonl'));
  const queryRows = readJsonl(path.join(outDir, 'queries.jsonl'));
  const qrelsRows = fs.readFileSync(path.join(outDir, 'qrels', 'test.tsv'), 'utf8').trim().split(/\r?\n/);
  const metadata = JSON.parse(fs.readFileSync(path.join(outDir, 'conversion-metadata.json'), 'utf8'));

  assert.equal(corpusRows.length, 2);
  assert.deepEqual(corpusRows[0], { _id: 'doc-1', title: 'Doc One', text: 'Body one' });
  assert.deepEqual(corpusRows[1], { _id: 'doc-2', title: '', text: 'Body two' });

  assert.equal(queryRows.length, 2);
  assert.deepEqual(queryRows[0], { _id: 'q-1', text: 'What is one?' });
  assert.deepEqual(queryRows[1], { _id: 'q-2', text: 'What is two?' });

  assert.equal(qrelsRows[0], 'query-id\tcorpus-id\tscore');
  assert.equal(qrelsRows[1], 'q-1\tdoc-1\t1');
  assert.equal(qrelsRows[2], 'q-2\tdoc-2\t2');

  assert.equal(metadata.kind, 'mldr-en-beir-conversion.v1');
  assert.equal(metadata.stats.corpus.written, 2);
  assert.equal(metadata.stats.queries.written, 2);
  assert.equal(metadata.stats.qrels.written, 2);

  // eslint-disable-next-line no-console
  console.log('convert-mldr-en-to-beir tests: PASS');
}

main();
