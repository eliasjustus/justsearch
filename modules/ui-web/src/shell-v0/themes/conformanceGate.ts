// SPDX-License-Identifier: Apache-2.0
/**
 * 569 Move 6 — the install/apply-time conformance gate.
 *
 * The FLOOR (rung 4) beneath the unrepresentability moves: certify a Presentation
 * Declaration before it is applied or shared. Most breaking forms are already
 * unrepresentable (Move 2: closed vocab, reserved components, no cssText) and the
 * accessible/contrast-safe element is co-projected (Move 3); this gate adds the checks the
 * type system can't make statically — and, on failure, QUARANTINES the offending surface to
 * the default render rather than failing the whole app (degrade-never-fail). The full
 * runtime a11y/axe pass over the applied DOM is the final live batch; here we certify what is
 * statically checkable on the declaration.
 *
 * 569 §19 Seam 2 — the verdict carries STRUCTURED {@link ConformanceError} values (a discriminant
 * union), not human strings. {@link describeConformanceError} renders the prose. Structured errors
 * are what make the downstream features possible: an editor that anchors each error to its JSON node,
 * and a generative self-repair loop that feeds the failure reasons back to the model.
 */
import {
  validatePresentationDeclaration,
  type PresentationDeclaration,
} from './presentationDeclaration.js';
import { parseColor, contrastRatio, WCAG_AA } from './contrast.js';
import { missingRequiredRegions, hiddenRequiredRegions } from './requiredRegions.js';
// 569 Fix C — coverage PROJECTS from the role/layout authorities (not a hardcoded list): a new
// colour role or layout zone is gate-covered automatically. Plus the perf budget (rung-4 floor).
import { CONTRAST_PAIRS, PERF_BUDGET, measureUiSchema } from './conformancePolicy.js';

/**
 * A single, machine-readable conformance failure (569 §19 Seam 2). The discriminant `kind` plus its
 * structured fields let a consumer anchor the error to the offending region/token/field, drive a
 * self-repair loop, or render prose via {@link describeConformanceError}.
 */
export type ConformanceError =
  | { readonly kind: 'invalid-json'; readonly message: string }
  | { readonly kind: 'unrepresentable'; readonly message: string }
  | {
      readonly kind: 'contrast';
      readonly fg: string;
      readonly bg: string;
      readonly ratio: number;
      readonly floor: number;
    }
  | {
      readonly kind: 'perf';
      readonly surfaceId: string;
      readonly nodes: number;
      readonly depth: number;
      readonly maxNodes: number;
      readonly maxDepth: number;
    }
  | { readonly kind: 'required-region-missing'; readonly regions: readonly string[] }
  | { readonly kind: 'required-region-hidden'; readonly regions: readonly string[] };

/** Render a {@link ConformanceError} to the human prose (the historical string form). */
export function describeConformanceError(e: ConformanceError): string {
  switch (e.kind) {
    case 'invalid-json':
    case 'unrepresentable':
      return e.message;
    case 'contrast':
      return (
        `contrast: --${e.fg} on --${e.bg} is ${e.ratio.toFixed(2)}:1 (< ${e.floor}); ` +
        `fails the readability floor`
      );
    case 'perf':
      return (
        `perf: body ${e.surfaceId} exceeds the budget (${e.nodes} nodes / depth ${e.depth} vs ` +
        `${e.maxNodes}/${e.maxDepth}); quarantined to the default`
      );
    case 'required-region-missing':
      return (
        `layout omits required region(s) [${e.regions.join(', ')}] — quarantined to the default ` +
        `layout (the operability/required-presence floor)`
      );
    case 'required-region-hidden':
      return (
        `required region(s) [${e.regions.join(', ')}] carry a \`visibleWhen\` — a required region is ` +
        `unconditionally present (it cannot be authored hidden); quarantined to the default layout`
      );
  }
}

export interface ConformanceVerdict {
  readonly ok: boolean;
  readonly errors: readonly ConformanceError[];
  /** Surface ids whose body failed and must fall back to the default render. */
  readonly quarantinedSurfaces: readonly string[];
  /** True when the authored layout omits a required region and must fall back to the default. */
  readonly quarantinedLayout: boolean;
}

export interface CertifyResult {
  readonly verdict: ConformanceVerdict;
  /** The validated declaration (null if it failed hard validation). */
  readonly declaration: PresentationDeclaration | null;
}

/**
 * Certify a candidate declaration: hard validation (unrepresentability) + the statically
 * checkable contrast floor on literal token pairs. Non-literal (oklch/var) pairs are left to
 * the runtime contrast oracle in the final live batch (the role co-projection already makes
 * accent on-colours safe by construction).
 */
export function certifyPresentation(candidate: unknown): CertifyResult {
  const validation = validatePresentationDeclaration(candidate);
  if (!validation.ok) {
    return {
      verdict: {
        ok: false,
        errors: validation.errors.map(
          (message): ConformanceError => ({ kind: 'unrepresentable', message }),
        ),
        quarantinedSurfaces: [],
        quarantinedLayout: false,
      },
      declaration: null,
    };
  }
  const decl = validation.declaration;
  const errors: ConformanceError[] = [];

  const tokens = decl.theme?.tokens ?? {};
  for (const { fg, bg } of CONTRAST_PAIRS) {
    const fgVal = tokens[fg];
    const bgVal = tokens[bg];
    if (fgVal === undefined || bgVal === undefined) continue;
    const fgc = parseColor(fgVal);
    const bgc = parseColor(bgVal);
    if (!fgc || !bgc) continue; // non-literal — the runtime oracle covers it
    const ratio = contrastRatio(fgc, bgc);
    if (ratio < WCAG_AA) {
      errors.push({ kind: 'contrast', fg, bg, ratio, floor: WCAG_AA });
    }
  }

  // Perf budget (Move 6, rung-4): a schema-valid but pathological body (too many nodes / too deep)
  // is rejected — it certifies structurally but would render-thrash. The offending surface is
  // quarantined to the default (degrade-never-fail), not the whole app.
  const perfQuarantined: string[] = [];
  for (const [surfaceId, body] of Object.entries(decl.body ?? {})) {
    const { nodes, depth } = measureUiSchema((body as { uischema?: unknown }).uischema);
    if (nodes > PERF_BUDGET.maxNodes || depth > PERF_BUDGET.maxDepth) {
      errors.push({
        kind: 'perf',
        surfaceId,
        nodes,
        depth,
        maxNodes: PERF_BUDGET.maxNodes,
        maxDepth: PERF_BUDGET.maxDepth,
      });
      perfQuarantined.push(surfaceId);
    }
  }

  // Operability floor (Move 4/6): a layout that omits a required region is quarantined to the
  // default layout — a user skeleton can never silently orphan a trust/operability-critical
  // region (required-presence). The body/theme tiers are unaffected (degrade-never-fail).
  let quarantinedLayout = false;
  if (decl.layout) {
    const missing = missingRequiredRegions(decl.layout);
    if (missing.length > 0) {
      errors.push({ kind: 'required-region-missing', regions: missing });
      quarantinedLayout = true;
    }
    // 569 §14 — visibility/non-occlusion: a required region must be UNCONDITIONALLY present, so it
    // may not carry a `visibleWhen` that could hide it (the present-but-hidden loophole the
    // presence-only check missed). Same remedy as omission: quarantine the layout to the default.
    const hidden = hiddenRequiredRegions(decl.layout);
    if (hidden.length > 0) {
      errors.push({ kind: 'required-region-hidden', regions: hidden });
      quarantinedLayout = true;
    }
  }

  return {
    verdict: {
      ok: errors.length === 0,
      errors,
      quarantinedSurfaces: perfQuarantined,
      quarantinedLayout,
    },
    declaration: decl,
  };
}

/**
 * Apply quarantine: return a copy of the declaration with the given surface bodies removed,
 * so the apply path renders the DEFAULT (built-in) surface for them — degrade-never-fail.
 */
export function quarantineSurfaces(
  decl: PresentationDeclaration,
  surfaceIds: readonly string[],
): PresentationDeclaration {
  if (surfaceIds.length === 0 || !decl.body) return decl;
  const drop = new Set(surfaceIds);
  const body = Object.fromEntries(
    Object.entries(decl.body).filter(([id]) => !drop.has(id)),
  );
  return { ...decl, body };
}

/**
 * Apply layout quarantine: strip the authored layout tier so the apply path falls back to the
 * DEFAULT (built-in) layout — used when the layout omits a required region (required-presence,
 * Move 4/6). The body + theme tiers are preserved.
 */
export function quarantineLayout(decl: PresentationDeclaration): PresentationDeclaration {
  if (!decl.layout) return decl;
  const { layout: _dropped, ...rest } = decl;
  return rest;
}
