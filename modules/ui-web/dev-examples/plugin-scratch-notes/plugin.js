/*
 * Scratch Notes — V1.5 reference plugin with real functionality.
 *
 * Demonstrates that V1.5 plugins can ship genuine user-facing
 * features. This plugin contributes a `<scratch-notes-pad>`
 * Surface to the rail with a localStorage-backed textarea.
 *
 * V1.5.1 (slice 477 H2.1) — Compartment-Loader contract.
 * V1.5.1 follow-on (478 §4.I) — register() returns a
 * PluginContribution record so registration is atomic + the host
 * applies customElements.define on the plugin's behalf with
 * namespace validation.
 *
 * Plugin source contract (V1.5.1):
 *   The plugin source is an EXPRESSION (not an ES module). The
 *   expression evaluates to a factory `(endowments) => PluginManifest`.
 *   The host's loader fetches this source as text + evaluates
 *   inside a SES Compartment with explicit endowments.
 *
 * §4.I shape: instead of imperatively calling customElements.define
 * at factory-evaluate time (V1.5 alpha pattern), this plugin
 * declares the class in `register()`'s returned PluginContribution.
 * The host validates + registers atomically. Benefit: if the
 * registration would fail (invalid tag suffix, namespace mismatch),
 * NO partial state lands.
 */

(({ HTMLElement, localStorage }) => {
  const STORAGE_KEY = 'jf-plugin-scratch-notes:body';

  // Define the class but DON'T register it via customElements.define.
  // The §4.I PluginContribution declaration in register() lets the
  // host register it with namespace validation.
  class ScratchNotesPad extends HTMLElement {
    connectedCallback() {
      const initial = (() => {
        try {
          return localStorage.getItem(STORAGE_KEY) ?? '';
        } catch {
          return '';
        }
      })();

      this.innerHTML = `
        <div style="
          height: 100%;
          display: flex;
          flex-direction: column;
          padding: 1.5rem;
          gap: 0.75rem;
          font-family: system-ui, -apple-system, sans-serif;
          color: var(--text-primary, #e5e7eb);
        ">
          <header style="display:flex;align-items:baseline;gap:0.5rem;">
            <h2 style="margin:0;font-size:1.25rem;">Scratch Notes</h2>
            <span style="font-size:0.7rem;opacity:0.6;">
              persisted to localStorage; survives reload
            </span>
          </header>
          <textarea
            data-test="scratch-notes-input"
            placeholder="Type anywhere; auto-saves on every keystroke."
            style="
              flex: 1;
              min-height: 200px;
              padding: 0.75rem;
              border-radius: 0.4rem;
              background: var(--glass-surface, rgba(255,255,255,0.04));
              border: 1px solid var(--glass-border, rgba(255,255,255,0.10));
              color: var(--text-primary, #e5e7eb);
              font-family: var(--font-mono, ui-monospace, 'SF Mono', monospace);
              font-size: 0.9rem;
              line-height: 1.5;
              resize: none;
            "
          ></textarea>
          <footer style="display:flex;justify-content:space-between;font-size:0.75rem;color:var(--text-secondary, #9ca3af);">
            <span data-test="scratch-notes-charcount">0 chars</span>
            <button
              data-test="scratch-notes-clear"
              style="
                background: transparent;
                border: 1px solid var(--glass-border, rgba(255,255,255,0.15));
                color: var(--text-secondary, #9ca3af);
                padding: 0.25rem 0.6rem;
                border-radius: 0.3rem;
                cursor: pointer;
                font-size: 0.75rem;
              "
            >Clear</button>
          </footer>
        </div>
      `;

      const ta = this.querySelector('[data-test="scratch-notes-input"]');
      const charCount = this.querySelector('[data-test="scratch-notes-charcount"]');
      const clearBtn = this.querySelector('[data-test="scratch-notes-clear"]');

      ta.value = initial;
      charCount.textContent = `${initial.length} chars`;

      ta.addEventListener('input', () => {
        try {
          localStorage.setItem(STORAGE_KEY, ta.value);
        } catch {
          // storage may be full or disabled; tolerated
        }
        charCount.textContent = `${ta.value.length} chars`;
      });

      clearBtn.addEventListener('click', () => {
        ta.value = '';
        try {
          localStorage.removeItem(STORAGE_KEY);
        } catch {
          // tolerated
        }
        charCount.textContent = '0 chars';
        ta.focus();
      });
    }
  }

  return {
    id: 'scratch-notes',
    version: '0.3.0',
    displayName: 'Scratch Notes',
    contractVersion: '1.1',
    tagNamespace: 'scratch-notes',

    capabilities: {
      customElementTags: ['scratch-notes-pad'],
      surfaces: [
        {
          id: 'scratch-notes.pad',
          mountTag: 'scratch-notes-pad',
          labelKey: 'scratch-notes.pad.label',
          descriptionKey: 'scratch-notes.pad.description',
          audience: 'USER',
          placement: 'RAIL',
          consumes: {
            operations: [],
            resources: [],
            prompts: [],
            diagnosticChannels: [],
          },
        },
      ],
    },

    signature: 'v1.5.1-stub:scratch-notes@0.3.0',

    // §4.I — declare contribution. The host registers
    // customElements + surfacePorts + translations atomically.
    // No imperative customElements.define at factory-evaluate
    // time — the host does it on behalf of the plugin with
    // namespace validation BEFORE the define runs. If the tag
    // suffix is invalid (e.g., contains uppercase), the host
    // throws a clear error and no class is registered.
    register(host) {
      void host;
      return {
        customElements: [
          { tagSuffix: 'pad', klass: ScratchNotesPad },
        ],
        translations: {
          en: {
            'scratch-notes.pad.label': 'Scratch Notes',
            'scratch-notes.pad.description':
              'Persistent notepad backed by localStorage. V1.5.1 reference plugin (§4.I shape).',
          },
        },
      };
    },

    unregister(host) {
      void host;
      // §4.F — the registry's uninstall calls
      // unregisterPluginCatalog(id) which O(1) removes the
      // plugin's entire i18n scope. No tracking array needed.
      // Custom-element registration cannot be undone (HTML spec);
      // the class persists in the global registry but the
      // surface contribution is removed via
      // removePluginSurfaceContributions.
    },
  };
})
