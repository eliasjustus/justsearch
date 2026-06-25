/**
 * @vitest-environment happy-dom
 *
 * Slice 491 §9.D Phase E (C0/C2) — ChatShapeMount tests.
 *
 * Validates the shape-id → view-factory resolution + mounted-element render
 * path. Uses a stub view factory registered against a test shape ref.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import './ChatShapeMount.js';
import { mountView, type ViewFactory } from '../../router/view-factory.js';
import {
  __resetViewFactoryRegistryForTest,
  __setViewFactoryForTest,
  registerViewFactory,
} from '../../router/viewFactoryRegistry.js';

class FakeMountedView extends HTMLElement {
  static observedAttributes = ['api-base'];
}

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

beforeEach(() => {
  __resetViewFactoryRegistryForTest();
  if (!customElements.get('jf-fake-mounted-view')) {
    customElements.define('jf-fake-mounted-view', FakeMountedView);
  }
});

afterEach(() => {
  __resetViewFactoryRegistryForTest();
});

describe('ChatShapeMount', () => {
  it('renders no-shape-id error when shape-id attribute is missing', async () => {
    const el = document.createElement('jf-chat-shape-mount');
    document.body.appendChild(el);
    await settle(el);
    const text = el.shadowRoot?.textContent ?? '';
    expect(text).toContain('no');
    expect(text).toContain('shape-id');
    el.remove();
  });

  it('renders no-factory error when shape-id has no registered factory', async () => {
    const el = document.createElement('jf-chat-shape-mount');
    el.setAttribute('shape-id', 'core.unregistered-shape');
    document.body.appendChild(el);
    await settle(el);
    const text = el.shadowRoot?.textContent ?? '';
    expect(text).toContain('no view factory registered');
    expect(text).toContain('core.unregistered-shape');
    el.remove();
  });

  it('mounts the registered view factory for a known shape-id', async () => {
    registerViewFactory('core.fake-shape', 'jf-fake-mounted-view');
    const el = document.createElement('jf-chat-shape-mount');
    el.setAttribute('shape-id', 'core.fake-shape');
    el.setAttribute('api-base', 'http://test');
    document.body.appendChild(el);
    await settle(el);
    // The mounted view is rendered inside the mount element.
    const mounted = el.shadowRoot?.querySelector('jf-fake-mounted-view');
    expect(mounted).toBeTruthy();
    expect(mounted?.getAttribute('api-base')).toBe('http://test');
    el.remove();
  });

  // 507/508-merge T2.4 — host_ forwarding. Setting host_ on the
  // wrapper as a property must propagate to the inner mounted view
  // via mountView's opts. Without this, FreeChatView / AgentView
  // declared host_ as a property but the parent never assigned it,
  // so the inner view fell back to direct imports.
  it('forwards host_ from the wrapper to the inner mounted view', async () => {
    registerViewFactory('core.fake-shape', 'jf-fake-mounted-view');
    const el = document.createElement('jf-chat-shape-mount') as HTMLElement & {
      host_?: unknown;
      shapeId?: string;
      apiBase?: string;
    };
    const sentinel = { whoami: 'fake-host' };
    el.host_ = sentinel;
    el.shapeId = 'core.fake-shape';
    el.apiBase = 'http://test';
    document.body.appendChild(el);
    await settle(el);
    const mounted = el.shadowRoot?.querySelector('jf-fake-mounted-view') as
      | (HTMLElement & { host_?: unknown })
      | null;
    expect(mounted).toBeTruthy();
    expect(mounted?.host_).toBe(sentinel);
    el.remove();
  });

  // Slice 491 F3 — runtime brand verification fires at the mount dispatch site
  // (ChatShapeMount.tryMount → mountView), not just at registration. A factory
  // that steals __viewBrand from a real factory but isn't in the module-private
  // VALID_VIEW_FACTORIES WeakSet must be rejected.
  it('rejects a forged factory whose __viewBrand was stolen from a real factory', async () => {
    const real = registerViewFactory('core.real-shape', 'jf-fake-mounted-view');
    // Forge: keep the stolen brand Symbol but use a brand-new object reference
    // that was never registered in VALID_VIEW_FACTORIES.
    const forged = {
      __viewBrand: real.__viewBrand,
      shapeRef: 'core.forged-shape',
      mount: () => {
        const evilEl = document.createElement('div');
        evilEl.setAttribute('data-evil', '1');
        return evilEl;
      },
    } as unknown as ViewFactory<'core.forged-shape'>;
    // mountView called directly should throw the WeakSet-membership error.
    expect(() => mountView(forged, { apiBase: 'http://test' })).toThrowError(
      /not in the catalog WeakSet/i,
    );
    // And the same goes for the ChatShapeMount dispatch path that uses mountView:
    // inject the forged factory directly into the registry (test helper).
    __setViewFactoryForTest(
      'core.forged-shape',
      forged as unknown as ViewFactory<import('../../../api/types/conversation-shape.js').ConversationShapeRef>,
    );
    const el = document.createElement('jf-chat-shape-mount');
    el.setAttribute('shape-id', 'core.forged-shape');
    document.body.appendChild(el);
    await settle(el);
    // The forged mount() never ran — no evil div, no mounted child.
    const evil = el.shadowRoot?.querySelector('[data-evil="1"]');
    expect(evil).toBeFalsy();
    el.remove();
  });
});
