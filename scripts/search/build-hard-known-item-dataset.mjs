#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { parseArgs } from 'node:util';

const DEFAULT_MAX_QUERIES = 150;
const DEFAULT_VALIDATE_LIMIT = 10;
const DEFAULT_RANK_MIN = 2;
const DEFAULT_RANK_MAX = 10;

const { values } = parseArgs({
  options: {
    terms: { type: 'string' },
    corpus: { type: 'string' },
    'out-dir': { type: 'string' },
    'base-url': { type: 'string', default: '' },
    'max-queries': { type: 'string', default: String(DEFAULT_MAX_QUERIES) },
    'validate-limit': { type: 'string', default: String(DEFAULT_VALIDATE_LIMIT) },
    'rank-min': { type: 'string', default: String(DEFAULT_RANK_MIN) },
    'rank-max': { type: 'string', default: String(DEFAULT_RANK_MAX) },
  },
  strict: true,
});

if (!values.terms || !values.corpus || !values['out-dir']) {
  process.stderr.write(
    'Usage: node build-hard-known-item-dataset.mjs --terms <terms.ndjson> --corpus <corpus.jsonl> --out-dir <dataset-dir> ' +
    '[--base-url <url>] [--max-queries 150] [--validate-limit 10] [--rank-min 2] [--rank-max 10]\n',
  );
  process.exit(1);
}

const repoRoot = path.resolve(import.meta.dirname, '..', '..');
const termsPath = path.resolve(repoRoot, values.terms);
const corpusPath = path.resolve(repoRoot, values.corpus);
const outDir = path.resolve(repoRoot, values['out-dir']);
const baseUrl = String(values['base-url'] || '').trim().replace(/\/+$/, '');
const maxQueries = parseInt(values['max-queries'], 10);
const validateLimit = parseInt(values['validate-limit'], 10);
const rankMin = parseInt(values['rank-min'], 10);
const rankMax = parseInt(values['rank-max'], 10);

if (!Number.isFinite(maxQueries) || maxQueries <= 0) {
  throw new Error(`Invalid --max-queries '${values['max-queries']}'`);
}
if (!Number.isFinite(validateLimit) || validateLimit <= 0) {
  throw new Error(`Invalid --validate-limit '${values['validate-limit']}'`);
}
if (!Number.isFinite(rankMin) || rankMin <= 0 || !Number.isFinite(rankMax) || rankMax < rankMin) {
  throw new Error(`Invalid rank window ${rankMin}-${rankMax}`);
}

function readNdjson(filePath) {
  return fs.readFileSync(filePath, 'utf8')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => JSON.parse(line));
}

function ensureDir(dirPath) {
  fs.mkdirSync(dirPath, { recursive: true });
}

function writeJson(filePath, value) {
  ensureDir(path.dirname(filePath));
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function canonicalDocId(docId) {
  return String(docId || '').trim().toLowerCase();
}

function fileNameToDocId(fileName) {
  let value = String(fileName || '').trim().toLowerCase();
  if (!value) return '';
  value = value.replace(/\\/g, '/');
  const lastSlash = value.lastIndexOf('/');
  if (lastSlash >= 0) {
    value = value.slice(lastSlash + 1);
  }
  value = value.replace(/\.[^.]+$/, '');
  return value;
}

function pickQueryTerms(topTerms) {
  const ranked = Array.isArray(topTerms) ? topTerms.slice(0, 10) : [];
  const moderate = ranked.filter((term, index) => (
    index >= 1
    && Number.isFinite(term?.idf)
    && term.idf >= 1.8
    && term.idf <= 4.8
    && Number.isFinite(term?.tf)
    && term.tf >= 2
  ));
  const broader = ranked.filter((term, index) => (
    index >= 2
    && Number.isFinite(term?.idf)
    && term.idf >= 1.2
    && term.idf <= 3.2
  ));

  const variants = [];
  if (moderate.length >= 3) {
    variants.push({
      strategy: 'moderate_tail_3',
      terms: [moderate[0], moderate[1], moderate[2]],
    });
  }
  if (moderate.length >= 2 && broader.length >= 1) {
    variants.push({
      strategy: 'mixed_specificity',
      terms: [moderate[0], broader[0], moderate[1]],
    });
  }
  if (moderate.length >= 2) {
    variants.push({
      strategy: 'partial_recall_2',
      terms: [moderate[0], moderate[1]],
    });
  }
  if (ranked.length >= 4) {
    variants.push({
      strategy: 'skip_head_tail_3',
      terms: [ranked[1], ranked[2], ranked[3]],
    });
  }

  const seen = new Set();
  return variants
    .map((variant) => ({
      ...variant,
      terms: variant.terms
        .filter(Boolean)
        .map((term) => String(term.term || '').trim().toLowerCase())
        .filter(Boolean),
    }))
    .filter((variant) => variant.terms.length >= 2)
    .filter((variant) => {
      const query = variant.terms.join(' ');
      if (seen.has(query)) return false;
      seen.add(query);
      return true;
    })
    .map((variant) => ({
      ...variant,
      query: variant.terms.join(' '),
    }));
}

function docDifficulty(topTerms) {
  const ranked = Array.isArray(topTerms) ? topTerms.slice(0, 10) : [];
  if (ranked.length === 0) return Number.POSITIVE_INFINITY;
  const selected = ranked.slice(1, 4).filter((term) => Number.isFinite(term?.idf));
  if (selected.length === 0) return Number.POSITIVE_INFINITY;
  const avgIdf = selected.reduce((sum, term) => sum + term.idf, 0) / selected.length;
  return Math.abs(avgIdf - 3.1);
}

async function searchRank(baseUrlValue, queryText, targetDocId, limit) {
  const response = await fetch(`${baseUrlValue}/api/knowledge/search`, {
    method: 'POST',
    headers: { 'content-type': 'application/json; charset=utf-8' },
    body: JSON.stringify({
      query: queryText,
      limit,
      mode: 'lexical',
    }),
  });
  if (!response.ok) {
    throw new Error(`Search failed ${response.status} ${response.statusText}`);
  }
  const payload = await response.json();
  const hits = Array.isArray(payload?.results) ? payload.results : [];
  const normalizedTarget = canonicalDocId(targetDocId);
  let rank = null;
  for (let i = 0; i < hits.length; i += 1) {
    const filename = hits[i]?.fields?.filename;
    const predicted = fileNameToDocId(filename);
    if (predicted === normalizedTarget) {
      rank = i + 1;
      break;
    }
  }
  return {
    rank,
    totalHits: Number.isFinite(payload?.total) ? payload.total : null,
  };
}

const docs = readNdjson(termsPath)
  .map((doc) => ({
    docId: canonicalDocId(doc.docId),
    title: doc.title || '',
    topTerms: Array.isArray(doc.topTerms) ? doc.topTerms : [],
  }))
  .filter((doc) => doc.docId && doc.topTerms.length >= 2)
  .sort((a, b) => docDifficulty(a.topTerms) - docDifficulty(b.topTerms));

const selected = [];
const rejected = [];

for (const doc of docs) {
  if (selected.length >= maxQueries) break;
  const candidates = pickQueryTerms(doc.topTerms);
  if (candidates.length === 0) {
    rejected.push({ docId: doc.docId, reason: 'no_candidates' });
    continue;
  }

  let accepted = null;
  for (const candidate of candidates) {
    if (!baseUrl) {
      accepted = {
        docId: doc.docId,
        query: candidate.query,
        strategy: candidate.strategy,
        rank: null,
        totalHits: null,
      };
      break;
    }

    const probe = await searchRank(baseUrl, candidate.query, doc.docId, validateLimit);
    if (probe.rank !== null && probe.rank >= rankMin && probe.rank <= rankMax) {
      accepted = {
        docId: doc.docId,
        query: candidate.query,
        strategy: candidate.strategy,
        rank: probe.rank,
        totalHits: probe.totalHits,
      };
      break;
    }
  }

  if (accepted) {
    selected.push(accepted);
  } else {
    rejected.push({ docId: doc.docId, reason: 'no_query_in_rank_window' });
  }
}

ensureDir(path.join(outDir, 'qrels'));
fs.copyFileSync(corpusPath, path.join(outDir, 'corpus.jsonl'));

const queriesLines = [];
const qrelsLines = ['query-id\tcorpus-id\tscore'];
selected.forEach((entry, index) => {
  const qid = `known_item_refinding_${index}`;
  queriesLines.push(JSON.stringify({ _id: qid, text: entry.query }));
  qrelsLines.push(`${qid}\t${entry.docId}\t1`);
  entry.qid = qid;
});

fs.writeFileSync(path.join(outDir, 'queries.jsonl'), `${queriesLines.join('\n')}\n`, 'utf8');
fs.writeFileSync(path.join(outDir, 'qrels', 'test.tsv'), `${qrelsLines.join('\n')}\n`, 'utf8');

const summary = {
  kind: 'known-item-refinding-dataset.v1',
  sourceTerms: path.relative(repoRoot, termsPath),
  sourceCorpus: path.relative(repoRoot, corpusPath),
  outDir: path.relative(repoRoot, outDir),
  baseUrl: baseUrl || null,
  selection: {
    maxQueries,
    selectedCount: selected.length,
    rejectedCount: rejected.length,
    rankWindow: baseUrl ? { min: rankMin, max: rankMax, limit: validateLimit } : null,
  },
  selected,
  rejectedSample: rejected.slice(0, 25),
};
writeJson(path.join(outDir, 'known-item-refinding-summary.json'), summary);

process.stdout.write(`${JSON.stringify(summary, null, 2)}\n`);
