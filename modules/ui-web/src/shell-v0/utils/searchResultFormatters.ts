// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 486 G35 — search-result formatters.
 *
 * Three pure functions that format a SearchHit list for clipboard
 * paste. Each handles empty / single / multi-result inputs.
 *
 * Formats (V1, narrow scope per slice 486 §22):
 *   - Markdown: bullet list with title (linked-style), path, and
 *     optional snippet. Suitable for pasting into Obsidian /
 *     Notion / GitHub markdown.
 *   - JSON: pretty-printed JSON.stringify of the hit array, two-
 *     space indent. Suitable for piping into jq / scripts.
 *   - Paths: newline-separated paths only. Suitable for piping to
 *     a CLI / opening-in-editor batch / grep input.
 *
 * Non-goals (deferred per §22 narrow scope):
 *   - CSV / TSV
 *   - HTML / rich-text formatted clipboard
 *   - Per-result selection (V1 always copies the full result list
 *     visible in the SearchSurface; future widening = checkbox-
 *     selection consumer of the same formatters)
 *   - Custom user-authored templates (Tier B widening)
 */

import type { SearchHit } from '../state/searchState.js';

/**
 * Format hits as a markdown bullet list. Each hit is rendered as:
 *
 *   - **{title}**
 *     `{path}`
 *     {snippet — first 240 chars, wrapped in italics}
 *
 * Returns an empty string when `hits` is empty.
 */
export function formatAsMarkdown(hits: readonly SearchHit[]): string {
  if (hits.length === 0) return '';
  const lines: string[] = [];
  for (const hit of hits) {
    lines.push(`- **${escapeMarkdown(hit.title)}**`);
    lines.push(`  \`${hit.path}\``);
    if (hit.snippet && hit.snippet.trim().length > 0) {
      const trimmed = hit.snippet.slice(0, 240).replace(/\s+/g, ' ').trim();
      lines.push(`  *${escapeMarkdown(trimmed)}*`);
    }
    lines.push('');
  }
  // Drop the trailing empty line.
  return lines.slice(0, -1).join('\n');
}

/**
 * Format hits as pretty-printed JSON (two-space indent). Returns
 * `[]` when hits is empty (so the clipboard never contains
 * unparseable input).
 */
export function formatAsJson(hits: readonly SearchHit[]): string {
  return JSON.stringify(hits, null, 2);
}

/**
 * Format hits as newline-separated paths only. Useful for piping
 * to a CLI or opening files in batch. Returns an empty string
 * when hits is empty.
 */
export function formatAsPaths(hits: readonly SearchHit[]): string {
  return hits.map((h) => h.path).join('\n');
}

/**
 * Escape a small set of markdown-significant characters so the
 * formatted output renders the literal text rather than markdown
 * directives. Conservative — only characters likely to confuse a
 * markdown parser inside a list item or italics span.
 */
function escapeMarkdown(s: string): string {
  return s
    .replace(/\\/g, '\\\\')
    .replace(/\*/g, '\\*')
    .replace(/_/g, '\\_')
    .replace(/`/g, '\\`')
    .replace(/\[/g, '\\[')
    .replace(/\]/g, '\\]');
}
