// @vitest-environment happy-dom

/**
 * Tempdoc 883 D-A.7 / ADR-0047 — the context-window section is REACHABLE and HONEST.
 *
 * Two properties, and both were live failure modes rather than hypotheticals:
 *
 *  1. Reachability. The section's identity is one string repeated in three files that never
 *     reference each other: `context-window` as the register's section key
 *     (`settingsRegister.ts`), as the `sectionRenderers()` dispatch key (`SettingsSurface.ts`), and
 *     as `settings.section.context-window` in the label catalog. A typo in any one yields a
 *     silently blank sub-anchor. (Same join `SettingsSurface.chatWidth.test.ts` pins for its own.)
 *  2. The readout reflects the ENGINE, not the setting. `contextLength` is `0` on virtually every
 *     installation because `0` means auto, so a control that rendered the stored value would show
 *     "0" where the truth is "32,768 (top-rung)". The Auto arm therefore has to read the live AI
 *     snapshot, and the override arm has to post `llm.contextWindow` — the wire name for
 *     `UiSettings.contextLength` (`SettingsController.mergeV2Into`).
 *
 * Harness: the one `SettingsSurface.chatWidth.test.ts` established, plus `activeCategory = 'agent'`
 * (this section lives under AI -> Agent, not the default Appearance category).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import './SettingsSurface.js';
import '../renderers/registry.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __resetThemeStateForTest } from '../state/themeState.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';
import { __resetUserStateForTest } from '../state/UserStateDocument.js';
import { __resetSessionRegistryForTest } from '../plugin-api/sessionRegistry.js';
import { restoreActivePresentationOnBoot } from '../state/presentationState.js';
import { __resetPresentationForTest } from '../state/presentationRuntime.js';
import { __seedForTest, __resetForTest } from '../../i18n/resourceCatalog.js';
import {
  __feedForTest,
  __resetAiStateForTest,
  type InferenceSnapshot,
} from '../state/aiStateStore.js';
import { formatCount } from '../display/format.js';

interface FetchCall {
  readonly path: string;
  readonly method: string | undefined;
  readonly body: string;
}

type MountedSettings = HTMLElement & {
  updateComplete: Promise<unknown>;
  activeCategory: string;
};

async function mountSettings(
  settings: Record<string, unknown>,
  calls: FetchCall[],
): Promise<MountedSettings> {
  const el = document.createElement('jf-settings-surface') as MountedSettings;
  (el as unknown as Record<string, unknown>).host_ = createMockHostApi({
    data: {
      fetch: (path: string, init?: { method?: string; body?: string | object }) => {
        const body = init?.body;
        calls.push({
          path,
          method: init?.method,
          body: typeof body === 'string' ? body : JSON.stringify(body ?? null),
        });
        return Promise.resolve(new Response(JSON.stringify(settings), { status: 200 }));
      },
    },
  });
  document.body.appendChild(el);
  // AI -> Agent, where the register declares this section (Appearance is the default category).
  el.activeCategory = 'agent';
  await new Promise((resolve) => setTimeout(resolve, 0));
  await el.updateComplete;
  return el;
}

function readout(el: MountedSettings): string {
  const anchor = el.shadowRoot!.querySelector('[data-settings-anchor="context-window"]');
  expect(anchor, 'the register key must dispatch to a renderer').toBeTruthy();
  const section = anchor!.querySelector('[data-testid="settings-context-window"]');
  expect(section, 'the renderer must produce the titled section').toBeTruthy();
  return (
    section!.querySelector('[data-testid="context-window-readout"]')!.textContent ?? ''
  )
    .replace(/\s+/g, ' ')
    .trim();
}

function overrideInput(el: MountedSettings): HTMLInputElement {
  return el.shadowRoot!.querySelector('.context-window-override') as HTMLInputElement;
}

/** Seed the shared AI store the way a live poll would, so the readout has an engine to describe. */
function feedEngine(inference: Record<string, unknown>): void {
  __feedForTest({ inference: inference as unknown as InferenceSnapshot });
}

beforeEach(() => {
  __resetUserConfigForTest();
  __resetUserStateForTest();
  __resetThemeStateForTest();
  __resetSessionRegistryForTest();
  __resetPresentationForTest();
  __resetAiStateForTest();
  __resetForTest();
  __seedForTest({
    'settings.related.label': 'Related settings',
    'settings.section.agent-autonomy': 'Agent autonomy',
    'settings.section.context-window': 'Context window',
  });
  restoreActivePresentationOnBoot();
});

afterEach(() => {
  document.body.innerHTML = '';
  __resetPresentationForTest();
  __resetUserStateForTest();
  __resetAiStateForTest();
  __resetForTest();
  vi.restoreAllMocks();
});

describe('SettingsSurface — context window (883 D-A.7 / ADR-0047)', () => {
  it('AUTO: reads the DERIVED window off the running engine, not the stored 0', async () => {
    feedEngine({
      mode: 'online',
      available: true,
      llmContextTokens: 32768,
      contextWindow: { rung: 32768, reason: 'top-rung', slots: 2, kvType: 'q8_0' },
    });
    // contextWindow 0 IS auto — the exact value that would render as "0" if the readout were
    // sourced from the setting instead of the engine.
    const el = await mountSettings({ ui: {}, llm: { contextWindow: 0 } }, []);

    expect(readout(el)).toBe(`Auto → ${formatCount(32768)} tokens (top-rung, 2 slots, q8_0)`);
    // The field shows blank, not "0": blank is how the user says "auto" back to it.
    expect(overrideInput(el).value).toBe('');
  });

  it('AUTO with no engine running: says the window is derived at start, never invents one', async () => {
    feedEngine({ mode: 'offline', available: false });
    const el = await mountSettings({ ui: {}, llm: { contextWindow: 0 } }, []);
    expect(readout(el)).toBe('Auto — derived when the assistant starts');
  });

  it('OVERRIDE: shows the stored value AND what actually launched', async () => {
    // The two can disagree — a stepped-down or refused launch is exactly what the user needs to
    // see next to their own number, so the readout carries both rather than either alone.
    feedEngine({
      mode: 'online',
      available: true,
      llmContextTokens: 8192,
      contextWindow: { rung: 8192, reason: 'override', slots: 2, kvType: 'q8_0' },
    });
    const el = await mountSettings({ ui: {}, llm: { contextWindow: 8192 } }, []);
    expect(readout(el)).toBe(
      `Override ${formatCount(8192)} tokens → ${formatCount(8192)} tokens (override, 2 slots, q8_0)`,
    );
    expect(overrideInput(el).value).toBe('8192');
  });

  it('typing a value POSTs the llm slice — the wire name, not the Java field name', async () => {
    const calls: FetchCall[] = [];
    const el = await mountSettings({ ui: {}, llm: { contextWindow: 0 } }, calls);
    const input = overrideInput(el);
    input.value = '16384';
    input.dispatchEvent(new Event('change'));
    await el.updateComplete;

    const posts = calls.filter((c) => c.method === 'POST' && c.path === '/api/settings/v2');
    expect(posts).toHaveLength(1);
    // `llm.contextWindow` is what SettingsController maps onto UiSettings.contextLength; posting
    // `contextLength` would be accepted as valid JSON and silently ignored.
    expect(JSON.parse(posts[0]!.body)).toEqual({ llm: { contextWindow: 16384 } });
  });

  it('clearing the field posts 0 — auto has to be REACHABLE, not just the initial state', async () => {
    const calls: FetchCall[] = [];
    const el = await mountSettings({ ui: {}, llm: { contextWindow: 16384 } }, calls);
    const input = overrideInput(el);
    input.value = '';
    input.dispatchEvent(new Event('change'));
    await el.updateComplete;

    const posts = calls.filter((c) => c.method === 'POST' && c.path === '/api/settings/v2');
    expect(JSON.parse(posts[0]!.body)).toEqual({ llm: { contextWindow: 0 } });
  });

  it('a sub-512 value is floored client-side, so the field cannot show a number the backend rewrote', async () => {
    const calls: FetchCall[] = [];
    const el = await mountSettings({ ui: {}, llm: { contextWindow: 0 } }, calls);
    const input = overrideInput(el);
    input.value = '100';
    input.dispatchEvent(new Event('change'));
    await el.updateComplete;

    const posts = calls.filter((c) => c.method === 'POST' && c.path === '/api/settings/v2');
    expect(JSON.parse(posts[0]!.body)).toEqual({ llm: { contextWindow: 512 } });
    expect(overrideInput(el).value).toBe('512');
  });

  it('a REJECTED save reverts to the PREVIOUS value — not to the one the backend refused', async () => {
    // The optimistic write is what makes the field responsive (pinned by the floor test above,
    // where the rendered 512 can only have come from the local write); without the revert it is
    // the 806 B.2 defect exactly — a control rendering its own local copy while the backend never
    // changed. Starting from a NON-blank stored value is what separates "reverted" from "never
    // wrote": a missing revert leaves 16,384, a missing write would never have shown it.
    const el = document.createElement('jf-settings-surface') as MountedSettings;
    (el as unknown as Record<string, unknown>).host_ = createMockHostApi({
      data: {
        fetch: (_path: string, init?: { method?: string }) =>
          Promise.resolve(
            init?.method === 'POST'
              ? new Response('{}', { status: 500 })
              : new Response(JSON.stringify({ ui: {}, llm: { contextWindow: 4096 } }), {
                  status: 200,
                }),
          ),
      },
    });
    document.body.appendChild(el);
    el.activeCategory = 'agent';
    await new Promise((resolve) => setTimeout(resolve, 0));
    await el.updateComplete;
    expect(overrideInput(el).value).toBe('4096');

    const input = overrideInput(el);
    input.value = '16384';
    input.dispatchEvent(new Event('change'));
    await new Promise((resolve) => setTimeout(resolve, 0));
    await el.updateComplete;

    expect(overrideInput(el).value).toBe('4096');
    expect(readout(el)).toContain(`Override ${formatCount(4096)} tokens`);
  });
});
