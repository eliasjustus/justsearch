import { describe, it, expect } from 'vitest';
import { originatorToTransport } from './originatorTransport.js';

describe('§32 S2 — originatorToTransport (trust-bridge mapping)', () => {
  it('agent → AGENT_LOOP (UNTRUSTED — gate engages on write/destructive)', () => {
    expect(originatorToTransport('agent')).toBe('AGENT_LOOP');
  });

  it('system → SYSTEM_INTERNAL', () => {
    expect(originatorToTransport('system')).toBe('SYSTEM_INTERNAL');
  });

  it('user → BUTTON', () => {
    expect(originatorToTransport('user')).toBe('BUTTON');
  });

  it('absent → BUTTON (preserves pre-bridge default)', () => {
    expect(originatorToTransport(undefined)).toBe('BUTTON');
  });
});
