#!/usr/bin/env node
/**
 * Extract distinctive terms per document using TF-IDF scoring.
 * Output: NDJSON with { docId, title, topTerms: [{ term, tfidf, tf, idf }] }
 *
 * Usage:
 *   node extract-known-item-terms.mjs --corpus <path> [--top-n 10] [--min-df 2] [--out <path>]
 */
import { createReadStream, writeFileSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { parseArgs } from 'node:util';

const { values: args } = parseArgs({
  options: {
    corpus:  { type: 'string' },
    'top-n': { type: 'string', default: '10' },
    'min-df': { type: 'string', default: '2' },
    out:     { type: 'string', default: '' },
  }
});

if (!args.corpus) {
  console.error('Usage: node extract-known-item-terms.mjs --corpus <corpus.jsonl> [--top-n 10] [--min-df 2] [--out <path>]');
  process.exit(1);
}

const TOP_N = parseInt(args['top-n'], 10);
const MIN_DF = parseInt(args['min-df'], 10);

// Tokenize: lowercase, split on non-alpha, drop short/stopwords
const STOP = new Set([
  'the','be','to','of','and','a','in','that','have','i','it','for','not','on',
  'with','he','as','you','do','at','this','but','his','by','from','they','we',
  'say','her','she','or','an','will','my','one','all','would','there','their',
  'what','so','up','out','if','about','who','get','which','go','me','when',
  'make','can','like','time','no','just','him','know','take','people','into',
  'year','your','good','some','could','them','see','other','than','then','now',
  'look','only','come','its','over','think','also','back','after','use','two',
  'how','our','work','first','well','way','even','new','want','because','any',
  'these','give','day','most','us','was','were','been','had','has','did','are',
  'is','am','being','does','shall','may','might','must','should','could',
  'court','case','state','united','states','law','order','filed','upon',
  'such','under','section','act','party','parties','judgment','motion',
  'plaintiff','defendant','appellate','appeal','ruling','argued','decided',
  'opinion','dissent','concur','justice','judge','circuit','district',
]);

function tokenize(text) {
  return text.toLowerCase().split(/[^a-z]+/).filter(t => t.length > 2 && !STOP.has(t));
}

// Phase 1: Read all docs, compute TF per doc and DF across corpus
console.error('Phase 1: Reading corpus and computing term frequencies...');
const docs = []; // [{ id, title, tf: Map<term, count>, totalTerms }]
const df = new Map(); // term -> number of docs containing it

const rl = createInterface({ input: createReadStream(args.corpus), crlfDelay: Infinity });
for await (const line of rl) {
  let parsed;
  try { parsed = JSON.parse(line); } catch { console.error(`Skipping malformed line`); continue; }
  const { _id, title, text } = parsed;
  const tokens = tokenize((title || '') + ' ' + text);
  const tf = new Map();
  for (const t of tokens) {
    tf.set(t, (tf.get(t) || 0) + 1);
  }
  // Update DF
  for (const term of tf.keys()) {
    df.set(term, (df.get(term) || 0) + 1);
  }
  docs.push({ id: _id, title, tf, totalTerms: tokens.length });
}

const N = docs.length;
console.error(`Loaded ${N} documents, ${df.size} unique terms`);

// Phase 2: Compute TF-IDF and extract top-N per doc
console.error('Phase 2: Computing TF-IDF scores...');
const results = [];

for (const doc of docs) {
  const scored = [];
  for (const [term, count] of doc.tf) {
    const termDf = df.get(term);
    if (termDf < MIN_DF) continue; // Skip ultra-rare terms (likely OCR noise)
    const tfScore = count / doc.totalTerms;
    const idfScore = Math.log(N / termDf);
    scored.push({ term, tfidf: tfScore * idfScore, tf: count, idf: idfScore });
  }
  scored.sort((a, b) => b.tfidf - a.tfidf);
  results.push({
    docId: doc.id,
    title: doc.title,
    topTerms: scored.slice(0, TOP_N),
  });
}

// Output
const output = results.map(r => JSON.stringify(r)).join('\n') + '\n';
if (args.out) {
  writeFileSync(args.out, output);
  console.error(`Wrote ${results.length} documents to ${args.out}`);
} else {
  process.stdout.write(output);
}

// Summary stats
const avgTerms = results.reduce((s, r) => s + r.topTerms.length, 0) / results.length;
console.error(`Average ${avgTerms.toFixed(1)} distinctive terms per document`);
