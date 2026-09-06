/**
 * lib/ledger/record.mjs — the harness-neutral session-ledger record (tempdoc 886
 * §10.2/§12 PR 1).
 *
 * WHY THIS EXISTS. Every existing reader under scripts/agent-analytics/ speaks
 * Claude Code's transcript shape natively (message.usage.input_tokens, etc).
 * Tempdoc 886 found that the unit of cost — context tokens re-presented per API
 * call — is a harness-neutral idea; only the field NAMES differ per provider.
 * `Call` and `ToolEvent` are that neutral shape: a `harness`/`provider`-tagged
 * projection of each adapter's own log, never a second authority (the
 * repo-wide "projection vs fork" discovery rule for new representations of
 * existing data).
 *
 * ABSENT AXES ARE NULL, NEVER 0 (886 §12 contract). Codex has no billable cache
 * write (`cacheWrite5m`/`cacheWrite1h` stay null); Claude has no reasoning-token
 * axis (`reasoning` stays null). A reader must not sum a null axis as if it were
 * zero spend — it means "this provider does not bill this", not "billed nothing
 * this call".
 *
 * `makeCall`/`makeToolEvent` are the only constructors: every adapter builds
 * records through them so a missing required field fails fast at the adapter
 * boundary, not silently downstream in a reader. `isCall`/`isToolEvent` are
 * pure shape checks for tests and for `lib/ledger/index.mjs` (which does not
 * re-validate every field, just merges what each adapter already validated).
 */

const VALID_HARNESSES = new Set(['claude-code', 'codex-cli']);
/**
 * `main`/`spawn`/`fork` are produced today. Claude derives `spawn`/`fork`
 * from `subagents/*.meta.json`; Codex derives `spawn` from
 * `session_meta.payload.source.subagent.thread_spawn`, which now carries an
 * explicit parent thread id. `resume` and `thread` remain reserved vocabulary:
 *
 *   - `thread` needs a parent identifier for non-spawn inter-agent traffic.
 *     Codex `inter_agent_communication_metadata` still provides only a
 *     session-level fact, so it cannot establish that edge.
 *   - `resume`  needs an explicit resumed-FROM linkage: a Codex rollout
 *     whose `session_meta` (or a dedicated field) names the prior rollout
 *     it resumes, or a Claude Code transcript carrying `--resume`'s source
 *     sessionId. Neither adapter reads such a field today.
 *
 * Adding either requires the SAME evidence standard as every other rule in
 * this module: a field observed in real payloads, not an inferred label.
 */
const VALID_LINEAGE_KINDS = new Set(['main', 'spawn', 'fork', 'resume', 'thread']);

/**
 * Build a normalised `Call`. Throws on a missing `harness` or `sessionId` —
 * those two are the record's identity; everything else degrades to `null`/`0`
 * rather than throwing, because a real transcript line legitimately omits most
 * fields (a boundary line has no token usage at all, for instance).
 */
export function makeCall(partial) {
  if (!partial || typeof partial !== 'object') {
    throw new Error('makeCall: partial must be an object');
  }
  const { harness, sessionId } = partial;
  if (!harness) throw new Error('makeCall: harness is required');
  if (!VALID_HARNESSES.has(harness)) throw new Error(`makeCall: unknown harness "${harness}"`);
  if (!sessionId) throw new Error('makeCall: sessionId is required');

  const lineageIn = partial.lineage || {};
  const kind = lineageIn.kind ?? 'main';
  if (!VALID_LINEAGE_KINDS.has(kind)) throw new Error(`makeCall: unknown lineage.kind "${kind}"`);

  const tokensIn = partial.tokens || {};

  const call = {
    harness,
    provider: partial.provider ?? null,
    project: partial.project ?? null,
    sessionId,
    callId: partial.callId ?? null,
    lineage: {
      parentSessionId: lineageIn.parentSessionId ?? null,
      kind,
      agentType: lineageIn.agentType ?? null,
      requestedModel: lineageIn.requestedModel ?? null,
      description: lineageIn.description ?? null,
    },
    ts: partial.ts ?? null,
    model: partial.model ?? null,
    reasoningEffort: partial.reasoningEffort ?? null,
    tokens: {
      fresh: tokensIn.fresh ?? 0,
      cacheRead: tokensIn.cacheRead ?? null,
      cacheWrite5m: tokensIn.cacheWrite5m ?? null,
      cacheWrite1h: tokensIn.cacheWrite1h ?? null,
      output: tokensIn.output ?? 0,
      reasoning: tokensIn.reasoning ?? null,
    },
    contextTokens: partial.contextTokens ?? 0,
    compactionBoundary: Boolean(partial.compactionBoundary),
    speed: partial.speed ?? null,
    // True when an adapter FABRICATED this Call rather than reading it off a
    // real usage snapshot — e.g. the Codex adapter's zero-token boundary Call
    // for a `compacted` line with no following `token_count` event. Default
    // false: a Call is real unless an adapter says otherwise.
    synthetic: Boolean(partial.synthetic),
  };

  // Optional passthrough metadata a caller attached (e.g. the Claude adapter's
  // compactMetadata on a boundary call). Not part of the required shape, so it
  // is copied verbatim rather than validated here.
  if (partial.compactMetadata !== undefined) call.compactMetadata = partial.compactMetadata;
  if (partial.truncated !== undefined) call.truncated = partial.truncated;

  return call;
}

/** True when `x` has the minimum shape a `Call` must have. */
export function isCall(x) {
  return Boolean(x) && typeof x === 'object'
    && VALID_HARNESSES.has(x.harness)
    && typeof x.sessionId === 'string' && x.sessionId.length > 0
    && x.lineage && typeof x.lineage === 'object'
    && VALID_LINEAGE_KINDS.has(x.lineage.kind)
    && x.tokens && typeof x.tokens === 'object'
    && typeof x.tokens.fresh === 'number'
    && typeof x.tokens.output === 'number'
    && typeof x.contextTokens === 'number';
}

const VALID_ROLES = new Set(['read', 'edit', 'shell', 'search', 'spawn', 'wait', 'web', 'other']);

/** Build a normalised `ToolEvent`. Throws on a missing `harness` or `sessionId`. */
export function makeToolEvent(partial) {
  if (!partial || typeof partial !== 'object') {
    throw new Error('makeToolEvent: partial must be an object');
  }
  if (!partial.harness) throw new Error('makeToolEvent: harness is required');
  if (!partial.sessionId) throw new Error('makeToolEvent: sessionId is required');

  const role = VALID_ROLES.has(partial.role) ? partial.role : 'other';

  const event = {
    harness: partial.harness,
    sessionId: partial.sessionId,
    callRef: partial.callRef ?? null,
    role,
    name: partial.name ?? '(unknown)',
    inputChars: partial.inputChars ?? 0,
    outputChars: partial.outputChars ?? 0,
    isError: Boolean(partial.isError),
    ts: partial.ts ?? null,
  };
  if (partial.truncated !== undefined) event.truncated = partial.truncated;
  return event;
}

/** True when `x` has the minimum shape a `ToolEvent` must have. */
export function isToolEvent(x) {
  return Boolean(x) && typeof x === 'object'
    && typeof x.harness === 'string' && x.harness.length > 0
    && typeof x.sessionId === 'string' && x.sessionId.length > 0
    && VALID_ROLES.has(x.role)
    && typeof x.name === 'string'
    && typeof x.inputChars === 'number'
    && typeof x.outputChars === 'number';
}
