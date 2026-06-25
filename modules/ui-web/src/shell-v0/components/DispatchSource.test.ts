// @vitest-environment happy-dom
import { describe, it, expect, vi, afterEach } from 'vitest';
import './DispatchSource.js';
import { DispatchSource, type DispatchSourceData } from './DispatchSource.js';

function make(
  provenance: DispatchSourceData | null,
  detailed = false,
): DispatchSource {
  const el = document.createElement('jf-dispatch-source') as DispatchSource;
  el.provenance = provenance;
  el.detailed = detailed;
  document.body.appendChild(el);
  return el;
}

const SAMPLE: DispatchSourceData = {
  transport: 'BUTTON',
  executor: 'UI',
  initiator: 'user@local',
  occurredAt: '2026-05-15T10:00:00Z',
};

describe('DispatchSource', () => {
  afterEach(() => {
    document.body.querySelectorAll('jf-dispatch-source').forEach((el) => el.remove());
  });

  it('renders nothing when provenance is null', async () => {
    const el = make(null);
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.badge')).toBeNull();
  });

  it('compact mode renders icon + label', async () => {
    const el = make(SAMPLE);
    await el.updateComplete;
    const badge = el.shadowRoot?.querySelector('.badge');
    expect(badge).not.toBeNull();
    const icon = el.shadowRoot?.querySelector('.icon');
    expect(icon?.textContent).toBe('🖱');
    const label = el.shadowRoot?.querySelector('.label');
    expect(label?.textContent).toBe('Button');
  });

  it('compact mode has tooltip with full provenance', async () => {
    const el = make(SAMPLE);
    await el.updateComplete;
    const badge = el.shadowRoot?.querySelector('.badge') as HTMLElement;
    expect(badge?.title).toContain('Transport: BUTTON');
    expect(badge?.title).toContain('Executor: UI');
    expect(badge?.title).toContain('Initiator: user@local');
  });

  it('detailed mode renders executor + initiator + time', async () => {
    const el = make(SAMPLE, true);
    await el.updateComplete;
    const fields = el.shadowRoot?.querySelectorAll('.detail-field');
    expect(fields?.length).toBeGreaterThanOrEqual(2);
    const texts = [...(fields ?? [])].map((f) => f.textContent);
    expect(texts).toContain('UI');
    expect(texts.some((t) => t?.includes('user@local'))).toBe(true);
  });

  it('detailed mode omits initiator when null', async () => {
    const el = make({ ...SAMPLE, initiator: null }, true);
    await el.updateComplete;
    const text = el.shadowRoot?.textContent ?? '';
    expect(text).not.toContain('user@local');
    expect(text).toContain('UI');
  });

  it('unknown transport renders with fallback icon', async () => {
    const spy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const el = make({ ...SAMPLE, transport: 'MYSTERY_TRANSPORT' });
    await el.updateComplete;
    const icon = el.shadowRoot?.querySelector('.icon');
    expect(icon?.textContent).toBe('❓');
    spy.mockRestore();
  });

  it('reflects detailed attribute to host', async () => {
    const el = make(SAMPLE, true);
    await el.updateComplete;
    expect(el.hasAttribute('detailed')).toBe(true);
  });

  it('each known transport renders a distinct icon', async () => {
    const transports = [
      'URL_BAR', 'BUTTON', 'AGENT_LOOP', 'MCP', 'LLM_EMISSION',
      'PALETTE', 'RAIL', 'SYSTEM_INTERNAL', 'SCHEDULED', 'RULE_ENGINE',
    ];
    const icons = new Set<string>();
    for (const t of transports) {
      const el = make({ ...SAMPLE, transport: t });
      await el.updateComplete;
      const icon = el.shadowRoot?.querySelector('.icon')?.textContent ?? '';
      icons.add(icon);
      el.remove();
    }
    expect(icons.size).toBeGreaterThanOrEqual(7);
  });
});
