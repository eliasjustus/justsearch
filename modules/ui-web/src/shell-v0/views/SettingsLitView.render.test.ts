// @vitest-environment happy-dom
/**
 * Render tests for SettingsLitView (slice 3a-2-b).
 */
import { describe, it, expect, vi } from 'vitest';
import './SettingsLitView';
import type { SettingsLitView, SettingsChangeEventDetail } from './SettingsLitView';

async function nextRender(): Promise<void> {
  // Allow Lit's async update cycle to settle.
  await new Promise(resolve => setTimeout(resolve, 0));
}

function createView(data: Partial<SettingsLitView['data']> = {}): SettingsLitView {
  const el = document.createElement('jf-settings-view') as SettingsLitView;
  el.data = data;
  document.body.appendChild(el);
  return el;
}

describe('SettingsLitView', () => {
  it('renders three panels (Interface, Appearance, Keyboard)', async () => {
    const view = createView();
    await view.updateComplete;

    const sections = view.shadowRoot?.querySelectorAll('section.panel');
    expect(sections?.length).toBe(3);
    const titles = Array.from(view.shadowRoot?.querySelectorAll('.panel-title') ?? []).map(
      el => el.textContent?.trim(),
    );
    expect(titles).toEqual(['Interface', 'Appearance', 'Keyboard']);
  });

  it('mounts a jf-form per panel', async () => {
    const view = createView({ mode: 'simple', theme: 'dark', highContrast: true });
    await view.updateComplete;

    const forms = view.shadowRoot?.querySelectorAll('jf-form');
    expect(forms?.length).toBe(3);
  });

  it('emits settings-change with panel discriminator + partial update on form-change', async () => {
    const view = createView({ mode: 'simple' });
    await view.updateComplete;

    const handler = vi.fn();
    view.addEventListener('settings-change', handler);

    // Fire a synthetic form-change for the interface panel.
    const interfaceForm = view.shadowRoot?.querySelector('section[aria-label="Interface"] jf-form');
    interfaceForm?.dispatchEvent(
      new CustomEvent('form-change', {
        detail: { data: { mode: 'advanced' }, path: '/mode', value: 'advanced' },
        bubbles: true,
        composed: true,
      }),
    );
    await nextRender();

    expect(handler).toHaveBeenCalledTimes(1);
    const detail = (handler.mock.calls[0]![0] as CustomEvent<SettingsChangeEventDetail>).detail;
    expect(detail.panel).toBe('interface');
    expect(detail.update).toEqual({ mode: 'advanced' });
  });

  it('appearance panel projects both theme + highContrast', async () => {
    const view = createView({ theme: 'system', highContrast: false });
    await view.updateComplete;

    const handler = vi.fn();
    view.addEventListener('settings-change', handler);

    const appearanceForm = view.shadowRoot?.querySelector('section[aria-label="Appearance"] jf-form');
    appearanceForm?.dispatchEvent(
      new CustomEvent('form-change', {
        detail: { data: { theme: 'dark', highContrast: true }, path: '/theme', value: 'dark' },
        bubbles: true,
        composed: true,
      }),
    );
    await nextRender();

    const detail = (handler.mock.calls[0]![0] as CustomEvent<SettingsChangeEventDetail>).detail;
    expect(detail.panel).toBe('appearance');
    expect(detail.update.theme).toBe('dark');
    expect(detail.update.highContrast).toBe(true);
  });

  it('keyboard panel projects defaultAction', async () => {
    const view = createView({ defaultAction: 'open' });
    await view.updateComplete;

    const handler = vi.fn();
    view.addEventListener('settings-change', handler);

    const keyboardForm = view.shadowRoot?.querySelector('section[aria-label="Keyboard"] jf-form');
    keyboardForm?.dispatchEvent(
      new CustomEvent('form-change', {
        detail: { data: { defaultAction: 'preview' }, path: '/defaultAction', value: 'preview' },
        bubbles: true,
        composed: true,
      }),
    );
    await nextRender();

    const detail = (handler.mock.calls[0]![0] as CustomEvent<SettingsChangeEventDetail>).detail;
    expect(detail.panel).toBe('keyboard');
    expect(detail.update.defaultAction).toBe('preview');
  });

  it('reflects updated data on re-render', async () => {
    const view = createView({ mode: 'simple' });
    await view.updateComplete;

    view.data = { mode: 'advanced', theme: 'light' };
    await view.updateComplete;

    // The form for the interface panel should now have data.mode = 'advanced'.
    const interfaceForm = view.shadowRoot?.querySelector('section[aria-label="Interface"] jf-form') as { data?: Record<string, unknown> } | null;
    expect(interfaceForm?.data?.mode).toBe('advanced');

    const appearanceForm = view.shadowRoot?.querySelector('section[aria-label="Appearance"] jf-form') as { data?: Record<string, unknown> } | null;
    expect(appearanceForm?.data?.theme).toBe('light');
  });

  it('does NOT render Desktop panel when autostart is undefined', async () => {
    const view = createView({ mode: 'simple' });
    await view.updateComplete;
    const desktop = view.shadowRoot?.querySelector('section[aria-label="Desktop"]');
    expect(desktop).toBeNull();
  });

  it('renders Desktop panel when autostart is defined', async () => {
    const view = createView({ autostart: false });
    await view.updateComplete;
    const desktop = view.shadowRoot?.querySelector('section[aria-label="Desktop"]');
    expect(desktop).not.toBeNull();
    const desktopForm = desktop?.querySelector('jf-form') as { data?: Record<string, unknown> } | null;
    expect(desktopForm?.data?.autostart).toBe(false);
  });

  it('Desktop panel projects autostart change with panel=desktop', async () => {
    const view = createView({ autostart: false });
    await view.updateComplete;

    const handler = vi.fn();
    view.addEventListener('settings-change', handler);

    const desktopForm = view.shadowRoot?.querySelector('section[aria-label="Desktop"] jf-form');
    desktopForm?.dispatchEvent(
      new CustomEvent('form-change', {
        detail: { data: { autostart: true }, path: '/autostart', value: true },
        bubbles: true,
        composed: true,
      }),
    );
    await nextRender();

    const detail = (handler.mock.calls[0]![0] as CustomEvent<SettingsChangeEventDetail>).detail;
    expect(detail.panel).toBe('desktop');
    expect(detail.update.autostart).toBe(true);
  });

  it('Desktop panel reflects autostart toggle from undefined to defined', async () => {
    const view = createView();
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('section[aria-label="Desktop"]')).toBeNull();

    view.data = { autostart: true };
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('section[aria-label="Desktop"]')).not.toBeNull();
  });
});
