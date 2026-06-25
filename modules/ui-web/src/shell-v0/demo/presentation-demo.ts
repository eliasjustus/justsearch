// SPDX-License-Identifier: Apache-2.0
/**
 * 569 — visual-verification demo for the user-authored frontend.
 *
 * Mounted by `main.jsx` when the URL carries `?presentation-demo=1`. Renders, with NO backend,
 * the self-contained pieces of the inversion so they can be screenshotted (ui-shot):
 *   1. a REAL surface region rendered from a declaration through the projection engine (Move 1/3);
 *   2. the §9 spike — one declaration driving three maximally-different surface KINDS;
 *   3. the user-authored interaction statechart (Move 8) — state + dispatched named effects;
 *   4. quarantine-to-default — a region whose runtime audit fails reverts to the built-in (Move 6).
 *
 * Run: cd modules/ui-web && npm run dev → http://localhost:<port>/?presentation-demo=1
 */
import '../components/DeclaredSurface.js';
// Renderer set the engine dispatches over (self-register on import).
import '../renderers/controls/TextControl.js';
import '../renderers/controls/NumberControl.js';
import '../renderers/controls/BooleanControl.js';
import '../renderers/controls/EnumControl.js';
import '../renderers/controls/ArrayControl.js';
import '../renderers/controls/ObjectControl.js';
import '../renderers/layouts/VerticalLayout.js';
// 569 Fix 1 — the bespoke-quality renderers the declared Settings body's x-ui-renderer hints resolve to.
import '../renderers/controls/XUiRendererControl.js';
import '../renderers/controls/OptionButtonGroupRenderer.js';
import '../renderers/controls/ToggleSwitchRenderer.js';
import '../renderers/controls/SearchResultsRenderer.js';
import '../renderers/controls/SourceChipsRenderer.js';
import '../themes/default.css';
import type { DeclaredSurface } from '../components/DeclaredSurface.js';
import {
  SETTINGS_INTERFACE_BODY,
  RESULTS_LIST_BODY,
  AGENT_STATUS_BODY,
  APPEARANCE_FLOW,
  LIBRARY_CARDS_BODY,
  CONFIRM_CEREMONY,
  HELP_REFERENCE_BODY,
  HEALTH_STATS_BODY,
} from '../themes/builtinPresentations.js';
import { createMachine, type InteractionStatechart } from '../substrates/interaction/index.js';
import { listJournal } from '../substrates/effects/index.js';
// 569 §14 — the appearance behaviour writer + the Library folder-card renderer (for the live demos).
import { applyAppearance } from '../state/themeState.js';
import '../renderers/controls/FolderCardRenderer.js';
// 569 §15 — the Help + Health renderers (for the live demos of the 3rd/4th surfaces).
import '../renderers/controls/ShortcutsTableRenderer.js';
import '../renderers/controls/ListItemsRenderer.js';
import '../renderers/controls/MetricCardRenderer.js';
// 569 §14 — start the ONE observed-state authority so the demo's liveness readout reflects the REAL
// backend (the store only polls once started; same-origin /api by default, proxied to the backend).
import { startAiStateStore } from '../state/aiStateStore.js';
import { auditAndQuarantine } from '../state/runtimeConformance.js';
import {
  applyPresentationBodies,
  activeBodyFor,
} from '../state/presentationRuntime.js';
import { certifyPresentation, describeConformanceError } from '../themes/conformanceGate.js';
import { applyPresentation } from '../state/presentationState.js';

function h<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  props: Partial<HTMLElementTagNameMap[K]> = {},
  ...children: (Node | string)[]
): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  Object.assign(el, props);
  for (const c of children) el.append(c);
  return el;
}

function section(title: string, ...children: Node[]): HTMLElement {
  return h(
    'section',
    { className: 'demo-section' },
    h('h2', {}, title),
    ...children,
  );
}

function declaredSurface(body: { schema: unknown; uischema: unknown; heading?: string }, data: Record<string, unknown>): DeclaredSurface {
  const el = document.createElement('jf-declared-surface') as DeclaredSurface;
  el.declaration = body as DeclaredSurface['declaration'];
  el.data = data;
  el.enabled = true;
  return el;
}

export function mountPresentationDemo(host: HTMLElement): void {
  document.documentElement.style.height = '100%';
  document.body.style.margin = '0';
  // 569 §14 — start the observed-state authority so the liveness readout (§7) reflects the REAL
  // backend. `?api=<base>` overrides; default same-origin '' (the vite /api proxy → the backend).
  startAiStateStore(new URLSearchParams(location.search).get('api') ?? '');
  host.style.cssText = 'padding:1.5rem;font:14px var(--font-native);color:var(--text-primary);background:var(--surface-1);min-height:100vh;display:flex;flex-direction:column;gap:1.5rem;max-width:760px';

  host.append(h('h1', {}, '569 — the user-authored frontend'));

  // 1. Declaration-driven Settings region.
  host.append(
    section(
      '1 · A real region rendered from a declaration (Move 1/3)',
      declaredSurface(SETTINGS_INTERFACE_BODY, {
        mode: 'advanced',
        theme: 'dark',
        highContrast: false,
        vimMode: true,
        defaultAction: 'open',
      }),
    ),
  );

  // 2. §9 three-surface spike — one declaration, three KINDS.
  host.append(
    section(
      '2 · §9 spike — one declaration, three surface kinds',
      declaredSurface(RESULTS_LIST_BODY, {
        query: 'neural search',
        hits: [
          { title: 'Design doc', path: '/docs/569.md', score: 0.92 },
          { title: 'Spec', path: '/docs/spec.md', score: 0.81 },
        ],
      }),
      declaredSurface(AGENT_STATUS_BODY, {
        status: 'done',
        answer: 'The frontend is a projection of one user-authored declaration.',
        sources: [{ title: 'tempdoc 569', url: '/docs/569' }],
      }),
    ),
  );

  // 3. Interaction statechart (Move 8), driven by the REAL applyEffect dispatcher (Fix 4): the
  // CONFIRM transition fires a real `toast` Effect that is dispatched + recorded in the Effect
  // Journal — proving Move 8 drives the real effect substrate (undo/replay/audit), not a stub.
  // (A demo-local chart with a safe `toast` effect, so no backend op is invoked.)
  const DEMO_CHART: InteractionStatechart = {
    id: 'demo.confirm',
    initial: 'idle',
    states: [
      { id: 'idle', transitions: [{ on: 'REQUEST', target: 'confirming' }] },
      {
        id: 'confirming',
        transitions: [
          { on: 'CONFIRM', target: 'done', guard: 'typed == true', effects: [{ kind: 'toast', message: 'Confirmed', severity: 'success' }] },
          { on: 'CANCEL', target: 'idle' },
        ],
      },
      { id: 'done', transitions: [] },
    ],
  };
  const stateEl = h('strong', { id: 'sc-state' }, 'idle');
  const journalEl = h('div', { id: 'sc-journal', className: 'demo-log' }, 'Effect Journal: 0 real effect(s)');
  const typedBox = h('input', { type: 'checkbox', id: 'sc-typed' }) as HTMLInputElement;
  const machine = createMachine(DEMO_CHART); // default dispatcher = the real applyEffect (journaled)
  const refresh = (): void => {
    stateEl.textContent = machine.state;
    journalEl.textContent = `Effect Journal: ${listJournal().length} real effect(s) dispatched`;
  };
  const sendBtn = (label: string, event: string) =>
    h('button', {
      className: 'demo-btn',
      onclick: () => {
        machine.send(event, { typed: typedBox.checked });
        refresh();
      },
    }, label);
  host.append(
    section(
      '3 · The interaction statechart (Move 8) — states + REAL journaled effects',
      h('div', { className: 'demo-row' },
        h('span', {}, 'state: '), stateEl,
        h('label', { className: 'demo-inline' }, typedBox, ' typed==true (guard)'),
      ),
      h('div', { className: 'demo-row' },
        sendBtn('REQUEST', 'REQUEST'),
        sendBtn('CONFIRM', 'CONFIRM'),
        sendBtn('CANCEL', 'CANCEL'),
      ),
      journalEl,
    ),
  );

  // 4. Quarantine-to-default (Move 6).
  applyPresentationBodies({
    schemaVersion: 1,
    id: 'demo.quarantine',
    displayName: 'Quarantine demo',
    body: { 'demo.region': SETTINGS_INTERFACE_BODY },
  });
  const qStatus = h('strong', { id: 'q-status' }, activeBodyFor('demo.region') ? 'declared (engine)' : 'built-in');
  const qBtn = h('button', {
    className: 'demo-btn',
    onclick: () => {
      // Force a runtime audit failure → the region is quarantined to the built-in render.
      auditAndQuarantine('demo.region', host, () => ['computed contrast 1.3:1 on <span>']);
      qStatus.textContent = activeBodyFor('demo.region') ? 'declared (engine)' : 'built-in (quarantined)';
    },
  }, 'Force runtime contrast failure');
  host.append(
    section(
      '4 · Quarantine-to-default on a failed runtime audit (Move 6)',
      h('div', { className: 'demo-row' }, h('span', {}, 'demo.region renders: '), qStatus),
      qBtn,
    ),
  );

  // 5. Authoring origin + anti-spoof (Move 7 + Move 4/6): apply an authored skin; the trusted
  // channel is unrepresentable — a declaration that tries to mount it is REJECTED by the gate.
  const authMsg = h('strong', { id: 'auth-msg' }, '—');
  const applyValid = h('button', {
    className: 'demo-btn',
    onclick: () => {
      const res = applyPresentationBodies({
        schemaVersion: 1,
        id: 'demo.authored',
        displayName: 'Authored skin',
        body: { 'demo.region': SETTINGS_INTERFACE_BODY },
      });
      authMsg.textContent = res.ok
        ? 'Applied authored skin ✓'
        : `notes: ${res.errors.map(describeConformanceError).join('; ')}`;
    },
  }, 'Apply a valid authored skin');
  const trySpoof = h('button', {
    className: 'demo-btn',
    onclick: () => {
      const { verdict } = certifyPresentation({
        schemaVersion: 1,
        id: 'demo.spoof',
        displayName: 'Spoof',
        layout: { regions: [{ id: 'x', component: 'jf-authorization-host' }] },
      });
      authMsg.textContent = `Rejected (anti-spoof): ${
        verdict.errors[0] ? describeConformanceError(verdict.errors[0]) : 'reserved component'
      }`;
    },
  }, 'Try to mount the trusted dialog');
  host.append(
    section(
      '5 · Authoring origin + anti-spoof (Move 7 + Move 4/6)',
      h('div', { className: 'demo-row' }, applyValid, trySpoof),
      h('div', { className: 'demo-row' }, h('span', {}, 'result: '), authMsg),
    ),
  );

  // 6. In-UI authoring (Move 7): an editor — type/paste a declaration → Certify & apply through the
  // ONE writer → it renders live through the engine (degrade-never-fail; a rejected one shows errors).
  const sample = JSON.stringify(
    {
      schemaVersion: 1,
      id: 'user.my-panel',
      displayName: 'My panel',
      body: {
        'demo.authoring': {
          heading: 'My authored panel',
          schema: {
            type: 'object',
            properties: {
              mode: { type: 'string', enum: ['simple', 'advanced'], 'x-ui-renderer': 'option-button-group' },
              fast: { type: 'boolean', title: 'Fast mode', 'x-ui-renderer': 'toggle-switch' },
            },
          },
          uischema: {
            type: 'VerticalLayout',
            elements: [
              { type: 'Control', scope: '#/properties/mode' },
              { type: 'Control', scope: '#/properties/fast' },
            ],
          },
        },
      },
    },
    null,
    2,
  );
  const editor = h('textarea', { id: 'authoring-editor', className: 'demo-editor' }) as HTMLTextAreaElement;
  editor.value = sample;
  const editorMsg = h('strong', { id: 'authoring-msg' }, '—');
  const declared = document.createElement('jf-declared-surface') as DeclaredSurface;
  declared.data = { mode: 'advanced', fast: true };
  const applyAuthored = h('button', {
    className: 'demo-btn',
    onclick: () => {
      let parsed: unknown;
      try {
        parsed = JSON.parse(editor.value);
      } catch (e) {
        editorMsg.textContent = `Not valid JSON: ${(e as Error)?.message}`;
        return;
      }
      const res = applyPresentation(parsed);
      const body = activeBodyFor('demo.authoring');
      if (body) declared.declaration = body;
      editorMsg.textContent = res.ok
        ? 'Certified & applied ✓ (rendered below)'
        : `Rejected: ${res.errors.map(describeConformanceError).join('; ')}`;
    },
  }, 'Certify & apply');
  host.append(
    section(
      '6 · In-UI authoring — type a declaration, certify & apply (Move 7)',
      editor,
      h('div', { className: 'demo-row' }, applyAuthored, h('span', {}, 'result: '), editorMsg),
      h('div', { className: 'demo-row' }, declared),
    ),
  );

  // 7. 569 §14 — the last two co-projected facets made structural (Move 3): LIVENESS (the engine
  // derives the live tri-state from the one observed-state authority — the author names the signal
  // but has no field for the state) and OVERFLOW (the engine clips the trailing tail via
  // OverflowController — the author declares priority but cannot naked-clip). Both render through
  // <jf-declared-surface>, so a faked-healthy indicator and a naked-clipped bar are unrepresentable.
  host.append(
    section(
      '7 · Co-projected liveness + overflow (Move 3 — the 2 last facets)',
      declaredSurface(
        {
          schema: { type: 'object', properties: {} },
          uischema: { type: 'VerticalLayout', elements: [] },
          heading: 'Live status + adaptive strip',
          liveness: 'core.retrieval',
          overflow: [
            { id: 's1', label: 'Indexed 12,340 docs', priority: 100, pinned: true },
            { id: 's2', label: 'Queue 3 jobs', priority: 90 },
            { id: 's3', label: 'GPU 41%', priority: 80 },
            { id: 's4', label: 'Memory 2.1 GB', priority: 70 },
            { id: 's5', label: 'Uptime 4h 12m', priority: 60 },
            { id: 's6', fact: 'core.embed.dim', priority: 50 },
            { id: 's7', label: 'Reranker on', priority: 40 },
            { id: 's8', label: 'SPLADE on', priority: 30 },
          ],
        } as DeclaredSurface['declaration'],
        {},
      ),
    ),
  );

  // 8. 569 §14 — mandatory-region VISIBILITY/non-occlusion: a required region that is PRESENT but
  // carries a `visibleWhen` (the present-but-hidden loophole the presence-only check missed) is
  // quarantined to the default layout, exactly like an omitted one. The layout tier has no live
  // renderer here, so we surface the gate's VERDICT (the same quarantine-to-default mechanism §4
  // shows on a body) — proving the check fires.
  const mrStatus = h('strong', { id: 'mr-status' }, '—');
  const mrBtn = h(
    'button',
    {
      className: 'demo-btn',
      onclick: () => {
        const { verdict } = certifyPresentation({
          schemaVersion: 1,
          id: 'demo.hidden-required',
          displayName: 'Hide the required region',
          layout: { regions: [{ id: 'stage', visibleWhen: 'data.never == true' }] },
        });
        mrStatus.textContent = verdict.quarantinedLayout
          ? `quarantined to default ✓ — ${
              verdict.errors.map(describeConformanceError).find((e) => /unconditionally/.test(e)) ?? ''
            }`
          : 'NOT quarantined (unexpected)';
      },
    },
    'Apply a layout that hides the required region',
  );
  host.append(
    section(
      '8 · Mandatory-region visibility — a present-but-hidden required region quarantines (Move 4/6)',
      h('div', { className: 'demo-row' }, mrBtn, h('span', {}, 'result: '), mrStatus),
    ),
  );

  // 9. 569 §14 — behaviour as the OPERATING MODE of a real surface flow: the APPEARANCE_FLOW
  // statechart drives a REAL restyle. Each button sends a named EVENT into the machine, which
  // dispatches set-appearance (the page restyles via the host listener wired here, replicating the
  // Shell's global listener) + save-settings — both journaled through the gated 550 dispatcher.
  const apListener = (e: Event): void => {
    const d =
      (e as CustomEvent<{ theme?: 'light' | 'dark' | 'system'; highContrast?: boolean }>).detail ??
      {};
    void applyAppearance({
      ...(d.theme !== undefined ? { theme: d.theme } : {}),
      ...(d.highContrast !== undefined ? { highContrast: d.highContrast } : {}),
    });
  };
  document.addEventListener('jf-set-appearance', apListener);
  const apMachine = createMachine(APPEARANCE_FLOW); // default gated dispatcher (journaled)
  const apJournal = h('strong', { id: 'ap-journal' }, '0');
  const apBtn = (label: string, event: string): HTMLElement =>
    h(
      'button',
      {
        className: 'demo-btn',
        onclick: () => {
          apMachine.send(event);
          apJournal.textContent = String(listJournal().length);
        },
      },
      label,
    );
  host.append(
    section(
      '9 · Behaviour as operating mode — the appearance statechart restyles a real surface (Move 8)',
      h(
        'div',
        { className: 'demo-row' },
        apBtn('Light', 'THEME_LIGHT'),
        apBtn('Dark', 'THEME_DARK'),
        apBtn('High contrast on', 'HC_ON'),
        apBtn('High contrast off', 'HC_OFF'),
      ),
      h(
        'div',
        { className: 'demo-row' },
        h('span', {}, 'Effect Journal (gated + journaled): '),
        apJournal,
        h('span', {}, ' effect(s) — the page restyles live'),
      ),
    ),
  );

  // 10. 569 §14 — the Library ROLLOUT: the indexed-folder cards rendered THROUGH the engine (the
  // folder-card renderer) from a declaration at bespoke parity — the 2nd real surface inverted. The
  // Remove INTENT the renderer emits is surfaced (the gated operation stays surface-owned, §7).
  const libCards = declaredSurface(LIBRARY_CARDS_BODY as DeclaredSurface['declaration'], {
    folders: [
      {
        pathHash: 'h1',
        displayPath: '/home/elias/Documents',
        status: 'indexed',
        metaText: 'Documents · 12,340 files · 2h ago',
      },
      {
        pathHash: 'h2',
        displayPath: '/home/elias/Code/justsearch',
        status: 'indexed',
        metaText: 'Code · 8,210 files · 5m ago',
      },
      {
        pathHash: 'h3',
        displayPath: '/mnt/archive',
        status: 'error',
        metaText: 'Archive · count pending',
        walkError: 'permission denied',
      },
    ],
  });
  const libMsg = h('strong', { id: 'lib-msg' }, '—');
  libCards.addEventListener('jf-folder-card-remove', (e: Event) => {
    libMsg.textContent = `Remove intent for ${
      (e as CustomEvent<{ pathHash: string }>).detail.pathHash
    } (the surface handles the gated op)`;
  });
  host.append(
    section(
      '10 · The Library rendered through the engine — declared folder cards (the 2nd real surface)',
      libCards,
      h('div', { className: 'demo-row' }, h('span', {}, 'remove intent: '), libMsg),
    ),
  );

  // 11. 569 §15 — a BRANCHING, GUARDED statechart (the delete-confirm ceremony): multiple states
  // (idle → confirming → done) + a `typed == true` guard, vs the single-state appearance flow. REQUEST
  // opens `confirming`; CONFIRM with an empty input is BLOCKED by the guard; typing "DELETE" lets
  // CONFIRM reach `done`, firing the closed `toast` (journaled). The real Settings delete runs this.
  const ceMachine = createMachine(CONFIRM_CEREMONY); // default gated dispatcher (journaled)
  const ceState = h('strong', { id: 'ce-state' }, 'idle');
  const ceTyped = h('input', {
    type: 'text',
    id: 'ce-typed',
    placeholder: 'type DELETE',
  }) as HTMLInputElement;
  const ceJournal = h('div', { id: 'ce-journal', className: 'demo-log' }, 'Effect Journal: 0 real effect(s)');
  const ceRefresh = (): void => {
    ceState.textContent = ceMachine.state;
    ceJournal.textContent = `Effect Journal: ${listJournal().length} real effect(s) dispatched`;
  };
  const ceBtn = (label: string, event: string): HTMLElement =>
    h(
      'button',
      {
        className: 'demo-btn',
        onclick: () => {
          ceMachine.send(event, { typed: ceTyped.value.trim().toUpperCase() === 'DELETE' });
          ceRefresh();
        },
      },
      label,
    );
  host.append(
    section(
      '11 · A BRANCHING, guarded statechart — the delete-confirm ceremony (Move 8)',
      h(
        'div',
        { className: 'demo-row' },
        h('span', {}, 'state: '),
        ceState,
        h('label', { className: 'demo-inline' }, 'type to confirm: ', ceTyped),
      ),
      h('div', { className: 'demo-row' }, ceBtn('REQUEST delete', 'REQUEST'), ceBtn('CONFIRM delete', 'CONFIRM'), ceBtn('CANCEL delete', 'CANCEL')),
      ceJournal,
    ),
  );

  // 12. 569 §15 — more REAL surfaces inverted: the Help reference (shortcuts table + bulleted lists via
  // the shortcuts-table / list-items renderers) and the Health stats (metric cards + the co-projected
  // OVERFLOW strip via metric-card) rendered THROUGH the engine.
  host.append(
    section(
      '12 · More surfaces inverted — declared Help reference + Health stats (Move 1/3)',
      declaredSurface(HELP_REFERENCE_BODY as DeclaredSurface['declaration'], {
        shortcuts: [
          { keys: '/', desc: 'Focus search bar' },
          { keys: '??', desc: 'Enter AI chat mode' },
          { keys: 'Ctrl+Enter', desc: 'Open selected file' },
        ],
        troubleshooting: ['If results look stale, use "Reindex" in Library or Health.'],
        network: ['Local app traffic uses loopback (not a network service).'],
      }),
      declaredSurface(HEALTH_STATS_BODY as DeclaredSurface['declaration'], {
        metrics: [
          { label: 'Files', value: '12,340', icon: 'file-text', tone: 'success' },
          { label: 'Size', value: '62 MB', icon: 'database', tone: 'neutral' },
          { label: 'Memory', value: '2.1 GB', icon: 'memory-stick', tone: 'warning', sub: 'of 8 GB' },
          { label: 'Queue', value: '0', icon: 'zap', tone: 'neutral', sub: 'Idle' },
        ],
      }),
    ),
  );

  // Minimal demo chrome styling.
  const style = document.createElement('style');
  style.textContent = `
    .demo-section { border: 1px solid var(--border-subtle); border-radius: 8px; padding: 1rem; background: var(--surface-2); }
    .demo-section h2 { margin: 0 0 0.75rem; font-size: var(--font-size-md); }
    .demo-row { display: flex; align-items: center; gap: 0.75rem; margin: 0.5rem 0; flex-wrap: wrap; }
    .demo-inline { display: inline-flex; align-items: center; gap: 0.3rem; }
    .demo-btn { padding: 0.35rem 0.75rem; cursor: pointer; border-radius: 6px; border: 1px solid var(--border-subtle); background: var(--surface-3); color: inherit; }
    .demo-log { margin: 0.5rem 0 0; padding-left: 1.1rem; font-family: var(--font-mono); font-size: var(--font-size-xs); opacity: 0.85; }
    .demo-editor { width: 100%; min-height: 9rem; font-family: var(--font-mono); font-size: var(--font-size-xs); padding: 0.5rem; border-radius: 6px; border: 1px solid var(--border-subtle); background: var(--surface-1); color: var(--text-primary); }
  `;
  host.append(style);
}
