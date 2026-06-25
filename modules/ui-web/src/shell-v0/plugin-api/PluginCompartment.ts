// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 466 — V1.5 SES Compartment factory for plugin isolation.
 *
 * V1.5 alpha scope:
 *   - Construct a per-plugin `Compartment` with explicit endowments.
 *   - Endowments come from the host-provided endowment map; plugin
 *     code cannot reach `globalThis`, `window`, `document`, `fetch`,
 *     `localStorage`, etc., unless the host endows them.
 *   - Calls `lockdown()` lazily, exactly once, from `ensureSesLoaded`
 *     (tempdoc 560 §28). Per
 *     `slices/470-v1-5-user-ui-authorship-substrate.md` §B.B.4, a bare
 *     Compartment shares the realm's mutable primordials, so isolation
 *     was only partial. lockdown() now freezes the intrinsics — but
 *     lazily (on first plugin load, after app chrome has initialized)
 *     rather than at boot, which dodges the Lit / Vite boot-time
 *     prototype-mutation risk that originally deferred it. The freeze
 *     still lands before the first Compartment is constructed.
 *   - The Compartment evaluates a manifest-shaped module given as
 *     a SOURCE STRING. As of slice 477 H2.1 the PluginLoader DOES
 *     evaluate plugin source inside the Compartment
 *     (`compartment.evaluate(source)` in loadPluginFromUrl) — this is
 *     the live isolation path, not forward-compat substrate. NOTE: the
 *     default endowment bundle still includes `document` (reachable
 *     `window` via `document.defaultView`) — see docs/tempdocs/547
 *     F1/F2 before relying on a Compartment alone for isolation.
 *
 * Production validation per 470 §B.A.4: Agoric and MetaMask both
 * ship SES Compartments in browser/WebView contexts. The library
 * is mature; the integration cost is shape, not viability.
 *
 * @see https://hardenedjs.org/ — Hardened JavaScript / SES home
 * @see https://github.com/endojs/endo — Endo (the SES distribution)
 */

// Slice 477 H2.6 — SES bundle code-splitting.
// SES is no longer statically imported here. The PluginLoader
// calls `await ensureSesLoaded()` before constructing a
// Compartment; tests that need Compartment synchronously must
// `import 'ses'` themselves.

let __sesLoadPromise: Promise<unknown> | null = null;

/**
 * Tempdoc 560 §28 — the production lockdown taming. MUST stay in sync with substrate-lockdown.test.ts,
 * which calls lockdown() itself (hoisted before any import, so it cannot import this const).
 */
const LOCKDOWN_OPTIONS = {
  errorTaming: 'unsafe',
  consoleTaming: 'unsafe',
  domainTaming: 'unsafe',
  overrideTaming: 'severe',
} as const;

/**
 * Lazily import SES and harden the realm (Compartment, lockdown, Hardened JS intrinsics). Idempotent —
 * repeated calls return the same in-flight promise; once resolved subsequent calls resolve immediately.
 *
 * Call this BEFORE any code that constructs `new Compartment(...)`. The PluginLoader does so
 * internally; direct callers of createPluginCompartment must ensure SES is loaded first.
 */
export async function ensureSesLoaded(): Promise<void> {
  if (__sesLoadPromise === null) {
    __sesLoadPromise = import('ses').then(() => hardenRealmOnce());
  }
  await __sesLoadPromise;
}

/**
 * Tempdoc 560 §28 — harden the realm exactly once, before any Compartment is constructed. lockdown()
 * freezes the shared primordials so an evaluated plugin cannot tamper with intrinsics the host or
 * other plugins rely on — the SES guarantee a bare Compartment lacks (see the class note above).
 * Called from the single SES chokepoint so the freeze lands before the first Compartment, yet stays
 * code-split and only fires once an untrusted plugin is actually loaded (after app chrome is up,
 * dodging the boot-time Lit/Vite prototype-mutation risk the staged plan flagged).
 *
 * Skipped under vitest: lockdown freezes Date, which is incompatible with vitest fake timers /
 * happy-dom (the reason substrate-lockdown.test.ts is an isolated file that calls lockdown directly).
 * The main suite reaches this path via PluginLoader tests, so it must stay a no-op there. Idempotent:
 * SES installs `globalThis.harden` only post-lockdown, so its presence guards against a double call.
 */
function hardenRealmOnce(): void {
  const mode = (import.meta as ImportMeta & { env?: { MODE?: string } }).env?.MODE;
  if (mode === 'test') return;
  const g = globalThis as unknown as { lockdown?: (options?: unknown) => void; harden?: unknown };
  if (typeof g.lockdown === 'function' && typeof g.harden !== 'function') {
    g.lockdown(LOCKDOWN_OPTIONS);
  }
}

/**
 * Endowments the host explicitly grants to a plugin compartment.
 * V1.5.1 (slice 477 H2.1) expands the shape from the V1.5 alpha
 * scaffold to the actual production endowment set:
 *
 *   - `host`: the PluginHostApi (slice 3a.1 Phase 7); plugin's
 *     entry point uses this to register surface-port handlers
 *     and any other host-mediated capability.
 *   - `console`: host-provided logger (typically prefix-wrapped)
 *     so plugin diagnostic output is attributed.
 *   - `customElements`: the browser's CustomElementRegistry. Plugins
 *     can register `<plugin-tag>` elements in the registry. Without
 *     this endowment, plugins cannot define elements (which is the
 *     correct default for trust-tier-attenuated bundles per 478 §4.C).
 *   - `HTMLElement`: the host's HTMLElement constructor. Plugin
 *     classes extend this; cross-realm class inheritance is a
 *     supported pattern (the host's customElements registry accepts
 *     class chains rooted at the host's HTMLElement).
 *   - `localStorage`: same-origin localStorage. UNTRUSTED_PLUGIN
 *     bundles attenuate this to a scoped key-value store (V1.5.1
 *     polish; V1.5 alpha endows the raw global).
 *   - `document`: the host's document. Plugins generally avoid this
 *     in favor of returning DOM via Surface mount; included for
 *     compatibility with V1.5 alpha plugins that touched the DOM
 *     at registration time. V1.5.2+ removes this once factory-shape
 *     plugins are the norm.
 *   - `setTimeout` / `clearTimeout` / `setInterval` / `clearInterval`:
 *     timer primitives. SES Compartments don't include these by
 *     default; plugins that schedule work need them endowed.
 *   - `Lit` (optional): if the plugin's `consumes` declares Lit
 *     primitives, the bundle includes `LitElement`, `html`, `css`,
 *     `nothing`. V1.5.1 plugins that don't consume Lit don't get it.
 *
 * Trust-tier attenuation (slice 467 / 477 H2.4) refines this map
 * per tier; CORE/TRUSTED_PLUGIN get the full bundle, UNTRUSTED_PLUGIN
 * gets a scoped subset.
 */
export interface PluginEndowments {
  host: unknown;
  console?: unknown;
  customElements?: unknown;
  HTMLElement?: unknown;
  localStorage?: unknown;
  document?: unknown;
  setTimeout?: unknown;
  clearTimeout?: unknown;
  setInterval?: unknown;
  clearInterval?: unknown;
  // Lit primitives (optional, per `consumes` declaration)
  LitElement?: unknown;
  html?: unknown;
  css?: unknown;
  nothing?: unknown;
  [key: string]: unknown;
}

/**
 * Construct a Compartment for a plugin.
 *
 * @param endowments objects/functions the plugin's globalThis will
 *   expose. The plugin sees ONLY these names; everything else
 *   resolves to ReferenceError.
 * @returns a fresh Compartment ready to evaluate plugin source.
 *
 * The returned Compartment exposes the standard ECMAScript
 * intrinsics (Array, Object, Promise, etc.) plus the explicitly-
 * endowed names. It does NOT expose DOM globals, `fetch`,
 * `setTimeout`, etc.
 */
export function createPluginCompartment(
  endowments: PluginEndowments,
  kernelModules?: Record<string, { namespace: Record<string, unknown> }>,
): Compartment {
  // SES adds Compartment to globalThis as a side effect of `import 'ses'`.
  // The constructor signature: new Compartment(endowments, modules, options).
  // Tempdoc 560 §4.2: the module map is the @kernel/* capability boundary — a
  // resolver-time mediated map (built by resolveKernelModules) that substitutes
  // the trust-attenuated capability per specifier. A module-mode plugin's
  // `import { data } from '@kernel/data'` resolves through it; passing {} keeps
  // the legacy script-mode (endowment-only) plugins unchanged.
  return new Compartment(endowments, kernelModules ?? {});
}

/**
 * Test helper: returns true if SES has been imported and the
 * Compartment global is available. V1.5 alpha doesn't require
 * lockdown to be called, so this is the only meaningful "is SES
 * ready" check.
 */
export function isSesAvailable(): boolean {
  return typeof Compartment !== 'undefined';
}

/**
 * Slice 477 H2.1 — default endowment bundle for plugins evaluated
 * by the V1.5.1 PluginLoader.
 *
 * The bundle includes the browser globals plugins typically need
 * to define custom elements, persist data, and interact with the
 * DOM. The host API (`PluginHostApi`) is NOT included here —
 * `register(host)` callback receives it via parameter when the
 * registry installs the manifest. Mixing register-time host calls
 * into the factory's endowments would conflate validation order
 * (manifest must be validated before host calls).
 *
 * V1.5.1 alpha intentionally endows the raw browser globals (no
 * scoped wrappers). 477 H2.4 / 478 §4.C add per-tier attenuation:
 * UNTRUSTED_PLUGIN bundles will substitute a scoped `localStorage`
 * (per-plugin namespace), a `customElements` proxy that enforces
 * tag-namespace prefix, etc. The default bundle here represents
 * the CORE / TRUSTED_PLUGIN endowment set; UNTRUSTED-tier
 * attenuation is applied by `buildCapabilityBundle()` per slice 467.
 */
export function buildDefaultEndowments(
  host: unknown = null,
): PluginEndowments {
  // Reach for browser globals through `globalThis` so this works in
  // both happy-dom (test) and real browser environments.
  const g = globalThis as unknown as Record<string, unknown>;
  return {
    host,
    console: g['console'],
    customElements: g['customElements'],
    HTMLElement: g['HTMLElement'],
    localStorage: g['localStorage'],
    document: g['document'],
    setTimeout: g['setTimeout'],
    clearTimeout: g['clearTimeout'],
    setInterval: g['setInterval'],
    clearInterval: g['clearInterval'],
    // V1.5.1 alpha: include Lit primitives unconditionally. V1.5.2+
    // gates these on the manifest's `consumes` declaration once
    // the per-Surface Compartment design (478 §4.C) ships.
    // Lit is imported at host load; reference via globalThis-style
    // lookup would fail. Callers can splice these in via
    // `endowmentsExtension` if their plugin needs them; default
    // bundle leaves Lit out to avoid forcing every plugin to
    // depend on it at the realm boundary.
  };
}
