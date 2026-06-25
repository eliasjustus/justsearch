/**
 * @justsearch/plugin-api — V1.5.1 plugin authoring SDK.
 *
 * This is the published-package face of the V1.5 plugin contract.
 * The types here mirror the monorepo source at
 * `modules/ui-web/src/shell-v0/plugin-api/`. V1.5.1 alpha syncs
 * via `npm run sync-types`; V1.5.2 wires automated type
 * propagation in CI.
 *
 * Plugin authors use the types from this package to get full
 * editor support when authoring TypeScript plugins. The compiled
 * JS is then wrapped in the V1.5.1 factory expression for
 * Compartment.evaluate (see docs/how-to/write-a-plugin.md).
 *
 * The V1.5 contract is documented at:
 * https://github.com/eliasjustus/JustSearch/blob/main/docs/how-to/write-a-plugin.md
 */

export * from './plugin-types.js';
export * from './trust-types.js';
