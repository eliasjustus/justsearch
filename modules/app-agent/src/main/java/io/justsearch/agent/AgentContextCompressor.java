/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/**
 * Tool-message context compression for the agent loop (tempdoc 240 W2 — extracted
 * from {@code AgentLoopService}). Keeps the agent's conversation within the token
 * budget by compressing older tool results:
 *
 * <ul>
 *   <li>{@link #truncate(String)} — Layer-2 hard cut at {@code MAX_TOOL_RESULT_CHARS};
 *   <li>{@link #stripSearchExcerpts(String)} — drops the longest per-result field;
 *   <li>{@link #compressToolMessages(List)} — Layer-3: compresses all but the last
 *       {@code keepLastResults} tool messages each iteration.
 * </ul>
 */
final class AgentContextCompressor {

  /**
   * Tempdoc 865 §7.5 — THE RECEIPT: which tool calls' result excerpts are still in the prompt this
   * pass produced, and which no longer are. Keyed by {@code tool_call_id}, which is what makes the
   * join to grounding sources possible at all: compression copies the message map and replaces only
   * {@code content} ({@link #compressToolMessages}), so the link from a compressed message back to
   * its tool call — and thence to the sources that call minted — survives untouched.
   *
   * <p>It is a RECEIPT, not a re-derivation: the compressor states what it left standing, read off
   * the very message list it just wrote, using its own {@code Excerpt:} line pattern. The decision
   * is therefore self-evidencing — whether a given message still carries result excerpts is
   * decidable from the artifact itself, with no bookkeeping to fall out of sync.
   *
   * <p>Every pass reports on EVERY tool message, kept and compressed alike, so one receipt is a
   * complete picture of a single prompt rather than a running tally. That is what makes the state
   * PER-FINAL-PROMPT: a source whose text was stripped at iteration 5 and re-delivered by a search
   * at iteration 9 has an intact carrier again, and the latest receipt says so.
   *
   * @param excerptsIntact tool calls whose message still carries {@code Excerpt:} lines
   * @param excerptsStripped tool calls whose message no longer does
   */
  record CompressionReceipt(Set<String> excerptsIntact, Set<String> excerptsStripped) {

    /** No pass has run, so nothing is known about any prompt. Consumers must say nothing. */
    static final CompressionReceipt NONE = new CompressionReceipt(Set.of(), Set.of());

    CompressionReceipt {
      excerptsIntact = Set.copyOf(excerptsIntact);
      excerptsStripped = Set.copyOf(excerptsStripped);
    }

    /** True when this receipt describes a real prompt (at least one tool message was seen). */
    boolean observed() {
      return !excerptsIntact.isEmpty() || !excerptsStripped.isEmpty();
    }

    /** True when this tool call's message still carries its result excerpts. */
    boolean intact(String toolCallId) {
      return excerptsIntact.contains(toolCallId);
    }
  }

  /** Per-tool-result hard cap. See AgentLoopService's three-layer truncation note. */
  static final int MAX_TOOL_RESULT_CHARS =
      Math.max(100, resolveInt(rc -> rc.agent().maxToolResultChars(), 4000));

  private static final Pattern EXCERPT_LINE =
      Pattern.compile("^\\s+Excerpt:.*$", Pattern.MULTILINE);
  private static final Set<String> COMPRESSION_KEYWORDS =
      Set.of("error", "warning", "failed", "result", "path", "match", "id");

  private final boolean enabled;
  private final int minChars;
  private final int keepLastResults;

  AgentContextCompressor(boolean enabled, int minChars, int keepLastResults) {
    this.enabled = enabled;
    this.minChars = minChars;
    this.keepLastResults = keepLastResults;
  }

  /** Layer-2: hard-truncate a single tool result that exceeds the per-result cap. */
  static String truncate(String output) {
    if (output == null || output.length() <= MAX_TOOL_RESULT_CHARS) {
      return output;
    }
    return output.substring(0, MAX_TOOL_RESULT_CHARS)
        + "\n[... truncated, " + (output.length() - MAX_TOOL_RESULT_CHARS) + " chars omitted]";
  }

  /**
   * Layer-3: compress all but the last {@code keepLastResults} tool messages in place.
   *
   * <p>Tempdoc 865 §7.5 — returns the {@link CompressionReceipt} for the message list it leaves
   * behind. Every early return still reports: compression being disabled, or the run being too
   * short to compress, are answers about the prompt ("nothing was stripped"), not an absence of
   * one, and a consumer that could not tell those apart would have to say nothing in both cases.
   */
  CompressionReceipt compressToolMessages(List<Map<String, Object>> messages) {
    if (!enabled || messages == null || messages.isEmpty()) {
      return receiptFor(messages);
    }

    List<Integer> toolMessageIndexes = new ArrayList<>();
    for (int i = 0; i < messages.size(); i++) {
      Object role = messages.get(i).get("role");
      if ("tool".equals(role)) {
        toolMessageIndexes.add(i);
      }
    }

    if (toolMessageIndexes.size() <= keepLastResults) {
      return receiptFor(messages);
    }

    int compressCount = toolMessageIndexes.size() - keepLastResults;
    for (int n = 0; n < compressCount; n++) {
      int messageIndex = toolMessageIndexes.get(n);
      Map<String, Object> message = messages.get(messageIndex);
      Object contentValue = message.get("content");
      if (!(contentValue instanceof String content)) {
        continue;
      }
      String compressed = compressToolOutput(stripSearchExcerpts(content));
      if (compressed.equals(content)) {
        continue;
      }
      var replacement = new LinkedHashMap<String, Object>(message);
      replacement.put("content", compressed);
      messages.set(messageIndex, replacement);
    }
    return receiptFor(messages);
  }

  /**
   * Tempdoc 865 §7.5 — read the receipt off a message list: a tool message that still matches
   * {@link #EXCERPT_LINE} carries its result excerpts, one that does not has lost them.
   *
   * <p>Reading the RESULT rather than diffing the pass is deliberate. A message compressed in an
   * earlier iteration is not touched again (the {@code [compressed-tool-output} guard in {@link
   * #compressToolOutput} returns it unchanged), so a per-pass diff would report it as untouched and
   * therefore intact — the exact inversion of the truth. The artifact cannot lie that way.
   *
   * <p>A tool message that never carried excerpts (a non-search tool) reports as stripped. That
   * costs nothing: such a call minted no grounding source, so no source ever joins to it.
   */
  private static CompressionReceipt receiptFor(List<Map<String, Object>> messages) {
    if (messages == null || messages.isEmpty()) {
      return CompressionReceipt.NONE;
    }
    var intact = new LinkedHashSet<String>();
    var stripped = new LinkedHashSet<String>();
    for (Map<String, Object> message : messages) {
      if (!"tool".equals(message.get("role"))
          || !(message.get("tool_call_id") instanceof String id)
          || id.isBlank()) {
        continue;
      }
      String content = message.get("content") instanceof String s ? s : "";
      if (EXCERPT_LINE.matcher(content).find()) {
        intact.add(id);
      } else {
        stripped.add(id);
      }
    }
    // One call id can only carry one message, but assert the partition rather than assume it: an
    // id in both sets would make `intact` ambiguous, and intact-wins is the say-less answer.
    stripped.removeAll(intact);
    return new CompressionReceipt(intact, stripped);
  }

  /**
   * Search-specific compression: strips excerpt lines from search_index output. Excerpts are the
   * longest per-result field (~200 chars each) and are only useful for the current iteration.
   */
  static String stripSearchExcerpts(String content) {
    if (content == null || !content.contains("Excerpt:")) {
      return content;
    }
    return EXCERPT_LINE.matcher(content).replaceAll("").replaceAll("\n{2,}", "\n");
  }

  private String compressToolOutput(String content) {
    if (content == null
        || content.length() < minChars
        || content.startsWith("[compressed-tool-output")) {
      return content;
    }

    String normalized = content.replace("\r\n", "\n");
    String[] lines = normalized.split("\n");
    var selected = new ArrayList<String>();
    var seen = new LinkedHashSet<String>();

    collectFirstLines(lines, selected, seen, 3);
    collectKeywordLines(lines, selected, seen, 3);
    collectLastLines(lines, selected, seen, 2);

    String kept = String.join("\n", selected).strip();
    if (kept.isBlank()) {
      kept = normalized.substring(0, Math.min(400, normalized.length())).strip();
    }
    int budget = Math.min(400, Math.max(150, normalized.length() / 5));
    if (kept.length() > budget) {
      kept = kept.substring(0, budget).strip();
    }

    String compressed =
        String.format(
            "[compressed-tool-output originalChars=%d keptChars=%d]%n%s",
            content.length(),
            kept.length(),
            kept);
    return compressed.length() < content.length() ? compressed : content;
  }

  private static void collectFirstLines(
      String[] lines, List<String> out, Set<String> seen, int maxLines) {
    for (String line : lines) {
      if (out.size() >= maxLines) {
        return;
      }
      addLine(out, seen, line);
    }
  }

  private static void collectKeywordLines(
      String[] lines, List<String> out, Set<String> seen, int maxLines) {
    for (String line : lines) {
      if (out.size() >= maxLines + 3) {
        return;
      }
      String lower = line.toLowerCase(Locale.ROOT);
      boolean hasKeyword = COMPRESSION_KEYWORDS.stream().anyMatch(lower::contains);
      if (hasKeyword) {
        addLine(out, seen, line);
      }
    }
  }

  private static void collectLastLines(
      String[] lines, List<String> out, Set<String> seen, int maxLines) {
    var tail = new ArrayList<String>();
    for (int i = lines.length - 1; i >= 0 && maxLines > 0; i--) {
      String trimmed = lines[i] == null ? "" : lines[i].strip();
      if (trimmed.isEmpty() || seen.contains(trimmed)) {
        continue;
      }
      tail.add(trimmed);
      maxLines--;
    }
    Collections.reverse(tail);
    for (String line : tail) {
      addLine(out, seen, line);
    }
  }

  private static boolean addLine(List<String> out, Set<String> seen, String line) {
    if (line == null) {
      return false;
    }
    String trimmed = line.strip();
    if (trimmed.isEmpty() || !seen.add(trimmed)) {
      return false;
    }
    out.add(trimmed);
    return true;
  }

  private static int resolveInt(ToIntFunction<ResolvedConfig> extractor, int fallback) {
    ConfigStore cs = ConfigStore.globalOrNull();
    return cs != null ? extractor.applyAsInt(cs.get()) : fallback;
  }
}
