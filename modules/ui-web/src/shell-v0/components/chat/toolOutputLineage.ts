// SPDX-License-Identifier: Apache-2.0
/**
 * toolOutputLineage — Tempdoc 577 §2.14 Root III (#18): THE single authority for a tool output's
 * text-provenance on the FE. The backend stamps the authoritative lineage into
 * {@code structuredData.lineage} (OutputLineage.wireToken); this reads it and projects the framing,
 * never re-deriving the classification (single-authority discipline, registered in run-renderers).
 *
 * The display half of prompt-injection safety: a corpus-quoted excerpt is framed as quoted — so
 * citation- or instruction-shaped text inside it cannot render as the agent's own credible claim.
 * Absent/unknown lineage defaults to {@code runtime} (a tool's computed value), so EVERY tool output
 * carries a lineage by construction.
 */

/** The text-provenance of a tool's returned bytes (mirrors the backend OutputLineage). */
export type ToolLineage = 'corpus-quoted' | 'runtime' | 'agent-authored';

/** Project the backend-stamped lineage off a tool result's structuredData. Default: runtime. */
export function toolOutputLineage(
  structuredData: Record<string, unknown> | undefined,
): ToolLineage {
  const raw = structuredData?.['lineage'];
  return raw === 'corpus-quoted' || raw === 'agent-authored' ? raw : 'runtime';
}

/**
 * The frame label for a tool output, or {@code null} when no frame is warranted (runtime values and
 * agent-authored text speak in the agent's own voice and need no quoting frame). Corpus-quoted text
 * is the user's documents quoted back — it MUST be framed so it reads as the documents' words.
 */
export function lineageFrameLabel(lineage: ToolLineage): string | null {
  return lineage === 'corpus-quoted' ? 'Quoted from your documents' : null;
}
