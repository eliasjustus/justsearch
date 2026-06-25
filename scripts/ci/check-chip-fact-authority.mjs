#!/usr/bin/env node
/**
 * chip-fact-authority gate — tempdoc 594 Move 2 (the rung-Gate backstop).
 *
 * A DeclaredAdaptiveItem chip that asserts a runtime/build FACT (a dimension / accelerator /
 * precision token) must use a `fact`-ref projected by the ONE Display-value authority
 * (`projectFact`, facts.ts), NOT a baked `label` literal. The structural close is Move 1b (the
 * factual form carries a fact-ref); this gate catches an author who hand-types a fact-shaped
 * literal in a chip declaration ("Embeddings 1024-d", "GPU cuda12", "Float32 vectors").
 *
 * Two checks, both register-driven (governance/chip-facts.v1.json):
 *  1. No `label:` string literal in a scoped file matches a declared fact-shape.
 *  2. Every declared factId resolves in the fact catalog (referential integrity — the gate's
 *     fact-shape catalog and the projector cannot silently diverge).
 */

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = join(__dirname, '..', '..');
const REGISTER = join(REPO_ROOT, 'governance', 'chip-facts.v1.json');

const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));
const failures = [];

const shapes = reg.factShapes.map((s) => ({ ...s, re: new RegExp(s.pattern, s.flags ?? '') }));

// Check 1 — scan each scoped file for a fact-shaped `label:` literal.
const LABEL_LITERAL = /\blabel:\s*(['"`])((?:\\.|(?!\1).)*)\1/g;
for (const rel of reg.scope) {
  const src = readFileSync(join(REPO_ROOT, rel), 'utf8');
  let m;
  while ((m = LABEL_LITERAL.exec(src)) !== null) {
    const value = m[2];
    for (const s of shapes) {
      if (s.re.test(value)) {
        failures.push(
          `${rel}: chip label "${value}" asserts a runtime/build fact (${s.id}: ${s.why}). ` +
            `Use \`fact: '<id>'\` (projectFact) instead of a baked label — a fact-shaped literal is ` +
            `the drift this gate forecloses (594 Move 2).`,
        );
      }
    }
  }
}

// Check 2 — every declared factId resolves in the catalog (no silent gate/projector divergence).
const catalogSrc = readFileSync(join(REPO_ROOT, reg.factCatalog), 'utf8');
for (const id of reg.factIds) {
  if (!catalogSrc.includes(`'${id}'`)) {
    failures.push(
      `${reg.factCatalog}: declared factId "${id}" is not defined in the fact catalog — the gate's ` +
        `coverage and the projector have diverged (594 §4 Move 2 / 548 §5.2 catalog-projection).`,
    );
  }
}

if (failures.length) {
  console.error('chip-fact-authority FAIL:');
  for (const f of failures) console.error('  ' + f);
  process.exit(1);
}
console.log(
  'chip-fact-authority OK — no fact-shaped chip-label literals; every declared fact resolves in the catalog.',
);
