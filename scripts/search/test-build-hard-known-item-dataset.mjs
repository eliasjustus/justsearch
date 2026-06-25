#!/usr/bin/env node

import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, '..', '..');
const scriptPath = path.join(__dirname, 'build-hard-known-item-dataset.mjs');

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

function runNodeAsync(args) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [scriptPath, ...args], {
      cwd: repoRoot,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    const stdoutChunks = [];
    const stderrChunks = [];
    child.stdout.on('data', (chunk) => stdoutChunks.push(String(chunk)));
    child.stderr.on('data', (chunk) => stderrChunks.push(String(chunk)));
    child.on('error', reject);
    child.on('close', (status) => {
      const stdout = stdoutChunks.join('');
      const stderr = stderrChunks.join('');
      if (status !== 0) {
        reject(new Error(`script failed exit=${status}\nstdout:\n${stdout}\nstderr:\n${stderr}`));
        return;
      }
      resolve(JSON.parse(stdout));
    });
  });
}

async function withServer(handler, fn) {
  const server = http.createServer(handler);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  const baseUrl = `http://127.0.0.1:${address.port}`;
  try {
    await fn(baseUrl);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

function testWritesDatasetWithoutValidation() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-known-item-'));
  try {
    const termsPath = path.join(tempRoot, 'terms.ndjson');
    const corpusPath = path.join(tempRoot, 'corpus.jsonl');
    const outDir = path.join(tempRoot, 'dataset');
    writeFile(termsPath, [
      JSON.stringify({
        docId: 'Doc_A',
        title: '',
        topTerms: [
          { term: 'tax', tfidf: 0.05, tf: 10, idf: 2.1 },
          { term: 'evasion', tfidf: 0.04, tf: 8, idf: 3.4 },
          { term: 'irs', tfidf: 0.03, tf: 7, idf: 3.1 },
          { term: 'agent', tfidf: 0.02, tf: 5, idf: 2.0 },
        ],
      }),
    ].join('\n'));
    writeFile(corpusPath, `${JSON.stringify({ _id: 'Doc_A', title: '', text: 'alpha beta gamma' })}\n`);

    const summary = runNode([
      '--terms', path.relative(repoRoot, termsPath),
      '--corpus', path.relative(repoRoot, corpusPath),
      '--out-dir', path.relative(repoRoot, outDir),
      '--max-queries', '1',
    ]);

    assert.equal(summary.selection.selectedCount, 1);
    assert.equal(fs.existsSync(path.join(outDir, 'corpus.jsonl')), true);
    assert.equal(fs.existsSync(path.join(outDir, 'queries.jsonl')), true);
    assert.equal(fs.existsSync(path.join(outDir, 'qrels', 'test.tsv')), true);
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}

async function testValidationSelectsNonTrivialRankWindow() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-known-item-'));
  try {
    const termsPath = path.join(tempRoot, 'terms.ndjson');
    const corpusPath = path.join(tempRoot, 'corpus.jsonl');
    const outDir = path.join(tempRoot, 'dataset');
    writeFile(termsPath, [
      JSON.stringify({
        docId: 'Doc_A',
        title: '',
        topTerms: [
          { term: 'uniquename', tfidf: 0.08, tf: 9, idf: 6.2 },
          { term: 'tax', tfidf: 0.05, tf: 10, idf: 2.1 },
          { term: 'evasion', tfidf: 0.04, tf: 8, idf: 3.4 },
          { term: 'agent', tfidf: 0.03, tf: 6, idf: 2.0 },
          { term: 'federal', tfidf: 0.02, tf: 6, idf: 1.7 },
        ],
      }),
      JSON.stringify({
        docId: 'Doc_B',
        title: '',
        topTerms: [
          { term: 'board', tfidf: 0.05, tf: 9, idf: 2.0 },
          { term: 'policy', tfidf: 0.04, tf: 7, idf: 2.4 },
          { term: 'appeal', tfidf: 0.03, tf: 6, idf: 2.1 },
          { term: 'meeting', tfidf: 0.02, tf: 5, idf: 1.9 },
        ],
      }),
    ].join('\n'));
    writeFile(corpusPath, [
      JSON.stringify({ _id: 'Doc_A', title: '', text: 'alpha beta gamma' }),
      JSON.stringify({ _id: 'Doc_B', title: '', text: 'delta epsilon zeta' }),
    ].join('\n') + '\n');

    await withServer(async (req, res) => {
      if (req.method !== 'POST' || req.url !== '/api/knowledge/search') {
        res.statusCode = 404;
        res.end('not found');
        return;
      }
      const body = await new Promise((resolve) => {
        const chunks = [];
        req.on('data', (chunk) => chunks.push(chunk));
        req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
      });
      const payload = JSON.parse(body);
      const query = String(payload.query || '');
      let results = [];
      if (query.includes('tax') && query.includes('evasion')) {
        results = [
          { fields: { filename: 'other-doc.txt' } },
          { fields: { filename: 'doc_a.txt' } },
        ];
      } else if (query.includes('policy') && query.includes('appeal')) {
        results = [
          { fields: { filename: 'doc_b.txt' } },
        ];
      }
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify({ results, total: results.length }));
    }, async (baseUrl) => {
      const summary = await runNodeAsync([
        '--terms', path.relative(repoRoot, termsPath),
        '--corpus', path.relative(repoRoot, corpusPath),
        '--out-dir', path.relative(repoRoot, outDir),
        '--base-url', baseUrl,
        '--max-queries', '2',
        '--rank-min', '2',
        '--rank-max', '10',
      ]);

      assert.equal(summary.selection.selectedCount, 1);
      assert.equal(summary.selected[0].docId, 'doc_a');
      assert.equal(summary.selected[0].rank, 2);
      const queries = fs.readFileSync(path.join(outDir, 'queries.jsonl'), 'utf8').trim().split(/\r?\n/);
      assert.equal(queries.length, 1);
    });
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}

await testValidationSelectsNonTrivialRankWindow();
testWritesDatasetWithoutValidation();
console.log('ok');
