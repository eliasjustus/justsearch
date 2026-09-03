import { existsSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';

export const CONFIG_LIFECYCLE_RULE_DESCRIPTIONS = {
  'config-surface/lifecycle-input-malformed':
    'The generated config matrix or lifecycle overlay is missing its required top-level shape',
  'config-surface/lifecycle-stage-invalid':
    'A canonical configuration declaration has no valid permanent, experimental, or deprecated stage',
  'config-surface/lifecycle-declaration-duplicate':
    'A canonical configuration declaration appears more than once in the generated matrix',
  'config-surface/lifecycle-metadata-missing':
    'An experimental or deprecated configuration declaration has no rich lifecycle metadata',
  'config-surface/lifecycle-metadata-duplicate':
    'A configuration declaration has more than one lifecycle metadata row',
  'config-surface/lifecycle-metadata-orphan':
    'A lifecycle metadata row does not join to an experimental or deprecated declaration',
  'config-surface/lifecycle-metadata-incomplete':
    'A non-permanent lifecycle row is missing owner, rationale, dates, exit criteria, or evidence',
  'config-surface/lifecycle-date-incoherent':
    'A lifecycle row has invalid or chronologically incoherent ISO dates',
  'config-surface/lifecycle-review-overdue':
    'A non-permanent configuration declaration passed its review-by date without a new decision',
  'config-surface/lifecycle-evidence-missing':
    'A lifecycle evidence link is not a resolvable repository-relative file',
};

const STAGES = new Set(['permanent', 'experimental', 'deprecated']);
const NON_PERMANENT = new Set(['experimental', 'deprecated']);
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

function isNonBlank(value) {
  return typeof value === 'string' && value.trim().length > 0;
}

function isIsoDate(value) {
  if (!isNonBlank(value) || !ISO_DATE.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(parsed.valueOf()) && parsed.toISOString().slice(0, 10) === value;
}

function evidencePath(repoRoot, link) {
  if (!isNonBlank(link)) return null;
  const withoutAnchor = link.split('#', 1)[0];
  if (!withoutAnchor || isAbsolute(withoutAnchor) || withoutAnchor.includes('..')) return null;
  return resolve(repoRoot, withoutAnchor);
}

/**
 * Exact join between source-projected config declarations and rich metadata for non-permanent rows.
 * The injected date keeps overdue behavior deterministic in unit tests and offline gate runs.
 */
export function validateConfigLifecycle({ matrix, overlay, repoRoot, today }) {
  const findings = [];
  const now = today ?? new Date().toISOString().slice(0, 10);
  const add = (ruleId, message, uri = 'governance/config-lifecycle.v1.json') => {
    findings.push({ ruleId, level: 'error', message, uri });
  };

  if (!matrix || !Array.isArray(matrix.rows) || !overlay || overlay.schemaVersion !== 1
      || !Array.isArray(overlay.entries)) {
    add(
      'config-surface/lifecycle-input-malformed',
      'Expected matrix.rows, config-lifecycle schemaVersion 1, and config-lifecycle entries.',
    );
    return findings;
  }
  if (!isIsoDate(now)) {
    throw new Error(`today must be an ISO date, received ${now}`);
  }

  const declarations = new Map();
  for (const row of matrix.rows) {
    const declaration = row?.declaration;
    const stage = row?.lifecycleStage;
    if (!isNonBlank(declaration) || !STAGES.has(stage)) {
      add(
        'config-surface/lifecycle-stage-invalid',
        `Invalid lifecycle projection for ${declaration || '<missing declaration>'}: ${stage ?? '<missing>'}.`,
        matrix.configKeyPath ?? matrix.envRegistryPath ?? 'runtime-config-matrix',
      );
      continue;
    }
    if (declarations.has(declaration)) {
      add(
        'config-surface/lifecycle-declaration-duplicate',
        `${declaration} appears more than once in the generated runtime-config matrix.`,
        matrix.configKeyPath ?? matrix.envRegistryPath ?? 'runtime-config-matrix',
      );
      continue;
    }
    declarations.set(declaration, stage);
  }

  const metadata = new Map();
  for (const entry of overlay.entries) {
    const declaration = entry?.declaration;
    if (!isNonBlank(declaration)) {
      add('config-surface/lifecycle-metadata-incomplete', 'Lifecycle row has no declaration.');
      continue;
    }
    if (metadata.has(declaration)) {
      add(
        'config-surface/lifecycle-metadata-duplicate',
        `${declaration} has more than one lifecycle metadata row.`,
      );
      continue;
    }
    metadata.set(declaration, entry);

    if (!NON_PERMANENT.has(declarations.get(declaration))) {
      add(
        'config-surface/lifecycle-metadata-orphan',
        `${declaration} does not join to an experimental or deprecated source declaration.`,
      );
    }

    const complete = isNonBlank(entry.owner)
      && isNonBlank(entry.rationale)
      && isNonBlank(entry.evidenceLink)
      && isNonBlank(entry.exitCriteria?.promoteWhen)
      && isNonBlank(entry.exitCriteria?.removeWhen);
    if (!complete) {
      add(
        'config-surface/lifecycle-metadata-incomplete',
        `${declaration} requires owner, rationale, promotion/removal criteria, and evidenceLink.`,
      );
    }

    const datesValid = isIsoDate(entry.introducedOn)
      && isIsoDate(entry.lastReviewedOn)
      && isIsoDate(entry.reviewBy)
      && entry.introducedOn <= entry.lastReviewedOn
      && entry.lastReviewedOn <= entry.reviewBy
      && entry.introducedOn <= now
      && entry.lastReviewedOn <= now;
    if (!datesValid) {
      add(
        'config-surface/lifecycle-date-incoherent',
        `${declaration} requires introducedOn <= lastReviewedOn <= reviewBy as real ISO dates.`,
      );
    } else if (entry.reviewBy < now) {
      add(
        'config-surface/lifecycle-review-overdue',
        `${declaration} review was due ${entry.reviewBy}; record a new decision before renewing it.`,
      );
    }

    const evidence = evidencePath(repoRoot, entry.evidenceLink);
    if (!evidence || !existsSync(evidence)) {
      add(
        'config-surface/lifecycle-evidence-missing',
        `${declaration} evidenceLink does not resolve to a repository file: ${entry.evidenceLink ?? '<missing>'}.`,
      );
    }
  }

  for (const [declaration, stage] of declarations) {
    if (NON_PERMANENT.has(stage) && !metadata.has(declaration)) {
      add(
        'config-surface/lifecycle-metadata-missing',
        `${declaration} is ${stage} but has no lifecycle metadata row.`,
      );
    }
  }

  return findings;
}
