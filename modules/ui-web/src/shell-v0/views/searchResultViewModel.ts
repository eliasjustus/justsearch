// SPDX-License-Identifier: Apache-2.0
/**
 * searchResultViewModel — Tempdoc 577 Goal 1 Phase 7 (570 Move B, the §18 D3 interim).
 *
 * The ONE projection from a search hit's wire identity to its typed result view:
 * `kind` (from `file_kind`/`mime_base` — the data the renderer used to ignore),
 * the glyph, and the snippet (preferring the worker-computed `excerptRegions`
 * over the raw `content_preview`, with word-boundary truncation — the mid-word
 * "…ingestion-time (offline, inde" cut becomes unrepresentable here).
 *
 * Explicitly NOT the 559 element-projection engine (that stays the long-term
 * target); this is the typed-view-model + one type-switching renderer interim
 * 570 §18.4 D3 names. The renderer switches over `ResultKind`; a homogeneous
 * type-blind row has no constructor in this grammar.
 */

export type ResultKind = 'markdown' | 'code' | 'image' | 'pdf' | 'document' | 'other';

export interface SearchResultView {
  kind: ResultKind;
  /** Icon-atom glyph name for the kind. */
  icon: 'file-text' | 'code' | 'image';
  title: string;
  path: string;
  snippet: string;
  snippetSource: 'excerpt' | 'preview' | 'none';
  /** Line anchor of the best excerpt (code rows show it as :L<n>). */
  approxLine?: number;
}

/** The wire identity subset the projection reads (matches SearchHit / SearchHitSnapshot). */
export interface ResultViewInput {
  title: string;
  path: string;
  snippet?: string;
  kind?: string;
  mimeBase?: string;
  excerptRegions?: Array<{ text?: string; approxLine?: number }>;
}

const SNIPPET_MAX = 240;

/** Closed file_kind → ResultKind mapping; mime_base disambiguates the rest. */
function deriveKind(fileKind: string | undefined, mimeBase: string | undefined): ResultKind {
  switch (fileKind) {
    case 'markdown':
      return 'markdown';
    case 'code':
      return 'code';
    case 'image':
      return 'image';
    case 'pdf':
      return 'pdf';
    default:
      break;
  }
  if (mimeBase?.startsWith('image')) return 'image';
  if (mimeBase === 'application/pdf') return 'pdf';
  if (mimeBase?.startsWith('text')) return 'document';
  return fileKind ? 'document' : 'other';
}

const KIND_ICONS: Record<ResultKind, SearchResultView['icon']> = {
  markdown: 'file-text',
  code: 'code',
  image: 'image',
  pdf: 'file-text',
  document: 'file-text',
  other: 'file-text',
};

/** Word-boundary truncation: never cuts mid-word; appends an ellipsis when cut. */
export function truncateAtWord(text: string, max: number = SNIPPET_MAX): string {
  const t = text.trim();
  if (t.length <= max) return t;
  const cut = t.slice(0, max);
  const lastSpace = cut.lastIndexOf(' ');
  // Backtrack to a word boundary unless that would eat most of the text.
  const head = lastSpace > max * 0.6 ? cut.slice(0, lastSpace) : cut;
  return `${head.trimEnd()}…`;
}

export function projectResultView(hit: ResultViewInput): SearchResultView {
  const kind = deriveKind(hit.kind, hit.mimeBase);
  const excerpt = hit.excerptRegions?.find((r) => (r.text ?? '').trim().length > 0);
  const snippetRaw = excerpt?.text ?? hit.snippet ?? '';
  const snippet = snippetRaw.trim() ? truncateAtWord(snippetRaw) : '';
  return {
    kind,
    icon: KIND_ICONS[kind],
    title: hit.title,
    path: hit.path,
    snippet,
    snippetSource: snippet ? (excerpt ? 'excerpt' : 'preview') : 'none',
    ...(excerpt?.approxLine != null ? { approxLine: excerpt.approxLine } : {}),
  };
}
