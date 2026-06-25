#!/usr/bin/env node

/**
 * Download a LoCoV1 task from the HuggingFace dataset viewer API and convert
 * it to BEIR format (corpus.jsonl, queries.jsonl, qrels/test.tsv).
 *
 * Uses the HuggingFace dataset viewer REST API (/filter endpoint) to paginate
 * through filtered rows — no Python or Parquet dependency needed.
 *
 * Usage:
 *   node scripts/search/convert-locov1-to-beir.mjs --task courtlistener_Plain_Text
 *   node scripts/search/convert-locov1-to-beir.mjs --task stackoverflow --out-dir tmp/beir-cache/stackoverflow/raw/stackoverflow
 *   node scripts/search/convert-locov1-to-beir.mjs --task legal_case_reports
 *
 * Then run:
 *   jseval run --dataset <task>
 */

import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import https from 'node:https';

const VALID_TASKS = [
  'courtlistener_HTML',
  'courtlistener_Plain_Text',
  'legal_case_reports',
  'stackoverflow',
  '2wikimqa',
  'gov_report',
  'multifieldqa',
  'passage_retrieval',
  'qasper_abstract',
  'qasper_title',
  'qmsum',
  'summ_screen_fd',
];

const DOCS_DATASET = 'hazyresearch/LoCoV1-Documents';
const QUERIES_DATASET = 'hazyresearch/LoCoV1-Queries';
const BASE_API = 'https://datasets-server.huggingface.co';
const PAGE_SIZE = 100;

function usage() {
  console.error(
    'Usage: node scripts/search/convert-locov1-to-beir.mjs\n' +
      '  --task <task>          LoCoV1 task name (required)\n' +
      '  [--out-dir <path>]     Output directory (default: tmp/beir-cache/<task>/raw/<task>/)\n' +
      '  [--overwrite]          Overwrite existing output files\n' +
      '\nValid tasks: ' +
      VALID_TASKS.join(', '),
  );
  process.exit(2);
}

function parseArgs(argv) {
  const out = { task: '', outDir: '', overwrite: false };
  for (let i = 2; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--task' && argv[i + 1]) out.task = argv[++i];
    else if (arg === '--out-dir' && argv[i + 1]) out.outDir = argv[++i];
    else if (arg === '--overwrite') out.overwrite = true;
    else if (arg === '--help') usage();
    else usage();
  }
  if (!out.task) {
    console.error('Error: --task is required');
    usage();
  }
  if (!VALID_TASKS.includes(out.task)) {
    console.error(`Error: unknown task "${out.task}". Valid tasks: ${VALID_TASKS.join(', ')}`);
    process.exit(2);
  }
  if (!out.outDir) {
    out.outDir = `tmp/beir-cache/${out.task}/raw/${out.task}`;
  }
  return out;
}

/**
 * Fetch JSON from a URL, following redirects.
 */
function fetchJson(url, redirects = 0) {
  return new Promise((resolve, reject) => {
    if (redirects > 5) {
      reject(new Error(`too many redirects: ${url}`));
      return;
    }
    https.get(
      url,
      { headers: { 'User-Agent': 'justsearch-locov1-converter/1.0', Accept: 'application/json' } },
      (res) => {
        const status = Number(res.statusCode || 0);
        if ([301, 302, 303, 307, 308].includes(status) && res.headers.location) {
          resolve(fetchJson(res.headers.location, redirects + 1));
          return;
        }
        if (status < 200 || status >= 300) {
          res.resume();
          reject(new Error(`HTTP ${status} for ${url}`));
          return;
        }
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          try {
            resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
          } catch (e) {
            reject(new Error(`JSON parse error for ${url}: ${e.message}`));
          }
        });
        res.on('error', reject);
      },
    ).on('error', reject);
  });
}

/**
 * Paginate through all rows matching a filter on the HF dataset viewer API.
 * Returns an array of row objects.
 */
async function fetchAllFilteredRows(dataset, filterWhere) {
  const rows = [];
  let offset = 0;
  let total = null;
  const MAX_RETRIES = 6;

  while (total === null || offset < total) {
    const url =
      `${BASE_API}/filter?dataset=${encodeURIComponent(dataset)}` +
      `&config=default&split=test` +
      `&where=${encodeURIComponent(filterWhere)}` +
      `&offset=${offset}&length=${PAGE_SIZE}`;

    let data = null;
    for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
      try {
        data = await fetchJson(url);
        break;
      } catch (err) {
        if (attempt < MAX_RETRIES) {
          const delay = attempt * 3000;
          console.error(`  retry ${attempt}/${MAX_RETRIES} after error: ${err.message} (waiting ${delay}ms)`);
          await new Promise((r) => setTimeout(r, delay));
        } else {
          throw err;
        }
      }
    }

    if (total === null) {
      total = data.num_rows_total || 0;
      console.error(`  total rows: ${total}`);
    }

    for (const entry of data.rows || []) {
      rows.push(entry.row);

      // Warn if any cell was truncated (long documents)
      if (entry.truncated_cells && entry.truncated_cells.length > 0) {
        console.error(`  WARNING: truncated cells in row ${entry.row_idx}: ${entry.truncated_cells.join(', ')}`);
      }
    }

    offset += PAGE_SIZE;
    if ((data.rows || []).length === 0) break; // safety: no more data
    if (rows.length % 500 === 0 || offset >= total) {
      console.error(`  fetched ${rows.length}/${total} rows`);
    }
  }

  return rows;
}

async function main() {
  const args = parseArgs(process.argv);
  const { task, outDir, overwrite } = args;

  const corpusPath = path.join(outDir, 'corpus.jsonl');
  const queriesPath = path.join(outDir, 'queries.jsonl');
  const qrelsDir = path.join(outDir, 'qrels');
  const qrelsPath = path.join(qrelsDir, 'test.tsv');

  // Check for existing output
  if (!overwrite && fs.existsSync(corpusPath) && fs.existsSync(queriesPath) && fs.existsSync(qrelsPath)) {
    console.error(`Output files already exist in ${outDir}. Use --overwrite to replace.`);
    process.exit(0);
  }

  await fsp.mkdir(qrelsDir, { recursive: true });

  // 1) Fetch documents
  console.error(`Fetching documents for task "${task}" from ${DOCS_DATASET}...`);
  const docRows = await fetchAllFilteredRows(DOCS_DATASET, `dataset='${task}'`);
  console.error(`  got ${docRows.length} documents`);

  // 2) Fetch queries
  console.error(`Fetching queries for task "${task}" from ${QUERIES_DATASET}...`);
  const queryRows = await fetchAllFilteredRows(QUERIES_DATASET, `dataset='${task}'`);
  console.error(`  got ${queryRows.length} queries`);

  // 3) Write corpus.jsonl — BEIR format: {"_id": "...", "title": "", "text": "..."}
  console.error(`Writing ${corpusPath}...`);
  const corpusLines = [];
  for (const row of docRows) {
    const id = row.pid;
    const text = row.passage || '';
    if (!id || !text) continue;

    // LoCoV1 passages contain the full document text including any heading.
    // Do NOT extract a title — the materializer prepends titles, which would
    // duplicate the first line and inflate BM25 scores (+0.035 nDCG on
    // courtlistener, measured in tempdoc 251 §5f audit).
    const title = '';

    corpusLines.push(JSON.stringify({ _id: id, title, text }));
  }
  await fsp.writeFile(corpusPath, corpusLines.join('\n') + '\n', 'utf8');
  console.error(`  wrote ${corpusLines.length} documents`);

  // 4) Write queries.jsonl — BEIR format: {"_id": "...", "text": "..."}
  console.error(`Writing ${queriesPath}...`);
  const queryLines = [];
  const qrelsLines = ['query-id\tcorpus-id\tscore']; // TSV header

  for (const row of queryRows) {
    const qid = row.qid;
    const query = row.query || '';
    const answerPids = row.answer_pids || [];

    if (!qid || !query) continue;

    queryLines.push(JSON.stringify({ _id: qid, text: query }));

    // 5) Build qrels from answer_pids (binary relevance: score=1)
    for (const pid of answerPids) {
      qrelsLines.push(`${qid}\t${pid}\t1`);
    }
  }
  await fsp.writeFile(queriesPath, queryLines.join('\n') + '\n', 'utf8');
  console.error(`  wrote ${queryLines.length} queries`);

  // 6) Write qrels/test.tsv
  console.error(`Writing ${qrelsPath}...`);
  await fsp.writeFile(qrelsPath, qrelsLines.join('\n') + '\n', 'utf8');
  console.error(`  wrote ${qrelsLines.length - 1} qrel entries`);

  console.error('\nDone! Run eval with:');
  console.error(`  jseval run --dataset ${task}`);
}

main().catch((err) => {
  console.error('FATAL:', err.message || err);
  process.exit(1);
});
