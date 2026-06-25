// SPDX-License-Identifier: Apache-2.0
/**
 * Backend-served Resource catalog (registry-resource namespace).
 *
 * Slice 3a.1.4b: parallel of `errorCatalog.ts` for the `registry-resource`
 * namespace. Resolves Resource presentation labels (label/description) and
 * MetricRef labels declared on HealthEvent body fields.
 *
 * The backend serves `GET /api/messages/registry-resource/{locale}` via
 * `MessageCatalogController` (one instance per namespace; see tempdoc 429
 * §E.8.b + §E.17). This module fetches the en catalog at app boot and
 * exposes a synchronous `localizeResourceKey(key)` lookup callable from
 * Lit components without tying them to the Lingui React runtime.
 *
 * Lookup contract: keys missing from the catalog return the raw key
 * (defensive — surfaces missing translations honestly rather than
 * silently substituting an empty string).
 *
 * Caching mirrors `errorCatalog.ts`: localStorage-cached body + ETag,
 * conditional GET on subsequent boots, graceful degradation if storage
 * is unavailable. Cache keys are namespace-scoped to avoid pollution.
 */

/**
 * 478 §4.F refactor (slice 477 follow-on): owner-scoped resource
 * catalog. The flat `Record<string, string>` is split into:
 *   - `core`: server-fetched authoritative translations
 *   - `plugins`: per-plugin namespace, keyed by plugin id
 *
 * Lookup priority: core > plugins (server-authoritative wins).
 *
 * Cross-plugin and plugin-overrides-core writes are now structurally
 * impossible: writers go through namespace-scoped APIs. This eliminates
 * the V1.5 alpha workaround where PluginRegistry tracked an
 * `installedTranslationKeys: string[]` array per plugin to support
 * removal-by-key-list. Removal is now a single namespace delete.
 */
let coreCatalog: Record<string, string> = {};
const pluginCatalogs = new Map<string, Record<string, string>>();
let bootAttempted = false;
let missingKeyLogged = new Set<string>();

const STORAGE_KEY_BODY = 'justsearch.resourceCatalog.en.body';
const STORAGE_KEY_ETAG = 'justsearch.resourceCatalog.en.etag';

interface ResourceCatalogResponse {
  schemaVersion?: string;
  locale?: string;
  namespace?: string;
  messages?: Record<string, string>;
}

function loadFromStorage(): { body: Record<string, string>; etag: string } | null {
  try {
    if (typeof localStorage === 'undefined') return null;
    const bodyJson = localStorage.getItem(STORAGE_KEY_BODY);
    const etag = localStorage.getItem(STORAGE_KEY_ETAG);
    if (!bodyJson || !etag) return null;
    const parsed = JSON.parse(bodyJson);
    if (!parsed || typeof parsed !== 'object') return null;
    const body = parsed as Record<string, string>;
    if (Object.keys(body).length === 0) return null;
    return { body, etag };
  } catch {
    return null;
  }
}

function saveToStorage(body: Record<string, string>, etag: string): void {
  try {
    if (typeof localStorage === 'undefined') return;
    localStorage.setItem(STORAGE_KEY_BODY, JSON.stringify(body));
    localStorage.setItem(STORAGE_KEY_ETAG, etag);
  } catch {
    // ignore — best-effort cache
  }
}

/**
 * Boot-time fetch of the registry-resource catalog from the backend.
 *
 * Call once during app boot (e.g., from `i18n.ts`). Subsequent calls are no-ops
 * unless the previous attempt failed and the catalog is empty.
 *
 * On success: in-memory `catalog` is populated; subsequent
 * `localizeResourceKey` calls resolve keys synchronously.
 *
 * On 304: the seeded localStorage body is retained as the in-memory catalog.
 *
 * On failure: the catalog stays empty (or seeded from prior session) and
 * `localizeResourceKey` falls back to raw-key passthrough.
 */
export async function bootResourceCatalog(baseUrl: string): Promise<void> {
  if (bootAttempted && Object.keys(coreCatalog).length > 0) {
    return;
  }
  bootAttempted = true;

  const cached = loadFromStorage();
  if (cached) {
    // Tempdoc 565 §28 — MERGE, never replace `coreCatalog`. Every catalog boot
    // (surface/health/operation/workflow) Object.assigns into this one object and
    // they all run together in i18n.ts's `Promise.all` with no ordering. A bare
    // `coreCatalog = …` here would wipe a sibling catalog that merged first on a
    // cold boot (the §28 workflow-label clobber). Keep all writers merge-only.
    Object.assign(coreCatalog, cached.body);
  }

  if (!baseUrl) {
    console.debug('[resourceCatalog] no baseUrl available; using cached catalog if any (raw-key fallback otherwise)');
    return;
  }

  try {
    const headers: Record<string, string> = {};
    if (cached?.etag) {
      headers['If-None-Match'] = cached.etag;
    }
    const response = await fetch(`${baseUrl}/api/messages/registry-resource/en`, { headers });

    if (response.status === 304) {
      return;
    }

    if (!response.ok) {
      console.debug(
        `[resourceCatalog] /api/messages/registry-resource/en returned ${response.status}; ` +
        `using cached catalog if any (raw-key fallback otherwise)`,
      );
      return;
    }

    const body = (await response.json()) as ResourceCatalogResponse;
    if (body && typeof body === 'object' && body.messages && typeof body.messages === 'object') {
      // §28 — merge, never replace (see the cache-seed note above): a cold-boot 200 here
      // must not clobber a sibling catalog (workflow/surface/operation) that already merged.
      Object.assign(coreCatalog, body.messages);
      const etag = response.headers.get('ETag');
      if (etag) {
        saveToStorage(body.messages, etag);
      }
    } else {
      console.debug('[resourceCatalog] /api/messages/registry-resource/en response missing `messages` map; cached catalog retained');
    }
  } catch (err) {
    console.debug('[resourceCatalog] /api/messages/registry-resource/en fetch failed; cached catalog retained', err);
  }
}

/**
 * Tempdoc 565 §28 cleanup — the ONE simple message-catalog boot. The
 * surface/health/operation/workflow catalogs are identical fetch-and-merge flows
 * differing only by namespace; collapsing them to this helper makes the
 * "merge into `coreCatalog`, never replace" invariant true BY CONSTRUCTION (a new
 * catalog is a one-line wrapper, not a copy that can drift into a `coreCatalog = …`
 * replacer — the bug §28 fixed). `bootResourceCatalog` stays separate: it owns the
 * extra localStorage/ETag/304 caching, but it too merges (see its note).
 *
 * Best-effort: a missing/!ok/failed fetch leaves `coreCatalog` untouched and the
 * affected keys fall back to raw-key passthrough (humanizeId / deriveTitle upstream).
 */
async function bootMessageCatalog(baseUrl: string, namespace: string): Promise<void> {
  if (!baseUrl) return;
  try {
    const response = await fetch(`${baseUrl}/api/messages/${namespace}/en`);
    if (!response.ok) return;
    const body = (await response.json()) as ResourceCatalogResponse;
    if (body?.messages && typeof body.messages === 'object') {
      Object.assign(coreCatalog, body.messages);
    }
  } catch {
    // best-effort — affected labels fall back to their upstream id-derived default
  }
}

export const bootSurfaceCatalog = (baseUrl: string): Promise<void> =>
  bootMessageCatalog(baseUrl, 'registry-surface');

export const bootHealthEventsCatalog = (baseUrl: string): Promise<void> =>
  bootMessageCatalog(baseUrl, 'health-events');

export const bootOperationMessageCatalog = (baseUrl: string): Promise<void> =>
  bootMessageCatalog(baseUrl, 'registry-operation');

/**
 * Tempdoc 565 §27.4: the run-window workflow picker's authored-label catalog, so
 * `present({kind:'workflow', labelKey})` resolves the authored label instead of the
 * `humanizeId(workflow id)` fallback.
 */
export const bootWorkflowCatalog = (baseUrl: string): Promise<void> =>
  bootMessageCatalog(baseUrl, 'registry-workflow');

/**
 * Synchronous lookup against the in-memory catalog. Returns the localized
 * string if present, or the raw key as a defensive fallback.
 *
 * 478 §4.F: lookup is core-first then per-plugin scoped. Server-fetched
 * core catalog wins over plugin contributions (authoritative). Plugin
 * scope is searched in insertion order (V1.5.1 alpha doesn't expose
 * order semantics; first-installed wins for shared plugin keys, but
 * cross-plugin shared keys are an anti-pattern — plugins should
 * namespace their own keys).
 *
 * Emits a one-shot diagnostic per missing key so contributors can spot
 * unbacked keys without log spam.
 */
export function localizeResourceKey(key: string): string {
  // Core (server-authoritative) wins.
  const coreMessage = coreCatalog[key];
  if (coreMessage !== undefined) return coreMessage;
  // Then check plugin scopes.
  for (const pluginScope of pluginCatalogs.values()) {
    const message = pluginScope[key];
    if (message !== undefined) return message;
  }
  if (!missingKeyLogged.has(key)) {
    missingKeyLogged.add(key);
    console.debug(`[resourceCatalog] missing key in catalog: ${key} (rendering raw key as fallback)`);
  }
  return key;
}

/**
 * 478 §4.F — register plugin-contributed translations under a
 * scoped namespace. Replaces a flat catalog merge.
 *
 * The plugin's keys live under `pluginCatalogs.<pluginId>` — they
 * cannot overwrite core (server-fetched) keys, and they cannot
 * overwrite ANOTHER plugin's keys. Cross-plugin shared keys are
 * structurally impossible: each plugin owns its own scope.
 *
 * Lookup is core-first (per `localizeResourceKey`); plugins resolve
 * keys their own scope provides + any keys other plugins or core
 * have provided. Plugins should namespace their keys
 * (`<pluginId>.<feature>.label`) to avoid surprise collisions.
 *
 * Returns the count of keys registered (NOT the keys added net of
 * core's existing keys — V1.5.1 alpha behavior was inconsistent
 * about this; §4.F's clean separation makes the count unambiguous).
 *
 * If `entries` is empty or non-string-valued, the plugin scope is
 * left empty (legitimate for plugins with no translations).
 */
export function registerPluginCatalog(
  pluginId: string,
  entries: Record<string, string>,
): number {
  const scope: Record<string, string> = {};
  let added = 0;
  for (const key of Object.keys(entries)) {
    const value = entries[key];
    if (typeof value === 'string') {
      scope[key] = value;
      added++;
    }
  }
  pluginCatalogs.set(pluginId, scope);
  return added;
}

/**
 * 478 §4.F — counterpart to {@link registerPluginCatalog}. Removes
 * the plugin's entire scope in O(1). PluginRegistry.uninstall calls
 * this; no longer needs to track per-plugin key arrays.
 *
 * Idempotent: removing an unknown plugin id is a no-op.
 */
export function unregisterPluginCatalog(pluginId: string): void {
  pluginCatalogs.delete(pluginId);
}

/**
 * V1.5 alpha back-compat shim — old name routes to a synthetic
 * 'legacy' plugin scope. Existing callers continue to work.
 *
 * @deprecated Use {@link registerPluginCatalog} with an explicit
 *   plugin id. The legacy bucket is preserved for back-compat but
 *   has no namespace isolation: all calls to this function share
 *   the same scope, so the last writer wins for shared keys.
 *
 * Returns the count of keys added net of core's existing keys (the
 * V1.5 alpha behavior — preserves the existing return contract).
 */
export function registerCatalogEntries(
  entries: Record<string, string>,
): number {
  const existing = pluginCatalogs.get('__legacy__') ?? {};
  let added = 0;
  for (const key of Object.keys(entries)) {
    const value = entries[key];
    if (typeof value === 'string' && coreCatalog[key] === undefined) {
      existing[key] = value;
      added++;
    }
  }
  pluginCatalogs.set('__legacy__', existing);
  return added;
}

/**
 * V1.5 alpha back-compat shim. Removes keys from the legacy bucket.
 *
 * @deprecated Use {@link unregisterPluginCatalog} with an explicit
 *   plugin id.
 */
export function unregisterCatalogEntries(keys: ReadonlyArray<string>): void {
  const legacy = pluginCatalogs.get('__legacy__');
  if (!legacy) return;
  for (const key of keys) {
    delete legacy[key];
  }
  if (Object.keys(legacy).length === 0) {
    pluginCatalogs.delete('__legacy__');
  }
}

/** Test-only: reset the module's cached state. */
export function __resetForTest(): void {
  coreCatalog = {};
  pluginCatalogs.clear();
  bootAttempted = false;
  missingKeyLogged = new Set<string>();
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem(STORAGE_KEY_BODY);
      localStorage.removeItem(STORAGE_KEY_ETAG);
    }
  } catch {
    // ignore
  }
}

/** Test-only: seed the catalog directly without an HTTP call. */
export function __seedForTest(messages: Record<string, string>): void {
  coreCatalog = { ...messages };
  bootAttempted = true;
}
