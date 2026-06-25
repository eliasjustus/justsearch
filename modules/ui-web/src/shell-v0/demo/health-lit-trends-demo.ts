// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 3a.1.4b cohort follow-up: visual-isolation surface for the four TIMESERIES
 * Resources. Bypasses HealthLitView so the trend Resources can be observed against
 * a live backend without Condition-driven dispatch.
 *
 * Usage:
 *   cd modules/ui-web && npm run dev
 *   # http://localhost:<port>/?lit-health-trends=1
 *
 * Each Resource's snapshot is fetched once on mount via `/api/metrics/{name}` and
 * bound to a `<jf-timeseries-sparkline>` element. Empty windows render the
 * graceful "<2 values returns nothing" state per the TimeseriesPolyline contract;
 * a "no data yet" caption surfaces in that case so the surface stays useful even
 * when the RRD is cold.
 *
 * This is the substrate-isolation route promised by slice 3a.1.4 §B.10 and
 * delivered as part of the §B.K cohort follow-up.
 */

import { Shell } from '../shell/Shell.js';
import '../views/TimeseriesSparkline.js';
import '../themes/default.css';
import type { TimeseriesSnapshot } from '../../api/generated/index.js';
import { localizeResourceKey } from '../../i18n/resourceCatalog.js';

interface TrendDescriptor {
  resourceId: string;
  endpoint: string;
  labelKey: string;
}

const TRENDS: ReadonlyArray<TrendDescriptor> = [
  {
    resourceId: 'core.metric-worker-job-queue-depth',
    endpoint: '/api/metrics/worker.job_queue.depth',
    labelKey: 'registry-resource.metric.job-queue-depth.label',
  },
  {
    resourceId: 'core.metric-worker-documents-indexed-rate-per-sec',
    endpoint: '/api/metrics/worker.documents.indexed.rate_per_sec',
    labelKey: 'registry-resource.metric.documents-indexed-rate.label',
  },
  {
    resourceId: 'core.metric-gpu-utilization-percent',
    endpoint: '/api/metrics/gpu.utilization.percent',
    labelKey: 'registry-resource.metric.gpu-utilization.label',
  },
  {
    resourceId: 'core.metric-gpu-memory-utilization-percent',
    endpoint: '/api/metrics/gpu.memory.utilization.percent',
    labelKey: 'registry-resource.metric.gpu-memory-utilization.label',
  },
];

async function fetchSnapshot(endpoint: string): Promise<TimeseriesSnapshot | null> {
  try {
    const resp = await fetch(endpoint);
    if (!resp.ok) return null;
    const json = (await resp.json()) as { snapshot?: TimeseriesSnapshot };
    return json.snapshot ?? null;
  } catch {
    return null;
  }
}

function renderTrend(descriptor: TrendDescriptor): HTMLElement {
  const wrapper = document.createElement('div');
  wrapper.style.display = 'flex';
  wrapper.style.flexDirection = 'column';
  wrapper.style.gap = '8px';
  wrapper.style.padding = '12px';
  wrapper.style.borderBottom = '1px solid #ddd';

  const heading = document.createElement('div');
  heading.style.fontWeight = '600';
  heading.style.fontSize = '14px';
  heading.textContent = localizeResourceKey(descriptor.labelKey);
  wrapper.appendChild(heading);

  const subtext = document.createElement('div');
  subtext.style.fontSize = '11px';
  subtext.style.color = '#777';
  subtext.style.fontFamily = 'monospace';
  subtext.textContent = `${descriptor.resourceId} · ${descriptor.endpoint}`;
  wrapper.appendChild(subtext);

  const sparkContainer = document.createElement('div');
  sparkContainer.style.minHeight = '24px';
  sparkContainer.textContent = 'Loading…';
  wrapper.appendChild(sparkContainer);

  void fetchSnapshot(descriptor.endpoint).then((snapshot) => {
    sparkContainer.textContent = '';
    if (!snapshot || !snapshot.values || snapshot.values.length < 2) {
      const empty = document.createElement('span');
      empty.style.color = '#999';
      empty.style.fontStyle = 'italic';
      empty.textContent = 'No data yet — RRD store has not accumulated samples.';
      sparkContainer.appendChild(empty);
      return;
    }
    const sparkline = document.createElement('jf-timeseries-sparkline');
    (sparkline as unknown as { snapshot: TimeseriesSnapshot }).snapshot = snapshot;
    sparkline.setAttribute('label', heading.textContent ?? descriptor.resourceId);
    sparkContainer.appendChild(sparkline);
  });

  return wrapper;
}

export function mountHealthLitTrendsDemo(host: HTMLElement): Shell {
  document.documentElement.style.height = '100%';
  document.body.style.height = '100%';
  document.body.style.margin = '0';
  host.style.position = 'absolute';
  host.style.inset = '0';
  host.style.padding = '0';
  host.style.margin = '0';

  const shell = new Shell();

  const container = document.createElement('div');
  container.style.padding = '8px';
  container.style.display = 'flex';
  container.style.flexDirection = 'column';

  const intro = document.createElement('div');
  intro.style.padding = '12px';
  intro.style.fontSize = '12px';
  intro.style.color = '#555';
  intro.textContent =
    'Substrate-isolation route (slice 3a.1.4b). Each TIMESERIES Resource is fetched ' +
    'once and bound to <jf-timeseries-sparkline>. Empty windows render the graceful ' +
    '<2-values fallback state.';
  container.appendChild(intro);

  for (const descriptor of TRENDS) {
    container.appendChild(renderTrend(descriptor));
  }

  shell.addPane({
    id: 'health-trends',
    title: 'Trend Resources (substrate isolation)',
    content: container,
    closable: false,
  });

  shell.attachTo(host);
  return shell;
}
