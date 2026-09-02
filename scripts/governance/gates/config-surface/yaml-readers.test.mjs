/**
 * Unit tests for the YAML-reader scanner + its verdict (tempdoc 883 decision 5).
 *
 * The gate's BITE was verified by hand against the real tree (seed an unread key in
 * `config/application.yaml` -> `config-surface` exits 1 -> remove -> exits 0), and the two keys
 * tempdoc 882 found by hand were the scanner's first real output. This file pins the parts that
 * decide that outcome, so a refactor cannot quietly turn the gate into a reporter — "reachable but
 * advisory" is its own failure mode (tempdoc 799 §C.3), distinct from "not wired at all".
 *
 * The three assertions that matter most are the ones about what does NOT count as a reader: the
 * first draft of this scanner accepted an ANCESTOR string literal, and `"search"` occurs in Java
 * for a hundred reasons, so every key in the file came back read and the gate reported green on a
 * corpus with two known-dead keys in it. A gate that reports green about a claim it cannot see is
 * worse than no gate.
 *
 * Run: `node scripts/governance/gates/config-surface/yaml-readers.test.mjs`
 */

import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import {
  flattenYamlKeys,
  extractPathChains,
  extractPutYamlPaths,
  scanYamlReaders,
} from './yaml-readers.mjs';
import { verdictForUnreadYamlKey } from './truth-table.mjs';

let passed = 0;
const failures = [];
const check = (name, fn) => {
  try {
    fn();
    passed += 1;
    console.log(`  ok  ${name}`);
  } catch (e) {
    failures.push(`${name}: ${e.message}`);
    console.log(`  FAIL ${name}: ${e.message}`);
  }
};

console.log('[test-config-surface-yaml-readers]');

// ---------------------------------------------------------------- flattening

check('a nested mapping flattens to dotted leaf paths', () => {
  const keys = flattenYamlKeys({ search: { hybrid: { bm25_k: 200, ann_k: 200 } } });
  assert.deepEqual([...keys].sort(), ['search.hybrid.ann_k', 'search.hybrid.bm25_k']);
});

check('a null value is a comment-shaped placeholder, not a setting', () => {
  // `app:` in the real file carries only comments. Reporting it would be a false positive on
  // every commented-out group, which is how a gate gets switched off.
  const keys = flattenYamlKeys({ app: null, llm: { enabled: true } });
  assert.deepEqual([...keys], ['llm.enabled']);
});

check('an empty list IS a value', () => {
  // `index.ocr.languages: []` means "no languages", which the resolver reads. Dropping empty
  // lists would make a real setting invisible to the scan.
  const keys = flattenYamlKeys({ index: { ocr: { languages: [] } } });
  assert.deepEqual([...keys], ['index.ocr.languages']);
});

check('a false or zero value is a value, not an absence', () => {
  const keys = flattenYamlKeys({ a: { b: false, c: 0, d: '' } });
  assert.deepEqual([...keys].sort(), ['a.b', 'a.c', 'a.d']);
});

// ---------------------------------------------------------------- path chains

check('a .path() chain yields its multi-segment prefixes', () => {
  const chains = extractPathChains('JsonNode n = root.path("index").path("ocr").path("languages");');
  assert.ok(chains.has('index.ocr'));
  assert.ok(chains.has('index.ocr.languages'));
});

check('a chain does NOT yield its single-segment prefix', () => {
  // Treating a walk into `index` as reading all of `index.*` would make every top-level group
  // unfalsifiable — the exact blind spot this scanner exists to close.
  const chains = extractPathChains('root.path("index").path("ocr")');
  assert.ok(!chains.has('index'));
});

check('a lone .path() call is not a chain', () => {
  assert.equal(extractPathChains('root.path("index")').size, 0);
});

// ---------------------------------------------------------------- putYaml path resolution

check('a putYaml* yamlPath relative to a bound node resolves to its absolute path', () => {
  // The real shape: `JsonNode searchRoot = root.path("search");` then a RELATIVE third argument.
  // Reading only string literals would miss this, which is precisely how `search.pipeline.profile`
  // stayed dead for months — its resolver key carries a `justsearch.` prefix the YAML path does not.
  const paths = extractPutYamlPaths(
    'JsonNode searchRoot = root.path("search");\n' +
      'putYamlFromNode("justsearch.search.pipeline.profile", searchRoot, "pipeline.profile");\n',
  );
  assert.ok(paths.has('search.pipeline.profile'));
  assert.ok(!paths.has('pipeline.profile'));
});

check('a putYaml* against `root` itself keeps the path absolute', () => {
  const paths = extractPutYamlPaths('putYamlInt("index.ocr.workers", root, "index.ocr.workers");');
  assert.ok(paths.has('index.ocr.workers'));
});

check('an unresolvable node expression is skipped, not guessed', () => {
  // Falling back to the literal rule is the safe direction: a guess here would mark a key read on
  // the strength of a node the scanner could not follow.
  const paths = extractPutYamlPaths('putYaml("a.b", someHelper.node(), "c.d");');
  assert.equal(paths.size, 0);
});

// ---------------------------------------------------------------- verdicts

check('an unbaselined unread yaml key FAILS — this is the gate biting', () => {
  const v = verdictForUnreadYamlKey({ key: 'search.pipeline.profile', baselined: false });
  assert.equal(v.status, 'fail');
  assert.equal(v.ruleId, 'config-surface/yaml-key-unread');
  assert.match(v.reason, /editing it does nothing/);
});

check('a baselined unread yaml key is info, not fail — known debt does not block', () => {
  const v = verdictForUnreadYamlKey({ key: 'search.pipeline.profile', baselined: true });
  assert.equal(v.status, 'info');
  assert.equal(v.ruleId, 'config-surface/yaml-key-unread-baselined');
});

// ---------------------------------------------------------------- end-to-end over a fixture tree

/** Build a throwaway repo-shaped tree: config/application.yaml + the three source files scanned. */
function fixture({ yaml, builder = '', consumer = '' }) {
  const root = mkdtempSync(join(tmpdir(), 'js-yamlgate-'));
  mkdirSync(join(root, 'config'), { recursive: true });
  writeFileSync(join(root, 'config', 'application.yaml'), yaml, 'utf8');
  const cfg = join(
    root, 'modules', 'configuration', 'src', 'main', 'java',
    'io', 'justsearch', 'configuration',
  );
  mkdirSync(join(cfg, 'resolved'), { recursive: true });
  writeFileSync(join(cfg, 'resolved', 'ResolvedConfigBuilder.java'), builder, 'utf8');
  const consumerDir = join(root, 'modules', 'app-services', 'src', 'main', 'java', 'io', 'justsearch');
  mkdirSync(consumerDir, { recursive: true });
  writeFileSync(join(consumerDir, 'Consumer.java'), consumer, 'utf8');
  return root;
}

check('POSITIVE fixture: a key with no contribution and no consumer is reported', () => {
  const root = fixture({ yaml: 'search:\n  pipeline:\n    profile: desktop-default\n' });
  try {
    const r = scanYamlReaders(root);
    assert.deepEqual(r.unreadYamlKeys, ['search.pipeline.profile']);
    assert.equal(r.totals.yamlKeys, 1);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

check('NEGATIVE fixture: a putYaml* yamlPath argument counts as a reader', () => {
  const root = fixture({
    yaml: 'search:\n  pipeline:\n    profile: desktop-default\n',
    builder:
      'putYaml("justsearch.search.pipeline.profile", root, "search.pipeline.profile");\n',
  });
  try {
    assert.deepEqual(scanYamlReaders(root).unreadYamlKeys, []);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

check('NEGATIVE fixture: a hand-walked node chain counts as a reader', () => {
  const root = fixture({
    yaml: 'index:\n  ocr:\n    languages: []\n',
    builder: 'JsonNode langs = root.path("index").path("ocr").path("languages");\n',
  });
  try {
    assert.deepEqual(scanYamlReaders(root).unreadYamlKeys, []);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

check('NEGATIVE fixture: a consumer naming the key outside configuration counts', () => {
  const root = fixture({
    yaml: 'search:\n  facets:\n    enabled: true\n',
    consumer: 'String k = "search.facets.enabled";\n',
  });
  try {
    assert.deepEqual(scanYamlReaders(root).unreadYamlKeys, []);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

check('an ANCESTOR string literal does NOT count as a reader', () => {
  // The regression this scanner shipped with in its first draft: accepting `"search"` marked every
  // key under `search:` read, and the two keys 882 found by hand came back green. Asserting the
  // key is still REPORTED here is what distinguishes "the scan ran" from "the scan saw nothing".
  const root = fixture({
    yaml: 'search:\n  pipeline:\n    profile: desktop-default\n',
    consumer: 'String bucket = "search";\nString p = "search.pipeline";\n',
  });
  try {
    assert.deepEqual(scanYamlReaders(root).unreadYamlKeys, ['search.pipeline.profile']);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

check('a missing application.yaml skips rather than reporting a clean bill of health', () => {
  const root = mkdtempSync(join(tmpdir(), 'js-yamlgate-empty-'));
  try {
    const r = scanYamlReaders(root);
    assert.equal(r.skipped, true);
    assert.deepEqual(r.unreadYamlKeys, []);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

check('unparseable YAML is reported, not swallowed as zero keys', () => {
  // A parse failure that returned {unreadYamlKeys: []} would read as "nothing dead" — the gate
  // reporting green about a file it could not open.
  const root = fixture({ yaml: 'search:\n  - bad\n    indent: [\n' });
  try {
    const r = scanYamlReaders(root);
    assert.ok(r.parseError, 'expected a parseError');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

console.log(`[test-config-surface-yaml-readers] ${passed} passed, ${failures.length} failed`);
if (failures.length > 0) {
  for (const f of failures) console.error('  - ' + f);
  process.exit(1);
}
