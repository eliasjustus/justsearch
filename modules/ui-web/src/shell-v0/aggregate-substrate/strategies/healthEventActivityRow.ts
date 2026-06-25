// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 511-followup-A — Canonical (HealthEvent, activity-row) strategy.
 *
 * Renders a wire-shape HealthEvent as a row in HealthSurface's
 * "Recent events" list. Reads:
 *  - `severity` ('INFO' | 'WARNING' | 'ERROR') → row CSS class
 *    ('info' | 'warning' | 'error', lowercase — matches HealthSurface's
 *    existing .event-row.<class> CSS).
 *  - `i18nKey` → title text via localizeResourceKey; falls back to
 *    `id` if the key isn't in the catalog or unset.
 *  - `body` (HealthEventBodyUnion) → message text, variant-aware:
 *      - 'condition' (AssertedCondition): message | reason | status.
 *      - 'lifecycle' (LifecycleEvent): attributes.message |
 *        attributes.disposition | flattened attributes.
 *      - 'threshold' (ThresholdState): message | phase + magnitudes.
 *  - `source.serviceName` → secondary chip (this metadata was
 *    dropped by the pre-followup-A local-shape rendering).
 *  - `timestamp` (ISO string) → relative time via formatRelativeIso.
 *  - `id` → DOM tooltip + DOM key.
 *
 * Before this strategy shipped, HealthSurface.ts:79 declared a local
 * `{title, message, level, timestamp}` interface and cast the SSE
 * payload to it. The wire emits a structurally-different
 * `{severity, source, i18nKey, body}` (per HeadHealthEventsEmitter +
 * LifecycleSnapshotTap). Result: every event row rendered with
 * undefined fields. This strategy reads the wire fields directly;
 * the migration in HealthSurface.ts switches renderEvents to mount
 * <jf-health-event> per event.
 *
 * Generated-type Pass-8 note: wire-types.ts emits every HealthEvent
 * field as optional, so a strategy could compile that reads only
 * `id`. The behavioral Pass-8 mirror in
 * healthEventActivityRow.test.ts is the load-bearing gate: it
 * mutates each of the 6 wire fields and asserts the rendered
 * output diff matches the declared role.
 */

import { html, nothing } from 'lit';
import type {
  HealthEvent,
  AssertedCondition,
  LifecycleEvent,
  ThresholdState,
  Severity,
} from '../../../api/generated/index.js';
import type { AggregateStrategy } from '../aggregateRegistry.js';
import { registerAggregateStrategy } from '../aggregateRegistry.js';
import { assertFieldRoles, type FieldRoles } from '../assertExhaustive.js';
import { present } from '../../display/present.js';
import { formatRelativeIso } from '../../../utils/relativeTime.js';

/**
 * Field-role classification — drives the behavioral Pass-8 test.
 * Every HealthEvent wire field is `visual` (all 6 are consumed by
 * the row's rendered output).
 */
export const HEALTH_EVENT_ACTIVITY_ROW_ROLES: FieldRoles<HealthEvent> = {
  id: 'visual',          // tooltip + DOM key
  timestamp: 'visual',   // relative-time display
  source: 'visual',      // serviceName chip
  severity: 'visual',    // row severity class
  i18nKey: 'visual',     // title text
  body: 'visual',        // message text (variant-aware)
};
assertFieldRoles<HealthEvent>(HEALTH_EVENT_ACTIVITY_ROW_ROLES);

function severityToClass(severity: Severity | undefined): 'error' | 'warning' | 'info' {
  if (severity === 'ERROR') return 'error';
  if (severity === 'WARNING') return 'warning';
  return 'info';
}

/**
 * Per-variant message extraction. Returns an empty string when the
 * variant carries no human-readable message; the row degrades to
 * showing just the title in that case.
 */
function bodyToMessage(body: HealthEvent['body']): string {
  if (!body) return '';
  switch (body.kind) {
    case 'condition': {
      const c = body as AssertedCondition;
      return c.message ?? c.reason ?? (c.status ? `status: ${c.status}` : '');
    }
    case 'lifecycle': {
      const l = body as LifecycleEvent;
      const attrs = l.attributes;
      if (attrs && typeof attrs === 'object') {
        const msg = typeof attrs['message'] === 'string' ? (attrs['message'] as string) : null;
        if (msg) return msg;
        const disposition =
          typeof attrs['disposition'] === 'string' ? (attrs['disposition'] as string) : null;
        if (disposition) return `disposition: ${disposition}`;
        // Last resort: flatten the attributes map.
        const flat = Object.entries(attrs)
          .map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : String(v)}`)
          .join(', ');
        return flat;
      }
      return '';
    }
    case 'threshold': {
      const t = body as ThresholdState;
      if (t.message) return t.message;
      const phase = t.phase ? `phase: ${t.phase}` : '';
      const mags = t.magnitudes
        ? Object.entries(t.magnitudes)
            .map(([k, v]) => `${k}=${v}`)
            .join(', ')
        : '';
      return [phase, mags].filter(Boolean).join(' / ');
    }
    default:
      return '';
  }
}

export const healthEventActivityRowStrategy: AggregateStrategy<
  'HealthEvent',
  'activity-row'
> = (event, _ctx, _host) => {
  if (!event.id) return nothing;
  const severityClass = severityToClass(event.severity);
  const title = event.i18nKey
    ? present({ kind: 'resource', key: event.i18nKey }).label || event.id
    : event.id;
  const message = bodyToMessage(event.body);
  const sourceName = event.source?.serviceName ?? '';
  const timestamp = event.timestamp ?? '';
  const relativeTime = timestamp ? formatRelativeIso(timestamp) : '';
  const bodyKind = event.body?.kind ?? 'unknown';

  return html`
    <div
      class="event-row ${severityClass}"
      data-severity=${event.severity ?? 'INFO'}
      data-body-kind=${bodyKind}
      title=${event.id}
    >
      <div class="event-message">
        <strong>${title}</strong>
        ${message ? html` — ${message}` : nothing}
        ${sourceName
          ? html`<span class="event-source"> · ${sourceName}</span>`
          : nothing}
      </div>
      <div class="event-time">${relativeTime}</div>
    </div>
  `;
};

export function registerHealthEventActivityRowStrategy(): () => void {
  return registerAggregateStrategy({
    aggregate: 'HealthEvent',
    context: 'activity-row',
    rank: 0,
    strategy: healthEventActivityRowStrategy,
    source: 'core',
  });
}

void healthEventActivityRowStrategy;
