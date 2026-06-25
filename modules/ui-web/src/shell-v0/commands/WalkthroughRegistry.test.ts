// @vitest-environment happy-dom

import { describe, it, expect, beforeEach } from 'vitest';
import {
  registerWalkthrough,
  unregisterWalkthrough,
  listWalkthroughs,
  getWalkthrough,
  onWalkthroughCatalogChange,
  __resetWalkthroughsForTest,
  type WalkthroughContribution,
} from './WalkthroughRegistry.js';
import { CORE_PROVENANCE, makePluginProvenance } from '../primitives/provenance.js';
const PLUGIN_PROVENANCE = makePluginProvenance('test-plugin', '1.0.0', 'TRUSTED_PLUGIN');

const baseSteps = [
  { id: 's1', title: 'One', body: 'first step' },
  { id: 's2', title: 'Two', body: 'second step' },
];

beforeEach(() => {
  __resetWalkthroughsForTest();
});

describe('WalkthroughRegistry', () => {
  it('register + list + getWalkthrough round-trip', () => {
    const w: WalkthroughContribution = {
      id: 'core.welcome',
      title: 'Welcome',
      priority: 0,
      source: 'core',
      provenance: CORE_PROVENANCE,
      steps: baseSteps,
    };
    registerWalkthrough(w);
    expect(getWalkthrough('core.welcome')).toEqual(w);
    expect(listWalkthroughs()).toEqual([w]);
  });

  it('rejects walkthroughs with no steps', () => {
    expect(() =>
      registerWalkthrough({
        id: 'bad',
        title: 'bad',
        priority: 0,
        source: 'core',
        provenance: CORE_PROVENANCE,
        steps: [],
      }),
    ).toThrow(/no steps/);
  });

  it('sorts list by ascending priority', () => {
    registerWalkthrough({ id: 'a', title: 'A', priority: 10, source: 'core', provenance: CORE_PROVENANCE, steps: baseSteps });
    registerWalkthrough({ id: 'b', title: 'B', priority: 1, source: 'core', provenance: CORE_PROVENANCE, steps: baseSteps });
    registerWalkthrough({ id: 'c', title: 'C', priority: 5, source: 'plugin', provenance: PLUGIN_PROVENANCE, steps: baseSteps });
    expect(listWalkthroughs().map((w) => w.id)).toEqual(['b', 'c', 'a']);
  });

  it('unregister removes the contribution', () => {
    registerWalkthrough({ id: 'x', title: 'X', priority: 0, source: 'core', provenance: CORE_PROVENANCE, steps: baseSteps });
    expect(unregisterWalkthrough('x')).toBe(true);
    expect(getWalkthrough('x')).toBeUndefined();
    expect(unregisterWalkthrough('x')).toBe(false);
  });

  it('notifies listeners on register / unregister', () => {
    let count = 0;
    const off = onWalkthroughCatalogChange(() => count++);
    registerWalkthrough({ id: 'a', title: 'A', priority: 0, source: 'core', provenance: CORE_PROVENANCE, steps: baseSteps });
    expect(count).toBe(1);
    unregisterWalkthrough('a');
    expect(count).toBe(2);
    off();
  });

  it('filters out walkthroughs whose when-clause does not match', () => {
    registerWalkthrough({
      id: 'visible',
      title: 'V',
      priority: 0,
      source: 'core',
      provenance: CORE_PROVENANCE,
      steps: baseSteps,
    });
    registerWalkthrough({
      id: 'gated-out',
      title: 'F',
      priority: 1,
      source: 'core',
      provenance: CORE_PROVENANCE,
      // ShellContext.activeProfile defaults to 'default' so this never matches.
      when: "activeProfile == 'no-such-profile'",
      steps: baseSteps,
    });
    const ids = listWalkthroughs().map((w) => w.id);
    expect(ids).toEqual(['visible']);
  });
});
