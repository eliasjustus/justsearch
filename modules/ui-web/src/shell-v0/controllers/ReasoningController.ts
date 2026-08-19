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

export class ReasoningController {
  reasoningText = '';
  isThinking = false;
  reasoningBlocks: ReasoningBlock[] = [];

  private thinkingStartedAt: number | null = null;
  private timerInterval: number | null = null;
  private readonly onUpdate: () => void;

  constructor(onUpdate: () => void) {
    this.onUpdate = onUpdate;
  }

  handleReasoningChunk(payload: unknown): void {
    const data = payload as Record<string, unknown>;
    if (!this.isThinking) {
      this.isThinking = true;
      this.thinkingStartedAt = Date.now();
      this.startTimer();
    }
    this.reasoningText += (data.text as string) ?? '';
    this.onUpdate();
  }

  endThinking(): void {
    if (!this.isThinking) return;
    this.isThinking = false;
    this.stopTimer();
    const duration = this.thinkingStartedAt
      ? Date.now() - this.thinkingStartedAt
      : 0;
    if (this.reasoningText) {
      this.reasoningBlocks.push({ text: this.reasoningText, durationMs: duration });
    }
    this.reasoningText = '';
    this.thinkingStartedAt = null;
    this.onUpdate();
  }

  finalize(): void {
    this.endThinking();
  }

  reset(): void {
    this.reasoningText = '';
    this.isThinking = false;
    this.thinkingStartedAt = null;
    this.reasoningBlocks = [];
    this.stopTimer();
  }

  get elapsedSeconds(): number {
    if (!this.thinkingStartedAt) return 0;
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
