/**
 * Tempdoc 800 — score an existing eval run BOTH ways and report the delta.
 *
 * THE QUESTION THIS ANSWERS. `KnowledgeSearchEngine` reorders results into cross-encoder order
 * without rewriting each hit's `score` (the reorder block moves the existing SearchResult objects;
 * it contains no score mutation). `retriever.py:143` then scores by that stale pre-rerank value, and
 * `ir_measures` ranks by score — so it reconstructs the FUSION ordering and discards the delivered
 * CE ordering. A stage that only reorders is therefore invisible to the metric judging it, and a
 * cross-encoder is exactly such a stage.
 *
 * Whether that matters in practice is an empirical question, and this answers it offline: no model,
 * no backend, no spend. It re-scores artifacts already on disk.
 *
 *   Order A ("measured")  — the *_run.trec file sorted by score descending: what ir_measures did.
 *   Order B ("delivered") — `predictedDocIds` from *_per_query.json, which artifacts.py builds from
 *                           scored_docs in append order, i.e. the order the API returned.
 *
 * VALIDITY. The two orders must cover the SAME document set for the comparison to be a pure
 * reorder rather than a set difference; the script asserts that per query and reports any breach.
 * Without that check a truncation or an id-space mismatch would masquerade as a ranking delta.
 *
 * Usage:
 *   node scripts/jseval/metric_order_ab.mjs [runsRoot] [--json]
 *     runsRoot defaults to tmp/781-certification. Any directory whose children contain
 *     <mode>_run.trec + <mode>_per_query.json + qrels.json works.
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const K = 10;
const MODES = ['hybrid', 'lexical', 'splade', 'vector'];

const dcg = (gains) => gains.reduce((s, g, i) => s + (Math.pow(2, g) - 1) / Math.log2(i + 2), 0);

function ndcgAt(order, rel, k) {
  const ideal = Object.values(rel).filter((v) => v > 0).sort((a, b) => b - a).slice(0, k);
  const idcg = dcg(ideal);
  return idcg === 0 ? 0 : dcg(order.slice(0, k).map((d) => rel[d] ?? 0)) / idcg;
}

function readTrec(path) {
  const byQ = new Map();
  for (const line of readFileSync(path, 'utf8').split(/\r?\n/)) {
    // Right-anchored: `qid Q0 <doc id> rank score tag`, and a doc id may contain
    // spaces (OHR-bench), so the id is everything between the fixed head and tail.
    // Mirrors jseval/trec.py parse_trec_line.
    const p = line.trim().split(/\s+/);
    if (p.length < 6) continue;
    const doc = p.slice(2, -3).join(' ');
    if (!doc) continue;
    if (!byQ.has(p[0])) byQ.set(p[0], []);
    byQ.get(p[0]).push({ doc, score: Number(p[p.length - 2]) });
  }
  for (const arr of byQ.values()) arr.sort((a, b) => b.score - a.score); // what ir_measures does
  return byQ;
}

function analyse(runDir, mode) {
  const trecPath = join(runDir, `${mode}_run.trec`);
  const pqPath = join(runDir, `${mode}_per_query.json`);
  const qrelsPath = join(runDir, 'qrels.json');
  if (![trecPath, pqPath, qrelsPath].every(existsSync)) return null;

  const trec = readTrec(trecPath);
  const pq = JSON.parse(readFileSync(pqPath, 'utf8'));
  const qrels = JSON.parse(readFileSync(qrelsPath, 'utf8'));
  const entries = Array.isArray(pq) ? pq : pq.entries || [];

  let sumA = 0, sumB = 0, n = 0, differingTop10 = 0, differingTop1 = 0, setMismatch = 0;
  for (const e of entries) {
    const scored = trec.get(e.qid);
    const delivered = (e.predictedDocIds || []).map(String);
    if (!scored || !delivered.length) continue;

    const orderA = scored.map((x) => x.doc);
    const a = new Set(orderA), b = new Set(delivered);
    if (a.size !== b.size || ![...a].every((x) => b.has(x))) { setMismatch++; continue; }

    const rel = qrels[e.qid] || {};
    sumA += ndcgAt(orderA, rel, K);
    sumB += ndcgAt(delivered, rel, K);
    n++;
    if (orderA.slice(0, K).join(',') !== delivered.slice(0, K).join(',')) differingTop10++;
    if (orderA[0] !== delivered[0]) differingTop1++;
  }
  if (!n) return null;
  return { mode, n, measured: sumA / n, delivered: sumB / n, differingTop10, differingTop1, setMismatch };
}

const root = process.argv[2] && !process.argv[2].startsWith('--') ? process.argv[2] : 'tmp/781-certification';
const asJson = process.argv.includes('--json');
if (!existsSync(root)) {
  console.error(`metric-order-ab: runs root not found: ${root}`);
  process.exit(2);
}

const rows = [];
for (const d of readdirSync(root, { withFileTypes: true })) {
  if (!d.isDirectory()) continue;
  for (const mode of MODES) {
    const r = analyse(join(root, d.name), mode);
    if (r) rows.push({ cell: d.name, ...r });
  }
}

if (asJson) {
  console.log(JSON.stringify({ root, k: K, rows }, null, 2));
} else {
  console.log(
    'cell'.padEnd(44), 'mode'.padEnd(8), 'n'.padStart(4),
    'measured'.padStart(9), 'delivered'.padStart(10), 'delta'.padStart(8),
    'top10!='.padStart(8), 'top1!='.padStart(7));
  for (const r of rows) {
    const delta = r.delivered - r.measured;
    console.log(
      r.cell.slice(0, 43).padEnd(44), r.mode.padEnd(8), String(r.n).padStart(4),
      r.measured.toFixed(4).padStart(9), r.delivered.toFixed(4).padStart(10),
      ((delta >= 0 ? '+' : '') + delta.toFixed(4)).padStart(8),
      String(r.differingTop10).padStart(8), String(r.differingTop1).padStart(7));
  }
  const affected = rows.filter((r) => r.differingTop10 > 0);
  const mismatches = rows.reduce((s, r) => s + r.setMismatch, 0);
  console.log(`\ncells: ${rows.length} | with a delivered-vs-measured order difference: ${affected.length}`);
  if (mismatches) console.log(`WARNING: ${mismatches} queries had non-identical doc SETS and were skipped — not a pure reorder.`);
}
