// SPDX-License-Identifier: Apache-2.0
/**
 * composeToolLabel — Tempdoc 565 §12.3.B.
 *
 * Projects an agent tool call into a human "what it did" for the compact tool row: a `label`
 * (the operation's display name, via the ONE Display authority `present()` — humanized fallback) and
 * a `target` (the salient argument: the search query, the ingested path, …). The row renders
 * `${label} · ${target}` instead of the bare wire `toolName`, so the run reads as prose
 * ("Search · discipline-gate kernel", "Ingest · discipline-gate-kernel.md") rather than
 * "core_search · completed". Pure + defensive: malformed/absent args degrade to the label alone.
 */
import { present } from './present.js';

export interface ToolLabel {
  /** The tool's human name (operation label, or a humanized id). Never empty. */
  readonly label: string;
  /** The salient argument (query / filename / pattern), or '' when none is parseable. */
  readonly target: string;
}

/** Argument keys, in priority order, that name "what the tool acted on". */
const TARGET_KEYS = [
  'query',
  'q',
  'search',
  'path',
  'paths',
  'file',
  'files',
  'pattern',
  'glob',
  'text',
  'prompt',
  'url',
  'name',
  'id',
] as const;

export function composeToolLabel(toolName: string, argsJson?: string | null): ToolLabel {
  return { label: resolveLabel(toolName), target: extractTarget(argsJson) };
}

function resolveLabel(toolName: string): string {
  const name = (toolName ?? '').trim();
  if (!name) return 'Tool';
  // Prefer the Display authority's label ONLY when it resolves a real i18n name (not its own
  // humanize-fallback, which keeps the wire `core_…_…` underscores). Otherwise use the clean local
  // humanize ("core_ingest_files" → "Ingest Files"). Agent tool names rarely map 1:1 to operation
  // catalog ids, so the local humanize is the common path.
  try {
    const label = present({ kind: 'operation', id: name }).label as unknown as string;
    if (label && label.trim() && !/[_]/.test(label)) return label;
  } catch {
    /* fall through to humanize */
  }
  return humanize(name);
}

function extractTarget(argsJson?: string | null): string {
  if (!argsJson || typeof argsJson !== 'string') return '';
  let parsed: unknown;
  try {
    parsed = JSON.parse(argsJson);
  } catch {
    return '';
  }
  if (!parsed || typeof parsed !== 'object') return '';
  const obj = parsed as Record<string, unknown>;
  for (const key of TARGET_KEYS) {
    const v = obj[key];
    if (typeof v === 'string' && v.trim()) return shorten(basename(v));
    if (Array.isArray(v) && v.length > 0 && typeof v[0] === 'string') {
      const extra = v.length > 1 ? ` +${v.length - 1}` : '';
      return shorten(basename(v[0] as string)) + extra;
    }
  }
  // Fallback: the first non-empty string value.
  for (const v of Object.values(obj)) {
    if (typeof v === 'string' && v.trim()) return shorten(v);
  }
  return '';
}

/** Last path segment for file-like targets; the string itself otherwise. */
function basename(s: string): string {
  const trimmed = s.replace(/[\\/]+$/, '');
  if (/[\\/]/.test(trimmed)) {
    const seg = trimmed.split(/[\\/]/).filter(Boolean).pop();
    if (seg) return seg;
  }
  return s;
}

function shorten(s: string, max = 48): string {
  const t = s.trim().replace(/\s+/g, ' ');
  return t.length > max ? t.slice(0, max - 1) + '…' : t;
}

function humanize(id: string): string {
  return id
    .replace(/^(core|vop)[._]/, '')
    .replace(/[._]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/\b\w/g, (c) => c.toUpperCase());
}
