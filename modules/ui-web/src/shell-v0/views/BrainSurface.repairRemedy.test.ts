// @vitest-environment happy-dom

/**
 * Tempdoc 806 B.2 (round-12): ONE condition, ONE named remedy.
 *
 * The Brain SIMPLE panel tells the user "A required component is missing — use Repair in Advanced"
 * for `installStatus.repairNeeded`. The Advanced panel it sends them to presented **Install** as the
 * primary CTA for that same condition, with Repair as an unemphasised sibling — the user follows a
 * named instruction and the destination names a different action.
 *
 * These assertions read the rendered buttons, not a helper, because the defect was which button
 * looked primary on the screen the user was sent to.
 */

import { describe, expect, it } from 'vitest';
import './BrainSurface';
import type { InstallStatus } from '../state/aiStateStore.js';

interface BrainHost extends HTMLElement {
  apiBase: string;
  settings: { mode?: 'simple' | 'advanced' };
  installStatus: InstallStatus | null;
  expanded: Record<string, boolean>;
  requestUpdate(): void;
  updateComplete: Promise<boolean>;
}

/** Mounts Advanced with the AI-install accordion open and returns the install-section buttons. */
async function installButtons(
  installStatus: InstallStatus,
): Promise<{ variants: Record<string, string | null>; text: string }> {
  const el = document.createElement('jf-brain-surface') as BrainHost;
  el.apiBase = '';
  el.settings = { mode: 'advanced' };
  document.body.appendChild(el);
  await el.updateComplete;
  el.installStatus = installStatus;
  el.expanded = { install: true };
  el.requestUpdate();
  await el.updateComplete;
  const variants: Record<string, string | null> = {};
  for (const b of el.shadowRoot?.querySelectorAll('jf-button') ?? []) {
    const label = b.getAttribute('label');
    if (label === 'Install' || label === 'Repair' || label === 'Cancel') {
      variants[label] = b.getAttribute('variant');
    }
  }
  const text = el.shadowRoot?.textContent ?? '';
  document.body.removeChild(el);
  return { variants, text };
}

const REPAIR_NEEDED: InstallStatus = {
  state: 'idle',
  phase: 'idle',
  installedFully: true,
  repairNeeded: true,
} as InstallStatus;

const HEALTHY: InstallStatus = {
  state: 'idle',
  phase: 'idle',
  installedFully: true,
} as InstallStatus;

describe('BrainSurface Advanced — the install panel names the same remedy Simple points at', () => {
  it('repairNeeded ⇒ Repair is the primary affordance, Install is not', async () => {
    const { variants, text } = await installButtons(REPAIR_NEEDED);
    expect(variants.Repair).toBe('primary');
    expect(variants.Install).not.toBe('primary');
    // The panel states the condition in the same words Simple used, so the two read as one story.
    expect(text).toContain('A required component is missing');
    expect(text).toContain('use Repair');
  });

  it('no repair needed ⇒ Install stays primary (the pre-existing default is untouched)', async () => {
    const { variants, text } = await installButtons(HEALTHY);
    expect(variants.Install).toBe('primary');
    expect(variants.Repair).not.toBe('primary');
    expect(text).not.toContain('A required component is missing');
  });
});
