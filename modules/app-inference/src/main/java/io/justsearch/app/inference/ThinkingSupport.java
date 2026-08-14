/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

/**
 * Whether the running llama-server can honour reasoning ("thinking") generation — the verdict the
 * runtime manifest publishes on {@code ai.thinkingSupport} (tempdoc 835 §5.2/§9c.2).
 *
 * <p>The authoritative signal is launch-argument acceptance: b8571's {@code /props} reports
 * {@code chat_template_caps} but has no {@code supports_enable_thinking} field, so per-request
 * thinking support is not advertised and cannot be read. Fail closed on <em>thinking</em>, never on
 * <em>inference</em>: a build that rejects {@code --reasoning-budget} is relaunched without it and
 * still serves answers.
 */
enum ThinkingSupport {
  /** Not determined — no server launched by us yet, or an externally-started server we adopted. */
  UNKNOWN,

  /** Launched with {@code --reasoning-budget <n>} and the server came up healthy. */
  SUPPORTED,

  /** The build rejected {@code --reasoning-budget}; relaunched without it, thinking is off. */
  UNSUPPORTED,

  /** Thinking is switched off by configuration (thinking disabled, or a reasoning budget of 0). */
  DISABLED
}
