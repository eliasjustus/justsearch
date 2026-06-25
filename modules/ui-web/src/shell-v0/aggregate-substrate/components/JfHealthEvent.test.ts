// @vitest-environment happy-dom

/**
 * Tempdoc 511-followup-A — `<jf-health-event>` smoke test.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type {
  HealthEvent,
  LifecycleEvent,
} from '../../../api/generated/index.js';
import { bootstrapAggregateSubstrate, __resetBootstrap } from '../bootstrap';
import { __clearAggregateRegistry } from '../aggregateRegistry';
import { JfHealthEvent } from './JfHealthEvent.js';
import './JfHealthEvent.js';

function sampleEvent(overrides: Partial<HealthEvent> = {}): HealthEvent {
  return {
    id: 'core.event.test',
    timestamp: '2026-05-18T12:00:00Z',
    source: { serviceName: 'head' },
    severity: 'INFO',
    i18nKey: 'health-events.test.message',
    body: { kind: 'lifecycle', attributes: { message: 'baseline' } } as LifecycleEvent,
    ...overrides,
  };
}

async function mountEvent(event: HealthEvent | null): Promise<JfHealthEvent> {
  const el = document.createElement('jf-health-event') as JfHealthEvent;
  el.event = event;
  el.context = 'activity-row';
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('<jf-health-event>', () => {
  beforeEach(() => {
    __clearAggregateRegistry();
    __resetBootstrap();
    bootstrapAggregateSubstrate();
  });
  afterEach(() => {
    document.body.innerHTML = '';
    __clearAggregateRegistry();
    __resetBootstrap();
  });

  it('renders a row when given an event', async () => {
    const el = await mountEvent(sampleEvent());
    const row = el.querySelector('.event-row');
    expect(row).not.toBeNull();
    expect(row?.getAttribute('data-severity')).toBe('INFO');
    expect(row?.getAttribute('data-body-kind')).toBe('lifecycle');
  });

  it('renders nothing when event is null', async () => {
    const el = await mountEvent(null);
    expect(el.querySelector('.event-row')).toBeNull();
  });

  it('reflects severity changes in the CSS class', async () => {
    const el = await mountEvent(sampleEvent({ severity: 'ERROR' }));
    const row = el.querySelector('.event-row');
    expect(row?.classList.contains('error')).toBe(true);
    expect(row?.getAttribute('data-severity')).toBe('ERROR');
  });

  it('renders body-derived message text', async () => {
    const el = await mountEvent(
      sampleEvent({
        body: {
          kind: 'lifecycle',
          attributes: { message: 'specific-text' },
        } as LifecycleEvent,
      }),
    );
    const msg = el.querySelector('.event-message')?.textContent ?? '';
    expect(msg).toContain('specific-text');
  });
});
