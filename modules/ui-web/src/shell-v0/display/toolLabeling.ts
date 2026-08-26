// SPDX-License-Identifier: Apache-2.0
/**
 * composeToolLabel — Tempdoc 565 §12.3.B.
 *
 * Projects an agent tool call into a human "what it did" for the compact tool row: a `label`
 * (the operation's display name, via the ONE Display authority `present()` — humanized fallback), a
 * `verbLabel` (tempdoc 871: the same operation as an ACT the model performed — "Searched", "Read"),
 * and a `target` (the salient argument: the search query, the ingested path, …). The row renders
 * `${label} · ${target}` instead of the bare wire `toolName`, so the run reads as prose
 * ("Search · discipline-gate kernel", "Ingest · discipline-gate-kernel.md") rather than
 * "core_search · completed". Pure + defensive: malformed/absent args degrade to the label alone.
 */
import { present } from './present.js';

export interface ToolLabel {
  /** The tool's human name (operation label, or a humanized id). Never empty. */
  readonly label: string;
  /**
   * Tempdoc 871 §3b — the tool as an ACT, for a surface that reads as a record of what the model
   * did ("Searched …" rather than "Search Index …"). Falls back to {@link label} when the tool maps
   * to no known verb, so this is never empty and a caller never has to branch.
   */
  readonly verbLabel: string;
  /** The salient argument (query / filename / pattern), or '' when none is parseable. */
  readonly target: string;
}

/**
 * Tempdoc 871 §3b (owner, 2026-08-26) — the verb map, keyed on a segment of the wire tool name so an
 * MCP tool named `notes.search` maps as readily as `core_search_index`.
 *
 * TENSE, deliberately not uniform: read/search/browse only ever render as the record of something
 * that already ran (they are auto-approved LOW, so the card appears at or after execution), which is
 * why they take the past form. A WRITE routinely renders BEFORE it happened — a pending call sitting
 * in the approval ceremony — so "Wrote" would be a lie on the very card the reader is deciding on.
 * It keeps the neutral form, which reads correctly in both states. (Owner-settled: search→Searched,
 * read→Read, browse/list→Listed, write→Write.)
 *
 * A tool that matches nothing here keeps its humanized label rather than getting a guessed verb.
 */
const VERBS: ReadonlyArray<readonly [ReadonlySet<string>, string]> = [
  [new Set(['search', 'find', 'query', 'grep']), 'Searched'],
  [new Set(['read', 'open']), 'Read'],
  [new Set(['browse', 'list', 'ls', 'dir']), 'Listed'],
  [new Set(['write', 'edit', 'save', 'create']), 'Write'],
];

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
  const label = resolveLabel(toolName);
  return { label, verbLabel: resolveVerb(toolName) ?? label, target: extractTarget(argsJson) };
}

/** Tempdoc 871 §3b — the tool's verb, or `null` when no segment of its name maps to one. */
function resolveVerb(toolName: string): string | null {
  const segments = (toolName ?? '')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter(Boolean);
  for (const segment of segments) {
    for (const [names, verb] of VERBS) if (names.has(segment)) return verb;
  }
  return null;
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
