// @vitest-environment happy-dom

/**
 * Tempdoc 727 F-7 — the encryption disclosure copy overclaimed: it said "agent runs" are encrypted
 * (false — AgentHistoryIndexer writes plaintext `.md` transcripts, never covered by DataKeyManager)
 * and said "your chat history … [is] encrypted" without the forward-only qualifier (chat encryption
 * is encrypt-on-write with no backfill of pre-setup content — ConversationEncryptionController.
 * handleSetup calls only `keys.setup(pass)`). These tests pin the corrected, scoped copy in both the
 * shared at-rest card (`renderAtRestCard`, a pure projection — called directly, no component mount
 * needed) and the SecuritySurface pre-setup pitch (a private method on the LitElement, driven via a
 * detached-element harness — the same pattern as BrainSurface.reindex-coherence.test.ts).
 *
 * Fails on the pre-fix copy: the old atRestCard string was `"your chat history, memories, and agent
 * runs are encrypted with your passphrase"` (matches /agent runs are encrypted/i) and the old
 * SecuritySurface pitch was `"Encrypt your chat history with a passphrase so it can't be read without
 * unlocking."` (an unqualified encryption claim with no forward-only qualifier in the same sentence).
 */

import { describe, expect, it } from 'vitest';
import { render, type TemplateResult } from 'lit';
import { renderAtRestCard } from './security/atRestCard.js';
import type { StatusSnapshot } from '../utils/statusPoll.js';
import './SecuritySurface.js';

function renderToText(tpl: TemplateResult | typeof import('lit').nothing): string {
  const container = document.createElement('div');
  render(tpl as TemplateResult, container);
  // Template literals wrap across lines for readability; collapse whitespace so multi-line
  // sentences remain matchable as a single run of text.
  return (container.textContent ?? '').replace(/\s+/g, ' ').trim();
}

function atRestCardText(convState: 'not_configured' | 'locked' | 'unlocked' | undefined): string {
  const status = {
    atRestProtection: { diskEncryption: 'ENCRYPTED', qualityKnown: true },
    conversationProtection: convState ? { state: convState } : undefined,
  } as unknown as StatusSnapshot;
  return renderToText(renderAtRestCard(status));
}

interface ChatProtectionHarness {
  encState: string;
  encAwaitingRecoverySave: boolean;
  encRecoveryKey: string | null;
  renderChatProtection(): TemplateResult;
}

/** Detached SecuritySurface (no connectedCallback ⇒ no fetch/subscribe), driving the private
 *  chat-protection pitch renderer directly — mirrors BrainSurface's calloutText harness. */
function chatProtectionText(encState: string): string {
  const el = document.createElement('jf-security-surface') as unknown as ChatProtectionHarness;
  el.encState = encState;
  el.encAwaitingRecoverySave = false;
  el.encRecoveryKey = null;
  return renderToText(el.renderChatProtection());
}

const FALSE_AGENT_RUNS_CLAIM = /agent runs are encrypted/i;
// Pre-fix wording asserted the whole set is encrypted with no forward-only qualifier attached to
// the "encrypted" clause itself — this narrower regex only matches the un-scoped form.
const UNQUALIFIED_CHAT_HISTORY_ENCRYPTED = /chat history[^.]*\bis\s+encrypted\b(?!\s+with your passphrase[^.]*(from|since|going forward|new))/i;

describe('atRestCard disclosure copy — tempdoc 727 F-7 (encryption overclaim)', () => {
  it.each(['not_configured', 'locked', 'unlocked'] as const)(
    'does not claim agent runs are encrypted (convState=%s)',
    (convState) => {
      const text = atRestCardText(convState);
      expect(text).not.toMatch(FALSE_AGENT_RUNS_CLAIM);
    },
  );

  it.each(['not_configured', 'locked', 'unlocked'] as const)(
    'states agent-run transcripts are NOT passphrase-encrypted (convState=%s)',
    (convState) => {
      const text = atRestCardText(convState);
      expect(text).toMatch(/agent-run transcripts.*not.*passphrase-encrypted/i);
    },
  );

  it('scopes the "what this protects" claim to what is written after setup', () => {
    const text = atRestCardText('unlocked');
    expect(text).toContain('chat history and memories are encrypted');
    expect(text).toMatch(/only covers what.s written from setup onward/i);
  });

  it('no longer claims the search index "rebuilds from your original files" without qualification (false for agent-run transcripts)', () => {
    const text = atRestCardText('locked');
    expect(text).toMatch(/document index rebuilds from your original files, but agent-run transcripts do not/i);
  });
});

describe('SecuritySurface pre-setup pitch copy — tempdoc 727 F-7', () => {
  it('the not_configured pitch is forward-only, not an unqualified encryption claim', () => {
    const text = chatProtectionText('not_configured');
    expect(text).not.toMatch(UNQUALIFIED_CHAT_HISTORY_ENCRYPTED);
    expect(text).toMatch(/encrypt new chat messages/i);
    expect(text).toMatch(/applies going forward/i);
  });

  it('keeps the irreversibility warning verbatim (exemplary — do not weaken)', () => {
    const text = chatProtectionText('not_configured');
    expect(text).toContain('If you forget the passphrase, your chat history is lost for good');
  });

  it('scopes the locked-state label to new messages, not the whole chat history', () => {
    const text = chatProtectionText('locked');
    expect(text).toContain('Your new chat messages are');
    expect(text).toContain('locked');
    expect(text).not.toContain('Your chat history is');
  });

  it('scopes the unlocked-state label to new messages, not the whole chat history', () => {
    const text = chatProtectionText('unlocked');
    expect(text).toContain('Your new chat messages are');
    expect(text).toContain('encrypted and unlocked');
  });
});
