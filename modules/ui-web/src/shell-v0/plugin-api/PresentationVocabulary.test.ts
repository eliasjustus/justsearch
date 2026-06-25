import { describe, expect, it } from 'vitest';

import {
  assertConstrainedPresentation,
  HOST_PRESENTATION_PREFIX,
  isConstrainedPresentationTag,
  isPresentationAdmissible,
} from './PresentationVocabulary.js';
import type { PluginSurfaceContribution } from './plugin-types.js';

function surface(mountTag: string): PluginSurfaceContribution {
  return {
    id: 'acme.dash',
    mountTag,
    labelKey: 'k.label',
    descriptionKey: 'k.desc',
    audience: 'USER',
    placement: 'RAIL',
  };
}

describe('PresentationVocabulary — §4.4 constrained component vocabulary', () => {
  it('recognizes the host jf-* vocabulary', () => {
    expect(HOST_PRESENTATION_PREFIX).toBe('jf-');
    expect(isConstrainedPresentationTag('jf-library-surface')).toBe(true);
    expect(isConstrainedPresentationTag('acme.dashboard')).toBe(false);
    expect(isConstrainedPresentationTag('div')).toBe(false);
  });

  it('lets CORE/TRUSTED plugins mount their own namespaced element', () => {
    expect(isPresentationAdmissible(surface('acme.dashboard'), 'CORE')).toBe(true);
    expect(isPresentationAdmissible(surface('acme.dashboard'), 'TRUSTED_PLUGIN')).toBe(true);
  });

  it('forbids an UNTRUSTED plugin from mounting its own element (a second authority)', () => {
    expect(isPresentationAdmissible(surface('acme.dashboard'), 'UNTRUSTED_PLUGIN')).toBe(false);
    expect(() =>
      assertConstrainedPresentation(surface('acme.dashboard'), 'UNTRUSTED_PLUGIN'),
    ).toThrow(/second presentation authority/);
  });

  it('lets an UNTRUSTED plugin mount a constrained host vocabulary component', () => {
    expect(isPresentationAdmissible(surface('jf-form'), 'UNTRUSTED_PLUGIN')).toBe(true);
    expect(() =>
      assertConstrainedPresentation(surface('jf-form'), 'UNTRUSTED_PLUGIN'),
    ).not.toThrow();
  });
});
