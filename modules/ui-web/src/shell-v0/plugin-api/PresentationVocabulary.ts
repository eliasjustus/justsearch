// SPDX-License-Identifier: Apache-2.0
/**
 * §4.4 PRESENTATION axis — the constrained component vocabulary that cannot emit a second
 * presentation authority (tempdoc 560 §4.4).
 *
 * The PRESENTATION axis projection of the one declaration model is a plugin's
 * {@link PluginSurfaceContribution}, which mounts a custom element by tag (`mountTag`). An arbitrary
 * plugin-defined element can paint raw colors / styles — a SECOND presentation authority that
 * bypasses the single theme authority the tempdoc-557 gates (presentation-purity / color-tokens /
 * theme-token-closure) establish for host code.
 *
 * The constraint: an UNTRUSTED plugin's PRESENTATION contribution may only mount a component from the
 * host's constrained vocabulary — the `jf-` design-system namespace. Every `jf-*` component is host
 * code under the 557 presentation-authority gates, so it is theme-token-bound by construction:
 * composing it cannot emit a second authority. A CORE / TRUSTED plugin (compiled-in, itself under the
 * 557 gates, or host-endorsed) may mount its own namespaced element.
 *
 * This is the PRESENTATION analogue of the host-owns-truth Trust substrate: the host owns the
 * presentation authority exactly as it owns the `core.*` namespace; an untrusted contribution may
 * compose host vocabulary but never mint its own authority.
 */

import type { PluginSurfaceContribution, PluginTrustTier } from './plugin-types.js';

/** The host design-system namespace — the constrained PRESENTATION vocabulary's tag prefix. */
export const HOST_PRESENTATION_PREFIX = 'jf-';

/**
 * Is {@code tag} part of the constrained host vocabulary? A `jf-*` tag is a host design-system
 * component, theme-token-bound by the 557 gates, so it cannot emit a second presentation authority.
 */
export function isConstrainedPresentationTag(tag: string): boolean {
  return typeof tag === 'string' && tag.startsWith(HOST_PRESENTATION_PREFIX);
}

/**
 * May a plugin of {@code tier} mount {@code contribution}'s element?
 *
 *  - CORE / TRUSTED_PLUGIN: yes — host-endorsed (and, when compiled-in, itself under the 557 gates),
 *    so it may mount its own namespaced element.
 *  - UNTRUSTED_PLUGIN: only if the mountTag is in the constrained host vocabulary (`jf-*`) — an
 *    arbitrary untrusted element would be a second presentation authority.
 */
export function isPresentationAdmissible(
  contribution: PluginSurfaceContribution,
  tier: PluginTrustTier,
): boolean {
  if (tier !== 'UNTRUSTED_PLUGIN') {
    return true;
  }
  return isConstrainedPresentationTag(contribution.mountTag);
}

/**
 * The Trust-substrate admission for the PRESENTATION axis: throws when an untrusted plugin's surface
 * would emit a second presentation authority (a non-vocabulary mountTag).
 *
 * @throws Error if {@code contribution} is inadmissible for {@code tier}
 */
export function assertConstrainedPresentation(
  contribution: PluginSurfaceContribution,
  tier: PluginTrustTier,
): void {
  if (!isPresentationAdmissible(contribution, tier)) {
    throw new Error(
      `Presentation authority (§4.4): UNTRUSTED plugin surface '${contribution.id}' may not mount ` +
        `its own element '${contribution.mountTag}' — a second presentation authority. Untrusted ` +
        `surfaces may only mount the constrained host vocabulary ('${HOST_PRESENTATION_PREFIX}*').`,
    );
  }
}
