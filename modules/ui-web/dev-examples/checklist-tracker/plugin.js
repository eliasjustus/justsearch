/**
 * Checklist Tracker — the first real third-party plugin (tempdoc 560 §28 / 533).
 *
 * Purpose: a genuinely third-party, URL-loaded, *writing* plugin that exercises the delivery path the
 * compiled-in Token Editor bypassed — untrusted load → operator approval → TRUSTED → SES-sandboxed,
 * own-element surface, persistent state. It is NOT a bundled feature; it is the substrate-validation
 * consumer that proves the pipe end-to-end.
 *
 * Design choices (structural):
 *   - State persists via `host.settings` (plugin-namespaced, ADR-0035-safe — the plugin authors its
 *     own scoped state, never backend truth). No new backend operation is invented.
 *   - It mounts its OWN `<checklist-panel>` custom element, so under the §4.4 presentation constraint
 *     it requires TRUSTED tier — which is exactly the operator-approval ceremony being validated. As
 *     UNTRUSTED its surface is dropped (0 surfaces); approved, it renders fully.
 *
 * Dev: `node dev-server.cjs`, then Settings → Plugins → Load from URL → http://127.0.0.1:3002/plugin.js
 */

(({ customElements, HTMLElement, console }) => {
  const STORAGE_KEY = 'items';
  // Set in register(host); the custom element reads it for persistence.
  let hostApi = null;

  function escapeHtml(s) {
    return String(s).replace(
      /[&<>"']/g,
      (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c],
    );
  }

  function loadItems() {
    try {
      const raw = hostApi?.settings?.getSetting?.(STORAGE_KEY);
      if (Array.isArray(raw)) return raw;
      if (typeof raw === 'string' && raw.trim()) return JSON.parse(raw);
    } catch (e) {
      console?.log?.('[checklist] load failed:', e?.message);
    }
    return [];
  }

  function saveItems(items) {
    try {
      hostApi?.settings?.setSetting?.(STORAGE_KEY, items);
    } catch (e) {
      console?.log?.('[checklist] save failed:', e?.message);
    }
  }

  class ChecklistPanel extends HTMLElement {
    connectedCallback() {
      this.items = loadItems();
      this.render();
    }

    render() {
      const root = this.shadowRoot ?? this.attachShadow({ mode: 'open' });
      const done = this.items.filter((i) => i.done).length;
      root.innerHTML = `
        <style>
          :host { display: block; padding: 1rem; color: var(--text-primary, #e5e7eb); font: 13px system-ui, sans-serif; }
          h1 { margin: 0 0 0.5rem; font-size: 1rem; }
          .count { color: var(--text-secondary, #9ca3af); font-size: 0.8rem; margin-bottom: 0.75rem; }
          form { display: flex; gap: 0.4rem; margin-bottom: 0.75rem; }
          input[type=text] { flex: 1; padding: 0.35rem 0.5rem; background: var(--surface-secondary, #1f2937);
            border: 1px solid var(--border-subtle, #374151); border-radius: 0.375rem; color: inherit; }
          button { padding: 0.3rem 0.55rem; cursor: pointer; border-radius: 0.375rem;
            border: 1px solid var(--border-subtle, #374151); background: var(--surface-secondary, #1f2937); color: inherit; }
          ul { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0.3rem; }
          li { display: flex; align-items: center; gap: 0.5rem; }
          li label { flex: 1; display: flex; align-items: center; gap: 0.45rem; }
          li.done label { text-decoration: line-through; color: var(--text-secondary, #9ca3af); }
          .del { border: none; background: transparent; color: var(--text-secondary, #9ca3af); }
        </style>
        <h1>Checklist</h1>
        <div class="count">${done} / ${this.items.length} done</div>
        <form id="add">
          <input id="text" type="text" placeholder="New item…" aria-label="New checklist item" />
          <button type="submit">Add</button>
        </form>
        <ul>
          ${this.items
            .map(
              (it, i) => `
            <li class="${it.done ? 'done' : ''}">
              <label><input type="checkbox" data-i="${i}" ${it.done ? 'checked' : ''} /> ${escapeHtml(it.text)}</label>
              <button class="del" data-del="${i}" aria-label="Delete item" title="Delete">✕</button>
            </li>`,
            )
            .join('')}
        </ul>
      `;

      root.getElementById('add').addEventListener('submit', (e) => {
        e.preventDefault();
        const input = root.getElementById('text');
        const value = input.value.trim();
        if (!value) return;
        this.items = [...this.items, { text: value, done: false }];
        saveItems(this.items);
        input.value = '';
        this.render();
      });
      root.querySelectorAll('input[type=checkbox]').forEach((cb) =>
        cb.addEventListener('change', () => {
          const i = Number(cb.dataset.i);
          this.items = this.items.map((it, idx) => (idx === i ? { ...it, done: cb.checked } : it));
          saveItems(this.items);
          this.render();
        }),
      );
      root.querySelectorAll('button[data-del]').forEach((b) =>
        b.addEventListener('click', () => {
          const i = Number(b.dataset.del);
          this.items = this.items.filter((_, idx) => idx !== i);
          saveItems(this.items);
          this.render();
        }),
      );
    }
  }

  // An UNTRUSTED plugin's customElements is a namespace-enforcing proxy; defining a non-jf-* tag throws.
  // Tolerate it so the load still reaches register() (the surface is dropped under §4.4 when UNTRUSTED).
  try {
    customElements.define('checklist-panel', ChecklistPanel);
  } catch (e) {
    console?.log?.('[checklist] customElements.define blocked (expected while UNTRUSTED):', e?.message);
  }

  // Tempdoc 560 §28.G (the ConversationShape runner) — the SAME checklist data rendered as a
  // conversation-shape view (a read-only digest). It is a SECOND contribution KIND from one plugin,
  // proving the plugin-facing view-factory path end-to-end: a declared shape + its own element get
  // registered at install (TRUSTED-gated, §4.4) and mounted live via `<jf-chat-shape-mount>`.
  class ChecklistShapeView extends HTMLElement {
    connectedCallback() {
      const items = loadItems();
      const done = items.filter((i) => i.done).length;
      const root = this.shadowRoot ?? this.attachShadow({ mode: 'open' });
      root.innerHTML = `
        <style>
          :host { display: block; padding: 1rem; color: var(--text-primary, #e5e7eb); font: 13px system-ui, sans-serif; }
          h1 { margin: 0 0 0.5rem; font-size: 1rem; }
          .count { color: var(--text-secondary, #9ca3af); font-size: 0.8rem; margin-bottom: 0.75rem; }
          ul { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0.3rem; }
          li.done { text-decoration: line-through; color: var(--text-secondary, #9ca3af); }
        </style>
        <h1>Checklist (shape view)</h1>
        <div class="count">${done} / ${items.length} done — mounted by the ConversationShape runner</div>
        <ul>${items.map((it) => `<li class="${it.done ? 'done' : ''}">${escapeHtml(it.text)}</li>`).join('')}</ul>
      `;
    }
  }
  try {
    customElements.define('checklist-tracker-shapeview', ChecklistShapeView);
  } catch (e) {
    console?.log?.('[checklist] shape-view define blocked (expected while UNTRUSTED):', e?.message);
  }

  const surface = {
    id: 'vendor.checklist.tracker-surface',
    mountTag: 'checklist-panel',
    labelKey: 'surface.checklist.label',
    descriptionKey: 'surface.checklist.description',
    audience: 'USER',
    placement: 'RAIL',
  };
  // A surface that HOSTS the plugin's ConversationShape via the host `jf-chat-shape-mount` vocabulary
  // (a jf-* tag, so the surface itself is presentation-admissible at any tier). The chrome sets
  // `shape-id` from consumes.conversationShapes[0]; the runner-registered factory resolves it to
  // <checklist-tracker-shapeview> (TRUSTED) or renders the no-factory placeholder (UNTRUSTED — dropped).
  const shapeSurface = {
    id: 'vendor.checklist.shape-surface',
    mountTag: 'jf-chat-shape-mount',
    labelKey: 'surface.checklist.shape.label',
    descriptionKey: 'surface.checklist.shape.description',
    audience: 'USER',
    placement: 'RAIL',
    consumes: { conversationShapes: ['vendor.checklist.demo-shape'] },
  };

  return {
    id: 'checklist-tracker',
    version: '0.1.0',
    displayName: 'Checklist Tracker',
    contractVersion: '1.1',
    // The renderer contract (slice 3a.1.5 §2) requires tagNamespace === id.
    tagNamespace: 'checklist-tracker',
    capabilities: { surfaces: [surface, shapeSurface] },
    register(host) {
      hostApi = host;
      return {
        translations: {
          en: {
            'surface.checklist.label': 'Checklist',
            'surface.checklist.description': 'A simple persistent checklist tracker',
            'surface.checklist.shape.label': 'Checklist (shape)',
            'surface.checklist.shape.description': 'The checklist rendered as a conversation shape',
          },
        },
        surfaceContributions: [{ contribution: surface }, { contribution: shapeSurface }],
        // Tempdoc 560 §28.G — declare the shape + its runner target (viewTag). The host registers the
        // (shapeRef → element) view factory at install, gated by trust tier + the `vendor.*` namespace.
        conversationShapes: [
          { contribution: { id: 'vendor.checklist.demo-shape', viewTag: 'checklist-tracker-shapeview' } },
        ],
      };
    },
  };
});
