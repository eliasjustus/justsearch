// SPDX-License-Identifier: Apache-2.0
/**
 * FolderCardRenderer — first-party `x-ui-renderer` hint renderer (569 §14 / the Library rollout).
 *
 * Renders the Library's indexed-folder cards (the 2nd real surface inverted after Settings) at the
 * bespoke quality LibrarySurface hand-authored — icon · status · path · meta · Remove — through the
 * projection engine, so the real Library is a projection of a declaration with no visual downgrade.
 *
 * CONTENT is projected from the pre-resolved card data (the surface owns async path resolution +
 * formatting); the one INTERACTION (Remove) is emitted as a named INTENT event `jf-folder-card-remove`
 * the surface handles (confirm dialog + the gated `core.remove-watched-root` operation) — the renderer
 * stays a pure projection, the operation+confirm stay the surface's (the §7 boundary).
 *
 * Schema fragment (hint on the array property):
 *   folders: { type:'array', 'x-ui-renderer':'folder-card',
 *     items:{ properties:{ pathHash, displayPath, status, metaText, walkError } } }
 *
 * Side-effect registers `'folder-card'` → `'jf-folder-card'` at module load.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import { registerXUiRenderer } from './xUiRendererRegistry.js';
import { icon } from '../../components/Icon.js';
import '../../components/Button.js';

interface FolderCard {
  readonly pathHash?: string;
  readonly displayPath?: string;
  readonly status?: string;
  readonly metaText?: string;
  readonly walkError?: string;
  /** Tempdoc 599 §16/B1 — failed-job count; >0 renders a clickable chip that opens the drill-down. */
  readonly failed?: number;
}

export class FolderCardRenderer extends JsonFormsRendererBase {
  static styles = css`
    :host {
      display: block;
    }
    .cards {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }
    .card {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      padding: 0.75rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      background: var(--surface-2);
    }
    .card-icon {
      flex: none;
      color: var(--text-secondary);
    }
    .card-info {
      flex: 1;
      min-inline-size: 0;
    }
    .card-path {
      display: flex;
      align-items: center;
      gap: 0.35rem;
      font-size: var(--font-size-sm);
      color: var(--text-primary);
    }
    .card-path span {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .card-meta {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.125rem;
    }
    .status-icon.indexed {
      color: var(--text-tint);
    }
    .status-icon.error {
      color: var(--text-danger);
    }
    .status-icon.pending {
      color: var(--text-secondary);
    }
    .status-icon.unavailable {
      color: var(--text-secondary);
    }
    .walk-error {
      color: var(--text-danger);
    }
    /* Tempdoc 599 §16/B1 — clickable "N failed" chip → opens the failed-files drawer. */
    .failed-chip {
      margin-left: 0.4rem;
      --jf-button-color: var(--text-danger);
      color: var(--text-danger);
    }
    .empty {
      padding: 1rem;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
  `;

  override render(): TemplateResult {
    if (!this.visible) return html``;
    const folders = Array.isArray(this.data) ? (this.data as FolderCard[]) : [];
    if (folders.length === 0) {
      return html`<div class="empty">No folders indexed yet.</div>`;
    }
    return html`<div class="cards" role="list">
      ${folders.map((f) => this.renderCard(f))}
    </div>`;
  }

  private statusIcon(status: string | undefined): TemplateResult {
    // Tempdoc 599 §16/A1 — 'unavailable' (folder path gone) is a muted x-circle, not the red error glyph.
    // Tempdoc 626 §Axis-C — 'unverified' (indexed but deletions couldn't be verified) is a muted
    // caution glyph, NOT the green ✓: the folder is searchable but its delete correspondence is unknown.
    const cls =
      status === 'indexed'
        ? 'indexed'
        : status === 'error'
          ? 'error'
          : status === 'unavailable'
            ? 'unavailable'
            : status === 'unverified'
              ? 'unverified'
              : 'pending';
    const name =
      status === 'indexed'
        ? 'check-circle-2'
        : status === 'error'
          ? 'alert-circle'
          : status === 'unavailable'
            ? 'x-circle'
            : status === 'unverified'
              ? 'alert-triangle'
              : 'clock';
    return html`<span class="status-icon ${cls}">${icon({ name, size: 16 })}</span>`;
  }

  private renderCard(f: FolderCard): TemplateResult {
    return html`<div class="card" role="listitem">
      <span class="card-icon">${icon({ name: 'folder', size: 24 })}</span>
      <div class="card-info">
        <div class="card-path">
          ${this.statusIcon(f.status)}<span title=${f.displayPath ?? ''}
            >${f.displayPath ?? '(resolving…)'}</span
          >
        </div>
        <div class="card-meta">
          ${f.metaText ?? ''}${f.walkError
            ? html` · <span class="walk-error">${f.walkError}</span>`
            : nothing}${(f.failed ?? 0) > 0
            ? html`<jf-button
                class="failed-chip"
                variant="ghost"
                size="sm"
                label=${`Show ${f.failed} failed file${f.failed === 1 ? '' : 's'}`}
                .onActivate=${() => this.emitShowFailed(f.pathHash ?? '')}
                >${icon({ name: 'alert-circle', size: 12 })} ${f.failed} failed</jf-button
              >`
            : nothing}
        </div>
      </div>
      <jf-button
        variant="danger"
        label="Remove"
        ?disabled=${!this.enabled}
        .onActivate=${() => this.emitRemove(f.pathHash ?? '')}
      >
        ${icon({ name: 'trash-2', size: 14 })} Remove
      </jf-button>
    </div>`;
  }

  /**
   * Emit the Remove INTENT — the surface owns the confirm dialog + the gated remove operation
   * (the renderer is a pure projection; the privileged action stays team/surface-owned, §7).
   */
  private emitRemove(pathHash: string): void {
    if (!pathHash) return;
    this.dispatchEvent(
      new CustomEvent<{ pathHash: string }>('jf-folder-card-remove', {
        detail: { pathHash },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /**
   * Emit the show-failed INTENT (tempdoc 599 §16/B1) — the surface opens the per-folder failed-files
   * drawer; the renderer stays a pure projection (mirrors {@link #emitRemove}).
   */
  private emitShowFailed(pathHash: string): void {
    if (!pathHash) return;
    this.dispatchEvent(
      new CustomEvent<{ pathHash: string }>('jf-folder-card-show-failed', {
        detail: { pathHash },
        bubbles: true,
        composed: true,
      }),
    );
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-folder-card')) {
  customElements.define('jf-folder-card', FolderCardRenderer);
}

registerXUiRenderer('folder-card', 'jf-folder-card');
