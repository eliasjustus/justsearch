#!/usr/bin/env node
/**
 * Local-only visual regression diffs for JustSearch UI screenshots.
 *
 * Policy:
 * - Baselines are NOT committed to the repo.
 * - User provides a baseline directory via JUSTSEARCH_UI_VISUAL_BASELINE_DIR.
 * - Current screenshots come from JUSTSEARCH_UI_VISUAL_CURRENT_DIR (defaults to agent-review/ui-screenshots).
 *
 * Usage (PowerShell):
 *   $env:JUSTSEARCH_UI_VISUAL_BASELINE_DIR = "D:\\justsearch-baselines\\ui"
 *   node .\\modules\\ui-web\\scripts\\visual-regression.mjs --update-baseline
 *   node .\\modules\\ui-web\\scripts\\visual-regression.mjs
 */

'use strict';

const fs = require('fs');
const fsp = require('fs/promises');
const path = require('path');

async function mkdirp(p) {
  await fsp.mkdir(p, { recursive: true });
}

function toPosix(p) {
  return String(p).split(path.sep).join('/');
}

function parseArgs(argv) {
  const out = {
    updateBaseline: false,
    baselineDir: process.env.JUSTSEARCH_UI_VISUAL_BASELINE_DIR || '',
    currentDir:
      process.env.JUSTSEARCH_UI_VISUAL_CURRENT_DIR ||
      path.resolve(process.cwd(), '..', '..', '..', 'agent-review', 'ui-screenshots'),
    outDir:
      process.env.JUSTSEARCH_UI_VISUAL_DIFF_DIR ||
      path.resolve(process.cwd(), '..', '..', '..', 'agent-review', 'ui-visual-diffs'),
    thresholdRatio: Number.isFinite(Number(process.env.JUSTSEARCH_UI_VISUAL_DIFF_THRESHOLD_RATIO))
      ? Number(process.env.JUSTSEARCH_UI_VISUAL_DIFF_THRESHOLD_RATIO)
      : 0.001, // 0.1% pixels by default
  };

  for (const tok of argv) {
    if (tok === '--update-baseline') out.updateBaseline = true;
    if (tok.startsWith('--baseline=')) out.baselineDir = tok.split('=', 2)[1] || out.baselineDir;
    if (tok.startsWith('--current=')) out.currentDir = tok.split('=', 2)[1] || out.currentDir;
    if (tok.startsWith('--out=')) out.outDir = tok.split('=', 2)[1] || out.outDir;
    if (tok.startsWith('--threshold=')) {
      const n = Number(tok.split('=', 2)[1]);
      if (Number.isFinite(n) && n >= 0) out.thresholdRatio = n;
    }
    if (tok === '--help' || tok === '-h') out.help = true;
  }

  return out;
}

function printUsage() {
  console.log(
    [
      'Usage: node modules/ui-web/scripts/visual-regression.mjs [options]',
      '',
      'Options:',
      '  --update-baseline              Copy current screenshots into baseline dir',
      '  --baseline=<dir>               Baseline dir (or set JUSTSEARCH_UI_VISUAL_BASELINE_DIR)',
      '  --current=<dir>                Current screenshots dir (default agent-review/ui-screenshots)',
      '  --out=<dir>                    Output dir for diffs (default agent-review/ui-visual-diffs)',
      '  --threshold=<ratio>            Fail threshold as fraction of pixels (default 0.001 = 0.1%)',
      '',
      'Environment:',
      '  JUSTSEARCH_UI_VISUAL_BASELINE_DIR',
      '  JUSTSEARCH_UI_VISUAL_CURRENT_DIR',
      '  JUSTSEARCH_UI_VISUAL_DIFF_DIR',
      '  JUSTSEARCH_UI_VISUAL_DIFF_THRESHOLD_RATIO',
    ].join('\n')
  );
}

function listPngs(dir) {
  if (!fs.existsSync(dir)) return [];
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  return entries
    .filter((e) => e.isFile() && e.name.toLowerCase().endsWith('.png'))
    .map((e) => path.join(dir, e.name))
    .sort((a, b) => path.basename(a).localeCompare(path.basename(b)));
}

async function copyFileAtomic(src, dst) {
  await mkdirp(path.dirname(dst));
  const tmp = `${dst}.tmp`;
  await fsp.copyFile(src, tmp);
  await fsp.rename(tmp, dst);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    printUsage();
    process.exit(0);
  }

  const baselineDir = args.baselineDir ? path.resolve(args.baselineDir) : '';
  const currentDir = path.resolve(args.currentDir);
  const outDir = path.resolve(args.outDir);

  if (!baselineDir) {
    console.error('ERROR: Missing baseline dir. Set JUSTSEARCH_UI_VISUAL_BASELINE_DIR or pass --baseline=<dir>.');
    console.error(`Current dir: ${toPosix(currentDir)}`);
    process.exit(2);
  }
  if (!fs.existsSync(currentDir)) {
    console.error(`ERROR: Current screenshots dir not found: ${toPosix(currentDir)}`);
    process.exit(2);
  }

  const currentPngs = listPngs(currentDir);
  if (currentPngs.length === 0) {
    console.error(`ERROR: No PNGs found in current dir: ${toPosix(currentDir)}`);
    process.exit(2);
  }

  if (args.updateBaseline) {
    await mkdirp(baselineDir);
    for (const src of currentPngs) {
      const dst = path.join(baselineDir, path.basename(src));
      await copyFileAtomic(src, dst);
    }
    console.log(`Baseline updated (${currentPngs.length} files): ${toPosix(baselineDir)}`);
    process.exit(0);
  }

  // Diff mode
  await mkdirp(outDir);
  const baselinePngs = listPngs(baselineDir);
  if (baselinePngs.length === 0) {
    console.error(`ERROR: No baseline PNGs found in: ${toPosix(baselineDir)}`);
    console.error('Run with --update-baseline to create baselines from current screenshots.');
    process.exit(2);
  }

  const baselineByName = new Map(baselinePngs.map((p) => [path.basename(p), p]));
  const currentByName = new Map(currentPngs.map((p) => [path.basename(p), p]));
  const names = Array.from(new Set([...baselineByName.keys(), ...currentByName.keys()])).sort();

  // Lazy require so this script can print helpful errors without install-time failures.
  let PNG;
  let pixelmatch;
  try {
     
    PNG = require('pngjs').PNG;
     
    const pmMod = require('pixelmatch');
    pixelmatch = typeof pmMod === 'function' ? pmMod : pmMod.default;
  } catch (e) {
    console.error('ERROR: Missing npm deps for visual diff. Install in modules/ui-web:');
    console.error('  npm i -D pixelmatch pngjs');
    console.error(`Underlying error: ${e?.message || String(e)}`);
    process.exit(2);
  }

  const summary = {
    baselineDir: toPosix(baselineDir),
    currentDir: toPosix(currentDir),
    outDir: toPosix(outDir),
    thresholdRatio: args.thresholdRatio,
    compared: [],
    missing: {
      baseline: [],
      current: [],
    },
    failures: [],
  };

  let anyFailed = false;

  for (const name of names) {
    const b = baselineByName.get(name) || null;
    const c = currentByName.get(name) || null;
    if (!b) {
      summary.missing.baseline.push(name);
      continue;
    }
    if (!c) {
      summary.missing.current.push(name);
      continue;
    }

    const bPng = PNG.sync.read(fs.readFileSync(b));
    const cPng = PNG.sync.read(fs.readFileSync(c));

    if (bPng.width !== cPng.width || bPng.height !== cPng.height) {
      const msg = `size mismatch baseline=${bPng.width}x${bPng.height} current=${cPng.width}x${cPng.height}`;
      summary.failures.push({ name, reason: msg });
      anyFailed = true;
      continue;
    }

    const diff = new PNG({ width: bPng.width, height: bPng.height });
    const diffPixels = pixelmatch(
      bPng.data,
      cPng.data,
      diff.data,
      bPng.width,
      bPng.height,
      {
        threshold: 0.1,
        includeAA: true,
      }
    );
    const total = bPng.width * bPng.height;
    const ratio = total > 0 ? diffPixels / total : 0;
    const diffFile = path.join(outDir, name.replace(/\\.png$/i, '.diff.png'));
    await fsp.writeFile(diffFile, PNG.sync.write(diff));

    const entry = {
      name,
      diffPixels,
      totalPixels: total,
      diffRatio: ratio,
      baseline: toPosix(b),
      current: toPosix(c),
      diff: toPosix(diffFile),
    };
    summary.compared.push(entry);

    if (ratio > args.thresholdRatio) {
      anyFailed = true;
      summary.failures.push({ name, reason: `diffRatio ${ratio} > threshold ${args.thresholdRatio}`, ...entry });
    }
  }

  const summaryPath = path.join(outDir, 'summary.json');
  await fsp.writeFile(summaryPath, JSON.stringify(summary, null, 2) + '\n', 'utf8');

  console.log(`Visual diff summary: ${toPosix(summaryPath)}`);
  console.log(`Compared: ${summary.compared.length}, failures: ${summary.failures.length}`);
  if (summary.missing.baseline.length > 0 || summary.missing.current.length > 0) {
    console.log(
      `Missing files: baseline=${summary.missing.baseline.length} current=${summary.missing.current.length}`
    );
  }

  process.exit(anyFailed ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});


