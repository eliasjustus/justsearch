// SPDX-License-Identifier: Apache-2.0
/**
 * 569 Move 1 — the Presentation Declaration.
 *
 * ONE typed, user-authorable artifact spanning the whole authored presentation:
 * the THEME tier (seeds/roles → tokens), the BODY tier (surface bodies as
 * declarations the Move-3 engine renders), and the LAYOUT/composition tier (the
 * surface-composition DSL — regions, order, visibility). It generalizes
 * {@link DesignTokenTree} (the theme-only artifact) along the exact same validation
 * pattern: a CLOSED authored vocabulary, error-accumulating validation that never
 * throws, and the malicious-content attack surface closed by construction.
 *
 * Move 2 (the keystone) is enforced STRUCTURALLY here: the type has **no field** for
 * raw CSS / `cssText`, a derived token, a raw channel, or a free (fg,bg) pair — those
 * forms are not expressible, not merely discouraged. The theme tier may only set
 * names in {@link KNOWN_TOKEN_NAMES}; the layout tier may only mount components in the
 * closed {@link isComponentTag} vocabulary; values are {@link isSafeTokenValue}-checked.
 *
 * A team default, a community skin, a user skin, and an LLM-authored skin are the
 * SAME artifact with a different origin (Move 1) — they flow through the one validate
 * → compile → apply pipe. {@link toThemeTree} projects the theme tier into a
 * {@link DesignTokenTree} so it rides the existing single apply writer (themeState).
 */

import {
  KNOWN_TOKEN_NAMES,
  isSafeTokenValue,
  type DesignTokenTree,
} from './designTokenTree.js';
import { isAuthorableComponent } from './authorableComponents.js';
// 569 Move 8 — the interaction (behavior) tier: a user-authored statechart over the closed
// authorable Effect vocabulary. Validation lives with the engine; this module composes it in.
import {
  validateStatechart,
  type InteractionStatechart,
} from '../substrates/interaction/index.js';
// Type-only — erased at runtime, so no component coupling into this data module.
import type { SurfaceBodyDeclaration } from '../components/DeclaredSurface.js';

/** A composed region in the surface-composition layout (the DSL tier, Move 2). */
export interface LayoutRegion {
  /** The region's slot/zone id. */
  readonly id: string;
  /** A host component to mount — MUST be in the closed component vocabulary. */
  readonly component?: string;
  /** Ordering within the surface (ascending). */
  readonly order?: number;
  /**
   * Optional visibility binding — a non-Turing-complete expression over the surface's
   * typed data (CEL/AEL-class). Authored as data; evaluated by the host (Phase 4).
   */
  readonly visibleWhen?: string;
}

/** The surface-composition layout tier — ordered, conditional regions. */
export interface PresentationLayout {
  readonly regions: readonly LayoutRegion[];
}

/** The theme tier — same closed-vocab token map as a DesignTokenTree. */
export interface PresentationTheme {
  readonly tokens: Record<string, string>;
}

/** Surface bodies keyed by the surface id they declare. */
export type PresentationBody = Readonly<Record<string, SurfaceBodyDeclaration>>;

/** Interaction statecharts keyed by id — the behavior tier (Move 8). */
export type PresentationInteraction = Readonly<Record<string, InteractionStatechart>>;

/**
 * The Presentation Declaration. Note the absence — by construction — of any
 * `cssText` / raw-structure / derived-token field: the breaking forms are
 * unrepresentable (Move 2).
 */
/**
 * 569 §19 Seam 1 — the declaration's PROVENANCE, a first-class declared field (not metadata). The
 * three authoring origins (team default, user, the on-device LLM) are the SAME artifact distinguished
 * only by this field; it is a trust axis the rest of the pipeline reads (the reserved trusted channel
 * refuses non-team origins; an "authored by the assistant" indicator is a projection of `kind`).
 */
export type PresentationOrigin = {
  readonly kind: 'team' | 'user' | 'llm' | 'plugin';
  /** Optional authoring timestamp (epoch ms) — informational. */
  readonly authoredAt?: number;
};

const ORIGIN_KINDS: ReadonlySet<string> = new Set(['team', 'user', 'llm', 'plugin']);

export interface PresentationDeclaration {
  readonly schemaVersion: 1;
  readonly id: string;
  readonly displayName: string;
  readonly description?: string;
  readonly version?: string;
  readonly author?: string;
  /** 569 §19 Seam 1 — authoring provenance (team / user / llm / plugin). */
  readonly origin?: PresentationOrigin;
  /** Theme tier — seeds/roles projected to tokens. */
  readonly theme?: PresentationTheme;
  /** Body tier — surface bodies rendered by the Move-3 engine. */
  readonly body?: PresentationBody;
  /** Layout/composition tier — the surface-composition DSL. */
  readonly layout?: PresentationLayout;
  /** Interaction/behavior tier — user-authored statecharts (Move 8). */
  readonly interaction?: PresentationInteraction;
}

export type PresentationValidationResult =
  | { readonly ok: true; readonly declaration: PresentationDeclaration }
  | { readonly ok: false; readonly errors: readonly string[] };

const ID_RE = /^[a-z][a-z0-9.-]+$/;

/**
 * Reject any key outside the closed authored shape (Move 2 / 568 Part II: format-level
 * unrepresentability). A derived token, a raw `cssText`, or an arbitrary-structure field
 * is not a silently-ignored extra — it is a validation error, so the breaking form is
 * UNREPRESENTABLE, not merely un-clicked.
 */
function rejectUnknownKeys(
  obj: Record<string, unknown>,
  allowed: readonly string[],
  prefix: string,
  errors: string[],
): void {
  const set = new Set(allowed);
  for (const k of Object.keys(obj)) {
    if (!set.has(k)) {
      errors.push(
        `${prefix}${k} is not an authorable field — the declaration has no slot for it ` +
          `(Move 2: a derived token / raw CSS / arbitrary structure is unrepresentable).`,
      );
    }
  }
}

function validateThemeTier(
  theme: unknown,
  errors: string[],
): void {
  if (theme === null || typeof theme !== 'object') {
    errors.push('theme must be an object when present');
    return;
  }
  rejectUnknownKeys(theme as Record<string, unknown>, ['tokens'], 'theme.', errors);
  const tokens = (theme as Record<string, unknown>)['tokens'];
  if (tokens === null || typeof tokens !== 'object') {
    errors.push('theme.tokens must be an object');
    return;
  }
  for (const [name, value] of Object.entries(tokens as Record<string, unknown>)) {
    if (!KNOWN_TOKEN_NAMES.has(name)) {
      errors.push(`theme.tokens.${name} is not a known token (see KNOWN_TOKEN_NAMES)`);
      continue;
    }
    if (typeof value !== 'string') {
      errors.push(`theme.tokens.${name} must be a string; got ${typeof value}`);
      continue;
    }
    if (!isSafeTokenValue(value)) {
      errors.push(
        `theme.tokens.${name} contains brace/angle-bracket characters that could ` +
          `break the :root rule context. Refusing for safety.`,
      );
    }
  }
}

function validateLayoutTier(layout: unknown, errors: string[]): void {
  if (layout === null || typeof layout !== 'object') {
    errors.push('layout must be an object when present');
    return;
  }
  const regions = (layout as Record<string, unknown>)['regions'];
  if (!Array.isArray(regions)) {
    errors.push('layout.regions must be an array');
    return;
  }
  regions.forEach((region, i) => {
    if (region === null || typeof region !== 'object') {
      errors.push(`layout.regions[${i}] must be an object`);
      return;
    }
    const r = region as Record<string, unknown>;
    rejectUnknownKeys(r, ['id', 'component', 'order', 'visibleWhen'], `layout.regions[${i}].`, errors);
    if (typeof r['id'] !== 'string' || (r['id'] as string).length === 0) {
      errors.push(`layout.regions[${i}].id must be a non-empty string`);
    }
    if (r['component'] !== undefined) {
      if (typeof r['component'] !== 'string' || !isAuthorableComponent(r['component'] as string)) {
        errors.push(
          `layout.regions[${i}].component must be an AUTHORABLE host component (in the closed ` +
            `vocabulary and not reserved — a trusted/chrome component cannot be skin-mounted); ` +
            `got ${JSON.stringify(r['component'])}`,
        );
      }
    }
    if (r['order'] !== undefined && typeof r['order'] !== 'number') {
      errors.push(`layout.regions[${i}].order must be a number when present`);
    }
    if (r['visibleWhen'] !== undefined && typeof r['visibleWhen'] !== 'string') {
      errors.push(`layout.regions[${i}].visibleWhen must be a string expression when present`);
    }
  });
}

function validateBodyTier(body: unknown, errors: string[]): void {
  if (body === null || typeof body !== 'object') {
    errors.push('body must be an object when present');
    return;
  }
  for (const [surfaceId, decl] of Object.entries(body as Record<string, unknown>)) {
    if (decl === null || typeof decl !== 'object') {
      errors.push(`body.${surfaceId} must be a surface-body declaration object`);
      continue;
    }
    const d = decl as Record<string, unknown>;
    if (d['schema'] === null || typeof d['schema'] !== 'object') {
      errors.push(`body.${surfaceId}.schema must be a JSON Schema object`);
    }
    if (d['uischema'] === null || typeof d['uischema'] !== 'object') {
      errors.push(`body.${surfaceId}.uischema must be a UI Schema object`);
    }
    // 569 §14 — the co-projected facets: declare WHICH/priority only; the engine derives the
    // live state + the clip, so these carry no breaking form (a ref + ordered items).
    if (d['liveness'] !== undefined && typeof d['liveness'] !== 'string') {
      errors.push(`body.${surfaceId}.liveness must be a string signal id when present`);
    }
    if (d['overflow'] !== undefined) {
      if (!Array.isArray(d['overflow'])) {
        errors.push(`body.${surfaceId}.overflow must be an array of adaptive items when present`);
      } else {
        d['overflow'].forEach((it: unknown, i: number) => {
          const item = it as Record<string, unknown>;
          if (it === null || typeof it !== 'object') {
            errors.push(`body.${surfaceId}.overflow[${i}] must be an object`);
            return;
          }
          // 594 Move 1b — a chip carries a string `id` plus EXACTLY ONE of `label` (decorative free
          // text) or `fact` (a fact-ref the engine projects via projectFact). Both-or-neither is an
          // error: a factual chip may not also bake a literal, and a chip must say something.
          const hasLabel = typeof item['label'] === 'string';
          const hasFact = typeof item['fact'] === 'string';
          if (typeof item['id'] !== 'string' || hasLabel === hasFact) {
            errors.push(
              `body.${surfaceId}.overflow[${i}] must carry string \`id\` and exactly one of ` +
                `\`label\` (decorative) or \`fact\` (a fact-ref; 594 Move 1b)`,
            );
          }
          if (typeof item['priority'] !== 'number') {
            errors.push(`body.${surfaceId}.overflow[${i}] must carry a number \`priority\``);
          }
        });
      }
    }
  }
}

function validateInteractionTier(interaction: unknown, errors: string[]): void {
  if (interaction === null || typeof interaction !== 'object') {
    errors.push('interaction must be an object when present');
    return;
  }
  for (const [id, chart] of Object.entries(interaction as Record<string, unknown>)) {
    const res = validateStatechart(chart);
    if (!res.ok) {
      for (const e of res.errors) errors.push(`interaction.${id}: ${e}`);
    }
  }
}

/**
 * Validate a candidate Presentation Declaration. Accepts unknown input; never throws;
 * accumulates all errors. Reuses the theme closed-vocab + safe-value rules, the closed
 * component vocabulary, and the closed authorable Effect vocabulary (interaction tier).
 */
export function validatePresentationDeclaration(
  candidate: unknown,
): PresentationValidationResult {
  const errors: string[] = [];

  if (candidate === null || typeof candidate !== 'object') {
    return { ok: false, errors: ['presentation declaration is not an object'] };
  }
  const t = candidate as Record<string, unknown>;

  rejectUnknownKeys(
    t,
    ['schemaVersion', 'id', 'displayName', 'description', 'version', 'author', 'origin', 'theme', 'body', 'layout', 'interaction'],
    '',
    errors,
  );

  if (t['schemaVersion'] !== 1) {
    errors.push(`schemaVersion must be the literal number 1; got ${JSON.stringify(t['schemaVersion'])}`);
  }
  if (typeof t['id'] !== 'string' || !ID_RE.test(t['id'] as string)) {
    errors.push(`id must be a string matching ${ID_RE}; got ${JSON.stringify(t['id'])}`);
  }
  if (typeof t['displayName'] !== 'string' || (t['displayName'] as string).length === 0) {
    errors.push('displayName must be a non-empty string');
  }
  for (const opt of ['description', 'version', 'author'] as const) {
    if (t[opt] !== undefined && typeof t[opt] !== 'string') {
      errors.push(`${opt} must be a string when present`);
    }
  }
  // 569 §19 Seam 1 — provenance: an object with a `kind` in the closed origin set.
  if (t['origin'] !== undefined) {
    const o = t['origin'];
    if (o === null || typeof o !== 'object') {
      errors.push('origin must be an object when present');
    } else {
      const ok = o as Record<string, unknown>;
      rejectUnknownKeys(ok, ['kind', 'authoredAt'], 'origin', errors);
      if (typeof ok['kind'] !== 'string' || !ORIGIN_KINDS.has(ok['kind'] as string)) {
        errors.push(`origin.kind must be one of team | user | llm | plugin; got ${JSON.stringify(ok['kind'])}`);
      }
      if (ok['authoredAt'] !== undefined && typeof ok['authoredAt'] !== 'number') {
        errors.push('origin.authoredAt must be a number when present');
      }
    }
  }

  if (t['theme'] !== undefined) validateThemeTier(t['theme'], errors);
  if (t['layout'] !== undefined) validateLayoutTier(t['layout'], errors);
  if (t['body'] !== undefined) validateBodyTier(t['body'], errors);
  if (t['interaction'] !== undefined) validateInteractionTier(t['interaction'], errors);

  if (errors.length > 0) return { ok: false, errors };
  return { ok: true, declaration: candidate as PresentationDeclaration };
}

/**
 * Project the theme tier of a declaration into a {@link DesignTokenTree}, so it flows
 * through the EXISTING single apply writer (themeState.applyAppearance) — one pipe,
 * one catalog (Move 1). Returns null when the declaration has no theme tier.
 */
export function toThemeTree(decl: PresentationDeclaration): DesignTokenTree | null {
  if (!decl.theme) return null;
  return {
    schemaVersion: 1,
    id: decl.id,
    displayName: decl.displayName,
    ...(decl.description !== undefined ? { description: decl.description } : {}),
    ...(decl.version !== undefined ? { version: decl.version } : {}),
    ...(decl.author !== undefined ? { author: decl.author } : {}),
    tokens: decl.theme.tokens,
  };
}
