// SPDX-License-Identifier: Apache-2.0
/**
 * ApiExplorerView — tempdoc 583 §D.3b/§D.3e: the developer-facing API-explorer surface.
 *
 * A pure PROJECTION of GET /api/meta/routes (the self-describing route manifest, §D.3a) — the live
 * HTTP surface grouped by domain cohort, each route showing its required runtime capability (from the
 * one RouteCapabilityPolicy authority the gates enforce with). §D.3e: each required capability is
 * cross-referenced with live readiness (GET /api/status) so the explorer doubles as a "what works
 * right now" map. Read-only — no controls, nothing to mutate.
 *
 * Mirrors GovernanceView (the sibling read-only DEEPLINK dev dashboard): composes SurfaceLayout,
 * emits no own page-title <h1> or main landmark (the shell owns those; tempdoc 578 a11y-closure) —
 * only sections; tokens only (no bare colored literals, §6 ban); degrades to a confident empty/error
 * state. Side-effect registers <jf-api-explorer-view> for the chrome dispatcher.
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { surfaceScrollLayoutStyles } from '../primitives/surfaceLayout.js';
import { apiPath } from '../../api/generated/apiRoutes.js';

interface RouteEntry {
  method: string;
  path: string;
  cohort: string;
  requiredCapabilities: string[];
  /** §D.3a owning-module dimension — the ApiModule that registered the route, or null (static *Routes). */
  owningModule?: string | null;
  /** §D.3a schema dimension — the response wire-record schema file, or null (undocumented routes). */
  responseSchema?: string | null;
}
interface RouteManifest {
  schemaVersion: string;
  count: number;
  routes: RouteEntry[];
}

/** Live availability of the capabilities a route can require (§D.3e); null = status not loaded. */
type Capabilities = { worker: boolean; inference: boolean } | null;

type LoadState =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'ready'; manifest: RouteManifest };

export class ApiExplorerView extends JfElement {
  static properties = {
    apiBase: { type: String, attribute: 'api-base' },
  } as const;

  declare apiBase: string;
  private load: LoadState = { kind: 'loading' };
  private capabilities: Capabilities = null;

  constructor() {
    super();
    this.apiBase = '';
  }

  override connectedCallback(): void {
    super.connectedCallback();
    void this.fetchManifest();
    void this.fetchCapabilities();
  }

  private async fetchManifest(): Promise<void> {
    this.load = { kind: 'loading' };
    this.requestUpdate();
    try {
      const res = await fetch(`${this.apiBase}${apiPath('GET /api/meta/routes')}`, {
        headers: { accept: 'application/json' },
      });
      if (res.status === 404) {
        this.load = { kind: 'error', message: 'The route manifest is not available in this build.' };
        this.requestUpdate();
        return;
      }
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`);
      }
      const manifest = (await res.json()) as RouteManifest;
      if (!manifest || !Array.isArray(manifest.routes)) {
        this.load = { kind: 'error', message: 'The route manifest response was malformed.' };
        this.requestUpdate();
        return;
      }
      this.load = { kind: 'ready', manifest };
    } catch (e) {
      this.load = { kind: 'error', message: e instanceof Error ? e.message : String(e) };
    }
    this.requestUpdate();
  }

  /** Best-effort live capability readiness (§D.3e). Failures leave capabilities null — the route list
   * still renders; only the live availability badges are suppressed. */
  private async fetchCapabilities(): Promise<void> {
    try {
      const res = await fetch(`${this.apiBase}${apiPath('GET /api/status')}`, { headers: { accept: 'application/json' } });
      if (!res.ok) return;
      const s = (await res.json()) as {
        aiReady?: boolean;
        indexAvailable?: boolean;
        readiness?: { components?: Record<string, { state?: string }> };
      };
      const comp = s.readiness?.components ?? {};
      const worker = comp.workerControlPlane?.state === 'READY' || s.indexAvailable === true;
      const inference = s.aiReady === true || comp.ai?.state === 'READY';
      this.capabilities = { worker, inference };
      this.requestUpdate();
    } catch {
      // best-effort; leave capabilities null
    }
  }

  static styles = [
    surfaceScrollLayoutStyles,
    css`
      :host {
        color: var(--text-primary);
      }
      .intro {
        color: var(--text-secondary);
        margin: 0 0 1rem;
        max-width: 60ch;
      }
      section {
        margin-top: 1.5rem;
      }
      h2 {
        color: var(--text-primary);
        font-weight: 600;
        margin: 0 0 0.5rem;
        font-size: var(--font-size-md);
      }
      .summary {
        display: flex;
        flex-wrap: wrap;
        gap: 0.75rem;
        margin-bottom: 0.5rem;
      }
      .stat {
        background: var(--surface-2);
        border: 1px solid var(--border-subtle);
        border-radius: 0.5rem;
        padding: 0.6rem 0.9rem;
        min-width: 8rem;
      }
      .stat .value {
        color: var(--text-primary);
        font-weight: 700;
      }
      .stat .label {
        color: var(--text-muted);
      }
      table {
        width: 100%;
        border-collapse: collapse;
      }
      th,
      td {
        text-align: left;
        padding: 0.35rem 0.6rem;
        border-bottom: 1px solid var(--border-subtle);
        vertical-align: top;
      }
      th {
        color: var(--text-secondary);
        font-weight: 600;
      }
      code {
        font-family: var(--jf-font-mono);
        color: var(--text-secondary);
      }
      .method {
        font-family: var(--jf-font-mono);
        font-weight: 700;
        color: var(--text-muted);
      }
      .cap {
        display: inline-block;
        font-size: var(--font-size-xs);
        border: 1px solid var(--border-subtle);
        border-radius: 0.25rem;
        padding: 0 0.35rem;
        margin-right: 0.25rem;
        color: var(--text-secondary);
      }
      .cap.ready {
        color: var(--text-success);
      }
      .cap.down {
        color: var(--text-muted);
      }
      .empty {
        color: var(--text-muted);
      }
      .owner {
        font-size: var(--font-size-xs);
        color: var(--text-secondary);
      }
      .schema {
        font-family: var(--jf-font-mono);
        font-size: var(--font-size-xs);
        color: var(--text-secondary);
      }
    `,
  ];

  override render(): TemplateResult {
    if (this.load.kind === 'loading') {
      return html`<section><p class="empty">Loading route manifest…</p></section>`;
    }
    if (this.load.kind === 'error') {
      return html`<section><p class="empty">${this.load.message}</p></section>`;
    }
    const m = this.load.manifest;
    const cohorts = [...new Set(m.routes.map((r) => r.cohort))].sort();
    const gatedCount = m.routes.filter((r) => r.requiredCapabilities.length > 0).length;
    return html`
      <p class="intro">
        The live HTTP surface, made legible: a read-only projection of GET /api/meta/routes (tempdoc
        583 §D) — every registered route grouped by domain, with the runtime capability each requires
        (the same RouteCapabilityPolicy the security filters enforce with).
      </p>
      <div class="summary">
        ${this.stat(String(m.count), 'routes')}
        ${this.stat(String(cohorts.length), 'cohorts')}
        ${this.stat(String(gatedCount), 'capability-gated')}
        ${this.capabilityStrip()}
      </div>
      ${cohorts.map((c) => this.cohortSection(c, m.routes.filter((r) => r.cohort === c)))}
    `;
  }

  private stat(value: string, label: string): TemplateResult {
    return html`<div class="stat">
      <div class="value">${value}</div>
      <div class="label">${label}</div>
    </div>`;
  }

  /** §D.3e — live availability of the gateable capabilities. */
  private capabilityStrip(): TemplateResult {
    const caps = this.capabilities;
    if (!caps) {
      return html`<div class="stat"><div class="value">—</div><div class="label">capabilities</div></div>`;
    }
    const cell = (name: string, up: boolean) =>
      html`<span class="cap ${up ? 'ready' : 'down'}">${name}: ${up ? 'available' : 'unavailable'}</span>`;
    return html`<div class="stat">
      <div class="value">${cell('worker', caps.worker)} ${cell('inference', caps.inference)}</div>
      <div class="label">live capabilities</div>
    </div>`;
  }

  private cohortSection(cohort: string, routes: RouteEntry[]): TemplateResult {
    return html`
      <section>
        <h2>${cohort} (${routes.length})</h2>
        <table>
          <thead>
            <tr>
              <th>Method</th>
              <th>Path</th>
              <th>Requires</th>
              <th>Owner</th>
              <th>Response</th>
            </tr>
          </thead>
          <tbody>
            ${routes.map(
              (r) => html`<tr>
                <td class="method">${r.method}</td>
                <td><code>${r.path}</code></td>
                <td>${this.capCells(r.requiredCapabilities)}</td>
                <td>${r.owningModule ? html`<span class="owner">${r.owningModule}</span>` : html`<span class="empty">—</span>`}</td>
                <td>
                  ${r.responseSchema
                    ? html`<code class="schema">${r.responseSchema}</code>`
                    : html`<span class="empty">—</span>`}
                </td>
              </tr>`,
            )}
          </tbody>
        </table>
      </section>
    `;
  }

  private capCells(required: string[]): TemplateResult {
    if (required.length === 0) return html`<span class="empty">—</span>`;
    const caps = this.capabilities;
    return html`${required.map((c) => {
      const up = caps ? (c === 'WORKER' ? caps.worker : c === 'INFERENCE' ? caps.inference : true) : null;
      const cls = up == null ? '' : up ? 'ready' : 'down';
      return html`<span class="cap ${cls}">${c.toLowerCase()}</span>`;
    })}`;
  }
}

if (!customElements.get('jf-api-explorer-view')) {
  customElements.define('jf-api-explorer-view', ApiExplorerView);
}
