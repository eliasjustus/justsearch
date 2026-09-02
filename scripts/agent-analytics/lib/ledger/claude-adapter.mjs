/**
 * lib/ledger/claude-adapter.mjs — Claude Code transcripts projected onto the
 * neutral `Call`/`ToolEvent` shape (tempdoc 886 §12 PR 1).
 *
 * WRAPS `lib/transcript-store.mjs` discovery (`discoverProjectDirs`,
 * `listSubagentPaths`, `streamLines`) — it does not re-implement transcript
 * discovery. It DOES re-implement the per-line entry walk that
 * `iterateTurns`/`parseTranscriptTokens` already do, for one reason:
 * `iterateTurns` collapses each `tool_use` block down to `{name, input}` with
 * no block `id`, and each `tool_result` down to `{isError, text}` with no
 * `tool_use_id` — so there is no way to join a tool RESULT back to the tool
 * NAME that produced it through that shape. This adapter needs that join (a
 * `ToolEvent` carries both `inputChars` from the use and `outputChars` from
 * the result), so it reads raw entries via `streamLines` instead.
 *
 * DEDUP RULE (886 §11 A3/A10, verified corpus-wide): Claude Code writes one
 * JSONL LINE per content block of a single logical assistant message, all
 * lines sharing one `message.id`, and `message.usage` repeats byte-identical
 * across those lines. So only the FIRST line for a given `message.id` becomes
 * a `Call` — but every line's `tool_use` blocks are registered into the
 * `toolUseById` map regardless of dedup, because a `tool_use` block can land
 * on a LATER line of the same message than the one that carried the usage
 * snapshot. Registering after the dedup skip would silently lose the
 * tool-name join for those blocks.
 *
 * COMPACTION: a line is a boundary when `isCompactSummary`, `subtype ===
 * 'compact_boundary'`, or `compactMetadata` is present (identical rule to
 * `transcript-store.iterateTurns`, kept in sync deliberately — see that
 * module's `isCompactBoundary` doc). The flag rides forward to the NEXT
 * Call-producing line, because compaction lines carry no usage of their own
 * (cache-efficiency.mjs's `analyseTranscript` established this "flag rides
 * the first post-boundary message" pattern first; this adapter follows it).
 */

import fs from 'node:fs';
import path from 'node:path';
import { DEFAULT_PROJECTS_ROOT, discoverProjectDirs, listSubagentPaths, streamLines } from '../transcript-store.mjs';
import { makeCall, makeToolEvent } from './record.mjs';
import { roleFor } from './tool-roles.mjs';

const TASK_NOTIFICATION_RE = /<task-notification>/;

function extractText(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) return content.filter((b) => b && b.type === 'text').map((b) => b.text || '').join('');
  return '';
}

/** Local copy of transcript-store's private extractToolResultText — not exported there. */
function extractToolResultText(block) {
  if (typeof block.content === 'string') return block.content;
  if (Array.isArray(block.content)) {
    return block.content
      .map((c) => (c && (c.text || (typeof c.content === 'string' ? c.content : ''))) || '')
      .join('\n');
  }
  return '';
}

/**
 * Split cache-creation tokens into the 5m/1h ephemeral tiers, falling back to
 * the flat `cache_creation_input_tokens` field (credited to 5m) when the
 * tiered object is absent — the same fallback `lib/transcript-cost.mjs`'s
 * private `splitCacheWrite` uses (745 item B bug 3), reimplemented here
 * because it is not exported and this module may only import sibling ledger
 * files plus transcript-store/transcript-cost (§10.4 boundary rule).
 */
function splitCacheWrite(usage) {
  const cc = usage.cache_creation;
  if (cc && typeof cc === 'object') {
    const w5 = cc.ephemeral_5m_input_tokens ?? 0;
    const w1 = cc.ephemeral_1h_input_tokens ?? 0;
    if (w5 || w1) return { w5, w1 };
  }
  return { w5: usage.cache_creation_input_tokens ?? 0, w1: 0 };
}

function isBoundaryEntry(entry) {
  return Boolean(entry.isCompactSummary || entry.subtype === 'compact_boundary' || entry.compactMetadata);
}

function compactMetadataOf(entry) {
  if (!entry.compactMetadata) return null;
  const m = entry.compactMetadata;
  return {
    trigger: m.trigger ?? null,
    preTokens: m.preTokens ?? null,
    postTokens: m.postTokens ?? null,
    durationMs: m.durationMs ?? null,
  };
}

/**
 * Parse one transcript file (main or subagent) into `{calls, toolEvents}`.
 * `lineage` is attached to every `Call` verbatim — the caller (this module's
 * `listClaudeCalls`) already knows whether this file is a main session or a
 * subagent, so this function does not infer it.
 */
function processClaudeTranscript(file, { sessionId, project, lineage }) {
  const calls = [];
  const toolEvents = [];
  const seenMessageIds = new Set();
  // tool_use block id -> {name, inputChars, callRef, ts} — populated on EVERY
  // line, before any dedup skip (see module doc).
  const toolUseById = new Map();
  let compactPending = false;
  let compactMeta = null;
  let index = 0;

  streamLines(file, (entry) => {
    if (isBoundaryEntry(entry)) {
      compactPending = true;
      // A real compaction is TWO consecutive boundary-flagged lines (verified
      // corpus-wide, 886 §12 PR 2): a `system`/`subtype:'compact_boundary'`
      // line CARRYING `compactMetadata`, immediately followed by a
      // `user`/`isCompactSummary:true` line that carries NONE. Unconditionally
      // overwriting `compactMeta` here clobbered the real metadata with the
      // second line's `null` before any Call consumed it — every boundary
      // Call in the corpus lost its trigger/preTokens/postTokens/durationMs
      // silently (context-residency.mjs's compaction ledger is what surfaced
      // this: 0 of 11 real events carried compactMetadata). Only overwrite
      // when THIS line actually carries metadata, so the first line's real
      // value survives the second line's metadata-less re-flag.
      const meta = compactMetadataOf(entry);
      if (meta) compactMeta = meta;
    }

    const content = entry.message?.content;

    if (entry.type === 'assistant') {
      const msgId = entry.message?.id ?? null;

      if (Array.isArray(content)) {
        for (const b of content) {
          if (b && b.type === 'tool_use' && b.id) {
            toolUseById.set(b.id, {
              name: b.name,
              inputChars: JSON.stringify(b.input ?? {}).length,
              callRef: msgId,
              ts: entry.timestamp ?? null,
            });
          }
        }
      }

      const usage = entry.message?.usage;
      const alreadySeen = Boolean(msgId) && seenMessageIds.has(msgId);
      if (usage && !alreadySeen) {
        if (msgId) seenMessageIds.add(msgId);

        const { w5, w1 } = splitCacheWrite(usage);
        const boundary = compactPending;
        const boundaryMeta = compactMeta;
        if (boundary) {
          compactPending = false;
          compactMeta = null; // consumed — never leak into a later metadata-less boundary
        }

        const call = makeCall({
          harness: 'claude-code',
          provider: 'anthropic',
          project,
          sessionId,
          callId: msgId ?? `${sessionId}:${index}`,
          lineage,
          ts: entry.timestamp ?? null,
          model: entry.message?.model ?? null,
          tokens: {
            fresh: usage.input_tokens ?? 0,
            cacheRead: usage.cache_read_input_tokens ?? 0,
            cacheWrite5m: w5,
            cacheWrite1h: w1,
            output: usage.output_tokens ?? 0,
            reasoning: null,
          },
          // input + cache_read + cache_creation (886 §12 rule). cache_creation
          // is read through splitCacheWrite's w5+w1, which equals the flat
          // `cache_creation_input_tokens` field except for the ~2.34% of
          // snapshots that carry ONLY the tiered form (745 item B) — using the
          // tiered sum is strictly more correct there and identical elsewhere.
          contextTokens: (usage.input_tokens ?? 0) + (usage.cache_read_input_tokens ?? 0) + w5 + w1,
          compactionBoundary: boundary,
          speed: usage.speed ?? null,
          compactMetadata: boundary && boundaryMeta ? boundaryMeta : undefined,
        });
        calls.push(call);
        index += 1;
      }
      return;
    }

    if (entry.type === 'user') {
      const text = extractText(content);
      if (TASK_NOTIFICATION_RE.test(text)) {
        toolEvents.push(makeToolEvent({
          harness: 'claude-code',
          sessionId,
          callRef: null,
          role: 'wait',
          name: 'task-notification',
          inputChars: 0,
          outputChars: text.length,
          isError: false,
          ts: entry.timestamp ?? null,
        }));
      }

      if (Array.isArray(content)) {
        for (const b of content) {
          if (!b || b.type !== 'tool_result') continue;
          const outText = extractToolResultText(b);
          const use = toolUseById.get(b.tool_use_id);
          toolEvents.push(makeToolEvent({
            harness: 'claude-code',
            sessionId,
            callRef: use?.callRef ?? null,
            role: roleFor('claude-code', use?.name ?? null),
            name: use?.name ?? '(unknown)',
            inputChars: use?.inputChars ?? 0,
            outputChars: outText.length,
            isError: Boolean(b.is_error),
            ts: entry.timestamp ?? null,
          }));
        }
      }
    }
  });

  return { calls, toolEvents };
}

function summarizeSession(sessionId, project, calls) {
  const timestamps = calls.map((c) => c.ts).filter(Boolean).sort();
  return {
    harness: 'claude-code',
    sessionId,
    project,
    firstTs: timestamps[0] ?? null,
    lastTs: timestamps[timestamps.length - 1] ?? null,
    calls: calls.length,
  };
}

/** Recursive `.jsonl` collector, depth-capped at 3 — same shape as every existing reader's walk. */
function collectJsonlFiles(dir, out, depth = 0) {
  let entries = [];
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const e of entries) {
    const p = path.join(dir, e.name);
    if (e.isDirectory() && depth < 3) collectJsonlFiles(p, out, depth + 1);
    else if (e.isFile() && e.name.endsWith('.jsonl')) out.push(p);
  }
}

/**
 * Every Claude Code transcript file this machine holds (main + subagent),
 * tagged by kind — for a consumer that needs raw FILE PATHS rather than
 * parsed `Call`s (`cache-efficiency.mjs`'s discovery, 886 §12 PR 1's first
 * migrated consumer). Deliberately the SAME recursive depth-3 walk
 * `cache-efficiency.mjs` used to do inline (`collectFiles` + a
 * `/subagents/` path regex) — centralised here so that reader's file
 * enumeration is provably unchanged (bit-identical before/after the
 * migration) while its OWN copy of the walk is deleted.
 */
export function listClaudeTranscriptFiles({ projectsRoot, projectFilter = null } = {}) {
  const root = projectsRoot ?? DEFAULT_PROJECTS_ROOT;
  const dirs = projectFilter ? discoverProjectDirs(root, projectFilter) : discoverProjectDirs(root);
  const out = [];
  for (const dir of dirs) {
    const files = [];
    collectJsonlFiles(dir.path, files);
    for (const filePath of files) {
      out.push({
        path: filePath,
        kind: /[\\/]subagents[\\/]/.test(filePath) ? 'subagent' : 'main',
        project: dir.name,
      });
    }
  }
  return out;
}

/**
 * Every Claude Code `Call`/`ToolEvent` this machine holds, across every
 * discovered project (or `projectFilter`'s subset), in the `sinceMs`/`untilMs`
 * mtime window. Never throws on a missing `~/.claude/projects` root — same
 * posture as `transcript-store.mjs`.
 */
export function listClaudeCalls({ projectsRoot, sinceMs = null, untilMs = null, projectFilter = null } = {}) {
  const root = projectsRoot ?? DEFAULT_PROJECTS_ROOT;
  const dirs = projectFilter ? discoverProjectDirs(root, projectFilter) : discoverProjectDirs(root);

  const calls = [];
  const toolEvents = [];
  const sessions = [];

  for (const dir of dirs) {
    let files;
    try {
      files = fs.readdirSync(dir.path, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const f of files) {
      if (!f.isFile() || !f.name.endsWith('.jsonl')) continue;
      const filePath = path.join(dir.path, f.name);
      let stat;
      try {
        stat = fs.statSync(filePath);
      } catch {
        continue;
      }
      if (sinceMs != null && stat.mtimeMs < sinceMs) continue;
      if (untilMs != null && stat.mtimeMs > untilMs) continue;

      const sessionId = f.name.slice(0, -'.jsonl'.length);
      const main = processClaudeTranscript(filePath, {
        sessionId,
        project: dir.name,
        lineage: { parentSessionId: null, kind: 'main' },
      });
      calls.push(...main.calls);
      toolEvents.push(...main.toolEvents);
      if (main.calls.length || main.toolEvents.length) {
        sessions.push(summarizeSession(sessionId, dir.name, main.calls));
      }

      for (const subPath of listSubagentPaths(dir.path, sessionId)) {
        const metaPath = subPath.replace(/\.jsonl$/, '.meta.json');
        let meta = {};
        try {
          meta = JSON.parse(fs.readFileSync(metaPath, 'utf8'));
        } catch {
          // no/invalid meta.json — lineage falls back to plain 'spawn' with no extra detail
        }
        const subBase = path.basename(subPath, '.jsonl');
        const subSessionId = `${sessionId}:${subBase}`;
        const sub = processClaudeTranscript(subPath, {
          sessionId: subSessionId,
          project: dir.name,
          lineage: {
            parentSessionId: sessionId,
            kind: meta.agentType === 'fork' ? 'fork' : 'spawn',
            agentType: meta.agentType ?? null,
            requestedModel: meta.model ?? null,
            description: meta.description ?? null,
          },
        });
        calls.push(...sub.calls);
        toolEvents.push(...sub.toolEvents);
        if (sub.calls.length || sub.toolEvents.length) {
          sessions.push(summarizeSession(subSessionId, dir.name, sub.calls));
        }
      }
    }
  }

  return { calls, toolEvents, sessions };
}
