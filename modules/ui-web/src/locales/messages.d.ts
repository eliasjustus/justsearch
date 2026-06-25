// SPDX-License-Identifier: Apache-2.0
// Type declaration for the compiled Lingui catalogs. Lingui's `compile`
// command (compileNamespace: "es") emits `.mjs` files; this declaration
// lets TypeScript resolve them with the correct `Messages` shape instead
// of `any`.
//
// Do NOT add a corresponding `.ts` catalog — Lingui's compile command
// does not regenerate it, so it would drift relative to `.po` and
// silently ship broken labels in production builds. See tempdoc 374
// §"Lingui catalog drift" for the original bug.

declare module "*/locales/en/messages.mjs" {
  import type { Messages } from "@lingui/core";
  export const messages: Messages;
}

declare module "*/locales/de/messages.mjs" {
  import type { Messages } from "@lingui/core";
  export const messages: Messages;
}
