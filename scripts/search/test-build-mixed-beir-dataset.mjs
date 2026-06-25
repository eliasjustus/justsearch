#!/usr/bin/env node

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, '..', '..');
const scriptPath = path.join(__dirname, 'build-mixed-beir-dataset.mjs');

function writeFile(filePath, content) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, content, 'utf8');
}

function runNode(args) {
  const result = spawnSync(process.execPath, [scriptPath, ...args], {
    cwd: repoRoot,
    encoding: 'utf8',
    windowsHide: true,
  });
  assert.equal(result.status, 0, `script failed\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`);
  return JSON.parse(result.stdout);
}

function testBuildMixedDataset() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-mixed-beir-'));
  try {
    const cacheRoot = path.join(tempRoot, 'cache');

    for (const dataset of ['alpha', 'beta']) {
      const base = path.join(cacheRoot, dataset, 'raw', dataset);
      writeFile(path.join(base, 'corpus.jsonl'), `${JSON.stringify({ _id: `${dataset}_doc`, title: '', text: `${dataset} text` })}\n`);
      writeFile(path.join(base, 'queries.jsonl'), `${JSON.stringify({ _id: `${dataset}_query`, text: `${dataset} query` })}\n`);
      writeFile(path.join(base, 'qrels', 'test.tsv'), `query-id\tcorpus-id\tscore\n${dataset}_query\t${dataset}_doc\t1\n`);
    }

    const summary = runNode([
      '--datasets', 'alpha,beta',
      '--mix-id', 'mixed_alpha_beta',
      '--cache-root', path.relative(repoRoot, cacheRoot),
    ]);

    assert.equal(summary.mixId, 'mixed_alpha_beta');
    assert.deepEqual(summary.corpusComponents, ['alpha', 'beta']);
    assert.equal(summary.totalCorpusDocs, 2);

    const corpus = fs.readFileSync(path.join(cacheRoot, 'mixed_alpha_beta', 'raw', 'mixed_alpha_beta', 'corpus.jsonl'), 'utf8').trim().split(/\r?\n/);
    assert.equal(corpus.length, 2);
    const firstDoc = JSON.parse(corpus[0]);
    assert.ok(firstDoc._id.startsWith('alpha__') || firstDoc._id.startsWith('beta__'));

    const alphaQueries = fs.readFileSync(path.join(cacheRoot, 'mixed_alpha_beta', 'source_queries', 'alpha', 'queries.jsonl'), 'utf8').trim().split(/\r?\n/);
    assert.equal(JSON.parse(alphaQueries[0])._id, 'alpha__alpha_query');
    const alphaQrels = fs.readFileSync(path.join(cacheRoot, 'mixed_alpha_beta', 'source_queries', 'alpha', 'qrels', 'test.tsv'), 'utf8');
    assert.match(alphaQrels, /alpha__alpha_query\talpha__alpha_doc\t1/);
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}

testBuildMixedDataset();
console.log('ok');
