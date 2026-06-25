// SPDX-License-Identifier: Apache-2.0
/**
 * GovernanceView — tempdoc 576 §15 / 530 Layer 3-4: the user-facing governance dashboard surface.
 *
 * The human read-view of the discipline-gate kernel. It is a pure PROJECTION of GET
 * /api/governance/state (itself a projection of the registry + baselines — one projector, not a fork):
 * the gate roster (id · title · tier), the exception-ledger ceiling (rung-4 meta-ratchet), the per-seam
 * mutation-strength floors (rung-2), and the class-size discipline-debt. Read-only — no controls,
 * nothing to mutate; it makes the enforcement ladder LEGIBLE (530 Layer 3's "make the ladder legible"),
 * the half the enforcement rungs left unbuilt.
 *
 * Composes SurfaceLayout (Authority I) and emits no own page-title heading or main landmark (the shell
 * owns those; tempdoc 578 a11y-closure) — only sections. Tokens only (no accent-as-text, §6 ban). Degrades to
 * a confident empty/error state (404 when the governance-state.json resource is not bundled).
 *
 * Side-effect registers <jf-governance-view> for the chrome dispatcher.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { surfaceScrollLayoutStyles } from '../primitives/surfaceLayout.js';
import '../components/StatusDot.js';

interface GateRow {
  id: string;
  title: string;
  tier: string;
  hasChangesets: boolean;
}
// Tempdoc 622 §17 — per-gate activation efficacy from the local-runtime gate history.
interface GateEfficacy {
  gate: string;
  totalRuns: number;
  runsWithFindings: number;
  error: number;
  warning: number;
  note: number;
  lastVerdict?: string;
  lastTs?: string;
  status: 'active' | 'never-fired' | 'orphaned';
}
interface EfficacyProjection {
  available: boolean;
  scope?: string;
  byGate: GateEfficacy[];
}
interface StrengthFloor {
  id: string;
  minStrength: number;
  maxNoCoverage: number;
}
interface ClassSizeDebt {
  files: number;
  ceiling?: number;
  totalDebt: number;
  worst: Array<{ path: string; pinned: number; over: number }>;
}
interface GovernanceState {
  schema: string;
  gateCount: number;
  gates: GateRow[];
  exceptions: { maxExceptions: number | null };
  strengthFloors: StrengthFloor[];
  classSizeDebt: ClassSizeDebt;
}

type LoadState =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'ready'; state: GovernanceState };

export class GovernanceView extends JfElement {
  static properties = {
    apiBase: { type: String, attribute: 'api-base' },
  } as const;

  declare apiBase: string;
  private load: LoadState = { kind: 'loading' };
  // Joined by gate id; empty when the local history is absent (clean checkout / CI).
  private efficacyByGate = new Map<string, GateEfficacy>();

  constructor() {
    super();
    this.apiBase = '';
  }

  override connectedCallback(): void {
    super.connectedCallback();
    void this.fetchState();
  }

  private async fetchState(): Promise<void> {
    this.load = { kind: 'loading' };
    this.requestUpdate();
    try {
      const res = await fetch(`${this.apiBase}/api/governance/state`, {
        headers: { accept: 'application/json' },
      });
      if (res.status === 404) {
        this.load = { kind: 'error', message: 'Governance state is not available in this build.' };
        this.requestUpdate();
        return;
      }
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`);
      }
      // GET /api/governance/state (GovernanceStateController) returns { source, available, gates, …,
      // registry: <the committed projection> }. The dashboard reads the always-present `registry`.
      const body = (await res.json()) as {
        registry?: GovernanceState;
        efficacy?: EfficacyProjection;
      };
      const state = body.registry;
      if (!state || !Array.isArray(state.gates)) {
        this.load = { kind: 'error', message: 'Governance projection is not available in this build.' };
        this.requestUpdate();
        return;
      }
      // Tempdoc 622 §17 — join the local-runtime activation efficacy onto the roster (by gate id).
      this.efficacyByGate = new Map(
        (body.efficacy?.byGate ?? []).map((e) => [e.gate, e]),
      );
      this.load = { kind: 'ready', state };
    } catch (e) {
      this.load = { kind: 'error', message: e instanceof Error ? e.message : String(e) };
    }
    this.requestUpdate();
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
      td.num {
        text-align: right;
        font-variant-numeric: tabular-nums;
      }
      .tier {
        color: var(--text-muted);
      }
      .yes {
        color: var(--text-success);
      }
      .empty {
        color: var(--text-muted);
      }
      .disclaimer {
        color: var(--text-muted);
        margin: 0 0 0.6rem;
        max-width: 70ch;
      }
      .activation {
        display: inline-flex;
        align-items: center;
        gap: 0.4rem;
      }
      .eff-label {
        color: var(--text-secondary);
      }
      code {
        font-family: var(--jf-font-mono);
        color: var(--text-secondary);
      }
    `,
  ];

  override render(): TemplateResult {
    if (this.load.kind === 'loading') {
      return html`<section><p class="empty">Loading governance state…</p></section>`;
    }
    if (this.load.kind === 'error') {
      return html`<section>
        <p class="empty">${this.load.message}</p>
      </section>`;
    }
    const s = this.load.state;
    return html`
      <p class="intro">
        The discipline-gate kernel, made legible: a read-only projection of the governance registry and
        its committed baselines (tempdoc 576 / 530 Layer 3-4). Not live verdicts — the kernel runs at
        build time.
      </p>
      <div class="summary">
        ${this.stat(String(s.gateCount), 'gates')}
        ${this.stat(s.exceptions.maxExceptions == null ? '—' : String(s.exceptions.maxExceptions), 'exception ceiling')}
        ${this.stat(String(s.strengthFloors.length), 'mutation-floored seams')}
        ${this.stat(String(s.classSizeDebt.totalDebt), 'class-size debt (LOC)')}
      </div>

      <section>
        <h2>Gates (${s.gateCount})</h2>
        <p class="disclaimer">
          Activation reflects <strong>local</strong> gate runs only (this machine's
          <code>governance-history</code>). CI and other contributors' runs aren't captured, so
          "never (local)" means unexercised here — not dead.
        </p>
        ${this.gateTable(s.gates)}
      </section>

      <section>
        <h2>Mutation-strength floors (rung 2)</h2>
        ${this.strengthTable(s.strengthFloors)}
      </section>

      <section>
        <h2>Class-size discipline-debt (rung 4)</h2>
        ${this.debtBlock(s.classSizeDebt)}
      </section>
    `;
  }

  private stat(value: string, label: string): TemplateResult {
    return html`<div class="stat">
      <div class="value">${value}</div>
      <div class="label">${label}</div>
    </div>`;
  }

  private gateTable(gates: GateRow[]): TemplateResult {
    if (gates.length === 0) return html`<p class="empty">No gates registered.</p>`;
    return html`
      <table>
        <thead>
          <tr>
            <th>Gate</th>
            <th>Title</th>
            <th>Tier</th>
            <th>Changeset-gated</th>
            <th>Activation</th>
          </tr>
        </thead>
        <tbody>
          ${gates.map(
            (g) => html`<tr>
              <td><code>${g.id}</code></td>
              <td>${g.title}</td>
              <td class="tier">${g.tier}</td>
              <td>${g.hasChangesets ? html`<span class="yes">yes</span>` : html`<span class="empty">—</span>`}</td>
              <td>${this.renderActivation(g.id)}</td>
            </tr>`,
          )}
        </tbody>
      </table>
    `;
  }

  /**
   * Tempdoc 622 §17 — the per-gate activation indicator: a tone dot (from the statusTone authority)
   * + a plain-text label. "Earning its keep" (ever found something) → ok tone; silent pass / never-run
   * locally → neutral (NOT a failure — see the disclaimer; CI/prod runs aren't captured); a gate in
   * history but not the roster → warning (registry drift). Absent efficacy (no local history) → em-dash.
   */
  private renderActivation(gateId: string): TemplateResult {
    const e = this.efficacyByGate.get(gateId);
    if (!e) return html`<span class="empty">—</span>`;
    let tone: 'ok' | 'neutral' | 'warning';
    let text: string;
    if (e.status === 'orphaned') {
      tone = 'warning';
      text = 'orphaned';
    } else if (e.totalRuns === 0) {
      tone = 'neutral';
      text = 'never (local)';
    } else if (e.runsWithFindings > 0) {
      tone = 'ok';
      text = e.error > 0 ? `${e.totalRuns} runs · ${e.error} err` : `${e.totalRuns} runs · findings`;
    } else {
      tone = 'neutral';
      text = `${e.totalRuns} runs · silent`;
    }
    return html`<span class="activation">
      <jf-status-dot status=${tone} size="sm"></jf-status-dot>
      <span class="eff-label">${text}</span>
    </span>`;
  }

  private strengthTable(floors: StrengthFloor[]): TemplateResult {
    if (floors.length === 0) return html`<p class="empty">No seams under a mutation-strength floor.</p>`;
    return html`
      <table>
        <thead>
          <tr>
            <th>Seam</th>
            <th class="num">Min strength</th>
            <th class="num">Max no-coverage</th>
          </tr>
        </thead>
        <tbody>
          ${floors.map(
            (f) => html`<tr>
              <td><code>${f.id}</code></td>
              <td class="num">${f.minStrength}%</td>
              <td class="num">${f.maxNoCoverage}</td>
            </tr>`,
          )}
        </tbody>
      </table>
    `;
  }

  private debtBlock(debt: ClassSizeDebt): TemplateResult {
    return html`
      <div class="summary">
        ${this.stat(String(debt.files), 'ratcheted files')}
        ${this.stat(String(debt.totalDebt), `LOC over ceiling${debt.ceiling ? ` (${debt.ceiling})` : ''}`)}
      </div>
      ${debt.worst && debt.worst.length > 0
        ? html`<table>
            <thead>
              <tr>
                <th>Worst offenders</th>
                <th class="num">Pinned</th>
                <th class="num">Over</th>
              </tr>
            </thead>
            <tbody>
              ${debt.worst.map(
                (r) => html`<tr>
                  <td><code>${r.path}</code></td>
                  <td class="num">${r.pinned}</td>
                  <td class="num">${r.over}</td>
                </tr>`,
              )}
            </tbody>
          </table>`
        : nothing}
    `;
  }
}

if (!customElements.get('jf-governance-view')) {
  customElements.define('jf-governance-view', GovernanceView);
}
