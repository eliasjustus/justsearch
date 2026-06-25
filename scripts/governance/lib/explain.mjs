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

import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

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
