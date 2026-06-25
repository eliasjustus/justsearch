// SPDX-License-Identifier: Apache-2.0
/**
 * VirtualOperationCatalog — Tempdoc 508 §11.5 / §13.5 — FE-side
 * sidecar catalog projecting TRUSTED+/CORE commands as agent-
 * visible operations.
 *
 * The Java OperationCatalog is immutable at boot — its entries are
 * compiled into the binary. Plugin-contributed commands cannot
 * mutate it. The bridge is a sidecar instead: the FE projects
 * commands into Operation-shaped records (id, label,
 * inputs-JSON-Schema, audience, presentation), serializes them
 * into the OpenAI tools wire shape (matching AgentOperationEmitter),
 * and posts the result to the backend via
 * `POST /api/agent/virtual-operations`. The agent emitter merges
 * the virtual list with the core catalog at tool-list time, so the
 * agent sees one unified vocabulary.
 *
 * Audience gating (the trust attenuation per §13.5): CORE and
 * TRUSTED commands declare Audience.USER | Audience.AGENT; UNTRUSTED
 * commands declare Audience.USER only and are NOT projected here.
 *
 * This module is purely additive — the existing
 * OperationCatalog / AgentOperationEmitter paths are unchanged.
 */

import { onCommandRegistryChange, listCommands } from './CommandRegistry.js';

/**
 * A virtual operation projected from a TRUSTED+/CORE command. The
 * wire shape matches the backend OpenAI tools envelope:
 *   { type: 'function', function: { name, description, parameters } }
 */
export interface VirtualOperation {
  /** Stable id matching the source command id (with `vop.` prefix). */
  readonly id: string;
  /** OpenAI function wire-name. Hyphen-/dot-safe transliteration of id. */
  readonly wireName: string;
  /** Human-facing description. Falls back to label when not provided. */
  readonly description: string;
  /** JSON-Schema for the function's inputs. Plugins can override; default = `{}`. */
  readonly parameters: Readonly<Record<string, unknown>>;
  /** Audience this operation is visible to. */
  readonly audience: ReadonlyArray<'USER' | 'AGENT' | 'OPERATOR' | 'DEVELOPER'>;
  /** Source command id (without prefix). */
  readonly sourceCommandId: string;
  /** Plugin id that owns this virtual operation, or 'shell'/'core'. */
  readonly owner: string;
}

/**
 * Customizations a command can carry to override defaults when
 * projected. Attached out-of-band via {@link decorateCommandForAgent}
 * because Command itself is intentionally minimal.
 */
export interface AgentProjectionMeta {
  readonly agentVisible?: boolean;
  readonly description?: string;
  readonly parameters?: Readonly<Record<string, unknown>>;
  readonly audience?: ReadonlyArray<'USER' | 'AGENT' | 'OPERATOR' | 'DEVELOPER'>;
  readonly owner?: string;
}

const decorations = new Map<string, AgentProjectionMeta>();
const virtualOps = new Map<string, VirtualOperation>();
const listeners = new Set<() => void>();
type Publisher = (ops: ReadonlyArray<VirtualOperation>) => void | Promise<void>;
let publisher: Publisher | null = null;

function notify(): void {
  for (const l of listeners) {
    try { l(); } catch { /* swallow */ }
  }
  if (publisher) {
    // Fire-and-forget on incremental updates; callers needing back-pressure use
    // {@link publishNow} (tempdoc 508-followup §β2).
    try { void publisher(Array.from(virtualOps.values())); } catch { /* swallow */ }
  }
}

/**
 * Convert a command id into a wire name compatible with the agent
 * emitter's `OperationCatalog.toWireName(...)` transliteration:
 * `.` and `-` → `_`. Prefix `vop_` so virtual operations don't
 * collide with core operation ids.
 */
export function commandIdToWireName(commandId: string): string {
  return 'vop_' + commandId.replace(/[.\-]/g, '_');
}

/**
 * Attach agent-projection metadata to a command. Called by
 * registerCommand consumers that want the command to appear in the
 * agent's tool list (or to suppress projection via
 * `agentVisible: false`).
 *
 * Default for commands without decoration: NOT projected. Producers
 * opt in explicitly so plugin-contributed commands don't accidentally
 * leak to the agent before audience review.
 */
export function decorateCommandForAgent(
  commandId: string,
  meta: AgentProjectionMeta,
): void {
  decorations.set(commandId, meta);
  rebuild();
}

export function clearAgentDecoration(commandId: string): void {
  decorations.delete(commandId);
  rebuild();
}

/**
 * Re-project commands → virtual operations based on current
 * decorations. Called on CommandRegistry changes and on decoration
 * updates. Skips:
 *   - commands without decoration
 *   - commands explicitly marked agentVisible: false
 *   - operation-sourced commands (already in the real OperationCatalog)
 */
function rebuild(): void {
  virtualOps.clear();
  for (const cmd of listCommands()) {
    if (cmd.source === 'operation') continue;
    const meta = decorations.get(cmd.id);
    if (!meta || meta.agentVisible === false) continue;
    const op: VirtualOperation = {
      id: `vop.${cmd.id}`,
      wireName: commandIdToWireName(cmd.id),
      description: meta.description ?? cmd.label,
      parameters: meta.parameters ?? { type: 'object', properties: {} },
      audience: meta.audience ?? ['USER', 'AGENT'],
      sourceCommandId: cmd.id,
      owner: meta.owner ?? 'shell',
    };
    virtualOps.set(op.id, op);
  }
  notify();
}

export function listVirtualOperations(): ReadonlyArray<VirtualOperation> {
  return Array.from(virtualOps.values());
}

export function onVirtualOperationChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * Serialize the virtual catalog into the OpenAI tools envelope —
 * one entry per agent-visible op. Matches the backend
 * AgentOperationEmitter's shape:
 *   { type: 'function', function: { name, description, parameters } }
 *
 * Filtered to operations that include AGENT in audience.
 */
export function serializeVirtualOperationsForAgent(): ReadonlyArray<{
  type: 'function';
  function: {
    name: string;
    description: string;
    parameters: Record<string, unknown>;
  };
  audience: ReadonlyArray<string>;
}> {
  return Array.from(virtualOps.values())
    .filter((op) => op.audience.includes('AGENT'))
    .map((op) => ({
      type: 'function' as const,
      function: {
        name: op.wireName,
        description: op.description,
        parameters: op.parameters as Record<string, unknown>,
      },
      // Tempdoc 508 §13 critical-analysis Phase C — explicit
      // audience field on the wire shape. The backend validates
      // each entry includes AGENT before accepting the publish;
      // omitting it returns 400.
      audience: op.audience,
    }));
}

/**
 * Resolve an agent tool invocation back to a shell command. The
 * agent sends a tool-call with `name === wireName`; this lookup
 * returns the source command id so the FE can invoke it via the
 * CommandRegistry. Returns null if no virtual operation matches.
 */
export function resolveAgentToolCall(wireName: string): string | null {
  for (const op of virtualOps.values()) {
    if (op.wireName === wireName) return op.sourceCommandId;
  }
  return null;
}

/**
 * Install a publisher hook that fires when the virtual catalog
 * changes. The shell wires this to POST the serialized list to the
 * backend so the AgentOperationEmitter can merge them at tool-list
 * time. One publisher at a time.
 */
export function setVirtualOperationPublisher(fn: Publisher | null): void {
  publisher = fn;
}

/**
 * Tempdoc 508-followup §β2 — explicitly fire the publisher with the current
 * virtual-operations list and await its completion. Boot sequences call this
 * after `setVirtualOperationPublisher` so the initial empty-catalog publish
 * (or any catalog assembled before the publisher was attached) lands on the
 * backend before user-facing surfaces become reachable.
 *
 * Without this, a user invoking the agent immediately after page load could
 * see a stale tool list — the publish ran fire-and-forget during boot and
 * had not yet been awaited when the agent endpoint was hit.
 *
 * Returns a resolved promise when no publisher is attached.
 */
export async function publishNow(): Promise<void> {
  if (!publisher) return;
  try {
    await publisher(Array.from(virtualOps.values()));
  } catch {
    // Initial publish is best-effort; the backend may not be ready yet at boot.
  }
}

/**
 * Subscribe to CommandRegistry changes so the virtual catalog
 * stays in sync. Returns the unsubscribe — typically called once
 * at shell boot.
 */
export function bootVirtualOperationCatalog(): () => void {
  const off = onCommandRegistryChange(() => {
    rebuild();
  });
  rebuild();
  return off;
}

export function __resetVirtualOperationCatalogForTest(): void {
  decorations.clear();
  virtualOps.clear();
  listeners.clear();
  publisher = null;
}
