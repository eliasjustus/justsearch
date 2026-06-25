// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { notifyDraftKeptOnce, __resetDraftKeptForTest } from './draftKeptHint.js';
import { EPHEMERAL_TOAST_EVENT } from '../components/advisory/ephemeralToast.js';

describe('draftKeptHint (tempdoc 609 §R T1.4)', () => {
  beforeEach(() => __resetDraftKeptForTest());

  it('emits a draft-kept toast once per surface, only when a draft exists', () => {
    const handler = vi.fn();
    document.addEventListener(EPHEMERAL_TOAST_EVENT, handler);

    notifyDraftKeptOnce('core.unified-chat-surface', false); // no draft → nothing
    expect(handler).toHaveBeenCalledTimes(0);

    notifyDraftKeptOnce('core.unified-chat-surface', true); // first leave with a draft → one toast
    expect(handler).toHaveBeenCalledTimes(1);
    const detail = (handler.mock.calls[0]![0] as CustomEvent<{ classId?: string; message: string }>).detail;
    expect(detail.classId).toBe('core.draft-kept');
    expect(detail.message).toBe('Draft kept');

    notifyDraftKeptOnce('core.unified-chat-surface', true); // again → suppressed (teach, don't nag)
    expect(handler).toHaveBeenCalledTimes(1);

    notifyDraftKeptOnce('core.presentation-editor-surface', true); // a different surface → its own one-shot
    expect(handler).toHaveBeenCalledTimes(2);

    document.removeEventListener(EPHEMERAL_TOAST_EVENT, handler);
  });
});
