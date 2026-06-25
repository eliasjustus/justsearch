// @vitest-environment happy-dom

import { describe, expect, it } from 'vitest';
import { LivenessWatchdog } from './LivenessWatchdog.js';

const wait = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));

describe('LivenessWatchdog', () => {
  it('fires onStale when no kick arrives within the window', async () => {
    let fired = 0;
    const wd = new LivenessWatchdog(30, () => fired++);
    wd.kick();
    expect(wd.armed).toBe(true);
    await wait(50);
    expect(fired).toBe(1);
    expect(wd.armed).toBe(false);
  });

  it('does NOT fire while kicks keep arriving inside the window (parked-but-alive)', async () => {
    let fired = 0;
    const wd = new LivenessWatchdog(40, () => fired++);
    wd.kick();
    for (let i = 0; i < 4; i++) {
      await wait(20); // < 40ms window
      wd.kick();
    }
    expect(fired).toBe(0);
    wd.clear();
  });

  it('clear() disarms a pending timer', async () => {
    let fired = 0;
    const wd = new LivenessWatchdog(20, () => fired++);
    wd.kick();
    wd.clear();
    expect(wd.armed).toBe(false);
    await wait(40);
    expect(fired).toBe(0);
  });

  it('a 0 window disables the watchdog', async () => {
    let fired = 0;
    const wd = new LivenessWatchdog(0, () => fired++);
    wd.kick();
    expect(wd.armed).toBe(false);
    await wait(20);
    expect(fired).toBe(0);
  });

  it('kick() re-arms from the latest signal (sliding window)', async () => {
    let fired = 0;
    const wd = new LivenessWatchdog(40, () => fired++);
    wd.kick();
    await wait(30);
    wd.kick(); // resets — should now fire ~40ms from here, not from the first kick
    await wait(25);
    expect(fired).toBe(0); // 55ms since first kick, but only 25ms since the re-arm
    await wait(30);
    expect(fired).toBe(1);
  });
});
