/**
 * --explain <ruleId> — Layer 3 §3.2 of tempdoc 530.
 *
 * Looks up a SARIF ruleId across every gate's `rule-descriptions.mjs`,
 * prints the human description, the gate that owns the rule, and a
 * template changeset that would cover the failure (when applicable).
 *
 * Eliminates the context-window cost of "I forgot how this gate's
 * protocol works."
 */

import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

import { repinRuleDescription } from './declared-growth-repin.mjs';

/**
 * Whether a gate actually wires the repin rule — the same predicate `repin-coverage.test.mjs`
 * asserts on, so `--explain` never invents a rule for a gate that is exempt from it.
 */
function gateWiresRepinRule(gate, repoRoot) {
  try {
    const enforcerPath = resolve(repoRoot, gate.enforcer);
    const text = readFileSync(enforcerPath, 'utf8');
    if (text.includes('declared-growth-repin')) return true;
    // Gates built on the shared per-file ratchet factory inherit the rule from it.
    if (text.includes('ratchet-gate.mjs')) {
      return readFileSync(resolve(dirname(enforcerPath), '../../lib/ratchet-gate.mjs'), 'utf8')
        .includes('declared-growth-repin');
    }
  } catch { /* unreadable enforcer — not our question to answer here */ }
  return false;
}

export async function explainRule({ ruleId, gates, repoRoot }) {
  // The ruleId is namespaced by gate-id (e.g., `class-size/silent-growth`).
  const namespace = ruleId.includes('/') ? ruleId.split('/')[0] : null;
  let owningGate = null;
  let description = null;

  for (const gate of gates) {
    if (!gate.ruleDescriptions) continue;
    const path = resolve(repoRoot, gate.ruleDescriptions);
    if (!existsSync(path)) continue;
    const mod = await import(pathToFileURL(path).href);
    // Look for any export that's a plain object containing the ruleId.
    for (const value of Object.values(mod)) {
      if (value && typeof value === 'object' && ruleId in value) {
        owningGate = gate;
        description = value[ruleId];
        break;
      }
    }
    if (description) break;
  }

  // The repin rule (tempdoc 918) is described by the SHARED module, and six of the eleven gates
  // that carry it merge that description into their enforcer's RETURN VALUE rather than into the
  // registry-declared `ruleDescriptions` module the loop above reads. `--explain` therefore
  // answered "No description registered" for exactly those six. Resolving it from the rule module
  // keeps one authority and covers any gate that wires the rule later.
  if (!description && namespace) {
    const gate = gates.find(g => g.id === namespace);
    if (gate && gateWiresRepinRule(gate, repoRoot)) {
      const suffix = ruleId.slice(namespace.length + 1);
      description = repinRuleDescription(namespace, suffix)[ruleId] ?? null;
      if (description) owningGate = gate;
    }
  }

  if (!description) {
    console.log(`No description registered for ruleId '${ruleId}'.`);
    console.log(`Tried ${gates.length} gate${gates.length === 1 ? '' : 's'}.`);
    if (namespace) {
      console.log(`Tip: the namespace prefix '${namespace}' suggests gate '${namespace}'; ` +
        `check its rule-descriptions.mjs.`);
    }
    return;
  }

  console.log(`Rule: ${ruleId}`);
  console.log(`Gate: ${owningGate.id} (${owningGate.title ?? 'untitled'})`);
  console.log();
  console.log(`Description:`);
  console.log(`  ${description}`);
  console.log();

  // The repin rule (tempdoc 918) is the one fail-shaped rule whose remedy is NOT a changeset — the
  // author already wrote one. Checked FIRST: `declared-regression-without-repin` matches the
  // 'regression' substring below and would otherwise be answered with the changeset template it
  // is telling the author is insufficient.
  if (ruleId.endsWith('-without-repin')) {
    console.log('Remedy: advance the baseline pin for the named row, in the SAME commit as the');
    console.log('changeset, to at least the measured value. Do NOT write a second changeset — you');
    console.log('already have one, and it is what licenses the pin edit. The finding names the pin');
    console.log('file, the row and the value to write.');
    console.log();
    console.log(`See docs/reference/contributing/discipline-gate-kernel.md (Changeset escape-hatch`);
    console.log(`protocol) and gates/${owningGate.id}/.changesets/README.md.`);
    return;
  }

  // For fail-ruleIds, print a template changeset.
  if (ruleId.includes('silent-') || ruleId.includes('exceeded') || ruleId.includes('untagged') || ruleId.includes('unresolved') || ruleId.includes('regression')) {
    console.log(`Template changeset (write to gates/${owningGate.id}/.changesets/<name>.md):`);
    console.log();
    console.log(`---`);
    console.log(`classification: declared-growth  # or declared-regression / merge-import / emergency-override`);
    console.log(`tempdoc: NNN                      # or adr: NNNN`);
    console.log(`---`);
    console.log(`Reason this change is acceptable. Cite the tempdoc / ADR. Explain the trade-off.`);
    console.log();
    console.log(`See gates/${owningGate.id}/.changesets/README.md for the full classification grammar.`);
  } else {
    console.log(`This rule is informational/pass-shaped; no changeset needed.`);
  }
}
