/**
 * 569 §9 — the de-risk spike: one external declaration drives three maximally-different surface
 * KINDS (static settings pane / results list / dynamic agent surface) through the SAME engine + gate.
 *
 * This is the falsification test the tempdoc names. It asserts the structural claim (one declaration
 * → three kinds → all pass the install-time conformance gate) AND records the HONEST ceiling: the
 * content is declarable to the generic-renderer level; bespoke polish + interaction are the §7 long
 * tail (custom renderers + the Move-8 statechart), not a wall.
 */
import { describe, it, expect } from 'vitest';
import { certifyPresentation } from './conformanceGate.js';
import {
  THREE_SURFACE_SPIKE,
  RESULTS_LIST_BODY,
  AGENT_STATUS_BODY,
  SETTINGS_INTERFACE_BODY,
} from './builtinPresentations.js';
import {
  applyPresentationBodies,
  activeBodyFor,
  __resetPresentationForTest,
} from '../state/presentationRuntime.js';

describe('§9 three-surface spike', () => {
  it('one declaration spans all three surface kinds and passes the conformance gate', () => {
    const { verdict, declaration } = certifyPresentation(THREE_SURFACE_SPIKE);
    expect(verdict.ok).toBe(true);
    expect(declaration).not.toBeNull();
    expect(Object.keys(declaration?.body ?? {}).sort()).toEqual([
      'core.agent',
      'core.results',
      'core.settings.interface',
    ]);
  });

  it('each of the three bodies is itself a valid renderer declaration (schema + uischema)', () => {
    for (const body of [SETTINGS_INTERFACE_BODY, RESULTS_LIST_BODY, AGENT_STATUS_BODY]) {
      expect(body.schema).toBeTypeOf('object');
      expect((body.uischema as { type?: string }).type).toBe('VerticalLayout');
    }
  });

  it('applying the spike exposes all three region bodies to their surfaces', () => {
    __resetPresentationForTest();
    const res = applyPresentationBodies(THREE_SURFACE_SPIKE);
    expect(res.ok).toBe(true);
    expect(activeBodyFor('core.settings.interface')).toBeDefined();
    expect(activeBodyFor('core.results')).toBeDefined();
    expect(activeBodyFor('core.agent')).toBeDefined();
  });
});
