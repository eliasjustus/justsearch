// SPDX-License-Identifier: Apache-2.0
/**
 * LivenessWatchdog — a stale-window backstop for a live stream (tempdoc 604).
 *
 * The codebase's liveness law is "a live thing emits a positive signal (heartbeat); an observer arms
 * a stale-window backstop and acts when the signal stops." This is the FE observer half for a
 * fetch-body stream (the agent run): the consumer calls {@link kick} on every received frame
 * (including the backend heartbeat); if no frame arrives within `staleMs`, the stream is presumed
 * transport-hung and {@link onStale} fires. A genuinely parked-but-alive run keeps heartbeating, so
 * its watchdog never trips; only a silent hang does.
 *
 * Framework-agnostic and timer-injectable-via-`staleMs` (a `0` window disables it). `EnvelopeStream`
 * has an equivalent inline watchdog for its `EventSource` transport; this standalone primitive serves
 * the fetch-body transport that cannot reuse `EnvelopeStream`.
 */
export class LivenessWatchdog {
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private readonly staleMs: number,
    private readonly onStale: () => void,
  ) {}

  /** A signal arrived (or the stream just started) — (re)arm the stale window. */
  kick(): void {
    this.clear();
    if (this.staleMs <= 0) {
      return;
    }
    this.timer = setTimeout(() => {
      this.timer = null;
      this.onStale();
    }, this.staleMs);
  }

  /** Disarm — the stream concluded (or is being torn down). Idempotent. */
  clear(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  /** True while a stale-window timer is pending. */
  get armed(): boolean {
    return this.timer !== null;
  }
}
