#!/usr/bin/env node

/**
 * PostToolUse hook for Edit/Write on discipline-gate baseline files +
 * tier-register + .changesets/ directories.
 *
 * When an agent edits any artifact load-bearing on the discipline-gate
 * kernel (tempdoc 530), this hook surfaces a reminder of the changeset
 * protocol and the gate's classification grammar. Mirrors lockfile-hint.mjs.
 *
 * Synchronous (<50ms). No subprocess. Path-based dispatch only.
 */

import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

const REPO_ROOT = resolve(new URL('.', import.meta.url).pathname, '..', '..', '..');

function normalize(p) {
  return p.replace(/\\/g, '/');
}

function loadRegistryBaselines() {
  // Returns a map: rel-path → gate-id.
  const out = new Map();
  const registryPath = resolve(REPO_ROOT, 'governance/registry.v1.json');
  if (!existsSync(registryPath)) return out;
  try {
    const reg = JSON.parse(readFileSync(registryPath, 'utf8'));
    for (const gate of reg.gates ?? []) {
      const baselinePath = gate.baseline?.path;
      if (baselinePath) out.set(normalize(baselinePath), gate.id);
    }
  } catch {
    /* registry malformed; hook silently no-ops */
  }
  return out;
}

function detectGate(filePath, baselines) {
  const p = normalize(filePath);
  // Exact baseline-file match.
  const direct = baselines.get(p);
  if (direct) return { gate: direct, kind: 'baseline' };
  // Path under a gate's .changesets/.
  for (const [, gateId] of baselines) {
    if (p.includes(`gates/${gateId}/.changesets/`)) {
      return { gate: gateId, kind: 'changeset' };
    }
  }
  // Tier-register edits.
  if (p.endsWith('.claude/rules/tier-register.md')) {
    return { gate: 'prose-tier-register', kind: 'register' };
  }
  return null;
}

function buildHint(gate, kind) {
  const lines = [];
  if (kind === 'baseline') {
    lines.push(
      `${gate} baseline edited — if you raised a pin or relaxed a threshold,`,
      `the discipline-gate kernel requires a classified changeset:`,
      `  gates/${gate}/.changesets/<id>.md with classification: + tempdoc:/adr: ref`,
      `Run \`node scripts/governance/run.mjs --gate ${gate} --mode gate\` to verify.`,
      `Load /governance for the full protocol.`,
    );
  } else if (kind === 'changeset') {
    lines.push(
      `${gate} changeset edited — verify classification: matches an allowed`,
      `value (see gates/${gate}/.changesets/README.md), and tempdoc:/adr:`,
      `field is present for non-shrink classifications.`,
    );
  } else if (kind === 'register') {
    lines.push(
      `tier-register edited — if a row's tier changed, the prose-tier-register`,
      `gate requires a changeset under gates/prose-tier-register/.changesets/`,
      `classified as tier-change / new-rule-registered / rule-retired /`,
      `emergency-override (with tempdoc:/adr: ref).`,
    );
  }
  return lines.join('\n');
}

async function main() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  const raw = Buffer.concat(chunks).toString('utf8');

  try {
    const input = JSON.parse(raw);
    const toolName = input.tool_name;
    const toolInput = input.tool_input;
    if (toolName !== 'Edit' && toolName !== 'Write') return;

    const filePath = toolInput?.file_path;
    if (!filePath) return;

    const baselines = loadRegistryBaselines();
    if (baselines.size === 0) return;

    const match = detectGate(filePath, baselines);
    if (!match) return;

    const hint = buildHint(match.gate, match.kind);

    process.stdout.write(
      JSON.stringify({
        hookSpecificOutput: {
          hookEventName: 'PostToolUse',
          additionalContext: hint,
        },
      }),
    );
  } catch {
    /* parse failure → silent */
  }
}

main().catch(() => process.exit(0));
