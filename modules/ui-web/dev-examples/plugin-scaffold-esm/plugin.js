/**
 * ES-module scaffold plugin (tempdoc 560 §4.2 module-mode). Unlike the factory scaffold, this reaches
 * a host capability via a REAL ES import — `import { data } from '@kernel/data'` — resolved by the
 * loader's kernel module map (tier-attenuated). The manifest is the default export.
 *
 * Endowments (customElements, HTMLElement, console) are Compartment globals in module scope.
 */
import { data } from '@kernel/data';

class ScaffoldEsmPanel extends HTMLElement {
  connectedCallback() {
    const root = this.attachShadow({ mode: 'open' });
    root.innerHTML = `<style>:host{display:block;padding:1rem;color:var(--text-primary,#e5e7eb)}</style>
      <h1>Scaffold ESM Plugin</h1><p>Reaches @kernel/data via a real ES import.</p>`;
  }
}
// UNTRUSTED customElements is a namespace-enforcing proxy keyed on expectedPluginId ('unknown' for the
// Settings load-from-URL path), so a non-matching tag throws — tolerate it so register() still runs.
try {
  customElements.define('scaffold-esm-panel', ScaffoldEsmPanel);
} catch (e) {
  console?.log?.('[scaffold-esm] customElements.define blocked by namespace proxy (expected):', e?.message);
}

export default {
  id: 'scaffold-esm',
  version: '0.1.0',
  displayName: 'Scaffold ESM Plugin',
  contractVersion: '1.1',
  tagNamespace: 'scaffold-esm',
  capabilities: {
    surfaces: [
      {
        id: 'scaffold-esm.panel-surface',
        mountTag: 'scaffold-esm-panel',
        labelKey: 'surface.scaffold-esm.label',
        descriptionKey: 'surface.scaffold-esm.description',
        audience: 'USER',
        placement: 'RAIL',
      },
    ],
  },
  register() {
    // `data` was reached through `import { data } from '@kernel/data'` — the resolver-substituted
    // boundary (no if(isUntrusted) anywhere; the import path IS the boundary). Prove it live:
    data
      .fetch('/api/health')
      .then((r) => r.json())
      .then((j) => console?.log?.('[scaffold-esm] @kernel ES-import → /api/health', j?.lifecycle?.state ?? j))
      .catch((e) => console?.log?.('[scaffold-esm] /api/health error:', e?.message));
    data
      .fetch('/api/secret-not-allowlisted')
      .then(() => console?.log?.('[scaffold-esm] UNEXPECTED: non-allowlisted succeeded'))
      .catch((e) => console?.log?.('[scaffold-esm] attenuation rejected non-allowlisted:', e?.message));
    return {
      translations: {
        en: {
          'surface.scaffold-esm.label': 'Scaffold ESM',
          'surface.scaffold-esm.description': 'ES-module plugin surface',
        },
      },
      surfaceContributions: [
        {
          contribution: {
            id: 'scaffold-esm.panel-surface',
            mountTag: 'scaffold-esm-panel',
            labelKey: 'surface.scaffold-esm.label',
            descriptionKey: 'surface.scaffold-esm.description',
            audience: 'USER',
            placement: 'RAIL',
          },
        },
      ],
    };
  },
};
