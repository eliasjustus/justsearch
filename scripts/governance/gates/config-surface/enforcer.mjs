/**
 * config-surface enforcer — tempdoc 799 K.4.
 *
 * Ratchets the runtime **configuration surface** so a cleanup stays cleaned up.
 * Tempdoc 754 classified 70 knobs and deleted 31, then recorded its own gap:
 * "no regrowth gate". Without one the surface returns to its prior size and the
 * next campaign pays the same cost — so this gate is the durable half of that work.
 *
 * WHAT IT COUNTS (declared deliberately — see tempdoc 799 §D.1): the metrics
 * emitted by `scripts/docs/generate-runtime-config-matrix.mjs`, which reads the
 * three configuration authorities (EnvRegistry, ConfigKey, ResolvedConfigBuilder)
 * and reports `yamlKeyCount`, `envSyspropPairCount` and `configKeyCount`.
 *
 * HONEST LIMIT: configuration reaches the Worker by three parallel paths (the
 * worker-config snapshot, blanket JUSTSEARCH_* env forwarding, and the explicit
 * WorkerSpawner forwarded-props list), and the post-handshake divergence check
 * only WARNs. A count over the declared authorities can therefore read complete
 * while an undeclared path grows. This gate ratchets what is *declared*; it does
 * not claim to see every effective knob.
 *
 * Baseline format (TSV, per line): `<metric> <count> <date>` — merge-friendly,
 * mirroring gates/module-deps/baseline.txt.
 */

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import {
  CONFIG_SURFACE_CLASSIFICATIONS,
  aggregateConfigSurfaceClassifications,
} from './classifications.mjs';
import { CONFIG_SURFACE_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import { scanDeadConfig } from './dead-config.mjs';
import {
  verdictForMetric,
  verdictForBaselineShift,
  verdictForDeadKey,
  verdictForUnreadComponent,
} from './truth-table.mjs';
import { loadChangesets } from '../../lib/changeset-loader.mjs';
import { readFileAtRef } from '../../lib/git-utils.mjs';

const TOOL = { toolName: 'justsearch-config-surface', toolVersion: '0.2.0' };

const SPLIT_LINES = new RegExp("\\r?\\n");

function deadBaselineUri(gate) {
  return gate.config?.deadConfigBaseline ?? 'gates/config-surface/dead-config-baseline.txt';
}

/** Report field → baseline metric name. */
const METRICS = {
  yaml_keys: 'yamlKeyCount',
  env_sysprop_pairs: 'envSyspropPairCount',
  config_keys: 'configKeyCount',
};

function parseBaseline(content) {
  const map = new Map();
  for (const raw of (content ?? '').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const parts = line.split(/\s+/);
    if (parts.length < 2) continue;
    const count = Number(parts[1]);
    if (Number.isFinite(count)) map.set(parts[0], count);
  }
  return map;
}

export async function enforceConfigSurface(options) {
  const { repoRoot, gate, baselineRef, rebalance = false, fixtureMode = false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const baselinePath = resolve(sourceRoot, gate.baseline.path);
  const reportRel =
    gate.config?.reportPath ??
    'tmp/agent-evidence/_summaries/runtime-config-ownership-matrix.generated.json';
  const reportPath = resolve(sourceRoot, reportRel);

  const baseline = existsSync(baselinePath)
    ? parseBaseline(readFileSync(baselinePath, 'utf8'))
    : new Map();

  const findings = [];
  let verdict = 'pass';

  let report;
  try {
    report = JSON.parse(readFileSync(reportPath, 'utf8'));
  } catch {
    return {
      ...TOOL,
      findings: [
        {
          ruleId: 'config-surface/report-malformed',
          level: 'error',
          message: `malformed or unreadable runtime-config matrix report at ${reportRel}`,
          uri: reportRel,
        },
      ],
      verdict: 'fail',
      ruleDescriptions: CONFIG_SURFACE_RULE_DESCRIPTIONS,
    };
  }

  const declarations = gate.changesetsDir
    ? loadChangesets({
        repoRoot: sourceRoot,
        changesetsDir: gate.changesetsDir,
        baselineRef,
        allowedClassifications: CONFIG_SURFACE_CLASSIFICATIONS,
        classificationField: 'classification',
        requireJustificationFor: new Set(['declared-growth', 'merge-import', 'emergency-override']),
        fixtureMode,
      })
    : [];
  const aggregated = aggregateConfigSurfaceClassifications(declarations);
  const coveringClassification = !aggregated.growthCovered
    ? 'silent-growth'
    : aggregated.classifications.find((c) =>
        ['declared-growth', 'merge-import', 'emergency-override'].includes(c),
      ) ?? 'silent-growth';

  const rebalanceWrites = new Map();

  for (const [metric, field] of Object.entries(METRICS)) {
    const current = Number(report[field]);
    if (!Number.isFinite(current)) continue;
    const pinned = baseline.get(metric) ?? current;
    const v = verdictForMetric({
      metric,
      current,
      pinned,
      classification: coveringClassification,
    });
    if (v.status === 'fail') {
      verdict = 'fail';
      findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: gate.baseline.path });
    } else if (v.status === 'info') {
      findings.push({ ruleId: v.ruleId, level: 'note', message: v.reason, uri: gate.baseline.path });
      if (rebalance && current < pinned) rebalanceWrites.set(metric, current);
    }
  }

  // --- Reader-presence (tempdoc 799 §O.3). The count ratchet above answers "how many settings
  // exist"; this answers "does anything read them", which is the defect 754 catalogued and which
  // 799 §N.2.f.1 proved the count ratchet cannot see. Skipped in fixtureMode: the fixtures are
  // synthetic count-ratchet trees with no Java sources, and dead-config.mjs has its own unit test.
  if (!fixtureMode) {
    const deadBaselinePath = resolve(
      sourceRoot,
      gate.config?.deadConfigBaseline ?? 'gates/config-surface/dead-config-baseline.txt',
    );
    const pinned = new Set();
    if (existsSync(deadBaselinePath)) {
      for (const raw of readFileSync(deadBaselinePath, 'utf8').split(SPLIT_LINES)) {
        const line = raw.trim();
        if (!line || line.startsWith('#')) continue;
        pinned.add(line);
      }
    }
    const scan = scanDeadConfig(sourceRoot);
    if (!scan.skipped) {
      for (const key of scan.deadKeys) {
        const v = verdictForDeadKey({ key, baselined: pinned.has('key:' + key) });
        if (v.status === 'fail') {
          verdict = 'fail';
          findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: deadBaselineUri(gate) });
        } else {
          findings.push({ ruleId: v.ruleId, level: 'note', message: v.reason, uri: deadBaselineUri(gate) });
        }
      }
      for (const component of scan.unreadComponents) {
        const v = verdictForUnreadComponent({
          component,
          baselined: pinned.has('component:' + component),
        });
        if (v.status === 'fail') {
          verdict = 'fail';
          findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: deadBaselineUri(gate) });
        } else {
          findings.push({ ruleId: v.ruleId, level: 'note', message: v.reason, uri: deadBaselineUri(gate) });
        }
      }
    }
  }

  // Baseline-shift detection — catches relaxing the pin instead of the surface.
  let priorBaseline = null;
  if (fixtureMode && fixtureRoot) {
    const p = resolve(fixtureRoot, '_baseline', gate.baseline.path);
    if (existsSync(p)) priorBaseline = parseBaseline(readFileSync(p, 'utf8'));
  } else if (baselineRef) {
    const content = readFileAtRef(baselineRef, gate.baseline.path, sourceRoot);
    if (content !== null) priorBaseline = parseBaseline(content);
  }
  if (priorBaseline) {
    for (const [metric, livePin] of baseline.entries()) {
      const priorPin = priorBaseline.get(metric);
      if (priorPin === undefined) continue;
      const v = verdictForBaselineShift({
        metric,
        priorPin,
        livePin,
        classification: coveringClassification,
      });
      if (v.status === 'fail') {
        verdict = 'fail';
        findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: gate.baseline.path });
      }
    }
  }

  if (rebalance && rebalanceWrites.size > 0) {
    const date = new Date().toISOString().slice(0, 10);
    const lines = [
      '# config-surface ratchet — tempdoc 799 K.4. <metric> <count> <date>',
      '# Produced from scripts/docs/generate-runtime-config-matrix.mjs. Only ratchets DOWN.',
    ];
    for (const metric of Object.keys(METRICS)) {
      const pinned = rebalanceWrites.has(metric) ? rebalanceWrites.get(metric) : baseline.get(metric);
      if (Number.isFinite(pinned)) lines.push(`${metric} ${pinned} ${date}`);
    }
    writeFileSync(baselinePath, lines.join('\n') + '\n');
  }

  return {
    ...TOOL,
    findings,
    verdict,
    ruleDescriptions: CONFIG_SURFACE_RULE_DESCRIPTIONS,
    rebalanceWrites: [...rebalanceWrites.entries()].map(([metric, c]) => ({
      file: gate.baseline.path,
      before: String(baseline.get(metric) ?? ''),
      after: String(c),
    })),
  };
}
