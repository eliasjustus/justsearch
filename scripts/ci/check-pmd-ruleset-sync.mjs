#!/usr/bin/env node
/**
 * The three PMD rulesets under `config/pmd/` must stay one ruleset with declared subtractions.
 *
 * `ruleset.xml` is the authority. `ruleset-cli-tools.xml` (CLI entry points) and
 * `ruleset-tests.xml` (every non-`main` Java source set) inline its rules rather than
 * referencing it, because PMD's `rule ref` resolves against the CLASSPATH, not the working
 * directory. That inlining is not a style choice with a "KEEP IN SYNC" comment for a guard —
 * it is a silent-dormancy hazard, twice over (tempdoc 930 §22.2 follow-up 10):
 *
 *   1. A rule added to `ruleset.xml` and forgotten in a derived file simply stops applying to
 *      that module set, with nothing red. `CommentContent` sat un-applied to `ssot-tools` and
 *      `core-contracts` this way until follow-up 2 noticed.
 *   2. A `ref` PMD cannot resolve (a file path, a typo) makes PMD log "Cannot resolve
 *      rule/ruleset reference", analyse ZERO files, and exit 0 — Gradle reports BUILD
 *      SUCCESSFUL. Probed 2026-09-05 with `<rule ref="config/pmd/ruleset.xml"/>`.
 *
 * So: every derived ruleset carries exactly the authority's rules minus its declared
 * subtraction, with byte-identical rule properties, every `ref` is a resolvable
 * `category/java/...` classpath reference, and each subtracted rule is named in the derived
 * file's `<description>` (the exclusion has to state its reason where a reader will find it).
 *
 * Run: `node scripts/ci/check-pmd-ruleset-sync.mjs`
 */
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = path.resolve(HERE, '..', '..');
export const AUTHORITY = 'config/pmd/ruleset.xml';

/**
 * Derived ruleset -> the rules it may drop. A rule is droppable only where its PREMISE is
 * false for that source population; the reason lives in the derived file's description.
 */
export const DERIVED = {
  'config/pmd/ruleset-cli-tools.xml': ['SystemPrintln', 'DoNotTerminateVM'],
  'config/pmd/ruleset-tests.xml': ['SystemPrintln', 'NonThreadSafeSingleton'],
};

const RULE_RE = /<rule\s+ref="([^"]+)"\s*(\/>|>([\s\S]*?)<\/rule>)/g;
const PROP_RE = /<property\s+name="([^"]+)"\s+value="([^"]*)"\s*\/>/g;

/** @returns {{rules: Map<string, string>, refs: string[], description: string}} */
export function parseRuleset(xml) {
  const rules = new Map();
  const refs = [];
  for (const m of xml.matchAll(RULE_RE)) {
    const ref = m[1];
    refs.push(ref);
    const name = ref.split('/').pop();
    const props = [...(m[3] ?? '').matchAll(PROP_RE)].map(([, k, v]) => `${k}=${v}`).sort();
    rules.set(name, `${ref}|${props.join(';')}`);
  }
  const desc = xml.match(/<description>([\s\S]*?)<\/description>/);
  return { rules, refs, description: desc ? desc[1] : '' };
}

export function checkAll(root = REPO_ROOT) {
  const errors = [];
  const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8');
  const authority = parseRuleset(read(AUTHORITY));

  for (const ref of authority.refs) {
    if (!ref.startsWith('category/java/')) {
      errors.push(`${AUTHORITY}: ref "${ref}" is not a category/java/... classpath reference`);
    }
  }

  for (const [rel, droppable] of Object.entries(DERIVED)) {
    const derived = parseRuleset(read(rel));
    for (const ref of derived.refs) {
      if (!ref.startsWith('category/java/')) {
        errors.push(`${rel}: ref "${ref}" is not a category/java/... classpath reference`);
      }
    }
    for (const name of droppable) {
      if (!authority.rules.has(name)) {
        errors.push(`${rel}: declares "${name}" droppable, but ${AUTHORITY} does not carry it`);
      }
      if (derived.rules.has(name)) {
        errors.push(`${rel}: carries "${name}", which it declares droppable — drop it or update DERIVED`);
      }
      if (!derived.description.includes(name)) {
        errors.push(`${rel}: drops "${name}" without naming it in <description> — state the reason there`);
      }
    }
    for (const [name, sig] of authority.rules) {
      if (droppable.includes(name)) continue;
      if (!derived.rules.has(name)) {
        errors.push(`${rel}: missing "${name}" from ${AUTHORITY} (add it, or declare it droppable with a reason)`);
      } else if (derived.rules.get(name) !== sig) {
        errors.push(`${rel}: "${name}" properties differ from ${AUTHORITY}`);
      }
    }
    for (const name of derived.rules.keys()) {
      if (!authority.rules.has(name)) errors.push(`${rel}: carries "${name}", absent from ${AUTHORITY}`);
    }
  }
  return errors;
}

function main() {
  const errors = checkAll();
  if (errors.length === 0) {
    console.log(`check-pmd-ruleset-sync: OK (${Object.keys(DERIVED).length} derived rulesets in sync)`);
    return;
  }
  console.error('check-pmd-ruleset-sync: FAIL');
  for (const e of errors) console.error(`  ${e}`);
  process.exitCode = 1;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
