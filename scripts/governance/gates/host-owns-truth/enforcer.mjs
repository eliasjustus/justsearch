// host-owns-truth gate (tempdoc 560 §4.5 / kernel decision 05) — the static backstop over the
// construction-time enforcement in ContributionRegistry.install. Reads the registry snapshot and
// fails any declaration whose namespace and provenance disagree: a `core.*` id is host truth and
// MUST be CORE provenance; a `vendor.*` id is a contribution and must NOT be CORE. This makes
// "a plugin forks/shadows a core primitive" detectable in the serialized substrate, not just at
// runtime install.

import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { HOST_OWNS_TRUTH_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import { verdictForEntry } from './truth-table.mjs';

const TOOL_NAME = 'justsearch-host-owns-truth';
const TOOL_VERSION = '0.1.0';
const DEFAULT_SNAPSHOT = 'tmp/consumer-presence/registry-snapshot.json';

export async function enforceHostOwnsTruth(options) {
  const { repoRoot, gate, fixtureMode = false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const snapshotRel = gate.config?.snapshotPath ?? DEFAULT_SNAPSHOT;
  const snapshotPath = resolve(sourceRoot, snapshotRel);

  const findings = [];
  const result = (verdict) => ({
    toolName: TOOL_NAME,
    toolVersion: TOOL_VERSION,
    findings,
    verdict,
    ruleDescriptions: HOST_OWNS_TRUTH_RULE_DESCRIPTIONS,
  });

  if (!existsSync(snapshotPath)) {
    findings.push({
      ruleId: 'host-owns-truth/snapshot-missing',
      level: 'warning',
      message: `registry snapshot not found at ${snapshotRel}. Run :modules:app-services:test (RegistrySnapshotExporterTest) to generate it.`,
      uri: snapshotRel,
    });
    return result('pass');
  }

  let snapshot;
  try {
    snapshot = JSON.parse(readFileSync(snapshotPath, 'utf8'));
  } catch (e) {
    findings.push({
      ruleId: 'host-owns-truth/snapshot-missing',
      level: 'warning',
      message: `registry snapshot at ${snapshotRel} could not be parsed: ${e.message}`,
      uri: snapshotRel,
    });
    return result('pass');
  }

  const entries = Array.isArray(snapshot.entries) ? snapshot.entries : [];
  let anyFail = false;
  for (const entry of entries) {
    if (verdictForEntry(entry) !== 'fail') continue;
    anyFail = true;
    const isCoreNs = (entry.id || '').startsWith('core.');
    findings.push({
      ruleId: isCoreNs ? 'host-owns-truth/core-namespace-forked' : 'host-owns-truth/vendor-namespace-mislabeled',
      level: 'error',
      message:
        `${entry.kind} '${entry.id}' has provenance ${entry.provenance} — ` +
        (isCoreNs
          ? 'a non-CORE declaration may not mint a core.* primitive (host owns truth, §4.5).'
          : 'a CORE declaration must live in core.*, not the vendor contribution namespace.'),
      uri: snapshotRel,
    });
  }

  return result(anyFail ? 'fail' : 'pass');
}
