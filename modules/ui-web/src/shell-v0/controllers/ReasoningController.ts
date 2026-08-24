// SPDX-License-Identifier: Apache-2.0
export interface ReasoningBlock {
  readonly text: string;
  readonly durationMs: number;
}

/**
 * Tempdoc 848 §2.5 — the ONE parser for a persisted reasoning array (`attributes.reasoning` on an
 * assistant record). Both windows import it: two independent `typeof x.text === 'string'` walks in
 * two views is exactly the drift the register discipline exists to stop. Malformed elements are
 * dropped rather than guessed at — the record is the authority, and half a block is not a block.
 */
export function reasoningBlocksFromRecord(value: unknown): ReasoningBlock[] {
  if (!Array.isArray(value)) return [];
  const blocks: ReasoningBlock[] = [];
  for (const raw of value) {
    if (typeof raw !== 'object' || raw === null) continue;
    const entry = raw as Record<string, unknown>;
    if (typeof entry.text !== 'string' || entry.text === '') continue;
    const durationMs = typeof entry.durationMs === 'number' && Number.isFinite(entry.durationMs)
      ? entry.durationMs
      : 0;
    blocks.push({ text: entry.text, durationMs });
  }
  return blocks;
}

/**
 * Tempdoc 859 §A §1.2 — the region's two boundaries, previously conflated in a single
 * `endThinking()`.
 *
 * MARK OUTPUT fires on the first non-`reasoning_chunk` event, INCLUDING a text `chunk`: it freezes
 * the duration, stops the ticker and drops the live affordance, but leaves the region OPEN so that
 * reasoning separated only by text still coalesces into one block (the 848 `chunk`-transparency
 * rule, which the record fold has always applied and the live side never did).
 *
 * CUT fires on the first non-`chunk`, non-`reasoning_chunk` event and closes the region into a
 * block. `endThinking()` survives as the two together, byte-identical for its existing callers.
 */
export class ReasoningController {
  reasoningText = '';
  reasoningBlocks: ReasoningBlock[] = [];

  /**
   * A region EXISTS. Distinct from {@link isThinking}, which is the display state: after
   * {@link markOutput} the region is still open (more reasoning may join it) but nothing about it is
   * in progress any more, so it must not wear the live affordance.
   */
  private regionOpen = false;
  /** The region has produced output, so its duration is settled. */
  private outputSeen = false;
  /** Frozen at {@link markOutput} — the 848 semantic: first reasoning token → first output of any kind. */
  private frozenDurationMs: number | null = null;
  private thinkingStartedAt: number | null = null;
  private timerInterval: number | null = null;
  private readonly onUpdate: () => void;

  constructor(onUpdate: () => void) {
    this.onUpdate = onUpdate;
  }

  /**
   * DERIVED, not stored (859 §A §1.2 / A3): a finished region can no longer claim to be in progress,
   * because "in progress" is now "a region is open AND it has produced no output". The stored flag
   * this replaces was controller-wide and was cleared by exactly one site — the first text chunk — so
   * on a delegate run that thought, called a tool, thought again and only then answered, every
   * already-finished thought wore the pulse until the run ended.
   */
  get isThinking(): boolean {
    return this.regionOpen && !this.outputSeen;
  }

  handleReasoningChunk(payload: unknown): void {
    const data = payload as Record<string, unknown>;
    if (!this.regionOpen) {
      this.regionOpen = true;
      this.outputSeen = false;
      this.frozenDurationMs = null;
      this.thinkingStartedAt = Date.now();
      this.startTimer();
    }
    this.reasoningText += (data.text as string) ?? '';
    this.onUpdate();
  }

  /**
   * The region produced output. Freezes the duration and drops the live affordance; the region stays
   * open. `thinkingStartedAt` is deliberately NOT cleared: a subsequent reasoning chunk belongs to
   * this same region, and clearing it would restart the clock mid-region and corrupt the duration.
   */
  markOutput(): void {
    if (!this.regionOpen || this.outputSeen) return;
    this.outputSeen = true;
    // `!== null`, not truthiness: an epoch-0 clock (every fake-timer test) is a REAL start time, and
    // reading it as "unset" reports every measured region as 0ms.
    this.frozenDurationMs = this.thinkingStartedAt !== null ? Date.now() - this.thinkingStartedAt : 0;
    this.stopTimer();
    this.onUpdate();
  }

  /**
   * Close the open region into a block. PUSHES it onto {@link reasoningBlocks} AND returns it — not
   * either/or: five live readers depend on the array being populated, and the run timeline needs the
   * block as a value to place in stream order. Returns `null` for a blank region (nothing was
   * thought) and for no region at all.
   */
  closeRegion(): ReasoningBlock | null {
    if (!this.regionOpen) return null;
    const duration =
      this.frozenDurationMs ??
      (this.thinkingStartedAt !== null ? Date.now() - this.thinkingStartedAt : 0);
    const text = this.reasoningText;
    this.regionOpen = false;
    this.outputSeen = false;
    this.frozenDurationMs = null;
    this.reasoningText = '';
    this.thinkingStartedAt = null;
    this.stopTimer();
    this.onUpdate();
    if (!text) return null;
    const block: ReasoningBlock = { text, durationMs: duration };
    this.reasoningBlocks.push(block);
    return block;
  }

  endThinking(): void {
    this.markOutput();
    this.closeRegion();
  }

  finalize(): void {
    this.endThinking();
  }

  reset(): void {
    this.reasoningText = '';
    this.regionOpen = false;
    this.outputSeen = false;
    this.frozenDurationMs = null;
    this.thinkingStartedAt = null;
    this.reasoningBlocks = [];
    this.stopTimer();
  }

  get elapsedSeconds(): number {
    if (this.thinkingStartedAt === null) return 0;
    return Math.max(1, Math.round((Date.now() - this.thinkingStartedAt) / 1000));
  }

  destroy(): void {
    this.stopTimer();
  }

  private startTimer(): void {
    this.stopTimer();
    this.timerInterval = window.setInterval(() => this.onUpdate(), 1000);
  }

  private stopTimer(): void {
    if (this.timerInterval !== null) {
      window.clearInterval(this.timerInterval);
      this.timerInterval = null;
    }
  }
}
