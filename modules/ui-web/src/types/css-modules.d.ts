// SPDX-License-Identifier: Apache-2.0
/**
 * Ambient module declaration for side-effect `.css` imports
 * (e.g. `import './themes/default.css';`).
 *
 * TypeScript 6.0's stricter side-effect-import resolution (TS2882)
 * now requires a module/type declaration for any import specifier
 * it cannot resolve on disk. Deliberately scoped to `*.css` only —
 * this project does not pull in `vite/client`'s full ambient type
 * set (see the `import.meta.env` cast in
 * `shell-v0/plugin-api/dev-fixtures.ts`), so this declares just the
 * CSS side-effect-import shape Vite provides, without introducing
 * `ImportMetaEnv`/`ImportMeta` globals into the rest of the module.
 */
declare module '*.css' {}
