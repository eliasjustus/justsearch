// SPDX-License-Identifier: Apache-2.0
// Tempdoc 561 (surface tier) — the ONE shared AgentSessionController.
//
// The one interaction window's inline agent run AND the retrospective panel (Sessions / Timeline /
// History) read the SAME surface-agnostic controller, so the panels project the same governed
// records the live run does (no second authority, no divergence). The controller is created lazily
// by whichever consumer mounts first (the window or the panel) and fans its one `onUpdate` callback
// out to all subscribers.
import { AgentSessionController } from '../controllers/AgentSessionController.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';

let _ctrl: AgentSessionController | null = null;
const _subs = new Set<() => void>();

function notifyAll(): void {
  for (const s of _subs) {
    try {
      s();
    } catch {
      /* swallow listener errors */
    }
  }
}

/** Get the one shared controller, creating it on first access (with the host's apiBase/host). */
export function getAgentSessionController(
  apiBase: string,
  host_?: PluginHostApi,
): AgentSessionController {
  if (!_ctrl) {
    _ctrl = new AgentSessionController(apiBase, notifyAll);
    if (host_) _ctrl.setHost(host_);
    _ctrl.startPolling();
  } else if (host_) {
    _ctrl.setHost(host_);
  }
  return _ctrl;
}

/** The controller if it already exists, else null (no side effect). */
export function peekAgentSessionController(): AgentSessionController | null {
  return _ctrl;
}

/** Subscribe to the shared controller's updates. Returns an unsubscribe fn. */
export function subscribeAgentSession(listener: () => void): () => void {
  _subs.add(listener);
  return () => _subs.delete(listener);
}

/** Test-only reset. */
export function __resetAgentSessionStore(): void {
  _ctrl?.destroy();
  _ctrl = null;
  _subs.clear();
}
