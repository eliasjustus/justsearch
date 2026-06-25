// SPDX-License-Identifier: Apache-2.0
/**
 * CommandRegistry — Tempdoc 508 §3.2 — unified registry of all
 * invocable actions.
 *
 * Commands come from four sources:
 * 1. Operations — backend-declared, projected from OperationCatalogClient
 * 2. Plugin commands — registered via host.registerCommand
 * 3. Shell commands — built-in navigation/UI actions
 * 4. Surface-context commands — available per focused surface
 *
 * The CommandPalette surface projects this registry with fuzzy search.
 */

import type { Provenance } from '../primitives/provenance.js';
import { present } from '../display/present.js';

export interface Command {
  readonly id: string;
  /**
   * Raw display label (legacy/back-compat). Prefer {@link labelKey} for new
   * registrations so the label is localized through the one display projector
   * (tempdoc 557 P2). When both are set, `labelKey` wins at render time.
   */
  readonly label: string;
  /** i18n key resolved via the display projector at render time (557 P2). */
  readonly labelKey?: string;
  readonly category?: string;
  readonly icon?: string;
  readonly shortcut?: string;
  readonly source: 'operation' | 'plugin' | 'shell' | 'surface';
  /** Tempdoc 543 §3.A / §21.A2 — required typed provenance. */
  readonly provenance: Provenance;
  /**
   * Tempdoc 508 §11.1 / §13.1 — VS Code-grammar visibility predicate.
   * If undefined / empty, the command is always visible. If set, the
   * evaluator (`evaluateWhen`) decides visibility against the live
   * ShellContext. Malformed expressions silently filter the command
   * out and log a single WARN.
   */
  readonly when?: string;
  /**
   * Command handler. The return value (if any) is captured by
   * {@link invokeCommandWithResult} and surfaced to the caller — for
   * agent-invoked virtual operations (§13.5 Phase B) the string
   * return becomes the `output` field POST'd back to the agent.
   * Fire-and-forget callers via {@link invokeCommand} ignore the
   * return value.
   */
  readonly handler: () => unknown | Promise<unknown>;
}

// Tempdoc 543 §28.W7 — shared registry primitive.
import { createRegistry } from '../primitives/registry.js';
import { notifyAllWith } from '../primitives/notify.js';

const _registry = createRegistry<Command>();
const commands = _registry._map;
const invocationListeners = new Set<(commandId: string) => void>();

function notifyInvocation(id: string): void {
  notifyAllWith(invocationListeners, id);
}

/**
 * Tempdoc 521 §16.4 — subscribe to command invocations (both fire-and-
 * forget and awaitable variants). The walkthrough card consumes this to
 * advance steps gated by `completionEvent: 'onCommand:<id>'`. Other
 * substrates can hook the same channel.
 */
export function onCommandInvoked(listener: (commandId: string) => void): () => void {
  invocationListeners.add(listener);
  return () => invocationListeners.delete(listener);
}

/**
 * 557 P2 — the one display label for a command: a `labelKey` is resolved through
 * the display projector (i18n / catalog), so plugin command labels are localized
 * like everything else; a raw `label` is the legacy fallback.
 */
export function commandLabel(cmd: { label: string; labelKey?: string }): string {
  return cmd.labelKey ? present({ kind: 'resource', key: cmd.labelKey }).label : cmd.label;
}

export const registerCommand = _registry.register;
export const unregisterCommand = _registry.unregister;
export const getCommand = _registry.get;
export const onCommandRegistryChange = _registry.subscribe;
export function listCommands(): Command[] {
  return Array.from(_registry.list());
}

export function invokeCommand(id: string): void {
  // §21.B — Action is canonical. Look up in Action substrate first;
  // fall back to legacy Command map for back-compat with TemplateCatalog
  // + plugin HostApi.registerCommand callsites that still use the
  // imperative Command path.
  const actionId = resolveActionIdFromCommandId(id);
  if (actionId !== null) {
    recordRecentCommand(id);
    notifyInvocation(id);
    // Dynamic import keeps the dependency loose-coupled in case of
    // module-load ordering shenanigans during boot.
    void import('../substrates/actions/index.js').then(({ invokeAndApply }) => {
      void invokeAndApply(actionId);
    });
    return;
  }
  const cmd = commands.get(id);
  if (cmd) {
    recordRecentCommand(id);
    notifyInvocation(id);
    void Promise.resolve(cmd.handler());
  }
}

/**
 * §21.B — map legacy command ids to migrated Action ids. Returns null
 * when no Action equivalent exists; caller falls back to the legacy
 * Command map.
 */
function resolveActionIdFromCommandId(commandId: string): string | null {
  // Shell commands migrated by registerShellActions (§21.B).
  if (commandId.startsWith('shell.')) {
    return `core.action.${commandId}`;
  }
  // Operation projections migrated by projectOperationsToActions (§21.B).
  if (commandId.startsWith('op.')) {
    return `core.action.${commandId}`;
  }
  // §28.W10 — Template projections moved into the Action substrate;
  // legacy 'template.<id>' ids resolve to 'core.action.template.<id>'.
  if (commandId.startsWith('template.')) {
    return `core.action.${commandId}`;
  }
  // Other command ids (plugin host commands + virtual operations that
  // return string values to the agent harness) stay on the legacy
  // Command path. Per §28.W10: CommandRegistry remains as the
  // value-returning-invocation substrate, distinct from Action's
  // Effect-returning shape.
  return null;
}

/**
 * Tempdoc 508 §11.5 / §13.5 Phase B — awaitable command invocation.
 * Returns a promise that resolves with success + captured output
 * (if any) or with error detail when the handler throws or rejects.
 * Used by the virtual-tool dispatcher to deliver an agent's tool-
 * call result back to the backend.
 *
 * Commands today are fire-and-forget; this wrapper awaits the
 * handler's return value (sync or async), captures any returned
 * string as output, and treats undefined returns as success with
 * an empty output.
 */
export interface InvocationResult {
  readonly ok: boolean;
  readonly output?: string;
  readonly error?: string;
}

export async function invokeCommandWithResult(
  id: string,
  opts: { originator?: 'user' | 'agent' | 'system' } = {},
): Promise<InvocationResult> {
  const originator = opts.originator ?? 'user';
  // §28.W13 — when the agent harness is the caller, route Action-
  // shaped invocations through invokeAndApply with originator='agent'
  // so the resulting Journal entry attributes correctly.
  const actionId = resolveActionIdFromCommandId(id);
  if (actionId !== null) {
    recordRecentCommand(id);
    notifyInvocation(id);
    try {
      const { invokeAndApply } = await import('../substrates/actions/index.js');
      await invokeAndApply(actionId, {}, null, undefined, originator);
      return { ok: true, output: '' };
    } catch (e) {
      return { ok: false, error: e instanceof Error ? e.message : String(e) };
    }
  }
  const cmd = commands.get(id);
  if (!cmd) {
    return { ok: false, error: `command not found: ${id}` };
  }
  recordRecentCommand(id);
  notifyInvocation(id);
  try {
    const value = await Promise.resolve(cmd.handler() as unknown);
    const output = typeof value === 'string' ? value : value === undefined ? '' : JSON.stringify(value);
    return { ok: true, output };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

// ---------------------------------------------------------------------------
// Recent commands (508 §3.3) — persisted in UserStateDocument
// ---------------------------------------------------------------------------

import { getRecentCommandIds as docGetRecent, recordRecentCommandId as docRecordRecent } from '../state/UserStateDocument.js';
import { getShellContext } from '../state/shellContextState.js';
import { evaluateWhen } from './whenExpression.js';

export function recordRecentCommand(id: string): void {
  docRecordRecent(id);
}

/**
 * §21.C — palette-projection back door. Returns the full Command map
 * so CommandPaletteProjection.ts can layer fuzzy + frecency over
 * Action.listActions ∪ legacy Commands (templates, plugin HostApi
 * commands). Read-only iteration shape.
 */
export function __getAllCommandsForPalette(): readonly Command[] {
  return Array.from(commands.values());
}

export function getRecentCommandIds(): readonly string[] {
  return docGetRecent();
}

// ---------------------------------------------------------------------------
// Mode prefixes (508 §3.3) — `>` commands, `#` surfaces, `@` settings
// ---------------------------------------------------------------------------

export type PaletteMode = 'commands' | 'surfaces' | 'settings';

export interface ParsedQuery {
  mode: PaletteMode;
  text: string;
}

export function parsePaletteQuery(raw: string): ParsedQuery {
  if (raw.startsWith('>')) return { mode: 'commands', text: raw.slice(1).trim() };
  if (raw.startsWith('#')) return { mode: 'surfaces', text: raw.slice(1).trim() };
  if (raw.startsWith('@')) return { mode: 'settings', text: raw.slice(1).trim() };
  return { mode: 'commands', text: raw };
}

// ---------------------------------------------------------------------------
// Fuzzy scorer (508 §3.3 / confidence investigation: 50-line hand-rolled)
// ---------------------------------------------------------------------------

export interface ScoredCommand {
  command: Command;
  score: number;
  matches: number[];
}

export function fuzzyScore(query: string, target: string): { score: number; matches: number[] } | null {
  if (query.length === 0) return { score: 0, matches: [] };

  const queryLower = query.toLowerCase();
  const targetLower = target.toLowerCase();
  const matches: number[] = [];
  let score = 0;
  let qi = 0;
  let lastMatchIndex = -1;

  for (let ti = 0; ti < targetLower.length && qi < queryLower.length; ti++) {
    if (targetLower[ti] === queryLower[qi]) {
      matches.push(ti);
      let bonus = 1;

      // Position-0 bonus
      if (ti === 0) bonus += 10;

      // Word-boundary bonus (after space, dash, underscore, or camelCase)
      if (ti > 0) {
        const prev = target[ti - 1]!;
        const curr = target[ti]!;
        if (prev === ' ' || prev === '-' || prev === '_' || prev === '.') {
          bonus += 5;
        } else if (prev === prev.toLowerCase() && curr === curr.toUpperCase()) {
          bonus += 5;
        }
      }

      // Contiguous match bonus
      if (lastMatchIndex === ti - 1) {
        bonus += 3;
      }

      score += bonus;
      lastMatchIndex = ti;
      qi++;
    }
  }

  // All query chars must be found
  if (qi < queryLower.length) return null;

  return { score, matches };
}

export function searchCommands(query: string): ScoredCommand[] {
  const parsed = parsePaletteQuery(query);
  const ctx = getShellContext();

  // Filter pool by mode (508 §3.3) and `when` (508 §11.1 / §13.1).
  const pool = Array.from(commands.values()).filter((c) => {
    // when-clause filter: commands with `when` evaluate against the
    // live ShellContext. Absent/empty when = always visible.
    if (!evaluateWhen(c.when, ctx as unknown as Record<string, unknown>)) return false;
    if (parsed.mode === 'surfaces') return c.id.startsWith('shell.go-to-');
    if (parsed.mode === 'settings') return c.category === 'Settings' || c.id.startsWith('settings.');
    return true; // commands mode = all
  });

  const recentIds = docGetRecent();
  const recentSet = new Set(recentIds);

  if (parsed.text.length === 0) {
    // Empty query: show recent commands first, then everything else.
    const recent: ScoredCommand[] = [];
    const rest: ScoredCommand[] = [];
    for (const cmd of pool) {
      const entry = { command: cmd, score: recentSet.has(cmd.id) ? 100 : 0, matches: [] };
      if (recentSet.has(cmd.id)) recent.push(entry);
      else rest.push(entry);
    }
    recent.sort((a, b) => recentIds.indexOf(a.command.id) - recentIds.indexOf(b.command.id));
    return [...recent, ...rest];
  }

  const results: ScoredCommand[] = [];
  for (const cmd of pool) {
    // 557 P2 — match against the projected label (honors labelKey), same as
    // searchPaletteEntries, so a localized command can't fall back to the raw key.
    const result = fuzzyScore(parsed.text, commandLabel(cmd));
    if (result) {
      // Recency bonus: +20 if used recently
      const recencyBonus = recentSet.has(cmd.id) ? 20 : 0;
      results.push({ command: cmd, score: result.score + recencyBonus, matches: result.matches });
    }
  }
  results.sort((a, b) => b.score - a.score);
  return results;
}

// ---------------------------------------------------------------------------
// Shell commands + Operation projection — RETIRED in §21.B
// ---------------------------------------------------------------------------
// Migrated to the Action substrate. See:
//   - registerShellActions (modules/ui-web/src/shell-v0/substrates/actions/index.ts)
//   - projectOperationsToActions (same file)
// invokeCommand routes legacy 'shell.*' / 'op.*' ids to the corresponding
// Action via resolveActionIdFromCommandId, so keybinding bindings carrying
// commandId: 'shell.toggle-palette' continue to work without changes.

// ---------------------------------------------------------------------------
// Surface-context commands (508 §3.2 source 4) — registered when a
// surface activates, unregistered when another surface activates.
// ---------------------------------------------------------------------------

let activeSurfaceCommandIds: Set<string> = new Set();

/**
 * Register commands scoped to a specific surface. Previous surface-context
 * commands are unregistered. Pass an empty array to clear all without
 * registering new ones.
 */
export function setActiveSurfaceCommands(
  surfaceId: string,
  cmds: ReadonlyArray<Omit<Command, 'source'>>,
): void {
  // Remove previously active surface commands
  for (const id of activeSurfaceCommandIds) {
    commands.delete(id);
  }
  activeSurfaceCommandIds = new Set();
  // Register new ones
  for (const c of cmds) {
    const scopedId = `${surfaceId}:${c.id}`;
    commands.set(scopedId, { ...c, id: scopedId, source: 'surface' });
    activeSurfaceCommandIds.add(scopedId);
  }
  _registry.__notify();
}

export function __resetForTest(): void {
  _registry.__resetForTest();
  activeSurfaceCommandIds.clear();
}
