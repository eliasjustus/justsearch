// SPDX-License-Identifier: Apache-2.0
/**
 * agentRecall — Tempdoc 577 §2.14 Root I (#20): the "what the agent did" digest as a PURE
 * projection of the ONE 550 federated ledger (not a second read of the FE Effect Journal).
 *
 * Before this, the catch-up digest counted agent activity from `summarizeAgentActivity` over the FE
 * Effect Journal alone — a different authority from the unified ledger History/Timeline/Inbox
 * project (the 553 drift class). This derives the same digest from {@link UnifiedActionEntry}[]
 * (the one log: backend operations + ingested FE effects), filtered to agent-originated ACTION rows
 * newer than the shared {@link recallCursor} seen-cursor. The undo/macro paths stay on the journal
 * (the undo authority) — this owns only the COUNT/SUMMARY, so the digest's notion of "what's new"
 * and the timeline's are one read.
 */
import type { UnifiedActionEntry } from '../../operations/ActionLedgerClient.js';

export interface AgentRecallDigest {
  /** Number of agent ACTION rows newer than the cursor. */
  readonly total: number;
  /** Count grouped by a coarse human activity noun. */
  readonly byKind: Readonly<Record<string, number>>;
  /** The newest `occurredAt` among the agent rows (advance the seen-cursor to this on "mark seen"). */
  readonly latestIso: string;
}

// Ledger kinds that are NOT "the assistant did X" actions: gate firings + grant lifecycle are
// authority/decision rows; index rows are system/worker outcomes. Excluded from the action count.
const NON_ACTION_KINDS: ReadonlySet<string> = new Set(['gate', 'grant', 'index']);

/**
 * Project the unified ledger into the agent recall digest: agent-originated action rows strictly
 * newer than `sinceIso` (the seen-cursor; '' = everything is new), grouped by the row's ledger
 * `kind` (operation / navigation / the FE effect kind — the same vocabulary the timeline shows).
 * Pure — same input, same output.
 */
export function summarizeAgentRecall(
  entries: ReadonlyArray<UnifiedActionEntry>,
  sinceIso: string,
): AgentRecallDigest {
  const byKind: Record<string, number> = {};
  let total = 0;
  let latestIso = sinceIso;
  for (const e of entries) {
    if (e.originator !== 'agent') continue;
    if (NON_ACTION_KINDS.has(e.kind)) continue;
    if (!(e.occurredAt > sinceIso)) continue;
    if (e.occurredAt > latestIso) latestIso = e.occurredAt;
    byKind[e.kind] = (byKind[e.kind] ?? 0) + 1;
    total += 1;
  }
  return { total, byKind, latestIso };
}
