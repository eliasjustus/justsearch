// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 629 — the read-only "Data protection" status card, extracted from HealthSurface so the new
 * Security & Privacy surface and Health can render the SAME card without duplicating its markup.
 *
 * Pure projection of the typed `/api/status` snapshot (`atRestProtection` + `conversationProtection`)
 * — no host, no fetch, no state. The card never over-claims: the index is only OS-disk-encrypted, an
 * unlocked window is readable, and configuration quality (TPM-only vs pre-boot PIN) shows
 * "unknown — needs admin" when it can't be sensed.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { icon } from '../../components/Icon.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';

/** Card CSS for surfaces that don't already define `.card`/`.data-row` (e.g. the Security surface). */
export const atRestCardStyles = css`
  .card {
    padding: 0.875rem;
    background: var(--surface-secondary);
    border: 1px solid var(--border-subtle);
    border-radius: 0.5rem;
  }
  .section h3 {
    margin: 0 0 0.5rem 0;
    font-size: var(--font-size-xs);
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: var(--text-secondary);
    font-weight: 600;
  }
  .data-row {
    display: flex;
    justify-content: space-between;
    padding: 0.375rem 0;
    font-size: var(--font-size-sm);
    border-bottom: 1px solid var(--border-subtle);
  }
  .data-row:last-of-type {
    border-bottom: none;
  }
  .data-row .key {
    color: var(--text-secondary);
  }
  .data-row .val {
    color: var(--text-primary);
    font-family: monospace;
  }
  .empty {
    padding: 1rem;
    text-align: center;
    color: var(--text-secondary);
    font-size: var(--font-size-sm);
  }
`;

/** Render the Data-protection card from the status snapshot, or `nothing` if at-rest data is absent. */
export function renderAtRestCard(status: StatusSnapshot | null): TemplateResult | typeof nothing {
  const ar = status?.atRestProtection;
  if (!ar) return nothing;
  const state = ar.diskEncryption ?? 'UNKNOWN';
  const encrypted = state === 'ENCRYPTED';
  const notEncrypted = state === 'NOT_ENCRYPTED';
  const pill = encrypted
    ? { label: 'Encrypted', css: 'background: var(--accent-success-16); color: var(--text-success);' }
    : notEncrypted
      ? { label: 'Not encrypted', css: 'background: var(--accent-warning-16); color: var(--text-warning);' }
      : state === 'ENCRYPTING'
        ? { label: 'Encrypting…', css: 'background: var(--surface-2); color: var(--text-secondary);' }
        : { label: 'Unknown', css: 'background: var(--surface-2); color: var(--text-secondary);' };
  const storeStatus = encrypted
    ? 'Protected by OS disk encryption'
    : notEncrypted
      ? 'Not encrypted'
      : 'Unknown';
  // Conversations carry an additional app-encryption dimension (passphrase), read from the REACTIVE
  // status field so the row can never go stale (629 FE design §4).
  const convState = status?.conversationProtection?.state;
  const convLabel =
    convState === 'unlocked'
      ? 'Encrypted (passphrase) · unlocked'
      : convState === 'locked'
        ? 'Encrypted (passphrase) · locked'
        : storeStatus;
  return html`
    <div class="card section">
      <h3>${icon({ name: 'shield', size: 12 })} Data protection
        <span
          style="float:right; padding: 0.125rem 0.5rem; border-radius: 0.25rem; font-weight: 500; ${pill.css}"
          >${pill.label}</span
        >
      </h3>
      <div class="data-row">
        <span class="key">Disk encryption</span>
        <span class="val">${pill.label}</span>
      </div>
      ${ar.source && ar.source !== 'none'
        ? html`
            <div class="data-row">
              <span class="key">Source</span>
              <span class="val">${ar.source}${ar.confidence ? html` · ${ar.confidence}` : nothing}</span>
            </div>
          `
        : nothing}
      <div class="data-row">
        <span class="key">Configuration</span>
        <span class="val">${ar.qualityKnown ? 'Known' : 'Unknown — needs admin'}</span>
      </div>
      <div class="data-row">
        <span class="key">Index</span>
        <span class="val">${storeStatus}</span>
      </div>
      <div class="data-row">
        <span class="key">Conversations</span>
        <span class="val">${convLabel}</span>
      </div>
      ${notEncrypted
        ? html`<div class="empty" style="padding: 0.5rem 0">
            Turn on device encryption (BitLocker with a startup PIN on Windows) to protect your data at
            rest. Note: Windows backs the recovery key up to your Microsoft account by default.
          </div>`
        : nothing}
      <!-- 629 (#12): honest "what's protected vs not" disclosure, projected from the typed status. -->
      <div class="empty" style="padding: 0.5rem 0; line-height: 1.5">
        <strong>What this protects:</strong> your chat history, memories, and agent runs are encrypted
        with your passphrase${convState === 'unlocked'
          ? ' — readable right now because they are unlocked'
          : convState === 'locked'
            ? ' and are currently locked'
            : ' once you set up chat encryption'}.
        Your search index is <em>not</em> passphrase-encrypted — it is protected only by your device's
        disk encryption (above) and rebuilds from your original files.
      </div>
    </div>
  `;
}
