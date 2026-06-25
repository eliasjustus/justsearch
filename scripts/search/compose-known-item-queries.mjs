#!/usr/bin/env node
/**
 * Compose known-item search queries from TF-IDF term extraction output.
 * Each query uses the top-K distinctive terms as a conjunction query.
 * The target document should be the #1 result for its query.
 *
 * Input: NDJSON from extract-known-item-terms.mjs
 * Output: BEIR-format queries.jsonl + qrels/ directory
 *
 * Usage:
 *   node compose-known-item-queries.mjs --terms <terms.ndjson> --out-dir <dir>
 *     [--query-terms 3] [--max-queries 150] [--min-tfidf 0.005]
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { parseArgs } from 'node:util';
import { join } from 'node:path';

const { values: args } = parseArgs({
  options: {
    terms:        { type: 'string' },
    'out-dir':    { type: 'string' },
    'query-terms':{ type: 'string', default: '3' },
    'max-queries':{ type: 'string', default: '150' },
    'min-tfidf':  { type: 'string', default: '0.005' },
  }
});

if (!args.terms || !args['out-dir']) {
  console.error('Usage: node compose-known-item-queries.mjs --terms <terms.ndjson> --out-dir <dir> [--query-terms 3] [--max-queries 150]');
  process.exit(1);
}

const K = parseInt(args['query-terms'], 10);
const MAX = parseInt(args['max-queries'], 10);
const MIN_TFIDF = parseFloat(args['min-tfidf']);
const outDir = args['out-dir'];

// Read term data
const lines = readFileSync(args.terms, 'utf8').trim().split('\n');
const docs = lines.map(l => JSON.parse(l));

// Filter: only docs where at least K terms exceed MIN_TFIDF threshold
const candidates = docs.filter(d => {
  const qualifying = d.topTerms.filter(t => t.tfidf >= MIN_TFIDF);
  return qualifying.length >= K;
});

console.error(`${candidates.length}/${docs.length} docs have ${K}+ terms above tfidf=${MIN_TFIDF}`);

// Sort by average TF-IDF of top-K terms (most distinctive first)
candidates.sort((a, b) => {
  const avgA = a.topTerms.slice(0, K).reduce((s, t) => s + t.tfidf, 0) / K;
  const avgB = b.topTerms.slice(0, K).reduce((s, t) => s + t.tfidf, 0) / K;
  return avgB - avgA;
});

// Take top MAX
const selected = candidates.slice(0, MAX);

// Generate queries and qrels
mkdirSync(join(outDir, 'qrels'), { recursive: true });

const queries = [];
const qrels = [];

for (let i = 0; i < selected.length; i++) {
  const doc = selected[i];
  const queryId = `known_item_${i}`;
  const queryTerms = doc.topTerms.slice(0, K).map(t => t.term);
  const queryText = queryTerms.join(' ');

  queries.push(JSON.stringify({ _id: queryId, text: queryText }));
  qrels.push(`${queryId}\t${doc.docId}\t1`);
}

// Write outputs
writeFileSync(join(outDir, 'queries.jsonl'), queries.join('\n') + '\n');
writeFileSync(join(outDir, 'qrels', 'test.tsv'), `query-id\tcorpus-id\tscore\n` + qrels.join('\n') + '\n');

console.error(`Generated ${selected.length} known-item queries in ${outDir}`);
console.error(`Sample queries:`);
for (let i = 0; i < Math.min(5, selected.length); i++) {
  const doc = selected[i];
  const q = doc.topTerms.slice(0, K).map(t => t.term).join(' ');
  console.error(`  ${q} -> ${doc.docId}`);
}
