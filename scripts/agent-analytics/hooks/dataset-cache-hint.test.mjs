/**
 * Tempdoc 709 discoverability — unit tests for dataset-cache-hint's pure classifier.
 *
 * The classifier decides whether (and which) non-blocking hint fires. A wrong predicate
 * either nags on unrelated `curl`/`cat` commands, or stays silent on the exact case it
 * exists to catch: an agent about to run a raw multi-GB corpus download that bypasses the
 * shared cache. This corpus is the living regression guard — new false-positives/negatives
 * are fixed here (corpus + predicate), not by a redesign.
 *
 * Run with: `node scripts/agent-analytics/hooks/dataset-cache-hint.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { classifyCorpusFetch } from './dataset-cache-hint.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

// [command, expectedClass]  (null = must stay silent)
const CORPUS = [
  // cache-backed: already using the cache-routed jseval fetcher → reassure + name the kill switch.
  ['python -m jseval corpus-fetch-clerc --name legal-clerc-probe --seed 666 --n-queries 20', 'cache-backed'],
  ['python -m jseval corpus-fetch-miracl --name miracl-de --lang de --seed 666 --n-docs 200', 'cache-backed'],
  ['jseval corpus-fetch-clerc --name x --seed 1 --n-queries 5', 'cache-backed'],
  ['PYTHONPATH=scripts/jseval python -m jseval corpus-fetch-miracl --lang yo --seed 7 --n-docs 50', 'cache-backed'],

  // ad-hoc: a raw download verb + a known corpus/dataset signal → redirect to the cache-backed cmd.
  ['curl -L https://huggingface.co/datasets/jhu-clsp/CLERC/resolve/main/collection/collection.doc.tsv.gz -o c.tsv.gz', 'ad-hoc'],
  ['wget https://huggingface.co/datasets/miracl/miracl-corpus/resolve/main/x.jsonl.gz', 'ad-hoc'],
  ['huggingface-cli download jhu-clsp/CLERC --repo-type dataset', 'ad-hoc'],
  ['hf download miracl/miracl-corpus --repo-type dataset', 'ad-hoc'],
  ['git clone https://huggingface.co/datasets/jhu-clsp/CLERC', 'ad-hoc'],
  ['aria2c https://huggingface.co/datasets/BeIR/scifact/resolve/main/x', 'ad-hoc'], // any /datasets/ path bypasses the cache

  // NEGATIVES — must stay silent.
  ['cat datasets/mixed/legal-clerc-200/docs.jsonl | head', null], // reading a local corpus, not fetching
  ['ls -la scripts/jseval/tmp/dataset-fetch-cache/clerc-raw/', null],
  ['curl -L https://huggingface.co/bartowski/model-GGUF/resolve/main/model.gguf -o m.gguf', null], // model download, not a dataset
  ['curl -s http://127.0.0.1:7860/api/health', null], // backend poll
  ['wget https://example.com/notes.txt', null], // unrelated download
  ['git clone https://github.com/some/repo', null], // code clone, no corpus signal
  ['python -m jseval run --pipeline', null], // jseval, but not a corpus-fetch
  ['python -m jseval corpus-build --name x', null], // a different jseval corpus subcommand (not cache-routed fetch)
  ['grep -rn "miracl-corpus" scripts/', null], // searching for the string, not downloading
  ['echo "curl huggingface.co/datasets/foo"', null], // echoing a string, not running
  ['', null],
];

for (const [cmd, want] of CORPUS) {
  run(`${want ?? 'silent'}: ${cmd}`, () => {
    assert.equal(classifyCorpusFetch(cmd), want);
  });
}

// --- guard the "right reason" (rule:critical-analysis-pass) ---
run('ad-hoc requires BOTH a download verb AND a corpus signal', () => {
  // corpus signal present but no download verb → silent (it's a search/read, not a fetch)
  assert.equal(classifyCorpusFetch('grep jhu-clsp/CLERC notes.md'), null);
  // download verb present but no corpus signal → silent (unrelated download)
  assert.equal(classifyCorpusFetch('curl https://example.com/x.zip -o x.zip'), null);
  // both → fires
  assert.equal(classifyCorpusFetch('curl https://huggingface.co/datasets/jhu-clsp/CLERC/x'), 'ad-hoc');
});
run('cache-backed wins even if an ad-hoc signal is also present', () => {
  assert.equal(
    classifyCorpusFetch('python -m jseval corpus-fetch-clerc # from huggingface.co/datasets/jhu-clsp/CLERC'),
    'cache-backed',
  );
});
run('undefined command does not fire', () => {
  assert.equal(classifyCorpusFetch(undefined), null);
});

// --- emit-shape contract: the hook emits additionalContext on a match, nothing otherwise ---
const HOOK = resolve(dirname(fileURLToPath(import.meta.url)), 'dataset-cache-hint.mjs');
function runHook(command) {
  return execFileSync('node', [HOOK], {
    input: JSON.stringify({ tool_name: 'Bash', tool_input: { command } }),
    encoding: 'utf8',
  });
}
run('cache-backed match emits a PreToolUse additionalContext JSON (advisory, never blocks)', () => {
  const out = runHook('python -m jseval corpus-fetch-clerc --seed 1 --n-queries 5 --name x');
  const parsed = JSON.parse(out);
  assert.equal(parsed.hookSpecificOutput.hookEventName, 'PreToolUse');
  assert.ok(parsed.hookSpecificOutput.additionalContext.includes('709'));
  assert.ok(parsed.hookSpecificOutput.additionalContext.includes('cross-worktree'));
  assert.equal(parsed.hookSpecificOutput.permissionDecision, undefined);
});
run('ad-hoc match emits the redirect hint', () => {
  const out = runHook('curl -L https://huggingface.co/datasets/jhu-clsp/CLERC/resolve/main/x -o x');
  const parsed = JSON.parse(out);
  assert.ok(parsed.hookSpecificOutput.additionalContext.includes('BYPASSES'));
  assert.ok(parsed.hookSpecificOutput.additionalContext.includes('corpus-fetch'));
});
run('non-Bash tool emits nothing', () => {
  const out = execFileSync('node', [HOOK], {
    input: JSON.stringify({ tool_name: 'Read', tool_input: { file_path: 'x' } }),
    encoding: 'utf8',
  });
  assert.equal(out.trim(), '');
});
run('negative command emits nothing', () => {
  assert.equal(runHook('curl -s http://127.0.0.1:7860/api/health').trim(), '');
});

// --- Report ---
if (failures.length > 0) {
  console.error(`dataset-cache-hint.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`dataset-cache-hint.test: all ${passed} checks passed`);
