// SPDX-License-Identifier: Apache-2.0
/**
 * 569 Move 1 — the custom Presentation Declaration catalog (persistence + lifecycle).
 *
 * Saved declarations live in `UserStateDocument.customPresentations` (cross-profile), the
 * same store as custom themes — they are the SAME kind of artifact with a different origin
 * (built-in vs user vs LLM). A declaration is certified (Move 6) before it persists; a
 * failed certification is rejected. This is the create → save → manage half of the
 * lifecycle; apply flows the theme tier through the single appearance writer and the
 * body/layout tiers through the engine.
 */
import { getDocument, mutateDocument } from '../state/UserStateDocument.js';
import { certifyPresentation, type ConformanceError } from './conformanceGate.js';
import type { PresentationDeclaration } from './presentationDeclaration.js';

export interface SaveResult {
  readonly ok: boolean;
  readonly errors: readonly ConformanceError[];
}

/** All persisted custom Presentation Declarations. */
export function listCustomPresentations(): readonly PresentationDeclaration[] {
  return getDocument().customPresentations ?? [];
}

/** Get one by id, or undefined. */
export function getCustomPresentation(id: string): PresentationDeclaration | undefined {
  return listCustomPresentations().find((p) => p.id === id);
}

/**
 * Certify and persist a declaration (insert or replace by id). A declaration that fails the
 * conformance gate is NOT persisted; its errors are returned.
 */
export function saveCustomPresentation(candidate: unknown): SaveResult {
  // 569 §19 Seam 1 — a custom declaration saved without an explicit origin is user-authored (the LLM
  // path already stamps `origin: llm`, which is preserved). Default the provenance before certifying.
  const stamped =
    candidate !== null &&
    typeof candidate === 'object' &&
    (candidate as Record<string, unknown>)['origin'] === undefined
      ? { ...(candidate as Record<string, unknown>), origin: { kind: 'user' } }
      : candidate;
  const { verdict, declaration } = certifyPresentation(stamped);
  if (!verdict.ok || !declaration) {
    return { ok: false, errors: verdict.errors };
  }
  mutateDocument((doc) => {
    const existing = doc.customPresentations ?? [];
    const next = [...existing.filter((p) => p.id !== declaration.id), declaration];
    return { ...doc, customPresentations: next };
  });
  return { ok: true, errors: [] };
}

/** Delete a custom declaration by id. */
export function deleteCustomPresentation(id: string): void {
  mutateDocument((doc) => {
    const existing = doc.customPresentations ?? [];
    const next = existing.filter((p) => p.id !== id);
    return { ...doc, customPresentations: next.length > 0 ? next : undefined };
  });
}
