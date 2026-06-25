/**
 * SARIF v2.1.0 (minimal subset) emitter — discipline-gate kernel (tempdoc 530).
 *
 * Originally factored out of `scripts/contract-governance/lib/sarif-emitter.mjs`
 * (slice 3a-1-8f §A.13). The Pass-2 confidence audit found the contract-
 * governance copy had hardcoded protobuf rule descriptions; this version
 * accepts an external `ruleDescriptions` map so each gate class can supply
 * its own without forking the emitter.
 *
 * Subset emitted:
 *   $schema, version, runs[]
 *   runs[].tool.driver.{name, version, informationUri, rules[]}
 *   runs[].results[].{ruleId, level, message.text, locations[]}
 */

const SARIF_SCHEMA =
  'https://raw.githubusercontent.com/oasis-tcs/sarif-spec/main/Schemata/sarif-schema-2.1.0.json';
const RUNNER_VERSION = '0.1.0';
const RUNNER_INFO_URI = 'https://github.com/eliasjustus/justsearch';

/**
 * @typedef {Object} Finding
 * @property {string} ruleId
 * @property {"error"|"warning"|"note"} level
 * @property {string} message
 * @property {string} [uri]
 * @property {number} [startLine]
 *
 * @typedef {Object} ToolRun
 * @property {string} toolName
 * @property {string} [toolVersion]
 * @property {string} axis
 * @property {string|null} categoryId
 * @property {Finding[]} findings
 *
 * @typedef {Object} EmitOptions
 * @property {Record<string, string>} [ruleDescriptions]
 *   Map from `ruleId` to a short human description. Used only for SARIF's
 *   `rules[]` declarative metadata; absent ids fall back to the ruleId
 *   itself.
 */

/**
 * @param {ToolRun[]} runs
 * @param {EmitOptions} [options]
 * @returns {object}  SARIF v2.1.0 document
 */
export function emitSarif(runs, options = {}) {
  const ruleDescriptions = options.ruleDescriptions ?? {};
  return {
    $schema: SARIF_SCHEMA,
    version: '2.1.0',
    runs: runs.map(toolRun => ({
      tool: {
        driver: {
          name: toolRun.toolName,
          version: toolRun.toolVersion ?? RUNNER_VERSION,
          informationUri: RUNNER_INFO_URI,
          rules: deriveRules(toolRun.findings, ruleDescriptions),
        },
      },
      properties: {
        axis: toolRun.axis,
        categoryId: toolRun.categoryId,
      },
      results: toolRun.findings.map(toResult),
    })),
  };
}

function toResult(finding) {
  /** @type {object} */
  const result = {
    ruleId: finding.ruleId,
    level: finding.level,
    message: { text: finding.message },
  };
  if (finding.uri) {
    result.locations = [
      {
        physicalLocation: {
          artifactLocation: { uri: finding.uri.replaceAll('\\', '/') },
          ...(finding.startLine ? { region: { startLine: finding.startLine } } : {}),
        },
      },
    ];
  }
  return result;
}

function deriveRules(findings, ruleDescriptions) {
  const seen = new Map();
  for (const f of findings) {
    if (seen.has(f.ruleId)) continue;
    seen.set(f.ruleId, {
      id: f.ruleId,
      name: ruleIdToName(f.ruleId),
      shortDescription: { text: ruleDescriptions[f.ruleId] ?? f.ruleId },
      defaultConfiguration: { level: f.level },
    });
  }
  return [...seen.values()];
}

function ruleIdToName(ruleId) {
  // Convert "namespace/kebab-case-id" to "PascalCaseId"
  const tail = ruleId.split('/').slice(-1)[0];
  return tail
    .split('-')
    .map(s => s.charAt(0).toUpperCase() + s.slice(1))
    .join('');
}
