// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 3a.2 visual verification surface: the Lit Health view mounted
 * as a single pane in the V0 Shell. Bypasses the React app via
 * `?lit-health=1` so the substrate can be observed in isolation
 * against a real backend without React's connection-state machine
 * in the way.
 *
 * Usage:
 *   cd modules/ui-web && npm run dev
 *   # http://localhost:<port>/?lit-health=1
 *
 * This is **not** the production migration path. The production
 * migration embeds `<jf-health-view>` directly into the existing
 * React `HealthView` (slice 3a.2 §B.B). This file is the debug
 * surface used during the slice's visual-verification step and is
 * preserved for future slices that need the same isolation
 * (substrate change validation, SSE wire-format probing, etc.).
 */

import { Shell } from '../shell/Shell.js';
import '../views/HealthLitView.js';
import '../themes/default.css';
import type { HealthLitView } from '../views/HealthLitView.js';

export function mountHealthLitDemo(host: HTMLElement): Shell {
  document.documentElement.style.height = '100%';
  document.body.style.height = '100%';
  document.body.style.margin = '0';
  host.style.position = 'absolute';
  host.style.inset = '0';
  host.style.padding = '0';
  host.style.margin = '0';

  const shell = new Shell();

  const healthView = document.createElement('jf-health-view') as HealthLitView;
  // The Lit Health view's URL composition uses relative paths
  // (same-origin via Vite's `/api` proxy). The apiBase property is
  // retained on the element for non-stream consumers.
  healthView.apiBase = window.location.origin;

  shell.addPane({
    id: 'health',
    title: 'System Health',
    content: healthView,
    closable: false,
  });

  shell.attachTo(host);
  return shell;
}
