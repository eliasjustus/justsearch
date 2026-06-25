// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 477 H3.3 — Theme L2 substrate.
 *
 * Validates a theme manifest fetched from `/themes/manifest.json`
 * against the JSON Schema at `/themes/manifest.schema.json`. The
 * validator is structural — it checks shape, required fields, and
 * id pattern; it does NOT validate the referenced CSS files.
 *
 * V1.5.1 alpha shipped a hardcoded TypeScript catalog
 * (`themesCatalog.ts`). H3.3 adds the manifest-driven path so
 * adding a theme is "drop a CSS file + update manifest.json"
 * rather than "rewrite the catalog + rebuild the app." V1.5.2
 * adds the Tauri filesystem watcher that replaces the manifest
 * with an auto-discovery scan.
 *
 * The DTCG-aligned theme.json (DTCG v2025.10 preview) is V1.6
 * territory — DTCG is preview-stage, not implementable as-is, and
 * the JustSearch theme contract (CSS-only) is simpler than DTCG's
 * structured token tree. 478 §4.E proposes the DTCG migration
 * once the spec stabilizes; H3.3 ships the lightweight manifest
 * format that is forward-compatible with the DTCG migration.
 */

export interface ThemeManifestEntry {
  readonly id: string;
  readonly displayName: string;
  readonly description?: string;
  readonly cssPath: string;
  readonly author?: string;
  readonly version?: string;
}

export interface ThemeManifest {
  readonly $schema?: string;
  readonly schemaVersion: 1;
  readonly themes: readonly ThemeManifestEntry[];
}

/**
 * Result of validating a candidate theme manifest. Either
 * `{ ok: true, manifest }` or `{ ok: false, errors }`. Validators
 * never throw; structural failures produce `ok: false`.
 */
export type ManifestValidationResult =
  | { readonly ok: true; readonly manifest: ThemeManifest }
  | { readonly ok: false; readonly errors: readonly string[] };

/** Theme id constraint: lowercase letter + dot/dash/digit/letter mix. */
const ID_PATTERN = /^[a-z][a-z0-9.-]+$/;

/** cssPath must start with `/` (root-relative). */
const CSS_PATH_PATTERN = /^\//;

/**
 * Validate a candidate manifest object against the V1.5.1 schema.
 * Accepts unknown input (e.g., parsed JSON); returns a typed
 * manifest on success or a list of error messages on failure.
 *
 * Errors are accumulated, not short-circuited — callers see all
 * problems at once.
 */
export function validateThemeManifest(
  candidate: unknown,
): ManifestValidationResult {
  const errors: string[] = [];

  if (candidate === null || typeof candidate !== 'object') {
    return { ok: false, errors: ['manifest is not an object'] };
  }
  const m = candidate as Record<string, unknown>;

  if (m['schemaVersion'] !== 1) {
    errors.push(
      `schemaVersion must be the literal number 1; got ${JSON.stringify(m['schemaVersion'])}`,
    );
  }
  if (!Array.isArray(m['themes'])) {
    errors.push('themes must be an array');
    return { ok: false, errors };
  }
  const seenIds = new Set<string>();
  m['themes'].forEach((entry, idx) => {
    const path = `themes[${idx}]`;
    if (entry === null || typeof entry !== 'object') {
      errors.push(`${path} is not an object`);
      return;
    }
    const e = entry as Record<string, unknown>;
    if (typeof e['id'] !== 'string') {
      errors.push(`${path}.id must be a string`);
    } else if (!ID_PATTERN.test(e['id'])) {
      errors.push(
        `${path}.id '${e['id']}' must match /^[a-z][a-z0-9.-]+$/`,
      );
    } else if (seenIds.has(e['id'])) {
      errors.push(`${path}.id '${e['id']}' is duplicated in the manifest`);
    } else {
      seenIds.add(e['id']);
    }
    if (typeof e['displayName'] !== 'string' || e['displayName'].length === 0) {
      errors.push(`${path}.displayName must be a non-empty string`);
    }
    if (typeof e['cssPath'] !== 'string') {
      errors.push(`${path}.cssPath must be a string`);
    } else if (!CSS_PATH_PATTERN.test(e['cssPath'])) {
      errors.push(`${path}.cssPath '${e['cssPath']}' must start with '/'`);
    }
    if (
      e['description'] !== undefined &&
      typeof e['description'] !== 'string'
    ) {
      errors.push(`${path}.description must be a string when present`);
    }
    if (e['author'] !== undefined && typeof e['author'] !== 'string') {
      errors.push(`${path}.author must be a string when present`);
    }
    if (e['version'] !== undefined && typeof e['version'] !== 'string') {
      errors.push(`${path}.version must be a string when present`);
    }
  });

  if (errors.length > 0) {
    return { ok: false, errors };
  }
  return { ok: true, manifest: candidate as ThemeManifest };
}

/**
 * Boot-time fetch of the theme manifest. Returns the validated
 * manifest, or `null` on fetch / parse / validation failure (the
 * caller falls back to the built-in catalog).
 *
 * V1.5.1 fetches a static JSON file shipped at
 * `/themes/manifest.json`. V1.5.2 with Tauri filesystem watcher
 * generates the manifest dynamically from a directory scan.
 */
export async function fetchThemeManifest(
  // Static manifest file (not an API call). Eslint's
  // restricted-globals warning is for backend API calls; this is
  // a co-located static asset.
  // eslint-disable-next-line no-restricted-globals
  fetchImpl: typeof fetch = fetch,
  url: string = '/themes/manifest.json',
): Promise<ThemeManifest | null> {
  let body: unknown;
  try {
    const response = await fetchImpl(url);
    if (!response.ok) {
      console.debug(
        `[themeManifest] fetch ${url} returned ${response.status}; using built-in catalog`,
      );
      return null;
    }
    body = await response.json();
  } catch (err) {
    console.debug(
      `[themeManifest] fetch ${url} failed; using built-in catalog`,
      err,
    );
    return null;
  }

  const result = validateThemeManifest(body);
  if (!result.ok) {
    console.debug(
      `[themeManifest] manifest validation failed (${result.errors.length} error(s)):\n` +
        result.errors.map((e) => `  - ${e}`).join('\n') +
        '\nFalling back to built-in catalog.',
    );
    return null;
  }
  return result.manifest;
}
