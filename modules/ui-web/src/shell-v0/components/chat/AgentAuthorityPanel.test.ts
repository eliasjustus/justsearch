// @vitest-environment happy-dom
/**
 * Tempdoc 605 #6 — the Abilities panel must render the tool DESCRIPTION as prose. The backend emits
 * the description as an i18n KEY (`ops.<op>.description`, AgentToolsController contract); the panel
 * resolves it through the same catalog the label uses. Regression: it used to render the raw key.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import './AgentAuthorityPanel.js';
import type { AgentAuthorityPanel } from './AgentAuthorityPanel.js';
import { __seedForTest, __resetForTest } from '../../../i18n/resourceCatalog.js';

let panel: AgentAuthorityPanel;

beforeEach(() => {
  __resetForTest();
  panel = document.createElement('jf-agent-authority-panel') as AgentAuthorityPanel;
  document.body.appendChild(panel);
});

afterEach(() => {
  if (panel.parentNode) panel.remove();
  __resetForTest();
});

async function settle(): Promise<void> {
  await panel.updateComplete;
  await Promise.resolve();
  await panel.updateComplete;
}

describe('AgentAuthorityPanel description resolution (tempdoc 605 #6)', () => {
  it('renders the resolved prose, not the raw ops.* i18n key', async () => {
    __seedForTest({ 'ops.file-operations.description': 'Read, write, move, or delete your files.' });
    panel.tools = [
      { name: 'core.file-operations', description: 'ops.file-operations.description', risk: 'high' },
    ];
    panel.level = 'auto';
    await settle();

    const text = panel.shadowRoot!.textContent ?? '';
    expect(text).toContain('Read, write, move, or delete your files.');
    expect(text).not.toContain('ops.file-operations.description');
  });

  it('falls back to the raw key only when the catalog has no entry (defensive, not the happy path)', async () => {
    // No seed → localizeResourceKey returns the key. Documents the fallback; the fix is that the
    // catalog IS booted in the real app, so this path is the safety net, not the rendered state.
    panel.tools = [
      { name: 'core.x', description: 'ops.x.description', risk: 'low' },
    ];
    panel.level = 'auto';
    await settle();
    expect(panel.shadowRoot!.textContent ?? '').toContain('ops.x.description');
  });
});
