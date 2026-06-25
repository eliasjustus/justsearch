// SPDX-License-Identifier: Apache-2.0
/**
 * Canonical first-party ContributionManifest — proves the §13.2.3
 * end-to-end path on main.
 *
 * Demonstrates declarative registration: instead of imperative
 * `registerAction(...)` calls, the feature ships a single typed
 * manifest, the kernel installs it atomically, and the Action
 * substrate exposes the registered entry through `getAction` /
 * `listActions`.
 *
 * Installed at boot from Shell.ts. The manifest's `provenance` flows
 * through to every contained Action / StatusBarItem / etc. — no
 * per-entry provenance plumbing required.
 */

import { installContributionManifest } from './index.js';
import { makeCoreProvenance } from '../../primitives/provenance.js';

const CORE_PROVENANCE_FOR_DEMO = makeCoreProvenance();

let installed = false;

/** Install the canonical demo manifest. Idempotent. */
export async function installCoreDemoManifest(): Promise<void> {
  if (installed) return;
  installed = true;
  await installContributionManifest({
    id: 'core.substrate-demo',
    version: '1.0.0',
    provenance: CORE_PROVENANCE_FOR_DEMO,
    contributes: {
      actions: [
        {
          id: 'core.action.demo-echo',
          title: 'Demo Echo Action',
          category: 'demo',
          appliesTo: null,
          priority: 0,
          handler: (_args, _addressable) => ({
            kind: 'toast' as const,
            message: 'core.substrate-demo manifest installed',
            severity: 'info' as const,
          }),
        },
      ],
    },
  });
}

/** Test-only reset. */
export function __resetCanonicalManifestInstalledForTest(): void {
  installed = false;
}

// ---------------------------------------------------------------------------
// 548 §4.3(d) — the ContributionManifest as the canonical declaration root for
// a REAL first-party feature. The "Welcome to JustSearch" walkthrough (521
// §16.4) used to be declared imperatively inside CorePlugin.register()'s
// returned PluginContribution. It now ships as its own ContributionManifest
// installed at boot: the manifest's single `provenance` flows through the
// `walkthroughs` coordinator (manifest/index.ts) onto the registered entry —
// no per-entry provenance/source plumbing — and uninstall rolls it back
// atomically. This proves the manifest derives provenance + lifecycle for a
// user-visible feature, not just the demo action above.
// ---------------------------------------------------------------------------

/**
 * "Welcome to JustSearch" walkthrough (moved here from CorePlugin as part of
 * the §4.3(d) migration). Four steps cover the primitives a new user needs to
 * discover: command palette, search, library, theme. The last step
 * auto-advances on `onSettingChanged:activeThemeId`.
 */
const CORE_WELCOME_WALKTHROUGH = {
  id: 'welcome',
  title: 'Welcome to JustSearch',
  description: 'A quick tour of the basics.',
  priority: 0,
  steps: [
    {
      id: 'open-palette',
      title: 'Open the command palette',
      body:
        'Press Ctrl+K (or Cmd+K on macOS) to open the command palette. ' +
        'Everything you can do in JustSearch is reachable from here.',
    },
    {
      id: 'run-a-search',
      title: 'Run your first search',
      body:
        'Switch to the Search surface from the left rail and try a query — ' +
        'JustSearch indexes whatever you point it at, so any keyword from ' +
        'your library should produce hits.',
    },
    {
      id: 'open-library',
      title: 'Open the Library',
      body:
        'The Library surface (left rail) is where you add watched roots ' +
        'and manage indexing. Add a folder to see how the indexer reacts.',
    },
    {
      id: 'switch-theme',
      title: 'Try a different theme',
      body:
        'Settings → Theme picks a different color palette. Themes are ' +
        'plugin contributions, so this is also how third-party themes ' +
        'show up once you install them.',
      completionEvent: 'onSettingChanged:activeThemeId',
    },
  ],
} as const;

let walkthroughInstalled = false;

/** Install the core welcome-walkthrough manifest. Idempotent. */
export async function installCoreWalkthroughManifest(): Promise<void> {
  if (walkthroughInstalled) return;
  walkthroughInstalled = true;
  await installContributionManifest({
    id: 'core.core-welcome',
    version: '1.0.0',
    provenance: makeCoreProvenance(),
    contributes: {
      walkthroughs: [CORE_WELCOME_WALKTHROUGH],
    },
  });
}

/** Test-only reset for the walkthrough manifest installer. */
export function __resetCoreWalkthroughManifestInstalledForTest(): void {
  walkthroughInstalled = false;
}
