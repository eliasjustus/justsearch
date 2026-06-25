// SPDX-License-Identifier: Apache-2.0
export interface ReasoningBlock {
  text: string;
  durationMs: number;
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
