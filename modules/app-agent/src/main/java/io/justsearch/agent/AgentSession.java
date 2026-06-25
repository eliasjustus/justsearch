/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import io.justsearch.agent.api.AgentErrorCode;
import io.justsearch.agent.api.ToolCallRequest;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.registry.OperationResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Holds state for a single agent session (one user interaction). */
final class AgentSession {
  private static final Logger LOG = LoggerFactory.getLogger(AgentSession.class);
  private static final ObjectMapper NORM_MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  private final List<Map<String, Object>> messages;
  private final Map<String, CompletableFuture<Boolean>> approvalGates = new ConcurrentHashMap<>();
  /**
   * Tempdoc 508 §11.5 / §13.5 Phase B — futures keyed by callId for
   * {@code vop_*} virtual tool invocations. When the LLM calls a
   * virtual tool, the agent loop emits {@code AgentEvent.ToolCallVirtual}
   * and blocks on the corresponding future until the FE POSTs the
   * result via {@code /api/chat/agent/tool-result}. Cleared on
   * resolve to bound memory.
   */
  private final Map<String, CompletableFuture<VirtualToolResult>> virtualToolFutures =
      new ConcurrentHashMap<>();
  private final List<ExecutedToolCall> executedTools = new ArrayList<>();
  private volatile boolean cancelled;
  private int iterationsUsed;
  private String lastCallSignature;
  private int consecutiveIdenticalCalls;
  private int loopBlockCount;

  // Multi-agent handoff state (loop-thread-only, no synchronisation needed)
  private String activeAgentId;
  private int agentIterationsSinceHandoff;
  private final List<Map<String, Object>> handoffHistory = new ArrayList<>();
  private int totalHandoffs = 0;

  // Token budget tracking (thread-safe via AtomicInteger)
  private final AtomicInteger budgetRemaining;
  private final AtomicInteger promptTokensConsumed;
  private final AtomicInteger completionTokensConsumed;

  // Tempdoc 577 §2.14 Root II (#14) — the model's context window (n_ctx), the cognitive-headroom
  // denominator. Set once at run start by AgentLoopService (the one site that resolves it from the
  // OnlineAiService); 0 when unknown ⇒ the FE shows no horizon ratio. Volatile: written on the loop
  // thread, read when emitting the budget event on the same thread (defensive against future moves).
  private volatile int contextWindow = 0;

  // Tempdoc 577 §2.14 Root I (#13) — the session-local event hub: the run is an OBSERVED entity, not
  // owned by the socket that started it. The loop publishes every event here; N observers (the
  // initiating SSE writer + any reattaching tab) subscribe (replay the buffer + receive ongoing).
  // ~1000 events is a generous reattach window; the full history lives on events.ndjson.
  private final RunEventHub eventHub = new RunEventHub(1000);

  // Tempdoc 415: session-lifecycle observability state. Loop-thread-only access; markTerminated
  // is idempotent (WARN-on-second-call) so a future regression that triggers it twice doesn't
  // corrupt the captured reason.
  private final Instant startedAt = Instant.now();
  private TerminalDisposition disposition;
  private AgentErrorCode terminationCode;
  private CancelTrigger cancelTrigger;
  private boolean terminated;

  /** Backward-compatible constructor for single-agent sessions (no initial agent ID). */
  AgentSession(List<Map<String, Object>> messages, int initialBudget) {
    this(messages, initialBudget, null);
  }

  AgentSession(List<Map<String, Object>> messages, int initialBudget, String initialAgentId) {
    this.messages = new ArrayList<>(messages);
    this.budgetRemaining = new AtomicInteger(initialBudget);
    this.promptTokensConsumed = new AtomicInteger(0);
    this.completionTokensConsumed = new AtomicInteger(0);
    this.activeAgentId = initialAgentId != null ? initialAgentId : "primary";
  }

  List<Map<String, Object>> messages() {
    return messages;
  }

  boolean isCancelled() {
    return cancelled;
  }

  /**
   * Tempdoc 561 P-D — whether this run is a BACKGROUND (non-interactive) run: no watcher is present to
   * approve gated tool calls, so the safety gate is safe-by-default (write/destructive ops are rejected
   * immediately rather than waiting for an approval that will never come). Set by the background-run
   * entry point ({@code AgentLoopService.runAgent(..., background=true)}).
   */
  private volatile boolean background;

  boolean isBackground() {
    return background;
  }

  void markBackground() {
    background = true;
  }

  /**
   * Tempdoc 561 P-D — the user's autonomy dial for this run. Volatile so a mid-run dial change
   * (POST /api/chat/agent/autonomy) takes effect on the NEXT tool-call gate. Default ASSIST (the
   * safe default: reads auto-run, writes require approval).
   */
  private volatile io.justsearch.agent.api.registry.AutonomyLevel autonomyLevel =
      io.justsearch.agent.api.registry.AutonomyLevel.DEFAULT;

  io.justsearch.agent.api.registry.AutonomyLevel autonomyLevel() {
    return autonomyLevel;
  }

  void setAutonomyLevel(io.justsearch.agent.api.registry.AutonomyLevel level) {
    if (level != null) {
      this.autonomyLevel = level;
    }
  }

  /**
   * Tempdoc 565 §30 — the human's mid-run STEERING directive (the DIRECTION authority's
   * {@code interject} value). An external {@code POST /api/chat/agent/steer} queues it; the loop
   * DRAINS it (read-and-clear, exactly-once) at the next step boundary and folds the text into the
   * next LLM call as a steering note. Mirrors {@link #autonomyLevel} (the {@code set-posture}
   * directive) — both are per-run control inputs an external thread writes and the loop reads
   * between steps; an {@link AtomicReference} gives the drain its exactly-once semantics.
   */
  private final AtomicReference<String> pendingInterject = new AtomicReference<>(null);

  /** Read-and-clear the pending steering directive; {@code null} if none queued. */
  String drainInterject() {
    return pendingInterject.getAndSet(null);
  }

  /** Queue a mid-run steering directive (no-op on blank). */
  void setInterject(String text) {
    if (text != null && !text.isBlank()) {
      pendingInterject.set(text.trim());
    }
  }

  void cancel() {
    cancelled = true;
    approvalGates.values().forEach(f -> f.complete(false));
    // §13.5 Phase B — cancel pending virtual-tool waits with a
    // structured cancelled result so the loop unblocks promptly.
    virtualToolFutures.values().forEach(
        f -> f.complete(VirtualToolResult.failure("session cancelled")));
    virtualToolFutures.clear();
  }

  int iterationsUsed() {
    return iterationsUsed;
  }

  void incrementIterations() {
    iterationsUsed++;
  }

  int toolCallsExecuted() {
    return executedTools.size();
  }

  /** Tempdoc 603 D-3 — the chunk-identity-absent sentinel: a document-level source carries no chunk
   *  ordinal and no precise line. {@code -1} (not {@code 0}, a valid first-chunk ordinal / first line)
   *  marks "provenance only" so the FE can classify it (the SOURCED frame) and suppress the precise-line
   *  deep-link/locator (the highlight keys on {@code startLine >= 0}). */
  static final int DOC_LEVEL_SENTINEL = -1;

  /**
   * Tempdoc 565 §3.A + 603 D-3 — collect the answer's grounding sources from this run's tool results.
   * Reads the structured search evidence ({@code searchResults}, from {@code SearchTool
   * .buildSearchEvidence}), preserving first-seen order. These become the clickable local-passage
   * citations on {@link AgentEvent.AgentDone}.
   *
   * <p><b>Provenance vs precision (603 D-3).</b> A grounding source's IDENTITY is the DOCUMENT it came
   * from; chunk identity ({@code parentDocId}+{@code chunkIndex}) is OPTIONAL ENRICHMENT that upgrades a
   * source to a line-precise, matcher-eligible passage. Source EXISTENCE is never gated on that
   * enrichment. So:
   * <ul>
   *   <li><b>chunk-precise</b> hit ({@code parentDocId} present) → keyed by {@code parentDocId#chunkIndex},
   *       carries its real chunk ordinal + line span (deep-links to the exact lines; the answer↔source
   *       matcher can add inline marks).
   *   <li><b>document-level</b> hit (no {@code parentDocId} — e.g. the main BM25/keyword pipeline under
   *       BLOCKED_LEGACY returns whole-doc hits whose stored fields lack the chunk-only {@code
   *       parent_doc_id}) → still a real source the answer drew on; keyed by its {@code path}, emitted
   *       with the {@link #DOC_LEVEL_SENTINEL} for chunk ordinal + lines (deep-links to the file top, no
   *       inline marks). Previously these were DROPPED, leaving the Sources pane falsely empty while the
   *       answer cited them (tempdoc 603 D-1).
   * </ul>
   * The run is genuinely ungrounded only when it did not search / a hit carried neither a {@code
   * parentDocId} nor a {@code path}.
   */
  List<AgentEvent.AgentSource> collectGroundingSources() {
    var seen = new LinkedHashSet<String>();
    var out = new ArrayList<AgentEvent.AgentSource>();
    int totalSearchHits = 0;
    for (ExecutedToolCall ex : executedTools) {
      Map<String, Object> data = ex.result() == null ? Map.of() : ex.result().structuredData();
      if (!(data.get("searchResults") instanceof List<?> results)) {
        continue;
      }
      totalSearchHits += results.size();
      for (Object o : results) {
        if (!(o instanceof Map<?, ?> m)) {
          continue;
        }
        String path = asString(m.get("path"));
        boolean chunkPrecise =
            m.get("parentDocId") instanceof String pd && !pd.isBlank();
        if (chunkPrecise) {
          String parentDocId = (String) m.get("parentDocId");
          int chunkIndex = m.get("chunkIndex") instanceof Number n ? n.intValue() : 0;
          if (!seen.add(parentDocId + "#" + chunkIndex)) {
            continue; // dedup repeated sources across turns
          }
          out.add(
              new AgentEvent.AgentSource(
                  parentDocId,
                  chunkIndex,
                  path,
                  asString(m.get("title")),
                  asString(m.get("excerpt")),
                  asInt(m.get("startLine")),
                  asInt(m.get("endLine")),
                  asString(m.get("headingText"))));
        } else if (!path.isBlank()) {
          // 603 D-3 — document-level provenance: identity is the path, chunk ordinal + lines are the
          // sentinel (no precise location). The whole document IS the source the answer drew on.
          if (!seen.add("doc#" + path)) {
            continue; // dedup the same document across hits/turns
          }
          out.add(
              new AgentEvent.AgentSource(
                  path,
                  DOC_LEVEL_SENTINEL,
                  path,
                  asString(m.get("title")),
                  asString(m.get("excerpt")),
                  DOC_LEVEL_SENTINEL,
                  DOC_LEVEL_SENTINEL,
                  asString(m.get("headingText"))));
        }
        // else: neither chunk identity nor a path — not addressable as a source; skipped.
      }
    }
    // Tempdoc 565 §3.A follow-up / 603 D-3 — observability for the now-narrow truly-uncitable case:
    // the run searched (hits exist) but NO hit carried even a path (no chunk identity AND no document
    // identity). With D-3 a path-bearing hit always yields a document-level source, so this WARN no
    // longer fires for the common BLOCKED_LEGACY whole-doc case — only for a genuinely identity-less
    // result (a malformed/stale Worker payload).
    if (totalSearchHits > 0 && out.isEmpty()) {
      LOG.warn(
          "Grounding empty: {} search hit(s) but none were addressable (no parentDocId AND no path);"
              + " the answer will lack source citations — check the search payload or a stale Worker build",
          totalSearchHits);
    }
    return out;
  }

  private static String asString(Object value) {
    return value instanceof String s ? s : "";
  }

  private static int asInt(Object value) {
    return value instanceof Number n ? n.intValue() : 0;
  }

  /** Create an approval gate for a pending tool call. Returns a future that completes on approve/reject. */
  CompletableFuture<Boolean> createApprovalGate(String callId) {
    var gate = new CompletableFuture<Boolean>();
    approvalGates.put(callId, gate);
    return gate;
  }

  /** Approve a pending tool call. Returns whether a gate with that callId existed (was completed). */
  boolean approve(String callId) {
    var gate = approvalGates.remove(callId);
    if (gate != null) {
      gate.complete(true);
      return true;
    }
    return false;
  }

  /** Reject a pending tool call. Returns whether a gate with that callId existed (was completed). */
  boolean reject(String callId) {
    var gate = approvalGates.remove(callId);
    if (gate != null) {
      gate.complete(false);
      return true;
    }
    return false;
  }

  // --- Budget gate (tempdoc 577 §2.12 Move 2) ---

  /** The human's decision at a held budget gate. */
  enum BudgetGateDecision {
    /** Budget was raised (via the raise endpoint) — resume the loop at this boundary. */
    CONTINUE,
    /** Synthesize from what the run has (the budget-edge finalize path). */
    FINALIZE,
    /** Stop the run here (cancel semantics, trigger BUDGET). */
    STOP
  }

  private volatile CompletableFuture<BudgetGateDecision> budgetGate;

  /**
   * Tempdoc 577 Move 2 — park the run at the budget boundary as a HELD decision (the budget
   * analogue of an approval gate). One gate at a time by construction (the loop is single-threaded
   * between iterations). The loop blocks on the returned future; resolution arrives from the raise
   * endpoint (CONTINUE), the decision endpoint (FINALIZE/STOP), or the loop's own timeout.
   */
  CompletableFuture<BudgetGateDecision> createBudgetGate() {
    var gate = new CompletableFuture<BudgetGateDecision>();
    budgetGate = gate;
    return gate;
  }

  /**
   * Resolve a held budget gate. Returns false when no gate is held (the run is not parked) — the
   * endpoint surfaces that as 404, mirroring approve/reject on an unknown callId.
   */
  boolean resolveBudgetGate(BudgetGateDecision decision) {
    var gate = budgetGate;
    if (gate == null || gate.isDone()) {
      return false;
    }
    budgetGate = null;
    gate.complete(decision);
    return true;
  }

  /** Whether a budget gate is currently held (the run is parked awaiting a decision). */
  boolean budgetGateHeld() {
    var gate = budgetGate;
    return gate != null && !gate.isDone();
  }

  /** Clear the gate reference after the loop consumed it (timeout path). */
  void clearBudgetGate() {
    budgetGate = null;
  }

  // --- Context gate (tempdoc 577 §2.14 Root II #14 — the COGNITIVE sibling of the budget gate) ---

  /** The human's decision at a held context-pressure gate. */
  enum ContextGateDecision {
    /** Proceed anyway — send the large prompt as-is (the user accepts the risk). */
    CONTINUE,
    /** Compact older turns to free context headroom, then resume. */
    SUMMARIZE,
    /** Stop the run here. */
    STOP
  }

  private volatile CompletableFuture<ContextGateDecision> contextGate;
  // The context gate fires AT MOST ONCE per run: once the user decides (continue/summarize), the run
  // is not re-parked every iteration. A renewed pressure spike after a summarize is covered by the
  // hard budget gate. Loop-thread-only, so a plain boolean suffices.
  private boolean contextGateFired = false;

  /**
   * Tempdoc 577 §2.14 Root II — park the run at the context-pressure boundary as a HELD decision (the
   * cognitive analogue of the budget gate). One gate at a time; the loop blocks on the returned
   * future until the context-decision endpoint resolves it or the loop's timeout fires.
   */
  CompletableFuture<ContextGateDecision> createContextGate() {
    var gate = new CompletableFuture<ContextGateDecision>();
    contextGate = gate;
    contextGateFired = true;
    return gate;
  }

  /** Resolve a held context gate. Returns false when no gate is held (surfaced as 404). */
  boolean resolveContextGate(ContextGateDecision decision) {
    var gate = contextGate;
    if (gate == null || gate.isDone()) {
      return false;
    }
    contextGate = null;
    gate.complete(decision);
    return true;
  }

  /** Whether a context gate is currently held (the run is parked awaiting a decision). */
  boolean contextGateHeld() {
    var gate = contextGate;
    return gate != null && !gate.isDone();
  }

  /** Whether the context gate has already fired this run (park at most once). */
  boolean contextGateFired() {
    return contextGateFired;
  }

  /** Clear the gate reference after the loop consumed it (timeout path). */
  void clearContextGate() {
    contextGate = null;
  }

  /**
   * Tempdoc 577 §2.14 Root II — compact older conversation turns to free context headroom (the
   * SUMMARIZE decision). Structurally: drop the OLDEST non-system, non-final messages, preserving the
   * system prompt (index 0 when present) and the most recent {@code keepRecent} messages (the live
   * working set). Returns the number of messages dropped, so the caller can narrate the compaction
   * honestly. Loop-thread-only (called between iterations); no concurrent mutation.
   */
  synchronized int compactOlderTurns(int keepRecent) {
    int size = messages.size();
    // Preserve a leading system message (role=system at index 0) as an anchor.
    int start = (size > 0 && "system".equals(messages.get(0).get("role"))) ? 1 : 0;
    int dropEnd = size - Math.max(0, keepRecent);
    if (dropEnd <= start) {
      return 0; // nothing compactable (the working set already fits the keep-window)
    }
    int dropped = dropEnd - start;
    messages.subList(start, dropEnd).clear();
    return dropped;
  }

  /**
   * Tempdoc 508 §11.5 / §13.5 Phase B — register a future for a
   * pending virtual-tool invocation. Returned future completes when
   * the FE POSTs the result OR when {@link #completeVirtualTool} is
   * called with a synthetic result (e.g., timeout, FE not subscribed).
   */
  CompletableFuture<VirtualToolResult> registerVirtualToolGate(String callId) {
    var future = new CompletableFuture<VirtualToolResult>();
    virtualToolFutures.put(callId, future);
    return future;
  }

  /**
   * Complete a pending virtual-tool future with the FE-supplied
   * (or synthetic) result. Returns true when the future was found
   * and completed; false when no pending call had that callId (which
   * the caller can surface as 404).
   */
  boolean completeVirtualTool(String callId, VirtualToolResult result) {
    var future = virtualToolFutures.remove(callId);
    if (future == null) return false;
    future.complete(result);
    return true;
  }

  /**
   * Result envelope for {@link #completeVirtualTool}. Mirrors the
   * fields the FE POSTs at {@code /api/chat/agent/tool-result}.
   * Success carries the captured output string; failure carries the
   * detail.
   */
  record VirtualToolResult(boolean success, String output, String errorDetail) {
    public static VirtualToolResult success(String output) {
      return new VirtualToolResult(true, output, null);
    }

    public static VirtualToolResult failure(String detail) {
      return new VirtualToolResult(false, null, detail);
    }
  }

  /** Record a tool execution and track consecutive identical calls. */
  void recordExecution(ToolCallRequest call, OperationResult result) {
    executedTools.add(new ExecutedToolCall(call, result));
    String signature = call.toolName() + ":" + normalizeArgs(call.arguments());
    if (signature.equals(lastCallSignature)) {
      consecutiveIdenticalCalls++;
    } else {
      lastCallSignature = signature;
      consecutiveIdenticalCalls = 1;
    }
  }

  /** Returns how many times the same (tool, args) pair has been called consecutively. */
  int consecutiveIdenticalCalls() {
    return consecutiveIdenticalCalls;
  }

  /**
   * Peek method: returns true if executing this call would cause consecutive identical calls to
   * reach or exceed the given threshold. Does NOT mutate state.
   */
  boolean wouldExceedLoopThreshold(ToolCallRequest call, int threshold) {
    String signature = call.toolName() + ":" + normalizeArgs(call.arguments());
    if (signature.equals(lastCallSignature)) {
      return (consecutiveIdenticalCalls + 1) >= threshold;
    }
    return false; // Different call — would reset to 1
  }

  /**
   * Records a blocked call: increments consecutive identical call count and loop block count
   * without adding to the executed tools list.
   */
  void recordBlockedCall(ToolCallRequest call) {
    String signature = call.toolName() + ":" + normalizeArgs(call.arguments());
    if (signature.equals(lastCallSignature)) {
      consecutiveIdenticalCalls++;
    } else {
      lastCallSignature = signature;
      consecutiveIdenticalCalls = 1;
    }
    loopBlockCount++;
  }

  /** Returns the total number of loop-blocked calls across the session. */
  int loopBlockCount() {
    return loopBlockCount;
  }

  /** Returns true if any executed tool result was successful. */
  boolean hasSuccessfulToolResult() {
    return executedTools.stream().anyMatch(e -> e.result().success());
  }

  private static String normalizeArgs(String json) {
    if (json == null || json.isBlank()) return "";
    try {
      // Parse into generic Java types (LinkedHashMap for objects), then re-serialize.
      // ORDER_MAP_ENTRIES_BY_KEYS sorts map keys during writeValueAsString, producing
      // canonical JSON regardless of the original key order.
      Object value = NORM_MAPPER.readValue(json, Object.class);
      return NORM_MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      return json.strip(); // fallback for non-JSON arguments
    }
  }

  /** Append a message to the conversation. */
  void appendMessage(Map<String, Object> message) {
    messages.add(message);
  }

  // Multi-agent handoff accessors

  String activeAgentId() {
    return activeAgentId;
  }

  /** Number of LLM calls the current agent has made since the last handoff (or session start). */
  int agentIterationsSinceHandoff() {
    return agentIterationsSinceHandoff;
  }

  void incrementAgentIterations() {
    agentIterationsSinceHandoff++;
  }

  List<Map<String, Object>> handoffHistory() {
    return Collections.unmodifiableList(handoffHistory);
  }

  /** Updates the active agent cursor and appends an entry to the handoff history. */
  void recordHandoff(String fromAgentId, String toAgentId, String reason) {
    activeAgentId = toAgentId;
    // Reset loop guard state — the new agent starts with a clean slate
    lastCallSignature = null;
    consecutiveIdenticalCalls = 0;
    loopBlockCount = 0;
    agentIterationsSinceHandoff = 0;
    var entry = new LinkedHashMap<String, Object>();
    entry.put("fromAgentId", fromAgentId);
    entry.put("toAgentId", toAgentId);
    entry.put("reason", reason);
    entry.put("timestamp", Instant.now().toString());
    handoffHistory.add(entry);
  }

  /**
   * Increments the total handoff count and returns the new total. Used by
   * {@link AgentLoopService} to detect runaway handoff cycles before executing a handoff.
   */
  int incrementTotalHandoffs() {
    return ++totalHandoffs;
  }

  /**
   * Clears all pending approval gates by completing them with {@code false}.
   *
   * <p>Called on handoff to enforce the approval boundary: approvals granted to the previous agent
   * role must not carry over to the new role. Unlike {@link #cancel()}, this does not set the
   * cancelled flag — the loop continues under the new agent.
   *
   * <p>In the current sequential inner-loop architecture, this is always a no-op at the handoff
   * point because each tool call in a batch is processed (and its gate resolved) before the loop
   * advances to the next call. No gates are in-flight when a handoff fires. The call is retained
   * as a defensive safeguard against future loop restructuring.
   */
  void clearPendingApprovals() {
    approvalGates.values().forEach(f -> f.complete(false));
    approvalGates.clear();
  }

  // Budget tracking accessors (thread-safe)

  /** Returns the remaining token budget. */
  int budgetRemaining() {
    return budgetRemaining.get();
  }

  /**
   * Tempdoc 577 Ext III — the raise-budget remedy: grant this run additional tokens mid-flight.
   * Thread-safe; the loop's between-step budget check and the next {@code AgentBudgetUpdate} pick up
   * the raised remaining naturally (no special-case resume path).
   */
  void addBudget(int tokens) {
    if (tokens > 0) {
      budgetRemaining.addAndGet(tokens);
    }
  }

  /** Returns the total tokens consumed (prompt + completion). Thread-safe atomic snapshot. */
  synchronized int totalTokens() {
    return promptTokensConsumed.get() + completionTokensConsumed.get();
  }

  /** Tempdoc 577 §2.14 Root II — the model's context window (n_ctx); 0 when unknown. */
  int contextWindow() {
    return contextWindow;
  }

  /** Tempdoc 577 §2.14 Root II — set the context window once at run start (AgentLoopService). */
  void contextWindow(int n) {
    this.contextWindow = Math.max(0, n);
  }

  // --- Run event hub (tempdoc 577 §2.14 Root I #13 — the run as an observed entity) ---

  /** The session-local event hub the loop publishes to and observers subscribe to. */
  RunEventHub eventHub() {
    return eventHub;
  }

  /** How many observers are currently attached to this run (the zero-observer policy reads this). */
  int observerCount() {
    return eventHub.observerCount();
  }

  /** Tempdoc 577 §2.14 Root I (#13) — what a run with NO observer does at its next decision point. */
  enum ZeroObserverPolicy {
    /** Park and await a re-attach before proceeding (a Watch run must not run unsupervised). */
    PARK,
    /** Proceed — the run's posture-graded gates already self-arbitrate without a watcher. */
    PROCEED
  }

  /**
   * Tempdoc 577 §2.14 Root I (#13) — the run's zero-observer behavior, a POSTURE-GRADED property of
   * the run entity (not a hidden default). A Watch run whose observer dropped PARKS — it must not
   * barrel ahead unsupervised; a re-attach resumes supervision (the approval gate is the park point,
   * reusing the held-gate machinery). Assist/Auto runs PROCEED: their gates already arbitrate by
   * posture, so a missing watcher changes nothing. With an observer present, always PROCEED.
   */
  ZeroObserverPolicy zeroObserverPolicy() {
    if (observerCount() > 0) {
      return ZeroObserverPolicy.PROCEED;
    }
    return autonomyLevel == io.justsearch.agent.api.registry.AutonomyLevel.WATCH
        ? ZeroObserverPolicy.PARK
        : ZeroObserverPolicy.PROCEED;
  }

  /**
   * Records token usage from an LLM response. Thread-safe - can be called from any thread.
   *
   * @param promptTokens tokens used for the prompt (may be null)
   * @param completionTokens tokens used for the completion (may be null)
   */
  void recordUsage(Integer promptTokens, Integer completionTokens) {
    if (promptTokens != null) {
      this.promptTokensConsumed.addAndGet(promptTokens);
      this.budgetRemaining.addAndGet(-promptTokens);
    }
    if (completionTokens != null) {
      this.completionTokensConsumed.addAndGet(completionTokens);
      this.budgetRemaining.addAndGet(-completionTokens);
    }
  }

  record ExecutedToolCall(ToolCallRequest call, OperationResult result) {}

  // ---------------------------------------------------------------------------
  // Tempdoc 415: session-lifecycle observability accessors
  // ---------------------------------------------------------------------------

  /**
   * Records the typed termination reason. Idempotent: a second call logs a WARN and returns
   * without overwriting the captured reason. Called from the loop-owning thread before each
   * {@code return;} that ends the session, so the {@code finally{}} block can read the reason
   * for both the metric emit and the {@code AgentRunStore.setTerminationReason} patch.
   */
  void markTerminated(TerminalDisposition d, AgentErrorCode code, CancelTrigger trigger) {
    Objects.requireNonNull(d, "disposition");
    if (terminated) {
      LOG.warn(
          "markTerminated called twice (existing={}, new={}); keeping existing.", disposition, d);
      return;
    }
    this.disposition = d;
    this.terminationCode = code;
    this.cancelTrigger = trigger;
    this.terminated = true;
  }

  boolean isTerminated() {
    return terminated;
  }

  TerminalDisposition disposition() {
    return disposition;
  }

  AgentErrorCode terminationCode() {
    return terminationCode;
  }

  CancelTrigger cancelTrigger() {
    return cancelTrigger;
  }

  /** Wall-clock duration since session creation. */
  long durationMs() {
    return Duration.between(startedAt, Instant.now()).toMillis();
  }

  /**
   * Sum of UTF-8 byte sizes of every persisted message in the conversation. Computed on demand
   * (called once per session in the loop's {@code finally{}}); per-message exceptions are
   * swallowed and count as 0 bytes for that message, matching {@link #normalizeArgs}'s defensive
   * style.
   */
  int contextSizeBytes() {
    long total = 0L;
    for (Map<String, Object> message : messages) {
      try {
        total += NORM_MAPPER.writeValueAsString(message).getBytes(StandardCharsets.UTF_8).length;
      } catch (Exception ignored) {
        // Skip un-serializable messages; never fail telemetry on a serialization edge case.
      }
    }
    return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
  }
}
