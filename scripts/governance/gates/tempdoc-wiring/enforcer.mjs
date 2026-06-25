/**
 * tempdoc-wiring enforcer — tempdoc 530 §4.1.
 *
 * Reads open tempdocs' frontmatter for an optional `governance:` block.
 * For each entry, validates: (a) the gate id exists in the registry; (b)
 * declared classifications are in that gate's allowed set.
 *
 * Pure opt-in. Tempdocs without a governance: block no-op (no warning).
 * Once a critical mass of tempdocs adopts the field, the gate can flip to
 * "warn on missing for tempdocs that touch ratcheted files."
 */

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseFrontmatter } from '../../lib/frontmatter.mjs';

export const TEMPDOC_WIRING_CLASSIFICATIONS = new Set(['governance-block-added', 'governance-block-removed', 'emergency-override']);
export const TEMPDOC_WIRING_RULE_DESCRIPTIONS = {
  'tempdoc-wiring/within-spec': 'Tempdoc governance: block is well-formed',
  'tempdoc-wiring/unknown-gate':
    'Tempdoc governance: references a gate id not in governance/registry.v1.json',
  'tempdoc-wiring/unknown-classification':
    'Tempdoc governance: declares a classification not in the gate\'s allowed set',
  'tempdoc-wiring/no-governance-block':
    'Tempdoc has no governance: frontmatter (opt-in; informational only)',
};

function parseGovernanceBlock(rawText) {
  // Simple YAML-ish parser for the governance: block. Format:
  //   governance:
  //     expects-changesets:
  //       - gate: <id>
  //         classifications: [a, b]
  // The substrate's parseFrontmatter only does flat key:value pairs;
  // this parser handles the nested expects-changesets list specifically.
  const lines = rawText.split(/\r?\n/);
  const out = { expectsChangesets: [] };
  let inGovernance = false;
  let inList = false;
  let cur = null;
  for (const raw of lines) {
    if (/^governance:\s*$/.test(raw)) { inGovernance = true; continue; }
    if (!inGovernance) continue;
    if (raw.length > 0 && !/^\s/.test(raw)) break; // un-indented = end of governance: block
    const trimmed = raw.trim();
    if (/^expects-changesets:\s*$/.test(trimmed)) { inList = true; continue; }
    if (!inList) continue;
    if (/^-\s+gate:\s*(.+)$/.test(trimmed)) {
      if (cur) out.expectsChangesets.push(cur);
      cur = { gate: RegExp.$1.trim(), classifications: [] };
    } else if (cur && /^classifications:\s*\[([^\]]*)\]/.test(trimmed)) {
      cur.classifications = RegExp.$1.split(',').map(s => s.trim()).filter(Boolean);
    }
  }
  if (cur) out.expectsChangesets.push(cur);
  return out;
}

async function loadGateClassifications(gate, repoRoot) {
  if (!gate.classifications) return null;
  const path = resolve(repoRoot, gate.classifications);
  if (!existsSync(path)) return null;
  try {
    const mod = await import(pathToFileURL(path).href);
    for (const value of Object.values(mod)) {
      if (value instanceof Set) return value;
    }
  } catch { /* ignore */ }
  return null;
}

export async function enforceTempdocWiring(options) {
  const { repoRoot, gate, fixtureMode=false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const tempdocsDir = resolve(sourceRoot, gate.config?.tempdocsDir ?? 'docs/tempdocs');
  const registryPath = resolve(sourceRoot, gate.config?.registryPath ?? 'governance/registry.v1.json');

  const findings = [];
  let verdict = 'pass';

  if (!existsSync(tempdocsDir) || !existsSync(registryPath)) {
    return { toolName: 'justsearch-tempdoc-wiring', toolVersion: '0.1.0', findings, verdict, ruleDescriptions: TEMPDOC_WIRING_RULE_DESCRIPTIONS };
  }

  const registry = JSON.parse(readFileSync(registryPath, 'utf8'));
  const gatesById = new Map((registry.gates ?? []).map(g => [g.id, g]));

  // Lazy classifications cache (gate.id → Set).
  const classCache = new Map();
  const getAllowed = async gateId => {
    if (classCache.has(gateId)) return classCache.get(gateId);
    const g = gatesById.get(gateId);
    const allowed = g ? await loadGateClassifications(g, repoRoot) : null;
    classCache.set(gateId, allowed);
    return allowed;
  };

  const entries = readdirSync(tempdocsDir);
  for (const name of entries) {
    if (!name.endsWith('.md')) continue;
    const full = join(tempdocsDir, name);
    const content = readFileSync(full, 'utf8');
    const fm = parseFrontmatter(content);
    if (!fm) continue;
    // Only consider tempdocs with status: open*
    if (!/^open\b/i.test(fm.frontmatter.status ?? '')) continue;

    // governance: blocks are nested YAML; parseFrontmatter only does flat.
    // Re-parse the raw frontmatter source for the governance block.
    const fmRaw = content.split(/\r?\n/).slice(1).join('\n');
    const fmEnd = fmRaw.indexOf('\n---');
    if (fmEnd === -1) continue;
    const fmBody = fmRaw.slice(0, fmEnd);
    const gov = parseGovernanceBlock(fmBody);
    if (gov.expectsChangesets.length === 0) continue;

    for (const ec of gov.expectsChangesets) {
      const g = gatesById.get(ec.gate);
      if (!g) {
        verdict = 'fail';
        findings.push({ ruleId: 'tempdoc-wiring/unknown-gate', level: 'error', message: `tempdoc ${name}: governance.expects-changesets references gate '${ec.gate}' not in registry`, uri: `docs/tempdocs/${name}` });
        continue;
      }
      const allowed = await getAllowed(ec.gate);
      if (!allowed) continue;
      for (const c of ec.classifications) {
        if (!allowed.has(c)) {
          verdict = 'fail';
          findings.push({ ruleId: 'tempdoc-wiring/unknown-classification', level: 'error', message: `tempdoc ${name}: gate '${ec.gate}' has no classification '${c}'`, uri: `docs/tempdocs/${name}` });
        }
      }
    }
  }

  return { toolName: 'justsearch-tempdoc-wiring', toolVersion: '0.1.0', findings, verdict, ruleDescriptions: TEMPDOC_WIRING_RULE_DESCRIPTIONS };
}
