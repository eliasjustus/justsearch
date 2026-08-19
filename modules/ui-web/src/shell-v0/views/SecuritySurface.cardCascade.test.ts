// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom

/**
 * Fix-round F2 (tempdoc 855 §15/independent review of R3) — SecuritySurface's `.section` rule
 * (added for the flat-row idiom, T1) was UNCONDITIONAL, so at equal CSS specificity it won the
 * cascade over `atRestCardStyles`' `.card` rule for the shared `renderAtRestCard()` output
 * (`atRestCard.ts:151` — `<div class="card section">`), which is `.body`'s FIRST child:
 *   - the Data-Protection card (shared with HealthSurface) lost its card chrome (padding/border/
 *     radius/background all flattened to the bare-section values), and
 *   - an unconditional divider (`margin-top`/`border-top`) drew above the very first section.
 *
 * Fix: `.section:not(.card)` + `.section:not(.card):not(:first-child)`, mirroring
 * SettingsSurface's existing `.section:not(:first-child)` pattern. This test mounts the REAL
 * component (so `static styles` adopted-stylesheet cascade applies — cascade behavior can't be
 * observed from a detached-render harness). The card/section CSS rules read theme custom
 * properties (`--border-subtle`, `--surface-secondary`) that no token stylesheet defines in this
 * isolated unit test, so those are injected on `:root` below (inherits into the shadow tree, same
 * as a real app boot's `tokens.css`) — without them `var()` resolves to nothing and every
 * border/background assertion would pass VACUOUSLY (computed value stuck at the unstyled initial)
 * regardless of which cascade rule actually won.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import './SecuritySurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import type { StatusSnapshot } from '../utils/statusPoll.js';

const BORDER_SUBTLE = 'rgb(48, 48, 48)';
const SURFACE_SECONDARY = 'rgb(4, 5, 6)';

let tokenStyleEl: HTMLStyleElement | null = null;

interface SecuritySurfaceHarness extends HTMLElement {
  host_: unknown;
  status: StatusSnapshot | null;
  updateComplete: Promise<unknown>;
  requestUpdate(): void;
}

async function mount(): Promise<SecuritySurfaceHarness> {
  const el = document.createElement('jf-security-surface') as unknown as SecuritySurfaceHarness;
  el.host_ = createMockHostApi();
  document.body.appendChild(el);
  await el.updateComplete;
  // Drive the shared at-rest card into its populated (non-`nothing`) render: a truthy
  // `atRestProtection` is the only precondition `renderAtRestCard` checks.
  el.status = {
    atRestProtection: { diskEncryption: 'ENCRYPTED', qualityKnown: true },
    conversationProtection: { state: 'locked' },
  } as unknown as StatusSnapshot;
  el.requestUpdate();
  await el.updateComplete;
  return el;
}

describe('SecuritySurface — .card.section cascade (fix-round F2)', () => {
  beforeEach(() => {
    tokenStyleEl = document.createElement('style');
    tokenStyleEl.textContent = `:root { --border-subtle: ${BORDER_SUBTLE}; --surface-secondary: ${SURFACE_SECONDARY}; }`;
    document.head.appendChild(tokenStyleEl);
  });

  afterEach(() => {
    document.body.innerHTML = '';
    tokenStyleEl?.remove();
    tokenStyleEl = null;
  });

  it('the shared at-rest card keeps its card chrome (not flattened by the bare .section rule)', async () => {
    const el = await mount();
    const card = el.shadowRoot!.querySelector('.card.section') as Element;
    expect(card, 'renderAtRestCard() must render the shared card').toBeTruthy();
    const style = getComputedStyle(card);
    // atRestCardStyles' `.card` rule: padding 0.875rem, border-radius 0.5rem, border-color
    // var(--border-subtle), background var(--surface-secondary) — the pre-fix unconditional
    // `.section` rule zeroed every one of these (padding:0, border:none, border-radius:0,
    // background:transparent), so an exact match on the CARD's real values (not just "not zero")
    // pins that the card rule, not the bare-section rule, is the one actually winning.
    expect(style.padding).toBe('14px'); // 0.875rem @ 16px root font-size
    expect(style.borderRadius).toBe('8px'); // 0.5rem
    expect(style.borderTopStyle).toBe('solid');
    expect(style.borderTopColor).toBe(BORDER_SUBTLE);
    expect(style.backgroundColor).toBe(SURFACE_SECONDARY);
  });

  it('the card, as the FIRST section, carries no divider (no spurious top border/margin)', async () => {
    const el = await mount();
    const card = el.shadowRoot!.querySelector('.card.section') as Element;
    const body = el.shadowRoot!.querySelector('.body') as Element;
    expect(body.firstElementChild, 'the card is the first child of .body').toBe(card);
    // The divider rule only fires via `.section:not(.card):not(:first-child)`, which never matches
    // `.card.section` — its border-top-width stays the CARD's own 1px (asserted above), never the
    // divider's ADDITIONAL 1.5rem top margin.
    const style = getComputedStyle(card);
    // No rule ever sets a `margin-top` on `.card.section` (unlike the divider rule's explicit
    // `1.5rem`/24px below), so happy-dom reports the unset computed value ('' — no inline pixel
    // resolution absent an applicable declaration) rather than a synthesized '0px'; asserting it is
    // NEITHER '' NOR the divider's '24px' would be vacuous either way alone, so pin both directly.
    expect(['', '0px']).toContain(style.marginTop);
    expect(style.marginTop).not.toBe('24px');
  });

  it('the Chat-encryption section (not first-child, no .card class) keeps its divider', async () => {
    const el = await mount();
    const sections = Array.from(el.shadowRoot!.querySelectorAll('.body > .section'));
    const chatSection = sections.find((s) => !s.classList.contains('card')) as Element | undefined;
    expect(chatSection, 'the chat-encryption section must render').toBeTruthy();
    const style = getComputedStyle(chatSection as Element);
    expect(style.borderTopStyle).toBe('solid');
    expect(style.borderTopColor).toBe(BORDER_SUBTLE);
    expect(style.marginTop).toBe('24px'); // 1.5rem — the divider rule applies here
    // Never carries the CARD's own chrome (it has no `.card` class).
    expect(style.borderRadius).toBe('0px');
    expect(style.backgroundColor).not.toBe(SURFACE_SECONDARY);
  });
});
