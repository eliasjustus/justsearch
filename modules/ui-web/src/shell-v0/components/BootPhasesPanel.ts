// SPDX-License-Identifier: Apache-2.0
/**
 * BootPhasesPanel — FE consumer for the composition-substrate boot-trace endpoint
 * (tempdoc 541 §4.2).
 *
 * Self-registers as `<jf-boot-phases-panel>`. Fetches `GET /api/boot/phases` on first
 * `connectedCallback` and renders the immutable boot-trace snapshot as a small table:
 * phase name, eagerness, duration (ms), outcome, reason code.
 *
 * Mounting: this element is **the** named production consumer for `GET /api/boot/phases`
 * per tempdoc 541's named-consumer-at-landing constraint (§4.1). The component is
 * imported from the Shell side-effect registry so it's bundled even when no surface
 * currently renders it; that is sufficient to satisfy C-018 (substrate-with-named-
 * consumer) at landing. Surface placement is a follow-on UX decision; the component
 * is invocable from any panel today by inserting `<jf-boot-phases-panel></jf-boot-phases-panel>`.
 *
 * Endpoint shape (verified live 2026-05-21):
 *
 *   {
 *     "boot": {
 *       "process": "head",
 *       "bootStartedAtMs": ...,
 *       "bootCompletedAtMs": ...,
 *       "totalDurationMs": ...,
 *       "phases": [
 *         { "name": ..., "eagerness": ..., "durationMs": ..., "outcome": ...,
 *           "reasonCode": ..., "spanId": ... },
 *         ...
 *       ]
 *     }
 *   }
 *
 * Error states: 503 if the HeadAssembly hasn't completed boot (unreachable in
 * practice — Javalin doesn't bind routes until HeadAssembly construction
 * finishes); 5xx surfaces as a small "load failed" line.
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './StatusBadge.js';

interface PhaseRow {
  name: string;
  eagerness: string;
  startedAtMs: number;
  completedAtMs: number | null;
  durationMs: number | null;
  outcome: string;
  reasonCode: string | null;
  spanId: string | null;
}

interface BootTraceEnvelope {
  boot: {
    process: string;
    bootStartedAtMs: number;
    bootCompletedAtMs: number | null;
    totalDurationMs: number | null;
    phases: PhaseRow[];
    // 541 fix-pass Tier 4 — head envelope only.
    rebuilds?: PhaseRow[];
    rebuildHistoryCapacity?: number;
    rebuildHistoryTotal?: number;
    // 541 fix-pass C.3 — brain envelope only.
    projection?: boolean;
    projectionNote?: string;
  };
}

export class BootPhasesPanel extends JfElement {
  static styles = css`
    :host { display: block; font-family: var(--jf-font-mono); font-size: var(--font-size-xs); }
    .err { color: var(--text-danger); }
    table { border-collapse: collapse; width: 100%; margin-bottom: 8px; }
    th, td { text-align: left; padding: 2px 6px; border-bottom: 1px solid #eee; }
    th { font-weight: 600; opacity: 0.75; }
    .outcome-READY { color: var(--text-success); }
    .outcome-DEGRADED { color: var(--text-warning); }
    .outcome-FAILED { color: var(--text-danger); }
    .outcome-PENDING { color: #888; }
    .header { display: flex; gap: 1em; margin-bottom: 6px; flex-wrap: wrap; }
    .header span { opacity: 0.75; }
    .projection-note { font-style: italic; opacity: 0.7; margin: 4px 0 8px; font-size: var(--font-size-xs); }
    .section-title { font-weight: 600; margin: 8px 0 4px; opacity: 0.8; }
    .rebuilds-empty { opacity: 0.6; font-style: italic; font-size: var(--font-size-xs); }
  `;

  private trace: BootTraceEnvelope | null = null;
  private error: string | null = null;
  private fetched = false;

  override connectedCallback(): void {
    super.connectedCallback();
    if (!this.fetched) {
      this.fetched = true;
      this.load();
    }
  }

  private async load(): Promise<void> {
    try {
      const res = await fetch('/api/boot/phases');
      if (!res.ok) {
        this.error = `HTTP ${res.status}`;
        this.requestUpdate();
        return;
      }
      this.trace = (await res.json()) as BootTraceEnvelope;
    } catch (e) {
      this.error = e instanceof Error ? e.message : String(e);
    }
    this.requestUpdate();
  }

  override render(): TemplateResult {
    if (this.error) {
      return html`<div class="err">Boot-trace load failed: ${this.error}</div>`;
    }
    if (!this.trace) {
      return html`<div>Loading boot trace…</div>`;
    }
    const b = this.trace.boot;
    return html`
      <div class="header">
        <span>process: <strong>${b.process}</strong></span>
        ${b.projection ? html`<jf-status-badge tone="info">projection</jf-status-badge>` : ''}
        <span>total: <strong>${b.totalDurationMs ?? '—'}ms</strong></span>
        <span>phases: <strong>${b.phases.length}</strong></span>
        ${b.rebuildHistoryTotal !== undefined
          ? html`<span>rebuilds: <strong>${b.rebuildHistoryTotal}</strong>${b.rebuildHistoryCapacity ? html` / cap ${b.rebuildHistoryCapacity}` : ''}</span>`
          : ''}
      </div>
      ${b.projectionNote
        ? html`<div class="projection-note">${b.projectionNote}</div>`
        : ''}
      <table>
        <thead>
          <tr>
            <th>phase</th>
            <th>eagerness</th>
            <th>duration</th>
            <th>outcome</th>
            <th>reason</th>
          </tr>
        </thead>
        <tbody>
          ${b.phases.map((p) => this.renderRow(p))}
        </tbody>
      </table>
      ${b.rebuilds !== undefined
        ? html`
            <div class="section-title">Rebuilds</div>
            ${b.rebuilds.length === 0
              ? html`<div class="rebuilds-empty">No post-boot rebuilds recorded.</div>`
              : html`
                  <table>
                    <thead>
                      <tr>
                        <th>event</th>
                        <th>eagerness</th>
                        <th>duration</th>
                        <th>outcome</th>
                        <th>reason</th>
                      </tr>
                    </thead>
                    <tbody>
                      ${b.rebuilds.map((p) => this.renderRow(p))}
                    </tbody>
                  </table>
                `}
          `
        : ''}
    `;
  }

  private renderRow(p: PhaseRow): TemplateResult {
    return html`
      <tr>
        <td>${p.name}</td>
        <td>${p.eagerness}</td>
        <td>${p.durationMs ?? '—'}ms</td>
        <td class="outcome-${p.outcome}">${p.outcome}</td>
        <td>${p.reasonCode ?? ''}</td>
      </tr>
    `;
  }
}

customElements.define('jf-boot-phases-panel', BootPhasesPanel);
