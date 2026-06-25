#!/usr/bin/env node

import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { parseArgs } from 'node:util';

const { values } = parseArgs({
  options: {
    datasets: { type: 'string' },
    'mix-id': { type: 'string', default: '' },
    'cache-root': { type: 'string', default: 'tmp/beir-cache' },
  },
  strict: true,
});

if (!values.datasets) {
  process.stderr.write('Usage: node build-mixed-beir-dataset.mjs --datasets scifact,fiqa [--mix-id mixed_scifact_fiqa]\n');
  process.exit(1);
}

const repoRoot = path.resolve(import.meta.dirname, '..', '..');
const cacheRoot = path.resolve(repoRoot, values['cache-root']);
const datasets = String(values.datasets)
  .split(',')
  .map((name) => name.trim().toLowerCase())
  .filter(Boolean);

if (datasets.length < 2) {
  throw new Error('Mixed corpus needs at least two datasets');
}

const mixId = String(values['mix-id'] || '').trim() || `mixed_${datasets.join('_')}`;
const datasetRoot = path.join(cacheRoot, mixId, 'raw', mixId);
const sourceRoot = path.join(cacheRoot, mixId, 'source_queries');

function ensureDir(dirPath) {
  fs.mkdirSync(dirPath, { recursive: true });
}

function readNdjson(filePath) {
  return fs.readFileSync(filePath, 'utf8')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => JSON.parse(line));
}

function findFirstFile(rootDir, fileName) {
  const queue = [rootDir];
  while (queue.length > 0) {
    const current = queue.shift();
    const entries = fs.readdirSync(current, { withFileTypes: true });
    for (const entry of entries) {
      const fullPath = path.join(current, entry.name);
      if (entry.isDirectory()) {
        queue.push(fullPath);
      } else if (entry.isFile() && entry.name === fileName) {
        return fullPath;
      }
    }
  }
  return null;
}

function resolveDatasetRawDir(dataset) {
  const root = path.join(cacheRoot, dataset, 'raw');
  if (!fs.existsSync(root)) {
    throw new Error(`Dataset raw dir not found: ${root}`);
  }
  const corpusPath = findFirstFile(root, 'corpus.jsonl');
  const queriesPath = findFirstFile(root, 'queries.jsonl');
  if (!corpusPath || !queriesPath) {
    throw new Error(`Dataset ${dataset} is missing corpus.jsonl or queries.jsonl under ${root}`);
  }
  const qrelsDir = path.join(path.dirname(queriesPath), 'qrels');
  const qrelsPath = path.join(qrelsDir, 'test.tsv');
  if (!fs.existsSync(qrelsPath)) {
    throw new Error(`Dataset ${dataset} is missing qrels/test.tsv under ${qrelsDir}`);
  }
  return { corpusPath, queriesPath, qrelsPath };
}

function namespaceId(dataset, originalId) {
  return `${dataset}__${String(originalId || '').trim()}`;
}

function writeJson(filePath, value) {
  ensureDir(path.dirname(filePath));
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

const corpusLines = [];
const sourceSummaries = [];

ensureDir(datasetRoot);
ensureDir(sourceRoot);

for (const dataset of datasets) {
  const resolved = resolveDatasetRawDir(dataset);
  const corpusDocs = readNdjson(resolved.corpusPath);
  const queries = readNdjson(resolved.queriesPath);
  const qrelsLines = fs.readFileSync(resolved.qrelsPath, 'utf8')
    .split(/\r?\n/)
    .filter(Boolean);
  const qrelsBody = qrelsLines[0].includes('query-id') ? qrelsLines.slice(1) : qrelsLines;

  for (const doc of corpusDocs) {
    const namespaced = {
      ...doc,
      _id: namespaceId(dataset, doc._id),
      source_dataset: dataset,
    };
    corpusLines.push(JSON.stringify(namespaced));
  }

  const sourceDir = path.join(sourceRoot, dataset);
  ensureDir(path.join(sourceDir, 'qrels'));

  const namespacedQueries = queries.map((query) => JSON.stringify({
    ...query,
    _id: namespaceId(dataset, query._id),
    source_dataset: dataset,
  }));
  fs.writeFileSync(path.join(sourceDir, 'queries.jsonl'), `${namespacedQueries.join('\n')}\n`, 'utf8');

  const qrelsOut = ['query-id\tcorpus-id\tscore'];
  for (const line of qrelsBody) {
    const cols = line.split('\t');
    if (cols.length < 3) continue;
    const qid = namespaceId(dataset, cols[0]);
    const did = namespaceId(dataset, cols[1]);
    const score = cols[2];
    qrelsOut.push(`${qid}\t${did}\t${score}`);
  }
  fs.writeFileSync(path.join(sourceDir, 'qrels', 'test.tsv'), `${qrelsOut.join('\n')}\n`, 'utf8');

  sourceSummaries.push({
    dataset,
    corpusPath: path.relative(repoRoot, resolved.corpusPath),
    queriesPath: path.relative(repoRoot, path.join(sourceDir, 'queries.jsonl')),
    qrelsPath: path.relative(repoRoot, path.join(sourceDir, 'qrels', 'test.tsv')),
    corpusDocCount: corpusDocs.length,
    queryCount: queries.length,
  });
}

fs.writeFileSync(path.join(datasetRoot, 'corpus.jsonl'), `${corpusLines.join('\n')}\n`, 'utf8');

const signatureHash = createHash('sha256')
  .update(JSON.stringify({
    datasets,
    counts: sourceSummaries.map((summary) => ({ dataset: summary.dataset, corpusDocCount: summary.corpusDocCount })),
  }))
  .digest('hex');

const summary = {
  kind: 'mixed-beir-dataset.v1',
  mixId,
  datasetRoot: path.relative(repoRoot, datasetRoot),
  corpusJsonl: path.relative(repoRoot, path.join(datasetRoot, 'corpus.jsonl')),
  corpusSignature: signatureHash,
  corpusComponents: datasets,
  totalCorpusDocs: sourceSummaries.reduce((sum, item) => sum + item.corpusDocCount, 0),
  sources: sourceSummaries,
};

writeJson(path.join(cacheRoot, mixId, 'mixed-beir-summary.json'), summary);
process.stdout.write(`${JSON.stringify(summary, null, 2)}\n`);
