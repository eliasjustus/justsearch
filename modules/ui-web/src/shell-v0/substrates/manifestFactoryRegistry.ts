// SPDX-License-Identifier: Apache-2.0
/**
 * Leaf registry of profile-scoped manifest factories.
 *
 * Extracted from `profiles/index.ts` to break the manifest↔profiles
 * import cycle (tempdoc 530 UI-cycle gate). Previously `manifest/index.ts`
 * reached the factory registry via a deferred `import('../profiles')`
 * (a dynamic edge madge counts), while `profiles/index.ts` statically
 * imports `manifest/index.ts`. Housing the registry in this leaf — which
 * imports nothing — lets `manifest` register a factory synchronously and
 * `profiles` read it, with neither importing the other for this purpose.
 *
 * The factory is typed `() => unknown` so this leaf needs no type import
 * (which would re-introduce an edge); the callers, which already hold the
 * `ContributionManifest` type, narrow at their own boundary.
 */

const _manifestFactories = new Map<string, () => unknown>();

/** Register a factory that re-instantiates a profile-scoped manifest. */
export function registerProfileScopedManifestFactory(
  id: string,
  factory: () => unknown,
): void {
  _manifestFactories.set(id, factory);
}

/** Unregister a profile-scoped manifest factory (e.g., on uninstall). */
export function unregisterProfileScopedManifestFactory(id: string): boolean {
  return _manifestFactories.delete(id);
}

/** Look up a registered profile-scoped manifest factory, or undefined. */
export function getProfileScopedManifestFactory(
  id: string,
): (() => unknown) | undefined {
  return _manifestFactories.get(id);
}

/** Test-only: clear all registered factories. */
export function __resetProfileScopedManifestFactoriesForTest(): void {
  _manifestFactories.clear();
}
