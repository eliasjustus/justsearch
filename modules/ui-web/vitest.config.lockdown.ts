/**
 * Slice 477 H2.5 — Vitest config for substrate-under-lockdown tests.
 *
 * Originally H2.5 planned a vitest setup file that ran `lockdown()`
 * before the WHOLE suite. Critical-analysis pivot: lockdown freezes
 * `Date`, which is incompatible with happy-dom's mutable `Date`
 * implementation AND with vitest's fake-timer machinery. Running
 * the full suite under lockdown produced 34 of 66 file failures,
 * none of which were substrate bugs — all vitest internals.
 *
 * Pivot: ship a single substrate-only test file
 * (`src/shell-v0/plugin-api/substrate-lockdown.test.ts`) that
 * imports SES + calls `lockdown()` at the top, then verifies
 * Compartment / PluginLoader / tier attenuation work in the
 * locked-down realm. This config runs ONLY that file.
 *
 * The default `npm run test:unit:run` excludes
 * `**\/*-lockdown.test.ts` so the rest of the suite isn't affected.
 *
 * Future regressions in lockdown compatibility surface here, run
 * via `npm run test:unit:lockdown`.
 */

import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['src/**/*-lockdown.test.{ts,tsx}'],
  },
});
