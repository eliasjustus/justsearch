// SPDX-License-Identifier: Apache-2.0
/**
 * Elicit substrate — Tempdoc 543 §14.3 β3 / §25.β3.
 *
 * Mid-invocation user-input primitive for Action handlers. Instead of
 * an Action handler painting its own modal/prompt UI, it calls
 * `elicit(schema, opts)` and awaits the user's response via the Form
 * substrate (jf-form). The kernel renders the modal chrome and routes
 * the response back to the handler.
 *
 * Pattern aligns with MCP `sampling.elicitInput` and the field's
 * converged "request input mid-tool-call" UX. Same primitive serves
 * first-party Actions (e.g., "rename — what's the new name?"), plugin
 * Actions (untrusted contributors get the same kernel-rendered chrome
 * as first-party), and the AI emitter (when it asks the user a
 * clarifying question mid-tool-call).
 *
 * The substrate is decoupled from chrome via document-dispatched
 * events: callers `elicit(...)`, the chrome surface (jf-elicit-host)
 * listens for the dispatched request event, renders the form, and
 * resolves the returned Promise when the user submits or cancels.
 */

import type { JsonSchema, UISchemaElement } from '@jsonforms/core';

let _nextId = 1;
const _pending = new Map<
  number,
  {
    readonly resolve: (value: unknown | null) => void;
    readonly request: ElicitRequest;
  }
>();

export interface ElicitRequest {
  readonly id: number;
  readonly title: string;
  readonly description?: string;
  readonly schema: JsonSchema;
  /** Optional UI schema for richer renderer dispatch. */
  readonly uischema?: UISchemaElement;
  /** Initial form data (defaults the renderer pre-populates). */
  readonly initialData?: unknown;
  /** Submit button label (default 'Submit'). */
  readonly submitLabel?: string;
  /** Cancel button label (default 'Cancel'). Set to null to hide cancel. */
  readonly cancelLabel?: string | null;
}

export interface ElicitOptions {
  readonly title: string;
  readonly description?: string;
  readonly schema: JsonSchema;
  readonly uischema?: UISchemaElement;
  readonly initialData?: unknown;
  readonly submitLabel?: string;
  readonly cancelLabel?: string | null;
}

/**
 * Open an elicitation request. Returns a Promise that resolves with
 * the submitted form data, or null if the user cancels / no chrome
 * is mounted to respond.
 *
 * Usage in an Action handler:
 *   const value = await ctx.elicit({
 *     title: 'Rename',
 *     schema: { type: 'object', properties: { name: { type: 'string' } } },
 *   });
 *   if (value === null) return { kind: 'noop' };
 *   return { kind: 'invoke-operation', operationId: '...', args: value };
 */
export function elicit(opts: ElicitOptions): Promise<unknown | null> {
  const id = _nextId++;
  const request: ElicitRequest = {
    id,
    title: opts.title,
    schema: opts.schema,
    ...(opts.description !== undefined ? { description: opts.description } : {}),
    ...(opts.uischema !== undefined ? { uischema: opts.uischema } : {}),
    ...(opts.initialData !== undefined ? { initialData: opts.initialData } : {}),
    ...(opts.submitLabel !== undefined ? { submitLabel: opts.submitLabel } : {}),
    ...(opts.cancelLabel !== undefined ? { cancelLabel: opts.cancelLabel } : {}),
  };
  return new Promise<unknown | null>((resolve) => {
    _pending.set(id, { resolve, request });
    if (typeof document !== 'undefined') {
      document.dispatchEvent(
        new CustomEvent('jf-elicit-request', {
          detail: request,
          bubbles: true,
        }),
      );
    } else {
      // Headless / SSR: no chrome can respond. Resolve to null so the
      // caller doesn't hang forever in test envs that omit the host.
      _pending.delete(id);
      resolve(null);
    }
  });
}

/**
 * Chrome calls this when the user submits the elicitation form.
 * Resolves the original elicit() Promise with the submitted value.
 */
export function resolveElicit(id: number, value: unknown): boolean {
  const entry = _pending.get(id);
  if (!entry) return false;
  _pending.delete(id);
  entry.resolve(value);
  return true;
}

/**
 * Chrome calls this when the user cancels the elicitation. Resolves
 * the original elicit() Promise with null.
 */
export function cancelElicit(id: number): boolean {
  const entry = _pending.get(id);
  if (!entry) return false;
  _pending.delete(id);
  entry.resolve(null);
  return true;
}

export function listPendingElicits(): readonly ElicitRequest[] {
  return Array.from(_pending.values()).map((p) => p.request);
}

export function getPendingElicitCount(): number {
  return _pending.size;
}

/** Test-only reset. */
export function __resetElicitForTest(): void {
  // Resolve any in-flight promises with null so awaiting tests don't hang.
  for (const entry of _pending.values()) entry.resolve(null);
  _pending.clear();
  _nextId = 1;
}
