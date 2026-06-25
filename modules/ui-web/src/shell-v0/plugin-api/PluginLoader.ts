// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 465 (V1.5 alpha) + 477 H2.1 (V1.5.1) — V1.5 plugin loader.
 *
 * Loads a plugin manifest from a URL. The plugin source is fetched
 * as text and evaluated INSIDE a SES Compartment with explicit
 * host endowments. Plugin code does not reach `globalThis`,
 * `window`, `document`, `fetch`, or any other browser global
 * unless the host explicitly endows it.
 *
 * V1.5 alpha shipped a native `import()` based loader that ran
 * plugin code in the HOST realm. That worked but the substrate's
 * promised "isolation" was decorative — a malicious plugin could
 * mutate `Array.prototype` or read `window.localStorage` directly.
 * 477 H2.1 closes that gap by switching to Compartment-Loader
 * integration. Plugins now genuinely run in a separate realm.
 *
 * Plugin source contract:
 *   The plugin module ships an EXPRESSION (NOT an ES module). The
 *   expression evaluates to either:
 *     (a) A PluginManifest object directly, OR
 *     (b) A factory function `(endowments) => PluginManifest`.
 *
 *   The factory shape is preferred — it makes the endowment
 *   contract explicit and lets the plugin define classes that
 *   close over the endowed primitives.
 *
 *   ESM `import` / `export` statements are NOT supported in plugin
 *   source. SES Compartments support cross-module imports via
 *   `compartment.import` + a module map; that infrastructure ships
 *   in V1.5.2+ when a real plugin needs to import sub-modules.
 *
 * Migration from V1.5 alpha contract:
 *
 *   // V1.5 alpha (deprecated)
 *   export default { id, version, ..., register(host) {...} };
 *
 *   // V1.5.1 (this slice)
 *   (({ customElements, HTMLElement, host }) => {
 *     class Foo extends HTMLElement { ... }
 *     customElements.define('foo', Foo);
 *     return { id, version, ..., register(host) {...} };
 *   })
 *
 * Per slices/470-v1-5-user-ui-authorship-substrate.md §B.A.1 and
 * §B.B.3: the loader composes with Tailwind v4's @layer cascade
 * and the V1.5 SurfaceCatalog merge. Audience-floor enforcement
 * still happens at registration time via PluginRegistry.
 *
 * @see slices/477-v1-5-post-alpha-roadmap.md §3.1 (Compartment-Loader integration)
 * @see slices/478-v1-5-structural-design-refinements.md §4.C
 *      (Compartment-per-Surface — V1.5.2+ refinement of this slice)
 */

import type { PluginManifest } from './plugin-types.js';
import type { HostApiDeps } from './HostApiImpl.js';
import { kernelEndowment, resolveKernelModules } from './KernelResolver.js';
import type { PluginRegistry, PluginTrustTier } from './PluginRegistry.js';
import {
  ensureSesLoaded,
  type PluginEndowments,
} from './PluginCompartment.js';
import { buildCapabilityBundle } from './PluginCapabilityBundle.js';
import {
  StubTrustChannel,
  type TrustChannel,
  type TrustVerdict,
} from './TrustChannel.js';

/**
 * Error thrown when {@link loadPluginFromUrl} fails. Carries the
 * URL + a structured cause so callers can build diagnostic UX
 * without parsing message strings.
 */
export class PluginLoadError extends Error {
  readonly url: string;
  readonly stage: 'fetch' | 'evaluate' | 'shape' | 'install';
  override readonly cause?: unknown;

  constructor(
    message: string,
    options: {
      url: string;
      stage: 'fetch' | 'evaluate' | 'shape' | 'install';
      cause?: unknown;
    },
  ) {
    super(message);
    this.name = 'PluginLoadError';
    this.url = options.url;
    this.stage = options.stage;
    this.cause = options.cause;
  }
}

/**
 * Source-fetcher abstraction. The default implementation uses
 * `fetch()` to retrieve the plugin source as text. Tests substitute
 * a mock that returns a fixed source string.
 */
export type SourceFetcher = (url: string) => Promise<string>;

/**
 * §4.2 — a plugin authored as an ES module (top-level `import`/`export`) is loaded in module-mode
 * (`compartment.import` + `@endo/module-source`) so it can reach capabilities via real
 * `import { x } from '@kernel/data'`; anything else is the legacy script-mode factory.
 */
const PLUGIN_ES_MODULE_RE = /^[ \t]*(?:import|export)\s/m;

/**
 * Default source fetcher. Uses `fetch()` against the plugin URL,
 * expects 2xx, returns the response body as text.
 */
async function defaultSourceFetcher(url: string): Promise<string> {
  // Plugin source URL — not a backend API call. The `request()`
  // helper adds CSRF/auth token handling that's specific to the
  // backend; plugin source is a remote ESM module fetch.
  // eslint-disable-next-line no-restricted-globals
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(
      `Plugin fetch failed for ${url}: HTTP ${response.status}`,
    );
  }
  return response.text();
}

/**
 * Tempdoc 560 §28 — the public source-fetch authority, reused by the Settings operator-approval flow
 * so the bytes it hashes for the allowlist are fetched identically to the loader's own fetch.
 */
export const fetchPluginSource: SourceFetcher = defaultSourceFetcher;

/**
 * Validate that an evaluated value matches the PluginManifest shape.
 * Returns the manifest on success; throws PluginLoadError(stage='shape')
 * otherwise. Same posture as `PluginRegistry.install` — structural
 * checks on required fields.
 */
function assertManifestShape(
  candidate: unknown,
  url: string,
): PluginManifest {
  if (candidate === null || typeof candidate !== 'object') {
    throw new PluginLoadError(
      `Plugin source at ${url} did not evaluate to a manifest object.`,
      { url, stage: 'shape' },
    );
  }
  const manifest = candidate as Partial<PluginManifest>;
  if (typeof manifest.id !== 'string' || manifest.id.length === 0) {
    throw new PluginLoadError(
      `Plugin manifest at ${url} is missing required string field 'id'.`,
      { url, stage: 'shape' },
    );
  }
  if (typeof manifest.version !== 'string' || manifest.version.length === 0) {
    throw new PluginLoadError(
      `Plugin manifest at ${url} is missing required string field 'version'.`,
      { url, stage: 'shape' },
    );
  }
  if (typeof manifest.register !== 'function') {
    throw new PluginLoadError(
      `Plugin manifest at ${url} is missing required function field 'register'.`,
      { url, stage: 'shape' },
    );
  }
  if (
    typeof manifest.contractVersion !== 'string' ||
    manifest.contractVersion.length === 0
  ) {
    throw new PluginLoadError(
      `Plugin manifest at ${url} is missing required string field 'contractVersion'.`,
      { url, stage: 'shape' },
    );
  }
  if (
    typeof manifest.tagNamespace !== 'string' ||
    manifest.tagNamespace.length === 0
  ) {
    throw new PluginLoadError(
      `Plugin manifest at ${url} is missing required string field 'tagNamespace'.`,
      { url, stage: 'shape' },
    );
  }
  return manifest as PluginManifest;
}

/**
 * Load a plugin from a URL into a SES Compartment and install it
 * into the registry.
 *
 * Pipeline:
 *   1. Fetch source as text via {@link SourceFetcher}.
 *   2. Build default endowments (host API + customElements,
 *      HTMLElement, localStorage, console, timers, optionally Lit).
 *   3. Construct a fresh `Compartment(endowments)`.
 *   4. `compartment.evaluate(sourceText)` evaluates the expression
 *      in the compartment's realm.
 *   5. If the result is a function, invoke it with endowments to
 *      get the manifest. Otherwise treat the result as the manifest
 *      directly.
 *   6. Validate the manifest shape (`assertManifestShape`).
 *   7. `registry.install(manifest)`.
 *
 * @param registry the {@link PluginRegistry} to install into.
 * @param url the URL of the plugin's source. Default fetcher uses
 *   `fetch()` against it; tests substitute a mock fetcher.
 * @param options optional { sourceFetcher, endowmentsExtension }.
 * @returns the installed manifest.
 * @throws {@link PluginLoadError} when fetch / evaluate / shape /
 *   install fails.
 */
export async function loadPluginFromUrl(
  registry: PluginRegistry,
  url: string,
  options: {
    sourceFetcher?: SourceFetcher;
    endowmentsExtension?: Partial<PluginEndowments>;
    /**
     * 477 H2.4 — trust tier the caller is granting this plugin.
     *
     * V1.5.1 H2.3 update: prefer `trustChannel` over `tier`. When
     * `trustChannel` is provided the verdict's tier wins; explicit
     * `tier` is reserved for compile-time CORE plugins or for tests
     * that bypass the channel.
     *
     * Default behavior: if neither `tier` nor `trustChannel` is
     * provided, the loader uses {@link StubTrustChannel} which
     * always produces UNTRUSTED_PLUGIN.
     */
    tier?: PluginTrustTier;
    /**
     * 477 H2.3 — TrustChannel that produces the Provenance for
     * this plugin. Per 478 §4.D, the loader is a typed CONSUMER
     * of trust verdicts; never decides trust inline. If provided,
     * the verdict's tier overrides any explicit `tier` option
     * EXCEPT when `tier` is 'CORE' (CORE bypasses verification —
     * compiled-in plugins).
     */
    trustChannel?: TrustChannel;
    /**
     * 477 H2.4 — the plugin id the caller expects this URL to
     * install. UNTRUSTED_PLUGIN bundles need this upfront because
     * customElements / localStorage attenuation is namespace-
     * scoped and the plugin source may register elements at
     * evaluation time (before the manifest is parsed).
     *
     * If provided, the loader verifies `manifest.id === expectedPluginId`
     * after evaluation; mismatch throws PluginLoadError(stage='shape').
     * If omitted, the loader uses `'unknown'` as the namespace
     * placeholder and trusts the post-evaluation manifest id.
     */
    expectedPluginId?: string;
    /**
     * Tempdoc 560 §4.2 — when provided, the loader builds the `@kernel/*` capability module map via
     * {@link resolveKernelModules} (resolver-time trust substitution) and passes it to the
     * Compartment, so a module-mode plugin's `import { data } from '@kernel/data'` resolves to the
     * tier-attenuated capability. Omitted ⇒ legacy script-mode (endowment-only) behavior unchanged.
     */
    hostDeps?: HostApiDeps;
  } = {},
): Promise<PluginManifest> {
  const sourceFetcher = options.sourceFetcher ?? defaultSourceFetcher;
  const explicitTier = options.tier;
  const trustChannel: TrustChannel = options.trustChannel ?? StubTrustChannel;
  const expectedPluginId = options.expectedPluginId ?? 'unknown';

  // 1. Fetch source as text.
  let source: string;
  try {
    source = await sourceFetcher(url);
  } catch (err) {
    throw new PluginLoadError(
      `Failed to fetch plugin source at ${url}.`,
      { url, stage: 'fetch', cause: err },
    );
  }

  // 1a. (477 H2.3) Resolve trust tier via TrustChannel. We need
  // tier BEFORE building endowments; TrustChannel.verify() runs
  // against the source text + a (still-unknown) signature
  // hint. Plugin manifests carry an optional `signature` field
  // (PluginTrust.PluginManifestWithSignature); we don't have the
  // manifest yet, but the signature is conventionally embedded
  // as a comment near the top of the source for stage-1
  // verification — V1.5.2 standardizes this.
  //
  // V1.5.1 alpha: extract signature comment if present; pass
  // null otherwise. CORE explicit tier bypasses verification.
  let verdict: TrustVerdict;
  if (explicitTier === 'CORE') {
    verdict = {
      tier: 'CORE',
      explanation: 'CORE — compiled-in or explicitly marked by host',
      identity: null,
    };
  } else {
    const signatureHint = extractSignatureHint(source);
    verdict = await trustChannel.verify({
      source,
      signature: signatureHint,
      url,
    });
    // Explicit `tier: 'TRUSTED_PLUGIN'` is allowed only as a
    // floor — caller asserts trust they verified out-of-band.
    // The verdict's tier is the actual ceiling.
    if (explicitTier === 'TRUSTED_PLUGIN' && verdict.tier === 'UNTRUSTED_PLUGIN') {
      verdict = { ...verdict, explanation: `${verdict.explanation} (caller asserted TRUSTED — overridden by channel verdict)` };
    }
  }
  const tier: PluginTrustTier = verdict.tier;

  // 2. Build endowments tier-attenuated per 477 H2.4 / 478 §4.D.
  // CORE/TRUSTED_PLUGIN: full default bundle.
  // UNTRUSTED_PLUGIN: scoped localStorage + namespace-enforcing
  // customElements proxy.
  // The `host` API is NOT included here — `manifest.register(host)`
  // receives it from the registry at install time. Including host
  // in the factory bundle would conflate validation order.
  const endowments: PluginEndowments = {
    ...buildCapabilityBundle(tier, expectedPluginId),
    // §4.2 — the @kernel/* access path as a resolver-time-substituted endowment (the achievable form
    // under SES 2.0; see kernelEndowment). `kernel('@kernel/data')` returns the tier-attenuated
    // capability. Only wired when the caller supplies host deps (the Shell's install-from-URL path).
    ...(options.hostDeps
      ? { kernel: kernelEndowment(expectedPluginId, tier, options.hostDeps) }
      : {}),
    ...(options.endowmentsExtension ?? {}),
  };

  // 3-5. Evaluate the source. Lazy-load SES first (slice 477 H2.6 — code-split SES into a vendor
  // chunk fetched only when the loader actually fires).
  await ensureSesLoaded();
  // §4.2: the @kernel/* capability boundary as a resolver-substituted module map (the tier-attenuated
  // capabilities). Legacy callers (no host deps) get an empty map.
  const kernelModules = options.hostDeps
    ? resolveKernelModules(expectedPluginId, tier, options.hostDeps)
    : {};
  let candidate: unknown;
  if (PLUGIN_ES_MODULE_RE.test(source)) {
    // §4.2 module-mode: an ES-module plugin reaches capabilities via real `import { x } from
    // '@kernel/data'` (resolved from the kernel module map) and exports its manifest as `default`. SES
    // 2.0 has no source compiler, so we use @endo/module-source's ModuleSource (lazy-imported).
    try {
      // @endo/module-source pulls @endo/env-options, which reads `process.env` (Node-only) at module
      // eval time — absent in the browser → "process is not defined". Shim it before the first import
      // (globalThis stays extensible post-SES-lockdown). Idempotent.
      const g = globalThis as { process?: { env?: Record<string, string> } };
      if (typeof g.process === 'undefined') {
        g.process = { env: {} };
      }
      const { ModuleSource } = await import('@endo/module-source');
      const entry = `plugin:${expectedPluginId}`;
      const compartment = new Compartment(endowments, kernelModules, {
        resolveHook: (specifier: string) => specifier,
        // The boundary IS the set of import paths (§4.2): @kernel/* resolves from the module map; the
        // plugin entry is its own source; ANY other bare import is denied — a module-mode plugin
        // cannot reach a capability except through @kernel/*.
        importHook: async (specifier: string) => {
          if (specifier === entry) return { source: new ModuleSource(source) };
          throw new Error(
            `@kernel boundary: module-mode plugin may not import '${specifier}' ` +
              `(only @kernel/* and its own entry are resolvable).`,
          );
        },
      });
      const ns = (await compartment.import(entry)) as { namespace: { default?: unknown } };
      candidate = ns.namespace.default;
    } catch (err) {
      throw new PluginLoadError(
        `ES-module plugin at ${url} failed to import (module-mode): ${
          err instanceof Error ? err.message : String(err)
        }`,
        { url, stage: 'evaluate', cause: err },
      );
    }
  } else {
    // Legacy script-mode: evaluate a factory/expression and invoke it with endowments.
    let evaluated: unknown;
    try {
      const compartment = new Compartment(endowments, kernelModules);
      evaluated = compartment.evaluate(source);
    } catch (err) {
      throw new PluginLoadError(
        `Plugin source at ${url} threw during compartment evaluation.`,
        { url, stage: 'evaluate', cause: err },
      );
    }
    candidate = evaluated;
    if (typeof evaluated === 'function') {
      try {
        candidate = (evaluated as (e: PluginEndowments) => unknown)(endowments);
      } catch (err) {
        throw new PluginLoadError(
          `Plugin factory at ${url} threw when invoked with endowments.`,
          { url, stage: 'evaluate', cause: err },
        );
      }
    }
  }

  // 6. Validate shape.
  const manifest = assertManifestShape(candidate, url);

  // 6a. (477 H2.4) Verify expectedPluginId matches if provided.
  if (
    options.expectedPluginId !== undefined &&
    manifest.id !== options.expectedPluginId
  ) {
    throw new PluginLoadError(
      `Plugin at ${url} declared id '${manifest.id}' but caller expected '${options.expectedPluginId}'. ` +
        'The expectedPluginId must match the manifest exactly — for UNTRUSTED tier this also matches the localStorage / customElements namespace.',
      { url, stage: 'shape' },
    );
  }

  // 7. Install — propagate the TrustChannel verdict tier so PRESENTATION/audience constraints use the
  // plugin's REAL tier (an UNTRUSTED plugin's non-vocabulary surfaces are then dropped, §4.4).
  try {
    registry.install(manifest, tier);
  } catch (err) {
    throw new PluginLoadError(
      `Plugin '${manifest.id}' from ${url} failed to install: ${
        err instanceof Error ? err.message : String(err)
      }`,
      { url, stage: 'install', cause: err },
    );
  }
  return manifest;
}

/**
 * Best-effort extraction of a signature hint from the plugin
 * source. V1.5.1 alpha convention: plugins embed a signature line
 * as `// @signature: <cosign-bundle-base64>` near the top of the
 * source. The TrustChannel uses this hint to feed the backend's
 * verification call. Absence of a signature line is fine — the
 * channel just verifies the source bytes against any signature
 * stored server-side or returns UNTRUSTED.
 *
 * V1.5.2+ standardizes this when sigstore-java integration ships;
 * the convention may move into the manifest's `signature` field
 * proper.
 */
function extractSignatureHint(source: string): string | undefined {
  const match = source.match(/@signature:\s*([A-Za-z0-9+/=._-]+)/);
  return match ? match[1] : undefined;
}

/**
 * Slice 469 — minimum-viable hot-reload composition.
 *
 * Tears down the named plugin (if installed) and re-loads from the
 * given URL. Each reload constructs a FRESH Compartment, so any
 * intrinsic mutations the previous load did are discarded.
 *
 * The reload is non-atomic — if the URL fails to load, the registry
 * is left empty for that plugin id. V1.5 alpha doesn't track
 * previous versions for rollback; V1.5.2+ adds rollback when 478
 * §4.H lifecycle state machine ships.
 */
export async function reloadPlugin(
  registry: PluginRegistry,
  id: string,
  url: string,
  options: {
    sourceFetcher?: SourceFetcher;
    endowmentsExtension?: Partial<PluginEndowments>;
  } = {},
): Promise<PluginManifest> {
  if (registry.has(id)) {
    registry.uninstall(id);
  }
  return loadPluginFromUrl(registry, url, options);
}
