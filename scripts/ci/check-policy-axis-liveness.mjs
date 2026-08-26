#!/usr/bin/env node
/**
 * policy-axis liveness check — tempdoc 879 §D.7.
 *
 * A declaration that no mechanism can contradict is not a constraint; it is a comment with a
 * compile-time cost, and it accrues authority it never earned. `OperationPolicy` accumulated five
 * such axes: `retry` had zero readers while a hard-coded `risk == LOW` decided retries; `rateLimit`
 * had zero readers AND zero non-empty declarations while an accepted ADR claimed the executor
 * throttled on it; `audit` was read only by a build-time lint while its own javadoc promised a
 * runtime effect.
 *
 * This gate stops the class recurring on the one record it has already occurred on twice. For every
 * component of `OperationPolicy` it asserts:
 *
 *   (1) READER — at least one production (`src/main`) reader outside the registry validators.
 *       A validator-only axis is not automatically dead (a structural invariant is a real job), but
 *       it may not be validator-only when the axis promises a RUNTIME effect. That distinction is
 *       prose; what the gate can enforce is that *something outside the linter* consults it.
 *
 *   (2) DECLARATION — for `Optional<...>` axes only, at least one catalog site that actually
 *       declares a value (`Optional.of(...)` in a policy construction, or the `withX(...)` wither).
 *       This is the rule `rateLimit` failed: it had a wire projection as a "reader" and was
 *       nonetheless inert, because no operation ever put anything in it.
 *
 * There is deliberately NO annotation escape hatch. An opt-out for "declared ahead of its consumer"
 * is exactly the deferral the wire-or-delete rule bans, and would be reached for immediately. If an
 * axis cannot pass, the answer is to wire it or delete it.
 *
 * Usage: `node scripts/ci/check-policy-axis-liveness.mjs` (from the repo root). Exit 0 = OK, 1 = drift.
 */

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const POLICY_SOURCE =
  'modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/OperationPolicy.java';
const CATALOG_DIR = 'modules/app-services/src/main/java/io/justsearch/app/services/registry/operations';
const VALIDATOR_MARKER = '/registry/validator/';
const MODULES_ROOT = 'modules';

function fail(messages) {
  console.error('policy-axis liveness: FAIL\n  - ' + messages.join('\n  - '));
  process.exit(1);
}

if (!existsSync(POLICY_SOURCE)) {
  fail([`authority missing: ${POLICY_SOURCE}`]);
}

/**
 * Extract the canonical record's component names + types.
 *
 * The canonical header is the `public record OperationPolicy(...)` declaration; the
 * backwards-compat constructor overloads below it are not parsed (they mirror a prefix of the
 * same list, so parsing them would double-count).
 */
function parseComponents(src) {
  const start = src.indexOf('public record OperationPolicy(');
  if (start < 0) return null;
  const open = src.indexOf('(', start);
  let depth = 0;
  let end = -1;
  for (let i = open; i < src.length; i += 1) {
    if (src[i] === '(') depth += 1;
    else if (src[i] === ')') {
      depth -= 1;
      if (depth === 0) {
        end = i;
        break;
      }
    }
  }
  if (end < 0) return null;
  const body = src.slice(open + 1, end);
  // Split on commas that are not inside generics (`Set<RequiredCapability>`, `Optional<Duration>`).
  const parts = [];
  let angle = 0;
  let current = '';
  for (const ch of body) {
    if (ch === '<') angle += 1;
    else if (ch === '>') angle -= 1;
    if (ch === ',' && angle === 0) {
      parts.push(current);
      current = '';
    } else {
      current += ch;
    }
  }
  parts.push(current);
  return parts
    .map((p) => p.replace(/\s+/g, ' ').trim())
    .filter(Boolean)
    .map((p) => {
      const lastSpace = p.lastIndexOf(' ');
      return { type: p.slice(0, lastSpace).trim(), name: p.slice(lastSpace + 1).trim() };
    })
    .filter((c) => c.name && c.type);
}

const policySrc = readFileSync(POLICY_SOURCE, 'utf8');
const components = parseComponents(policySrc);
if (!components || components.length === 0) {
  fail([`could not parse the canonical record header in ${POLICY_SOURCE}`]);
}

/** Every production Java source under modules/, excluding the authority itself. */
function collectMainSources(dir, acc) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    const st = statSync(full);
    if (st.isDirectory()) {
      if (entry === 'build' || entry === 'node_modules' || entry === 'target') continue;
      collectMainSources(full, acc);
    } else if (entry.endsWith('.java')) {
      const norm = full.split('\\').join('/');
      if (!norm.includes('/src/main/java/')) continue;
      if (norm.endsWith('OperationPolicy.java')) continue;
      acc.push(norm);
    }
  }
  return acc;
}

const sources = collectMainSources(MODULES_ROOT, []);
const readerIndex = new Map(); // axis name -> [files]
/**
 * Strip comments before matching. Without this the gate is trivially satisfied by prose: the
 * catalog's own javadoc contained the literal `OperationPolicy.retry()` while asserting that the
 * axis had no reader — a comment describing the absence would have counted as the presence.
 */
function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, ' ');
}

for (const file of sources) {
  // A catalog is a DECLARATION site, not a consumer. Counting one as a reader would let an axis
  // satisfy the gate by being declared, which is the exact state this gate exists to reject.
  if (file.includes('/registry/operations/')) continue;
  const src = stripComments(readFileSync(file, 'utf8'));
  // Records expose components as no-arg accessors, so a read is `.name()`. That alone is far too
  // loose across a tree this size (`.risk()`, `.confirm()` and friends exist on other types), so a
  // file only counts when it ALSO reaches an OperationPolicy: either it names the type or it calls
  // `policy()`. False negatives here are the safe direction — they make the gate stricter.
  const touchesPolicy = src.includes('OperationPolicy') || src.includes('policy()');
  if (!touchesPolicy) continue;
  for (const comp of components) {
    const re = new RegExp(`\\.${comp.name}\\(\\)`);
    if (re.test(src)) {
      if (!readerIndex.has(comp.name)) readerIndex.set(comp.name, []);
      readerIndex.get(comp.name).push(file);
    }
  }
}

/** Catalog sources — where declarations live. */
const catalogSources = existsSync(CATALOG_DIR)
  ? readdirSync(CATALOG_DIR)
      .filter((f) => f.endsWith('.java'))
      .map((f) => join(CATALOG_DIR, f).split('\\').join('/'))
  : [];
const catalogText = catalogSources.map((f) => readFileSync(f, 'utf8')).join('\n');

const failures = [];

for (const comp of components) {
  const readers = (readerIndex.get(comp.name) ?? []).filter(
    (f) => !f.includes(VALIDATOR_MARKER),
  );
  if (readers.length === 0) {
    const validatorOnly = (readerIndex.get(comp.name) ?? []).length > 0;
    failures.push(
      `axis '${comp.name}' has no production reader outside the registry validators` +
        (validatorOnly ? ' (validator-only)' : ' (no reader at all)') +
        ' — wire it to a consumer whose behaviour the declaration changes, or delete the axis' +
        ' (field, every declaration, validators, wire projection, schema, docs).',
    );
    continue;
  }

  // Optional axes additionally need a site that declares a value. This is the rule `rateLimit`
  // failed while still having a wire-projection "reader".
  if (comp.type.startsWith('Optional<')) {
    // Two ways an Optional axis can be declared: through its `withX(...)` wither (attributable by
    // name) or positionally inside a `new OperationPolicy(...)` call (attributable only by index).
    const witherName = 'with' + comp.name.charAt(0).toUpperCase() + comp.name.slice(1);
    const declaredByWither = catalogText.includes(`${witherName}(`);
    if (!declaredByWither && !declaresOptionalPositionally(comp, catalogSources)) {
      failures.push(
        `axis '${comp.name}' is Optional and no catalog declares a value for it — every` +
          ` construction site passes Optional.empty(). An axis nothing declares cannot change any` +
          ` behaviour; delete it, or declare it where it is meant to apply.`,
      );
    }
  }
}

/**
 * Positional attribution for an `Optional<...>` component: find each `new OperationPolicy(`
 * construction, split its top-level arguments, and check whether the argument at this component's
 * index is something other than `Optional.empty()`.
 *
 * The backwards-compat overloads mirror a prefix of the canonical list, so an index that exists in
 * a shorter call is still the same component — which is why positional attribution is sound here.
 */
function declaresOptionalPositionally(comp, files) {
  const index = components.indexOf(comp);
  for (const file of files) {
    const src = readFileSync(file, 'utf8');
    let from = 0;
    for (;;) {
      const at = src.indexOf('new OperationPolicy(', from);
      if (at < 0) break;
      const open = src.indexOf('(', at);
      let depth = 0;
      let end = -1;
      for (let i = open; i < src.length; i += 1) {
        if (src[i] === '(') depth += 1;
        else if (src[i] === ')') {
          depth -= 1;
          if (depth === 0) {
            end = i;
            break;
          }
        }
      }
      if (end < 0) break;
      const args = splitTopLevel(src.slice(open + 1, end));
      if (index < args.length) {
        const arg = args[index].replace(/\s+/g, '');
        if (arg && arg !== 'Optional.empty()') return true;
      }
      from = end + 1;
    }
  }
  return false;
}

function splitTopLevel(body) {
  const parts = [];
  let depth = 0;
  let angle = 0;
  let current = '';
  for (const ch of body) {
    if (ch === '(' || ch === '[' || ch === '{') depth += 1;
    else if (ch === ')' || ch === ']' || ch === '}') depth -= 1;
    else if (ch === '<') angle += 1;
    else if (ch === '>') angle -= 1;
    if (ch === ',' && depth === 0 && angle === 0) {
      parts.push(current);
      current = '';
    } else {
      current += ch;
    }
  }
  parts.push(current);
  return parts;
}

if (failures.length > 0) {
  fail(failures);
}

console.log(
  `policy-axis liveness: OK — ${components.length} axes, each with a production reader` +
    ` outside the validators (${components.map((c) => c.name).join(', ')}).`,
);
