// Positive fixture — composes the registered atom rather than hand-rolling a
// badge look, so the scan finds zero raw atom-class base rules.
import { html } from 'lit';
export const view = () => html`<jf-status-badge tone="success">healthy</jf-status-badge>`;
