/**
 * Scaffold plugin — minimal working plugin demonstrating the V1.5
 * plugin contract. Copy this directory and modify to start a new plugin.
 *
 * The exported factory function evaluates inside a SES Compartment.
 * Available endowments: customElements, HTMLElement, localStorage,
 * document, setTimeout, setInterval, console.
 *
 * The plugin receives a PluginHostApi via the register() callback —
 * use it for fetch, operations, notifications, search state, etc.
 */

(({ customElements, HTMLElement, kernel, console }) => {
  class ScaffoldPanel extends HTMLElement {
    connectedCallback() {
      const root = this.attachShadow({ mode: 'open' });
      root.innerHTML = `
        <style>
          :host { display: block; padding: 1rem; color: var(--text-primary, #e5e7eb); }
          h1 { margin: 0 0 0.5rem; font-size: 1rem; }
          button { padding: 0.4rem 0.625rem; cursor: pointer; }
        </style>
        <h1>Scaffold Plugin</h1>
        <p>This is a minimal plugin contributing a surface.</p>
        <button id="ping">Show notification</button>
      `;
      root.getElementById('ping').addEventListener('click', () => {
        this.dispatchEvent(new CustomEvent('plugin-ping', { bubbles: true, composed: true }));
      });
    }
  }
  // Guard: an UNTRUSTED plugin's customElements is a namespace-enforcing proxy keyed on the
  // expectedPluginId the loader was given (here 'unknown', since Settings → Load from URL passes none).
  // Defining a non-matching tag throws — tolerate it so the load still proceeds to register() (the
  // surface is dropped anyway under the §4.4 PRESENTATION constraint when the verdict is UNTRUSTED).
  try {
    customElements.define('scaffold-panel', ScaffoldPanel);
  } catch (e) {
    console?.log?.('[scaffold] customElements.define blocked by namespace proxy (expected for UNTRUSTED):', e?.message);
  }

  return {
    id: 'scaffold',
    version: '0.1.0',
    displayName: 'Scaffold Plugin',
    contractVersion: '1.1',
    tagNamespace: 'scaffold',
    capabilities: {
      surfaces: [
        {
          // Surface ids must be `vendor.<x>.<y>` (or `core.<y>`) to satisfy the router's SurfaceRef
          // id regex (`^(core|vendor\.[a-z][a-z0-9-]*)\.[a-z][a-z0-9-]*$`) — otherwise the surface is
          // admitted but not navigable. (The element tag below is separate: `scaffold-panel`.)
          id: 'vendor.scaffold.panel-surface',
          mountTag: 'scaffold-panel',
          labelKey: 'surface.scaffold-panel.label',
          descriptionKey: 'surface.scaffold-panel.description',
          audience: 'USER',
          placement: 'RAIL',
        },
      ],
    },
    register(host) {
      // host is the PluginHostApi (nested sub-interfaces per tempdoc 508 §2.2):
      //   host.ui.showNotification('Hello from scaffold')
      //   host.data.invokeOperation('core.ping-backend')
      //   const unsub = host.search.subscribeSearch((state) => console.log(state))

      // Tempdoc 560 §4.2 — reach a capability via the @kernel/* access path (resolver-substituted by
      // trust tier). For an UNTRUSTED plugin the resolved data capability is GET-only + allowlisted:
      // /api/health succeeds; a non-allowlisted path is rejected — the attenuation is which module the
      // resolver returned, not an if() the plugin can bypass.
      try {
        if (typeof kernel === 'function') {
          const data = kernel('@kernel/data');
          data
            .fetch('/api/health')
            .then((r) => r.json())
            .then((j) =>
              console?.log?.('[scaffold] @kernel/data fetched /api/health →', j?.lifecycle?.state ?? j),
            )
            .catch((e) => console?.log?.('[scaffold] @kernel/data /api/health error:', e?.message));
          data
            .fetch('/api/secret-not-allowlisted')
            .then(() => console?.log?.('[scaffold] UNEXPECTED: non-allowlisted fetch succeeded'))
            .catch((e) =>
              console?.log?.('[scaffold] @kernel/data attenuation rejected non-allowlisted path:', e?.message),
            );
        } else {
          console?.log?.('[scaffold] @kernel endowment absent (loaded without hostDeps)');
        }
      } catch (e) {
        console?.log?.('[scaffold] @kernel access error:', e?.message);
      }

      // Optional: register a command palette entry
      if (host.registration?.registerCommand) {
        host.registration.registerCommand('scaffold.greet', 'Scaffold: Say Hello', () => {
          host.ui?.showNotification?.('Hello from the scaffold plugin!');
        });
      }

      return {
        translations: {
          en: {
            'surface.scaffold-panel.label': 'Scaffold',
            'surface.scaffold-panel.description': 'Example plugin surface',
          },
        },
        surfaceContributions: [
          {
            contribution: {
              id: 'vendor.scaffold.panel-surface',
              mountTag: 'scaffold-panel',
              labelKey: 'surface.scaffold-panel.label',
              descriptionKey: 'surface.scaffold-panel.description',
              audience: 'USER',
              placement: 'RAIL',
            },
          },
        ],
      };
    },
  };
});
