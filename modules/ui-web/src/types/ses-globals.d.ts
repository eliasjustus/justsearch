// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 477 H2.7 — TS declarations for SES globals injected by
 * `import 'ses'`.
 *
 * Without this, TypeScript reports `lockdown` and `Compartment` as
 * undefined in any TS source that calls them. (`Compartment` was
 * already used in `PluginCompartment.ts` via a type-only fallback;
 * this declaration makes the global available consistently.)
 *
 * The actual runtime types come from `node_modules/ses/types.d.ts`
 * which is package-scoped — these declarations make `lockdown` /
 * `Compartment` reachable as ambient globals matching SES's own
 * type signatures.
 *
 * Reference: https://hardenedjs.org/ + node_modules/ses/types.d.ts
 */

import type { LockdownOptions, Compartment as SesCompartment } from 'ses';

declare global {
  /**
   * SES `lockdown()`. Available after `import 'ses'`. Calling this
   * freezes the JavaScript intrinsics globally; once called it cannot
   * be undone (the realm transitions to a "hardened" state).
   *
   * In V1.5 alpha this is opt-in via `?lockdown=1` URL param or
   * `VITE_SES_LOCKDOWN=1` env var (per `main.jsx`); V1.5.1 polish
   * makes it default-on after cross-platform Tauri verification.
   */
  function lockdown(options?: LockdownOptions): void;

  /**
   * SES `Compartment` constructor. Available after `import 'ses'`.
   * Each Compartment is a separate JavaScript realm with its own
   * globalThis; the host explicitly endows what the plugin sees.
   */
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const Compartment: typeof SesCompartment;
}

export {};
