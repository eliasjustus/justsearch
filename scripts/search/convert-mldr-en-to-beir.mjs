#!/usr/bin/env node

import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import zlib from 'node:zlib';
import readline from 'node:readline';
import { pipeline } from 'node:stream/promises';
import https from 'node:https';
import http from 'node:http';

const DEFAULT_CORPUS_URL =
  'https://huggingface.co/datasets/Shitao/MLDR/resolve/main/mldr-v1.0-en/corpus.jsonl.gz';
const DEFAULT_QUERIES_URL =
  'https://huggingface.co/datasets/Shitao/MLDR/resolve/main/mldr-v1.0-en/test.jsonl.gz';
const DEFAULT_QRELS_URL =
  'https://huggingface.co/datasets/Shitao/MLDR/resolve/main/qrels/qrels.mldr-v1.0-en-test.tsv';

function usage() {
  console.error(
    'Usage: node scripts/search/convert-mldr-en-to-beir.mjs ' +
      '[--corpus-gz <url-or-path>] [--queries-gz <url-or-path>] [--qrels-tsv <url-or-path>] ' +
      '[--download-dir <path>] [--out-dir <path>] [--force-download] [--overwrite]',
  );
  process.exit(2);
}

function parseArgs(argv) {
  const out = {
    corpusGz: DEFAULT_CORPUS_URL,
    queriesGz: DEFAULT_QUERIES_URL,
    qrelsTsv: DEFAULT_QRELS_URL,
    downloadDir: 'tmp/beir-cache/mldr-en/raw/_source',
    outDir: 'tmp/beir-cache/mldr-en/raw/mldr-en',
    forceDownload: false,
    overwrite: false,
  };

  for (let i = 2; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--corpus-gz' && argv[i + 1]) out.corpusGz = argv[++i];
    else if (arg === '--queries-gz' && argv[i + 1]) out.queriesGz = argv[++i];
    else if (arg === '--qrels-tsv' && argv[i + 1]) out.qrelsTsv = argv[++i];
    else if (arg === '--download-dir' && argv[i + 1]) out.downloadDir = argv[++i];
    else if (arg === '--out-dir' && argv[i + 1]) out.outDir = argv[++i];
    else if (arg === '--force-download') out.forceDownload = true;
    else if (arg === '--overwrite') out.overwrite = true;
    else if (arg === '--help') usage();
    else usage();
  }

  return out;
}

function isHttpUrl(value) {
  return /^https?:\/\//i.test(String(value || '').trim());
}

function normalizePath(value) {
  return path.resolve(String(value || '').trim());
}

function requestUrlStream(url, redirects = 0) {
  return new Promise((resolve, reject) => {
    if (redirects > 10) {
      reject(new Error(`too_many_redirects:${url}`));
      return;
    }
    const client = url.startsWith('https://') ? https : http;
    const req = client.get(
      url,
      {
        headers: {
          'User-Agent': 'justsearch-mldr-converter/1.0',
          Accept: '*/*',
        },
      },
      (res) => {
        const status = Number(res.statusCode || 0);
        if ([301, 302, 303, 307, 308].includes(status)) {
          const next = res.headers.location;
          res.resume();
          if (!next) {
            reject(new Error(`redirect_missing_location:${url}`));
            return;
          }
          const resolved = new URL(next, url).toString();
          requestUrlStream(resolved, redirects + 1).then(resolve).catch(reject);
          return;
        }
        if (status < 200 || status >= 300) {
          const chunks = [];
          res.on('data', (chunk) => chunks.push(chunk));
          res.on('end', () => {
            reject(
              new Error(
                `http_error:${status}:${url}:${Buffer.concat(chunks)
                  .toString('utf8')
                  .slice(0, 500)}`,
              ),
            );
          });
          return;
        }
        resolve(res);
      },
    );
    req.on('error', (err) => reject(err));
  });
}

async function ensureLocalCopy({ source, targetPath, forceDownload }) {
  await fsp.mkdir(path.dirname(targetPath), { recursive: true });
  if (!forceDownload && fs.existsSync(targetPath)) {
    return targetPath;
  }

  if (!isHttpUrl(source)) {
    const local = normalizePath(source);
    await fsp.copyFile(local, targetPath);
    return targetPath;
  }

  const stream = await requestUrlStream(source);
  await pipeline(stream, fs.createWriteStream(targetPath));
  return targetPath;
}

function normalizeJsonlLine(line) {
  const trimmed = String(line || '').trim();
  if (!trimmed) return null;
  try {
    return JSON.parse(trimmed);
  } catch {
    return null;
  }
}

function readId(obj) {
  const candidates = ['_id', 'id', 'docid', 'doc_id', 'query_id', 'qid'];
  for (const key of candidates) {
    const raw = obj?.[key];
    if (raw == null) continue;
    const value = String(raw).trim();
    if (value) return value;
  }
  return null;
}

function readText(obj) {
  const candidates = ['text', 'contents', 'content', 'body', 'query', 'question'];
  for (const key of candidates) {
    const raw = obj?.[key];
    if (raw == null) continue;
    const value = String(raw).trim();
    if (value) return value;
  }
  return null;
}

function readTitle(obj) {
  const raw = obj?.title;
  if (raw == null) return '';
  return String(raw).trim();
}

async function convertCorpus(corpusGzPath, outPath) {
  await fsp.mkdir(path.dirname(outPath), { recursive: true });
  const gunzip = zlib.createGunzip();
  const input = fs.createReadStream(corpusGzPath);
  const rl = readline.createInterface({
    input: input.pipe(gunzip),
    crlfDelay: Infinity,
  });
  const out = fs.createWriteStream(outPath, { encoding: 'utf8' });

  let total = 0;
  let written = 0;
  for await (const line of rl) {
    total += 1;
    const obj = normalizeJsonlLine(line);
    if (!obj) continue;
    const id = readId(obj);
    const text = readText(obj);
    if (!id || !text) continue;
    const row = {
      _id: id,
      title: readTitle(obj),
      text,
    };
    out.write(`${JSON.stringify(row)}\n`);
    written += 1;
  }
  out.end();
  await new Promise((resolve, reject) => {
    out.on('finish', resolve);
    out.on('error', reject);
  });
  return { total, written };
}

async function convertQueries(queriesGzPath, outPath) {
  await fsp.mkdir(path.dirname(outPath), { recursive: true });
  const gunzip = zlib.createGunzip();
  const input = fs.createReadStream(queriesGzPath);
  const rl = readline.createInterface({
    input: input.pipe(gunzip),
    crlfDelay: Infinity,
  });
  const out = fs.createWriteStream(outPath, { encoding: 'utf8' });

  let total = 0;
  let written = 0;
  for await (const line of rl) {
    total += 1;
    const obj = normalizeJsonlLine(line);
    if (!obj) continue;
    const id = readId(obj);
    const text = readText(obj);
    if (!id || !text) continue;
    out.write(`${JSON.stringify({ _id: id, text })}\n`);
    written += 1;
  }
  out.end();
  await new Promise((resolve, reject) => {
    out.on('finish', resolve);
    out.on('error', reject);
  });
  return { total, written };
}

function parseQrelsLine(parts, hasHeader) {
  if (parts.length < 3) return null;
  if (parts.length >= 4 && !hasHeader) {
    const qid = String(parts[0] || '').trim();
    const did = String(parts[2] || '').trim();
    const rel = Number.parseInt(String(parts[3] || '0'), 10);
    if (!qid || !did || !Number.isFinite(rel)) return null;
    return { qid, did, rel };
  }
  const qid = String(parts[0] || '').trim();
  const did = String(parts[1] || '').trim();
  const rel = Number.parseInt(String(parts[2] || '0'), 10);
  if (!qid || !did || !Number.isFinite(rel)) return null;
  return { qid, did, rel };
}

async function convertQrels(qrelsPath, outPath) {
  await fsp.mkdir(path.dirname(outPath), { recursive: true });
  const raw = await fsp.readFile(qrelsPath, 'utf8');
  const lines = raw.split(/\r?\n/);
  const nonEmpty = lines.map((line) => line.trim()).filter((line) => line.length > 0);
  const header = nonEmpty[0] || '';
  const hasHeader = header.includes('query-id') || header.includes('query_id');

  const out = fs.createWriteStream(outPath, { encoding: 'utf8' });
  out.write('query-id\tcorpus-id\tscore\n');

  let total = 0;
  let written = 0;
  for (let i = hasHeader ? 1 : 0; i < nonEmpty.length; i += 1) {
    total += 1;
    const parts = nonEmpty[i].split('\t');
    const parsed = parseQrelsLine(parts, hasHeader);
    if (!parsed) continue;
    out.write(`${parsed.qid}\t${parsed.did}\t${parsed.rel}\n`);
    written += 1;
  }
  out.end();
  await new Promise((resolve, reject) => {
    out.on('finish', resolve);
    out.on('error', reject);
  });
  return { total, written };
}

async function ensureOutputFresh(outDir, overwrite) {
  const out = normalizePath(outDir);
  const corpusPath = path.join(out, 'corpus.jsonl');
  const queriesPath = path.join(out, 'queries.jsonl');
  const qrelsPath = path.join(out, 'qrels', 'test.tsv');
  if (!overwrite && (fs.existsSync(corpusPath) || fs.existsSync(queriesPath) || fs.existsSync(qrelsPath))) {
    throw new Error(
      `output_exists:${out}. Pass --overwrite to replace existing BEIR files.`,
    );
  }
  await fsp.mkdir(path.join(out, 'qrels'), { recursive: true });
  return { out, corpusPath, queriesPath, qrelsPath };
}

async function main() {
  const args = parseArgs(process.argv);
  const output = await ensureOutputFresh(args.outDir, args.overwrite);
  const downloadDir = normalizePath(args.downloadDir);

  const corpusGzLocal = await ensureLocalCopy({
    source: args.corpusGz,
    targetPath: path.join(downloadDir, 'corpus.jsonl.gz'),
    forceDownload: args.forceDownload,
  });
  const queriesGzLocal = await ensureLocalCopy({
    source: args.queriesGz,
    targetPath: path.join(downloadDir, 'test.jsonl.gz'),
    forceDownload: args.forceDownload,
  });
  const qrelsLocal = await ensureLocalCopy({
    source: args.qrelsTsv,
    targetPath: path.join(downloadDir, 'qrels.mldr-v1.0-en-test.tsv'),
    forceDownload: args.forceDownload,
  });

  const [corpusStats, queryStats, qrelsStats] = await Promise.all([
    convertCorpus(corpusGzLocal, output.corpusPath),
    convertQueries(queriesGzLocal, output.queriesPath),
    convertQrels(qrelsLocal, output.qrelsPath),
  ]);

  const metadataPath = path.join(output.out, 'conversion-metadata.json');
  const metadata = {
    kind: 'mldr-en-beir-conversion.v1',
    generatedAt: new Date().toISOString(),
    outputDir: output.out,
    inputs: {
      corpusGz: args.corpusGz,
      queriesGz: args.queriesGz,
      qrelsTsv: args.qrelsTsv,
      localCorpusGz: corpusGzLocal,
      localQueriesGz: queriesGzLocal,
      localQrelsTsv: qrelsLocal,
    },
    stats: {
      corpus: corpusStats,
      queries: queryStats,
      qrels: qrelsStats,
    },
    outputs: {
      corpusJsonl: output.corpusPath,
      queriesJsonl: output.queriesPath,
      qrelsTestTsv: output.qrelsPath,
    },
  };
  await fsp.writeFile(metadataPath, `${JSON.stringify(metadata, null, 2)}\n`, 'utf8');

  console.log(
    JSON.stringify(
      {
        ok: true,
        outDir: output.out,
        metadata: metadataPath,
        stats: metadata.stats,
      },
      null,
      2,
    ),
  );
}

main().catch((err) => {
  console.error(err?.stack || String(err));
  process.exit(1);
});
