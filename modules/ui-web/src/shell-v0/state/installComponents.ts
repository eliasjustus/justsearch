// SPDX-License-Identifier: Apache-2.0
/**
 * installComponents.ts — tempdoc 840 Phase 5: the ONE derivation behind the user-facing side of the
 * AI model install.
 *
 * Before this module the install was a single undifferentiated ~7 GB wait: a percentage, a byte
 * counter, no rate, no ETA, and nothing naming what any of it was for. The backend has known far
 * more than that since Phases 0-4 — a standing per-component list on
 * `GET /api/ai/install/plan-preview` (`components[]`), an ordered stage plan with per-stage
 * capabilities on `GET /api/ai/install/status` (`stages[]` / `readyCapabilities`), and a measured
 * transfer rate + horizon. This module projects those wire facts into the shapes three surfaces
 * render (the Brain panel, the System Self-View, the task tray) so the SAME fact is never derived
 * twice with two different answers.
 *
 * Two properties are load-bearing and are why the composition lives here rather than inline in a
 * template:
 *
 *  1. **`-1` is UNKNOWN, and an unknown segment is OMITTED.** `AiInstallStatus.bytesPerSecond` /
 *     `remainingSeconds` carry `-1` as an explicit sentinel and never `0` (see the field javadoc:
 *     "a fabricated `0 B/s` on a transfer that is merely young is the most confident lie the surface
 *     could tell"). {@link composeTransferLine} therefore builds its line the way `TaskList`'s
 *     counts-line does — every segment past the first is APPENDED and each is withdrawn ENTIRELY
 *     when there is no honest basis for it — reusing `humanizeSeconds`, the one duration formatter,
 *     rather than adding a second.
 *
 *  2. **`unavailable` is not `declined`.** Hardware this machine lacks is not a choice the user
 *     made, so it gets the typed `unavailable{reason}` availability and NO control, while a
 *     declinable component gets a real opt-OUT control. Rendering the two the same way would imply
 *     an option that does not exist.
 */

import { formatBytes } from '../display/format.js';
import { humanizeSeconds } from './startupEstimate.js';
import { AVAILABLE, unavailableBecause, type Availability } from './availability.js';
import type { AiInstallStatus } from '../../api/generated/schema-types/ai-install-status.js';

/** `GET /api/ai/install/plan-preview` → `components[]` (app-api `InstallPlanPreview.ComponentEstimate`). */
export interface InstallComponentEstimate {
  id?: string;
  label?: string;
  description?: string;
  tier?: string;
  necessity?: string;
  declinable?: boolean;
  declined?: boolean;
  totalBytes?: number;
  downloadBytes?: number;
  state?: string;
  unavailableReason?: string;
}

/**
 * The four necessity categories (`Necessity.java`), in the order they are presented. The order is
 * the answer to "what do I lose if I say no?", loudest first — infrastructure last because it is the
 * only one that is not a user-facing capability at all.
 */
const NECESSITY_ORDER = [
  'required',
  'improves-results',
  'adds-feature',
  'infrastructure',
] as const;

export type Necessity = (typeof NECESSITY_ORDER)[number];

/** The category heading — the registry's own label, kept short enough for a group header. */
const NECESSITY_HEADING: Record<Necessity, string> = {
  required: 'Required',
  'improves-results': 'Improves results',
  'adds-feature': 'Adds a feature',
  infrastructure: 'Infrastructure',
};

/**
 * What the category MEANS, in consequence terms. This is the whole point of the necessity axis: a
 * user who turns off the reranker to save 340 MB gets measurably worse search, and no percentage or
 * byte counter can tell them that. The wording is the consequence, never the mechanism.
 */
export const NECESSITY_CONSEQUENCE: Record<Necessity, string> = {
  required: 'Search does not work without this.',
  'improves-results': 'Search works; results are worse without it.',
  'adds-feature': 'Search is unaffected; you lose chat and summaries.',
  infrastructure: 'Plumbing, not a capability you use directly.',
};

/**
 * An unrecognized necessity id must not become silently switch-off-able — the same conservative
 * mapping `Necessity.fromId`'s callers apply on the backend (a category this build does not know
 * leaves the package mandatory).
 */
export function toNecessity(id: string | null | undefined): Necessity {
  const found = NECESSITY_ORDER.find((n) => n === id);
  return found ?? 'required';
}

/** `ComponentEstimate.state` — derived from the plan, so it cannot disagree with what an install does. */
type ComponentState =
  | 'installed'
  | 'to-download'
  | 'declined'
  | 'unavailable'
  | 'not-in-mode';

const COMPONENT_STATE_TEXT: Record<ComponentState, string> = {
  installed: 'Installed',
  'to-download': 'Will download',
  declined: 'Turned off',
  unavailable: 'Not supported here',
  'not-in-mode': 'Not used in this mode',
};

/** One rendered component row. Everything a template needs, already decided. */
export interface ComponentRow {
  readonly id: string;
  readonly label: string;
  readonly description: string;
  readonly necessity: Necessity;
  readonly state: ComponentState;
  readonly stateText: string;
  /** Full footprint at the selected profile, or `null` when the registry declares no size. */
  readonly sizeText: string | null;
  /**
   * Opt-OUT, not opt-in: everything this machine supports starts SELECTED and the user unticks what
   * they do not want. Opt-in would leave most users on a degraded retrieval stack they never
   * knowingly chose.
   */
  readonly selected: boolean;
  /** True only for a component the user may actually turn off — no control is rendered otherwise. */
  readonly togglable: boolean;
  /**
   * The typed availability for the row's control. `unavailable{reason}` for hardware this machine
   * lacks (a reachable reason, no choice implied); `AVAILABLE` otherwise. Deliberately NOT `blocked`
   * — that kind is reserved for intent gates.
   */
  readonly availability: Availability;
}

/** One necessity group, with its heading and its consequence sentence. */
export interface ComponentGroup {
  readonly necessity: Necessity;
  readonly heading: string;
  readonly consequence: string;
  readonly rows: readonly ComponentRow[];
}

function toComponentState(state: string | null | undefined): ComponentState {
  switch (state) {
    case 'installed':
    case 'to-download':
    case 'declined':
    case 'unavailable':
    case 'not-in-mode':
      return state;
    default:
      // An unrecognized state is not evidence the component is present. "Will download" is the
      // least-wrong reading of "wanted, whereabouts unknown" and never claims something is installed.
      return 'to-download';
  }
}

/**
 * Project one wire component onto its row.
 *
 * `unavailable` is the case this function exists for. It carries the planner's own reason (it
 * already writes user-facing prose naming the actual constraint) and NO control, because an unticked
 * box implies a choice; `declined` — a choice the user really made — keeps its control so it can be
 * undone.
 */
export function composeComponentRow(c: InstallComponentEstimate): ComponentRow {
  const state = toComponentState(c.state);
  const necessity = toNecessity(c.necessity);
  const totalBytes = c.totalBytes ?? 0;
  const unavailable = state === 'unavailable';
  const reason =
    c.unavailableReason && c.unavailableReason.trim().length > 0
      ? c.unavailableReason.trim()
      : 'This component needs hardware this machine does not have.';
  return {
    id: c.id ?? '',
    label: c.label && c.label.length > 0 ? c.label : (c.id ?? 'component'),
    description: c.description ?? '',
    necessity,
    state,
    stateText: COMPONENT_STATE_TEXT[state],
    sizeText: totalBytes > 0 ? formatBytes(totalBytes) : null,
    selected: !(c.declined === true) && state !== 'declined',
    // `not-in-mode` is the intent axis, not the user's — offering a tick would promise a component
    // the active mode does not install whatever the tick says.
    togglable: c.declinable === true && !unavailable && state !== 'not-in-mode',
    availability: unavailable ? unavailableBecause(reason) : AVAILABLE,
  };
}

/**
 * Group the standing component list by necessity, in category order, dropping empty categories.
 *
 * Sourced from the PREVIEW deliberately: `status.packages` is run bookkeeping and comes back empty
 * on an idle machine, so a surface that asked it "what components are there" before the first
 * install would be told "none".
 */
export function composeComponentGroups(
  components: readonly InstallComponentEstimate[] | null | undefined,
): ComponentGroup[] {
  const rows = (components ?? []).map(composeComponentRow);
  const groups: ComponentGroup[] = [];
  for (const necessity of NECESSITY_ORDER) {
    const inGroup = rows.filter((r) => r.necessity === necessity);
    if (inGroup.length === 0) continue;
    groups.push({
      necessity,
      heading: NECESSITY_HEADING[necessity],
      consequence: NECESSITY_CONSEQUENCE[necessity],
      rows: inGroup,
    });
  }
  return groups;
}

/**
 * The measured transfer line — rate and time remaining — or `null` when neither is known.
 *
 * Both inputs use `-1` as an explicit unknown sentinel and are reset to it on every non-running
 * state, so an ended run never leaves a rate describing a transfer that is no longer happening. A
 * `0` rate is treated as unknown too: it is not on the wire by contract, and rendering "0 B/s" over
 * a transfer that is merely young is exactly the lie the sentinel exists to prevent. `0` seconds
 * remaining IS a value (nothing left to move) and is rendered.
 */
export function composeTransferLine(
  status: Pick<AiInstallStatus, 'bytesPerSecond' | 'remainingSeconds'> | null | undefined,
): string | null {
  const segments: string[] = [];
  const bps = status?.bytesPerSecond;
  if (typeof bps === 'number' && bps > 0) {
    segments.push(`${formatBytes(Math.round(bps))}/s`);
  }
  const remaining = status?.remainingSeconds;
  if (typeof remaining === 'number' && remaining >= 0) {
    segments.push(`~${humanizeSeconds(remaining)} left`);
  }
  return segments.length === 0 ? null : segments.join(' · ');
}

/** `AiInstallStatus.StageStatus.state` — the `StagedAcquisition.StageState` vocabulary plus `pending`. */
type StageState =
  | 'pending'
  | 'running'
  | 'completed'
  | 'skipped'
  | 'failed'
  | 'cancelled'
  | 'blocked';

const STAGE_STATE_TEXT: Record<StageState, string> = {
  pending: 'Waiting',
  running: 'Downloading',
  completed: 'Done',
  skipped: 'Nothing to do',
  failed: 'Failed',
  cancelled: 'Stopped',
  blocked: 'Cannot start',
};

function toStageState(state: string | null | undefined): StageState {
  switch (state) {
    case 'pending':
    case 'running':
    case 'completed':
    case 'skipped':
    case 'failed':
    case 'cancelled':
    case 'blocked':
      return state;
    default:
      return 'pending';
  }
}

/** One rendered stage row of the staged acquisition. */
export interface StageRow {
  readonly id: string;
  readonly label: string;
  readonly state: StageState;
  readonly stateText: string;
  /** True for the stage the run is currently in (`status.currentStage`). */
  readonly current: boolean;
  /** 0..100, or `null` when the stage declares no byte total (nothing honest to draw). */
  readonly percent: number | null;
  readonly bytesText: string | null;
  /**
   * Why a stage was refused BEFORE it started — today, not enough disk space. Actionable by the user,
   * and distinct from a failure: nothing was attempted, so nothing broke.
   */
  readonly blockedReason: string | null;
  readonly capabilities: readonly string[];
}

export function composeStageRows(status: AiInstallStatus | null | undefined): StageRow[] {
  const current = status?.currentStage ?? '';
  return (status?.stages ?? []).map((s) => {
    const total = s.totalBytes ?? 0;
    const done = s.downloadedBytes ?? 0;
    const state = toStageState(s.state);
    const blocked = s.blockedReason && s.blockedReason.trim().length > 0 ? s.blockedReason.trim() : null;
    return {
      id: s.stage ?? '',
      label: s.label && s.label.length > 0 ? s.label : (s.stage ?? 'stage'),
      state,
      stateText: STAGE_STATE_TEXT[state],
      current: !!s.stage && s.stage === current,
      percent: total > 0 ? Math.min(100, Math.max(0, Math.floor((done / total) * 100))) : null,
      bytesText: total > 0 ? `${formatBytes(done)} / ${formatBytes(total)}` : null,
      blockedReason: blocked,
      capabilities: s.capabilities ?? [],
    };
  });
}

/**
 * The capability id the retrieval core delivers (`CapabilityTier.RETRIEVAL_CORE.id()`), which the
 * CORE stage publishes on `StageStatus.capabilities` and `readyCapabilities` gains once that stage
 * has both ended in a configured state and landed every package it owns.
 */
const RETRIEVAL_CORE_CAPABILITY = 'retrieval-core';

/**
 * "You can search now, the rest is still downloading" — the entire point of the staged work, and
 * only sayable once the backend says the retrieval core is READY. Returns `null` while it is not, so
 * the surface stays silent rather than promising a capability that is not live.
 */
export function searchReadyNotice(status: AiInstallStatus | null | undefined): string | null {
  const ready = status?.readyCapabilities ?? [];
  if (!ready.includes(RETRIEVAL_CORE_CAPABILITY)) return null;
  const stages = composeStageRows(status);
  const remaining = stages.filter((s) => s.state === 'pending' || s.state === 'running');
  return remaining.length === 0
    ? 'Search is ready.'
    : `Search is ready — you can use it now while the rest downloads (${remaining
        .map((s) => s.label)
        .join(', ')}).`;
}

/**
 * U5 — the install phase message names a filesystem path
 * (`Downloading onnx/gte-multilingual-base/model.onnx...`) because the backend's
 * `AcquisitionScheduler.Item.id()` IS the download's target path, while the component list beside it
 * has friendly labels. Map the path onto the label of the package the run currently has in flight.
 *
 * The mapping goes through the package the backend marked `downloading` rather than by parsing the
 * path, because `updatePackageState(item.packageId(), "downloading")` is set on the same callback
 * that writes this message — so the two cannot disagree. With no in-flight package to name, the
 * original message stands: a wrong friendly label is worse than an ugly true one.
 */
export function friendlyInstallMessage(
  message: string | null | undefined,
  packages: AiInstallStatus['packages'] | null | undefined,
): string {
  const raw = message ?? '';
  const match = /^Downloading\s+(.+?)\.\.\.(.*)$/s.exec(raw);
  if (!match) return raw;
  const active = (packages ?? []).find(
    (p) => p.state === 'downloading' || p.state === 'verifying',
  );
  const label = active?.label;
  if (!label || label.length === 0) return raw;
  return `Downloading ${label}…${match[2] ?? ''}`;
}
