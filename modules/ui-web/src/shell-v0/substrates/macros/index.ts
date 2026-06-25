// SPDX-License-Identifier: Apache-2.0
/**
 * Macros substrate — Tempdoc 543 §14.3 δ3 / §25.δ3.
 *
 * "Replay this session as a macro" — the user picks a range of journal
 * entries, gives the sequence a name, and the kernel materializes a
 * new Action whose handler dispatches the effects in order.
 *
 * Macro persistence: localStorage. Macros survive sessions and surface
 * in the command palette via auto-registered Actions (one Action per
 * macro id; ids prefixed with `core.action.macro.`).
 *
 * The macros substrate consumes:
 *   - Effect Journal (recordEffect / listJournal) — source of entries
 *   - Effect substrate union — runtime dispatch reuses applyEffect
 *   - Action substrate — auto-register macros as Actions for palette
 *
 * Capability boundary: only USER-originated entries are eligible to
 * include in a macro (we don't replay AGENT-originated entries because
 * the AI's intent shouldn't be replayable without re-approval).
 */

import type { Effect } from '../effect.js';
import { applyEffect } from '../actions/index.js';
import { registerAction, unregisterAction } from '../actions/index.js';
import { CORE_PROVENANCE } from '../../primitives/provenance.js';
import { safeLocalStorage } from '../../primitives/storage.js';
import { notifyAll } from '../../primitives/notify.js';
// §32 U4 — parameterized macros prompt via elicit; §32 R-E5 — previewMacro
// dry-runs via previewSequence.
import { elicit, type ElicitOptions } from '../elicit/index.js';
import { previewSequence, type JournalEntry } from '../effects/index.js';
import type { JsonSchema } from '@jsonforms/core';

export interface MacroParams {
  /** Prompt title shown in the elicit modal. */
  readonly title: string;
  readonly description?: string;
  /** JSON Schema for the values to collect (e.g., `{ name: string }`). */
  readonly schema: JsonSchema;
}

export interface Macro {
  readonly id: string;
  readonly label: string;
  /**
   * The effects to dispatch, in order. When `params` is set, string fields may
   * contain `{{key}}` tokens substituted from the elicited values at run time
   * (§32 U4 parameterized macros).
   */
  readonly effects: ReadonlyArray<Effect>;
  /** §32 U4 — optional run-time prompt (elicit) for a parameterized macro. */
  readonly params?: MacroParams;
  /** ISO-8601 timestamp the macro was defined. */
  readonly definedAt: string;
}

const PERSIST_KEY = 'justsearch.macros.v1';

const _macros = new Map<string, Macro>();
const _listeners = new Set<() => void>();

function notify(): void {
  notifyAll(_listeners);
}

interface PersistedMacros {
  readonly version: 1;
  readonly macros: ReadonlyArray<Macro>;
}

function writePersisted(): void {
  const storage = safeLocalStorage();
  if (!storage) return;
  try {
    const payload: PersistedMacros = {
      version: 1,
      macros: Array.from(_macros.values()),
    };
    storage.setItem(PERSIST_KEY, JSON.stringify(payload));
  } catch {
    /* swallow */
  }
}

let _restored = false;
export function restoreMacrosFromStorage(): void {
  if (_restored) return;
  _restored = true;
  const storage = safeLocalStorage();
  if (!storage) return;
  const raw = storage.getItem(PERSIST_KEY);
  if (!raw) return;
  try {
    const parsed = JSON.parse(raw) as PersistedMacros;
    if (parsed?.version !== 1) return;
    if (!Array.isArray(parsed.macros)) return;
    for (const m of parsed.macros) {
      _macros.set(m.id, m);
      registerMacroAsAction(m);
    }
  } catch {
    /* swallow */
  }
}

/**
 * Define a macro from a list of effects. The effects array is usually
 * sourced from a slice of listJournal() entries, but callers can build
 * macros from any Effect sequence. Throws on duplicate id.
 */
export function defineMacro(opts: {
  id: string;
  label: string;
  effects: ReadonlyArray<Effect>;
  params?: MacroParams;
}): Macro {
  if (_macros.has(opts.id)) {
    throw new Error(`Macro already defined: ${opts.id}`);
  }
  const macro: Macro = {
    id: opts.id,
    label: opts.label,
    effects: opts.effects,
    ...(opts.params !== undefined ? { params: opts.params } : {}),
    definedAt: new Date().toISOString(),
  };
  _macros.set(opts.id, macro);
  registerMacroAsAction(macro);
  writePersisted();
  notify();
  return macro;
}

/**
 * 543-fwd #10 — extract the candidate CONSTANTS a macro could parameterize:
 * the user-facing string values (queries, paths, messages), NOT structural ids
 * (operationId / paneId / kind). Distinct, in first-seen order, min length 2.
 */
export function extractMacroConstants(effects: ReadonlyArray<Effect>): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  const add = (v: unknown): void => {
    if (typeof v === 'string' && v.trim().length >= 2 && !seen.has(v)) {
      seen.add(v);
      out.push(v);
    }
  };
  const addValues = (o: unknown): void => {
    if (o && typeof o === 'object') {
      for (const val of Object.values(o as Record<string, unknown>)) {
        if (Array.isArray(val)) val.forEach(add);
        else add(val);
      }
    }
  };
  for (const e of effects) {
    switch (e.kind) {
      case 'invoke-operation': addValues(e.args); break; // queries, paths, …
      case 'navigate': add(e.to); break;
      case 'toast': add(e.message); break;
      case 'set-form-value': add(e.value); break;
      case 'open-modal': addValues(e.payload); break;
      case 'set-search-query': add(e.query); break; // 569 §14 — a real text constant
      case 'apply-presentation': add(e.presentationId); break; // 569 §14 — parameterizable id
      default: break; // structural-id kinds carry no user constants
    }
  }
  return out;
}

/**
 * 543-fwd #10 — replace whole-value occurrences of each mapped constant with a
 * `{{paramName}}` token across the effects' string fields. Whole-value (not
 * substring) so a parameter cleanly stands for an entire field; runMacro's
 * `substitute` reverses this at run time. Pure; returns new effects.
 */
export function tokenizeMacroEffects(
  effects: ReadonlyArray<Effect>,
  mapping: ReadonlyArray<{ value: string; paramName: string }>,
): Effect[] {
  const sub = (v: unknown): unknown => {
    if (typeof v === 'string') {
      const hit = mapping.find((m) => m.value === v);
      return hit ? `{{${hit.paramName}}}` : v;
    }
    if (Array.isArray(v)) return v.map(sub);
    if (v && typeof v === 'object') {
      const o: Record<string, unknown> = {};
      for (const [k, val] of Object.entries(v as Record<string, unknown>)) o[k] = sub(val);
      return o;
    }
    return v;
  };
  return effects.map((e) => sub(e) as Effect);
}

/**
 * 543-fwd #9 + #10 — interactive macro authoring. Prompts (elicit) for a label
 * AND — for each candidate constant in the effects (queries / paths / messages)
 * — an optional parameter name (blank = keep literal). Non-blank ones are
 * tokenized to `{{name}}` and a `params` schema is attached so runMacro prompts
 * for them (parameterize-on-save / programming-by-demonstration generalization).
 * Returns the new Macro, or null when cancelled / empty / no label / id clash.
 * Shared by the audit log's "Save selected" and the AI digest's one-tap save.
 */
export async function defineMacroFromEffects(
  effects: ReadonlyArray<Effect>,
  opts: { description?: string } = {},
): Promise<Macro | null> {
  if (effects.length === 0) return null;
  const constants = extractMacroConstants(effects);
  // label + one optional "parameter name" field per candidate constant.
  const properties: Record<string, unknown> = {
    label: {
      type: 'string',
      title: 'Macro label',
      description: 'Human-readable name shown in the palette.',
    },
  };
  constants.forEach((c, i) => {
    const shown = c.length > 40 ? `${c.slice(0, 40)}…` : c;
    properties[`param_${i}`] = {
      type: 'string',
      title: `Parameter name for "${shown}"`,
      description: 'Blank = keep this value literal.',
    };
  });
  const formValue = await elicit({
    title: 'Save as macro',
    description:
      opts.description ??
      `Save ${effects.length} effect(s) as a reusable macro. Macros surface in the command palette under the "Macros" category.`,
    schema: { type: 'object', properties, required: ['label'] },
    initialData: { label: '' },
  } as ElicitOptions);
  if (!formValue) return null;
  const values = formValue as Record<string, string>;
  const label = values.label?.trim();
  if (!label) return null;

  // Collect non-blank parameterizations (543-fwd #10).
  const mapping = constants
    .map((value, i) => ({ value, paramName: (values[`param_${i}`] ?? '').trim() }))
    .filter((m) => m.paramName.length > 0);

  let finalEffects: ReadonlyArray<Effect> = effects;
  let params: MacroParams | undefined;
  if (mapping.length > 0) {
    finalEffects = tokenizeMacroEffects(effects, mapping);
    const paramProps: Record<string, JsonSchema> = {};
    for (const m of mapping) {
      paramProps[m.paramName] = { type: 'string', title: m.paramName } as JsonSchema;
    }
    params = {
      title: `Run "${label}"`,
      schema: {
        type: 'object',
        properties: paramProps,
        required: mapping.map((m) => m.paramName),
      } as JsonSchema,
    };
  }

  try {
    return defineMacro({
      id: `user.${Date.now()}`,
      label,
      effects: finalEffects,
      ...(params !== undefined ? { params } : {}),
    });
  } catch {
    return null; // duplicate id (Date.now collision) — swallow
  }
}

export function removeMacro(id: string): boolean {
  if (!_macros.has(id)) return false;
  _macros.delete(id);
  try {
    unregisterAction(`core.action.macro.${id}`);
  } catch {
    /* swallow */
  }
  writePersisted();
  notify();
  return true;
}

export function listMacros(): readonly Macro[] {
  return Array.from(_macros.values());
}

export function getMacro(id: string): Macro | undefined {
  return _macros.get(id);
}

// §32 U4 — recursively substitute `{{key}}` tokens in string fields with the
// elicited values. Pure; returns a new value. Unknown tokens are left intact.
// Author guidance (S8 review): template only FREE-TEXT fields (e.g.
// toast.message, navigate.to). A `{{token}}` placed in a union field (e.g.
// toast.severity) would be replaced with the raw elicited value, which may not
// satisfy the union — within-trust first-party macros are responsible for
// templating only free-text fields.
function substitute(value: unknown, vars: Record<string, unknown>): unknown {
  if (typeof value === 'string') {
    return value.replace(/\{\{(\w+)\}\}/g, (m, k: string) =>
      k in vars ? String(vars[k]) : m,
    );
  }
  if (Array.isArray(value)) return value.map((v) => substitute(v, vars));
  if (value && typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      out[k] = substitute(v, vars);
    }
    return out;
  }
  return value;
}

function resolveEffects(
  macro: Macro,
  vars: Record<string, unknown>,
): ReadonlyArray<Effect> {
  if (!macro.params) return macro.effects;
  return macro.effects.map((e) => substitute(e, vars) as Effect);
}

// 543-fwd #12 — a "backend effect" re-POSTs to the worker when dispatched
// (invoke-operation re-runs the op; undo-operation re-issues POST /api/undo).
// Replaying these silently would re-run real backend work (e.g. file-ops), so
// they are gated behind explicit confirmation (the dry-run panel).
function isBackendEffect(effect: Effect): boolean {
  return effect.kind === 'invoke-operation' || effect.kind === 'undo-operation';
}

function backendOpIds(effects: ReadonlyArray<Effect>): string[] {
  return effects
    .filter(isBackendEffect)
    .map((e) =>
      e.kind === 'invoke-operation' || e.kind === 'undo-operation'
        ? e.operationId
        : '',
    );
}

/**
 * 543-fwd #12 (macro dry-run diff) — the plan a macro replay WOULD execute,
 * without dispatching: the would-be journal entries (via previewSequence) plus
 * the operationIds of any backend effects that would be re-POSTed. The dry-run
 * panel renders `entries` (labelled via describeEffect) and warns on
 * `backendOps` before the user commits.
 */
export interface MacroReplayPlan {
  readonly entries: readonly JournalEntry[];
  readonly backendOps: readonly string[];
}

export function previewMacroReplay(
  id: string,
  vars: Record<string, unknown> = {},
): MacroReplayPlan {
  const macro = _macros.get(id);
  if (!macro) return { entries: [], backendOps: [] };
  const resolved = resolveEffects(macro, vars);
  return {
    entries: previewSequence(resolved, CORE_PROVENANCE),
    backendOps: backendOpIds(resolved),
  };
}

/**
 * Run a macro, dispatching its effects in order. For a parameterized macro
 * (§32 U4) it first prompts via the elicit substrate, then substitutes the
 * collected values into `{{key}}` tokens before dispatching. Returns the count
 * of effects dispatched; a cancelled prompt (or no chrome to answer it)
 * dispatches nothing. Stops at the first dispatch error.
 *
 * 543-fwd #12 — backend effects (invoke-operation / undo-operation re-POST to
 * the worker) are NOT dispatched unless `opts.allowBackendReplay` is true. This
 * stops a macro from silently re-running real backend work (e.g. file-ops). The
 * palette routes backend-bearing macros through the dry-run panel, which sets
 * the flag after the user confirms; pure-FE macros run unguarded.
 */
export interface RunMacroOptions {
  readonly allowBackendReplay?: boolean;
}

export async function runMacro(
  id: string,
  opts: RunMacroOptions = {},
): Promise<number> {
  const macro = _macros.get(id);
  if (!macro) return 0;
  let vars: Record<string, unknown> = {};
  if (macro.params) {
    const elicited = await elicit({
      title: macro.params.title,
      schema: macro.params.schema,
      ...(macro.params.description !== undefined
        ? { description: macro.params.description }
        : {}),
    } as ElicitOptions);
    if (elicited === null) return 0; // cancelled / no chrome → run nothing
    vars = (elicited as Record<string, unknown>) ?? {};
  }
  let applied = 0;
  for (const effect of resolveEffects(macro, vars)) {
    // 543-fwd #12 — guard: skip backend re-POST unless explicitly allowed.
    if (isBackendEffect(effect) && !opts.allowBackendReplay) continue;
    try {
      applyEffect(effect, CORE_PROVENANCE);
      applied++;
    } catch {
      break;
    }
  }
  return applied;
}

/**
 * §32 R-E5 — preview what a macro WOULD do without running it. Substitutes the
 * supplied `vars` (for parameterized macros) and returns the would-be journal
 * entries via previewSequence. No dispatch.
 */
export function previewMacro(
  id: string,
  vars: Record<string, unknown> = {},
): readonly JournalEntry[] {
  const macro = _macros.get(id);
  if (!macro) return [];
  return previewSequence(resolveEffects(macro, vars), CORE_PROVENANCE);
}

/**
 * Auto-register a macro as an Action so it surfaces in the command
 * palette. Idempotent — duplicate registration would throw and is
 * swallowed (the unregister-then-define path covers redefinition).
 */
function registerMacroAsAction(macro: Macro): void {
  try {
    registerAction({
      id: `core.action.macro.${macro.id}`,
      title: macro.label,
      category: 'Macros',
      provenance: CORE_PROVENANCE,
      handler: async () => {
        // 543-fwd #12 — a macro that would re-POST to the backend opens the
        // dry-run panel for confirmation instead of running silently; pure-FE
        // macros run directly. The panel calls runMacro with allowBackendReplay.
        const { backendOps } = previewMacroReplay(macro.id);
        if (backendOps.length > 0 && typeof document !== 'undefined') {
          document.dispatchEvent(
            new CustomEvent('jf-open-macro-dry-run', {
              detail: { macroId: macro.id },
              bubbles: true,
            }),
          );
          return { kind: 'noop' as const };
        }
        await runMacro(macro.id);
        return { kind: 'noop' as const };
      },
    });
  } catch {
    /* duplicate registration — swallow */
  }
}

export function subscribeMacros(listener: () => void): () => void {
  _listeners.add(listener);
  return () => {
    _listeners.delete(listener);
  };
}

/** Test-only reset. */
export function __resetMacrosForTest(): void {
  for (const m of _macros.values()) {
    try {
      unregisterAction(`core.action.macro.${m.id}`);
    } catch {
      /* swallow */
    }
  }
  _macros.clear();
  _listeners.clear();
  _restored = false;
  const storage = safeLocalStorage();
  storage?.removeItem(PERSIST_KEY);
}
