/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.agent;

import java.util.List;

/**
 * Envelope for {@code GET /api/chat/sessions} — the persisted agent sessions, newest first.
 *
 * <p>Tempdoc 564 Phase 3: the generated wire-contract surface the FE validates at the parse boundary.
 */
public record AgentSessionsResponse(List<AgentSessionSummary> sessions) {}
