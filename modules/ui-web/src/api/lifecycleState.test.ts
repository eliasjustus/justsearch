import { describe, it, expect } from 'vitest';
import { LIFECYCLE } from './lifecycleState';
import { LifecycleState } from './generated/status_pb';

/**
 * 548 S5 (§4.1): pins the FE lifecycle-state constants to the exact proto-canonical wire strings the
 * backend emits via `LifecycleState.wireName()`. If protobuf-es ever stops stripping the
 * `LIFECYCLE_STATE_` prefix (or the proto enum is renamed), these break here rather than silently
 * mis-comparing `/api/status` states in StatusDeck / HealthSurface.
 */
describe('LIFECYCLE constants (derived from the proto enum authority)', () => {
  it('equal the short wire strings the backend serializes', () => {
    expect(LIFECYCLE.STARTING).toBe('LIFECYCLE_STATE_STARTING');
    expect(LIFECYCLE.READY).toBe('LIFECYCLE_STATE_READY');
    expect(LIFECYCLE.DEGRADED).toBe('LIFECYCLE_STATE_DEGRADED');
    expect(LIFECYCLE.ERROR).toBe('LIFECYCLE_STATE_ERROR');
    expect(LIFECYCLE.STOPPING).toBe('LIFECYCLE_STATE_STOPPING');
    expect(LIFECYCLE.STOPPED).toBe('LIFECYCLE_STATE_STOPPED');
  });

  it('are sourced from the generated proto enum reverse-mapping', () => {
    expect(LIFECYCLE.READY).toBe('LIFECYCLE_STATE_' + LifecycleState[LifecycleState.READY]);
    expect(LIFECYCLE.STARTING).toBe('LIFECYCLE_STATE_' + LifecycleState[LifecycleState.STARTING]);
  });
});
