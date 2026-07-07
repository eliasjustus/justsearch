// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest';
import './LivenessReadout.js';
import { LivenessReadout } from './LivenessReadout.js';
import type { AiState } from '../state/aiStateStore.js';

// Only `statusTone` (dot tone) + `statusLabel` (live text) are read by the readout; a partial AiState
// is sufficient to exercise the tone mapping.
function partial(tone: AiState['statusTone'], label = 'x'): AiState {
  return { statusTone: tone, statusLabel: label } as unknown as AiState;
}

async function mount(): Promise<LivenessReadout> {
  const el = document.createElement('jf-liveness-readout') as LivenessReadout;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('LivenessReadout — tempdoc 649: the dot tone follows the verdict-derived statusTone', () => {
  it('maps statusTone → dot tone, so the calm "Catching up…" dot is info (tint), NOT amber degraded', async () => {
    const el = await mount();
    const dotTone = () => el.shadowRoot?.querySelector('.dot')?.getAttribute('data-tone');

    // The fix: the calm in-flux state is `info` (calm tint), not `degraded` (amber).
    el.aiState = partial('info', 'Catching up…');
    await el.updateComplete;
    expect(dotTone()).toBe('info');

    el.aiState = partial('success', 'Online');
    await el.updateComplete;
    expect(dotTone()).toBe('live');

    el.aiState = partial('warning', 'Reconnecting…');
    await el.updateComplete;
    expect(dotTone()).toBe('degraded');

    el.aiState = partial('error', 'Backend disconnected');
    await el.updateComplete;
    expect(dotTone()).toBe('error');

    el.aiState = partial('neutral', 'offline');
    await el.updateComplete;
    expect(dotTone()).toBe('idle');

    el.remove();
  });
});
