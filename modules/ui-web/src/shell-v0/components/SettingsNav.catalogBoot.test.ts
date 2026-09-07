// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom

/**
 * Round-18 sandbox finding F3 (tempdoc 941) — the Settings window rendered its own i18n keys
 * (`settings.search.placeholder`, `settings.group.general`, `settings.section.theme`, …) in a
 * long-running upgraded shell, while a genuine cold restart was fully localized.
 *
 * `registry-surface` is the ONE namespace every `settings.*` label resolves through
 * (`views/settingsRegister.ts` header), it is fetched exactly once at module evaluation
 * (`src/i18n.ts`), and nothing persists its body — so any single boot fetch that answers with
 * nothing (backend not serving yet) or with the WRONG body (a webview HTTP-cache entry left by
 * the previous install) leaves every `settings.*` key rendering raw for the life of the document.
 *
 * The assertion below is the routed regression home: no rendered Settings label starts with
 * `settings.` once the catalog boot has settled.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import './SettingsNav.js';
import { SETTINGS_REGISTER } from '../views/settingsRegister.js';
import {
  __resetForTest,
  bootSurfaceCatalog,
  localizeResourceKey,
} from '../../i18n/resourceCatalog.js';

const API_BASE = 'http://127.0.0.1:8080';

type NavElement = HTMLElement & {
  register: unknown;
  activeCategory: string;
  activeAnchor: string | null;
  updateComplete: Promise<unknown>;
};

/** Every `settings.*` key the register declares, plus the three the window chrome uses directly. */
function settingsKeys(): string[] {
  const keys: string[] = [
    'settings.search.placeholder',
    'settings.search.no-results',
    'settings.related.label',
  ];
  for (const group of SETTINGS_REGISTER) {
    keys.push(group.labelKey);
    for (const category of group.categories) {
      if (category.labelKey) keys.push(category.labelKey);
      for (const section of category.sections ?? []) keys.push(section.labelKey);
    }
  }
  return keys;
}

/** What `GET /api/messages/registry-surface/en` serves on the CURRENT build. */
function currentCatalogBody(): Record<string, string> {
  const messages = preUpgradeCatalogBody();
  for (const key of settingsKeys()) {
    messages[key] = key.split('.').pop() ?? key;
  }
  return messages;
}

/** The same namespace as a PRE-855 build served it: surface labels, no `settings.*` at all. */
function preUpgradeCatalogBody(): Record<string, string> {
  return {
    'registry-surface.presentation-gallery-surface.label': 'Skins',
    'registry-surface.presentation-editor-surface.label': 'Editor',
  };
}

function catalogResponse(messages: Record<string, string>): unknown {
  return {
    ok: true,
    status: 200,
    json: () => Promise.resolve({ namespace: 'registry-surface', messages }),
  };
}

async function mountNav(): Promise<NavElement> {
  const el = document.createElement('jf-settings-nav') as NavElement;
  el.register = SETTINGS_REGISTER;
  el.activeCategory = 'appearance';
  el.activeAnchor = null;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

/** Every label the nav actually paints: element text nodes plus the user-visible attributes. */
function renderedLabels(el: NavElement): string[] {
  const root = el.shadowRoot;
  if (!root) throw new Error('jf-settings-nav did not attach a shadow root');
  const labels: string[] = [];
  for (const node of Array.from(root.querySelectorAll('*'))) {
    for (const child of Array.from(node.childNodes)) {
      if (child.nodeType === 3) {
        const text = (child.textContent ?? '').trim();
        if (text) labels.push(text);
      }
    }
    for (const attribute of ['placeholder', 'aria-label', 'title']) {
      const value = node.getAttribute(attribute);
      if (value) labels.push(value);
    }
  }
  return labels;
}

function rawKeyLabels(el: NavElement): string[] {
  return renderedLabels(el).filter((label) => label.startsWith('settings.'));
}

describe('Settings i18n namespace survives a hostile catalog boot (941 round-18 F3)', () => {
  let originalFetch: typeof fetch;
  let nav: NavElement | null = null;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
    __resetForTest();
  });

  afterEach(() => {
    vi.useRealTimers();
    globalThis.fetch = originalFetch;
    nav?.remove();
    nav = null;
    __resetForTest();
    vi.clearAllMocks();
  });

  it('recovers the namespace when the boot fetch races a restarting backend', async () => {
    // The shell reloads itself the moment the Rust side reports a new backend instance
    // (`main.jsx` installBackendRestartBridge → window.location.reload()), so the catalog boot
    // starts against a Head that is not answering `/api/messages/**` yet.
    let attempt = 0;
    globalThis.fetch = vi.fn(() => {
      attempt += 1;
      if (attempt === 1) return Promise.reject(new TypeError('Failed to fetch'));
      if (attempt === 2) {
        return Promise.resolve({ ok: false, status: 503, json: () => Promise.resolve({}) });
      }
      return Promise.resolve(catalogResponse(currentCatalogBody()));
    }) as unknown as typeof fetch;

    vi.useFakeTimers();
    const boot = bootSurfaceCatalog(API_BASE);
    nav = await mountNav();

    // The trigger genuinely reproduced: with the namespace still missing the nav paints raw keys.
    expect(rawKeyLabels(nav).length).toBeGreaterThan(0);

    await vi.advanceTimersByTimeAsync(60_000);
    await boot;
    vi.useRealTimers();
    await nav.updateComplete;

    expect(rawKeyLabels(nav)).toEqual([]);
    // The collapsed groups' sections are not painted; assert the whole declared register too.
    expect(settingsKeys().filter((key) => localizeResourceKey(key) === key)).toEqual([]);
  });

  it('refuses a pre-upgrade catalog body served from the webview HTTP cache', async () => {
    // `MessageCatalogController` serves the catalog with `Cache-Control: public, max-age=3600`,
    // and the WebView2 HTTP cache outlives an over-install — so a cache-permissive boot fetch can
    // be answered from the body the PREVIOUS version published, which resolves the keys that
    // version knew (surface labels) and none of the ones it did not (`settings.*`, new in 855).
    // This mock plays that cache: only a request that forces revalidation reaches the live Head.
    globalThis.fetch = vi.fn((_url: string, init?: RequestInit) => {
      const revalidates =
        init?.cache === 'no-cache' || init?.cache === 'reload' || init?.cache === 'no-store';
      return Promise.resolve(
        catalogResponse(revalidates ? currentCatalogBody() : preUpgradeCatalogBody()),
      );
    }) as unknown as typeof fetch;

    await bootSurfaceCatalog(API_BASE);
    nav = await mountNav();

    expect(rawKeyLabels(nav)).toEqual([]);
    // The exact field signature this rules out: older keys of the same namespace resolving fine
    // while every `settings.*` key renders raw.
    expect(localizeResourceKey('registry-surface.presentation-gallery-surface.label')).toBe('Skins');
  });
});
