// SPDX-License-Identifier: Apache-2.0
/**
 * runStepPresentation — Tempdoc 565 §17: THE run-step presentation projection.
 *
 * ONE projector that derives a complete {@link StepPresentation} `{ tone, glyph, label, prominence,
 * live }` for any run step from its {@link UnifiedTurnItem}, so every run-step render site — the spine
 * node, the in-body trace row, the tool-call card — composes ONE descriptor instead of hand-authoring
 * glyph/label/tone/prominence ad-hoc (the forked leaf §17 diagnosed; tempdoc 559's "kernel half-applied
 * to presentation" at the run-step element). Each facet EXTENDS an existing authority, inventing no new
 * kernel: tone + glyph ← §3.B `statusTone` (siblings); prominence ← §12.3.C `prominenceFor`; label ←
 * `composeToolLabel` (tools) / the backend's already-human `AgentProgress` message (progress). This
 * projector also OWNS the per-kind status inference that was hand-rolled inline in `renderRunSpine`
 * (§17.G).
 *
 * The descriptor is BRANDED (the `present.ts` `DisplayLabel` idiom): ONLY this projector mints a
 * `StepPresentation`, so a render site cannot fabricate one and bypass the projection. (§17.G: a plain
 * interface prop is soft — the brand reaches tier-2 for "you must obtain it from the projector"; the
 * `run-renderers` stepPresentation gate is the backstop the type can't fully reach.)
 */
import {
  statusToTone,
  statusGlyph,
  presentedToolStatus,
  type NoticeTone,
  type StepGlyph,
} from '../utils/statusTone.js';
import { composeToolLabel } from '../display/toolLabeling.js';
import {
  prominenceFor,
  type UnifiedTurnItem,
  type TurnProminence,
} from './unifiedThreadProjection.js';

export type { StepGlyph };

/**
 * The complete, single-authority presentation of one run step. BRANDED so only {@link stepPresentation}
 * can mint it (§17 / §17.G typed seam).
 */
export type StepPresentation = {
  readonly tone: NoticeTone;
  readonly glyph: StepGlyph;
  readonly label: string;
  readonly prominence: TurnProminence;
  /** The step is actively running (drives the "alive" indicator). */
  readonly live: boolean;
} & { readonly __stepPresentation: unique symbol };

/**
 * Resolve the step's lifecycle status from its declaration — the per-kind inference once hand-rolled in
 * `renderRunSpine` (§17.G). `tool-activity` carries an explicit `status`; `progress` carries a
 * `severity`; an assistant answer is `completed`; an error is `error` (NOT `rejected` — a failed run
 * (✕) and a denied tool (⊘) are distinct glyphs; the pre-§17 spine conflated them because both share
 * the red tone).
 */
function inferStatus(item: UnifiedTurnItem): string {
  const a = item.attributes;
  if (typeof a.status === 'string' && a.status.length > 0) {
    // Tempdoc 577 Ext I — outcome is a facet distinct from lifecycle: a terminal `completed` whose
    // result carried success=false presents as failed (one seam; see statusTone.presentedToolStatus).
    return presentedToolStatus(a.status, typeof a.success === 'boolean' ? a.success : undefined);
  }
  if (item.kind === 'assistant') return 'completed';
  if (item.kind === 'error') return 'error';
  if (typeof a.severity === 'string' && a.severity.length > 0) return a.severity;
  return '';
}

/**
 * Tempdoc 577 Ext II / §2.12 Move 4 — the closed phase→act vocabulary. A progress step's user-tier
 * label projects from the TYPED `phase` token (the wire's machine token, emitted by
 * `AgentLoopService`/`AgentStepRunner`), never from the backend's free-prose `message` — process-
 * speak ("Calling LLM") stays out of the primary flow by construction. Move 4 grows the vocabulary
 * to narrate the loop's DECISIONS (retry-after-failure, finalize, budget-gate-held) instead of a
 * featureless "Thinking / Thinking" (§2.11 #6). An unknown phase falls back to the message so a
 * future backend phase degrades to its prose, not to a blank.
 */
const PROGRESS_PHASE_LABELS: Readonly<Record<string, string>> = {
  init: 'Session started',
  llm_call: 'Thinking',
  retry_after_tool_failure: 'Retrying with corrected input',
  finalizing: 'Wrapping up',
  budget_gate_held: 'Waiting on budget',
  // Tempdoc 577 §2.14 Root II — the context-management narration (the one growing phase vocabulary).
  context_gate_held: 'Context filling up',
  context_compacted: 'Compacted earlier turns to stay within the model’s memory',
  // Tempdoc 577 §2.14 Root I — a Watch run paused because its only watcher left (zero-observer park).
  run_unobserved_parked: 'Paused — no one is watching',
};

/**
 * The human label for a step — `composeToolLabel` for tools (verb + target); the typed phase token
 * via {@link PROGRESS_PHASE_LABELS} for progress (Ext II — the backend's prose `message` is the
 * unknown-phase fallback only, rendered as-is, NOT CSS-uppercased; §16.4/§17.G); a short noun for
 * the turn endpoints.
 */
function stepLabel(item: UnifiedTurnItem): string {
  const a = item.attributes;
  switch (item.kind) {
    case 'tool-activity': {
      const toolName = typeof a.toolName === 'string' ? a.toolName : 'tool';
      const args = typeof a.arguments === 'string' ? a.arguments : undefined;
      const { label, target } = composeToolLabel(toolName, args);
      return target ? `${label} · ${target}` : label;
    }
    case 'user':
      return 'Your message';
    case 'assistant':
      return 'Answer';
    case 'error':
      return item.content.trim() || 'Run error';
    case 'progress': {
      const phase = typeof a.phase === 'string' ? PROGRESS_PHASE_LABELS[a.phase] : undefined;
      return phase ?? (item.content.trim() || 'Working…');
    }
    case 'handoff':
      return item.content.trim() || 'Handoff';
    default:
      return item.content.trim();
  }
}

/**
 * Project a run step into its complete, single-authority presentation (§17). The ONLY mint of a branded
 * {@link StepPresentation}. `prominence` defaults to {@link prominenceFor} so a live item missing the
 * projected field still grades correctly.
 */
export function stepPresentation(item: UnifiedTurnItem): StepPresentation {
  const status = inferStatus(item);
  const live = status === 'executing' || status === 'running';
  return {
    tone: statusToTone(status || undefined),
    glyph: statusGlyph(status || undefined),
    label: stepLabel(item),
    prominence: item.prominence ?? prominenceFor(item.kind),
    live,
  } as StepPresentation;
}
